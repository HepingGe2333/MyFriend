package com.yap.app.domain

import java.util.Base64
import java.util.Locale

/**
 * 一次请求里要发给模型的**语音片段**。
 *
 * [base64] 是裸 base64（不带 `data:` 前缀）：OpenAI 兼容的 `input_audio` 就长这样。
 * [format] 是 provider 认的短名（`m4a` / `mp3` / `wav`），由 [AudioVideoPolicy.audioFormat] 归一。
 *
 * 为什么字段名不叫 `data`：`data` 在 Kotlin 里太容易和 `data class` 混着读，
 * 而且这里放的是编码后的字符串，不是字节。
 */
data class AudioClipPart(
    val base64: String,
    val format: String,
    val durationMs: Long? = null
)

/**
 * 一条工具结果里附带的媒体：`listen` 带一段语音，`watch` 带若干帧。
 *
 * 它挂在 [ToolResult.media] 上，由聊天层转成 provider 的多模态 parts 回灌给模型。
 * 这些内容**只出现在这一轮的工具结果里**：不写库、不进历史上下文，
 * 否则每聊一句都把整段音频 base64 重发一遍（历史里只留一句「（发来一段语音）」）。
 */
data class ToolMedia(
    val images: List<String> = emptyList(),
    val audios: List<AudioClipPart> = emptyList(),
    /** 给模型看的一句说明（「视频：5 帧，时长 37 秒」）；没有就不写。 */
    val caption: String = ""
) {
    val isEmpty: Boolean get() = images.isEmpty() && audios.isEmpty()
}

/** 语音 base64 与视频帧的编码：都在这里，纯函数，JVM 可测。 */
object MediaPayload {

    /** 一条语音最多 8 MiB（原始字节），超了拒绝——与上传上限同一套值。 */
    const val MAX_AUDIO_BYTES: Long = AudioVideoPolicy.AUDIO_MAX_BYTES

    /**
     * 把读到的音频字节编成 `input_audio` 的 part。
     *
     * 返回 null 的三种情况：空字节、超 8 MiB、mime 不是白名单里的音频。
     * **不抛异常**：编码失败只该让这一轮少一段音频，由调用方决定怎么提示。
     */
    fun audioPart(bytes: ByteArray, mime: String?, durationMs: Long? = null): AudioClipPart? {
        if (bytes.isEmpty()) return null
        if (bytes.size.toLong() > MAX_AUDIO_BYTES) return null
        val format = AudioVideoPolicy.audioFormat(mime) ?: return null
        val encoded = runCatching { Base64.getEncoder().encodeToString(bytes) }.getOrNull() ?: return null
        if (encoded.isEmpty()) return null
        return AudioClipPart(base64 = encoded, format = format, durationMs = durationMs)
    }

    /** 一帧 JPEG → `data:image/jpeg;base64,…`；空字节返回 null。 */
    fun frameDataUrl(jpeg: ByteArray): String? {
        if (jpeg.isEmpty()) return null
        val encoded = runCatching { Base64.getEncoder().encodeToString(jpeg) }.getOrNull() ?: return null
        if (encoded.isEmpty()) return null
        return "data:image/jpeg;base64,$encoded"
    }

    /**
     * 一组帧 → data URL 列表。
     *
     * 单帧超 1 MiB / 总字节超 8 MiB / 超过 8 帧的部分会被丢掉（**按时间顺序保留前面的**），
     * 而不是让整个请求失败：少两帧最多是「看得不完整」，整个请求失败是「他听不到/看不到」。
     */
    fun frameDataUrls(frames: List<ByteArray>): List<String> {
        var total = 0L
        val result = mutableListOf<String>()
        for (jpeg in frames) {
            if (result.size >= AudioVideoPolicy.MAX_VIDEO_FRAMES) break
            if (!AudioVideoPolicy.frameFits(total, jpeg.size)) continue
            val dataUrl = frameDataUrl(jpeg) ?: continue
            result += dataUrl
            total += jpeg.size.toLong()
        }
        return result
    }

    /** 从 data URL 里取回裸 base64（测试与调试用；解不出来返回空串）。 */
    fun base64Of(dataUrl: String): String =
        dataUrl.substringAfter("base64,", "").ifBlank { dataUrl.trim() }
}

