import CoreGraphics
import Foundation

// ---------------------------------------------------------------------------
// 表情包（`spec/stickers/README.md`，0.13 / schema v16）。
//
// 表情是**压缩过的 GIF**：单文件 ≤512 KB、长边 ≤320 px、帧数 ≤60、总时长 ≤6 s。
// 一张表情一条 `stickers` 记录，文件本身在 App 私有目录
//（`Application Support/stickers/<id>.gif`；服务端模式是 `/v1/media/<id>`）。
//
// 与 Android 的对齐：列名/顺序/可空性/索引名逐字一致（`spec/stickers/SCHEMA.md`），
// 所以两端导出的库可以直接 diff；`source` / `createdBy` 存枚举名（大写蛇形），
// 与 Kotlin 枚举的 `name` 相同。
//
// 为什么 `source` / `createdBy` 在实体里是 `String` 而不是 enum：
// 它们会从**别的端/旧版本**读回来（备份、服务端同步），遇到不认识的枚举值时
// 应该原样保留而不是把整行读失败。工厂方法只写已知值。
// ---------------------------------------------------------------------------

/// 这张表情是从哪来的（`stickers.source`）。
public enum StickerSource: String, CaseIterable, Sendable {
    /// 用户从相册/文件导入的。
    case imported = "IMPORTED"
    /// 模型用 `sticker.make` 做的。
    case modelMade = "MODEL_MADE"
    /// 从聊天消息里收藏的（用户发过来、或模型发过的）。
    case fromUserMessage = "FROM_USER_MESSAGE"
    /// 从网上下载的图片（`make` 的 url 路径）。
    case web = "WEB"
}

/// 谁做的这张表情（`stickers.createdBy`）。
public enum StickerCreator: String, CaseIterable, Sendable {
    case user = "USER"
    case model = "MODEL"
}

/// 表情的硬上限。集中在一处：压缩管线、工具参数、界面文案、测试都读这里。
public enum StickerLimits {
    /// 长边上限（像素）。
    public static let maxLongEdge = 320
    /// 帧率上限（帧/秒）。GIF 的延迟以 10 ms 为单位，20 fps = 50 ms。
    public static let maxFPS = 20
    /// 最短帧间隔（毫秒）= 1000 / maxFPS。
    public static let minFrameDelayMillis = 1000 / maxFPS
    /// 帧数上限。
    public static let maxFrames = 60
    /// 总时长上限（毫秒）。
    public static let maxDurationMillis = 6_000
    /// 文件体积上限（字节）。
    public static let maxBytes = 512 * 1024
    /// 调色板上限（GIF 8-bit；ImageIO 的 GIF 编码器每帧都会量化到 ≤256 色）。
    public static let colorLimit = 256
    /// 历史面板里「最近」那一档的条数。
    public static let recentLimit = 30
    /// 聊天气泡里表情的显示边长（pt）。
    public static let bubblePointSize: CGFloat = 240
    /// `make` 允许下载的最大字节数（8 MiB）。
    public static let maxDownloadBytes = 8 * 1024 * 1024
    /// 导入时允许的最大输入字节数（与聊天图同一个 20 MB 闸）。
    public static let maxImportBytes = 20 * 1024 * 1024
    /// 表情文案（叠字）最大字数：再长在 320 px 上就没法看了。
    public static let maxOverlayTextLength = 80
    /// `make` 下载的超时（秒）。
    public static let downloadTimeout: TimeInterval = 20

    /// 静态图也按单帧 GIF 存；`frames` 至少是 1。
    public static func clampFrames(_ value: Int) -> Int {
        min(max(value, 1), maxFrames)
    }

    public static func isGIFMime(_ mime: String) -> Bool {
        mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "image/gif"
    }
}

/// 一张表情（表 `stickers`）。
public struct StickerEntity: Equatable, Sendable {
    public var id: String
    /// 服务端模式下的用户 id；本地模式为 nil（单用户）。
    public var ownerUserId: String?
    /// 模型做的表情挂在某个人格名下；用户导入的为 nil。
    public var personaId: String?
    /// 本机文件路径（绝对路径）或服务端 `/v1/media/<id>`。
    ///
    /// **为什么不存 `file://` URL**：规格把这一列叫「本机文件路径」；界面要渲染时
    /// 再由 `StickerStorage.fileURL(for:)` 转成 URL（容器 UUID 变了也能按文件名找回来）。
    public var file: String
    /// `image/gif`（静态图也是单帧 GIF）。
    public var mime: String
    public var width: Int
    public var height: Int
    public var frames: Int
    public var bytes: Int
    /// `StickerSource` 的原始值。
    public var source: String
    /// `StickerCreator` 的原始值。
    public var createdBy: String
    public var createdAt: Int64

    public init(
        id: String,
        ownerUserId: String? = nil,
        personaId: String? = nil,
        file: String,
        mime: String = "image/gif",
        width: Int = 0,
        height: Int = 0,
        frames: Int = 1,
        bytes: Int = 0,
        source: String = StickerSource.imported.rawValue,
        createdBy: String = StickerCreator.user.rawValue,
        createdAt: Int64 = 0
    ) {
        self.id = id
        self.ownerUserId = ownerUserId
        self.personaId = personaId
        self.file = file
        self.mime = mime
        self.width = max(0, width)
        self.height = max(0, height)
        self.frames = StickerLimits.clampFrames(frames)
        self.bytes = max(0, bytes)
        self.source = source
        self.createdBy = createdBy
        self.createdAt = createdAt
    }

