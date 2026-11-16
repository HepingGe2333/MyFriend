// 来源：app/src/main/java/com/yap/app/domain/LinkDetector.kt
//
// 从一段文字里找出可点的链接，并把各种奇形怪状的写法收敛成 http/https 地址。
//
// 约定：
// - 纯函数：不联网、不读配置、任何输入都不抛异常（流式输出截断的半个链接也要能安全处理）；
// - 只放行 http / https，`javascript:`、`file:`、`content:`、`intent:`、`data:` 一律拒绝；
// - 中文标点、句号、成对括号等会话里的尾巴会被剔出地址，不跟着链接一起点；
// - 只认看得懂的形态，宁可少识别，也不要把普通文字误标成链接。
//
// **下标单位**：Kotlin 的 `String` 下标是 UTF-16 码元，Swift 这里统一用 `Character`（字素簇）。
// 纯 ASCII / 中文场景两者完全一致；带 emoji 时 Swift 的偏移更小，但区间仍然是原文里
// 一段**完整字符**的范围 —— 渲染层按 Character 切串即可，不会切出半个代理对。

import Foundation

/// 链接在原文里的形态。
///
/// 只为「在应用里看一眼他发来的东西」服务，不联网、不做语义判断。
public enum LinkKind: String {
    /// 带完整协议的地址，例如 `https://example.com/a`。
    case url = "URL"
    /// 没写协议的裸域名，例如 `example.com/a`、`www.example.com`。
    case bareDomain = "BARE_DOMAIN"
    /// Markdown 的 `[标题](地址)` 写法，点击区域是可见的「标题」。
    case markdown = "MARKDOWN"
}

/// 一段链接在原文中的位置。
///
/// `start` 含、`end` 不含，单位是原文里的字符（Character）下标；`url` 是可以直接加载的地址。
/// Markdown 链接的区间只覆盖可见的标题文字（不含 `[]` 与 `(...)`）。
public struct LinkSpan: Equatable {
    public let start: Int
    public let end: Int
    public let url: String
    public let kind: LinkKind

    public init(start: Int, end: Int, url: String, kind: LinkKind) {
        self.start = start
        self.end = end
        self.url = url
        self.kind = kind
    }

    /// 区间长度，方便渲染层判断边界。
    public var length: Int { end - start }
}

/// 链接检测器（对应 Kotlin 的 `object LinkDetector`）。
public enum LinkDetector {

    /// 单条地址的长度上限，防止把一大段无空格文本当成一条链接。
    public static let MAX_URL_LENGTH = 2048

    /// 裸域名后面不跟路径时也要有上限，避免长串编号被当成域名。
    static let MAX_HOST_LENGTH = 253

    /// 这些斜杠后缀更像文件名，不像域名后缀（`.md`、`.png` 之类不点亮）。
    static let FILE_EXTENSION_LIKE: Set<String> = [
        "txt", "md", "markdown", "kt", "kts", "java", "class", "js", "ts", "jsx", "tsx",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "php", "sh", "bat",
        "json", "xml", "yaml", "yml", "toml", "ini", "conf", "properties", "gradle",
        "lock", "log", "csv", "tsv", "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
        "ico", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z",
        "gz", "tar", "apk", "aab", "so", "dll", "exe", "bin", "mp3", "mp4", "mov", "wav",
        "avi", "mkv", "flac", "html", "htm", "css"
    ]

    /// 找出文字里所有链接，按出现位置排序，区间互不重叠。
    ///
    /// 括号、引号、中文标点都不会被算进链接；Markdown 链接的区间指向标题。
    public static func findAll(_ text: String) -> [LinkSpan] {
        if text.isEmpty { return [] }
        let chars = Array(text)
        var result: [LinkSpan] = []
        var index = 0
        while index < chars.count {
            if let match = matchMarkdownLink(chars, bracketStart: index) {
                result.append(match.span)
                index = match.endExclusive
                continue
            }
            if let span = matchSchemeUrl(chars, start: index) {
                result.append(span)
                index = span.end
                continue
            }
            if let span = matchBareDomain(chars, start: index) {
                result.append(span)
                index = span.end
                continue
            }
            index += 1
        }
        return result
    }

