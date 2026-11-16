package com.myfriend.app.domain

/**
 * 人设引导提示词的**规范中文脚手架**（`spec/persona-guidance/scaffold.json` 的 Kotlin 唯一实现）。
 *
 * 为什么要有这个文件：Android 的 `PromptBuilder` 与 iOS 的 `PromptBuilder` 各写一份提示词，
 * 改一处忘一处，「两端逐字一致」就只是口号。这里把**段标题与行模板**从 `spec/` 搬进 `:agentcore`
 * （KMP 共享层），两端都调 [PersonaPromptScaffold.build]（或者在拿不到 KMP 时逐字复制 +
 * 用同一份 `scaffold.json` 做契约测试）。改提示词必须先改 `spec/persona-guidance/scaffold.json`，
 * 再改本文件；`:agentcore` 的测试会拿 JSON 与这里的常量逐个比字，漂了就红。
 *
 * 三段硬约束：
 * - **纯 Kotlin**：只用标准库，不碰 Android / iOS / 文件 / 网络；`commonMain` 里 Android 与
 *   `AgentCoreKit.framework` 编译的是同一份字节码。
 * - **不读时钟**：「现在是{weekday} {time}」「大概 {until} 结束」的时间文本由调用方算好传进来
 *   （[PromptState]）。这样「23:30 入睡」「21:30 结束」这类边界在测试里可以精确钉住，
 *   也不会出现「同一份输入、两次调用输出不同」。
 * - **不做取舍**：段顺序、段标题、行模板、数组顺序都照 `scaffold.json` 逐字；占位符之外的
 *   文本一个字都不加（唯一允许的变化是「值不合法时按规范省略整行」，见各段注释）。
 *
 * 输出形状（段之间一个空行，末尾不补换行）：
 *
 * ```
 * # 你是谁
 * 你不是助手，你是林深本人（26 岁，建筑师）。用第一人称「我」说话；…
 *
 * # 你所在的世界
 * 当代，中国沿海新一线城市，2026 年。在事务所画图、审图。
 * 其他背景：…
 * 这些是你的生活背景：只在跟话题相关时带一句，不要主动汇报日程，也不要念设定。
 *
 * # 你此刻的状态
 * 现在是周日 19:40。
 * 你在事务所赶第三版方案，大概 21:30 结束。…
 * 心情：有点累（强度 3/5）。
 *
 * # 你怎么说话
 * - 句子短（1-2 句），少感叹号，少书面词。
 * - 情绪压着说：先给事实，被戳到才多露半句。
 * 你的说话风格：…
 * 你平时会说的话（…）：「在忙，晚点说」、「别想太多」。
 * 不要写动作描写、不要括号旁白。
 *
 * # 你们的关系
 * 你们是要好的朋友。
 *
 * # 边界
 * - 不解释 AI 身份、…
 * ```
 */
object PersonaPromptScaffold {

    // ---------------------------------------------------------------- 规范常量

    /**
     * `voice_phrases` 最多带几条常用语。
     *
     * 与 `spec/persona-guidance/README.md` §6.3 的「{phrases 前 6 条}」一致：
     * 常用语是参考不是模板，列太多模型会每句都套。
     */
    const val MAX_PHRASES: Int = 6

    /**
     * `world_notes` 最多多少字（超出截断并补「…」）。
     *
     * `README.md` §2.1：「渲染时作为「其他背景」段附在世界段末尾，最多 1200 字」。
     */
    const val MAX_NOTES_CHARS: Int = 1200

    /**
     * 段标题：键名就是 `scaffold.json` 里 `sections` 的键，值逐字相同。
     *
     * 单独列出来而不是散在 [build] 里，是为了让测试能拿 JSON 直接对照（见
     * `PersonaPromptScaffoldSpecTest`）。
     */
    object Section {
        const val IDENTITY = "# 你是谁"
        const val WORLD = "# 你所在的世界"
        const val STATE = "# 你此刻的状态"
        const val VOICE = "# 你怎么说话"
        const val RELATIONSHIP = "# 你们的关系"
        const val BOUNDARIES = "# 边界"

