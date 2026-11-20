package com.yap.app.domain

import com.yap.app.data.local.DiaryNoteEntity
import com.yap.app.data.local.StickerEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 备份格式 v6（0.13）：媒体扩到表情/语音/视频 + 顶层 `stickers[]` / `diaryNotes[]`。
 *
 * 这里全是纯 JVM：打包/落盘用临时目录，压缩器是假的（绝不用 Android Bitmap），
 * 所以「五种 kind 各自落到哪个目录」「GIF/音频/视频不会被送去压缩」「v1–v5 仍可读」
 * 都能在本地单测直接验。`BackupRepository` 依赖 Room/Android，不在这一层实例化。
 */
class BackupV6Test {

    // ------------------------------------------------------------------
    // 格式常量与 kind 表
    // ------------------------------------------------------------------

    @Test
    fun `格式版本是 v7 且五种媒体 kind 都登记`() {
        // 0.15 把版本抬到 7（v7 增量见 BackupCodecTest / BackupV7Test）：
        // 这里只继续钉「媒体 kind 表与上限」，版本号本身是 v7 测试的一部分。
        assertEquals(7, BACKUP_FORMAT_VERSION)
        assertEquals(7, BackupBundle().formatVersion)
        assertEquals(
            setOf(
                BackupMediaKind.PERSONA_AVATAR,
                BackupMediaKind.USER_AVATAR,
                BackupMediaKind.CHAT_IMAGE,
                BackupMediaKind.STICKER,
                BackupMediaKind.AUDIO,
                BackupMediaKind.VIDEO
            ),
            BackupMediaKind.all
        )
        // 只有头像与聊天图走重编码：GIF 压成 JPEG 就不动了，音频/视频更不能进 Bitmap。
        assertTrue(BackupMediaKind.isCompressible(BackupMediaKind.PERSONA_AVATAR))
        assertTrue(BackupMediaKind.isCompressible(BackupMediaKind.USER_AVATAR))
        assertTrue(BackupMediaKind.isCompressible(BackupMediaKind.CHAT_IMAGE))
        assertFalse(BackupMediaKind.isCompressible(BackupMediaKind.STICKER))
        assertFalse(BackupMediaKind.isCompressible(BackupMediaKind.AUDIO))
        assertFalse(BackupMediaKind.isCompressible(BackupMediaKind.VIDEO))
        assertEquals(8 * 1024 * 1024, BackupMediaLimits.MAX_MEDIA_FILE_BYTES)
        assertEquals(128L * 1024 * 1024, BackupMediaLimits.MAX_MEDIA_BYTES)
        // 堆再大，媒体预算也不会超过 128 MiB（规格的硬上限）。
        assertEquals(
            BackupMediaLimits.MAX_MEDIA_BYTES,
            BackupMediaLimits.effectiveMediaBudget(10L * 1024 * 1024 * 1024)
        )
    }

