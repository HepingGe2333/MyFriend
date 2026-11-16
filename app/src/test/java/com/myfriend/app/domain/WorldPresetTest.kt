package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界预设（规范 §2）。
 *
 * 覆盖：样例 JSON 逐字段读出来、渲染成「你所在的世界」段、缺字段 → 空数组、
 * 坏 JSON → null、坏日程条目被跳过、跨午夜被当成合法（次日）、day 白名单。
 */
class WorldPresetTest {

    private val sample = """
        {
          "worldName": "临港新城·云筑建筑事务所",
          "era": "当代，中国沿海新一线城市，2026 年",
          "description": "他带着六人小组负责一个高层住宅项目，加班是常态。",
          "schedule": [
            { "day": "工作日", "start": "09:00", "end": "18:30", "activity": "在事务所画图、审图" },
            { "day": "周三", "start": "20:00", "end": "20:30", "activity": "接母亲电话" }
          ],
          "relationships": [
            { "name": "母亲", "relation": "母亲", "note": "每周三晚打电话" }
          ],
          "obligations": ["按节点交图", "协调各专业"]
        }
    """.trimIndent()

    @Test
    fun `样例 JSON 能逐字段读出来`() {
        val world = WorldPresetCodec.decode(sample) ?: error("样例读不出来")
        assertEquals("临港新城·云筑建筑事务所", world.worldName)
        assertEquals(2, world.schedule.size)
        assertEquals("工作日", world.schedule[0].day)
        assertEquals("接母亲电话", world.schedule[1].activity)
        assertEquals("母亲", world.relationships.single().name)
        assertEquals(listOf("按节点交图", "协调各专业"), world.obligations)
        assertFalse(world.isEmpty())
    }

    @Test
    fun `渲染出规范要求的你所在的世界段`() {
        val text = WorldPresetCodec.decode(sample)!!.renderForPrompt()
        val scaffold = PromptBuilder.Scaffold
        assertTrue("要有 # 你所在的世界 标题", text.startsWith(scaffold.SECTION_WORLD + "\n"))
        assertTrue(
            "世界段第一行是脚手架的 world_name",
            text.startsWith(
                scaffold.SECTION_WORLD + "\n" +
                    scaffold.WORLD_NAME.replace("{worldName}", "临港新城·云筑建筑事务所")
            )
        )
        assertTrue(
            "第二行是脚手架的 world_intro（era。description）",
            text.contains("当代，中国沿海新一线城市，2026 年。他带着六人小组负责一个高层住宅项目，加班是常态。")
        )
        assertTrue("日程要带上时间与在做什么", text.contains("- 工作日 09:00-18:30 在事务所画图、审图"))
        assertTrue("关系要带注释", text.contains("- 母亲（母亲）：每周三晚打电话"))
        assertTrue("义务要带前缀", text.contains("- 必须：按节点交图"))
        assertTrue("结尾是脚手架的 world_hint", text.endsWith(scaffold.WORLD_HINT))
        assertTrue(
            "worldName 非空时用 world_name 渲染，并且只出现这一次",
            text.lines().count { it == scaffold.WORLD_NAME.replace("{worldName}", "临港新城·云筑建筑事务所") } == 1
        )
        assertFalse("没有 notes 就不该写「其他背景」", text.contains("其他背景："))
    }

    @Test
    fun `notes 渲染成其他背景 且最多 1200 字`() {
        val world = WorldPresetCodec.decode(sample)!!.copy(notes = "雨".repeat(1300))
        val text = world.renderForPrompt()
        assertTrue(text.contains("其他背景：" + "雨".repeat(WorldPreset.MAX_NOTES_CHARS)))
        assertFalse(
            "超过 1200 字要截断（不静默丢字段，但也不把整段塞进提示词）",
            text.contains("雨".repeat(WorldPreset.MAX_NOTES_CHARS + 1))
        )
        assertTrue(
            "「其他背景」要在 world_hint 之前",
            text.indexOf("其他背景：") < text.indexOf(PromptBuilder.Scaffold.WORLD_HINT)
        )
    }

    @Test
    fun `intro 缺一半时不留多余的句号`() {
        val eraOnly = WorldPreset(worldName = "某地", era = "2026 年").renderForPrompt().lines()
        assertEquals("只有 era 时写「era。」", "2026 年。", eraOnly[2])
        val descriptionOnly = WorldPreset(worldName = "某地", description = "一个靠海的小城").renderForPrompt().lines()
        assertEquals("只有 description 时直接写描述，不留前导句号", "一个靠海的小城", descriptionOnly[2])
    }

    @Test
    fun `关系的亲密度是可选扩展 填了才渲染`() {
        val world = WorldPreset(
            worldName = "某地",
            relationships = listOf(WorldRelationship("母亲", "母亲", "每周三晚打电话", intimacy = 4))
        )
        assertTrue(world.renderForPrompt().contains("- 母亲（母亲）：每周三晚打电话，亲密度 4/5"))
        val without = WorldPreset(
            worldName = "某地",
            relationships = listOf(WorldRelationship("母亲", "母亲", "每周三晚打电话"))
        )
        assertFalse(without.renderForPrompt().contains("亲密度"))
    }

