import SwiftUI

/// 搜索页：在**本机**聊天记录里找一句话，然后跳回去看上下文。
///
/// 结构（自上而下）：
/// 1. 返回 + 搜索框（进页面自动聚焦）；
/// 2. 两条筛选：人格（全部 / 某个人）、时间（全部 / 最近 7 天 / 最近 30 天）；
/// 3. 结果列表：头像、人格名、会话名、本地化时间、带高亮的摘要；滚到底自动取下一页（每页 30）。
///
/// 几个刻意的决定：
/// - **不联网、不落库**：`MessageStore.search` 只读本地库，关掉网络也能用；
/// - 点一条结果 → `Route.chatAt` 带着 `messageId` 进聊天页，由 `MessageBlocks.focus`
///   直接读目标所在的那一块（+ 上一块上下文）、滚过去并短暂高亮（约 2 秒淡出）；
/// - 从聊天页返回时**保留查询、筛选与列表位置**：这页一直留在导航栈里，
///   `@State` 不会丢，只需要把列表滚回上次点开的那一条；
/// - 空态分两种：「还没输入」给一句引导，「查了没有」给「换个词试试」——不是同一件事。
struct SearchScreen: View {

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.mfNavigationPath) private var path
    @Environment(\.dismiss) private var dismiss

    @StateObject private var model = SearchViewModel()
    @FocusState private var fieldFocused: Bool

    /// 从聊天页返回时用来恢复列表位置：最后点开的那一条。
    @State private var lastOpenedHitId: String?
    @State private var restoreTask: Task<Void, Never>?
    /// 只在第一次出现时自动聚焦：从聊天页返回时再把键盘弹出来会把列表顶走，
    /// 而「保留查询与列表位置」正是这一页的验收点。
    @State private var didAutoFocus = false
    #if DEBUG
    @State private var debugDidAutoOpen = false
    @State private var debugDidApplyHooks = false
    #endif

    /// 强调色跟设置走（与聊天页同一个口径）。
    private var accent: Color {
        let argb = env.currentSettings.accentColor
        let alpha = (UInt32(truncatingIfNeeded: argb) >> 24) & 0xFF
        return alpha == 0 ? MFTheme.defaultAccent : Color(argb: argb)
    }

    // MARK: - 主体

    var body: some View {
        VStack(spacing: 0) {
            topBar
            filterBar
            Rectangle()
                .fill(Color.primary.opacity(0.06))
                .frame(height: 0.5)
            content
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .onAppear {
            model.bind(env: env)
            if !didAutoFocus {
                didAutoFocus = true
                fieldFocused = true
            }
            #if DEBUG
            applyDebugHooks()
            // 截图用：键盘会盖住半屏结果。默认仍然自动聚焦（需求里就是这样）。
            if DebugSeeding.searchNoKeyboard { fieldFocused = false }
            #endif
        }
        .onDisappear { restoreTask?.cancel() }
    }

    // MARK: - 顶栏（返回 + 搜索框）

    private var topBar: some View {
        HStack(spacing: 8) {
            Button {
                fieldFocused = false
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 38, height: 38)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(L("返回")))

            searchField
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }

    private var searchField: some View {
        HStack(spacing: 7) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.secondary)
            TextField(L("搜索聊天记录"), text: $model.query)
                .font(.body)
                .focused($fieldFocused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onChange(of: model.query) { _ in model.queryChanged() }
                .onSubmit { model.search() }
                .accessibilityLabel(Text(L("搜索聊天记录")))
            if !model.query.isEmpty {
                Button {
                    model.query = ""
                    fieldFocused = true
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 15))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(L("清空搜索内容")))
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .fill(scheme == .dark ? Color.white.opacity(0.08) : Color.white)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
        )
    }

    // MARK: - 筛选

    private var filterBar: some View {
        HStack(spacing: 8) {
            Menu {
                Picker(L("按人格筛选"), selection: Binding(
                    get: { model.personaFilter ?? "" },
                    set: { model.setPersonaFilter($0.isEmpty ? nil : $0) }
                )) {
                    Text(L("全部人格")).tag("")
                    ForEach(model.personas, id: \.id) { persona in
                        Text(persona.name).tag(persona.id)
                    }
                }
            } label: {
                filterChip(
                    icon: "person.crop.circle",
                    title: model.personaFilterTitle,
                    active: model.personaFilter != nil
                )
            }
            .accessibilityLabel(Text(L("按人格筛选")))

            Menu {
                Picker(L("按时间筛选"), selection: Binding(
                    get: { model.timeFilter },
                    set: { model.setTimeFilter($0) }
                )) {
                    ForEach(SearchViewModel.TimeFilter.allCases) { filter in
                        Text(filter.title).tag(filter)
                    }
                }
            } label: {
                filterChip(
                    icon: "clock",
                    title: model.timeFilter.title,
                    active: model.timeFilter != .all
                )
            }
            .accessibilityLabel(Text(L("按时间筛选")))

            Spacer(minLength: 0)

            if model.hasActiveFilter {
                Button {
                    model.setPersonaFilter(nil)
                    model.setTimeFilter(.all)
                } label: {
                    Text(L("重置"))
                        .font(.caption)
                        .foregroundStyle(MFTheme.defaultAccent)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, MFTheme.gutter)
        .padding(.bottom, 8)
    }

    private func filterChip(icon: String, title: String, active: Bool) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.system(size: 11))
            // 芯片固定一行：英文的「Last 30 days」比「最近 30 天」宽，先缩字号再考虑截断，
            // 免得两个筛选胶囊在窄屏上把「重置」挤出去。
            Text(title)
                .font(.caption)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
            Image(systemName: "chevron.down")
                .font(.system(size: 9, weight: .semibold))
        }
        .foregroundStyle(active ? accent : Color.secondary)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(
            (active ? accent.opacity(0.12) : Color.primary.opacity(0.05)),
            in: Capsule()
        )
        .contentShape(Capsule())
    }

    // MARK: - 内容区

    @ViewBuilder
    private var content: some View {
        if model.trimmedQuery.isEmpty {
            hintState
        } else if model.isLoading && model.hits.isEmpty {
            statusState {
                ProgressView()
                Text(L("正在搜索…"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } else if let errorText = model.errorText, model.hits.isEmpty {
            statusState {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 26, weight: .light))
                    .foregroundStyle(.secondary)
                Text(errorText)
                    .font(.system(.body, design: .serif))
                Button(L("重试")) { model.retry() }
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(accent)
            }
        } else if model.searched && model.hits.isEmpty {
            statusState {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 26, weight: .light))
                    .foregroundStyle(.secondary)
                Text(L("没有找到相关的消息"))
                    .font(.system(.body, design: .serif))
                Text(L("换个词试试，或者换个筛选条件。"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        } else {
            resultList
        }
    }

    private var hintState: some View {
        statusState {
            Image(systemName: "text.magnifyingglass")
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(.secondary)
            Text(L("搜一搜说过的话"))
                .font(.system(.body, design: .serif))
            Text(L("输入关键词，找找过去的聊天记录。"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private func statusState<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 10) {
            content()
        }
        .padding(.horizontal, 40)
        .padding(.top, 60)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    // MARK: - 结果列表

    private var resultList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text(Lcount("已显示 %d 条", model.hits.count))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)

                    ForEach(model.hits) { hit in
                        Button {
                            open(hit)
                        } label: {
                            row(hit)
                        }
                        .buttonStyle(.plain)
                        .id(hit.id)
                        .onAppear { model.loadMoreIfNeeded(currentHitId: hit.id) }

                        if hit.id != model.hits.last?.id {
                            Rectangle()
                                .fill(Color.primary.opacity(0.06))
                                .frame(height: 0.5)
                                .padding(.leading, 64)
                        }
                    }

                    footer
                }
                .padding(.horizontal, MFTheme.gutter)
                .padding(.bottom, 28)
            }
            .scrollDismissesKeyboard(.immediately)
            .onAppear {
                restoreScrollPosition(proxy)
                #if DEBUG
                applyDebugScroll(proxy)
                #endif
            }
        }
    }

    private func row(_ hit: SearchHit) -> some View {
        HStack(alignment: .top, spacing: 10) {
            MFAvatar(
                personaName: hit.personaName,
                avatarUri: hit.avatar,
                avatarSeed: hit.avatarSeed,
                size: 38
            )
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(hit.personaName)
                        .font(.system(.subheadline, design: .serif))
                        .lineLimit(1)
                    Text(hit.sessionTitle)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer(minLength: 6)
                    Text(MFDate.relative(hit.createdAt))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Text(snippet(hit))
                    .font(.callout)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    /// 摘要 + 高亮：`SearchSnippet.segments()` 把 UTF-16 偏移切成「普通段 / 高亮段」。
    ///
    /// 用 `AttributedString` 而不是拼 `Text`：高亮需要**底色**，
    /// 而 `Text` 的 modifier 只能改颜色 / 字重，改不了某一小段的背景。
    private func snippet(_ hit: SearchHit) -> AttributedString {
        var result = AttributedString()
        for segment in hit.snippetSegments {
            var piece = AttributedString(segment.text)
            if segment.isHighlight {
                piece.foregroundColor = accent
                piece.backgroundColor = accent.opacity(0.16)
                piece.inlinePresentationIntent = .stronglyEmphasized
            }
            result += piece
        }
        return result
    }

    @ViewBuilder
    private var footer: some View {
        if model.isLoadingMore {
            HStack(spacing: 6) {
                ProgressView().controlSize(.mini)
                Text(L("正在加载更多…"))
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
        } else if !model.hasMore && !model.hits.isEmpty {
            Text(L("没有更多了"))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
        }
    }

    // MARK: - 打开结果

    /// 点一条结果：带着消息 id 进聊天页，并记住它（返回时把列表滚回这里）。
    private func open(_ hit: SearchHit) {
        lastOpenedHitId = hit.id
        fieldFocused = false
        // 与首页「点联系人」同一个口径：他成为当前正在聊的人（心跳 / 主动开口都读这个）。
        env.updateSettings { $0.activePersonaId = hit.personaId }
        path?.wrappedValue.append(Route.chatAt(sessionId: hit.sessionId, messageId: hit.id))
        #if DEBUG
        DebugSeeding.log("[search] 打开结果 \(hit.id) → 会话 \(hit.sessionId)")
        #endif
    }

    /// 从聊天页返回：把列表滚回上次点开的那一条（查询与筛选本来就在 `model` 里）。
    private func restoreScrollPosition(_ proxy: ScrollViewProxy) {
        guard let id = lastOpenedHitId else { return }
        restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            // 等一帧：刚 pop 回来时列表可能还在重建，立刻 scrollTo 会找不到 id。
            try? await Task.sleep(nanoseconds: 120_000_000)
            guard !Task.isCancelled else { return }
            proxy.scrollTo(id, anchor: .center)
            DebugSeeding.log("[search] 恢复列表位置到 \(id)")
        }
    }

    // MARK: - 调试钩子（仅 Debug）

    #if DEBUG
    private func applyDebugHooks() {
        guard !debugDidApplyHooks else { return }
        debugDidApplyHooks = true
        if let prefill = DebugSeeding.searchQuery, !prefill.isEmpty {
            model.query = prefill
            model.search()
        }
        if let filter = DebugSeeding.searchTimeFilter {
            model.setTimeFilter(filter)
        }
        if let name = DebugSeeding.searchPersonaName,
           let persona = model.personas.first(where: { $0.name == name }) {
            model.setPersonaFilter(persona.id)
        }
        guard DebugSeeding.searchOpenFirst, !debugDidAutoOpen else { return }
        debugDidAutoOpen = true
        Task { @MainActor in
            // 结果要等防抖 + 一帧才回来：轮询几次，第一条出现就点进去。
            for _ in 0..<20 {
                try? await Task.sleep(nanoseconds: 500_000_000)
                if let first = model.hits.first {
                    open(first)
                    if DebugSeeding.searchRoundTrip {
                        // 再等 4 秒（够看清聊天页里的定位高亮），然后自己 pop 回来。
                        try? await Task.sleep(nanoseconds: 4_000_000_000)
                        path?.wrappedValue.removeLast()
                    }
                    return
                }
            }
        }
    }

    /// `simctl` 不能上滑：自己把列表滚到底，露出「正在加载更多…」并触发下一页。
    private func applyDebugScroll(_ proxy: ScrollViewProxy) {
        guard DebugSeeding.searchScrollToBottom else { return }
        Task { @MainActor in
            for index in 0..<5 {
                try? await Task.sleep(nanoseconds: index == 0 ? 2_000_000_000 : 1_000_000_000)
                guard let last = model.hits.last else { continue }
                withAnimation(.easeOut(duration: 0.2)) {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
    }
    #endif
}
