package com.myfriend.agentd.proactive

import com.myfriend.app.domain.Heartbeat
import com.myfriend.app.domain.InProcessAgentCore
import com.myfriend.app.domain.ProactiveCapInput
import com.myfriend.app.domain.ProactiveLevel

/**
 * 主动会话引擎的数据类型（`docs/design/server-authoritative.md` §3.4）。
 *
 * 与客户端的差别只有一处：判定**完全在服务端**。客户端不再传「现在几点」「是否免打扰」，
 * 也不需要后台进程；服务端在自己的时钟上每 [ProactivePolicy.tickIntervalMs] 跑一拍，
 * 决定「这一拍要不要主动开口」，把要落库的 [PendingProactive] 交给存储层（由主线接）。
 *
 * 规则不是在这里发明的，全部落在 `:agentcore` 的既有实现上（也就是 `spec/agent-core/cases/`
 * 钉住的那一套）：睡眠/免打扰/唤醒用 `SleepPolicy`，`当日上限` 用 `ProactiveLevel` +
 * `MoodBehavior`（经 `InProcessAgentCore.proactiveCap`），冲动概率用 `Heartbeat`。
 * 本文件只补「服务端特有的那几件事」：谁现在可以被打扰、当天已经说了几次、下一次什么时候再看。
 */

/**
 * 一个人格此刻的主动判定输入。
 *
 * 字段刻意都是**纯数据**：没有 Room 实体、没有网络、没有 Clock —— 引擎是纯函数，
 * 时间由调用方（[ProactiveEngine] 的 Clock 或 `tickOnce(now)`）显式传入，
 * 这样「睡眠窗口边界」「每日上限」这些边界才能在 JVM 单测里一条条钉住。
 */
data class PersonaState(
    val personaId: String,
    /** 只用于兜底文案/log；判定不依赖名字。 */
    val displayName: String = "",
    /** 这个人格的主动消息总开关（人格级或全局）。关了就连判定都不做。 */
    val enabled: Boolean = true,
    /** `sleepEnabled=false`：他不睡，任何时间都醒着。 */
    val sleepEnabled: Boolean = true,
    /** 几点睡 / 几点醒（当天第几分钟，可空 = 默认 23:30–08:00）。 */
    val sleepMinutes: Int? = null,
    val wakeMinutes: Int? = null,
    /** 睡着时是否免打扰（由模型在睡前决定，用户改不了）。 */
    val dndOnSleep: Boolean = false,
    /** 睡眠中被叫醒需要的连续消息数（1–20）。服务端离线时拿不到消息，默认按 6 处理。 */
    val wakeThreshold: Int = 6,
    /** 最近一次互动（用户说的最后一句 / 他说的最后一句 / 上次主动），毫秒；0 = 还没有。 */
    val lastInteractionAt: Long = 0L,
    /** 上次真正主动开口的时刻，毫秒；0 = 还没有。 */
    val lastProactiveAt: Long = 0L,
    /** 今天（服务端本地日）已经主动开口几次。 */
    val proactiveCountToday: Int = 0,
    /** 他此刻的心情（会影响上限与冲动概率）。 */
    val mood: MoodState = MoodState()
)

/**
 * 主动程度落到「每日上限」上的那一格。
 *
 * `dailyCapOverride` 是给「存储层还没接完、或用户显式把主动关掉」留的口子：
 * - `null` = 按 [level] 换算（1–5 → 1–5 次/天，心情再挪一格）；
 * - `<= 0` = 今天不主动（`Overridden` 时上限 0，引擎直接返回 [ProactiveReason.DAILY_CAP]）。
 *
 * 上限的唯一实现还是 `:agentcore` 的 `InProcessAgentCore.proactiveCap`：
 * 它在 `ProactiveLevel.dailyCap(level)` 之上再叠加心情修正（`spec/agent-core/cases/proactive-cap.json`）。
 */
data class LevelPolicy(
    val level: Int = ProactiveLevel.DEFAULT,
    val enabled: Boolean = true,
    val dailyCapOverride: Int? = null
) {

    /** 只看主动程度的基础上限（不含心情）。 */
    fun baseDailyCap(): Int = dailyCapOverride?.coerceAtLeast(0) ?: ProactiveLevel.dailyCap(level)

    /** 今天的实际上限（含心情修正）；`0` 表示今天不主动。 */
    fun dailyCap(mood: MoodState = MoodState()): Int {
        val override = dailyCapOverride
        if (override != null) return override.coerceAtLeast(0)
        return InProcessAgentCore.proactiveCap(
            ProactiveCapInput(
                level = level,
                moodLabel = mood.label.takeIf { it.isNotBlank() },
                moodIntensity = if (mood.label.isBlank()) null else mood.intensity
            )
        )
    }

    /** 心跳权重：1 的人很少心动，5 的人明显更黏（0.7 / 0.85 / 1.0 / 1.15 / 1.3）。 */
    fun heartbeatWeight(): Double = ProactiveLevel.heartbeatWeight(level)

    /** 归一化后的主动程度（1–5），给路由/log 用。 */
    fun normalizedLevel(): Int = ProactiveLevel.normalize(level)
}

