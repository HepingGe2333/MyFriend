package com.myfriend.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 世界预设（`spec/persona-guidance/README.md` §2）。
 *
 * 世界预设回答的是「他现在活在什么样的地方、今天要做什么、被什么缠着」：
 * 名字/时代/一段处境描述，加上三条可引用的细节——日程、关系、义务。
 * 它只在与话题相关时被带出，不是拿来念的台词（§1.1 第 5 条）。
 *
 * 纯 JVM：不读时钟、不碰数据库、不依赖 Android。JSON 解析宁可丢一个坏条目，
 * 也不让整份世界预设变成异常——一条坏日程不该让角色忘记自己住在哪。
 */
data class WorldScheduleEntry(
    /** `工作日` / `周末` / `每天` / `周一`…`周日`。 */
    val day: String = "",
    /** 24 小时制 `HH:mm`。 */
    val start: String = "",
    val end: String = "",
    val activity: String = ""
) {
    /** 开始时间晚于结束时间 = 跨午夜（例如 22:00–02:00），按次日算，是合法的。 */
    val crossesMidnight: Boolean
        get() {
            val startMinute = WorldPreset.minuteOfDay(start) ?: return false
            val endMinute = WorldPreset.minuteOfDay(end) ?: return false
            return endMinute < startMinute
        }

    val isValid: Boolean
        get() = day in WorldPreset.ALLOWED_DAYS &&
            WorldPreset.minuteOfDay(start) != null &&
            WorldPreset.minuteOfDay(end) != null &&
            activity.isNotBlank()

    fun renderLine(): String {
        val suffix = if (crossesMidnight) "（次日）" else ""
        return "$day $start-$end$suffix ${activity.trim()}".trim()
    }
}

data class WorldRelationship(
    val name: String = "",
    val relation: String = "",
    val note: String = "",
    /**
     * 可选亲密度 1–5（编辑器里的「亲密度」旋钮）。
     *
     * 规范 §2.1 的关系只写了 name/relation/note，这是 Android 端的**可选扩展**：
     * 不填就不写进 JSON（老数据与其它端都读不出差别），填了也只多一个 `intimacy` 键。
     */
    val intimacy: Int? = null
) {
    fun renderLine(): String {
        val head = when {
            name.isBlank() && relation.isBlank() -> return ""
            name.isBlank() -> relation
            relation.isBlank() -> name
            else -> "$name（$relation）"
        }
        val closeness = intimacy?.let { "，亲密度 $it/5" }.orEmpty()
        return if (note.isBlank()) "$head$closeness" else "$head：${note.trim()}$closeness"
    }

    /** 合法：至少有一个称呼/关系；亲密度要么不填，要么在 1–5。 */
    val isValid: Boolean
        get() = (name.isNotBlank() || relation.isNotBlank()) && (intimacy == null || intimacy in 1..5)
}

