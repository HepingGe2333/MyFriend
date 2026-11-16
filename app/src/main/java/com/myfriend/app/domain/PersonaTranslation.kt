package com.myfriend.app.domain

import com.myfriend.app.data.local.PersonaEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * 话风里**参与翻译/哈希**的三项是否全空（`phrases` 不算，§3）。
 *
 * 例子：只学过常用语、tone/habits/avoid 都空的话风，参与哈希与缓存的内容与「没有话风」完全相同，
 * 必须同样写 `null`——否则改一句口头禅就会经由 null ↔ 空对象翻转，让整份译文失效。
 *
 * 放在文件级是因为 [PersonaTranslationSource]（哈希）与 [PersonaTranslationCodec]（缓存）
 * 必须用**同一个判据**，否则同一份值在两个 JSON 里一个写 null、一个写空对象。
 */
private fun SpeechStyle.translatableFieldsEmpty(): Boolean =
    tone.isEmpty() && habits.isEmpty() && avoid.isEmpty()

/**
 * 翻译后的人设正文（`spec/persona-translation/README.md` §3 / §4）。
 *
 * 字段与 [PersonaEntity] 一一对应，只是换成了 App 当前语言。它是**纯数据**：
 * 不读时钟、不碰数据库、也不认识「当前语言是什么」——那些都归调用方。
 *
 * `systemPrompt` 就是 [PersonaEntity.prompt]（用户写的那段人设提示词），
 * 它也要翻：中文人设配英文界面时，模型会在两种语言之间摇摆（§1）。
 */
data class TranslatedPersona(
    val name: String = "",
    val description: String = "",
    val traits: List<String> = emptyList(),
    val likes: List<String> = emptyList(),
    val behaviors: List<String> = emptyList(),
    val backstory: String = "",
    val quirks: String = "",
    val systemPrompt: String = ""
) {
    /** 一个字都没翻（跳过 / 整份失败时的「空正文」标记）。调用方看到它就用原文。 */
    fun isEmpty(): Boolean =
        name.isBlank() && description.isBlank() && traits.isEmpty() && likes.isEmpty() &&
            behaviors.isEmpty() && backstory.isBlank() && quirks.isBlank() && systemPrompt.isBlank()
}

/**
 * 一份完整的翻译缓存（`personas.translationJson`，§4）。
 *
 * [sourceHash] 是「这份译文是按哪一版源字段翻的」：源字段任一变化 → 哈希对不上 →
 * 缓存作废、下次启动重译（§4）。[lang] 存调用方给的完整 BCP-47 tag（§7：非 zh/en 时
 * 按完整 tag 区分缓存），比较时再用 [PersonaLanguage.normalize] 只比主语言子标签。
 *
 * [world] 与人格字段分开存：没有世界预设的人就是 `null`，不要伪造一个空世界；
 * [speech] 同理：话风里只有 `tone/habits/avoid` 参与翻译与缓存，`phrases` 永远留在人格原文里。
 */
data class PersonaTranslation(
    val lang: String = "",
    val translatedAt: Long = 0L,
    val sourceHash: String = "",
    val persona: TranslatedPersona = TranslatedPersona(),
    val world: WorldPreset? = null,
    val speech: SpeechStyle? = null
) {
    /** 缓存是否可用于这次的「源哈希 + 目标语言」（两条都满足才算命中）。 */
    fun matches(sourceHash: String, lang: String): Boolean =
        this.sourceHash.isNotBlank() &&
            this.sourceHash == sourceHash &&
            PersonaLanguage.isSame(this.lang, lang)

    /**
     * 连语言、哈希、正文都没有 = 等于没有缓存。
     *
     * 注意「跳过翻译」那份**不算空**：它有 lang/hash，写进缓存后下次直接命中（零请求）。
     */
    fun isEmpty(): Boolean =
        lang.isBlank() && sourceHash.isBlank() && persona.isEmpty() &&
            (world == null || world.isEmpty()) && (speech == null || speech.isEmpty())
}

