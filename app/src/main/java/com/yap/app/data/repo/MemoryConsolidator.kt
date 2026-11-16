package com.yap.app.data.repo

import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MemoryConsolidationDao
import com.yap.app.data.local.MemoryConsolidationEntity
import com.yap.app.data.local.MemoryDao
import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.MemoryTermDao
import com.yap.app.data.local.MemoryTermEntity
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.ChineseTerms
import com.yap.app.domain.ConsolidationPlan
import com.yap.app.domain.ImportanceScoring
import com.yap.app.domain.LifecycleAction
import com.yap.app.domain.MemoryDrawer
import com.yap.app.domain.MemoryLifecycle
import com.yap.app.domain.MessageWindows
import com.yap.app.domain.MemoryLifecycleState
import com.yap.app.domain.MemoryOp
import com.yap.app.domain.MemoryScreening
import com.yap.app.domain.MemoryTags
import com.yap.app.domain.MemoryTier
import com.yap.app.domain.PersonaCard
import com.yap.app.domain.Recall
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.InferenceParams
import com.yap.app.network.WireMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次「每日记忆整理」的结果。
 *
 * @param status 这次到底做成了什么（关闭 / 没人设 / 没配置 / 没记忆 / 完成 / 失败）
 * @param kept 被明确留下的条数（含 raise 提权的）
 * @param merged 被并进别人的条数（不含被保留的那条）
 * @param archived 本轮被归档的条数（不含 merged）
 * @param forgotten 本轮被真正删除的条数
 * @param summary 模型给的一句话总结（失败时为中文错误说明）
 */
data class ConsolidationResult(
    val personaId: String,
    val status: ConsolidationStatus,
    val kept: Int = 0,
    val merged: Int = 0,
    val archived: Int = 0,
    val forgotten: Int = 0,
    val summary: String = "",
    val at: Long = 0L,
    val modelName: String? = null
) {
    val didAnything: Boolean get() = status == ConsolidationStatus.DONE

    /** 给日志 / 调试面板看的一句中文。 */
    fun describe(): String = when (status) {
        ConsolidationStatus.DONE ->
            "整理完记忆：留下 $kept 条、合并 $merged 条、归档 $archived 条、忘了 $forgotten 条"

        ConsolidationStatus.DISABLED -> "记忆整理已关闭"
        ConsolidationStatus.NO_PERSONA -> "找不到这个人格，没法整理记忆"
        ConsolidationStatus.NO_CONFIG -> "还没有可用的 API 配置，这次没整理记忆"
        ConsolidationStatus.NO_MEMORY -> "还没有需要整理的记忆"
        ConsolidationStatus.FAILED -> summary.ifBlank { "整理记忆时出错了" }
    }
}

enum class ConsolidationStatus { DONE, DISABLED, NO_PERSONA, NO_CONFIG, NO_MEMORY, FAILED }

/**
 * 每日记忆整理：把抽屉里的东西过一遍，留下印象深的，把不重要的挪出去。
 *
 * ## 「随风叉、用完即弃」是什么意思
 *
 * 整理**不是**一次聊天：这里自己拼一份 messages（[forkedMessages]）直接调模型，
 * 不建 session、不写 messages 表、不给工具、不读会话历史，调用一结束就把结果落库、
 * 把这次的请求上下文丢掉 —— 派生出来的会话根本不存在，也就没有任何「整理对话」会漏到界面上。
 * 同理，[WebSearchScorer] 的打分调用也是这样一次性的分叉。
 *
 * ## 谁负责什么
 *
 * - 模型只负责「下判断」：哪条留着、哪条并起来、哪条归档、哪条忘掉（[ConsolidationPlan]）；
 * - 本类负责「按判断改库」，并在模型没提到的记忆上跑 [MemoryLifecycle] 的时间衰减；
 * - 纯逻辑（阈值、衰减公式、JSON 容错解析）都在 domain 层，单测覆盖。
 */
