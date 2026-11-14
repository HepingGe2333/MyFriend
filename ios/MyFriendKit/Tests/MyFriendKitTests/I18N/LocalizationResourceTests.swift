import Foundation
import XCTest

/// 本地化资源的守卫测试。
///
/// 这一组测试只做一件事：让「两份 strings 漂了」在 CI 里立刻红，而不是等到切到英文
/// 才发现某个界面出现了中文 / 某个占位符丢了。具体钉四件事：
///
/// 1. `zh-Hans` 与 `en` 的 **key 集合完全一致**（缺一条、多一条、重复一条都算失败）；
/// 2. 同一条 key 的**占位符集合一致**（`%@` / `%d` / `%%`；允许英语调整顺序，不允许丢参数）；
/// 3. 单复数变体成对出现：`Lcount(...)` 用的 key 必须有 `.one`，且不能有孤立的 `.one`；
/// 4. 代码里 `L(...)` / `Lf(...)` / `Lcount(...)` 用到的 key 都能在 resources 里找到，
///    反过来 resources 里也没有没人用的死 key。
///
/// 为什么不用 `.stringsdict`：这个工程只有两份 `.strings`，为几个数量词再引一套格式
/// （还要在 pbxproj 里登记）不划算；`.one` 变体由这里的第 2、3 条守着，写漏会红。
final class LocalizationResourceTests: XCTestCase {

    // MARK: - 找文件

