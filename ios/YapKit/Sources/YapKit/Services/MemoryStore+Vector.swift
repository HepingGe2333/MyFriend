import Foundation

// 来源：app/src/main/java/com/yap/app/data/repo/MemoryVectorIndex.kt
//
// 记忆向量的本地索引：建、改、删、查，全在这一个地方。
//
// ## 它是什么
//
// `memory_vectors` 表里每条记忆一行（本实现 384 个 float32 ≈ 1.5KB），查询时按余弦取最像的几条，
// 作为「倒排索引之外的第二条线索」。倒排索引治「同一个词换个说法」，向量治「同一个意思换一片字」，
// 两者互补，都不够用时还有 LIKE 兜底。
//
// ## 内存纪律（与 Android 一致，这不是优化而是纪律）
//
// - 不缓存记忆向量：任何时刻都不把整张表的 FloatArray 留在内存里。补建 64 条一批、检索 128 条一块，
//   扫完即丢；
// - 只 memo「最近一次查询的嵌入」（一条，约 1.5KB）：同一轮里拼上下文和回忆工具可能用同一句话查两遍；
// - `clearCache()` 留给低内存回调。
//
// ## 失败策略
//
// 索引为空、编码出错、SQL 抛异常 —— 全部静默降级成空结果。调用方（MemoryRepository、RecallService）
// 据此退回倒排/LIKE 路径：**加了向量只会更好，绝不会更差**。
//
// ## 与 Android 的差别
//
// Android 用 Room 的 `@Query`，这里没有 DAO 层（`Data/Stores.swift` 是通用 CRUD），
// 所以「缺向量的 id」「孤儿向量」这类查询直接写 SQL —— 这也是本文件叫 `MemoryStore+Vector` 的原因：
// 它是 `MemoryStore` / `MemoryVectorStore` 之上的一层索引逻辑。

/// 检索时一块扫多少条向量：128 个 [Float] ≈ 200KB 的瞬时峰值，扫完即丢。
private let vectorSearchChunk = 128

/// 一条向量检索结果：记忆 id + 余弦相似度（0..1，越像越大）。
public struct VectorHit: Equatable, Sendable {
    public var memoryId: String
    public var similarity: Double

    public init(memoryId: String, similarity: Double) {
        self.memoryId = memoryId
        self.similarity = similarity
    }
}

/// 记忆向量的本地索引。
///
/// 线程安全：内部一把锁保护「已补齐的人格集合」与「上次查询的嵌入」。
/// 补建本身可能被启动与第一次召回同时触发，锁保证只让一个人干活。
public final class MemoryVectorIndex {

    /// 补建时一批多少条：编码、写库、丢弃，都按这个粒度走。
    public static let backfillBatch = 64

    /// 补建最多问几轮「还有谁缺向量」：64 × 40 = 2560 条，已超过单人格记忆的实际上限。
    public static let backfillMaxRounds = 40

    /// `search(personaId:)` 按人格取 id 时的上限（生产路径更常用 `searchIn`）。
    public static let searchScanLimit = 400

    private let stores: DatabaseStores
    private let now: () -> Int64
    private let lock = NSLock()
    /// 这次进程里已经确认补齐过的人格：确认过就不再扫一遍。
    private var ensuredPersonas: Set<String> = []
    private var cachedQuery: String?
    private var cachedQueryVector: [Float]?

    public init(stores: DatabaseStores, now: @escaping () -> Int64 = { DomainClock.nowMillis }) {
        self.stores = stores
        self.now = now
    }

    // MARK: - 补建

