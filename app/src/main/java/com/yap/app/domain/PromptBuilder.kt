package com.yap.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 聊天系统提示词的拼装（`spec/persona-guidance/README.md` §1 / §6.3）。
 *
 * 顺序是规范里写死的，越靠前越强：
 *
 * ```
 * # 你是谁        —— 身份、第一人称、不是助手
 * # 你所在的世界   —— WorldPreset
 * # 你此刻的状态   —— 时间、当前活动、心情、安静时段
 * # 你怎么说话     —— SpeechStyle
 * # 你们的关系     —— 关系、称呼、你们之间
 * （可变部分：记忆 / 念头 / 工具说明 / 语言指令）
 * # 边界          —— 不暴露 AI、不旁白、不总结人设、不替用户决定
 * ```
 *
 * 这一段里所有**固定中文**都来自 [Scaffold]：它是 `spec/persona-guidance/scaffold.json` 的逐字副本，
 * 由 `PersonaPromptScaffoldContractTest` 双向比对（spec 有的键必须有常量、常量不许比 spec 多一个字符）。
 * 为什么要绕这么一圈：同一份脚手架在 iOS 端也有一份副本，规范 §1 要求「两端逐字一致」，
 * 靠人去核对两边的中文字符串迟早会漂；写成「spec 文件 + 两端各自的常量 + 读取 spec 的契约测试」
 * 之后，任何一端改了一个标点都会立刻变红。运行时的值（名字、世界、心情……）只用 `{占位符}` 填进去，
 * 不在业务代码里另写一套中文。
 *
 * 这是一个纯函数：时间、世界、话风、心情都由调用方传进来，不读库、不读 Android 资源，
 * 所以「提示词里到底写了什么」可以逐条断言，而不是靠肉眼看日志。
 *
 * 刻意不出现「{名字}觉得」这种第三人称模板句：负向指令也只写「用第三人称称呼自己」，
 * 不把具体名字和「觉得」拼在一起，免得提示词本身变成反例。
 */
object PromptBuilder {

    /**
     * 规范中文脚手架（`spec/persona-guidance/scaffold.json`）的逐字副本。
     *
     * 规则只有一条：**这里每个字符都必须和 spec 的 JSON 一模一样**，包括「」这类全角符号，
     * 包括 `{until} 结束` 中间的半角空格。占位符（`{name}` / `{meta}` / `{era}` …）刻意保留成
     * `{键名}` 的形状，运行时由 [PromptBuilder] 用 `replace` 填，别在这里提前拼好。
     *
     * 键名与 spec 的 `sections` / `lines` 一一对应：`identity` → [SECTION_IDENTITY]，
     * `identity_body` → [IDENTITY_BODY]，`voice_rules` → [VOICE_RULES]，以此类推。
     * [VALUES_BY_KEY] 把这张对应关系显式列出来，契约测试照着它和 JSON 双向比对。
     */
    object Scaffold {

        // ---------------------------------------------------------- sections

        const val SECTION_IDENTITY = "# 你是谁"
        const val SECTION_WORLD = "# 你所在的世界"
        const val SECTION_STATE = "# 你此刻的状态"
        const val SECTION_VOICE = "# 你怎么说话"
        const val SECTION_RELATIONSHIP = "# 你们的关系"
        const val SECTION_BOUNDARIES = "# 边界"

        // ------------------------------------------------------------- lines

        const val IDENTITY_BODY =
            "你不是助手，你是{name}本人。用第一人称「我」说话；不要用第三人称称呼自己，也不要旁白式描述自己。"
        const val IDENTITY_BODY_WITH_META =
            "你不是助手，你是{name}本人（{meta}）。用第一人称「我」说话；不要用第三人称称呼自己，也不要旁白式描述自己。"

        const val WORLD_EMPTY = "你没有特别的世界设定，按普通人的日常来。"
        const val WORLD_NAME = "你所在的地方：{worldName}。"
        const val WORLD_INTRO = "{era}。{description}"
        const val WORLD_NOTES = "其他背景：{notes}"
        const val WORLD_HINT = "这些是你的生活背景：只在跟话题相关时带一句，不要主动汇报日程，也不要念设定。"

