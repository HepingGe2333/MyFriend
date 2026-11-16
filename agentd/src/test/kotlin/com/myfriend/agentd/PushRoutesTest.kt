package com.myfriend.agentd

import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.MessageRole
import com.myfriend.agentd.store.PushRegistration
import com.myfriend.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** `POST/DELETE /v1/push/register` + 可插拔 [PushSender] 的 M6 服务端测试。 */
class PushRoutesTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-push-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun call(method: String, path: String, body: String? = null): AgentdApi.Response =
        AuthorityRoutes.handle(AgentdServices(store), method, path, body)

    private fun json(response: AgentdApi.Response): JsonValue.Obj = TestSupport.parse(response.body)

    private fun errorCode(response: AgentdApi.Response): String = json(response).obj("error").str("code")

    @Test
    fun `push register stores a token and survives a restart`() {
        val response = call(
            "POST",
            "/v1/push/register",
            """{"platform":"ios","token":"token-1","bundleId":"com.example.myfriend"}"""
        )
        assertEquals(200, response.status)
        assertTrue(json(response).bool("registered"))
        assertTrue(json(response).bool("created"))
        assertEquals(1, store.pushTokens().size)
        assertEquals("ios", store.pushTokens().single().platform)
        assertEquals("com.example.myfriend", store.pushTokens().single().bundleId)
        assertTrue(Files.isRegularFile(root.resolve(FileStore.PUSH_TOKENS_FILE)))

        val reopened = FileStore.open(root, clock.reader)
        assertEquals("token-1", reopened.pushTokens().single().token)
    }

    @Test
    fun `push register is idempotent and updates the bundle id`() {
        call("POST", "/v1/push/register", """{"platform":"android","token":"token-a","bundleId":"old"}""")
        val first = store.pushTokens().single()
        clock.advance(10)
        val second = call(
            "POST",
            "/v1/push/register",
            """{"platform":"android","token":"token-a","packageName":"com.example.new"}"""
        )
        assertEquals(200, second.status)
        assertFalse("重复注册不是新建", json(second).bool("created"))
        assertEquals(1, store.pushTokens().size)
        assertEquals("com.example.new", store.pushTokens().single().bundleId)
        assertEquals(first.createdAt, store.pushTokens().single().createdAt)
        assertTrue(store.pushTokens().single().updatedAt > first.updatedAt)
    }

    @Test
    fun `push register validates platform token and bundle id`() {
        val platform = call("POST", "/v1/push/register", """{"platform":"windows","token":"t"}""")
        assertEquals(400, platform.status)
        assertEquals("invalid_argument", errorCode(platform))
        assertEquals(400, call("POST", "/v1/push/register", """{"platform":"ios"}""").status)
        assertEquals(400, call("POST", "/v1/push/register", """{"platform":"ios","token":"  "}""").status)
        assertEquals(405, call("GET", "/v1/push/register").status)
    }

    @Test
    fun `push unregister removes by body and is idempotent`() {
        call("POST", "/v1/push/register", """{"platform":"ios","token":"token-1"}""")
        val removed = call("DELETE", "/v1/push/register", """{"platform":"ios","token":"token-1"}""")
        assertEquals(200, removed.status)
        assertTrue(json(removed).bool("unregistered"))
        assertTrue(json(removed).bool("removed"))
        assertTrue(store.pushTokens().isEmpty())

        val again = call("DELETE", "/v1/push/register", """{"platform":"ios","token":"token-1"}""")
        assertEquals(200, again.status)
        assertFalse(json(again).bool("removed"))
    }

    @Test
    fun `push unregister accepts query parameters and requires a token`() {
        call("POST", "/v1/push/register", """{"platform":"android","token":"token-q"}""")
        val removed = call("DELETE", "/v1/push/register?platform=android&token=token-q")
        assertEquals(200, removed.status)
        assertTrue(store.pushTokens().isEmpty())
        assertEquals(400, call("DELETE", "/v1/push/register").status)
    }

    @Test
    fun `proactive message invokes the push sender when a token exists`() {
        var now = 1_600_000_000_000L
        val proactiveStore = FileStore.open(root, { now })
        proactiveStore.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.USER, "在吗")
        now += 6 * 60 * 60 * 1000L
        callOn(proactiveStore, "POST", "/v1/push/register", """{"platform":"ios","token":"push-1"}""")

        val sent = ArrayList<Pair<List<PushRegistration>, PushMessage>>()
        val services = AgentdServices(
            store = proactiveStore,
            proactiveRoll = { 0.0 },
            pushSender = PushSender { tokens, message -> sent += tokens to message }
        )
        val response = ToolsRoutes.handle(services, ToolsRoutes.TICK_PATH, """{"now":$now}""")
        assertEquals(200, response.status)
        assertEquals(1, sent.size)
        assertEquals("push-1", sent.single().first.single().token)
        assertEquals(FileStore.SEED_PERSONA_1_ID, sent.single().second.personaId)
        assertEquals(FileStore.SEED_SESSION_ID, sent.single().second.sessionId)
        assertTrue(sent.single().second.body.isNotBlank())
    }

    @Test
    fun `no push sender call happens without registered tokens`() {
        var now = 1_600_000_000_000L
        val proactiveStore = FileStore.open(root, { now })
        proactiveStore.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.USER, "在吗")
        now += 6 * 60 * 60 * 1000L

        val sent = ArrayList<PushMessage>()
        val services = AgentdServices(
            store = proactiveStore,
            proactiveRoll = { 0.0 },
            pushSender = PushSender { _, message -> sent += message }
        )
        val response = ToolsRoutes.handle(services, ToolsRoutes.TICK_PATH, """{"now":$now}""")
        assertEquals(200, response.status)
        assertTrue(sent.isEmpty())
        assertEquals("消息仍然落库", 1, proactiveStore.latestMessages(FileStore.SEED_SESSION_ID).count { it.source == "PROACTIVE" })
    }

    @Test
    fun `push sender failures never break the proactive tick`() {
        var now = 1_600_000_000_000L
        val proactiveStore = FileStore.open(root, { now })
        proactiveStore.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.USER, "在吗")
        now += 6 * 60 * 60 * 1000L
        callOn(proactiveStore, "POST", "/v1/push/register", """{"platform":"ios","token":"push-fail"}""")

        val services = AgentdServices(
            store = proactiveStore,
            proactiveRoll = { 0.0 },
            pushSender = PushSender { _, _ -> throw IllegalStateException("APNs down") }
        )
        val response = ToolsRoutes.handle(services, ToolsRoutes.TICK_PATH, """{"now":$now}""")
        assertEquals(200, response.status)
        assertEquals(1, proactiveStore.latestMessages(FileStore.SEED_SESSION_ID).count { it.source == "PROACTIVE" })
    }

    private fun callOn(target: FileStore, method: String, path: String, body: String? = null): AgentdApi.Response =
        AuthorityRoutes.handle(AgentdServices(target), method, path, body)
}
