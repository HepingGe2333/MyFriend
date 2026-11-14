import Foundation

// 来源：app/src/main/java/com/myfriend/app/data/repo/WebSearchScorer.kt
//
// 联网搜索的「重要性打分器」：搜完顺手判断「值不值得记」，值得就写进长期记忆。
//
// ## 「随风叉、用完即弃」
//
// 打分**不是**一次对话：这里自己拼两份 messages（system = 打分器提示词，user = 搜索词 + 结果摘要）
// 直接调模型，不建 session、不写 messages 表、不给工具、不读历史会话；拿到 JSON 就落库，
// 然后把请求上下文丢掉。界面上永远看不到这轮「打分对话」。
//
// ## 为什么它自己拿一个 `AsyncMutex`
//
// 同一轮工具调用里模型可能连开两次 `web_search`，两次打分同时写库会各写一条几乎一样的记忆。
// 与 Android 的 `scoreMutex` 一致：同一时间只打一次分，后面那次等前面结束。
//
// ## 与 Android 的对齐 / 差异
//
// - 判据是同一份 `ImportanceScoring.SHARED_CRITERIA`（聊天、上网、每日整理同一个标准）；
// - 阈值同为 `REMEMBER_FLOOR = 3`；提示词、温度 0.2、输出上限 300、用量类目 `SCORING` 一致；
// - 来源标记一致：`sourceQuote = "他自己上网看到的"`，不是用户说过的话；
// - 失败一律降级成「这次不记」，绝不把错误抛给搜索结果或本轮回复（与 Android 的 `runCatching` 同义）；
// - **iOS 多做一步去重**：写之前先比一遍已有记忆（`MemoryScreening.similarity ≥ 0.72`），
//   同一件事不重复写一条。Android 是直接写，长期用下来同一件事被搜到两次就会有两行。
//   这是本任务「写入去重」要求的那一步，差异写在这里而不是悄悄改判据。

/// 一次打分的结果。字段与 Android 的 `WebSearchScorer.ScoreResult` 一一对应。
public struct WebSearchScoreResult: Equatable, Sendable {
    /// 是否真的问了模型（没内容 / 没配置 / 出错时为 false）。
    public var scored: Bool
    /// 模型判断值不值得记。
    public var important: Bool
    public var importance: Int
    public var takeaway: String
    public var drawer: MemoryDrawer
    /// 写进记忆的 id；不值得 / 去重命中 / 写失败时为 nil。
    public var memoryId: String?
    /// 排查用的一句话（同时也让测试能断言「为什么没记」）。
    public var message: String

    public init(
        scored: Bool,
        important: Bool,
        importance: Int,
        takeaway: String,
        drawer: MemoryDrawer,
        memoryId: String? = nil,
        message: String = ""
    ) {
        self.scored = scored
        self.important = important
        self.importance = importance
        self.takeaway = takeaway
        self.drawer = drawer
        self.memoryId = memoryId
        self.message = message
    }
}

/// 工具执行器对打分器的唯一依赖（测试里可以注入一个不联网的假实现）。
public protocol WebSearchScoring: AnyObject {
    /// 后台打分：搜索本身不必等它。`hitsSummary` 为空时什么都不做。
    func scoreInBackground(
        personaId: String,
        query: String,
        hitsSummary: String,
        config: CompletionConfig,
        model: String
    )
}

public final class WebSearchScorer: WebSearchScoring {

    public static let sourceQuote = "他自己上网看到的"
    public static let maxQueryChars = 200
    public static let maxHitsSummaryChars = 2000
    public static let maxContentChars = 400
    /// 判断类任务：温度压低，输出只有一行 JSON。
    public static let temperature = 0.2
    public static let maxOutputTokens = 300

    /// 已经有一条很像的记忆就不再写：与 `MemoryScreening.reinforceSimilarity` 同一个门槛。
    public static let dedupeSimilarity = MemoryScreening.reinforceSimilarity

    private let stores: DatabaseStores
    private let memory: MemoryRepository
    private let completion: CompletionClient
    private let now: () -> Int64
    private let log: (String) -> Void
    /// 同一时间只打一次分（与 Android 的 `scoreMutex` 同语义）。
    private let scoreGate = AsyncMutex()

    public init(
        stores: DatabaseStores,
        memory: MemoryRepository,
        completion: CompletionClient,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.memory = memory
        self.completion = completion
        self.now = now
        self.log = log
    }

    // MARK: - 后台入口

    public func scoreInBackground(
        personaId: String,
        query: String,
        hitsSummary: String,
        config: CompletionConfig,
        model: String
    ) {
        let summary = hitsSummary.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !summary.isEmpty else { return }
        guard let key = config.apiKey?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty else {
            return
        }
        // 打分是「顺手做的事」：用独立 Task，别挂在工具调用/这一轮回复的生命周期上。
        Task { [weak self] in
            guard let self else { return }
            let result = await self.score(
                personaId: personaId, query: query, hitsSummary: summary,
                config: config, model: model
            )
            self.log("[scorer] scored=\(result.scored) important=\(result.important) memoryId=\(result.memoryId ?? "nil") msg=\(result.message)")
        }
    }

    // MARK: - 打分

    /// 判断「他刚搜到的这些值不值得记住」，值得就顺手写进长期记忆。**不抛异常**。
    public func score(
        personaId: String,
        query: String,
        hitsSummary: String,
        config: CompletionConfig,
        model: String
    ) async -> WebSearchScoreResult {
        await scoreGate.withLock {
            await scoreLocked(
                personaId: personaId, query: query, hitsSummary: hitsSummary,
                config: config, model: model
            )
        }
    }

