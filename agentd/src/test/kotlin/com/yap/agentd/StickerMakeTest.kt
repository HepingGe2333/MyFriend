package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaKind
import com.yap.agentd.sticker.StickerComposeException
import com.yap.agentd.sticker.StickerComposer
import com.yap.agentd.sticker.StickerFrame
import com.yap.agentd.sticker.StickerGif
import com.yap.agentd.sticker.StickerInput
import com.yap.agentd.sticker.StickerSource
import com.yap.agentd.tools.ServerToolCatalog
import com.yap.agentd.tools.ToolExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import kotlin.random.Random

/**
 * 0.14.1 的 `sticker.make` 工具层（`spec/stickers`）：
 *
 * - 输出真的进了媒体表（`kind=STICKER`，`/v1/media/<id>` 能取到 GIF89a）；
 * - 基底/叠图可以来自已有表情，也可以来自 `url`（只允许公开 http(s)）；
 * - 素材不存在、URL 非法、合成失败都明确失败（不假装发过）；
 * - 照片感大动图不能把一档编码拖到几十秒（预量化回归用例）。
 */
class StickerMakeTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    /** 1x1 红色 GIF（StickerGif 自己编出来，保证是真 GIF89a）。 */
    private val gifBytes: ByteArray = StickerGif.encode(
        StickerSource(
            width = 1,
            height = 1,
            frames = listOf(StickerFrame(intArrayOf(0xFFFF0000.toInt()), delayMs = 100))
        )
    )

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-sticker-make")
        clock.now = 1_000_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun run(arguments: String): ToolExecutor.Outcome = ToolExecutor(store, clock = clock.asClock()).execute(
        ServerToolCatalog.STICKER,
        arguments,
        ToolExecutor.Context(personaId = personaId, sessionId = sessionId)
    )

    private fun existingSticker(): String = store.createSticker(
        personaId = personaId,
        mime = "image/gif",
        data = gifBytes,
        width = 1,
        height = 1,
        frames = 1
    ).id

    @Test
    fun `make stores a real gif in the media table as a sticker`() {
        val outcome = run("""{"action":"make","text":"好耶","position":"TOP"}""")
        assertTrue(outcome.contentForModel, outcome.ok)
        val id = outcome.contentForModel.substringAfter("做好了（id=").substringBefore("）")
        val media = store.media(id)
        assertTrue("必须进媒体表", media != null)
        assertEquals(MediaKind.STICKER, media!!.kind)
        assertEquals("image/gif", media.mime)
        val bytes = store.mediaBytes(id)!!
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals("表情行与媒体行同 id", id, store.sticker(id)!!.id)
        // 尺寸/字节上限（spec/stickers）。
        assertTrue(maxOf(store.sticker(id)!!.width, store.sticker(id)!!.height) <= 320)
        assertTrue(bytes.size <= 512 * 1024)
    }

    @Test
    fun `make can use existing stickers as base and overlay`() {
        val base = existingSticker()
        val overlay = existingSticker()
        val outcome = run(
            """{"action":"make","baseStickerId":"$base","overlayStickerId":"$overlay","text":"叠一下","position":"BOTTOM"}"""
        )
        assertTrue(outcome.contentForModel, outcome.ok)
        assertTrue(outcome.contentForModel.contains("做好了（id="))

        assertFalse(run("""{"action":"make","baseStickerId":"nope","text":"x"}""").ok)
        assertFalse(run("""{"action":"make","overlayStickerId":"nope","text":"x"}""").ok)
    }

    @Test
    fun `make rejects unsafe urls without downloading anything`() {
        for (url in listOf("file:///etc/passwd", "http://127.0.0.1:1/x.gif", "not a url")) {
            val outcome = run("""{"action":"make","url":"$url","text":"x"}""")
            assertFalse("$url 不能被下载：${outcome.contentForModel}", outcome.ok)
        }
        // 本地服务器（回环地址）也被 WebUrlPolicy 拒绝：不在测试里开假 HTTP 服务。
        assertTrue(run("""{"action":"make","url":"http://localhost/x.gif"}""").contentForModel.contains("不能下载本机/内网地址"))
    }

    @Test
    fun `photo-like animation does not take tens of seconds per encode`() {
        // 40 万种颜色的 8 帧动图（照片感素材）：没有预量化时 StickerGif 的映射缓存会退化，
        // 一档就要几十秒；这里断言整次合成在合理时间内完成（预量化阈值 4096 色）。
        val random = Random(7)
        val width = 200
        val height = 200
        val frames = (0 until 8).map { index ->
            val pixels = IntArray(width * height) { i ->
                val r = random.nextInt(256)
                val g = random.nextInt(256)
                val b = (i + index * 13) and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            StickerFrame(pixels, delayMs = 100)
        }
        val base = StickerInput(StickerGif.encode(StickerSource(width, height, frames), maxColors = 256), "image/gif")
        val started = System.nanoTime()
        val composed = StickerComposer.compose(base, null, "照片感", "CENTER")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("8 帧照片感动图合成耗时 ${elapsedMs}ms，太慢了（预量化回归？）", elapsedMs < 15_000)
        assertTrue(composed.gif.size <= 512 * 1024)
        assertTrue(StickerGif.decode(composed.gif) != null)
    }

    @Test
    fun `compose failure surfaces a clear tool failure`() {
        // 坏字节当作表情底：底层抛 StickerComposeException，工具回 ok=false + 中文原因。
        val broken = store.createSticker(personaId, "image/gif", gifBytes, 1, 1, 1)
        // 把文件删掉 → 素材找不到，明确失败（不是 500）。
        store.deleteMedia(broken.id, force = true)
        val outcome = run("""{"action":"make","baseStickerId":"${broken.id}","text":"x"}""")
        assertFalse(outcome.ok)
        assertTrue(outcome.contentForModel.contains("没有这个表情"))
        try {
            StickerComposer.compose(StickerInput(byteArrayOf(1, 2, 3), "image/gif"), null, null)
            fail("坏字节应当抛 StickerComposeException")
        } catch (expected: StickerComposeException) {
            assertEquals("invalid_image", expected.code)
        }
    }
}
