import Foundation

/// 0.10.2 i18n 收尾的三组测试共用的资源定位与解析。
///
/// 守卫本体（key 集合一致 / 占位符按位置一致 / 没有死 key）在 `LocalizationResourceTests`；
/// 这里只把「怎么找到 `ios/MyFriend/Resources/*.lproj/Localizable.strings`」和
/// 「怎么把一条 key 的英文值取出来」抽出来，免得三个文件各写一份路径回溯。
enum I18NTestResources {

    /// 仓库里 `ios/` 目录：测试源码往上走，找到带着 `MyFriend/Resources` 的那一层。
    static let iosRoot: URL = {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<12 {
            let candidate = current.appendingPathComponent("MyFriend/Resources", isDirectory: true)
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDir), isDir.boolValue {
                return current
            }
            current = current.deletingLastPathComponent()
        }
        fatalError("找不到 ios/MyFriend/Resources：测试源码可能被搬出了仓库")
    }()

    static func url(_ language: String, _ file: String = "Localizable.strings") -> URL {
        iosRoot.appendingPathComponent("MyFriend/Resources/\(language).lproj/\(file)")
    }

    static func entries(_ language: String, _ file: String = "Localizable.strings") throws
        -> [LocalizationResourceTests.Entry] {
        try LocalizationResourceTests.parse(url(language, file))
    }

    /// key → value。`.strings` 里重复 key 会被后者覆盖，但重复本身由守卫测试单独抓。
    static func map(_ language: String) throws -> [String: String] {
        Dictionary(try entries(language).map { ($0.key, $0.value) }, uniquingKeysWith: { _, last in last })
    }

    /// 英文资源里这条 key 的值；没有就返回 nil（守卫会报缺 key，这里只负责取）。
    static func english(_ key: String) throws -> String? {
        try map("en")[key]
    }

    /// 按资源里的模板渲染：用于「0 / 1 / 2 分别读成什么」这类断言。
    static func render(_ template: String, _ arguments: [CVarArg]) -> String {
        String(format: template, arguments: arguments)
    }

    private static let cjk = try! NSRegularExpression(pattern: #"[\u4e00-\u9fff]"#)

    static func containsCJK(_ text: String) -> Bool {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return cjk.firstMatch(in: text, range: range) != nil
    }

    /// 英文模板在 `count == 1` 时**不应该**留下复数词：`1 messages` / `1 tables` 都算失败。
    ///
    /// 只认「数字 1 + 一个以 s 结尾的英文词」这一种形状：`1 min ago`、`1 API config`、
    /// `1 person's avatar` 都不会被误判。
    private static let pluralAfterOne = try! NSRegularExpression(
        pattern: #"\b1\s+[A-Za-z][A-Za-z'’-]*s\b"#
    )

    static func pluralNounsAfterOne(in text: String) -> [String] {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return pluralAfterOne.matches(in: text, range: range).compactMap { match in
            Range(match.range, in: text).map { String(text[$0]) }
        }
    }
}
