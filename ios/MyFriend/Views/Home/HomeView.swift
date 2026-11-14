import SwiftUI

/// 首页 = 联系人列表（1.9 §七）。
///
/// 这一版把「人格」和「最近对话」合成一件事：**一个人就是一段会话**。
/// 所以首页不再有「人格一排 + 最近对话」两块，也没有「新建对话」——
/// 聊天这件事不再是「开一段新的」，而是「去找那个人」。
///
/// 一行一个联系人：头像、名字、最后一句、时间、未读数。
/// 右上角是设置（人格管理也搬进了设置里）；右下角的「+」是**新建人格**，不是新建对话。
/// 长按某一行可以删掉这个人（连同他的聊天、记忆与主动节奏，二次确认）。
struct HomeView: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    /// 一行 = 一个人 + 他的那一段会话。
    private struct Contact: Identifiable {
        let persona: PersonaEntity
        let session: ChatSessionEntity?

        var id: String { persona.id }

        /// 排序用：说过话的人按最后一句的时间，没说过话的按人格更新时间。
        var sortAt: Int64 {
            session?.lastMessageAt ?? persona.updatedAt
        }

        var unread: Int { session?.unreadCount ?? 0 }
    }

    @State private var contacts: [Contact] = []
    @State private var errorText: String?
    @State private var pendingDelete: PersonaEntity?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                if contacts.isEmpty {
                    emptyState
                } else {
                    contactList
                }
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.bottom, 110)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .overlay(alignment: .bottomTrailing) { newPersonaButton }
        .onAppear(perform: reload)
        .onChange(of: env.currentSettings.activePersonaId) { _ in reload() }
        .refreshable { reload() }
        .alert("出了点问题", isPresented: Binding(
            get: { errorText != nil },
            set: { if !$0 { errorText = nil } }
        )) {
            Button("知道了", role: .cancel) { errorText = nil }
        } message: {
            Text(errorText ?? "")
        }
        .confirmationDialog(
            pendingDelete.map { "删除「\($0.name)」？" } ?? "删除这个人？",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { presented in if !presented { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) { deletePending() }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("他的聊天、记忆与主动节奏都会一起删掉，这段关系没法恢复。至少留着一个人。")
        }
    }

    // MARK: - 头部

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text("MyFriend")
                    .font(.system(size: 32, weight: .bold, design: .serif))
                Spacer()
                NavigationLink(value: Route.personas) {
                    Image(systemName: "person.2")
                        .font(.system(size: 17))
                        .foregroundStyle(.primary)
                }
                .padding(.leading, 18)
                .accessibilityLabel(Text("管理联系人"))
                NavigationLink(value: Route.settings) {
                    Image(systemName: "gearshape")
                        .font(.system(size: 18))
                        .foregroundStyle(.primary)
                }
                .padding(.leading, 16)
                .accessibilityLabel(Text("设置"))
            }
            Text("留一处安静的地方，慢慢说话")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 12)
    }

    // MARK: - 列表

    private var contactList: some View {
        VStack(spacing: 0) {
            ForEach(contacts) { contact in
                Button {
                    open(contact)
                } label: {
                    contactRow(contact)
                }
                .buttonStyle(.plain)
                .contextMenu {
                    Button {
                        open(contact)
                    } label: {
                        Label("去找他", systemImage: "bubble.left")
                    }
                    NavigationLink(destination: PersonaEditorScreen(personaId: contact.persona.id)) {
                        Label("编辑这个人", systemImage: "pencil")
                    }
                    Button(role: .destructive) {
                        askDelete(contact.persona)
                    } label: {
                        Label("删除这个人", systemImage: "trash")
                    }
                }
                if contact.id != contacts.last?.id {
                    Divider().padding(.leading, 70)
                }
            }
        }
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
                .fill(scheme == .dark ? Color.white.opacity(0.04) : Color.white)
        )
    }

    private func contactRow(_ contact: Contact) -> some View {
        HStack(spacing: 12) {
            MFAvatar(persona: contact.persona, size: 46)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(contact.persona.name)
                        .font(.system(.body, design: .serif))
                        .lineLimit(1)
                    if contact.persona.id == env.currentSettings.activePersonaId {
                        Text("正在聊")
                            .font(.caption2)
                            .foregroundStyle(MFTheme.defaultAccent)
                    }
                    Spacer(minLength: 8)
                    if let at = contact.session?.lastMessageAt {
                        Text(MFDate.relative(at))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                HStack(spacing: 6) {
                    Text(preview(contact))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    if contact.unread > 0 {
                        Text("\(contact.unread)")
                            .font(.caption2.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(MFTheme.defaultAccent, in: Capsule())
                    }
                }
                if let mood = contact.persona.mood, !mood.isEmpty {
                    Text("此刻：\(mood)")
                        .font(.caption2)
                        .foregroundStyle(MFTheme.defaultAccent.opacity(0.85))
                        .lineLimit(1)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    private func preview(_ contact: Contact) -> String {
        guard let session = contact.session else { return "还没有说过话" }
        let text = (session.lastMessagePreview ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? "还没有说过话" : text
    }

    // MARK: - 空状态

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("这里还没有人")
                .font(.system(.body, design: .serif))
            Text("点右下角的「+」，先给自己建一个联系人：写下他是谁、怎么说话，然后跟他说第一句。")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 32)
    }

    // MARK: - 新建人格

    private var newPersonaButton: some View {
        NavigationLink(destination: PersonaEditorScreen(personaId: nil)) {
            Image(systemName: "plus")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(.white)
                .frame(width: 54, height: 54)
                .background(MFTheme.defaultAccent, in: Circle())
                .shadow(color: MFTheme.defaultAccent.opacity(0.3), radius: 10, y: 4)
        }
        .buttonStyle(.plain)
        .padding(.trailing, 20)
        .padding(.bottom, 24)
        .accessibilityLabel(Text("新建联系人"))
    }

    // MARK: - 动作

    private func reload() {
        do {
            let personas = try env.stores.personas.allOrderedByUpdatedAt()
            contacts = personas.map { persona in
                Contact(
                    persona: persona,
                    session: (try? env.stores.sessions.session(forPersona: persona.id)) ?? nil
                )
            }
            .sorted { lhs, rhs in
                if lhs.sortAt != rhs.sortAt { return lhs.sortAt > rhs.sortAt }
                return lhs.persona.name < rhs.persona.name
            }
        } catch {
            errorText = error.localizedDescription
        }
    }

    /// 点一行 = 去找他。会话不存在就顺手建一段（一人一段，§二）。
    private func open(_ contact: Contact) {
        guard let session = env.session(forPersona: contact.persona.id) else {
            errorText = "这段对话没建成，检查一下本地存储空间。"
            return
        }
        env.updateSettings { $0.activePersonaId = contact.persona.id }
        reload()
        path?.wrappedValue.append(Route.chat(sessionId: session.id))
    }

    /// 首页需要一个能主动 push 的路径（`NavigationLink(value:)` 只在视图树里可用，
    /// 而「点一行先建会话再跳」需要先有会话 id）。
    @Environment(\.mfNavigationPath) private var path

    private func askDelete(_ persona: PersonaEntity) {
        guard contacts.count > 1 else {
            errorText = "至少要留着一个人。想换掉的话，先新建一个，再回来删这个。"
            return
        }
        pendingDelete = persona
    }

    private func deletePending() {
        guard let persona = pendingDelete else { return }
        pendingDelete = nil
        do {
            try env.deletePersona(persona)
            if env.currentSettings.activePersonaId == persona.id {
                let next = (try? env.stores.personas.allOrderedByUpdatedAt())?.first?.id
                env.updateSettings { $0.activePersonaId = next }
            }
            reload()
        } catch {
            errorText = error.localizedDescription
        }
    }
}
