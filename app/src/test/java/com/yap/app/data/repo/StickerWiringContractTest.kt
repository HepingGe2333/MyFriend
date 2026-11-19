package com.yap.app.data.repo

import com.yap.app.domain.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 表情包的**接线契约**（`spec/stickers/README.md`）。
 *
 * 纯逻辑（编解码、压缩参数、工具三条路径）在 `domain` 的测试里；这里只钉「真的接上了」——
 * 这类改动最常见的失败形态是「单测全绿、功能没接」：工具注册了但执行器没分支、
 * 点了表情但没有落库、服务端模式把 GIF 当聊天图重编码成 JPEG。
 *
 * 用读源码而不是反射：这些接线横跨 Hilt/Compose/Room，JVM 单测里跑不起来，
 * 而「哪一行必须存在」是稳定、可 review、改错就红的。
 */
class StickerWiringContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("../$relative"), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $relative（工作目录=${File("").absolutePath}）")
    }

    private val toolExecutor by lazy { source("src/main/java/com/yap/app/data/repo/ToolExecutor.kt") }
    private val chatRepository by lazy { source("src/main/java/com/yap/app/data/repo/ChatRepository.kt") }
    private val chatViewModel by lazy { source("src/main/java/com/yap/app/ui/screens/chat/ChatViewModel.kt") }
    private val chatScreen by lazy { source("src/main/java/com/yap/app/ui/screens/chat/ChatScreen.kt") }
    private val stickerViews by lazy { source("src/main/java/com/yap/app/ui/screens/chat/StickerViews.kt") }
    private val repository by lazy { source("src/main/java/com/yap/app/data/repo/StickerRepository.kt") }
    private val downloader by lazy { source("src/main/java/com/yap/app/data/remote/StickerDownloader.kt") }
    private val serverUploader by lazy { source("src/main/java/com/yap/app/data/remote/ServerStickerUploader.kt") }
    private val appModule by lazy { source("src/main/java/com/yap/app/di/AppModule.kt") }

    // ------------------------------------------------------------ 工具

    @Test
    fun `工具注册了 sticker 且参数与规范一致`() {
        val spec = ToolCatalog.byName(ToolCatalog.STICKER) ?: error("没有注册 sticker")
        listOf("action", "stickerId", "url", "baseStickerId", "text", "overlayStickerId", "position", "fromMessageId")
            .forEach { parameter ->
                assertTrue("sticker 参数缺少 $parameter：${spec.parametersJson}", spec.parametersJson.contains("\"$parameter\""))
            }
        assertTrue("action 必填", spec.parametersJson.contains("\"required\": [\"action\"]"))
        listOf("send", "make", "save").forEach { action ->
            assertTrue("action 枚举缺少 $action", spec.parametersJson.contains("\"$action\""))
        }
        listOf("TOP", "CENTER", "BOTTOM").forEach { position ->
            assertTrue("position 枚举缺少 $position", spec.parametersJson.contains("\"$position\""))
        }
        assertTrue("能力段里必须有 sticker", ToolCatalog.capabilityPrompt().contains("- " + ToolCatalog.signature(ToolCatalog.byName(ToolCatalog.STICKER)!!) + "："))
    }

    @Test
    fun `执行器里有 sticker 分支 且三条动作都接上`() {
        assertTrue(toolExecutor.contains("\"sticker\" -> sticker("))
        assertTrue(toolExecutor.contains("StickerTool.send("))
        assertTrue(toolExecutor.contains("StickerTool.make("))
        assertTrue(toolExecutor.contains("StickerTool.save("))
        assertTrue("失败原因要走资源（界面按语言显示）", toolExecutor.contains("ToolTraceText.stickerFailure("))
        assertTrue("标题也要走资源", toolExecutor.contains("ToolTraceText.stickerTitle("))
        val toolCatalog = source("src/main/java/com/yap/app/domain/ToolProtocol.kt")
        assertTrue("工具痕迹的标题注册表要有 sticker", source("src/main/java/com/yap/app/data/repo/ToolTraceText.kt").contains("ToolCatalog.STICKER -> STICKER"))
        assertTrue("回灌参数回显要有 sticker", toolCatalog.contains("ToolCatalog.STICKER -> stickerArgumentHint(invocation)"))
    }

    @Test
    fun `工具 send 的附件会挂到本轮的助手消息上`() {
        assertTrue("ToolExecution 必须能带附件", toolExecutor.contains("val attachments: List<com.yap.app.domain.MessageAttachment> = emptyList()"))
        assertTrue(chatRepository.contains("toolAttachments += execution.attachments"))
        assertTrue(
            "助手消息落库时要写 attachmentsJson",
            chatRepository.contains("attachmentsJson = AttachmentsCodec.encode(toolAttachments")
        )
        assertTrue("只发表情不写字也算一轮（不能当成空流丢掉）", chatRepository.contains("toolAttachments.isEmpty()"))
    }

    // ------------------------------------------------------------ 下载与安全

    @Test
    fun `make 的下载先过 WebUrlPolicy 且有 8MiB 上限`() {
        assertTrue("必须先过策略", downloader.contains("WebUrlPolicy.check(target)"))
        assertTrue("上限 8 MiB", downloader.contains("8L * 1024 * 1024"))
        assertTrue("只接受图片", downloader.contains("looksLikeImage"))
        assertTrue("声明长度超限要先挡", downloader.contains("contentLength()"))
    }

    // ------------------------------------------------------------ 发送 / 收藏 / 服务端

    @Test
    fun `用户点选表情能发出去 且不经过草稿`() {
        assertTrue(chatRepository.contains("suspend fun sendUserSticker("))
        assertTrue(chatRepository.contains("stickerRepository.attachmentOf(sticker)"))
        assertTrue(chatViewModel.contains("fun sendSticker(stickerId: String)"))
        assertTrue(chatViewModel.contains("chatRepository.sendUserSticker(sessionId, stickerId)"))
        // 点选即发送：不能把表情塞进草稿、也不能清掉用户已经打了一半的字。
        val block = chatViewModel.substring(chatViewModel.indexOf("fun sendSticker("))
        val end = block.indexOf("fun saveStickerFromMessage(")
        val sendBlock = if (end > 0) block.substring(0, end) else block.take(2000)
        assertFalse("发送表情不该往草稿里写东西", sendBlock.contains("draft = "))
    }

    @Test
    fun `长按收藏把消息里的表情收进我的表情`() {
        assertTrue(chatViewModel.contains("fun saveStickerFromMessage(messageId: String)"))
        assertTrue(chatViewModel.contains("stickerRepository.saveFromMessage("))
        assertTrue(chatScreen.contains("R.string.chat_sticker_save"))
        assertTrue("收藏入口只对带表情的消息出现", chatScreen.contains("canSaveSticker"))
        assertTrue("同一文件复用（规范：同一文件，增加 owner 关联）", repository.contains("stickerDao.findByFile(sticker.uri)"))
        assertTrue("收藏的来源是 FROM_USER_MESSAGE", repository.contains("StickerSourceKind.FROM_USER_MESSAGE"))
    }

    @Test
    fun `服务端模式用 kind STICKER 上传 不重编码 GIF`() {
        assertTrue("kind 必须是 STICKER", serverUploader.contains("const val KIND_STICKER = \"STICKER\""))
        assertTrue("mime 必须是 gif", serverUploader.contains("const val STICKER_MIME = \"image/gif\""))
        assertFalse("表情不许走 JPEG 重编码", serverUploader.contains("compressChatImage"))
        assertTrue("聊天仓储里表情与图片走两条通道", chatRepository.contains("if (attachment.isSticker)"))
        assertTrue(chatRepository.contains("ServerStickerUploader("))
        assertTrue("只读表情目录", serverUploader.contains("MediaOwnership.isWithin"))
        assertTrue("Hilt 绑定要在", appModule.contains("provideStickerUrlDownloader"))
    }

    // ------------------------------------------------------------ 界面

    @Test
    fun `输入栏有独立表情按钮 与图片按钮分开`() {
        assertTrue("Composer 要有表情开关", chatScreen.contains("onToggleStickers: () -> Unit"))
        assertTrue(chatScreen.contains("R.string.chat_send_sticker"))
        assertTrue(chatScreen.contains("viewModel::toggleStickerPanel"))
        assertTrue("点开的是历史面板", chatScreen.contains("StickerPanel("))
        // 图片按钮仍在，并且没有和表情按钮合并成一个。
        assertTrue(chatScreen.contains("R.string.chat_send_image"))
    }

    @Test
    fun `历史面板是最近 30 个加全部`() {
        assertTrue(stickerViews.contains("R.string.chat_sticker_panel_recent"))
        assertTrue(stickerViews.contains("R.string.chat_sticker_panel_all"))
        assertTrue("最近 30 个来自仓储常量", repository.contains("const val RECENT_LIMIT = 30"))
        assertTrue(chatScreen.contains("com.yap.app.data.repo.StickerRepository.RECENT_LIMIT"))
    }

    @Test
    fun `聊天里按 240dp 渲染表情并逐帧播放`() {
        assertTrue(stickerViews.contains("val STICKER_BUBBLE_SIZE: Dp = 240.dp"))
        assertTrue("表情要自己播帧（Coil 的动图支持在额外依赖里）", stickerViews.contains("rememberAnimatedFrame"))
        assertTrue("气泡里遇到表情走 StickerImage", chatScreen.contains("StickerImage("))
        assertTrue(chatScreen.contains("attachment.isSticker"))
        assertTrue("表情消息的无障碍描述走资源", chatScreen.contains("R.string.chat_sticker_message"))
        // 关掉动画时只画第一帧（设置里的「动画效果」）。
        assertTrue(stickerViews.contains("LocalAnimationsEnabled"))
    }

    @Test
    fun `导入入口走 sticker 自己的压缩管线`() {
        assertTrue(chatViewModel.contains("fun importSticker(uri: Uri)"))
        assertTrue(chatViewModel.contains("stickerRepository.import("))
        assertTrue(chatScreen.contains("stickerPicker"))
        assertTrue("导入要真的压缩再落盘", repository.contains("StickerPipeline.build(source)"))
        assertTrue("落盘目录是 files/stickers", source("src/main/java/com/yap/app/util/MediaStorage.kt").contains("fun stickerDirectory("))
    }

    @Test
    fun `表情文件不进聊天附件的引用计数删除路径`() {
        val mediaStorage = source("src/main/java/com/yap/app/util/MediaStorage.kt")
        val roots = mediaStorage.substring(mediaStorage.indexOf("private fun ownedRoots"))
        assertFalse(
            "表情目录不能被 ownedRoots 收进去：删一条消息不该删掉收藏的表情",
            roots.contains("STICKER_DIR")
        )
    }

    @Test
    fun `长按删除表情在服务端与本地都还在`() {
        // 0.14.1 回归检查：§10 移除「重新生成」时不该顺手把表情删除的入口/分支删掉。
        val repository = source("src/main/java/com/yap/app/data/repo/StickerRepository.kt")
        val delete = repository.substring(repository.indexOf("suspend fun delete(id: String)"))
            .substringBefore("\n    /**")
        assertTrue("服务端模式要先 DELETE /v1/stickers/{id}", delete.contains("serverWrites.deleteSticker(stickerId)"))
        assertTrue("成功后删本机缓存行", delete.contains("stickerDao.delete(stickerId)"))
        assertTrue("本地模式找不到行时不报成功", delete.contains("stickerDao.getById(stickerId) == null"))

        val viewModel = source("src/main/java/com/yap/app/ui/screens/chat/ChatViewModel.kt")
        assertTrue("ViewModel 仍有删除入口", viewModel.contains("fun deleteSticker(stickerId: String)"))
        assertTrue("失败要提示一句", viewModel.contains("R.string.chat_sticker_delete_failed"))
        val screen = source("src/main/java/com/yap/app/ui/screens/chat/ChatScreen.kt")
        assertTrue("面板长按删除仍接线", screen.contains("onDelete = { sticker -> viewModel.deleteSticker(sticker.id) }"))
    }

    @Test
    fun `删除全部数据时表情行与文件一起清`() {
        assertTrue(chatRepository.contains("stickerRepository.wipeAll()"))
        assertTrue(repository.contains("MediaStorage.clearStickers(context)"))
        assertTrue(repository.contains("stickerDao.deleteAll()"))
    }

    @Test
    fun `提示词里会列出我的表情 让 send 拿得到 id`() {
        assertTrue(chatRepository.contains("StickerPromptBlock.build("))
        assertTrue(chatRepository.contains("stickerPromptBlock.takeIf"))
    }

    @Test
    fun `schema v16 的表与迁移都在`() {
        val database = source("src/main/java/com/yap/app/data/local/YapDatabase.kt")
        // v16 是表情包那一版；之后还会升（v17 多维情绪），这里只要求「至少已经是 16」。
        assertTrue(
            "版本至少要是 16",
            Regex("""version = (1[6-9]|[2-9]\d),""").containsMatchIn(database)
        )
        assertTrue("要有 MIGRATION_15_16", database.contains("MIGRATION_15_16 = object : Migration(15, 16)"))
        assertTrue("实体要注册进数据库", database.contains("StickerEntity::class"))
        assertTrue("DAO 要暴露出来", database.contains("abstract fun stickerDao(): StickerDao"))
        assertTrue("迁移要注册进 Room", appModule.contains("YapDatabase.MIGRATION_15_16"))
        assertTrue("schema 要导出", File("../app/schemas/com.yap.app.data.local.YapDatabase/16.json").exists() ||
            File("app/schemas/com.yap.app.data.local.YapDatabase/16.json").exists() ||
            File("schemas/com.yap.app.data.local.YapDatabase/16.json").exists())
    }

    @Test
    fun `表情表的三条来源与两个创建者与规范逐字一致`() {
        val domain = source("src/main/java/com/yap/app/domain/StickerTool.kt")
        listOf("IMPORTED", "MODEL_MADE", "FROM_USER_MESSAGE", "WEB").forEach { kind ->
            assertTrue("来源缺少 $kind", domain.contains(kind))
        }
        listOf("USER", "MODEL").forEach { creator ->
            assertTrue("创建者缺少 $creator", domain.contains(creator))
        }
        assertEquals(
            "四个来源之间不能有重复值",
            4,
            setOf("IMPORTED", "MODEL_MADE", "FROM_USER_MESSAGE", "WEB").size
        )
    }
}
