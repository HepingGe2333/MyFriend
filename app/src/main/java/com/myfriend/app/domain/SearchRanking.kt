package com.myfriend.app.domain

/**
 * 排序只看这四样东西：主键、正文、时间、人格。
 *
 * 刻意做成这个窄形状（而不是直接用 Room 实体），是为了让「命中判定 + 排序」成为
 * 一个可以在普通 JVM 单测里钉死的纯函数：`spec/search/cases/ranking.json` 的
 * `input.messages` 正好能映射成它。
 */
data class RankInput(
    val id: String,
    val content: String,
    val createdAt: Long,
    val personaId: String? = null
)

/**
 * 检索过滤（规范：过滤在排序**之前**应用，且不改变剩下命中之间的相对顺序）。
 *
 * - [personaId] null = 所有人格；
 * - [since] / [until] null = 不限时间；区间是**闭区间**（用例里 `since = 2000` 要保留 createdAt = 2000 的那条）。
 *
 * [until] 不在跨端契约里（`spec/search/cases/ranking.json` 只写了 personaId / since），
 * Android 侧多留一个上界是为了「最近 7 天」这种窗口查询能一次说完，不影响契约。
 */
data class SearchFilter(
    val personaId: String? = null,
    val since: Long? = null,
    val until: Long? = null
) {
    fun accepts(input: RankInput): Boolean {
        if (personaId != null && input.personaId != personaId) return false
        if (since != null && input.createdAt < since) return false
        if (until != null && input.createdAt > until) return false
        return true
    }
}

/**
 * 聊天记录检索的命中判定与排序 —— 两端共用的一份实现。
 *
 * ## 规则（与 `spec/search/README.md` 的 Ranking rules 一一对应）
 *
 * 1. 命中 = 正文**大小写不敏感地包含** query，**或** query 经 [ChineseTerms] 切出的任一词元
 *    在正文里出现（词元本身是字二元组，所以「项目压力」能命中「项目压得我睡不着」）；
 * 2. 排序键：命中的**不同 query 词元个数** desc → [ChineseTerms.relevance] desc →
 *    `createdAt` desc → `id` asc；
 * 3. 过滤（[SearchFilter]）先于排序；空 query 返回空列表（不能因为没有关键词就把整个库倒出来）。
 *
 * ## 为什么词元个数排在相关度前面
 *
 * 「harbin coat」里两个词都命中的消息，通常比只命中一个的更像用户要找的东西；
 * 而 [ChineseTerms.relevance] 是「加权覆盖率 + 余弦」的连续分，用在同分量的比较上更合适。
 * 这条顺序写死在 `spec/search/cases/ranking.json` 里，两端必须一致。
 *
 * ## 词元个数上限
 *
 * 这里只用 [MAX_QUERY_TERMS] 个权重最高的词元，和 `data/local/MessageSearchSql` 里
 * 生成 SQL 粗筛用的词元列表**是同一个上限**。两边必须一致：SQL 粗筛捞回来的候选集，
 * 只有在「排序认为它命中」时才算命中；上限不同就会出现「排序觉得该有、SQL 没捞到」的
 * 静默漏召回，这种 bug 在界面上是看不见的，只能靠两边同源来保证。
 */
object SearchRanking {

    /**
     * 一次检索最多用多少个 query 词元。
     *
     * 12 是「够用且克制」的折中：`ChineseTerms.MAX_QUERY_TERMS` 默认 24 是给记忆倒排索引的
     * （那边每个词元一次索引查询，代价很低）；聊天记录这边每个词元要在 SQL 里多一个
     * `LIKE '%term%'`，24 个全用上会让一次全表扫描的代价翻倍，而第 13 个以后的词元权重
     * 已经很低，属于长尾噪声。
     */
    const val MAX_QUERY_TERMS = 12

    /** 命中 query 的不重复词元个数（0 表示「一个词元都没命中」）。 */
    fun matchedTerms(query: String, content: String): Int {
        if (query.isBlank() || content.isEmpty()) return 0
        val terms = ChineseTerms.queryTerms(query, MAX_QUERY_TERMS)
        if (terms.isEmpty()) return 0
        val haystack = ChineseTerms.normalize(content)
        if (haystack.isEmpty()) return 0
        return terms.count { term -> haystack.contains(term) }
    }

    /**
     * 这条消息算不算命中。
     *
     * 「包含 query」用 [String.indexOf] 的 `ignoreCase`，而不是先 lowercase 再比：
     * 后者在土耳其语 `İ/ı` 这类大小写映射上会改变字符串长度，偏移会错（摘要高亮就跟着错）。
     */
    fun isHit(query: String, content: String): Boolean {
        if (query.isBlank() || content.isEmpty()) return false
        if (content.indexOf(query, ignoreCase = true) >= 0) return true
        return matchedTerms(query, content) > 0
    }

    /** 只过滤不排序（给「按人格/时间过滤后还要按别的顺序展示」的场景留口）。 */
    fun filter(inputs: List<RankInput>, filter: SearchFilter?): List<RankInput> =
        if (filter == null) inputs else inputs.filter { filter.accepts(it) }

    /**
     * 过滤 + 排序。返回的顺序就是界面上的顺序，也是分页切片的顺序。
     *
     * 稳定性：所有键都可能相同，最后一条 `id` 兜底，保证同一个库在任何设备上跑出同一个顺序
     * （`spec/search/cases/ranking.json` 里专门有一条「时间戳都能相同」的用例）。
     */
    fun rank(
        inputs: List<RankInput>,
        query: String,
        filter: SearchFilter? = null
    ): List<RankInput> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val candidates = filter(inputs, filter)
        if (candidates.isEmpty()) return emptyList()

        data class Scored(val input: RankInput, val terms: Int, val relevance: Double)

        return candidates.asSequence()
            // 先算便宜的词元命中，再用它筛掉非命中：relevance 要对正文做一次归一化 + 二元组，
            // 只留给真正的命中（候选池上限 500 条，这个顺序差出一倍的 CPU）。
            .map { input -> input to matchedTerms(q, input.content) }
            .filter { (input, terms) -> terms > 0 || input.content.indexOf(q, ignoreCase = true) >= 0 }
            .map { (input, terms) ->
                Scored(input = input, terms = terms, relevance = ChineseTerms.relevance(q, input.content))
            }
            .sortedWith(
                compareByDescending<Scored> { it.terms }
                    .thenByDescending { it.relevance }
                    .thenByDescending { it.input.createdAt }
                    .thenBy { it.input.id }
            )
            .map { it.input }
            .toList()
    }
}