    /// 把任意写法收敛成可加载的 http/https 地址；不是链接时返回 nil。
    ///
    /// 会补 `https://`、去掉尾部标点、拒绝非 http(s) 的协议。
    public static func normalize(_ raw: String) -> String? {
        var value = raw.trimmed
        if value.isEmpty || charCount(value) > MAX_URL_LENGTH { return nil }
        if !canStandAsUrl(value) { return nil }

        // 尖括号包裹是 Markdown/邮件里常见的写法：<https://example.com>
        let initialChars = Array(value)
        if value.count > 2 && initialChars.first == "<" && initialChars.last == ">" {
            value = String(initialChars[1..<(initialChars.count - 1)]).trimmed
        }
        value = trimTrailingPunctuation(value)
        if value.isEmpty || charCount(value) > MAX_URL_LENGTH { return nil }
        // 协议相对写法 //example.com 补成 https
        if value.hasPrefix("//") { value = "https:" + value }

        let scheme = detectScheme(value)
        if let scheme {
            // javascript:、file:、content:、intent:、data:、mailto: 等一律不放行
            if scheme != "http" && scheme != "https" { return nil }
        } else {
            // 没写协议：必须像个域名，否则「第一部分：……」这种普通文字会被误判
            let hostPart = takeWhile(value) { $0 != "/" && $0 != "?" && $0 != "#" }
            if !looksLikeBareDomain(hostPart) { return nil }
            value = "https://" + value
        }

        return isLoadableHttpUrl(value) ? value : nil
    }

    // MARK: 匹配

    /// 一次匹配的结果：链接本身 + 这段写法在原文里结束的位置。
    struct Match {
        let span: LinkSpan
        let endExclusive: Int
    }

    /// `[标题](地址)`：区间只覆盖标题，跳过整个写法。
    static func matchMarkdownLink(_ chars: [Character], bracketStart: Int) -> Match? {
        if bracketStart >= chars.count || chars[bracketStart] != "[" { return nil }
        let labelStart = bracketStart + 1
        // 与 Kotlin 的 `indexOf("](")` 一致：找的是「] 紧跟 (」这个**两字符**序列，
        // 不是「第一个 ]」——`[a]b](url)` 的标题应当是 `a]b`。
        let labelEnd = indexOfPair(chars, "]", "(", from: labelStart)
        if labelEnd <= labelStart { return nil }
        let urlStart = labelEnd + 2
        let label = String(chars[labelStart..<labelEnd])
        if label.isEmpty || label.contains("\n") || label.count > MAX_URL_LENGTH { return nil }
        // 地址里本身可能带成对的括号（维基百科那种 …/Foo_(bar)），
        // 不能拿第一个 ')' 收尾，否则会被截成一个 404 地址、后面还留半个括号在正文里。
        let closed = closeParenFor(chars, start: urlStart)
        if closed <= urlStart { return nil }
        guard let url = normalize(String(chars[urlStart..<closed])) else { return nil }
        return Match(span: LinkSpan(start: labelStart, end: labelEnd, url: url, kind: .markdown),
                     endExclusive: closed + 1)
    }

    /// 从 `start` 起找到与地址开头表达式配对的右括号位置；找不到返回 -1。
    ///
    /// 只在同一行里找，并且遇到空白就直接放弃——Markdown 链接的地址段不该有空格，
    /// 有空格说明这根本不是链接（宁可少识别，也不要把后面半段正文吃进地址）。
    static func closeParenFor(_ chars: [Character], start: Int) -> Int {
        var depth = 0
        var index = start
        while index < chars.count {
            switch chars[index] {
            case "(":
                depth += 1
            case "\n":
                return -1
            case " ":
                return -1
            case ")":
                if depth == 0 { return index }
                depth -= 1
            default:
                break
            }
            index += 1
        }
        return -1
    }

