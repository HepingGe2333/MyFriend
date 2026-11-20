package com.yap.app.domain

/**
 * 两端（Android / iOS）与 `agentd` 共用的提示词**段落标题、行模板与时间格式**。
 *
 * 为什么要有这个文件：同一个标题在仓库里至少有三份——Android 的 `PromptBuilder`、
 * iOS 的 `YapKit/Chat/PromptBuilder.swift`、以及 `spec/` 里的规范文本。改一处忘一处，
 * 「两端逐字一致」就靠人肉 grep。这里把**已经稳定的字面量**收进 `:agentcore`，
 * 两端改成引用同一份常量即可（迁移由后续 agent 做；本文件不删除任何现有类）。
 *
 * 与既有共享常量的关系：
 * - 身份/世界/状态/话风/关系/边界六段来自 [PersonaPromptScaffold]（`scaffold.json` 已有共享实现），
 *   这里只做**统一入口**，不复制字符串；
 * - 工具能力段标题、心情段标题、反 AI 段标题、时间格式此前只在某一端有常量，这里新增；
 * - 备忘录 / 主题总结的标题与上限来自 [MemoryContract]（0.15 上下文/记忆 v2）。
 *
 * 硬约束：纯 Kotlin、不读时钟、不读资源、不依赖界面语言。语言相关的标题/模板见
 * [PromptLanguage] / [PromptTemplates]；本文件只钉中文（规范原文）与格式。
 */
object PromptContract {

    /** 段落标题：键名稳定，值逐字与规范/现有实现一致。 */
    object Section {
        // 六段人设脚手架：唯一实现在 PersonaPromptScaffold，这里只转发。
        const val IDENTITY: String = PersonaPromptScaffold.Section.IDENTITY
        const val WORLD: String = PersonaPromptScaffold.Section.WORLD
        const val STATE: String = PersonaPromptScaffold.Section.STATE
        const val VOICE: String = PersonaPromptScaffold.Section.VOICE
        const val RELATIONSHIP: String = PersonaPromptScaffold.Section.RELATIONSHIP
        const val BOUNDARIES: String = PersonaPromptScaffold.Section.BOUNDARIES

        /** 「你能做什么」段标题（`spec/persona-guidance/README.md` §1.3）。 */
        const val CAPABILITIES = "# 你能做什么"

        /** 「你的心情」段标题（`spec/mood/README.md` §3）。 */
        const val MOOD = "# 你的心情"

        /** 「别像 AI」段标题（`spec/mood/README.md` §5）。 */
        const val ANTI_AI = "# 别像 AI"

        /** 0.15 会话备忘录总标题（`docs/design/context-memory-v2.md`）。 */
        const val MEMO: String = MemoryContract.Injection.TITLE

        /** 0.15 心理活动标题（`docs/design/context-memory-v2.md` 注入示例）。 */
        const val MENTAL_ACTIVITY = "# 你刚才心里想"

        /** 语言小节的标题（沿用地道的 `## 语言` / `## Language` 形式）。 */
        const val LANGUAGE = "## 语言"

        /** 所有固定标题（顺序即提示词里的推荐出现顺序）。 */
        val all: Map<String, String> = linkedMapOf(
            "identity" to IDENTITY,
            "world" to WORLD,
            "state" to STATE,
            "voice" to VOICE,
            "relationship" to RELATIONSHIP,
            "capabilities" to CAPABILITIES,
            "mood" to MOOD,
            "antiAi" to ANTI_AI,
            "memo" to MEMO,
            "mentalActivity" to MENTAL_ACTIVITY,
            "boundaries" to BOUNDARIES,
            "language" to LANGUAGE
        )
    }

    /**
     * 「你能做什么」段的**行格式**（两端逐字一致）。
     *
     * 规范只钉了形状：`- 工具名(必填, 可选?)：一句话用途` + 换行 `· 区别：…`。
     * 工具清单本身由各端的工具注册表生成（本文件不持有工具名，避免和 schema 分成两份）。
     */
    object ToolCapability {
        const val TITLE = Section.CAPABILITIES

