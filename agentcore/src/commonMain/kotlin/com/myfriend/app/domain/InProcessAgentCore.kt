package com.myfriend.app.domain

import kotlin.math.floor

/**
 * [AgentCore] 的进程内实现：**直接委托**现有纯函数，一行逻辑都不复制。
 *
 * 为什么这样写：`SleepPolicy` / `DeferReply` / `ProactiveLevel` / `MoodBehavior` / `ToolArgClamp`
 * 是 0.9–0.10 就用契约用例钉住过的唯一真源（`spec/agent-core/cases/` 下的 JSON，iOS 读同一批文件）。
 * 抽模块的目标是「换一条路走」，不是重写规则；所以这里只做参数整形与结果整形，
 * 规则本身仍在原来的 object 里，改规则只改一处、两端同时生效。
 *
 * 纯 Kotlin/JVM：没有 Android、没有 Room、没有协程、没有序列化库。
 */
object InProcessAgentCore : AgentCore {

    override fun sleepDecision(input: SleepInput): SleepDecision {
        val decision = SleepPolicy.evaluate(
            sleepEnabled = input.sleepEnabled,
            sleepMinutes = input.sleepMinutes,
            wakeMinutes = input.wakeMinutes,
            minuteOfDay = input.nowMinutes,
            dndOnSleep = input.dndOnSleep,
            recentUserMessages = input.userMessagesInWindow,
            wakeThreshold = input.wakeThreshold
        )
        // 「为什么没回」这句中文原本长在 agentd 的 HTTP 层；它两端必须一致（远程/进程内
        // 不该给出两种说法），所以收进决策层。
        val wakeClock = SleepPolicy.wakeClockText(
            sleepEnabled = input.sleepEnabled,
            sleepMinutes = input.sleepMinutes,
            wakeMinutes = input.wakeMinutes
        )
        val reason = when {
            !decision.asleep -> "醒着，会正常回复"
            decision.dnd -> "免打扰中，不会醒"
            decision.woken -> MoodRecovery.WAKE_REASON
            wakeClock != null -> "睡着了 · $wakeClock 醒"
            else -> "睡着了"
        }
        return SleepDecision(
            wake = decision.shouldReply,
            moodRelaxed = decision.woken,
            reason = reason,
            marksRead = decision.marksRead
        )
    }

    override fun deferPlan(minutes: Int?, reason: String?, now: Long): DeferPlan {
        val plan = DeferReply.plan(minutes = minutes, reason = reason, now = now)
        return DeferPlan(
            earliestAt = plan.earliestAt,
            latestAt = plan.latestAt,
            minutes = plan.minutes,
            reason = plan.reason
        )
    }

    override fun proactiveCap(input: ProactiveCapInput): Int {
        val baseCap = ProactiveLevel.dailyCap(input.level)
        val label = input.moodLabel?.trim().orEmpty()
        // 没有标签就没有 valence / energy，强行按强度挪一格等于瞎猜 —— 只按主动程度换算。
        if (label.isBlank()) return baseCap
        // 与 agentd 0.10.x 的 `/v1/core/proactive-cap` 一致：只用 label + intensity 换算 tuning。
        // 深夜折扣会改 tick 间隔 / 浏览倾向，但不改 dailyMessageCapDelta，所以 now=0 不影响上限。
        val tuning = MoodBehavior.from(
            MoodState(label = label, intensity = input.moodIntensity ?: 2),
            now = 0L
        )
        return MoodBehavior.effectiveDailyCap(baseCap, tuning)
    }

    override fun clampToolArgs(tool: String, args: Map<String, Any?>): Map<String, Any?> {
        // 保留调用方给的顺序与全部键：模型多写的字段不能因为夹一次就丢掉。
        val out = LinkedHashMap<String, Any?>(args)
        when (tool) {
            AgentCore.TOOL_REMEMBER -> out["importance"] = ToolArgClamp.clampImportance(
                intArgument(args, "importance") ?: ToolArgClamp.IMPORTANCE_DEFAULT
            )

            AgentCore.TOOL_WEB_SEARCH -> out["count"] = ToolArgClamp.clampCount(
                intArgument(args, "count") ?: ToolArgClamp.COUNT_DEFAULT
            )

            AgentCore.TOOL_DEFER_REPLY -> out["minutes"] = ToolArgClamp.clampDeferMinutes(
                intArgument(args, "minutes") ?: ToolArgClamp.MINUTES_DEFAULT
            )

            // 不认识的工具：不拒绝，原样返回（拒绝是服务端 HTTP 层的事）。
            else -> return out
        }
        return out
    }

    /**
     * 从参数表里取一个整数。
     *
     * 只接受 JSON 数值语义的类型（Int / Long / 整数值的 Double / 数字字符串）：
     * `null`、布尔、对象一律当作「没给」，回落到缺省值——模型偶尔会把 `importance`
     * 写成 `"5"`，这种能救就救；写成 `true` 的，按缺省处理比按 1 处理更不容易误导。
     */
    private fun intArgument(args: Map<String, Any?>, key: String): Int? = when (val raw = args[key]) {
        is Int -> raw
        is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        is Double -> raw
            .takeIf { it.isFinite() && it == floor(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }
            ?.toInt()

        is String -> raw.trim().toIntOrNull()
        else -> null
    }
}
