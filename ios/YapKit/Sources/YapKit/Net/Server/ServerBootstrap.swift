import Foundation

// ---------------------------------------------------------------------------
// 首次连上一台服务端时的**双向全量同步**（bootstrap，0.12.1）。
//
// 为什么需要它：切到另一台服务端时，两边看到的不是同一段关系。
// - 新装客户端 + 服务端已有历史 → 本机是空的，首页/聊天页什么都看不到；
// - 本机用了很久 + 新服务端 → 服务端只有几个种子人格，你们的几千条聊天不在；
// - 两边都有 → 各有对方没有的部分。
// 增量同步（`ServerSyncService`，按游标）解决不了这三种情况：它只搬「游标之后」的增量。
//
// 状态机（每个 `baseURL` 只跑一次，记录键 `server_bootstrap_done:<url>`）：
//
//   读服务端快照（GET /v1/state?since=0）+ 读本机库
//      ├─ 服务端空、本机有 → PUSH：人格/预设/记忆/设置 + 会话 + 每会话 200 条/批 import
//      ├─ 服务端有、本机空 → PULL：personas/sessions/预设/记忆/设置 + 每会话倒序翻完所有块
//      ├─ 两边都有        → MERGE：先推本机独有（服务端优先），再全量拉，最后推本机独有消息
//      └─ 两边都空        → EMPTY：什么都不做（只落完成标记）
//
// 三条硬约束（也是这个类为什么这么写）：
// 1. **幂等**：所有写都是按 id upsert 或 import（服务端按 id 去重）。进度按「阶段 / 会话」
//    落进 `server_sync_state`，重试用同一条路径不会造出第二份；不依赖服务端生成新 id
//    （真遇到服务端不接受本机 id，回落成「不带 id 新建」，并把映射记下来，重试不会再建一次）。
// 2. **可重试**：整体按 `ServerSyncService.RetryPolicy` 指数退避重试；失败保留进度，
//    下一次进前台接着跑。
// 3. **可显示进度**：每个阶段/每个会话/每批消息都回调一次 `ServerBootstrapProgress`，
//    设置页把它显示成一行中文；界面拿不到 actor，所以回调注入。
//
// 拉取时消息**升序落库**：本地 `message_blocks` 是按写入顺序切块的，倒序写会让「最新一块」
// 变成最旧那一块（首屏直接翻到一年前）。所以倒序翻页只为少记状态，写库前统一排序。
// ---------------------------------------------------------------------------

// MARK: - 进度与报告

/// bootstrap 的方向（也是「这台服务端当时是什么状态」的记录）。
public enum ServerBootstrapMode: String, Codable, Equatable, Sendable {
    /// 服务端没有数据、本机有：全量推送。
    case push = "PUSH"
    /// 服务端有数据、本机没有：全量拉取。
    case pull = "PULL"
    /// 两边都有：先推本机独有，再全量拉，最后补推本机独有消息。
    case merge = "MERGE"
    /// 两边都没有：只落完成标记。
    case empty = "EMPTY"

    public var label: String {
        switch self {
        case .push: return "把本机的搬上去"
        case .pull: return "把服务端的取回来"
        case .merge: return "两边合到一起"
        case .empty: return "不用搬"
        }
    }
}

/// 一次 bootstrap 的进度快照（给设置页显示）。
public struct ServerBootstrapProgress: Equatable, Sendable {
    public enum Stage: String, Equatable, Sendable {
        case detecting
        case pushing
        case pulling
        case done
    }

    public var stage: Stage
    public var mode: ServerBootstrapMode
    /// 已经处理完的条目数（人格 + 预设 + 记忆所属人格 + 设置 + 会话）。
    public var completed: Int
    public var total: Int
    /// 一句话补充（例如「消息 320/900」）；空串表示没有。
    public var detail: String
    public var at: Int64

    public init(
        stage: Stage,
        mode: ServerBootstrapMode,
        completed: Int = 0,
        total: Int = 0,
        detail: String = "",
        at: Int64 = 0
    ) {
        self.stage = stage
        self.mode = mode
        self.completed = completed
        self.total = total
        self.detail = detail
        self.at = at
    }

    /// 给用户看的一行中文（设置页直接显示，不出现「bootstrap / 同步任务」这类词）。
    public var describe: String {
        let suffix = detail.isEmpty ? "" : " · \(detail)"
        switch stage {
        case .detecting:
            return "正在核对这台机器和那边的记录…"
        case .pushing:
            return "正在把本机的记录搬过去（\(completed)/\(max(total, completed))）\(suffix)"
        case .pulling:
            return "正在把那边的记录取回来（\(completed)/\(max(total, completed))）\(suffix)"
        case .done:
            return "已经接上了：\(mode.label)\(suffix)"
        }
    }
}

/// 一次 bootstrap 的对账结果（日志、测试、设置页「上次同步」都用它）。
public struct ServerBootstrapReport: Equatable, Sendable {
    public var mode: ServerBootstrapMode
    public var personasPushed: Int
    public var personasPulled: Int
    public var presetsPushed: Int
    public var presetsPulled: Int
    public var memoriesPushed: Int
    public var memoriesPulled: Int
    public var sessionsPushed: Int
    public var sessionsPulled: Int
    /// 服务端**实际新写入**的消息条数（`imported` 之和）。
    public var messagesPushed: Int
    /// 服务端已经有了、跳过的条数（`skipped` 之和，重试时会很大）。
    public var messagesSkipped: Int
    public var messagesPulled: Int
    /// 推了多少批（每批 ≤200 条）。
    public var batches: Int
    /// 因为本地 `file://` 图不在服务端而丢掉的附件引用数（见报告里的取舍）。
    public var attachmentsDropped: Int
    public var settingsPushed: Bool
    public var settingsPulled: Bool
    /// 这次有没有把本地头像上传成 `USER_AVATAR`（推设置时顺手做的）。
    public var avatarUploaded: Bool
    public var at: Int64

    public init(
        mode: ServerBootstrapMode = .empty,
        personasPushed: Int = 0,
        personasPulled: Int = 0,
        presetsPushed: Int = 0,
        presetsPulled: Int = 0,
        memoriesPushed: Int = 0,
        memoriesPulled: Int = 0,
        sessionsPushed: Int = 0,
        sessionsPulled: Int = 0,
        messagesPushed: Int = 0,
        messagesSkipped: Int = 0,
        messagesPulled: Int = 0,
        batches: Int = 0,
        attachmentsDropped: Int = 0,
        settingsPushed: Bool = false,
        settingsPulled: Bool = false,
        avatarUploaded: Bool = false,
        at: Int64 = 0
    ) {
        self.mode = mode
        self.personasPushed = personasPushed
        self.personasPulled = personasPulled
        self.presetsPushed = presetsPushed
        self.presetsPulled = presetsPulled
        self.memoriesPushed = memoriesPushed
        self.memoriesPulled = memoriesPulled
        self.sessionsPushed = sessionsPushed
        self.sessionsPulled = sessionsPulled
        self.messagesPushed = messagesPushed
        self.messagesSkipped = messagesSkipped
        self.messagesPulled = messagesPulled
        self.batches = batches
        self.attachmentsDropped = attachmentsDropped
        self.settingsPushed = settingsPushed
        self.settingsPulled = settingsPulled
        self.avatarUploaded = avatarUploaded
        self.at = at
    }

