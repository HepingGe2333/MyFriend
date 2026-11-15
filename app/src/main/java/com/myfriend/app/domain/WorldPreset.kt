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
    val note: String = ""
) {
    fun renderLine(): String {
        val head = when {
            name.isBlank() && relation.isBlank() -> return ""
            name.isBlank() -> relation
            relation.isBlank() -> name
            else -> "$name（$relation）"
        }
        return if (note.isBlank()) head else "$head：${note.trim()}"
    }
}

data class WorldPreset(
    val worldName: String = "",
    val era: String = "",
    val description: String = "",
    val schedule: List<WorldScheduleEntry> = emptyList(),
    val relationships: List<WorldRelationship> = emptyList(),
    val obligations: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        worldName.isBlank() && era.isBlank() && description.isBlank() &&
            schedule.isEmpty() && relationships.isEmpty() && obligations.isEmpty()

    /**
     * §6.3 的「你所在的世界」段。
     *
     * `worldName` 为空表示没有世界预设（§2.1）：返回空串，由调用方整段省略，
     * 不能让提示词里出现一个只有标题的空段落。
     */
    fun renderForPrompt(): String {
        if (isEmpty() || worldName.isBlank()) return ""
        val body = buildString {
            appendLine(worldName.trim())
            val lead = listOf(era.trim(), description.trim()).filter { it.isNotBlank() }.joinToString("。")
            if (lead.isNotBlank()) appendLine(if (lead.endsWith("。")) lead else "$lead。")
            schedule.filter { it.isValid }.forEach { appendLine("- ${it.renderLine()}") }
            relationships.mapNotNull { it.renderLine().takeIf(String::isNotBlank) }
                .forEach { appendLine("- $it") }
            obligations.map { it.trim() }.filter { it.isNotBlank() }.forEach { appendLine("- 必须：$it") }
        }.trim()
        return if (body.isBlank()) "" else "# 你所在的世界\n$body"
    }

    companion object {
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
                        note = obj.str("note")
                    ).takeIf { it.name.isNotBlank() || it.relation.isNotBlank() }
                },
                obligations = root.arr("obligations").mapNotNull { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
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
                note = obj.str("note")
            )
        }
        return WorldPreset(
            worldName = root.str("worldName"),
            era = root.str("era"),
            description = root.str("description"),
            schedule = schedule,
            relationships = relationships,
            obligations = root.arr("obligations").mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotBlank() }
            }
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
                        mapOf(
                            "name" to JsonPrimitive(relation.name.trim()),
                            "relation" to JsonPrimitive(relation.relation.trim()),
                            "note" to JsonPrimitive(relation.note.trim())
                        )
                    )
                }),
                "obligations" to JsonArray(
                    preset.obligations.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_ITEMS)
                        .map(::JsonPrimitive)
                )
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
                note = obj.str("note")
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
                mapOf(
                    "name" to JsonPrimitive(relation.name),
                    "relation" to JsonPrimitive(relation.relation),
                    "note" to JsonPrimitive(relation.note)
                )
            )
        }).toString()
    }.getOrDefault("[]")

    private const val MAX_ITEMS = 32

    private fun JsonObject.str(key: String): String =
        ((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: "").trim()

    private fun JsonObject.arr(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.toList() ?: emptyList()
}
