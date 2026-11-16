import Combine
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
    @Environment(\.scenePhase) private var scenePhase

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
    /// 最近读到的会话摘要（未读 / 预览 / 最后一条时间）。
    ///
    /// 为什么要有它：首页原来只在 `onAppear` 读一次库，心跳/主动唤醒在用户停留首页时
    /// 写了消息，未读角标与「最后一句」不会变（0.10.2 审计 P2-4）。
    /// 现在页面可见且 App 在前台时每 4 秒对一次这三列（只读 `chat_sessions`，不碰消息正文），
    /// 变了才重画；空闲时没有别的轮询。
    @State private var digests: [SessionDigest] = []
    /// 页面是否在屏幕上（`onAppear` / `onDisappear` 之间）。
    @State private var isVisible = false
    /// App 是否在前台（`scenePhase` 的缓存：Task 里要读的是最新值，而不是闭包捕获的那一份）。
    @State private var sceneActive = true
    /// 4 秒一拍的摘要核对（只在可见 + 前台时活着）。
    @State private var pulseTask: Task<Void, Never>?

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
        .onAppear {
            // 生成主动消息前也要有译文（§2）：首页出现时给"正在聊的那个人"踢一次。
            if let activeId = env.currentSettings.activePersonaId {
                PersonaTranslationTrigger.kick(env, personaId: activeId)
            } else if let defaultPersona = (try? env.stores.personas.defaultPersona()) ?? nil {
                PersonaTranslationTrigger.kick(env, personaId: defaultPersona.id)
            }
            isVisible = true
            reload()
            startDigestPulse()
        }
        .onDisappear {
            isVisible = false
            stopDigestPulse()
        }
        .onChange(of: env.currentSettings.activePersonaId) { _ in reload() }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                sceneActive = true
                // 从后台回来：先核一次（这段时间里可能有人写过消息），再等下一拍。
                refreshDigestsIfChanged()
                startDigestPulse()
            case .background, .inactive:
                sceneActive = false
                stopDigestPulse()
            @unknown default:
                break
            }
        }
        .refreshable { reload() }
        .alert(L("出了点问题"), isPresented: Binding(
            get: { errorText != nil },
            set: { if !$0 { errorText = nil } }
        )) {
            Button(L("知道了"), role: .cancel) { errorText = nil }
        } message: {
            Text(errorText ?? "")
        }
        .confirmationDialog(
            pendingDelete.map { Lf("删除「%@」？", $0.name) } ?? L("删除这个人？"),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { presented in if !presented { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(L("删除"), role: .destructive) { deletePending() }
            Button(L("取消"), role: .cancel) { pendingDelete = nil }
        } message: {
            Text(L("他的聊天、记忆与主动节奏都会一起删掉，这段关系没法恢复。至少留着一个人。"))
        }
    }

    // MARK: - 头部

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text("Yap")
                    .font(.system(size: 32, weight: .bold, design: .serif))
                Spacer()
                NavigationLink(value: Route.search) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 17))
                        .foregroundStyle(.primary)
                }
                .padding(.leading, 16)
                .accessibilityLabel(Text(L("搜索聊天记录")))
                NavigationLink(value: Route.personas) {
                    Image(systemName: "person.2")
                        .font(.system(size: 17))
                        .foregroundStyle(.primary)
                }
                .padding(.leading, 18)
                .accessibilityLabel(Text(L("管理联系人")))
                NavigationLink(value: Route.settings) {
                    Image(systemName: "gearshape")
                        .font(.system(size: 18))
                        .foregroundStyle(.primary)
                }
                .padding(.leading, 16)
                .accessibilityLabel(Text(L("设置")))
            }
            // 英文这句比中文长一倍（「A quiet place to talk, unhurried」），留两行并允许纵向伸展，
            // 否则在 iPhone SE 这类窄屏上会被压成一行后截断。
            Text(L("留一处安静的地方，慢慢说话"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
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
                        Label(L("去找他"), systemImage: "bubble.left")
                    }
                    NavigationLink(destination: PersonaEditorScreen(personaId: contact.persona.id)) {
                        Label(L("编辑这个人"), systemImage: "pencil")
                    }
                    Button(role: .destructive) {
                        askDelete(contact.persona)
                    } label: {
                        Label(L("删除这个人"), systemImage: "trash")
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
                        Text(L("正在聊"))
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
                    Text(Lf("此刻：%@", mood))
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
        guard let session = contact.session else { return L("还没有说过话") }
        let text = (session.lastMessagePreview ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? L("还没有说过话") : text
    }

    // MARK: - 空状态

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("这里还没有人"))
                .font(.system(.body, design: .serif))
            Text(L("点右下角的「+」，先给自己建一个联系人：写下他是谁、怎么说话，然后跟他说第一句。"))
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
        .accessibilityLabel(Text(L("新建联系人")))
    }

    // MARK: - 动作

    private func reload() {
        do {
            let personas = try env.stores.personas.allOrderedByUpdatedAt()
            let sessions = try env.stores.sessions.allOrderedByRecency()
            var byPersona: [String: ChatSessionEntity] = [:]
            for session in sessions where byPersona[session.personaId] == nil {
                byPersona[session.personaId] = session
            }
            contacts = personas.map { persona in
                Contact(persona: persona, session: byPersona[persona.id])
            }
            .sorted { lhs, rhs in
                if lhs.sortAt != rhs.sortAt { return lhs.sortAt > rhs.sortAt }
                return lhs.persona.name < rhs.persona.name
            }
            digests = HomeDigest.digests(from: sessions)
        } catch {
            errorText = error.localizedDescription
        }
    }

    // MARK: - 观察后台写入（0.11.0 审计 P2-4）

    /// 首页核一次会话摘要的间隔（只在可见 + 前台时跑）。
    static let pulseIntervalSeconds: TimeInterval = 4

    /// 起一拍「观察聚合」（0.11.1 审计 P2-12）。
    ///
    /// 原来用 `Timer.publish(...).autoconnect()`：订阅绑在视图树上，`onDisappear` 不会退订，
    /// 首页被 push 走之后仍然每 4 秒唤醒主线程（与聊天页「不可见即停」的口径不一致）。
    /// 改成受控 `Task`：不可见 / 进后台立刻取消，回来再起，节奏与 `pulseIntervalSeconds` 一致。
    private func startDigestPulse() {
        stopDigestPulse()
        pulseTask = Task {
            while !Task.isCancelled {
                do {
                    try await Task.sleep(
                        nanoseconds: UInt64(Self.pulseIntervalSeconds * 1_000_000_000)
                    )
                } catch {
                    return
                }
                guard !Task.isCancelled, isVisible, sceneActive else { return }
                refreshDigestsIfChanged()
            }
        }
    }

    private func stopDigestPulse() {
        pulseTask?.cancel()
        pulseTask = nil
    }

    /// 会话摘要变了才重画（未读 / 预览 / 最后一条时间）。
    ///
    /// 只读 `chat_sessions` 的几列（没有消息正文），一次几十行；值没变就什么都不做，
    /// 不会像 0.10.2 那样「只有 onAppear 读一次」，也不会每拍重画整张列表。
    private func refreshDigestsIfChanged() {
        let fresh = HomeDigest.digests(from: (try? env.stores.sessions.allOrderedByRecency()) ?? [])
        guard HomeDigest.changed(old: digests, new: fresh) else { return }
        reload()
    }

    /// 点一行 = 去找他。会话不存在就顺手建一段（一人一段，§二）。
    private func open(_ contact: Contact) {
        guard let session = env.session(forPersona: contact.persona.id) else {
            errorText = L("这段对话没建成，检查一下本地存储空间。")
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
            errorText = L("至少要留着一个人。想换掉的话，先新建一个，再回来删这个。")
            return
        }
        pendingDelete = persona
    }

    private func deletePending() {
        guard let persona = pendingDelete else { return }
        pendingDelete = nil
        Task { @MainActor in
            do {
                try await env.deletePersonaAsync(persona)
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
}
