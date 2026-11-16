import Foundation

// ---------------------------------------------------------------------------
// 人格进化的评估与「待确认变化」的流转（`docs/design/persona-evolution.md` §2）。
//
// 这一层负责三件事，校验本身在 `Domain/PersonaEvolution`（纯函数，可穷举测试）：
// 1. **什么时候评估**：每 20 条用户消息，或距上次评估 ≥24 小时（游标见
//    `PersonaEvolutionCursor`）；
// 2. **评估一次**：当前人格四字段 + 最近对话 → 一次 completion（temperature 0.2，
//    因为要的是稳定 JSON 而不是文采）→ 校验 → 写 `persona_pending_patches`；
// 3. **人审**：接受（写回人格、`version+1`）、拒绝（删掉）、7 天过期（读的时候顺手清）。
//
// 为什么评估在后台跑：它是一次额外的模型调用，绝不能挡住回复。`ChatBackgroundService`
// 在回复落库之后调它，失败只写日志（与抽记忆/压摘要同一套规矩）。
// ---------------------------------------------------------------------------

public final class PersonaEvolutionService {

    /// 一次评估的结果（日志与测试用）。
    public struct Outcome: Equatable, Sendable {
        /// 这次是不是真的调了模型。
        public var evaluated: Bool
        /// 校验后还剩几条合法变化。
        public var proposed: Int
        /// 真正新落库的待确认条目（去重后）。
        public var created: Int
        /// 一句人话，进日志。
        public var message: String

        public init(evaluated: Bool = false, proposed: Int = 0, created: Int = 0, message: String = "") {
            self.evaluated = evaluated
            self.proposed = proposed
            self.created = created
            self.message = message
        }
    }

    /// 评估那一轮用的参数：低温度（要稳定 JSON），输出不需要很长（≤3 条变化）。
    public static let temperature = 0.2
    public static let maxOutputTokens = 800

    private let stores: DatabaseStores
    private let completion: CompletionClient
    private let cursors: PersonaEvolutionCursorStoring
    private let now: () -> Int64
    private let log: (String) -> Void

    public init(
        stores: DatabaseStores,
        completion: CompletionClient,
        cursors: PersonaEvolutionCursorStoring = UserDefaultsEvolutionCursorStore(),
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.completion = completion
        self.cursors = cursors
        self.now = now
        self.log = log
    }

    // MARK: - 触发节奏

    public func userMessageCount(personaId: String) -> Int {
        (try? stores.messages.count(where: "personaId = ? AND role = 'USER'", [.text(personaId)])) ?? 0
    }

    /// 现在该不该评估这个人格。
    ///
    /// 第一次调用只记基线（不评估）：否则「刚装好 App、消息数 0」会被当成
    /// 「已经过了 24 小时」而立刻评估一次空对话。
    public func shouldEvaluate(personaId: String, now at: Int64? = nil) -> Bool {
        let at = at ?? now()
        let total = userMessageCount(personaId: personaId)
        guard total > 0 else { return false }
        guard let cursor = cursors.cursor(personaId: personaId), cursor.lastEvaluatedAt > 0 else {
            // 还没有基线：从此刻开始计时，等消息数或时间到了再评估。
            cursors.save(
                PersonaEvolutionCursor(lastEvaluatedAt: at, userMessageCount: total),
                personaId: personaId
            )
            return false
        }
        let newMessages = total - cursor.userMessageCount
        if newMessages >= PersonaEvolution.triggerUserMessages { return true }
        return at - cursor.lastEvaluatedAt >= PersonaEvolution.triggerIntervalMillis
    }

