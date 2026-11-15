package com.myfriend.app.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 备份格式 v3 的媒体部分（纯 JVM，可在本地单测直接跑）。
 *
 * 这里钉住的是「图片真的能来回」：
 * - 导出打包 → 清空本机 → 导入落盘，文件真的在新目录里，内容逐字节一致；
 * - 单张 / 总量超限、坏 base64、非图片 mime、危险文件名：跳过并计数，不崩；
 * - 头像 / 聊天图片的引用（`avatarUri` / `attachmentsJson`）指向**新**文件；
 * - v1/v2 没有 media 时，附件仍然按老行为丢弃（只剩文字）。
 */
class BackupMediaTest {

    // ------------------------------------------------------------------
    // base64
    // ------------------------------------------------------------------

    @Test
    fun `base64 是标准写法 带 padding 不换行`() {
        val bytes = ByteArray(256) { (it * 7).toByte() }
        val encoded = BackupBase64.encode(bytes)
        assertEquals("长度按 4 对齐", 0, encoded.length % 4)
        assertTrue("不该有换行", !encoded.contains("\n") && !encoded.contains("\r"))
        assertTrue("只允许标准字母表", encoded.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
        // RFC 4648 的公开向量：f / fo / foo / foob / fooba / foobar
        assertEquals("Zg==", BackupBase64.encode("f".toByteArray()))
        assertEquals("Zm8=", BackupBase64.encode("fo".toByteArray()))
        assertEquals("Zm9v", BackupBase64.encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", BackupBase64.encode("foob".toByteArray()))
        assertEquals("Zm9vYmE=", BackupBase64.encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", BackupBase64.encode("foobar".toByteArray()))
        assertArrayEquals(bytes, BackupBase64.decode(encoded))
    }

    @Test
    fun `坏 base64 返回 null 而不是抛异常`() {
        assertNull(BackupBase64.decode("abc"))          // 长度不是 4 的倍数
        assertNull(BackupBase64.decode("****"))
        assertNull(BackupBase64.decode("ab=c"))
        assertNull(BackupBase64.decode("Zm9vYmFy_-==")) // URL-safe 字符不接受
        assertEquals(0, BackupBase64.decode("")!!.size)
        // 导入时对空白宽容（别人手工排版过），但内容仍要正确。
        assertArrayEquals("foobar".toByteArray(), BackupBase64.decode("Zm9v YmFy\n"))
    }

    // ------------------------------------------------------------------
    // 导出打包 → 清空 → 导入落盘（这张表就是「换手机」）
    // ------------------------------------------------------------------

    @Test
    fun `两张头像两张聊天图 往返后文件真的在 且字节一致`() {
        val root = tempDir("roundtrip")
        val sourceAvatarDir = File(root, "old/avatars").apply { mkdirs() }
        val sourceMediaDir = File(root, "old/media").apply { mkdirs() }
        val avatarA = writeFile(sourceAvatarDir, "avatar-a.jpg", jpegBytes(100))
        val avatarB = writeFile(sourceAvatarDir, "avatar-b.png", pngBytes(80))
        val chat1 = writeFile(sourceMediaDir, "chat-1.png", pngBytes(120))
        val chat2 = writeFile(sourceMediaDir, "chat-2.jpg", jpegBytes(90))
        val avatarABytes = avatarA.readBytes()
        val avatarBBytes = avatarB.readBytes()

        // ---- 导出：两个头像（一个人格 + 一个用户自己的）+ 两张聊天图 ----
        val candidates = listOf(
            BackupMediaCandidate(BackupMediaKind.PERSONA_AVATAR, "p1", avatarA.name, avatarA, "image/jpeg"),
            BackupMediaCandidate(BackupMediaKind.PERSONA_AVATAR, "p2", avatarB.name, avatarB, "image/png"),
            BackupMediaCandidate(BackupMediaKind.CHAT_IMAGE, "p1", chat1.name, chat1, "image/png"),
            BackupMediaCandidate(BackupMediaKind.CHAT_IMAGE, "p1", chat2.name, chat2, "image/jpeg")
        )
        val packed = BackupMediaPacker.pack(candidates)
        assertEquals(0, packed.skippedByLimit)
        assertEquals(4, packed.media.size)

        val chatNames = packed.media.filter { it.kind == BackupMediaKind.CHAT_IMAGE }.map { it.fileName }.toSet()
        val portableAttachments = BackupAttachmentsPortable.portable(
            attachmentsJson(
                listOf(
                    attachment(chat1, "image/png"),
                    attachment(chat2, "image/jpeg")
                )
            ),
            chatNames
        ) ?: error("两张聊天图都进了 media，附件不该被丢")
        assertTrue("附件只写裸文件名", portableAttachments.contains("\"uri\":\"chat-1.png\""))
        assertTrue("附件里不该有设备路径", !portableAttachments.contains("file://"))

        // ---- 换手机：把旧文件全删掉，新的媒体目录是空的 ----
        sourceAvatarDir.deleteRecursively()
        sourceMediaDir.deleteRecursively()
        val newAvatarDir = File(root, "new/avatars").apply { mkdirs() }
        val newMediaDir = File(root, "new/media").apply { mkdirs() }

        val restored = BackupMediaRestorer.restore(packed.media, newAvatarDir, newMediaDir)
        assertTrue("不该有图片被跳过：${restored.warnings}", restored.warnings.isEmpty())
        assertEquals(2, restored.avatarsByFileName.size)
        assertEquals(2, restored.chatImagesByFileName.size)

        // 头像：引用指向新文件；文件真的在；内容逐字节一致（校验和也一起打印出来对账）。
        val personaAvatar = restored.avatarsByFileName["avatar-a.jpg"] ?: error("avatar-a.jpg 没有恢复")
        assertTrue("新头像必须真的在磁盘上", personaAvatar.file.isFile)
        assertTrue("新头像必须用新文件名（不复用备份里的名字）", personaAvatar.file.name != "avatar-a.jpg")
        assertEquals(avatarABytes.size.toLong(), personaAvatar.file.length())
        assertArrayEquals(avatarABytes, personaAvatar.file.readBytes())
        assertEquals(sha256(avatarABytes), sha256(personaAvatar.file.readBytes()))
        assertTrue("uri 必须指向新文件", personaAvatar.uri.endsWith(personaAvatar.file.name))
        println("avatar restored: ${personaAvatar.file.name} (${personaAvatar.file.length()} bytes, sha256=${sha256(personaAvatar.file.readBytes())})")

        val personaAvatarB = restored.avatarsByFileName["avatar-b.png"] ?: error("avatar-b.png 没有恢复")
        assertArrayEquals(avatarBBytes, personaAvatarB.file.readBytes())

        // 聊天图片：attachmentsJson 重建成本机新路径，文件字节一致。
        val rebuiltAttachments = BackupAttachmentsPortable.restore(
            portableAttachments,
            restored.chatImagesByFileName
        )
        assertEquals("两张图都该重建成功", 0, rebuiltAttachments.dropped)
        val entries = AttachmentsCodec.decode(rebuiltAttachments.json)
        assertEquals(2, entries.size)
        entries.forEach { entry ->
            val file = fileOf(entry.uri)
            assertTrue("附件必须指向新文件：${entry.uri}", file != null)
            assertTrue("附件文件必须存在：${entry.uri}", file!!.isFile)
            assertTrue("附件不能再指向旧目录", !file.path.contains("/old/"))
            assertTrue("附件 uri 必须是本机新路径", entry.uri.startsWith("file:"))
        }
        val restoredChat1 = entries.first { it.uri.endsWith(".png") }
        val expectedPng = pngBytes(120)
        assertArrayEquals(expectedPng, fileOf(restoredChat1.uri)!!.readBytes())
        println("chat restored: ${fileOf(restoredChat1.uri)!!.name} (${fileOf(restoredChat1.uri)!!.length()} bytes, sha256=${sha256(expectedPng)})")
    }

    @Test
    fun `总量超限时停止收集 并报告没带走的张数`() {
        val dir = tempDir("budget-total").apply { mkdirs() }
        val files = (1..4).map { index ->
            writeFile(dir, "chat-$index.jpg", jpegBytes(60 + index * 10))
        }
        val candidates = files.mapIndexed { index, file ->
            BackupMediaCandidate(BackupMediaKind.CHAT_IMAGE, null, file.name, file, "image/jpeg")
        }
        // 预算只够两张：第三张开始停止收集，后两张计入「超出上限」。
        val packed = BackupMediaPacker.pack(candidates, totalBudget = (files[0].length() + files[1].length()))
        assertEquals(2, packed.media.size)
        assertEquals(2, packed.skippedByLimit)
    }

    @Test
    fun `单张超过 8 MiB 的图片被跳过 其余照常`() {
        val dir = tempDir("budget-file").apply { mkdirs() }
        val big = writeFile(dir, "chat-big.jpg", ByteArray(9 * 1024 * 1024) { 1 })
        val small = writeFile(dir, "chat-small.jpg", jpegBytes(50))
        val packed = BackupMediaPacker.pack(
            listOf(
                BackupMediaCandidate(BackupMediaKind.CHAT_IMAGE, null, big.name, big, "image/jpeg"),
                BackupMediaCandidate(BackupMediaKind.CHAT_IMAGE, null, small.name, small, "image/jpeg")
            )
        )
        assertEquals(1, packed.media.size)
        assertEquals("chat-small.jpg", packed.media.single().fileName)
        assertEquals(1, packed.skippedByLimit)
    }

    // ------------------------------------------------------------------
    // 导入的坏数据：跳过 + 计数，不崩
    // ------------------------------------------------------------------

    @Test
    fun `坏 base64 与不支持的 mime 被跳过 其它正常恢复`() {
        val root = tempDir("bad-data")
        val avatarDir = File(root, "avatars").apply { mkdirs() }
        val mediaDir = File(root, "media").apply { mkdirs() }
        val good = jpegBytes(30)
        val entries = listOf(
            BackupMedia(
                id = "1",
                kind = BackupMediaKind.PERSONA_AVATAR,
                fileName = "avatar-good.jpg",
                mime = "image/jpeg",
                bytes = good.size,
                data = BackupBase64.encode(good)
            ),
            BackupMedia(
                id = "2",
                kind = BackupMediaKind.PERSONA_AVATAR,
                fileName = "avatar-bad.jpg",
                mime = "image/jpeg",
                bytes = 10,
                data = "这不是 base64"
            ),
            BackupMedia(
                id = "3",
                kind = BackupMediaKind.CHAT_IMAGE,
                fileName = "chat-video.mp4",
                mime = "video/mp4",
                bytes = 3,
                data = BackupBase64.encode(byteArrayOf(1, 2, 3))
            ),
            BackupMedia(
                id = "4",
                kind = "UNKNOWN_KIND",
                fileName = "chat-unknown.jpg",
                mime = "image/jpeg",
                bytes = 3,
                data = BackupBase64.encode(byteArrayOf(1, 2, 3))
            ),
            BackupMedia(
                id = "5",
                kind = BackupMediaKind.CHAT_IMAGE,
                fileName = "chat-mismatch.jpg",
                mime = "image/jpeg",
                bytes = 999,
                data = BackupBase64.encode(byteArrayOf(1, 2, 3))
            )
        )
        val restored = BackupMediaRestorer.restore(entries, avatarDir, mediaDir)
        assertEquals("只有那张好头像该恢复", 1, restored.avatarsByFileName.size)
        assertTrue(restored.chatImagesByFileName.isEmpty())
        assertEquals(1, restored.writtenFiles.size)
        // 4 条坏数据（坏 base64 / 视频 mime / 未知 kind / bytes 对不上）
        assertTrue(
            "坏条目要计数：${restored.warnings}",
            restored.warnings.contains(BackupMediaWarning.ImportSkipped(4))
        )
        assertTrue(restored.avatarsByFileName.getValue("avatar-good.jpg").file.isFile)
    }

    @Test
    fun `危险的 fileName 被拒绝 一个字节都不写`() {
        val root = tempDir("guard")
        val avatarDir = File(root, "avatars").apply { mkdirs() }
        val mediaDir = File(root, "media").apply { mkdirs() }
        val data = BackupBase64.encode(jpegBytes(10))
        val dangerous = listOf("../evil.jpg", "/data/user/0/com.myfriend.app/files/evil.jpg", "..\\evil.jpg", "a/b.jpg", "")
        val entries = dangerous.mapIndexed { index, name ->
            BackupMedia(
                id = "g$index",
                kind = BackupMediaKind.CHAT_IMAGE,
                fileName = name,
                mime = "image/jpeg",
                bytes = jpegBytes(10).size,
                data = data
            )
        }
        val restored = BackupMediaRestorer.restore(entries, avatarDir, mediaDir)
        assertTrue(restored.chatImagesByFileName.isEmpty())
        assertEquals(0, restored.writtenFiles.size)
        assertEquals(BackupMediaWarning.ImportSkipped(dangerous.size), restored.warnings.single())
        // 目录外没有出现任何文件。
        assertFalse(File(root, "evil.jpg").exists())
        assertFalse(File(root, "avatars/../evil.jpg").canonicalFile.exists())
        assertTrue("媒体目录不该被写进东西", mediaDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `目录非法时跳过并计数 不抛异常`() {
        val root = tempDir("bad-dir")
        val notADirectory = File(root, "avatars").apply { writeText("I am a file") }
        val mediaDir = File(root, "media").apply { mkdirs() }
        val bytes = jpegBytes(12)
        val restored = BackupMediaRestorer.restore(
            listOf(
                BackupMedia(
                    id = "1",
                    kind = BackupMediaKind.PERSONA_AVATAR,
                    fileName = "avatar-x.jpg",
                    mime = "image/jpeg",
                    bytes = bytes.size,
                    data = BackupBase64.encode(bytes)
                )
            ),
            notADirectory,
            mediaDir
        )
        assertTrue(restored.avatarsByFileName.isEmpty())
        assertTrue(restored.warnings.contains(BackupMediaWarning.ImportSkipped(1)))
    }

    @Test
    fun `同一文件名的重复条目只认第一条`() {
        val root = tempDir("duplicate")
        val avatarDir = File(root, "avatars").apply { mkdirs() }
        val mediaDir = File(root, "media").apply { mkdirs() }
        val first = jpegBytes(20)
        val second = jpegBytes(30)
        val entries = listOf(
            BackupMedia(
                id = "1", kind = BackupMediaKind.PERSONA_AVATAR, fileName = "avatar-same.jpg",
                mime = "image/jpeg", bytes = first.size, data = BackupBase64.encode(first)
            ),
            BackupMedia(
                id = "2", kind = BackupMediaKind.PERSONA_AVATAR, fileName = "avatar-same.jpg",
                mime = "image/jpeg", bytes = second.size, data = BackupBase64.encode(second)
            )
        )
        val restored = BackupMediaRestorer.restore(entries, avatarDir, mediaDir)
        val kept = restored.avatarsByFileName.getValue("avatar-same.jpg")
        assertArrayEquals(first, kept.file.readBytes())
        assertTrue(restored.warnings.contains(BackupMediaWarning.ImportSkipped(1)))
    }

    // ------------------------------------------------------------------
    // v4：导入压缩 + 内容哈希去重
    // ------------------------------------------------------------------

    @Test
    fun `导入时压缩器输出被落盘 同一张聊天图只留一份`() {
        val root = tempDir("compress")
        val avatarDir = File(root, "avatars").apply { mkdirs() }
        val mediaDir = File(root, "media").apply { mkdirs() }
        val original = pngBytes(120)
        val compressed = jpegBytes(60)
        val entries = listOf(
            BackupMedia(
                id = "1", kind = BackupMediaKind.CHAT_IMAGE, fileName = "chat-a.png",
                mime = "image/png", bytes = original.size, data = BackupBase64.encode(original)
            ),
            BackupMedia(
                // 不同源文件名、同样的内容：导入后应该指向同一个内容寻址文件。
                id = "2", kind = BackupMediaKind.CHAT_IMAGE, fileName = "chat-b.png",
                mime = "image/png", bytes = original.size, data = BackupBase64.encode(original)
            )
        )
        val restored = BackupMediaRestorer.restore(
            entries = entries,
            avatarDir = avatarDir,
            chatDir = mediaDir,
            compressor = { _, _, _ -> BackupCompressedImage(compressed, "image/jpeg", "jpg") }
        )

        assertTrue("压缩成功时不该有 warning：${restored.warnings}", restored.warnings.isEmpty())
        assertEquals("两张同图只该落一份", 1, restored.writtenFiles.size)
        assertEquals(2, restored.chatImagesByFileName.size)
        val a = restored.chatImagesByFileName.getValue("chat-a.png")
        val b = restored.chatImagesByFileName.getValue("chat-b.png")
        assertEquals("同内容要复用同一份文件", a.file, b.file)
        assertTrue("落盘的是压缩后的字节", a.file.readBytes().contentEquals(compressed))
        assertTrue("文件名是 chat-<sha16>.jpg", Regex("""chat-[0-9a-f]{16}\.jpg""").matches(a.file.name))
        assertEquals("image/jpeg", a.mime)
        assertEquals("压缩后不应该再有原图大小的副本", 1, mediaDir.listFiles()!!.count { it.isFile })
    }

    @Test
    fun `压缩失败或超出输出上限时跳过并计数`() {
        val root = tempDir("compress-fail")
        val avatarDir = File(root, "avatars").apply { mkdirs() }
        val mediaDir = File(root, "media").apply { mkdirs() }
        val bytes = jpegBytes(40)
        fun entry(id: String) = BackupMedia(
            id = id, kind = BackupMediaKind.CHAT_IMAGE, fileName = "chat-$id.jpg",
            mime = "image/jpeg", bytes = bytes.size, data = BackupBase64.encode(bytes)
        )
        val failed = BackupMediaRestorer.restore(
            listOf(entry("1")), avatarDir, mediaDir,
            compressor = { _, _, _ -> null }
        )
        assertTrue(failed.warnings.contains(BackupMediaWarning.ImportSkipped(1)))
        assertTrue(failed.writtenFiles.isEmpty())

        val tooLarge = BackupMediaRestorer.restore(
            listOf(entry("2")), avatarDir, mediaDir,
            compressor = { _, _, _ ->
                BackupCompressedImage(ByteArray(BackupMediaLimits.MAX_CHAT_OUTPUT_BYTES + 1))
            }
        )
        assertTrue(tooLarge.warnings.contains(BackupMediaWarning.ImportSkipped(1)))
        assertTrue(tooLarge.writtenFiles.isEmpty())
    }

    @Test
    fun `压缩上限常量与规范一致`() {
        assertEquals(512, BackupMediaLimits.AVATAR_MAX_EDGE)
        assertEquals(1600, BackupMediaLimits.CHAT_MAX_EDGE)
        assertEquals(400 * 1024, BackupMediaLimits.outputLimit(BackupMediaKind.PERSONA_AVATAR))
        assertEquals(800 * 1024, BackupMediaLimits.outputLimit(BackupMediaKind.CHAT_IMAGE))
        assertEquals(85, BackupMediaLimits.JPEG_QUALITY)
    }

    @Test
    fun `头像保持一文一文件 聊天图才是内容寻址`() {
        val bytes = jpegBytes(30)
        val avatar = BackupMediaRestorer.defaultNewName(BackupMediaRestorer.AvatarPrefix, "jpg", bytes)
        val chat = BackupMediaRestorer.defaultNewName(BackupMediaRestorer.ChatPrefix, "jpg", bytes)
        assertTrue("头像要带 UUID，避免删除一个头像时把共用的文件删掉", avatar.contains("-"))
        assertTrue("头像名以 avatar- 开头", avatar.startsWith("avatar-"))
        assertEquals("同一份内容生成同名聊天图片", chat, BackupMediaRestorer.defaultNewName("chat-", "jpg", bytes))
        assertTrue("聊天图片名是内容哈希", Regex("""chat-[0-9a-f]{16}\.jpg""").matches(chat))
    }

    // ------------------------------------------------------------------
    // v1/v2 兼容 + 导入大小闸
    // ------------------------------------------------------------------

    @Test
    fun `没有 media 的老备份 附件仍然只剩文字`() {
        val old = attachmentsJson(listOf(attachment(File("a.jpg"), "image/jpeg")))
            .replace("\"uri\":\"a.jpg\"", "\"uri\":\"file:///data/user/0/com.myfriend.app/files/media/chat-old.jpg\"")
        val restored = BackupAttachmentsPortable.restore(old, emptyMap())
        assertEquals("设备路径的附件一条都认不回来", 1, restored.dropped)
        assertNull(restored.json)
    }

    @Test
    fun `导入大小闸是 256 MiB`() {
        assertEquals(256L * 1024 * 1024, BackupMediaLimits.MAX_BACKUP_BYTES)
        assertFalse(BackupMediaLimits.isTooLarge(256L * 1024 * 1024))
        assertTrue(BackupMediaLimits.isTooLarge(256L * 1024 * 1024 + 1))
        assertEquals(256L, BackupMediaLimits.megabytes())
        // 旧的 64 MB 已经装不下一份自己导出的 v3 备份（128 MiB 图片 base64 后约 171 MiB）。
        assertFalse(BackupMediaLimits.isTooLarge(171L * 1024 * 1024))
    }

    @Test
    fun `导出时只写裸文件名 不写设备路径`() {
        val attachmentJson = attachmentsJson(
            listOf(
                attachment(File("/data/user/0/com.myfriend.app/files/media/chat-a.jpg"), "image/jpeg")
            )
        )
        assertEquals(
            "聊天附件导出时只留文件名",
            """[{"uri":"chat-a.jpg"}]""",
            BackupAttachmentsPortable.portable(attachmentJson, setOf("chat-a.jpg"))
        )
        // 没有对应 media 的附件不写：换机它也回不来。
        assertNull(BackupAttachmentsPortable.portable(attachmentJson, emptySet()))
        // 非图片 mime + 文件名也认不出扩展名 → 丢掉。
        assertNull(
            BackupAttachmentsPortable.portable(
                attachmentsJson(listOf(attachment(File("clip.mp4"), "video/mp4"))),
                setOf("clip.mp4")
            )
        )
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun tempDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "myfriend-backup-media-${UUID.randomUUID()}-$name")
        dir.mkdirs()
        return dir
    }

    private fun writeFile(dir: File, name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    private fun jpegBytes(size: Int): ByteArray = ByteArray(size) { (it * 13 + 7).toByte() }.also {
        // 前两个字节写成 JPEG 的 SOI，方便人肉认出「这是图片」。
        if (it.size >= 2) {
            it[0] = 0xFF.toByte()
            it[1] = 0xD8.toByte()
        }
    }

    private fun pngBytes(size: Int): ByteArray = ByteArray(size) { (it * 5 + 3).toByte() }.also {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        signature.copyInto(it, 0, 0, minOf(4, it.size))
    }

    private fun attachmentsJson(items: List<String>): String = "[${items.joinToString(",")}]"

    private fun attachment(file: File, mime: String): String {
        val uri = if (file.path.startsWith("/")) "file://${file.path}" else file.name
        return """{"uri":"$uri","mime":"$mime"}"""
    }

    private fun fileOf(uri: String): File? =
        if (uri.startsWith("file:")) File(java.net.URI(uri)) else null

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
