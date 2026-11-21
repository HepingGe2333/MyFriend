package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 模型优化开关（0.15，schema v20）。
 *
 * 三件事必须逐条钉住：
 * 1. 默认是 `AUTO`，认不出的值也回落到 `AUTO`（老库/手改数据不能让界面或提示词崩）；
 * 2. `AUTO` 的推断只看 model 名（含 `glm` → GLM，否则 DeepSeek），显式选择优先于推断；
 * 3. `# 工具使用` 段只在 DeepSeek 档出现，GLM 档完全不注入——而且**除了这一段**，
 *    其它提示词段（人设/世界/话风/边界）一个字都不变。
 */
class ModelProfileTest {

    // ---------------------------------------------------------------- 存储与推断

    @Test
    fun `默认是 AUTO 且认不出的值回落到 AUTO`() {
        assertEquals(ModelProfile.AUTO, ModelProfile.fromStored(null))
        assertEquals(ModelProfile.AUTO, ModelProfile.fromStored(""))
        assertEquals(ModelProfile.AUTO, ModelProfile.fromStored("   "))
        assertEquals(ModelProfile.AUTO, ModelProfile.fromStored("CLAUDE"))
        assertEquals(ModelProfile.AUTO, ModelProfile.fromStored("自动"))
        assertEquals("列默认值就是 AUTO", "AUTO", ModelProfile.DEFAULT_STORED)
    }

    @Test
    fun `落库值大小写与空格都能读回来`() {
        assertEquals(ModelProfile.DEEPSEEK, ModelProfile.fromStored("deepseek"))
        assertEquals(ModelProfile.GLM, ModelProfile.fromStored(" glm "))
        assertEquals("DEEPSEEK", ModelProfile.DEEPSEEK.stored)
        assertEquals("AUTO", ModelProfile.AUTO.stored)
    }

    @Test
    fun `AUTO 按 model 名推断`() {
        assertEquals(ModelProfile.GLM, ModelProfile.effective(ModelProfile.AUTO, "glm-4.5"))
        assertEquals(ModelProfile.GLM, ModelProfile.effective(ModelProfile.AUTO, "GLM-4-Plus"))
        assertEquals(ModelProfile.GLM, ModelProfile.effective(ModelProfile.AUTO, "zhipu/glm-4"))
        assertEquals(ModelProfile.DEEPSEEK, ModelProfile.effective(ModelProfile.AUTO, "deepseek-chat"))
        assertEquals(ModelProfile.DEEPSEEK, ModelProfile.effective(ModelProfile.AUTO, "campus-chat"))
        assertEquals(ModelProfile.DEEPSEEK, ModelProfile.effective(ModelProfile.AUTO, "gpt-4o"))
        assertEquals("没有 model 名时按 DeepSeek（规则更全的那一档）", ModelProfile.DEEPSEEK, ModelProfile.effective(ModelProfile.AUTO, ""))
        assertEquals(ModelProfile.DEEPSEEK, ModelProfile.effective(null, null))
    }

    @Test
    fun `显式选择优先于 model 名`() {
        assertEquals(ModelProfile.GLM, ModelProfile.effective(ModelProfile.GLM, "deepseek-chat"))
        assertEquals(ModelProfile.DEEPSEEK, ModelProfile.effective(ModelProfile.DEEPSEEK, "glm-4.5"))
    }

    // ---------------------------------------------------------------- 提示词分派

    @Test
    fun `DeepSeek 档注入工具与安全规则段`() {
        val section = ModelProfilePrompt.toolsSection(ModelProfile.DEEPSEEK, "deepseek-chat")
        assertTrue(section.startsWith(ModelProfilePrompt.SECTION))
        assertTrue("要讲 set_activity", section.contains("set_activity"))
        assertTrue("要讲 remember", section.contains("remember"))
        assertTrue("要讲 memorize", section.contains("memorize"))
        assertTrue("要讲 web_search", section.contains("web_search"))
        assertTrue("工具结果只是素材", section.contains("不要提工具名"))
    }

    @Test
    fun `GLM 档不注入这一段 是 lean`() {
        assertEquals("", ModelProfilePrompt.toolsSection(ModelProfile.GLM, "glm-4.5"))
        assertEquals("", ModelProfilePrompt.toolsSection(ModelProfile.AUTO, "glm-4.5"))
        assertTrue(
            "AUTO + glm 走 lean",
            !ModelProfilePrompt.toolsSection(ModelProfile.AUTO, "glm-4-plus").contains(ModelProfilePrompt.SECTION)
        )
    }

    @Test
    fun `AUTO 非 glm 就走 DeepSeek 档`() {
        val section = ModelProfilePrompt.toolsSection(ModelProfile.AUTO, "campus-chat")
        assertTrue(section.contains(ModelProfilePrompt.SECTION))
        assertTrue(section.contains("set_activity"))
    }

    @Test
    fun `没有联网能力时不提 web_search 而是教它说不确定`() {
        val offline = ModelProfilePrompt.toolsSection(
            profile = ModelProfile.DEEPSEEK,
            modelName = "deepseek-chat",
            webEnabled = false
        )
        assertFalse(offline.contains("web_search"))
        assertTrue(offline.contains("没有联网能力"))
        assertTrue("该有的规则仍然在", offline.contains("set_activity"))
    }

    @Test
    fun `英文人格的规则段也是英文标题`() {
        val section = ModelProfilePrompt.toolsSection(
            profile = ModelProfile.DEEPSEEK,
            modelName = "deepseek-chat",
            language = PromptLanguage.EN
        )
        assertTrue(section.startsWith(ModelProfilePrompt.SECTION_EN))
        assertTrue(section.contains("set_activity"))
        assertEquals(
            "GLM 在英文下同样不注入",
            "",
            ModelProfilePrompt.toolsSection(ModelProfile.GLM, "glm-4", language = PromptLanguage.EN)
        )
    }

