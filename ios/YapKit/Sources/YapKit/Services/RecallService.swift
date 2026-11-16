import Foundation

// 来源：app/src/main/java/com/yap/app/data/repo/RecallRepository.kt
//
// 回忆：按想要的强度，把「记忆 / 聊天记录 / 他独自上网看到的」翻一遍。
//
// 分工和记忆整理保持一致：
// - 该翻多深、怎么排序、怎么写成中文段落，全在 Domain/Recall 里，有单测；
// - 这里只负责按档位去数据库取数、把实体转成领域视图，再顺手记下「这些记忆又被用到了」。
//
// 安全边界：没有记忆开关、找不到人格、查询为空、数据库报错，都返回一句人话（isEmpty = true），
// 不抛异常、不让模型以为工具坏了。
//
// 与 Android 的差别：Android 在回忆之后会异步触发 MemoryReconsolidator（用心情轻轻改写 1~2 条记忆）；
// iOS 的漂移服务还没移植，这里留一个可选回调 `afterRecallDrift`：App 层接上以后行为一致，
// 不接就是「只回忆、不改写」——比假装做了更诚实。

public final class RecallService {

    static let maxQueryChars = 60
    static let maxMemories = 200
    static let searchLimit = 80
    static let indexLimit = 120
    static let tagLimit = 30
    static let tagLookupTerms = 3
    static let timeLimit = 40
    static let vectorLimit = 16
    static let vectorBonus = 0.25
    static let maxTouchedMemories = 6
    static let normalSearchLimit = 20
    static let deepSearchLimit = 40
    static let scanSessions = 12
    static let supplementThreshold = 3
    static let supplementPerSession = 2

    /// 「翻箱倒柜」时每个会话读多少条窗口（0.11.1 审计 P2-7）。
    ///
    /// 以前这里用 `messages.bySession` 把整段会话（含 `content` / `attachmentsJson` /
    /// `toolTraceJson`）物化到内存再 filter/suffix —— 0.10.2 P2-1 点名的第五处没清，
    /// 而回忆是用户点一下「深一点想」就会走的高频动作。现在只读最近 200 条对话消息
    /// （`recentDialogue` 已经在 SQL 侧过滤 system 与空正文），再在窗口内打分。
    static let recallWindowMessages = 200
    static let normalDigestChars = 500
    static let deepDigestChars = 1200

    private let stores: DatabaseStores
    private let memoryRepository: MemoryRepository
    private let browsingDigest: (String) -> String?
    private let afterRecallDrift: ((String, [String], Int64) -> Void)?
    private let now: () -> Int64

    public init(
        stores: DatabaseStores,
        memoryRepository: MemoryRepository,
        browsingDigest: @escaping (String) -> String? = { _ in nil },
        afterRecallDrift: ((String, [String], Int64) -> Void)? = nil,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.stores = stores
        self.memoryRepository = memoryRepository
        self.browsingDigest = browsingDigest
        self.afterRecallDrift = afterRecallDrift
        self.now = now
    }

