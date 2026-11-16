package com.yap.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 0.12.1 bootstrap 依赖的传输层契约（真实 socket 假服务端）：
 *
 * - `POST /v1/sessions/{id}/messages/import` 的路径/body/批量上限/响应解析；
 * - `GET /v1/personas`、`/v1/presets`、`/v1/personas/{id}/memories` 的返回形状；
 * - 可选端点（presets/memories）404 时适配器返回 null 而不是把整次 bootstrap 打挂。
 */
class ServerApiBootstrapTest {

    private lateinit var server: FakeHttpTestServer
    private lateinit var api: ServerApi

    @Before
    fun setUp() {
        server = FakeHttpTestServer()
        api = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, token = "test-token", enabled = true),
            debuggable = true
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `批量导入走 import 端点 保留时间已读附件角色 并解析计数`() = runBlocking {
        server.respondJson("""{"imported":2,"skipped":0,"sessionId":"s1"}""")

        val result = api.importMessages(
            sessionId = "s1",
            messages = listOf(
                ServerMessageImport(
                    id = "m1",
                    role = "USER",
                    content = "你好",
                    createdAt = 1_000,
                    readAt = 1_200,
                    attachments = listOf("/v1/media/media-1"),
                    source = "IMPORT",
                    clientMessageId = "import-m1"
                ),
                ServerMessageImport(
                    id = "m2",
                    role = "ASSISTANT",
                    content = "在",
                    createdAt = 2_000,
                    readAt = null
                )
            )
        )

        assertEquals(2, result.imported)
        assertEquals(0, result.skipped)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions/s1/messages/import", request.path)
        val messages = kotlinx.serialization.json.Json.parseToJsonElement(request.body)
            .jsonObject.getValue("messages").jsonArray
        assertEquals(2, messages.size)
        val first = messages[0].jsonObject
        assertEquals("m1", first.getValue("id").jsonPrimitive.content)
        assertEquals("USER", first.getValue("role").jsonPrimitive.content)
        assertEquals(1_000L, first.getValue("createdAt").jsonPrimitive.content.toLong())
        assertEquals(1_200L, first.getValue("readAt").jsonPrimitive.content.toLong())
        assertEquals("IMPORT", first.getValue("source").jsonPrimitive.content)
        assertEquals(
            "/v1/media/media-1",
            first.getValue("attachments").jsonArray.first().jsonPrimitive.content
        )
        assertEquals("import-m1", first.getValue("clientMessageId").jsonPrimitive.content)
    }

    @Test
    fun `超过 200 条的批次在客户端就失败 不发请求`() = runBlocking {
        val messages = (0 until 201).map {
            ServerMessageImport(id = "m-$it", role = "USER", content = "x", createdAt = it.toLong())
        }
        try {
            api.importMessages("s1", messages)
            fail("expected the client-side batch limit to reject 201 messages")
        } catch (error: ServerApiException) {
            assertEquals("invalid_argument", error.code)
        }
        assertTrue("超限不应发出请求", server.requests.isEmpty())
    }

    @Test
    fun `GET personas 支持对象容器 并保留扩展字段`() = runBlocking {
        server.respondJson(
            """{"serverTime":1,"personas":[{"id":"p1","name":"林深","prompt":"p","updatedAt":2}]}"""
        )
        val personas = api.personas()
        assertEquals(1, personas.size)
        assertEquals("p1", personas.first().getValue("id").jsonPrimitive.content)
        assertEquals("p", personas.first().getValue("prompt").jsonPrimitive.content)
    }

    @Test
    fun `GET presets 支持裸数组`() = runBlocking {
        server.respondJson("""[{"id":"preset-1","name":"温柔","updatedAt":2}]""")
        val presets = api.presets()
        assertEquals(1, presets.size)
        assertEquals("preset-1", presets.first().getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `GET memories 带 limit 与 since 并支持对象容器`() = runBlocking {
        server.respondJson("""{"personaId":"p1","memories":[{"id":"m1","content":"咖啡"}]}""")
        val memories = api.memories("p1", since = 50L)
        assertEquals(1, memories.size)
        val request = server.requests.single()
        assertEquals("/v1/personas/p1/memories", request.path)
        assertEquals("500", request.query["limit"])
        assertEquals("50", request.query["since"])
    }

    @Test
    fun `适配器对缺失的 presets 端点返回 null 而不是失败`() = runBlocking {
        server.respondJson("""{"error":{"message":"not found","code":"not_found"}}""", status = 404)
        val remote = ServerApiBootstrapRemote(object : ServerApiProvider {
            override fun api(): ServerApi = api
            override fun isEnabled(): Boolean = true
        })
        assertNull(remote.presets())
    }

    @Test
    fun `适配器解包 settings 外层对象 并兼容平铺`() = runBlocking {
        val remote = ServerApiBootstrapRemote(object : ServerApiProvider {
            override fun api(): ServerApi = api
            override fun isEnabled(): Boolean = true
        })
        server.respondJson("""{"settings":{"themeMode":"DARK","userAvatarUri":"/v1/media/me"}}""")
        var settings = remote.settings()
        assertEquals("DARK", settings.getValue("themeMode").jsonPrimitive.content)

        server.respondJson("""{"themeMode":"LIGHT"}""")
        settings = remote.settings()
        assertEquals("LIGHT", settings.getValue("themeMode").jsonPrimitive.content)
    }

    @Test
    fun `适配器解包 persona 外层对象`() = runBlocking {
        server.respondJson("""{"persona":{"id":"srv-p1","name":"林深","updatedAt":9}}""")
        val remote = ServerApiBootstrapRemote(object : ServerApiProvider {
            override fun api(): ServerApi = api
            override fun isEnabled(): Boolean = true
        })
        val persona = remote.createPersona(
            buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("林深"))
            }
        )
        assertEquals("srv-p1", persona.getValue("id").jsonPrimitive.content)
    }
}
