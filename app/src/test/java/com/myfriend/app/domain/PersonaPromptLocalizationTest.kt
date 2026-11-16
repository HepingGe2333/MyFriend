package com.myfriend.app.domain

import com.myfriend.app.data.local.PersonaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译缓存 → 提示词的**应用**规则（`spec/persona-translation/README.md` §1 / §4 / §8）。
 *
 * 关键只有一句话：只有「语言对得上 + 源字段哈希对得上」的缓存才能替换提示词里的字段。
 * 一份过期的译文比中文原文更危险——它会让模型按一个已经不存在的设定说话，
 * 而用户完全不知道为什么。
 */
class PersonaPromptLocalizationTest {

    private fun persona(
        name: String = "林深",
        description: String = "建筑事务所项目负责人。",
        traits: String = "[\"慢热\"]",
        backstory: String? = "父亲早逝。",
        quirks: String? = "称呼对方「你」。",
        prompt: String = "你是林深。",
        worldJson: String? = "{\"worldName\":\"临港新城\",\"notes\":\"城市常年阴雨\"}",
        speechStyleJson: String? = null,
        translationJson: String? = null
    ) = PersonaEntity(
        id = "persona-1",
        name = name,
        description = description,
        avatarUri = null,
        avatarSeed = 0,
        prompt = prompt,
        category = "日常聊天",
        version = 1,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = true,
        createdAt = 1L,
        updatedAt = 1L,
        traits = traits,
        backstory = backstory,
        quirks = quirks,
        worldJson = worldJson,
        speechStyleJson = speechStyleJson,
        translationJson = translationJson,
        translationLang = translationJson?.let { PersonaTranslationCodec.decode(it)?.lang }
    )

    private fun cacheFor(persona: PersonaEntity, lang: String = "en", hash: String? = null): String {
        val translation = PersonaTranslation(
            lang = lang,
            translatedAt = 7L,
            sourceHash = hash ?: PersonaTranslationSource.hashOf(persona),
            persona = TranslatedPersona(
                name = "Lin Shen",
                description = "Project lead at an architecture firm.",
                traits = listOf("reserved"),
                likes = listOf("coffee"),
                behaviors = listOf("works late"),
                backstory = "His father died young.",
                quirks = "calls the other person \"you\"",
                systemPrompt = "You are Lin Shen."
            ),
            world = WorldPreset(
                worldName = "Lingang New City",
                era = "2026",
                notes = "Rainy city."
            )
        )
        return PersonaTranslationCodec.encode(translation)
    }

    @Test
    fun `缓存命中时替换被翻译的字段`() {
        val original = persona()
        val localized = PersonaPromptLocalization.effective(
            original.copy(translationJson = cacheFor(original)),
            lang = "en"
        )
        assertEquals("Lin Shen", localized.name)
        assertEquals("Project lead at an architecture firm.", localized.description)
        assertEquals(listOf("reserved"), PersonaProfile.decodeList(localized.traits))
        assertEquals("His father died young.", localized.backstory)
        assertEquals("calls the other person \"you\"", localized.quirks)
        assertEquals("You are Lin Shen.", localized.prompt)
        val world = WorldPresetCodec.decode(localized.worldJson)
        assertEquals("Lingang New City", world?.worldName)
        assertEquals("Rainy city.", world?.notes)
        // 不翻译的字段原样保留
        assertEquals(original.id, localized.id)
        assertEquals(original.category, localized.category)
    }

    @Test
    fun `语言对不上时用原文`() {
        val original = persona()
        val withCache = original.copy(translationJson = cacheFor(original, lang = "en"))
        val localized = PersonaPromptLocalization.effective(withCache, lang = "zh")
        assertEquals("林深", localized.name)
        // 除了那份没被采用的缓存，其它字段与原始实体逐字段相同。
        assertEquals(original, localized.copy(translationJson = null, translationLang = null))
    }

    @Test
    fun `源字段变化后缓存失效，用原文`() {
        val original = persona()
        val cached = cacheFor(original)
        // 用户改了名字（源字段变了），但缓存的哈希还是旧的。
        val changed = original.copy(name = "林深深", translationJson = cached)
        val localized = PersonaPromptLocalization.effective(changed, lang = "en")
        assertEquals("缓存失效时必须用原文", "林深深", localized.name)
        assertEquals(
            "世界也不该用旧译文",
            "临港新城",
            WorldPresetCodec.decode(localized.worldJson)?.worldName
        )
    }

    @Test
    fun `没有缓存时原样返回`() {
        val original = persona()
        assertEquals(original, PersonaPromptLocalization.effective(original, lang = "en"))
    }

    @Test
    fun `坏缓存不会让提示词变空`() {
        val original = persona()
        val localized = PersonaPromptLocalization.effective(
            original.copy(translationJson = "{坏掉的", translationLang = "en"),
            lang = "en"
        )
        assertEquals("林深", localized.name)
        assertEquals(original.worldJson, localized.worldJson)
        assertEquals(original.prompt, localized.prompt)
    }

    @Test
    fun `话风只替换 tone habits avoid phrases 保留原文`() {
        val original = persona(
            speechStyleJson = SpeechStyleCodec.encode(
                SpeechStyle(
                    tone = "简短、口语",
                    phrases = listOf("在忙，晚点说", "你先睡吧"),
                    habits = listOf("句子很短"),
                    avoid = listOf("长篇大论")
                )
            )
        )
        val hash = PersonaTranslationSource.hashOf(original)
        val cached = PersonaTranslationCodec.encode(
            PersonaTranslation(
                lang = "en",
                translatedAt = 7L,
                sourceHash = hash,
                speech = SpeechStyle(
                    tone = "short and direct",
                    habits = listOf("very short sentences"),
                    avoid = listOf("long speeches")
                )
            )
        )
        val localized = PersonaPromptLocalization.effective(
            original.copy(translationJson = cached, translationLang = "en"),
            lang = "en"
        )
        val style = SpeechStyleCodec.decode(localized.speechStyleJson)
        assertEquals("short and direct", style?.tone)
        assertEquals(listOf("very short sentences"), style?.habits)
        assertEquals(listOf("long speeches"), style?.avoid)
        assertEquals(
            "phrases 永远是原文（§3：翻译会毁掉语感）",
            listOf("在忙，晚点说", "你先睡吧"),
            style?.phrases
        )
    }

    @Test
    fun `当前语言取系统语言主标签`() {
        assertTrue(PersonaPromptLocalization.currentAppLanguage().isNotBlank())
        assertFalse(PersonaPromptLocalization.currentAppLanguage().contains("-"))
    }
}
