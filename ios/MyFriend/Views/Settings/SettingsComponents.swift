import SwiftUI

// ---------------------------------------------------------------------------
// 设置 / 人格 / 记忆 / 备份四组页面共用的外观零件。
//
// 为什么不用 `Form`：这四页要跟首页保持同一种「纸面 + 卡片」的气质（暖白画布、16pt 圆角、
// 细描边），`Form` 的 grouped 列表会带一层系统灰底与自己的分隔线，压不住。这里用
// ScrollView + 卡片拼，控件仍全部是系统控件（开关、滑块、选择器），行为与可访问性不打折。
//
// 全部以 `MF` 开头：这四个目录和聊天页是同 target，避免与那边的组件重名。
// ---------------------------------------------------------------------------

enum MFUI {

    /// 卡片底色：浅色下是白纸，深色下比画布亮一档（0.07 → 0.045 叠加）。
    static func cardFill(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color.white.opacity(0.045) : Color.white
    }

    static func cardStroke(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color.white.opacity(0.07) : Color.black.opacity(0.045)
    }

    /// 主题色候选：与 Android 设置页的 `accentChoices` 同一组 ARGB。
    static let accentChoices: [Int64] = [
        0xFFC76F4E, 0xFFB6883E, 0xFF6F8B65, 0xFF4F7F92,
        0xFF6E79A8, 0xFF966A88, 0xFFB76076, 0xFF66645E
    ]

    /// 危险动作的红：比系统红稍暗一点，不像报警。
    static let danger = Color(red: 0.72, green: 0.26, blue: 0.24)
}

/// Provider 的中文名字：设置页与编辑页共用一份，免得同一档在两个地方叫法不同。
enum MFProviderLabel {
    static func text(_ provider: ProviderType) -> String {
        switch provider {
        case .openai: return "OpenAI 兼容"
        case .gemini: return "Gemini"
        case .claude: return "Claude"
        }
    }

    static func all() -> [(ProviderType, String)] {
        ProviderType.allCases.map { ($0, text($0)) }
    }
}

/// 分节标题（外观、API 配置、记忆……）。
struct MFSectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(.subheadline, design: .serif))
            .foregroundStyle(.secondary)
            .padding(.top, 2)
    }
}

/// 一张纸：内容 + 16pt 圆角 + 细描边。
struct MFCard<Content: View>: View {
    @Environment(\.colorScheme) private var scheme

    private let padding: CGFloat
    private let content: Content

    init(padding: CGFloat = 16, @ViewBuilder content: () -> Content) {
        self.padding = padding
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content
        }
        .padding(padding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
                .fill(MFUI.cardFill(scheme))
        )
        .overlay(
            RoundedRectangle(cornerRadius: MFTheme.cardRadius, style: .continuous)
                .strokeBorder(MFUI.cardStroke(scheme), lineWidth: 0.7)
        )
    }
}

/// 卡片行之间的细分隔线（缩进对齐图标之后）。
struct MFRowDivider: View {
    var leadingInset: CGFloat = 34

    var body: some View {
        Divider().padding(.leading, leadingInset)
    }
}

/// 只有一行字的一行：图标 + 标题 + 一句人话解释。
struct MFSettingRow: View {
    let icon: String
    let title: String
    var subtitle: String?
    var tint: Color = .secondary

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15))
                .foregroundStyle(tint)
                .frame(width: 22, height: 20)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(.body, design: .serif))
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 4)
        }
        .padding(.vertical, 8)
    }
}

/// 带一个开关的一行。解释放在标题下面，开关在右侧。
struct MFToggleRow: View {
    let icon: String
    let title: String
    var subtitle: String?
    @Binding var isOn: Bool
    var tint: Color = MFTheme.defaultAccent

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 15))
                    .foregroundStyle(.secondary)
                    .frame(width: 22, height: 20)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(.body, design: .serif))
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
        .tint(tint)
        .padding(.vertical, 7)
    }
}

/// 右侧带一个值的行（主题模式、人格数量之类）。
struct MFValueRow: View {
    let icon: String
    let title: String
    var subtitle: String?
    let value: String
    var accent: Color = .secondary

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15))
                .foregroundStyle(.secondary)
                .frame(width: 22, height: 20)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(.body, design: .serif))
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            Text(value)
                .font(.footnote)
                .foregroundStyle(accent)
                .multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 8)
    }
}

/// 卡片底部的一句小字说明（比正文低一档对比度）。
struct MFNote: View {
    let text: String
    var icon: String = "info.circle"

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .padding(.top, 1)
            Text(text)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 6)
    }
}

/// 一排可选文字（主题 / 气泡样式 / 记忆分组用）。
/// 自己画而不是用 `Picker(.segmented)`：字号跟随正文，深色下也不会变成一块灰胶囊。
struct MFChoiceBar<Value: Hashable>: View {
    struct Option {
        let value: Value
        let label: String
    }

    let options: [Option]
    @Binding var selection: Value
    var accent: Color = MFTheme.defaultAccent

    init(options: [(Value, String)], selection: Binding<Value>, accent: Color = MFTheme.defaultAccent) {
        self.options = options.map { Option(value: $0.0, label: $0.1) }
        self._selection = selection
        self.accent = accent
    }

    var body: some View {
        HStack(spacing: 6) {
            ForEach(options, id: \.value) { option in
                let selected = option.value == selection
                Button {
                    selection = option.value
                } label: {
                    Text(option.label)
                        .font(.footnote)
                        .foregroundStyle(selected ? Color.white : Color.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 7)
                        .background(
                            RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .fill(selected ? accent : Color.secondary.opacity(0.10))
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// 一条提醒（成功或失败都走它）。用 `.mfAlert($banner)` 展示。
struct MFAlert: Identifiable {
    let id = UUID()
    var title: String
    var message: String
}

extension View {
    /// 统一的「知道了」弹窗。标题写状态，正文写人话。
    func mfAlert(_ alert: Binding<MFAlert?>) -> some View {
        self.alert(
            alert.wrappedValue?.title ?? "",
            isPresented: Binding(
                get: { alert.wrappedValue != nil },
                set: { presented in if !presented { alert.wrappedValue = nil } }
            ),
            presenting: alert.wrappedValue
        ) { _ in
            Button("知道了", role: .cancel) {}
        } message: { item in
            Text(item.message)
        }
    }
}
