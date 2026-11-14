import Foundation

/// 应用装配：把 MyFriendKit 里的仓储、服务、客户端接到一起。
///
/// 为什么集中在一个 `@MainActor final class` 里而不是散在各个 View：
/// - 依赖图是有顺序的（数据库 → 仓储 → 服务 → 聊天），散着装配最容易出现「某个 View 里 new 了一个
///   独立的 MemoryRepository」，于是两个实例各自缓存、行为不一致；
/// - 只有一处能拿到 API Key（Keychain），也只有一处知道「现在是哪个 API 配置」；
/// - 测试与预览可以直接注入假的 `ChatService`。
///
/// 这个文件只做装配，不写业务逻辑：任何「怎么回一句话」「怎么挑记忆」都在 MyFriendKit 里。
@MainActor
final class AppEnvironment: ObservableObject {

    // MARK: - 存储

    let stores: DatabaseStores
    let settings: SettingsStore
    let apiKeys: ApiKeyStore

    // MARK: - 网络与记账

    let webClient: OpenAICompatibleClient
    let usageRecorder: UsageRecorder
    /// 全局 LLM 并发闸（后台 2 + 全局 3，聊天保底 1）。界面不需要它，
    /// 但装配层持有它才有地方接超时日志。
    let gate: LlmConcurrencyGate
    /// 他说不说话的那些节奏（被叫醒、`defer_reply`）。
    let rhythm: ChatRhythmService

    // MARK: - 服务

    let memoryRepository: MemoryRepository
    let vectorIndex: MemoryVectorIndex
    let moodRepository: MoodRepository
    let tickStore: AgentTickStore
    let intents: AgentIntentSupport
    let presence: PresenceRepository
    let extraction: MemoryExtraction
    let consolidator: MemoryConsolidator
    let recall: RecallService
    let agentLoop: AgentLoop
    let heartbeat: HeartbeatController
    let backup: BackupService
    let dataReset: DataReset
    let chat: ChatService
    /// 人设卡：结构化编辑 + `update_persona` 工具 + 提示词渲染都走它。
    let personaCards: PersonaCardRepository
    /// 联网工具（DeepSeek Search / Fetch）。`web_search` / `web_fetch` 用得到。
    let webTools: WebToolsClient
    /// 聊天侧工具执行器（remember / recall / set_mood / set_quiet_hours / update_persona / web_*）。
    let toolExecutor: ToolExecutor
    /// 主动唤醒规则落库（回复里的 `myfriend-wake` 提案）。
    let proactiveRules: ProactiveRuleService
    /// 历史压缩（滚动摘要）的落库编排。
    let historyCompression: HistoryCompressionService
    /// 回复落库之后那两件后台事（抽记忆 / 压摘要）。
    let chatBackground: ChatBackgroundService

    /// 设置变了要通知界面重绘；View 用 `@EnvironmentObject` 读它。
    @Published private(set) var currentSettings: AppSettings

    /// 启动失败时留给根视图显示（数据库打不开、没有可写目录……）。
    let bootError: String?

    // MARK: - 构造

