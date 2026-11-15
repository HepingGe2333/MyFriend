package com.myfriend.app.network

import com.myfriend.app.domain.NetworkErrorKind
import com.myfriend.app.domain.WebToolsProviders
import com.myfriend.app.domain.WebUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一个搜索结果；字段与 `spec/web-search/README.md` §3 的归一化结果一致。
 *
 * 前三个字段是所有 provider 都有的；后面几个是 DeepSeek 官方接口额外给出的排序信息，
 * 其它 provider 留默认值。保留它们是为了不改变既有工具结果文案的形状。
 */
@Serializable
data class WebSearchHit(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val summary: String = "",
    @SerialName("site_name") val siteName: String? = null,
    val date: String? = null,
    val authority: Int = -1,
    @SerialName("rerank_score") val rerankScore: Double = 0.0
)

@Serializable
data class WebPage(
    val url: String = "",
    val title: String = "",
    val markdown: String = ""
)

/**
 * 联网工具的网络客户端：`web_search` / `web_fetch` 的后端适配层。
 *
 * 支持的 provider 与请求形状见 `spec/web-search/README.md`：
 * - `DEEPSEEK`：`POST {base}/v1/tools/search`、`POST {base}/v1/tools/fetch`（保持 0.12 之前的行爲）；
 * - `TAVILY` / `BRAVE` / `SERPER` / `GENERIC`：客户端直接请求各自的搜索接口；
 * - `web_fetch`：只有 DEEPSEEK 走服务端，其余由客户端 `GET` 目标网页并抽正文。
 *
 * 这里**只做网络与解析**（无 UI、无 Room）。所有 provider 的结果最后都收敛到 [WebSearchHit]，
 * 并统一做「丢非 http(s)、按 URL 去重、snippet 截 500、最多 count 条」。
 */
@Singleton
class WebSearchClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {

    /**
     * 调搜索接口。
     *
     * @param provider `DEEPSEEK` / `TAVILY` / `BRAVE` / `SERPER` / `GENERIC`（大小写与脏值会被归一化）
     * @param baseUrl 用户配置的地址；留空时用 provider 默认地址（DEEPSEEK 的默认值由调用方传入 LLM base）
     * @param apiKey provider 密钥；GENERIC 允许为空，其余缺 Key 直接报错
     */
    suspend fun search(
        provider: String,
        baseUrl: String,
        apiKey: String,
        query: String,
        count: Int = 6,
        oriQuestion: String? = null,
        needContent: Boolean = false,
        allowInsecureHttp: Boolean = false
    ): List<WebSearchHit> {
        val selected = WebToolsProviders.normalize(provider)
        val keyword = query.trim()
        if (keyword.isEmpty()) throw ApiException(NetworkErrorKind.WebQueryMissing)
        val key = apiKey.trim()
        if (key.isEmpty() && WebToolsProviders.requiresApiKey(selected)) {
            throw ApiException(NetworkErrorKind.WebKeyMissing)
        }
        val limit = normalizedCount(count)
        val body = when (selected) {
            WebToolsProviders.TAVILY -> execute(
                request = tavilyRequest(baseUrl, key, keyword, limit, allowInsecureHttp),
                provider = selected
            )
            WebToolsProviders.BRAVE -> execute(
                request = braveRequest(baseUrl, key, keyword, limit, allowInsecureHttp),
                provider = selected
            )
            WebToolsProviders.SERPER -> execute(
                request = serperRequest(baseUrl, key, keyword, limit, allowInsecureHttp),
                provider = selected
            )
            WebToolsProviders.GENERIC -> execute(
                request = genericRequest(baseUrl, key, keyword, limit, allowInsecureHttp),
                provider = selected
            )
            else -> execute(
                request = deepSeekSearchRequest(
                    baseUrl = baseUrl,
                    apiKey = key,
                    query = keyword,
                    count = limit,
                    oriQuestion = oriQuestion,
                    needContent = needContent,
                    allowInsecureHttp = allowInsecureHttp
                ),
                provider = selected
            )
        }
        val hits = when (selected) {
            WebToolsProviders.TAVILY -> parseTavily(body)
            WebToolsProviders.BRAVE -> parseBrave(body)
            WebToolsProviders.SERPER -> parseSerper(body)
            WebToolsProviders.GENERIC -> parseGeneric(body)
            else -> parseDeepSeekSearch(body)
        }
        return normalizeHits(hits, limit)
    }

