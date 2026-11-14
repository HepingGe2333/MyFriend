package com.myfriend.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.MessageStatus
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.ProactiveKind
import com.myfriend.app.data.local.ProactiveRuleDao
import com.myfriend.app.data.local.ProactiveRuleEntity
import com.myfriend.app.data.local.ProactiveSource
import com.myfriend.app.domain.MessageWindows
import com.myfriend.app.domain.LlmLane
import com.myfriend.app.domain.ProactiveProposal
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.WireMessage
import com.myfriend.app.network.inferenceParams
import com.myfriend.app.util.ProactiveNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主动唤醒规则：到点由 WorkManager 唤醒，用该人格绑定的 API 生成一条真实消息，
 * 落库为助手消息并弹出通知，用户点开就能接着聊。
 */
@Singleton
class ProactiveRepository @Inject constructor(
    private val ruleDao: ProactiveRuleDao,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val memoryRepository: MemoryRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val chatRepository: ChatRepository,
    private val registry: AiProviderRegistry,
    private val personaCardRepository: PersonaCardRepository,
    private val moodRepository: MoodRepository,
    private val idleBrowsing: IdleBrowsing,
    private val scheduler: ProactiveScheduler,
    private val database: MyFriendDatabase,
    @ApplicationContext private val context: Context
) {

    fun observeAll(): Flow<List<ProactiveRuleEntity>> = ruleDao.observeAll()
    fun observeByPersona(personaId: String): Flow<List<ProactiveRuleEntity>> = ruleDao.observeByPersona(personaId)

    suspend fun get(id: String): ProactiveRuleEntity? = ruleDao.getById(id)

    /** 到点时间：daily 取下一个 HH:mm，interval 取 now + N 小时。 */
    fun computeNextTrigger(
        kind: ProactiveKind,
        timeOfDayMinutes: Int?,
        intervalMinutes: Int?,
        from: Long = System.currentTimeMillis()
    ): Long = when (kind) {
        ProactiveKind.DAILY -> {
            val minutes = timeOfDayMinutes ?: 20 * 60
            val zone = ZoneId.systemDefault()
            val now = Instant.ofEpochMilli(from).atZone(zone)
            val target = now.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(zone)
            val next = if (target.isAfter(now)) target else target.plusDays(1)
            next.toInstant().toEpochMilli()
        }
        ProactiveKind.INTERVAL -> from + (intervalMinutes ?: 180).coerceAtLeast(15) * 60_000L
    }

    suspend fun createManual(
        personaId: String,
        sessionId: String?,
        kind: ProactiveKind,
        timeOfDayMinutes: Int?,
        intervalMinutes: Int?,
        hint: String,
        note: String
    ): Result<Unit> = runCatching {
        require(personaId.isNotBlank()) { "人格不存在" }
        val persona = personaDao.getById(personaId) ?: error("人格不存在")
        if (kind == ProactiveKind.DAILY) {
            requireNotNull(timeOfDayMinutes) { "请选择每天的时间" }
        } else {
            requireNotNull(intervalMinutes) { "请设置间隔小时数" }
            require(intervalMinutes in 15..(24 * 60)) { "间隔需在 1 到 24 小时之间" }
        }
        val now = System.currentTimeMillis()
        val next = computeNextTrigger(kind, timeOfDayMinutes, intervalMinutes, now)
        ruleDao.insert(
            ProactiveRuleEntity(
                id = UUID.randomUUID().toString(),
                personaId = persona.id,
                sessionId = sessionId,
                kind = kind,
                timeOfDayMinutes = timeOfDayMinutes,
                intervalMinutes = intervalMinutes,
                hint = hint.trim().take(600),
                note = note.trim().ifBlank { defaultNote(kind, timeOfDayMinutes, intervalMinutes) },
                enabled = true,
                pending = false,
                source = ProactiveSource.MANUAL,
                nextTriggerAt = next,
                lastTriggeredAt = null,
                lastError = null,
                createdAt = now,
                updatedAt = now
            )
        )
        scheduler.ensureScheduled()
    }

    /** AI 在对话里提议的节奏：直接生效（pending 只当「还没提示过」的标记）。 */
    suspend fun createFromProposal(
        personaId: String,
        sessionId: String?,
        proposal: ProactiveProposal
    ): Result<String> = runCatching {
        val persona = personaDao.getById(personaId) ?: error("人格不存在")
        val now = System.currentTimeMillis()
        // 同样的节奏他已经安排过一次了：更新那条，别再堆一条一模一样的。
        val duplicate = runCatching { ruleDao.enabledRules() }.getOrDefault(emptyList()).firstOrNull {
            it.personaId == persona.id &&
                it.kind == proposal.kind &&
                it.timeOfDayMinutes == proposal.timeOfDayMinutes &&
                it.intervalMinutes == proposal.intervalMinutes
        }
        if (duplicate != null) {
            ruleDao.update(
                duplicate.copy(
                    enabled = true,
                    pending = true,
                    note = proposal.note.trim().take(120).ifBlank { duplicate.note },
                    hint = proposal.hint.trim().take(600).ifBlank { duplicate.hint },
                    nextTriggerAt = computeNextTrigger(
                        proposal.kind,
                        proposal.timeOfDayMinutes,
                        proposal.intervalMinutes,
                        now
                    ),
                    lastError = null,
                    updatedAt = now
                )
            )
            scheduler.ensureScheduled()
            return@runCatching duplicate.id
        }
        val id = UUID.randomUUID().toString()
        ruleDao.insert(
            ProactiveRuleEntity(
                id = id,
                personaId = persona.id,
                sessionId = sessionId,
                kind = proposal.kind,
                timeOfDayMinutes = proposal.timeOfDayMinutes,
                intervalMinutes = proposal.intervalMinutes,
                hint = proposal.hint.trim().take(600),
                note = proposal.note.trim().take(120),
                // 1.3 起不再等用户确认：他自己安排的节奏直接生效。
                // pending 只留作「他刚安排、聊天页要提示一次」的标记，调度看的是 enabled。
                enabled = true,
                pending = true,
                source = ProactiveSource.AI,
                nextTriggerAt = computeNextTrigger(
                    proposal.kind,
                    proposal.timeOfDayMinutes,
                    proposal.intervalMinutes,
                    now
                ),
                lastTriggeredAt = null,
                lastError = null,
                createdAt = now,
                updatedAt = now
            )
        )
        scheduler.ensureScheduled()
        id
    }

    suspend fun confirm(id: String): Result<Unit> = runCatching {
        val rule = ruleDao.getById(id) ?: error("规则不存在")
        val now = System.currentTimeMillis()
        val next = computeNextTrigger(rule.kind, rule.timeOfDayMinutes, rule.intervalMinutes, now)
        ruleDao.setEnabled(id, true, next, now)
        scheduler.ensureScheduled()
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> = runCatching {
        val rule = ruleDao.getById(id) ?: error("规则不存在")
        val now = System.currentTimeMillis()
        val next = if (enabled) {
            computeNextTrigger(rule.kind, rule.timeOfDayMinutes, rule.intervalMinutes, now)
        } else {
            rule.nextTriggerAt
        }
        ruleDao.setEnabled(id, enabled, next, now)
        scheduler.ensureScheduled()
    }

    suspend fun delete(id: String) {
        ruleDao.deleteById(id)
        scheduler.ensureScheduled()
    }

    suspend fun deleteForPersona(personaId: String) {
        ruleDao.deleteByPersona(personaId)
        scheduler.ensureScheduled()
    }

    /** 删除全部数据时调用：规则与排期一起清掉。 */
    suspend fun wipeAll() {
        ruleDao.deleteAll()
        scheduler.cancelAll()
    }

    suspend fun pendingForPersona(personaId: String): List<ProactiveRuleEntity> = ruleDao.pendingForPersona(personaId)

    /** 立即触发一次（只生成消息，不改变规则的排期），用于「试一次」与排期前的确认。 */
    suspend fun triggerNow(ruleId: String): Result<String> = runCatching {
        val rule = ruleDao.getById(ruleId) ?: error("规则不存在")
        fire(rule, System.currentTimeMillis())
        rule.note
    }

    /** 由 Worker 调用：把所有到点的规则跑一遍。 */
    suspend fun runDue(now: Long = System.currentTimeMillis()): Int {
        val due = runCatching { ruleDao.dueAt(now) }.getOrDefault(emptyList())
        var fired = 0
        due.forEach { rule ->
            val error = runCatching { fire(rule, now) }.exceptionOrNull()
            // 按他自己定的作息在睡觉：不算失败，照常排下一次，也不打扰对方。
            val asleep = error is AsleepException
            val next = when {
                error == null || asleep -> computeNextTrigger(rule.kind, rule.timeOfDayMinutes, rule.intervalMinutes, now)
                else -> now + RETRY_DELAY_MS
            }
            ruleDao.markTriggered(
                id = rule.id,
                nextTriggerAt = next,
                at = now,
                error = if (asleep) null else error?.message?.take(300),
                now = System.currentTimeMillis()
            )
            if (error == null) fired++
        }
        return fired
    }

    /**
     * 没人在的时候让他自己上网看看。
     * 只对「有主动唤醒规则的人格」做，避免给从没开启过主动消息的人偷偷跑网络。
     */
    suspend fun browseIdlePersonas(now: Long = System.currentTimeMillis()): Int {
        val personaIds = runCatching {
            ruleDao.enabledRules().map { it.personaId }.toSet()
        }.getOrDefault(emptySet())
        if (personaIds.isEmpty()) return 0
        var browsed = 0
        personaIds.forEach { personaId ->
            val done = runCatching {
                if (!idleBrowsing.shouldBrowseNow(personaId, now)) return@runCatching false
                idleBrowsing.browseOnce(personaId, now).isSuccess
            }.getOrDefault(false)
            if (done) browsed++
        }
        return browsed
    }

    private suspend fun fire(rule: ProactiveRuleEntity, now: Long) {
        val persona = personaDao.getById(rule.personaId) ?: error("人格已不存在")
        if (personaCardRepository.isAsleep(persona, minuteOfDay(now))) throw AsleepException()
        val session = rule.sessionId?.let { sessionDao.getById(it) }
            ?: chatRepository.getOrCreateForPersona(persona.id)
        val configId = session.apiConfigId ?: persona.defaultApiConfigId ?: error("这个会话还没有 API 配置")
        val resolved = apiConfigRepository.resolve(configId) ?: error("API 配置已不存在")

        // 这一轮模型会读到会话里的历史，所以用户消息此刻算「已读」。
        runCatching { messageDao.markUserMessagesRead(session.id, now) }
        val messages = buildProactiveMessages(session, persona, rule)
        val raw = registry.adapterFor(resolved.config.provider).complete(
            config = resolved.config,
            apiKey = resolved.apiKey,
            model = session.model.ifBlank { resolved.config.model },
            messages = messages,
            params = resolved.config.inferenceParams().copy(temperature = resolved.config.temperature ?: 0.85),
            // 这是后台主动开口：走后台名额，不能占用户聊天的保底位。
            kind = com.myfriend.app.domain.UsageKind.CHAT,
            lane = LlmLane.BACKGROUND,
            sessionId = session.id,
            personaId = persona.id
        )
        val text = ProactiveProtocol.extract(raw).first.trim()
        if (text.isBlank()) error("模型没有返回内容")

        val created = System.currentTimeMillis()
        val stamp = if (created <= now) now + 1 else created
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
            model = session.model.ifBlank { resolved.config.model },
            edited = false,
            attachmentsJson = null
        )
        database.withTransaction {
            messageDao.insert(message)
            val fresh = sessionDao.getById(session.id)
            if (fresh != null) {
                sessionDao.update(
                    fresh.copy(
                        lastMessagePreview = text.replace('\n', ' ').trim().take(100),
                        lastMessageAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            if (chatRepository.isSessionOpen(session.id)) {
                sessionDao.markRead(session.id)
            } else {
                sessionDao.incrementUnread(session.id)
            }
        }
        ProactiveNotifier.notify(context, persona, session.id, text)
    }

    private suspend fun buildProactiveMessages(
        session: ChatSessionEntity,
        persona: PersonaEntity,
        rule: ProactiveRuleEntity
    ): List<WireMessage> {
        // 1.9：记忆内置开启，不再看开关。
        val memories = memoryRepository.getRelevant(persona.id)
        val digest = runCatching { idleBrowsing.latestDigest(persona.id) }.getOrNull()
        val instruction = buildString {
            append("现在是 ")
            append(ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
            append("，按之前和对方的约定，到了你主动开口的时间。")
            append("\n这件事的背景：")
            append(rule.note.ifBlank { "你希望主动关心对方" })
            append("\n开口时的指引：")
            append(rule.hint.ifBlank { "用你的方式自然地关心对方此刻的状态" })
            append("\n要求：只写这一条消息本身，像平时发消息一样，1-3 句，不要解释你在执行规则，不要使用列表，不要提及「主动唤醒」这个功能。")
            if (!digest.isNullOrBlank()) {
                append("\n\n你最近一个人待着的时候自己看到的：\n")
                append(digest.take(600))
                append("\n如果聊起来自然，可以顺口提一句；不自然就在这里放着，别硬讲。")
            }
        }
        val system = buildString {
            append(personaCardRepository.systemText(persona))
            append(moodRepository.promptLine(persona.id))
            personaCardRepository.sleepHint(persona)?.let { append("\n（").append(it).append("）") }
            if (memories.isNotEmpty()) append(memoryRepository.formatForPrompt(memories))
            append("\n\n---\n")
            append(instruction)
        }
        // 只取最近 40 行，挑出最后 10 句；主动唤醒不需要整段历史。
        val recentRows = messageDao.recentBySession(session.id, MessageWindows.WORKER_HISTORY_ROWS)
        val history = MessageWindows.lastN(recentRows, 10) { message ->
            (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) &&
                message.content.isNotBlank() &&
                message.status != MessageStatus.ERROR
        }.map { WireMessage(if (it.role == MessageRole.USER) "user" else "assistant", it.content) }
        return if (session.apiConfigId != null) {
            listOf(WireMessage("system", system)) + history
        } else {
            listOf(WireMessage("system", system))
        }
    }

    private fun defaultNote(kind: ProactiveKind, timeOfDayMinutes: Int?, intervalMinutes: Int?): String = when (kind) {
        ProactiveKind.DAILY -> "每天 ${ProactiveProtocol.formatTimeOfDay(timeOfDayMinutes ?: 0)} 主动问候"
        ProactiveKind.INTERVAL -> "每 ${(intervalMinutes ?: 120) / 60} 小时主动关心一次"
    }

    private fun minuteOfDay(now: Long): Int {
        val time = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneId.systemDefault())
        return time.hour * 60 + time.minute
    }

    private class AsleepException : Exception("这个点他在睡觉")

    private companion object {
        const val RETRY_DELAY_MS = 30 * 60 * 1000L
    }
}
