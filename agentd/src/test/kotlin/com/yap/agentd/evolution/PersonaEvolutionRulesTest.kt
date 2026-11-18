package com.yap.agentd.evolution

import com.yap.agentd.persona.PersonaFields
import com.yap.agentd.store.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人格进化**规则层**（移植自客户端 `PersonaEvolution`，语义必须一致）：
 *
 * 白名单四字段、一次 ≤3 条（add ≤2 / remove ≤1）、置信度 ≥0.6、value ≤40 字、
 * 去重/存在性校验、quirks 的句级增删。规则错了会让模型"得寸进尺"地改人设。
 */
class PersonaEvolutionRulesTest {

    private val empty = PersonaFields.Current()

    private fun change(
        field: PersonaEvolutionRules.Field,
        op: PersonaEvolutionRules.Op,
        value: String,
        confidence: Double = 0.9
    ) = PersonaEvolutionRules.Change(field, op, value, "测试", confidence)

    @Test
    fun `parse tolerates markdown fences and surrounding prose`() {
        val raw = """
            好的，这是我的判断：
            ```json
            {"changes":[{"field":"traits","op":"add","value":"嘴硬心软","reason":"先顶一句再软","confidence":0.8}]}
            ```
            以上。
        """.trimIndent()

        val changes = PersonaEvolutionRules.parse(raw)
        assertEquals(1, changes.size)
        assertEquals(PersonaEvolutionRules.Field.TRAITS, changes[0].field)
        assertEquals(PersonaEvolutionRules.Op.ADD, changes[0].op)
        assertEquals("嘴硬心软", changes[0].value)
        assertEquals(0.8, changes[0].confidence, 0.001)
    }

    @Test
    fun `parse drops unknown fields ops and blank values`() {
        val raw = """{"changes":[
            {"field":"identity","op":"add","value":"换名字","confidence":0.9},
            {"field":"traits","op":"replace","value":"替换","confidence":0.9},
            {"field":"traits","op":"add","value":"   ","confidence":0.9},
            {"field":"traits","op":"add","value":"慢热","confidence":0.9}
        ]}"""

        val changes = PersonaEvolutionRules.parse(raw)
        assertEquals(1, changes.size)
        assertEquals("慢热", changes[0].value)
    }

    @Test
    fun `sanitize enforces count confidence and value length limits`() {
        val changes = PersonaEvolutionRules.sanitize(
            listOf(
                change(PersonaEvolutionRules.Field.TRAITS, PersonaEvolutionRules.Op.ADD, "慢热", 0.9),
                change(PersonaEvolutionRules.Field.TRAITS, PersonaEvolutionRules.Op.ADD, "嘴硬", 0.8),
                change(PersonaEvolutionRules.Field.TRAITS, PersonaEvolutionRules.Op.ADD, "话少", 0.7),
                change(PersonaEvolutionRules.Field.LIKES, PersonaEvolutionRules.Op.ADD, "夜里散步", 0.5),
                change(
                    PersonaEvolutionRules.Field.BEHAVIORS,
                    PersonaEvolutionRules.Op.ADD,
                    "这是一个超过四十个字符的值".repeat(3),
                    0.95
                )
            ),
            empty
        )

        assertTrue("总数不超过 3", changes.size <= PersonaEvolutionRules.MAX_CHANGES)
        assertTrue("add 不超过 2", changes.count { it.op == PersonaEvolutionRules.Op.ADD } <= PersonaEvolutionRules.MAX_ADDS)
        assertTrue("低于 0.6 的丢掉", changes.none { it.value == "夜里散步" })
        assertTrue("value 截到 40 字", changes.all { it.value.length <= PersonaEvolutionRules.VALUE_MAX_CHARS })
    }

