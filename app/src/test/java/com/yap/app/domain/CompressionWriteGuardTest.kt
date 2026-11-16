package com.yap.app.domain

import com.yap.app.domain.CompressionWriteGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 历史压缩写回前的判断（审计 P1-2）。
 *
 * 关键回归：**清空上下文之后压缩结果不许落库**。清空会 bump 世代号，
 * 于是压缩开始时记下的世代立刻过期，写回前被丢弃。
 */
class CompressionWriteGuardTest {

    @Test
    fun `期间什么都没变 可以写回`() {
        assertEquals(
            Decision.APPLY,
            decide(startGeneration = 0, currentGeneration = 0, startUpTo = null, currentUpTo = null)
        )
    }

    @Test
    fun `期间只是又多了几条新消息 仍然可以写回`() {
        // 用户发下一条消息不会改变摘要边界：新消息不在这一批的覆盖范围里，覆盖仍然连续。
        assertEquals(
            Decision.APPLY,
            decide(
                startGeneration = 3,
                currentGeneration = 3,
                startUpTo = 1_000,
                currentUpTo = 1_000,
                startCount = 80,
                currentCount = 82
            )
        )
    }

    @Test
    fun `清空上下文之后（世代过期）丢弃`() {
        assertEquals(
            Decision.DISCARD_STALE_GENERATION,
            decide(startGeneration = 0, currentGeneration = 1)
        )
    }

    @Test
    fun `会话被删掉之后丢弃`() {
        assertEquals(
            Decision.DISCARD_SESSION_GONE,
            decide(startGeneration = 0, currentGeneration = 0, sessionExists = false)
        )
    }

    @Test
    fun `摘要边界被别的写入改过 丢弃`() {
        assertEquals(
            Decision.DISCARD_SUMMARY_CHANGED,
            decide(
                startGeneration = 1,
                currentGeneration = 1,
                startUpTo = 1_000,
                currentUpTo = 2_000
            )
        )
    }

    @Test
    fun `消息变少（清空或删消息）丢弃`() {
        assertEquals(
            Decision.DISCARD_MESSAGES_CHANGED,
            decide(
                startGeneration = 1,
                currentGeneration = 1,
                startUpTo = 1_000,
                currentUpTo = 1_000,
                startCount = 80,
                currentCount = 0
            )
        )
    }

    @Test
    fun `0 与 null 都表示从来没压过`() {
        assertEquals(
            Decision.APPLY,
            decide(
                startGeneration = 0,
                currentGeneration = 0,
                startUpTo = 0L,
                currentUpTo = null
            )
        )
    }

    private fun decide(
        startGeneration: Long,
        currentGeneration: Long,
        sessionExists: Boolean = true,
        startUpTo: Long? = null,
        currentUpTo: Long? = null,
        startCount: Int = 100,
        currentCount: Int = 100
    ): Decision = CompressionWriteGuard.decide(
        startGeneration = startGeneration,
        currentGeneration = currentGeneration,
        sessionExists = sessionExists,
        startSummaryUpTo = startUpTo,
        currentSummaryUpTo = currentUpTo,
        startMessageCount = startCount,
        currentMessageCount = currentCount
    )
}
