import Foundation

// 来源：`spec/experience/README.md` §6「记忆」的 `session_memory` 部分。
//
// 一层存、一层算：
// - `SessionMemoryRepository`：读一份会话备忘录、分别更新短期/长期摘要、拼成提示词那一段；
// - `SessionMemoryService`：节流（每 5 / 30 条用户消息）→ 生成 → 落库；失败保留旧摘要。
//
// 为什么短期与长期分开写、分开记 `upTo`：两份摘要的节奏差 6 倍，
// 用同一行做「整行覆盖」会让短期摘要的更新顺带把长期摘要冲回旧值（或者反过来）。

// MARK: - 仓储

public final class SessionMemoryRepository {

    private let stores: DatabaseStores
    private let now: () -> Int64

    public init(stores: DatabaseStores, now: @escaping () -> Int64 = { DomainClock.nowMillis }) {
        self.stores = stores
        self.now = now
    }

    public func memory(sessionId: String) -> SessionMemoryEntity? {
        (try? stores.sessionMemory.bySession(sessionId)) ?? nil
    }

    /// 写短期摘要（≤200 字）。**只动短期三列**，长期摘要原样保留。
    @discardableResult
    public func applyShort(
        sessionId: String,
        personaId: String,
        summary: String,
        userCount: Int,
        at: Int64? = nil
    ) -> SessionMemoryEntity? {
        guard let clean = SessionMemoryPolicy.clean(summary, limit: SessionMemoryPolicy.shortLimit) else {
            return nil
        }
        let timestamp = at ?? now()
        var entity = memory(sessionId: sessionId)
            ?? SessionMemoryEntity(sessionId: sessionId, personaId: personaId, updatedAt: timestamp)
        entity.shortSummary = clean
        entity.shortUpdatedAt = timestamp
        entity.shortUserCount = max(userCount, 0)
        entity.updatedAt = timestamp
        guard (try? stores.sessionMemory.upsert(entity)) != nil else { return nil }
        return entity
    }

    /// 写长期摘要（≤500 字）。**只动长期三列**，短期摘要原样保留。
    @discardableResult
    public func applyLong(
        sessionId: String,
        personaId: String,
        summary: String,
        userCount: Int,
        at: Int64? = nil
    ) -> SessionMemoryEntity? {
        guard let clean = SessionMemoryPolicy.clean(summary, limit: SessionMemoryPolicy.longLimit) else {
            return nil
        }
        let timestamp = at ?? now()
        var entity = memory(sessionId: sessionId)
            ?? SessionMemoryEntity(sessionId: sessionId, personaId: personaId, updatedAt: timestamp)
        entity.longSummary = clean
        entity.longUpdatedAt = timestamp
        entity.longUserCount = max(userCount, 0)
        entity.updatedAt = timestamp
        guard (try? stores.sessionMemory.upsert(entity)) != nil else { return nil }
        return entity
    }

    /// 「你的备忘录」那一段（短期在前、长期在后）；两份都空返回空串。
    public func promptBlock(sessionId: String) -> String {
        guard let entity = memory(sessionId: sessionId) else { return "" }
        return SessionMemoryPolicy.memoBlock(short: entity.shortSummary, long: entity.longSummary)
    }

    /// 清掉一段会话的备忘录（清空上下文 / 删会话时跟着走）。
    public func delete(sessionId: String) {
        _ = try? stores.sessionMemory.delete(id: sessionId)
    }
}

// MARK: - 生成

/// 一份摘要的生成器。单测注入假实现，验证节流 / 上限 / 失败保留旧摘要。
public protocol SessionSummarizing: AnyObject {
    /// 最近 5 轮的短期摘要。
    func summarizeShort(
        persona: PersonaEntity,
        session: ChatSessionEntity,
        recent: [MessageEntity],
        previous: String?,
        now: Int64
    ) async -> String?

