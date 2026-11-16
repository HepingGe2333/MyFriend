import Foundation

// ---------------------------------------------------------------------------
// 聊天记录检索的「摘要 + 高亮」纯函数。
//
// 规范在仓库根的 `spec/search/README.md`（Snippet rules），两侧实现必须逐条一致：
// Android 与 iOS 的测试读的是**同一份** `spec/search/cases/snippet.json`。
// 所以这个文件刻意做成「没有数据库、没有 UI、没有时钟」的纯计算：
// 输入正文 + 查询 + 窗口宽度，输出一段能直接画的摘要和若干高亮区间。
//
// 几个容易踩的点（都按规范原文来，不自己发挥）：
// - 偏移一律按 **UTF-16 码元**算（Kotlin `String` / Swift `String.utf16` 的共性），
//   这样两端把同一个 `[start, end)` 交给各自的富文本 API 时落在同一个位置；
// - 「省略号」也是码元：`…`（U+2026）是 BMP 字符，正好 1 个码元，
//   所以有前导省略号时所有高亮偏移 +1，简单且不会因为 emoji 漂移；
// - 命中点前的上下文固定 30 个码元（规范写死 30，`window` 只决定窗口总长）；
// - 找不到命中也得给一段「开头预览」，否则搜索页会出现一整列空白行。
// ---------------------------------------------------------------------------

/// 一段摘要，以及这段摘要里需要高亮的区间。
///
/// `highlights` 是 `text` 内部的 UTF-16 半开区间 `[start, end)`，可直接交给
/// `AttributedString` / `NSRange`；`segments()` 则是给 SwiftUI 用的「切开的样子」。
public struct SearchSnippet: Equatable, Sendable {

    /// 规范里写死的窗口宽度：命中点前后各约 30 个码元。
    public static let defaultWindow = 60

    /// 命中点之前固定留多少个码元（`spec/search/README.md` 第 4 条）。
    public static let contextBefore = 30

    /// 摘要正文（可能带前导 / 结尾的 `…`）。
    public let text: String
    /// 高亮区间，元素是 `text` 内部的 UTF-16 偏移，升序且互不重叠。
    public let highlights: [Range<Int>]

    public init(text: String, highlights: [Range<Int>]) {
        self.text = text
        self.highlights = highlights
    }

    public static let empty = SearchSnippet(text: "", highlights: [])

    public var isEmpty: Bool { text.isEmpty }

    /// 有没有命中（`false` 表示这只是「开头预览」）。
    public var hasHighlights: Bool { !highlights.isEmpty }

    /// 是否是「前面被截掉了」（渲染时可以据此决定要不要画淡出的左边框）。
    public var isHeadTruncated: Bool { text.hasPrefix("…") }

    /// 是否是「后面被截掉了」。
    public var isTailTruncated: Bool { text.hasSuffix("…") }

    // MARK: - 规范实现

    /// 按 `spec/search/README.md` 的 Snippet rules 生成摘要。
    ///
    /// - Parameters:
    ///   - content: 消息正文（原样，不做 trim）。
    ///   - query: 查询词，**调用方已经 trim 过**（空串直接得到空结果）。
    ///   - window: 窗口宽度（UTF-16 码元），默认 60 与用例一致。
    public static func build(
        content: String,
        query: String,
        window: Int = defaultWindow
    ) -> SearchSnippet {
        // 第 1 条：空查询什么都不返回（不是「返回开头预览」）。
        if query.isEmpty || window <= 0 { return .empty }

        let units = Array(content.utf16)
        if units.isEmpty { return .empty }

        // 第 2 条：第一个大小写不敏感的命中位置（UTF-16 偏移）。
        let occurrences = allOccurrences(of: query, in: content)

        guard let first = occurrences.first else {
            // 第 3 条：没有命中 → 开头 window 个码元 + 省略号，无高亮。
            let end = min(units.count, window)
            let head = string(from: units, 0..<end)
            return SearchSnippet(
                text: end < units.count ? head + ellipsis : head,
                highlights: []
            )
        }

        // 第 4 条：命中点前 30 个码元起，向右取满 window；不够就回退到左边。
        var start = max(0, first.lowerBound - contextBefore)
        let end = min(units.count, start + window)
        if start > 0 && end - start < window {
            start = max(0, end - window)
        }

        let leading = start > 0 ? ellipsis : ""
        let trailing = end < units.count ? ellipsis : ""
        let body = string(from: units, start..<end)

        // 第 5 条：窗口内**所有**命中都高亮，偏移相对返回的 text（含前导省略号）。
        let shift = leading.utf16.count
        let highlights = occurrences.compactMap { range -> Range<Int>? in
            guard range.lowerBound >= start, range.upperBound <= end else { return nil }
            let lower = range.lowerBound - start + shift
            let upper = range.upperBound - start + shift
            return lower..<upper
        }

        return SearchSnippet(text: leading + body + trailing, highlights: highlights)
    }

