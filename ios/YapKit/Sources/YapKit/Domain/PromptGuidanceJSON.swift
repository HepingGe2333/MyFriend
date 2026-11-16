import Foundation

// ---------------------------------------------------------------------------
// 人设引导（世界预设 / 话风 / 当前活动）共用的 JSON 宽容解析。
//
// 为什么单独一个文件：这三段 JSON 有三个来源 —— 用户手填、AI 补全、备份导入。
// 三个来源都不会保证「干净」：
// - AI 爱在 JSON 前后加一句解释、或者包在 ```json 代码块里（§6.1 / §6.2 的实测就是这样）；
// - 手填的人会写注释、写单引号、写中文逗号；
// - 备份来自另一端的旧版本，字段可能缺、类型可能错。
//
// 所以规则只有一条：**永远不为了让解析「严格」而丢掉人已经写下的东西**。
// 认不出就退化成 nil / 默认值，绝不抛异常、绝不把坏 JSON 原样塞进提示词。
//
// 提示词里的这几段是**中文脚手架**（与 Kit 里其他 prompt 一致），不走 `Localizable.strings`：
// 那是「给模型看的说明书」，不是用户界面文案。
// ---------------------------------------------------------------------------

public enum PromptGuidanceJSON {

    /// 存库前的清洗：只去首尾空白、空串 → nil。
    ///
    /// **不截断**：0.12 起「存储 / 解析 / 哈希一律保留原文」，截断只允许发生在
    /// 渲染提示词那一步（例如 `WorldPreset.renderForPrompt` 对 notes 的 1200 字上限）。
    /// 以前这里有一个 16000 字的硬上限，超过就从中间切断 JSON —— 结果是库里留下
    /// 一段解不出来的坏 JSON，用户写的东西静默消失。
    public static func clean(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return trimmed
    }

