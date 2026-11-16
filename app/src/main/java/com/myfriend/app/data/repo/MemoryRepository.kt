package com.myfriend.app.data.repo

import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MemoryCategory
import com.myfriend.app.data.local.MemoryDao
import com.myfriend.app.data.local.MemoryEntity
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MemoryEchoDao
import com.myfriend.app.data.local.MemoryEchoEntity
import com.myfriend.app.data.local.MemoryTermDao
import com.myfriend.app.data.local.MemoryTermEntity
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.domain.ChineseTerms
import com.myfriend.app.domain.DomainErrorCode
import com.myfriend.app.domain.ImportanceScoring
import com.myfriend.app.domain.MemoryDecision
import com.myfriend.app.domain.MemoryDrawer
import com.myfriend.app.domain.MessageWindows
import com.myfriend.app.domain.MemoryLifecycle
import com.myfriend.app.domain.MemoryLifecycleState
import com.myfriend.app.domain.MemoryRow
import com.myfriend.app.domain.MemoryScreening
import com.myfriend.app.domain.MemoryTags
import com.myfriend.app.domain.MemoryTier
import com.myfriend.app.domain.UserErrorException
import com.myfriend.app.domain.MemoryCandidate as DomainMemoryCandidate
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.InferenceParams
import com.myfriend.app.network.userError
import com.myfriend.app.network.WireMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次「从聊天里记东西」的结果。
 *
 * 以前这里带一句现成的中文（"记忆已更新（+2）"…），现在只给数量与 [outcome]：
 * 界面按当前语言映射资源，英文界面不再看到中文。
 */
data class MemoryExtractionResult(
    val added: Int,
    val reinforced: Int = 0,
    val echoed: Int = 0,
    val rejected: Int = 0,
    /** 特殊结果（对话太少 / 还没到间隔）由调用点显式指定；null 表示按数量推断。 */
    val outcomeOverride: MemoryExtractionOutcome? = null,
    /** 「还没到整理间隔（每 N 条）」里的 N。 */
    val every: Int? = null
) {
    val outcome: MemoryExtractionOutcome
        get() = outcomeOverride ?: when {
            added > 0 -> MemoryExtractionOutcome.ADDED
            reinforced > 0 -> MemoryExtractionOutcome.REINFORCED
            echoed > 0 -> MemoryExtractionOutcome.ECHOED
            else -> MemoryExtractionOutcome.NONE
        }
}

/** 抽取结果的几种「说法」，由 UI 翻成句子。 */
enum class MemoryExtractionOutcome {
    ADDED,
    REINFORCED,
    ECHOED,
    NONE,
    NOT_ENOUGH_CHAT,
    INTERVAL_NOT_REACHED
}

/**
 * 一次召回的候选集：候选记忆 + 「这条候选有多像这句话」的向量分。
 *
 * 向量分只用来在**同一批候选里**微调先后（见 [MemoryScreening]），它不决定谁能进候选。
 * 默认值让「没有向量可用」这条分支不需要任何特殊判断。
 */
private data class RecallCandidates(
    val memories: List<MemoryEntity>,
    /** 记忆 id → 余弦相似度（0..1）；空 map = 这次没有向量信号。 */
    val vectorScores: Map<String, Double> = emptyMap()
)

