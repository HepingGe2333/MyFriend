package com.myfriend.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlin.math.roundToInt

/**
 * 结构化人设卡。
 *
 * 和旧版「一大段提示词」相比，人设卡把「你是谁 / 怎么说话 / 性格 / 在意什么 / 边界 / 生活 / 你们之间」
 * 拆成可单独修改的字段：系统提示词里只留 [PersonaCardPrompt.stableStrategy] 这样一条短策略，
 * 正文由 [PersonaCardPrompt.renderContext] 按预算渲染成运行时上下文。
 * 模型可以在对话里通过 [PersonaCardPatch] 打补丁生成新版本，旧版本留在上层用于回滚。
 *
 * 所有字段都有安全默认值；任何解析、渲染入口都不应该抛异常。
 */

/** 一个性格标签，[strength] 表示这个特质有多强（0..1）。 */
@Serializable
data class PersonaTrait(val name: String = "", val strength: Float = 0.5f)

/** 身份层：名字、年龄感、身份/职业、和「你」的关系。 */
@Serializable
data class PersonaIdentity(
    val name: String = "",
    val age: String = "",              // 年龄感，如 "二十多岁"
    val role: String = "",             // 身份/职业
    val relation: String = ""          // 和「你」是什么关系
)

/** 声音层：语速、句子长度、口头禅、称呼、语气小习惯。 */
@Serializable
data class PersonaVoice(
    val pace: String = "",             // 语速，如 "慢，偶尔停顿"
    val sentence: String = "",         // 句子长度偏好
    val catchphrases: List<String> = emptyList(),  // 口头禅
    val address: String = "",          // 怎么称呼对方
    val quirks: String = ""            // 标点/表情/语气小习惯
)

/** 生活层：作息、当下在做什么、最近关心什么。属于最容易随对话变化、也最先被裁剪的细节。 */
@Serializable
data class PersonaLife(
    val schedule: String = "",         // 作息
    val doing: String = "",            // 现在正在做什么
    val caresAbout: String = ""        // 最近在关心什么
)

/** 一张完整的人设卡。 */
@Serializable
data class PersonaCard(
    val identity: PersonaIdentity = PersonaIdentity(),
    val voice: PersonaVoice = PersonaVoice(),
    val traits: List<PersonaTrait> = emptyList(),
    val values: List<String> = emptyList(),
    val boundaries: List<String> = emptyList(),
    val life: PersonaLife = PersonaLife(),
    val bond: String = ""              // 对「你」的认知与变化
) {
    /** 卡里是否还没有任何有效内容（空卡走稳定兜底策略）。 */
    fun isEmpty(): Boolean =
        identity.name.isBlank() &&
            identity.age.isBlank() &&
            identity.role.isBlank() &&
            identity.relation.isBlank() &&
            voice.pace.isBlank() &&
            voice.sentence.isBlank() &&
            voice.catchphrases.none { it.isNotBlank() } &&
            voice.address.isBlank() &&
            voice.quirks.isBlank() &&
            traits.none { it.name.isNotBlank() } &&
            values.none { it.isNotBlank() } &&
            boundaries.none { it.isNotBlank() } &&
            life.schedule.isBlank() &&
            life.doing.isBlank() &&
            life.caresAbout.isBlank() &&
            bond.isBlank()
}

/** 三档预算：注入正文的字数上限。 */
enum class PromptBudget(val maxChars: Int) {
    LEAN(600),
    NORMAL(1400),
    RICH(2600)
}

// ---------------------------------------------------------------------------
// 内部上限：字段级防御性裁剪，避免异常输入把上下文撑爆（正常内容不会触发）。
// ---------------------------------------------------------------------------

private const val NAME_MAX = 40
private const val ROLE_MAX = 80
private const val FIELD_MAX = 240
private const val LIST_ITEM_MAX = 60
private const val BOND_MAX = 2000
private const val BOND_RENDER_MAX = 400
private const val MAX_TRAITS = 12
private const val MAX_LIST_ITEMS = 8
private const val MAX_LEGACY_BOUNDARIES = 8
private const val MAX_LEGACY_PACE_PARTS = 4

private val WHITESPACE_RUN = Regex("\\s+")

