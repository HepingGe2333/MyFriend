package com.yap.agentd.tools

import com.yap.agentd.TestSupport
import com.yap.agentd.search.FakeHttpServer
import com.yap.agentd.search.WebSearchService
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.StoredIntent
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path

/**
 * 工具执行器：每个工具真的做了它承诺的事（写库/写文件/发请求），以及坏参数不会炸。
 *
 * 为什么不用 mock：这些工具的产物就是**服务端存储**（心情/活动/记忆/日记/表情/念头），
 * 用真实 `FileStore` 才能同时验证「模型调用之后，`/v1/state` 与备份能不能看到」。
 */
class ToolExecutorTest {

    private lateinit var root: Path
    private val clock = TestSupport.FakeClock(1_700_000_000_000L)
    private lateinit var store: FileStore
    private lateinit var executor: ToolExecutor

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-tools")
        clock.now = 1_700_000_000_000L
        store = FileStore.open(root, clock.reader)
        executor = ToolExecutor(store, clock = java.time.Clock.fixed(
            java.time.Instant.ofEpochMilli(clock.now),
            java.time.ZoneId.of("Asia/Shanghai")
        ))
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun run(name: String, args: String): ToolExecutor.Outcome =
        executor.execute(name, args, ToolExecutor.Context(personaId, sessionId))

    // ------------------------------------------------------------------ stay_silent / defer_reply

    @Test
    fun `stay_silent asks for suppression and writes nothing`() {
        val outcome = run("stay_silent", """{"reason":"累了"}""")
        assertTrue(outcome.ok)
        assertTrue("必须让 ChatService 不落 assistant 消息", outcome.staySilent)
        assertTrue(outcome.attachments.isEmpty())
        assertEquals("没说话", outcome.trace.title)
        // 不产生念头、不改心情、不落消息。
        assertEquals(0, store.pendingIntents(personaId).size)
        assertNull(store.mood(personaId)?.takeIf { it.label == "累了" })
        assertEquals(0L, store.session(sessionId)!!.messageCount)
    }

    @Test
    fun `stay_silent is refused after two silent turns in a row`() {
        assertTrue(run("stay_silent", """{"reason":"第一次"}""").staySilent)
        assertTrue(run("stay_silent", """{"reason":"第二次"}""").staySilent)
        val third = run("stay_silent", """{"reason":"第三次"}""")
        assertFalse("连续两轮之后必须回一句", third.ok)
        assertFalse("这一轮不能沉默", third.staySilent)
        assertTrue(third.contentForModel.contains("必须回一句"))
        // 真的回了一句之后，还能再沉默。
        store.recordAssistantReply(personaId)
        assertTrue(run("stay_silent", """{"reason":"又可以了"}""").staySilent)
    }

    @Test
    fun `consecutive silent counter is persisted`() {
        run("stay_silent", "{}")
        val reopened = FileStore.open(root, clock.reader)
        assertEquals(1, reopened.autoState(personaId).consecutiveSilent)
        reopened.recordAssistantReply(personaId)
        assertEquals(0, FileStore.open(root, clock.reader).autoState(personaId).consecutiveSilent)
    }

    @Test
    fun `defer_reply writes a pending intent with clamped minutes and a deadline window`() {
        val outcome = run("defer_reply", """{"minutes":3000,"reason":"手头正忙"}""")
        assertTrue(outcome.ok)
        val intent = store.pendingIntents(personaId).single()
        assertEquals(StoredIntent.STATUS_PENDING, intent.status)
        assertEquals("分钟数必须夹到 720", clock.now + 720 * 60_000L, intent.earliestAt)
        assertEquals(ToolExecutor.DEFER_WINDOW_MS, intent.latestAt - intent.earliestAt)
        assertEquals("手头正忙", intent.note)
        assertEquals(sessionId, intent.sessionId)
        assertEquals("等会儿再说", outcome.trace.title)
    }

    @Test
    fun `defer_reply defaults to 45 minutes when minutes is missing or garbage`() {
        run("defer_reply", """{"minutes":"abc"}""")
        assertEquals(clock.now + 45 * 60_000L, store.pendingIntents(personaId).single().earliestAt)
    }

    // ------------------------------------------------------------------ set_mood / set_activity

    @Test
    fun `set_mood writes all four dimensions`() {
        val outcome = run(
            "set_mood",
            """{"label":"有点累","valence":-1,"arousal":1,"dominance":2,"intensity":4,"reason":"连着加班"}"""
        )
        assertTrue(outcome.ok)
        val mood = store.mood(personaId)!!
        assertEquals("有点累", mood.label)
        assertEquals(-1, mood.valence)
        assertEquals(1, mood.arousal)
        assertEquals(2, mood.dominance)
        assertEquals(4, mood.intensity)
        assertEquals("连着加班", mood.reason)
        assertEquals("心情变了", outcome.trace.title)
    }