    /**
     * 抓取单个网页正文。
     *
     * DEEPSEEK 仍交给服务端；其它 provider 由客户端 GET（10s、≤2MiB、固定 UA）并抽正文。
     * **无论哪种 provider，URL 都必须先过 [WebUrlPolicy]**（http/https、非内网/本机、无 userinfo）。
     */
    suspend fun fetch(
        provider: String,
        baseUrl: String,
        apiKey: String,
        url: String,
        allowInsecureHttp: Boolean = false
    ): WebPage {
        val selected = WebToolsProviders.normalize(provider)
        val target = url.trim()
        if (target.isEmpty()) throw ApiException(NetworkErrorKind.WebUrlMissing)
        WebUrlPolicy.check(target)?.let { rejection ->
            if (rejection == WebUrlPolicy.Rejection.EMPTY) {
                throw ApiException(NetworkErrorKind.WebUrlMissing)
            }
            throw ApiException(NetworkErrorKind.WebUrlRejected(rejection))
        }
        if (selected != WebToolsProviders.DEEPSEEK) {
            return fetchLocally(target, allowInsecureHttp)
        }

        val key = requireApiKey(apiKey)
        val endpoint = resolveEndpoint(baseUrl, TOOL_FETCH, allowInsecureHttp)
        val payload = buildJsonObject { put("url", target) }
        val page = parseDeepSeekFetch(post(endpoint, key, payload))
        val resolvedUrl = page.url.ifBlank { target }
        return page.copy(
            url = resolvedUrl,
            title = page.title.ifBlank { resolvedUrl }
        )
    }

    // ---------------------------------------------------------------- 请求构造

    private fun deepSeekSearchRequest(
        baseUrl: String,
        apiKey: String,
        query: String,
        count: Int,
        oriQuestion: String?,
        needContent: Boolean,
        allowInsecureHttp: Boolean
    ): Request {
        val endpoint = resolveEndpoint(baseUrl, TOOL_SEARCH, allowInsecureHttp)
        val payload = buildJsonObject {
            put("query", query)
            put("count", count)
            oriQuestion?.trim()?.takeIf { it.isNotEmpty() }?.let { put("ori_question", it) }
            put("need_content", needContent)
        }
        return postRequest(endpoint, apiKey, payload)
    }

    private fun tavilyRequest(
        baseUrl: String,
        apiKey: String,
        query: String,
        count: Int,
        allowInsecureHttp: Boolean
    ): Request {
        val endpoint = providerEndpoint(WebToolsProviders.TAVILY, baseUrl, "search", allowInsecureHttp)
        val payload = buildJsonObject {
            // 规范要求 body 里也带 api_key；header 同样带 Authorization，两个都保留。
            put("api_key", apiKey)
            put("query", query)
            put("max_results", count)
            put("include_answer", false)
        }
        return postRequest(endpoint, apiKey, payload)
    }

    private fun braveRequest(
        baseUrl: String,
        apiKey: String,
        query: String,
        count: Int,
        allowInsecureHttp: Boolean
    ): Request {
        val base = providerEndpoint(WebToolsProviders.BRAVE, baseUrl, "web/search", allowInsecureHttp)
        val url = NetworkGuard.parseHttpUrl(base, allowInsecureHttp)
            .newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("count", count.toString())
            .build()
        return Request.Builder()
            .url(url)
            .get()
            .header("X-Subscription-Token", apiKey)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
    }

