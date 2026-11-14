import Foundation
import UIKit

/// 聊天页的状态机。
///
/// 为什么要有这一层（而不是把逻辑塞进 `ChatScreen` 的 `@State`）：
/// - 一轮回复是「先把用户那句话落库 → 再消费 `AsyncThrowingStream` → 最后重读消息」，
///   中途还要能被停止、能在离开页面后继续跑完，这些都不是「视图状态」而是「会话状态」；
/// - 消息列表、流式正文、动作痕迹、心情、「他此刻」是五份互相独立的状态，
///   混在 View 里最容易出现「正在输入的字盖住了历史消息」这类互相踩的问题；
/// - 与 SwiftUI 无关，换成 UIKit 也能直接用。
///
/// 它**不实现任何能力**：落库、拼提示词、流式解析都在 `MyFriendKit` 里，
/// 这里只做「读 → 交给服务 → 把事件翻译成界面状态」。
@MainActor
final class ChatViewModel: ObservableObject {

    // MARK: - 会话数据

    @Published private(set) var session: ChatSessionEntity?
    @Published private(set) var persona: PersonaEntity?
    @Published private(set) var messages: [MessageEntity] = []
    @Published private(set) var mood = MoodState()
    @Published private(set) var presence = Presence()
    /// 首屏读完之前不画空会话引导，免得「先闪一下空状态再出现历史」。
    @Published private(set) var loaded = false
    /// 窗口前面还有没有更早的消息（聊天页的「加载更早」）。
    @Published private(set) var hasMoreHistory = false
    /// 正在取更早的一页：加载中不重复触发（滚到顶部时 `onAppear` 会反复喊）。
    @Published private(set) var isLoadingEarlier = false
    /// 有没有可用的 API 配置：没有就禁用输入栏并提示去设置。
    @Published private(set) var hasApiConfig = false

    // MARK: - 从搜索结果定位

    /// 要滚过去的那条消息（搜索结果跳进来时设定；界面用它在滚动视图里 `scrollTo`）。
    @Published private(set) var scrollTargetId: String?
    /// 正在短暂高亮的那条消息（约 2 秒后自己淡出，与 Android 的「闪一下」一致）。
    @Published private(set) var highlightedMessageId: String?

    // MARK: - 生成中

    @Published private(set) var isGenerating = false
    /// 已经点了停止、正在等这一轮收尾（避免连点）。
    @Published private(set) var isStopping = false
    /// 流式正文的**全文**（`ChatEvent.delta` 传的是全文，不是增量）。
    @Published private(set) var streamingText = ""
    @Published private(set) var streamingTraces: [ToolTraceItem] = []

    /// 输入框里的草稿。视图直接 `$model.draft` 双向绑定。
    @Published var draft = ""

    /// 已经压好、存在沙盒里，等着跟这句话一起发出去的图片。
    /// 最多 `AttachmentsCodec.maxImagesPerMessage` 张（与 Android 一致）。
    @Published private(set) var pendingAttachments: [MessageAttachment] = []
    /// 正在把相册里选的图压缩落盘（期间发送按钮转圈，避免发出半份附件）。
    @Published private(set) var isPreparingAttachments = false

    /// 一句会自己消失的提示（已复制、生成失败……）。不做成 alert：聊天里不该被弹窗打断。
    @Published private(set) var toast: String?
    /// 上一次发送被拒（没落库）的原因。非空时输入框上方显示一条「草稿还在，可以重试」。
    @Published private(set) var sendFailure: String?

    /// 上一轮他为什么没说话（睡着 / 免打扰 / 主动晚点回）。界面不解释、不弹窗，
    /// 只在「已读」这一件事上按它决定要不要显示（§四：不显示任何「他可能不想回」的解释）。
    @Published private(set) var lastNoReply: ChatNoReplyReason?

    // MARK: - 分页

    /// 首屏与每一页的条数。语义与 Android 侧的窗口化一致（见 `MessagePaging`）。
    static let pageSize = MessagePaging.pageSize

    // MARK: - 内部

