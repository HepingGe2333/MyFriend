package com.myfriend.agentd

import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.MessageRole
import com.myfriend.agentd.store.StoreJson
import com.myfriend.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `docs/design/server-authoritative.md` §3.6 的 CRUD handler 级测试。
 *
 * 直接调 [AuthorityRoutes.handle]（不起 socket），覆盖每个路由的正常路径、400/404/405，
 * 以及「删除人格级联」「并发写同一人格不丢字段」这些存储层契约。
 */
class CrudRoutesTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-crud-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun call(method: String, path: String, body: String? = null): AgentdApi.Response =
        AuthorityRoutes.handle(AgentdServices(store), method, path, body)

    private fun body(response: AgentdApi.Response): JsonValue.Obj = TestSupport.parse(response.body)

    private fun errorCode(response: AgentdApi.Response): String = body(response).obj("error").str("code")

    // ------------------------------------------------------------------ 会话

    @Test
    fun `creating a session for the seeded persona returns the existing one`() {
        val response = call("POST", "/v1/sessions", """{"personaId":"seed-linshen"}""")
        assertEquals(200, response.status)
        val json = body(response)
        assertFalse(json.bool("created"))
        assertEquals(FileStore.SEED_SESSION_ID, json.obj("session").str("id"))
        assertEquals(1, store.sessions().size)
    }

    @Test
    fun `one persona one session is enforced for a new persona`() {
        val first = call("POST", "/v1/sessions", """{"personaId":"seed-alan","title":"和阿岚"}""")
        assertEquals(200, first.status)
        assertTrue(body(first).bool("created"))
        val sessionId = body(first).obj("session").str("id")
        val second = call("POST", "/v1/sessions", """{"personaId":"seed-alan","title":"想覆盖"}""")
        assertEquals(200, second.status)
        assertFalse(body(second).bool("created"))
        assertEquals(sessionId, body(second).obj("session").str("id"))
        assertEquals("第一次的标题保留", "和阿岚", body(second).obj("session").str("title"))
        assertEquals(1, store.sessions().count { it.personaId == "seed-alan" })
    }

    @Test
    fun `creating a session validates personaId and existence`() {
        assertEquals(400, call("POST", "/v1/sessions", "{}").status)
        assertEquals(400, call("POST", "/v1/sessions", """{"personaId":"../bad"}""").status)
        val missing = call("POST", "/v1/sessions", """{"personaId":"does-not-exist"}""")
        assertEquals(404, missing.status)
        assertEquals("not_found", errorCode(missing))
    }

    @Test
    fun `read clears unread and returns readAt`() {
        val message = store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.ASSISTANT, "在")
        assertEquals(1, store.session(FileStore.SEED_SESSION_ID)!!.unread)

        val response = call("POST", "/v1/sessions/${FileStore.SEED_SESSION_ID}/read", "{}")
        assertEquals(200, response.status)
        val json = body(response)
        assertEquals(0, json.int("unread"))
        assertEquals(1, json.int("marked"))
        assertEquals(clock.now, json.long("readAt"))
        assertEquals(0, store.session(FileStore.SEED_SESSION_ID)!!.unread)
        assertNotNull(store.findMessage(FileStore.SEED_SESSION_ID, message.id)?.readAt)
    }

    @Test
    fun `read upTo marks older messages but still clears the counter`() {
        val older = store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.ASSISTANT, "一")
        clock.advance(10)
        val newer = store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.ASSISTANT, "二")

        val response = call(
            "POST",
            "/v1/sessions/${FileStore.SEED_SESSION_ID}/read",
            """{"upTo":${older.createdAt}}"""
        )
        assertEquals(200, response.status)
        assertEquals(1, body(response).int("marked"))
        assertNotNull(store.findMessage(FileStore.SEED_SESSION_ID, older.id)?.readAt)
        assertNull(store.findMessage(FileStore.SEED_SESSION_ID, newer.id)?.readAt)
        assertEquals(0, store.session(FileStore.SEED_SESSION_ID)!!.unread)
    }

    @Test
    fun `read rejects unknown sessions and negative upTo`() {
        assertEquals(404, call("POST", "/v1/sessions/nope/read", "{}").status)
        assertEquals(
            400,
            call("POST", "/v1/sessions/${FileStore.SEED_SESSION_ID}/read", """{"upTo":-1}""").status
        )
    }

    // ------------------------------------------------------------------ 人格

    @Test
    fun `persona list carries client fields and parsed activity`() {
        val created = call(
            "POST",
            "/v1/personas",
            """
            {"id":"seed-linshen","worldJson":"{\"worldName\":\"临港\"}",
             "speechStyleJson":"{\"tone\":\"简短\"}","traits":["慢热"],"likes":["雨"],
             "behaviors":["倾听"],"backstory":"过去","quirks":"口头禅",
             "translationJson":null,"translationLang":"en","mystery":{"a":1}}
            """.trimIndent()
        )
        assertEquals(200, created.status)

        val list = call("GET", "/v1/personas")
        assertEquals(200, list.status)
        val persona = body(list).objects("personas").first { it.str("id") == "seed-linshen" }
        assertEquals("""{"worldName":"临港"}""", persona.str("worldJson"))
        assertEquals("""{"tone":"简短"}""", persona.str("speechStyleJson"))
        assertEquals(listOf("慢热"), persona.strings("traits"))
        assertEquals("en", persona.str("translationLang"))
        assertEquals(1, persona.obj("mystery").int("a"))
        assertEquals(JsonValue.Null, persona.fields["activity"])
    }

    @Test
    fun `persona create keeps unknown fields and update merges only supplied ones`() {
        val created = call(
            "POST",
            "/v1/personas",
            """{"name":"新人","description":"旧描述","worldJson":"W","unknownField":{"x":1}}"""
        )
        assertEquals(200, created.status)
        val id = body(created).obj("persona").str("id")
        assertEquals("W", body(created).obj("persona").str("worldJson"))
        assertEquals(1, body(created).obj("persona").obj("unknownField").int("x"))

        val updated = call(
            "POST",
            "/v1/personas",
            """{"id":"$id","description":"新描述","likes":["咖啡"]}"""
        )
        assertEquals(200, updated.status)
        val persona = body(updated).obj("persona")
        assertEquals("新人", persona.str("name"))
        assertEquals("新描述", persona.str("description"))
        assertEquals("W", persona.str("worldJson"))
        assertEquals(1, persona.obj("unknownField").int("x"))
        assertEquals(listOf("咖啡"), persona.strings("likes"))
    }

    @Test
    fun `persona create and update validate id and required name`() {
        assertEquals(400, call("POST", "/v1/personas", """{"description":"没有名字"}""").status)
        assertEquals(400, call("POST", "/v1/personas", """{"id":"../x","name":"x"}""").status)
        val missing = call("POST", "/v1/personas", """{"id":"nope","name":"x"}""")
        assertEquals(404, missing.status)
        assertEquals("not_found", errorCode(missing))
    }

    @Test
    fun `deleting a persona cascades sessions messages memories mood presence and proactive state`() {
        val created = call("POST", "/v1/personas", """{"name":"要删的人"}""")
        val id = body(created).obj("persona").str("id")
        val session = call("POST", "/v1/sessions", """{"personaId":"$id"}""")
        val sessionId = body(session).obj("session").str("id")
        store.appendMessage(sessionId, MessageRole.USER, "你好")
        store.appendMessage(sessionId, MessageRole.ASSISTANT, "在")
        val memory = call("POST", "/v1/personas/$id/memories", """{"content":"记得"}""")
        val memoryId = body(memory).obj("memory").str("id")
        store.setMood(id, "开心", 3)
        store.setPresence(id, "IDLE", "家")
        store.recordProactiveFired(id, clock.now, "2026-11-16")

        val deleted = call("DELETE", "/v1/personas/$id")
        assertEquals(200, deleted.status)
        assertNull(store.persona(id))
        assertNull(store.session(sessionId))
        assertNull(store.memory(memoryId))
        assertNull(store.mood(id))
        assertNull(store.presence(id))
        assertFalse(Files.exists(root.resolve("sessions/$sessionId")))
        assertFalse(Files.readString(root.resolve("memories.json")).contains(id))
        assertFalse(Files.readString(root.resolve("proactive_state.json")).contains(id))
        assertTrue("媒体目录不随人格删除", Files.isDirectory(root.resolve(FileStore.MEDIA_DIR)))
    }

    @Test
    fun `deleting an unknown persona is 404`() {
        val response = call("DELETE", "/v1/personas/nope")
        assertEquals(404, response.status)
        assertEquals("not_found", errorCode(response))
    }

    // ------------------------------------------------------------------ 记忆

    @Test
    fun `memory create list and update round trip`() {
        val created = call(
            "POST",
            "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories",
            """{"content":"他喜欢雨天","importance":80,"tags":["天气","偏好"]}"""
        )
        assertEquals(200, created.status)
        val memory = body(created).obj("memory")
        val id = memory.str("id")
        assertEquals(FileStore.SEED_PERSONA_1_ID, memory.str("personaId"))
        assertEquals(80, memory.int("importance"))
        assertEquals(listOf("天气", "偏好"), memory.strings("tags"))
        val createdAt = memory.long("createdAt")

        clock.advance(50)
        val updated = call(
            "POST",
            "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories",
            """{"id":"$id","content":"他更喜欢雨天","importance":90}"""
        )
        val after = body(updated).obj("memory")
        assertEquals(createdAt, after.long("createdAt"))
        assertEquals(clock.now, after.long("updatedAt"))
        assertEquals(90, after.int("importance"))
        assertEquals("标签未提供时保留", listOf("天气", "偏好"), after.strings("tags"))

        // 也接受客户端数据库里的 JSON 字符串标签。
        val stringTags = call(
            "POST",
            "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories",
            """{"id":"$id","tags":"[\"新标签\"]"}"""
        )
        assertEquals(listOf("新标签"), body(stringTags).obj("memory").strings("tags"))

        val list = call("GET", "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories")
        assertEquals(1, body(list).objects("memories").size)
    }

    @Test
    fun `memory list applies limit and since`() {
        val first = call(
            "POST",
            "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories",
            """{"content":"一"}"""
        )
        val firstUpdated = body(first).obj("memory").long("updatedAt")
        clock.advance(10)
        call("POST", "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories", """{"content":"二"}""")

        val limited = call("GET", "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories?limit=1")
        assertEquals(1, body(limited).objects("memories").size)
        val since = call("GET", "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories?since=$firstUpdated")
        val contents = body(since).objects("memories").map { it.str("content") }
        assertEquals(listOf("二"), contents)
    }

    @Test
    fun `memory create validates fields and ownership`() {
        val missing = call("POST", "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories", "{}")
        assertEquals(400, missing.status)
        assertEquals("missing_field", errorCode(missing))
        assertEquals(
            400,
            call("POST", "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories", """{"content":"x","importance":101}""").status
        )
        assertEquals(
            404,
            call("POST", "/v1/personas/does-not-exist/memories", """{"content":"x"}""").status
        )
        assertEquals(
            404,
            call("POST", "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories", """{"id":"nope","content":"x"}""").status
        )
        // 别的记忆 id 不能被挂到另一个人格下。
        val other = call("POST", "/v1/personas/seed-alan/memories", """{"content":"别人的"}""")
        val otherId = body(other).obj("memory").str("id")
        assertEquals(
            404,
            call("POST", "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories", """{"id":"$otherId","content":"x"}""").status
        )
    }

    @Test
    fun `deleting a memory is idempotent only for existing ids`() {
        val created = call(
            "POST",
            "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories",
            """{"content":"会删"}"""
        )
        val id = body(created).obj("memory").str("id")
        assertEquals(200, call("DELETE", "/v1/memories/$id").status)
        assertNull(store.memory(id))
        assertEquals(404, call("DELETE", "/v1/memories/$id").status)
    }

    // ------------------------------------------------------------------ 预设

    @Test
    fun `preset create update and delete keep world and style fields`() {
        val created = call(
            "POST",
            "/v1/presets",
            """{"name":"夏夜","description":"旧","worldJson":"W","speechStyleJson":"S","mystery":2}"""
        )
        assertEquals(200, created.status)
        val id = body(created).obj("preset").str("id")
        assertEquals("W", body(created).obj("preset").str("worldJson"))
        assertEquals("S", body(created).obj("preset").str("speechStyleJson"))
        assertEquals(2, body(created).obj("preset").int("mystery"))

        val updated = call("POST", "/v1/presets", """{"id":"$id","description":"新"}""")
        assertEquals("旧世界保留", "W", body(updated).obj("preset").str("worldJson"))
        assertEquals("新", body(updated).obj("preset").str("description"))

        val list = call("GET", "/v1/presets")
        assertEquals(1, body(list).objects("presets").size)
        assertEquals(200, call("DELETE", "/v1/presets/$id").status)
        assertEquals(0, body(call("GET", "/v1/presets")).objects("presets").size)
        assertEquals(404, call("DELETE", "/v1/presets/$id").status)
    }

    @Test
    fun `preset create requires a name and update needs an existing id`() {
        assertEquals(400, call("POST", "/v1/presets", "{}").status)
        assertEquals(404, call("POST", "/v1/presets", """{"id":"nope","name":"x"}""").status)
    }

    // ------------------------------------------------------------------ 设置

    @Test
    fun `settings merge accepts only the whitelist and never stores api keys`() {
        val response = call(
            "POST",
            "/v1/settings",
            """{"themeMode":"DARK","fontScale":1.2,"apiKey":"sk-secret","secrets":{"x":"y"},"unknown":1}"""
        )
        assertEquals(200, response.status)
        val settings = body(response).obj("settings")
        assertEquals("DARK", settings.str("themeMode"))
        assertEquals(1.2, (settings.fields["fontScale"] as JsonValue.Num).asDouble, 0.0001)
        assertFalse(settings.fields.containsKey("apiKey"))
        assertFalse(settings.fields.containsKey("secrets"))
        assertFalse(settings.fields.containsKey("unknown"))

        val disk = Files.readString(root.resolve(FileStore.SETTINGS_FILE))
        assertFalse("settings.json 不能出现客户端传的密钥：$disk", disk.contains("sk-secret"))
        val get = call("GET", "/v1/settings")
        assertEquals("DARK", body(get).obj("settings").str("themeMode"))
    }

    @Test
    fun `settings never leaks pre-existing secrets on disk`() {
        store.setSetting("apiKey", JsonValue.Str("sk-old"))
        store.setSetting("themeMode", JsonValue.Str("LIGHT"))
        val settings = body(call("GET", "/v1/settings")).obj("settings")
        assertFalse(settings.fields.containsKey("apiKey"))
        assertEquals("LIGHT", settings.str("themeMode"))
        // 下一次白名单写入会把旧盘上的 apiKey 一并清掉。
        call("POST", "/v1/settings", """{"fontScale":1.0}""")
        assertFalse(Files.readString(root.resolve(FileStore.SETTINGS_FILE)).contains("sk-old"))
    }

    @Test
    fun `settings validate types and active persona`() {
        assertEquals(400, call("POST", "/v1/settings", """{"fontScale":"big"}""").status)
        assertEquals(400, call("POST", "/v1/settings", """{"animationsEnabled":"yes"}""").status)
        assertEquals(400, call("POST", "/v1/settings", """{"themeMode":"NEON"}""").status)
        assertEquals(400, call("POST", "/v1/settings", """{"bubbleStyle":"FANCY"}""").status)
        assertEquals(400, call("POST", "/v1/settings", """{"activePersonaId":"nope"}""").status)
    }

    // ------------------------------------------------------------------ 方法与并发

    @Test
    fun `crud routes answer 405 for unsupported methods`() {
        assertEquals(405, call("DELETE", "/v1/settings").status)
        assertEquals(405, call("PUT", "/v1/personas").status)
        assertEquals(405, call("DELETE", "/v1/sessions").status)
        assertEquals(405, call("POST", "/v1/memories/x").status)
    }

    @Test
    fun `concurrent updates of the same persona do not lose fields`() {
        val threads = 8
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        repeat(threads) { index ->
            executor.submit {
                ready.countDown()
                start.await()
                val response = call(
                    "POST",
                    "/v1/personas",
                    """{"id":"seed-linshen","name":"林深","concurrent$index":$index}"""
                )
                assertEquals(200, response.status)
            }
        }
        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        val persona = store.persona(FileStore.SEED_PERSONA_1_ID)!!
        repeat(threads) { index ->
            assertEquals(
                "并发写不能丢字段 $index：${persona.extraFields.keys}",
                JsonValue.Num(index.toString()),
                persona.extraFields["concurrent$index"]
            )
        }
    }

    @Test
    fun `memory and preset and setting data survive a restart`() {
        val memory = call("POST", "/v1/personas/${FileStore.SEED_PERSONA_1_ID}/memories", """{"content":"别忘"}""")
        val memoryId = body(memory).obj("memory").str("id")
        val preset = call("POST", "/v1/presets", """{"name":"重启预设"}""")
        val presetId = body(preset).obj("preset").str("id")
        call("POST", "/v1/settings", """{"themeMode":"DARK"}""")

        val reopened = FileStore.open(root, clock.reader)
        assertEquals("别忘", reopened.memory(memoryId)?.content)
        assertEquals("重启预设", reopened.preset(presetId)?.name)
        assertEquals("DARK", reopened.publicSettings().str("themeMode"))
        assertTrue(Files.isRegularFile(root.resolve(FileStore.MEMORIES_FILE)))
        assertTrue(Files.isRegularFile(root.resolve(FileStore.PRESETS_FILE)))
    }
}
