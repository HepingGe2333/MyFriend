package com.yap.app.data.remote

import com.yap.app.data.local.MessageEntity
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.MessageAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.13 历史媒体逐项上传（纯 JVM）。
 *
 * 钉住编排契约：顺序、压缩/上限、失败不阻断、同一文件只传一次、附件改写、进度、可取消。
 */
class ServerHistoryMediaTest {

    private class Upload(val bytes: ByteArray, val mime: String, val kind: String, val personaId: String?)

    private class FakeMediaApi : ServerMediaApi {
        val uploads = mutableListOf<Upload>()
        var failMimes = mutableSetOf<String>()

        /** 第 N 次上传失败（N 从 1 开始）：图片会被重编码成 JPEG，按 mime 判断不可靠。 */
        var failCall: Int = -1
        /** 第 N 次上传时挂起（取消测试用）。 */
        var suspendAt: Int = -1
        val gate = CompletableDeferred<Unit>()

        override suspend fun uploadMedia(
            bytes: ByteArray,
            mime: String,
            kind: String,
            personaId: String?
        ): ServerMedia {
            uploads += Upload(bytes, mime, kind, personaId)
            if (uploads.size == suspendAt) gate.await()
            if (uploads.size == failCall || mime in failMimes) {
                throw ServerApiException("simulated upload failure", code = "server_error")
            }
            val id = "media-${uploads.size}"
            return ServerMedia(id = id, url = "/v1/media/$id", kind = kind, mime = mime, bytes = bytes.size.toLong())
        }
    }

    private val compressor: (ByteArray, String) -> CompressedImage? = { bytes, _ ->
        CompressedImage(("jpeg:" + bytes.size).toByteArray(), "image/jpeg")
    }

    private fun uploader(
        api: FakeMediaApi,
        files: Map<String, ByteArray>,
        compress: (ByteArray, String) -> CompressedImage? = compressor,
        limits: ServerHistoryMediaLimits = ServerHistoryMediaLimits()
    ) = ServerHistoryMediaUploader(
        api = api,
        readBytes = { uri -> files[uri] },
        compressImage = compress,
        limits = limits
    )

    private fun message(
        id: String,
        personaId: String = "p1",
        attachments: List<MessageAttachment> = emptyList(),
        content: String = "content-$id"
    ): MessageEntity = consentMessage(
        id = id,
        sessionId = "s1",
        personaId = personaId,
        attachments = attachments,
        content = content
    )

    private fun image(uri: String, mime: String = "image/jpeg") = MessageAttachment(uri = uri, mime = mime)

    // ------------------------------------------------------------------ 顺序与改写

    @Test
    fun `按消息与附件顺序逐项上传 并把附件改写成服务端引用`() = runBlocking {
        val api = FakeMediaApi()
        val files = mapOf(
            "file:///media/a.jpg" to byteArrayOf(1),
            "file:///media/b.jpg" to byteArrayOf(2),
            "file:///media/c.jpg" to byteArrayOf(3),
            "file:///media/voice.m4a" to byteArrayOf(4),
            "file:///media/clip.mp4" to byteArrayOf(5)
        )
        val messages = listOf(
            message("m1", attachments = listOf(image("file:///media/a.jpg"))),
            message(
                "m2",
                attachments = listOf(
                    image("file:///media/b.jpg"),
                    image("file:///media/c.jpg")
                )
            ),
            message(
                "m3",
                attachments = listOf(
                    MessageAttachment(uri = "file:///media/voice.m4a", mime = "audio/m4a"),
                    MessageAttachment(uri = "file:///media/clip.mp4", mime = "video/mp4")
                )
            )
        )

        val result = uploader(api, files).prepare(messages, personaId = "srv-p1")

        assertEquals(
            "图片→图片→图片→音频→视频（消息/附件原顺序）",
            listOf(
                ServerMediaKinds.CHAT_IMAGE,
                ServerMediaKinds.CHAT_IMAGE,
                ServerMediaKinds.CHAT_IMAGE,
                ServerMediaKinds.AUDIO,
                ServerMediaKinds.VIDEO
            ),
            api.uploads.map { it.kind }
        )
        assertEquals("personaId 传的是服务端 id", "srv-p1", api.uploads.first().personaId)
        assertEquals(5, result.uploaded)
        assertEquals(0, result.failed)
        assertEquals(
            listOf("/v1/media/media-1"),
            AttachmentsCodec.decode(result.messages[0].attachmentsJson).map { it.uri }
        )
        assertEquals(
            listOf("/v1/media/media-2", "/v1/media/media-3"),
            AttachmentsCodec.decode(result.messages[1].attachmentsJson).map { it.uri }
        )
        assertEquals(
            listOf("/v1/media/media-4", "/v1/media/media-5"),
            AttachmentsCodec.decode(result.messages[2].attachmentsJson).map { it.uri }
        )
        assertEquals("正文不动", "content-m1", result.messages[0].content)
    }

