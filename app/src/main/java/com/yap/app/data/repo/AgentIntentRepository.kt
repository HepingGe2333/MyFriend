package com.yap.app.data.repo

import com.yap.app.data.local.AgentIntentDao
import com.yap.app.data.local.AgentIntentEntity
import com.yap.app.data.local.AgentIntentKind
import com.yap.app.data.local.AgentIntentStatus
import com.yap.app.data.local.AgentTickDao
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.ProactiveKind
import com.yap.app.data.local.ProactiveRuleDao
import com.yap.app.domain.AgentWindow
import com.yap.app.core.AgentCoreClient
import com.yap.app.domain.SleepPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 「他想什么时候找你」这件事的唯一真相来源。
 *
 * 规则（[com.yap.app.data.local.ProactiveRuleEntity]）只是节奏偏好，
 * 到点后会在这里变成一条带**时间窗口**的意向；发不发、几点发，由循环在窗口里自己决定。
 * 这样他就不会像闹钟一样每次都在 22:30:00 出现。
 */
@Singleton
class AgentIntentRepository @Inject constructor(
    private val intentDao: AgentIntentDao,
    private val tickDao: AgentTickDao,
    private val ruleDao: ProactiveRuleDao,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    /** agent 决策（每日主动上限的兜底换算）：默认进程内，可切 agentd，失败自动回退。 */
    private val agentCore: AgentCoreClient
) {

    private companion object {
        /** 规则到点后，意向窗口从这里开始往后散开的最大分钟数。 */
        const val SCATTER_MAX_MINUTES = 90

        /** 窗口至少留这么久，免得「现在就可以发」变成机械的立刻发。 */
        const val WINDOW_MIN_MINUTES = 30
        const val WINDOW_MAX_MINUTES = 120

        /** 今天就是不想说话的概率。 */
        const val SKIP_CHANCE = 0.18

        /** 两条主动消息之间的最小间隔。 */
        const val MIN_GAP_MS = 90 * 60_000L

        /** 追问：多久之后、多大几率、窗口多长。 */
        const val FOLLOW_UP_MIN_MINUTES = 25
        const val FOLLOW_UP_MAX_MINUTES = 70
        const val FOLLOW_UP_WINDOW_MINUTES = 20
        const val FOLLOW_UP_CHANCE = 0.45

        /** 已发但对方一直没回，之后的意向窗口往后推的分钟数。 */
        const val IGNORED_PUSH_BACK_MINUTES = 180
    }

    // ---------------------------------------------------------------- 读

    fun observeIntents(personaId: String, limit: Int = 20): Flow<List<AgentIntentEntity>> =
        intentDao.observeRecent(personaId, limit)

    fun observeTicks(personaId: String, limit: Int = 30) = tickDao.observeRecent(personaId, limit)

    fun observeLatestTicks(limit: Int = 30) = tickDao.observeLatest(limit)

    suspend fun pending(personaId: String): List<AgentIntentEntity> = intentDao.pendingForPersona(personaId)

    suspend fun pendingCount(personaId: String): Int = intentDao.pendingCount(personaId)

    /** 窗口已经打开（或已经过期但还没发）的意向。 */
    suspend fun due(now: Long): List<AgentIntentEntity> = intentDao.dueAt(now)

    suspend fun expireStale(now: Long): Int = intentDao.expireOlderThan(now)

    // ---------------------------------------------------------------- 写

    /**
     * 同一件事只想留一个念头。
     *
     * 他有时会在几次思考里反复想到同一句话；旧版本会把这些都插进来，列表里就成了一排一样的。
     * 这里按「同一个人格 + 同一个动作 + 同一句备注」归并，只留最早的那条，其余标成已放弃。
     */
    suspend fun collapseDuplicates(): Int {
        val pending = runCatching { intentDao.pendingWithNote() }.getOrDefault(emptyList())
        if (pending.size < 2) return 0
        var dropped = 0
        pending
            .groupBy { Triple(it.personaId, it.kind, it.note.trim()) }
            .forEach { (_, group) ->
                group.sortedBy { it.createdAt }.drop(1).forEach { extra ->
                    runCatching {
                        intentDao.mark(extra.id, AgentIntentStatus.DROPPED, null, "重复的念头，已经并成一条")
                    }
                    dropped++
                }
            }
        return dropped
    }

    suspend fun plan(
        personaId: String,
        window: AgentWindow,
        kind: AgentIntentKind = AgentIntentKind.REACH_OUT,
        note: String = "",
        hint: String = "",
        ruleId: String? = null,
        sessionId: String? = null,
        now: Long = System.currentTimeMillis()
    ): String {
        val earliest = window.earliestAt.coerceAtLeast(now)
        val latest = window.latestAt.coerceAtLeast(earliest + 15 * 60_000L)
        val cleanNote = note.take(200)
        // 同一件事只想留一个念头：重复想起同一句话时，只把窗口往后挪，不再堆一条。
        if (cleanNote.isNotBlank()) {
            val existing = runCatching {
                intentDao.pendingForPersona(personaId)
                    .firstOrNull { it.kind == kind && it.note.trim() == cleanNote.trim() && it.ruleId == ruleId }
            }.getOrNull()
            if (existing != null) {
                runCatching {
                    intentDao.update(
                        existing.copy(
                            earliestAt = earliest,
                            latestAt = latest,
                            hint = hint.take(600),
                            sessionId = sessionId ?: existing.sessionId
                        )
                    )
                }
                return existing.id
            }
        }
        val id = UUID.randomUUID().toString()
        intentDao.insert(
            AgentIntentEntity(
                id = id,
                personaId = personaId,
                sessionId = sessionId,
                kind = kind,
                earliestAt = earliest,
                latestAt = latest,
                note = cleanNote,
                hint = hint.take(600),
                status = AgentIntentStatus.PENDING,
                ruleId = ruleId,
                createdAt = now
            )
        )
        return id
    }

    suspend fun markSent(id: String, at: Long = System.currentTimeMillis()) {
        intentDao.mark(id, AgentIntentStatus.SENT, at, null)
    }

    suspend fun markDropped(id: String, reason: String) {
        intentDao.mark(id, AgentIntentStatus.DROPPED, null, reason.take(200))
    }

    suspend fun deleteForPersona(personaId: String) {
        intentDao.deleteByPersona(personaId)
    }

    suspend fun wipeAll() {
        intentDao.deleteAll()
        tickDao.deleteAll()
    }

    // ---------------------------------------------------------------- 规则 → 意向

    /**
     * 把「到点的规则」变成意向。
     *
     * - 时间不再准点：在意向窗口里前后来回散，最多往后推 [SCATTER_MAX_MINUTES] 分钟；
     * - 有 [SKIP_CHANCE] 的概率今天就是不想说，直接把规则推到下一次；
     * - 最近主动过但一直没回音，窗口整体往后推，越推越淡。
     */
    suspend fun materializeDueRules(
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): Int {
        val due = runCatching { ruleDao.dueAt(now) }.getOrDefault(emptyList())
        if (due.isEmpty()) return 0
        var created = 0
        due.forEach { rule ->
            val persona = runCatching { personaDao.getById(rule.personaId) }.getOrNull()
            val next = nextTriggerFor(
                kind = rule.kind,
                timeOfDayMinutes = jitteredMinutes(rule.kind, rule.timeOfDayMinutes, rule.intervalMinutes, random),
                intervalMinutes = jitteredInterval(rule.kind, rule.intervalMinutes, random),
                from = now
            )
            val skip = rule.enabled && !rule.pending && random.nextDouble() < SKIP_CHANCE
            if (skip) {
                ruleDao.markTriggered(
                    id = rule.id,
                    nextTriggerAt = next,
                    at = now,
                    error = null,
                    now = System.currentTimeMillis()
                )
                return@forEach
            }
            if (rule.enabled && !rule.pending) {
                val earliest = now + random.nextLong(5, SCATTER_MAX_MINUTES.toLong()) * 60_000L
                val span = random.nextLong(
                    WINDOW_MIN_MINUTES.toLong(),
                    WINDOW_MAX_MINUTES.toLong()
                ) * 60_000L
                val pushed = ignoredPushBack(persona?.id ?: rule.personaId, now)
                val window = AgentWindow(
                    earliestAt = earliest + pushed,
                    latestAt = earliest + span + pushed,
                    note = rule.note,
                    hint = rule.hint
                )
                plan(
                    personaId = rule.personaId,
                    window = window,
                    kind = AgentIntentKind.REACH_OUT,
                    note = rule.note,
                    hint = rule.hint,
                    ruleId = rule.id,
                    sessionId = rule.sessionId,
                    now = now
                )
                created++
            }
            ruleDao.markTriggered(
                id = rule.id,
                nextTriggerAt = next,
                at = now,
                error = null,
                now = System.currentTimeMillis()
            )
        }
        return created
    }

    private fun jitteredMinutes(
        kind: ProactiveKind,
        timeOfDayMinutes: Int?,
        intervalMinutes: Int?,
        random: Random
    ): Int? {
        if (kind != ProactiveKind.DAILY) return timeOfDayMinutes
        val base = timeOfDayMinutes ?: 20 * 60
        // 每天浮动 ±25 分钟：人不会在同一个分钟数上出现。
        return (base + random.nextInt(-25, 26)).coerceIn(0, 1439)
    }

    private fun jitteredInterval(kind: ProactiveKind, intervalMinutes: Int?, random: Random): Int? {
        if (kind != ProactiveKind.INTERVAL) return intervalMinutes
        val base = (intervalMinutes ?: 180).coerceAtLeast(15)
        val factor = 0.75 + random.nextDouble() * 0.6 // 0.75x ~ 1.35x
        return (base * factor).toInt().coerceAtLeast(15)
    }

    /** 上次主动之后对方一直没说话：这次把意向窗口整体往后挪，越没人理越安静。 */
    private suspend fun ignoredPushBack(personaId: String, now: Long): Long {
        val lastSpoken = runCatching { tickDao.lastSpokenAt(personaId) }.getOrNull() ?: return 0L
        val lastUser = runCatching {
            sessionDao.getByPersona(personaId)
                .maxByOrNull { it.lastMessageAt ?: it.createdAt }
                ?.let { messageDao.latestUser(it.id)?.createdAt }
        }.getOrNull()
        val userReplied = lastUser != null && lastUser > lastSpoken
        if (userReplied) return 0L
        // 至少沉默了 4 小时才算「被忽略」，之后每次多推 3 小时。
        val silenceHours = (now - lastSpoken) / 3_600_000L
        if (silenceHours < 4) return 0L
        return IGNORED_PUSH_BACK_MINUTES * 60_000L
    }

    // ---------------------------------------------------------------- 克制

    /** 一天里已经主动说了几次（按人格的主动程度限流）。 */
    suspend fun spokenToday(personaId: String, now: Long): Int {
        val startOfDay = startOfDay(now)
        return runCatching { tickDao.spokenSince(personaId, startOfDay) }.getOrDefault(0)
    }

    suspend fun lastSpokenAt(personaId: String): Long? =
        runCatching { tickDao.lastSpokenAt(personaId) }.getOrNull()

    /**
     * 现在能不能主动开口：作息、频率、间隔、对话是否正热。
     *
     * 每日上限只有这一处判定。[limitOverride] 是给调用方（AgentLoop / 心跳）把自己已经算好的上限带进来的；
     * 不传就回落到人格的 `proactiveLevel`（1–5 → 每日 1–5 次）。
     * 1.9 起设置页不再有「每天最多主动几次」，上限是这个人自己的性格。
     */
    suspend fun canReachOut(
        personaId: String,
        now: Long,
        isConversationHot: Boolean,
        limitOverride: Int? = null
    ): ReachOutCheck {
        val persona = personaDao.getById(personaId) ?: return ReachOutCheck(false, "人格不存在")
        // 带了上限就用调用方算好的那个；没有就按人格的主动程度换算。
        // 不带心情的兜底换算：canReachOut 的调用方要么已经算好上限传进来，
        // 要么这里只按人格的主动程度换算（心情修正发生在 AgentLoop / 心跳里）。
        val limit = limitOverride ?: withContext(Dispatchers.IO) {
            agentCore.proactiveCap(persona.proactiveLevel, null)
        }
        if (limit <= 0) return ReachOutCheck(false, "他不想主动开口")
        val spoken = spokenToday(personaId, now)
        if (spoken >= limit) return ReachOutCheck(false, "今天已经主动 $spoken 次，够了")
        // 1.9：不睡觉的人任何时间都可以主动；睡觉的人按睡窗判定（默认 23:30–08:00）。
        if (persona.sleepEnabled) {
            val (sleep, wake) = SleepPolicy.window(persona.quietSleepMinutes, persona.quietWakeMinutes)
            if (SleepPolicy.inWindow(minuteOfDay(now), sleep, wake)) {
                return ReachOutCheck(false, "他这个点在睡觉")
            }
        }
        val last = lastSpokenAt(personaId)
        if (last != null && now - last < MIN_GAP_MS) {
            return ReachOutCheck(false, "刚说完没多久，再等等")
        }
        if (isConversationHot) return ReachOutCheck(false, "你们正聊着，不用他额外开口")
        return ReachOutCheck(true, null)
    }

    /**
     * 他刚主动说了一句、对方还没回：有一定概率过一会儿轻轻追一句。
     * 只排一次，不会连环追问。
     */
    suspend fun planFollowUp(
        personaId: String,
        sessionId: String?,
        sentAt: Long,
        random: Random = Random.Default
    ): String? {
        if (random.nextDouble() >= FOLLOW_UP_CHANCE) return null
        val pendingFollowUp = pending(personaId).any { it.kind == AgentIntentKind.FOLLOW_UP }
        if (pendingFollowUp) return null
        val start = sentAt + random.nextLong(
            FOLLOW_UP_MIN_MINUTES.toLong(),
            FOLLOW_UP_MAX_MINUTES.toLong()
        ) * 60_000L
        val window = AgentWindow(
            earliestAt = start,
            latestAt = start + FOLLOW_UP_WINDOW_MINUTES * 60_000L,
            note = "他刚才说的还没回音",
            hint = "不要催，不要问「在吗」，就像忽然又想起这件事，轻轻补一句"
        )
        return plan(
            personaId = personaId,
            window = window,
            kind = AgentIntentKind.FOLLOW_UP,
            note = window.note,
            hint = window.hint,
            sessionId = sessionId,
            now = sentAt
        )
    }

    /** 对方说话了：把还没发出去的追问作废（人已经回来了，不需要补）。 */
    suspend fun dropFollowUps(personaId: String) {
        pending(personaId)
            .filter { it.kind == AgentIntentKind.FOLLOW_UP }
            .forEach { markDropped(it.id, "对方已经回来了") }
    }

    /** 在窗口里挑一个「像人」的时刻回来发：偏向窗口前半段，但不会准点。 */
    fun pickMoment(intent: AgentIntentEntity, random: Random = Random.Default): Long {
        val span = (intent.latestAt - intent.earliestAt).coerceAtLeast(15 * 60_000L)
        val ratio = 0.15 + random.nextDouble() * 0.7
        return intent.earliestAt + (span * ratio).toLong()
    }

    /** 下一次大概什么时候：daily 按 HH:mm（已加过抖动），interval 按小时数（已加过抖动）。 */
    private fun nextTriggerFor(
        kind: ProactiveKind,
        timeOfDayMinutes: Int?,
        intervalMinutes: Int?,
        from: Long
    ): Long = when (kind) {
        ProactiveKind.DAILY -> {
            val minutes = timeOfDayMinutes ?: 20 * 60
            val zone = ZoneId.systemDefault()
            val nowZoned = Instant.ofEpochMilli(from).atZone(zone)
            val target = nowZoned.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(zone)
            val next = if (target.isAfter(nowZoned)) target else target.plusDays(1)
            next.toInstant().toEpochMilli()
        }
        ProactiveKind.INTERVAL -> from + (intervalMinutes ?: 180).coerceAtLeast(15) * 60_000L
    }

    private fun startOfDay(now: Long): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun minuteOfDay(now: Long): Int {
        val time: LocalTime = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.hour * 60 + time.minute
    }

    /** 会话是不是正热：他刚发过、对方刚回、或者对面正开着聊天页。 */
    suspend fun conversationHot(sessionId: String?, now: Long): Boolean {
        if (sessionId == null) return false
        val latest = runCatching { messageDao.latestInSession(sessionId) }.getOrNull() ?: return false
        val role = latest.role
        val age = now - latest.createdAt
        return when {
            role == MessageRole.USER && age < 5 * 60_000L -> true
            role == MessageRole.ASSISTANT && age < 60_000L -> true
            else -> false
        }
    }

    data class ReachOutCheck(val allowed: Boolean, val reason: String?)
}
