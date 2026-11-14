import SwiftUI

// 来源：app/src/main/java/com/myfriend/app/ui/screens/proactive/ProactiveScreen.kt
//
// 「主动唤醒」：他被允许在哪些时刻主动来找你。
//
// 对齐 Android 的那个页面，但**没有**照搬全部：这里只有三件事，
// 因为 1.9 把「每天最多主动几次」交给了人格，规则页不该再长出第二套参数。
// 1. 看：每条规则的一句话说明、节奏、下次大概什么时候、是谁安排的；
// 2. 开关 / 删除：不喜欢随时关掉或删掉（删规则不动已经聊过的内容）；
// 3. 手动建一条：到点由他自己按人格写那句话，这里只定「大约什么时候」。
//
// 时间不是准点：规则到点后会变成一条带窗口的念头（`AgentIntentSupport.materializeDueRules`），
// 由心跳在他的克制范围内决定要不要真的开口。所以列表里写的是「下次大概 … 前后」。
//
// 视觉沿用项目里的克制风格（`MFTheme` / `MFCard` / `MFNote`），没有自己的新配色。

struct ProactiveListScreen: View {

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var rules: [ProactiveRulePresentation] = []
    @State private var personas: [PersonaEntity] = []
    @State private var pendingDelete: ProactiveRulePresentation?
    @State private var banner: MFAlert?
    @State private var showingCreate = false

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }
    private var enabledCount: Int { rules.filter(\.enabled).count }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                if rules.isEmpty {
                    emptyState
                } else {
                    listCard
                }
                footnote
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle("主动唤醒")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showingCreate = true
                } label: {
                    Image(systemName: "plus")
                }
                .disabled(personas.isEmpty)
                .accessibilityLabel("新建主动唤醒")
            }
        }
        .sheet(isPresented: $showingCreate) {
            ProactiveRuleEditorSheet(
                personas: personas,
                defaultPersonaId: env.currentPersona()?.id
            ) { draft in
                create(draft)
            }
        }
        .mfAlert($banner)
        .confirmationDialog(
            pendingDelete.map { _ in "删除这条规则？" } ?? "",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { presented in if !presented { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                guard let rule = pendingDelete else { return }
                pendingDelete = nil
                delete(rule)
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text(deleteMessage)
        }
        .onAppear {
            reload()
            #if DEBUG
            // 截图验收：`simctl` 点不了「+」，用它把新建表单直接打开。
            if ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_PROACTIVE_CREATE_SHEET"] == "1" {
                showingCreate = true
            }
            #endif
        }
    }

    // MARK: - 头部

    private var header: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("他会主动来找你")
                .font(.system(.title3, design: .serif))
            Text(rules.isEmpty
                 ? "还没有节奏。设一个时间，或者直接在聊天里说个大概时间。"
                 : "\(enabledCount) 条开着 · 共 \(rules.count) 条；时间是一段窗口，不是闹钟。")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
    }

    private var deleteMessage: String {
        guard let rule = pendingDelete else { return "" }
        return "\(rule.note)\n删除后不再按这个节奏找你，已经聊过的内容都会留着。"
    }

    // MARK: - 空态

    private var emptyState: some View {
        MFCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    Image(systemName: "alarm")
                        .font(.system(size: 16))
                        .foregroundStyle(accent)
                    Text("还没有主动唤醒规则")
                        .font(.system(.body, design: .serif))
                }
                Text("设一个时间，或直接在聊天里说个大概时间，他会自己安排。"
                     + "不喜欢随时关掉。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 6)
        }
    }

    // MARK: - 列表

    private var listCard: some View {
        MFCard(padding: 6) {
            VStack(spacing: 0) {
                ForEach(Array(rules.enumerated()), id: \.element.id) { index, rule in
                    row(rule)
                    if index < rules.count - 1 { MFRowDivider() }
                }
            }
        }
    }

    private func row(_ rule: ProactiveRulePresentation) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(rule.note)
                        .font(.system(.body, design: .serif))
                        .foregroundStyle(rule.enabled ? .primary : .secondary)
                    Text("\(rule.personaName) · \(rule.scheduleLabel) · \(rule.sourceLabel)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(rule.nextLabel)
                        .font(.caption)
                        .foregroundStyle(rule.enabled ? accent : .secondary)
                }
                Spacer(minLength: 6)
                Toggle("", isOn: Binding(
                    get: { rule.enabled },
                    set: { setEnabled(rule, enabled: $0) }
                ))
                .labelsHidden()
                .tint(accent)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .contextMenu {
            Button {
                setEnabled(rule, enabled: !rule.enabled)
            } label: {
                Label(rule.enabled ? "先关掉" : "打开", systemImage: rule.enabled ? "pause.circle" : "play.circle")
            }
            Button(role: .destructive) {
                pendingDelete = rule
            } label: {
                Label("删除", systemImage: "trash")
            }
        }
    }

    // MARK: - 脚注

    private var footnote: some View {
        MFNote(
            text: "规则到点后他仍然可以决定这次不说话——人不是闹钟。"
                + "iOS 不保证后台准时：系统可能很久才给一次机会，所以「下次大概」只表示他最早会在那时想到你。",
            icon: "clock"
        )
    }

    // MARK: - 数据

    private func reload() {
        personas = ((try? env.stores.personas.allOrderedByUpdatedAt()) ?? []).reversed()
        let entities = (try? env.stores.proactiveRules.all(orderBy: "nextTriggerAt ASC")) ?? []
        rules = ProactiveRulePresenter.present(rules: entities, personas: personas)
    }

    private func setEnabled(_ rule: ProactiveRulePresentation, enabled: Bool) {
        guard env.proactiveRules.setEnabled(id: rule.id, enabled: enabled) else {
            banner = MFAlert(title: "没改成功", message: "这条规则可能已经被删掉了。")
            reload()
            return
        }
        reload()
    }

    private func create(_ draft: ProactiveRuleDraft) {
        let result = env.proactiveRules.createManual(
            personaId: draft.personaId,
            sessionId: env.session(forPersona: draft.personaId)?.id,
            kind: draft.kind,
            timeOfDayMinutes: draft.timeOfDayMinutes,
            intervalMinutes: draft.intervalMinutes,
            hint: draft.hint,
            note: draft.note
        )
        switch result {
        case .success:
            showingCreate = false
            reload()
        case .failure(let error):
            banner = MFAlert(title: "没建成", message: error.localizedDescription)
        }
    }

    private func delete(_ rule: ProactiveRulePresentation) {
        guard env.proactiveRules.delete(id: rule.id) else {
            banner = MFAlert(title: "没删掉", message: "这条规则可能已经被删掉了。")
            reload()
            return
        }
        reload()
    }
}