    private func scoreLocked(
        personaId: String,
        query: String,
        hitsSummary: String,
        config: CompletionConfig,
        model: String
    ) async -> WebSearchScoreResult {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let summary = hitsSummary.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQuery.isEmpty, !summary.isEmpty else {
            return empty("没有可打分的内容")
        }
        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else {
            return empty("找不到这个人格，没打分")
        }

        // ---- 一次用完即弃的分叉调用：没有会话、没有工具、没有历史 ----
        let messages = forkedMessages(persona: persona, query: trimmedQuery, hitsSummary: summary)
        let raw: String
        do {
            let result = try await completion.complete(
                config: config,
                model: model,
                messages: messages,
                params: CompletionParams(
                    temperature: Self.temperature,
                    maxOutputTokens: Self.maxOutputTokens
                ),
                kind: .scoring,
                context: UsageContext(personaId: persona.id, sessionId: nil)
            )
            raw = result.text
        } catch {
            // 打分失败不影响这次搜索本身：调用方拿到的就是「没记」。
            log("[scorer] 打分请求失败：\(error.localizedDescription)")
            return empty("打分时出错了：\(String(error.localizedDescription.prefix(100)))")
        }

        guard let score = ImportanceScoring.parseSearchScore(raw) else {
            return empty("打分结果看不懂，这次不记")
        }
        guard ImportanceScoring.shouldRemember(score) else {
            return WebSearchScoreResult(
                scored: true,
                important: score.important,
                importance: score.importance,
                takeaway: score.takeaway,
                drawer: score.drawer,
                memoryId: nil,
                message: "这次搜到的东西不值得记"
            )
        }

        let takeaway = score.takeaway.trimmingCharacters(in: .whitespacesAndNewlines)
        let content = takeaway.isEmpty
            ? "他查了「\(String(trimmedQuery.prefix(40)))」，看到一些以后可能用得上的事。"
            : takeaway

        // 去重：同一件事已经在抽屉里，就不要再写一行。
        if let duplicate = duplicateOf(personaId: persona.id, content: content) {
            return WebSearchScoreResult(
                scored: true,
                important: true,
                importance: score.importance,
                takeaway: content,
                drawer: score.drawer,
                memoryId: nil,
                message: "这件事记忆里已经有了（\(duplicate)）"
            )
        }

        let written = memory.addDetailed(
            personaId: persona.id,
            content: String(content.prefix(Self.maxContentChars)),
            category: Self.category(of: score.drawer),
            importance: ImportanceScoring.clampImportance(score.importance),
            pinned: false,
            drawer: score.drawer.rawValue,
            sourceQuote: Self.sourceQuote,
            sourceSessionId: nil
        )
        switch written {
        case .success(let id):
            return WebSearchScoreResult(
                scored: true, important: true, importance: score.importance,
                takeaway: content, drawer: score.drawer, memoryId: id,
                message: "记住了一条上网看到的"
            )
        case .failure(let error):
            log("[scorer] 写记忆失败：\(error.localizedDescription)")
            return WebSearchScoreResult(
                scored: true, important: true, importance: score.importance,
                takeaway: content, drawer: score.drawer, memoryId: nil,
                message: "想记但没记成"
            )
        }
    }

    // MARK: - 分叉会话（用完即弃）

    /// 只有两条消息：system = 打分器提示词，user = 搜索词 + 结果摘要。
    /// 绝不带历史会话、绝不给工具、绝不进 session 表 —— 这轮「对话」不留任何痕迹。
    func forkedMessages(
        persona: PersonaEntity,
        query: String,
        hitsSummary: String
    ) -> [CompletionMessage] {
        let system = ImportanceScoring.searchScoringSystemPrompt(personaSummary: Self.personaSummary(persona))
        let user = """
        他刚才搜的是：\(String(query.prefix(Self.maxQueryChars)))

        搜到的内容：
        \(String(hitsSummary.prefix(Self.maxHitsSummaryChars)))
        """
        return [.system(system), .user(user)]
    }

    /// 只取「他是谁」的几项：打分不需要完整人设。
    static func personaSummary(_ persona: PersonaEntity) -> String {
        var parts: [String] = []
        let name = persona.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !name.isEmpty { parts.append(name) }
        let description = persona.description.trimmingCharacters(in: .whitespacesAndNewlines)
        if !description.isEmpty { parts.append(String(description.prefix(60))) }
        return parts.isEmpty ? "他" : parts.joined(separator: "；")
    }

    /// 抽屉 → 数据库 category（与 Android 的 `categoryOf` 一致）。
    static func category(of drawer: MemoryDrawer) -> MemoryCategory {
        switch drawer {
        case .event: return .event
        case .selfDrawer: return .other
        case .user: return .fact
        }
    }

    /// 已有记忆里跟这条最像的那一行（≥ `dedupeSimilarity` 才算重复）。
    private func duplicateOf(personaId: String, content: String) -> String? {
        let existing = (try? memory.getForPrompt(personaId: personaId, limit: 80)) ?? []
        for row in existing {
            if MemoryScreening.similarity(row.content, content) >= Self.dedupeSimilarity {
                return String(row.content.prefix(20))
            }
        }
        return nil
    }

    private func empty(_ message: String) -> WebSearchScoreResult {
        WebSearchScoreResult(
            scored: false,
            important: false,
            importance: ImportanceScoring.MIN_IMPORTANCE,
            takeaway: "",
            drawer: .user,
            memoryId: nil,
            message: message
        )
    }
}
