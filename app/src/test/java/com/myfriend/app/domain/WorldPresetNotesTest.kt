package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `notes`：世界设定的**兜底自由文本**（`spec/persona-guidance/README.md` §2.1）。
 *
 * 结构化字段（日程/关系/义务）表达不了的东西都写这里：城市气质、行业黑话、家庭旧事、忌讳。
 * 三条契约：
 * 1. 缺这个字段的老数据读出来是空串，不是异常（备份 v5 里 worldJson 是旧 JSON）；
 * 2. 写进 JSON、读回来一模一样（备份与两端同步靠它）；
 * 3. 渲染成「其他背景：…」附在世界段末尾，最多 1200 字。
 */
class WorldPresetNotesTest {

    @Test
    fun `缺 notes 字段按空串处理`() {
        val world = WorldPresetCodec.decode("""{"worldName":"某城","era":"当代"}""")
            ?: error("老数据应该还能读")
        assertEquals("", world.notes)
        // 只有 notes 有内容时，空世界的判定也要认它。
        assertTrue(WorldPreset().isEmpty())
        assertFalse(WorldPreset(notes = "一条背景").isEmpty())
    }

    @Test
    fun `notes 写进 JSON 再读回来完全一致`() {
        val original = WorldPreset(
            worldName = "临港新城·云筑建筑事务所",
            era = "当代",
            description = "赶图",
            notes = "城市常年阴雨。\n甲方叫「东家」。\n忌讳：别提父亲的病。"
        )
        val encoded = WorldPresetCodec.encode(original)
        assertTrue("JSON 里必须有 notes 键", encoded.contains("\"notes\""))
        assertEquals(original, WorldPresetCodec.decode(encoded))
    }

    @Test
    fun `宽松解析保留 notes 全文不截断`() {
        // 0.12 起解析/存储不再截断（只渲染时截 1200），与 iOS 的规范化哈希保持一致。
        val long = "字".repeat(2000)
        val decoded = WorldPresetCodec.decode("""{"worldName":"某城","notes":"$long"}""")
            ?: error("读不出来")
        assertEquals(2000, decoded.notes.length)
        // 渲染仍按 1200 截断（提示词体积），但存储不截断。
        val rendered = decoded.renderForPrompt()
        assertTrue(rendered.contains("字".repeat(WorldPreset.MAX_NOTES_CHARS)))
        assertFalse(rendered.contains("字".repeat(WorldPreset.MAX_NOTES_CHARS + 1)))
    }

    @Test
    fun `渲染成其他背景并附在世界段末尾`() {
        val world = WorldPreset(
            worldName = "临港新城·云筑建筑事务所",
            era = "当代",
            description = "赶图",
            schedule = listOf(WorldScheduleEntry("工作日", "09:00", "18:30", "画图")),
            obligations = listOf("按节点交图"),
            notes = "城市常年阴雨。"
        )
        val text = world.renderForPrompt()
        assertTrue(text.contains("其他背景：城市常年阴雨。"))
        assertTrue(
            "其他背景要在日程与义务之后",
            text.indexOf("其他背景：") > text.indexOf("- 必须：按节点交图")
        )
    }

    @Test
    fun `没有 notes 时不会渲染出一个空的背景行`() {
        val text = WorldPreset(worldName = "某城", era = "当代").renderForPrompt()
        assertFalse(text.contains("其他背景"))
    }

    @Test
    fun `渲染时超过 1200 字只带前 1200 字`() {
        val long = "字".repeat(WorldPreset.MAX_NOTES_CHARS + 400)
        val text = WorldPreset(worldName = "某城", notes = long).renderForPrompt()
        val line = text.lineSequence().first { it.startsWith("其他背景：") }
        assertEquals("其他背景：".length + WorldPreset.MAX_NOTES_CHARS, line.length)
    }

    @Test
    fun `备份 v5 原样搬运 worldJson 所以 notes 自动带上`() {
        // 备份把 worldJson 当成一整个字符串搬运（BackupPersona/BackupPreset.worldJson），
        // 所以 JSON 里多一个 notes 键不需要升备份版本。
        val encoded = WorldPresetCodec.encode(
            WorldPreset(worldName = "某城", era = "当代", notes = "城市常年阴雨。")
        )
        assertEquals("城市常年阴雨。", WorldPresetCodec.decode(encoded)?.notes)

        // 旧备份（写这个字段之前）没有 notes 键：读出来是空串，不是异常、也不是 null 世界。
        val legacy = """
            {"worldName":"某城","era":"当代","description":"赶图",
             "schedule":[],"relationships":[],"obligations":[]}
        """.trimIndent()
        val decoded = WorldPresetCodec.decode(legacy) ?: error("旧备份的世界读不出来")
        assertEquals("", decoded.notes)
        assertEquals("某城", decoded.worldName)
    }

    @Test
    fun `AI 兜底世界保留角色名 并把简介渲染成其他背景`() {
        // v14 的兜底世界：worldName = 角色名，notes = 角色简介原文（其余字段空）。
        // 名字不进提示词（脚手架的 world 段没有它的位置），但它必须作为数据留在世界里；
        // 简介则真的会被模型看到——这就是「兜底」的意义。
        val fallback = WorldPreset(worldName = "林深", notes = "建筑事务所项目负责人，最近在赶工。")
        assertEquals("林深", fallback.worldName)
        assertFalse(fallback.isEmpty())
        assertTrue(fallback.renderForPrompt().contains("其他背景：建筑事务所项目负责人，最近在赶工。"))
    }
}
