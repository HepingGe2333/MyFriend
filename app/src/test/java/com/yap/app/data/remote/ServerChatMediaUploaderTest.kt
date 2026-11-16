package com.yap.app.data.remote

import com.yap.app.domain.MessageAttachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「带图消息先压缩、再上传」的纯 JVM 编排测试。
 *
 * 用假 [ServerMediaApi] 记录调用顺序与收到的字节，钉住三件事：
 * 1. 顺序必须是 读文件 → 压缩 → 上传（不能把原图直接传上去）；
 * 2. 上传的是**压缩后的字节**，且再做一次 2 MiB 上限检查；
 * 3. 任何一张失败，整条消息失败、不返回半截附件列表。
 */
class ServerChatMediaUploaderTest {

    private class FakeMediaApi : ServerMediaApi {
        val calls = mutableListOf<UploadCall>()
        var nextId = 0
        var failure: Throwable? = null
        var onUpload: () -> Unit = {}

        data class UploadCall(
            val bytes: ByteArray,
            val mime: String,
            val kind: String,
            val personaId: String?
        )

        override suspend fun uploadMedia(
            bytes: ByteArray,
            mime: String,
            kind: String,
            personaId: String?
        ): ServerMedia {
            failure?.let { throw it }
            onUpload()
            calls += UploadCall(bytes, mime, kind, personaId)
            nextId++
            return ServerMedia(
                id = "m$nextId",
                url = "/v1/media/m$nextId",
                mime = mime,
                kind = kind,
                bytes = bytes.size.toLong()
            )
        }
    }

    private val attachment = MessageAttachment(uri = "file:///data/media/chat-a.jpg", mime = "image/jpeg")

    @Test
    fun `顺序是读文件再压缩再上传 上传的是压缩后的字节`() = runBlocking {
        val api = FakeMediaApi()
        val events = mutableListOf<String>()
        api.onUpload = { events += "upload" }
        val uploader = ServerChatMediaUploader(
            api = api,
            compressor = ChatImageCompressor { bytes, _ ->
                events += "compress:${String(bytes)}"
                CompressedImage("compressed".toByteArray(), "image/jpeg")
            },
            readBytes = { uri ->
                events += "read:$uri"
                "original".toByteArray()
            }
        )

        val result = uploader.uploadImages(listOf(attachment), personaId = "p1")

        assertTrue(result.isSuccess)
        assertEquals(listOf("/v1/media/m1"), result.getOrThrow())
        assertEquals(
            listOf(
                "read:file:///data/media/chat-a.jpg",
                "compress:original",
                "upload"
            ),
            events
        )
        val call = api.calls.single()
        assertEquals("compressed", String(call.bytes))
        assertEquals("image/jpeg", call.mime)
        assertEquals(ServerMediaKinds.CHAT_IMAGE, call.kind)
        assertEquals("p1", call.personaId)
    }

    @Test
    fun `压缩后仍超过上传上限时失败且不调用上传`() = runBlocking {
        val api = FakeMediaApi()
        val uploader = ServerChatMediaUploader(
            api = api,
            compressor = { _, _ -> CompressedImage(ByteArray(6), "image/jpeg") },
            readBytes = { _ -> ByteArray(1) },
            maxUploadBytes = 4
        )

        val result = uploader.uploadImages(listOf(attachment), personaId = null)

        assertTrue(result.isFailure)
        assertEquals("media_too_large", (result.exceptionOrNull() as ServerApiException).code)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `读不到附件时明确失败`() = runBlocking {
        val api = FakeMediaApi()
        val uploader = ServerChatMediaUploader(
            api = api,
            compressor = { _, _ -> CompressedImage(byteArrayOf(1), "image/jpeg") },
            readBytes = { null }
        )

        val result = uploader.uploadImages(listOf(attachment), personaId = null)

        assertEquals("media_read_failed", (result.exceptionOrNull() as ServerApiException).code)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `压缩失败时明确失败且不调用上传`() = runBlocking {
        val api = FakeMediaApi()
        val uploader = ServerChatMediaUploader(
            api = api,
            compressor = { _, _ -> null },
            readBytes = { byteArrayOf(1) }
        )

        val result = uploader.uploadImages(listOf(attachment), personaId = null)

        assertEquals("media_compress_failed", (result.exceptionOrNull() as ServerApiException).code)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `多张图按顺序全部上传 任何一张失败就不返回半截列表`() = runBlocking {
        val first = MessageAttachment(uri = "file:///data/media/chat-1.jpg")
        val second = MessageAttachment(uri = "file:///data/media/chat-2.jpg")
        val api = FakeMediaApi()
        val uploader = ServerChatMediaUploader(
            api = api,
            compressor = { bytes, _ -> CompressedImage(bytes, "image/jpeg") },
            readBytes = { uri -> uri.substringAfterLast('-').toByteArray() }
        )

        val ok = uploader.uploadImages(listOf(first, second), personaId = null)
        assertTrue(ok.isSuccess)
        assertEquals(listOf("/v1/media/m1", "/v1/media/m2"), ok.getOrThrow())

        val failingApi = FakeMediaApi().apply { failure = ServerApiException("boom", code = "io_error") }
        val failing = ServerChatMediaUploader(
            api = failingApi,
            compressor = { bytes, _ -> CompressedImage(bytes, "image/jpeg") },
            readBytes = { uri -> uri.substringAfterLast('-').toByteArray() }
        ).uploadImages(listOf(first, second), personaId = null)

        assertTrue(failing.isFailure)
        assertEquals("io_error", (failing.exceptionOrNull() as ServerApiException).code)
        assertFalse("失败时不能返回半截结果", failing.isSuccess)
    }
}
