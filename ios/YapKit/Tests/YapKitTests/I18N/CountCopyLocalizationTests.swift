import Foundation
import XCTest
@testable import YapKit

/// 英文单复数收尾（0.10.2 审计点名的备份 / 清空 / 检索计数 + 顺带扫出来的几处）。
///
/// 做法与 `PluralAndRecallCopyTests` 一致：中文只有一种说法，可以逐字断言；
/// 英文在 SPM 里没有资源包，所以**直接解析 `en.lproj/Localizable.strings`**，
/// 断言三件事：
///
/// 1. 该有 `.one` 的 key 确实有（`Lcount` / `PluralText` 才会去挑它）；
/// 2. 英文值里没有中文（未翻译在资源里长得跟翻译好一样，只能扫字符）；
/// 3. 0 / 1 / 2 三个值都不会读成 `1 messages` 这种——`count == 1` 时选到的模板里
///    不许出现「数字 1 + 复数词」。
final class CountCopyLocalizationTests: XCTestCase {

    // MARK: - 工具

    /// 英文模板：`count == 1` 且存在 `.one` 时用 `.one`，否则用基值（与 `Lcount` 同一规则）。
    private func englishTemplate(_ key: String, count: Int) throws -> (template: String, english: [String: String]) {
        let english = try I18NTestResources.map("en")
        guard let base = english[key] else {
            XCTFail("en.lproj 里缺「\(key)」")
            return ("", english)
        }
        if count == 1, let one = english[key + ".one"] { return (one, english) }
        return (base, english)
    }

    /// 0 / 1 / 2 三次渲染：断言「1」时没有复数词，且英文里没有中文。
    private func assertCountsReadNaturally(
        _ key: String,
        _ arguments: (Int) -> [CVarArg],
        requiresOne: Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let english = try I18NTestResources.map("en")
        if requiresOne {
            XCTAssertNotNil(english[key + ".one"], "en 缺「\(key).one」：count == 1 时会读成复数", file: file, line: line)
        } else {
            XCTAssertNil(english[key + ".one"], "「\(key)」的英文没有计数名词，不该有 .one 变体", file: file, line: line)
        }
        for count in [0, 1, 2] {
            let (template, _) = try englishTemplate(key, count: count)
            XCTAssertFalse(
                I18NTestResources.containsCJK(template),
                "「\(key)」第 \(count) 档的英文里还有中文：\(template)", file: file, line: line
            )
            let rendered = I18NTestResources.render(template, arguments(count))
            if count == 1 {
                XCTAssertTrue(
                    I18NTestResources.pluralNounsAfterOne(in: rendered).isEmpty,
                    "「\(key)」在 count == 1 时读成了复数：\(rendered)", file: file, line: line
                )
            } else {
                XCTAssertFalse(rendered.isEmpty, "「\(key)」在 count == \(count) 时渲染成空串", file: file, line: line)
            }
        }
    }

    /// 中文侧：没有资源时中文值就是 key 本身，按 key 渲染即可逐字断言。
    private func assertChinese(
        _ key: String,
        _ arguments: (Int) -> [CVarArg],
        expected: [Int: String],
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let chinese = try I18NTestResources.map("zh-Hans")
        guard let template = chinese[key] else {
            return XCTFail("zh-Hans 里缺「\(key)」", file: file, line: line)
        }
        for (count, text) in expected.sorted(by: { $0.key < $1.key }) {
            XCTAssertEqual(
                I18NTestResources.render(template, arguments(count)), text,
                "「\(key)」在 \(count) 时的中文说法变了", file: file, line: line
            )
        }
    }

    // MARK: - 检索：已显示 N 条

    func testSearchResultCountReadsRightAtZeroOneTwo() throws {
        let key = "已显示 %d 条"
        try assertChinese(key, { [Int($0)] }, expected: [0: "已显示 0 条", 1: "已显示 1 条", 2: "已显示 2 条"])
        // 英文是「N shown」：没有计数名词，但已经有 `.one`，0/1/2 都要读得通。
        try assertCountsReadNaturally(key, { [Int($0)] }, requiresOne: true)
    }

