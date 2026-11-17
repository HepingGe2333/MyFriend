package com.yap.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份 v3–v6（媒体随备份走 + 导入压缩 + 表情/音视频/日记）的**接线契约**。
 *
 * 纯逻辑部分（base64 / 打包 / 落盘 / 附件重写）在 `BackupMediaTest` / `BackupV6Test` 里有真单测；
 * 这里只钉住「BackupRepository 真的把那套逻辑接上了」——这类问题最典型的形态是
 * 「测试全绿、发布包里图片还是没带走」。
 */
class BackupMediaWiringContractTest {

    private val repo get() = source("src/main/java/com/yap/app/data/repo/BackupRepository.kt")
    private val codec get() = source("src/main/java/com/yap/app/domain/BackupCodec.kt")
    private val media get() = source("src/main/java/com/yap/app/domain/BackupMedia.kt")
    private val storage get() = source("src/main/java/com/yap/app/util/MediaStorage.kt")

    @Test
    fun `格式版本是 v6 且顶层有 media stickers diaryNotes`() {
        assertTrue("BackupCodec 的版本常量要改成 6", codec.contains("BACKUP_FORMAT_VERSION: Int = 6"))
        assertTrue("v4 的配置段要带 webToolsProvider", codec.contains("val webToolsProvider: String = WebToolsProviders.MESSAGEAPI"))
        assertTrue("顶部结构要有 media 数组", codec.contains("val media: List<BackupMedia> = emptyList()"))
        assertTrue("v6 顶部要有 stickers 数组", codec.contains("val stickers: List<BackupSticker> = emptyList()"))
        assertTrue("v6 顶部要有 diaryNotes 数组", codec.contains("val diaryNotes: List<BackupDiaryNote> = emptyList()"))
        assertTrue("media 的 7 个字段", listOf(
            "val id: String", "val kind: String", "val personaId: String?", "val fileName: String",
            "val mime: String", "val bytes: Int", "val data: String"
        ).all { codec.contains(it) })
    }

    @Test
    fun `导出真的收集并打包五种媒体 以及表情行与日记`() {
        val export = bodyOf("suspend fun export(")
        assertTrue("导出要收集候选媒体", export.contains("BackupMediaCandidate("))
        assertTrue("要按上限打包", export.contains("BackupMediaPacker.pack(candidates)"))
        assertTrue("media 要写进 bundle", export.contains("media = packed.media"))
        assertTrue("消息附件只写打包成功的媒体文件名", export.contains("toBackupMessage(mediaFileNames)"))
        assertTrue("超限要回报给界面", export.contains("BackupMediaWarning.ExportSkipped("))
        assertTrue("只读属于 App 媒体目录里的文件", repo.contains("MediaStorage.ownedFile("))
        // v6：五种 kind 都要真的被收集。
        listOf(
            "BackupMediaKind.STICKER", "BackupMediaKind.AUDIO", "BackupMediaKind.VIDEO",
            "MediaStorage.stickerDirectory(", "MediaStorage.audioDirectory(", "MediaStorage.videoDirectory("
        ).forEach { marker -> assertTrue("导出没有接上 $marker", export.contains(marker)) }
        assertTrue("表情行要导出", export.contains("stickerDao.observeAll()"))
        assertTrue("日记行要导出", export.contains("diaryNoteDao.allForPersona("))
    }

