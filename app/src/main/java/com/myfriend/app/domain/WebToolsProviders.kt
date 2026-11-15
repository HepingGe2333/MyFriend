package com.myfriend.app.domain

/**
 * `web_search` / `web_fetch` 支持的后端（`spec/web-search/README.md` §1）。
 *
 * 库里按字符串存（`api_configs.webToolsProvider`），默认 `DEEPSEEK`；
 * 不认得的字符串一律退回默认值——备份或手工 SQL 写进来的脏值不该让整个工具不可用。
 *
 * 这里是「默认地址」的唯一来源：网络层用它拼请求，设置页用它做占位符与说明，
 * 备份导入用它校验 GENERIC 的必填地址。三处各写一份的话，迟早会有一处对不上。
 */
object WebToolsProviders {

    const val DEEPSEEK = "DEEPSEEK"
    const val TAVILY = "TAVILY"
    const val BRAVE = "BRAVE"
    const val SERPER = "SERPER"
    const val GENERIC = "GENERIC"

    /** 设置页展示顺序。 */
    val all: List<String> = listOf(DEEPSEEK, TAVILY, BRAVE, SERPER, GENERIC)

    /**
     * 空 / 大小写不标准 / 不认识的 provider 一律收敛到合法值。
     *
     * 备份是外部输入，可能来自旧版本或手写文件；这里不抛错，只保证下游拿到的一定是
     * [all] 里的一个。
     */
    fun normalize(raw: String?): String {
        val value = raw?.trim()?.uppercase().orEmpty()
        return if (value in all) value else DEEPSEEK
    }

    fun isValid(raw: String?): Boolean = raw?.trim()?.uppercase() in all

    /**
     * 不填 `webToolsBaseUrl` 时用的地址。
     *
     * `DEEPSEEK` 没有固定地址：沿用 LLM 配置的 base URL（见 [WebSearchClient] 的调用方），
     * 所以这里返回 null；`GENERIC` 是自建服务，规范要求必须显式填写，也返回 null。
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

    /** DEEPSEEK 的兜底地址：LLM 配置里也没有 base URL 时才会用到。 */
    const val DEEPSEEK_FALLBACK_BASE = "https://api.deepseek.com"
}