    // MARK: - 清空：有 N 张表没删成功

    func testClearFailureCountUsesSingularAtOne() throws {
        let key = "；有 %d 张表没删成功：%@，其余数据已删除"

        // 中文侧走真实的 `ClearSummary`，逐字断言 0 / 1 / 2（0 张表时不出现这句话）。
        let none = ClearSummary(warnings: [])
        XCTAssertFalse(none.describeAll().contains("张表没删成功"), "没有失败的表时不该出现这句话")

        let one = ClearSummary(warnings: ["memory_terms"])
        XCTAssertEqual(
            one.describeAll(),
            "所有本地数据已删除（0 个人格 · 0 条记忆 · 0 段对话 · 0 句话）；有 1 张表没删成功：memory_terms，其余数据已删除"
        )
        let two = ClearSummary(warnings: ["memory_terms", "usage_events"])
        XCTAssertEqual(
            two.describeAll(),
            "所有本地数据已删除（0 个人格 · 0 条记忆 · 0 段对话 · 0 句话）；有 2 张表没删成功：memory_terms、usage_events，其余数据已删除"
        )

        // 英文侧：`.one` 必须是 "1 table couldn't be cleared"，且 0/1/2 都自然。
        try assertCountsReadNaturally(key, { [Int($0), "memory_terms"] }, requiresOne: true)
        let english = try I18NTestResources.map("en")
        XCTAssertEqual(
            english[key + ".one"].map { I18NTestResources.render($0, [1, "memory_terms"]) },
            "; 1 table couldn't be cleared: memory_terms. Everything else was deleted."
        )
        XCTAssertEqual(
            english[key].map { I18NTestResources.render($0, [2, "memory_terms"]) },
            "; 2 tables couldn't be cleared: memory_terms. Everything else was deleted."
        )
    }

    // MARK: - 用量：一共 N 次调用

    func testUsageCallCountUsesSingularAtOne() throws {
        let key = "一共 %d 次调用 · 读了 %@ · 写了 %@"
        try assertChinese(
            key, { [Int($0), "1.2K", "300"] },
            expected: [
                0: "一共 0 次调用 · 读了 1.2K · 写了 300",
                1: "一共 1 次调用 · 读了 1.2K · 写了 300",
                2: "一共 2 次调用 · 读了 1.2K · 写了 300"
            ]
        )
        try assertCountsReadNaturally(key, { [Int($0), "1.2K", "300"] }, requiresOne: true)
        let english = try I18NTestResources.map("en")
        XCTAssertEqual(
            english[key + ".one"].map { I18NTestResources.render($0, [1, "1.2K", "300"]) },
            "1 call in total · 1.2K read · 300 written"
        )
    }

    // MARK: - 人格：每 N 条消息回头看一眼 / 连着 N 条会被叫醒

    func testPersonaExtractionAndWakeThresholdCountsUseSingular() throws {
        let extraction = "每聊 %d 条消息，他会在后台回头看一眼，把值得记的事记下来。"
        try assertChinese(
            extraction, { [Int($0)] },
            expected: [
                0: "每聊 0 条消息，他会在后台回头看一眼，把值得记的事记下来。",
                1: "每聊 1 条消息，他会在后台回头看一眼，把值得记的事记下来。",
                2: "每聊 2 条消息，他会在后台回头看一眼，把值得记的事记下来。"
            ]
        )
        try assertCountsReadNaturally(extraction, { [Int($0)] }, requiresOne: true)

        let wake = "睡着的时候，你连着发满 %d 条，他会被叫醒（并有点不好意思）。1–20。"
        try assertChinese(
            wake, { [Int($0)] },
            expected: [
                0: "睡着的时候，你连着发满 0 条，他会被叫醒（并有点不好意思）。1–20。",
                1: "睡着的时候，你连着发满 1 条，他会被叫醒（并有点不好意思）。1–20。",
                2: "睡着的时候，你连着发满 2 条，他会被叫醒（并有点不好意思）。1–20。"
            ]
        )
        try assertCountsReadNaturally(wake, { [Int($0)] }, requiresOne: true)
    }

    // MARK: - API 配置：能看到 N 个可用项

