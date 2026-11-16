import Foundation

// ---------------------------------------------------------------------------
// 服务端设置 ⇄ 本机设置（0.12.1：用户头像也走这一层）。
//
// 为什么单独一个文件：`ServerWriteService.settingsBody` 只负责「推」，而 bootstrap 的
// **全量拉取**还要「按服务端回来的值改本机设置」。两件事放一起，键名就不会出现
// 「推的时候叫 A、拉的时候叫 B」这种只有真机才发现的错。
//
// 边界（与 0.9 起的既有取舍一致）：
// - 只映射**可移植**的键：主题、字号、气泡、动画、触感、常驻开关、自动翻译、聊天背景色、
//   当前人格、用户头像。设备路径（`chatBackgroundImageUri`）与内部状态（心跳进度、
//   引导卡片是否关过）永远留在本机。
// - 用户头像键名 `myAvatarUri`，值是服务端相对路径 `/v1/media/<id>`（0.12.1 服务端白名单）。
//   本地模式存的是 `file://` 沙盒路径 —— 两种形状不会互相污染：推的时候只推服务端形状，
//   拉的时候只认服务端形状，本地模式一个字节都不改。
// ---------------------------------------------------------------------------

public enum ServerSettingsMapping {

    /// 用户头像在服务端设置白名单里的键名（0.12.1 的正式键）。
    public static let avatarKey = "userAvatarUri"
    /// 设计稿/早期对齐时用过的键名。只用于**兼容读**与**双写**。
    public static let legacyAvatarKey = "myAvatarUri"
    /// 读的时候按顺序取第一个有效的键。
    public static let avatarKeys = [avatarKey, legacyAvatarKey]

    /// 服务端设置对象 → 本机设置（只覆盖服务端权威的那几个键，没下发的保持原样）。
    ///
    /// 容错原则：类型不对**忽略这个键**，而不是让整次同步失败 —— 一个坏字段不该
    /// 把用户其它设置卡住。
    public static func applying(
        _ object: [String: JSONValue],
        to current: AppSettings
    ) -> AppSettings {
        var settings = current
        if let raw = object["themeMode"]?.stringValue, !raw.isEmpty {
            settings.themeMode = ThemeMode(stored: raw)
        }
        if let raw = object["accentColor"]?.intValue {
            settings.accentColor = raw
        }
        if let raw = object["fontScale"]?.doubleValue, raw.isFinite, raw > 0 {
            settings.fontScale = min(max(Float(raw), 0.85), 1.3)
        }
        if let raw = object["bubbleStyle"]?.stringValue, !raw.isEmpty {
            settings.bubbleStyle = BubbleStyle(stored: raw)
        }
        if let raw = object["animationsEnabled"]?.boolValue {
            settings.animationsEnabled = raw
        }
        if let raw = object["hapticsEnabled"]?.boolValue {
            settings.hapticsEnabled = raw
        }
        if let raw = object["agentLoopEnabled"]?.boolValue {
            settings.agentLoopEnabled = raw
        }
        if let raw = object["autoTranslatePersona"]?.boolValue {
            settings.autoTranslatePersona = raw
        }
        if let value = object["chatBackgroundColor"] {
            // null = 服务端明确说「没有背景色」；缺键 = 没下发（保持本机）。
            switch value {
            case .null: settings.chatBackgroundColor = nil
            case .int(let color): settings.chatBackgroundColor = color
            default: break
            }
        }
        if let value = object["activePersonaId"] {
            switch value {
            case .null: settings.activePersonaId = nil
            case .string(let id):
                let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
                settings.activePersonaId = trimmed.isEmpty ? nil : trimmed
            default: break
            }
        }
        // 用户头像：只认服务端媒体路径。`null` = 服务端明确说没有头像。
        // 两个键都读：有一个给了有效路径就用它；两个都是 null 才清掉本机头像。
        var sawAvatarKey = false
        var resolvedAvatar: String?
        var sawNull = false
        for key in avatarKeys {
            guard let value = object[key] else { continue }
            sawAvatarKey = true
            switch value {
            case .null:
                sawNull = true
            case .string(let uri):
                let trimmed = uri.trimmingCharacters(in: .whitespacesAndNewlines)
                if ServerMediaReference.isServerMediaURI(trimmed) {
                    resolvedAvatar = trimmed
                } else if trimmed.isEmpty {
                    sawNull = true
                }
                // 其它形状（例如服务端误存了本机路径）：忽略，别把界面搞成坏引用。
            default:
                break
            }
        }
        if let resolvedAvatar {
            settings.myAvatarUri = resolvedAvatar
        } else if sawAvatarKey && sawNull {
            settings.myAvatarUri = nil
        }
        return settings
    }

    /// 服务端设置里有没有一个可用的用户头像（`/v1/media/<id>`）。
    ///
    /// 「绑定头像时不要覆盖服务端已有的」这条判断在 bootstrap 里出现两次（推设置、合并时
    /// 补传本机头像），所以放在这里，别各写一份。
    public static func hasServerAvatar(_ object: [String: JSONValue]) -> Bool {
        avatarKeys.contains { key in
            ServerMediaReference.isServerMediaURI(object[key]?.stringValue)
        }
    }

    /// 本机设置 → 服务端设置白名单。
    ///
    /// 用户头像只有**已经是服务端媒体**时才写：本地模式下的 `file://` 路径推到服务端
    /// 毫无意义（另一台设备打不开），而且会覆盖服务端上已有的头像。
    ///
    /// - Parameter clearingAvatar: 用户在本机删掉了头像。此时必须把白名单里的头像键
    ///   **显式写成 null**：缺键的服务端语义是「这个键我没下发 / 别动它」，不写键就删不掉
    ///   服务端那一份，下次拉设置它又回来了。
    public static func body(
        _ settings: AppSettings,
        clearingAvatar: Bool = false
    ) -> [String: JSONValue] {
        var body: [String: JSONValue] = [
            "themeMode": .string(settings.themeMode.stored),
            "accentColor": .int(settings.accentColor),
            "fontScale": .double(Double(settings.fontScale)),
            "bubbleStyle": .string(settings.bubbleStyle.stored),
            "animationsEnabled": .bool(settings.animationsEnabled),
            "hapticsEnabled": .bool(settings.hapticsEnabled),
            "agentLoopEnabled": .bool(settings.agentLoopEnabled),
            "autoTranslatePersona": .bool(settings.autoTranslatePersona)
        ]
        body["chatBackgroundColor"] = settings.chatBackgroundColor.map { .int($0) } ?? .null
        body["activePersonaId"] = settings.activePersonaId.map(JSONValue.string) ?? .null
        if clearingAvatar {
            for key in avatarKeys { body[key] = .null }
        } else if let avatar = settings.myAvatarUri,
                  ServerMediaReference.isServerMediaURI(avatar) {
            // 双写：白名单只认其中一个键的服务端版本都能保存下来。
            body[avatarKey] = .string(avatar)
            body[legacyAvatarKey] = .string(avatar)
        }
        return body
    }
}
