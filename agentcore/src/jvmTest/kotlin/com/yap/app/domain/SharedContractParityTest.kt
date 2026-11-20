package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 共享常量与**现有实现**的对照测试（0.15 下沉的第一层保护）。
 *
 * 这一批不替换任何现有类：`app` / `ios` / `agentd` 仍各写一份。测试做两件事：
 * 1. 新对象与 `:agentcore` 里已有的唯一真源（`PersonaPromptScaffold`）逐字一致；
 * 2. 旧实现里的字面量**还在**（或者已经改成引用共享常量），证明新常量没有凭空发明一个值。
 *
 * 为什么要读仓库文件：工具能力标题、反 AI 标题、时间格式这些常量目前只存在于
 * `app` / `ios` / `agentd` 的源码里，`agentcore` 拿不到它们（也不该反过来依赖 `app`）。
 * 迁移完成后第 2 条会自动通过（源码里出现 `PromptContract` / `MemoryContract` 等共享引用），
 * 所以这个测试不会在后续迁移时变成假红。找不到文件时按环境问题跳过（不把 sandbox 当失败）。
 */
class SharedContractParityTest {

    // ------------------------------------------------------------------ 与 agentcore 既有真源对照

    @Test
    fun `prompt contract delegates to persona scaffold instead of copying`() {
        assertEquals(
            PersonaPromptScaffold.Section.IDENTITY,
            PromptContract.Section.IDENTITY
        )
        assertEquals(
            PersonaPromptScaffold.Section.BOUNDARIES,
            PromptContract.Section.BOUNDARIES
        )
        assertEquals(
            PersonaPromptScaffold.Line.STATE_LINE,
            PromptContract.Time.STATE_LINE
        )
        assertEquals(
            PersonaPromptScaffold.Line.boundaryRules,
            PromptContract.Boundaries.RULES
        )
    }

    @Test
    fun `budget numbers match the existing api defaults implementation`() {
        // 与 app/src/main/java/com/yap/app/domain/ApiDefaults.kt 的常量同值。
        assertEquals(1_000_000, ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS)
        assertEquals(16_384, ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS)
        assertEquals(2, ExperienceContract.CHARS_PER_TOKEN)
        assertEquals(
            (1_000_000 - 16_384) * 2,
            ExperienceContract.historyCharBudget()
        )
        // 与 iOS ApiDefaults.historyCharBudget 同式：窗口减去输出再乘 2。
        assertEquals(
            72_000,
            ExperienceContract.historyCharBudget(contextWindowTokens = 100_000, maxOutputTokens = 64_000)
        )
    }

    // ------------------------------------------------------------------ 与 Android 源码对照

    @Test
    fun `android capability and anti ai titles still exist`() {
        assertMigratable(
            path = "app/src/main/java/com/yap/app/domain/ToolProtocol.kt",
            legacyLiteral = "# 你能做什么",
            sharedMarker = "PromptContract"
        )
        assertMigratable(
            path = "app/src/main/java/com/yap/app/domain/PromptBuilder.kt",
            legacyLiteral = "# 别像 AI",
            sharedMarker = "PromptContract"
        )
    }

    @Test
    fun `android api defaults still declare the same numbers`() {
        assertMigratable(
            path = "app/src/main/java/com/yap/app/domain/ApiDefaults.kt",
            legacyLiteral = "16_384",
            sharedMarker = "ExperienceContract"
        )
        val text = readRepoText("app/src/main/java/com/yap/app/domain/ApiDefaults.kt")
        assertTrue("Android 默认窗口应为 1_000_000", text.contains("1_000_000"))
        assertTrue("Android token→字符折算应为 2", text.contains("CHARS_PER_TOKEN"))
    }

    @Test
    fun `android memory memo and mental activity wording still exists`() {
        assertMigratable(
            path = "app/src/main/java/com/yap/app/domain/SessionMemory.kt",
            legacyLiteral = "## 你的备忘录",
            sharedMarker = "MemoryContract"
        )
        assertMigratable(
            path = "app/src/main/java/com/yap/app/domain/MentalActivity.kt",
            legacyLiteral = "你刚才心里想：",
            sharedMarker = "PromptContract"
        )
    }

    @Test
    fun `android block protocol strings still exist`() {
        val text = readRepoText("app/src/main/java/com/yap/app/domain/Blocking.kt")
        val hasLegacy = text.contains("pending_confirmation") &&
            text.contains("blockedUntil") &&
            text.contains("rejected")
        assertTrue(
            "Blocking.kt 既没有 §9 的状态字面量，也没有引用 BlockProtocol（迁移时至少保留一个）",
            hasLegacy || text.contains("BlockProtocol")
        )
    }

    @Test
    fun `android and agentd still declare tool round constants`() {
        listOf(
            "app/src/main/java/com/yap/app/data/repo/ChatRepository.kt",
            "app/src/main/java/com/yap/app/domain/ToolRounds.kt",
            "agentd/src/main/kotlin/com/yap/agentd/llm/LlmReplyGenerator.kt"
        ).forEach { path ->
            val text = readRepoText(path)
            assertTrue(
                "$path 应引用共享轮数契约（ExperienceContract / ToolRounds / clampToolRounds）",
                text.contains("ExperienceContract") || text.contains("ToolRounds") ||
                    text.contains("clampToolRounds") || text.contains("DEFAULT_TOOL_ROUNDS")
            )
        }
    }