    private var env: AppEnvironment?
    /// 这张会话在库里一共多少条（不是窗口里多少条）：用来判断「有没有新消息进来」。
    private var totalMessageCount = 0
    private var sessionId = ""
    private var generationTask: Task<Void, Never>?
    /// 「他此刻」/ 心情的重算（30 秒一拍，只在页面可见且 App 在前台时跑）。
    private var pulseTask: Task<Void, Never>?
    /// 消息轮询：Kit 是拉取式的（没有 Room 的 Flow），页面上要自己按需重读。
    private var messagePulseTask: Task<Void, Never>?
    /// 页面是否在屏幕上（`onAppear` 与 `onDisappear` 之间）。
    ///
    /// 0.11.0 审计 P2-3：两条轮询 Task 原来只在 `leave()` 时停，页面一直开着就每 4 秒查一次库。
    /// 现在「可见 + 前台」才有轮询，节奏交给 `ChatPollingPolicy`（纯逻辑，有单测）。
    private var isVisible = false
    /// App 是否在前台（`ChatScreen` 转发的 `scenePhase`）。
    private var sceneActive = true
    private var toastTask: Task<Void, Never>?
    /// 停止的兜底：底层流万一不响应取消，也不让界面永远停在「正在输入」。
    private var stopFallback: Task<Void, Never>?
    /// 定位高亮的淡出计时器。
    private var highlightTask: Task<Void, Never>?

    // MARK: - 进 / 出页面

    /// 绑定依赖（只做一次；`@StateObject` 在页面第一次出现时调用）。
    func bind(env: AppEnvironment, sessionId: String) {
        self.env = env
        self.sessionId = sessionId
    }

    /// 页面出现：标记「这一轮会话正开着」（未读计数据此归零），重读消息，开始 30 秒脉冲。
    ///
    /// 每次 `onAppear` 都会走到这里 —— 从内置浏览器、记忆页返回时正好需要刷新一遍，
    /// 因为那边可能改过消息（比如清空上下文）或人格。
    func enter() {
        guard let env else { return }
        isVisible = true
        env.chat.setOpenSession(sessionId)
        // 只追加「窗口最后一条之后」的新消息：已加载的历史不该因为重新出现页面被挤掉（P2-5）。
        reloadMessages(preservingLoadedRange: true)
        refreshContext()
        loaded = true
        restartPulses()
    }

    /// 页面离开：把「正开着」的标记撤掉，停掉脉冲。
    ///
    /// 刻意**不**取消正在跑的生成：他正说到一半的那句话不该因为用户退出页面被掐断；
    /// `ChatService` 自己会把结果落库（此时会话已不是「开着」的，未读会 +1，首页能看到）。
    func leave() {
        isVisible = false
        env?.chat.setOpenSession(nil)
        pulseTask?.cancel()
        pulseTask = nil
        messagePulseTask?.cancel()
        messagePulseTask = nil
        stopFallback?.cancel()
        stopFallback = nil
        highlightTask?.cancel()
        highlightTask = nil
    }

    // MARK: - 读