/** 折成一行并限制长度；超长时尽量在句读处停下，实在没有句读才补省略号。 */
private fun String.cleaned(max: Int): String {
    val oneLine = trim().replace('\n', ' ').replace('\r', ' ').replace(WHITESPACE_RUN, " ").trim()
    if (oneLine.length <= max) return oneLine
    val cut = oneLine.take(max - 1)
    val boundary = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '；', '，', '、', ' '))
    return if (boundary >= max / 2) cut.take(boundary + 1).trimEnd() else cut.trimEnd() + "…"
}

/** 把任意来源的卡收敛到安全范围：去空项、夹紧 strength、限制字段长度。 */
private fun PersonaCard.sanitized(): PersonaCard = PersonaCard(
    identity = PersonaIdentity(
        name = identity.name.cleaned(NAME_MAX),
        age = identity.age.cleaned(NAME_MAX),
        role = identity.role.cleaned(ROLE_MAX),
        relation = identity.relation.cleaned(NAME_MAX)
    ),
    voice = PersonaVoice(
        pace = voice.pace.cleaned(FIELD_MAX),
        sentence = voice.sentence.cleaned(FIELD_MAX),
        catchphrases = voice.catchphrases
            .map { it.cleaned(LIST_ITEM_MAX) }
            .filter { it.isNotBlank() }
            .take(MAX_LIST_ITEMS),
        address = voice.address.cleaned(NAME_MAX),
        quirks = voice.quirks.cleaned(FIELD_MAX)
    ),
    traits = traits
        .map { PersonaTrait(name = it.name.cleaned(NAME_MAX), strength = it.strength.coerceIn(0f, 1f)) }
        .filter { it.name.isNotBlank() }
        .take(MAX_TRAITS),
    values = values.map { it.cleaned(LIST_ITEM_MAX) }.filter { it.isNotBlank() }.take(MAX_LIST_ITEMS),
    boundaries = boundaries.map { it.cleaned(LIST_ITEM_MAX) }.filter { it.isNotBlank() }.take(MAX_LIST_ITEMS),
    life = PersonaLife(
        schedule = life.schedule.cleaned(FIELD_MAX),
        doing = life.doing.cleaned(FIELD_MAX),
        caresAbout = life.caresAbout.cleaned(FIELD_MAX)
    ),
    bond = bond.cleaned(BOND_MAX)
)

/** 人设卡的 JSON 编解码。用于本地持久化，不参与网络协议。 */
object PersonaCardCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = true
    }

    fun encode(card: PersonaCard): String =
        runCatching { json.encodeToString(card) }.getOrDefault("{}")

    /** null / 空串 / 坏 JSON / 类型不匹配都返回空卡，绝不抛。 */
    fun decode(raw: String?): PersonaCard {
        if (raw.isNullOrBlank()) return PersonaCard()
        return runCatching { json.decodeFromString<PersonaCard>(raw) }
            .map { it.sanitized() }
            .getOrDefault(PersonaCard())
    }
}

/**
 * 人设卡 -> 提示词。
 *
 * 分成两层：
 * - [stableStrategy] 很短、很稳定，放系统提示词最前面，任何预算下都不删；
 * - [renderContext] 是随预算伸缩的正文，按「细节先掉、身份与声音最后掉」的顺序整段丢弃。
 */
object PersonaCardPrompt {

    private const val STRATEGY_MAX = 80
    private const val STRATEGY_FALLBACK = "用自然、简短、像真人发消息的方式说话。"
    private const val STRATEGY_TAIL = "；先回应再展开，别解释自己是 AI。"
    private const val PLACEHOLDER = "（还没有设定）"
    private const val PROTECTED_PRIORITY = 100

    // 各段落/行的丢弃优先级：数字越小越先被丢掉。
    private const val P_LIFE = 8
    private const val P_QUIRK = 12
    private const val P_BOND = 34
    private const val P_VALUES = 52
    private const val P_BOUNDARIES = 56
    private const val P_CATCHPHRASE = 60
    private const val P_TRAIT = 64
    private const val P_SENTENCE = 76
    private const val P_AGE = 80
    private const val P_ADDRESS = 82
    private const val P_RELATION = 84
    private const val P_ROLE = 88

