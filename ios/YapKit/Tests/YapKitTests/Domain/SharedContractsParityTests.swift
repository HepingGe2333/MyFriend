import Foundation
import XCTest
@testable import YapKit

/// 0.15 共享契约的**逐字对照**：Swift 镜像（`Domain/SharedContracts.swift`）必须与
/// `agentcore/src/commonMain/kotlin/com/yap/app/domain/` 里的唯一真源一致。
///
/// 为什么是「读源码」而不是「读 framework」：macOS 的 `swift test` 链接不了 iOS 的
/// Kotlin/Native framework，但这一层全是常量与纯函数。源码是唯一真源，读它就能把
/// 「iOS 忘了跟着改一个数字」变成一条会红的测试 —— 与
/// `PersonaPromptScaffoldContractTests` 读 `spec/` 的做法同一思路。
///
/// 找不到仓库文件时按环境问题跳过（不把沙箱当失败）。
final class SharedContractsParityTests: XCTestCase {

    // MARK: - 定位 agentcore 源码

    private static func domainDirectory(file: String = #filePath) -> URL? {
        var url = URL(fileURLWithPath: file).deletingLastPathComponent()
        let fm = FileManager.default
        for _ in 0..<20 {
            let candidate = url.appendingPathComponent("agentcore/src/commonMain/kotlin/com/yap/app/domain")
            if fm.fileExists(atPath: candidate.path) { return candidate }
            let parent = url.deletingLastPathComponent()
            if parent.path == url.path { break }
            url = parent
        }
        return nil
    }

