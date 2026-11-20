package com.yap.app.domain

import com.yap.app.data.local.MentalActivityEntity
import com.yap.app.data.local.SessionMemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 0.15「媒体类型 / 哈希校验」的纯 JVM 单测。
 *
 * 钉住四件事：
 * 1. 类型：kind 与 mime 必须显式、已知、同族；未知类型**拒绝**，不再兜底成普通图片；
 * 2. 附件写路径：新写入一律显式 kind（普通图片也写 IMAGE），旧数据（kind=null）仍能读；
 * 3. 哈希：`MediaHashes` 的归一/比对（含 `ETag` 的引号与弱校验前缀）；
 * 4. 备份 `media[].sha256`：一致通过、不一致拒绝该条、没有哈希的旧数据照旧兼容。
 *
 * 落盘用临时目录 + 假压缩器（null = 原样落盘），不拉 Android 图形栈。
 */
class MediaIntegrityTest {

    // ------------------------------------------------------------------
    // 类型：严格判定（新写入 / 新响应）
    // ------------------------------------------------------------------

    @Test
    fun `严格判定要求 kind 与 mime 都已知且同族`() {
        val image = MediaTypes.descriptor(MediaKinds.CHAT_IMAGE, "image/png")
        assertNotNull(image)
        assertEquals(MediaKinds.IMAGE, image!!.family)

        val avatar = MediaTypes.descriptor(MediaKinds.PERSONA_AVATAR, "image/jpeg")
        assertEquals(MediaKinds.AVATAR, avatar!!.family)

        val audio = MediaTypes.descriptor(MediaKinds.AUDIO, "audio/m4a; codecs=mp4a")
        assertEquals("mime 参数要被剥掉", "audio/m4a", audio!!.mime)
        assertEquals(MediaKinds.AUDIO, audio.family)

        val video = MediaTypes.descriptor(MediaKinds.VIDEO, "application/mp4")
        assertEquals("application/mp4 按视频处理（服务端既有约定）", MediaKinds.VIDEO, video!!.family)

        val sticker = MediaTypes.descriptor(MediaKinds.STICKER, "image/gif")
        assertEquals(MediaKinds.STICKER, sticker!!.family)
    }

    @Test
    fun `未知 kind 与不同族的 mime 一律拒绝而不是猜`() {
        // 未知 kind：不能当图片。
        assertNull(MediaTypes.descriptor("GIF", "image/gif"))
        assertNull(MediaTypes.descriptor("FILE", "application/pdf"))
        assertNull(MediaTypes.descriptor("", "image/jpeg"))
        assertNull(MediaTypes.descriptor(null, "image/jpeg"))
        assertEquals(MediaTypeRejection.UNKNOWN_KIND, MediaTypes.rejection("GIF", "image/gif"))

        // 已知 kind + 不同族 mime：拒绝（AUDIO 不该收一张图）。
        assertNull(MediaTypes.descriptor(MediaKinds.AUDIO, "image/jpeg"))
        assertEquals(
            MediaTypeRejection.MIME_MISMATCH,
            MediaTypes.rejection(MediaKinds.AUDIO, "image/jpeg")
        )

        // 未知 mime：拒绝（application/octet-stream 不能靠猜）。
        assertNull(MediaTypes.descriptor(MediaKinds.CHAT_IMAGE, "application/octet-stream"))
        assertEquals(
            MediaTypeRejection.UNSUPPORTED_MIME,
            MediaTypes.rejection(MediaKinds.CHAT_IMAGE, "application/octet-stream")
        )

        // 缺 mime：拒绝（有 kind 也不替它编一个）。
        assertEquals(MediaTypeRejection.MISSING_MIME, MediaTypes.rejection(MediaKinds.AUDIO, "  "))

        // 白名单边界：聊天图不收 GIF（GIF 是表情），头像不收 GIF。
        assertNull(MediaTypes.descriptor(MediaKinds.CHAT_IMAGE, "image/gif"))
        assertNull(MediaTypes.descriptor(MediaKinds.PERSONA_AVATAR, "image/gif"))
    }

