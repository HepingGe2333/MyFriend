package com.myfriend.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 人设引导脚手架的**运行时行为**测试（`spec/persona-guidance/scaffold.json` 的语义）。
 *
 * 分工：
 * - 本文件只依赖 Kotlin 标准库，所以同一个文件在 JVM（`:agentcore:jvmTest`）与
 *   iOS（`iosSimulatorArm64Test`）上编译、跑的是同一批断言；
 * - 「Kotlin 常量 == `spec/persona-guidance/scaffold.json` 逐字一致」那一条必须读文件，
 *   而 commonTest 不能碰文件系统（iOS target 也会编译它），所以那份用例在
 *   `agentcore/src/jvmTest/.../PersonaPromptScaffoldSpecTest.kt`。链起来就是
 *   **输出 ↔ 常量 ↔ spec 的 JSON**，任何一环漂了都会红。
 *
 * 断言口径刻意用「包含」而不是「整份字符串相等」：整份相等只能证明「现在长这样」，
 * 包含式断言能把规范里的每一条单独钉住（少了哪条、多了哪条一眼看出来）。
 */
class PersonaPromptScaffoldTest {

    // ------------------------------------------------------------------ 夹具

    /** 规范里所有占位符的取值（`scaffold.json` 的 `lines` 只用到这些键）。 */
    private val values = mapOf(
        "name" to "林深",
        "meta" to "26 岁，建筑师",
        "worldName" to "临港新城·云筑建筑事务所",
        "era" to "当代，中国沿海新一线城市，2026 年",
        "description" to "在事务所画图、审图",
        "notes" to "周三是母亲打电话的日子，别问设计上的事。",
        "weekday" to "周日",
        "time" to "19:40",
        "activity" to "事务所赶第三版方案",
        "until" to "21:30",
        "mood" to "有点累",
        "intensity" to "3",
        "tone" to "简短直接，偏冷淡务实",
        "phrases" to "「在忙，晚点说」、「别想太多」"
    )

    /** 按 [values] 替换模板里的 `{…}`；模板里没有的占位符不会被替换（断言时能看出漂移）。 */
    private fun filled(template: String): String {
        var text = template
        for ((key, value) in values) text = text.replace("{$key}", value)
        return text
    }

    /** 「一切都在」的那份输入：身份带 meta、世界带 notes、状态忙且有心、有心情、有话风。 */
    private fun fullInput(): PromptScaffoldInput = PromptScaffoldInput(
        name = "林深",
        meta = "26 岁，建筑师",
        world = PromptWorld(
            worldName = "临港新城·云筑建筑事务所",
            era = "当代，中国沿海新一线城市，2026 年",
            description = "在事务所画图、审图",
            notes = "周三是母亲打电话的日子，别问设计上的事。"
        ),
        state = PromptState(
            weekday = "周日",
            time = "19:40",
            // 故意带「在」：模板是「你在{activity}」，值里的「在」必须被归一化掉。
            activity = "在事务所赶第三版方案",
            until = "21:30",
            availability = PromptAvailability.BUSY,
            mood = "有点累",
            moodIntensity = 3
        ),
        voice = PromptVoice(
            tone = "简短直接，偏冷淡务实",
            phrases = listOf("在忙，晚点说", "别想太多")
        )
    )

    private fun assertContainsAll(prompt: String, vararg expected: String) {
        for (text in expected) {
            assertTrue(prompt.contains(text), "提示词里少了这一行/这一段：\n$text\n----- 实际 -----\n$prompt")
        }
    }

    // ------------------------------------------------------------------ 完整输出

    @Test
    fun `full prompt contains every section title in canonical order`() {
        val prompt = PersonaPromptScaffold.build(fullInput())

        val titles = PersonaPromptScaffold.Section.all.values.toList()
        assertEquals(6, titles.size, "规范里有六个段")
        for (title in titles) {
            assertTrue(prompt.contains(title), "缺少段标题：$title\n$prompt")
        }
        // 顺序不可乱（README §1「越靠前越强」）。
        val positions = titles.map { prompt.indexOf(it) }
        assertEquals(positions.sorted(), positions, "段标题顺序不对：$positions\n$prompt")
        assertEquals(0, positions.first(), "第一段必须是「# 你是谁」")
    }

