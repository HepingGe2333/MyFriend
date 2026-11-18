package com.yap.app.ui.screens.persona

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「名字/提示词可以留空保存」的**源码契约**（0.13 真机反馈）。
 *
 * 这条规则有三层，任何一层漏掉用户都会再次撞上「一项没填就保存不了」：
 *
 * 1. 两个编辑页的 ViewModel 不能再有 blank 拦截分支，且保存前要走 domain 兜底；
 * 2. 仓储层的 `save()` 不能再抛 `PERSONA_NAME_REQUIRED` / `PRESET_*`，否则预设生成人格、
 *    复制、导入这些不经过编辑页的入口仍会被拦；长度上限必须保留；
 * 3. 预设编辑页的保存按钮不能再用 name/prompt 非空来 disabled（那是最直接的「点不了」）。
 *
 * 只扫关键片段，不逐行比对：这些文件还在被别的任务改，断言越细越容易变成假红。
 * 真正的行为由 `PersonaDraftFallbackTest` 用纯函数覆盖。
 */
class PersonaOptionalSaveContractTest {

    private fun moduleRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "src/main/java/com/yap/app").isDirectory) return dir
            if (File(dir, "app/src/main/java/com/yap/app").isDirectory) return File(dir, "app")
            dir = dir.parentFile
        }
        error("找不到 app 模块根目录（从 $start 向上）：需要包含 src/main/java/com/yap/app")
    }

    private fun read(relative: String): String {
        val file = File(moduleRoot(), relative)
        assertTrue("文件不存在：${file.absolutePath}", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    private val personaViewModel = "src/main/java/com/yap/app/ui/screens/persona/PersonaEditorViewModel.kt"
    private val presetViewModel = "src/main/java/com/yap/app/ui/screens/preset/PresetEditorViewModel.kt"
    private val personaRepository = "src/main/java/com/yap/app/data/repo/PersonaRepository.kt"
    private val presetRepository = "src/main/java/com/yap/app/data/repo/PresetRepository.kt"
    private val presetScreen = "src/main/java/com/yap/app/ui/screens/preset/PresetEditorScreen.kt"

    @Test
    fun `人格编辑页不再用名字或提示词拦保存`() {
        val source = read(personaViewModel)

        assertFalse(
            "PersonaEditorViewModel 还在用「请填写人格名称」拦保存（0.13 起允许留空）",
            source.contains("persona_editor_name_required")
        )
        assertFalse(
            "PersonaEditorViewModel 还在用「请写下人格提示词」拦保存（0.13 起允许留空）",
            source.contains("persona_editor_prompt_required")
        )
        assertFalse(
            "PersonaEditorViewModel.save() 里还有 `if (form.name.isBlank())` 拦截",
            source.contains("if (form.name.isBlank())")
        )
        assertFalse(
            "PersonaEditorViewModel.save() 里还有 `if (form.prompt.isBlank())` 拦截",
            source.contains("if (form.prompt.isBlank())")
        )
        // 兜底必须真的接上：名字走默认名，提示词走「名字 + 简介」生成。
        assertTrue("保存时应调用 PersonaDraftDefaults.name 兜底默认名", source.contains("PersonaDraftDefaults.name("))
        assertTrue("保存时应调用 PersonaDraftDefaults.prompt 生成提示词", source.contains("PersonaDraftDefaults.prompt("))
    }

    @Test
    fun `预设编辑页不再用名字或提示词拦保存`() {
        val source = read(presetViewModel)

        assertFalse(
            "PresetEditorViewModel 还在用「预设需要包含人格提示词」拦保存",
            source.contains("preset_editor_prompt_required")
        )
        assertFalse(
            "PresetEditorViewModel.save() 里还有 `if (form.name.isBlank())` 拦截",
            source.contains("if (form.name.isBlank())")
        )
        assertFalse(
            "PresetEditorViewModel.save() 里还有 `if (form.prompt.isBlank())` 拦截",
            source.contains("if (form.prompt.isBlank())")
        )
        assertTrue("预设保存也要走同一套兜底规则", source.contains("PersonaDraftDefaults.name("))
        assertTrue("预设保存也要走同一套生成规则", source.contains("PersonaDraftDefaults.prompt("))
        // 「AI 补全世界」仍要求有简介/名字：那是补全的入参要求，不是保存闸门，保留是对的。
        assertTrue(
            "fillWorldWithAi 的入参要求被误删了（它允许保留）",
            source.contains("brief.isBlank() && form.name.isBlank()")
        )
    }

    @Test
    fun `预设编辑页的保存按钮不再要求先填满`() {
        val source = read(presetScreen)

        assertFalse(
            "PresetEditorScreen 的保存按钮仍被 name/prompt 非空 disabled（用户会直接点不了保存）",
            source.contains("state.form.name.isNotBlank()") || source.contains("state.form.prompt.isNotBlank()")
        )
    }

    @Test
    fun `仓储层不再因空名字或空提示词报错 但保留长度上限`() {
        val persona = read(personaRepository)
        val preset = read(presetRepository)

        assertFalse(
            "PersonaRepository.save() 还在抛 PERSONA_NAME_REQUIRED",
            persona.contains("userError(DomainErrorCode.PERSONA_NAME_REQUIRED)")
        )
        assertFalse(
            "PresetRepository.save() 还在抛 PRESET_NAME_REQUIRED",
            preset.contains("userError(DomainErrorCode.PRESET_NAME_REQUIRED)")
        )
        assertFalse(
            "PresetRepository.save() 还在抛 PRESET_PROMPT_REQUIRED",
            preset.contains("userError(DomainErrorCode.PRESET_PROMPT_REQUIRED)")
        )

        listOf(
            persona to "PERSONA_NAME_TOO_LONG",
            persona to "PERSONA_PROMPT_TOO_LONG",
            preset to "PRESET_NAME_TOO_LONG",
            preset to "PRESET_PROMPT_TOO_LONG"
        ).forEach { (source, code) ->
            assertTrue("长度上限校验 $code 被误删了", source.contains("userError(DomainErrorCode.$code)"))
        }

        // 仓储层兜底：所有入口（预设生成人格、复制、导入）都必须经过这里。
        assertTrue("PersonaRepository.save() 缺少名字/提示词兜底", persona.contains("PersonaDraftDefaults.name("))
        assertTrue("PersonaRepository.save() 缺少提示词生成", persona.contains("PersonaDraftDefaults.prompt("))
        assertTrue("PresetRepository.save() 缺少名字/提示词兜底", preset.contains("PersonaDraftDefaults.name("))
        assertTrue("PresetRepository.save() 缺少提示词生成", preset.contains("PersonaDraftDefaults.prompt("))
    }

    @Test
    fun `默认名与默认人设走本地化资源 且中英成对`() {
        val zh = read("src/main/res/values/strings.xml")
        val en = read("src/main/res/values-en/strings.xml")

        listOf("draft_default_name", "draft_default_prompt", "draft_default_prompt_separator").forEach { key ->
            assertTrue("中文资源缺少 $key", zh.contains("name=\"$key\""))
            assertTrue("英文资源缺少 $key", en.contains("name=\"$key\""))
            assertTrue("$key 没有被源码引用（会变成死文案）", javaClassReferenced(key))
        }

        // 旧的拦截文案已经没有入口在用了：留着会被 ResourceUsageGuardTest 判成死键。
        listOf("persona_editor_name_required", "persona_editor_prompt_required", "preset_editor_prompt_required")
            .forEach { key ->
                assertFalse("中文资源里还留着没人用的 $key", zh.contains("name=\"$key\""))
                assertFalse("英文资源里还留着没人用的 $key", en.contains("name=\"$key\""))
            }
    }

    /** `R.string.<key>` 是否出现在 src/main 的 Kotlin 源码里。 */
    private fun javaClassReferenced(key: String): Boolean {
        val main = File(moduleRoot(), "src/main/java")
        return main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { it.readText(Charsets.UTF_8).contains("R.string.$key") }
    }
}
