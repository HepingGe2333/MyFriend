import SwiftUI

/// 人格预设列表（`spec/persona-guidance/README.md` §2.2）。
///
/// 预设是「一整套设定」：名字、一句介绍、提示词、世界、话风。用户先在这里把一份设定做好，
/// 再「应用」到某个人格或「复制」成一份新人格——这样调设定时不用反复动正在聊的那个人。
///
/// 入口：设置 → 联系人 → 人格预设；人格编辑页里也有一个入口。
/// 样式与 `PersonaListScreen` 一致：一排卡片，点进去改，长按删除。
struct PresetListScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var presets: [PersonaPresetEntity] = []
    @State private var pendingDelete: PersonaPresetEntity?
    @State private var banner: MFAlert?

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header

                if presets.isEmpty {
                    emptyState
                } else {
                    ForEach(presets, id: \.id) { preset in
                        NavigationLink(destination: PresetEditorScreen(presetId: preset.id)) {
                            presetCard(preset)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button(role: .destructive) {
                                pendingDelete = preset
                            } label: {
                                Label(L("删除"), systemImage: "trash")
                            }
                        }
                    }
                }

                newPresetButton
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(L("人格预设"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: reload)
        // 服务端模式：预设是服务端权威，进页面先拉一次并刷新本地缓存。
        .task {
            guard env.serverModeActive else { return }
            await env.refreshPresetsFromServer()
            reload()
        }
        .mfAlert($banner)
        .confirmationDialog(
            pendingDelete.map { Lf("删除预设「%@」？", $0.name) } ?? L("删除这个预设？"),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { presented in if !presented { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(L("删除"), role: .destructive) { deletePending() }
            Button(L("取消"), role: .cancel) { pendingDelete = nil }
        } message: {
            Text(L("只删这份设定；已经应用过的人格不会被改回去。"))
        }
    }

    // MARK: - 头部 / 空状态 / 新建

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("人格预设"))
                .font(.system(size: 26, weight: .bold, design: .serif))
            Text(L("一整套设定：他是谁、他在什么世界、他怎么说话。做好一份，可以反复用在不同的人身上。"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
    }

    private var emptyState: some View {
        MFCard {
            VStack(alignment: .leading, spacing: 8) {
                Text(L("还没有预设"))
                    .font(.system(.body, design: .serif))
                Text(L("预设不是另一个人，是一份可以复用的底稿。世界与话风都可以留空。"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 6)
        }
    }

    private var newPresetButton: some View {
        NavigationLink(destination: PresetEditorScreen(presetId: nil)) {
            HStack(spacing: 6) {
                Image(systemName: "plus")
                    .font(.system(size: 11, weight: .bold))
                Text(L("新建预设"))
                    .font(.footnote.weight(.medium))
            }
            .foregroundStyle(accent)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // MARK: - 卡片

    private func presetCard(_ preset: PersonaPresetEntity) -> some View {
        let world = WorldPreset.decode(preset.worldJson)
        let style = SpeechStyle.decode(preset.speechStyleJson)
        return HStack(alignment: .top, spacing: 12) {
            Image(systemName: "doc.text")
                .font(.system(size: 16))
                .foregroundStyle(accent)
                .frame(width: 34, height: 34)
                .background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(preset.name.isEmpty ? L("还没有名字") : preset.name)
                    .font(.system(.body, design: .serif))
                    .lineLimit(1)
                Text(preset.description.isEmpty ? L("还没有一句介绍") : preset.description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    if let world, !world.isEmpty {
                        label(world.worldName.isEmpty ? L("有世界设定") : world.worldName)
                    } else {
                        label(L("没有世界"))
                    }
                    if let style, !style.isEmpty {
                        label(L("有话风"))
                    } else {
                        label(L("没有话风"))
                    }
                }
                .padding(.top, 1)
            }
            Spacer(minLength: 4)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.tertiary)
                .padding(.top, 10)
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.secondary.opacity(0.06))
        )
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Color.secondary.opacity(0.10), in: Capsule())
    }

    // MARK: - 数据

    private func reload() {
        presets = (try? env.stores.presets.allOrderedByUpdatedAt()) ?? []
    }

    private func deletePending() {
        guard let preset = pendingDelete else { return }
        pendingDelete = nil
        Task { @MainActor in
            do {
                try await env.deletePreset(id: preset.id)
                reload()
            } catch {
                banner = MFAlert(title: L("没能删掉"), message: error.localizedDescription)
            }
        }
    }
}
