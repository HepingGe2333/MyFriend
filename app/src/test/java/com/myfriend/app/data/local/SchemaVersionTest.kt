package com.myfriend.app.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
            "这些迁移只在 MyFriendDatabase 里声明了，却没进 AppModule.addMigrations(...)，等于没写：$missing",
            missing.isEmpty()
        )
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
        const val SCHEMA_DIR = "schemas/com.myfriend.app.data.local.MyFriendDatabase"
        const val DATABASE_SOURCE = "src/main/java/com/myfriend/app/data/local/MyFriendDatabase.kt"
        const val APP_MODULE_SOURCE = "src/main/java/com/myfriend/app/di/AppModule.kt"

        /** `@Database(... version = 8 ...)`：注解参数可能跨很多行（entities 列表在前）。 */
        val DATABASE_VERSION = Regex("""@Database\s*\([\s\S]*?\bversion\s*=\s*(\d+)""")

        /** `val MIGRATION_7_8 = object : Migration(7, 8) {` */
        val MIGRATION_DECLARATION =
            Regex("""MIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\(\s*(\d+)\s*,\s*(\d+)\s*\)""")

        val ADD_MIGRATIONS = Regex("""addMigrations\(([\s\S]*?)\)\s*""")
    }
}
