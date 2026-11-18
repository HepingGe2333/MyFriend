import Foundation

// 聊天结束后那两件「后台该做的事」的真实现（`ChatService` 只负责说一声「该做了」）。
//
// 为什么要有这一层：`ChatService` 定义了两个很窄的回调（抽记忆 / 压摘要），
// 但真正跑起来需要「解析 API 配置 → 起一个后台 Task → 失败当没发生」这一整套装配。
// 这些不属于聊天链路，也不属于 UI，放在这里最合适：
//
// - `historyCompressionNeeded` → `HistoryCompressionService.ensureAsync`（自己会守并发、失败吞掉）；
// - `memoryExtractionNeeded`   → `MemoryExtraction.extract`（同样异步、失败只写日志）。
//
// 两条都遵守同一套规矩：**不阻塞回复、不抛给用户、失败只落日志**。
// 并发的全局上限在网络层（`LlmConcurrencyGate`）那一处，这里不重复实现闸。

public final class ChatBackgroundService: ChatBackgroundTasks {

    private let stores: DatabaseStores
    private let extraction: MemoryExtraction
    private let compression: HistoryCompressionService
    private let resolveConfig: (ChatSessionEntity) -> CompressionConfig?
    private let log: (String) -> Void
    /// 人格进化（0.13）。可选：没装进化服务时这条链路安静跳过。
    private let evolution: PersonaEvolutionService?
    /// 自动情绪判定（0.13 schema v17）。同样可选。
    private let moodJudgement: MoodJudgementService?
    /// 会话备忘录（0.14）。可选：没装时这条链路安静跳过。
    private let sessionMemory: SessionMemoryService?
    /// 心理活动（0.14）。可选：没装时这条链路安静跳过。
    private let mentalActivity: MentalActivityService?
    /// 服务端模式判定（0.13）：服务端独立运行，本机不再跑「回复后的后台自主逻辑」。
    /// 用闭包是因为模式运行中可切换；默认 `false` 让测试与老调用点不用改。
    private let isServerMode: () -> Bool
    private let lock = NSLock()
    private var extracting: Set<String> = []

    public init(
        stores: DatabaseStores,
        extraction: MemoryExtraction,
        compression: HistoryCompressionService,
        resolveConfig: @escaping (ChatSessionEntity) -> CompressionConfig?,
        evolution: PersonaEvolutionService? = nil,
        moodJudgement: MoodJudgementService? = nil,
        sessionMemory: SessionMemoryService? = nil,
        mentalActivity: MentalActivityService? = nil,
        isServerMode: @escaping () -> Bool = { false },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.extraction = extraction
        self.compression = compression
        self.resolveConfig = resolveConfig
        self.evolution = evolution
        self.moodJudgement = moodJudgement
        self.sessionMemory = sessionMemory
        self.mentalActivity = mentalActivity
        self.isServerMode = isServerMode
        self.log = log
    }

    /// 服务端模式：本机的后台自主逻辑（抽记忆/压摘要/进化/情绪）一律不跑。
    ///
    /// 为什么连「压摘要」也停：服务端模式下上下文由服务端维护，本机压一遍既花一次模型调用，
    /// 又会和下一次同步进来的块对不上。
    private var localAutonomyAllowed: Bool { !isServerMode() }

