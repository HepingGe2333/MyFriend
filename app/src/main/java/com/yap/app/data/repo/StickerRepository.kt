package com.yap.app.data.repo

import android.content.Context
import android.net.Uri
import com.yap.app.data.local.StickerDao
import com.yap.app.data.local.StickerEntity
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
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
     * 压缩 → 写 `files/stickers/<id>.gif` → 插一行。
     *
     * 写文件与插行都在 [MediaFileGc.withFileIoLock]（媒体文件临界区）里：
     * 与聊天图片、头像共用同一把锁，「写一半被杀掉」「刚写的文件被 GC 当成孤儿」都不会发生。
     */
    private suspend fun saveInternal(
        source: StickerSource,
        kind: StickerSourceKind,
        createdBy: StickerCreator,
        personaId: String?
    ): StickerEntity? {
        val build = StickerPipeline.build(source) ?: return null
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
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
                ownerUserId = null,
                personaId = personaId,
                file = Uri.fromFile(target).toString(),
                mime = RenderMime.GIF,
                width = build.width,
                height = build.height,
                frames = build.frames,
                bytes = build.byteCount,
                source = kind.name,
                createdBy = createdBy.name,
                createdAt = now
            )
            val inserted = runCatching { stickerDao.insert(entity) }.getOrDefault(-1L)
            if (inserted == -1L) {
                target.delete()
                return@withFileIoLock null
            }
            entity
        }
    }

    companion object {
        /** 历史面板第一屏：「最近 30 个」（`spec/stickers/README.md`）。 */
        const val RECENT_LIMIT = 30
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