@Singleton
class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry,
    private val settingsRepository: SettingsRepository,
    private val memoryEchoDao: MemoryEchoDao,
    private val memoryTermDao: MemoryTermDao,
    /** 向量索引：与倒排索引互补的第二条线索（见 [MemoryVectorIndex]）。 */
    private val memoryVectorIndex: MemoryVectorIndex,
    /** M5：服务端模式的记忆写路径（失败不回退静默本地写）。 */
    private val serverWrites: com.myfriend.app.data.remote.ServerWriteGateway,
    private val json: Json
) {
    private val extractionMutex = Mutex()

    /** 索引补建的互斥锁：App 启动和第一次召回可能同时触发，只让一个人干活。 */
    private val indexMutex = Mutex()

    /** 这次进程里是否已经确认过索引完整；确认过就不再每次召回都 count() 一遍。 */
    @Volatile
    private var termIndexEnsured = false

    fun observe(personaId: String): Flow<List<MemoryEntity>> = memoryDao.observeByPersona(personaId)
    suspend fun count(personaId: String): Int = memoryDao.countForPersona(personaId)

    /**
     * 按「这一轮聊的是什么」挑记忆，整条链路是三步：
     *
     * 1. **候选**：倒排索引命中（换一种说法也能找到）∪ 向量 top-16（意思相近、字面不重叠也能撞上）
     *    ∪ 关键词 LIKE 兜底 ∪ 标签命中 ∪ 时间指代（「去年冬天」）∪ `getForPrompt` 的 pinned/重要度兜底；
     * 2. **重排**：在内存里算词元重合度、向量相似度、标签、发生时间、pinned、重要度、新鲜度、抽屉
     *    （[MemoryScreening]）；
     * 3. **裁剪**：按字数预算取前 N 条，避免把上下文塞满。
     *
     * 只从 [MemoryDao.getForPrompt] 取基线：**归档的记忆永远不进上下文**（它们还在库里，
     * 只可能被「回忆」工具翻出来）。挑中的记忆顺手记一次「被想起来」，
     * 这样它不会被每日衰减无声无息地沉掉 —— 有人一直提起的事本来就该更牢。
     *
     * 四层保险：索引没建好 → 靠 LIKE 与兜底；向量表空着/编码失败 → 退回纯词元重排；
     * query 为空 → 直接走 pinned/重要度；数据库出任何错 → 退回「取全量再排序」。行为不会比以前差。
     */
    suspend fun getRelevant(personaId: String, query: String = "", limit: Int = 200): List<MemoryEntity> {
        // 1.9：记忆是内置行为，没有「长期记忆开关」可关；这里不再读设置。
        val now = System.currentTimeMillis()
        val candidates = runCatching { recallCandidates(personaId, query, limit) }
            .getOrDefault(RecallCandidates(emptyList()))
        val memories = candidates.memories
        if (memories.isEmpty()) return emptyList()
        val byId = memories.associateBy { it.id }
        val rows = memories.map { it.toScreenRow() }
        val picked = MemoryScreening.selectWithinBudget(
            memories = rows,
            query = query,
            now = now,
            maxChars = MAX_MEMORY_CONTEXT_CHARS,
            vectorScores = candidates.vectorScores
        )
        val pickedEntities = picked.mapNotNull { byId[it.id] }
        touchRecalled(pickedEntities, now)
        return pickedEntities
    }

    /**
     * 召回候选集：只负责「把可能相关的东西捞上来」，不排序（排序在 [MemoryScreening] 里做）。
     *
     * 候选多几条没关系（一次最多几百条，都在内存里），漏掉才是问题；
     * 但每条候选都必须过一遍 persona 与归档检查，否则会把别的抽屉或早该沉下去的记忆塞进上下文。
     */
    private suspend fun recallCandidates(
        personaId: String,
        query: String,
        limit: Int
    ): RecallCandidates {
        val now = System.currentTimeMillis()
        val pool = memoryDao.getForPrompt(personaId, limit)
        if (query.isBlank()) return RecallCandidates(pool)

        val candidates = LinkedHashMap<String, MemoryEntity>(pool.size + CANDIDATE_EXTRA)
        pool.forEach { candidates[it.id] = it }

        // 索引缺了才补（老用户库里的记忆是 v7 之前写的，没有索引）。
        ensureTermIndex()
        val terms = ChineseTerms.queryTerms(query)

        // 1) 倒排索引命中
        if (terms.isNotEmpty()) {
            runCatching { memoryDao.memoryIdsByTerms(terms, INDEX_CANDIDATES) }
                .getOrDefault(emptyList())
                .forEach { id ->
                    if (candidates.containsKey(id)) return@forEach
                    val memory = runCatching { memoryDao.getById(id) }.getOrNull() ?: return@forEach
                    if (memory.personaId != personaId || memory.isArchived()) return@forEach
                    candidates[id] = memory
                }
        }

        // 2) 关键词 LIKE 兜底：索引坏掉/没建好时也照样能按字面找到
        val like = likeSafe(query)
        if (like.isNotBlank()) {
            runCatching { memoryDao.searchForPersona(personaId, like, LIKE_CANDIDATES) }
                .getOrDefault(emptyList())
                .forEach { if (!it.isArchived()) candidates[it.id] = it }
        }

        // 3) 标签兜底：query 里的短词可能就是当初打的标签（「妹妹」「失眠」）
        terms.take(TAG_LOOKUP_TERMS).forEach { term ->
            runCatching { memoryDao.byTag(personaId, term, TAG_CANDIDATES) }
                .getOrDefault(emptyList())
                .forEach { if (!it.isArchived()) candidates[it.id] = it }
        }

        // 4) 时间指代：「去年冬天」这类说法，把有发生时间的记忆也捞上来
        if (ChineseTerms.hasTimeHint(query, now)) {
            runCatching { memoryDao.withTimeHint(personaId, TIME_CANDIDATES) }
                .getOrDefault(emptyList())
                .forEach { if (!it.isArchived()) candidates[it.id] = it }
        }

        // 5) 向量：意思相近但字面不重叠时也能翻到（「项目压力」↔「工作上的压力」）。
        //    只在**上面已经捞上来的候选并集**里取 top-16，而不是再扫一遍全库正文：
        //    并集本身已经覆盖 pinned/重要/最近 limit 条 + 倒排/LIKE/标签/时间命中的记忆，
        //    为了第 200 名之后的几条老记忆再读一遍所有正文，在一周就能聊上千轮的场景里不划算。
        //    真需要全库扫时走 [MemoryVectorIndex.search]，那条路读正文的代价更高。
        val vectorScores = vectorScoresOf(personaId, query, candidates.keys)
        return RecallCandidates(candidates.values.toList(), vectorScores)
    }

    /**
     * 向量这一路：记忆 id → 余弦相似度。任何一步出错都返回空 map。
     *
     * 空 map 的语义是「这次没有向量信号」，[MemoryScreening] 会原样退化成纯词元重排——
     * 向量只是他「换个说法也记得」的那点运气，不能成为召回的依赖。
     */
    private suspend fun vectorScoresOf(
        personaId: String,
        query: String,
        ids: Collection<String>
    ): Map<String, Double> {
        if (query.isBlank() || ids.isEmpty()) return emptyMap()
        return runCatching {
            // 老库升上来时没有向量：第一次用到时补一次（每个进程每个人格只补一次）。
            memoryVectorIndex.ensureIndex(personaId)
            memoryVectorIndex.searchIn(ids, query, VECTOR_TOP_K)
        }.getOrDefault(emptyList())
            .associate { it.memoryId to it.similarity }
    }

    /**
     * 惰性补建倒排索引：`memory_terms` 全空时，为所有记忆补一次（App 启动或第一次召回时触发）。
     *
     * 只在「确实没有索引」时做：每次召回都全量重建会把启动拖慢几十毫秒到几百毫秒，
     * 而补完一次就够了（之后写入/更新/删除都会同步维护）。失败不抛：没有索引也能靠 LIKE 兜底。
     */
    /** 顺手回收孤儿词元：记忆被整批删掉时不会自动带走索引。 */
    suspend fun pruneTermIndex(): Int = runCatching { memoryTermDao.deleteOrphans() }.getOrDefault(0)

    suspend fun ensureTermIndex() {
        if (termIndexEnsured) return
        indexMutex.withLock {
            if (termIndexEnsured) return
            runCatching {
                if (memoryTermDao.count() == 0) {
                    personaDao.getAll().forEach { persona ->
                        memoryDao.getForPersona(persona.id, INDEX_BACKFILL_LIMIT).forEach { memory ->
                            rebuildTermIndex(memory.id, memory.content)
                        }
                    }
                }
            }
            termIndexEnsured = true
        }
    }

    /**
     * 向量索引补建：给启动流程（[com.myfriend.app.MyFriendApplication]）留的入口。
     *
     * 建议在 `onCreate` 的 IO 协程里、紧挨着 `ensureTermIndex()` 调一次：
     * `runCatching { memoryRepository.ensureVectorIndex() }`。
     * **不调也不会错** —— 第一次召回/回忆时会自己补，只是那一次会稍微慢一点。
     * 每个进程每个人格只真正干活一次，之后的调用都是直接返回。
     */
    suspend fun ensureVectorIndex(): Int =
        runCatching { memoryVectorIndex.ensureIndex() }.getOrDefault(0)

    /**
     * 按库里当前的正文重建某条记忆的倒排索引。
     *
     * 给「记忆会随回忆漂移」那条链路用：漂移是直接改库里的 content（不走本类），
     * 改完调一下这里，新措辞才搜得到、旧措辞才不会继续命中。
     * 记忆不存在就安静返回（可能刚被忘掉）。
     */
    suspend fun reindexTerms(memoryId: String) {
        val memory = runCatching { memoryDao.getById(memoryId) }.getOrNull() ?: return
        rebuildTermIndex(memory.id, memory.content)
        // 正文变了，向量也要跟着变：漂移（MemoryReconsolidator）改完正文就是调这里，
        // 不重算的话「用新措辞想不起来旧事」，而且旧向量还会按旧意思把它翻出来。
        memoryVectorIndex.upsert(memory.id, memory.content)
    }

    /** 重建一条记忆的倒排索引：正文改过就必须先删后写，否则会留下查得到的旧词元。 */
    private suspend fun rebuildTermIndex(memoryId: String, content: String) {
        runCatching { memoryTermDao.deleteByMemory(memoryId) }
        val terms = ChineseTerms.terms(content)
            .map { MemoryTermEntity(memoryId = memoryId, term = it.term, weight = it.weight) }
        if (terms.isNotEmpty()) runCatching { memoryTermDao.insertAll(terms) }
    }

    /** LIKE 里的 % 和 _ 是通配符，模型给的关键词带上来会变成全表扫，先清掉。 */
    private fun likeSafe(term: String): String =
        term.replace("%", "").replace("_", "").trim().take(40)

    private fun MemoryEntity.isArchived(): Boolean =
        status.trim().uppercase() == STATUS_ARCHIVED

    /**
     * 召回即「想起来一次」：刷新 lastUsedAt / useCount，并给一点分量回血（见 [MemoryLifecycle]）。
     *
     * 每 [RECALL_TOUCH_INTERVAL_MILLIS] 才写一次库：上下文几乎每轮都要拼，
     * 不能因为聊天频繁就把同一批记忆写成热行。
     */
    private suspend fun touchRecalled(memories: List<MemoryEntity>, now: Long) {
        memories.forEach { memory ->
            if (now - memory.lastUsedAt < RECALL_TOUCH_INTERVAL_MILLIS) return@forEach
            runCatching {
                memoryDao.update(
                    memory.copy(
                        lastUsedAt = now,
                        useCount = memory.useCount + 1,
                        strength = MemoryLifecycle.recallStrength(memory.toLifecycleState(), now),
                        updatedAt = now
                    )
                )
            }
        }
    }

    /** Room 实体 → 生命周期纯视图（domain 层不依赖 Room，所以在这里转一次）。 */
    private fun MemoryEntity.toLifecycleState(): MemoryLifecycleState = MemoryLifecycleState(
        id = id,
        tier = MemoryTier.parse(status),
        pinned = pinned,
        importance = importance,
        useCount = useCount,
        lastUsedAt = lastUsedAt,
        createdAt = createdAt,
        strength = strength,
        lastConsolidatedAt = consolidatedAt ?: 0L,
        // 三个抽屉的衰减速度不一样：关于对方的事实最抗时间（见 MemoryLifecycle 里的常量注释）。
        drawer = drawerValue()
    )

    /** 抽屉可能来自旧数据：认不出来就按 category 推断，不让一条脏字符串炸掉整次召回。 */
    private fun MemoryEntity.drawerValue(): MemoryDrawer =
        runCatching { MemoryDrawer.valueOf(drawer?.trim()?.uppercase().orEmpty()) }
            .getOrElse { MemoryScreening.drawerOf(category.name) }

    private fun MemoryEntity.toScreenRow(): MemoryRow = MemoryRow(
        id = id,
        content = content,
        drawer = drawerValue(),
        importance = importance,
        pinned = pinned,
        hits = useCount.coerceAtLeast(1),
        lastHitAt = lastUsedAt,
        createdAt = createdAt,
        tags = MemoryTags.decode(tags),
        happenedAt = happenedAt
    )

    /**
     * 带抽屉与来源的写入：AI 用 remember 工具、以及筛查后落库都走这里，
     * 这样每条记忆都知道自己属于哪个抽屉、是从哪句话来的。
     */
    suspend fun addDetailed(
        personaId: String,
        content: String,
        category: MemoryCategory,
        importance: Int,
        pinned: Boolean = false,
        drawer: String? = null,
        sourceQuote: String? = null,
        sourceSessionId: String? = null,
        tags: List<String> = emptyList(),
        happenedAt: Long? = null
    ): Result<String> = runCatching {
        val text = content.trim()
        if (text.isBlank()) userError(DomainErrorCode.MEMORY_EMPTY)
        if (text.length > 2000) userError(DomainErrorCode.MEMORY_TOO_LONG)
        val now = System.currentTimeMillis()
        var memory = MemoryEntity(
            id = UUID.randomUUID().toString(),
            personaId = personaId,
            content = text,
            category = category,
            importance = importance.coerceIn(1, 5),
            pinned = pinned,
            sourceSessionId = sourceSessionId,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = 0,
            useCount = 1,
            drawer = drawer,
            sourceQuote = sourceQuote?.take(300),
            tags = MemoryTags.encode(tags),
            happenedAt = happenedAt?.takeIf { it > 0L }
        )
        // M5：服务端模式先写服务端，成功后才写本机缓存；服务端分配的 id 就是缓存行的 id，
        // 之后编辑/删除直接用同一个 id，不需要额外的映射表。
        if (serverWrites.isEnabled()) {
            val server = serverWrites.saveMemory(
                personaId,
                com.myfriend.app.data.remote.ServerMemoryWrite.payload(memory, creating = true)
            ).getOrThrow()
            memory = com.myfriend.app.data.remote.ServerMemoryWrite.localEntity(server, memory)
        }
        memoryDao.insert(memory)
        // 新记忆马上进倒排索引与向量索引：不建的话，它要等到下次全量补建才查得到。
        rebuildTermIndex(memory.id, text)
        memoryVectorIndex.upsert(memory.id, text)
        memory.id
    }

    /**
     * 把这次抽出来的结构信息（标签、发生时间）补贴到一条已有记忆上：只补空，不覆盖已有的。
     *
     * 为什么「只补空」：同一条记忆可能被不同轮对话反复加固，每次都覆盖一次的话，
     * 后面一次模糊的「最近」会把前面确切的「去年冬天」冲掉——他对旧事的时间感就是这样被弄乱的。
     */
    private suspend fun mergeStructure(memoryId: String, tags: List<String>, happenedAt: Long?) {
        if (tags.isEmpty() && happenedAt == null) return
        val memory = memoryDao.getById(memoryId) ?: return
        val mergedTags = MemoryTags.sanitize(MemoryTags.decode(memory.tags) + tags)
        val mergedHappened = memory.happenedAt ?: happenedAt?.takeIf { it > 0L }
        memoryDao.update(
            memory.copy(
                tags = MemoryTags.encode(mergedTags) ?: memory.tags,
                happenedAt = mergedHappened
            )
        )
    }

    /** 同一件事又被提到：加固而不是新增。 */
    suspend fun reinforce(memoryId: String, appendedText: String? = null, importance: Int? = null): Result<Unit> = runCatching {
        val memory = memoryDao.getById(memoryId) ?: userError(DomainErrorCode.MEMORY_NOT_FOUND)
        val now = System.currentTimeMillis()
        val merged = if (appendedText.isNullOrBlank()) memory.content else {
            (memory.content.trimEnd() + "；" + appendedText.trim()).take(2000)
        }
        var updated = memory.copy(
            content = merged,
            importance = importance?.coerceIn(1, 5) ?: memory.importance,
            useCount = memory.useCount + 1,
            lastUsedAt = now,
            updatedAt = now
        )
        // M5：加固也是一次「编辑」。服务端失败就整体失败，不能只在本机变长、服务端还是旧内容。
        if (serverWrites.isEnabled()) {
            val server = serverWrites.saveMemory(
                memory.personaId,
                com.myfriend.app.data.remote.ServerMemoryWrite.payload(updated, creating = false)
            ).getOrThrow()
            updated = com.myfriend.app.data.remote.ServerMemoryWrite.localEntity(server, updated)
        }
        memoryDao.update(updated)
        // 正文变长了：索引跟着重建，否则新补上的那句话永远搜不到。
        rebuildTermIndex(memoryId, merged)
        memoryVectorIndex.upsert(memoryId, merged)
    }

    suspend fun add(
        personaId: String,
        content: String,
        category: MemoryCategory,
        importance: Int,
        pinned: Boolean = false
    ): Result<Unit> = runCatching {
        val text = content.trim()
        if (text.isBlank()) userError(DomainErrorCode.MEMORY_EMPTY)
        if (text.length > 2000) userError(DomainErrorCode.MEMORY_TOO_LONG)
        val now = System.currentTimeMillis()
        var memory = MemoryEntity(
            id = UUID.randomUUID().toString(),
            personaId = personaId,
            content = text,
            category = category,
            importance = importance.coerceIn(1, 5),
            pinned = pinned,
            sourceSessionId = null,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = 0,
            useCount = 0
        )
        if (serverWrites.isEnabled()) {
            val server = serverWrites.saveMemory(
                personaId,
                com.myfriend.app.data.remote.ServerMemoryWrite.payload(memory, creating = true)
            ).getOrThrow()
            memory = com.myfriend.app.data.remote.ServerMemoryWrite.localEntity(server, memory)
        }
        memoryDao.insert(memory)
        rebuildTermIndex(memory.id, text)
        memoryVectorIndex.upsert(memory.id, text)
    }

    suspend fun update(memory: MemoryEntity, content: String, category: MemoryCategory, importance: Int): Result<Unit> = runCatching {
        val text = content.trim()
        if (text.isBlank()) userError(DomainErrorCode.MEMORY_EMPTY)
        if (text.length > 2000) userError(DomainErrorCode.MEMORY_TOO_LONG)
        val now = System.currentTimeMillis()
        var updated = memory.copy(
            content = text,
            category = category,
            importance = importance.coerceIn(1, 5),
            updatedAt = now
        )
        // M5：服务端成功后才写本机；服务端返回的 content/importance 以它为准（可能与本地夹取不同）。
        if (serverWrites.isEnabled()) {
            val server = serverWrites.saveMemory(
                memory.personaId,
                com.myfriend.app.data.remote.ServerMemoryWrite.payload(updated, creating = false)
            ).getOrThrow()
            updated = com.myfriend.app.data.remote.ServerMemoryWrite.localEntity(server, updated)
        }
        memoryDao.update(updated)
        rebuildTermIndex(updated.id, text)
        memoryVectorIndex.upsert(updated.id, text)
    }

    suspend fun delete(memory: MemoryEntity) {
        // M5：服务端是权威源；服务端删不掉时本机先留着，避免「这台看不见、那边还在」。
        // 404 由 ServerWriteGateway 当成已删除处理。
        if (serverWrites.isEnabled()) {
            serverWrites.deleteMemory(memory.id).getOrThrow()
        }
        memoryDao.deleteById(memory.id)
        // 记忆没了，索引也必须跟着没：留着会指向一条查不到的 id。
        // 顺序是先删记忆再删索引：万一删索引失败，孤儿词元只是查不到东西；反过来会留下「查得到但已经不记得」的坏状态。
        runCatching { memoryTermDao.deleteByMemory(memory.id) }
        memoryVectorIndex.remove(memory.id)
    }

    /** 合并重复记忆时用：按 id 删掉那条被并入的旧记忆。 */
    suspend fun deleteById(memoryId: String) {
        val memory = memoryDao.getById(memoryId) ?: return
        delete(memory)
    }

    suspend fun setPinned(memory: MemoryEntity, pinned: Boolean) {
        memoryDao.setPinned(memory.id, pinned, System.currentTimeMillis())
    }

    fun formatForPrompt(memories: List<MemoryEntity>): String {
        if (memories.isEmpty()) return ""
        // 按抽屉讲给模型听，比按数据库的 category 更接近「他记得的事」。
        val grouped = memories.groupBy { entity ->
            runCatching { MemoryDrawer.valueOf(entity.drawer?.trim()?.uppercase().orEmpty()) }
                .getOrElse { MemoryScreening.drawerOf(entity.category.name) }
        }
        val labels = mapOf(
            MemoryDrawer.USER to "关于对方",
            MemoryDrawer.SELF to "你自己是什么样的人",
            MemoryDrawer.EVENT to "你们之间的经历与约定"
        )
        return buildString {
            append("\n\n---\n## 你记得的事\n")
            append("以下是你与对方长期相处中自然记住的信息。只在话题相关时自然地使用，不要逐条复述，也不要声称自己记错。\n")
            memoryOrder.forEach { drawer ->
                val items = grouped[drawer].orEmpty()
                if (items.isEmpty()) return@forEach
                append("\n**${labels[drawer]}**\n")
                items.sortedByDescending { it.importance }.forEach { memory ->
                    append("- ${memory.content}")
                    if (memory.pinned) append("（对你很重要）")
                    append("\n")
                }
            }
        }
    }

    private val memoryOrder = listOf(MemoryDrawer.USER, MemoryDrawer.SELF, MemoryDrawer.EVENT)

    suspend fun maybeExtractMemories(sessionId: String, force: Boolean = false): Result<MemoryExtractionResult> {
        return extractionMutex.withLock {
            runCatching {
                val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
                val configId = session.apiConfigId ?: userError(DomainErrorCode.API_CONFIG_NOT_BOUND)
                val resolved = apiConfigRepository.resolve(configId)
                    ?: userError(DomainErrorCode.API_CONFIG_NOT_FOUND)
                val persona = personaDao.getById(session.personaId)
                    ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
                val personaName = persona.name.ifBlank { "他" }
                // 总条数只用来判断「距离上次抽取够不够 N 条」，单独 COUNT 即可；
                // 正文只取最近 40 行（下面实际只用最后 20 句），不把整段历史读进内存。
                val currentCount = messageDao.countInSession(sessionId)
                val recentRows = messageDao.recentBySession(sessionId, MessageWindows.EXTRACTION_ROWS)
                val conversational = MessageWindows.lastN(recentRows, MessageWindows.EXTRACTION_ROWS) {
                    it.role != MessageRole.SYSTEM && it.content.isNotBlank()
                }
                if (conversational.size < 4) {
                    return@runCatching MemoryExtractionResult(
                        added = 0,
                        outcomeOverride = MemoryExtractionOutcome.NOT_ENOUGH_CHAT
                    )
                }
                // 抽记忆的频率由人格决定（会话可覆盖，3–12 条）。1.9 把它从设置页搬到了联系人编辑页：
                // 「记性」是这个人什么样的一部分，不该是一个全局开关。
                val every = (session.extractionEveryOverride ?: persona.extractionEvery).coerceIn(3, 12)
                if (!force && currentCount - session.lastMemoryExtractedCount < every) {
                    return@runCatching MemoryExtractionResult(
                        added = 0,
                        outcomeOverride = MemoryExtractionOutcome.INTERVAL_NOT_REACHED,
                        every = every
                    )
                }
                val recent = conversational.takeLast(20)
                val existing = memoryDao.getForPersona(persona.id, 80)
                val transcript = recent.joinToString("\n") {
                    "${if (it.role == MessageRole.USER) "对方" else persona.name}：${it.content.take(1800)}"
                }
                val existingText = existing.joinToString("\n") { "- ${it.content}" }.ifBlank { "（暂无）" }
                val system = """
                    你是一个长期记忆整理器，为「$personaName」整理与对方的长期记忆。
                    只提取以后相处真正用得上的信息：稳定的身份与生活背景、偏好与忌讳、重要经历、明确的约定，以及他自己在对话里显露的性格与说话方式。
                    不提取一次性情绪、寒暄、以及已有记忆里已经写下的内容。
                    每条都要指出它是被哪句话触发的（sourceQuote，原话片段，30 字以内）。
                    没有值得长期保存的信息时输出空数组 []。
                    只输出 JSON，不要 Markdown 代码块，不要解释。格式：
                    [{"content":"用第三人称写的一句完整记忆","category":"FACT|PREFERENCE|EVENT|RELATIONSHIP|OTHER","drawer":"SELF|USER|EVENT","importance":1到5的整数,"confidence":0到1的小数,"sourceQuote":"触发它的原话","tags":["短标签"],"happenedAt":"这件事发生的时间"}]
                    drawer 含义：USER 关于对方的事，SELF 关于他自己是什么样的人，EVENT 两人之间的经历与约定。
                    tags 是以后能被一句话勾出来的短词：人（妹妹、阿杰）、事（搬家、面试）、物（猫、吉他）、情绪（失眠、焦虑）。
                    最多 5 个，每个不超过 6 个字；宁可不给，也别拿整句话当标签。
                    happenedAt 是**这件事发生的时间**，不是现在：对方说「昨天面试砸了」，就填「昨天」；
                    说「去年冬天去过一次哈尔滨」，就填「去年冬天」。原话里没有时间线索就填空字符串，不要猜。
                    最多 5 条。每条不超过 300 字。importance 表示未来重要性；拿不准就把 confidence 压低，不要硬凑。
                """.trimIndent()
                val user = """
                    已有长期记忆：
                    $existingText

                    最近对话：
                    $transcript
                """.trimIndent()
                val raw = registry.adapterFor(resolved.config.provider).complete(
                    config = resolved.config,
                    apiKey = resolved.apiKey,
                    model = session.model.ifBlank { resolved.config.model },
                    messages = listOf(
                        WireMessage("system", system),
                        WireMessage("user", user)
                    ),
                    params = InferenceParams(temperature = 0.2, maxOutputTokens = 700),
                    kind = com.myfriend.app.domain.UsageKind.EXTRACTION,
                    personaId = persona.id,
                    sessionId = sessionId
                )
                val now = System.currentTimeMillis()
                // 抽取里的「昨天/上周三/去年冬天」要用这一轮的 now 换算成毫秒。
                val candidates = parseCandidates(raw, now)
                val existingRows = existing.map { it.toScreenRow() }
                val echoes = memoryEchoDao.byPersona(persona.id).map { it.toCandidate() }
                var added = 0
                var reinforced = 0
                var echoed = 0
                var rejected = 0
                val toInsert = mutableListOf<MemoryEntity>()
                val echoWrites = mutableListOf<MemoryEchoEntity>()
                // 同一个抽屉里刚写下的回声也要参与本次判定，否则一轮抽出两条相近的会各存一份。
                val workingEchoes = echoes.toMutableList()
                candidates.take(5).forEachIndexed { index, candidate ->
                    val domainCandidate = candidate.toCandidate()
                    when (val decision = MemoryScreening.decide(domainCandidate, existingRows, workingEchoes, now)) {
                        is MemoryDecision.Reject -> rejected++
                        is MemoryDecision.Echo -> {
                            echoed++
                            echoWrites += domainCandidate.toEchoEntity(persona.id, now + index)
                            workingEchoes += domainCandidate
                        }
                        is MemoryDecision.Promote -> {
                            val promoted = decision.candidate
                            toInsert += promoted.toEntity(persona.id, sessionId, now + index)
                            // 这条已经下沉成正式记忆，对应的回声可以清掉了。
                            echoWrites.removeAll { echo ->
                                MemoryScreening.similarity(echo.content, promoted.content) >= 0.6
                            }
                            added++
                        }
                        is MemoryDecision.Reinforce -> {
                            reinforce(decision.id, decision.appendedText, null)
                            // 又说到同一件事：这次给的标签与发生时间补贴到已有记忆上（只补不覆盖）。
                            runCatching {
                                mergeStructure(decision.id, domainCandidate.tags, domainCandidate.happenedAt)
                            }
                            reinforced++
                        }
                    }
                }
                if (echoWrites.isNotEmpty()) memoryEchoDao.insertAll(echoWrites)
                if (toInsert.isNotEmpty()) {
                    memoryDao.insertAll(toInsert)
                }
                sessionDao.setMemoryExtractedCount(sessionId, currentCount, now)
                MemoryExtractionResult(
                    added = added,
                    reinforced = reinforced,
                    echoed = echoed,
                    rejected = rejected
                )
            }
        }
    }

    suspend fun extractFromLatestSession(personaId: String): Result<MemoryExtractionResult> {
        val sessions = sessionDao.getByPersona(personaId)
        val target = sessions.maxByOrNull { it.lastMessageAt ?: it.createdAt }
            ?: return Result.failure(UserErrorException(DomainErrorCode.NO_CONVERSATION_YET))
        return maybeExtractMemories(target.id, force = true)
    }

    private fun parseCandidates(raw: String, now: Long): List<ExtractedMemory> {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = runCatching {
            json.parseToJsonElement(cleaned.substring(start, end + 1)) as? JsonArray
        }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (content.isBlank()) return@mapNotNull null
            val category = runCatching {
                MemoryCategory.valueOf(
                    obj["category"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() ?: "OTHER"
                )
            }.getOrDefault(MemoryCategory.OTHER)
            val importance = obj["importance"]?.jsonPrimitive?.intOrNull ?: 3
            val drawerRaw = obj["drawer"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase().orEmpty()
            val drawer = MemoryDrawer.entries.firstOrNull { it.name == drawerRaw }
                ?: MemoryScreening.drawerOf(category.name)
            val confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                ?: obj["confidence"]?.jsonPrimitive?.intOrNull?.toFloat()
                ?: 0.7f
            val sourceQuote = obj["sourceQuote"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            // tags 可能是 JSON 数组也可能是逗号串：都交给 ImportanceScoring 的宽松解析。
            val tags = ImportanceScoring.parseTags(runCatching { obj["tags"]?.toString() }.getOrNull())
            // happenedAt 是相对说法（昨天/上周三/去年冬天）：用 now 换算，算不出来就 null。
            val happenedRaw = runCatching { obj["happenedAt"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val happenedAt = ImportanceScoring.parseRelativeTime(happenedRaw, now)
            ExtractedMemory(
                content = content.take(800),
                category = category,
                drawer = drawer,
                importance = importance,
                confidence = confidence.coerceIn(0f, 1f),
                sourceQuote = sourceQuote.take(120),
                tags = tags,
                happenedAt = happenedAt
            )
        }
    }

    private fun ExtractedMemory.toCandidate(): DomainMemoryCandidate =
        DomainMemoryCandidate(
            content = content,
            drawer = drawer,
            importance = importance,
            sourceQuote = sourceQuote,
            categoryName = category.name,
            confidence = confidence,
            tags = tags,
            happenedAt = happenedAt
        )

    private fun DomainMemoryCandidate.categoryValue(): MemoryCategory =
        runCatching { MemoryCategory.valueOf(categoryName.trim().uppercase()) }
            .getOrDefault(
                when (drawer) {
                    MemoryDrawer.EVENT -> MemoryCategory.EVENT
                    MemoryDrawer.SELF -> MemoryCategory.OTHER
                    MemoryDrawer.USER -> MemoryCategory.FACT
                }
            )

    private fun DomainMemoryCandidate.toEntity(
        personaId: String,
        sessionId: String,
        timestamp: Long
    ): MemoryEntity = MemoryEntity(
        id = UUID.randomUUID().toString(),
        personaId = personaId,
        content = content.take(800),
        category = categoryValue(),
        importance = importance.coerceIn(1, 5),
        pinned = false,
        sourceSessionId = sessionId,
        createdAt = timestamp,
        updatedAt = timestamp,
        lastUsedAt = 0,
        useCount = 1,
        drawer = drawer.name,
        sourceQuote = sourceQuote.takeIf { it.isNotBlank() },
        tags = MemoryTags.encode(tags),
        happenedAt = happenedAt?.takeIf { it > 0L }
    )

    private fun DomainMemoryCandidate.toEchoEntity(personaId: String, timestamp: Long): MemoryEchoEntity =
        MemoryEchoEntity(
            id = UUID.randomUUID().toString(),
            personaId = personaId,
            drawer = drawer.name,
            content = content.take(800),
            importance = importance.coerceIn(1, 5),
            sourceQuote = sourceQuote,
            categoryName = categoryValue().name,
            confidence = confidence,
            createdAt = timestamp,
            lastSeenAt = timestamp
        )

    private fun MemoryEchoEntity.toCandidate(): DomainMemoryCandidate =
        DomainMemoryCandidate(
            content = content,
            drawer = runCatching { MemoryDrawer.valueOf(drawer.trim().uppercase()) }
                .getOrElse { MemoryScreening.drawerOf(categoryName) },
            importance = importance,
            sourceQuote = sourceQuote,
            categoryName = categoryName,
            confidence = confidence
        )

    private fun normalize(text: String): String =
        text.trim().lowercase().replace(Regex("\\s+"), "").replace(Regex("[，。！？,.!?；;]"), "")

    private data class ExtractedMemory(
        val content: String,
        val category: MemoryCategory,
        val drawer: MemoryDrawer,
        val importance: Int,
        val confidence: Float,
        val sourceQuote: String,
        /** 模型给的短标签（已清洗：≤5 个、每个 ≤12 字）。 */
        val tags: List<String> = emptyList(),
        /** 这件事发生的时间（毫秒）；换算不出来就是 null。 */
        val happenedAt: Long? = null
    )

    private companion object {
        const val MEMORY_INTERVAL = 6
        const val MAX_MEMORY_CONTEXT_CHARS = 7000

        /** 同一条记忆至少隔 6 小时才写一次「被想起来」，避免每轮对话都写库。 */
        const val RECALL_TOUCH_INTERVAL_MILLIS = 6 * 3_600_000L

        const val STATUS_ARCHIVED = "ARCHIVED"

        /** 候选集预留的额外容量（索引/标签/时间捞回来的记忆条数上限之和）。 */
        const val CANDIDATE_EXTRA = 160

        /** 倒排索引一次最多返回多少条候选：宁可多，排序在内存里做。 */
        const val INDEX_CANDIDATES = 120

        /** LIKE 兜底候选上限。 */
        const val LIKE_CANDIDATES = 60

        /** 拿几个查询词元去查标签（标签是短词，前几个通常就是人名/主题词）。 */
        const val TAG_LOOKUP_TERMS = 3

        /** 单个标签一次最多带回来多少条。 */
        const val TAG_CANDIDATES = 30

        /** 「去年冬天」这类时间指代一次最多带回来多少条有发生时间的记忆。 */
        const val TIME_CANDIDATES = 40

        /** 补建索引时每个人格最多补多少条（和上下文取数同一量级）。 */
        const val INDEX_BACKFILL_LIMIT = 500

        /** 向量这一路一次取多少条：16 条足够把「换个说法」的漏补上，再多就是噪声。 */
        const val VECTOR_TOP_K = 16
    }
}
