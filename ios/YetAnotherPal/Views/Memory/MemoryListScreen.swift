import SwiftUI

/// 记忆列表。
///
/// 上面是「记忆的健康」（三档数量 + 上次整理），下面是按重要度或时间分组的卡片。
/// 一条记忆卡片上能一眼看到：它属于哪个抽屉、有多重、还剩多少分量、被想起过几次。
/// 固定 / 删除走长按菜单，点一下进详情（含原文与漂移次数）。
struct MemoryListScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var memories: [MemoryEntity] = []
    @State private var counts = TierCounts()
    @State private var lastConsolidation: MemoryConsolidationEntity?
    @State private var grouping: Grouping = .importance
    @State private var pendingDelete: MemoryEntity?
    @State private var showAdd = false
    @State private var banner: MFAlert?
    @StateObject private var consolidation = ConsolidationRunner()

    private struct TierCounts {
        var active = 0
        var dormant = 0
        var archived = 0
        var total: Int { active + dormant + archived }
    }

    private enum Grouping: String, CaseIterable, Hashable {
        case importance
        case time
    }

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }
    private var personaId: String? {
        if let id = env.currentSettings.activePersonaId,
           (try? env.stores.personas.byId(id)) != nil {
            return id
        }
        return (try? env.stores.personas.allOrderedByUpdatedAt())?.first?.id
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                healthCard
                groupingBar

                if memories.isEmpty {
                    emptyState
                } else {
                    ForEach(grouped, id: \.title) { group in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(group.title)
                                .font(.system(.footnote, design: .serif))
                                .foregroundStyle(.secondary)
                                .padding(.top, 4)
                            ForEach(group.items, id: \.id) { memory in
                                NavigationLink(destination: MemoryDetailScreen(memoryId: memory.id)) {
                                    memoryCard(memory)
                                }
                                .buttonStyle(.plain)
                                .contextMenu {
                                    Button {
                                        togglePin(memory)
                                    } label: {
                                        Label(memory.pinned ? L("取消固定") : L("固定为重要"), systemImage: "pin")
                                    }
                                    Button(role: .destructive) {
                                        pendingDelete = memory
                                    } label: {
                                        Label(L("删除这条记忆"), systemImage: "trash")
                                    }
                                }
                            }
                        }
                    }
                }

                addButton
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(L("记忆"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: reload)
        // 服务端模式：记忆是服务端权威，进页面先拉一次并刷新本地缓存（失败就读缓存）。
        .task {
            guard env.serverModeActive, let personaId else { return }
            await env.refreshMemoriesFromServer(personaId: personaId)
            reload()
        }
        .mfAlert($banner)
        .sheet(isPresented: $showAdd) {
            MemoryEditorSheet(personaId: personaId) {
                reload()
            }
        }
        .confirmationDialog(
            L("忘掉这一条？"),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { presented in if !presented { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(L("忘掉"), role: .destructive) { deletePending() }
            Button(L("取消"), role: .cancel) { pendingDelete = nil }
        } message: {
            Text(L("这条记忆会连同它的索引一起删掉，之后聊天里不会再被想起。"))
        }
    }

    // MARK: - 记忆的健康

    private var healthCard: some View {
        MFCard {
            HStack(alignment: .firstTextBaseline) {
                Text(L("记忆的健康"))
                    .font(.system(.body, design: .serif))
                Spacer()
                Text(Lcount("%d 条记忆", counts.total))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 8) {
                tierCount(L("还在用"), counts.active, color: accent)
                tierCount(L("沉下去了"), counts.dormant, color: .secondary)
                tierCount(L("已归档"), counts.archived, color: .secondary.opacity(0.7))
            }
            .padding(.top, 12)

            Text(L("沉下去的不再优先被想起，关键词命中时还能捞回来；归档的不进上下文，只有他认真回忆时才可能翻到。"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)

            Text(MemoryHealthText.lastRunLine(lastConsolidation))
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 8)

            MFRowDivider(leadingInset: 0)

            HStack(alignment: .center, spacing: 8) {
                // 1.9：记忆与整理都内置开启（§一），这里不再有「先打开开关」的提示。
                Spacer()
                Button {
                    Task { @MainActor in
                        await consolidation.run(env: env, personaId: personaId)
                        reload()
                    }
                } label: {
                    HStack(spacing: 5) {
                        if consolidation.isRunning {
                            ProgressView().scaleEffect(0.65)
                        } else {
                            Image(systemName: "wand.and.stars")
                        }
                        Text(consolidation.isRunning ? L("整理中…") : L("现在整理一次"))
                    }
                    .font(.footnote)
                }
                .disabled(consolidation.isRunning)
                .tint(accent)
            }
            .padding(.top, 10)

            if let message = consolidation.message {
                MFNote(text: message, icon: "checkmark.circle")
            }

            MFNote(text: L("iOS 没有 WorkManager：关掉 App 之后只能等系统有空跑一次，这里的手动入口随时能用。"))
        }
    }

    private func tierCount(_ label: String, _ value: Int, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)")
                .font(.system(size: 22, weight: .medium, design: .serif))
                .foregroundStyle(color)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var groupingBar: some View {
        MFChoiceBar(
            options: [
                (Grouping.importance, L("按重要度")),
                (Grouping.time, L("按时间"))
            ],
            selection: $grouping,
            accent: accent
        )
    }

    // MARK: - 卡片

    private func memoryCard(_ memory: MemoryEntity) -> some View {
        let tier = MemoryTier.parse(memory.status)
        let drawer = env.memoryRepository.drawerValue(memory)
        let tags = MemoryTags.decode(memory.tags)
        let bodyOpacity: Double = {
            switch tier {
            case .active: return 1
            case .dormant: return 0.72
            case .archived: return 0.5
            }
        }()

        return VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                Text(MFMemoryDrawerText.badge(drawer))
                    .font(.caption2)
                    .foregroundStyle(accent)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1)
                    .background(accent.opacity(0.12), in: Capsule())

                if memory.pinned {
                    Label(L("一直记着"), systemImage: "pin.fill")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 4)

                if tier != .active {
                    Text(MFMemoryTierText.tag(tier))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                importanceStars(memory.importance)
            }

            Text(memory.content)
                .font(.system(.body, design: .serif))
                .foregroundStyle(Color.primary.opacity(bodyOpacity))
                .lineLimit(4)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            strengthBar(memory.strength)

            HStack(spacing: 6) {
                if tags.isEmpty {
                    Text(MFDate.relative(memory.updatedAt))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(tags.prefix(3), id: \.self) { tag in
                        Text(tag)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                Text(Lcount("被想起 %d 次", memory.useCount))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if let footnote = MFMemoryTierText.footnote(tier) {
                Text(footnote)
                    .font(.caption2)
                    .foregroundStyle(.secondary.opacity(0.85))
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
                .fill(MFUI.cardFill(scheme))
        )
        .overlay(
            RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
                .strokeBorder(MFUI.cardStroke(scheme), lineWidth: 0.7)
        )
        .contentShape(Rectangle())
    }

    private func importanceStars(_ importance: Int) -> some View {
        HStack(spacing: 1.5) {
            ForEach(1...5, id: \.self) { index in
                Image(systemName: index <= importance ? "star.fill" : "star")
                    .font(.system(size: 8))
                    .foregroundStyle(accent.opacity(index <= importance ? 0.85 : 0.25))
            }
        }
    }

    private func strengthBar(_ strength: Double) -> some View {
        let value = min(max(strength, 0), 1)
        return GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.secondary.opacity(0.15))
                Capsule()
                    .fill(accent.opacity(0.75))
                    .frame(width: max(2, proxy.size.width * CGFloat(value)))
            }
        }
        .frame(height: 4)
        .padding(.vertical, 1)
    }

    // MARK: - 分组

    private struct MemoryGroup {
        let title: String
        let items: [MemoryEntity]
    }

    private var grouped: [MemoryGroup] {
        switch grouping {
        case .importance:
            let pinned = memories.filter { $0.pinned }
            let rest = memories.filter { !$0.pinned }
            let buckets: [(String, [MemoryEntity])] = [
                (L("一直记着"), pinned),
                (L("最要紧的"), rest.filter { $0.importance >= 5 }),
                (L("要紧的"), rest.filter { $0.importance == 4 }),
                (L("平平常常"), rest.filter { $0.importance == 3 }),
                (L("轻的"), rest.filter { $0.importance <= 2 })
            ]
            return buckets.filter { !$0.1.isEmpty }.map { MemoryGroup(title: $0.0, items: $0.1) }

        case .time:
            let calendar = Calendar.current
            let now = Date()
            var today: [MemoryEntity] = []
            var thisWeek: [MemoryEntity] = []
            var thisMonth: [MemoryEntity] = []
            var earlier: [MemoryEntity] = []
            for memory in memories {
                let date = Date(timeIntervalSince1970: Double(memory.updatedAt) / 1000)
                if calendar.isDateInToday(date) {
                    today.append(memory)
                } else if let week = calendar.date(byAdding: .day, value: -7, to: now), date >= week {
                    thisWeek.append(memory)
                } else if let month = calendar.date(byAdding: .month, value: -1, to: now), date >= month {
                    thisMonth.append(memory)
                } else {
                    earlier.append(memory)
                }
            }
            let buckets: [(String, [MemoryEntity])] = [
                (L("今天"), today),
                (L("这一周"), thisWeek),
                (L("这个月"), thisMonth),
                (L("更早"), earlier)
            ]
            return buckets.filter { !$0.1.isEmpty }.map { MemoryGroup(title: $0.0, items: $0.1) }
        }
    }

    // MARK: - 空状态与新增

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("还没有记忆"))
                .font(.system(.body, design: .serif))
            Text(L("在聊天里自然相处，他会自己记下值得记的事；也可以点下面手动记一件想让他记住的事。"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var addButton: some View {
        Button {
            showAdd = true
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "plus.circle")
                    .font(.system(size: 16))
                Text(L("手动记一件事"))
                    .font(.system(.body, design: .serif))
                Spacer()
            }
            .foregroundStyle(accent)
            .padding(.vertical, 14)
            .padding(.horizontal, 14)
            .background(
                RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
                    .strokeBorder(accent.opacity(0.3), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            )
        }
        .buttonStyle(.plain)
        .disabled(personaId == nil)
    }

    // MARK: - 动作

    private func reload() {
        guard let personaId else {
            memories = []
            counts = TierCounts()
            lastConsolidation = nil
            return
        }
        memories = (try? env.memoryRepository.getAllForPersona(personaId)) ?? []
        // 三档数量按「这个人格的记忆」数，避免和下面的列表对不上。
        var next = TierCounts()
        next.dormant = (try? env.stores.memories.count(
            where: "personaId = ? AND status = ?",
            [.text(personaId), .text(MemoryTier.dormant.rawValue)]
        )) ?? 0
        next.archived = (try? env.stores.memories.count(
            where: "personaId = ? AND status = ?",
            [.text(personaId), .text(MemoryTier.archived.rawValue)]
        )) ?? 0
        next.active = (try? env.stores.memories.count(
            where: "personaId = ? AND (status = ? OR status IS NULL OR status = '')",
            [.text(personaId), .text(MemoryTier.active.rawValue)]
        )) ?? 0
        counts = next
        lastConsolidation = (try? env.stores.memoryConsolidations.latest(personaId: personaId)) ?? nil
    }

    private func togglePin(_ memory: MemoryEntity) {
        env.memoryRepository.setPinned(memory, pinned: !memory.pinned)
        reload()
    }

    private func deletePending() {
        guard let memory = pendingDelete else { return }
        pendingDelete = nil
        Task { @MainActor in
            do {
                try await env.deleteMemory(memory)
                reload()
            } catch {
                banner = MFAlert(title: L("没能删掉"), message: error.localizedDescription)
            }
        }
    }
}
