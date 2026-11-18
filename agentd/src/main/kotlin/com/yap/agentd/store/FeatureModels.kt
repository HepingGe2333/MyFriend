package com.yap.agentd.store

import com.yap.agentd.store.StoreJson.nullableNum
import com.yap.agentd.store.StoreJson.nullableStr
import com.yap.agentd.store.StoreJson.num
import com.yap.agentd.store.StoreJson.obj
import com.yap.agentd.store.StoreJson.optInt
import com.yap.agentd.store.StoreJson.optLong
import com.yap.agentd.store.StoreJson.optString
import com.yap.agentd.store.StoreJson.reqLong
import com.yap.agentd.store.StoreJson.reqString
import com.yap.agentd.store.StoreJson.str
import com.yap.app.core.json.JsonValue

/**
 * 0.13 新增的三块服务端状态：**表情包 / 日记板 / 延后回复的念头**，外加自动判定的游标。
 *
 * 为什么都塞在这一个文件里：它们是同一批「服务端自己跑、客户端只同步」的东西
 * （`spec/stickers/README.md`、`spec/diary/README.md`、`spec/mood/README.md`），
 * 各自只有几十行，分散成四个文件只是让 `FileStore` 的 import 更长。
 *
 * 所有 `toJson` 的键名都是**磁盘契约**（`stickers.json` / `diary_notes.json` /
 * `agent_intents.json` / `auto_state.json`），改名 = 老数据读不出来，别随手改。
 */

// ---------------------------------------------------------------------- stickers.json

/**
 * 一个表情包（`spec/stickers/README.md` §数据）。
 *
 * 文件字节不在这里：走媒体表（`media.json` + `media/<id>.<ext>`），`kind=STICKER`，
 * [id] 同时就是媒体 id。这样备份 v6 的「`stickers[].fileName` ↔ `media[]` 里 `kind=STICKER`
 * 的 `fileName`」这条关联是天然的，不需要第二条 id 映射。
 *
 * [file] 是**裸文件名**（`<id>.gif`），与客户端 `StickerEntity.file` 的「裸名」语义一致；
 * 下载地址由 [url] 给（`/v1/media/<id>`）。
 */
data class Sticker(
    val id: String,
    val ownerUserId: String?,
    val personaId: String?,
    /** 裸文件名（不含目录、不含 scheme）。 */
    val file: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val frames: Int,
    val bytes: Long,
    /** `IMPORTED` / `MODEL_MADE` / `FROM_USER_MESSAGE` / `WEB`。 */
    val source: String,
    /** `USER` / `MODEL`。 */
    val createdBy: String,
    val createdAt: Long
) {
    val url: String get() = MediaUrl.url(id)

    /** 媒体引用（服务端内部/消息附件统一用它）。 */
    val mediaUrl: String get() = MediaUrl.url(id)

    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "ownerUserId" to nullableStr(ownerUserId),
        "personaId" to nullableStr(personaId),
        "file" to str(file),
        "mime" to str(mime),
        "width" to num(width),
        "height" to num(height),
        "frames" to num(frames),
        "bytes" to num(bytes),
        "source" to str(source),
        "createdBy" to str(createdBy),
        "createdAt" to num(createdAt)
    )

    companion object {
        const val SOURCE_IMPORTED = "IMPORTED"
        const val SOURCE_MODEL_MADE = "MODEL_MADE"
        const val SOURCE_FROM_USER_MESSAGE = "FROM_USER_MESSAGE"
        const val SOURCE_WEB = "WEB"
        val SOURCES = listOf(SOURCE_IMPORTED, SOURCE_MODEL_MADE, SOURCE_FROM_USER_MESSAGE, SOURCE_WEB)

        const val CREATED_BY_USER = "USER"
        const val CREATED_BY_MODEL = "MODEL"
        val CREATED_BY = listOf(CREATED_BY_USER, CREATED_BY_MODEL)

        /** `spec/stickers`：单文件 ≤512 KB、长边 ≤320 px、帧数 ≤60。 */
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_EDGE_PX = 320
        const val MAX_FRAMES = 60

        /** 允许的表情 mime（静态图也存成 gif/png/webp）。 */
        val MIMES = listOf(MediaMime.GIF, MediaMime.PNG, MediaMime.WEBP)

        fun normalizeSource(raw: String?): String {
            val cleaned = raw?.trim()?.uppercase().orEmpty()
            return if (cleaned in SOURCES) cleaned else SOURCE_IMPORTED
        }

        fun normalizeCreatedBy(raw: String?): String {
            val cleaned = raw?.trim()?.uppercase().orEmpty()
            return if (cleaned in CREATED_BY) cleaned else CREATED_BY_USER
        }

        fun fromJson(value: JsonValue.Obj, where: String): Sticker {
            val id = value.reqString("id", where)
            if (!MediaUrl.isValidId(id)) throw StoreCorruptionException("$where 的 id 不合法")
            val file = value.optString("file", where)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${id}.gif"
            if (!MediaItem.FILE_NAME_PATTERN.matches(file)) {
                throw StoreCorruptionException("$where 的 file 不是裸文件名")
            }
            val mime = value.optString("mime", where)?.trim()?.ifEmpty { null }
                ?: MediaMime.GIF
            if (!MediaMime.isValid(mime)) throw StoreCorruptionException("$where 的 mime 不合法：$mime")
            val personaId = value.optString("personaId", where)?.takeIf { it.isNotBlank() }
            if (personaId != null && !FileStore.isValidId(personaId)) {
                throw StoreCorruptionException("$where 的 personaId 不合法")
            }
            return Sticker(
                id = id,
                ownerUserId = value.optString("ownerUserId", where)?.takeIf { it.isNotBlank() },
                personaId = personaId,
                file = file,
                mime = mime,
                width = (value.optInt("width", where) ?: 0).coerceAtLeast(0),
                height = (value.optInt("height", where) ?: 0).coerceAtLeast(0),
                frames = (value.optInt("frames", where) ?: 1).coerceAtLeast(1),
                bytes = (value.optLong("bytes", where) ?: 0L).coerceAtLeast(0),
                source = normalizeSource(value.optString("source", where)),
                createdBy = normalizeCreatedBy(value.optString("createdBy", where)),
                createdAt = value.optLong("createdAt", where) ?: 0L
            )
        }
    }
}

