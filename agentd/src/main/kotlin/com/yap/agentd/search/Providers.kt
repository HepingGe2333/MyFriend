package com.yap.agentd.search

import com.yap.app.core.json.JsonValue
import com.yap.app.domain.ToolArgClamp

/**
 * 搜索 provider 的注册表与实现（`spec/web-search/README.md` + `docs/design/server-authoritative.md` §3.5）。
 *
 * 一共有三种东西，别混：
 * 1. **协议 provider**：一种真正不同的线上协议 —— `MESSAGEAPI` / `TAVILY` / `BRAVE` / `SERPER` / `GENERIC`
 *    （0.12 之前的五个）加上 0.12 新增的 `SEARXNG`（自建聚合搜索，无 key）与 `BING`（Azure Bing Web Search）；
 * 2. **站点过滤源**：`ZHIHU` / `TOUTIAO` / `QUORA` **不是新协议**，只是「把 `site:zhihu.com` 拼进 query」，
 *    再走 `SEARXNG`（默认）或 `BING`。实现就是一个薄包装 [SiteScopedProvider]；
 * 3. **归一化**：所有 provider 的原始响应先各自解析成 [WebSearchHit] 列表，再由
 *    [WebSearchService.normalizeHits] 统一「丢非 http(s)、按 URL 去重、snippet ≤500、最多 count 条」。
 *
 * 这里没有任何第三方依赖：请求用 JDK [java.net.http.HttpClient]（见 [SearchHttpClient]），
 * JSON 用 `:agentcore` 的自写解析器（见 [JsonSupport]）。
 */
object Providers {

    const val MESSAGEAPI = "MESSAGEAPI"
    const val TAVILY = "TAVILY"
    const val BRAVE = "BRAVE"
    const val SERPER = "SERPER"
    const val GENERIC = "GENERIC"

    /** 0.12 新增：自建 SearXNG 聚合搜索。地址必填，不需要 key。 */
    const val SEARXNG = "SEARXNG"

    /** 0.12 新增：Azure Bing Web Search。 */
    const val BING = "BING"

    /** 站点过滤源：走 SearXNG/Bing 加 `site:`，不直接爬站点（§3.5.1）。 */
    const val ZHIHU = "ZHIHU"
    const val TOUTIAO = "TOUTIAO"
    const val QUORA = "QUORA"

    /** 0.12 之前的旧值，读旧配置时折算成 [MESSAGEAPI]（与客户端 `WebToolsProviders` 一致）。 */
    const val LEGACY_DEEPSEEK = "DEEPSEEK"

    const val TAVILY_DEFAULT_BASE = "https://api.tavily.com"
    const val BRAVE_DEFAULT_BASE = "https://api.search.brave.com/res/v1"
    const val SERPER_DEFAULT_BASE = "https://google.serper.dev"
    const val BING_DEFAULT_BASE = "https://api.bing.microsoft.com"

    // 注意：`MESSAGEAPI` 没有固定默认地址 —— 它沿用 LLM 配置的 base（`spec/web-search/README.md` §1）。
    // 服务端不在这里偷偷回落到某个厂商的地址：配置缺失时宁可报 MISSING_BASE，也不要把 key 发错地方。

    /** 站点过滤源默认走 SearXNG（自建、无 key、§3.5「推荐」）。 */
    const val DEFAULT_SITE_ENGINE = SEARXNG

    val all: List<String> = listOf(
        MESSAGEAPI, TAVILY, BRAVE, SERPER, GENERIC, SEARXNG, BING, ZHIHU, TOUTIAO, QUORA
    )

    private val SITE_SCOPED: Map<String, String> = mapOf(
        ZHIHU to "zhihu.com",
        TOUTIAO to "toutiao.com",
        QUORA to "quora.com"
    )

    private val ALIASES: Map<String, String> = mapOf(LEGACY_DEEPSEEK to MESSAGEAPI)

    private val IMPLEMENTATIONS: Map<String, SearchProvider> = mapOf(
        MESSAGEAPI to MessageApiProvider,
        TAVILY to TavilyProvider,
        BRAVE to BraveProvider,
        SERPER to SerperProvider,
        GENERIC to GenericProvider,
        SEARXNG to SearxngProvider,
        BING to BingProvider
    )

    /** 空 / 大小写不标准 / 旧值 / 不认识的 provider 一律收敛到合法值（脏配置不该让工具不可用）。 */
    fun normalize(raw: String?): String {
        val value = raw?.trim()?.uppercase().orEmpty()
        ALIASES[value]?.let { return it }
        return if (value in all) value else MESSAGEAPI
    }

