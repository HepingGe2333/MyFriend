import Foundation
import XCTest

/// 「消失的设置」守卫（§九）。
///
/// 1.9 删掉了一整批用户能调的参数：记忆开关、读消息的节奏、额度与单价、每天主动次数，
/// 以及已经没人再用的 Harness 三列。产品要求是「设置页看不到」，工程要求是「代码里也搜不到」——
/// 不然半年后总有人把它加回来，而且往往只加一半（界面有、行为没有）。
///
/// 这条测试直接扫**生产源码**（不扫测试与文档）：
/// - 扫到任何一个退役标识符就红；
/// - 唯一允许出现的地方是 `Data/Schema.swift`：迁移必须写出旧列名才能把列删掉。
///
/// 为什么不写成「正则扫整个仓库」：测试目录里本来就要写这些名字（守卫本身要列出它们），
/// 把它们也算进来只会让守卫变成噪音，然后被删掉。
final class SettingsRetirementTests: XCTestCase {

    /// 1.9 退役的全部标识符。
    private static let retired = [
        // Harness（原生模式）
        "HarnessMode", "harnessMode", "harnessUrl", "harnessSyncPath",
        // 额度 / 预算闸
        "ApiBudget", "guardDecision", "budgetVerdict", "monthlyBudgetMicroUsd",
        "overBudgetChatOnly", "inputPriceUsdPerMillion", "outputPriceUsdPerMillion",
        "PriceOverride",
        // 用户能调的参数
        "replyPace", "ReplyPace", "proactiveDailyLimit",
        "memoryConsolidationEnabled", "webSearchScoringEnabled",
        "memoryEnabled", "autoMemoryEnabled"
    ]

    /// 唯一允许「写出旧名字」的文件：迁移得知道要删哪些列。
    private static let allowlist = ["Schema.swift"]

    func testRetiredSettingsAreGoneFromProductionSources() throws {
        let root = try repoRoot()
        var offenders: [String] = []
        var scanned = 0

        for directory in ["ios/MyFriendKit/Sources", "ios/MyFriend"] {
            let base = root.appendingPathComponent(directory)
            guard FileManager.default.fileExists(atPath: base.path) else { continue }
            for url in try swiftFiles(under: base) {
                if Self.allowlist.contains(url.lastPathComponent) { continue }
                scanned += 1
                let text = try String(contentsOf: url, encoding: .utf8)
                for token in Self.retired where text.contains(token) {
                    offenders.append("\(url.lastPathComponent): \(token)")
                }
            }
        }

        XCTAssertGreaterThan(scanned, 50, "扫描范围看起来不对：生产源码应当有上百个文件")
        XCTAssertTrue(offenders.isEmpty, "这些退役的设置在代码里还留着：\n" + offenders.joined(separator: "\n"))
    }

    /// 设置页不该再有那几节，也不该再有任何「能填数字」的输入框（§七）。
    ///
    /// 这里查的是**控件**而不是文案：界面里保留一句「这一版不再有记忆开关」是好事，
    /// 但一个能点的开关、一个能填金额的输入框，都是这一版明确删掉的东西。
    func testSettingsScreenHasNoRetiredControls() throws {
        let root = try repoRoot()
        let settings = try String(
            contentsOf: root.appendingPathComponent("ios/MyFriend/Views/Settings/SettingsScreen.swift"),
            encoding: .utf8
        )
        // 记忆那一整节没了。
        XCTAssertFalse(settings.contains("MFSectionTitle(text: \"记忆\")"),
                       "设置页不该再有「记忆」这一节")
        // 剩下的开关只有「界面动画 / 触感反馈 / 常驻思考」这三个，记忆类的开关不该再出现。
        for retiredRow in ["title: \"长期记忆\"", "title: \"自动抽取\"", "title: \"每天整理一次\"",
                           "MFRowTitle(\"读消息的节奏\"", "MFRowTitle(\"每月额度"] {
            XCTAssertFalse(settings.contains(retiredRow), "设置页还有已删除的那一行：\(retiredRow)")
        }
        // 唯一那个「能填数字」的输入框（每月额度）也删了。
        XCTAssertFalse(settings.contains("Stepper("), "「每天最多主动几次」的 Stepper 应当删除")
        XCTAssertFalse(settings.contains("TextField("), "设置页不该再有输入框（额度已经删掉）")

        let usage = try String(
            contentsOf: root.appendingPathComponent("ios/MyFriend/Views/Settings/SettingsUsageSection.swift"),
            encoding: .utf8
        )
        // 用量页只统计 token：没有输入框、没有金额、没有「花完以后」。
        XCTAssertFalse(usage.contains("TextField("))
        XCTAssertFalse(usage.contains("formatUsd"))
        XCTAssertFalse(usage.contains("budget"))
        XCTAssertTrue(usage.contains("token"), "用量页应当只讲 token")
    }

    // MARK: - 文件系统

    private func repoRoot() throws -> URL {
        let fixtures = try XCTUnwrap(
            RepoLayout.backupFixtureDirectory(),
            "找不到 fixtures/backup，无法定位仓库根"
        )
        // fixtures/backup → fixtures → 仓库根
        return fixtures.deletingLastPathComponent().deletingLastPathComponent()
    }

    private func swiftFiles(under directory: URL) throws -> [URL] {
        let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        )
        var result: [URL] = []
        while let url = enumerator?.nextObject() as? URL {
            guard url.pathExtension == "swift" else { continue }
            result.append(url)
        }
        return result
    }
}
