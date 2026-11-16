package com.yap.app.data.repo

import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MemoryDao
import com.yap.app.data.local.MemoryEntity
import androidx.sqlite.db.SimpleSQLiteQuery
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageSearchSql
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.PersonaDao
import com.yap.app.domain.ChineseTerms
import com.yap.app.domain.MemoryDrawer
import com.yap.app.domain.MemoryScreening
import com.yap.app.domain.MemoryTags
import com.yap.app.domain.Recall
import com.yap.app.domain.RecallDepth
import com.yap.app.domain.RecallHit
import com.yap.app.domain.RecallKind
import com.yap.app.domain.RecallMemoryInput
import com.yap.app.domain.RecallMemoryState
import com.yap.app.domain.RecallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 回忆：按想要的强度，把「记忆 / 聊天记录 / 他独自上网看到的」翻一遍。
 *
 * 分工和记忆整理保持一致：
 * - 该翻多深、怎么排序、怎么写成中文段落，全在纯领域 [Recall] 里，有单测；
 * - 这里只负责按档位去数据库取数、把实体转成领域视图，再顺手记下「这些记忆又被用到了」。
 *
 * 安全边界：没有记忆开关、找不到人格、查询为空、数据库报错，都返回一句人话（[RecallResult.isEmpty] = true），
 * 不抛异常、不让模型以为工具坏了。
 */
