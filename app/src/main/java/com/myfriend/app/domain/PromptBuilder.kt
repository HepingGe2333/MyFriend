package com.myfriend.app.domain

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
 * 这是一个纯函数：时间、世界、话风、心情都由调用方传进来，不读库、不读 Android 资源，
 * 所以「提示词里到底写了什么」可以逐条断言，而不是靠肉眼看日志。
 *
 * 刻意不出现「{名字}觉得」这种第三人称模板句：负向指令也只写「用第三人称称呼自己」，
 * 不把具体名字和「觉得」拼在一起，免得提示词本身变成反例。
 */
object PromptBuilder {

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
        /** `MoodPrompt.line(...)` 的原文：标题会被剥掉，正文并进「你此刻的状态」。 */
        val moodBlock: String = "",
        /** 现在是不是他平时的睡觉时间（安静时段）。 */
        val asleep: Boolean = false,
        /** 额外状态（presence / 今天话多话少），一行一句，并进状态段。 */
        val extraStatus: String = "",
        /** 记忆、念头、工具说明……放在边界之前。 */
        val variableBlocks: List<String> = emptyList(),
        /** 语言指令（`LanguageDirective.section`），放在边界之前。 */
        val languageSection: String = ""
    )

    private const val LEGACY_MAX = 1600
    private const val PROFILE_MAX = 1200
    private const val BOND_MAX = 600
    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

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
        val sections = mutableListOf<String>()
        sections += identitySection(input)
        worldSection(input.world)?.let { sections += it }
        sections += statusSection(input)
        sections += speechSection(input)
        sections += relationshipSection(input)
        return sections.joinToString("\n\n").trim()
    }

    /** §1.1 / §1.2 的边界：放在整份提示词最后，约束最容易被忘掉的那几件事。 */
    fun boundaries(): String = """
        # 边界
        - 不解释 AI 身份，不说「作为一个 AI」这类话；你就是这个人。
        - 不旁白：不写动作描写、不写括号里的神态，也不替自己描述表情。
        - 不复述、不总结人设，不把上面这些设定当台词念。
        - 不替对方下判断、不替对方做决定，也不做心理分析。
        - 不知道的事就说「不清楚」或「回头再说」，不编造事实。
        - 常用语是参考不是模板，一次回复最多自然带一处。
    """.trimIndent()

    // ------------------------------------------------------------------ 各段

    private fun identitySection(input: Inputs): String {
        val name = input.name.trim()
        val age = input.age.trim()
        val role = input.role.trim()
        val who = when {
            name.isNotBlank() && age.isNotBlank() && role.isNotBlank() -> "${name}本人（$age，$role）"
            name.isNotBlank() && role.isNotBlank() -> "${name}本人（$role）"
            name.isNotBlank() && age.isNotBlank() -> "${name}本人（$age）"
            name.isNotBlank() -> "$name 本人"
            role.isNotBlank() -> "对方的$role"
            else -> "对方的那个熟人"
        }
        val body = buildString {
            appendLine("你不是助手，你是$who。用第一人称「我」说话；")
            appendLine("不要用第三人称称呼自己、也不要替自己旁白（不要写「他/她怎么想」这种句子）。")
            val cardLines = buildList {
                if (input.card.traits.isNotEmpty()) {
                    add("- 性格：" + input.card.traits.map { it.name.trim() }.filter { it.isNotBlank() }.take(6).joinToString("、"))
                }
                if (input.card.values.isNotEmpty()) {
                    add("- 在意：" + input.card.values.map { it.trim() }.filter { it.isNotBlank() }.take(6).joinToString("、"))
                }
                if (input.card.boundaries.isNotEmpty()) {
                    add("- 你守着的边界：" + input.card.boundaries.map { it.trim() }.filter { it.isNotBlank() }.take(6).joinToString("；"))
                }
                if (input.card.life.schedule.isNotBlank()) {
                    add("- 作息：${input.card.life.schedule.trim().take(120)}")
                }
                if (input.card.life.doing.isNotBlank()) {
                    add("- 平时这个时候：${input.card.life.doing.trim().take(120)}")
                }
                if (input.card.life.caresAbout.isNotBlank()) {
                    add("- 最近在意：${input.card.life.caresAbout.trim().take(120)}")
                }
            }
            cardLines.filter { it.length > 4 }.forEach(::appendLine)
            profileBody(input.profileBlock)?.let { appendLine(it) }
            if (input.card.isEmpty() && input.legacyPrompt.isNotBlank()) {
                appendLine(input.legacyPrompt.trim().take(LEGACY_MAX))
            }
        }.trim()
        return "# 你是谁\n$body"
    }

    private fun worldSection(world: WorldPreset?): String? {
        val rendered = world?.renderForPrompt().orEmpty().trim()
        return rendered.ifBlank { null }
    }

    private fun statusSection(input: Inputs): String {
        val clock = clockText(input.now, input.zone)
        val activity = input.activity?.renderForPrompt(input.now, input.zone) ?: "没在忙什么。"
        val parts = buildList {
            add(clock)
            add(activity)
            val mood = stripHeading(input.moodBlock)
            if (mood.isNotBlank()) add("心情：$mood")
            if (input.asleep) add("这个点你已经睡了，现在是被消息叫醒的；回得短、慢一点也正常。")
            val extra = stripHeading(input.extraStatus)
            if (extra.isNotBlank()) add(extra)
        }
        return "# 你此刻的状态\n" + parts.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun speechSection(input: Inputs): String {
        val speech = input.speech
        if (speech != null && !speech.isEmpty()) {
            val quirks = input.card.voice.quirks.trim().take(100)
            val extra = if (quirks.isBlank()) "" else "\n- 小习惯：$quirks"
            return "# 你怎么说话\n" + speech.renderForPrompt() + extra
        }
        // 没有学来的话风：用旧人设卡里的语速/句子/口头禅，再兜一句通用要求。
        val voice = input.card.voice
        val lines = buildList {
            add("- 句子短（1-2 句），少感叹号，少书面词。")
            if (voice.pace.isNotBlank()) add("- 语速：${voice.pace.trim().take(80)}")
            if (voice.sentence.isNotBlank()) add("- 句子：${voice.sentence.trim().take(80)}")
            voice.catchphrases.map { it.trim() }.filter { it.isNotBlank() }
                .take(SpeechStyle.MAX_PROMPT_PHRASES)
                .takeIf { it.isNotEmpty() }
                ?.let { add("- 参考你平时会说的话（自然使用，不要每句都套）：" + it.joinToString("、") { phrase -> "「$phrase」" }) }
            if (voice.quirks.isNotBlank()) add("- 小习惯：${voice.quirks.trim().take(100)}")
            add("- 不要写动作描写、不要括号旁白。")
        }
        return "# 你怎么说话\n" + lines.joinToString("\n")
    }

    private fun relationshipSection(input: Inputs): String {
        val lines = mutableListOf<String>()
        val relation = input.relation.trim()
        if (relation.isNotBlank()) lines += "- 你是对方的$relation。"
        val address = input.address.trim()
        if (address.isNotBlank()) lines += "- 你称呼对方「$address」。"
        val bond = input.card.bond.trim().take(BOND_MAX)
        if (bond.isNotBlank()) lines += bond
        if (lines.isEmpty()) lines += "- 按你们聊出来的关系自然相处，不用每次重新介绍自己。"
        return "# 你们的关系\n" + lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ 工具

    private fun clockText(now: Long, zone: ZoneId): String {
        if (now <= 0L) return "现在是你们聊天的这一刻。"
        val zoned = runCatching { Instant.ofEpochMilli(now).atZone(zone) }.getOrNull()
            ?: return "现在是你们聊天的这一刻。"
        val weekday = WEEKDAYS[zoned.dayOfWeek] ?: zoned.dayOfWeek.name
        return "现在是${weekday} ${zoned.format(HH_MM)}。"
    }

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
}
