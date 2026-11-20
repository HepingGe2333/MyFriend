package com.yap.app.i18n

import com.yap.app.domain.PromptBuilder
import com.yap.app.domain.PromptContract
import com.yap.app.domain.PromptLanguage
import com.yap.app.domain.PromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言小节（0.15 重写）。
 *
 * 0.14 及以前：这一节跟着 **App 界面语言** 走（`prompt_language_directive` 资源 + `LanguageDirective`），
 * 所以用户把界面切成英文，中文人格也开始用英文思考。0.15 起语言是**人格属性**
 * （`personas.languageTag`），这一节由共享契约 [PromptTemplates] 生成。
 *
 * 这里盯的就是这条主线：人格选中文就说中文、选英文就说英文，界面语言不参与。
 */
class LanguageDirectiveTest {

    @Test
    fun `中文人格的语言小节是中文`() {
        val section = PromptBuilder.languageSectionFor("zh-CN")
        assertTrue("中文标题来自共享契约", section.contains(PromptContract.Section.LANGUAGE))
        assertTrue(section.contains(PromptLanguage.DIRECTIVE_ZH))
        assertTrue("金句要钉住：用中文思考和说话", section.contains("用中文"))
    }

    @Test
    fun `英文人格的语言小节是英文`() {
        val section = PromptBuilder.languageSectionFor("en-US")
        assertTrue("英文人格不该再看到中文标题", !section.contains(PromptContract.Section.LANGUAGE))
        assertTrue(section.contains("## Language"))
        assertEquals("## Language", section.lineSequence().first())
        assertTrue(section.contains("Think and speak in English."))
    }

    @Test
    fun `认不出的语言标签回落中文`() {
        assertEquals(PromptBuilder.languageSectionFor(null), PromptBuilder.languageSectionFor("klingon"))
        assertTrue(PromptBuilder.languageSectionFor("klingon").contains(PromptLanguage.DIRECTIVE_ZH))
    }

    @Test
    fun `语言小节粘在 system 的边界之前 而不是跟着界面语言`() {
        val zh = PromptBuilder.system(
            PromptBuilder.Inputs(
                name = "林深",
                language = PromptLanguage.ZH,
                languageSection = "\n\n" + PromptBuilder.languageSectionFor(null)
            )
        )
        val en = PromptBuilder.system(
            PromptBuilder.Inputs(
                name = "Elias",
                language = PromptLanguage.EN,
                languageSection = "\n\n" + PromptBuilder.languageSectionFor("en")
            )
        )
        assertTrue(zh.contains(PromptContract.Section.LANGUAGE))
        assertTrue(en.contains("## Language"))
        // 边界永远是最后一段。
        assertTrue(zh.indexOf("## 语言") < zh.indexOf(PromptBuilder.Scaffold.SECTION_BOUNDARIES))
        assertTrue(en.indexOf("## Language") < en.indexOf("# Boundaries"))
        assertFalse("英文人格的系统提示词里不该出现中文语言指令", en.contains("用中文思考和说话"))
    }

    @Test
    fun `共享模板给出六段的双语标题`() {
        assertEquals(PromptContract.Section.ANTI_AI, PromptTemplates.title(PromptTemplates.CoreSection.ANTI_AI, PromptLanguage.ZH))
        assertEquals("# Don't sound like an AI", PromptTemplates.title(PromptTemplates.CoreSection.ANTI_AI, PromptLanguage.EN))
        assertEquals(PromptContract.Section.BOUNDARIES, PromptTemplates.title(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.ZH))
        assertEquals("# Boundaries", PromptTemplates.title(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.EN))
    }
}
