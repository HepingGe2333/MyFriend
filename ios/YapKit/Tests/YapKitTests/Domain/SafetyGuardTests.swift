import Foundation
import XCTest
@testable import YapKit

/// 0.15 危机安全兜底（`SafetyGuard`，纯逻辑）。
///
/// 钉住三件事：什么算危机、什么算「回复里已经有安全锚点」、追加的那两句长什么样。
/// 真正「回复落库前追加」在 `ChatReliabilityTests` 里（端到端）。
final class SafetyGuardTests: XCTestCase {

    // MARK: - 危机识别

    func testCrisisPhrasesFromProductSpecAreDetected() {
        for phrase in ["不想活", "自杀", "安眠药", "割腕", "消失比较好"] {
            XCTAssertTrue(
                SafetyGuard.isCrisis("我今天\(phrase)了"),
                "产品要求必须接住的说法：\(phrase)"
            )
        }
        // 同义的明确说法也认。
        for phrase in ["轻生", "活不下去", "结束生命", "自残"] {
            XCTAssertTrue(SafetyGuard.isCrisis(phrase), "同义危机说法：\(phrase)")
        }
    }

    func testEverydayMessagesAreNotCrisis() {
        XCTAssertFalse(SafetyGuard.isCrisis("今天好累，先睡了"))
        XCTAssertFalse(SafetyGuard.isCrisis("那你早点休息"))
        XCTAssertFalse(SafetyGuard.isCrisis(""))
        XCTAssertFalse(SafetyGuard.isCrisis("   "))
        // 「笑死」这类日常夸张不是危机（我们的规则里没有它，这里钉住别误加）。
        XCTAssertFalse(SafetyGuard.isCrisis("笑死我了"))
    }

    func testEnglishCrisisMessagesAreDetectedToo() {
        XCTAssertTrue(SafetyGuard.isCrisis("I want to kill myself"))
        XCTAssertTrue(SafetyGuard.isCrisis("I've been thinking about suicide"))
        XCTAssertFalse(SafetyGuard.isCrisis("I'm just tired tonight"))
    }

    // MARK: - 安全锚点

    func testAnchorsFromProductSpecAreRecognized() {
        for anchor in ["我在", "别一个人", "身边有人", "120", "110", "医院", "急诊", "求助", "家人", "朋友"] {
            XCTAssertTrue(
                SafetyGuard.hasSafetyAnchor("我会陪着你，" + anchor + "。"),
                "已有锚点就不该再追加：\(anchor)"
            )
        }
    }

    func testEnglishAnchorIsRecognized() {
        XCTAssertTrue(SafetyGuard.hasSafetyAnchor("I'm here with you, you're not alone."))
        XCTAssertTrue(SafetyGuard.hasSafetyAnchor("Please call 120 or go to the hospital."))
    }

    func testReplyWithoutAnchorHasNone() {
        XCTAssertFalse(SafetyGuard.hasSafetyAnchor("嗯。那你早点睡。"))
        XCTAssertFalse(SafetyGuard.hasSafetyAnchor(""))
    }

    // MARK: - 需不需要补

    func testNeedsSafetyNetOnlyWhenCrisisAndNoAnchor() {
        XCTAssertTrue(SafetyGuard.needsSafetyNet(
            userMessage: "我真的不想活了", reply: "嗯。那你早点睡。"
        ))
        XCTAssertFalse(SafetyGuard.needsSafetyNet(
            userMessage: "我真的不想活了", reply: "我在，别一个人待着。"
        ), "回复里已经有安全锚点就不再追加")
        XCTAssertFalse(SafetyGuard.needsSafetyNet(
            userMessage: "今天好累", reply: "嗯。那你早点睡。"
        ), "用户没提危机就不打扰")
    }

    // MARK: - 追加

    func testAppendKeepsOriginalReplyAndAddsOneOrTwoSentences() {
        let reply = "嗯。那你早点睡。"
        let combined = SafetyGuard.appendingSafetyNetIfNeeded(
            reply: reply, userMessage: "我真的不想活了"
        )
        XCTAssertTrue(combined.hasPrefix(reply), "只在末尾追加，不改写模型说的话")
        XCTAssertTrue(combined.contains("\n"), "另起一行，不和人设的话黏成一句")
        XCTAssertFalse(combined.contains("\n\n"), "不空行分段：它属于这一条回复，不是另发一条")
        XCTAssertTrue(combined.contains("120") || combined.contains("110"))
        let appended = String(combined.dropFirst(reply.count))
        let sentences = appended.filter { "。！？".contains($0) }.count
        XCTAssertLessThanOrEqual(sentences, 2, "最多两句：不喊口号、不说教")
        XCTAssertGreaterThanOrEqual(sentences, 1)
    }

    func testAppendIsIdempotent() {
        let once = SafetyGuard.appendingSafetyNetIfNeeded(
            reply: "嗯。", userMessage: "我不想活了"
        )
        let twice = SafetyGuard.appendingSafetyNetIfNeeded(
            reply: once, userMessage: "我不想活了"
        )
        XCTAssertEqual(once, twice, "补过一次之后回复里已经有锚点，不该补第二遍")
    }

    func testNoAppendWhenReplyAlreadyAnchored() {
        let reply = "我在呢。要不要先给家人打个电话？"
        XCTAssertEqual(
            reply,
            SafetyGuard.appendingSafetyNetIfNeeded(reply: reply, userMessage: "我不想活了")
        )
    }

    func testEmptyReplyBecomesSafetyNet() {
        let combined = SafetyGuard.appendingSafetyNetIfNeeded(
            reply: "   ", userMessage: "我不想活了"
        )
        XCTAssertEqual(SafetyGuard.safetyNetText(language: .zh), combined)
    }

    func testEnglishPersonaGetsEnglishSafetyNet() {
        let combined = SafetyGuard.appendingSafetyNetIfNeeded(
            reply: "Go to sleep.", userMessage: "I want to kill myself", language: .en
        )
        XCTAssertTrue(combined.contains("is anyone with you"))
        XCTAssertTrue(combined.hasPrefix("Go to sleep."))
    }
}
