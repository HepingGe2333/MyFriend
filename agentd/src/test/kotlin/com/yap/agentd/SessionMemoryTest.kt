package com.yap.agentd

import com.yap.agentd.experience.ExperienceService
import com.yap.agentd.experience.SessionMemoryService
import com.yap.agentd.llm.ChatPromptBuilder
import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfigResolver
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.SessionMemory
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
 * 0.14 会话记忆（`spec/experience/README.md` §6）：节流、失败保留旧摘要、注入顺序。
 *
 * 节流按「该会话的非拉黑用户消息条数」：每 5 条更新短期、每 30 条更新长期。
 * 测试不 sleep：条数与时间都由测试自己造。
 */
class SessionMemoryTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-session-memory")
        clock.now = 1_000L
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

    private fun say(index: Int, blocked: Boolean = false) {
        clock.advance(1_000)
        store.appendMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = "第 $index 条：我下周要去哈尔滨，有点怕冷",
            clientMessageId = "c-$index",
            blocked = blocked
        )
    }

    private fun service(llm: LlmAccess): SessionMemoryService = SessionMemoryService(store, llm, clock.asClock())

    // ------------------------------------------------------------------ 节流

    @Test
    fun `short summary updates every five user messages only`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            calls.incrementAndGet()
            exchange.json(200, FakeLlmServer.completion("你提过下周要去哈尔滨，怕冷。"))
        }.use { server ->
            val memory = service(llmFor(server))
            repeat(4) { index -> say(index) }
            val before = memory.maybeUpdate(sessionId, clock.now)
            assertFalse("4 条还不更新", before.shortUpdated)
            assertNull(store.sessionMemory(sessionId))

            say(5)
            val after = memory.maybeUpdate(sessionId, clock.now)
            assertTrue("第 5 条要更新", after.shortUpdated)
            val saved = store.sessionMemory(sessionId)!!
            assertEquals("你提过下周要去哈尔滨，怕冷。", saved.shortSummary)
            assertEquals(5L, saved.shortUserMessages)
            assertEquals(1, calls.get())

            say(6)
            memory.maybeUpdate(sessionId, clock.now)
            assertEquals("第 6 条不该再更新", 1, calls.get())
        }
    }

    @Test
    fun `long summary updates every thirty user messages`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("你们认识三年，他一直在做建筑。"))
        }.use { server ->
            val memory = service(llmFor(server))
            repeat(29) { index -> say(index) }
            val at29 = memory.maybeUpdate(sessionId, clock.now)
            assertFalse("29 条还没有长期摘要", at29.longUpdated)
            assertNull(store.sessionMemory(sessionId)!!.let { it.longSummary.takeIf(String::isNotBlank) })

            say(30)
            val at30 = memory.maybeUpdate(sessionId, clock.now)
            assertTrue("第 30 条要更新长期摘要", at30.longUpdated)
            val saved = store.sessionMemory(sessionId)!!
            assertEquals("你们认识三年，他一直在做建筑。", saved.longSummary)
            assertEquals(30L, saved.longUserMessages)
            assertTrue("短期摘要也顺手更新了", saved.shortSummary.isNotBlank())
        }
    }

    @Test
    fun `blocked messages never count toward the summary throttle`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("不该被写进摘要。"))
        }.use { server ->
            val memory = service(llmFor(server))
            repeat(5) { index -> say(index, blocked = true) }
            val result = memory.maybeUpdate(sessionId, clock.now)
            assertFalse("被拉黑的消息不进摘要", result.shortUpdated)
            assertNull(store.sessionMemory(sessionId))
            assertEquals("模型能看见的 0 条", 0L, result.userMessages)

            repeat(5) { index -> say(100 + index) }
            val after = memory.maybeUpdate(sessionId, clock.now)
            assertTrue("只有非拉黑的 5 条才触发", after.shortUpdated)
        }
    }

    // ------------------------------------------------------------------ 失败保留旧摘要

    @Test
    fun `a failing model keeps the old summary and does not advance the cursor`() {
        var fail = false
        FakeLlmServer { exchange ->
            if (fail) {
                exchange.json(500, """{"error":{"message":"boom"}}""")
            } else {
                exchange.json(200, FakeLlmServer.completion("旧的短期摘要"))
            }
        }.use { server ->
            val memory = service(llmFor(server))
            repeat(5) { index -> say(index) }
            memory.maybeUpdate(sessionId, clock.now)
            val old = store.sessionMemory(sessionId)!!
            assertEquals("旧的短期摘要", old.shortSummary)

            fail = true
            repeat(5) { index -> say(100 + index) }
            val result = memory.maybeUpdate(sessionId, clock.now)
            assertFalse("失败不算更新", result.shortUpdated)
            assertEquals("失败保留旧摘要", "旧的短期摘要", store.sessionMemory(sessionId)!!.shortSummary)
            assertEquals("游标不推进，下一条还会再试", 5L, store.sessionMemory(sessionId)!!.shortUserMessages)
        }
    }

    @Test
    fun `a blank model answer is treated as a failure`() {
        FakeLlmServer { exchange -> exchange.json(200, FakeLlmServer.completion("   ")) }.use { server ->
            val memory = service(llmFor(server))
            repeat(5) { index -> say(index) }
            val result = memory.maybeUpdate(sessionId, clock.now)
            assertFalse(result.shortUpdated)
            assertNull(store.sessionMemory(sessionId))
        }
    }

    @Test
    fun `an over long summary is truncated instead of rejected`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("长".repeat(500)))
        }.use { server ->
            val memory = service(llmFor(server))
            repeat(5) { index -> say(index) }
            assertTrue(memory.maybeUpdate(sessionId, clock.now).shortUpdated)
            assertEquals(
                SessionMemory.MAX_SHORT_CHARS,
                store.sessionMemory(sessionId)!!.shortSummary.length
            )
        }
    }

    // ------------------------------------------------------------------ 注入

    @Test
    fun `short summary is injected before the long one in the memo section`() {
        store.saveSessionMemory(
            SessionMemory(
                sessionId = sessionId,
                personaId = personaId,
                shortSummary = "最近：你提过下周去哈尔滨。",
                longSummary = "更早：你们认识三年了。"
            )
        )
        val builder = ChatPromptBuilder()
        val prompt = builder.systemPrompt(
            persona = store.persona(personaId)!!,
            mood = null,
            activity = null,
            now = clock.now,
            sessionMemory = store.sessionMemory(sessionId)
        )
        assertTrue(prompt.contains("# 你的备忘录"))
        assertTrue(prompt.contains("最近你记得：最近：你提过下周去哈尔滨。"))
        assertTrue(prompt.contains("更早之前：更早：你们认识三年了。"))
        assertTrue(
            "短期在前、长期在后",
            prompt.indexOf("下周去哈尔滨") < prompt.indexOf("认识三年")
        )
        assertTrue("备忘录不是人设的一部分（在边界之前）", prompt.indexOf("# 你的备忘录") < prompt.indexOf("# 边界"))
    }

    @Test
    fun `experience updates the memo on chat completion`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("你提过去哈尔滨，怕冷。"))
        }.use { server ->
            val llm = llmFor(server)
            val experience = ExperienceService(
                store,
                mental = null,
                memory = SessionMemoryService(store, llm, clock.asClock()),
                clock = clock.asClock()
            )
            repeat(5) { index -> say(index) }
            val outcome = experience.onChatCompleted(personaId, sessionId)
            assertTrue(outcome.memoryShortUpdated)
            assertEquals("你提过去哈尔滨，怕冷。", store.sessionMemory(sessionId)!!.shortSummary)
        }
    }

    @Test
    fun `memory survives a store reopen`() {
        store.saveSessionMemory(
            SessionMemory(
                sessionId = sessionId,
                personaId = personaId,
                shortSummary = "短期",
                shortUserMessages = 5,
                longSummary = "长期",
                longUserMessages = 30
            )
        )
        store.saveMentalActivity(
            com.yap.agentd.store.MentalActivity(
                id = "persist-1",
                personaId = personaId,
                sessionId = sessionId,
                at = clock.now,
                thought = "记住的念头",
                moodLabel = "平静",
                moodValence = 0,
                moodArousal = 2,
                moodDominance = 3,
                moodIntensity = 2
            )
        )
        val reopened = FileStore.open(root, clock.reader)
        val memory = reopened.sessionMemory(sessionId)
        assertNotNull(memory)
        assertEquals("短期", memory!!.shortSummary)
        assertEquals("长期", memory.longSummary)
        assertEquals("记住的念头", reopened.mentalActivities(personaId).single().thought)
    }
}