    /** 这个 provider 是不是「站点过滤源」。 */
    fun isSiteScoped(provider: String): Boolean = normalize(provider) in SITE_SCOPED

    /** 站点过滤源要拼的域名；不是站点源返回 null。 */
    fun siteFor(provider: String): String? = SITE_SCOPED[normalize(provider)]

    /**
     * 这个 provider 需不需要 key。
     *
     * `SEARXNG` / `GENERIC` 是自建服务，允许没有 key（规范 §1/§5）；
     * 站点过滤源的答案取决于底层引擎，调用方要先把 engine 归一化再问。
     */
    fun requiresApiKey(provider: String): Boolean = when (normalize(provider)) {
        GENERIC, SEARXNG -> false
        else -> true
    }

    /** 不填 baseUrl 时用的默认地址；`SEARXNG` / `GENERIC` / `MESSAGEAPI` 没有可用的固定默认值。 */
    fun defaultBaseUrl(provider: String): String? = when (normalize(provider)) {
        TAVILY -> TAVILY_DEFAULT_BASE
        BRAVE -> BRAVE_DEFAULT_BASE
        SERPER -> SERPER_DEFAULT_BASE
        BING -> BING_DEFAULT_BASE
        else -> null
    }

    /** 站点过滤源底层引擎的归一化：只认 `SEARXNG` / `BING`，其它值退回默认的 SearXNG。 */
    fun normalizeEngine(raw: String?): String = when (val value = raw?.trim()?.uppercase().orEmpty()) {
        SEARXNG -> SEARXNG
        BING -> BING
        else -> DEFAULT_SITE_ENGINE
    }

    /**
     * 结果条数：缺省 6，越界按 `:agentcore` 的 `ToolArgClamp`（1–12）夹取。
     *
     * 为什么不在这里自己写 `coerceIn`：1–12 是 `spec/agent-core/cases/tool-args.json` 钉住的跨端事实，
     * 抄一份就意味着以后改一处漏一处。
     */
    fun normalizeCount(count: Int?): Int = if (count == null) {
        ToolArgClamp.COUNT_DEFAULT
    } else {
        ToolArgClamp.clampCount(count)
    }

    /** provider 名 → 实现。站点源必须由调用方先换成底层引擎（见 [WebSearchService]）。 */
    fun implementation(name: String): SearchProvider {
        val normalized = normalize(name)
        return IMPLEMENTATIONS[normalized] ?: MessageApiProvider
    }
}

/** 一个搜索协议：怎么拼请求、怎么把响应解析成 [WebSearchHit]。 */
interface SearchProvider {
    /** 错误文案里出现的名字（站点过滤源沿用底层引擎名，站点在 query 里）。 */
    val name: String

    fun searchCall(query: String, count: Int, config: ProviderConfig): SearchCall

    /** 解析原始响应；形状不对时返回空列表，**JSON 本身不合法**时抛 [SearchException]。 */
    fun parseHits(body: String): List<WebSearchHit>
}

// ---------------------------------------------------------------------------- 端点

internal object Endpoints {

    /** base 必须是 http(s)、有 host、不含空白/userinfo；否则 [SearchErrorKind.INVALID_BASE]。 */
    fun requireHttpBase(raw: String, provider: String): String {
        val value = raw.trim()
        val scheme = when {
            value.startsWith("http://", ignoreCase = true) -> "http://"
            value.startsWith("https://", ignoreCase = true) -> "https://"
            else -> throw SearchException(
                SearchError(SearchErrorKind.INVALID_BASE, provider, null, "$provider 的地址必须以 http(s):// 开头")
            )
        }
        val authority = value.substring(scheme.length).substringBefore('/').substringBefore('?')
        if (authority.isBlank() || authority.any { it.isWhitespace() } || authority.contains('@')) {
            throw SearchException(
                SearchError(SearchErrorKind.INVALID_BASE, provider, null, "$provider 的地址缺少可用的主机名")
            )
        }
        return value.trimEnd('/')
    }

    /** 解析 base：显式配置优先，否则用 provider 默认值；两者都没有 → [SearchErrorKind.MISSING_BASE]。 */
    fun resolveBase(config: ProviderConfig, provider: String): String {
        val explicit = config.baseUrl.trim()
        if (explicit.isNotEmpty()) return requireHttpBase(explicit, provider)
        val default = Providers.defaultBaseUrl(provider)
        if (default.isNullOrBlank()) {
            throw SearchException(
                SearchError(SearchErrorKind.MISSING_BASE, provider, null, "$provider 需要先配置搜索地址")
            )
        }
        return requireHttpBase(default, provider)
    }

