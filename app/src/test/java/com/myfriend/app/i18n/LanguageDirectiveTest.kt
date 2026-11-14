package com.myfriend.app.i18n

import com.myfriend.app.domain.LanguageDirective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 系统提示词末尾那一节「用对方的语言回答」。
 *
 * 断言的是**真实资源**：中文模板来自 `values/strings.xml`，英文模板来自 `values-en/strings.xml`。
 * 两条主线——中文系统说中文、英文系统说英文——各一条，另外钉住「拼在 system 最后」。
 */
class LanguageDirectiveTest {

    private val zhTemplate = TestResources.strings("values").getValue("prompt_language_directive")
    private val enTemplate = TestResources.strings("values-en").getValue("prompt_language_directive")

    @Test
    fun `中文系统在 system 末尾追加中文语言小节`() {
        val languageName = LanguageDirective.languageName(Locale.SIMPLIFIED_CHINESE)
        assertEquals("中文", languageName)

        val system = "你是林深，一个温柔而安静的朋友。"
        val built = system + LanguageDirective.section(zhTemplate, languageName)

        assertTrue("中文模板该有自己的标题", built.contains("## 语言"))
        assertTrue("中文模板里要说明对方在说什么语言", built.contains("中文"))
        assertTrue("语言小节要粘在 system 的最末尾", built.endsWith("专有名词可以保留原文。"))
        assertTrue("小节和上文之间要空一行", built.contains("。\n\n## 语言\n"))
    }

    @Test
    fun `英文系统在 system 末尾追加英文语言小节`() {
        val languageName = LanguageDirective.languageName(Locale.US)
        assertEquals("English", languageName)

        val system = "You are Elias, a gentle and quiet friend."
        val built = system + LanguageDirective.section(enTemplate, languageName)

        assertTrue("英文模板该有自己的标题", built.contains("## Language"))
        assertTrue("英文模板里要说明对方在说什么语言", built.contains("English"))
        assertTrue("语言小节要粘在 system 的最末尾", built.endsWith("proper nouns can stay as they are."))
        assertTrue("小节和上文之间要空一行", built.contains("friend.\n\n## Language\n"))
    }

    @Test
    fun `语言名只取语言不取地区`() {
        assertEquals("中文", LanguageDirective.languageName(Locale.CHINA))
        assertEquals("中文", LanguageDirective.languageName(Locale.TAIWAN))
        assertEquals("English", LanguageDirective.languageName(Locale.UK))
        assertEquals("English", LanguageDirective.languageName(Locale.CANADA))
    }

    @Test
    fun `认不出的语言退回语言标签本身`() {
        // Kokani 这种没有显示名的语言：不抛异常，退回标签，模型照样能读懂。
        val name = LanguageDirective.languageName(Locale.forLanguageTag("kok"))
        assertTrue("语言名不该是空的", name.isNotBlank())
    }
}
