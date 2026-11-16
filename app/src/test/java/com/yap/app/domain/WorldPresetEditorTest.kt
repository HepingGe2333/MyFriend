package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 世界预设**可视化编辑器**的状态层（`spec/persona-guidance/README.md` §2.3）。
 *
 * 规范要求换掉 JSON 文本框，但界面本身在这台机器上没法渲染验证（没有模拟器）。
 * 所以把「增删行、上下移动、时间校验、跨午夜、字数限制、保存校验」全部压进
 * [WorldEditorState] 这个纯状态里，逐条断言；Compose 侧只剩「把状态画出来 + 把点击折回状态」。
 *
 * 另外两条契约：
 * 1. 空状态示例必须与 `spec/persona-guidance/cases/world-preset-linshen.json` 的 `expect` 一致
 *    （示例是产品行为，不能随手改字）；
 * 2. 编辑器 → JSON → 编辑器 往返不丢字段（含 `notes` 与关系亲密度）。
 */
class WorldPresetEditorTest {

    private fun findRepoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile &&
                File(dir, "spec/persona-guidance/cases").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        error("找不到仓库根目录（从 $start 向上）：需要同时有 settings.gradle.kts 与 spec/persona-guidance/cases")
    }

    private fun linshenExpect(): JsonObject {
        val file = File(findRepoRoot(), "spec/persona-guidance/cases/world-preset-linshen.json")
        assertTrue("案例文件不存在：${file.absolutePath}", file.isFile)
        val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
        return root["expect"] as JsonObject
    }

    private fun JsonObject.str(key: String): String = (this[key] as JsonPrimitive).content

    // ------------------------------------------------------------- 空状态示例

    @Test
    fun `空状态示例与规范案例逐字段一致`() {
        val expect = linshenExpect()
        val example = WorldEditorState.example()
        assertEquals(expect.str("worldName"), example.worldName)
        assertEquals(expect.str("era"), example.era)
        assertEquals(expect.str("description"), example.description)

        val expectedSchedule = expect["schedule"] as JsonArray
        assertEquals(expectedSchedule.size, example.schedule.size)
        expectedSchedule.forEachIndexed { index, element ->
            val obj = element as JsonObject
            val row = example.schedule[index]
            assertEquals("第 $index 行 day", obj.str("day"), row.day)
            assertEquals("第 $index 行 start", obj.str("start"), row.start)
            assertEquals("第 $index 行 end", obj.str("end"), row.end)
            assertEquals("第 $index 行 activity", obj.str("activity"), row.activity)
        }
        val expectedRelationships = expect["relationships"] as JsonArray
        assertEquals(expectedRelationships.size, example.relationships.size)
        expectedRelationships.forEachIndexed { index, element ->
            val obj = element as JsonObject
            val row = example.relationships[index]
            assertEquals(obj.str("name"), row.name)
            assertEquals(obj.str("relation"), row.relation)
            assertEquals(obj.str("note"), row.note)
        }
        val expectedObligations = (expect["obligations"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(expectedObligations, example.obligations)
        assertFalse("示例不能是空状态", example.isEmpty())
    }

    @Test
    fun `空状态示例自身能通过校验`() {
        assertEquals(emptyList<WorldEditorIssue>(), WorldEditorState.example().issues())
    }

    // --------------------------------------------------------------- 增删移动

    @Test
    fun `日程可以加行 改行 删行 上下移`() {
        var state = WorldEditorState()
        state = state.addSchedule()
        state = state.addSchedule()
        assertEquals(2, state.schedule.size)
        assertEquals("工作日", state.schedule[0].day)

        state = state.updateSchedule(0) { it.copy(day = "周末", activity = "补觉") }
        assertEquals("周末", state.schedule[0].day)
        assertEquals("补觉", state.schedule[0].activity)

        state = state.moveSchedule(0, 1)
        assertEquals("补觉", state.schedule[1].activity)
        // 再把「补觉」那行移回最上面：移动的是它自己的下标（1），不是 0。
        state = state.moveSchedule(1, -1)
        assertEquals(0, state.schedule.indexOfFirst { it.activity == "补觉" })

        // 现在顺序是 [补觉, 空日程]；删掉第一行后只剩那条空日程（用户自己还没填）。
        state = state.removeSchedule(0)
        assertEquals(1, state.schedule.size)
        assertEquals("", state.schedule.single().activity)
    }

    @Test
    fun `移到边界时原样返回 不会把行弄丢`() {
        val state = WorldEditorState(
            schedule = listOf(WorldScheduleEntry("每天", "09:00", "10:00", "a"))
        )
        assertEquals(state, state.moveSchedule(0, -1))
        assertEquals(state, state.moveSchedule(0, 1))
        assertEquals(state, state.removeSchedule(5))
        assertEquals(state, state.moveSchedule(9, 1))
    }

    @Test
    fun `关系可以加行 改行 删行 上下移`() {
        var state = WorldEditorState()
        state = state.addRelationship()
        state = state.addRelationship()
        state = state.updateRelationship(0) { it.copy(name = "母亲", relation = "家人", note = "每周三电话") }
        assertEquals(2, state.relationships.size)
        assertEquals("母亲", state.relationships[0].name)
        state = state.moveRelationship(0, 1)
        assertEquals("母亲", state.relationships[1].name)
        state = state.removeRelationship(1)
        assertEquals(1, state.relationships.size)
        assertEquals("", state.relationships.single().name)
    }

    @Test
    fun `义务一行一条 可增删改`() {
        var state = WorldEditorState()
        state = state.addObligation()
        state = state.addObligation()
        state = state.updateObligation(0, "按节点交图")
        state = state.updateObligation(1, "协调各专业")
        assertEquals(listOf("按节点交图", "协调各专业"), state.obligations)
        state = state.removeObligation(0)
        assertEquals(listOf("协调各专业"), state.obligations)
        assertEquals(state, state.removeObligation(9))
    }

    @Test
    fun `其他背景按 1200 字截断 并给出超长信号`() {
        val long = "字".repeat(WorldPreset.MAX_NOTES_CHARS + 50)
        val state = WorldEditorState().withNotes(long)
        assertEquals(WorldPreset.MAX_NOTES_CHARS, state.notes.length)
        assertFalse("输入时就该截断，不该等保存再截", state.notesTooLong)

        val overLimit = WorldEditorState(notes = long)
        assertTrue(overLimit.notesTooLong)
        assertTrue(overLimit.issues().any { it.field == WorldEditorField.NOTES })
    }

    // ------------------------------------------------------------------ 校验

    @Test
    fun `时间写错时报错并定位到具体行`() {
        val state = WorldEditorState(
            schedule = listOf(
                WorldScheduleEntry("工作日", "09:00", "18:30", "画图"),
                WorldScheduleEntry("工作日", "25:00", "18:30", "写错的小时")
            )
        )
        val issues = state.issues()
        assertEquals(1, issues.size)
        assertEquals(WorldEditorField.SCHEDULE_START, issues.single().field)
        assertEquals(1, issues.single().rowIndex)
    }

    @Test
    fun `空日程行 空关系行 都会报错`() {
        val state = WorldEditorState(
            schedule = listOf(WorldScheduleEntry("工作日", "09:00", "18:00", "  ")),
            relationships = listOf(WorldRelationship(name = "", relation = "", note = "只有备注"))
        )
        val issues = state.issues()
        assertTrue(issues.any { it.field == WorldEditorField.SCHEDULE_ACTIVITY && it.rowIndex == 0 })
        assertTrue(issues.any { it.field == WorldEditorField.RELATION_NAME && it.rowIndex == 0 })
    }

    @Test
    fun `跨午夜是合法的 不报错`() {
        val state = WorldEditorState(
            schedule = listOf(WorldScheduleEntry("每天", "22:00", "02:00", "值夜班"))
        )
        assertEquals(emptyList<WorldEditorIssue>(), state.issues())
        assertTrue(state.schedule.single().crossesMidnight)
    }

    @Test
    fun `亲密度只接受 1 到 5`() {
        val ok = WorldEditorState(
            relationships = listOf(WorldRelationship("母亲", "家人", "打电话", intimacy = 5))
        )
        assertEquals(emptyList<WorldEditorIssue>(), ok.issues())
        val bad = ok.copy(relationships = listOf(ok.relationships.single().copy(intimacy = 9)))
        assertTrue(bad.issues().any { it.field == WorldEditorField.RELATION_INTIMACY })
    }

    @Test
    fun `day 不在白名单时报错`() {
        val state = WorldEditorState(
            schedule = listOf(WorldScheduleEntry("周八", "09:00", "10:00", "不存在"))
        )
        assertTrue(state.issues().any { it.field == WorldEditorField.SCHEDULE_DAY })
    }

    // ------------------------------------------------------- 往返与落库映射

    @Test
    fun `编辑器状态经 JSON 往返后逐字段相同`() {
        val state = WorldEditorState.example().copy(
            notes = "城市气质：常年阴雨。\n行业黑话：甲方叫「东家」。",
            relationships = WorldEditorState.example().relationships +
                WorldRelationship("母亲", "家人", "周三电话", intimacy = 4)
        )
        val preset = state.toPreset()
        val encoded = WorldPresetCodec.encode(preset)
        val decoded = WorldPresetCodec.decode(encoded) ?: error("往返丢失：$encoded")
        assertEquals(preset, decoded)
        assertEquals(WorldEditorState.from(decoded), state.copy(obligations = state.obligations.map { it.trim() }))
    }

    @Test
    fun `空关系和空义务在落库时被过滤 但编辑器里不会静默丢行`() {
        val state = WorldEditorState(
            obligations = listOf(" 交图 ", "", "   "),
            relationships = listOf(WorldRelationship("", "", ""), WorldRelationship("老周", "同事", ""))
        )
        val preset = state.toPreset()
        assertEquals(listOf("交图"), preset.obligations)
        assertEquals(listOf("老周"), preset.relationships.map { it.name })
        // 编辑器本身不改：那三行还在，用户自己决定删不删。
        assertEquals(3, state.obligations.size)
        assertEquals(2, state.relationships.size)
    }

    @Test
    fun `worldName 为空的世界不落库 但不是空状态`() {
        val state = WorldEditorState(description = "只有处境")
        val preset = state.toPreset()
        assertTrue(preset.isEmpty().not())
        assertEquals("", preset.worldName)
    }

    // ------------------------------------------------------------ AI 补全合并

    @Test
    fun `AI 补全只填空白项 不覆盖用户已经写的东西`() {
        val mine = WorldEditorState(
            worldName = "我自己写的世界",
            era = "",
            schedule = listOf(WorldScheduleEntry("每天", "09:00", "10:00", "我写的日程")),
            notes = ""
        )
        val generated = WorldEditorState.example()
        val merged = mine.mergedWith(generated)
        assertEquals("我自己写的世界", merged.worldName)
        assertEquals("用户写的日程不能被冲掉", "我写的日程", merged.schedule.single().activity)
        assertEquals("空着的 era 应该被补上", generated.era, merged.era)
        assertEquals("空着的 notes 应该被补上", generated.notes, merged.notes)
        assertEquals(generated.relationships, merged.relationships)
        assertNotEquals(generated.worldName, merged.worldName)
    }

    // -------------------------------------------------------------- 状态示例

    @Test
    fun `示例之外的空状态是空的`() {
        assertTrue(WorldEditorState().isEmpty())
        assertTrue(WorldEditorState.from(null).isEmpty())
        assertEquals(WorldEditorState(), WorldEditorState.from(WorldPreset()))
    }
}