    /** 在 base 后面接一段路径（base 已去掉尾斜杠）。 */
    fun withPath(base: String, path: String): String {
        val clean = path.trim().trimStart('/')
        return if (clean.isEmpty()) base else "$base/$clean"
    }

    /**
     * MESSAGEAPI 的工具端点：`{base}/v1/tools/{tool}`。
     *
     * 与客户端 `WebSearchClient.toolsEndpoint` 同一套归一化：去掉尾斜杠、去掉已经写进来的
     * `/tools/search` `/tools/fetch`，base 已以 `/v1` 结尾就不再补 `/v1`。
     */
    fun messageApi(baseUrl: String, tool: String, provider: String = Providers.MESSAGEAPI): String {
        val explicit = baseUrl.trim()
        if (explicit.isEmpty()) {
            throw SearchException(
                SearchError(SearchErrorKind.MISSING_BASE, provider, null, "$provider 需要先配置搜索地址")
            )
        }
        val base = requireHttpBase(explicit, provider)
            .removeSuffix("/tools/search")
            .removeSuffix("/tools/fetch")
            .trimEnd('/')
        if (base.isEmpty()) {
            throw SearchException(
                SearchError(SearchErrorKind.MISSING_BASE, provider, null, "$provider 需要先配置搜索地址")
            )
        }
        val root = if (base.endsWith("/v1")) base else "$base/v1"
        return "$root/tools/$tool"
    }

    /** SearXNG：地址允许带 `/search` 后缀（用户常常直接粘贴搜索页地址），拼一次就够。 */
    fun searxng(baseUrl: String, provider: String = Providers.SEARXNG): String =
        requireHttpBase(baseUrl.trim(), provider).removeSuffix("/search").trimEnd('/')

    /** Bing：地址允许带 `/v7.0/search` 后缀。 */
    fun bing(baseUrl: String, provider: String = Providers.BING): String =
        requireHttpBase(baseUrl.trim(), provider).removeSuffix("/v7.0/search").trimEnd('/')
}

// ---------------------------------------------------------------------------- 实现

internal fun bearerHeaders(apiKey: String): Map<String, String> =
    if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")

/** `MESSAGEAPI`：0.12 之前的行为，`POST {base}/v1/tools/search` + `Authorization: Bearer`。 */
internal object MessageApiProvider : SearchProvider {
    override val name: String = Providers.MESSAGEAPI

    override fun searchCall(query: String, count: Int, config: ProviderConfig): SearchCall {
        val endpoint = Endpoints.messageApi(config.baseUrl, "search", name)
        val body = JsonSupport.body(
            "query" to JsonSupport.str(query),
            "count" to JsonSupport.num(count),
            // 与客户端一致：默认不要正文（正文是 web_fetch 的活）。
            "need_content" to JsonSupport.bool(false)
        )
        return SearchCall.PostJson(endpoint, bearerHeaders(config.apiKey), body)
    }

    override fun parseHits(body: String): List<WebSearchHit> {
        val root = JsonSupport.parse(body, name)
        val results = when (root) {
            is JsonValue.Arr -> root.arrayObjects()
            is JsonValue.Obj -> {
                val array = root.objects("results").ifEmpty { root.objects("data") }
                array.ifEmpty { root.objects("hits") }
            }

            else -> emptyList()
        }
        return results.map { hit ->
            WebSearchHit(
                title = hit.string("title", "name"),
                url = hit.string("url", "link", "href"),
                snippet = hit.string("snippet", "description", "abstract", "summary", "content")
            )
        }
    }
}

/** `TAVILY`：`POST {base}/search`，header 与 body 都带 key。 */
internal object TavilyProvider : SearchProvider {
    override val name: String = Providers.TAVILY

    override fun searchCall(query: String, count: Int, config: ProviderConfig): SearchCall {
        val base = Endpoints.resolveBase(config, name)
        val endpoint = Endpoints.withPath(base, "search")
        val key = config.apiKey.trim()
        val body = JsonSupport.body(
            // 规范要求 body 里也带 api_key；header 同样带 Authorization，两个都保留。
            "api_key" to JsonSupport.str(key),
            "query" to JsonSupport.str(query),
            "max_results" to JsonSupport.num(count),
            "include_answer" to JsonSupport.bool(false)
        )
        return SearchCall.PostJson(endpoint, bearerHeaders(key), body)
    }