    /// 评估一次。
    ///
    /// **不管成功与否都会推进游标**：模型这次输出坏 JSON，不代表下一条用户消息要再烧一次调用；
    /// 等下一次节奏（再 20 条 / 再 24 小时）即可。
    @discardableResult
    public func evaluate(
        personaId: String,
        sessionId: String?,
        config: CompletionConfig,
        model: String
    ) async -> Result<Outcome, Error> {
        let at = now()
        defer { advanceCursor(personaId: personaId, at: at) }

        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else {
            return .failure(CompletionFailure(String(localized: "人格不存在")))
        }
        let session = sessionId.flatMap { (try? stores.sessions.byId($0)) ?? nil }
        let recent = session.map { current in
            (try? stores.messages.recent(
                sessionId: current.id, limit: PersonaEvolution.recentMessageLimit
            )) ?? []
        } ?? []
        let dialogue = Self.renderDialogue(recent)
        let messages = PersonaEvolution.messages(
            persona: persona,
            recentDialogue: dialogue,
            earlierSummary: session?.historySummary
        )
        do {
            let response = try await completion.complete(
                config: config,
                model: model,
                messages: messages,
                params: CompletionParams(
                    temperature: Self.temperature,
                    maxOutputTokens: Self.maxOutputTokens
                ),
                // 复用 EXTRACTION 类目：它也是「从对话里提炼出一条东西」的后台调用，
                // 不新增用量类目（那需要两端同时改用量的展示与备份）。
                kind: .extraction,
                context: UsageContext(personaId: persona.id, sessionId: sessionId)
            )
            guard let evaluation = PersonaEvolution.parse(response.text, persona: persona) else {
                log("[evolution] 评估结果不是合法 JSON，这一次跳过")
                return .success(Outcome(evaluated: true, message: "评估结果不是合法 JSON"))
            }
            var created = 0
            for change in evaluation.changes {
                let patch = PersonaPendingPatchEntity(
                    id: UUID().uuidString,
                    personaId: persona.id,
                    sessionId: sessionId,
                    patchJson: PersonaEvolution.encodePatch([change]),
                    summary: PersonaEvolution.summaryLine(change),
                    reason: change.reason,
                    createdAt: at
                )
                if (try? stores.personaPendingPatches.insertIfNew(patch)) == true {
                    created += 1
                }
            }
            let message = evaluation.isEmpty
                ? "这次没有值得改的地方"
                : "提出 \(evaluation.changes.count) 条，新增 \(created) 条待确认"
            log("[evolution] \(message)")
            return .success(Outcome(
                evaluated: true,
                proposed: evaluation.changes.count,
                created: created,
                message: message
            ))
        } catch let failure as CompletionFailure {
            return .failure(failure)
        } catch {
            let text = (error as NSError).localizedDescription
            return .failure(CompletionFailure(text.isEmpty ? String(localized: "评估失败") : text))
        }
    }

    private func advanceCursor(personaId: String, at: Int64) {
        cursors.save(
            PersonaEvolutionCursor(
                lastEvaluatedAt: at,
                userMessageCount: userMessageCount(personaId: personaId)
            ),
            personaId: personaId
        )
    }

    /// 最近对话 → 文本。只带角色与正文（图片、工具痕迹不带：评估人格不需要它们）。
    static func renderDialogue(_ messages: [MessageEntity]) -> String {
        messages
            .filter { $0.role == .user || $0.role == .assistant }
            .filter { $0.status != .error }
            .compactMap { message -> String? in
                let content = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !content.isEmpty else { return nil }
                let speaker = message.role == .user ? String(localized: "对方") : String(localized: "我")
                return "\(speaker)：\(String(content.prefix(400)))"
            }
            .joined(separator: "\n")
    }

    // MARK: - 待确认变化（人审）

    /// 读这个人格的待确认变化：先清掉 7 天过期的，再返回（新的在前）。
    public func pending(personaId: String, at: Int64? = nil) -> [PersonaPendingPatchEntity] {
        _ = try? stores.personaPendingPatches.removeExpired(now: at ?? now())
        return (try? stores.personaPendingPatches.byPersona(personaId)) ?? []
    }

    /// 用户点了「接受」：把这条变化写回人格，`version+1`，然后删掉待确认条目。
    ///
    /// - Returns: 写入后的人格与实际生效的条数（通常 1；0 表示「这条已经不成立了」，
    ///   比如人格里已经有这个值了 —— 那也不算失败，条目照样删掉）。
    @discardableResult
    public func approve(patchId: String, at nowAt: Int64? = nil) throws -> (persona: PersonaEntity, applied: Int) {
        guard let patch = (try? stores.personaPendingPatches.byId(patchId)) ?? nil else {
            throw PersonaEvolutionError.patchNotFound
        }
        guard let persona = (try? stores.personas.byId(patch.personaId)) ?? nil else {
            try? stores.personaPendingPatches.remove(id: patchId)
            throw PersonaEvolutionError.personaNotFound
        }
        let changes = PersonaEvolution.decodePatch(patch.patchJson)
        let (updated, applied) = PersonaEvolution.apply(
            changes, to: persona, now: nowAt ?? now()
        )
        if applied > 0 {
            try stores.personas.upsert(updated)
        }
        try stores.personaPendingPatches.remove(id: patchId)
        return (updated, applied)
    }

    /// 用户点了「拒绝」：只丢掉这条提议，人格一个字都不动。
    public func reject(patchId: String) throws {
        try stores.personaPendingPatches.remove(id: patchId)
    }

    /// 从待确认条目里取出一条给人看的 changes（界面展示「多了什么/少了什么」）。
    public static func changes(of patch: PersonaPendingPatchEntity) -> [PersonaEvolution.Change] {
        PersonaEvolution.decodePatch(patch.patchJson)
    }
}

public enum PersonaEvolutionError: Error, LocalizedError, Equatable {
    case patchNotFound
    case personaNotFound

    public var errorDescription: String? {
        switch self {
        case .patchNotFound: return String(localized: "这条变化已经不在了")
        case .personaNotFound: return String(localized: "人格不存在")
        }
    }
}
