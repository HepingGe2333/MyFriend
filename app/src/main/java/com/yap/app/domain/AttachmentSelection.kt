package com.yap.app.domain

/**
 * 一条消息实际能带哪些附件（本地模式与服务端模式共用同一套规则）。
 *
 * 规则（`spec/audio-video/README.md`）：
 * - 图片最多 [maxImages] 张（与 `ImageContextBudget` 的预算一致）；
 * - 语音、视频各最多一段：一条消息里带两段语音没有意义，只会把请求撑大；
 * - 顺序保持用户选择的顺序（气泡里「先语音后视频」看得见）。
 *
 * 为什么单独抽出来：这是「发送」这条路径的唯一判据，本地写库与服务端上传如果各写一遍，
 * 迟早会出现「本机显示两段语音、服务端只收到一段」这种只在一边发生的错。
 */
object AttachmentSelection {

    fun select(
        attachments: List<MessageAttachment>,
        maxImages: Int = ImageContextBudget.MAX_IMAGES_PER_MESSAGE
    ): List<MessageAttachment> {
        if (attachments.isEmpty()) return emptyList()
        var images = 0
        var audios = 0
        var videos = 0
        return attachments.filter { attachment ->
            when {
                attachment.isAudio -> audios++ == 0
                attachment.isVideo -> videos++ == 0
                attachment.isImage -> images++ < maxImages
                else -> false
            }
        }
    }

    /** 一条消息是否「只有媒体、没有正文」——发送时正文可以为空，但不能两者都空。 */
    fun isMediaOnly(content: String, attachments: List<MessageAttachment>): Boolean =
        content.isBlank() && attachments.any { it.isAudioOrVideo || it.isChatImage || it.isSticker }
}
