import Foundation

// ---------------------------------------------------------------------------
// 聊天记录检索的排序规则（纯逻辑）。
//
// 规范在 `spec/search/README.md` 的 Ranking rules / Filters，用例在
// `spec/search/cases/ranking.json`（Android 生成，iOS 必须复现同一个顺序）。
//
// 排序键（全部按顺序比较，前一个相等才看后一个）：
//   1. 命中的**不同查询词元**个数，降序 —— 「同时说到哈尔滨和外套」比「只说到哈尔滨」更相关；
//   2. 共享的 `ChineseTerms.relevance`，降序 —— 二元组覆盖率 + 余弦，中英文同一套；
//   3. `createdAt` 降序 —— 同样相关时近的优先；
//   4. `id` 升序 —— 全平局时仍然稳定（用 UTF-16 码元序，与 Kotlin `compareTo` 一致）。
//
// 过滤（`personaId` / `since`）在排序**之前**做：过滤只该减少候选，
// 不该改变剩下那些命中之间的相对顺序。
//
// 这一层不碰数据库：候选集由 `MessageStore.search` 粗筛出来（LIKE），精排在这里，
// 所以规范用例可以直接喂内存里的消息数组。
// ---------------------------------------------------------------------------

/// 参与排序的一条候选消息（只要排序需要的四个字段，避免让 Domain 依赖数据层实体）。
public struct SearchRankItem: Equatable, Sendable {
    public var id: String
    public var content: String
    public var createdAt: Int64
    public var personaId: String

    public init(id: String, content: String, createdAt: Int64, personaId: String) {
        self.id = id
        self.content = content
        self.createdAt = createdAt
        self.personaId = personaId
    }
}

/// 一条命中 + 它的两个分值（分页游标要用它们，界面不直接展示）。
public struct SearchRankedHit: Equatable, Sendable {
    public var item: SearchRankItem
    /// 命中了查询里几个**不同**词元（降序第一键）。
    public var matchCount: Int
    /// `ChineseTerms.relevance(query, content)`（降序第二键）。
    public var relevance: Double

    public init(item: SearchRankItem, matchCount: Int, relevance: Double) {
        self.item = item
        self.matchCount = matchCount
        self.relevance = relevance
    }
}

/// 分页游标 = 上一页最后一条的完整排序位置。
///
/// 为什么不能只用 `createdAt`：这次的顺序是「分值优先」，不是时间顺序；
/// 只带时间戳就无法判断「下一条是不是已经翻过了」。四个键一起带才和排序键一一对应。
public struct SearchCursor: Equatable, Sendable {
    public var matchCount: Int
    public var relevance: Double
    public var createdAt: Int64
    public var id: String

    public init(matchCount: Int, relevance: Double, createdAt: Int64, id: String) {
        self.matchCount = matchCount
        self.relevance = relevance
        self.createdAt = createdAt
        self.id = id
    }

    public init(_ hit: SearchRankedHit) {
        self.matchCount = hit.matchCount
        self.relevance = hit.relevance
        self.createdAt = hit.item.createdAt
        self.id = hit.item.id
    }
}

public enum SearchRanking {

    /// 一条消息算不算命中。
    ///
    /// 两条路：正文里字面包含查询（大小写不敏感），**或**查询切出来的任一词元出现在正文里。
    /// 第二条是中文的命根子：查「项目压力」时二元组能命中「项目压得我睡不着」，
    /// 纯 LIKE 是查不到的（这也是为什么粗筛 SQL 要把词元一起 LIKE）。
    public static func isHit(query: String, content: String) -> Bool {
        let trimmed = query.trimmed
        if trimmed.isEmpty { return false }
        if content.isEmpty { return false }
        if content.range(of: trimmed, options: [.caseInsensitive]) != nil { return true }
        return matchedTermCount(query: trimmed, content: content) > 0
    }

    /// 正文命中了查询里的几个**不同**词元。
    ///
    /// 词元来自共享的 `ChineseTerms.queryTerms`（已归一化、去重、限量），
    /// 正文侧也走 `ChineseTerms.normalize` 再比：全角/大小写/标点两边一致，
    /// 否则「ＨＡＲＢＩＮ」查「harbin」会漏。
    public static func matchedTermCount(query: String, content: String) -> Int {
        let trimmed = query.trimmed
        if trimmed.isEmpty || content.isEmpty { return 0 }
        let terms = ChineseTerms.queryTerms(trimmed)
        if terms.isEmpty { return 0 }
        let normalized = ChineseTerms.normalize(content)
        if normalized.isEmpty { return 0 }
        var count = 0
        for term in terms where normalized.range(of: term) != nil {
            count += 1
        }
        return count
    }

    /// 过滤 + 精排。返回的数组只含命中，顺序即界面展示顺序。
    ///
    /// - Parameters:
    ///   - items: 粗筛出来的候选（可以含不命中的）。
    ///   - query: 查询词，内部会 trim；trim 之后为空则没有任何命中。
    ///   - personaId: 只看某个人格（`nil` = 全部）。
    ///   - since: 只要这个时刻之后（**含**边界）的消息（`nil` = 全部时间）。
    public static func rank(
        _ items: [SearchRankItem],
        query: String,
        personaId: String? = nil,
        since: Int64? = nil
    ) -> [SearchRankedHit] {
        let trimmed = query.trimmed
        if trimmed.isEmpty || items.isEmpty { return [] }

        // 过滤在排序之前：只减少候选，不改变幸存者之间的相对顺序。
        let filtered = items.filter { item in
            if let personaId, item.personaId != personaId { return false }
            if let since, item.createdAt < since { return false }
            return true
        }
        if filtered.isEmpty { return [] }

        var ranked: [SearchRankedHit] = []
        ranked.reserveCapacity(filtered.count)
        for item in filtered {
            guard isHit(query: trimmed, content: item.content) else { continue }
            ranked.append(SearchRankedHit(
                item: item,
                matchCount: matchedTermCount(query: trimmed, content: item.content),
                relevance: ChineseTerms.relevance(query: trimmed, document: item.content)
            ))
        }
        return sorted(ranked)
    }

    /// 按规范的四键排序（外部也用它给「已经算好分值」的数组排序，避免重复算 relevance）。
    public static func sorted(_ hits: [SearchRankedHit]) -> [SearchRankedHit] {
        hits.sorted(by: precedes)
    }

    /// `lhs` 是否排在 `rhs` 前面（严格弱序，全平局时返回 false）。
    public static func precedes(_ lhs: SearchRankedHit, _ rhs: SearchRankedHit) -> Bool {
        if lhs.matchCount != rhs.matchCount { return lhs.matchCount > rhs.matchCount }
        if lhs.relevance != rhs.relevance { return lhs.relevance > rhs.relevance }
        if lhs.item.createdAt != rhs.item.createdAt { return lhs.item.createdAt > rhs.item.createdAt }
        return Lexicographic.utf16Less(lhs.item.id, rhs.item.id)
    }

    /// `hit` 是否严格排在游标**之后**（下一页该从这里继续）。
    public static func comesAfter(_ hit: SearchRankedHit, cursor: SearchCursor) -> Bool {
        let anchor = SearchRankedHit(
            item: SearchRankItem(id: cursor.id, content: "", createdAt: cursor.createdAt, personaId: ""),
            matchCount: cursor.matchCount,
            relevance: cursor.relevance
        )
        return precedes(anchor, hit)
    }
}
