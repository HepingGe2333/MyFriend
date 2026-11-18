package com.yap.agentd.automation

import com.yap.agentd.ReplyOutcome
import com.yap.agentd.ReplyGenerator
import com.yap.agentd.SendMessageRequest
import com.yap.agentd.TestSupport
import com.yap.agentd.Usage
import com.yap.agentd.AgentdServices
import com.yap.agentd.evolution.EvolutionService
import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfigResolver
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.mood.MoodService
import com.yap.agentd.store.FileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * 服务端自动判定：**每条聊天结束后**按 4 条 / 24h 判心情、按 20 条 / 24h 评估进化。
 *
 * 这条测试回答的是任务里那句话：「主动消息、日记提醒、情绪、进化全部**不依赖客户端连接**」。
 * 所以它走的是 [AgentdServices.chatService]（HTTP 层的那个入口）→ 自动触发 → 两天没连客户端也照跑。
 */
class AutoTriggersTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-auto")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun llmFor(server: FakeLlmServer): LlmAccess {
        store.pushLlmConfig(server.baseUrl)
        return LlmAccess(LlmClient(), LlmConfigResolver(store, loopback = true))
    }

    /** 假模型：心情判定回多维 JSON，进化评估回一条 traits 变化。 */
    private fun fakeLlm(): FakeLlmServer = FakeLlmServer { exchange ->
        val body = exchange.request.body
        if (body.contains("情绪判定器")) {
            exchange.json(
                200,
                """{"choices":[{"message":{"role":"assistant","content":""" +
                    """"{\"label\":\"有点累\",\"valence\":-1,\"arousal\":1,\"dominance\":2,\"intensity\":3,\"reason\":\"聊得有点久\"}"}}],""" +
                    """"usage":{"prompt_tokens":10,"completion_tokens":20}}"""
            )
        } else {
            exchange.json(
                200,
                """{"choices":[{"message":{"role":"assistant","content":""" +
                    """"{\"changes\":[{\"field\":\"traits\",\"op\":\"add\",\"value\":\"嘴硬心软\",\"reason\":\"测试\",\"confidence\":0.9}]}"}}],""" +
                    """"usage":{"prompt_tokens":10,"completion_tokens":20}}"""
            )
        }
    }

    /** 聊天生成器：只回一句固定文字（不调模型），好把注意力放在自动判定上。 */
    private fun chatOnlyServices(llm: LlmAccess): AgentdServices = AgentdServices(
        store = store,
        replyGenerator = ReplyGenerator { _, emit ->
            emit(com.yap.agentd.SseEvent.Delta("嗯。"))
            ReplyOutcome(Usage(1, 1))
        },
        llm = llm
    )

    private fun send(services: AgentdServices, index: Int) {
        services.chatService!!.send(
            sessionId,
            SendMessageRequest(clientMessageId = "m-$index", content = "第 $index 条"),
            object : com.yap.agentd.SseSink {
                override fun emit(event: com.yap.agentd.SseEvent) = Unit
            }
        )
    }

    // ------------------------------------------------------------------ 心情

    @Test
    fun `mood is evaluated automatically after four user messages`() {
        fakeLlm().use { server ->
            val services = chatOnlyServices(llmFor(server))
            repeat(3) { index -> send(services, index) }
            assertEquals("3 条还不该判定", 0, server.requests.size)
            assertNull(store.mood(personaId)?.takeIf { it.label == "有点累" })

            send(services, 4)
            assertEquals("第 4 条之后要问一次模型", 1, server.requests.size)
            val mood = store.mood(personaId)!!
            assertEquals("有点累", mood.label)
            assertEquals(-1, mood.valence)
            assertEquals(2, mood.dominance)
            assertEquals("聊得有点久", mood.reason)
            // 游标推进了：再聊一条不会重复判定。
            send(services, 5)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `mood auto evaluation is not tied to a client connection`() {
        fakeLlm().use { server ->
            val services = chatOnlyServices(llmFor(server))
            // 只有服务端自己知道「聊了 4 条」：没有任何客户端在线状态、没有 tick 调用。
            repeat(4) { index -> send(services, index) }
            val outcome = services.autoTriggers!!.onChatCompleted(personaId)
            assertTrue("第 5 条后不再判定，但游标要跟着涨", outcome.userMessagesSeen >= 5)
            assertEquals("有点累", store.mood(personaId)!!.label)
        }
    }

    @Test
    fun `mood is evaluated again after 24 hours even below the threshold`() {
        val clock = TestSupport.FakeClock(1_700_000_000_000L)
        store = FileStore.open(root, clock.reader)
        fakeLlm().use { server ->
            val llm = llmFor(server)
            val triggers = AutoTriggers(store, MoodService(store, llm), EvolutionService(store, llm), clock.asClock())
            // 先造一条「很久以前判过一次」的游标。
            store.recordUserMessageForAuto(personaId)
            store.recordMoodAutoEval(personaId, clock.now)
            clock.advance(25 * 60 * 60 * 1000L)

            store.recordUserMessageForAuto(personaId)
            val outcome = triggers.run(personaId)
            assertTrue("隔了 25 小时且有新消息 → 要判定", outcome.moodRan)
            assertEquals("有点累", store.mood(personaId)!!.label)
        }
    }

    // ------------------------------------------------------------------ 进化

    @Test
    fun `evolution is evaluated automatically after twenty user messages`() {
        fakeLlm().use { server ->
            val llm = llmFor(server)
            val triggers = AutoTriggers(store, MoodService(store, llm), EvolutionService(store, llm))
            repeat(19) { triggers.onChatCompleted(personaId) }
            assertTrue("19 条还不该评估进化", store.pendingPatches(personaId).isEmpty())

            val outcome = triggers.onChatCompleted(personaId)
            assertTrue(outcome.evolutionRan)
            assertNotNull("要写入待审补丁（不自动改人设）", outcome.pendingPatchId)
            val patch = store.pendingPatches(personaId).single()
            assertTrue(patch.patchJson, patch.patchJson.contains("嘴硬心软"))
            // 人设本身没被改。
            val persona = store.persona(personaId)!!
            assertFalse(
                "待审补丁不该直接改人格",
                persona.extraFields.values.any { it.toString().contains("嘴硬心软") }
            )
        }
    }

    @Test
    fun `mood and evolution use separate cursors`() {
        fakeLlm().use { server ->
            val llm = llmFor(server)
            val triggers = AutoTriggers(store, MoodService(store, llm), EvolutionService(store, llm))
            repeat(4) { triggers.onChatCompleted(personaId) }
            val afterMood = store.autoState(personaId)
            assertEquals(4, afterMood.moodCursor)
            assertEquals("进化的游标不该被心情判定推进", 0, afterMood.evolutionCursor)
        }
    }

    // ------------------------------------------------------------------ 边界

    @Test
    fun `a missing llm configuration does not advance the evolution cursor`() {
        // 没有 api_configs / secrets：configOrNull() = null。
        val llm = LlmAccess(LlmClient(), LlmConfigResolver(store, loopback = true))
        val triggers = AutoTriggers(store, MoodService(store, llm), EvolutionService(store, llm))
        repeat(20) { triggers.onChatCompleted(personaId) }
        val state = store.autoState(personaId)
        assertEquals("没配置时不该假装评估过", 0, state.evolutionCursor)
        assertTrue(store.pendingPatches(personaId).isEmpty())
        // 心情走时间衰减，游标仍然推进（否则每条消息都要重试）。
        assertTrue(state.moodCursor > 0)
    }

    @Test
    fun `auto triggers survive a failing llm without throwing`() {
        FakeLlmServer { exchange -> exchange.json(500, """{"error":{"message":"boom"}}""") }.use { server ->
            val llm = llmFor(server)
            val triggers = AutoTriggers(store, MoodService(store, llm), EvolutionService(store, llm))
            repeat(3) { triggers.onChatCompleted(personaId) }
            // 第 4 条会真的打一次模型（然后 500）；这里只要求它不抛、且游标推进。
            val outcome = triggers.onChatCompleted(personaId)
            assertTrue("失败也不能抛，游标照样推进", outcome.moodRan)
            assertTrue("失败后不该写入虚假心情（衰减路径除外）", store.mood(personaId) == null || store.mood(personaId)!!.label != "有点累")
        }
    }

    @Test
    fun `shouldRun is a pure threshold and interval rule`() {
        assertFalse(AutoTriggers.shouldRun(0, 0, 1_000, AutoTriggers.MOOD_MESSAGES))
        assertFalse(AutoTriggers.shouldRun(3, 0, 1_000, AutoTriggers.MOOD_MESSAGES))
        assertTrue(AutoTriggers.shouldRun(4, 0, 1_000, AutoTriggers.MOOD_MESSAGES))
        assertTrue(AutoTriggers.shouldRun(20, 0, 1_000, AutoTriggers.EVOLUTION_MESSAGES))
        assertFalse(
            "没有新消息时不应该因为时间到了就判定",
            AutoTriggers.shouldRun(0, 1_000, 1_000 + AutoTriggers.INTERVAL_MS, AutoTriggers.MOOD_MESSAGES)
        )
        assertTrue(
            "有新消息且隔满 24h → 判定",
            AutoTriggers.shouldRun(1, 1_000, 1_000 + AutoTriggers.INTERVAL_MS, AutoTriggers.MOOD_MESSAGES)
        )
    }

    @Test
    fun `counters are persisted so a restart does not re-evaluate the same messages`() {
        fakeLlm().use { server ->
            val llm = llmFor(server)
            val triggers = AutoTriggers(store, MoodService(store, llm), EvolutionService(store, llm))
            repeat(4) { triggers.onChatCompleted(personaId) }
            val requestsBefore = server.requests.size

            val reopened = FileStore.open(root)
            val reopenedTriggers = AutoTriggers(reopened, MoodService(reopened, llm), EvolutionService(reopened, llm))
            reopenedTriggers.onChatCompleted(personaId)
            assertEquals("重启后不该重复判定", requestsBefore, server.requests.size)
        }
    }

    @Test
    fun `chat service only runs the hooks when an llm is configured`() {
        val requested = AtomicInteger()
        val services = AgentdServices(
            store = store,
            replyGenerator = ReplyGenerator { _, _ ->
                requested.incrementAndGet()
                ReplyOutcome(Usage(1, 1))
            }
        )
        assertNull("没有 LLM 时不做自动判定", services.autoTriggers)
        repeat(5) { index -> send(services, index) }
        assertEquals(5, requested.get())
        assertEquals(0, store.autoState(personaId).moodCursor)
    }
}