    /// 更早对话的长期摘要。
    func summarizeLong(
        persona: PersonaEntity,
        session: ChatSessionEntity,
        earlier: [MessageEntity],
        previous: String?,
        now: Int64
    ) async -> String?
}

// MARK: - 服务

public final class SessionMemoryService {

    private let stores: DatabaseStores
    private let repository: SessionMemoryRepository
    private let summarizer: SessionSummarizing?
    private let now: () -> Int64
    private let log: (String) -> Void
    private let lock = NSLock()
    private var running: Set<String> = []

    public init(
        stores: DatabaseStores,
        repository: SessionMemoryRepository? = nil,
        summarizer: SessionSummarizing?,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.repository = repository ?? SessionMemoryRepository(stores: stores, now: now)
        self.summarizer = summarizer
        self.now = now
        self.log = log
    }

    /// 该更新就更新（短期 / 长期各自判各自的节流）。
    ///
    /// 调用点在「回复已经落库之后」，所以这里**绝不影响回复**：
    /// 任何一步失败都只留日志，旧摘要原样留着（下一次回复会重试）。
    public func updateIfNeeded(sessionId: String, personaId: String, now: Int64? = nil) async {
        let at = now ?? self.now()
        guard let summarizer else { return }
        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else { return }
        guard let session = (try? stores.sessions.byId(sessionId)) ?? nil else { return }
        let userCount = (try? stores.messages.countUserMessages(
            sessionId: sessionId, includeBlocked: false
        )) ?? 0
        let existing = repository.memory(sessionId: sessionId)
        let needsShort = SessionMemoryPolicy.shouldUpdate(
            currentUserCount: userCount,
            upTo: existing?.shortUserCount ?? 0,
            interval: SessionMemoryPolicy.shortInterval
        )
        let needsLong = SessionMemoryPolicy.shouldUpdate(
            currentUserCount: userCount,
            upTo: existing?.longUserCount ?? 0,
            interval: SessionMemoryPolicy.longInterval
        )
        guard needsShort || needsLong else { return }
        guard begin(sessionId) else { return }
        defer { end(sessionId) }

        let window = (try? stores.messages.recentVisible(
            sessionId: sessionId, limit: SessionMemoryPolicy.longWindowMessages
        )) ?? []
        if needsShort {
            let recent = Array(window.suffix(SessionMemoryPolicy.shortRounds * 2))
            if let summary = await summarizer.summarizeShort(
                persona: persona,
                session: session,
                recent: recent,
                previous: existing?.shortSummary,
                now: at
            ) {
                let saved = repository.applyShort(
                    sessionId: sessionId, personaId: personaId,
                    summary: summary, userCount: userCount, at: at
                )
                log(saved == nil
                    ? "[memo] 短期摘要没写进去（保留旧的）"
                    : "[memo] 短期摘要已更新（\(userCount) 条用户消息）")
            } else {
                log("[memo] 短期摘要生成失败（保留旧的）")
            }
        }
        if needsLong {
            // 长期摘要看「更早的对话」：窗口里最后 10 条留给短期，其余是长期素材。
            let earlier = Array(window.dropLast(SessionMemoryPolicy.shortRounds * 2))
            if let summary = await summarizer.summarizeLong(
                persona: persona,
                session: session,
                earlier: earlier,
                previous: existing?.longSummary,
                now: at
            ) {
                let saved = repository.applyLong(
                    sessionId: sessionId, personaId: personaId,
                    summary: summary, userCount: userCount, at: at
                )
                log(saved == nil
                    ? "[memo] 长期摘要没写进去（保留旧的）"
                    : "[memo] 长期摘要已更新（\(userCount) 条用户消息）")
            } else {
                log("[memo] 长期摘要生成失败（保留旧的）")
            }
        }
    }

    private func begin(_ sessionId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if running.contains(sessionId) { return false }
        running.insert(sessionId)
        return true
    }

    private func end(_ sessionId: String) {
        lock.lock()
        running.remove(sessionId)
        lock.unlock()
    }
}