    private fun serperRequest(
        baseUrl: String,
        apiKey: String,
        query: String,
        count: Int,
        allowInsecureHttp: Boolean
    ): Request {
        val endpoint = providerEndpoint(WebToolsProviders.SERPER, baseUrl, "search", allowInsecureHttp)
        val payload = buildJsonObject {
            put("q", query)
            put("num", count)
        }
        return postRequest(endpoint, apiKey, payload, keyHeader = "X-API-KEY")
    }

    private fun genericRequest(
        baseUrl: String,
        apiKey: String,
        query: String,
        count: Int,
        allowInsecureHttp: Boolean
    ): Request {
        val endpoint = providerEndpoint(WebToolsProviders.GENERIC, baseUrl, "", allowInsecureHttp)
        val payload = buildJsonObject {
            put("query", query)
            put("count", count)
        }
        return postRequest(endpoint, apiKey, payload)
    }

    private fun postRequest(
        endpoint: String,
        apiKey: String,
        payload: JsonObject,
        keyHeader: String = "Authorization"
    ): Request {
        val builder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        if (keyHeader == "Authorization") {
            if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
        } else if (apiKey.isNotEmpty()) {
            builder.header(keyHeader, apiKey)
        }
        return builder.build()
    }

    private suspend fun post(endpoint: String, apiKey: String, payload: JsonObject): String =
        execute(postRequest(endpoint, apiKey, payload), WebToolsProviders.DEEPSEEK)

    // ---------------------------------------------------------------- 执行

