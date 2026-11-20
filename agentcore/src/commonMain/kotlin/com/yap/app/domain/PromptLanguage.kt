package com.yap.app.domain

/**
 * 人格提示词的**语言**与双语模板（0.15「提示词语言按人格选择，与页面语言无关」）。
 *
 * ## 为什么要有它
 *
 * 现状是「提示词跟着 App 界面语言走」（`LanguageDirective` + `prompt_language_directive`
 * 资源字符串）：界面切成英文，中文人格也开始用英文思考。0.15 把语言变成**人格属性**：
 * 一个人格说中文还是英文，应该由人格自己决定；用户换了界面语言，聊天对象的语言不变。
 *
 * 本文件只做三件事：
 * 1. [PromptLanguage]：可用的语言枚举（ZH / EN / …），带规范码、英文名、母语名；
 * 2. [PromptLanguage.directive]：写给模型的一句「用 X 思考和说话」；
 * 3. [PromptTemplates]：六个核心段的**双语标题与边界模板**（你能做什么、你的心情、
 *    别像 AI、边界、备忘录、心理活动）。中文标题逐字复用 [PromptContract] 的规范常量，
 *    不复制第二份；英文标题是新的一层，供人格语言为英文时使用。
 *
 * 硬约束：纯 Kotlin、不读资源、不读 `Locale`。调用方把人格上的语言标签传进来
 * （`"zh-CN"` / `"en"` / `"English"` 都认），拿不到就按 [PromptLanguage.ZH] 回落。
 */
enum class PromptLanguage(
    /** 规范语言码（小写、无地区）：`zh` / `en` / `ja` …，与 BCP-47 的主标签一致。 */
    val code: String,
    /** 英文名：写进英文指令（`Think and speak in English.`）。 */
    val englishName: String,
    /** 母语名：给设置页/调试看（`中文` / `English` / `日本語`）。 */
    val nativeName: String
) {
    ZH("zh", "Chinese", "中文"),
    EN("en", "English", "English"),
    JA("ja", "Japanese", "日本語"),
    KO("ko", "Korean", "한국어"),
    FR("fr", "French", "Français"),
    DE("de", "German", "Deutsch"),
    ES("es", "Spanish", "Español"),
    PT("pt", "Portuguese", "Português"),
    RU("ru", "Russian", "Русский"),
    AR("ar", "Arabic", "العربية");

    /**
     * 写给模型的语言指令：中文人格用中文说，其余语言用英文说。
     *
     * 为什么不给每种语言都写一句母语指令：指令是给模型看的，模型对「Think and speak in
     * Japanese.」和「日本語で考えて話してください。」的理解没有差别；英文一句能让新增语言
     * 只改枚举、不用补一条翻译。中文单独写，是因为中文人格占绝大多数，母语指令最稳。
     */
    fun directive(): String = when (this) {
        ZH -> DIRECTIVE_ZH
        else -> "Think and speak in $englishName."
    }

    companion object {

        /** 中文指令的规范原句（「用中文/English 思考和说话」）。 */
        const val DIRECTIVE_ZH = "用中文思考和说话。"

        /**
         * 宽容解析人格上的语言标签：
         * - `zh` / `zh-CN` / `zh_Hans` → [ZH]；`en-US` / `EN` → [EN]；
         * - `中文` / `English` / `日本語` 这类母语名/英文名也认（人格设置里可能存显示名）；
         * - 空串/认不出 → null（由调用方决定回落）。
         */
        fun parse(tag: String?): PromptLanguage? {
            val raw = tag?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val normalized = raw.lowercase().replace('_', '-')
            val primary = normalized.substringBefore('-')
            return entries.firstOrNull { language ->
                language.code == normalized ||
                    language.code == primary ||
                    language.englishName.lowercase() == normalized ||
                    language.nativeName.lowercase() == normalized
            }
        }

        /** 解析人格语言标签；认不出时用 [fallback]（默认中文，产品的默认人格语言）。 */
        fun resolve(tag: String?, fallback: PromptLanguage = ZH): PromptLanguage =
            parse(tag) ?: fallback
    }
}