    /// 补齐缺失向量：老库升上来的记忆没有向量，第一次用到时补一次。
    ///
    /// - Parameter personaId: 只补这一个人格；传 nil 表示所有人格（启动时用这个）。
    /// - Returns: 这次实际写入的向量条数。
    ///
    /// 三道省时省内存的闸门：进程内记忆「这个人格已经补齐」→ 直接返回；真的要补时按 64 条一批
    /// 读、编码、写，不会为了补 500 条记忆一次性建 500 个 [Float]。
    @discardableResult
    public func ensureIndex(personaId: String? = nil) -> Int {
        let targets: [PersonaEntity]
        if let personaId, !personaId.trimmingCharacters(in: .whitespaces).isEmpty {
            targets = ((try? stores.personas.byId(personaId)) ?? nil).map { [$0] } ?? []
        } else {
            targets = (try? stores.personas.all(orderBy: "updatedAt DESC")) ?? []
        }
        if targets.isEmpty { return 0 }

        lock.lock()
        defer { lock.unlock() }
        let pending = targets.filter { !ensuredPersonas.contains($0.id) }
        if pending.isEmpty { return 0 }

        if personaId == nil {
            // 全库补建时才清理：换过嵌入实现的话，上一代向量在这里一次性作废。
            _ = pruneForeignProviders()
            _ = pruneOrphans()
        }

        var written = 0
        for persona in pending {
            written += (try? backfillPersona(persona.id)) ?? 0
            // 失败了也记成「已确认」：与其每次召回都重试一遍拖慢聊天，不如等下次进程启动。
            ensuredPersonas.insert(persona.id)
        }
        return written
    }

    /// 补一个人格的向量，返回写入条数。
    ///
    /// 判据只能按「这批 id 谁已经有向量」来查：`memory_vectors` 里没有 personaId 列，
    /// 拿全库向量数与单人格记忆数比会漏补。好在这一步每个进程每个人格只跑一次。
    private func backfillPersona(_ personaId: String) throws -> Int {
        var written = 0
        var rounds = 0
        // 一轮只取一批「还缺向量」的 id，补完再问一次，直到问不出东西——
        // 所以记忆超过一批也补得完（之前用固定上限一把抓，第 N 条之后永远没有向量）。
        while rounds < Self.backfillMaxRounds {
            rounds += 1
            let missing = idsMissingVectors(personaId: personaId, limit: Self.backfillBatch)
            if missing.isEmpty { break }
            let memories = memories(ids: missing)
            if memories.isEmpty { break }
            let rows = memories.compactMap { encodeRow(memoryId: $0.id, content: $0.content) }
            if rows.isEmpty {
                // 剩下的都是算不出向量的（纯标点之类）：不写向量，也不该无限循环。
                break
            }
            try stores.memoryVectors.upsert(rows)
            written += rows.count
            if missing.count < Self.backfillBatch { break }
        }
        return written
    }

    /// 还没有向量的记忆 id（补建用）。
    ///
    /// 只挑真的能算出向量的行（正文非空白），否则「一条空记忆」会让补建永远补不完。
    /// 用 `NOT EXISTS` 而不是 `LEFT JOIN … IS NULL`：`memory_vectors` 上有 memoryId 索引，走一遍就够。
    private func idsMissingVectors(personaId: String, limit: Int) -> [String] {
        let sql = """
        SELECT m.id FROM memories m
        WHERE m.personaId = ?
          AND m.content IS NOT NULL AND TRIM(m.content) != ''
          AND NOT EXISTS (
              SELECT 1 FROM memory_vectors v
              WHERE v.memoryId = m.id AND v.provider = ? AND v.dim = ?
          )
        ORDER BY m.pinned DESC, m.importance DESC, m.updatedAt DESC
        LIMIT ?
        """
        guard let statement = try? stores.database.prepare(sql) else { return [] }
        try? statement.bind([
            .text(personaId), .text(LocalEmbedding.PROVIDER), .int(LocalEmbedding.DIM), .int(limit)
        ])
        var ids: [String] = []
        while (try? statement.step()) == true {
            if let id = statement.text(at: 0) { ids.append(id) }
        }
        return ids
    }

    private func memories(ids: [String]) -> [MemoryEntity] {
        if ids.isEmpty { return [] }
        let placeholders = Array(repeating: "?", count: ids.count).joined(separator: ", ")
        return (try? stores.memories.fetch(where: "id IN (\(placeholders))", ids.map { .text($0) })) ?? []
    }

    // MARK: - 写入

