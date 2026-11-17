package com.yap.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0.13 新增的三个服务端端点（任务 1/2/3）的传输层契约。
 *
 * 用真实的 socket（[FakeHttpTestServer]）而不是拦截器：路径拼错、方法写错、请求体形状不对
 * 都会在这里当场变红，和 `agentd` 的路由逐字对齐。
 */
class ServerPhase2ApiTest {

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
    fun `情绪判定 POST 路径与多维解析`() = runBlocking {
        server.respondJson(
            """{"personaId":"p1","changed":true,"source":"llm",
               "mood":{"personaId":"p1","label":"有点累","intensity":3,
                       "valence":-1,"arousal":2,"dominance":3,"reason":"加班","updatedAt":123}}"""
        )

        val result = api.evaluateMood("p1")

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/personas/p1/mood/evaluate", request.path)
        assertEquals("test-token", request.headers["authorization"]?.removePrefix("Bearer "))
        assertTrue(result.changed)
        assertEquals("llm", result.source)
        assertEquals("有点累", result.mood?.label)
        assertEquals(-1, result.mood?.valence)
        assertEquals(2, result.mood?.arousal)
        assertEquals(3, result.mood?.dominance)
    }

    @Test
    fun `情绪判定 source=decay 且没有 mood 时也能解析`() = runBlocking {
        server.respondJson("""{"personaId":"p1","changed":false,"source":"decay","mood":null}""")
        val result = api.evaluateMood("p1")
        assertEquals("decay", result.source)
        assertEquals(null, result.mood)
    }

    @Test
    fun `进化评估 POST 并解析补丁`() = runBlocking {
        server.respondJson(
            """{"personaId":"p1","proposed":1,"alreadyPending":false,"summary":"性格 +嘴硬心软",
               "patch":{"id":"patch-1","personaId":"p1","sessionId":"s1",
                        "patchJson":"{\"changes\":[{\"field\":\"traits\",\"op\":\"add\",\"value\":\"嘴硬心软\"}]}",
                        "summary":"性格 +嘴硬心软","reason":"最近几次","createdAt":111,"expiresAt":222,
                        "changes":[{"field":"traits","op":"add","value":"嘴硬心软","reason":"最近几次"}]}}"""
        )

        val result = api.evaluateEvolution("p1")

        assertEquals("POST", server.requests.single().method)
        assertEquals("/v1/personas/p1/evolution/evaluate", server.requests.single().path)
        assertEquals(1, result.proposed)
        assertEquals("patch-1", result.patch?.id)
        assertTrue(result.patch!!.patchJson.contains("嘴硬心软"))
        assertEquals(222L, result.patch?.expiresAt)
    }

    @Test
    fun `待审列表 GET 并解析`() = runBlocking {
        server.respondJson(
            """{"personaId":"p1","serverTime":100,
               "patches":[{"id":"patch-1","personaId":"p1","patchJson":"{\"changes\":[]}","summary":"s"}]}"""
        )

        val result = api.pendingEvolution("p1")

        assertEquals("GET", server.requests.single().method)
        assertEquals("/v1/personas/p1/evolution/pending", server.requests.single().path)
        assertEquals(1, result.patches.size)
        assertEquals("patch-1", result.patches.single().id)
        assertEquals(100L, result.serverTime)
    }

    @Test
    fun `接受 POST 到 patchId accept 并解析完整人格`() = runBlocking {
        server.respondJson(
            """{"personaId":"p1","patchId":"patch-1","changed":true,"version":4,
               "applied":[{"field":"traits","op":"add","value":"嘴硬心软","reason":""}],
               "persona":{"id":"p1","version":4,"traits":"[\"慢热\"]","updatedAt":999}}"""
        )

        val result = api.acceptEvolution("p1", "patch-1")

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/personas/p1/evolution/patch-1/accept", request.path)
        assertEquals(4, result.version)
        assertEquals("p1", result.persona?.get("id")?.jsonPrimitive?.content)
        assertEquals("traits", result.applied.single().field)
    }

    @Test
    fun `拒绝 POST 到 patchId reject`() = runBlocking {
        server.respondJson("""{"personaId":"p1","patchId":"patch-1","rejected":true}""")

        val result = api.rejectEvolution("p1", "patch-1")

        assertEquals("POST", server.requests.single().method)
        assertEquals("/v1/personas/p1/evolution/patch-1/reject", server.requests.single().path)
        assertTrue(result.rejected)
    }

    @Test
    fun `配置推送 POST v1 llm-config 的请求体`() = runBlocking {
        server.respondJson("""{"ok":true,"id":"client-pushed","provider":"OPENAI","baseUrl":"https://api.example.com","model":"m"}""")

        val result = api.pushLlmConfig(
            ServerLlmConfigWrite(
                baseUrl = "https://api.example.com",
                model = "m",
                apiKey = "sk-secret",
                provider = "OPENAI"
            )
        )

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/llm-config", request.path)
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("https://api.example.com", body.getValue("baseUrl").jsonPrimitive.content)
        assertEquals("m", body.getValue("model").jsonPrimitive.content)
        assertEquals("sk-secret", body.getValue("apiKey").jsonPrimitive.content)
        assertEquals("OPENAI", body.getValue("provider").jsonPrimitive.content)
        assertEquals("client-pushed", result.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `远程部署 403 会带 remote_config_disallowed code`() = runBlocking {
        server.respondJson(
            """{"error":{"message":"远程部署不接受客户端推送的模型配置","code":"remote_config_disallowed"}}""",
            status = 403
        )

        val error = runCatching {
            api.pushLlmConfig(ServerLlmConfigWrite("https://api.example.com", "m", "sk", "OPENAI"))
        }.exceptionOrNull() as ServerApiException

        assertEquals(403, error.status)
        assertEquals("remote_config_disallowed", error.code)
    }

    @Test
    fun `服务端没配 LLM 时情绪端点 503`() = runBlocking {
        server.respondJson(
            """{"error":{"message":"没有可用的模型配置","code":"llm_not_configured"}}""",
            status = 503
        )
        val error = runCatching { api.evaluateMood("p1") }.exceptionOrNull() as ServerApiException
        assertEquals(503, error.status)
    }
}
