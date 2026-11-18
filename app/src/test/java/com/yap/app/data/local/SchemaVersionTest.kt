package com.yap.app.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移链守卫（跑在 JVM 上，不需要模拟器）。
 *
 * 这个 App 里，数据库迁移写错的后果不是「报个错」，而是用户升级完发现「他不记得我了」。
 * 所以这里盯三件最容易出事、又最容易被忘掉的事：
 *
 * 1. `@Database(version = N)` 和 `app/schemas/` 里导出的最高版本必须一致——
 *    只改注解不写迁移/不导出 schema，这一条就会红。
 * 2. 迁移对象必须覆盖 1 → N 的每一档，不能有断档——
 *    没有 `fallbackToDestructiveMigration()` 兜底时，断一档就是老用户升级即崩。
 * 3. 声明出来的迁移必须真的被 AppModule 注册进 Room——
 *    写了迁移对象却忘了加进 `addMigrations(...)`，效果和没写一样。
 *
 * 这里刻意用「读源码文本 + 读 schema json」而不是反射：JVM 单测里 `@Database` 是
 * CLASS 级保留（运行时反射看不到版本号），而加载 Room 的类又会拖进 android 依赖。
 * 文本扫描 + schema 文件是这两边都稳定、且改错就能立刻发现的做法。
 * 真正跑迁移的测试仍然在 androidTest（那边有真 SQLite）。
 */
class SchemaVersionTest {