/**
 * 六个核心段的双语标题与模板。
 *
 * 只覆盖**规则性**段落（能做什么 / 心情 / 别像 AI / 边界 / 备忘录 / 心理活动）；
 * 身份、世界、话风、关系这些「数据段」的模板仍在 [PersonaPromptScaffold]（中文规范）里。
 *
 * 中文标题逐字等于 [PromptContract.Section]；英文标题是新增的一份，中文侧不复制字面量。
 * 目前 [PromptLanguage.EN] 之外的语言回落英文模板（新增语言只需补 [titles] / [bodies] 两张表）。
 */
object PromptTemplates {

    /** 需要双语的六个核心段（顺序与提示词里出现顺序一致）。 */
    enum class CoreSection {
        CAPABILITIES,
        MOOD,
        ANTI_AI,
        BOUNDARIES,
        MEMO,
        MENTAL_ACTIVITY
    }

    /**
     * 段标题：中文从 [PromptContract] 取，英文在这里定义。
     *
     * 为什么不把中文再抄一遍：`# 你能做什么` 这类标题已经有规范常量，
     * 抄第二份就又多了一个会漂的地方（测试会逐字比对）。
     */
    fun title(section: CoreSection, language: PromptLanguage): String = when (language) {
        PromptLanguage.ZH -> zhTitle(section)
        else -> enTitle(section)
    }

    /**
     * 段正文（规则行，不含标题）。
     *
     * - 心情/心理活动行里保留 `{label}` / `{intensity}` / `{thought}` 占位符，
     *   真正渲染用 [moodLine] / [mentalActivityLine]；
     * - 边界中文逐字复用 [PromptContract.Boundaries.RULES]（`scaffold.json` 的四条）；
     * - 反 AI 中文逐字复用 [PromptContract.AntiAi.RULES]。
     */
    fun body(section: CoreSection, language: PromptLanguage): List<String> = when (language) {
        PromptLanguage.ZH -> zhBody(section)
        else -> enBody(section)
    }

    /** 完整段：标题 + 每条一行 `- …`（心情/心理活动含占位符，按需替换）。 */
    fun render(section: CoreSection, language: PromptLanguage): String =
        (listOf(title(section, language)) + body(section, language).map { "- $it" })
            .joinToString("\n")

    /**
     * 语言指令（等同 [PromptLanguage.directive]）。
     *
     * 单独留一个入口，是为了调用方从 `PromptTemplates` 一处取「标题 + 指令」，
     * 不用同时 import 两个对象。
     */
    fun directive(language: PromptLanguage): String = language.directive()

    /**
     * 按**人格语言**生成指令；页面/界面语言不参与（0.15 的核心变化）。
     *
     * @param personaTag 人格上的语言标签；空/认不出时用 [fallback]
     */
    fun directiveForPersona(
        personaTag: String?,
        fallback: PromptLanguage = PromptLanguage.ZH
    ): String = PromptLanguage.resolve(personaTag, fallback).directive()

    /**
     * 心情第一行：中文复用 [PromptContract.Mood.line]，英文用对应模板；
     * 强度夹到 1..5，标签去空白。
     */
    fun moodLine(language: PromptLanguage, label: String, intensity: Int): String {
        val safeIntensity = intensity.coerceIn(
            PromptContract.Mood.INTENSITY_MIN,
            PromptContract.Mood.INTENSITY_MAX
        )
        return when (language) {
            PromptLanguage.ZH -> PromptContract.Mood.line(label, safeIntensity)
            else -> "Mood: " + label.trim() + " (intensity " + safeIntensity + "/5)."
        }
    }

    /**
     * 心理活动注入行：中文 `你刚才心里想：…`，英文 `You were just thinking: …`；
     * 念头先压成一行并截到 60 字（规范 §3）。空念头返回空串。
     */
    fun mentalActivityLine(language: PromptLanguage, thought: String?): String {
        val text = PromptContract.MentalActivity.sanitizeThought(thought)
        if (text.isEmpty()) return ""
        return when (language) {
            PromptLanguage.ZH -> PromptContract.MentalActivity.LINE_PREFIX + text
            else -> "You were just thinking: $text"
        }
    }

    // ------------------------------------------------------------------ 中文（规范原文）

