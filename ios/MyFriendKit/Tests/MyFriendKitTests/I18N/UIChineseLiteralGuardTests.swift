import Foundation
import XCTest

/// 「UI 里没有留下裸中文」的守卫。
///
/// 约定：`ios/MyFriend/Views/**` 与 `ios/MyFriend/App/**` 里用户能看到的每一句中文，
/// 都必须经由本地化入口（`L(...)` / `Lf(...)` / `Lcount(...)`）拿，key 就是中文原文。
/// 裸的 `Text("中文")`、`"中文"` 赋值给 `String`、拼在插值里……都会在这条测试里变红。
///
/// 允许出现中文的位置（白名单要写理由）：
/// - **注释**：给开发者看的说明，不进界面；
/// - **日志**：`DebugSeeding.log` / `Self.log` / `print` 的参数，只写进 `Documents/debug.log`；
/// - `App/SeedPersonas.swift`：种子人格是**数据**（要落库、用户可改），中文 / 英语各一套人设，
///   在首次启动时按语言选一套；提示词面向模型，不是界面文案；
/// - `App/DebugSeeding.swift`：整个文件在 `#if DEBUG` 里，是模拟器验收用的调试种子
///   （日志、本地参考服务的配置名、调试用的主动规则 hint/note），Release 包不存在；
/// - `App/Localization.swift`：语言名「中文 / English」是给模型看的语言标识，不是界面文案。
///
/// 范围之外：`MyFriendKit/` 的提示词脚手架、日志与模型面向错误文案本轮不翻译（见交付报告）。
final class UIChineseLiteralGuardTests: XCTestCase {

    private static let iosRoot: URL = {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<12 {
            let candidate = current.appendingPathComponent("MyFriend/Resources", isDirectory: true)
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDir), isDir.boolValue {
                return current
            }
            current = current.deletingLastPathComponent()
        }
        fatalError("找不到 ios/MyFriend/Resources")
    }()

    /// 文件级白名单：路径后缀 → 理由。
    private static let fileWhitelist: [(suffix: String, reason: String)] = [
        ("App/DebugSeeding.swift", "仅 DEBUG 构建的模拟器验收钩子，Release 里不存在"),
        ("App/Localization.swift", "语言名「中文 / English」是发给模型的语言标识")
    ]

    private static let chinese = try! NSRegularExpression(pattern: #"[\u4e00-\u9fff]"#)

    func testNoBareChineseUILiterals() throws {
        var violations: [String] = []
        var scannedFiles = 0
        var allowedByWhitelist = 0

        for file in LocalizationResourceTests.swiftFiles() {
            let path = file.path
            if let match = Self.fileWhitelist.first(where: { path.hasSuffix($0.suffix) }) {
                _ = match
                allowedByWhitelist += 1
                continue
            }
            guard let raw = try? String(contentsOf: file, encoding: .utf8) else { continue }
            scannedFiles += 1
            let code = LocalizationResourceTests.stripComments(raw)
            let lines = code.components(separatedBy: "\n")

            for (offset, line) in lines.enumerated() {
                let lineNumber = offset + 1
                for literal in Self.chineseLiterals(in: line) {
                    // 日志：只写进 debug.log，不翻译。
                    if Self.isLogLine(line) { continue }
                    // 本地化入口：key 就是中文原文。
                    if Self.isLocalizationArgument(line: line, literalStart: literal.start) { continue }
                    let name = file.lastPathComponent
                    violations.append("\(name):\(lineNumber)  \(line.trimmingCharacters(in: .whitespaces))")
                }
            }
        }

        XCTAssertGreaterThan(scannedFiles, 15, "扫描到的 Swift 文件太少，测试定位可能错了")
        // 种子人格的数据在 0.11.0 搬到了 `MyFriendKit/Sources`（Kit 不在本测试扫描范围内），
        // 白名单还剩 `DebugSeeding` 与 `Localization` 两个文件。
        XCTAssertGreaterThan(allowedByWhitelist, 1, "白名单文件一个都没匹配上，路径可能变了")
        XCTAssertTrue(
            violations.isEmpty,
            """
            发现 \(violations.count) 处裸中文字面量（用户可见文案必须走 L()/Lf()/Lcount()，
            或者加进本测试的白名单并写清理由）：
            """ + "\n" + violations.joined(separator: "\n")
        )
    }

    /// 一行里所有含中文的字符串字面量（起点是引号的下标）。
    static func chineseLiterals(in line: String) -> [(text: String, start: Int)] {
        var result: [(String, Int)] = []
        var index = line.startIndex
        var inString = false
        var escaped = false
        var literalStart = line.startIndex
        var current = ""
        while index < line.endIndex {
            let ch = line[index]
            if inString {
                if escaped {
                    current.append(ch)
                    escaped = false
                } else if ch == "\\" {
                    escaped = true
                    current.append(ch)
                } else if ch == "\"" {
                    inString = false
                    let range = NSRange(current.startIndex..<current.endIndex, in: current)
                    if Self.chinese.firstMatch(in: current, range: range) != nil {
                        let offset = line.distance(from: line.startIndex, to: literalStart)
                        result.append((current, offset))
                    }
                    current = ""
                } else {
                    current.append(ch)
                }
            } else if ch == "\"" {
                inString = true
                literalStart = index
                current = ""
            }
            index = line.index(after: index)
        }
        return result
    }

    static func isLogLine(_ line: String) -> Bool {
        line.contains("DebugSeeding.log(") || line.contains("Self.log(")
            || line.contains(" log(") || line.contains("print(")
    }

    /// 字面量前面紧挨着 `L(` / `Lf(` / `Lcount(` / `NSLocalizedString(` 就算本地化过。
    static func isLocalizationArgument(line: String, literalStart: Int) -> Bool {
        let prefix = String(line.prefix(literalStart))
            .trimmingCharacters(in: .whitespaces)
        return ["L(", "Lf(", "Lcount(", "NSLocalizedString("].contains { prefix.hasSuffix($0) }
    }
}
