package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 系统提示词的分段顺序与硬约束（规范 §1 / §6.3）。
 *
 * 这些断言刻意写得「像在检查一份合同」：段落顺序、第一人称、世界/话风有没有带进去、
 * 常用语有没有超过 6 条、边界是不是最后一段。提示词是产品行为，改一个字都该先让这里红。
 */
class PromptBuilderTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    /** 2026-11-18 是周三；19:40 对应规范里的实测场景。 */
    private val now = ZonedDateTime.of(2026, 11, 18, 19, 40, 0, 0, zone).toInstant().toEpochMilli()

    private val world = WorldPreset(
        worldName = "临港新城·云筑建筑事务所",
        era = "当代，中国沿海新一线城市，2026 年",
        description = "他带着六人小组负责一个高层住宅项目，加班是常态。",
        schedule = listOf(
            WorldScheduleEntry("工作日", "09:00", "18:30", "在事务所画图、审图"),
            WorldScheduleEntry("周三", "20:00", "20:30", "接母亲电话")
        ),
        relationships = listOf(WorldRelationship("母亲", "母亲", "每周三晚打电话")),
        obligations = listOf("按节点交图")
    )

    private val speech = SpeechStyle(
        tone = "简短直接，偏冷淡务实",
        phrases = (1..8).map { "口头禅$it" },
        habits = listOf("句子很短", "少用感叹号"),
        avoid = listOf("长篇大论")
    )

    private val activity = PersonaActivity(
        kind = ActivityKind.WORK,
        activity = "在事务所赶第三版方案",
        until = now + 90 * 60_000L,
        availability = ActivityAvailability.BUSY
    )

    private fun inputs(
        worldPreset: WorldPreset? = world,
        speechStyle: SpeechStyle? = speech,
        currentActivity: PersonaActivity? = activity,
        nowMillis: Long = now
    ) = PromptBuilder.Inputs(
        name = "林深",
        age = "28 岁",
        role = "建筑事务所项目负责人",
        relation = "朋友",
        address = "阿深",
        card = PersonaCard(
            identity = PersonaIdentity(name = "林深", age = "28 岁", role = "建筑事务所项目负责人", relation = "朋友"),
            voice = PersonaVoice(address = "阿深"),
            bond = "你们认识两年了，他不太主动，但会记得你说过的事。"
        ),
        legacyPrompt = "你是林深，说话从容、克制。",
        profileBlock = "## 你是谁\n### 性格\n- 慢热\n- 嘴硬心软",
        world = worldPreset,
        speech = speechStyle,
        activity = currentActivity,
        now = nowMillis,
        zone = zone,
        moodBlock = "\n\n## 你现在的心情\n有点累（强度 3/5，明显）。",
        asleep = false,
        extraStatus = "今天话不多。",
        variableBlocks = listOf(
            "## 你记得的事\n- 对方下个月要去哈尔滨。",
            "## 你可以调用这些工具\ntool list"
        ),
        languageSection = "\n\n## 语言\n用中文回答。"
    )

    @Test
    fun `六段顺序与边界位置都符合规范`() {
        val text = PromptBuilder.system(inputs())
        val who = text.indexOf("# 你是谁")
        val where = text.indexOf("# 你所在的世界")
        val state = text.indexOf("# 你此刻的状态")
        val how = text.indexOf("# 你怎么说话")
        val relation = text.indexOf("# 你们的关系")
        val variable = text.indexOf("## 你记得的事")
        val tool = text.indexOf("## 你可以调用这些工具")
        val boundary = text.indexOf("# 边界")
        listOf(who, where, state, how, relation, variable, tool, boundary).forEach {
            assertTrue("缺少某一段（index=$it）：\n$text", it >= 0)
        }
        assertTrue("身份要在世界之前", who < where)
        assertTrue("世界要在状态之前", where < state)
        assertTrue("状态要在话风之前", state < how)
        assertTrue("话风要在关系之前", how < relation)
        assertTrue("记忆/工具说明要在关系之后", relation < variable && relation < tool)
        assertTrue("边界要在可变部分之后（最后一段）", boundary > variable && boundary > tool)
        assertTrue("提示词以边界段结束", text.trimEnd().endsWith("一次回复最多自然带一处。"))
    }

    @Test
    fun `包含世界段 话风段 与边界`() {
        val text = PromptBuilder.system(inputs())
        assertTrue(text.contains("# 你所在的世界"))
        assertTrue(text.contains("临港新城·云筑建筑事务所"))
        assertTrue(text.contains("工作日 09:00-18:30 在事务所画图、审图"))
        assertTrue(text.contains("母亲（母亲）：每周三晚打电话"))
        assertTrue(text.contains("- 必须：按节点交图"))
        assertTrue(text.contains("# 你怎么说话"))
        assertTrue(text.contains("简短直接，偏冷淡务实"))
        assertTrue(text.contains("自然使用，不要每句都套"))
        assertTrue(text.contains("# 边界"))
        assertTrue(text.contains("不解释 AI 身份"))
        assertTrue(text.contains("不写动作描写"))
        assertTrue(text.contains("不替对方下判断"))
        assertTrue(text.contains("不清楚"))
    }

    @Test
    fun `状态段带本地时间 当前活动 心情 与安静时段`() {
        val text = PromptBuilder.system(inputs())
        assertTrue("要有周三与 19:40", text.contains("周三 19:40"))
        assertTrue(text.contains("你在事务所赶第三版方案"))
        assertTrue(text.contains("有点累"))
        assertFalse(text.contains("这个点你已经睡了"))

        val asleep = PromptBuilder.system(inputs().copy(asleep = true))
        assertTrue("睡觉时间要明确写出来", asleep.contains("这个点你已经睡了"))
    }

    @Test
    fun `过期活动在状态里显示没在忙什么`() {
        val expired = activity.copy(until = now - 1)
        val text = PromptBuilder.system(inputs(currentActivity = expired))
        assertTrue(text.contains("没在忙什么"))
    }

    @Test
    fun `常用语最多 6 条`() {
        val text = PromptBuilder.system(inputs())
        assertTrue(text.contains("口头禅1"))
        assertTrue(text.contains("口头禅6"))
        assertFalse("第 7 条不该写进提示词", text.contains("口头禅7"))
        assertFalse(text.contains("口头禅8"))
    }

    @Test
    fun `没有第三人称模板句`() {
        val text = PromptBuilder.system(inputs())
        assertFalse("不能出现「林深觉得」这种第三人称自述", text.contains("林深觉得"))
        assertFalse(text.contains("林深认为"))
        assertFalse(text.contains("他说自己"))
        assertTrue("要明确要求第一人称", text.contains("第一人称「我」"))
    }

    @Test
    fun `没有世界时不出现空的世界段`() {
        val text = PromptBuilder.system(inputs(worldPreset = null))
        assertFalse(text.contains("# 你所在的世界"))
        assertTrue(text.contains("# 你是谁"))
        assertTrue(text.contains("# 边界"))
    }

    @Test
    fun `没有话风时用通用说话要求兜底`() {
        val text = PromptBuilder.system(inputs(speechStyle = null))
        assertTrue(text.contains("# 你怎么说话"))
        assertTrue(text.contains("句子短"))
        assertFalse(text.contains("口头禅1"))
    }

    @Test
    fun `打印一份最终系统提示词样例供人工复核`() {
        println(PromptBuilder.system(inputs()))
    }

    @Test
    fun `身份段明确不是助手并禁止第三人称`() {
        val identity = PromptBuilder.base(inputs()).substringBefore("# 你所在的世界")
        assertTrue(identity.contains("你不是助手"))
        assertTrue(identity.contains("第一人称"))
        assertTrue(identity.contains("不要用第三人称"))
        assertTrue(identity.contains("林深本人"))
    }
}
