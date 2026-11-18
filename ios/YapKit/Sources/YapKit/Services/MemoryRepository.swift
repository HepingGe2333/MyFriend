import Foundation

// 来源：app/src/main/java/com/yap/app/data/repo/MemoryRepository.kt（写入 + 召回部分）
//
// 记忆仓储：写入（含标签/抽屉/时间）、召回（候选并集 → 本地重排 → 字数预算裁剪）、
// 倒排索引的惰性补建、以及「被想起来一次」的强度回血。
//
// 与 Android 的对应关系，以及刻意保留的边界：
// - **业务判断不在这个文件**：衰减/归档公式在 Domain/MemoryLifecycle，重排与筛查在
//   Services/MemoryScreening，倒排在 Domain/ChineseTerms，向量在 Services/MemoryStore+Vector；
// - 这里只做「把库里的行捞上来 / 把决定写回去」；
// - Android 的 `syncCoordinator.markNativeSessionsDirtyForPersona` 在 iOS 还没有对应物
//   （PersonaSync 未移植），所以暂不调用 —— 备注在 README/报告里，没有偷偷留空实现。
//
// 四层保险（与 Android 相同）：索引没建好 → 靠 LIKE 与兜底；向量表空着/编码失败 → 退回纯词元重排；
// query 为空 → 直接走 pinned/重要度；数据库出任何错 → 退回「取全量再排序」。行为不会比以前差。

/// 一次「召回候选集」：候选记忆 + 「这条候选有多像这句话」的向量分。
///
/// 向量分只用来在**同一批候选里**微调先后（见 MemoryScreening），它不决定谁能进候选。
private struct RecallCandidates {
    var memories: [MemoryEntity]
    /// 记忆 id → 余弦相似度（0..1）；空 map = 这次没有向量信号。
    var vectorScores: [String: Double]
}

public final class MemoryRepository {

    /// 一条记忆至少隔 6 小时才写一次「被想起来」，避免每轮对话都写库。
    public static let recallTouchIntervalMillis: Int64 = 6 * 3_600_000

    /// 进上下文的字数预算。
    public static let maxMemoryContextChars = 7000

    /// 单条记忆正文上限（与写入校验、Android 的 2000 字一致）。
    public static let maxContentChars = 2000

    public let stores: DatabaseStores
    public let vectorIndex: MemoryVectorIndex
    private let now: () -> Int64
    private let lock = NSLock()
    /// 这次进程里是否已经确认过倒排索引完整；确认过就不再每次召回都查一遍。
    private var termIndexEnsured = false

    public init(
        stores: DatabaseStores,
        vectorIndex: MemoryVectorIndex? = nil,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.stores = stores
        self.now = now
        self.vectorIndex = vectorIndex ?? MemoryVectorIndex(stores: stores, now: now)
    }

    // MARK: - 读

    public func count(personaId: String) throws -> Int {
        try stores.memories.count(personaId: personaId)
    }

    /// 还在用（未归档）的记忆，按 pinned/重要度/更新时间排。进上下文的基线。
    public func getForPrompt(personaId: String, limit: Int) throws -> [MemoryEntity] {
        try stores.memories.fetch(
            where: "personaId = ? AND status != 'ARCHIVED'",
            [.text(personaId)],
            orderBy: "pinned DESC, importance DESC, updatedAt DESC",
            limit: limit
        )
    }

    /// 全部记忆（含归档）。整理与回忆要看到这些。
    public func getAllForPersona(_ personaId: String) throws -> [MemoryEntity] {
        try stores.memories.fetch(
            where: "personaId = ?",
            [.text(personaId)],
            orderBy: "pinned DESC, importance DESC, updatedAt DESC"
        )
    }

