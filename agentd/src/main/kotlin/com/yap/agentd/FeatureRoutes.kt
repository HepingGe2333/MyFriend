package com.yap.agentd

import com.yap.agentd.AgentdApi.Response
import com.yap.agentd.store.DiaryNote
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.MediaUrl
import com.yap.agentd.store.Sticker
import com.yap.agentd.store.StoreDiaryNoteNotFoundException
import com.yap.agentd.store.StoreJson
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue
import java.util.Base64

/**
 * 表情包与日记板的服务端路由（`spec/stickers/README.md`、`spec/diary/README.md`）。
 *
 * | 方法 | 路径 | 说明 |
 * | --- | --- | --- |
 * | `GET` | `/v1/stickers?personaId=&limit=` | 表情列表（最近优先） |
 * | `POST` | `/v1/stickers` | 一条请求带元数据 + base64 字节（或 `mediaId`）→ 建行 |
 * | `DELETE` | `/v1/stickers/{id}` | 删表情（行 + 文件） |
 * | `GET` | `/v1/personas/{id}/diary?limit=&since=` | 他的日记板 |
 * | `POST` | `/v1/personas/{id}/diary` | 新建（无 id）/ 更新（带 id） |
 * | `DELETE` | `/v1/personas/{id}/diary/{noteId}` | 撕掉一条 |
 *
 * 为什么单独一个文件而不是塞进 [CrudRoutes]（900 多行）：这两块是 0.13 新增的
 * 「服务端自己也要用」的存储（`sticker` / `diary` 工具直接读写同一份数据），
 * 路由要长的形状只有这么几行，混进 CRUD 大文件只会让「谁在写这张表」更难 grep。
 *
 * 错误体与其它路由完全一致（`{"error":{"message":"…","code":"…"}}`）；
 * token/限流/1 MiB 请求体上限仍由 [AgentdServer] 统一处理。
 */
object StickerRoutes {

    const val PATH = "/v1/stickers"

    /**
     * `POST /v1/stickers` 里 inline base64 的字符上限。
     *
     * 为什么不是「512 KiB 原始字节 ≈ 683 KiB base64」：JSON 解析器（`:agentcore` 的 `Json`）
     * 对单个字符串有 256 KiB 字符的硬上限（安全审计要求，任何单字段都不能一口气吃掉堆）。
     * 所以 inline 最多约 **192 KiB 的文件**——压缩后的表情绝大多数比这更小；
     * 更大的文件走 `POST /v1/media?kind=STICKER`（原始字节）再 `POST /v1/stickers {"mediaId":…}`。
     */
    const val MAX_DATA_CHARS = 256 * 1024 - 1024

    /** 列表默认/最多返回多少条。 */
    const val DEFAULT_LIMIT = 30
    const val MAX_LIMIT = 100

    fun matches(path: String): Boolean = path == PATH || path.startsWith("$PATH/")

    fun handle(store: FileStore, method: String, rawPath: String, body: String?): Response {
        val path = rawPath.substringBefore('?')
        val verb = method.uppercase()
        return when {
            path == PATH && verb == "GET" -> list(store, rawPath)
            path == PATH && verb == "POST" -> create(store, body)
            path.startsWith("$PATH/") && verb == "DELETE" -> delete(store, path.removePrefix("$PATH/"))
            path == PATH -> throw methodNotAllowed(verb)
            else -> throw methodNotAllowed(verb)
        }
    }

    // ------------------------------------------------------------------ GET

