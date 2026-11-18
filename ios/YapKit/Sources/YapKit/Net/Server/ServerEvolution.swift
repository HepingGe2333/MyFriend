import Foundation

// ---------------------------------------------------------------------------
// 服务端的人格进化（0.13 第二阶段）：evaluate / pending / accept / reject。
//
// 契约（`agentd` 的 `EvolutionRoutes`）：
//
//   POST /v1/personas/{id}/evolution/evaluate        → {personaId, proposed, alreadyPending, summary, patch}
//   GET  /v1/personas/{id}/evolution/pending         → {personaId, serverTime, patches[]}
//   POST /v1/personas/{id}/evolution/{patchId}/accept → {personaId, patchId, changed, version, applied[], persona}
//   POST /v1/personas/{id}/evolution/{patchId}/reject → {personaId, patchId, rejected}
//
// 错误语义（客户端按 `code` 区分，文案直接用服务端那句中文）：
// 404 `not_found` / `patch_not_found`、410 `patch_expired`、
// 503 `llm_not_configured` / `llm_invalid_config`、502 `llm_*`。
//
// 为什么「接受」只在服务端做：人格的权威副本在服务端，四字段合并 + `version + 1` 也在那里。
// 客户端如果先写本地再推，一旦请求失败这台手机就会以为改过了，而另一端与服务端都还不知道
// （下一次同步会把它莫名其妙地回滚）。所以顺序固定为：服务端成功 → 再用回执写本地缓存。
//
// 待审补丁在客户端是**缓存**：`/pending` 的返回覆盖本地那几行（服务端是权威，它也会
// 顺手清掉 7 天过期的）。这样换设备、重装以后「他最近的变化」还在。
// ---------------------------------------------------------------------------

/// 一条待审变化的明细（服务端解析后的视图）。
public struct ServerEvolutionChange: Decodable, Equatable, Sendable {
    public var field: String
    public var op: String
    public var value: String
    public var reason: String

    public init(field: String = "", op: String = "", value: String = "", reason: String = "") {
        self.field = field
        self.op = op
        self.value = value
        self.reason = reason
    }

    private enum CodingKeys: String, CodingKey { case field, op, value, reason }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        field = try container.decodeIfPresent(String.self, forKey: .field) ?? ""
        op = try container.decodeIfPresent(String.self, forKey: .op) ?? ""
        value = try container.decodeIfPresent(String.self, forKey: .value) ?? ""
        reason = try container.decodeIfPresent(String.self, forKey: .reason) ?? ""
    }
}

/// 服务端的一条待审补丁。
public struct ServerEvolutionPatch: Decodable, Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var sessionId: String?
    /// 规范化后的 changes JSON（服务端保留原始文本，客户端整份存下来回放）。
    public var patchJson: String
    public var summary: String
    public var reason: String
    public var createdAt: Int64
    public var expiresAt: Int64?
    public var changes: [ServerEvolutionChange]

    public init(
        id: String,
        personaId: String = "",
        sessionId: String? = nil,
        patchJson: String = "",
        summary: String = "",
        reason: String = "",
        createdAt: Int64 = 0,
        expiresAt: Int64? = nil,
        changes: [ServerEvolutionChange] = []
    ) {
        self.id = id
        self.personaId = personaId
        self.sessionId = sessionId
        self.patchJson = patchJson
        self.summary = summary
        self.reason = reason
        self.createdAt = createdAt
        self.expiresAt = expiresAt
        self.changes = changes
    }

    private enum CodingKeys: String, CodingKey {
        case id, personaId, sessionId, patchJson, summary, reason, createdAt, expiresAt, changes
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        personaId = try container.decodeIfPresent(String.self, forKey: .personaId) ?? ""
        sessionId = try container.decodeIfPresent(String.self, forKey: .sessionId)
        patchJson = try container.decodeIfPresent(String.self, forKey: .patchJson) ?? ""
        summary = try container.decodeIfPresent(String.self, forKey: .summary) ?? ""
        reason = try container.decodeIfPresent(String.self, forKey: .reason) ?? ""
        createdAt = try container.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        expiresAt = try container.decodeIfPresent(Int64.self, forKey: .expiresAt)
        changes = try container.decodeIfPresent([ServerEvolutionChange].self, forKey: .changes) ?? []
    }

    /// `patchJson` 缺失时退回用 `changes` 拼一份（服务端两个都给，但老版本可能只给一个）。
    public var resolvedPatchJson: String {
        let trimmed = patchJson.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        let items = changes.map { change in
            PersonaEvolution.Change(
                field: PersonaEvolution.Field(rawValue: change.field) ?? .traits,
                operation: PersonaEvolution.Operation(rawValue: change.op) ?? .add,
                value: change.value,
                reason: change.reason
            )
        }
        return PersonaEvolution.encodePatch(items)
    }
}

