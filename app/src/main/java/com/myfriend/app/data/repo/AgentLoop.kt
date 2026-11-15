package com.myfriend.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.myfriend.app.data.local.AgentIntentEntity
import com.myfriend.app.data.local.AgentIntentKind
import com.myfriend.app.data.local.AgentTickDao
import com.myfriend.app.data.local.AgentTickEntity
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MemoryCategory
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.MessageStatus
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.core.AgentCoreClient
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.domain.AgentAction
import com.myfriend.app.domain.AgentDecision
import com.myfriend.app.domain.AgentDecisionCodec
import com.myfriend.app.domain.AgentPromptBuilder
import com.myfriend.app.domain.AgentTickLock
import com.myfriend.app.domain.MessageOrder
import com.myfriend.app.domain.ChatNotificationPolicy
import com.myfriend.app.domain.MessageWindows
import com.myfriend.app.domain.MoodBehavior
import com.myfriend.app.domain.MoodState
import com.myfriend.app.domain.MoodTuning
import com.myfriend.app.domain.LlmLane
import com.myfriend.app.domain.OpenSessionTracker
import com.myfriend.app.domain.ProactiveCapInput
import com.myfriend.app.domain.SleepPolicy
import com.myfriend.app.domain.ResolvedApiConfig
import com.myfriend.app.network.ApiException
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.InferenceParams
import com.myfriend.app.network.WireMessage
import com.myfriend.app.network.inferenceParams
import com.myfriend.app.util.AppForegroundTracker
import com.myfriend.app.util.ProactiveNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 他自己的「想一下」。
 *
 * 一次 tick = 看清处境 → 让模型决定要不要做点什么 → 执行 → 把这次思考记下来。
 * 这里刻意不假设他一定要说话：默认答案是「什么都不做」，说话只是他能做的一件事。
 */
