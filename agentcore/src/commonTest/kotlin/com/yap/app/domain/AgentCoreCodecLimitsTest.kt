package com.yap.app.domain

import com.yap.app.core.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * `AgentCoreCodec` 在「坏/超大输入」下的行为（0.11.1 编解码健壮性加固）。
 *
 * 规则：能明确拒绝的就不猜默认值；错误必须是 [AgentCoreProtocolException]（调用方据此
 * 回 400 / 回退进程内），而不是 `NumberFormatException` / `StackOverflowError` 之类。
 * 另外远端错误体读回来也要有界（审计 P2-8：服务端可控文本会进客户端日志）。
 */
class AgentCoreCodecLimitsTest {

    private fun fails(block: () -> Unit): AgentCoreProtocolException {
        try {
            block()
        } catch (expected: AgentCoreProtocolException) {
            return expected
        }
        throw AssertionError("预期抛 AgentCoreProtocolException，但没有抛")
    }

    @Test
    fun `deeply nested request is a protocol error`() {
        val nested = "[".repeat(100) + "]".repeat(100)
        val error = fails { AgentCoreCodec.decodeSleepInput("""{"sleepEnabled":$nested}""") }
        assertTrue(error.message.orEmpty().contains("嵌套过深"), "错误文案：${error.message}")
    }

    @Test
    fun `over long string field is a protocol error`() {
        val longText = "x".repeat(Json.MAX_STRING_LENGTH + 1)
        val error = fails { AgentCoreCodec.decodeDeferPlan("""{"reason":"$longText"}""") }
        assertTrue(error.message.orEmpty().contains("字符串过长"), "错误文案：${error.message}")
    }

    @Test
    fun `huge exponent where an int is required is a protocol error`() {
        val error = fails {
            AgentCoreCodec.decodeSleepInput(
                """{"sleepEnabled":true,"dndOnSleep":false,"nowMinutes":1e308,
                   "userMessagesInWindow":0,"wakeThreshold":6}"""
            )
        }
        assertTrue(error.message.orEmpty().contains("必须是整数"), "错误文案：${error.message}")
    }

    @Test
    fun `nan literal is a protocol error`() {
        val error = fails { AgentCoreCodec.decodeDeferPlan("""{"earliestAt":NaN,"latestAt":0,"minutes":45}""") }
        assertTrue(error.message.orEmpty().contains("不是合法 JSON"), "错误文案：${error.message}")
    }

    @Test
    fun `duplicate keys are a protocol error`() {
        val error = fails { AgentCoreCodec.decodeProactiveCap("""{"dailyCap":3,"dailyCap":5}""") }
        assertTrue(error.message.orEmpty().contains("重复键"), "错误文案：${error.message}")
    }

    @Test
    fun `decoded error message is truncated`() {
        val message = "错".repeat(AgentCoreCodec.MAX_ERROR_MESSAGE_CHARS + 500)
        val decoded = AgentCoreCodec.decodeErrorMessage(
            """{"error":{"message":"$message","code":"internal_error"}}"""
        )
        val text = decoded.orEmpty()
        assertTrue(text.length <= AgentCoreCodec.MAX_ERROR_MESSAGE_CHARS + 1, "错误文案没被截断：${text.length}")
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun `oversized error body is ignored instead of parsed`() {
        assertNull(AgentCoreCodec.decodeErrorMessage("x".repeat(AgentCoreCodec.MAX_ERROR_BODY_CHARS + 1)))
        assertNull(AgentCoreCodec.decodeErrorMessage("not json"))
        assertNull(AgentCoreCodec.decodeErrorMessage(null))
    }

    @Test
    fun `normal error body still round trips`() {
        assertEquals(
            "字段 now 必须是整数（invalid_argument）",
            AgentCoreCodec.decodeErrorMessage(
                """{"error":{"message":"字段 now 必须是整数","code":"invalid_argument"}}"""
            )
        )
    }
}
