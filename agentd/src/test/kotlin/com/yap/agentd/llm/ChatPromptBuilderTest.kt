package com.yap.agentd.llm

import com.yap.agentd.TestSupport
import com.yap.agentd.proactive.ActivityAvailability
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Mood
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * 服务端提示词拼装（`spec/persona-guidance` §1 + `spec/mood` §3）。
 *
 * 这些断言直接钉「提示词里到底写了什么、顺序对不对」：规范要求段顺序不可乱、
 * 固定中文来自共享脚手架（`:agentcore` `PersonaPromptScaffold`），心情必须带三个维度
 * 且不能出现「我的情绪是…」式自述。
 */
class ChatPromptBuilderTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-15T11:40:00Z").toEpochMilli() // 19:40 本地

    private val builder = ChatPromptBuilder(zone)

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-prompt-test")
        store = FileStore.open(root, { now })
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun persona() = store.persona(FileStore.SEED_PERSONA_1_ID)!!

    private fun seedRichPersona() {
        store.setPersonaExtras(
            extras = mapOf(
                "worldJson" to JsonValue.Str(
                    """{"worldName":"临港新城·云筑建筑事务所","era":"当代，中国沿海新一线城市，2026 年",""" +
                        """"description":"在事务所画图、审图。","notes":"周三晚上要给母亲打电话。"}"""
                ),
                "speechStyleJson" to JsonValue.Str(
                    """{"tone":"简短直接，偏冷淡务实","phrases":["在忙，晚点说","别想太多"],""" +
                        """"habits":["句子很短"],"avoid":["长篇大论"]}"""
                ),
                "traits" to listText("慢热", "嘴硬心软"),
                "likes" to listText("夜里散步"),
                "behaviors" to listText("先听完再给建议"),
                "quirks" to JsonValue.Str("写字前会先转笔。"),
                "personaCardJson" to JsonValue.Str(
                    """{"identity":{"age":"二十六","role":"建筑师","relation":"恋人"},""" +
                        """"voice":{"address":"你"},"life":{"schedule":"十一点半睡"},"bond":"你们在一起三年了。"}"""
                )
            )
        )
    }

    @Test
    fun `sections follow the normative order`() {
        seedRichPersona()
        val prompt = builder.systemPrompt(persona(), mood(), activity = null, now = now)

        // 0.15 上下文/记忆 v2 的固定顺序：完整设定 + 工具 → （记忆为空则省略）→
        // 当前状态（此刻 + 心情）；「别像 AI」挂在边界之后。
        val titles = listOf(
            "# 你是谁",
            "# 你所在的世界",
            "# 你怎么说话",
            "# 你们的关系",
            "# 你能做什么",
            "# 边界",
            "# 别像 AI",
            "# 你此刻的状态",
            "# 你的心情"
        )
        val positions = titles.map { prompt.indexOf(it) }
        assertTrue("这些段都必须出现：$positions", positions.all { it >= 0 })
        assertEquals("段顺序不可乱", positions.sorted(), positions)
    }

    @Test
    fun `identity world voice relationship and profile data all make it into the prompt`() {
        seedRichPersona()
        val prompt = builder.systemPrompt(persona(), mood(), activity = null, now = now)

        assertTrue(prompt.contains("林深"))
        assertTrue("人设卡 meta 进身份段", prompt.contains("二十六，建筑师"))
        assertTrue("世界名", prompt.contains("临港新城"))
        assertTrue("世界描述", prompt.contains("在事务所画图、审图"))
        assertTrue("其他背景", prompt.contains("周三晚上要给母亲打电话"))
        assertTrue("话风 tone", prompt.contains("简短直接，偏冷淡务实"))
        assertTrue("常用语", prompt.contains("在忙，晚点说"))
        assertTrue("话风习惯数据行", prompt.contains("- 习惯：句子很短"))
        assertTrue("关系段", prompt.contains("你是对方的恋人"))
        assertTrue("profile 性格", prompt.contains("- 性格：慢热、嘴硬心软"))
        assertTrue("profile 设定", prompt.contains("写字前会先转笔"))
        assertTrue("时间（日期 + 星期 + 时区）", prompt.contains("2026-11-15 周日 19:40（UTC+08:00）"))
    }

    @Test
    fun `no world and no mood still produce a complete prompt without a mood section`() {
        val prompt = builder.systemPrompt(persona(), mood = null, activity = null, now = now)
        assertTrue(prompt.contains("你没有特别的世界设定"))
        assertFalse("没有心情就不写心情段", prompt.contains("# 你的心情"))
        assertTrue(prompt.contains("# 边界"))
    }

    @Test
    fun `busy activity makes the prompt say he is not available`() {
        val activity = ActivityState(
            kind = "WORK",
            activity = "在事务所赶第三版方案",
            until = now + 90 * 60_000L,
            availability = ActivityAvailability.BUSY
        )
        val prompt = builder.systemPrompt(persona(), mood(), activity, now)

        assertTrue(prompt.contains("你不太有空"))
        assertTrue(prompt.contains("21:10 结束"))
        assertFalse("不能在忙的时候说「没在忙什么」", prompt.contains("你没在忙什么。"))
    }

    @Test
    fun `multi dimensional mood is injected and never self reported`() {
        val prompt = builder.systemPrompt(persona(), mood(), activity = null, now = now)

        assertTrue(prompt.contains("心情：有点累（强度 3/5）"))
        assertTrue("三个维度都要有", prompt.contains("愉快度 -1"))
        assertTrue(prompt.contains("唤醒度 2/5"))
        assertTrue(prompt.contains("掌控感 3/5"))
        assertTrue(prompt.contains("不要直接报数值"))
        assertEquals("「我的情绪是」只允许出现在禁止自述的那一句里", 1, Regex("我的情绪是").findAll(prompt).count())
        assertFalse("心情原因不直接进提示词", prompt.contains("连着加班"))
    }

    @Test
    fun `chat messages map history roles and keep the system prompt first`() {
        seedRichPersona()
        val messages = mutableListOf(
            com.yap.agentd.store.Message(
                id = "m1", sessionId = FileStore.SEED_SESSION_ID, blockIndex = 0,
                role = MessageRole.USER, content = "在干嘛", createdAt = 1, readAt = null,
                attachments = emptyList(), source = "CHAT", clientMessageId = null,
                replyTo = null, promptTokens = null, completionTokens = null
            ),
            com.yap.agentd.store.Message(
                id = "m2", sessionId = FileStore.SEED_SESSION_ID, blockIndex = 0,
                role = MessageRole.ASSISTANT, content = "在改图。", createdAt = 2, readAt = null,
                attachments = emptyList(), source = "CHAT", clientMessageId = null,
                replyTo = null, promptTokens = null, completionTokens = null
            ),
            com.yap.agentd.store.Message(
                id = "m3", sessionId = FileStore.SEED_SESSION_ID, blockIndex = 0,
                role = MessageRole.USER, content = "几点完", createdAt = 3, readAt = null,
                attachments = listOf("/v1/media/img-1"), source = "CHAT", clientMessageId = null,
                replyTo = null, promptTokens = null, completionTokens = null
            )
        )
        val built = builder.chatMessages(persona(), mood(), null, messages, now)

        assertEquals(listOf("system", "user", "assistant", "user"), built.map { it.role })
        assertEquals("在干嘛", built[1].content)
        assertTrue("附件要告诉模型「你看不到内容」", built[3].content.contains("看不到内容"))
    }

    @Test
    fun `the remaining budget decides history length instead of a fixed forty cap`() {
        val messages = (1..60).map { index ->
            com.yap.agentd.store.Message(
                id = "m$index", sessionId = FileStore.SEED_SESSION_ID, blockIndex = 0,
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "第 $index 句", createdAt = index.toLong(), readAt = null,
                attachments = emptyList(), source = "CHAT", clientMessageId = null,
                replyTo = null, promptTokens = null, completionTokens = null
            )
        }
        val built = builder.chatMessages(persona(), null, null, messages, now)
        assertEquals("60 条短消息全都在预算内（不再被 40 条截断）", 61, built.size)
        assertEquals("顺序仍然是升序", "第 60 句", built.last().content)
    }

    @Test
    fun `proactive messages add the one line instruction and the situation`() {
        val built = builder.proactiveMessages(persona(), mood(), null, emptyList(), now)
        val system = built.first().content
        assertTrue(system.contains("# 现在的情况"))
        assertTrue(system.contains("只输出这一句话本身"))
        assertTrue(built.any { it.role == "user" && it.content.contains("主动找他") })
    }

    private fun mood() = Mood(
        personaId = FileStore.SEED_PERSONA_1_ID,
        label = "有点累",
        intensity = 3,
        updatedAt = now - 60_000L,
        valence = -1,
        arousal = 2,
        dominance = 3,
        reason = "连着加班"
    )
}
