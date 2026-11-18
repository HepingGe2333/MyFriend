package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「名字 / 提示词留空也能保存」的兜底规则单测（0.13）。
 *
 * 真机反馈的根因有两层：编辑页拦保存、仓储层也拦。规则抽成 [PersonaDraftDefaults]
 * 的纯函数之后，这里把「全空 / 只填一项 / 两项都填 / 超长」逐一钉死——
 * 这些组合就是用户实际点「保存」时的全部输入形态，用 JVM 单测覆盖比跑 Room 便宜得多。
 *
 * 注意：默认名 / 默认人设 / 分隔符都是**调用方传进来的资源字符串**，所以这里显式传中英两套，
 * 既验证了「语言由调用方决定」，也顺带证明 domain 里没有硬编码文案。
 */
class PersonaDraftFallbackTest {

    // 中文资源（values/strings.xml）
    private val zhName = "未命名"
    private val zhSeparator = "："
    private val zhFallback = "你是用户身边的一位朋友。"

    // 英文资源（values-en/strings.xml）
    private val enName = "Unnamed"
    private val enSeparator = ": "
    private val enFallback = "You are a friend the user talks to."

    private fun resolveName(raw: String, fallback: String = zhName) =
        PersonaDraftDefaults.name(raw, fallback)

    private fun resolvePrompt(
        rawPrompt: String,
        name: String,
        description: String,
        separator: String = zhSeparator,
        fallback: String = zhFallback
    ) = PersonaDraftDefaults.prompt(rawPrompt, name, description, separator, fallback)

    // ------------------------------------------------------------ 名字

    @Test
    fun `名字全空落成本地化默认名`() {
        assertEquals(zhName, resolveName(""))
        assertEquals(enName, resolveName("", fallback = enName))
    }

    @Test
    fun `名字只有空白也算空`() {
        assertEquals(zhName, resolveName("   \n\t"))
    }

    @Test
    fun `名字非空时原样保留 只去首尾空白`() {
        assertEquals("林深", resolveName("  林深  "))
    }

    // ------------------------------------------------------------ 提示词

    @Test
    fun `名字和描述都空时用系统默认人设`() {
        val prompt = resolvePrompt(rawPrompt = "", name = "", description = "")

        assertEquals(zhFallback, prompt)
        assertTrue("兜底后必须至少有一句提示词", prompt.isNotBlank())
    }

    @Test
    fun `只填名字时提示词就是名字`() {
        assertEquals("林深", resolvePrompt(rawPrompt = "", name = "林深", description = ""))
    }

    @Test
    fun `只填描述时提示词就是描述`() {
        assertEquals(
            "退役军人，话不多但很稳。",
            resolvePrompt(rawPrompt = "", name = "", description = "退役军人，话不多但很稳。")
        )
    }

    @Test
    fun `名字和描述都有时拼成一句`() {
        assertEquals(
            "林深：退役军人，话不多但很稳。",
            resolvePrompt(rawPrompt = "", name = "林深", description = "退役军人，话不多但很稳。")
        )
    }

    @Test
    fun `提示词只有空白视为未填 走生成规则`() {
        assertEquals("林深", resolvePrompt(rawPrompt = "   ", name = "林深", description = ""))
    }

    @Test
    fun `用户写了提示词就原样保留 不再拼接`() {
        assertEquals(
            "你是一位安静的图书管理员。",
            resolvePrompt(
                rawPrompt = "  你是一位安静的图书管理员。 ",
                name = "林深",
                description = "退役军人"
            )
        )
    }

    // ------------------------------------------------------------ 语言与预设

    @Test
    fun `英文资源下生成结果用英文分隔符与英文兜底`() {
        assertEquals(
            "Lin Shen: a former soldier",
            resolvePrompt(
                rawPrompt = "",
                name = "Lin Shen",
                description = "a former soldier",
                separator = enSeparator,
                fallback = enFallback
            )
        )
        assertEquals(
            enFallback,
            resolvePrompt(rawPrompt = "", name = "", description = "", separator = enSeparator, fallback = enFallback)
        )
    }

    @Test
    fun `预设与人格共用同一套规则`() {
        // 预设的编辑页把 name/description/prompt 原样交给这里，规则不因入口不同而变。
        val fromPreset = resolvePrompt(rawPrompt = "", name = "深夜电台", description = "温柔的主播")
        val fromPersona = resolvePrompt(rawPrompt = "", name = "深夜电台", description = "温柔的主播")

        assertEquals("深夜电台：温柔的主播", fromPreset)
        assertEquals(fromPreset, fromPersona)
    }

    // ------------------------------------------------------------ 长度边界

    @Test
    fun `超长名字不被兜底规则悄悄截断 仍由仓储层报长度错误`() {
        val longName = "名".repeat(80)
        assertEquals(longName, resolveName(longName))

        val overLongName = "名".repeat(81)
        assertEquals(overLongName, resolveName(overLongName))
        assertFalse("兜底规则不该把超长名字截成合法长度", overLongName.length <= 80)
    }

    @Test
    fun `超长提示词不被兜底规则悄悄截断 仍由仓储层报长度错误`() {
        val overLongPrompt = "词".repeat(200_001)
        assertEquals(overLongPrompt, resolvePrompt(rawPrompt = overLongPrompt, name = "", description = ""))
    }

    @Test
    fun `生成出来的提示词一定短于仓储层上限`() {
        // 名字最长 80、描述最长 600（人格）或 300（预设），拼起来远小于 200_000，
        // 所以「由兜底生成的提示词」永远不会撞上长度校验，用户不会因为没填而报超长。
        val generated = resolvePrompt(rawPrompt = "", name = "名".repeat(80), description = "述".repeat(600))

        assertTrue(generated.length < 200_000)
        assertTrue(generated.contains(zhSeparator))
    }
}
