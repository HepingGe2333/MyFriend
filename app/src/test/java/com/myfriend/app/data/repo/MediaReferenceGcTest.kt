package com.myfriend.app.data.repo

import com.myfriend.app.util.MediaFileGc
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 0.12 媒体去重的收尾：**只有没人再引用时才删文件**。
 *
 * 缺陷复现路径：聊天图按内容寻址命名（`chat-<sha16>.<ext>`），同一张图只落一份。
 * 以前删掉引用它的一条消息 / 一段会话 / 一个人格，就把共享文件一起删了，另一条消息
 * 立刻变成「图片已不在本机」。
 *
 * 这里的测法：真文件（[TemporaryFolder]）+ 真 [MediaReferences]（引用来源换成内存假实现）。
 * 引用查询的 SQL 部分在 Room 里，纯 JVM 跑不了；但「先删行、再问引用、没引用才删」这套判断
 * 是本次修复的核心，用假来源能把它钉死（也能钉住 `excludingPersonaId` 的语义）。
 *
 * `LIKE` 转义的 SQL 接线由最后一个用例按源码契约钉住（真实 SQL 由 instrumented 用例覆盖）。
 */
class MediaReferenceGcTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `两条消息引用同一张图 删一条文件还在 删第二条才删`() = runBlocking {
        val image = temp.newFile("chat-1234567890abcdef.jpg")
        val source = FakeSource()
        // 同一张图被两个人的消息引用（去重是全局的，跨会话/跨人格共用一个文件）。
        source.attachments += "chat-1234567890abcdef.jpg" to "persona-a"
        source.attachments += "chat-1234567890abcdef.jpg" to "persona-b"
        val references = MediaReferences(source)

        // 删掉第一条消息（数据库行已经删掉）：还有一条引用它 → 文件必须留着。
        source.attachments.removeAt(0)
        assertFalse("还有一条消息引用，文件不能删", deleteIfUnreferenced(image, references))
        assertTrue("另一条消息的图还在", image.exists())

