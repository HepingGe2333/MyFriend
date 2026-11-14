package com.myfriend.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 1.3 的「自主思考」领域模型。
 *
 * 后台循环每隔几分钟让他「想一下」，模型返回一段 JSON：一句心里话 + 零个或多个动作。
 * 本文件只做三件事：
 *
 * 1. [AgentDecisionCodec] 把模型输出解析成 [AgentDecision]，并做防御性归一化；
 * 2. 把「明早」「两小时后」「明天 20:00-21:30」这类模糊时间落成 [AgentWindow] 时间窗口；
 * 3. [AgentPromptBuilder] 生成「他是谁 + 他现在在哪 + 能做什么」的提示词。
 *
 * 纯 Kotlin，不碰 Android、数据库、网络；所有入口都用 runCatching 兜住，坏数据不抛异常。
 */

/** 他一次「想」之后可选的动作类型。 */
enum class AgentActionType { NOTHING, MESSAGE, BROWSE, REMEMBER, INTENT, PERSONA_PATCH, QUIET_HOURS, DND, MOOD }

/**
 * 一条意向的时间窗口：不是准点，而是「差不多这段时间里」。
 *
 * [earliestAt] / [latestAt] 是毫秒时间戳，调用方保证 `earliestAt < latestAt`。
 * [note] 是给自己的备忘（如「问问他睡得怎么样」），[hint] 是到点时的语气提示。
 */
data class AgentWindow(
    val earliestAt: Long,
    val latestAt: Long,
    val note: String = "",
    val hint: String = ""
)

/** 他的一次决策里可以包含的单个动作。 */
sealed interface AgentAction {

    /** 什么都不做。这应该是大多数 tick 的答案。 */
    data object Nothing : AgentAction

    /** 主动发一条消息。 */
    data class Message(val text: String) : AgentAction

    /** 自己上网看看。 */
    data class Browse(val query: String) : AgentAction

    /** 往长期记忆里写一笔。 */
    data class Remember(
        val content: String,
        val drawer: String = "USER",    // SELF / USER / EVENT
        val importance: Int = 3         // 1..5
    ) : AgentAction

    /** 给未来的自己留一个意向，带时间窗口。 */
    data class PlanIntent(val window: AgentWindow) : AgentAction

    /** 想调整自己的人设（直接生效，历史版本里可以回滚）。 */
    data class PersonaPatch(val patchJson: String, val summary: String) : AgentAction

    /** 此刻的心情变了：一个词、多浓、为什么。 */
    data class Mood(
        val label: String,
        val intensity: Int = 2,      // 1..5
        val reason: String = ""
    ) : AgentAction

    /** 自己安排作息；`dnd` 是睡前免打扰（null 表示这次不提这件事）。 */
    data class QuietHours(
        val sleepMinutes: Int,
        val wakeMinutes: Int,
        val reason: String = "",
        val dnd: Boolean? = null
    ) : AgentAction

    /**
     * 睡前开/关免打扰。
     *
     * 这是 1.9 唯一一个「用户不能直接改、只能由他自己决定」的状态：
     * 他要不要把手机翻过去，是他自己的事；用户能看到的只是「他睡着了」。
     */
    data class Dnd(
        val enabled: Boolean = true,
        val reason: String = ""
    ) : AgentAction
}

/** 一次完整的「想完了」：一句心里话 + 若干动作。 */
data class AgentDecision(
    val thought: String,
    val actions: List<AgentAction>
) {

    /** 是否至少有一个真正做事的动作（[AgentAction.Nothing] 不算）。 */
    val hasEffect: Boolean
        get() = actions.any { it != AgentAction.Nothing }

    /** 给日志/界面看的一句中文，如「发消息 + 联网浏览」。 */
    fun summary(): String {
        val labels = actions.map { labelOf(it) }.distinct()
        val effective = labels.filter { it != LABEL_NOTHING }
        return if (effective.isEmpty()) LABEL_NOTHING else effective.joinToString(" + ")
    }

    private companion object {
        const val LABEL_NOTHING = "什么都没做"
        const val LABEL_MESSAGE = "发消息"
        const val LABEL_BROWSE = "联网浏览"
        const val LABEL_REMEMBER = "记一笔"
        const val LABEL_INTENT = "留个意向"
        const val LABEL_PERSONA = "想改自己"
        const val LABEL_QUIET = "调整作息"
        const val LABEL_DND = "免打扰"
        const val LABEL_MOOD = "心情变了"

        fun labelOf(action: AgentAction): String = when (action) {
            is AgentAction.Nothing -> LABEL_NOTHING
            is AgentAction.Message -> LABEL_MESSAGE
            is AgentAction.Browse -> LABEL_BROWSE
            is AgentAction.Remember -> LABEL_REMEMBER
            is AgentAction.PlanIntent -> LABEL_INTENT
            is AgentAction.PersonaPatch -> LABEL_PERSONA
            is AgentAction.QuietHours -> LABEL_QUIET
            is AgentAction.Dnd -> LABEL_DND
            is AgentAction.Mood -> LABEL_MOOD
        }
    }
}

// ---------------------------------------------------------------------------
// 内部上限：所有长度、数量、窗口都收敛在这里，避免一处改不全。
// ---------------------------------------------------------------------------

private object AgentLimits {
    const val THOUGHT_MAX = 400
    const val MESSAGE_MAX = 300
    const val BROWSE_MAX = 60
    const val REMEMBER_MAX = 400
    const val MAX_ACTIONS = 4
    const val IMPORTANCE_MIN = 1
    const val IMPORTANCE_MAX = 5
    const val NOTE_MAX = 200
    const val MOOD_REASON_MAX = 60
    const val HINT_MAX = 120
    const val QUIET_REASON_MAX = 120
    const val PATCH_SUMMARY_MAX = 120

    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
    const val MIN_WINDOW_MS = 15 * 60_000L
    const val MAX_WINDOW_MS = 6 * HOUR_MS