    @Test
    fun `图片压缩后再上传 上传字节是压缩后的`() = runBlocking {
        val api = FakeMediaApi()
        val files = mapOf("file:///media/a.jpg" to ByteArray(5_000) { 7 })
        val result = uploader(api, files).prepare(
            listOf(message("m1", attachments = listOf(image("file:///media/a.jpg")))),
            personaId = null
        )

        assertEquals(1, result.uploaded)
        assertEquals("image/jpeg", api.uploads.single().mime)
        assertEquals("压缩器输出（不是原图字节）", "jpeg:5000", String(api.uploads.single().bytes))
    }

    @Test
    fun `历史表情按 STICKER 原样上传 不做 JPEG 重编码`() = runBlocking {
        val api = FakeMediaApi()
        val withKind = "file:///stickers/kind.gif"
        val byMime = "file:///stickers/mime.gif"
        val raw = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        val files = mapOf(withKind to raw, byMime to raw)
        val result = uploader(api, files).prepare(
            listOf(
                message(
                    "m1",
                    attachments = listOf(
                        MessageAttachment(uri = withKind, mime = "image/gif", kind = "STICKER"),
                        // 老数据没有 kind 字段：按 mime=image/gif 同样认作表情。
                        MessageAttachment(uri = byMime, mime = "image/gif")
                    )
                )
            ),
            personaId = null
        )

        assertEquals(2, api.uploads.size)
        assertTrue(api.uploads.all { it.kind == ServerMediaKinds.STICKER })
        assertTrue(api.uploads.all { it.mime == "image/gif" })
        assertTrue("GIF 必须原样上传（重编码会把动画压没）", api.uploads.all { it.bytes.contentEquals(raw) })
        assertEquals(2, result.uploaded)
        assertEquals(0, result.failed)
    }

    // ------------------------------------------------------------------ 失败与不阻断

    @Test
    fun `单项失败计入失败数 不阻断其它消息且失败附件不进服务端副本`() = runBlocking {
        val api = FakeMediaApi()
        api.failCall = 2
        val files = mapOf(
            "file:///media/ok.jpg" to byteArrayOf(1),
            "file:///media/bad.png" to byteArrayOf(2)
            // broken.jpg 故意不在 files 里：读不到
        )
        val messages = listOf(
            message("m1", attachments = listOf(image("file:///media/ok.jpg"))),
            message("m2", attachments = listOf(image("file:///media/bad.png", mime = "image/png"))),
            message("m3", attachments = listOf(image("file:///media/broken.jpg"))),
            message("m4", content = "纯文字")
        )

        val result = uploader(api, files).prepare(messages, personaId = null)

        assertEquals(1, result.uploaded)
        assertEquals(2, result.failed)
        assertEquals("失败项只从服务端副本里去掉", null, result.messages[1].attachmentsJson)
        assertEquals(null, result.messages[2].attachmentsJson)
        assertEquals("纯文字消息原样保留", null, result.messages[3].attachmentsJson)
        assertEquals("4 条消息都要返回（文字照样同步）", 4, result.messages.size)
        assertEquals(2, result.failures.size)
        assertTrue(result.failures.any { it.reason == "media_read_failed" })
        assertTrue(result.failures.any { it.reason == "server_error" })
    }

    @Test
    fun `已经是服务端引用的附件不重复上传也不计失败`() = runBlocking {
        val api = FakeMediaApi()
        val messages = listOf(
            message(
                "m1",
                attachments = listOf(
                    image("/v1/media/old-1"),
                    image("file:///media/new.jpg")
                )
            )
        )
        val files = mapOf("file:///media/new.jpg" to byteArrayOf(9))

        val result = uploader(api, files).prepare(messages, personaId = null)

        assertEquals("只传本地文件那一个", 1, api.uploads.size)
        assertEquals(1, result.uploaded)
        assertEquals(0, result.failed)
        assertEquals(1, result.skipped)
        assertEquals(
            listOf("/v1/media/old-1", "/v1/media/media-1"),
            AttachmentsCodec.decode(result.messages[0].attachmentsJson).map { it.uri }
        )
    }

    @Test
    fun `同一文件被多条消息引用 一次运行只上传一次`() = runBlocking {
        val api = FakeMediaApi()
        val files = mapOf("file:///media/same.jpg" to byteArrayOf(3))
        val messages = listOf(
            message("m1", attachments = listOf(image("file:///media/same.jpg"))),
            message("m2", attachments = listOf(image("file:///media/same.jpg")))
        )

        val result = uploader(api, files).prepare(messages, personaId = null)

        assertEquals(1, api.uploads.size)
        assertEquals(1, result.uploaded)
        assertEquals(1, result.skipped)
        assertEquals(
            "/v1/media/media-1",
            AttachmentsCodec.decode(result.messages[1].attachmentsJson).single().uri
        )
    }

    // ------------------------------------------------------------------ 上限