        /** 段首说明（0.13 起 Android 用的这一句；iOS 的等价说明由调用方传）。 */
        const val LEAD =
            "下面这些是你自己的手。需要的时候直接调用，别为了显得勤快而用；用不上就不要调用。"

        /** 工具名与用途之间的分隔符（全角冒号，与规范一致）。 */
        const val NAME_SEPARATOR = "："

        /** 与其它工具的区别行：两个空格 + 中点 + 「区别：」。 */
        const val CONTRAST_PREFIX = "  · 区别："

        /** 参数签名里可选参数的标记：`hours?`。 */
        const val OPTIONAL_MARK = "?"

        /** 参数签名里参数之间的分隔符。 */
        const val PARAM_SEPARATOR = ", "

        /** 段末「没有联网能力」的说明（0.15 起两端统一用这一句）。 */
        const val OFFLINE_NOTE = "- 你现在没有联网能力。涉及实时信息时，老实说自己不确定，别编。"

        /**
         * 参数签名：`name(required, optional?)`。
         *
         * 顺序由调用方给（必填在前、可选在后），本函数不排序：排序规则属于各端 schema 的
         * 展示约定，共享的是**标点与问号**，不是 schema 遍历顺序。空名会被丢掉。
         */
        fun signature(
            name: String,
            required: List<String> = emptyList(),
            optional: List<String> = emptyList()
        ): String {
            val toolName = name.trim()
            val req = required.map { it.trim() }.filter { it.isNotEmpty() }
            val opt = optional.map { it.trim() }.filter { it.isNotEmpty() }
            val params = req + opt.map { it + OPTIONAL_MARK }
            if (params.isEmpty()) return toolName
            return toolName + "(" + params.joinToString(PARAM_SEPARATOR) + ")"
        }

        /**
         * 一行工具说明：`- signature：description`；[contrast] 非空时再加一行 `  · 区别：…`。
         * 描述为空时只输出 `- signature`（不印一个空冒号）。
         */
        fun line(signature: String, description: String, contrast: String = ""): String {
            val sig = signature.trim()
            val desc = description.trim()
            val head = if (desc.isEmpty()) "- $sig" else "- $sig$NAME_SEPARATOR$desc"
            val detail = contrast.trim()
            return if (detail.isEmpty()) head else head + "\n" + CONTRAST_PREFIX + detail
        }

        /** 把若干行拼成完整段（标题 + 行）；不给段首说明，调用方自己决定要不要 [LEAD]。 */
        fun section(lines: List<String>): String =
            (listOf(TITLE) + lines.map { it.trimEnd() }.filter { it.isNotEmpty() }).joinToString("\n")
    }

    /** 「你的心情」段：标题 + 直觉标签 + 三维数值的固定写法（`spec/mood/README.md` §3）。 */
    object Mood {
        const val TITLE = Section.MOOD

        /** 第一行：`心情：{label}（强度 {intensity}/5）。` */
        const val LINE = "心情：{label}（强度 {intensity}/5）。"

        /** 第二行：三维数值（愉快度 -2..2，唤醒度/掌控感 0..5）。 */
        const val DIMENSIONS_LINE =
            "愉快度 {valence} / 唤醒度 {arousal}/5 / 掌控感 {dominance}/5。"

        /** 强度定义域：1..5（越界夹取，脏数据不写进提示词）。 */
        const val INTENSITY_MIN = 1
        const val INTENSITY_MAX = 5

        /** 三维的物理范围（夹取用；规范里是 -2..2 / 0..5 / 0..5）。 */
        const val VALENCE_MIN = -2
        const val VALENCE_MAX = 2
        const val AROUSAL_MIN = 0
        const val AROUSAL_MAX = 5
        const val DOMINANCE_MIN = 0
        const val DOMINANCE_MAX = 5

        /** `心情：有点累（强度 3/5）。`；标签去空白，强度夹到 1..5。 */
        fun line(label: String, intensity: Int): String = LINE
            .replace("{label}", label.trim())
            .replace("{intensity}", intensity.coerceIn(INTENSITY_MIN, INTENSITY_MAX).toString())

