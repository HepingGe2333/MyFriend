package com.yap.app.domain

/**
 * 他此刻在做什么。
 *
 * 这是**他自己的状态**，不是用户能设置的开关：界面只能看见，不能改。
 * 推导只依赖「他最近做过什么」——睡没睡、刚醒没、刚说过话没、想了没、翻过东西没、
 * 心里是不是记着一件准备开口的事。全都由时间推出来，所以他什么都不做的时候会自然地
 * 变回「在发呆」，而不是卡在某个状态里。
 *
 * 这个文件是纯 Kotlin：不碰 Android、数据库、网络；时间一律由参数传进来；
 * 坏数据（null、负数、未来时间、超大值）都不抛异常，只是安静地当作「不知道」。
 */

/** 他在做的事情的种类。界面不显示这些英文名字，只显示 [Presence.text] 里的中文。 */
enum class PresenceKind {
    /** 他自己定的作息时间段里，睡着。 */
    ASLEEP,

    /** 刚睡醒的一小段，脑子还没转起来。 */
    JUST_WOKE,

    /** 在翻东西：刚自己上网看过点什么。 */
    BROWSING,

    /** 在想事情：刚自己一个人想过一阵。 */
    THINKING,

    /** 在写东西：刚说过话，或者对方刚说话、他大概正在回去。 */
    WRITING,

    /** 心里记着一件事，过一会儿想找你。 */
    WAITING,

    /** 在发呆：没什么特别的事，就是坐着。 */
    IDLE,

    /** 在忙别的：好一阵子没动静了。 */
    AWAY
}

/**
 * 推导「他此刻在做什么」要看的几件事。
 *
 * 时间单位一律是「多少分钟之前」，null 表示不知道（没有这类记录）。
 * [minutesSinceLastBrowse] 是优化项：能精确知道上一次「上网看看」是什么时候时传进来；
 * 不知道也可以，用 [lastTickOutcome] 里能看出来的迹象兜底。
 */
data class PresenceInput(
    /** 现在是不是他给自己安排的休息时间。 */
    val asleepNow: Boolean = false,
    /** 睡着时「几点醒」的文本（如 "23:30"）；不知道或不睡觉时为 null。 */
    val sleepWakeText: String? = null,
    /** 距离他最近一次「醒来」过了多少分钟（按他自己定的作息算）；不知道为 null。 */
    val minutesSinceWake: Int? = null,
    /** 距离上一次后台「想一下」过了多少分钟。 */
    val minutesSinceLastTick: Int? = null,
    /** 上一次「想」之后干了什么的一句话（库里 action/outcome 的原文，可能是空串）。 */
    val lastTickOutcome: String? = null,
    /** 距离上一次「上网看看」过了多少分钟；不知道为 null。 */
    val minutesSinceLastBrowse: Int? = null,
    /** 距离最近一条消息（谁说的都算）过了多少分钟。 */
    val minutesSinceLastMessage: Int? = null,
    /** 距离他自己最近说的一句话过了多少分钟。 */
    val minutesSinceLastAssistantMessage: Int? = null,
    /** 他留的念头里，有没有落在接下来半小时内的。 */
    val hasPendingIntentSoon: Boolean = false,
    /** 现在几点（0..23），只用来让措辞更贴近当下，不参与排序。 */
    val hourOfDay: Int = 12
)

/**
 * 一次推导的结果。
 *
 * @param text 一行中文，界面直接显示（如「在想事情」）。
 * @param detail 稍微多一句的解释，用在需要解释的地方（如「他刚自己呆着想了一阵，没急着开口」）。
 * @param sinceAt 这个状态大概从什么时候开始（毫秒时间戳）；不知道时为 0，界面就不显示「…前」。
 * @param updatedAt 这次推导的时刻。
 */
data class Presence(
    val kind: PresenceKind = PresenceKind.IDLE,
    val text: String = "",
    val detail: String = "",
    val sinceAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * 睡着时「几点醒」的原文（如 "23:30"）；界面要用它拼自己语言的那一句。
     *
     * [text] 是给中文看的整句，界面不再直接用它（英语要能说 "Asleep · up at 23:30"）；
     * 但纯 Kotlin 的测试与工具侧仍然只认 [text]。
     */
    val wakeText: String? = null
) {

    /** 有没有内容可显示：没有的话界面就整行不画。 */
    fun isMeaningful(): Boolean = text.isNotBlank()

    /**
     * 给界面用的一行：「在想事情 · 12 分钟前」。
     * 不知道从什么时候开始（[sinceAt] 为 0）时就只显示那句话，别硬凑时间。
     */
    fun display(now: Long = nowMillis()): String {
        if (text.isBlank()) return ""
        val ago = relativeTime(sinceAt, now)
        return if (ago.isBlank()) text else "$text · $ago"
    }
}

/**
 * 判定用的常量与理由。都写成分钟，方便和界面上的「3 分钟前」对得上。
 */
object PresenceRules {

    /**
     * 刚醒的窗口：45 分钟。
     * 人起床后的懵一般持续半小时上下；再长就不算「刚醒」，再短会显得他没醒过。
     */
    const val JUST_WOKE_MINUTES = 45

