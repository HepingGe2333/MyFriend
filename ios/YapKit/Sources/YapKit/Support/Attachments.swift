import Foundation

// ---------------------------------------------------------------------------
// 消息附件（图片）。
//
// 来源：Android `domain/Attachments.kt`。这里**逐字段照抄**，因为 `messages.attachmentsJson`
// 会跟着备份在两端之间原样搬运（图片二进制不搬，元数据搬）：
//
//   [{"uri":"file:///…/chat-xxxx.jpg","mime":"image/jpeg","width":1600,"height":1200}]
//
// 字段名 `uri` / `mime` / `width` / `height` 是两端唯一的契约，一个字母都不能改。
// Android 侧用 kotlinx.serialization（`ignoreUnknownKeys = true`、`encodeDefaults = false`、
// `explicitNulls = false`），所以 iOS 这边也要做到三件同样的事：
//
// 1. **默认值不写进 JSON**（`mime = image/jpeg`、宽高 = 0 时省略）；
// 2. **未知字段忽略**（新版本多写的字段，老版本读得进）；
// 3. **坏数据不崩**：字段缺失/类型不对就整段当没有（Android 的 `runCatching` 同样整段丢掉），
//    `uri` 为空的条目再过滤掉。
//
// 图片文件本身存在 App 沙盒（`Application Support/attachments/`），
// `uri` 是 `file://` 绝对路径 —— 换机/重装就失效，所以二进制**不进备份**。
// ---------------------------------------------------------------------------

/// 一条消息里附带的一张图片（0.13 起也用来带表情：`kind=STICKER` + `mime=image/gif`）。
public struct MessageAttachment: Equatable, Codable, Sendable {

    /// Android 的默认 MIME：压缩后我们统一写 JPEG。
    public static let defaultMime = "image/jpeg"

    /// 表情附件的 `kind` 值（与 agentd 的 `MediaKind.STICKER` 一致）。
    ///
    /// 为什么同时写 `kind` 和 mime：两端都是「看到 `image/gif` 就当表情」也能工作，
    /// 但服务端媒体/同步链路只带 mediaId 时会把 mime 补成默认的 `image/jpeg`；
    /// 多一个显式 `kind` 让「这条消息带的是表情」不再依赖 mime 猜。
    /// Android 的解码器 `ignoreUnknownKeys = true`，读到这个字段会忽略，不会炸。
    public static let stickerKind = "STICKER"

    /// 0.13 音视频模态（`spec/audio-video/README.md`）：`kind` 的三个取值。
    ///
    /// 服务端媒体（`/v1/media/<id>`）的同步链路只带 mediaId，mime 会被补成默认的
    /// `image/jpeg`；没有显式 `kind` 的话，语音/视频回到客户端就变成了「坏图」。
    /// 所以 `kind` 是**协议字段**，不是装饰：IMAGE / AUDIO / VIDEO 与服务端
    /// `MediaKind` 逐字对应。
    public static let imageKind = "IMAGE"
    public static let audioKind = "AUDIO"
    public static let videoKind = "VIDEO"

    /// `file://` 绝对路径，指向 App 私有目录里的副本（或 `/v1/media/<id>`）。
    public var uri: String
    public var mime: String
    /// 图片解码后的像素宽（0 = 不知道）。Android 目前恒写 0，iOS 写真实值方便气泡排版；
    /// 两端解码器都把 0 当「不知道」，所以不影响互通。
    public var width: Int
    public var height: Int
    /// 附件种类（`IMAGE` / `AUDIO` / `VIDEO` / `STICKER`）；nil = 按 mime 判断
    /// （老的图片附件没有这一列）。
    ///
    /// 只在新版本写、老版本忽略，所以「加了字段」不会破坏备份互通：
    /// 编码时 nil 不写进 JSON，已有图片的 `attachmentsJson` 一个字节都不变。
    public var kind: String?
    /// 音频/视频的时长（毫秒）；nil = 不知道（图片恒为 nil）。
    ///
    /// 与 `kind` 一样只在新版本写、老版本忽略：`durationMs` 为空时一个字节都不写。
    public var durationMs: Int?

    public init(
        uri: String,
        mime: String = MessageAttachment.defaultMime,
        width: Int = 0,
        height: Int = 0,
        kind: String? = nil,
        durationMs: Int? = nil
    ) {
        self.uri = uri
        self.mime = mime
        self.width = width
        self.height = height
        self.kind = kind
        self.durationMs = durationMs
    }

    /// 一段语音（AAC 单声道 16 kHz、`.m4a`）。
    public static func audio(
        uri: String,
        durationMs: Int,
        mime: String = AudioVideoLimits.audioMime
    ) -> MessageAttachment {
        MessageAttachment(uri: uri, mime: mime, width: 0, height: 0,
                          kind: audioKind, durationMs: max(0, durationMs))
    }

