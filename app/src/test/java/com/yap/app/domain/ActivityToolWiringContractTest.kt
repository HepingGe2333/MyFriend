package com.yap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `set_activity` 的接线契约（规范 §4）。
 *
 * 纯逻辑（夹取 / 编解码 / 提示词渲染）在上面的测试里；这里只钉「真的接上了」：
 * 工具注册了、参数 schema 对了、执行器写的是 `personas.activityJson`、
 * 心跳在 BUSY 时跳过、LIMITED 时降权。这类问题最典型的形态是「单测全绿，功能没接」。
 */
class ActivityToolWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $relative（工作目录=${File("").absolutePath}）")
    }

    @Test
    fun `工具目录里注册了 set_activity 且参数与规范一致`() {
        val spec = ToolCatalog.byName(ToolCatalog.SET_ACTIVITY) ?: error("没有注册 set_activity")
        listOf("kind", "activity", "minutes", "availability").forEach { parameter ->
            assertTrue("set_activity 参数缺少 $parameter：${spec.parametersJson}", spec.parametersJson.contains("\"$parameter\""))
        }
        assertTrue("activity 必填", spec.parametersJson.contains("\"required\": [\"activity\"]"))
        assertTrue("minutes 的边界要是 15-480", spec.parametersJson.contains("\"minimum\": 15"))
        assertTrue(spec.parametersJson.contains("\"maximum\": 480"))
        listOf("WORK", "COMMUTE", "MEAL", "SOCIAL", "CHORE", "STUDY", "REST", "OTHER").forEach { kind ->
            assertTrue("kind 枚举缺少 $kind", spec.parametersJson.contains("\"$kind\""))
        }
        listOf("BUSY", "LIMITED", "FREE").forEach { availability ->
            assertTrue("availability 枚举缺少 $availability", spec.parametersJson.contains("\"$availability\""))
        }
    }

    @Test
    fun `工具描述写清了与安静时段的区别`() {
        val spec = ToolCatalog.byName(ToolCatalog.SET_ACTIVITY)!!
        assertTrue("要说明它管的是「我在做事」", spec.description.contains("我在做事"))
        assertTrue("要明确和 set_quiet_hours / 安静时段不是一回事", spec.description.contains("set_quiet_hours"))
        assertTrue(spec.description.contains("安静时段"))
        val prompt = ToolCatalog.capabilityPrompt()
        assertTrue("能力提示词里要提到 set_activity", prompt.contains(ToolCatalog.SET_ACTIVITY))
    }

    @Test
    fun `执行器把活动写进 activityJson 而不是别的字段`() {
        val text = source("src/main/java/com/yap/app/data/repo/ToolExecutor.kt")
        assertTrue(text.contains("\"set_activity\" -> setActivity("))
        assertTrue("要写当前人格的 activityJson", text.contains("activityJson = PersonaActivityCodec.encode(activity)"))
        assertTrue("参数夹取要过 ToolArgClamp", text.contains("ToolArgClamp.clampActivityMinutes"))
        assertTrue(text.contains("ToolArgClamp.normalizeActivityKind"))
        assertTrue(text.contains("ToolArgClamp.normalizeActivityAvailability"))
        // 活动不是作息：不能顺手改 quietSleepMinutes / quietWakeMinutes。
        val block = text.substring(text.indexOf("private suspend fun setActivity"), text.indexOf("// ---------------------------------------------------------------- 先不回"))
        assertFalse("set_activity 不该写作息字段", block.contains("quietSleepMinutes"))
        assertFalse(block.contains("quietWakeMinutes"))
        assertFalse("set_activity 不该改人设卡", block.contains("personaCardRepository"))
    }

    @Test
    fun `心跳在 BUSY 时跳过 LIMITED 时降权`() {
        val text = source("src/main/java/com/yap/app/data/repo/HeartbeatController.kt")
        assertTrue(text.contains("PersonaActivityCodec.decode(persona.activityJson)"))
        assertTrue(text.contains("ActivityAvailability.BUSY"))
        assertTrue(text.contains("LIMITED_PROACTIVE_FACTOR"))
        // BUSY 的跳过要发生在掷骰子之前：否则「忙」只是概率低，不是不开口。
        val busy = text.indexOf("ActivityAvailability.BUSY")
        val roll = text.indexOf("Heartbeat.shouldFire")
        assertTrue("BUSY 判断要在掷骰子之前", busy in 0 until roll)
    }

    @Test
    fun `提示词与人格展示都读 activityJson`() {
        val chat = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        assertTrue(chat.contains("PersonaActivityCodec.decode(persona.activityJson)"))
        assertTrue(chat.contains("PromptBuilder.system("))
        val cardRepo = source("src/main/java/com/yap/app/data/repo/PersonaCardRepository.kt")
        assertTrue(cardRepo.contains("PromptBuilder.base("))
        val editor = source("src/main/java/com/yap/app/ui/screens/persona/PersonaEditorScreen.kt")
        assertTrue("人格编辑页要展示当前活动", editor.contains("persona_editor_activity_section"))
    }
}