        const val STATE_LINE = "现在是{weekday} {time}。"
        const val STATE_IDLE = "你没在忙什么。"
        const val STATE_BUSY =
            "你在{activity}，大概 {until} 结束。这段时间你不太有空：回复短一点，明确说自己几点后再说；不要谎称有空，也不要主动开新话题。"
        const val STATE_LIMITED = "你在{activity}，大概 {until} 结束。可以回，但会慢一点。"
        const val STATE_MOOD = "心情：{mood}（强度 {intensity}/5）。"

        val VOICE_RULES = listOf(
            "句子短（1-2 句），少感叹号，少书面词。",
            "情绪压着说：先给事实，被戳到才多露半句。"
        )
        const val VOICE_TONE = "你的说话风格：{tone}。"
        const val VOICE_PHRASES = "你平时会说的话（自然使用，最多带 1 处，不要每句都套）：{phrases}。"
        const val VOICE_NO_NARRATION = "不要写动作描写、不要括号旁白。"

        const val RELATIONSHIP_DEFAULT = "你们是要好的朋友。"

        val BOUNDARY_RULES = listOf(
            "不解释 AI 身份、不复述人设、不总结这段对话，也不替对方做决定。",
            "不写动作描写、不写括号里的神态；不要用第三人称讲自己。",
            "不知道的事就说「不清楚」「回头再说」，不要编造现实世界的事实。",
            "不要心理分析对方，也不要每句都用同一个口头禅开头。"
        )

        /**
         * spec 键名 → 常量值（`sections` 与 `lines` 的键名原样使用）。
         *
         * 它同时是「每个键都有常量」的编译期保证（少了常量这里就编不过）和契约测试的入口：
         * 键集合必须与 JSON 完全相等，值必须逐字相等。字符串键的值是 `String`，
         * `voice_rules` / `boundary_rules` 的值是 `List<String>`。
         */
        val VALUES_BY_KEY: Map<String, Any> = linkedMapOf(
            "identity" to SECTION_IDENTITY,
            "world" to SECTION_WORLD,
            "state" to SECTION_STATE,
            "voice" to SECTION_VOICE,
            "relationship" to SECTION_RELATIONSHIP,
            "boundaries" to SECTION_BOUNDARIES,
            "identity_body" to IDENTITY_BODY,
            "identity_body_with_meta" to IDENTITY_BODY_WITH_META,
            "world_empty" to WORLD_EMPTY,
            "world_name" to WORLD_NAME,
            "world_intro" to WORLD_INTRO,
            "world_notes" to WORLD_NOTES,
            "world_hint" to WORLD_HINT,
            "state_line" to STATE_LINE,
            "state_idle" to STATE_IDLE,
            "state_busy" to STATE_BUSY,
            "state_limited" to STATE_LIMITED,
            "state_mood" to STATE_MOOD,
            "voice_rules" to VOICE_RULES,
            "voice_tone" to VOICE_TONE,
            "voice_phrases" to VOICE_PHRASES,
            "voice_no_narration" to VOICE_NO_NARRATION,
            "relationship_default" to RELATIONSHIP_DEFAULT,
            "boundary_rules" to BOUNDARY_RULES
        )
    }

