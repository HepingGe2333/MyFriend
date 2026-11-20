package com.yap.app.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.15 / schema v19：人格语言 + 对话主题总结。
 *
 * 盯三处 parity：实体字段、迁移对象、导出的 schema json。少一处都是「真机上才发现的升级问题」。
 */
class SchemaV19Test {

    private fun locate(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("找不到：${candidates.joinToString(" / ")}")

    private val schemaDir = locate(
        "schemas/com.yap.app.data.local.YapDatabase",
        "app/schemas/com.yap.app.data.local.YapDatabase"
    )
    private val databaseSource = locate(
        "src/main/java/com/yap/app/data/local/YapDatabase.kt",
        "app/src/main/java/com/yap/app/data/local/YapDatabase.kt"
    )
    private val appModuleSource = locate(
        "src/main/java/com/yap/app/di/AppModule.kt",
        "app/src/main/java/com/yap/app/di/AppModule.kt"
    )
    private val entitiesSource = locate(
        "src/main/java/com/yap/app/data/local/Entities.kt",
        "app/src/main/java/com/yap/app/data/local/Entities.kt"
    )

    @Test
    fun `v19 schema 有人格语言与主题总结列`() {
        val file = File(schemaDir, "19.json")
        assertTrue("必须导出 19.json（exportSchema = true）", file.exists())
        val database = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        assertEquals(19, database["version"]!!.jsonPrimitive.int)

        val persona = database["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == "personas" }
        val personaColumns = persona["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertTrue("personas.languageTag", personaColumns.contains("languageTag"))

        val sessionMemory = database["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == "session_memory" }
        val memoryColumns = sessionMemory["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertTrue("session_memory.topicsJson", memoryColumns.contains("topicsJson"))
        assertTrue("session_memory.topicUserCount", memoryColumns.contains("topicUserCount"))
        assertTrue("session_memory.topicUpdatedAt", memoryColumns.contains("topicUpdatedAt"))
    }

    @Test
    fun `MIGRATION_18_19 只加列且幂等`() {
        val text = databaseSource.readText()
        val start = text.indexOf("MIGRATION_18_19 = object : Migration(18, 19)")
        assertTrue("没找到 MIGRATION_18_19", start >= 0)
        val block = text.substring(start, minOf(start + 1_200, text.length))
        assertTrue("加 languageTag", block.contains("ADD COLUMN `languageTag` TEXT"))
        assertTrue("加 topicsJson", block.contains("ADD COLUMN `topicsJson` TEXT"))
        assertTrue("加 topicUserCount", block.contains("ADD COLUMN `topicUserCount` INTEGER NOT NULL DEFAULT 0"))
        assertTrue("先查列再改（幂等）", block.contains("columns(db, \"personas\")"))
        assertTrue("先查列再改（幂等）", block.contains("columns(db, \"session_memory\")"))
    }

    @Test
    fun `v19 迁移注册进 Room`() {
        assertTrue(
            "写了迁移忘记注册 = 没写",
            appModuleSource.readText().contains("YapDatabase.MIGRATION_18_19")
        )
    }

    @Test
    fun `实体字段与迁移一一对应`() {
        val text = entitiesSource.readText()
        assertTrue(text.contains("val languageTag: String? = null"))
        assertTrue(text.contains("val topicsJson: String? = null"))
        assertTrue(text.contains("val topicUserCount: Int = 0"))
        assertTrue(text.contains("val topicUpdatedAt: Long? = null"))
    }
}
