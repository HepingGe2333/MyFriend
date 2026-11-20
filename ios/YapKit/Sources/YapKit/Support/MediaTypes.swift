import Foundation

// ---------------------------------------------------------------------------
// 媒体类型（0.15）：**一份类型清单，三处共用**。
//
// 在这之前「这是什么媒体」散在三个地方各猜一次：
// - `messages.attachmentsJson[].kind`：`IMAGE` / `STICKER` / `AUDIO` / `VIDEO`；
// - `backup.media[].kind`：`PERSONA_AVATAR` / `USER_AVATAR` / `CHAT_IMAGE` / `STICKER` /
//   `AUDIO` / `VIDEO`；
// - 服务端 `/v1/media` 的 `kind` 查询参数：又是另一套名字。
//
// 三套名字本身是对的（备份要区分「头像属于谁」，附件不需要），但**校验**必须只有一套：
// 0.15 的要求是「附件 DTO 明确 kind 与 mime，未知类型拒绝」。于是这里给出：
//
// - `MediaType`：五类规范类型 `IMAGE` / `STICKER` / `AUDIO` / `VIDEO` / `AVATAR`；
// - `MediaType.allowedMimes`：每一类认哪些 mime（与 agentd `MediaMime` 同一组值）；
// - `AttachmentKindCheck`：一条附件的「kind + mime」到底合不合法。
//
// 为什么 `AVATAR` 不在**附件**里出现：附件是「一条消息带了什么」，头像不是消息附件，
// 它是 `personas.avatarUri` + `media[].kind=PERSONA_AVATAR/USER_AVATAR`。把 AVATAR 塞进
// attachmentsJson 只会让渲染层多一种永远不该走到的分支，所以这里显式拒绝。
// ---------------------------------------------------------------------------

/// 跨端媒体类型（附件 DTO 与备份 `media[]` 共用的规范名）。
public enum MediaType: String, CaseIterable, Sendable {
    case image = "IMAGE"
    case sticker = "STICKER"
    case audio = "AUDIO"
    case video = "VIDEO"
    /// 只出现在 `media[]`（`PERSONA_AVATAR` / `USER_AVATAR`），**不是**消息附件类型。
    case avatar = "AVATAR"

    /// 一条消息的 `attachmentsJson[]` 允许的 kind（`AVATAR` 不在其中）。
    public static let attachmentTypes: [MediaType] = [.image, .sticker, .audio, .video]

    /// 解析一个显式写入的 kind：大小写不敏感、去空白；空值或未知值返回 nil。
    ///
    /// 返回 nil 的两种情况要分开处理，所以调用方还要看原始字符串是不是空的：
    /// - 没写（老数据）：按 mime 推断，属于兼容；
    /// - 写了但不认识（新版本/坏数据）：**拒绝**，绝不猜成图片。
    public static func parse(_ raw: String?) -> MediaType? {
        let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        guard !trimmed.isEmpty else { return nil }
        return MediaType(rawValue: trimmed)
    }

    /// 备份 `media[].kind`（六个历史取值）→ 规范类型。
    public static func fromBackupKind(_ raw: String?) -> MediaType? {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? "" {
        case "PERSONA_AVATAR", "USER_AVATAR": return .avatar
        case "CHAT_IMAGE": return .image
        case "STICKER": return .sticker
        case "AUDIO": return .audio
        case "VIDEO": return .video
        default: return nil
        }
    }

    /// 一条附件能不能是这一类（头像不行）。
    public var isAttachmentType: Bool {
        Self.attachmentTypes.contains(self)
    }

    /// 这一类认的 mime（与 `AttachmentsCodec.allowedMimes` 同一组值，按类拆开）。
    public var allowedMimes: Set<String> {
        switch self {
        case .image, .avatar: return ["image/jpeg", "image/png", "image/webp"]
        case .sticker: return ["image/gif"]
        case .audio: return ["audio/m4a", "audio/mp4", "audio/mpeg"]
        case .video: return ["video/mp4", "video/quicktime"]
        }
    }