    /// 仓库里 `ios/` 目录（测试源码往上走，找到带着 `MyFriend/Resources` 的那一层）。
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
        fatalError("找不到 ios/MyFriend/Resources：测试源码可能被搬出了仓库")
    }()

    private static func stringsURL(_ language: String, _ file: String = "Localizable.strings") -> URL {
        iosRoot.appendingPathComponent("MyFriend/Resources/\(language).lproj/\(file)")
    }

    private static var sourceRoots: [URL] {
        ["MyFriend/Views", "MyFriend/App"].map { iosRoot.appendingPathComponent($0, isDirectory: true) }
    }

    // MARK: - 解析 .strings

    /// 一条 `"key" = "value";`。行号只为失败信息里能指到人。
    struct Entry: Equatable {
        var key: String
        var value: String
        var line: Int
    }

    /// 一个够用的 `.strings` 解析器：支持 `//` 与 `/* */` 注释、空行、转义引号。
    ///
    /// 不引 `PropertyListSerialization`：`.strings` 是旧式 plist，系统解析器对
    /// 「重复 key」是**后写覆盖**而不是报错——那正是我们要抓的东西，所以自己扫一遍。
    static func parse(_ url: URL) throws -> [Entry] {
        let text = try String(contentsOf: url, encoding: .utf8)
        var entries: [Entry] = []
        var index = text.startIndex
        var line = 1

        func advance(_ count: Int) {
            var i = 0
            while i < count, index < text.endIndex {
                if text[index] == "\n" { line += 1 }
                index = text.index(after: index)
                i += 1
            }
        }
        func skipTrivia() {
            while index < text.endIndex {
                let ch = text[index]
                if ch.isWhitespace { advance(1); continue }
                if ch == "/", text.index(after: index) < text.endIndex {
                    let next = text[text.index(after: index)]
                    if next == "/" {
                        while index < text.endIndex, text[index] != "\n" { advance(1) }
                        continue
                    }
                    if next == "*" {
                        advance(2)
                        while index < text.endIndex {
                            if text[index] == "*", text.index(after: index) < text.endIndex,
                               text[text.index(after: index)] == "/" {
                                advance(2)
                                break
                            }
                            advance(1)
                        }
                        continue
                    }
                }
                return
            }
        }
        func readQuoted() -> String? {
            guard index < text.endIndex, text[index] == "\"" else { return nil }
            advance(1)
            var out = ""
            while index < text.endIndex {
                let ch = text[index]
                if ch == "\\" {
                    advance(1)
                    guard index < text.endIndex else { return nil }
                    let escaped = text[index]
                    switch escaped {
                    case "n": out.append("\n")
                    case "t": out.append("\t")
                    case "r": out.append("\r")
                    case "\"": out.append("\"")
                    case "\\": out.append("\\")
                    default: out.append(escaped)
                    }
                    advance(1)
                    continue
                }
                if ch == "\"" { advance(1); return out }
                out.append(ch)
                advance(1)
            }
            return nil
        }

        while true {
            skipTrivia()
            guard index < text.endIndex else { break }
            guard let key = readQuoted() else {
                // 不是 key 的行（解析器看不懂）：跳过这一行，避免死循环。
                while index < text.endIndex, text[index] != "\n" { advance(1) }
                continue
            }
            let keyLine = line
            skipTrivia()
            guard index < text.endIndex, text[index] == "=" else {
                XCTFail("\(url.lastPathComponent):\(keyLine) 这条 key 后面缺 `=`")
                return entries
            }
            advance(1)
            skipTrivia()
            guard let value = readQuoted() else {
                XCTFail("\(url.lastPathComponent):\(keyLine) 这条 key 后面缺 value")
                return entries
            }
            skipTrivia()
            if index < text.endIndex, text[index] == ";" { advance(1) }
            entries.append(Entry(key: key, value: value, line: keyLine))
        }
        return entries
    }

    private func load(_ language: String) throws -> [Entry] {
        try Self.parse(Self.stringsURL(language))
    }

    // MARK: - 1. key 集合一致 / 重复 key

    func testLocalizableKeysMatchAcrossLanguages() throws {
        let zh = try load("zh-Hans")
        let en = try load("en")
        XCTAssertFalse(zh.isEmpty, "zh-Hans 的 Localizable.strings 是空的")
        XCTAssertFalse(en.isEmpty, "en 的 Localizable.strings 是空的")

        for (language, entries) in [("zh-Hans", zh), ("en", en)] {
            let duplicates = Dictionary(grouping: entries, by: \.key)
                .filter { $0.value.count > 1 }
                .map { "\($0.key)（第 \($0.value.map(\.line).map(String.init).joined(separator: ", ")) 行）" }
                .sorted()
            XCTAssertTrue(duplicates.isEmpty, "\(language) 里有重复 key：\n" + duplicates.joined(separator: "\n"))
        }

        let zhKeys = Set(zh.map(\.key))
        let enKeys = Set(en.map(\.key))
        let missingInEnglish = zhKeys.subtracting(enKeys).sorted()
        let extraInEnglish = enKeys.subtracting(zhKeys).sorted()
        XCTAssertTrue(
            missingInEnglish.isEmpty,
            "en 缺这些 key（共 \(missingInEnglish.count) 条）：\n" + missingInEnglish.joined(separator: "\n")
        )
        XCTAssertTrue(
            extraInEnglish.isEmpty,
            "en 多了这些 key（共 \(extraInEnglish.count) 条，zh-Hans 里没有）：\n" + extraInEnglish.joined(separator: "\n")
        )
    }

    // MARK: - 2. 占位符一致

    /// 取出格式串里的占位符，按「去掉位置前缀」归一化后排序。
    ///
    /// 允许英语调整参数顺序（"%@ in %d" vs "%d 条 %@"），但集合必须一样：
    /// 丢一个 `%@` 就是界面上少了一句话，多一个 `%d` 就是崩溃。
    static func placeholders(in value: String) -> [String] {
        let pattern = #"%(?:\d+\$)?[-+ #0]*[0-9.]*(?:hh|h|ll|l|q|L|z|t|j)?[@dDiuUxXoOfeEgGcCsSpaA]|%%"#
        let regex = try! NSRegularExpression(pattern: pattern)
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return regex.matches(in: value, range: range).map { match in
            var token = String(value[Range(match.range, in: value)!])
            if token == "%%" { return "%%" }
            if let dollar = token.firstIndex(of: "$") {
                // "%1$@" → "%@"：顺序可以变，参数身份不能变。
                token = "%" + token[token.index(after: dollar)...]
            }
            return token
        }.sorted()
    }

    /// 取出格式串里的占位符，**按出现顺序**（去掉位置前缀）。
    ///
    /// 与 `placeholders(in:)` 的区别只有「不排序」：这条才是 `String(format:)` 的真实语义。
    static func orderedPlaceholders(in value: String) -> [String] {
        let pattern = #"%(?:\d+\$)?[-+ #0]*[0-9.]*(?:hh|h|ll|l|q|L|z|t|j)?[@dDiuUxXoOfeEgGcCsSpaA]|%%"#
        let regex = try! NSRegularExpression(pattern: pattern)
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return regex.matches(in: value, range: range).map { match in
            var token = String(value[Range(match.range, in: value)!])
            if token == "%%" { return "%%" }
            if let dollar = token.firstIndex(of: "$") {
                token = "%" + token[token.index(after: dollar)...]
            }
            return token
        }
    }

    func testPlaceholdersMatchAcrossLanguages() throws {
        let zh = Dictionary(uniqueKeysWithValues: try load("zh-Hans").map { ($0.key, $0.value) })
        let en = try load("en")
        for entry in en {
            guard let zhValue = zh[entry.key] else { continue }
            let zhTokens = Self.placeholders(in: zhValue)
            let enTokens = Self.placeholders(in: entry.value)
            XCTAssertEqual(
                zhTokens, enTokens,
                "key「\(entry.key)」的占位符不一致：zh=\(zhTokens) en=\(enTokens)"
            )
        }
    }

    /// 占位符**按位置**一致（0.10.2 审计 P2-9）。
    ///
    /// 上面那条只比集合（排序过）：`"%@ 里有 %d 条"` 与 `"%d entry in %@"` 也算通过，
    /// 可这两种写法里「第 1 个参数」根本不是同一个东西 —— `String(format:)` 会把
    /// `%@` 的位置交给 `Int`、把 `%d` 的位置交给 `String`，轻则乱码重则崩溃。
    ///
    /// 这里按**出现顺序**比对：英文想调整语序就必须写位置参数（`%1$@ … %2$d`），
    /// 而那正好保证了「参数身份不变」。
    func testPlaceholdersMatchByPosition() throws {
        let zh = Dictionary(uniqueKeysWithValues: try load("zh-Hans").map { ($0.key, $0.value) })
        let en = try load("en")
        var mismatches: [String] = []
        for entry in en {
            guard let zhValue = zh[entry.key] else { continue }
            let zhTokens = Self.orderedPlaceholders(in: zhValue)
            let enTokens = Self.orderedPlaceholders(in: entry.value)
            if zhTokens != enTokens {
                mismatches.append("「\(entry.key)」zh=\(zhTokens) en=\(enTokens)")
            }
        }
        XCTAssertTrue(
            mismatches.isEmpty,
            "这些 key 的占位符**位置**不一致（语序变了就必须用位置参数 %1$@/%2$d）：\n"
                + mismatches.joined(separator: "\n")
        )
    }

    // MARK: - 2b. 英文资源里不能残留中文（未翻译）

    private static let cjk = try! NSRegularExpression(pattern: #"[\u4e00-\u9fff]"#)

    private static func containsCJK(_ text: String) -> Bool {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return cjk.firstMatch(in: text, range: range) != nil
    }

    /// 英文资源的**值**里不允许出现 CJK（0.10.2 审计 P2-9）。
    ///
    /// 为什么必须钉这一条：key 是中文原文，所以「没翻译」在英文资源里长得跟「翻译好了」
    /// 一模一样（值就是 key 本身）；只有扫字符能发现。0.10.1 的守卫只比 key 集合与占位符，
    /// 漏一条翻译照样全绿，界面上却是一句中文。
    ///
    /// 白名单只有一个理由：**中文本身是内容**（比如语言名「中文」）。
    func testEnglishValuesContainNoChinese() throws {
        let allowed: Set<String> = ["中文"]
        var offenders: [String] = []
        for entry in try load("en") {
            if allowed.contains(entry.value) { continue }
            if Self.containsCJK(entry.value) {
                offenders.append("第 \(entry.line) 行「\(entry.key)」= \(entry.value)")
            }
        }
        XCTAssertTrue(
            offenders.isEmpty,
            "en 里有 \(offenders.count) 条没翻译（值里含中文）：\n" + offenders.joined(separator: "\n")
        )
        // InfoPlist.strings 同理（App 名字/权限说明会直接上屏）。
        for entry in try Self.parse(Self.stringsURL("en", "InfoPlist.strings")) {
            XCTAssertFalse(Self.containsCJK(entry.value), "InfoPlist.strings(en) 第 \(entry.line) 行没翻译：\(entry.value)")
        }
    }

    /// 单复数变体的**值**里不能残留 key 后缀（0.9.1 有一批 `.one` 值把后缀写进了正文）。
    func testPluralValuesDoNotLeakKeySuffix() throws {
        for language in ["zh-Hans", "en"] {
            let offenders = try load(language).filter { $0.key.hasSuffix(".one") && $0.value.hasSuffix(".one") }
                .map { "第 \($0.line) 行「\($0.key)」= \($0.value)" }
            XCTAssertTrue(
                offenders.isEmpty,
                "\(language) 里有值的结尾多了 `.one`（用户会看到这个后缀）：\n" + offenders.joined(separator: "\n")
            )
        }
    }

    // MARK: - 3. 单复数变体成对

    func testPluralVariantsArePaired() throws {
        for language in ["zh-Hans", "en"] {
            let keys = Set(try load(language).map(\.key))
            let ones = keys.filter { $0.hasSuffix(".one") }
            let missingOne = keys.filter { !$0.hasSuffix(".one") }
                .filter { SourceKeys.counted.contains($0) || KitSourceKeys.counted.contains($0) }
                .filter { !ones.contains($0 + ".one") }
                .sorted()
            let orphanOnes = ones.filter { !keys.contains(String($0.dropLast(4))) }.sorted()
            XCTAssertTrue(missingOne.isEmpty, "\(language)：Lcount 用的 key 少了 .one 变体：\n" + missingOne.joined(separator: "\n"))
            XCTAssertTrue(orphanOnes.isEmpty, "\(language)：有孤立的 .one 变体：\n" + orphanOnes.joined(separator: "\n"))
        }
    }

    // MARK: - 4. 代码与资源互相覆盖

    /// 代码里 `L(...)` / `Lf(...)` / `Lcount(...)` 用到的 key。
    enum SourceKeys {
        static let all: Set<String> = scan().all
        static let counted: Set<String> = scan().counted

        static func scan() -> (all: Set<String>, counted: Set<String>) {
            var all = Set<String>()
            var counted = Set<String>()
            // App 层有两种写法：`L/Lf/Lcount/NSLocalizedString(...)` 与直接 `String(localized:)`
            //（后者主要出现在 Debug 钩子与个别组件里）。两种都要算「代码用到」。
            let pattern = #"\b(L|Lf|Lcount|NSLocalizedString)\(\s*"((?:[^"\\]|\\.)*)"|String\(localized:\s*"((?:[^"\\]|\\.)*)""#
            let regex = try! NSRegularExpression(pattern: pattern)
            for url in LocalizationResourceTests.swiftFiles() {
                guard let raw = try? String(contentsOf: url, encoding: .utf8) else { continue }
                let text = stripComments(raw)
                let range = NSRange(text.startIndex..<text.endIndex, in: text)
                for match in regex.matches(in: text, range: range) {
                    let keyIndex = match.range(at: 2).length > 0 ? 2 : 3
                    guard let keyRange = Range(match.range(at: keyIndex), in: text) else { continue }
                    let raw = String(text[keyRange])
                    let key = raw
                        .replacingOccurrences(of: #"\n"#, with: "\n")
                        .replacingOccurrences(of: #"\""#, with: "\"")
                        .replacingOccurrences(of: #"\\"#, with: "\\")
                    all.insert(key)
                    if match.range(at: 1).length > 0, let fn = Range(match.range(at: 1), in: text),
                       text[fn] == "Lcount" {
                        counted.insert(key)
                    }
                }
            }
            return (all, counted)
        }
    }

    func testEverySourceKeyExistsInResourcesAndViceVersa() throws {
        let source = SourceKeys.all.union(KitSourceKeys.all)
        XCTAssertFalse(source.isEmpty, "没有在 Views/App 里扫到任何 L() key：测试定位或源码结构变了")
        XCTAssertGreaterThan(KitSourceKeys.all.count, 100, "没有在 MyFriendKit/Sources 里扫到 String(localized:) key")

        for language in ["zh-Hans", "en"] {
            let resource = Set(try load(language).map(\.key))
            let missing = source.subtracting(resource).sorted()
            XCTAssertTrue(missing.isEmpty, "\(language) 里缺代码用到的 key：\n" + missing.joined(separator: "\n"))
            let unused = resource.subtracting(source).filter { !$0.hasSuffix(".one") }.sorted()
            XCTAssertTrue(unused.isEmpty, "\(language) 里有没人用的 key：\n" + unused.joined(separator: "\n"))
        }
    }

    /// Kit 层（`String(localized: "…")`）的 key 也要在**两份**资源里成对存在。
    ///
    /// 单独再钉一条的理由：Kit 的文案没有 App 的 `L()` 外壳，最容易「只加 en、忘了中文」
    /// 或者「改了中文原文、没改另一份」。这一条失败时的信息比合并检查更直指 Kit。
    func testKitLocalizedKeysExistInBothLanguages() throws {
        let kitKeys = KitSourceKeys.all
        XCTAssertGreaterThan(kitKeys.count, 100, "Kit 的 String(localized:) 扫描结果太少，路径可能变了")
        for language in ["zh-Hans", "en"] {
            let resource = Set(try load(language).map(\.key))
            let missing = kitKeys.subtracting(resource).sorted()
            XCTAssertTrue(
                missing.isEmpty,
                "\(language) 里缺 Kit 用到的 \(missing.count) 条 key：\n" + missing.joined(separator: "\n")
            )
        }
        // 缺 key / 死 key 都红：让「资源里没有、代码里也没用」的旧词也能被发现。
        let resource = Set(try load("zh-Hans").map(\.key))
        let unused = resource.subtracting(SourceKeys.all).subtracting(kitKeys)
            .filter { !$0.hasSuffix(".one") }.sorted()
        XCTAssertTrue(unused.isEmpty, "资源里有没人用的 key：\n" + unused.joined(separator: "\n"))
    }

    // MARK: - 5. 语言指令与 Android 逐字一致

    /// 「## 语言」小节的模板在两端必须逐字一致（占位符按各平台格式规则：Android `%1$s`、iOS `%1$@`）。
    ///
    /// 这一条是跨端契约：Android 的 `values/strings.xml` 与 `values-en/strings.xml` 里
    /// `prompt_language_directive` 就是下面这两段文本；谁改了措辞，这条测试先红。
    func testLanguageDirectiveMatchesAndroidVerbatim() throws {
        let zh = Dictionary(uniqueKeysWithValues: try load("zh-Hans").map { ($0.key, $0.value) })
        let en = Dictionary(uniqueKeysWithValues: try load("en").map { ($0.key, $0.value) })
        let key = "prompt_language_directive"
        let zhExpected = "## 语言\n对方现在用%1$@跟你说话。请用同一种语言回答，专有名词可以保留原文。"
        let enExpected = "## Language\nThey are speaking to you in %1$@ right now. Answer in the same language; proper nouns can stay as they are."
        XCTAssertEqual(zh[key], zhExpected, "中文语言指令与 Android values/strings.xml 不一致")
        XCTAssertEqual(en[key], enExpected, "英文语言指令与 Android values-en/strings.xml 不一致")
        // 模板里的语言名参数只有一个，且两种语言都是 %1$@（iOS 的字符串占位符）。
        XCTAssertEqual(Self.placeholders(in: zhExpected), ["%@"])
        XCTAssertEqual(Self.placeholders(in: enExpected), ["%@"])
    }

    // MARK: - 6. InfoPlist.strings

    func testInfoPlistStringsStayInSync() throws {
        let zh = try Self.parse(Self.stringsURL("zh-Hans", "InfoPlist.strings"))
        let en = try Self.parse(Self.stringsURL("en", "InfoPlist.strings"))
        XCTAssertEqual(Set(zh.map(\.key)), Set(en.map(\.key)))
        XCTAssertFalse(zh.isEmpty)
    }

    /// Kit 源码里 `String(localized: "…")` 的 key。
    enum KitSourceKeys {
        static let all: Set<String> = scan().all
        /// 走单复数变体的 key（`PluralText.format/template`）：英文必须有 `.one`。
        static let counted: Set<String> = scan().counted

        static func scan() -> (all: Set<String>, counted: Set<String>) {
            var keys = Set<String>()
            var counted = Set<String>()
            // 两条分别扫：`String(localized:)` 是普通 key，`PluralText.…` 是需要 `.one` 的计数 key。
            let localizedPattern = #"String\(localized:\s*"((?:[^"\\]|\\.)*)""#
            let countedPattern = #"PluralText\.(?:format|template)\(\s*"((?:[^"\\]|\\.)*)""#
            let localizedRegex = try! NSRegularExpression(pattern: localizedPattern)
            let countedRegex = try! NSRegularExpression(pattern: countedPattern)
            let root = LocalizationResourceTests.iosRoot
                .appendingPathComponent("MyFriendKit/Sources", isDirectory: true)
            guard let enumerator = FileManager.default.enumerator(
                at: root, includingPropertiesForKeys: nil
            ) else { return (keys, counted) }
            for case let url as URL in enumerator where url.pathExtension == "swift" {
                guard let raw = try? String(contentsOf: url, encoding: .utf8) else { continue }
                let text = LocalizationResourceTests.stripComments(raw)
                let range = NSRange(text.startIndex..<text.endIndex, in: text)
                for (regex, isCounted) in [(localizedRegex, false), (countedRegex, true)] {
                    for match in regex.matches(in: text, range: range) {
                        guard let keyRange = Range(match.range(at: 1), in: text) else { continue }
                        let key = String(text[keyRange])
                            .replacingOccurrences(of: #"\n"#, with: "\n")
                            .replacingOccurrences(of: #"\""#, with: "\"")
                            .replacingOccurrences(of: #"\\"#, with: "\\")
                        keys.insert(key)
                        if isCounted { counted.insert(key) }
                    }
                }
            }
            return (keys, counted)
        }
    }

    // MARK: - 扫描源码文件

    /// 把 `//` 与 `/* */` 注释替换成等长空白（保留换行，行号不变）。
    ///
    /// 注释里会写示例 `L("…")`，不去掉的话「代码用到的 key」会多出几条不存在的 key。
    /// 字符串字面量里的 `//`（比如 URL）不能被误判，所以逐字符扫而不是按行切。
    static func stripComments(_ text: String) -> String {
        var out = ""
        var index = text.startIndex
        var inString = false
        var escaped = false
        while index < text.endIndex {
            let ch = text[index]
            let next = text.index(after: index) < text.endIndex ? text[text.index(after: index)] : nil
            if inString {
                out.append(ch)
                if escaped { escaped = false }
                else if ch == "\\" { escaped = true }
                else if ch == "\"" { inString = false }
                index = text.index(after: index)
                continue
            }
            if ch == "\"" { inString = true; out.append(ch); index = text.index(after: index); continue }
            if ch == "/", next == "/" {
                while index < text.endIndex, text[index] != "\n" {
                    out.append(" ")
                    index = text.index(after: index)
                }
                continue
            }
            if ch == "/", next == "*" {
                out.append(" "); out.append(" ")
                index = text.index(index, offsetBy: 2)
                while index < text.endIndex {
                    if text[index] == "*", text.index(after: index) < text.endIndex,
                       text[text.index(after: index)] == "/" {
                        out.append(" "); out.append(" ")
                        index = text.index(index, offsetBy: 2)
                        break
                    }
                    out.append(text[index] == "\n" ? "\n" : " ")
                    index = text.index(after: index)
                }
                continue
            }
            out.append(ch)
            index = text.index(after: index)
        }
        return out
    }

    static func swiftFiles() -> [URL] {
        var result: [URL] = []
        for root in sourceRoots {
            guard let enumerator = FileManager.default.enumerator(
                at: root, includingPropertiesForKeys: nil
            ) else { continue }
            for case let url as URL in enumerator where url.pathExtension == "swift" {
                result.append(url)
            }
        }
        return result.sorted { $0.path < $1.path }
    }
}
