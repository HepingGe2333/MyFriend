package com.yap.app.data.local

import com.yap.app.domain.ChineseTerms
import com.yap.app.domain.SearchRanking

/**
 * 一条要交给 Room `@RawQuery` 的 SQL 与它的绑定参数。
 *
 * SQL 里**只有**本文件拼出来的固定片段和 `?` 占位符；用户输入一律进 [args]。
 * 这样即使 query 里写着 `%`、`'`、`; DROP TABLE`，它也只是一段被绑定的字符串。
 */
data class SqlQuery(val sql: String, val args: List<Any?>)

/**
 * 聊天记录检索的 SQL（v11：逐块扫描，无候选上限）。
 *
 * ## 两条 SQL，各管一件事
 *
 * 1. [blocksQuery]：**枚举块**（只有 `(sessionId, blockIndex)` 两列 + 时间范围过滤），
 *    人格过滤在这里通过 `chat_sessions` 完成；时间过滤靠块的首尾时间直接跳过整块；
 * 2. [blockScanQuery]：**读一块**，`WHERE m.sessionId = ? AND m.blockIndex = ?`，
 *    再加若干个 `LIKE` 词元。一块最多 [com.yap.app.domain.MessageBlocks.BLOCK_SIZE] 条，
 *    所以这条 SQL 的读取量与历史长度无关。
 *
 * 0.10.2 的 `candidateQuery` 是「一条 SQL 扫全表 + `LIMIT 500`」：命中超过 500 条时更早的
 * 那些永远到不了界面。现在没有候选池，也不需要 `COUNT(*)` 总数预查询——总数在逐块扫描时
 * 顺路数出来（见 `com.yap.app.domain.BlockSearch`）。
 *
 * ## 为什么每个词元都要一个 `LIKE`
 *
 * 中文没有空格，`LIKE '%项目压力%'` 找不到「项目压得我睡不着」。词元是字二元组
 * （`项目` / `目压` / `压力`），任何一个在正文里出现都算命中 —— 这正是
 * `SearchRanking` 的命中判定，两边用同一份词元列表（[SearchRanking.MAX_QUERY_TERMS]）。
 *
 * ## 注入与转义
 *
 * - **参数化**：所有值都是绑定参数（`?`），不是字符串拼接；
 * - **LIKE 转义**：`%` / `_` / `\` 会被转义并用 `ESCAPE '\'` 声明。用户输入里的 `%`
 *   必须当普通字符（「100%」搜不到「100 分」），这条有单测钉死。
 */
object MessageSearchSql {

    /** LIKE 的转义字符；SQL 里用 `ESCAPE '\'` 声明。 */
    private const val ESCAPE_CHAR = '\\'

    private const val SELECT_COLUMNS = """
        SELECT m.id AS messageId, m.sessionId AS sessionId, s.title AS sessionTitle,
        s.personaId AS personaId, p.name AS personaName, p.avatarUri AS personaAvatarUri,
        p.avatarSeed AS personaAvatarSeed, m.content AS content, m.createdAt AS createdAt,
        m.role AS role
        FROM messages m
        INNER JOIN chat_sessions s ON s.id = m.sessionId
        INNER JOIN personas p ON p.id = s.personaId
    """

    /**
     * 粗筛命中的 `LIKE` 模式：先原文（整体包含），再每个词元。
     *
     * 顺序固定（原文在前），因为「整体包含」是最强信号，SQL 的 `OR` 短路对它最友好。
     * 与原文相同的词元会被去掉，避免同一个模式查两遍。
     */
    fun likePatterns(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val patterns = LinkedHashSet<String>()
        patterns += likePattern(trimmed)
        ChineseTerms.queryTerms(trimmed, SearchRanking.MAX_QUERY_TERMS)
            .filter { it.isNotBlank() && it != trimmed }
            .forEach { patterns += likePattern(it) }
        return patterns.toList()
    }

    /**
     * 把一段用户输入变成 `LIKE` 模式（`%value%`），并转义 `%` / `_` / `\`。
     *
     * 注意这是**语义转义**不是安全措施：安全由参数化负责。转义的目的是让 `%`、`_`
     * 回到「普通字符」的意思 —— 不然搜「100%」会变成「以 100 开头的所有东西」。
     */
    fun likePattern(value: String): String = "%" + escapeLike(value) + "%"

