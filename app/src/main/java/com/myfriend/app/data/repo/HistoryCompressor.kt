package com.myfriend.app.data.repo

import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.domain.CompressionWriteGuard
import com.myfriend.app.domain.HistoryCompression
import com.myfriend.app.domain.SessionGenerationRegistry
import com.myfriend.app.domain.MsgView
import com.myfriend.app.domain.UsageKind
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.InferenceParams
import com.myfriend.app.network.WireMessage
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 一次历史压缩的结果。 */
enum class CompressionStatus {
    /** 真的收成了一段记忆，并且写回了会话。 */
    DONE,

    /** 这次不用压（还短、已经压过、同一时间已经在跑…）。 */
    SKIPPED,

    /** 没有可用的 API 配置（或模型名），只能等下次。 */
    NO_CONFIG,

    /** 模型写了东西，但没通过校验 —— 宁可当没发生。 */
    REFUSED,

    /** 调用失败（网络、额度、解析…）。都当成没做。 */
    FAILED,

    /** 调用之前就被取消了（清空上下文 / 删除会话）。 */
    CANCELLED,

    /** 模型写完了，但期间这段对话被清空 / 删除 / 改过，结果被丢弃（不写回）。 */
    DISCARDED
}

/**
 * 一次压缩做成了什么。
 *
 * @param status 结论
 * @param summary 写回会话的那段记忆（被拒绝 / 失败时为空）
 * @param compressedUpTo 这段摘要覆盖到哪条消息的时间
 * @param messageCount 这次被收进摘要的消息条数
 * @param note 中文说明，给日志与调试面板用
 */
data class CompressionResult(
    val status: CompressionStatus,
    val summary: String = "",
    val compressedUpTo: Long? = null,
    val messageCount: Int = 0,
    val note: String = ""
) {
    val done: Boolean get() = status == CompressionStatus.DONE
}

/**
 * 长会话的滚动记忆：把已经聊过去的那一段，收成他自己还记得住的一段话。
 *
 * ## 这是一次「用完即弃的分叉调用」
 *
 * 和 [MemoryReconsolidator]、[MemoryConsolidator] 一样：自己拼 system + user 直接调模型，
 * 没有会话、没有历史、没有工具、不写 messages 表。温度压到 0.2，输出只要一小段正文
 * （不超过 500 token），记账记在 [UsageKind.HISTORY] 上，所以在「用量与花费」里
 * 能看出来这是「他在整理自己的记性」，不混进聊天。
 *
 * ## 绝不阻塞聊天
 *
 * 聊天主路径只在回复落库之后调 [ensureAsync]，异步跑、不 await、不抛异常；
 * 预算不够、没配 API、模型抽风，都只是安静地什么都没发生。
 *
 * ## 绝不删消息
 *
 * 这里只写 `chat_sessions` 的三个字段（摘要正文、压到哪一条、什么时候压的），
 * 原始消息一条都不动：搜索、导出、分页看到的仍然是完整的聊天记录。
 */