    private fun list(store: FileStore, target: String): Response {
        val query = Query.parse(target)
        val personaId = query.string("personaId")?.trim()?.takeIf { it.isNotEmpty() }
        if (personaId != null && !FileStore.isValidId(personaId)) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", "personaId 格式不对")
        }
        val limit = (query.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        // 最近的在前：表情面板就是「最近 30 个 + 全部」。
        val stickers = store.stickers(personaId, limit = Int.MAX_VALUE)
            .sortedWith(compareByDescending<Sticker> { it.createdAt }.thenByDescending { it.id })
            .take(limit)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "serverTime" to StoreJson.num(store.serverTime()),
                    "stickers" to StoreJson.arr(stickers.map { stickerJson(it, store.stickerMediaId(it.id)) })
                )
            )
        )
    }

    // ------------------------------------------------------------------ POST

    private fun create(store: FileStore, body: String?): Response {
        val request = featureObjectBody(body)
        val personaId = request.optionalStringField("personaId")?.trim()?.takeIf { it.isNotEmpty() }
        if (personaId != null) {
            if (!FileStore.isValidId(personaId)) throw invalid("personaId 格式不对")
            if (store.persona(personaId) == null) {
                throw AgentdApi.ApiFailure(404, "not_found", "没有这个人格")
            }
        }
        val width = request.optionalIntField("width") ?: 0
        val height = request.optionalIntField("height") ?: 0
        val frames = request.optionalIntField("frames") ?: 1
        val source = request.optionalStringField("source")
        val createdBy = request.optionalStringField("createdBy")
        val ownerUserId = request.optionalStringField("ownerUserId")?.trim()?.takeIf { it.isNotEmpty() }

        // 两种建行方式：带 base64 字节（一条请求齐活），或引用一个已上传的媒体。
        val mediaId = request.optionalStringField("mediaId")?.trim()?.takeIf { it.isNotEmpty() }
        if (mediaId != null) {
            if (!MediaUrl.isValidId(mediaId)) throw invalid("mediaId 格式不对")
            val row = try {
                store.registerSticker(
                    mediaId = mediaId,
                    personaId = personaId,
                    width = width,
                    height = height,
                    frames = frames,
                    source = source ?: Sticker.SOURCE_IMPORTED,
                    createdBy = createdBy ?: Sticker.CREATED_BY_USER,
                    ownerUserId = ownerUserId
                )
            } catch (failure: IllegalArgumentException) {
                throw AgentdApi.ApiFailure(400, "invalid_argument", failure.message ?: "这个媒体不能当表情")
            }
            return Response(200, StoreJson.stringify(StoreJson.obj(
                "created" to StoreJson.bool(true),
                "sticker" to stickerJson(row, store.stickerMediaId(row.id))
            )))
        }

        val encoded = request.requireStringField("data").trim()
        if (encoded.isEmpty()) throw invalid("data 不能为空（标准 base64 的表情文件）")
        if (encoded.length > MAX_DATA_CHARS) {
            throw AgentdApi.ApiFailure(
                413,
                "content_too_large",
                "inline base64 最多 ${MAX_DATA_CHARS / 1024} KiB 字符：更大的表情请先 " +
                    "POST /v1/media?kind=STICKER 传原始字节，再用 {\"mediaId\":…} 建行"
            )
        }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw invalid("data 不是合法的 base64")
        }
        if (bytes.size > Sticker.MAX_FILE_BYTES) {
            throw AgentdApi.ApiFailure(
                413,
                "content_too_large",
                "表情文件最大 ${Sticker.MAX_FILE_BYTES / 1024} KB（解码后 ${bytes.size / 1024} KB）"
            )
        }
        val mime = (request.optionalStringField("mime")?.trim()?.lowercase())
            ?: MediaMime.GIF
        if (!MediaMime.isValid(mime)) throw invalid("不支持的 mime：$mime")
        val row = try {
            store.createSticker(
                personaId = personaId,
                mime = mime,
                data = bytes,
                width = width,
                height = height,
                frames = frames,
                source = source ?: Sticker.SOURCE_IMPORTED,
                createdBy = createdBy ?: Sticker.CREATED_BY_USER,
                ownerUserId = ownerUserId
            )
        } catch (failure: IllegalArgumentException) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", failure.message ?: "表情不合法")
        }
        return Response(200, StoreJson.stringify(StoreJson.obj(
            "created" to StoreJson.bool(true),
            "sticker" to stickerJson(row, store.stickerMediaId(row.id))
        )))
    }

    // ------------------------------------------------------------------ DELETE

    private fun delete(store: FileStore, stickerId: String): Response {
        if (!MediaUrl.isValidId(stickerId)) throw notFound()
        if (!store.deleteSticker(stickerId)) throw notFound()
        return Response(200, StoreJson.stringify(StoreJson.obj(
            "deleted" to StoreJson.bool(true),
            "id" to StoreJson.str(stickerId)
        )))
    }

    /**
     * 对外的行形状：`file`（裸名，与备份 `fileName` 同义）+ `mediaId`/`url`（取字节）。
     *
     * [mediaId] 由调用方从存储层解析（正常情况下等于 `id`；从客户端备份导入的行可能是另一条媒体）。
     */
    internal fun stickerJson(sticker: Sticker, mediaId: String?): JsonValue.Obj = StoreJson.obj(
        "id" to StoreJson.str(sticker.id),
        "ownerUserId" to StoreJson.nullableStr(sticker.ownerUserId),
        "personaId" to StoreJson.nullableStr(sticker.personaId),
        "file" to StoreJson.str(sticker.file),
        "fileName" to StoreJson.str(sticker.file),
        "mediaId" to StoreJson.str(mediaId ?: sticker.id),
        "url" to StoreJson.str(MediaUrl.url(mediaId ?: sticker.id)),
        "mime" to StoreJson.str(sticker.mime),
        "width" to StoreJson.num(sticker.width),
        "height" to StoreJson.num(sticker.height),
        "frames" to StoreJson.num(sticker.frames),
        "bytes" to StoreJson.num(sticker.bytes),
        "source" to StoreJson.str(sticker.source),
        "createdBy" to StoreJson.str(sticker.createdBy),
        "createdAt" to StoreJson.num(sticker.createdAt)
    )

    // ------------------------------------------------------------------ 小工具

    private fun invalid(message: String) = AgentdApi.ApiFailure(400, "invalid_argument", message)

    private fun notFound() = AgentdApi.ApiFailure(404, "not_found", "没有这个表情")

    private fun methodNotAllowed(verb: String) = AgentdApi.ApiFailure(
        405,
        "method_not_allowed",
        when (verb) {
            "PUT", "PATCH" -> "$PATH 只支持 GET/POST/DELETE"
            else -> "$PATH 只支持 GET/POST/DELETE"
        }
    )

    /** 查询串（只取第一个值；非法百分号转义 → 400）。 */
    internal class Query(private val values: Map<String, String>) {
        fun string(key: String): String? = values[key]

        fun long(key: String): Long? {
            val raw = values[key] ?: return null
            if (raw.isEmpty()) return null
            return raw.toLongOrNull() ?: throw invalid("参数 $key 必须是整数")
        }

        fun int(key: String): Int? {
            val raw = values[key] ?: return null
            if (raw.isEmpty()) return null
            return raw.toIntOrNull() ?: throw invalid("参数 $key 必须是整数")
        }

        companion object {
            fun parse(target: String): Query {
                val index = target.indexOf('?')
                if (index < 0 || index == target.length - 1) return Query(emptyMap())
                val values = LinkedHashMap<String, String>()
                for (pair in target.substring(index + 1).split('&')) {
                    if (pair.isEmpty()) continue
                    val equals = pair.indexOf('=')
                    val name = decode(if (equals < 0) pair else pair.substring(0, equals))
                    val value = if (equals < 0) "" else decode(pair.substring(equals + 1))
                    if (!values.containsKey(name)) values[name] = value
                }
                return Query(values)
            }

            private fun decode(text: String): String = try {
                java.net.URLDecoder.decode(text, Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw invalid("查询串里有非法的百分号转义")
            }
        }
    }
}

