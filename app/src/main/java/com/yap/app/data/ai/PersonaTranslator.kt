package com.yap.app.data.ai

import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.PersonaProfile
import com.yap.app.domain.PersonaLanguage
import com.yap.app.domain.PersonaTranslation
import com.yap.app.domain.PersonaTranslationSource
import com.yap.app.domain.SpeechStyle
import com.yap.app.domain.SpeechStyleCodec
import com.yap.app.domain.TranslatedPersona
import com.yap.app.domain.UsageKind
import com.yap.app.domain.WorldPreset
import com.yap.app.domain.WorldPresetCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次翻译的结果。
 *
 * [ok] 表示**这一次有没有真的拿到可用的译文**；界面据此显示「翻译失败，可重试」（§7）。
 * [skipped] 表示源语言 == 目标语言，压根没发请求（零网络、零计费）；
 * 它不算失败，[translation] 里只有 lang/hash，没有正文（调用方直接用原文）。
 */
data class PersonaTranslationResult(
    val translation: PersonaTranslation,
    val ok: Boolean,
    val skipped: Boolean = false
) {
    /** 需要提示用户「翻译失败」的情形（跳过不算，无 Key 静默回退也不算提示，§7）。 */
    val failed: Boolean get() = !ok && !skipped
}

/**
 * 人设/世界预设在「开始人格」之前翻译成 App 当前语言（`spec/persona-translation/README.md`）。
 *
 * 这一段只负责**一次翻译**：拿 [PersonaEntity] 的源字段 → 一份 [PersonaTranslation]。
 * **去重/命中缓存/落库（`translationJson` / `translationLang`）是调用方（协调器）的活**：
 * 什么时候翻、要不要翻由 [PersonaTranslationSource.needsTranslation] 判断；
 * 这个类**每次调用最多发一个请求**，不缓存、不并发去重、不写库——它没有 DB 依赖，
 * 所以单测能直接跑（用假 [JsonLlmClient]）。
 *
 * 隐私（§6）：只发用户自己写的人设/世界字段，**不发聊天记录、记忆、API Key**；
 * 请求原文既不打日志也不落库（这个类里没有任何 Logger/DAO，是刻意省掉的）。
 *
 * 翻译范围与缓存形状（§3 / §4）：`persona` + `world` + `speech{tone,habits,avoid}`；
 * `speech.phrases` **永不进请求、也不进 `sourceHash`**（§3：翻译会毁掉语感）。
 * 两端（Android / iOS）请求键与缓存键完全一致，缓存 JSON 里 `world` / `speech` 缺省写 `null`。
 */
