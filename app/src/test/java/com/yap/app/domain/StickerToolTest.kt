package com.yap.app.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型工具 `sticker` 的三条路径（`spec/stickers/README.md`「模型工具 sticker」）。
 *
 * 全部用**假工作区**：假下载器（固定字节）、假图片（几张纯色帧）、内存里的表情记录。
 * 这样能覆盖真正的逻辑分支（素材优先级、叠字/叠图顺序、失败原因），
 * 又不需要设备、网络、Room——工具的「接线」由单独的契约测试盯。
 */
/** 造一张纯色（可多帧）的假图：假工作区与断言共用。 */
private fun solid(width: Int, height: Int, color: Int, frames: Int = 1): StickerSource =
        StickerSource(
            width = width,
            height = height,
            frames = (0 until frames).map { index ->
            StickerFrame(IntArray(width * height) { color + index * 0x000101 }, 80)
        }
    )

class StickerToolTest {

    /** 内存版工作区：记录每一次副作用，方便断言「到底做了什么」。 */
    private class FakeWorkspace(
        private val stickers: MutableMap<String, StickerRecord> = mutableMapOf(),
        private val messages: MutableMap<String, List<StickerRecord>> = mutableMapOf(),
        private val downloadOk: Boolean = true,
        private val decodable: Boolean = true
    ) : StickerWorkspace {

        val saved = mutableListOf<Pair<StickerSourceKind, StickerCreator>>()
        val renderedTexts = mutableListOf<String>()
        val renderedOverlays = mutableListOf<Int>()
        var blankUsed = false
        private var nextId = 1

        override suspend fun findSticker(stickerId: String): StickerRecord? = stickers[stickerId]

        override suspend fun downloadImage(url: String): ByteArray? = if (downloadOk) byteArrayOf(1, 2, 3, 4) else null

        override suspend fun decodeImage(bytes: ByteArray): StickerSource? =
            if (decodable) solid(32, 32, 0xFF00AA00.toInt(), frames = 3) else null

        override suspend fun decodeSticker(sticker: StickerRecord): StickerSource? =
            if (decodable) solid(64, 64, 0xFFAA0000.toInt(), frames = sticker.frames) else null

        override suspend fun blankSource(): StickerSource {
            blankUsed = true
            return solid(320, 320, 0xFFFFFFFF.toInt())
        }

        override suspend fun renderText(source: StickerSource, text: String, position: StickerPosition): StickerSource {
            renderedTexts += "$text@$position"
            return source
        }

        override suspend fun renderOverlay(source: StickerSource, overlay: StickerSource, position: StickerPosition): StickerSource {
            renderedOverlays += overlay.frames.size
            return source
        }

        override suspend fun saveSticker(
            source: StickerSource,
            kind: StickerSourceKind,
            createdBy: StickerCreator,
            personaId: String?
        ): StickerRecord {
            saved += kind to createdBy
            val record = StickerRecord(
                id = "made-${nextId++}",
                file = "file:///tmp/made.gif",
                width = source.width,
                height = source.height,
                frames = source.frames.size,
                bytes = 1234,
                source = kind.name,
                createdBy = createdBy.name,
                createdAt = 1_700_000_000_000,
                personaId = personaId
            )
            stickers[record.id] = record
            return record
        }

        override suspend fun saveStickerFromMessage(messageId: String, personaId: String?): StickerRecord? {
            val sticker = messages[messageId]?.firstOrNull() ?: return null
            stickers[sticker.id] = sticker
            return sticker
        }
    }

    private fun record(id: String, frames: Int = 1, createdBy: StickerCreator = StickerCreator.USER) = StickerRecord(
        id = id,
        file = "file:///tmp/$id.gif",
        width = 64,
        height = 64,
        frames = frames,
        bytes = 900,
        source = StickerSourceKind.IMPORTED.name,
        createdBy = createdBy.name,
        createdAt = 1_700_000_000_000
    )

    // ------------------------------------------------------------ send

    @Test
    fun `send 用已有 id 发出去`() = runBlocking {
        val workspace = FakeWorkspace(stickers = mutableMapOf("s1" to record("s1", frames = 4)))
        val outcome = StickerTool.send(workspace, "s1")
        assertTrue("send 应该成功：$outcome", outcome is StickerToolOutcome.Sent)
        assertEquals("s1", (outcome as StickerToolOutcome.Sent).sticker.id)
        assertEquals("已发送表情", StickerTool.summary(outcome))
        assertTrue("回灌正文要告诉他接着把话说完", StickerTool.contentForModel(outcome).contains("接着"))
    }

    @Test
    fun `send 找不到 id 时给出人话`() = runBlocking {
        val outcome = StickerTool.send(FakeWorkspace(), "nope")
        assertTrue(outcome is StickerToolOutcome.Failed)
        assertEquals(StickerToolFailure.STICKER_NOT_FOUND, (outcome as StickerToolOutcome.Failed).reason)
        assertTrue(StickerTool.contentForModel(outcome).contains("找不到"))
    }