    // ---------------------------------------------------------------- 与 PromptBuilder 的接线

    private fun inputs(profile: ModelProfile, modelName: String) = PromptBuilder.Inputs(
        name = "林深",
        card = PersonaCard(),
        capabilitiesSection = "# 你能做什么\n- remember(内容)：记住这件事",
        modelProfile = profile,
        modelName = modelName,
        webToolsEnabled = true,
        now = 1_700_000_000_000L
    )

    @Test
    fun `toolsBlock 里 DeepSeek 有这一段 GLM 没有`() {
        assertTrue(PromptBuilder.toolsBlock(inputs(ModelProfile.DEEPSEEK, "deepseek-chat")).contains(ModelProfilePrompt.SECTION))
        assertTrue(PromptBuilder.toolsBlock(inputs(ModelProfile.AUTO, "deepseek-chat")).contains(ModelProfilePrompt.SECTION))
        assertFalse(PromptBuilder.toolsBlock(inputs(ModelProfile.GLM, "glm-4.5")).contains(ModelProfilePrompt.SECTION))
        assertFalse(PromptBuilder.toolsBlock(inputs(ModelProfile.AUTO, "glm-4.5")).contains(ModelProfilePrompt.SECTION))
        assertTrue(
            "工具清单本身永远在（优化开关不改工具清单）",
            PromptBuilder.toolsBlock(inputs(ModelProfile.GLM, "glm-4.5")).contains("# 你能做什么")
        )
    }

    @Test
    fun `换优化档不改变其它提示词段`() {
        val deepseek = PromptBuilder.system(inputs(ModelProfile.DEEPSEEK, "deepseek-chat"))
        val glm = PromptBuilder.system(inputs(ModelProfile.GLM, "glm-4.5"))

        // 人设 / 世界 / 话风 / 边界 / 反 AI 各段逐字相同，只在工具段上有差异。
        val deepseekRest = deepseek.replace(ModelProfilePrompt.toolsSection(ModelProfile.DEEPSEEK, "deepseek-chat") + "\n\n", "")
        assertEquals("除了 # 工具使用 这一段，两份提示词必须完全一致", glm, deepseekRest)
        assertFalse(glm.contains(ModelProfilePrompt.SECTION))
        assertTrue(glm.contains(PromptBuilder.Scaffold.SECTION_IDENTITY))
        assertTrue(glm.contains(PromptBuilder.Scaffold.SECTION_BOUNDARIES))
    }

    @Test
    fun `GLM 档的提示词确实更短`() {
        val deepseek = PromptBuilder.toolsBlock(inputs(ModelProfile.DEEPSEEK, "deepseek-chat"))
        val glm = PromptBuilder.toolsBlock(inputs(ModelProfile.GLM, "glm-4.5"))
        assertTrue("lean 档必须更短：${glm.length} vs ${deepseek.length}", glm.length < deepseek.length)
    }

    // ---------------------------------------------------------------- 接线契约（源码级）

    private fun appRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    @Test
    fun `聊天主路径把人格的档位与这一轮的 model 名交给提示词`() {
        val chat = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        assertTrue(
            "人格上选的档位要进 PromptBuilder.Inputs",
            chat.contains("modelProfile = ModelProfile.fromStored(persona.modelProfile)")
        )
        assertTrue(
            "AUTO 靠这一轮真正要发的 model 名推断",
            chat.contains("modelName = session.model.ifBlank { config.model }")
        )
        assertTrue("联网能力也要传，否则会对着没联网的配置教它 web_search", chat.contains("webToolsEnabled = config.webToolsEnabled"))
        val builder = source("src/main/java/com/yap/app/domain/PromptBuilder.kt")
        assertTrue("PromptBuilder 必须真的调用分派函数", builder.contains("ModelProfilePrompt.toolsSection("))
        assertTrue("工具段 = 工具清单 + 规则段", builder.contains("input.capabilitiesSection.trim()"))
    }

    @Test
    fun `编辑页把档位交给仓储 并且不会在别的保存路径里被重置`() {
        val personaVm = source("src/main/java/com/yap/app/ui/screens/persona/PersonaEditorViewModel.kt")
        assertTrue(personaVm.contains("modelProfile = form.modelProfile"))
        assertTrue(personaVm.contains("ModelProfile.fromStored(persona.modelProfile)"))

        val presetVm = source("src/main/java/com/yap/app/ui/screens/preset/PresetEditorViewModel.kt")
        assertTrue(presetVm.contains("modelProfile = form.modelProfile"))
        assertTrue(presetVm.contains(".fromStored(preset.modelProfile)"))

        val personaRepo = source("src/main/java/com/yap/app/data/repo/PersonaRepository.kt")
        assertTrue(
            "draft 为 null 时保留库里已有的值（换头像/回滚不该把档位改回 AUTO）",
            personaRepo.contains("ModelProfile.fromStored(draft.modelProfile ?: existing?.modelProfile)")
        )
        val presetRepo = source("src/main/java/com/yap/app/data/repo/PresetRepository.kt")
        assertTrue(
            "预设同样保留已有档位",
            presetRepo.contains("fromStored(draft.modelProfile ?: existing?.modelProfile)")
        )
        assertTrue("预设转人格时带过去", presetRepo.contains("modelProfile = preset.modelProfile"))
        assertTrue("人格存成预设时带过去", presetRepo.contains("modelProfile = persona.modelProfile"))
    }
}