@Singleton
class PersonaTranslator @Inject constructor(
    private val llm: JsonLlmClient
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * 翻译一次。
     *
     * 四种出口：
     * 1. 源语言 == 目标语言 → [PersonaTranslationResult.skipped]，零请求；
     * 2. 成功 → `ok = true`，人设/世界逐字段替换成译文；
     * 3. 回复是坏 JSON / 缺字段 → 缺的字段逐个回退原文（不是整份丢弃，§7），`ok = true` 只要
     *    至少有人设或世界对象可用；
     * 4. 没配置 / 调用失败 / 超时 → 全部用原文，`ok = false`（§7：不阻塞聊天，下次再试）。
     *
     * @param targetLang App 当前语言（BCP-47）；空值时按中文处理——App 默认语言就是中文。
     * @param timeoutMs 超时（§5：8s）；超时按失败处理，不抛给调用方。
     * @param now 写进 [PersonaTranslation.translatedAt] 的时间戳，测试可注入，免得依赖时钟。
     */
    suspend fun translate(
        persona: PersonaEntity,
        targetLang: String,
        configId: String? = null,
        timeoutMs: Long = TIMEOUT_MS,
        now: Long = System.currentTimeMillis()
    ): PersonaTranslationResult {
        val lang = targetLang.trim().ifBlank { PersonaLanguage.ZH }
        val sourceHash = PersonaTranslationSource.hashOf(persona)
        val originalPersona = originalPersona(persona)
        val originalWorld = originalWorld(persona)
        val originalSpeech = originalSpeechOf(persona)

        val sourceLang = PersonaLanguage.detect(PersonaTranslationSource.sourceText(persona))
        if (PersonaLanguage.isSame(sourceLang, lang)) {
            // 源 = 目标：一个字都不用翻。返回的正文是空的（调用方用原文），
            // 但 lang/hash 留着——这份写进缓存后，下次命中就直接跳过，零请求。
            return PersonaTranslationResult(
                translation = PersonaTranslation(
                    lang = lang,
                    translatedAt = now,
                    sourceHash = sourceHash
                ),
                ok = true,
                skipped = true
            )
        }

        val raw = call(
            system = systemPrompt(lang),
            user = requestJson(originalPersona, originalWorld, originalSpeech),
            configId = configId,
            timeoutMs = timeoutMs
        )
        if (raw.isNullOrBlank()) {
            return fallback(lang, sourceHash, now, originalPersona, originalWorld, originalSpeech)
        }

        val root = GuidanceJson.firstObject(raw)
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return fallback(lang, sourceHash, now, originalPersona, originalWorld, originalSpeech)
        val personaObject = root["persona"] as? JsonObject
        val worldObject = root["world"] as? JsonObject
        val speechObject = root["speech"] as? JsonObject
        // 一个对象都没捞到（比如只回了个 {"error":…}）等于白翻：原文 + 失败标记，别把
        // 「全字段回退」伪装成成功，否则界面不会提示可重试。
        if (personaObject == null && worldObject == null && speechObject == null) {
            return fallback(lang, sourceHash, now, originalPersona, originalWorld, originalSpeech)
        }

        return PersonaTranslationResult(
            translation = PersonaTranslation(
                lang = lang,
                translatedAt = now,
                sourceHash = sourceHash,
                persona = mergePersona(personaObject, originalPersona),
                world = mergeWorld(worldObject, originalWorld),
                speech = mergeSpeech(speechObject, originalSpeech)
            ),
            ok = true
        )
    }

    // ---- 请求 ------------------------------------------------------------------------------

    /** 一次调用，包在超时 + 容错里；超时/异常都变成 `null`，由调用方走原文。 */
    private suspend fun call(
        system: String,
        user: String,
        configId: String?,
        timeoutMs: Long
    ): String? {
        val outcome = runCatching {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                llm.completeJson(
                    system = system,
                    user = user,
                    configId = configId,
                    kind = UsageKind.TRANSLATION,
                    temperature = TEMPERATURE,
                    maxOutputTokens = MAX_OUTPUT_TOKENS
                )
            }
        }
        // runCatching 会把「协程被取消」一起吞掉：页面退出/换会话时的取消必须继续往上抛，
        // 否则这个已经被取消的协程还会去回写缓存、记账。
        outcome.exceptionOrNull()?.let { error -> if (error is CancellationException) throw error }
        return outcome.getOrNull()
    }

    /**
     * 用户消息：`persona` + `world` + `speech`（§3 / §5，与 iOS 同形状）。
     *
     * - `speech` 只发 `tone/habits/avoid`；`phrases` 绝不出现（§3：翻它会毁掉语感）；
     * - 没有世界预设 / 没有话风时不发对应的键：发了模型就会顺手编一个，凭空多出来的设定会改掉人设；
     * - 段内空字段也保留键名：提示词要求「键名原样保留，不要增删键」，
     *   请求里先少一个键，模型照做时就会少回来一个键。
     */
    private fun requestJson(
        persona: TranslatedPersona,
        world: WorldPreset?,
        speech: SpeechStyle?
    ): String {
        val payload = buildJsonObject {
            put(
                "persona",
                buildJsonObject {
                    put("name", JsonPrimitive(persona.name))
                    put("description", JsonPrimitive(persona.description))
                    put("traits", JsonArray(persona.traits.map(::JsonPrimitive)))
                    put("likes", JsonArray(persona.likes.map(::JsonPrimitive)))
                    put("behaviors", JsonArray(persona.behaviors.map(::JsonPrimitive)))
                    put("backstory", JsonPrimitive(persona.backstory))
                    put("quirks", JsonPrimitive(persona.quirks))
                    put("systemPrompt", JsonPrimitive(persona.systemPrompt))
                }
            )
            if (world != null) {
                put("world", json.parseToJsonElement(WorldPresetCodec.encode(world)))
            }
            if (speech != null && !speech.isEmpty()) {
                put(
                    "speech",
                    buildJsonObject {
                        put("tone", JsonPrimitive(speech.tone.trim()))
                        put("habits", JsonArray(speech.habits.map { it.trim() }.filter { it.isNotBlank() }.map(::JsonPrimitive)))
                        put("avoid", JsonArray(speech.avoid.map { it.trim() }.filter { it.isNotBlank() }.map(::JsonPrimitive)))
                    }
                )
            }
        }
        return payload.toString()
    }

    // ---- 解析与逐字段回退 --------------------------------------------------------------------

    /** 人设：某个字段缺失/空白/类型不对，就单独用原文，绝不整份丢弃（§7）。 */
    private fun mergePersona(source: JsonObject?, original: TranslatedPersona): TranslatedPersona {
        if (source == null) return original
        return TranslatedPersona(
            name = source.textOr("name", original.name, MAX_NAME),
            description = source.textOr("description", original.description, MAX_DESCRIPTION),
            traits = source.listOr("traits", original.traits, PersonaProfile.MAX_TRAITS),
            likes = source.listOr("likes", original.likes, PersonaProfile.MAX_LIKES),
            behaviors = source.listOr("behaviors", original.behaviors, PersonaProfile.MAX_BEHAVIORS),
            backstory = source.textOr("backstory", original.backstory, MAX_BACKSTORY),
            quirks = source.textOr("quirks", original.quirks, MAX_QUIRKS),
            // 人设提示词不截断：截一半的提示词比中文原文更糟；长度由调用方的 prompt 上限管。
            systemPrompt = source.textOr("systemPrompt", original.systemPrompt)
        )
    }

    /** 世界：逐字段回退原文；原文没有世界时，模型多给的 world 也不采用（见 [requestJson]）。 */
    private fun mergeWorld(source: JsonObject?, original: WorldPreset?): WorldPreset? {
        if (original == null) return null
        if (source == null) return original
        val decoded = WorldPresetCodec.decode(repairWorld(source, original).toString()) ?: return original
        // 亲密度是 Android 端的可选扩展（`WorldRelationship.intimacy`），README §3 里没有它，
        // 模型很可能直接漏掉。条数对得上就按同下标补回原文，否则宁可不要也不乱配。
        val relationships = if (decoded.relationships.size == original.relationships.size) {
            decoded.relationships.mapIndexed { index, relation ->
                val before = original.relationships[index].intimacy
                if (relation.intimacy == null && before != null) relation.copy(intimacy = before) else relation
            }
        } else {
            decoded.relationships
        }
        return WorldPreset(
            worldName = decoded.worldName.ifBlank { original.worldName }.take(MAX_NAME),
            era = decoded.era.ifBlank { original.era },
            description = decoded.description.ifBlank { original.description }.take(MAX_WORLD_DESCRIPTION),
            // 日程/关系/义务没法「按条目对齐回退」（模型可能少给、乱序），整组为空才退回原文。
            schedule = decoded.schedule.ifEmpty { original.schedule },
            relationships = relationships.ifEmpty { original.relationships },
            obligations = decoded.obligations.ifEmpty { original.obligations },
            notes = decoded.notes.ifBlank { original.notes }.take(WorldPreset.MAX_NOTES_CHARS)
        ).takeIf { !it.isEmpty() }
    }

    /**
     * 话风：逐字段回退原文；`phrases` 永远用原文（不翻译、也不接受模型改动）。
     *
     * 模型没给 `speech` 整段、或某个字段缺失/类型不对 → 那个字段用原文（§7）。
     * 没有话风的人（原文为 null）即使模型多给了 `speech` 也不采用（同 [mergeWorld] 的规则）。
     */
    private fun mergeSpeech(source: JsonObject?, original: SpeechStyle?): SpeechStyle? {
        if (original == null) return null
        if (source == null) return original
        return SpeechStyle(
            tone = source.textOr("tone", original.tone, MAX_TONE),
            phrases = original.phrases,
            habits = source.listOr("habits", original.habits, MAX_SPEECH_ITEMS),
            avoid = source.listOr("avoid", original.avoid, MAX_SPEECH_ITEMS)
        )
    }

    /**
     * 日程修复：`day` 只认中文白名单（[WorldPreset.ALLOWED_DAYS]）。
     *
     * README §3 把 `schedule[].day` 也列进翻译字段，但 [WorldPresetCodec.decode] 见到
     * 「Weekday」这种 day 会把**整条**日程丢掉。这里把 day / 起止时间按原文逐条补回去，
     * 只保留译好的 activity：宁可出现「工作日 09:00-18:30 画图」这种半译，
     * 也不要把一整天的作息弄没（那会让角色忘记自己今天在上班）。
     */
    private fun repairWorld(source: JsonObject, original: WorldPreset): JsonObject {
        val entries = source["schedule"] as? JsonArray ?: return source
        if (original.schedule.isEmpty()) return source
        val repaired = entries.mapIndexed { index, element ->
            val entry = element as? JsonObject ?: return@mapIndexed element
            val fallback = original.schedule.getOrNull(index) ?: return@mapIndexed entry
            val day = entry.rawString("day").orEmpty()
            val start = entry.rawString("start").orEmpty()
            val end = entry.rawString("end").orEmpty()
            val activity = entry.rawString("activity").orEmpty()
            JsonObject(
                buildMap<String, JsonElement> {
                    put("day", JsonPrimitive(day.takeIf { it in WorldPreset.ALLOWED_DAYS } ?: fallback.day))
                    put(
                        "start",
                        JsonPrimitive(start.takeIf { WorldPreset.minuteOfDay(it) != null } ?: fallback.start)
                    )
                    put("end", JsonPrimitive(end.takeIf { WorldPreset.minuteOfDay(it) != null } ?: fallback.end))
                    put("activity", JsonPrimitive(activity.ifBlank { fallback.activity }))
                }
            )
        }
        return JsonObject(buildMap<String, JsonElement> {
            putAll(source)
            put("schedule", JsonArray(repaired))
        })
    }

    /** 失败/超时的统一出口：原文 + lang/hash，`ok = false`（§7）。 */
    private fun fallback(
        lang: String,
        sourceHash: String,
        now: Long,
        persona: TranslatedPersona,
        world: WorldPreset?,
        speech: SpeechStyle?
    ): PersonaTranslationResult = PersonaTranslationResult(
        translation = PersonaTranslation(
            lang = lang,
            translatedAt = now,
            sourceHash = sourceHash,
            persona = persona,
            world = world,
            speech = speech
        ),
        ok = false
    )

    // ---- 原文 ------------------------------------------------------------------------------

    /** 原文的人设正文（回退用），顺手按仓库其它地方的上限截断。 */
    private fun originalPersona(persona: PersonaEntity): TranslatedPersona = TranslatedPersona(
        name = persona.name.trim().take(MAX_NAME),
        description = persona.description.trim().take(MAX_DESCRIPTION),
        traits = PersonaProfile.sanitizeList(
            PersonaProfile.decodeList(persona.traits), PersonaProfile.MAX_TRAITS
        ),
        likes = PersonaProfile.sanitizeList(
            PersonaProfile.decodeList(persona.likes), PersonaProfile.MAX_LIKES
        ),
        behaviors = PersonaProfile.sanitizeList(
            PersonaProfile.decodeList(persona.behaviors), PersonaProfile.MAX_BEHAVIORS
        ),
        backstory = persona.backstory?.trim()?.take(MAX_BACKSTORY).orEmpty(),
        quirks = persona.quirks?.trim()?.take(MAX_QUIRKS).orEmpty(),
        systemPrompt = persona.prompt.trim()
    )

    /** 原文的世界预设；没有 / 全空都是 `null`（不伪造空世界）。 */
    private fun originalWorld(persona: PersonaEntity): WorldPreset? =
        WorldPresetCodec.decode(persona.worldJson)?.takeIf { !it.isEmpty() }

    /** 原文的话风；没有 / 全空都是 `null`。`phrases` 会保留在缓存里供回退/应用时使用。 */
    private fun originalSpeechOf(persona: PersonaEntity): SpeechStyle? =
        SpeechStyleCodec.decode(persona.speechStyleJson)?.takeIf { !it.isEmpty() }

    // ---- JSON 小工具（宽容：缺 / 空白 / 类型不对一律走 fallback） -----------------------------

    private fun JsonObject.rawString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()

    private fun JsonObject.textOr(key: String, fallback: String, max: Int = Int.MAX_VALUE): String {
        val value = rawString(key)
        return if (value.isNullOrBlank()) fallback else value.take(max)
    }

    /** 数组字段：不是数组 / 全空白 → 原文；有条目就接受（少一两条不逐条补齐，模型可能合并了同类项）。 */
    private fun JsonObject.listOr(key: String, fallback: List<String>, max: Int): List<String> {
        val array = this[key] as? JsonArray ?: return fallback
        val items = array.mapNotNull { element ->
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            if (!primitive.isString) return@mapNotNull null
            primitive.content.trim().takeIf { it.isNotBlank() }
        }
        return if (items.isEmpty()) fallback else PersonaProfile.sanitizeList(items, max)
    }

    companion object {
        /** §5 的调用参数与超时：温度 0.2、8 秒。 */
        const val TIMEOUT_MS = 8_000L
        const val TEMPERATURE = 0.2

        /**
         * 输出上限：整份人设 + 世界 JSON，比总结长得多。
         * 给小了会被截断成坏 JSON——那等于每次翻译都失败，还照样扣一次钱。
         */
        const val MAX_OUTPUT_TOKENS = 4_000

        /** 与仓库其它地方一致的长度上限（角色名 60、简介/过去 600、设定 300、世界 notes 1200）。 */
        const val MAX_NAME = 60
        const val MAX_DESCRIPTION = 600
        const val MAX_BACKSTORY = 600
        const val MAX_QUIRKS = 300
        const val MAX_WORLD_DESCRIPTION = 600

        /** 话风译文的上限（与编辑器/总结器保持同一量级，不发明新的规范）。 */
        const val MAX_TONE = 120
        const val MAX_SPEECH_ITEMS = 20

        /** §5 原文里的唯一占位符。 */
        const val LANG_PLACEHOLDER = "{目标语言}"

        /** §5 的提示词原文，逐字照抄；只把 [LANG_PLACEHOLDER] 换掉，其余一个字都不改。 */
        val PROMPT_TEMPLATE: String = """
            你是翻译器。把给定的人设/世界设定翻译成{目标语言}，保留语气、称呼与专有名词的意译一致性。
            只输出 JSON，键名原样保留，不要增删键，不要翻译 phrases 数组，不要解释：
            { "persona": {…}, "world": {…}, "speech": {…} }
            规则：
            - 专有名词首次出现可音译，之后保持一致；
            - 不要润色成书面语，保持口语性格；
            - 不确定的短语可保留原文。
        """.trimIndent()

        /** 目标语言的名（`中文` / `English` / 其它 tag 原样）由 [PersonaLanguage.displayName] 决定。 */
        fun systemPrompt(targetLang: String): String =
            PROMPT_TEMPLATE.replace(LANG_PLACEHOLDER, PersonaLanguage.displayName(targetLang))
    }
}