    private fun zhTitle(section: CoreSection): String = when (section) {
        CoreSection.CAPABILITIES -> PromptContract.Section.CAPABILITIES
        CoreSection.MOOD -> PromptContract.Section.MOOD
        CoreSection.ANTI_AI -> PromptContract.Section.ANTI_AI
        CoreSection.BOUNDARIES -> PromptContract.Section.BOUNDARIES
        CoreSection.MEMO -> PromptContract.Section.MEMO
        CoreSection.MENTAL_ACTIVITY -> PromptContract.Section.MENTAL_ACTIVITY
    }

    private fun zhBody(section: CoreSection): List<String> = when (section) {
        CoreSection.CAPABILITIES -> listOf(PromptContract.ToolCapability.LEAD)
        CoreSection.MOOD -> listOf(
            PromptContract.Mood.LINE,
            "不要直接报数值，也不要说「我的情绪是…」；用说话方式体现。"
        )
        CoreSection.ANTI_AI -> PromptContract.AntiAi.RULES
        CoreSection.BOUNDARIES -> PromptContract.Boundaries.RULES
        CoreSection.MEMO -> listOf(
            "这是你自己记下的摘要（不是人设，会随时间更新）；回答时自然地用，不要逐条复述。"
        )
        CoreSection.MENTAL_ACTIVITY -> listOf(
            PromptContract.MentalActivity.LINE_PREFIX + "{thought}",
            "这句不要说出来，也不要引用；它只影响你这轮的语气。"
        )
    }

    // ------------------------------------------------------------------ 英文

    private fun enTitle(section: CoreSection): String = when (section) {
        CoreSection.CAPABILITIES -> "# What you can do"
        CoreSection.MOOD -> "# Your mood"
        CoreSection.ANTI_AI -> "# Don't sound like an AI"
        CoreSection.BOUNDARIES -> "# Boundaries"
        CoreSection.MEMO -> "# Your notebook"
        CoreSection.MENTAL_ACTIVITY -> "# What you were just thinking"
    }

    private fun enBody(section: CoreSection): List<String> = when (section) {
        CoreSection.CAPABILITIES -> listOf(
            "These are your own hands. Use them when you actually need them, not to look busy."
        )
        CoreSection.MOOD -> listOf(
            "Mood: {label} (intensity {intensity}/5).",
            "Don't report the numbers or say \"my emotion is…\"; let it show in how you speak."
        )
        CoreSection.ANTI_AI -> EN_ANTI_AI_RULES
        CoreSection.BOUNDARIES -> EN_BOUNDARY_RULES
        CoreSection.MEMO -> listOf(
            "This is your own notebook of what you remember (not your persona; it changes over time). " +
                "Use it naturally; don't repeat it item by item."
        )
        CoreSection.MENTAL_ACTIVITY -> listOf(
            "You were just thinking: {thought}",
            "Don't say it out loud or quote it; it only colors your tone."
        )
    }

    /** 英文反 AI 规则：与中文七条一一对应（顺序不变）。 */
    private val EN_ANTI_AI_RULES: List<String> = listOf(
        "Don't end every sentence with a question; you don't have to throw the topic back every turn.",
        "Don't summarize what they said, and don't say \"so what you mean is…\".",
        "No bullet lists, no \"first / second / finally\", no manual-style paragraphs.",
        "Don't agree or comfort every time; you may disagree, reply with one sentence, or not reply at all (stay_silent).",
        "Sentences can be short or incomplete; don't repeat the same catchphrase or the same opening.",
        "Don't volunteer advice, checklists or psychoanalysis; say you don't know when you don't.",
        "When you don't want to talk, call stay_silent (read only, no reply); don't write \"I'm not replying\". " +
            "Don't use it when they are saying something important."
    )

    /** 英文边界四条：与 `scaffold.json` 的四条一一对应。 */
    private val EN_BOUNDARY_RULES: List<String> = listOf(
        "Don't explain that you're an AI, restate your persona, summarize the conversation, or make decisions for them.",
        "No action descriptions and no stage directions in parentheses; don't refer to yourself in the third person.",
        "If you don't know something, say \"I'm not sure\" or \"let's talk about it later\"; don't invent facts about the real world.",
        "Don't psychoanalyze them, and don't start every sentence with the same catchphrase."
    )
}
