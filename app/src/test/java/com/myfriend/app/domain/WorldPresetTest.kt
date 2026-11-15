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
        assertTrue("要有 # 你所在的世界 标题", text.startsWith("# 你所在的世界"))
        assertTrue(text.contains("当代，中国沿海新一线城市，2026 年"))
        assertTrue("日程要带上时间与在做什么", text.contains("工作日 09:00-18:30 在事务所画图、审图"))
        assertTrue("关系要带注释", text.contains("母亲（母亲）：每周三晚打电话"))
        assertTrue("义务要带前缀", text.contains("- 必须：按节点交图"))
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
    fun `worldName 为空表示没有世界预设`() {
        assertTrue(WorldPreset(era = "某年", description = "某地").renderForPrompt().isEmpty())
        assertTrue(WorldPreset().isEmpty())
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