data class WorldPreset(
    val worldName: String = "",
    val era: String = "",
    val description: String = "",
    val schedule: List<WorldScheduleEntry> = emptyList(),
    val relationships: List<WorldRelationship> = emptyList(),
    val obligations: List<String> = emptyList(),
    /**
     * 兜底自由文本（规范 §2.1）：结构化字段表达不了的世界观。
     *
     * 渲染成「其他背景：…」附在世界段末尾，最多 [MAX_NOTES_CHARS] 字；
     * 超出截断（界面另外提示），不静默丢。
     */
    val notes: String = ""
) {
    fun isEmpty(): Boolean =
        worldName.isBlank() && era.isBlank() && description.isBlank() &&
            schedule.isEmpty() && relationships.isEmpty() && obligations.isEmpty() &&
            notes.isBlank()

    /**
     * §6.3 的「你所在的世界」段——整个工程里**唯一**拼这一段的地方。
     *
     * 固定文案全部读 [PromptBuilder.Scaffold]（`spec/persona-guidance/scaffold.json` 的逐字副本），
     * 而不是在本文件里再写一遍中文。一个数据类去读提示词常量确实有点反直觉：分层上「世界长什么样」
     * 和「提示词怎么写」是两件事。但世界段只有这一处实现，脚手架又只有 PromptBuilder 里那一份，
     * 在这里再抄一套 literal 就等于给自己留了第二个真相源——两次改动之间必然出现「一边改了、
     * 另一边没改」的提示词。宁可打破一次分层，也不打破「两端逐字一致」这条规范。
     *
     * 渲染顺序（§6.3）：world_name（`你所在的地方：{worldName}。`）→ intro
     * （`{era}。{description}`）→ 日程 → 人际关系 → 义务 → 其他背景 → 提示语。
     *
     * 「有没有世界」的唯一判定是 [isEmpty]：**所有字段都为空**才算没有世界（§2.1，规范
     * 0.12 更新后的口径）。以前这里用 `worldName.isBlank()` 当开关，于是「只填了 era /
     * description / notes」的世界会被整段丢掉，与 iOS 和规范都不一致。
     *
     * `worldName` 只是**数据**：非空时用脚手架的 `world_name` 渲染成一行，为空时整行省略，
     * 其余字段照常渲染——只填了 `notes` 的人也应该让模型看到「其他背景」。
     */
    fun renderForPrompt(): String {
        if (isEmpty()) return ""
        val lines = buildList {
            worldName.trim().takeIf { it.isNotEmpty() }?.let {
                add(PromptBuilder.Scaffold.WORLD_NAME.replace("{worldName}", it))
            }
            worldIntro()?.let { add(it) }
            schedule.filter { it.isValid }.forEach { add("- ${it.renderLine()}") }
            relationships.mapNotNull { it.renderLine().takeIf(String::isNotBlank) }
                .forEach { add("- $it") }
            obligations.map { it.trim() }.filter { it.isNotBlank() }.forEach { add("- 必须：$it") }
            val background = notes.trim().take(MAX_NOTES_CHARS)
            if (background.isNotBlank()) {
                add(PromptBuilder.Scaffold.WORLD_NOTES.replace("{notes}", background))
            }
            add(PromptBuilder.Scaffold.WORLD_HINT)
        }
        return PromptBuilder.Scaffold.SECTION_WORLD + "\n" + lines.joinToString("\n")
    }

    /**
     * `{era}。{description}`；缺一半时不留下多出来的标点：
     * 只有时代 → 「…2026 年。」（模板里的句号正好收尾），只有描述 → 去掉开头那个句号。
     * 两半都空 → 不写 intro 行（不硬造一个空句子）。
     */
    private fun worldIntro(): String? {
        val era = era.trim()
        val description = description.trim()
        if (era.isBlank() && description.isBlank()) return null
        val text = PromptBuilder.Scaffold.WORLD_INTRO
            .replace("{era}", era)
            .replace("{description}", description)
            .let { if (era.isBlank()) it.removePrefix("。") else it }
        return text.trim().ifBlank { null }
    }

    companion object {
        /** 「其他背景（notes）」最多渲染多少字（规范 §2.1：1200 字）。 */
        const val MAX_NOTES_CHARS = 1200

        /** §2.1 允许的 `day` 取值。 */
        val ALLOWED_DAYS = setOf(
            "工作日", "周末", "每天",
            "周一", "周二", "周三", "周四", "周五", "周六", "周日"
        )

        /** 只认 24 小时制 `HH:mm`（也容忍全角冒号、1 位小时）。 */
        fun minuteOfDay(raw: String): Int? {
            val text = raw.trim().replace('：', ':')
            if (text.isBlank()) return null
            val match = CLOCK.matchEntire(text) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }

        private val CLOCK = Regex("""(\d{1,2}):(\d{2})""")
        private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")
    }

    /** 给界面/调试用的 `HH:mm` 规范化（坏值原样 trim 返回）。 */
    fun normalizedClock(raw: String): String {
        val minute = minuteOfDay(raw) ?: return raw.trim()
        return LocalTime.of(minute / 60, minute % 60).format(WorldPreset.HH_MM)
    }
}

/**
 * 世界预设的 JSON 编解码。
 *
 * 三种读法各有用途：
 * - [decode]：宽容解析（LLM / 备份 / 历史脏数据），坏 JSON 返回 `null`，缺字段 → 空数组；
 * - [decodeOrEmpty]：在 [decode] 上再兜一层「空世界」；
 * - [decodeStrict]：给编辑器保存用，日程/关系的格式不对时返回 `null`，界面据此提示，而不是悄悄吞掉。
 */
object WorldPresetCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** 坏 JSON / 非对象 → null；空串 / 全空对象 → 空世界（不是 null）。 */
    fun decode(raw: String?): WorldPreset? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return runCatching {
            WorldPreset(
                worldName = root.str("worldName"),
                era = root.str("era"),
                description = root.str("description"),
                schedule = root.arr("schedule").mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    WorldScheduleEntry(
                        day = obj.str("day"),
                        start = obj.str("start"),
                        end = obj.str("end"),
                        activity = obj.str("activity")
                    ).takeIf { it.isValid }
                },
                relationships = root.arr("relationships").mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    WorldRelationship(
                        name = obj.str("name"),
                        relation = obj.str("relation"),
                        note = obj.str("note"),
                        intimacy = obj.int("intimacy")
                    ).takeIf { it.name.isNotBlank() || it.relation.isNotBlank() }
                },
                obligations = root.arr("obligations").mapNotNull { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                        ?.takeIf { it.isNotBlank() }
                },
                notes = root.str("notes").take(WorldPreset.MAX_NOTES_CHARS)
            )
        }.getOrNull()
    }

    fun decodeOrEmpty(raw: String?): WorldPreset = decode(raw) ?: WorldPreset()

    /**
     * 编辑器保存用：日程/关系的每一条都必须合法，否则返回 `null`（不静默丢条目）。
     *
     * 缺字段仍然是宽容的；只有「写了但写错」才算错。
     */
    fun decodeStrict(raw: String?): WorldPreset? {
        if (raw.isNullOrBlank()) return WorldPreset()
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val schedule = root.arr("schedule").map { element ->
            val obj = element as? JsonObject ?: return null
            val entry = WorldScheduleEntry(
                day = obj.str("day"),
                start = obj.str("start"),
                end = obj.str("end"),
                activity = obj.str("activity")
            )
            if (!entry.isValid) return null
            entry
        }
        val relationships = root.arr("relationships").map { element ->
            val obj = element as? JsonObject ?: return null
            WorldRelationship(
                name = obj.str("name"),
                relation = obj.str("relation"),
                note = obj.str("note"),
                intimacy = obj.int("intimacy")
            ).takeIf { it.isValid } ?: return null
        }
        return WorldPreset(
            worldName = root.str("worldName"),
            era = root.str("era"),
            description = root.str("description"),
            schedule = schedule,
            relationships = relationships,
            obligations = root.arr("obligations").mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotBlank() }
            },
            notes = root.str("notes").take(WorldPreset.MAX_NOTES_CHARS)
        )
    }

    fun encode(preset: WorldPreset): String = runCatching {
        JsonObject(
            mapOf(
                "worldName" to JsonPrimitive(preset.worldName.trim()),
                "era" to JsonPrimitive(preset.era.trim()),
                "description" to JsonPrimitive(preset.description.trim()),
                "schedule" to JsonArray(preset.schedule.take(MAX_ITEMS).map { entry ->
                    JsonObject(
                        mapOf(
                            "day" to JsonPrimitive(entry.day.trim()),
                            "start" to JsonPrimitive(entry.start.trim()),
                            "end" to JsonPrimitive(entry.end.trim()),
                            "activity" to JsonPrimitive(entry.activity.trim())
                        )
                    )
                }),
                "relationships" to JsonArray(preset.relationships.take(MAX_ITEMS).map { relation ->
                    JsonObject(
                        buildMap {
                            put("name", JsonPrimitive(relation.name.trim()))
                            put("relation", JsonPrimitive(relation.relation.trim()))
                            put("note", JsonPrimitive(relation.note.trim()))
                            // 不填就不写这个键：老数据与其它端读出来没有任何差别。
                            relation.intimacy?.takeIf { it in 1..5 }?.let { put("intimacy", JsonPrimitive(it)) }
                        }
                    )
                }),
                "obligations" to JsonArray(
                    preset.obligations.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_ITEMS)
                        .map(::JsonPrimitive)
                ),
                "notes" to JsonPrimitive(preset.notes.trim().take(WorldPreset.MAX_NOTES_CHARS))
            )
        ).toString()
    }.getOrDefault("{}")

    /** 编辑器用：只解析日程数组；格式不对返回 null（不静默吞掉用户写的条目）。 */
    fun decodeSchedule(raw: String?): List<WorldScheduleEntry>? {
        if (raw.isNullOrBlank()) return emptyList()
        val list = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
        val entries = list.map { element ->
            val obj = element as? JsonObject ?: return null
            WorldScheduleEntry(
                day = obj.str("day"),
                start = obj.str("start"),
                end = obj.str("end"),
                activity = obj.str("activity")
            ).takeIf { it.isValid } ?: return null
        }
        return entries
    }

    /** 编辑器用：只解析关系数组；格式不对返回 null。 */
    fun decodeRelationships(raw: String?): List<WorldRelationship>? {
        if (raw.isNullOrBlank()) return emptyList()
        val list = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
        return list.map { element ->
            val obj = element as? JsonObject ?: return null
            WorldRelationship(
                name = obj.str("name"),
                relation = obj.str("relation"),
                note = obj.str("note"),
                intimacy = obj.int("intimacy")
            )
        }
    }

    fun encodeSchedule(entries: List<WorldScheduleEntry>): String = runCatching {
        JsonArray(entries.map { entry ->
            JsonObject(
                mapOf(
                    "day" to JsonPrimitive(entry.day),
                    "start" to JsonPrimitive(entry.start),
                    "end" to JsonPrimitive(entry.end),
                    "activity" to JsonPrimitive(entry.activity)
                )
            )
        }).toString()
    }.getOrDefault("[]")

    fun encodeRelationships(entries: List<WorldRelationship>): String = runCatching {
        JsonArray(entries.map { relation ->
            JsonObject(
                buildMap {
                    put("name", JsonPrimitive(relation.name))
                    put("relation", JsonPrimitive(relation.relation))
                    put("note", JsonPrimitive(relation.note))
                    relation.intimacy?.takeIf { it in 1..5 }?.let { put("intimacy", JsonPrimitive(it)) }
                }
            )
        }).toString()
    }.getOrDefault("[]")

    private const val MAX_ITEMS = 32

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.str(key: String): String =
        ((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: "").trim()

    private fun JsonObject.arr(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.toList() ?: emptyList()
}