    /// 是不是服务端媒体（`/v1/media/<id>`）。
    public var isServerSticker: Bool {
        ServerMediaReference.mediaId(from: file) != nil
    }

    /// 是不是动图（多帧）。
    public var isAnimated: Bool { frames > 1 }

    /// 给界面/日志看的一行：「320×280 · 24 帧 · 118 KB」。
    public var displaySummary: String {
        let size = "\(width)×\(height)"
        let frameText = frames > 1 ? " · \(frames) 帧" : ""
        let kb = max(1, Int((Double(bytes) / 1024.0).rounded()))
        return size + frameText + " · \(kb) KB"
    }

    public var sourceKind: StickerSource? { StickerSource(rawValue: source) }
    public var creatorKind: StickerCreator? { StickerCreator(rawValue: createdBy) }
}

extension StickerEntity: SQLiteRecord {
    public static let tableName = "stickers"

    /// 顺序必须与 `Schema.stickersDDL` 的列顺序一致（`SQLiteStoreTests` 会逐表核对）。
    public static let columns = [
        "id", "ownerUserId", "personaId", "file", "mime", "width", "height",
        "frames", "bytes", "source", "createdBy", "createdAt"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id),
            .optionalText(ownerUserId),
            .optionalText(personaId),
            .text(file),
            .text(mime),
            .int(width),
            .int(height),
            .int(frames),
            .int(bytes),
            .text(source),
            .text(createdBy),
            .int(createdAt)
        ]
    }

    public init(row: SQLiteRow) throws {
        id = try row.text("id")
        ownerUserId = try row.optionalText("ownerUserId")
        personaId = try row.optionalText("personaId")
        file = try row.text("file")
        mime = try row.text("mime", "image/gif")
        width = try row.int("width")
        height = try row.int("height")
        frames = StickerLimits.clampFrames(try row.int("frames", 1))
        bytes = try row.int("bytes")
        source = try row.text("source", StickerSource.imported.rawValue)
        createdBy = try row.text("createdBy", StickerCreator.user.rawValue)
        createdAt = try row.int64("createdAt")
    }
}

/// `stickers` 的读写。
///
/// 查询只有三类，都围绕「我的表情」这一件事：最近 N 个（历史面板第一档）、全部（第二档）、
/// 按 (文件, owner) 找已有记录（收藏去重）。跨人格隔离不在这里做：表情是**用户自己的**
/// 收藏，人格只记录「谁做的」。
public final class StickerStore: SQLiteStore<StickerEntity> {

    /// 历史面板第一档：最近 30 个（按 `createdAt` 倒序）。
    public func recent(limit: Int = StickerLimits.recentLimit) throws -> [StickerEntity] {
        try fetch(where: nil, [], orderBy: "createdAt DESC, id DESC", limit: max(0, limit))
    }

    /// 历史面板第二档：全部（同样按时间倒序）。
    public func allStickers() throws -> [StickerEntity] {
        try fetch(where: nil, [], orderBy: "createdAt DESC, id DESC")
    }

    public func byPersona(_ personaId: String) throws -> [StickerEntity] {
        try fetch(where: "personaId = ?", [.text(personaId)], orderBy: "createdAt DESC")
    }

    /// 某个用户自己的表情（服务端模式的 owner 维度；`nil` 表示本地单用户）。
    public func byOwner(_ ownerUserId: String?) throws -> [StickerEntity] {
        if let ownerUserId {
            return try fetch(where: "ownerUserId = ?", [.text(ownerUserId)], orderBy: "createdAt DESC")
        }
        return try fetch(where: "ownerUserId IS NULL", [], orderBy: "createdAt DESC")
    }

    /// 收藏去重：同一个文件 + 同一个 owner 已经收藏过。
    ///
    /// 为什么按 `file` 而不是按内容哈希：表情已经是压缩产物（同名文件就是同一份字节），
    /// 文件路径就是它的身份；而收藏的要求是「同一文件、createdBy 不变，增加 owner 关联」。
    public func findByFile(_ file: String, ownerUserId: String?) throws -> StickerEntity? {
        let exactFile = file.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !exactFile.isEmpty else { return nil }
        if let ownerUserId {
            return try first(
                where: "file = ? AND ownerUserId = ?", [.text(exactFile), .text(ownerUserId)],
                orderBy: "createdAt DESC"
            )
        }
        return try first(
            where: "file = ? AND ownerUserId IS NULL", [.text(exactFile)],
            orderBy: "createdAt DESC"
        )
    }

    /// 按文件找任意一条（不区分 owner）：收藏一条模型做的表情时，用它继承 `createdBy`/`source`。
    public func firstByFile(_ file: String) throws -> StickerEntity? {
        let exactFile = file.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !exactFile.isEmpty else { return nil }
        return try first(where: "file = ?", [.text(exactFile)], orderBy: "createdAt ASC")
    }

    public func count(ownerUserId: String?) throws -> Int {
        if let ownerUserId {
            return try count(where: "ownerUserId = ?", [.text(ownerUserId)])
        }
        return try count(where: "ownerUserId IS NULL", [])
    }

    /// 删一行（不动文件：文件可能还被某条消息的附件引用着，由调用方决定要不要删文件）。
    @discardableResult
    public func remove(id: String) throws -> Bool {
        try database.withConnectionLock {
            try delete(where: "id = ?", [.text(id)])
            return database.lastChangeCount() > 0
        }
    }
}
