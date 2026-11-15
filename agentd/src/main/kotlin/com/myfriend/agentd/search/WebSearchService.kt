package com.myfriend.agentd.search

import com.myfriend.app.core.json.JsonValue
import com.myfriend.app.domain.WebUrlPolicy

/**
 * 服务端联网搜索：`web_search` / `web_fetch` 的唯一入口（`docs/design/server-authoritative.md` §3.5）。
 *
 * 两种用法：
 * ```kotlin
 * val hits = service.search(SearchRequest(query = "杭州 天气", count = 6, provider = "SEARXNG", baseUrl = "http://127.0.0.1:8080"))
 * val page = service.webFetch("https://example.com/post")   // 服务端直连公开页面
 * val page2 = service.webFetch(url, ProviderConfig(base, key, provider = "MESSAGEAPI")) // 走 tools/fetch
 * ```
 *
 * 铁律：
 * 1. **所有 provider 的结果都是统一的 [WebSearchHit]**：丢非 http(s)、按 URL 去重（保留第一次出现）、
 *    snippet 截 500、最多 count 条 —— 与客户端 `WebSearchClient` 和 `spec/web-search/README.md` §3 一致；
 * 2. **`web_fetch` 先过 [WebUrlPolicy] 再发请求**（http/https、拒绝私网/本机/userinfo），
 *    正文去 script/style/标签、压空白、截 8000 字；
 * 3. **失败一律抛带上下文的 [SearchException]**，不静默返回空列表（规范 §5）；
 * 4. **绝不打印 key**：错误文案只有 provider + 状态码，原始响应体最多 200 字符进 stderr，
 *    且日志预览里 key 被替换成 `***`（见 [SearchHttpClient.execute]）。
 *
 * 这个类没有状态、不读配置表、不碰数据库：provider/base/key 都是每次调用的参数，
 * 所以路由层（`Server.kt`，由主线接）只需要把 `api_configs` 里的值搬进来。
 */
