import Foundation

// ---------------------------------------------------------------------------
// 记账：把「这一次用了多少 token」写进本机的 `api_usage`，并按月汇总。
//
// 来源：Android `data/repo/ApiUsageRepository.kt`。网络层只需要两个动作：
// 请求后报一句用了多少、界面要的时候问一句这个月用了多少，所以这里的协议刻意做得很窄。
//
// 记账是**旁路**：任何一步失败都只是这一笔没记上，绝不能把聊天或后台任务搞挂。
// 数据只在本机，不上传。
//
// 1.9 的变化（§五）：
// - 没有额度、没有超支拦截、没有自定义单价：这一层不再做任何「放不放行」的判断，
//   只记账。产品里也不再有「花了多少钱」这个概念，界面只显示 token；
// - `api_usage.sessionId` **必须写**：设置页要按「按人排行」统计 token。
//
// iOS 与 Android 的一处差别：Android 用 suspend + Room + Flow（能实时订阅本月流水），
// 这里 SQLite 是同步的，所以直接同步返回；界面要刷新时自己再查一次（字段少、成本可忽略）。
// ---------------------------------------------------------------------------

/// 某一类用途这个月用了多少 token。
public struct KindUsage: Equatable, Sendable {
    public var kind: String
    public var label: String
    public var calls: Int
    public var promptTokens: Int64
    public var completionTokens: Int64

    public init(
        kind: String,
        label: String,
        calls: Int,
        promptTokens: Int64,
        completionTokens: Int64
    ) {
        self.kind = kind
        self.label = label
        self.calls = calls
        self.promptTokens = promptTokens
        self.completionTokens = completionTokens
    }

    public var totalTokens: Int64 { promptTokens + completionTokens }
}

/// 某个会话（= 某个人）这个月用了多少 token。
///
/// 这是「按人排行」的口径：他陪你聊天花了多少、背着你整理记忆花了多少，
/// 用户想看的其实是「跟谁在一起的时候用得最多」。
public struct SessionUsage: Equatable, Sendable {
    public var sessionId: String
    public var personaId: String?
    public var calls: Int
    public var promptTokens: Int64
    public var completionTokens: Int64

    public init(sessionId: String, personaId: String?, calls: Int,
                promptTokens: Int64, completionTokens: Int64) {
        self.sessionId = sessionId
        self.personaId = personaId
        self.calls = calls
        self.promptTokens = promptTokens
        self.completionTokens = completionTokens
    }

    public var totalTokens: Int64 { promptTokens + completionTokens }
}

/// 这个月用了多少：不看某一笔，只看「一共多少 token、都花在什么事上、跟谁在一起」。
public struct UsageTotals: Equatable, Sendable {
    public var calls: Int
    public var promptTokens: Int64
    public var completionTokens: Int64
    public var byKind: [KindUsage]
    /// 按会话排好的前几名（默认前 10，见 `bySessionLimit`）。
    public var bySession: [SessionUsage]

    public static let bySessionLimit = 10

    public init(
        calls: Int = 0,
        promptTokens: Int64 = 0,
        completionTokens: Int64 = 0,
        byKind: [KindUsage] = [],
        bySession: [SessionUsage] = []
    ) {
        self.calls = calls
        self.promptTokens = promptTokens
        self.completionTokens = completionTokens
        self.byKind = byKind
        self.bySession = bySession
    }

    public var totalTokens: Int64 { promptTokens + completionTokens }
    public var isEmpty: Bool { calls == 0 }

    public static let empty = UsageTotals()

