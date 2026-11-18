package com.yap.app.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 服务端备份（`GET /v1/backup`，v6 同形）→ 客户端本地 v6 备份形状。
 *
 * 0.13 起服务端导出的就是**客户端 v6 同形 JSON**（`formatVersion=6`，顶层含
 * `media[]` / `stickers[]` / `diaryNotes[]`），所以这里只做「两端历史字段名不一致」的兼容，
 * 不再有版本落差：
 * - v6 新增的三段原样透传（本地 `BackupCodec` 已经能吃 v6）；
 * - 表情行的 `fileName` / `file` 两个同义字段互相补齐（服务端只写一个时本地也读得到）。
 *
 * 为什么需要一层适配而不是直接把服务端 JSON 塞进本地 `importBundle`：
 * - 服务端人格用 `avatarUrl`，客户端列是 `avatarUri`；
 * - 服务端记忆 `importance` 是 0–100，客户端是 1–5；
 * - 服务端消息把附件存成字符串数组，客户端存 `attachmentsJson` 文本；
 * - 服务端会话叫 `unread`，客户端备份叫 `unreadCount`。
 *
 * 不转换就会出现「接回来了，但头像没了 / 重要度变成 80 星 / 图片全丢」。
 * 这个适配**只用于刷新本地缓存**：上传给服务端的仍是原始备份（服务端负责权威导入）。
 */
object ServerBackupAdapter {

    /** 服务端备份原文 → 本地可解码的 v6 原文；不是 JSON 对象时返回 null。 */
    fun toLocalBackupJson(raw: String): String? {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull() ?: return null
        return toLocalBackup(root).toString()
    }

    fun toLocalBackup(root: JsonObject): JsonObject {
        val fields = linkedMapOf<String, JsonElement>()
        root.forEach { (key, value) -> if (key !in SECTIONS) fields[key] = value }

        root["personas"]?.let { fields["personas"] = mapArray(it) { persona ->
            val out = LinkedHashMap(persona)
            persona.string("avatarUrl")?.takeIf { it.isNotBlank() }?.let { url ->
                // 本地缓存也保留服务端媒体引用；渲染层会把它解析成带鉴权的 URL。
                out["avatarUri"] = JsonPrimitive(url)
            }
            JsonObject(out)
        } }
        root["memories"]?.let { fields["memories"] = mapArray(it) { memory ->
            val out = LinkedHashMap(memory)
            memory.int("importance")?.let { importance ->
                // 服务端 0–100 → 客户端 1–5；已经是 1–5 的旧客户端备份原样保留。
                out["importance"] = JsonPrimitive(
                    if (importance > 5) ServerMemoryWrite.importanceFromServer(importance) else importance.coerceIn(1, 5)
                )
            }
            // 客户端备份里的 tags 是 JSON 文本（`"["散步"]"`），服务端存的是数组；
            // 不转成字符串会让整条记忆解码失败并被丢弃（不是少个标签，是整条没了）。
            memory["tags"]?.let { tags ->
                if (tags is JsonArray) out["tags"] = JsonPrimitive(tags.toString())
            }
            JsonObject(out)
        } }
        root["sessions"]?.let { fields["sessions"] = mapArray(it) { session ->
            val out = LinkedHashMap(session)
            session.int("unread")?.let { out["unreadCount"] = JsonPrimitive(it) }
            JsonObject(out)
        } }
        // 预设字段两边同构（含 avatarUri），原样保留即可。
        root["presets"]?.let { fields["presets"] = it }
        // v6：表情行的文件名有两个同义字段（Android 写 `fileName`、iOS/服务端写 `file`）。
        // 两边都补齐，本地导入/缓存刷新才不会因为「表情文件找不到」把整行丢掉。
        root["stickers"]?.let { fields["stickers"] = mapArray(it) { sticker ->
            val out = LinkedHashMap(sticker)
            val fileName = sticker.string("fileName")?.takeIf { it.isNotBlank() }
            val file = sticker.string("file")?.takeIf { it.isNotBlank() }
            if (fileName == null && file != null) out["fileName"] = JsonPrimitive(file)
            if (file == null && fileName != null) out["file"] = JsonPrimitive(fileName)
            JsonObject(out)
        } }
        // v6：日记行两端同构（七个字段），原样保留即可；此处显式列出是为了让读者知道它被考虑过。
        root["diaryNotes"]?.let { fields["diaryNotes"] = it }
        root["messages"]?.let { fields["messages"] = mapArray(it) { message ->
            val out = LinkedHashMap(message)
            message["attachments"]?.let { attachments ->
                val json = attachmentsAsLocalJson(attachments)
                if (json != null) out["attachmentsJson"] = JsonPrimitive(json)
            }
            JsonObject(out)
        } }
        return JsonObject(fields)
    }

    private val SECTIONS = setOf("personas", "memories", "sessions", "messages", "presets")

    private fun mapArray(element: JsonElement, transform: (JsonObject) -> JsonObject): JsonArray {
        val array = runCatching { element.jsonArray }.getOrNull() ?: return JsonArray(emptyList())
        return JsonArray(array.mapNotNull { item -> runCatching { transform(item.jsonObject) }.getOrNull() })
    }

    /**
     * 服务端 `attachments: ["/v1/media/id"]` → 客户端 `attachmentsJson`。
     *
     * 只保留字符串项、丢掉坏数据；mime 用 image/jpeg 占位（本地渲染不依赖它，
     * 上传新消息时也会重新压缩成 JPEG）。
     */
    private fun attachmentsAsLocalJson(element: JsonElement): String? {
        val items = runCatching { element.jsonArray }.getOrNull() ?: return null
        val valid = items.mapNotNull { item ->
            if (item is JsonNull) return@mapNotNull null
            runCatching { item.jsonPrimitive.content }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (valid.isEmpty()) return null
        return buildJsonArray {
            valid.forEach { uri ->
                add(buildJsonObject {
                    put("uri", uri)
                    put("mime", "image/jpeg")
                })
            }
        }.toString()
    }
}
