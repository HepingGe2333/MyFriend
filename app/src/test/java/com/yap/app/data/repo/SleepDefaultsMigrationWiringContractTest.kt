package com.yap.app.data.repo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 一次性迁移的**接线**（0.12 续 7）。
 *
 * 条件与「只跑一次」的编排在 `SleepDefaultsMigration`（有真单测），但「启动时到底有没有调它、
 * 有没有写标记」只有源码契约能拦住 —— 本工程没有 Robolectric，`PersonaRepositoryInitializer`
 * 依赖 `Context` + DataStore，纯 JVM 里实例化不了。
 *
 * 这里钉四件事：
 * 1. 启动播种入口先跑迁移（哪怕是老用户「库里已有人」的那条早退分支）；
 * 2. 迁移真的落一条 `UPDATE`（`PersonaDao.disableSleepFor`），不是只在内存里算；
 * 3. 跑完写一次性标记（DataStore 键 `sleep_defaults_migrated_v1`）；
 * 4. 日志只说人数，不含用户内容。
 */
class SleepDefaultsMigrationWiringContractTest {

    private fun source(relative: String) =
        File("src/main/java/com/yap/app/$relative").also {
            assertTrue("找不到源文件：${it.path}", it.isFile)
        }.readText()

    private val initializer = source("data/repo/PersonaRepositoryInitializer.kt")
    private val dao = source("data/local/Daos.kt")
    private val settings = source("data/prefs/SettingsRepository.kt")

    @Test
    fun `启动时先跑迁移再播种`() {
        val body = initializer.substringAfter("suspend fun seedIfNeeded()")
        val call = body.indexOf("migrateSleepDefaults()")
        assertTrue("种子判定里必须调用迁移", call >= 0)
        assertTrue(
            "迁移必须在「库里已有人」的早退判断之前（老用户走的就是那条分支）",
            call < body.indexOf("if (personaDao.count() > 0)")
        )
        assertTrue(
            "迁移也必须在真正插入种子之前",
            call < body.indexOf("SeedPersonas.build(")
        )
    }

    @Test
    fun `迁移真的写库`() {
        assertTrue(
            "PersonaDao 要有一条批量关睡眠的 UPDATE",
            dao.contains("UPDATE personas SET sleepEnabled = 0 WHERE id IN (:ids)")
        )
        assertTrue(
            "初始化器要调用它（不是只在内存里算一遍）",
            initializer.contains("personaDao.disableSleepFor(")
        )
        assertTrue(
            "筛选条件走纯函数，SQL 里不再重复写条件",
            initializer.contains("SleepDefaultsMigration.plan(")
        )
    }

    @Test
    fun `跑完写一次性标记`() {
        assertTrue("DataStore 键要与 iOS 同名", settings.contains("\"sleep_defaults_migrated_v1\""))
        assertTrue(
            "迁移读标记",
            initializer.contains("settingsRepository.hasMigratedSleepDefaults()")
        )
        assertTrue(
            "迁移写标记",
            initializer.contains("settingsRepository.markSleepDefaultsMigrated()")
        )
    }

    @Test
    fun `日志不带用户内容`() {
        assertTrue(
            "要有一条 debug 日志，且带可检索的前缀",
            initializer.contains("Log.d(") && initializer.contains("\"sleepDefaultsMigratedV1")
        )
        val logLines = initializer.lines().filter { it.contains("Log.") }
        assertTrue("至少要有一条日志语句", logLines.isNotEmpty())
        assertTrue(
            "日志只该有人数与说明，不许拼名字/简介这类用户内容",
            logLines.none { it.contains("persona.name") || it.contains("persona.prompt") }
        )
    }
}
