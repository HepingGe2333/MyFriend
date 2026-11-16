import PhotosUI
import SwiftUI

/// 聊天页。
///
/// 这是 Android `ChatScreen.kt` 的 iOS 版：顶部一行「返回 / 他 / 更多」，
/// 中间是消息与流式气泡，底部是输入栏。所有「怎么回一句话」都在 `YapKit` 的
/// `ChatService` 里，这个文件只做三件事：**把状态画出来**、**把点击交回去**、**把滚动管好**。
///
/// 几个刻意与 Android 对齐的细节：
/// - 长按气泡出的是自己的菜单（复制 / 重新生成 / 删除），不是系统选字工具条；
/// - 流式正文用全文覆盖同一条气泡（`ChatEvent.delta` 传的就是全文），带闪烁光标；
/// - 生成中发送按钮变停止按钮，停止不会清空气泡，而是留一句「已停止」；
/// - 滚到底部做了 60ms 节流，长回复一路 token 一路滚会抖。
struct ChatScreen: View {

    let sessionId: String
    /// 从搜索结果跳进来时要定位的那条消息；普通进入是 nil。
    let focusMessageId: String?

    init(sessionId: String, focusMessageId: String? = nil) {
        self.sessionId = sessionId
        self.focusMessageId = focusMessageId
    }

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase

    @StateObject private var model = ChatViewModel()
    @FocusState private var composerFocused: Bool

    /// 从聊天页推出去的目的地：内置浏览器、长期记忆、API 设置。
    /// （为什么不用 `NavigationLink(value:)`：链接在 `Text` 里、菜单项在 `Menu` 里，
    ///  都没法包一个 `NavigationLink`；`Route` 的 push 需要根视图持有 `NavigationPath`，见报告。）
    private enum Destination: Identifiable {
        case browser(String)
        case memories
        case settings

        var id: String {
            switch self {
            case .browser(let url): return "browser:\(url)"
            case .memories: return "memories"
            case .settings: return "settings"
            }
        }
    }

    @State private var destination: Destination?
    @State private var showProfile = false
    /// 相册里选中的临时项；读成 Data、压缩落盘之后就清空（避免重复导入）。
    @State private var pickedItems: [PhotosPickerItem] = []
    /// 全屏看图：点的是哪条消息里的第几张。
    @State private var previewItem: ChatImagePreviewItem?
    @State private var confirmClear = false
    @State private var pendingDelete: MessageEntity?

    /// 是否跟随到底部。用户往上翻之后停止自动跟随，点「最新」或发消息时恢复。
    @State private var autoFollow = true
    @State private var lastScrollAt: UInt64 = 0
    @State private var pendingScroll: Task<Void, Never>?

    private let bottomAnchor = "chat-bottom"

    /// 强调色跟设置走（`accentColor` 是 Android 那边存的 ARGB）。
    private var accent: Color {
        let argb = env.currentSettings.accentColor
        let alpha = (UInt32(truncatingIfNeeded: argb) >> 24) & 0xFF
        return alpha == 0 ? MFTheme.defaultAccent : Color(argb: argb)
    }

    private var bubbleStyle: BubbleStyle { env.currentSettings.bubbleStyle }
    private var animationsEnabled: Bool { env.currentSettings.animationsEnabled }

    // MARK: - 主体

