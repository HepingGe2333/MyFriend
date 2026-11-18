package com.yap.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日记板：**他自己的备忘录**（`spec/diary/README.md`）。
 *
 * 这里只有纯逻辑：内容规整、提醒时间换算、条数上限、提醒窗口判定、给模型看的正文。
 * 数据库、心跳、工具执行都在上层；时间与时区一律由调用方传进来。
 *
 * 三种「时间」不要混淆：
 * - [Entry.createdAt]：他写下这条的时刻；
 * - [Entry.remindAt]：他**希望**被提醒的时刻（可空 = 不提醒）；
 * - [Entry.remindedAt]：这条提醒**已经变成念头**的时刻（非空 = 已经触发过，永不重复）。
 */
object Diary {

    /** 单条内容上限（规范：≤ 2000 字）。 */
    const val CONTENT_MAX: Int = 2000

    /** `list` 的默认与上限（规范：默认 20 条，最多 50）。 */
    const val LIST_DEFAULT: Int = 20
    const val LIST_MAX: Int = 50

    /** `remindInMinutes` 的夹取范围：1 分钟到 30 天；再长就不是「提醒」而是日程了。 */
    const val REMIND_IN_MINUTES_MIN: Int = 1
    const val REMIND_IN_MINUTES_MAX: Int = 30 * 24 * 60

    /** 到点后最多补多久：超过 24 小时的提醒只清标记，不再打扰（规范）。 */
    const val MISSED_GRACE_MS: Long = 24 * 60 * 60 * 1000L

    /** 提醒变成念头后，留给循环的开口窗口（他说不说是他自己的事，窗口不宜太长）。 */
    const val REMINDER_WINDOW_MS: Long = 30 * 60 * 1000L