class WebSearchService(
    private val http: SearchHttpClient = SearchHttpClient(),
    /**
     * `web_fetch` 的 URL 策略，默认就是 [WebUrlPolicy.check]。
     *
     * 留成可注入的**只为测试**：单测要抓的页面是本地 `127.0.0.1` 上的假服务端，而真实策略必须拒绝它。
     * 生产路径上没有人会传第二个参数（构造函数的默认值就是唯一正确的策略）。
     */
    private val urlPolicy: (String) -> WebUrlPolicy.Rejection? = WebUrlPolicy::check
) {

    /** 直连抓取时错误文案里的 provider 名（既不是某个搜索后端，也不是 MESSAGEAPI）。 */
    private val fetchProvider = "WEB_FETCH"

    /**
     * 调搜索接口。
     *
     * @throws SearchException 查询为空 / 缺 key / 缺地址 / 网络与状态码错误 / 响应过大 / 无法解析
     */
    fun search(request: SearchRequest): List<WebSearchHit> {
        val requested = Providers.normalize(request.provider)
        val query = request.query.trim()
        if (query.isEmpty()) {
            throw SearchException(
                SearchError(SearchErrorKind.EMPTY_QUERY, requested, null, "搜索词不能为空")
            )
        }
        val resolved = resolve(requested, request)
        val count = Providers.normalizeCount(request.count)
        val call = resolved.provider.searchCall(query, count, resolved.config)
        val body = http.execute(call, resolved.provider.name, resolved.config.apiKey)
        val hits = resolved.provider.parseHits(body)
        return normalizeHits(hits, count)
    }

    /**
     * 抓取单个网页正文。
     *
     * 传 [config] 且它是 `MESSAGEAPI` 时走 `POST {base}/v1/tools/fetch`（0.12 之前的行为，工具结果形状不变）；
     * 不传（或其它 provider）则由服务端直连目标页面。两条路径都**先**做 URL 策略检查。
     */
    fun webFetch(url: String, config: ProviderConfig? = null): WebFetchResult {
        val target = url.trim()
        val provider = config?.let { Providers.normalize(it.provider) } ?: fetchProvider
        if (target.isEmpty()) {
            throw SearchException(SearchError(SearchErrorKind.EMPTY_URL, provider, null, "URL 不能为空"))
        }
        urlPolicy(target)?.let { rejection ->
            throw SearchException(
                SearchError(SearchErrorKind.URL_REJECTED, provider, null, describe(rejection))
            )
        }
        if (provider == Providers.MESSAGEAPI) {
            return fetchViaMessageApi(target, config!!.copy(provider = Providers.MESSAGEAPI))
        }
        return fetchDirect(target)
    }

    // ------------------------------------------------------------------ provider 解析

    private data class Resolved(val provider: SearchProvider, val config: ProviderConfig)

    /**
     * 把「用户选的 provider」变成「真正要请求的协议实现」。
     *
     * 站点过滤源（`ZHIHU` / `TOUTIAO` / `QUORA`）在这一步换成 `SEARXNG` / `BING` 的实现，
     * 并用 [SiteScopedProvider] 把 `site:` 拼进 query；key 的必填性按**底层引擎**判断
     * （SearXNG 不需要 key，Bing 需要）。
     */
    private fun resolve(requested: String, request: SearchRequest): Resolved {
        val site = Providers.siteFor(requested)
        val engineName = if (site != null) Providers.normalizeEngine(request.engine) else requested
        val config = ProviderConfig(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            provider = engineName
        )
        if (Providers.requiresApiKey(engineName) && config.apiKey.isBlank()) {
            throw SearchException(
                SearchError(
                    SearchErrorKind.MISSING_KEY,
                    engineName,
                    null,
                    "这个配置没有 $engineName 的搜索 key"
                )
            )
        }
        // 地址缺失/非法在 provider 拼请求时抛（每个 provider 的默认地址规则不同）。
        val engine = Providers.implementation(engineName)
        val provider = if (site != null) {
            // 错误文案沿用底层引擎名：限流的是引擎，不是知乎。
            SiteScopedProvider(engine, site, name = engine.name)
        } else {
            engine
        }
        return Resolved(provider, config)
    }

    // ------------------------------------------------------------------ 抓取

    /** `MESSAGEAPI`：`POST {base}/v1/tools/fetch` `{"url"}` → `{content}` / `{markdown}` / `{text}`。 */
    private fun fetchViaMessageApi(target: String, config: ProviderConfig): WebFetchResult {
        if (config.apiKey.isBlank()) {
            throw SearchException(
                SearchError(SearchErrorKind.MISSING_KEY, Providers.MESSAGEAPI, null, "这个配置没有搜索 key")
            )
        }
        val endpoint = Endpoints.messageApi(config.baseUrl, "fetch", Providers.MESSAGEAPI)
        val body = JsonSupport.body("url" to JsonSupport.str(target))
        val raw = http.execute(
            SearchCall.PostJson(endpoint, bearerHeaders(config.apiKey), body),
            Providers.MESSAGEAPI,
            config.apiKey
        )
        return parseMessageApiFetch(raw, target)
    }

    private fun parseMessageApiFetch(body: String, target: String): WebFetchResult {
        val root = JsonSupport.parse(body, Providers.MESSAGEAPI)
        val objectRoot = when (root) {
            is JsonValue.Obj -> root.objOrNull("data") ?: root
            // 有些兼容实现直接回一段纯文本 JSON 字符串。
            is JsonValue.Str -> return WebFetchResult(
                url = target,
                title = target,
                text = root.value.trim().take(SearchLimits.MAX_FETCH_CHARS)
            )

            else -> throw SearchException(
                SearchError(
                    SearchErrorKind.UNPARSABLE,
                    Providers.MESSAGEAPI,
                    null,
                    "${Providers.MESSAGEAPI} 的抓取响应缺少内容"
                )
            )
        }
        val url = objectRoot.string("url", "link").ifBlank { target }
        val title = objectRoot.string("title", "name").ifBlank { url }
        val text = objectRoot.string("markdown", "content", "text")
        return WebFetchResult(
            url = url,
            title = title,
            text = text.take(SearchLimits.MAX_FETCH_CHARS)
        )
    }

    /** 服务端直接 GET 公开页面：10s、≤2 MiB、固定 UA，然后去标签压空白。 */
    private fun fetchDirect(target: String): WebFetchResult {
        val raw = http.execute(
            SearchCall.Get(
                target,
                mapOf(
                    "Accept" to "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.5"
                )
            ),
            fetchProvider
        )
        val title = HtmlText.title(raw)
        return WebFetchResult(
            url = target,
            title = title.ifBlank { target },
            text = HtmlText.toText(raw).take(SearchLimits.MAX_FETCH_CHARS)
        )
    }

    /** 把 URL 策略的拒绝原因翻成一句可以直接给模型看的话（不回显整个 URL）。 */
    private fun describe(rejection: WebUrlPolicy.Rejection): String = when (rejection) {
        WebUrlPolicy.Rejection.EMPTY -> "URL 不能为空"
        WebUrlPolicy.Rejection.UNSUPPORTED_SCHEME -> "只抓取 http/https 页面"
        WebUrlPolicy.Rejection.MISSING_HOST -> "URL 缺少可用的主机名（或带了账号密码）"
        WebUrlPolicy.Rejection.LOCAL_HOST -> "不抓取本机/内网地址"
    }

    companion object {

        /**
         * 归一化（`spec/web-search/README.md` §3，与客户端逐字一致）：
         * 丢非 http(s)、按 URL 去重（保留第一次出现）、snippet 去标签并截 500、最多 count 条。
         */
        fun normalizeHits(hits: List<WebSearchHit>, count: Int): List<WebSearchHit> {
            val limit = Providers.normalizeCount(count)
            val seen = HashSet<String>(hits.size)
            val out = ArrayList<WebSearchHit>(minOf(limit, hits.size))
            for (hit in hits) {
                if (out.size >= limit) break
                val url = hit.url.trim()
                val lower = url.lowercase()
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) continue
                if (!seen.add(url)) continue
                out += WebSearchHit(
                    title = hit.title.trim(),
                    url = url,
                    snippet = HtmlText.toText(hit.snippet).take(SearchLimits.MAX_SNIPPET_CHARS)
                )
            }
            return out
        }
    }
}
