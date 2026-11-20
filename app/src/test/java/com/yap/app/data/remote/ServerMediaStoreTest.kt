package com.yap.app.data.remote

import com.yap.app.domain.MessageAttachment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files

/**
 * 服务端媒体落盘（0.13）的纯 JVM 单测。
 *
 * 用临时目录 + 注入下载器钉住最容易在真机上才暴露的几件事：
 * 1. 字节 magic 嗅探（服务端不给 mime，`/v1/media/<id>` 只有字节）；
 * 2. 用途 → 目录 / 文件名 / 扩展名（清理路径依赖前缀）；
 * 3. 幂等：文件已在就不下载；同一 id 一批里只下一次；
 * 4. 失败/超限只返回「保留服务端引用」的结果，绝不抛；
 * 5. 既有的语音/视频/表情元数据优先于 magic（否则一条语音会被当图片）。
 */
class ServerMediaStoreTest {

    private val root: File = Files.createTempDirectory("server-media-store").toFile()
    private val directories = ServerMediaDirectories(
        chatImages = File(root, "media").apply { mkdirs() },
        avatars = File(root, "avatars").apply { mkdirs() },
        stickers = File(root, "stickers").apply { mkdirs() },
        audio = File(root, "audio").apply { mkdirs() },
        video = File(root, "video").apply { mkdirs() }
    )

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ magic 嗅探

    @Test
    fun `magic 嗅探认得出常见容器`() {
        assertEquals("image/jpeg", ServerMediaSniffer.mimeOf(jpegBytes()))
        assertEquals("image/png", ServerMediaSniffer.mimeOf(pngBytes()))
        assertEquals("image/gif", ServerMediaSniffer.mimeOf(gifBytes()))
        assertEquals("audio/m4a", ServerMediaSniffer.mimeOf(ftypBytes("M4A ")))
        assertEquals("audio/m4a", ServerMediaSniffer.mimeOf(ftypBytes("isom", "soun")))
        assertEquals("video/quicktime", ServerMediaSniffer.mimeOf(ftypBytes("qt  ")))
        assertEquals("video/mp4", ServerMediaSniffer.mimeOf(ftypBytes("isom", "vide")))
        assertEquals("audio/mpeg", ServerMediaSniffer.mimeOf("ID3\u0004".toByteArray(Charsets.US_ASCII) + ByteArray(16)))
        // 录音/录像常把 moov 放在文件尾：超过头部扫描窗口时，尾部也要能认出 track 类型。
        val moovAtTail = ByteArray(3 * 1024 * 1024)
        assertEquals(
            "audio/m4a",
            ServerMediaSniffer.mimeOf(
                ftypBytes("isom") + moovAtTail + "soun".toByteArray(Charsets.US_ASCII)
            )
        )
        assertEquals(
            "video/mp4",
            ServerMediaSniffer.mimeOf(
                ftypBytes("isom") + moovAtTail + "vide".toByteArray(Charsets.US_ASCII)
            )
        )
        assertNull("认不出来的字节返回 null（0.15 起调用方拒绝，不再按普通图片兜底）", ServerMediaSniffer.mimeOf(ByteArray(4)))
        assertNull(ServerMediaSniffer.mimeOf(null))
    }

    @Test
    fun `用途与目录 文件名 扩展名一一对应`() {
        assertEquals("chat-", ServerMediaKind.filePrefix(ServerMediaKind.CHAT_IMAGE))
        assertEquals("sticker-", ServerMediaKind.filePrefix(ServerMediaKind.STICKER))
        assertEquals("voice-", ServerMediaKind.filePrefix(ServerMediaKind.AUDIO))
        assertEquals("video-", ServerMediaKind.filePrefix(ServerMediaKind.VIDEO))
        assertEquals("avatar-", ServerMediaKind.filePrefix(ServerMediaKind.PERSONA_AVATAR))

        assertEquals("jpg", ServerMediaKind.extensionOf(ServerMediaKind.CHAT_IMAGE, "image/jpeg"))
        assertEquals("gif", ServerMediaKind.extensionOf(ServerMediaKind.STICKER, "image/gif"))
        assertEquals("m4a", ServerMediaKind.extensionOf(ServerMediaKind.AUDIO, "audio/m4a"))
        assertEquals("mp3", ServerMediaKind.extensionOf(ServerMediaKind.AUDIO, "audio/mpeg"))
        assertEquals("mov", ServerMediaKind.extensionOf(ServerMediaKind.VIDEO, "video/quicktime"))

        val target = ServerMediaKind.targetFor(
            id = "abc",
            kind = ServerMediaKind.AUDIO,
            mime = "audio/m4a",
            directories = directories
        )
        assertNotNull(target)
        assertEquals(File(root, "audio"), target!!.directory)
        assertEquals("voice-abc.m4a", target.fileName)
        assertEquals(File(root, "audio/voice-abc.m4a"), target.file)
    }

