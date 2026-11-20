package com.yap.app.data.repo

import com.yap.app.domain.BlockSearch
import com.yap.app.domain.MessageBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 块元数据**写入路径**的守卫（读源码）。
 *
 * 块表是「派生但落库」的数据：漏一条写入路径就会出「块里 201 条」「首屏指到空块」
 * 这种只会在用户手机上才看得见的问题。所以这里用两份清单把它钉住：
 *
 * 1. **谁能直接写 messages**：只有 `MessageBlocksWriter`（`MessageBlockWriter`）。
 *    其它仓库/仓库外的写路径都必须调它（发送、回复、中断、失败、编辑、
 *    删除、清空、删会话、删人格、主动消息、心跳、备份导入、擦除）；
 * 2. **擦除路径必须连带块表**：`RoomErasureStore` 的 `MESSAGES` 分支要同时清块行，
 *    并在 `SESSIONS` 之后回收孤儿块。
 */
class MessageBlockContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private val writer get() = source("src/main/java/com/yap/app/data/repo/MessageBlockWriter.kt")
    private val chatRepo get() = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
    private val proactiveRepo get() = source("src/main/java/com/yap/app/data/repo/ProactiveRepository.kt")
    private val agentLoop get() = source("src/main/java/com/yap/app/data/repo/AgentLoop.kt")
    private val backupRepo get() = source("src/main/java/com/yap/app/data/repo/BackupRepository.kt")
    private val erasureStore get() = source("src/main/java/com/yap/app/data/repo/RoomErasureStore.kt")

    /** 直接改「会话里有哪些消息」的 DAO 调用：只允许出现在 writer 里。 */
    private val forbiddenDirectWrites = listOf(
        "messageDao.insert(",
        "messageDao.insertAll(",
        "messageDao.deleteById(",
        "messageDao.deleteAfterCursor(",
        "messageDao.deleteBySession(",
        "messageDao.deleteByPersona(",
        "messageDao.deleteAll()"
    )

    @Test
    fun `所有写消息的路径都经由 MessageBlockWriter`() {
        listOf("ChatRepository.kt" to chatRepo, "ProactiveRepository.kt" to proactiveRepo,
            "AgentLoop.kt" to agentLoop, "BackupRepository.kt" to backupRepo).forEach { (name, text) ->
            forbiddenDirectWrites.forEach { call ->
                assertFalse("$name 直接调了 $call——块元数据会漏维护，必须走 MessageBlockWriter", text.contains(call))
            }
        }
        assertTrue("writer 自己才是落库入口", writer.contains("messageDao.insert(positioned)"))
    }

    @Test
    fun `写路径清单逐条有块维护`() {
        // 发送 / 回复 / 中断 / 失败
        assertTrue("用户发消息要追加块", chatRepo.contains("blockWriter.append(message)"))
        assertTrue("助手回复要追加块", chatRepo.contains("blockWriter.append(assistant)"))
        // §10：不再有「重新生成」写路径（它以前是唯一删最后一条助手消息的地方）。
        // 编辑用户消息（按锚点插入）
        assertTrue("编辑用户消息按锚点重落", chatRepo.contains("blockWriter.replaceAtAnchor("))
        assertTrue("编辑之后删后续消息要重算受影响的块", chatRepo.contains("blockWriter.deleteAfterCursor("))
        // 删除单条
        assertTrue("删除单条消息要走删除规则", chatRepo.contains("blockWriter.deleteMessage(message)"))
        // 清空上下文 / 删除会话
        assertTrue("清空上下文要连带块行", chatRepo.contains("blockWriter.deleteSession(sessionId)"))
        assertTrue("删除会话要连带块行", chatRepo.contains("blockWriter.deleteSession(sessionId)"))
        // 主动消息 / 心跳
        assertTrue("规则型主动消息要走追加规则", proactiveRepo.contains("blockWriter.append(message)"))
        assertTrue("心跳主动消息要走追加规则", agentLoop.contains("blockWriter.append(message)"))
        // 备份导入
        assertTrue("备份导入后按消息顺序重建块表", backupRepo.contains("blockWriter.insertImported(messages)"))
    }

    @Test
    fun `擦除路径连带块表`() {
        assertTrue(
            "MESSAGES 分支要同时清块（整库 / 按人）",
            erasureStore.contains("blockWriter.deleteAll()") &&
                erasureStore.contains("blockWriter.deletePersona(personaId)")
        )
        assertTrue(
            "删人格时块表按 sessionId 反查，必须在 SESSIONS 之前执行",
            writer.contains("blockDao.deleteByPersona(personaId)") &&
                erasureStore.contains("ErasableTable.MESSAGES ->") &&
                erasureStore.indexOf("blockWriter.deletePersona(personaId)") <
                erasureStore.indexOf("sessionDao.deleteByPersona(personaId)")
        )
        assertTrue("SESSIONS 之后回收孤儿块", erasureStore.contains("blockDao.deleteOrphans()"))
    }

    @Test
    fun `删除规则 空块删行 编号不重排`() {
        assertTrue("块空了要整行删掉", writer.contains("blockDao.delete(sessionId, blockIndex)"))
        assertFalse("不许重排块编号（重平衡）", writer.contains("renumber"))
        assertFalse("不许把短块合并", writer.contains("merge"))
        assertTrue(
            "删除之后按剩下的行重算首尾（纯规则）",
            writer.contains("MessageBlocks.afterDelete(")
        )
    }

    @Test
    fun `追加与锚点插入都走纯规则`() {
        assertTrue(writer.contains("MessageBlocks.planAppend("))
        assertTrue(writer.contains("MessageBlocks.planInsertAtAnchor("))
        assertTrue(writer.contains("MessageBlocks.rebuild("))
    }

    @Test
    fun `备份格式仍然是 v2 不含块元数据`() {
        val codec = source("src/main/java/com/yap/app/domain/BackupCodec.kt")
        assertFalse("备份里不该出现块元数据字段", codec.contains("blockIndex"))
        assertFalse("备份里不该出现 message_blocks", codec.contains("message_blocks"))
        val version = source("src/main/java/com/yap/app/domain/BackupCodec.kt")
        assertTrue("备份版本仍是 v2", version.contains("BACKUP_FORMAT_VERSION"))
    }

    @Test
    fun `块大小常量是 200 且只有一个来源`() {
        assertEquals(200, MessageBlocks.BLOCK_SIZE)
        assertTrue(
            "实体默认值与迁移都必须引用同一个常量来源（迁移用 MessageBlocks.BLOCK_SIZE）",
            source("src/main/java/com/yap/app/data/local/MessageBlockMigration.kt")
                .contains("MessageBlocks.BLOCK_SIZE")
        )
    }

    @Test
    fun `检索游标来自块扫描而不是 offset`() {
        assertTrue("检索用 BlockSearch 的键集游标", chatRepo.contains("BlockSearch.page("))
        assertTrue("返回下一页游标", chatRepo.contains("nextCursor = page.nextCursor"))
        assertFalse("不再有 offset 分页", chatRepo.contains(".drop(offset)"))
        assertFalse("不再有候选上限常量", chatRepo.contains("CANDIDATE_LIMIT"))
        assertTrue(
            "游标类型只从 BlockSearch 取（不在仓库里另造一套排序键）",
            chatRepo.contains("cursor: BlockSearch.Cursor?")
        )
    }
}
