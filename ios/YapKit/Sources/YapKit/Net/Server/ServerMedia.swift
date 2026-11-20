import Foundation

// ---------------------------------------------------------------------------
// 服务端媒体（M5）：上传 / 下载图片。
//
// 契约由 `agentd` 的媒体路由钉死（`docs/design/server-authoritative.md` §3.6）：
//
//   POST /v1/media?kind=CHAT_IMAGE|PERSONA_AVATAR|USER_AVATAR&mime=image/jpeg&personaId=<id>
//        body = 原始字节，Content-Type 必填
//        → {"id","kind","mime","bytes","sha256","url"}（无 envelope；url 是 /v1/media/<id>）
//   GET  /v1/media/{id}  → 原始字节（需要 Authorization，和其它 /v1 接口一样）
//
// 为什么上传用原始字节而不是 base64：2 MiB 的图 base64 会多 1/3，而且服务端要再解一次；
// 原始 body + Content-Type 是 HTTP 本来的形状，也方便服务端直接用 sha256 去重。
//
// 客户端不保存任何「服务端文件路径」：`attachmentsJson` 里存的是 `/v1/media/<id>`，
// 渲染时再按 id 取（见 `ServerMediaCache`）。这样换服务端地址、换设备都不会变成坏引用。
// ---------------------------------------------------------------------------

/// 一张图片在服务端属于谁（与本地 `MediaKind` 一一对应）。
public enum ServerMediaKind: String, CaseIterable, Sendable {
    case chatImage = "CHAT_IMAGE"
    case personaAvatar = "PERSONA_AVATAR"
    case userAvatar = "USER_AVATAR"

    public init(mediaKind: MediaKind) {
        switch mediaKind {
        case .avatar: self = .personaAvatar
        case .chatImage: self = .chatImage
        }
    }
}

/// `POST /v1/media` 的响应（200 复用 / 201 新建，形状相同）。
public struct ServerMedia: Decodable, Equatable, Sendable {
    public var id: String
    public var kind: String
    public var mime: String
    public var bytes: Int
    /// 服务端算的 sha256（64 位小写 hex）；客户端不用它做校验，只用于日志/调试。
    public var sha256: String
    /// 相对路径 `/v1/media/<id>`（不是绝对 URL）。
    public var url: String

    public init(
        id: String,
        kind: String = "",
        mime: String = "",
        bytes: Int = 0,
        sha256: String = "",
        url: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.mime = mime
        self.bytes = bytes
        self.sha256 = sha256
        self.url = url?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            ? url!
            : ServerMediaReference.uri(id: id)
    }

    private enum CodingKeys: String, CodingKey { case id, kind, mime, bytes, sha256, url }

    /// 宽容解码：`id` 是唯一必需字段；其余缺省给安全默认值，`url` 缺省时按 id 拼。
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        kind = try container.decodeIfPresent(String.self, forKey: .kind) ?? ""
        mime = try container.decodeIfPresent(String.self, forKey: .mime) ?? ""
        bytes = try container.decodeIfPresent(Int.self, forKey: .bytes) ?? 0
        sha256 = try container.decodeIfPresent(String.self, forKey: .sha256) ?? ""
        let rawURL = try container.decodeIfPresent(String.self, forKey: .url)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        url = (rawURL?.isEmpty == false) ? rawURL! : ServerMediaReference.uri(id: id)
    }
}

/// 服务端媒体引用的两种写法：`/v1/media/<id>`（服务端下发）与 `server:<id>`（老缓存）。
public enum ServerMediaReference {

    /// 服务端相对路径的前缀（与 agentd 的 `MEDIA_PATH` 一致）。
    public static let pathPrefix = "/v1/media/"

    /// 本地 `attachmentsJson` 里存的标准写法。
    public static func uri(id: String) -> String {
        pathPrefix + id
    }

    /// 从任意写法里取出媒体 id；不是服务端媒体就返回 nil（本地 `file://` 不受影响）。
    ///
    /// 认得的写法：
    /// - `/v1/media/<id>`（服务端下发/我们写库的形状）；
    /// - `http(s)://host/v1/media/<id>`（调试时手填的绝对 URL）；
    /// - `server:<id>`（M4 的旧缓存形状，读到就顺手迁过来）。
    public static func mediaId(from uri: String?) -> String? {
        guard let raw = uri?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }
        if raw.lowercased().hasPrefix("server:") {
            let id = String(raw.dropFirst("server:".count))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return id.isEmpty ? nil : id
        }
        guard let range = raw.range(of: pathPrefix) else { return nil }
        var tail = String(raw[range.upperBound...])
        if let query = tail.firstIndex(where: { $0 == "?" || $0 == "#" }) {
            tail = String(tail[tail.startIndex..<query])
        }
        tail = tail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !tail.isEmpty, !tail.contains("/") else { return nil }
        return tail
    }

    public static func isServerMediaURI(_ uri: String?) -> Bool {
        mediaId(from: uri) != nil
    }
}

