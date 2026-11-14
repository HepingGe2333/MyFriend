package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Key 到底配好了没有」的三态（安全审计 P2-6）。
 *
 * 只看密文非空会把「Keystore 失效」显示成「已保存」，用户发消息只会收到 401。
 */
class ApiKeyStateTest {

    @Test
    fun `没有密文就是未配置`() {
        assertEquals(ApiKeyState.MISSING, ApiKeyPolicy.evaluate(null, null))
        assertEquals(ApiKeyState.MISSING, ApiKeyPolicy.evaluate("", "sk-x"))
        assertEquals(ApiKeyState.MISSING, ApiKeyPolicy.evaluate("   ", null))
    }

    @Test
    fun `解得出来才算已配置`() {
        assertEquals(ApiKeyState.USABLE, ApiKeyPolicy.evaluate("cipher", "sk-123"))
        assertTrue(ApiKeyPolicy.isUsable("cipher", "sk-123"))
    }

    @Test
    fun `有密文但解不出来是 无法恢复`() {
        assertEquals(ApiKeyState.UNRECOVERABLE, ApiKeyPolicy.evaluate("cipher", null))
        assertEquals(ApiKeyState.UNRECOVERABLE, ApiKeyPolicy.evaluate("cipher", ""))
        assertEquals(ApiKeyState.UNRECOVERABLE, ApiKeyPolicy.evaluate("cipher", "   "))
        assertFalse("解不出来不能算已配置", ApiKeyPolicy.isUsable("cipher", null))
    }
}