/**
 * 引擎里那几个「产品节奏」常量。
 *
 * 都放在这里（而不是散在 if 里）是为了让测试能把 60 秒一拍换成 40 毫秒、
 * 而不必 sleep 一分钟。默认值与客户端 `HeartbeatController` 一致：
 * 60 秒一拍（服务端不需要 30 秒那么勤）、刚聊完 10 分钟不掷（[Heartbeat.COOL_DOWN_MINUTES]）。
 */
data class ProactivePolicy(
    /** 一拍多少毫秒：默认 60 秒（§3.4「服务端定时器驱动」）。 */
    val tickIntervalMs: Long = 60_000L
) {
    /** 这一拍覆盖多少分钟；用来把「每分钟概率」换算成「本拍概率」。 */
    val tickIntervalMinutes: Double get() = tickIntervalMs / 60_000.0
}

/**
 * 为什么没有主动开口（或为什么开了口）。
 *
 * 用枚举而不是裸字符串：路由层/日志/测试都按 [code] 对照，[message] 是给人看的一句中文。
 * 同时它也是 `ProactiveDecision.reason` 的类型 —— 新增原因必须同时给出 code 与文案，
 * 不会出现「日志里只有一个数字」这种情况。
 */
enum class ProactiveReason(val code: String, val message: String) {
    /** 这一拍他想开口了（唯一会返回 pending 的情况）。 */
    FIRED("fired", "这一拍他想开口了"),

    /** 人格或全局把主动消息关掉了。 */
    DISABLED("disabled", "主动消息被关掉了"),

    /** 在睡眠窗口内且开着免打扰（规范 §6.2：不回复、不唤醒、不打扰）。 */
    DND("dnd", "他在睡，而且开着免打扰"),

    /** 在睡眠窗口内。 */
    ASLEEP("asleep", "他在睡觉"),

    /** 还没有任何互动起点（刚装上、或存储层还没接）：只记下起点，这一拍不掷骰子。 */
    NO_BASELINE("no_baseline", "还没有可用的互动起点"),

    /** 刚聊完（[Heartbeat.COOL_DOWN_MINUTES] 之内）：不是概率低一点，而是根本不掷。 */
    RECENT_INTERACTION("recent_interaction", "刚聊完，先安静一会儿"),

    /** 今天主动开口的次数用完了（上限由主动程度 + 心情决定）。 */
    DAILY_CAP("daily_cap", "今天主动的次数用完了"),

    /** 掷了骰子，没中（绝大多数拍都属于这一类）。 */
    NOT_THIS_TICK("not_this_tick", "这一拍没掷中")
}

/**
 * 要落库的那条主动消息（存储层由主线接；本模块只产出它）。
 *
 * @param text 已经写好的正文（由注入的 [ProactiveTextSource] 生成；默认实现是一句兜底中文，
 *             主线接 LLM 后应替换成真正按人格写的那一句）
 * @param createdAt 决定它的时刻（毫秒）
 */
data class PendingProactive(
    val text: String,
    val personaId: String,
    val createdAt: Long
)

/**
 * 一拍的判定结果。
 *
 * @param shouldFire 要不要真的主动开口
 * @param reason 见 [ProactiveReason]；`FIRED` 时 [pending] 一定非空
 * @param targetPersonaId 这次判定针对的人格
 * @param scheduledAt **下一次值得再看一眼的时刻**（毫秒）：
 * 睡着/免打扰时是下一次醒来，刚聊完是冷却结束，每日上限用完是下一个本地零点，
 * 没掷中是下一拍；真的开口了就是 `now`。存储层可以据此做长轮询/事件下发。
 * @param probability 这一拍「想开口」的概率（已含心情放大、深夜折扣、主动程度权重与节拍换算）；
 *                    没走到掷骰子那一步时为 0，只用于日志与测试。
 * @param roll 这一拍掷出的随机数（没掷时为 `-1.0`）。
 * @param dailyCap 今天的上限（含心情修正）；`sentToday` 是今天已经开口的次数。
 */
data class ProactiveDecision(
    val shouldFire: Boolean,
    val reason: ProactiveReason,
    val targetPersonaId: String,
    val scheduledAt: Long,
    val pending: PendingProactive? = null,
    val probability: Double = 0.0,
    val roll: Double = -1.0,
    val dailyCap: Int = 0,
    val sentToday: Int = 0
)

/**
 * 主动消息正文的来源。
 *
 * 为什么做成可注入：引擎只负责「该不该开口、什么时候开口」，开口说的话要按人格 + 上下文写，
 * 那是 LLM 的活（主线接编排层时注入）。默认实现给一句克制的中文，保证「不接 LLM 也能跑通链路」，
 * 也让测试可以断言 pending 的形状而不依赖模型。
 */
fun interface ProactiveTextSource {
    fun compose(persona: PersonaState, now: Long): String

    companion object {
        /** 兜底文案：没有人格/模型时也不至于发空消息。 */
        val DEFAULT: ProactiveTextSource = ProactiveTextSource { _, _ ->
            "忽然想起你，想问问你现在在做什么。"
        }
    }
}
