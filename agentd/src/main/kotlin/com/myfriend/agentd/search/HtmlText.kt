package com.myfriend.agentd.search

/**
 * HTML → 纯文本：服务端 `web_fetch` 的正文抽取。
 *
 * 与客户端 `WebSearchClient.htmlToText` / `extractHtmlTitle` 同一套规则（`spec/web-search/README.md` §4）：
 * - 先整段去掉 `<head>`、`<script>`、`<style>`、注释；
 * - 其余标签换成空格（不是删掉：`<p>a</p><p>b</p>` 不能粘成 `ab`）；
 * - 解常见实体（`&nbsp;` `&amp;` `&#39;` …）；
 * - 空白压成一个空格再 trim。
 *
 * 刻意不做「正文抽取」（Readability 那种）：服务端只需要一段可以塞进上下文的纯文本，
 * 而任何启发式正文抽取都会在新闻页/论坛页上出各种边角错误，收益不抵维护成本。
 */
object HtmlText {

    private val HEAD = Regex("""<head\b[^>]*>[\s\S]*?</head\s*>""", setOf(RegexOption.IGNORE_CASE))
    private val COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val SCRIPT_STYLE = Regex(
        """<(script|style|noscript|template)\b[^>]*>[\s\S]*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val TAG = Regex("""<[^>]*>""")
    private val TITLE = Regex("""<title\b[^>]*>([\s\S]*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE))
    private val WHITESPACE = Regex("""\s+""")
    private val NUMERIC_ENTITY = Regex("""&#(x[0-9a-fA-F]+|\d+);""")
    private val NAMED_ENTITIES = listOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&hellip;" to "…",
        "&middot;" to "·",
        "&ldquo;" to "“",
        "&rdquo;" to "”",
        "&lsquo;" to "‘",
        "&rsquo;" to "’"
    )

    /** `<head>`/script/style/注释/标签/实体/空白全部处理完的纯文本。 */
    fun toText(html: String): String {
        if (html.isBlank()) return ""
        var text = html
        text = COMMENT.replace(text, " ")
        text = HEAD.replace(text, " ")
        text = SCRIPT_STYLE.replace(text, " ")
        text = TAG.replace(text, " ")
        text = decodeEntities(text)
        return WHITESPACE.replace(text, " ").trim()
    }

    /**
     * 抽 `<title>`；一定要在 [toText] 之前从原文里取，否则标题会被并进正文、或被 `<head>` 规则删掉。
     *
     * 返回的标题同样是纯文本，并且截到 [SearchLimits.MAX_TITLE_CHARS]。
     */
    fun title(html: String): String {
        if (html.isBlank()) return ""
        val raw = TITLE.find(html)?.groupValues?.getOrNull(1).orEmpty()
        return toText(raw).take(SearchLimits.MAX_TITLE_CHARS)
    }

    /** 解码命名实体与数字实体（`&#39;` / `&#x27;`）。认不出的实体原样保留。 */
    private fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var decoded = text
        NAMED_ENTITIES.forEach { (entity, value) -> decoded = decoded.replace(entity, value) }
        return NUMERIC_ENTITY.replace(decoded) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith("x") || raw.startsWith("X")) {
                raw.substring(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
    }
}