    override fun parseHits(body: String): List<WebSearchHit> {
        val root = JsonSupport.parse(body, name)
        val results = (root as? JsonValue.Obj)?.objects("results").orEmpty()
        return results.map { hit ->
            WebSearchHit(
                title = hit.string("title", "name"),
                url = hit.string("url", "link"),
                snippet = hit.string("content", "snippet", "description")
            )
        }
    }
}

/**
 * `SEARXNG`（0.12 新增）：`GET {base}/search?q=<urlencoded>&format=json&categories=general`。
 *
 * 为什么推荐它：自建、无 key、不按次计费，也不受第三方配额限制；代价是结果质量取决于实例。
 * `format=json` 需要实例在 `settings.yml` 里打开 `search.formats: [html, json]`——没打开时实例会回
 * HTML（或被 WAF 拦），这里会得到 [SearchErrorKind.UNPARSABLE]，比静默空结果更有用。
 */
internal object SearxngProvider : SearchProvider {
    override val name: String = Providers.SEARXNG

    override fun searchCall(query: String, count: Int, config: ProviderConfig): SearchCall {
        if (config.baseUrl.isBlank()) {
            throw SearchException(
                SearchError(SearchErrorKind.MISSING_BASE, name, null, "$name 需要先配置自建实例地址")
            )
        }
        val base = Endpoints.searxng(config.baseUrl, name)
        val url = SearchHttpClient.appendQuery(
            Endpoints.withPath(base, "search"),
            listOf(
                "q" to query,
                "format" to "json",
                "categories" to "general"
            )
        )
        // SearXNG 不需要 key；有些自建实例前面挂了反代 Basic Auth，那属于部署层的事，
        // 不在 provider 里猜（真需要时由反代放行来源 IP）。
        return SearchCall.Get(url)
    }

    override fun parseHits(body: String): List<WebSearchHit> {
        val root = JsonSupport.parse(body, name)
        val results = (root as? JsonValue.Obj)?.objects("results").orEmpty()
        return results.map { hit ->
            WebSearchHit(
                title = hit.string("title"),
                url = hit.string("url"),
                // SearXNG 把摘要放在 content（有时是 content 里的高亮 HTML）：后面统一去标签。
                snippet = hit.string("content", "snippet", "description")
            )
        }
    }

    // 说明：SearXNG 的 `/search` 不吃「条数」参数（一次一页），数量在 [WebSearchService.normalizeHits]
    // 里统一截断，所以这里不传 count —— 与客户端 `GENERIC` 的做法一致。
}

/**
 * `BING`（0.12 新增）：`GET https://api.bing.microsoft.com/v7.0/search?q=&count=&mkt=zh-CN`，
 * 头 `Ocp-Apim-Subscription-Key`。
 *
 * 中文场景固定 `mkt=zh-CN`：Bing 的 `mkt` 会显著影响中文查询的召回与排序，
 * 而 Yap 的人格默认中文（英文查询下 Bing 对 `mkt` 不敏感，固定值不伤）。
 */
internal object BingProvider : SearchProvider {
    override val name: String = Providers.BING

    override fun searchCall(query: String, count: Int, config: ProviderConfig): SearchCall {
        val base = Endpoints.bing(
            config.baseUrl.trim().ifEmpty { Providers.BING_DEFAULT_BASE },
            name
        )
        val url = SearchHttpClient.appendQuery(
            Endpoints.withPath(base, "v7.0/search"),
            listOf(
                "q" to query,
                "count" to count.toString(),
                "mkt" to "zh-CN"
            )
        )
        val headers = if (config.apiKey.isBlank()) {
            emptyMap()
        } else {
            mapOf("Ocp-Apim-Subscription-Key" to config.apiKey.trim())
        }
        return SearchCall.Get(url, headers)
    }

    override fun parseHits(body: String): List<WebSearchHit> {
        val root = JsonSupport.parse(body, name)
        val page = (root as? JsonValue.Obj)?.objOrNull("webPages")
        val results = page?.objects("value").orEmpty()
        return results.map { hit ->
            WebSearchHit(
                title = hit.string("name", "title"),
                url = hit.string("url"),
                snippet = hit.string("snippet", "content", "description")
            )
        }
    }
}

/** `BRAVE`：`GET {base}/web/search?q=&count=`，header `X-Subscription-Token`。 */
internal object BraveProvider : SearchProvider {
    override val name: String = Providers.BRAVE

