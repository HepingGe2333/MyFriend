package com.yap.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PersonaEntity::class,
        PersonaRevisionEntity::class,
        PersonaPresetEntity::class,
        ApiConfigEntity::class,
        ChatSessionEntity::class,
        MessageEntity::class,
        MessageBlockEntity::class,
        MemoryEntity::class,
        MemoryEchoEntity::class,
        PersonaPendingPatchEntity::class,
        ProactiveRuleEntity::class,
        AgentIntentEntity::class,
        AgentTickEntity::class,
        MemoryConsolidationEntity::class,
        ApiUsageEntity::class,
        MemoryTermEntity::class,
        MemoryVersionEntity::class,
        MemoryVectorEntity::class,
        DiaryNoteEntity::class,
        StickerEntity::class,
        MentalActivityEntity::class,
        SessionMemoryEntity::class
    ],
    version = 20,
    // 把每一版表结构导出成 json，落到 app/schemas/ 下。
    // 这是以后写正经迁移测试、以及 review 时「这一版到底动了什么」的唯一凭据。
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class YapDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun revisionDao(): PersonaRevisionDao
    abstract fun presetDao(): PersonaPresetDao
    abstract fun apiConfigDao(): ApiConfigDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun messageDao(): MessageDao
    abstract fun messageBlockDao(): MessageBlockDao
    abstract fun memoryDao(): MemoryDao
    abstract fun proactiveRuleDao(): ProactiveRuleDao
    abstract fun memoryEchoDao(): MemoryEchoDao
    abstract fun personaPendingPatchDao(): PersonaPendingPatchDao
    abstract fun agentIntentDao(): AgentIntentDao
    abstract fun agentTickDao(): AgentTickDao
    abstract fun memoryConsolidationDao(): MemoryConsolidationDao
    abstract fun apiUsageDao(): ApiUsageDao
    abstract fun memoryTermDao(): MemoryTermDao
    abstract fun memoryVersionDao(): MemoryVersionDao
    abstract fun memoryVectorDao(): MemoryVectorDao
    abstract fun diaryNoteDao(): DiaryNoteDao
    abstract fun stickerDao(): StickerDao
    abstract fun mentalActivityDao(): MentalActivityDao
    abstract fun sessionMemoryDao(): SessionMemoryDao

    /** 媒体文件引用计数（0.12 去重收尾）：纯查询，不新增表，也不动 schema。 */
    abstract fun mediaReferenceDao(): MediaReferenceDao

    companion object {
        const val NAME = "yap.db"

        /**
         * v20：0.15 模型优化开关（[com.yap.app.domain.ModelProfile]）。
         *
         * `personas.modelProfile` 与 `persona_presets.modelProfile` 各加一列 `TEXT NOT NULL
         * DEFAULT 'AUTO'`：
         * - 老库升级后都是 `'AUTO'`（按 model 名推断），行为与 v19 一致——
         *   唯一的区别是 DeepSeek 系模型会多拿到一段 `# 工具使用` 规则，这正是这个开关的默认档；
         * - 预设那张表按「表存在才加列」处理（非常老的库理论上可能没有它）。
         *
         * 幂等：列先查 `PRAGMA table_info`，可重复执行。
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val personaColumns = columns(db, "personas")
                if (personaColumns.isNotEmpty() && "modelProfile" !in personaColumns) {
                    db.execSQL(
                        "ALTER TABLE `personas` ADD COLUMN `modelProfile` TEXT NOT NULL DEFAULT 'AUTO'"
                    )
                }
                val presetColumns = columns(db, "persona_presets")
                if (presetColumns.isNotEmpty() && "modelProfile" !in presetColumns) {
                    db.execSQL(
                        "ALTER TABLE `persona_presets` ADD COLUMN `modelProfile` TEXT NOT NULL DEFAULT 'AUTO'"
                    )
                }
            }
        }

        /**
         * v19：0.15 上下文/记忆 v2 与人格语言（`docs/design/context-memory-v2.md`）。
         *
         * - `personas.languageTag TEXT`：人格自己的提示词语言（与界面语言无关）；
         * - `session_memory.topicsJson TEXT`：对话主题总结（≤12 段 × 120 字）；
         * - `session_memory.topicUserCount INTEGER NOT NULL DEFAULT 0` / `topicUpdatedAt INTEGER`：
         *   滚动更新的游标与时间。
         *
         * 只加可空列与带默认值的列：老库升级后行为与 v18 一致（人格语言回落中文、
         * 没有主题总结），不需要回填数据。幂等：先查 `PRAGMA table_info`。
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val personaColumns = columns(db, "personas")
                if (personaColumns.isNotEmpty() && "languageTag" !in personaColumns) {
                    db.execSQL("ALTER TABLE `personas` ADD COLUMN `languageTag` TEXT")
                }
                val memoryColumns = columns(db, "session_memory")
                if (memoryColumns.isNotEmpty()) {
                    if ("topicsJson" !in memoryColumns) {
                        db.execSQL("ALTER TABLE `session_memory` ADD COLUMN `topicsJson` TEXT")
                    }
                    if ("topicUserCount" !in memoryColumns) {
                        db.execSQL(
                            "ALTER TABLE `session_memory` ADD COLUMN `topicUserCount` INTEGER NOT NULL DEFAULT 0"
                        )
                    }
                    if ("topicUpdatedAt" !in memoryColumns) {
                        db.execSQL("ALTER TABLE `session_memory` ADD COLUMN `topicUpdatedAt` INTEGER")
                    }
                }
            }
        }

        /**
         * v18：0.14 体验版（`spec/experience/README.md` §2 / §3 / §6）。
         *
         * - `personas` 加三列可空 `TEXT/INTEGER`：`blockedUntil` / `blockReason` / `blockProposalJson`；
         * - `messages` 加 `blocked INTEGER NOT NULL DEFAULT 0`（老消息一律可见）；
         * - 新建 `mental_activities`（心理活动，按人格保留最近 50 条）；
         * - 新建 `session_memory`（每个会话一行短期/长期摘要）。
         *
         * 只加可空列与空表：老库升级后行为与 v17 一致（没人被拉黑、没有念头、没有备忘录）。
         * 幂等：列先查 `PRAGMA table_info`，表/索引用 `IF NOT EXISTS`，可重复执行。
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val personaColumns = columns(db, "personas")
                if (personaColumns.isNotEmpty()) {
                    if ("blockedUntil" !in personaColumns) {
                        db.execSQL("ALTER TABLE `personas` ADD COLUMN `blockedUntil` INTEGER")
                    }
                    if ("blockReason" !in personaColumns) {
                        db.execSQL("ALTER TABLE `personas` ADD COLUMN `blockReason` TEXT")
                    }
                    if ("blockProposalJson" !in personaColumns) {
                        db.execSQL("ALTER TABLE `personas` ADD COLUMN `blockProposalJson` TEXT")
                    }
                }

                val messageColumns = columns(db, "messages")
                if (messageColumns.isNotEmpty() && "blocked" !in messageColumns) {
                    db.execSQL(
                        "ALTER TABLE `messages` ADD COLUMN `blocked` INTEGER NOT NULL DEFAULT 0"
                    )
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mental_activities` (
                        `id` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `at` INTEGER NOT NULL,
                        `thought` TEXT NOT NULL,
                        `moodLabel` TEXT,
                        `moodIntensity` INTEGER,
                        `moodValence` INTEGER,
                        `moodArousal` INTEGER,
                        `moodDominance` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mental_activities_personaId_at` " +
                        "ON `mental_activities` (`personaId`, `at`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mental_activities_sessionId_at` " +
                        "ON `mental_activities` (`sessionId`, `at`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mental_activities_at` " +
                        "ON `mental_activities` (`at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_memory` (
                        `sessionId` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `shortSummary` TEXT,
                        `longSummary` TEXT,
                        `shortUserCount` INTEGER NOT NULL,
                        `longUserCount` INTEGER NOT NULL,
                        `shortUpdatedAt` INTEGER,
                        `longUpdatedAt` INTEGER,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_memory_personaId` " +
                        "ON `session_memory` (`personaId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_memory_updatedAt` " +
                        "ON `session_memory` (`updatedAt`)"
                )
            }
        }

        /**
         * v17：多维情绪（`spec/mood/README.md` §1）。
         *
         * 给 `personas` 加三列可空 `INTEGER`：
         * - `moodValence`（-2..2 愉快度）；
         * - `moodArousal`（0..5 唤醒度）；
         * - `moodDominance`（0..5 掌控感）。
         *
         * 然后按规范「旧数据迁移」把已有的标签/强度折算进来：valence 由 label 映射
         * （累/烦→-1、平静→0、开心→1），arousal 由 intensity 推，dominance=3。
         *
         * 为什么不把映射写成一条 SQL `CASE`：心情词表在 `MoodLexicon` 里，SQL 再抄一份
         * 就等于有两个真源，以后加词一定会漂。这里直接调 `MoodDimensions.fromLegacy`
         * （与运行时读路径同一个函数），SQL 只负责「把算好的三个数写回这一行」。
         *
         * 只补 `moodValence IS NULL` 且 `mood IS NOT NULL` 的行：
         * - 空心情没有可映射的东西，保持 NULL（读路径会给基准值）；
         * - 重复执行时已经没有 NULL 行可补，天然幂等。
         *
         * 列本身仍然可空：将来换词表/判定的维度是 NULL 时才代表「还没算过」，
         * 不能用一个看起来有值的 0 冒充「他很平静」。
         *
         * 幂等：先查 `PRAGMA table_info`，列已经在就跳过；回填只针对 NULL 行。
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val personaColumns = columns(db, "personas")
                // 防御：极老/被修复脚本动过的库可能连 personas 都没有。没有表就没有旧心情要保，
                // 交给 Room 按 v17 的实体建表即可；这里硬 ALTER 只会让升级当场崩。
                if (personaColumns.isEmpty()) return
                if ("moodValence" !in personaColumns) {
                    db.execSQL("ALTER TABLE `personas` ADD COLUMN `moodValence` INTEGER")
                }
                if ("moodArousal" !in personaColumns) {
                    db.execSQL("ALTER TABLE `personas` ADD COLUMN `moodArousal` INTEGER")
                }
                if ("moodDominance" !in personaColumns) {
                    db.execSQL("ALTER TABLE `personas` ADD COLUMN `moodDominance` INTEGER")
                }
                backfillMoodDimensions(db)
            }
        }

        /**
         * v17 的旧数据回填：把 `(mood, moodIntensity)` 折算成三个维度写回。
         *
         * 先读完再写（不在打开的游标上执行 UPDATE）：人格数量很少（几十条），
         * 用一次列表换「不同 SQLite 版本上的游标行为一致」是值得的。
         */
        private fun backfillMoodDimensions(db: SupportSQLiteDatabase) {
            val rows = mutableListOf<Triple<String, String?, Int?>>()
            db.query(
                "SELECT `id`, `mood`, `moodIntensity` FROM `personas` " +
                    "WHERE `mood` IS NOT NULL AND `mood` != '' AND `moodValence` IS NULL"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += Triple(
                        cursor.getString(0),
                        if (cursor.isNull(1)) null else cursor.getString(1),
                        if (cursor.isNull(2)) null else cursor.getInt(2)
                    )
                }
            }
            rows.forEach { (id, mood, intensity) ->
                val reading = com.yap.app.domain.MoodDimensions.fromLegacy(
                    label = mood.orEmpty(),
                    intensity = intensity ?: 2
                )
                db.execSQL(
                    "UPDATE `personas` SET `moodValence` = ?, `moodArousal` = ?, `moodDominance` = ? WHERE `id` = ?",
                    arrayOf<Any>(reading.valence, reading.arousal, reading.dominance, id)
                )
            }
        }

        /**
         * v16：表情包（`spec/stickers/README.md` / `SCHEMA.md`）。
         *
         * 只新建一张表 + 两条索引，不动任何既有表与既有一行数据：老库升级后 `stickers` 是空的
         * =「他还没有表情」，聊天、记忆、人格、日记一条都不受影响。
         *
         * 幂等：`CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`，
         * 迁移测试与将来的修复脚本可以重复调用（重复执行不报错、也不清数据）。
         *
         * 列名/顺序/可空/默认值必须与 `SCHEMA.md` 逐字一致（两端靠它对齐）。
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stickers` (
                        `id` TEXT NOT NULL,
                        `ownerUserId` TEXT,
                        `personaId` TEXT,
                        `file` TEXT NOT NULL,
                        `mime` TEXT NOT NULL,
                        `width` INTEGER NOT NULL DEFAULT 0,
                        `height` INTEGER NOT NULL DEFAULT 0,
                        `frames` INTEGER NOT NULL DEFAULT 1,
                        `bytes` INTEGER NOT NULL DEFAULT 0,
                        `source` TEXT NOT NULL,
                        `createdBy` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stickers_createdAt` ON `stickers` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stickers_personaId` ON `stickers` (`personaId`)"
                )
            }
        }

        /**
         * v15：日记板（`spec/diary/README.md`）。
         *
         * 只新建一张表，不动任何既有表：老库升级后 `diary_notes` 是空的 = 「他还没写过备忘」，
         * 聊天、记忆、人格一条都不受影响。这是最安全的一类迁移——没有回填、没有重命名、
         * 没有列改写，所以不存在「升级之后他忘了我」的风险。
         *
         * 幂等：`CREATE TABLE IF NOT EXISTS` + 索引同样 `IF NOT EXISTS`，
         * 迁移测试与将来的修复脚本可以重复调用（重复执行不会报错，也不会清数据）。
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `diary_notes` (
                        `id` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `remindAt` INTEGER,
                        `remindedAt` INTEGER,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_diary_notes_personaId` ON `diary_notes` (`personaId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_diary_notes_remindAt` ON `diary_notes` (`remindAt`)"
                )
            }
        }

        /**
         * v14：人格提示词的翻译缓存（`spec/persona-translation/README.md` §4）。
         *
         * 只给 `personas` 加两列可空 `TEXT`：
         * - `translationJson`：`{lang, translatedAt, sourceHash, persona{…}, world{…}}`；
         * - `translationLang`：与 `lang` 同时写，方便不解析 JSON 就判断「这份缓存是不是当前语言」。
         *
         * 不动任何既有数据：老库升级后读出来是 NULL = 「还没翻译过」，下次启动会按需译一次。
         *
         * 幂等：先查 `PRAGMA table_info`，列已经在就跳过（迁移测试与将来的修复脚本会重复调用）。
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val personaColumns = columns(db, "personas")
                if ("translationJson" !in personaColumns) {
                    db.execSQL("ALTER TABLE `personas` ADD COLUMN `translationJson` TEXT")
                }
                if ("translationLang" !in personaColumns) {
                    db.execSQL("ALTER TABLE `personas` ADD COLUMN `translationLang` TEXT")
                }
            }
        }

        /**
         * v13：人设引导（`spec/persona-guidance/README.md` §2 / §3 / §4）。
         *
         * 只加可空列，不动任何既有数据：
         * - `personas` 加 `worldJson` / `speechStyleJson` / `activityJson`；
         * - `persona_presets` 加 `worldJson` / `speechStyleJson`（预设可以带世界与话风，
         *   应用到人格时复制一份，之后预设变化不自动覆盖已有人格，见规范 §2.2）。
         *
         * 全部 `TEXT` 且默认 NULL：老库升级后读出来就是「没有世界 / 没学过话风 / 没在忙」，
         * 行为与 v12 完全一致。
         *
         * 幂等：先查 `PRAGMA table_info`，列已经在就跳过。Room 跑迁移时自带事务，
         * 但同一个 Migration 也可能被迁移测试或将来的「修复脚本」重复调用，
         * 实现成幂等比依赖调用方更稳。
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val personaColumns = columns(db, "personas")
                if ("worldJson" !in personaColumns) {
                    db.execSQL("ALTER TABLE `personas` ADD COLUMN `worldJson` TEXT")
                }
                if ("speechStyleJson" !in personaColumns) {
                    db.execSQL("ALTER TABLE `personas` ADD COLUMN `speechStyleJson` TEXT")
                }
                if ("activityJson" !in personaColumns) {
                    db.execSQL("ALTER TABLE `personas` ADD COLUMN `activityJson` TEXT")
                }
                val presetColumns = columns(db, "persona_presets")
                if ("worldJson" !in presetColumns) {
                    db.execSQL("ALTER TABLE `persona_presets` ADD COLUMN `worldJson` TEXT")
                }
                if ("speechStyleJson" !in presetColumns) {
                    db.execSQL("ALTER TABLE `persona_presets` ADD COLUMN `speechStyleJson` TEXT")
                }
            }
        }

        /**
         * v12：`api_configs.webToolsProvider`（`spec/web-search/README.md` §1）。
         *
         * 只加一列，不动任何既有数据；默认 `'DEEPSEEK'`，所以老库里已有的联网配置升级后
         * 仍走原来的 MessageAPI / DeepSeek 工具端点，换 provider 是用户之后在设置页里显式做的选择。
         *
         * **0.12 改名时这条 SQL 刻意不改**：`DEEPSEEK` 是 v12 的列默认值，已经写进
         * `app/schemas/12.json`，改掉它会让已装 v12 的库 schema 校验失败。旧值读出后由
         * `WebToolsProviders.normalize` 折算成 `MESSAGEAPI`（归一只在代码层，不升 schema）。
         *
         * 幂等：先查 `PRAGMA table_info`，列已经在就跳过。Room 跑迁移时自带事务，
         * 但同一个 Migration 也可能被迁移测试或将来的「修复脚本」重复调用，
         * 实现成幂等比依赖调用方更稳。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if ("webToolsProvider" in apiConfigColumns(db)) return
                db.execSQL(
                    "ALTER TABLE `api_configs` ADD COLUMN `webToolsProvider` TEXT NOT NULL DEFAULT 'DEEPSEEK'"
                )
            }
        }

        /** 读 `api_configs` 现有列名（幂等迁移与测试共用同一段 SQL）。 */
        private fun apiConfigColumns(db: SupportSQLiteDatabase): Set<String> = columns(db, "api_configs")

        /** 读任意表的现有列名（v12/v13 的幂等迁移共用）。 */
        private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }

        /**
         * v11：会话历史的**区块**（规范 `spec/messages-blocks/README.md`）。
         *
         * 两件事：`messages` 加 `blockIndex`，新建 `message_blocks`；然后把老数据按
         * `(createdAt, id)` 顺序回填成 `行号 / 200`。实现见 [MessageBlockMigration]
         * （流式回填，峰值内存与会话长度无关；重复执行安全）。
         *
         * 备份格式不变（仍是 v2）：块元数据是派生数据，导入之后按追加规则重建。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MessageBlockMigration.migrate10To11(db)
            }
        }

        /**
         * v10：一人一段会话。
         *
         * 用户明确要求「只保留最新那段，其余直接删除」，所以这是一次**有意的数据损失**，
         * 不做什么「先备份到影子表」的额外机制：影子表只会让用户在若干年后发现一堆自己不知道的旧会话。
         *
         * 迁移顺序是有讲究的：
         * 1. 先补齐新列（人格细分、会话覆盖、消息已读），这样后面删数据时表结构已经稳定；
         * 2. 删「非最新」会话的消息与摘要（摘要在会话行上，随会话一起走）；
         * 3. 把规则 / 念头 / 记忆里指向被删会话的 `sessionId` 置空——规则、念头、记忆本身属于人格，
         *    它们不该因为会话被并掉就一起消失；
         * 4. 给保留下来的那段补默认 API 配置；
         * 5. 最后才建唯一索引：先删干净再建，否则任何一个人格有两段会话都会让迁移当场失败。
         *
         * 排序键按规格用 `COALESCE(lastMessageAt, updatedAt)`；完全相同的两段用 rowid 兜底，
         * 保证「保留哪一段」是确定的，不会因为 SQLite 的查询计划变化而在不同手机上留下不同的人。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 1. 人格的 1.9 细分字段 -------------------------------------
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `traits` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `likes` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `behaviors` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `backstory` TEXT")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `quirks` TEXT")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `proactiveLevel` INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `memoryRetention` INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `extractionEvery` INTEGER NOT NULL DEFAULT 6")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `sleepEnabled` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `wakeThreshold` INTEGER NOT NULL DEFAULT 6")
                db.execSQL("ALTER TABLE `personas` ADD COLUMN `dndOnSleep` INTEGER NOT NULL DEFAULT 0")

                // ---- 2. 会话级覆盖 + 消息已读 -----------------------------------
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `memoryRetentionOverride` INTEGER")
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `extractionEveryOverride` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `readAt` INTEGER")
                // 「按人看用量」要按 sessionId 聚合；老库没有这个索引，顺手补上。
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_usage_sessionId_at` ON `api_usage` (`sessionId`, `at`)")

                // ---- 3. 每个 persona 只留最新一段 ------------------------------
                // 这条子查询选的是「存在比它更新的一段」的会话，也就是要删的那些。
                val staleSessions = """
                    SELECT s.id FROM `chat_sessions` s WHERE EXISTS (
                        SELECT 1 FROM `chat_sessions` t WHERE t.personaId = s.personaId AND (
                            COALESCE(t.lastMessageAt, t.updatedAt) > COALESCE(s.lastMessageAt, s.updatedAt)
                            OR (
                                COALESCE(t.lastMessageAt, t.updatedAt) = COALESCE(s.lastMessageAt, s.updatedAt)
                                AND t.rowid > s.rowid
                            )
                        )
                    )
                """.trimIndent()

                db.execSQL("DELETE FROM `messages` WHERE `sessionId` IN ($staleSessions)")
                db.execSQL("UPDATE `proactive_rules` SET `sessionId` = NULL WHERE `sessionId` IN ($staleSessions)")
                db.execSQL("UPDATE `agent_intents` SET `sessionId` = NULL WHERE `sessionId` IN ($staleSessions)")
                db.execSQL("UPDATE `persona_pending_patches` SET `sessionId` = NULL WHERE `sessionId` IN ($staleSessions)")
                db.execSQL("UPDATE `memories` SET `sourceSessionId` = NULL WHERE `sourceSessionId` IN ($staleSessions)")
                db.execSQL("DELETE FROM `chat_sessions` WHERE `id` IN ($staleSessions)")

                // ---- 4. 保留段的 apiConfigId 为空时补默认配置 --------------------
                // 优先人格自己的默认配置，其次这台手机上最近改过的那条；都没有就保持 NULL，
                // 界面上会提示「请先选择 API 配置」，而不是硬塞一条不存在的 id。
                db.execSQL(
                    """
                    UPDATE `chat_sessions` SET `apiConfigId` = COALESCE(
                        (SELECT p.`defaultApiConfigId` FROM `personas` p WHERE p.`id` = `chat_sessions`.`personaId`),
                        (SELECT c.`id` FROM `api_configs` c ORDER BY c.`updatedAt` DESC LIMIT 1)
                    ) WHERE `apiConfigId` IS NULL
                    """.trimIndent()
                )

                // ---- 5. 唯一索引（先删旧索引，再建唯一索引） --------------------
                db.execSQL("DROP INDEX IF EXISTS `index_chat_sessions_personaId`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_sessions_personaId_unique` " +
                        "ON `chat_sessions` (`personaId`)"
                )
            }
        }

        /**
         * v9：彻底移除原生同步模式后，api_configs 与 chat_sessions 里那几列只服务于旧链路的死字段。
         *
         * Room 的表校验要求「实体声明的列」和「数据库里的列」完全一致，多出来的列会被判成
         * `Migration didn't properly handle`；而 minSdk 26 上 `DROP COLUMN` 又不可靠。
         * 所以这里走 SQLite 的标准做法：建一张没有死列的新表、原样把数据抄过去、换名、补索引。
         * 全程在一个事务里（Room 跑 Migration 时自带事务），任何一步失败都会整体回滚，
         * 不会出现「列删了一半、记忆也没了」的中间态。
         *
         * 被删的列：API 配置的 3 列（模式、地址、路径），会话的 6 列（同步状态、已同步版本、
         * 同步用记忆哈希、服务端会话 ID、最近一次同步错误与时间）。其中唯一的「用户数据」
         * 是同步状态，它只对已经不存在的那条链路有意义，删掉不影响人格、记忆、对话与设置。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `api_configs_new` (
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
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `webToolsEnabled` INTEGER NOT NULL DEFAULT 0,
                        `webToolsBaseUrl` TEXT NOT NULL DEFAULT '',
                        `webToolsApiKeyCipher` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `api_configs_new` (
                        `id`, `name`, `provider`, `baseUrl`, `apiKeyCipher`, `model`, `customHeadersJson`,
                        `allowInsecureHttp`, `temperature`, `topP`, `maxOutputTokens`, `frequencyPenalty`,
                        `presencePenalty`, `createdAt`, `updatedAt`, `webToolsEnabled`, `webToolsBaseUrl`,
                        `webToolsApiKeyCipher`
                    ) SELECT
                        `id`, `name`, `provider`, `baseUrl`, `apiKeyCipher`, `model`, `customHeadersJson`,
                        `allowInsecureHttp`, `temperature`, `topP`, `maxOutputTokens`, `frequencyPenalty`,
                        `presencePenalty`, `createdAt`, `updatedAt`, `webToolsEnabled`, `webToolsBaseUrl`,
                        `webToolsApiKeyCipher`
                    FROM `api_configs`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `api_configs`")
                db.execSQL("ALTER TABLE `api_configs_new` RENAME TO `api_configs`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_configs_updatedAt` ON `api_configs` (`updatedAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_sessions_new` (
                        `id` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `apiConfigId` TEXT,
                        `model` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `lastMessagePreview` TEXT,
                        `lastMessageAt` INTEGER,
                        `unreadCount` INTEGER NOT NULL,
                        `pinned` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastPersonaVersion` INTEGER NOT NULL,
                        `lastMemoryExtractedCount` INTEGER NOT NULL,
                        `historySummary` TEXT,
                        `historySummarizedUpTo` INTEGER,
                        `historySummaryAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `chat_sessions_new` (
                        `id`, `personaId`, `apiConfigId`, `model`, `title`, `lastMessagePreview`,
                        `lastMessageAt`, `unreadCount`, `pinned`, `createdAt`, `updatedAt`,
                        `lastPersonaVersion`, `lastMemoryExtractedCount`, `historySummary`,
                        `historySummarizedUpTo`, `historySummaryAt`
                    ) SELECT
                        `id`, `personaId`, `apiConfigId`, `model`, `title`, `lastMessagePreview`,
                        `lastMessageAt`, `unreadCount`, `pinned`, `createdAt`, `updatedAt`,
                        `lastPersonaVersion`, `lastMemoryExtractedCount`, `historySummary`,
                        `historySummarizedUpTo`, `historySummaryAt`
                    FROM `chat_sessions`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `chat_sessions`")
                db.execSQL("ALTER TABLE `chat_sessions_new` RENAME TO `chat_sessions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_personaId` ON `chat_sessions` (`personaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_lastMessageAt` ON `chat_sessions` (`lastMessageAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_pinned` ON `chat_sessions` (`pinned`)")
            }
        }

        /** v8：本地向量索引 + 会话历史压缩（滚动摘要）。 */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN historySummary TEXT")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN historySummarizedUpTo INTEGER")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN historySummaryAt INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_vectors` (
                        `memoryId` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `dim` INTEGER NOT NULL,
                        `vector` BLOB NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`memoryId`, `provider`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_vectors_provider` ON `memory_vectors` (`provider`)")
            }
        }

        /** v7：用量记账、记忆倒排索引、回忆重构留痕、记忆的结构化字段。 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN tags TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN happenedAt INTEGER")
                db.execSQL("ALTER TABLE memories ADD COLUMN originalContent TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN driftCount INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `api_usage` (
                        `id` TEXT NOT NULL,
                        `at` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `personaId` TEXT,
                        `sessionId` TEXT,
                        `modelName` TEXT,
                        `promptTokens` INTEGER NOT NULL,
                        `completionTokens` INTEGER NOT NULL,
                        `costMicroUsd` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_usage_at` ON `api_usage` (`at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_usage_kind_at` ON `api_usage` (`kind`, `at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_usage_personaId_at` ON `api_usage` (`personaId`, `at`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_terms` (
                        `memoryId` TEXT NOT NULL,
                        `term` TEXT NOT NULL,
                        `weight` REAL NOT NULL,
                        PRIMARY KEY(`memoryId`, `term`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_terms_term` ON `memory_terms` (`term`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_versions` (
                        `id` TEXT NOT NULL,
                        `memoryId` TEXT NOT NULL,
                        `at` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `beforeContent` TEXT,
                        `reason` TEXT,
                        `moodLabel` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_versions_memoryId_at` ON `memory_versions` (`memoryId`, `at`)")
            }
        }

        /** v6：记忆的生命周期——状态、分量、归档时间，以及每日整理的留痕。 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE memories ADD COLUMN strength REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE memories ADD COLUMN archivedAt INTEGER")
                db.execSQL("ALTER TABLE memories ADD COLUMN consolidatedAt INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_consolidations` (
                        `id` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `at` INTEGER NOT NULL,
                        `kept` INTEGER NOT NULL,
                        `merged` INTEGER NOT NULL,
                        `archived` INTEGER NOT NULL,
                        `forgotten` INTEGER NOT NULL,
                        `summary` TEXT NOT NULL,
                        `modelName` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_consolidations_personaId_at` ON `memory_consolidations` (`personaId`, `at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_consolidations_at` ON `memory_consolidations` (`at`)")
            }
        }

        /** v5：心情——此刻的一个词、强度、原因，以及上次变化的时间。 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE personas ADD COLUMN mood TEXT")
                db.execSQL("ALTER TABLE personas ADD COLUMN moodIntensity INTEGER")
                db.execSQL("ALTER TABLE personas ADD COLUMN moodReason TEXT")
                db.execSQL("ALTER TABLE personas ADD COLUMN moodUpdatedAt INTEGER")
                db.execSQL("ALTER TABLE personas ADD COLUMN moodNudgedAt INTEGER")
            }
        }

        /** v4：自主循环——意向（时间窗口）与每次思考的留痕。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_intents` (
                        `id` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `kind` TEXT NOT NULL,
                        `earliestAt` INTEGER NOT NULL,
                        `latestAt` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `hint` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `ruleId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `sentAt` INTEGER,
                        `lastError` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_intents_personaId_status` ON `agent_intents` (`personaId`, `status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_intents_earliestAt` ON `agent_intents` (`earliestAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_intents_latestAt` ON `agent_intents` (`latestAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_ticks` (
                        `id` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `at` INTEGER NOT NULL,
                        `thought` TEXT NOT NULL,
                        `actionJson` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `messageId` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_ticks_personaId_at` ON `agent_ticks` (`personaId`, `at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_ticks_at` ON `agent_ticks` (`at`)")
            }
        }

        /** v3：人设卡、联网工具配置、工具调用痕迹、记忆抽屉与回声、待确认人设补丁。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE personas ADD COLUMN personaCardJson TEXT")
                db.execSQL("ALTER TABLE personas ADD COLUMN quietSleepMinutes INTEGER")
                db.execSQL("ALTER TABLE personas ADD COLUMN quietWakeMinutes INTEGER")

                db.execSQL("ALTER TABLE api_configs ADD COLUMN webToolsEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN webToolsBaseUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN webToolsApiKeyCipher TEXT")

                db.execSQL("ALTER TABLE messages ADD COLUMN toolTraceJson TEXT")

                db.execSQL("ALTER TABLE memories ADD COLUMN drawer TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN sourceQuote TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_echoes` (
                        `id` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `drawer` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `importance` INTEGER NOT NULL,
                        `sourceQuote` TEXT NOT NULL,
                        `categoryName` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastSeenAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_echoes_personaId_drawer` ON `memory_echoes` (`personaId`, `drawer`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_echoes_createdAt` ON `memory_echoes` (`createdAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `persona_pending_patches` (
                        `id` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `patchJson` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `reason` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_persona_pending_patches_personaId` ON `persona_pending_patches` (`personaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_persona_pending_patches_createdAt` ON `persona_pending_patches` (`createdAt`)")
            }
        }

        /** v2：新增自定义预设、主动唤醒规则，以及消息图片附件列。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `persona_presets` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `avatarUri` TEXT,
                        `avatarSeed` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_persona_presets_updatedAt` ON `persona_presets` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_persona_presets_category` ON `persona_presets` (`category`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `proactive_rules` (
                        `id` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `kind` TEXT NOT NULL,
                        `timeOfDayMinutes` INTEGER,
                        `intervalMinutes` INTEGER,
                        `hint` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `pending` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `nextTriggerAt` INTEGER NOT NULL,
                        `lastTriggeredAt` INTEGER,
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_rules_personaId` ON `proactive_rules` (`personaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_rules_nextTriggerAt` ON `proactive_rules` (`nextTriggerAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_rules_enabled` ON `proactive_rules` (`enabled`)")
            }
        }
    }
}
