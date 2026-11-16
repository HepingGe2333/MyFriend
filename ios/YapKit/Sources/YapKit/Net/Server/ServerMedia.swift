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

    /// 下载一张图片的原始字节（渲染用；带 Authorization）。
    ///
    /// 304（ETag 命中）对客户端等价于「缓存还能用」：`ServerMediaCache` 自己按文件存在
    /// 做了同样的事，所以这里不做条件请求，直接读全文。
    func mediaData(mediaId: String) async throws -> Data {
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
        return data
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

extension ServerAPIClient: ServerMediaUploading {}