/**
 * 「这一轮带了什么媒体」的提示词块。
 *
 * 模型必须知道**这一条消息**里带着语音/视频（而不是只看到一句占位符），
 * 否则它不会想到去调 `listen` / `watch`。同时这里只给 id 与类型，不给 base64：
 * 媒体本体由那一轮的 user 消息 / 工具结果单独发送，历史里不重复。
 */
data class CurrentMedia(
    /** 稳定的媒体 id（`av:<messageId>:<序号>`），模型在 `listen(audioId=…)` 里原样传回来。 */
    val mediaId: String,
    val kind: AudioVideoKind,
    val durationMs: Long?,
    val mime: String? = null
)

object AudioVideoText {

    /** 当前轮媒体块的标题。 */
    const val CURRENT_BLOCK_TITLE = "【对方这一轮发来的】"

    /** 一条消息里最多附带一段语音 + 一条视频（再多也不会有意义，只会把请求撑大）。 */
    const val MAX_MEDIA_PER_MESSAGE = 2

    /**
     * 当前轮媒体块（写进 system 的 variableBlocks）。
     *
     * 内容很短：类型、时长、mediaId，最后一句告诉模型「要听/要看就调用对应工具」。
     * 空列表返回空串，调用方据此不注入这一段。
     */
    fun currentBlock(items: List<CurrentMedia>): String {
        if (items.isEmpty()) return ""
        return buildString {
            appendLine(CURRENT_BLOCK_TITLE)
            items.forEach { media ->
                when (media.kind) {
                    AudioVideoKind.AUDIO -> appendLine(
                        "- 语音：时长 ${AudioVideoPolicy.clockDuration(media.durationMs)}，" +
                            "mediaId=${media.mediaId}（想听就调用 listen）"
                    )
                    AudioVideoKind.VIDEO -> appendLine(
                        "- 视频：时长 ${AudioVideoPolicy.clockDuration(media.durationMs)}，" +
                            "mediaId=${media.mediaId}（想看画面就调用 watch）"
                    )
                }
            }
        }.trimEnd()
    }

    /** 历史/上下文里表示「这条消息带了一段语音」的占位正文（不带 base64）。 */
    fun audioPlaceholder(durationMs: Long?): String {
        val seconds = AudioVideoPolicy.durationSeconds(durationMs)
        return "（对方发来一段 $seconds 秒的语音）"
    }

    /** 历史/上下文里表示「这条消息带了一段视频」的占位正文。 */
    fun videoPlaceholder(durationMs: Long?): String {
        val seconds = AudioVideoPolicy.durationSeconds(durationMs)
        return "（对方发来一段 $seconds 秒的视频）"
    }

    /** 模型面前那段语音的一句话说明（跟在 `input_audio` 旁边）。 */
    fun listenContent(durationMs: Long?): String =
        "（这是对方发来的语音，时长 ${AudioVideoPolicy.durationSeconds(durationMs)} 秒。你听了一遍。）"

    /** 模型面前那段视频的一句话说明（跟在抽出来的帧旁边）。 */
    fun watchContent(frames: Int, durationMs: Long?): String =
        "（这是对方发来的视频，${VideoFramePlan.caption(frames, durationMs ?: 0L)}。你看了这些画面。）"

    /** 工具摘要：与规格里的「我听了一遍…」一致，短到能放进一行小字。 */
    fun listenSummary(durationMs: Long?): String =
        "我听了一遍 · ${AudioVideoPolicy.durationSeconds(durationMs)} 秒"

    /** 工具摘要：与规格里的「我看了 N 帧…」一致。 */
    fun watchSummary(frames: Int, durationMs: Long?): String =
        "我看了 $frames 帧 · ${AudioVideoPolicy.durationSeconds(durationMs)} 秒"

    /** 找不到媒体时回灌给模型的人话（不要让它以为工具坏了）。 */
    fun noMedia(kind: AudioVideoKind): String = when (kind) {
        AudioVideoKind.AUDIO -> "这段聊天里没有语音可以听。先请对方发一段，或者说清楚你现在听不到。"
        AudioVideoKind.VIDEO -> "这段聊天里没有视频可以看。先请对方发一段，或者说清楚你现在看不到。"
    }

    /** 模型不支持该模态时给用户看的说法（存成令牌，界面按语言取资源）。 */
    fun unsupportedModelText(kind: AudioVideoKind): String = when (kind) {
        AudioVideoKind.AUDIO -> "这个模型听不了语音"
        AudioVideoKind.VIDEO -> "这个模型看不了视频"
    }

