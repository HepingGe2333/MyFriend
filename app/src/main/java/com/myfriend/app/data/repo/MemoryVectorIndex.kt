package com.myfriend.app.data.repo

import com.myfriend.app.data.local.MemoryDao
import com.myfriend.app.data.local.MemoryVectorDao
import com.myfriend.app.data.local.MemoryVectorEntity
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.domain.LocalEmbedding
import com.myfriend.app.domain.VectorMath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 检索时一块扫多少条向量：128 个 FloatArray ≈ 200KB 的瞬时峰值，扫完即丢。 */
private const val SEARCH_CHUNK = 128

/** 一条向量检索结果：记忆 id + 余弦相似度（0..1，越像越大）。 */
data class VectorHit(val memoryId: String, val similarity: Double)

/**
 * 库里这条向量还算不算数：provider 与维度都得对上。
 *
 * 换嵌入实现时**只改** [LocalEmbedding.PROVIDER]，旧向量就在这一步自然失效——
 * 不需要迁移脚本，也不会出现「旧向量按新含义被解读」这种最难查的错。
 */
internal fun isUsableVector(provider: String, dim: Int): Boolean =
    provider == LocalEmbedding.PROVIDER && dim == LocalEmbedding.DIM

/**
 * 清掉上一代嵌入实现留下的向量（同一时刻只允许一种 provider 的向量在库里）。
 *
 * 返回删掉的行数（Room 的 `DELETE` 会回传受影响行数），只为日志与单测可见；
 * 失败一律当作「删了 0 条」——清理是顺手做的事，不该让启动流程失败。
 *
 * 抽成独立函数是为了能用假 DAO 把「换实现后旧向量不参与检索」这条约定钉在单测里。
 */
internal suspend fun pruneForeignVectors(dao: MemoryVectorDao): Int =
    runCatching { dao.deleteOtherProviders(LocalEmbedding.PROVIDER) }.getOrDefault(0)

/**
 * 在给定的记忆 id 里按向量余弦取 topK。
 *
 * - 只查向量表，不碰记忆正文：正文由调用方自己拿着（召回时本来就已经取过了）；
 * - **分块扫**：一次只把 [SEARCH_CHUNK] 条向量解码进内存，算完这块就丢掉，
 *   所以就算候选有上千条，同一时刻也只持有 128 个 FloatArray（约 200KB）；
 * - 每块先取一次 topK 再合并：部分选择 + 分块，全程不建「全量候选列表」；
 * - 坏数据（半个 float、维度不对、别的 provider、NaN）一律跳过，不抛异常。
 */
internal suspend fun searchVectors(
    dao: MemoryVectorDao,
    memoryIds: Collection<String>,
    queryVector: FloatArray,
    limit: Int
): List<VectorHit> {
    if (limit <= 0 || memoryIds.isEmpty()) return emptyList()
    if (queryVector.isEmpty() || VectorMath.isZero(queryVector)) return emptyList()
    val ids = memoryIds.filter { it.isNotBlank() }.distinct()
    if (ids.isEmpty()) return emptyList()

    val perChunk = ArrayList<VectorHit>(limit * 2)
    for (chunk in ids.chunked(SEARCH_CHUNK)) {
        val rows = runCatching { dao.forMemories(chunk, LocalEmbedding.PROVIDER) }
            .getOrDefault(emptyList())
        if (rows.isEmpty()) continue
        val hits = ArrayList<VectorHit>(rows.size)
        rows.forEach { row ->
            if (!isUsableVector(row.provider, row.dim)) return@forEach
            val vector = VectorMath.decode(row.vector, row.dim) ?: return@forEach
            if (vector.size != LocalEmbedding.DIM) return@forEach
            val similarity = VectorMath.cosine(queryVector, vector)
            if (!similarity.isFinite() || similarity <= 0.0) return@forEach
            hits += VectorHit(row.memoryId, similarity.coerceIn(0.0, 1.0))
        }
        perChunk += VectorMath.topK(hits, limit) { it.similarity }
    }
    if (perChunk.isEmpty()) return emptyList()
    return VectorMath.topK(perChunk, limit) { it.similarity }
}