    /// 重读消息窗口。
    ///
    /// - Parameter preservingLoadedRange: `true` 只把「窗口最后一条之后」的新消息追加进来，
    ///   已加载的历史一条都不少（心跳写消息、流式结束、页面重新出现都走它）；
    ///   `false` 以「最近一屏（不小于已加载条数）」重置窗口 —— 删除消息 / 清空上下文 /
    ///   重新生成这类**会改动历史**的动作必须走它，否则窗口里会留着已经不存在的消息。
    func reloadMessages(preservingLoadedRange: Bool = false) {
        guard let env else { return }

        // 0.11.1 审计 P1-3：先确认**会话还在不在**。`try?` 会把 nil 拍平成同一条分支，
        // 所以这里显式解一层：读不到就说明会话被别处删了（清空全部聊天 / 删人格 / 替换导入）。
        guard let fresh = (try? env.stores.sessions.byId(sessionId)) ?? nil else {
            handleSessionGone()
            return
        }
        if session != fresh { session = fresh }
        // 进页面就把未读清零：Android 端也是「打开会话即已读」。
        if fresh.unreadCount != 0 {
            var current = fresh
            current.unreadCount = 0
            try? env.stores.sessions.upsert(current)
            session = current
        }
        if let personaId = session?.personaId {
            persona = ((try? env.stores.personas.byId(personaId)) ?? nil) ?? persona
        }

        // **窗口化**：只读最近一屏（首屏 60 条；已经上滑加载过的页也保住，不把人拽回顶上）。
        // `messages.bySession` 是整段全量读取，长会话下是这一页最大的一块内存。
        if !preservingLoadedRange || messages.isEmpty {
            resetWindow(env)
            return
        }

        // 0.11.1 审计 P1-3：「只追加」必须知道外部删除。拿库里的两个**轻量**数字
        // （总数 + 最新一条 id）做判定：条数变少 / 窗口比库里还多 → 重置窗口；
        // 会话没了 → 清空并停轮询（`ChatWindowPolicy` 是纯函数，有单测）。
        let storedTotal = (try? env.stores.messages.count(sessionId: sessionId)) ?? nil
        let latestId = ((try? env.stores.messages.latest(sessionId: sessionId)) ?? nil)?.id
        let plan = ChatWindowPolicy.plan(
            sessionExists: true,
            storedTotal: storedTotal,
            loadedCount: messages.count,
            loadedTotalAtLastCheck: totalMessageCount,
            latestIdMatched: latestId == messages.last?.id
        )
        switch plan {
        case .sessionGone:
            handleSessionGone()
            return
        case .keep:
            break
        case .reset:
            resetWindow(env)
            return
        case .append:
            if let last = messages.last { appendNewerMessages(after: last) }
        }
        #if DEBUG
        DebugSeeding.log("[chat] window loaded=\(messages.count) total=\(totalMessageCount) hasMore=\(hasMoreHistory)")
        #endif
    }

    /// 以「最近一屏（不小于已加载条数）」重置窗口。
    private func resetWindow(_ env: AppEnvironment) {
        let window = max(Self.pageSize, messages.count)
        messages = ((try? env.stores.messages.recent(sessionId: sessionId, limit: window)) ?? [])
        totalMessageCount = ((try? env.stores.messages.count(sessionId: sessionId)) ?? messages.count)
        refreshHasMore()
        #if DEBUG
        DebugSeeding.log("[chat] window reset loaded=\(messages.count) total=\(totalMessageCount)")
        #endif
    }

    /// 会话已经不存在：回到空态并停掉两条轮询（0.11.1 审计 P1-3）。
    ///
    /// 不弹 toast：这条路径常常是「用户从设置页清空了全部聊天，返回时页面还在」，
    /// 静默变成空态比弹一句「对话不在了」更贴近「他确实不在了」这件事。
    private func handleSessionGone() {
        session = nil
        persona = nil
        messages = []
        totalMessageCount = 0
        hasMoreHistory = false
        isLoadingEarlier = false
        // 停轮询：会话都不在了，每 20 秒重试只是白读库。
        messagePulseTask?.cancel()
        messagePulseTask = nil
        pulseTask?.cancel()
        pulseTask = nil
        #if DEBUG
        DebugSeeding.log("[chat] session gone, 清空窗口并停轮询")
        #endif
    }

    /// 把「`cursor` 之后」的新消息接在已加载区间后面（0.11.0 P2-5）。
    ///
    /// 窗口以已加载区间的最早一条为锚：新消息只往后追加，最早那一页不会被挤掉、
    /// 滚动位置也不跳（0.10.2 是「重读最后 N 条」，来一条新消息就把最旧的挤出去）。
    private func appendNewerMessages(after cursor: MessageEntity) {
        guard let env else { return }
        let newer = (try? env.stores.messages.pageAfter(
            sessionId: sessionId,
            createdAt: cursor.createdAt,
            id: cursor.id,
            limit: MessagePaging.maxLoadedMessages
        )) ?? []
        if !newer.isEmpty {
            messages = MessagePaging.appendingNewer(newer, to: messages)
        }
        totalMessageCount = ((try? env.stores.messages.count(sessionId: sessionId)) ?? messages.count)
        refreshHasMore()
    }