/// 能上传图片的东西（生产是 `ServerAPIClient`；测试注入假实现来断言「先压缩→再上传」的顺序）。
public protocol ServerMediaUploading: AnyObject {
    func uploadMedia(bytes: Data, mime: String, kind: String, personaId: String?) async throws -> ServerMedia
}

/// 能上传**任意媒体文件**（图片/表情/音频/视频）的东西。
///
/// 与 `ServerMediaUploading` 分开的理由：后者只服务「聊天图片 / 头像」两条既有链路
///（只收 JPEG/PNG/WebP、单文件 ≤2 MiB），行为被既有测试钉死了；历史媒体上传要按
/// `kind` 区分上限（图片 2 MiB、音频 8 MiB、视频 64 MiB，见 agentd `MediaRoutes`），
/// 还要接受 `image/gif` / `audio/*` / `video/*`。新增一条协议而不是改旧协议，
/// 老的调用点与测试注入的假实现都不受影响。
public protocol ServerMediaFileUploading: AnyObject {
    func uploadMediaFile(
        bytes: Data,
        mime: String,
        kind: String,
        personaId: String?
    ) async throws -> ServerMedia
}

/// 历史媒体上传会用到的服务端用途（与 agentd 的 `MediaKind` 逐字对应）。
public enum ServerMediaFileKind: String, CaseIterable, Sendable {
    case chatImage = "CHAT_IMAGE"
    case personaAvatar = "PERSONA_AVATAR"
    case userAvatar = "USER_AVATAR"
    case sticker = "STICKER"
    case audio = "AUDIO"
    case video = "VIDEO"

    /// 单文件上限（原始字节）：与 agentd `MediaRoutes.maxBytes(kind)` 一致。
    public var maxBytes: Int {
        switch self {
        case .audio: return 8 * 1024 * 1024
        case .video: return 64 * 1024 * 1024
        default: return 2 * 1024 * 1024
        }
    }

    /// 这个用途允许的 MIME（与 agentd `MediaMime.ALL` 的对应关系一致）。
    public var allowedMimes: Set<String> {
        switch self {
        case .chatImage, .personaAvatar, .userAvatar, .sticker:
            return ["image/jpeg", "image/png", "image/webp", "image/gif"]
        case .audio:
            return ["audio/m4a", "audio/mp4", "audio/mpeg"]
        case .video:
            return ["video/mp4", "video/quicktime"]
        }
    }
}

// MARK: - 服务端媒体的元信息（kind / mime / 时长）

/// 一次下载的结果：字节 + 服务端声明的哈希 / MIME（0.15）。
///
/// 为什么不让下载只返回 `Data`：`GET /v1/media/{id}` 的响应头里带着 `ETag`（服务端用
/// 内容 sha256 做 ETag），落地前必须拿它和字节比对 —— 否则「中间网络/代理改了几个字节」
/// 在本机看起来和正常下载一模一样，而备份/转发会把坏字节传给另一端。
public struct ServerMediaDownload: Equatable, Sendable {
    public var data: Data
    /// 服务端声明的 sha256（64 位小写 hex）；没有这条头时 nil。
    public var sha256: String?
    /// 响应 Content-Type（缺省 nil）。
    public var mime: String?

    public init(data: Data, sha256: String? = nil, mime: String? = nil) {
        self.data = data
        self.sha256 = ServerMediaHash.normalized(sha256)
        self.mime = mime
    }
}

/// sha256 归一化：去引号、去空白、小写；不是 64 位 hex 就当没有（绝不因一条坏头拒绝下载）。
public enum ServerMediaHash {

    public static func normalized(_ raw: String?) -> String? {
        var text = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if text.hasPrefix("W/") { text = String(text.dropFirst(2)) }
        if text.hasPrefix("\"") && text.hasSuffix("\"") && text.count >= 2 {
            text = String(text.dropFirst().dropLast())
        }
        text = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard text.count == 64,
              text.unicodeScalars.allSatisfy({ scalar in
                  (scalar.value >= 48 && scalar.value <= 57)
                      || (scalar.value >= 97 && scalar.value <= 102)
              }) else { return nil }
        return text
    }

