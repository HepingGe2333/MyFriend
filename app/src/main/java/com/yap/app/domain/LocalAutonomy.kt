package com.yap.app.domain

/**
 * 「本地自主逻辑该不该跑」的**唯一判定**（0.13）。
 *
 * 服务端模式下的分工是硬约束：他在服务端那边独立运行（主动消息、情绪/进化、日记提醒），
 * 客户端连上只同步——拉状态/事件、发用户消息、同步写路径。**不允许**再跑一套本地自主逻辑，
 * 否则用户会收到双份主动消息、心情被两条链路各判一次、日记提醒响两遍。
 *
 * 为什么单独抽成纯函数：
 * - 心跳、常驻循环服务、WorkManager 兜底、主动规则 Worker、记忆整理 Worker 有五个入口，
 *   每个各写一遍 `isEnabled()` 判断，迟早会漏掉一个（漏掉的那个就会在服务端模式下偷偷跑）；
 * - 这是「只同步」的核心策略，值得在 JVM 单测里逐条钉住，而不是只在真机上看有没有双份消息。
 *
 * 注意：[Gate.KEEP] 只用于「开关读不出来」这一种情况——**读不到设置不等于用户关掉了它**，
 * 服务端模式不是这种情况：服务端模式是明确的运行状态，必须 STOP。
 */
object LocalAutonomy {

    /** 本地主动逻辑的三态闸门（与 `ProactiveDiagnostics.LoopSwitch` 同形，多了服务端优先）。 */
    enum class Gate {
        /** 允许本地自主逻辑运行。 */
        RUN,

        /** 明确停止：服务端模式，或用户关了常驻循环。 */
        STOP,

        /** 读不出来：保持现状，不做任何启停。 */
        KEEP
    }

    /**
     * 服务端模式下所有本地自主逻辑都停：心跳、AgentLoop、情绪判定、进化评估、日记提醒、记忆整理。
     *
     * 一个函数回答所有入口的问题，避免「这个入口忘了判断」。
     */
    fun localAutonomyAllowed(serverModeEnabled: Boolean): Boolean = !serverModeEnabled

    /**
     * 常驻循环（前台服务 + 15 分钟兜底）该做什么。
     *
     * @param agentLoopEnabled 用户拨的「常驻后台」开关；null = 读不出来
     * @param serverModeEnabled 当前是否服务端模式（地址可用且开关打开）
     */
    fun proactiveGate(agentLoopEnabled: Boolean?, serverModeEnabled: Boolean): Gate = when {
        // 服务端模式优先级最高：即使本机开关是开着的，也不能起本地循环。
        serverModeEnabled -> Gate.STOP
        agentLoopEnabled == true -> Gate.RUN
        agentLoopEnabled == false -> Gate.STOP
        else -> Gate.KEEP
    }

    /**
     * 一轮「他主动想」的入口该不该继续（心跳/兜底 Worker/主动规则 Worker 共用）。
     *
     * 与 [proactiveGate] 的区别：这里不关心用户开关（那是服务/Worker 的调度层负责的），
     * 只回答「服务端模式下必须一票否决」。
     */
    fun shouldRunLocalTick(serverModeEnabled: Boolean): Boolean = !serverModeEnabled
}
