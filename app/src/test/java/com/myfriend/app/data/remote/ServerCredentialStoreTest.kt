package com.myfriend.app.data.remote

import com.myfriend.app.data.secure.StringCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端连接配置的纯 JVM 单测（M3）：
 *
 * 1. **加密存储**：地址/令牌落盘的是密文；解不开时安静退化成「没配」；
 * 2. **https / http 规则**：复用 [ServerConfig]，release 拒绝明文、debug 只放行本机/局域网；
 * 3. **令牌不进日志**：`toString` 与脱敏展示都不含明文。
 */
class ServerCredentialStoreTest {

    /** 假加密器：只做可逆变换，用来证明「落盘的不是明文」；生产实现是 Android Keystore。 */
    private class FakeCipher : StringCipher {
        override fun encrypt(plainText: String): String =
            if (plainText.isEmpty()) "" else "enc:" + plainText.reversed()

        override fun decrypt(encoded: String?): String? {
            if (encoded.isNullOrBlank()) return null
            if (!encoded.startsWith("enc:")) return null
            return encoded.removePrefix("enc:").reversed()
        }
    }

    private class FakeBackend : CredentialBackend {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun store(cipher: StringCipher = FakeCipher()): Pair<ServerCredentialStore, FakeBackend> {
        val backend = FakeBackend()
        return ServerCredentialStore(cipher, backend) to backend
    }

    // ------------------------------------------------------------------ 加密存储

    @Test
    fun `地址与令牌落盘的是密文 读取后还原`() {
        val (store, backend) = store()
        val credentials = ServerCredentials(
            url = "https://server.example:8788/mf",
            token = "s3cret-token-value",
            enabled = true
        )
        store.save(credentials)

        val raw = backend.values.values.joinToString("\n")
        assertFalse("地址不能明文落盘：$raw", raw.contains("server.example"))
        assertFalse("令牌不能明文落盘：$raw", raw.contains("s3cret-token-value"))
        assertTrue("开关不是秘密，可以明文", backend.values["server_enabled"] == "1")

        val loaded = store.load()
        assertEquals(credentials.url, loaded.url)
        assertEquals(credentials.token, loaded.token)
        assertTrue(loaded.enabled)
    }

    @Test
    fun `解不开的密文按没配处理 不抛异常`() {
        val (store, backend) = store()
        backend.write("server_enabled", "1")
        backend.write("server_url_cipher", "this-is-not-valid")
        backend.write("server_token_cipher", "also-broken")

        val loaded = store.load()
        assertEquals("", loaded.url)
        assertEquals("", loaded.token)
        assertTrue("开关本身还在（但地址为空 → 不会连出去）", loaded.enabled)
        assertEquals(ServerConfigRejection.EMPTY, loaded.toConfig(debuggable = true).rejection(true))
    }

    @Test
    fun `清除后连开关一起清掉`() {
        val (store, backend) = store()
        store.save(ServerCredentials("https://x.example", "t", enabled = true))
        store.clear()
        assertTrue(backend.values.isEmpty())
        assertEquals(ServerCredentials.EMPTY, store.load())
    }

    // ------------------------------------------------------------------ https / http

    @Test
    fun `https 在 release 与 debug 都允许`() {
        val creds = ServerCredentials("https://server.example:8788", enabled = true)
        assertNull(creds.toConfig(debuggable = false).rejection(false))
        assertNull(creds.toConfig(debuggable = true).rejection(true))
    }

    @Test
    fun `调试构建允许本机或局域网 http 但拒绝公网明文`() {
        val local = ServerCredentials("http://192.168.1.8:8788", enabled = true)
        assertNull(local.toConfig(debuggable = true).rejection(true))

        val public = ServerCredentials("http://example.com:8788", enabled = true)
        assertEquals(
            ServerConfigRejection.CLEARTEXT_HTTP_NOT_LOCAL,
            public.toConfig(debuggable = true).rejection(true)
        )
    }

