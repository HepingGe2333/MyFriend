package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误令牌的编解码。
 *
 * 令牌是「落库的错误」（`messages.errorMessage`、`ToolTraceItem.detail`、`proactive_rules.lastError`）
 * 与界面之间的契约：存 ASCII 令牌，渲染时按当前语言翻译；老库里的中文（不是令牌）原样返回。
 */
class UserErrorTokensTest {

    private fun roundTrip(error: UserError): UserError? = UserErrorTokens.decode(UserErrorTokens.encode(error))

    @Test
    fun `数据层错误码能原样往返`() {
        val samples = listOf(
            UserError.Domain(DomainErrorCode.PERSONA_NOT_FOUND),
            UserError.Domain(DomainErrorCode.MESSAGE_TOO_LONG),
            UserError.Domain(DomainErrorCode.HEADER_VALUE_NOT_STRING, arg = "X-Trace"),
            UserError.Domain(DomainErrorCode.IMPORT_TOO_MANY_PERSONAS, number = 200),
            UserError.Domain(DomainErrorCode.TOOL_UNKNOWN, arg = "remember_all")
        )
        samples.forEach { error ->
            assertEquals(error, roundTrip(error))
        }
    }

    @Test
    fun `网络层错误能原样往返`() {
        val samples = listOf(
            UserError.Network(NetworkErrorKind.EmptyUrl),
            UserError.Network(NetworkErrorKind.Http(401)),
            UserError.Network(NetworkErrorKind.Http(429)),
            UserError.Network(NetworkErrorKind.Timeout),
            UserError.Network(NetworkErrorKind.NetworkInterrupted),
            UserError.Network(NetworkErrorKind.ChatQueueBusy),
            UserError.Network(NetworkErrorKind.StreamError(500)),
            UserError.Network(NetworkErrorKind.ProviderUnavailable("GEMINI")),
            UserError.Network(NetworkErrorKind.ResponseTooLong(8L * 1024 * 1024)),
            UserError.Network(NetworkErrorKind.WebStatus(402)),
            UserError.Network(NetworkErrorKind.WebPageUnreachable)
        )
        samples.forEach { error ->
            assertEquals(error, roundTrip(error))
        }
    }

    @Test
    fun `未知错误也能往返`() {
        assertEquals(UserError.Unknown, roundTrip(UserError.Unknown))
    }

    @Test
    fun `令牌本身就是 ASCII 前缀`() {
        val token = UserErrorTokens.encode(UserError.Domain(DomainErrorCode.PERSONA_NOT_FOUND))
        assertTrue(token.startsWith(UserErrorTokens.PREFIX))
        assertTrue(UserErrorTokens.isToken(token))
        assertFalse(UserErrorTokens.isToken("人格不存在"))
    }

    @Test
    fun `老库里的中文不是令牌 解码返回 null 好让界面原样显示`() {
        assertNull(UserErrorTokens.decode("API Key 无效或未授权（401）"))
        assertNull(UserErrorTokens.decode(""))
        assertNull(UserErrorTokens.decode(null))
        assertNull(UserErrorTokens.decode("${UserErrorTokens.PREFIX}domain:NO_SUCH_CODE"))
    }

    @Test
    fun `参数里的分隔符不会把令牌弄坏`() {
        val error = UserError.Domain(DomainErrorCode.HEADER_VALUE_NOT_STRING, arg = "a|b\nc")
        val decoded = roundTrip(error)
        assertTrue(decoded is UserError.Domain)
        assertEquals(DomainErrorCode.HEADER_VALUE_NOT_STRING, (decoded as UserError.Domain).code)
        assertTrue("竖线要被替换掉，不能让令牌多出一段", decoded.arg?.contains('|') == false)
    }

    @Test
    fun `日志用的中文说法在 每个错误上都有`() {
        // debugText 只给日志/模型提示词用，界面走资源；这里保证它不会抛、也不为空。
        DomainErrorCode.entries.forEach { code ->
            assertTrue("$code 没有日志说法", UserError.Domain(code).debugText().isNotBlank())
        }
        assertTrue(UserError.Network(NetworkErrorKind.Http(500)).debugText().isNotBlank())
    }

    @Test
    fun `同一个错误码 的令牌是稳定的`() {
        // 稳定性是落库的前提：同一含义今天存、明天读，必须还是同一条。
        val a = UserErrorTokens.encode(UserError.Domain(DomainErrorCode.PERSONA_NOT_FOUND))
        val b = UserErrorTokens.encode(UserError.Domain(DomainErrorCode.PERSONA_NOT_FOUND))
        assertEquals(a, b)
        assertFalse(a.contains("人格"))
    }
}