    @Test
    fun `附件 kind 只允许 IMAGE STICKER AUDIO VIDEO，AVATAR 不是附件类型`() {
        assertTrue(MediaKinds.attachmentKinds.containsAll(listOf("IMAGE", "STICKER", "AUDIO", "VIDEO")))
        assertNull(
            "AVATAR 只能出现在 media[].kind，不是消息附件",
            MediaTypes.attachmentDescriptor(MediaKinds.AVATAR, "image/jpeg")
        )
        assertNull(MediaTypes.attachmentDescriptor("PERSONA_AVATAR", "image/jpeg"))
        assertNull(MediaTypes.attachmentDescriptor("FILE", "application/pdf"))

        // 旧数据 kind=null：按 mime 判（gif 仍是表情），不是「猜」。
        assertEquals(
            MediaKinds.STICKER,
            MediaTypes.attachmentDescriptor(null, "image/gif")!!.kind
        )
        assertEquals(
            MediaKinds.IMAGE,
            MediaTypes.attachmentDescriptor(null, "image/png")!!.kind
        )
        assertEquals(
            MediaKinds.AUDIO,
            MediaTypes.attachmentDescriptor(null, "audio/m4a")!!.kind
        )
        // mime 本身不认识 → 拒绝（旧数据也不能把未知格式当图片）。
        assertNull(MediaTypes.attachmentDescriptor(null, "application/octet-stream"))
    }

    @Test
    fun `新写入的附件一律显式 kind 并归一 mime 与 sha256`() {
        val plain = MessageAttachment(uri = "file:///media/chat-1.jpg", mime = "image/jpeg")
        val written = MediaTypes.normalizeForWrite(plain)!!
        assertEquals("普通图片也要显式写 IMAGE", MessageAttachment.KIND_IMAGE, written.kind)
        assertEquals("image/jpeg", written.mime)

        val gif = MessageAttachment(uri = "file:///stickers/s.gif", mime = "image/gif")
        assertEquals(MessageAttachment.KIND_STICKER, MediaTypes.normalizeForWrite(gif)!!.kind)

        val audio = MessageAttachment(uri = "file:///audio/a.m4a", mime = "audio/m4a", kind = "audio")
        val normalizedAudio = MediaTypes.normalizeForWrite(audio)!!
        assertEquals("小写 kind 要归一成大写", MessageAttachment.KIND_AUDIO, normalizedAudio.kind)

        val hashed = MessageAttachment(
            uri = "file:///media/chat-1.jpg",
            mime = "image/jpeg",
            sha256 = "  \"ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789\"  "
        )
        assertEquals(
            "sha256 要归一成小写去引号",
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            MediaTypes.normalizeForWrite(hashed)!!.sha256
        )

        // 未知类型不写入：宁可不带这条附件，也不当图片写进去。
        assertNull(MediaTypes.normalizeForWrite(MessageAttachment(uri = "file:///x.bin", mime = "application/pdf")))
        assertNull(
            MediaTypes.normalizeForWrite(
                MessageAttachment(uri = "file:///x.jpg", mime = "image/jpeg", kind = "AVATAR")
            )
        )
    }

    // ------------------------------------------------------------------
    // 哈希
    // ------------------------------------------------------------------

    @Test
    fun `sha256 归一与比对`() {
        val bytes = "hello".toByteArray()
        val digest = MediaHashes.sha256Hex(bytes)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            digest
        )
        assertEquals("标准向量：abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", MediaHashes.sha256Hex("abc".toByteArray()))

        assertEquals(digest, MediaHashes.normalize(digest.uppercase()))
        assertEquals("ETag 带引号", digest, MediaHashes.normalize("\"$digest\""))
        assertEquals("弱 ETag 前缀", digest, MediaHashes.normalize("W/\"$digest\""))
        assertNull("不是 64 位 hex 的值等于没有哈希", MediaHashes.normalize("abc"))
        assertNull(MediaHashes.normalize(""))
        assertNull(MediaHashes.normalize(null))
        assertNull(MediaHashes.normalize("sha256:" + digest))
        assertNull(MediaHashes.normalize("z".repeat(64)))

