import SwiftUI

/// 统一头像组件：人格头像与「我的头像」都从这里出。
///
/// 为什么只有一个组件（而不是每个页面自己画一个圈）：
/// - **内存**：所有地方都按显示尺寸解码（`AttachmentThumbnail` → `MediaStorage.thumbnail`），
///   头像图不会以整图形式常驻；
/// - **一致**：有图用图、没图用名字首字 + 由 `avatarSeed` 决定的稳定底色 ——
///   同一个人格在任何页面、任何设备上都长一个样；
/// - **好换**：相册换头像之后所有出现的地方一起变，不需要一处处改。
///
/// 两种角色：
/// - `.persona`：他（首页联系人、聊天顶栏、会话资料、人格列表/编辑器、设置、记忆页）；
/// - `.me`：你自己（聊天页自己那一侧的气泡旁、设置里的「我的头像」）。
struct MFAvatar: View {

    enum Style {
        case persona
        case me
    }

    let name: String
    let avatarUri: String?
    /// 底色种子：只对 `.persona` 有意义，与名字绑定（换设备也一致）。
    let avatarSeed: Int
    let mood: String?
    let moodIntensity: Int?
    let size: CGFloat
    let style: Style

    @Environment(\.colorScheme) private var scheme

    /// 首字底色：六支低饱和色，六个一组轮着用（与 Android 的 `PersonaAvatar` 同一套观感）。
    private static let palette: [Color] = [
        Color(hex: 0x7C, 0x93, 0xB8), Color(hex: 0x8F, 0xB0, 0x9A), Color(hex: 0xB8, 0x94, 0x7C),
        Color(hex: 0xA0, 0x8C, 0xB8), Color(hex: 0xB8, 0x8C, 0x9A), Color(hex: 0x8C, 0xA8, 0xB8)
    ]

    /// 人格头像（他）。
    init(persona: PersonaEntity, size: CGFloat = 44) {
        self.name = persona.name
        self.avatarUri = persona.avatarUri
        self.avatarSeed = persona.avatarSeed
        self.mood = persona.mood
        self.moodIntensity = persona.moodIntensity
        self.size = size
        self.style = .persona
    }

    /// 用「名字 + 图」直接构造：人格编辑器里还没落库的预览也用它。
    init(personaName: String, avatarUri: String?, avatarSeed: Int = 0, size: CGFloat = 44) {
        self.name = personaName
        self.avatarUri = avatarUri
        self.avatarSeed = avatarSeed
        self.mood = nil
        self.moodIntensity = nil
        self.size = size
        self.style = .persona
    }

    /// 我的头像（你）。
    init(myAvatarUri: String?, size: CGFloat = 44) {
        self.name = L("我")
        self.avatarUri = myAvatarUri
        self.avatarSeed = 0
        self.mood = nil
        self.moodIntensity = nil
        self.size = size
        self.style = .me
    }

    var body: some View {
        ZStack {
            if let avatarUri, !avatarUri.isEmpty {
                AttachmentThumbnail(uri: avatarUri, pointSize: size, contentMode: .fill) { _ in
                    // 文件不在了就退回首字，而不是留一个破图框。
                    fallback
                }
                .frame(width: size, height: size)
                .clipShape(Circle())
            } else {
                fallback
            }
        }
        .frame(width: size, height: size)
        .overlay {
            Circle().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.7)
        }
        .overlay(alignment: .bottomTrailing) { moodDot }
        .accessibilityLabel(Text(accessibilityText))
    }

    // MARK: - 兜底：首字

    private var fallback: some View {
        ZStack {
            Circle()
                .fill(fallbackFill)
            if style == .me {
                Image(systemName: "person.fill")
                    .font(.system(size: size * 0.4, weight: .medium))
                    .foregroundStyle(MFTheme.defaultAccent.opacity(0.85))
            } else {
                Text(initial)
                    .font(.system(size: size * 0.38, weight: .medium, design: .serif))
                    .foregroundStyle(.white)
            }
        }
    }

    private var fallbackFill: Color {
        if style == .me {
            return scheme == .dark
                ? Color.white.opacity(0.10)
                : MFTheme.defaultAccent.opacity(0.12)
        }
        let index = abs(avatarSeed) % Self.palette.count
        return Self.palette[index]
    }

    private var initial: String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "?" : String(trimmed.prefix(1))
    }

    // MARK: - 心情小点

    @ViewBuilder
    private var moodDot: some View {
        if style == .persona, let mood, let intensity = moodIntensity, intensity >= 3, !mood.isEmpty {
            Circle()
                .fill(MFTheme.defaultAccent)
                .frame(width: size * 0.22, height: size * 0.22)
                .overlay(
                    Circle().strokeBorder(MFTheme.canvas(scheme), lineWidth: 1.5)
                )
                .accessibilityLabel(Text(mood))
        }
    }

    private var accessibilityText: String {
        switch style {
        case .persona: return name.isEmpty ? L("他的头像") : Lf("%@的头像", name)
        case .me: return L("我的头像")
        }
    }
}

/// 上一版里这个名字在 `HomeView.swift` 中（一个只在首页用的圆圈）。
/// 2.0 起统一成 `MFAvatar`，保留这个名字只是为了让老代码继续编译。
@available(*, deprecated, renamed: "MFAvatar")
typealias AvatarCircle = MFAvatar
