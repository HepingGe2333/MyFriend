// 来源：app/src/main/java/com/myfriend/app/ui/components/MarkdownText.kt 里的 `buildInline` /
// `appendInline`（第 605–762 行）
//
// Android 端直接产出 `AnnotatedString`（Compose 类型）；Swift 这边只产出**结构化 span 列表**，
// 让 UI 层自己决定用 AttributedString、Text + 拼接还是别的画法。
// 规则一条不改：加粗 / 斜体 / 行内代码 / 删除线 / 链接 / `$…$` 数学，以及它们的嵌套关系。
//
// 几个必须保留的细节（都是踩过坑的）：
// - 反斜杠转义：`\*` 就是字面的星号，不开始斜体；
// - 链接先按原文坐标找出来，扫描时只查「当前位置有没有链接」，
//   这样流式输出截断到一半的地址不会把后面的文字吞进去；
// - Markdown 链接 `[标题](地址)` 的地址不可用（例如 `javascript:`）时，
//   标题按「下划线普通文字」渲染，不点亮；
// - 配对失败时退回单个字符（`**abc` 里那两个星号都按字面显示）。

import Foundation

/// 行内的一段文字和它的修饰。
///
/// 修饰可以叠加（Android 端是 `SpanStyle.merge`）：`**` 里套链接就是 bold + link。
public struct InlineSpan: Equatable {
    public var text: String
    public var isBold: Bool
    public var isItalic: Bool
    public var isCode: Bool
    public var isStrikethrough: Bool
    /// `$…$` 里的数学：`text` 已经是 Latex 转过的可读文本。
    public var isMath: Bool
    /// Markdown 链接但地址不可用：下划线，但不点亮、不可点。
    public var isUnderlined: Bool
    /// 可点的链接目标；nil 表示不是链接。
    public var url: String?

    public init(text: String, isBold: Bool = false, isItalic: Bool = false, isCode: Bool = false,
                isStrikethrough: Bool = false, isMath: Bool = false, isUnderlined: Bool = false,
                url: String? = nil) {
        self.text = text
        self.isBold = isBold
        self.isItalic = isItalic
        self.isCode = isCode
        self.isStrikethrough = isStrikethrough
        self.isMath = isMath
        self.isUnderlined = isUnderlined
        self.url = url
    }
}

/// 行内 Markdown 解析。
public enum InlineMarkdown {

    /// 修饰状态（不含正文与链接）：递归时逐层合并。
    struct Style: Equatable {
        var isBold = false
        var isItalic = false
        var isCode = false
        var isStrikethrough = false
        var isMath = false
        var isUnderlined = false
        var url: String?
    }

    /// 把一段行内文本切成 span 列表；相邻同样式的片段会被合并（Android 端是逐字 append，
    /// 那在 Swift 侧会让 span 数量爆炸，合并不影响任何视觉结果）。
    public static func parse(_ raw: String) -> [InlineSpan] {
        var spans: [InlineSpan] = []
        appendInline(raw, style: Style(), into: &spans)
        return spans
    }

    /// 便于 UI 直接拼 AttributedString / NSAttributedString 的纯文本形式。
    public static func plainText(_ raw: String) -> String {
        parse(raw).map(\.text).joined()
    }

