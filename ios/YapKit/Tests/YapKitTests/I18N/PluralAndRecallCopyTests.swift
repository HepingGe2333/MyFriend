import Foundation
import XCTest
@testable import YapKit

/// i18n 残留：
/// 1. `Recall.chatHit` 的「对方说：/ 我说过：」在工具详情里不再露中文（提示词版本保持中文）；
/// 2. 英文单复数：0 / 1 / 2 的说法都要对（`.one` 变体在 SPM 里回落中文 key，
///    所以中文读法可以逐字断言；英文由资源守卫保证有 `.one`）；
/// 3. Kit 摘要不再是一整句塞四个 `%d`（拆成按计数的小句）。
final class PluralAndRecallCopyTests: XCTestCase {

    // MARK: - Recall 前缀

    func testChatHitPromptPrefixStaysChinese() {
        XCTAssertEqual(Recall.chatHitPrefix("USER"), "对方说：")
        XCTAssertEqual(Recall.chatHitPrefix("ASSISTANT"), "我说过：")
        XCTAssertEqual(Recall.chatHitPrefix(nil), "")
        let hit = Recall.chatHit(text: "我下个月要去哈尔滨", sessionTitle: "旅行", at: 1, role: "USER")
        XCTAssertTrue(hit.text.hasPrefix("对方说："), "提示词里仍是中文（rendered 也用它）")
    }

    func testDisplayTextSwapsPrefixButKeepsBody() {
        let userHit = Recall.chatHit(text: "我下个月要去哈尔滨", sessionTitle: "旅行", at: 1, role: "USER")
        let displayed = Recall.displayChatText(userHit)
        XCTAssertEqual(displayed, "对方说：我下个月要去哈尔滨")
        XCTAssertEqual(Recall.displayChatHitPrefix("USER"), "对方说：")
        XCTAssertEqual(Recall.displayChatHitPrefix("ASSISTANT"), "我说过：")

        let memoryHit = RecallHit(kind: .memory, text: "对方说：这不是聊天命中", at: 1)
        XCTAssertEqual(Recall.displayChatText(memoryHit), "对方说：这不是聊天命中",
                       "只有聊天命中才换前缀；记忆正文原样显示")
    }

    // MARK: - 单复数

    func testPluralTextHandlesZeroOneTwo() {
        XCTAssertEqual(PluralText.format("%d 个人格", 0), "0 个人格")
        XCTAssertEqual(PluralText.format("%d 个人格", 1), "1 个人格")
        XCTAssertEqual(PluralText.format("%d 个人格", 2), "2 个人格")
        XCTAssertEqual(PluralText.format("%d 句话", 1), "1 句话")
        // 计数不是第一个占位符的句子也能选对模板（`%@ 里有 %d 条…`）。
        let template = PluralText.template("%@ 里有 %d 条读不出来，已跳过", 1)
        XCTAssertTrue(template.contains("%d"), "模板仍要留下占位符")
    }

    func testBackupPreviewNoLongerSaysOnePersonas() {
        let single = BackupPreview(
            personas: 1, memories: 1, sessions: 1, messages: 1, rules: 1,
            presets: 0, apiConfigs: 0, exportedAt: 0, appVersion: "0.10.1"
        )
        XCTAssertEqual(single.describe(), "1 个人格 · 1 条记忆 · 1 段对话 · 1 句话 · 1 条主动节奏")
        let plural = BackupPreview(
            personas: 2, memories: 3, sessions: 1, messages: 60, rules: 0,
            presets: 0, apiConfigs: 0, exportedAt: 0, appVersion: "0.10.1"
        )
        XCTAssertEqual(plural.describe(), "2 个人格 · 3 条记忆 · 1 段对话 · 60 句话")
        let empty = BackupPreview(
            personas: 0, memories: 0, sessions: 0, messages: 0, rules: 0,
            presets: 0, apiConfigs: 0, exportedAt: 0, appVersion: "0.10.1"
        )
        XCTAssertEqual(empty.describe(), "这份备份里什么也没有")
        XCTAssertEqual(empty.exportedAtText(), "带走的时间不详")
    }

    func testClearSummaryUsesPerCountSentences() {
        let chats = ClearSummary(messages: 1, sessions: 1, memories: 1)
        XCTAssertEqual(chats.describeChats(), "所有聊天与记忆已清空（1 段对话 · 1 句话 · 1 条记忆）")
        let all = ClearSummary(messages: 120, sessions: 2, memories: 1, personas: 1, apiConfigs: 1)
        XCTAssertEqual(
            all.describeAll(),
            "所有本地数据已删除（1 个人格 · 1 条记忆 · 2 段对话 · 120 句话 · 1 个 API 配置）"
        )
    }

    // MARK: - 其它 Kit 文案

    func testUsageKindLabelsAreLocalizedKeys() {
        // 这些是「用量」页直接显示的标签；没本地化时英文界面会整列中文。
        XCTAssertEqual(UsageKind.label(UsageKind.chat), "聊天")
        XCTAssertEqual(UsageKind.label(UsageKind.scoring), "判断值不值得记")
        XCTAssertEqual(UsageKind.label("SOMETHING_ELSE"), "其他")
    }

    func testPresenceAndRecallRelativeTimeSingularReadsRight() {
        let now: Int64 = 1_794_500_000_000
        XCTAssertEqual(PresenceRules.relativeTime(ms: now - 60_000, now: now), "1 分钟前")
        XCTAssertEqual(Recall.displayDateLabel(at: now - 86_400_000, now: now), "昨天")
        XCTAssertEqual(Recall.displayDateLabel(at: now - 3 * 86_400_000, now: now), "3 天前")
    }
}
