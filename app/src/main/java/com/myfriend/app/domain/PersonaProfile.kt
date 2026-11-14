package com.myfriend.app.domain

import com.myfriend.app.data.local.PersonaEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * 1.9 的「联系人细分字段」：性格 / 喜欢 / 习惯 / 过去 / 设定。
 *
 * 它们不是人设卡的替代品：人设卡（[PersonaCard]）描述「他是谁、怎么说话」，
 * 这里描述的是用户在编辑页里能一眼看懂、一句话就能改的五件事。
 * 两者都会进提示词，但这一段只占很小的一块，标题固定为「## 你是谁」。
 *
 * 两个刻意的设计：
 * 1. **没填的字段不占位**。空列表/空字符串整段不出现，提示词里不会留「（未填写）」这种噪声；
 * 2. **列表是 JSON 存库里、纯文本进提示词**。界面上是逗号分隔，库里是数组，
 *    换端、备份、老数据升级都不需要猜分隔符。
 */
object PersonaProfile {

    const val MAX_TRAITS = 6
    const val MAX_LIKES = 8
    const val MAX_BEHAVIORS = 6
    const val MAX_BACKSTORY = 600
    const val MAX_QUIRKS = 300

    /** 提示词里这一段最多占多少字：再多就会把人设卡挤掉。 */
    private const val PROMPT_MAX_CHARS = 1200

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 宽松解码：坏 JSON / 非字符串 / 嵌套对象一律当作空列表，绝不抛。 */
    fun decodeList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(raw)
            val array = root as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** 去重、去空白、限长度；顺序保留用户写的顺序。 */
    fun sanitizeList(items: List<String>, max: Int): List<String> =
        items.map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .map { it.take(40) }
            .distinct()
            .take(max)

    fun encodeList(items: List<String>): String =
        runCatching { JsonArray(items.map(::JsonPrimitive)).toString() }.getOrDefault("[]")

    /** 人设的五段结构；返回空串表示这个人还没有填任何细分字段。 */
    fun promptBlock(persona: PersonaEntity): String {
        val traits = sanitizeList(decodeList(persona.traits), MAX_TRAITS)
        val likes = sanitizeList(decodeList(persona.likes), MAX_LIKES)
        val behaviors = sanitizeList(decodeList(persona.behaviors), MAX_BEHAVIORS)
        val backstory = persona.backstory?.trim()?.take(MAX_BACKSTORY).orEmpty()
        val quirks = persona.quirks?.trim()?.take(MAX_QUIRKS).orEmpty()

        val sections = buildList {
            if (traits.isNotEmpty()) add(section("性格", traits))
            if (likes.isNotEmpty()) add(section("喜欢的事", likes))
            if (behaviors.isNotEmpty()) add(section("习惯", behaviors))
            if (backstory.isNotBlank()) add("### 过去\n$backstory")
            if (quirks.isNotBlank()) add("### 设定\n$quirks")
        }
        if (sections.isEmpty()) return ""
        val body = sections.joinToString("\n\n").take(PROMPT_MAX_CHARS)
        return "## 你是谁\n$body"
    }

    private fun section(title: String, items: List<String>): String =
        "### $title\n" + items.joinToString("\n") { "- $it" }

    /** 给界面用的人话摘要（下拉列表、联系人卡片都能用）。 */
    fun oneLine(persona: PersonaEntity): String =
        sanitizeList(decodeList(persona.traits), MAX_TRAITS).take(2)
            .joinToString(" · ")
            .ifBlank { persona.description.trim() }
}