    @Test
    fun `kind-aware mime 表不会把格式认错`() {
        // 图片（含头像/聊天图）只认旧三种。
        listOf(BackupMediaFormats.JPEG, BackupMediaFormats.PNG, BackupMediaFormats.WEBP).forEach { mime ->
            assertTrue(BackupMediaFormats.supports(BackupMediaKind.PERSONA_AVATAR, mime))
            assertTrue(BackupMediaFormats.supports(BackupMediaKind.CHAT_IMAGE, mime))
        }
        assertFalse(BackupMediaFormats.supports(BackupMediaKind.CHAT_IMAGE, BackupMediaFormats.GIF))
        assertFalse(BackupMediaFormats.supports(BackupMediaKind.PERSONA_AVATAR, BackupMediaFormats.VIDEO_MP4))
        // 表情只收图片（GIF 是主形态）。
        assertTrue(BackupMediaFormats.supports(BackupMediaKind.STICKER, BackupMediaFormats.GIF))
        assertFalse(BackupMediaFormats.supports(BackupMediaKind.STICKER, BackupMediaFormats.AUDIO_M4A))
        // 音频/视频只收各自容器。
        listOf(
            BackupMediaFormats.AUDIO_M4A,
            BackupMediaFormats.AUDIO_MP4,
            BackupMediaFormats.AUDIO_MPEG
        ).forEach { assertTrue(BackupMediaFormats.supports(BackupMediaKind.AUDIO, it)) }
        listOf(BackupMediaFormats.VIDEO_MP4, BackupMediaFormats.VIDEO_QUICKTIME).forEach {
            assertTrue(BackupMediaFormats.supports(BackupMediaKind.VIDEO, it))
        }
        assertFalse(BackupMediaFormats.supports(BackupMediaKind.AUDIO, BackupMediaFormats.VIDEO_MP4))
        assertFalse(BackupMediaFormats.supports(BackupMediaKind.VIDEO, BackupMediaFormats.AUDIO_M4A))
        // 扩展名映射：音频统一 m4a，视频 mp4/mov，表情 gif。
        assertEquals("m4a", BackupMediaFormats.extensionFor(BackupMediaKind.AUDIO, BackupMediaFormats.AUDIO_MP4))
        assertEquals("mp3", BackupMediaFormats.extensionFor(BackupMediaKind.AUDIO, BackupMediaFormats.AUDIO_MPEG))
        assertEquals("mov", BackupMediaFormats.extensionFor(BackupMediaKind.VIDEO, BackupMediaFormats.VIDEO_QUICKTIME))
        assertEquals("gif", BackupMediaFormats.extensionFor(BackupMediaKind.STICKER, BackupMediaFormats.GIF))
        assertNull(BackupMediaFormats.extensionFor(BackupMediaKind.CHAT_IMAGE, BackupMediaFormats.GIF))
        assertEquals(BackupMediaFormats.GIF, BackupMediaFormats.mimeOfFile("sticker-x.gif"))
        assertEquals(BackupMediaFormats.AUDIO_M4A, BackupMediaFormats.mimeOfFile("voice-x.m4a"))
        assertEquals(BackupMediaFormats.VIDEO_MP4, BackupMediaFormats.mimeOfFile("video-x.mp4"))
    }

    // ------------------------------------------------------------------
    // 导出打包 → 换手机 → 导入落盘（五目录）
    // ------------------------------------------------------------------

