import Foundation

// ---------------------------------------------------------------------------
// 聊天主链路（只做**无状态兼容模式**）。
//
// 来源：Android `data/repo/ChatRepository.kt` 的
//   sendUserMessage → generateAssistant → saveInterrupted/saveFailed
// 以及它依赖的那几件小事（下一次时间戳、自动标题、未读计数、生成后异步触发的后台任务）。
//
// 刻意**没有**搬过来的部分（见交付报告）：
//   - 图片消息：`AttachmentsCodec`/`MediaStorage` 还没移植，`attachmentsJson` 原样保留但不发给模型；
//     1.9 起「原生 Harness」整组删除（实体、备份、DDL 都不再有那三列），相关分支也就不存在了；
//   - 主动唤醒提案的**落库**（ProactiveProtocol）：正文里的 `myfriend-proactive` 代码块会被剥掉
//     （不能显示给用户），提案 JSON 通过 `ChatCompletion.proactiveProposalJson` 交回给上层；
//   - 通知、前台状态：属于 App 层，用 `chatEvents` 出来的事件驱动。
//
// 保险栓与 Android 一致：模型没吐出任何文字、或者一轮工具跑完仍然没说话，都按失败处理并落库，
// 界面上看到的是「一条失败的回复 + 可以重试」，而不是一个静默消失的气泡。
// ---------------------------------------------------------------------------

/// 这一轮他**选择不回**：不产生助手消息，只留下「已读」或者什么反应都没有。
///
/// 1.9 新增的三种「沉默」都要能被界面认出来（§四、§六）：不是失败、不是还在输入，
/// 而是「这条消息他看到了，但这一次没回」。
public enum ChatNoReplyReason: Equatable, Sendable {
    /// 在睡、开了免打扰：不回复、不唤醒、不留已读。
    case sleepingDoNotDisturb
    /// 在睡、没开免打扰，但被叫醒的连续消息数还不够。
    case sleepingWaiting(wakeAt: String)
    /// 模型用 `defer_reply` 说「现在不想说话」：过 `minutes` 分钟再决定开不开口。
    case deferred(minutes: Int, reason: String)

    /// 界面要不要在他没回的时候显示「已读」。
    public var showsRead: Bool {
        if case .sleepingDoNotDisturb = self { return false }
        return true
    }

    /// 界面**不显示**任何解释性文案（§四）：沉默就是沉默。
    public func describe() -> String { "" }
}

/// 生成过程的对外事件。
///
/// 为什么要流式事件而不是回调闭包：界面要「边流边画」，同时又要能被打断。
/// `AsyncThrowingStream` 天然带取消（用户点停止 → 取消 Task → 流结束）。
public enum ChatEvent: Equatable, Sendable {
    /// 当前**可见**的正文全文（不含协议块）。与 Android 的 `GenerationEvent.Delta` 同义：
    /// 传全文而不是增量，界面直接覆盖最后一条气泡，避免增量拼错。
    case delta(String)
    /// 他开始用一个工具（界面可以在这里显示「正在联网搜索…」）。
    case toolStarted(ToolTraceItem)
    /// 一个工具跑完了（成功或失败都算，失败时 trace.ok = false）。
    case toolFinished(ToolTraceItem)
    /// 这一轮结束：助手消息已落库。
    case done(ChatCompletion)
    /// 这一轮他没有说话（睡着了 / 免打扰 / 主动选择晚点回）。界面上只会看到「已读」。
    case noReply(ChatNoReplyReason)
}

/// 一轮生成的结果。
public struct ChatCompletion: Equatable, Sendable {
    /// 已经落库的助手消息（`status` 为 COMPLETE / STOPPED / ERROR）。
    public var message: MessageEntity
    /// 这一轮用过的工具痕迹（已写进 `message.toolTraceJson`）。
    public var toolTraces: [ToolTraceItem]
    /// 该不该在后台抽取记忆（Android 是 `pendingMemory`：新增消息数到阈值）。
    public var pendingMemory: Bool
    /// 回复里带的主动唤醒提案（已从正文剥掉），上层可据此建规则；没有就是 nil。
    public var proactiveProposalJson: String?

    public init(
        message: MessageEntity,
        toolTraces: [ToolTraceItem] = [],
        pendingMemory: Bool = false,
        proactiveProposalJson: String? = nil
    ) {
        self.message = message
        self.toolTraces = toolTraces
        self.pendingMemory = pendingMemory
        self.proactiveProposalJson = proactiveProposalJson
    }
}

