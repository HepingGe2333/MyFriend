package com.yap.app.data.remote

import kotlinx.serialization.Serializable

/**
 * 服务端「他随身带的东西」：表情包与日记板（0.13）。
 *
 * 这两个东西在服务端模式下的归属与人格/记忆一样：**服务端是权威**，本机 Room 只是缓存。
 * 客户端只做三件事：
 * 1. 新增/删除时调用服务端 CRUD（写路径，失败要能让用户知道）；
 * 2. 拉取列表时更新本机缓存（表情的字节经 `/v1/media/<id>` 落盘，日记只同步行）；
 * 3. 界面上的展示、发送表情仍然读本机缓存（离线时也有东西可看）。
 *
 * 契约（与 `agentd` 的实现逐字对应，改字段必须两端一起改）：
 * - `GET /v1/stickers?personaId=&limit=` → `{"serverTime":…,"stickers":[StickerRow…]}`
 * - `POST /v1/stickers` → 一条请求带元数据 + base64（也接受先传 `/v1/media` 再带 `mediaId`）
 * - `DELETE /v1/stickers/{id}`
 * - `GET /v1/personas/{id}/diary?limit=&since=` → `{"personaId":…,"notes":[NoteRow…]}`
 * - `POST /v1/personas/{id}/diary` → `{"note":NoteRow}`
 * - `DELETE /v1/personas/{id}/diary/{noteId}`
 */

/** 服务端表情一行（与备份 v6 的 `stickers[]` 同形，`file` 是裸文件名）。 */
@Serializable
data class ServerStickerRow(
    val id: String = "",
    val ownerUserId: String? = null,
    val personaId: String? = null,
    /** 裸文件名（与备份 `fileName` 同义）；服务端也回 `fileName`，解析时取非空那个。 */
    val file: String = "",
    val fileName: String? = null,
    /** 服务端媒体 id（契约：与 [id] 相同；缺省时按 [id] 兜底）。 */
    val mediaId: String? = null,
    /** 下载地址 `/v1/media/<id>`（缺省时按 [id] 拼）。 */
    val url: String? = null,
    val mime: String = "image/gif",
    val width: Int = 0,
    val height: Int = 0,
    val frames: Int = 1,
    val bytes: Int = 0,
    val source: String = "IMPORTED",
    val createdBy: String = "USER",
    val createdAt: Long = 0L
) {
    /** 规范化媒体引用：`/v1/media/<id>`；没有可用 id 时 null。 */
    fun mediaRef(): String? = ServerMediaRef.canonical(url)
        ?: ServerMediaRef.canonical(mediaId)
        ?: ServerMediaRef.canonical(id)

    /** 本机缓存行的稳定 id：服务端 id 就是唯一真源。 */
    fun localId(): String = id.trim().ifEmpty { mediaRef()?.let { ServerMediaRef.idOf(it) }.orEmpty() }
}

/** `GET /v1/stickers` 的根对象（宽松解析：缺 `stickers` 当空列表）。 */
@Serializable
data class ServerStickerList(
    val serverTime: Long = 0L,
    val stickers: List<ServerStickerRow> = emptyList()
)

/** `POST /v1/stickers` 的响应：`{"created":true,"sticker":{…}}`。 */
@Serializable
data class ServerStickerCreated(
    val created: Boolean = false,
    val sticker: ServerStickerRow? = null
)

/** 服务端日记一条（与备份 v6 的 `diaryNotes[]` 同形）。 */
@Serializable
data class ServerDiaryRow(
    val id: String = "",
    val personaId: String = "",
    val content: String = "",
    val createdAt: Long = 0L,
    val remindAt: Long? = null,
    val remindedAt: Long? = null,
    val updatedAt: Long = 0L
)

/** `GET /v1/personas/{id}/diary` 的根对象。 */
@Serializable
data class ServerDiaryList(
    val personaId: String = "",
    val notes: List<ServerDiaryRow> = emptyList()
)

/** `POST /v1/personas/{id}/diary` 的响应。 */
@Serializable
data class ServerDiarySaved(
    val note: ServerDiaryRow? = null
)

/**
 * 本地人格 id → 服务端人格 id 的查询（生产实现是 [ServerWriteGateway]）。
 *
 * 抽接口只为一件事：表情/日记的拉取编排要能在纯 JVM 单测里跑，不需要构造加密存储。
 */
interface ServerPersonaIdMapper {
    suspend fun serverPersonaId(localPersonaId: String): String
}

/**
 * 服务端表情字节的本地化（生产实现走 `/v1/media/<id>` → `files/stickers/`）。
 *
 * 返回「规范引用 `/v1/media/<id>` → 本机 `file://`」的映射；下载失败的引用不出现在 map 里，
 * 调用方保留服务端引用（渲染时按需走网络）。
 */
interface ServerCatalogMedia {
    suspend fun localUris(refs: List<String>): Map<String, String>
}

/**
 * 本机有哪些人格（日记拉取要按人格逐个问服务端）。
 *
 * 抽接口的动机与 [ServerPersonaIdMapper] 相同：让同步编排在纯 JVM 单测里可跑。
 */
interface ServerCatalogPersonas {
    suspend fun localPersonaIds(): List<String>
}

/** [ServerCatalogMedia] 的生产实现：复用 [ServerMediaStore] 的幂等/原子落盘/失败占位。 */
class ServerStickerMedia(private val store: ServerMediaStore) : ServerCatalogMedia {
    override suspend fun localUris(refs: List<String>): Map<String, String> {
        if (refs.isEmpty()) return emptyMap()
        val result = store.materialize(
            refs.mapNotNull { ref ->
                val canonical = ServerMediaRef.canonical(ref)
                canonical?.let { ServerAttachmentRef(serverRef = it, kind = ServerMediaKind.STICKER) }
            }
        )
        return result.media.mapNotNull { (key, attachment) ->
            attachment.uri.takeIf { attachment.local }?.let { key to it }
        }.toMap()
    }
}
