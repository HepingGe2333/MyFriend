package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「当前打开的会话」的原子语义（审计 P1-1）。
 *
 * 真实触发路径：聊天页里切换人格 → `navigate(chat(new)) { popUpTo(HOME) }`。
 * 新 ViewModel 的 `init` 先写入新 sessionId，旧 `NavBackStackEntry` 的 ViewModel
 * 随后才 `onCleared`。如果清空是无条件的，最终值是 null：用户正看着的会话被判成
 * 「没开着」——未读 +1，前台还会弹一条「你正在读的消息」的系统通知。
 */
class OpenSessionTrackerTest {

    @Test
    fun `打开与清空同一段会话`() {
        val tracker = OpenSessionTracker()
        tracker.open("A")
        assertTrue(tracker.isOpen("A"))
        assertEquals("A", tracker.openSessionId)

        assertTrue("当前仍是 A，应当清掉", tracker.clearIfOpen("A"))
        assertNull(tracker.openSessionId)
        assertFalse(tracker.isOpen("A"))
    }

    @Test
    fun `旧会话的清空不会影响新会话`() {
        val tracker = OpenSessionTracker()
        tracker.open("A")
        // 转场：新 VM 先写入 B。
        tracker.open("B")
        // 旧 VM 的 onCleared 迟到：compare-and-clear 必须什么都不做。
        assertFalse("A 已经不是当前会话，不能清", tracker.clearIfOpen("A"))
        assertEquals("B", tracker.openSessionId)
        assertTrue(tracker.isOpen("B"))
    }

    @Test
    fun `已经清空过的会话再清一次是幂等的`() {
        val tracker = OpenSessionTracker()
        tracker.open("A")
        assertTrue(tracker.clearIfOpen("A"))
        assertFalse("已经清空，不该再算清成功", tracker.clearIfOpen("A"))
        assertNull(tracker.openSessionId)
    }

    @Test
    fun `显式清空用于删除全部数据`() {
        val tracker = OpenSessionTracker()
        tracker.open("A")
        tracker.clearAll()
        assertNull(tracker.openSessionId)
    }

    /**
     * 并发交错时最终状态**必须**是新会话。
     *
     * 无论「旧 VM 清空」排在「新 VM 写入」之前还是之后：
     * - 先清后写 → 最终 B；
     * - 先写后清 → 清的是 A，不匹配 B → 最终仍是 B。
     * 这正是原子 compare-and-clear 要保证的性质。
     */
    @Test
    fun `并发打开新会话与清空旧会话 最终总是新会话`() {
        val failures = AtomicInteger(0)
        repeat(200) {
            val tracker = OpenSessionTracker()
            tracker.open("A")
            val start = CountDownLatch(1)
            val writer = Thread {
                start.await()
                tracker.open("B")
            }
            val clearer = Thread {
                start.await()
                tracker.clearIfOpen("A")
            }
            writer.start()
            clearer.start()
            start.countDown()
            writer.join()
            clearer.join()
            if (tracker.openSessionId != "B") failures.incrementAndGet()
        }
        assertEquals("任何交错下当前会话都必须是 B", 0, failures.get())
    }
}