    func testApiConfigAvailableItemsCountUsesSingular() throws {
        let key = "连上了：这个 Key 能看到 %d 个可用项，比如 %@。"
        try assertChinese(
            key, { [Int($0), "gpt-4o-mini"] },
            expected: [
                0: "连上了：这个 Key 能看到 0 个可用项，比如 gpt-4o-mini。",
                1: "连上了：这个 Key 能看到 1 个可用项，比如 gpt-4o-mini。",
                2: "连上了：这个 Key 能看到 2 个可用项，比如 gpt-4o-mini。"
            ]
        )
        try assertCountsReadNaturally(key, { [Int($0), "gpt-4o-mini"] }, requiresOne: true)
        let english = try I18NTestResources.map("en")
        XCTAssertEqual(
            english[key + ".one"].map { I18NTestResources.render($0, [1, "gpt-4o-mini"]) },
            "Connected: this Key can see 1 available item, such as gpt-4o-mini."
        )
    }

    // MARK: - 备份：另有 N 个 API 配置的空壳（已有 `.one`，这里钉住读法）

    func testBackupShellCountKeepsItsSingular() throws {
        let key = "备份里另有 %d 个 API 配置的空壳（地址与模型，不含密钥）。"
        try assertCountsReadNaturally(key, { [Int($0)] }, requiresOne: true)
        let english = try I18NTestResources.map("en")
        XCTAssertEqual(
            english[key + ".one"].map { I18NTestResources.render($0, [1]) },
            "The backup also holds 1 API configuration shell (address and model, no key)."
        )
    }

    // MARK: - 主动节奏：N 条开着 · 共 M 条（英文没有计数名词，所以不需要 `.one`）

    func testProactiveHeaderCountLineStaysNaturalAtZeroOneTwo() throws {
        let key = "%d 条开着 · 共 %d 条；时间是一段窗口，不是闹钟。"
        try assertChinese(
            key, { [Int($0), 5] },
            expected: [
                0: "0 条开着 · 共 5 条；时间是一段窗口，不是闹钟。",
                1: "1 条开着 · 共 5 条；时间是一段窗口，不是闹钟。",
                2: "2 条开着 · 共 5 条；时间是一段窗口，不是闹钟。"
            ]
        )
        try assertChinese(
            key, { [Int($0), Int($0)] },
            expected: [
                0: "0 条开着 · 共 0 条；时间是一段窗口，不是闹钟。",
                1: "1 条开着 · 共 1 条；时间是一段窗口，不是闹钟。",
                2: "2 条开着 · 共 2 条；时间是一段窗口，不是闹钟。"
            ]
        )
        // 英文是「N on · M total」：没有计数名词，0/1/2 都读得通，所以不挂 `.one`
        //（挂了也只是把同样的值再抄一遍，还会让「1 时没有复数 s」这条检查失去意义）。
        try assertCountsReadNaturally(key, { [Int($0), 5] }, requiresOne: false)
        let english = try I18NTestResources.map("en")
        XCTAssertEqual(
            english[key].map { I18NTestResources.render($0, [1, 1]) },
            "1 on · 1 total; a time is a window, not an alarm."
        )
    }

    // MARK: - 备份预览的中英对照（已有单复数，这里防止回退）

    func testBackupPreviewStillCountsZeroOneTwo() {
        func preview(personas: Int, memories: Int) -> BackupPreview {
            BackupPreview(
                personas: personas, memories: memories, sessions: 0, messages: 0,
                rules: 0, presets: 0, apiConfigs: 0, exportedAt: 0, appVersion: "0.10.2"
            )
        }
        XCTAssertEqual(
            preview(personas: 1, memories: 1).describe(),
            "1 个人格 · 1 条记忆 · 0 段对话 · 0 句话"
        )
        XCTAssertEqual(
            preview(personas: 2, memories: 2).describe(),
            "2 个人格 · 2 条记忆 · 0 段对话 · 0 句话"
        )
        // 中文没有「零」的说法，0 时直接说「这份备份里什么也没有」。
        XCTAssertEqual(preview(personas: 0, memories: 0).describe(), "这份备份里什么也没有")
    }
}
