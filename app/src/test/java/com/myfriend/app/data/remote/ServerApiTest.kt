package com.myfriend.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ServerApi] 的 JSON 接口单测（真 socket 假服务端，不发外网）。
 *
 * 钉住四件事：
 * 1. 路径 / query / 方法与契约 §3.1–§3.4 一致（含 `/v1` 只出现一次、personaId 编码）；
 * 2. DTO 能解析服务端真实形状（可加字段、未知事件字段、中文正文、null 字段）；
 * 3. 非 2xx 统一错误体映射成 [ServerApiException]（code / status / message）；
 * 4. 响应体 2 MiB 上限与超时映射（这两条是「服务端异常不应该拖垮客户端」的底线）。
 */
class ServerApiTest {

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

    // ------------------------------------------------------------------ §3.1 state

    @Test
    fun `state 的路径 query 头与 DTO 解析`() = runBlocking {
        server.respondJson(
            """
            {
              "serverTime": 1794500000000,
              "personas": [
                {"id":"p1","name":"小明","nameEn":"Ming","avatarUrl":"/v1/media/a","isDefault":true,
                 "createdAt":1,"updatedAt":5,"unknownField":"ignored"}
              ],
              "sessions": [
                {"id":"s1","personaId":"p1","title":"今天","createdAt":1,"updatedAt":9,
                 "lastMessageAt":100,"lastBlockIndex":3,"unread":2,"messageCount":7}
              ],
              "messages": [
                {"id":"m1","sessionId":"s1","blockIndex":3,"role":"USER","content":"你好",
                 "createdAt":100,"readAt":null,"attachments":[],"source":"CHAT"},
                {"id":"m2","sessionId":"s1","blockIndex":3,"role":"ASSISTANT","content":"在的",
                 "createdAt":101,"readAt":102,"attachments":["media-1"],"source":"PROACTIVE",
                 "clientMessageId":null,"replyTo":"m1"}
              ],
              "mood": {"personaId":"p1","label":"平静","intensity":3,"updatedAt":7},
              "presence": {"personaId":"p1","kind":"IDLE","detail":"在发呆","updatedAt":8},
              "serverCursor": 42,
              "hasMoreMessages": true
            }
            """.trimIndent()
        )

        val snapshot = api.state(since = 1234, personaId = "p 1")

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/state", request.path)
        assertEquals("1234", request.query["since"])
        assertEquals("personaId 要原样编码再从 query 解出来", "p 1", request.query["personaId"])
        assertEquals("Bearer test-token", request.headers["authorization"])
        assertEquals("application/json", request.headers["accept"])

        assertEquals(1794500000000, snapshot.serverTime)
        assertEquals(42, snapshot.serverCursor)
        assertTrue("首次同步的截断标记要能解析出来", snapshot.hasMoreMessages)
        assertEquals(1, snapshot.personas.size)
        assertEquals("小明", snapshot.personas.single().name)
        assertEquals("/v1/media/a", snapshot.personas.single().avatarUrl)

        val session = snapshot.sessions.single()
        assertEquals("s1", session.id)
        assertEquals("p1", session.personaId)
        assertEquals(3, session.lastBlockIndex)
        assertEquals(2, session.unread)
        assertEquals(7, session.messageCount)
        assertEquals(100L, session.lastMessageAt)

        assertEquals(2, snapshot.messages.size)
        val user = snapshot.messages[0]
        assertEquals("USER", user.role)
        assertEquals("你好", user.content)
        assertNull(user.readAt)
        val assistant = snapshot.messages[1]
        assertEquals(listOf("media-1"), assistant.attachments)
        assertEquals("PROACTIVE", assistant.source)
        assertEquals("m1", assistant.replyTo)
        assertEquals(102L, assistant.readAt)

        assertEquals("平静", snapshot.mood?.label)
        assertEquals(3, snapshot.mood?.intensity)
        assertEquals("IDLE", snapshot.presence?.kind)
    }

