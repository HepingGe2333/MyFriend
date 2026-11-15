package com.myfriend.app.domain

/**
 * 「两端必须一致的 agent 决策」的唯一接口（0.11.0 架构）。
 *
 * 为什么需要这一层：这些判断（此刻在不在睡、这一轮先不回要隔多久、今天还能主动几次、
 * 模型给的数字该夹到哪）本身是**毫秒级的纯计算**，但过去在 Android 里是一份、在
 * `agentd` 里是另一份（靠 `kotlin.srcDir` + exclude 复用源码，排除列表一漂就会少一个文件）。
 * 现在实现只有一份：[:agentcore](../agentcore) 里的 [InProcessAgentCore]。
 *
 * 接口刻意做成**同步纯函数**，不用 suspend、不碰协程：
 * - 这些决策是纯计算，没有 IO；塞进 suspend 只会让调用方多写一层 launch，
 *   而它们恰恰要在「生成回复之前」立刻拿到结果；
 * - 远程实现（`RemoteAgentCoreClient`）负责把同步调用包成一次带超时的 HTTP 请求，
 *   失败时回退到进程内实现——**决策层不能因为服务不可用就让聊天挂掉**。
 *
 * 字段名与 `agentd` 的 HTTP 契约（`/v1/core/…`）逐字一致，见 [AgentCoreProtocol]。
 */
interface AgentCore {

    /**
     * 睡眠 / 免打扰 / 唤醒阈值判定。
     *
     * 语义与 0.10.x 的 `SleepPolicy.evaluate` 完全一致，只是把参数收成了一个数据类。
     */
    fun sleepDecision(input: SleepInput): SleepDecision

    /**
     * `defer_reply` 的时间计划：缺省 45 分钟、夹 5–720、决策窗口 +30 分钟、理由截 80 字。
     *
     * @param minutes 模型给的分钟数（可空 = 用缺省值）
     * @param reason 模型给的理由（可空；会 trim 并截断）
     * @param now 现在（毫秒时间戳，由调用方显式传入）
     */
    fun deferPlan(minutes: Int?, reason: String?, now: Long): DeferPlan

    /**
     * 每日主动上限：主动程度 1–5（越界夹取），心情再挪一格。
     *
     * 这是规格里写的简易入口：**只有强度、没有心情标签**时无法判断 valence / energy，
     * 所以心情修正这一格不会生效。需要完整心情修正时用 [proactiveCap] 的三参数重载
     * （Android 的 `AgentLoop` / 心跳走的就是那条，行为与 0.10.x 逐字一致）。
     */
    fun proactiveCap(level: Int, moodIntensity: Int?): Int =
        proactiveCap(ProactiveCapInput(level = level, moodIntensity = moodIntensity))

    /**
     * 每日主动上限（含心情修正）的完整入口。
     *
     * [ProactiveCapInput.moodLabel] 为空 / 空白时按「没有心情」处理，只按主动程度换算。
     */
    fun proactiveCap(input: ProactiveCapInput): Int

    /**
     * 工具参数的数值夹取：只夹已知的数值键（`importance` / `count` / `minutes`），
     * 其余键原样保留，缺省值补在结果里。
     *
     * 不认识的工具**不在这里拒绝**（原样返回）：拒绝是服务端 HTTP 层的事
     * （`agentd` 对它返回 400 `unknown_tool`），客户端不该替服务端定义工具表。
     */
    fun clampToolArgs(tool: String, args: Map<String, Any?>): Map<String, Any?>

    companion object {
        /** 工具名：与 `ToolCatalog` / `agentd` 的 `/v1/core/tool-args` 一致。 */
        const val TOOL_REMEMBER = "remember"
        const val TOOL_WEB_SEARCH = "web_search"
        const val TOOL_DEFER_REPLY = "defer_reply"
    }
}

/**
 * 睡眠判定的入参。字段名与 `agentd` 的 `/v1/core/sleep-decision` 请求体逐字一致。
 *
 * @param sleepEnabled 人格是否主动睡觉；false = 任何时间都回
 * @param dndOnSleep 睡着时是否免打扰（只由模型写入，用户没有开关）
 * @param sleepMinutes 几点睡（当天第几分钟，可空 = 默认窗口）
 * @param wakeMinutes 几点醒（当天第几分钟，可空 = 默认窗口）
 * @param nowMinutes 现在（当天第几分钟，会先取模 1440）
 * @param userMessagesInWindow 最近 30 分钟内用户发了几条（含刚发的这一条）
 * @param wakeThreshold 连续来几条消息会被叫醒（1–20，越界夹取）
 */
data class SleepInput(
    val sleepEnabled: Boolean,
    val dndOnSleep: Boolean,
    val sleepMinutes: Int? = null,
    val wakeMinutes: Int? = null,
    val nowMinutes: Int = 0,
    val userMessagesInWindow: Int = 0,
    val wakeThreshold: Int = SleepPolicy.MIN_WAKE_THRESHOLD
)

/**
 * 睡眠判定的结果。字段名与 `agentd` 的响应体一致（`marksRead` 是 0.11.0 新增的兼容字段：
 * 少了它，远程模式会在「免打扰」时悄悄把已读补回去）。
 *
 * @param wake 这一轮要不要真的去生成回复（= `SleepPolicy.Decision.shouldReply`）
 * @param moodRelaxed 睡眠中被叫醒了（要留下心情恢复的痕迹）
 * @param reason 给界面/日志看的一句中文
 * @param marksRead 要不要给用户消息写「已读」；免打扰是唯一例外（规格 §6.2）
 */
data class SleepDecision(
    val wake: Boolean,
    val moodRelaxed: Boolean,
    val reason: String,
    val marksRead: Boolean
) {
    /**
     * 此刻在他的睡眠窗口里。
     *
     * 注意它**不能**用 `!wake` 推：被叫醒时 `wake = true`，但人仍在睡眠窗口里。
     * `asleep = !wake || moodRelaxed` 正好覆盖三种情况（醒着 / 睡着没醒 / 睡着被叫醒）。
     */
    val asleep: Boolean get() = !wake || moodRelaxed

    /** 睡眠中开着免打扰（= 连已读都不留）。 */
    val dnd: Boolean get() = !marksRead
}

/**
 * `defer_reply` 的时间计划。字段名与 `agentd` 的响应体一致。
 *
 * @param earliestAt 到点后最早可以开始想这件事
 * @param latestAt 决策窗口的终点（到点不是闹钟）
 * @param minutes 夹取后的分钟数
 * @param reason trim / 截断后的理由（可空输入会得到空串）
 */
data class DeferPlan(
    val earliestAt: Long,
    val latestAt: Long,
    val minutes: Int,
    val reason: String
)

/**
 * 每日主动上限的完整入参。
 *
 * @param level 人格的 proactiveLevel（1–5，越界夹取）
 * @param moodLabel 当前心情标签（可空；空 = 不修正上限）
 * @param moodIntensity 心情强度 1–5（可空；缺省按 2）
 */
data class ProactiveCapInput(
    val level: Int,
    val moodLabel: String? = null,
    val moodIntensity: Int? = null
)
