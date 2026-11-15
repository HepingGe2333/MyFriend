package com.myfriend.agentd.search

/**
 * 服务端联网搜索的数据类型与错误模型（`docs/design/server-authoritative.md` §3.5）。
 *
 * 这里是「服务端自己发起搜索」的那一层：客户端不再持有搜索 key，也不再自己联网。
 * 归一化规则与客户端 `app/src/main/java/com/myfriend/app/network/WebSearchClient.kt` 逐字一致
 * （见 `spec/web-search/README.md` §3）：丢非 http(s)、按 URL 去重、snippet ≤500、最多 count 条。
 *
 * 这一层**不碰** SQLite / 路由 / API 配置表：调用方把 provider、base、key 传进来，
 * 结果要么是 [WebSearchHit] 列表，要么是带 provider 与状态码的 [SearchError]。
 */
data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String
)

/** `web_fetch` 的结果：正文已经去标签、压空白并截断到 [SearchLimits.MAX_FETCH_CHARS]。 */
data class WebFetchResult(
    val url: String,
    val title: String,
    val text: String
)

/** 一次搜索请求。 */
data class SearchRequest(
    val query: String,
    /** 1–12，默认 [SearchLimits.DEFAULT_COUNT]；越界按 [com.myfriend.app.domain.ToolArgClamp] 夹取。 */
    val count: Int = SearchLimits.DEFAULT_COUNT,
    /** `MESSAGEAPI` / `TAVILY` / `SEARXNG` / `BING` / `ZHIHU` / `TOUTIAO` / `QUORA` …（脏值会归一化）。 */
    val provider: String = Providers.MESSAGEAPI,
    /** provider 的地址；留空时用 provider 默认地址（`SEARXNG` / `GENERIC` / `MESSAGEAPI` 必须显式给）。 */
    val baseUrl: String = "",
    /** provider 的 key；`SEARXNG` 与 `GENERIC` 允许为空。 */
    val apiKey: String = "",
    /**
     * 站点过滤源（`ZHIHU` / `TOUTIAO` / `QUORA`）底层要用的搜索引擎：`SEARXNG`（默认）或 `BING`。
     *
     * 这不是新协议：站点源只是把 `site:` 拼进 query 再走这个引擎（§3.5.1）。
     */
    val engine: String = Providers.SEARXNG
)

/**
 * 单个 provider 的地址、密钥与名字。
 *
 * [provider] 只有 `webFetch` 用得到：`MESSAGEAPI` 的抓取仍走服务端 `POST {base}/v1/tools/fetch`
 * （与 0.12 之前一致），其它 provider 直连目标网页（见 [WebSearchService.webFetch]）。
 */
data class ProviderConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val provider: String = Providers.MESSAGEAPI
)

/** 错误类别；HTTP 状态只在 [SearchError.status] 里出现，文案永远不含 key。 */
enum class SearchErrorKind {
    /** 查询是空白。 */
    EMPTY_QUERY,

    /** URL 是空白（`web_fetch`）。 */
    EMPTY_URL,

    /** 这个 provider 需要 key，但配置里没有。规范 §5：绝不静默返回空结果。 */
    MISSING_KEY,

    /** 这个 provider 需要显式地址（`SEARXNG` / `GENERIC` / `MESSAGEAPI`），但配置里没有。 */
    MISSING_BASE,

    /** 地址不是 http(s)，或者拼出来不是合法 URI。 */
    INVALID_BASE,

    /** `web_fetch` 的目标 URL 没过 [com.myfriend.app.domain.WebUrlPolicy]（非 http(s)/本机/内网/带 userinfo）。 */
    URL_REJECTED,

    /** HTTP 401：key 无效。 */
    UNAUTHORIZED,

    /** HTTP 403：key 没权限 / 配额被禁用。 */
    FORBIDDEN,

    /** HTTP 429：限流。 */
    RATE_LIMITED,

    /** HTTP 5xx：provider 侧故障。 */
    SERVER_ERROR,

    /** 其它非 2xx（400 / 404 / 其它 4xx）。 */
    HTTP_STATUS,

    /** 连接/请求超时（10 秒）。 */
    TIMEOUT,

    /** DNS 解析不到。 */
    UNKNOWN_HOST,

    /** 其它网络错误（连接被拒、连接中断、TLS 失败…）。 */
    NETWORK,

    /** 响应体超过 [SearchLimits.MAX_RESPONSE_BYTES]（2 MiB）。 */
    RESPONSE_TOO_LARGE,

    /** provider 返回的不是 JSON，或者根节点形状完全不对。 */
    UNPARSABLE
}

/**
 * 一次搜索/抓取失败。
 *
 * 故意用 data class（而不是把 provider/status 塞进继承层级）：调用方按 [kind] 做分支、
 * 按 [provider] + [status] 写日志即可，`message` 是**可以直接给模型看**的一句话，
 * 原始响应体只进服务端 stderr（且最多 200 字符，见 [SearchHttpClient]）。
 */
data class SearchError(
    val kind: SearchErrorKind,
    val provider: String,
    val status: Int? = null,
    val message: String
)

/** [SearchError] 的异常包装：路由层 catch 它并翻成 4xx/5xx。 */
class SearchException(val error: SearchError) : Exception(error.message) {
    val kind: SearchErrorKind get() = error.kind
}

/** 与客户端 `WebSearchClient` 完全一致的常量（改一处就两边一起改）。 */
object SearchLimits {
    const val DEFAULT_COUNT = 6
    const val MIN_COUNT = 1
    const val MAX_COUNT = 12

    /** 单条 snippet 的字符上限。 */
    const val MAX_SNIPPET_CHARS = 500

    /** 抓取正文的字符上限。 */
    const val MAX_FETCH_CHARS = 8_000

    /** 标题的字符上限（只用于 `<title>` 抽取）。 */
    const val MAX_TITLE_CHARS = 200

    /** 响应体字节上限：2 MiB。 */
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    /** 错误响应体写日志前的截断长度（规范 §5）。 */
    const val MAX_LOGGED_BODY_CHARS = 200

    /** 连接超时与请求超时都是 10 秒。 */
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val REQUEST_TIMEOUT_MS = 10_000L

    /** 固定 UA；抓网页与调 provider 都是这一个。 */
    const val USER_AGENT = "MyFriend/1.0"
}
