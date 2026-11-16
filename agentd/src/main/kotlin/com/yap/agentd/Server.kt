package com.yap.agentd

import com.yap.agentd.AgentdApi.Response
import com.yap.agentd.store.FileStore
import com.yap.app.core.json.JsonParseException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
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
 * - 声明超过路由上限的 body 直接 413，不读进内存：媒体上传（`/v1/media`）放宽到 4 MiB，
 *   其余路由仍是 [AgentdApi.MAX_BODY_BYTES]（1 MiB）；
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
 *
 * 0.12.0 的服务端权威模式在这条链路上加了两种**非一次性响应**（见 [AuthorityRoutes]）：
 * - `GET /v1/events` 长轮询：最多等 25 秒再回 JSON，等待期间占住一个 worker；
 * - `POST /v1/sessions/{id}/messages`：SSE，边生成边写（`Content-Length` 缺失，靠关闭连接表示结束）。
 * 两者都复用同一套 token/限流/请求体上限；SSE 的写失败**不会**中断生成（见 [HttpSseSink] 与
 * `ChatService` 的 `SafeSink`），这是契约里「客户端断开后服务端继续生成并落库」的落点。
 *
 * 0.12.0 的联网搜索 / 网页抓取 / 主动 tick（`/v1/tools/…`、`/v1/proactive/tick`）是普通的一次性
 * JSON 响应，走 [ToolsRoutes]：同样先过 token/限流/1 MiB 上限，不占用流式路径。
 *
 * 媒体（`/v1/media`，M5 上传/下载）走 [MediaRoutes]：它需要**原始字节**（上传）和**原始字节响应**
 * （下载），所以在 body 被 UTF-8 解码之前分流；body 上限单独放宽到 4 MiB，文件大小仍由路由按
 * 2 MiB 拒绝。下载的 304 由 [replyBinary] 写（无 body、只带 ETag/Cache-Control）。
 */
object AgentdServer {

    /** 请求体字节上限：和 handler 层共用同一个常量。 */
    const val MAX_BODY_BYTES = AgentdApi.MAX_BODY_BYTES

    /** 一次请求（请求行 + 头部 + body）的总读时限（毫秒）。 */
    const val READ_TIMEOUT_MS = 5_000L

    /** `POST /v1/backup` 的请求体上限（与客户端 v5 备份的 256 MiB 语义一致）。 */
    const val MAX_BACKUP_BODY_BYTES = 256 * 1024 * 1024

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

    /**
     * `POST /v1/backup` 的 body 读时限（秒）。整份备份可能上百 MB：沿用 5 秒的
     * [READ_TIMEOUT_MS] 会让「慢一点但正常的上传」在读到一半时被 408 打断。
     */
    private const val BACKUP_IMPORT_READ_TIMEOUT_SECONDS = 300L

    /** 备份导出的 chunked 分段大小（每段独立 flush；16 KiB 与 socket 缓冲匹配）。 */
    private const val STREAM_CHUNK_BYTES = 16 * 1024

