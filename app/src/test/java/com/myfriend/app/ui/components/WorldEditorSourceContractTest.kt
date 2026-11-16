package com.myfriend.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 世界设定编辑器的**源码契约**（`spec/persona-guidance/README.md` §2.3）。
 *
 * 这一组断言补的是「UI 渲染测试跑不了」的那个缺口里最要紧的一半：规范要的不是
 * 「某个界面看起来像编辑器」，而是**产品里不再存在 JSON 文本框**、编辑器**是预设页/人格页里的一个区块**、
 * 保存前真的走结构校验。这些东西在源码层面就能钉住：
 *
 * 1. JSON 文本框与它的文案/占位符/错误提示必须消失；
 * 2. 可视化控件（下拉、时间选择器、增删/上下移、字数提示）必须存在；
 * 3. 两个编辑页都必须把世界卡**嵌在自己页面里**（不是独立路由）；
 * 4. 两个 ViewModel 保存前都必须调 `world.issues()` 并把问题定位到行。
 *
 * 真正「像素对不对、点击有没有反应」仍然需要模拟器，这里不假装测过。
 */
class WorldEditorSourceContractTest {

    private fun moduleRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "src/main/java/com/myfriend/app").isDirectory) return dir
            if (File(dir, "app/src/main/java/com/myfriend/app").isDirectory) return File(dir, "app")
            dir = dir.parentFile
        }
        error("找不到 app 模块根目录（从 $start 向上）：需要包含 src/main/java/com/myfriend/app")
    }

    private fun repoFile(relative: String): File {
        val file = File(moduleRoot(), relative)
        assertTrue("文件不存在：${file.absolutePath}", file.isFile)
        return file
    }

    private fun read(relative: String): String = repoFile(relative).readText(Charsets.UTF_8)

    @Test
    fun `世界设定不再有 JSON 文本框`() {
        val stringsZh = read("src/main/res/values/strings.xml")
        val stringsEn = read("src/main/res/values-en/strings.xml")
        listOf(
            "guidance_world_schedule_label",
            "guidance_world_schedule_placeholder",
            "guidance_world_relationships_label",
            "guidance_world_relationships_placeholder",
            "guidance_world_schedule_invalid",
            "guidance_world_relationships_invalid"
        ).forEach { key ->
            assertFalse("中文资源里不该再有 JSON 文本框中用到的 $key", stringsZh.contains("name=\"$key\""))
            assertFalse("英文资源里不该再有 JSON 文本框中用到的 $key", stringsEn.contains("name=\"$key\""))
        }
        // 界面层也不该再传「日程 JSON / 关系 JSON」这种状态。
        val uiSources = File(moduleRoot(), "src/main/java/com/myfriend/app/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
        uiSources.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            assertFalse("${file.name} 里还在用 scheduleJson", text.contains("scheduleJson"))
            assertFalse("${file.name} 里还在用 relationshipsJson", text.contains("relationshipsJson"))
        }
    }

    @Test
    fun `世界卡是行式可视化控件 而不是一个多行文本框`() {
        val editor = read("src/main/java/com/myfriend/app/ui/components/WorldPresetEditor.kt")
        listOf(
            "TimePickerDialog" to "时间选择器",
            "DropdownMenu" to "day / 关系下拉",
            "WorldEditorState.DAY_OPTIONS" to "每天的取值来自状态层白名单",
            "WorldEditorState.RELATION_OPTIONS" to "关系下拉的常用值",
            "guidance_world_add_schedule" to "日程可加行",
            "guidance_world_add_relationship" to "关系可加行",
            "guidance_world_add_obligation" to "义务可加行",
            "guidance_world_move_up" to "可上下移",
            "guidance_world_move_down" to "可上下移",
            "guidance_world_delete_row" to "可删除",
            "guidance_world_next_day" to "跨午夜显示「次日」",
            "guidance_world_notes_counter" to "其他背景有字数提示",
            "guidance_world_example_hint" to "空状态给示例",
            "issues" to "把行级校验结果画成错误态"
        ).forEach { (needle, why) ->
            assertTrue("世界卡缺少「$why」（找不到 $needle）", editor.contains(needle))
        }
        // 旧卡（GuidanceEditors.kt）不能再定义世界卡：一个控件只能有一个实现。
        val legacy = read("src/main/java/com/myfriend/app/ui/components/GuidanceEditors.kt")
        assertFalse("GuidanceEditors.kt 里不该再定义 WorldPresetCard", legacy.contains("fun WorldPresetCard("))
    }

    @Test
    fun `世界编辑是预设页与人格页里的一个区块 不是独立页面`() {
        val presetScreen = read("src/main/java/com/myfriend/app/ui/screens/preset/PresetEditorScreen.kt")
        assertTrue("预设编辑页里必须嵌着世界卡", presetScreen.contains("WorldPresetCard("))
        assertTrue("预设页要把世界状态传进去", presetScreen.contains("state = state.form.world"))
        assertTrue("空状态示例来自状态层（不是另写一份）", presetScreen.contains("WorldEditorState.example()"))

        val personaScreen = read("src/main/java/com/myfriend/app/ui/screens/persona/PersonaEditorScreen.kt")
        assertTrue("人格编辑页的「世界与话风」区块里必须有世界卡", personaScreen.contains("WorldPresetCard("))
        assertTrue(personaScreen.contains("state = form.world"))

        val nav = read("src/main/java/com/myfriend/app/navigation/MyFriendNavHost.kt")
        assertFalse(
            "不允许出现独立的世界编辑路由（规范 §2.3：整合在人格预设界面内）",
            nav.contains("world")
        )
    }

    @Test
    fun `保存前必须走结构校验并把错误定位到具体行`() {
        listOf(
            "src/main/java/com/myfriend/app/ui/screens/preset/PresetEditorViewModel.kt",
            "src/main/java/com/myfriend/app/ui/screens/persona/PersonaEditorViewModel.kt"
        ).forEach { path ->
            val vm = read(path)
            assertTrue("$path 保存前必须调 world.issues()", vm.contains("form.world.issues()"))
            assertTrue("$path 必须用 WorldEditorErrors 生成可定位的错误文案", vm.contains("WorldEditorErrors.message"))
            assertTrue("$path 必须把编辑器状态落成 WorldPreset", vm.contains("form.world.toPreset()"))
        }
        val errors = read("src/main/java/com/myfriend/app/ui/common/WorldEditorErrors.kt")
        assertTrue("错误文案要能带行号", errors.contains("rowIndex + 1"))
        assertTrue(errors.contains("SCHEDULE_START"))
        assertTrue(errors.contains("RELATION_INTIMACY"))
        assertTrue(errors.contains("NOTES"))
    }

    @Test
    fun `notes 字段在编辑器里受 1200 字约束且渲染进世界段`() {
        val state = read("src/main/java/com/myfriend/app/domain/WorldPresetEditor.kt")
        assertTrue("输入时截断用 WorldPreset.MAX_NOTES_CHARS", state.contains("take(WorldPreset.MAX_NOTES_CHARS)"))
        val world = read("src/main/java/com/myfriend/app/domain/WorldPreset.kt")
        assertTrue("世界段末尾要渲染「其他背景：」", world.contains("其他背景："))
        assertTrue("渲染也要截到 1200 字", world.contains("notes.trim().take(MAX_NOTES_CHARS)"))
    }
}