    @Test
    fun `baseUrl 以 v1 结尾时不会拼出双 v1`() = runBlocking {
        val withV1 = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl + "/v1", enabled = true),
            debuggable = true
        )
        server.respondJson("""{"serverTime":1,"serverCursor":2}""")

        withV1.state()

        assertEquals("/v1/state", server.requests.single().path)
    }

    @Test
    fun `没有令牌时不发 Authorization 头`() = runBlocking {
        val anonymous = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, token = null, enabled = true),
            debuggable = true
        )
        server.respondJson("""{"serverTime":1}""")

        anonymous.state()

        assertNull(server.requests.single().headers["authorization"])
    }

    // ------------------------------------------------------------------ §3.1 sessions

    @Test
    fun `sessions 按 personaId 过滤并解析列表`() = runBlocking {
        server.respondJson(
            """
            {"serverTime":2,"serverCursor":5,"sessions":[
              {"id":"s1","personaId":"p1","title":"一","lastMessageAt":10,"unread":0,"lastBlockIndex":0},
              {"id":"s2","personaId":"p1","title":"二","lastMessageAt":20,"unread":1,"lastBlockIndex":2}
            ]}
            """.trimIndent()
        )

        val sessions = api.sessions(personaId = "p1")

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/sessions", request.path)
        assertEquals("p1", request.query["personaId"])
        assertEquals(listOf("s1", "s2"), sessions.map { it.id })
        assertEquals("二", sessions[1].title)
        assertEquals(2, sessions[1].lastBlockIndex)
        assertNull(sessions[0].updatedAt.let { if (it == 0L) null else it })
    }

    @Test
    fun `sessions 不传 personaId 时 query 里没有它`() = runBlocking {
        server.respondJson("""{"sessions":[]}""")

        assertTrue(api.sessions().isEmpty())

        assertEquals("/v1/sessions", server.requests.single().path)
        assertFalse(server.requests.single().query.containsKey("personaId"))
    }

    // ------------------------------------------------------------------ §3.2 messages

    @Test
    fun `messages 前翻传 beforeBlock 并解析一页一块`() = runBlocking {
        server.respondJson(
            """
            {"sessionId":"s1","blockIndex":2,"hasMoreOlder":true,"hasMoreNewer":false,
             "messages":[
               {"id":"m1","sessionId":"s1","blockIndex":2,"role":"USER","content":"早","createdAt":5},
               {"id":"m2","sessionId":"s1","blockIndex":2,"role":"ASSISTANT","content":"早呀","createdAt":6}
             ]}
            """.trimIndent()
        )

        val page = api.messages("s1", beforeBlock = 3)

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/sessions/s1/messages", request.path)
        assertEquals("3", request.query["beforeBlock"])
        assertFalse("一页一块：客户端不给 limitBlocks", request.query.containsKey("limitBlocks"))
        assertFalse(request.query.containsKey("afterBlock"))

        assertEquals(2, page.blockIndex)
        assertTrue(page.hasMoreOlder)
        assertFalse(page.hasMoreNewer)
        assertEquals(listOf("m1", "m2"), page.messages.map { it.id })
    }

    @Test
    fun `messages 后翻传 afterBlock 且 blockIndex 可以为空`() = runBlocking {
        server.respondJson(
            """{"sessionId":"s1","blockIndex":null,"hasMoreOlder":true,"hasMoreNewer":false,"messages":[]}"""
        )

        val page = api.messages("s1", afterBlock = 9)

        val request = server.requests.single()
        assertEquals("/v1/sessions/s1/messages", request.path)
        assertEquals("9", request.query["afterBlock"])
        assertNull("翻过头时服务端给 null", page.blockIndex)
        assertTrue(page.messages.isEmpty())
    }

    @Test
    fun `messages 同时给两个方向直接失败且不发请求`() {
        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.messages("s1", beforeBlock = 1, afterBlock = 2) }
        }

        assertEquals("invalid_argument", error.code)
        assertTrue("这种错误不该浪费一次网络往返", server.requests.isEmpty())
    }

    // ------------------------------------------------------------------ §3.4 events

    @Test
    fun `events 长轮询 query 与事件解析`() = runBlocking {
        server.respondJson(
            """
            {"serverTime":5,"cursor":7,"events":[
              {"cursor":6,"type":"message.created","at":3,"sessionId":"s1","personaId":"p1",
               "payload":{"messageId":"m9","role":"ASSISTANT"}},
              {"cursor":7,"type":"mood.changed","at":4,"payload":{"label":"开心"}}
            ]}
            """.trimIndent()
        )

        val events = api.events(since = 6, timeoutSeconds = 25, limit = 50)

        val request = server.requests.single()
        assertEquals("/v1/events", request.path)
        assertEquals("6", request.query["since"])
        assertEquals("25", request.query["timeoutSeconds"])
        assertEquals("50", request.query["limit"])
        assertEquals(7, events.cursor)
        assertEquals(2, events.events.size)
        assertEquals("message.created", events.events[0].type)
        assertEquals("s1", events.events[0].sessionId)
        assertNotNull(events.events[0].payload["messageId"])
        assertNull("payload 里没有的键就是没有", events.events[0].payload["missing"])
        assertEquals("mood.changed", events.events[1].type)
    }

    @Test
    fun `events 的 timeoutSeconds 按服务端上限截断`() = runBlocking {
        server.respondJson("""{"cursor":0,"events":[]}""")

        api.events(since = 0, timeoutSeconds = 120)

        assertEquals("25", server.requests.single().query["timeoutSeconds"])
    }

    // ------------------------------------------------------------------ 错误 / 上限 / 超时

    @Test
    fun `非 2xx 的统一错误体映射成 code status message`() = runBlocking {
        server.respondJson(
            """{"error":{"message":"没有这个会话","code":"not_found"}}""",
            status = 404
        )

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.messages("missing") }
        }

        assertEquals("not_found", error.code)
        assertEquals(404, error.status)
        assertEquals("没有这个会话", error.message)
    }

    @Test
    fun `错误体不是 JSON 时退化成 http 状态码`() = runBlocking {
        server.respondJson("bad gateway", status = 502)

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.state() }
        }

        assertEquals("http_502", error.code)
        assertEquals(502, error.status)
        assertEquals("server returned HTTP 502", error.message)
    }

    @Test
    fun `响应体超过 2 MiB 直接失败`() = runBlocking {
        server.respondJson("""{"data":"""" + "a".repeat((ServerApi.MAX_RESPONSE_BYTES + 16).toInt()) + """"}""")

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.state() }
        }

        assertEquals("response_too_large", error.code)
    }

    @Test
    fun `读超时映射成 timeout 而不是裸 IO 异常`() = runBlocking {
        val impatient = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, enabled = true),
            debuggable = true,
            readTimeoutMillis = 200
        )
        server.responder = {
            Thread.sleep(800)
            FakeHttpTestServer.FakeReply.Json("""{"serverTime":1}""")
        }

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { impatient.state() }
        }

        assertEquals("timeout", error.code)
        assertNull("超时没有 HTTP 状态码", error.status)
    }

    @Test
    fun `配置不可用时立刻失败且不发请求`() = runBlocking {
        val notConfigured = ServerApi(config = ServerConfig(), debuggable = true)

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { notConfigured.state() }
        }

        assertEquals("server_unavailable", error.code)
        assertFalse(notConfigured.isUsable)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `响应不是契约 JSON 时映射成 invalid_response`() = runBlocking {
        server.respondJson("""{"serverTime":"not-a-number"}""")

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.state() }
        }

        assertEquals("invalid_response", error.code)
        assertEquals(200, error.status)
    }
}
