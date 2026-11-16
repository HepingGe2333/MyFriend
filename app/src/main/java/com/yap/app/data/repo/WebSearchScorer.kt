package com.yap.app.data.repo

import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MemoryCategory
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.ImportanceScoring
import com.yap.app.domain.MemoryDrawer
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.InferenceParams
import com.yap.app.network.WireMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 联网搜索的「重要性打分器」。
 *
 * 和每日整理共用 [ImportanceScoring.SHARED_CRITERIA] 这一套「值得记吗」的判据，
 * 所以「他会记住什么」在聊天、上网、整理三处是同一个标准，不会分裂成两个人格。
 *
 * ## 「随风叉、用完即弃」是什么意思
 *
 * 打分**不是**一次对话：这里自己拼一份 messages（[forkedMessages]）直接调模型，
 * 不建 session、不写 messages 表、不给工具、不读历史会话；拿到 JSON 就落库并丢掉请求上下文，
 * 界面上永远看不到这轮「打分对话」。它也**不会**自己去调工具 —— 接线在主线（ToolExecutor）那边，
 * 这里只提供 `score(...)` 和单测友好的纯解析。
 */
@Singleton
class WebSearchScorer @Inject constructor(
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val memoryRepository: MemoryRepository,
    private val registry: AiProviderRegistry
) {
    /**
     * 一次打分的结果。
     *
     * @param scored 是否真的问了模型（开关关着 / 没配置 / 没内容 / 出错时为 false）
     * @param important 模型判断值不值得记
     * @param memoryId 写进记忆的 id；没写（不值得 / 写失败 / 记忆开关关着）时为 null
     */
    data class ScoreResult(
        val scored: Boolean,
        val important: Boolean,
        val importance: Int,
        val takeaway: String,
        val drawer: MemoryDrawer,
        val memoryId: String? = null,
        val message: String = ""
    )

    /**
     * 后台打分：搜索本身不必等它。
     *
     * 打分要问一次模型，把它挡在搜索结果前面只会让「查資料」变慢；
     * 所以工具这边只把摘要丢进来，打完分落库完就算了，结果不影响这次工具调用。
     */
    fun scoreInBackground(personaId: String, query: String, hitsSummary: String) {
        if (hitsSummary.isBlank()) return
        backgroundScope.launch {
            val result = runCatching { score(personaId, query, hitsSummary) }.getOrNull()
            // 排查用：打分为什么没记（开关关了 / 没配置 / 模型说不重要）。
            android.util.Log.d(
                "WebSearchScorer",
                "score scored=${result?.scored} important=${result?.important} memoryId=${result?.memoryId} msg=${result?.message}"
            )
        }
    }

    /** 同一时间只允许一次打分，避免同一轮工具调用并发打两遍。 */
    private val scoreMutex = Mutex()

    /** 打分是「顺手做的事」，用自己的作用域，别挂在调用方的生命周期上。 */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 判断「他刚搜到的这些值不值得记住」，值得就顺手写进长期记忆。
     *
     * @param query 这轮实际用的搜索词
     * @param hitsSummary 搜索结果摘要（一两行一条就够，由调用方整理成文本）
     */
    suspend fun score(
        personaId: String,
        query: String,
        hitsSummary: String,
        now: Long = System.currentTimeMillis()
    ): ScoreResult = scoreMutex.withLock {
        try {
            scoreLocked(personaId, query, hitsSummary, now)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 打分失败不影响这次搜索本身：调用方拿到的就是「没记」。
            empty("打分时出错了：${error.message ?: "未知错误"}".take(120))
        }
    }

    // ---------------------------------------------------------------- 主流程

    private suspend fun scoreLocked(
        personaId: String,
        query: String,
        hitsSummary: String,
        now: Long
    ): ScoreResult {
        // 1.9：联网打分是内置行为，不再有开关。
        val trimmedQuery = query.trim()
        val summary = hitsSummary.trim()
        if (trimmedQuery.isBlank() || summary.isBlank()) return empty("没有可打分的内容")

        val persona = runCatching { personaDao.getById(personaId) }.getOrNull()
            ?: return empty("找不到这个人格，没打分")
        val configId = runCatching { configIdOf(persona) }.getOrNull()
        val resolved = configId?.let { runCatching { apiConfigRepository.resolve(it) }.getOrNull() }
            ?: return empty("还没有可用的 API 配置，没打分")
        val model = resolved.config.model

        // ---- 一次用完即弃的分叉调用：没有会话、没有工具、没有历史 ----
        val messages = forkedMessages(persona, trimmedQuery, summary)
        val raw = try {
            registry.adapterFor(resolved.config.provider).complete(
                config = resolved.config,
                apiKey = resolved.apiKey,
                model = model,
                messages = messages,
                // 判断类任务：温度压低到 0.2，输出只有一行 JSON，长度给足 300 就够。
                params = InferenceParams(temperature = 0.2, maxOutputTokens = 300),
                kind = com.yap.app.domain.UsageKind.SCORING,
                personaId = persona.id
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return empty("打分时出错了：${error.message ?: "未知错误"}".take(120))
        }
        // 到这里请求上下文已经用完即弃：只留 raw 解析出来的判断。
        val score = ImportanceScoring.parseSearchScore(raw)
            ?: return empty("打分结果看不懂，这次不记")

        if (!ImportanceScoring.shouldRemember(score)) {
            return ScoreResult(
                scored = true,
                important = score.important,
                importance = score.importance,
                takeaway = score.takeaway,
                drawer = score.drawer,
                memoryId = null,
                message = "这次搜到的东西不值得记"
            )
        }
        // 1.9：记忆内置开启，打分通过就直接写进记忆；不再有「只看不记」的分支。


        val content = score.takeaway.trim()
            .ifBlank { "他查了「${trimmedQuery.take(40)}」，看到一些以后可能用得上的事。" }
        val memoryId = runCatching {
            memoryRepository.addDetailed(
                personaId = persona.id,
                content = content.take(400),
                category = categoryOf(score.drawer),
                importance = ImportanceScoring.clampImportance(score.importance),
                pinned = false,
                drawer = score.drawer.name,
                // 来源写清楚：这条不是对方说的，是他自己上网看到的。
                sourceQuote = SOURCE_QUOTE,
                sourceSessionId = null
            )
        }.getOrNull()?.getOrNull()

        return ScoreResult(
            scored = true,
            important = true,
            importance = score.importance,
            takeaway = content.take(200),
            drawer = score.drawer,
            memoryId = memoryId,
            message = if (memoryId != null) "记住了一条上网看到的" else "想记但没记成"
        )
    }

    // ---------------------------------------------------------------- 分叉会话（用完即弃）

    /**
     * 拼出这次打分用的 messages：system = 重要性打分器提示词，user = 搜索词 + 结果摘要。
     *
     * **随风叉、用完即弃**：只有这两条消息，绝不带历史会话、绝不给工具、绝不进 session 表。
     * 结果只以 [ScoreResult] 的形式回到调用方，这轮「对话」不会在界面上留下任何痕迹。
     */
    private suspend fun forkedMessages(
        persona: PersonaEntity,
        query: String,
        hitsSummary: String
    ): List<WireMessage> {
        val summary = runCatching { personaSummaryOf(persona) }.getOrDefault(persona.name)
        val system = ImportanceScoring.searchScoringSystemPrompt(summary)
        val user = buildString {
            appendLine("他刚才搜的是：${query.take(MAX_QUERY_CHARS)}")
            appendLine()
            appendLine("搜到的内容：")
            appendLine(hitsSummary.take(MAX_HITS_SUMMARY_CHARS))
        }.trimEnd()
        return listOf(WireMessage("system", system), WireMessage("user", user))
    }

    /** 只取「他是谁」的几项：打分不需要完整人设。 */
    private suspend fun personaSummaryOf(persona: PersonaEntity): String {
        val parts = buildList {
            if (persona.name.isNotBlank()) add(persona.name)
            if (persona.description.isNotBlank()) add(persona.description.take(60))
        }
        return parts.joinToString("；").ifBlank { "他" }
    }

    // ---------------------------------------------------------------- 小工具

    private suspend fun configIdOf(persona: PersonaEntity): String? =
        persona.defaultApiConfigId
            ?: runCatching { sessionDao.latestForPersona(persona.id)?.apiConfigId }.getOrNull()

    private fun categoryOf(drawer: MemoryDrawer): MemoryCategory = when (drawer) {
        MemoryDrawer.EVENT -> MemoryCategory.EVENT
        MemoryDrawer.SELF -> MemoryCategory.OTHER
        MemoryDrawer.USER -> MemoryCategory.FACT
    }

    private fun empty(message: String): ScoreResult = ScoreResult(
        scored = false,
        important = false,
        importance = ImportanceScoring.MIN_IMPORTANCE,
        takeaway = "",
        drawer = MemoryDrawer.USER,
        memoryId = null,
        message = message
    )

    private companion object {
        const val SOURCE_QUOTE = "他自己上网看到的"
        const val MAX_QUERY_CHARS = 200
        const val MAX_HITS_SUMMARY_CHARS = 2000
    }
}
