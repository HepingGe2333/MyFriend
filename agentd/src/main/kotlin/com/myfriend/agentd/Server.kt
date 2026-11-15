package com.myfriend.agentd

import com.myfriend.agentd.AgentdApi.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `agentd` 的 HTTP 接线：一个**最小**的 HTTP/1.1 实现（`ServerSocket` + 固定线程池 + 每连接 socket 超时）。
 *
 * 为什么不再用 JDK 自带的 `com.sun.net.httpserver.HttpServer`（0.11.0 用的就是它）：
 * 0.11.1 的审计要求「请求体有大小上限、读有超时、半开连接打不瘫 /health」，而这个 API 做不到：
 * - `HttpExchange` 没有读超时；`InputStream.available()` 在它的请求体上还是**不可靠**的
 *   （底层 `BufferedInputStream` 把内部缓冲读完后，`available()` 恒为 0，轮询会误判超时）；
 * - `FixedLengthOutputStream.close()` 会 `drain(64 KiB)` 排空未读完的请求体，
 *   半开连接会**永久阻塞**在 drain 里——而且 `exchange.close()` 解不开它
 *   （`ExchangeImpl.close` 看到输入流已 closed 就跳过，不会关底层 socket）；
 * - 这些行为只能靠 `sun.net.httpserver.drainAmount` / `maxReqTime` 之类的内部属性绕过，
 *   属性还依赖 JDK 内部类的初始化顺序，不可靠也不可测。
 *
 * 所以改成 ~250 行的最小实现，只做我们需要的部分：一次请求一条连接（`Connection: close`），
 * 不做 keep-alive、chunked、TLS、压缩。得到的是**真正的 socket 读超时**和明确的错误路径：
 *
 * - 请求行 / 头部 / body 共用一个总时限 [READ_TIMEOUT_MS]，超时回 408（`SocketTimeoutException`
 *   或多字节慢速滴水都会在总时限处被截断）；
 * - 声明超过 [AgentdApi.MAX_BODY_BYTES] 的 body 直接 413，不读进内存；
 * - `Transfer-Encoding: chunked` 回 411（两端客户端发字符串 body 都带 `Content-Length`；
 *   chunked 没法在总时限内安全地增量解析，干脆不支持）；
 * - 处理请求的是 [WORKER_THREADS] 个固定线程 + 有界队列：慢连接最多占满池子，`/health`
 *   在别的线程上照常响应；队列满了直接关连接（不无限堆积）；
 * - 可选共享 token：所有请求（含 `/health`）要求 `Authorization: Bearer <token>`，
 *   常数时间比较，缺失/错误回 401；
 * - 按来源的令牌桶限流（[RateLimiter]）：超限回 429 + `Retry-After`，`/health` 有自己
 *   的桶（见 [rateLimitKey]），所以业务流量打满配额时探针仍然能立刻拿到 200。
 *
 * 限流与鉴权都在**读完请求头、读 body 之前**判定：超限/未授权请求不会被读进 1 MiB 内存，
 * worker 立刻释放（未读的 body 由 [drainAndClose] 短时间排空后关闭）。
 */
object AgentdServer {

    /** 请求体字节上限：和 handler 层共用同一个常量。 */
    const val MAX_BODY_BYTES = AgentdApi.MAX_BODY_BYTES

    /** 一次请求（请求行 + 头部 + body）的总读时限（毫秒）。 */
    const val READ_TIMEOUT_MS = 5_000L

    /** 处理请求的固定线程数：慢客户端最多占住这些线程，`/health` 仍有线程可用。 */
    const val WORKER_THREADS = 4

    /** 等待处理的连接队列上限；满了就直接关连接，不无限堆积。 */
    private const val CONNECTION_QUEUE_CAPACITY = 64

    /** `ServerSocket` 的 backlog。 */
    private const val BACKLOG = 64

    /** 请求行最大字节数。 */
    private const val MAX_REQUEST_LINE_BYTES = 8 * 1024

    /** 全部请求头合计最大字节数。 */
    private const val MAX_HEADER_BYTES = 16 * 1024

    /** 关闭连接前最多再读掉这么多字节（让客户端先把响应收完，避免 RST 丢包）。 */
    private const val DRAIN_BYTES = 64 * 1024

    /** 关闭连接前的排空时限（毫秒）。 */
    private const val DRAIN_TIMEOUT_MS = 50L