    @Test
    fun `五种 kind 往返后各自落到正确的目录 且字节一致`() {
        val root = tempDir("five-kinds")
        val oldAvatarDir = File(root, "old/avatars").apply { mkdirs() }
        val oldMediaDir = File(root, "old/media").apply { mkdirs() }
        val oldStickerDir = File(root, "old/stickers").apply { mkdirs() }
        val oldAudioDir = File(root, "old/audio").apply { mkdirs() }
        val oldVideoDir = File(root, "old/video").apply { mkdirs() }

        val avatar = writeFile(oldAvatarDir, "avatar-a.jpg", jpegBytes(120))
        val chat = writeFile(oldMediaDir, "chat-a.png", pngBytes(90))
        val sticker = writeFile(oldStickerDir, "sticker-a.gif", gifBytes(60))
        val audio = writeFile(oldAudioDir, "voice-a.m4a", audioBytes(80))
        val video = writeFile(oldVideoDir, "video-a.mp4", videoBytes(100))

        val candidates = listOf(
            BackupMediaCandidate(BackupMediaKind.PERSONA_AVATAR, "p1", avatar.name, avatar, "image/jpeg"),
            BackupMediaCandidate(BackupMediaKind.CHAT_IMAGE, "p1", chat.name, chat, "image/png"),
            BackupMediaCandidate(BackupMediaKind.STICKER, "p1", sticker.name, sticker, "image/gif"),
            BackupMediaCandidate(BackupMediaKind.AUDIO, "p1", audio.name, audio, "audio/m4a"),
            BackupMediaCandidate(BackupMediaKind.VIDEO, "p1", video.name, video, "video/mp4")
        )
        val packed = BackupMediaPacker.pack(candidates)
        assertEquals("五种媒体都该带走", 5, packed.media.size)
        assertEquals(0, packed.skippedByLimit)
        assertEquals(5, packed.media.map { it.kind }.toSet().size)

        // 换手机：旧目录全删，新的五个目录是空的。
        root.resolve("old").deleteRecursively()
        val newAvatarDir = File(root, "new/avatars").apply { mkdirs() }
        val newMediaDir = File(root, "new/media").apply { mkdirs() }
        val newStickerDir = File(root, "new/stickers").apply { mkdirs() }
        val newAudioDir = File(root, "new/audio").apply { mkdirs() }
        val newVideoDir = File(root, "new/video").apply { mkdirs() }

        val compressedKinds = mutableSetOf<String>()
        val restored = BackupMediaRestorer.restore(
            entries = packed.media,
            avatarDir = newAvatarDir,
            chatDir = newMediaDir,
            stickerDir = newStickerDir,
            audioDir = newAudioDir,
            videoDir = newVideoDir,
            // 假压缩器：只对头像/聊天图「成功」，其余 kind 若被调用会返回 null 导致该条被跳过。
            compressor = { bytes, mime, kind ->
                compressedKinds += kind
                if (BackupMediaKind.isCompressible(kind)) {
                    BackupCompressedImage(bytes, mime, BackupMediaFormats.extensionOf(mime) ?: "jpg")
                } else {
                    null
                }
            }
        )
        assertTrue("不该有媒体被跳过：${restored.warnings}", restored.warnings.isEmpty())
        assertEquals("只有头像与聊天图该进压缩器", setOf(BackupMediaKind.PERSONA_AVATAR, BackupMediaKind.CHAT_IMAGE), compressedKinds)
        assertEquals(5, restored.byFileName.size)

        val avatarRestored = restored.avatarsByFileName.getValue("avatar-a.jpg")
        assertEquals(newAvatarDir, avatarRestored.file.parentFile)
        assertTrue("头像保持一文一文件（带 UUID）", Regex("""avatar-[0-9a-f]{16}-[0-9a-f-]{36}\.jpg""").matches(avatarRestored.file.name))
        assertArrayEquals(jpegBytes(120), avatarRestored.file.readBytes())

        val chatRestored = restored.chatImagesByFileName.getValue("chat-a.png")
        assertEquals(newMediaDir, chatRestored.file.parentFile)
        assertTrue("聊天图内容寻址", Regex("""chat-[0-9a-f]{16}\.png""").matches(chatRestored.file.name))
        assertArrayEquals(pngBytes(90), chatRestored.file.readBytes())

        val stickerRestored = restored.stickersByFileName.getValue("sticker-a.gif")
        assertEquals(newStickerDir, stickerRestored.file.parentFile)
        assertTrue("表情落在 files/stickers 且带 sticker- 前缀", Regex("""sticker-[0-9a-f]{16}\.gif""").matches(stickerRestored.file.name))
        assertArrayEquals(gifBytes(60), stickerRestored.file.readBytes())

        val audioRestored = restored.audioByFileName.getValue("voice-a.m4a")
        assertEquals(newAudioDir, audioRestored.file.parentFile)
        assertTrue(
            "语音前缀必须与 MediaStorage 的清理语义一致（voice-*）",
            Regex("""voice-[0-9a-f]{16}\.m4a""").matches(audioRestored.file.name)
        )
        assertArrayEquals(audioBytes(80), audioRestored.file.readBytes())

        val videoRestored = restored.videoByFileName.getValue("video-a.mp4")
        assertEquals(newVideoDir, videoRestored.file.parentFile)
        assertTrue(
            "视频前缀必须与 MediaStorage 的清理语义一致（video-*）",
            Regex("""video-[0-9a-f]{16}\.mp4""").matches(videoRestored.file.name)
        )
        assertArrayEquals(videoBytes(100), videoRestored.file.readBytes())

        // 回滚清单要覆盖新目录：落库失败时这些文件都要能删掉。
        assertEquals(5, restored.writtenFiles.size)
        assertTrue(restored.writtenFiles.all { it.isFile })
    }