    /// `mime` 是不是这一类接受的格式。
    public func accepts(mime raw: String) -> Bool {
        let mime = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !mime.isEmpty else { return false }
        return allowedMimes.contains(mime)
    }

    /// 从 mime 推类型（**只给老数据兜底**：新写入一律显式写 kind）。
    ///
    /// `image/gif` → STICKER：GIF 在这个产品里只有表情会用到（聊天图统一压成 JPEG）。
    /// 认不出来返回 nil（调用方应当拒绝这条数据，而不是当成图片）。
    public static func inferred(fromMime raw: String) -> MediaType? {
        let mime = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !mime.isEmpty else { return nil }
        if mime == "image/gif" { return .sticker }
        if mime.hasPrefix("image/") {
            return MediaType.image.allowedMimes.contains(mime) ? .image : nil
        }
        if MediaType.audio.allowedMimes.contains(mime) { return .audio }
        if MediaType.video.allowedMimes.contains(mime) { return .video }
        return nil
    }
}

/// 一条附件的 `kind` + `mime` 校验结果。
///
/// 三态而不是 Bool：`legacy` 是「老数据没写 kind，但 mime 认得出」——它要能继续读，
/// 却也不该被当成「新写入的合法附件」（导出时会补上显式 kind）。
public enum AttachmentKindCheck: Equatable, Sendable {
    /// 显式写了 kind，且 mime 与它匹配。
    case explicit(MediaType)
    /// 没写 kind（0.13 及更早的图片附件）：按 mime 推断出类型，属于兼容读取。
    case legacy(MediaType)
    /// 不接受：未知 kind / mime 为空 / mime 与 kind 不匹配 / AVATAR 混进附件。
    case invalid(reason: String)

    /// 校验通过时对应的规范类型。
    public var mediaType: MediaType? {
        switch self {
        case .explicit(let type), .legacy(let type): return type
        case .invalid: return nil
        }
    }

    public var isValid: Bool { mediaType != nil }
}

extension MessageAttachment {

    /// 显式写下的 kind（规范化后）；没写返回 nil。
    public var mediaType: MediaType? {
        MediaType.parse(kind)
    }

    /// 这条附件的 kind + mime 校验。
    ///
    /// 规则（与 Android `0.15` 的附件契约一致）：
    /// 1. 显式 kind 必须是 `IMAGE` / `STICKER` / `AUDIO` / `VIDEO` 之一（`AVATAR` 拒绝）；
    /// 2. mime 必须非空，且属于该 kind 的白名单；
    /// 3. 没写 kind 时按 mime 推断（`image/gif` → STICKER），推断不出来就拒绝 ——
    ///    绝不「猜成图片」。
    public var kindCheck: AttachmentKindCheck {
        let trimmedMime = mime.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedMime.isEmpty else {
            return .invalid(reason: "mime 是空的")
        }
        let rawKind = kind?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if rawKind.isEmpty {
            guard let inferred = MediaType.inferred(fromMime: trimmedMime) else {
                return .invalid(reason: "认不出这种 mime：\(trimmedMime)")
            }
            guard inferred.isAttachmentType else {
                return .invalid(reason: "这里不该出现 \(inferred.rawValue)")
            }
            return .legacy(inferred)
        }
        guard let declared = MediaType.parse(rawKind) else {
            return .invalid(reason: "未知的 kind：\(rawKind)")
        }
        guard declared.isAttachmentType else {
            return .invalid(reason: "附件不能用 \(declared.rawValue)")
        }
        guard declared.accepts(mime: trimmedMime) else {
            return .invalid(reason: "\(declared.rawValue) 不接受 \(trimmedMime)")
        }
        return .explicit(declared)
    }

    /// 这条附件是否合法（新写入与读入都该先过这一关）。
    public var isKindValid: Bool { kindCheck.isValid }

    /// 校验通过的规范类型；老数据（没写 kind）也会给出推断值。
    public var resolvedMediaType: MediaType? { kindCheck.mediaType }
}