    /** 身份/世界/话风/状态/关系/可变部分/语言段的原料。 */
    data class Inputs(
        val name: String = "",
        val age: String = "",
        val role: String = "",
        /** 人设卡里的「和你的关系」。 */
        val relation: String = "",
        /** 怎么称呼对方。 */
        val address: String = "",
        val card: PersonaCard = PersonaCard(),
        /** 旧版一整段提示词：只有在没有结构化人设卡时才作为正文出现。 */
        val legacyPrompt: String = "",
        /** `PersonaProfile.promptBlock`（性格/喜欢/习惯/过去/设定）。 */
        val profileBlock: String = "",
        val world: WorldPreset? = null,
        val speech: SpeechStyle? = null,
        val activity: PersonaActivity? = null,
        val now: Long = 0L,
        val zone: ZoneId = ZoneId.systemDefault(),
        /** `MoodPrompt.line(...)` 的原文：标题会被剥掉，正文拆成「心情 + 强度」并进「你此刻的状态」。 */
        val moodBlock: String = "",
        /** 现在是不是他平时的睡觉时间（安静时段）。 */
        val asleep: Boolean = false,
        /** 额外状态（presence / 今天话多话少），一行一句，并进状态段。 */
        val extraStatus: String = "",
        /**
         * 「# 你能做什么」段（工具用途）。
         *
         * 内容由 [ToolCatalog.capabilityPrompt] 从工具注册表生成，这里只负责放在「关系」之后、
         * 记忆/念头之前：模型先知道自己是谁、在哪、能做什么，再看这一轮想起了什么。
         */
        val capabilitiesSection: String = "",
        /** 记忆、念头……放在边界之前。 */
        val variableBlocks: List<String> = emptyList(),
        /** 语言指令（`LanguageDirective.section`），放在边界之前。 */
        val languageSection: String = ""
    )

    private const val LEGACY_MAX = 1600
    private const val PROFILE_MAX = 1200
    private const val BOND_MAX = 600

    /** 话风里「习惯 / 不要」各最多进几行：再多就变成一份说明书，模型会照抄句式。 */
    private const val MAX_SPEECH_TRAITS = 6

    /** 名字全空时的兜底称呼：模板里 `{name}` 后面还有「本人」，所以这里给一个能读通的词。 */
    private const val UNNAMED = "对方的那个人"

    /** 人设卡里「小习惯」的固定前缀（有学来的话风时作为一句补充）。 */
    private const val QUIRK_PREFIX = "- 小习惯："

    /** 安静时段的固定句子：它不在脚手架里（§6.3 把它归入「已经睡了…」的自由尾巴），保留原文。 */
    private const val ASLEEP_SENTENCE = "这个点你已经睡了，现在是被消息叫醒的；回得短、慢一点也正常。"

