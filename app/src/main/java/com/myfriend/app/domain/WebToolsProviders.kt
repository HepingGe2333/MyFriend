package com.myfriend.app.domain

/**
 * `web_search` / `web_fetch` 支持的后端（`spec/web-search/README.md` §1）。
 *
 * 库里按字符串存（`api_configs.webToolsProvider`），默认 `MESSAGEAPI`；
 * 不认得的字符串一律退回默认值——备份或手工 SQL 写进来的脏值不该让整个工具不可用。
 *
 * ## 0.12 改名：`DEEPSEEK` → `MESSAGEAPI`
 *
 * 这个值以前叫 `DEEPSEEK`，指向 DeepSeek 官方的 `/v1/tools/search`、`/v1/tools/fetch`。
 * 现在叫 [MESSAGEAPI]：它代表「任何提供同一套 MessageAPI tools 接口的厂商」，
 * DeepSeek 官方只是默认实现（默认 base URL 语义不变：留空时沿用 LLM 配置的 base）。
 *
 * 兼容策略只有一条：**读旧值、写新值**。
 * - 读库 / 读备份：[normalize] 把旧的 `DEEPSEEK`（大小写、前后空白都不敏感）折算成 [MESSAGEAPI]；
 * - 写库 / 写备份：一律写 [MESSAGEAPI]；
 * - 数据库列的默认值与 `app/schemas/12.json` 仍是 `'DEEPSEEK'`——已装 v12 的库改默认值会让
 *   Room 的 schema 校验失败，所以归一只发生在代码层（见 [com.myfriend.app.data.local.ApiConfigEntity]）。
 *
 * 这里是「默认地址」的唯一来源：网络层用它拼请求，设置页用它做占位符与说明，
 * 备份导入用它校验 GENERIC 的必填地址。三处各写一份的话，迟早会有一处对不上。
 */
object WebToolsProviders {

    /** 「MessageAPI 兼容 tools 接口」的后端（DeepSeek 官方为默认实现）。 */
    const val MESSAGEAPI = "MESSAGEAPI"
    const val TAVILY = "TAVILY"
    const val BRAVE = "BRAVE"
    const val SERPER = "SERPER"
    const val GENERIC = "GENERIC"

    /**
     * 0.12 之前的旧值。
     *
     * 只在**读**旧库 / 旧备份时出现，[normalize] 会把它折算成 [MESSAGEAPI]；
     * 任何写入路径都不该再产生这个值。
     */
    const val LEGACY_DEEPSEEK = "DEEPSEEK"

    /** 设置页展示顺序。 */
    val all: List<String> = listOf(MESSAGEAPI, TAVILY, BRAVE, SERPER, GENERIC)

    /**
     * 旧值 → 新值的别名表。
     *
     * 加新别名只动这里：读库、读备份、UI、网络层都走 [normalize]，不会各记一份。
     */
    private val aliases: Map<String, String> = mapOf(LEGACY_DEEPSEEK to MESSAGEAPI)

    /**
     * 空 / 大小写不标准 / 旧版本的值 / 不认识的 provider 一律收敛到合法值。
     *
     * 备份是外部输入，可能来自旧版本或手写文件；这里不抛错，只保证下游拿到的一定是
     * [all] 里的一个。旧的 `DEEPSEEK` 是「同一套 tools 接口」的旧名字，折算成 [MESSAGEAPI]。
     */
    fun normalize(raw: String?): String {
        val value = raw?.trim()?.uppercase().orEmpty()
        aliases[value]?.let { return it }
        return if (value in all) value else MESSAGEAPI
    }

    /**
     * 这个字符串是不是「认识的 provider」——含 0.12 之前的旧值。
     *
     * 旧值也算合法：它只是旧版本写出来的，行为与新值逐字一致，不该被当成脏数据。
     */
    fun isValid(raw: String?): Boolean {
        val value = raw?.trim()?.uppercase().orEmpty()
        return value in all || value in aliases
    }

    /**
     * 不填 `webToolsBaseUrl` 时用的地址。
     *
     * [MESSAGEAPI] 没有固定地址：沿用 LLM 配置的 base URL（见 [com.myfriend.app.network.WebSearchClient]
     * 的调用方），所以这里返回 null；`GENERIC` 是自建服务，规范要求必须显式填写，也返回 null。
     */
    fun defaultBaseUrl(provider: String): String? = when (normalize(provider)) {
        TAVILY -> "https://api.tavily.com"
        BRAVE -> "https://api.search.brave.com/res/v1"
        SERPER -> "https://google.serper.dev"
        else -> null
    }

    /** GENERIC 是唯一允许「没有 Key」的 provider；其余缺 Key 必须报错，不能静默空结果。 */
    fun requiresApiKey(provider: String): Boolean = normalize(provider) != GENERIC

    /** 是否允许把用户手填的 base URL 指向内网/本机（只有自建 GENERIC 需要）。 */
    fun allowsPrivateBaseUrl(provider: String): Boolean = normalize(provider) == GENERIC

    /**
     * [MESSAGEAPI] 的兜底地址：LLM 配置里也没有 base URL 时才会用到。
     *
     * 值仍是 DeepSeek 官方的地址（默认实现），但语义是「MessageAPI 兼容接口的默认实现地址」。
     */
    const val MESSAGEAPI_FALLBACK_BASE = "https://api.deepseek.com"
}