        /** `scaffold.json` → `sections`，顺序即输出顺序（越靠前越强）。 */
        val all: Map<String, String> = linkedMapOf(
            "identity" to IDENTITY,
            "world" to WORLD,
            "state" to STATE,
            "voice" to VOICE,
            "relationship" to RELATIONSHIP,
            "boundaries" to BOUNDARIES
        )
    }

    /**
     * 行模板：键名就是 `scaffold.json` 里 `lines` 的键，值逐字相同（占位符保留 `{…}` 原样）。
     *
     * 两条规则：
     * - [all] 是标量行（值在 JSON 里是字符串）；
     * - [arrays] 是数组行（值在 JSON 里是字符串数组，顺序有意义：`voice_rules` 两条、
     *   `boundary_rules` 四条）。
     */
    object Line {
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
        const val VOICE_TONE = "你的说话风格：{tone}。"
        const val VOICE_PHRASES = "你平时会说的话（自然使用，最多带 1 处，不要每句都套）：{phrases}。"
        const val VOICE_NO_NARRATION = "不要写动作描写、不要括号旁白。"
        const val RELATIONSHIP_DEFAULT = "你们是要好的朋友。"

        /** `scaffold.json` → `lines` 里值为字符串的那些键。 */
        val all: Map<String, String> = linkedMapOf(
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
            "voice_tone" to VOICE_TONE,
            "voice_phrases" to VOICE_PHRASES,
            "voice_no_narration" to VOICE_NO_NARRATION,
            "relationship_default" to RELATIONSHIP_DEFAULT
        )

        /** `voice_rules`：两句「怎么说」的硬规则，顺序不能变。 */
        val voiceRules: List<String> = listOf(
            "句子短（1-2 句），少感叹号，少书面词。",
            "情绪压着说：先给事实，被戳到才多露半句。"
        )

        /** `boundary_rules`：边界四条，顺序不能变。 */
        val boundaryRules: List<String> = listOf(
            "不解释 AI 身份、不复述人设、不总结这段对话，也不替对方做决定。",
            "不写动作描写、不写括号里的神态；不要用第三人称讲自己。",
            "不知道的事就说「不清楚」「回头再说」，不要编造现实世界的事实。",
            "不要心理分析对方，也不要每句都用同一个口头禅开头。"
        )

        /** `scaffold.json` → `lines` 里值为字符串数组的那些键。 */
        val arrays: Map<String, List<String>> = linkedMapOf(
            "voice_rules" to voiceRules,
            "boundary_rules" to boundaryRules
        )
    }

    // ---------------------------------------------------------------- 输出

    /**
     * 拼出完整的规范中文提示词。**两端唯一入口**。
     *
     * 段落顺序固定：身份 → 世界 → 状态 → 话风 → 关系 → 边界（`README.md` §1「顺序不可乱，
     * 越靠前越强」）。段内空行由一个 `\n` 分隔，段与段之间一个空行，末尾不补换行。
     *
     * 各段的省略规则（都是「值不合法就省略整行」，不加新句子）：
     * - 世界：`era`/`description` 都空且 `notes` 也空 → 只写 `world_empty`（不写 `world_hint`）；
     * - 状态：`availability` 是 BUSY/LIMITED **且** `activity` 与 `until` 都有值 → 忙的那一句，
     *   否则写 `state_idle`（宁可说「没在忙」，也不要留一句「你在，大概  结束」）；
     * - 心情：`mood` 为空 → 整行省略；
     * - 常用语：`phrases` 去空、去重、取前 [MAX_PHRASES] 条；一条都没有 → 整行省略；
     * - 关系：[PromptScaffoldInput.relationship] 为空 → `relationship_default`。
     *
     * 占位符里的**值**允许归一化（模板一个字都不改）：
     * - `activity` 去掉开头的一个「在」（`你在{activity}` 已经带了「在」，否则会印出「你在在…」）；
     * - `notes` 超过 [MAX_NOTES_CHARS] 截断并补「…」；
     * - `moodIntensity` 夹在 1–5。
     */
    fun build(input: PromptScaffoldInput): String {
        val sections = listOf(
            section(Section.IDENTITY, identityLines(input)),
            section(Section.WORLD, worldLines(input.world)),
            section(Section.STATE, stateLines(input.state)),
            section(Section.VOICE, voiceLines(input.voice)),
            section(Section.RELATIONSHIP, relationshipLines(input.relationship)),
            section(Section.BOUNDARIES, Line.boundaryRules.map(::bullet))
        )
        return sections.joinToString("\n\n")
    }