/**
 * [PersonaTranslation] 的 JSON 编解码（§4 的固定形状）。
 *
 * 与 [WorldPresetCodec] / [SpeechStyleCodec] 一样是**宽容读、严格写**：
 * - [decode]：坏 JSON / 非对象 → `null`；缺字段 → 默认值；未知键忽略；坏 world → `null`；
 * - [encode]：键名与顺序照 §4，`world` 没有就写 `"world": null`（不省键，两端 schema 对齐）。
 *
 * 读的时候绝不能因为一个字段坏了就整份丢掉：翻译是可选缓存，缓存坏了顶多重译一次，
 * 但不该让「打开会话」跟着炸。
 */
object PersonaTranslationCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** 坏 JSON → `null`；空串 / `{}` → 全空对象（不是 null，方便调用方统一走 isEmpty 判断）。 */
    fun decode(raw: String?): PersonaTranslation? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return runCatching {
            val persona = root["persona"] as? JsonObject
            PersonaTranslation(
                lang = root.str("lang"),
                translatedAt = root.long("translatedAt") ?: 0L,
                sourceHash = root.str("sourceHash"),
                persona = TranslatedPersona(
                    name = persona?.str("name").orEmpty(),
                    description = persona?.str("description").orEmpty(),
                    traits = persona?.strings("traits").orEmpty(),
                    likes = persona?.strings("likes").orEmpty(),
                    behaviors = persona?.strings("behaviors").orEmpty(),
                    backstory = persona?.str("backstory").orEmpty(),
                    quirks = persona?.str("quirks").orEmpty(),
                    systemPrompt = persona?.str("systemPrompt").orEmpty()
                ),
                // 世界走自己的宽容解码；全空的世界等同于「没有世界」，统一收成 null。
                world = (root["world"] as? JsonObject)
                    ?.let { WorldPresetCodec.decode(it.toString()) }
                    ?.takeIf { !it.isEmpty() },
                // 话风缓存只含 tone/habits/avoid（没有 phrases）；全空同样收成 null。
                speech = (root["speech"] as? JsonObject)
                    ?.let { SpeechStyleCodec.decode(it.toString()) }
                    ?.takeIf { !it.isEmpty() }
            )
        }.getOrNull()
    }

    /** 照 §4 的形状写死键名；`world` 为 null 时写 JSON null。 */
    fun encode(t: PersonaTranslation): String = runCatching {
        JsonObject(
            mapOf(
                "lang" to JsonPrimitive(t.lang),
                "translatedAt" to JsonPrimitive(t.translatedAt),
                "sourceHash" to JsonPrimitive(t.sourceHash),
                "persona" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(t.persona.name),
                        "description" to JsonPrimitive(t.persona.description),
                        "traits" to JsonArray(t.persona.traits.map(::JsonPrimitive)),
                        "likes" to JsonArray(t.persona.likes.map(::JsonPrimitive)),
                        "behaviors" to JsonArray(t.persona.behaviors.map(::JsonPrimitive)),
                        "backstory" to JsonPrimitive(t.persona.backstory),
                        "quirks" to JsonPrimitive(t.persona.quirks),
                        "systemPrompt" to JsonPrimitive(t.persona.systemPrompt)
                    )
                ),
                "world" to (t.world?.let { preset ->
                    json.parseToJsonElement(WorldPresetCodec.encode(preset))
                } ?: JsonNull),
                // 两端 JSON 形状固定：没有话风也写 null，不省键（iOS 同形状）。
                // null 判据与哈希相同（只看 tone/habits/avoid，phrases 不影响）。
                "speech" to (t.speech?.takeUnless { it.translatableFieldsEmpty() }?.let { style ->
                    JsonObject(
                        mapOf(
                            "tone" to JsonPrimitive(style.tone.trim()),
                            "habits" to JsonArray(style.habits.map { it.trim() }.filter { it.isNotBlank() }.map(::JsonPrimitive)),
                            "avoid" to JsonArray(style.avoid.map { it.trim() }.filter { it.isNotBlank() }.map(::JsonPrimitive))
                        )
                    )
                } ?: JsonNull)
            )
        ).toString()
    }.getOrDefault("{}")

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.str(key: String): String =
        ((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: "").trim()

    private fun JsonObject.strings(key: String): List<String> =
        ((this[key] as? JsonArray)?.toList().orEmpty()).mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotBlank() }
        }
}