    /** 语音气泡下方/预览里的一行字（不带语言的部分，语言文案走资源）。 */
    fun durationLabel(durationMs: Long?): String = AudioVideoPolicy.clockDuration(durationMs)

    /** 归类标签（服务端/日志用，全大写，与 `agentd` 的 MediaKind 一致）。 */
    fun kindLabel(kind: AudioVideoKind): String = kind.name.uppercase(Locale.US)
}

/**
 * 媒体 id 的编解码。
 *
 * 一条消息最多两段媒体（语音 + 视频各一），所以 id = `av:<messageId>:<序号>`；
 * 模型只会原样回传，不需要理解结构。也接受「只给 messageId」（序号默认 0）、
 * 以及服务端模式下的 `/v1/media/<id>` 引用（那种会先当消息 id 找一圈，找不到再按媒体引用找）。
 */
data class MediaRefId(val messageId: String, val index: Int = 0)

object MediaIds {

    const val PREFIX = "av:"

    fun encode(messageId: String, index: Int): String = "$PREFIX$messageId:$index"

    fun decode(raw: String?): MediaRefId? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (!value.startsWith(PREFIX)) return MediaRefId(value, 0)
        val rest = value.removePrefix(PREFIX)
        val separator = rest.lastIndexOf(':')
        if (separator <= 0) return MediaRefId(rest, 0).takeIf { rest.isNotBlank() }
        val id = rest.substring(0, separator)
        val index = rest.substring(separator + 1).toIntOrNull() ?: 0
        if (id.isBlank()) return null
        return MediaRefId(id, index.coerceAtLeast(0))
    }

    /** 从消息的附件列表里挑出第 [index] 段媒体（越界返回 null）。 */
    fun mediaAt(attachments: List<MessageAttachment>, index: Int): MessageAttachment? =
        attachments.filter { it.isAudioOrVideo }.getOrNull(index)
}

/** 媒体输入在 provider 侧失败的四种情况（界面按语言取资源）。 */
enum class MediaErrorCode {
    UNSUPPORTED_AUDIO,
    UNSUPPORTED_VIDEO,
    AUDIO_TOO_LARGE,
    VIDEO_TOO_LARGE
}

/**
 * 「模型不支持这种模态」的**落库令牌**。
 *
 * 这里不能往 `DomainErrorCode` 里加枚举（那是 `agentcore` 的跨端契约，不在本次改动范围），
 * 但聊天气泡里那条失败说明必须能在英文界面下说英文。所以沿用 `@mf-err:` 的同一套思路：
 * 库里存 `@yap-media:audio`，`ErrorText.stored` 读到它再按当前语言取资源；
 * 老数据里的中文原样显示。
 */
object MediaErrorTokens {

    const val PREFIX = "@yap-media:"

    const val AUDIO = "audio"
    const val VIDEO = "video"

    /** 服务端 413：模型/网关收不下这么大的请求（语音 8 MiB 的 base64 会更大）。 */
    const val AUDIO_TOO_LARGE = "audio_large"
    const val VIDEO_TOO_LARGE = "video_large"

    fun encode(kind: AudioVideoKind): String = PREFIX + when (kind) {
        AudioVideoKind.AUDIO -> AUDIO
        AudioVideoKind.VIDEO -> VIDEO
    }

    /** 请求体积被拒（413）：不是「模型听不了」，而是这一条太大。 */
    fun encodeTooLarge(kind: AudioVideoKind): String = PREFIX + when (kind) {
        AudioVideoKind.AUDIO -> AUDIO_TOO_LARGE
        AudioVideoKind.VIDEO -> VIDEO_TOO_LARGE
    }

    /** 解出来的错误类别；不是本协议令牌返回 null。 */
    fun decode(raw: String?): MediaErrorCode? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith(PREFIX)) return null
        return when (text.removePrefix(PREFIX).trim()) {
            AUDIO -> MediaErrorCode.UNSUPPORTED_AUDIO
            VIDEO -> MediaErrorCode.UNSUPPORTED_VIDEO
            AUDIO_TOO_LARGE -> MediaErrorCode.AUDIO_TOO_LARGE
            VIDEO_TOO_LARGE -> MediaErrorCode.VIDEO_TOO_LARGE
            else -> null
        }
    }
}