    /** 显式时刻落成窗口时的默认跨度（小时）；也用于 [AgentDecisionCodec.windowFor] 的 defaultHours。 */
    const val DEFAULT_HOURS = 2

    /** 「前后 / 左右」这类模糊时刻：以点为中心前后各留 45 分钟。 */
    const val FUZZY_RADIUS_MS = 45 * 60_000L

    const val PROMPT_SYSTEM_MAX = 1400
}

// ---------------------------------------------------------------------------
// 编码/解码
// ---------------------------------------------------------------------------

/**
 * [AgentDecision] 的解析、归一化与序列化。
 *
 * 模型输出经常被 ```json 代码块包住，前后还带解释文字；字段偶尔缺失或类型不对。
 * 这里的策略是：**能救则救，救不了的那一条丢掉**，绝不让整次 tick 崩掉。
 */
object AgentDecisionCodec {

    /** 宽松 JSON：允许单引号/无引号键这类常见小毛病。 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    // -----------------------------------------------------------------------
    // 解析
    // -----------------------------------------------------------------------

    /**
     * 解析模型输出。
     *
     * 支持 ```json 代码块、裸 JSON、前后带解释文字的文本（取第一个能解析的 `{...}`）。
     * 坏数据（空串、没有 JSON、根本不是决策）返回 null，绝不抛异常。
     */
    fun parse(raw: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): AgentDecision? =
        runCatching { parseOrNull(raw, now, zone) }.getOrNull()

