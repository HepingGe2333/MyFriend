package com.yap.app.data.remote

import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.MessageAttachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音/视频上传编排（`spec/audio-video/README.md`「附件」）。
 *
 * 纯 JVM：`ServerMediaApi` 与读文件都注入假实现。钉住三件事——
 * kind 正确（AUDIO/VIDEO）、超限在**发出去之前**就拒绝、失败不返回半截附件。
 */
class ServerAudioVideoUploaderTest {

    private class FakeApi : ServerMediaApi {
        val uploads = mutableListOf<Triple<ByteArray, String, String>>()
        var nextId = 1
        var blankId = false

        override suspend fun uploadMedia(
            bytes: ByteArray,
            mime: String,
            kind: String,
            personaId: String?
        ): ServerMedia {
            uploads += Triple(bytes, mime, kind)
            val id = if (blankId) "" else "media-${nextId++}"
            return ServerMedia(id = id, url = "/v1/media/$id", kind = kind, mime = mime)
        }
    }

    private fun audioAttachment(uri: String = "file:///audio/voice-1.m4a", durationMs: Long? = 12_000) =
        MessageAttachment(
            uri = uri,
            mime = "audio/m4a",
            kind = MessageAttachment.KIND_AUDIO,
            durationMs = durationMs
        )

    private fun videoAttachment(uri: String = "file:///video/video-1.mp4", durationMs: Long? = 30_000) =
        MessageAttachment(
            uri = uri,
            mime = "video/mp4",
            kind = MessageAttachment.KIND_VIDEO,
            durationMs = durationMs
        )

    @Test
    fun uploadsAudioWithCorrectKindAndBytes() = runBlocking {
        val api = FakeApi()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val uploader = ServerAudioVideoUploader(api) { bytes }
        val result = uploader.upload(listOf(audioAttachment()), personaId = "p1")

        assertEquals(listOf("/v1/media/media-1"), result.getOrThrow())
        assertEquals(1, api.uploads.size)
        val (sent, mime, kind) = api.uploads.first()
        assertTrue(bytes.contentEquals(sent))
        assertEquals("audio/m4a", mime)
        assertEquals(ServerMediaKinds.AUDIO, kind)
    }

    @Test
    fun uploadsVideoWithCorrectKind() = runBlocking {
        val api = FakeApi()
        val uploader = ServerAudioVideoUploader(api) { byteArrayOf(7) }
        val result = uploader.upload(listOf(videoAttachment()), personaId = null)

        assertEquals(listOf("/v1/media/media-1"), result.getOrThrow())
        assertEquals(ServerMediaKinds.VIDEO, api.uploads.first().third)
    }

    @Test
    fun preservesAttachmentOrder() = runBlocking {
        val api = FakeApi()
        val uploader = ServerAudioVideoUploader(api) { attachment ->
            if (attachment.isAudio) byteArrayOf(1) else byteArrayOf(2)
        }
        val result = uploader.upload(
            listOf(audioAttachment(), videoAttachment()),
            personaId = null
        )
        assertEquals(listOf("/v1/media/media-1", "/v1/media/media-2"), result.getOrThrow())
        assertEquals(ServerMediaKinds.AUDIO, api.uploads[0].third)
        assertEquals(ServerMediaKinds.VIDEO, api.uploads[1].third)
    }

    @Test
    fun rejectsAudioOverEightMegabytesBeforeUploading() = runBlocking {
        val api = FakeApi()
        val uploader = ServerAudioVideoUploader(api) {
            ByteArray((AudioVideoPolicy.AUDIO_MAX_BYTES + 1).toInt())
        }
        val result = uploader.upload(listOf(audioAttachment()), personaId = null)
        assertTrue(result.isFailure)
        assertEquals("media_too_large", (result.exceptionOrNull() as ServerApiException).code)
        assertTrue("超限的请求不该发出去", api.uploads.isEmpty())
    }

    @Test
    fun rejectsVideoOverTwoMinutes() = runBlocking {
        val api = FakeApi()
        val uploader = ServerAudioVideoUploader(api) { byteArrayOf(1) }
        val result = uploader.upload(
            listOf(videoAttachment(durationMs = AudioVideoPolicy.VIDEO_MAX_DURATION_MS + 1)),
            personaId = null
        )
        assertTrue(result.isFailure)
        assertEquals("media_too_long", (result.exceptionOrNull() as ServerApiException).code)
        assertTrue(api.uploads.isEmpty())
    }

    @Test
    fun rejectsUnreadableAttachment() = runBlocking {
        val api = FakeApi()
        val uploader = ServerAudioVideoUploader(api) { null }
        val result = uploader.upload(listOf(audioAttachment()), personaId = null)
        assertTrue(result.isFailure)
        assertEquals("media_read_failed", (result.exceptionOrNull() as ServerApiException).code)
    }

    @Test
    fun emptyBytesAreRejected() = runBlocking {
        val api = FakeApi()
        val uploader = ServerAudioVideoUploader(api) { ByteArray(0) }
        val result = uploader.upload(listOf(videoAttachment()), personaId = null)
        assertTrue(result.isFailure)
        assertEquals("media_empty", (result.exceptionOrNull() as ServerApiException).code)
    }

    @Test
    fun serverEntryWithoutIdIsInvalidResponse() = runBlocking {
        val api = FakeApi().apply { blankId = true }
        val uploader = ServerAudioVideoUploader(api) { byteArrayOf(1) }
        val result = uploader.upload(listOf(audioAttachment()), personaId = null)
        assertTrue(result.isFailure)
        assertEquals("invalid_response", (result.exceptionOrNull() as ServerApiException).code)
    }

    @Test
    fun attachmentsThatAreNotAudioOrVideoAreIgnored() = runBlocking {
        val api = FakeApi()
        val uploader = ServerAudioVideoUploader(api) { error("不该读图片") }
        val result = uploader.upload(
            listOf(MessageAttachment(uri = "file:///media/chat-1.jpg", mime = "image/jpeg")),
            personaId = null
        )
        assertTrue(result.getOrThrow().isEmpty())
        assertTrue(api.uploads.isEmpty())
    }

    @Test
    fun emptyListShortCircuits() = runBlocking {
        val api = FakeApi()
        val uploader = ServerAudioVideoUploader(api) { error("不该被调用") }
        assertTrue(uploader.upload(emptyList(), personaId = null).getOrThrow().isEmpty())
    }
}
