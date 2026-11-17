package com.yap.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条消息里附带的媒体，`uri` 指向应用私有目录中的副本
 * （服务端模式是 `/v1/media/<id>`；本地模式是 `file://` 路径）。
 *
 * [kind] 的取值（0.12 只有图片，所以老数据解出来是 `null`，等价于「普通图片」）：
 * - `null`：普通图片（与 0.12 的 `attachmentsJson` 完全兼容）；
 * - `"IMAGE"`：显式标注的普通图片（0.13 起新写入的图片可以带上，渲染与 null 相同）；
 * - `"STICKER"`：表情包（`spec/stickers/README.md`）。它也是 `image/gif`，
 *   但渲染方式、气泡尺寸、长按菜单、上传用途（`kind=STICKER`）都不同，所以必须能区分；
 * - `"AUDIO"`：语音（`spec/audio-video/README.md`），mime 是 `audio/m4a|mp4|mpeg`；
 * - `"VIDEO"`：视频，mime 是 `video/mp4|quicktime`。
 *
 * [durationMs] 只对语音/视频有意义（毫秒）；图片与表情为 `null`。
 * 它是**媒体本身的时长**，不是消息创建时间，也不参与排序。
 */
@Serializable
data class MessageAttachment(
    val uri: String,
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val kind: String? = null,
    val durationMs: Long? = null
) {
    /**
     * 图片类 mime。
     *
     * 注意 `video/quicktime` 以 `video/` 开头，不会落进这里；这个判定在 0.12 就存在，
     * 现在多了一层保险：视频 mime 显式排除。
     */
    val isImage: Boolean get() = mime.startsWith("image/") && !isVideo

    /** 表情包：显式标了 `kind=STICKER`，或干脆是 `image/gif`（老客户端/服务端只给 mime）。 */
    val isSticker: Boolean
        get() = kind == KIND_STICKER || mime.equals(RenderMime.GIF, ignoreCase = true)

    /** 普通聊天图片：是图片且不是表情包（「图片消息」的逻辑不该把表情也当成照片）。 */
    val isChatImage: Boolean get() = isImage && !isSticker

    /** 语音：显式 `kind=AUDIO`，或 mime 本身是音频（服务端/旧客户端只给 mime 的情况）。 */
    val isAudio: Boolean
        get() = kind == KIND_AUDIO || mime.startsWith("audio/")

    /** 视频：显式 `kind=VIDEO`，或 mime 本身是视频。 */
    val isVideo: Boolean
        get() = kind == KIND_VIDEO || mime.startsWith("video/")

    /** 需要模型「听/看」的媒体（聊天图片另走视觉通道，不在此列）。 */
    val isAudioOrVideo: Boolean get() = isAudio || isVideo

    /** 这条附件对应的多模态种类；图片/表情没有媒体种类。 */
    val mediaKind: AudioVideoKind?
        get() = when {
            isAudio -> AudioVideoKind.AUDIO
            isVideo -> AudioVideoKind.VIDEO
            else -> null
        }

    /** 落库/传输时用的规范 kind（图片保留 null 之外的显式值，避免改写老数据）。 */
    val canonicalKind: String?
        get() = when {
            isSticker -> KIND_STICKER
            isAudio -> KIND_AUDIO
            isVideo -> KIND_VIDEO
            kind == KIND_IMAGE -> KIND_IMAGE
            else -> kind
        }

    companion object {
        const val KIND_IMAGE = "IMAGE"
        const val KIND_STICKER = "STICKER"
        const val KIND_AUDIO = "AUDIO"
        const val KIND_VIDEO = "VIDEO"
    }
}

/** 附件/表情会遇到的 MIME 常量（只收录真正被比较的那几个，避免到处写裸字符串）。 */
object RenderMime {
    const val GIF = "image/gif"
}

object AttachmentsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(items: List<MessageAttachment>): String? {
        if (items.isEmpty()) return null
        return runCatching { json.encodeToString(items) }.getOrNull()
    }

    fun decode(raw: String?): List<MessageAttachment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<MessageAttachment>>(raw) }
            .getOrDefault(emptyList())
            // 老数据里可能有空 uri 的脏附件；媒体种类与时长在这里做一次宽松归一：
            // kind 大小写不统一（服务端/旧版本写过小写）也认，避免同一条语音换端后变成「图片」。
            .filter { it.uri.isNotBlank() }
            .map { attachment ->
                val normalizedKind = attachment.kind?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                val normalizedDuration = attachment.durationMs
                    ?.takeIf { it > 0L }
                if (normalizedKind == attachment.kind && normalizedDuration == attachment.durationMs) {
                    attachment
                } else {
                    attachment.copy(kind = normalizedKind, durationMs = normalizedDuration)
                }
            }
    }
}