    /// `http://` / `https://` 开头的地址。
    static func matchSchemeUrl(_ chars: [Character], start: Int) -> LinkSpan? {
        if !startsWithScheme(chars, start: start) { return nil }
        if !isLinkBoundary(chars, start: start) { return nil }
        let rawEnd = consumeUrlChars(chars, start: start)
        if rawEnd <= start || rawEnd - start > MAX_URL_LENGTH { return nil }
        let trimmed = trimTrailingPunctuation(String(chars[start..<rawEnd]))
        if trimmed.isEmpty { return nil }
        guard let url = normalize(trimmed) else { return nil }
        return LinkSpan(start: start, end: start + trimmed.count, url: url, kind: .url)
    }

    /// 没写协议的域名，例如 `example.com/x`、`www.example.com`。
    static func matchBareDomain(_ chars: [Character], start: Int) -> LinkSpan? {
        if !isBareDomainCandidate(chars, start: start) { return nil }
        if !isLinkBoundary(chars, start: start) { return nil }
        let rawEnd = consumeUrlChars(chars, start: start)
        if rawEnd <= start || rawEnd - start > MAX_URL_LENGTH { return nil }
        let trimmed = trimTrailingPunctuation(String(chars[start..<rawEnd]))
        if trimmed.isEmpty { return nil }
        let hostPart = takeWhile(trimmed) { $0 != "/" && $0 != "?" && $0 != "#" }
        if !looksLikeBareDomain(hostPart) { return nil }
        // 识别出来的地址必须和 normalize 的判断一致，避免渲染层与检测层各说各话
        guard let url = normalize(trimmed) else { return nil }
        return LinkSpan(start: start, end: start + trimmed.count, url: url, kind: .bareDomain)
    }

    // MARK: 扫描辅助

    static func startsWithScheme(_ chars: [Character], start: Int) -> Bool {
        if start > 0 {
            let previous = chars[start - 1]
            // 只从链接真正开始的位置试：`xhttps://`、`a.https://` 之类不算
            if isWordChar(previous) || previous == "@" || previous == "."
                || previous == "/" || previous == "%" || previous == "-" {
                return false
            }
        }
        return hasPrefixIgnoringCase(chars, start: start, prefix: "https://")
            || hasPrefixIgnoringCase(chars, start: start, prefix: "http://")
    }

    /// 从 `start` 往后吃掉所有可能属于地址的 ASCII 字符。
    static func consumeUrlChars(_ chars: [Character], start: Int) -> Int {
        var index = start
        let limit = min(chars.count, start + MAX_URL_LENGTH + 1)
        while index < limit && isUrlChar(chars[index]) { index += 1 }
        return index
    }

    static func isUrlChar(_ char: Character) -> Bool {
        // 空格、控制符、中文和全角标点都算地址结束
        guard let code = char.asciiValue else { return false }
        if code < 0x21 || code > 0x7E { return false }
        switch char {
        // `*` 留给 Markdown 的粗体/斜体标记，别把它吃进地址
        case "<", ">", "\"", "'", "`", "[", "]", "{", "}", "|", "\\", "^", "*":
            return false
        default:
            return true
        }
    }

    /// 链接前面不能紧贴单词字符，否则 `someone@example.com`、`v1.2` 会被切开当链接。
    static func isLinkBoundary(_ chars: [Character], start: Int) -> Bool {
        if start == 0 { return true }
        let previous = chars[start - 1]
        return !isWordChar(previous) && previous != "@" && previous != "."
            && previous != "/" && previous != "%" && previous != "-"
    }

    /// 只把 ASCII 字母数字当「单词的一部分」：`看example.com` 这种中文紧贴的链接也要能认出来。
    static func isWordChar(_ char: Character) -> Bool {
        guard let code = char.asciiValue else { return false }
        return (0x61...0x7A).contains(code)   // a-z
            || (0x41...0x5A).contains(code)   // A-Z
            || (0x30...0x39).contains(code)   // 0-9
            || char == "_"
    }