/**
 * 语言判定与语言名（§5 / §7）。
 *
 * 只做三件极简的事，不做真正的语言识别：
 * 1. BCP-47 tag 归一到主语言子标签（`en-US` → `en`、`zh-Hans` → `zh`）；
 * 2. 源文本的中文判定：CJK 字符占比 > 0.2（§5 的硬规则，不是猜）；
 * 3. 给翻译提示词用的目标语言名（提示词里写「中文 / English」，不是写 `zh`）。
 *
 * 纯 JVM：没有 Locale、没有 Android Resources——单测能直接断言。
 */
object PersonaLanguage {

    const val ZH = "zh"
    const val EN = "en"

    /** §5：CJK 占比超过这个值就算中文。 */
    const val CJK_RATIO = 0.2

    private const val DISPLAY_ZH = "中文"
    private const val DISPLAY_EN = "English"

    /**
     * BCP-47 → 主语言子标签（小写）。
     *
     * 兼容 `_`（`zh_CN`，老备份/手写值里见过）；空串仍然是空串——**空 tag 不等于任何语言**，
     * 否则「没有语言的缓存」会被误判成命中。
     */
    fun normalize(tag: String): String {
        val trimmed = tag.trim().lowercase()
        if (trimmed.isEmpty()) return ""
        return trimmed.substringBefore('-').substringBefore('_')
    }

    /**
     * CJK 字符占比 > [CJK_RATIO]（分母是**非空白**字符）。
     *
     * 只数汉字：日文假名、全角标点都不算，免得「英文里夹几个中文标点」被判成中文。
     */
    fun isChinese(text: String): Boolean {
        if (text.isBlank()) return false
        var total = 0
        var cjk = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint)) continue
            total++
            if (isCjk(codePoint)) cjk++
        }
        return total > 0 && cjk.toDouble() / total > CJK_RATIO
    }

    /** 源文本语言：只区分 `zh` / `en`（§5）——判定不出来时按英文处理（不值得为它再猜一次）。 */
    fun detect(text: String): String = if (isChinese(text)) ZH else EN

    /**
     * 两个语言 tag 是否是同一种语言（`en-US` 与 `en` 相同，`en` 与 `zh` 不同）。
     *
     * 空 tag 一律算「不相同」：调用方用它判断缓存命中，空语言只能是坏数据。
     */
    fun isSame(source: String, target: String): Boolean {
        val left = normalize(source)
        return left.isNotEmpty() && left == normalize(target)
    }

    /**
     * 翻译提示词里的目标语言名（§5 的 `{目标语言}` 占位符）。
     *
     * 认得的两种写语言名（模型对「中文 / English」的理解比对 `zh` / `en` 稳）；
     * 其它 BCP-47 tag 原样交给模型（§7：非 zh/en 时按 tag 传），空值回落到中文——
     * App 的默认语言就是中文，随便传个空串不该把提示词写成「翻译成」。
     */
    fun displayName(tag: String): String = when (normalize(tag)) {
        ZH -> DISPLAY_ZH
        EN -> DISPLAY_EN
        else -> tag.trim().ifBlank { DISPLAY_ZH }
    }

    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x4E00..0x9FFF || // 基本汉字
            codePoint in 0x3400..0x4DBF || // 扩展 A
            codePoint in 0xF900..0xFAFF || // 兼容汉字
            codePoint in 0x20000..0x2FA1F // 扩展 B 及以后
}

/**
 * 翻译的源字段：拼什么、按什么顺序、缓存如何失效（§3 / §4）。
 *
 * ## 语言检测用的文本
 *
 * 把参与翻译的字段按固定顺序用换行拼起来，只用来算 CJK 占比（§5）。这个顺序**不是**
 * `sourceHash` 的一部分——哈希看的是下面的规范化 JSON，不要在这里再加一个真相源。
 *
 * ## 缓存指纹（§4，0.12 规范更新）
 *
 * `sourceHash` = **规范化 JSON** 的 SHA-256：对象形状固定为
 * `{"persona":{…},"world":{…},"speech":{…}}`，**键按字典序排序、紧凑无空白**，
 * UTF-8 字节做 SHA-256，小写十六进制 + `sha256:` 前缀。
 *
 * 为什么从「字段用 NUL 拼起来」改成规范化 JSON：iOS 与 Android 以前各拼各的，
 * 字段顺序/分隔符稍有差别就会对同一份人设算出两个哈希；而规范化 JSON 是两端都能
 * 独立实现、且结果唯一的形式（`json_encode` 等价）。`phrases` **不参与**：它不翻译，
 * 改常用语不该让已经翻好的人设整体重翻（§3）。
 */