@Singleton
class MemoryConsolidator @Inject constructor(
    private val memoryDao: MemoryDao,
    private val memoryTermDao: MemoryTermDao,
    /** 整理会改写正文，向量也要跟着换，否则这一路的相似度还停在旧措辞上。 */
    private val memoryVectorIndex: MemoryVectorIndex,
    private val consolidationDao: MemoryConsolidationDao,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val personaCardRepository: PersonaCardRepository,
    private val idleBrowsing: IdleBrowsing,
    private val registry: AiProviderRegistry
) {
    /** 同一时间只允许一次整理：两个 Worker 撞上时，后来的那次直接等前面结束。 */
    private val consolidateMutex = Mutex()

    /**
     * 整理一个人格的记忆。
     *
     * 安全返回：没开记忆整理、没人设、没 API 配置、没记忆时都不会抛异常，只返回对应 [ConsolidationStatus]。
     * 网络/模型出错时返回 [ConsolidationStatus.FAILED]，**不**写整理留痕（那不算一次整理）。
     */
    suspend fun consolidate(
        personaId: String,
        now: Long = System.currentTimeMillis()
    ): ConsolidationResult = consolidateMutex.withLock {
        try {
            consolidateLocked(personaId, now)
        } catch (error: CancellationException) {
            // Worker 被系统叫停：原样抛出去，别吞成「一次失败」。
            throw error
        } catch (error: Throwable) {
            ConsolidationResult(
                personaId = personaId,
                status = ConsolidationStatus.FAILED,
                summary = "整理记忆时出错了：${error.message ?: "未知错误"}".take(120),
                at = now
            )
        }
    }

    /** 后台 Worker 用：把「所有配了 API 的人格」各整理一次。 */
    suspend fun consolidateAll(now: Long = System.currentTimeMillis()): List<ConsolidationResult> {
        val personas = runCatching { personaDao.getAll() }.getOrDefault(emptyList())
        return personas.mapNotNull { persona ->
            // 没有可解析的 API 配置就跳过：整理要花一次模型调用，不该白花在没配置的人格上。
            val configId = runCatching { configIdOf(persona) }.getOrNull() ?: return@mapNotNull null
            if (runCatching { apiConfigRepository.resolve(configId) }.getOrNull() == null) {
                return@mapNotNull null
            }
            consolidate(persona.id, now).takeIf { it.status != ConsolidationStatus.DISABLED }
        }
    }

    // ---------------------------------------------------------------- 主流程

    private suspend fun consolidateLocked(personaId: String, now: Long): ConsolidationResult {
        // 1.9：整理记忆是内置行为，不再有开关；DISABLED 只保留为旧状态机的一档。
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull()
            ?: return ConsolidationResult(personaId, ConsolidationStatus.NO_PERSONA, at = now)

        val all = runCatching { memoryDao.getAllForPersona(persona.id) }.getOrDefault(emptyList())
            .take(MAX_MEMORIES_PER_RUN)
        if (all.isEmpty()) {
            return ConsolidationResult(persona.id, ConsolidationStatus.NO_MEMORY, at = now)
        }

        val configId = runCatching { configIdOf(persona) }.getOrNull()
        val resolved = configId?.let { runCatching { apiConfigRepository.resolve(it) }.getOrNull() }
            ?: return ConsolidationResult(persona.id, ConsolidationStatus.NO_CONFIG, at = now)
        val model = resolved.config.model

        // ---- 一次用完即弃的分叉调用：没有会话、没有工具、没有历史 ----
        // 清单按抽屉各取一批：USER 抽屉里的老事实（过敏、家人）不能被 EVENT 的琐事挤出去。
        val promptMemories = selectPromptMemories(all)
        val messages = forkedMessages(persona, promptMemories, all.size, now)
        val raw = try {
            registry.adapterFor(resolved.config.provider).complete(
                config = resolved.config,
                apiKey = resolved.apiKey,
                model = model,
                messages = messages,
                // 整理是「按规矩分类」，不需要创造力：温度压低到 0.25，输出长度受控。
                params = InferenceParams(temperature = 0.25, maxOutputTokens = 900),
                kind = com.yap.app.domain.UsageKind.CONSOLIDATION,
                personaId = persona.id
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return ConsolidationResult(
                personaId = persona.id,
                status = ConsolidationStatus.FAILED,
                summary = "整理记忆时出错了：${error.message ?: "未知错误"}".take(120),
                at = now,
                modelName = model
            )
        }
        // 到这里请求上下文已经用完即弃：raw 是唯一留下来的东西。
        val plan = ImportanceScoring.parseConsolidation(raw)

        val applied = applyPlan(plan, all, promptMemories, now)
        val decayed = applyDecay(all, applied.handledIds, persona.memoryRetention, now)

        val kept = applied.kept
        val merged = applied.merged
        val archived = applied.archived + decayed.archived
        val forgotten = applied.forgotten + decayed.forgotten
        val summary = plan.summary.ifBlank { defaultSummary(kept, merged, archived, forgotten) }

        // 无论模型给没给操作，都留一条痕迹：界面上要能说「他昨天整理过记忆」。
        runCatching {
            consolidationDao.insert(
                MemoryConsolidationEntity(
                    id = UUID.randomUUID().toString(),
                    personaId = persona.id,
                    at = now,
                    kept = kept,
                    merged = merged,
                    archived = archived,
                    forgotten = forgotten,
                    summary = summary.take(200),
                    modelName = model
                )
            )
        }

        return ConsolidationResult(
            personaId = persona.id,
            status = ConsolidationStatus.DONE,
            kept = kept,
            merged = merged,
            archived = archived,
            forgotten = forgotten,
            summary = summary,
            at = now,
            modelName = model
        )
    }

    // ---------------------------------------------------------------- 分叉会话（用完即弃）

    /**
     * 拼出这次整理用到的 messages。
     *
     * 这是一个**随风叉、用完即弃**的请求：只有 system（整理器提示词）+ user（编号清单 + 最近聊天摘要 +
     * 最近浏览 digest）两条，绝不带历史会话、绝不给工具、绝不写进任何 session/message 表。
     * 模型看到的世界只有这份清单，所以清单里必须自带一切判断依据（抽屉、重要度、hit、年龄、正文）。
     */
    private suspend fun forkedMessages(
        persona: PersonaEntity,
        memories: List<MemoryEntity>,
        totalMemories: Int,
        now: Long
    ): List<WireMessage> {
        val summary = personaSummary(persona)
        val system = ImportanceScoring.consolidationSystemPrompt(summary)
        val user = buildString {
            appendLine("现在：${formatTime(now)}")
            appendLine()
            appendLine(
                "【记忆清单】共 $totalMemories 条，列出了 ${memories.size} 条" +
                    "（编号 | 抽屉 | 重要度 | 被提起 | 多久没动 | 状态 | 正文；有的还会带「标签」「发生在」）"
            )
            memories.forEachIndexed { index, memory ->
                appendLine(memoryLine(index + 1, memory, now))
            }
            if (totalMemories > memories.size) {
                appendLine("（按三个抽屉各取了一批，剩下的 ${totalMemories - memories.size} 条没列出来，它们会自己变淡）")
            }
            appendLine()
            val chats = recentChatDigest(persona.id, now)
            appendLine("【最近聊到的】")
            appendLine(chats.ifBlank { "（最近没有聊天）" })
            appendLine()
            val browsing = runCatching { idleBrowsing.latestDigest(persona.id) }.getOrNull()
            appendLine("【他最近自己上网看到的】")
            appendLine(browsing?.take(MAX_BROWSE_DIGEST_CHARS)?.takeIf { it.isNotBlank() } ?: "（这几天没上网看东西）")
        }.trimEnd()
        return listOf(WireMessage("system", system), WireMessage("user", user))
    }

    /**
     * 一行清单：编号 + 判断所需的一切，正文截断到 [MAX_LINE_CONTENT_CHARS]。
     *
     * 「标签」与「发生在」有就写、没有就不写：它们不是必填字段，
     * 写成「标签：无」只会让模型以为要补一个，反而开始瞎编。
     */
    private fun memoryLine(index: Int, memory: MemoryEntity, now: Long): String {
        val drawer = drawerOf(memory)
        val age = ageText(memory, now)
        val pinned = if (memory.pinned) " | 钉住" else ""
        val tags = MemoryTags.decode(memory.tags)
        val tagText = if (tags.isEmpty()) "" else " | 标签：${tags.joinToString("、")}"
        val happened = memory.happenedAt?.takeIf { it > 0L }
        val happenedText = if (happened == null) "" else " | 发生在：${Recall.dateLabel(happened, now)}"
        val body = memory.content.replace('\n', ' ').trim().take(MAX_LINE_CONTENT_CHARS)
        return "第$index 条 | $drawer | 重要度${memory.importance.coerceIn(1, 5)} | " +
            "被提起 ${memory.useCount.coerceAtLeast(0)} 次 | $age | " +
            tierLabel(MemoryTier.parse(memory.status)) + pinned + tagText + happenedText + " | $body"
    }

    /**
     * 按抽屉各取一批进清单：USER 抽屉的老事实不该被 EVENT 的琐事挤掉。
     *
     * 以前是「按 updatedAt 取最近 60 条」——EVENT 抽屉天天在动，半年前写下的
     * 「他对芒果过敏」很容易排到第 61 名以外：整理器根本看不到它，也就不会去 keep 它。
     * 现在每个抽屉各留 [MAX_PROMPT_PER_DRAWER] 个座位（总数不超过 [MAX_PROMPT_MEMORIES]）。
     *
     * 注意：编号是「清单里的第几条」，[applyPlan] 必须用同一份列表建索引表。
     */
    private fun selectPromptMemories(memories: List<MemoryEntity>): List<MemoryEntity> {
        if (memories.size <= MAX_PROMPT_MEMORIES) return memories
        val ordered = memories.sortedWith(
            compareByDescending<MemoryEntity> { it.pinned }
                .thenByDescending { it.importance }
                .thenByDescending { it.updatedAt }
        )
        val picked = LinkedHashMap<String, MemoryEntity>()
        MemoryDrawer.entries.forEach { drawer ->
            ordered.asSequence()
                .filter { drawerOf(it) == drawer }
                .take(MAX_PROMPT_PER_DRAWER)
                .forEach { picked[it.id] = it }
        }
        // 某个抽屉不够 30 条时，用剩下的记忆按原顺序补满，总上限不变。
        if (picked.size < MAX_PROMPT_MEMORIES) {
            ordered.asSequence()
                .filter { it.id !in picked }
                .take(MAX_PROMPT_MEMORIES - picked.size)
                .forEach { picked[it.id] = it }
        }
        return picked.values.sortedWith(
            compareByDescending<MemoryEntity> { it.pinned }
                .thenByDescending { it.importance }
                .thenByDescending { it.updatedAt }
        )
    }

    /** 最近 8 句对话的摘要（只取最新一个会话），用于判断「最近在意什么」。 */
    private suspend fun recentChatDigest(personaId: String, now: Long): String = runCatching {
        val session = sessionDao.latestForPersona(personaId) ?: return@runCatching ""
        // 只查最近 40 行再取最后 8 句：整理记忆的后台 worker 没必要把整段历史搬进内存。
        val recentRows = messageDao.recentBySession(session.id, MessageWindows.WORKER_RECENT_ROWS)
        val messages = MessageWindows.lastN(recentRows, MAX_RECENT_MESSAGES) {
            it.role != MessageRole.SYSTEM && it.content.isNotBlank()
        }
        messages.joinToString("\n") { message ->
            val who = if (message.role == MessageRole.USER) "对方" else "他"
            "- ${who}（${ageText(message.createdAt, now)}）：${message.content.replace('\n', ' ').take(60)}"
        }
    }.getOrDefault("")

    /** 人设摘要：只取最能说明「他是谁」的几项，避免整理提示词过长。 */
    private suspend fun personaSummary(persona: PersonaEntity): String {
        val card = runCatching { personaCardRepository.cardOf(persona.id) }.getOrNull()
            ?: PersonaCard()
        val parts = buildList {
            val name = card.identity.name.ifBlank { persona.name }
            if (name.isNotBlank()) add(name)
            card.identity.age.takeIf { it.isNotBlank() }?.let { add(it) }
            card.identity.role.takeIf { it.isNotBlank() }?.let { add("身份：$it") }
            card.identity.relation.takeIf { it.isNotBlank() }?.let { add("关系：$it") }
            val traits = card.traits.map { it.name }.filter { it.isNotBlank() }.take(3)
            if (traits.isNotEmpty()) add("性格：" + traits.joinToString("、"))
            card.life.caresAbout.takeIf { it.isNotBlank() }?.let { add("最近关心：${it.take(40)}") }
            card.bond.takeIf { it.isNotBlank() }?.let { add("对你：${it.take(60)}") }
        }
        return parts.joinToString("；").ifBlank { persona.name.ifBlank { "他" } }
    }

    // ---------------------------------------------------------------- 按模型指示改库

    private data class Applied(
        val handledIds: Set<String>,
        val kept: Int,
        val merged: Int,
        val archived: Int,
        val forgotten: Int
    )

    /**
     * 执行模型给的指令。
     *
     * 顺序固定 raise → keep → merge → archive → forget：
     * 先加固/合并，再归档删库，避免「先删了后面还想合并它」的尴尬。
     * 同一条记忆只被处理一次（[Applied.handledIds]），模型重复列同一编号不会重复计数。
     */
    private suspend fun applyPlan(
        plan: ConsolidationPlan,
        memories: List<MemoryEntity>,
        promptMemories: List<MemoryEntity>,
        now: Long
    ): Applied {
        if (plan.isEmpty || memories.isEmpty()) {
            return Applied(emptySet(), 0, 0, 0, 0)
        }
        // 编号是「清单里的第几条」，必须和提示词里那份列表一一对应（见 selectPromptMemories）。
        val byIndex = promptMemories
            .mapIndexed { index, memory -> (index + 1).toString() to memory }
            .toMap()
        val byId = memories.associateBy { it.id }
        val handled = mutableSetOf<String>()
        var kept = 0
        var merged = 0
        var archived = 0
        var forgotten = 0

        // 模型抄回来的编号可能是 "3"、"第3条"、"#3"，也可能直接抄了真实 id：都认。
        fun resolve(raw: String): MemoryEntity? {
            val key = raw.trim()
                .trim('"', '\'')
                .removePrefix("#")
                .removePrefix("第")
                .removeSuffix("条")
                .removeSuffix("号")
                .trim()
            if (key.isEmpty()) return null
            return byIndex[key] ?: byId[key] ?: byIndex[key.filter { it.isDigit() }]
        }

        val ordered = plan.operations.sortedBy { opOrder(it.op) }
        ordered.forEach { op ->
            val targets = op.ids
                .mapNotNull { resolve(it) }
                .distinctBy { it.id }
                .filter { it.id !in handled }
            if (targets.isEmpty()) return@forEach

            when (op.op) {
                MemoryOp.KEEP -> targets.forEach { memory ->
                    val state = memory.toLifecycleState()
                    val strength = MemoryLifecycle.keepStrength(state, now)
                    runCatching {
                        // 对已归档的条目说 keep = 把它从归档里拿回上下文（见提示词里的说明）。
                        if (state.tier == MemoryTier.ARCHIVED) {
                            memoryDao.setLifecycle(
                                id = memory.id,
                                status = MemoryTier.ACTIVE.name,
                                strength = strength,
                                archivedAt = null,
                                now = now
                            )
                        } else {
                            memoryDao.touchStrength(memory.id, strength, now)
                        }
                    }
                    handled += memory.id
                    kept++
                }

                MemoryOp.RAISE -> targets.forEach { memory ->
                    val state = memory.toLifecycleState()
                    val importance = ImportanceScoring.clampImportance(op.importance ?: memory.importance)
                    val strength = MemoryLifecycle.keepStrength(state, now)
                    runCatching {
                        val newContent = (op.content ?: memory.content).take(MAX_CONTENT_CHARS)
                        memoryDao.applyConsolidation(
                            id = memory.id,
                            importance = importance,
                            strength = strength,
                            content = newContent,
                            now = now
                        )
                        // 正文被改写：倒排索引跟着重建，否则改之前的词元还在索引里。
                        if (newContent != memory.content) rebuildTermIndex(memory.id, newContent)
                        // 归档的记忆被提权，同样等于重新拿回来。
                        if (state.tier == MemoryTier.ARCHIVED) {
                            memoryDao.setLifecycle(
                                id = memory.id,
                                status = MemoryTier.ACTIVE.name,
                                strength = strength,
                                archivedAt = null,
                                now = now
                            )
                        }
                    }
                    handled += memory.id
                    // 提权 = 留下并加强，算在「留下」里（表里没有单独的 raise 计数）。
                    kept++
                }

                MemoryOp.MERGE -> {
                    if (targets.size < 2) return@forEach
                    val sorted = targets.sortedWith(compareBy({ it.createdAt }, { it.id }))
                    val target = sorted.first()
                    val rest = sorted.drop(1)
                    val content = (op.content ?: mergedContent(sorted)).take(MAX_CONTENT_CHARS)
                    val importance = ImportanceScoring.clampImportance(
                        op.importance ?: sorted.maxOf { it.importance }
                    )
                    runCatching {
                        memoryDao.applyConsolidation(
                            id = target.id,
                            importance = importance,
                            strength = MemoryLifecycle.keepStrength(target.toLifecycleState(), now),
                            content = content,
                            now = now
                        )
                        // 合并后要把结构字段带过来：DAO 的 applyConsolidation 只改正文/重要度/分量，
                        // tags 与 happenedAt 在这里补写，否则并一次就丢了「这件事什么时候发生的」。
                        val mergedTags = MemoryTags.sanitize(
                            sorted.flatMap { MemoryTags.decode(it.tags) }
                        )
                        val mergedHappened = sorted.firstNotNullOfOrNull { it.happenedAt?.takeIf { t -> t > 0L } }
                        val current = memoryDao.getById(target.id)
                        if (current != null && (mergedTags.isNotEmpty() || mergedHappened != null)) {
                            memoryDao.update(
                                current.copy(
                                    tags = MemoryTags.encode(mergedTags) ?: current.tags,
                                    happenedAt = mergedHappened ?: current.happenedAt
                                )
                            )
                        }
                        // 正文变了，索引必须重建（否则被并进来的内容搜不到）。
                        rebuildTermIndex(target.id, content)
                        rest.forEach { memory ->
                            // 被并掉的：归档（不是删除），万一并错了还能从「回忆」里找回来。
                            memoryDao.setLifecycle(
                                id = memory.id,
                                status = MemoryTier.ARCHIVED.name,
                                strength = 0.0,
                                archivedAt = now,
                                now = now
                            )
                        }
                    }
                    handled += target.id
                    rest.forEach { handled += it.id }
                    merged += rest.size
                }

                MemoryOp.ARCHIVE -> targets.forEach { memory ->
                    runCatching {
                        memoryDao.setLifecycle(
                            id = memory.id,
                            status = MemoryTier.ARCHIVED.name,
                            strength = 0.0,
                            archivedAt = now,
                            now = now
                        )
                    }
                    handled += memory.id
                    archived++
                }

                MemoryOp.FORGET -> targets.forEach { memory ->
                    // 忘掉就是真删：先删记忆再删索引（顺序理由见 MemoryRepository.delete）。
                    runCatching { memoryDao.deleteById(memory.id) }
                    runCatching { memoryTermDao.deleteByMemory(memory.id) }
                    handled += memory.id
                    forgotten++
                }
            }
        }
        return Applied(handled, kept, merged, archived, forgotten)
    }

    /** 模型没提到的记忆：交给时间自己处理（变淡 → 沉底 → 归档 → 很久以后忘掉）。 */
    private suspend fun applyDecay(
        memories: List<MemoryEntity>,
        handledIds: Set<String>,
        retention: Int,
        now: Long
    ): DecayOutcome {
        var archived = 0
        var forgotten = 0
        // 「记性」只改淡下去的速度，不改阈值（规格 §三；与 iOS `persona.memoryDecayFactor` 同源）。
        val retentionFactor = MemoryLifecycle.retentionFactor(retention)
        memories.forEach { memory ->
            if (memory.id in handledIds) return@forEach
            val update = MemoryLifecycle.dailyUpdate(memory.toLifecycleState(), now, retentionFactor)
            when (val action = update.action) {
                is LifecycleAction.Keep -> runCatching {
                    memoryDao.touchStrength(memory.id, update.strength, now)
                }

                is LifecycleAction.Demote -> {
                    val toArchive = action.to == MemoryTier.ARCHIVED
                    runCatching {
                        memoryDao.setLifecycle(
                            id = memory.id,
                            status = action.to.name,
                            strength = update.strength,
                            archivedAt = if (toArchive) now else null,
                            now = now
                        )
                    }
                    if (toArchive) archived++
                }

                is LifecycleAction.Promote -> runCatching {
                    memoryDao.setLifecycle(
                        id = memory.id,
                        status = MemoryTier.ACTIVE.name,
                        strength = update.strength,
                        archivedAt = null,
                        now = now
                    )
                }

                is LifecycleAction.Forget -> {
                    runCatching { memoryDao.deleteById(memory.id) }
                    runCatching { memoryTermDao.deleteByMemory(memory.id) }
                    forgotten++
                }
            }
        }
        return DecayOutcome(archived = archived, forgotten = forgotten)
    }

    private data class DecayOutcome(val archived: Int, val forgotten: Int)

    // ---------------------------------------------------------------- 小工具

    private fun opOrder(op: MemoryOp): Int = when (op) {
        MemoryOp.RAISE -> 0
        MemoryOp.KEEP -> 1
        MemoryOp.MERGE -> 2
        MemoryOp.ARCHIVE -> 3
        MemoryOp.FORGET -> 4
    }

    /** 合并后的正文：保留最老那条的原文，再把其余的不同部分接上去，总长受 [MAX_CONTENT_CHARS] 限制。 */
    private fun mergedContent(sorted: List<MemoryEntity>): String {
        val base = sorted.first().content.trim()
        val extra = sorted.drop(1)
            .map { it.content.trim() }
            .filter { it.isNotBlank() && !base.contains(it) }
        val combined = (listOf(base) + extra).joinToString("；")
        return combined.take(MAX_CONTENT_CHARS)
    }

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
        // 抽屉决定衰减速度：关于对方的事实最抗时间，共同经历最快。
        drawer = drawerOf(this)
    )

    /** 重建一条记忆的倒排索引：正文改过就先删后写，避免留下查得到的旧词元。 */
    private suspend fun rebuildTermIndex(memoryId: String, content: String) {
        runCatching { memoryTermDao.deleteByMemory(memoryId) }
        val terms = ChineseTerms.terms(content)
            .map { MemoryTermEntity(memoryId = memoryId, term = it.term, weight = it.weight) }
        if (terms.isNotEmpty()) runCatching { memoryTermDao.insertAll(terms) }
        // 词元与向量是同一份正文的两种索引，别只更新一半。
        runCatching { memoryVectorIndex.upsert(memoryId, content) }
    }

    private fun drawerOf(memory: MemoryEntity): MemoryDrawer =
        runCatching { MemoryDrawer.valueOf(memory.drawer?.trim()?.uppercase().orEmpty()) }
            // 旧记忆没有 drawer 字段时按 category 推断（和 MemoryRepository 的规则一致）。
            .getOrElse { MemoryScreening.drawerOf(memory.category.name) }

    private fun ageText(memory: MemoryEntity, now: Long): String = ageText(
        at = if (memory.lastUsedAt > 0L) memory.lastUsedAt else memory.createdAt,
        now = now
    )

    private fun ageText(at: Long, now: Long): String {
        if (at <= 0L) return "时间不明"
        val days = ((now - at).coerceAtLeast(0L)) / MemoryLifecycle.DAY_MILLIS
        return when {
            days <= 0L -> "今天动过"
            days == 1L -> "1 天前动过"
            days < 30L -> "$days 天前动过"
            else -> "${days / 30} 个月前动过"
        }
    }

    private fun formatTime(now: Long): String = runCatching {
        val time = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.systemDefault())
        time.format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", java.util.Locale.CHINA)
        )
    }.getOrDefault("")

    /** 状态用中文讲给模型听，别把 ACTIVE/ARCHIVED 这种数据库值丢进提示词。 */
    private fun tierLabel(tier: MemoryTier): String = when (tier) {
        MemoryTier.ACTIVE -> "还在用"
        MemoryTier.DORMANT -> "已经沉下去"
        MemoryTier.ARCHIVED -> "已归档"
    }

    private fun defaultSummary(kept: Int, merged: Int, archived: Int, forgotten: Int): String {
        val parts = buildList {
            if (kept > 0) add("留下 $kept 条")
            if (merged > 0) add("并起 $merged 条")
            if (archived > 0) add("挪出去 $archived 条")
            if (forgotten > 0) add("忘掉 $forgotten 条")
        }
        return if (parts.isEmpty()) "这次没怎么动，让它自己慢慢变淡" else parts.joinToString("，")
    }

    private suspend fun configIdOf(persona: PersonaEntity): String? =
        persona.defaultApiConfigId
            ?: runCatching { sessionDao.latestForPersona(persona.id)?.apiConfigId }.getOrNull()

    private companion object {
        /** 一次整理最多看多少条记忆（多的留给下一次）。 */
        const val MAX_MEMORIES_PER_RUN = 300

        /** 塞进提示词的清单条数上限：再多模型会开始乱抄编号。 */
        const val MAX_PROMPT_MEMORIES = 90

        /** 每个抽屉在清单里最多占多少条：保证 USER 抽屉的老事实一定看得到。 */
        const val MAX_PROMPT_PER_DRAWER = 30

        /** 清单里每条正文的截断长度：够模型判断，又不至于把上下文塞满。 */
        const val MAX_LINE_CONTENT_CHARS = 120

        /** 最近聊天摘要取几条。 */
        const val MAX_RECENT_MESSAGES = 8

        /** 浏览 digest 截断长度。 */
        const val MAX_BROWSE_DIGEST_CHARS = 600

        /** 合并后的正文上限。 */
        const val MAX_CONTENT_CHARS = 800
    }
}
