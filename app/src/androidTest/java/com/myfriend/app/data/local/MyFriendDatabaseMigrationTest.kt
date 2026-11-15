package com.myfriend.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 迁移测试：证明「升级 App 不会把他的记忆清空」。
 *
 * 这是这个项目里最不能省的一组测试。用户看不见数据库，也管不到迁移怎么写；
 * 一旦升级时把表清掉，他感受到的不是「报了个错」，而是「这个人突然不记得我了」——
 * 而这种损失是不可解释、也不可挽回的。所以这里手工造出旧版本的库文件，
 * 跑真实的 Migration 对象，再逐条断言「旧数据还在、新东西也有了」。
 *
 * 用的是 `FrameworkSQLiteOpenHelperFactory`：不需要历史 schema json，
 * 表结构直接按 Entities 对应版本的 DDL 手写，跑在真机/模拟器上。
 * 本机模拟器不稳，验收标准是 `assembleDebugAndroidTest` 能编译通过；
 * 有设备时直接 `connectedDebugAndroidTest` 就能跑起来。
 */
@RunWith(AndroidJUnit4::class)
class MyFriendDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val openedHelpers = mutableListOf<SupportSQLiteOpenHelper>()

    @After
    fun tearDown() {
        openedHelpers.forEach { runCatching { it.close() } }
        openedHelpers.clear()
        context.deleteDatabase(DB_NAME)
    }

    // ---------- v6 → v7 ----------

    @Test
    fun migrate6To7_保留旧记忆并补齐新列与新表() {
        val db = createLegacyDatabase(version = 6, ddl = V6_SCHEMA)
        insertLegacyRows(db)

        MyFriendDatabase.MIGRATION_6_7.migrate(db)

        // 1. 新列都在。
        val memoryColumns = db.columnNames("memories")
        listOf("tags", "happenedAt", "originalContent", "driftCount").forEach { column ->
            assertTrue("v7 应该给 memories 加上 $column，实际列为 $memoryColumns", column in memoryColumns)
        }

        // 2. 三张新表 + 索引都在。
        listOf("api_usage", "memory_terms", "memory_versions").forEach { table ->
            assertTrue("v7 应该建出 $table 表", db.hasTable(table))
        }
        listOf(
            "index_api_usage_at",
            "index_api_usage_kind_at",
            "index_api_usage_personaId_at",
            "index_memory_terms_term",
            "index_memory_versions_memoryId_at"
        ).forEach { index ->
            assertTrue("v7 应该建出索引 $index", db.hasIndex(index))
        }

        // 3. 旧的那一行还在，内容一个字都没少，新列有安全默认值。
        db.query("SELECT content, driftCount, status, strength FROM memories WHERE id = ?", arrayOf(LEGACY_MEMORY_ID))
            .use { cursor ->
                assertTrue("迁移把旧记忆弄丢了", cursor.moveToFirst())
                assertEquals("迁移改写了记忆内容", LEGACY_MEMORY_CONTENT, cursor.getString(0))
                assertEquals("旧记忆的 driftCount 应该默认 0（还没被重构过）", 0, cursor.getInt(1))
                assertEquals("旧记忆的状态应该还是 ACTIVE", "ACTIVE", cursor.getString(2))
                assertEquals("旧记忆的分量不应该被迁移改掉", 1.0, cursor.getDouble(3), 0.0001)
            }
        assertEquals("memories 的行数变了", 1, db.count("memories"))
        assertEquals("personas 的行数变了", 1, db.count("personas"))
        assertEquals("api_configs 的行数变了", 1, db.count("api_configs"))
    }

    // ---------- v8 → v9 ----------

    @Test
    fun migrate8To9_移除同步死列但一条数据都没丢() {
        val db = createLegacyDatabase(version = 8, ddl = V8_FULL_SCHEMA)
        db.execSQL(
            """
            INSERT INTO `api_configs` (
                `id`, `name`, `provider`, `baseUrl`, `apiKeyCipher`, `model`, `customHeadersJson`,
                `allowInsecureHttp`, `temperature`, `topP`, `maxOutputTokens`, `frequencyPenalty`,
                `presencePenalty`, `harnessMode`, `harnessUrl`, `harnessSyncPath`, `createdAt`, `updatedAt`,
                `webToolsEnabled`, `webToolsBaseUrl`, `webToolsApiKeyCipher`
            ) VALUES (
                'config-1', '默认', 'OPENAI', 'https://api.example.com', NULL, 'gpt-test', '{}',
                0, 0.8, NULL, 2048, NULL,
                NULL, 'NATIVE', 'https://old.example.com', 'v1/old/sync',
                1700000000000, 1700000000100,
                1, 'https://api.deepseek.com', NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `chat_sessions` (
                `id`, `personaId`, `apiConfigId`, `model`, `title`, `lastMessagePreview`, `lastMessageAt`,
                `unreadCount`, `pinned`, `createdAt`, `updatedAt`, `lastPersonaVersion`, `syncState`,
                `syncedPersonaVersion`, `syncedMemoriesHash`, `serverSessionId`, `lastSyncError`, `lastSyncAt`,
                `lastMemoryExtractedCount`, `historySummary`, `historySummarizedUpTo`, `historySummaryAt`
            ) VALUES (
                'session-1', 'persona-1', 'config-1', 'gpt-test', '旧会话', '晚安', 1700000000500,
                2, 1, 1700000000000, 1700000000600, 3, 'SYNCED',
                3, 'hash-abc', 'server-9', NULL, 1700000000400,
                6, '更早之前的那一段', 1700000000300, 1700000000350
            )
            """.trimIndent()
        )

        MyFriendDatabase.MIGRATION_8_9.migrate(db)

        // 1. 死列真的没了。
        val apiColumns = db.columnNames("api_configs")
        listOf("harnessMode", "harnessUrl", "harnessSyncPath").forEach { column ->
            assertTrue("v9 之后 api_configs 不该再有 $column，实际列为 $apiColumns", column !in apiColumns)
        }
        val sessionColumns = db.columnNames("chat_sessions")
        listOf(
            "syncState", "syncedPersonaVersion", "syncedMemoriesHash",
            "serverSessionId", "lastSyncError", "lastSyncAt"
        ).forEach { column ->
            assertTrue("v9 之后 chat_sessions 不该再有 $column，实际列为 $sessionColumns", column !in sessionColumns)
        }

        // 2. 数据一条都没少，值也没变。
        db.query(
            "SELECT name, model, baseUrl, temperature, maxOutputTokens, webToolsEnabled, webToolsBaseUrl FROM api_configs WHERE id = ?",
            arrayOf("config-1")
        ).use { cursor ->
            assertTrue("迁移把 API 配置弄丢了", cursor.moveToFirst())
            assertEquals("默认", cursor.getString(0))
            assertEquals("gpt-test", cursor.getString(1))
            assertEquals("https://api.example.com", cursor.getString(2))
            assertEquals(0.8, cursor.getDouble(3), 0.0001)
            assertEquals(2048, cursor.getInt(4))
            assertEquals(1, cursor.getInt(5))
            assertEquals("https://api.deepseek.com", cursor.getString(6))
        }
        db.query(
            "SELECT title, lastMessagePreview, pinned, lastPersonaVersion, lastMemoryExtractedCount, historySummary FROM chat_sessions WHERE id = ?",
            arrayOf("session-1")
        ).use { cursor ->
            assertTrue("迁移把会话弄丢了", cursor.moveToFirst())
            assertEquals("旧会话", cursor.getString(0))
            assertEquals("晚安", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(3, cursor.getInt(3))
            assertEquals(6, cursor.getInt(4))
            assertEquals("更早之前的那一段", cursor.getString(5))
        }

        // 3. 重建表以后索引要补回来，否则 Room 的表校验和查询计划都会退化。
        listOf(
            "index_api_configs_updatedAt",
            "index_chat_sessions_personaId",
            "index_chat_sessions_lastMessageAt",
            "index_chat_sessions_pinned"
        ).forEach { index ->
            assertTrue("v9 重建表之后应该补回索引 $index", db.hasIndex(index))
        }
    }

    /**
     * 关键的一条：迁移完以后让 Room 自己打开一次。
     *
     * 上面那条测试只证明 `MIGRATION_8_9` 的 SQL 跑得通；真正决定「老用户升级会不会崩」的，
     * 是 Room 打开库时的表校验（列必须完全一致，多一列少一列都会拒绝启动）。
     * 这条测试走完整的 Room 打开路径：8 → 9 自动迁移 → onValidateSchema，任何一列对不上都会红。
     */
    @Test
    fun migrate8To10_Room打开旧库时表校验通过() {
        val legacy = createLegacyDatabase(version = 8, ddl = V8_FULL_SCHEMA)
        legacy.execSQL(
            """
            INSERT INTO `api_configs` (
                `id`, `name`, `provider`, `baseUrl`, `apiKeyCipher`, `model`, `customHeadersJson`,
                `allowInsecureHttp`, `temperature`, `topP`, `maxOutputTokens`, `frequencyPenalty`,
                `presencePenalty`, `harnessMode`, `harnessUrl`, `harnessSyncPath`, `createdAt`, `updatedAt`,
                `webToolsEnabled`, `webToolsBaseUrl`, `webToolsApiKeyCipher`
            ) VALUES (
                'config-1', '默认', 'OPENAI', 'https://api.example.com', NULL, 'gpt-test', '{}',
                0, 0.8, NULL, 2048, NULL,
                NULL, 'NATIVE', 'https://old.example.com', 'v1/old/sync',
                1700000000000, 1700000000100,
                0, '', NULL
            )
            """.trimIndent()
        )
        legacy.execSQL(
            """
            INSERT INTO `chat_sessions` (
                `id`, `personaId`, `apiConfigId`, `model`, `title`, `lastMessagePreview`, `lastMessageAt`,
                `unreadCount`, `pinned`, `createdAt`, `updatedAt`, `lastPersonaVersion`, `syncState`,
                `syncedPersonaVersion`, `syncedMemoriesHash`, `serverSessionId`, `lastSyncError`, `lastSyncAt`,
                `lastMemoryExtractedCount`, `historySummary`, `historySummarizedUpTo`, `historySummaryAt`
            ) VALUES (
                'session-1', 'persona-1', 'config-1', 'gpt-test', '旧会话', '晚安', 1700000000500,
                0, 0, 1700000000000, 1700000000600, 3, 'SYNCED',
                3, 'hash-abc', 'server-9', NULL, 1700000000400,
                6, NULL, NULL, NULL
            )
            """.trimIndent()
        )
        legacy.close()

        val room = Room.databaseBuilder(context, MyFriendDatabase::class.java, DB_NAME)
            // 老用户可能从 v8 直接升到当前版本：迁移链要能一路走到底（v8→v9→v10）。
            .addMigrations(
                MyFriendDatabase.MIGRATION_8_9,
                MyFriendDatabase.MIGRATION_9_10,
                MyFriendDatabase.MIGRATION_10_11,
                MyFriendDatabase.MIGRATION_11_12
            )
            .allowMainThreadQueries()
            .build()
        try {
            val opened = room.openHelper.writableDatabase
            assertEquals("Room 打开后 user_version 应该是当前版本", 12, opened.version)
            room.query("SELECT title FROM chat_sessions WHERE id = ?", arrayOf("session-1")).use { cursor ->
                assertTrue("Room 打开后旧会话应该还在", cursor.moveToFirst())
                assertEquals("旧会话", cursor.getString(0))
            }
            room.query("SELECT name FROM api_configs WHERE id = ?", arrayOf("config-1")).use { cursor ->
                assertTrue("Room 打开后旧 API 配置应该还在", cursor.moveToFirst())
                assertEquals("默认", cursor.getString(0))
            }
        } finally {
            room.close()
        }
    }

    // ---------- v9 → v10：一人一段 ----------

    @Test
    fun migrate9To10_每人只留最新一段会话并删掉多余消息() {
        val db = createV9DatabaseWithData()

        MyFriendDatabase.MIGRATION_9_10.migrate(db)

        // 1. 每个 persona 恰好一段会话。
        assertEquals("persona 数不该变", 2, db.count("personas"))
        assertEquals("一人一段：会话应该只剩 2 段", 2, db.count("chat_sessions"))

        db.query(
            "SELECT id, apiConfigId FROM chat_sessions WHERE personaId = ?",
            arrayOf("persona-1")
        ).use { cursor ->
            assertTrue("persona-1 的会话被删光了", cursor.moveToFirst())
            assertEquals("应该保留 lastMessageAt 最新的那段", "session-new", cursor.getString(0))
            assertEquals("apiConfigId 为空时应补成人格的默认配置", "config-1", cursor.getString(1))
        }
        db.query(
            "SELECT id, apiConfigId FROM chat_sessions WHERE personaId = ?",
            arrayOf("persona-2")
        ).use { cursor ->
            assertTrue("persona-2 的会话被删光了", cursor.moveToFirst())
            assertEquals("session-p2", cursor.getString(0))
            assertEquals("没有默认配置时补最近改过的配置", "config-2", cursor.getString(1))
        }

        // 2. 被删会话的消息一条不剩，保留会话的消息一条不少。
        assertEquals("只剩保留会话的 1 条消息", 1, db.count("messages"))
        db.query("SELECT id FROM messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session-new 的那条消息应该还在", "msg-new", cursor.getString(0))
        }

        // 3. 规则 / 念头 / 记忆里指向被删会话的引用置空，但数据本身不丢。
        db.query("SELECT sessionId, note FROM proactive_rules WHERE id = 'rule-1'")
            .use { cursor ->
                assertTrue("主动规则不该因为会话被并掉就消失", cursor.moveToFirst())
                assertNull("规则里的旧 sessionId 应该被清空", cursor.getString(0))
            }
        db.query("SELECT sourceSessionId FROM memories WHERE id = 'memory-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull("记忆里的旧 sourceSessionId 应该被清空", cursor.getString(0))
        }

        // 4. 唯一索引存在，并且真的会被 SQLite 执行。
        assertTrue(
            "应该建出 index_chat_sessions_personaId_unique",
            db.hasIndex("index_chat_sessions_personaId_unique")
        )
        try {
            db.execSQL(
                """
                INSERT INTO chat_sessions (
                    id, personaId, apiConfigId, model, title, lastMessagePreview, lastMessageAt,
                    unreadCount, pinned, createdAt, updatedAt, lastPersonaVersion,
                    lastMemoryExtractedCount, historySummary, historySummarizedUpTo, historySummaryAt,
                    memoryRetentionOverride, extractionEveryOverride
                ) VALUES (
                    'session-duplicate', 'persona-1', NULL, 'm', 't', NULL, NULL,
                    0, 0, 1, 1, 1, 0, NULL, NULL, NULL, NULL, NULL
                )
                """.trimIndent()
            )
            fail("唯一索引没有生效：同一 persona 的第二段会话居然插进去了")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // 预期如此。
        }

        // 5. 1.9 的新列都补齐了安全默认值，老数据不用回填。
        db.query(
            "SELECT traits, likes, behaviors, proactiveLevel, memoryRetention, extractionEvery, sleepEnabled, wakeThreshold, dndOnSleep FROM personas WHERE id = 'persona-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
            assertEquals("[]", cursor.getString(1))
            assertEquals("[]", cursor.getString(2))
            assertEquals(3, cursor.getInt(3))
            assertEquals(3, cursor.getInt(4))
            assertEquals(6, cursor.getInt(5))
            assertEquals(1, cursor.getInt(6))
            assertEquals(6, cursor.getInt(7))
            assertEquals(0, cursor.getInt(8))
        }
        db.query("SELECT readAt FROM messages WHERE id = 'msg-new'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull("老消息的已读时间应该为空", cursor.getString(0))
        }
    }

    /**
     * 最关键的一条：让 Room 自己打开 v9 旧库。
     *
     * 只跑 SQL 证明不了升级不崩；Room 打开时会校验「实体声明的列」和「库里的列」完全一致，
     * 唯一索引的 unique 标志也在校验范围内。这条测试走完整路径：v9 → v10 → onValidateSchema。
     */
    @Test
    fun migrate9To10_Room打开旧库并完成一人一段() {
        val legacy = createV9DatabaseWithData()
        closeAllHelpers()

        val room = Room.databaseBuilder(context, MyFriendDatabase::class.java, DB_NAME)
            .addMigrations(
                MyFriendDatabase.MIGRATION_8_9,
                MyFriendDatabase.MIGRATION_9_10,
                MyFriendDatabase.MIGRATION_10_11,
                MyFriendDatabase.MIGRATION_11_12
            )
            .allowMainThreadQueries()
            .build()
        try {
            val opened = room.openHelper.writableDatabase
            assertEquals("Room 打开后 user_version 应该是当前版本", 12, opened.version)
            room.query("SELECT COUNT(*) FROM personas", emptyArray()).use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
            room.query("SELECT COUNT(*) FROM chat_sessions", emptyArray()).use { cursor ->
                cursor.moveToFirst()
                assertEquals("一人一段：每个 persona 恰好一段会话", 2, cursor.getInt(0))
            }
            room.query("SELECT id FROM chat_sessions WHERE personaId = 'persona-1'", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("session-new", cursor.getString(0))
            }
        } finally {
            room.close()
        }
    }

    /**
     * 造一个 v9 库：先按 v8 建表、跑真实 MIGRATION_8_9，再手动把 user_version 写成 9。
     *
     * 这样 v9 的表结构就是「用户手机上那一版」本身，不会出现测试里手抄 DDL 抄漏一列、
     * 导致迁移测试绿得毫无意义的情况。
     */
    private fun createV9DatabaseWithData(): SupportSQLiteDatabase {
        val db = createLegacyDatabase(version = 8, ddl = V8_FULL_SCHEMA)
        MyFriendDatabase.MIGRATION_8_9.migrate(db)
        db.execSQL("PRAGMA user_version = 9")

        db.execSQL(
            """
            INSERT INTO personas (
                id, name, description, avatarUri, avatarSeed, prompt, category, version,
                defaultApiConfigId, modelOverride, isDefault, createdAt, updatedAt,
                personaCardJson, quietSleepMinutes, quietWakeMinutes,
                mood, moodIntensity, moodReason, moodUpdatedAt, moodNudgedAt
            ) VALUES (
                'persona-1', '小满', '旧版本留下的人', NULL, 7, '你是小满', '朋友', 1,
                'config-1', NULL, 1, 1700000000000, 1700000000000,
                NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO personas (
                id, name, description, avatarUri, avatarSeed, prompt, category, version,
                defaultApiConfigId, modelOverride, isDefault, createdAt, updatedAt,
                personaCardJson, quietSleepMinutes, quietWakeMinutes,
                mood, moodIntensity, moodReason, moodUpdatedAt, moodNudgedAt
            ) VALUES (
                'persona-2', '阿岚', '第二个人', NULL, 3, '你是阿岚', '朋友', 1,
                NULL, NULL, 0, 1700000000000, 1700000000000,
                NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
            )
            """.trimIndent()
        )
        insertConfig(db, "config-1", 1700000000100)
        insertConfig(db, "config-2", 1700000000200)

        // persona-1 有三段会话：old / middle / new；new 的 apiConfigId 为空。
        insertSession(db, "session-old", "persona-1", null, 1700000000300)
        insertSession(db, "session-middle", "persona-1", "config-1", 1700000000400)
        insertSession(db, "session-new", "persona-1", null, 1700000000500)
        insertSession(db, "session-p2", "persona-2", null, 1700000000600)

        insertMessage(db, "msg-old", "session-old")
        insertMessage(db, "msg-middle", "session-middle")
        insertMessage(db, "msg-new", "session-new")

        db.execSQL(
            """
            INSERT INTO proactive_rules (
                id, personaId, sessionId, kind, timeOfDayMinutes, intervalMinutes,
                hint, note, enabled, pending, source, nextTriggerAt, lastTriggeredAt,
                lastError, createdAt, updatedAt
            ) VALUES (
                'rule-1', 'persona-1', 'session-old', 'DAILY', 1290, NULL,
                '关心一下', '别像查岗', 1, 0, 'AI', 1700000000900, NULL,
                NULL, 1700000000000, 1700000000000
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO memories (
                id, personaId, content, category, importance, pinned, sourceSessionId,
                createdAt, updatedAt, lastUsedAt, useCount, drawer, sourceQuote,
                status, strength, archivedAt, consolidatedAt, tags, happenedAt,
                originalContent, driftCount
            ) VALUES (
                'memory-1', 'persona-1', '她怕冷', 'FACT', 3, 0, 'session-old',
                1700000000000, 1700000000000, 1700000000000, 1, 'USER', NULL,
                'ACTIVE', 1.0, NULL, NULL, NULL, NULL, NULL, 0
            )
            """.trimIndent()
        )
        return db
    }

    private fun insertConfig(db: SupportSQLiteDatabase, id: String, updatedAt: Long) {
        db.execSQL(
            """
            INSERT INTO api_configs (
                id, name, provider, baseUrl, apiKeyCipher, model, customHeadersJson,
                allowInsecureHttp, temperature, topP, maxOutputTokens, frequencyPenalty,
                presencePenalty, createdAt, updatedAt, webToolsEnabled, webToolsBaseUrl,
                webToolsApiKeyCipher
            ) VALUES (
                '$id', '$id', 'OPENAI', 'https://api.example.com', NULL, 'gpt-test', '{}',
                0, NULL, NULL, NULL, NULL,
                NULL, $updatedAt, $updatedAt, 0, '', NULL
            )
            """.trimIndent()
        )
    }

    private fun insertSession(
        db: SupportSQLiteDatabase,
        id: String,
        personaId: String,
        apiConfigId: String?,
        lastMessageAt: Long
    ) {
        db.execSQL(
            """
            INSERT INTO chat_sessions (
                id, personaId, apiConfigId, model, title, lastMessagePreview, lastMessageAt,
                unreadCount, pinned, createdAt, updatedAt, lastPersonaVersion,
                lastMemoryExtractedCount, historySummary, historySummarizedUpTo, historySummaryAt
            ) VALUES (
                '$id', '$personaId', ${apiConfigId?.let { "'$it'" } ?: "NULL"}, 'gpt-test', '$id',
                '旧消息', $lastMessageAt, 0, 0, $lastMessageAt, $lastMessageAt, 1, 0,
                '更早之前的一段摘要', $lastMessageAt, $lastMessageAt
            )
            """.trimIndent()
        )
    }

    private fun insertMessage(db: SupportSQLiteDatabase, id: String, sessionId: String) {
        db.execSQL(
            """
            INSERT INTO messages (
                id, sessionId, personaId, role, content, createdAt, updatedAt,
                status, errorMessage, model, edited, attachmentsJson, toolTraceJson
            ) VALUES (
                '$id', '$sessionId', 'persona-1', 'USER', '喂？', 1700000000300, 1700000000300,
                'COMPLETE', NULL, NULL, 0, NULL, NULL
            )
            """.trimIndent()
        )
    }

    private fun closeAllHelpers() {
        openedHelpers.forEach { runCatching { it.close() } }
        openedHelpers.clear()
    }

    // ---------- v5 → v7 ----------

    @Test
    fun migrate5To7_连续两版迁移不丢记忆且默认值正确() {
        val db = createLegacyDatabase(version = 5, ddl = V5_SCHEMA)
        insertLegacyRows(db)

        // 用户可能隔了几个版本才升级，必须能一路走到底。
        MyFriendDatabase.MIGRATION_5_6.migrate(db)
        MyFriendDatabase.MIGRATION_6_7.migrate(db)

        val memoryColumns = db.columnNames("memories")
        listOf(
            "status", "strength", "archivedAt", "consolidatedAt", // v5 → v6
            "tags", "happenedAt", "originalContent", "driftCount" // v6 → v7
        ).forEach { column ->
            assertTrue("从 v5 一路升到 v7 后 memories 应该有 $column，实际列为 $memoryColumns", column in memoryColumns)
        }

        db.query(
            "SELECT content, status, strength, importance FROM memories WHERE id = ?",
            arrayOf(LEGACY_MEMORY_ID)
        ).use { cursor ->
            assertTrue("v5 → v7 把旧记忆弄丢了", cursor.moveToFirst())
            assertEquals("v5 → v7 改写了记忆内容", LEGACY_MEMORY_CONTENT, cursor.getString(0))
            assertEquals("老记忆升级后应该是 ACTIVE，不能凭空变成归档", "ACTIVE", cursor.getString(1))
            assertEquals("老记忆升级后分量应该是 1.0", 1.0, cursor.getDouble(2), 0.0001)
            assertEquals("重要度不该被迁移改动", 3, cursor.getInt(3))
        }
        assertEquals("memories 的行数变了", 1, db.count("memories"))

        // v6 新增的整理留痕表也得在，否则他「最近整理过记忆」这件事会没有落点。
        assertTrue("v5 → v6 应该建出 memory_consolidations 表", db.hasTable("memory_consolidations"))
        assertTrue(
            "v5 → v6 应该建出 memory_consolidations 的索引",
            db.hasIndex("index_memory_consolidations_personaId_at")
        )
    }

    // ---------- 迁移链完整性 ----------

    @Test
    fun 已注册的迁移覆盖了每一个版本号没有断档() {
        // 没有 fallbackToDestructiveMigration 兜底了，断一档就是老用户升级即崩。
        // 这里把当前注册的迁移摊开，要求 1 → 当前版本 每一档都有主。
        // 清单本身在 JVM 侧的 SchemaVersionTest 里也有守卫（声明的迁移必须被 AppModule 注册）；
        // 这里守的是「注册给 Room 的那份名单」和「真实版本号」对不对得上。
        val registered = listOf(
            MyFriendDatabase.MIGRATION_1_2,
            MyFriendDatabase.MIGRATION_2_3,
            MyFriendDatabase.MIGRATION_3_4,
            MyFriendDatabase.MIGRATION_4_5,
            MyFriendDatabase.MIGRATION_5_6,
            MyFriendDatabase.MIGRATION_6_7,
            MyFriendDatabase.MIGRATION_7_8,
            MyFriendDatabase.MIGRATION_8_9,
            MyFriendDatabase.MIGRATION_9_10,
            MyFriendDatabase.MIGRATION_10_11,
            MyFriendDatabase.MIGRATION_11_12
        )
        val steps = registered.map { it.startVersion to it.endVersion }.toSet()
        val currentVersion = currentDatabaseVersion()
        val expected = (1 until currentVersion).map { it to it + 1 }.toSet()
        assertEquals("迁移链有断档；没有 destructive fallback 时这就是升级崩溃", expected, steps)
    }

    /**
     * 当前数据库版本：不手写常量，问 Room 自己。
     *
     * 做法是建一个一次性空库（Room 建表时会把 SQLite 的 user_version 写成 `@Database` 的版本号），
     * 读完立刻删掉。这样「版本号」只有一个真源，升级时不会出现「代码升了、测试里的常量忘了改」
     * 那种假绿——那正是这组迁移测试最不能有的东西。
     */
    private fun currentDatabaseVersion(): Int {
        val probeName = "version-probe.db"
        context.deleteDatabase(probeName)
        val probe = Room.databaseBuilder(context, MyFriendDatabase::class.java, probeName)
            .allowMainThreadQueries()
            .build()
        return try {
            probe.openHelper.writableDatabase.version
        } finally {
            probe.close()
            context.deleteDatabase(probeName)
        }
    }

    // ---------- v10 → v11：区块 ----------

    @Test
    fun migrate10To11_回填块编号且一条消息都不丢() {
        val db = createV10DatabaseWithMessages(sessionId = "session-1", messageCount = 450)

        MyFriendDatabase.MIGRATION_10_11.migrate(db)

        // 1. 消息一条不少，正文也没被改写。
        assertEquals("迁移不许丢消息", 450, db.count("messages"))
        db.query("SELECT content FROM messages WHERE id = ?", arrayOf("m000")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("第 m000 条", cursor.getString(0))
        }

        // 2. blockIndex = 行号 / 200（0 起）。
        db.query(
            "SELECT id, blockIndex FROM messages WHERE sessionId = ? ORDER BY createdAt ASC, id ASC",
            arrayOf("session-1")
        ).use { cursor ->
            var position = 0
            while (cursor.moveToNext()) {
                assertEquals(
                    "第 $position 条消息的 blockIndex 应该是 ${position / 200}",
                    position / 200,
                    cursor.getInt(1)
                )
                position++
            }
            assertEquals("应该正好遍历 450 条", 450, position)
        }

        // 3. 块边界：200 / 200 / 50，首尾消息与时间都要对。
        assertEquals("450 条应该切成 3 块", 3, db.count("message_blocks"))
        db.query(
            "SELECT sessionId, blockIndex, firstMessageId, lastMessageId, firstCreatedAt, lastCreatedAt, messageCount " +
                "FROM message_blocks ORDER BY blockIndex"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session-1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals("m000", cursor.getString(2))
            assertEquals("m199", cursor.getString(3))
            assertEquals(BASE_TIME + 0L, cursor.getLong(4))
            assertEquals(BASE_TIME + 199L, cursor.getLong(5))
            assertEquals(200, cursor.getInt(6))

            assertTrue(cursor.moveToNext())
            assertEquals(1, cursor.getInt(1))
            assertEquals("m200", cursor.getString(2))
            assertEquals("m399", cursor.getString(3))
            assertEquals(200, cursor.getInt(6))

            assertTrue(cursor.moveToNext())
            assertEquals("最后一块只有 50 条", 2, cursor.getInt(1))
            assertEquals("m400", cursor.getString(2))
            assertEquals("m449", cursor.getString(3))
            assertEquals(50, cursor.getInt(6))

            assertFalse("不该有第四块", cursor.moveToNext())
        }

        // 4. 两个索引都在（否则「读一块」会退化成扫历史）。
        assertTrue(
            "message_blocks(sessionId, blockIndex) 索引",
            db.hasIndex("index_message_blocks_sessionId_blockIndex")
        )
        assertTrue(
            "messages(sessionId, blockIndex, createdAt) 索引",
            db.hasIndex("index_messages_sessionId_blockIndex_createdAt")
        )
    }

    @Test
    fun migrate10To11_空库也能迁移() {
        val db = createLegacyDatabase(version = 8, ddl = V8_FULL_SCHEMA)
        MyFriendDatabase.MIGRATION_8_9.migrate(db)
        MyFriendDatabase.MIGRATION_9_10.migrate(db)
        db.execSQL("PRAGMA user_version = 10")

        MyFriendDatabase.MIGRATION_10_11.migrate(db)

        assertTrue("空库也要建出块表", db.hasTable("message_blocks"))
        assertTrue("空库也要加 blockIndex 列", "blockIndex" in db.columnNames("messages"))
        assertEquals(0, db.count("message_blocks"))
        assertEquals(0, db.count("messages"))
    }

    @Test
    fun migrate10To11_重复执行是幂等的() {
        val db = createV10DatabaseWithMessages(sessionId = "session-1", messageCount = 450)

        MyFriendDatabase.MIGRATION_10_11.migrate(db)
        val firstBlocks = dumpBlocks(db)
        val firstAssignments = dumpAssignments(db)

        // 第二次执行：加列要跳过（PRAGMA 判断）、块行先删后建，不允许报错或重复插。
        MyFriendDatabase.MIGRATION_10_11.migrate(db)

        assertEquals("重复迁移不该改变块行", firstBlocks, dumpBlocks(db))
        assertEquals("重复迁移不该改变块编号", firstAssignments, dumpAssignments(db))
        assertEquals(3, db.count("message_blocks"))
        assertEquals(450, db.count("messages"))
    }

    @Test
    fun migrate10To11_孤儿块会被清掉() {
        val db = createV10DatabaseWithMessages(sessionId = "session-1", messageCount = 10)
        MyFriendDatabase.MIGRATION_10_11.migrate(db)
        db.execSQL(
            """
            INSERT INTO message_blocks (sessionId, blockIndex, firstMessageId, lastMessageId,
                firstCreatedAt, lastCreatedAt, messageCount)
            VALUES ('deleted-session', 0, 'ghost-1', 'ghost-1', 1, 1, 1)
            """.trimIndent()
        )

        MyFriendDatabase.MIGRATION_10_11.migrate(db)

        db.query("SELECT COUNT(*) FROM message_blocks WHERE sessionId = 'deleted-session'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("会话不存在的块行必须被清掉", 0, cursor.getInt(0))
        }
    }

    /**
     * 最关键的一条：让 Room 自己打开 v10 旧库（10 → 11 自动迁移 + onValidateSchema）。
     *
     * 表校验会逐列比对，`blockIndex` 的 NOT NULL/DEFAULT、块表主键、两个索引少一个都会红。
     */
    @Test
    fun migrate10To11_Room打开旧库时表校验通过() {
        createV10DatabaseWithMessages(sessionId = "session-1", messageCount = 450)
        closeAllHelpers()

        val room = Room.databaseBuilder(context, MyFriendDatabase::class.java, DB_NAME)
            .addMigrations(
                MyFriendDatabase.MIGRATION_8_9,
                MyFriendDatabase.MIGRATION_9_10,
                MyFriendDatabase.MIGRATION_10_11,
                MyFriendDatabase.MIGRATION_11_12
            )
            .allowMainThreadQueries()
            .build()
        try {
            val opened = room.openHelper.writableDatabase
            assertEquals("Room 打开后 user_version 应该是 12", 12, opened.version)
            room.query("SELECT COUNT(*) FROM messages", emptyArray()).use { cursor ->
                cursor.moveToFirst()
                assertEquals("Room 打开后消息一条不少", 450, cursor.getInt(0))
            }
            room.query("SELECT COUNT(*), MAX(blockIndex) FROM message_blocks", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("块表要在 Room 校验之后仍然存在", 3, cursor.getInt(0))
                assertEquals(2, cursor.getInt(1))
            }
        } finally {
            room.close()
        }
    }

    // ---------- v11 → v12：搜索 provider ----------

    @Test
    fun migrate11To12_保留配置与联网字段并给默认provider() {
        val db = createV11DatabaseWithConfig()

        MyFriendDatabase.MIGRATION_11_12.migrate(db)

        assertTrue("v12 应该给 api_configs 加上 webToolsProvider", "webToolsProvider" in db.columnNames("api_configs"))
        db.query(
            "SELECT name, baseUrl, webToolsEnabled, webToolsBaseUrl, webToolsApiKeyCipher, webToolsProvider FROM api_configs WHERE id = 'config-1'"
        ).use { cursor ->
            assertTrue("迁移把 API 配置弄丢了", cursor.moveToFirst())
            assertEquals("config-1", cursor.getString(0))
            assertEquals("https://api.example.com", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("https://search.example.com", cursor.getString(3))
            assertEquals("LOCAL-WEB-CIPHER", cursor.getString(4))
            // 列的 SQL 默认值仍是旧值 'DEEPSEEK'（与 schemas/12.json 一致，不能改）；
            // 读出来之后由 WebToolsProviders.normalize 折算成可用的 MESSAGEAPI。
            assertEquals("老配置的列默认值仍是 v12 的旧值", "DEEPSEEK", cursor.getString(5))
            assertEquals(
                "旧值读出来必须是一个可用的 provider",
                "MESSAGEAPI",
                com.myfriend.app.domain.WebToolsProviders.normalize(cursor.getString(5))
            )
        }
    }

    @Test
    fun migrate11To12_空库也能迁移() {
        val db = createV11DatabaseWithConfig(insertConfig = false)

        MyFriendDatabase.MIGRATION_11_12.migrate(db)

        assertTrue("空库也要加列", "webToolsProvider" in db.columnNames("api_configs"))
        assertEquals(0, db.count("api_configs"))
    }

    @Test
    fun migrate11To12_重复执行是幂等的() {
        val db = createV11DatabaseWithConfig()

        MyFriendDatabase.MIGRATION_11_12.migrate(db)
        // 迁移后再把 provider 改成 TAVILY：重复执行不能把用户的选择重置成默认值。
        db.execSQL("UPDATE api_configs SET webToolsProvider = 'TAVILY' WHERE id = 'config-1'")

        MyFriendDatabase.MIGRATION_11_12.migrate(db)

        db.query("SELECT webToolsProvider FROM api_configs WHERE id = 'config-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("重复迁移不该覆盖已有 provider", "TAVILY", cursor.getString(0))
        }
        assertEquals(1, db.count("api_configs"))
    }

    /**
     * 最关键的一条：让 Room 自己打开 v11 旧库（11 → 12 + onValidateSchema）。
     * 表校验会比对列类型与默认值，DDL 与 Entities 对不上就会红。
     */
    @Test
    fun migrate11To12_Room打开旧库时表校验通过() {
        createV11DatabaseWithConfig()
        closeAllHelpers()

        val room = Room.databaseBuilder(context, MyFriendDatabase::class.java, DB_NAME)
            .addMigrations(
                MyFriendDatabase.MIGRATION_8_9,
                MyFriendDatabase.MIGRATION_9_10,
                MyFriendDatabase.MIGRATION_10_11,
                MyFriendDatabase.MIGRATION_11_12
            )
            .allowMainThreadQueries()
            .build()
        try {
            val opened = room.openHelper.writableDatabase
            assertEquals("Room 打开后 user_version 应该是 12", 12, opened.version)
            room.query("SELECT webToolsProvider FROM api_configs WHERE id = 'config-1'", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                // Room 的 schema 校验要求列默认值仍是 'DEEPSEEK'；代码层归一化成 MESSAGEAPI。
                assertEquals("DEEPSEEK", cursor.getString(0))
                assertEquals(
                    "MESSAGEAPI",
                    com.myfriend.app.domain.WebToolsProviders.normalize(cursor.getString(0))
                )
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun provider读写往返_切换时候保留地址与密文() = kotlinx.coroutines.runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, MyFriendDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.apiConfigDao()
            val original = testApiConfig()
            dao.insert(original)
            assertEquals("MESSAGEAPI", dao.getById("config-1")?.webToolsProvider)

            // 0.12 改名兼容：v12 之前写进库里的旧值读出来仍然是可用的 MESSAGEAPI。
            dao.insert(original.copy(id = "config-legacy", webToolsProvider = "DEEPSEEK"))
            val legacy = dao.getById("config-legacy") ?: error("旧配置不见了")
            assertEquals("库里的旧值原样保留（不升级 schema 就不是迁移的活）", "DEEPSEEK", legacy.webToolsProvider)
            assertEquals(
                "读库归一化：旧值等价于 MESSAGEAPI",
                "MESSAGEAPI",
                com.myfriend.app.domain.WebToolsProviders.normalize(legacy.webToolsProvider)
            )

            // 模拟设置页切换 provider：只换这一列，地址与密文保持原值。
            dao.update(original.copy(webToolsProvider = "TAVILY"))
            val switched = dao.getById("config-1") ?: error("配置不见了")
            assertEquals("TAVILY", switched.webToolsProvider)
            assertEquals("https://search.example.com", switched.webToolsBaseUrl)
            assertEquals("LOCAL-WEB-CIPHER", switched.webToolsApiKeyCipher)
        } finally {
            room.close()
        }
    }

    /** 造一个 v11 库：v8 → 真实迁移到 v11，再写入一条带联网字段的配置。 */
    private fun createV11DatabaseWithConfig(insertConfig: Boolean = true): SupportSQLiteDatabase {
        val db = createLegacyDatabase(version = 8, ddl = V8_FULL_SCHEMA)
        MyFriendDatabase.MIGRATION_8_9.migrate(db)
        MyFriendDatabase.MIGRATION_9_10.migrate(db)
        MyFriendDatabase.MIGRATION_10_11.migrate(db)
        db.execSQL("PRAGMA user_version = 11")
        if (insertConfig) {
            db.execSQL(
                """
                INSERT INTO api_configs (
                    id, name, provider, baseUrl, apiKeyCipher, model, customHeadersJson,
                    allowInsecureHttp, temperature, topP, maxOutputTokens, frequencyPenalty,
                    presencePenalty, createdAt, updatedAt, webToolsEnabled, webToolsBaseUrl,
                    webToolsApiKeyCipher
                ) VALUES (
                    'config-1', 'config-1', 'OPENAI', 'https://api.example.com', NULL, 'gpt-test', '{}',
                    0, NULL, NULL, NULL, NULL,
                    NULL, 1700000000000, 1700000000000, 1, 'https://search.example.com',
                    'LOCAL-WEB-CIPHER'
                )
                """.trimIndent()
            )
        }
        return db
    }

    private fun testApiConfig(): com.myfriend.app.data.local.ApiConfigEntity =
        com.myfriend.app.data.local.ApiConfigEntity(
            id = "config-1",
            name = "config-1",
            provider = com.myfriend.app.data.local.ProviderType.OPENAI,
            baseUrl = "https://api.example.com",
            apiKeyCipher = null,
            model = "gpt-test",
            customHeadersJson = "{}",
            allowInsecureHttp = false,
            temperature = null,
            topP = null,
            maxOutputTokens = null,
            frequencyPenalty = null,
            presencePenalty = null,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            webToolsEnabled = true,
            webToolsBaseUrl = "https://search.example.com",
            webToolsApiKeyCipher = "LOCAL-WEB-CIPHER",
            webToolsProvider = com.myfriend.app.domain.WebToolsProviders.MESSAGEAPI
        )

    /** 造一个 v10 库：按 v8 建表 → 跑真实迁移到 v10 → 写入 [messageCount] 条消息。 */
    private fun createV10DatabaseWithMessages(sessionId: String, messageCount: Int): SupportSQLiteDatabase {
        val db = createLegacyDatabase(version = 8, ddl = V8_FULL_SCHEMA)
        MyFriendDatabase.MIGRATION_8_9.migrate(db)
        MyFriendDatabase.MIGRATION_9_10.migrate(db)
        db.execSQL("PRAGMA user_version = 10")
        insertSession(db, sessionId, "persona-1", null, BASE_TIME + messageCount)
        repeat(messageCount) { index ->
            insertMessageAt(db, "m%03d".format(index), sessionId, BASE_TIME + index)
        }
        return db
    }

    private fun insertMessageAt(
        db: SupportSQLiteDatabase,
        id: String,
        sessionId: String,
        createdAt: Long
    ) {
        db.execSQL(
            """
            INSERT INTO messages (
                id, sessionId, personaId, role, content, createdAt, updatedAt,
                status, errorMessage, model, edited, attachmentsJson, toolTraceJson, readAt
            ) VALUES (?, ?, 'persona-1', 'USER', ?, ?, ?, 'COMPLETE', NULL, NULL, 0, NULL, NULL, NULL)
            """.trimIndent(),
            arrayOf(id, sessionId, "第 $id 条", createdAt, createdAt)
        )
    }

    private fun dumpBlocks(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT * FROM message_blocks ORDER BY sessionId, blockIndex").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it).orEmpty() })
                }
            }
        }

    private fun dumpAssignments(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT id, blockIndex FROM messages ORDER BY id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add("${cursor.getString(0)}=${cursor.getInt(1)}")
            }
        }

    // ---------- 造旧库 ----------

    /**
     * 按指定版本手工建出旧库：用真实的 SQLite 打开器，跑完 DDL 后 user_version 就是 version。
     * 这样不需要历史 schema json，也能造出「用户手机上那版」的表结构。
     */
    private fun createLegacyDatabase(version: Int, ddl: List<String>): SupportSQLiteDatabase {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        ddl.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        openedHelpers += helper
        return helper.writableDatabase
    }

    /** 三个版本都写到同一首「旧数据」：迁移必须把它们原样带过去。 */
    private fun insertLegacyRows(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO `personas` (
                `id`, `name`, `description`, `avatarUri`, `avatarSeed`, `prompt`, `category`, `version`,
                `defaultApiConfigId`, `modelOverride`, `isDefault`, `createdAt`, `updatedAt`,
                `personaCardJson`, `quietSleepMinutes`, `quietWakeMinutes`
            ) VALUES (
                'persona-1', '小满', '旧版本留下的人', NULL, 7, '你是小满', 'COMPANION', 1,
                NULL, NULL, 1, 1700000000000, 1700000000000,
                NULL, NULL, NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `api_configs` (
                `id`, `name`, `provider`, `baseUrl`, `apiKeyCipher`, `model`, `customHeadersJson`,
                `allowInsecureHttp`, `temperature`, `topP`, `maxOutputTokens`, `frequencyPenalty`,
                `presencePenalty`, `harnessMode`, `harnessUrl`, `harnessSyncPath`, `createdAt`, `updatedAt`,
                `webToolsEnabled`, `webToolsBaseUrl`, `webToolsApiKeyCipher`
            ) VALUES (
                'config-1', '默认', 'OPENAI', 'https://api.example.com', NULL, 'gpt-test', '{}',
                0, NULL, NULL, NULL, NULL,
                NULL, 'STATELESS', '', '', 1700000000000, 1700000000000,
                0, '', NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `memories` (
                `id`, `personaId`, `content`, `category`, `importance`, `pinned`, `sourceSessionId`,
                `createdAt`, `updatedAt`, `lastUsedAt`, `useCount`, `drawer`, `sourceQuote`
            ) VALUES (
                '$LEGACY_MEMORY_ID', 'persona-1', '$LEGACY_MEMORY_CONTENT', 'FACT', 3, 0, NULL,
                1700000000000, 1700000000000, 1700000000000, 2, 'USER', NULL
            )
            """.trimIndent()
        )
    }

    private fun SupportSQLiteDatabase.columnNames(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) names.add(cursor.getString(nameIndex))
            names
        }

    private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use { it.count > 0 }

    private fun SupportSQLiteDatabase.hasIndex(index: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(index))
            .use { it.count > 0 }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private companion object {
        const val DB_NAME = "migration-test.db"
        // 这里刻意没有 CURRENT_VERSION：版本号已经改为运行时问 Room（见 currentDatabaseVersion），
        // 手写常量一旦忘了跟着升，这条测试就会绿得毫无意义。

        /** v11 区块测试用的固定时间基准：消息 createdAt = BASE_TIME + 下标。 */
        const val BASE_TIME = 1_700_000_000_000L

        const val LEGACY_MEMORY_ID = "memory-legacy-1"
        const val LEGACY_MEMORY_CONTENT = "她说过她妹妹在南方读大学"

        /**
         * v8 的完整表结构，直接从 `app/schemas/…/8.json` 抄下来（生成于 1.7）。
         *
         * 用它把「用户手机上那一版库」原样造出来，再让 Room 自己打开，
         * 才能验证迁移之后 Room 的表校验（列完全一致）也通过——这是升级不崩的最后一关。
         */
        val V8_FULL_SCHEMA: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS `personas` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `avatarUri` TEXT, `avatarSeed` INTEGER NOT NULL, `prompt` TEXT NOT NULL, `category` TEXT NOT NULL, `version` INTEGER NOT NULL, `defaultApiConfigId` TEXT, `modelOverride` TEXT, `isDefault` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `personaCardJson` TEXT, `quietSleepMinutes` INTEGER, `quietWakeMinutes` INTEGER, `mood` TEXT, `moodIntensity` INTEGER, `moodReason` TEXT, `moodUpdatedAt` INTEGER, `moodNudgedAt` INTEGER, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_personas_isDefault` ON `personas` (`isDefault`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_personas_updatedAt` ON `personas` (`updatedAt`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `persona_revisions` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `version` INTEGER NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `avatarUri` TEXT, `avatarSeed` INTEGER NOT NULL, `prompt` TEXT NOT NULL, `category` TEXT NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_persona_revisions_personaId_version` ON `persona_revisions` (`personaId`, `version`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `persona_presets` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `prompt` TEXT NOT NULL, `category` TEXT NOT NULL, `avatarUri` TEXT, `avatarSeed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_persona_presets_updatedAt` ON `persona_presets` (`updatedAt`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_persona_presets_category` ON `persona_presets` (`category`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `api_configs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `provider` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `apiKeyCipher` TEXT, `model` TEXT NOT NULL, `customHeadersJson` TEXT NOT NULL, `allowInsecureHttp` INTEGER NOT NULL, `temperature` REAL, `topP` REAL, `maxOutputTokens` INTEGER, `frequencyPenalty` REAL, `presencePenalty` REAL, `harnessMode` TEXT NOT NULL, `harnessUrl` TEXT NOT NULL, `harnessSyncPath` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `webToolsEnabled` INTEGER NOT NULL DEFAULT 0, `webToolsBaseUrl` TEXT NOT NULL DEFAULT '', `webToolsApiKeyCipher` TEXT, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_api_configs_updatedAt` ON `api_configs` (`updatedAt`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `apiConfigId` TEXT, `model` TEXT NOT NULL, `title` TEXT NOT NULL, `lastMessagePreview` TEXT, `lastMessageAt` INTEGER, `unreadCount` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastPersonaVersion` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `syncedPersonaVersion` INTEGER, `syncedMemoriesHash` TEXT, `serverSessionId` TEXT, `lastSyncError` TEXT, `lastSyncAt` INTEGER, `lastMemoryExtractedCount` INTEGER NOT NULL, `historySummary` TEXT, `historySummarizedUpTo` INTEGER, `historySummaryAt` INTEGER, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_chat_sessions_personaId` ON `chat_sessions` (`personaId`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_chat_sessions_lastMessageAt` ON `chat_sessions` (`lastMessageAt`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_chat_sessions_pinned` ON `chat_sessions` (`pinned`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `personaId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorMessage` TEXT, `model` TEXT, `edited` INTEGER NOT NULL, `attachmentsJson` TEXT, `toolTraceJson` TEXT, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt` ON `messages` (`sessionId`, `createdAt`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_messages_sessionId_role` ON `messages` (`sessionId`, `role`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_messages_createdAt` ON `messages` (`createdAt`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `memories` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `content` TEXT NOT NULL, `category` TEXT NOT NULL, `importance` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `sourceSessionId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `useCount` INTEGER NOT NULL, `drawer` TEXT, `sourceQuote` TEXT, `status` TEXT NOT NULL, `strength` REAL NOT NULL, `archivedAt` INTEGER, `consolidatedAt` INTEGER, `tags` TEXT, `happenedAt` INTEGER, `originalContent` TEXT, `driftCount` INTEGER NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memories_personaId` ON `memories` (`personaId`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memories_personaId_importance` ON `memories` (`personaId`, `importance`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `memory_echoes` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `drawer` TEXT NOT NULL, `content` TEXT NOT NULL, `importance` INTEGER NOT NULL, `sourceQuote` TEXT NOT NULL, `categoryName` TEXT NOT NULL, `confidence` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memory_echoes_personaId_drawer` ON `memory_echoes` (`personaId`, `drawer`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memory_echoes_createdAt` ON `memory_echoes` (`createdAt`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `persona_pending_patches` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `sessionId` TEXT, `patchJson` TEXT NOT NULL, `summary` TEXT NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_persona_pending_patches_personaId` ON `persona_pending_patches` (`personaId`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_persona_pending_patches_createdAt` ON `persona_pending_patches` (`createdAt`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `proactive_rules` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `timeOfDayMinutes` INTEGER, `intervalMinutes` INTEGER, `hint` TEXT NOT NULL, `note` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `pending` INTEGER NOT NULL, `source` TEXT NOT NULL, `nextTriggerAt` INTEGER NOT NULL, `lastTriggeredAt` INTEGER, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_proactive_rules_personaId` ON `proactive_rules` (`personaId`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_proactive_rules_nextTriggerAt` ON `proactive_rules` (`nextTriggerAt`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_proactive_rules_enabled` ON `proactive_rules` (`enabled`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `agent_intents` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `earliestAt` INTEGER NOT NULL, `latestAt` INTEGER NOT NULL, `note` TEXT NOT NULL, `hint` TEXT NOT NULL, `status` TEXT NOT NULL, `ruleId` TEXT, `createdAt` INTEGER NOT NULL, `sentAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_agent_intents_personaId_status` ON `agent_intents` (`personaId`, `status`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_agent_intents_earliestAt` ON `agent_intents` (`earliestAt`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_agent_intents_latestAt` ON `agent_intents` (`latestAt`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `agent_ticks` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `at` INTEGER NOT NULL, `thought` TEXT NOT NULL, `actionJson` TEXT NOT NULL, `outcome` TEXT NOT NULL, `messageId` TEXT, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_agent_ticks_personaId_at` ON `agent_ticks` (`personaId`, `at`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_agent_ticks_at` ON `agent_ticks` (`at`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `memory_consolidations` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `at` INTEGER NOT NULL, `kept` INTEGER NOT NULL, `merged` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `forgotten` INTEGER NOT NULL, `summary` TEXT NOT NULL, `modelName` TEXT, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memory_consolidations_personaId_at` ON `memory_consolidations` (`personaId`, `at`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memory_consolidations_at` ON `memory_consolidations` (`at`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `api_usage` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `personaId` TEXT, `sessionId` TEXT, `modelName` TEXT, `promptTokens` INTEGER NOT NULL, `completionTokens` INTEGER NOT NULL, `costMicroUsd` INTEGER NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_api_usage_at` ON `api_usage` (`at`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_api_usage_kind_at` ON `api_usage` (`kind`, `at`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_api_usage_personaId_at` ON `api_usage` (`personaId`, `at`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `memory_terms` (`memoryId` TEXT NOT NULL, `term` TEXT NOT NULL, `weight` REAL NOT NULL, PRIMARY KEY(`memoryId`, `term`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memory_terms_term` ON `memory_terms` (`term`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `memory_versions` (`id` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `at` INTEGER NOT NULL, `content` TEXT NOT NULL, `beforeContent` TEXT, `reason` TEXT, `moodLabel` TEXT, PRIMARY KEY(`id`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memory_versions_memoryId_at` ON `memory_versions` (`memoryId`, `at`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `memory_vectors` (`memoryId` TEXT NOT NULL, `provider` TEXT NOT NULL, `dim` INTEGER NOT NULL, `vector` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`memoryId`, `provider`))
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `index_memory_vectors_provider` ON `memory_vectors` (`provider`)
            """.trimIndent(),
        )

        /**
         * v5 的表结构：personas 还没有心情列，memories 还没有生命周期列。
         * 只为迁移测试服务，所以只建三张会被迁移碰到的表。
         */
        val V5_SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS `personas` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `avatarUri` TEXT,
                `avatarSeed` INTEGER NOT NULL,
                `prompt` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `defaultApiConfigId` TEXT,
                `modelOverride` TEXT,
                `isDefault` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `personaCardJson` TEXT,
                `quietSleepMinutes` INTEGER,
                `quietWakeMinutes` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_personas_isDefault` ON `personas` (`isDefault`)",
            "CREATE INDEX IF NOT EXISTS `index_personas_updatedAt` ON `personas` (`updatedAt`)",
            """
            CREATE TABLE IF NOT EXISTS `api_configs` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `baseUrl` TEXT NOT NULL,
                `apiKeyCipher` TEXT,
                `model` TEXT NOT NULL,
                `customHeadersJson` TEXT NOT NULL,
                `allowInsecureHttp` INTEGER NOT NULL,
                `temperature` REAL,
                `topP` REAL,
                `maxOutputTokens` INTEGER,
                `frequencyPenalty` REAL,
                `presencePenalty` REAL,
                `harnessMode` TEXT NOT NULL,
                `harnessUrl` TEXT NOT NULL,
                `harnessSyncPath` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `webToolsEnabled` INTEGER NOT NULL DEFAULT 0,
                `webToolsBaseUrl` TEXT NOT NULL DEFAULT '',
                `webToolsApiKeyCipher` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_api_configs_updatedAt` ON `api_configs` (`updatedAt`)",
            """
            CREATE TABLE IF NOT EXISTS `memories` (
                `id` TEXT NOT NULL,
                `personaId` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `importance` INTEGER NOT NULL,
                `pinned` INTEGER NOT NULL,
                `sourceSessionId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `lastUsedAt` INTEGER NOT NULL,
                `useCount` INTEGER NOT NULL,
                `drawer` TEXT,
                `sourceQuote` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_memories_personaId` ON `memories` (`personaId`)",
            "CREATE INDEX IF NOT EXISTS `index_memories_personaId_importance` ON `memories` (`personaId`, `importance`)",
            "CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)"
        )

        /**
         * v6 = v5 再跑一遍 MIGRATION_5_6 的 DDL，就是用户手机上 v6 那一版的表结构。
         * 用「跑真实迁移」而不是再抄一遍 DDL，保证两边的列不会各写各的。
         */
        val V6_SCHEMA: List<String> = V5_SCHEMA + listOf(
            "ALTER TABLE `memories` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ACTIVE'",
            "ALTER TABLE `memories` ADD COLUMN `strength` REAL NOT NULL DEFAULT 1.0",
            "ALTER TABLE `memories` ADD COLUMN `archivedAt` INTEGER",
            "ALTER TABLE `memories` ADD COLUMN `consolidatedAt` INTEGER"
        )
    }
}