// MARK: - 本地生成器

/// 用当前 API 配置写两份摘要。
///
/// 两份都用同一条约束：**只写对话里发生过的事**，不臆造、不评论、不写成日记。
/// 短期覆盖最近几轮（谁说了什么、情绪如何），长期覆盖更早的事（约定、经历、变化）。
public final class LocalSessionSummarizer: SessionSummarizing {

    public static let temperature = 0.2
    public static let maxOutputTokens = 400

    private let completion: CompletionClient
    private let resolveConfig: (ChatSessionEntity?) -> CompressionConfig?
    private let log: (String) -> Void

    public init(
        completion: CompletionClient,
        resolveConfig: @escaping (ChatSessionEntity?) -> CompressionConfig?,
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.completion = completion
        self.resolveConfig = resolveConfig
        self.log = log
    }

    public func summarizeShort(
        persona: PersonaEntity,
        session: ChatSessionEntity,
        recent: [MessageEntity],
        previous: String?,
        now: Int64
    ) async -> String? {
        guard !recent.isEmpty else { return nil }
        let system = """
        你在帮「\(persona.name)」整理自己的备忘录（只给他自己看，不会发给对方）。
        把最近几轮对话压成一段不超过 200 字的短期摘要：谁说了什么、聊到哪、情绪怎么样。
        只写对话里真的出现过的事，不评论、不联想、不写成日记。直接输出摘要正文，不要标题。
        """
        var user = "最近的对话：\n" + dialogueText(recent)
        if let previous, !previous.isEmpty {
            user += "\n\n上一版短期摘要（可以在此基础上更新）：\n\(previous)"
        }
        return await complete(system: system, user: user, model: session, persona: persona)
    }

    public func summarizeLong(
        persona: PersonaEntity,
        session: ChatSessionEntity,
        earlier: [MessageEntity],
        previous: String?,
        now: Int64
    ) async -> String? {
        guard !earlier.isEmpty || !(previous ?? "").isEmpty else { return nil }
        let system = """
        你在帮「\(persona.name)」整理自己的备忘录（只给他自己看，不会发给对方）。
        把更早的对话压成一段不超过 500 字的长期摘要：重要的经历、约定、对方的变化。
        只写对话里真的出现过的事，不臆造、不评论。有旧摘要时在它基础上合并更新。直接输出摘要正文。
        """
        var user = ""
        if !earlier.isEmpty { user += "更早的对话：\n" + dialogueText(earlier) }
        if let previous, !previous.isEmpty {
            user += (user.isEmpty ? "" : "\n\n") + "上一版长期摘要（合并更新）：\n\(previous)"
        }
        return await complete(system: system, user: user, model: session, persona: persona)
    }

    private func dialogueText(_ messages: [MessageEntity]) -> String {
        messages.map { message in
            let who = message.role == .user ? "对方" : "你"
            let text = message.content.replacingOccurrences(of: "\n", with: " ")
            return "- \(who)：\(String(text.prefix(120)))"
        }.joined(separator: "\n")
    }

    private func complete(
        system: String,
        user: String,
        model: ChatSessionEntity,
        persona: PersonaEntity
    ) async -> String? {
        guard let resolved = resolveConfig(model) else {
            log("[memo] 还没有可用的 API 配置")
            return nil
        }
        do {
            let result = try await completion.complete(
                config: resolved.config,
                model: resolved.model,
                messages: [.system(system), .user(user)],
                params: CompletionParams(temperature: Self.temperature, maxOutputTokens: Self.maxOutputTokens),
                kind: .history,
                context: UsageContext(personaId: persona.id, sessionId: model.id)
            )
            return SessionMemoryPolicy.clean(result.text, limit: SessionMemoryPolicy.longLimit)
        } catch {
            log("[memo] 调模型失败：\(error.localizedDescription)")
            return nil
        }
    }
}