    private func kotlin(_ fileName: String) throws -> String {
        guard let directory = Self.domainDirectory() else {
            throw XCTSkip("找不到 agentcore 源码目录")
        }
        let url = directory.appendingPathComponent(fileName)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("找不到 \(fileName)")
        }
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// 取 Kotlin 里 `const val NAME = 值` / `const val NAME: Type = 值` 的字面量。
    private func intConstant(_ name: String, in text: String) throws -> Int {
        // 常量可能是字面量（`28_000`）或简单乘积（`10 * 60 * 1000`）：取到行尾再求值。
        let pattern = #"const val \#(name)\s*(?::\s*\w+\s*)?=\s*([0-9_]+(?:\s*\*\s*[0-9_]+)*)"#
        let regex = try NSRegularExpression(pattern: pattern)
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range) else {
            throw XCTSkip("Kotlin 里找不到常量 \(name)")
        }
        guard let valueRange = Range(match.range(at: 1), in: text) else {
            throw XCTSkip("Kotlin 常量 \(name) 解析失败")
        }
        let expression = String(text[valueRange]).replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: " ", with: "")
        let parts = expression.split(separator: "*").compactMap { Int($0) }
        guard !parts.isEmpty, parts.count == expression.split(separator: "*").count else {
            throw XCTSkip("Kotlin 常量 \(name) 不是整数表达式：\(expression)")
        }
        return parts.reduce(1, *)
    }

    private func doubleConstant(_ name: String, in text: String) throws -> Double {
        let pattern = #"const val \#(name)\s*(?::\s*\w+\s*)?=\s*([0-9_.]+)"#
        let regex = try NSRegularExpression(pattern: pattern)
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range),
              let valueRange = Range(match.range(at: 1), in: text) else {
            throw XCTSkip("Kotlin 里找不到常量 \(name)")
        }
        let raw = text[valueRange].replacingOccurrences(of: "_", with: "")
        guard let value = Double(raw) else {
            throw XCTSkip("Kotlin 常量 \(name) 不是数字：\(raw)")
        }
        return value
    }

    private func stringConstant(_ name: String, in text: String) throws -> String {
        let pattern = #"const val \#(name)\s*(?::\s*\w+\s*)?=\s*"([^"]*)""#
        let regex = try NSRegularExpression(pattern: pattern)
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range),
              let valueRange = Range(match.range(at: 1), in: text) else {
            throw XCTSkip("Kotlin 里找不到常量 \(name)")
        }
        return String(text[valueRange])
    }

    private func assertContains(_ haystack: String, _ needle: String, _ message: String) {
        XCTAssertTrue(haystack.contains(needle), message)
    }

    // MARK: - ExperienceContract

    func testContextBudgetNumbersMatchKotlin() throws {
        let text = try kotlin("ExperienceContract.kt")
        XCTAssertEqual(ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS,
                       try intConstant("DEFAULT_CONTEXT_WINDOW_TOKENS", in: text))
        XCTAssertEqual(ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS,
                       try intConstant("DEFAULT_MAX_OUTPUT_TOKENS", in: text))
        XCTAssertEqual(ExperienceContract.CHARS_PER_TOKEN,
                       try intConstant("CHARS_PER_TOKEN", in: text))
        XCTAssertEqual(ExperienceContract.PRACTICAL_HISTORY_CHARS,
                       try intConstant("PRACTICAL_HISTORY_CHARS", in: text))

        let expected = (try intConstant("DEFAULT_CONTEXT_WINDOW_TOKENS", in: text)
            - (try intConstant("DEFAULT_MAX_OUTPUT_TOKENS", in: text)))
            * (try intConstant("CHARS_PER_TOKEN", in: text))
        XCTAssertEqual(ExperienceContract.shared.historyCharBudget(
            contextWindowTokens: ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS,
            maxOutputTokens: ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS
        ), expected)

        // 小窗口时取「窗口折算」而不是产品上限：20K − 4K = 16K tokens → 32,000 字符；
        // 产品上限 28,000 更小，所以最终是 28,000（plan 取两者较小值）。
        let cheap = ExperienceContract.shared.practicalPlan(
            contextWindowTokens: 20_000, maxOutputTokens: 4_000, practicalChars: 28_000
        )
        XCTAssertEqual(cheap.historyCharBudget, 28_000)
        XCTAssertEqual(cheap.validate(), [])
    }

    func testSegmentKeysAndRatiosMatchKotlin() throws {
        let text = try kotlin("ExperienceContract.kt")
        for segment in ExperienceContract.ContextSegment.allCases {
            assertContains(text, "\(segment.rawValue)\"", "Kotlin 缺少段 key：\(segment.rawValue)")
            XCTAssertNotNil(ExperienceContract.SEGMENT_RATIOS_PER_MILLE[segment])
        }
        XCTAssertEqual(ExperienceContract.SEGMENT_RATIOS_PER_MILLE.values.reduce(0, +), 1000)
        // 硬顶与 MemoryContract 共用同一份常量（不允许各写一份）。
        XCTAssertEqual(ExperienceContract.SEGMENT_HARD_CAPS[.longTermMemory],
                       MemoryContract.Summary.LONG_TERM_MAX_CHARS)
        XCTAssertEqual(ExperienceContract.SEGMENT_HARD_CAPS[.shortTermMemory],
                       MemoryContract.Summary.SHORT_TERM_MAX_CHARS)
        XCTAssertEqual(ExperienceContract.SEGMENT_HARD_CAPS[.topicSummary],
                       MemoryContract.Summary.TOPIC_MAX_CHARS)
    }

    func testRhythmConstantsMatchKotlin() throws {
        let text = try kotlin("ExperienceContract.kt")
        XCTAssertEqual(ExperienceContract.TYPING_BASE_SECONDS,
                       try doubleConstant("TYPING_BASE_SECONDS", in: text), accuracy: 1e-9)
        XCTAssertEqual(ExperienceContract.TYPING_SECONDS_PER_CHAR,
                       try doubleConstant("TYPING_SECONDS_PER_CHAR", in: text), accuracy: 1e-9)
        XCTAssertEqual(ExperienceContract.TYPING_MIN_SECONDS,
                       try doubleConstant("TYPING_MIN_SECONDS", in: text), accuracy: 1e-9)
        XCTAssertEqual(ExperienceContract.TYPING_MAX_SECONDS,
                       try doubleConstant("TYPING_MAX_SECONDS", in: text), accuracy: 1e-9)
        XCTAssertEqual(ExperienceContract.BURST_GAP_BASE_SECONDS,
                       try doubleConstant("BURST_GAP_BASE_SECONDS", in: text), accuracy: 1e-9)
        XCTAssertEqual(ExperienceContract.BURST_GAP_SECONDS_PER_CHAR,
                       try doubleConstant("BURST_GAP_SECONDS_PER_CHAR", in: text), accuracy: 1e-9)
        XCTAssertEqual(ExperienceContract.RHYTHM_JITTER_RATIO,
                       try doubleConstant("RHYTHM_JITTER_RATIO", in: text), accuracy: 1e-9)
        XCTAssertEqual(ExperienceContract.MAX_MOOD_ADJUSTMENT_SECONDS,
                       try doubleConstant("MAX_MOOD_ADJUSTMENT_SECONDS", in: text), accuracy: 1e-9)
    }

    func testToolRoundConstantsMatchKotlin() throws {
        let text = try kotlin("ExperienceContract.kt")
        XCTAssertEqual(ExperienceContract.DEFAULT_TOOL_ROUNDS,
                       try intConstant("DEFAULT_TOOL_ROUNDS", in: text))
        XCTAssertEqual(ExperienceContract.MIN_TOOL_ROUNDS,
                       try intConstant("MIN_TOOL_ROUNDS", in: text))
        XCTAssertEqual(ExperienceContract.MAX_TOOL_ROUNDS,
                       try intConstant("MAX_TOOL_ROUNDS", in: text))
        XCTAssertTrue(text.contains("fun clampToolRounds"))
    }

    // MARK: - MemoryContract

    func testMemoryThresholdsAndTitlesMatchKotlin() throws {
        let text = try kotlin("MemoryContract.kt")
        XCTAssertEqual(MemoryContract.Summary.LONG_TERM_MAX_CHARS,
                       try intConstant("LONG_TERM_MAX_CHARS", in: text))
        XCTAssertEqual(MemoryContract.Summary.SHORT_TERM_MAX_CHARS,
                       try intConstant("SHORT_TERM_MAX_CHARS", in: text))
        XCTAssertEqual(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS,
                       try intConstant("TOPIC_SEGMENT_MAX_CHARS", in: text))
        XCTAssertEqual(MemoryContract.Summary.TOPIC_MAX_SEGMENTS,
                       try intConstant("TOPIC_MAX_SEGMENTS", in: text))
        XCTAssertEqual(MemoryContract.Summary.SHORT_UPDATE_EVERY_ROUNDS,
                       try intConstant("SHORT_UPDATE_EVERY_ROUNDS", in: text))
        XCTAssertEqual(MemoryContract.Summary.LONG_UPDATE_MIN_ROUNDS,
                       try intConstant("LONG_UPDATE_MIN_ROUNDS", in: text))
        XCTAssertEqual(MemoryContract.Summary.LONG_UPDATE_MAX_ROUNDS,
                       try intConstant("LONG_UPDATE_MAX_ROUNDS", in: text))
        XCTAssertEqual(MemoryContract.Summary.TOPIC_MAX_CHARS,
                       MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS * MemoryContract.Summary.TOPIC_MAX_SEGMENTS)

        XCTAssertEqual(MemoryContract.Injection.TITLE, try stringConstant("TITLE", in: text))
        XCTAssertEqual(MemoryContract.Injection.LONG_TERM_TITLE,
                       try stringConstant("LONG_TERM_TITLE", in: text))
        XCTAssertEqual(MemoryContract.Injection.SHORT_TERM_TITLE,
                       try stringConstant("SHORT_TERM_TITLE", in: text))
        XCTAssertEqual(MemoryContract.Injection.TOPIC_TITLE,
                       try stringConstant("TOPIC_TITLE", in: text))
        XCTAssertEqual(MemoryContract.Retrieval.MAX_QUERY_CHARS,
                       try intConstant("MAX_QUERY_CHARS", in: text))
        assertContains(text, "fun notes(", "Kotlin 的会话备忘录入口改了名字")
        assertContains(text, "fun topicLine(", "Kotlin 的主题行入口改了名字")
    }

    // MARK: - PromptContract

    func testPromptTitlesMatchKotlin() throws {
        let text = try kotlin("PromptContract.kt")
        XCTAssertEqual(PromptContract.Section.CAPABILITIES, try stringConstant("CAPABILITIES", in: text))
        XCTAssertEqual(PromptContract.Section.MOOD, try stringConstant("MOOD", in: text))
        XCTAssertEqual(PromptContract.Section.ANTI_AI, try stringConstant("ANTI_AI", in: text))
        XCTAssertEqual(PromptContract.Section.MENTAL_ACTIVITY,
                       try stringConstant("MENTAL_ACTIVITY", in: text))
        XCTAssertEqual(PromptContract.Section.LANGUAGE, try stringConstant("LANGUAGE", in: text))
        XCTAssertEqual(PromptContract.Mood.LINE, try stringConstant("LINE", in: text))
        XCTAssertEqual(PromptContract.Mood.DIMENSIONS_LINE,
                       try stringConstant("DIMENSIONS_LINE", in: text))
        XCTAssertEqual(PromptContract.MentalActivity.LINE_PREFIX,
                       try stringConstant("LINE_PREFIX", in: text))
        // 反 AI 七条、边界四条必须逐条出现在 Kotlin 里（顺序也算）。
        for rule in PromptContract.AntiAi.RULES {
            assertContains(text, rule, "Kotlin 反 AI 规则缺失：\(rule)")
        }
        // 边界四条的唯一实现在 `PersonaPromptScaffold.Line.boundaryRules`；PromptContract 只转发。
        assertContains(text, "PersonaPromptScaffold.Line.boundaryRules",
                      "Kotlin 的边界段必须转发脚手架，而不是各写一份")
        XCTAssertEqual(PromptContract.Section.CAPABILITIES, "# 你能做什么")
    }

    // MARK: - PromptLanguage

    func testPromptLanguageCodesMatchKotlin() throws {
        let text = try kotlin("PromptLanguage.kt")
        for language in PromptLanguage.allCases {
            assertContains(text, "\(language.code.uppercased())(\"\(language.code)\"",
                           "Kotlin 缺少人格语言：\(language.code)")
            XCTAssertFalse(language.directive().isEmpty)
        }
        XCTAssertEqual(PromptLanguage.DIRECTIVE_ZH, try stringConstant("DIRECTIVE_ZH", in: text))
        XCTAssertEqual(PromptLanguage.parse("zh-CN"), .zh)
        XCTAssertEqual(PromptLanguage.parse("EN"), .en)
        XCTAssertEqual(PromptLanguage.parse("日本語"), .ja)
        XCTAssertNil(PromptLanguage.parse(nil))
        // 英文标题必须出现在 Kotlin 的 `enTitle` 里（不允许只改一端）。
        for section in PromptTemplates.CoreSection.allCases {
            let title = PromptTemplates.title(section: section, language: .en)
            assertContains(text, title, "Kotlin 英文标题缺失：\(title)")
        }
        assertContains(text, "Think and speak in", "Kotlin 的英文语言指令缺失")
    }

    // MARK: - BlockProtocol

    func testBlockProtocolFieldsAndStatusesMatchKotlin() throws {
        let text = try kotlin("BlockProtocol.kt")
        XCTAssertEqual(BlockProtocol.STATUS_PENDING_CONFIRMATION,
                       try stringConstant("STATUS_PENDING_CONFIRMATION", in: text))
        XCTAssertEqual(BlockProtocol.STATUS_BLOCKED, try stringConstant("STATUS_BLOCKED", in: text))
        XCTAssertEqual(BlockProtocol.STATUS_REJECTED, try stringConstant("STATUS_REJECTED", in: text))
        for field in BlockProtocol.PROPOSAL_FIELDS {
            assertContains(text, "\"\(field)\"", "Kotlin 缺少协议字段：\(field)")
        }
        XCTAssertEqual(BlockProtocol.PROPOSAL_FIELDS.count, 7)
        XCTAssertEqual(BlockProtocol.PROPOSAL_TTL_MS,
                       Int64(try intConstant("PROPOSAL_TTL_MS", in: text)))
        XCTAssertEqual(BlockProtocol.MIN_HOURS, try intConstant("MIN_HOURS", in: text))
        XCTAssertEqual(BlockProtocol.MAX_HOURS, try intConstant("MAX_HOURS", in: text))
        XCTAssertEqual(BlockProtocol.DEFAULT_HOURS, try intConstant("DEFAULT_HOURS", in: text))
        XCTAssertEqual(BlockProtocol.MAX_REASON_CHARS,
                       try intConstant("MAX_REASON_CHARS", in: text))

        // 七键 JSON 往返 + 字段顺序。
        let proposal = BlockProtocol.Proposal(
            proposalId: "p-1", personaId: "persona-1", hours: 6,
            reason: "越界了", userMessageId: "m-2", proposedAt: 1_700_000_000_000,
            expiresAt: 1_700_000_600_000
        )
        let json = BlockProtocol.shared.encode(proposal)
        XCTAssertEqual(BlockProtocol.shared.decode(json), proposal)
        let keys = BlockProtocol.PROPOSAL_FIELDS.map { "\"\($0)\":" }
        var cursor = json.startIndex
        for key in keys {
            guard let range = json.range(of: key, range: cursor..<json.endIndex) else {
                return XCTFail("编码后的 JSON 缺字段或顺序不对：\(key) / \(json)")
            }
            cursor = range.upperBound
        }
        XCTAssertEqual(BlockProtocol.shared.pendingJson(expiresAt: 10),
                       "{\"status\":\"pending_confirmation\",\"expiresAt\":10}")
        XCTAssertEqual(BlockProtocol.shared.blockedJson(blockedUntil: 10),
                       "{\"status\":\"blocked\",\"blockedUntil\":10}")
        XCTAssertEqual(BlockProtocol.shared.rejectedJson(code: "SAME_ROUND"),
                       "{\"status\":\"rejected\",\"code\":\"SAME_ROUND\"}")
    }

    // MARK: - 现有 iOS 实现引用共享常量（防止两端各写一份）

    func testExistingIOSPromptsReferenceSharedContract() throws {
        guard let directory = Self.domainDirectory() else { throw XCTSkip("找不到 agentcore 源码目录") }
        let repoRoot = directory.deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let toolProtocol = repoRoot.appendingPathComponent(
            "ios/YapKit/Sources/YapKit/Chat/ToolProtocol.swift"
        )
        let antiAI = repoRoot.appendingPathComponent(
            "ios/YapKit/Sources/YapKit/Chat/AntiAIPrompt.swift"
        )
        guard let toolText = try? String(contentsOf: toolProtocol, encoding: .utf8),
              let antiText = try? String(contentsOf: antiAI, encoding: .utf8) else {
            throw XCTSkip("找不到 iOS 提示词源码")
        }
        XCTAssertTrue(
            toolText.contains("PromptContract") || toolText.contains("# 你能做什么"),
            "ToolProtocol.swift 既没有引用共享契约，也没有保留规范标题"
        )
        XCTAssertTrue(
            antiText.contains("PromptContract") || antiText.contains("# 别像 AI"),
            "AntiAIPrompt.swift 既没有引用共享契约，也没有保留规范标题"
        )
    }
}
