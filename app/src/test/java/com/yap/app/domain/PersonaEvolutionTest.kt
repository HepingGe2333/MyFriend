package com.yap.app.domain

import com.yap.app.domain.PersonaEvolution.Change
import com.yap.app.domain.PersonaEvolution.Current
import com.yap.app.domain.PersonaEvolution.Field
import com.yap.app.domain.PersonaEvolution.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人格进化评估（0.13）：模型输出的补丁一律不可信，规则全在这几个纯函数里。
 *
 * 覆盖三块：解析（宽松、绝不抛）、规则（白名单/上限/置信度/去重）、应用与落库（上限保护、往返）。
 * 不依赖数据库/网络/Android API，全部是内存里的字符串与 data class。
 */
class PersonaEvolutionTest {

    private val current = Current(
        traits = listOf("慢热", "嘴硬心软"),
        likes = listOf("夜里散步"),
        behaviors = listOf("先听完再给建议"),
        quirks = "不说「加油」。喜欢用省略号"
    )

    private fun add(field: Field, value: String, confidence: Double = 1.0): Change =
        Change(field, Op.ADD, value, "", confidence)

    private fun remove(field: Field, value: String, confidence: Double = 1.0): Change =
        Change(field, Op.REMOVE, value, "", confidence)

    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析正常 JSON 读出四组字段`() {
        val raw = """
            {"changes":[
              {"field":"traits","op":"add","value":"嘴硬心软","reason":"最近几次都先顶一句再软下来","confidence":0.8},
              {"field":"quirks","op":"remove","value":"喜欢用省略号","reason":"这阵子少用了","confidence":0.7}
            ]}
        """.trimIndent()

        val changes = PersonaEvolution.parse(raw)

        assertEquals(2, changes.size)
        assertEquals(Field.TRAITS, changes[0].field)
        assertEquals(Op.ADD, changes[0].op)
        assertEquals("嘴硬心软", changes[0].value)
        assertEquals("最近几次都先顶一句再软下来", changes[0].reason)
        assertEquals(0.8, changes[0].confidence, 0.0001)
        assertEquals(Field.QUIRKS, changes[1].field)
        assertEquals(Op.REMOVE, changes[1].op)
    }

    @Test
    fun `解析容忍代码块与前后解释`() {
        val raw = """
            好的，我看了一下最近的聊天，结论如下：
            ```json
            {"changes":[{"field":"likes","op":"add","value":"雨天在家看书","confidence":0.9}]}
            ```
            以上仅供参考。
        """.trimIndent()

        val changes = PersonaEvolution.parse(raw)

        assertEquals(1, changes.size)
        assertEquals(Field.LIKES, changes.single().field)
        assertEquals("雨天在家看书", changes.single().value)
        assertEquals(0.9, changes.single().confidence, 0.0001)
    }

    @Test
    fun `坏输入一律返回空列表且不抛`() {
        val bad = listOf(
            "",
            "   ",
            "不是 json",
            "null",
            "[]",
            "[1,2,3]",
            "{}",
            """{"changes":null}""",
            """{"changes":"[]"}""",
            """{"changes":{}}""",
            "```json\n\n```",
            """{"changes":[{"field":"traits","""
        )
        bad.forEach { raw ->
            assertTrue("输入 [$raw] 应当解析为空", PersonaEvolution.parse(raw).isEmpty())
        }
    }

    @Test
    fun `非法字段与非法操作被丢弃`() {
        val raw = """
            {"changes":[
              {"field":"性格","op":"add","value":"嘴硬心软","confidence":0.9},
              {"field":"traits","op":"增加","value":"慢热","confidence":0.9},
              {"field":"trait","op":"add","value":"慢热","confidence":0.9},
              {"field":"LIKES","op":"ADD","value":"雨天","confidence":0.9}
            ]}
        """.trimIndent()

        val changes = PersonaEvolution.parse(raw)

        assertEquals(1, changes.size)
        assertEquals(Field.LIKES, changes.single().field)
        assertEquals(Op.ADD, changes.single().op)
        assertEquals("雨天", changes.single().value)
    }

    @Test
    fun `字段或操作类型不对整条丢弃`() {
        val raw = """
            {"changes":[
              {"field":123,"op":"add","value":"甲"},
              {"field":"traits","op":["add"],"value":"乙"},
              {"field":"traits","op":"add","value":{"text":"丙"}},
              {"field":"traits","op":"add","value":"丁","confidence":0.9}
            ]}
        """.trimIndent()

        val changes = PersonaEvolution.parse(raw)

        assertEquals(1, changes.size)
        assertEquals("丁", changes.single().value)
    }

    @Test
    fun `confidence 缺失按零处理字符串数字能读`() {
        val raw = """
            {"changes":[
              {"field":"traits","op":"add","value":"慢热"},
              {"field":"likes","op":"add","value":"雨天","confidence":"0.7"},
              {"field":"behaviors","op":"add","value":"先听完再说","confidence":true}
            ]}
        """.trimIndent()

        val changes = PersonaEvolution.parse(raw)

        assertEquals(3, changes.size)
        assertEquals(0.0, changes[0].confidence, 0.0)
        assertEquals(0.7, changes[1].confidence, 0.0001)
        assertEquals(0.0, changes[2].confidence, 0.0)

        // 没有置信度的条目会在 sanitize 被丢掉，只留下「雨天」
        val kept = PersonaEvolution.sanitize(changes, Current())
        assertEquals(1, kept.size)
        assertEquals("雨天", kept.single().value)
    }

    // ---------------------------------------------------------------- 规则

    @Test
    fun `置信度门槛是零点六`() {
        val low = add(Field.TRAITS, "嘴硬", 0.59)
        val edge = add(Field.TRAITS, "心软", 0.6)

        assertEquals(listOf("心软"), PersonaEvolution.sanitize(listOf(low, edge), Current()).map { it.value })
        assertTrue(PersonaEvolution.sanitize(listOf(low), Current()).isEmpty())
        assertTrue(PersonaEvolution.sanitize(listOf(add(Field.TRAITS, "甲", Double.NaN)), Current()).isEmpty())
    }

    @Test
    fun `sanitize 一次最多三条 add 两条 remove 一条`() {
        val changes = listOf(
            add(Field.TRAITS, "甲", 0.90),
            add(Field.LIKES, "乙", 0.80),
            add(Field.BEHAVIORS, "丙", 0.70),
            remove(Field.TRAITS, "慢热", 0.90),
            remove(Field.LIKES, "夜里散步", 0.95)
        )

        val kept = PersonaEvolution.sanitize(changes, current)

        assertEquals(3, kept.size)
        assertEquals(2, kept.count { it.op == Op.ADD })
        assertEquals(1, kept.count { it.op == Op.REMOVE })
    }

    @Test
    fun `add 最多两条按置信度取前两条`() {
        val kept = PersonaEvolution.sanitize(
            listOf(
                add(Field.TRAITS, "甲", 0.9),
                add(Field.TRAITS, "乙", 0.8),
                add(Field.TRAITS, "丙", 0.7)
            ),
            Current()
        )

        assertEquals(listOf("甲", "乙"), kept.map { it.value })
    }

    @Test
    fun `remove 最多一条`() {
        val kept = PersonaEvolution.sanitize(
            listOf(
                remove(Field.TRAITS, "慢热", 0.9),
                remove(Field.TRAITS, "嘴硬心软", 0.8)
            ),
            current
        )

        assertEquals(1, kept.size)
        assertEquals("慢热", kept.single().value)
    }

    @Test
    fun `重复的 add 与删除不存在值会被丢掉`() {
        val changes = listOf(
            add(Field.TRAITS, "慢热", 0.95),        // 已经是性格
            add(Field.TRAITS, " 慢热 ", 0.9),       // 只差空白，同样重复
            add(Field.TRAITS, "雨天出门", 0.85),     // 新的
            remove(Field.TRAITS, "不存在", 0.95),    // 删不存在的
            remove(Field.TRAITS, "嘴硬心软", 0.6)    // 存在
        )

        val kept = PersonaEvolution.sanitize(changes, current)

        assertEquals(listOf("雨天出门", "嘴硬心软"), kept.map { it.value })
        assertEquals(listOf(Op.ADD, Op.REMOVE), kept.map { it.op })
    }

    @Test
    fun `超限是跳过该条而不是整体截断`() {
        val changes = listOf(
            add(Field.TRAITS, "甲", 0.95),
            add(Field.LIKES, "乙", 0.90),
            add(Field.BEHAVIORS, "丙", 0.85),   // add 已满，跳过这一条
            remove(Field.TRAITS, "慢热", 0.80)   // 后面的 remove 仍然能进来
        )

        val kept = PersonaEvolution.sanitize(changes, current)

        assertEquals(listOf(Op.ADD, Op.ADD, Op.REMOVE), kept.map { it.op })
        assertEquals(listOf("甲", "乙", "慢热"), kept.map { it.value })
    }

    @Test
    fun `value 折成一行并截到四十字`() {
        val long = "夜里\n散步" + "很".repeat(60)

        val value = PersonaEvolution.sanitize(listOf(add(Field.TRAITS, long)), Current()).single().value

        assertEquals(PersonaEvolution.VALUE_MAX_CHARS, value.length)
        assertFalse("value 里不能有换行：$value", value.contains("\n"))
        assertTrue("value 应当从「夜里 散步」开始：$value", value.startsWith("夜里 散步"))
    }

    @Test
    fun `输出先 add 后 remove 且同分保持原顺序`() {
        val changes = listOf(
            remove(Field.TRAITS, "慢热", 0.9),
            add(Field.LIKES, "甲", 0.9),
            add(Field.BEHAVIORS, "乙", 0.9)
        )

        val kept = PersonaEvolution.sanitize(changes, current)

        assertEquals(listOf(Op.ADD, Op.ADD, Op.REMOVE), kept.map { it.op })
        assertEquals(listOf("甲", "乙", "慢热"), kept.map { it.value })
    }

    // ---------------------------------------------------------------- 应用

    @Test
    fun `apply 增删列表保持顺序`() {
        val updated = PersonaEvolution.apply(
            current,
            listOf(
                add(Field.TRAITS, "爱开玩笑"),
                remove(Field.LIKES, "夜里散步")
            )
        )

        assertEquals(listOf("慢热", "嘴硬心软", "爱开玩笑"), updated.traits)
        assertTrue(updated.likes.isEmpty())
        assertEquals(current.behaviors, updated.behaviors)
        assertEquals(current.quirks, updated.quirks)
    }

    @Test
    fun `quirk 的 add 追加一句且重复添加无效`() {
        val added = PersonaEvolution.apply(current, listOf(add(Field.QUIRKS, "喜欢在句尾加省略号")))
        assertEquals("不说「加油」。喜欢用省略号\n喜欢在句尾加省略号", added.quirks)

        // 再 add 同一句：幂等
        val again = PersonaEvolution.apply(added, listOf(add(Field.QUIRKS, "喜欢在句尾加省略号")))
        assertEquals(added.quirks, again.quirks)

        // 空文本时直接就是那一句
        val blank = PersonaEvolution.apply(Current(), listOf(add(Field.QUIRKS, "只有一句")))
        assertEquals("只有一句", blank.quirks)
    }

    @Test
    fun `quirk 的 remove 删掉整句或整行`() {
        // 同一行多句：只删命中的那一句
        val twoSentences = PersonaEvolution.apply(
            Current(quirks = "不说「加油」。喜欢用省略号"),
            listOf(remove(Field.QUIRKS, "加油"))
        )
        assertEquals("喜欢用省略号", twoSentences.quirks)

        // 多行：命中的整行被删掉
        val multiLine = PersonaEvolution.apply(
            Current(quirks = "不说「加油」。\n喜欢用省略号\n很少用表情"),
            listOf(remove(Field.QUIRKS, "省略号"))
        )
        assertEquals("不说「加油」。\n很少用表情", multiLine.quirks)

        // 删不存在的片段：原样返回
        val untouched = PersonaEvolution.apply(
            Current(quirks = "不说「加油」。"),
            listOf(remove(Field.QUIRKS, "喝酒"))
        )
        assertEquals("不说「加油」。", untouched.quirks)
    }

    @Test
    fun `apply 不会超过 PersonaProfile 的上限`() {
        val full = Current(
            traits = (1..PersonaProfile.MAX_TRAITS).map { "特质$it" },
            likes = (1..PersonaProfile.MAX_LIKES).map { "喜欢$it" },
            behaviors = (1..PersonaProfile.MAX_BEHAVIORS).map { "习惯$it" }
        )

        val updated = PersonaEvolution.apply(
            full,
            listOf(
                add(Field.TRAITS, "新特质"),
                add(Field.LIKES, "新喜欢"),
                add(Field.BEHAVIORS, "新习惯")
            )
        )

        assertEquals(PersonaProfile.MAX_TRAITS, updated.traits.size)
        assertEquals(PersonaProfile.MAX_LIKES, updated.likes.size)
        assertEquals(PersonaProfile.MAX_BEHAVIORS, updated.behaviors.size)
        assertFalse(updated.traits.contains("新特质"))
        assertFalse(updated.likes.contains("新喜欢"))
        assertFalse(updated.behaviors.contains("新习惯"))
    }

    @Test
    fun `quirks 超长时丢掉新值而不是截断旧值`() {
        val nearLimit = Current(quirks = "设".repeat(PersonaProfile.MAX_QUIRKS - 5))

        val updated = PersonaEvolution.apply(nearLimit, listOf(add(Field.QUIRKS, "这一句放不下")))

        assertEquals(nearLimit.quirks, updated.quirks)
        assertTrue(updated.quirks.length <= PersonaProfile.MAX_QUIRKS)

        // 还放得下时正常追加
        val roomLeft = Current(quirks = "设".repeat(PersonaProfile.MAX_QUIRKS - 5))
        val added = PersonaEvolution.apply(roomLeft, listOf(add(Field.QUIRKS, "五个字")))
        assertEquals(roomLeft.quirks + "\n五个字", added.quirks)
    }

    @Test
    fun `apply 对已有值去重`() {
        val dirty = Current(traits = listOf("慢热", "慢热", " 嘴硬心软 "))

        val updated = PersonaEvolution.apply(dirty, listOf(add(Field.TRAITS, "慢热")))

        assertEquals(listOf("慢热", "嘴硬心软"), updated.traits)
    }

    // ---------------------------------------------------------------- 落库往返

    @Test
    fun `补丁 JSON 往返一致`() {
        val changes = listOf(
            Change(Field.TRAITS, Op.ADD, "嘴硬心软", "最近都先顶一句再软下来", 0.8),
            Change(Field.QUIRKS, Op.REMOVE, "喜欢用省略号", "这阵子少用了", 0.7)
        )

        val encoded = PersonaEvolution.encodePatch(changes)

        assertTrue(encoded.startsWith("""{"changes":["""))
        assertTrue(encoded.contains(""""field":"traits""""))
        assertTrue(encoded.contains(""""op":"add""""))
        // 落库形状只保留 field/op/value/reason，不写 confidence
        assertFalse(encoded.contains("confidence"))

        val decoded = PersonaEvolution.decodePatch(encoded)
        assertEquals(changes.map { Triple(it.field, it.op, it.value) }, decoded.map { Triple(it.field, it.op, it.value) })
        assertEquals("最近都先顶一句再软下来", decoded[0].reason)
        assertEquals("这阵子少用了", decoded[1].reason)

        // 再编码一次结果相同（幂等）
        assertEquals(encoded, PersonaEvolution.encodePatch(decoded))
        assertEquals("""{"changes":[]}""", PersonaEvolution.encodePatch(emptyList()))

        // 坏补丁不抛、返回空
        listOf("", "坏数据", "{}", """{"changes":"[]"}""").forEach { bad ->
            assertTrue("补丁 [$bad] 应当解码为空", PersonaEvolution.decodePatch(bad).isEmpty())
        }
    }

    @Test
    fun `isEvolutionPatch 认补丁不认人设卡`() {
        assertTrue(PersonaEvolution.isEvolutionPatch("""{"changes":[{"field":"traits","op":"add","value":"慢热"}]}"""))
        assertTrue(PersonaEvolution.isEvolutionPatch("""{"changes":[]}"""))

        assertFalse(PersonaEvolution.isEvolutionPatch("""{"identity":{"name":"林深"},"traits":["慢热"]}"""))
        assertFalse(PersonaEvolution.isEvolutionPatch("""{"changes":[],"voice":{"pace":"慢"}}"""))
        assertFalse(PersonaEvolution.isEvolutionPatch("""{"changes":[],"traits":["慢热"]}"""))
        assertFalse(PersonaEvolution.isEvolutionPatch("""{"changes":"[]"}"""))
        assertFalse(PersonaEvolution.isEvolutionPatch("坏数据"))
        assertFalse(PersonaEvolution.isEvolutionPatch(""))
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun `summary 是一行中文摘要`() {
        val text = PersonaEvolution.summary(
            listOf(
                add(Field.TRAITS, "嘴硬心软"),
                remove(Field.LIKES, "夜里散步")
            )
        )

        assertEquals("性格 +嘴硬心软；喜欢的事 \u2212夜里散步", text)
        assertEquals("", PersonaEvolution.summary(emptyList()))
        assertEquals("", PersonaEvolution.summary(listOf(add(Field.TRAITS, "   "))))
    }

    @Test
    fun `systemPrompt 写清白名单与硬约束`() {
        val prompt = PersonaEvolution.systemPrompt()

        listOf("traits", "likes", "behaviors", "quirks", "0.6", "40", "add", "remove").forEach { token ->
            assertTrue("提示词里缺少 $token", prompt.contains(token))
        }
        assertTrue("要写明最多 3 条", prompt.contains("最多 3 条"))
        assertTrue("反例里要有「温柔」", prompt.contains("温柔"))
        assertTrue("反例里要有「争吵」", prompt.contains("争吵"))
        assertTrue("要要求只输出 JSON", prompt.contains("只输出"))
        assertTrue("提示词不该过长：${prompt.length}", prompt.length < 2000)
    }

    @Test
    fun `userPrompt 含人格名与摘要`() {
        val summaryText = "最近一周他话少了一些，聊过两次加班。"
        val prompt = PersonaEvolution.userPrompt("林深", current, summaryText)

        assertTrue(prompt.contains("林深"))
        assertTrue(prompt.contains("慢热"))
        assertTrue(prompt.contains("夜里散步"))
        assertTrue(prompt.contains("先听完再给建议"))
        assertTrue(prompt.contains("不说「加油」"))
        assertTrue(prompt.contains("最近一周他话少了一些"))
        assertTrue(prompt.contains("traits"))
        assertTrue(prompt.contains("现在时间"))
    }

    @Test
    fun `userPrompt 对空人格与空摘要给占位`() {
        val prompt = PersonaEvolution.userPrompt("  ", Current(), "  ")

        assertTrue(prompt.contains("联系人：他"))
        assertTrue(prompt.contains("（暂无）"))
        assertTrue(prompt.contains("（没有可用的最近对话）"))
        // 至少把一个 JSON 示例给模型，避免它自由发挥
        assertTrue(prompt.contains("changes"))
        assertTrue(prompt.contains("\"changes\":[]") || prompt.contains("{\"changes\":[]}"))
    }
}
