import XCTest
@testable import YapKit

/// 0.15 上下文装配 v2（`docs/design/context-memory-v2.md` + `spec/context-profile/README.md`）
/// 的规则测试：段顺序（state_first）、预算、以及「超限先压主题、再减历史」两条裁剪次序。
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

    /// 一份「什么都有」的固定段输入：每段一个可搜索的标记。
    private func fullInput(
        history: [WireMessage] = [],
        contextWindowTokens: Int = ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS,
        maxOutputTokens: Int = ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS,
        practicalChars: Int = ExperienceContract.PRACTICAL_HISTORY_CHARS
    ) -> ContextV2Input {
        ContextV2Input(
            currentState: "STATE_TIME_AAA",
            guidance: "GUIDANCE_GGG",
            persona: "PERSONA_BBB",
            tools: "TOOLS_CCC",
            antiAI: "ANTI_AI_DDD",
            longTermMemory: "LONG_MEMORY_EEE",
            shortTermMemory: "SHORT_MEMORY_FFF",
            topics: [MemoryContract.TopicSegment(timeLabel: "2026-11-18 晚", summary: "TOPIC_HHH")],
            languageDirective: "LANGUAGE_III",
            boundary: "BOUNDARY_JJJ",
            history: history.isEmpty ? [message("HISTORY_KKK")] : history,
            contextWindowTokens: contextWindowTokens,
            maxOutputTokens: maxOutputTokens,
            practicalChars: practicalChars
        )
    }

    // MARK: - 顺序（state_first）

    /// 0.15 的固定顺序：状态 → 引导 → 人设 → 工具 → 反AI → 记忆 → 语言 → 边界。
    ///
    /// 这条顺序是 Bench v2 搜出来的（`eval/reports/context-search-combine-*.md`），
    /// 也是 `PromptBuilder.PromptSection.stateFirstOrder` 那一份；两处必须一致。
    func testSegmentsAppearInStateFirstOrder() {
        let output = ContextAssemblerV2.assemble(fullInput())
        let system = output.system
        let markers = ["STATE_TIME_AAA", "GUIDANCE_GGG", "PERSONA_BBB", "TOOLS_CCC",
                       "ANTI_AI_DDD", "LONG_MEMORY_EEE", "SHORT_MEMORY_FFF", "TOPIC_HHH",
                       "LANGUAGE_III", "BOUNDARY_JJJ"]
        let positions = markers.compactMap { system.range(of: $0)?.lowerBound }
        XCTAssertEqual(positions.count, markers.count, "固定段必须全部出现：\(system)")
        XCTAssertEqual(positions, positions.sorted(), "固定段顺序不能乱：\(system)")
        XCTAssertEqual(output.history.map(\.content), ["HISTORY_KKK"])
    }

    /// 状态与引导都在人设**前面**（0.15 架构改动就这一条）。
    func testStateAndGuidanceComeBeforePersona() {
        let system = ContextAssemblerV2.assemble(fullInput()).system
        let state = try? XCTUnwrap(system.range(of: "STATE_TIME_AAA"))
        let guidance = try? XCTUnwrap(system.range(of: "GUIDANCE_GGG"))
        let persona = try? XCTUnwrap(system.range(of: "PERSONA_BBB"))
        guard let state, let guidance, let persona else {
            return XCTFail("固定段缺失：\(system)")
        }
        XCTAssertLessThan(state.lowerBound, persona.lowerBound, "状态在人设之前")
        XCTAssertLessThan(guidance.lowerBound, persona.lowerBound, "引导也在人设之前")
        XCTAssertLessThan(state.lowerBound, guidance.lowerBound, "引导在状态之后")
    }

    /// 装配器的顺序必须就是 `PromptSection.stateFirstOrder`，不许各写一份。
    func testAssemblerOrderMatchesPromptSectionOrder() {
        let system = ContextAssemblerV2.assemble(fullInput()).system
        let expected = PromptSection.stateFirstOrder
        XCTAssertEqual(expected, [.state, .guidance, .persona, .tools, .antiAI,
                                  .memory, .language, .boundary])

        let markers: [PromptSection: String] = [
            .state: "STATE_TIME_AAA", .guidance: "GUIDANCE_GGG", .persona: "PERSONA_BBB",
            .tools: "TOOLS_CCC", .antiAI: "ANTI_AI_DDD", .memory: "LONG_MEMORY_EEE",
            .language: "LANGUAGE_III", .boundary: "BOUNDARY_JJJ"
        ]
        let positions = expected.compactMap { section in
            markers[section].flatMap { system.range(of: $0)?.lowerBound }
        }
        XCTAssertEqual(positions.count, expected.count)
        XCTAssertEqual(positions, positions.sorted(), "装配顺序与枚举顺序分叉了：\(system)")
    }

    func testSectionTitlesComeFromSharedContract() {
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            persona: "人设",
            longTermMemory: "长期",
            shortTermMemory: "短期",
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
            persona: "人设",
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

    /// 引导段**不参与状态截断**：它是这一轮的行为指令，截一半比没有更糟。
    func testGuidanceIsNeverClipped() {
        let longState = String(repeating: "状", count: 5_000)
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            currentState: longState,
            guidance: PromptGuidance.section(.defensive),
            persona: "人设",
            history: [message("hi")]
        ))
        XCTAssertTrue(output.system.contains(PromptGuidance.section(.defensive)),
                      "引导段被截断了：\(output.system.suffix(200))")
        XCTAssertLessThan(output.system.count, longState.count, "状态段本身应该被截短")
    }

    func testTopicsAreTruncatedBeforeHistoryIsCut() {
        // 主题每段都超 120 字：先把每段压到 120，而不是先砍历史。
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            persona: "人设",
            topics: topics(3, chars: 400),
            history: (0..<20).map { message("历史\($0)") }
        ))
        XCTAssertTrue(output.topicsTruncated, "每段超 120 字必须逐段截断")
        XCTAssertGreaterThan(output.history.count, 10, "压主题之后历史不该被砍光")
    }

    func testTopicCountIsLimitedToTwelve() {
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            persona: "人设",
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
        // 人设自己就超过了预算：超出部分从历史预算里扣，总量仍然守住硬约束。
        let history = (0..<30).map { message(String(repeating: "史", count: 100) + "\($0)") }
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            persona: String(repeating: "设", count: 3000),
            history: history,
            contextWindowTokens: 20_000,
            maxOutputTokens: 4_000,
            practicalChars: 4_000
        ))
        XCTAssertTrue(output.fixedSegmentsOverBudget)
        XCTAssertLessThan(output.history.count, history.count, "固定段超了，历史必须变少")
        XCTAssertEqual(output.history.last?.content, history.last?.content, "最新一条必须留下")
        let total = output.system.count + output.history.reduce(0) { $0 + $1.content.count }
        XCTAssertLessThanOrEqual(total, 4_000 + 80, "总字符要守住硬预算（允许分隔符的少量余量）")
    }

    func testHistoryKeepsNewestMessagesWithinBudget() {
        let history = (0..<40).map { message(String(repeating: "字", count: 300) + "\($0)") }
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            persona: "人设",
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
            currentState: "状态",
            guidance: "引导",
            persona: "人设",
            tools: "工具",
            antiAI: "反AI",
            longTermMemory: "长期",
            shortTermMemory: "短期",
            topics: [MemoryContract.TopicSegment(timeLabel: "x", summary: "主题")],
            languageDirective: "语言",
            boundary: "边界",
            history: [message("hi")],
            contextWindowTokens: 0,
            maxOutputTokens: 0,
            practicalChars: 0
        ))
        // 预算为 0 时：状态与引导被裁掉，但**规则与人设**（不可裁的段）仍然在。
        XCTAssertTrue(output.system.contains("人设"))
        XCTAssertTrue(output.system.contains("边界"))
        XCTAssertTrue(output.history.isEmpty)
        XCTAssertEqual(output.plan.historyCharBudget, 0)
        XCTAssertEqual(output.plan.validate(), [])
    }

    func testPracticalPlanUsesProductCapWhenWindowHuge() {
        let output = ContextAssemblerV2.assemble(ContextV2Input(persona: "人设"))
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
            persona: "人设",
            history: [message("hi")]
        ))
        XCTAssertFalse(output.system.contains(MemoryContract.Injection.LONG_TERM_TITLE))
        XCTAssertFalse(output.system.contains(MemoryContract.Injection.SHORT_TERM_TITLE))
        XCTAssertFalse(output.system.contains(MemoryContract.Injection.TOPIC_TITLE))
    }

    // MARK: - 从 PromptBuilder 的段字典直接装

    /// `ChatService` 走这条：`PromptBuilder.sections` 出段，装配器只管顺序与预算。
    func testInputCanBeBuiltFromPromptBuilderSections() {
        let sections = Dictionary(
            uniqueKeysWithValues: PromptBuilder.sections(SystemPromptInput(
                personaText: "# 你是谁\n你叫林深。",
                stateBlock: PromptBuilder.stateSection(now: 1_795_000_000_000, activity: nil),
                guidanceBlock: PromptGuidance.section(.presentOnly),
                toolsCapability: "TOOLS_ZZZ"
            )).map { ($0.section, $0.text) }
        )
        let output = ContextAssemblerV2.assemble(ContextV2Input(
            sections: sections, history: [message("在吗")]
        ))
        let system = output.system
        let state = try? XCTUnwrap(system.range(of: "# 你此刻的状态"))
        let guidance = try? XCTUnwrap(system.range(of: "# 引导"))
        let identity = try? XCTUnwrap(system.range(of: "# 你是谁"))
        guard let state, let guidance, let identity else {
            return XCTFail("缺段：\(system)")
        }
        XCTAssertLessThan(state.lowerBound, guidance.lowerBound)
        XCTAssertLessThan(guidance.lowerBound, identity.lowerBound, "引导在话风（人设段）之前")
        XCTAssertTrue(system.contains("TOOLS_ZZZ"))
    }
}
