import Foundation

// ---------------------------------------------------------------------------
// 待确认的人格变化（`docs/design/persona-evolution.md` §2）。
//
// 形状与 Android 的 `PersonaPendingPatchEntity` 逐字一致（表 `persona_pending_patches`）：
// `patchJson` 是**已经过校验的 changes 数组**，`summary` 是给人看的一行话，
// `reason` 是给用户判断「要不要接受」的理由，`sessionId` 记录它是在哪段对话里冒出来的。
//
// 为什么先落 pending 而不是直接改人格：
// - 进化是模型对自己的判断，可能错（一次争吵不该让「温柔」消失）；
// - 用户必须能拒绝，而且拒绝要留痕（这条记录就是痕）；
// - 7 天没人管就自动丢掉（`PersonaEvolution.pendingTtlMillis`），否则「他最近的变化」
//   会变成一堆三个月前的死按钮。
// ---------------------------------------------------------------------------

/// 一条待确认的人格变化。
public struct PersonaPendingPatchEntity: Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var sessionId: String?
    /// `[{"field":"traits","op":"add","value":"…"}]`（`PersonaEvolution.Change` 编码后）。
    public var patchJson: String
    /// 一行「他最近的变化」：如「多了个习惯：夜里散步」。
    public var summary: String
    /// 为什么这么变（一句话，来自评估模型的 reason）。
    public var reason: String
    public var createdAt: Int64

    public init(
        id: String,
        personaId: String,
        sessionId: String? = nil,
        patchJson: String,
        summary: String,
        reason: String,
        createdAt: Int64
    ) {
        self.id = id
        self.personaId = personaId
        self.sessionId = sessionId
        self.patchJson = patchJson
        self.summary = String(summary.prefix(120))
        self.reason = String(reason.prefix(300))
        self.createdAt = createdAt
    }

    /// 超过 7 天：自动丢弃（`docs/design/persona-evolution.md` §2）。
    public func isExpired(now: Int64) -> Bool {
        now - createdAt > PersonaEvolution.pendingTtlMillis
    }
}

extension PersonaPendingPatchEntity: SQLiteRecord {
    public static let tableName = "persona_pending_patches"

    public static let columns = [
        "id", "personaId", "sessionId", "patchJson", "summary", "reason", "createdAt"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id),
            .text(personaId),
            .optionalText(sessionId),
            .text(patchJson),
            .text(summary),
            .text(reason),
            .int(createdAt)
        ]
    }

    public init(row: SQLiteRow) throws {
        id = try row.text("id")
        personaId = try row.text("personaId")
        sessionId = try row.optionalText("sessionId")
        patchJson = try row.text("patchJson")
        summary = try row.text("summary")
        reason = try row.text("reason")
        createdAt = try row.int64("createdAt")
    }
}

/// 待确认变化的读写。
public final class PersonaPendingPatchStore: SQLiteStore<PersonaPendingPatchEntity> {

    /// 这个人格的全部待确认变化（新的在前）。
    public func byPersona(_ personaId: String) throws -> [PersonaPendingPatchEntity] {
        try fetch(where: "personaId = ?", [.text(personaId)], orderBy: "createdAt DESC")
    }

    public func count(personaId: String) throws -> Int {
        try count(where: "personaId = ?", [.text(personaId)])
    }

    /// 写一条之前先去重：同一个人格、同一份 patch 已经在等着了就不再堆一条。
    ///
    /// 为什么按 `patchJson` 去重而不是按 summary：summary 是渲染出来的中文，
    /// 同一个变化换个说法就会绕过检查；patchJson 是规范化的（字段顺序固定、values 去重过），
    /// 同一份变化一定得到同一串。
    @discardableResult
    public func insertIfNew(_ patch: PersonaPendingPatchEntity) throws -> Bool {
        let existing = try byPersona(patch.personaId)
        if existing.contains(where: { $0.patchJson == patch.patchJson }) { return false }
        try upsert(patch)
        return true
    }

    public func remove(id: String) throws {
        try delete(where: "id = ?", [.text(id)])
    }

    public func removeAll(personaId: String) throws {
        try delete(where: "personaId = ?", [.text(personaId)])
    }

    /// 7 天过期的清理（每次读取/评估时顺手做一次，不依赖定时任务）。
    @discardableResult
    public func removeExpired(now: Int64) throws -> Int {
        let cutoff = now - PersonaEvolution.pendingTtlMillis
        let stale = try fetch(where: "createdAt < ?", [.int(cutoff)])
        guard !stale.isEmpty else { return 0 }
        for patch in stale {
            try remove(id: patch.id)
        }
        return stale.count
    }
}
