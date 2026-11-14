// 来源：app/src/main/java/com/myfriend/app/domain/MemoryScreening.kt（第 13–78 行）
//
// 这里只放**被多个模块共用**的两个记忆原语：抽屉（MemoryDrawer）与标签编解码（MemoryTags）。
// 它们原本和「记忆筛查」在同一个 Kotlin 文件里，但 ImportanceScoring / MemoryLifecycle /
// Recall 都要用到，所以单独落在 Domain 里，避免三个文件各抄一份、日后改一处漏两处。
//
// 完整的记忆筛查（候选判定、二次落库、相似度）不在本次移植范围内，
// 它自己的文件里应当 `import` 这里的类型，不要重新声明。

import Foundation

/// 三个抽屉：他是什么样的人 / 关于你 / 共同经历与约定。
///
/// 字符串原名与 Kotlin 端一致，因为它会出现在数据库列、备份文件和模型输出里。
public enum MemoryDrawer: String, CaseIterable, Sendable {
    case selfDrawer = "SELF"
    case user = "USER"
    case event = "EVENT"

    /// 从数据库 / 备份里的字符串解析；空 / 未知一律当作 USER（与 Kotlin 的 parse 一致）。
    public static func parse(_ raw: String?) -> MemoryDrawer {
        let key = (raw ?? "").uppercased().trimmed
        return MemoryDrawer(rawValue: key) ?? .user
    }

    /// 把模型给的 category 名映射到抽屉。
    /// 兼容旧枚举（FACT/PREFERENCE/EVENT/RELATIONSHIP/OTHER）与中英文自定义标签。
    public static func fromCategoryName(_ categoryName: String) -> MemoryDrawer {
        let key = categoryName.trimmed.uppercased()
        if key.isEmpty { return .user }
        if key.contains("SELF") || key.contains("PERSONA") || key.contains("TRAIT")
            || key.contains("HABIT") || key.contains("STYLE") || key.contains("CHARACTER")
            || key.contains("自我") || key.contains("人格") || key.contains("性格") {
            return .selfDrawer
        }
        if key.contains("EVENT") || key.contains("EXPERIENCE") || key.contains("PROMISE")
            || key.contains("AGREEMENT") || key.contains("PLAN") || key.contains("RELATIONSHIP")
            || key.contains("MEMORY") || key.contains("约定") || key.contains("经历")
            || key.contains("关系") {
            return .event
        }
        return .user
    }

    /// 抽屉是「他记得的是关于谁的」，比数据库里的 category 更接近人话。
    public var label: String {
        switch self {
        case .selfDrawer: return "关于他自己"
        case .user: return "关于对方"
        case .event: return "你们之间的经历"
        }
    }
}

/// `tags` 字段的 JSON 编解码：数据库里存的是 JSON 数组，如 `["妹妹","失眠"]`。
///
/// 坏数据（不是数组、空串、nil、超长）一律当「没有标签」——
/// 标签只是给他自己查记忆用的辅助，不能因为一条脏 JSON 让整次召回失败。
public enum MemoryTags {

    /// 一条记忆最多几个标签：多了就不叫标签，叫关键词堆。
    public static let MAX_TAGS = 5

    /// 单个标签最多几个字：标签是「妹妹」「失眠」，不是一句话。
    public static let MAX_TAG_CHARS = 12

    /// 解析库里的标签列；坏数据一律空列表。
    public static func decode(_ raw: String?) -> [String] {
        let text = (raw ?? "").trimmed
        if text.isEmpty || text == "null" { return [] }
        guard let data = text.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data),
              let array = parsed as? [Any] else {
            return []
        }
        var pieces: [String] = []
        for element in array {
            if let string = element as? String {
                pieces.append(string)
            } else if element is NSNull {
                continue
            } else if let number = element as? NSNumber {
                pieces.append(number.stringValue)
            }
        }
        return sanitize(pieces)
    }

    /// 落库用：没有标签时写 nil（不写空数组，省得每次读还要判空一次）。
    public static func encode(_ tags: [String]) -> String? {
        let clean = sanitize(tags)
        if clean.isEmpty { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: clean) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// 清洗：去空白、去重、截长度与条数；不认识的字符原样保留（人名地名什么都有）。
    public static func sanitize(_ tags: [String]) -> [String] {
        if tags.isEmpty { return [] }
        var seen = Set<String>()
        var out: [String] = []
        for raw in tags {
            let tag = raw.trimmed.replacingOccurrences(of: "\n", with: " ").trimmed
            if tag.isEmpty || tag == "null" { continue }
            let clipped = String(tag.prefix(MAX_TAG_CHARS))
            if seen.insert(clipped).inserted {
                out.append(clipped)
            }
            if out.count >= MAX_TAGS { break }
        }
        return out
    }
}