    public var describe: String {
        "\(mode.label)：人格 \(personasPushed)/\(personasPulled)、预设 \(presetsPushed)/\(presetsPulled)"
            + "、记忆 \(memoriesPushed)/\(memoriesPulled)、会话 \(sessionsPushed)/\(sessionsPulled)"
            + "、消息 推 \(messagesPushed)（跳过 \(messagesSkipped)）/拉 \(messagesPulled)"
            + "\(attachmentsDropped > 0 ? "、丢弃本地图引用 \(attachmentsDropped)" : "")"
    }
}

public enum ServerBootstrapOutcome: Equatable, Sendable {
    /// 已经做过 / 服务端模式没开 / 正在跑：什么都不做。
    case skipped(reason: String)
    case completed(ServerBootstrapReport)
    case failed(message: String, attempts: Int)
}

/// 完成标记的内容（`server_bootstrap_done:<url>` 的值）。
public struct ServerBootstrapDone: Codable, Equatable, Sendable {
    public var at: Int64
    public var mode: String
    public var messages: Int

    public init(at: Int64, mode: String, messages: Int) {
        self.at = at
        self.mode = mode
        self.messages = messages
    }

    /// 给设置页显示的一行（进程重启后没有内存里的 report，只能从这里还原）。
    public var describe: String {
        let label = ServerBootstrapMode(rawValue: mode)?.label ?? mode
        return "上次接上时：\(label) · 推上去 \(messages) 条"
    }
}

// MARK: - 服务