    /// 字节的 sha256（64 位小写 hex）。
    public static func hex(_ data: Data) -> String {
        ImageContentHash.sha256Hex(data)
    }

    /// 两个哈希是否一致（都先归一化；任一侧缺失 → false，调用方自己决定是否跳过校验）。
    public static func matches(_ lhs: String?, _ rhs: String?) -> Bool {
        guard let left = normalized(lhs), let right = normalized(rhs) else { return false }
        return left == right
    }
}

/// 服务端媒体 id → 附件元信息。
///
/// 背景（0.13 音视频）：服务端消息只下发 `["/v1/media/<id>"]`，不带 kind/mime。
/// 图片靠 mime 默认值还能显示，但一段语音/视频回到客户端就变成了「坏图」——
/// 所以上传时把本机已知的元信息（kind、mime、时长、宽高）记一行，
/// 同步/缓存时按 mediaId 补齐。存在 `server_sync_state`，不新增表。
public final class ServerMediaMetaStore {

    /// 一个媒体 id 的元信息（JSON 落库）。
    public struct Meta: Codable, Equatable, Sendable {
        public var kind: String
        public var mime: String
        public var durationMs: Int?
        public var width: Int
        public var height: Int
        /// 0.15：上传时服务端算的 sha256（64 位小写 hex）。
        ///
        /// 下载落地时拿它和实际字节比对（服务端下载响应的 `ETag` 也是同一个值）。
        /// 老数据没有这一项 → nil，跳过校验（旧数据兼容）。
        public var sha256: String?

        public init(
            kind: String,
            mime: String,
            durationMs: Int? = nil,
            width: Int = 0,
            height: Int = 0,
            sha256: String? = nil
        ) {
            self.kind = kind
            self.mime = mime
            self.durationMs = durationMs
            self.width = width
            self.height = height
            self.sha256 = sha256
        }
    }

    private let state: ServerSyncStateStore
    private let now: () -> Int64

    public init(state: ServerSyncStateStore, now: @escaping () -> Int64 = { DomainClock.nowMillis }) {
        self.state = state
        self.now = now
    }

    public convenience init(
        database: SQLiteDatabase,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.init(state: ServerSyncStateStore(database: database), now: now)
    }

    /// 上传成功后记一行（失败只忽略：元信息丢了最多是气泡退化，不该让发送失败）。
    ///
    /// - Parameter sha256: 服务端返回的内容哈希（0.15）。下载落地时用它校验字节，
    ///   老调用点不传就是 nil（= 这条记录没有哈希，下载时跳过校验）。
    public func record(_ attachment: MessageAttachment, mediaId: String, sha256: String? = nil) {
        let id = mediaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        let kind = attachment.normalizedKind
            ?? AttachmentsCodec.kind(forMime: attachment.mime)
            ?? MessageAttachment.imageKind
        let meta = Meta(
            kind: kind,
            mime: attachment.mime,
            durationMs: attachment.durationMs,
            width: attachment.width,
            height: attachment.height,
            sha256: ServerMediaHash.normalized(sha256)
        )
        guard let data = try? JSONEncoder().encode(meta),
              let json = String(data: data, encoding: .utf8) else { return }
        try? state.set(json, for: ServerSyncStateStore.Key.mediaMeta(id), at: now())
    }

    public func meta(mediaId: String) -> Meta? {
        let id = mediaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty,
              let json = state.string(ServerSyncStateStore.Key.mediaMeta(id)),
              let data = json.data(using: .utf8),
              let meta = try? JSONDecoder().decode(Meta.self, from: data) else { return nil }
        return meta
    }

    /// 把一个「只有 id」的附件补全成带 kind/mime/时长的本地附件。
    public func attachment(mediaId: String) -> MessageAttachment? {
        guard let meta = meta(mediaId: mediaId) else { return nil }
        return MessageAttachment(
            uri: ServerMediaReference.uri(id: mediaId),
            mime: meta.mime.isEmpty ? MessageAttachment.defaultMime : meta.mime,
            width: meta.width,
            height: meta.height,
            kind: meta.kind.isEmpty ? nil : meta.kind,
            durationMs: meta.durationMs
        )
    }
}

// MARK: - 客户端方法

public extension ServerAPIClient {