    /** 约 60 字以内的稳定策略；空卡返回通用兜底，保证长度不超过 80 字。 */
    fun stableStrategy(card: PersonaCard): String {
        val clean = card.sanitized()
        val name = clean.identity.name.ifBlank { clean.identity.role }
        if (clean.isEmpty() || name.isBlank()) return STRATEGY_FALLBACK

        var result = "你是${name.cleaned(16)}"
        val extras = buildList {
            if (clean.identity.role.isNotBlank() && clean.identity.name.isNotBlank()) {
                add("，" + clean.identity.role.cleaned(16))
            }
            if (clean.identity.relation.isNotBlank()) {
                add("，对方的" + clean.identity.relation.cleaned(12))
            }
            if (clean.voice.address.isNotBlank()) {
                add("，称呼对方「" + clean.voice.address.cleaned(8) + "」")
            }
            if (clean.voice.pace.isNotBlank()) {
                // 旧提示词里常写成「你说话从容、克制」「语速偏慢」，先剥掉主语再拼，免得出现「说话你说话…」
                val pace = clean.voice.pace.cleaned(12)
                    .removePrefix("你说话").removePrefix("我说话").removePrefix("说话")
                    .removePrefix("语速").removePrefix("：").removePrefix("，")
                    .trim()
                if (pace.isNotBlank()) add("，说话" + pace)
            }
            clean.traits.firstOrNull()?.let { add("，" + it.name.cleaned(10)) }
        }
        for (extra in extras) {
            if (result.length + extra.length + STRATEGY_TAIL.length <= STRATEGY_MAX) {
                result += extra
            }
        }
        result += STRATEGY_TAIL
        if (result.length > STRATEGY_MAX) result = result.take(STRATEGY_MAX)
        return result.trim()
    }

    /**
     * 人设卡正文 -> Markdown 段落，按 [budget] 裁剪。
     *
     * 裁剪单位是整行（不会从句子里截断）：生活细节、语气小习惯最先掉，
     * 身份与声音至少各保留一行；两段标题始终输出，空内容用占位句兜底。
     */
    fun renderContext(card: PersonaCard, budget: PromptBudget = PromptBudget.NORMAL): String {
        val sections = buildSections(card.sanitized())
        val kept = sections.flatMap { it.lines }.map { it.id }.toMutableSet()
        var rendered = renderSections(sections, kept)

        if (rendered.length > budget.maxChars) {
            val droppable = sections.flatMap { it.lines }
                .filter { it.priority < PROTECTED_PRIORITY }
                .sortedBy { it.priority }
            for (line in droppable) {
                if (rendered.length <= budget.maxChars) break
                if (kept.remove(line.id)) rendered = renderSections(sections, kept)
            }
        }
        if (rendered.length > budget.maxChars) rendered = truncateToBudget(rendered, budget.maxChars)
        return rendered.trimEnd()
    }

    /**
     * 从旧版「一大段提示词」尽力解析出人设卡。
     *
     * 识别策略见 [parseLegacy]；识别不出的原文整体保留在 bond（≤2000 字时全文保留），
     * 并补一个默认 trait，保证旧人格不会因为结构化而丢失设定。
     */
    fun fromLegacyPrompt(prompt: String): PersonaCard {
        val raw = prompt.trim()
        if (raw.isEmpty()) return PersonaCard()
        return runCatching { parseLegacy(raw) }
            .getOrElse { PersonaCard(bond = raw.take(BOND_MAX)).sanitized() }
    }

    // -----------------------------------------------------------------------
    // 正文裁剪的实现
    // -----------------------------------------------------------------------

    private class Line(val id: Int, val text: String, val priority: Int)
    private class Section(val title: String, val required: Boolean, val lines: List<Line>)