    @Test
    fun `release 连本机明文也拒绝`() {
        val creds = ServerCredentials("http://10.0.2.2:8788", enabled = true)
        assertEquals(
            ServerConfigRejection.CLEARTEXT_HTTP_IN_RELEASE,
            creds.toConfig(debuggable = false).rejection(false)
        )
    }

    @Test
    fun `关闭时任何地址都不可用`() {
        val creds = ServerCredentials("https://server.example:8788", enabled = false)
        assertEquals(ServerConfigRejection.DISABLED, creds.toConfig(debuggable = true).rejection(true))
        assertFalse(creds.toConfig(debuggable = true).isUsable(true))
    }

    // ------------------------------------------------------------------ 令牌脱敏

    @Test
    fun `令牌展示只保留末四位`() {
        assertEquals("", ServerCredentials().maskedToken())
        assertEquals("很短或空的令牌不泄露长度", "•".repeat(8), ServerCredentials(token = "abcd").maskedToken())
        assertEquals("••••3456", ServerCredentials(token = "1234567893456").maskedToken())
    }

    @Test
    fun `toString 与脱敏展示都不含明文令牌`() {
        val creds = ServerCredentials("https://server.example", "s3cret-token-value", enabled = true)
        assertFalse(creds.toString().contains("s3cret-token-value"))
        assertFalse(creds.maskedToken().contains("s3cret"))
        assertFalse(creds.toConfig(debuggable = true).toString().contains("s3cret-token-value"))
        assertTrue(creds.toString().contains("hasToken=true"))
    }

    // ------------------------------------------------------------------ 来源优先级

    @Test
    fun `用户保存的配置优先于系统属性与构建期值`() {
        val config = ServerConfig.resolve(
            credentials = ServerCredentials("https://user.example", "user-token", enabled = true),
            buildUrl = "https://build.example",
            buildToken = "build-token",
            systemProperty = { "https://property.example" }
        )
        assertEquals("https://user.example", config.baseUrl)
        assertEquals("Bearer user-token", config.authorizationHeader())
        assertTrue(config.enabled)
    }

    @Test
    fun `用户显式关掉服务端时不再回落到构建期地址`() {
        val config = ServerConfig.resolve(
            credentials = ServerCredentials("https://user.example", "user-token", enabled = false),
            buildUrl = "https://build.example",
            buildToken = "build-token",
            systemProperty = { "https://property.example" }
        )
        assertFalse("设置页关了就是关了，不能被 debug 构建偷偷打开", config.enabled)
        assertEquals(ServerConfigRejection.DISABLED, config.rejection(debuggable = true))
    }

    @Test
    fun `运行时仓库默认关闭 保存后可用 清除后回到本地`() {
        val (store, _) = store()
        val repository = ServerModeRepository(store)
        assertFalse("默认关闭", repository.state.value.usable)
        assertFalse(repository.isEnabled())

        val saved = repository.save(
            ServerCredentials("https://server.example:8788", "token", enabled = true)
        ).getOrThrow()
        assertTrue(saved.usable)
        assertTrue(repository.isEnabled())

        repository.clear().getOrThrow()
        assertEquals(ServerCredentials.EMPTY, repository.currentCredentials())
        assertFalse(repository.isEnabled())
    }

    @Test
    fun `测试连接在地址不合法时不发请求 直接给失败`() = runBlocking {
        val (store, _) = store()
        val repository = ServerModeRepository(store)
        // 调试构建下公网 http 会被 ServerConfig 拒绝；验证测试连接复用了同一套安全策略。
        val result = repository.testConnection(
            ServerCredentials("http://example.com:8788", "token", enabled = true)
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `没有用户配置时仍按系统属性与构建期兜底`() {
        val config = ServerConfig.resolve(
            credentials = ServerCredentials.EMPTY,
            buildUrl = "https://build.example",
            buildToken = "build-token",
            systemProperty = { null }
        )
        assertEquals("https://build.example", config.baseUrl)
        assertTrue(config.enabled)
    }
}