    /// 某条记忆的正文写入/加固/漂移/整理之后调用：重算并覆盖它的向量。
    ///
    /// 正文变空白（理论上不会发生）时删掉旧向量，否则一条已经被清空的记忆还会被向量检索翻出来。
    public func upsert(memoryId: String, content: String) {
        if memoryId.trimmingCharacters(in: .whitespaces).isEmpty { return }
        guard let row = encodeRow(memoryId: memoryId, content: content) else {
            remove(memoryId: memoryId)
            return
        }
        _ = try? stores.memoryVectors.upsert(row)
    }

    /// 记忆被真正删掉时调用：向量不能比记忆活得久。
    public func remove(memoryId: String) {
        if memoryId.trimmingCharacters(in: .whitespaces).isEmpty { return }
        _ = try? stores.memoryVectors.deleteVectors(forMemory: memoryId)
    }

    // MARK: - 查询

    /// 在整个人的记忆里按向量取 topK。
    ///
    /// 契约方法；生产路径更常用 `searchIn` —— 召回时调用方手里**已经**有候选 id 了，
    /// 再按人格查一遍正文只是白白多读几百行。
    public func search(personaId: String, query: String, limit: Int) -> [VectorHit] {
        if personaId.trimmingCharacters(in: .whitespaces).isEmpty || limit <= 0 { return [] }
        if query.trimmingCharacters(in: .whitespaces).isEmpty { return [] }
        _ = ensureIndex(personaId: personaId)
        let sql = """
        SELECT id FROM memories
        WHERE personaId = ? AND status != 'ARCHIVED'
        ORDER BY pinned DESC, importance DESC, updatedAt DESC
        LIMIT ?
        """
        var ids: [String] = []
        if let statement = try? stores.database.prepare(sql) {
            try? statement.bind([.text(personaId), .int(Self.searchScanLimit)])
            while (try? statement.step()) == true {
                if let id = statement.text(at: 0) { ids.append(id) }
            }
        }
        return searchIn(memoryIds: ids, query: query, limit: limit)
    }

    /// 在给定的记忆 id 集合里按向量取 topK。
    ///
    /// 这是两条召回链路真正用到的方法：它们把「已经捞上来的候选并集」的 id 传进来，
    /// 于是向量检索不会额外读一遍正文，也不会把别的抽屉/别的记忆混进来。
    ///
    /// 分块扫：一次只把 128 条向量解码进内存，算完这块就丢掉；每块先取一次 topK 再合并。
    /// 坏数据（半个 float、维度不对、别的 provider、NaN）一律跳过，不抛异常。
    public func searchIn(memoryIds: [String], query: String, limit: Int) -> [VectorHit] {
        if limit <= 0 || memoryIds.isEmpty { return [] }
        guard let queryVector = queryVectorOf(query) else { return [] }
        // 保序去重：同分候选的先后由输入顺序决定，Set 会把这件事变成随机的。
        var seen = Set<String>()
        var ids: [String] = []
        for id in memoryIds where !id.trimmingCharacters(in: .whitespaces).isEmpty {
            if seen.insert(id).inserted { ids.append(id) }
        }
        if ids.isEmpty { return [] }

        var perChunk: [VectorHit] = []
        var start = 0
        while start < ids.count {
            let end = min(start + vectorSearchChunk, ids.count)
            let chunk = Array(ids[start..<end])
            perChunk.append(contentsOf: scanChunk(chunk, queryVector: queryVector, limit: limit))
            start = end
        }
        if perChunk.isEmpty { return [] }
        return VectorMath.topK(perChunk, limit: limit) { $0.similarity }
    }

    private func scanChunk(_ ids: [String], queryVector: [Float], limit: Int) -> [VectorHit] {
        let placeholders = Array(repeating: "?", count: ids.count).joined(separator: ", ")
        let sql = """
        SELECT memoryId, provider, dim, vector FROM memory_vectors
        WHERE provider = ? AND memoryId IN (\(placeholders))
        """
        guard let statement = try? stores.database.prepare(sql) else { return [] }
        try? statement.bind([.text(LocalEmbedding.PROVIDER)] + ids.map { .text($0) })
        var hits: [VectorHit] = []
        while (try? statement.step()) == true {
            let row = SQLiteRow(statement: statement)
            guard let memoryId = try? row.text("memoryId"),
                  let provider = try? row.text("provider"),
                  let dim = try? row.int("dim"),
                  isUsableVector(provider: provider, dim: dim),
                  let blob = try? row.blob("vector"),
                  let vector = VectorMath.decode(blob, dim: dim),
                  vector.count == LocalEmbedding.DIM
            else { continue }
            let similarity = VectorMath.cosine(queryVector, vector)
            if !similarity.isFinite || similarity <= 0 { continue }
            hits.append(VectorHit(memoryId: memoryId, similarity: min(max(similarity, 0.0), 1.0)))
        }
        return VectorMath.topK(hits, limit: limit) { $0.similarity }
    }