    @Test
    fun `set_mood clamps dimensions and rejects a missing label`() {
        run("set_mood", """{"label":"烦","valence":-9,"arousal":99,"dominance":-4,"intensity":9}""")
        val mood = store.mood(personaId)!!
        assertEquals(-2, mood.valence)
        assertEquals(5, mood.arousal)
        assertEquals(0, mood.dominance)
        assertEquals(5, mood.intensity)

        val failure = run("set_mood", """{"reason":"没写词"}""")
        assertFalse(failure.ok)
        assertTrue(failure.contentForModel.contains("没有成功"))
    }

    @Test
    fun `set_mood legacy alias mood works too`() {
        assertTrue(run("set_mood", """{"mood":"雀跃","intensity":5}""").ok)
        assertEquals("雀跃", store.mood(personaId)!!.label)
    }

    @Test
    fun `set_activity writes an expiring activity and normalises enums`() {
        val outcome = run(
            "set_activity",
            """{"kind":"work","activity":"在事务所赶第三版方案","minutes":5,"availability":"busy"}"""
        )
        assertTrue(outcome.ok)
        val stored = store.activity(personaId)!!
        val json = Json.parse(stored) as JsonValue.Obj
        assertEquals("WORK", (json.fields["kind"] as JsonValue.Str).value)
        assertEquals("在事务所赶第三版方案", (json.fields["activity"] as JsonValue.Str).value)
        assertEquals("BUSY", (json.fields["availability"] as JsonValue.Str).value)
        assertEquals("分钟数必须夹到下限 15", clock.now + 15 * 60_000L, (json.fields["until"] as JsonValue.Num).asLong)
    }

    @Test
    fun `set_activity requires a description`() {
        val outcome = run("set_activity", """{"minutes":30}""")
        assertFalse(outcome.ok)
        assertNull(store.activity(personaId))
    }

    // ------------------------------------------------------------------ diary

    @Test
    fun `diary add list and remove round trip`() {
        val added = run("diary", """{"action":"add","content":"记得周四给妈妈打电话","remindInMinutes":30}""")
        assertTrue(added.ok)
        val note = store.diaryNotes(personaId).single()
        assertEquals("记得周四给妈妈打电话", note.content)
        assertEquals("remindInMinutes 要换算成绝对时刻", clock.now + 30 * 60_000L, note.remindAt)
        assertEquals("写日记", added.trace.title)

        val listed = run("diary", """{"action":"list","limit":5}""")
        assertTrue(listed.ok)
        assertTrue(listed.contentForModel.contains("记得周四给妈妈打电话"))
        assertTrue(listed.contentForModel.contains(note.id))

        assertTrue(run("diary", """{"action":"remove","noteId":"${note.id}"}""").ok)
        assertTrue(store.diaryNotes(personaId).isEmpty())
    }

    @Test
    fun `diary add without content fails without writing`() {
        val outcome = run("diary", """{"action":"add"}""")
        assertFalse(outcome.ok)
        assertTrue(store.diaryNotes(personaId).isEmpty())
    }

    @Test
    fun `diary remove of an unknown id returns ok=false`() {
        val outcome = run("diary", """{"action":"remove","noteId":"nope"}""")
        assertFalse(outcome.ok)
        assertTrue(outcome.contentForModel.contains("没找到"))
    }

    @Test
    fun `diary list on an empty board is honest`() {
        val outcome = run("diary", """{"action":"list"}""")
        assertTrue(outcome.ok)
        assertTrue(outcome.contentForModel.contains("空的"))
    }

    // ------------------------------------------------------------------ remember / recall

    @Test
    fun `remember writes a memory and recall finds it`() {
        val remembered = run(
            "remember",
            """{"content":"对方提过下个月要去哈尔滨，怕冷","importance":5,"tags":["哈尔滨","怕冷"]}"""
        )
        assertTrue(remembered.ok)
        val memory = store.memories(personaId).single()
        assertEquals("5 → 100 分制", 100, memory.importance)
        assertEquals(listOf("哈尔滨", "怕冷"), memory.tags)

        val recalled = run("recall", """{"query":"哈尔滨"}""")
        assertTrue(recalled.ok)
        assertTrue(recalled.contentForModel.contains("怕冷"))
        assertEquals("想起了 1 条", recalled.summary)
    }

    @Test
    fun `recall misses are reported as an empty result`() {
        val outcome = run("recall", """{"query":"不存在的词"}""")
        assertTrue(outcome.ok)
        assertTrue(outcome.contentForModel.contains("没翻到什么"))
    }

    @Test
    fun `remember clamps importance and rejects empty content`() {
        run("remember", """{"content":"一件小事","importance":99}""")
        assertEquals(100, store.memories(personaId).single().importance)
        assertFalse(run("remember", """{"importance":3}""").ok)
        assertEquals(1, store.memories(personaId).size)
    }

    // ------------------------------------------------------------------ web_search / web_fetch

