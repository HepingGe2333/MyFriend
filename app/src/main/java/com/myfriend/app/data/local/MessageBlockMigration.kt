package com.myfriend.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.myfriend.app.domain.MessageBlocks

/**
 * `MIGRATION_10_11` 的实现（规范 `spec/messages-blocks/README.md` §5）。
 *
 * ## 为什么单独一个文件
 *
 * 迁移是一段**不能出错**的 SQL：它要在用户手机上、对几年攒下来的历史跑一次，
 * 中间还夹着「按 `(createdAt, id)` 回填 `blockIndex`」这种需要顺序计算的活。
 * 放在 `MyFriendDatabase` 的 companion 里会淹没在其它迁移之间；单独放这里
 * 既方便 androidTest 直接对着它写用例，也让「回填规则与 `MessageBlocks.rebuild`
 * 是同一份」这件事在 import 里一眼看得见。
 *
 * ## 步骤（与规范一一对应）
 *
 * 1. 加 `messages.blockIndex`（默认 0）；
 * 2. 建 `message_blocks` 与两个索引；
 * 3. 对每个会话按 `(createdAt, id)` 顺序回填 `blockIndex = 行号 / BLOCK_SIZE`，
 *    并按同一份顺序生成块行；
 * 4. 删掉会话已不存在的孤儿块行。
 *
 * ## 幂等与内存
 *
 * - 每一步都是 `IF NOT EXISTS` / `INSERT OR REPLACE` / 先删后建，**重复执行不会失败**，
 *   也不会重复插块（androidTest 里有一条「迁移幂等」用例直接跑两遍）；
 * - 回填是**流式**的：游标一次只前进一条，手上最多留「当前块的首尾两条」，所以迁移的
 *   峰值内存与会话长度无关（不会把整段历史读成一个 `List`）。
 */
object MessageBlockMigration {

    const val BLOCKS_TABLE = "message_blocks"

    /** 与 Room 由 [MessageBlockEntity] 生成的 DDL 完全一致（顺序、NOT NULL、主键都对齐）。 */
    val CREATE_BLOCKS_TABLE = """
        CREATE TABLE IF NOT EXISTS `message_blocks` (
            `sessionId` TEXT NOT NULL,
            `blockIndex` INTEGER NOT NULL,
            `firstMessageId` TEXT NOT NULL,
            `lastMessageId` TEXT NOT NULL,
            `firstCreatedAt` INTEGER NOT NULL,
            `lastCreatedAt` INTEGER NOT NULL,
            `messageCount` INTEGER NOT NULL,
            PRIMARY KEY(`sessionId`, `blockIndex`)
        )
    """.trimIndent()

    val CREATE_BLOCKS_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_message_blocks_sessionId_blockIndex` " +
            "ON `message_blocks` (`sessionId`, `blockIndex`)"

    val CREATE_MESSAGES_BLOCK_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_blockIndex_createdAt` " +
            "ON `messages` (`sessionId`, `blockIndex`, `createdAt`)"

    /** `MIGRATION_10_11` 的全部内容。 */
    fun migrate10To11(db: SupportSQLiteDatabase) {
        // ---- 1. 加列 ----------------------------------------------------
        if (!db.hasColumn("messages", "blockIndex")) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `blockIndex` INTEGER NOT NULL DEFAULT 0")
        }

        // ---- 2. 建表与索引 ----------------------------------------------
        db.execSQL(CREATE_BLOCKS_TABLE)
        db.execSQL(CREATE_BLOCKS_INDEX)
        db.execSQL(CREATE_MESSAGES_BLOCK_INDEX)

        // ---- 3. 逐会话回填 ----------------------------------------------
        val sessions = ArrayList<String>()
        db.query("SELECT `id` FROM `chat_sessions`").use { cursor ->
            while (cursor.moveToNext()) sessions += cursor.getString(0)
        }
        sessions.forEach { sessionId -> backfillSession(db, sessionId) }

        // ---- 4. 孤儿块 --------------------------------------------------
        db.execSQL("DELETE FROM `message_blocks` WHERE `sessionId` NOT IN (SELECT `id` FROM `chat_sessions`)")
    }

    /**
     * 一个会话的回填：流式读 `(id, createdAt)`，块边界一到就写一块。
     *
     * 写回分两条：
     * - `UPDATE messages SET blockIndex = ?` 用「块内第一条 / 最后一条」的闭区间，
     *   一条消息一条 UPDATE 在几万条时会明显拖慢迁移，这里每块只发一条；
     * - `INSERT OR REPLACE INTO message_blocks` 把这一块的元数据落库。
     */
    private fun backfillSession(db: SupportSQLiteDatabase, sessionId: String) {
        // 幂等：重复执行时先把这个会话的旧块行清掉，再按当前消息重建。
        db.execSQL("DELETE FROM `message_blocks` WHERE `sessionId` = ?", arrayOf(sessionId))

        var position = 0
        var blockFirst: MessageBlocks.Ref? = null
        var blockLast: MessageBlocks.Ref? = null

        db.query(
            "SELECT `id`, `createdAt` FROM `messages` WHERE `sessionId` = ? " +
                "ORDER BY `createdAt` ASC, `id` ASC",
            arrayOf(sessionId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val ref = MessageBlocks.Ref(
                    id = cursor.getString(0),
                    sessionId = sessionId,
                    createdAt = cursor.getLong(1)
                )
                if (blockFirst == null) blockFirst = ref
                blockLast = ref
                position++
                if (position % MessageBlocks.BLOCK_SIZE == 0) {
                    // position 是「已经读了第几条」：整块读完时块编号是 position/BLOCK_SIZE - 1。
                    val blockIndex = position / MessageBlocks.BLOCK_SIZE - 1
                    blockFirst?.let { first ->
                        blockLast?.let { last ->
                            writeBlock(db, sessionId, blockIndex, first, last, MessageBlocks.BLOCK_SIZE)
                        }
                    }
                    blockFirst = null
                    blockLast = null
                }
            }
        }
        // 最后一块（不满 BLOCK_SIZE，或恰好整除时上面已经写完）
        blockFirst?.let { first ->
            blockLast?.let { last ->
                val blockIndex = position / MessageBlocks.BLOCK_SIZE
                val count = position - blockIndex * MessageBlocks.BLOCK_SIZE
                writeBlock(db, sessionId, blockIndex, first, last, count)
            }
        }
    }

    private fun writeBlock(
        db: SupportSQLiteDatabase,
        sessionId: String,
        blockIndex: Int,
        first: MessageBlocks.Ref,
        last: MessageBlocks.Ref,
        count: Int
    ) {
        db.execSQL(
            """
            UPDATE `messages` SET `blockIndex` = ? WHERE `sessionId` = ?
            AND (`createdAt` > ? OR (`createdAt` = ? AND `id` >= ?))
            AND (`createdAt` < ? OR (`createdAt` = ? AND `id` <= ?))
            """.trimIndent(),
            arrayOf<Any?>(
                blockIndex,
                sessionId,
                first.createdAt,
                first.createdAt,
                first.id,
                last.createdAt,
                last.createdAt,
                last.id
            )
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO `message_blocks`
            (`sessionId`, `blockIndex`, `firstMessageId`, `lastMessageId`,
             `firstCreatedAt`, `lastCreatedAt`, `messageCount`)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(sessionId, blockIndex, first.id, last.id, first.createdAt, last.createdAt, count)
        )
    }

    /** `PRAGMA table_info` 里有没有这一列（ALTER 的幂等判断）。 */
    private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) found = true
            }
            found
        }
}