        /** `愉快度 -1 / 唤醒度 2/5 / 掌控感 3/5。`（三个值都夹到物理范围）。 */
        fun dimensionsLine(valence: Int, arousal: Int, dominance: Int): String =
            DIMENSIONS_LINE
                .replace("{valence}", valence.coerceIn(VALENCE_MIN, VALENCE_MAX).toString())
                .replace("{arousal}", arousal.coerceIn(AROUSAL_MIN, AROUSAL_MAX).toString())
                .replace("{dominance}", dominance.coerceIn(DOMINANCE_MIN, DOMINANCE_MAX).toString())
    }

    /**
     * 反 AI 味段（`spec/mood/README.md` §5）。
     *
     * 文案与 Android `PromptBuilder.ANTI_AI_RULES` / iOS `AntiAIPrompt.rules` **逐字一致**；
     * 两端目前各写一份，这里先收成共享常量，迁移时两端改成引用本对象。
     */
    object AntiAi {
        const val TITLE = Section.ANTI_AI

        /** 七条硬约束（顺序：最常犯 → 最隐蔽）。 */
        val RULES: List<String> = listOf(
            "不要每句话都反问；不需要每轮都把话题抛回去。",
            "不要总结对方说的话，也不要说「所以你的意思是…」。",
            "不要列点、不要「首先/其次/最后」，不要写成一段说明书。",
            "不要每次都认同或安慰；可以不同意、可以只回一句、也可以不回（stay_silent）。",
            "句子可以短、可以不完整；不要重复同一个口头禅或同一个开头。",
            "不主动给建议、清单或心理分析；不知道就说不知道。",
            "不想说话时调用 stay_silent（只已读不回），不要用文字说「我不回」；对方在说重要的事时不要用。"
        )

        /** 完整段：标题 + 每条一行 `- …`。 */
        fun section(): String = (listOf(TITLE) + RULES.map { "- $it" }).joinToString("\n")
    }

    /**
     * 边界段：直接复用 `spec/persona-guidance/scaffold.json` 的四条（唯一实现在
     * [PersonaPromptScaffold.Line.boundaryRules]），不再复制一份。
     */
    object Boundaries {
        const val TITLE = Section.BOUNDARIES
        val RULES: List<String> = PersonaPromptScaffold.Line.boundaryRules

        fun section(): String = (listOf(TITLE) + RULES.map { "- $it" }).joinToString("\n")
    }

    /**
     * 会话备忘录标题/长度的**转发入口**（真实规则在 [MemoryContract]）。
     *
     * 保留这一层是因为提示词装配方更愿意从 `PromptContract` 一处拿所有段标题；
     * 迁移时不会出现「标题引这里、上限引那里」的两套口径。
     */
    object Memo {
        const val TITLE = MemoryContract.Injection.TITLE
        const val LONG_TITLE = MemoryContract.Injection.LONG_TERM_TITLE
        const val SHORT_TITLE = MemoryContract.Injection.SHORT_TERM_TITLE
        const val TOPIC_TITLE = MemoryContract.Injection.TOPIC_TITLE

        const val LONG_MAX_CHARS = MemoryContract.Summary.LONG_TERM_MAX_CHARS
        const val SHORT_MAX_CHARS = MemoryContract.Summary.SHORT_TERM_MAX_CHARS
        const val TOPIC_SEGMENT_MAX_CHARS = MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS
        const val TOPIC_MAX_SEGMENTS = MemoryContract.Summary.TOPIC_MAX_SEGMENTS

        /** 拼备忘录的纯规则直接委托 [MemoryContract.Injection.notes]。 */
        fun block(
            longTerm: String? = null,
            shortTerm: String? = null,
            topics: List<MemoryContract.TopicSegment> = emptyList()
        ): String = MemoryContract.Injection.notes(longTerm = longTerm, shortTerm = shortTerm, topics = topics)
    }

    /**
     * 心理活动（私密念头）：标题 + 注入行的固定前缀 + 60 字上限
     * （`spec/experience/README.md` §3；0.15 标题见 `docs/design/context-memory-v2.md`）。
     */
    object MentalActivity {
        const val TITLE = Section.MENTAL_ACTIVITY