    /// 一段视频（不转码，`.mp4`/`.mov`）。
    public static func video(
        uri: String,
        durationMs: Int,
        width: Int = 0,
        height: Int = 0,
        mime: String = AudioVideoLimits.videoMime
    ) -> MessageAttachment {
        MessageAttachment(uri: uri, mime: mime, width: width, height: height,
                          kind: videoKind, durationMs: max(0, durationMs))
    }

    /// `kind` 归一化（大写、去空白）；nil = 没写。
    public var normalizedKind: String? {
        let trimmed = kind?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    public var isImage: Bool {
        if normalizedKind == Self.audioKind || normalizedKind == Self.videoKind { return false }
        return mime.hasPrefix("image/")
    }

    /// 一段语音：显式 `kind=AUDIO`，或者 mime 是 `audio/*`（老版本/外部数据只写 mime）。
    public var isAudio: Bool {
        if normalizedKind == Self.audioKind { return true }
        if normalizedKind == Self.stickerKind || normalizedKind == Self.imageKind
            || normalizedKind == Self.videoKind {
            return false
        }
        return mime.lowercased().hasPrefix("audio/")
    }

    /// 一段视频：显式 `kind=VIDEO`，或者 mime 是 `video/*`。
    public var isVideo: Bool {
        if normalizedKind == Self.videoKind { return true }
        if normalizedKind == Self.stickerKind || normalizedKind == Self.imageKind
            || normalizedKind == Self.audioKind {
            return false
        }
        return mime.lowercased().hasPrefix("video/")
    }

    /// 语音或视频（两者共用「一段媒体」的气泡与上传链路）。
    public var isPlayableMedia: Bool { isAudio || isVideo }

    /// 时长（秒）；不知道时 nil。
    public var durationSeconds: Double? {
        guard let durationMs, durationMs > 0 else { return nil }
        return Double(durationMs) / 1000
    }

    /// 是不是表情：显式 `kind=STICKER`，或者 mime 是 GIF（老版本/Android 只写 mime）。
    public var isSticker: Bool {
        if normalizedKind == Self.stickerKind { return true }
        guard normalizedKind == nil || normalizedKind == Self.imageKind else { return false }
        return StickerLimits.isGIFMime(mime)
    }

    enum CodingKeys: String, CodingKey {
        case uri, mime, width, height, kind, durationMs
    }

    /// 手写解码（不用合成实现）：合成实现遇到缺字段会整条失败，
    /// 而 Android 的语义是「缺字段用默认值」。类型不对才让整段解码失败。
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        uri = try container.decode(String.self, forKey: .uri)
        mime = try container.decodeIfPresent(String.self, forKey: .mime) ?? Self.defaultMime
        width = try container.decodeIfPresent(Int.self, forKey: .width) ?? 0
        height = try container.decodeIfPresent(Int.self, forKey: .height) ?? 0
        let rawKind = try container.decodeIfPresent(String.self, forKey: .kind)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        kind = (rawKind?.isEmpty == false) ? rawKind : nil
        let rawDuration = try container.decodeIfPresent(Int.self, forKey: .durationMs) ?? 0
        durationMs = rawDuration > 0 ? rawDuration : nil
    }

    /// 手写编码：与 Android 的 `encodeDefaults = false` 对齐 —— 默认值不写进 JSON。
    /// 这样同一个附件在两端写出来的字符串形状一致（也就少一次「为什么备份不一样」的排查）。
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(uri, forKey: .uri)
        if mime != Self.defaultMime { try container.encode(mime, forKey: .mime) }
        if width != 0 { try container.encode(width, forKey: .width) }
        if height != 0 { try container.encode(height, forKey: .height) }
        if let kind, !kind.isEmpty { try container.encode(kind, forKey: .kind) }
        if let durationMs, durationMs > 0 { try container.encode(durationMs, forKey: .durationMs) }
    }
}

/// `messages.attachmentsJson` 的编解码。
///
/// 为什么不直接用 `JSONDecoder` 解 `[MessageAttachment]`：
/// Android 的 `runCatching { json.decodeFromString<List<…>>(raw) }.getOrDefault(emptyList())`
/// 会把**整段**坏数据当空；而 `JSONDecoder` 的报错粒度是「一条」，容易变成
/// 「只丢一张图、剩下的照用」——那和 Android 就不是同一套语义了。这里用
/// `JSONSerialization` 自己走一遍，逐条与 Kotlin 的行为对齐。
public enum AttachmentsCodec {

    /// 一条消息最多带几张图（Android `ChatRepository.MAX_IMAGES_PER_MESSAGE`）。
    public static let maxImagesPerMessage = 4

    /// 一条消息最多带几段语音 / 几段视频（0.13）。
    ///
    /// 为什么是 1：一段 5 分钟的语音 base64 就有 ~10 MB，两段会把一次请求推到
    /// 上下文与 body 上限附近；视频同理（8 帧 × 1024px JPEG）。产品上「一句话 = 一段语音」
    /// 也更接近真实聊天。
    public static let maxAudioPerMessage = 1
    public static let maxVideoPerMessage = 1