    // MARK: - 清理

    /// 清掉上一代嵌入实现的向量（换实现、或存储紧张时手动回收空间）。返回删掉的行数。
    public func pruneForeignProviders() -> Int {
        guard let statement = try? stores.database.prepare(
            "DELETE FROM memory_vectors WHERE provider != ?"
        ) else { return 0 }
        try? statement.bind([.text(LocalEmbedding.PROVIDER)])
        guard (try? statement.step()) != nil else { return 0 }
        return stores.database.lastChangeCount()
    }

    /// 回收「记忆已经不在、向量还留着」的孤儿行。
    public func pruneOrphans() -> Int {
        let sql = "DELETE FROM memory_vectors WHERE NOT EXISTS (SELECT 1 FROM memories m WHERE m.id = memory_vectors.memoryId)"
        guard let statement = try? stores.database.prepare(sql) else { return 0 }
        guard (try? statement.step()) != nil else { return 0 }
        return stores.database.lastChangeCount()
    }

    /// 备份导入 / 清空数据之后调用：忘记「已经补齐过」的判断与查询缓存，
    /// 下次 ensureIndex 会按库里现在的内容重新补一遍。
    public func resetEnsured() {
        lock.lock()
        ensuredPersonas.removeAll()
        cachedQuery = nil
        cachedQueryVector = nil
        lock.unlock()
    }

    /// 低内存回调用：丢掉缓存的查询嵌入。
    /// 记忆向量本来就不常驻，所以这里没有「全表缓存」需要清 —— 这正是设计成这样的原因。
    public func clearCache() {
        lock.lock()
        cachedQuery = nil
        cachedQueryVector = nil
        lock.unlock()
    }

    // MARK: - 内部

    /// 库里这条向量还算不算数：provider 与维度都得对上。
    ///
    /// 换嵌入实现时**只改** `LocalEmbedding.PROVIDER`，旧向量就在这一步自然失效——
    /// 不需要迁移脚本，也不会出现「旧向量按新含义被解读」这种最难查的错。
    private func isUsableVector(provider: String, dim: Int) -> Bool {
        provider == LocalEmbedding.PROVIDER && dim == LocalEmbedding.DIM
    }

    /// 正文 → 一行向量实体；空正文/纯标点（零向量）返回 nil，表示「这条记忆不该有向量」。
    private func encodeRow(memoryId: String, content: String) -> MemoryVectorEntity? {
        let text = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if memoryId.trimmingCharacters(in: .whitespaces).isEmpty || text.isEmpty { return nil }
        let vector = LocalEmbedding.embed(text)
        if VectorMath.isZero(vector) { return nil }
        return MemoryVectorEntity(
            memoryId: memoryId,
            provider: LocalEmbedding.PROVIDER,
            dim: LocalEmbedding.DIM,
            vector: VectorMath.encode(vector),
            updatedAt: now()
        )
    }

    /// 查询文本 → 查询向量；空查询、纯虚词（零向量）返回 nil，调用方直接跳过这一路。
    private func queryVectorOf(_ query: String) -> [Float]? {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalized.isEmpty { return nil }
        lock.lock()
        if let cached = cachedQueryVector, cachedQuery == normalized {
            lock.unlock()
            return cached
        }
        lock.unlock()
        let vector = LocalEmbedding.embed(normalized)
        if VectorMath.isZero(vector) { return nil }
        lock.lock()
        cachedQuery = normalized
        cachedQueryVector = vector
        lock.unlock()
        return vector
    }
}