    private suspend fun execute(request: Request, provider: String): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val raw = if (response.isSuccessful) {
                    readBounded(response, MAX_RESPONSE_BYTES)
                } else {
                    readUpTo(response, MAX_LOGGED_BODY_CHARS)
                }
                if (!response.isSuccessful) throw response.toApiException(provider, raw)
                raw
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: ApiException) {
            throw error
        } catch (error: Exception) {
            throw mapNetworkError(error)
        }
    }

    private suspend fun fetchLocally(target: String, allowInsecureHttp: Boolean): WebPage =
        withContext(Dispatchers.IO) {
            val url = NetworkGuard.parseHttpUrl(target, allowInsecureHttp)
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", FETCH_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.5")
                .build()
            val raw = try {
                // 单独一份带 10s 上限的客户端：抓网页不能把一次工具调用拖到默认超时。
                client.newBuilder()
                    .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        val body = if (response.isSuccessful) {
                            readBounded(response, MAX_RESPONSE_BYTES)
                        } else {
                            readUpTo(response, MAX_LOGGED_BODY_CHARS)
                        }
                        if (!response.isSuccessful) throw response.toApiException(WebToolsProviders.GENERIC, body)
                        body
                    }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: ApiException) {
                throw error
            } catch (error: Exception) {
                throw mapNetworkError(error)
            }
            val title = extractHtmlTitle(raw)
            WebPage(
                url = target,
                title = title.ifBlank { target },
                // 规范上限 8000 字符；比 DeepSeek 服务端抓取的 12000 更保守（正文是我们自己抽的）。
                markdown = htmlToText(raw).take(MAX_FETCH_CHARS)
            )
        }

    private fun readBounded(response: Response, limit: Int): String {
        if (response.body?.contentLength()?.let { it > limit } == true) {
            throw ApiException(NetworkErrorKind.ResponseTooLong(limit.toLong()))
        }
        val stream = response.body?.byteStream()
            ?: throw ApiException(NetworkErrorKind.WebUnparsable)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw ApiException(NetworkErrorKind.ResponseTooLong(limit.toLong()))
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /** 错误响应只留 200 字符进日志（规范 §5），绝不把整段第三方返回读进内存。 */
    private fun readUpTo(response: Response, limit: Int): String {
        val stream = response.body?.byteStream() ?: return ""
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        var total = 0
        while (total < limit) {
            val read = stream.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun Response.toApiException(provider: String, body: String): ApiException {
        val preview = body.take(MAX_LOGGED_BODY_CHARS)
        // 原始响应体只进日志，且只留 200 字符；模型只会看到「哪个 provider、什么状态码」。
        println("[websearch] provider=$provider status=$code body=${preview.replace('\n', ' ')}")
        if (code == 404 && provider == WebToolsProviders.DEEPSEEK) {
            return ApiException(NetworkErrorKind.WebPageUnreachable, "provider=$provider status=$code")
        }
        return ApiException(NetworkErrorKind.WebStatus(code), "provider=$provider status=$code")
    }

    private fun mapNetworkError(error: Exception): Exception = when (error) {
        is ApiException -> error
        is SocketTimeoutException -> ApiException(NetworkErrorKind.Timeout)
        is UnknownHostException -> ApiException(NetworkErrorKind.UnknownHost)
        is IOException -> ApiException(NetworkErrorKind.NetworkInterrupted, error.message)
        else -> error
    }

    // ---------------------------------------------------------------- 地址

    /**
     * 归一化 baseUrl 并拼出 DeepSeek 工具端点：
     * 去掉尾斜杠；已以 /v1 结尾就沿用，否则补 /v1。结果形如 https://api.deepseek.com/v1/tools/search。
     */
    internal fun toolsEndpoint(baseUrl: String, tool: String): String {
        val base = baseUrl.trim()
            .trimEnd('/')
            .removeSuffix("/tools/search")
            .removeSuffix("/tools/fetch")
            .trimEnd('/')
        if (base.isEmpty()) throw ApiException(NetworkErrorKind.WebBaseMissing)
        val root = if (base.endsWith("/v1")) base else "$base/v1"
        val path = tool.trim().trimStart('/').ifBlank { TOOL_SEARCH }
        return if (path.contains('/')) "$root/$path" else "$root/tools/$path"
    }

    private fun resolveEndpoint(baseUrl: String, tool: String, allowInsecureHttp: Boolean): String =
        NetworkGuard.parseHttpUrl(toolsEndpoint(baseUrl, tool), allowInsecureHttp).toString()

    /**
     * 非 DeepSeek provider 的端点：
     * - 显式 baseUrl 优先；留空用规范里的默认地址；
     * - TAVILY / SERPER 追加 `/search`，BRAVE 追加 `/web/search`，GENERIC **原样使用** base；
     * - 统一过 [NetworkGuard]（http(s)、明文开关语义）。
     */
    internal fun providerEndpoint(
        provider: String,
        baseUrl: String,
        suffix: String,
        allowInsecureHttp: Boolean
    ): String {
        val explicit = baseUrl.trim().trimEnd('/')
        val base = explicit.ifEmpty {
            WebToolsProviders.defaultBaseUrl(provider)
                ?: throw ApiException(NetworkErrorKind.WebBaseMissing)
        }
        val raw = if (suffix.isBlank()) base else "$base/${suffix.trimStart('/')}"
        return NetworkGuard.parseHttpUrl(raw, allowInsecureHttp).toString()
    }

    private fun requireApiKey(apiKey: String): String {
        val key = apiKey.trim()
        if (key.isEmpty()) throw ApiException(NetworkErrorKind.WebKeyMissing)
        return key
    }

    // ---------------------------------------------------------------- 解析

    /** 容错解析 DeepSeek 搜索响应：缺字段走默认值，results 缺失/非数组返回 emptyList。 */
    internal fun parseDeepSeekSearch(body: String): List<WebSearchHit> {
        val element = parseRoot(body)
        val results: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["results"] as? JsonArray)
                ?: (element["data"] as? JsonArray)
                ?: (element["hits"] as? JsonArray)
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return results.mapNotNull { item -> (item as? JsonObject)?.let { obj -> toDeepSeekHit(obj) } }
    }

    internal fun parseTavily(body: String): List<WebSearchHit> =
        parseResultArray(body, "results") { obj ->
            WebSearchHit(
                title = obj.text("title", "name"),
                url = obj.text("url", "link"),
                snippet = obj.text("content", "snippet", "description"),
                date = obj.textOrNull("published_date", "publishedAt", "date")
            )
        }

    internal fun parseBrave(body: String): List<WebSearchHit> {
        val root = parseRoot(body) as? JsonObject ?: return emptyList()
        val web = root["web"] as? JsonObject ?: return emptyList()
        val results = web["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { item ->
            (item as? JsonObject)?.let { obj ->
                WebSearchHit(
                    title = obj.text("title"),
                    url = obj.text("url"),
                    snippet = obj.text("description", "snippet"),
                    date = obj.textOrNull("age", "page_age", "date")
                )
            }
        }
    }

    internal fun parseSerper(body: String): List<WebSearchHit> =
        parseResultArray(body, "organic") { obj ->
            WebSearchHit(
                title = obj.text("title"),
                url = obj.text("link", "url"),
                snippet = obj.text("snippet", "description"),
                date = obj.textOrNull("date")
            )
        }

    internal fun parseGeneric(body: String): List<WebSearchHit> =
        parseResultArray(body, "results") { obj ->
            WebSearchHit(
                title = obj.text("title", "name"),
                url = obj.text("url", "link"),
                snippet = obj.text("snippet", "content", "description"),
                date = obj.textOrNull("publishedAt", "published_at", "date")
            )
        }

    /** 容错解析 DeepSeek 抓取响应：兼容 content / markdown 两种字段名，标题为空时回退成 url。 */
    internal fun parseDeepSeekFetch(body: String): WebPage {
        val element = parseRoot(body)
        val root = when (element) {
            is JsonObject -> (element["data"] as? JsonObject) ?: element
            is JsonPrimitive -> return WebPage(markdown = truncateMarkdown(element.contentOrNull.orEmpty()))
            else -> throw ApiException(NetworkErrorKind.WebUnparsable)
        }
        val url = root.text("url", "link")
        val title = root.text("title", "name").ifBlank { url }
        val markdown = root.text("markdown", "content", "text")
        return WebPage(url = url, title = title, markdown = truncateMarkdown(markdown))
    }

    private fun parseResultArray(
        body: String,
        key: String,
        mapper: (JsonObject) -> WebSearchHit
    ): List<WebSearchHit> {
        val root = parseRoot(body) as? JsonObject ?: return emptyList()
        val results = root[key] as? JsonArray ?: return emptyList()
        return results.mapNotNull { item -> (item as? JsonObject)?.let(mapper) }
    }

    private fun parseRoot(body: String): JsonElement =
        runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: throw ApiException(NetworkErrorKind.WebUnparsable)

    /**
     * 归一化（规范 §3）：丢非 http(s)、按 URL 去重（保留第一次出现）、snippet 截 500、最多 count 条。
     */
    internal fun normalizeHits(hits: List<WebSearchHit>, count: Int): List<WebSearchHit> {
        val seen = HashSet<String>()
        val limit = normalizedCount(count)
        val out = ArrayList<WebSearchHit>(minOf(limit, hits.size))
        for (hit in hits) {
            if (out.size >= limit) break
            val url = hit.url.trim()
            if (!url.lowercase().startsWith("http://") && !url.lowercase().startsWith("https://")) continue
            if (!seen.add(url)) continue
            out += hit.copy(
                title = hit.title.trim(),
                url = url,
                snippet = htmlToText(hit.snippet).take(MAX_SNIPPET_CHARS),
                summary = hit.summary.trim()
            )
        }
        return out
    }

    private fun normalizedCount(count: Int): Int =
        if (count <= 0) DEFAULT_COUNT else count.coerceAtMost(MAX_COUNT)

    private fun toDeepSeekHit(obj: JsonObject): WebSearchHit = WebSearchHit(
        title = obj.text("title", "name"),
        url = obj.text("url", "link", "href"),
        snippet = obj.text("snippet", "description", "abstract"),
        summary = obj.text("summary", "content"),
        siteName = obj.textOrNull("site_name", "siteName", "site"),
        date = obj.textOrNull("date", "published_at", "publishedAt", "time"),
        authority = obj.intOrNull("authority", "authority_score") ?: -1,
        rerankScore = obj.doubleOrNull("rerank_score", "rerankScore", "score") ?: 0.0
    )

    private fun truncateMarkdown(raw: String): String =
        if (raw.length <= MAX_MARKDOWN_CHARS) raw else raw.take(MAX_MARKDOWN_CHARS) + TRUNCATED_SUFFIX

    // ---------------------------------------------------------------- 文本

    /**
     * HTML → 纯文本：去掉 script/style 整段、其余标签、常见实体，空白压成一个空格。
     *
     * 也用于 provider 返回的 snippet（有些后端把高亮标签原样塞进 snippet）。
     */
    internal fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        var text = html
        HEAD.replace(text, " ").let { text = it }
        SCRIPT_STYLE.replace(text, " ").let { text = it }
        text = TAG.replace(text, " ")
        text = decodeEntities(text)
        text = text.replace(WHITESPACE, " ").trim()
        return text
    }

    /** `<title>` 只在原文里取，先于去标签，否则标题和正文会粘在一起。 */
    internal fun extractHtmlTitle(html: String): String {
        val raw = TITLE.find(html)?.groupValues?.getOrNull(1).orEmpty()
        return htmlToText(raw).take(MAX_TITLE_CHARS)
    }

    private fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var decoded = text
        NAMED_ENTITIES.forEach { (entity, value) -> decoded = decoded.replace(entity, value) }
        decoded = NUMERIC_ENTITY.replace(decoded) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        return decoded
    }

    private fun JsonObject.textOrNull(vararg keys: String): String? {
        keys.forEach { key ->
            val text = (this[key] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return null
    }

    private fun JsonObject.text(vararg keys: String): String = textOrNull(*keys).orEmpty()

    private fun JsonObject.intOrNull(vararg keys: String): Int? =
        textOrNull(*keys)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    private fun JsonObject.doubleOrNull(vararg keys: String): Double? =
        textOrNull(*keys)?.toDoubleOrNull()

    internal companion object {
        const val DEFAULT_COUNT = 6
        const val MAX_COUNT = 12
        const val MAX_SNIPPET_CHARS = 500
        const val MAX_FETCH_CHARS = 8_000
        const val MAX_TITLE_CHARS = 200
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_LOGGED_BODY_CHARS = 200
        const val FETCH_TIMEOUT_SECONDS = 10L
        const val USER_AGENT = "MyFriend-Android"
        const val FETCH_USER_AGENT = "MyFriend/1.0"
        const val TOOL_SEARCH = "search"
        const val TOOL_FETCH = "fetch"
        const val MAX_MARKDOWN_CHARS = 12_000
        const val TRUNCATED_SUFFIX = "\n\n…（内容过长已截断）"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val HEAD = Regex("""<head\b[^>]*>[\s\S]*?</head\s*>""", setOf(RegexOption.IGNORE_CASE))
        private val SCRIPT_STYLE = Regex(
            """<(script|style)\b[^>]*>[\s\S]*?</\1\s*>""",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val TAG = Regex("""<[^>]*>""")
        private val TITLE = Regex("""<title\b[^>]*>([\s\S]*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE))
        private val WHITESPACE = Regex("""\s+""")
        private val NUMERIC_ENTITY = Regex("""&#(\d+);""")
        private val NAMED_ENTITIES = listOf(
            "&nbsp;" to " ",
            "&amp;" to "&",
            "&lt;" to "<",
            "&gt;" to ">",
            "&quot;" to "\"",
            "&#39;" to "'",
            "&apos;" to "'"
        )
    }
}
