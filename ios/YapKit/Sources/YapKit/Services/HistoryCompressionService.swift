import Foundation

// 来源：app/src/main/java/com/yap/app/data/repo/HistoryCompressor.kt
//
// 长会话的滚动记忆：把已经聊过去的那一段，收成他自己还记得住的一段话。
//
// ## 这是一次「用完即弃的分叉调用」
//
// 自己拼 system + user 直接调模型：没有会话、没有历史、没有工具、不写 messages 表。
// 温度压到 0.2，输出只要一小段正文（≤ 500 token），记账记在 `HISTORY` 上，
// 所以在「用量」里能看出来这是「他在整理自己的记性」，不混进聊天。
//
// ## 绝不阻塞聊天
//
// 聊天主路径只在回复落库之后调 `ensureAsync`：异步跑、不 await、不抛异常。
// 没配 API、模型抽风、写库失败，都只是安静地什么都没发生。
//
// ## 绝不删消息
//
// 这里只写 `chat_sessions` 的三个字段（摘要正文、压到哪一条、什么时候压的），
// 原始消息一条都不动：搜索、导出、分页看到的仍然是完整的聊天记录。
//
// ## 判据全在 Domain
//
// 「该不该压」「压到哪一条」「压的时候怎么跟他说」「写回来的东西能不能当记忆」
// 都在 `Domain/HistoryCompression.swift`（有单测）。这个文件只做读库、调模型、落库。
//
// ## 守卫（1.7 事故的教训）
//
// 模型偶尔会把工具调用当成正文写回来（```yap-tool …```）。一旦那种东西被存成
// 「他还记得的事」，之后每一轮都会进提示词。所以：
// 1. `salvageSummary` 剥代码块，剥掉过东西的回复必须还剩足够长的一段话；
// 2. `isSummaryAcceptable` 再拒一次「带工具协议形状」「全是占位词」「推脱道歉」的文字；
// 3. `repairStaleSummaries` 启动时把库里已经写坏的旧摘要清掉（下次聊到该压时会重新压）。

/// 一次历史压缩的结论。与 Android 的 `CompressionStatus` 一一对应。
public enum CompressionStatus: String, Equatable, Sendable {
    /// 真的收成了一段记忆，并且写回了会话。
    case done = "DONE"
    /// 这次不用压（还短、已经压过、同一时间已经在跑……）。
    case skipped = "SKIPPED"
    /// 没有可用的 API 配置（或模型名），只能等下次。
    case noConfig = "NO_CONFIG"
    /// 模型写了东西，但没通过校验 —— 宁可当没发生。
    case refused = "REFUSED"
    /// 调用失败（网络、解析……）。都当成没做。
    case failed = "FAILED"
}

/// 一次压缩做成了什么。
public struct CompressionResult: Equatable, Sendable {
    public var status: CompressionStatus
    /// 写回会话的那段记忆（被拒绝 / 失败时为空）。
    public var summary: String
    /// 这段摘要覆盖到哪条消息的时间。
    public var compressedUpTo: Int64?
    /// 这次被收进摘要的消息条数。
    public var messageCount: Int
    /// 中文说明，给日志与调试面板用。
    public var note: String

    public init(
        status: CompressionStatus,
        summary: String = "",
        compressedUpTo: Int64? = nil,
        messageCount: Int = 0,
        note: String = ""
    ) {
        self.status = status
        self.summary = summary
        self.compressedUpTo = compressedUpTo
        self.messageCount = messageCount
        self.note = note
    }

    public var done: Bool { status == .done }

    /// 给日志 / 设置页「他最近在想什么」用的一句中文。
    public func describe() -> String {
        switch status {
        case .done: return note.isEmpty ? "更早的那些已经收成一段记忆" : note
        case .skipped, .refused, .failed, .noConfig: return note
        }
    }
}

/// 一次压缩要用到的东西：配置 + 模型名（Key 已经在 `CompletionConfig` 里）。
public struct CompressionConfig: Equatable, Sendable {
    public var config: CompletionConfig
    public var model: String

    public init(config: CompletionConfig, model: String) {
        self.config = config
        self.model = model
    }
}

public final class HistoryCompressionService {

    /// 判断「该不该压」时读最近的多少条：逐字保留 + 够一批 + 一点余量。
    public static let planWindow = HistoryCompression.DEFAULT_MAX_VERBATIM
        + HistoryCompression.DEFAULT_MIN_TO_COMPRESS
        + HistoryCompression.DEFAULT_MAX_BATCH + 8
    /// 找摘要边界时最多扫这么多条（只有长期没压过、边界很远时才会扫到这里）。
    public static let scanCap = 1024
    /// 记忆不需要创造力，但要稳定地说同一件事。
    public static let temperature = 0.2
    /// 摘要正文只要一小段：200 字上下，500 token 足够应付写超。
    public static let maxOutputTokens = 500