// ---------------------------------------------------------------------- diary_notes.json

/**
 * 一条日记（`spec/diary/README.md` §数据）。
 *
 * `remindAt` 到点后由心跳把它变成一条主动消息并写 [remindedAt]（**只触发一次**）；
 * 错过超过 24 小时只清标记、不补发。
 */
data class DiaryNote(
    val id: String,
    val personaId: String,
    val content: String,
    val createdAt: Long,
    val remindAt: Long?,
    val remindedAt: Long?,
    val updatedAt: Long
) {
    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "personaId" to str(personaId),
        "content" to str(content),
        "createdAt" to num(createdAt),
        "remindAt" to nullableNum(remindAt),
        "remindedAt" to nullableNum(remindedAt),
        "updatedAt" to num(updatedAt)
    )

    /** 这条提醒是不是「到点且还没提醒过」。 */
    fun isDue(now: Long): Boolean =
        remindAt != null && remindedAt == null && remindAt <= now

    companion object {
        /** 内容上限（`spec/diary`：≤2000 字）。 */
        const val MAX_CONTENT_CHARS = 2_000

        /** 过点超过这么久就不再补发（只清标记）。 */
        const val CATCH_UP_WINDOW_MS = 24L * 60 * 60 * 1000

        /** `remindInMinutes` 的取值范围（1 分钟 – 30 天）。 */
        const val MIN_REMIND_MINUTES = 1
        const val MAX_REMIND_MINUTES = 43_200

        fun fromJson(value: JsonValue.Obj, where: String): DiaryNote {
            val id = value.reqString("id", where)
            if (!FileStore.isValidId(id)) throw StoreCorruptionException("$where 的 id 不合法")
            val personaId = value.reqString("personaId", where)
            if (!FileStore.isValidId(personaId)) throw StoreCorruptionException("$where 的 personaId 不合法")
            val createdAt = value.optLong("createdAt", where) ?: 0L
            return DiaryNote(
                id = id,
                personaId = personaId,
                content = value.optString("content", where).orEmpty(),
                createdAt = createdAt,
                remindAt = value.optLong("remindAt", where),
                remindedAt = value.optLong("remindedAt", where),
                updatedAt = value.optLong("updatedAt", where) ?: createdAt
            )
        }
    }
}

// ---------------------------------------------------------------------- agent_intents.json

/**
 * `defer_reply` 留下的一个念头（「过一会儿再找他」）。
 *
 * 客户端有 `agent_intents` 表；服务端这一份是**最小版**：`earliestAt` 到点后由心跳
 * 变成一条主动消息，写 [sentAt] 并改成 `SENT`（只发一次）。`status` 只有
 * `PENDING` / `SENT` / `EXPIRED` 三个值——服务端不需要客户端的完整状态机。
 */
