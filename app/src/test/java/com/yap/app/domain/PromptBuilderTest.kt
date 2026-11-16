package com.yap.app.domain

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
 * 常用语有没有超过 6 条、空世界有没有兜住、边界是不是最后一段。提示词是产品行为，
 * 改一个字都该先让这里红。
 *
 * 固定文案本身不在这里抄第二遍——`PersonaPromptScaffoldContractTest` 负责「中文与
 * spec/persona-guidance/scaffold.json 逐字一致」；这里负责「运行时到底用了哪一句、用对了没有」。
 */
class PromptBuilderTest {

    private val scaffold = PromptBuilder.Scaffold

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
        val who = text.indexOf(scaffold.SECTION_IDENTITY)
        val where = text.indexOf(scaffold.SECTION_WORLD)
        val state = text.indexOf(scaffold.SECTION_STATE)
        val how = text.indexOf(scaffold.SECTION_VOICE)
        val relation = text.indexOf(scaffold.SECTION_RELATIONSHIP)
        val variable = text.indexOf("## 你记得的事")
        val tool = text.indexOf("## 你可以调用这些工具")
        val boundary = text.indexOf(scaffold.SECTION_BOUNDARIES)
        listOf(who, where, state, how, relation, variable, tool, boundary).forEach {
            assertTrue("缺少某一段（index=$it）：\n$text", it >= 0)
        }
        assertTrue("身份要在世界之前", who < where)
        assertTrue("世界要在状态之前", where < state)
        assertTrue("状态要在话风之前", state < how)
        assertTrue("话风要在关系之前", how < relation)
        assertTrue("记忆/工具说明要在关系之后", relation < variable && relation < tool)
        assertTrue("边界要在可变部分之后（最后一段）", boundary > variable && boundary > tool)
        assertTrue(
            "提示词以脚手架最后一条边界规则结束",
            text.trimEnd().endsWith("- " + scaffold.BOUNDARY_RULES.last())
        )
    }

    @Test
    fun `包含世界段 话风段 与边界`() {
        val text = PromptBuilder.system(inputs())
        assertTrue(text.contains(scaffold.SECTION_WORLD))
        // 世界段的第一行是脚手架的 world_name（你所在的地方：{worldName}。）。
        assertTrue(
            "worldName 要用世界的 world_name 模板渲染",
            text.contains(scaffold.WORLD_NAME.replace("{worldName}", "临港新城·云筑建筑事务所"))
        )
        // 第二行是脚手架的 world_intro（era。description）。
        assertTrue(text.contains("当代，中国沿海新一线城市，2026 年。他带着六人小组负责一个高层住宅项目，加班是常态。"))
        assertTrue(text.contains("- 工作日 09:00-18:30 在事务所画图、审图"))
        assertTrue(text.contains("- 母亲（母亲）：每周三晚打电话"))
        assertTrue(text.contains("- 必须：按节点交图"))
        assertTrue(text.contains("这些是你的生活背景：只在跟话题相关时带一句，不要主动汇报日程，也不要念设定。"))
        assertTrue(text.contains(scaffold.SECTION_VOICE))
        assertTrue(text.contains("你的说话风格：简短直接，偏冷淡务实。"))
        assertTrue(text.contains("自然使用，最多带 1 处，不要每句都套"))
        assertTrue(text.contains(scaffold.SECTION_BOUNDARIES))
        assertTrue(text.contains("不解释 AI 身份"))
        assertTrue(text.contains("不写动作描写"))
        assertTrue(text.contains("不替对方做决定"))
        assertTrue(text.contains("不要心理分析对方"))
        assertTrue(text.contains("不清楚"))
    }

    @Test
    fun `状态段带本地时间 当前活动 心情 与安静时段`() {
        val text = PromptBuilder.system(inputs())
        assertTrue("要有周三与 19:40", text.contains("现在是周三 19:40。"))
        assertTrue(text.contains("你在事务所赶第三版方案，大概 21:10 结束。"))
        assertTrue("心情按脚手架拆成「心情 + 强度」", text.contains("心情：有点累（强度 3/5）。"))
        assertFalse(text.contains("这个点你已经睡了"))

        val asleep = PromptBuilder.system(inputs().copy(asleep = true))
        assertTrue("睡觉时间要明确写出来", asleep.contains("这个点你已经睡了"))
    }

    @Test
    fun `心情的原因不会因为脚手架而丢掉`() {
        val withReason = inputs().copy(
            moodBlock = "\n\n## 你现在的心情\n有点累（强度 3/5，明显）。原因：今天加班。"
        )
        val text = PromptBuilder.system(withReason)
        assertTrue(text.contains("心情：有点累（强度 3/5）。"))
        assertTrue("「原因」是数据，接在脚手架心情句之后", text.contains("原因：今天加班。"))
    }

    @Test
    fun `过期活动在状态里显示没在忙什么`() {
        val expired = activity.copy(until = now - 1)
        val text = PromptBuilder.system(inputs(currentActivity = expired))
        assertTrue(text.contains(scaffold.STATE_IDLE))
    }

    @Test
    fun `没有活动时也用脚手架的没在忙什么`() {
        val text = PromptBuilder.system(inputs(currentActivity = null))
        assertTrue(text.contains(scaffold.STATE_IDLE))
    }

    @Test
    fun `没有时间时不硬凑时间句 状态段其余内容照常`() {
        // now <= 0 是「调用方没给时间」（人格卡预览等）：脚手架只有一种时间句，硬凑会写出
        // 「现在是 。」这种句子，所以整行省略，而不是换一句规范外的中文。
        val text = PromptBuilder.system(inputs(currentActivity = null).copy(now = 0L))
        assertTrue(text.contains(scaffold.SECTION_STATE))
        assertFalse("没有时间就不该有时间句", text.contains("现在是"))
        assertTrue(text.contains(scaffold.STATE_IDLE))
        assertTrue(text.contains("心情：有点累（强度 3/5）。"))
    }

    @Test
    fun `常用语最多 6 条`() {
        val text = PromptBuilder.system(inputs())
        assertTrue(text.contains("「口头禅1」"))
        assertTrue(text.contains("「口头禅6」"))
        assertFalse("第 7 条不该写进提示词", text.contains("口头禅7"))
        assertFalse(text.contains("口头禅8"))
    }

    @Test
    fun `话风的习惯与不要作为数据行进提示词`() {
        // 脚手架没有「习惯 / 不要」这两行，但它们是用户自己填的设定：
        // 丢掉等于用户改了话风却完全不影响模型。这里断言它们作为数据行接在脚手架之后。
        val text = PromptBuilder.system(inputs())
        assertTrue(text.contains("- 习惯：句子很短"))
        assertTrue(text.contains("- 不要：长篇大论"))
    }

    @Test
    fun `没有第三人称模板句`() {
        val text = PromptBuilder.system(inputs())
        assertFalse("不能出现「林深觉得」这种第三人称自述", text.contains("林深觉得"))
        assertFalse(text.contains("林深认为"))
        assertFalse(text.contains("他说自己"))
        assertTrue("要明确要求第一人称", text.contains("第一人称「我」"))
        assertTrue("要明确禁止第三人称", text.contains("不要用第三人称称呼自己"))
        assertTrue("要明确禁止旁白", text.contains("也不要旁白式描述自己"))
    }

    @Test
    fun `没有世界时给出脚手架的空世界句 而不是空段`() {
        val text = PromptBuilder.system(inputs(worldPreset = null))
        assertTrue("空世界也要有段标题", text.contains(scaffold.SECTION_WORLD))
        assertTrue(text.contains(scaffold.WORLD_EMPTY))
        assertTrue(text.contains(scaffold.SECTION_IDENTITY))
        assertTrue(text.contains(scaffold.SECTION_BOUNDARIES))
        // 有世界时不该出现空世界句。
        assertFalse(PromptBuilder.system(inputs()).contains(scaffold.WORLD_EMPTY))
    }

    @Test
    fun `只有 worldName 的世界预设渲染 world_name 与提示语 不退回空世界`() {
        val text = PromptBuilder.system(inputs(worldPreset = WorldPreset(worldName = "某个城市")))
        assertTrue(
            text,
            text.contains(scaffold.WORLD_NAME.replace("{worldName}", "某个城市"))
        )
        assertTrue(text, text.contains(scaffold.WORLD_HINT))
        assertFalse("有 worldName 就不是空世界", text.contains(scaffold.WORLD_EMPTY))
    }

    @Test
    fun `没有话风时用脚手架规则兜底 并保留人设卡的口头禅`() {
        val text = PromptBuilder.system(
            inputs(speechStyle = null).copy(
                card = PersonaCard(
                    identity = PersonaIdentity(name = "林深", relation = "朋友"),
                    voice = PersonaVoice(address = "阿深", pace = "慢", catchphrases = listOf("嗯", "行"))
                )
            )
        )
        assertTrue(text.contains(scaffold.SECTION_VOICE))
        scaffold.VOICE_RULES.forEach { assertTrue("缺少话风规则：$it", text.contains(it)) }
        assertTrue(text.contains(scaffold.VOICE_NO_NARRATION))
        assertTrue(text.contains("- 语速：慢"))
        assertTrue("口头禅复用脚手架的 voice_phrases 句型", text.contains("「嗯」、「行」"))
        assertFalse(text.contains("口头禅1"))
    }

    @Test
    fun `身份段按脚手架写 meta 年龄在前身份在后`() {
        val full = PromptBuilder.system(inputs())
        assertTrue(full.contains("林深本人（28 岁，建筑事务所项目负责人）"))

        val ageOnly = PromptBuilder.system(inputs().copy(role = ""))
        assertTrue(ageOnly.contains("林深本人（28 岁）"))

        val roleOnly = PromptBuilder.system(inputs().copy(age = ""))
        assertTrue(roleOnly.contains("林深本人（建筑事务所项目负责人）"))

        val noMeta = PromptBuilder.system(inputs().copy(age = "", role = ""))
        assertTrue(
            "没有 meta 时用 identity_body（不带括号）",
            noMeta.contains(scaffold.IDENTITY_BODY.replace("{name}", "林深"))
        )
    }

    @Test
    fun `身份段明确不是助手并禁止第三人称`() {
        val identity = PromptBuilder.base(inputs()).substringBefore(scaffold.SECTION_WORLD)
        assertTrue(identity.contains("你不是助手"))
        assertTrue(identity.contains("第一人称"))
        assertTrue(identity.contains("不要用第三人称"))
        assertTrue(identity.contains("林深本人"))
    }

    @Test
    fun `世界段的 notes 渲染成其他背景并截断到 1200 字`() {
        val longNotes = "雨".repeat(1300)
        val text = PromptBuilder.system(inputs(worldPreset = world.copy(notes = longNotes)))
        assertTrue(text.contains("其他背景：" + "雨".repeat(WorldPreset.MAX_NOTES_CHARS)))
        assertFalse("超过 1200 字必须截断", text.contains("雨".repeat(WorldPreset.MAX_NOTES_CHARS + 1)))
    }

    @Test
    fun `没有关系时用脚手架的关系默认句`() {
        val text = PromptBuilder.system(
            inputs().copy(relation = "", address = "", card = PersonaCard())
        )
        assertTrue(text.contains(scaffold.SECTION_RELATIONSHIP))
        assertTrue(text.contains(scaffold.RELATIONSHIP_DEFAULT))
    }

    @Test
    fun `边界段是最后一段 且用脚手架的 4 条规则`() {
        val text = PromptBuilder.system(inputs())
        assertEquals(
            "边界段必须是脚手架那 4 条",
            scaffold.BOUNDARY_RULES.size,
            text.substringAfter(scaffold.SECTION_BOUNDARIES).lines().count { it.startsWith("- ") }
        )
        assertTrue(text.trimEnd().endsWith("- " + scaffold.BOUNDARY_RULES.last()))
        assertFalse("边界之后不该再有段", text.substringAfter(scaffold.SECTION_BOUNDARIES).contains("\n# "))
    }

    @Test
    fun `打印一份最终系统提示词样例供人工复核`() {
        println(PromptBuilder.system(inputs()))
    }
}
