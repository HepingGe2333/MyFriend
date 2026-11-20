package com.yap.app.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.14 / schema v18 的三处 parity：实体、迁移、导出的 schema。 */
class SchemaV18Test {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到：${candidates.joinToString()}")

    private val schemaDir = locate(
        "schemas/com.yap.app.data.local.YapDatabase",
        "app/schemas/com.yap.app.data.local.YapDatabase",
        "../app/schemas/com.yap.app.data.local.YapDatabase"
    )
    private val databaseSource = locate(
        "src/main/java/com/yap/app/data/local/YapDatabase.kt",
        "app/src/main/java/com/yap/app/data/local/YapDatabase.kt",
        "../app/src/main/java/com/yap/app/data/local/YapDatabase.kt"
    )
    private val appModuleSource = locate(
        "src/main/java/com/yap/app/di/AppModule.kt",
        "app/src/main/java/com/yap/app/di/AppModule.kt",
        "../app/src/main/java/com/yap/app/di/AppModule.kt"
    )

    @Test
    fun `v18 schema 有拉黑列 拉黑消息列 心理活动与会话备忘录表`() {
        val root = Json.parseToJsonElement(File(schemaDir, "18.json").readText()).jsonObject
        val database = root["database"]!!.jsonObject
        assertEquals(18, database["version"]!!.jsonPrimitive.int)
        val entities = database["entities"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["tableName"]!!.jsonPrimitive.content }

        val personas = entities.getValue("personas")["fields"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["columnName"]!!.jsonPrimitive.content }
        listOf("blockedUntil", "blockReason").forEach { column ->
            val field = personas[column] ?: error("personas 缺少 $column")
            assertFalse("$column 必须可空（未拉黑 = NULL）", field["notNull"]!!.jsonPrimitive.boolean)
        }
        val messages = entities.getValue("messages")["fields"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["columnName"]!!.jsonPrimitive.content }
        val blocked = messages["blocked"] ?: error("messages 缺少 blocked")
        assertEquals("INTEGER", blocked["affinity"]!!.jsonPrimitive.content)
        assertEquals("0", blocked["defaultValue"]!!.jsonPrimitive.content)

        val mental = entities.getValue("mental_activities")["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertTrue(mental.containsAll(listOf("personaId", "sessionId", "at", "thought", "moodValence")))
        val memo = entities.getValue("session_memory")["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertTrue(memo.containsAll(listOf("sessionId", "personaId", "shortSummary", "longSummary")))
    }

    @Test
    fun `MIGRATION_17_18 幂等且注册进 Room`() {
        val text = databaseSource.readText()
        val start = text.indexOf("MIGRATION_17_18 = object : Migration(17, 18)")
        assertTrue("没找到 MIGRATION_17_18", start >= 0)
        val block = text.substring(start, minOf(text.length, start + 7_000))
        listOf(
            "ALTER TABLE `personas` ADD COLUMN `blockedUntil` INTEGER",
            "ALTER TABLE `personas` ADD COLUMN `blockReason` TEXT",
            "ALTER TABLE `personas` ADD COLUMN `blockProposalJson` TEXT",
            "ALTER TABLE `messages` ADD COLUMN `blocked` INTEGER NOT NULL DEFAULT 0",
            "CREATE TABLE IF NOT EXISTS `mental_activities`",
            "CREATE TABLE IF NOT EXISTS `session_memory`"
        ).forEach { ddl -> assertTrue("迁移缺少：$ddl", block.contains(ddl)) }
        assertTrue(block.contains("columns(db, \"personas\")"))
        assertTrue(block.contains("columns(db, \"messages\")"))
        assertTrue(appModuleSource.readText().contains("MIGRATION_17_18"))
    }

    @Test
    fun `拉黑消息永久排除在模型上下文查询之外`() {
        val daos = locate(
            "src/main/java/com/yap/app/data/local/Daos.kt",
            "app/src/main/java/com/yap/app/data/local/Daos.kt",
            "../app/src/main/java/com/yap/app/data/local/Daos.kt"
        ).readText()
        assertTrue("recentBySession 必须过滤 blocked", daos.contains("sessionId = :sessionId AND blocked = 0"))
        assertTrue("已读不能标到 blocked 消息上", daos.contains("readAt IS NULL AND blocked = 0"))
        val search = locate(
            "src/main/java/com/yap/app/data/local/MessageSearchSql.kt",
            "app/src/main/java/com/yap/app/data/local/MessageSearchSql.kt",
            "../app/src/main/java/com/yap/app/data/local/MessageSearchSql.kt"
        ).readText()
        assertTrue("回忆候选不能翻出 blocked", search.contains("AND m.blocked = 0"))
    }
}