    @Test
    fun `音频超过 8MB 不上传并计失败`() = runBlocking {
        val api = FakeMediaApi()
        val uri = "file:///media/big.m4a"
        val files = mapOf(uri to ByteArray(8 * 1024 * 1024 + 1))
        val result = uploader(api, files).prepare(
            listOf(message("m1", attachments = listOf(MessageAttachment(uri = uri, mime = "audio/m4a")))),
            personaId = null
        )

        assertTrue(api.uploads.isEmpty())
        assertEquals(1, result.failed)
        assertEquals("media_too_large", result.failures.single().reason)
    }

    @Test
    fun `视频超过 64MB 不上传 8MB 以内直接上传`() = runBlocking {
        val api = FakeMediaApi()
        val small = "file:///media/small.mp4"
        val big = "file:///media/big.mp4"
        val files = mapOf(
            small to ByteArray(1024),
            big to ByteArray(1025)
        )
        val result = uploader(
            api = api,
            files = files,
            limits = ServerHistoryMediaLimits(videoBytes = 1024)
        ).prepare(
            listOf(
                message(
                    "m1",
                    attachments = listOf(
                        MessageAttachment(uri = small, mime = "video/mp4"),
                        MessageAttachment(uri = big, mime = "video/mp4")
                    )
                )
            ),
            personaId = null
        )

        assertEquals(1, api.uploads.size)
        assertEquals(ServerMediaKinds.VIDEO, api.uploads.single().kind)
        assertEquals(1, result.uploaded)
        assertEquals(1, result.failed)
    }

    @Test
    fun `图片压缩后仍超 800KB 计失败且不上传`() = runBlocking {
        val api = FakeMediaApi()
        val files = mapOf("file:///media/huge.jpg" to byteArrayOf(1))
        val result = uploader(
            api = api,
            files = files,
            compress = { _, _ -> CompressedImage(ByteArray(801 * 1024), "image/jpeg") }
        ).prepare(
            listOf(message("m1", attachments = listOf(image("file:///media/huge.jpg")))),
            personaId = null
        )

        assertTrue(api.uploads.isEmpty())
        assertEquals(1, result.failed)
        assertEquals("media_too_large", result.failures.single().reason)
    }

    @Test
    fun `压缩器返回空 计失败`() = runBlocking {
        val api = FakeMediaApi()
        val files = mapOf("file:///media/x.jpg" to byteArrayOf(1))
        val result = uploader(
            api = api,
            files = files,
            compress = { _, _ -> CompressedImage(ByteArray(0), "image/jpeg") }
        ).prepare(
            listOf(message("m1", attachments = listOf(image("file:///media/x.jpg")))),
            personaId = null
        )

        assertTrue(api.uploads.isEmpty())
        assertEquals(1, result.failed)
        assertEquals("media_compress_failed", result.failures.single().reason)
    }

    // ------------------------------------------------------------------ 进度与取消

    @Test
    fun `进度按项推进 最终 processed 等于总项数`() = runBlocking {
        val api = FakeMediaApi()
        val files = mapOf(
            "file:///media/1.jpg" to byteArrayOf(1),
            "file:///media/2.jpg" to byteArrayOf(2)
        )
        val progress = mutableListOf<HistoryMediaProgress>()
        val result = uploader(api, files).prepare(
            messages = listOf(
                message("m1", attachments = listOf(image("file:///media/1.jpg"))),
                message("m2", attachments = listOf(image("file:///media/2.jpg")))
            ),
            personaId = null,
            progress = HistoryMediaProgress(total = 2),
            onProgress = { progress += it }
        )

        assertEquals(2, result.uploaded)
        assertEquals(listOf(1, 2), progress.map { it.processed })
        assertEquals(2, progress.map { it.uploaded }.last())
        assertTrue(progress.last().done)
    }

    @Test
    fun `取消会中断后续上传 已上传的保留`() = runBlocking {
        val api = FakeMediaApi()
        api.suspendAt = 2
        val files = mapOf(
            "file:///media/1.jpg" to byteArrayOf(1),
            "file:///media/2.jpg" to byteArrayOf(2),
            "file:///media/3.jpg" to byteArrayOf(3)
        )
        val messages = listOf(
            message("m1", attachments = listOf(image("file:///media/1.jpg"))),
            message("m2", attachments = listOf(image("file:///media/2.jpg"))),
            message("m3", attachments = listOf(image("file:///media/3.jpg")))
        )
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            uploader(api, files).prepare(messages, personaId = null, onProgress = {
                if (it.processed == 1) started.complete(Unit)
            })
        }
        withTimeout(5_000) { started.await() }
        // 等第二项真的挂在上传里，再取消。
        while (api.uploads.size < 2) {
            kotlinx.coroutines.yield()
        }
        job.cancel()
        job.join()

        assertTrue("取消后不再上传第三项", api.uploads.size <= 2)
        assertTrue(job.isCancelled)
    }
}