    /// 单文件上限：与 agentd 的 2 MiB 硬上限一致（本地压缩后的聊天图 ≤800KB，够用）。
    static let maxMediaUploadBytes = 2 * 1024 * 1024

    /// 上传一张图片的原始字节。
    ///
    /// - Parameters:
    ///   - bytes: 压缩后的 JPEG/PNG/WebP 字节（**不是** base64）。
    ///   - mime: 必须与服务端白名单一致（image/jpeg、image/png、image/webp）。
    ///   - kind: `CHAT_IMAGE` / `PERSONA_AVATAR` / `USER_AVATAR`。
    ///   - personaId: 头像/聊天图属于哪个人格（可选；给了就必须存在）。
    func uploadMedia(
        bytes: Data,
        mime: String,
        kind: String,
        personaId: String? = nil
    ) async throws -> ServerMedia {
        guard !bytes.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "这张图片是空的")
        }
        guard bytes.count <= Self.maxMediaUploadBytes else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "图片超过 2 MB，请换一张小一点的"
            )
        }
        let trimmedMime = mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard Self.supportedMediaMimes.contains(trimmedMime) else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "服务端不收这种图片格式（只支持 JPEG / PNG / WebP）"
            )
        }
        let trimmedKind = kind.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKind.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "kind 不能为空")
        }
        var query = [
            URLQueryItem(name: "kind", value: trimmedKind),
            URLQueryItem(name: "mime", value: trimmedMime)
        ]
        if let personaId = personaId?.trimmingCharacters(in: .whitespacesAndNewlines),
           !personaId.isEmpty {
            query.append(URLQueryItem(name: "personaId", value: personaId))
        }
        var request = try makeRequest(
            path: "/media",
            query: query,
            method: "POST",
            accept: "application/json",
            // 上传是「大 body + 慢网络」：用资源超时（30s）而不是请求超时（10s）当上限。
            timeout: Self.resourceTimeout
        )
        request.setValue(trimmedMime, forHTTPHeaderField: "Content-Type")
        request.httpBody = bytes
        return try await send(request, as: ServerMedia.self)
    }

    /// 上传一张表情（`POST /v1/media?kind=STICKER&mime=image/gif`），返回 `/v1/media/<id>`。
    ///
    /// 为什么单独开一个方法而不是让调用方拼 kind：表情的 kind/mime 是**协议**的一部分
    ///（agentd 按 kind 判 2 MiB 上限、按 mime 落 `.gif` 扩展名），散在调用点迟早写错。
    /// 走 `uploadMediaFile`（0.13 的通用媒体上传）：它按 kind 校验 MIME 与上限，
    /// 表情与图片/音频/视频共用同一套服务端契约。
    func uploadSticker(bytes: Data, personaId: String? = nil) async throws -> ServerMedia {
        try await uploadMediaFile(
            bytes: bytes,
            mime: "image/gif",
            kind: ServerMediaFileKind.sticker.rawValue,
            personaId: personaId
        )
    }

    /// 下载一张图片的原始字节（渲染用；带 Authorization）。
    ///
    /// 304（ETag 命中）对客户端等价于「缓存还能用」：`ServerMediaCache` 自己按文件存在
    /// 做了同样的事，所以这里不做条件请求，直接读全文。
    func mediaData(mediaId: String) async throws -> Data {
        try await mediaDownload(mediaId: mediaId).data
    }

    /// 下载 + 服务端声明的哈希 / MIME（0.15）。
    ///
    /// 哈希来自响应头 `ETag`（agentd 用内容 sha256 做 ETag；`If-None-Match` 命中时是 304，
    /// 但这里永远发无条件请求，所以拿到的一定是完整字节）。头缺失/格式不对 → `sha256 = nil`，
    /// 调用方跳过校验（老服务端兼容）。
    func mediaDownload(mediaId: String) async throws -> ServerMediaDownload {
        let id = mediaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "媒体 id 不能为空")
        }
        let request = try makeRequest(
            path: "/media/\(Self.writePathSegment(id))",
            query: [],
            method: "GET",
            accept: "*/*",
            timeout: Self.resourceTimeout
        )
        let (data, response) = try await data(for: request)
        guard (200..<300).contains(response.statusCode) else {
            throw Self.error(from: data, status: response.statusCode)
        }
        let headers = response.allHeaderFields
        let etag = headers.first { key, _ in
            (key as? String)?.caseInsensitiveCompare("ETag") == .orderedSame
        }?.value as? String
        let contentType = headers.first { key, _ in
            (key as? String)?.caseInsensitiveCompare("Content-Type") == .orderedSame
        }?.value as? String
        return ServerMediaDownload(
            data: data,
            sha256: etag,
            mime: contentType?.split(separator: ";").first.map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines)
            }
        )
    }

    /// 按 `attachmentsJson` 里的写法（`/v1/media/<id>` 等）下载。
    func mediaData(uri: String) async throws -> Data {
        guard let id = ServerMediaReference.mediaId(from: uri) else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "这不是服务端媒体")
        }
        return try await mediaData(mediaId: id)
    }

    /// 服务端支持的图片 MIME（与 agentd 的 `MediaRoutes.SUPPORTED_MIMES` 一致）。
    static let supportedMediaMimes: Set<String> = ["image/jpeg", "image/png", "image/webp"]

    // MARK: 内部

    /// 发请求 + 解 JSON（媒体路由与普通 JSON 路由共用同一套错误映射）。
    private func send<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> T {
        let (data, response) = try await data(for: request)
        guard (200..<300).contains(response.statusCode) else {
            throw Self.error(from: data, status: response.statusCode)
        }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw ServerAPIError(
                code: ServerAPIError.Code.decodeFailed,
                status: response.statusCode,
                message: "服务端返回的数据格式不正确"
            )
        }
    }

    /// 统一的网络错误映射（与 `get`/`post` 同口径）。
    private func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw ServerAPIError(
                    code: ServerAPIError.Code.invalidResponse,
                    message: "服务端没有返回 HTTP 响应"
                )
            }
            return (data, http)
        } catch let error as ServerAPIError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError {
            throw Self.mapURLError(error)
        } catch {
            throw ServerAPIError(code: ServerAPIError.Code.networkError, message: "连接服务端失败")
        }
    }
}