    @Test
    fun `full prompt contains every applicable scalar line template rendered`() {
        val prompt = PersonaPromptScaffold.build(fullInput())
        val line = PersonaPromptScaffold.Line

        assertContainsAll(
            prompt,
            filled(line.IDENTITY_BODY_WITH_META),
            filled(line.WORLD_NAME),
            filled(line.WORLD_INTRO),
            filled(line.WORLD_NOTES),
            line.WORLD_HINT,
            filled(line.STATE_LINE),
            filled(line.STATE_BUSY),
            filled(line.STATE_MOOD),
            filled(line.VOICE_TONE),
            filled(line.VOICE_PHRASES),
            line.VOICE_NO_NARRATION,
            line.RELATIONSHIP_DEFAULT
        )
        assertContainsAll(prompt, *line.voiceRules.toTypedArray())
        assertContainsAll(prompt, *line.boundaryRules.toTypedArray())

        // 同一份输入里不该同时出现的替代行。
        assertFalse(prompt.contains("你是林深本人。"), "带 meta 时不该再用没有括号的身份句")
        assertFalse(prompt.contains(line.WORLD_EMPTY), "有世界设定时不该说「没有特别的世界设定」")
        assertFalse(prompt.contains(line.STATE_IDLE), "正在忙时不该说「没在忙什么」")
        assertFalse(prompt.contains("可以回，但会慢一点"), "BUSY 时不该用「可以回，但会慢一点」")
    }

    @Test
    fun `boundaries section keeps the four rules verbatim`() {
        val prompt = PersonaPromptScaffold.build(fullInput())
        val rules = PersonaPromptScaffold.Line.boundaryRules
        assertEquals(4, rules.size, "规范里边界是四条")
        val section = prompt.substring(prompt.indexOf(PersonaPromptScaffold.Section.BOUNDARIES))
        for (rule in rules) {
            assertTrue(section.contains("- $rule"), "边界段少了这一条（必须是列表项）：$rule\n$section")
        }
        // 列表项顺序必须与 JSON 一致。
        val positions = rules.map { section.indexOf(it) }
        assertEquals(positions.sorted(), positions, "边界四条的顺序不对")
    }

    @Test
    fun `no placeholder survives the build`() {
        val prompt = PersonaPromptScaffold.build(fullInput())
        assertFalse(prompt.contains("{"), "还有没被替换的占位符：\n$prompt")
        assertFalse(prompt.contains("}"), "还有没被替换的占位符：\n$prompt")
    }

    @Test
    fun `sections are separated by exactly one blank line and there is no trailing newline`() {
        val prompt = PersonaPromptScaffold.build(fullInput())
        assertFalse(prompt.startsWith("\n"))
        assertFalse(prompt.endsWith("\n"), "末尾不补换行（两端逐字比对时更简单）")
        assertFalse(prompt.contains("\n\n\n"), "段之间只留一个空行")
        assertEquals(6, prompt.split("\n\n").size, "六段 → 五个分隔符（六个部分）")
    }

    // ------------------------------------------------------------------ 身份

    @Test
    fun `without meta the identity line has no parentheses`() {
        val prompt = PersonaPromptScaffold.build(fullInput().copy(meta = null))
        assertTrue(prompt.contains(PersonaPromptScaffold.Line.IDENTITY_BODY.replace("{name}", "林深")))
        assertFalse(prompt.contains("（26 岁，建筑师）"))
        assertFalse(prompt.contains("本人（"), "没有 meta 时不该留一个空括号")
    }

    @Test
    fun `name placeholder is replaced in the identity sentence`() {
        val prompt = PersonaPromptScaffold.build(fullInput().copy(name = "阿岚"))
        assertTrue(prompt.contains("你是阿岚本人（26 岁，建筑师）"), prompt)
        assertFalse(prompt.contains("{name}"))
    }

