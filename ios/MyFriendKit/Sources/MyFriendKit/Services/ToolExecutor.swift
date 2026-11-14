import Foundation

// 来源：app/src/main/java/com/myfriend/app/data/repo/ToolExecutor.kt
//
// 他手里的工具：查资料、读网页、记事、改自己的人设、安排作息、记心情、翻记忆。
//
// ## 这是唯一执行工具的地方
//
// 聊天流程（`ChatService`）只做三件事：发现调用 → 交给这里 → 把结果回灌给模型。
// 每个工具自己决定该不该拒绝（没开联网、时间格式不对、内容太短……），
// 失败也要给模型一句人话（`contentForModel`），不许静默。
//
// ## 与 Android 的对齐点（照 `ToolExecutor.kt` 逐条搬）
//
// 1. **工具名**：`think` / `web_search` / `web_fetch` / `remember` / `update_persona`（= 人设卡补丁，
//    Android 用这个名字；这里额外容忍 `persona_patch` 这个别名）/ `set_quiet_hours`（含 `dnd`）/
//    `set_mood` / `recall` / `defer_reply`；
// 2. **参数夹取**：`importance` 1–5、`count` 1–12（缺省 6）、`depth` 宽松解析（1/2/3、quick/normal/deep、
//    中文别名都认，认不出按「认真回忆」）、`minutes` 5–720、`reason` 截断长度；
// 3. **回灌给模型的文案形状**：成功是「心情已经记下……」「已写进长期记忆……」，失败是
//    「这次调用没成功：……。用你已经知道的东西继续，不要向对方解释工具细节。」；
// 4. **动作卡片的标题**按结果变（「记住了」/「又确认了一次」/「这次先不记」/「想了想，没有改」），
//    不总等于「记住了一件事」。
//
// ## iOS 与 Android 的已知差异（都写在报告里，不是静默空实现）
//
// - `web_search` / `web_fetch` 走 DeepSeek 官方 Search/Fetch 接口（`Net/WebToolsClient.swift`），
//   与 Android 的 `DeepSeekWebTools` 同协议；没开联网 / 没 Key 时明确说「这个人格还没有开启联网」；
// - Android 搜到东西后会另开一次「用完即弃」的打分（`WebSearchScorer`）把值得记的写进长期记忆，
//   iOS 这一步还没接（见报告）——搜索本身不受影响；
// - `persona_revisions` / `persona_pending_patches` 两张表 iOS 没有，所以改人设直接生效、版本号靠
//   `persona.version`（与 1.3 起的 Android 行为一致：不等用户点头）。

public final class ToolExecutor: ToolRunning {

    // MARK: - 依赖

    private let stores: DatabaseStores
    private let apiKeys: ApiKeyStore
    private let memory: MemoryRepository
    private let recallService: RecallService
    private let mood: MoodRepository
    private let personaCards: PersonaCardRepository
    private let intents: AgentIntentSupport
    private let echoes: MemoryEchoStoring
    private let webTools: WebToolsClient
    /// 搜完顺手判断「值不值得记」；没接（或没配置）就是「只搜不记」，搜索本身不受影响。
    private let scorer: WebSearchScoring?
    /// agent 决策层（0.11.0）：工具参数夹取与 `defer_reply` 计划走它。
    /// 默认本机实现；配成远程时失败会回退本机（工具执行不该因为内核服务没起而失败）。
    private let agentCore: any AgentCoreClient
    private let now: () -> Int64
    private let log: (String) -> Void

    /// 联网工具默认地址（与 Android 的 `DEFAULT_DEEPSEEK_BASE` 一致）。
    public static let defaultWebToolsBase = "https://api.deepseek.com"

    public init(
        stores: DatabaseStores,
        apiKeys: ApiKeyStore,
        memory: MemoryRepository,
        recallService: RecallService,
        mood: MoodRepository,
        personaCards: PersonaCardRepository,
        intents: AgentIntentSupport,
        echoes: MemoryEchoStoring? = nil,
        webTools: WebToolsClient = WebToolsClient(),
        scorer: WebSearchScoring? = nil,
        agentCore: any AgentCoreClient = NativeAgentCoreClient(),
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.apiKeys = apiKeys
        self.memory = memory
        self.recallService = recallService
        self.mood = mood
        self.personaCards = personaCards
        self.intents = intents
        self.echoes = echoes ?? MemoryEchoStore(database: stores.database)
        self.webTools = webTools
        self.scorer = scorer
        self.agentCore = agentCore
        self.now = now
        self.log = log
    }