/**
 * 日记板（`spec/diary/README.md`）。
 *
 * 端点挂在人格下面（`/v1/personas/{id}/diary`），因为「每条笔记只属于一个人格」；
 * 工具 `diary` 读写的是同一份 `diary_notes.json`（`FileStore.upsertDiaryNote`）。
 */
object DiaryRoutes {

    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50

    /** 路径形如 `/v1/personas/{id}/diary[/{noteId}]`。 */
    fun matches(path: String, suffix: String): Boolean = suffix == "diary" || suffix.startsWith("diary/")

    fun handle(
        store: FileStore,
        method: String,
        personaId: String,
        suffix: String,
        rawPath: String,
        body: String?
    ): Response {
        if (!FileStore.isValidId(personaId) || store.persona(personaId) == null) {
            throw AgentdApi.ApiFailure(404, "not_found", "没有这个人格")
        }
        val verb = method.uppercase()
        val noteId = suffix.removePrefix("diary").removePrefix("/").takeIf { it.isNotEmpty() }
        return try {
            when {
                noteId == null && verb == "GET" -> list(store, personaId, rawPath)
                noteId == null && verb == "POST" -> upsert(store, personaId, body)
                noteId != null && verb == "DELETE" -> delete(store, personaId, noteId)
                else -> throw AgentdApi.ApiFailure(405, "method_not_allowed", "日记接口只支持 GET/POST/DELETE")
            }
        } catch (missing: StoreDiaryNoteNotFoundException) {
            AgentdApi.errorResponse(404, "not_found", "没有这条日记")
        }
    }