    var body: some View {
        VStack(spacing: 0) {
            topBar
            messageArea
            if !model.hasApiConfig {
                noConfigBar
            }
            composer
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .navigationDestination(isPresented: Binding(
            get: { destination != nil },
            set: { if !$0 { destination = nil } }
        )) {
            destinationView
        }
        .fullScreenCover(item: $previewItem) { item in
            ChatImageViewer(attachments: item.attachments, initialIndex: item.index)
        }
        .sheet(isPresented: $showProfile) {
            ChatProfileSheet(
                persona: model.persona,
                session: model.session,
                config: env.activeApiConfig,
                messageCount: model.messages.count,
                mood: model.mood,
                onOpenMemories: {
                    showProfile = false
                    destination = .memories
                },
                onOpenSettings: {
                    showProfile = false
                    destination = .settings
                }
            )
        }
        .confirmationDialog(
            L("清空这段对话？"),
            isPresented: $confirmClear,
            titleVisibility: .visible
        ) {
            Button(L("清空上下文"), role: .destructive) { model.clearContext() }
            Button(L("取消"), role: .cancel) {}
        } message: {
            Text(L("这段对话的消息会被删除。人格设定与长期记忆都会保留。"))
        }
        .confirmationDialog(
            L("删除这条消息？"),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(L("删除"), role: .destructive) {
                if let message = pendingDelete { model.delete(message) }
                pendingDelete = nil
            }
            Button(L("取消"), role: .cancel) { pendingDelete = nil }
        } message: {
            Text(L("删除后无法撤销。"))
        }
        .overlay(alignment: .bottom) { toastView }
        .animation(.easeInOut(duration: 0.18), value: model.toast)
        .onAppear {
            model.bind(env: env, sessionId: sessionId)
            // 进聊天前异步翻译一次（`spec/persona-translation` §2）：不阻塞首屏，
            // 本轮用已有缓存，翻译落地后的回复就用译文。
            PersonaTranslationTrigger.kick(env, sessionId: sessionId)
            model.enter()
            // 定位模式：用户点的是「某条老消息」，先别跟随到底部，否则等于白点。
            if let focusMessageId {
                autoFollow = false
                model.focus(on: focusMessageId)
            }
            // Debug：进入聊天 30 秒后打一条内存快照（Release 里是空实现）。
            DebugMemoryLog.scheduleChatSnapshot()
            // Debug：`YAP_DEBUG_OPEN_IMAGE=1` 时自动打开第一张图的全屏查看（模拟器没法点）。
            if DebugSeeding.openImageOnLaunch { openFirstImageForDebug() }
        }
        .onDisappear {
            model.leave()
            pendingScroll?.cancel()
            pendingScroll = nil
        }
        // 前台 / 后台：0.11.0 审计 P2-3 —— 轮询只在「页面可见 + App 在前台」时跑。
        // 回前台时 `setSceneActive(true)` 会先追加一次新消息（心跳/主动唤醒期间写进来的），
        // 再按 `ChatPollingPolicy` 排下一拍；进后台立刻停，不留在后台每 4 秒查一次库。
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                model.setSceneActive(true)
                autoFollow = true
            case .background, .inactive:
                model.setSceneActive(false)
            @unknown default:
                break
            }
        }
        // 服务端模式：首次全量同步 / 每 5 分钟增量同步写库之后，聊天页也要立刻看到
        //（否则要等它自己 4 秒一拍，或者「进来时还没同步完」就会一直空着）。
        .onChange(of: env.serverDataVersion) { _ in
            model.reloadMessages(preservingLoadedRange: true)
        }
    }

    @ViewBuilder
    private var destinationView: some View {
        if let destination {
            switch destination {
            case .browser(let url):
                InAppBrowserView(url: url)
            case .memories:
                MemoryListScreen()
            case .settings:
                SettingsScreen()
            }
        } else {
            EmptyView()
        }
    }

    // MARK: - 顶栏

    private var topBar: some View {
        HStack(spacing: 6) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 38, height: 38)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(L("返回")))

            Button {
                showProfile = true
            } label: {
                HStack(spacing: 10) {
                    if let persona = model.persona {
                        MFAvatar(persona: persona, size: 38)
                    } else {
                        Circle()
                            .fill(Color.secondary.opacity(0.2))
                            .frame(width: 38, height: 38)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.persona?.name ?? L("对话"))
                            .font(.system(.headline, design: .serif))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        subtitle
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text(L("打开会话资料")))

            Spacer(minLength: 4)

            Menu {
                Button {
                    showProfile = true
                } label: {
                    Label(L("会话资料"), systemImage: "info.circle")
                }
                Button {
                    destination = .memories
                } label: {
                    Label(L("长期记忆"), systemImage: "brain")
                }
                Button {
                    destination = .settings
                } label: {
                    Label(L("API 设置"), systemImage: "gearshape")
                }
                Divider()
                Button(role: .destructive) {
                    confirmClear = true
                } label: {
                    Label(L("清空上下文"), systemImage: "trash")
                }
                .disabled(model.messages.isEmpty || model.isGenerating)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 18, weight: .semibold))
                    .frame(width: 38, height: 38)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(Text(L("更多")))
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }

    /// 副标题：生成中是「正在输入…」，否则是「心情胶囊 + 他此刻」。
    /// 这一行刻意只显示、不提供任何设置入口——他的状态是可以看见的，不是可以调的。
    @ViewBuilder
    private var subtitle: some View {
        if model.isGenerating {
            Text(model.isStopping ? L("正在停下…") : L("正在输入…"))
                .font(.caption2)
                .foregroundStyle(accent)
        } else {
            HStack(spacing: 6) {
                ChatMoodCapsule(mood: model.mood)
                // 睡着时状态行要带上「几点醒」（§7）：那是用户唯一需要知道的事实。
                let presenceText = SleepSchedule.statusLine(
                    model.persona, presenceText: model.presence.display()
                )
                if !presenceText.isEmpty {
                    Text(presenceText)
                        .font(.caption2)
                        .foregroundStyle(MFTheme.subtleText(scheme))
                        .lineLimit(1)
                }
            }
        }
    }

    // MARK: - 消息区

    private var messageArea: some View {
        ZStack(alignment: .top) {
            if model.loaded && model.messages.isEmpty && !model.isGenerating {
                emptyConversation
            } else {
                chatScroll
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var chatScroll: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    Color.clear.frame(height: 1).id("chat-top")
                    // 上滑到顶就取更早的一块（v11：一页 = 一块，最多 BLOCK_SIZE=200 条）；加载中/到头了不显示。
                    if model.hasMoreHistory || model.isLoadingEarlier {
                        loadEarlierRow
                            // id 带上已加载条数：这一页插进来之后视图会重建、`onAppear` 再触发一次，
                            // 于是「停在顶部」时能一页页接着取到最早，而不是卡在 120 条。
                            .id("chat-load-earlier-\(model.messages.count)")
                            // 只有「用户在往上翻」时才自动取：冷启动时列表在自动滚到底之前
                            // 会短暂停在顶部，若这里无条件触发，一进来就白白多读一块。
                            .onAppear {
                                if !autoFollow { model.loadEarlierMessages() }
                            }
                            .onTapGesture { model.loadEarlierMessages() }
                    }
                    ForEach(model.rows) { row in
                        switch row.content {
                        case .day(let label):
                            ChatDateDivider(label: label)
                        case .message(let message):
                            ChatBubbleRow(
                                message: message,
                                isLatestAssistant: message.id == model.latestAssistantId,
                                showsRead: model.showsReadBadge(for: message),
                                bubbleStyle: bubbleStyle,
                                accent: accent,
                                animationsEnabled: animationsEnabled,
                                myAvatarUri: env.currentSettings.myAvatarUri,
                                onOpenLink: openLink,
                                onOpenImage: { attachments, index in
                                    previewItem = ChatImagePreviewItem(
                                        message: message, attachments: attachments, index: index
                                    )
                                },
                                onCopy: { model.copy(message.content) },
                                onRegenerate: { model.regenerate() },
                                onDelete: { pendingDelete = message }
                            )
                            // 搜索结果跳过来时的高亮：一圈强调色底，约 2 秒后淡出。
                            // 用负 padding 让底色「包住」气泡而不改变原有排版间距。
                            .background(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(accent.opacity(message.id == model.highlightedMessageId ? 0.16 : 0))
                                    .padding(.horizontal, -6)
                                    .padding(.vertical, -3)
                            )
                            .animation(
                                animationsEnabled ? .easeOut(duration: 0.7) : nil,
                                value: model.highlightedMessageId
                            )
                            .id(message.id)
                        }
                    }
                    // 往下滚到底就取**下一块**（v11 新增的向下加载）：不在最新位置时，
                    // 以前只能「回到最新」，现在可以按块往下走，块与块之间没有空隙。
                    if model.hasMoreNewer || model.isLoadingNewer {
                        loadNewerRow
                            .id("chat-load-newer-\(model.messages.count)")
                            // 出现在屏幕里就说明用户已经翻到已加载窗口的底部：直接取下一块。
                            // `isLoadingNewer` / `hasMoreNewer` 在 ViewModel 里挡住重复触发。
                            .onAppear { model.loadNewerMessages() }
                            .onTapGesture { model.loadNewerMessages() }
                    }
                    if model.isGenerating {
                        ChatStreamingBubble(
                            text: model.streamingText,
                            traces: model.streamingTraces,
                            bubbleStyle: bubbleStyle,
                            animationsEnabled: animationsEnabled,
                            onOpenLink: openLink
                        )
                        .id("chat-streaming")
                    }
                    Color.clear
                        .frame(height: 1)
                        .id(bottomAnchor)
                }
                .padding(.horizontal, 14)
                .padding(.top, 8)
                .padding(.bottom, 10)
            }
            .scrollDismissesKeyboard(.interactively)
            // 手机上往上翻一下就不要再把他往下拽：这是「我在看历史」的信号。
            .simultaneousGesture(
                DragGesture(minimumDistance: 12).onChanged { _ in autoFollow = false }
            )
            // 只在「最后一条变了」时滚到底：上滑加载更早的消息是在数组**前面**插，
            // 若按条数变化触发，会把刚翻到的历史位置一脚踹回最新消息。
            .onChange(of: model.messages.last?.id) { _ in
                scrollToBottom(proxy, animated: true)
            }
            .onChange(of: model.isGenerating) { _ in
                scrollToBottom(proxy, animated: true)
            }
            .onChange(of: model.streamingText) { _ in
                throttledScrollToBottom(proxy)
            }
            .onChange(of: model.scrollTargetId) { target in
                guard let target else { return }
                scrollToFocus(proxy, messageId: target)
            }
            .overlay(alignment: .bottomTrailing) {
                jumpToLatest(proxy)
            }
            #if DEBUG
            // 分页验收：`simctl` 不能上滑，用它把列表滚到「加载更早」那一行。
            .onAppear {
                guard ProcessInfo.processInfo.environment["YAP_DEBUG_CHAT_TOP"] == "1" else { return }
                Task { @MainActor in
                    // 等页面真的挂载出来（冷启动要几秒），再连滚几次：
                    // 第一次露出「加载更早」那一行，随后内容在前面插进来，后面的滚稳定停在顶部。
                    for index in 0..<4 {
                        try? await Task.sleep(nanoseconds: index == 0 ? 3_000_000_000 : 1_500_000_000)
                        autoFollow = false   // 模拟「用户往上翻」
                        withAnimation(.easeOut(duration: 0.25)) {
                            proxy.scrollTo("chat-top", anchor: .top)
                        }
                    }
                }
            }
            // 分页验收（v11 的**向下加载**）：`simctl` 不能滑动，用它把列表滚到底，
            // 露出「下方还有更新的消息」那一行并触发下一块（配合搜索结果定位到老消息使用）。
            .onAppear {
                guard ProcessInfo.processInfo.environment["YAP_DEBUG_CHAT_DOWN"] == "1" else { return }
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    autoFollow = false   // 模拟「用户在翻历史」
                    for _ in 0..<4 {
                        withAnimation(.easeOut(duration: 0.25)) {
                            proxy.scrollTo(bottomAnchor, anchor: .bottom)
                        }
                        try? await Task.sleep(nanoseconds: 1_500_000_000)
                    }
                }
            }
            #endif
        }
    }

    @ViewBuilder
    private func jumpToLatest(_ proxy: ScrollViewProxy) -> some View {
        if !autoFollow && !model.messages.isEmpty {
            Button {
                autoFollow = true
                // 窗口不在最新块时，「回到最新」要先把下面的块取回来，否则只是滚到
                // 「下方还有更新的消息」那一行就停住（v11 的向下加载）。
                if model.hasMoreNewer { model.loadNewerMessages() }
                scrollToBottom(proxy, animated: true)
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                    Text(L("最新"))
                        .font(.caption2)
                }
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(.thinMaterial, in: Capsule())
                .overlay(Capsule().strokeBorder(Color.primary.opacity(0.08), lineWidth: 1))
            }
            .buttonStyle(.plain)
            .padding(.trailing, 4)
            .padding(.bottom, 10)
            .accessibilityLabel(Text(L("回到最新消息")))
        }
    }

    /// 空会话引导：几句能直接填进输入框的开场白（点了只填，不直接发送——
    /// 用户可以改一改再说，这比替他把话说完更礼貌）。
    private var emptyConversation: some View {
        VStack(spacing: 10) {
            if let persona = model.persona {
                MFAvatar(persona: persona, size: 74)
                    .padding(.bottom, 4)
                Text(persona.name)
                    .font(.system(.title2, design: .serif))
                Text(persona.description.isEmpty ? L("开始一段新的对话。") : persona.description)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
            } else {
                Text(L("开始一段新的对话"))
                    .font(.system(.title3, design: .serif))
            }

            VStack(spacing: 8) {
                ForEach([L("嗨，今天过得怎么样？"), L("陪我待一会儿"), L("我最近有件事想和你说")], id: \.self) { suggestion in
                    Button {
                        model.draft = suggestion
                        composerFocused = true
                    } label: {
                        Text(suggestion)
                            .font(.body)
                            .foregroundStyle(.primary)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 9)
                            .background(
                                scheme == .dark ? Color.white.opacity(0.08) : Color.white.opacity(0.9),
                                in: Capsule()
                            )
                            .overlay(
                                Capsule().strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
                            )
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 8)

            Text(L("对话仅保存在本机"))
                .font(.caption2)
                .foregroundStyle(.secondary.opacity(0.7))
                .padding(.top, 6)
        }
        .padding(.horizontal, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 分页

    /// 列表顶部的「加载更早」：既是状态（转圈），也是触发器（滚到它就会取下一页）。
    private var loadEarlierRow: some View {
        HStack(spacing: 6) {
            if model.isLoadingEarlier {
                ProgressView().controlSize(.mini)
                Text(L("正在加载更早的消息…"))
            } else {
                Image(systemName: "arrow.up.circle")
                Text(L("加载更早"))
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
    }

    /// 列表底部的「下方还有更新的消息」：既是状态（转圈），也是触发器（滚到它就会取下一块）。
    private var loadNewerRow: some View {
        HStack(spacing: 6) {
            if model.isLoadingNewer {
                ProgressView().controlSize(.mini)
                Text(L("正在加载更新的消息…"))
            } else {
                Image(systemName: "arrow.down.circle")
                Text(L("下方还有更新的消息，点这里加载"))
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
    }

    // MARK: - 滚动

    /// 立刻滚到底部（新消息、开始/结束生成时用）。
    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool) {
        guard autoFollow else { return }
        lastScrollAt = DispatchTime.now().uptimeNanoseconds
        if animated && animationsEnabled {
            withAnimation(.easeOut(duration: 0.18)) {
                proxy.scrollTo(bottomAnchor, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(bottomAnchor, anchor: .bottom)
        }
    }

    /// 滚到搜索结果点进来的那条消息。
    ///
    /// 为什么滚好几次：目标那一行在 `LazyVStack` 里可能还没被创建出来（尤其是刚往前读了几页、
    /// 视图还在重建时），第一次 `scrollTo` 会落空。这里重试几次，每次错开 120ms；
    /// 重复滚到同一个位置是幂等的，用户看不出多余动作。
    private func scrollToFocus(_ proxy: ScrollViewProxy, messageId: String) {
        Task { @MainActor in
            for attempt in 0..<4 {
                if attempt > 0 {
                    try? await Task.sleep(nanoseconds: 120_000_000)
                }
                withAnimation(animationsEnabled && attempt == 0 ? .easeInOut(duration: 0.28) : nil) {
                    proxy.scrollTo(messageId, anchor: .center)
                }
            }
        }
    }

    /// 流式输出时**每 60ms 最多滚一次**：每个 token 都滚一次会让长回复整屏发抖，
    /// 而完全不等又会让字跑出可视区。
    private func throttledScrollToBottom(_ proxy: ScrollViewProxy) {
        guard autoFollow else { return }
        let interval: UInt64 = 60_000_000
        let now = DispatchTime.now().uptimeNanoseconds
        let elapsed = now >= lastScrollAt ? now - lastScrollAt : interval

        if elapsed >= interval && pendingScroll == nil {
            lastScrollAt = now
            proxy.scrollTo(bottomAnchor, anchor: .bottom)
            return
        }
        guard pendingScroll == nil else { return }
        pendingScroll = Task { @MainActor in
            let wait = interval - min(elapsed, interval)
            try? await Task.sleep(nanoseconds: max(wait, 1))
            pendingScroll = nil
            guard autoFollow else { return }
            lastScrollAt = DispatchTime.now().uptimeNanoseconds
            proxy.scrollTo(bottomAnchor, anchor: .bottom)
        }
    }

    // MARK: - 输入栏

    private var composer: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(Color.primary.opacity(0.06))
                .frame(height: 0.5)

            sendFailureBar

            sleepHintBar

            attachmentPreview

            HStack(alignment: .bottom, spacing: 10) {
                photoPickerButton

                TextField(composerPlaceholder, text: $model.draft, axis: .vertical)
                    // 1–5 行自适应：一句话时不占地方，写长了会自己长高（再多就内部滚动）。
                    .lineLimit(1...5)
                    .font(.body)
                    .focused($composerFocused)
                    .disabled(!model.hasApiConfig)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(
                        RoundedRectangle(cornerRadius: 21, style: .continuous)
                            .fill(scheme == .dark ? Color.white.opacity(0.07) : Color.white)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 21, style: .continuous)
                            .strokeBorder(Color.primary.opacity(0.10), lineWidth: 1)
                    )
                    .opacity(model.hasApiConfig ? 1 : 0.6)

                sendButton
            }
            .padding(.horizontal, 14)
            .padding(.top, 8)
            .padding(.bottom, 10)
        }
        .background(.regularMaterial)
    }

    /// 发送被拒（没落库）时的提示：草稿与图片都还在，点一下就能重试。
    ///
    /// 0.10.2 审计 P1-10：原来失败时 `ChatViewModel` 已经把 `draft` 与 `pendingAttachments`
    /// 清空了，用户只能重打重选。现在这两样原样留着，这条提示只负责说明「为什么没发出去」。
    @ViewBuilder
    private var sendFailureBar: some View {
        if let failure = model.sendFailure {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.circle")
                    .font(.system(size: 14))
                    .foregroundStyle(.orange)
                Text(failure)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                Spacer(minLength: 8)
                Button {
                    autoFollow = true
                    model.send()
                } label: {
                    Text(L("重试"))
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(accent)
                }
                .buttonStyle(.plain)
                .disabled(!model.canSend)
                Button {
                    model.dismissSendFailure()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(L("关闭")))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Color.orange.opacity(0.10))
        }
    }

    /// 输入框上方那一行「他睡着了（08:00 醒）；连着发几条可以叫醒他」（0.12 续 7）。
    ///
    /// 只在「这一轮因为他睡着而被静默、且他此刻还在睡」时出现（判定在 `ChatViewModel.sleepHint`）；
    /// 他回了话或睡醒之后自己消失。它不是消息气泡、不写库、也不弹窗 ——
    /// 顶栏那行 presence（「睡着了 · 08:00 醒」）仍然照旧，这两处说的是不同的事：
    /// 那是他的状态，这是**这一轮为什么没有回复**。
    @ViewBuilder
    private var sleepHintBar: some View {
        if let hint = model.sleepHint {
            HStack(spacing: 8) {
                Image(systemName: "moon.zzz")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                Text(hint)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Color.primary.opacity(0.04))
        }
    }

    private var composerPlaceholder: String {
        if !model.hasApiConfig { return L("先去设置里配一个 API") }
        return L("说点什么…")
    }

    // MARK: - 发图片

    /// 「+」：打开系统相册选图（`PhotosPicker` = `PHPickerViewController` 的 SwiftUI 外壳）。
    ///
    /// 一条消息最多四张（`AttachmentsCodec.maxImagesPerMessage`）：已选的张数会从可选上限里扣掉，
    /// 选满之后按钮自己变灰，而不是让用户选完再被裁掉。
    private var photoPickerButton: some View {
        let remaining = max(0, AttachmentsCodec.maxImagesPerMessage - model.pendingAttachments.count)
        let enabled = model.hasApiConfig && !model.isGenerating && remaining > 0
        // 闭包里只读这一份快照：`PhotosPicker` 的 label 是 @Sendable，
        // 直接读 @MainActor 的 `model.isPreparingAttachments` 会出并发警告。
        let preparing = model.isPreparingAttachments
        return PhotosPicker(
            selection: $pickedItems,
            maxSelectionCount: max(remaining, 1),
            matching: .images,
            photoLibrary: .shared()
        ) {
            Image(systemName: preparing ? "hourglass" : "plus")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(enabled ? accent : Color.secondary.opacity(0.45))
                .frame(width: 38, height: 38)
                .contentShape(Circle())
        }
        .disabled(!enabled)
        .accessibilityLabel(Text(L("从相册选一张图")))
        .onChange(of: pickedItems) { items in
            importPickedImages(items)
        }
    }

    /// 输入框上方那条缩略图：选了哪几张、还能一张张撤掉。
    @ViewBuilder
    private var attachmentPreview: some View {
        if !model.pendingAttachments.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(model.pendingAttachments.enumerated()), id: \.offset) { index, attachment in
                        ZStack(alignment: .topTrailing) {
                            AttachmentThumbnail(uri: attachment.uri, pointSize: 54) { missing in
                                AttachmentPlaceholder(missing: missing, compact: true)
                            }
                            .frame(width: 54, height: 54)
                            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

                            Button {
                                model.removeAttachment(at: index)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 17))
                                    .symbolRenderingMode(.palette)
                                    .foregroundStyle(Color.white, Color.black.opacity(0.55))
                            }
                            .buttonStyle(.plain)
                            .offset(x: 5, y: -5)
                            .accessibilityLabel(Text(L("撤掉这张图")))
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
        }
    }

    /// 相册选完：压好、落盘、放进待发送列表。失败的图给一句中文提示，不影响其它张。
    private func importPickedImages(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty else { return }
        pickedItems = []
        let room = max(0, AttachmentsCodec.maxImagesPerMessage - model.pendingAttachments.count)
        guard room > 0 else { return }
        model.setPreparingAttachments(true)
        Task { @MainActor in
            let result = await ImageImport.importMany(items, kind: .chatImage, limit: room)
            model.setPreparingAttachments(false)
            model.addAttachments(result.attachments)
            if let failure = result.failure { model.showToast(failure) }
        }
    }

    private var sendButton: some View {
        let stopping = model.isGenerating
        let enabled = stopping || model.canSend
        return Button {
            if stopping {
                model.stop()
                return
            }
            guard model.canSend else { return }
            autoFollow = true
            model.send()
            composerFocused = true
        } label: {
            ZStack {
                Circle()
                    .fill(enabled ? accent : Color.secondary.opacity(0.22))
                    .frame(width: 42, height: 42)
                if stopping {
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(Color.white)
                        .frame(width: 13, height: 13)
                } else {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(enabled ? Color.white : Color.secondary)
                }
            }
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(Text(stopping ? L("停止生成") : L("发送")))
    }

    private var noConfigBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "key")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
            Text(L("先去设置里配一个 API"))
                .font(.footnote)
                .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            NavigationLink(value: Route.settings) {
                Text(L("去设置"))
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(MFTheme.defaultAccent)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            Color.primary.opacity(0.06),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .padding(.horizontal, 14)
        .padding(.bottom, 6)
    }

    // MARK: - 提示条

    @ViewBuilder
    private var toastView: some View {
        if let toast = model.toast {
            Text(toast)
                .font(.footnote)
                .foregroundStyle(.primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(.thinMaterial, in: Capsule())
                .overlay(Capsule().strokeBorder(Color.primary.opacity(0.08), lineWidth: 1))
                .padding(.bottom, 96)
                .transition(.opacity)
                .accessibilityAddTraits(.isStaticText)
        }
    }

    // MARK: - 动作

    /// 只给截图验收用：等消息加载完，把第一张有附件的消息的全屏查看打开。
    private func openFirstImageForDebug() {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            for message in model.messages.reversed() {
                let attachments = AttachmentsCodec.decode(message.attachmentsJson)
                guard !attachments.isEmpty else { continue }
                previewItem = ChatImagePreviewItem(message: message, attachments: attachments, index: 0)
                return
            }
        }
    }

    private func openLink(_ url: String) {
        guard URL(string: url) != nil else { return }
        destination = .browser(url)
    }
}

// MARK: - 心情胶囊

/// 他此刻心情的一枚小胶囊：五个点表示浓度，一个字表示内容。
///
/// 做得很轻——心情是背景信息，不该抢走对话本身（与 Android 的 `MoodChip(compact = true)` 一致）。
struct ChatMoodCapsule: View {

    let mood: MoodState

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if mood.updatedAt <= 0 && mood.isEmpty() {
            EmptyView()
        } else {
            HStack(spacing: 5) {
                HStack(spacing: 2) {
                    ForEach(0..<5, id: \.self) { index in
                        Circle()
                            .fill(tint.opacity(index < intensity ? 0.9 : 0.22))
                            .frame(width: intensity >= 4 ? 4 : 3, height: intensity >= 4 ? 4 : 3)
                    }
                }
                Text(MoodLexicon.displayLabel(mood.label.isEmpty ? MoodLexicon.BASELINE : mood.label))
                    .font(.caption2)
                    .foregroundStyle(tint)
                    .lineLimit(1)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.primary.opacity(0.06), in: Capsule())
            .accessibilityLabel(Text(Lf("他现在的心情：%@", MoodLexicon.displayLabel(mood.label))))
        }
    }

    private var intensity: Int {
        min(max(mood.intensity, 1), 5)
    }

    /// 心情的极性决定小点的颜色：低落偏砖红、疲惫偏棕、雀跃偏绿、其余用次要文字色。
    private var tint: Color {
        let word = MoodLexicon.classify(mood.label)
        if word.valence <= -2 { return Color(hex: 0xB4, 0x73, 0x6B) }
        if word.valence < 0 || word.energy <= -2 { return Color(hex: 0xB0, 0x8A, 0x5A) }
        if word.valence >= 2 { return Color(hex: 0x6E, 0x9E, 0x76) }
        return MFTheme.subtleText(scheme)
    }
}