/// 工具执行器。
///
/// `ToolExecutor`（联网搜索、读网页、记住、改人设、设作息、设心情、回忆）是另一批模块的活；
/// 聊天服务只负责「按协议呼出去、把结果回灌」。默认实现老老实实说自己做不了，
/// 这样模型和用户都不会被一个静默空结果骗过去。
public protocol ToolRunning: AnyObject {
    func run(_ invocation: ToolInvocation, persona: PersonaEntity, sessionId: String) async -> ToolResult
}

/// 默认工具执行器：iOS 还没接入任何工具。
public final class UnavailableToolRunner: ToolRunning {
    public init() {}

    public func run(_ invocation: ToolInvocation, persona: PersonaEntity, sessionId: String) async -> ToolResult {
        let name = invocation.name
        return ToolResult(
            invocation: invocation,
            ok: false,
            summary: "这个工具还没接上",
            contentForModel: "工具「\(name)」在当前版本里还用不了。不要假装做过，直接用自己的话回答。",
            errorMessage: "iOS 端还没有接入工具：\(name)"
        )
    }
}

/// 生成结束后要在后台做的事（记忆抽取、历史压缩）。
///
/// 为什么用回调而不是直接依赖 `MemoryExtraction` / `HistoryCompressor`：
/// 这两件事都是「另一条链路」（要模型、要一次完整的后台调用），把它们塞进聊天服务会让
/// 「回一句话」依赖「整理记忆」的实现细节。聊天服务只负责**说一声「该做了」**，
/// 而且是在回复落库之后（不阻塞回复，慢也晚不了一毫秒）。
public protocol ChatBackgroundTasks: AnyObject {
    /// 这一轮聊完了：考虑从这段对话里抽取记忆。
    func memoryExtractionNeeded(sessionId: String, personaId: String)
    /// 这一轮聊完了：考虑把已经聊过去的一段压成滚动摘要。
    func historyCompressionNeeded(sessionId: String)
}

/// 默认空实现：功能没接上时，回复绝不会因此变慢或失败。
public final class NoBackgroundTasks: ChatBackgroundTasks {
    public init() {}
    public func memoryExtractionNeeded(sessionId: String, personaId: String) {}
    public func historyCompressionNeeded(sessionId: String) {}
}

public final class ChatService {

    /// 一次回复里最多连着用几轮工具，用完就必须说话（Android `MAX_TOOL_ROUNDS`）。
    public static let maxToolRounds = 3
    /// 自动记忆的间隔兜底值：人格上没填（老数据）时用这个（§三：`extractionEvery` 默认 6）。
    public static let memoryInterval = PersonaFieldLimits.extractionEveryDefault
    /// 消息长度上限（Android 的 `require(content.length <= 32_000)`）。
    public static let maxMessageLength = 32_000

    private let stores: DatabaseStores
    private let apiKeys: ApiKeyStore
    private let settings: SettingsProviding
    /// 记账在**客户端**那一侧（`OpenAICompatibleClient` 持有 `UsageReporter`），
    /// 与 Android 的 `OpenAiAdapter(reporting)` 完全对应：聊天服务不碰钱，只把 kind 传下去。
    ///
    /// 记忆召回。默认直接用 Services 那份 `MemoryRepository`（候选并集 → 本地重排 → 字数预算），
    /// 测试可以传一个「什么都搜不到」的实例，把注意力集中在对话本身。
    private let memory: MemoryRepository
    private let client: ChatCompleting
    private let tools: ToolRunning
    private let background: ChatBackgroundTasks
    private let now: () -> Int64
    /// 「他此刻在做什么」的来源。`PresenceRepository` 已经落在 Services 里，
    /// 但它还要 `AgentTickStoring` / `AgentIntentSupport` 两个依赖，装配属于 App 层的事；
    /// 这里留一个闭包，App 传 `{ presence.current(personaId: $0).detail }` 即可，nil = 不加这一段。
    private let presenceDetail: ((String) -> String?)?
    /// 心情：直接复用 Services 的 `MoodRepository`（读时顺手让时间冲淡、并落库）。
    private let mood: MoodRepository
    /// 说话节奏：被叫醒后的心情拉回、以及 `defer_reply` 落的念头。nil = 不落（测试常用）。
    private let rhythm: ChatRhythmHandling?
    /// 主动唤醒规则的落库（回复里剥出来的 `myfriend-wake` 提案）。nil = 只把 JSON 交给上层、不落库。
    private let proactive: ProactiveRuleWriting?
    /// 人格文本的来源：人设卡渲染（`PersonaCardRepository.systemText`）就接在这里；
    /// nil = 回落到 `PromptBuilder.personaSection(persona)`（1.9 的「## 你是谁」五小节）。
    private let personaText: ((PersonaEntity) -> String)?
    /// 排查用日志（App 层写进 Documents/debug.log）。nil = 不记。
    private let log: ((String) -> Void)?
    /// 附件文件（沙盒里的图片）：把历史里最近几张图读成 data URL 交给多模态报文。
    /// 默认用 App 的 `Application Support/attachments`；单测可以换成临时目录。
    private let media: MediaStorage

