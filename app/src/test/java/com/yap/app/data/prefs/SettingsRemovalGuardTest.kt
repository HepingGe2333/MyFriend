package com.yap.app.data.prefs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「消失的设置」守卫（规格 §一 / §九）。
 *
 * 这一版最容易被做了一半的事，是界面上删了、代码里还留着：某个后台任务照样读旧开关，
 * 或者备份又把删掉的字段写回去。产品上看起来「设置没了」，行为却还是旧的，这种问题很难靠走查发现。
 *
 * 所以这里直接扫源码：删掉的设置名、预算类名，在 `app/src/main/java` 里出现一次就算失败。
 * 对应验收命令：
 * grep -rn "proactiveDailyLimit|...|budgetVerdict" app/src/main/java  → 0。
 */
class SettingsRemovalGuardTest {

    private fun locate(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("找不到主源码目录（工作目录=${File("").absolutePath}）：${candidates.joinToString(" / ")}")

    private val mainSourceDir: File by lazy {
        locate("src/main/java", "app/src/main/java", "../app/src/main/java")
    }

    /** 被彻底删除的设置键 / 预算类名。 */
    private val bannedTokens = listOf(
        "proactiveDailyLimit",
        "monthlyBudgetMicroUsd",
        "replyPace",
        "memoryConsolidationEnabled",
        "autoMemoryEnabled",
        "webSearchScoringEnabled",
        "overBudgetChatOnly",
        "inputPriceUsdPerMillion",
        "outputPriceUsdPerMillion",
        // 注意：memoryEnabled 只在「旧备份兼容」里可能作为注释出现；1.9 的实现连注释都不再用它。
        "memoryEnabled",
        "ApiBudget",
        "budgetVerdict",
        "BudgetVerdict",
        "BudgetInput"
    )

    /** 设置页里不该再出现的栏目名。 */
    private val bannedUiLabels = listOf(
        "长期记忆",
        "自动整理记忆",
        "每天整理记忆",
        "联网时判断重要性",
        "读消息的时间",
        "每天最多主动几次",
        "每月额度",
        "用量与花费"
    )

    @Test
    fun `被删除的设置与预算代码在主源码里一次都不出现`() {
        val hits = mutableListOf<String>()
        mainSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                bannedTokens.forEach { token ->
                    if (text.contains(token)) hits += "${file.relativeTo(mainSourceDir)} → $token"
                }
            }
        assertTrue(
            "这些设置早就该删干净了，但主源码里还找得到：\n" + hits.joinToString("\n"),
            hits.isEmpty()
        )
    }

    @Test
    fun `ApiBudget 源文件已经不存在`() {
        val budgetFile = File(mainSourceDir, "com/yap/app/data/repo/ApiBudget.kt")
        assertTrue("ApiBudget.kt 应该被整个删掉，而不是留一个空壳", !budgetFile.exists())
    }

    @Test
    fun `设置页不再出现记忆开关 节奏 额度 主动次数`() {
        val settingsScreen = File(
            mainSourceDir,
            "com/yap/app/ui/screens/settings/SettingsScreen.kt"
        )
        assertTrue("找不到 SettingsScreen.kt：${settingsScreen.absolutePath}", settingsScreen.exists())
        val text = settingsScreen.readText()
        val found = bannedUiLabels.filter { text.contains(it) }
        assertTrue("设置页还在显示这些早该消失的栏目：$found", found.isEmpty())
    }

    @Test
    fun `AppSettings 里不再有被删除的字段`() {
        val appSettings = File(mainSourceDir, "com/yap/app/data/prefs/AppSettings.kt")
        assertTrue(appSettings.exists())
        val text = appSettings.readText()
        val found = bannedTokens.filter { text.contains(it) }
        assertTrue("AppSettings 里还有被删除的设置字段：$found", found.isEmpty())
        // 外观与后台开关仍然要留着：这一版删的不是全部设置，是「调机器人」的那部分。
        assertTrue(text.contains("themeMode"))
        assertTrue(text.contains("agentLoopEnabled"))
    }
}
