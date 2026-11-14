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

/// 一条消息里附带的一张图片。
public struct MessageAttachment: Equatable, Codable, Sendable {

    /// Android 的默认 MIME：压缩后我们统一写 JPEG。
    public static let defaultMime = "image/jpeg"

    /// `file://` 绝对路径，指向 App 私有目录里的副本。
    public var uri: String
    public var mime: String
    /// 图片解码后的像素宽（0 = 不知道）。Android 目前恒写 0，iOS 写真实值方便气泡排版；
    /// 两端解码器都把 0 当「不知道」，所以不影响互通。
    public var width: Int
    public var height: Int

    public init(
        uri: String,
        mime: String = MessageAttachment.defaultMime,
        width: Int = 0,
        height: Int = 0
    ) {
        self.uri = uri
        self.mime = mime
        self.width = width
        self.height = height
    }

    public var isImage: Bool { mime.hasPrefix("image/") }

    enum CodingKeys: String, CodingKey {
        case uri, mime, width, height
    }

    /// 手写解码（不用合成实现）：合成实现遇到缺字段会整条失败，
    /// 而 Android 的语义是「缺字段用默认值」。类型不对才让整段解码失败。
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        uri = try container.decode(String.self, forKey: .uri)
        mime = try container.decodeIfPresent(String.self, forKey: .mime) ?? Self.defaultMime
        width = try container.decodeIfPresent(Int.self, forKey: .width) ?? 0
        height = try container.decodeIfPresent(Int.self, forKey: .height) ?? 0
    }

    /// 手写编码：与 Android 的 `encodeDefaults = false` 对齐 —— 默认值不写进 JSON。
    /// 这样同一个附件在两端写出来的字符串形状一致（也就少一次「为什么备份不一样」的排查）。
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(uri, forKey: .uri)
        if mime != Self.defaultMime { try container.encode(mime, forKey: .mime) }
        if width != 0 { try container.encode(width, forKey: .width) }
        if height != 0 { try container.encode(height, forKey: .height) }
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

    /// 最近几条带图的消息会真的把图发给模型（Android `MAX_IMAGE_MESSAGES_IN_CONTEXT`）：
    /// base64 很占上下文，历史里的图不该全带上。
    public static let maxImageMessagesInContext = 2

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
        return MessageAttachment(uri: uri, mime: mime, width: width, height: height)
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