    private const val ERROR_UNAUTHORIZED =
        """{"error":{"message":"缺少或错误的访问令牌","code":"unauthorized"}}"""
    private const val ERROR_TOO_LARGE =
        """{"error":{"message":"请求体超过 1024 KiB 上限","code":"content_too_large"}}"""
    private const val ERROR_TIMEOUT =
        """{"error":{"message":"读取请求体超时","code":"request_timeout"}}"""
    private const val ERROR_LENGTH_REQUIRED =
        """{"error":{"message":"请求体必须带 Content-Length（不支持 chunked）","code":"length_required"}}"""
    private const val ERROR_BAD_REQUEST =
        """{"error":{"message":"请求格式不正确","code":"bad_request"}}"""
    private const val ERROR_INTERNAL =
        """{"error":{"message":"服务内部错误","code":"internal_error"}}"""
    private const val ERROR_RATE_LIMITED =
        """{"error":{"message":"请求过于频繁，请稍后再试","code":"rate_limited"}}"""

    /** 起好的服务：监听 socket + 处理线程池 + accept 线程，`stop()` 时一起关掉。 */
    class StartedServer internal constructor(
        private val serverSocket: ServerSocket,
        private val workers: ThreadPoolExecutor,
        private val acceptor: Thread,
        private val openSockets: MutableSet<Socket>
    ) {
        val port: Int get() = serverSocket.localPort
        val address: InetSocketAddress get() = serverSocket.localSocketAddress as InetSocketAddress

        fun stop(delaySeconds: Int = 0) {
            runCatching { serverSocket.close() }
            runCatching { acceptor.interrupt() }
            // 直接迭代快照，不用 `toList()`：Collection.toList() 在 size == 1 时会自己调一次
            // `iterator().next()`，而 worker 的 finally 可能正好在 size() 与 iterator() 之间
            // 把最后一个 socket 移掉 —— 那会抛 NoSuchElementException 把 stop() 打断。
            val iterator = openSockets.iterator()
            while (iterator.hasNext()) {
                val socket = iterator.next()
                runCatching { socket.close() }
            }
            runCatching { openSockets.clear() }
            workers.shutdownNow()
        }
    }

    /**
     * 启动服务。
     *
     * @param host 绑定地址（默认 `127.0.0.1`，由 `Main` 决定）。
     * @param token 非 null 时启用共享 token 校验（所有路径，包含 `/health`）。
     * @param rateLimiter 按来源的令牌桶；默认 60 请求/分钟、突发 30。
     *   传 `RateLimiter(perMinute = 0, burst = 1)` 即关闭限流。
     */
    fun start(
        host: String,
        port: Int,
        token: String? = null,
        rateLimiter: RateLimiter = RateLimiter()
    ): StartedServer {
        require(host.isNotBlank()) { "host 不能为空" }
        require(token == null || token.isNotBlank()) { "token 不能是空串" }

        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        try {
            serverSocket.bind(InetSocketAddress(host, port), BACKLOG)
        } catch (failure: Exception) {
            runCatching { serverSocket.close() }
            throw failure
        }

        // CopyOnWrite：连接数很少，add/remove 的拷贝成本可以忽略；stop() 遍历时不会被 worker 的
        // 并发 remove 打断（ConcurrentHashMap 的迭代器在「遍历 + 并发删除」下可能抛 NoSuchElement）。
        val openSockets: MutableSet<Socket> = CopyOnWriteArraySet()
        val workers = ThreadPoolExecutor(
            WORKER_THREADS,
            WORKER_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(CONNECTION_QUEUE_CAPACITY),
            daemonThreads("agentd-worker")
        )
        val acceptor = Thread(
            { acceptLoop(serverSocket, workers, openSockets, token, rateLimiter) },
            "agentd-accept"
        ).apply {
            // 非守护线程：`main` 打印完用法就返回了，靠它把 JVM 留在前台（`stop()` / Ctrl-C 退出）。
            isDaemon = false
        }
        acceptor.start()
        return StartedServer(serverSocket, workers, acceptor, openSockets)
    }