/// `POST …/evolution/evaluate` 的响应。
public struct ServerEvolutionEvaluation: Decodable, Equatable, Sendable {
    public var personaId: String
    /// 模型给出来、通过校验的变化条数（0 = 没有值得改的；**不是**错误）。
    public var proposed: Int
    /// 服务端说「这个补丁已经在等着了」（同一段对话重复评估时）。
    public var alreadyPending: Bool
    public var summary: String
    public var patch: ServerEvolutionPatch?

    public init(
        personaId: String = "", proposed: Int = 0, alreadyPending: Bool = false,
        summary: String = "", patch: ServerEvolutionPatch? = nil
    ) {
        self.personaId = personaId
        self.proposed = proposed
        self.alreadyPending = alreadyPending
        self.summary = summary
        self.patch = patch
    }

    private enum CodingKeys: String, CodingKey { case personaId, proposed, alreadyPending, summary, patch }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        personaId = try container.decodeIfPresent(String.self, forKey: .personaId) ?? ""
        proposed = try container.decodeIfPresent(Int.self, forKey: .proposed) ?? 0
        alreadyPending = try container.decodeIfPresent(Bool.self, forKey: .alreadyPending) ?? false
        summary = try container.decodeIfPresent(String.self, forKey: .summary) ?? ""
        patch = try container.decodeIfPresent(ServerEvolutionPatch.self, forKey: .patch)
    }
}

/// `GET …/evolution/pending` 的响应。
public struct ServerEvolutionPendingPage: Decodable, Equatable, Sendable {
    public var personaId: String
    public var serverTime: Int64
    public var patches: [ServerEvolutionPatch]

    public init(personaId: String = "", serverTime: Int64 = 0, patches: [ServerEvolutionPatch] = []) {
        self.personaId = personaId
        self.serverTime = serverTime
        self.patches = patches
    }

    private enum CodingKeys: String, CodingKey { case personaId, serverTime, patches }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        personaId = try container.decodeIfPresent(String.self, forKey: .personaId) ?? ""
        serverTime = try container.decodeIfPresent(Int64.self, forKey: .serverTime) ?? 0
        patches = try container.decodeIfPresent([ServerEvolutionPatch].self, forKey: .patches) ?? []
    }
}

/// `POST …/{patchId}/accept` 的响应。
public struct ServerEvolutionAcceptance: Decodable, Equatable, Sendable {
    public var personaId: String
    public var patchId: String
    /// false = 补丁合法但去重后没有真正变化（服务端**没有**升版本）。
    public var changed: Bool
    public var version: Int
    public var applied: [ServerEvolutionChange]
    /// 服务端变更后的人格（字段与 `ServerPersona` + extras 一致）。
    public var persona: [String: JSONValue]?

    public init(
        personaId: String = "", patchId: String = "", changed: Bool = false,
        version: Int = 0, applied: [ServerEvolutionChange] = [],
        persona: [String: JSONValue]? = nil
    ) {
        self.personaId = personaId
        self.patchId = patchId
        self.changed = changed
        self.version = version
        self.applied = applied
        self.persona = persona
    }

    private enum CodingKeys: String, CodingKey { case personaId, patchId, changed, version, applied, persona }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        personaId = try container.decodeIfPresent(String.self, forKey: .personaId) ?? ""
        patchId = try container.decodeIfPresent(String.self, forKey: .patchId) ?? ""
        changed = try container.decodeIfPresent(Bool.self, forKey: .changed) ?? false
        version = try container.decodeIfPresent(Int.self, forKey: .version) ?? 0
        applied = try container.decodeIfPresent([ServerEvolutionChange].self, forKey: .applied) ?? []
        persona = try? container.decodeIfPresent([String: JSONValue].self, forKey: .persona)
    }
}