        /** 「你刚才心里想：…」——发给模型、不发给用户。 */
        const val LINE_PREFIX = "你刚才心里想："

        /** 念头最多 60 字（规范 §3）。 */
        const val MAX_THOUGHT_CHARS = 60

        /** 压成一行、去空白、截到 [MAX_THOUGHT_CHARS]；空念头返回空串。 */
        fun sanitizeThought(raw: String?): String =
            MemoryContract.Injection.flatten(raw).take(MAX_THOUGHT_CHARS)

        /** 注入行；空念头返回 null（调用方不加这一块）。 */
        fun line(thought: String?): String? {
            val text = sanitizeThought(thought)
            return if (text.isEmpty()) null else "$LINE_PREFIX$text"
        }

        /** 完整块：标题 + 注入行；空念头返回空串。 */
        fun block(thought: String?): String {
            val text = sanitizeThought(thought)
            return if (text.isEmpty()) "" else "$TITLE\n$LINE_PREFIX$text"
        }
    }

    /**
     * 时间格式（`spec/context-time/README.md`）：`YYYY-MM-DD 周X HH:mm（UTC±HH:mm）`。
     *
     * 只放**格式与拼装**，不读时钟：日期、星期、时刻、偏移由调用方从平台时间 API 算好传进来，
     * 这样「23:30 入睡」「周日 19:40」这类边界在测试里能精确构造。
     */
    object Time {

        /** 时刻格式：24 小时制 `HH:mm`。 */
        const val HH_MM_PATTERN = "HH:mm"

        /** 日期格式：`yyyy-MM-dd`。 */
        const val DATE_PATTERN = "yyyy-MM-dd"

        /** 时区偏移前缀：`UTC+08:00` / `UTC-05:30` / `UTC`。 */
        const val UTC_PREFIX = "UTC"

        /** 「你此刻的状态」第一行模板（唯一实现在脚手架）。 */
        const val STATE_LINE: String = PersonaPromptScaffold.Line.STATE_LINE

        /** 中文星期（周一为一周之始，与 `spec/persona-guidance` 的世界日程一致）。 */
        val WEEKDAYS_ZH: List<String> = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /** 0 = 周一 … 6 = 周日；越界返回 null（宁可省略也不印一个错的星期）。 */
        fun weekdayZh(mondayBasedIndex: Int): String? =
            WEEKDAYS_ZH.getOrNull(mondayBasedIndex)

        /**
         * 时区偏移的规范写法：`UTC+08:00` / `UTC-05:30` / `UTC`。
         *
         * 用整数分钟而不是平台 `ZoneOffset`：`:agentcore` 不依赖 `java.time` / Foundation。
         * 超过 ±18 小时按 ±18 小时夹取（地球上的时区不会更离谱）。
         */
        fun zoneOffsetLabel(offsetMinutes: Int): String {
            val total = offsetMinutes.coerceIn(-18 * 60, 18 * 60)
            if (total == 0) return UTC_PREFIX
            val sign = if (total < 0) "-" else "+"
            val abs = if (total < 0) -total else total
            return UTC_PREFIX + sign + padTwo(abs / 60) + ":" + padTwo(abs % 60)
        }

        /** `现在是{weekday} {time}。`：两个占位符替换，模板一个字不改。 */
        fun stateLine(weekday: String, time: String): String = STATE_LINE
            .replace("{weekday}", weekday.trim())
            .replace("{time}", time.trim())

        /**
         * 聊天之外（主动消息处境描述）共用的一行：`2026-11-18 周三 19:40（UTC+08:00）`。
         *
         * 与 Android `PromptBuilder.clockText` / iOS 的状态段同一形状；只拼字符串，
         * 不决定「拿不到时间时怎么办」（调用方自己省略整行）。
         */
        fun clockText(date: String, weekday: String, time: String, offsetMinutes: Int): String =
            date.trim() + " " + weekday.trim() + " " + time.trim() +
                "（" + zoneOffsetLabel(offsetMinutes) + "）"

        private fun padTwo(value: Int): String = if (value < 10) "0$value" else value.toString()
    }
}