@Singleton
class RecallRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val messageDao: MessageDao,
    private val sessionDao: ChatSessionDao,
    private val personaDao: PersonaDao,
    private val idleBrowsing: IdleBrowsing,
    /** 只为「第一次回忆时顺手补建倒排索引」；召回的数据仍直接走 DAO。 */
    private val memoryRepository: MemoryRepository,
    /** 向量索引：翻记忆时的第二条线索（见 [MemoryVectorIndex]）。 */
    private val memoryVectorIndex: MemoryVectorIndex,
    /** 回忆会被此刻的心情轻轻染一下——只改 1～2 条，且不挡这次回忆。 */
    private val memoryReconsolidator: MemoryReconsolidator
) {

    /** 重构是「顺手做的事」，不能挂在本次召回的生命周期上。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 按 [depth] 的强度回忆一次。
     *
     * @param query 想找的内容；为空时退化成「最近 / 重要优先」，不报错
     * @param now 当前时间（由调用方传入，便于单测与复盘）
     */
    suspend fun recall(
        personaId: String,
        query: String,
        depth: RecallDepth,
        now: Long = System.currentTimeMillis()
    ): RecallResult {
        val term = query.trim().take(MAX_QUERY_CHARS)
        val nothing = RecallResult(
            hits = emptyList(),
            rendered = Recall.emptyAnswer(term, depth),
            isEmpty = true
        )
        if (personaId.isBlank()) return nothing
        return runCatching {
            // 记忆关了就等于「他确实没有这段记忆」，安静地返回一句想不起来。
            // 1.9：记忆内置开启；回忆工具不再看设置。
            val persona = personaDao.getById(personaId) ?: return@runCatching nothing

            val hits = buildList {
                addAll(memoryHits(persona.id, term, depth, now))
                if (depth.includeChatSearch) addAll(chatHits(persona.id, term, depth, now))
                if (depth.includeBrowsing) addAll(browsingHits(persona.id, depth, now))
            }
            val ordered = Recall.rank(hits, term, depth, now).take(depth.maxHits)
            if (ordered.isEmpty()) return@runCatching nothing
            markMemoriesUsed(ordered, now)
            driftAfterRecall(persona.id, ordered, now)
            RecallResult(
                hits = ordered,
                rendered = Recall.render(ordered, depth, term, now),
                isEmpty = false
            )
        }.getOrElse { error ->
            // 别把协程取消吞掉，其余的坏数据都退化成「想不起来」。
            if (error is CancellationException) throw error
            nothing
        }
    }

    /**
     * 想起来之后，让记忆被此刻的心情轻轻染一下。
     *
     * 用的是「不挡这次回忆」的写法：内部自己限流、自己掷骰子、失败什么都不做。
     * 用户看到的仍然是同一次回忆的同一段文字——改动下次才会体现出来。
     */
    private fun driftAfterRecall(personaId: String, hits: List<RecallHit>, now: Long) {
        val ids = hits.asSequence()
            .filter { it.kind == RecallKind.MEMORY && it.refId.isNotBlank() }
            .map { it.refId }
            .distinct()
            .take(MAX_DRIFT_CANDIDATES)
            .toList()
        if (ids.isEmpty()) return
        appScope.launch {
            runCatching { memoryReconsolidator.reconsiderAfterRecall(personaId, ids, now) }
        }
    }

    // ---------------------------------------------------------------- 记忆

    /**
     * 记忆候选：倒排索引（换一种说法也找得到）∪ LIKE 兜底 ∪ 标签命中 ∪ 时间指代
     * ∪ 向量命中 ∪ 全量兜底。
     *
     * 排序与档位筛选全交给纯领域 [Recall]；这里只负责把可能相关的东西都捞上来。
     * 索引没建好、查询为空、向量表空着、任何一步出错都只是「少几条候选」或「少一点加分」，
     * 下面的全量兜底会接住。
     */
    private suspend fun memoryHits(
        personaId: String,
        term: String,
        depth: RecallDepth,
        now: Long
    ): List<RecallHit> {
        val found = LinkedHashMap<String, MemoryEntity>()
        val indexHits = mutableSetOf<String>()
        val terms = ChineseTerms.queryTerms(term)

        if (term.isNotBlank()) {
            // 老用户库里的记忆是 v7 之前写的，没有索引：第一次回忆时顺手补一次（失败也无所谓）。
            runCatching { memoryRepository.ensureTermIndex() }

            // 1) 倒排索引命中
            if (terms.isNotEmpty()) {
                runCatching { memoryDao.memoryIdsByTerms(terms, INDEX_LIMIT) }
                    .getOrDefault(emptyList())
                    .forEach { id ->
                        val memory = runCatching { memoryDao.getById(id) }.getOrNull() ?: return@forEach
                        if (memory.personaId != personaId) return@forEach
                        indexHits += memory.id
                        found[memory.id] = memory
                    }
            }

            // 2) LIKE 兜底：只认字面，换个说法就搜不到，但没有索引时它是主路径。
            val like = likeSafe(term)
            if (like.isNotBlank()) {
                runCatching { memoryDao.searchForPersona(personaId, like, SEARCH_LIMIT) }
                    .getOrDefault(emptyList())
                    .forEach { found[it.id] = it }
            }

            // 3) 标签：「他妹妹那件事」里的「妹妹」可能就是当初打的标签。
            terms.take(TAG_LOOKUP_TERMS).forEach { tag ->
                runCatching { memoryDao.byTag(personaId, tag, TAG_LIMIT) }
                    .getOrDefault(emptyList())
                    .forEach { found[it.id] = it }
            }

            // 4) 时间指代：「去年冬天」这类说法，把有发生时间的记忆也捞上来。
            if (ChineseTerms.hasTimeHint(term, now)) {
                runCatching { memoryDao.withTimeHint(personaId, TIME_LIMIT) }
                    .getOrDefault(emptyList())
                    .forEach { found[it.id] = it }
            }
        }

        // 5) 全量兜底（含归档）：状态筛选交给纯领域按档位做，避免这里再写一套规则。
        runCatching { memoryDao.getAllForPersona(personaId).take(MAX_MEMORIES) }
            .getOrDefault(emptyList())
            .forEach { found[it.id] = it }

        val inputs = found.values.mapNotNull { it.toRecallInput() }
        // 6) 向量：意思相近、字面不重叠时也能翻到（「项目压力」↔「工作上的压力」）。
        //    只在上面已经捞上来的候选里算 —— 全量兜底那一步本来就已经把这个人格的记忆取出来了，
        //    再按人格查一遍向量表只是多读一遍正文。
        val vectorScores = vectorScoresOf(personaId, term, found.keys)
        return Recall.memoryHits(inputs, term, depth, now, indexHits = indexHits)
            .map { hit -> withVectorBonus(hit, vectorScores) }
    }

    /**
     * 向量命中给 [RecallHit.weight] 一个小加成（最多 [VECTOR_BONUS]）。
     *
     * 加在 weight 上是因为 [Recall] 的排序把 weight 当「本身有多重要」直接计入总分：
     * 0.25 与「倒排索引直接命中」那点加成（`Recall.INDEX_BONUS`）同量级，意思是
     * 「除了字面，还有一层像」，但远小于关键词命中（2.0）——所以它只能在**同一档相关度**
     * 的记忆之间把先后拨一拨，不会让一条不相关的旧事挤掉真正对上的那条。
     *
     * 归档记忆不会因此被日常档看到：`Recall.memoryHits` 已经先按档位筛过状态
     * （只有 DEEP 才 includeArchived），这里拿到的是筛完的命中。
     */
    private fun withVectorBonus(hit: RecallHit, vectorScores: Map<String, Double>): RecallHit {
        if (hit.kind != RecallKind.MEMORY) return hit
        val similarity = vectorScores[hit.refId] ?: return hit
        if (!similarity.isFinite() || similarity <= 0.0) return hit
        val bonus = (VECTOR_BONUS * similarity).coerceIn(0.0, VECTOR_BONUS)
        return hit.copy(weight = (hit.weight + bonus).coerceIn(0.0, 1.0))
    }

    /**
     * 向量这一路：记忆 id → 余弦相似度（0..1）。任何一步出错都返回空 map。
     *
     * 空 map 的语义是「这次没有向量信号」：排完全靠关键词、标签、时间与新鲜度，
     * 和加向量之前一模一样。
     */
    private suspend fun vectorScoresOf(
        personaId: String,
        term: String,
        ids: Collection<String>
    ): Map<String, Double> {
        if (term.isBlank() || ids.isEmpty()) return emptyMap()
        return runCatching {
            // 老库升上来时没有向量：第一次翻记忆时补一次（每个进程每个人格一次）。
            memoryVectorIndex.ensureIndex(personaId)
            memoryVectorIndex.searchIn(ids, term, VECTOR_LIMIT)
        }.getOrDefault(emptyList()).associate { it.memoryId to it.similarity }
    }

    private fun MemoryEntity.toRecallInput(): RecallMemoryInput? {
        if (content.isBlank()) return null
        val state = when (status.trim().uppercase()) {
            STATUS_ARCHIVED -> RecallMemoryState.ARCHIVED
            STATUS_DORMANT -> RecallMemoryState.DORMANT
            else -> RecallMemoryState.ACTIVE
        }
        // 抽屉可能来自旧数据：认不出来就按 category 推断，不让一条坏数据炸掉整次回忆。
        val drawerValue = runCatching {
            MemoryDrawer.valueOf(drawer?.trim()?.uppercase().orEmpty())
        }.getOrElse { MemoryScreening.drawerOf(category.name) }
        return RecallMemoryInput(
            id = id,
            content = content,
            drawer = drawerValue,
            importance = importance,
            pinned = pinned,
            lastUsedAt = lastUsedAt,
            createdAt = createdAt,
            state = state,
            strength = strength.coerceIn(0.0, 1.0),
            archivedAt = archivedAt,
            tags = MemoryTags.decode(tags),
            happenedAt = happenedAt
        )
    }

    /** 被回忆到的记忆也算「又用了一次」；没有专门的 DAO 方法，就先按能到的字段更新。 */
    private suspend fun markMemoriesUsed(hits: List<RecallHit>, now: Long) {
        hits.asSequence()
            .filter { it.kind == RecallKind.MEMORY && it.refId.isNotBlank() }
            .map { it.refId }
            .distinct()
            .take(MAX_TOUCHED_MEMORIES)
            .forEach { id ->
                runCatching {
                    val memory = memoryDao.getById(id) ?: return@runCatching
                    val archived = memory.status.trim().uppercase() == STATUS_ARCHIVED
                    // 只动「被用到」的痕迹：不改 updatedAt / content，免得打乱记忆列表的顺序。
                    // 归档的只记「被想起过」，不动 lastUsedAt——否则「很旧才彻底忘掉」的时间线会被一直推后。
                    memoryDao.update(
                        memory.copy(
                            useCount = memory.useCount + 1,
                            lastUsedAt = if (archived) memory.lastUsedAt else now
                        )
                    )
                }
            }
    }

    // ---------------------------------------------------------------- 聊天记录

    private suspend fun chatHits(
        personaId: String,
        term: String,
        depth: RecallDepth,
        now: Long
    ): List<RecallHit> {
        val hits = mutableListOf<RecallHit>()
        // 聊天记录的粗筛和检索页共用一份 SQL（见 MessageSearchSql）：人格过滤直接进 SQL，
        // 召回也从「一条 LIKE」升级成「原文 + 中文词元若干条 LIKE」。
        val query = MessageSearchSql.recentCandidatesQuery(
            text = term,
            personaId = personaId,
            limit = SEARCH_LIMIT
        )
        if (query.sql.isNotEmpty()) {
            val rows = runCatching {
                messageDao.searchRecentCandidates(SimpleSQLiteQuery(query.sql, query.args.toTypedArray()))
            }.getOrDefault(emptyList())
            rows.asSequence()
                .filter { it.personaId == personaId && it.content.isNotBlank() }
                .take(if (depth == RecallDepth.DEEP) DEEP_SEARCH_LIMIT else NORMAL_SEARCH_LIMIT)
                .forEach { row ->
                    hits += Recall.chatHit(row.content, row.sessionTitle, row.createdAt)
                }
        }
        // 翻箱倒柜时再按会话扫一遍最近的消息：关键词搜不到的旧话，常常就躺在最近的上下文里。
        if (depth.scanRecentChats && (term.isBlank() || hits.size < SUPPLEMENT_THRESHOLD)) {
            val sessions = runCatching { sessionDao.getByPersona(personaId) }
                .getOrDefault(emptyList())
                .sortedByDescending { it.lastMessageAt ?: it.createdAt }
                .take(SCAN_SESSIONS)
            for (session in sessions) {
                val messages = runCatching { messageDao.getBySession(session.id) }
                    .getOrDefault(emptyList())
                    .filter {
                        it.role != MessageRole.SYSTEM &&
                            it.status != MessageStatus.ERROR &&
                            it.content.isNotBlank()
                    }
                if (messages.isEmpty()) continue
                val picked = if (term.isBlank()) {
                    messages.takeLast(SUPPLEMENT_PER_SESSION)
                } else {
                    messages.filter { Recall.keywordScore(it.content, term) > 0.0 }
                        .takeLast(SUPPLEMENT_PER_SESSION)
                        .ifEmpty { messages.takeLast(1) }
                }
                picked.forEach { message ->
                    hits += Recall.chatHit(
                        text = message.content,
                        sessionTitle = session.title,
                        at = message.createdAt,
                        role = message.role.name
                    )
                }
            }
        }
        return hits.distinctBy { it.text to it.at }
    }

    // ---------------------------------------------------------------- 他看到的

    private suspend fun browsingHits(personaId: String, depth: RecallDepth, now: Long): List<RecallHit> {
        val digest = runCatching { idleBrowsing.latestDigest(personaId) }.getOrNull()
        if (digest.isNullOrBlank()) return emptyList()
        // 摘要只在进程内缓存里、没有时间戳，只能当作「最近看到的」，日期按 now 算。
        val text = digest.trim().take(if (depth == RecallDepth.DEEP) DEEP_DIGEST_CHARS else NORMAL_DIGEST_CHARS)
        return listOf(Recall.browsingHit(text, now))
    }

    // ---------------------------------------------------------------- 辅助

    /** LIKE 查询里 % 和 _ 是通配符，模型给的关键词里带上它们会变成全表扫描，先清掉。 */
    private fun likeSafe(term: String): String =
        term.replace("%", "").replace("_", "").trim().take(MAX_QUERY_CHARS)

    private companion object {
        const val MAX_QUERY_CHARS = 60
        const val MAX_MEMORIES = 200
        const val SEARCH_LIMIT = 80

        /** 倒排索引一次最多带回来多少条候选。 */
        const val INDEX_LIMIT = 120

        /** 一次回忆最多让几条记忆被心情染色：多了就不像「想起来」，像批量改写。 */
        const val MAX_DRIFT_CANDIDATES = 3

        /** 标签一次最多带回来多少条。 */
        const val TAG_LIMIT = 30

        /** 拿几个查询词元去查标签。 */
        const val TAG_LOOKUP_TERMS = 3

        /** 时间指代一次最多带回来多少条有发生时间的记忆。 */
        const val TIME_LIMIT = 40

        /** 向量一次取多少条候选：与「进上下文」那一路同一个量级。 */
        const val VECTOR_LIMIT = 16

        /** 向量命中的 weight 加成上限（≈0.2–0.3）：和倒排命中那点加成同量级，只微调先后。 */
        const val VECTOR_BONUS = 0.25
        const val MAX_TOUCHED_MEMORIES = 6
        const val NORMAL_SEARCH_LIMIT = 20
        const val DEEP_SEARCH_LIMIT = 40
        const val SCAN_SESSIONS = 12
        const val SUPPLEMENT_THRESHOLD = 3
        const val SUPPLEMENT_PER_SESSION = 2
        const val NORMAL_DIGEST_CHARS = 500
        const val DEEP_DIGEST_CHARS = 1200
        const val STATUS_DORMANT = "DORMANT"
        const val STATUS_ARCHIVED = "ARCHIVED"
    }

}