    @Test
    fun `send 没给 id 也是同一种失败`() = runBlocking {
        val outcome = StickerTool.send(FakeWorkspace(), "  ")
        assertEquals(StickerToolFailure.STICKER_NOT_FOUND, (outcome as StickerToolOutcome.Failed).reason)
    }

    // ------------------------------------------------------------ make

    @Test
    fun `make 下载 URL 后生成一张 WEB 表情`() = runBlocking {
        val workspace = FakeWorkspace()
        val outcome = StickerTool.make(
            workspace = workspace,
            request = StickerMakeRequest(url = "https://example.com/a.gif"),
            personaId = "persona-1"
        )
        assertTrue("make 应该成功：$outcome", outcome is StickerToolOutcome.Made)
        val made = outcome as StickerToolOutcome.Made
        assertTrue("纯下载的来源是 WEB", made.fromWeb)
        assertEquals(StickerSourceKind.WEB, workspace.saved.single().first)
        assertEquals(StickerCreator.MODEL, workspace.saved.single().second)
        assertEquals("persona-1", made.sticker.personaId)
        assertTrue(StickerTool.summary(outcome).contains("做好了"))
        assertTrue(StickerTool.summary(outcome).contains(made.sticker.id))
    }

    @Test
    fun `make 用已有表情当底再叠字 走 MODEL_MADE`() = runBlocking {
        val workspace = FakeWorkspace(stickers = mutableMapOf("base" to record("base", frames = 3)))
        val outcome = StickerTool.make(
            workspace = workspace,
            request = StickerMakeRequest(
                baseStickerId = "base",
                text = "笑死",
                position = StickerPosition.BOTTOM
            ),
            personaId = null
        )
        assertTrue(outcome is StickerToolOutcome.Made)
        assertTrue("叠过字就不是纯下载了", !(outcome as StickerToolOutcome.Made).fromWeb)
        assertEquals(StickerSourceKind.MODEL_MADE, workspace.saved.single().first)
        assertEquals(listOf("笑死@BOTTOM"), workspace.renderedTexts)
        assertEquals("底图的帧数要保留（3 帧）", 3, outcome.sticker.frames)
    }

    @Test
    fun `make 叠另一张表情 先图后字`() = runBlocking {
        val workspace = FakeWorkspace(
            stickers = mutableMapOf(
                "base" to record("base", frames = 2),
                "overlay" to record("overlay", frames = 5)
            )
        )
        val outcome = StickerTool.make(
            workspace = workspace,
            request = StickerMakeRequest(
                baseStickerId = "base",
                overlayStickerId = "overlay",
                text = "在吗",
                position = StickerPosition.TOP
            ),
            personaId = null
        )
        assertTrue(outcome is StickerToolOutcome.Made)
        assertEquals("叠图要被调用一次", listOf(5), workspace.renderedOverlays)
        assertEquals("叠字在叠图之后（字在最上面）", listOf("在吗@TOP"), workspace.renderedTexts)
    }

    @Test
    fun `make 只给 text 时用内置空白底`() = runBlocking {
        val workspace = FakeWorkspace()
        val outcome = StickerTool.make(
            workspace = workspace,
            request = StickerMakeRequest(text = "救命"),
            personaId = null
        )
        assertTrue(outcome is StickerToolOutcome.Made)
        assertTrue("没有素材时要用内置白底（meme 风格）", workspace.blankUsed)
        assertEquals(StickerSourceKind.MODEL_MADE, workspace.saved.single().first)
        assertEquals(listOf("救命@CENTER"), workspace.renderedTexts)
    }

    @Test
    fun `make 什么都没给时拒绝`() = runBlocking {
        val outcome = StickerTool.make(FakeWorkspace(), StickerMakeRequest(), null)
        assertEquals(StickerToolFailure.EMPTY_MAKE, (outcome as StickerToolOutcome.Failed).reason)
    }

    @Test
    fun `make 下载失败与图片读不出来分开报`() = runBlocking {
        val downloadFail = StickerTool.make(
            workspace = FakeWorkspace(downloadOk = false),
            request = StickerMakeRequest(url = "https://example.com/x.gif"),
            personaId = null
        )
        assertEquals(StickerToolFailure.DOWNLOAD_FAILED, (downloadFail as StickerToolOutcome.Failed).reason)

        val decodeFail = StickerTool.make(
            workspace = FakeWorkspace(decodable = false),
            request = StickerMakeRequest(url = "https://example.com/x.gif"),
            personaId = null
        )
        assertEquals(StickerToolFailure.IMAGE_UNREADABLE, (decodeFail as StickerToolOutcome.Failed).reason)
    }

    @Test
    fun `make 基底 id 不存在时不偷偷换成空白底`() = runBlocking {
        val workspace = FakeWorkspace()
        val outcome = StickerTool.make(
            workspace = workspace,
            request = StickerMakeRequest(baseStickerId = "missing", text = "嗯"),
            personaId = null
        )
        assertEquals(StickerToolFailure.STICKER_NOT_FOUND, (outcome as StickerToolOutcome.Failed).reason)
        assertTrue("失败时不许落库", workspace.saved.isEmpty())
    }

    // ------------------------------------------------------------ save