    static func appendInline(_ raw: String, style: Style, into spans: inout [InlineSpan]) {
        let chars = Array(raw)
        var index = 0
        // 先按原文坐标把链接找出来，逐字扫描时只查当前位置有没有链接。
        var inlineLinks: [Int: LinkSpan] = [:]
        var markdownLinks: [Int: LinkSpan] = [:]
        for span in LinkDetector.findAll(raw) {
            if span.kind == .markdown {
                markdownLinks[span.start] = span
            } else {
                inlineLinks[span.start] = span
            }
        }

        func emit(_ text: String, _ style: Style) {
            if text.isEmpty { return }
            if var last = spans.last, last.matches(style) {
                last.text += text
                spans[spans.count - 1] = last
                return
            }
            spans.append(InlineSpan(
                text: text,
                isBold: style.isBold,
                isItalic: style.isItalic,
                isCode: style.isCode,
                isStrikethrough: style.isStrikethrough,
                isMath: style.isMath,
                isUnderlined: style.isUnderlined,
                url: style.url
            ))
        }

        while index < chars.count {
            let char = chars[index]
            // 反斜杠转义：下一个字符按字面显示。
            if char == "\\" && index + 1 < chars.count {
                emit(String(chars[index + 1]), style)
                index += 2
                continue
            }
            if let link = inlineLinks[index] {
                var linkStyle = style
                linkStyle.url = link.url
                linkStyle.isUnderlined = true
                emit(String(chars[link.start..<link.end]), linkStyle)
                index = link.end
                continue
            }
            if hasPrefix(chars, index, "**") {
                let end = indexOf(chars, "**", from: index + 2)
                if end > index + 2 {
                    var inner = style
                    inner.isBold = true
                    appendInline(String(chars[(index + 2)..<end]), style: inner, into: &spans)
                    index = end + 2
                } else {
                    emit("*", style)
                    index += 1
                }
            } else if hasPrefix(chars, index, "~~") {
                let end = indexOf(chars, "~~", from: index + 2)
                if end > index + 2 {
                    var inner = style
                    inner.isStrikethrough = true
                    appendInline(String(chars[(index + 2)..<end]), style: inner, into: &spans)
                    index = end + 2
                } else {
                    emit("~", style)
                    index += 1
                }
            } else if char == "`" {
                let end = indexOf(chars, "`", from: index + 1)
                if end > index + 1 {
                    var inner = style
                    inner.isCode = true
                    emit(String(chars[(index + 1)..<end]), inner)
                    index = end + 1
                } else {
                    emit("`", style)
                    index += 1
                }
            } else if char == "$" {
                let end = indexOf(chars, "$", from: index + 1)
                let inner = end > index + 1 ? String(chars[(index + 1)..<end]) : ""
                if end > index + 1 && !inner.contains("\n") {
                    var innerStyle = style
                    innerStyle.isMath = true
                    emit(Latex.toReadable(inner), innerStyle)
                    index = end + 1
                } else {
                    emit("$", style)
                    index += 1
                }
            } else if char == "[" {
                let labelEnd = indexOfPair(chars, "]", "(", from: index + 1)
                let urlEnd = labelEnd > 0 ? indexOf(chars, ")", from: labelEnd + 2) : -1
                if labelEnd > index + 1 && urlEnd > labelEnd + 2 {
                    let label = String(chars[(index + 1)..<labelEnd])
                    if let target = markdownLinks[index + 1] {
                        var linkStyle = style
                        linkStyle.url = target.url
                        linkStyle.isUnderlined = true
                        emit(label, linkStyle)
                    } else {
                        // 目标地址不可用（比如 javascript:）：按普通文字渲染，不点亮
                        var dull = style
                        dull.isUnderlined = true
                        emit(label, dull)
                    }
                    index = urlEnd + 1
                } else {
                    emit("[", style)
                    index += 1
                }
            } else if char == "*" || char == "_" {
                let marker = String(char)
                let end = indexOf(chars, marker, from: index + 1)
                if end > index + 1 {
                    var inner = style
                    inner.isItalic = true
                    appendInline(String(chars[(index + 1)..<end]), style: inner, into: &spans)
                    index = end + 1
                } else {
                    emit(marker, style)
                    index += 1
                }
            } else {
                emit(String(char), style)
                index += 1
            }
        }
    }

    // MARK: 小工具

    static func hasPrefix(_ chars: [Character], _ index: Int, _ prefix: String) -> Bool {
        let target = Array(prefix)
        if index + target.count > chars.count { return false }
        for offset in 0..<target.count where chars[index + offset] != target[offset] {
            return false
        }
        return true
    }

    static func indexOf(_ chars: [Character], _ needle: String, from start: Int) -> Int {
        let target = Array(needle)
        if target.isEmpty { return min(max(start, 0), chars.count) }
        guard chars.count >= target.count else { return -1 }
        var index = max(start, 0)
        while index + target.count <= chars.count {
            if Array(chars[index..<(index + target.count)]) == target { return index }
            index += 1
        }
        return -1
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
}

extension InlineSpan {
    /// 两个 span 是否「样式相同」（用来合并相邻片段）。
    func matches(_ style: InlineMarkdown.Style) -> Bool {
        isBold == style.isBold
            && isItalic == style.isItalic
            && isCode == style.isCode
            && isStrikethrough == style.isStrikethrough
            && isMath == style.isMath
            && isUnderlined == style.isUnderlined
            && url == style.url
    }
}
