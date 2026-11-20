package com.yap.agentd.tools

import com.yap.agentd.TestSupport
import com.yap.agentd.llm.ChatPromptBuilder
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.SessionMemory
import com.yap.app.domain.MemoryContract
import com.yap.app.domain.PromptContract
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
 * 0.15 的提示词/工具护栏（评测里暴露的三类真实问题）：
 *
 * 1. 模型会说「我记住了」却不调 `remember` → 能力段必须点名「必须调用 `remember`」；
 * 2. 模型想不起旧事会编 → 能力段必须指引 `memorize` / `recall`；
 * 3. 模型爱写旁白/动作（「沉默了一会儿」）→ 共享「# 别像 AI」之外再加三条服务端规则。
 *
 * 同时验证 `memorize` 工具真的能检索并输出 `<回忆：…>`（设计文档的注入形状）。
 */
class ToolPromptGuardrailsTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-18T11:40:00Z").toEpochMilli() // 19:40 本地

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-tool-guardrails")
        store = FileStore.open(root, { now })
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    @Test
    fun `the capability prompt insists on remember and points at memorize`() {
        val capabilities = ServerToolCatalog.capabilityPrompt()
        assertTrue(capabilities.contains("必须调用 `remember`"))
        assertTrue(capabilities.contains("`memorize`"))
        assertTrue(capabilities.contains("`recall`"))
    }

    @Test
    fun `the system prompt forbids narration and XML tool tags in the body`() {
        val prompt = ChatPromptBuilder(zone).systemPrompt(
            persona = store.persona(personaId)!!,
            mood = null,
            activity = null,
            now = now
        )
        assertTrue(prompt.contains(PromptContract.AntiAi.TITLE))
        assertTrue("旁白要禁掉", prompt.contains("不要写旁白"))
        assertTrue(prompt.contains("沉默了一会儿"))
        assertTrue("不要在正文里写 XML 工具标签", prompt.contains("<tool_call>"))
        assertTrue("只写说出口的话", prompt.contains("只写说出口的话"))
    }

    @Test
    fun `memorize returns the designed memory shape`() {
        store.appendMessage(sessionId, MessageRole.USER, "我下周要去哈尔滨，有点怕冷")
        store.appendMessage(sessionId, MessageRole.ASSISTANT, "多带件厚的。")
        store.saveSessionMemory(
            SessionMemory(
                sessionId = sessionId,
                personaId = personaId,
                topics = listOf(
                    MemoryContract.TopicSegment("2026-11-18 晚", "哈尔滨的行程与怕冷")
                )
            )
        )
        val executor = ToolExecutor(store, clock = Clock.fixed(Instant.ofEpochMilli(now), zone))
        val outcome = executor.execute(
            "memorize",
            """{"query":"哈尔滨"}""",
            ToolExecutor.Context(personaId = personaId, sessionId = sessionId)
        )

        assertTrue(outcome.ok)
        assertEquals("想了想", outcome.trace.title)
        assertTrue(outcome.contentForModel, outcome.contentForModel.contains("<回忆："))
        assertTrue(outcome.contentForModel.contains("哈尔滨"))
        assertTrue("别忘了提醒模型别编", outcome.contentForModel.contains("别编"))
    }

    @Test
    fun `memorize is honest when nothing is found`() {
        store.appendMessage(sessionId, MessageRole.USER, "今天天气不错")
        val executor = ToolExecutor(store, clock = Clock.fixed(Instant.ofEpochMilli(now), zone))
        val outcome = executor.execute(
            "memorize",
            """{"query":"量子力学"}""",
            ToolExecutor.Context(personaId = personaId, sessionId = sessionId)
        )
        assertTrue(outcome.ok)
        assertFalse(outcome.contentForModel.contains("<回忆："))
        assertTrue(outcome.contentForModel.contains("没找到"))
        assertTrue("要明确说别编", outcome.contentForModel.contains("别编"))
    }

    @Test
    fun `memorize requires a query`() {
        val executor = ToolExecutor(store, clock = Clock.fixed(Instant.ofEpochMilli(now), zone))
        val outcome = executor.execute(
            "memorize",
            "{}",
            ToolExecutor.Context(personaId = personaId, sessionId = sessionId)
        )
        assertFalse(outcome.ok)
    }
}