@Singleton
class HistoryCompressor @Inject constructor(
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val personaDao: PersonaDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry,
    private val sessionGeneration: SessionGenerationRegistry
) {

    /** fire-and-forget 用的作用域：进程活着就一直活着，随应用一起结束。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一段对话同一时间只跑一次：免得连着两轮回复各触发一次、白烧一次额度。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * 正在被「清空上下文 / 删除会话」取消的那几次压缩。
     *
     * 取消的理由与生成收尾相同：压缩的最后一步是 `setHistorySummary`，如果用户在
     * 几秒的模型调用里清空了上下文，旧摘要会被写回来。真正保证「不写回」的是
     * 世代号（[CompressionWriteGuard]）；这个集合是**明确的取消点**：
     * 在调用模型之前就跳过一次，省掉一次无意义的额度。
     */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /**
     * 明确取消一个会话正在跑的压缩。
     *
     * 只对**真正在跑**的那一次生效：没有在跑的压缩时什么都不做（否则这个标记
     * 会把清空之后的第一轮正常压缩也吞掉）。
     */
    fun cancel(sessionId: String) {
        if (sessionId.isNotBlank() && inFlight.contains(sessionId)) cancelled.add(sessionId)
    }


    /**
     * 该压就压一次；不抛异常。
     *
     * @param sessionId 会话 id
     * @param now 时间基准（写「什么时候压过」用）
     */
    suspend fun maybeCompress(
        sessionId: String,
        now: Long = System.currentTimeMillis()
    ): CompressionResult {
        if (sessionId.isBlank()) {
            return CompressionResult(CompressionStatus.SKIPPED, note = "会话 id 是空的")
        }
        if (!inFlight.add(sessionId)) {
            return CompressionResult(CompressionStatus.SKIPPED, note = "这段对话已经在整理了")
        }
        return try {
            compress(sessionId, now)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // 任何意外都收在这里：压缩永远不是主线，失败就当没发生。
            CompressionResult(
                CompressionStatus.FAILED,
                note = "整理的时候出错了：${error.message ?: "未知错误"}".take(120)
            )
        } finally {
            inFlight.remove(sessionId)
            cancelled.remove(sessionId)
        }
    }

    /**
     * 启动时自检：把库里那些「不像记忆」的旧摘要清掉。
     *
     * 起因是一次真实的线上事故：1.7 早期版本把模型写回来的工具调用
     * （```` ```myfriend-tool …``` ````）当成回忆存进了 `historySummary`，
     * 于是之后每一轮对话都会把它当成「他还记得的事」塞进提示词。
     * 判据已经补上了，但**已经写进库里的脏数据不会自己消失**，
     * 所以每次启动顺手核一遍：不合规的就清空（连带清掉「压到哪一条」），
     * 下次聊到该压的时候会重新压一遍。
     *
     * @return 清掉了几条会话的摘要
     */
    suspend fun repairStaleSummaries(): Int {
        val sessions = runCatching { sessionDao.withHistorySummary() }.getOrDefault(emptyList())
        if (sessions.isEmpty()) return 0
        var cleared = 0
        sessions.forEach { session ->
            val summary = session.historySummary?.takeIf { it.isNotBlank() } ?: return@forEach
            if (HistoryCompression.isLegacySummaryHealthy(summary)) return@forEach
            runCatching { sessionDao.clearHistorySummary(session.id) }
                .onSuccess { cleared++ }
        }
        return cleared
    }

    /**
     * 一轮回复写完之后顺手叫一下：异步、不等、不抛。
     *
     * 调用方（[ChatRepository]）不需要 try/catch，也不会因为它慢而晚一毫秒回话。
     */
    fun ensureAsync(sessionId: String) {
        if (sessionId.isBlank()) return
        scope.launch {
            try {
                maybeCompress(sessionId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // 后台整理失败不影响任何事，安静结束。
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun compress(sessionId: String, now: Long): CompressionResult {
        val session = runCatching { sessionDao.getById(sessionId) }.getOrNull()
            ?: return CompressionResult(CompressionStatus.SKIPPED, note = "会话不存在")

        // 这一轮属于哪个世代、摘要边界停在哪：写回之前要再比一次。
        val generation = sessionGeneration.current(sessionId)
        val startSummaryUpTo = session.historySummarizedUpTo

        val total = runCatching { messageDao.countInSession(sessionId) }.getOrDefault(0)
        if (total <= HistoryCompression.DEFAULT_MAX_VERBATIM) {
            return CompressionResult(CompressionStatus.SKIPPED, note = "只有 $total 条，还不用收")
        }

        // 近处这一段只看窗口就够：压缩的结论只取决于「最近几条要逐字留」和「更早的够不够一批」。
        val window = runCatching {
            messageDao.recentBySession(sessionId, PLAN_WINDOW)
        }.getOrDefault(emptyList()).map { it.toView() }

        val plan = HistoryCompression.plan(window, session.historySummarizedUpTo)
        val keepFrom = plan.keepFrom
        if (!plan.shouldCompress || keepFrom == null) {
            return CompressionResult(CompressionStatus.SKIPPED, note = plan.reason)
        }

        // 真正要压的那一批：从摘要边界之后「最旧的几条」开始，保证摘要覆盖是连续的
        // ——不会跳过中间没见过的部分，也不会一次把整段旧历史塞给模型。
        val chunk = loadChunk(
            sessionId = sessionId,
            summaryUpTo = session.historySummarizedUpTo,
            before = keepFrom
        )
        if (chunk.isEmpty()) {
            return CompressionResult(CompressionStatus.SKIPPED, note = "没找到可以收的那一段")
        }

        val persona = runCatching { personaDao.getById(session.personaId) }.getOrNull()
        val configId = session.apiConfigId ?: persona?.defaultApiConfigId
            ?: return CompressionResult(CompressionStatus.NO_CONFIG, note = "还没给他配 API")
        val resolved = runCatching { apiConfigRepository.resolve(configId) }.getOrNull()
            ?: return CompressionResult(CompressionStatus.NO_CONFIG, note = "绑定的 API 配置已不存在")
        val model = session.model.ifBlank { resolved.config.model }.trim()
        if (model.isBlank()) {
            return CompressionResult(CompressionStatus.NO_CONFIG, note = "这个配置里没有模型名")
        }

        val previous = session.historySummary?.trim()?.takeIf { it.isNotBlank() }

        // 明确的取消点：清空 / 删除已经打过招呼了，就别再花一次模型调用。
        if (cancelled.contains(sessionId)) {
            return CompressionResult(CompressionStatus.CANCELLED, note = "这段对话刚被清空 / 删除，这次不收了")
        }

        // ---- 一次用完即弃的分叉调用：没有会话、没有工具、没有历史 ----
        val raw = try {
            registry.adapterFor(resolved.config.provider).complete(
                config = resolved.config,
                apiKey = resolved.apiKey,
                model = model,
                messages = listOf(
                    WireMessage("system", HistoryCompression.systemPrompt()),
                    WireMessage("user", HistoryCompression.summaryPrompt(previous, chunk))
                ),
                // 记忆不需要创造力：温度压低，输出只要一小段。
                params = InferenceParams(temperature = TEMPERATURE, maxOutputTokens = MAX_OUTPUT_TOKENS),
                kind = UsageKind.HISTORY,
                sessionId = sessionId
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return CompressionResult(
                CompressionStatus.FAILED,
                note = "整理的时候出错了：${error.message ?: "未知错误"}".take(120)
            )
        }

        // 先洗（剥代码块、清前缀），再判断：剥掉过东西的回复必须还剩下足够一段话，
        // 否则就只剩「嗯，这个我想记下来。」这种残句会被永久记成他的回忆。
        val summary = HistoryCompression.salvageSummary(raw)
        if (summary == null || !HistoryCompression.isSummaryAcceptable(summary, previous)) {
            return CompressionResult(
                CompressionStatus.REFUSED,
                messageCount = chunk.size,
                note = "写回来的东西不像一段记忆，这次不当真"
            )
        }

        val upTo = chunk.last().at
        // 写完之前再核一次：期间清空 / 删除 / 并发压缩过就丢弃，绝不让旧摘要回来。
        val freshSession = runCatching { sessionDao.getById(sessionId) }.getOrNull()
        val currentCount = runCatching { messageDao.countInSession(sessionId) }.getOrDefault(0)
        val decision = CompressionWriteGuard.decide(
            startGeneration = generation,
            currentGeneration = sessionGeneration.current(sessionId),
            sessionExists = freshSession != null,
            startSummaryUpTo = startSummaryUpTo,
            currentSummaryUpTo = freshSession?.historySummarizedUpTo,
            startMessageCount = total,
            currentMessageCount = currentCount
        )
        if (decision != CompressionWriteGuard.Decision.APPLY) {
            Log.i(
                TAG,
                "discard history summary for session=$sessionId: reason=$decision " +
                    "start=$total current=$currentCount"
            )
            return CompressionResult(
                CompressionStatus.DISCARDED,
                messageCount = chunk.size,
                note = "这段对话在整理期间变化了（$decision），这次结果丢弃"
            )
        }
        val applied = runCatching {
            sessionDao.setHistorySummary(sessionId, summary, upTo, now)
        }
        if (applied.isFailure) {
            return CompressionResult(
                CompressionStatus.FAILED,
                messageCount = chunk.size,
                note = "写回摘要时出错，当作没收过"
            )
        }
        return CompressionResult(
            status = CompressionStatus.DONE,
            summary = summary,
            compressedUpTo = upTo,
            messageCount = chunk.size,
            note = "更早的 ${chunk.size} 条已经收成一段记忆"
        )
    }

    /**
     * 取出「摘要边界之后、逐字保留段之前」最旧的一批消息。
     *
     * 难处在于 DAO 只提供两种读法：「从历史最旧处取 N 条」和「取最近 N 条」，
     * 没有「从摘要边界之后取」。所以分两种情况：
     *
     * 1. 从来没压过（没有边界）：从历史最旧的开始取一批，覆盖起点就是历史开头，天然连续；
     * 2. 压过：从最新往回看，直到看见「已经压过的那一条」为止 —— 这时窗口里第一条没被压的，
     *    就是边界之后紧接着的那条，覆盖一定连续（不会跳过中间没见过的部分）。
     *
     * 两种读法都有条数上限（[SCAN_CAP]），正常聊天根本到不了那里；
     * 只有长期没压过、积压很久时才会多读几次，且绝不会把整段历史一次性读进内存。
     */
    private suspend fun loadChunk(
        sessionId: String,
        summaryUpTo: Long?,
        before: Long
    ): List<MsgView> {
        if (summaryUpTo == null || summaryUpTo <= 0L) {
            return prefixChunk(sessionId, before, summaryUpTo = null)
        }
        tailChunkAfterBoundary(sessionId, summaryUpTo, before)?.let { return it }
        // 兜底：积压特别久、往回看够不到边界时，从最旧的取（边界若在前段里同样连续）。
        return prefixChunk(sessionId, before, summaryUpTo)
    }

    /** 从历史最旧处取一批，只要「边界之后」的那些。 */
    private suspend fun prefixChunk(
        sessionId: String,
        before: Long,
        summaryUpTo: Long?
    ): List<MsgView> {
        val rows = runCatching { messageDao.olderThan(sessionId, before, SCAN_CAP) }
            .getOrDefault(emptyList())
            .filter { it.createdAt < before }
        return rows
            .filter { summaryUpTo == null || it.createdAt > summaryUpTo }
            .take(HistoryCompression.DEFAULT_MAX_BATCH)
            .map { it.toView() }
    }

    /**
     * 从最新往回看，取出「边界之后最旧的一批」。
     *
     * @return 找到时返回那一批（可能是空列表：说明已经压到最新了）；
     *         往回看到上限还没够到边界时返回 null，交给调用方兜底。
     */
    private suspend fun tailChunkAfterBoundary(
        sessionId: String,
        summaryUpTo: Long,
        before: Long
    ): List<MsgView>? {
        var limit = PLAN_WINDOW
        while (limit <= SCAN_CAP) {
            val raw = runCatching { messageDao.recentBySession(sessionId, limit) }
                .getOrDefault(emptyList())
            // 「逐字保留段」里的消息这次不参与，先把它们剔掉再找边界。
            val rows = raw.asReversed().filter { it.createdAt < before }
            // 窗口里第一条还没被压过的位置。
            val start = rows.indexOfFirst { it.createdAt > summaryUpTo }
            if (start < 0) return emptyList()          // 这一段全都压过了，没有新的
            if (start > 0) {
                // 它前面那条已经压过了：边界落在窗口里，覆盖是连续的。
                val end = (start + HistoryCompression.DEFAULT_MAX_BATCH).coerceAtMost(rows.size)
                return rows.subList(start, end).map { it.toView() }
            }
            if (raw.size < limit) {
                // 整个历史都已经在这个窗口里：从开头起就是没压过的，也连续。
                return rows.take(HistoryCompression.DEFAULT_MAX_BATCH).map { it.toView() }
            }
            // 窗口里全都还没压过：往回看还没够到边界，把窗口放大再试。
            limit *= 4
        }
        return null
    }

    /** 模型偶尔会把「摘要：」、引号、Markdown 代码块一起带回来，这里剥掉。 */
    private fun MessageEntity.toView(): MsgView = MsgView(
        id = id,
        at = createdAt,
        fromUser = role == MessageRole.USER,
        text = content
    )

    private companion object {
        const val TAG = "HistoryCompressor"

        /** 记忆不需要创造力，但要稳定地说同一件事。 */
        const val TEMPERATURE = 0.2

        /** 摘要正文只要一小段：200 字上下，500 token 足够应付写超。 */
        const val MAX_OUTPUT_TOKENS = 500

        /** 判断「该不该压」时读最近的多少条：逐字保留 + 够一批 + 一点余量。 */
        const val PLAN_WINDOW = HistoryCompression.DEFAULT_MAX_VERBATIM +
            HistoryCompression.DEFAULT_MIN_TO_COMPRESS +
            HistoryCompression.DEFAULT_MAX_BATCH + 8

        /** 找摘要边界时最多扫这么多条（只有长期没压过、边界很远时才会扫到这里）。 */
        const val SCAN_CAP = 1_024
    }
}