    // MARK: - 渲染辅助

    /// 摘要被切成「普通段 / 高亮段」的样子，按顺序拼起来正好等于 `text`。
    ///
    /// 为什么不让界面自己算：高亮偏移是按 UTF-16 给的，而 SwiftUI 的
    /// `Text` 只认 `String` / `AttributedString`，两边换算一次就够，别在每个视图里各写一份。
    /// 越界或重叠的区间会被安静地跳过 —— 界面宁可少一段高亮，也不能崩。
    public struct Segment: Equatable, Sendable {
        public let text: String
        public let isHighlight: Bool

        public init(text: String, isHighlight: Bool) {
            self.text = text
            self.isHighlight = isHighlight
        }
    }

    public func segments() -> [Segment] {
        if text.isEmpty { return [] }
        let units = Array(text.utf16)
        var result: [Segment] = []
        var cursor = 0
        for range in highlights {
            let start = max(cursor, min(range.lowerBound, units.count))
            let end = max(start, min(range.upperBound, units.count))
            if start > cursor {
                result.append(Segment(text: Self.string(from: units, cursor..<start), isHighlight: false))
            }
            if end > start {
                result.append(Segment(text: Self.string(from: units, start..<end), isHighlight: true))
            }
            cursor = max(cursor, end)
        }
        if cursor < units.count {
            result.append(Segment(text: Self.string(from: units, cursor..<units.count), isHighlight: false))
        }
        return result
    }

    // MARK: - 内部

    static let ellipsis = "…"

    /// 用 UTF-16 切片还原字符串。
    ///
    /// 直接 `String(decoding:as:)` 而不是 `String.Index(utf16Offset:)`：后者在
    /// 「偏移落在代理对中间」时行为未定义，而用例虽然避开了 emoji，真机上不是。
    /// 切在代理对中间会得到一个替换字符 —— 这是可接受的降级（不崩、长度仍然对）。
    static func string(from units: [UInt16], _ range: Range<Int>) -> String {
        let lower = max(0, min(range.lowerBound, units.count))
        let upper = max(lower, min(range.upperBound, units.count))
        return String(decoding: units[lower..<upper], as: UTF16.self)
    }

    /// `content` 里所有大小写不敏感的命中（UTF-16 偏移，升序、互不重叠）。
    ///
    /// 用 `String.range(of:options:.caseInsensitive)` 而不是「自己 lowercased 再找」：
    /// 有些字符转小写会改变长度（`İ` → `i̇`），按码元硬比会把偏移算歪。
    static func allOccurrences(of query: String, in content: String) -> [Range<Int>] {
        guard !query.isEmpty, !content.isEmpty else { return [] }

        var result: [Range<Int>] = []
        var cursor = content.startIndex
        while cursor < content.endIndex {
            guard let found = content.range(
                of: query, options: [.caseInsensitive], range: cursor..<content.endIndex
            ) else { break }
            let start = content.utf16.distance(from: content.startIndex, to: found.lowerBound)
            let end = content.utf16.distance(from: content.startIndex, to: found.upperBound)
            if end > start { result.append(start..<end) }
            cursor = found.upperBound > found.lowerBound
                ? found.upperBound
                : content.index(after: found.lowerBound)
        }
        return result
    }
}
