package com.myfriend.app.data.local

import com.myfriend.app.domain.ChineseTerms
import com.myfriend.app.domain.SearchRanking

/**
 * 一条要交给 Room `@RawQuery` 的 SQL 与它的绑定参数。
 *
 * SQL 里**只有**本文件拼出来的固定片段和 `?` 占位符；用户输入一律进 [args]。
 * 这样即使 query 里写着 `%`、`'`、`; DROP TABLE`，它也只是一段被绑定的字符串。
 */
data class SqlQuery(val sql: String, val args: List<Any?>)

/**
 * 聊天记录检索的 SQL 粗筛。
 *
 * ## 为什么不建 `message_terms` 倒排表（取舍）
 *
 * 两条路都试过（见 0.10.2 的 review 记录），最后选「LIKE 粗筛 + 内存精排」：
 *
 * - 建倒排表要把 schema 升到 v11、写 `MIGRATION_10_11`、在消息写入 / 编辑 / 删除 / 导入 /
 *   备份恢复**每一条**路径上同步维护，漏一处就是「搜得到旧内容、搜不到新内容」这种
 *   只在用户手机上才看得见的错；备份恢复还会因为「派生索引不进备份」而必须重建一次。
 * - 聊天记录的消息量级（一个人聊几个月也就几万条）根本用不上倒排，LIKE 全表扫一遍在
 *   中端机上是个位数毫秒到几十毫秒的量级，而检索是用户主动触发、有 250ms 防抖的低频操作。
 *
 * 代价写在报告里：**召回上限是 [CANDIDATE_LIMIT] 条**（只取最近的 500 条粗筛命中再精排），
 * 超过 500 条命中的超长历史，更早的命中到不了界面（界面会显示「结果太多，只显示最近的部分」）。
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

    /**
     * 粗筛候选池上限。
     *
     * 500 的依据：一次检索最多精排 500 条正文（几十万字符），在低端机上也在十几毫秒内；
     * 而正常人的历史里，任意关键词命中 500 条以上已经很罕见，真出现时用户要的也不是「翻到第 40 页」。
     */
    const val CANDIDATE_LIMIT = 500

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

    /** `WHERE` 之后、`AND (...LIKE...)` 之前的那一段（人格 / 时间过滤），参数顺序与 SQL 一致。 */
    private fun filters(personaId: String?, since: Long?, until: Long?): Pair<String, List<Any?>> {
        val clauses = StringBuilder()
        val args = ArrayList<Any?>(3)
        if (personaId != null) {
            clauses.append("\n  AND s.personaId = ?")
            args += personaId
        }
        if (since != null) {
            clauses.append("\n  AND m.createdAt >= ?")
            args += since
        }
        if (until != null) {
            clauses.append("\n  AND m.createdAt <= ?")
            args += until
        }
        return clauses.toString() to args
    }

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
     * 候选查询：按 `createdAt DESC, id ASC` 取最近 [limit] 条粗筛命中。
     *
     * SQL 的排序只是为了「粗筛取哪些」是确定的（同样命中的情况下先取最近的），
     * 真正的展示顺序由 [SearchRanking.rank] 在内存里重新算。
     */
    fun candidateQuery(
        text: String,
        personaId: String? = null,
        since: Long? = null,
        until: Long? = null,
        limit: Int = CANDIDATE_LIMIT
    ): SqlQuery {
        val patterns = likePatterns(text)
        if (patterns.isEmpty()) return SqlQuery("", emptyList())
        val (filterClauses, filterArgs) = filters(personaId, since, until)
        val likeClauses = patterns.joinToString("\n     OR ") { "m.content LIKE ? ESCAPE '\\'" }
        val sql = buildString {
            append(SELECT_COLUMNS.trimIndent())
            append("\n WHERE m.content != ''")
            append(filterClauses)
            append("\n  AND (")
            append(likeClauses)
            append(")\n ORDER BY m.createdAt DESC, m.id ASC")
            append("\n LIMIT ?")
        }
        return SqlQuery(sql, filterArgs + patterns + limit.coerceAtLeast(1))
    }

    /** 总数查询：与 [candidateQuery] 同一套过滤条件，用来告诉界面「一共有多少条命中」。 */
    fun countQuery(
        text: String,
        personaId: String? = null,
        since: Long? = null,
        until: Long? = null
    ): SqlQuery {
        val patterns = likePatterns(text)
        if (patterns.isEmpty()) return SqlQuery("", emptyList())
        val (filterClauses, filterArgs) = filters(personaId, since, until)
        val likeClauses = patterns.joinToString("\n     OR ") { "m.content LIKE ? ESCAPE '\\'" }
        val sql = buildString {
            append("SELECT COUNT(*) AS total FROM messages m")
            append("\n INNER JOIN chat_sessions s ON s.id = m.sessionId")
            append("\n INNER JOIN personas p ON p.id = s.personaId")
            append("\n WHERE m.content != ''")
            append(filterClauses)
            append("\n  AND (")
            append(likeClauses)
            append(")")
        }
        return SqlQuery(sql, filterArgs + patterns)
    }
}
