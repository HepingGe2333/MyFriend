package com.myfriend.app.data.repo

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MessageBlockEntity
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.MessageStatus
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.domain.MessageBlocks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 块维护的**真实落库**测试（Room in-memory，跑在模拟器上）。
 *
 * 纯规则由 `MessageBlocksTest` 钉住；这里钉的是「规则 + DAO 组合起来之后，库里的块表
 * 与消息永远自洽」。为什么值得单开一组 androidTest：
 *
 * - 块元数据是**派生但落库**的数据，一旦某条路径漏维护，问题只在真实 SQLite 上表现为
 *   「块里 201 条」「首屏 MAX(blockIndex) 指到空块」——JVM 假 DAO 是发现不了的；
 * - 备份导入的「重建」尤其需要真库：它要按 `(createdAt, id)` 顺序重写 `blockIndex`，
 *   再重建块行，两步必须一致。
 */
@RunWith(AndroidJUnit4::class)
class MessageBlockWriterInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MyFriendDatabase
    private lateinit var writer: MessageBlockWriter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MyFriendDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writer = MessageBlockWriter(db, db.messageDao(), db.messageBlockDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------ 追加

    @Test
    fun 追加450条消息得到200_200_50三块并且块表自洽() = runBlocking {
        insertSession("session-1", "persona-1")
        repeat(450) { index ->
            writer.append(message("m%03d".format(index), "session-1", BASE_TIME + index))
        }

        assertConsistent("session-1")
        val blocks = blocks("session-1")
        assertEquals(3, blocks.size)
        assertEquals(listOf(0, 1, 2), blocks.map { it.blockIndex })
        assertEquals(listOf(200, 200, 50), blocks.map { it.messageCount })
        assertEquals("m000", blocks[0].firstMessageId)
        assertEquals("m199", blocks[0].lastMessageId)
        assertEquals("m200", blocks[1].firstMessageId)
        assertEquals("m449", blocks[2].lastMessageId)
        assertEquals(BASE_TIME + 449, blocks[2].lastCreatedAt)
    }

    @Test
    fun 消息的blockIndex跟着块走_不靠默认值0() = runBlocking {
        insertSession("session-1", "persona-1")
        repeat(201) { index ->
            writer.append(message("m%03d".format(index), "session-1", BASE_TIME + index))
        }

        assertEquals(0, message("m000").blockIndex)
        assertEquals(0, message("m199").blockIndex)
        assertEquals("第 201 条要落进新块", 1, message("m200").blockIndex)
        assertEquals(listOf(0, 1), blocks("session-1").map { it.blockIndex })
    }

    // ------------------------------------------------------------------ 删除

    @Test
    fun 删除块中间的消息_只减条数首尾不变() = runBlocking {
        insertSession("session-1", "persona-1")
        repeat(10) { index ->
            writer.append(message("m%03d".format(index), "session-1", BASE_TIME + index))
        }

        writer.deleteMessage(message("m005"))

        val block = blocks("session-1").single()
        assertEquals(9, block.messageCount)
        assertEquals("m000", block.firstMessageId)
        assertEquals("m009", block.lastMessageId)
        assertConsistent("session-1")
    }

    @Test
    fun 删除块首条_首条按剩下的行重算() = runBlocking {
        insertSession("session-1", "persona-1")
        repeat(10) { index ->
            writer.append(message("m%03d".format(index), "session-1", BASE_TIME + index))
        }

        writer.deleteMessage(message("m000"))

        val block = blocks("session-1").single()
        assertEquals("m001", block.firstMessageId)
        assertEquals(BASE_TIME + 1, block.firstCreatedAt)
        assertConsistent("session-1")
    }

    @Test
    fun 删空一块_块行删除且编号留空洞_不重排() = runBlocking {
        insertSession("session-1", "persona-1")
        repeat(401) { index ->
            writer.append(message("m%03d".format(index), "session-1", BASE_TIME + index))
        }
        // 把 1 号块（m200..m399）删空。
        (200..399).forEach { index -> writer.deleteMessage(message("m%03d".format(index))) }

        val blocks = blocks("session-1")
        assertEquals("1 号块应整行消失", listOf(0, 2), blocks.map { it.blockIndex })
        assertEquals("0 号块不受影响", 200, blocks[0].messageCount)
        assertEquals("2 号块只剩最后一条", 1, blocks[1].messageCount)
        assertEquals("空出来的 1 号编号不重排，2 号还是 2 号", 2, blocks[1].blockIndex)
        assertConsistent("session-1")

        // 追加继续落在编号最大的那一块（2 号），不会回头填 1 号。
        writer.append(message("m401", "session-1", BASE_TIME + 401))
        assertEquals(2, message("m401").blockIndex)
        assertConsistent("session-1")
    }

    @Test
    fun 编辑用户消息按锚点重落_块归属不变() = runBlocking {
        insertSession("session-1", "persona-1")
        repeat(10) { index ->
            writer.append(message("m%03d".format(index), "session-1", BASE_TIME + index))
        }

        writer.replaceAtAnchor(message("m007").copy(content = "改过的内容", edited = true))

        assertEquals("同一条消息仍在原来那一块", 0, message("m007").blockIndex)
        assertEquals("改过的内容", message("m007").content)
        assertEquals("块条数不变", 10, blocks("session-1").single().messageCount)
        assertConsistent("session-1")
    }

    @Test
    fun 删除游标之后的全部消息_受影响的块都要重算() = runBlocking {
        insertSession("session-1", "persona-1")
        repeat(450) { index ->
            writer.append(message("m%03d".format(index), "session-1", BASE_TIME + index))
        }

        // 从 m250 之后全删：0 号块剩 200 条不变，1 号块剩 50 条，2 号块被删空。
        writer.deleteAfterCursor("session-1", BASE_TIME + 250, "m250")

        assertEquals(251, db.messageDao().countInSession("session-1"))
        val blocks = blocks("session-1")
        assertEquals(listOf(0, 1), blocks.map { it.blockIndex })
        assertEquals(listOf(200, 51), blocks.map { it.messageCount })
        assertConsistent("session-1")
    }

    // ------------------------------------------------------------------ 备份导入重建

    @Test
    fun 备份导入重建_按消息顺序重算blockIndex与块行() = runBlocking {
        insertSession("session-1", "persona-1")
        // 模拟合并导入：序列里既有旧消息也有新消息，插入顺序甚至是乱的。
        val imported = (0 until 450).map { index ->
            message("m%03d".format(index), "session-1", BASE_TIME + index)
        }.shuffled()

        writer.insertImported(imported)

        assertEquals(450, db.messageDao().countInSession("session-1"))
        assertConsistent("session-1")
        val blocks = blocks("session-1")
        assertEquals(listOf(0, 1, 2), blocks.map { it.blockIndex })
        assertEquals(listOf(200, 200, 50), blocks.map { it.messageCount })
        // 顺序是按 (createdAt, id) 而不是插入顺序：0 号块的首条一定是最早的。
        assertEquals("m000", blocks[0].firstMessageId)
        assertEquals("m449", blocks[2].lastMessageId)
    }

    @Test
    fun 合并导入之后既有历史与新历史一起重建_块表自洽() = runBlocking {
        insertSession("session-1", "persona-1")
        // 本机已有 30 条（时间较晚）。
        repeat(30) { index ->
            writer.append(message("local%02d".format(index), "session-1", BASE_TIME + 1_000 + index))
        }
        // 备份里是更早的 220 条（合并导入时插到中间/前面）。
        val imported = (0 until 220).map { index ->
            message("old%03d".format(index), "session-1", BASE_TIME + index)
        }

        writer.insertImported(imported)

        assertEquals(250, db.messageDao().countInSession("session-1"))
        assertConsistent("session-1")
        val blocks = blocks("session-1")
        assertEquals(listOf(0, 1), blocks.map { it.blockIndex })
        assertEquals(200, blocks[0].messageCount)
        assertEquals(50, blocks[1].messageCount)
        assertEquals("最早的是备份里的那条", "old000", blocks[0].firstMessageId)
        assertEquals("最新的是本机那条", "local29", blocks[1].lastMessageId)
    }

    @Test
    fun 替换导入_旧块先清掉再按新消息重建() = runBlocking {
        insertSession("session-1", "persona-1")
        repeat(220) { index ->
            writer.append(message("old%03d".format(index), "session-1", BASE_TIME + index))
        }
        assertEquals(2, blocks("session-1").size)

        writer.deleteSession("session-1")
        writer.insertImported((0 until 10).map { index ->
            message("new%02d".format(index), "session-1", BASE_TIME + 5_000 + index)
        })

        assertEquals(1, blocks("session-1").size)
        assertEquals(10, blocks("session-1").single().messageCount)
        assertConsistent("session-1")
    }

    // ------------------------------------------------------------------ 清空 / 擦除

    @Test
    fun 清空会话与删除人格都会连带块表() = runBlocking {
        insertSession("session-1", "persona-1")
        insertSession("session-2", "persona-1")
        insertSession("session-3", "persona-2")
        repeat(10) { index ->
            writer.append(message("a%02d".format(index), "session-1", BASE_TIME + index))
            writer.append(message("b%02d".format(index), "session-2", BASE_TIME + index))
            writer.append(message("c%02d".format(index), "session-3", BASE_TIME + index, personaId = "persona-2"))
        }
        assertEquals(3, db.messageBlockDao().countInSession("session-1") + db.messageBlockDao().countInSession("session-2") + db.messageBlockDao().countInSession("session-3"))

        writer.deleteSession("session-1")
        assertNull("清空会话之后块行也要没", db.messageBlockDao().lastBlockIndex("session-1"))

        writer.deletePersona("persona-1")
        assertNull(db.messageBlockDao().lastBlockIndex("session-2"))
        assertTrue("别人的块不能跟着删", db.messageBlockDao().lastBlockIndex("session-3") != null)
        assertConsistent("session-3")
    }

    @Test
    fun 整库清空之后块表为空() = runBlocking {
        insertSession("session-1", "persona-1")
        repeat(10) { index -> writer.append(message("m%02d".format(index), "session-1", BASE_TIME + index)) }

        writer.deleteAll()

        assertEquals(0, db.messageDao().countInSession("session-1"))
        assertEquals(0, db.messageBlockDao().countInSession("session-1"))
        assertNull(db.messageBlockDao().lastBlockIndex("session-1"))
    }

    // ------------------------------------------------------------------ 事务嵌套

    @Test
    fun 在调用方事务里追加不会嵌套失败_块维护沿用外层事务() = runBlocking {
        insertSession("session-1", "persona-1")

        // 生产路径就是这样：ChatRepository 把「写消息 + 更新会话预览」包在一个事务里，
        // 块维护必须沿用外层事务，而不是自己再开一个（那会死锁或破坏原子性）。
        db.withTransaction {
            writer.append(message("m000", "session-1", BASE_TIME))
            writer.append(message("m001", "session-1", BASE_TIME + 1))
            db.chatSessionDao().updatePreview("session-1", "预览", BASE_TIME + 1)
        }

        assertEquals(2, db.messageDao().countInSession("session-1"))
        assertEquals(2, blocks("session-1").single().messageCount)
        assertConsistent("session-1")
    }

    // ------------------------------------------------------------------ 工具

    private suspend fun insertSession(id: String, personaId: String) {
        db.chatSessionDao().insert(
            ChatSessionEntity(
                id = id,
                personaId = personaId,
                apiConfigId = null,
                model = "test-model",
                title = id,
                lastMessagePreview = null,
                lastMessageAt = null,
                unreadCount = 0,
                pinned = false,
                createdAt = BASE_TIME,
                updatedAt = BASE_TIME,
                lastPersonaVersion = 1,
                lastMemoryExtractedCount = 0
            )
        )
    }

    private fun message(
        id: String,
        sessionId: String,
        createdAt: Long,
        personaId: String = "persona-1"
    ): MessageEntity = MessageEntity(
        id = id,
        sessionId = sessionId,
        personaId = personaId,
        role = MessageRole.USER,
        content = id,
        createdAt = createdAt,
        updatedAt = createdAt,
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        model = null,
        edited = false
    )

    private suspend fun message(id: String): MessageEntity =
        requireNotNull(db.messageDao().getById(id)) { "消息 $id 不存在" }

    private suspend fun blocks(sessionId: String): List<MessageBlockEntity> =
        db.query(
            "SELECT * FROM message_blocks WHERE sessionId = ? ORDER BY blockIndex",
            arrayOf(sessionId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MessageBlockEntity(
                            sessionId = cursor.getString(0),
                            blockIndex = cursor.getInt(1),
                            firstMessageId = cursor.getString(2),
                            lastMessageId = cursor.getString(3),
                            firstCreatedAt = cursor.getLong(4),
                            lastCreatedAt = cursor.getLong(5),
                            messageCount = cursor.getInt(6)
                        )
                    )
                }
            }
        }

    /**
     * 块表自洽的不变量（规范 §1/§2）：
     *
     * 1. 每条消息的 `blockIndex` 都有对应的块行；
     * 2. 每个块行的 `messageCount` 等于该块里真实的条数；
     * 3. 块行的 first/last 与该块按 `(createdAt, id)` 的首尾一致；
     * 4. 块大小 ≤ BLOCK_SIZE（锚点插入允许临时 +1，但本测试里不触发）。
     */
    private suspend fun assertConsistent(sessionId: String) {
        val blockRows = blocks(sessionId)
        val all = db.query(
            "SELECT id, createdAt, blockIndex FROM messages WHERE sessionId = ? ORDER BY createdAt ASC, id ASC",
            arrayOf(sessionId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getString(0), cursor.getLong(1), cursor.getInt(2)))
                }
            }
        }
        val byBlock = all.groupBy { it.third }
        assertEquals(
            "块表里的编号必须正好是消息里出现过的编号",
            byBlock.keys.sorted(),
            blockRows.map { it.blockIndex }
        )
        blockRows.forEach { block ->
            val rows = byBlock[block.blockIndex].orEmpty()
            assertEquals("块 ${block.blockIndex} 的 messageCount 与真实条数不一致", rows.size, block.messageCount)
            assertEquals("块 ${block.blockIndex} 的 first 不对", rows.first().first, block.firstMessageId)
            assertEquals("块 ${block.blockIndex} 的 last 不对", rows.last().first, block.lastMessageId)
            assertEquals("块 ${block.blockIndex} 的 firstCreatedAt 不对", rows.first().second, block.firstCreatedAt)
            assertEquals("块 ${block.blockIndex} 的 lastCreatedAt 不对", rows.last().second, block.lastCreatedAt)
            assertTrue(
                "普通追加不该让块超过 BLOCK_SIZE（实际 ${block.messageCount}）",
                block.messageCount <= MessageBlocks.BLOCK_SIZE
            )
        }
        assertFalse("没有任何块行时不该有消息", all.isNotEmpty() && blockRows.isEmpty())
    }

    private companion object {
        const val BASE_TIME = 1_700_000_000_000L
    }
}