    @Test
    fun `下载上限按用途取常量`() {
        assertEquals(ServerMediaLimits.MAX_UPLOAD_BYTES, ServerMediaKind.maxBytes(ServerMediaKind.CHAT_IMAGE))
        assertEquals(ServerMediaLimits.MAX_AUDIO_BYTES, ServerMediaKind.maxBytes(ServerMediaKind.AUDIO))
        assertEquals(ServerMediaLimits.MAX_VIDEO_BYTES, ServerMediaKind.maxBytes(ServerMediaKind.VIDEO))
        assertEquals(
            "用途未知时按最大上限一次取回",
            ServerMediaLimits.MAX_VIDEO_BYTES,
            ServerMediaKind.maxBytes(null)
        )
    }

    // ------------------------------------------------------------------ 落盘

    @Test
    fun `下载成功落到持久目录并返回本地 uri 与 serverMediaId`() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        val materializer = ServerMediaMaterializer(directories) { ref, maxBytes ->
            calls += ref to maxBytes
            ServerMediaDownload(jpegBytes())
        }

        val result = materializer.materialize(listOf(ServerAttachmentRef("/v1/media/abc")))

        assertEquals(1, result.downloaded)
        assertEquals(0, result.failed)
        assertEquals(0, result.skipped)
        assertEquals(1, calls.size)
        assertEquals(ServerMediaLimits.MAX_VIDEO_BYTES, calls.single().second)

