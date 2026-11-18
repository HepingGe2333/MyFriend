import Foundation

// ---------------------------------------------------------------------------
// 服务端「他随身带的东西」：表情包 / 日记板（0.13）。
//
// 归属与人格/记忆一样：**服务端是权威**，本机数据库只是缓存。
// - 新增/删除走服务端 CRUD（失败要让用户知道）；
// - 拉取列表更新本机缓存（表情字节经 `/v1/media/<id>` 落盘，日记只同步行）；
// - 界面/发送仍读本机缓存（离线也有东西可看）。
//
// 字段与 `agentd` 的响应逐字对应（契约见仓库 `docs/design/server-authoritative.md` 与
// Android `ServerCatalog.kt`）：改字段必须两端一起改。
// ---------------------------------------------------------------------------

/// 服务端表情行（与备份 v6 的 `stickers[]` 同形；`file` 是裸文件名）。
public struct ServerStickerRow: Codable, Equatable, Sendable {
    public var id: String
    public var ownerUserId: String?
    public var personaId: String?
    /// 裸文件名（与备份 `fileName` 同义）。
    public var file: String
    public var fileName: String?
    /// 服务端媒体 id（契约：与 `id` 相同）。
    public var mediaId: String?
    /// 下载地址 `/v1/media/<id>`（缺省时按 `id` 拼）。
    public var url: String?
    public var mime: String
    public var width: Int
    public var height: Int
    public var frames: Int
    public var bytes: Int
    public var source: String
    public var createdBy: String
    public var createdAt: Int64

    public init(
        id: String,
        ownerUserId: String? = nil,
        personaId: String? = nil,
        file: String = "",
        fileName: String? = nil,
        mediaId: String? = nil,
        url: String? = nil,
        mime: String = "image/gif",
        width: Int = 0,
        height: Int = 0,
        frames: Int = 1,
        bytes: Int = 0,
        source: String = "IMPORTED",
        createdBy: String = "USER",
        createdAt: Int64 = 0
    ) {
        self.id = id
        self.ownerUserId = ownerUserId
        self.personaId = personaId
        self.file = file
        self.fileName = fileName
        self.mediaId = mediaId
        self.url = url
        self.mime = mime
        self.width = width
        self.height = height
        self.frames = frames
        self.bytes = bytes
        self.source = source
        self.createdBy = createdBy
        self.createdAt = createdAt
    }

    /// 规范化媒体引用：`/v1/media/<id>`；没有可用 id 时 nil。
    ///
    /// 服务端可能给三种写法（契约：`url` = `/v1/media/<id>`、`mediaId` = 裸 id、
    /// 有些版本只回 `file`），这里统一成客户端库里的标准形态。
    public var mediaRef: String? {
        if let mediaId = Self.bareId(mediaId) { return ServerMediaReference.uri(id: mediaId) }
        if let url, let id = ServerMediaReference.mediaId(from: url) { return ServerMediaReference.uri(id: id) }
        if let id = Self.bareId(id) { return ServerMediaReference.uri(id: id) }
        return nil
    }

    /// 本机缓存行的稳定 id：服务端 id 是唯一真源。
    public var localId: String {
        if let id = Self.bareId(id) { return id }
        guard let ref = mediaRef, let id = ServerMediaReference.mediaId(from: ref) else { return "" }
        return id
    }

    /// 裸 id 清洗：只接受 `[A-Za-z0-9_-]{1,64}`（与 `ServerMediaReference` 的 id 口径一致）。
    static func bareId(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        guard trimmed.count <= 64,
              trimmed.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" }) else {
            return nil
        }
        return trimmed
    }

    private enum CodingKeys: String, CodingKey {
        case id, ownerUserId, personaId, file, fileName, mediaId, url, mime,
             width, height, frames, bytes, source, createdBy, createdAt
    }

    /// 宽松解码：缺字段用默认值，坏行跳过（服务端加字段/老服务端缺字段都不该让整次同步失败）。
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? "" ?? ""
        ownerUserId = (try? c.decodeIfPresent(String.self, forKey: .ownerUserId)) ?? nil
        personaId = (try? c.decodeIfPresent(String.self, forKey: .personaId)) ?? nil
        file = (try? c.decodeIfPresent(String.self, forKey: .file)) ?? "" ?? ""
        fileName = (try? c.decodeIfPresent(String.self, forKey: .fileName)) ?? nil
        mediaId = (try? c.decodeIfPresent(String.self, forKey: .mediaId)) ?? nil
        url = (try? c.decodeIfPresent(String.self, forKey: .url)) ?? nil
        mime = (try? c.decodeIfPresent(String.self, forKey: .mime)) ?? "image/gif" ?? "image/gif"
        width = (try? c.decodeIfPresent(Int.self, forKey: .width)) ?? 0 ?? 0
        height = (try? c.decodeIfPresent(Int.self, forKey: .height)) ?? 0 ?? 0
        frames = (try? c.decodeIfPresent(Int.self, forKey: .frames)) ?? 1 ?? 1
        bytes = (try? c.decodeIfPresent(Int.self, forKey: .bytes)) ?? 0 ?? 0
        source = (try? c.decodeIfPresent(String.self, forKey: .source)) ?? "IMPORTED" ?? "IMPORTED"
        createdBy = (try? c.decodeIfPresent(String.self, forKey: .createdBy)) ?? "USER" ?? "USER"
        createdAt = (try? c.decodeIfPresent(Int64.self, forKey: .createdAt)) ?? 0 ?? 0
    }
}

