import Foundation
import XCTest
@testable import YapKit

/// 「这次先不记」的六种原因（0.10.2 i18n 收尾）。
///
/// 它们会经 `MemoryDecision.reject(reason:)` 进 `remember` 工具的动作卡片详情，
/// 所以是**用户可见文案**：六条都得有资源、英文不许含中文。
/// 同时又不能把提示词翻了——原因嵌在 `ToolResult.contentForModel` 里回灌模型，
/// 那里必须还是中文原文（`RejectReason.promptText`）。
final class MemoryScreeningLocalizationTests: XCTestCase {

    /// 中文原文（= 资源 key = 提示词里的说法）→ 期望的英文。
    private static let expectedEnglish: [(chinese: String, english: String)] = [
        ("内容为空", "Empty content"),
        ("内容太短，没有可长期复用的信息", "Too short to be worth keeping"),
        ("内容太长，不像一条记忆", "Too long to be a single memory"),
        ("模型自己也不确定，先不留", "The model wasn't sure, so it wasn't kept"),
        ("只是寒暄，没有长期价值", "Just small talk, no lasting value"),
        ("一次性的情绪，不必长期记住", "A one-off feeling, not worth keeping")
    ]

    private func candidate(
        _ content: String,
        importance: Int = 3,
        confidence: Float = 0.8
    ) -> MemoryCandidate {
        MemoryCandidate(
            content: content,
            drawer: .user,
            importance: importance,
            sourceQuote: content,
            categoryName: "FACT",
            confidence: confidence
        )
    }

    // MARK: - 中文侧（无资源时回落 key = 中文原文）

    func testRejectReasonsStayChineseWithoutResources() {
        XCTAssertEqual(MemoryScreening.RejectReason.allCases.count, 6, "拒绝原因应该正好六条")
        for reason in MemoryScreening.RejectReason.allCases {
            XCTAssertEqual(reason.localizedText, reason.rawValue, "\(reason) 在 SPM 里应回落成中文原文")
            XCTAssertEqual(reason.promptText, reason.rawValue, "提示词版本永远中文")
        }
    }

    /// 六条规则各自命中时，`rejectReason` 返回的仍是中文（界面语言由资源决定）。
    func testRejectReasonPickedForEachRule() {
        let cases: [(MemoryScreening.RejectReason, MemoryCandidate, String)] = [
            (.emptyContent, candidate(""), ""),
            (.tooShort, candidate("在吗"), "在吗"),
            (.tooLong, candidate(String(repeating: "长", count: 301)), String(repeating: "长", count: 301)),
            (.lowConfidence, candidate("我下周三要去哈尔滨出差", confidence: 0.1), "我下周三要去哈尔滨出差"),
            (.smallTalk, candidate("你好你好你好你好你好"), "你好你好你好你好你好"),
            (.oneOffEmotion, candidate("今天真的好累啊，想早点睡", importance: 1), "今天真的好累啊，想早点睡")
        ]
        for (expected, candidate, content) in cases {
            XCTAssertEqual(
                MemoryScreening.rejectReason(candidate, content: content), expected.localizedText,
                "\(expected) 这条规则没有按预期命中"
            )
        }
        // 正常的一条记忆不该被拒。
        let fine = candidate("对方喜欢在夜里散步，讨厌被追问")
        XCTAssertNil(
            MemoryScreening.rejectReason(fine, content: "对方喜欢在夜里散步，讨厌被追问"),
            "正常记忆不该被拒"
        )
    }

    /// 界面译文能反查回中文原文：`contentForModel` 里嵌着这句话，英文不许进提示词。
    func testPromptTextRoundTripsToChinese() {
        for reason in MemoryScreening.RejectReason.allCases {
            XCTAssertEqual(
                MemoryScreening.RejectReason.promptText(forLocalized: reason.localizedText),
                reason.rawValue,
                "\(reason) 的界面译文没能反查回中文原文"
            )
        }
        // 认不出的说法原样返回，别把空字符串塞进提示词。
        XCTAssertEqual(MemoryScreening.RejectReason.promptText(forLocalized: "Something else"), "Something else")
    }

    // MARK: - 英文侧（资源）

    func testEveryRejectReasonHasEnglishCopy() throws {
        let english = try I18NTestResources.map("en")
        let chinese = try I18NTestResources.map("zh-Hans")
        for row in Self.expectedEnglish {
            XCTAssertNotNil(chinese[row.chinese], "zh-Hans 里缺「\(row.chinese)」")
            guard let value = english[row.chinese] else {
                XCTFail("en.lproj 里缺「\(row.chinese)」")
                continue
            }
            XCTAssertEqual(value, row.english, "「\(row.chinese)」的英文值与定稿不一致")
            XCTAssertFalse(
                I18NTestResources.containsCJK(value),
                "「\(row.chinese)」的英文值里还有中文：\(value)"
            )
        }
    }

    /// `MemoryDecision.reject` 带的是界面语言的说法；`ToolExecutor` 靠它填详情，
    /// 而中文原文只用于回灌模型。这里钉住「同一个决定在两种环境里各取所需」。
    func testDecisionCarriesLocalizedReasonWhilePromptStaysChinese() {
        let decision = MemoryScreening.decide(
            candidate("在吗"),
            existing: [],
            echoes: [],
            now: 1_794_500_000_000
        )
        guard case .reject(let reason) = decision else {
            return XCTFail("短寒暄应该被判为 reject，实际是 \(decision)")
        }
        XCTAssertEqual(reason, "内容太短，没有可长期复用的信息")
        XCTAssertEqual(
            MemoryScreening.RejectReason.promptText(forLocalized: reason),
            "内容太短，没有可长期复用的信息"
        )
    }
}