    /**
     * 从 Gradle 单测的工作目录往上找这几样东西。
     *
     * 单测的工作目录通常是模块目录（`app/`），但也可能是仓库根目录（IDE 直接跑测试时），
     * 所以每个路径都按「模块内 / 仓库根 / 隔壁 app 目录」三个候选逐个试，取第一个存在的。
     */
    private fun locate(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error(
                "找不到这些路径中的任何一个（工作目录=${File("").absolutePath}）：" +
                    candidates.joinToString(" / ")
            )

    private val schemaDir: File by lazy {
        locate(
            SCHEMA_DIR,
            "app/$SCHEMA_DIR",
            "../app/$SCHEMA_DIR"
        )
    }

    private val databaseSource: File by lazy {
        locate(
            DATABASE_SOURCE,
            "app/$DATABASE_SOURCE",
            "../app/$DATABASE_SOURCE"
        )
    }

    private val appModuleSource: File by lazy {
        locate(
            APP_MODULE_SOURCE,
            "app/$APP_MODULE_SOURCE",
            "../app/$APP_MODULE_SOURCE"
        )
    }

    /** `@Database(...)` 里手写的版本号。 */
    private val declaredVersion: Int by lazy {
        val text = databaseSource.readText()
        DATABASE_VERSION.find(text)?.groupValues?.get(1)?.toInt()
            ?: error("在 $databaseSource 里没找到 @Database(... version = N ...)")
    }

    /**
     * 导出的 schema 文件里的版本号：文件名是版本号，文件内容里 `database.version` 也是版本号。
     *
     * 两个都取，顺便校验它们一致——导出目录被手工改名/手工改内容时能立刻发现。
     */
    private val schemaVersions: Map<Int, Int> by lazy {
        val files = schemaDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("${schemaDir.absolutePath} 里没有任何 schema json；exportSchema 是不是被关掉了？", files.isNotEmpty())
        files.associate { file ->
            val versionFromName = file.nameWithoutExtension.toIntOrNull()
                ?: error("schema 文件名应该是版本号，实际是 ${file.name}")
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val versionInFile = root["database"]?.jsonObject?.get("version")?.jsonPrimitive?.int
                ?: error("${file.name} 里没有 database.version")
            assertEquals(
                "${file.name} 的文件名和内容里的版本号不一致：导出目录被动过？",
                versionFromName,
                versionInFile
            )
            versionFromName to versionInFile
        }
    }

    @Test
    fun 注解里的版本号和导出的最高schema版本一致() {
        val maxSchemaVersion = schemaVersions.keys.max()
        assertEquals(
            "@Database(version = $declaredVersion) 和 app/schemas 里最高的 schema 版本 $maxSchemaVersion 不一致。" +
                "如果你刚升了版本，记得先把 schema 编译导出一次（assembleDebug / kspDebugKotlin 就会导出），" +
                "否则用户升级时没有对应的表结构可迁移。",
            declaredVersion,
            maxSchemaVersion
        )
        assertTrue(
            "当前版本 ${declaredVersion} 的 schema json 不在导出目录里；别人没法 review 这一版到底动了什么",
            schemaVersions.containsKey(declaredVersion)
        )
    }

    @Test
    fun 已声明的迁移覆盖了一到当前版本的每一档() {
        val declared = declaredMigrations()
        assertTrue("在 $databaseSource 里一个 MIGRATION_x_y 都没扫到，正则可能和源码格式对不上了", declared.isNotEmpty())

        val steps = declared.map { it.from to it.to }.toSet()
        val expected = (1 until declaredVersion).map { it to it + 1 }.toSet()
        assertEquals(
            "迁移链有断档：当前版本是 $declaredVersion，缺少 " +
                expected.filterNot { it in steps }.joinToString { "MIGRATION_${it.first}_${it.second}" } +
                "；没有 fallbackToDestructiveMigration 时这就是「老用户升级即崩」。",
            expected,
            steps
        )
    }

    @Test
    fun 声明的迁移都被AppModule注册进了Room() {
        val registered = addMigrationsBlock()
        val missing = declaredMigrations()
            .map { "MIGRATION_${it.from}_${it.to}" }
            .filterNot { name -> Regex("\\b$name\\b").containsMatchIn(registered) }
        assertTrue(
            "这些迁移只在 YapDatabase 里声明了，却没进 AppModule.addMigrations(...)，等于没写：$missing",
            missing.isEmpty()
        )
    }

    /**
     * v11：区块分页的 schema 必须真的导出，而且列/主键/索引与规范一致。
     *
     * 规范 `spec/messages-blocks/README.md` §1 规定得很细（字段名、主键、两个索引），
     * 少一个索引不会让功能「坏掉」，但会让「读一块」退化成扫一段历史——这种退化没有报错，
     * 只有在长会话里才感觉得到，所以只能靠这里钉住。
     */
    @Test
    fun `v11 的 schema 里区块列与区块表都在`() {
        val schema = Json.parseToJsonElement(File(schemaDir, "11.json").readText()).jsonObject
        val entities = schema["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }

        val messages = entities.first { it["tableName"]!!.jsonPrimitive.content == "messages" }
        val messageFields = messages["fields"]!!.jsonArray.map { it.jsonObject }
        val blockIndex = messageFields.firstOrNull { it["columnName"]!!.jsonPrimitive.content == "blockIndex" }
            ?: error("v11 的 messages 里没有 blockIndex 列")
        assertEquals("blockIndex 必须是 INTEGER", "INTEGER", blockIndex["affinity"]!!.jsonPrimitive.content)
        assertEquals("blockIndex 必须 NOT NULL", true, blockIndex["notNull"]!!.jsonPrimitive.boolean)
        assertEquals("blockIndex 默认值必须是 0", "0", blockIndex["defaultValue"]!!.jsonPrimitive.content)

        val blocks = entities.first { it["tableName"]!!.jsonPrimitive.content == "message_blocks" }
        val blockFields = blocks["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertEquals(
            "message_blocks 的列必须与规范 §1 完全一致",
            listOf("sessionId", "blockIndex", "firstMessageId", "lastMessageId", "firstCreatedAt", "lastCreatedAt", "messageCount"),
            blockFields
        )
        val primaryKey = blocks["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("主键必须是 (sessionId, blockIndex)", listOf("sessionId", "blockIndex"), primaryKey)

        val blockIndices = blocks["indices"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(
            "message_blocks(sessionId, blockIndex) 索引必须在：$blockIndices",
            "index_message_blocks_sessionId_blockIndex" in blockIndices
        )
        val messageIndices = messages["indices"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(
            "messages(sessionId, blockIndex, createdAt) 索引必须在：$messageIndices",
            "index_messages_sessionId_blockIndex_createdAt" in messageIndices
        )
    }

    @Test
    fun `MIGRATION_10_11 走的是 MessageBlockMigration 且注册进了 Room`() {
        val text = databaseSource.readText()
        assertTrue(
            "迁移实现应当收在 MessageBlockMigration（规则与 MessageBlocks 同源）",
            text.contains("MessageBlockMigration.migrate10To11(db)")
        )
        val migration = File(databaseSource.parentFile, "MessageBlockMigration.kt").readText()
        assertTrue("回填规则必须引用 MessageBlocks.BLOCK_SIZE", migration.contains("MessageBlocks.BLOCK_SIZE"))
        assertTrue("回填必须按 (createdAt, id) 排序", migration.contains("ORDER BY `createdAt` ASC, `id` ASC"))
        assertTrue("最后要删孤儿块", migration.contains("DELETE FROM `message_blocks` WHERE `sessionId` NOT IN"))
        assertTrue(
            "迁移必须幂等：加列前先查 PRAGMA，建表用 IF NOT EXISTS",
            migration.contains("PRAGMA table_info") && migration.contains("CREATE TABLE IF NOT EXISTS")
        )
        assertTrue("MIGRATION_10_11 必须注册进 AppModule", addMigrationsBlock().contains("MIGRATION_10_11"))
    }

    /**
     * v12：`api_configs.webToolsProvider`（`spec/web-search/README.md` §1）。
     *
     * 规范把列名、类型、默认值写死了；默认值写错（例如 `DEEPSEEK` 少了引号）在 SQLite 里
     * 不会报错，只会让老库升级后读出一个空字符串，然后被 provider 归一化悄悄退回默认 provider——
     * 看起来「能用」，实际是把规范里的默认值丢了。这里逐项钉住。
     *
     * **0.12 改名不影响这里**：provider 的字符串值从 `DEEPSEEK` 改成了 `MESSAGEAPI`，但列的
     * SQL 默认值与 `schemas/12.json` 必须保持旧值——已装 v12 的库启动时会拿 schema 校验实体，
     * 改默认值会让那些库直接启动失败。旧值由 `WebToolsProviders.normalize` 在代码层折算。
     */
    @Test
    fun `v12 的 schema 里有 webToolsProvider 且默认值是 DEEPSEEK`() {
        val schema = Json.parseToJsonElement(File(schemaDir, "12.json").readText()).jsonObject
        val entities = schema["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }
        val apiConfigs = entities.first { it["tableName"]!!.jsonPrimitive.content == "api_configs" }
        val fields = apiConfigs["fields"]!!.jsonArray.map { it.jsonObject }
        val provider = fields.firstOrNull { it["columnName"]!!.jsonPrimitive.content == "webToolsProvider" }
            ?: error("v12 的 api_configs 里没有 webToolsProvider 列")
        assertEquals("webToolsProvider 必须是 TEXT", "TEXT", provider["affinity"]!!.jsonPrimitive.content)
        assertEquals("webToolsProvider 必须 NOT NULL", true, provider["notNull"]!!.jsonPrimitive.boolean)
        assertEquals(
            "webToolsProvider 的默认值必须是 'DEEPSEEK'（含 SQL 引号）",
            "'DEEPSEEK'",
            provider["defaultValue"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `MIGRATION_11_12 幂等且注册进了 Room`() {
        val text = databaseSource.readText()
        val start = text.indexOf("MIGRATION_11_12 = object : Migration(11, 12)")
        assertTrue("没找到 MIGRATION_11_12 的声明", start >= 0)
        val block = text.substring(start, minOf(text.length, start + 900))
        assertTrue("加列前要先查 PRAGMA table_info", block.contains("PRAGMA table_info"))
        assertTrue(
            "DDL 必须与规范逐字一致",
            block.contains(
                "ALTER TABLE `api_configs` ADD COLUMN `webToolsProvider` TEXT NOT NULL DEFAULT 'DEEPSEEK'"
            )
        )
        assertTrue("MIGRATION_11_12 必须注册进 AppModule", addMigrationsBlock().contains("MIGRATION_11_12"))
    }

    /**
     * v13：人设引导的三列（`spec/persona-guidance/README.md` §2 / §3 / §4）。
     *
     * 列名、类型、可空性逐项钉死：全部 `TEXT` 且可空，老库升级后读出来是 NULL（= 没有世界 /
     * 没学过话风 / 没在忙），行为与 v12 完全一致。`api_configs` 的旧默认值刻意**没有**被这次
     * 迁移改动（v12 的 `webToolsProvider DEFAULT 'DEEPSEEK'` 仍然在）。
     */
    @Test
    fun `v13 的 schema 里人设与预设都有引导字段且都可空`() {
        val schema = Json.parseToJsonElement(File(schemaDir, "13.json").readText()).jsonObject
        val entities = schema["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }

        val personas = entities.first { it["tableName"]!!.jsonPrimitive.content == "personas" }
        val personaFields = personas["fields"]!!.jsonArray.map { it.jsonObject }.associateBy {
            it["columnName"]!!.jsonPrimitive.content
        }
        listOf("worldJson", "speechStyleJson", "activityJson").forEach { column ->
            val field = personaFields[column] ?: error("v13 的 personas 里没有 $column")
            assertEquals("$column 必须是 TEXT", "TEXT", field["affinity"]!!.jsonPrimitive.content)
            assertEquals("$column 必须可空（老库默认 NULL）", false, field["notNull"]!!.jsonPrimitive.boolean)
        }

        val presets = entities.first { it["tableName"]!!.jsonPrimitive.content == "persona_presets" }
        val presetFields = presets["fields"]!!.jsonArray.map { it.jsonObject }.associateBy {
            it["columnName"]!!.jsonPrimitive.content
        }
        listOf("worldJson", "speechStyleJson").forEach { column ->
            val field = presetFields[column] ?: error("v13 的 persona_presets 里没有 $column")
            assertEquals("$column 必须是 TEXT", "TEXT", field["affinity"]!!.jsonPrimitive.content)
            assertEquals("$column 必须可空", false, field["notNull"]!!.jsonPrimitive.boolean)
        }

        // api_configs 的 v12 默认值不能被这次迁移顺手改掉。
        val apiConfigs = entities.first { it["tableName"]!!.jsonPrimitive.content == "api_configs" }
        val provider = apiConfigs["fields"]!!.jsonArray.map { it.jsonObject }
            .first { it["columnName"]!!.jsonPrimitive.content == "webToolsProvider" }
        assertEquals("v12 的旧默认值必须保留", "'DEEPSEEK'", provider["defaultValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `MIGRATION_12_13 幂等 只加可空列 且注册进了 Room`() {
        val text = databaseSource.readText()
        val start = text.indexOf("MIGRATION_12_13 = object : Migration(12, 13)")
        assertTrue("没找到 MIGRATION_12_13 的声明", start >= 0)
        val block = text.substring(start, minOf(text.length, start + 1800))
        listOf(
            "ALTER TABLE `personas` ADD COLUMN `worldJson` TEXT",
            "ALTER TABLE `personas` ADD COLUMN `speechStyleJson` TEXT",
            "ALTER TABLE `personas` ADD COLUMN `activityJson` TEXT",
            "ALTER TABLE `persona_presets` ADD COLUMN `worldJson` TEXT",
            "ALTER TABLE `persona_presets` ADD COLUMN `speechStyleJson` TEXT"
        ).forEach { ddl -> assertTrue("DDL 必须与规范一致：$ddl", block.contains(ddl)) }
        assertTrue("加列前要先查 PRAGMA table_info", block.contains("PRAGMA table_info"))
        assertTrue("MIGRATION_12_13 必须注册进 AppModule", addMigrationsBlock().contains("MIGRATION_12_13"))
    }

    /**
     * v14：人格提示词的翻译缓存（`spec/persona-translation/README.md` §4）。
     *
     * 这次只加两列，改动很小，但恰恰最容易写错：两列必须都是可空 `TEXT`，不带 `DEFAULT`、不带 `NOT NULL`。
     * 一旦写成 `NOT NULL DEFAULT ''`，老库升级时会被逼着给每一行塞一个空串；而代码判「有没有缓存」
     * 看的是 `translationLang != null`，空串会被当成「有缓存但语言为空」，于是每次启动都重译一遍——
     * 这种错不会有任何报错，只会让用户觉得翻译一直在重来。所以这里连「只允许 ADD COLUMN ... TEXT」
     * 这条形状也一起钉住。
     */
    @Test
    fun `MIGRATION_13_14 幂等 只加可空列 且注册进了 Room`() {
        val text = databaseSource.readText()
        val start = text.indexOf("MIGRATION_13_14 = object : Migration(13, 14)")
        assertTrue("没找到 MIGRATION_13_14 的声明", start >= 0)
        // 只切到声明 + migrate 主体（下一段 KDoc 之前）：否则后面迁移的注释与 DDL 会混进来干扰断言。
        val docComment = text.indexOf("/**", start)
        val end = if (docComment > start) docComment else minOf(text.length, start + 1200)
        val block = text.substring(start, end)

        listOf(
            "ALTER TABLE `personas` ADD COLUMN `translationJson` TEXT",
            "ALTER TABLE `personas` ADD COLUMN `translationLang` TEXT"
        ).forEach { ddl -> assertTrue("DDL 必须与规范一致：$ddl", block.contains(ddl)) }
        assertFalse("只允许加可空列，DDL 里不许出现 NOT NULL", block.contains("NOT NULL"))
        assertFalse("只允许加可空列，DDL 里不许出现 DEFAULT", block.contains("DEFAULT"))

        // 幂等靠 PRAGMA：MIGRATION_13_14 复用 columns(db, "personas")，helper 自己发 PRAGMA table_info。
        assertTrue(
            "加列前要先查 PRAGMA table_info（复用 columns(db, \"personas\")）",
            block.contains("columns(db, \"personas\")")
        )
        assertTrue("columns(...) 必须用 PRAGMA table_info 读列名", text.contains("PRAGMA table_info"))

        assertTrue("MIGRATION_13_14 必须注册进 AppModule", addMigrationsBlock().contains("MIGRATION_13_14"))
        assertTrue(
            "v14 的 schema json 必须导出，否则 review 时看不出这一版只加了两列：$schemaDir",
            File(schemaDir, "14.json").isFile
        )
    }

    /**
     * v14：译文缓存两列在导出的 schema 里必须与迁移同名同责。
     *
     * `notNull=false` 是这次的唯一契约（老库读出来 = NULL = 还没翻译过）；`defaultValue` 必须没有，
     * 否则「没有缓存」会被写成一个空串，判空逻辑全部失效。
     */
    @Test
    fun `v14 的 schema 里翻译缓存两列都在且可空`() {
        val schema = Json.parseToJsonElement(File(schemaDir, "14.json").readText()).jsonObject
        val entities = schema["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }
        val personas = entities.first { it["tableName"]!!.jsonPrimitive.content == "personas" }
        val personaFields = personas["fields"]!!.jsonArray.map { it.jsonObject }.associateBy {
            it["columnName"]!!.jsonPrimitive.content
        }

        listOf("translationJson", "translationLang").forEach { column ->
            val field = personaFields[column] ?: error("v14 的 personas 里没有 $column")
            assertEquals("$column 必须是 TEXT", "TEXT", field["affinity"]!!.jsonPrimitive.content)
            assertEquals("$column 必须可空（老库升级后是 NULL）", false, field["notNull"]!!.jsonPrimitive.boolean)
            assertNull("$column 不能有默认值，否则「没有缓存」会变成空串", field["defaultValue"])
        }

        // v13 的引导字段不能被这次迁移顺手改掉。
        listOf("worldJson", "speechStyleJson", "activityJson").forEach { column ->
            val field = personaFields[column] ?: error("v14 的 personas 里丢了 v13 的 $column")
            assertEquals("$column 必须还是可空", false, field["notNull"]!!.jsonPrimitive.boolean)
        }
    }


    /**
     * v16：表情包（`spec/stickers/SCHEMA.md`）。
     *
     * 规范把列名/顺序/可空/默认值/索引名都写死了（两端逐字一致），所以这里逐项钉住：
     * 少一个索引不会让功能坏掉，只会让「最近 30 个」退化成全表扫描；而列的可空性写错，
     * 老库升级时要么崩、要么让「没有归属」变成空串。
     */
    @Test
    fun `v16 的 schema 里表情表的列与索引与规范逐字一致`() {
        val schema = Json.parseToJsonElement(File(schemaDir, "16.json").readText()).jsonObject
        val entities = schema["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }
        val stickers = entities.firstOrNull { it["tableName"]!!.jsonPrimitive.content == "stickers" }
            ?: error("v16 的 schema 里没有 stickers 表")

        val fields = stickers["fields"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            "stickers 的列名与顺序必须与 SCHEMA.md 完全一致",
            listOf(
                "id", "ownerUserId", "personaId", "file", "mime", "width", "height",
                "frames", "bytes", "source", "createdBy", "createdAt"
            ),
            fields.map { it["columnName"]!!.jsonPrimitive.content }
        )

        val byName = fields.associateBy { it["columnName"]!!.jsonPrimitive.content }
        fun field(name: String) = byName[name] ?: error("stickers 缺少列 $name")
        assertEquals("id 必须是主键 TEXT NOT NULL", "TEXT", field("id")["affinity"]!!.jsonPrimitive.content)
        assertEquals(true, field("id")["notNull"]!!.jsonPrimitive.boolean)
        listOf("file", "mime", "source", "createdBy", "createdAt").forEach { column ->
            assertEquals("$column 必须 NOT NULL", true, field(column)["notNull"]!!.jsonPrimitive.boolean)
        }
        listOf("ownerUserId", "personaId").forEach { column ->
            assertEquals("$column 必须可空（不属于任何人/人格时是 NULL）", false, field(column)["notNull"]!!.jsonPrimitive.boolean)
        }
        assertEquals(
            "width 默认值必须是 0",
            "0",
            field("width")["defaultValue"]!!.jsonPrimitive.content
        )
        assertEquals("height 默认值必须是 0", "0", field("height")["defaultValue"]!!.jsonPrimitive.content)
        assertEquals("frames 默认值必须是 1（静态图也是单帧）", "1", field("frames")["defaultValue"]!!.jsonPrimitive.content)
        assertEquals("bytes 默认值必须是 0", "0", field("bytes")["defaultValue"]!!.jsonPrimitive.content)

        val primaryKey = stickers["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("主键必须是 id", listOf("id"), primaryKey)

        val indices = stickers["indices"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(
            "两条索引的名字必须与规范逐字一致（两端对齐）",
            listOf("index_stickers_createdAt", "index_stickers_personaId"),
            indices
        )
    }

    @Test
    fun `MIGRATION_15_16 幂等 只建表与索引 且注册进了 Room`() {
        val text = databaseSource.readText()
        val start = text.indexOf("MIGRATION_15_16 = object : Migration(15, 16)")
        assertTrue("没找到 MIGRATION_15_16 的声明", start >= 0)
        val docComment = text.indexOf("/**", start)
        val end = if (docComment > start) docComment else minOf(text.length, start + 2400)
        val block = text.substring(start, end)

        assertTrue("建表必须用 CREATE TABLE IF NOT EXISTS（幂等）", block.contains("CREATE TABLE IF NOT EXISTS `stickers`"))
        assertTrue(
            "索引必须用 CREATE INDEX IF NOT EXISTS",
            block.contains("CREATE INDEX IF NOT EXISTS `index_stickers_createdAt`") &&
                block.contains("CREATE INDEX IF NOT EXISTS `index_stickers_personaId`")
        )
        listOf(
            "`id` TEXT NOT NULL",
            "`ownerUserId` TEXT",
            "`personaId` TEXT",
            "`file` TEXT NOT NULL",
            "`mime` TEXT NOT NULL",
            "`width` INTEGER NOT NULL DEFAULT 0",
            "`height` INTEGER NOT NULL DEFAULT 0",
            "`frames` INTEGER NOT NULL DEFAULT 1",
            "`bytes` INTEGER NOT NULL DEFAULT 0",
            "`source` TEXT NOT NULL",
            "`createdBy` TEXT NOT NULL",
            "`createdAt` INTEGER NOT NULL"
        ).forEach { column ->
            assertTrue("迁移里的列必须与规范一致：$column", block.contains(column))
        }
        assertFalse("这次只新建表，不许 ALTER/DROP 任何既有表", block.contains("ALTER TABLE") || block.contains("DROP TABLE"))
        assertTrue("MIGRATION_15_16 必须注册进 AppModule", addMigrationsBlock().contains("MIGRATION_15_16"))
        assertTrue("v16 的 schema json 必须导出", File(schemaDir, "16.json").isFile)
    }

    /**
     * v17：多维情绪（`spec/mood/README.md` §1）。
     *
     * 三列必须都是可空 `INTEGER`、没有 DEFAULT：老库升级后读出来是 NULL =
     * 「还没算过维度」，由 `MoodDimensions.fromLegacy` 现场按 label/intensity 折算。
     * 写成 `NOT NULL DEFAULT 0` 会让每一行都变成「愉快度 0」，看起来有值、其实是错的。
     */
    @Test
    fun `v17 的 schema 里多维情绪三列都在且可空`() {
        val schema = Json.parseToJsonElement(File(schemaDir, "17.json").readText()).jsonObject
        val entities = schema["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }
        val personas = entities.first { it["tableName"]!!.jsonPrimitive.content == "personas" }
        val fields = personas["fields"]!!.jsonArray.map { it.jsonObject }.associateBy {
            it["columnName"]!!.jsonPrimitive.content
        }

        listOf("moodValence", "moodArousal", "moodDominance").forEach { column ->
            val field = fields[column] ?: error("v17 的 personas 里没有 $column")
            assertEquals("$column 必须是 INTEGER", "INTEGER", field["affinity"]!!.jsonPrimitive.content)
            assertEquals("$column 必须可空（老库升级后是 NULL）", false, field["notNull"]!!.jsonPrimitive.boolean)
            assertNull("$column 不能有默认值，否则「还没算过」会变成一个假数字", field["defaultValue"])
        }

        // 既有的心情四列一个都不能少：label/intensity/reason/updatedAt 仍然是真源。
        listOf("mood", "moodIntensity", "moodReason", "moodUpdatedAt", "moodNudgedAt").forEach { column ->
            assertTrue("v17 的 personas 里丢了旧心情列 $column", fields.containsKey(column))
        }
    }

    @Test
    fun `MIGRATION_16_17 幂等 加可空列并回填旧心情 且注册进了 Room`() {
        val text = databaseSource.readText()
        val start = text.indexOf("MIGRATION_16_17 = object : Migration(16, 17)")
        assertTrue("没找到 MIGRATION_16_17 的声明", start >= 0)
        val docComment = text.indexOf("/**", start)
        val end = if (docComment > start) docComment else minOf(text.length, start + 1600)
        val block = text.substring(start, end)

        listOf(
            "ALTER TABLE `personas` ADD COLUMN `moodValence` INTEGER",
            "ALTER TABLE `personas` ADD COLUMN `moodArousal` INTEGER",
            "ALTER TABLE `personas` ADD COLUMN `moodDominance` INTEGER"
        ).forEach { ddl -> assertTrue("DDL 必须与规范一致：$ddl", block.contains(ddl)) }
        assertFalse("只允许加可空列，DDL 里不许出现 NOT NULL", block.contains("NOT NULL"))
        assertFalse("只允许加可空列，DDL 里不许出现 DEFAULT", block.contains("DEFAULT"))
        assertTrue(
            "加列前要先查 PRAGMA table_info（复用 columns(db, \"personas\")）",
            block.contains("columns(db, \"personas\")")
        )
        assertTrue("MIGRATION_16_17 必须注册进 AppModule", addMigrationsBlock().contains("MIGRATION_16_17"))
        assertTrue("v17 的 schema json 必须导出", File(schemaDir, "17.json").isFile)

        // 旧数据映射（规范 §1）：必须复用 domain 的折算函数，不能在 SQL 里再抄一份心情词表
        // （两份词表迟早会漂，而漂了不会有任何报错）。回填只针对「有标签且三列还是 NULL」的行。
        assertTrue(
            "旧数据映射必须调用 MoodDimensions.fromLegacy",
            text.contains("MoodDimensions.fromLegacy")
        )
        assertTrue("回填只处理「有心情标签 + 还没算过维度」的行", text.contains("`moodValence` IS NULL"))
        assertTrue("回填要写回三列", text.contains("SET `moodValence` = ?, `moodArousal` = ?, `moodDominance` = ?"))
    }

    /**
     * parity：实体、迁移、导出的 schema 三处必须说同一件事。
     *
     * 之前出现过「实体加了列、迁移忘了写」与「迁移写了、schema 没导出」两种漂移，
     * 表现分别是升级即崩与 review 看不出版本变化。这里把三份并排比一次。
     */
    @Test
    fun `多维情绪的实体 迁移 与 schema 三处对齐`() {
        val entitySource = File(databaseSource.parentFile, "Entities.kt").readText()
        val migrationSource = databaseSource.readText()
        val schema = Json.parseToJsonElement(File(schemaDir, "17.json").readText()).jsonObject
        val entities = schema["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }
        val personas = entities.first { it["tableName"]!!.jsonPrimitive.content == "personas" }
        val schemaColumns = personas["fields"]!!.jsonArray.map {
            it.jsonObject["columnName"]!!.jsonPrimitive.content
        }.toSet()

        listOf("moodValence", "moodArousal", "moodDominance").forEach { column ->
            assertTrue(
                "实体里声明了 $column，但导出的 v17 schema 里没有它（忘了编译导出？）",
                column in schemaColumns
            )
            assertTrue(
                "实体里缺 $column（Entity 是迁移与 schema 的入口）",
                Regex("""val\s+$column\s*:\s*Int\?""").containsMatchIn(entitySource)
            )
            assertTrue(
                "迁移里缺 $column 的 ADD COLUMN",
                migrationSource.contains("ADD COLUMN `$column` INTEGER")
            )
        }
    }


    // ---------------------------------------------------------------- 解析源码

    private data class DeclaredMigration(val from: Int, val to: Int)

    /**
     * 扫 `val MIGRATION_7_8 = object : Migration(7, 8) { ... }`。
     *
     * 顺便校验名字和参数对得上：`MIGRATION_7_8 = Migration(6, 7)` 这种复制粘贴错，
     * 只要有人写出来，这一条就会红。
     */
    private fun declaredMigrations(): List<DeclaredMigration> {
        val text = databaseSource.readText()
        return MIGRATION_DECLARATION.findAll(text).map { match ->
            val (fromName, toName, from, to) = match.destructured
            assertEquals(
                "MIGRATION_${fromName}_${toName} 的名字和 Migration($from, $to) 的参数对不上",
                "${fromName}_$toName",
                "${from}_$to"
            )
            DeclaredMigration(from.toInt(), to.toInt())
        }.toList()
    }

    /** `addMigrations(...)` 括号里那一段文本（也就是真正交给 Room 的那份名单）。 */
    private fun addMigrationsBlock(): String {
        val text = appModuleSource.readText()
        val match = ADD_MIGRATIONS.find(text)
            ?: error("在 $appModuleSource 里没找到 addMigrations(...)；迁移是不是改到别处注册了？")
        return match.groupValues[1]
    }

    private companion object {
        const val SCHEMA_DIR = "schemas/com.yap.app.data.local.YapDatabase"
        const val DATABASE_SOURCE = "src/main/java/com/yap/app/data/local/YapDatabase.kt"
        const val APP_MODULE_SOURCE = "src/main/java/com/yap/app/di/AppModule.kt"

        /** `@Database(... version = 8 ...)`：注解参数可能跨很多行（entities 列表在前）。 */
        val DATABASE_VERSION = Regex("""@Database\s*\([\s\S]*?\bversion\s*=\s*(\d+)""")

        /** `val MIGRATION_7_8 = object : Migration(7, 8) {` */
        val MIGRATION_DECLARATION =
            Regex("""MIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\(\s*(\d+)\s*,\s*(\d+)\s*\)""")

        val ADD_MIGRATIONS = Regex("""addMigrations\(([\s\S]*?)\)\s*""")
    }
}