    /** 逐字符转义，保证 SQL 里 `ESCAPE '\'` 之后 `%` / `_` 是字面量。 */
    fun escapeLike(value: String): String {
        if (value.isEmpty()) return value
        val out = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '%', '_', ESCAPE_CHAR -> out.append(ESCAPE_CHAR).append(ch)
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * 读一块：`WHERE m.sessionId = ? AND m.blockIndex = ?` + 时间过滤 + 词元 `LIKE`。
     *
     * 刻意**不加 `LIMIT`**：一块的条数由 [com.yap.app.domain.MessageBlocks] 保证
     * （≤ BLOCK_SIZE），加 `LIMIT` 反而会让「块内超出一条」被静默截断，检索结果少一条
     * 是用户能看见、但很难解释的缺陷。
     *
     * `ORDER BY (createdAt, id)` 让块内顺序稳定；精排仍由 `SearchRanking` / `BlockSearch`
     * 在内存里做（SQL 的顺序不参与最终排序）。
     */
    fun blockScanQuery(
        text: String,
        sessionId: String,
        blockIndex: Int,
        since: Long? = null,
        until: Long? = null
    ): SqlQuery {
        val patterns = likePatterns(text)
        if (patterns.isEmpty()) return SqlQuery("", emptyList())
        val args = ArrayList<Any?>(4 + patterns.size)
        args += sessionId
        args += blockIndex
        val sql = buildString {
            append(SELECT_COLUMNS.trimIndent())
            append("\n WHERE m.sessionId = ? AND m.blockIndex = ?")
            append("\n  AND m.content != ''")
            if (since != null) {
                append("\n  AND m.createdAt >= ?")
                args += since
            }
            if (until != null) {
                append("\n  AND m.createdAt <= ?")
                args += until
            }
            append("\n  AND (")
            append(patterns.joinToString("\n     OR ") { "m.content LIKE ? ESCAPE '\\'" })
            append(")\n ORDER BY m.createdAt ASC, m.id ASC")
        }
        args.addAll(patterns)
        return SqlQuery(sql, args)
    }

    /**
     * 枚举块：只回 `(sessionId, blockIndex)`，按 `(sessionId, blockIndex)` 升序。
     *
     * 人格过滤走 `chat_sessions`（块表上没有 personaId）；时间过滤按**块的首尾时间**判断重叠，
     * 整块落在区间外就直接跳过，不必把它读进内存再丢。
     */
    fun blocksQuery(
        personaId: String? = null,
        since: Long? = null,
        until: Long? = null
    ): SqlQuery {
        val args = ArrayList<Any?>(3)
        val sql = buildString {
            append("SELECT b.sessionId AS sessionId, b.blockIndex AS blockIndex")
            append("\n FROM message_blocks b")
            append("\n INNER JOIN chat_sessions s ON s.id = b.sessionId")
            append("\n WHERE 1 = 1")
            if (personaId != null) {
                append("\n  AND s.personaId = ?")
                args += personaId
            }
            if (since != null) {
                // 块的最后一条比下界还早 → 整块都在区间外。
                append("\n  AND b.lastCreatedAt >= ?")
                args += since
            }
            if (until != null) {
                append("\n  AND b.firstCreatedAt <= ?")
                args += until
            }
            append("\n ORDER BY b.sessionId ASC, b.blockIndex ASC")
        }
        return SqlQuery(sql, args)
    }

    /**
     * 「最近若干条命中」——**只给后台回忆用**，不属于检索契约。
     *
     * 回忆（`RecallRepository`）要的是「从最近的历史里捞几句相关的话」，本身有
     * `SEARCH_LIMIT` 条数的启发式上限，也不做翻页；所以这里保留一条有界查询，
     * 而不是让它去走逐块扫描。检索页（`ChatRepository.searchMessages`）不用它。
     */
    fun recentCandidatesQuery(
        text: String,
        personaId: String? = null,
        limit: Int
    ): SqlQuery {
        val patterns = likePatterns(text)
        if (patterns.isEmpty()) return SqlQuery("", emptyList())
        val args = ArrayList<Any?>(2 + patterns.size)
        val sql = buildString {
            append(SELECT_COLUMNS.trimIndent())
            append("\n WHERE m.content != ''")
            if (personaId != null) {
                append("\n  AND s.personaId = ?")
                args += personaId
            }
            append("\n  AND (")
            append(patterns.joinToString("\n     OR ") { "m.content LIKE ? ESCAPE '\\'" })
            append(")\n ORDER BY m.createdAt DESC, m.id ASC")
            append("\n LIMIT ?")
        }
        args.addAll(patterns)
        args += limit.coerceAtLeast(1)
        return SqlQuery(sql, args)
    }
}
