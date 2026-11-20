package com.yap.agentd.media

import com.yap.agentd.TestSupport
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaKind
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.MediaUrl
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * `listen` / `watch` 的媒体解析层（`spec/audio-video`）。
 *
 * 这里**不跑 HTTP、不跑真实生成器**：用真实 [FileStore]（临时数据目录）造媒体与消息，
 * 用假 [FrameExtractor] 钉住「帧数上限 / JPEG 校验 / 没有解码器必须逐字失败」。
 * 唯一的真实 `ffmpeg` 用例在末尾，本机没装 `ffmpeg` 时整例 `Assume` 跳过（不能因此失败）。
 */
class MediaUnderstandingTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_700_000_000_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-media-understanding")
        clock.now = 1_700_000_000_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    // ------------------------------------------------------------------ 造数据的小工具

    /** 一段真正「像视频」的字节（不解析，只给假抽取器用；`ftyp` 只是让日志像回事）。 */
    private val videoBytes = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x70, 0x34, 0x32, 1, 2, 3)

    private fun newSession(title: String): String = store.ensureSession(personaId, title).id

    private fun audioMedia(mime: String = MediaMime.M4A, data: ByteArray): String =
        store.saveMedia(MediaKind.AUDIO, personaId, mime, data).item.id

    private fun videoMedia(mime: String = MediaMime.VIDEO_MP4, data: ByteArray): String =
        store.saveMedia(MediaKind.VIDEO, personaId, mime, data).item.id

    private fun attach(sessionId: String, vararg mediaIds: String, blocked: Boolean = false): Message =
        store.appendMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = "…",
            attachments = mediaIds.map { MediaUrl.url(it) },
            blocked = blocked
        )

    /** 一帧「像 JPEG」的字节：`FF D8 FF E0` + 序号（内容不解析，只校验 magic）。 */
    private fun jpeg(index: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), index.toByte())

    /** 假抽取器（默认 `available() = true`）。 */
    private fun fakeFrames(vararg frames: ByteArray): FrameExtractor =
        FrameExtractor { _, _, _, _ -> frames.toList() }

    private fun reasonOf(result: MediaLookup): String =
        (result as? MediaLookup.Failure)?.reason ?: throw AssertionError("期望 Failure，实际是 $result")

    // ------------------------------------------------------------------ listen

    @Test
    fun `listen with an explicit id returns base64 and provider format`() {
        val sessionId = newSession("语音")
        val understanding = MediaUnderstanding(store, fakeFrames())

        for ((mime, format) in listOf(
            MediaMime.M4A to "m4a",
            MediaMime.AUDIO_MP4 to "m4a",
            MediaMime.MPEG to "mp3"
        )) {
            // 每份内容不同（saveMedia 按 sha256 去重，同内容会复用上一条的 mime）。
            val bytes = "audio:$mime".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 1, 2, 127, -1, -128)
            val mediaId = audioMedia(mime, bytes)
            // 裸 id 与 `/v1/media/<id>` 两种引用都必须认。
            val reference = if (mime == MediaMime.MPEG) mediaId else MediaUrl.url(mediaId)

            val payload = (understanding.listen(sessionId, reference) as MediaLookup.Audio).payload
            assertEquals(mediaId, payload.mediaId)
            assertEquals(mime, payload.mime)
            assertEquals(format, payload.format)
            assertEquals(bytes.size, payload.bytes)
            assertArrayEquals(bytes, Base64.getDecoder().decode(payload.base64))
        }
    }

    @Test
    fun `listen without an id picks the most recent voice message`() {
        val sessionId = newSession("语音")
        val understanding = MediaUnderstanding(store, fakeFrames())
        val older = audioMedia(MediaMime.M4A, byteArrayOf(1, 1, 1))
        val newer = audioMedia(MediaMime.M4A, byteArrayOf(2, 2, 2))
        // 最新一条消息带的是图片：不能被当成「最近一条语音」。
        val image = store.saveMedia(MediaKind.CHAT_IMAGE, personaId, MediaMime.JPEG, byteArrayOf(9, 9, 9)).item.id

        attach(sessionId, older)
        attach(sessionId, newer)
        attach(sessionId, image)

        val payload = (understanding.listen(sessionId, null) as MediaLookup.Audio).payload
        assertEquals(newer, payload.mediaId)
        assertArrayEquals(byteArrayOf(2, 2, 2), Base64.getDecoder().decode(payload.base64))
    }

    @Test
    fun `listen fails clearly for unknown deleted and malformed ids`() {
        val sessionId = newSession("语音")
        val understanding = MediaUnderstanding(store, fakeFrames())

        assertEquals("没有这个音频（可能已删除）", reasonOf(understanding.listen(sessionId, "no-such-media")))
        assertEquals("没有这个音频（可能已删除）", reasonOf(understanding.listen(sessionId, "../etc/passwd")))

        val deleted = audioMedia(MediaMime.M4A, byteArrayOf(4, 4, 4))
        assertTrue(store.deleteMedia(deleted, force = true).deleted)
        assertEquals(
            "没有这个音频（可能已删除）",
            reasonOf(understanding.listen(sessionId, MediaUrl.url(deleted)))
        )

        // 会话不存在 = 这里没有可听的语音（不是 500）。
        assertEquals("这段对话里没有可以听的语音", reasonOf(understanding.listen("no-such-session", null)))
    }

    @Test
    fun `listen rejects non audio media`() {
        val sessionId = newSession("语音")
        val understanding = MediaUnderstanding(store, fakeFrames())
        val image = store.saveMedia(MediaKind.CHAT_IMAGE, personaId, MediaMime.JPEG, byteArrayOf(5, 5, 5)).item.id
        val video = videoMedia(MediaMime.VIDEO_MP4, byteArrayOf(6, 6, 6))

        assertEquals("这不是音频", reasonOf(understanding.listen(sessionId, image)))
        assertEquals("这不是音频", reasonOf(understanding.listen(sessionId, video)))
    }

    @Test
    fun `listen rejects audio above the size limit`() {
        val sessionId = newSession("语音")
        // 路由层才限制上传大小；saveMedia 本身没有上限，所以能造出「元数据合法但超限」的媒体。
        val tooBig = ByteArray(MediaUnderstanding.MAX_AUDIO_BYTES + 1) { index -> (index % 251).toByte() }
        val mediaId = audioMedia(MediaMime.M4A, tooBig)

        val result = MediaUnderstanding(store, fakeFrames()).listen(sessionId, mediaId)
        assertEquals("音频太大（上限 8 MB）", reasonOf(result))
    }

    @Test
    fun `listen reports a missing file`() {
        val sessionId = newSession("语音")
        val understanding = MediaUnderstanding(store, fakeFrames())
        val audioId = audioMedia(MediaMime.M4A, byteArrayOf(11, 12, 13))
        // 元数据还在、文件被删（客户端 GC / 手工清理）：要明确说「文件不见了」，不是「没有这个音频」。
        Files.deleteIfExists(store.mediaDirectory.resolve("$audioId.m4a"))

        assertEquals("音频文件不见了", reasonOf(understanding.listen(sessionId, audioId)))
    }

    // ------------------------------------------------------------------ watch

    @Test
    fun `watch returns extracted jpeg frames`() {
        val sessionId = newSession("视频")
        val mediaId = videoMedia(MediaMime.VIDEO_MP4, videoBytes)
        val frames = listOf(jpeg(1), jpeg(2), jpeg(3))
        val understanding = MediaUnderstanding(store, FrameExtractor { _, _, _, _ -> frames })

        val video = understanding.watch(sessionId, MediaUrl.url(mediaId)) as MediaLookup.Video
        assertEquals(mediaId, video.frames.mediaId)
        assertEquals(3, video.frames.frameCount)
        assertEquals(MediaMime.JPEG, video.frames.mime)
        for ((index, frame) in video.frames.jpegs.withIndex()) {
            assertArrayEquals(frames[index], frame)
        }
    }

    @Test
    fun `watch caps frames at eight and drops non jpeg frames`() {
        val sessionId = newSession("视频")
        val mediaId = videoMedia(MediaMime.VIDEO_MP4, videoBytes)

        var askedMaxFrames = -1
        var askedMaxEdge = -1
        val twelve = (1..12).map { jpeg(it) }
        val capped = MediaUnderstanding(
            store,
            FrameExtractor { _, _, maxFrames, maxEdge ->
                askedMaxFrames = maxFrames
                askedMaxEdge = maxEdge
                twelve
            }
        ).watch(sessionId, mediaId) as MediaLookup.Video
        assertEquals(8, capped.frames.frameCount)
        assertEquals(MediaUnderstanding.MAX_FRAMES, askedMaxFrames)
        assertEquals(MediaUnderstanding.MAX_FRAME_EDGE, askedMaxEdge)
        assertArrayEquals(jpeg(8), capped.frames.jpegs.last())

        // 中间混进非 JPEG 的帧：丢掉；剩下的仍然最多 8 帧。
        val mixed = mutableListOf<ByteArray>()
        for (index in 1..6) {
            mixed += jpeg(index)
            mixed += byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte()) // PNG 头，必须被丢掉
        }
        mixed += (7..9).map { jpeg(it) }

        val filtered = MediaUnderstanding(store, FrameExtractor { _, _, _, _ -> mixed })
            .watch(sessionId, mediaId) as MediaLookup.Video
        assertEquals(8, filtered.frames.frameCount)
        for (frame in filtered.frames.jpegs) {
            assertEquals(0xFF, frame[0].toInt() and 0xFF)
            assertEquals(0xD8, frame[1].toInt() and 0xFF)
        }
        assertArrayEquals(jpeg(8), filtered.frames.jpegs.last())
    }

    @Test
    fun `watch fails verbatim when no decoder is available`() {
        val sessionId = newSession("视频")
        val mediaId = videoMedia(MediaMime.VIDEO_MP4, videoBytes)

        var extractCalls = 0
        val unavailable = object : FrameExtractor {
            override fun available(): Boolean = false

            override fun extract(video: ByteArray, mime: String, maxFrames: Int, maxEdge: Int): List<ByteArray> {
                extractCalls += 1
                return emptyList()
            }
        }

        val result = MediaUnderstanding(store, unavailable).watch(sessionId, mediaId)
        assertEquals("服务器没有视频解码器，看不了视频", reasonOf(result))
        assertEquals(0, extractCalls)

        // 显式指定一个不存在的 ffmpeg 路径：必须明确「没有解码器」，不能悄悄用别的实现。
        assertFalse(FfmpegFrameExtractor("/definitely/not/here/ffmpeg").available())
    }

    @Test
    fun `watch fails when the extractor produces no frames`() {
        val sessionId = newSession("视频")
        val mediaId = videoMedia(MediaMime.VIDEO_MP4, videoBytes)

        assertEquals(
            "没能从这段视频里抽出画面",
            reasonOf(MediaUnderstanding(store, fakeFrames()).watch(sessionId, mediaId))
        )
        // 全是非 JPEG 的帧和「一帧都没有」是同一件事：不能把垃圾塞给模型。
        val pngOnly = arrayOf(byteArrayOf(0x89.toByte(), 0x50.toByte()), byteArrayOf(1, 2, 3))
        assertEquals(
            "没能从这段视频里抽出画面",
            reasonOf(MediaUnderstanding(store, fakeFrames(*pngOnly)).watch(sessionId, mediaId))
        )
    }

    @Test
    fun `watch rejects non video media`() {
        val sessionId = newSession("视频")
        val understanding = MediaUnderstanding(store, fakeFrames())
        val image = store.saveMedia(MediaKind.CHAT_IMAGE, personaId, MediaMime.JPEG, byteArrayOf(21, 22)).item.id
        val audio = audioMedia(MediaMime.M4A, byteArrayOf(23, 24))

        assertEquals("这不是视频", reasonOf(understanding.watch(sessionId, image)))
        assertEquals("这不是视频", reasonOf(understanding.watch(sessionId, audio)))
    }

    @Test
    fun `watch reports a missing file`() {
        val sessionId = newSession("视频")
        val videoId = videoMedia(MediaMime.VIDEO_MP4, byteArrayOf(14, 15, 16))
        Files.deleteIfExists(store.mediaDirectory.resolve("$videoId.mp4"))

        assertEquals("视频文件不见了", reasonOf(MediaUnderstanding(store, fakeFrames()).watch(sessionId, videoId)))
    }

    @Test
    fun `blocked messages are never used as the recent media`() {
        val audioSession = newSession("语音")
        val blockedAudio = audioMedia(MediaMime.M4A, byteArrayOf(7, 7, 7))
        attach(audioSession, blockedAudio, blocked = true)
        assertEquals(
            "这段对话里没有可以听的语音",
            reasonOf(MediaUnderstanding(store, fakeFrames()).listen(audioSession, null))
        )

        val videoSession = newSession("视频")
        val blockedVideo = videoMedia(MediaMime.VIDEO_MP4, byteArrayOf(8, 8, 8))
        attach(videoSession, blockedVideo, blocked = true)
        assertEquals(
            "这段对话里没有可以看的视频",
            reasonOf(MediaUnderstanding(store, fakeFrames()).watch(videoSession, null))
        )
    }

    @Test
    fun `watch without an id picks the most recent video message`() {
        val sessionId = newSession("视频")
        val older = videoMedia(MediaMime.QUICKTIME, byteArrayOf(31, 31, 31))
        val newer = videoMedia(MediaMime.VIDEO_MP4, videoBytes)
        attach(sessionId, older)
        attach(sessionId, newer)

        val frames = listOf(jpeg(1), jpeg(2))
        val video = MediaUnderstanding(store, fakeFrames(*frames.toTypedArray()))
            .watch(sessionId, null) as MediaLookup.Video
        assertEquals(newer, video.frames.mediaId)
        assertEquals(2, video.frames.frameCount)
    }

    // ------------------------------------------------------------------ 真实 ffmpeg（没有就跳过）

    @Test
    fun `real ffmpeg smoke test extracts jpeg frames`() {
        val extractor = FfmpegFrameExtractor()
        // 本机没装 ffmpeg：整例跳过（真实抽帧不是 CI 的硬前提，明确失败的是 watch 的行为）。
        assumeTrue("本机没有 ffmpeg，跳过真实抽帧冒烟", extractor.available())
        val binary = extractor.resolvedBinary()!!

        val workDir = Files.createTempDirectory("yap-watch-smoke")
        try {
            val output = workDir.resolve("out.mp4")
            val process = ProcessBuilder(
                binary,
                "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi",
                "-i", "testsrc=duration=1:size=160x120:rate=10",
                "-pix_fmt", "yuv420p",
                output.toString()
            ).redirectErrorStream(true).start()

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            val log = if (finished) process.inputStream.readBytes().toString(Charsets.UTF_8) else "生成超时"
            if (!finished) process.destroyForcibly()
            assumeTrue("ffmpeg 生成测试视频失败，跳过：$log", finished && process.exitValue() == 0)

            val video = Files.readAllBytes(output)
            val frames = extractor.extract(
                video,
                MediaMime.VIDEO_MP4,
                MediaUnderstanding.MAX_FRAMES,
                MediaUnderstanding.MAX_FRAME_EDGE
            )
            assertTrue("1 秒 testsrc 至少应该抽到一帧，实际 ${frames.size}", frames.isNotEmpty())
            assertTrue("最多 ${MediaUnderstanding.MAX_FRAMES} 帧，实际 ${frames.size}", frames.size <= MediaUnderstanding.MAX_FRAMES)
            for (frame in frames) {
                assertTrue("帧太小，不像 JPEG：${frame.size} 字节", frame.size > 2)
                assertEquals(0xFF, frame[0].toInt() and 0xFF)
                assertEquals(0xD8, frame[1].toInt() and 0xFF)
            }

            // 再走一遍完整链路：存进 FileStore → watch(mediaId) → 拿到的 VideoFrames 与直接抽帧一致。
            val sessionId = newSession("视频冒烟")
            val mediaId = videoMedia(MediaMime.VIDEO_MP4, video)
            val result = MediaUnderstanding(store, FfmpegFrameExtractor()).watch(sessionId, mediaId)
            val seen = result as MediaLookup.Video
            assertEquals(frames.size, seen.frames.frameCount)
            assertEquals(MediaMime.JPEG, seen.frames.mime)
        } finally {
            TestSupport.deleteRecursively(workDir)
        }
    }
}
