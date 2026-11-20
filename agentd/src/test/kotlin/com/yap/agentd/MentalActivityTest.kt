package com.yap.agentd

import com.yap.agentd.experience.ExperienceService
import com.yap.agentd.experience.MentalActivityRules
import com.yap.agentd.experience.MentalActivityService
import com.yap.agentd.experience.SessionMemoryService
import com.yap.agentd.llm.ChatPromptBuilder
import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfigResolver
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.mood.MoodRules
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MentalActivity
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Mood
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
 * 0.14 心理活动（`spec/experience/README.md` §3）：触发、限幅、注入、保留上限、后台拍。
 *
 * 规则层（纯函数）与 service 层分开钉：触发条件的边界最容易写错，而 LLM 调用要有假服务端。
 */
class MentalActivityTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-mental")
        // 从一个「真实量级」的时间开始：活跃窗口要往前减 10 分钟，1_000L 会减成负数。
        clock.now = 1_000_000_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun llmFor(server: FakeLlmServer): LlmAccess {
        store.pushLlmConfig(server.baseUrl)
        return LlmAccess(LlmClient(), LlmConfigResolver(store, loopback = true))
    }

    private fun say(index: Int) {
        store.appendMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = "第 $index 条",
            clientMessageId = "c-$index"
        )
    }

    private fun twoReplyTurns() {
        store.recordAssistantReply(personaId)
        store.recordAssistantReply(personaId)
    }

    // ------------------------------------------------------------------ 规则层

    @Test
    fun `active window is ten minutes`() {
        assertTrue(MentalActivityRules.isActiveWindow(clock.now, clock.now))
        assertTrue(MentalActivityRules.isActiveWindow(clock.now - 10 * 60_000L, clock.now))
        assertFalse(MentalActivityRules.isActiveWindow(clock.now - 10 * 60_000L - 1, clock.now))
        assertFalse("没有消息就不活跃", MentalActivityRules.isActiveWindow(null, clock.now))
    }

    @Test
    fun `trigger needs an active window and either three turns or three minutes`() {
        val now = clock.now
        val active = now - 60_000L
        val inactive = now - 11 * 60_000L

        assertFalse(
            "窗口外不做",
            MentalActivityRules.shouldRun(now, inactive, 0, 10, 0, mandatory = false)
        )
        assertFalse(
            "两轮还不够",
            MentalActivityRules.shouldRun(now, active, now, 2, 0, mandatory = false)
        )
        assertTrue(
            "满 3 轮要做",
            MentalActivityRules.shouldRun(now, active, now, 3, 0, mandatory = false)
        )
        assertTrue(
            "距上次满 3 分钟要做",
            MentalActivityRules.shouldRun(now, active, now - 3 * 60_000L, 0, 0, mandatory = false)
        )
        assertFalse(
            "既不够轮次也不够时间就不做",
            MentalActivityRules.shouldRun(now, active, now - 60_000L, 1, 0, mandatory = false)
        )
        assertTrue(
            "激动/变化大必做（即使刚到窗口）",
            MentalActivityRules.shouldRun(now, active, now - 1_000L, 0, 0, mandatory = true)
        )
    }

    @Test
    fun `arousal and a two step mood change are mandatory but a small change is not`() {
        val calm = Mood(personaId = personaId, label = "平静", intensity = 2, updatedAt = 1_000, arousal = 2)
        assertFalse(MentalActivityRules.isMandatory(calm, null))

        val excited = calm.copy(label = "雀跃", arousal = 4)
        assertTrue("唤醒度 ≥4 必做", MentalActivityRules.isMandatory(excited, null))

        val previous = MentalActivity(
            id = "m-1",
            personaId = personaId,
            sessionId = sessionId,
            at = 500L,
            thought = "刚才心里有点沉",
            moodLabel = "低落",
            moodValence = -2,
            moodArousal = 2,
            moodDominance = 3,
            moodIntensity = 2
        )
        val changed = calm.copy(valence = 1)
        assertEquals(3, MentalActivityRules.moodDelta(previous, changed))
        assertTrue("变化 ≥2 必做", MentalActivityRules.isMandatory(changed, previous))

        val small = calm.copy(valence = -1)
        assertEquals(1, MentalActivityRules.moodDelta(previous, small))
        assertFalse("变化只有 1 不算必做", MentalActivityRules.isMandatory(small, previous))
    }

    @Test
    fun `mood adjustment moves at most one step per dimension`() {
        val previous = Mood(
            personaId = personaId,
            label = "平静",
            intensity = 3,
            updatedAt = 1_000,
            valence = 0,
            arousal = 2,
            dominance = 3
        )
        val proposed = MoodRules.Values(
            label = "雀跃",
            valence = 2,
            arousal = 5,
            dominance = 0,
            intensity = 5
        )
        val adjusted = MentalActivityRules.adjust(previous, proposed)
        assertEquals(1, adjusted.valence)
        assertEquals(3, adjusted.arousal)
        assertEquals(2, adjusted.dominance)
        assertEquals(4, adjusted.intensity)
        assertEquals("雀跃", adjusted.label)

        // 没有建议 = 保持原样。
        val unchanged = MentalActivityRules.adjust(previous, null)
        assertEquals(MoodRules.Values.from(previous), unchanged)
    }

    // ------------------------------------------------------------------ service

    @Test
    fun `three replies in the active window generate one thought and adjust mood by one step`() {
        FakeLlmServer { exchange ->
            exchange.json(
                200,
                FakeLlmServer.completion(
                    """{"thought":"他刚刚那句话，让我心里软了一下","label":"有点软","valence":1,"arousal":3,"dominance":3,"intensity":3}"""
                )
            )
        }.use { server ->
            val llm = llmFor(server)
            store.setMood(personaId, "平静", intensity = 3, valence = 0, arousal = 2, dominance = 3)
            say(1)
            twoReplyTurns()
            say(2)
            clock.advance(30_000L)
            store.recordAssistantReply(personaId) // 第 3 轮

            val service = MentalActivityService(store, llm, clock.asClock())
            val activity = service.maybeRun(personaId, sessionId, clock.now, force = true)

            assertNotNull("满 3 轮要产生心理活动", activity)
            assertEquals("他刚刚那句话，让我心里软了一下", activity!!.thought)
            val mood = store.mood(personaId)!!
            assertEquals("愉快度只能动 1 格", 1, mood.valence)
            assertEquals("唤醒度只能动 1 格", 3, mood.arousal)
            assertEquals(1, store.mentalActivities(personaId).size)
            assertEquals("游标推进，下一轮重新数", 3L, store.autoState(personaId).mentalCursor)
        }
    }

    @Test
    fun `a failing model writes nothing and does not advance the cursor`() {
        FakeLlmServer { exchange -> exchange.json(500, """{"error":{"message":"boom"}}""") }.use { server ->
            val llm = llmFor(server)
            say(1)
            twoReplyTurns()
            store.recordAssistantReply(personaId)
            clock.advance(30_000L)
            val service = MentalActivityService(store, llm, clock.asClock())
            assertNull(service.maybeRun(personaId, sessionId, clock.now, force = true))
            assertEquals(0, store.mentalActivities(personaId).size)
            assertEquals(0L, store.autoState(personaId).mentalCursor)
        }
    }

    @Test
    fun `a thought longer than sixty chars is rejected instead of truncated`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("""{"thought":"${"想".repeat(61)}"}"""))
        }.use { server ->
            val llm = llmFor(server)
            say(1)
            twoReplyTurns()
            store.recordAssistantReply(personaId)
            clock.advance(30_000L)
            val service = MentalActivityService(store, llm, clock.asClock())
            assertNull(service.maybeRun(personaId, sessionId, clock.now, force = true))
            assertEquals(0, store.mentalActivities(personaId).size)
        }
    }

    @Test
    fun `only the newest fifty thoughts are kept per persona`() {
        repeat(60) { index ->
            clock.advance(1_000)
            store.saveMentalActivity(
                MentalActivity(
                    id = "mental-$index",
                    personaId = personaId,
                    sessionId = sessionId,
                    at = clock.now,
                    thought = "第 $index 个念头",
                    moodLabel = "平静",
                    moodValence = 0,
                    moodArousal = 2,
                    moodDominance = 3,
                    moodIntensity = 2
                )
            )
        }
        val kept = store.mentalActivities(personaId, limit = 100)
        assertEquals(MentalActivity.KEEP_RECENT, kept.size)
        assertEquals("留的是最新的", "第 59 个念头", kept.first().thought)
        assertEquals("最老的被裁掉", "第 10 个念头", kept.last().thought)
    }

    @Test
    fun `the thought is injected into the next prompt before the memo`() {
        clock.advance(1_000)
        val activity = store.saveMentalActivity(
            MentalActivity(
                id = "mental-inject",
                personaId = personaId,
                sessionId = sessionId,
                at = clock.now,
                thought = "刚才那句话有点戳到我",
                moodLabel = "有点软",
                moodValence = 1,
                moodArousal = 3,
                moodDominance = 3,
                moodIntensity = 3
            )
        )
        store.saveSessionMemory(
            com.yap.agentd.store.SessionMemory(
                sessionId = sessionId,
                personaId = personaId,
                shortSummary = "你提过下周要去哈尔滨。",
                longSummary = "你们认识三年了。"
            )
        )
        val builder = ChatPromptBuilder(java.time.ZoneId.of("Asia/Shanghai"))
        val prompt = builder.systemPrompt(
            persona = store.persona(personaId)!!,
            mood = store.mood(personaId),
            activity = null,
            now = clock.now,
            sessionMemory = store.sessionMemory(sessionId),
            mentalActivity = activity
        )
        assertTrue("0.15 起标题与共享契约一致（没有「的」）", prompt.contains("# 你刚才心里想"))
        assertTrue(prompt.contains("你刚才心里想：刚才那句话有点戳到我"))
        assertTrue(prompt.contains("# 你的备忘录"))
        assertTrue(prompt.contains("你提过下周要去哈尔滨。"))
        assertTrue(prompt.contains("你们认识三年了。"))
        assertTrue(
            "长期在短期之前（v2 固定顺序：长期 → 短期 → 此刻 → 主题）",
            prompt.indexOf("你们认识三年了。") < prompt.indexOf("你提过下周要去哈尔滨。")
        )
        assertTrue("心理活动在备忘录之后（备忘录 = 他记住了什么，念头 = 他刚才在想什么）",
            prompt.indexOf(MEMO_TITLE) < prompt.indexOf(MENTAL_TITLE))
        assertTrue("心理活动在历史之前、人设之后", prompt.indexOf("# 你怎么说话") < prompt.indexOf(MENTAL_TITLE))
    }

    @Test
    fun `the heartbeat tick runs mental activities without any client connection`() {
        val requests = AtomicInteger()
        FakeLlmServer { exchange ->
            requests.incrementAndGet()
            exchange.json(
                200,
                FakeLlmServer.completion("""{"thought":"安静下来，忽然想起他刚才那句","valence":1}""")
            )
        }.use { server ->
            val llm = llmFor(server)
            say(1)
            repeat(3) { store.recordAssistantReply(personaId) }
            clock.advance(30_000L)
            val services = AgentdServices(store = store, llm = llm, proactiveRoll = { 1.0 })
            val outcome = services.proactiveTicker!!.tickWithDecisions(clock.now)

            assertTrue("心跳跑了", outcome.now == clock.now)
            assertEquals("心理活动由心跳自己触发（不依赖客户端）", 1, store.mentalActivities(personaId).size)
            assertTrue("确实打过模型", requests.get() >= 1)
        }
    }

    @Test
    fun `experience on chat completion only forces the activity when it is mandatory`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("""{"thought":"就是有点困","valence":-1}"""))
        }.use { server ->
            val llm = llmFor(server)
            val mental = MentalActivityService(store, llm, clock.asClock())
            val experience = ExperienceService(
                store,
                mental = mental,
                memory = null,
                clock = clock.asClock()
            )
            store.setMood(personaId, "平静", intensity = 2, valence = 0, arousal = 1, dominance = 3)
            say(1)
            store.recordAssistantReply(personaId)
            clock.advance(30_000L)
            // 不激动、轮次不够、间隔不够：force 也不该硬做。
            val calm = experience.onChatCompleted(personaId, sessionId)
            assertNull(calm.mentalActivity)

            clock.advance(1_000)
            store.setMood(personaId, "雀跃", intensity = 4, valence = 2, arousal = 5, dominance = 3, reason = "突然很开心")
            say(2)
            store.recordAssistantReply(personaId)
            val excited = experience.onChatCompleted(personaId, sessionId)
            assertNotNull("唤醒度 ≥4 必做", excited.mentalActivity)
            assertEquals(1, store.mentalActivities(personaId).size)
        }
    }

    companion object {
        private const val MENTAL_TITLE = "# 你刚才心里想"
        private const val MEMO_TITLE = "# 你的备忘录"
    }
}