    private fun buildSections(card: PersonaCard): List<Section> {
        var nextId = 0
        fun line(text: String, priority: Int) = Line(nextId++, text, priority)

        // 你是谁：名字/年龄感/身份/关系，第一行受保护。
        val identityLines = mutableListOf<Line>()
        fun addIdentity(text: String, priority: Int) {
            identityLines += line(text, if (identityLines.isEmpty()) PROTECTED_PRIORITY else priority)
        }
        if (card.identity.name.isNotBlank()) addIdentity("- 名字：${card.identity.name}", P_ROLE)
        if (card.identity.age.isNotBlank()) addIdentity("- 年龄感：${card.identity.age}", P_AGE)
        if (card.identity.role.isNotBlank()) addIdentity("- 身份：${card.identity.role}", P_ROLE)
        if (card.identity.relation.isNotBlank()) addIdentity("- 和你的关系：${card.identity.relation}", P_RELATION)

        // 说话方式：语速/句子/口头禅/称呼/小习惯，第一行受保护。
        val voiceLines = mutableListOf<Line>()
        fun addVoice(text: String, priority: Int) {
            voiceLines += line(text, if (voiceLines.isEmpty()) PROTECTED_PRIORITY else priority)
        }
        if (card.voice.pace.isNotBlank()) addVoice("- 语速：${card.voice.pace}", P_ADDRESS)
        if (card.voice.sentence.isNotBlank()) addVoice("- 句子：${card.voice.sentence}", P_SENTENCE)
        if (card.voice.catchphrases.isNotEmpty()) {
            addVoice("- 口头禅：${card.voice.catchphrases.joinToString("、") { "「$it」" }}", P_CATCHPHRASE)
        }
        if (card.voice.address.isNotBlank()) addVoice("- 称呼你：${card.voice.address}", P_ADDRESS)
        if (card.voice.quirks.isNotBlank()) addVoice("- 小习惯：${card.voice.quirks}", P_QUIRK)

        val traitLines = card.traits.map { line("- ${it.name}（强度 ${strengthLabel(it.strength)}）", P_TRAIT) }
        val valueLines = card.values.map { line("- $it", P_VALUES) }
        val boundaryLines = card.boundaries.map { line("- $it", P_BOUNDARIES) }

        val lifeLines = mutableListOf<Line>()
        if (card.life.schedule.isNotBlank()) lifeLines += line("- 作息：${card.life.schedule}", P_LIFE)
        if (card.life.doing.isNotBlank()) lifeLines += line("- 现在在：${card.life.doing}", P_LIFE)
        if (card.life.caresAbout.isNotBlank()) lifeLines += line("- 最近在意：${card.life.caresAbout}", P_LIFE)

        val bondLines = if (card.bond.isNotBlank()) {
            listOf(line(card.bond.cleaned(BOND_RENDER_MAX), P_BOND))
        } else {
            emptyList()
        }

        return listOf(
            Section("你是谁", required = true, lines = identityLines),
            Section("说话方式", required = true, lines = voiceLines),
            Section("性格", required = false, lines = traitLines),
            Section("在意的事", required = false, lines = valueLines),
            Section("边界", required = false, lines = boundaryLines),
            Section("生活", required = false, lines = lifeLines),
            Section("你们之间", required = false, lines = bondLines)
        )
    }

    private fun renderSections(sections: List<Section>, kept: Set<Int>): String =
        sections.mapNotNull { section ->
            val lines = section.lines.filter { it.id in kept }.map { it.text }
            val body = when {
                lines.isNotEmpty() -> lines.joinToString("\n")
                section.required -> PLACEHOLDER
                else -> return@mapNotNull null
            }
            "### ${section.title}\n$body"
        }.joinToString("\n\n")

