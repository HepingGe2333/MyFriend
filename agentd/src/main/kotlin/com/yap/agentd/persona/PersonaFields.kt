package com.yap.agentd.persona

import com.yap.agentd.store.Persona
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue

/**
 * 服务端对人格「客户端字段」的读取/写回（0.13：提示词、进化、情绪都要用）。
 *
 * 服务端存储把客户端字段原样放在 `Persona.extraFields` 里：**形状由客户端决定**，
 * 服务端只按字段名认语义（`docs/design/server-authoritative.md` §3.6「字段名与客户端 schema 同义」）。
 * 现实里同一个字段有两种形状，本文件都要认：
 *
 * - 客户端 Room 列：`traits = "[\"慢热\"]"`（**JSON 数组文本**），`quirks = "…"`（自由文本）；
 * - 直接调的 HTTP 客户端：`traits = ["慢热"]`（真正的 JSON 数组）。
 *
 * 写回时**保持原有形状**（原来是什么就写什么），否则客户端下次同步会把 `[]` 解析成
 * 一整个字符串。字段上限与客户端 `PersonaProfile` 一致（6 / 8 / 6 / 300），超了丢新值，
 * 绝不截断已有值。
 */
object PersonaFields {

    const val TRAITS = "traits"
    const val LIKES = "likes"
    const val BEHAVIORS = "behaviors"
    const val QUIRKS = "quirks"
    const val VERSION = "version"

    const val MAX_TRAITS = 6
    const val MAX_LIKES = 8
    const val MAX_BEHAVIORS = 6
    const val MAX_QUIRKS = 300

    /** 人格当前的四组值（进化的输入/输出、提示词的 profile 段都用它）。 */
    data class Current(
        val traits: List<String> = emptyList(),
        val likes: List<String> = emptyList(),
        val behaviors: List<String> = emptyList(),
        val quirks: String = ""
    ) {
        fun isEmpty(): Boolean =
            traits.isEmpty() && likes.isEmpty() && behaviors.isEmpty() && quirks.isBlank()
    }

    fun current(persona: Persona): Current = Current(
        traits = decodeList(persona.extraFields[TRAITS]).take(MAX_TRAITS),
        likes = decodeList(persona.extraFields[LIKES]).take(MAX_LIKES),
        behaviors = decodeList(persona.extraFields[BEHAVIORS]).take(MAX_BEHAVIORS),
        quirks = decodeQuirks(persona.extraFields[QUIRKS]).trim().take(MAX_QUIRKS)
    )

    /**
     * 把人设的四组值 + version 写回 `extraFields`（纯函数，不改入参）。
     *
     * 形状保持：原来的值是 JSON 数组就写数组；是字符串（客户端 Room 列）或缺失就写
     * 「JSON 数组文本」。quirks 空串时写 `null` 形状（JSON `null`），与客户端「为空」一致。
     */
    fun withCurrent(
        extras: Map<String, JsonValue>,
        current: Current,
        version: Int
    ): Map<String, JsonValue> {
        val updated = LinkedHashMap(extras)
        updated[TRAITS] = encodeListLike(extras[TRAITS], current.traits)
        updated[LIKES] = encodeListLike(extras[LIKES], current.likes)
        updated[BEHAVIORS] = encodeListLike(extras[BEHAVIORS], current.behaviors)
        val quirks = current.quirks.trim().take(MAX_QUIRKS)
        updated[QUIRKS] = if (quirks.isEmpty()) JsonValue.Null else JsonValue.Str(quirks)
        updated[VERSION] = JsonValue.Num(version.toString())
        return updated
    }

    /** 人格版本号：客户端缺省是 1（`ServerBootstrapMapping` 的同一默认值），服务端不猜 0。 */
    fun version(persona: Persona): Int = when (val raw = persona.extraFields[VERSION]) {
        is JsonValue.Num -> raw.asInt ?: 1
        is JsonValue.Str -> raw.value.trim().toIntOrNull() ?: 1
        else -> 1
    }

    // ------------------------------------------------------------------ 读

    /**
     * `traits` / `likes` / `behaviors` 的统一读法。
     *
     * - `Arr`：直接取字符串元素；
     * - `Str`：先按 JSON 数组解析（客户端 Room 列的形状），解析失败时把整段当一条
     *   （旧数据/手工写的人格里确实存在纯文本）；
     * - 缺失/`null`/空串：空列表。
     */
    fun decodeList(value: JsonValue?): List<String> = when (value) {
        null, JsonValue.Null -> emptyList()
        is JsonValue.Arr -> value.items.mapNotNull { (it as? JsonValue.Str)?.value?.trim() }
            .filter { it.isNotEmpty() }

        is JsonValue.Str -> {
            val text = value.value.trim()
            if (text.isEmpty() || text == "[]") {
                emptyList()
            } else {
                val parsed = runCatching { Json.parse(text) as? JsonValue.Arr }.getOrNull()
                if (parsed != null) {
                    parsed.items.mapNotNull { (it as? JsonValue.Str)?.value?.trim() }
                        .filter { it.isNotEmpty() }
                } else {
                    listOf(text)
                }
            }
        }

        else -> emptyList()
    }

    /** `quirks`：客户端是**一整段自由文本**；数组形状按「一行一条」拼回去。 */
    fun decodeQuirks(value: JsonValue?): String = when (value) {
        null, JsonValue.Null -> ""
        is JsonValue.Str -> value.value
        is JsonValue.Arr -> value.items.mapNotNull { (it as? JsonValue.Str)?.value }
            .filter { it.isNotBlank() }
            .joinToString("\n")

        else -> ""
    }