    private fun acceptLoop(
        serverSocket: ServerSocket,
        workers: ThreadPoolExecutor,
        openSockets: MutableSet<Socket>,
        token: String?,
        rateLimiter: RateLimiter
    ) {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break // 服务被 stop() 关掉
            }
            openSockets.add(socket)
            try {
                workers.execute { handleConnection(socket, token, rateLimiter, openSockets) }
            } catch (_: RejectedExecutionException) {
                // 队列满了：直接关连接，别让攻击者把内存也堆满。
                openSockets.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    // ------------------------------------------------------------------ 一条连接

    private fun handleConnection(
        socket: Socket,
        token: String?,
        rateLimiter: RateLimiter,
        openSockets: MutableSet<Socket>
    ) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS.toInt()
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MS)
            try {
                handleRequest(socket, input, output, deadlineNanos, token, rateLimiter)
            } finally {
                // 响应已经 flush；先半关输出让客户端读到 EOF，再短时间排空输入（减少 RST 丢响应）。
                drainAndClose(socket, input)
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
        } finally {
            openSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun handleRequest(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        deadlineNanos: Long,
        token: String?,
        rateLimiter: RateLimiter
    ) {
        // 先只读到请求头：超时/超限/畸形请求和下面两条「早退」都不需要把 body 读进内存。
        val head = try {
            readHead(input, deadlineNanos)
        } catch (_: RequestTimeoutException) {
            reply(socket, output, 408, ERROR_TIMEOUT)
            return
        } catch (_: BodyTooLargeException) {
            reply(socket, output, 413, ERROR_TOO_LARGE)
            return
        } catch (_: LengthRequiredException) {
            reply(socket, output, 411, ERROR_LENGTH_REQUIRED)
            return
        } catch (_: BadRequestException) {
            reply(socket, output, 400, ERROR_BAD_REQUEST)
            return
        } catch (_: IOException) {
            return // 客户端提前断开
        }

        // 限流在鉴权之前、读 body 之前：超限客户端拿不到「读 1 MiB + 解析 JSON」的算力，
        // 也挡住拿错误 token 反复试的请求。key 带 scope：`/health` 与业务接口分开记账。
        val client = socket.inetAddress?.hostAddress ?: "unknown"
        val rateDecision = rateLimiter.tryAcquire(rateLimitKey(client, head.target))
        if (!rateDecision.allowed) {
            reply(
                socket, output, 429, ERROR_RATE_LIMITED,
                extraHeaders = mapOf("Retry-After" to rateDecision.retryAfterSeconds.toString())
            )
            return
        }

        if (!authorized(head.headers, token)) {
            reply(socket, output, 401, ERROR_UNAUTHORIZED, extraHeaders = mapOf("WWW-Authenticate" to "Bearer"))
            return
        }

        val body = try {
            readBody(input, output, deadlineNanos, head)
        } catch (_: RequestTimeoutException) {
            reply(socket, output, 408, ERROR_TIMEOUT)
            return
        } catch (_: BadRequestException) {
            reply(socket, output, 400, ERROR_BAD_REQUEST)
            return
        } catch (_: IOException) {
            return // 客户端提前断开
        }

        val response: Response = try {
            AgentdApi.handle(
                method = head.method,
                rawPath = head.target,
                body = String(body, StandardCharsets.UTF_8)
            )
        } catch (_: Exception) {
            // AgentdApi 自己会兜住所有异常；这里只是防御，不让 handler 的 bug 打挂线程。
            reply(socket, output, 500, ERROR_INTERNAL)
            return
        }
        reply(socket, output, response.status, response.body)
    }

    /**
     * 限流的记账 key：**`/health` 单独一个桶**。
     *
     * 取舍：如果 `/health` 和业务接口共用一个桶，一个把配额打满的客户端（或者反向代理后面
     * 折叠成同一个来源 IP 的所有人）会让健康检查也回 429 —— 运维看到的是「服务挂了」，
     * 实际只是配额用尽。分开记账的代价只是一行状态，好处是探针永远拿得到真实状态；
     * 两个桶仍各自受限，`/health` 不会成为绕过限流的口子。
     */
    private fun rateLimitKey(client: String, target: String): String {
        val path = target.substringBefore('?')
        return if (path == "/health") "health|$client" else "core|$client"
    }

    // ------------------------------------------------------------------ 解析请求

    /** 请求行 + 头部（body 之前的部分）。 */
    private data class RequestHead(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val contentLength: Long?
    )

    private fun readHead(input: InputStream, deadlineNanos: Long): RequestHead {
        val requestLine = readLine(input, deadlineNanos, MAX_REQUEST_LINE_BYTES, "请求行")
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw BadRequestException()
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]
        if (method.isEmpty() || target.isEmpty()) throw BadRequestException()
        if (!version.equals("HTTP/1.1", ignoreCase = true) && !version.equals("HTTP/1.0", ignoreCase = true)) {
            throw BadRequestException()
        }

        val headers = LinkedHashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input, deadlineNanos, MAX_HEADER_BYTES, "请求头")
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) throw BadRequestException()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw BadRequestException()
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            // 重复的 Content-Length / Transfer-Encoding 会带来解析歧义（请求走私），直接拒。
            if (headers.containsKey(name)) {
                if (name == "content-length" || name == "transfer-encoding") throw BadRequestException()
                continue
            }
            headers[name] = value
        }

        if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
            throw LengthRequiredException()
        }
        val contentLength = headers["content-length"]?.let { raw ->
            raw.toLongOrNull() ?: throw BadRequestException()
        }
        if (contentLength != null && contentLength < 0) throw BadRequestException()
        if (contentLength != null && contentLength > MAX_BODY_BYTES) throw BodyTooLargeException()
        return RequestHead(method, target, headers, contentLength)
    }

    private fun readBody(
        input: InputStream,
        output: OutputStream,
        deadlineNanos: Long,
        head: RequestHead
    ): ByteArray {
        val contentLength = head.contentLength ?: return ByteArray(0)
        if (contentLength == 0L) return ByteArray(0)

        // curl 对大 body 会发 Expect: 100-continue；先回一个 100，客户端才会开始发 body。
        if (head.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
        val body = ByteArray(contentLength.toInt())
        var read = 0
        while (read < body.size) {
            ensureWithinDeadline(deadlineNanos)
            val n = try {
                input.read(body, read, body.size - read)
            } catch (_: SocketTimeoutException) {
                throw RequestTimeoutException()
            }
            if (n < 0) throw BadRequestException() // 客户端提前断开
            read += n
        }
        return body
    }

    /**
     * 读一行（`\r\n` 或 `\n` 结尾）。
     *
     * 逐字节读是为了在「慢速滴水」时也能按**总时限**截断：每读一个字节都检查一次 deadline，
     * 而不是依赖 socket 的按次超时（那会被「每隔 4 秒发 1 字节」无限续命）。
     */
    private fun readLine(
        input: InputStream,
        deadlineNanos: Long,
        maxBytes: Int,
        what: String
    ): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            ensureWithinDeadline(deadlineNanos)
            val byte = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                throw RequestTimeoutException()
            }
            if (byte < 0) {
                if (buffer.size() == 0) throw EOFException()
                throw BadRequestException()
            }
            if (byte == '\n'.code) {
                val bytes = buffer.toByteArray()
                return if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    String(bytes, 0, bytes.size - 1, StandardCharsets.ISO_8859_1)
                } else {
                    String(bytes, StandardCharsets.ISO_8859_1)
                }
            }
            buffer.write(byte)
            if (buffer.size() > maxBytes) throw BadRequestException("$what 过长")
        }
    }

    private fun ensureWithinDeadline(deadlineNanos: Long) {
        if (System.nanoTime() >= deadlineNanos) throw RequestTimeoutException()
    }

    // ------------------------------------------------------------------ 鉴权

    private fun authorized(headers: Map<String, String>, token: String?): Boolean {
        if (token == null) return true
        val header = headers["authorization"] ?: return false
        val prefix = "Bearer "
        if (!header.startsWith(prefix, ignoreCase = true)) return false
        val provided = header.substring(prefix.length).trim()
        return constantTimeEquals(token, provided)
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8)
        )

    // ------------------------------------------------------------------ 写响应

    private fun reply(
        socket: Socket,
        output: OutputStream,
        status: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        runCatching {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Cache-Control: no-store\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Connection: close\r\n")
                for ((name, value) in extraHeaders) append(name).append(": ").append(value).append("\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
            output.write(bytes)
            output.flush()
            // 先半关输出（发 FIN），客户端 readAll 才能看到 EOF；剩余请求体由 drainAndClose 短时间排空。
            socket.shutdownOutput()
        }
    }

    /** 关闭前短暂排空客户端还没发完的请求体，避免带未读数据的 close 触发 RST 把响应丢掉。 */
    private fun drainAndClose(socket: Socket, input: InputStream) {
        runCatching {
            val previousTimeout = socket.soTimeout
            socket.soTimeout = DRAIN_TIMEOUT_MS.toInt()
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_TIMEOUT_MS)
            val buffer = ByteArray(4096)
            var total = 0
            while (total < DRAIN_BYTES && System.nanoTime() < deadlineNanos) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            socket.soTimeout = previousTimeout
        }
        runCatching { socket.close() }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }

    // ------------------------------------------------------------------ 异常与线程

    /** 读超时（映射为 408）。 */
    private class RequestTimeoutException : Exception()

    /** 请求体超过上限（映射为 413）。 */
    private class BodyTooLargeException : Exception()

    /** 只接受带 `Content-Length` 的 body（映射为 411）。 */
    private class LengthRequiredException : Exception()

    /** 请求格式不对 / 客户端提前断开（映射为 400）。 */
    private class BadRequestException(message: String = "bad request") : Exception(message)

    private fun daemonThreads(name: String): ThreadFactory {
        val counter = AtomicInteger(1)
        return ThreadFactory { runnable ->
            Thread(runnable, "$name-${counter.getAndIncrement()}").apply { isDaemon = true }
        }
    }
}