    // ------------------------------------------------------------------ 世界

    @Test
    fun `empty world uses world_empty and never prints the notes line`() {
        val prompt = PersonaPromptScaffold.build(
            PromptScaffoldInput(name = "林深", world = PromptWorld())
        )
        assertTrue(prompt.contains(PersonaPromptScaffold.Line.WORLD_EMPTY), prompt)
        assertFalse(prompt.contains("其他背景"), "没有 notes 时不该出现「其他背景」")
        assertFalse(prompt.contains(PersonaPromptScaffold.Line.WORLD_HINT), "没有世界时不必解释怎么用世界")
    }

    @Test
    fun `world without notes prints intro and hint but no notes line`() {
        val prompt = PersonaPromptScaffold.build(
            fullInput().copy(world = PromptWorld(era = "当代", description = "在事务所画图"))
        )
        assertTrue(prompt.contains("当代。在事务所画图"), prompt)
        assertTrue(prompt.contains(PersonaPromptScaffold.Line.WORLD_HINT))
        assertFalse(prompt.contains("其他背景"), "没有 notes 时不该出现「其他背景」")
        assertFalse(prompt.contains(PersonaPromptScaffold.Line.WORLD_EMPTY))
    }

    @Test
    fun `world name uses the scaffold line only when it is set`() {
        val withName = PersonaPromptScaffold.build(
            fullInput().copy(world = PromptWorld(worldName = "临港新城", era = "当代"))
        )
        assertTrue(
            withName.contains(PersonaPromptScaffold.Line.WORLD_NAME.replace("{worldName}", "临港新城")),
            "worldName 非空时必须用 world_name 模板：" + withName
        )

        val withoutName = PersonaPromptScaffold.build(
            fullInput().copy(world = PromptWorld(era = "当代", description = "在事务所画图"))
        )
        assertFalse(
            withoutName.contains("你所在的地方："),
            "worldName 为空时不该出现 world_name 行：" + withoutName
        )
        // 只有 worldName 也算有世界：至少渲染 world_name + world_hint，不退回 world_empty。
        val nameOnly = PersonaPromptScaffold.build(
            fullInput().copy(world = PromptWorld(worldName = "临港新城"))
        )
        assertTrue(
            nameOnly.contains(PersonaPromptScaffold.Line.WORLD_NAME.replace("{worldName}", "临港新城")),
            nameOnly
        )
        assertTrue(nameOnly.contains(PersonaPromptScaffold.Line.WORLD_HINT), nameOnly)
        assertFalse(nameOnly.contains(PersonaPromptScaffold.Line.WORLD_EMPTY), nameOnly)
    }

    @Test
    fun `world naming line comes before intro and hint`() {
        val prompt = PersonaPromptScaffold.build(fullInput())
        val section = prompt.substringAfter(PersonaPromptScaffold.Section.WORLD)
        val nameIndex = section.indexOf("你所在的地方：临港新城·云筑建筑事务所。")
        val introIndex = section.indexOf("当代，中国沿海新一线城市，2026 年。")
        val hintIndex = section.indexOf(PersonaPromptScaffold.Line.WORLD_HINT)
        assertTrue(nameIndex in 0 until introIndex, "world_name 要在 world_intro 之前：$section")
        assertTrue(introIndex < hintIndex, "world_intro 要在 world_hint 之前：$section")
    }

    @Test
    fun `notes are truncated at the spec limit`() {
        val longNotes = "设".repeat(PersonaPromptScaffold.MAX_NOTES_CHARS + 50)
        val prompt = PersonaPromptScaffold.build(
            fullInput().copy(world = PromptWorld(era = "当代", notes = longNotes))
        )
        val line = prompt.lines().first { it.startsWith("其他背景：") }
        assertEquals(
            "其他背景：" + "设".repeat(PersonaPromptScaffold.MAX_NOTES_CHARS) + "…",
            line,
            "notes 超过 1200 字要截断并补「…」"
        )
    }

