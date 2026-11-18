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
/// 它**不实现任何能力**：落库、拼提示词、流式解析都在 `YapKit` 里，
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
    /// 已加载的窗口上面还有没有更早的一块（聊天页的「加载更早」）。
    @Published private(set) var hasMoreHistory = false
    /// 已加载的窗口下面还有没有更新的一块（滚到底时加载下一块，v11 新增的向下加载）。
    @Published private(set) var hasMoreNewer = false
    /// 正在取更早的一块：加载中不重复触发（滚到顶部时 `onAppear` 会反复喊）。
    @Published private(set) var isLoadingEarlier = false
    /// 正在取更新的一块（同上，滚到底会连喊几次）。
    @Published private(set) var isLoadingNewer = false
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

    /// 输入框上方那行「他睡着了（08:00 醒）；连着发几条可以叫醒他」；nil = 不显示（0.12 续 7）。
    ///
    /// 出现条件全在 `ChatNoReplyReason.sleepHintWakeText`：只有「睡着、还没到阈值」被静默，
    /// 且**此刻**他还在睡时才有值。所以他睡醒之后（`presence` 每 30 秒重算一次，SwiftUI 跟着重画）
    /// 这一行自己就没了；回了话则由 `.done` 清空 `lastNoReply`。
    ///
    /// 它不是消息、不写库、也不是弹窗：只是界面对「已读但没回」的一句解释，
    /// 和顶栏那行 presence（「睡着了 · 08:00 醒」）说的不是同一件事——那是他的状态，
    /// 这是**这一轮为什么没有回复**。
    var sleepHint: String? {
        guard let wakeAt = lastNoReply?.sleepHintWakeText(persona: persona) else { return nil }
        return Lf("他睡着了（%@ 醒）；连着发几条可以叫醒他", wakeAt)
    }

    // MARK: - 分页（v11：一页 = 一块，规范 `spec/messages-blocks/README.md` §3）

    // MARK: - 内部

    private var env: AppEnvironment?
    /// 这张会话在库里一共多少条（不是窗口里多少条）：用来判断「有没有新消息进来」。
    private var totalMessageCount = 0
    private var sessionId = ""
    /// 窗口里已经加载了哪几块（闭区间；块号可能因为删空而有空洞）。
    ///
    /// 区块分页只认块号：「上面还有没有」= 比 `lowerBound` 更旧的块存不存在，
    /// 「下面还有没有」= 比 `upperBound` 更新的块存不存在。不靠偏移量、也不数条数。
    private var loadedBlockRange: ClosedRange<Int>?
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
    /// 这一轮是不是真的**收到过 `done`**（助手消息已落库）。
    ///
    /// 只给云端模式下的人格进化触发用：`finishGeneration()` 在「他这一轮没说话」
    /// （`.noReply`）、用户点停止、网络失败时也会走到，那些都不算「一轮回复完成」，
    /// 不该顺手评估一次人格。本地模式不看它（`ChatService` 落库后自己触发）。
    private var completedReply = false

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
        // 服务端模式：内容在服务端，进页面先去拉一次「最新一块」（并按需标已读、补发队列）。
        // 拉完再重读一次库，界面上看到的就是刚下发的历史；拉失败就照旧显示缓存。
        if env.serverModeActive {
            Task { [weak self] in
                await env.prepareServerChat(sessionId: sessionId)
                guard let self, !Task.isCancelled, self.isVisible else { return }
                self.reloadMessages(preservingLoadedRange: true)
                self.refreshContext()
            }
        }
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

        // **区块化窗口**：首屏只读最新一块，已经上滑/下滑加载过的块也保住，不把人拽回顶上。
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

    /// 重置窗口：**只加载最新那一块**（首屏 1 块，规范 §3 Initial load）。
    ///
    /// 为什么从「最近 60 条」改成「最新块」：块是存储层的物理分页单位，
    /// 首屏大小不再由界面常量决定 —— 会话短时一块就是全部，长时一块最多 200 条，
    /// 读多少行与会话总长无关（`(sessionId, blockIndex)` 上有索引）。
    private func resetWindow(_ env: AppEnvironment) {
        if let window = ((try? env.stores.messages.blocksAround(sessionId: sessionId)) ?? nil) {
            messages = window.messages
            loadedBlockRange = window.blockIndex...window.blockIndex
        } else {
            messages = []
            loadedBlockRange = nil
        }
        totalMessageCount = ((try? env.stores.messages.count(sessionId: sessionId)) ?? messages.count)
        refreshHasMore()
        #if DEBUG
        DebugSeeding.log("[chat] window reset session=\(sessionId) blocks=\(loadedBlockRange.map { "\($0.lowerBound)...\($0.upperBound)" } ?? "-") loaded=\(messages.count) total=\(totalMessageCount)")
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
        loadedBlockRange = nil
        hasMoreHistory = false
        hasMoreNewer = false
        isLoadingEarlier = false
        isLoadingNewer = false
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
        // 新消息可能已经开出了新的一块：把已加载区间的最新块号推到库里最新那一块，
        // 否则「下面还有更新的消息」会在其实已经到底时还亮着。
        if let last = ((try? env.stores.messages.lastBlockIndex(sessionId: sessionId)) ?? nil) {
            let lower = loadedBlockRange?.lowerBound ?? last
            let upper = max(last, loadedBlockRange?.upperBound ?? last)
            loadedBlockRange = lower...upper
        }
        totalMessageCount = ((try? env.stores.messages.count(sessionId: sessionId)) ?? messages.count)
        refreshHasMore()
    }

    /// 重新算「上面还有没有更早的一块 / 下面还有没有更新的一块」。
    ///
    /// 直接问块表（`olderBlockIndex` / `newerBlockIndex`），不数消息条数：
    /// 块可能被删空而留下编号空洞，所以「上一块」不等于 `blockIndex - 1`。
    private func refreshHasMore() {
        guard let env, let range = loadedBlockRange else {
            hasMoreHistory = false
            hasMoreNewer = false
            return
        }
        hasMoreHistory = ((try? env.stores.messages.olderBlockIndex(
            sessionId: sessionId, before: range.lowerBound
        )) ?? nil) != nil
        hasMoreNewer = ((try? env.stores.messages.newerBlockIndex(
            sessionId: sessionId, after: range.upperBound
        )) ?? nil) != nil
        // 服务端模式：更早的内容可能还没缓存到本地（本地块只到第一块，但服务端还有）。
        // 界面上的「加载更早」必须按服务端的说法显示，否则用户永远翻不到历史。
        if env.serverModeActive && env.serverHasMoreOlder(sessionId: sessionId) {
            hasMoreHistory = true
        }
    }

    /// 往上翻到顶：把**上一块**接在窗口前面（v11：一页 = 一块，规范 §3 Older）。
    ///
    /// 三条保护：已经没有更早的块了、正在取（滚到顶时 `onAppear` 会连喊几次）、
    /// 取回来的 id 与窗口里已有的重复（`MessagePaging.prepend` 兜底，避免重复 id）。
    func loadEarlierMessages() {
        guard let env, hasMoreHistory, !isLoadingEarlier, loadedBlockRange != nil else { return }
        isLoadingEarlier = true
        // 服务端模式：更早的内容在服务端，先按**服务端的块**拉一页（块号不通用，见
        // `ServerSyncMapping.message` 的注释），写完缓存再按本地块接上。
        if env.serverModeActive {
            let id = sessionId
            Task { [weak self] in
                await env.loadOlderServerBlock(sessionId: id)
                guard let self, !Task.isCancelled else { return }
                self.isLoadingEarlier = false
                self.reloadMessages(preservingLoadedRange: true)
                self.refreshHasMore()
            }
            return
        }
        #if DEBUG
        // 分页验收：让「正在加载更早…」这一帧停留得久一点，好让 simctl 截到图。
        if ProcessInfo.processInfo.environment["YAP_DEBUG_SLOW_PAGING"] == "1" {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                self.appendOlderBlock(env)
            }
            return
        }
        #endif
        appendOlderBlock(env)
    }

    /// 取上一块接在窗口前面（调试慢速路径也复用它，逻辑只有一份）。
    private func appendOlderBlock(_ env: AppEnvironment) {
        defer { isLoadingEarlier = false }
        guard let range = loadedBlockRange,
              let olderIndex = ((try? env.stores.messages.olderBlockIndex(
                  sessionId: sessionId, before: range.lowerBound
              )) ?? nil),
              let window = ((try? env.stores.messages.block(olderIndex, sessionId: sessionId)) ?? nil),
              !window.messages.isEmpty else {
            hasMoreHistory = false
            return
        }
        messages = MessagePaging.prepend(window.messages, to: messages)
        loadedBlockRange = olderIndex...range.upperBound
        refreshHasMore()
        #if DEBUG
        DebugSeeding.log("[chat] load earlier +\(window.messages.count) loaded=\(messages.count) hasMore=\(hasMoreHistory)")
        #endif
    }

    /// 往下滚到底：把**下一块**接在窗口后面（v11 新增的向下加载，规范 §3 Newer）。
    ///
    /// 以前不在最新位置时只能「回到最新」（整段重读），会把用户正在看的历史丢掉；
    /// 现在按块往下走，块与块之间没有空隙，读多少行也只与块大小有关。
    func loadNewerMessages() {
        guard let env, hasMoreNewer, !isLoadingNewer, loadedBlockRange != nil else { return }
        isLoadingNewer = true
        appendNewerBlock(env)
    }

    private func appendNewerBlock(_ env: AppEnvironment) {
        defer { isLoadingNewer = false }
        guard let range = loadedBlockRange,
              let newerIndex = ((try? env.stores.messages.newerBlockIndex(
                  sessionId: sessionId, after: range.upperBound
              )) ?? nil),
              let window = ((try? env.stores.messages.block(newerIndex, sessionId: sessionId)) ?? nil),
              !window.messages.isEmpty else {
            hasMoreNewer = false
            return
        }
        // cap: .max —— 往下翻不能把人正在看的历史挤掉（块号区间还要靠它算「上面还有没有」）。
        messages = MessagePaging.appendingNewer(window.messages, to: messages, cap: .max)
        loadedBlockRange = range.lowerBound...newerIndex
        refreshHasMore()
        #if DEBUG
        DebugSeeding.log("[chat] load newer +\(window.messages.count) loaded=\(messages.count) hasMoreNewer=\(hasMoreNewer)")
        #endif
    }

    // MARK: - 从搜索结果定位到某条消息

    /// 把目标消息所在的那一块读进窗口（再往前带一块上下文），然后让界面滚过去 + 短暂高亮。
    ///
    /// 目标可能比首屏那一块早很多：直接查它落在第几块（`SELECT blockIndex FROM messages`），
    /// 只读那一块和它的上一块 —— 不再「一页页 60 条往前翻」，读的行数与目标有多久远无关。
    /// 块定位的边界（目标块不存在 / 上一块被删空 / 有编号空洞）由 `MessageBlocks.focus` 兜住。
    ///
    /// 为什么定位之后不再跟随到底部：用户点的是「某条老消息」，把他拽到最新一句等于白点。
    /// `autoFollow` 由界面负责关掉（`ChatScreen` 在 onAppear 里做）。
    func focus(on messageId: String) {
        guard let env, !messageId.isEmpty else { return }
        guard let targetBlock = ((try? env.stores.messages.blockIndex(ofMessage: messageId)) ?? nil) else {
            showToast(L("没找到这条消息，可能已经被删掉了"))
            return
        }
        let result = MessageBlocks.focus(targetMessageId: messageId, targetBlock: targetBlock) { index in
            (try? env.stores.messages.block(index, sessionId: self.sessionId))?.messages
        }
        guard result.found else {
            #if DEBUG
            DebugSeeding.log("[chat] 定位 \(messageId) 失败：块 \(targetBlock) 里没有它")
            #endif
            showToast(L("没找到这条消息，可能已经被删掉了"))
            return
        }
        messages = result.messages
        loadedBlockRange = result.lowerBlockIndex...result.upperBlockIndex
        refreshHasMore()
        #if DEBUG
        DebugSeeding.log("[chat] 定位 \(messageId)：块 \(result.lowerBlockIndex)...\(result.upperBlockIndex)（\(result.blocksLoaded) 块）窗口 \(messages.count) 条 hasMore=\(hasMoreHistory) hasNewer=\(hasMoreNewer)")
        #endif
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
        // 服务端模式下「能不能聊」由服务端决定，本机不需要任何 API 配置：
        // 没有这一行，用户一开服务端模式就会看到「先去设置里配一个 API」。
        hasApiConfig = env.serverModeActive || env.resolvedChatConfig() != nil
        guard let persona else { return }
        if env.serverModeActive {
            // 服务端模式（M4）：心情/在场由服务端下发，本机不再做衰减推导。
            // 服务端没下发时退回本地推导（第一次同步还没跑完的那一小段时间）。
            let live = env.serverLiveState
            mood = live.moodForDisplay ?? env.moodRepository.current(personaId: persona.id)
            presence = live.presenceForDisplay ?? env.presence.current(personaId: persona.id)
            return
        }
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

    // MARK: - 附件（图片 / 语音 / 视频）

    /// 加进待发送的附件；超过上限的部分直接丢掉（界面已经限过一次，这里是兜底）。
    ///
    /// 上限与 `AttachmentsCodec` 同一组：图片 4 张、语音 1 段、视频 1 段。
    /// 语音/视频合起来算「一段媒体」——一次发两段语音对方也不知道先听哪条。
    func addAttachments(_ attachments: [MessageAttachment]) {
        var images = attachments.filter { $0.isImage }
        var audio = attachments.filter { $0.isAudio }
        var video = attachments.filter { $0.isVideo }
        let room = max(0, AttachmentsCodec.maxImagesPerMessage - pendingAttachments.filter { $0.isImage }.count)
        images = Array(images.prefix(room))
        if pendingAttachments.contains(where: { $0.isPlayableMedia }) {
            audio = []
            video = []
        }
        var kept = images
        if let first = audio.first { kept.append(first) }
        if let first = video.first { kept.append(first) }
        guard !kept.isEmpty else { return }
        pendingAttachments.append(contentsOf: kept)
    }

    /// 已经选了几张图（`PhotosPicker` 的可选上限要扣掉它）。
    var pendingImageCount: Int {
        pendingAttachments.filter { $0.isImage }.count
    }

    /// 已经选了一段语音/视频没有（有的话「按住说话 / 选视频」按钮先别开）。
    var hasPendingPlayableMedia: Bool {
        pendingAttachments.contains { $0.isPlayableMedia }
    }

    func removeAttachment(at index: Int) {
        guard pendingAttachments.indices.contains(index) else { return }
        let attachment = pendingAttachments.remove(at: index)
        discard(attachment)
    }

    func clearPendingAttachments() {
        let attachments = pendingAttachments
        pendingAttachments.removeAll()
        for attachment in attachments { discard(attachment) }
    }

    /// 撤掉/放弃一个待发送附件时清文件。
    ///
    /// 图片：走引用计数（同一张图可能还被别的消息/头像用着）；
    /// 语音/视频：文件名带 UUID、不做内容去重，直接删自己目录里的那一份。
    private func discard(_ attachment: MessageAttachment) {
        guard let env else { return }
        if attachment.isPlayableMedia {
            AudioVideoStore.shared.deleteIfOwned(attachment)
            return
        }
        MediaStorage.shared.deleteIfUnreferenced(
            attachment,
            references: MediaReferences(stores: env.stores, settings: env.settings)
        )
    }

    func setPreparingAttachments(_ preparing: Bool) {
        isPreparingAttachments = preparing
    }

    func send() {
        guard let env, !isGenerating, !isPreparingAttachments else { return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let attachments = pendingAttachments
        guard !text.isEmpty || !attachments.isEmpty else { return }
        // 服务端模式下内容与生成都在服务端，本机不需要 API 配置。
        // 本地模式保持原样：没有配置就不发一个注定失败的请求（并给一句引导）。
        guard env.serverModeActive || env.resolvedChatConfig() != nil else {
            hasApiConfig = false
            showToast(L("先去设置里配一个 API"))
            return
        }
        // 服务端模式带图片：先压缩 → 上传，再入队发送（异步；期间显示「正在准备图片」）。
        // 本地模式保持原来的同步链路（附件在选图时已经压好、落盘）。
        if env.serverModeActive && !attachments.isEmpty {
            isPreparingAttachments = true
            Task { @MainActor in
                let outcome = await env.sendCheckedWithMedia(
                    sessionId: sessionId, text: text, attachments: attachments
                )
                isPreparingAttachments = false
                applySendOutcome(outcome, text: text, attachments: attachments)
            }
            return
        }
        // 只有「真的落库了」才清空输入状态（0.10.2 审计 P1-10）。
        // 消息过长 / 会话被删 / 配置没了都发生在落库之前：这时草稿与图片原样留着，
        // 用户点一下「重试」就能接着发；附件引用没变、文件还在磁盘上，不会留下孤儿图。
        //
        // 走 `env.sendChecked`：服务端模式走 SSE + 待发队列，本地模式走原链路。
        // 这个判断只出现在装配层一处，这里不重复。
        applySendOutcome(
            env.sendChecked(sessionId: sessionId, text: text, attachments: attachments),
            text: text,
            attachments: attachments
        )
    }

    /// 发送结果落到界面状态：只有「真的入队/落库了」才清空输入；被拒绝时草稿与图片原样留着。
    private func applySendOutcome(
        _ outcome: ChatService.SendOutcome,
        text: String,
        attachments: [MessageAttachment]
    ) {
        switch outcome {
        case .started(let stream):
            sendFailure = nil
            draft = ""
            pendingAttachments.removeAll()
            // `ChatService` 会**先**把用户这句话落库、再返回流；立刻追加一次，
            // 自己说的话就马上出现在屏幕上，而不是等模型开口。
            // 用「追加」而不是重读最后 N 条：已加载的历史不能被自己这一句挤掉（P2-5）。
            reloadMessages(preservingLoadedRange: true)
            // 0.14 拉黑（`spec/experience/README.md` §2）：这一轮不会生成、也不会回，
            // 进去「正在输入」再立刻退出只会闪一下 —— 直接不进生成态。
            // 界面靠消息自己的 blocked 标记显示红色感叹号与「对方已将你拉黑」。
            if messages.last?.blocked == true {
                isGenerating = false
                streamingText = ""
                streamingTraces = []
                return
            }
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

    // MARK: - 表情（0.13）

    /// 发送一个表情（历史面板点选 / 长按收藏后点发送）。
    ///
    /// 与普通发送的区别只有两点：
    /// 1. **不带文字**：表情自己就是一句话（`ChatService` 允许正文为空的图片消息）；
    /// 2. 不清空草稿与已选图片：用户可能正写到一半，点个表情只是插一句。
    ///
    /// 服务端模式要先上传 GIF 拿 `/v1/media/<id>`（`StickerService.prepareForSending`）：
    /// 服务端的聊天媒体链路只认已上传的 mediaId，而且它会把本地文件重压成 JPEG ——
    /// 那样表情就不动了，所以这一步必须走表情自己的上传（`kind=STICKER`）。
    func sendSticker(_ sticker: StickerEntity) {
        guard let env, !isGenerating, !isPreparingAttachments else { return }
        guard env.serverModeActive || env.resolvedChatConfig() != nil else {
            hasApiConfig = false
            showToast(L("先去设置里配一个 API"))
            return
        }
        isPreparingAttachments = true
        let targetSessionId = sessionId
        Task { @MainActor in
            do {
                let personaId = (try? env.stores.sessions.byId(targetSessionId))??.personaId
                let attachment = try await env.stickers.prepareForSending(
                    sticker, personaId: personaId
                )
                let outcome = await env.sendCheckedWithMedia(
                    sessionId: targetSessionId, text: "", attachments: [attachment]
                )
                isPreparingAttachments = false
                switch outcome {
                case .started(let stream):
                    sendFailure = nil
                    reloadMessages(preservingLoadedRange: true)
                    consume(stream)
                case .rejected(let error):
                    let message = Self.describe(error)
                    sendFailure = message
                    showToast(message)
                }
            } catch {
                isPreparingAttachments = false
                let message = (error as? LocalizedError)?.errorDescription ?? L("表情没发出去")
                showToast(message.isEmpty ? L("表情没发出去") : message)
            }
        }
    }

    /// 长按气泡「添加到我的表情」：把这条消息里的表情收进收藏（同一文件，不重新编码）。
    func saveSticker(from message: MessageEntity) {
        guard let env else { return }
        Task { @MainActor in
            do {
                let result = try await env.stickers.save(fromMessageId: message.id)
                showToast(result.alreadySaved ? L("这个表情已经在收藏里了") : L("已添加到我的表情"))
            } catch {
                let text = (error as? LocalizedError)?.errorDescription ?? L("没能保存这个表情")
                showToast(text.isEmpty ? L("没能保存这个表情") : text)
            }
        }
    }


    /// 重新生成：`ChatService` 会删掉最后一条助手消息再跑一轮。
    func regenerate() {
        guard let env, !isGenerating else { return }
        // 服务端模式这一版没有「重新生成」接口（服务端只在收到消息后生成），
        // 与其让用户点一个注定失败的按钮，不如明确说一句。
        guard !env.serverModeActive else {
            showToast(L("服务端模式下重新生成还没做"))
            return
        }
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
        completedReply = false
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
            // 0.12 续 7：他这一轮说了话 → 上一轮的「他睡着了」提示立刻作废。
            lastNoReply = nil
            // 云端模式的人格进化只认「真的完成了一轮回复」（停止/失败/没说话都不算）。
            completedReply = true
        case .noReply(let reason):
            // 他这一轮没说话：没有助手消息要落库，界面只重读一次消息（用户气泡会显示「已读」）。
            lastNoReply = reason
            streamingText = ""
            streamingTraces = []
        case .appended:
            // 0.14 连发：这一轮又落了一条独立消息，立刻重读让它逐条出现
            //（而不是等这一轮结束一起冒出来）。
            reloadMessages(preservingLoadedRange: true)
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
        // 云端模式：这一轮助手回复已经落库，按与本地模式同一节流考虑跑一次人格进化
        //（本地模式由 `ChatService` 在落库后触发，这里不做，避免两条路径各烧一次调用）。
        if completedReply, let env, env.serverModeActive, let persona {
            env.triggerPersonaEvolutionForServerReply(sessionId: sessionId, personaId: persona.id)
            // 0.13 第二阶段：情绪也搬到服务端判（每 4 条用户消息 / 24 小时，节流在 Kit 里）。
            // 结果写本地缓存，界面/离线提示词/心跳情绪放大都读本地那几列。
            env.triggerCloudMoodForServerReply(sessionId: sessionId, personaId: persona.id)
        }
        completedReply = false
        // 流结束：消息轮询重新按计划起来（生成中它是停的）。
        restartPulses()
    }

    // MARK: - 消息操作

    /// 删除一条消息，并把会话的「最后一句预览」重算一遍。
    func delete(_ message: MessageEntity) {
        guard let env else { return }
        do {
            try env.stores.messages.delete(id: message.id)
            // 图片跟着这条消息一起离开 —— 但只删零引用的：同一张图可能还被别的消息、
            // 人格或我的头像引用着（文件名按内容哈希去重）。
            MediaStorage.shared.deleteAttachmentsIfUnreferenced(
                in: message.attachmentsJson,
                references: MediaReferences(stores: env.stores, settings: env.settings)
            )
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
            // 先删消息行，再逐个文件查引用：同一张图可能还被别的会话/头像引用着。
            let images = ((try? env.stores.messages.bySession(sessionId)) ?? [])
                .flatMap { AttachmentsCodec.decode($0.attachmentsJson) }
            try env.stores.messages.delete(where: "sessionId = ?", [.text(sessionId)])
            // 0.14：上下文清空了，备忘录与心理活动也一起走（留着会让下一轮提示词
            // 说起一段已经不存在的对话）。
            try env.stores.sessionMemory.delete(where: "sessionId = ?", [.text(sessionId)])
            try env.stores.mentalActivities.delete(where: "sessionId = ?", [.text(sessionId)])
            MediaStorage.shared.deleteIfUnreferenced(
                images,
                references: MediaReferences(stores: env.stores, settings: env.settings)
            )
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
