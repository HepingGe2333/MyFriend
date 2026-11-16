package com.yap.agentd.proactive

import com.yap.app.domain.Heartbeat
import com.yap.app.domain.SleepPolicy
import java.time.Instant
import java.time.ZoneId

/**
 * 「他现在醒着吗、能被打扰吗」 —— 由睡眠窗口与最后互动推导出来的纯数据。
 *
 * 判定本身不在这个文件里：它直接调用 `:agentcore` 的 `SleepPolicy.evaluate`，
 * 也就是 `spec/agent-core/cases/sleep-decision.json` 那 16 条用例钉住的同一份实现
 * （`sleepEnabled=false` 永远醒着、默认 23:30–08:00、睡点算睡着、醒点算醒着、跨午夜、
 * 免打扰不唤醒也不留已读、唤醒阈值 1–20、睡醒同点退回默认窗口…）。
 *
 * 服务端比客户端只多知道一件事：**最后互动时间**。它有两种用法，都在这里：
 * - 「最近 30 分钟内对方说过话」→ 视为窗口内有 1 条消息（[recentUserMessages]），
 *   够不够叫醒他仍由 `wakeThreshold` 决定（默认 6 条，1 条不够）；
 * - [minutesSinceLastInteraction] 给心跳曲线用（刚聊完要先安静一会儿）。
 *
 * 客户端离线时服务端收不到新消息，所以 [ProactiveEngine] 只会拿到 0 条（没人能叫醒他）；
 * 这个参数留着，是为了「客户端恢复连接后补发的消息」这条链路不用改判定逻辑。
 */