    // ------------------------------------------------------------------ 状态

    @Test
    fun `busy state uses the busy sentence with activity and until`() {
        val prompt = PersonaPromptScaffold.build(fullInput())
        assertTrue(
            prompt.contains(
                "你在事务所赶第三版方案，大概 21:30 结束。这段时间你不太有空：回复短一点，明确说自己几点后再说；不要谎称有空，也不要主动开新话题。"
            ),
            prompt
        )
    }

    @Test
    fun `limited state uses the limited sentence`() {
        val prompt = PersonaPromptScaffold.build(
            fullInput().copy(
                state = fullInput().state.copy(availability = PromptAvailability.LIMITED)
            )
        )
        assertTrue(
            prompt.contains("你在事务所赶第三版方案，大概 21:30 结束。可以回，但会慢一点。"),
            prompt
        )
        assertFalse(prompt.contains("不要谎称有空"), "LIMITED 不该用 BUSY 那一句")
    }

    @Test
    fun `free state or missing until falls back to the idle sentence`() {
        val free = PersonaPromptScaffold.build(
            fullInput().copy(state = fullInput().state.copy(availability = PromptAvailability.FREE))
        )
        assertTrue(free.contains(PersonaPromptScaffold.Line.STATE_IDLE), free)

        // BUSY 但没有 until：宁可说「没在忙什么」，也不要印出「你在…，大概  结束」。
        val noUntil = PersonaPromptScaffold.build(
            fullInput().copy(state = fullInput().state.copy(until = null))
        )
        assertTrue(noUntil.contains(PersonaPromptScaffold.Line.STATE_IDLE), noUntil)
    }

    @Test
    fun `state line uses the caller supplied weekday and time`() {
        val prompt = PersonaPromptScaffold.build(fullInput())
        assertTrue(prompt.contains("现在是周日 19:40。"), prompt)
    }

    @Test
    fun `mood line carries the mood word and clamped intensity`() {
        val prompt = PersonaPromptScaffold.build(fullInput())
        assertTrue(prompt.contains("心情：有点累（强度 3/5）。"), prompt)

        val clamped = PersonaPromptScaffold.build(
            fullInput().copy(state = fullInput().state.copy(moodIntensity = 9))
        )
        assertTrue(clamped.contains("心情：有点累（强度 5/5）。"), "强度要夹在 1–5：$clamped")

        val noMood = PersonaPromptScaffold.build(
            fullInput().copy(state = fullInput().state.copy(mood = null))
        )
        assertFalse(noMood.contains("心情："), "没有心情词时整行省略：$noMood")
    }

    // ------------------------------------------------------------------ 话风

    @Test
    fun `phrases are capped at six and rendered once`() {
        val phrases = (1..8).map { "第${it}句" }
        val prompt = PersonaPromptScaffold.build(
            fullInput().copy(voice = PromptVoice(tone = "简短", phrases = phrases))
        )
        for (index in 1..PersonaPromptScaffold.MAX_PHRASES) {
            assertTrue(prompt.contains("「第${index}句」"), "前六句都该在：$prompt")
        }
        assertFalse(prompt.contains("「第7句」"), "第七句必须被截掉：$prompt")
        assertFalse(prompt.contains("「第8句」"), "第八句必须被截掉：$prompt")
        assertEquals(
            1,
            prompt.lines().count { it.contains("你平时会说的话") },
            "常用语只出现一次（不是每句都套）"
        )
    }