object PersonaTranslationSource {

    /**
     * 语言检测用的文本（字段顺序固定）。
     *
     * 人设/世界直接用库里存的 JSON 原文（而不是解析后的值）：解析会「顺手修」脏数据，
     * 而「是不是中文」应该跟着用户真正写下的东西走。话风例外：必须解析出
     * `tone/habits/avoid` 三项，才能把 `phrases` 排除在外（§3）。
     */
    private fun textFields(persona: PersonaEntity): List<String> = listOf(
        persona.name.trim(),
        persona.description.trim(),
        PersonaProfile.decodeList(persona.traits).joinToString("\n"),
        PersonaProfile.decodeList(persona.likes).joinToString("\n"),
        PersonaProfile.decodeList(persona.behaviors).joinToString("\n"),
        persona.backstory?.trim().orEmpty(),
        persona.quirks?.trim().orEmpty(),
        persona.prompt.trim(),
        persona.worldJson.orEmpty()
    ) + speechFields(persona.speechStyleJson)

    /**
     * 话风里**参与翻译**的三个字段；`phrases` 永不出现（§3：它不进请求、不进 `sourceHash`）。
     *
     * 解析坏了/为空 = 没有话风字段，返回空列表（不让坏 JSON 影响其它字段的哈希）。
     */
    private fun speechFields(raw: String?): List<String> {
        val style = SpeechStyleCodec.decode(raw) ?: return emptyList()
        return listOf(
            style.tone.trim(),
            style.habits.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n"),
            style.avoid.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
        )
    }

    /**
     * 语言检测用的拼接：字段之间用换行（空白不计入 CJK 占比，短人设也不会被分隔符带偏）。
     */
    fun sourceText(persona: PersonaEntity): String = textFields(persona).joinToString("\n")

    /**
     * 参与哈希的规范化 JSON（§4 / §4.1）。公开给测试，用来逐字钉住「键有序、无空白、UTF-8」。
     *
     * 形状（§4.1 定死，**不随世界/话风是不是空而增删键**）：
     * ```json
     * {"persona":{"backstory":"…","behaviors":[],"description":"…","likes":[],
     *             "name":"…","quirks":"…","systemPrompt":"…","traits":[]},
     *  "speech":{"avoid":[],"habits":[],"tone":"…"} | null,
     *  "world":{"description":"…","era":"…","notes":"…","obligations":[],
     *           "relationships":[…],"schedule":[…],"worldName":"…"} | null}
     * ```
     * 三条容易写错的规则：
     * - **显式构造固定键**，不再复用 [WorldPresetCodec.encode]：那套「省略空键/过滤空项」是人格列的
     *   存储规则，不是哈希协议；哈希的键集合必须两端逐键一致，空串/空数组照写；
     * - **值原样**：这里绝不再 `trim()` / 过滤空项（世界/话风在解码阶段已经归一过，那是解析的事——
     *   规范化阶段二次加工只会让两端对同一份人格算出两个哈希）；
     * - `speech` 的「全空」只看 `tone/habits/avoid`，**不看 `phrases`**（§3：phrases 不翻译、
     *   不进哈希；否则只改常用语就会经由 null ↔ 空对象翻转改变哈希）。
     *
     * 键顺序由 [canonicalJson] 递归排序，不依赖构造顺序。
     */
    fun canonicalSourceJson(persona: PersonaEntity): String = canonicalJson(
        buildJsonObject {
            put(
                "persona",
                buildJsonObject {
                    // 人设字段就是库里那一列的原值：不 trim（§4.1 第 6 条）。
                    put("name", JsonPrimitive(persona.name))
                    put("description", JsonPrimitive(persona.description))
                    put("traits", stringArray(PersonaProfile.decodeList(persona.traits)))
                    put("likes", stringArray(PersonaProfile.decodeList(persona.likes)))
                    put("behaviors", stringArray(PersonaProfile.decodeList(persona.behaviors)))
                    put("backstory", JsonPrimitive(persona.backstory.orEmpty()))
                    put("quirks", JsonPrimitive(persona.quirks.orEmpty()))
                    put("systemPrompt", JsonPrimitive(persona.prompt))
                }
            )
            val world = WorldPresetCodec.decode(persona.worldJson)?.takeUnless { it.isEmpty() }
            put("world", world?.let(::canonicalWorldObject) ?: JsonNull)
            val speech = SpeechStyleCodec.decode(persona.speechStyleJson)
                ?.takeUnless { it.translatableFieldsEmpty() }
            put(
                "speech",
                speech?.let {
                    buildJsonObject {
                        put("tone", JsonPrimitive(it.tone))
                        put("habits", stringArray(it.habits))
                        put("avoid", stringArray(it.avoid))
                    }
                } ?: JsonNull
            )
        }
    )