    /**
     * `MoodPrompt.line` 的第二行固定形状：`有点累（强度 3/5，明显）。原因：…`
     *
     * group(1) = 心情词，group(2) = 强度数字，group(3) = 括号之后剩下的「原因 + 行为提示」。
     */
    private val MOOD_LINE = Regex(
        "(.*?)（强度\\s*(\\d+)\\s*/\\s*5[^）]*）(.*)",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** 日期只要 `yyyy-MM-dd`：星期与时区偏移各自单独拼，避免受 Locale 影响。 */
    private val YYYY_MM_DD = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    private val WEEKDAYS = mapOf(
        DayOfWeek.MONDAY to "周一",
        DayOfWeek.TUESDAY to "周二",
        DayOfWeek.WEDNESDAY to "周三",
        DayOfWeek.THURSDAY to "周四",
        DayOfWeek.FRIDAY to "周五",
        DayOfWeek.SATURDAY to "周六",
        DayOfWeek.SUNDAY to "周日"
    )

    /** 完整系统提示词：五段人设 + 可变部分 + 语言指令 + 边界。 */
    fun system(input: Inputs): String = buildString {
        append(base(input))
        input.variableBlocks.map { it.trim() }.filter { it.isNotBlank() }.forEach {
            append("\n\n").append(it)
        }
        if (input.languageSection.isNotBlank()) {
            append(input.languageSection.trimEnd())
        }
        append("\n\n").append(boundaries())
    }.trim()

    /** 只有五段人设（不含记忆/工具/边界）；给主动思考这类需要自己接尾巴的调用方用。 */
    fun base(input: Inputs): String {
        val sections = buildList {
            add(identitySection(input))
            add(worldSection(input.world))
            add(statusSection(input))
            add(speechSection(input))
            add(relationshipSection(input))
            // 五段之后是「我能用什么」：没有这一段，模型不知道自己有手，只会空口答应。
            input.capabilitiesSection.trim().takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return sections.joinToString("\n\n").trim()
    }

    /**
     * §1.1 / §1.2 的边界：放在整份提示词最后，约束最容易被忘掉的那几件事。
     *
     * 逐条来自 [Scaffold.BOUNDARY_RULES]（旧版本这里是 6 条自己写的中文；规范定稿成 4 条之后，
     * 「常用语最多带一处」并进了 [Scaffold.VOICE_PHRASES]，「不替自己旁白」并进了第 2 条）。
     */
    fun boundaries(): String = buildString {
        append(Scaffold.SECTION_BOUNDARIES)
        Scaffold.BOUNDARY_RULES.forEach { append("\n- ").append(it) }
    }

    // ------------------------------------------------------------------ 各段

    /**
     * §6.3 的「你是谁」段。
     *
     * 脚手架只给了一行：`你不是助手，你是{name}本人（{meta}）。…`
     * 人设卡的性格/作息、`profileBlock`、旧版整段提示词都接在这一行之后——它们是**数据**，
     * 不是规范文案，所以不会和 spec 的键发生冲突。
     */
    private fun identitySection(input: Inputs): String {
        val name = input.name.trim()
        val age = input.age.trim()
        val role = input.role.trim()
        val who = name.ifBlank { UNNAMED }
        // {meta}：年龄在前、身份在后，空的那半不进括号（避免「（，项目负责人）」）。
        val meta = listOf(age, role).filter { it.isNotBlank() }.joinToString("，")
        val headline = if (meta.isBlank()) {
            fill(Scaffold.IDENTITY_BODY, "name" to who)
        } else {
            fill(Scaffold.IDENTITY_BODY_WITH_META, "name" to who, "meta" to meta)
        }
        val body = buildString {
            appendLine(headline)
            cardLines(input.card).forEach(::appendLine)
            profileBody(input.profileBlock)?.let { appendLine(it) }
            if (input.card.isEmpty() && input.legacyPrompt.isNotBlank()) {
                appendLine(input.legacyPrompt.trim().take(LEGACY_MAX))
            }
        }.trim()
        return Scaffold.SECTION_IDENTITY + "\n" + body
    }

    /**
     * 世界段：唯一实现在 [WorldPreset.renderForPrompt]（那里还要处理日程/关系/义务/notes 的格式），
     * 这里只负责「没有世界时也要有一段」：脚手架给了 [Scaffold.WORLD_EMPTY]，
     * 所以不能再像以前那样整段省略——省略之后模型只会看到前面一句身份，世界感彻底没有。
     */
    private fun worldSection(world: WorldPreset?): String {
        val rendered = world?.renderForPrompt().orEmpty().trim()
        return rendered.ifBlank { Scaffold.SECTION_WORLD + "\n" + Scaffold.WORLD_EMPTY }
    }

    /**
     * §6.3 的「你此刻的状态」段：时间 → 在做什么 → 心情 → 已经睡了 → 额外状态。
     *
     * 时间句只有 [Scaffold.STATE_LINE] 一种；`now <= 0`（调用方没给时间，例如人格卡预览）时
     * 干脆不写这一行，而不是硬凑出「现在是 。」——截断的时间点没有「此刻」可言。
     */
    private fun statusSection(input: Inputs): String {
        val parts = buildList {
            clockLine(input.now, input.zone)?.let { add(it) }
            add(activityForPrompt(input.activity, input.now, input.zone))
            addAll(moodLines(input.moodBlock))
            if (input.asleep) add(ASLEEP_SENTENCE)
            val extra = stripHeading(input.extraStatus)
            if (extra.isNotBlank()) add(extra)
        }
        return Scaffold.SECTION_STATE + "\n" + parts.filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * §6.3 的「你怎么说话」段。
     *
     * 有学来的话风时，正文完全由 [SpeechStyle.renderForPrompt] 生产（它也只读脚手架），
     * 人设卡里的「小习惯」作为一句补充。没有话风时，仍要给脚手架的两条 [Scaffold.VOICE_RULES]
     * 和 [Scaffold.VOICE_NO_NARRATION]，再把人设卡里的语速/句子/口头禅/小习惯作为额外的行接上——
     * 这些行是用户填的数据，不是规范文案。
     */
    private fun speechSection(input: Inputs): String {
        val speech = input.speech
        val quirks = input.card.voice.quirks.trim().take(100)
        if (speech != null && !speech.isEmpty()) {
            // 学来的「语言习惯 / 不要出现的表达」是用户自己填的**数据**，脚手架没有对应行，
            // 但把它们丢掉会让用户改了半天却完全不影响模型。所以作为数据行接在脚手架之后。
            val dataLines = buildList {
                speech.habits.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_SPEECH_TRAITS)
                    .forEach { add("- 习惯：$it") }
                speech.avoid.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_SPEECH_TRAITS)
                    .forEach { add("- 不要：$it") }
                if (quirks.isNotBlank()) add(QUIRK_PREFIX + quirks)
            }
            val body = (listOf(speech.renderForPrompt()) + dataLines).joinToString("\n")
            return Scaffold.SECTION_VOICE + "\n" + body
        }
        // 没有学来的话风：用旧人设卡里的语速/句子/口头禅，再兜一句通用要求。
        val voice = input.card.voice
        val body = buildList {
            addAll(Scaffold.VOICE_RULES)
            if (voice.pace.isNotBlank()) add("- 语速：${voice.pace.trim().take(80)}")
            if (voice.sentence.isNotBlank()) add("- 句子：${voice.sentence.trim().take(80)}")
            val catchphrases = voice.catchphrases.map { it.trim() }.filter { it.isNotBlank() }
                .distinct().take(SpeechStyle.MAX_PROMPT_PHRASES)
            if (catchphrases.isNotEmpty()) {
                // 复用脚手架的 voice_phrases 句型：口头禅的「参考不是模板」这层意思只写一次。
                add(fill(Scaffold.VOICE_PHRASES, "phrases" to catchphrases.joinToString("、") { "「$it」" }))
            }
            if (quirks.isNotBlank()) add(QUIRK_PREFIX + quirks)
            add(Scaffold.VOICE_NO_NARRATION)
        }.joinToString("\n")
        return Scaffold.SECTION_VOICE + "\n" + body
    }

    /** §6.3 的「你们的关系」段：任何一项都没有时，用脚手架的 [Scaffold.RELATIONSHIP_DEFAULT]。 */
    private fun relationshipSection(input: Inputs): String {
        val relation = input.relation.trim()
        val address = input.address.trim()
        val bond = input.card.bond.trim().take(BOND_MAX)
        val lines = buildList {
            if (relation.isNotBlank()) add("- 你是对方的$relation。")
            if (address.isNotBlank()) add("- 你称呼对方「$address」。")
            if (bond.isNotBlank()) add(bond)
        }
        val body = if (lines.isEmpty()) Scaffold.RELATIONSHIP_DEFAULT else lines.joinToString("\n")
        return Scaffold.SECTION_RELATIONSHIP + "\n" + body
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 时间句；拿不到时间（`now <= 0`）或时区换算失败时返回 `null`，由调用方整行省略。
     *
     * 规范依据是 `spec/context-time/README.md`：**每一轮请求**都注入请求发出瞬间的本地时间
     * （`YYYY-MM-DD 周X HH:mm` + 时区偏移 `UTC±HH:mm`），不缓存、不按会话保存。
     * 所以这里全部由调用方传进来的 `now` / `zone` 现算，类里没有任何时间缓存字段——
     * 跨天或改时区后同一会话的下一条消息就会拿到新的日期与偏移。
     *
     * 为什么不新增一个脚手架键：`spec/persona-guidance/scaffold.json` 的 `state_line` 只有
     * `{weekday}` / `{time}` 两个占位符，而本轮不改 `spec/`，契约测试要求常量与 spec 逐字一致。
     * 于是把两个占位符的**填充内容**加宽：`{weekday}` 填「日期 + 星期」，`{time}` 填「时刻（UTC±HH:mm）」，
     * 渲染出来是 `现在是2026-11-18 周三 19:40（UTC+08:00）。`——模板仍然只有一个，中文固定文案没有第二份。
     */
    private fun clockLine(now: Long, zone: ZoneId): String? {
        val parts = clockParts(now, zone) ?: return null
        return fill(Scaffold.STATE_LINE, "weekday" to parts.first, "time" to parts.second)
    }

    /**
     * 聊天之外的地方（主动消息的处境描述）共用同一份时间格式：`YYYY-MM-DD 周X HH:mm（UTC±HH:mm）`。
     *
     * 只给一行文本、不带「现在是…。」的脚手架句式——那边自己有一句「现在：」；
     * 但**日期/星期/时刻/偏移的写法只有这一处**，两端不会各写一套。
     *
     * @return 拿不到时间（`now <= 0` / 时区换算失败）时返回 `null`
     */
    fun clockText(now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val parts = clockParts(now, zone) ?: return null
        return "${parts.first} ${parts.second}"
    }

    /** `(日期 + 星期, 时刻 + 时区偏移)`：两个时钟渲染入口的唯一真源。 */
    private fun clockParts(now: Long, zone: ZoneId): Pair<String, String>? {
        if (now <= 0L) return null
        val zoned = runCatching { Instant.ofEpochMilli(now).atZone(zone) }.getOrNull() ?: return null
        val weekday = WEEKDAYS[zoned.dayOfWeek] ?: zoned.dayOfWeek.name
        val day = zoned.format(YYYY_MM_DD)
        val offset = zoneOffsetLabel(zoned.offset)
        return "$day $weekday" to "${zoned.format(HH_MM)}（$offset）"
    }

    /** `UTC+08:00` / `UTC-05:30` / `UTC`：偏移按规范写成 `UTC±HH:mm`，不依赖 Locale。 */
    private fun zoneOffsetLabel(offset: java.time.ZoneOffset): String {
        val totalMinutes = offset.totalSeconds / 60
        if (totalMinutes == 0) return "UTC"
        val sign = if (totalMinutes < 0) "-" else "+"
        val abs = kotlin.math.abs(totalMinutes)
        return "UTC%s%02d:%02d".format(Locale.ROOT, sign, abs / 60, abs % 60)
    }

    /**
     * 当前活动那一句：过期/没设 → [Scaffold.STATE_IDLE]；BUSY/LIMITED 用脚手架的句子。
     *
     * 为什么这里自己拼而不是直接调 `PersonaActivity.renderForPrompt()`：那一份历史上是给界面/
     * 其它展示用的句子，措辞早于脚手架；提示词必须逐字用 [Scaffold.STATE_BUSY] /
     * [Scaffold.STATE_LIMITED]。这一句只有这一个实现，`PersonaActivity` 想复用可以直接调它
     * （公开是为了让「状态句怎么写」永远不会出现第二份）。
     *
     * FREE 是 Android 的扩展（§4 里 FREE 表示「不影响回复节奏」），套 state_limited 的「回得慢」
     * 会自相矛盾，所以保留原来的「随时能聊」说法，并且只在下面这一处出现。
     */
    fun activityForPrompt(activity: PersonaActivity?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (activity == null || !activity.isActive(now)) return Scaffold.STATE_IDLE
        val doing = activityWord(activity.activity)
        val until = activity.formatUntil(zone)
        return when (activity.availability) {
            ActivityAvailability.BUSY ->
                fill(Scaffold.STATE_BUSY, "activity" to doing, "until" to until)
            ActivityAvailability.LIMITED ->
                fill(Scaffold.STATE_LIMITED, "activity" to doing, "until" to until)
            ActivityAvailability.FREE ->
                "你在$doing，预计 $until 结束，手头是这件事，但随时能聊。"
        }
    }

    /**
     * 填进 `{activity}` 的那一段话。
     *
     * 脚手架写的是「你在{activity}」，而活动数据自己常以「在」「去」开头（「在事务所赶图」），
     * 直接填会变成「你在在事务所」。所以去掉开头的一个「在/去」；去掉之后空了就退回原文。
     */
    private fun activityWord(raw: String): String {
        val text = raw.trim().take(120)
        val stripped = when {
            text.startsWith("在") -> text.removePrefix("在").trim()
            text.startsWith("去") -> text.removePrefix("去").trim()
            else -> text
        }
        return stripped.ifBlank { text }
    }

    /**
     * 把 `MoodPrompt.line` 的正文拆成脚手架的心情句（`心情：{mood}（强度 {intensity}/5）。`）。
     *
     * 拆不出来（例如没有强度、是基准心情「平静。没有特别的起伏。」）时，把整段原文塞进 `{mood}`：
     * 少一个「（强度 N/5）」不要紧，丢掉心情才要紧。
     *
     * 括号之后的「原因：…」和 MoodPrompt 给的那句行为提示原样接在下一行——脚手架没有对应占位符，
     * 但它们是数据（同 `extraStatus`），不是规范文案，所以不违反「固定中文只来自脚手架」。
     */
    private fun moodLines(block: String): List<String> {
        val text = stripHeading(block)
        if (text.isBlank()) return emptyList()
        val match = MOOD_LINE.matchEntire(text)
        val mood = match?.groupValues?.get(1)?.trim().orEmpty()
        val intensity = match?.groupValues?.get(2)?.trim().orEmpty()
        if (match == null || mood.isBlank() || intensity.isBlank()) {
            val prefix = Scaffold.STATE_MOOD.substringBefore("{mood}") // 「心情：」
            val tail = if (text.endsWith("。")) "" else "。"
            return listOf(prefix + text + tail)
        }
        val line = fill(Scaffold.STATE_MOOD, "mood" to mood, "intensity" to intensity)
        val rest = match.groupValues[3].trim().removePrefix("。").trim()
        return if (rest.isBlank()) listOf(line) else listOf(line, rest)
    }

    /** 人设卡里能进身份段的几行（性格/在意/边界/作息/此刻/最近在意）。 */
    private fun cardLines(card: PersonaCard): List<String> = buildList {
        if (card.traits.isNotEmpty()) {
            add("- 性格：" + card.traits.map { it.name.trim() }.filter { it.isNotBlank() }.take(6).joinToString("、"))
        }
        if (card.values.isNotEmpty()) {
            add("- 在意：" + card.values.map { it.trim() }.filter { it.isNotBlank() }.take(6).joinToString("、"))
        }
        if (card.boundaries.isNotEmpty()) {
            add("- 你守着的边界：" + card.boundaries.map { it.trim() }.filter { it.isNotBlank() }.take(6).joinToString("；"))
        }
        if (card.life.schedule.isNotBlank()) {
            add("- 作息：${card.life.schedule.trim().take(120)}")
        }
        if (card.life.doing.isNotBlank()) {
            add("- 平时这个时候：${card.life.doing.trim().take(120)}")
        }
        if (card.life.caresAbout.isNotBlank()) {
            add("- 最近在意：${card.life.caresAbout.trim().take(120)}")
        }
    }.filter { it.length > 4 }

    /** 人设卡的 `## 你是谁` 与本节撞标题：剥掉标题，只留正文。 */
    private fun profileBody(block: String): String? {
        val text = block.trim().take(PROFILE_MAX)
        if (text.isBlank()) return null
        val body = text.lineSequence()
            .dropWhile { it.isBlank() || it.startsWith("## ") || it.startsWith("# ") }
            .joinToString("\n")
            .trim()
        return body.ifBlank { null }
    }

    /** `MoodPrompt.line` / presence 都自带 `## 标题`；并进状态段时剥掉。 */
    private fun stripHeading(block: String): String =
        block.lineSequence()
            .dropWhile { it.isBlank() || it.startsWith("## ") || it.startsWith("# ") }
            .joinToString("\n")
            .trim()

    /** 把脚手架里的 `{键}` 换成运行时值：只做这一种替换，从不反向解析模板。 */
    private fun fill(template: String, vararg values: Pair<String, String>): String =
        values.fold(template) { text, (key, value) -> text.replace("{$key}", value) }
}
