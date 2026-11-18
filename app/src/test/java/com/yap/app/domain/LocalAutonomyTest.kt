package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「服务端模式下客户端只同步」的核心策略单测（0.13）。
 *
 * 服务端模式下他在服务端那边独立运行（主动消息/情绪/进化/日记提醒）。本机再跑一套本地
 * 自主逻辑就会出现双份消息、双份情绪判定。这里把五个入口共用的判定逐条钉住：
 * 1. 服务端模式一票否决（即使本机「常驻后台」开关是开着的）；
 * 2. 本地模式行为完全不变（开关开 → 跑，关 → 停，读不出来 → 保持现状）；
 * 3. 心跳/兜底 Worker 的「这一轮该不该跑」与服务端模式一致；
 * 4. 诊断日志里能看见 `SERVER_MODE` 这个稳定原因（真机排查"为什么不主动"时不会误判成故障）。
 */
class LocalAutonomyTest {

    @Test
    fun `服务端模式下即使常驻后台开着也不跑本地自主逻辑`() {
        assertEquals(
            LocalAutonomy.Gate.STOP,
            LocalAutonomy.proactiveGate(agentLoopEnabled = true, serverModeEnabled = true)
        )
        assertFalse(LocalAutonomy.localAutonomyAllowed(serverModeEnabled = true))
        assertFalse(LocalAutonomy.shouldRunLocalTick(serverModeEnabled = true))
    }

    @Test
    fun `本地模式下开关开着就跑`() {
        assertEquals(
            LocalAutonomy.Gate.RUN,
            LocalAutonomy.proactiveGate(agentLoopEnabled = true, serverModeEnabled = false)
        )
        assertTrue(LocalAutonomy.localAutonomyAllowed(serverModeEnabled = false))
        assertTrue(LocalAutonomy.shouldRunLocalTick(serverModeEnabled = false))
    }

    @Test
    fun `本地模式下开关关掉就停`() {
        assertEquals(
            LocalAutonomy.Gate.STOP,
            LocalAutonomy.proactiveGate(agentLoopEnabled = false, serverModeEnabled = false)
        )
    }

    @Test
    fun `读不到开关时保持现状而不是当成关闭`() {
        assertEquals(
            LocalAutonomy.Gate.KEEP,
            LocalAutonomy.proactiveGate(agentLoopEnabled = null, serverModeEnabled = false)
        )
    }

    @Test
    fun `服务端模式优先于读不到开关`() {
        assertEquals(
            LocalAutonomy.Gate.STOP,
            LocalAutonomy.proactiveGate(agentLoopEnabled = null, serverModeEnabled = true)
        )
    }

    @Test
    fun `心跳早退的中文原因能映射成稳定的 SERVER_MODE`() {
        assertEquals(
            ProactiveDiagnostics.Reason.SERVER_MODE,
            ProactiveDiagnostics.fromBeatWhy("服务端模式：主动由服务端负责")
        )
        // AgentLoop 的 summary 走同一套词表：不能因为两边措辞不同就变成 UNKNOWN。
        assertEquals(
            ProactiveDiagnostics.Reason.SERVER_MODE,
            ProactiveDiagnostics.fromTickSummary("服务端模式：主动由服务端负责（客户端只同步）")
        )
    }

    @Test
    fun `服务端模式的一拍分类是 SERVER_MODE 而不是故障`() {
        val reason = ProactiveDiagnostics.classify(
            fired = false,
            acted = false,
            why = "服务端模式：主动由服务端负责"
        )
        assertEquals(ProactiveDiagnostics.Reason.SERVER_MODE, reason)
    }

    @Test
    fun `诊断日志里带 SERVER_MODE 与一句人话`() {
        val line = ProactiveDiagnostics.logLine(
            fired = false,
            acted = false,
            reason = ProactiveDiagnostics.Reason.SERVER_MODE
        )
        assertTrue(line.contains("reason=SERVER_MODE"))
        assertTrue(line.contains("服务端"))
    }
}
