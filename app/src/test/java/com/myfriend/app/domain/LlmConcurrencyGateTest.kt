package com.myfriend.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局并发闸（规格 §5.1）：3 个并发，后台最多 2 个，聊天永远能进剩下那个。
 *
 * 这些测试不依赖网络、不依赖 Android：闸门本身就是纯逻辑，
 * 如果它在这里不成立，线上表现就是「用户发完消息要等后台整理完」——那种卡顿非常难查。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmConcurrencyGateTest {

    @Test
    fun `全局最多三个 后台最多两个`() = runTest {
        val gate = LlmConcurrencyGate()

        gate.acquire(LlmLane.BACKGROUND)
        gate.acquire(LlmLane.BACKGROUND)
        assertEquals(2 to 2, gate.activeCounts())

        // 第三个后台必须在队列里等：后台名额只有 2。
        val thirdBackground = async { gate.acquire(LlmLane.BACKGROUND) }
        runCurrent()
        assertEquals(1, gate.queuedCount())
        assertFalse("第三个后台任务不应该已经拿到位", thirdBackground.isCompleted)

        // 聊天仍然能进：全局 3 里有 1 个是给它保底的。
        gate.acquire(LlmLane.CHAT)
        assertEquals(3 to 2, gate.activeCounts())

        gate.release(LlmLane.CHAT)
        runCurrent()
        assertEquals("后台名额满了，第三个仍然该等着", 1, gate.queuedCount())

        gate.release(LlmLane.BACKGROUND)
        runCurrent()
        assertTrue("后台让出一个位之后，排队的第三个应该被放行", thirdBackground.isCompleted)
        assertEquals(2 to 2, gate.activeCounts())

        gate.release(LlmLane.BACKGROUND)
        gate.release(LlmLane.BACKGROUND)
        assertEquals(0 to 0, gate.activeCounts())
    }

    @Test
    fun `后台连开五个任务最多只有两个同时在跑`() = runTest {
        val gate = LlmConcurrencyGate()
        val finish = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0

        val jobs = (1..5).map {
            async {
                gate.acquire(LlmLane.BACKGROUND)
                running++
                maxRunning = maxOf(maxRunning, running)
                // 卡在这里，模拟「任务真的在跑」；没有这个卡点，单线程测试里看不出并发峰值。
                finish.await()
                running--
                gate.release(LlmLane.BACKGROUND)
            }
        }
        runCurrent()
        // 只放行两个；其余三个还在队列里。
        assertEquals(2, gate.activeCounts().first)
        assertEquals(3, gate.queuedCount())
        assertEquals(2, maxRunning)

        finish.complete(Unit)
        jobs.awaitAll()
        assertEquals("后台并发峰值必须 ≤ 2", 2, maxRunning)
        assertEquals(0 to 0, gate.activeCounts())
        assertEquals(0, gate.queuedCount())
    }

    @Test
    fun `聊天优先：只剩一个位时先给聊天 而不是排队更早的后台`() = runTest {
        val gate = LlmConcurrencyGate()
        gate.acquire(LlmLane.BACKGROUND)
        gate.acquire(LlmLane.BACKGROUND)
        gate.acquire(LlmLane.CHAT) // 全局已满

        // 后台先排队，聊天后排队：这是最坏顺序，聊天仍该先被放行。
        val backgroundWaiter = async { gate.acquire(LlmLane.BACKGROUND) }
        runCurrent()
        val chatWaiter = async { gate.acquire(LlmLane.CHAT) }
        runCurrent()

        // 放掉聊天自己占的那个位：只剩 1 个全局位，应该按「聊天优先」给排队的聊天。
        gate.release(LlmLane.CHAT)
        runCurrent()
        assertTrue("聊天应该先被放行", chatWaiter.isCompleted)
        assertFalse("后台还在等它的名额", backgroundWaiter.isCompleted)

        gate.release(LlmLane.CHAT)
        gate.release(LlmLane.BACKGROUND)
        backgroundWaiter.await()
        gate.release(LlmLane.BACKGROUND)
        // 还剩下测试开头自己占着的那个后台位。
        gate.release(LlmLane.BACKGROUND)
        assertEquals(0 to 0, gate.activeCounts())
    }

    @Test
    fun `等太久可以超时放弃 排队的位置会被清掉`() = runTest {
        val gate = LlmConcurrencyGate()
        gate.acquire(LlmLane.BACKGROUND)
        gate.acquire(LlmLane.BACKGROUND)

        val admitted = withTimeoutOrNull(100L) { gate.acquire(LlmLane.BACKGROUND); true }
        assertFalse("位置满了又等不到，应该超时返回 null", admitted == true)
        assertEquals("超时的等待者必须从队列里删掉，不能永久占位", 0, gate.queuedCount())

        gate.release(LlmLane.BACKGROUND)
        gate.release(LlmLane.BACKGROUND)
        assertEquals(0 to 0, gate.activeCounts())
    }

    @Test
    fun `多余的 release 不会把计数压成负数`() = runTest {
        val gate = LlmConcurrencyGate()
        gate.release(LlmLane.BACKGROUND)
        gate.release(LlmLane.CHAT)
        // 计数下限是 0：调用方写错时，不能让之后所有请求都误以为还有空位/永远没空位。
        assertEquals(0 to 0, gate.activeCounts())
        gate.acquire(LlmLane.CHAT)
        assertEquals(1 to 0, gate.activeCounts())
        gate.release(LlmLane.CHAT)
    }

    @Test
    fun `后台名额是 2 时 单个后台任务也进不了第三格`() = runTest {
        val gate = LlmConcurrencyGate(globalLimit = 3, backgroundLimit = 2)
        repeat(2) { gate.acquire(LlmLane.BACKGROUND) }
        // 即便全局还有 1 个位，后台也不许再进人——那个位是留给聊天的。
        val denied = withTimeoutOrNull(50L) { gate.acquire(LlmLane.BACKGROUND); true }
        assertFalse(denied == true)
        gate.release(LlmLane.BACKGROUND)
        gate.release(LlmLane.BACKGROUND)
    }
}