    /// 把一段时间内的流水汇总成一张「他这个月用了多少」。
    ///
    /// 展示顺序固定：聊天在最前，其余按「他在做什么」排；没用过的类别不出现。
    public static func from(_ rows: [ApiUsageEntity], sessionLimit: Int = UsageTotals.bySessionLimit)
        -> UsageTotals {
        if rows.isEmpty { return .empty }
        var prompt: Int64 = 0
        var completion: Int64 = 0
        // kind -> [calls, prompt, completion]
        var kindBuckets: [String: [Int64]] = [:]
        // sessionId -> personaId / [calls, prompt, completion]
        var sessionBuckets: [String: [Int64]] = [:]
        var sessionPersonas: [String: String] = [:]

        for row in rows {
            let kind = UsageKind.normalize(row.kind)
            let promptTokens = Int64(max(row.promptTokens, 0))
            let completionTokens = Int64(max(row.completionTokens, 0))
            prompt += promptTokens
            completion += completionTokens

            var bucket = kindBuckets[kind] ?? [0, 0, 0]
            bucket[0] += 1
            bucket[1] += promptTokens
            bucket[2] += completionTokens
            kindBuckets[kind] = bucket

            let sessionId = (row.sessionId ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            // 1.9 之前的老流水没有 sessionId：仍然计入总数与按用途统计，但不进「按人排行」
            // （不然会出现一条叫「不知道」的假联系人，比不显示更糟）。
            if sessionId.isEmpty { continue }
            var session = sessionBuckets[sessionId] ?? [0, 0, 0]
            session[0] += 1
            session[1] += promptTokens
            session[2] += completionTokens
            sessionBuckets[sessionId] = session
            if let personaId = row.personaId, !personaId.isEmpty {
                sessionPersonas[sessionId] = personaId
            }
        }

        let byKind: [KindUsage] = UsageKind.displayOrder.compactMap { kind in
            guard let bucket = kindBuckets[kind] else { return nil }
            return KindUsage(
                kind: kind,
                label: UsageKind.label(kind),
                calls: Int(bucket[0]),
                promptTokens: bucket[1],
                completionTokens: bucket[2]
            )
        }

        let bySession: [SessionUsage] = sessionBuckets
            .map { id, bucket in
                SessionUsage(
                    sessionId: id,
                    personaId: sessionPersonas[id],
                    calls: Int(bucket[0]),
                    promptTokens: bucket[1],
                    completionTokens: bucket[2]
                )
            }
            // 用量相同时按 id 排一下：同样的数据两次打开顺序一致，界面不会抖。
            .sorted { lhs, rhs in
                lhs.totalTokens == rhs.totalTokens ? lhs.sessionId < rhs.sessionId
                    : lhs.totalTokens > rhs.totalTokens
            }
            .prefix(max(sessionLimit, 0))
            .map { $0 }

        return UsageTotals(
            calls: rows.count,
            promptTokens: prompt,
            completionTokens: completion,
            byKind: byKind,
            bySession: bySession
        )
    }
}

/// 网络层眼里的「记账」。
///
/// 做成协议是为了两件事：adapter 不依赖 SQLite；测试可以塞一个只记内存的假记账器。
/// 换实现（比如以后改成先在本地攒、批量落库）也不用动 adapter。
public protocol UsageRecording: AnyObject {
    /// 请求后报一笔：用了多少 token（认不出 usage 时传 `.zero`，这笔仍然算一次调用）。
    /// 返回 false 只表示「这一笔没记上」，调用方不需要做任何事。
    @discardableResult
    func record(
        kind: String,
        modelName: String?,
        usage: TokenUsage,
        at: Int64,
        personaId: String?,
        sessionId: String?
    ) -> Bool
}

/// 一次调用「算在谁头上」：设置页的按人排行全靠它。
///
/// 为什么单独一个结构而不是四个散参数：调用点很多（聊天、抽取、整理、思考），
/// 少传 sessionId 这种事在四个可选参数下太容易发生，而这个字段正是 1.9 的硬要求。
public struct UsageContext: Equatable, Sendable {
    public var personaId: String?
    public var sessionId: String?

    public init(personaId: String? = nil, sessionId: String? = nil) {
        self.personaId = personaId
        self.sessionId = sessionId
    }

    public static let none = UsageContext()
}

/// 真正的记账器：写 `api_usage`，并按月聚合。
public final class UsageRecorder: UsageRecording {

    /// 只留最近多少天：更早的明细对「这个月用了多少」没用，留着只占地方。
    public static let retainDays = 90

    private static let millisPerDay: Int64 = 24 * 60 * 60 * 1000

    private let usage: ApiUsageStore

    public init(stores: DatabaseStores) {
        self.usage = stores.apiUsage
    }

    /// 记一笔。失败时安静吞掉（返回 false 只给调用方参考）。
    ///
    /// 与 Android 一样只吞「写不进去」，不吞取消：Swift 里取消是 `CancellationError`，
    /// 这里直接把它原样抛给上层的做法不可行（协议是同步的），所以调用方在流里自己检查
    /// `Task.isCancelled`；记账本身在毫秒级完成，不值得为它中断。
    @discardableResult
    public func record(
        kind: String,
        modelName: String?,
        usage usageValue: TokenUsage,
        at: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        personaId: String? = nil,
        sessionId: String? = nil
    ) -> Bool {
        let trimmedName = modelName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = (trimmedName?.isEmpty == false) ? String(trimmedName!.prefix(120)) : nil
        let trimmedSession = sessionId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedPersona = personaId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let entity = ApiUsageEntity(
            id: UUID().uuidString,
            at: at,
            kind: UsageKind.normalize(kind),
            personaId: (trimmedPersona?.isEmpty == false) ? trimmedPersona : nil,
            sessionId: (trimmedSession?.isEmpty == false) ? trimmedSession : nil,
            modelName: model,
            promptTokens: max(usageValue.promptTokens, 0),
            completionTokens: max(usageValue.completionTokens, 0),
            // 成本只是「将来真要算账时不用重跑」的一列估值，界面不显示它。
            costMicroUsd: ApiCost.estimateCostMicroUsd(modelName: modelName, usage: usageValue)
        )
        do {
            try usage.upsert(entity)
            return true
        } catch {
            return false
        }
    }

    /// 本月总 token 数。
    public func monthTokens(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Int64 {
        (try? usage.sumTokens(since: ApiCost.monthStart(now))) ?? 0
    }

    /// 本月汇总，一次性取（按用途 + 按会话）。
    public func monthTotals(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> UsageTotals {
        guard let rows = try? usage.since(ApiCost.monthStart(now)) else { return .empty }
        return UsageTotals.from(rows)
    }

    /// 只留最近 [days] 天。清理失败不值得打扰任何人，下次启动再来。
    public func pruneOlderThan(days: Int = UsageRecorder.retainDays,
                               now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        guard days > 0 else { return }
        let before = now - Int64(days) * Self.millisPerDay
        try? usage.delete(where: "at < ?", [.int(before)])
    }

    /// 用户选择删掉全部本地数据时，一起把流水清掉。
    public func wipe() {
        try? usage.deleteAll()
    }
}
