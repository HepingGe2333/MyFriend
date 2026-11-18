package com.yap.app.data.repo

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.yap.app.data.local.StickerDao
import com.yap.app.data.local.StickerEntity
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
import com.yap.app.data.remote.ServerModeRepository
import com.yap.app.data.remote.ServerWriteGateway
import com.yap.app.data.remote.StickerMediaBytes
import com.yap.app.data.remote.StickerUrlDownloader
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.RenderMime
import com.yap.app.domain.StickerCreator
import com.yap.app.domain.StickerPipeline
import com.yap.app.domain.StickerPosition
import com.yap.app.domain.StickerRecord
import com.yap.app.domain.StickerSource
import com.yap.app.domain.StickerSourceKind
import com.yap.app.domain.StickerWorkspace
import com.yap.app.util.MediaFileGc
import com.yap.app.util.MediaStorage
import com.yap.app.util.StickerImporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 表情包的仓储：**导入 → 压缩 → 落盘 → 落库**，以及模型工具需要的全部副作用。
 *
 * 一份实现同时服务两条入口：
 * 1. 用户界面（相册导入、历史面板、长按收藏）；
 * 2. 模型工具 `sticker`（`send` / `make` / `save`）——所以它实现了纯逻辑需要的
 *    [StickerWorkspace] 接口，工具那边一行 Android 代码都没有（可纯 JVM 单测）。
 *
 * 文件与行的关系是「一行一文件」（`files/stickers/<id>.gif`）：
 * - 不用内容寻址：表情是**用户/模型的作品**，删一个收藏不该影响到聊天里已经发出去的那一份；
 * - 聊天消息只引用文件路径（`attachmentsJson`），不做引用计数：表情文件比聊天图片小得多
 *   （≤512 KB），而「删消息顺手删掉收藏」是更糟的 bug。
 */