        assertTrue("没有期望值 = 旧数据，通过", MediaHashes.matches(null, bytes))
        assertTrue(MediaHashes.matches(digest, bytes))
        assertTrue(MediaHashes.matches("\"${digest.uppercase()}\"", bytes))
        assertFalse("内容不同必须拒绝", MediaHashes.matches(digest, "hello!".toByteArray()))
        assertFalse("期望值有、实际值算不出来 → 拒绝", MediaHashes.matches(digest, null as String?))
    }

    // ------------------------------------------------------------------
    // 附件 JSON：读旧、写新、拒绝未知
    // ------------------------------------------------------------------

    @Test
    fun `附件编码写入显式 kind 并保留 sha256`() {
        val encoded = AttachmentsCodec.encode(
            listOf(
                MessageAttachment(
                    uri = "file:///media/chat-1.jpg",
                    mime = "image/jpeg",
                    sha256 = MediaHashes.sha256Hex(byteArrayOf(1, 2, 3))
                ),
                MessageAttachment(uri = "file:///stickers/s.gif", mime = "image/gif")
            )
        )!!
        assertTrue("普通图片要写 IMAGE", encoded.contains("\"kind\":\"IMAGE\""))
        assertTrue("表情写 STICKER", encoded.contains("\"kind\":\"STICKER\""))
        assertTrue("sha256 跟着附件走", encoded.contains("\"sha256\":\""))

        val decoded = AttachmentsCodec.decode(encoded)
        assertEquals(2, decoded.size)
        assertEquals(MessageAttachment.KIND_IMAGE, decoded.first().kind)
        assertNotNull(decoded.first().sha256)
    }

    @Test
    fun `附件解码接受旧数据但拒绝未知类型`() {
        val legacy = """[{"uri":"file:///media/a.jpg","mime":"image/jpeg"}]"""
        val decodedLegacy = AttachmentsCodec.decode(legacy)
        assertEquals(1, decodedLegacy.size)
        assertNull("旧数据 kind=null 保持 null（写回时才补显式 kind）", decodedLegacy.first().kind)

        val unknownKind = """[{"uri":"file:///x.bin","mime":"application/pdf","kind":"FILE"}]"""
        assertTrue("未知 kind 的附件必须被丢掉", AttachmentsCodec.decode(unknownKind).isEmpty())

        // 真正的「不同族」：视频挂了一段音频（image/jpeg 是默认值，不算证据，见下一个用例）。
        val mismatch = """[{"uri":"file:///x.mp4","mime":"audio/m4a","kind":"VIDEO"}]"""
        assertTrue("kind 与 mime 不同族的附件必须被丢掉", AttachmentsCodec.decode(mismatch).isEmpty())

        // 旧数据只写了 kind（mime 还留着数据类的默认 image/jpeg）：按 kind 补缺省 mime，不丢。
        val defaultMime = """[{"uri":"file:///x.m4a","kind":"AUDIO","mime":"image/jpeg"}]"""
        val tolerated = AttachmentsCodec.decode(defaultMime).single()
        assertEquals(MessageAttachment.KIND_AUDIO, tolerated.kind)
        assertEquals("audio/m4a", tolerated.mime)

        // 0.15 之前的服务端/旧客户端可能只写 kind 不写 mime：按 kind 补缺省 mime，而不是丢掉。
        val noMime = """[{"uri":"file:///a.m4a","kind":"AUDIO"}]"""
        val restored = AttachmentsCodec.decode(noMime).single()
        assertEquals(MessageAttachment.KIND_AUDIO, restored.kind)
        assertEquals("audio/m4a", restored.mime)
    }

    // ------------------------------------------------------------------
    // 备份 media[].sha256：一致通过 / 不一致拒绝 / 旧数据兼容
    // ------------------------------------------------------------------

    @Test
    fun `备份 media 的 sha256 一致才落盘 不一致拒绝该条`() {
        val root = tempDir("media-hash")
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(8) { 0x11 }
        val sha = MediaHashes.sha256Hex(bytes)

        val good = BackupMedia(
            id = "m-good",
            kind = BackupMediaKind.CHAT_IMAGE,
            personaId = "p1",
            fileName = "chat-good.jpg",
            mime = "image/jpeg",
            bytes = bytes.size,
            sha256 = sha,
            data = BackupBase64.encode(bytes)
        )
        val bad = good.copy(
            id = "m-bad",
            fileName = "chat-bad.jpg",
            // 声明一个与实际字节不同的哈希（模拟传输/手工改动造成的损坏）。
            sha256 = MediaHashes.sha256Hex(ByteArray(4)),
            data = BackupBase64.encode(bytes)
        )
        val legacy = good.copy(id = "m-legacy", fileName = "chat-legacy.jpg", sha256 = "")

        val result = BackupMediaRestorer.restore(
            entries = listOf(good, bad, legacy),
            avatarDir = File(root, "avatars"),
            chatDir = File(root, "media"),
            compressor = null
        )
        assertEquals("两条通过、一条拒绝", 2, result.chatImagesByFileName.size)
        assertNotNull(result.chatImagesByFileName["chat-good.jpg"])
        assertNull("sha256 不一致的那条不落盘", result.chatImagesByFileName["chat-bad.jpg"])
        assertNotNull("旧数据没有 sha256 时照旧兼容", result.chatImagesByFileName["chat-legacy.jpg"])
        assertTrue(
            "被拒绝的条目要计进导入提醒",
            result.warnings.any { it is BackupMediaWarning.ImportSkipped }
        )
        // 拒绝的那条：磁盘上不能留下文件。
        assertFalse(File(root, "media/chat-bad.jpg").exists())
        // 落盘文件的内容必须是声明的那份字节。
        val restored = result.chatImagesByFileName["chat-good.jpg"]!!
        assertEquals(bytes.size, restored.file.length().toInt())
        assertEquals(sha, MediaHashes.sha256Hex(restored.file.readBytes()))
        assertEquals("落盘结果带本地重算的 sha256", sha, restored.sha256)
    }

    @Test
    fun `导出打包会写 sha256 且与字节一致`() {
        val root = tempDir("pack-hash")
        val file = File(root, "chat-pack.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()))
        }
        val packed = BackupMediaPacker.pack(
            listOf(
                BackupMediaCandidate(
                    kind = BackupMediaKind.CHAT_IMAGE,
                    personaId = "p1",
                    fileName = file.name,
                    file = file,
                    mime = "image/jpeg"
                )
            )
        )
        val media = packed.media.single()
        val bytes = BackupBase64.decode(media.data)!!
        assertEquals(bytes.size, media.bytes)
        assertEquals(MediaHashes.sha256Hex(bytes), media.sha256)
        assertTrue(MediaHashes.matches(media.sha256, bytes))
    }

    // ------------------------------------------------------------------
    // 0.15 两张新表：备份结构 ⇄ 本地实体
    // ------------------------------------------------------------------

    @Test
    fun `心理活动与会话备忘录的备份映射保留 0_15 字段`() {
        val activity = MentalActivityEntity(
            id = "mental-1",
            personaId = "p1",
            sessionId = "s1",
            at = 1_000L,
            thought = "他好像有点累",
            moodLabel = "疲惫",
            moodIntensity = 3,
            moodValence = -1,
            moodArousal = 1,
            moodDominance = 2
        )
        val backup = activity.toBackupMentalActivity()
        assertEquals("s1", backup.sessionId)
        assertEquals(-1, backup.moodValence)
        assertEquals("他好像有点累", backup.thought)

        val restored = backup.toEntity()
        assertEquals(activity, restored)

        val memory = SessionMemoryEntity(
            sessionId = "s1",
            personaId = "p1",
            shortSummary = "短期：刚聊完搬家",
            longSummary = "长期：他在准备换工作",
            shortUserCount = 5,
            longUserCount = 18,
            shortUpdatedAt = 900L,
            longUpdatedAt = 800L,
            updatedAt = 950L,
            topicsJson = TopicSummary.encode(
                listOf(
                    MemoryContract.TopicSegment(timeLabel = "2026-11-18 晚上", summary = "聊搬家和换工作")
                )
            ),
            topicUserCount = 12,
            topicUpdatedAt = 850L
        )
        val backupMemory = memory.toBackupSessionMemory()
        assertEquals(5L, backupMemory.shortUserMessages)
        assertEquals(18L, backupMemory.longUserMessages)
        assertEquals(12L, backupMemory.topicUserMessages)
        assertEquals(1, backupMemory.topics.size)
        assertEquals("2026-11-18 晚上", backupMemory.topics.single().timeLabel)

        val restoredMemory = backupMemory.toEntity(sessionId = "local-s1")
        assertEquals("local-s1", restoredMemory.sessionId)
        assertEquals(5, restoredMemory.shortUserCount)
        assertEquals(18, restoredMemory.longUserCount)
        assertEquals(12, restoredMemory.topicUserCount)
        assertEquals("updatedAt 取三段里最新的一段（备份里没有单独的 updatedAt）", 900L, restoredMemory.updatedAt)
        val topics = TopicSummary.parse(restoredMemory.topicsJson!!)
        assertEquals(1, topics.size)
        assertEquals("聊搬家和换工作", topics.single().summary)
    }

    @Test
    fun `会话备忘录读取兼容 topicsJson 字符串与本地游标别名`() {
        val legacy = """{"sessionId":"s1","personaId":"p1",
            "shortSummary":"短期","shortUserCount":4,
            "topicsJson":"[{\"time\":\"2026-11-18 晚上\",\"summary\":\"旧写法\"}]",
            "topicUserCount":9}"""
        val memory = BackupCodec.decode(
            """{"format":"myfriend.backup","formatVersion":7,"personas":[{"id":"p1","name":"他"}],
               "sessionMemory":[$legacy]}"""
        ) as BackupDecodeResult.Success
        val backup = memory.bundle.sessionMemory.single()
        assertEquals("本地别名游标要能读", 4, backup.shortCursor())
        assertEquals(9, backup.topicCursor())
        val entity = backup.toEntity("s1")
        assertEquals(4, entity.shortUserCount)
        assertEquals(9, entity.topicUserCount)
        assertEquals(
            "旧 topicsJson 字符串原样落成本地列",
            "旧写法",
            TopicSummary.parse(entity.topicsJson!!).single().summary
        )
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }
}