// MARK: - 新建

/// 手动建一条规则时表单里填的东西。
struct ProactiveRuleDraft {
    var personaId: String
    var kind: ProactiveKind
    /// 每天固定的时刻（距零点分钟数），`kind == .daily` 时用。
    var timeOfDayMinutes: Int?
    /// 间隔分钟数，`kind == .interval` 时用。
    var intervalMinutes: Int?
    var note: String
    var hint: String
}

/// 「新建主动唤醒」的最小表单：类型 + 时间/间隔 + 一句话说明 + 写作指引。
///
/// 不走模型：Android 也是这么做的（用户设的是「大概什么时候」，那句话到点由他自己写）。
struct ProactiveRuleEditorSheet: View {

    let personas: [PersonaEntity]
    var defaultPersonaId: String?
    let onCreate: (ProactiveRuleDraft) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var personaId: String = ""
    @State private var kind: ProactiveKind = .daily
    @State private var time: Date = Self.defaultTime
    @State private var intervalHours: Int = 3
    @State private var note: String = ""
    @State private var hint: String = ""

    private static var defaultTime: Date {
        var components = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        components.hour = 22
        components.minute = 30
        return Calendar.current.date(from: components) ?? Date()
    }

    var body: some View {
        NavigationStack {
            Form {
                if personas.count > 1 {
                    Section("谁") {
                        Picker("联系人", selection: $personaId) {
                            ForEach(personas, id: \.id) { persona in
                                Text(persona.name).tag(persona.id)
                            }
                        }
                    }
                }

                Section {
                    Picker("触发方式", selection: $kind) {
                        Text("每天固定时刻").tag(ProactiveKind.daily)
                        Text("每隔几小时").tag(ProactiveKind.interval)
                    }
                    .pickerStyle(.segmented)

                    if kind == .daily {
                        DatePicker("时间", selection: $time, displayedComponents: .hourAndMinute)
                    } else {
                        Stepper("间隔 \(intervalHours) 小时", value: $intervalHours, in: 1...24)
                    }
                } header: {
                    Text("什么时候")
                } footer: {
                    Text(kind == .daily
                         ? "到点不一定准时：你看到的是一个窗口。"
                         : "间隔太短会显得像查岗，1–24 小时比较像人。")
                }

                Section {
                    TextField("例如：睡前问候（可留空）", text: $note)
                    TextField("例如：像往常一样轻声问他今天过得怎么样（可留空）", text: $hint, axis: .vertical)
                        .lineLimit(2...4)
                } header: {
                    Text("说什么")
                } footer: {
                    Text("到点那句话由他自己按人格写，这里只影响语气与角度。")
                }
            }
            .navigationTitle("新建主动唤醒")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        onCreate(draft)
                    }
                    .disabled(personaId.isEmpty)
                }
            }
            .onAppear {
                if personaId.isEmpty {
                    personaId = defaultPersonaId ?? personas.first?.id ?? ""
                }
            }
        }
    }

    private var draft: ProactiveRuleDraft {
        let components = Calendar.current.dateComponents([.hour, .minute], from: time)
        let minutes = (components.hour ?? 22) * 60 + (components.minute ?? 30)
        return ProactiveRuleDraft(
            personaId: personaId,
            kind: kind,
            timeOfDayMinutes: kind == .daily ? minutes : nil,
            intervalMinutes: kind == .interval ? intervalHours * 60 : nil,
            note: note,
            hint: hint
        )
    }
}