@Singleton
class StickerRepository @Inject constructor(
    private val stickerDao: StickerDao,
    private val messageDao: MessageDao,
    private val mediaBytes: StickerMediaBytes,
    private val downloader: StickerUrlDownloader,
    /** 0.13：服务端模式下表情是服务端的东西，新增/删除要先过服务端（本机只是缓存）。 */
    private val serverMode: ServerModeRepository,
    private val serverWrites: ServerWriteGateway,
    @ApplicationContext private val context: Context
) : StickerWorkspace {

    // ---------------------------------------------------------------- 读取（界面）

    /** 最近的表情（历史面板第一屏，规范：最近 30 个）。 */
    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<StickerEntity>> = stickerDao.observeRecent(limit)

    /** 全部表情（面板里的「全部」）。 */
    fun observeAll(): Flow<List<StickerEntity>> = stickerDao.observeAll()

    suspend fun recent(limit: Int = RECENT_LIMIT): List<StickerEntity> = stickerDao.recent(limit)

    suspend fun get(id: String): StickerEntity? = stickerDao.getById(id)

    /** 把一行转成消息附件：`kind=STICKER` + `image/gif`（与 `MessageAttachment` 兼容扩展）。 */
    fun attachmentOf(sticker: StickerEntity): MessageAttachment = MessageAttachment(
        uri = sticker.file,
        mime = RenderMime.GIF,
        width = sticker.width,
        height = sticker.height,
        kind = MessageAttachment.KIND_STICKER
    )

    /** 渲染用：某个引用（文件路径 / 服务端媒体）→ 画面序列；失败返回 null（界面画占位）。 */
    suspend fun loadFrames(rawUri: String): StickerSource? = withContext(Dispatchers.IO) {
        val bytes = mediaBytes.load(rawUri) ?: return@withContext null
        mediaBytes.decode(bytes)
    }

    // ---------------------------------------------------------------- 导入（用户）

    /**
     * 相册 / 文件选择导入：读字节 → 逐帧解码 → 同一条压缩管线 → `files/stickers/<id>.gif`。
     *
     * 与聊天图片的 `MediaStorage.persistChatImage` 是**两条独立管线**（规范要求）：
     * 那边是「长边 1568 的 JPEG」，这边是「长边 320 的 GIF」；唯一共享的是「只写 App 私有目录」。
     */
    suspend fun import(uri: Uri, personaId: String? = null): Result<StickerEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = StickerImporter.readBytes(context, uri)
                    ?: error("sticker input is not readable")
                val source = StickerImporter.decodeBytes(bytes)
                    ?: error("sticker input is not an image")
                saveInternal(source, StickerSourceKind.IMPORTED, StickerCreator.USER, personaId)
                    ?: error("sticker could not be compressed")
            }
        }

    // ---------------------------------------------------------------- 收藏（长按）

    /**
     * 「添加到我的表情」：把某条消息里的表情存进收藏。
     *
     * 规范要求「同一文件、`createdBy` 不变，只增加 owner 关联」：
     * - 消息里的引用就是本机 `files/stickers/<id>.gif` → 直接复用同一个文件，只插一行（`source=FROM_USER_MESSAGE`）；
     * - 消息里的引用是服务端媒体 / 已被删掉 → 下载字节后再走一次压缩管线（拿不到就别假装收藏成功）。
     */
    suspend fun saveFromMessage(messageId: String, personaId: String? = null): Result<StickerEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val saved = saveStickerFromMessage(messageId, personaId)
                    ?: error("message has no sticker")
                stickerDao.getById(saved.id) ?: error("sticker row disappeared")
            }
        }

    /**
     * 服务端模式发送前：把表情文件读成字节（只读 `files/stickers`，≤8 MiB）。
     *
     * 上传编排在 `ServerStickerUploader`，那里只认字节，不碰数据库。
     */
    suspend fun bytesOf(sticker: StickerEntity): ByteArray? =
        withContext(Dispatchers.IO) { mediaBytes.load(sticker.file) }

    // ---------------------------------------------------------------- 数据清空

    /** 「删除全部数据」：表情行与表情文件一起清（文件不在 `MediaStorage.ownedRoots` 里，必须显式删）。 */
    suspend fun wipeAll(): Int = withContext(Dispatchers.IO) {
        val rows = runCatching { stickerDao.count() }.getOrDefault(0)
        runCatching { stickerDao.deleteAll() }
        val files = MediaStorage.clearStickers(context)
        rows + files
    }

    // ---------------------------------------------------------------- StickerWorkspace

    override suspend fun findSticker(stickerId: String): StickerRecord? =
        stickerDao.getById(stickerId.trim())?.toRecord()

    override suspend fun downloadImage(url: String): ByteArray? = downloader.download(url)

    override suspend fun decodeImage(bytes: ByteArray): StickerSource? = withContext(Dispatchers.IO) {
        mediaBytes.decode(bytes)
    }

    override suspend fun decodeSticker(sticker: StickerRecord): StickerSource? =
        loadFrames(sticker.file)

    override suspend fun blankSource(): StickerSource = StickerImporter.blankSource()

    override suspend fun renderText(
        source: StickerSource,
        text: String,
        position: StickerPosition
    ): StickerSource = withContext(Dispatchers.Default) {
        StickerImporter.renderText(source, text, position)
    }

    override suspend fun renderOverlay(
        source: StickerSource,
        overlay: StickerSource,
        position: StickerPosition
    ): StickerSource = withContext(Dispatchers.Default) {
        StickerImporter.renderOverlay(source, overlay, position)
    }

    override suspend fun saveSticker(
        source: StickerSource,
        kind: StickerSourceKind,
        createdBy: StickerCreator,
        personaId: String?
    ): StickerRecord? = withContext(Dispatchers.IO) {
        saveInternal(source, kind, createdBy, personaId)?.toRecord()
    }

    override suspend fun saveStickerFromMessage(messageId: String, personaId: String?): StickerRecord? =
        withContext(Dispatchers.IO) {
            val message = messageDao.getById(messageId) ?: return@withContext null
            val attachments = AttachmentsCodec.decode(message.attachmentsJson)
            val sticker = attachments.firstOrNull { it.isSticker } ?: return@withContext null
            val createdBy = if (message.role == MessageRole.ASSISTANT) {
                StickerCreator.MODEL
            } else {
                StickerCreator.USER
            }
            // 1. 同一份本地文件已经收藏过：只补一次 owner 关联，不重复收。
            stickerDao.findByFile(sticker.uri)?.let { existing ->
                if (personaId != null && existing.personaId == null) {
                    runCatching { stickerDao.setPersona(existing.id, personaId) }
                }
                return@withContext existing.copy(personaId = existing.personaId ?: personaId).toRecord()
            }
            // 2. 引用是服务端媒体/外部地址：先取字节，再走同一条管线落到本地。
            val source = mediaBytes.load(sticker.uri)?.let { bytes -> mediaBytes.decode(bytes) }
                ?: return@withContext null
            saveInternal(source, StickerSourceKind.FROM_USER_MESSAGE, createdBy, personaId)?.toRecord()
        }

    // ---------------------------------------------------------------- 落盘 + 落库

    /**
     * 压缩 → （服务端模式下先建服务端行）→ 写 `files/stickers/<id>.gif` → 插一行。
     *
     * 写文件与插行都在 [MediaFileGc.withFileIoLock]（媒体文件临界区）里：
     * 与聊天图片、头像共用同一把锁，「写一半被杀掉」「刚写的文件被 GC 当成孤儿」都不会发生。
     *
     * 服务端模式（0.13）：表情是**服务端的东西**，先 `POST /v1/stickers` 拿到服务端 id，
     * 再用这个 id 落本地缓存文件与行。服务端失败就整次失败（返回 null，界面提示「没存下来」）——
     * 绝不悄悄只存本机，否则下次同步时这个表情会「凭空消失」。
     */
    private suspend fun saveInternal(
        source: StickerSource,
        kind: StickerSourceKind,
        createdBy: StickerCreator,
        personaId: String?
    ): StickerEntity? {
        val build = StickerPipeline.build(source) ?: return null
        val now = System.currentTimeMillis()
        // 服务端模式：先建服务端行（服务端分配 id）；失败直接放弃，不写本地半份数据。
        val serverRow = if (serverMode.isEnabled()) {
            // 服务端只认自己的 id：本地人格 id 先换成服务端 id 再 POST，否则会 404。
            val serverPersonaId = personaId?.let {
                runCatching { serverWrites.serverPersonaId(it) }.getOrElse { _ -> it }
            }
            // 大表情不能走 inline base64（服务端 JSON 单字符串 256 KiB 上限，base64 后
            // 原文件只剩 ~192 KiB）：先 `POST /v1/media?kind=STICKER`，再用 mediaId 建行。
            val mediaId = if (build.bytes.size > INLINE_BASE64_LIMIT_BYTES) {
                serverWrites.uploadStickerMedia(build.bytes, serverPersonaId).getOrNull() ?: return null
            } else {
                null
            }
            serverWrites.createSticker(
                payload = serverStickerPayload(
                    bytes = if (mediaId == null) build.bytes else null,
                    mediaId = mediaId,
                    kind = kind,
                    createdBy = createdBy,
                    width = build.width,
                    height = build.height,
                    frames = build.frames,
                    personaId = serverPersonaId
                )
            ).getOrNull() ?: return null
        } else {
            null
        }
        val id = serverRow?.localId()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val dir = MediaStorage.stickerDirectory(context)
        return MediaFileGc.withFileIoLock {
            val target = File(dir, "$id.gif")
            val temp = File(dir, "$id.tmp")
            val written = runCatching {
                temp.writeBytes(build.bytes)
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    // 改名失败（极少数文件系统）：退化成直接写到目标文件。
                    temp.delete()
                    target.writeBytes(build.bytes)
                }
                target.isFile && target.length() == build.byteCount.toLong()
            }.getOrDefault(false)
            if (!written) {
                temp.delete()
                target.delete()
                return@withFileIoLock null
            }
            val entity = StickerEntity(
                id = id,
                ownerUserId = serverRow?.ownerUserId,
                // 本机缓存一律存**本地**人格 id（服务端 id 只用于请求）。
                personaId = personaId,
                file = Uri.fromFile(target).toString(),
                mime = serverRow?.mime?.takeIf { it.isNotBlank() } ?: RenderMime.GIF,
                width = build.width,
                height = build.height,
                frames = build.frames,
                bytes = build.byteCount,
                source = serverRow?.source?.takeIf { it.isNotBlank() } ?: kind.name,
                createdBy = serverRow?.createdBy?.takeIf { it.isNotBlank() } ?: createdBy.name,
                createdAt = serverRow?.createdAt?.takeIf { it > 0L } ?: now
            )
            val inserted = runCatching { stickerDao.insert(entity) }.getOrDefault(-1L)
            if (inserted == -1L) {
                target.delete()
                return@withFileIoLock null
            }
            entity
        }
    }

    /**
     * 删一个表情（面板/设置里的「删掉」）。
     *
     * 服务端模式先 `DELETE /v1/stickers/{id}`，成功（或服务端说 404 = 已经没了）后才删本机缓存行。
     * 本机文件**不删**：聊天历史里已经发出去的那条消息仍然引用同一个文件，删了会让旧消息变成
     * 一张打不开的图（iOS 侧也是同一规则）。表情文件 ≤512 KB，留给媒体清理就好。
     */
    suspend fun delete(id: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val stickerId = id.trim()
            if (stickerId.isEmpty()) return@runCatching false
            if (serverMode.isEnabled()) {
                // 失败原样上抛：用户点删除时要能知道「服务端没删掉」。
                serverWrites.deleteSticker(stickerId).getOrThrow()
            } else if (stickerDao.getById(stickerId) == null) {
                return@runCatching false
            }
            stickerDao.delete(stickerId) > 0
        }
    }

    /** 服务端建表情的请求体：一条请求带元数据 + base64（服务端契约 §2）。 */
    private fun serverStickerPayload(
        bytes: ByteArray?,
        mediaId: String?,
        kind: StickerSourceKind,
        createdBy: StickerCreator,
        width: Int,
        height: Int,
        frames: Int,
        personaId: String?
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        if (!personaId.isNullOrBlank()) put("personaId", personaId)
        put("mime", RenderMime.GIF)
        if (mediaId != null) {
            // 服务端契约：`mediaId` 表示「字节已经通过 /v1/media 传过了」。
            put("mediaId", mediaId)
        } else if (bytes != null) {
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("bytes", bytes.size)
        }
        put("width", width)
        put("height", height)
        put("frames", frames)
        put("source", kind.name)
        put("createdBy", createdBy.name)
    }

    companion object {
        /** 历史面板第一屏：「最近 30 个」（`spec/stickers/README.md`）。 */
        const val RECENT_LIMIT = 30

        /**
         * 服务端 inline base64 的**原文件**上限（保守取 180 KiB）。
         *
         * 服务端的 JSON 解析器对单个字符串有 256 KiB 硬上限，base64 膨胀 4/3，
         * 原文件最多约 192 KiB；超过就改走 `/v1/media` + `mediaId`。
         */
        const val INLINE_BASE64_LIMIT_BYTES = 180 * 1024
    }
}

/** 实体 → 领域模型（工具与界面都只认领域模型，不直接摸 Room 的行）。 */
fun StickerEntity.toRecord(): StickerRecord = StickerRecord(
    id = id,
    file = file,
    mime = mime,
    width = width,
    height = height,
    frames = frames,
    bytes = bytes,
    source = source,
    createdBy = createdBy,
    createdAt = createdAt,
    personaId = personaId,
    ownerUserId = ownerUserId
)