    private let lock = NSLock()
    private var runningGenerations: [String: RunningGeneration] = [:]
    private var openSessionId: String?

    public init(
        stores: DatabaseStores,
        apiKeys: ApiKeyStore,
        settings: SettingsProviding,
        client: ChatCompleting,
        memory: MemoryRepository? = nil,
        tools: ToolRunning = UnavailableToolRunner(),
        background: ChatBackgroundTasks = NoBackgroundTasks(),
        presenceDetail: ((String) -> String?)? = nil,
        mood: MoodRepository? = nil,
        rhythm: ChatRhythmHandling? = nil,
        proactive: ProactiveRuleWriting? = nil,
        personaText: ((PersonaEntity) -> String)? = nil,
        log: ((String) -> Void)? = nil,
        media: MediaStorage = .shared,
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.stores = stores
        self.apiKeys = apiKeys
        self.settings = settings
        self.client = client
        self.memory = memory ?? MemoryRepository(stores: stores)
        self.tools = tools
        self.background = background
        self.presenceDetail = presenceDetail
        self.mood = mood ?? MoodRepository(stores: stores, now: now)
        self.rhythm = rhythm
        self.proactive = proactive
        self.personaText = personaText
        self.log = log
        self.media = media
        self.now = now
    }

    // MARK: - 会话打开状态（未读计数用）

    public func setOpenSession(_ sessionId: String?) {
        lock.lock()
        openSessionId = sessionId
        lock.unlock()
    }

    public func isSessionOpen(_ sessionId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return openSessionId == sessionId
    }

    /// 有没有一轮生成正在跑（界面用来决定「发送」按钮是箭头还是方块）。
    public func isGenerating(sessionId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return runningGenerations[sessionId] != nil
    }

    // MARK: - 发送 / 重新生成 / 停止