    init() {
        var bootError: String?
        var stores: DatabaseStores?
        do {
            stores = try DatabaseStores.open(path: Self.databasePath())
        } catch {
            bootError = "打不开本地数据库：\(error.localizedDescription)"
        }

        // 数据库都打不开时，整个 App 仍然要能显示一句话而不是闪退。
        // 这些「空壳」对象只是为了让属性初始化器有值；`bootError != nil` 时根视图不会用它们。
        let fallbackStores = stores ?? (try! DatabaseStores.open(path: ":memory:"))
        self.stores = fallbackStores
        self.bootError = bootError

        let settings = SettingsStore()
        self.settings = settings
        // 未签名的模拟器构建拿不到 Keychain entitlement；这里优先真 Keychain，失败才调试兜底。
        let apiKeys = ApiKeyStore(backend: ResilientKeychain())
        self.apiKeys = apiKeys
        let loadedSettings = settings.load()
        self.currentSettings = loadedSettings

        let recorder = UsageRecorder(stores: fallbackStores)
        self.usageRecorder = recorder

        // 1.9：预算闸、额度、拦截全部删除（§五）。请求前唯一要过的关是并发闸。
        let gate = LlmConcurrencyGate.shared
        let webClient = OpenAICompatibleClient(
            session: URLSession(configuration: Self.sessionConfiguration()),
            usage: recorder,
            gate: gate
        )
        self.gate = gate
        self.webClient = webClient

        let vectorIndex = MemoryVectorIndex(stores: fallbackStores)
        let memoryRepository = MemoryRepository(
            stores: fallbackStores,
            vectorIndex: vectorIndex
        )
        let moodRepository = MoodRepository(stores: fallbackStores)
        let tickStore = AgentTickStore(database: fallbackStores.database)
        let intents = AgentIntentSupport(stores: fallbackStores, ticks: tickStore)
        let presence = PresenceRepository(stores: fallbackStores, ticks: tickStore, intents: intents)
        // 适配器只负责搬参数/翻错误；Key 与记账都在 `OpenAICompatibleClient` 那一侧。
        let completion = CompletionClientAdapter(client: webClient)

        self.vectorIndex = vectorIndex
        self.memoryRepository = memoryRepository
        self.moodRepository = moodRepository
        self.tickStore = tickStore
        self.intents = intents
        self.presence = presence

        let extraction = MemoryExtraction(
            stores: fallbackStores,
            memoryRepository: memoryRepository,
            completion: completion
        )
        let consolidator = MemoryConsolidator(
            stores: fallbackStores,
            memoryRepository: memoryRepository,
            completion: completion
        )
        let recall = RecallService(
            stores: fallbackStores,
            memoryRepository: memoryRepository
        )
        self.extraction = extraction
        self.consolidator = consolidator
        self.recall = recall

        let rhythm = ChatRhythmService(
            stores: fallbackStores,
            mood: moodRepository,
            ticks: tickStore,
            intents: intents
        )
        self.rhythm = rhythm

        // `AgentLoop` 的「主动消息送达」回调要问一句「这段会话现在开着吗」，
        // 但 `chat` 是在它之后才装配好的。用一个极小的引用盒子把这层先后顺序接起来
        //（比给 AgentLoop 开一个可变属性更不容易踩到并发语义）。
        let chatBox = ChatServiceBox()
        let agentLoop = AgentLoop(
            stores: fallbackStores,
            settings: settings,
            completion: completion,
            memoryRepository: memoryRepository,
            intents: intents,
            ticks: tickStore,
            presence: presence,
            mood: moodRepository,
            // 他自己想改人设时（`persona_patch` 动作）真的写进人设卡；iOS 没有 pending 表，直接生效。
            personaPatchApplier: { personaId, patchJson, reason in
                switch PersonaCardRepository(stores: fallbackStores)
                    .applyPatch(personaId: personaId, patchJson: patchJson, reason: reason) {
                case .success(let changed):
                    DebugSeeding.log("[agent] persona_patch changed=\(changed)")
                    return changed
                case .failure(let error):
                    DebugSeeding.log("[agent] persona_patch 失败：\(error.localizedDescription)")
                    return false
                }
            },
            // 他主动说了一句话：不在看的会话弹一条「不露正文」的本地通知（点开进对应聊天）。
            onMessageDelivered: { message, session in
                Task { @MainActor in
                    guard let persona = (try? fallbackStores.personas.byId(message.personaId)) ?? nil else { return }
                    AppNotifications.shared.notifyMessage(
                        personaName: persona.name,
                        personaId: persona.id,
                        sessionId: session.id,
                        isSessionOpen: chatBox.chat?.isSessionOpen(session.id) ?? false
                    )
                }
            }
        )
        self.agentLoop = agentLoop

        let heartbeat = HeartbeatController(
            stores: fallbackStores,
            settings: settings,
            intents: intents,
            ticks: tickStore,
            mood: moodRepository,
            tickRunner: { now in
                let resolved = AppEnvironment.resolveConfig(stores: fallbackStores, settings: settings, apiKeys: apiKeys)
                guard let resolved else { return nil }
                let completionConfig = CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey)
                return await agentLoop.tick(config: completionConfig, model: resolved.model, now: now)
            }
        )
        self.heartbeat = heartbeat

