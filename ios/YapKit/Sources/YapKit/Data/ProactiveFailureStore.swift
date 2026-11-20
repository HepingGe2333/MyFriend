import Foundation

// ---------------------------------------------------------------------------
// 主动消息失败记录（0.15）：服务端专有的诊断段在客户端的落点。
//
// 服务端有一次「没发出去的主动消息」时会把 stage/reason/detail 记进
// `extensions.proactiveFailures[]`（它自己的 `proactive_failures.json`）。客户端在
// **导入服务端备份**时把这些记录落到本机，于是「他今天怎么一句话没说」在设置页/日志里
// 也能查，而不必去服务器上翻文件。
//
// 三个刻意的取舍：
// 1. **不是新表**：这是诊断数据（不按字段查询、不参与跨端 schema），与
//    `ServerMediaMetaStore` 一样存在 `server_sync_state` 的 key-value 里；
//    Android 的客户端没有这张表，为它升 schema 只会制造两端版本差。
// 2. **只读不写**：客户端备份格式里没有 `proactiveFailures`（导出不写），
//    它也不是本机产生的——只有服务端权威模式才会产生主动消息失败。
// 3. **只留最近 200 条**（与服务端 `ProactiveFailure.KEEP_RECENT` 同一个数字）：
//    诊断数据不该无限增长。
// ---------------------------------------------------------------------------

/// 主动消息失败记录的本机台账（存在 `server_sync_state`，不是业务表）。
public final class ProactiveFailureStore {

    /// 最多留多少条（与服务端 `ProactiveFailure.KEEP_RECENT` 一致）。
    public static let maxRecords = 200

    private let state: ServerSyncStateStore
    private let now: () -> Int64

    public init(state: ServerSyncStateStore, now: @escaping () -> Int64 = { DomainClock.nowMillis }) {
        self.state = state
        self.now = now
    }

    public convenience init(
        database: SQLiteDatabase,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.init(state: ServerSyncStateStore(database: database), now: now)
    }

    /// 本机现有的记录（按时间从新到旧；坏 JSON 一律当空，不让诊断数据拖垮导入）。
    public func all() -> [BackupProactiveFailure] {
        guard let json = (try? state.value(ServerSyncStateStore.Key.proactiveFailures)) ?? nil,
              let data = json.data(using: .utf8),
              let records = try? JSONDecoder().decode([BackupProactiveFailure].self, from: data) else {
            return []
        }
        return records.sorted { $0.at > $1.at }
    }

    /// 追加一批（按 id 去重、保留最近 200 条）；返回最终条数。
    ///
    /// 同一个 id 重复导入（同一份备份导两次）不会变成两条。
    @discardableResult
    public func append(_ failures: [BackupProactiveFailure]) -> Int {
        guard !failures.isEmpty else { return all().count }
        var byId: [String: BackupProactiveFailure] = [:]
        for record in all() { byId[record.id] = record }
        for record in failures {
            let id = record.id.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty else { continue }
            byId[id] = record
        }
        let kept = byId.values
            .sorted { lhs, rhs in
                if lhs.at != rhs.at { return lhs.at > rhs.at }
                return lhs.id > rhs.id
            }
            .prefix(Self.maxRecords)
        return save(Array(kept))
    }

    /// 整段替换（`replace` 导入的语义：备份就是新状态）。
    @discardableResult
    public func replaceAll(_ failures: [BackupProactiveFailure]) -> Int {
        let kept = failures
            .filter { !$0.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .sorted { $0.at > $1.at }
            .prefix(Self.maxRecords)
        return save(Array(kept))
    }

    public func clear() {
        try? state.set(nil, for: ServerSyncStateStore.Key.proactiveFailures, at: now())
    }

    private func save(_ records: [BackupProactiveFailure]) -> Int {
        guard !records.isEmpty else {
            clear()
            return 0
        }
        guard let data = try? JSONEncoder().encode(records),
              let json = String(data: data, encoding: .utf8) else {
            return all().count
        }
        // 写失败不影响导入主流程（诊断数据丢了只是少一段日志）。
        try? state.set(json, for: ServerSyncStateStore.Key.proactiveFailures, at: now())
        return records.count
    }
}
