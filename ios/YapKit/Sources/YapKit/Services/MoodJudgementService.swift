import Foundation

// ---------------------------------------------------------------------------
// 自动情绪判定的服务层（`spec/mood/README.md` §2，schema v17）。
//
// 这一层只做四件事（校验本身在 `Domain/MoodJudgement`，纯函数）：
// 1. **什么时候判**：每 4 条用户消息，或距上次判定 ≥24 小时（游标见 `MoodJudgementCursor`）；
// 2. **判一次**：上一轮心情 + 最近 8 条对话摘要 → 一次 completion（temperature 0.2，
//    要的是稳定 JSON 而不是文采）；
// 3. **写库**：`MoodJudgementRules.shouldWrite` 说「有变化」才写；
// 4. **失败兜底**：坏 JSON / 调用失败 / 没配 API → 按小时往基准衰减，只写日志，不报错。
//
// 为什么在后台跑：它是一次额外的模型调用，绝不能挡住回复。`ChatBackgroundService`
// 在回复落库之后调它，与抽记忆/压摘要/人格进化同一套规矩。
// ---------------------------------------------------------------------------

public final class MoodJudgementService {

    /// 一次判定的结果（日志与测试用）。
    public struct Outcome: Equatable, Sendable {
        /// 这次是不是真的调了模型。
        public var evaluated: Bool
        /// 有没有真的把新心情写进库。
        public var written: Bool
        /// 一句人话，进日志。
        public var message: String

        public init(evaluated: Bool = false, written: Bool = false, message: String = "") {
            self.evaluated = evaluated
            self.written = written
            self.message = message
        }
    }

    private let stores: DatabaseStores
    private let completion: CompletionClient
    private let cursors: MoodJudgementCursorStoring
    private let mood: MoodRepository
    private let now: () -> Int64
    private let log: (String) -> Void

    /// 单飞闸：同一个人格同时只判一次（聊天触发 + 后台补算可能撞车）。
    private let flightLock = NSLock()
    private var inFlight: Set<String> = []

    public init(
        stores: DatabaseStores,
        completion: CompletionClient,
        mood: MoodRepository,
        cursors: MoodJudgementCursorStoring = UserDefaultsMoodJudgementCursorStore(),
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.completion = completion
        self.mood = mood
        self.cursors = cursors
        self.now = now
        self.log = log
    }

    // MARK: - 触发节奏

    public func userMessageCount(personaId: String) -> Int {
        (try? stores.messages.count(where: "personaId = ? AND role = 'USER'", [.text(personaId)])) ?? 0
    }

    /// 现在该不该判这个人格。
    ///
    /// 第一次调用只记基线（不判定）：否则「刚装好 App、消息数 0」会被当成
    /// 「已经过了 24 小时」而立刻判一次空对话。
    public func shouldJudge(personaId: String, now at: Int64? = nil) -> Bool {
        let at = at ?? now()
        let total = userMessageCount(personaId: personaId)
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

    /// 抢单飞名额（true = 这次归你判）。
    public func beginJudgement(personaId: String) -> Bool {
        flightLock.lock()
        defer { flightLock.unlock() }
        if inFlight.contains(personaId) { return false }
        inFlight.insert(personaId)
        return true
    }

    public func endJudgement(personaId: String) {
        flightLock.lock()
        inFlight.remove(personaId)
        flightLock.unlock()
    }

    // MARK: - 判定

    /// 判一次。
    ///
    /// **不管成功与否都会推进游标**：模型这次输出坏 JSON，不代表下一条用户消息要再烧一次调用；
    /// 等下一次节奏（再 4 条 / 再 24 小时）即可。
    @discardableResult
    public func judge(
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
            ?? ((try? stores.sessions.session(forPersona: persona.id)) ?? nil)
        let recent = session.map { current in
            (try? stores.messages.recent(
                sessionId: current.id, limit: MoodJudgementRules.recentMessageLimit
            )) ?? []
        } ?? []
        let previous = mood.current(personaId: persona.id, now: at)
        let messages = MoodJudgementRules.messages(
            previous: previous,
            recentDialogue: MoodJudgementRules.renderDialogue(recent),
            earlierSummary: session?.historySummary
        )
        do {
            let response = try await completion.complete(
                config: config,
                model: model,
                messages: messages,
                params: CompletionParams(
                    temperature: MoodJudgementRules.temperature,
                    maxOutputTokens: MoodJudgementRules.maxOutputTokens
                ),
                // 复用 EXTRACTION 类目：它也是「从对话里提炼一点东西」的后台调用。
                // 不新增用量类目（那要两端同时改用量的展示与备份）。
                kind: .extraction,
                context: UsageContext(personaId: persona.id, sessionId: session?.id)
            )
            guard let judgement = MoodJudgementRules.parse(response.text, previous: previous) else {
                log("[mood] 判定结果不是合法 JSON，这一次跳过（等下一个节奏）")
                return .success(Outcome(evaluated: true, message: "判定结果不是合法 JSON"))
            }
            guard MoodJudgementRules.shouldWrite(judgement, previous: previous) else {
                return .success(Outcome(evaluated: true, message: "心情没有明显变化，保持原样"))
            }
            let next = mood.applyJudgement(
                personaId: persona.id, judgement: judgement, previous: previous, now: at
            )
            guard let next else {
                return .success(Outcome(evaluated: true, message: "人格已不存在，没写进去"))
            }
            log("[mood] 心情更新：\(next.display())")
            return .success(Outcome(
                evaluated: true, written: true,
                message: "心情变成「\(next.label)」（\(next.display())）"
            ))
        } catch {
            // 失败按小时衰减（规范 §2）：不把错误抛给界面，也不让心情僵在一个旧值上。
            let decayed = mood.decayOnFailure(personaId: persona.id, now: at)
            log("[mood] 判定失败，按时间衰减：\(error.localizedDescription)")
            return .success(Outcome(
                evaluated: false, written: decayed.changed,
                message: "判定失败，心情按时间往平静挪"
            ))
        }
    }

    /// 没有可用的 API 配置时也要衰减（规范 §2：「失败/无配置：按时间衰减」）。
    @discardableResult
    public func decay(personaId: String, now at: Int64? = nil) -> Bool {
        mood.decayOnFailure(personaId: personaId, now: at).changed
    }

    private func advanceCursor(personaId: String, at: Int64) {
        cursors.save(
            MoodJudgementCursor(
                lastJudgedAt: at,
                userMessageCount: userMessageCount(personaId: personaId)
            ),
            personaId: personaId
        )
    }
}
