package com.myfriend.agentd

import com.myfriend.agentd.store.ApiConfig
import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.MessageRole
import com.myfriend.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `GET /v1/state`、`GET /v1/sessions`、消息分页、`GET /v1/events` 的 handler 级测试。
 *
 * handler 是纯函数（`AuthorityRoutes.handle(services, method, path, body)`），所以这些用例
 * 不用起 socket；真实 HTTP 行为（SSE / 长轮询占线程 / token）在 `AuthorityServerTest` 里。
 */
class StateApiTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private lateinit var services: AgentdServices
    private val clock = TestSupport.FakeClock(1_000L)

    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-state-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
        services = AgentdServices(store)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun get(path: String): AgentdApi.Response = AuthorityRoutes.handle(services, "GET", path, null)

    private fun bodyOf(response: AgentdApi.Response): JsonValue.Obj {
        assertEquals("期望 200，实际 ${response.status}：${response.body}", 200, response.status)
        return TestSupport.parse(response.body)
    }

    // ------------------------------------------------------------------ /v1/state

    @Test
    fun `state returns personas sessions mood presence and cursor`() {
        val json = bodyOf(get("/v1/state"))

        assertEquals(3, json.objects("personas").size)
        assertEquals("林深", json.objects("personas").first().str("name"))
        assertEquals(1, json.objects("sessions").size)
        assertTrue(json.arr("messages").isEmpty())
        assertEquals(0L, json.long("serverCursor"))
        assertEquals("mood 是单个对象", "平静", json.obj("mood").str("label"))
        assertEquals("IDLE", json.obj("presence").str("kind"))
        assertEquals(clock.now, json.long("serverTime"))
        assertFalse(json.bool("hasMoreMessages"))
    }

    @Test
    fun `since filters personas sessions and messages by their own timestamps`() {
        clock.advance(10)
        val user = store.appendMessage(sessionId, MessageRole.USER, "增量一")
        clock.advance(10)
        val assistant = store.appendMessage(sessionId, MessageRole.ASSISTANT, "增量二")

        // since=1000：人格 updatedAt 还是 1000（不 > since），会话 1020，消息两条
        val json = bodyOf(get("/v1/state?since=1000"))
        assertEquals("人格没变过就不返回", 0, json.objects("personas").size)
        assertEquals(1, json.objects("sessions").size)
        assertEquals(listOf(user.id, assistant.id), json.objects("messages").map { it.str("id") })

        // since=1010：只有 createdAt > 1010 的消息（user 恰好 1010，不算）
        val incremental = bodyOf(get("/v1/state?since=1010"))
        assertEquals(listOf(assistant.id), incremental.objects("messages").map { it.str("id") })
    }

    @Test
    fun `state filters by persona id`() {
        val json = bodyOf(get("/v1/state?personaId=${FileStore.SEED_PERSONA_2_ID}"))

        assertEquals(1, json.objects("personas").size)
        assertEquals("阿岚", json.objects("personas").single().str("name"))
        assertTrue("这个会话不属于二号人格", json.objects("sessions").isEmpty())
        assertNull("二号人格没有播种心情", json.optObj("mood"))
        assertNull(json.optObj("presence"))
    }

    @Test
    fun `state rejects bad parameters`() {
        assertEquals(404, get("/v1/state?personaId=nope").status)
        assertEquals(400, get("/v1/state?since=-1").status)
        assertEquals(400, get("/v1/state?since=abc").status)
        assertEquals("空值按没给处理", 200, get("/v1/state?since=").status)
        val wrongMethod = AuthorityRoutes.handle(services, "POST", "/v1/state", "{}")
        assertEquals(405, wrongMethod.status)
        assertTrue(wrongMethod.body.contains("method_not_allowed"))
    }

    @Test
    fun `initial sync returns only the newest block and says so`() {
        repeat(250) { store.appendMessage(sessionId, MessageRole.USER, "m$it") }

        val json = bodyOf(get("/v1/state"))
        val messages = json.objects("messages")
        assertEquals("首次同步只给最新一块（50 条）", 50, messages.size)
        assertEquals(1, messages.first().int("blockIndex"))
        assertTrue("更早的历史要用分页接口", json.bool("hasMoreMessages"))
    }

    @Test
    fun `secrets never appear in a state response`() {
        store.putApiConfig(
            ApiConfig(
                id = "cfg-1",
                name = "测试配置",
                provider = "MESSAGEAPI",
                baseUrl = "https://example.invalid/v1",
                model = "m",
                createdAt = 0,
                updatedAt = 0
            )
        )
        store.setApiKey("cfg-1", "sk-must-never-leak")

        val body = get("/v1/state").body
        assertFalse(body.contains("sk-must-never-leak"))
        assertFalse("本阶段 state 根本不下发 api_configs", body.contains("api_configs"))
    }

    // ------------------------------------------------------------------ /v1/sessions

    @Test
    fun `session list carries lastMessageAt unread and lastBlockIndex`() {
        store.appendMessage(sessionId, MessageRole.USER, "你好")
        val assistant = store.appendMessage(sessionId, MessageRole.ASSISTANT, "在的")

        val json = bodyOf(get("/v1/sessions"))
        val session = json.objects("sessions").single()
        assertEquals(sessionId, session.str("id"))
        assertEquals(assistant.createdAt, session.long("lastMessageAt"))
        assertEquals(1, session.int("unread"))
        assertEquals(0, session.int("lastBlockIndex"))
        assertEquals(2, session.int("messageCount"))
        assertEquals(store.cursor(), json.long("serverCursor"))
    }

    @Test
    fun `session list can be filtered by persona and includes empty sessions`() {
        assertEquals(1, bodyOf(get("/v1/sessions")).objects("sessions").size)
        assertEquals(
            0,
            bodyOf(get("/v1/sessions?personaId=${FileStore.SEED_PERSONA_2_ID}")).objects("sessions").size
        )
        assertEquals(400, get("/v1/sessions?personaId=../x").status)
    }

    // ------------------------------------------------------------------ 分页

    @Test
    fun `message page defaults to the newest block`() {
        repeat(250) { store.appendMessage(sessionId, MessageRole.USER, "m$it") }

        val json = bodyOf(get("/v1/sessions/$sessionId/messages"))
        assertEquals(1, json.int("blockIndex"))
        assertEquals(50, json.objects("messages").size)
        assertTrue(json.bool("hasMoreOlder"))
        assertFalse(json.bool("hasMoreNewer"))
        assertEquals("m200", json.objects("messages").first().str("content"))
    }

    @Test
    fun `paging walks one block at a time in both directions`() {
        repeat(250) { store.appendMessage(sessionId, MessageRole.USER, "m$it") }

        val older = bodyOf(get("/v1/sessions/$sessionId/messages?beforeBlock=1&limitBlocks=1"))
        assertEquals(0, older.int("blockIndex"))
        assertEquals(200, older.objects("messages").size)
        assertFalse(older.bool("hasMoreOlder"))
        assertTrue(older.bool("hasMoreNewer"))

        val newer = bodyOf(get("/v1/sessions/$sessionId/messages?afterBlock=0"))
        assertEquals(1, newer.int("blockIndex"))
        assertEquals(50, newer.objects("messages").size)
        assertTrue(newer.bool("hasMoreOlder"))
        assertFalse(newer.bool("hasMoreNewer"))

        // 翻过头：没有这一页，但反方向还有内容（blockIndex 是 null，客户端据此停手）
        val past = bodyOf(get("/v1/sessions/$sessionId/messages?beforeBlock=0"))
        assertTrue(past.fields["blockIndex"] is JsonValue.Null)
        assertFalse(past.bool("hasMoreOlder"))
        assertTrue(past.bool("hasMoreNewer"))

        val newest = bodyOf(get("/v1/sessions/$sessionId/messages?afterBlock=1"))
        assertTrue(newest.fields["blockIndex"] is JsonValue.Null)
        assertTrue(newest.bool("hasMoreOlder"))
        assertFalse(newest.bool("hasMoreNewer"))
    }

    @Test
    fun `beforeBlock skips a numbering hole left by an emptied block`() {
        repeat(500) { store.appendMessage(sessionId, MessageRole.USER, "m$it") }
        assertEquals(listOf(0, 1, 2), store.blocks(sessionId).map { it.index })

        for (message in store.blockMessages(sessionId, 1)) {
            assertTrue(store.deleteMessage(sessionId, message.id))
        }
        assertEquals(listOf(0, 2), store.blocks(sessionId).map { it.index })

        val json = bodyOf(get("/v1/sessions/$sessionId/messages?beforeBlock=2"))
        assertEquals("跳过空洞 1，取到 0", 0, json.int("blockIndex"))
        assertEquals(200, json.objects("messages").size)
        assertFalse(json.bool("hasMoreOlder"))
        assertTrue(json.bool("hasMoreNewer"))
    }

    @Test
    fun `an empty session answers an empty page instead of an error`() {
        val json = bodyOf(get("/v1/sessions/$sessionId/messages"))
        assertTrue(json.fields["blockIndex"] is JsonValue.Null)
        assertTrue(json.objects("messages").isEmpty())
        assertFalse(json.bool("hasMoreOlder"))
        assertFalse(json.bool("hasMoreNewer"))
    }

    @Test
    fun `paging rejects offsets oversized pages and unknown sessions`() {
        val tooMany = get("/v1/sessions/$sessionId/messages?limitBlocks=2")
        assertEquals(400, tooMany.status)
        assertTrue(tooMany.body.contains("unsupported_limit_blocks"))

        assertEquals(400, get("/v1/sessions/$sessionId/messages?beforeBlock=abc").status)
        assertEquals(400, get("/v1/sessions/$sessionId/messages?beforeBlock=-1").status)
        assertEquals(
            400,
            get("/v1/sessions/$sessionId/messages?beforeBlock=1&afterBlock=0").status
        )
        assertEquals(404, get("/v1/sessions/does-not-exist/messages").status)
        assertEquals("路径穿越 id 直接 404，不碰文件系统", 404, get("/v1/sessions/../messages").status)
        assertEquals(404, get("/v1/sessions/$sessionId/nope").status)
        assertEquals(405, AuthorityRoutes.handle(services, "POST", "/v1/sessions/$sessionId/messages", "{}").status)
    }

    // ------------------------------------------------------------------ /v1/events

    @Test
    fun `events return immediately when there is something new`() {
        val user = store.appendMessage(sessionId, MessageRole.USER, "事件")

        val json = bodyOf(get("/v1/events?since=0&timeoutSeconds=1"))
        assertEquals(store.cursor(), json.long("cursor"))
        val events = json.objects("events")
        assertEquals(1, events.size)
        assertEquals("message.created", events.single().str("type"))
        assertEquals(user.id, events.single().obj("payload").obj("message").str("id"))
    }

    @Test
    fun `events long poll times out with an empty page and does not advance the cursor`() {
        val since = store.cursor()
        val startedAt = System.nanoTime()
        val json = bodyOf(get("/v1/events?since=$since&timeoutSeconds=1"))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("应该真的等到超时：${elapsedMs}ms", elapsedMs >= 900)
        assertTrue(json.objects("events").isEmpty())
        assertEquals("空轮询不能前移游标", since, json.long("cursor"))
    }

    @Test
    fun `events long poll wakes up as soon as a message arrives`() {
        val since = store.cursor()
        val pending = CompletableFuture.supplyAsync {
            AuthorityRoutes.handle(services, "GET", "/v1/events?since=$since&timeoutSeconds=5", null)
        }
        Thread.sleep(200)
        store.appendMessage(sessionId, MessageRole.USER, "醒醒")

        val response = pending.get(5, TimeUnit.SECONDS)
        assertEquals(200, response.status)
        val events = TestSupport.parse(response.body).objects("events")
        assertEquals(1, events.size)
        assertEquals("message.created", events.single().str("type"))
    }

    @Test
    fun `events reject a negative cursor`() {
        assertEquals(400, get("/v1/events?since=-2").status)
        assertEquals(400, get("/v1/events?limit=x").status)
    }

    // ------------------------------------------------------------------ 未启用 store

    @Test
    fun `authoritative routes answer 503 when no data directory is configured`() {
        val bare = AgentdServices(store = null)
        val response = AuthorityRoutes.handle(bare, "GET", "/v1/state", null)
        assertEquals(503, response.status)
        assertTrue(response.body.contains("state_unavailable"))
        assertTrue(Files.isDirectory(root))
    }
}