        // 删掉第二条消息：引用归零 → 这才该删。
        source.attachments.removeAt(0)
        assertTrue("最后一条引用没了，文件该删", deleteIfUnreferenced(image, references))
        assertFalse("文件应该真的删掉了", image.exists())
    }

    @Test
    fun `删人格时按排除这个人之后的口径判断`() = runBlocking {
        val image = temp.newFile("chat-fedcba0987654321.jpg")
        val source = FakeSource()
        source.attachments += "chat-fedcba0987654321.jpg" to "persona-a"
        source.attachments += "chat-fedcba0987654321.jpg" to "persona-b"
        val references = MediaReferences(source)

        // 删 persona-a：他自己的那条马上要被删掉，所以用「排除他」的口径——persona-b 还在用 → 留。
        assertFalse(
            "删一个人不能把他跟另一个人共用的图删掉",
            deleteIfUnreferenced(image, references, excludingPersonaId = "persona-a")
        )
        assertTrue(image.exists())

        // 只剩 persona-a 一个人引用时，删他就该回收。
        source.attachments.removeAt(1)
        assertTrue(
            "只被这个人引用，删他就回收",
            deleteIfUnreferenced(image, references, excludingPersonaId = "persona-a")
        )
    }

    @Test
    fun `人格头像与用户头像共享同一文件时先留着 都不引用了才删`() = runBlocking {
        val avatar = temp.newFile("avatar-0123456789abcdef-1.jpg")
        val uri = "file:///data/user/0/com.myfriend.app/files/avatars/avatar-0123456789abcdef-1.jpg"
        val source = FakeSource()
        source.personaAvatars["persona-a"] = uri
        source.userAvatar = uri
        val references = MediaReferences(source)

        // 给人格换头像：旧文件还被「我的头像」引用 → 不能删。
        assertFalse(
            "用户头像还在用同一个文件",
            deleteIfUnreferenced(avatar, references, excludingPersonaId = "persona-a")
        )
        assertTrue(avatar.exists())

        // 用户也换头像了：两个引用都没了 → 回收。
        source.userAvatar = null
        assertTrue(
            "没人引用了才删",
            deleteIfUnreferenced(avatar, references, excludingPersonaId = "persona-a")
        )
        assertFalse(avatar.exists())
    }

    @Test
    fun `预设共用某个人格的头像时删人格不删文件`() = runBlocking {
        val avatar = temp.newFile("avatar-aaaabbbbccccdddd-9.jpg")
        val uri = "file:///data/user/0/com.myfriend.app/files/avatars/avatar-aaaabbbbccccdddd-9.jpg"
        val source = FakeSource()
        source.personaAvatars["persona-a"] = uri
        source.presetAvatars += uri
        val references = MediaReferences(source)

        assertFalse(
            "「保存人格为预设」共用同一张头像，删人格不能删预设的脸",
            deleteIfUnreferenced(avatar, references, excludingPersonaId = "persona-a")
        )
        assertTrue(avatar.exists())

        source.presetAvatars.clear()
        assertTrue(
            "预设也删了才回收",
            deleteIfUnreferenced(avatar, references, excludingPersonaId = "persona-a")
        )
    }

    @Test
    fun `备份替换导入 新图不被删 旧图无引用才删`() = runBlocking {
        val newChat = temp.newFile("chat-new00000000000.jpg")
        val oldChat = temp.newFile("chat-old00000000000.jpg")
        val newAvatar = temp.newFile("avatar-newwwwwwwwwwwww-1.jpg")
        val oldAvatar = temp.newFile("avatar-oldwwwwwwwwwwwww-1.jpg")
        val source = FakeSource()
        // 替换导入之后：库里只有备份带来的新引用（新消息 + 新人格头像）。
        source.attachments += "chat-new00000000000.jpg" to "persona-new"
        source.personaAvatars["persona-new"] =
            "file:///data/user/0/com.myfriend.app/files/avatars/avatar-newwwwwwwwwwwww-1.jpg"
        val references = MediaReferences(source)

        val chatCandidates = listOf(newChat, oldChat)
        val avatarCandidates = listOf(newAvatar, oldAvatar)
        // 与 BackupRepository 的清理同构：keep 集合（这次写进来的新图）之外，再按引用兜一遍。
        val keep = setOf(newChat.name, newAvatar.name)
        val allCandidates = (chatCandidates + avatarCandidates).map { it.name }.filterNot { it in keep }
        val referenced = references.referencedNames(allCandidates)

        // 关键断言：keep 之外的两个旧文件都没有引用（库里已经换成新引用），所以才会被回收。
        assertTrue("keep 之外没有引用", referenced.isEmpty())
        // 旧聊天图：没有行引用 → 删；新聊天图在 keep 里，根本不会进候选。
        assertEquals(1, MediaFileGc.deleteUnreferencedFiles(chatCandidates.filter { it.name !in keep }) { it in referenced })
        assertTrue("新图留着", newChat.exists())
        assertFalse("旧图没有引用，回收", oldChat.exists())

        assertEquals(1, MediaFileGc.deleteUnreferencedFiles(avatarCandidates.filter { it.name !in keep }) { it in referenced })
        assertTrue("新头像留着（人格接回来还有脸）", newAvatar.exists())
        assertFalse("旧头像没有引用，回收", oldAvatar.exists())
    }

    @Test
    fun `单个文件查询与路径版本等价`() = runBlocking {
        val source = FakeSource()
        source.attachments += "chat-1.jpg" to "persona-a"
        val references = MediaReferences(source)

        assertTrue(references.isReferenced("chat-1.jpg"))
        assertTrue(
            "库里存的是 file:// 绝对路径，查询要能按文件名对上",
            references.isUriReferenced("file:///data/user/0/com.myfriend.app/files/media/chat-1.jpg")
        )
        assertFalse(
            "content:// 不是本机媒体文件，谈不上引用",
            references.isUriReferenced("content://media/external/images/1")
        )
        source.attachments.clear()
        assertFalse("没人引用了", references.isReferenced("chat-1.jpg"))
    }

    // ---------------------------------------------------------------- 纯逻辑：LIKE 转义 / 文件名

    @Test
    fun `LIKE 模式串转义百分号下划线与反斜杠`() {
        assertEquals("普通文件名不额外转义", "%chat-abc123.jpg%", MediaFileGc.likePattern("chat-abc123.jpg"))
        assertEquals("百分号要转义（否则匹配任意串）", "%a\\%b%", MediaFileGc.likePattern("a%b"))
        assertEquals("下划线要转义（否则匹配任意一个字符）", "%a\\_b%", MediaFileGc.likePattern("a_b"))
        assertEquals("反斜杠自己也要转义", "%a\\\\b%", MediaFileGc.likePattern("a\\b"))
        // 组合：`%_\` 三个元字符都要按字面匹配。
        assertEquals("%\\%\\_\\\\%", MediaFileGc.likePattern("%_\\"))
        assertTrue("转义符与 DAO 的 ESCAPE 子句一致", MediaFileGc.LIKE_ESCAPE == '\\')
    }

    @Test
    fun `引用计数比较的是文件名`() {
        assertEquals(
            "chat-0123456789abcdef.jpg",
            MediaFileGc.fileNameOf("file:///data/user/0/com.myfriend.app/files/media/chat-0123456789abcdef.jpg")
        )
        assertEquals("裸文件名原样返回", "chat-1.jpg", MediaFileGc.fileNameOf("chat-1.jpg"))
        assertNull("content:// 不是本机媒体文件", MediaFileGc.fileNameOf("content://media/external/images/1"))
        assertNull("https:// 不是本机媒体文件", MediaFileGc.fileNameOf("https://example.com/a.jpg"))
        assertNull("空值", MediaFileGc.fileNameOf("   "))
        assertNull("目录穿越不认", MediaFileGc.fileNameOf("file:///data/user/0/p/files/avatars/../../databases/myfriend.db"))
    }

    @Test
    fun `引用计数 DAO 的 SQL 必须带 ESCAPE 且只看有附件的消息`() {
        // 纯 JVM 跑不了 Room，但「转义有没有真的接进 SQL」是这次修复的命门：
        // 少了 ESCAPE 子句，`likePattern` 里的反斜杠就只是普通字符，转义等于没做。
        val dao = File("src/main/java/com/myfriend/app/data/local/MediaReferenceDao.kt").readText()
        assertTrue("LIKE 必须配 ESCAPE", dao.contains("LIKE :pattern ESCAPE '\\'"))
        assertTrue("空附件列不该进统计", dao.contains("attachmentsJson IS NOT NULL"))
        assertTrue("跨会话全局统计", !dao.contains("WHERE sessionId"))
        assertTrue("删人格时要能排除这个人", dao.contains(":excludingPersonaId IS NULL OR personaId != :excludingPersonaId"))
        assertTrue("头像表要查人格与预设", dao.contains("FROM personas") && dao.contains("FROM persona_presets"))
    }

    @Test
    fun `删除前的引用结论来自 MediaReferences 而不是各写一份`() {
        val janitor = File("src/main/java/com/myfriend/app/data/repo/MediaJanitor.kt").readText()
        assertTrue("门面用批量引用查询", janitor.contains("referencedNames("))
        val storage = File("src/main/java/com/myfriend/app/util/MediaStorage.kt").readText()
        assertTrue("单点函数存在", storage.contains("suspend fun deleteIfUnreferenced("))
        assertTrue(
            "引用查询与删除在同一个进程级临界区里（0.12 遗留缺陷 2）",
            storage.contains("deleteUnreferencedFilesWithReferrer(")
        )
        assertTrue(
            "归属守卫与引用守卫在同一个函数里",
            storage.contains("MediaOwnership.isWithin(file, roots)")
        )
    }

    @Test
    fun `所有会删库内媒体的地方都接了引用守卫`() {
        // 只列「会删库里引用得到的文件」的那批路径；`PersonaEditorViewModel` 的草稿文件
        // 按构造不可能被数据库引用（每次都是新 UUID），保留同步删除并已在代码里注明。
        val guarded = listOf(
            "src/main/java/com/myfriend/app/data/repo/ChatRepository.kt",
            "src/main/java/com/myfriend/app/data/repo/PersonaRepository.kt",
            "src/main/java/com/myfriend/app/data/repo/PresetRepository.kt",
            "src/main/java/com/myfriend/app/data/repo/BackupRepository.kt",
            "src/main/java/com/myfriend/app/data/repo/RoomErasureStore.kt",
            "src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt",
            "src/main/java/com/myfriend/app/ui/screens/settings/SettingsViewModel.kt"
        )
        val forbidden = listOf(
            "MediaStorage.deleteAttachments(",
            "MediaStorage.deleteFiles(",
            "MediaStorage.deleteAvatar(",
            "MediaStorage.clearChatImages(",
            "MediaStorage.clearUnreferencedAvatars("
        )
        guarded.forEach { path ->
            val source = File(path).readText()
            forbidden.forEach { call ->
                assertFalse("$path 还在用不带引用守卫的 $call", source.contains(call))
            }
        }
        // DataEraser 那条路（清空聊天 / 删人格 / 备份替换）的四个文件操作都在
        // AndroidErasureFiles 里统一接了守卫（`clearAllMedia` 是「删全部数据」专用，整目录删）。
        val erasure = File("src/main/java/com/myfriend/app/data/repo/RoomErasureStore.kt").readText()
        listOf(
            "clearChatImagesExceptIfUnreferenced(",
            "deleteFilesIfUnreferenced(",
            "deleteIfUnreferenced(",
            "clearUnreferencedAvatarsIfUnreferenced("
        ).forEach { call ->
            assertTrue("删除路径 $call 要走引用守卫", erasure.contains(call))
        }
        assertTrue("四条清理都经过 MediaReferences", erasure.contains("mediaReferences.referencedNames("))
    }

    // ---------------------------------------------------------------- 假引用来源

    /**
     * 内存版 [MediaReferenceSource]：`attachments` 里每条 = 一条消息的一个附件。
     *
     * 删消息 / 删人格在这里就是「从列表里移除对应项」，与真实 Room 的「删行」等价。
     */
    private class FakeSource : MediaReferenceSource {
        val attachments = mutableListOf<Pair<String, String?>>()
        val personaAvatars = mutableMapOf<String?, String?>()
        val presetAvatars = mutableSetOf<String>()
        var userAvatar: String? = null

        override suspend fun messageAttachmentCount(fileName: String, excludingPersonaId: String?): Int =
            attachments.count { (name, personaId) ->
                name == fileName && (excludingPersonaId == null || personaId != excludingPersonaId)
            }

        override suspend fun personaAvatarNames(excludingPersonaId: String?): Set<String> =
            personaAvatars
                .filterKeys { personaId -> excludingPersonaId == null || personaId != excludingPersonaId }
                .values
                .mapNotNull(MediaFileGc::fileNameOf)
                .toSet()

        override suspend fun presetAvatarNames(): Set<String> =
            presetAvatars.mapNotNull(MediaFileGc::fileNameOf).toSet()

        override suspend fun userAvatarName(): String? = MediaFileGc.fileNameOf(userAvatar)
    }

    /** 与 `MediaJanitor` 同构的一步：先问引用，再决定删不删。 */
    private suspend fun deleteIfUnreferenced(
        file: File,
        references: MediaReferences,
        excludingPersonaId: String? = null
    ): Boolean {
        val referenced = references.referencedNames(listOf(file.name), excludingPersonaId)
        return MediaFileGc.deleteIfUnreferenced(file) { it in referenced }
    }
}
