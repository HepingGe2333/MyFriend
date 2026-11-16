package com.yap.agentd.search

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** 一个 provider 的请求：只有「GET 带查询参数」和「POST 一段 JSON」两种形状。 */
sealed interface SearchCall {
    data class Get(val url: String, val headers: Map<String, String> = emptyMap()) : SearchCall

    data class PostJson(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val body: String
    ) : SearchCall
}

/**
 * 服务端的 HTTP 客户端：只用 JDK 自带的 [HttpClient]（`agentd` 的硬约束是零第三方依赖）。
 *
 * 四条硬规则（`spec/web-search/README.md` §4/§5）：
 * 1. 连接与请求超时各 10 秒；
 * 2. 成功响应最多读 2 MiB，超了就是 [SearchErrorKind.RESPONSE_TOO_LARGE]（不把整段第三方返回读进内存）；
 * 3. `User-Agent: Yap/1.0`；
 * 4. 401/403/429/5xx 映射成带 provider 与状态码的 [SearchError]；原始响应体只进日志、最多 200 字符，
 *    而且**日志里永远不会出现 key**（[execute] 收到 secret 后在预览里替换成 `***`）。
 *
 * 超时、DNS、连接中断这些「网络层的事」也在这里收敛成一个 [SearchException]，
 * provider 实现只关心「怎么拼请求、怎么解析 JSON」。
 */
class SearchHttpClient(
    private val client: HttpClient = defaultClient(),
    private val timeoutMs: Long = SearchLimits.REQUEST_TIMEOUT_MS,
    private val maxResponseBytes: Int = SearchLimits.MAX_RESPONSE_BYTES,
    private val log: (String) -> Unit = { System.err.println(it) }
) {

    /**
     * 执行一次请求，返回响应体（≤ [maxResponseBytes] 字节，按 UTF-8 解码）。
     *
     * @param secret provider 的 key：**只用来在日志预览里把它替换成 `***`**，绝不进请求体/异常文案。
     */
    fun execute(call: SearchCall, provider: String, secret: String = ""): String {
        val request = try {
            buildRequest(call)
        } catch (invalid: IllegalArgumentException) {
            // URI 拼坏了（base 里有空格/中文/非法字符）：这是配置问题，不是网络问题。
            throw SearchException(
                SearchError(SearchErrorKind.INVALID_BASE, provider, null, "$provider 的地址不是合法 URL")
            )
        }
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (timeout: HttpTimeoutException) {
            throw SearchException(
                SearchError(SearchErrorKind.TIMEOUT, provider, null, "$provider 请求超时（${timeoutMs / 1000} 秒）")
            )
        } catch (unknownHost: UnknownHostException) {
            throw SearchException(
                SearchError(SearchErrorKind.UNKNOWN_HOST, provider, null, "$provider 的地址解析不到")
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SearchException(
                SearchError(SearchErrorKind.NETWORK, provider, null, "$provider 请求被中断")
            )
        } catch (e: ConnectException) {
            throw SearchException(
                SearchError(SearchErrorKind.NETWORK, provider, null, "$provider 连接失败")
            )
        } catch (e: java.io.IOException) {
            throw SearchException(
                SearchError(SearchErrorKind.NETWORK, provider, null, "$provider 网络不可用")
            )
        }

        val status = response.statusCode()
        val stream = response.body()
        try {
            if (status !in 200..299) {
                val preview = redact(readAtMost(stream, SearchLimits.MAX_LOGGED_BODY_CHARS), secret)
                log("[search] provider=$provider status=$status body=${preview.replace('\n', ' ')}")
                throw SearchException(statusError(provider, status))
            }
            return readBounded(stream, provider)
        } finally {
            runCatching { stream.close() }
        }
    }

    // ------------------------------------------------------------------ 请求

    private fun buildRequest(call: SearchCall): HttpRequest {
        val builder = when (call) {
            is SearchCall.Get -> HttpRequest.newBuilder(URI.create(call.url))
                .GET()
                .header("Accept", "application/json")
                .headers(call.headers)

            is SearchCall.PostJson -> HttpRequest.newBuilder(URI.create(call.url))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .headers(call.headers)
                .POST(HttpRequest.BodyPublishers.ofString(call.body, Charsets.UTF_8))
        }
        return builder
            .timeout(Duration.ofMillis(timeoutMs))
            .header("User-Agent", SearchLimits.USER_AGENT)
            .build()
    }

    /** [HttpRequest.Builder.headers] 不接受空名字/控制字符，坏配置在拼请求时就该变成 INVALID_BASE。 */
    private fun HttpRequest.Builder.headers(headers: Map<String, String>): HttpRequest.Builder {
        headers.forEach { (name, value) -> header(name, value) }
        return this
    }

    // ------------------------------------------------------------------ 读取

    /** 有界读取：超过 2 MiB 就抛 [SearchErrorKind.RESPONSE_TOO_LARGE]。 */
    private fun readBounded(stream: InputStream, provider: String): String {
        val output = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxResponseBytes) {
                throw SearchException(
                    SearchError(
                        SearchErrorKind.RESPONSE_TOO_LARGE,
                        provider,
                        null,
                        "$provider 的响应超过 ${maxResponseBytes / (1024 * 1024)} MiB 上限"
                    )
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /** 只读前 [limit] 个字符（错误响应体用，避免读到一道几 MB 的错误页）。 */
    private fun readAtMost(stream: InputStream, limit: Int): String {
        val buffer = ByteArray(256)
        val output = ByteArrayOutputStream(buffer.size)
        var total = 0
        while (total < limit) {
            val read = stream.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun redact(text: String, secret: String): String =
        if (secret.isBlank()) text else text.replace(secret, "***")

    // ------------------------------------------------------------------ 错误映射

    private fun statusError(provider: String, status: Int): SearchError {
        val kind = when {
            status == 401 -> SearchErrorKind.UNAUTHORIZED
            status == 403 -> SearchErrorKind.FORBIDDEN
            status == 429 -> SearchErrorKind.RATE_LIMITED
            status >= 500 -> SearchErrorKind.SERVER_ERROR
            else -> SearchErrorKind.HTTP_STATUS
        }
        val what = when (kind) {
            SearchErrorKind.UNAUTHORIZED -> "$provider 拒绝了 key（401）"
            SearchErrorKind.FORBIDDEN -> "$provider 没有权限或配额被禁用（403）"
            SearchErrorKind.RATE_LIMITED -> "$provider 限流了（429）"
            SearchErrorKind.SERVER_ERROR -> "$provider 服务端故障（$status）"
            else -> "$provider 返回了 $status"
        }
        return SearchError(kind, provider, status, what)
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(SearchLimits.CONNECT_TIMEOUT_MS))
            // 搜索接口偶发 301/302（尤其自建 SearXNG 反代）；跟随重定向更像浏览器。
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        /** 查询参数的百分号编码：空格编成 `%20` 而不是 `+`（SearXNG/Bing 都按 RFC 3986 解）。 */
        fun encodeComponent(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        /** 把 `(key, value)` 列表拼成查询串（值一律编码）。 */
        fun queryString(params: List<Pair<String, String>>): String =
            params.joinToString("&") { (key, value) -> "$key=${encodeComponent(value)}" }

        /** 在已带查询串的地址后面追加参数。 */
        fun appendQuery(url: String, params: List<Pair<String, String>>): String {
            if (params.isEmpty()) return url
            val separator = if (url.contains('?')) "&" else "?"
            return url + separator + queryString(params)
        }
    }
}
