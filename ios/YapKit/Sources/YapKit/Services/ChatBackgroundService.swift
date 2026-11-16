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
    private let lock = NSLock()
    private var extracting: Set<String> = []

    public init(
        stores: DatabaseStores,
        extraction: MemoryExtraction,
        compression: HistoryCompressionService,
        resolveConfig: @escaping (ChatSessionEntity) -> CompressionConfig?,
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.extraction = extraction
        self.compression = compression
        self.resolveConfig = resolveConfig
        self.log = log
    }

    public func memoryExtractionNeeded(sessionId: String, personaId: String) {
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
        compression.ensureAsync(sessionId: sessionId)
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
