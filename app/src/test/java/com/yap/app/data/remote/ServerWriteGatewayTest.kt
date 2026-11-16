package com.yap.app.data.remote

import com.yap.app.data.prefs.AppSettings
import com.yap.app.domain.PersonaActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M5 写路径的失败/成功语义（纯 JVM）。
 *
 * 直接走真实 [ServerApi] + 假 socket 服务端，钉住：
 * - 成功时返回服务端对象、请求路径/方法/body 正确；
 * - 失败时是 `Result.failure`（调用方**不得**回退本地写）；
 * - 删除遇到 404 视为「服务端已经没了」，本机也该删；
 * - 关闭服务端模式时旧的「尽力而为」方法仍不抛异常（本地模式回归）。
 */
class ServerWriteGatewayTest {

    private class FakeProvider(
        var api: ServerApi?,
        var enabled: Boolean
    ) : ServerApiProvider {
        override fun api(): ServerApi? = api
        override fun isEnabled(): Boolean = enabled
    }

    private lateinit var server: FakeHttpTestServer
    private lateinit var api: ServerApi
    private lateinit var provider: FakeProvider
    private lateinit var gateway: ServerWriteGateway

    @Before
    fun setUp() {
        server = FakeHttpTestServer()
        api = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, token = "test-token", enabled = true),
            debuggable = true
        )
        provider = FakeProvider(api = api, enabled = true)
        gateway = ServerWriteGateway(provider, TestServerLocalStore())
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ------------------------------------------------------------------ 人格

    @Test
    fun `保存人格成功返回服务端 persona 对象`() = runBlocking {
        server.respondJson("""{"persona":{"id":"server-p1","name":"林深","createdAt":10,"updatedAt":20}}""")

        val result = gateway.savePersona(
            buildJsonObject {
                put("id", "p1")
                put("name", "林深")
            }
        )

        assertTrue(result.isSuccess)
        val persona = result.getOrThrow()
        assertEquals("server-p1", persona.getValue("id").jsonPrimitive.content)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/personas", request.path)
        assertTrue(request.body.contains("\"name\":\"林深\""))
    }

    @Test
    fun `保存人格失败是 failure 不是静默成功`() = runBlocking {
        server.respondJson(
            """{"error":{"message":"名字不能为空","code":"invalid_argument"}}""",
            status = 400
        )

        val result = gateway.savePersona(buildJsonObject { put("name", "") })

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as ServerApiException
        assertEquals(400, error.status)
        assertEquals("invalid_argument", error.code)
    }

    @Test
    fun `删除人格 404 当成已经删掉 其它错误保持失败`() = runBlocking {
        server.respondJson("""{"error":{"message":"没有这个人格","code":"not_found"}}""", status = 404)
        val deleted = gateway.deletePersona("p1")
        assertTrue("服务端已经没了，本机缓存也应该能删", deleted.isSuccess)
        assertEquals("/v1/personas/p1", server.requests.single().path)
        assertEquals("DELETE", server.requests.single().method)

        server.respondJson("""{"error":{"message":"boom","code":"state_unavailable"}}""", status = 503)
        val failed = gateway.deletePersona("p1")
        assertTrue(failed.isFailure)
        assertEquals("state_unavailable", (failed.exceptionOrNull() as ServerApiException).code)
    }

    // ------------------------------------------------------------------ 记忆

    @Test
    fun `保存记忆路径与响应`() = runBlocking {
        server.respondJson("""{"memory":{"id":"m1","content":"记得","importance":60,"createdAt":1,"updatedAt":2}}""")

        val result = gateway.saveMemory(
            "p1",
            buildJsonObject {
                put("content", "记得")
                put("importance", 60)
            }
        )

        assertTrue(result.isSuccess)
        assertEquals("m1", result.getOrThrow().getValue("id").jsonPrimitive.content)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/personas/p1/memories", request.path)
    }

    @Test
    fun `删除记忆 404 当成功 500 保持失败`() = runBlocking {
        server.respondJson("""{"error":{"message":"没有这条记忆","code":"not_found"}}""", status = 404)
        assertTrue(gateway.deleteMemory("m1").isSuccess)
        assertEquals("/v1/memories/m1", server.requests.single().path)

        server.respondJson("""{"error":{"message":"boom","code":"internal"}}""", status = 500)
        assertTrue(gateway.deleteMemory("m1").isFailure)
    }

    // ------------------------------------------------------------------ 预设

    @Test
    fun `保存与删除预设路径`() = runBlocking {
        server.respondJson("""{"preset":{"id":"pre1","name":"模板","createdAt":1,"updatedAt":2}}""")
        val saved = gateway.savePreset(buildJsonObject { put("name", "模板") })
        assertTrue(saved.isSuccess)
        assertEquals("/v1/presets", server.requests.last().path)

        server.respondJson("""{"deleted":true,"id":"pre1"}""")
        assertTrue(gateway.deletePreset("pre1").isSuccess)
        assertEquals("/v1/presets/pre1", server.requests.last().path)
        assertEquals("DELETE", server.requests.last().method)
    }

    // ------------------------------------------------------------------ 设置

    @Test
    fun `设置推送只发白名单 body`() = runBlocking {
        server.respondJson("""{"settings":{"themeMode":"DARK"}}""")

        val result = gateway.pushSettings(AppSettings(themeMode = com.yap.app.data.prefs.ThemeMode.DARK))

        assertTrue(result.isSuccess)
        val request = server.requests.single()
        assertEquals("/v1/settings", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.body.contains("\"themeMode\":\"DARK\""))
        assertFalse(request.body.contains("apiKey", ignoreCase = true))
    }

    // ------------------------------------------------------------------ 备份

    @Test
    fun `备份导出返回原始 JSON 文本`() = runBlocking {
        val raw = """{"format":"myfriend.backup","formatVersion":5,"personas":[]}"""
        server.respondJson(raw)

        val result = gateway.exportBackup()

        assertEquals(raw, result.getOrThrow())
        assertEquals("/v1/backup", server.requests.single().path)
        assertEquals("GET", server.requests.single().method)
    }

    @Test
    fun `备份导入发送 mode 与 data 对象 并解析分段计数`() = runBlocking {
        server.respondJson(
            """{"mode":"merge","imported":{"personas":2,"sessions":2,"messages":9,
               "memories":4,"presets":1,"settings":true}}"""
        )

        val result = gateway.importBackup(
            rawBackup = """{"format":"myfriend.backup","formatVersion":5,"personas":[]}""",
            mode = ServerBackupMode.MERGE
        )

        assertTrue(result.isSuccess)
        val imported = result.getOrThrow()
        assertEquals("merge", imported.mode)
        assertEquals(2, imported.imported.personas)
        assertEquals(9, imported.imported.messages)
        assertTrue(imported.imported.settings)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/backup", request.path)
        assertTrue(request.body.contains("\"mode\":\"merge\""))
        assertTrue(request.body.contains("\"data\":"))
        // data 是对象，不是被二次编码的字符串。
        assertFalse(request.body.contains("\"data\":\"{"))
    }

    @Test
    fun `不是 JSON 对象的备份在本地就失败 不发请求`() = runBlocking {
        val result = gateway.importBackup("not-json", ServerBackupMode.REPLACE)

        assertTrue(result.isFailure)
        assertTrue(server.requests.isEmpty())
    }

    // ------------------------------------------------------------------ 本地模式回归

    @Test
    fun `服务端关闭时权威写失败 但旧的尽力而为方法不抛异常`() = runBlocking {
        provider.enabled = false
        provider.api = null

        assertFalse(gateway.isEnabled())
        assertTrue(gateway.savePersona(buildJsonObject { put("name", "x") }).isFailure)
        assertTrue(gateway.deletePersona("p1").isFailure)

        // 旧方法的分寸不变：活动上报成功（空操作）、已读返回 false、建会话返回 null。
        val activity = gateway.reportActivity("p1", PersonaActivity(activity = "在忙"))
        assertTrue("本地模式不能因为服务端关闭而抛异常", activity.isSuccess)
        assertFalse(gateway.markRead("s1"))
        assertNull(gateway.createSession("p1"))
        assertTrue("本地模式下不应该发任何请求", server.requests.isEmpty())
    }

}
