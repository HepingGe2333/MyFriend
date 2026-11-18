package com.yap.app.domain

/**
 * `listen` / `watch` 两个工具的结果构造。
 *
 * 单独放在 domain 里，是为了让「工具到底回灌了什么」可以脱离 Android/Room 单测：
 * 摘要用「我听了一遍 · 12 秒」/「我看了 5 帧 · 37 秒」，正文里带一句说明，
 * 真正的多模态内容挂在 [ToolResult.media] 上（语音 base64 / 视频帧 data URL）。
 *
 * 失败也分得清：**没有媒体**（对方还没发过）与**读不出来/解不出来**（文件坏了、服务端下载失败）
 * 是两种不同的话，模型据此决定是「请对方再发一次」还是「老实说我这边打不开」。
 */
object MediaTool {

    /** `listen(audioId?)` */
    fun listening(invocation: ToolInvocation, part: AudioClipPart, durationMs: Long?): ToolResult {
        val seconds = AudioVideoPolicy.durationSeconds(durationMs ?: part.durationMs)
        return ToolResult(
            invocation = invocation,
            ok = true,
            summary = AudioVideoText.listenSummary(durationMs ?: part.durationMs),
            contentForModel = "你听了一遍这段 ${seconds} 秒的语音，声音本身就在下面这条消息里。" +
                "按你听到的内容回应（语气、情绪、背景声都算），不要假装听到了没听到的东西。",
            media = ToolMedia(
                audios = listOf(part),
                caption = AudioVideoText.listenContent(durationMs ?: part.durationMs)
            )
        )
    }

    /** `watch(videoId?)` */
    fun watching(
        invocation: ToolInvocation,
        frames: List<String>,
        durationMs: Long?
    ): ToolResult {
        val caption = VideoFramePlan.caption(frames.size, durationMs ?: 0L)
        return ToolResult(
            invocation = invocation,
            ok = true,
            summary = AudioVideoText.watchSummary(frames.size, durationMs),
            contentForModel = "你看了这段视频的 ${frames.size} 个画面（$caption）。画面就在下面这条消息里，" +
                "按你看到的回应；没看清的地方不要编。",
            media = ToolMedia(
                images = frames,
                caption = AudioVideoText.watchContent(frames.size, durationMs)
            )
        )
    }

    /** 这段聊天里根本没有对应媒体：不伪造内容，也不报「工具坏了」。 */
    fun missing(invocation: ToolInvocation, kind: AudioVideoKind): ToolResult = ToolResult(
        invocation = invocation,
        ok = false,
        summary = if (kind == AudioVideoKind.AUDIO) "这段聊天里没有语音" else "这段聊天里没有视频",
        contentForModel = AudioVideoText.noMedia(kind),
        errorMessage = AudioVideoText.noMedia(kind)
    )

    /** 有媒体，但这台机器读不出来（文件删了 / 服务端下载失败 / 视频解不出帧）。 */
    fun unreadable(invocation: ToolInvocation, kind: AudioVideoKind): ToolResult = ToolResult(
        invocation = invocation,
        ok = false,
        summary = if (kind == AudioVideoKind.AUDIO) "这段语音我这边打不开" else "这段视频我这边打不开",
        contentForModel = if (kind == AudioVideoKind.AUDIO) {
            "这段语音在你这台设备上打不开（文件可能已经不在了）。老实说你这边听不到，请对方再发一次。"
        } else {
            "这段视频在你这台设备上打不开（文件可能已经不在了）。老实说你这边看不到，请对方再发一次。"
        },
        errorMessage = if (kind == AudioVideoKind.AUDIO) "音频读不出来" else "视频读不出来"
    )

    /** 工具参数里的媒体 id（两种工具的参数名不同，解析规则一样）。 */
    fun requestedId(invocation: ToolInvocation): String? =
        invocation.argument("audioId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: invocation.argument("videoId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: invocation.argument("mediaId")?.trim()?.takeIf { it.isNotEmpty() }
}