    @Test
    fun `android rhythm still uses the old formula constants for comparison`() {
        val text = readRepoText("app/src/main/java/com/yap/app/domain/ExperienceRhythm.kt")
        assertTrue(
            "ExperienceRhythm.kt 里应还能找到旧公式的常量（迁移前对照用）",
            text.contains("TYPING_BASE_MS") || text.contains("ExperienceContract")
        )
        assertTrue(text.contains("typingDelayMillis") || text.contains("ExperienceContract"))
    }

    // ------------------------------------------------------------------ 与 iOS / 规范对照

    @Test
    fun `ios capability and anti ai titles still exist`() {
        assertMigratable(
            path = "ios/YapKit/Sources/YapKit/Chat/ToolProtocol.swift",
            legacyLiteral = "# 你能做什么",
            sharedMarker = "PromptContract"
        )
        assertMigratable(
            path = "ios/YapKit/Sources/YapKit/Chat/AntiAIPrompt.swift",
            legacyLiteral = "# 别像 AI",
            sharedMarker = "PromptContract"
        )
    }

    @Test
    fun `anti ai rules appear verbatim in both existing implementations`() {
        val android = readRepoText("app/src/main/java/com/yap/app/domain/PromptBuilder.kt")
        val ios = readRepoText("ios/YapKit/Sources/YapKit/Chat/AntiAIPrompt.swift")
        for (rule in PromptContract.AntiAi.RULES) {
            assertTrue(
                "Android PromptBuilder 缺少反 AI 规则：$rule",
                android.contains(rule) || android.contains("PromptContract")
            )
            assertTrue(
                "iOS AntiAIPrompt 缺少反 AI 规则：$rule",
                ios.contains(rule) || ios.contains("PromptContract")
            )
        }
    }

    @Test
    fun `time formats still use the same HH mm and UTC shape on both ends`() {
        val android = readRepoText("app/src/main/java/com/yap/app/domain/PromptBuilder.kt")
        assertTrue(android.contains("HH:mm") || android.contains("PromptContract"))
        assertTrue(android.contains("yyyy-MM-dd") || android.contains("PromptContract"))
        assertTrue(android.contains("UTC") || android.contains("PromptContract"))
        val ios = readRepoText("ios/YapKit/Sources/YapKit/Domain/PromptGuidanceJSON.swift")
        assertTrue(ios.contains("HH:mm") || ios.contains("clockText") || ios.contains("PromptContract"))
        // iOS 手写 `%04d-%02d-%02d`（避免 DateFormatter 跟随系统语言），形状与 `yyyy-MM-dd` 一致。
        assertTrue(
            ios.contains("%04d-%02d-%02d") || ios.contains("yyyy-MM-dd") || ios.contains("PromptContract")
        )
        assertTrue(ios.contains("UTC") || ios.contains("PromptContract"))
    }

    @Test
    fun `ios api defaults still declare the same numbers`() {
        val text = readRepoText("ios/YapKit/Sources/YapKit/Net/ApiDefaults.swift")
        assertTrue(text.contains("16_384"))
        assertTrue(text.contains("1_000_000"))
        assertTrue(text.contains("charsPerToken"))
    }

    @Test
    fun `ios typing rhythm still exists for comparison`() {
        val text = readRepoText("ios/YapKit/Sources/YapKit/Domain/MessageRhythm.swift")
        assertTrue(text.contains("TypingDelay"))
        assertTrue(text.contains("burstGap") || text.contains("MessageRhythm") || text.contains("ExperienceContract"))
    }

    @Test
    fun `spec files still carry the canonical titles`() {
        val moodSpec = readRepoText("spec/mood/README.md")
        assertTrue(moodSpec.contains("# 你的心情"))
        val experienceSpec = readRepoText("spec/experience/README.md")
        assertTrue(experienceSpec.contains("pending_confirmation"))
        assertTrue(experienceSpec.contains("blockedUntil"))
        assertTrue(experienceSpec.contains("expiresAt"))
    }

    @Test
    fun `agentd mental activity title still exists`() {
        val text = readRepoText("agentd/src/main/kotlin/com/yap/agentd/experience/MentalActivityService.kt")
        assertTrue(
            "agentd 里应还能找到旧的心理活动标题/注入行（迁移前对照用）",
            text.contains("# 你刚才心里想的") || text.contains("你刚才心里想")
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun assertMigratable(path: String, legacyLiteral: String, sharedMarker: String) {
        val text = readRepoText(path)
        assertTrue(
            "$path 既没有旧字面量 `$legacyLiteral`，也没有引用共享常量 `$sharedMarker`（迁移时至少保留一个）",
            text.contains(legacyLiteral) || text.contains(sharedMarker)
        )
    }

    private fun readRepoText(path: String): String {
        val root = repoRoot()
        assumeTrue("找不到仓库根目录（从 ${System.getProperty("user.dir")} 向上找）", root != null)
        val file = File(root, path)
        assumeTrue("找不到 $path（跳过源码对照；CI 完整仓库里应存在）", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }
}