// MARK: - 会话资料

/// 「会话资料」：这一段对话用的是谁、哪个配置、多少条消息，以及去记忆 / 设置的两个入口。
///
/// 刻意不做成设置面板：这里只陈列「现在是什么」，改人格去人格页、改记忆去记忆页。
struct ChatProfileSheet: View {

    let persona: PersonaEntity?
    let session: ChatSessionEntity?
    let config: ApiConfigEntity?
    let messageCount: Int
    let mood: MoodState
    let onOpenMemories: () -> Void
    let onOpenSettings: () -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    infoCard
                    actions
                    Text(L("所有对话只保存在这台设备上。"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.top, 4)
                }
                .padding(MFTheme.gutter)
            }
            .background(MFTheme.canvas(scheme).ignoresSafeArea())
            .navigationTitle(L("会话资料"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("完成")) { dismiss() }
                }
            }
        }
    }

    private var header: some View {
        HStack(spacing: 14) {
            if let persona {
                MFAvatar(persona: persona, size: 56)
                VStack(alignment: .leading, spacing: 3) {
                    Text(persona.name)
                        .font(.system(.title3, design: .serif))
                    HStack(spacing: 6) {
                        Text(persona.category.isEmpty ? L("未分类") : persona.category)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Text("v\(persona.version)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                Text(L("对话"))
                    .font(.system(.title3, design: .serif))
            }
            Spacer(minLength: 0)
        }
    }

    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let description = persona?.description, !description.isEmpty {
                Text(description)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            row(L("当前 API"), config?.name ?? L("未绑定"))
            row(L("模型"), modelName)
            row(L("这段对话"), session?.title.isEmpty == false ? session!.title : L("新的对话"))
            row(L("消息"), Lcount("%d 条消息", messageCount))
            row(L("他的心情"), mood.isEmpty() ? MoodLexicon.displayLabel(MoodLexicon.BASELINE) : mood.display())
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            scheme == .dark ? Color.white.opacity(0.05) : Color.white,
            in: RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
        )
    }

    private var modelName: String {
        if let override = session?.model, !override.isEmpty { return override }
        if let model = config?.model, !model.isEmpty { return model }
        return L("未设置")
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 76, alignment: .leading)
            Text(value)
                .font(.caption)
                .foregroundStyle(.primary)
                .lineLimit(2)
            Spacer(minLength: 0)
        }
    }

    private var actions: some View {
        VStack(spacing: 0) {
            actionRow(L("长期记忆"), icon: "brain", action: onOpenMemories)
            Rectangle()
                .fill(Color.primary.opacity(0.06))
                .frame(height: 0.5)
                .padding(.leading, 44)
            actionRow(L("API 设置"), icon: "gearshape", action: onOpenSettings)
        }
        .background(
            scheme == .dark ? Color.white.opacity(0.05) : Color.white,
            in: RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
        )
    }

    private func actionRow(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 15))
                    .foregroundStyle(MFTheme.defaultAccent)
                    .frame(width: 20)
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.secondary.opacity(0.6))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