    @Test
    fun `缺字段当空数组而不是崩掉`() {
        val world = WorldPresetCodec.decode("""{"worldName":"某个城市"}""")!!
        assertEquals("某个城市", world.worldName)
        assertTrue(world.schedule.isEmpty())
        assertTrue(world.relationships.isEmpty())
        assertTrue(world.obligations.isEmpty())
    }

    @Test
    fun `坏 JSON 返回 null 空串返回 null`() {
        assertNull(WorldPresetCodec.decode("not json"))
        assertNull(WorldPresetCodec.decode("""{"worldName":}"""))
        assertNull(WorldPresetCodec.decode("   "))
        assertNull(WorldPresetCodec.decode(null))
        assertTrue(WorldPresetCodec.decodeOrEmpty("bad").isEmpty())
    }

    @Test
    fun `坏日程条目被跳过 不影响其他字段`() {
        val world = WorldPresetCodec.decode(
            """
            {"worldName":"某地",
             "schedule":[
               {"day":"工作日","start":"09:00","end":"18:30","activity":"上班"},
               {"day":"周八","start":"09:00","end":"18:30","activity":"不存在的星期"},
               {"day":"每天","start":"25:00","end":"26:00","activity":"错误时间"},
               {"day":"每天","start":"9:00","end":"10:00","activity":"短写也认"}
             ]}
            """.trimIndent()
        )!!
        assertEquals("坏条目要丢掉", 2, world.schedule.size)
        assertEquals("上班", world.schedule[0].activity)
        assertEquals("短写也认", world.schedule[1].activity)
        assertTrue(world.renderForPrompt().contains("短写也认"))
    }

    @Test
    fun `跨午夜是合法的并按次日渲染`() {
        val entry = WorldScheduleEntry(day = "每天", start = "22:00", end = "02:00", activity = "值夜班")
        assertTrue(entry.isValid)
        assertTrue(entry.crossesMidnight)
        assertTrue(entry.renderLine().contains("（次日）"))
        assertEquals(22 * 60, WorldPreset.minuteOfDay("22:00"))
        assertEquals(2 * 60, WorldPreset.minuteOfDay("02:00"))
    }

    @Test
    fun `day 只认规范里的取值`() {
        listOf("工作日", "周末", "每天", "周一", "周三", "周日").forEach { day ->
            assertTrue(
                "$day 应该是合法 day",
                WorldScheduleEntry(day = day, start = "09:00", end = "10:00", activity = "x").isValid
            )
        }
        assertFalse(
            WorldScheduleEntry(day = "周八", start = "09:00", end = "10:00", activity = "x").isValid
        )
    }

    @Test
    fun `只有全部字段为空才算没有世界`() {
        // §2.1（0.12 规范更新）：**所有字段都为空**才是「没有世界」。
        // 以前 Android 用 worldName 是否为本当开关，会把只填了 era/description/notes 的世界整段丢掉。
        assertTrue("全空才是没有世界", WorldPreset().isEmpty())
        assertTrue(WorldPreset().renderForPrompt().isEmpty())
        assertTrue(
            "只有空白的字段也等于空",
            WorldPreset(worldName = " ", era = "\n", notes = "").isEmpty()
        )

        val noName = WorldPreset(era = "某年", description = "某地")
        assertFalse("worldName 为空但其它字段非空 ≠ 没有世界", noName.isEmpty())
        val text = noName.renderForPrompt()
        assertTrue("仍要渲染世界段标题", text.startsWith(PromptBuilder.Scaffold.SECTION_WORLD + "\n"))
        assertFalse("worldName 为空时省略 world_name 行", text.contains("你所在的地方："))
        assertTrue("era + description 要渲染成 intro", text.contains("某年。某地"))
        assertTrue("结尾仍是 world_hint", text.endsWith(PromptBuilder.Scaffold.WORLD_HINT))

        val notesOnly = WorldPreset(notes = "他怕冷，也怕吵。")
        assertTrue(notesOnly.renderForPrompt().contains("其他背景：他怕冷，也怕吵。"))

        // worldName 非空 → world_name 行；只填 worldName 也算有世界。
        assertEquals(
            "# 你所在的世界\n你所在的地方：某个城市。\n" + PromptBuilder.Scaffold.WORLD_HINT,
            WorldPreset(worldName = "某个城市").renderForPrompt()
        )
        assertEquals("09:30", WorldPreset().normalizedClock("9:30"))
    }

    @Test
    fun `编解码往返保留全部字段`() {
        val original = WorldPresetCodec.decode(sample)!!
        val roundTrip = WorldPresetCodec.decode(WorldPresetCodec.encode(original))!!
        assertEquals(original, roundTrip)
    }

    @Test
    fun `严格解析遇到写错的日程会返回 null 而不是静默丢条目`() {
        val bad = """{"worldName":"某地","schedule":[{"day":"周八","start":"09:00","end":"10:00","activity":"x"}]}"""
        assertNull(WorldPresetCodec.decodeStrict(bad))
        assertEquals(2, WorldPresetCodec.decodeStrict(sample)!!.schedule.size)
        assertNull(WorldPresetCodec.decodeSchedule("""[{"day":"周八"}]"""))
        assertNull(WorldPresetCodec.decodeRelationships("not array"))
    }
}
