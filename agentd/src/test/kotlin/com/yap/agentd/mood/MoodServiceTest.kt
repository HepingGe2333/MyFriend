package com.yap.agentd.mood

import com.yap.agentd.AgentdServices
import com.yap.agentd.MoodRoutes
import com.yap.agentd.TestSupport
import com.yap.agentd.bool
import com.yap.agentd.int
import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.llmAccess
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.obj
import com.yap.agentd.objects
import com.yap.agentd.store.FileStore
import com.yap.agentd.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 服务端情绪判定（`POST /v1/personas/{id}/mood/evaluate`）：
 *
 * - 真调模型（假 SSE 服务端）→ 范围/幅度限制 → 落库 + `mood.changed`；
 * - 没明显变化不写；
 * - 无配置/上游失败 → 200 + `source=decay`（规范 §2 最后一条：不报错）；
 * - `/v1/state` 下发多维字段、旧数据迁移。
 */
class MoodServiceTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private lateinit var server: FakeLlmServer
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli() // 20:00 本地

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-mood-test")
        store = FileStore.open(root, { now })
        server = FakeLlmServer { exchange ->
            exchange.sse(
                listOf(
                    FakeLlmServer.chunk(
                        """{"label":"有点累","valence":-1,"arousal":2,"dominance":3,"intensity":3,"reason":"连着加班"}"""
                    ),
                    FakeLlmServer.DONE
                )
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        TestSupport.deleteRecursively(root)
    }

    private fun service(withConfig: Boolean = true, clockMillis: Long = now): MoodService {
        if (withConfig) store.pushLlmConfig(server.baseUrl)
        val access = llmAccess(store)
        return MoodService(store, access, Clock.fixed(Instant.ofEpochMilli(clockMillis), zone))
    }

    @Test
    fun `evaluate stores the multidimensional mood and emits mood changed`() {
        store.appendMessage(FileStore.SEED_SESSION_ID, "USER", "最近是不是很累")
        val outcome = service().evaluate(personaId, now)

        assertTrue(outcome.changed)
        assertEquals(MoodService.Source.LLM, outcome.source)
        val mood = store.mood(personaId)!!
        assertEquals("有点累", mood.label)
        assertEquals(-1, mood.valence)
        assertEquals(2, mood.arousal)
        assertEquals(3, mood.dominance)
        assertEquals(3, mood.intensity)
        assertEquals("连着加班", mood.reason)

        val events = store.awaitEvents(0, 0, 100).events
        assertTrue("要发 mood.changed", events.any { it.type == "mood.changed" })
        val eventMood = events.last { it.type == "mood.changed" }.payload.fields["mood"] as com.yap.app.core.json.JsonValue.Obj
        assertEquals(-1, (eventMood.fields["valence"] as com.yap.app.core.json.JsonValue.Num).asInt)

        // 请求形状：system + user，user 里带上一轮心情与最近对话。
        val body = TestSupport.parse(server.onlyRequest().body)
        val messages = body.objects("messages")
        assertEquals(2, messages.size)
        assertTrue(messages[0].str("content").contains("情绪判定器"))
        assertTrue(messages[1].str("content").contains("## 上一轮心情"))
        assertTrue(messages[1].str("content").contains("最近是不是很累"))
    }

    @Test
    fun `evaluate limits a single mood swing to two steps`() {
        store.setMood(personaId, "雀跃", 4, valence = 2, arousal = 4, dominance = 4)
        server.close()
        server = FakeLlmServer { exchange ->
            exchange.json(
                200,
                """{"choices":[{"message":{"content":"{\"label\":\"低落\",\"valence\":-2,\"arousal\":0,\"dominance\":0,\"intensity\":4}"}}]}"""
            )
        }
        val outcome = service().evaluate(personaId, now)

        assertTrue(outcome.changed)
        assertEquals("2 → -2 只允许走 2 格", 0, outcome.values.valence)
        assertEquals(2, outcome.values.arousal)
        assertEquals(2, outcome.values.dominance)
    }

    @Test
    fun `no noticeable change does not write`() {
        store.setMood(personaId, "有点累", 3, valence = -1, arousal = 2, dominance = 3, reason = "连着加班")
        val before = store.mood(personaId)!!
        server.close()
        server = FakeLlmServer { exchange ->
            exchange.json(
                200,
                """{"choices":[{"message":{"content":"{\"label\":\"有点累\",\"valence\":-1,\"arousal\":2,\"dominance\":3,\"intensity\":3}"}}]}"""
            )
        }
        val outcome = service().evaluate(personaId, now)

        assertFalse(outcome.changed)
        assertEquals(MoodService.Source.LLM, outcome.source)
        assertEquals("没有明显变化就不写", before.updatedAt, store.mood(personaId)!!.updatedAt)
    }

    @Test
    fun `without config the service decays instead of failing`() {
        store.setMood(personaId, "雀跃", 4, valence = 2, arousal = 5, dominance = 5)
        val later = now + 3 * 3_600_000L + 10L
        val outcome = service(withConfig = false, clockMillis = later).evaluate(personaId, later)

        assertTrue(outcome.changed)
        assertEquals(MoodService.Source.DECAY, outcome.source)
        val mood = store.mood(personaId)!!
        assertEquals(0, mood.valence)
        assertEquals("3 小时 × 每小时 1 格", 2, mood.arousal)
        assertEquals(3, mood.dominance)
    }

    @Test
    fun `upstream failure falls back to decay without an error`() {
        store.setMood(personaId, "雀跃", 4, valence = 2, arousal = 5, dominance = 5)
        server.close()
        server = FakeLlmServer { exchange -> exchange.json(500, """{"error":{"message":"boom"}}""") }
        val later = now + 2 * 3_600_000L
        val outcome = service(clockMillis = later).evaluate(personaId, later)

        assertEquals(MoodService.Source.DECAY, outcome.source)
        assertEquals(1, store.mood(personaId)!!.valence)
    }

    @Test
    fun `route returns the mood json and state output carries all dimensions`() {
        val access = llmAccess(store)
        store.pushLlmConfig(server.baseUrl)
        val services = AgentdServices(store, llm = access)

        val response = MoodRoutes.handle(services, "POST", "/v1/personas/$personaId/mood/evaluate", null)

        assertEquals(200, response.status)
        val body = TestSupport.parse(response.body)
        assertEquals("llm", body.str("source"))
        assertEquals(true, body.bool("changed"))
        val mood = body.obj("mood")
        assertEquals("有点累", mood.str("label"))
        assertEquals(-1, mood.int("valence"))
        assertEquals(2, mood.int("arousal"))
        assertEquals(3, mood.int("dominance"))

        // `/v1/state` 的 mood 对象自动带多维字段（客户端按需读）。
        val snapshot = store.snapshot(0L, personaId)
        val moodJson = snapshot.mood!!.toJson()
        assertEquals(-1, (moodJson.fields["valence"] as com.yap.app.core.json.JsonValue.Num).asInt)
        assertEquals(3, (moodJson.fields["dominance"] as com.yap.app.core.json.JsonValue.Num).asInt)
    }

    @Test
    fun `route rejects unknown persona and wrong method`() {
        val services = AgentdServices(store, llm = llmAccess(store))
        assertEquals(404, MoodRoutes.handle(services, "POST", "/v1/personas/no-such/mood/evaluate", null).status)
        assertEquals(405, MoodRoutes.handle(services, "GET", "/v1/personas/$personaId/mood/evaluate", null).status)
    }
}
