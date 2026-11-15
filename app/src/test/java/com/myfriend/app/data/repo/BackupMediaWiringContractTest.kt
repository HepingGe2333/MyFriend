package com.myfriend.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份 v3（图片随备份走）的**接线契约**。
 *
 * 纯逻辑部分（base64 / 打包 / 落盘 / 附件重写）在 `BackupMediaTest` 里有真单测；
 * 这里只钉住「BackupRepository 真的把那套逻辑接上了」——这类问题最典型的形态是
 * 「测试全绿、发布包里图片还是没带走」。
 */
class BackupMediaWiringContractTest {

    private val repo get() = source("src/main/java/com/myfriend/app/data/repo/BackupRepository.kt")
    private val codec get() = source("src/main/java/com/myfriend/app/domain/BackupCodec.kt")
    private val media get() = source("src/main/java/com/myfriend/app/domain/BackupMedia.kt")
    private val storage get() = source("src/main/java/com/myfriend/app/util/MediaStorage.kt")

    @Test
    fun `格式版本是 v3 且顶层有 media`() {
        assertTrue("BackupCodec 的版本常量要改成 3", codec.contains("BACKUP_FORMAT_VERSION: Int = 3"))
        assertTrue("顶部结构要有 media 数组", codec.contains("val media: List<BackupMedia> = emptyList()"))
        assertTrue("media 的 7 个字段", listOf(
            "val id: String", "val kind: String", "val personaId: String?", "val fileName: String",
            "val mime: String", "val bytes: Int", "val data: String"
        ).all { codec.contains(it) })
    }

    @Test
    fun `导出真的收集并打包图片`() {
        val export = bodyOf("suspend fun export(")
        assertTrue("导出要收集候选图片", export.contains("BackupMediaCandidate("))
        assertTrue("要按上限打包", export.contains("BackupMediaPacker.pack(candidates)"))
        assertTrue("media 要写进 bundle", export.contains("media = packed.media"))
        assertTrue("消息附件只写打包成功的文件名", export.contains("toBackupMessage(chatImageNames)"))
        assertTrue("超限要回报给界面", export.contains("BackupMediaWarning.ExportSkipped("))
        assertTrue("只读属于 App 媒体目录里的文件", repo.contains("MediaStorage.ownedFile("))
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
        assertTrue("聊天附件按 media 重建", import.contains("BackupAttachmentsPortable.restore("))
        assertTrue("『我的头像』要写回设置", import.contains("setUserAvatarUri("))
        assertTrue("替换后按引用清理旧头像", import.contains("clearUnreferencedAvatars("))
        assertTrue("替换后清旧聊天图但保住这次新写的", import.contains("clearChatImagesExcept("))
        // 不能反过来把「chat-* 全删」的旧清理直接搬过来：那会删掉刚恢复的新图。
        assertFalse(
            "替换模式不能调用会清空全部 chat-* 的旧清理",
            import.contains("runCatching { dataEraser.commitFiles(")
        )
    }

    @Test
    fun `媒体落盘有目录归属守卫 且不拿备份文件名拼路径`() {
        assertTrue("落盘要过 MediaOwnership", media.contains("MediaOwnership.isWithin(target"))
        assertTrue("备份文件名只做关联键", media.contains("newName(prefix, extension)"))
        assertTrue("设备路径解析要过归属守卫", storage.contains("fun ownedFile("))
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
        return candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()
}