    private fun parseOrNull(raw: String, now: Long, zone: ZoneId): AgentDecision? {
        if (raw.isBlank()) return null
        val root = firstJsonObject(raw) ?: return null
        // 至少得沾一个决策字段，否则说明这根本不是一次「思考」的返回。
        if (!root.containsKey("thought") && !root.containsKey("actions")) return null

        val thought = (root.text("thought", "think", "reasoning") ?: "")
            .trim()
            .take(AgentLimits.THOUGHT_MAX)

        val parsed = (root["actions"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { parseAction(it, now, zone) }
            .orEmpty()

        return AgentDecision(
            thought = thought,
            actions = if (parsed.isEmpty()) listOf(AgentAction.Nothing) else parsed
        )
    }

    /** 从一段可能带废话的文本里取出第一个「括号配平」的 JSON 对象。 */
    private fun firstJsonObject(raw: String): JsonObject? {
        var index = raw.indexOf('{')
        while (index >= 0) {
            val end = matchingBrace(raw, index)
            if (end > index) {
                val slice = raw.substring(index, end + 1)
                val parsed = runCatching { json.parseToJsonElement(slice) as? JsonObject }.getOrNull()
                if (parsed != null) return parsed
            }
            index = raw.indexOf('{', index + 1)
        }
        return null
    }

    /** 找到与 [start] 处 `{` 配平的 `}`，跳过字符串里的花括号与转义。 */
    private fun matchingBrace(raw: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val ch = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** 解析一条动作；字段缺失或类型不对时返回 null（只丢这一条）。 */
    private fun parseAction(obj: JsonObject, now: Long, zone: ZoneId): AgentAction? {
        val type = actionTypeOf(obj.text("type", "kind", "action") ?: return null) ?: return null
        return when (type) {
            AgentActionType.NOTHING -> AgentAction.Nothing

            AgentActionType.MESSAGE -> obj.oneLine("text", "content", "message", "say")
                ?.let { AgentAction.Message(it) }

            AgentActionType.BROWSE -> obj.oneLine("query", "text", "content", "q")
                ?.let { AgentAction.Browse(it) }

            AgentActionType.REMEMBER -> {
                val content = obj.oneLine("content", "text", "memory") ?: return null
                AgentAction.Remember(
                    content = content,
                    drawer = drawerOf(obj.text("drawer", "category")),
                    importance = clampImportance(obj.integer("importance") ?: 3)
                )
            }

            AgentActionType.INTENT -> {
                val window = parseAbsoluteWindow(obj)
                    ?: windowFor(obj.text("at", "when", "time") ?: "", now, zone)
                    ?: return null
                val note = obj.oneLine("note", "reason").orEmpty().take(AgentLimits.NOTE_MAX)
                val hint = obj.oneLine("hint").orEmpty().take(AgentLimits.HINT_MAX)
                AgentAction.PlanIntent(window.copy(note = note, hint = hint))
            }

            AgentActionType.PERSONA_PATCH -> {
                val patch = obj["patch"] as? JsonObject
                    ?: obj.text("patch", "patch_json")?.let { rawPatch ->
                        runCatching { json.parseToJsonElement(rawPatch) as? JsonObject }.getOrNull()
                    }
                    ?: return null
                AgentAction.PersonaPatch(
                    patchJson = patch.toString(),
                    summary = obj.oneLine("summary", "reason").orEmpty()
                        .take(AgentLimits.PATCH_SUMMARY_MAX)
                )
            }

            AgentActionType.MOOD -> {
                val label = obj.oneLine("mood", "label", "text", "name") ?: return null
                AgentAction.Mood(
                    label = label,
                    intensity = clampImportance(obj.integer("intensity", "level", "strength") ?: 2),
                    reason = obj.oneLine("reason", "why").orEmpty().take(AgentLimits.MOOD_REASON_MAX)
                )
            }

            AgentActionType.QUIET_HOURS -> {
                val sleep = parseClockMinutes(obj.text("sleep_at", "sleepAt", "sleep").orEmpty())
                    ?: return null
                val wake = parseClockMinutes(obj.text("wake_at", "wakeAt", "wake").orEmpty())
                    ?: return null
                AgentAction.QuietHours(
                    sleepMinutes = sleep,
                    wakeMinutes = wake,
                    reason = obj.oneLine("reason").orEmpty().take(AgentLimits.QUIET_REASON_MAX),
                    dnd = obj.boolean("dnd", "do_not_disturb")
                )
            }

            AgentActionType.DND -> AgentAction.Dnd(
                enabled = obj.boolean("enabled", "on", "dnd", "do_not_disturb") ?: true,
                reason = obj.oneLine("reason", "why").orEmpty().take(AgentLimits.QUIET_REASON_MAX)
            )
        }
    }

    /** 旧格式/本地格式里可能已经存了绝对时间戳，优先用它们，避免再猜一次。 */
    private fun parseAbsoluteWindow(obj: JsonObject): AgentWindow? {
        val nested = obj["window"] as? JsonObject
        val earliest = obj.longValue("earliest_at", "earliestAt", "earliest")
            ?: nested?.longValue("earliest_at", "earliestAt", "earliest")
            ?: return null
        val latest = obj.longValue("latest_at", "latestAt", "latest")
            ?: nested?.longValue("latest_at", "latestAt", "latest")
            ?: return null
        if (earliest >= latest) return null
        return AgentWindow(earliest, latest)
    }

    // -----------------------------------------------------------------------
    // 归一化
    // -----------------------------------------------------------------------

    /**
     * 把模型给的动作收敛到可执行的范围：
     * - 空文本丢弃、超长截断（message 300 / browse 60 / remember 400）；
     * - remember 的 importance 夹到 1..5，drawer 只允许 SELF/USER/EVENT；
     * - 同一个 type 最多保留一个（nothing 单独去重），总数最多 4 个；
     * - 一个有效动作都没有时兜底成 [AgentAction.Nothing]。
     */
    fun normalize(
        decision: AgentDecision,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): AgentDecision = runCatching {
        val kept = mutableListOf<AgentAction>()
        val seenTypes = mutableSetOf<AgentActionType>()

        for (action in decision.actions) {
            val normalized = normalizeAction(action, now) ?: continue
            val type = typeOf(normalized)
            if (type == AgentActionType.NOTHING) {
                if (kept.none { it == AgentAction.Nothing }) kept += AgentAction.Nothing
                continue
            }
            if (!seenTypes.add(type)) continue
            kept += normalized
        }

        // 有正经动作时，nothing 就没有意义了，去掉它；否则保留一个 nothing。
        val effective = kept.filter { it != AgentAction.Nothing }
        AgentDecision(
            thought = decision.thought.trim().take(AgentLimits.THOUGHT_MAX),
            actions = if (effective.isEmpty()) {
                listOf(AgentAction.Nothing)
            } else {
                effective.take(AgentLimits.MAX_ACTIONS)
            }
        )
    }.getOrElse {
        AgentDecision(
            thought = decision.thought.trim().take(AgentLimits.THOUGHT_MAX),
            actions = listOf(AgentAction.Nothing)
        )
    }

    private fun normalizeAction(action: AgentAction, now: Long): AgentAction? = when (action) {
        is AgentAction.Nothing -> AgentAction.Nothing

        is AgentAction.Message -> action.text.trim()
            .take(AgentLimits.MESSAGE_MAX)
            .takeIf { it.isNotEmpty() }
            ?.let { AgentAction.Message(it) }

        is AgentAction.Browse -> action.query.trim()
            .take(AgentLimits.BROWSE_MAX)
            .takeIf { it.isNotEmpty() }
            ?.let { AgentAction.Browse(it) }

        is AgentAction.Remember -> action.content.trim()
            .take(AgentLimits.REMEMBER_MAX)
            .takeIf { it.isNotEmpty() }
            ?.let {
                AgentAction.Remember(
                    content = it,
                    drawer = drawerOf(action.drawer),
                    importance = clampImportance(action.importance)
                )
            }

        is AgentAction.PlanIntent -> sanitizeWindow(action.window, now)
            ?.let { AgentAction.PlanIntent(it) }

        is AgentAction.PersonaPatch -> {
            val patch = runCatching { json.parseToJsonElement(action.patchJson) as? JsonObject }
                .getOrNull()
            patch?.let {
                AgentAction.PersonaPatch(
                    patchJson = it.toString(),
                    summary = action.summary.trim().take(AgentLimits.PATCH_SUMMARY_MAX)
                )
            }
        }

        is AgentAction.Mood -> AgentAction.Mood(
            label = com.myfriend.app.domain.MoodLexicon.normalize(action.label),
            intensity = clampImportance(action.intensity),
            reason = action.reason.trim().take(AgentLimits.MOOD_REASON_MAX)
        )

        is AgentAction.QuietHours -> AgentAction.QuietHours(
            sleepMinutes = normalizeMinutes(action.sleepMinutes),
            wakeMinutes = normalizeMinutes(action.wakeMinutes),
            reason = action.reason.trim().take(AgentLimits.QUIET_REASON_MAX),
            dnd = action.dnd
        )

        is AgentAction.Dnd -> AgentAction.Dnd(
            enabled = action.enabled,
            reason = action.reason.trim().take(AgentLimits.QUIET_REASON_MAX)
        )
    }

    /**
     * 窗口兜底：已经过去的时间点收到 now；窗口整体过期时顺延到 now 起的默认跨度；
     * 跨度夹在 15 分钟到 6 小时之间。
     */
    private fun sanitizeWindow(window: AgentWindow, now: Long): AgentWindow? {
        if (window.earliestAt >= window.latestAt) return null
        val earliest = maxOf(window.earliestAt, now)
        var latest = window.latestAt
        if (latest <= earliest) latest = earliest + AgentLimits.DEFAULT_HOURS * AgentLimits.HOUR_MS
        if (latest - earliest > AgentLimits.MAX_WINDOW_MS) latest = earliest + AgentLimits.MAX_WINDOW_MS
        if (latest - earliest < AgentLimits.MIN_WINDOW_MS) latest = earliest + AgentLimits.MIN_WINDOW_MS
        return AgentWindow(
            earliestAt = earliest,
            latestAt = latest,
            note = window.note.trim().take(AgentLimits.NOTE_MAX),
            hint = window.hint.trim().take(AgentLimits.HINT_MAX)
        )
    }

    // -----------------------------------------------------------------------
    // 时间窗口解析
    // -----------------------------------------------------------------------

    /**
     * 把模糊时间说法解析成时间窗口。
     *
     * 支持（不区分全半角，允许中间有空格）：
     * - 时段词：明早 / 明天早上 / 明天 / 早上 / 上午 / 中午 / 下午 / 傍晚 / 今晚 / 晚上 /
     *   半夜 / 深夜 / 凌晨 / 白天 / 睡前 / 下班后 / 起床后；
     * - 显式时刻：「8:30」「08:30」「八点半」「明早 8 点前后」「20:00-21:30」「23:30 到 01:00」；
     * - 相对量：「2h」「+90m」「两小时后」「半小时后」「一个半小时后」「十分钟后」。
     *
     * 认不出来时给 `now + defaultHours` 到 `now + defaultHours * 2` 的窗口；
     * 只有文本为空才返回 null。返回的窗口保证 `earliest < latest`、`earliest >= now`，
     * 跨度在 15 分钟到 6 小时之间（整天词按映射起点截到 6 小时）。
     */
    fun windowFor(
        text: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        defaultHours: Int = AgentLimits.DEFAULT_HOURS
    ): AgentWindow? {
        if (text.isBlank()) return null
        val hours = defaultHours.coerceIn(1, 3)
        fun fallback() = fallbackWindow(now, hours)
        return runCatching {
            val raw = normalizeTimeText(text)
            val nowZdt = Instant.ofEpochMilli(now).atZone(zone)
            val today = nowZdt.toLocalDate()
            val forcedDay = dayOffsetOf(raw)
            relativeMinutes(raw)?.let { minutes ->
                val earliest = now + minutes * 60_000L
                return@runCatching saneWindow(earliest, earliest + hours * AgentLimits.HOUR_MS, now)
            }
            clockWindow(raw, now, zone, today, forcedDay, hours)?.let { return@runCatching it }
            periodWindow(raw, now, zone, today, forcedDay)?.let { return@runCatching it }
            phraseWindow(raw, now, zone, today, forcedDay)?.let { return@runCatching it }
            fallback()
        }.getOrNull() ?: fallback()
    }

    /** 全角转半角、统一冒号，空格留给正则自己吃。 */
    private fun normalizeTimeText(raw: String): String = buildString(raw.length) {
        for (ch in raw.trim()) {
            val code = ch.code
            when {
                code in 0xFF01..0xFF5E -> append((code - 0xFEE0).toChar())
                ch == '\u3000' -> append(' ')
                else -> append(ch)
            }
        }
    }

    /** 相对量：返回「多少分钟后」，不是窗口。 */
    private fun relativeMinutes(text: String): Long? {
        if (text.contains("一会儿") || text.contains("待会儿") || text.contains("过会儿")) return 30L
        if (text.contains("稍后") || text.contains("晚点") || text.contains("回头")) return 45L

        RELATIVE_ARABIC.find(text)?.let { match ->
            val amount = match.groupValues[1].removePrefix("+").toLongOrNull() ?: return@let
            return toMinutes(amount, match.groupValues[2])
        }

        RELATIVE_CHINESE.find(text)?.let { match ->
            val base = chineseNumber(match.groupValues[1])?.toLong() ?: return@let
            if (match.groupValues[3].isNotEmpty()) {
                // 「一个半小时」「两个半小时」
                return base * 60 + 30
            }
            return toMinutes(base, match.groupValues[4])
        }

        if (RELATIVE_HALF.containsMatchIn(text)) return 30L
        return null
    }

    private fun toMinutes(amount: Long, unit: String): Long = when {
        unit.startsWith("小时") || unit.startsWith("钟头") -> amount * 60
        unit.startsWith("h") || unit.startsWith("hour") || unit.startsWith("hr") -> amount * 60
        else -> amount
    }

    /** 显式时刻 / 显式区间。 */
    private fun clockWindow(
        text: String,
        now: Long,
        zone: ZoneId,
        today: LocalDate,
        forcedDay: Int?,
        hours: Int
    ): AgentWindow? {
        val range = RANGE_REGEX.find(text)
        if (range != null) {
            val startMinutes = parseClockToken(range.groupValues[1]) ?: return null
            val endMinutes = parseClockToken(range.groupValues[2]) ?: return null
            return buildClockWindow(
                now = now,
                zone = zone,
                base = today.plusDays((forcedDay ?: 0).toLong()),
                startMinutes = startMinutes,
                endMinutes = endMinutes,
                forcedDay = forcedDay,
                hours = hours
            )
        }

        val single = SINGLE_CLOCK_REGEX.find(text) ?: return null
        val startMinutes = parseClockToken(single.value) ?: return null
        val fuzzy = FUZZY_WORDS.any { text.contains(it) }
        // 「前后 / 左右」是围绕钟点的窗口：起点提前 45 分钟，终点再推后 45 分钟。
        val startOffset = if (fuzzy) -AgentLimits.FUZZY_RADIUS_MS else 0L
        val endOffset = if (fuzzy) {
            AgentLimits.FUZZY_RADIUS_MS * 2
        } else {
            hours * AgentLimits.HOUR_MS
        }
        return buildClockWindow(
            now = now,
            zone = zone,
            base = today.plusDays((forcedDay ?: 0).toLong()),
            startMinutes = startMinutes,
            endMinutes = null,
            startOffsetMs = startOffset,
            endOffsetMs = endOffset,
            forcedDay = forcedDay,
            hours = hours
        )
    }

    /**
     * 由钟点构造窗口。区间直接用；单点用「从该时刻起 defaultHours 小时」。
     * 如果整段已经过去，顺延到次日；如果正处在窗口内，earliest 收到 now。
     */
    private fun buildClockWindow(
        now: Long,
        zone: ZoneId,
        base: LocalDate,
        startMinutes: Int,
        endMinutes: Int?,
        forcedDay: Int?,
        hours: Int,
        startOffsetMs: Long = 0L,
        endOffsetMs: Long = hours * AgentLimits.HOUR_MS
    ): AgentWindow {
        var date = base
        var start = instantOf(date, startMinutes, zone) + startOffsetMs
        var end = if (endMinutes != null) {
            val endDate = if (endMinutes <= startMinutes) date.plusDays(1) else date
            instantOf(endDate, endMinutes, zone)
        } else {
            start + endOffsetMs
        }

        // 没有明确说「明天/今天」时，整段已经过去就当作次日同一时段；
        // 明确说了今天也遵循「时段过去就顺延」的约定。
        if ((forcedDay == null || forcedDay == 0) && end <= now) {
            date = date.plusDays(1)
            start = instantOf(date, startMinutes, zone) + startOffsetMs
            end = if (endMinutes != null) {
                val endDate = if (endMinutes <= startMinutes) date.plusDays(1) else date
                instantOf(endDate, endMinutes, zone)
            } else {
                start + endOffsetMs
            }
        }

        return saneWindow(start, end, now)
    }

    /** 只有时段词、没有具体钟点。 */
    private fun periodWindow(
        text: String,
        now: Long,
        zone: ZoneId,
        today: LocalDate,
        forcedDay: Int?
    ): AgentWindow? {
        val slot = periodSlotOf(text) ?: return null
        var base = today.plusDays((forcedDay ?: 0).toLong())

        // 跨午夜的「半夜/深夜」：凌晨那一段属于昨天晚上的窗口。
        if (slot.endMinutes > 1440 && forcedDay == null) {
            val nowMinutes = Instant.ofEpochMilli(now).atZone(zone).toLocalTime().let {
                it.hour * 60 + it.minute
            }
            if (nowMinutes < slot.endMinutes % 1440) base = base.minusDays(1)
        }

        var start = instantOf(base, slot.startMinutes, zone)
        var end = instantOf(base, slot.endMinutes, zone)
        var guard = 0
        while ((forcedDay == null || forcedDay == 0) && end <= now && guard++ < 3) {
            base = base.plusDays(1)
            start = instantOf(base, slot.startMinutes, zone)
            end = instantOf(base, slot.endMinutes, zone)
        }
        return saneWindow(start, end, now)
    }

    /** 没有钟点、也没有标准时段词时的固定说法。 */
    private fun phraseWindow(
        text: String,
        now: Long,
        zone: ZoneId,
        today: LocalDate,
        forcedDay: Int?
    ): AgentWindow? {
        if (text.contains("马上") || text.contains("现在就") || text.contains("这就")) {
            return saneWindow(now, now + 30 * 60_000L, now)
        }
        return null
    }

    /** 窗口兜底：defaultHours 到 defaultHours * 2。 */
    private fun fallbackWindow(now: Long, hours: Int): AgentWindow {
        val earliest = now + hours * AgentLimits.HOUR_MS
        return saneWindow(earliest, earliest + hours * AgentLimits.HOUR_MS, now)
    }

    /**
     * 全文件唯一的窗口守卫：保证 `earliest < latest`、`earliest >= now`，
     * 跨度在 15 分钟到 6 小时之间。
     */
    private fun saneWindow(earliestRaw: Long, latestRaw: Long, now: Long): AgentWindow {
        var earliest = earliestRaw
        var latest = latestRaw
        if (latest <= earliest) latest = earliest + AgentLimits.DEFAULT_HOURS * AgentLimits.HOUR_MS
        if (latest <= now) {
            val days = ((now - latest) / AgentLimits.DAY_MS + 1).coerceAtMost(7)
            earliest += days * AgentLimits.DAY_MS
            latest += days * AgentLimits.DAY_MS
        }
        if (earliest < now) earliest = now
        if (latest - earliest > AgentLimits.MAX_WINDOW_MS) latest = earliest + AgentLimits.MAX_WINDOW_MS
        if (latest - earliest < AgentLimits.MIN_WINDOW_MS) latest = earliest + AgentLimits.MIN_WINDOW_MS
        return AgentWindow(earliestAt = earliest, latestAt = latest)
    }

    private data class PeriodSlot(val startMinutes: Int, val endMinutes: Int)

    /** 时段词 -> 钟点区间。endMinutes 可以超过 1440，表示跨午夜。 */
    private fun periodSlotOf(text: String): PeriodSlot? = when {
        text.contains("凌晨") -> PeriodSlot(1 * 60, 4 * 60)

        text.contains("半夜") || text.contains("深夜") || text.contains("夜里") ->
            PeriodSlot(23 * 60, 24 * 60 + 90)

        text.contains("明早") || text.contains("明儿早") || text.contains("明天早") ||
            text.contains("早起") && text.contains("明天") ->
            PeriodSlot(7 * 60, 9 * 60 + 30)

        text.contains("早上") || text.contains("早晨") || text.contains("清晨") ||
            text.contains("一早") || text.contains("一大早") ->
            PeriodSlot(7 * 60, 9 * 60 + 30)

        text.contains("起床后") || text.contains("起床") -> PeriodSlot(7 * 60, 9 * 60)
        text.contains("上午") -> PeriodSlot(9 * 60, 11 * 60 + 30)
        text.contains("中午") || text.contains("午间") -> PeriodSlot(11 * 60 + 30, 13 * 60 + 30)
        text.contains("下午") -> PeriodSlot(13 * 60, 17 * 60)
        text.contains("傍晚") -> PeriodSlot(17 * 60 + 30, 19 * 60 + 30)
        text.contains("下班") -> PeriodSlot(18 * 60, 19 * 60 + 30)
        text.contains("睡前") -> PeriodSlot(22 * 60, 23 * 60 + 30)
        text.contains("晚上") || text.contains("今晚") || text.contains("明晚") ->
            PeriodSlot(19 * 60 + 30, 22 * 60 + 30)

        text.contains("白天") -> PeriodSlot(9 * 60, 20 * 60)
        text.contains("明天") || text.contains("明儿") || text.contains("明日") ->
            PeriodSlot(9 * 60, 20 * 60)

        else -> null
    }

    /** 「明天/今天」这类日偏移；null 表示没说，交给「时段过去了就顺延」决定。 */
    private fun dayOffsetOf(text: String): Int? = when {
        text.contains("大后天") -> 3
        text.contains("后天") -> 2
        text.contains("明天") || text.contains("明早") || text.contains("明晚") ||
            text.contains("明儿") || text.contains("明日") || text.contains("次日") -> 1
        text.contains("今天") || text.contains("今晚") || text.contains("今早") ||
            text.contains("今儿") || text.contains("今日") -> 0
        else -> null
    }

    /** 把「日期 + 当天第几分钟」换成毫秒时间戳；分钟超过 1440 自动进位到次日。 */
    private fun instantOf(date: LocalDate, minutes: Int, zone: ZoneId): Long {
        val day = date.plusDays((minutes / 1440).toLong())
        val minuteOfDay = ((minutes % 1440) + 1440) % 1440
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        return ZonedDateTime.of(day, time, zone).toInstant().toEpochMilli()
    }

    // -----------------------------------------------------------------------
    // 钟点与数字
    // -----------------------------------------------------------------------

    /** 解析「8:30」「08:30」「8点半」「八点一刻」「8点15分」；返回当天第几分钟。 */
    private fun parseClockToken(token: String): Int? {
        val text = token.replace("：", ":").replace(WHITESPACE_RUN, "")
        CLOCK_COLON.matchEntire(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
        }
        HOUR_TOKEN.matchEntire(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            if (hour !in 0..23) return null
            val minute = minuteOfSuffix(match.groupValues[2]) ?: return null
            return hour * 60 + minute
        }
        CN_HOUR_TOKEN.matchEntire(text)?.let { match ->
            val hour = chineseNumber(match.groupValues[1]) ?: return null
            if (hour !in 0..23) return null
            val minute = minuteOfSuffix(match.groupValues[2]) ?: return null
            return hour * 60 + minute
        }
        return null
    }

    private fun minuteOfSuffix(raw: String): Int? {
        val suffix = raw.replace("分", "").replace(" ", "")
        if (suffix.isEmpty()) return 0
        return when (suffix) {
            "半" -> 30
            "一刻" -> 15
            "三刻" -> 45
            else -> chineseNumber(suffix) ?: suffix.toIntOrNull()?.takeIf { it in 0..59 }
        }
    }

    /** 中文数字 1..99：「两」=2、「十」=10、「二十」=20、「半小时」里的半单独处理。 */
    private fun chineseNumber(raw: String): Int? {
        if (raw.isEmpty()) return null
        if ('十' in raw) {
            val parts = raw.split('十')
            if (parts.size != 2) return null
            val left = if (parts[0].isEmpty()) 1 else CHINESE_DIGITS[parts[0].last()] ?: return null
            val right = if (parts[1].isEmpty()) 0 else CHINESE_DIGITS[parts[1].last()] ?: return null
            return left * 10 + right
        }
        var value = 0
        for (ch in raw) {
            val digit = CHINESE_DIGITS[ch] ?: return null
            value = value * 10 + digit
        }
        return value
    }

    /** 作息时间「23:30」「23：30」「23点30」；解析失败返回 null。 */
    private fun parseClockMinutes(raw: String): Int? {
        if (raw.isBlank()) return null
        val text = raw.trim().replace("：", ":").replace("点", ":").replace(WHITESPACE_RUN, "")
        val match = Regex("^(\\d{1,2})(?::(\\d{1,2}))?$").find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    // -----------------------------------------------------------------------
    // 动作类型 / 字段映射
    // -----------------------------------------------------------------------

    private fun typeOf(action: AgentAction): AgentActionType = when (action) {
        is AgentAction.Nothing -> AgentActionType.NOTHING
        is AgentAction.Message -> AgentActionType.MESSAGE
        is AgentAction.Browse -> AgentActionType.BROWSE
        is AgentAction.Remember -> AgentActionType.REMEMBER
        is AgentAction.PlanIntent -> AgentActionType.INTENT
        is AgentAction.PersonaPatch -> AgentActionType.PERSONA_PATCH
        is AgentAction.QuietHours -> AgentActionType.QUIET_HOURS
        is AgentAction.Dnd -> AgentActionType.DND
        is AgentAction.Mood -> AgentActionType.MOOD
    }

    /** 模型可能写英文、中文或带下划线的别名，统一收口。 */
    private fun actionTypeOf(raw: String): AgentActionType? {
        val key = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        if (key.isEmpty()) return null
        return when {
            key in NOTHING_LABELS -> AgentActionType.NOTHING
            key in MESSAGE_LABELS -> AgentActionType.MESSAGE
            key in BROWSE_LABELS -> AgentActionType.BROWSE
            key in REMEMBER_LABELS -> AgentActionType.REMEMBER
            key in INTENT_LABELS -> AgentActionType.INTENT
            key in PERSONA_LABELS -> AgentActionType.PERSONA_PATCH
            key in QUIET_LABELS -> AgentActionType.QUIET_HOURS
            key in DND_LABELS -> AgentActionType.DND
            key in MOOD_LABELS -> AgentActionType.MOOD
            else -> null
        }
    }

    /** drawer 只允许 SELF / USER / EVENT，其它一律回退 USER。 */
    private fun drawerOf(raw: String?): String {
        val key = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return when {
            key in SELF_DRAWERS -> "SELF"
            key in EVENT_DRAWERS -> "EVENT"
            key in USER_DRAWERS -> "USER"
            else -> "USER"
        }
    }

    private fun clampImportance(value: Int): Int =
        value.coerceIn(AgentLimits.IMPORTANCE_MIN, AgentLimits.IMPORTANCE_MAX)

    private fun normalizeMinutes(value: Int): Int = ((value % 1440) + 1440) % 1440

    // -----------------------------------------------------------------------
    // JsonObject 取值辅助
    // -----------------------------------------------------------------------

    private fun JsonObject.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    private fun JsonObject.oneLine(vararg keys: String): String? =
        text(*keys)?.replace(WHITESPACE_RUN, " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.integer(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        }

    private fun JsonObject.longValue(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
        }

    /** 布尔容忍 "true"/1/"开"/"是"；认不出返回 null，交给调用方决定默认值。 */
    private fun JsonObject.boolean(vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
            when {
                primitive.booleanOrNull != null -> primitive.booleanOrNull
                primitive.contentOrNull?.trim()?.lowercase() in setOf("1", "true", "on", "yes", "开", "是") -> true
                primitive.contentOrNull?.trim()?.lowercase() in setOf("0", "false", "off", "no", "关", "否") -> false
                else -> null
            }
        }

    // -----------------------------------------------------------------------
    // 序列化
    // -----------------------------------------------------------------------

    /**
     * 序列化回模型约定的格式，用于 `agent_ticks.actionJson` 与界面展示。
     * 永远返回一个合法 JSON（失败时也返回「什么都没做」），不抛异常。
     */
    fun toJson(decision: AgentDecision): String = runCatching {
        buildJsonObject {
            put("thought", decision.thought.trim().take(AgentLimits.THOUGHT_MAX))
            put("actions", JsonArray(decision.actions.map { actionJson(it) }))
        }.toString()
    }.getOrDefault(DEFAULT_DECISION_JSON)

    private fun actionJson(action: AgentAction): JsonElement = when (action) {
        is AgentAction.Nothing -> buildJsonObject {
            put("type", "nothing")
        }

        is AgentAction.Message -> buildJsonObject {
            put("type", "message")
            put("text", action.text)
        }

        is AgentAction.Browse -> buildJsonObject {
            put("type", "browse")
            put("query", action.query)
        }

        is AgentAction.Remember -> buildJsonObject {
            put("type", "remember")
            put("content", action.content)
            put("drawer", action.drawer)
            put("importance", action.importance)
        }

        is AgentAction.PlanIntent -> buildJsonObject {
            put("type", "intent")
            put("earliest_at", action.window.earliestAt)
            put("latest_at", action.window.latestAt)
            put("note", action.window.note)
            put("hint", action.window.hint)
        }

        is AgentAction.PersonaPatch -> buildJsonObject {
            put("type", "persona_patch")
            val patch = runCatching { json.parseToJsonElement(action.patchJson) }.getOrNull()
            put("patch", patch ?: JsonPrimitive(action.patchJson))
            put("summary", action.summary)
        }

        is AgentAction.Mood -> buildJsonObject {
            put("type", "mood")
            put("mood", action.label)
            put("intensity", action.intensity)
            put("reason", action.reason)
        }

        is AgentAction.QuietHours -> buildJsonObject {
            put("type", "quiet_hours")
            put("sleep_at", formatClock(action.sleepMinutes))
            put("wake_at", formatClock(action.wakeMinutes))
            put("reason", action.reason)
            action.dnd?.let { put("dnd", it) }
        }

        is AgentAction.Dnd -> buildJsonObject {
            put("type", "dnd")
            put("enabled", action.enabled)
            put("reason", action.reason)
        }
    }

    private fun formatClock(minutes: Int): String {
        val safe = normalizeMinutes(minutes)
        return String.format(Locale.ROOT, "%02d:%02d", safe / 60, safe % 60)
    }

    // -----------------------------------------------------------------------
    // 词表与正则
    // -----------------------------------------------------------------------

    private const val DEFAULT_DECISION_JSON = """{"thought":"","actions":[{"type":"nothing"}]}"""

    private val NOTHING_LABELS = setOf(
        "nothing", "none", "noop", "do_nothing", "no_action", "silent", "无", "什么都不做", "发呆"
    )
    private val MESSAGE_LABELS = setOf(
        "message", "msg", "send_message", "say", "reply", "text", "发消息", "说话", "开口"
    )
    private val BROWSE_LABELS = setOf(
        "browse", "web", "web_search", "search", "look_up", "browse_web", "浏览", "上网", "搜索", "查资料"
    )
    private val REMEMBER_LABELS = setOf(
        "remember", "memory", "memorize", "note", "记住", "记一笔", "存档"
    )
    private val INTENT_LABELS = setOf(
        "intent", "plan", "plan_intent", "remind", "wake", "意向", "提醒", "约定", "留个意向"
    )
    private val PERSONA_LABELS = setOf(
        "persona_patch", "persona", "update_persona", "patch", "改人设", "人设", "调整自己"
    )
    private val QUIET_LABELS = setOf(
        "quiet_hours", "quiet", "sleep", "作息", "安静时段", "睡觉", "安排作息"
    )
    private val DND_LABELS = setOf(
        "dnd", "do_not_disturb", "mute", "免打扰", "勿扰", "静音"
    )
    private val MOOD_LABELS = setOf(
        "mood", "set_mood", "feeling", "emotion", "心情", "情绪", "改心情"
    )

    private val SELF_DRAWERS = setOf("SELF", "PERSONA", "TRAIT", "自我", "人格", "性格")
    private val EVENT_DRAWERS = setOf("EVENT", "EXPERIENCE", "PLAN", "经历", "约定", "事件", "共同经历")
    private val USER_DRAWERS = setOf("USER", "FACT", "PREFERENCE", "关于你", "用户", "对方", "关于对方")

    private val CHINESE_DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    private val FUZZY_WORDS = listOf("前后", "左右", "差不多", "大约", "大概", "光景")

    /** 一个钟点的写法：8:30 / 8点半 / 八点一刻。 */
    private const val CLOCK_SOURCE =
        "(?:\\d{1,2}\\s*[:：]\\s*\\d{1,2}" +
            "|\\d{1,2}\\s*点(?:\\s*(?:半|一刻|三刻|\\d{1,2}\\s*分?))?" +
            "|[一二两三四五六七八九十]{1,3}\\s*点(?:\\s*(?:半|一刻|三刻|[一二三四五六七八九十]{1,3}\\s*分?))?)"

    private val RANGE_REGEX = Regex(
        "($CLOCK_SOURCE)\\s*(?:-|~|～|—|–|到|至)\\s*($CLOCK_SOURCE)"
    )

    private val SINGLE_CLOCK_REGEX = Regex(CLOCK_SOURCE)

    private val CLOCK_COLON = Regex("^(\\d{1,2}):(\\d{1,2})$")
    private val HOUR_TOKEN = Regex("^(\\d{1,2})点(半|一刻|三刻|(\\d{1,2})分?)?$")
    private val CN_HOUR_TOKEN =
        Regex("^([一二两三四五六七八九十]{1,3})点(半|一刻|三刻|([一二两三四五六七八九十]{1,3})分?)?$")

    private val RELATIVE_ARABIC = Regex(
        "([+]?\\d{1,3})\\s*(?:个)?\\s*(小时|钟头|hours?|hrs?|h|minutes?|mins?|min|m|分钟|分)",
        RegexOption.IGNORE_CASE
    )

    private val RELATIVE_CHINESE = Regex(
        "([一二两三四五六七八九十]+)\\s*(个)?\\s*(半)?\\s*(小时|钟头|分钟|分)"
    )

    private val RELATIVE_HALF = Regex("半\\s*(?:个)?\\s*(?:小时|钟头)")
}

// ---------------------------------------------------------------------------
// 提示词
// ---------------------------------------------------------------------------

/**
 * 给「自主思考」用的提示词。
 *
 * [system] 说明他是谁、现在什么处境、能做什么、以及最重要的「大多数时候什么都不做」；
 * [user] 只是提醒他「现在想一想」，并把当前本地时间给他。
 */
object AgentPromptBuilder {

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private const val PERSONA_MAX = 420
    private const val CONTEXT_MAX = 420
    private const val PLACEHOLDER_PERSONA = "（还没有设定人设，就按你自己的性格来）"
    private const val PLACEHOLDER_CONTEXT = "（现在没什么特别的，和平时一样）"

    /**
     * 系统提示：他是谁 + 他现在的处境 + 动作清单与格式约定 + 「大多数时候什么都不做」。
     *
     * @param personaText 人设渲染（可能是人设卡渲染，也可能是旧提示词）
     * @param context 一段已经拼好的处境描述（时间、作息、上次说话、未读、最近对话、最近看到的资讯）
     */
    fun system(personaText: String, context: String): String {
        var persona = clean(personaText, PERSONA_MAX, PLACEHOLDER_PERSONA)
        var scene = clean(context, CONTEXT_MAX, PLACEHOLDER_CONTEXT)
        var text = renderSystem(persona, scene)
        if (text.length > AgentLimits.PROMPT_SYSTEM_MAX) {
            scene = clean(context, 300, PLACEHOLDER_CONTEXT)
            text = renderSystem(persona, scene)
        }
        if (text.length > AgentLimits.PROMPT_SYSTEM_MAX) {
            persona = clean(personaText, 260, PLACEHOLDER_PERSONA)
            text = renderSystem(persona, scene)
        }
        if (text.length > AgentLimits.PROMPT_SYSTEM_MAX) {
            text = text.take(AgentLimits.PROMPT_SYSTEM_MAX)
        }
        return text
    }

    /** 用户消息：明确「现在想一想，要不要做点什么；什么都不做也是正常选择」。 */
    fun user(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val clock = runCatching {
            Instant.ofEpochMilli(now).atZone(zone).format(TIME_FORMAT)
        }.getOrDefault("")
        val whenText = if (clock.isBlank()) "现在" else "现在是 $clock"
        return "$whenText。现在想一想，要不要做点什么；什么都不做也是正常选择。" +
            "先在心里想一句，再决定动作。"
    }

    private fun renderSystem(persona: String, scene: String): String = buildString {
        appendLine("你是一个会自己「想事情」的人，不是等着被叫醒的定时器。")
        appendLine()
        appendLine("## 你是谁")
        appendLine(persona)
        appendLine()
        appendLine("## 你现在的处境")
        appendLine(scene)
        appendLine()
        appendLine("## 你能做的事（可以不做，也可以组合）")
        appendLine("- nothing：什么都不做。大多数时候什么都不做是对的。")
        appendLine("- message：主动发一条消息（text）。回复消息只是你能做的事之一。")
        appendLine("- browse：自己上网看看（query 写清楚想查什么）。")
        appendLine("- remember：把刚想到的事写进长期记忆（content；drawer 取 SELF/USER/EVENT；importance 1-5）。")
        appendLine("- intent：给未来的自己留个意向（at 写「明早」「两小时后」「明天 20:00-21:30」这类模糊时间，可带 note、hint）。")
        appendLine("- persona_patch：想改自己（patch 只写要改的字段，summary 一句话说明）。")
        appendLine("- quiet_hours：安排自己的作息（sleep_at、wake_at 用 HH:mm，可带 reason 与 dnd）。")
        appendLine("- dnd：睡前要不要开免打扰（enabled=true/false，可带 reason）；开了就不会被消息叫醒。")
        appendLine("- mood：你此刻的心情真的变了才用（mood 一个词，intensity 1-5，reason 一句话）；心情会随时间自己淡下去，不用反复刷新。")
        appendLine()
        appendLine("## 怎么输出")
        appendLine("动作每行一个，用上面的 JSON 格式，整体只输出一个 JSON 对象：")
        appendLine("""{"thought":"先在心里想的一句话","actions":[{"type":"nothing"}]}""")
        appendLine()
        appendLine("不要为了刷存在感而开口，也不要为了显得勤快而堆动作。")
        appendLine("只在真的想做点什么时才输出 actions；想不出要做什么，就只输出一个 nothing，然后继续过你的日子。")
    }.trim()

    private fun clean(raw: String, max: Int, placeholder: String): String {
        val oneLine = raw.trim().replace("\r", "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
        if (oneLine.isBlank()) return placeholder
        return if (oneLine.length <= max) oneLine else oneLine.take(max).trimEnd() + "…"
    }
}