/// 首次连接某台服务端时的双向全量同步。
///
/// 与 `ServerSyncService` 一样是 `actor`：同一时刻只该有一次在跑（两次并发会各自以为
/// 「服务端还没有这段历史」而重复推，虽然幂等但白跑几百个请求）。
public actor ServerBootstrapService {

    /// 每个 baseURL 一个完成标记。
    public static let doneKeyPrefix = "server_bootstrap_done:"
    /// 未完成时的进度（重试用它跳过已经做过的会话）。
    public static let progressKeyPrefix = "server_bootstrap_progress:"

    /// 服务端一页消息最多翻多少块（防御性上限：坏服务端一直说 hasMoreOlder 时别死循环）。
    static let maxBlocksPerSession = 10_000
    /// 一次从本地读多少条消息（推历史时分页读，不把整段历史一次读进内存）。
    static let localMessagePageSize = 400
    /// 拉记忆时一次要多少条（服务端默认 200）。
    static let pullMemoryLimit = 500

    private let client: ServerAPIClient
    private let stores: DatabaseStores
    private let state: ServerSyncStateStore
    private let resolver: ServerSessionResolver
    /// 服务端设置拉回来要落到这里；单独构造（测试不关心设置）时可为 nil。
    private let settings: SettingsStore?
    /// 拉回来的记忆要重建倒排/向量索引；不注入时只写库（召回会退回到关键词路径）。
    private let memories: MemoryRepository?
    /// 本地头像上传（推设置时顺手做）；不注入时本地头像不推到服务端。
    private let avatarUploader: ServerUserAvatarUploader?
    private let retry: ServerSyncService.RetryPolicy
    private let now: () -> Int64
    private let log: (String) -> Void
    private let sleeper: (Double) async -> Void
    private let progress: (@Sendable (ServerBootstrapProgress) -> Void)?

    private var inFlight = false
    /// 上一次读到的服务端快照里的会话（推消息时用它判断「服务端是不是已经有这一段」）。
    private var serverSessions: [String: ServerSession] = [:]
    /// 本次运行里已经处理过的会话（服务端 id），避免同一会话在推/拉两阶段重复。
    private var pulledMessageIds: [String: Set<String>] = [:]
    /// **服务端人格 id → 本机人格 id**（只在两边 id 不同时才有这一行）。
    ///
    /// 为什么必须有它：本机模式先播了种子人格（id 是本机 UUID），服务端也播了自己的种子
    /// 人格（id 是 `p-…`），名字一样、人是一样的。`pushEntities` 已经会用 id 映射 / 同名
    /// 判据认成同一个人、不在服务端另建；如果**拉取**只按服务端 id upsert，首页就会出现
    /// 两个「林深」（一个带本机历史、一个带服务端历史），而且服务端那段会话还会挂到
    /// 一个本机不存在的人格上。这里把服务端 id 折算回本机 id，会话/消息/记忆一起折算。
    private var personaAlias: [String: String] = [:]

    public init(
        client: ServerAPIClient,
        stores: DatabaseStores,
        state: ServerSyncStateStore? = nil,
        settings: SettingsStore? = nil,
        memories: MemoryRepository? = nil,
        avatarUploader: ServerUserAvatarUploader? = nil,
        retry: ServerSyncService.RetryPolicy = ServerSyncService.RetryPolicy(),
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in },
        sleeper: @escaping (Double) async -> Void = { seconds in
            guard seconds > 0 else { return }
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        },
        progress: (@Sendable (ServerBootstrapProgress) -> Void)? = nil
    ) {
        self.client = client
        self.stores = stores
        self.state = state ?? ServerSyncStateStore(database: stores.database)
        self.resolver = ServerSessionResolver(stores: stores)
        self.settings = settings
        self.memories = memories
        self.avatarUploader = avatarUploader
        self.retry = retry
        self.now = now
        self.log = log
        self.sleeper = sleeper
        self.progress = progress
    }

    // MARK: 读

    /// 当前 baseURL 的完成标记（没做过返回 nil）。
    public func doneRecord() -> ServerBootstrapDone? {
        guard let raw = state.string(Self.doneKey(baseURLKey())),
              let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(ServerBootstrapDone.self, from: data)
    }

    /// 这台服务端是不是已经做完全量同步。
    public func isBootstrapped() -> Bool {
        doneRecord() != nil
    }

    /// 忘掉「已经做过」与未完成进度（用户手动重来 / 换了一份数据之后）。
    public func reset() throws {
        try state.set(nil, for: Self.doneKey(baseURLKey()), at: now())
        try state.set(nil, for: Self.progressKey(baseURLKey()), at: now())
    }

    // MARK: 主流程

    /// 跑一次全量同步。
    ///
    /// - Parameters:
    ///   - force: 忽略完成标记与「正在跑」标志（设置页「重新同步」/ 测试用）。
    ///     生产路径不要传：它会让每次进前台都重推一遍全量。
    public func bootstrap(force: Bool = false) async -> ServerBootstrapOutcome {
        guard client.config.isUsable else {
            return .skipped(reason: "服务端模式未启用")
        }
        if inFlight && !force { return .skipped(reason: "已经在做首次同步了") }
        inFlight = true
        defer { inFlight = false }

        if !force, isBootstrapped() {
            return .skipped(reason: "这台服务端已经做过首次同步")
        }
        if force {
            // 手动重来：完成标记与进度都清掉（进度清了才会真的重推；幂等所以安全）。
            try? reset()
        }

        var attempt = 0
        var lastMessage = ""
        while attempt < retry.maxAttempts {
            attempt += 1
            do {
                let report = try await run(resuming: !force)
                markDone(report)
                publish(ServerBootstrapProgress(
                    stage: .done,
                    mode: report.mode,
                    completed: report.personasPushed + report.presetsPushed
                        + report.sessionsPushed + report.personasPulled
                        + report.presetsPulled + report.sessionsPulled,
                    total: report.personasPushed + report.presetsPushed
                        + report.sessionsPushed + report.personasPulled
                        + report.presetsPulled + report.sessionsPulled,
                    detail: report.describe,
                    at: now()
                ))
                log("首次同步完成：\(report.describe)")
                return .completed(report)
            } catch is CancellationError {
                return .failed(message: "首次同步被取消", attempts: attempt)
            } catch {
                lastMessage = ServerSyncService.describe(error)
                log("首次同步失败（第 \(attempt)/\(retry.maxAttempts) 次）：\(lastMessage)")
                guard attempt < retry.maxAttempts else { break }
                await sleeper(retry.delay(after: attempt))
            }
        }
        return .failed(message: lastMessage, attempts: attempt)
    }

    // MARK: 内部：一轮

    private func run(resuming: Bool) async throws -> ServerBootstrapReport {
        let at = now()
        // 每次重试都是一轮新的对账：上一轮失败前记下的折算关系可能已经过时（用户删了人格）。
        personaAlias.removeAll()
        pulledMessageIds.removeAll()
        publish(ServerBootstrapProgress(
            stage: .detecting, mode: .empty, detail: "", at: at
        ))

        // 1) 服务端此刻的样子。`since=0` 是唯一能一次拿到「有哪些人格/会话」的接口。
        let snapshot = try await client.state(since: 0)
        serverSessions = Dictionary(
            snapshot.sessions.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )

        // 2) 本机此刻的样子。
        let localPersonas = try stores.personas.all(orderBy: "createdAt ASC")
        let localPresets = try stores.presets.allOrderedByUpdatedAt()
        let localSessions = try stores.sessions.all(orderBy: "createdAt ASC")

        let serverHasData = !snapshot.personas.isEmpty || !snapshot.sessions.isEmpty
        let localHasData = !localPersonas.isEmpty || !localSessions.isEmpty
        let mode: ServerBootstrapMode
        switch (localHasData, serverHasData) {
        case (true, false): mode = .push
        case (false, true): mode = .pull
        case (true, true): mode = .merge
        case (false, false): mode = .empty
        }
        log("首次同步：本机 \(localPersonas.count) 人 / \(localSessions.count) 段会话，"
            + "服务端 \(snapshot.personas.count) 人 / \(snapshot.sessions.count) 段会话 → \(mode.rawValue)")

        var report = ServerBootstrapReport(mode: mode, at: at)
        if mode == .empty {
            // 两边都没有：仍然把设置推一次（主题/头像这类东西没有历史也能搬），
            // 但不再造会话。失败不影响「完成」——下一次进前台会重试。
            try? await pushSettings(personaIdMap: [:], report: &report)
            return report
        }

        var record = resuming ? loadRecord() : nil
        let total = localPersonas.count + localPresets.count + 1
            + localPersonas.count
            + (mode == .pull ? 0 : localSessions.count)
            + (mode == .push ? 0 : snapshot.personas.count + snapshot.sessions.count + 1)

        // 3) 推本机独有（服务端优先：同 id / 同名的一律不推）。
        var personaIdMap: [String: String] = [:]
        if mode == .push || mode == .merge {
            if record?.entitiesPushed != true {
                personaIdMap = try await pushEntities(
                    snapshot: snapshot,
                    localPersonas: localPersonas,
                    localPresets: localPresets,
                    report: &report,
                    progressBase: 0,
                    progressTotal: total
                )
                record = (record ?? ServerBootstrapRecord(mode: mode.rawValue, startedAt: at))
                record?.entitiesPushed = true
                saveRecord(record)
            } else {
                personaIdMap = loadPersonaIdMap(localIds: localPersonas.map(\.id))
                catchUpEntityCounts(localPersonas: localPersonas, report: &report)
            }
        }

        // 4) 全量拉取（服务端优先：冲突时服务端的值覆盖本机缓存）。
        if mode == .pull || mode == .merge {
            // 上一次跑到一半（或上一次已经推完）：把「本机人格 → 服务端人格」反向折算铺好，
            // 拉取时才不会把服务端那一份当成本机没见过的第二个人（见 `personaAlias`）。
            primePersonaAlias(localPersonas: localPersonas)
            if record?.pulledAll != true {
                try await pullAll(
                    snapshot: snapshot,
                    report: &report,
                    record: &record,
                    progressBase: (mode == .merge)
                        ? localPersonas.count + localPresets.count + 1 + localPersonas.count + localSessions.count
                        : 0,
                    progressTotal: total
                )
                record = (record ?? ServerBootstrapRecord(mode: mode.rawValue, startedAt: at))
                record?.pulledAll = true
                saveRecord(record)
            } else {
                report.personasPulled = snapshot.personas.count
                report.sessionsPulled = snapshot.sessions.count
            }
        }

        // 5) 推会话与消息（push：全部；merge：只推服务端还没有的那些消息）。
        if mode == .push || mode == .merge {
            if personaIdMap.isEmpty {
                personaIdMap = loadPersonaIdMap(localIds: localPersonas.map(\.id))
            }
            // 推会话时要用「服务端人格 id → 本机人格 id」把会话认回本机那一段
            //（服务端另建过人格 id 时尤其重要，见 `personaAlias`）。
            primePersonaAlias(localPersonas: localPersonas)
            try await pushSessions(
                localSessions: localSessions,
                personaIdMap: personaIdMap,
                snapshot: snapshot,
                onlyMissing: mode == .merge,
                report: &report,
                record: &record,
                progressBase: max(0, total - localSessions.count),
                progressTotal: total
            )
        }

        // 6) 拉取时拿到游标：之后走增量（`since=cursor`），不会再从头拉一遍。
        if mode == .pull || mode == .merge, snapshot.serverCursor > 0 {
            try state.advanceCursor(to: snapshot.serverCursor, at: at)
            try state.set(String(at), for: ServerSyncStateStore.Key.lastSyncAt, at: at)
        }

        report.at = now()
        return report
    }

    // MARK: 内部：推实体

    /// 推人格 / 预设 / 记忆 / 设置。返回 `本地人格 id → 服务端人格 id` 的映射。
    private func pushEntities(
        snapshot: ServerStateSnapshot,
        localPersonas: [PersonaEntity],
        localPresets: [PersonaPresetEntity],
        report: inout ServerBootstrapReport,
        progressBase: Int,
        progressTotal: Int
    ) async throws -> [String: String] {
        var completed = progressBase
        var personaIdMap: [String: String] = [:]
        publishPush(completed: completed, total: progressTotal, detail: "人格", mode: report.mode)

        // ---- 人格 ----
        var matchedServerPersonaIds = Set<String>()
        for persona in localPersonas {
            completed += 1
            if let mapped = state.string(ServerSyncStateStore.Key.personaIdMap(persona.id)) {
                personaIdMap[persona.id] = mapped
                matchedServerPersonaIds.insert(mapped)
                continue
            }
            if let existing = snapshot.personas.first(where: { $0.id == persona.id }) {
                // 服务端已经有同 id：服务端优先，记下映射就够。
                personaIdMap[persona.id] = existing.id
                matchedServerPersonaIds.insert(existing.id)
                try state.set(persona.id, for: ServerSyncStateStore.Key.personaIdMap(persona.id), at: now())
                continue
            }
            if let sameName = snapshot.personas.first(where: {
                !matchedServerPersonaIds.contains($0.id) && Self.samePersonaName($0.name, persona.name)
            }) {
                // 同名不同 id（本机模式播的种子人 vs 服务端播的同一批人）：认成同一个人，
                // 不新建第二个人（新建会让首页出现两个「林深」）。
                personaIdMap[persona.id] = sameName.id
                matchedServerPersonaIds.insert(sameName.id)
                try state.set(sameName.id, for: ServerSyncStateStore.Key.personaIdMap(persona.id), at: now())
                continue
            }
            let serverId = try await pushPersona(persona)
            personaIdMap[persona.id] = serverId
            matchedServerPersonaIds.insert(serverId)
            try state.set(serverId, for: ServerSyncStateStore.Key.personaIdMap(persona.id), at: now())
            report.personasPushed += 1
            publishPush(completed: completed, total: progressTotal, detail: "人格 \(persona.name)", mode: report.mode)
        }

        // ---- 预设 ----
        // 服务端空的时候不必先 GET（它一定没有）；merge 时拉一次，避免按 id 重复新建。
        let serverPresets: [[String: JSONValue]]
        if report.mode == .push {
            serverPresets = []
        } else {
            serverPresets = (try? await client.presets()) ?? []
        }
        let serverPresetIds = Set(serverPresets.compactMap { $0["id"]?.stringValue })
        let serverPresetNames = Set(serverPresets.compactMap { $0["name"]?.stringValue })
        for preset in localPresets {
            completed += 1
            if state.string(ServerSyncStateStore.Key.presetIdMap(preset.id)) != nil { continue }
            if serverPresetIds.contains(preset.id) {
                try state.set(preset.id, for: ServerSyncStateStore.Key.presetIdMap(preset.id), at: now())
                continue
            }
            if serverPresetNames.contains(preset.name), !preset.name.isEmpty {
                try state.set(preset.id, for: ServerSyncStateStore.Key.presetIdMap(preset.id), at: now())
                continue
            }
            let serverId = try await pushPreset(preset)
            try state.set(serverId, for: ServerSyncStateStore.Key.presetIdMap(preset.id), at: now())
            report.presetsPushed += 1
            publishPush(completed: completed, total: progressTotal, detail: "预设 \(preset.name)", mode: report.mode)
        }

        // ---- 记忆（按人格：先看服务端已有，再推本机独有）----
        for persona in localPersonas {
            completed += 1
            let serverPersonaId = personaIdMap[persona.id] ?? persona.id
            let localMemories = (try? stores.memories.byPersona(persona.id)) ?? []
            guard !localMemories.isEmpty else { continue }
            let existing: [[String: JSONValue]]
            if report.mode == .push {
                existing = []
            } else {
                let response = try? await client.memories(
                    personaId: serverPersonaId,
                    limit: Self.pullMemoryLimit
                )
                existing = response?["memories"]?.arrayValue?.compactMap(\.objectValue) ?? []
            }
            let existingIds = Set(existing.compactMap { $0["id"]?.stringValue })
            let existingContents = Set(existing.compactMap { $0["content"]?.stringValue })
            for memory in localMemories {
                if state.string(ServerSyncStateStore.Key.memoryIdMap(memory.id)) != nil { continue }
                if existingIds.contains(memory.id) {
                    try state.set(memory.id, for: ServerSyncStateStore.Key.memoryIdMap(memory.id), at: now())
                    continue
                }
                let content = memory.content.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !content.isEmpty else { continue }
                // 内容相同的记忆视为「服务端已经有了」：服务端的记忆模型没有 drawer/status，
                // 按内容比对是唯一能防住重复的判据（也防住了重试时服务端换了 id 的情况）。
                if existingContents.contains(content) {
                    try state.set(memory.id, for: ServerSyncStateStore.Key.memoryIdMap(memory.id), at: now())
                    continue
                }
                let serverId = try await pushMemory(memory, serverPersonaId: serverPersonaId)
                try state.set(serverId, for: ServerSyncStateStore.Key.memoryIdMap(memory.id), at: now())
                report.memoriesPushed += 1
            }
        }

        // ---- 设置（含头像）----
        //
        // 只在 PUSH 时推：MERGE 的服务端已经有设置，而「推设置」是**白名单合并**
        // （服务端按我们发过去的键覆盖），先推再拉等于用本机的旧值盖掉服务端的权威值，
        // 与「冲突服务端优先」正好相反。MERGE 的设置一律由 `pullAll` 拉回来。
        completed += 1
        if report.mode == .push {
            try await pushSettings(personaIdMap: personaIdMap, report: &report)
        }
        publishPush(completed: completed, total: progressTotal, detail: "设置", mode: report.mode)
        return personaIdMap
    }

    /// 服务端人格 body 推上去；返回服务端最终使用的 id。
    ///
    /// 两种失败要分开：
    /// - 网络/鉴权/5xx：原样抛出，交给外层重试；
    /// - 服务端「不认识这个 id」（404 / 400）：回落成「不带 id 新建」，让服务端生成 id。
    ///   这一步之所以必要：服务端的 `POST /v1/personas` 语义是「带 id = 更新」，
    ///   更新一个不存在的 id 会 404。
    private func pushPersona(_ persona: PersonaEntity) async throws -> String {
        let body = ServerWriteService.personaBody(persona, includeID: true)
        do {
            let envelope = try await client.savePersona(body)
            if let id = envelope.persona?["id"]?.stringValue, !id.isEmpty { return id }
            return persona.id
        } catch let error as ServerAPIError where Self.isUnknownIdRejection(error) {
            var withoutId = ServerWriteService.personaBody(persona, includeID: false)
            withoutId.removeValue(forKey: "id")
            let envelope = try await client.savePersona(withoutId)
            guard let id = envelope.persona?["id"]?.stringValue, !id.isEmpty else {
                throw ServerWriteError.invalidResponse("新建人格后服务端没有返回 id")
            }
            log("服务端不接受本机人格 id（\(persona.id)），已改用服务端 id \(id)")
            return id
        }
    }

    private func pushPreset(_ preset: PersonaPresetEntity) async throws -> String {
        let body = ServerWriteService.presetBody(preset, includeID: true)
        do {
            let envelope = try await client.savePreset(body)
            if let id = envelope.preset?["id"]?.stringValue, !id.isEmpty { return id }
            return preset.id
        } catch let error as ServerAPIError where Self.isUnknownIdRejection(error) {
            var withoutId = ServerWriteService.presetBody(preset, includeID: false)
            withoutId.removeValue(forKey: "id")
            let envelope = try await client.savePreset(withoutId)
            guard let id = envelope.preset?["id"]?.stringValue, !id.isEmpty else {
                throw ServerWriteError.invalidResponse("新建预设后服务端没有返回 id")
            }
            return id
        }
    }

    private func pushMemory(_ memory: MemoryEntity, serverPersonaId: String) async throws -> String {
        let body = ServerWriteService.memoryBody(memory, includeID: true)
        do {
            let envelope = try await client.saveMemory(personaId: serverPersonaId, body: body)
            if let id = envelope.memory?["id"]?.stringValue, !id.isEmpty { return id }
            return memory.id
        } catch let error as ServerAPIError where Self.isUnknownIdRejection(error) {
            var withoutId = ServerWriteService.memoryBody(memory, includeID: false)
            withoutId.removeValue(forKey: "id")
            let envelope = try await client.saveMemory(personaId: serverPersonaId, body: withoutId)
            guard let id = envelope.memory?["id"]?.stringValue, !id.isEmpty else {
                throw ServerWriteError.invalidResponse("新建记忆后服务端没有返回 id")
            }
            return id
        }
    }

    /// 推设置。本机如果有一个「只在本地」的头像，先上传成 `USER_AVATAR` 再推
    ///（否则设置里那个 `file://` 推过去在别的设备上就是一张坏图）。
    ///
    /// - Parameter personaIdMap: 本机人格 id → 服务端人格 id。`activePersonaId` 必须换算：
    ///   服务端另发过人格 id 时，直接推本机 id 会被它判成「指向不存在的人格」整条 400，
    ///   于是每次进前台都失败（设置其它键也跟着推不上去）。
    private func pushSettings(
        personaIdMap: [String: String],
        report: inout ServerBootstrapReport
    ) async throws {
        guard let settings else {
            // 没注入设置来源：只推一份默认值没有意义，跳过。
            return
        }
        let original = settings.load()
        var current = original
        if let active = original.activePersonaId,
           let mapped = personaIdMap[active], mapped != active {
            current.activePersonaId = mapped
        }
        if let avatar = current.myAvatarUri,
           !ServerMediaReference.isServerMediaURI(avatar),
           let uploader = avatarUploader {
            // 服务端已经有头像就不要再覆盖（服务端优先）。
            let serverSettings = try? await client.settings()
            let serverHasAvatar = serverSettings.map(ServerSettingsMapping.hasServerAvatar) ?? false
            if !serverHasAvatar {
                do {
                    let result = try await uploader.upload(localURI: avatar)
                    current.myAvatarUri = result.uri
                    // 只把头像地址写回本机：`activePersonaId` 上面换成了服务端 id，
                    // 那个 id 对本机没有意义（本机设置必须继续指向本机人格）。
                    var localCopy = original
                    localCopy.myAvatarUri = result.uri
                    settings.save(localCopy)
                    report.avatarUploaded = true
                    log("本机头像已上传：\(result.uri)")
                } catch {
                    // 头像上传失败不该挡住「把聊天搬过去」：只记日志，设置照常推（不带头像键）。
                    log("头像上传失败（继续推设置）：\(ServerSyncService.describe(error))")
                }
            }
        }
        _ = try await client.updateSettings(ServerSettingsMapping.body(current))
        report.settingsPushed = true
    }

    // MARK: 内部：推会话与消息

    private func pushSessions(
        localSessions: [ChatSessionEntity],
        personaIdMap: [String: String],
        snapshot: ServerStateSnapshot,
        onlyMissing: Bool,
        report: inout ServerBootstrapReport,
        record: inout ServerBootstrapRecord?,
        progressBase: Int,
        progressTotal: Int
    ) async throws {
        var completed = progressBase
        var sessionByPersona: [String: ServerSession] = [:]
        for session in snapshot.sessions {
            if sessionByPersona[session.personaId] == nil {
                sessionByPersona[session.personaId] = session
            }
        }
        let at = now()
        for localSession in localSessions {
            completed += 1
            let localId = localSession.id
            if record?.pushedSessions.contains(localId) == true { continue }
            let serverPersonaId = personaIdMap[localSession.personaId] ?? localSession.personaId
            // 1) 找到（或建出）服务端上的那一段会话。
            //
            // 交给 `resolver` 的会话要先把人格 id 折算回本机 id：服务端另建了人格 id 时
            //（`p-1` vs 本机 UUID），按服务端 id 找不到本机那一段，resolver 会**再建一段**
            // 空会话（首页于是多一行、本机历史留在原来那段里）。
            var serverSession: ServerSession?
            if let sid = localSession.serverSessionId, let known = serverSessions[sid] {
                serverSession = known
            } else if let claimed = sessionByPersona[serverPersonaId] {
                serverSession = claimed
                _ = try resolver.localSessionId(
                    forServer: Self.withLocalPersona(claimed, alias: personaAlias), at: at
                )
            } else {
                let creation = try await client.createSession(
                    personaId: serverPersonaId,
                    title: localSession.title.isEmpty ? nil : localSession.title
                )
                serverSession = creation.session
                sessionByPersona[serverPersonaId] = creation.session
                serverSessions[creation.session.id] = creation.session
                _ = try resolver.localSessionId(
                    forServer: Self.withLocalPersona(creation.session, alias: personaAlias), at: at
                )
                report.sessionsPushed += 1
            }
            guard let serverSession else { continue }
            let serverSessionId = serverSession.id

            // 2) 本机这一段有哪些消息要推（merge 时跳过服务端已有的 id）。
            let already = onlyMissing ? (pulledMessageIds[localId] ?? []) : []
            let localRead = try readLocalMessages(sessionId: localId, excluding: already)
            let messages = localRead.messages
            report.attachmentsDropped += localRead.attachmentsDropped
            guard !messages.isEmpty else {
                record = (record ?? ServerBootstrapRecord(mode: report.mode.rawValue, startedAt: at))
                record?.pushedSessions.append(localId)
                saveRecord(record)
                publishPush(completed: completed, total: progressTotal, detail: "会话", mode: report.mode)
                continue
            }

            // 3) 分批 import（≤200 条 / 批）。整批失败会抛出去：外层重试时按 id 幂等。
            let batches = ServerAPIClient.importBatches(for: messages)
            for batch in batches {
                do {
                    let result = try await client.importMessages(
                        sessionId: serverSessionId, messages: batch
                    )
                    report.messagesPushed += result.imported
                    report.messagesSkipped += result.skipped
                    report.batches += 1
                } catch let error as ServerAPIError where error.code == "unknown_media" {
                    // 服务端说某张附件不存在（本地媒体没搬过去）：去掉附件重推这一批，
                    // 至少把文字留下。丢几个引用比整段历史推不上去好。
                    let stripped = batch.map { message -> ServerImportMessage in
                        var copy = message
                        report.attachmentsDropped += copy.attachments.count
                        copy.attachments = []
                        return copy
                    }
                    let result = try await client.importMessages(
                        sessionId: serverSessionId, messages: stripped
                    )
                    report.messagesPushed += result.imported
                    report.messagesSkipped += result.skipped
                    report.batches += 1
                }
                publishPush(
                    completed: completed,
                    total: progressTotal,
                    detail: "消息 \(report.messagesPushed)",
                    mode: report.mode
                )
            }
            record = (record ?? ServerBootstrapRecord(mode: report.mode.rawValue, startedAt: at))
            record?.pushedSessions.append(localId)
            saveRecord(record)
            publishPush(completed: completed, total: progressTotal, detail: "会话", mode: report.mode)
        }
    }

    /// 读本机一段会话的待推消息（分页读，不整段进内存）。
    ///
    /// 过滤规则：
    /// - 排队中的占位消息（`server-pending:`）不推：它还没有服务端 id，也不是历史；
    /// - `status == .error` 的不推（本地模式发送失败的残句，服务端不该收到）；
    /// - 已经存在于服务端的 id（merge 拉回来的）不推；
    /// - 附件只保留服务端媒体引用（`/v1/media/<id>` / `server:<id>`）：本地 `file://` 在服务端
    ///   不存在，硬推会被 `unknown_media` 整批拒掉（见 `pushSessions` 的兜底）。
    private func readLocalMessages(
        sessionId: String,
        excluding existingIds: Set<String>
    ) throws -> (messages: [ServerImportMessage], attachmentsDropped: Int) {
        var result: [ServerImportMessage] = []
        /// 本地 `file://` 图搬不过去（服务端没有这个文件）：去掉引用时数一下，
        /// 报告里要如实告诉用户「几条附件丢了」，不能悄悄少几张图。
        var attachmentsDropped = 0
        var cursorAt = Int64.min
        var cursorId = ""
        while true {
            let page = try stores.messages.pageAfter(
                sessionId: sessionId,
                createdAt: cursorAt,
                id: cursorId,
                limit: Self.localMessagePageSize
            )
            if page.isEmpty { break }
            for message in page {
                if existingIds.contains(message.id) { continue }
                if message.id.hasPrefix(ServerChatGateway.pendingIdPrefix) { continue }
                if message.status == .error { continue }
                let attachments = Self.serverAttachments(from: message.attachmentsJson)
                let localAttachmentCount = AttachmentsCodec.decode(message.attachmentsJson).count
                attachmentsDropped += max(0, localAttachmentCount - attachments.count)
                let content = message.content
                // 正文为空且没有任何（可搬的）附件：没有意义的空行，不推。
                guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        || !attachments.isEmpty else { continue }
                result.append(ServerImportMessage(
                    id: ServerAPIClient.normalizedImportId(message.id),
                    role: message.role.stored,
                    content: content,
                    createdAt: message.createdAt,
                    readAt: message.readAt,
                    attachments: attachments,
                    source: "IMPORT"
                ))
            }
            guard let last = page.last else { break }
            cursorAt = last.createdAt
            cursorId = last.id
            if page.count < Self.localMessagePageSize { break }
        }
        // 同一批里 id 冲突（本地历史里偶发的重复 id）只留第一条：服务端按 id 去重，
        // 但它对「同一请求里出现两次相同 id」的行为没有定义，客户端先自己擦干净。
        var seen = Set<String>()
        return (result.filter { seen.insert($0.id).inserted }, attachmentsDropped)
    }

    /// `attachmentsJson` → 服务端媒体引用（只保留服务端认识的）。
    static func serverAttachments(from json: String?) -> [String] {
        let items = AttachmentsCodec.decode(json)
        var result: [String] = []
        var seen = Set<String>()
        for item in items {
            guard let id = ServerMediaReference.mediaId(from: item.uri) else { continue }
            let uri = ServerMediaReference.uri(id: id)
            if seen.insert(uri).inserted { result.append(uri) }
        }
        return result
    }

    // MARK: 内部：拉取

    private func pullAll(
        snapshot: ServerStateSnapshot,
        report: inout ServerBootstrapReport,
        record: inout ServerBootstrapRecord?,
        progressBase: Int,
        progressTotal: Int
    ) async throws {
        var completed = progressBase
        let at = now()

        // 1) 人格
        for serverPersona in snapshot.personas {
            completed += 1
            // 同名/同 id 已经认成本机某一个（见 `personaAlias`）：**不要再建第二行**，
            // 而是按「服务端优先」把服务端那几列写回本机那一行（本机独有的列照旧保留）。
            if let localId = personaAlias[serverPersona.id], localId != serverPersona.id {
                if let existing = (try? stores.personas.byId(localId)) ?? nil {
                    var mapped = ServerSyncMapping.persona(
                        from: serverPersona, existing: existing, now: at
                    )
                    mapped.id = localId
                    try stores.personas.upsert(mapped)
                }
            } else {
                let existing = (try? stores.personas.byId(serverPersona.id)) ?? nil
                let mapped = ServerSyncMapping.persona(
                    from: serverPersona, existing: existing, now: at
                )
                try stores.personas.upsert(mapped)
            }
            report.personasPulled += 1
            publishPull(completed: completed, total: progressTotal, detail: "人格", mode: report.mode)
        }

        // 2) 会话（含未读数：resolver 把服务端 `unread` 写进本地那一行）
        for serverSession in snapshot.sessions {
            completed += 1
            _ = try resolver.localSessionId(
                forServer: Self.withLocalPersona(serverSession, alias: personaAlias),
                at: at
            )
            report.sessionsPulled += 1
            publishPull(completed: completed, total: progressTotal, detail: "会话", mode: report.mode)
        }

        // 3) 快照里带的「每会话最新一块」（先落一次，保证打开会话立刻有内容）
        try writeMessages(
            snapshot.messages,
            snapshot: snapshot,
            at: at,
            report: &report
        )

        // 4) 每个会话倒序翻完所有块（升序写库）
        for serverSession in snapshot.sessions where serverSession.lastBlockIndex >= 0 {
            let localId = resolver.existingLocalSessionId(serverSessionId: serverSession.id)
                ?? serverSession.id
            if record?.pulledSessions.contains(localId) == true { continue }
            let messages = try await pullAllBlocks(serverSession: serverSession)
            pulledMessageIds[localId] = Set(messages.map(\.id))
            let written = try writeMessages(
                messages,
                snapshot: snapshot,
                at: at,
                report: &report
            )
            report.messagesPulled += written
            // 块状态：已经拉到最早一块了，界面上不该再显示「加载更早」。
            try? state.set("0", for: ServerSyncStateStore.Key.hasOlder(localId), at: at)
            if let lastBlock = serverSession.lastBlockIndex >= 0 ? serverSession.lastBlockIndex : nil {
                try? state.set(
                    String(lastBlock),
                    for: ServerSyncStateStore.Key.block(localId),
                    at: at
                )
            }
            record = (record ?? ServerBootstrapRecord(mode: report.mode.rawValue, startedAt: at))
            record?.pulledSessions.append(localId)
            saveRecord(record)
            completed += 1
            publishPull(
                completed: completed,
                total: progressTotal,
                detail: "消息 \(report.messagesPulled)",
                mode: report.mode
            )
        }

        // 5) 预设
        //
        // 本机预设推上去时服务端可能另发了一个 id（`pushPreset` 的 404 回落）。那一条
        // 拉回来时如果按服务端 id upsert，本机就会出现**同一个预设的两行**
        //（`local-preset` 与 `pre-1`）。所以先铺「服务端预设 id → 本机预设 id」再拉。
        if let presets = try? await client.presets() {
            var presetAlias: [String: String] = [:]
            for localPreset in (try? stores.presets.all()) ?? [] {
                if let mapped = state.string(ServerSyncStateStore.Key.presetIdMap(localPreset.id)),
                   !mapped.isEmpty, mapped != localPreset.id {
                    presetAlias[mapped] = localPreset.id
                }
            }
            for object in presets {
                guard let id = object["id"]?.stringValue, !id.isEmpty else { continue }
                let localId = presetAlias[id] ?? id
                let existing = (try? stores.presets.byId(localId)) ?? nil
                let fallback = PersonaPresetEntity(id: localId, name: object["name"]?.stringValue ?? "")
                if var preset = try? ServerWriteService.preset(from: object, existing: existing ?? fallback) {
                    // 服务端优先（内容以它为准），但本机 id 保持权威（与人格/会话同一口径）。
                    preset.id = localId
                    try stores.presets.upsert(preset)
                    report.presetsPulled += 1
                }
            }
            completed += 1
            publishPull(completed: completed, total: progressTotal, detail: "预设", mode: report.mode)
        }

        // 6) 记忆（按人格）
        for serverPersona in snapshot.personas {
            // 服务端的人格 id 可能与本机不同（见 `personaAlias`）：请求用它，落库用本机 id，
            // 否则记忆会挂在一个本机不存在的人格下面（记忆页按 personaId 找，等于丢了）。
            let localPersonaId = Self.localPersonaId(
                forServer: serverPersona.id, alias: personaAlias
            )
            let response = try? await client.memories(
                personaId: serverPersona.id, limit: Self.pullMemoryLimit
            )
            let objects = response?["memories"]?.arrayValue?.compactMap(\.objectValue) ?? []
            // 与预设同理：本机记忆推上去时服务端可能另发 id，拉回来要折算回本机那一条，
            // 否则同一条记忆会在记忆页出现两份（id 不同、内容一样）。
            var memoryAlias: [String: String] = [:]
            for localMemory in (try? stores.memories.byPersona(localPersonaId)) ?? [] {
                if let mapped = state.string(ServerSyncStateStore.Key.memoryIdMap(localMemory.id)),
                   !mapped.isEmpty, mapped != localMemory.id {
                    memoryAlias[mapped] = localMemory.id
                }
            }
            for object in objects {
                guard let id = object["id"]?.stringValue, !id.isEmpty else { continue }
                let localId = memoryAlias[id] ?? id
                let existing = (try? stores.memories.byId(localId)) ?? nil
                let fallback = MemoryEntity(id: localId, personaId: localPersonaId, content: "")
                guard var memory = try? ServerWriteService.memory(
                    from: object, existing: existing ?? fallback, personaId: localPersonaId
                ) else { continue }
                // 服务端对象里带着它自己的 `personaId`（可能是 p1），映射器会让它赢；
                // 人格 id 折算过时必须以本机 id 为准，否则记忆会挂在一个不存在的人格上。
                memory.id = localId
                memory.personaId = localPersonaId
                try stores.memories.upsert(memory)
                memories?.reindexTerms(memoryId: localId)
                memories?.vectorIndex.upsert(memoryId: localId, content: memory.content)
                report.memoriesPulled += 1
            }
            completed += 1
            publishPull(
                completed: completed,
                total: progressTotal,
                detail: "记忆 \(report.memoriesPulled)",
                mode: report.mode
            )
        }

        // 7) 设置（含用户头像）
        if let settings {
            if let values = try? await client.settings() {
                var merged = ServerSettingsMapping.applying(values, to: settings.load())
                // 服务端说的「正在聊的人」是它那边的人格 id：折算成本机人格 id 再落库，
                // 否则首页会指向一个本机不存在的 id（切过去就是空白）。
                if let active = merged.activePersonaId,
                   let local = personaAlias[active], local != active {
                    merged.activePersonaId = local
                }
                settings.save(merged)
                report.settingsPulled = true
                // 合并时本机头像可能还是 `file://`（服务端没有头像，所以 `applying` 没动它）。
                // 服务端优先：它已经有头像就不覆盖；没有才把本机这张传上去（推送阶段没传过，
                // 因为 MERGE 不推设置，推了会把服务端的设置盖掉）。
                if report.mode == .merge,
                   !ServerSettingsMapping.hasServerAvatar(values),
                   let localAvatar = merged.myAvatarUri,
                   !ServerMediaReference.isServerMediaURI(localAvatar),
                   let uploader = avatarUploader {
                    do {
                        let uploaded = try await uploader.upload(localURI: localAvatar)
                        var next = merged
                        next.myAvatarUri = uploaded.uri
                        settings.save(next)
                        _ = try await client.updateSettings(ServerSettingsMapping.body(next))
                        report.avatarUploaded = true
                        log("本机头像已上传：\(uploaded.uri)")
                    } catch {
                        // 头像只是设置里的一项：传不上去不该让整次首同步失败。
                        log("头像上传失败（继续）：\(ServerSyncService.describe(error))")
                    }
                }
            }
            completed += 1
            publishPull(completed: completed, total: progressTotal, detail: "设置", mode: report.mode)
        }
    }

    /// 从最新一块开始，倒序把所有块翻完（不写库，只在内存里攒）。
    private func pullAllBlocks(serverSession: ServerSession) async throws -> [ServerMessage] {
        var all: [ServerMessage] = []
        var seenBlocks = Set<Int>()
        var page = try await client.messages(sessionId: serverSession.id)
        var guardCount = 0
        while true {
            guardCount += 1
            if guardCount > Self.maxBlocksPerSession {
                log("会话 \(serverSession.id) 的块数超过 \(Self.maxBlocksPerSession)，先停在这里")
                break
            }
            if let block = page.blockIndex {
                if !seenBlocks.insert(block).inserted { break }
            }
            all.append(contentsOf: page.messages)
            // 终止条件只有一个：服务端说上面没有更早的块了（块号有空洞，不能用 == 0 判断）。
            guard page.hasMoreOlder, let block = page.blockIndex else { break }
            let next = try await client.messages(
                sessionId: serverSession.id, beforeBlock: block
            )
            if next.messages.isEmpty && next.blockIndex == nil { break }
            page = next
        }
        // 同一批里可能有重复 id（快照与分页重叠）：按 id 去重，保留后出现的（readAt 可能更新）。
        var indexById: [String: Int] = [:]
        var deduped: [ServerMessage] = []
        for message in all {
            if let index = indexById[message.id] {
                deduped[index] = message
            } else {
                indexById[message.id] = deduped.count
                deduped.append(message)
            }
        }
        return deduped
    }

    /// 把一批服务端消息写进本地缓存（按 `(createdAt, id)` 升序，块边界才自然）。
    ///
    /// - Returns: 写进去的条数。
    @discardableResult
    private func writeMessages(
        _ messages: [ServerMessage],
        snapshot: ServerStateSnapshot,
        at: Int64,
        report: inout ServerBootstrapReport
    ) throws -> Int {
        guard !messages.isEmpty else { return 0 }
        let personaBySession = Dictionary(
            snapshot.sessions.map { ($0.id, $0.personaId) },
            uniquingKeysWith: { first, _ in first }
        )
        let sorted = messages.sorted { lhs, rhs in
            if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
            return lhs.id < rhs.id
        }
        var written = 0
        try stores.database.inTransaction {
            var latestBySession: [String: ServerMessage] = [:]
            for serverMessage in sorted {
                guard let localId = resolver.existingLocalSessionId(serverSessionId: serverMessage.sessionId)
                        ?? (try? resolver.localSessionId(
                            forServer: ServerSession(id: serverMessage.sessionId, personaId: ""),
                            at: at
                        )) else { continue }
                // 消息的 personaId 也要折算（服务端人格 id 可能与本机不同，见 `personaAlias`）：
                // 会话表里有值就用会话的，会话缺人格时退回本机那一行。
                let mappedPersonaId = Self.localPersonaId(
                    forServer: personaBySession[serverMessage.sessionId] ?? "",
                    alias: personaAlias
                )
                let personaId = mappedPersonaId.isEmpty
                    ? (((try? stores.sessions.byId(localId)) ?? nil)?.personaId ?? "")
                    : mappedPersonaId
                let existing = (try? stores.messages.byId(serverMessage.id)) ?? nil
                var record = ServerSyncMapping.message(
                    from: serverMessage, existing: existing, personaId: personaId, now: at
                )
                record.sessionId = localId
                // 已读时间：快照/分页里的 readAt 是服务端权威，映射里已经带上；
                // 服务端没给（nil）时不要拿本地的旧值冒充「已读」。
                try stores.messages.upsert(record)
                written += 1
                if let latest = latestBySession[serverMessage.sessionId] {
                    if serverMessage.createdAt >= latest.createdAt { latestBySession[serverMessage.sessionId] = serverMessage }
                } else {
                    latestBySession[serverMessage.sessionId] = serverMessage
                }
            }
            // 会话预览：取每个会话的最新一条（与本地 ChatService 同一口径）。
            for (serverSessionId, message) in latestBySession {
                guard let localId = resolver.existingLocalSessionId(serverSessionId: serverSessionId),
                      var session = (try? stores.sessions.byId(localId)) ?? nil else { continue }
                if let preview = ServerSyncMapping.preview(from: message) {
                    session.lastMessagePreview = preview
                }
                session.lastMessageAt = max(session.lastMessageAt ?? 0, message.createdAt)
                try stores.sessions.upsert(session)
            }
        }
        report.at = at
        return written
    }

    // MARK: 内部：进度 / 记录

    private func publishPush(completed: Int, total: Int, detail: String, mode: ServerBootstrapMode) {
        publish(ServerBootstrapProgress(
            stage: .pushing, mode: mode, completed: completed, total: total,
            detail: detail, at: now()
        ))
    }

    private func publishPull(completed: Int, total: Int, detail: String, mode: ServerBootstrapMode) {
        publish(ServerBootstrapProgress(
            stage: .pulling, mode: mode, completed: completed, total: total,
            detail: detail, at: now()
        ))
    }

    private func publish(_ progress: ServerBootstrapProgress) {
        guard let callback = self.progress else { return }
        callback(progress)
    }

    private func baseURLKey() -> String {
        client.config.baseURL?.absoluteString ?? "unknown"
    }

    static func doneKey(_ baseURL: String) -> String { doneKeyPrefix + baseURL }
    static func progressKey(_ baseURL: String) -> String { progressKeyPrefix + baseURL }

    private func markDone(_ report: ServerBootstrapReport) {
        let done = ServerBootstrapDone(
            at: report.at, mode: report.mode.rawValue, messages: report.messagesPushed
        )
        if let data = try? JSONEncoder().encode(done),
           let text = String(data: data, encoding: .utf8) {
            try? state.set(text, for: Self.doneKey(baseURLKey()), at: report.at)
        }
        // 进度记录用完就删：留着它只会在下次换 URL 时变成误导。
        try? state.set(nil, for: Self.progressKey(baseURLKey()), at: report.at)
    }

    private func loadRecord() -> ServerBootstrapRecord? {
        guard let raw = state.string(Self.progressKey(baseURLKey())),
              let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(ServerBootstrapRecord.self, from: data)
    }

    private func saveRecord(_ record: ServerBootstrapRecord?) {
        guard var record else { return }
        let at = now()
        record.updatedAt = at
        guard let data = try? JSONEncoder().encode(record),
              let text = String(data: data, encoding: .utf8) else { return }
        try? state.set(text, for: Self.progressKey(baseURLKey()), at: at)
    }

    private func loadPersonaIdMap(localIds: [String]) -> [String: String] {
        var result: [String: String] = [:]
        for id in localIds {
            if let mapped = state.string(ServerSyncStateStore.Key.personaIdMap(id)) {
                result[id] = mapped
            }
        }
        return result
    }

    /// 把「本机人格 id → 服务端人格 id」反向铺进 `personaAlias`（只在两者不同时）。
    ///
    /// 幂等：同一份 state 反复读、反复铺，结果不变。断点续跑与「上一次已经推完」都靠它，
    /// 否则一次中断会让下一次运行把服务端那一份人格当成本机没见过的第二个人。
    private func primePersonaAlias(localPersonas: [PersonaEntity]) {
        for persona in localPersonas {
            guard let serverId = state.string(ServerSyncStateStore.Key.personaIdMap(persona.id)),
                  !serverId.isEmpty,
                  serverId != persona.id else { continue }
            personaAlias[serverId] = persona.id
        }
    }

    /// 断点续跑：实体阶段已完成时，把报告里的计数补齐（让「上次同步」的数字看着对）。
    private func catchUpEntityCounts(
        localPersonas: [PersonaEntity],
        report: inout ServerBootstrapReport
    ) {
        let mapped = localPersonas.filter {
            state.string(ServerSyncStateStore.Key.personaIdMap($0.id)) != nil
        }
        report.personasPushed = mapped.count
    }

    // MARK: 小工具

    /// 服务端人格 id → 本机人格 id（没有映射就原样返回；空串也原样返回，交给调用方兜底）。
    static func localPersonaId(forServer serverId: String, alias: [String: String]) -> String {
        guard !serverId.isEmpty, let local = alias[serverId], !local.isEmpty else { return serverId }
        return local
    }

    /// 把服务端会话的 `personaId` 折算成本机人格 id（其余字段一字不动）。
    ///
    /// 只给 `ServerSessionResolver` 用：它按 `personaId` 找「本机这个人格的那一段会话」，
    /// 不折算就会在人格 id 不同时新建一段，那段历史就挂到另一个人格上了。
    static func withLocalPersona(
        _ session: ServerSession,
        alias: [String: String]
    ) -> ServerSession {
        let mapped = localPersonaId(forServer: session.personaId, alias: alias)
        guard mapped != session.personaId else { return session }
        var copy = session
        copy.personaId = mapped
        return copy
    }

    /// 人格「是不是同一个人」：名字去空白后比较（本机模式播的种子人和服务端播的是同一批名字）。
    static func samePersonaName(_ lhs: String, _ rhs: String) -> Bool {
        let left = lhs.trimmingCharacters(in: .whitespacesAndNewlines)
        let right = rhs.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !left.isEmpty, !right.isEmpty else { return false }
        return left.caseInsensitiveCompare(right) == .orderedSame
    }

    /// 服务端拒绝「这个 id 不存在」类错误：404，或 400 且 code 是 not_found / invalid_argument。
    static func isUnknownIdRejection(_ error: ServerAPIError) -> Bool {
        if error.status == 404 { return true }
        if error.status == 400 {
            return error.code == "not_found" || error.code == "invalid_argument"
                || error.code == "missing_field"
        }
        return false
    }
}

// MARK: - 断点记录

/// 未完成时落进 `server_sync_state` 的进度（重试跳过已经做过的部分）。
struct ServerBootstrapRecord: Codable, Equatable {
    var mode: String
    var startedAt: Int64
    var updatedAt: Int64 = 0
    /// 实体（人格/预设/记忆/设置）这一阶段是否已完成。
    var entitiesPushed: Bool = false
    /// 全量拉取是否已完成。
    var pulledAll: Bool = false
    /// 已经推完消息的本地会话 id。
    var pushedSessions: [String] = []
    /// 已经拉完所有块的本地会话 id。
    var pulledSessions: [String] = []
}
