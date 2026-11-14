package com.myfriend.app.data.repo

import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MemoryDao
import com.myfriend.app.data.local.MemoryEntity
import com.myfriend.app.data.local.MemoryVersionDao
import com.myfriend.app.data.local.MemoryVersionEntity
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.domain.DriftBudget
import com.myfriend.app.domain.MemoryReconsolidation
import com.myfriend.app.domain.MoodState
import com.myfriend.app.domain.ResolvedApiConfig
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.InferenceParams
import com.myfriend.app.network.WireMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** 一次回忆重构到底做成了什么。 */
enum class DriftStatus {
    /** 真的改了一点，并且写进了版本历史。 */
    DONE,

    /** 这次什么都没动（模型说不用改，或者已经定型）。 */
    UNCHANGED,

    /** 模型改了，但没通过校验 —— 宁可当没发生，只记「又想起过一次」。 */
    REFUSED,

    /** 记忆开关关着。 */
    DISABLED,

    /** 找不到这个人格。 */
    NO_PERSONA,

    /** 这条记忆不存在、不属于这个人格、或者太短。 */
    NO_MEMORY,

    /** 没有可用的 API 配置。 */
    NO_CONFIG,

    /** 调用失败（网络、额度、解析……）。都当成没做。 */
    FAILED
}

/**
 * 一次回忆重构的结果。
 *
 * @param before 改之前的正文
 * @param after 改之后的正文（被拒绝时是模型想改成的样子，只用于日志，不落库）
 * @param similarity 改完与改之前的相似度
 */
data class DriftResult(
    val status: DriftStatus,
    val memoryId: String = "",
    val before: String = "",
    val after: String = "",
    val similarity: Double = 0.0,
    val summary: String = ""
) {
    val drifted: Boolean get() = status == DriftStatus.DONE
}

/**
 * 回忆重构：他想起一件事的时候，这件事会被此刻的心情轻轻染一下。
 *
 * ## 这是一次「用完即弃的分叉调用」
 *
 * 和 [MemoryConsolidator] 一样，这里自己拼 system + user 直接调模型：
 * 没有会话、没有历史、没有工具、不写 messages 表，调用一结束只留下一条
 * [MemoryVersionEntity]（或者什么都不留）。温度压到 0.3，输出只要一小段正文。
 *
 * ## 谁负责什么
 *
 * - 领域层 [MemoryReconsolidation] 决定「允许改多少」和「这一段能不能落地」；
 * - 本类只负责：读记忆、拼提示词、解析一段正文、按领域结论落库；
 * - 校验没过就只记 [MemoryDao.markUsed]，**绝不**把没通过校验的文本写进记忆。
 *
 * ## 边界（都是安静返回，不抛异常）
 *
 * 记忆开关关着、没人设、没配置、记忆不存在或太短、已经定型、调用失败 ——
 * 一律返回对应 [DriftStatus]，调用方不需要 try/catch。
 */