// MARK: - 任意媒体文件上传（图片 / 表情 / 音频 / 视频）

public extension ServerAPIClient {

    /// 大文件上传的资源超时：64 MiB 视频在慢网络上 30 秒传不完。
    static let mediaFileUploadTimeout: TimeInterval = 300

    /// 上传一份**任意媒体**（历史媒体搬家用）。
    ///
    /// 与 `uploadMedia` 的区别只有「接受哪些 MIME / 上限多大」，请求形状完全一致
    /// （`POST /media?kind=&mime=` + 原始字节 + `Content-Type`）。校验全部在本地做：
    /// 一个注定 413/415 的请求不发出去（历史里有几千条消息时这很关键）。
    ///
    /// - Parameters:
    ///   - bytes: 原始字节（图片已在调用方压好，音频/视频不转码）。
    ///   - mime: 必须与 `kind.allowedMimes` 一致。
    ///   - kind: `ServerMediaFileKind.rawValue`。
    ///   - personaId: 可选，给了就必须是服务端认识的人格。
    func uploadMediaFile(
        bytes: Data,
        mime: String,
        kind: String,
        personaId: String? = nil
    ) async throws -> ServerMedia {
        guard !bytes.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "这个媒体文件是空的")
        }
        let trimmedKind = kind.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let fileKind = ServerMediaFileKind(rawValue: trimmedKind) else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "服务端不认识这种媒体用途（\(trimmedKind)）"
            )
        }
        let trimmedMime = mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard fileKind.allowedMimes.contains(trimmedMime) else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "服务端不收这种媒体格式（\(trimmedMime)）"
            )
        }
        guard bytes.count <= fileKind.maxBytes else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "这个媒体文件超过上限（\(fileKind.maxBytes / 1024) KB）"
            )
        }
        var query = [
            URLQueryItem(name: "kind", value: fileKind.rawValue),
            URLQueryItem(name: "mime", value: trimmedMime)
        ]
        if let personaId = personaId?.trimmingCharacters(in: .whitespacesAndNewlines),
           !personaId.isEmpty {
            query.append(URLQueryItem(name: "personaId", value: personaId))
        }
        var request = try makeRequest(
            path: "/media",
            query: query,
            method: "POST",
            accept: "application/json",
            timeout: Self.mediaFileUploadTimeout
        )
        request.setValue(trimmedMime, forHTTPHeaderField: "Content-Type")
        request.httpBody = bytes
        return try await send(request, as: ServerMedia.self)
    }
}

extension ServerAPIClient: ServerMediaUploading {}
extension ServerAPIClient: ServerMediaFileUploading {}