    private const val ERROR_UNAUTHORIZED =
        """{"error":{"message":"缺少或错误的访问令牌","code":"unauthorized"}}"""
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
    private const val ERROR_STATE_UNAVAILABLE =
        """{"error":{"message":"服务端权威模式未启用：启动时没有配置 --data（数据目录）","code":"state_unavailable"}}"""

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
        rateLimiter: RateLimiter = RateLimiter(),
        services: AgentdServices = AgentdServices()
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
            { acceptLoop(serverSocket, workers, openSockets, token, rateLimiter, services) },
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
        rateLimiter: RateLimiter,
        services: AgentdServices
    ) {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break // 服务被 stop() 关掉
            }
            openSockets.add(socket)
            try {
                workers.execute { handleConnection(socket, token, rateLimiter, openSockets, services) }
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
        openSockets: MutableSet<Socket>,
        services: AgentdServices
    ) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS.toInt()
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MS)
            try {
                handleRequest(socket, input, output, deadlineNanos, token, rateLimiter, services)
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
        rateLimiter: RateLimiter,
        services: AgentdServices
    ) {
        // 先只读到请求头：超时/超限/畸形请求和下面两条「早退」都不需要把 body 读进内存。
        val head = try {
            readHead(input, deadlineNanos)
        } catch (_: RequestTimeoutException) {
            reply(socket, output, 408, ERROR_TIMEOUT)
            return
        } catch (tooLarge: BodyTooLargeException) {
            reply(socket, output, 413, errorTooLarge(tooLarge.limitBytes))
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

        // 备份的两条路径都不允许「整份进内存」，所以必须在 readBody 之前分流：
        // - `GET  /v1/backup`：chunked 流式写响应（边读磁盘边写 socket）；
        // - `POST /v1/backup`：请求体流式落到 `--data` 下的 `.staging/` 临时文件，再增量解析。
        if (isBackupExport(head.method, head.target)) {
            handleBackupExport(socket, output, services, head.http10)
            return
        }
        if (isBackupImport(head.method, head.target)) {
            handleBackupImport(socket, output, input, deadlineNanos, head, services)
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

        // 媒体路由要原始字节（上传）与原始字节响应（下载），必须在 UTF-8 解码之前分流。
        // token/限流已经在上面处理；这里只多一个「媒体路径 4 MiB」的 body 上限（见 readHead）。
        if (MediaRoutes.matches(head.target)) {
            when (val result = MediaRoutes.handle(services, head.method, head.target, head.headers, body)) {
                is MediaRoutes.MediaResult.Json ->
                    reply(socket, output, result.response.status, result.response.body)

                is MediaRoutes.MediaResult.Binary -> replyBinary(socket, output, result)
            }
            return
        }

        val bodyText = String(body, StandardCharsets.UTF_8)

        // 流式响应要在同一根连接上边生成边写，所以先于「一次性 Response」分发。
        if (AuthorityRoutes.isStreaming(head.method, head.target)) {
            handleStreaming(socket, output, head, bodyText, services)
            return
        }

        val response: Response = if (AuthorityRoutes.matches(head.method, head.target)) {
            try {
                AuthorityRoutes.handle(services, head.method, head.target, bodyText)
            } catch (_: Exception) {
                // AuthorityRoutes 自己会兜住所有异常；这里只是防御，不让 handler 的 bug 打挂线程。
                reply(socket, output, 500, ERROR_INTERNAL)
                return
            }
        } else if (ToolsRoutes.matches(head.method, head.target)) {
            // 联网搜索 / 抓取 / 主动 tick：一次性 JSON 响应（搜索与抓取会阻塞到上游返回）。
            // token/限流/1 MiB 上限已经在上面统一处理过，这里不重复。
            try {
                ToolsRoutes.handle(services, head.target, bodyText)
            } catch (_: Exception) {
                // ToolsRoutes 自己会兜住所有异常；这里只是防御，不让 handler 的 bug 打挂线程。
                reply(socket, output, 500, ERROR_INTERNAL)
                return
            }
        } else {
            try {
                AgentdApi.handle(
                    method = head.method,
                    rawPath = head.target,
                    body = bodyText
                )
            } catch (_: Exception) {
                // AgentdApi 自己会兜住所有异常；这里只是防御，不让 handler 的 bug 打挂线程。
                reply(socket, output, 500, ERROR_INTERNAL)
                return
            }
        }
        reply(socket, output, response.status, response.body)
    }

    /**
     * SSE：校验阶段还来得及回普通 JSON 错误；一旦开始写，就只能用 `event: error` 收尾。
     *
     * 注意这里**没有**「客户端断开就 return」的分支：`HttpSseSink` 写失败只标记 broken，
     * `ChatService` 继续生成并落库；本函数返回后 `handleConnection` 的 finally 会关掉连接。
     */
    private fun handleStreaming(
        socket: Socket,
        output: OutputStream,
        head: RequestHead,
        bodyText: String,
        services: AgentdServices
    ) {
        val sink = HttpSseSink(socket, output)
        try {
            AuthorityRoutes.stream(services, head.method, head.target, bodyText, sink)
        } catch (failure: AgentdApi.ApiFailure) {
            sink.fail(failure.status, failure.code, failure.message ?: "请求不合法")
        } catch (invalid: JsonParseException) {
            sink.fail(400, "invalid_json", "请求体不是合法 JSON：${invalid.message.orEmpty()}")
        } catch (unexpected: Exception) {
            sink.fail(500, "internal_error", AgentdApi.internalErrorMessage(unexpected))
        } finally {
            sink.finish()
        }
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
        val contentLength: Long?,
        /** 请求行是 HTTP/1.0：chunked 是 1.1 的特性，这类连接改成「关闭即结束」。 */
        val http10: Boolean
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
        // 媒体上传路由单独放宽到 4 MiB，其它路由仍是 1 MiB（AgentdApi.MAX_BODY_BYTES）。
        val bodyLimit = when {
            MediaRoutes.matches(target) -> MediaRoutes.bodyLimit(target)
            // 批量历史消息导入：200 条一批、单批 ≤8 MiB（与 handler 层同一个常量）。
            CrudRoutes.isMessageImportPath(target.substringBefore('?')) -> CrudRoutes.MAX_IMPORT_BODY_BYTES
            // 备份导入是整份 JSON（含媒体的 base64）：与客户端 v5 的 256 MiB 语义一致。
            target.substringBefore('?') == CrudRoutes.BACKUP_PATH -> MAX_BACKUP_BODY_BYTES
            else -> MAX_BODY_BYTES
        }
        if (contentLength != null && contentLength > bodyLimit) throw BodyTooLargeException(bodyLimit)
        return RequestHead(method, target, headers, contentLength, version.equals("HTTP/1.0", ignoreCase = true))
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

    /**
     * SSE 的 socket 出口：第一帧前写响应头，之后只追加 `event/data` 帧。
     *
     * 三个刻意的选择：
     * - **不写 `Content-Length`**：响应体长度未知，用 `Connection: close` + 半关输出表示结束；
     * - **写失败只标记 broken，不往外抛**：`ChatService` 的生成循环和落库不能被断开的客户端打断；
     * - **失败后不再尝试写**：避免每个 delta 都触发一次异常（客户端已经走了，写也没意义）。
     */
    private class HttpSseSink(
        private val socket: Socket,
        private val output: OutputStream
    ) : SseSink {

        private var started = false
        private var broken = false
        private var done = false

        override fun emit(event: SseEvent) {
            if (broken || done) return
            try {
                if (!started) {
                    writeHead()
                    started = true
                }
                output.write(event.frame().toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } catch (failure: IOException) {
                broken = true
                System.err.println(
                    "[agentd] SSE 写入失败，客户端可能已断开（继续生成并落库）：${failure.message}"
                )
            }
        }

        /** 还没开始写 → 普通 JSON 错误（此时还能给正确的状态码）；已经开始 → `event: error`。 */
        fun fail(status: Int, code: String, message: String) {
            if (broken || done) return
            if (!started) {
                reply(socket, output, status, AgentdApi.errorResponse(status, code, message).body)
                done = true
            } else {
                emit(SseEvent.Error(code, message))
                finish()
            }
        }

        /** 正常收尾：flush + 半关输出，让客户端读到 EOF（没有 Content-Length 时这就是结束信号）。 */
        fun finish() {
            if (done) return
            done = true
            if (!started || broken) return
            runCatching { output.flush() }
            runCatching { socket.shutdownOutput() }
        }

        private fun writeHead() {
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream; charset=utf-8\r\n")
                append("Cache-Control: no-store\r\n")
                append("X-Accel-Buffering: no\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
        }
    }

    // ------------------------------------------------------------------ 备份（流式）

    private fun pathOf(target: String): String = target.substringBefore('?')

    private fun isBackupExport(method: String, target: String): Boolean =
        method.equals("GET", ignoreCase = true) && pathOf(target) == CrudRoutes.BACKUP_PATH

    private fun isBackupImport(method: String, target: String): Boolean =
        method.equals("POST", ignoreCase = true) && pathOf(target) == CrudRoutes.BACKUP_PATH

    /**
     * `GET /v1/backup`：**流式（chunked）**写响应。
     *
     * 为什么不用 Content-Length：长度等于「整份 JSON 的字节数」，只有把整份备份构造出来才知道，
     * 那正是要避免的。chunked 让服务端边读磁盘边写 socket：10 万条消息 + 500 张图时峰值内存
     * 仍是几十 KB（见 `FileStore.writeBackup`）。
     *
     * 错误处理：写响应头之前出错 → 普通 JSON 错误（状态码正确）；**已经开始写之后**出错 → 只能
     * 断开连接（客户端会读到不完整的 JSON 并自己失败），细节只进 stderr。
     */
    private fun handleBackupExport(
        socket: Socket,
        output: OutputStream,
        services: AgentdServices,
        http10: Boolean
    ) {
        val store = services.store
        if (store == null) {
            reply(socket, output, 503, ERROR_STATE_UNAVAILABLE)
            return
        }
        // HTTP/1.0 没有 chunked：不写 `Transfer-Encoding`，靠关闭连接表示结束。
        val useChunked = !http10
        val chunked = if (useChunked) ChunkedOutputStream(output) else null
        val stream: OutputStream = chunked ?: output
        var started = false
        try {
            writeChunkedHead(socket, output, chunked = useChunked)
            started = true
            val writer = OutputStreamWriter(stream, StandardCharsets.UTF_8)
            store.writeBackup(AgentdApi.VERSION, store.serverTime(), writer)
            writer.flush()
            chunked?.finish()
            output.flush()
            socket.shutdownOutput()
        } catch (failure: Exception) {
            // 已经发了 200 + 部分 body：不能再改成错误状态码，断开连接让客户端知道没收全。
            System.err.println(
                "[agentd] 备份导出中断（客户端会看到不完整的 JSON）：" +
                    "${failure::class.qualifiedName}：${failure.message}"
            )
            runCatching { chunked?.abort() }
            if (!started) runCatching { reply(socket, output, 500, ERROR_INTERNAL) }
        }
    }

    /**
     * `POST /v1/backup`：请求体**流式落到临时文件**，再交给
     * [CrudRoutes.importBackupFromFile] 增量解析。
     *
     * 临时文件放在 `<data>/.staging/`（与 `media/` 同一文件系统：导入提交时媒体文件可以
     * 原子 rename 进 `media/`）；无论成功失败都在 finally 里删掉，进程被杀留下的残渣由
     * `FileStore` 启动时清理。
     */
    private fun handleBackupImport(
        socket: Socket,
        output: OutputStream,
        input: InputStream,
        deadlineNanos: Long,
        head: RequestHead,
        services: AgentdServices
    ) {
        val store = services.store
        if (store == null) {
            reply(socket, output, 503, ERROR_STATE_UNAVAILABLE)
            return
        }
        val stagingDir = store.root.resolve(FileStore.STAGING_DIR)
        var temp: Path? = null
        try {
            Files.createDirectories(stagingDir)
            temp = Files.createTempFile(stagingDir, "upload-", ".json")
            val bodyDeadline = System.nanoTime() +
                TimeUnit.SECONDS.toNanos(BACKUP_IMPORT_READ_TIMEOUT_SECONDS)
            readBodyToFile(input, output, maxOf(deadlineNanos, bodyDeadline), head, temp)
            val response = CrudRoutes.importBackupFromFile(store, temp)
            reply(socket, output, response.status, response.body)
        } catch (_: RequestTimeoutException) {
            reply(socket, output, 408, ERROR_TIMEOUT)
        } catch (_: BadRequestException) {
            reply(socket, output, 400, ERROR_BAD_REQUEST)
        } catch (_: IOException) {
            // 客户端提前断开：没有响应可写，直接让外层关连接。
        } finally {
            temp?.let { file -> runCatching { Files.deleteIfExists(file) } }
            runCatching { Files.deleteIfExists(stagingDir) }
        }
    }

    /** 把声明长度的请求体分块写进文件（64 KiB 一段；每段前检查总时限）。 */
    private fun readBodyToFile(
        input: InputStream,
        output: OutputStream,
        deadlineNanos: Long,
        head: RequestHead,
        target: Path
    ) {
        val contentLength = head.contentLength ?: throw BadRequestException()
        if (contentLength == 0L) throw BadRequestException()
        if (head.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
        Files.newOutputStream(target).use { sink ->
            val buffer = ByteArray(64 * 1024)
            var remaining = contentLength
            while (remaining > 0) {
                ensureWithinDeadline(deadlineNanos)
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = try {
                    input.read(buffer, 0, want)
                } catch (_: SocketTimeoutException) {
                    throw RequestTimeoutException()
                }
                if (read < 0) throw BadRequestException() // 客户端提前断开
                sink.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    /** chunked 响应头（HTTP/1.0 客户端不支持 chunked：退化成「关闭连接即结束」）。 */
    private fun writeChunkedHead(socket: Socket, output: OutputStream, chunked: Boolean) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            if (chunked) append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    /**
     * chunked 编码的 `OutputStream`：写满 [STREAM_CHUNK_BYTES] 就发一段，
     * [finish] 发结束段（`0\r\n\r\n`）。
     *
     * 只做「分段」这一件事：不做压缩、不做 keep-alive；配合 `Connection: close`，
     * 即使某些中间层不认 chunked，客户端也能靠 EOF 判断结束。
     */
    private class ChunkedOutputStream(
        private val output: OutputStream,
        private val chunkSize: Int = STREAM_CHUNK_BYTES
    ) : OutputStream() {

        private val buffer = ByteArray(chunkSize)
        private var filled = 0
        private var finished = false

        override fun write(b: Int) {
            if (filled == buffer.size) drain()
            buffer[filled++] = b.toByte()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var position = offset
            var remaining = length
            while (remaining > 0) {
                if (filled == buffer.size) drain()
                val take = minOf(remaining, buffer.size - filled)
                System.arraycopy(bytes, position, buffer, filled, take)
                filled += take
                position += take
                remaining -= take
            }
        }

        override fun flush() {
            drain()
            output.flush()
        }

        /** 正常收尾：发结束段（`0` + 空行）。 */
        fun finish() {
            if (finished) return
            drain()
            finished = true
            output.write("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
        }

        /** 出错时放弃：不再发结束段，让客户端读到「没收全」。 */
        fun abort() {
            finished = true
        }

        private fun drain() {
            if (filled == 0 || finished) return
            output.write(Integer.toHexString(filled).toByteArray(StandardCharsets.ISO_8859_1))
            output.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write(buffer, 0, filled)
            output.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            filled = 0
        }
    }

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

    /**
     * 写媒体下载响应：原始字节 + Content-Type + 每条响应自带的头（Cache-Control/ETag/…）。
     *
     * 304 没有 body（`Content-Length: 0`），也不写 Content-Type；状态行/ETag 仍然要有，
     * 否则客户端无法确认「本地副本就是最新表示」。
     */
    private fun replyBinary(
        socket: Socket,
        output: OutputStream,
        response: MediaRoutes.MediaResult.Binary
    ) {
        runCatching {
            val headers = buildString {
                append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
                if (response.contentType != null) {
                    append("Content-Type: ").append(response.contentType).append("\r\n")
                }
                append("Content-Length: ").append(response.bytes.size).append("\r\n")
                append("Connection: close\r\n")
                for ((name, value) in response.headers) append(name).append(": ").append(value).append("\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
            if (response.bytes.isNotEmpty()) output.write(response.bytes)
            output.flush()
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

    /** 413 的文案带上实际生效的上限：媒体路由 4 MiB、其它路由 1 MiB。 */
    private fun errorTooLarge(limitBytes: Int): String =
        """{"error":{"message":"请求体超过 ${limitBytes / 1024} KiB 上限","code":"content_too_large"}}"""

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Unknown"
    }

    // ------------------------------------------------------------------ 异常与线程

    /** 读超时（映射为 408）。 */
    private class RequestTimeoutException : Exception()

    /** 请求体超过路由对应的上限（映射为 413）。 */
    private class BodyTooLargeException(val limitBytes: Int) : Exception()

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
