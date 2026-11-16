package com.yap.app.domain

import com.yap.app.data.local.PersonaEntity
import java.util.Locale

/**
 * 把翻译缓存**应用到提示词**（`spec/persona-translation/README.md` §1 / §7）。
 *
 * 这一层的存在理由很简单：提示词拼装在很多地方（聊天、主动消息、导出预览），
 * 它们都只认 [PersonaEntity]。与其让每个调用方自己解一遍缓存，不如在这里给出
 * 「这个人此刻应该以什么身份出现在提示词里」——语言对不上或缓存过期就原样返回，
 * 调用方永远不需要判断「翻译有没有生效」。
 *
 * 只在**缓存真的匹配当前语言与源字段哈希**时才替换：宁可让模型读到中文原文，
 * 也不能拿一份过期的译文假装是这个人（那会让他答非所问，而用户完全不知道为什么）。
 */
object PersonaPromptLocalization {

    /** 软件当前语言（对应 `values-*` 资源）：取系统语言的主语言标签。 */
    fun currentAppLanguage(): String {
        val raw = runCatching { Locale.getDefault().language }.getOrNull().orEmpty()
        return PersonaLanguage.normalize(raw.ifBlank { "en" })
    }

    /**
     * 提示词里用的「他」：字段被替换成当前语言的译文（人设 + 世界）。
     *
     * 只替换**被翻译的那些字段**：名字、简介、性格/喜欢/习惯、过去、设定、旧提示词、世界 JSON，
     * 以及话风的 `tone/habits/avoid`（`phrases` 永远是原文，§3）。
     * 心情、活动、作息、API 绑定、主动程度等都不动——它们不是翻译的对象。
     */
    fun effective(persona: PersonaEntity, lang: String = currentAppLanguage()): PersonaEntity {
        val cache = PersonaTranslationCodec.decode(persona.translationJson) ?: return persona
        if (!cache.matches(PersonaTranslationSource.hashOf(persona), lang)) return persona
        val translated = cache.persona
        val translatedSpeech = cache.speech
        if (translated.isEmpty() && cache.world == null &&
            (translatedSpeech == null || translatedSpeech.isEmpty())
        ) {
            return persona
        }
        return persona.copy(
            name = translated.name.ifBlank { persona.name },
            description = translated.description.ifBlank { persona.description },
            traits = translated.traits.takeIf { it.isNotEmpty() }
                ?.let(PersonaProfile::encodeList) ?: persona.traits,
            likes = translated.likes.takeIf { it.isNotEmpty() }
                ?.let(PersonaProfile::encodeList) ?: persona.likes,
            behaviors = translated.behaviors.takeIf { it.isNotEmpty() }
                ?.let(PersonaProfile::encodeList) ?: persona.behaviors,
            backstory = translated.backstory.ifBlank { persona.backstory },
            quirks = translated.quirks.ifBlank { persona.quirks },
            prompt = translated.systemPrompt.ifBlank { persona.prompt },
            worldJson = cache.world?.let(WorldPresetCodec::encode) ?: persona.worldJson,
            // 话风只换被翻译的三个字段；`phrases` 与解析失败时的原值一律保留（§3：翻它会毁语感）。
            speechStyleJson = translatedSpeech?.let { mergeSpeech(persona.speechStyleJson, it) }
                ?: persona.speechStyleJson
        )
    }

    /** 译文的 tone/habits/avoid + 原文的 phrases（缓存里本来就没有 phrases）。 */
    private fun mergeSpeech(raw: String?, translated: SpeechStyle): String {
        val original = SpeechStyleCodec.decode(raw) ?: SpeechStyle()
        return SpeechStyleCodec.encode(
            SpeechStyle(
                tone = translated.tone.ifBlank { original.tone },
                phrases = original.phrases,
                habits = translated.habits.ifEmpty { original.habits },
                avoid = translated.avoid.ifEmpty { original.avoid }
            )
        )
    }
}
