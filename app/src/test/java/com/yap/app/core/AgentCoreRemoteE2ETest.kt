package com.yap.app.core

import com.yap.app.domain.AgentCore
import com.yap.app.domain.InProcessAgentCore
import com.yap.app.domain.ProactiveCapInput
import com.yap.app.domain.SleepInput
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 可选的真机/本机联调测试：真的打到一个运行中的 `agentd`。
 *
 * 为什么放在单测里而不是丢掉：客户端/服务端的字段名、路径、默认值只有「真的连一次」才能证明。
 * 但 CI 里没有 agentd 进程，所以：
 * - 没有 `AGENTCORE_E2E_URL`（或 Gradle `-PagentCoreE2eUrl=...`）时，[e2eUrl] 为空 → 测试直接返回；
 * - 设置了地址时，用 `agentd/build/install/agentd/bin/agentd --port 8788` 起服务后跑：
 *   `-PagentCoreE2eUrl=http://127.0.0.1:8788`。
 *
 * 关键设计：[InProcessAgentCore] 只用于算期望值；**远程失败不会被悄悄回退**——
 * 这里的 fallback 是一个「一被调用就抛异常」的实现，所以只要 4 个决策有一个回退了，
 * 测试就会红，而不是因为「回退结果相等」假装通过。
 */
class AgentCoreRemoteE2ETest {

    private val e2eUrl: String = System.getenv("AGENTCORE_E2E_URL")?.trim().orEmpty()

    @Test
    fun `all four decisions really go over the wire to agentd`() {
        if (e2eUrl.isBlank()) {
            // 没有指定真实服务：跳过（不是失败）。跑法见类注释。
            return
        }

        val logs = mutableListOf<String>()
        val core = RemoteAgentCoreClient(
            baseUrl = e2eUrl,
            fallback = object : AgentCore {
                override fun sleepDecision(input: SleepInput) = error("远程失败：sleepDecision 回退了进程内")
                override fun deferPlan(minutes: Int?, reason: String?, now: Long) =
                    error("远程失败：deferPlan 回退了进程内")

                override fun proactiveCap(input: ProactiveCapInput) =
                    error("远程失败：proactiveCap 回退了进程内")

                override fun clampToolArgs(tool: String, args: Map<String, Any?>) =
                    error("远程失败：clampToolArgs 回退了进程内")
            },
            logger = AgentCoreLogger { message, _ -> logs += message },
            httpClient = OkHttpClient(),
            timeoutMillis = 5_000L
        )

        val sleepInput = SleepInput(
            sleepEnabled = true,
            dndOnSleep = false,
            sleepMinutes = 1410,
            wakeMinutes = 480,
            nowMinutes = 120,
            userMessagesInWindow = 6,
            wakeThreshold = 6
        )
        assertEquals(InProcessAgentCore.sleepDecision(sleepInput), core.sleepDecision(sleepInput))

        assertEquals(
            InProcessAgentCore.deferPlan(90, "现在不想说话", 1700000000000L),
            core.deferPlan(90, "现在不想说话", 1700000000000L)
        )

        val capInput = ProactiveCapInput(level = 3, moodLabel = "雀跃", moodIntensity = 4)
        assertEquals(InProcessAgentCore.proactiveCap(capInput), core.proactiveCap(capInput))

        assertEquals(
            InProcessAgentCore.clampToolArgs("remember", mapOf("importance" to 9)),
            core.clampToolArgs("remember", mapOf("importance" to 9))
        )

        assertEquals("远程调用不应该回退（日志：$logs）", emptyList<String>(), logs)
    }
}
