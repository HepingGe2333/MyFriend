package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明文 HTTP 的两道闸（安全审计 P1-2）。
 *
 * 1. 用户必须在 API 配置里显式勾「允许不安全 HTTP」；
 * 2. 构建本身必须允许（debug 才允许，release 的系统层 manifest 是
 *    `usesCleartextTraffic="false"`）。
 *
 * 只有两个都为真才放行；否则给出明确原因（用户没同意 vs 发布版不支持），
 * 不允许「悄悄失败成超时」。
 */
class CleartextHttpPolicyTest {

    @Test
    fun `debug 且用户同意才允许明文`() {
        assertTrue(CleartextHttpPolicy.allowsInsecureHttp(allowInsecureHttp = true, debuggable = true))
        assertNull(CleartextHttpPolicy.rejection(allowInsecureHttp = true, debuggable = true))
    }

    @Test
    fun `用户不同意时永远不允许`() {
        assertFalse(CleartextHttpPolicy.allowsInsecureHttp(allowInsecureHttp = false, debuggable = true))
        assertFalse(CleartextHttpPolicy.allowsInsecureHttp(allowInsecureHttp = false, debuggable = false))
        assertEquals(
            NetworkErrorKind.HttpsRequired,
            CleartextHttpPolicy.rejection(allowInsecureHttp = false, debuggable = true)
        )
    }

    @Test
    fun `release 构建即使开关打开也拒绝 并给出专门的原因`() {
        assertFalse("发布包系统层禁止明文，App 闸门不能再放行", CleartextHttpPolicy.allowsInsecureHttp(
            allowInsecureHttp = true,
            debuggable = false
        ))
        assertEquals(
            "要给「发布版只允许 HTTPS」的专门文案，而不是静默失败",
            NetworkErrorKind.CleartextDisabled,
            CleartextHttpPolicy.rejection(allowInsecureHttp = true, debuggable = false)
        )
    }
}