    /** 兜底：极端输入下按句读处截断，保证不超过预算且不以半个字/半个段落收尾。 */
    private fun truncateToBudget(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val boundary = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '；', '\n'))
        return (if (boundary >= maxChars / 2) cut.take(boundary + 1) else cut).trimEnd()
    }

    private fun strengthLabel(strength: Float): String {
        val tenths = (strength.coerceIn(0f, 1f) * 10f).roundToInt()
        return if (tenths >= 10) "1.0" else "0.$tenths"
    }

    // -----------------------------------------------------------------------
    // 旧版提示词解析
    // -----------------------------------------------------------------------

    private val BOUNDARY_MARKERS = listOf("不要", "别", "禁止", "避免", "不能", "不得", "切勿")
    private val VOICE_MARKERS = listOf("说话", "语气", "语速", "表达", "称呼", "句子", "用词", "口头禅")
    private val RELATION_MARKERS = listOf(
        "恋人", "爱人", "伴侣", "朋友", "知己", "搭档", "同学", "同事", "家人", "导师", "邻居"
    )
    private val BULLET_PREFIX = Regex("^[-*•·>]+\\s*")
    private val ORDER_PREFIX = Regex("^\\d+[.、)）]\\s*")

    private fun parseLegacy(raw: String): PersonaCard {
        var name = ""
        var role = ""
        var relation = ""
        val paceParts = mutableListOf<String>()
        val boundaryParts = mutableListOf<String>()
        var heading = ""

        for (rawLine in raw.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                heading = trimmed.trimStart('#').trim()
                continue
            }
            val body = trimmed.replace(BULLET_PREFIX, "").replace(ORDER_PREFIX, "").trim()
            if (body.isEmpty()) continue

            // 「你是X，一个……的朋友。」
            if (name.isBlank()) {
                val prefix = when {
                    body.startsWith("你是") -> "你是"
                    body.startsWith("你叫") -> "你叫"
                    else -> null
                }
                if (prefix != null) {
                    val after = body.removePrefix(prefix)
                    val cut = after.indexOfAny(charArrayOf('，', ',', '。', '、', '；', ';', '：', ':', '！', '!', '？', '?', ' '))
                    val parsedName = (if (cut >= 0) after.substring(0, cut) else after).trim()
                    if (parsedName.isNotBlank() && parsedName.length <= 20) {
                        name = parsedName
                        val tail = if (cut >= 0) {
                            after.substring(cut + 1).trim().trimEnd('。', '，', '.', '!', '！', '？', '?')
                        } else {
                            ""
                        }
                        if (tail.isNotBlank()) {
                            role = tail
                            relation = RELATION_MARKERS.firstOrNull { tail.contains(it) }.orEmpty()
                        }
                        continue
                    }
                }
            }

            // 「不要…」「不称…」「别…」这类句子 -> 边界
            if (isBoundaryLine(body, heading)) {
                boundaryParts += body
                continue
            }
            // 「说话…」「语气…」或位于「表达方式」小节下的句子 -> 语速/语气描述
            if (isVoiceLine(body, heading)) {
                paceParts += body
            }
        }

        val recognized = name.isNotBlank() || role.isNotBlank() ||
            paceParts.isNotEmpty() || boundaryParts.isNotEmpty()
        return PersonaCard(
            identity = PersonaIdentity(name = name, role = role, relation = relation),
            voice = PersonaVoice(
                pace = paceParts.take(MAX_LEGACY_PACE_PARTS).joinToString("；")
            ),
            // 识别不出任何结构时，至少要留下「这是原文里的自我设定」这一条，避免人格塌成空卡。
            traits = if (recognized) {
                emptyList()
            } else {
                listOf(PersonaTrait(name = "保持原文里的自我设定", strength = 0.6f))
            },
            boundaries = boundaryParts.take(MAX_LEGACY_BOUNDARIES),
            // bond 是自由文本段：≤2000 字时整体保留原文，保证旧设定不丢。
            bond = legacyBond(raw)
        ).sanitized()
    }

    private fun isBoundaryLine(body: String, heading: String): Boolean {
        if (body.startsWith("不") || body.startsWith("别")) return true
        if (BOUNDARY_MARKERS.any { body.startsWith(it) }) return true
        return heading.contains("边界") || heading.contains("原则") || heading.contains("禁止")
    }

    private fun isVoiceLine(body: String, heading: String): Boolean {
        if (heading.contains("表达") || heading.contains("说话") || heading.contains("语气")) return true
        return VOICE_MARKERS.any { body.contains(it) }
    }

    private fun legacyBond(raw: String): String {
        if (raw.length <= BOND_MAX) return raw
        val cut = raw.take(BOND_MAX)
        val boundary = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '；', '\n'))
        return (if (boundary >= BOND_MAX * 3 / 4) cut.take(boundary + 1) else cut).trim()
    }
}

/**
 * 模型提出的人设补丁。
 *
 * 约定：只覆盖补丁里出现过的字段；列表字段是「替换」语义；未知字段、类型不符的字段一律忽略；
 * 坏 JSON 返回原卡，保证打补丁失败不会破坏已有的人设。
 */
object PersonaCardPatch {