    /// 便宜的预筛：只有以字母数字开头、且后面不远就有 `www.` 或一个点的位置才值得细看。
    static func isBareDomainCandidate(_ chars: [Character], start: Int) -> Bool {
        if start >= chars.count { return false }
        let char = chars[start]
        if !(char.isLetter || char.isNumber) { return false }
        if start > 0 && (isWordChar(chars[start - 1]) || chars[start - 1] == "@") { return false }
        let limit = min(chars.count, start + 64)
        var index = start
        while index < limit {
            if chars[index] == "." { return true }
            index += 1
        }
        return false
    }

    // MARK: 校验

    /// 只做粗筛：地址里不能有空白、换行和控制字符。
    static func canStandAsUrl(_ value: String) -> Bool {
        for char in value {
            if char.isWhitespace { return false }
            guard let code = char.asciiValue else { continue }
            if code < 0x20 || code == 0x7F { return false }
        }
        return true
    }

    /// 冒号前是不是一个已知的协议名；不是的话说明冒号属于「域名:端口」。
    static func detectScheme(_ value: String) -> String? {
        let chars = Array(value)
        guard let colon = chars.firstIndex(of: ":"), colon > 0 else { return nil }
        let scheme = String(chars[0..<colon])
        if scheme.count > 32 { return nil }
        guard let first = scheme.first, first.isLetter else { return nil }
        // 协议名里不允许点，这样 example.com:8080 不会被当成协议
        let valid = scheme.allSatisfy { $0.isLetter || $0.isNumber || $0 == "+" || $0 == "-" }
        if !valid { return nil }
        return scheme.lowercased()
    }

    /// 裸域名（可能带端口）的形态检查：至少两段、后缀是字母、不能是文件名后缀。
    static func looksLikeBareDomain(_ hostPart: String) -> Bool {
        if hostPart.isEmpty { return false }
        let parts = hostPart.components(separatedBy: ":")
        let host = parts[0]
        let port = parts.count > 1 ? parts[1] : ""
        if hostPart.contains(":") {
            if port.isEmpty || port.count > 5 || port.contains(where: { !$0.isNumber }) {
                return false
            }
        }
        if !isValidHost(host) { return false }
        let labels = host.components(separatedBy: ".")
        let tld = (labels.last ?? "").lowercased()
        return !FILE_EXTENSION_LIKE.contains(tld)
    }

    static func isValidHost(_ host: String) -> Bool {
        if host.isEmpty || host.count > MAX_HOST_LENGTH { return false }
        if host.hasPrefix(".") || host.hasSuffix(".") { return false }
        let labels = host.components(separatedBy: ".")
        // 只认能落地的主机名：单段（localhost 之类）不算，避免把 `https://abc` 也点亮
        if labels.count < 2 { return false }
        if labels.contains(where: { $0.isEmpty || $0.count > 63 }) { return false }
        if labels.contains(where: { label in
            label.contains { char in
                guard let code = char.asciiValue else { return true }
                let isLower = (0x61...0x7A).contains(code)
                let isUpper = (0x41...0x5A).contains(code)
                let isDigit = (0x30...0x39).contains(code)
                return !(isLower || isUpper || isDigit || char == "-")
            }
        }) {
            return false
        }
        if labels.contains(where: { $0.hasPrefix("-") || $0.hasSuffix("-") }) { return false }
        // IPv4 字面量：192.168.1.1 这种也是可以看的地址
        let allIpv4 = labels.count == 4 && labels.allSatisfy { label in
            label.count <= 3 && label.allSatisfy { $0.isNumber }
                && (Int(label).map { (0...255).contains($0) } ?? false)
        }
        if allIpv4 { return true }
        let tld = labels[labels.count - 1]
        if tld.count < 2 { return false }
        let allLetters = tld.allSatisfy { char in
            guard let code = char.asciiValue else { return false }
            return (0x61...0x7A).contains(code) || (0x41...0x5A).contains(code)
        }
        if !allLetters && !tld.lowercased().hasPrefix("xn--") { return false }
        return true
    }