    @Test
    fun `save 把消息里的表情收进收藏`() = runBlocking {
        val workspace = FakeWorkspace(
            messages = mutableMapOf("m1" to listOf(record("s9", frames = 6, createdBy = StickerCreator.MODEL)))
        )
        val outcome = StickerTool.save(workspace, "m1", "persona-1")
        assertTrue(outcome is StickerToolOutcome.Saved)
        assertEquals("s9", (outcome as StickerToolOutcome.Saved).sticker.id)
        assertEquals("已收藏表情", StickerTool.summary(outcome))
    }

    @Test
    fun `save 消息里没有表情时说清楚`() = runBlocking {
        val workspace = FakeWorkspace(messages = mutableMapOf("m1" to emptyList()))
        val outcome = StickerTool.save(workspace, "m1", null)
        assertEquals(StickerToolFailure.NO_STICKER_IN_MESSAGE, (outcome as StickerToolOutcome.Failed).reason)
    }

    @Test
    fun `save 没给消息 id 也是人话`() = runBlocking {
        val outcome = StickerTool.save(FakeWorkspace(), "   ", null)
        assertEquals(StickerToolFailure.MESSAGE_NOT_FOUND, (outcome as StickerToolOutcome.Failed).reason)
    }

    // ------------------------------------------------------------ 位置解析 / 提示词

    @Test
    fun `position 解析认中英文 认不出就居中`() {
        assertEquals(StickerPosition.TOP, StickerPosition.parse("TOP"))
        assertEquals(StickerPosition.BOTTOM, StickerPosition.parse(" bottom "))
        assertEquals(StickerPosition.TOP, StickerPosition.parse("上方"))
        assertEquals(StickerPosition.CENTER, StickerPosition.parse("??? "))
        assertEquals(StickerPosition.CENTER, StickerPosition.parse(null))
    }

    @Test
    fun `每个失败原因都有给模型看的一句话`() {
        StickerToolFailure.entries.forEach { reason ->
            val text = StickerTool.failureText(reason)
            assertTrue("失败原因 $reason 必须有回灌文案", text.isNotBlank())
            assertTrue("回灌文案不该带英文枚举名：$reason -> $text", !text.contains(reason.name))
        }
    }

    @Test
    fun `我的表情清单给出 id 与动静态 但不泄露文件路径`() {
        val block = StickerPromptBlock.build(
            listOf(
                record("s1", frames = 12, createdBy = StickerCreator.MODEL),
                record("s2")
            )
        )
        assertTrue(block.contains("# 我的表情"))
        assertTrue(block.contains("s1"))
        assertTrue(block.contains("动图（12 帧）"))
        assertTrue(block.contains("静态图"))
        assertTrue(block.contains("你做的"))
        assertTrue("不许把本机路径写进提示词", !block.contains("file://"))
        assertTrue("超过 20 张要截断", StickerPromptBlock.build((1..30).map { record("s$it") }).lines().size <= 24)
    }

    @Test
    fun `没有表情时清单是空串`() {
        assertEquals("", StickerPromptBlock.build(emptyList()))
    }

    @Test
    fun `附件里的表情能被认出来 且与普通图片区分`() {
        val sticker = MessageAttachment(uri = "file:///tmp/a.gif", mime = "image/gif", kind = "STICKER")
        assertTrue(sticker.isSticker)
        assertTrue("表情也是图片（要能进 isImage 的图片链路）", sticker.isImage)
        assertTrue("但它不是普通聊天图片", !sticker.isChatImage)

        val gifWithoutKind = MessageAttachment(uri = "file:///tmp/b.gif", mime = "image/gif")
        assertTrue("老客户端只给 mime 也算表情", gifWithoutKind.isSticker)

        val photo = MessageAttachment(uri = "file:///tmp/c.jpg", mime = "image/jpeg")
        assertTrue(photo.isChatImage)
        assertTrue(!photo.isSticker)

        // 编码往返：kind 要能存住；老 JSON（没有 kind）解出来是 null，不能变成 STICKER。
        val encoded = AttachmentsCodec.encode(listOf(sticker, photo))!!
        val decoded = AttachmentsCodec.decode(encoded)
        assertEquals(2, decoded.size)
        assertTrue(decoded[0].isSticker)
        assertEquals("STICKER", decoded[0].kind)
        assertNull(decoded[1].kind)
        val legacy = AttachmentsCodec.decode("""[{"uri":"file:///tmp/x.jpg","mime":"image/jpeg"}]""")
        assertEquals(1, legacy.size)
        assertNull("老数据没有 kind", legacy[0].kind)
        assertTrue(!legacy[0].isSticker)
    }

    @Test
    fun `表情的附件构造用 gif 与 STICKER 标记`() {
        val record = record("s1", frames = 3)
        val attachment = MessageAttachment(
            uri = record.file,
            mime = RenderMime.GIF,
            width = record.width,
            height = record.height,
            kind = MessageAttachment.KIND_STICKER
        )
        assertNotNull(attachment)
        assertTrue(attachment.isSticker)
        assertEquals("image/gif", attachment.mime)
    }
}