    /// 按「这一轮聊的是什么」挑记忆，整条链路是三步：
    ///
    /// 1. **候选**：倒排索引命中 ∪ 关键词 LIKE 兜底 ∪ 标签命中 ∪ 时间指代 ∪ pinned/重要度兜底
    ///    ∪ 向量 top-16；
    /// 2. **重排**：在内存里算词元重合度、向量相似度、标签、发生时间、pinned、重要度、新鲜度、抽屉；
    /// 3. **裁剪**：按字数预算取前 N 条，避免把上下文塞满。
    ///
    /// 只从 `getForPrompt` 取基线：**归档的记忆永远不进上下文**（它们还在库里，只可能被「回忆」翻出来）。
    /// 挑中的记忆顺手记一次「被想起来」，这样它不会被每日衰减无声无息地沉掉。
    public func getRelevant(personaId: String, query: String = "", limit: Int = 200) -> [MemoryEntity] {
        // 1.9 起记忆内置开启：没有「关掉记忆」这个开关了（§一）。召回只看相关度与预算。
        let now = now()
        let candidates = (try? recallCandidates(personaId: personaId, query: query, limit: limit))
            ?? RecallCandidates(memories: [], vectorScores: [:])
        let memories = candidates.memories
        if memories.isEmpty { return [] }
        let byId = Dictionary(uniqueKeysWithValues: memories.map { ($0.id, $0) })
        let rows = memories.map { screenRow($0) }
        let picked = MemoryScreening.selectWithinBudget(
            memories: rows,
            query: query,
            now: now,
            maxChars: Self.maxMemoryContextChars,
            vectorScores: candidates.vectorScores
        )
        let pickedEntities = picked.compactMap { byId[$0.id] }
        // 0.14 记忆审计：候选并集来自五条路（索引 / LIKE / 标签 / 时间 / 向量），
        // 同一条内容可能因为不同来源被挑中两次；而这批记忆最终要逐条写进提示词，
        // 重复的一条会白占两行预算、还会让模型以为「这件事他说了两遍」。
        let unique = Self.deduplicated(pickedEntities)
        touchRecalled(unique, now: now)
        return unique
    }

    /// 召回候选集：只负责「把可能相关的东西捞上来」，不排序（排序在 MemoryScreening 里做）。
    ///
    /// 候选多几条没关系（一次最多几百条，都在内存里），漏掉才是问题；
    /// 但每条候选都必须过一遍 persona 与归档检查，否则会把别的抽屉或早该沉下去的记忆塞进上下文。
    private func recallCandidates(personaId: String, query: String, limit: Int) throws -> RecallCandidates {
        let now = now()
        let pool = try getForPrompt(personaId: personaId, limit: limit)
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return RecallCandidates(memories: pool, vectorScores: [:])
        }

        var candidates: [String: MemoryEntity] = [:]
        var order: [String] = []
        func put(_ memory: MemoryEntity) {
            if candidates[memory.id] == nil { order.append(memory.id) }
            candidates[memory.id] = memory
        }
        pool.forEach { put($0) }

        // 索引缺了才补（老用户库里的记忆是 v7 之前写的，没有索引）。
        ensureTermIndex()
        let terms = ChineseTerms.queryTerms(query)

        // 1) 倒排索引命中
        if !terms.isEmpty {
            for id in memoryIdsByTerms(terms, limit: Self.indexCandidates) {
                if candidates[id] != nil { continue }
                guard let memory = try? stores.memories.byId(id) else { continue }
                if memory.personaId != personaId || isArchived(memory) { continue }
                put(memory)
            }
        }

        // 2) 关键词 LIKE 兜底：索引坏掉/没建好时也照样能按字面找到。
        let like = likeSafe(query)
        if !like.isEmpty {
            for memory in searchForPersona(personaId: personaId, query: like, limit: Self.likeCandidates)
            where !isArchived(memory) {
                put(memory)
            }
        }

        // 3) 标签兜底：query 里的短词可能就是当初打的标签（「妹妹」「失眠」）。
        for term in terms.prefix(Self.tagLookupTerms) {
            for memory in byTag(personaId: personaId, tag: term, limit: Self.tagCandidates)
            where !isArchived(memory) {
                put(memory)
            }
        }

        // 4) 时间指代：「去年冬天」这类说法，把有发生时间的记忆也捞上来。
        if ChineseTerms.hasTimeHint(query, now: now) {
            for memory in withTimeHint(personaId: personaId, limit: Self.timeCandidates)
            where !isArchived(memory) {
                put(memory)
            }
        }