    @Test
    fun `语音 视频 表情同样受 8 MiB 单文件上限约束`() {
        val dir = tempDir("v6-per-file").apply { mkdirs() }
        val bigVideo = writeFile(dir, "video-big.mp4", ByteArray(9 * 1024 * 1024) { 1 })
        val bigAudio = writeFile(dir, "voice-big.m4a", ByteArray(9 * 1024 * 1024) { 2 })
        val smallSticker = writeFile(dir, "sticker-small.gif", gifBytes(40))
        val packed = BackupMediaPacker.pack(
            listOf(
                BackupMediaCandidate(BackupMediaKind.VIDEO, null, bigVideo.name, bigVideo, "video/mp4"),
                BackupMediaCandidate(BackupMediaKind.AUDIO, null, bigAudio.name, bigAudio, "audio/m4a"),
                BackupMediaCandidate(BackupMediaKind.STICKER, null, smallSticker.name, smallSticker, "image/gif")
            )
        )
        assertEquals("超限的两份被跳过", 1, packed.media.size)
        assertEquals("sticker-small.gif", packed.media.single().fileName)
        assertEquals(2, packed.skippedByLimit)
    }

    @Test
    fun `总量预算把五种 kind 一起算 超出后停止收集`() {
        val dir = tempDir("v6-budget").apply { mkdirs() }
        val files = listOf(
            BackupMediaCandidate(BackupMediaKind.CHAT_IMAGE, null, "chat-1.jpg", writeFile(dir, "chat-1.jpg", jpegBytes(50)), "image/jpeg"),
            BackupMediaCandidate(BackupMediaKind.STICKER, null, "sticker-1.gif", writeFile(dir, "sticker-1.gif", gifBytes(60)), "image/gif"),
            BackupMediaCandidate(BackupMediaKind.AUDIO, null, "voice-1.m4a", writeFile(dir, "voice-1.m4a", audioBytes(70)), "audio/m4a"),
            BackupMediaCandidate(BackupMediaKind.VIDEO, null, "video-1.mp4", writeFile(dir, "video-1.mp4", videoBytes(80)), "video/mp4")
        )
        val budget = files[0].file.length() + files[1].file.length()
        val packed = BackupMediaPacker.pack(files, totalBudget = budget)
        assertEquals(2, packed.media.size)
        assertEquals(2, packed.skippedByLimit)
    }

    @Test
    fun `目录为 null 时该 kind 跳过并计数 不写任何文件`() {
        val root = tempDir("v6-null-dirs")
        val avatarDir = File(root, "avatars").apply { mkdirs() }
        val mediaDir = File(root, "media").apply { mkdirs() }
        val stickerDir = File(root, "stickers").apply { mkdirs() }
        val entries = listOf(
            mediaEntry(BackupMediaKind.STICKER, "sticker-a.gif", "image/gif", gifBytes(10)),
            mediaEntry(BackupMediaKind.AUDIO, "voice-a.m4a", "audio/m4a", audioBytes(20)),
            mediaEntry(BackupMediaKind.VIDEO, "video-a.mp4", "video/mp4", videoBytes(30)),
            mediaEntry(BackupMediaKind.CHAT_IMAGE, "chat-a.png", "image/png", pngBytes(40))
        )
        val restored = BackupMediaRestorer.restore(
            entries = entries,
            avatarDir = avatarDir,
            chatDir = mediaDir
            // v6 三个目录都传 null：只应跳过这三条，聊天图照常恢复。
        )
        assertEquals(1, restored.chatImagesByFileName.size)
        assertTrue(restored.stickersByFileName.isEmpty())
        assertTrue(restored.audioByFileName.isEmpty())
        assertTrue(restored.videoByFileName.isEmpty())
        assertTrue("三条要计入 skipped：${restored.warnings}", restored.warnings.contains(BackupMediaWarning.ImportSkipped(3)))
        assertTrue("不该往 stickerDir 写任何东西", stickerDir.listFiles().isNullOrEmpty())
        assertEquals(1, restored.writtenFiles.size)
        assertTrue(restored.writtenFiles.single().parentFile == mediaDir)
    }

