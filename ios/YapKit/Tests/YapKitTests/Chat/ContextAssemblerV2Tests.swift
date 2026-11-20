import XCTest
@testable import YapKit

/// 0.15 上下文装配 v2（`docs/design/context-memory-v2.md`）的规则测试：
/// 顺序、预算、以及「超限先压主题、再减历史」两条裁剪次序。
final class ContextAssemblerV2Tests: XCTestCase {

    private func message(_ text: String, role: String = "user") -> WireMessage {
        WireMessage(role: role, content: text)
    }

    private func topics(_ count: Int, chars: Int = 20) -> [MemoryContract.TopicSegment] {
        (0..<count).map { index in
            MemoryContract.TopicSegment(
                timeLabel: "2026-11-\(index + 1) 晚",
                summary: String(repeating: "话", count: chars) + "第\(index)段"
            )
        }
    }

    // MARK: - 顺序

    func testSegmentsAppearInFixedOrder() {
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            personaAndTools: "PERSONA_AND_TOOLS",
            longTermMemory: "LONG_MEMORY_AAA",
            shortTermMemory: "SHORT_MEMORY_BBB",
            currentState: "CURRENT_STATE_CCC",
            topics: [MemoryContract.TopicSegment(timeLabel: "2026-11-18 晚", summary: "TOPIC_DDD")],
            history: [message("HISTORY_EEE")]
        ))
        let system = output.system
        let order = ["PERSONA_AND_TOOLS", "LONG_MEMORY_AAA", "SHORT_MEMORY_BBB",
                     "CURRENT_STATE_CCC", "TOPIC_DDD"].compactMap { system.range(of: $0)?.lowerBound }
        XCTAssertEqual(order.count, 5, "固定段必须全部出现：\(system)")
        XCTAssertEqual(order, order.sorted(), "固定段顺序不能乱：\(system)")
        XCTAssertEqual(output.history.map(\.content), ["HISTORY_EEE"])
    }

    func testSectionTitlesComeFromSharedContract() {
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            personaAndTools: "人设",
            longTermMemory: "长期",
            shortTermMemory: "短期",
            currentState: "状态",
            topics: [MemoryContract.TopicSegment(timeLabel: "2026-11-18 晚", summary: "主题")],
            history: [message("你好")]
        ))
        XCTAssertTrue(output.system.contains(MemoryContract.Injection.TITLE), "缺总标题：\(output.system)")
        XCTAssertTrue(output.system.contains(MemoryContract.Injection.LONG_TERM_TITLE))
        XCTAssertTrue(output.system.contains(MemoryContract.Injection.SHORT_TERM_TITLE))
        XCTAssertTrue(output.system.contains(MemoryContract.Injection.TOPIC_TITLE))
        XCTAssertTrue(output.system.contains("- 2026-11-18 晚：主题"))
    }

    // MARK: - 硬顶

    func testLongAndShortMemoryAreCapped() {
        let long = String(repeating: "长", count: 900)
        let short = String(repeating: "短", count: 400)
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            personaAndTools: "人设",
            longTermMemory: long,
            shortTermMemory: short,
            history: [message("hi")]
        ))
        XCTAssertLessThanOrEqual(
            output.segmentChars[.longTermMemory] ?? 0,
            MemoryContract.Summary.LONG_TERM_MAX_CHARS + 1,
            "长期记忆必须截到 500 字（+省略号）"
        )
        XCTAssertLessThanOrEqual(
            output.segmentChars[.shortTermMemory] ?? 0,
            MemoryContract.Summary.SHORT_TERM_MAX_CHARS + 1,
            "短期记忆必须截到 200 字（+省略号）"
        )
    }

    func testTopicsAreTruncatedBeforeHistoryIsCut() {
        // 主题每段都超 120 字：先把每段压到 120，而不是先砍历史。
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            personaAndTools: "人设",
            topics: topics(3, chars: 400),
            history: (0..<20).map { message("历史\($0)") }
        ))
        XCTAssertTrue(output.topicsTruncated, "每段超 120 字必须逐段截断")
        XCTAssertGreaterThan(output.history.count, 10, "压主题之后历史不该被砍光")
    }

    func testTopicCountIsLimitedToTwelve() {
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            personaAndTools: "人设",
            topics: topics(20),
            history: [message("hi")],
            contextWindowTokens: ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS,
            maxOutputTokens: ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS,
            practicalChars: 100_000
        ))
        XCTAssertLessThanOrEqual(output.droppedTopics, 20)
        let topicLines = output.system.split(separator: "\n").filter { $0.hasPrefix("- 2026-") }
        XCTAssertLessThanOrEqual(topicLines.count, MemoryContract.Summary.TOPIC_MAX_SEGMENTS)
    }

    func testHistoryBudgetShrinksWhenFixedSegmentsOverflow() {
        // 人设自己就超过了 18% 的段预算：超出部分从历史预算里扣，总量仍然守住硬约束。
        let history = (0..<30).map { message(String(repeating: "史", count: 100) + "\($0)") }
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            personaAndTools: String(repeating: "设", count: 3000),
            history: history,
            contextWindowTokens: 20_000,
            maxOutputTokens: 4_000,
            practicalChars: 4_000
        ))
        XCTAssertTrue(output.fixedSegmentsOverBudget)
        XCTAssertLessThan(output.history.count, history.count, "固定段超了，历史必须变少")
        XCTAssertEqual(output.history.last?.content, history.last?.content, "最新一条必须留下")
        let total = output.system.count + output.history.reduce(0) { $0 + $1.content.count }
        XCTAssertLessThanOrEqual(total, 4_000 + 40, "总字符要守住硬预算（允许分隔符的少量余量）")
    }

    func testHistoryKeepsNewestMessagesWithinBudget() {
        let history = (0..<40).map { message(String(repeating: "字", count: 300) + "\($0)") }
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            personaAndTools: "人设",
            history: history,
            contextWindowTokens: 20_000,
            maxOutputTokens: 4_000,
            practicalChars: 10_000
        ))
        XCTAssertFalse(output.history.isEmpty)
        XCTAssertEqual(output.history.last?.content, history.last?.content, "最新一条必须留下")
        XCTAssertGreaterThan(output.droppedHistoryMessages, 0, "预算不够时必须从最旧的开始丢")
        let total = output.history.reduce(0) { $0 + $1.content.count }
        XCTAssertLessThanOrEqual(total, output.historyCharBudget + 100, "历史正文字数不能超过预算")
    }

    func testZeroBudgetKeepsOnlyPersona() {
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            personaAndTools: "人设",
            longTermMemory: "长期",
            shortTermMemory: "短期",
            currentState: "状态",
            topics: [MemoryContract.TopicSegment(timeLabel: "x", summary: "主题")],
            history: [message("hi")],
            contextWindowTokens: 0,
            maxOutputTokens: 0,
            practicalChars: 0
        ))
        XCTAssertEqual(output.system, "人设")
        XCTAssertTrue(output.history.isEmpty)
        XCTAssertEqual(output.plan.historyCharBudget, 0)
        XCTAssertEqual(output.plan.validate(), [])
    }

    func testPracticalPlanUsesProductCapWhenWindowHuge() {
        let output = ContextAssemblerV2.assemble(ContextV2Input(personaAndTools: "人设"))
        XCTAssertEqual(output.plan.historyCharBudget, ExperienceContract.PRACTICAL_HISTORY_CHARS)
        XCTAssertEqual(output.plan.validate(), [])
        XCTAssertEqual(output.plan.charLimit(for: .remainingHistory),
                       output.plan.historyCharBudget - ExperienceContract.shared.planForBudget(
                           historyCharBudget: ExperienceContract.PRACTICAL_HISTORY_CHARS
                       ).segments.filter { $0.segment != .remainingHistory }.reduce(0) { $0 + $1.charLimit })
    }

    func testTrimHistoryAlwaysKeepsNewest() {
        let history = [message("a"), message("b"), message("c")]
        XCTAssertEqual(ContextAssemblerV2.trimHistory(history, budget: 0).count, 0)
        XCTAssertEqual(ContextAssemblerV2.trimHistory(history, budget: 1).map(\.content), ["c"])
        XCTAssertEqual(ContextAssemblerV2.trimHistory(history, budget: 2).map(\.content), ["b", "c"])
        XCTAssertEqual(ContextAssemblerV2.trimHistory(history, budget: 100).count, 3)
    }

    func testEmptySegmentsAreOmittedWithoutBlankTitles() {
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            personaAndTools: "人设",
            history: [message("hi")]
        ))
        XCTAssertFalse(output.system.contains(MemoryContract.Injection.LONG_TERM_TITLE))
        XCTAssertFalse(output.system.contains(MemoryContract.Injection.SHORT_TERM_TITLE))
        XCTAssertFalse(output.system.contains(MemoryContract.Injection.TOPIC_TITLE))
    }
}
