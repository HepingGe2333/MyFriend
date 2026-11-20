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
    fun `a plan change updates the short summary before the five round mark`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("你们约好周四一起去看展。"))
        }.use { server ->
            val memory = service(llmFor(server))
            say(1)
            val normal = memory.maybeUpdate(sessionId, clock.now)
            assertFalse("1 条还不够 5 条的节流", normal.shortUpdated)
            assertNull(store.sessionMemory(sessionId))

            // 模型说「计划变了」（例如这轮调了 remember / defer_reply）：立刻更新短期缓存。
            val planned = memory.maybeUpdate(sessionId, clock.now, planChanged = true)
            assertTrue("计划变化要立刻更新短期摘要", planned.shortUpdated)
            assertEquals("你们约好周四一起去看展。", store.sessionMemory(sessionId)!!.shortSummary)
        }
    }

    @Test
    fun `long summary is decided by the model between fifteen and forty rounds`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("你们认识三年，他一直在做建筑。"))
        }.use { server ->
            val memory = service(llmFor(server))
            repeat(14) { index -> say(index) }
            val at14 = memory.maybeUpdate(sessionId, clock.now)
            assertFalse("14 条还没到长期摘要的检查点（15 条起）", at14.longUpdated)
            assertNull(store.sessionMemory(sessionId)?.longSummary?.takeIf(String::isNotBlank))

            say(15)
            val at15 = memory.maybeUpdate(sessionId, clock.now)
            assertTrue("第 15 条起由模型决定：它说更新就更新", at15.longUpdated)
            val saved = store.sessionMemory(sessionId)!!
            assertEquals("你们认识三年，他一直在做建筑。", saved.longSummary)
            assertEquals(15L, saved.longUserMessages)
            assertEquals("检查游标也推到 15", 15L, saved.longCheckedUserMessages)
        }
    }

    @Test
    fun `a model that says SKIP only advances the check cursor and is forced at forty`() {
        val longChecks = java.util.concurrent.atomic.AtomicInteger()
        FakeLlmServer { exchange ->
            val body = exchange.request.body
            if (body.contains("更早之前的对话")) {
                // 长期检查：前两次都说「先不更新」，40 条那次必须给出内容。
                if (longChecks.incrementAndGet() <= 2) {
                    exchange.json(200, FakeLlmServer.completion("SKIP"))
                } else {
                    exchange.json(200, FakeLlmServer.completion("你们认识三年了。"))
                }
            } else {
                exchange.json(200, FakeLlmServer.completion("最近在聊哈尔滨。"))
            }
        }.use { server ->
            val memory = service(llmFor(server))
            repeat(15) { index -> say(index) }
            val skipped = memory.maybeUpdate(sessionId, clock.now)
            assertTrue("模型说先不更新", skipped.longSkipped)
            assertFalse(skipped.longUpdated)
            val afterSkip = store.sessionMemory(sessionId)!!
            assertEquals("长期摘要还没有", "", afterSkip.longSummary)
            assertEquals("检查游标推到 15（下一轮检查在 30 条）", 15L, afterSkip.longCheckedUserMessages)

            // 15 → 29：还没到下一次检查。
            repeat(14) { index -> say(100 + index) }
            val at29 = memory.maybeUpdate(sessionId, clock.now)
            assertFalse("29 条还没到下一次检查", at29.longUpdated || at29.longSkipped)
            assertEquals(0, store.sessionMemory(sessionId)!!.longUserMessages)

            // 30：第二次检查（模型仍说 SKIP）。
            say(200)
            val second = memory.maybeUpdate(sessionId, clock.now)
            assertTrue(second.longSkipped)

            // 40：强制更新（不再允许 SKIP）。
            repeat(10) { index -> say(300 + index) }
            val forced = memory.maybeUpdate(sessionId, clock.now)
            assertTrue("40 条必须更新（不能再 SKIP）", forced.longUpdated)
            assertEquals("你们认识三年了。", store.sessionMemory(sessionId)!!.longSummary)
            assertEquals(40L, store.sessionMemory(sessionId)!!.longUserMessages)
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
    fun `the memo uses the shared v2 titles with long term before short term`() {
        store.saveSessionMemory(
            SessionMemory(
                sessionId = sessionId,
                personaId = personaId,
                shortSummary = "最近：你提过下周去哈尔滨。",
                longSummary = "更早：你们认识三年了。",
                topics = listOf(
                    com.yap.app.domain.MemoryContract.TopicSegment(
                        timeLabel = "2026-11-18 晚",
                        summary = "加班与方案改版"
                    )
                )
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
        // 段标题统一走共享契约（`:agentcore` MemoryContract.Injection）。
        assertTrue(prompt.contains("# 你的备忘录"))
        assertTrue(prompt.contains("## 长期记忆"))
        assertTrue(prompt.contains("## 短期记忆"))
        assertTrue(prompt.contains("## 对话主题"))
        assertTrue(prompt.contains("- 更早：你们认识三年了。"))
        assertTrue(prompt.contains("- 最近：你提过下周去哈尔滨。"))
        assertTrue(prompt.contains("- 2026-11-18 晚：加班与方案改版"))
        assertTrue(
            "v2 固定顺序：长期在上、短期在下、主题最后",
            prompt.indexOf("## 长期记忆") < prompt.indexOf("## 短期记忆") &&
                prompt.indexOf("## 短期记忆") < prompt.indexOf("## 对话主题")
        )
        assertTrue(
            "备忘录属于「他记住了什么」，排在完整设定/工具/反 AI 之后、边界（最后一段）之前",
            prompt.indexOf("# 边界") > prompt.indexOf("# 你的备忘录") &&
                prompt.indexOf("# 别像 AI") < prompt.indexOf("# 你的备忘录")
        )
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
