// 来源：app/src/main/java/com/yap/app/ui/components/MarkdownText.kt 里的 `MarkdownParser`
// 与 `sealed interface MarkdownBlock`（第 72–186 行）
//
// 只移植**解析器**，不移植 Compose 渲染：把一段回复切成块，交给 UI 层决定怎么画。
// 为什么解析放在 Domain：它是一条纯字符串规则，和平台无关，而且「什么样的写法算代码块」
// 必须和 Android 端一致 —— 否则同一段回复在两端排版不同，用户会觉得「换了台手机他就不会写字了」。

import Foundation

/// 一段回复被切成的块。
///
/// `paragraph` / `heading` / `codeBlock` / `quote` / `bullets` / `ordered` / `divider` / `mathBlock`
/// 与 Android 端的 `MarkdownBlock` 一一对应（顺序也一致，便于逐段比对排版）。
public enum MarkdownBlock: Equatable {
    case paragraph(text: String)
    case heading(level: Int, text: String)
    case codeBlock(language: String, code: String)
    case quote(text: String)
    case bullets(items: [String])
    case ordered(items: [String])
    case divider
    case mathBlock(latex: String)
}

/// Markdown 块级解析器。
public enum MarkdownParser {

    static let headingRegex = KotlinRegex(#"^(#{1,6})\s+(.*)$"#)
    static let bulletRegex = KotlinRegex(#"^\s*[-*+]\s+(.*)$"#)
    static let orderedRegex = KotlinRegex(#"^\s*(\d+)[.)]\s+(.*)$"#)

    /// 把一段回复切成块。空串返回空数组，绝不抛异常。
    public static func parse(_ source: String) -> [MarkdownBlock] {
        let lines = source.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var blocks: [MarkdownBlock] = []
        var index = 0
        while index < lines.count {
            let line = lines[index]
            let trimmed = line.trimmed
            if trimmed.isEmpty {
                index += 1
            } else if trimmed.hasPrefix("```") {
                let language = trimmed.removing(prefix: "```").trimmed
                var code: [String] = []
                index += 1
                while index < lines.count && !lines[index].trimmed.hasPrefix("```") {
                    code.append(lines[index])
                    index += 1
                }
                if index < lines.count { index += 1 }
                blocks.append(.codeBlock(language: language, code: code.joined(separator: "\n")))
            } else if trimmed == "---" || trimmed == "***" || trimmed == "___" {
                blocks.append(.divider)
                index += 1
            } else if let heading = headingMatch(line) {
                blocks.append(.heading(level: heading.level, text: heading.text))
                index += 1
            } else if trimmed.hasPrefix("$$") {
                var math = ""
                let rest = trimmed.removing(prefix: "$$")
                if let close = rest.range(of: "$$") {
                    math += String(rest[rest.startIndex..<close.lowerBound])
                    index += 1
                } else {
                    if !rest.trimmed.isEmpty { math += rest }
                    index += 1
                    while index < lines.count {
                        let current = lines[index]
                        if current.trimmed.hasSuffix("$$") {
                            math += "\n" + current.trimmed.removing(suffix: "$$")
                            index += 1
                            break
                        }
                        math += "\n" + current
                        index += 1
                    }
                }
                blocks.append(.mathBlock(latex: math.trimmed))
            } else if trimmed.hasPrefix(">") {
                var quote: [String] = []
                while index < lines.count && lines[index].trimmed.hasPrefix(">") {
                    quote.append(lines[index].trimmed.removing(prefix: ">")
                        .removing(prefix: " ").trimmingCharacters(in: .whitespaces))
                    index += 1
                }
                blocks.append(.quote(text: quote.joined(separator: "\n")))
            } else if bulletRegex.matches(line) {
                var items: [String] = []
                while index < lines.count, let match = bulletRegex.firstMatch(in: lines[index]) {
                    items.append(match.group(1, in: lines[index]) ?? "")
                    index += 1
                }
                blocks.append(.bullets(items: items))
            } else if orderedRegex.matches(line) {
                var items: [String] = []
                while index < lines.count, let match = orderedRegex.firstMatch(in: lines[index]) {
                    items.append(match.group(2, in: lines[index]) ?? "")
                    index += 1
                }
                blocks.append(.ordered(items: items))
            } else {
                var paragraph: [String] = []
                while index < lines.count {
                    let current = lines[index]
                    let currentTrimmed = current.trimmed
                    if currentTrimmed.isEmpty
                        || currentTrimmed.hasPrefix("```")
                        || currentTrimmed.hasPrefix("$$")
                        || currentTrimmed.hasPrefix(">")
                        || headingRegex.matches(current)
                        || bulletRegex.matches(current)
                        || orderedRegex.matches(current)
                        || currentTrimmed == "---" || currentTrimmed == "***" || currentTrimmed == "___" {
                        break
                    }
                    paragraph.append(current)
                    index += 1
                }
                if !paragraph.isEmpty {
                    blocks.append(.paragraph(text: paragraph.joined(separator: "\n")))
                }
            }
        }
        return blocks
    }

    /// 标题：`#`..`######` + 至少一个空白 + 正文。Kotlin 端要求整行匹配。
    static func headingMatch(_ line: String) -> (level: Int, text: String)? {
        guard headingRegex.matches(line) else { return nil }
        guard let match = headingRegex.firstMatch(in: line.trimmed) else { return nil }
        let marks = match.group(1, in: line.trimmed) ?? ""
        let text = match.group(2, in: line.trimmed) ?? ""
        return (marks.count, text)
    }
}