    @Test
    fun `导入先落盘媒体 再落库引用 并在失败时回滚`() {
        val import = bodyOf("suspend fun importBundle(")
        val restoreIndex = import.indexOf("BackupMediaRestorer.restore(")
        val transactionIndex = import.indexOf("database.withTransaction {")
        assertTrue("导入要调用媒体落盘", restoreIndex >= 0)
        assertTrue("媒体必须在写库事务之前落盘", restoreIndex < transactionIndex)
        assertTrue("失败要把这次写的文件删掉", import.contains("restoredMedia.writtenFiles.forEach"))
        assertTrue("头像引用用恢复后的本机路径", import.contains("restoredMedia.avatarsByFileName"))
        assertTrue("附件按 media 重建", import.contains("BackupAttachmentsPortable.restore("))
        assertTrue("『我的头像』要写回设置", import.contains("setUserAvatarUri("))
        assertTrue("替换后按引用清理旧头像", import.contains("clearUnreferencedAvatars("))
        assertTrue("替换后清旧聊天图但保住这次新写的", import.contains("clearChatImagesExcept("))
        // v6：表情行/日记行要落库，且替换模式先清空再插入（顺序错了会把刚接回来的删掉）。
        assertTrue("表情行要落库", import.contains("stickerDao.insert("))
        assertTrue("日记行要落库", import.contains("diaryNoteDao.insert("))
        assertTrue("替换模式要清掉旧表情行", import.contains("stickerDao.deleteAll()"))
        assertTrue("替换模式要清掉旧日记行", import.contains("diaryNoteDao.deleteAll()"))
        assertTrue(
            "表情清空必须发生在插入之前",
            import.indexOf("stickerDao.deleteAll()") < import.indexOf("stickerDao.insert(")
        )
        // 不能反过来把「chat-* 全删」的旧清理直接搬过来：那会删掉刚恢复的新图。
        assertFalse(
            "替换模式不能调用会清空全部 chat-* 的旧清理",
            import.contains("runCatching { dataEraser.commitFiles(")
        )
    }

    @Test
    fun `媒体落盘有目录归属守卫 且不拿备份文件名拼路径`() {
        assertTrue("落盘要过 MediaOwnership", media.contains("MediaOwnership.isWithin(target"))
        assertTrue("备份文件名只做关联键", media.contains("newName(prefix, outputExtension, outputBytes)"))
        assertTrue("设备路径解析要过归属守卫", storage.contains("fun ownedFile("))
        // v6：五个目录 + 与 MediaStorage 清理语义一致的前缀。
        listOf("AvatarPrefix", "ChatPrefix", "StickerPrefix", "AudioPrefix", "VideoPrefix")
            .forEach { prefix -> assertTrue("缺少落盘前缀 $prefix", media.contains("const val $prefix")) }
        assertTrue("语音前缀必须与 MediaStorage 对齐", media.contains("const val AudioPrefix = \"voice-\""))
        assertTrue("视频前缀必须与 MediaStorage 对齐", media.contains("const val VideoPrefix = \"video-\""))
    }

    @Test
    fun `导入真的接了媒体压缩与内容哈希去重`() {
        val import = bodyOf("suspend fun importBundle(")
        assertTrue(
            "导入必须把 MediaStorage 的压缩器传给 restorer",
            import.contains("compressor = { bytes, mime, kind -> MediaStorage.compressBackupImage(bytes, mime, kind) }")
        )
        assertTrue("压缩器要真的用 Android Bitmap 重编码", storage.contains("fun compressBackupImage("))
        assertTrue("透明图垫白底", storage.contains("flattenForJpeg(scaled)"))
        assertTrue("上限来自 BackupMediaLimits", storage.contains("BackupMediaLimits.outputLimit(kind)"))
        assertTrue("压缩质量先 85", storage.contains("intArrayOf(85, 80, 75, 70, 65, 60, 55, 50)"))
        // 去重：聊天图片用纯内容名（可以复用同一份文件），头像保留 UUID（避免删一个坏另一个）。
        assertTrue("聊天图按内容寻址命名", media.contains("\"\$prefix\$hash.\$extension\""))
        assertTrue("头像保留 UUID", media.contains("\"\$prefix\$hash-\${UUID.randomUUID()}.\$extension\""))
        assertTrue("存储选图也按内容哈希去重", storage.contains("val target = File(dir, \"chat-\$hash.\$extension\")"))
        // v6：GIF/音频/视频不能被当成图片送去压缩。
        assertTrue("只有头像与聊天图走压缩", media.contains("BackupMediaKind.isCompressible(kind)"))
    }

    /** 从 [marker] 起截到本类下一个成员声明之前（够用即可）。 */
    private fun bodyOf(marker: String): String {
        val text = repo
        val start = text.indexOf(marker)
        require(start >= 0) { "找不到 $marker" }
        val rest = text.substring(start + marker.length)
        val endTokens = listOf(
            "\n    /**\n     * 读进来的备份先解码",
            "\n    /**\n     * 从 JSON 文本",
            "\n    /**\n     * 把「他」收集成",
            "\n    /**\n     * 把已经解码好的备份",
            "\n    private companion object"
        ).map { rest.indexOf(it) }.filter { it > 0 }
        val end = endTokens.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()
}
