package com.myfriend.app.domain

/**
 * 一条检索命中要显示的那一小段话（摘要 + 高亮）。
 *
 * `text` 是**已经截好**的展示文本（可能带首尾省略号），`highlights` 是相对 `text` 的
 * UTF-16 偏移区间。这里用 Kotlin 的 `IntRange`，它是**两端闭**的：规格里的
 * `[start, end)` 落成 Kotlin 就是 `start..(end - 1)`（规格见 `spec/search/README.md`）。
 *
 * 为什么强调闭区间：`[3, 6)` 是三个字符，写成 `3..6` 就变成四个字符，高亮的最后一个字
 * 会多亮一格 —— 这种错在肉眼上极难发现，但两端截图一比就露馅。
 */
data class Snippet(val text: String, val highlights: List<IntRange>) {
    val isEmpty: Boolean get() = text.isEmpty()
}

/** 渲染用的一段：要么整段正常，要么整段高亮。相邻片段拼起来就是 [Snippet.text]。 */
data class Segment(val text: String, val highlighted: Boolean)

/**
 * 聊天记录检索的摘要与高亮 —— 两端共用的一份实现（iOS 侧 `SearchSnippet` 与
 * `spec/search/cases/snippet.json` 逐条对齐）。
 *
 * ## 规则（与 `spec/search/README.md` 的 Snippet rules 一一对应）
 *
 * 1. `query` 为空（trim 后）→ `text = ""`，`highlights = []`；
 * 2. 在 `content` 里找**第一个**大小写不敏感的出现位置 `index`；
 * 3. 找不到 → `text` 取 `content` 的前 `window` 个字符，被截断就补一个「…」，无高亮；
 * 4. 找到 → `start = max(0, index - 30)`、`end = min(length, start + window)`；
 *    若 `start > 0 && end - start < window` 再把 `start = max(0, end - window)`（窗口不够就整体左滑）；
 *    文本 = 左侧省略号（`start > 0` 时）+ `content[start:end]` + 右侧省略号（`end < length` 时）；
 * 5. `highlights` 是这个窗口内**所有**大小写不敏感的出现，偏移要加上左侧省略号那 1 个字符。
 *
 * 字符一律按 UTF-16 code unit 计数（Kotlin `String` 的下标），和规格一致。
 *
 * ## 两个规格没写死、由这里定下来的细节
 *
 * - **重叠出现只算不重叠的那一种**：`content = "aaa"`、`query = "aa"` 只高亮 `[0,2)`，
 *   不会再把 `[1,3)` 也算进去。理由是高亮是给眼睛看的，重叠区间渲染出来会变成一条更长的
 *   高亮，反而让人以为命中的是「aaa」。iOS 侧按同样规则实现即可。
 * - `window <= 0` 一律返回空（没有窗口就没有摘要），不会抛异常。
 *
 * 纯 Kotlin：不碰 Android、不读时钟、不查库，住在 `:agentcore`、由 `:app` 与 `:agentd` 共用。
 */
object SearchSnippet {

    /** 默认窗口：60 个 UTF-16 code unit，正好是「一行半」的摘要长度。 */
    const val WINDOW = 60

    /** 命中位置左边留多少上下文：30 个字符，中文大约是一句话的前半段。 */
    private const val CONTEXT_BEFORE = 30

    private const val ELLIPSIS = "…"

    /**
     * 按规格生成摘要。
     *
     * @param content 消息正文（原样，不做换行折叠：折叠会改变偏移，高亮就会错位）
     * @param query 检索词（规格约定已 trim；这里对空白串仍然短路）
     * @param window 窗口长度，默认 [WINDOW]
     */
    fun build(content: String, query: String, window: Int = WINDOW): Snippet {
        if (content.isEmpty() || query.isBlank() || window <= 0) return Snippet("", emptyList())

        val index = content.indexOf(query, ignoreCase = true)
        if (index < 0) {
            val end = minOf(window, content.length)
            val text = content.substring(0, end) + if (end < content.length) ELLIPSIS else ""
            return Snippet(text, emptyList())
        }

        var start = (index - CONTEXT_BEFORE).coerceAtLeast(0)
        val end = minOf(content.length, start + window)
        if (start > 0 && end - start < window) start = (end - window).coerceAtLeast(0)

        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < content.length) ELLIPSIS else ""
        val body = content.substring(start, end)
        val highlights = occurrences(body, query).map { (from, to) ->
            (from + prefix.length)..(to + prefix.length - 1)
        }
        return Snippet(prefix + body + suffix, highlights)
    }

    /** [build] 的便捷重载：直接给渲染用的分段。 */
    fun segments(content: String, query: String, window: Int = WINDOW): List<Segment> =
        segments(build(content, query, window))

    /**
     * 把摘要切成「高亮 / 不高亮」的相邻片段，UI 直接按顺序拼 AnnotatedString 就行。
     *
     * 空摘要返回空列表（调用方据此决定要不要显示占位）；没有高亮时返回一整段普通文本。
     */
    fun segments(snippet: Snippet): List<Segment> {
        val text = snippet.text
        if (text.isEmpty()) return emptyList()
        val ranges = snippet.highlights
            .map { range -> range.first.coerceIn(0, text.length) to (range.last + 1).coerceIn(0, text.length) }
            .filter { (from, to) -> to > from }
            .sortedBy { it.first }
        if (ranges.isEmpty()) return listOf(Segment(text, false))

        val out = ArrayList<Segment>(ranges.size * 2 + 1)
        var cursor = 0
        ranges.forEach { (from, to) ->
            if (from > cursor) out += Segment(text.substring(cursor, from), false)
            out += Segment(text.substring(from, to), true)
            cursor = maxOf(cursor, to)
        }
        if (cursor < text.length) out += Segment(text.substring(cursor), false)
        return out
    }

    /**
     * 所有大小写不敏感的出现位置（不重叠，见类注释）。
     *
     * 返回 `[from, to)` 半开区间，`from`/`to` 是 `haystack` 里的 UTF-16 下标。
     */
    private fun occurrences(haystack: String, needle: String): List<Pair<Int, Int>> {
        if (needle.isEmpty() || haystack.isEmpty() || needle.length > haystack.length) return emptyList()
        val result = ArrayList<Pair<Int, Int>>(4)
        var from = 0
        val limit = haystack.length - needle.length
        while (from <= limit) {
            val at = haystack.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            result += at to at + needle.length
            from = at + needle.length
        }
        return result
    }
}