    // ------------------------------------------------------------------
    // 消息附件：kind / durationMs 跨端保留
    // ------------------------------------------------------------------

    @Test
    fun `附件往返保留 kind 与 durationMs 并指向正确目录`() {
        val root = tempDir("v6-attachments")
        val oldSticker = File(root, "old/stickers").apply { mkdirs() }
        val oldAudio = File(root, "old/audio").apply { mkdirs() }
        val oldVideo = File(root, "old/video").apply { mkdirs() }
        val sticker = writeFile(oldSticker, "sticker-a.gif", gifBytes(30))
        val audio = writeFile(oldAudio, "voice-a.m4a", audioBytes(40))
        val video = writeFile(oldVideo, "video-a.mp4", videoBytes(50))
        val candidates = listOf(
            BackupMediaCandidate(BackupMediaKind.STICKER, "p1", sticker.name, sticker, "image/gif"),
            BackupMediaCandidate(BackupMediaKind.AUDIO, "p1", audio.name, audio, "audio/m4a"),
            BackupMediaCandidate(BackupMediaKind.VIDEO, "p1", video.name, video, "video/mp4")
        )
        val packed = BackupMediaPacker.pack(candidates)
        val names = packed.media.map { it.fileName }.toSet()
        val raw = "[" +
            "{\"uri\":\"file:///data/user/0/com.yap.app/files/stickers/sticker-a.gif\"," +
            "\"mime\":\"image/gif\",\"kind\":\"STICKER\"}," +
            "{\"uri\":\"file:///data/user/0/com.yap.app/files/audio/voice-a.m4a\"," +
            "\"mime\":\"audio/m4a\",\"kind\":\"AUDIO\",\"durationMs\":4200}," +
            "{\"uri\":\"file:///data/user/0/com.yap.app/files/video/video-a.mp4\"," +
            "\"mime\":\"video/mp4\",\"kind\":\"VIDEO\",\"durationMs\":9000}" +
            "]"
        val portable = BackupAttachmentsPortable.portable(raw, names)
            ?: error("三类媒体都在 media 里，附件不该被全部丢掉")
        // 导出的附件只写裸文件名，kind/duration 原样保留。
        assertFalse(portable.contains("file://"))
        val portableEntries = AttachmentsCodec.decode(portable)
        assertEquals(listOf("STICKER", "AUDIO", "VIDEO"), portableEntries.map { it.kind })
        assertEquals(listOf(null, 4200L, 9000L), portableEntries.map { it.durationMs })

        // 换手机：旧文件全删，媒体落到新目录。
        root.resolve("old").deleteRecursively()
        val stickerDir = File(root, "new/stickers").apply { mkdirs() }
        val audioDir = File(root, "new/audio").apply { mkdirs() }
        val videoDir = File(root, "new/video").apply { mkdirs() }
        val restored = BackupMediaRestorer.restore(
            entries = packed.media,
            avatarDir = File(root, "new/avatars").apply { mkdirs() },
            chatDir = File(root, "new/media").apply { mkdirs() },
            stickerDir = stickerDir,
            audioDir = audioDir,
            videoDir = videoDir
        )
        val rebuilt = BackupAttachmentsPortable.restore(portable, restored.byFileName)
        assertEquals("三类附件都该重建成功", 0, rebuilt.dropped)
        val entries = AttachmentsCodec.decode(rebuilt.json)
        assertEquals(listOf("STICKER", "AUDIO", "VIDEO"), entries.map { it.kind })
        assertEquals(listOf(null, 4200L, 9000L), entries.map { it.durationMs })
        val stickerAttachment = entries[0]
        val audioAttachment = entries[1]
        val videoAttachment = entries[2]
        assertTrue(stickerAttachment.uri.startsWith("file://"))
        assertTrue(File(java.net.URI(stickerAttachment.uri)).parentFile == stickerDir)
        assertTrue(File(java.net.URI(audioAttachment.uri)).parentFile == audioDir)
        assertTrue(File(java.net.URI(videoAttachment.uri)).parentFile == videoDir)
        assertEquals("image/gif", stickerAttachment.mime)
        assertEquals("audio/m4a", audioAttachment.mime)
        assertEquals("video/mp4", videoAttachment.mime)
        // 指向的文件真的存在（换机后能播放/能显示）。
        entries.forEach { entry ->
            val file = File(java.net.URI(entry.uri))
            assertTrue("附件文件必须存在：${entry.uri}", file.isFile)
        }
    }

