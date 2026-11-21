import Foundation
import XCTest
@testable import YapKit

/// 0.15 高置信自动记忆（纯规则）。
///
/// 只钉规则本身：哪些话算「高置信事实」、记忆正文取哪一段、重要度/标签是多少、
/// 什么时候算重复。真正「什么时候写进库」在 `ChatReliabilityTests` 里。
///
/// 规则与 Android `domain/AutoMemory.kt` 同一套语义（记忆会随备份跨端同步，
/// 两端写出来的 tags / importance 必须一致）。
final class HighConfidenceMemoryTests: XCTestCase {

    // MARK: - 命中

    func testAllergyIsHighConfidenceWithImportanceFive() throws {
        let hit = try XCTUnwrap(HighConfidenceMemory.detect("我对花生过敏，别给我点带坚果的菜"))
        XCTAssertEqual(.allergy, hit.rule)
        XCTAssertEqual(5, hit.importance, "过敏漏一次可能真出事：给最高重要度")
        XCTAssertEqual(["auto", "allergy"], hit.tags, "与 Android 一致的落库标签")
        XCTAssertEqual("我对花生过敏，别给我点带坚果的菜", hit.content, "整句都是有用的信息，不按逗号切")
    }

    func testDietRestrictionIsCapturedAsAllergyKind() throws {
        // 忌口 / 不能吃与过敏同一个 kind（Android `AutoMemory.Kind.ALLERGY`）：都是「吃了会出事」。
        let hit = try XCTUnwrap(HighConfidenceMemory.detect("我不能吃辣，一吃就胃疼"))
        XCTAssertEqual(.allergy, hit.rule)
        XCTAssertEqual(5, hit.importance)
        XCTAssertTrue(hit.content.contains("不能吃辣"))
    }

    func testBirthdayAnniversaryAreImportanceFiveAndPromiseFour() throws {
        let birthday = try XCTUnwrap(HighConfidenceMemory.detect("我生日是三月十四号"))
        XCTAssertEqual(.birthday, birthday.rule)
        XCTAssertEqual(5, birthday.importance)

        let anniversary = try XCTUnwrap(HighConfidenceMemory.detect("我们的纪念日是六月一号"))
        XCTAssertEqual(.anniversary, anniversary.rule)
        XCTAssertEqual(5, anniversary.importance)

        let promise = try XCTUnwrap(HighConfidenceMemory.detect("我们说好周六一起去看海"))
        XCTAssertEqual(.promise, promise.rule)
        XCTAssertEqual(4, promise.importance)
        XCTAssertEqual(["auto", "promise"], promise.tags)
    }

    func testEarlierRuleWinsWhenMessageHasTwoFacts() throws {
        // 过敏事关安全，优先级高于生日：一条消息只记一件事时先记要紧的那件。
        let hit = try XCTUnwrap(HighConfidenceMemory.detect("我生日那天对芒果过敏了"))
        XCTAssertEqual(.allergy, hit.rule)
    }

    func testContentIsTheClauseAroundTheMatch() throws {
        let hit = try XCTUnwrap(HighConfidenceMemory.detect("今天好累。我对海鲜过敏，别点龙虾。晚安"))
        XCTAssertEqual("我对海鲜过敏，别点龙虾", hit.content, "只取命中的那一小句（不含句末标点）")
    }

    func testLongClauseIsTruncated() throws {
        let long = "我对花生过敏" + String(repeating: "，真的别点花生", count: 30)
        let hit = try XCTUnwrap(HighConfidenceMemory.detect(long))
        XCTAssertLessThanOrEqual(hit.content.count, HighConfidenceMemory.maxFragmentChars)
        XCTAssertTrue(hit.content.hasPrefix("我对花生过敏"))
    }

    // MARK: - 不命中

    func testNegatedFactsAreNotCaptured() {
        XCTAssertNil(HighConfidenceMemory.detect("我不过敏，什么都能吃"))
        XCTAssertNil(HighConfidenceMemory.detect("我不忌口"))
        XCTAssertNil(HighConfidenceMemory.detect("我没有过敏"))
        XCTAssertNil(HighConfidenceMemory.detect("没有纪念日这回事"))
    }

    func testThirdPersonFactsAreNotCaptured() {
        // 「我朋友对花生过敏」不是「我」的事：自动记忆只认第一人称（与 Android 同一套规则）。
        XCTAssertNil(HighConfidenceMemory.detect("我朋友对花生过敏"))
        XCTAssertNil(HighConfidenceMemory.detect("我妈不能吃辣"))
        XCTAssertNil(HighConfidenceMemory.detect("他生日是三月十四号"))
    }

    func testEverydayChatIsNotCaptured() {
        XCTAssertNil(HighConfidenceMemory.detect("今天好累，先睡了"))
        XCTAssertNil(HighConfidenceMemory.detect(""))
        XCTAssertNil(HighConfidenceMemory.detect("   "))
        XCTAssertNil(HighConfidenceMemory.detect("在吗"))
        // 只说「喜欢 / 讨厌」属于口味偏好，不在高置信规则里（那条走日常抽取）。
        XCTAssertNil(HighConfidenceMemory.detect("我喜欢吃草莓"))
    }

    func testQuestionsAreNotCaptured() {
        // 「我生日是哪天」不是记忆；写进去只会占掉召回预算。
        XCTAssertNil(HighConfidenceMemory.detect("我生日是哪天来着"))
        XCTAssertNil(HighConfidenceMemory.detect("我对什么过敏来着"))
    }

    // MARK: - 去重

    func testSameFactIsDuplicateAcrossPunctuationAndWhitespace() {
        XCTAssertTrue(HighConfidenceMemory.isDuplicate(
            of: "我对燕麦过敏",
            among: ["我 对燕麦过敏。"]
        ))
    }

    func testSameFactRepeatedIsDuplicate() {
        let content = "我对花生过敏"
        XCTAssertTrue(HighConfidenceMemory.isDuplicate(of: content, among: ["我对花生过敏"]))
        XCTAssertTrue(HighConfidenceMemory.isDuplicate(
            of: content, among: ["他生日是三月十四号", "我对花生过敏，别点花生"]
        ))
    }

    func testDifferentFactsAreNotDuplicates() {
        XCTAssertFalse(HighConfidenceMemory.isDuplicate(of: "我对花生过敏", among: ["我生日是三月十四号"]))
        XCTAssertFalse(HighConfidenceMemory.isDuplicate(of: "我对花生过敏", among: []))
        // 太短的东西不参与包含判定，免得「生日」两个字把所有记忆都算成同一条。
        XCTAssertFalse(HighConfidenceMemory.isDuplicate(of: "生日", among: ["生日"]))
    }

    // MARK: - 规则内容与提示词

    func testRulesCoverTheProductList() {
        let allPatterns = HighConfidenceMemory.rules.map(\.pattern).joined(separator: "|")
        for required in ["过敏", "不能吃", "忌口", "生日", "纪念日", "重要约定"] {
            XCTAssertTrue(allPatterns.contains(required), "产品要求的高置信模式必须被认出来：\(required)")
        }
    }

    func testRememberPromptStillSaysTextAloneDoesNotCount() throws {
        let spec = try XCTUnwrap(ToolCatalog.byName(ToolCatalog.remember))
        XCTAssertTrue(spec.description.contains("只在正文里说"), "「只在正文说记住了不算」的提示要保留")
        XCTAssertTrue(spec.description.contains("不算记住"))
        let capability = ToolCatalog.capabilityPrompt()
        XCTAssertTrue(capability.contains("只在正文里说「记住了」不算"),
                      "能力段里那句提示也要保留（模型两处都看）")
    }
}
