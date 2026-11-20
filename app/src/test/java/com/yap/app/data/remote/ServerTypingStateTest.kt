package com.yap.app.data.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端模式「正在输入」的进程级状态（`spec/experience/README.md` §8）。
 *
 * 这一层只做「哪些会话正在输入」的瞬时集合：不落库、不补动画；`stopped` 幂等，
 * 空 id 不占位（不会把指示器挂到一个空会话上）。
 */
class ServerTypingStateTest {

    @Test
    fun `started 与 stopped 成对更新集合`() {
        val state = ServerTypingState()
        assertTrue(state.typingSessions.value.isEmpty())
        state.typingStarted("s1")
        assertTrue(state.typingSessions.value.contains("s1"))
        state.typingStopped("s1")
        assertTrue(state.typingSessions.value.isEmpty())
    }

    @Test
    fun `多个会话互不影响`() {
        val state = ServerTypingState()
        state.typingStarted("s1")
        state.typingStarted("s2")
        assertEquals(setOf("s1", "s2"), state.typingSessions.value)
        state.typingStopped("s1")
        assertEquals(setOf("s2"), state.typingSessions.value)
    }

    @Test
    fun `stopped 幂等 重复收也不会抛异常`() {
        val state = ServerTypingState()
        state.typingStopped("s1")
        state.typingStopped("s1")
        assertTrue(state.typingSessions.value.isEmpty())
        state.typingStarted("s1")
        state.typingStarted("s1")
        assertEquals(1, state.typingSessions.value.size)
        state.typingStopped("s1")
        state.typingStopped("s1")
        assertTrue(state.typingSessions.value.isEmpty())
    }

    @Test
    fun `空 id 与空白 id 不占位`() {
        val state = ServerTypingState()
        state.typingStarted("")
        state.typingStarted("   ")
        state.typingStopped("")
        assertTrue(state.typingSessions.value.isEmpty())
    }

    @Test
    fun `observe 只观察指定会话`() = runBlocking {
        val state = ServerTypingState()
        assertFalse(state.observe("s1").first())
        state.typingStarted("s2")
        assertFalse("别的会话在输入不该点亮本会话", state.observe("s1").first())
        state.typingStarted("s1")
        assertTrue(state.observe("s1").first())
        state.typingStopped("s1")
        assertFalse(state.observe("s1").first())
    }

    @Test
    fun `clearAll 用于换服务端时收掉全部指示`() {
        val state = ServerTypingState()
        state.typingStarted("s1")
        state.typingStarted("s2")
        state.clearAll()
        assertTrue(state.typingSessions.value.isEmpty())
    }
}
