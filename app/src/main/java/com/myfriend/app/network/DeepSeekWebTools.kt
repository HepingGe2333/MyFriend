package com.myfriend.app.network

import com.myfriend.app.domain.NetworkErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepSeek 官方 Search / Fetch 接口（联网工具）。
 *
 * 这是给 AI 用的联网工具，不参与普通聊天推理：模型先调用 search 拿到候选网页，
 * 再按需调用 fetch 抓取正文，结果作为“工具结果”回填给模型。
 * 本类只做网络与解析（无 UI、无 Room），节点为 {base}/v1/tools/search 与 {base}/v1/tools/fetch。
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

@Singleton
class DeepSeekWebTools @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {

    /**
     * 调用 DeepSeek Search，返回候选网页。results 为空时返回 emptyList。
     *
     * @param baseUrl https://api.deepseek.com、https://api.deepseek.com/v1、带尾斜杠均可
     * @param oriQuestion 原始问题，用于让服务端做结果重排；为 null 时不发送该字段
     */
    suspend fun search(
        baseUrl: String,
        apiKey: String,
        query: String,
        count: Int = 6,
        oriQuestion: String? = null,
        needContent: Boolean = false,
        allowInsecureHttp: Boolean = false
    ): List<WebSearchHit> {
        val keyword = query.trim()
        if (keyword.isEmpty()) throw ApiException(NetworkErrorKind.WebQueryMissing)
        val key = requireApiKey(apiKey)
        val url = resolveEndpoint(baseUrl, TOOL_SEARCH, allowInsecureHttp)
        val payload = buildJsonObject {
            put("query", keyword)
            put("count", if (count <= 0) DEFAULT_COUNT else count)
            oriQuestion?.trim()?.takeIf { it.isNotEmpty() }?.let { put("ori_question", it) }
            put("need_content", needContent)
        }
        return parseSearch(post(url, key, payload, TOOL_SEARCH))
    }

    /** 调用 DeepSeek Fetch，抓取单个网页正文。markdown 超长会被截断。 */
    suspend fun fetch(
        baseUrl: String,
        apiKey: String,
        url: String,
        allowInsecureHttp: Boolean = false
    ): WebPage {
        val target = url.trim()
        if (target.isEmpty()) throw ApiException(NetworkErrorKind.WebUrlMissing)
        val key = requireApiKey(apiKey)
        val endpoint = resolveEndpoint(baseUrl, TOOL_FETCH, allowInsecureHttp)
        val payload = buildJsonObject { put("url", target) }
        val page = parseFetch(post(endpoint, key, payload, TOOL_FETCH))
        val resolvedUrl = page.url.ifBlank { target }
        return page.copy(
            url = resolvedUrl,
            title = page.title.ifBlank { resolvedUrl }
        )
    }

    /**
     * 归一化 baseUrl 并拼出工具端点：
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

    /** 容错解析搜索响应：缺字段走默认值，results 缺失/非数组返回 emptyList。 */
    internal fun parseSearch(body: String): List<WebSearchHit> {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: throw ApiException(NetworkErrorKind.WebUnparsable)
        val results: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["results"] as? JsonArray)
                ?: (element["data"] as? JsonArray)
                ?: (element["hits"] as? JsonArray)
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return results.mapNotNull { item -> (item as? JsonObject)?.let { obj -> toHit(obj) } }
    }

    /** 容错解析抓取响应：兼容 content / markdown 两种字段名，标题为空时回退成 url。 */
    internal fun parseFetch(body: String): WebPage {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: throw ApiException(NetworkErrorKind.WebUnparsable)
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

    private fun resolveEndpoint(baseUrl: String, tool: String, allowInsecureHttp: Boolean): String =
        NetworkGuard.parseHttpUrl(toolsEndpoint(baseUrl, tool), allowInsecureHttp).toString()

    private fun requireApiKey(apiKey: String): String {
        val key = apiKey.trim()
        if (key.isEmpty()) throw ApiException(NetworkErrorKind.WebKeyMissing)
        return key
    }

    private suspend fun post(
        endpoint: String,
        apiKey: String,
        payload: JsonObject,
        tool: String
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw response.toApiException(tool, raw)
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

    private fun Response.toApiException(tool: String, body: String): ApiException {
        if (code == 404 && tool == TOOL_FETCH) {
            return ApiException(NetworkErrorKind.WebPageUnreachable, body.take(1200))
        }
        val detail = extractErrorMessage(body)
        return ApiException(NetworkErrorKind.WebStatus(code), body.take(1200).ifBlank { detail })
    }

    private fun extractErrorMessage(body: String): String {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return ""
        val message = (root["error"] as? JsonObject)?.textOrNull("message", "msg", "detail")
            ?: (root["error"] as? JsonPrimitive)?.contentOrNull
            ?: root.textOrNull("message", "msg")
        return message?.trim()?.take(400).orEmpty()
    }

    private fun mapNetworkError(error: Exception): Exception = when (error) {
        is ApiException -> error
        is SocketTimeoutException -> ApiException(NetworkErrorKind.Timeout)
        is UnknownHostException -> ApiException(NetworkErrorKind.UnknownHost)
        is IOException -> ApiException(NetworkErrorKind.NetworkInterrupted, error.message)
        else -> error
    }

    private fun toHit(obj: JsonObject): WebSearchHit = WebSearchHit(
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

    private companion object {
        const val DEFAULT_COUNT = 6
        const val MAX_MARKDOWN_CHARS = 12_000
        const val TRUNCATED_SUFFIX = "\n\n…（内容过长已截断）"
        const val USER_AGENT = "MyFriend-Android"
        const val TOOL_SEARCH = "search"
        const val TOOL_FETCH = "fetch"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