    @Test
    fun `web_search calls the server provider and returns normalised hits`() {
        val upstream = """{"results":[{"title":"杭州天气","url":"https://w.example/hz","snippet":"<b>多云</b>"}]}"""
        FakeHttpServer { FakeHttpServer.FakeResponse(200, upstream) }.use { server ->
            val search = WebSearchService()
            val withEnv = ToolExecutor(
                store,
                search,
                env = { name ->
                    when (name) {
                        ToolExecutor.ENV_SEARCH_BASE -> server.baseUrl
                        ToolExecutor.ENV_SEARCH_KEY -> "sk-test"
                        else -> null
                    }
                },
                clock = java.time.Clock.systemUTC()
            )
            val outcome = withEnv.execute(
                "web_search",
                """{"query":"杭州 天气","count":3}""",
                ToolExecutor.Context(personaId, sessionId)
            )
            assertTrue(outcome.ok)
            assertTrue(outcome.contentForModel.contains("杭州天气"))
            assertTrue("标签要去掉", outcome.contentForModel.contains("多云"))
            assertEquals("联网搜索", outcome.trace.title)
            val request = server.onlyRequest()
            assertTrue("key 只从服务端环境来", request.header("Authorization").orEmpty().contains("sk-test"))
        }
    }

    @Test
    fun `web_fetch reads a page through the same url policy`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, "<html><title>示例</title><p>正文</p></html>") }.use { server ->
            val search = WebSearchService(urlPolicy = { null })
            val withSearch = ToolExecutor(store, search, clock = java.time.Clock.systemUTC())
            val outcome = withSearch.execute(
                "web_fetch",
                """{"url":"${server.baseUrl}/page"}""",
                ToolExecutor.Context(personaId, sessionId)
            )
            assertTrue(outcome.ok)
            assertTrue(outcome.contentForModel.contains("正文"))
            assertEquals("打开链接", outcome.trace.title)
        }
    }

    @Test
    fun `web_search without a url reports the failure instead of throwing`() {
        val withBadUrl = ToolExecutor(store, WebSearchService(urlPolicy = { null }))
        val outcome = withBadUrl.execute(
            "web_fetch",
            """{"url":"file:///etc/passwd"}""",
            ToolExecutor.Context(personaId, sessionId)
        )
        assertFalse(outcome.ok)
        assertFalse(outcome.trace.ok)
    }

    // ------------------------------------------------------------------ sticker

    @Test
    fun `sticker send attaches an existing sticker and list shows it`() {
        val sticker = store.createSticker(
            personaId = personaId,
            mime = MediaMime.GIF,
            data = GIF_BYTES,
            width = 1,
            height = 1,
            frames = 1
        )
        val sent = run("sticker", """{"action":"send","stickerId":"${sticker.id}"}""")
        assertTrue(sent.ok)
        assertEquals(listOf(sticker.mediaUrl), sent.attachments)

        val listed = run("sticker", """{"action":"list"}""")
        assertTrue(listed.ok)
        assertTrue(listed.contentForModel.contains(sticker.id))
        // 其它人格的表情不出现在列表里。
        val otherPersona = FileStore.SEED_PERSONA_2_ID
        val other = executor.execute(
            "sticker",
            """{"action":"list"}""",
            ToolExecutor.Context(otherPersona, sessionId)
        )
        assertFalse(other.contentForModel.contains(sticker.id))
    }

    @Test
    fun `sticker send of an unknown id fails and make is reported as unsupported`() {
        assertFalse(run("sticker", """{"action":"send","stickerId":"nope"}""").ok)
        val make = run("sticker", """{"action":"make","url":"https://example.com/a.gif"}""")
        assertFalse(make.ok)
        assertTrue(make.contentForModel.contains("还不能现做表情"))
    }

    @Test
    fun `unknown tools and broken arguments never throw`() {
        val unknown = run("nope", "{}")
        assertFalse(unknown.ok)
        assertTrue(unknown.contentForModel.contains("不认识的工具"))

        val broken = run("set_mood", "{not json")
        assertFalse(broken.ok)
        assertNotNull(broken.summary)
    }

    @Test
    fun `trace json matches the client ToolTraceItem shape`() {
        val outcome = run("diary", """{"action":"add","content":"买牛奶"}""")
        val encoded = executor.encodeTrace(listOf(outcome.trace))!!
        val items = Json.parse(encoded) as JsonValue.Arr
        val item = items.items.single() as JsonValue.Obj
        assertEquals("diary", (item.fields["name"] as JsonValue.Str).value)
        assertEquals("写日记", (item.fields["title"] as JsonValue.Str).value)
        assertEquals("买牛奶", (item.fields["detail"] as JsonValue.Str).value)
        assertEquals(true, (item.fields["ok"] as JsonValue.Bool).value)
        assertEquals("副标题是内容摘要", "买牛奶", (item.fields["subtitle"] as JsonValue.Str).value)
        assertNull("空 traces 不产生 JSON", executor.encodeTrace(emptyList()))
    }

    companion object {
        /** 1×1 的透明 GIF（42 字节），测试里当表情用。 */
        val GIF_BYTES: ByteArray = Base64Gif.decode()

        private object Base64Gif {
            fun decode(): ByteArray =
                java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        }
    }
}