/// `POST …/{patchId}/reject` 的响应。
public struct ServerEvolutionRejection: Decodable, Equatable, Sendable {
    public var personaId: String
    public var patchId: String
    public var rejected: Bool

    public init(personaId: String = "", patchId: String = "", rejected: Bool = true) {
        self.personaId = personaId
        self.patchId = patchId
        self.rejected = rejected
    }

    private enum CodingKeys: String, CodingKey { case personaId, patchId, rejected }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        personaId = try container.decodeIfPresent(String.self, forKey: .personaId) ?? ""
        patchId = try container.decodeIfPresent(String.self, forKey: .patchId) ?? ""
        rejected = try container.decodeIfPresent(Bool.self, forKey: .rejected) ?? true
    }
}

public extension ServerAPIClient {

    /// 请服务端评估一次人格进化（这是一次**显式的用户动作**：没有可用模型时服务端会回 503）。
    func evaluateEvolution(personaId: String) async throws -> ServerEvolutionEvaluation {
        try await post(
            path: evolutionPath(personaId, "evaluate"),
            body: EmptyBody(),
            as: ServerEvolutionEvaluation.self,
            timeout: Self.resourceTimeout
        )
    }

    /// 拉服务端的待审补丁（服务端顺手清掉 7 天过期的）。
    func pendingEvolutions(personaId: String) async throws -> ServerEvolutionPendingPage {
        try await get(
            path: evolutionPath(personaId, "pending"),
            query: [],
            as: ServerEvolutionPendingPage.self,
            timeout: Self.resourceTimeout
        )
    }

    /// 接受一条待审补丁（服务端合并四字段 + `version + 1`）。
    func acceptEvolution(personaId: String, patchId: String) async throws -> ServerEvolutionAcceptance {
        try await post(
            path: evolutionPath(personaId, "\(Self.writePathSegment(patchId))/accept"),
            body: EmptyBody(),
            as: ServerEvolutionAcceptance.self,
            timeout: Self.resourceTimeout
        )
    }

    /// 拒绝一条待审补丁（人格不动）。
    func rejectEvolution(personaId: String, patchId: String) async throws -> ServerEvolutionRejection {
        try await post(
            path: evolutionPath(personaId, "\(Self.writePathSegment(patchId))/reject"),
            body: EmptyBody(),
            as: ServerEvolutionRejection.self,
            timeout: Self.resourceTimeout
        )
    }

    private func evolutionPath(_ personaId: String, _ suffix: String) throws -> String {
        let id = personaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "人格 id 不能为空")
        }
        return "/personas/\(Self.writePathSegment(id))/evolution/\(suffix)"
    }
}

// MARK: - 服务端错误分类

/// 进化端点失败时「该给用户看什么」。
///
/// 三类要分开：立刻能理解的（过期/没有了）、要用户去配置的（模型没配）、暂时性的（网络/上游）。
public enum ServerEvolutionFailure: Equatable, Sendable {
    /// 补丁已经过期或被别处处理掉了：本地那条也该删掉。
    case patchGone(String)
    /// 服务端没有可用的模型配置：这是**显式动作**，必须告诉用户去配。
    case llmNotConfigured(String)
    /// 上游模型失败 / 网络不通：待审项保留，用户还能再点一次。
    case temporary(String)

    public var message: String {
        switch self {
        case .patchGone(let message), .llmNotConfigured(let message), .temporary(let message):
            return message
        }
    }

    /// 这条失败要不要把本地待审项删掉（只有「它已经不存在了」才删）。
    public var shouldDropPending: Bool {
        if case .patchGone = self { return true }
        return false
    }

    public static func classify(_ error: Error) -> ServerEvolutionFailure {
        if let error = error as? ServerAPIError {
            let code = error.code.lowercased()
            let message = error.message
            if code == "patch_expired" || code == "patch_not_found" {
                return .patchGone(message)
            }
            if code == "llm_not_configured" || code == "llm_invalid_config" {
                return .llmNotConfigured(message)
            }
            return .temporary(message)
        }
        return .temporary(error.localizedDescription)
    }
}