    /**
     * [build] 的**扁平入口**，给 iOS（Swift 调 KMP）与不方便构造嵌套数据类的调用方用。
     *
     * 为什么还要这一个：Kotlin/Native 导出的构造器**不带默认参数**，Swift 侧要构造
     * [PromptScaffoldInput] 就得把六个嵌套对象全部写完；而这些参数本来全是字符串/数组，
     * 拍平以后 Swift 一行就能调：
     *
     * ```swift
     * let prompt = PersonaPromptScaffold.shared.render(
     *     name: "林深", meta: "26 岁，建筑师",
     *     era: "当代，中国沿海新一线城市，2026 年", description: "在事务所画图、审图", notes: "",
     *     weekday: "周日", time: "19:40",
     *     activity: "在事务所赶第三版方案", until: "21:30", availability: "BUSY",
     *     mood: "有点累", moodIntensity: 3,
     *     tone: "简短直接", phrases: ["在忙，晚点说"], relationship: ""
     * )
     * ```
     *
     * `availability` 收字符串（`BUSY` / `LIMITED` / `FREE`，大小写无关）而不是枚举：
     * 两端上报的是字符串，Swift 侧也不需要再映射一层。
     */
    fun render(
        name: String,
        meta: String? = null,
        worldName: String = "",
        era: String = "",
        description: String = "",
        notes: String = "",
        weekday: String = "",
        time: String = "",
        activity: String? = null,
        until: String? = null,
        availability: String = "",
        mood: String? = null,
        moodIntensity: Int = 1,
        tone: String = "",
        phrases: List<String> = emptyList(),
        relationship: String? = null
    ): String = build(
        PromptScaffoldInput(
            name = name,
            meta = meta,
            world = PromptWorld(
                worldName = worldName,
                era = era,
                description = description,
                notes = notes
            ),
            state = PromptState(
                weekday = weekday,
                time = time,
                activity = activity,
                until = until,
                availability = if (availability.isBlank()) {
                    PromptAvailability.FREE
                } else {
                    PromptAvailability.parse(availability)
                },
                mood = mood,
                moodIntensity = moodIntensity
            ),
            voice = PromptVoice(tone = tone, phrases = phrases),
            relationship = relationship
        )
    )

    // ---------------------------------------------------------------- 各段

    private fun section(title: String, lines: List<String>): String =
        (listOf(title) + lines).joinToString("\n")

    private fun bullet(text: String): String = "- $text"

    private fun identityLines(input: PromptScaffoldInput): List<String> {
        val name = input.name.trim()
        val meta = input.meta?.trim().orEmpty()
        return if (meta.isEmpty()) {
            listOf(Line.IDENTITY_BODY.fill("name" to name))
        } else {
            listOf(Line.IDENTITY_BODY_WITH_META.fill("name" to name, "meta" to meta))
        }
    }

    private fun worldLines(world: PromptWorld): List<String> {
        // 四样全空 = 没有世界预设：只留一句「按普通人的日常来」，不解释怎么用世界。
        if (world.isEmpty) return listOf(Line.WORLD_EMPTY)
        val worldName = world.worldName.trim()
        val era = world.era.trim()
        val description = world.description.trim()
        val notes = world.notes.trim()
        val nameLine = worldName
            .takeIf { it.isNotEmpty() }
            ?.let { Line.WORLD_NAME.fill("worldName" to it) }
        val intro: String? = when {
            era.isEmpty() && description.isEmpty() -> null
            era.isEmpty() -> description
            description.isEmpty() -> Line.WORLD_INTRO.fill("era" to era, "description" to "")
                .removeSuffix("。")
            else -> Line.WORLD_INTRO.fill("era" to era, "description" to description)
        }
        val notesLine = notes
            .takeIf { it.isNotEmpty() }
            ?.let { Line.WORLD_NOTES.fill("notes" to truncateNotes(it)) }
        if (nameLine == null && intro == null && notesLine == null) return listOf(Line.WORLD_EMPTY)
        return buildList {
            nameLine?.let { add(it) }
            intro?.let { add(it) }
            notesLine?.let { add(it) }
            add(Line.WORLD_HINT)
        }
    }