@Singleton
class MemoryReconsolidator @Inject constructor(
    private val memoryDao: MemoryDao,
    private val memoryVersionDao: MemoryVersionDao,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val moodRepository: MoodRepository,
    private val registry: AiProviderRegistry,
    /** 改完正文要重建倒排索引，否则「被想起改写过的记忆」再也搜不到。 */
    private val memoryRepository: MemoryRepository
) {

    /** 同一个人的两段记忆不要同时改：并发写库没必要，也会多烧一次额度。 */
    private val driftMutex = Mutex()

    /** 每个人格上次「想起并尝试改写」的时间，用来做间隔（内存里就够，重启后重新算）。 */
    private val lastAttemptAt = mutableMapOf<String, Long>()

    /**
     * 让一段记忆在「此刻的心情」里被想起一次。
     *
     * @param mood 此刻的心情（由调用方传，别让本类自己去猜）
     * @param now 时间基准
     */
    suspend fun reconsider(
        personaId: String,
        memoryId: String,
        mood: MoodState,
        now: Long = System.currentTimeMillis()
    ): DriftResult {
        // 1.9：记忆内置开启，不再有「记忆开关」这种东西；DISABLED 状态保留只为兼容旧调用。
        val memory = runCatching { memoryDao.getById(memoryId) }.getOrNull()
            ?: return DriftResult(DriftStatus.NO_MEMORY, memoryId)
        if (memory.personaId != personaId) {
            return DriftResult(DriftStatus.NO_MEMORY, memoryId)
        }
        if (memory.content.trim().length < MIN_CONTENT_CHARS) {
            return DriftResult(DriftStatus.NO_MEMORY, memoryId, summary = "这条太短了，没什么好动的")
        }
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull()
            ?: return DriftResult(DriftStatus.NO_PERSONA, memoryId)

        val budget = MemoryReconsolidation.driftBudget(mood, ageDaysOf(memory, now), memory.driftCount)
        if (!budget.allowed) {
            // 已经定型：只记「被想起过一次」，不再花额度。
            runCatching { memoryDao.markUsed(memory.id, now) }
            return DriftResult(DriftStatus.UNCHANGED, memoryId, before = memory.content, summary = budget.reason)
        }
        val resolved = resolveConfig(persona)
            ?: return DriftResult(DriftStatus.NO_CONFIG, memoryId, summary = "还没有可用的 API 配置")

        // ---- 一次用完即弃的分叉调用：没有会话、没有工具、没有历史 ----
        val raw = try {
            registry.adapterFor(resolved.config.provider).complete(
                config = resolved.config,
                apiKey = resolved.apiKey,
                model = resolved.config.model,
                messages = listOf(
                    WireMessage("system", MemoryReconsolidation.systemPrompt()),
                    WireMessage(
                        "user",
                        MemoryReconsolidation.prompt(
                            content = memory.content,
                            mood = mood,
                            driftCount = memory.driftCount,
                            budget = budget,
                            ageText = ageText(memory, now)
                        )
                    )
                ),
                // 只让它动几个词，不需要创造力。
                params = InferenceParams(temperature = TEMPERATURE, maxOutputTokens = MAX_OUTPUT_TOKENS),
                kind = com.myfriend.app.domain.UsageKind.RECONSOLIDATION,
                personaId = personaId
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return DriftResult(
                DriftStatus.FAILED,
                memoryId,
                before = memory.content,
                summary = "想的时候出错了：${error.message ?: "未知错误"}".take(120)
            )
        }

        val proposed = cleanProposal(raw)
        if (proposed.isBlank() || isNoChange(proposed, memory.content)) {
            runCatching { memoryDao.markUsed(memory.id, now) }
            return DriftResult(DriftStatus.UNCHANGED, memoryId, before = memory.content, summary = "这次一个字没动")
        }

        val anchors = anchorsOf(memory)
        val accepted = MemoryReconsolidation.acceptDrift(memory.content, proposed, budget, anchors) &&
            withinAnchor(memory, proposed, budget)
        if (!accepted) {
            // 校验没过：这次改写作废，但「他想起过这件事」是真的。
            runCatching { memoryDao.markUsed(memory.id, now) }
            return DriftResult(
                DriftStatus.REFUSED,
                memoryId,
                before = memory.content,
                after = proposed,
                similarity = MemoryReconsolidation.similarity(memory.content, proposed),
                summary = "改得太多，最后没动它"
            )
        }

        val similarity = MemoryReconsolidation.similarity(memory.content, proposed)
        val applied = runCatching {
            memoryDao.applyDrift(
                id = memory.id,
                content = proposed,
                beforeContent = memory.content,
                now = now
            )
            memoryVersionDao.insert(
                MemoryVersionEntity(
                    id = UUID.randomUUID().toString(),
                    memoryId = memory.id,
                    at = now,
                    content = proposed,
                    beforeContent = memory.content,
                    reason = MemoryReconsolidation.versionReason(mood, memory.driftCount + 1, similarity),
                    moodLabel = mood.label.trim().ifBlank { null }
                )
            )
        }
        if (applied.isSuccess) {
            // 正文变了，索引要跟着换；失败不影响这次改写本身。
            runCatching { memoryRepository.reindexTerms(memory.id) }
        }
        if (applied.isFailure) {
            return DriftResult(
                DriftStatus.FAILED,
                memoryId,
                before = memory.content,
                summary = "写回记忆时出错，当作没改"
            )
        }
        return DriftResult(
            DriftStatus.DONE,
            memoryId,
            before = memory.content,
            after = proposed,
            similarity = similarity,
            summary = "想起它的时候，措辞被此刻的心情染了一点"
        )
    }

    /**
     * 主线在「回忆」之后调这个：**只挑 1-2 条**，大多数时候什么都不发生。
     *
     * 为什么这么克制：
     * - 先摇一次骰子（[AFTER_RECALL_CHANCE]），免得每句对话都多花一次模型调用；
     * - 同一个人格两次之间至少隔 [COOLDOWN_MS]；
     * - 已经定型的、太短的、不属于这个人格的一律跳过，连提示词都不拼。
     *
     * 全程不抛异常：recall 是主线的关键路径，这里只当「顺手想起」。
     *
     * @return 这次真的处理过的记忆（可能为空）
     */
    suspend fun reconsiderAfterRecall(
        personaId: String,
        memoryIds: List<String>,
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): List<DriftResult> {
        if (personaId.isBlank() || memoryIds.isEmpty()) return emptyList()
        // 大多数回忆不会改写记忆：这只是「偶尔走神」。
        if (random.nextDouble() > AFTER_RECALL_CHANCE) return emptyList()

        return driftMutex.withLock {
            val last = lastAttemptAt[personaId] ?: 0L
            if (now - last < COOLDOWN_MS) return@withLock emptyList()

            // 用 List 而不是 Sequence：Sequence.filter 不是 inline，里面不能调 suspend 函数。
            val eligible = memoryIds
                .filter { it.isNotBlank() }
                .distinct()
                .filter { id -> runCatching { driftable(personaId, id) }.getOrDefault(false) }
            if (eligible.isEmpty()) return@withLock emptyList()

            lastAttemptAt[personaId] = now
            val mood = runCatching { moodRepository.current(personaId, now) }
                .getOrDefault(MoodState())
            pickFew(eligible, random).mapNotNull { id ->
                runCatching { reconsider(personaId, id, mood, now) }.getOrNull()
                    ?.takeIf { it.status == DriftStatus.DONE || it.status == DriftStatus.REFUSED }
            }
        }
    }

    /** 这个人格被改动过的记忆条数（界面/日志想看「他的记忆动过几次」时用）。 */
    suspend fun driftedCount(personaId: String): Int =
        runCatching { memoryDao.countDrifted(personaId) }.getOrDefault(0)

    /**
     * **删除清理入口**：`memory_versions`（记忆旧正文）是它写的，也必须由它清。
     *
     * 这张表只挂在 `memoryId` 上、没有外键，0.10.2 之前没有任何删除路径：
     * 每次「回忆漂移」都写一条，删人格 / 清空聊天与记忆 / 删除全部数据 / 备份替换
     * 都会把这些整段用户信息留在 `myfriend.db` 里。
     *
     * @param personaId null = 整表清空（删除全部数据 / 备份替换）；
     *                  非 null = 只在「这个人的记忆已经删掉之后」回收孤儿行。
     */
    suspend fun purgeVersions(personaId: String? = null) {
        runCatching {
            if (personaId == null) memoryVersionDao.deleteAll() else memoryVersionDao.deleteOrphans()
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 这条路能不能走：属于这个人、够长、还没定型。 */
    private suspend fun driftable(personaId: String, memoryId: String): Boolean {
        val memory = memoryDao.getById(memoryId) ?: return false
        if (memory.personaId != personaId) return false
        if (memory.content.trim().length < MIN_CONTENT_CHARS) return false
        return memory.driftCount < MemoryReconsolidation.MAX_DRIFTS
    }

    /** 只挑 1-2 条，随机；不会把一整批回忆都改一遍。 */
    private fun pickFew(candidates: List<String>, random: Random): List<String> {
        if (candidates.size == 1) return candidates
        val shuffled = candidates.shuffled(random)
        val count = if (shuffled.size >= 2 && random.nextBoolean()) 2 else 1
        return shuffled.take(count)
    }

    /**
     * 和「第一次写下的原文」比一眼：单次放宽过了，不等于累计漂移也该宽。
     * 没有锚点时（从没改过）直接放行。
     */
    private fun withinAnchor(memory: MemoryEntity, proposed: String, budget: DriftBudget): Boolean {
        val anchor = memory.originalContent?.takeIf { it.isNotBlank() } ?: return true
        val similarity = MemoryReconsolidation.similarity(anchor, proposed)
        return similarity >= MemoryReconsolidation.anchorSimilarity(budget)
    }

    /**
     * 把模型那段输出洗成一段正文。
     *
     * 模型有时会包 ``` 围栏、加「正文：」、套引号；这些都不该进记忆。
     */
    private fun cleanProposal(raw: String): String {
        var text = raw.lines()
            .filterNot { it.trim().startsWith("```") }
            .joinToString("\n")
            .trim()
        listOf("正文：", "正文:", "改后：", "改后:", "改完之后：").forEach { prefix ->
            if (text.startsWith(prefix)) text = text.removePrefix(prefix).trim()
        }
        text = text.trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』').trim()
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** 模型明确说「不改」、或干脆把原文抄回来时，不算一次改动。 */
    private fun isNoChange(proposed: String, before: String): Boolean {
        val flat = proposed.replace(Regex("\\s+"), "")
        val markers = listOf("不改", "不变", "不需要改", "@@不改@@", "原文不变")
        if (markers.any { flat == it } || (flat.length <= 6 && markers.any { flat.contains(it) })) return true
        return flat == before.replace(Regex("\\s+"), "")
    }

    /** 记忆标签里的人名/物名：有就必须原样保留，没有就传空。 */
    private fun anchorsOf(memory: MemoryEntity): List<String> {
        val raw = memory.tags?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .map { it.trim() }
                .filter { it.length in 2..12 }
                .distinct()
                .take(MAX_ANCHORS)
        }.getOrDefault(emptyList())
    }

    private suspend fun resolveConfig(persona: PersonaEntity): ResolvedApiConfig? {
        val configId = persona.defaultApiConfigId
            ?: runCatching { sessionDao.latestForPersona(persona.id)?.apiConfigId }.getOrNull()
            ?: return null
        return runCatching { apiConfigRepository.resolve(configId) }.getOrNull()
    }

    /** 这段记忆算多久以前的：优先用「事情发生的时间」，没有就用记下来的时间。 */
    private fun ageDaysOf(memory: MemoryEntity, now: Long): Double {
        val base = memory.happenedAt?.takeIf { it > 0L } ?: memory.createdAt
        if (base <= 0L) return 0.0
        return ((now - base).coerceAtLeast(0L)) / DAY_MS
    }

    private fun ageText(memory: MemoryEntity, now: Long): String {
        val days = ageDaysOf(memory, now)
        return when {
            days < 1.0 -> "这是最近几天的事。"
            days < 2.0 -> "这是昨天前后的事。"
            days < 40.0 -> "这大概是 ${days.toInt()} 天前的事。"
            days < 365.0 -> "这大概是 ${(days / 30).toInt()} 个月前的事。"
            else -> "这已经是一年多以前的事了。"
        }
    }

    private companion object {
        /** 一条记忆短于这个长度就没必要重构（改也改不出什么）。 */
        const val MIN_CONTENT_CHARS = 12

        /** 重构调用只要一小段正文：温度低、输出短。 */
        const val TEMPERATURE = 0.3
        const val MAX_OUTPUT_TOKENS = 260

        /** 回忆之后真正去尝试改写的概率：大多数时候什么都不发生。 */
        const val AFTER_RECALL_CHANCE = 0.34

        /** 同一个人格两次尝试之间至少隔这么久。 */
        const val COOLDOWN_MS = 20 * 60 * 1000L

        /** 最多认几个锚点词。 */
        const val MAX_ANCHORS = 6

        const val DAY_MS = 24 * 60 * 60 * 1000.0
    }
}