    /** 一条笔记的只读视图（不让领域层依赖 Room 实体）。 */
    data class Entry(
        val id: String,
        val content: String,
        val createdAt: Long,
        val remindAt: Long? = null,
        val remindedAt: Long? = null
    )

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val MM_DD = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)
    private val YYYY_MM_DD = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    private val WEEKDAYS = mapOf(
        java.time.DayOfWeek.MONDAY to "周一",
        java.time.DayOfWeek.TUESDAY to "周二",
        java.time.DayOfWeek.WEDNESDAY to "周三",
        java.time.DayOfWeek.THURSDAY to "周四",
        java.time.DayOfWeek.FRIDAY to "周五",
        java.time.DayOfWeek.SATURDAY to "周六",
        java.time.DayOfWeek.SUNDAY to "周日"
    )

    // ---------------------------------------------------------------- 写入前的规整

    /**
     * 规整写进来的正文：去掉首尾空白、折成不超过 [CONTENT_MAX] 字。
     *
     * 空内容（或只有空白）返回 `null`：一条空笔记没有意义，让调用方明确地失败，
     * 而不是静默写一条他自己都看不懂的空行。
     */
    fun cleanContent(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        return if (text.length <= CONTENT_MAX) text else text.take(CONTENT_MAX)
    }

    /** `list` 的条数：缺省 20，夹在 1..50。 */
    fun limitOf(requested: Int?): Int =
        (requested ?: LIST_DEFAULT).coerceIn(1, LIST_MAX)

    /**
     * 解析「什么时候提醒我」。
     *
     * 优先级：显式 [remindAtMillis] > [remindInMinutes]（相对 now）> 不提醒。
     * 两者都给时用显式的那个——模型偶尔会两个都写，明确的时间点更可信。
     * 非法值（0、负数、超出 30 天）一律当作「不提醒」，绝不写出一个荒唐的定时。
     */
    fun resolveRemindAt(remindAtMillis: Long?, remindInMinutes: Int?, now: Long): Long? {
        val explicit = remindAtMillis?.takeIf { it > 0L }
        if (explicit != null) return explicit
        val minutes = remindInMinutes ?: return null
        if (minutes < REMIND_IN_MINUTES_MIN || minutes > REMIND_IN_MINUTES_MAX) return null
        return now + minutes.toLong() * 60_000L
    }

    // ---------------------------------------------------------------- 提醒

    /** 到点的提醒是不是已经错过太久（> 24 小时）：错过就不补，只清标记。 */
    fun isMissed(remindAt: Long?, now: Long): Boolean {
        if (remindAt == null || remindAt <= 0L) return false
        return now - remindAt > MISSED_GRACE_MS
    }

    /** 这是不是一条「该提醒了」的笔记：有 remindAt、还没提醒过、且已经到点。 */
    fun isDue(entry: Entry, now: Long): Boolean =
        entry.remindAt != null && entry.remindAt > 0L && entry.remindedAt == null && entry.remindAt <= now

    // ---------------------------------------------------------------- 给模型看

    /**
     * `list` 回灌给模型的正文。
     *
     * 写成他自己翻本子时的语气：先时间，再内容，最后提醒状态；没有笔记时给一句人话，
     * 而不是空字符串（空字符串会让模型以为工具坏了，反复重试）。
     */
    fun renderForModel(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (entries.isEmpty()) return "日记本上还没有东西。"
        val header = "你日记本上的备忘（最近 ${entries.size} 条，新的在上面）："
        val lines = entries.map { entry ->
            val whenText = relative(entry.createdAt, now)
            val remind = when {
                entry.remindAt == null -> ""
                entry.remindedAt != null -> "（提醒过）"
                entry.remindAt <= now -> "（提醒到点了）"
                else -> "（提醒：${absolute(entry.remindAt, zone)}）"
            }
            // 带上 id：模型要能指着某一条说「撕掉它」（remove 只认 id，界面不显示 id）。
            "- #${entry.id} （$whenText）${entry.content.replace('\n', ' ').take(200)}$remind"
        }
        return (listOf(header) + lines).joinToString("\n")
    }

    /** 一条笔记写进模型正文的单行形式（`add` 回灌用）。 */
    fun renderOne(entry: Entry, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val remind = entry.remindAt?.let { "；到 ${absolute(it, zone)} 提醒你" }.orEmpty()
        return "记下了：「${entry.content.replace('\n', ' ').take(200)}」$remind"
    }

    /**
     * 提醒变成念头时写进 `agent_intents.note` 的一句话。
     *
     * 是**他自己**看到备忘时的一句自语，不是系统通知文案；所以用第一人称、带上原文。
     */
    fun reminderNote(content: String): String =
        "日记里写着要记得：${content.replace('\n', ' ').trim().take(120)}"

    /** 念头的 hint：告诉未来的自己这条提醒是从哪来的、可以怎么用。 */
    fun reminderHint(createdAt: Long, now: Long): String =
        "这条备忘是${relative(createdAt, now)}写下的；如果合适，可以就这件事开口，不适合就先放着。"

    // ---------------------------------------------------------------- 时间文案

    /** 「11-21 周五 09:00」：同一年只写月日，跨年才写全年；写入约定里不出现工程格式。 */
    fun absolute(at: Long, zone: ZoneId, now: Long = System.currentTimeMillis()): String = runCatching {
        val zoned = Instant.ofEpochMilli(at).atZone(zone)
        val sameYear = Instant.ofEpochMilli(now).atZone(zone).year == zoned.year
        val day = if (sameYear) zoned.format(MM_DD) else zoned.format(YYYY_MM_DD)
        val weekday = WEEKDAYS[zoned.dayOfWeek].orEmpty()
        listOf(day, weekday, zoned.format(HH_MM)).filter { it.isNotBlank() }.joinToString(" ")
    }.getOrDefault("")

    /** 「刚刚 / 12 分钟前 / 3 小时前 / 昨天 / 3 天前」：够粗就行，不必精确。 */
    private fun relative(at: Long, now: Long): String {
        if (at <= 0L) return "很久以前"
        val delta = now - at
        if (delta < 60_000L) return "刚刚"
        val minutes = delta / 60_000L
        if (minutes < 60) return "$minutes 分钟前"
        val hours = minutes / 60
        if (hours < 24) return "$hours 小时前"
        val days = hours / 24
        if (days == 1L) return "昨天"
        if (days < 30) return "$days 天前"
        val months = days / 30
        if (months < 12) return "$months 个月前"
        return "${months / 12} 年前"
    }
}