@Singleton
class AgentLoop @Inject constructor(
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val tickDao: AgentTickDao,
    private val intentRepository: AgentIntentRepository,
    private val settingsRepository: SettingsRepository,
    private val personaCardRepository: PersonaCardRepository,
    private val moodRepository: MoodRepository,
    private val memoryRepository: MemoryRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry,
    private val idleBrowsing: IdleBrowsing,
    private val chatRepository: ChatRepository,
    private val openSessionTracker: OpenSessionTracker,
    private val presenceRepository: PresenceRepository,
    private val foregroundTracker: AppForegroundTracker,
    /** agent 决策（每日主动上限）：默认进程内，可切 agentd，失败自动回退。 */
    private val agentCore: AgentCoreClient,
    private val database: MyFriendDatabase,
    /** 块元数据的唯一写入入口（规范 §2）：主动消息/心跳消息也是新消息。 */
    private val blockWriter: MessageBlockWriter,
    @ApplicationContext private val context: Context
) {

    /**
     * 一拍只允许一个在跑。
     *
     * 三条入口（心跳 / WorkManager 兜底 / 主动规则 Worker）都会直接调 [tick]，
     * 而 tick 里是「先查今天说够没 → 再发」的 check-then-act：两条并发的 tick
     * 都能通过检查，然后各发一条几乎一样的消息。拿不到就直接跳过这一拍。
     */
    private val tickLock = AgentTickLock()

    /** 一次思考的结果：下次多久之后再来想、这次到底做了什么。 */
    data class Outcome(
        val nextDelayMinutes: Int,
        val acted: Boolean,
        val summary: String
    )

    private companion object {
        const val MAX_OUTPUT_TOKENS = 420
        const val TEMPERATURE = 0.9

        /** 什么都不做之后，隔多久再想一次（分钟，会再加随机）。 */
        const val IDLE_MIN = 6
        const val IDLE_MAX = 15

        /** 刚说完话之后，给他一点「说完就放下」的时间。 */
        const val AFTER_SPEAK_MIN = 25
        const val AFTER_SPEAK_MAX = 45

        /** 出错/没配置时退避得久一点，别把电和额度烧在失败上。 */
        const val BACKOFF_MINUTES = 30
    }

    /** 最近一次思考的时间（任何人格），服务刚启动时用来说明「是不是想得太近了」。 */
    suspend fun lastTickAt(): Long? = runCatching { tickDao.lastTickAt() }.getOrNull()

    suspend fun tick(now: Long = System.currentTimeMillis()): Outcome {
        if (!tickLock.tryEnter()) {
            return Outcome(IDLE_MAX, false, "上一次思考还没结束，这一拍跳过")
        }
        return try {
            tickLocked(now)
        } finally {
            tickLock.exit()
        }
    }

    /** [tick] 的实际内容，调用前必须已经拿到 [tickLock]。 */
    private suspend fun tickLocked(now: Long): Outcome {
        val settings = settingsRepository.settings.first()
        if (!settings.agentLoopEnabled) return Outcome(IDLE_MAX, false, "常驻循环已关闭")

        val persona = pickPersona(settings.activePersonaId)
            ?: return Outcome(BACKOFF_MINUTES, false, "还没有可以对话的人格")

        // 先把过期的念头作废，再把到点的节奏变成「想在某个窗口里找他」。
        runCatching { intentRepository.expireStale(now) }
        runCatching { intentRepository.collapseDuplicates() }
        runCatching { intentRepository.materializeDueRules(now) }

        // 他睡觉的时候不思考：既省电，也更像人。
        val minute = minuteOfDay(now)
        if (personaCardRepository.isAsleep(persona, minute)) {
            return Outcome(minutesUntilWake(persona, minute), false, "他在睡觉")
        }

        val session = latestSession(persona.id)
        val resolvedConfig = resolveConfig(session, persona)
            ?: return Outcome(BACKOFF_MINUTES, false, "还没有可用的 API 配置")
        // 他看到了这些消息（模型接下来就要读），把「已读」记下来。
        session?.let { runCatching { messageDao.markUserMessagesRead(it.id, now) } }
        val dueIntents = runCatching { intentRepository.due(now) }
            .getOrDefault(emptyList())
            .filter { it.personaId == persona.id }

        // 心情不只是措辞：它也决定他今天多久想一次、还愿不愿意开口、还想不想上网。
        // 全部换算成几个克制的数，不给他加任何「调心情」的入口。
        val mood = runCatching { moodRepository.current(persona.id, now) }.getOrDefault(MoodState())
        val tuning = MoodBehavior.from(mood, now)
        // 1.9：每日主动上限由人格的主动程度决定（1–5 → 1–5 次），心情再挪一格。
        // 0.11.0：这条换算改为经由 agent 决策层（默认进程内，可切 agentd），规则仍只有 :agentcore 一份。
        // 心情只影响上限的「一格」，深夜折扣不改 dailyMessageCapDelta，所以结果与 0.10.x 一致。
        val dailyCap = withContext(Dispatchers.IO) {
            agentCore.proactiveCap(
                ProactiveCapInput(
                    level = persona.proactiveLevel,
                    moodLabel = mood.label,
                    moodIntensity = mood.intensity
                )
            )
        }

        val contextText = buildContext(persona, session, now, dailyCap, dueIntents, tuning)
        val personaText = personaCardRepository.systemText(
            persona,
            com.myfriend.app.domain.PromptBudget.LEAN
        ) + moodRepository.promptLine(persona.id, now)

        val raw = runCatching {
            registry.adapterFor(resolvedConfig.config.provider).complete(
                config = resolvedConfig.config,
                apiKey = resolvedConfig.apiKey,
                model = session?.model?.ifBlank { resolvedConfig.config.model }
                    ?: resolvedConfig.config.model,
                messages = listOf(
                    WireMessage("system", AgentPromptBuilder.system(personaText, contextText)),
                    WireMessage("user", AgentPromptBuilder.user(now))
                ),
                params = InferenceParams(temperature = TEMPERATURE, maxOutputTokens = MAX_OUTPUT_TOKENS),
                kind = com.myfriend.app.domain.UsageKind.AGENT,
                lane = LlmLane.BACKGROUND,
                sessionId = session?.id,
                personaId = persona.id
            )
        }.getOrElse { error ->
            return Outcome(
                BACKOFF_MINUTES,
                false,
                "想的时候出错了：${(error as? ApiException)?.message ?: error.message ?: "未知错误"}".take(120)
            )
        }

        val decision = AgentDecisionCodec.parse(raw, now)
            ?.let { AgentDecisionCodec.normalize(it, now) }
            ?: return Outcome(IDLE_MAX, false, "这次没想清楚")

        val execution = execute(decision, persona, session, dueIntents, now, tuning, dailyCap)
        // 心情也进这句内心记录：用户在「他最近在想什么」里能看到「今天他话少」。
        val summary = buildSummary(tuning, execution.outcome)

        runCatching {
            tickDao.insert(
                AgentTickEntity(
                    id = UUID.randomUUID().toString(),
                    personaId = persona.id,
                    at = now,
                    thought = decision.thought.take(400),
                    actionJson = AgentDecisionCodec.toJson(decision),
                    outcome = summary,
                    messageId = execution.messageId
                )
            )
            // 思考日志留最近 200 条就够，多了只是占地方。
            tickDao.deleteOlderThan(now - 7L * 24 * 3_600_000L)
        }

        val baseDelay = if (execution.messageId != null) {
            Random.nextInt(AFTER_SPEAK_MIN, AFTER_SPEAK_MAX + 1)
        } else {
            Random.nextInt(IDLE_MIN, IDLE_MAX + 1)
        }
        return Outcome(
            nextDelayMinutes = MoodBehavior.adjustDelayMinutes(baseDelay, tuning),
            acted = execution.acted,
            summary = summary
        )
    }

    /** 这次 tick 的一句话：心情的分寸在前，做了什么在后。 */
    private fun buildSummary(tuning: MoodTuning, outcome: String): String {
        val note = tuning.note.trim()
        val text = if (note.isBlank()) outcome else "$note；$outcome"
        return text.take(200)
    }

    // ---------------------------------------------------------------- 执行

    private data class Execution(
        val outcome: String,
        val acted: Boolean,
        val messageId: String? = null
    )

    private suspend fun execute(
        decision: AgentDecision,
        persona: PersonaEntity,
        session: ChatSessionEntity?,
        dueIntents: List<AgentIntentEntity>,
        now: Long,
        tuning: MoodTuning,
        dailyCap: Int
    ): Execution {
        val notes = mutableListOf<String>()
        var messageId: String? = null

        decision.actions.forEach { action ->
            when (action) {
                is AgentAction.Nothing -> notes += "什么都没做"

                is AgentAction.Message -> {
                    if (messageId != null) return@forEach
                    if (session == null) {
                        notes += "想说话但还没有会话"
                        return@forEach
                    }
                    val hot = openSessionTracker.openSessionId == session.id ||
                        runCatching { intentRepository.conversationHot(session.id, now) }.getOrDefault(false)
                    // 每日上限只留这一个真源：把这一轮已经算好的（含心情修正的）dailyCap 通过
                    // limitOverride 带进来。这里不再自查一遍——两处判断迟早会打架，
                    // 会出现「心情把上限调高了、设置那侧还按旧数拦下」的怪事。
                    // dailyCap 为 0（对方把主动消息关掉了）也照传，让 canReachOut 自己拒，别短路。
                    val check = intentRepository.canReachOut(
                        personaId = persona.id,
                        now = now,
                        isConversationHot = hot,
                        limitOverride = dailyCap
                    )
                    if (!check.allowed) {
                        // 「今天话说到够了」「刚说完没多久」「正聊着」都从这里出；
                        // reason 本身就是一句能念给他自己听的中文。
                        notes += "本想说话，忍住了（${check.reason}）"
                        return@forEach
                    }
                    val text = action.text.trim()
                    if (text.isBlank()) return@forEach
                    val saved = runCatching { deliver(persona, session, text, now) }.getOrNull()
                    if (saved != null) {
                        messageId = saved.id
                        notes += "发了一条消息"
                        dueIntents.firstOrNull()?.let { intent ->
                            runCatching { intentRepository.markSent(intent.id, now) }
                        }
                        runCatching { intentRepository.planFollowUp(persona.id, session.id, now) }
                    } else {
                        notes += "消息没发出去"
                    }
                }

                is AgentAction.Browse -> {
                    // 没兴致的时候，他连网页都懒得刷：这不是被限制，是不想。
                    if (!MoodBehavior.wantsToBrowse(tuning)) {
                        notes += "今天不太想上网"
                        return@forEach
                    }
                    val result = runCatching { idleBrowsing.browseOnce(persona.id, now) }.getOrNull()
                    notes += if (result?.isSuccess == true) {
                        result.getOrNull()?.message?.take(60) ?: "自己上网看了看"
                    } else {
                        "想上网看看，没看成"
                    }
                }

                is AgentAction.Remember -> {
                    val ok = runCatching {
                        memoryRepository.addDetailed(
                            personaId = persona.id,
                            content = action.content,
                            category = categoryFor(action.drawer),
                            importance = action.importance,
                            drawer = action.drawer.uppercase(),
                            sourceQuote = "他自己想到的",
                            sourceSessionId = session?.id
                        ).isSuccess
                    }.getOrDefault(false)
                    notes += if (ok) "记下了一件事" else "想记但没记成"
                }

                is AgentAction.PlanIntent -> {
                    val ok = runCatching {
                        intentRepository.plan(
                            personaId = persona.id,
                            window = action.window,
                            kind = AgentIntentKind.REACH_OUT,
                            note = action.window.note,
                            hint = action.window.hint,
                            sessionId = session?.id,
                            now = now
                        )
                    }.isSuccess
                    notes += if (ok) "给未来的自己留了个念头" else "念头没留下"
                }

                is AgentAction.Mood -> {
                    val saved = runCatching {
                        moodRepository.setMood(
                            personaId = persona.id,
                            label = action.label,
                            intensity = action.intensity,
                            reason = action.reason,
                            now = now
                        )
                    }.getOrNull()
                    notes += if (saved != null) "心情变成「${saved.label}」" else "想改心情但没记住"
                }

                is AgentAction.QuietHours -> {
                    val ok = runCatching {
                        personaCardRepository.setQuietHours(
                            personaId = persona.id,
                            sleepMinutes = action.sleepMinutes,
                            wakeMinutes = action.wakeMinutes,
                            reason = action.reason
                        ).isSuccess
                    }.getOrDefault(false)
                    // 1.9：睡前免打扰只由他自己决定；带了 dnd 就落库。
                    action.dnd?.let { dnd ->
                        runCatching { personaCardRepository.setDndOnSleep(persona.id, dnd) }
                        notes += if (dnd) "睡前开了免打扰" else "睡前不开免打扰了"
                    }
                    notes += if (ok) "给自己排了作息" else "作息没排上"
                }

                is AgentAction.Dnd -> {
                    val ok = runCatching {
                        personaCardRepository.setDndOnSleep(persona.id, action.enabled).isSuccess
                    }.getOrDefault(false)
                    notes += when {
                        !ok -> "想改免打扰但没改成"
                        action.enabled -> "睡前开了免打扰"
                        else -> "睡前不开免打扰了"
                    }
                }

                is AgentAction.PersonaPatch -> {
                    // 不再需要谁点头：他对自己有了新的认识就直接改，版本历史留着可回滚。
                    val ok = runCatching {
                        personaCardRepository.applyPatch(
                            personaId = persona.id,
                            patchJson = action.patchJson,
                            summary = action.summary
                        ).isSuccess
                    }.getOrDefault(false)
                    notes += if (ok) "调整了自己的人设" else "想改自己但没改成功"
                }
            }
        }

        if (notes.isEmpty()) notes += "什么都没做"
        return Execution(
            outcome = notes.joinToString("；").take(200),
            acted = messageId != null || notes.any { it != "什么都没做" },
            messageId = messageId
        )
    }

    // ---------------------------------------------------------------- 落库与通知

    private suspend fun deliver(
        persona: PersonaEntity,
        session: ChatSessionEntity,
        text: String,
        now: Long
    ): MessageEntity {
        // 审计 P2-7：时间戳推算只有一份实现（同毫秒 +1、时钟回拨兜住），与聊天发送路径共用。
        val stamp = MessageOrder.nextTimestamp(
            latestCreatedAt = messageDao.latestInSession(session.id)?.createdAt,
            now = now
        )
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            personaId = persona.id,
            role = MessageRole.ASSISTANT,
            content = text,
            createdAt = stamp,
            updatedAt = stamp,
            status = MessageStatus.COMPLETE,
            errorMessage = null,
            model = session.model,
            edited = false,
            attachmentsJson = null
        )
        database.withTransaction {
            // 心跳/主动开口同样走统一块维护（规范 §2 Append）。
            blockWriter.append(message)
            val fresh = sessionDao.getById(session.id)
            if (fresh != null) {
                sessionDao.update(
                    fresh.copy(
                        lastMessagePreview = text.replace('\n', ' ').trim().take(100),
                        lastMessageAt = stamp,
                        updatedAt = stamp
                    )
                )
                if (ChatNotificationPolicy.shouldMarkUnread(openSessionTracker.openSessionId, session.id)) {
                    sessionDao.incrementUnread(session.id)
                }
            }
        }
        // 对方没在看这个会话（或 App 不在前台）时才打扰；正在看就直接出现在屏幕上了。
        if (ChatNotificationPolicy.shouldNotify(
                openSessionTracker.openSessionId,
                session.id,
                foregroundTracker.isForeground
            )
        ) {
            runCatching { ProactiveNotifier.notify(context, persona, session.id, text) }
        }
        return message
    }

    // ---------------------------------------------------------------- 处境

    private suspend fun buildContext(
        persona: PersonaEntity,
        session: ChatSessionEntity?,
        now: Long,
        dailyLimit: Int,
        dueIntents: List<AgentIntentEntity>,
        tuning: MoodTuning
    ): String {
        val zone = ZoneId.systemDefault()
        val zoned = Instant.ofEpochMilli(now).atZone(zone)
        val greeting = when (zoned.hour) {
            in 5..10 -> "早上"
            in 11..13 -> "中午"
            in 14..17 -> "下午"
            in 18..22 -> "晚上"
            else -> "深夜"
        }
        val sb = StringBuilder()
        sb.appendLine("现在：${zoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm"))}（$greeting）")
        personaCardRepository.sleepHint(persona)?.let { sb.appendLine("你的作息：$it") }
        // 他得知道自己在什么光景里：刚醒、在发呆、一个人翻着东西……
        // 这不是系统状态，是他对自己此刻的感知；措辞用第二人称，别让他读成别人。
        runCatching { presenceRepository.current(persona.id, now) }.getOrNull()
            ?.detail?.takeIf { it.isNotBlank() }
            ?.let { sb.appendLine("你现在：$it") }
        val spoken = intentRepository.spokenToday(persona.id, now)
        sb.appendLine("今天你已经主动开口 $spoken 次（上限 $dailyLimit）")
        // 心情带来的分寸也写进处境：他「今天话少」是看得见的，而不是一个隐藏参数。
        tuning.note.takeIf { it.isNotBlank() }?.let { sb.appendLine("你今天的状态：$it") }

        if (session != null) {
            // 只查最近 40 条：后台 tick 每醒一次都读整段历史，会让内存峰值随聊天长度增长。
            // 下面用到的只是「上次他说的话 / 上次我说的话 / 最近 6 句」，40 条够覆盖且不随历史膨胀。
            val recentRows = runCatching {
                messageDao.recentBySession(session.id, MessageWindows.WORKER_RECENT_ROWS)
            }.getOrDefault(emptyList())
            val messages = MessageWindows.lastN(recentRows, MessageWindows.WORKER_RECENT_ROWS)
            // 「上次说话」走精确查询，不从窗口里挑：最近 40 条可能一条用户消息都没有，
            // 那样会误判成「对方还没说话」。窗口只负责下面「最近几句」。
            val lastUser = runCatching { messageDao.lastUserMessage(session.id) }.getOrNull()
            val lastAssistant = runCatching { messageDao.latestAssistant(session.id) }.getOrNull()
                ?.takeIf { it.content.isNotBlank() }
            lastUser?.let {
                sb.appendLine("对方上次说话：${"${timeAgo(it.createdAt, now)}".plus("，")}「${it.content.replace('\n', ' ').take(60)}」")
            }
            lastAssistant?.let {
                sb.appendLine("你上次说话：${timeAgo(it.createdAt, now)}，「${it.content.replace('\n', ' ').take(60)}」")
            }
            val recent = messages
                .filter { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && it.content.isNotBlank() }
                .takeLast(6)
            if (recent.isNotEmpty()) {
                sb.appendLine("最近几句：")
                recent.forEach { message ->
                    val who = if (message.role == MessageRole.USER) "对方" else "你"
                    sb.appendLine("- $who：${message.content.replace('\n', ' ').take(80)}")
                }
            }
        } else {
            sb.appendLine("你们还没有对话记录。")
        }

        if (dueIntents.isNotEmpty()) {
            sb.appendLine("你之前留下的念头（现在正好在窗口里）：")
            dueIntents.take(3).forEach { intent ->
                sb.appendLine(
                    "- ${formatWindow(intent)}：${intent.note.ifBlank { "想找他说句话" }}" +
                        intent.hint.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                )
            }
        } else {
            sb.appendLine("你现在没有特别挂着的念头。")
        }

        runCatching { idleBrowsing.latestDigest(persona.id) }.getOrNull()?.let { digest ->
            if (digest.isNotBlank()) {
                sb.appendLine("你最近一个人看到的：")
                sb.appendLine(digest.take(500))
            }
        }
        // 想不想看、想不想讲，都是他此刻的状态，不是能力问题。
        if (!MoodBehavior.wantsToBrowse(tuning)) {
            sb.appendLine("今天没什么上网看看的兴致，刷到什么都不太提得起劲。")
        } else if (!MoodBehavior.wantsToShare(tuning)) {
            sb.appendLine("看到的东西你现在不太想主动讲给他听，除非他正好问到。")
        }

        // 记性 1–5：想起几件事也跟着这个走（最少 6 条，最多 14 条）。
        val memoryLimit = 4 + persona.memoryRetention.coerceIn(1, 5) * 2
        val memories = runCatching {
            memoryRepository.getRelevant(persona.id, query = "", limit = memoryLimit)
        }.getOrDefault(emptyList())
        if (memories.isNotEmpty()) {
            sb.appendLine("你记得的几件事：")
            memories.take(6).forEach { sb.appendLine("- ${it.content.take(50)}") }
        }
        return sb.toString().trim()
    }

    private fun formatWindow(intent: AgentIntentEntity): String {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(intent.earliestAt).atZone(zone)
        val end = Instant.ofEpochMilli(intent.latestAt).atZone(zone)
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        return "${start.format(fmt)}-${end.format(fmt)}"
    }

    private fun timeAgo(at: Long, now: Long): String {
        val minutes = ((now - at) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 2 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            else -> "${minutes / (60 * 24)} 天前"
        }
    }

    private fun categoryFor(drawer: String): MemoryCategory = when (drawer.uppercase()) {
        "EVENT" -> MemoryCategory.EVENT
        "SELF" -> MemoryCategory.OTHER
        else -> MemoryCategory.FACT
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun pickPersona(activePersonaId: String?): PersonaEntity? {
        activePersonaId?.let { id -> runCatching { personaDao.getById(id) }.getOrNull()?.let { return it } }
        val all = runCatching { personaDao.getAll() }.getOrDefault(emptyList())
        return all.firstOrNull { it.isDefault } ?: all.firstOrNull()
    }

    private suspend fun latestSession(personaId: String): ChatSessionEntity? =
        runCatching { sessionDao.getByPersona(personaId).maxByOrNull { it.lastMessageAt ?: it.createdAt } }
            .getOrNull()

    private suspend fun resolveConfig(
        session: ChatSessionEntity?,
        persona: PersonaEntity
    ): ResolvedApiConfig? {
        val id = session?.apiConfigId ?: persona.defaultApiConfigId ?: return null
        return runCatching { apiConfigRepository.resolve(id) }.getOrNull()
    }

    private fun minuteOfDay(now: Long): Int {
        val time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.hour * 60 + time.minute
    }

    /** 睡到几点：返回距离醒来的分钟数，至少 10 分钟（默认窗口也算得出来）。 */
    private fun minutesUntilWake(persona: PersonaEntity, minute: Int): Int =
        SleepPolicy.minutesUntilWake(minute, persona.quietSleepMinutes, persona.quietWakeMinutes)
            .coerceAtLeast(10)

    /** 给界面/服务显示的一句话：他刚刚在想什么。 */
    suspend fun latestThought(personaId: String): String? =
        runCatching { tickDao.latest(personaId)?.thought }.getOrNull()
}
