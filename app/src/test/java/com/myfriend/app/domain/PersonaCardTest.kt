package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaCardTest {

    private val sampleCard = PersonaCard(
        identity = PersonaIdentity(
            name = "林深",
            age = "二十多岁",
            role = "夜班电台主播",
            relation = "朋友"
        ),
        voice = PersonaVoice(
            pace = "慢，偶尔停顿",
            sentence = "短句为主，偶尔一句很长",
            catchphrases = listOf("嗯……", "慢慢来"),
            address = "你",
            quirks = "喜欢用省略号，很少用表情"
        ),
        traits = listOf(PersonaTrait("温柔", 0.8f), PersonaTrait("嘴硬", 0.6f)),
        values = listOf("真诚", "不轻易评判"),
        boundaries = listOf("不替对方做重大决定"),
        life = PersonaLife(
            schedule = "凌晨睡，午后起",
            doing = "在剪一档深夜节目",
            caresAbout = "朋友最近的状态"
        ),
        bond = "认识很久了，知道对方不太爱麻烦别人。"
    )

    // 1. encode/decode 往返（含中文、列表）
    @Test
    fun roundTripsChineseCard() {
        val encoded = PersonaCardCodec.encode(sampleCard)
        assertTrue(encoded.contains("林深"))
        assertTrue(encoded.contains("嗯……"))
        assertEquals(sampleCard, PersonaCardCodec.decode(encoded))
    }

    // 2. null / 空 / 坏 JSON 返回空卡且不抛
    @Test
    fun decodeFallsBackToEmptyCard() {
        assertEquals(PersonaCard(), PersonaCardCodec.decode(null))
        assertEquals(PersonaCard(), PersonaCardCodec.decode(""))
        assertEquals(PersonaCard(), PersonaCardCodec.decode("   "))
        assertEquals(PersonaCard(), PersonaCardCodec.decode("不是json"))
        assertEquals(PersonaCard(), PersonaCardCodec.decode("[1,2,3]"))
        assertEquals(PersonaCard(), PersonaCardCodec.decode("""{"identity":"林深"}"""))
        assertEquals(PersonaCard(), PersonaCardCodec.decode("""{"identity":{"name":{}}}"""))
        assertTrue(PersonaCardCodec.decode("不是json").isEmpty())
    }

    @Test
    fun decodeIgnoresUnknownFields() {
        val card = PersonaCardCodec.decode(
            """{"identity":{"name":"林深"},"whatever":true,"voice":{"pace":"慢"}}"""
        )
        assertEquals("林深", card.identity.name)
        assertEquals("慢", card.voice.pace)
    }

    // 3. stableStrategy 非空且 ≤ 80（空卡与满卡各一次）
    @Test
    fun stableStrategyStaysShort() {
        val empty = PersonaCardPrompt.stableStrategy(PersonaCard())
        assertTrue(empty.isNotBlank())
        assertTrue(empty.length <= 80)
        assertEquals("用自然、简短、像真人发消息的方式说话。", empty)

        val full = PersonaCardPrompt.stableStrategy(sampleCard)
        assertTrue(full.isNotBlank())
        assertTrue("策略长度 ${full.length}：$full", full.length <= 80)
        assertTrue(full.contains("林深"))
        assertTrue(full.contains("先回应再展开"))
    }

    // 4. renderContext 在 LEAN 下 ≤ 600、含两个必备标题、不截半
    @Test
    fun renderContextRespectsLeanBudget() {
        val text = PersonaCardPrompt.renderContext(sampleCard, PromptBudget.LEAN)
        assertTrue("长度 ${text.length}", text.length <= PromptBudget.LEAN.maxChars)
        assertTrue(text.contains("### 你是谁"))
        assertTrue(text.contains("### 说话方式"))
        // 正常体量的卡在 LEAN 下不该丢细节段落
        assertTrue(text.contains("### 生活"))
        assertTrue(text.contains("### 你们之间"))
        // 以完整段落收尾，不会被截成半个字
        assertFalse(text.endsWith("\n\n"))
        assertFalse(text.endsWith("\n"))
        assertFalse(text.endsWith("…"))
        assertEquals(text, text.trim())
        assertFalse(text.lines().last().isBlank())
    }

    @Test
    fun hardBudgetDropsDetailsFirst() {
        val bloated = sampleCard.copy(
            voice = sampleCard.voice.copy(quirks = "小习惯" + "很琐碎".repeat(120)),
            values = List(8) { "在意的事$it" + "细节".repeat(30) },
            boundaries = List(8) { "边界$it" + "底线".repeat(30) },
            traits = List(12) { PersonaTrait("特质$it", 0.5f) },
            life = PersonaLife("凌晨睡".repeat(20), "剪节目".repeat(20), "关心".repeat(20)),
            bond = "回忆。".repeat(200)
        )
        val text = PersonaCardPrompt.renderContext(bloated, PromptBudget.LEAN)
        assertTrue("长度 ${text.length}", text.length <= PromptBudget.LEAN.maxChars)
        assertTrue(text.contains("### 你是谁"))
        assertTrue(text.contains("### 说话方式"))
        // 语气小习惯与生活细节先被裁掉
        assertFalse(text.contains("小习惯"))
        assertFalse(text.contains("### 生活"))
        assertFalse(text.endsWith("\n\n"))
    }

    // 5. apply 只改出现过的字段；坏 JSON 返回原卡
    private val officialPatch = """
        {"reason":"相处久了，他开始嘴硬","voice":{"catchphrases":["……随你"],"address":"你"},"traits":[{"name":"嘴硬","strength":0.7}],"bond":"越来越熟，敢开玩笑了。"}
    """.trimIndent()

    @Test
    fun patchOnlyOverwritesPresentFields() {
        val base = PersonaCard(
            identity = PersonaIdentity(name = "林深"),
            voice = PersonaVoice(pace = "慢", catchphrases = listOf("嗯")),
            traits = listOf(PersonaTrait("温柔", 0.8f)),
            bond = "刚认识"
        )

        val patched = PersonaCardPatch.apply(base, officialPatch)

        assertEquals("林深", patched.identity.name)             // identity 没出现，保持原样
        assertEquals("慢", patched.voice.pace)                  // pace 没出现，保持原样
        assertEquals(listOf("……随你"), patched.voice.catchphrases) // 列表是替换语义
        assertEquals("你", patched.voice.address)
        assertEquals(listOf(PersonaTrait("嘴硬", 0.7f)), patched.traits)
        assertEquals("越来越熟，敢开玩笑了。", patched.bond)
    }

    @Test
    fun badPatchKeepsOriginalCard() {
        val base = sampleCard
        assertEquals(base, PersonaCardPatch.apply(base, "不是 JSON"))
        assertEquals(base, PersonaCardPatch.apply(base, ""))
        assertEquals(base, PersonaCardPatch.apply(base, "[1,2,3]"))
        assertEquals(base, PersonaCardPatch.apply(base, """{"unknown":{"a":1}}"""))
        assertEquals(base, PersonaCardPatch.apply(base, """{"voice":{"catchphrases":"不是数组"}}"""))
    }

    // 6. summarize 对官方示例补丁包含「说话」或「性格」
    @Test
    fun summarizeDescribesPatch() {
        val summary = PersonaCardPatch.summarize(officialPatch)
        assertTrue(summary.isNotBlank())
        assertTrue("摘要：$summary", summary.contains("说话") || summary.contains("性格"))
        assertTrue(summary.contains("嘴硬"))
        assertEquals("调整了人设", PersonaCardPatch.summarize("不是 JSON"))
        assertEquals("调整了人设", PersonaCardPatch.summarize("""{"reason":"随便改改"}"""))
    }

    // 补充：旧版提示词 -> 人设卡，且原文不丢
    @Test
    fun legacyPromptIsStructuredWithoutLosingText() {
        val legacy = listOf(
            "你是林深，一个温柔而安静的朋友。",
            "",
            "## 你的表达方式",
            "- 你说话从容、克制，不急着给建议。",
            "- 不称自己为“AI 助手”。",
            "## 你的陪伴原则",
            "- 不替对方做重大决定。"
        ).joinToString("\n")

        val card = PersonaCardPrompt.fromLegacyPrompt(legacy)

        assertEquals("林深", card.identity.name)
        assertTrue(card.identity.role.contains("温柔而安静"))
        assertEquals("朋友", card.identity.relation)
        assertTrue(card.voice.pace.contains("从容"))
        assertTrue(card.boundaries.any { it.contains("AI 助手") })
        assertTrue(card.bond.contains("你是林深"))  // 原文整体保留
        assertTrue(card.traits.isEmpty())
        assertTrue(PersonaCardPrompt.renderContext(card, PromptBudget.LEAN).isNotBlank())
    }

    @Test
    fun unrecognizedLegacyKeepsRawTextInBond() {
        val legacy = "我们坐在天台，看云。"
        val card = PersonaCardPrompt.fromLegacyPrompt(legacy)

        assertEquals(legacy, card.bond)
        assertEquals(1, card.traits.size)
        assertEquals("保持原文里的自我设定", card.traits.first().name)
        assertEquals(0.6f, card.traits.first().strength, 0.0001f)
        assertTrue(PersonaCardPrompt.fromLegacyPrompt("").isEmpty())
    }
}