    public func memoryExtractionNeeded(sessionId: String, personaId: String) {
        guard localAutonomyAllowed else { return }
        let trimmed = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return }
        guard enter(trimmed) else { return }
        let extraction = self.extraction
        let resolve = self.resolveConfig
        let stores = self.stores
        let log = self.log
        Task { [weak self] in
            defer { self?.leave(trimmed) }
            guard let session = (try? stores.sessions.byId(trimmed)) ?? nil else { return }
            guard let resolved = resolve(session) else {
                log("[memory] 还没有可用的 API 配置，这次没抽记忆")
                return
            }
            let result = await extraction.extract(
                sessionId: trimmed,
                config: resolved.config,
                model: resolved.model
            )
            switch result {
            case .success(let outcome):
                log("[memory] \(outcome.message)（会话 \(trimmed)）")
            case .failure(let error):
                log("[memory] 抽取失败：\(error.localizedDescription)")
            }
        }
    }

    public func historyCompressionNeeded(sessionId: String) {
        guard localAutonomyAllowed else { return }
        compression.ensureAsync(sessionId: sessionId)
    }

    /// 人格进化评估（`docs/design/persona-evolution.md` §2）。
    ///
    /// 先问服务「到节奏了吗」（每 20 条用户消息 / 24 小时），没到就什么都不做——
    /// 这条路径每次回复都会走一遍，所以判定必须在开 Task 之前做。
    ///
    /// 单飞名额在 `PersonaEvolutionService` 里抢：云端模式
    /// （`CloudPersonaEvolution`）走的是同一个服务对象、同一道闸，
    /// 所以两条触发路径同时命中、或者刚切完模式，都不会评估两次。
    public func personaEvolutionNeeded(sessionId: String, personaId: String) {
        guard localAutonomyAllowed else { return }
        guard let evolution else { return }
        let trimmedSession = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedSession.isEmpty else { return }
        guard evolution.shouldEvaluate(personaId: personaId) else { return }
        guard evolution.beginEvaluation(personaId: personaId) else { return }
        let stores = self.stores
        let resolve = self.resolveConfig
        let log = self.log
        Task {
            // 不管成功/失败都要还名额（评估失败也一样，别把这个人格永久卡在「正在评估」）。
            defer { evolution.endEvaluation(personaId: personaId) }
            guard let session = (try? stores.sessions.byId(trimmedSession)) ?? nil else { return }
            guard let resolved = resolve(session) else {
                log("[evolution] 还没有可用的 API 配置，这次不评估")
                return
            }
            let result = await evolution.evaluate(
                personaId: personaId,
                sessionId: trimmedSession,
                config: resolved.config,
                model: resolved.model
            )
            switch result {
            case .success(let outcome):
                log("[evolution] \(outcome.message)（人格 \(personaId)）")
            case .failure(let error):
                log("[evolution] 评估失败：\(error.localizedDescription)")
            }
        }
    }

    /// 自动情绪判定（`spec/mood/README.md` §2）。
    ///
    /// 触发节奏由服务判定（每 4 条用户消息 / 24 小时），没到就什么都不做 ——
    /// 这条路径每次回复都会走一遍，所以判定必须在开 Task 之前做。
    ///
    /// 三种失败都不报错、不打扰用户：没装服务（安静跳过）、没配 API（按时间衰减）、
    /// 模型输出坏 JSON（服务内部按时间衰减并写日志）。
    public func moodJudgementNeeded(sessionId: String, personaId: String) {
        guard localAutonomyAllowed else { return }
        guard let moodJudgement else { return }
        let trimmedSession = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedSession.isEmpty else { return }
        guard moodJudgement.shouldJudge(personaId: personaId) else { return }
        guard moodJudgement.beginJudgement(personaId: personaId) else { return }
        let stores = self.stores
        let resolve = self.resolveConfig
        let log = self.log
        Task {
            // 不管成功/失败都要还名额（判定失败也一样，别把这个人格永久卡在「正在判定」）。
            defer { moodJudgement.endJudgement(personaId: personaId) }
            guard let session = (try? stores.sessions.byId(trimmedSession)) ?? nil else { return }
            guard let resolved = resolve(session) else {
                log("[mood] 还没有可用的 API 配置，这次不判定（心情按时间衰减）")
                moodJudgement.decay(personaId: personaId)
                return
            }
            let result = await moodJudgement.judge(
                personaId: personaId,
                sessionId: trimmedSession,
                config: resolved.config,
                model: resolved.model
            )
            switch result {
            case .success(let outcome):
                log("[mood] \(outcome.message)（人格 \(personaId)）")
            case .failure(let error):
                // 判定服务内部已把失败变成「按时间衰减」，走到这里说明人格/库出了别的问题：
                // 仍然只写日志，不让一次后台调用影响聊天。
                log("[mood] 判定失败：\(error.localizedDescription)")
                moodJudgement.decay(personaId: personaId)
            }
        }
    }

    /// 0.14 会话备忘录：每 5 / 30 条用户消息更新短期 / 长期摘要。
    ///
    /// 节流与服务内的「单飞」都在 `SessionMemoryService` 里；
    /// 这里只负责「回复已经落库了，异步跑一次，失败当没发生」。
    public func sessionMemoryNeeded(sessionId: String, personaId: String) {
        guard localAutonomyAllowed else { return }
        guard let sessionMemory else { return }
        let trimmed = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task {
            await sessionMemory.updateIfNeeded(sessionId: trimmed, personaId: personaId)
        }
    }

    /// 0.14 心理活动：活跃窗口内每 3 分钟 / 3 轮写一句私密念头。
    ///
    /// 判定（活跃窗口 / 节流 / 心情激动必做）在 `MentalActivityService` 里；
    /// 这里只把「聊完这一轮」这件事交出去，失败不影响回复。
    public func mentalActivityNeeded(sessionId: String, personaId: String) {
        guard localAutonomyAllowed else { return }
        guard let mentalActivity else { return }
        let trimmed = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task {
            await mentalActivity.runIfNeeded(personaId: personaId, sessionId: trimmed)
        }
    }

    private func enter(_ sessionId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if extracting.contains(sessionId) { return false }
        extracting.insert(sessionId)
        return true
    }

    private func leave(_ sessionId: String) {
        lock.lock()
        extracting.remove(sessionId)
        lock.unlock()
    }
}