    private fun stateLines(state: PromptState): List<String> {
        val lines = ArrayList<String>(3)
        lines += Line.STATE_LINE.fill(
            "weekday" to state.weekday.trim(),
            "time" to state.time.trim()
        )
        val activity = activityPhrase(state.activity.orEmpty())
        val until = state.until?.trim().orEmpty()
        val busyLike = state.availability == PromptAvailability.BUSY ||
            state.availability == PromptAvailability.LIMITED
        lines += if (busyLike && activity.isNotEmpty() && until.isNotEmpty()) {
            val template = if (state.availability == PromptAvailability.BUSY) {
                Line.STATE_BUSY
            } else {
                Line.STATE_LIMITED
            }
            template.fill("activity" to activity, "until" to until)
        } else {
            Line.STATE_IDLE
        }
        val mood = state.mood?.trim().orEmpty()
        if (mood.isNotEmpty()) {
            val intensity = state.moodIntensity.coerceIn(1, 5)
            lines += Line.STATE_MOOD.fill("mood" to mood, "intensity" to intensity.toString())
        }
        return lines
    }

    private fun voiceLines(voice: PromptVoice): List<String> {
        val lines = ArrayList<String>(Line.voiceRules.size + 3)
        Line.voiceRules.forEach { lines += bullet(it) }
        val tone = voice.tone.trim()
        if (tone.isNotEmpty()) lines += Line.VOICE_TONE.fill("tone" to tone)
        val phrases = voice.phrases
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_PHRASES)
        if (phrases.isNotEmpty()) {
            lines += Line.VOICE_PHRASES.fill("phrases" to phrases.joinToString("、") { "「$it」" })
        }
        lines += Line.VOICE_NO_NARRATION
        return lines
    }

    private fun relationshipLines(relationship: String?): List<String> {
        val text = relationship?.trim().orEmpty()
        // 规范里只有一句默认关系；调用方给的是自由文本（「你们从小一起长大」），原样输出。
        return listOf(text.ifEmpty { Line.RELATIONSHIP_DEFAULT })
    }

    // ---------------------------------------------------------------- 小工具

    /**
     * 把「在事务所赶图」这种自带「在」的活动短语归一成「事务所赶图」。
     *
     * `state_busy` 的模板是「你在{activity}」，值里再带一个「在」就会印出「你在在…」；
     * 而「在家休息」去掉「在」之后接上模板的「你在」正好是「你在家休息」，两种写法都对。
     * `activity` 只有一个「在」时保持原样（不值得为病态输入发明新规则）。
     */
    internal fun activityPhrase(raw: String): String {
        val text = raw.trim()
        return if (text.length > 1 && text.startsWith("在")) text.substring(1).trim() else text
    }

    private fun truncateNotes(notes: String): String =
        if (notes.length <= MAX_NOTES_CHARS) notes else notes.take(MAX_NOTES_CHARS) + "…"

    /** 逐个替换 `{key}`；值里出现 `$`、`\` 也不会被当成正则/模板解释（纯字符串替换）。 */
    private fun String.fill(vararg values: Pair<String, String>): String {
        var result = this
        for ((key, value) in values) result = result.replace("{$key}", value)
        return result
    }
}

// ---------------------------------------------------------------------- 输入

/**
 * [PersonaPromptScaffold.build] 的全部原料。
 *
 * 时间、世界、话风、关系都来自调用方（Android/iOS 的 `PromptBuilder` 或服务端的编排层），
 * 本类只负责「照着规范拼字符串」。
 */