    private let stores: DatabaseStores
    private let completion: CompletionClient
    private let resolveConfig: (ChatSessionEntity) -> CompressionConfig?
    private let now: () -> Int64
    private let log: (String) -> Void

    /// 同一段对话同一时间只跑一次：免得连着两轮回复各触发一次、白烧一次额度。
    private let lock = NSLock()
    private var inFlight: Set<String> = []

    public init(
        stores: DatabaseStores,
        completion: CompletionClient,
        resolveConfig: @escaping (ChatSessionEntity) -> CompressionConfig?,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.completion = completion
        self.resolveConfig = resolveConfig
        self.now = now
        self.log = log
    }

    // MARK: - 入口

    /// 该压就压一次；不抛异常。
    public func maybeCompress(sessionId: String, now: Int64? = nil) async -> CompressionResult {
        let trimmed = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return CompressionResult(status: .skipped, note: "会话 id 是空的") }
        if !enter(trimmed) {
            return CompressionResult(status: .skipped, note: "这段对话已经在整理了")
        }
        defer { leave(trimmed) }
        do {
            return try await compress(sessionId: trimmed, now: now ?? self.now())
        } catch is CancellationError {
            return CompressionResult(status: .failed, note: "整理被中断了")
        } catch {
            // 任何意外都收在这里：压缩永远不是主线，失败就当没发生。
            return CompressionResult(
                status: .failed,
                note: String("整理的时候出错了：\(error.localizedDescription)".prefix(120))
            )
        }
    }

    /// 一轮回复写完之后顺手叫一下：异步、不等、不抛。
    public func ensureAsync(sessionId: String) {
        let trimmed = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return }
        Task { [weak self] in
            _ = await self?.maybeCompress(sessionId: trimmed)
        }
    }

    /// 启动时自检：把库里那些「不像记忆」的旧摘要清掉。
    ///
    /// 判据已补上，但**已经写进库里的脏数据不会自己消失**，所以每次启动顺手核一遍：
    /// 不合规的就清空（连带清掉「压到哪一条」），下次聊到该压的时候会重新压一遍。
    @discardableResult
    public func repairStaleSummaries() -> Int {
        let sessions = (try? stores.sessions.fetch(
            where: "historySummary IS NOT NULL AND historySummary != ''", []
        )) ?? []
        var cleared = 0
        for session in sessions {
            let summary = session.historySummary?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if summary.isEmpty { continue }
            if HistoryCompression.isLegacySummaryHealthy(summary) { continue }
            // 只清摘要三列：整行写回会把这段时间里别人写的预览 / 未读一起按旧快照盖回去
            // （0.10.2 审计 P1-1）。
            if (try? stores.sessions.clearHistorySummary(id: session.id, updatedAt: now())) == true {
                cleared += 1
                log("[history] 清掉了一段不像记忆的旧摘要（\(session.id)）")
            }
        }
        return cleared
    }

    // MARK: - 内部

    private func compress(sessionId: String, now: Int64) async throws -> CompressionResult {
        guard let session = (try? stores.sessions.byId(sessionId)) ?? nil else {
            return CompressionResult(status: .skipped, note: "会话不存在")
        }
        let total = (try? stores.messages.count(sessionId: sessionId)) ?? 0
        if total <= HistoryCompression.DEFAULT_MAX_VERBATIM {
            return CompressionResult(status: .skipped, note: "只有 \(total) 条，还不用收")
        }

        // 近处这一段只看窗口就够：压缩的结论只取决于「最近几条要逐字留」和「更早的够不够一批」。
        let windowRows = (try? stores.messages.fetch(
            where: "sessionId = ?", [.text(sessionId)],
            orderBy: "createdAt DESC", limit: Self.planWindow
        )) ?? []
        let window = windowRows.reversed().map(Self.view)
        let plan = HistoryCompression.plan(messages: window, summaryUpTo: session.historySummarizedUpTo)
        guard plan.shouldCompress, let keepFrom = plan.keepFrom else {
            return CompressionResult(status: .skipped, note: plan.reason)
        }

        let chunk = loadChunk(
            sessionId: sessionId,
            summaryUpTo: session.historySummarizedUpTo,
            before: keepFrom
        )
        if chunk.isEmpty {
            return CompressionResult(status: .skipped, note: "没找到可以收的那一段")
        }

        guard let resolved = resolveConfig(session) else {
            return CompressionResult(status: .noConfig, note: "还没给他配 API")
        }
        let model = resolved.model.trimmingCharacters(in: .whitespacesAndNewlines)
        if model.isEmpty {
            return CompressionResult(status: .noConfig, note: "这个配置里没有模型名")
        }

        let previous = session.historySummary?.trimmingCharacters(in: .whitespacesAndNewlines)
        let usablePrevious = (previous?.isEmpty == false) ? previous : nil

        // ---- 一次用完即弃的分叉调用：没有会话、没有工具、没有历史 ----
        let result: CompletionResult
        do {
            result = try await completion.complete(
                config: resolved.config,
                model: model,
                messages: [
                    .system(HistoryCompression.systemPrompt()),
                    .user(HistoryCompression.summaryPrompt(previous: usablePrevious, chunk: chunk))
                ],
                params: CompletionParams(temperature: Self.temperature, maxOutputTokens: Self.maxOutputTokens),
                kind: .history,
                context: UsageContext(personaId: session.personaId, sessionId: sessionId)
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return CompressionResult(
                status: .failed,
                messageCount: chunk.count,
                note: String("整理的时候出错了：\(error.localizedDescription)".prefix(120))
            )
        }

        // 先洗（剥代码块、清前缀），再判断：剥掉过东西的回复必须还剩下足够一段话，
        // 否则就只剩「嗯，这个我想记下来。」这种残句会被永久记成他的回忆。
        guard let summary = HistoryCompression.salvageSummary(result.text),
              HistoryCompression.isSummaryAcceptable(summary, previous: usablePrevious) else {
            return CompressionResult(
                status: .refused,
                messageCount: chunk.count,
                note: "写回来的东西不像一段记忆，这次不当真"
            )
        }

        guard let upTo = chunk.last?.at else {
            return CompressionResult(status: .skipped, note: "这一批消息没有可用的时间戳")
        }
        // 只写摘要三列（0.10.2 审计 P1-1）：整行写回会冲掉这几秒里用户新消息带来的
        // 预览 / 未读，以及抽记忆任务写的计数；会话被删时还会被 REPLACE 插回一行幽灵会话。
        //
        // 乐观检查用「压到哪儿」的单调性：只有这次压得比库里更远才写。
        // 两次压缩同时跑时，覆盖更多消息的那次生效，另一条只记日志（不弹错）。
        let wrote: Bool
        do {
            wrote = try stores.sessions.updateHistorySummary(
                id: session.id,
                summary: summary,
                summarizedUpTo: upTo,
                summaryAt: now,
                updatedAt: now
            )
        } catch {
            return CompressionResult(
                status: .failed,
                messageCount: chunk.count,
                note: "写回摘要时出错，当作没收过"
            )
        }
        guard wrote else {
            log("[history] 会话已被删除，或摘要已被更靠后的一次整理盖过（\(sessionId)），这次不写回")
            return CompressionResult(
                status: .skipped,
                messageCount: chunk.count,
                note: "摘要已经被更新的一次整理盖过，这次不写回"
            )
        }
        log("[history] 更早的 \(chunk.count) 条收成一段记忆（\(sessionId)）")
        return CompressionResult(
            status: .done,
            summary: summary,
            compressedUpTo: upTo,
            messageCount: chunk.count,
            note: "更早的 \(chunk.count) 条已经收成一段记忆"
        )
    }

    /// 取出「摘要边界之后、逐字保留段之前」最旧的一批消息。
    ///
    /// Android 的 DAO 只有「取最旧 N 条 / 取最近 N 条」两种读法，所以那边写了两种扫描策略；
    /// iOS 的 `SQLiteStore.fetch` 支持带条件与 limit 的查询，这里直接用 SQL 表达同一件事：
    /// 「边界之后、逐字保留段之前」按时间升序取一批（≤ `DEFAULT_MAX_BATCH` 条），覆盖一定连续。
    private func loadChunk(sessionId: String, summaryUpTo: Int64?, before: Int64) -> [MsgView] {
        var conditions: [String] = ["sessionId = ?", "createdAt < ?"]
        var bindings: [SQLiteValue] = [.text(sessionId), .int(before)]
        if let summaryUpTo, summaryUpTo > 0 {
            conditions.append("createdAt > ?")
            bindings.append(.int(summaryUpTo))
        }
        let rows = (try? stores.messages.fetch(
            where: conditions.joined(separator: " AND "),
            bindings,
            orderBy: "createdAt ASC",
            limit: HistoryCompression.DEFAULT_MAX_BATCH
        )) ?? []
        return rows.map(Self.view)
    }

    static func view(_ message: MessageEntity) -> MsgView {
        MsgView(
            id: message.id,
            at: message.createdAt,
            fromUser: message.role == .user,
            text: message.content
        )
    }

    private func enter(_ sessionId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if inFlight.contains(sessionId) { return false }
        inFlight.insert(sessionId)
        return true
    }

    private func leave(_ sessionId: String) {
        lock.lock()
        inFlight.remove(sessionId)
        lock.unlock()
    }
}