    /// 窗口前面还有没有更早的消息。
    ///
    /// 用 `countBefore` 而不是「这一页满 60 就算还有」：恰好 60 条时后者会多显示一次
    /// 「加载更早」，点下去发现没有——那是把边界判断推给用户。
    private func refreshHasMore() {
        guard let env, let first = messages.first else {
            hasMoreHistory = false
            return
        }
        let older = (try? env.stores.messages.countBefore(
            sessionId: sessionId, createdAt: first.createdAt, id: first.id
        )) ?? 0
        hasMoreHistory = older > 0
    }

    /// 往上翻到顶：再取 60 条更早的，接在窗口前面。
    ///
    /// 三条保护：已经没有更早的了、正在取（滚到顶时 `onAppear` 会连喊几次）、
    /// 取回来的 id 与窗口里已有的重复（同一毫秒落了两条消息时的兜底）。
    func loadEarlierMessages() {
        guard let env, hasMoreHistory, !isLoadingEarlier, messages.first != nil else { return }
        isLoadingEarlier = true
        #if DEBUG
        // 分页验收：让「正在加载更早…」这一帧停留得久一点，好让 simctl 截到图。
        if ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SLOW_PAGING"] == "1" {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                self.appendEarlierPage(env: env)
            }
            return
        }
        #endif
        appendEarlierPage(env: env)
    }

    /// 取一页更早的接在窗口前面（调试慢速路径也复用它，逻辑只有一份）。
    private func appendEarlierPage(env: AppEnvironment) {
        guard let first = messages.first else {
            isLoadingEarlier = false
            return
        }
        let older = ((try? env.stores.messages.pageBefore(
            sessionId: sessionId,
            createdAt: first.createdAt,
            id: first.id,
            limit: Self.pageSize
        )) ?? [])
        messages = MessagePaging.prepend(older, to: messages)
        isLoadingEarlier = false
        refreshHasMore()
        #if DEBUG
        DebugSeeding.log("[chat] load earlier +\(older.count) loaded=\(messages.count) hasMore=\(hasMoreHistory)")
        #endif
    }

    // MARK: - 从搜索结果定位到某条消息

    /// 把目标消息所在的那一页读进窗口，然后让界面滚过去 + 短暂高亮。
    ///
    /// 目标可能比首屏那 60 条早很多，所以这里复用 0.10.1 的游标分页：
    /// 一页页往前取，直到它进入窗口（`MessagePaging.window(reaching:)` 里那段循环是纯逻辑，
    /// 单测覆盖了「目标在很前面 / 目标已删除 / 加载器不给新页」三种边界）。
    ///
    /// 为什么定位之后不再跟随到底部：用户点的是「某条老消息」，把他拽到最新一句等于白点。
    /// `autoFollow` 由界面负责关掉（`ChatScreen` 在 onAppear 里做）。
    func focus(on messageId: String) {
        guard let env, !messageId.isEmpty else { return }
        let result = MessagePaging.window(reaching: messageId, from: messages) { [weak self] oldest, _ in
            guard let self, let oldest else { return nil }
            return (try? env.stores.messages.pageBefore(
                sessionId: self.sessionId,
                createdAt: oldest.createdAt,
                id: oldest.id,
                limit: Self.pageSize
            )) ?? []
        }
        messages = result.messages
        refreshHasMore()
        #if DEBUG
        DebugSeeding.log("[chat] 定位 \(messageId)：窗口 \(messages.count) 条（往前 \(result.pagesLoaded) 页）found=\(result.found) hasMore=\(hasMoreHistory)")
        #endif
        guard result.found else {
            showToast(L("没找到这条消息，可能已经被删掉了"))
            return
        }
        scrollTargetId = messageId
        highlightedMessageId = messageId
        highlightTask?.cancel()
        highlightTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: Self.highlightDurationNanos)
            } catch {
                return
            }
            guard let self, !Task.isCancelled else { return }
            self.highlightedMessageId = nil
        }
    }

    /// 高亮持续多久（2 秒，之后由界面的动画淡出）。
    static let highlightDurationNanos: UInt64 = 2_000_000_000

    /// 心情与「他此刻」都是**只读推导**：心情会让时间冲淡（`MoodRepository` 顺手落库），
    /// presence 什么都不写。30 秒重算一次，与 Android 的 Flow 语义一致。
    func refreshContext() {
        guard let env else { return }
        hasApiConfig = env.resolvedChatConfig() != nil
        guard let persona else { return }
        mood = env.moodRepository.current(personaId: persona.id)
        presence = env.presence.current(personaId: persona.id)
    }

    // MARK: - 轮询（0.11.0 审计 P2-3）

    /// 页面可见性与前台状态变了：按新的计划重启两条轮询。
    ///
    /// `ChatScreen` 的 `onAppear` / `onDisappear` / `scenePhase` 都走这里（以及 `enter()`）。
    func setSceneActive(_ active: Bool) {
        guard sceneActive != active else { return }
        sceneActive = active
        if active {
            // 回到前台：这段时间他可能自己写了点什么（心跳 / 主动唤醒），先核一次再排下一拍。
            reloadMessages(preservingLoadedRange: true)
            refreshContext()
        }
        restartPulses()
    }

    /// 按 `ChatPollingPolicy` 重排两条轮询。
    ///
    /// 规则（纯逻辑在 Kit，有单测）：
    /// - 页面不可见 / App 在后台：两路都停；
    /// - 正在流式输出：消息轮询停（流是事件源，`finishGeneration` 会统一重读），心情仍然 30 秒一拍；
    /// - 停在页面上发呆：消息轮询 20 秒一拍（保底，不是 0.10.2 的每 4 秒）。
    private func restartPulses() {
        pulseTask?.cancel()
        pulseTask = nil
        messagePulseTask?.cancel()
        messagePulseTask = nil
        let plan = ChatPollingPolicy.plan(
            isVisible: isVisible, sceneActive: sceneActive, isGenerating: isGenerating
        )
        if plan.contextIntervalNanos != nil { startContextPulse() }
        if plan.messageIntervalNanos != nil { startMessagePulse() }
    }

    /// 心情与「他此刻」的脉冲：每 30 秒重算一次（只在可见 + 前台时跑）。
    private func startContextPulse() {
        pulseTask = Task { [weak self] in
            while true {
                guard let self, !Task.isCancelled else { return }
                let plan = ChatPollingPolicy.plan(
                    isVisible: self.isVisible, sceneActive: self.sceneActive, isGenerating: self.isGenerating
                )
                guard let interval = plan.contextIntervalNanos else { return }
                do {
                    try await Task.sleep(nanoseconds: interval)
                } catch {
                    return
                }
                guard !Task.isCancelled else { return }
                self.refreshContext()
            }
        }
    }

    /// 每 20 秒核一次「库里是不是多了消息」（只在可见 + 前台、且不在流式输出时跑）。
    ///
    /// 为什么还需要它：这条会话的消息**不一定都是从这个页面发出去的** ——
    /// 心跳 / 主动唤醒 / 回忆重写都可能在他那边往当前会话里写一条；
    /// Android 靠 Room 的 Flow 自动刷，iOS 的 Kit 是拉取式的（见 `PresenceRepository` 的注释），
    /// 所以界面自己按需重读。只比一次「最新一条的 id + 条数」，没有变化就不动状态，不触发重绘。
    private func startMessagePulse() {
        messagePulseTask = Task { [weak self] in
            while true {
                guard let self, !Task.isCancelled else { return }
                let plan = ChatPollingPolicy.plan(
                    isVisible: self.isVisible, sceneActive: self.sceneActive, isGenerating: self.isGenerating
                )
                guard let interval = plan.messageIntervalNanos else { return }
                do {
                    try await Task.sleep(nanoseconds: interval)
                } catch {
                    return
                }
                guard !Task.isCancelled else { return }
                guard ChatPollingPolicy.plan(
                    isVisible: self.isVisible, sceneActive: self.sceneActive, isGenerating: self.isGenerating
                ).messageIntervalNanos != nil else { continue }
                self.reloadMessagesIfChanged()
            }
        }
    }

    /// 轮询一拍：交给 `reloadMessages` 的「只追加 + 外部删除判定」统一处理。
    ///
    /// 0.11.1 审计 P1-3：原来这里只看「条数或最新 id 变了没」，而**会话被删**这一种变化
    /// 恰好会让比较条件永远成立（`latest(sessionId:)` 返回 nil ≠ 窗口最后一条 id），
    /// 于是每 20 秒做一次 count + pageAfter 的无效重试。现在会话没了会清空窗口并停轮询。
    private func reloadMessagesIfChanged() {
        reloadMessages(preservingLoadedRange: true)
    }

    // MARK: - 发送

    var canSend: Bool {
        guard hasApiConfig, !isGenerating, !isPreparingAttachments else { return false }
        return !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !pendingAttachments.isEmpty
    }

    // MARK: - 附件（图片）

    /// 加进待发送的图片；超过上限的部分直接丢掉（界面只让选 4 张，这里是兜底）。
    func addAttachments(_ attachments: [MessageAttachment]) {
        let images = attachments.filter { $0.isImage }
        guard !images.isEmpty else { return }
        let room = max(0, AttachmentsCodec.maxImagesPerMessage - pendingAttachments.count)
        pendingAttachments.append(contentsOf: images.prefix(room))
    }

    func removeAttachment(at index: Int) {
        guard pendingAttachments.indices.contains(index) else { return }
        let attachment = pendingAttachments.remove(at: index)
        // 用户撤掉的那张不该留在沙盒里当垃圾。
        MediaStorage.shared.delete(attachment)
    }

    func clearPendingAttachments() {
        MediaStorage.shared.delete(pendingAttachments)
        pendingAttachments.removeAll()
    }

    func setPreparingAttachments(_ preparing: Bool) {
        isPreparingAttachments = preparing
    }

    func send() {
        guard let env, !isGenerating, !isPreparingAttachments else { return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let attachments = pendingAttachments
        guard !text.isEmpty || !attachments.isEmpty else { return }
        // 没有 API 配置就不发一个注定失败的请求：禁用输入栏 + 一句引导。
        // 会话绑定哪个配置由装配层负责（`AppEnvironment` 建会话时写 `apiConfigId`）。
        guard env.resolvedChatConfig() != nil else {
            hasApiConfig = false
            showToast(L("先去设置里配一个 API"))
            return
        }
        // 只有「真的落库了」才清空输入状态（0.10.2 审计 P1-10）。
        // 消息过长 / 会话被删 / 配置没了都发生在落库之前：这时草稿与图片原样留着，
        // 用户点一下「重试」就能接着发；附件引用没变、文件还在磁盘上，不会留下孤儿图。
        switch env.chat.sendChecked(sessionId: sessionId, text: text, attachments: attachments) {
        case .started(let stream):
            sendFailure = nil
            draft = ""
            pendingAttachments.removeAll()
            // `ChatService` 会**先**把用户这句话落库、再返回流；立刻追加一次，
            // 自己说的话就马上出现在屏幕上，而不是等模型开口。
            // 用「追加」而不是重读最后 N 条：已加载的历史不能被自己这一句挤掉（P2-5）。
            reloadMessages(preservingLoadedRange: true)
            consume(stream)
        case .rejected(let error):
            let message = Self.describe(error)
            sendFailure = message
            showToast(message)
        }
    }

    /// 撤掉「发送失败」的提示（用户自己关掉，或者下一次发送成功时自动清）。
    func dismissSendFailure() {
        sendFailure = nil
    }

    /// 重新生成：`ChatService` 会删掉最后一条助手消息再跑一轮。
    func regenerate() {
        guard let env, !isGenerating else { return }
        guard env.resolvedChatConfig() != nil else {
            hasApiConfig = false
            showToast(L("先去设置里配一个 API"))
            return
        }
        let stream = env.chat.regenerate(sessionId: sessionId)
        reloadMessages()
        consume(stream)
    }

    /// 停止：交给 `ChatService`，它会把已经说出来的半句落成 `STOPPED` 消息再结束流。
    ///
    /// 不直接 `cancel` 自己这一路消费：那样等于把「落成半句」也一起掐掉，
    /// 界面上会看到气泡凭空消失，而不是「他话说了一半」。
    func stop() {
        guard let env, isGenerating, !isStopping else { return }
        isStopping = true
        env.chat.stop(sessionId: sessionId)
        stopFallback?.cancel()
        stopFallback = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: 2_000_000_000)
            } catch {
                return
            }
            guard let self, !Task.isCancelled, self.isGenerating else { return }
            self.generationTask?.cancel()
            self.finishGeneration()
        }
    }

    // MARK: - 消费流

    private func consume(_ stream: AsyncThrowingStream<ChatEvent, Error>) {
        generationTask?.cancel()
        isGenerating = true
        // 开始流式输出：按 `ChatPollingPolicy` 立刻把消息轮询停掉（流是事件源）。
        restartPulses()
        isStopping = false
        lastNoReply = nil
        streamingText = ""
        streamingTraces = []
        stopFallback?.cancel()
        stopFallback = nil

        // 强引用 self：一轮回复正在跑的时候，即使页面被 pop 掉也要把它读完、写进库，
        // 否则用户会得到一条「永远没说完的消息」。任务结束即释放，不会长期泄漏。
        generationTask = Task { [self] in
            var failure: String?
            do {
                for try await event in stream {
                    apply(event)
                }
            } catch {
                if !Task.isCancelled { failure = Self.describe(error) }
            }
            finishGeneration()
            if let failure { showToast(failure) }
        }
    }

    private func apply(_ event: ChatEvent) {
        switch event {
        case .delta(let text):
            streamingText = text
        case .toolStarted(let item):
            streamingTraces.append(item)
        case .toolFinished(let item):
            // `ChatService` 先发一个只带名字的「开始」，跑完再发完整的一条：
            // 同一个工具只该算一次，否则摘要会变成「联网搜索 2 次」。
            if let index = streamingTraces.lastIndex(where: { $0.name == item.name && $0.title == item.title }) {
                streamingTraces[index] = item
            } else {
                streamingTraces.append(item)
            }
        case .done:
            // 落库后的完整消息在 `finishGeneration` 里统一重读，避免两条流各自拼状态。
            break
        case .noReply(let reason):
            // 他这一轮没说话：没有助手消息要落库，界面只重读一次消息（用户气泡会显示「已读」）。
            lastNoReply = reason
            streamingText = ""
            streamingTraces = []
        }
    }

    private func finishGeneration() {
        generationTask = nil
        isGenerating = false
        isStopping = false
        streamingText = ""
        streamingTraces = []
        stopFallback?.cancel()
        stopFallback = nil
        reloadMessages(preservingLoadedRange: true)
        refreshContext()
        // 流结束：消息轮询重新按计划起来（生成中它是停的）。
        restartPulses()
    }

    // MARK: - 消息操作

    /// 删除一条消息，并把会话的「最后一句预览」重算一遍。
    func delete(_ message: MessageEntity) {
        guard let env else { return }
        do {
            try env.stores.messages.delete(id: message.id)
            // 图片跟着这条消息一起离开：留着文件只占空间，而且在文件 App 里也看不到它。
            MediaStorage.shared.deleteAttachments(in: message.attachmentsJson)
            try refreshSessionPreview(env)
            reloadMessages()
            showToast(L("已删除这条消息"))
        } catch {
            showToast(Lf("删除失败：%@", error.localizedDescription))
        }
    }

    /// 清空这段对话的上下文（人格与长期记忆都不动）。
    func clearContext() {
        guard let env, !isGenerating else { return }
        do {
            // 先把这段对话里的图片文件找出来，消息删掉之后再删文件。
            let images = ((try? env.stores.messages.bySession(sessionId)) ?? [])
                .flatMap { AttachmentsCodec.decode($0.attachmentsJson) }
            try env.stores.messages.delete(where: "sessionId = ?", [.text(sessionId)])
            MediaStorage.shared.delete(images)
            try refreshSessionPreview(env)
            reloadMessages()
            showToast(L("上下文已清空"))
        } catch {
            showToast(Lf("清空失败：%@", error.localizedDescription))
        }
    }

    func copy(_ text: String) {
        UIPasteboard.general.string = text
        showToast(L("已复制"))
    }

    /// 会话摘要栏（首页那一行预览）跟着消息走。
    ///
    /// 这段与 `ChatService` 里私有的 `refreshPreview` 同义 —— 删除/清空消息后必须重算，
    /// 否则首页会指着一个已经不存在的「最后一句」。理想情况是它作为公开 API 暴露出来（见报告）。
    private func refreshSessionPreview(_ env: AppEnvironment) throws {
        let latest = try env.stores.messages.latest(sessionId: sessionId)
        let flattened = (latest?.content ?? "")
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let preview: String? = flattened.isEmpty
            ? (latest?.status == .error ? L("回复失败") : nil)
            : String(flattened.prefix(100))
        let updatedAt = DomainClock.nowMillis
        // 0.11.1 审计 P2-15：定向 UPDATE 三列，不再「读整行 → 改 → 整行 REPLACE」。
        // 后者会把这段时间里心跳/主动唤醒写入的 `unreadCount` 回滚成旧快照
        // （0.10.2 P1-1 的同一类问题在 UI 路径上的残留）。
        let stillExists = try env.stores.sessions.updatePreview(
            id: sessionId, preview: preview, lastMessageAt: latest?.createdAt, updatedAt: updatedAt
        )
        if !stillExists {
            handleSessionGone()
            return
        }
        if var current = session {
            current.lastMessagePreview = preview
            current.lastMessageAt = latest?.createdAt
            current.updatedAt = updatedAt
            session = current
        }
    }

    // MARK: - 提示

    func showToast(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        toast = trimmed
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: 2_400_000_000)
            } catch {
                return
            }
            guard let self, !Task.isCancelled else { return }
            self.toast = nil
        }
    }

    private static func describe(_ error: Error) -> String {
        if let api = error as? ApiException { return api.message }
        let localized = (error as NSError).localizedDescription
        return localized.isEmpty ? L("生成失败") : localized
    }

    // MARK: - 列表条目（日期分隔条 + 消息）

    /// 消息列表的一行：要么是「今天 / 昨天 / 3月5日」的分隔条，要么是一条消息。
    struct Row: Identifiable {
        enum Content {
            case day(String)
            case message(MessageEntity)
        }

        let id: String
        let content: Content
    }

    /// 跨天的第一条消息前面插一条日期分隔条（与 Android 的 `ChatDisplayItem` 一一对应）。
    var rows: [Row] {
        var result: [Row] = []
        var previousAt: Int64?
        for message in messages {
            if previousAt == nil || !Self.isSameDay(previousAt!, message.createdAt) {
                let label = MFDate.day(message.createdAt)
                result.append(Row(id: "day-\(message.id)", content: .day(label)))
            }
            result.append(Row(id: message.id, content: .message(message)))
            previousAt = message.createdAt
        }
        return result
    }

    /// 这条用户消息要不要显示「已读」（§2.3、§四）。
    ///
    /// 三个条件缺一不可：
    /// 1. 它是用户发的，并且 `readAt` 有值（模型真的看到过它）；
    /// 2. 它是**最后一条消息**（后面还没有他的回复）——否则每条消息都挂一个「已读」很吵；
    /// 3. 此刻不是「正在生成」：他正在打字时显示「已读」会让人以为他看了却不回。
    func showsReadBadge(for message: MessageEntity) -> Bool {
        guard message.role == .user, message.readAt != nil, !isGenerating else { return false }
        guard let last = messages.last else { return false }
        return last.id == message.id
    }

    /// 最后一条助手消息的 id：只有它才值得「重新生成」（`ChatService` 删的也是最后一条）。
    var latestAssistantId: String? {
        messages.last(where: { $0.role == .assistant })?.id
    }

    private static func isSameDay(_ lhs: Int64, _ rhs: Int64) -> Bool {
        let calendar = Calendar.current
        return calendar.isDate(
            Date(timeIntervalSince1970: Double(lhs) / 1000),
            inSameDayAs: Date(timeIntervalSince1970: Double(rhs) / 1000)
        )
    }
}
