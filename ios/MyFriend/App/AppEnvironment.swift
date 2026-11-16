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
    /// 见文件末尾 `AppEnvironmentBox` 注释。
    private let envBox = AppEnvironmentBox()

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
    /// agent 决策层（0.11.0）：睡眠判定 / defer 计划 / 主动上限 / 工具参数夹取。
    ///
    /// 装配层只造**一个**实例再分发：远程模式下它持有一条 `URLSession`，
    /// 每个服务各造一份会让超时与连接各自独立、联调日志也散掉。
    /// 选择顺序见 `AgentCoreClientFactory`（环境变量 → Info.plist → 本机）；
    /// 默认本机实现，零网络请求。
    let agentCore: any AgentCoreClient

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

    // MARK: - 服务端模式（M3/M4）
    //
    // 装配层只做三件事：读配置、造三件套（同步 / 事件 / 聊天）、把它们接到生命周期上。
    // 真正的逻辑都在 MyFriendKit（`ServerSyncService` / `ServerEventService` /
    // `ServerChatGateway`），这里不复制任何一条业务规则。

    /// 服务端模式的设置来源（令牌在 Keychain，开关/地址在 UserDefaults）。
    let serverCredentials: ServerCredentialStore
    /// 当前生效的服务端配置（设置页改完会重建）。
    @Published private(set) var serverConfig: ServerConfig
    /// 服务端模式是不是真的可用（开关 + 合法地址）。界面用它决定走哪条链路。
    @Published private(set) var serverModeActive: Bool
    /// 服务端下发的心情/在场（M4：这两种东西在服务端模式下是「下发」的，不是本机推导的）。
    @Published private(set) var serverLiveState: ServerLiveState

    private(set) var serverSync: ServerSyncService?
    private(set) var serverEvents: ServerEventService?
    private(set) var serverChat: ServerChatGateway?
    /// 前台事件长轮询任务（进后台就取消）。
    private var serverEventTask: Task<Void, Never>?
    /// 「App 在前台吗」的线程安全镜像。
    ///
    /// 为什么不用 `isForeground` 那个 `private(set) var`：它属于 `@MainActor`，
    /// 而服务端事件流在**后台线程**上问这一句（决定要不要弹通知）。跨隔离域读一个
    /// 可变属性是数据竞争，所以这里单独一份加锁的布尔值，主线程写、任意线程读。
    private let serverForeground = ServerForegroundFlag()

    // MARK: - 前台心跳与补算的节流状态（0.10.2 审计 P1-2 / P1-3）

    /// 「补算」的重入保护：冷启动 `.task` 与回前台 `onChange` 会同时命中同一次启动，
    /// 没有它就会跑两遍（两次心跳 + 两次每日整理 + 两条主动消息）。
    private let catchUpGuard = CatchUpGuard()
    /// 每日整理同理：BGProcessingTask 与回前台补算可能撞上。
    private var isConsolidating = false
    /// 前台 30 秒一拍的定时器（只在 App 前台时存在）。
    private var foregroundPulseTask: Task<Void, Never>?
    /// 当前是不是前台（`scenePhase` 的镜像；定时器循环里用它再确认一次）。
    private(set) var isForeground = false

    /// 启动失败时留给根视图显示（数据库打不开、没有可写目录……）。
    let bootError: String?

    // MARK: - 构造

    init() {
        var bootError: String?
        var stores: DatabaseStores?
        do {
            stores = try DatabaseStores.open(path: Self.databasePath())
        } catch {
            bootError = Lf("打不开本地数据库：%@", error.localizedDescription)
        }

        // 数据库都打不开时，整个 App 仍然要能显示一句话而不是闪退。
        // 这些「空壳」对象只是为了让属性初始化器有值；`bootError != nil` 时根视图不会用它们。
        let fallbackStores = stores ?? (try! DatabaseStores.open(path: ":memory:"))
        self.stores = fallbackStores
        self.bootError = bootError

        let settings = SettingsStore()
        self.settings = settings
        // 未签名的模拟器构建拿不到 Keychain entitlement；只有「DEBUG + 模拟器」才允许
        // 把 Key 临时放进 UserDefaults 调通链路。真机 / Release 上写失败会抛错
        //（`KeychainFallbackPolicy`，见 0.10.2 审计 P1-5）。
        let apiKeys = ApiKeyStore(backend: ResilientKeychain())
        self.apiKeys = apiKeys
        // agent 决策层：默认本机（零网络）。要让 App 走远程，用环境变量/Info.plist 选择，
        // 例如 `SIMCTL_CHILD_MYFRIEND_AGENT_CORE=remote` + `..._URL=http://127.0.0.1:8788`。
        let agentCore = AgentCoreClientFactory.make(log: { DebugSeeding.log($0) })
        self.agentCore = agentCore
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
            completion: completion,
            // 写回被并发抢走时只进日志（后台任务失败不该弹错给用户）。
            log: { DebugSeeding.log($0) }
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
            agentCore: agentCore,
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
            agentCore: agentCore,
            tickRunner: { now in
                let resolved = AppEnvironment.resolveConfig(stores: fallbackStores, settings: settings, apiKeys: apiKeys)
                guard let resolved else { return nil }
                let completionConfig = CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey)
                return await agentLoop.tick(config: completionConfig, model: resolved.model, now: now)
            },
            // 低电量模式下把前台账拍放宽到 2 分钟（`ForegroundPulse.intervalMillis` 与
            // 概率换算用的是同一个数）：省电优先，但也别让「他」彻底不说话。
            isLowPower: { ProcessInfo.processInfo.isLowPowerModeEnabled }
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
        // 联网打分的「用完即弃」调用：与聊天/抽取共用同一个 completion 与并发闸。
        let webSearchScorer = WebSearchScorer(
            stores: fallbackStores,
            memory: memoryRepository,
            completion: completion,
            log: { DebugSeeding.log($0) }
        )
        // 服务端模式下的活动上报：init 里不能捕获 self，用局部转发变量在装配完成后赋值。
        let box = envBox
        let toolExecutor = ToolExecutor(
            stores: fallbackStores,
            apiKeys: apiKeys,
            memory: memoryRepository,
            recallService: recall,
            mood: moodRepository,
            personaCards: personaCards,
            intents: intents,
            webTools: webTools,
            scorer: webSearchScorer,
            agentCore: agentCore,
            // 服务端模式下把 `set_activity` 上报给服务端（写路径最小闭环）。
            // 这里的闭包要读「现在的模式」，所以通过弱引用问 self，而不是在装配时拍死。
            activityReported: { activity, personaId in
                box.env?.reportServerActivity(activity, personaId: personaId)
            },
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
        // 媒体清理收进 `DataReset`（0.10.2 审计 P1-7）：设置页与备份页两个入口都只调它，
        // 谁也不会再忘记删图片/头像文件。
        self.dataReset = DataReset(
            stores: fallbackStores,
            settings: settings,
            apiKeys: apiKeys,
            media: .shared
        )

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
            log: { DebugSeeding.log($0) },
            // 语言跟随系统（含 per-app language）：每次请求现读，用户改完语言不用重启装配。
            languageDirective: { AppLanguage.languageDirective },
            agentCore: agentCore
        )
        chatBox.chat = self.chat

        // ---- 服务端模式 ----
        // 令牌与 API Key 共用同一份 Keychain 分区；开关/地址在 UserDefaults。
        // 这里只把「设置来源」建出来；真正的客户端/服务在 `applyServerConfig()` 里装配
        //（它要在 init 完全结束之后才碰 self，见那边的注释）。
        let serverCredentials = ServerCredentialStore(keychain: ResilientKeychain())
        self.serverCredentials = serverCredentials
        let loadedServerConfig = (try? ServerConfig.loadForApp(credentials: serverCredentials))
            ?? ServerConfig(baseURL: nil, token: nil, enabled: false)
        self.serverConfig = loadedServerConfig
        self.serverModeActive = loadedServerConfig.isUsable
        self.serverLiveState = ServerLiveState()

        if bootError == nil {
            // 服务端模式下**不再种本地人格**：人和会话都是服务端下发的，
            // 本机再种一份会变成「同一个人出现两次」（本地一个、服务端一个不同 id 的）。
            // 已有本地人格的情况属于 M5（人格/记忆 CRUD 上移）的迁移范围，这里不做合并：
            // 同步只按 id 命中，命中不了的服务端人格会作为新的人格出现（见交付报告的已知限制）。
            if !loadedServerConfig.isUsable {
                seedIfNeeded()
            } else {
                DebugSeeding.log("[server] 服务端模式：跳过本地人格种子（人以服务端为准）")
            }
            bindMissingApiConfigs()
            ensureEveryPersonaHasSession()
            // 并发闸超时只写进日志：用户不该看见「他排队超时了」。
            gate.onTimeout = { lane, waited in
                DebugSeeding.log("[gate] \(lane.label)等了 \(waited / 1000) 秒没拿到名额，这次先不做")
            }
        }

        // 服务端模式：读设置 → 造客户端 → 决定走哪条链路。
        // 放在 init 最后一行的理由：`applyServerConfig()` 会捕获 self（事件流要知道
        // 「前台吗 / 这个会话开着吗」），而捕获 self 必须等所有存储属性都初始化完。
        applyServerConfig()
        box.env = self
    }

    // MARK: - 服务端模式（M3/M4）

    /// 读设置并重建服务端三件套。设置页改完地址/令牌/开关之后调它。
    ///
    /// 三件事按顺序：
    /// 1. `ServerConfig.loadForApp`：设备设置（Keychain 令牌 + UserDefaults 开关/地址）优先，
    ///    其次环境变量 / Info.plist（与 P1a 的 `ServerConfig.load` 同一套校验，https 规则不变）；
    /// 2. 合法就造一个 `ServerAPIClient` 与同步/事件/聊天三件套；不合法就把它们清空；
    /// 3. 把「为什么没起来」写进 Debug 日志（配置错误里绝不含令牌）。
    func applyServerConfig() {
        let loaded: ServerConfig
        do {
            loaded = try ServerConfig.loadForApp(credentials: serverCredentials)
        } catch {
            // 地址不合法（http 明文 / 格式错 / Release 里写 http）：服务端模式直接不可用，
            // 界面会在「测试连接」处显示原因。
            serverConfig = ServerConfig(baseURL: nil, token: nil, enabled: false)
            serverModeActive = false
            serverSync = nil
            serverEvents = nil
            serverChat = nil
            DebugSeeding.log("[server] 配置不合法：\(error.localizedDescription)")
            return
        }
        serverConfig = loaded
        guard loaded.isUsable else {
            serverModeActive = false
            serverSync = nil
            serverEvents = nil
            serverChat = nil
            DebugSeeding.log("[server] 服务端模式未启用（继续用本地模式）")
            return
        }

        let session = URLSession(configuration: Self.sessionConfiguration())
        let client = ServerAPIClient(config: loaded, session: session)
        let outbox = ServerOutboxStore(database: stores.database)
        let chatGateway = ServerChatGateway(
            client: client,
            stores: stores,
            outbox: outbox,
            log: { DebugSeeding.log($0) }
        )
        serverSync = ServerSyncService(
            client: client,
            stores: stores,
            log: { DebugSeeding.log($0) }
        )
        // 这两件事会被服务端事件流在**后台线程**上问，所以只捕获「已经造好的对象」
        // 与「加锁的布尔值」，不捕获 self（跨隔离域读 @MainActor 的可变状态是数据竞争）：
        // - `ChatService.isSessionOpen` 自己带锁，任意线程可调；
        // - `ServerForegroundFlag` 也是带锁的。
        let chatRef = chat
        let foreground = serverForeground
        serverEvents = ServerEventService(
            client: client,
            stores: stores,
            notifier: ServerNotifications(isSessionOpen: { sessionId in
                chatRef.isSessionOpen(sessionId)
            }),
            isSessionOpen: { sessionId in
                chatRef.isSessionOpen(sessionId)
            },
            isForeground: { foreground.isActive },
            onLiveState: { [weak self] state in
                Task { @MainActor in self?.serverLiveState = state }
            },
            log: { DebugSeeding.log($0) }
        )
        serverChat = chatGateway
        serverModeActive = true
        DebugSeeding.log("[server] 服务端模式已启用：\(loaded.baseURL?.absoluteString ?? "-")")
        // 重启进程后待发队列里可能有上次没发出去的消息：先把气泡补回界面。
        let restored = chatGateway.recoverPendingMessages()
        if restored > 0 { DebugSeeding.log("[server] 恢复了 \(restored) 条待发消息") }
    }

    /// 服务端模式下的「发送」入口：服务端可用就走服务端，否则走本地（行为一字未改）。
    ///
    /// 界面（`ChatViewModel.send`）只认这一个入口，所以「哪种模式」的判断只在这里出现一次。
    func sendChecked(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment] = []
    ) -> ChatService.SendOutcome {
        if let gateway = serverChat, gateway.isActive {
            return gateway.sendChecked(sessionId: sessionId, text: text, attachments: attachments)
        }
        return chat.sendChecked(sessionId: sessionId, text: text, attachments: attachments)
    }

    /// 进聊天页：服务端模式下把它该做的事做掉（拉最新块 / 标已读 / 补发队列）。
    ///
    /// 失败只写日志：这些都是「让内容更新」的辅助动作，不该在打开页面时弹错误
    ///（本地模式照旧读缓存，界面上不会空白）。
    func prepareServerChat(sessionId: String) async {
        guard let gateway = serverChat, gateway.isActive else { return }
        // 0) 本地会话还没和服务端会话对上号（第一次进服务端模式、或本地模式先建的会话）：
        //    先 `POST /v1/sessions` 建/取回它，把服务端 id 记到本机那条会话上。
        //    没有这一步，后面所有写路径都会打到本机 UUID 上（服务端 404）。
        if let session = ((try? stores.sessions.byId(sessionId)) ?? nil),
           session.serverSessionId == nil || session.serverSessionId?.isEmpty == true {
            do {
                _ = try await gateway.ensureSession(personaId: session.personaId, title: session.title)
            } catch {
                DebugSeeding.log("[server] 建会话失败：\(error.localizedDescription)")
            }
        }
        do {
            try await gateway.loadLatestBlock(sessionId: sessionId)
        } catch {
            DebugSeeding.log("[server] 拉会话块失败：\(error.localizedDescription)")
        }
        await gateway.markRead(sessionId: sessionId)
        if !gateway.pendingEntries(sessionId: sessionId).isEmpty {
            _ = await gateway.flushOutbox()
        }
    }

    /// 服务端模式下往前翻一块（聊天页上滑到底）。
    ///
    /// 用的是**服务端的块号**（`ServerChatGateway` 自己记着），不是本地块号：
    /// 本地块由 `MessageBlockStore` 按写入顺序切，两边块号不是一回事。
    func loadOlderServerBlock(sessionId: String) async {
        guard let gateway = serverChat, gateway.isActive else { return }
        do {
            try await gateway.loadOlderBlockRememberingPosition(sessionId: sessionId)
        } catch {
            DebugSeeding.log("[server] 翻更早的块失败：\(error.localizedDescription)")
        }
    }

    /// 服务端说这个会话上面还有没有更早的块（本地缓存可能还没拉到那么早）。
    func serverHasMoreOlder(sessionId: String) -> Bool {
        guard let gateway = serverChat, gateway.isActive else { return false }
        return gateway.hasOlderOnServer(sessionId: sessionId)
    }

    /// 同步一次服务端状态（启动 / 回前台 / 事件流空闲时）。
    @discardableResult
    func syncServerNow() async -> ServerSyncOutcome? {
        guard let sync = serverSync else { return nil }
        let outcome = await sync.syncNow()
        switch outcome {
        case .synced(let report):
            DebugSeeding.log("[server] 同步完成：会话 \(report.sessions) / 消息 \(report.messages)")
        case .failed(let message, let attempts):
            DebugSeeding.log("[server] 同步失败（\(attempts) 次）：\(message)")
        case .skipped(let reason):
            DebugSeeding.log("[server] 同步跳过：\(reason)")
        }
        return outcome
    }

    /// 起前台事件订阅（长轮询）。重复调用是安全的。
    func startServerEvents() {
        guard let events = serverEvents, serverModeActive else { return }
        guard serverEventTask == nil else { return }
        serverEventTask = Task { [weak events] in
            await events?.runLoop()
        }
        DebugSeeding.log("[server] 事件订阅已启动")
    }

    func stopServerEvents() {
        serverEventTask?.cancel()
        serverEventTask = nil
    }

    /// 服务端模式的一次「进前台」全套动作：同步状态 + 起事件流 + 补发队列。
    ///
    /// 顺序有意为之：先同步（把离线期间的整段状态补齐），再起事件流（从这里开始只收增量），
    /// 否则事件流会一边补历史一边收增量，游标语义会乱。
    func activateServerMode() async {
        guard serverModeActive else { return }
        await syncServerNow()
        if let gateway = serverChat, gateway.isActive {
            _ = await gateway.flushOutbox()
        }
        startServerEvents()
    }

    /// `set_activity` 在服务端模式下要上报给服务端（规范 §4：主动引擎据此决定要不要开口）。
    ///
    /// 失败只记日志：本地那份活动已经写进人格了，界面上「在忙」照样显示；
    /// 下一次同步会被服务端权威值覆盖，不需要在这里回滚用户看得见的东西。
    func reportServerActivity(_ activity: PersonaActivity, personaId: String) {
        guard serverModeActive else { return }
        // `ServerChatGateway` 不暴露 client，这里单独造一个同配置的（成本只是一次 init）。
        let api = ServerAPIClient(config: serverConfig, session: URLSession(configuration: Self.sessionConfiguration()))
        Task { @MainActor in
            do {
                _ = try await api.reportActivity(
                    personaId: personaId,
                    activity: activity.activity,
                    kind: activity.kind.stored,
                    minutes: activity.minutes,
                    availability: activity.availability.rawValue
                )
                DebugSeeding.log("[server] 活动已上报：\(activity.activity)")
            } catch {
                DebugSeeding.log("[server] 活动上报失败（只记日志）：\(error.localizedDescription)")
            }
        }
    }

    /// 注册推送 token（M6 的脚手架：服务端有 token 就交给可插拔的 `PushSender`）。
    func registerServerPushToken(_ token: String) async {
        guard serverModeActive else { return }
        let api = ServerAPIClient(config: serverConfig, session: URLSession(configuration: Self.sessionConfiguration()))
        do {
            let result = try await api.registerPushToken(
                token: token,
                bundleId: Bundle.main.bundleIdentifier
            )
            DebugSeeding.log("[server] 推送 token 已注册：created=\(result.created)")
        } catch {
            DebugSeeding.log("[server] 推送 token 注册失败：\(error.localizedDescription)")
        }
    }

    /// 设置页「测试连接」用：只打 `/health`，不触发同步。
    func testServerConnection() async -> Result<ServerHealth, Error> {
        let api = ServerAPIClient(config: serverConfig, session: URLSession(configuration: Self.sessionConfiguration()))
        do {
            return .success(try await api.testConnection())
        } catch {
            return .failure(error)
        }
    }

    /// 设置页保存服务端设置：写 Keychain / UserDefaults，然后立刻生效。
    ///
    /// - Throws: 地址不合法（http 明文 / 格式错）时抛给界面显示中文原因；令牌已经写进 Keychain 了。
    func saveServerSettings(enabled: Bool, baseURLText: String, token: String?) throws {
        serverCredentials.setModeEnabled(enabled)
        serverCredentials.setBaseURLText(baseURLText)
        try serverCredentials.setToken(token)
        applyServerConfig()
        stopServerEvents()
        if serverModeActive {
            Task { await activateServerMode() }
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

    /// 主动唤醒的「现在试一次」：走心跳平时那条开口路径（同一套人格提示词、同一个落库与通知回调），
    /// 只生成一条消息、**不改规则的排期**；失败原因由 `AgentLoop` 写回规则的 `lastError`。
    func triggerProactiveRule(_ ruleId: String) async -> AgentLoop.ProactiveFireOutcome {
        guard let resolved = resolvedChatConfig() else {
            return .failure(L("还没有可用的 API 配置"))
        }
        return await agentLoop.fireProactiveRule(
            ruleId: ruleId,
            config: CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey),
            model: resolved.model
        )
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

    /// 第一次装上时给三个能直接对话的示例人格；已经有数据就什么都不做。
    ///
    /// 两套人设随系统语言二选一（`SeedPersonas`）：中文系统得到「林深 / 阿岚 / 墨」，
    /// 英文系统得到 Rowan / Juno / Ellis。判定只看「库里有没有人」这一件事——
    /// 所以只影响新安装，已有数据不迁移、不改名，切语言也不会重写它们。
    private func seedIfNeeded() {
        // 播种逻辑在 Kit（`PersonaSeeding`，有单测）：首次启动与「删除全部本地数据之后」
        // 走的是同一份代码、同一套固定 id（0.11.0 审计 P2-13）。
        let outcome = (try? PersonaSeeding.seedIfNeeded(
            stores: stores,
            language: AppLanguage.current.seedLanguage,
            sessionTitle: L("新的对话"),
            apiConfigId: defaultApiConfigId(),
            now: DomainClock.nowMillis
        )) ?? PersonaSeeding.Outcome()
        guard !outcome.skipped, let first = outcome.personaIds.first else { return }
        updateSettings { $0.activePersonaId = first }
    }

    /// 「删除全部本地数据」的唯一入口（设置页与备份页都调它）。
    ///
    /// 为什么不让两个 View 各自调 `dataReset.clearAll()`：清空之后还要做几件装配层的事，
    /// 任何一处漏掉都会让用户看到不一致的状态（0.11.0 审计 P2-13）：
    /// 1. 按**当前语言**重新播种三位示例人格（不留一个空白 App）；
    /// 2. 设置刚被清空，把「正在聊的人」指回第一个种子人格；
    /// 3. 给每个人格补上会话与 API 配置绑定（清空之后通常还没有配置，这一步是幂等的）。
    @discardableResult
    func clearAllData() -> ClearSummary {
        dataReset.reseed = ReseedConfig(
            language: AppLanguage.current.seedLanguage,
            sessionTitle: L("新的对话"),
            apiConfigId: nil,
            now: DomainClock.nowMillis
        )
        let summary = dataReset.clearAll()
        dataReset.reseed = nil
        reloadSettings()
        if let first = (try? stores.personas.all(orderBy: "createdAt ASC"))?.first {
            updateSettings { $0.activePersonaId = first.id }
        }
        ensureEveryPersonaHasSession()
        bindMissingApiConfigs()
        heartbeat.clearCache()
        DebugSeeding.log("[reset] 全部删除：重新播种 \(summary.seededPersonas) 个人格、\(summary.seededSessions) 段会话")
        return summary
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

    /// 这段会话还在不在（不建新的）。
    ///
    /// 0.11.1 审计 P2-2：通知深链在 push 之前必须校验 —— 会话可能已经被删掉
    /// （清空全部数据、删人格、替换导入），直接跳进去只会得到空白页。
    func sessionExists(id: String) -> Bool {
        ((try? stores.sessions.byId(id)) ?? nil) != nil
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
            // 会话标题是落库的数据：建的时候用当时的界面语言，之后不随语言切换重写。
            title: L("新的对话"),
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
    /// 删掉一个人，连同他的聊天、记忆、回声、思考留痕与记忆版本。
    ///
    /// 级联清单一律走 `PersonaDeletion`（Kit，有单测）：0.10.1 这里漏了 `memory_echoes`、
    /// `agent_ticks`、`memory_versions`，也处理「删的是默认人格」——
    /// 删完之后库里一个默认人格都没有，`defaultPersona()` 返回 nil（0.10.2 安全审计 P1-6）。
    func deletePersona(_ persona: PersonaEntity) throws {
        let outcome = PersonaDeletion.delete(
            personaId: persona.id,
            stores: stores,
            memoryRepository: memoryRepository,
            activePersonaId: currentSettings.activePersonaId
        )
        guard outcome.personaDeleted else {
            throw PersonaDeletionError.failed
        }
        // 人已经删了，再决定头像文件要不要删：先删库行、再查引用，只有零引用才删文件。
        // 头像按内容哈希去重，可能与预设头像、我的头像或某条消息的图共用同一个文件；
        // 这里必须走 Kit 的 `deleteIfUnreferenced`，不能在 App 层复制一份引用查询。
        MediaStorage.shared.deleteIfUnreferenced(
            persona.avatarUri,
            references: MediaReferences(stores: stores, settings: settings)
        )
        // 「正在聊的人」若指向被删的人：交还给新的默认人格（一个都不剩时清空）。
        if outcome.activePersonaWasDeleted {
            updateSettings { $0.activePersonaId = outcome.nextDefaultPersonaId }
        }
        // 心跳快照里还有那个人的心情/互动时间：清掉，下一拍重新读。
        heartbeat.clearCache()
        DebugSeeding.log("[persona] 删掉了 \(persona.id)：回声 \(outcome.echoes) 条、思考 \(outcome.ticks) 条、记忆版本 \(outcome.memoryVersions) 条")
    }

    /// 把「没有绑定 API 配置」的会话补上默认配置。
    ///
    /// 什么时候会出现这种会话：从 Android 备份接回来（那边可能没绑）、老版本建的、
    /// 以及「先建会话再配 API」的顺序。不补的话，用户点进会话发消息只会得到一句
    /// 「请先为该会话选择 API 配置」——那是内部错误的措辞，不该甩给用户。
    func bindMissingApiConfigs() {
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
    ///
    /// **重入保护**（0.10.2 审计 P1-2）：冷启动时 `.task` 与 `scenePhase == .active` 会各触发
    /// 一次，两次读到同一个 `lastHeartbeatAt` / `lastConsolidatedAt`，于是都判「该补」。
    /// 现在同一时刻只允许一次，且两次之间至少隔 `CatchUpGuard.defaultMinimumIntervalMillis`。
    func performCatchUp() async {
        let now = DomainClock.nowMillis
        guard catchUpGuard.begin(now: now) else {
            DebugSeeding.log("[bg] 已经在补算了（或 30 秒内刚补过），这次不重复")
            return
        }
        defer { catchUpGuard.finish() }
        let plan = catchUpPlan(isForeground: true)
        DebugSeeding.log("[bg] 回到前台补算：\(plan.note)")
        if plan.shouldConsolidate {
            _ = await runDailyConsolidation()
        }
        if plan.shouldTick {
            _ = await runBackgroundTick()
        }
    }

    // MARK: - 前台 30 秒一拍（0.10.2 审计 P1-3）

    /// 进前台：起定时器。已经在跑就什么都不做（重复调用是安全的）。
    ///
    /// 为什么是 `Task` + `Task.sleep` 而不是 `Timer`：它跟着 `scenePhase` 启停，
    /// 进后台时 `cancel()` 立刻生效；`Timer` 在后台会被系统挂着，开销和语义都更差。
    func startForegroundPulse() {
        isForeground = true
        serverForeground.set(true)
        guard foregroundPulseTask == nil else { return }
        let hasConfig = resolvedChatConfig() != nil
        let hasPersona = currentPersona() != nil
        guard ForegroundPulse.shouldRun(isActive: true, hasApiConfig: hasConfig, hasPersona: hasPersona) else {
            DebugSeeding.log("[bg] 前台心跳没起来：\(hasConfig ? "还没有可用人格" : "还没有 API 配置")")
            return
        }
        foregroundPulseTask = Task { [weak self] in
            while !Task.isCancelled {
                let interval = self?.heartbeat.checkIntervalMillis() ?? HeartbeatController.beatIntervalMillis
                do {
                    try await Task.sleep(nanoseconds: UInt64(max(interval, 0)) * 1_000_000)
                } catch {
                    return
                }
                guard let self, !Task.isCancelled else { return }
                // `scenePhase` 已经变了但 `onChange` 还没跑完时，别多跑一拍。
                guard self.isForeground else { return }
                _ = await self.runBackgroundTick()
            }
        }
        DebugSeeding.log("[bg] 前台心跳已启动：每 \(foregroundPulseIntervalMillis / 1000) 秒一拍")
    }

    /// 进后台：停定时器（系统会挂起进程，留着只是白占资源）。
    func stopForegroundPulse() {
        isForeground = false
        serverForeground.set(false)
        foregroundPulseTask?.cancel()
        foregroundPulseTask = nil
        // 回到前台时重新读一次真实状态（心情、最后互动），别拿几分钟前的快照掷骰子。
        heartbeat.clearCache()
        DebugSeeding.log("[bg] 前台心跳已停止")
    }

    /// 当前一拍的间隔（低电量 2 分钟 / 平时 30 秒）。日志与界面用。
    var foregroundPulseIntervalMillis: Int64 { heartbeat.checkIntervalMillis() }

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
        // 「昨日整理」一天只该有一次：BGProcessingTask 与回前台补算会撞车
        //（两次整理 = 两次模型调用 + 两条 memory_consolidations 记录）。
        guard !isConsolidating else {
            DebugSeeding.log("[bg] 已经在整理记忆了，这次跳过")
            return false
        }
        guard let resolved = resolvedChatConfig() else {
            DebugSeeding.log("[bg] 还没有 API 配置，这次不整理记忆")
            return false
        }
        guard let persona = currentPersona() else { return false }
        isConsolidating = true
        defer { isConsolidating = false }
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
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0.10.0"
    }
}


/// 装配期的一个小盒子：`AgentLoop` 的回调要读 `chat`，而 `chat` 在它之后才建好。
private final class ChatServiceBox {
    var chat: ChatService?
}

/// 「App 在前台吗」的线程安全镜像（服务端事件流会在后台线程问它）。
final class ServerForegroundFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = true

    func set(_ active: Bool) {
        lock.lock()
        value = active
        lock.unlock()
    }

    var isActive: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// `init` 里不能捕获 `self`（会触发「used before being initialized」）。
/// 用一个小盒子在装配完成后回填：闭包捕获盒子（无 `self`），盒子在 init 末尾拿到 self。
final class AppEnvironmentBox {
    weak var env: AppEnvironment?
}
