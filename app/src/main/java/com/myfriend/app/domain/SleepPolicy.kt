package com.myfriend.app.domain

import kotlin.math.abs

/**
 * 睡眠、免打扰与唤醒阈值。
 *
 * 规格里「作息」从「用户设的静音时段」改成了「他会不会睡觉」：
 * - `sleepEnabled = false`：他不睡，任何时间都会回；
 * - `sleepEnabled = true`：在他自己的时段里睡（没设时段就按 23:30–08:00）；
 * - `dndOnSleep`：睡前由**模型**决定开不开免打扰，用户不能直接改；
 * - `wakeThreshold`：睡眠中连续来 N 条消息才会被叫醒。
 *
 * 这里刻意做成纯函数：不读库、不看设备时间，时间一律由调用方传进来。
 * 这样这些边界（阈值刚到、dnd 开着、跨午夜）才能在 JVM 上一条条测出来，
 * 而不是靠某天在模拟器上「看起来差不多」。
 */
object SleepPolicy {

    /** 没设作息时他默认几点睡（距零点分钟数，23:30）。 */
    const val DEFAULT_SLEEP_MINUTES = 23 * 60 + 30

    /** 没设作息时他默认几点醒（08:00）。 */
    const val DEFAULT_WAKE_MINUTES = 8 * 60

    /** 判定窗口：最近 30 分钟内对方发的消息算「连续叫你」。 */
    const val WAKE_WINDOW_MS = 30 * 60_000L

    const val MIN_WAKE_THRESHOLD = 1
    const val MAX_WAKE_THRESHOLD = 20

    /**
     * 一次「他在睡吗、要不要回」的判定结果。
     *
     * @param asleep 此刻在他的睡眠窗口里（`sleepEnabled=false` 时永远 false）
     * @param dnd 睡眠中开着免打扰：无论叫多少条都不醒
     * @param woken 睡眠中被对方叫醒了：这一轮正常回，并且要留下「被叫醒」的痕迹
     */
    data class Decision(
        val asleep: Boolean = false,
        val dnd: Boolean = false,
        val woken: Boolean = false
    ) {
        /** 这一轮要不要真的去生成回复。 */
        val shouldReply: Boolean get() = !asleep || woken
    }

    /** 方便调用方传参：从人格的两个可空字段得到一个确定的作息窗口。 */
    fun window(sleepMinutes: Int?, wakeMinutes: Int?): Pair<Int, Int> {
        val sleep = normalizeMinutes(sleepMinutes ?: DEFAULT_SLEEP_MINUTES)
        val wake = normalizeMinutes(wakeMinutes ?: DEFAULT_WAKE_MINUTES)
        // 睡和醒定在同一个点等于没安排：退回到默认窗口，而不是「全天都在睡」或「全天都醒着」。
        return if (sleep == wake) DEFAULT_SLEEP_MINUTES to DEFAULT_WAKE_MINUTES else sleep to wake
    }

    /** 焦虑的边界都收在这里：分钟数取模 1440，阈值夹 1..20。 */
    fun normalizeMinutes(minutes: Int): Int = ((minutes % 1440) + 1440) % 1440

    fun normalizeThreshold(raw: Int): Int = raw.coerceIn(MIN_WAKE_THRESHOLD, MAX_WAKE_THRESHOLD)

    /** 在不在睡眠窗口里（支持跨午夜，例如 23:30–08:00）。 */
    fun inWindow(minuteOfDay: Int, sleepMinutes: Int, wakeMinutes: Int): Boolean {
        val minute = normalizeMinutes(minuteOfDay)
        val sleep = normalizeMinutes(sleepMinutes)
        val wake = normalizeMinutes(wakeMinutes)
        if (sleep == wake) return false
        return if (sleep > wake) minute >= sleep || minute < wake else minute in sleep until wake
    }

    /**
     * 完整判定。
     *
     * @param recentUserMessages 最近 30 分钟内、包含刚发的这一条在内，对方发了几条
     */
    fun evaluate(
        sleepEnabled: Boolean,
        sleepMinutes: Int?,
        wakeMinutes: Int?,
        minuteOfDay: Int,
        dndOnSleep: Boolean,
        recentUserMessages: Int,
        wakeThreshold: Int
    ): Decision {
        if (!sleepEnabled) return Decision()
        val (sleep, wake) = window(sleepMinutes, wakeMinutes)
        if (!inWindow(minuteOfDay, sleep, wake)) return Decision()
        if (dndOnSleep) return Decision(asleep = true, dnd = true, woken = false)
        val recent = recentUserMessages.coerceAtLeast(0)
        val woken = recent >= normalizeThreshold(wakeThreshold)
        return Decision(asleep = true, dnd = false, woken = woken)
    }

    /** 聊天页状态行用的「23:30 醒」；不睡觉的人返回 null，界面就不显示。 */
    fun wakeClockText(sleepEnabled: Boolean, sleepMinutes: Int?, wakeMinutes: Int?): String? {
        if (!sleepEnabled) return null
        val (_, wake) = window(sleepMinutes, wakeMinutes)
        return "%02d:%02d".format(wake / 60, wake % 60)
    }

    /** 距离醒来还有多少分钟；用于睡眠时的心跳退避。 */
    fun minutesUntilWake(minuteOfDay: Int, sleepMinutes: Int?, wakeMinutes: Int?): Int {
        val (sleep, wake) = window(sleepMinutes, wakeMinutes)
        if (!inWindow(minuteOfDay, sleep, wake)) return 0
        val minute = normalizeMinutes(minuteOfDay)
        val diff = ((wake - minute) + 1440) % 1440
        return abs(diff).coerceAtLeast(10)
    }
}