    // ------------------------------------------------------------------ 写

    private fun encodeListLike(previous: JsonValue?, items: List<String>): JsonValue {
        val cleaned = items.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (previous is JsonValue.Arr) {
            JsonValue.Arr(cleaned.mapTo(mutableListOf()) { JsonValue.Str(it) })
        } else {
            JsonValue.Str(encodeArrayText(cleaned))
        }
    }

    /** `["a","b"]` 的文本形式（客户端 Room 列的格式；用共享 JSON 写，转义不会漏）。 */
    fun encodeArrayText(items: List<String>): String =
        Json.stringify(JsonValue.Arr(items.mapTo(mutableListOf()) { JsonValue.Str(it) }))

    // ------------------------------------------------------------------ 客户端复合字段

    /**
     * 世界预设里进提示词的几样（`spec/persona-guidance/README.md` §2.1/§6.3）。
     *
     * 日程/人际关系/义务/`notes` 不在这里：共享脚手架（`:agentcore` `PersonaPromptScaffold`）
     * 只渲染「地方 / 时代 / 描述 / 其他背景」——规范 §2.1 说日程等只在与话题相关时带出，
     * 不应把整份设定念给模型。
     */
    data class World(
        val worldName: String = "",
        val era: String = "",
        val description: String = "",
        val notes: String = ""
    ) {
        fun isEmpty(): Boolean =
            worldName.isBlank() && era.isBlank() && description.isBlank() && notes.isBlank()
    }

    /** 话风（`speechStyleJson`）：tone/phrases 进脚手架，habits/avoid 作为数据行补充。 */
    data class Speech(
        val tone: String = "",
        val phrases: List<String> = emptyList(),
        val habits: List<String> = emptyList(),
        val avoid: List<String> = emptyList()
    ) {
        fun isEmpty(): Boolean = tone.isBlank() && phrases.isEmpty()
    }

    /** 人设卡（`personaCardJson`）里提示词用得到的几格；缺字段全部走安全默认值。 */
    data class Card(
        val age: String = "",
        val role: String = "",
        val relation: String = "",
        val address: String = "",
        val catchphrases: List<String> = emptyList(),
        val voiceQuirks: String = "",
        val bond: String = "",
        val schedule: String = "",
        val doing: String = "",
        val caresAbout: String = ""
    )

    fun world(persona: Persona): World {
        val obj = jsonObject(persona.extraFields["worldJson"]) ?: return World()
        return World(
            worldName = text(obj.fields["worldName"]),
            era = text(obj.fields["era"]),
            description = text(obj.fields["description"]),
            notes = text(obj.fields["notes"])
        )
    }

    fun speech(persona: Persona): Speech {
        val obj = jsonObject(persona.extraFields["speechStyleJson"]) ?: return Speech()
        return Speech(
            tone = text(obj.fields["tone"]),
            phrases = stringList(obj.fields["phrases"]),
            habits = stringList(obj.fields["habits"]),
            avoid = stringList(obj.fields["avoid"])
        )
    }

    fun card(persona: Persona): Card {
        val root = jsonObject(persona.extraFields["personaCardJson"]) ?: return Card()
        val identity = root.fields["identity"] as? JsonValue.Obj
        val voice = root.fields["voice"] as? JsonValue.Obj
        val life = root.fields["life"] as? JsonValue.Obj
        return Card(
            age = text(identity?.fields?.get("age")),
            role = text(identity?.fields?.get("role")),
            relation = text(identity?.fields?.get("relation")),
            address = text(voice?.fields?.get("address")),
            catchphrases = stringList(voice?.fields?.get("catchphrases")),
            voiceQuirks = text(voice?.fields?.get("quirks")),
            bond = text(root.fields["bond"]),
            schedule = text(life?.fields?.get("schedule")),
            doing = text(life?.fields?.get("doing")),
            caresAbout = text(life?.fields?.get("caresAbout"))
        )
    }

    // ------------------------------------------------------------------ 小工具

    /** 客户端复合字段（worldJson 等）可能是对象，也可能是「JSON 文本」；两种都认。 */
    private fun jsonObject(value: JsonValue?): JsonValue.Obj? = when (value) {
        null, JsonValue.Null -> null
        is JsonValue.Obj -> value
        is JsonValue.Str -> runCatching { Json.parse(value.value) as? JsonValue.Obj }.getOrNull()
        else -> null
    }

    private fun text(value: JsonValue?): String = when (value) {
        null, JsonValue.Null -> ""
        is JsonValue.Str -> value.value.trim()
        is JsonValue.Num -> value.raw
        else -> ""
    }

    private fun stringList(value: JsonValue?): List<String> = when (value) {
        null, JsonValue.Null -> emptyList()
        is JsonValue.Arr -> value.items.mapNotNull { (it as? JsonValue.Str)?.value?.trim() }
            .filter { it.isNotEmpty() }

        is JsonValue.Str -> decodeList(value)
        else -> emptyList()
    }

    /** 解析任意 JSON 文本（坏数据返回 null，绝不抛）。 */
    fun parseObjectOrNull(raw: String?): JsonValue.Obj? {
        if (raw.isNullOrBlank()) return null
        return try {
            Json.parse(raw) as? JsonValue.Obj
        } catch (_: JsonParseException) {
            null
        }
    }
}