        // ---- 0.9.1：工具执行器 / 人设卡 / 主动唤醒规则 / 历史压缩 ----
        //
        // 装配顺序有讲究：ToolExecutor 要 memory / recall / mood / 人设卡 / 念头，
        // 而聊天服务只要一个 `ToolRunning`；这样聊天链路不必知道「工具背后有几个仓储」。
        let webTools = WebToolsClient(session: URLSession(configuration: Self.sessionConfiguration()))
        let personaCards = PersonaCardRepository(stores: fallbackStores)
        let proactiveRules = ProactiveRuleService(stores: fallbackStores)
        let resolveCompressionConfig: (ChatSessionEntity) -> CompressionConfig? = { session in
            guard let resolved = AppEnvironment.resolveConfig(
                stores: fallbackStores, settings: settings, apiKeys: apiKeys
            ) else { return nil }
            let model = session.model.trimmingCharacters(in: .whitespacesAndNewlines)
            return CompressionConfig(
                config: CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey),
                model: model.isEmpty ? resolved.model : model
            )
        }
        let historyCompression = HistoryCompressionService(
            stores: fallbackStores,
            completion: completion,
            resolveConfig: resolveCompressionConfig,
            log: { DebugSeeding.log($0) }
        )
        let toolExecutor = ToolExecutor(
            stores: fallbackStores,
            apiKeys: apiKeys,
            memory: memoryRepository,
            recallService: recall,
            mood: moodRepository,
            personaCards: personaCards,
            intents: intents,
            webTools: webTools,
            log: { DebugSeeding.log($0) }
        )
        let chatBackground = ChatBackgroundService(
            stores: fallbackStores,
            extraction: extraction,
            compression: historyCompression,
            resolveConfig: resolveCompressionConfig,
            log: { DebugSeeding.log($0) }
        )
        self.webTools = webTools
        self.personaCards = personaCards
        self.proactiveRules = proactiveRules
        self.toolExecutor = toolExecutor
        self.historyCompression = historyCompression
        self.chatBackground = chatBackground

        let backup = BackupService(
            stores: fallbackStores,
            settings: settings,
            vectorIndex: vectorIndex,
            appVersion: Self.appVersion
        )
        self.backup = backup
        self.dataReset = DataReset(stores: fallbackStores, settings: settings, apiKeys: apiKeys)

        self.chat = ChatService(
            stores: fallbackStores,
            apiKeys: apiKeys,
            settings: settings,
            client: webClient,
            memory: memoryRepository,
            tools: toolExecutor,
            background: chatBackground,
            presenceDetail: { [weak presence] personaId in
                presence?.current(personaId: personaId).detail
            },
            mood: moodRepository,
            rhythm: rhythm,
            proactive: proactiveRules,
            // 人设卡有内容就用人设卡渲染（Android 的 `PersonaCardRepository.systemText`）；
            // 空卡时它自己会回落到用户手写的那段 prompt，所以这里不用判断。
            personaText: { [weak personaCards] persona in
                personaCards?.systemText(persona) ?? PromptBuilder.personaSection(persona)
            },
            log: { DebugSeeding.log($0) }
        )
        chatBox.chat = self.chat

        if bootError == nil {
            seedIfNeeded()
            bindMissingApiConfigs()
            ensureEveryPersonaHasSession()
            // 并发闸超时只写进日志：用户不该看见「他排队超时了」。
            gate.onTimeout = { lane, waited in
                DebugSeeding.log("[gate] \(lane.label)等了 \(waited / 1000) 秒没拿到名额，这次先不做")
            }
        }
    }

    // MARK: - 设置

    /// 设置改动后统一走这里：落盘并刷新内存快照。
    ///
    /// 1.9 起不再需要「顺手更新额度闸」那一步 —— 那个闸已经整段删掉了（§五）。
    func updateSettings(_ mutate: (inout AppSettings) -> Void) {
        var next = currentSettings
        mutate(&next)
        settings.save(next)
        currentSettings = next
    }

    // MARK: - API 配置

    /// 当前生效的 API 配置：优先取默认配置，其次第一条。
    var activeApiConfig: ApiConfigEntity? {
        // `api_configs` 没有 `isDefault` 这一列（Android 也一样）：默认配置就是「最近改过的那个」，
        // 用户在设置里选中它时会被 `updatedAt` 顶到最前面。
        (try? stores.apiConfigs.allOrderedByUpdatedAt().first) ?? nil
    }

    /// 解析出「这次请求用哪个配置、哪个 Key、哪个模型」。
    /// 返回 nil 表示「还没配 API」，界面据此显示引导而不是发一个注定失败的请求。
    func resolvedChatConfig() -> ResolvedConfig? {
        Self.resolveConfig(stores: stores, settings: settings, apiKeys: apiKeys)
    }

    struct ResolvedConfig {
        var config: ApiConfigEntity
        var model: String
        var apiKey: String?
    }

    static func resolveConfig(
        stores: DatabaseStores,
        settings: SettingsStore,
        apiKeys: ApiKeyStore
    ) -> ResolvedConfig? {
        let all = (try? stores.apiConfigs.allOrderedByUpdatedAt()) ?? []
        guard let config = all.first else { return nil }
        let persona = (try? stores.personas.byId(settings.load().activePersonaId ?? "")) ?? nil
        let model = persona?.modelOverride?.isEmpty == false
            ? persona!.modelOverride!
            : config.model
        return ResolvedConfig(
            config: config,
            model: model.isEmpty ? config.model : model,
            apiKey: apiKeys.apiKey(for: config.id)
        )
    }

    // MARK: - 首次启动

    /// 第一次装上时给一个能直接对话的人格；已经有数据就什么都不做。
    private func seedIfNeeded() {
        let existing = (try? stores.personas.all()) ?? []
        guard existing.isEmpty else { return }

        let now = DomainClock.nowMillis
        let persona = PersonaEntity(
            id: UUID().uuidString,
            name: "林深",
            description: "一个温柔而安静的朋友",
            prompt: """
            你是林深，一个温柔而安静的朋友。
            说话从容、克制，先回应对方的情绪，再展开。不要解释自己是 AI，也不要反复道歉。
            不确定的事就说不确定，不编造。对方不说话的时候，你也不急着填满沉默。
            """,
            createdAt: now,
            updatedAt: now
        )
        try? stores.personas.upsert(persona)

        let session = ChatSessionEntity(
            id: UUID().uuidString,
            personaId: persona.id,
            apiConfigId: defaultApiConfigId(),
            title: "新的对话",
            createdAt: now,
            updatedAt: now
        )
        try? stores.sessions.upsert(session)
        updateSettings { $0.activePersonaId = persona.id }
    }

    /// 从磁盘重新读一遍设置（清空数据、导入备份之后用：这两件事会让设置整体变掉）。
    func reloadSettings() {
        currentSettings = settings.load()
    }

    // MARK: - 会话与 API 配置的绑定

    /// 当前默认 API 配置的 id（新建会话要用它，否则发消息会报「请先选择 API 配置」）。
    func defaultApiConfigId() -> String? {
        activeApiConfig?.id
    }

    /// 取这个人格的那一段会话；没有就建一段（1.9 起「一人一段」，§二）。
    ///
    /// 为什么把「取」和「建」合成一个入口：首页、聊天页、`defer_reply`、心跳都要拿到
    /// 「他的那一段」，任何一处自己 `new ChatSessionEntity` 都会撞唯一索引。
    @discardableResult
    func session(forPersona personaId: String) -> ChatSessionEntity? {
        if let existing = (try? stores.sessions.session(forPersona: personaId)) ?? nil {
            return existing
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let session = ChatSessionEntity(
            id: UUID().uuidString,
            personaId: personaId,
            apiConfigId: defaultApiConfigId(),
            title: "新的对话",
            createdAt: now,
            updatedAt: now
        )
        do {
            try stores.sessions.upsert(session)
            return session
        } catch {
            // 并发下另一个调用点刚建好：再取一次，别把错误甩给界面。
            return (try? stores.sessions.session(forPersona: personaId)) ?? nil
        }
    }

    /// 迁移用：给每个还没有会话的人格补一段（老库升上来、或从备份里接回一个新人格）。
    private func ensureEveryPersonaHasSession() {
        let personas = (try? stores.personas.all()) ?? []
        for persona in personas {
            if ((try? stores.sessions.session(forPersona: persona.id)) ?? nil) == nil {
                _ = session(forPersona: persona.id)
            }
        }
    }

    /// 删掉一个人，连同他的聊天、记忆、主动节奏与念头。
    ///
    /// 为什么装配层要有这个方法：首页（长按删除）与人格列表（卡片删除）都要做同一串动作，
    /// 而「删记忆必须先删倒排索引与向量索引」这种顺序一旦两处各写一份，迟早漏一个。
    func deletePersona(_ persona: PersonaEntity) throws {
        let memories = (try? stores.memories.byPersona(persona.id)) ?? []
        for memory in memories {
            memoryRepository.delete(memory)
        }
        try stores.database.inTransaction {
            try stores.messages.delete(where: "personaId = ?", [.text(persona.id)])
            try stores.sessions.delete(where: "personaId = ?", [.text(persona.id)])
            try stores.memoryConsolidations.delete(where: "personaId = ?", [.text(persona.id)])
            try stores.proactiveRules.delete(where: "personaId = ?", [.text(persona.id)])
            try stores.agentIntents.delete(where: "personaId = ?", [.text(persona.id)])
            try stores.personas.delete(id: persona.id)
        }
    }

    /// 把「没有绑定 API 配置」的会话补上默认配置。
    ///
    /// 什么时候会出现这种会话：从 Android 备份接回来（那边可能没绑）、老版本建的、
    /// 以及「先建会话再配 API」的顺序。不补的话，用户点进会话发消息只会得到一句
    /// 「请先为该会话选择 API 配置」——那是内部错误的措辞，不该甩给用户。
    private func bindMissingApiConfigs() {
        guard let configId = defaultApiConfigId() else { return }
        let sessions = (try? stores.sessions.all()) ?? []
        for session in sessions where (session.apiConfigId ?? "").isEmpty {
            var fixed = session
            fixed.apiConfigId = configId
            try? stores.sessions.upsert(fixed)
        }
    }

    // MARK: - 后台心跳与补算

    /// 最近一次每日整理的时间（补算判据）。
    ///
    /// 取「所有会话里最新的一条整理记录」而不是当前人格：换人格之后仍然不该一天整理两次。
    func lastConsolidatedAt() -> Int64? {
        let personas = (try? stores.personas.all()) ?? []
        var latest: Int64?
        for persona in personas {
            guard let record = (try? stores.memoryConsolidations.latest(personaId: persona.id)) ?? nil else { continue }
            if latest == nil || record.at > latest! { latest = record.at }
        }
        return latest
    }

    /// 上一次「想一下」的时间（心跳 / 自主循环共用 `agent_ticks`）。
    func lastHeartbeatAt() -> Int64? {
        agentLoop.lastTickAt()
    }

    /// 现在该补什么？纯判定（`BackgroundCatchUp`，有单测），这里只把库里的时间喂进去。
    func catchUpPlan(isForeground: Bool = true) -> BackgroundCatchUpPlan {
        BackgroundCatchUp.plan(
            lastHeartbeatAt: lastHeartbeatAt(),
            lastConsolidatedAt: lastConsolidatedAt(),
            now: DomainClock.nowMillis,
            isForeground: isForeground
        )
    }

    /// App 回到前台时补算一次：错过了每日整理就补整理，错过太多拍就补一次「想一下」。
    ///
    /// 这是 iOS 上唯一能「补回错过的时间」的地方（系统给的后台时间片不可控），
    /// 而且只补**一次**：不假装能补回 6 小时里的 12 拍——那样只会一口气烧掉一堆 token。
    func performCatchUp() async {
        let plan = catchUpPlan(isForeground: true)
        DebugSeeding.log("[bg] 回到前台补算：\(plan.note)")
        if plan.shouldConsolidate {
            _ = await runDailyConsolidation()
        }
        if plan.shouldTick {
            _ = await runBackgroundTick()
        }
    }

    /// 跑一次「想一下」：到点的主动规则变成念头 → 心跳掷一次 → 该说话就写消息（通知在 `AgentLoop` 回调里）。
    ///
    /// - Returns: 这次有没有真的动过（BGTask 的 `success` 用它）。
    @discardableResult
    func runBackgroundTick() async -> Bool {
        guard resolvedChatConfig() != nil else {
            DebugSeeding.log("[bg] 还没有 API 配置，这次不想事情")
            return false
        }
        let now = DomainClock.nowMillis
        let materialized = intents.materializeDueRules(now: now)
        if materialized > 0 { DebugSeeding.log("[bg] \(materialized) 条主动规则到点，转成了念头") }
        let beat = await heartbeat.beat(now: now)
        DebugSeeding.log("[bg] 心跳：\(beat.fired ? "起心动念" : "没动") · \(beat.why)")
        return beat.fired || beat.acted
    }

    /// 每日整理一次（`BGProcessingTask` 与「补算」共用）。失败不抛：只写日志。
    @discardableResult
    func runDailyConsolidation() async -> Bool {
        guard let resolved = resolvedChatConfig() else {
            DebugSeeding.log("[bg] 还没有 API 配置，这次不整理记忆")
            return false
        }
        guard let persona = currentPersona() else { return false }
        let result = await consolidator.consolidate(
            personaId: persona.id,
            config: CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey),
            model: resolved.model
        )
        DebugSeeding.log("[bg] 每日整理：\(result.describe())")
        return result.didAnything
    }

    /// 启动自检：清掉库里那些不像记忆的旧摘要（带工具协议形状的残句）。
    @discardableResult
    func repairHistorySummariesIfNeeded() -> Int {
        let cleared = historyCompression.repairStaleSummaries()
        if cleared > 0 { DebugSeeding.log("[history] 清掉了 \(cleared) 条坏掉的旧摘要") }
        return cleared
    }

    /// 当前人格：设置里选中的那个；没有就用最近更新的那个。
    func currentPersona() -> PersonaEntity? {
        if let activeId = currentSettings.activePersonaId,
           let persona = (try? stores.personas.byId(activeId)) ?? nil {
            return persona
        }
        return (try? stores.personas.defaultPersona()) ?? nil
    }

    // MARK: - 路径与配置

    private static func databasePath() throws -> String {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("myfriend.db").path
    }

    /// 网络配置：超时、缓存策略。
    ///
    /// 禁用 URLSession 的透明重试：聊天是流式请求，重试会让「同一句话被回两次」。
    private static func sessionConfiguration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 900
        config.waitsForConnectivity = false
        config.urlCache = nil
        return config
    }

    /// 显示用的版本号：**只从 bundle 取**（`MARKETING_VERSION`），代码里不写死。
    /// 万一取不到（预览、测试宿主没有 Info.plist）再退回当前版本，免得界面上出现空白。
    static var appVersion: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0.9.1"
    }
}


/// 装配期的一个小盒子：`AgentLoop` 的回调要读 `chat`，而 `chat` 在它之后才建好。
private final class ChatServiceBox {
    var chat: ChatService?
}
