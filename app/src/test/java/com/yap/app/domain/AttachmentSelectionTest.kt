package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一条消息能带哪些附件（本地/服务端共用的判据）。
 *
 * 这些用例对应「发送」这条路径的实际分支：只有语音没有正文是合法的一条消息；
 * 两段语音不会都发出去；图片张数仍受原有上限约束。
 */
class AttachmentSelectionTest {

    private fun image(index: Int) = MessageAttachment(uri = "file:///media/chat-$index.jpg")
    private fun audio(index: Int) = MessageAttachment(
        uri = "file:///audio/voice-$index.m4a",
        mime = "audio/m4a",
        kind = MessageAttachment.KIND_AUDIO,
        durationMs = 3_000
    )
    private fun video(index: Int) = MessageAttachment(
        uri = "file:///video/video-$index.mp4",
        mime = "video/mp4",
        kind = MessageAttachment.KIND_VIDEO,
        durationMs = 20_000
    )

    @Test
    fun keepsOneAudioAndOneVideoAndPreservesOrder() {
        val selected = AttachmentSelection.select(
            listOf(audio(1), image(1), video(1))
        )
        assertEquals(3, selected.size)
        assertTrue(selected[0].isAudio)
        assertTrue(selected[1].isImage)
        assertTrue(selected[2].isVideo)
    }

    @Test
    fun dropsSecondAudioAndSecondVideo() {
        val selected = AttachmentSelection.select(
            listOf(audio(1), audio(2), video(1), video(2))
        )
        assertEquals(2, selected.size)
        assertEquals(listOf("file:///audio/voice-1.m4a", "file:///video/video-1.mp4"), selected.map { it.uri })
    }

    @Test
    fun capsImagesAtTheExistingLimit() {
        val selected = AttachmentSelection.select(List(6) { image(it) }, maxImages = 4)
        assertEquals(4, selected.size)
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertTrue(AttachmentSelection.select(emptyList()).isEmpty())
    }

    @Test
    fun mediaOnlyMessageIsLegalWithoutText() {
        assertTrue(AttachmentSelection.isMediaOnly("", listOf(audio(1))))
        assertTrue(AttachmentSelection.isMediaOnly("   ", listOf(video(1))))
        assertTrue(AttachmentSelection.isMediaOnly("", listOf(image(1))))
        assertFalse("纯文本不算媒体消息", AttachmentSelection.isMediaOnly("在吗", emptyList()))
        assertFalse("两者都空不是一条合法消息", AttachmentSelection.isMediaOnly("", emptyList()))
        assertFalse("有正文就不是「只有媒体」", AttachmentSelection.isMediaOnly("在吗", listOf(audio(1))))
    }
}