    // ------------------------------------------------------------------
    // stickers[] / diaryNotes[] 的编解码与实体映射
    // ------------------------------------------------------------------

    @Test
    fun `stickers 与 diaryNotes 往返后字段一致`() {
        val bundle = BackupBundle(
            personas = listOf(BackupPersona(id = "p1", name = "他")),
            stickers = listOf(
                BackupSticker(
                    id = "s1",
                    ownerUserId = "u1",
                    personaId = "p1",
                    fileName = "sticker-a.gif",
                    mime = "image/gif",
                    width = 320,
                    height = 240,
                    frames = 12,
                    bytes = 4096,
                    source = "MODEL_MADE",
                    createdBy = "MODEL",
                    createdAt = 1_700_000_000_000L
                )
            ),
            diaryNotes = listOf(
                BackupDiaryNote(
                    id = "d1",
                    personaId = "p1",
                    content = "周四给妈妈打电话",
                    createdAt = 1_700_000_000_000L,
                    remindAt = 1_700_100_000_000L,
                    remindedAt = 1_700_050_000_000L,
                    updatedAt = 1_700_000_100_000L
                )
            )
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(bundle)) as BackupDecodeResult.Success
        val sticker = decoded.bundle.stickers.single()
        assertEquals("s1", sticker.id)
        assertEquals("u1", sticker.ownerUserId)
        assertEquals(12, sticker.frames)
        assertEquals("MODEL_MADE", sticker.source)
        assertEquals("MODEL", sticker.createdBy)
        val note = decoded.bundle.diaryNotes.single()
        assertEquals("周四给妈妈打电话", note.content)
        assertEquals(1_700_100_000_000L, note.remindAt)
        assertEquals(1_700_050_000_000L, note.remindedAt)
    }

    @Test
    fun `表情行只写 iOS 的 file 字段也能归一出文件名`() {
        val raw = """
            {
              "format": "myfriend.backup",
              "formatVersion": 6,
              "personas": [{"id": "p1", "name": "他"}],
              "stickers": [
                {"id": "s1", "personaId": "p1", "file": "sticker-a.gif"}
              ]
            }
        """.trimIndent()
        val success = BackupCodec.decode(raw) as? BackupDecodeResult.Success
            ?: error("只有 file 字段的 iOS v6 表情行不该让整份备份失败")
        val sticker = success.bundle.stickers.single()
        assertEquals("", sticker.fileName)
        assertEquals("sticker-a.gif", sticker.file)
        assertEquals("跨端归一后取 file 字段", "sticker-a.gif", sticker.portableFileName)
    }

    @Test
    fun `表情与日记的实体映射保留字段 并重建本机路径`() {
        val entity = StickerEntity(
            id = "s1",
            ownerUserId = "u1",
            personaId = "p1",
            file = "file:///data/user/0/com.yap.app/files/stickers/abc.gif",
            mime = "image/gif",
            width = 300,
            height = 200,
            frames = 8,
            bytes = 1234,
            source = "IMPORTED",
            createdBy = "USER",
            createdAt = 1_700_000_000_000L
        )
        val backup = entity.toBackupSticker() ?: error("合法表情行应该能导出")
        assertEquals("abc.gif", backup.fileName)
        val rebuilt = backup.toEntity("file:///data/user/0/com.yap.app/files/stickers/new.gif")
        assertEquals("file:///data/user/0/com.yap.app/files/stickers/new.gif", rebuilt.file)
        assertEquals(8, rebuilt.frames)
        assertEquals(1234, rebuilt.bytes)
        assertEquals("IMPORTED", rebuilt.source)

        // 服务端模式的表情行（/v1/media/<id>）没有本机字节，不能导出成设备路径。
        assertNull(entity.copy(file = "/v1/media/abc").toBackupSticker())

        val note = DiaryNoteEntity(
            id = "d1",
            personaId = "p1",
            content = "一点小事",
            createdAt = 1_700_000_000_000L,
            remindAt = null,
            remindedAt = null,
            updatedAt = 1_700_000_100_000L
        )
        val noteBackup = note.toBackupDiaryNote()
        assertEquals(note, noteBackup.toEntity())
    }

