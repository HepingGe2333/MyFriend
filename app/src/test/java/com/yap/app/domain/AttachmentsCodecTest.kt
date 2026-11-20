package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsCodecTest {

    @Test
    fun roundTripsSingleImage() {
        val items = listOf(
            MessageAttachment(
                uri = "file:///data/user/0/com.yap.app.debug/files/media/a.jpg",
                mime = "image/jpeg",
                width = 1200,
                height = 900
            )
        )

        val encoded = AttachmentsCodec.encode(items)
        // 默认值（image/jpeg）仍不写，但 0.15 起**新写入的附件一律显式写 kind**。
        assertTrue(encoded!!.contains("a.jpg"))
        assertTrue(encoded.contains("1200"))
        val decoded = AttachmentsCodec.decode(encoded)
        assertEquals("普通图片写回时补显式 IMAGE", MessageAttachment.KIND_IMAGE, decoded.single().kind)
        assertEquals(items.single().uri, decoded.single().uri)
        assertEquals(items.single().mime, decoded.single().mime)
    }

    @Test
    fun encodesEmptyListAsNull() {
        assertNull(AttachmentsCodec.encode(emptyList()))
        assertTrue(AttachmentsCodec.decode(null).isEmpty())
        assertTrue(AttachmentsCodec.decode("").isEmpty())
    }

    @Test
    fun dropsBrokenPayloadsInsteadOfCrashing() {
        assertTrue(AttachmentsCodec.decode("不是 JSON").isEmpty())
        assertTrue(AttachmentsCodec.decode("[{\"mime\":\"image/png\"}]").isEmpty())
        assertTrue(AttachmentsCodec.decode("[{\"uri\":\"  \"}]").isEmpty())
    }

    @Test
    fun roundTripsAudioAndVideoWithKindAndDuration() {
        val items = listOf(
            MessageAttachment(
                uri = "file:///data/user/0/com.yap.app/files/audio/voice-1.m4a",
                mime = "audio/m4a",
                kind = MessageAttachment.KIND_AUDIO,
                durationMs = 12_500
            ),
            MessageAttachment(
                uri = "file:///data/user/0/com.yap.app/files/video/video-1.mp4",
                mime = "video/mp4",
                kind = MessageAttachment.KIND_VIDEO,
                durationMs = 37_000
            )
        )
        val encoded = AttachmentsCodec.encode(items)!!
        assertTrue(encoded.contains("AUDIO"))
        assertTrue(encoded.contains("VIDEO"))
        assertTrue(encoded.contains("durationMs"))
        assertEquals(items, AttachmentsCodec.decode(encoded))
    }

    @Test
    fun legacyImageJsonStillDecodesWithoutKind() {
        val decoded = AttachmentsCodec.decode(
            """[{"uri":"file:///tmp/a.jpg","mime":"image/jpeg","width":10,"height":10}]"""
        )
        assertEquals(1, decoded.size)
        assertNull(decoded.first().kind)
        assertNull(decoded.first().durationMs)
        assertTrue(decoded.first().isChatImage)
    }

    @Test
    fun oldStickerKindIsPreservedAndNewImageJsonOmitsNulls() {
        val sticker = MessageAttachment(uri = "file:///stickers/s.gif", mime = "image/gif", kind = "STICKER")
        assertEquals(sticker, AttachmentsCodec.decode(AttachmentsCodec.encode(listOf(sticker))!!).first())
        // 0.15：普通图片也要显式写 kind（读旧数据时 kind=null 仍等价 IMAGE）。
        val plain = MessageAttachment(uri = "file:///tmp/a.jpg")
        val encoded = AttachmentsCodec.encode(listOf(plain))!!
        assertTrue("新写入的普通图片要显式写 IMAGE：$encoded", encoded.contains("\"kind\":\"IMAGE\""))
        assertTrue("没有时长就不写 durationMs", !encoded.contains("durationMs"))
    }

    @Test
    fun kindCaseIsNormalizedAndZeroDurationDropped() {
        val decoded = AttachmentsCodec.decode(
            """[{"uri":"file:///tmp/a.m4a","mime":"audio/m4a","kind":"audio","durationMs":0}]"""
        )
        assertEquals(MessageAttachment.KIND_AUDIO, decoded.first().kind)
        assertNull("0 毫秒等于没有时长", decoded.first().durationMs)
        assertTrue(decoded.first().isAudio)
    }

    @Test
    fun ignoresUnknownFieldsAndDefaultsMime() {
        val decoded = AttachmentsCodec.decode(
            """[{"uri":"file:///tmp/b.png","extra":"ignored"}]"""
        )

        assertEquals(1, decoded.size)
        assertEquals("image/jpeg", decoded.first().mime)
        assertTrue(decoded.first().isImage)
    }
}
