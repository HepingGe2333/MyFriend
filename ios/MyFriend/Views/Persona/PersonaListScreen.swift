import SwiftUI

/// 联系人（人格）列表：一排卡片，点进去编辑。
///
/// 1.9 起首页就是联系人列表，这个页面退成了「管理」：卡上放头像、名字、一句简介、
/// 此刻的心情与版本号；长按出「设为默认 / 删除」，底部是新建。
/// iOS 的差异：不用分类筛选（这个版本的人格数量通常个位数，多一层筛选只会多一次点击）。
struct PersonaListScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var personas: [PersonaEntity] = []
    @State private var pendingDelete: PersonaEntity?
    @State private var banner: MFAlert?

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }
    private var activePersonaId: String? {
        if let id = env.currentSettings.activePersonaId,
           personas.contains(where: { $0.id == id }) {
            return id
        }
        return personas.first?.id
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header

                if personas.isEmpty {
                    emptyState
                } else {
                    ForEach(personas, id: \.id) { persona in
                        NavigationLink(destination: PersonaEditorScreen(personaId: persona.id)) {
                            personaCard(persona)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button {
                                makeDefault(persona)
                            } label: {
                                Label("设为默认", systemImage: "checkmark.circle")
                            }
                            Button(role: .destructive) {
                                askDelete(persona)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                    }
                }

                newPersonaButton
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle("人格")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: reload)
        .mfAlert($banner)
        .confirmationDialog(
            pendingDelete.map { "删除「\($0.name)」？" } ?? "删除这个人格？",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { presented in if !presented { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) { deletePending() }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("这个人的聊天、记忆与主动节奏都会一起删掉。至少留着一个人格。")
        }
    }

    // MARK: - 头部

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("人格")
                .font(.system(size: 26, weight: .bold, design: .serif))
            Text("每个人格拥有独立的提示词、记忆与上下文。点一个改，长按可以设为默认或删除。")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
    }

    // MARK: - 卡片

    private func personaCard(_ persona: PersonaEntity) -> some View {
        let active = persona.id == activePersonaId
        return HStack(alignment: .top, spacing: 14) {
            MFAvatar(persona: persona, size: 52)

            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(persona.name)
                        .font(.system(.title3, design: .serif))
                        .lineLimit(1)
                    if persona.isDefault || active {
                        Text(persona.isDefault ? "默认" : "正在聊")
                            .font(.caption2)
                            .foregroundStyle(accent)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(accent.opacity(0.14), in: Capsule())
                    }
                    Spacer(minLength: 4)
                }

                Text(persona.description.isEmpty ? "还没有简介" : persona.description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 7) {
                    if let mood = persona.mood, !mood.isEmpty {
                        moodCapsule(mood, intensity: persona.moodIntensity ?? 2)
                    }
                    Text("v\(persona.version)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 1)
            }

            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.tertiary)
                .padding(.top, 6)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
                .fill(MFUI.cardFill(scheme))
        )
        .overlay(
            RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
                .strokeBorder(active ? accent.opacity(0.35) : MFUI.cardStroke(scheme), lineWidth: active ? 1 : 0.7)
        )
        .contentShape(Rectangle())
    }

    private func moodCapsule(_ mood: String, intensity: Int) -> some View {
        HStack(spacing: 4) {
            Image(systemName: "heart")
                .font(.system(size: 9))
            Text(mood)
                .font(.caption2)
            Text("\(min(max(intensity, 1), 5))/5")
                .font(.caption2)
                .opacity(0.75)
        }
        .foregroundStyle(accent)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(accent.opacity(0.12), in: Capsule())
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("还没有人格")
                .font(.system(.body, design: .serif))
            Text("创建一个人格，写下他的性格与说话方式，然后就可以开始说话了。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var newPersonaButton: some View {
        NavigationLink(destination: PersonaEditorScreen(personaId: nil)) {
            HStack(spacing: 10) {
                Image(systemName: "plus.circle")
                    .font(.system(size: 16))
                Text("新建一个人格")
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
    }

    // MARK: - 动作

    private func reload() {
        personas = (try? env.stores.personas.allOrderedByUpdatedAt()) ?? []
    }

    private func makeDefault(_ persona: PersonaEntity) {
        let now = DomainClock.nowMillis
        for other in personas where other.id != persona.id && other.isDefault {
            var cleared = other
            cleared.isDefault = false
            cleared.updatedAt = now
            try? env.stores.personas.upsert(cleared)
        }
        var updated = persona
        updated.isDefault = true
        updated.updatedAt = now
        do {
            try env.stores.personas.upsert(updated)
            env.updateSettings { $0.activePersonaId = persona.id }
            reload()
            banner = MFAlert(title: "换过来了", message: "接下来先跟你说话的是「\(persona.name)」。")
        } catch {
            banner = MFAlert(title: "没能切换", message: error.localizedDescription)
        }
    }

    private func askDelete(_ persona: PersonaEntity) {
        guard personas.count > 1 else {
            banner = MFAlert(
                title: "先留着他",
                message: "至少要留着一个人格。想换掉的话，先新建一个，再回来删这个。"
            )
            return
        }
        pendingDelete = persona
    }

    private func deletePending() {
        guard let persona = pendingDelete else { return }
        pendingDelete = nil
        do {
            // 删人的一串动作（含记忆索引）统一在装配层，首页与这里共用同一份。
            try env.deletePersona(persona)
            if env.currentSettings.activePersonaId == persona.id {
                let next = (try? env.stores.personas.allOrderedByUpdatedAt())?.first?.id
                env.updateSettings { $0.activePersonaId = next }
            }
            reload()
            banner = MFAlert(title: "删掉了", message: "「\(persona.name)」和他的聊天、记忆都删掉了。")
        } catch {
            banner = MFAlert(title: "没能删掉", message: error.localizedDescription)
        }
    }
}
