import SwiftUI

/// 一条记忆的详情：正文、来源、以及「它被改写了几次」。
///
/// 这里能看到 `originalContent`：回忆会随着当时的心情改写措辞，第一次写下来的那句
/// 留在原处当锚点。`driftCount` 是它被重新回忆并改写过的次数——这是这套记忆模型里
/// 最容易被忽略、也最像人的一部分，所以给它一块单独的位置。
struct MemoryDetailScreen: View {
    let memoryId: String

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @State private var memory: MemoryEntity?
    @State private var showDeleteConfirm = false
    @State private var banner: MFAlert?

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }

    var body: some View {
        ScrollView {
            if let memory {
                VStack(alignment: .leading, spacing: 16) {
                    contentCard(memory)
                    if hasDrift(memory) {
                        originalCard(memory)
                    }
                    factsCard(memory)
                    if let quote = memory.sourceQuote, !quote.isEmpty {
                        quoteCard(quote)
                    }
                    actionsCard(memory)
                }
                .padding(.horizontal, MFTheme.gutter)
                .padding(.vertical, 16)
            } else {
                Text(L("这条记忆已经不在库里了。"))
                    .font(.system(.body, design: .serif))
                    .foregroundStyle(.secondary)
                    .padding(24)
            }
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(L("他记得的事"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: reload)
        .mfAlert($banner)
        .confirmationDialog(L("忘掉这一条？"), isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button(L("忘掉"), role: .destructive, action: deleteMemory)
            Button(L("取消"), role: .cancel) {}
        } message: {
            Text(L("删掉以后聊天里不会再被想起。固定过的记忆也一样会消失。"))
        }
    }

    // MARK: - 正文

    private func contentCard(_ memory: MemoryEntity) -> some View {
        let tier = MemoryTier.parse(memory.status)
        let drawer = env.memoryRepository.drawerValue(memory)
        let tags = MemoryTags.decode(memory.tags)

        return MFCard {
            HStack(spacing: 6) {
                Text(MFMemoryDrawerText.detail(drawer))
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
                Text(MFMemoryTierText.label(tier))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Text(memory.content)
                .font(.system(.body, design: .serif))
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 10)

            strengthBar(memory.strength)
                .padding(.top, 12)

            HStack(spacing: 8) {
                Text(Lf("分量 %d%%", Int((min(max(memory.strength, 0), 1)) * 100)))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                ForEach(1...5, id: \.self) { index in
                    Image(systemName: index <= memory.importance ? "star.fill" : "star")
                        .font(.system(size: 9))
                        .foregroundStyle(accent.opacity(index <= memory.importance ? 0.85 : 0.25))
                }
            }
            .padding(.top, 6)

            if !tags.isEmpty {
                HStack(spacing: 6) {
                    ForEach(tags, id: \.self) { tag in
                        Text(tag)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.secondary.opacity(0.10), in: Capsule())
                    }
                }
                .padding(.top, 10)
            }

            if let footnote = MFMemoryTierText.footnote(tier) {
                MFNote(text: footnote, icon: "info.circle")
            }
        }
    }

    private func strengthBar(_ strength: Double) -> some View {
        let value = min(max(strength, 0), 1)
        return GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.secondary.opacity(0.15))
                Capsule()
                    .fill(accent.opacity(0.75))
                    .frame(width: max(2, proxy.size.width * CGFloat(value)))
            }
        }
        .frame(height: 5)
    }

    // MARK: - 原文与漂移

    private func hasDrift(_ memory: MemoryEntity) -> Bool {
        guard let original = memory.originalContent, !original.isEmpty else { return false }
        return original != memory.content
    }

    private func originalCard(_ memory: MemoryEntity) -> some View {
        MFCard {
            HStack(spacing: 6) {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                Text(L("第一次被写下来时"))
                    .font(.system(.footnote, design: .serif))
                Spacer()
                if memory.driftCount > 0 {
                    Text(Lcount("改写 %d 次", memory.driftCount))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Text(memory.originalContent ?? "")
                .font(.system(.callout, design: .serif))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 8)

            MFNote(text: L("回忆不是回放：每次被想起，措辞都可能被当时的心情改写一点。原文留在这里当锚点。"))
        }
    }

    // MARK: - 事实

    private func factsCard(_ memory: MemoryEntity) -> some View {
        MFCard {
            Text(L("关于这条记忆"))
                .font(.system(.body, design: .serif))
                .padding(.bottom, 6)

            factRow(L("分类"), MFMemoryText.categoryLabel(memory.category))
            factRow(L("被想起"), Lcount("%d 次", memory.useCount))
            factRow(L("上次被想起"), memory.lastUsedAt > 0 ? MFDate.relative(memory.lastUsedAt) : L("还没被想起过"))
            factRow(L("记下来"), MFDate.relative(memory.createdAt))
            factRow(L("最近改动"), MFDate.relative(memory.updatedAt))
            if let happenedAt = memory.happenedAt, happenedAt > 0 {
                factRow(L("事情发生"), MFDate.day(happenedAt))
            }
            if let consolidatedAt = memory.consolidatedAt, consolidatedAt > 0 {
                factRow(L("上次整理"), MFDate.relative(consolidatedAt))
            }
            if let archivedAt = memory.archivedAt, archivedAt > 0 {
                factRow(L("归档于"), MFDate.relative(archivedAt))
            }
        }
    }

    private func factRow(_ title: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .font(.caption)
                .multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 4)
    }

    private func quoteCard(_ quote: String) -> some View {
        MFCard {
            Text(L("它是从这句话里来的"))
                .font(.system(.footnote, design: .serif))
            Text(quote)
                .font(.system(.callout, design: .serif))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 8)
        }
    }

    // MARK: - 动作

    private func actionsCard(_ memory: MemoryEntity) -> some View {
        MFCard {
            Button {
                env.memoryRepository.setPinned(memory, pinned: !memory.pinned)
                reload()
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: memory.pinned ? "pin.slash" : "pin")
                        .font(.system(size: 14))
                        .frame(width: 22)
                    Text(memory.pinned ? L("取消固定") : L("固定为重要"))
                        .font(.system(.body, design: .serif))
                    Spacer()
                }
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            MFRowDivider(leadingInset: 0)

            Button {
                showDeleteConfirm = true
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "trash")
                        .font(.system(size: 14))
                        .frame(width: 22)
                    Text(L("忘掉这一条"))
                        .font(.system(.body, design: .serif))
                    Spacer()
                }
                .foregroundStyle(MFUI.danger)
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private func reload() {
        memory = (try? env.stores.memories.byId(memoryId)) ?? nil
    }

    private func deleteMemory() {
        guard let memory else { return }
        env.memoryRepository.delete(memory)
        dismiss()
    }
}