    private const val FALLBACK_SUMMARY = "调整了人设"
    private const val SUMMARY_MAX = 60

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun apply(card: PersonaCard, patchJson: String): PersonaCard {
        val root = runCatching { json.parseToJsonElement(patchJson) as? JsonObject }.getOrNull()
            ?: return card
        return runCatching {
            var identity = card.identity
            var voice = card.voice
            var traits = card.traits
            var values = card.values
            var boundaries = card.boundaries
            var life = card.life
            var bond = card.bond

            root.objectOrNull("identity")?.let { obj ->
                identity = identity.copy(
                    name = obj.textOrNull("name") ?: identity.name,
                    age = obj.textOrNull("age") ?: identity.age,
                    role = obj.textOrNull("role") ?: identity.role,
                    relation = obj.textOrNull("relation") ?: identity.relation
                )
            }
            root.objectOrNull("voice")?.let { obj ->
                voice = voice.copy(
                    pace = obj.textOrNull("pace") ?: voice.pace,
                    sentence = obj.textOrNull("sentence") ?: voice.sentence,
                    catchphrases = obj.stringListOrNull("catchphrases") ?: voice.catchphrases,
                    address = obj.textOrNull("address") ?: voice.address,
                    quirks = obj.textOrNull("quirks") ?: voice.quirks
                )
            }
            root.arrayOrNull("traits")?.let { array ->
                traits = array.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val name = obj.textOrNull("name") ?: return@mapNotNull null
                    if (name.isBlank()) {
                        null
                    } else {
                        val strength = (obj["strength"] as? JsonPrimitive)?.floatOrNull ?: 0.5f
                        PersonaTrait(name = name, strength = strength)
                    }
                }
            }
            root.stringListOrNull("values")?.let { values = it }
            root.stringListOrNull("boundaries")?.let { boundaries = it }
            root.objectOrNull("life")?.let { obj ->
                life = life.copy(
                    schedule = obj.textOrNull("schedule") ?: life.schedule,
                    doing = obj.textOrNull("doing") ?: life.doing,
                    caresAbout = obj.textOrNull("caresAbout") ?: life.caresAbout
                )
            }
            root.textOrNull("bond")?.let { bond = it }

            PersonaCard(
                identity = identity,
                voice = voice,
                traits = traits,
                values = values,
                boundaries = boundaries,
                life = life,
                bond = bond
            ).sanitized()
        }.getOrDefault(card)
    }

    /** 给用户看的一行摘要，如「说话习惯·称呼、性格·嘴硬」；解析失败返回「调整了人设」。 */
    fun summarize(patchJson: String): String {
        val root = runCatching { json.parseToJsonElement(patchJson) as? JsonObject }.getOrNull()
            ?: return FALLBACK_SUMMARY
        val parts = mutableListOf<String>()

        root.objectOrNull("identity")?.let { obj ->
            val label = when {
                obj.textOrNull("name") != null -> "名字"
                obj.textOrNull("role") != null -> "身份"
                obj.textOrNull("age") != null -> "年龄感"
                obj.textOrNull("relation") != null -> "关系"
                else -> null
            }
            if (label != null) parts += "身份·$label"
        }
        root.objectOrNull("voice")?.let { obj ->
            // 只挑一个最能代表这次改动的点，保持摘要是一行。
            val label = when {
                obj.textOrNull("address") != null -> "称呼"
                obj.textOrNull("pace") != null -> "语速"
                obj.stringListOrNull("catchphrases") != null -> "口头禅"
                obj.textOrNull("sentence") != null -> "句子长短"
                obj.textOrNull("quirks") != null -> "小习惯"
                else -> null
            }
            if (label != null) parts += "说话习惯·$label"
        }
        root.arrayOrNull("traits")
            ?.mapNotNull { (it as? JsonObject)?.textOrNull("name") }
            ?.takeIf { it.isNotEmpty() }
            ?.let { parts += "性格·" + it.take(3).joinToString("、") }
        if (root.stringListOrNull("values") != null) parts += "在意的事"
        if (root.stringListOrNull("boundaries") != null) parts += "边界"
        if (root.objectOrNull("life") != null) parts += "生活"
        if (root.textOrNull("bond") != null) parts += "你们之间"

        if (parts.isEmpty()) return FALLBACK_SUMMARY
        val summary = parts.joinToString("、")
        return if (summary.length > SUMMARY_MAX) summary.take(SUMMARY_MAX - 1) + "…" else summary
    }

    // -- 温和的 JSON 取值工具：类型不符一律当作「字段没出现」 ------------------

    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.textOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.stringListOrNull(key: String): List<String>? {
        val array = arrayOrNull(key) ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { prim -> prim.isString }?.content }
    }
}
