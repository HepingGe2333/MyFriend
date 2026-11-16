import Foundation
import XCTest

/// 测试怎么找到仓库里的 fixture。
///
/// `swift test` 的工作目录是包根 `ios/YapKit`，fixture 在仓库根的 `fixtures/backup`，
/// 相对路径是 `../../../fixtures/backup`。但「工作目录」这件事在 Xcode 里跑、在 CI 里跑、
/// 或者别人从别的目录 `swift test --package-path` 时都不一样，所以这里不写死拼接：
/// 从几个可能的锚点往上走，谁先看到 `fixtures/backup` 目录就用谁。
///
/// 这比 `#filePath` 单点定位稳：源码被复制到别处（比如打包进 Xcode 工程）时，
/// 还能靠工作目录或可执行文件路径找回来。
enum RepoLayout {
    static let fixtureSubpath = "fixtures/backup"

    /// 仓库根下的 `fixtures/backup` 目录；找不到返回 nil（测试里用 XCTUnwrap 给出清楚原因）。
    static func backupFixtureDirectory(file: String = #filePath) -> URL? {
        for anchor in anchors(file: file) {
            if let found = walkUp(from: anchor) {
                return found
            }
        }
        return nil
    }

    private static func anchors(file: String) -> [URL] {
        var anchors: [URL] = []
        // 1) 测试源码文件本身（最可靠：它在仓库里）。
        if !file.isEmpty {
            anchors.append(URL(fileURLWithPath: file))
        }
        // 2) 当前工作目录（`swift test` 通常是包根 ios/YapKit）。
        anchors.append(URL(fileURLWithPath: FileManager.default.currentDirectoryPath, isDirectory: true))
        // 3) 测试可执行文件（.build/<config>/YapKitPackageTests.xctest/...，深几层也能往上走）。
        if let executable = Bundle.main.executableURL {
            anchors.append(executable)
        }
        if let firstArgument = CommandLine.arguments.first, !firstArgument.isEmpty {
            anchors.append(URL(fileURLWithPath: firstArgument))
        }
        return anchors
    }

    private static func walkUp(from anchor: URL) -> URL? {
        var current = anchor.hasDirectoryPath ? anchor : anchor.deletingLastPathComponent()
        for _ in 0..<16 {
            let candidate = current.appendingPathComponent(fixtureSubpath, isDirectory: true)
            var isDirectory: ObjCBool = false
            if FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDirectory),
               isDirectory.boolValue {
                return candidate
            }
            let parent = current.deletingLastPathComponent()
            if parent.path == current.path { break }
            current = parent
        }
        return nil
    }
}