        // 5) 向量：意思相近但字面不重叠时也能翻到（「项目压力」↔「工作上的压力」）。
        //    只在**上面已经捞上来的候选并集**里取 top-16，而不是再扫一遍全库正文：
        //    并集本身已经覆盖 pinned/重要/最近 limit 条 + 倒排/LIKE/标签/时间命中的记忆。
        let vectorScores = vectorScoresOf(personaId: personaId, query: query, ids: Array(candidates.keys))
        return RecallCandidates(memories: order.compactMap { candidates[$0] }, vectorScores: vectorScores)
    }

    /// 向量这一路：记忆 id → 余弦相似度。任何一步出错都返回空 map。
    ///
    /// 空 map 的语义是「这次没有向量信号」，MemoryScreening 会原样退化成纯词元重排——
    /// 向量只是他「换个说法也记得」的那点运气，不能成为召回的依赖。
    private func vectorScoresOf(personaId: String, query: String, ids: [String]) -> [String: Double] {
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || ids.isEmpty { return [:] }
        // 老库升上来时没有向量：第一次用到时补一次（每个进程每个人格只补一次）。
        vectorIndex.ensureIndex(personaId: personaId)
        let hits = vectorIndex.searchIn(memoryIds: ids, query: query, limit: Self.vectorTopK)
        return Dictionary(hits.map { ($0.memoryId, $0.similarity) }, uniquingKeysWith: { first, _ in first })
    }

    // MARK: - 倒排索引

    /// 惰性补建倒排索引：`memory_terms` 全空时，为所有记忆补一次。
    ///
    /// 只在「确实没有索引」时做：每次召回都全量重建会把启动拖慢几十毫秒到几百毫秒，
    /// 而补完一次就够了（之后写入/更新/删除都会同步维护）。失败不抛：没有索引也能靠 LIKE 兜底。
    public func ensureTermIndex() {
        if termIndexEnsured { return }
        lock.lock()
        defer { lock.unlock() }
        if termIndexEnsured { return }
        if ((try? stores.memoryTerms.count()) ?? 0) == 0 {
            for persona in (try? stores.personas.all()) ?? [] {
                for memory in (try? stores.memories.fetch(
                    where: "personaId = ?",
                    [.text(persona.id)],
                    orderBy: "pinned DESC, importance DESC, updatedAt DESC",
                    limit: Self.indexBackfillLimit
                )) ?? [] {
                    rebuildTermIndex(memoryId: memory.id, content: memory.content)
                }
            }
        }
        termIndexEnsured = true
    }

    /// 顺手回收孤儿词元：记忆被整批删掉时不会自动带走索引。
    @discardableResult
    public func pruneTermIndex() -> Int {
        let sql = "DELETE FROM memory_terms WHERE NOT EXISTS (SELECT 1 FROM memories m WHERE m.id = memory_terms.memoryId)"
        guard let statement = try? stores.database.prepare(sql) else { return 0 }
        guard (try? statement.step()) != nil else { return 0 }
        return stores.database.lastChangeCount()
    }

    /// 向量索引补建：给启动流程留的入口。不调也不会错——第一次召回/回忆时会自己补。
    @discardableResult
    public func ensureVectorIndex() -> Int {
        vectorIndex.ensureIndex()
    }

    /// 按库里当前的正文重建某条记忆的倒排索引，并顺带重算向量。
    ///
    /// 给「记忆会随回忆漂移」那条链路用：漂移是直接改库里的 content，改完调一下这里，
    /// 新措辞才搜得到、旧措辞才不会继续命中。记忆不存在就安静返回（可能刚被忘掉）。
    public func reindexTerms(memoryId: String) {
        guard let memory = try? stores.memories.byId(memoryId) else { return }
        rebuildTermIndex(memoryId: memory.id, content: memory.content)
        vectorIndex.upsert(memoryId: memory.id, content: memory.content)
    }

    /// 重建一条记忆的倒排索引：正文改过就必须先删后写，否则会留下查得到的旧词元。
    func rebuildTermIndex(memoryId: String, content: String) {
        _ = try? stores.memoryTerms.deleteTerms(forMemory: memoryId)
        let terms = ChineseTerms.terms(content).map {
            MemoryTermEntity(memoryId: memoryId, term: $0.term, weight: $0.weight)
        }
        if !terms.isEmpty { _ = try? stores.memoryTerms.upsert(terms) }
    }

    // MARK: - 写

    /// 带抽屉与来源的写入：AI 用 remember 工具、自主循环、抽取落库都走这里，
    /// 这样每条记忆都知道自己属于哪个抽屉、是从哪句话来的。
    public func addDetailed(
        personaId: String,
        content: String,
        category: MemoryCategory,
        importance: Int,
        pinned: Bool = false,
        drawer: String? = nil,
        sourceQuote: String? = nil,
        sourceSessionId: String? = nil,
        tags: [String] = [],
        happenedAt: Int64? = nil
    ) -> Result<String, Error> {
        let text = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return .failure(MemoryRepositoryError.emptyContent) }
        if text.count > Self.maxContentChars { return .failure(MemoryRepositoryError.contentTooLong) }
        let now = now()
        let memory = MemoryEntity(
            id: UUID().uuidString,
            personaId: personaId,
            content: text,
            category: category,
            importance: min(max(importance, 1), 5),
            pinned: pinned,
            sourceSessionId: sourceSessionId,
            createdAt: now,
            updatedAt: now,
            lastUsedAt: 0,
            useCount: 1,
            drawer: drawer,
            sourceQuote: sourceQuote.map { String($0.prefix(300)) },
            status: "ACTIVE",
            strength: 1.0,
            tags: MemoryTags.encode(tags),
            happenedAt: happenedAt.flatMap { $0 > 0 ? $0 : nil }
        )
        do {
            try stores.memories.upsert(memory)
        } catch {
            return .failure(error)
        }
        // 新记忆马上进倒排索引与向量索引：不建的话，它要等到下次全量补建才查得到。
        rebuildTermIndex(memoryId: memory.id, content: text)
        vectorIndex.upsert(memoryId: memory.id, content: text)
        return .success(memory.id)
    }

    /// 简化写入：用户手动添加记忆。
    public func add(
        personaId: String,
        content: String,
        category: MemoryCategory,
        importance: Int,
        pinned: Bool = false
    ) -> Result<String, Error> {
        let text = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return .failure(MemoryRepositoryError.emptyContent) }
        if text.count > Self.maxContentChars { return .failure(MemoryRepositoryError.contentTooLong) }
        let now = now()
        let memory = MemoryEntity(
            id: UUID().uuidString,
            personaId: personaId,
            content: text,
            category: category,
            importance: min(max(importance, 1), 5),
            pinned: pinned,
            createdAt: now,
            updatedAt: now,
            useCount: 0
        )
        do {
            try stores.memories.upsert(memory)
        } catch {
            return .failure(error)
        }
        rebuildTermIndex(memoryId: memory.id, content: text)
        vectorIndex.upsert(memoryId: memory.id, content: text)
        return .success(memory.id)
    }

    /// 编辑一条记忆：正文、类别、重要度。索引跟着重建（否则改之前的词元还在）。
    public func update(memory: MemoryEntity, content: String, category: MemoryCategory, importance: Int) -> Result<Void, Error> {
        let text = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return .failure(MemoryRepositoryError.emptyContent) }
        if text.count > Self.maxContentChars { return .failure(MemoryRepositoryError.contentTooLong) }
        var updated = memory
        updated.content = text
        updated.category = category
        updated.importance = min(max(importance, 1), 5)
        updated.updatedAt = now()
        do {
            try stores.memories.upsert(updated)
        } catch {
            return .failure(error)
        }
        rebuildTermIndex(memoryId: updated.id, content: text)
        vectorIndex.upsert(memoryId: updated.id, content: text)
        return .success(())
    }

    /// 同一件事又被提到：加固而不是新增。
    public func reinforce(memoryId: String, appendedText: String? = nil, importance: Int? = nil) -> Result<Void, Error> {
        guard let memory = try? stores.memories.byId(memoryId) else {
            return .failure(MemoryRepositoryError.memoryMissing)
        }
        let now = now()
        var merged = memory.content
        if let appendedText, !appendedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            merged = String((memory.content.trimmingCharacters(in: .whitespacesAndNewlines)
                + "；" + appendedText.trimmingCharacters(in: .whitespacesAndNewlines)).prefix(Self.maxContentChars))
        }
        var updated = memory
        updated.content = merged
        if let importance { updated.importance = min(max(importance, 1), 5) }
        updated.useCount = memory.useCount + 1
        updated.lastUsedAt = now
        updated.updatedAt = now
        do {
            try stores.memories.upsert(updated)
        } catch {
            return .failure(error)
        }
        // 正文变长了：索引跟着重建，否则新补上的那句话永远搜不到。
        rebuildTermIndex(memoryId: memoryId, content: merged)
        vectorIndex.upsert(memoryId: memoryId, content: merged)
        return .success(())
    }

    /// 把这次抽出来的结构信息（标签、发生时间）补贴到一条已有记忆上：**只补空，不覆盖**。
    ///
    /// 为什么只补空：同一条记忆可能被不同轮对话反复加固，每次都覆盖一次的话，
    /// 后面一次模糊的「最近」会把前面确切的「去年冬天」冲掉——他对旧事的时间感就是这样被弄乱的。
    public func mergeStructure(memoryId: String, tags: [String], happenedAt: Int64?) {
        if tags.isEmpty && happenedAt == nil { return }
        guard let memory = try? stores.memories.byId(memoryId) else { return }
        let mergedTags = MemoryTags.sanitize(MemoryTags.decode(memory.tags) + tags)
        var updated = memory
        if let encoded = MemoryTags.encode(mergedTags) {
            updated.tags = encoded
        }
        if let happenedAt, happenedAt > 0, memory.happenedAt == nil {
            updated.happenedAt = happenedAt
        }
        _ = try? stores.memories.upsert(updated)
    }

    /// 删除一条记忆：记忆没了，索引也必须跟着没。
    ///
    /// 顺序是先删记忆再删索引：万一删索引失败，孤儿词元只是查不到东西；反过来会留下
    /// 「查得到但已经不记得」的坏状态。
    public func delete(_ memory: MemoryEntity) {
        _ = try? stores.memories.delete(id: memory.id)
        _ = try? stores.memoryTerms.deleteTerms(forMemory: memory.id)
        // 版本历史里存着这条记忆的正文与 beforeContent：「忘掉」就是真删，
        // 不能只在界面上看不见（0.10.2 审计 P2-11）。
        _ = try? stores.memoryVersions.delete(where: "memoryId = ?", [.text(memory.id)])
        vectorIndex.remove(memoryId: memory.id)
    }

    public func deleteById(_ memoryId: String) {
        guard let memory = try? stores.memories.byId(memoryId) else { return }
        delete(memory)
    }

    public func setPinned(_ memory: MemoryEntity, pinned: Bool) {
        var updated = memory
        updated.pinned = pinned
        updated.updatedAt = now()
        _ = try? stores.memories.upsert(updated)
    }

    // MARK: - 生命周期写回（整理 / 回忆共用）

    /// 写回层级（ACTIVE / DORMANT / ARCHIVED）与分量；`archivedAt` 为 nil 表示回到上下文。
    public func setLifecycle(id: String, status: MemoryTier, strength: Double, archivedAt: Int64?, now: Int64) {
        guard let memory = try? stores.memories.byId(id) else { return }
        var updated = memory
        updated.status = status.rawValue
        updated.strength = min(max(strength, 0.0), 1.0)
        updated.archivedAt = archivedAt
        updated.updatedAt = now
        updated.consolidatedAt = now
        _ = try? stores.memories.upsert(updated)
    }

    /// 只写分量与「整理过」的时间，不动正文与层级（模型说「留着」时用）。
    public func touchStrength(id: String, strength: Double, now: Int64) {
        guard let memory = try? stores.memories.byId(id) else { return }
        var updated = memory
        updated.strength = min(max(strength, 0.0), 1.0)
        updated.updatedAt = now
        updated.consolidatedAt = now
        _ = try? stores.memories.upsert(updated)
    }

    /// 整理器改了正文/重要度/分量：三样一起写回，并重建索引。
    public func applyConsolidation(id: String, importance: Int, strength: Double, content: String, now: Int64) {
        guard let memory = try? stores.memories.byId(id) else { return }
        var updated = memory
        updated.importance = min(max(importance, 1), 5)
        updated.strength = min(max(strength, 0.0), 1.0)
        updated.content = String(content.prefix(Self.maxContentChars))
        updated.updatedAt = now
        updated.consolidatedAt = now
        _ = try? stores.memories.upsert(updated)
        if updated.content != memory.content {
            rebuildTermIndex(memoryId: id, content: updated.content)
            vectorIndex.upsert(memoryId: id, content: updated.content)
        }
    }

    /// 回忆重构的留痕 + 正文改写：改正文、记降次、留原文锚点，并写一条 memory_versions。
    ///
    /// Android 里这段在 MemoryReconsolidator（要调模型）；iOS 这一版只保留「落库那一半」，
    /// 让召回链路 / 以后的漂移服务都能复用同一处写库逻辑。
    public func applyDrift(
        memoryId: String,
        newContent: String,
        reason: String?,
        moodLabel: String?,
        now: Int64
    ) {
        guard let memory = try? stores.memories.byId(memoryId) else { return }
        let trimmed = newContent.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == memory.content { return }
        var updated = memory
        updated.content = String(trimmed.prefix(Self.maxContentChars))
        if updated.originalContent == nil {
            updated.originalContent = memory.content
        }
        updated.driftCount = memory.driftCount + 1
        updated.lastUsedAt = now
        updated.updatedAt = now
        _ = try? stores.memories.upsert(updated)
        _ = try? stores.memoryVersions.upsert(
            MemoryVersionEntity(
                id: UUID().uuidString,
                memoryId: memoryId,
                at: now,
                content: updated.content,
                beforeContent: memory.content,
                reason: reason,
                moodLabel: moodLabel
            )
        )
        rebuildTermIndex(memoryId: memoryId, content: updated.content)
        vectorIndex.upsert(memoryId: memoryId, content: updated.content)
    }

    /// 被用到一次（不改正文、不改 updatedAt 的顺序语义）：回忆工具用。
    public func markUsed(id: String, now: Int64, bumpLastUsedAt: Bool = true) {
        guard let memory = try? stores.memories.byId(id) else { return }
        var updated = memory
        updated.useCount = memory.useCount + 1
        if bumpLastUsedAt { updated.lastUsedAt = now }
        _ = try? stores.memories.upsert(updated)
    }

    // MARK: - 提示词渲染

    /// 单条记忆进提示词时的字数上限（0.14 审计：超长截断）。
    ///
    /// 记忆本身可以到 2000 字（那是用户/模型写的原文），但提示词里的每一行都只是**索引**：
    /// 一条几千字的记忆会把 7 000 字的上下文预算吃掉一半，挤掉其它更相关的事。
    /// 截断只影响提示词，不改库里的正文。
    public static let maxPromptLineChars = 300

    /// 按内容去重（保留先出现的那条：picked 已经按相关度排过序）。
    static func deduplicated(_ memories: [MemoryEntity]) -> [MemoryEntity] {
        var seen = Set<String>()
        var result: [MemoryEntity] = []
        for memory in memories {
            let key = memory.content
                .replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            if key.isEmpty { continue }
            if seen.insert(key).inserted { result.append(memory) }
        }
        return result
    }

    /// 按抽屉讲给模型听，比按数据库的 category 更接近「他记得的事」。
    public func formatForPrompt(_ memories: [MemoryEntity]) -> String {
        if memories.isEmpty { return "" }
        let grouped = Dictionary(grouping: memories) { drawerValue($0) }
        let labels: [MemoryDrawer: String] = [
            .user: "关于对方",
            .selfDrawer: "你自己是什么样的人",
            .event: "你们之间的经历与约定"
        ]
        var text = "\n\n---\n## 你记得的事\n"
        text += "以下是你与对方长期相处中自然记住的信息。只在话题相关时自然地使用，不要逐条复述，也不要声称自己记错。\n"
        for drawer in [MemoryDrawer.user, .selfDrawer, .event] {
            let items = grouped[drawer] ?? []
            if items.isEmpty { continue }
            text += "\n**\(labels[drawer] ?? "")**\n"
            for memory in items.sorted(by: { $0.importance > $1.importance }) {
                // 0.14 超长截断：提示词里的每一条都只留前 300 字（见 `maxPromptLineChars`）。
                let content = memory.content.count > Self.maxPromptLineChars
                    ? String(memory.content.prefix(Self.maxPromptLineChars)) + "…"
                    : memory.content
                text += "- \(content)"
                if memory.pinned { text += "（对你很重要）" }
                text += "\n"
            }
        }
        return text
    }

    // MARK: - 视图转换（给其它服务复用）

    /// Room 实体 → 生命周期纯视图（Domain 不依赖数据库，所以在这里转一次）。
    public func lifecycleState(_ memory: MemoryEntity) -> MemoryLifecycleState {
        MemoryLifecycleState(
            id: memory.id,
            tier: MemoryTier.parse(memory.status),
            pinned: memory.pinned,
            importance: memory.importance,
            useCount: memory.useCount,
            lastUsedAt: memory.lastUsedAt,
            createdAt: memory.createdAt,
            strength: memory.strength,
            lastConsolidatedAt: memory.consolidatedAt ?? 0,
            // 三个抽屉的衰减速度不一样：关于对方的事实最抗时间（见 MemoryLifecycle 常量注释）。
            drawer: drawerValue(memory)
        )
    }

    /// 抽屉可能来自旧数据：认不出来就按 category 推断，不让一条脏字符串炸掉整次召回。
    public func drawerValue(_ memory: MemoryEntity) -> MemoryDrawer {
        let raw = (memory.drawer ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if let drawer = MemoryDrawer(rawValue: raw) { return drawer }
        return MemoryScreening.drawerOf(memory.category.rawValue)
    }

    /// 一行记忆的筛查视图。
    public func screenRow(_ memory: MemoryEntity) -> MemoryRow {
        MemoryRow(
            id: memory.id,
            content: memory.content,
            drawer: drawerValue(memory),
            importance: memory.importance,
            pinned: memory.pinned,
            hits: max(memory.useCount, 1),
            lastHitAt: memory.lastUsedAt,
            createdAt: memory.createdAt,
            tags: MemoryTags.decode(memory.tags),
            happenedAt: memory.happenedAt
        )
    }

    public func isArchived(_ memory: MemoryEntity) -> Bool {
        memory.status.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "ARCHIVED"
    }

    // MARK: - 内部查询

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

    /// LIKE 里的 % 和 _ 是通配符，模型给的关键词带上来会变成全表扫，先清掉。
    private func likeSafe(_ term: String) -> String {
        String(term.replacingOccurrences(of: "%", with: "").replacingOccurrences(of: "_", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
    }

    /// 召回即「想起来一次」：刷新 lastUsedAt / useCount，并给一点分量回血（见 MemoryLifecycle）。
    private func touchRecalled(_ memories: [MemoryEntity], now: Int64) {
        for memory in memories {
            if now - memory.lastUsedAt < Self.recallTouchIntervalMillis { continue }
            var updated = memory
            updated.lastUsedAt = now
            updated.useCount = memory.useCount + 1
            updated.strength = MemoryLifecycle.recallStrength(lifecycleState(memory), now: now)
            updated.updatedAt = now
            _ = try? stores.memories.upsert(updated)
        }
    }

    // MARK: - 常量

    static let indexCandidates = 120
    static let likeCandidates = 60
    static let tagLookupTerms = 3
    static let tagCandidates = 30
    static let timeCandidates = 40
    static let indexBackfillLimit = 500
    static let vectorTopK = 16
}

public enum MemoryRepositoryError: Error, LocalizedError, CustomStringConvertible {
    case emptyContent
    case contentTooLong
    case memoryMissing

    public var errorDescription: String? { description }

    public var description: String {
        switch self {
        case .emptyContent: return String(localized: "记忆内容不能为空")
        case .contentTooLong:
            return PluralText.format("单条记忆请控制在 %d 字以内", MemoryRepository.maxContentChars)
        case .memoryMissing: return String(localized: "记忆已不存在")
        }
    }
}