    // ------------------------------------------------------------------
    // 兼容：v1–v5 样本全部可读
    // ------------------------------------------------------------------

    @Test
    fun `v1 到 v5 的样本仍然全部可读 且不会因为缺段失败`() {
        val dir = File("../fixtures/backup")
        val fixtures = dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.filter { it.name.startsWith("android-export-") || it.name.startsWith("ios-export-") }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("fixtures/backup 目录不存在或为空：${dir.absolutePath}", fixtures.isNotEmpty())
        val oldKinds = setOf(
            BackupMediaKind.PERSONA_AVATAR,
            BackupMediaKind.USER_AVATAR,
            BackupMediaKind.CHAT_IMAGE
        )
        fixtures.forEach { file ->
            val raw = file.readText()
            val result = BackupCodec.decode(raw)
            val success = result as? BackupDecodeResult.Success
            assertTrue("样本 ${file.name} 读不出来：$result", success != null)
            val bundle = success!!.bundle
            assertEquals("读旧备份也按当前格式返回", 7, bundle.formatVersion)
            assertFalse("样本 ${file.name} 不该带密钥字段", BackupCodec.containsSecretField(raw))
            if (Regex(""".*-v[1-5]\.json""").matches(file.name)) {
                assertTrue("${file.name} 是老格式，不该凭空多出 stickers", bundle.stickers.isEmpty())
                assertTrue("${file.name} 是老格式，不该凭空多出 diaryNotes", bundle.diaryNotes.isEmpty())
                assertTrue(
                    "${file.name} 里的 media 只该是旧三种 kind",
                    bundle.media.all { it.kind in oldKinds }
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun mediaEntry(kind: String, fileName: String, mime: String, bytes: ByteArray): BackupMedia =
        BackupMedia(
            id = UUID.randomUUID().toString(),
            kind = kind,
            fileName = fileName,
            mime = mime,
            bytes = bytes.size,
            data = BackupBase64.encode(bytes)
        )

    private fun tempDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "yap-backup-v6-${UUID.randomUUID()}-$name")
        dir.mkdirs()
        return dir
    }

    private fun writeFile(dir: File, name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    private fun jpegBytes(size: Int): ByteArray = ByteArray(size) { (it * 13 + 7).toByte() }.also {
        if (it.size >= 2) {
            it[0] = 0xFF.toByte()
            it[1] = 0xD8.toByte()
        }
    }

    private fun pngBytes(size: Int): ByteArray = ByteArray(size) { (it * 5 + 3).toByte() }.also {
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47).copyInto(it, 0, 0, minOf(4, it.size))
    }

    private fun gifBytes(size: Int): ByteArray = ByteArray(size) { (it * 3 + 1).toByte() }.also {
        "GIF89a".toByteArray(Charsets.US_ASCII).copyInto(it, 0, 0, minOf(6, it.size))
    }

    private fun audioBytes(size: Int): ByteArray = ByteArray(size) { (it * 7 + 2).toByte() }.also {
        "ftypM4A ".toByteArray(Charsets.US_ASCII).copyInto(it, 4)
    }

    private fun videoBytes(size: Int): ByteArray = ByteArray(size) { (it * 11 + 5).toByte() }.also {
        "ftypisom".toByteArray(Charsets.US_ASCII).copyInto(it, 4)
    }
}