        val media = result.attachmentFor("/v1/media/abc")
        assertNotNull(media)
        assertEquals("abc", media!!.serverMediaId)
        assertEquals("image/jpeg", media.mime)
        assertTrue("本地路径必须是 file://：${media.uri}", media.uri.startsWith("file://"))
        assertTrue(media.local)
        val file = File(URI(media.uri))
        assertTrue(file.isFile)
        assertTrue(file.length() > 0L)
        assertEquals("chat-abc.jpg", file.name)
        assertEquals(File(root, "media"), file.parentFile)
    }

    @Test
    fun `幂等 目标文件已存在就不再调用下载器`() = runBlocking {
        val first = ServerMediaMaterializer(directories) { _, _ -> ServerMediaDownload(jpegBytes()) }
            .materialize(listOf(ServerAttachmentRef("/v1/media/once")))
        val firstUri = first.attachmentFor("/v1/media/once")!!.uri

        var downloads = 0
        val second = ServerMediaMaterializer(directories) { _, _ ->
            downloads++
            null
        }.materialize(listOf(ServerAttachmentRef("/v1/media/once")))

        assertEquals("第二次不该再问服务端要字节", 0, downloads)
        assertEquals(1, second.skipped)
        assertEquals(0, second.downloaded)
        assertEquals(0, second.failed)
        assertEquals(firstUri, second.attachmentFor("/v1/media/once")!!.uri)
    }

    @Test
    fun `同一批里同 id 只下一次且只计一次`() = runBlocking {
        var downloads = 0
        val materializer = ServerMediaMaterializer(directories) { _, _ ->
            downloads++
            ServerMediaDownload(jpegBytes())
        }

        val result = materializer.materialize(
            listOf(
                ServerAttachmentRef("/v1/media/dup"),
                ServerAttachmentRef("/v1/media/dup"),
                ServerAttachmentRef("dup")
            )
        )

        assertEquals(1, downloads)
        assertEquals(1, result.downloaded)
        assertEquals(1, result.media.size)
        assertEquals(0, result.failed)
    }

    @Test
    fun `下载失败保留服务端引用并计数`() = runBlocking {
        val materializer = ServerMediaMaterializer(directories) { _, _ -> null }
        val result = materializer.materialize(listOf(ServerAttachmentRef("/v1/media/gone")))

        assertEquals(1, result.failed)
        assertEquals(0, result.downloaded)
        val media = result.attachmentFor("/v1/media/gone")!!
        assertEquals("/v1/media/gone", media.uri)
        assertEquals("gone", media.serverMediaId)
        assertFalse(media.local)
        assertTrue("失败不该落任何文件", directories.all().all { dir -> dir.listFiles().isNullOrEmpty() })
    }

    @Test
    fun `下载器抛异常也不外抛 只计失败`() = runBlocking {
        val materializer = ServerMediaMaterializer(directories) { _, _ ->
            error("network is down")
        }
        val result = materializer.materialize(listOf(ServerAttachmentRef("/v1/media/boom")))

        assertEquals(1, result.failed)
        assertEquals("/v1/media/boom", result.attachmentFor("/v1/media/boom")!!.uri)
    }

    @Test
    fun `已知是语音但下载失败时仍保留语音类型`() = runBlocking {
        val existing = MessageAttachment(
            uri = "/v1/media/v9",
            mime = "audio/m4a",
            kind = MessageAttachment.KIND_AUDIO
        )
        val materializer = ServerMediaMaterializer(directories) { _, _ -> null }
        val media = materializer
            .materialize(listOf(ServerAttachmentRef("/v1/media/v9", existing = existing)))
            .attachmentFor("/v1/media/v9")!!

        assertEquals("占位仍保留语音类型（界面该画语音占位）", MessageAttachment.KIND_AUDIO, media.kind)
        assertEquals("audio/m4a", media.mime)
        assertEquals("/v1/media/v9", media.uri)
    }

    @Test
    fun `超过真实用途上限的字节不落盘`() = runBlocking {
        // 用途未知 → 按 64 MiB 取回；嗅探出是图片后按 2 MiB 复检，3 MiB 的“图片”必须拒绝。
        val oversized = jpegBytes() + ByteArray(3 * 1024 * 1024)
        val materializer = ServerMediaMaterializer(directories) { _, _ -> ServerMediaDownload(oversized) }
        val result = materializer.materialize(listOf(ServerAttachmentRef("/v1/media/big")))

        assertEquals(1, result.failed)
        assertTrue(directories.chatImages.listFiles().isNullOrEmpty())
    }

    @Test
    fun `既有语音元数据优先 magic 落到 audio 目录并保留 mime 与 kind`() = runBlocking {
        val existing = MessageAttachment(
            uri = "/v1/media/voice1",
            mime = "audio/m4a",
            kind = MessageAttachment.KIND_AUDIO,
            durationMs = 3_000
        )
        // 字节是 mp4 容器（m4a 也是 ftyp），没有元数据时会退化成视频；有元数据就按语音落。
        val materializer = ServerMediaMaterializer(directories) { _, _ -> ServerMediaDownload(ftypBytes("isom", "vide")) }
        val result = materializer.materialize(
            listOf(ServerAttachmentRef("/v1/media/voice1", existing = existing))
        )

        val media = result.attachmentFor("/v1/media/voice1")!!
        assertTrue(media.local)
        assertEquals("audio/m4a", media.mime)
        assertEquals(MessageAttachment.KIND_AUDIO, media.kind)
        assertEquals(File(root, "audio/voice-voice1.m4a"), File(URI(media.uri)))
    }

    @Test
    fun `默认图片元数据不挡 magic 的音视频判定`() = runBlocking {
        // 上一轮同步留下的默认 image/jpeg（只是缺省值，不是证据）。
        val existing = MessageAttachment(uri = "/v1/media/vid1", mime = "image/jpeg")
        val materializer = ServerMediaMaterializer(directories) { _, _ -> ServerMediaDownload(ftypBytes("M4A ")) }
        val result = materializer.materialize(
            listOf(ServerAttachmentRef("/v1/media/vid1", existing = existing))
        )

        val media = result.attachmentFor("/v1/media/vid1")!!
        assertEquals(MessageAttachment.KIND_AUDIO, media.kind)
        assertEquals(File(root, "audio/voice-vid1.m4a"), File(URI(media.uri)))
    }

    @Test
    fun `GIF 按表情落 stickers 目录`() = runBlocking {
        val materializer = ServerMediaMaterializer(directories) { _, _ -> ServerMediaDownload(gifBytes()) }
        val result = materializer.materialize(listOf(ServerAttachmentRef("/v1/media/st1")))

        val media = result.attachmentFor("/v1/media/st1")!!
        assertEquals(MessageAttachment.KIND_STICKER, media.kind)
        assertEquals(File(root, "stickers/sticker-st1.gif"), File(URI(media.uri)))
    }

    @Test
    fun `头像用途强制落 avatars 目录 且按图片上限下载`() = runBlocking {
        var limit = -1
        val materializer = ServerMediaMaterializer(directories) { _, maxBytes ->
            limit = maxBytes
            ServerMediaDownload(jpegBytes())
        }
        val result = materializer.materialize(
            listOf(
                ServerAttachmentRef(
                    serverRef = "/v1/media/av1",
                    personaId = "p1",
                    kind = ServerMediaKind.PERSONA_AVATAR
                )
            )
        )

        val media = result.attachmentFor("/v1/media/av1")!!
        assertEquals(ServerMediaLimits.MAX_UPLOAD_BYTES, limit)
        assertEquals(File(root, "avatars/avatar-av1.jpg"), File(URI(media.uri)))
        assertTrue(directories.chatImages.listFiles().isNullOrEmpty())
    }

    @Test
    fun `本机已有附件文件不再下载`() = runBlocking {
        val local = File(directories.chatImages, "chat-local.jpg").apply { writeBytes(jpegBytes()) }
        val existing = MessageAttachment(uri = "file://" + local.toURI().path, mime = "image/jpeg")
        var downloads = 0
        val materializer = ServerMediaMaterializer(directories) { _, _ ->
            downloads++
            null
        }

        val result = materializer.materialize(
            listOf(ServerAttachmentRef("/v1/media/local", existing = existing))
        )

        assertEquals(0, downloads)
        assertEquals(1, result.skipped)
        assertEquals("file://" + local.toURI().path, result.attachmentFor("/v1/media/local")!!.uri)
    }

    // ------------------------------------------------------------------ 0.15：sha256 校验

    @Test
    fun `下载哈希与服务端声明一致时通过 并记录到附件上`() = runBlocking {
        val bytes = jpegBytes()
        val sha = com.yap.app.domain.MediaHashes.sha256Hex(bytes)
        val materializer = ServerMediaMaterializer(directories) { _, _ ->
            ServerMediaDownload(bytes, sha)
        }
        val result = materializer.materialize(listOf(ServerAttachmentRef("/v1/media/hash-ok")))

        assertEquals(1, result.downloaded)
        assertEquals(0, result.failed)
        assertEquals(0, result.hashMismatches)
        val media = result.attachmentFor("/v1/media/hash-ok")!!
        assertTrue(media.local)
        assertEquals("附件要记录校验过的 sha256", sha, media.sha256)
        assertEquals(File(root, "media/chat-hash-ok.jpg").readText(Charsets.ISO_8859_1), String(bytes, Charsets.ISO_8859_1))
    }

    @Test
    fun `下载哈希与服务端声明不一致时删临时文件 记失败 不落库`() = runBlocking {
        val bytes = jpegBytes()
        val wrong = com.yap.app.domain.MediaHashes.sha256Hex(ByteArray(3))
        val materializer = ServerMediaMaterializer(directories) { _, _ ->
            ServerMediaDownload(bytes, wrong)
        }
        val result = materializer.materialize(listOf(ServerAttachmentRef("/v1/media/hash-bad")))

        assertEquals(0, result.downloaded)
        assertEquals(1, result.failed)
        assertEquals("哈希不一致要单独计数（便于诊断）", 1, result.hashMismatches)
        val media = result.attachmentFor("/v1/media/hash-bad")!!
        assertFalse("失败项不落库，只保留服务端引用", media.local)
        assertEquals("/v1/media/hash-bad", media.uri)
        assertNull(media.sha256)
        // 目标文件与任何临时文件都不能留下。
        assertTrue("磁盘不该留下坏字节", directories.all().all { dir -> dir.listFiles().isNullOrEmpty() })
    }

    @Test
    fun `期望哈希与下载字节不一致时同样拒绝`() = runBlocking {
        val bytes = jpegBytes()
        val expected = com.yap.app.domain.MediaHashes.sha256Hex(byteArrayOf(9, 9, 9))
        val materializer = ServerMediaMaterializer(directories) { _, _ -> ServerMediaDownload(bytes) }
        val result = materializer.materialize(
            listOf(ServerAttachmentRef("/v1/media/expected", expectedSha256 = expected))
        )

        assertEquals(1, result.failed)
        assertEquals(1, result.hashMismatches)
        assertTrue(directories.chatImages.listFiles().isNullOrEmpty())
    }

    @Test
    fun `旧服务端没有哈希时照旧落盘（旧数据兼容）`() = runBlocking {
        val materializer = ServerMediaMaterializer(directories) { _, _ ->
            ServerMediaDownload(jpegBytes(), sha256 = null)
        }
        val result = materializer.materialize(listOf(ServerAttachmentRef("/v1/media/no-hash")))

        assertEquals(1, result.downloaded)
        assertEquals(0, result.hashMismatches)
        val media = result.attachmentFor("/v1/media/no-hash")!!
        assertTrue(media.local)
        assertEquals(
            "没有服务端哈希时记录本地重算值",
            com.yap.app.domain.MediaHashes.sha256Hex(jpegBytes()),
            media.sha256
        )
    }

    @Test
    fun `本机记录哈希与文件不一致时删掉坏副本并重新下载`() = runBlocking {
        val good = jpegBytes()
        val goodSha = com.yap.app.domain.MediaHashes.sha256Hex(good)
        // 先放一个内容与记录不符的坏文件（模拟本地副本被写坏/被截断）。
        val local = File(directories.chatImages, "chat-repair.jpg").apply { writeBytes(ByteArray(4)) }
        val existing = MessageAttachment(
            uri = "file://" + local.toURI().path,
            mime = "image/jpeg",
            sha256 = goodSha
        )
        var downloads = 0
        val materializer = ServerMediaMaterializer(directories) { _, _ ->
            downloads++
            ServerMediaDownload(good, goodSha)
        }
        val result = materializer.materialize(
            listOf(ServerAttachmentRef("/v1/media/repair", existing = existing))
        )

        assertEquals("坏副本要触发一次重新下载", 1, downloads)
        assertEquals(1, result.downloaded)
        val media = result.attachmentFor("/v1/media/repair")!!
        assertTrue(media.local)
        assertEquals(goodSha, media.sha256)
        assertEquals("文件内容要变回服务端那份", good.size, File(URI(media.uri)).length().toInt())
    }

    @Test
    fun `未知类型拒绝而不是猜成普通图片`() = runBlocking {
        // 既没有元数据、字节 magic 也认不出来：0.14 会把它当图片落进 files/media，
        // 0.15 必须拒绝（计失败、不落盘）。
        val materializer = ServerMediaMaterializer(directories) { _, _ ->
            ServerMediaDownload(ByteArray(8) { 0x42 })
        }
        val result = materializer.materialize(listOf(ServerAttachmentRef("/v1/media/unknown")))

        assertEquals(0, result.downloaded)
        assertEquals(1, result.failed)
        assertTrue(result.media.values.single().kind == null)
        assertTrue("未知类型不能落任何文件", directories.all().all { dir -> dir.listFiles().isNullOrEmpty() })
    }

    // ------------------------------------------------------------------ 测试字节

    private fun jpegBytes(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(32) { 0x20 }

    private fun pngBytes(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16) { 0x20 }

    private fun gifBytes(): ByteArray =
        "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(16) { 0x20 }

    /** `ftyp` 容器头：4 字节长度占位 + `ftyp` + brand + 4 字节 version/brand + 可选 body。 */
    private fun ftypBytes(brand4: String, body: String = ""): ByteArray {
        val head = ByteArray(4) { 0 }
        val ftyp = "ftyp".toByteArray(Charsets.US_ASCII)
        val brand = brand4.toByteArray(Charsets.US_ASCII)
        val tail = ByteArray(4) { 0 }
        return head + ftyp + brand + tail + body.toByteArray(Charsets.US_ASCII)
    }
}