/// `GET /v1/stickers` 的根对象。
public struct ServerStickerListResponse: Decodable, Equatable, Sendable {
    public var serverTime: Int64
    public var stickers: [ServerStickerRow]

    private enum CodingKeys: String, CodingKey { case serverTime, stickers }

    public init(serverTime: Int64 = 0, stickers: [ServerStickerRow] = []) {
        self.serverTime = serverTime
        self.stickers = stickers
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        serverTime = (try? c.decodeIfPresent(Int64.self, forKey: .serverTime)) ?? 0 ?? 0
        stickers = (try? c.decodeIfPresent([ServerStickerRow].self, forKey: .stickers)) ?? [] ?? []
    }
}

/// `POST /v1/stickers` 的响应：`{"created":true,"sticker":{…}}`。
public struct ServerStickerCreatedResponse: Decodable, Equatable, Sendable {
    public var created: Bool
    public var sticker: ServerStickerRow?

    private enum CodingKeys: String, CodingKey { case created, sticker }

    public init(created: Bool = false, sticker: ServerStickerRow? = nil) {
        self.created = created
        self.sticker = sticker
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        created = (try? c.decodeIfPresent(Bool.self, forKey: .created)) ?? false ?? false
        sticker = (try? c.decodeIfPresent(ServerStickerRow.self, forKey: .sticker)) ?? nil
    }
}

/// 服务端日记一条（与备份 v6 的 `diaryNotes[]` 同形）。
public struct ServerDiaryRow: Codable, Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var content: String
    public var createdAt: Int64
    public var remindAt: Int64?
    public var remindedAt: Int64?
    public var updatedAt: Int64

    public init(
        id: String = "",
        personaId: String = "",
        content: String = "",
        createdAt: Int64 = 0,
        remindAt: Int64? = nil,
        remindedAt: Int64? = nil,
        updatedAt: Int64 = 0
    ) {
        self.id = id
        self.personaId = personaId
        self.content = content
        self.createdAt = createdAt
        self.remindAt = remindAt
        self.remindedAt = remindedAt
        self.updatedAt = updatedAt
    }

    private enum CodingKeys: String, CodingKey {
        case id, personaId, content, createdAt, remindAt, remindedAt, updatedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? "" ?? ""
        personaId = (try? c.decodeIfPresent(String.self, forKey: .personaId)) ?? "" ?? ""
        content = (try? c.decodeIfPresent(String.self, forKey: .content)) ?? "" ?? ""
        createdAt = (try? c.decodeIfPresent(Int64.self, forKey: .createdAt)) ?? 0 ?? 0
        remindAt = (try? c.decodeIfPresent(Int64.self, forKey: .remindAt)) ?? nil
        remindedAt = (try? c.decodeIfPresent(Int64.self, forKey: .remindedAt)) ?? nil
        updatedAt = (try? c.decodeIfPresent(Int64.self, forKey: .updatedAt)) ?? 0 ?? 0
    }
}

/// `GET /v1/personas/{id}/diary` 的根对象。
public struct ServerDiaryListResponse: Decodable, Equatable, Sendable {
    public var personaId: String
    public var notes: [ServerDiaryRow]

    private enum CodingKeys: String, CodingKey { case personaId, notes }

    public init(personaId: String = "", notes: [ServerDiaryRow] = []) {
        self.personaId = personaId
        self.notes = notes
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        personaId = (try? c.decodeIfPresent(String.self, forKey: .personaId)) ?? "" ?? ""
        notes = (try? c.decodeIfPresent([ServerDiaryRow].self, forKey: .notes)) ?? [] ?? []
    }
}

/// `POST /v1/personas/{id}/diary` 的响应：`{"note":{…}}`。
public struct ServerDiarySavedResponse: Decodable, Equatable, Sendable {
    public var note: ServerDiaryRow?

    private enum CodingKeys: String, CodingKey { case note }

    public init(note: ServerDiaryRow? = nil) {
        self.note = note
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        note = (try? c.decodeIfPresent(ServerDiaryRow.self, forKey: .note)) ?? nil
    }
}

/// 表情/日记的传输口（生产实现是 `ServerAPIClient`；单测注入假实现，不必起真连接）。
public protocol ServerCatalogAPI: AnyObject {
    func stickerRows(personaId: String?, limit: Int) async throws -> [ServerStickerRow]
    func createSticker(_ body: [String: JSONValue]) async throws -> ServerStickerRow?
    /// 大表情的字节上传（`POST /v1/media?kind=STICKER`），返回媒体 id。
    func uploadStickerMedia(data: Data, personaId: String?) async throws -> String
    func deleteSticker(id: String) async throws
    func diaryRows(personaId: String, limit: Int, since: Int64) async throws -> [ServerDiaryRow]
    func saveDiary(personaId: String, body: [String: JSONValue]) async throws -> ServerDiaryRow?
    func deleteDiary(personaId: String, noteId: String) async throws
    func mediaData(mediaId: String) async throws -> Data
}

extension ServerAPIClient: ServerCatalogAPI {}
