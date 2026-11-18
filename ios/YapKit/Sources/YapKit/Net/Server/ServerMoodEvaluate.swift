import Foundation

// ---------------------------------------------------------------------------
// 服务端情绪判定（0.13 第二阶段）：`POST /v1/personas/{id}/mood/evaluate`。
//
// 服务端模式下，聊天与人格的权威副本都在服务端，情绪也该由**服务端**判定：
// 它看得见完整对话、它自己调模型、结果写进 `moods.json` 并跟着 `/v1/state` 下发。
// 客户端这边只剩两件事：
// 1. **按节奏请求一次**（每 4 条用户消息 / 24 小时，与本地模式同一套游标）；
// 2. **把结果写进本地缓存**（人格的 `mood*` 列）：界面的心情一句话、离线时的提示词、
//    心跳的情绪放大都读本地这几列 —— 断网时不能变回「平静」。
//
// 为什么不等 `/v1/state` 下发：`/v1/state` 是**增量同步**的节拍（启动 / 回前台 / 5 分钟），
// 而情绪判定的节奏是「4 条消息」。让同步去驱动情绪，会出现「刚聊完十句，心情还是半小时前的」。
//
// 服务端对「没配模型」的处理是**回 200 + source=decay/unchanged**（`spec/mood/README.md` §2）：
// 情绪是后台动作，不该因为模型没配就报错。客户端因此只看 `mood` 字段有没有值。
// ---------------------------------------------------------------------------

/// `POST /v1/personas/{id}/mood/evaluate` 的响应。
///
/// `source` 说明这次是模型判定（`llm`）、失败后按时间衰减（`decay`），还是没明显变化
/// （`unchanged`）。**任何 source 下都可能带 `mood`**，所以这里不按 source 过滤。
public struct ServerMoodEvaluation: Decodable, Equatable, Sendable {
    public var personaId: String
    public var changed: Bool
    public var source: String
    public var mood: ServerMood?

    public init(
        personaId: String = "", changed: Bool = false,
        source: String = "", mood: ServerMood? = nil
    ) {
        self.personaId = personaId
        self.changed = changed
        self.source = source
        self.mood = mood
    }

    private enum CodingKeys: String, CodingKey { case personaId, changed, source, mood }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        personaId = try container.decodeIfPresent(String.self, forKey: .personaId) ?? ""
        changed = try container.decodeIfPresent(Bool.self, forKey: .changed) ?? false
        source = try container.decodeIfPresent(String.self, forKey: .source) ?? ""
        mood = try container.decodeIfPresent(ServerMood.self, forKey: .mood)
    }

    /// 这次判定有没有真的用到模型（`false` = 服务端没配置 / 上游失败后的回落）。
    public var usedModel: Bool { source.lowercased() == "llm" }
}

public extension ServerAPIClient {

    /// 请服务端判定一次情绪。
    ///
    /// - Parameter personaId: **服务端**人格 id。本机人格 id 与服务端 id 可能不同
    ///   （首次同步时服务端另发过一个 id），调用方要用映射后的那个。
    func evaluateMood(personaId: String) async throws -> ServerMoodEvaluation {
        let id = personaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "人格 id 不能为空")
        }
        // 服务端判定要真的调一次模型（可能好几秒），用资源超时而不是 10s 请求超时。
        return try await post(
            path: "/personas/\(Self.writePathSegment(id))/mood/evaluate",
            body: EmptyBody(),
            as: ServerMoodEvaluation.self,
            timeout: Self.resourceTimeout
        )
    }
}

/// 没有请求体、但 `post` 需要一个 Encodable：空的 JSON 对象 `{}`。
struct EmptyBody: Encodable {}

// MARK: - 编排

/// 云端模式的情绪判定编排：节流 → 请求服务端 → 写本地缓存。
///
/// 与本地模式（`MoodJudgementService`）**共用同一份游标**（`MoodJudgementCursorStoring`），
/// 所以切换模式不会出现「刚判过又判一次」或「两边各存一份节奏」。
public final class CloudMoodJudgement {

    /// 一次尝试的结果（日志与测试用）。
    public struct Outcome: Equatable, Sendable {
        /// 这次是不是真的发了请求。
        public var requested: Bool
        /// 有没有把心情写进本地缓存。
        public var written: Bool
        /// 一句人话，进日志。
        public var message: String

        public init(requested: Bool = false, written: Bool = false, message: String = "") {
            self.requested = requested
            self.written = written
            self.message = message
        }
    }