    /// 最近几条带图的消息会真的把图发给模型（Android `MAX_IMAGE_MESSAGES_IN_CONTEXT`）：
    /// base64 很占上下文，历史里的图不该全带上。
    public static let maxImageMessagesInContext = 2

    /// 两端认的附件 mime（`spec/audio-video/README.md`：图片 4 种、音频 3 种、视频 2 种）。
    ///
    /// 服务端 `MediaMime.ALL` 是同一组值；不在这里的格式一律拒绝（不让一个注定
    /// 415/413 的请求发出去）。
    public static let allowedMimes: Set<String> = [
        "image/jpeg", "image/png", "image/webp", "image/gif",
        "audio/m4a", "audio/mp4", "audio/mpeg",
        "video/mp4", "video/quicktime"
    ]

    /// 这个 mime 是不是两端都认的附件格式。
    public static func isAllowedMime(_ mime: String) -> Bool {
        allowedMimes.contains(mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }

    /// 按 mime 推附件种类（解码老数据 / 外部数据时用）。
    public static func kind(forMime mime: String) -> String? {
        switch mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "audio/m4a", "audio/mp4", "audio/mpeg": return MessageAttachment.audioKind
        case "video/mp4", "video/quicktime": return MessageAttachment.videoKind
        case "image/jpeg", "image/png", "image/webp": return MessageAttachment.imageKind
        default: return nil
        }
    }

    /// 编码；空列表返回 nil（Android 也是如此：`attachmentsJson = null` 表示「没有附件」）。
    public static func encode(_ items: [MessageAttachment]) -> String? {
        guard !items.isEmpty else { return nil }
        let encoder = JSONEncoder()
        encoder.outputFormatting = []
        guard let data = try? encoder.encode(items) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// 解码；任何坏数据都返回空数组，**不抛异常、不崩**。
    public static func decode(_ raw: String?) -> [MessageAttachment] {
        let text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let data = text.data(using: .utf8) else { return [] }
        guard let root = try? JSONSerialization.jsonObject(with: data) else { return [] }
        guard let array = root as? [Any] else { return [] }

        var result: [MessageAttachment] = []
        for element in array {
            guard let object = element as? [String: Any] else { return [] }
            guard let attachment = decodeOne(object) else { return [] }
            result.append(attachment)
        }
        // `uri` 全是空白的条目没有意义（Android 同样过滤）。
        return result.filter { !$0.uri.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    /// 解一条；`nil` = 这条是坏数据，整段作废（与 Kotlin 的整段 `runCatching` 一致）。
    private static func decodeOne(_ object: [String: Any]) -> MessageAttachment? {
        // `uri` 在 Kotlin 里是必填的：缺了或类型不对，整段就是坏数据。
        guard let uri = object["uri"] as? String else { return nil }
        var mime = MessageAttachment.defaultMime
        if let rawMime = object["mime"], !(rawMime is NSNull) {
            guard let value = rawMime as? String else { return nil }
            mime = value
        }
        guard let width = try? optionalInt(object["width"]),
              let height = try? optionalInt(object["height"]) else { return nil }
        var kind: String?
        if let rawKind = object["kind"], !(rawKind is NSNull) {
            guard let value = rawKind as? String else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            kind = trimmed.isEmpty ? nil : trimmed
        }
        // `durationMs`：与宽度/高度同一套宽容语义（缺省/null = 不知道，类型不对 = 坏数据）。
        guard let rawDuration = try? optionalInt(object["durationMs"]) else { return nil }
        let durationMs = rawDuration > 0 ? rawDuration : nil
        return MessageAttachment(
            uri: uri, mime: mime, width: width, height: height, kind: kind, durationMs: durationMs
        )
    }

    /// 这条消息有没有图片。
    public static func hasImages(_ raw: String?) -> Bool {
        !decode(raw).isEmpty
    }

    // MARK: 底层

    /// 取一个可缺省的整数字段：没写 / 写了 null 都当默认值 0；
    /// 写了别的类型（字符串、小数以外的怪东西）算坏数据。
    private static func optionalInt(_ raw: Any?) throws -> Int {
        guard let raw, !(raw is NSNull) else { return 0 }
        // `JSONSerialization` 把 true/false 也给成 NSNumber，所以要单独把布尔挑出去。
        guard let number = raw as? NSNumber, !(raw is Bool) else {
            throw AttachmentDecodeError.wrongType
        }
        let double = number.doubleValue
        guard double.isFinite, double >= Double(Int.min), double <= Double(Int.max) else {
            throw AttachmentDecodeError.wrongType
        }
        return Int(double)
    }

    private enum AttachmentDecodeError: Error {
        case wrongType
    }
}