data class SleepState(
    val asleep: Boolean,
    val dnd: Boolean,
    val woken: Boolean,
    val sleepEnabled: Boolean,
    /** 实际生效的作息（`null` 配置已按 `SleepPolicy.window` 折成默认 23:30–08:00）。 */
    val sleepMinutes: Int,
    val wakeMinutes: Int,
    /** 现在（服务端本地时区）是当天第几分钟。 */
    val minuteOfDay: Int,
    val lastInteractionAt: Long,
    val now: Long,
    val zone: ZoneId
) {

    /** 醒着 = 不在睡眠窗口内。 */
    val awake: Boolean get() = !asleep

    /** 这一轮要不要真的去生成回复：睡着且没被叫醒就不回（`SleepPolicy.Decision` 同义）。 */
    val shouldReply: Boolean get() = !asleep || woken

    /** 要不要留已读：免打扰是唯一的例外（§6.2）。 */
    val marksRead: Boolean get() = !dnd

    /**
     * 距离醒来还有多少分钟；醒着时为 0。
     *
     * 不睡觉的人格（`sleepEnabled=false`）永远是 0 —— 不能因为「默认作息窗口里正好是凌晨」
     * 就报一个 300 分钟的倒计时，那会让日志读起来像「他还要睡 5 小时」，而他其实醒着。
     */
    val minutesUntilWake: Int
        get() = if (!sleepEnabled || !asleep) 0 else {
            SleepPolicy.minutesUntilWake(minuteOfDay, sleepMinutes, wakeMinutes)
        }

    /** 下一次醒来（本地时间）对应的毫秒时刻；睡着时它就是 `scheduledAt`。 */
    val nextAwakeAt: Long get() = nextOccurrence(now, wakeMinutes, zone)

    /** 作息窗口的展示文案，例如 `23:30–08:00`。 */
    val windowText: String get() = "${clock(sleepMinutes)}–${clock(wakeMinutes)}"

    /** 距离最后互动多少分钟（没有记录 → 0）。 */
    val minutesSinceLastInteraction: Double
        get() = Heartbeat.minutesBetween(lastInteractionAt, now)

    companion object {

        /**
         * 从人格状态推导睡眠状态。
         *
         * @param recentUserMessages 最近 30 分钟内对方发了几条（含刚发的）；默认由 [lastInteractionAt] 推：
         *                           30 分钟内有互动 = 1 条（够不够叫醒仍看 [PersonaState.wakeThreshold]）
         */
        fun derive(
            persona: PersonaState,
            now: Long,
            zone: ZoneId,
            recentUserMessages: Int = recentUserMessages(persona.lastInteractionAt, now)
        ): SleepState = derive(
            sleepEnabled = persona.sleepEnabled,
            sleepMinutes = persona.sleepMinutes,
            wakeMinutes = persona.wakeMinutes,
            dndOnSleep = persona.dndOnSleep,
            minuteOfDay = minuteOfDay(now, zone),
            recentUserMessages = recentUserMessages,
            wakeThreshold = persona.wakeThreshold,
            lastInteractionAt = persona.lastInteractionAt,
            now = now,
            zone = zone
        )

        /** 显式版：`spec/agent-core/cases/sleep-decision.json` 的字段一一对应，便于拿用例文件直接喂。 */
        fun derive(
            sleepEnabled: Boolean,
            sleepMinutes: Int?,
            wakeMinutes: Int?,
            dndOnSleep: Boolean,
            minuteOfDay: Int,
            recentUserMessages: Int,
            wakeThreshold: Int,
            lastInteractionAt: Long,
            now: Long,
            zone: ZoneId
        ): SleepState {
            val decision = SleepPolicy.evaluate(
                sleepEnabled = sleepEnabled,
                sleepMinutes = sleepMinutes,
                wakeMinutes = wakeMinutes,
                minuteOfDay = minuteOfDay,
                dndOnSleep = dndOnSleep,
                recentUserMessages = recentUserMessages,
                wakeThreshold = wakeThreshold
            )
            val (sleep, wake) = SleepPolicy.window(sleepMinutes, wakeMinutes)
            return SleepState(
                asleep = decision.asleep,
                dnd = decision.dnd,
                woken = decision.woken,
                sleepEnabled = sleepEnabled,
                sleepMinutes = sleep,
                wakeMinutes = wake,
                minuteOfDay = SleepPolicy.normalizeMinutes(minuteOfDay),
                lastInteractionAt = lastInteractionAt,
                now = now,
                zone = zone
            )
        }

        /**
         * 「最近 30 分钟内有互动」→ 1 条消息，否则 0。
         *
         * 为什么不是真的去数消息：服务端主动判定时客户端可能根本没连着（这正是「无后台」的前提），
         * 能拿到的只有「最后互动时间」。1 条是最保守的下界：它只会让「阈值 1 就醒」的人格醒来，
         * 不会替阈值 6 的人格编造 6 条消息。
         */
        fun recentUserMessages(
            lastInteractionAt: Long,
            now: Long,
            windowMs: Long = SleepPolicy.WAKE_WINDOW_MS
        ): Int {
            if (lastInteractionAt <= 0L) return 0
            val delta = now - lastInteractionAt
            return if (delta in 0..windowMs) 1 else 0
        }

        /** 现在（本地时区）是当天第几分钟（0..1439）。 */
        fun minuteOfDay(now: Long, zone: ZoneId): Int {
            val time = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
            return time.hour * 60 + time.minute
        }

        /** 现在是本地几点（0..23）；心跳的「深夜折扣」用它。 */
        fun hourOfDay(now: Long, zone: ZoneId): Int = minuteOfDay(now, zone) / 60

        /**
         * 下一个「本地时间等于 [minuteOfDay]」的时刻（严格晚于 [now]）。
         *
         * 用 `java.time` 的本地日历而不是「now + 差值」：跨夏令时的时区里，
         * 直接用毫秒加会让「08:00 醒」变成「09:00 醒」。
         */
        fun nextOccurrence(now: Long, minuteOfDay: Int, zone: ZoneId): Long {
            val minute = SleepPolicy.normalizeMinutes(minuteOfDay)
            val current = Instant.ofEpochMilli(now).atZone(zone)
            val startOfDay = current.toLocalDate().atStartOfDay(zone)
            val today = startOfDay.plusMinutes(minute.toLong())
            val candidate = if (today.isAfter(current)) today else today.plusDays(1)
            return candidate.toInstant().toEpochMilli()
        }

        /** 本地时间的下一个零点（每日主动次数在这里重置）。 */
        fun nextLocalMidnight(now: Long, zone: ZoneId): Long {
            val current = Instant.ofEpochMilli(now).atZone(zone)
            return current.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }

        /** 当天第几分钟 → `HH:mm`。 */
        fun clock(minuteOfDay: Int): String {
            val minute = SleepPolicy.normalizeMinutes(minuteOfDay)
            val hour = minute / 60
            val rest = minute % 60
            return (if (hour < 10) "0$hour" else "$hour") + ":" + (if (rest < 10) "0$rest" else "$rest")
        }
    }
}