    // MARK: - 总入口

    public func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String
    ) async -> ToolResult {
        let name = invocation.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let at = now()
        switch name {
        case "web_search":
            return await webSearch(invocation, persona: persona, sessionId: sessionId)
        case "web_fetch":
            return await webFetch(invocation, persona: persona, sessionId: sessionId)
        case "remember":
            return await remember(invocation, persona: persona, sessionId: sessionId, now: at)
        case "update_persona", "persona_patch":
            return updatePersona(invocation, persona: persona, sessionId: sessionId, now: at)
        case "set_quiet_hours":
            return setQuietHours(invocation, persona: persona, now: at)
        case "set_mood":
            return setMood(invocation, persona: persona, now: at)
        case "recall":
            return recall(invocation, persona: persona, now: at)
        case "defer_reply":
            return await deferReply(invocation, persona: persona, sessionId: sessionId, now: at)
        case "think":
            return think(invocation)
        default:
            // 不认识就明确说，不假装做过：模型要能知道这条路走不通。
            return failure(invocation, String(format: String(localized: "不认识这个工具：%@"), name))
        }
    }

    // MARK: - 心情

    /// 他自己说「我现在是这种心情」。
    ///
    /// 这不是给他加状态，是让他把心里的变化说出来并留下来：一旦落库，下一次提示词里就有它，
    /// 语气会跟着变。所以这里只做规整，不评判心情合不合理。
    private func setMood(_ invocation: ToolInvocation, persona: PersonaEntity, now: Int64) -> ToolResult {
        let raw = (invocation.argument("mood") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return failure(invocation, String(localized: "没有写是什么心情")) }
        let rawIntensity = invocation.intArgument("intensity", default: 0)
        let intensity = (1...5).contains(rawIntensity) ? rawIntensity : nil
        let reason = String((invocation.argument("reason") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        guard let state = mood.setMood(
            personaId: persona.id, label: raw, intensity: intensity,
            reason: reason, now: now
        ) else {
            return failure(invocation, String(localized: "找不到这个人格"))
        }
        let tail: String
        if MoodLexicon.isNegative(state.label) {
            tail = "接下来会说得慢一点、少一点，但不用解释自己为什么低落。"
        } else if MoodLexicon.isLowEnergy(state.label) {
            tail = "接下来会懒一点、回得短一点，不用硬撑。"
        } else if state.intensity >= 4 {
            tail = "这股劲儿会自然带在语气里。"
        } else {
            tail = "语气里自然带一点就好。"
        }
        var modelText = "心情已经记下：你现在是「\(state.label)」（强度 \(state.intensity)/5）"
        if !state.reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            modelText += "，因为\(state.reason)"
        }
        modelText += "。\(tail)对方不知道你记了心情，也不用主动汇报，接着把话说完就行。"
        return success(
            invocation,
            title: String(localized: "心情变了"),
            subtitle: state.display(),
            detail: state.display(),
            modelText: modelText
        )
    }

    // MARK: - 深思

    /// think 不碰任何外部东西，只是给模型一次「再想一遍」的机会。
    private func think(_ invocation: ToolInvocation) -> ToolResult {
        let question = (invocation.argument("question") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return success(
            invocation,
            title: String(localized: "想了想"),
            subtitle: question.isEmpty ? nil : String(question.prefix(28)),
            detail: question.isEmpty ? String(localized: "（没有写具体问题，就是想清楚再答）") : question,
            modelText: "（可以慢慢想。想清楚以后，直接用你自己的语气把话说完，不用把思考过程念给对方。）"
        )
    }

    // MARK: - 联网

    private func webSearch(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String
    ) async -> ToolResult {
        let query = (invocation.argument("query") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty { return failure(invocation, String(localized: "没有给出要查的内容")) }
        guard let access = webAccess(persona: persona, sessionId: sessionId) else {
            return failure(invocation, String(localized: "这个人格还没有开启联网"))
        }
        // 0.11.0：参数夹取走 agent 决策层（契约端点 `/v1/core/tool-args`），
        // 本机实现里仍然是 `ToolArgClamp.clampCount` 那一句，不复制边界值。
        let clamped: [String: JSONValue]
        do {
            clamped = try await clampedToolArgs(AgentCoreTool.webSearch, invocation: invocation)
        } catch {
            return failure(invocation, String(localized: "决策服务不可用，这次先不做了"))
        }
        let count = AgentCoreJSON.int(clamped["count"]) ?? ToolArgClamp.countDefault
        do {
            let hits = try await webTools.search(
                query: String(query.prefix(128)),
                count: count,
                access: access
            )
            if hits.isEmpty {
                return ToolResult(
                    invocation: invocation,
                    ok: true,
                    summary: String(localized: "没查到什么"),
                    contentForModel: "没有搜到结果，换个说法或换关键词再试。",
                    traceTitle: String(localized: "联网搜索"),
                    traceDetail: String(localized: "没有搜到结果")
                )
            }
            // 搜完顺手判断「值不值得记」：在后台跑，不挡这次工具的结果，也不改它的成败。
            scheduleScoring(persona: persona, sessionId: sessionId, query: query, hits: hits)
            return ToolResult(
                invocation: invocation,
                ok: true,
                summary: String(query.prefix(40)),
                contentForModel: Self.renderHits(hits),
                traceTitle: String(localized: "联网搜索"),
                traceDetail: hits.prefix(4).map { hit in
                    var line = hit.title.isEmpty ? hit.url : hit.title
                    if let site = hit.siteName, !site.isEmpty { line += " · \(site)" }
                    if let date = hit.date, date.count >= 10 { line += " · \(String(date.prefix(10)))" }
                    let body = hit.summary.isEmpty ? hit.snippet : hit.summary
                    if !body.isEmpty { line += "\n" + String(body.prefix(160)) }
                    return line
                }.joined(separator: "\n")
            )
        } catch let webFailure as WebToolsFailure {
            return failure(invocation, webFailure.message)
        } catch {
            return failure(invocation, Self.userFacing(error))
        }
    }

    private func webFetch(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String
    ) async -> ToolResult {
        let url = (invocation.argument("url") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if url.isEmpty { return failure(invocation, String(localized: "没有给出要读的链接")) }
        guard let access = webAccess(persona: persona, sessionId: sessionId) else {
            return failure(invocation, String(localized: "这个人格还没有开启联网"))
        }
        do {
            let page = try await webTools.fetch(url: url, access: access)
            let body = String(page.markdown.prefix(6000))
            let title = page.title.isEmpty ? page.url : page.title
            return ToolResult(
                invocation: invocation,
                ok: true,
                summary: String(title.prefix(40)),
                contentForModel: "《\(title)》\n\(page.url)\n\n\(body)",
                traceTitle: String(localized: "读了网页"),
                traceDetail: String(body.prefix(400))
            )
        } catch let webFailure as WebToolsFailure {
            return failure(invocation, webFailure.message)
        } catch {
            return failure(invocation, Self.userFacing(error))
        }
    }

    /// 只有开了联网、并且拿得到 Key，工具才可用。
    ///
    /// 与 Android 的 `webAccess` 一样先看这一轮绑定的配置，再看人格的默认配置；
    /// 联网工具自己的 Key 优先，没有才复用主 Key。
    private func webAccess(persona: PersonaEntity, sessionId: String) -> WebToolsAccess? {
        let sessionConfigId = (try? stores.sessions.byId(sessionId))??.apiConfigId
        let configId = (sessionConfigId?.isEmpty == false ? sessionConfigId : nil)
            ?? persona.defaultApiConfigId
        guard let configId, let config = (try? stores.apiConfigs.byId(configId)) ?? nil else {
            return nil
        }
        guard config.webToolsEnabled else { return nil }
        let baseUrl = config.webToolsBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? Self.defaultWebToolsBase
            : config.webToolsBaseUrl
        let key = (apiKeys.webToolsAPIKey(for: config.id)?.trimmingCharacters(in: .whitespacesAndNewlines))
            .flatMap { $0.isEmpty ? nil : $0 }
            ?? (apiKeys.apiKey(for: config.id)?.trimmingCharacters(in: .whitespacesAndNewlines))
                .flatMap { $0.isEmpty ? nil : $0 }
        guard let key else { return nil }
        return WebToolsAccess(baseUrl: baseUrl, apiKey: key, allowInsecureHttp: config.allowInsecureHttp)
    }

    /// 搜索打分：把结果压成一段摘要交给打分器，失败 / 没配置都安静跳过。
    ///
    /// 与 Android `WebSearchScorer.scoreInBackground` 一致：工具调用不等它，打分的成败与
    /// 「这次搜索有没有结果」无关。**不抛**任何错误。
    private func scheduleScoring(
        persona: PersonaEntity,
        sessionId: String,
        query: String,
        hits: [WebSearchHit]
    ) {
        guard let scorer, let (config, model) = chatConfig(persona: persona, sessionId: sessionId) else {
            return
        }
        scorer.scoreInBackground(
            personaId: persona.id,
            query: query,
            hitsSummary: Self.scoringSummary(hits),
            config: config,
            model: model
        )
    }

    /// 打分只需要的摘要（一两行一条）：标题 + 链接 + 摘要，最长 2000 字。
    static func scoringSummary(_ hits: [WebSearchHit]) -> String {
        var lines: [String] = []
        for hit in hits.prefix(6) {
            let title = hit.title.isEmpty ? hit.url : hit.title
            var line = "- \(title)\n  \(hit.url)"
            let body = hit.summary.isEmpty ? hit.snippet : hit.summary
            if !body.isEmpty { line += "\n  " + String(body.prefix(240)) }
            lines.append(line)
        }
        return String(lines.joined(separator: "\n").prefix(WebSearchScorer.maxHitsSummaryChars))
    }

    /// 打分用哪份 API 配置：本会话绑定的优先，其次人格默认（与聊天一致的口径）。
    ///
    /// 没 Key 就没有可用的请求，直接返回 nil —— 打分不值得为它报一次错。
    private func chatConfig(persona: PersonaEntity, sessionId: String) -> (CompletionConfig, String)? {
        let sessionConfigId = (try? stores.sessions.byId(sessionId))??.apiConfigId
        let configId = (sessionConfigId?.isEmpty == false ? sessionConfigId : nil)
            ?? persona.defaultApiConfigId
        guard let configId, let config = (try? stores.apiConfigs.byId(configId)) ?? nil else {
            return nil
        }
        let key = (apiKeys.apiKey(for: config.id)?.trimmingCharacters(in: .whitespacesAndNewlines))
            .flatMap { $0.isEmpty ? nil : $0 }
        guard let key else { return nil }
        return (CompletionConfig(entity: config, apiKey: key), config.model)
    }

    /// 回灌给模型的搜索结果。形状与 Android 的 `renderHits` 一致（编号 + 标题 + 链接 + 摘要 + 来源提示）。
    static func renderHits(_ hits: [WebSearchHit]) -> String {
        var out = "搜索结果（按相关度）：\n"
        for (index, hit) in hits.enumerated() {
            out += "\(index + 1). \(hit.title.isEmpty ? hit.url : hit.title)\n"
            out += "   \(hit.url)\n"
            if hit.siteName != nil || hit.date != nil {
                out += "   "
                if let site = hit.siteName { out += site + " · " }
                if let date = hit.date { out += String(date.prefix(10)) + " · " }
                out += "权威度 \(hit.authority)\n"
            }
            let body = hit.summary.isEmpty ? hit.snippet : hit.summary
            if !body.isEmpty { out += "   \(String(body.prefix(500)))\n" }
        }
        out += "\n引用时请带上来源标题与链接；不确定的地方要说明不确定。"
        return out
    }

    // MARK: - 记忆

    private func remember(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Int64
    ) async -> ToolResult {
        let content = (invocation.argument("content") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if content.isEmpty { return failure(invocation, String(localized: "没有给出要记住的内容")) }
        let drawer: MemoryDrawer
        switch (invocation.argument("drawer") ?? "").uppercased() {
        case "SELF": drawer = .selfDrawer
        case "EVENT": drawer = .event
        default: drawer = .user
        }
        // 0.11.0：参数夹取走 agent 决策层（契约端点 `/v1/core/tool-args`）。
        let clamped: [String: JSONValue]
        do {
            clamped = try await clampedToolArgs(AgentCoreTool.remember, invocation: invocation)
        } catch {
            return failure(invocation, String(localized: "决策服务不可用，这次先不做了"))
        }
        let importance = AgentCoreJSON.int(clamped["importance"]) ?? ToolArgClamp.importanceDefault
        let candidate = MemoryCandidate(
            content: content,
            drawer: drawer,
            importance: importance,
            sourceQuote: String(content.prefix(120)),
            categoryName: drawer.rawValue,
            confidence: 0.8
        )
        let existing = memory.getRelevant(personaId: persona.id, limit: 80).map { memory.screenRow($0) }
        let existingEchoes = echoes.byPersona(persona.id).map { echo in
            MemoryCandidate(
                content: echo.content,
                drawer: MemoryDrawer(rawValue: echo.drawer.uppercased())
                    ?? MemoryScreening.drawerOf(echo.categoryName),
                importance: echo.importance,
                sourceQuote: echo.sourceQuote,
                categoryName: echo.categoryName,
                confidence: echo.confidence
            )
        }
        switch MemoryScreening.decide(candidate, existing: existing, echoes: existingEchoes, now: now) {
        case .reject(let reason):
            // 「这次先不记」不是错误：模型该知道的是「这件事不必落库」，然后自然聊过去。
            // 原因有两种说法：`traceDetail` 跟着界面语言走，回灌模型的那句始终用中文原文。
            return ToolResult(
                invocation: invocation,
                ok: true,
                summary: String(localized: "这次先不记"),
                contentForModel: "这件事不必写进长期记忆（\(MemoryScreening.RejectReason.promptText(forLocalized: reason))），自然聊过去就好。",
                traceTitle: String(localized: "想了想要不要记"),
                traceDetail: String(format: String(localized: "这次先不记：%@"), reason)
            )
        case .echo(let kept):
            echoes.insertAll([MemoryEcho(
                id: UUID().uuidString,
                personaId: persona.id,
                drawer: kept.drawer.rawValue,
                content: String(kept.content.prefix(800)),
                importance: kept.importance,
                sourceQuote: kept.sourceQuote,
                categoryName: Self.categoryValue(kept).rawValue,
                confidence: kept.confidence,
                createdAt: now,
                lastSeenAt: now
            )])
            return success(
                invocation,
                title: String(localized: "记下了（还在确认）"),
                subtitle: String(kept.content.prefix(40)),
                detail: String(localized: "第一次听到，先放在心里；再提到一次就会真正记住。"),
                modelText: "已记下：「\(String(kept.content.prefix(80)))」。这还只是印象，不用反复确认。"
            )
        case .promote(let kept, let mergeIntoId):
            let text: String
            if let mergeIntoId,
               let merged = memory.getRelevant(personaId: persona.id, limit: 80).first(where: { $0.id == mergeIntoId }) {
                text = Self.appendTo(merged.content, kept.content)
            } else {
                text = kept.content
            }
            let category = Self.category(of: kept.drawer)
            let written = memory.addDetailed(
                personaId: persona.id,
                content: text,
                category: category,
                importance: kept.importance,
                drawer: kept.drawer.rawValue,
                sourceQuote: kept.sourceQuote,
                sourceSessionId: sessionId
            )
            switch written {
            case .failure(let error):
                log("[tool] remember 落库失败：\(error.localizedDescription)")
                return failure(invocation, String(format: String(localized: "这次没记下来：%@"), Self.userFacing(error)))
            case .success:
                if let mergeIntoId { memory.deleteById(mergeIntoId) }
                // 已经升级成正式记忆：旧回声没有用了，留着只会让 `memory_echoes` 慢慢变大。
                // 与 Android `ToolExecutor` 的 `memoryEchoDao.deleteByPersona(persona.id)` 同语义：
                // 清的是**这个人的**回声，别人格的一律不动；`.reinforce` 分支不清（那条还在「确认中」）。
                echoes.deleteAll(personaId: persona.id)
                return success(
                    invocation,
                    title: String(localized: "记住了"),
                    subtitle: String(text.prefix(40)),
                    detail: String(text.prefix(300)),
                    modelText: "已写进长期记忆：「\(String(text.prefix(120)))」。"
                )
            }
        case .reinforce(let id, let appendedText):
            _ = memory.reinforce(memoryId: id, appendedText: appendedText, importance: candidate.importance)
            return success(
                invocation,
                title: String(localized: "又确认了一次"),
                subtitle: String(candidate.content.prefix(40)),
                detail: appendedText ?? String(localized: "这条记忆更牢了。"),
                modelText: "这条你已经提过，我加固了一下印象。"
            )
        }
    }

    static func appendTo(_ existing: String?, _ addition: String) -> String {
        let base = (existing ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if base.isEmpty { return addition }
        if base.contains(String(addition.prefix(12))) { return base }
        return String("\(base)；\(String(addition.prefix(200)))".prefix(2000))
    }

    static func category(of drawer: MemoryDrawer) -> MemoryCategory {
        switch drawer {
        case .selfDrawer: return .relationship
        case .user: return .fact
        case .event: return .event
        }
    }

    /// 回声落库时要一个 category 名（与抽取那条路一致）。
    static func categoryValue(_ candidate: MemoryCandidate) -> MemoryCategory {
        MemoryCategory(stored: candidate.categoryName.uppercased())
    }

    // MARK: - 回忆

    /// recall：他自己去翻「记忆 / 聊天记录 / 他独自上网看到的」。
    ///
    /// 翻不到不是错误：那意味着「想不太起来」，甚至「可能早就忘了」。
    /// 所以空结果也返回 ok = true，只把不同的说法交给模型，免得它以为工具坏了去反复重试。
    private func recall(_ invocation: ToolInvocation, persona: PersonaEntity, now: Int64) -> ToolResult {
        let query = (invocation.argument("query") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty { return failure(invocation, String(localized: "没说要回忆什么")) }
        // depth 宽松解析：字符串（1/2/3、quick/normal/deep、中文别名）和数字都认，缺省认真回忆。
        let depth: RecallDepth
        if let text = invocation.argument("depth") {
            depth = RecallDepth.parse(text)
        } else {
            depth = RecallDepth.fromLevel(invocation.intArgument("depth", default: RecallDepth.normal.level))
        }
        let outcome = recallService.recall(personaId: persona.id, query: query, depth: depth, now: now)
        let detail: String
        if outcome.isEmpty {
            detail = String(format: String(localized: "（%@）翻了一遍，没有找到"), depth.displayLabel)
        } else {
            detail = outcome.hits.map { hit in
                "（\(Recall.displaySourceLabel(hit, now: now))）\(String(Recall.displayChatText(hit).prefix(80)))"
            }.joined(separator: "\n")
        }
        return success(
            invocation,
            title: String(localized: "回想了一下"),
            subtitle: outcome.isEmpty
                ? String(localized: "翻了翻，没想起什么")
                : PluralText.format("翻了翻记忆 · %d 条", outcome.hits.count),
            detail: String(detail.prefix(600)),
            modelText: outcome.rendered
        )
    }

    // MARK: - 人设

    private func updatePersona(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Int64
    ) -> ToolResult {
        // 模型通常把补丁放在 `patch` 里；也容忍直接把字段平铺在 arguments 上。
        let patchJson: String
        if let object = Self.argumentsObject(invocation.argumentsJson), let patch = object["patch"] {
            patchJson = Self.jsonString(patch)
        } else {
            patchJson = invocation.argumentsJson
        }
        if patchJson.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || patchJson.trimmingCharacters(in: .whitespacesAndNewlines) == "{}" {
            return failure(invocation, String(localized: "没有给出要改的内容"))
        }
        // 旧人格只有一整段提示词：readCard 会临时拆出一张卡，不落库也不报错。
        let card = personaCards.readCard(persona)
        if card.isEmpty {
            return failure(invocation, String(localized: "这个人格还没有人设，先别改"))
        }
        let reason = String((invocation.argument("reason") ?? "").prefix(200))
        switch personaCards.applyPatch(personaId: persona.id, patchJson: patchJson, reason: reason) {
        case .failure(let error):
            return failure(invocation, error.localizedDescription.isEmpty ? String(localized: "这次没改成功") : error.localizedDescription)
        case .success(let changed):
            let summary = PersonaCardPatch.summarize(patchJson)
            if !changed {
                // 本来就是这样：不算错，也别让他一直重试。
                return success(
                    invocation,
                    title: String(localized: "想了想，没有改"),
                    subtitle: String(localized: "这处本来就是这样"),
                    detail: String(localized: "这处设定本来就是你说的样子，不用改。"),
                    modelText: "这处设定你已经是这样了，不用改；也别再重复调用。"
                )
            }
            var detail = summary
            if !reason.isEmpty { detail += "\n" + String(format: String(localized: "原因：%@"), reason) }
            detail += "\n" + String(localized: "已经生效，改动进了版本历史。")
            return success(
                invocation,
                title: String(localized: "调整了自己的人设"),
                subtitle: summary,
                detail: detail,
                modelText: "你已经把「\(summary)」写进自己的人设，从现在开始就按这个来。不用向对方请示，也不必解释这是改动。"
            )
        }
    }

    // MARK: - 作息

    private func setQuietHours(_ invocation: ToolInvocation, persona: PersonaEntity, now: Int64) -> ToolResult {
        guard let sleep = ProactiveProtocol.parseTimeOfDay(invocation.argument("sleep_at")) else {
            return failure(invocation, String(localized: "睡觉时间要写成 HH:mm"))
        }
        guard let wake = ProactiveProtocol.parseTimeOfDay(invocation.argument("wake_at")) else {
            return failure(invocation, String(localized: "起床时间要写成 HH:mm"))
        }
        let reason = String((invocation.argument("reason") ?? "").prefix(160))
        switch personaCards.setQuietHours(
            personaId: persona.id, sleepMinutes: sleep, wakeMinutes: wake, reason: reason
        ) {
        case .failure(let error):
            return failure(invocation, error.localizedDescription.isEmpty ? String(localized: "这次没安排上") : error.localizedDescription)
        case .success:
            break
        }
        // 1.9：睡前免打扰是模型的决定，不是用户的设置；写了就落库，没写就不动。
        let dnd = invocation.boolArgument("dnd")
        if let dnd {
            if case .failure(let error) = personaCards.setDndOnSleep(personaId: persona.id, enabled: dnd) {
                log("[tool] set_dnd 失败：\(error.localizedDescription)")
            }
        }
        // 界面用的一行（跟随界面语言）与提示词用的一行（脚手架保持中文）分开：
        // 否则英文界面下模型会在中文提示词里读到一句英文。
        let dndLine: String
        let dndPromptLine: String
        switch dnd {
        case .some(true):
            dndLine = "\n" + String(localized: "睡前会开免打扰：睡着时不会被消息叫醒。")
            dndPromptLine = "\n睡前会开免打扰：睡着时不会被消息叫醒。"
        case .some(false):
            dndLine = "\n" + String(localized: "睡前不开免打扰：睡着时被连着叫几条会醒。")
            dndPromptLine = "\n睡前不开免打扰：睡着时被连着叫几条会醒。"
        case .none:
            dndLine = ""
            dndPromptLine = ""
        }
        let clock = String(
            format: String(localized: "%@ 睡 · %@ 起"),
            ProactiveProtocol.formatTimeOfDay(sleep),
            ProactiveProtocol.formatTimeOfDay(wake)
        )
        let quietDetail = String(localized: "这段时间他不会主动来打扰。")
        let detail = (reason.isEmpty ? quietDetail : "\(reason)\n" + quietDetail) + dndLine
        return success(
            invocation,
            title: String(localized: "安排了自己的作息"),
            subtitle: clock,
            detail: detail,
            modelText: "作息已记下：\(ProactiveProtocol.formatTimeOfDay(sleep)) 到 \(ProactiveProtocol.formatTimeOfDay(wake)) 是你的休息时间，这段时间不会有主动消息。\(dndPromptLine)"
        )
    }

    // MARK: - 先不回

    /// 这一轮选择不回。
    ///
    /// 三件必须一起成立的事（规格 §四）：不产生助手消息、用户消息已读、留一条 REACH_OUT 念头。
    /// 真正的「不发消息」由 `ChatService` 在拿到 `defer_reply` 时直接拦截；这里照样实现一份，
    /// 保证「任何一处调它，落库语义都一致」。
    private func deferReply(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Int64
    ) async -> ToolResult {
        // 0.11.0：夹取 / trim / 窗口计算走 agent 决策层（`/v1/core/defer-plan`）；
        // 失败时用本机 `DeferReply.plan` 兜底（工具执行不能失败）。
        let minutes = invocation.intArgument("minutes", default: DeferReply.defaultMinutes)
        let reason = invocation.argument("reason")
        let plan: DeferPlan
        do {
            // 0.11.1 审计 P2-1：严格模式失败 → 这条工具**可见地失败**（trace 里能看到原因），
            // 而不是悄悄用本机结果糊过去。普通回退模式照旧（`decide` 内部回退本机）。
            plan = try await agentCore.decide(
                endpoint: "defer-plan",
                nativeFallback: {
                    NativeAgentCoreClient().deferPlanNow(minutes: minutes, reason: reason, now: now)
                }
            ) {
                try await agentCore.deferPlan(minutes: minutes, reason: reason, now: now)
            }
        } catch {
            log("agentcore: defer-plan 严格模式失败（\(error)），这一轮不落「先不回」")
            return failure(
                invocation,
                String(localized: "决策服务不可用，这次先不做了")
            )
        }
        let window = AgentWindow(
            earliestAt: plan.earliestAt,
            latestAt: plan.latestAt,
            note: plan.reason.isEmpty ? "刚才没说话，过一会儿再看看" : plan.reason,
            hint: "到点以后你自己决定要不要开口；不想说也可以继续沉默"
        )
        _ = intents.plan(
            personaId: persona.id,
            window: window,
            kind: .reachOut,
            note: window.note,
            hint: window.hint,
            sessionId: sessionId,
            now: now
        )
        // 他确实看过了，只是选择不回；「已读」是唯一该出现的反应。
        _ = try? stores.messages.markUserMessagesRead(sessionId: sessionId, at: now)
        return success(
            invocation,
            title: String(localized: "决定先不回"),
            subtitle: nil,
            detail: String(
                format: PluralText.template("过 %d 分钟再想这件事%@", plan.minutes),
                plan.minutes,
                plan.reason.isEmpty ? "" : String(format: String(localized: "（%@）"), plan.reason)
            ),
            modelText: "好的，这一轮不回复。不要再输出任何文字，也不要解释；对方会看到「已读」，过了 \(plan.minutes) 分钟你会再想起这件事。"
        )
    }

    // MARK: - 辅助

    /// 走 agent 决策层夹一次工具参数（0.11.0）。
    ///
    /// 参数表原样传过去（`tool-args` 的契约是「只夹已知数值键、其余键原样保留」），
    /// 回来再取要用的那一个数字。远程失败时用本机兜底并记一行日志：
    /// 工具执行失败会让模型以为「这件事做不了」，比慢一个超时更糟。
    private func clampedToolArgs(
        _ tool: String,
        invocation: ToolInvocation
    ) async throws -> [String: JSONValue] {
        let args = AgentCoreJSON.object(fromArgumentsJson: invocation.argumentsJson)
        // 严格模式失败会抛出来，由调用点变成一条可见的工具失败（0.11.1 审计 P2-1）；
        // 普通回退模式由 `decide` 静默用本机夹一次。
        return try await agentCore.decide(
            endpoint: "tool-args",
            nativeFallback: { NativeAgentCoreClient().clampToolArgsNow(tool: tool, args: args) }
        ) {
            try await agentCore.clampToolArgs(tool: tool, args: args)
        }
    }

    private func success(
        _ invocation: ToolInvocation,
        title: String,
        subtitle: String?,
        detail: String,
        modelText: String
    ) -> ToolResult {
        ToolResult(
            invocation: invocation,
            ok: true,
            summary: String((subtitle ?? title).prefix(40)),
            contentForModel: modelText,
            traceTitle: title,
            traceDetail: detail
        )
    }

    private func failure(_ invocation: ToolInvocation, _ message: String) -> ToolResult {
        ToolResult(
            invocation: invocation,
            ok: false,
            summary: message,
            contentForModel: "这次调用没成功：\(message)。用你已经知道的东西继续，不要向对方解释工具细节。",
            errorMessage: message,
            traceTitle: ToolProtocol.describe(ToolResult(
                invocation: invocation, ok: false, summary: message, contentForModel: "", errorMessage: message
            )).title,
            traceDetail: message
        )
    }

    static func userFacing(_ error: Error) -> String {
        if let api = error as? ApiException { return api.message }
        if let failure = error as? CompletionFailure { return failure.message }
        let localized = (error as NSError).localizedDescription
        return localized.isEmpty ? String(localized: "工具调用失败") : localized
    }

    /// 参数 JSON → 对象（坏数据返回 nil，不抛）。
    static func argumentsObject(_ raw: String) -> [String: Any]? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = (text.isEmpty ? "{}" : text).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object
    }

    /// 任意 JSON 值 → 字符串（对象按键名排序，字符串原样）。与 `ToolProtocol.argumentsJson(from:)` 同语义。
    static func jsonString(_ value: Any) -> String {
        if let text = value as? String { return text }
        if JSONSerialization.isValidJSONObject(value),
           let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            return text
        }
        return ""
    }
}
