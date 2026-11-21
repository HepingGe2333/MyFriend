package com.yap.app.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.15 / schema v20：模型优化开关（`personas.modelProfile` / `persona_presets.modelProfile`）。
 *
 * 三处 parity（实体字段、迁移对象、导出的 schema json）少一处都是「真机上才发现的升级问题」；
 * 另外钉住「只加一列、默认 `'AUTO'`、非空」——写成可空或没有默认值，老库升级后
 * 每一行都会变成 NULL/空串，`ModelProfile.fromStored` 虽然会回落 AUTO，但 schema 与规范就不一致了。
 */
class SchemaV20Test {

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

    /** 从 20.json 里取某张表的某列。 */
    private fun column(table: String, column: String): Map<String, kotlinx.serialization.json.JsonElement> {
        val database = Json.parseToJsonElement(File(schemaDir, "20.json").readText())
            .jsonObject["database"]!!.jsonObject
        assertEquals("20.json 的版本号必须是 20", 20, database["version"]!!.jsonPrimitive.int)
        val entity = database["entities"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["tableName"]!!.jsonPrimitive.content == table }
            ?: error("20.json 里没有 $table 表")
        return entity["fields"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["columnName"]!!.jsonPrimitive.content == column }
            ?: error("20.json 的 $table 里没有 $column 列")
    }

    @Test
    fun `v20 schema 里人格与预设都有模型优化列`() {
        listOf("personas", "persona_presets").forEach { table ->
            val field = column(table, "modelProfile")
            assertEquals("$table.modelProfile 必须是 TEXT", "TEXT", field["affinity"]!!.jsonPrimitive.content)
            assertEquals("$table.modelProfile 必须非空（默认 AUTO）", true, field["notNull"]!!.jsonPrimitive.boolean)
            assertEquals(
                "$table.modelProfile 的默认值必须是 'AUTO'（带 SQL 引号）",
                "'AUTO'",
                field["defaultValue"]!!.jsonPrimitive.content
            )
        }
    }

    @Test
    fun `v19 的列一个都没丢`() {
        assertEquals("personas.languageTag", "TEXT", column("personas", "languageTag")["affinity"]!!.jsonPrimitive.content)
        assertEquals(
            "session_memory.topicsJson",
            "TEXT",
            column("session_memory", "topicsJson")["affinity"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `MIGRATION_19_20 只加列且幂等`() {
        val text = databaseSource.readText()
        val start = text.indexOf("MIGRATION_19_20 = object : Migration(19, 20)")
        assertTrue("没找到 MIGRATION_19_20", start >= 0)
        val docComment = text.indexOf("/**", start)
        val end = if (docComment > start) docComment else minOf(text.length, start + 1_400)
        val block = text.substring(start, end)

        assertTrue(
            "DDL 必须与 schema 逐字对齐：personas",
            block.contains("ALTER TABLE `personas` ADD COLUMN `modelProfile` TEXT NOT NULL DEFAULT 'AUTO'")
        )
        assertTrue(
            "DDL 必须与 schema 逐字对齐：persona_presets",
            block.contains("ALTER TABLE `persona_presets` ADD COLUMN `modelProfile` TEXT NOT NULL DEFAULT 'AUTO'")
        )
        assertTrue("先查列再改（幂等）：personas", block.contains("columns(db, \"personas\")"))
        assertTrue("先查列再改（幂等）：persona_presets", block.contains("columns(db, \"persona_presets\")"))
        assertTrue("预设表不存在时不能崩", block.contains("isNotEmpty()"))
        assertTrue("只有 ALTER TABLE，不许动别的表", !block.contains("DROP ") && !block.contains("CREATE TABLE"))
    }

    @Test
    fun `v20 迁移注册进 Room`() {
        assertTrue(
            "写了迁移忘记注册 = 没写",
            appModuleSource.readText().contains("YapDatabase.MIGRATION_19_20")
        )
    }

    @Test
    fun `实体字段与迁移一一对应`() {
        val text = entitiesSource.readText()
        assertEquals(
            "两处（人格 + 预设）都要声明 modelProfile",
            2,
            Regex("""val\s+modelProfile\s*:\s*String\s*=\s*"AUTO"""").findAll(text).count()
        )
        assertEquals(
            "两处都要带列默认值 AUTO（否则老库 ALTER 出来的默认值与实体不一致）",
            2,
            Regex("""@ColumnInfo\(defaultValue = "AUTO"\)\s*\n\s*val\s+modelProfile""").findAll(text).count()
        )
    }
}