    /**
     * 「在写东西」的窗口：3 分钟。
     * 消息刚发出去、或者对方刚说完，他这会儿人还在对话框前；超过三分钟就未必了。
     */
    const val WRITING_MINUTES = 3

    /**
     * 「在翻东西」的窗口：15 分钟。
     * 后台每几分钟才想一次，翻完一样东西的余温大概就一刻钟。
     */
    const val BROWSING_MINUTES = 15

    /**
     * 「在想事情」的窗口：15 分钟。
     * 和翻东西同一个尺度：一次「想」的痕迹只在这段时间内还算「正在想」。
     */
    const val THINKING_MINUTES = 15

    /**
     * 「过一会儿想找你」的提前量：30 分钟。
     * 只提前半小时说，太早说了会像提醒事项，不像一个人心里揣着件事。
     */
    const val INTENT_SOON_MINUTES = 30

    /**
     * 「在忙别的」的门槛：3 小时。
     * 三小时没有任何动静，才值得说一句他在忙别的；再短会像在盯着他。
     */
    const val AWAY_MINUTES = 180

    /** 深夜（0..4 点）时「在发呆」的措辞会不一样：这么晚还没睡，本身就是件事。 */
    val LATE_NIGHT_HOURS = 0..4
}

/**
 * 由「他最近做过什么」推出现在在做什么。
 *
 * 规则是一串优先级，先命中的赢（越靠前越具体、越不容置疑）：
 *
 * 1. 睡着了 → 睡着了；
 * 2. 刚醒（起床 45 分钟内）→ 刚醒；
 * 3. 3 分钟内说过话 → 在写东西；
 * 4. 15 分钟内翻过东西 → 在翻东西；
 * 5. 15 分钟内想过事情 → 在想事情；
 * 6. 半小时内有个想找你的念头 → 过一会儿想找你；
 * 7. 3 小时以上没动静 → 在忙别的；
 * 8. 其余 → 在发呆。
 *
 * 为什么是这个顺序：睡觉和刚醒是生理事实，压过一切；「在写东西」有时效性最强，
 * 刚说完话的人不会同时在「翻东西」；「在等时机」是心里的打算，比「没动静」更值得说；
 * 「在忙别的」需要足够长的沉默才算，不然会显得在监视他。
 */
object PresenceDeriver {

    /** 什么都没做、也没有任何记录时的状态：坐着发呆。最不冒犯的一种猜测。 */
    val IDLE_FALLBACK = Presence(
        kind = PresenceKind.IDLE,
        text = "在发呆",
        detail = "没什么特别的事，他就坐着发发呆。"
    )

    fun derive(input: PresenceInput, now: Long): Presence = runCatching {
        val sinceTick = input.minutesSinceLastTick.minutesOrNull()
        val sinceMessage = input.minutesSinceLastMessage.minutesOrNull()
        val sinceOwnMessage = input.minutesSinceLastAssistantMessage.minutesOrNull()
        val sinceWake = input.minutesSinceWake.minutesOrNull()
        val sinceBrowse = input.minutesSinceLastBrowse.minutesOrNull()
            ?: browseMinutesFromOutcome(input.lastTickOutcome, sinceTick)
        val sinceAnyActivity = listOfNotNull(sinceTick, sinceMessage, sinceOwnMessage).minOrNull()
        val hour = input.hourOfDay.coerceIn(0, 23)

        when {
            input.asleepNow -> presence(
                kind = PresenceKind.ASLEEP,
                // 规格 §六：聊天页状态行要能读到「睡着了 · 23:30 醒」。
                // 没有作息文本时退回原来的「睡着了」，不硬编一个时间。
                text = input.sleepWakeText?.takeIf { it.isNotBlank() }
                    ?.let { "睡着了 · $it 醒" }
                    ?: "睡着了",
                detail = "他把这段时间设成了休息，消息会留着，醒了再看。",
                sinceMinutes = null,
                now = now,
                wakeText = input.sleepWakeText?.takeIf { it.isNotBlank() }
            )

            sinceWake != null && sinceWake <= PresenceRules.JUST_WOKE_MINUTES -> presence(
                kind = PresenceKind.JUST_WOKE,
                text = "刚醒",
                detail = if (hour in 5..10) {
                    "他一早刚起来，还没完全清醒。"
                } else {
                    "他刚睡醒，人还有点懵。"
                },
                sinceMinutes = sinceWake,
                now = now
            )

            speaksRecently(sinceOwnMessage, sinceMessage) -> presence(
                kind = PresenceKind.WRITING,
                text = "在写东西",
                detail = if (sinceOwnMessage != null && sinceOwnMessage <= PresenceRules.WRITING_MINUTES) {
                    "他刚说完一句，还在那儿。"
                } else {
                    "对方刚说了话，他大概正在写回来。"
                },
                sinceMinutes = listOfNotNull(sinceOwnMessage, sinceMessage).minOrNull(),
                now = now
            )

            sinceBrowse != null && sinceBrowse <= PresenceRules.BROWSING_MINUTES -> presence(
                kind = PresenceKind.BROWSING,
                text = "在翻东西",
                detail = "他正自己翻着些东西，看到有意思的才可能跟你说。",
                sinceMinutes = sinceBrowse,
                now = now
            )

            sinceTick != null && sinceTick <= PresenceRules.THINKING_MINUTES -> presence(
                kind = PresenceKind.THINKING,
                text = "在想事情",
                detail = "他刚一个人想了一阵，没急着开口。",
                sinceMinutes = sinceTick,
                now = now
            )

            input.hasPendingIntentSoon -> presence(
                kind = PresenceKind.WAITING,
                text = "过一会儿想找你",
                detail = "他心里记着一件事，在等一个合适的时机开口。",
                sinceMinutes = null,
                now = now
            )

            sinceAnyActivity != null && sinceAnyActivity >= PresenceRules.AWAY_MINUTES -> presence(
                kind = PresenceKind.AWAY,
                text = "在忙别的",
                detail = "他有好一阵没动静了，大概在忙自己的事。",
                sinceMinutes = sinceAnyActivity,
                now = now
            )

            else -> presence(
                kind = PresenceKind.IDLE,
                text = "在发呆",
                detail = if (hour in PresenceRules.LATE_NIGHT_HOURS) {
                    "这么晚了还没睡，他就一个人坐着。"
                } else {
                    "没什么特别的事，他就坐着发发呆。"
                },
                sinceMinutes = sinceAnyActivity,
                now = now
            )
        }
    }.getOrElse { IDLE_FALLBACK.copy(updatedAt = now) }