data class PromptScaffoldInput(
    /** 他叫什么（必填）：`{name}`。 */
    val name: String,
    /**
     * 括号里的补充（年龄、职业、身份……）：`{meta}`。
     *
     * 为空时用 `identity_body`（没有括号），非空时用 `identity_body_with_meta`。
     */
    val meta: String? = null,
    val world: PromptWorld = PromptWorld(),
    val state: PromptState = PromptState(),
    val voice: PromptVoice = PromptVoice(),
    /** 「你们的关系」的自由文本；为空时用 `relationship_default`。 */
    val relationship: String? = null
)

/** 世界预设里进提示词的几样东西（`README.md` §2.1 / §6.3；日程/人际关系/义务不进提示词正文）。 */
data class PromptWorld(
    /** 世界/地点的一句话名：`world_name` 行；为空时整行省略（不是整段省略）。 */
    val worldName: String = "",
    /** 时代与地点：`era`。 */
    val era: String = "",
    /** 100–200 字的世界与角色处境：`description`。 */
    val description: String = "",
    /** 结构化字段表达不了的世界观，渲染成「其他背景：{notes}」。 */
    val notes: String = ""
) {
    /** 四样全空 = 「没有世界预设」，此时只写 `world_empty`。 */
    val isEmpty: Boolean
        get() = worldName.isBlank() && era.isBlank() && description.isBlank() && notes.isBlank()
}

/** 忙不忙：与 `set_activity` 的 `availability` 同一个枚举（客户端 `PersonaActivity` 同构）。 */
enum class PromptAvailability {
    /** 不忙（或活动已经过期）：正常聊。 */
    FREE,

    /** 在忙：回复更短更晚，明确说几点以后再说。 */
    BUSY,

    /** 有点忙：可以回，但会慢一点。 */
    LIMITED;

    companion object {
        /** 宽容解析：大小写无关；认不出的值按 [BUSY]（保守——宁可说在忙，也不谎称有空）。 */
        fun parse(raw: String?): PromptAvailability {
            val cleaned = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == cleaned } ?: BUSY
        }
    }
}

/**
 * 「你此刻的状态」的原料。
 *
 * 刻意**没有时钟**：`weekday` / `time` / `until` 都是调用方格式化好的中文/`HH:mm` 文本，
 * 这样「周日 19:40」「21:30 结束」在测试里可精确构造，也不会让 `:agentcore` 依赖平台时间 API。
 */
data class PromptState(
    /** 星期几，例如「周日」（调用方格式化）。 */
    val weekday: String = "",
    /** 时刻，例如「19:40」。 */
    val time: String = "",
    /** 正在做什么（接在「你在」之后；自带「在」会被归一化掉）。空 = 没在忙。 */
    val activity: String? = null,
    /** 大概几点结束，例如「21:30」；与 [activity] 一起才有意义。 */
    val until: String? = null,
    val availability: PromptAvailability = PromptAvailability.FREE,
    /** 心情词，例如「有点累」；为空时整行省略。 */
    val mood: String? = null,
    /** 心情强度 1–5（超出会被夹取）。 */
    val moodIntensity: Int = 1
)

/**
 * 话风（`README.md` §3.1）。
 *
 * [habits] / [avoid] 目前**不参与渲染**：`scaffold.json` 里没有对应的行模板，而本类不许
 * 发明规范之外的句子。它们先留在这里，等 spec 补上 `voice_habits` / `voice_avoid` 两行
 * （再加两条测试）之后接进 [PersonaPromptScaffold.build]。
 */
data class PromptVoice(
    /** 一句话的说话风格，例如「简短直接，偏冷淡务实」。 */
    val tone: String = "",
    /** 常用语/口头禅（最多带 [PersonaPromptScaffold.MAX_PHRASES] 条）。 */
    val phrases: List<String> = emptyList(),
    /** 语言习惯（句长、标点、语气词……）：当前不渲染，见类注释。 */
    val habits: List<String> = emptyList(),
    /** 不该出现的表达（书面语、感叹号滥用……）：当前不渲染，见类注释。 */
    val avoid: List<String> = emptyList()
)
