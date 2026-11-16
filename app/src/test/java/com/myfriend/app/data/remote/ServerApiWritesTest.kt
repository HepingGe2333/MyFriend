package com.myfriend.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 服务端写路径（M3 最小闭环）的传输层单测：`/health`、建会话、标已读、活动上报。
 *
 * 只钉「方法/路径/请求体/响应解析」——业务编排在 `ServerChatGatewayTest` 与
 * `RoomServer*Sink` 的映射规则里测。
 */
class ServerApiWritesTest {

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
    fun `health 是 GET health 且带 Bearer 头`() = runBlocking {
        server.respondJson("""{"ok":true,"name":"agentd","version":"0.12.0","serverTime":1794500000000}""")

        val health = api.health()

        assertTrue(health.healthy)
        assertEquals("agentd", health.name)
        assertEquals("0.12.0", health.version)
        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/health", request.path)
        assertEquals("Bearer test-token", request.headers["authorization"])
    }

    @Test
    fun `建会话 POST v1 sessions 且 body 只带 personaId 与 title`() = runBlocking {
        server.respondJson(
            """
            {"created":true,"session":{"id":"s1","personaId":"p1","title":"和某人的对话",
              "lastBlockIndex":-1,"unread":0,"messageCount":0,"updatedAt":10,"createdAt":10}}
            """.trimIndent()
        )

        val created = api.createSession("p1", "自定义标题")

        assertTrue(created.created)
        assertEquals("s1", created.session.id)
        assertEquals(-1, created.session.lastBlockIndex)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions", request.path)
        assertEquals("application/json; charset=utf-8", request.headers["content-type"])
        assertEquals("p1", jsonField(request.body, "personaId"))
        assertEquals("自定义标题", jsonField(request.body, "title"))
    }

    @Test
    fun `标已读 POST 会话 read 且 upTo 可选`() = runBlocking {
        server.respondJson("""{"sessionId":"s1","unread":0,"readAt":99,"marked":3}""")

        val receipt = api.markRead("s1", upTo = 55)

        assertEquals(0, receipt.unread)
        assertEquals(99L, receipt.readAt)
        assertEquals(3, receipt.marked)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions/s1/read", request.path)
        assertEquals("55", jsonField(request.body, "upTo"))
    }

    @Test
    fun `活动上报 POST 人格 activity 且字段与规范同构`() = runBlocking {
        server.respondJson(
            """{"activity":{"kind":"WORK","activity":"在改图","until":1763200000000,"availability":"BUSY"}}"""
        )

        val report = api.reportActivity(
            personaId = "p1",
            activity = ServerActivityWrite(
                kind = "WORK",
                activity = "在改图",
                until = 1763200000000,
                availability = "BUSY"
            )
        )

        assertEquals("在改图", report.activity?.activity)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/personas/p1/activity", request.path)
        assertEquals("WORK", jsonField(request.body, "kind"))
        assertEquals("在改图", jsonField(request.body, "activity"))
        assertEquals("1763200000000", jsonField(request.body, "until"))
        assertEquals("BUSY", jsonField(request.body, "availability"))
    }

    @Test
    fun `推送注册 POST v1 push register`() = runBlocking {
        server.respondJson("""{"registered":true,"platform":"android"}""")

        val result = api.registerPush("android", "fcm-token")

        assertTrue(result.registered)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/push/register", request.path)
        assertEquals("android", jsonField(request.body, "platform"))
        assertEquals("fcm-token", jsonField(request.body, "token"))
    }

    /** 极简 JSON 字段取值：测试断言用，不做通用解析。 */
    private fun jsonField(body: String, field: String): String? {
        val marker = "\"$field\":"
        val start = body.indexOf(marker) + marker.length
        if (start <= 0) return null
        val rest = body.substring(start)
        return when {
            rest.startsWith("\"") -> rest.substring(1).substringBefore('"')
            else -> rest.takeWhile { it != ',' && it != '}' }.trim()
        }
    }
}