    private let client: ServerAPIClient
    private let mood: MoodRepository
    private let cursors: MoodJudgementCursorStoring
    private let messageCount: (String) -> Int
    private let serverPersonaId: (String) -> String
    private let now: () -> Int64
    private let log: (String) -> Void

    /// 单飞闸：同一个人格同时只请求一次（聊天触发 + 回前台补算可能撞车）。
    private let flightLock = NSLock()
    private var inFlight: Set<String> = []

    public init(
        client: ServerAPIClient,
        stores: DatabaseStores,
        mood: MoodRepository,
        cursors: MoodJudgementCursorStoring = UserDefaultsMoodJudgementCursorStore(),
        serverPersonaId: @escaping (String) -> String = { $0 },
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.client = client
        self.mood = mood
        self.cursors = cursors
        self.messageCount = { personaId in
            (try? stores.messages.count(
                where: "personaId = ? AND role = 'USER'", [.text(personaId)]
            )) ?? 0
        }
        self.serverPersonaId = serverPersonaId
        self.now = now
        self.log = log
    }

    /// 现在该不该判（与本地模式同一条规则：每 4 条用户消息 / 24 小时，第一次只记基线）。
    public func shouldEvaluate(personaId: String, at moment: Int64? = nil) -> Bool {
        let at = moment ?? now()
        let total = messageCount(personaId)
        guard total > 0 else { return false }
        guard let cursor = cursors.cursor(personaId: personaId), cursor.lastJudgedAt > 0 else {
            cursors.save(
                MoodJudgementCursor(lastJudgedAt: at, userMessageCount: total),
                personaId: personaId
            )
            return false
        }
        if total - cursor.userMessageCount >= MoodJudgementRules.triggerUserMessages { return true }
        return at - cursor.lastJudgedAt >= MoodJudgementRules.triggerIntervalMillis
    }

    public func beginEvaluation(personaId: String) -> Bool {
        flightLock.lock()
        defer { flightLock.unlock() }
        if inFlight.contains(personaId) { return false }
        inFlight.insert(personaId)
        return true
    }

    public func endEvaluation(personaId: String) {
        flightLock.lock()
        inFlight.remove(personaId)
        flightLock.unlock()
    }

    /// 到了节奏就请求一次；没到就安静返回 nil。
    ///
    /// **失败也推进游标**（与本地模式一致）：服务端这次没配模型，不代表下一条用户消息
    /// 要再白跑一次请求；等下一个节奏即可。
    @discardableResult
    public func evaluateIfNeeded(personaId: String) async -> Outcome? {
        guard shouldEvaluate(personaId: personaId) else { return nil }
        guard beginEvaluation(personaId: personaId) else { return nil }
        defer { endEvaluation(personaId: personaId) }
        return await evaluate(personaId: personaId)
    }

    /// 不看节奏，直接判一次（测试与「设置页手动刷新」用）。
    @discardableResult
    public func evaluate(personaId: String) async -> Outcome {
        let at = now()
        defer { advanceCursor(personaId: personaId, at: at) }
        let remoteId = serverPersonaId(personaId)
        do {
            let evaluation = try await client.evaluateMood(personaId: remoteId)
            guard let remoteMood = evaluation.mood, !remoteMood.label.isEmpty else {
                // 服务端没配置模型时就是这条路：不是错误，只是这次没有结果。
                return Outcome(requested: true, message: "服务端这次没有给出心情（\(evaluation.source)）")
            }
            let written = mood.applyServerMood(
                personaId: personaId,
                label: remoteMood.label,
                intensity: remoteMood.intensity,
                dimensions: remoteMood.dimensions,
                reason: remoteMood.reason,
                now: at
            )
            guard let written else {
                return Outcome(requested: true, message: "人格已经不在本机了，心情没写进去")
            }
            log("[mood] 云端判定：\(written.display())")
            return Outcome(requested: true, written: true, message: "心情变成「\(written.label)」")
        } catch {
            // 网络失败 / 服务端没开：**不**按时间衰减。
            // 衰减是本地模式的兜底（模型判不出来时让心情慢慢回到平静），而云端模式下
            // 拿不到结果只说明这台手机现在连不上服务端；把心情往下压会让用户看到
            // 「他一断网就变冷淡」，那是假的。
            log("[mood] 云端判定失败（这次跳过）：\(ServerSyncService.describe(error))")
            return Outcome(requested: true, message: "云端判定没成功，这次跳过")
        }
    }

    private func advanceCursor(personaId: String, at: Int64) {
        cursors.save(
            MoodJudgementCursor(
                lastJudgedAt: at,
                userMessageCount: messageCount(personaId)
            ),
            personaId: personaId
        )
    }
}