    /// 按 `depth` 的强度回忆一次。
    ///
    /// - Parameters:
    ///   - query: 想找的内容；为空时退化成「最近 / 重要优先」，不报错
    ///   - now: 当前时间（由调用方传入，便于单测与复盘）
    public func recall(personaId: String, query: String, depth: RecallDepth, now: Int64? = nil) -> RecallResult {
        let at = now ?? self.now()
        let term = String(query.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxQueryChars))
        let nothing = RecallResult(hits: [], rendered: Recall.emptyAnswer(query: term, depth: depth), isEmpty: true)
        if personaId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nothing }
        // 记忆关了就等于「他确实没有这段记忆」，安静地返回一句想不起来。
        // 1.9 起记忆内置开启：没有「关掉记忆」这个开关了（§一）。
        guard ((try? stores.personas.byId(personaId)) ?? nil) != nil else { return nothing }

        var hits: [RecallHit] = []
        hits.append(contentsOf: memoryHits(personaId: personaId, term: term, depth: depth, now: at))
        if depth.includeChatSearch {
            hits.append(contentsOf: chatHits(personaId: personaId, term: term, depth: depth, now: at))
        }
        if depth.includeBrowsing {
            hits.append(contentsOf: browsingHits(personaId: personaId, depth: depth, now: at))
        }
        let ordered = Array(Recall.rank(hits, query: term, depth: depth, now: at).prefix(depth.maxHits))
        if ordered.isEmpty { return nothing }
        markMemoriesUsed(ordered, now: at)
        let ids = ordered
            .filter { $0.kind == .memory && !$0.refId.isEmpty }
            .map(\.refId)
        let uniqueIds = Array(Set(ids)).prefix(3)
        if let afterRecallDrift, !uniqueIds.isEmpty {
            // 只要这次回忆已经成了，漂移是「顺手做的事」，不挡返回（回调方自己决定异步）。
            afterRecallDrift(personaId, Array(uniqueIds), at)
        }
        return RecallResult(hits: ordered, rendered: Recall.render(ordered, depth: depth, query: term, now: at),
                            isEmpty: false)
    }

    // MARK: - 记忆

    /// 记忆候选：倒排索引 ∪ LIKE 兜底 ∪ 标签命中 ∪ 时间指代 ∪ 全量兜底 ∪ 向量。
    ///
    /// 排序与档位筛选全交给纯领域 Recall；这里只负责把可能相关的东西都捞上来。
    private func memoryHits(personaId: String, term: String, depth: RecallDepth, now: Int64) -> [RecallHit] {
        var found: [String: MemoryEntity] = [:]
        var foundOrder: [String] = []
        var indexHits = Set<String>()
        func put(_ memory: MemoryEntity) {
            if found[memory.id] == nil { foundOrder.append(memory.id) }
            found[memory.id] = memory
        }

        let terms = ChineseTerms.queryTerms(term)
        if !term.isEmpty {
            // 老用户库里的记忆是 v7 之前写的，没有索引：第一次回忆时顺手补一次（失败也无所谓）。
            memoryRepository.ensureTermIndex()

            // 1) 倒排索引命中
            if !terms.isEmpty {
                for id in memoryIdsByTerms(terms, limit: Self.indexLimit) {
                    guard let memory = (try? stores.memories.byId(id)) ?? nil else { continue }
                    if memory.personaId != personaId { continue }
                    indexHits.insert(memory.id)
                    put(memory)
                }
            }

            // 2) LIKE 兜底：只认字面，换个说法就搜不到，但没有索引时它是主路径。
            let like = likeSafe(term)
            if !like.isEmpty {
                for memory in searchForPersona(personaId: personaId, query: like, limit: Self.searchLimit) {
                    put(memory)
                }
            }

            // 3) 标签：「他妹妹那件事」里的「妹妹」可能就是当初打的标签。
            for tag in terms.prefix(Self.tagLookupTerms) {
                for memory in byTag(personaId: personaId, tag: tag, limit: Self.tagLimit) {
                    put(memory)
                }
            }

            // 4) 时间指代：「去年冬天」这类说法，把有发生时间的记忆也捞上来。
            if ChineseTerms.hasTimeHint(term, now: now) {
                for memory in withTimeHint(personaId: personaId, limit: Self.timeLimit) {
                    put(memory)
                }
            }
        }

        // 5) 全量兜底（含归档）：状态筛选交给纯领域按档位做，避免这里再写一套规则。
        for memory in Array(((try? memoryRepository.getAllForPersona(personaId)) ?? []).prefix(Self.maxMemories)) {
            put(memory)
        }

        let inputs = foundOrder.compactMap { found[$0] }.compactMap { toRecallInput($0) }
        // 6) 向量：意思相近、字面不重叠时也能翻到。只在已经捞上来的候选里算。
        let vectorScores = vectorScoresOf(personaId: personaId, term: term, ids: Array(found.keys))
        return Recall.memoryHits(memories: inputs, query: term, depth: depth, now: now, indexHits: indexHits)
            .map { withVectorBonus($0, vectorScores: vectorScores) }
    }

    /// 向量命中给 `RecallHit.weight` 一个小加成（最多 0.25）。
    ///
    /// 加在 weight 上是因为 Recall 的排序把 weight 当「本身有多重要」直接计入总分：
    /// 0.25 与「倒排索引直接命中」那点加成同量级，意思是「除了字面，还有一层像」，
    /// 但远小于关键词命中（2.0）——所以它只能在**同一档相关度**的记忆之间把先后拨一拨。
    func withVectorBonus(_ hit: RecallHit, vectorScores: [String: Double]) -> RecallHit {
        guard hit.kind == .memory else { return hit }
        guard let similarity = vectorScores[hit.refId], similarity.isFinite, similarity > 0 else { return hit }
        let bonus = min(max(Self.vectorBonus * similarity, 0), Self.vectorBonus)
        var copy = hit
        copy.weight = min(max(hit.weight + bonus, 0), 1)
        return copy
    }

    private func vectorScoresOf(personaId: String, term: String, ids: [String]) -> [String: Double] {
        if term.isEmpty || ids.isEmpty { return [:] }
        memoryRepository.vectorIndex.ensureIndex(personaId: personaId)
        let hits = memoryRepository.vectorIndex.searchIn(memoryIds: ids, query: term, limit: Self.vectorLimit)
        return Dictionary(hits.map { ($0.memoryId, $0.similarity) }, uniquingKeysWith: { first, _ in first })
    }

    func toRecallInput(_ memory: MemoryEntity) -> RecallMemoryInput? {
        let content = memory.content.trimmingCharacters(in: .whitespacesAndNewlines)
        if content.isEmpty { return nil }
        let state: RecallMemoryState
        switch memory.status.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() {
        case "ARCHIVED": state = .archived
        case "DORMANT": state = .dormant
        default: state = .active
        }
        return RecallMemoryInput(
            id: memory.id,
            content: memory.content,
            drawer: memoryRepository.drawerValue(memory),
            importance: memory.importance,
            pinned: memory.pinned,
            lastUsedAt: memory.lastUsedAt,
            createdAt: memory.createdAt,
            state: state,
            strength: min(max(memory.strength, 0.0), 1.0),
            archivedAt: memory.archivedAt,
            tags: MemoryTags.decode(memory.tags),
            happenedAt: memory.happenedAt
        )
    }

    /// 被回忆到的记忆也算「又用了一次」；归档的只记「被想起过」，不动 lastUsedAt——
    /// 否则「很旧才彻底忘掉」的时间线会被一直推后。
    private func markMemoriesUsed(_ hits: [RecallHit], now: Int64) {
        for id in Array(Set(hits.filter { $0.kind == .memory && !$0.refId.isEmpty }.map(\.refId)))
            .prefix(Self.maxTouchedMemories) {
            guard let memory = (try? stores.memories.byId(id)) ?? nil else { continue }
            let archived = memoryRepository.isArchived(memory)
            memoryRepository.markUsed(id: id, now: now, bumpLastUsedAt: !archived)
        }
    }

    // MARK: - 聊天记录

    private func chatHits(personaId: String, term: String, depth: RecallDepth, now: Int64) -> [RecallHit] {
        var hits: [RecallHit] = []
        let like = likeSafe(term)
        if !like.isEmpty {
            let rows = messageSearch(like)
            var accepted: [RecallHit] = []
            for row in rows {
                guard row.message.personaId == personaId else { continue }
                let content = row.message.content.trimmingCharacters(in: .whitespacesAndNewlines)
                if content.isEmpty { continue }
                accepted.append(
                    Recall.chatHit(text: row.message.content, sessionTitle: row.sessionTitle,
                                   at: row.message.createdAt)
                )
            }
            let limit = depth == .deep ? Self.deepSearchLimit : Self.normalSearchLimit
            hits.append(contentsOf: accepted.prefix(limit))
        }
        // 翻箱倒柜时再按会话扫一遍最近的消息：关键词搜不到的旧话，常常就躺在最近的上下文里。
        if depth.scanRecentChats && (term.isEmpty || hits.count < Self.supplementThreshold) {
            let sessions = ((try? stores.sessions.byPersona(personaId)) ?? [])
                .sorted { ($0.lastMessageAt ?? $0.createdAt) > ($1.lastMessageAt ?? $1.createdAt) }
                .prefix(Self.scanSessions)
            for session in sessions {
                // 只读窗口：`recentDialogue` 在 SQL 里就过滤掉了 system 与「只有空白」的正文，
                // 长会话下不会为了翻两句旧话把整段历史读进内存。
                let messages = ((try? stores.messages.recentDialogue(
                    sessionId: session.id, limit: Self.recallWindowMessages
                )) ?? [])
                    .filter { $0.status != .error }
                if messages.isEmpty { continue }
                let picked: [MessageEntity]
                if term.isEmpty {
                    picked = Array(messages.suffix(Self.supplementPerSession))
                } else {
                    var filtered = messages.filter { Recall.keywordScore(text: $0.content, query: term) > 0 }
                    filtered = Array(filtered.suffix(Self.supplementPerSession))
                    picked = filtered.isEmpty ? Array(messages.suffix(1)) : filtered
                }
                for message in picked {
                    hits.append(
                        Recall.chatHit(
                            text: message.content,
                            sessionTitle: session.title,
                            at: message.createdAt,
                            role: message.role.rawValue
                        )
                    )
                }
            }
        }
        // 同一句话只算一次（文本 + 时间戳去重）。
        var seen = Set<String>()
        var unique: [RecallHit] = []
        for hit in hits {
            let key = "\(hit.text)|\(hit.at)"
            if seen.insert(key).inserted { unique.append(hit) }
        }
        return unique
    }

    struct MessageSearchRow {
        var message: MessageEntity
        var sessionTitle: String
    }

    private func messageSearch(_ query: String) -> [MessageSearchRow] {
        let sql = """
        SELECT m.*, s.title AS sessionTitle FROM messages m
        INNER JOIN chat_sessions s ON s.id = m.sessionId
        WHERE m.content LIKE '%' || ? || '%' COLLATE NOCASE
        ORDER BY m.createdAt DESC LIMIT 80
        """
        guard let statement = try? stores.database.prepare(sql) else { return [] }
        try? statement.bind([.text(query)])
        var rows: [MessageSearchRow] = []
        while (try? statement.step()) == true {
            let row = SQLiteRow(statement: statement)
            guard let message = try? MessageEntity(row: row) else { continue }
            rows.append(MessageSearchRow(message: message, sessionTitle: (try? row.text("sessionTitle")) ?? ""))
        }
        return rows
    }

    // MARK: - 他看到的

    private func browsingHits(personaId: String, depth: RecallDepth, now: Int64) -> [RecallHit] {
        guard let digest = browsingDigest(personaId) else { return [] }
        let trimmed = digest.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return [] }
        // 摘要只在进程内缓存里、没有时间戳，只能当作「最近看到的」，日期按 now 算。
        let limit = depth == .deep ? Self.deepDigestChars : Self.normalDigestChars
        return [Recall.browsingHit(digest: String(trimmed.prefix(limit)), at: now)]
    }

    // MARK: - 查询辅助

    private func memoryIdsByTerms(_ terms: [String], limit: Int) -> [String] {
        if terms.isEmpty { return [] }
        let placeholders = Array(repeating: "?", count: terms.count).joined(separator: ", ")
        let sql = "SELECT DISTINCT memoryId FROM memory_terms WHERE term IN (\(placeholders)) LIMIT ?"
        guard let statement = try? stores.database.prepare(sql) else { return [] }
        try? statement.bind(terms.map { .text($0) } + [.int(limit)])
        var ids: [String] = []
        while (try? statement.step()) == true {
            if let id = statement.text(at: 0) { ids.append(id) }
        }
        return ids
    }

    private func searchForPersona(personaId: String, query: String, limit: Int) -> [MemoryEntity] {
        let sql = """
        SELECT * FROM memories
        WHERE personaId = ? AND content LIKE '%' || ? || '%' COLLATE NOCASE
        ORDER BY pinned DESC, importance DESC, updatedAt DESC LIMIT ?
        """
        return fetchEntities(sql: sql, arguments: [.text(personaId), .text(query), .int(limit)])
    }

    private func byTag(personaId: String, tag: String, limit: Int) -> [MemoryEntity] {
        let sql = """
        SELECT * FROM memories
        WHERE personaId = ? AND tags IS NOT NULL AND tags LIKE '%' || ? || '%'
        ORDER BY importance DESC, updatedAt DESC LIMIT ?
        """
        return fetchEntities(sql: sql, arguments: [.text(personaId), .text(tag), .int(limit)])
    }

    private func withTimeHint(personaId: String, limit: Int) -> [MemoryEntity] {
        let sql = """
        SELECT * FROM memories WHERE personaId = ? AND happenedAt IS NOT NULL
        ORDER BY happenedAt DESC LIMIT ?
        """
        return fetchEntities(sql: sql, arguments: [.text(personaId), .int(limit)])
    }

    private func fetchEntities(sql: String, arguments: [SQLiteValue]) -> [MemoryEntity] {
        guard let statement = try? stores.database.prepare(sql) else { return [] }
        try? statement.bind(arguments)
        var rows: [MemoryEntity] = []
        while (try? statement.step()) == true {
            if let memory = try? MemoryEntity(row: SQLiteRow(statement: statement)) {
                rows.append(memory)
            }
        }
        return rows
    }

    /// LIKE 查询里 % 和 _ 是通配符，模型给的关键词里带上它们会变成全表扫描，先清掉。
    private func likeSafe(_ term: String) -> String {
        String(term.replacingOccurrences(of: "%", with: "").replacingOccurrences(of: "_", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxQueryChars))
    }
}
