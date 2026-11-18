package com.yap.app.data.repo

import android.content.Context
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.remote.ServerMediaCache
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.AudioClipPart
import com.yap.app.domain.AudioVideoKind
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.MediaIds
import com.yap.app.domain.MediaPayload
import com.yap.app.domain.MessageAttachment
import com.yap.app.util.MediaStorage
import com.yap.app.util.VideoFramesExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话里的一段语音/视频（0.13）。
 *
 * [messageId] + [index] 就是提示词里给模型的 `mediaId`（`av:<messageId>:<序号>`）：
 * 模型在 `listen(audioId=…)` 里原样回传，这里再解析回一条具体的附件。
 */
data class SessionMedia(
    val messageId: String,
    /** 该消息内语音/视频的序号（一条消息最多一段语音 + 一条视频）。 */
    val index: Int,
    val attachment: MessageAttachment
) {
    val kind: AudioVideoKind
        get() = if (attachment.isAudio) AudioVideoKind.AUDIO else AudioVideoKind.VIDEO
}

/**
 * 语音/视频的**读取面**：本地私有文件与服务端 `/v1/media/<id>` 都从这一个入口进。
 *
 * 三个使用方：
 * 1. 聊天上下文（这一轮用户发来的语音/视频 → provider 多模态 parts）；
 * 2. `listen` / `watch` 工具（按 mediaId 找、或取会话最近一条）；
 * 3. 气泡播放（需要能交给 MediaPlayer/VideoView 的本地文件）。
 *
 * 所有读取都守两条线：
 * - **归属**：本地只读 `files/audio`、`files/video`（[MediaStorage.voiceVideoFile]）；
 * - **上限**：音频 8 MiB、视频 64 MiB（[AudioVideoPolicy]），服务端引用也按同一条线下载。
 *
 * 失败一律返回 null / 空列表，由调用方给出人话（工具里是 `MediaTool.unreadable`，
 * 聊天里是那句「这个模型听不了语音」）。这里绝不抛异常到聊天主链路。
 */
@Singleton
class AudioVideoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val mediaCache: ServerMediaCache
) {

    /** 一条消息里所有语音/视频附件（保持落库顺序）。 */
    fun mediaOf(message: MessageEntity): List<SessionMedia> {
        val attachments = AttachmentsCodec.decode(message.attachmentsJson)
            .filter { it.isAudioOrVideo }
        return attachments.mapIndexed { index, attachment ->
            SessionMedia(messageId = message.id, index = index, attachment = attachment)
        }
    }

    /** 提示词里给模型看的 mediaId（与 [MediaIds] 同一套编码）。 */
    fun mediaIdOf(media: SessionMedia): String = MediaIds.encode(media.messageId, media.index)

    /**
     * 找一段媒体：
     * - [requestedId] 非空：先按 `av:<messageId>:<序号>` 找（也接受只给 messageId）；
     * - 没给或找不到：取**这段会话最近一条**指定类型的媒体（从最近的消息往回翻）。
     */
    suspend fun findMedia(
        sessionId: String,
        kind: AudioVideoKind,
        requestedId: String? = null
    ): SessionMedia? {
        val reference = MediaIds.decode(requestedId)
        if (reference != null) {
            val explicit = runCatching {
                messageDao.getById(reference.messageId)?.let { message ->
                    mediaOf(message).firstOrNull { it.index == reference.index && it.kind == kind }
                        ?: mediaOf(message).firstOrNull { it.kind == kind }
                }
            }.getOrNull()
            if (explicit != null) return explicit
        }
        val recent = runCatching { messageDao.recentBySession(sessionId, SESSION_SCAN_LIMIT) }
            .getOrDefault(emptyList())
        return recent
            .asReversed()
            .asSequence()
            .mapNotNull { message -> mediaOf(message).firstOrNull { it.kind == kind } }
            .firstOrNull()
    }

    /** 最近的语音/视频（聊天上下文用：只看最新一条用户消息）。 */
    fun mediaInMessage(message: MessageEntity?, kind: AudioVideoKind? = null): List<SessionMedia> {
        if (message == null) return emptyList()
        val media = mediaOf(message)
        return if (kind == null) media else media.filter { it.kind == kind }
    }

    /** 可播放的本地文件：本地附件直接用，服务端引用先下载到缓存。 */
    suspend fun localFile(media: SessionMedia): File? {
        MediaStorage.voiceVideoFile(context, media.attachment.uri)?.let { return it }
        return mediaCache.localFile(media.attachment)
    }

    /**
     * 只按附件取本地可播放文件（气泡播放用）。
     *
     * 不需要知道它属于哪条消息：播放只看 uri/mime，而归属守卫仍在
     * [MediaStorage.voiceVideoFile] / [ServerMediaCache] 里各做一次。
     */
    suspend fun localFile(attachment: MessageAttachment): File? =
        localFile(SessionMedia(messageId = attachment.uri, index = 0, attachment = attachment))

    /** 原始字节（送给 provider 的音频，或抽帧的输入）。 */
    suspend fun readBytes(media: SessionMedia): ByteArray? = withContext(Dispatchers.IO) {
        val limit = when (media.kind) {
            AudioVideoKind.AUDIO -> AudioVideoPolicy.AUDIO_MAX_BYTES
            AudioVideoKind.VIDEO -> AudioVideoPolicy.VIDEO_MAX_BYTES
        }
        val file = localFile(media) ?: return@withContext null
        if (!file.isFile || !file.canRead()) return@withContext null
        if (file.length() <= 0L || file.length() > limit) return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * 把一段语音编成 `input_audio` part。
     *
     * 超过 8 MiB 直接返回 null（规格：>8MB 拒绝）。返回 null 时调用方**不要**伪造转写，
     * 而是在这一轮里说明「这段语音太大，听不到」。
     */
    suspend fun audioClip(media: SessionMedia): AudioClipPart? {
        if (media.kind != AudioVideoKind.AUDIO) return null
        val bytes = readBytes(media) ?: return null
        return MediaPayload.audioPart(bytes, media.attachment.mime, media.attachment.durationMs)
    }

    /** 抽帧：最多 8 张、每帧 ≤1024px、总 ≤8 MiB，返回 `data:image/jpeg;base64,…` 列表。 */
    suspend fun videoFrames(media: SessionMedia): List<String> {
        if (media.kind != AudioVideoKind.VIDEO) return emptyList()
        val file = localFile(media) ?: return emptyList()
        val jpegs = withContext(Dispatchers.Default) {
            VideoFramesExtractor.extract(file, AudioVideoPolicy.MAX_VIDEO_FRAMES)
        }
        return MediaPayload.frameDataUrls(jpegs.map { it.jpeg })
    }

    /** 视频时长（毫秒）：读不到返回 null（只按大小挡）。 */
    suspend fun videoDurationMs(media: SessionMedia): Long? {
        if (media.kind != AudioVideoKind.VIDEO) return media.attachment.durationMs
        media.attachment.durationMs?.takeIf { it > 0L }?.let { return it }
        val file = localFile(media) ?: return null
        return withContext(Dispatchers.IO) { VideoFramesExtractor.durationMs(file) }
    }

    private companion object {
        /** 找「最近一条语音/视频」时翻多少条消息：语音不会埋得太深，200 条足够。 */
        const val SESSION_SCAN_LIMIT = 200
    }
}