    private fun list(store: FileStore, personaId: String, target: String): Response {
        val query = StickerRoutes.Query.parse(target)
        val limit = (query.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val since = (query.long("since") ?: 0L).coerceAtLeast(0L)
        val notes = store.diaryNotes(personaId, limit = limit, since = since)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "personaId" to StoreJson.str(personaId),
                    "notes" to StoreJson.arr(notes.map { noteJson(it) })
                )
            )
        )
    }

    /**
     * 新建/更新。
     *
     * 请求体：`{"id"?, "content", "remindAt"?, "remindInMinutes"?}`。
     * - 不带 `id` = 新建；带 `id` 但不存在 → 404；
     * - `remindAt` 与 `remindInMinutes` 二选一（都给以 `remindAt` 为准）；
     * - `remindAt: null` = 清掉提醒（`remindedAt` 由服务端管，不接受客户端写）。
     */
    private fun upsert(store: FileStore, personaId: String, body: String?): Response {
        val request = featureObjectBody(body)
        val noteId = request.optionalStringField("id")?.trim()?.takeIf { it.isNotEmpty() }
        if (noteId != null && !FileStore.isValidId(noteId)) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", "id 格式不对")
        }
        val now = store.serverTime()
        val clearReminder = request.fields.containsKey("remindAt") && request.fields["remindAt"] is JsonValue.Null
        val explicitRemindAt = request.optionalLongField("remindAt")?.also {
            if (it <= 0L) throw AgentdApi.ApiFailure(400, "invalid_argument", "remindAt 必须是正的毫秒时间戳")
        }
        val remindInMinutes = request.optionalIntField("remindInMinutes")?.also {
            if (it !in DiaryNote.MIN_REMIND_MINUTES..DiaryNote.MAX_REMIND_MINUTES) {
                throw AgentdApi.ApiFailure(
                    400,
                    "invalid_argument",
                    "remindInMinutes 必须在 ${DiaryNote.MIN_REMIND_MINUTES}–${DiaryNote.MAX_REMIND_MINUTES} 之间"
                )
            }
        }
        val remindAt = explicitRemindAt
            ?: remindInMinutes?.let { now + it * 60_000L }
        val note = try {
            store.upsertDiaryNote(
                personaId = personaId,
                noteId = noteId,
                content = request.optionalStringField("content"),
                remindAt = remindAt,
                clearReminder = clearReminder && explicitRemindAt == null
            )
        } catch (missing: StoreDiaryNoteNotFoundException) {
            throw missing
        } catch (invalid: IllegalArgumentException) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", invalid.message ?: "日记不合法")
        }
        return Response(200, StoreJson.stringify(StoreJson.obj("note" to noteJson(note))))
    }

    private fun delete(store: FileStore, personaId: String, noteId: String): Response {
        if (!FileStore.isValidId(noteId)) throw notFound()
        if (!store.deleteDiaryNote(personaId, noteId)) throw notFound()
        return Response(200, StoreJson.stringify(StoreJson.obj(
            "deleted" to StoreJson.bool(true),
            "id" to StoreJson.str(noteId)
        )))
    }

    internal fun noteJson(note: DiaryNote): JsonValue.Obj = StoreJson.obj(
        "id" to StoreJson.str(note.id),
        "personaId" to StoreJson.str(note.personaId),
        "content" to StoreJson.str(note.content),
        "createdAt" to StoreJson.num(note.createdAt),
        "remindAt" to StoreJson.nullableNum(note.remindAt),
        "remindedAt" to StoreJson.nullableNum(note.remindedAt),
        "updatedAt" to StoreJson.num(note.updatedAt)
    )

    private fun notFound() = AgentdApi.ApiFailure(404, "not_found", "没有这条日记")
}

// ---------------------------------------------------------------------- 共用的小工具

/** 解析「必须是一个 JSON 对象」的请求体（空/坏 JSON/非对象都翻成 400）。 */
internal fun featureObjectBody(body: String?): JsonValue.Obj {
    if (body.isNullOrBlank()) throw AgentdApi.ApiFailure(400, "empty_body", "请求体不能为空")
    val root = try {
        com.yap.app.core.json.Json.parse(body)
    } catch (invalidJson: JsonParseException) {
        throw AgentdApi.ApiFailure(400, "invalid_json", "请求体不是合法 JSON：${invalidJson.message.orEmpty()}")
    }
    return root as? JsonValue.Obj
        ?: throw AgentdApi.ApiFailure(400, "invalid_body", "请求体必须是一个 JSON 对象")
}

internal fun JsonValue.Obj.requireStringField(key: String): String =
    (fields[key] as? JsonValue.Str)?.value
        ?: throw AgentdApi.ApiFailure(400, "missing_field", "缺少字段 $key（字符串）")

internal fun JsonValue.Obj.optionalStringField(key: String): String? = when (val value = fields[key]) {
    null, JsonValue.Null -> null
    is JsonValue.Str -> value.value
    else -> throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 $key 必须是字符串")
}

internal fun JsonValue.Obj.optionalIntField(key: String): Int? = when (val value = fields[key]) {
    null, JsonValue.Null -> null
    is JsonValue.Num -> value.asInt
        ?: throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 $key 必须是整数")

    else -> throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 $key 必须是整数")
}

internal fun JsonValue.Obj.optionalLongField(key: String): Long? = when (val value = fields[key]) {
    null, JsonValue.Null -> null
    is JsonValue.Num -> value.asLong
        ?: throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 $key 必须是整数")

    else -> throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 $key 必须是整数")
}