    /**
     * `world` 的固定 7 键（§4.1 第 3 条）。
     *
     * `relationships[].intimacy` 只在非 null 时写：null 与「没填」在规范里同义，写 `"intimacy":null`
     * 会让键集合随数据变化，跨端对不上。
     */
    private fun canonicalWorldObject(world: WorldPreset): JsonObject = buildJsonObject {
        put("worldName", JsonPrimitive(world.worldName))
        put("era", JsonPrimitive(world.era))
        put("description", JsonPrimitive(world.description))
        put(
            "schedule",
            buildJsonArray {
                world.schedule.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("day", JsonPrimitive(entry.day))
                            put("start", JsonPrimitive(entry.start))
                            put("end", JsonPrimitive(entry.end))
                            put("activity", JsonPrimitive(entry.activity))
                        }
                    )
                }
            }
        )
        put(
            "relationships",
            buildJsonArray {
                world.relationships.forEach { person ->
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive(person.name))
                            put("relation", JsonPrimitive(person.relation))
                            put("note", JsonPrimitive(person.note))
                            person.intimacy?.let { put("intimacy", JsonPrimitive(it)) }
                        }
                    )
                }
            }
        )
        put("obligations", stringArray(world.obligations))
        put("notes", JsonPrimitive(world.notes))
    }

    /**
     * 缓存指纹：`"sha256:" + 小写 hex`（§4）。
     *
     * 输入是 [canonicalSourceJson] 的 UTF-8 字节，不再自己拼分隔符：字段值里出现 NUL
     * 或换行都不会改变「哪一段属于哪个字段」。
     */
    fun hashOf(persona: PersonaEntity): String =
        "sha256:" + sha256(canonicalSourceJson(persona))

    /**
     * 这次要不要发请求（`spec/persona-translation` §2 / §7）。
     *
     * 三件事按顺序：源语言 == 目标语言 → 不翻；缓存能命中（哈希 + 语言都对）→ 不翻；
     * 其余都翻。它只看 [PersonaEntity.translationJson]，不读数据库、不写缓存——
     * 落库是调用方的活（翻译只是一次网络调用，不该偷偷改数据）。
     */
    fun needsTranslation(persona: PersonaEntity, targetLang: String): Boolean {
        val target = PersonaLanguage.normalize(targetLang)
        val source = PersonaLanguage.detect(sourceText(persona))
        if (PersonaLanguage.isSame(source, target)) return false
        val cache = PersonaTranslationCodec.decode(persona.translationJson)
        return cache == null || cache.isEmpty() || !cache.matches(hashOf(persona), targetLang)
    }

    // ---------------------------------------------------------------- 规范化 JSON

    private fun stringArray(values: List<String>): JsonArray =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    /**
     * 递归规范化：对象键按字典序、数组保序、字符串转义交给 kotlinx（与 JSON 规范一致）、
     * 数字/布尔原样、无任何多余空白。`JsonObject` 的默认 `toString()` 用的是插入顺序，
     * 所以不能直接拿来算哈希。
     */
    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                JsonPrimitive(key).toString() + ":" + canonicalJson(value)
            }

        is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) {
            // 用 kotlinx 生成带引号/转义的 JSON 字符串：引号、反斜杠、控制字符、emoji 都正确。
            JsonPrimitive(element.content).toString()
        } else {
            element.content
        }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}