    override fun searchCall(query: String, count: Int, config: ProviderConfig): SearchCall {
        val base = Endpoints.resolveBase(config, name)
        val url = SearchHttpClient.appendQuery(
            Endpoints.withPath(base, "web/search"),
            listOf("q" to query, "count" to count.toString())
        )
        val headers = if (config.apiKey.isBlank()) {
            emptyMap()
        } else {
            mapOf("X-Subscription-Token" to config.apiKey.trim())
        }
        return SearchCall.Get(url, headers)
    }

    override fun parseHits(body: String): List<WebSearchHit> {
        val root = JsonSupport.parse(body, name)
        val web = (root as? JsonValue.Obj)?.objOrNull("web")
        val results = web?.objects("results").orEmpty()
        return results.map { hit ->
            WebSearchHit(
                title = hit.string("title"),
                url = hit.string("url"),
                snippet = hit.string("description", "snippet")
            )
        }
    }
}

/** `SERPER`：`POST {base}/search`，header `X-API-KEY`，body `{q, num}`。 */
internal object SerperProvider : SearchProvider {
    override val name: String = Providers.SERPER

    override fun searchCall(query: String, count: Int, config: ProviderConfig): SearchCall {
        val base = Endpoints.resolveBase(config, name)
        val endpoint = Endpoints.withPath(base, "search")
        val body = JsonSupport.body(
            "q" to JsonSupport.str(query),
            "num" to JsonSupport.num(count)
        )
        val headers = if (config.apiKey.isBlank()) {
            emptyMap()
        } else {
            mapOf("X-API-KEY" to config.apiKey.trim())
        }
        return SearchCall.PostJson(endpoint, headers, body)
    }

    override fun parseHits(body: String): List<WebSearchHit> {
        val root = JsonSupport.parse(body, name)
        val results = (root as? JsonValue.Obj)?.objects("organic").orEmpty()
        return results.map { hit ->
            WebSearchHit(
                title = hit.string("title"),
                url = hit.string("link", "url"),
                snippet = hit.string("snippet", "description")
            )
        }
    }
}

/** `GENERIC`（自建）：`POST {base}`（地址原样使用），body `{query, count}`。 */
internal object GenericProvider : SearchProvider {
    override val name: String = Providers.GENERIC

    override fun searchCall(query: String, count: Int, config: ProviderConfig): SearchCall {
        if (config.baseUrl.isBlank()) {
            throw SearchException(
                SearchError(SearchErrorKind.MISSING_BASE, name, null, "$name 需要先配置自建服务地址")
            )
        }
        val endpoint = Endpoints.requireHttpBase(config.baseUrl, name)
        val body = JsonSupport.body(
            "query" to JsonSupport.str(query),
            "count" to JsonSupport.num(count)
        )
        return SearchCall.PostJson(endpoint, bearerHeaders(config.apiKey), body)
    }

    override fun parseHits(body: String): List<WebSearchHit> {
        val root = JsonSupport.parse(body, name)
        val results = (root as? JsonValue.Obj)?.objects("results").orEmpty()
        return results.map { hit ->
            WebSearchHit(
                title = hit.string("title", "name"),
                url = hit.string("url", "link"),
                snippet = hit.string("snippet", "content", "description")
            )
        }
    }
}

/**
 * 站点过滤源：`ZHIHU` / `TOUTIAO` / `QUORA`。
 *
 * 它**不是**新协议，也不直接爬站点（那会违反站点条款、还会被登录墙挡住）：
 * 只是把 `site:<域名>` 拼进 query，再交给底层 [base]（SearXNG 或 Bing）去搜。
 * 返回的仍是统一的 `{title,url,snippet}`；正文永远只由 `web_fetch` 抓公开页面。
 *
 * `name` 默认沿用底层引擎名（错误文案里显示「SEARXNG 限流了」比「ZHIHU 限流了」更准确：
 * 限流的是引擎，不是知乎）。
 */
class SiteScopedProvider(
    private val base: SearchProvider,
    private val site: String,
    override val name: String = base.name
) : SearchProvider {

    override fun searchCall(query: String, count: Int, config: ProviderConfig): SearchCall =
        base.searchCall(scopedQuery(query), count, config)

    override fun parseHits(body: String): List<WebSearchHit> = base.parseHits(body)

    /**
     * 拼上 `site:`；查询里已经有一个**独立的** `site:<域名>` 词元就不再重复拼
     * （避免 `site:a site:a`）。按空白切词比较，所以 `mysite:zhihu.com` 不算。
     */
    fun scopedQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return "site:$site"
        val marker = "site:$site"
        val present = trimmed.split(WHITESPACE).any { it.equals(marker, ignoreCase = true) }
        return if (present) trimmed else "$trimmed $marker"
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