    /// 发一条用户消息，然后开始生成回复。
    ///
    /// 顺序与 Android 一致：**先把用户消息落库**，再生成。这样即使网络立刻失败，
    /// 用户那句「我说的」也还在，历史不会缺一块。
    ///
    /// 参数/配置类错误（没选 API、没填 Key、消息为空）会在流里以 `ApiException` 抛出，
    /// 且**不会**留下半截消息。
    ///
    /// 图片：`attachments` 是已经压好、存进沙盒的附件（`MediaStorage.saveImage` 的产物），
    /// 落库时写进 `messages.attachmentsJson`。正文可以为空——只发一张图也是一句话。
    public func send(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment] = []
    ) -> AsyncThrowingStream<ChatEvent, Error> {
        do {
            try appendUserMessage(sessionId: sessionId, text: text, attachments: attachments)
        } catch {
            return Self.failedStream(error)
        }
        return generate(sessionId: sessionId)
    }

    /// 重新生成：删掉最后一条助手消息（如果有），然后重新跑一轮。
    ///
    /// 与 Android `prepareRegeneration` 一致：只删「最后一条是助手消息」的那一条，
    /// 没有可回复的用户消息就直接报错。
    public func regenerate(sessionId: String) -> AsyncThrowingStream<ChatEvent, Error> {
        do {
            guard try stores.sessions.byId(sessionId) != nil else {
                throw ApiException(statusCode: nil, message: "会话不存在")
            }
            if let latest = try stores.messages.latest(sessionId: sessionId), latest.role == .assistant {
                try stores.messages.delete(id: latest.id)
                try refreshPreview(sessionId: sessionId)
            }
            let hasUser = try stores.messages
                .fetch(where: "sessionId = ? AND role = ?", [.text(sessionId), .text(MessageRole.user.stored)],
                       orderBy: "createdAt DESC", limit: 1)
                .isEmpty == false
            guard hasUser else {
                throw ApiException(statusCode: nil, message: "没有可以重新生成的消息")
            }
        } catch {
            return Self.failedStream(error)
        }
        return generate(sessionId: sessionId)
    }

    /// 停止当前这一轮。
    ///
    /// 已经吐出来的正文会被落成一条 `STOPPED` 消息（与 Android 的 `saveInterrupted` 一致）——
    /// 用户看到的是「他话说了一半」，而不是清空。
    public func stop(sessionId: String) {
        runningTask(sessionId)?.cancel()
    }

    // MARK: - 运行中任务表（锁只在这几个同步小函数里碰）

    /// 一轮正在跑的生成。用一个 class 当把手，是为了让「注册」可以发生在 Task 启动**之前**：
    /// 否则一轮极快的生成可能在注册之前就结束了，留下一个永远「正在生成」的假状态。
    private final class RunningGeneration {
        var task: Task<Void, Never>?
    }

    private func registerGeneration(_ generation: RunningGeneration, sessionId: String) {
        lock.lock()
        runningGenerations[sessionId] = generation
        lock.unlock()
    }

    private func attach(_ task: Task<Void, Never>, to generation: RunningGeneration) {
        lock.lock()
        generation.task = task
        lock.unlock()
    }

    /// 只有「还是这一轮」时才清掉；否则会误删下一轮的把手。
    private func clearGeneration(_ generation: RunningGeneration, sessionId: String) {
        lock.lock()
        if runningGenerations[sessionId] === generation {
            runningGenerations[sessionId] = nil
        }
        lock.unlock()
    }

    private func runningTask(_ sessionId: String) -> Task<Void, Never>? {
        lock.lock()
        defer { lock.unlock() }
        return runningGenerations[sessionId]?.task
    }

    // MARK: - 用户消息落库

    private func appendUserMessage(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment] = []
    ) throws {
        let content = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // 与 Android 一致：正文可以为空，只要至少有图。
        let images = attachments.filter { $0.isImage }.prefix(AttachmentsCodec.maxImagesPerMessage)
        guard !content.isEmpty || !images.isEmpty else {
            throw ApiException(statusCode: nil, message: "消息不能为空")
        }
        guard content.count <= Self.maxMessageLength else {
            throw ApiException(statusCode: nil, message: "消息过长")
        }
        guard let session = try stores.sessions.byId(sessionId) else {
            throw ApiException(statusCode: nil, message: "会话不存在")
        }
        guard let configId = session.apiConfigId, !configId.isEmpty else {
            throw ApiException(statusCode: nil, message: "请先为该会话选择 API 配置")
        }
        guard try stores.apiConfigs.byId(configId) != nil else {
            throw ApiException(statusCode: nil, message: "API 配置已不存在")
        }
        let persona = try stores.personas.byId(session.personaId)
        let timestamp = try nextTimestamp(sessionId: sessionId)
        let isFirstUserMessage = try stores.messages.count(
            where: "sessionId = ? AND role = ?", [.text(sessionId), .text(MessageRole.user.stored)]
        ) == 0
        let message = MessageEntity(
            id: UUID().uuidString,
            sessionId: sessionId,
            personaId: session.personaId,
            role: .user,
            content: content,
            createdAt: timestamp,
            updatedAt: timestamp,
            status: .complete,
            model: nil,
            edited: false,
            attachmentsJson: AttachmentsCodec.encode(Array(images))
        )
        // 首页那一行预览：只有图的时候写「图片」，与 Android 的 `ifBlank { "图片" }` 一致。
        let flattened = content.replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let preview = flattened.isEmpty ? "图片" : flattened
        try stores.database.inTransaction {
            try stores.messages.upsert(message)
            var updated = session
            updated.lastMessagePreview = String(preview.prefix(90))
            updated.lastMessageAt = timestamp
            updated.unreadCount = 0
            updated.updatedAt = timestamp
            if isFirstUserMessage {
                updated.title = Self.autoTitle(content.isEmpty ? "图片" : content)
            }
            _ = persona
            try stores.sessions.upsert(updated)
        }
    }

    // MARK: - 生成

    private func generate(sessionId: String) -> AsyncThrowingStream<ChatEvent, Error> {
        AsyncThrowingStream { continuation in
            // 同一个会话只允许一轮在跑：再点一次发送不该让两条流互相踩。
            runningTask(sessionId)?.cancel()

            let generation = RunningGeneration()
            registerGeneration(generation, sessionId: sessionId)
            let task = Task { [weak self] in
                guard let self else {
                    continuation.finish()
                    return
                }
                await self.runGeneration(sessionId: sessionId, continuation: continuation)
                self.clearGeneration(generation, sessionId: sessionId)
            }
            attach(task, to: generation)
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runGeneration(
        sessionId: String,
        continuation: AsyncThrowingStream<ChatEvent, Error>.Continuation
    ) async {
        var shownText = ""
        var traces: [ToolTraceItem] = []
        var completed = false
        var personaId = ""
        var model = ""
        do {
            let resolved = try resolveSession(sessionId: sessionId)
            let session = resolved.session
            let persona = resolved.persona
            let config = resolved.config
            personaId = persona.id
            model = session.model.isEmpty ? config.model : session.model

            let all = try stores.messages.bySession(sessionId)
            let recent = Array(all.suffix(PromptBuilder.contextRecentLimit))
            guard recent.contains(where: { $0.role == .user }) else {
                throw ApiException(statusCode: nil, message: "还没有可以回复的消息")
            }

            // 睡眠/免打扰/唤醒阈值（§六）。判定放在「模型看到消息」之后、真正生成之前：
            // 这一步决定这一轮到底要不要说话，以及要不要留「已读」。
            let sleepTimestamp = now()
            let recentUserCount = (try? stores.messages.countUserMessages(
                personaId: persona.id, since: sleepTimestamp - SleepSchedule.wakeWindowMillis
            )) ?? 1
            let sleepDecision = SleepGate.decide(
                persona: persona, recentUserMessages: recentUserCount, now: sleepTimestamp
            )
            guard sleepDecision.shouldReply else {
                if sleepDecision.marksRead {
                    // 看到了就是已读 —— 哪怕这一轮不回。免打扰是唯一的例外（§6.2）。
                    _ = try? stores.messages.markUserMessagesRead(sessionId: sessionId, at: sleepTimestamp)
                }
                let reason: ChatNoReplyReason
                switch sleepDecision {
                case .sleepingDoNotDisturb:
                    reason = .sleepingDoNotDisturb
                default:
                    reason = .sleepingWaiting(wakeAt: SleepSchedule.wakeClockText(persona))
                }
                continuation.yield(.noReply(reason))
                continuation.finish()
                return
            }
            if sleepDecision == .wokenUp {
                // 被叫醒了：心情拉回 + 留一句「本来在睡」（界面不提示这件事）。
                rhythm?.handleWokenUp(personaId: persona.id, at: sleepTimestamp)
            }

            // 模型真的会读到这些消息了：把用户消息标成已读（§2.3）。
            _ = try? stores.messages.markUserMessagesRead(sessionId: sessionId, at: now())

            let mediaStorage = media
            let history = PromptBuilder.history(
                messages: recent,
                summarizedUpTo: session.historySummarizedUpTo,
                // 只有最近两条带图消息会真的把图交给模型（见 `AttachmentsCodec`）。
                imageResolver: { attachment in mediaStorage.dataURL(for: attachment) }
            )
            // 用最近一句对方说的话当检索线索，挑「此刻最相关」的记忆。
            // 1.9 起记忆**内置开启**：没有开关可关（§一）。
            let recallQuery = String(
                (recent.last { $0.role == .user }?.content ?? "").prefix(300)
            )
            let memories: [MemoryEntity] = memory.getRelevant(
                personaId: persona.id, query: recallQuery
            )
            let memoriesBlock = memory.formatForPrompt(memories)

            let timestamp = now()
            // 心情只读一次：提示词那一段与「他今天话多话少」都由这一份状态换算出来。
            // `MoodRepository` 会顺手做两件事（与 Android 一致）：让时间冲淡、把「已经问过心情」记下来。
            let moodState = mood.current(personaId: persona.id, now: timestamp)
            let nudge = mood.reflectionNudge(personaId: persona.id, now: timestamp)

            let system = PromptBuilder.systemPrompt(SystemPromptInput(
                personaText: personaText?(persona) ?? PromptBuilder.personaSection(persona),
                memoriesBlock: memoriesBlock,
                earlierMemoryBlock: HistoryCompression.memoryBlock(
                    summary: session.historySummary, summarizedUpTo: session.historySummarizedUpTo
                ),
                moodBlock: MoodPrompt.line(moodState),
                moodPaceBlock: MoodBehavior.promptLine(moodState, now: timestamp),
                presenceBlock: PromptBuilder.presenceBlock(detail: presenceDetail?(persona.id)),
                moodNudgeBlock: nudge ?? "",
                proactiveCapability: ProactiveCapabilityPrompt.text,
                toolsCapability: ToolCatalog.capabilityPrompt(webEnabled: config.webToolsEnabled)
            ))

            var conversation = PromptBuilder.wireMessages(system: system, history: history)
            var round = 0
            var executed = Set<String>()
            var finalBody = ""

            while true {
                var buffer = ""
                let stream = client.streamChat(
                    config: config,
                    apiKey: resolved.apiKey,
                    model: model,
                    messages: conversation,
                    params: config.inferenceParams(),
                    extraHeaders: [:],
                    kind: UsageKind.chat,
                    usageContext: UsageContext(personaId: persona.id, sessionId: sessionId)
                )
                for try await delta in stream {
                    if Task.isCancelled { throw CancellationError() }
                    buffer += delta
                    // 只把「还没写到的工具块」之前的部分推到界面，协议块永不示人。
                    let visible = ToolProtocol.visiblePrefix(buffer)
                    if visible != shownText {
                        shownText = visible
                        continuation.yield(.delta(visible))
                    }
                }
                if Task.isCancelled { throw CancellationError() }

                let (body, calls) = ToolProtocol.extract(buffer)

                // `defer_reply`（§四）：这一轮他选择不说话。
                //
                // 必须在执行其它工具之前拦下来：他要是已经决定不回，就不该再顺手记一笔、
                // 查一次网页，更不该把半句话落成一条助手消息。用户消息此时已经是已读。
                if let deferred = calls.first(where: {
                    $0.name.trimmingCharacters(in: .whitespacesAndNewlines) == ToolCatalog.deferReply
                }) {
                    let minutes = deferred.intArgument("minutes", default: ChatRhythmService.defaultDeferMinutes)
                    let reason = String(
                        (deferred.argument("reason") ?? "")
                            .trimmingCharacters(in: .whitespacesAndNewlines)
                            .prefix(ChatRhythmService.deferReasonMax)
                    )
                    let clamped = min(max(minutes, ChatRhythmService.deferMinutesRange.lowerBound),
                                      ChatRhythmService.deferMinutesRange.upperBound)
                    rhythm?.handleDeferredReply(
                        personaId: persona.id, sessionId: sessionId,
                        minutes: clamped, reason: reason, at: now()
                    )
                    continuation.yield(.noReply(.deferred(minutes: clamped, reason: reason)))
                    continuation.finish()
                    return
                }

                if calls.isEmpty || round >= Self.maxToolRounds {
                    finalBody = body.isEmpty ? (calls.isEmpty ? buffer.trimmingCharacters(in: .whitespacesAndNewlines) : "") : body
                    break
                }
                round += 1
                var results: [ToolResult] = []
                for call in calls {
                    if Task.isCancelled { throw CancellationError() }
                    let fingerprint = call.name.trimmingCharacters(in: .whitespacesAndNewlines)
                        + "|" + call.argumentsJson.trimmingCharacters(in: .whitespacesAndNewlines)
                    if executed.contains(fingerprint) {
                        // 同一个调用这一轮已经做过：别再执行一次，只告诉它「刚做过」，
                        // 否则模型会陷进「查了又查」的循环。
                        results.append(ToolResult(
                            invocation: call,
                            ok: true,
                            summary: "刚刚已经做过",
                            contentForModel: "这个调用你刚刚已经做过了，结果就在上面。直接用那些结果把话说完。"
                        ))
                        continue
                    }
                    executed.insert(fingerprint)
                    let result = await tools.run(call, persona: persona, sessionId: sessionId)
                    let trace = ToolTraceItem.from(result)
                    traces.append(trace)
                    continuation.yield(.toolStarted(ToolTraceItem(
                        name: trace.name, title: trace.title, subtitle: nil, detail: "", ok: true
                    )))
                    continuation.yield(.toolFinished(trace))
                    results.append(result)
                }
                // 过程话不算数：先把气泡里的临时文字清干净，等最后那一轮的真话。
                shownText = ""
                continuation.yield(.delta(""))
                conversation.append(WireMessage(role: "assistant", content: buffer.trimmingCharacters(in: .whitespacesAndNewlines)))
                conversation.append(WireMessage(
                    role: "user",
                    content: ToolProtocol.renderResultsForModel(results)
                        + "\n\n（以上是你自己刚做完的事，对方看不见细节。现在接着把话说完，别重复，也别提工具。）"
                ))
            }

            let rawFinal = finalBody.trimmingCharacters(in: .whitespacesAndNewlines)
            // 主动唤醒提案（`myfriend-wake`）：代码块不能给用户看，解析成规则交回上层并落库。
            let extracted = ProactiveProtocol.extract(rawFinal)
            let finalText = extracted.body
            let proposal = extracted.proposal
            let proposalJson = extracted.payloadJson
            if finalText.isEmpty && proposalJson == nil {
                throw ApiException(
                    statusCode: nil,
                    message: traces.isEmpty ? "模型没有返回任何文字" : "他查完了东西，怕是走神了，再发一条试试"
                )
            }
            // 最终正文可能和流里那一份不一样（去掉代码块、trim）：补一次，界面才对得上库里的内容。
            if finalText != shownText {
                shownText = finalText
                continuation.yield(.delta(finalText))
            }

            guard let saved = try saveAssistant(
                sessionId: sessionId,
                personaId: persona.id,
                content: finalText,
                status: .complete,
                errorMessage: nil,
                model: model,
                traces: traces
            ) else {
                throw ApiException(statusCode: nil, message: "回复没有保存成功，请重试")
            }
            completed = true

            let count = try stores.messages.count(sessionId: sessionId)
            let freshSession = try stores.sessions.byId(sessionId)
            // 抽记忆的频率现在归人格（`extractionEvery`，会话可覆盖，§三）：
            // 记性好、话多的人可以抽得勤一点，不用再让用户在设置里猜这个数字。
            let extractionEvery = (freshSession ?? session).effectiveExtractionEvery(persona: persona)
            let pendingMemory = count - (freshSession?.lastMemoryExtractedCount ?? 0) >= extractionEvery

            // 主动唤醒规则：他自己安排的节奏**直接生效**（与 Android 一致，不等用户点头）。
            // 落库失败不影响这一轮回复，但一定要留一行日志——不静默。
            if let proposal {
                persistProactiveProposal(
                    proposal: proposal, personaId: persona.id, sessionId: sessionId
                )
            }

            // 回复已经落库了，两件后台的事各自异步跑：慢也晚不了一毫秒回话，失败也当没发生。
            background.historyCompressionNeeded(sessionId: sessionId)
            if pendingMemory {
                background.memoryExtractionNeeded(sessionId: sessionId, personaId: persona.id)
            }

            continuation.yield(.done(ChatCompletion(
                message: saved,
                toolTraces: traces,
                pendingMemory: pendingMemory,
                proactiveProposalJson: proposalJson
            )))
            continuation.finish()
        } catch is CancellationError {
            // 用户点了停止：把已经说出来的那半句落库，流正常结束（界面看消息状态就知道是停的）。
            if !completed {
                _ = try? saveAssistant(
                    sessionId: sessionId,
                    personaId: personaId,
                    content: shownText,
                    status: .stopped,
                    errorMessage: nil,
                    model: model,
                    traces: traces,
                    allowEmpty: true
                )
            }
            continuation.finish()
        } catch {
            // 取消有时会以 `URLError.cancelled` 的形式冒出来：仍然按「用户停止」处理，
            // 否则界面会把一次主动停止显示成「网络连接中断」。
            if Task.isCancelled {
                if !completed {
                    _ = try? saveAssistant(
                        sessionId: sessionId,
                        personaId: personaId,
                        content: shownText,
                        status: .stopped,
                        errorMessage: nil,
                        model: model,
                        traces: traces,
                        allowEmpty: true
                    )
                }
                continuation.finish()
                return
            }
            let message: String
            if let api = error as? ApiException {
                message = api.message
            } else {
                let localized = (error as NSError).localizedDescription
                message = localized.isEmpty ? "生成失败" : localized
            }
            var saved: MessageEntity?
            if !completed {
                saved = try? saveAssistant(
                    sessionId: sessionId,
                    personaId: personaId,
                    content: shownText,
                    status: .error,
                    errorMessage: String(message.prefix(800)),
                    model: model,
                    traces: traces,
                    allowEmpty: true
                )
            }
            if let saved {
                continuation.yield(.done(ChatCompletion(
                    message: saved, toolTraces: traces, pendingMemory: false, proactiveProposalJson: nil
                )))
            }
            continuation.finish(throwing: error)
        }
    }

    // MARK: - 解决配置 / Key

    private struct ResolvedSession {
        var session: ChatSessionEntity
        var persona: PersonaEntity
        var config: ApiConfigEntity
        var apiKey: String?
    }

    private func resolveSession(sessionId: String) throws -> ResolvedSession {
        guard let session = try stores.sessions.byId(sessionId) else {
            throw ApiException(statusCode: nil, message: "会话不存在")
        }
        guard let persona = try stores.personas.byId(session.personaId) else {
            throw ApiException(statusCode: nil, message: "人格不存在")
        }
        guard let configId = session.apiConfigId, !configId.isEmpty else {
            throw ApiException(statusCode: nil, message: "请先为该会话选择 API 配置")
        }
        guard let config = try stores.apiConfigs.byId(configId) else {
            throw ApiException(statusCode: nil, message: "API 配置已不存在")
        }
        let key = apiKeys.apiKey(for: config.id)
        // 没有 Key 也不让请求白跑一趟：Android 会照发然后拿 401，
        // 但用户看到的同样是一句「API Key 无效」，不如在本地就把话说清楚（少一次往返、少一次计费）。
        let trimmedKey = key?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if trimmedKey.isEmpty && !Self.hasCustomAuthorization(config) {
            throw ApiException(statusCode: nil, message: "还没有填 API Key：请在设置里为这个 API 配置补上密钥")
        }
        return ResolvedSession(
            session: session,
            persona: persona,
            config: config,
            apiKey: trimmedKey.isEmpty ? nil : trimmedKey
        )
    }

    /// 自定义 Header 里带了 Authorization 时，主 Key 可以为空（与 Android 的 header 优先级一致）。
    static func hasCustomAuthorization(_ config: ApiConfigEntity) -> Bool {
        guard let data = config.customHeadersJson.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return false
        }
        return object.keys.contains { $0.caseInsensitiveCompare("Authorization") == .orderedSame }
    }

    // MARK: - 落库

    @discardableResult
    private func saveAssistant(
        sessionId: String,
        personaId: String,
        content: String,
        status: MessageStatus,
        errorMessage: String?,
        model: String,
        traces: [ToolTraceItem],
        allowEmpty: Bool = false
    ) throws -> MessageEntity? {
        if content.isEmpty && !allowEmpty { return nil }
        let timestamp = try nextTimestamp(sessionId: sessionId)
        let message = MessageEntity(
            id: UUID().uuidString,
            sessionId: sessionId,
            personaId: personaId,
            role: .assistant,
            content: content,
            createdAt: timestamp,
            updatedAt: timestamp,
            status: status,
            errorMessage: errorMessage,
            model: model,
            edited: false,
            toolTraceJson: ToolTraceCodec.encode(traces)
        )
        try stores.database.inTransaction {
            try stores.messages.upsert(message)
            guard var session = try stores.sessions.byId(sessionId) else { return }
            let preview = String(content.replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
            session.lastMessagePreview = preview.isEmpty
                ? (status == .error ? "回复失败" : nil)
                : preview
            session.lastMessageAt = timestamp
            session.updatedAt = timestamp
            if isSessionOpen(sessionId) {
                session.unreadCount = 0
            } else {
                session.unreadCount += 1
            }
            try stores.sessions.upsert(session)
        }
        return message
    }

    private func refreshPreview(sessionId: String) throws {
        guard var session = try stores.sessions.byId(sessionId) else { return }
        let latest = try stores.messages.latest(sessionId: sessionId)
        let preview = String((latest?.content ?? "").replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
        session.lastMessagePreview = preview.isEmpty
            ? (latest?.status == .error ? "回复失败" : nil)
            : preview
        session.lastMessageAt = latest?.createdAt
        session.updatedAt = now()
        try stores.sessions.upsert(session)
    }

    /// 消息时间戳严格递增：同一毫秒里连发两条时，历史顺序才不会乱。
    private func nextTimestamp(sessionId: String) throws -> Int64 {
        let latest = try stores.messages.latest(sessionId: sessionId)
        let timestamp = now()
        if let latest, timestamp <= latest.createdAt { return latest.createdAt + 1 }
        return timestamp
    }

    static func autoTitle(_ content: String) -> String {
        let flattened = content
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if flattened.count <= 26 { return flattened }
        return String(flattened.prefix(26)) + "…"
    }

    // MARK: - 主动唤醒提案

    /// 把回复里的主动唤醒代码块剥掉，正文交回界面，提案 JSON 交回上层。
    ///
    /// 真正干活的是 `ProactiveProtocol.extract`（两种语言标记都认：新的 `myfriend-wake`
    /// 与早期 iOS 用的 `myfriend-proactive`）。这里保留一个同名入口，是为了让已有调用点与测试
    /// 不必关心协议细节。
    static func extractProactive(_ rawText: String) -> (text: String, proposalJson: String?) {
        let extracted = ProactiveProtocol.extract(rawText)
        return (extracted.body, extracted.payloadJson)
    }

    /// 把提案写成一条 `proactive_rules`（同一节奏已有就更新，不堆重复）。
    private func persistProactiveProposal(
        proposal: ProactiveProposal,
        personaId: String,
        sessionId: String
    ) {
        guard let proactive else {
            log?("[proactive] 收到提案，但这一层没有接规则落库：\(proposal.kind.stored)")
            return
        }
        switch proactive.createFromProposal(
            personaId: personaId, sessionId: sessionId, proposal: proposal, at: now()
        ) {
        case .success(let id):
            log?("[proactive] 规则已落库 \(id)（\(proposal.kind.stored)）")
        case .failure(let error):
            log?("[proactive] 规则没落库：\(error.localizedDescription)")
        }
    }

    // MARK: - 失败流

    /// 还没开始生成就失败（参数不对）：给一条只带错误的流，界面按同样的方式处理。
    static func failedStream(_ error: Error) -> AsyncThrowingStream<ChatEvent, Error> {
        AsyncThrowingStream { continuation in
            continuation.finish(throwing: error)
        }
    }
}