    /** 3 分钟内有消息来往（他自己说的，或者对方刚说、他正回）。 */
    private fun speaksRecently(sinceOwnMessage: Int?, sinceMessage: Int?): Boolean =
        (sinceOwnMessage != null && sinceOwnMessage <= PresenceRules.WRITING_MINUTES) ||
            (sinceMessage != null && sinceMessage <= PresenceRules.WRITING_MINUTES)

    /**
     * 上一次「想」的痕迹里能不能看出他在翻东西。
     *
     * 库里的 outcome 是他那一轮做过什么的一句话（如「自己上网看了看」「发了一条消息」），
     * 也可能是动作类型名（browse / web / search）。认不出来就当没翻过。
     */
    private fun browseMinutesFromOutcome(outcome: String?, sinceTick: Int?): Int? {
        if (sinceTick == null || outcome.isNullOrBlank()) return null
        return if (looksLikeBrowsing(outcome)) sinceTick else null
    }

    private fun looksLikeBrowsing(outcome: String): Boolean {
        val text = outcome.trim()
        if (text.isEmpty()) return false
        val keywords = listOf("上网", "浏览", "翻了翻", "翻东西", "查了查")
        if (keywords.any { text.contains(it) }) return true
        val lower = text.lowercase()
        return lower.contains("browse") || lower.contains("\"web\"") || lower.contains("web_search")
    }

    /** 负数、未知一律当作「刚刚」，不让坏数据变成一个奇怪的状态。 */
    private fun Int?.minutesOrNull(): Int? = when {
        this == null -> null
        this < 0 -> 0
        else -> this
    }

    private fun presence(
        kind: PresenceKind,
        text: String,
        detail: String,
        sinceMinutes: Int?,
        now: Long,
        wakeText: String? = null
    ): Presence {
        val sinceAt = if (sinceMinutes == null) {
            0L
        } else {
            // 时间戳不要超过 now、也不要为负：坏数据把「多少分钟前」算大了的时候，
            // 界面按「不知道什么时候」处理，总比显示一个荒唐的时间强。
            val raw = now - sinceMinutes.toLong() * 60_000L
            when {
                raw <= 0L -> 0L
                raw > now -> now
                else -> raw
            }
        }
        return Presence(
            kind = kind,
            text = text,
            detail = detail,
            sinceAt = sinceAt,
            updatedAt = now,
            wakeText = wakeText
        )
    }
}

/**
 * 「多久以前」的中文说法：刚刚 / 3 分钟前 / 昨天 / 上周 / 很久以前。
 *
 * [ms] 是那个时刻（毫秒时间戳），[now] 是现在。0 或负数表示「不知道什么时候」，返回空串，
 * 让调用方干脆别写这一句；未来的时间钟（时钟回拨）当作「刚刚」，不抛异常。
 */
fun relativeTime(ms: Long, now: Long): String {
    if (ms <= 0L) return ""
    if (now <= ms) return "刚刚"
    val delta = now - ms
    val minutes = delta / 60_000L
    if (minutes < 1L) return "刚刚"
    if (minutes < 60L) return "$minutes 分钟前"
    val hours = delta / 3_600_000L
    if (hours < 24L) return "$hours 小时前"
    val days = delta / 86_400_000L
    return when {
        days <= 1L -> "昨天"
        days < 7L -> "$days 天前"
        days < 14L -> "上周"
        days < 30L -> "${days / 7} 周前"
        days < 365L -> "${days / 30} 个月前"
        else -> "很久以前"
    }
}