data class StoredIntent(
    val id: String,
    val personaId: String,
    val sessionId: String?,
    val kind: String,
    val earliestAt: Long,
    val latestAt: Long,
    val note: String,
    val hint: String,
    val status: String,
    val createdAt: Long,
    val sentAt: Long?
) {
    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "personaId" to str(personaId),
        "sessionId" to nullableStr(sessionId),
        "kind" to str(kind),
        "earliestAt" to num(earliestAt),
        "latestAt" to num(latestAt),
        "note" to str(note),
        "hint" to str(hint),
        "status" to str(status),
        "createdAt" to num(createdAt),
        "sentAt" to nullableNum(sentAt)
    )

    fun isDue(now: Long): Boolean = status == STATUS_PENDING && earliestAt <= now

    /** 过窗口太久（`latestAt` 之后 24h）就作废，不再补发。 */
    fun isExpired(now: Long): Boolean =
        status == STATUS_PENDING && latestAt > 0 && now - latestAt > DiaryNote.CATCH_UP_WINDOW_MS

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_EXPIRED = "EXPIRED"
        const val KIND_REACH_OUT = "REACH_OUT"

        fun fromJson(value: JsonValue.Obj, where: String): StoredIntent {
            val id = value.reqString("id", where)
            if (!FileStore.isValidId(id)) throw StoreCorruptionException("$where 的 id 不合法")
            val personaId = value.reqString("personaId", where)
            if (!FileStore.isValidId(personaId)) throw StoreCorruptionException("$where 的 personaId 不合法")
            val createdAt = value.optLong("createdAt", where) ?: 0L
            return StoredIntent(
                id = id,
                personaId = personaId,
                sessionId = value.optString("sessionId", where),
                kind = value.optString("kind", where) ?: KIND_REACH_OUT,
                earliestAt = value.optLong("earliestAt", where) ?: createdAt,
                latestAt = value.optLong("latestAt", where) ?: 0L,
                note = value.optString("note", where).orEmpty(),
                hint = value.optString("hint", where).orEmpty(),
                status = value.optString("status", where) ?: STATUS_PENDING,
                createdAt = createdAt,
                sentAt = value.optLong("sentAt", where)
            )
        }
    }
}

// ---------------------------------------------------------------------- auto_state.json

/**
 * 自动判定的游标：某个人格「已经对到第几条用户消息」跑过情绪/进化判定。
 *
 * 为什么必须落盘：契约要求「每条聊天结束后自动触发，不依赖客户端连接」。
 * 如果游标只在内存里，服务端一重启就会把同一批消息再判一次（多花两份钱），
 * 而用户看到的只有「心情莫名其妙又变了一次」。
 *
 * [moodCursor] / [evolutionCursor] 都是**用户消息累计条数**（不是 id），
 * 语义与客户端 `lastMemoryExtractedCount` 同构：`userMessagesSeen - cursor >= N` 就触发。
 */
data class AutoState(
    val personaId: String,
    val moodCursor: Long = 0,
    val moodEvaluatedAt: Long = 0,
    val evolutionCursor: Long = 0,
    val evolutionEvaluatedAt: Long = 0,
    /** 累计见过的用户消息条数（服务端自己数，不用每次扫消息）。 */
    val userMessagesSeen: Long = 0,
    /**
     * 连续 `stay_silent` 的轮数（`spec/mood` §4：**不要连续使用超过 2 轮**）。
     *
     * 落盘而不是只放内存：重启之后「他刚连续两轮没说话」这件事不该被忘掉，
     * 否则第三轮又可以沉默。任何一条真正的 assistant 回复都会把它清零。
     */
    val consecutiveSilent: Int = 0
) {
    /** 连续沉默超过这个轮数就必须回一句（规范 §4）。 */
    val silentLimitReached: Boolean get() = consecutiveSilent >= MAX_CONSECUTIVE_SILENT

    fun toJson(): JsonValue.Obj = obj(
        "personaId" to str(personaId),
        "moodCursor" to num(moodCursor),
        "moodEvaluatedAt" to num(moodEvaluatedAt),
        "evolutionCursor" to num(evolutionCursor),
        "evolutionEvaluatedAt" to num(evolutionEvaluatedAt),
        "userMessagesSeen" to num(userMessagesSeen),
        "consecutiveSilent" to num(consecutiveSilent)
    )

    companion object {
        /** `spec/mood` §4：连续使用 `stay_silent` 的轮数上限。 */
        const val MAX_CONSECUTIVE_SILENT = 2

        fun fromJson(value: JsonValue.Obj, where: String): AutoState = AutoState(
            personaId = value.reqString("personaId", where),
            moodCursor = value.optLong("moodCursor", where) ?: 0L,
            moodEvaluatedAt = value.optLong("moodEvaluatedAt", where) ?: 0L,
            evolutionCursor = value.optLong("evolutionCursor", where) ?: 0L,
            evolutionEvaluatedAt = value.optLong("evolutionEvaluatedAt", where) ?: 0L,
            userMessagesSeen = value.optLong("userMessagesSeen", where) ?: 0L,
            consecutiveSilent = value.optInt("consecutiveSilent", where) ?: 0
        )
    }
}
