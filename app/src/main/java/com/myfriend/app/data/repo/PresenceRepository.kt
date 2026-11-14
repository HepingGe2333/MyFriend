package com.myfriend.app.data.repo

import com.myfriend.app.data.local.AgentIntentDao
import com.myfriend.app.data.local.AgentTickDao
import com.myfriend.app.data.local.AgentTickEntity
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.domain.Presence
import com.myfriend.app.domain.PresenceDeriver
import com.myfriend.app.domain.PresenceInput
import com.myfriend.app.domain.PresenceKind
import com.myfriend.app.domain.PresenceRules
import com.myfriend.app.domain.SleepPolicy
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 「他此刻在做什么」的数据拼装。
 *
 * 只读：这里从不写库、也没有任何「设置状态」的方法。它做的事就是把他最近留下的痕迹
 * （后台想过什么、说过什么、翻过什么、心里记着什么、是不是在休息）拼成 [PresenceInput]，
 * 交给 [PresenceDeriver] 推成一句话。
 *
 * 界面上的「12 分钟前」需要自己往前走，所以每 [PULSE_MS] 主动重算一次；
 * 同时订阅 tick 表，他真干了什么的时候能立刻刷新。
 */
@Singleton
class PresenceRepository @Inject constructor(
    private val personaDao: PersonaDao,
    private val personaCardRepository: PersonaCardRepository,
    private val agentTickDao: AgentTickDao,
    private val agentIntentDao: AgentIntentDao,
    private val chatSessionDao: ChatSessionDao,
    private val messageDao: MessageDao
) {

    /** 界面用的那个流：跟着他自己的动静走，也自己按 [PULSE_MS] 往前走。 */
    fun observe(personaId: String): Flow<Presence> = observe(personaId, ZoneId.systemDefault())

    /** 带时区的重载，方便固定时区地看同一份数据。 */
    fun observe(personaId: String, zone: ZoneId): Flow<Presence> {
        val ticks = agentTickDao.observeRecent(personaId, RECENT_TICKS)
            .catch { emit(emptyList()) }
        // 心跳只用来让「3 分钟前」这类字慢慢变大，不写库、不留协程：
        // 收集结束时 flow 里的 delay 会被取消，循环跟着结束。
        val pulse = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(PULSE_MS)
            }
        }
        return combine(ticks, pulse) { recent, now -> recent to now }
            .map { (recent, now) -> snapshot(personaId, recent, now, zone) }
            .catch { error ->
                if (error is CancellationException) throw error
                // 数据库出问题不该让聊天标题栏空掉：退回最朴素的一句。
                emit(PresenceDeriver.IDLE_FALLBACK)
            }
    }

    // ------------------------------------------------------------ 内部

    /**
     * 此刻的他：不订阅、不等待，问一次就回答一次。
     *
     * 后台「想一下」的时候需要知道自己现在是什么光景（刚醒、在发呆、在忙别的），
     * 但它不能挂着界面用的那条流，所以这里给一个一次性快照。
     *
     * 返回的 [Presence.detail] 换成了**他自己视角**的说法（「刚醒，脑子还没转」），
     * 而不是界面用的第三人称（「他有好一阵没动静了」）：这段字会原样拼进他的提示词，
     * 用「他」开头会让他读起来像在说别人；「在忙别的」那种监控感的说法也不该出现在他心里。
     */
    suspend fun current(
        personaId: String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Presence {
        val ticks = runCatching {
            agentTickDao.observeRecent(personaId, RECENT_TICKS).first()
        }.getOrDefault(emptyList())
        val presence = snapshot(personaId, ticks, now, zone)
        return presence.copy(detail = selfDescription(presence))
    }

    /** 把推导结果翻成他自己会说的那句话。认不出的状态就不硬写，退回原来的解释。 */
    private fun selfDescription(presence: Presence): String = when (presence.kind) {
        PresenceKind.ASLEEP -> "这会儿睡下了，脑子是歇着的"
        PresenceKind.JUST_WOKE -> "刚醒，脑子还没转过来"
        PresenceKind.BROWSING -> "正一个人翻着点东西，看到有意思的会想讲给他听"
        PresenceKind.THINKING -> "刚一个人坐着想了一阵，没急着开口"
        PresenceKind.WRITING -> "人还在对话框这边，刚说了话"
        PresenceKind.WAITING -> "心里揣着一件事，在等一个合适的时机"
        // 「在忙别的」是给界面看的说法，他自己不会这么讲自己。
        PresenceKind.AWAY -> "有阵子没顾上这边，在过自己的日子"
        PresenceKind.IDLE -> if (presence.detail.contains("这么晚")) {
            "这么晚了还没睡，一个人坐着"
        } else {
            "在发呆，没什么事"
        }
    }

    /** 把这一瞬间能查到的痕迹拼成一次推导。任何一处查不到都不影响其它几处。 */
    private suspend fun snapshot(
        personaId: String,
        recentTicks: List<AgentTickEntity>,
        now: Long,
        zone: ZoneId
    ): Presence = runCatching {
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull()
        val asleepNow = persona?.let { it.isAsleep(now, zone) } ?: false
        val minutesSinceWake = persona?.let { minutesSinceWake(it, now, zone) }
        val lastTick = recentTicks.firstOrNull()
        val lastBrowse = recentTicks.firstOrNull { it.looksLikeBrowsing() }
        // 最近在聊的那个会话：一次查询同时拿到「最近一条消息」和「他最后说的那句」。
        val session = runCatching { chatSessionDao.latestForPersona(personaId) }.getOrNull()
        val lastMessage = session?.let {
            runCatching { messageDao.latestInSession(it.id) }.getOrNull()
        }
        val lastOwnMessage = session?.let {
            runCatching { messageDao.latestAssistant(it.id) }.getOrNull()
        }
        val input = PresenceInput(
            asleepNow = asleepNow,
            sleepWakeText = persona?.let {
                SleepPolicy.wakeClockText(it.sleepEnabled, it.quietSleepMinutes, it.quietWakeMinutes)
            },
            minutesSinceWake = minutesSinceWake,
            minutesSinceLastTick = minutesBetween(lastTick?.at, now),
            lastTickOutcome = lastTick?.outcome,
            minutesSinceLastBrowse = minutesBetween(lastBrowse?.at, now),
            minutesSinceLastMessage = minutesBetween(lastMessage?.createdAt, now),
            minutesSinceLastAssistantMessage = minutesBetween(lastOwnMessage?.createdAt, now),
            hasPendingIntentSoon = hasIntentSoon(personaId, now),
            hourOfDay = hourOfDay(now, zone)
        )
        PresenceDeriver.derive(input, now)
    }.getOrElse { PresenceDeriver.IDLE_FALLBACK.copy(updatedAt = now) }

    private fun PersonaEntity.isAsleep(now: Long, zone: ZoneId): Boolean {
        val minute = minuteOfDay(now, zone)
        return runCatching { personaCardRepository.isAsleep(this, minute) }.getOrDefault(false)
    }

    /**
     * 距离最近一次「醒来」过了多少分钟。
     *
     * 只按他自己定的作息算：今天的起床时间还没到，就用昨天那个。
     * 他没安排作息时返回 null —— 不知道就别猜。
     */
    private fun minutesSinceWake(persona: PersonaEntity, now: Long, zone: ZoneId): Int? {
        val wake = persona.quietWakeMinutes ?: return null
        // 睡和醒定在同一个点上等于没安排作息，这时候「起床时间」没有意义，别硬算。
        if (persona.quietSleepMinutes == wake) return null
        val wakeMinute = ((wake % 1440) + 1440) % 1440
        return runCatching {
            val today = Instant.ofEpochMilli(now).atZone(zone)
            var wakeAt = today.toLocalDate()
                .atTime(wakeMinute / 60, wakeMinute % 60)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            if (wakeAt > now) wakeAt -= DAY_MS
            minutesBetween(wakeAt, now)
        }.getOrNull()
    }

    /** 他给自己留的念头里，有没有落在接下来半小时内的（还没过期的）。 */
    private suspend fun hasIntentSoon(personaId: String, now: Long): Boolean = runCatching {
        val horizon = now + PresenceRules.INTENT_SOON_MINUTES * 60_000L
        agentIntentDao.pendingForPersona(personaId).any { intent ->
            intent.earliestAt <= horizon && intent.latestAt >= now
        }
    }.getOrDefault(false)

    private fun AgentTickEntity.looksLikeBrowsing(): Boolean {
        val text = outcome
        if (text.contains("上网") || text.contains("浏览") || text.contains("翻了翻")) return true
        return actionJson.contains("\"browse\"") || actionJson.contains("\"web_search\"")
    }

    private companion object {
        /** 每 30 秒重算一次：让「3 分钟前」这样的字自己往前走。 */
        const val PULSE_MS = 30_000L

        /** 只为判断「刚才有没有翻东西」而读的最近几条思考留痕。 */
        const val RECENT_TICKS = 30

        const val DAY_MS = 86_400_000L

        /** 两个时间点差多少分钟；不知道（null）或时钟回拨都算「刚刚」。 */
        fun minutesBetween(at: Long?, now: Long): Int? {
            if (at == null || at <= 0L) return null
            val delta = now - at
            if (delta <= 0L) return 0
            val minutes = delta / 60_000L
            return if (minutes > Int.MAX_VALUE) Int.MAX_VALUE else minutes.toInt()
        }

        fun minuteOfDay(now: Long, zone: ZoneId): Int =
            runCatching {
                val time = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
                time.hour * 60 + time.minute
            }.getOrDefault(0)

        fun hourOfDay(now: Long, zone: ZoneId): Int =
            runCatching { Instant.ofEpochMilli(now).atZone(zone).hour }.getOrDefault(12)
    }
}