    /// 完整地址的结构检查：协议、有权重的主机名、合法端口。
    static func isLoadableHttpUrl(_ url: String) -> Bool {
        guard let range = url.range(of: "://") else { return false }
        let schemeEnd = url.distance(from: url.startIndex, to: range.lowerBound)
        if schemeEnd <= 0 { return false }
        let scheme = String(url[url.startIndex..<range.lowerBound]).lowercased()
        if scheme != "http" && scheme != "https" { return false }
        let rest = String(url[range.upperBound...])
        if rest.isEmpty { return false }
        if rest.count > MAX_URL_LENGTH { return false }
        let authority = takeWhile(rest) { $0 != "/" && $0 != "?" && $0 != "#" }
        if authority.isEmpty { return false }
        let hostPort: String
        if let at = authority.lastIndex(of: "@") {
            hostPort = String(authority[authority.index(after: at)...])
        } else {
            hostPort = authority
        }
        if hostPort.isEmpty { return false }
        // IPv6 字面量（[::1]:8080）只放行带方括号的写法
        if hostPort.hasPrefix("[") { return hostPartClosesBracket(hostPort) }
        let parts = hostPort.components(separatedBy: ":")
        let host = parts[0]
        let port = parts.count > 1 ? parts[1] : ""
        if hostPort.contains(":") {
            if port.isEmpty || port.count > 5 || port.contains(where: { !$0.isNumber }) {
                return false
            }
        }
        return isValidHost(host)
    }

    static func hostPartClosesBracket(_ hostPort: String) -> Bool {
        hostPort.contains("]")
    }

    /// 去掉句尾、中文标点和不成对括号；括号成对时保留（维基百科那种网址）。
    static func trimTrailingPunctuation(_ raw: String) -> String {
        let chars = Array(raw)
        var end = chars.count
        while end > 0 {
            let char = chars[end - 1]
            let trim: Bool
            switch char {
            case ".", ",", ";", ":", "!", "?", "'", "\"",
                "。", "，", "、", "；", "：", "！", "？", "…",
                "）", "】", "》", "」", "』", "\u{201D}", "\u{2019}", "·":
                trim = true
            case ")":
                trim = countChar(chars, end: end, char: ")") > countChar(chars, end: end, char: "(")
            case "]":
                trim = countChar(chars, end: end, char: "]") > countChar(chars, end: end, char: "[")
            default:
                trim = false
            }
            if !trim { break }
            end -= 1
        }
        return String(chars[0..<end])
    }

    static func countChar(_ chars: [Character], end: Int, char: Character) -> Int {
        var count = 0
        var index = 0
        while index < end {
            if chars[index] == char { count += 1 }
            index += 1
        }
        return count
    }

    // MARK: 小工具

    static func charCount(_ text: String) -> Int { text.count }

    static func takeWhile(_ text: String, _ predicate: (Character) -> Bool) -> String {
        String(text.prefix { predicate($0) })
    }

    static func hasPrefixIgnoringCase(_ chars: [Character], start: Int, prefix: String) -> Bool {
        let target = Array(prefix.lowercased())
        if start + target.count > chars.count { return false }
        for offset in 0..<target.count {
            let char = chars[start + offset]
            guard char.asciiValue != nil else { return false }
            if Character(char.lowercased()) != target[offset] { return false }
        }
        return true
    }

    static func indexOfPair(_ chars: [Character], _ first: Character,
                            _ second: Character, from start: Int) -> Int {
        var index = max(start, 0)
        while index + 1 < chars.count {
            if chars[index] == first && chars[index + 1] == second { return index }
            index += 1
        }
        return -1
    }

    static func indexOf(_ chars: [Character], _ char: Character, from start: Int) -> Int {
        var index = max(start, 0)
        while index < chars.count {
            if chars[index] == char { return index }
            index += 1
        }
        return -1
    }
}