    @Test
    fun `sanitize skips duplicates of current values and allows at most one remove`() {
        val current = PersonaFields.Current(
            traits = listOf("慢热"),
            likes = listOf("夜里散步"),
            behaviors = listOf("先听完再给建议"),
            quirks = "写字前会先转笔。"
        )
        val changes = PersonaEvolutionRules.sanitize(
            listOf(
                change(PersonaEvolutionRules.Field.TRAITS, PersonaEvolutionRules.Op.ADD, "慢热", 0.9),
                change(PersonaEvolutionRules.Field.LIKES, PersonaEvolutionRules.Op.REMOVE, "夜里散步", 0.9),
                change(PersonaEvolutionRules.Field.BEHAVIORS, PersonaEvolutionRules.Op.REMOVE, "先听完再给建议", 0.8),
                change(PersonaEvolutionRules.Field.QUIRKS, PersonaEvolutionRules.Op.REMOVE, "先转笔", 0.95)
            ),
            current
        )

        assertTrue("已有值不重复 add", changes.none { it.op == PersonaEvolutionRules.Op.ADD })
        assertEquals("remove 最多一条", 1, changes.count { it.op == PersonaEvolutionRules.Op.REMOVE })
        assertTrue("remove 的对象必须在当前人格里", changes.single().value.isNotBlank())
    }

    @Test
    fun `apply merges lists and quirks with the documented limits`() {
        val current = PersonaFields.Current(traits = listOf("慢热"), quirks = "写字前会先转笔。")
        val after = PersonaEvolutionRules.apply(
            current,
            listOf(
                change(PersonaEvolutionRules.Field.TRAITS, PersonaEvolutionRules.Op.ADD, "嘴硬心软"),
                change(PersonaEvolutionRules.Field.QUIRKS, PersonaEvolutionRules.Op.ADD, "怕别人突然沉默。"),
                change(PersonaEvolutionRules.Field.QUIRKS, PersonaEvolutionRules.Op.REMOVE, "写字前会先转笔")
            )
        )

        assertEquals(listOf("慢热", "嘴硬心软"), after.traits)
        assertEquals("怕别人突然沉默。", after.quirks.trim())
    }

    @Test
    fun `encode and decode round trip keeps the four wire fields`() {
        val patch = PersonaEvolutionRules.encodePatch(
            listOf(
                change(PersonaEvolutionRules.Field.LIKES, PersonaEvolutionRules.Op.ADD, "雨天在家看书"),
                change(PersonaEvolutionRules.Field.BEHAVIORS, PersonaEvolutionRules.Op.REMOVE, "熬夜")
            )
        )
        val decoded = PersonaEvolutionRules.decodePatch(patch)

        assertEquals(2, decoded.size)
        assertEquals(PersonaEvolutionRules.Field.LIKES, decoded[0].field)
        assertEquals("雨天在家看书", decoded[0].value)
        assertEquals(PersonaEvolutionRules.Op.REMOVE, decoded[1].op)
        assertFalse("补丁不写 confidence", patch.contains("confidence"))
    }

    @Test
    fun `variable prompt includes current mood dimensions`() {
        val mood = Mood(
            personaId = "p",
            label = "有点累",
            intensity = 3,
            updatedAt = 0,
            valence = -1,
            arousal = 2,
            dominance = 3
        )
        val prompt = PersonaEvolutionRules.userPrompt("林深", empty, "对方：今天好累", mood, now = 0)
        assertTrue(prompt.contains("## 当前情绪"))
        assertTrue(prompt.contains("有点累"))
        assertTrue(prompt.contains("愉快度 -1"))
        assertTrue(prompt.contains("唤醒度 2/5"))
        assertTrue(prompt.contains("## 最近对话摘要"))
    }

    @Test
    fun `invalid json returns an empty patch instead of throwing`() {
        assertTrue(PersonaEvolutionRules.parse("not json at all").isEmpty())
        assertTrue(PersonaEvolutionRules.parse("").isEmpty())
        assertTrue(PersonaEvolutionRules.parse("""{"changes":"nope"}""").isEmpty())
        assertTrue(PersonaEvolutionRules.summary(emptyList()).isEmpty())
    }
}
