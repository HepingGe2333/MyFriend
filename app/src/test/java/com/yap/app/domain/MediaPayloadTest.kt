package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 多模态 payload 的编码（`spec/audio-video/README.md`「模型侧」）。
 *
 * 三个不能错的点：`input_audio` 的 base64 是**裸编码**（不是 data URL）、`format` 是
 * provider 的短名（m4a/mp3）、超过 8 MiB 的语音直接拒绝而不是发一个注定 413 的请求。
 */
class MediaPayloadTest {

    @Test
    fun audioPartEncodesBase64AndFormat() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val part = MediaPayload.audioPart(bytes, "audio/m4a", durationMs = 3_000)
        assertNotNull(part)
        assertEquals("m4a", part!!.format)
        assertEquals(3_000L, part.durationMs)
        assertEquals("裸 base64（不带 data: 前缀）", Base64.getEncoder().encodeToString(bytes), part.base64)
        assertTrue(!part.base64.startsWith("data:"))
        // 往返：解回来与原始字节一致
        assertTrue(bytes.contentEquals(Base64.getDecoder().decode(part.base64)))
    }

    @Test
    fun audioPartMapsMp3Format() {
        val part = MediaPayload.audioPart(byteArrayOf(9), "audio/mpeg")
        assertEquals("mp3", part!!.format)
    }

    @Test
    fun audioPartRejectsTooLargeEmptyAndWrongMime() {
        assertNull(MediaPayload.audioPart(ByteArray(0), "audio/m4a"))
        val tooLarge = ByteArray((AudioVideoPolicy.AUDIO_MAX_BYTES + 1).toInt())
        assertNull(">8MB 必须拒绝", MediaPayload.audioPart(tooLarge, "audio/m4a"))
        assertNull(MediaPayload.audioPart(byteArrayOf(1), "video/mp4"))
        assertNull(MediaPayload.audioPart(byteArrayOf(1), "application/octet-stream"))
    }

    @Test
    fun frameDataUrlLooksLikeImageUrl() {
        val url = MediaPayload.frameDataUrl(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        assertEquals("data:image/jpeg;base64,/9g=", url)
        assertNull(MediaPayload.frameDataUrl(ByteArray(0)))
    }

    @Test
    fun frameDataUrlsCapsAtEightFrames() {
        val frames = (1..12).map { ByteArray(10) { it.toByte() } }
        val urls = MediaPayload.frameDataUrls(frames)
        assertEquals(8, urls.size)
        urls.forEach { assertTrue(it.startsWith("data:image/jpeg;base64,")) }
    }

    @Test
    fun frameDataUrlsStopsWhenTotalBudgetIsExceeded() {
        // 每帧 1 MiB：正好 8 帧 = 8 MiB；第 9 帧开始超预算
        val frame = ByteArray(AudioVideoPolicy.FRAME_MAX_BYTES)
        val urls = MediaPayload.frameDataUrls(List(10) { frame })
        assertEquals(8, urls.size)
    }

    @Test
    fun frameDataUrlsSkipsOversizedFrames() {
        val tooBig = ByteArray(AudioVideoPolicy.FRAME_MAX_BYTES + 1)
        val ok = ByteArray(16)
        val urls = MediaPayload.frameDataUrls(listOf(tooBig, ok))
        assertEquals(1, urls.size)
    }

    @Test
    fun frameDataUrlsSkipsEmptyFrames() {
        assertTrue(MediaPayload.frameDataUrls(emptyList()).isEmpty())
        assertTrue(MediaPayload.frameDataUrls(listOf(ByteArray(0))).isEmpty())
    }

    @Test
    fun base64OfStripsTheDataUrlPrefix() {
        val bytes = byteArrayOf(1, 2, 3)
        val url = MediaPayload.frameDataUrl(bytes)!!
        assertEquals(Base64.getEncoder().encodeToString(bytes), MediaPayload.base64Of(url))
        assertEquals("abc", MediaPayload.base64Of("abc"))
    }

    @Test
    fun currentMediaBlockListsMediaIdsAndHints() {
        val block = AudioVideoText.currentBlock(
            listOf(
                CurrentMedia("av:m1:0", AudioVideoKind.AUDIO, 12_000),
                CurrentMedia("av:m1:1", AudioVideoKind.VIDEO, 37_000)
            )
        )
        assertTrue(block.contains("mediaId=av:m1:0"))
        assertTrue(block.contains("语音"))
        assertTrue(block.contains("listen"))
        assertTrue(block.contains("mediaId=av:m1:1"))
        assertTrue(block.contains("视频"))
        assertTrue(block.contains("watch"))
        assertEquals("", AudioVideoText.currentBlock(emptyList()))
    }

    @Test
    fun placeholdersSayWhatCameIn() {
        assertEquals("（对方发来一段 12 秒的语音）", AudioVideoText.audioPlaceholder(12_000))
        assertEquals("（对方发来一段 37 秒的视频）", AudioVideoText.videoPlaceholder(37_000))
    }

    @Test
    fun mediaIdsRoundTripAndToleratePlainIds() {
        val encoded = MediaIds.encode("msg-1", 1)
        assertEquals("av:msg-1:1", encoded)
        assertEquals(MediaRefId("msg-1", 1), MediaIds.decode(encoded))
        assertEquals(MediaRefId("msg-1", 0), MediaIds.decode("msg-1"))
        assertEquals(MediaRefId("msg-1", 0), MediaIds.decode("av:msg-1"))
        assertNull(MediaIds.decode(""))
        assertNull(MediaIds.decode("   "))
        // 负序号夹到 0
        assertEquals(MediaRefId("msg-1", 0), MediaIds.decode("av:msg-1:-4"))
    }

    @Test
    fun errorTokensRoundTrip() {
        assertEquals("@yap-media:audio", MediaErrorTokens.encode(AudioVideoKind.AUDIO))
        assertEquals("@yap-media:video", MediaErrorTokens.encode(AudioVideoKind.VIDEO))
        assertEquals(MediaErrorCode.UNSUPPORTED_AUDIO, MediaErrorTokens.decode("@yap-media:audio"))
        assertEquals(MediaErrorCode.UNSUPPORTED_VIDEO, MediaErrorTokens.decode("@yap-media:video"))
        assertEquals(MediaErrorCode.AUDIO_TOO_LARGE, MediaErrorTokens.decode(MediaErrorTokens.encodeTooLarge(AudioVideoKind.AUDIO)))
        assertEquals(MediaErrorCode.VIDEO_TOO_LARGE, MediaErrorTokens.decode(MediaErrorTokens.encodeTooLarge(AudioVideoKind.VIDEO)))
        assertNull(MediaErrorTokens.decode("@mf-err:unknown"))
        assertNull(MediaErrorTokens.decode("普通中文错误"))
    }
}