    /// 从「模型可能加了前后缀」的文本里取出第一个 JSON 对象。
    ///
    /// 处理三种常见脏数据：
    /// 1. ```json … ``` / ``` … ``` 代码块（取代码块里的内容）；
    /// 2. JSON 前后各有一句中文解释（从第一个 `{` 到最后一个 `}`）；
    /// 3. 尾随逗号（`}`,`]` 前多一个逗号）—— 手写时最常见。
    public static func object(from raw: String?) -> [String: Any]? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }

        for candidate in candidates(from: trimmed) {
            if let object = parseObject(candidate) { return object }
        }
        return nil
    }

    /// 可能的 JSON 文本片段，按「最像」到「最不像」的顺序。
    static func candidates(from text: String) -> [String] {
        var result: [String] = []
        // 1. 代码块里的内容（可能不止一个块，逐个试）。
        var cursor = text.startIndex
        while let start = text.range(of: "```", range: cursor..<text.endIndex) {
            guard let end = text.range(of: "```", range: start.upperBound..<text.endIndex) else { break }
            var body = String(text[start.upperBound..<end.lowerBound])
            // 去掉语言标记（json / JSON / javascript）。
            if let newline = body.firstIndex(of: "\n") {
                let firstLine = body[body.startIndex..<newline].trimmingCharacters(in: .whitespaces)
                if !firstLine.contains("{"), !firstLine.contains("}") {
                    body = String(body[body.index(after: newline)...])
                }
            }
            result.append(body)
            cursor = end.upperBound
        }
        // 2. 全文，以及第一个 `{` 到最后一个 `}`。
        result.append(text)
        if let open = text.firstIndex(of: "{"), let close = text.lastIndex(of: "}"), open < close {
            result.append(String(text[open...close]))
        }
        return result
    }

    static func parseObject(_ text: String) -> [String: Any]? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        for candidate in [trimmed, removingTrailingCommas(trimmed)] {
            guard let data = candidate.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                continue
            }
            return object
        }
        return nil
    }

    /// 去掉 `,}` / `,]`（含中间空白）这种尾随逗号。
    static func removingTrailingCommas(_ text: String) -> String {
        var out = ""
        var index = text.startIndex
        while index < text.endIndex {
            let ch = text[index]
            if ch == "," {
                var lookahead = text.index(after: index)
                var sawWhitespace = false
                while lookahead < text.endIndex, text[lookahead].isWhitespace {
                    lookahead = text.index(after: lookahead)
                    sawWhitespace = true
                }
                if lookahead < text.endIndex, text[lookahead] == "}" || text[lookahead] == "]" {
                    // 丢掉这个逗号（它的空白由后面的字符自己带）。
                    index = text.index(after: index)
                    if sawWhitespace { out.append(" ") }
                    continue
                }
            }
            out.append(ch)
            index = text.index(after: index)
        }
        return out
    }

    // MARK: - 字段读取（类型错了就当没写）

    /// 取字符串字段：去首尾空白，**不截断**（存储/解析/哈希必须保留原文）。
    public static func string(_ object: [String: Any], _ key: String) -> String? {
        let raw: String?
        if let text = object[key] as? String {
            raw = text
        } else if let number = object[key] as? NSNumber {
            raw = number.stringValue
        } else {
            raw = nil
        }
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    public static func int(_ object: [String: Any], _ key: String) -> Int? {
        if let number = object[key] as? NSNumber { return number.intValue }
        if let text = object[key] as? String { return Int(text.trimmingCharacters(in: .whitespaces)) }
        return nil
    }

    /// 字符串数组：容忍「一个字符串」「数组里混了数字」「数组里混了对象」。
    ///
    /// 不做条数/单条长度截断：保留用户写下的每一项原文（去空白、去重是解析的事）。
    public static func stringList(_ object: [String: Any], _ key: String) -> [String] {
        var values: [String] = []
        if let array = object[key] as? [Any] {
            for element in array {
                if let text = element as? String { values.append(text) }
                else if let number = element as? NSNumber { values.append(number.stringValue) }
            }
        } else if let single = object[key] as? String {
            values = single.split(whereSeparator: { "，,、\n；;".contains($0) }).map(String.init)
        }
        return PersonaTags.normalize(values)
    }

    /// 对象数组（日程 / 关系）。
    ///
    /// 不按条数截断：条数上限只是编辑器的建议值（写不下就滚动），不是存储限制。
    public static func objectList(_ object: [String: Any], _ key: String) -> [[String: Any]] {
        guard let array = object[key] as? [Any] else { return [] }
        return array.compactMap { $0 as? [String: Any] }
    }
}

/// 提示词里要用的几个「人话时间」小工具。
///
/// 为什么不用 `DateFormatter`：提示词里的星期与时刻只要固定形状（「周三」「19:40」），
/// 而 `DateFormatter` 会跟着系统语言变 —— 换到英文系统就会在一段中文提示词里出现 "Wed"。
enum PromptClock {

    static let chineseWeekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"]

    /// 毫秒时间戳 → 日历部件（默认系统时区，与 `ServiceTime.calendar()` 一致）。
    static func components(_ millis: Int64, calendar: Calendar = ServiceTime.calendar()) -> DateComponents {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000.0)
        return calendar.dateComponents([.weekday, .hour, .minute], from: date)
    }

    /// 「周三」
    static func weekdayText(_ millis: Int64, calendar: Calendar = ServiceTime.calendar()) -> String {
        let weekday = components(millis, calendar: calendar).weekday ?? 1
        let index = min(max(weekday - 1, 0), 6)
        return chineseWeekdays[index]
    }

    /// 「19:40」
    static func clockText(_ millis: Int64, calendar: Calendar = ServiceTime.calendar()) -> String {
        let parts = components(millis, calendar: calendar)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }

    /// 「07-15」这种日期（提示词里只在说跨天日程时才用得上）。
    static func dayIndex(_ millis: Int64, calendar: Calendar = ServiceTime.calendar()) -> Int {
        components(millis, calendar: calendar).weekday ?? 1
    }
}