/**
 * 记忆向量的本地索引：建、改、删、查，全在这一个地方。
 *
 * ## 它是什么
 *
 * `memory_vectors` 表里每条记忆一行（本实现是 384 个 float32 ≈ 1.5KB），
 * 查询时按余弦取最像的几条，作为「倒排索引之外的第二条线索」。
 * 倒排索引治「同一个词换个说法」，向量治「同一个意思换一片字」——两者互补，都不够用时还有 LIKE 兜底。
 *
 * ## 内存纪律
 *
 * - **不缓存记忆向量**：任何时刻都不把整张表的 FloatArray 留在内存里。
 *   补建与检索都按批走（补建 64 条一批、检索 128 条一块），扫完即丢；
 * - 只 memo 「最近一次查询的嵌入」（一条，约 1.5KB）：同一轮里拼上下文和回忆工具可能用同一句话查两遍；
 * - [clearCache] 留给系统的低内存回调，随时可以把上面那一条丢掉。
 *
 * ## 失败策略
 *
 * 索引为空、编码出错、DAO 抛异常 —— 全部静默降级成空结果。调用方（[MemoryRepository]、
 * [RecallRepository]）据此退回原来的倒排/LIKE 路径：**加了向量只会更好，绝不会更差**。
 */
@Singleton
class MemoryVectorIndex @Inject constructor(
    private val memoryDao: MemoryDao,
    private val personaDao: PersonaDao,
    private val vectorDao: MemoryVectorDao
) {

    /** 补建索引的互斥锁：启动补建与第一次召回可能同时触发，只让一个人干活。 */
    private val backfillMutex = Mutex()

    /** 这次进程里已经确认补齐过的人格（受 [backfillMutex] 保护）：确认过就不再扫一遍。 */
    private val ensuredPersonas = mutableSetOf<String>()

    /** 最近一次查询的嵌入（值很小，只为省掉同一轮里重复编码同一句话）。 */
    @Volatile
    private var cachedQuery: String? = null

    @Volatile
    private var cachedQueryVector: FloatArray? = null

    /**
     * 补齐缺失向量：老库升上来的记忆（v8 之前写的）没有向量，第一次用到时补一次。
     *
     * @param personaId 只补这一个人格；传 null 表示所有人格（启动时用这个）。
     * @return 这次实际写入的向量条数。
     *
     * 三道省时省内存的闸门：进程内记忆「这个人格已经补齐」→ 直接返回；
     * 「向量数已经够记忆数」→ 直接返回；真的要补时也**按 64 条一批**读、编码、写，
     * 不会为了补 500 条记忆一次性建 500 个 FloatArray。
     */
    suspend fun ensureIndex(personaId: String? = null): Int {
        val targets = runCatching {
            if (personaId.isNullOrBlank()) personaDao.getAll()
            else listOfNotNull(personaDao.getById(personaId))
        }.getOrDefault(emptyList())
        if (targets.isEmpty()) return 0

        return backfillMutex.withLock {
            val pending = targets.filter { it.id !in ensuredPersonas }
            if (pending.isEmpty()) return@withLock 0

            if (personaId.isNullOrBlank()) {
                // 全库补建时才清理：换过嵌入实现的话，上一代向量在这里一次性作废。
                pruneForeignProviders()
                runCatching { vectorDao.deleteOrphans() }
            }

            var written = 0
            for (persona in pending) {
                written += runCatching { backfillPersona(persona.id) }.getOrDefault(0)
                // 失败了也记成「已确认」：与其每次召回都重试一遍拖慢聊天，不如等下次进程启动。
                ensuredPersonas += persona.id
            }
            written
        }
    }

    /**
     * 某条记忆的正文写入/加固/漂移/整理之后调用：重算并覆盖它的向量。
     *
     * 正文变空白（理论上不会发生）时删掉旧向量，否则一条已经被清空的记忆还会被向量检索翻出来。
     */
    suspend fun upsert(memoryId: String, content: String) {
        if (memoryId.isBlank()) return
        runCatching {
            val row = encodeRow(memoryId, content)
            if (row == null) vectorDao.deleteByMemory(memoryId) else vectorDao.insertAll(listOf(row))
        }
    }

    /** 记忆被真正删掉时调用：向量不能比记忆活得久。 */
    suspend fun remove(memoryId: String) {
        if (memoryId.isBlank()) return
        runCatching { vectorDao.deleteByMemory(memoryId) }
    }

    /**
     * 在整个人的记忆里按向量取 topK。
     *
     * 契约方法；生产路径更常用 [searchIn] —— 召回时调用方手里**已经**有候选 id 了，
     * 再按人格查一遍正文只是白白多读几百行（见 [MemoryRepository.getRelevant] 的注释）。
     */
    suspend fun search(personaId: String, query: String, limit: Int): List<VectorHit> {
        if (personaId.isBlank() || limit <= 0 || query.isBlank()) return emptyList()
        runCatching { ensureIndex(personaId) }
        val ids = runCatching {
            memoryDao.getForPrompt(personaId, SEARCH_SCAN_LIMIT).map { it.id }
        }.getOrDefault(emptyList())
        return searchIn(ids, query, limit)
    }

    /**
     * 在给定的记忆 id 集合里按向量取 topK。
     *
     * 这是两条召回链路真正用到的方法：它们把「已经捞上来的候选并集」的 id 传进来，
     * 于是向量检索不会额外读一遍正文，也不会把别的抽屉/别的记忆混进来。
     */
    suspend fun searchIn(memoryIds: Collection<String>, query: String, limit: Int): List<VectorHit> {
        if (memoryIds.isEmpty() || limit <= 0) return emptyList()
        val queryVector = queryVectorOf(query) ?: return emptyList()
        return searchVectors(vectorDao, memoryIds, queryVector, limit)
    }

    /** 清掉上一代嵌入实现的向量（换实现、或存储紧张时手动回收空间）。返回删掉的行数。 */
    suspend fun pruneForeignProviders(): Int = pruneForeignVectors(vectorDao)

    /** 回收「记忆已经不在、向量还留着」的孤儿行。 */
    suspend fun pruneOrphans(): Int = runCatching { vectorDao.deleteOrphans() }.getOrDefault(0)

    /**
     * 低内存回调用：丢掉缓存的查询嵌入。
     * 记忆向量本来就不常驻，所以这里没有「全表缓存」需要清 —— 这正是设计成这样的原因。
     */
    fun clearCache() {
        cachedQuery = null
        cachedQueryVector = null
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 补一个人格的向量。返回写入条数。
     *
     * 判据只能按这批 id 去查「谁已经有向量」：`memory_vectors` 里没有 personaId 列，
     * 拿全库向量数跟单人格记忆数比会漏补（那个人格的记忆可能一条向量都没有）。
     * 好在这一步每个进程每个人格只跑一次。
     */
    private suspend fun backfillPersona(personaId: String): Int {
        var written = 0
        // 一轮只取一批「还缺向量」的 id，补完再问一次——直到问不出东西，
        // 所以记忆超过一批也补得完（之前用固定上限一把抓，第 N 条之后永远没有向量）。
        var rounds = 0
        while (rounds < BACKFILL_MAX_ROUNDS) {
            rounds++
            val missing = runCatching {
                memoryDao.idsMissingVectors(personaId, LocalEmbedding.PROVIDER, LocalEmbedding.DIM, BACKFILL_BATCH)
            }.getOrDefault(emptyList())
            if (missing.isEmpty()) break
            val memories = runCatching { memoryDao.byIds(missing) }.getOrDefault(emptyList())
            if (memories.isEmpty()) break
            val rows = memories.mapNotNull { memory -> encodeRow(memory.id, memory.content) }
            if (rows.isEmpty()) {
                // 剩下的都是算不出向量的（纯标点之类）：不写向量，也不该无限循环。
                break
            }
            runCatching { vectorDao.insertAll(rows) }.onSuccess { written += rows.size }
            if (missing.size < BACKFILL_BATCH) break
        }
        return written
    }

    /** 正文 → 一行向量实体；空正文/纯标点（零向量）返回 null，表示「这条记忆不该有向量」。 */
    private fun encodeRow(memoryId: String, content: String): MemoryVectorEntity? {
        if (memoryId.isBlank() || content.isBlank()) return null
        val vector = runCatching { LocalEmbedding.embed(content) }.getOrNull() ?: return null
        if (VectorMath.isZero(vector)) return null
        return MemoryVectorEntity(
            memoryId = memoryId,
            provider = LocalEmbedding.PROVIDER,
            dim = LocalEmbedding.DIM,
            vector = VectorMath.encode(vector),
            updatedAt = System.currentTimeMillis()
        )
    }

    /** 查询文本 → 查询向量；空查询、纯虚词（零向量）返回 null，调用方直接跳过这一路。 */
    private fun queryVectorOf(query: String): FloatArray? {
        val normalized = query.trim()
        if (normalized.isEmpty()) return null
        val cached = cachedQueryVector
        if (cached != null && cachedQuery == normalized) return cached
        val vector = runCatching { LocalEmbedding.embed(normalized) }.getOrNull() ?: return null
        if (VectorMath.isZero(vector)) return null
        cachedQuery = normalized
        cachedQueryVector = vector
        return vector
    }

    private companion object {
        /** 补建时一批多少条：编码、写库、丢弃，都按这个粒度走。 */
        const val BACKFILL_BATCH = 64

        /**
         * 补建最多问几轮「还有谁缺向量」。
         *
         * 64 × 40 = 2560 条，已经超过这个 App 单人格记忆的实际上限；
         * 设个上限只是防止某种诡异数据让启动流程一直转下去。
         */
        const val BACKFILL_MAX_ROUNDS = 40

        /** [search] 按人格取 id 时的上限（正常情况下调用方用 [searchIn] 自己给 id）。 */
        const val SEARCH_SCAN_LIMIT = 400
    }
}