    @Test
    fun `voice tone and rules are present and tone is omitted when blank`() {
        val prompt = PersonaPromptScaffold.build(fullInput())
        assertTrue(prompt.contains("你的说话风格：简短直接，偏冷淡务实。"), prompt)
        assertTrue(prompt.contains("- 句子短（1-2 句），少感叹号，少书面词。"), prompt)
        assertTrue(prompt.contains("- 情绪压着说：先给事实，被戳到才多露半句。"), prompt)
        assertTrue(prompt.contains("不要写动作描写、不要括号旁白。"), prompt)

        val noTone = PersonaPromptScaffold.build(
            fullInput().copy(voice = PromptVoice(phrases = emptyList()))
        )
        assertFalse(noTone.contains("你的说话风格："), noTone)
        assertFalse(noTone.contains("你平时会说的话"), noTone)
        assertTrue(noTone.contains("- 句子短（1-2 句），少感叹号，少书面词。"), "规则两行永远在")
    }

    // ------------------------------------------------------------------ 关系 / 边界

    @Test
    fun `custom relationship replaces the default sentence`() {
        val prompt = PersonaPromptScaffold.build(fullInput().copy(relationship = "你们从初中就认识。"))
        assertTrue(prompt.contains("你们从初中就认识。"), prompt)
        assertFalse(prompt.contains(PersonaPromptScaffold.Line.RELATIONSHIP_DEFAULT))
    }

    @Test
    fun `default relationship is used when none is given`() {
        val prompt = PersonaPromptScaffold.build(fullInput().copy(relationship = "  "))
        assertTrue(prompt.contains(PersonaPromptScaffold.Line.RELATIONSHIP_DEFAULT), prompt)
    }

    // ------------------------------------------------------------------ 扁平入口（Swift）

    @Test
    fun `render builds the same prompt as build for the same values`() {
        val viaRender = PersonaPromptScaffold.render(
            name = "林深",
            meta = "26 岁，建筑师",
            worldName = "临港新城·云筑建筑事务所",
            era = "当代，中国沿海新一线城市，2026 年",
            description = "在事务所画图、审图",
            notes = "周三是母亲打电话的日子，别问设计上的事。",
            weekday = "周日",
            time = "19:40",
            activity = "在事务所赶第三版方案",
            until = "21:30",
            availability = "busy",
            mood = "有点累",
            moodIntensity = 3,
            tone = "简短直接，偏冷淡务实",
            phrases = listOf("在忙，晚点说", "别想太多")
        )
        assertEquals(PersonaPromptScaffold.build(fullInput()), viaRender)
    }

    @Test
    fun `render treats blank availability as free and unknown values as busy`() {
        val idle = PersonaPromptScaffold.render(
            name = "林深",
            weekday = "周日",
            time = "19:40",
            activity = "在事务所赶图",
            until = "21:30"
        )
        assertTrue(idle.contains(PersonaPromptScaffold.Line.STATE_IDLE), idle)

        val busy = PersonaPromptScaffold.render(
            name = "林深",
            weekday = "周日",
            time = "19:40",
            activity = "在事务所赶图",
            until = "21:30",
            availability = "开会中"
        )
        assertTrue(busy.contains("这段时间你不太有空"), "认不出的 availability 按 BUSY（不谎称有空）：$busy")
    }

    @Test
    fun `availability parsing is case insensitive and defaults to busy`() {
        assertEquals(PromptAvailability.BUSY, PromptAvailability.parse("busy"))
        assertEquals(PromptAvailability.LIMITED, PromptAvailability.parse(" Limited "))

        assertEquals(PromptAvailability.FREE, PromptAvailability.parse("free"))
        assertEquals(PromptAvailability.BUSY, PromptAvailability.parse(null))
        assertEquals(PromptAvailability.BUSY, PromptAvailability.parse(""))
        assertEquals(PromptAvailability.BUSY, PromptAvailability.parse("开会中"))
    }

    @Test
    fun `activity phrase drops a single leading zai`() {
        assertEquals("事务所赶图", PersonaPromptScaffold.activityPhrase("在事务所赶图"))
        assertEquals("家休息", PersonaPromptScaffold.activityPhrase("在家休息"))
        assertEquals("开会", PersonaPromptScaffold.activityPhrase("开会"))
        assertEquals("在", PersonaPromptScaffold.activityPhrase("在"))
    }
}
