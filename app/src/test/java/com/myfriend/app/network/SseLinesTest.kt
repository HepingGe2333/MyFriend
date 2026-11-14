package com.myfriend.app.network

import com.myfriend.app.domain.NetworkErrorKind
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 单行上限。
 *
 * 这一行是「长会话内存」之外唯一能让 App 在内存里炸掉的地方：服务端异常时可能不停发
 * 一行不掉换行的数据。这里用 okio 的 Buffer 直接钉住边界。
 */
class SseLinesTest {

    @Test
    fun `普通一行原样读出来`() {
        val source = Buffer().writeUtf8("data: {\"a\":1}\nnext")
        assertEquals("data: {\"a\":1}", SseLines.readLine(source))
        assertEquals("next", SseLines.readLine(source))
    }

    @Test
    fun `流末尾返回 null`() {
        assertNull(SseLines.readLine(Buffer()))
        val source = Buffer().writeUtf8("last line\n")
        assertEquals("last line", SseLines.readLine(source))
        assertNull(SseLines.readLine(source))
    }

    @Test
    fun `恰好在上限内的长行能读出来`() {
        val limit = 32L
        val payload = "x".repeat((limit - 1).toInt()) // 加上换行符正好 limit 字节
        val source = Buffer().writeUtf8(payload + "\n")
        assertEquals(payload, SseLines.readLine(source, limit))
    }

    @Test
    fun `超长一行按回复太长中止 而不是吃掉内存`() {
        val limit = 32L
        val source = Buffer().writeUtf8("y".repeat((limit + 1).toInt()) + "\n")
        val error = assertThrows(ApiException::class.java) {
            SseLines.readLine(source, limit)
        }
        assertEquals(NetworkErrorKind.ResponseTooLong(limit), error.kind)
    }

    @Test
    fun `超长且没有换行的流也会中止`() {
        val limit = 16L
        val source = Buffer().writeUtf8("z".repeat(64))
        val error = assertThrows(ApiException::class.java) {
            SseLines.readLine(source, limit)
        }
        assertEquals(NetworkErrorKind.ResponseTooLong(limit), error.kind)
    }

    @Test
    fun `默认上限是 8MB`() {
        assertEquals(8L * 1024 * 1024, SseLines.MAX_LINE_BYTES)
        assertTrue(SseLines.MAX_LINE_BYTES < 64L * 1024 * 1024)
    }
}
