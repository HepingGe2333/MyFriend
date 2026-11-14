import PhotosUI
import SwiftUI

/// 设置主页。
///
/// 1.9 的瘦身（§七）：只剩外观 / API 配置 / 联系人 / 用量 / 主动 / 备份 / 危险区。
/// 记忆开关、读消息的节奏、额度、每天主动次数**全部删除** —— 用户面对的是一个联系人，
/// 不是一个可以调参数的机器人。
///
/// 顺序按 iOS 用户真正会碰到的频率排：先外观，再「能不能用」的 API，然后是人与用量，
/// 最后才是会删东西的两颗按钮。
///
/// 所有改动都走 `env.updateSettings { }`，点一下立刻落盘、立刻生效；
/// 没有「保存」按钮，也没有「未保存修改」这种需要用户额外理解的中间态。
struct SettingsScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var personas: [PersonaEntity] = []
    @State private var configs: [ApiConfigEntity] = []
    @State private var banner: MFAlert?
    @State private var pendingAction: PendingAction?
    /// 设置「我的头像」用的相册选择项（读完就清空）。
    @State private var myAvatarPick: PhotosPickerItem?

    private var settings: AppSettings { env.currentSettings }

    /// 「我的头像」那一行的滚动锚点（Debug 截图用）。
    private static let myAvatarAnchor = "settings-my-avatar"
    /// 「主动唤醒」入口的滚动锚点（Debug 截图用）。
    private static let proactiveAnchor = "settings-proactive"
    private var accent: Color { Color(argb: env.currentSettings.accentColor) }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    appearanceSection
                    apiSection
                    personaSection
                    SettingsUsageSection()
                    proactiveSection
                    backupSection
                    dangerSection
                    footer
                }
                .padding(.horizontal, MFTheme.gutter)
                .padding(.bottom, 40)
            }
            .background(MFTheme.canvas(scheme).ignoresSafeArea())
            .onAppear {
                reload()
                // Debug：截图验收用（`MYFRIEND_DEBUG_OPEN=settings-avatar|settings-proactive`）。
                if DebugSeeding.openSettingsAvatar {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        withAnimation(.easeInOut(duration: 0.25)) {
                            proxy.scrollTo(Self.myAvatarAnchor, anchor: .center)
                        }
                    }
                } else if DebugSeeding.openSettingsProactive {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        withAnimation(.easeInOut(duration: 0.25)) {
                            proxy.scrollTo(Self.proactiveAnchor, anchor: .center)
                        }
                    }
                }
            }
        }
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .mfAlert($banner)
        .confirmationDialog(
            pendingAction?.title ?? "",
            isPresented: Binding(
                get: { pendingAction != nil },
                set: { presented in if !presented { pendingAction = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(pendingAction?.confirmTitle ?? "确定", role: .destructive) {
                guard let action = pendingAction else { return }
                pendingAction = nil
                perform(action)
            }
            Button("取消", role: .cancel) { pendingAction = nil }
        } message: {
            Text(pendingAction?.message ?? "")
        }
    }

    // MARK: - 头部

    private var header: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Text("MyFriend")
                    .font(.system(size: 30, weight: .bold, design: .serif))
                Spacer()
                Text("v\(AppEnvironment.appVersion)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text("留一处安静的地方，慢慢说话")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(summaryLine)
                .font(.caption)
                .foregroundStyle(accent)
                .padding(.top, 2)
        }
        .padding(.top, 8)
    }

    private var summaryLine: String {
        let memoryCount = (try? env.stores.memories.count()) ?? 0
        return "\(personas.count) 个人 · \(configs.count) 个 API 配置 · \(memoryCount) 条记忆"
    }

    // MARK: - 外观

    private var appearanceSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: "外观")

            MFCard {
                VStack(alignment: .leading, spacing: 8) {
                    MFRowTitle("主题", hint: "跟随系统最省心；浅色与深色只影响这套界面。")
                    MFChoiceBar(
                        options: [
                            (ThemeMode.system, "跟随系统"),
                            (ThemeMode.light, "浅色"),
                            (ThemeMode.dark, "深色")
                        ],
                        selection: binding(\.themeMode),
                        accent: accent
                    )
                }

                MFRowDivider(leadingInset: 0)

                VStack(alignment: .leading, spacing: 10) {
                    MFRowTitle("主色", hint: "用在发送气泡、按钮与高亮上。")
                    HStack(spacing: 10) {
                        ForEach(MFUI.accentChoices, id: \.self) { argb in
                            accentDot(argb)
                        }
                        Spacer(minLength: 0)
                        ColorPicker("自定义主色", selection: accentBinding, supportsOpacity: false)
                            .labelsHidden()
                    }
                }
                .padding(.vertical, 12)

                MFRowDivider(leadingInset: 0)

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("字号")
                            .font(.system(.body, design: .serif))
                        Spacer()
                        Text("\(Int((settings.fontScale * 100).rounded()))%")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    HStack(spacing: 10) {
                        Text("A").font(.system(size: 12)).foregroundStyle(.secondary)
                        Slider(value: fontScaleBinding, in: 0.85...1.3, step: 0.05)
                            .tint(accent)
                        Text("A").font(.system(size: 18)).foregroundStyle(.secondary)
                    }
                    Text("只改字的大小，不改布局。0.85 到 1.3 之间。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 12)

                MFRowDivider(leadingInset: 0)

                VStack(alignment: .leading, spacing: 8) {
                    MFRowTitle("气泡样式", hint: "圆角与留白密度。")
                    MFChoiceBar(
                        options: [
                            (BubbleStyle.soft, "柔和"),
                            (BubbleStyle.compact, "紧凑"),
                            (BubbleStyle.outlined, "描边")
                        ],
                        selection: binding(\.bubbleStyle),
                        accent: accent
                    )
                }
                .padding(.vertical, 12)

                MFRowDivider(leadingInset: 0)

                MFToggleRow(
                    icon: "sparkles",
                    title: "界面动画",
                    subtitle: "关掉后页面与气泡的过渡更克制。",
                    isOn: binding(\.animationsEnabled),
                    tint: accent
                )
                MFRowDivider()
                MFToggleRow(
                    icon: "hand.tap",
                    title: "触感反馈",
                    subtitle: "长按、切人格时的轻微振动。",
                    isOn: binding(\.hapticsEnabled),
                    tint: accent
                )
            }
        }
    }

    private func accentDot(_ argb: Int64) -> some View {
        let selected = settings.accentColor == argb
        return Button {
            env.updateSettings { $0.accentColor = argb }
        } label: {
            Circle()
                .fill(Color(argb: argb))
                .frame(width: 26, height: 26)
                .overlay(
                    Circle()
                        .strokeBorder(Color.primary.opacity(selected ? 0.55 : 0), lineWidth: 1.5)
                        .padding(-3)
                )
                .overlay(
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .opacity(selected ? 1 : 0)
                )
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("主色"))
    }

    // MARK: - API 配置

    private var apiSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: "API 配置")

            MFCard(padding: 6) {
                if configs.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("还没有 API 配置")
                            .font(.system(.body, design: .serif))
                        Text("没有它他就没法开口说话。填一个 OpenAI 兼容的服务就行：OpenAI、DeepSeek、OpenRouter 都可以。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(10)
                    MFRowDivider(leadingInset: 8)
                } else {
                    ForEach(configs, id: \.id) { config in
                        NavigationLink(destination: ApiConfigEditorScreen(configId: config.id)) {
                            apiConfigRow(config)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button {
                                pendingAction = .deleteConfig(config)
                            } label: {
                                Label("删除这个配置", systemImage: "trash")
                            }
                        }
                        MFRowDivider(leadingInset: 52)
                    }
                }

                NavigationLink(destination: ApiConfigEditorScreen(configId: nil)) {
                    HStack(spacing: 10) {
                        Image(systemName: "plus.circle")
                            .font(.system(size: 16))
                            .foregroundStyle(accent)
                        Text("添加 API 配置")
                            .font(.system(.body, design: .serif))
                            .foregroundStyle(accent)
                        Spacer()
                    }
                    .padding(.vertical, 12)
                    .padding(.horizontal, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            MFNote(text: "Key 存在这台手机的 Keychain 里：不进备份、不进数据库，也不会显示回来。")
        }
    }

    private func apiConfigRow(_ config: ApiConfigEntity) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "network")
                .font(.system(size: 14))
                .foregroundStyle(accent)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(config.name.isEmpty ? "未命名配置" : config.name)
                        .font(.system(.body, design: .serif))
                        .lineLimit(1)
                    if config.id == env.activeApiConfig?.id {
                        Text("默认")
                            .font(.caption2)
                            .foregroundStyle(accent)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(accent.opacity(0.14), in: Capsule())
                    }
                }
                Text("\(MFProviderLabel.text(config.provider)) · \(config.model.isEmpty ? "还没填模型" : config.model)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 6)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 8)
        .contentShape(Rectangle())
    }

    // MARK: - 联系人（人格）

    /// 1.9 把「人格管理」从首页挪到了设置里（首页现在就是联系人列表，§七）：
    /// 人格是「他是谁」这件事，属于设置，不属于每天要看的那个列表。
    private var personaSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: "联系人")

            MFCard(padding: 6) {
                NavigationLink(value: Route.personas) {
                    HStack(spacing: 12) {
                        Image(systemName: "person.2")
                            .font(.system(size: 15))
                            .foregroundStyle(accent)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("管理联系人（\(personas.count) 个）")
                                .font(.system(.body, design: .serif))
                            Text("改他的性格、记性、作息；也可以新建或删除一个人。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 6)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFRowDivider(leadingInset: 0)

                NavigationLink(value: Route.memories) {
                    HStack(spacing: 12) {
                        Image(systemName: "brain")
                            .font(.system(size: 15))
                            .foregroundStyle(accent)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("他记得的事")
                                .font(.system(.body, design: .serif))
                            Text("记忆一直在自己整理：留下重要的，把不重要的慢慢淡下去。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 6)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFRowDivider(leadingInset: 0)

                myAvatarRow
            }

            MFNote(text: "这一版不再有「记忆开关」和「回复节奏」：他记不记得、什么时候回，都由他自己决定。")
        }
    }

    // MARK: - 我的头像

    /// 「我的头像」：安静的一行。只在聊天里你自己那一侧显示，图片不跟着备份走。
    ///
    /// 为什么不单开一页：换头像就是一次选择，多一层页面只会让它显得像个功能。
    private var myAvatarRow: some View {
        HStack(spacing: 12) {
            MFAvatar(myAvatarUri: settings.myAvatarUri, size: 30)

            VStack(alignment: .leading, spacing: 2) {
                Text("我的头像")
                    .font(.system(.body, design: .serif))
                Text(settings.myAvatarUri == nil
                     ? "给自己选一张；聊天里你那一侧会显示。"
                     : "只在你自己那一侧显示。图片不跟着备份走。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 6)

            HStack(spacing: 12) {
                if settings.myAvatarUri != nil {
                    Button("移除") { removeMyAvatar() }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .buttonStyle(.plain)
                }
                PhotosPicker(selection: $myAvatarPick, matching: .images, photoLibrary: .shared()) {
                    Text(settings.myAvatarUri == nil ? "选择" : "换一张")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(accent)
                }
                .onChange(of: myAvatarPick) { item in
                    importMyAvatar(item)
                }
            }
        }
        .padding(10)
        .contentShape(Rectangle())
        .id(Self.myAvatarAnchor)
    }

    private func importMyAvatar(_ item: PhotosPickerItem?) {
        guard let item else { return }
        myAvatarPick = nil
        Task { @MainActor in
            let outcome = await ImageImport.importOne(item, kind: .avatar)
            switch outcome {
            case .success(let attachment):
                let previous = env.currentSettings.myAvatarUri
                env.updateSettings { $0.myAvatarUri = attachment.uri }
                // 换下来的旧图删掉；只删我们自己目录里的文件。
                MediaStorage.shared.deleteFileIfOwned(previous)
            case .failure(let message):
                banner = MFAlert(title: "这张图用不了", message: message)
            }
        }
    }

    private func removeMyAvatar() {
        let previous = env.currentSettings.myAvatarUri
        env.updateSettings { $0.myAvatarUri = nil }
        MediaStorage.shared.deleteFileIfOwned(previous)
    }

    // MARK: - 主动

    private var proactiveSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: "主动")

            MFCard {
                MFToggleRow(
                    icon: "bolt.heart",
                    title: "常驻思考",
                    subtitle: "开着的时候，他会在前台每 30 秒想一次事情，进后台后登记一次机会式刷新。",
                    isOn: binding(\.agentLoopEnabled),
                    tint: accent
                )

                NavigationLink(value: Route.proactive) {
                    HStack(spacing: 12) {
                        Image(systemName: "alarm")
                            .font(.system(size: 15))
                            .foregroundStyle(accent)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("主动唤醒")
                                .font(.system(.body, design: .serif))
                            Text("他会按自己的节奏来找你；时间不是准点，是一段窗口。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 6)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFNote(
                    text: "每天最多主动几次，现在由他自己决定（联系人 → 主动程度）。"
                        + "iOS 不保证后台准时：系统可能几十分钟到几小时才给一次机会，也可能一天都不给。"
                        + "所以他有时候像睡着了——不是不理你。"
                )
            }
        }
        .id(Self.proactiveAnchor)
    }

    // MARK: - 备份

    private var backupSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: "备份")

            MFCard(padding: 6) {
                NavigationLink(value: Route.backups) {
                    HStack(spacing: 12) {
                        Image(systemName: "shippingbox")
                            .font(.system(size: 15))
                            .foregroundStyle(accent)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("把他带走 / 接他回来")
                                .font(.system(.body, design: .serif))
                            Text("一份 JSON，装着他、你们的对话和他记得的事。密钥不在里面。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 6)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - 危险区

    private var dangerSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: "危险区")

            MFCard {
                MFNote(text: "清空之前，先把他带走。删掉的东西不会自己回来。", icon: "exclamationmark.triangle")

                Button {
                    pendingAction = .clearChats
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "trash")
                            .font(.system(size: 14))
                            .frame(width: 22)
                        Text("清空全部聊天与记忆")
                            .font(.system(.body, design: .serif))
                        Spacer()
                    }
                    .foregroundStyle(MFUI.danger)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFRowDivider(leadingInset: 0)

                Button {
                    pendingAction = .clearAll
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "delete.left")
                            .font(.system(size: 14))
                            .frame(width: 22)
                        Text("删除全部本地数据")
                            .font(.system(.body, design: .serif))
                        Spacer()
                    }
                    .foregroundStyle(MFUI.danger)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                MFNote(
                    text: "「清空」只清会话、消息与记忆，人格和 API 配置留着；「删除全部」会连人格、配置与设置一起删，App 回到刚装上的样子。"
                )
            }
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("关于 MyFriend")
                .font(.system(.footnote, design: .serif))
                .foregroundStyle(.secondary)
            Text("他在这台手机上的一切都只存在这台手机上：对话、记忆、用量都不上传。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
    }

    // MARK: - 绑定与动作

    private func binding<Value>(_ keyPath: WritableKeyPath<AppSettings, Value>) -> Binding<Value> {
        Binding(
            get: { env.currentSettings[keyPath: keyPath] },
            set: { newValue in
                env.updateSettings { $0[keyPath: keyPath] = newValue }
            }
        )
    }

    private var fontScaleBinding: Binding<Double> {
        Binding(
            get: { Double(env.currentSettings.fontScale) },
            set: { value in
                env.updateSettings { $0.fontScale = Float(value) }
            }
        )
    }

    private var accentBinding: Binding<Color> {
        Binding(
            get: { Color(argb: env.currentSettings.accentColor) },
            set: { color in
                env.updateSettings { $0.accentColor = Int64.argb(from: color) }
            }
        )
    }

    private func reload() {
        personas = (try? env.stores.personas.allOrderedByUpdatedAt()) ?? []
        configs = (try? env.stores.apiConfigs.allOrderedByUpdatedAt()) ?? []
    }

    private func perform(_ action: PendingAction) {
        switch action {
        case .deleteConfig(let config):
            deleteConfig(config)
        case .clearChats:
            clearChats()
        case .clearAll:
            clearAll()
        }
    }

    private func deleteConfig(_ config: ApiConfigEntity) {
        do {
            try env.stores.apiConfigs.delete(id: config.id)
            try? env.apiKeys.deleteKeys(for: config.id)
            reload()
            banner = MFAlert(
                title: "删掉了",
                message: "「\(config.name.isEmpty ? "这个配置" : config.name)」已经从这台手机上删掉。"
            )
        } catch {
            banner = MFAlert(title: "没能删掉", message: error.localizedDescription)
        }
    }

    private func clearChats() {
        do {
            // 消息要删了，它们带的图片文件也别留在沙盒里（人格头像不动：人还在）。
            let images = ((try? env.stores.messages.all()) ?? [])
                .flatMap { AttachmentsCodec.decode($0.attachmentsJson) }
            let summary = try env.dataReset.clearChats()
            MediaStorage.shared.delete(images)
            reload()
            banner = MFAlert(title: "清空了", message: summary.describeChats())
        } catch {
            banner = MFAlert(title: "没能清空", message: error.localizedDescription)
        }
    }

    private func clearAll() {
        let summary = env.dataReset.clearAll()
        // 全部数据都删：附件目录（消息图 + 头像）也一起清掉。
        MediaStorage.shared.deleteAll()
        // 清空会重置 SettingsStore；AppEnvironment 的内存快照要跟着换一份，
        // 否则界面上还挂着已经不存在的人格与主题。
        env.reloadSettings()
        reload()
        banner = MFAlert(title: "都删掉了", message: summary.describeAll())
    }

    /// 三颗会删东西的按钮共用同一个 `confirmationDialog`。
    ///
    /// SwiftUI 里同一层视图挂多个 `confirmationDialog` 时只有最后一个可靠地弹出来，
    /// 所以这里用一个动作枚举 + 一个弹窗，文案按枚举分支给。
    private enum PendingAction {
        case deleteConfig(ApiConfigEntity)
        case clearChats
        case clearAll

        var title: String {
            switch self {
            case .deleteConfig(let config):
                let name = config.name.isEmpty ? "这个配置" : config.name
                return "删除「\(name)」？"
            case .clearChats:
                return "清空全部聊天与记忆？"
            case .clearAll:
                return "删除全部本地数据？"
            }
        }

        var confirmTitle: String {
            switch self {
            case .deleteConfig: return "删除"
            case .clearChats: return "清空"
            case .clearAll: return "全部删除"
            }
        }

        var message: String {
            switch self {
            case .deleteConfig:
                return "这台手机上存的 Key 也会一起删掉。备份里本来就没有密钥，删了就只能重新填一次。"
            case .clearChats:
                return "所有会话、消息和长期记忆会被删除。人格与 API 配置会留着。这个动作没法撤销——想留个底的话，先把他带走。"
            case .clearAll:
                return "人格、聊天、记忆、预设、主动节奏、API 配置与设置都会删掉，App 回到刚装上的样子。想留个底的话，先用上面的「把他带走」。"
            }
        }
    }
}

/// 卡片里的小标题 + 一句解释（主题、主色、气泡样式这些「成组」的项用）。
struct MFRowTitle: View {
    let title: String
    let hint: String

    init(_ title: String, hint: String) {
        self.title = title
        self.hint = hint
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.system(.body, design: .serif))
            Text(hint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
