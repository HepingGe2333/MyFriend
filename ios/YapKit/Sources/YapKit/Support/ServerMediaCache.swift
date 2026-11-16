import CoreGraphics
import Foundation

// ---------------------------------------------------------------------------
// 服务端媒体缓存（M5）：把 `/v1/media/<id>` 取回来、按文件缓存、按显示尺寸解码。
//
// 为什么不在渲染时直接 `AsyncImage(url:)`：
// - 服务端要求 `Authorization: Bearer <token>`，`AsyncImage` 不能带自定义头；
// - 聊天页一屏十几张图，每滚一次都重新下载是几百 KB 的浪费；
// - 解码仍要按**显示尺寸**做（与本地图同一条 `ImageThumbnailDecoder` 路径），
//   否则 4000px 的原图会直接把内存顶上去。
//
// 缓存分三层：
// 1. 磁盘：`Caches/server-media/<mediaId>.img`（Caches 目录本来就不进 iCloud 备份，
//    系统也可以在存储吃紧时清掉它 —— 清掉只是下次重新下载，不丢数据）；
// 2. 内存缩略图：`ImageThumbnailCache`（与本地缩略图共用同一个预算，键带 `server:` 前缀）；
// 3. 网络：`ServerAPIClient.mediaData(mediaId:)`（带 Authorization）。
//
// 进程级单例 + `configure(client:)`：界面层（`AttachmentThumbnail`）拿不到 AppEnvironment，
// 装配层在服务端配置变化时把客户端换掉；没配置时它只回答「不是服务端媒体」。
// ---------------------------------------------------------------------------

public final class ServerMediaCache {

    public static let shared = ServerMediaCache()

    /// 磁盘缓存目录。
    public let directory: URL

    private let fileManager: FileManager
    private let lock = NSLock()
    private var client: ServerAPIClient?
    /// 正在下载的 id → 那次任务（同一张图并发请求只下载一次）。
    private var inflight: [String: Task<Data, Error>] = [:]

    public init(
        directory: URL? = nil,
        fileManager: FileManager = .default
    ) {
        self.fileManager = fileManager
        if let directory {
            self.directory = directory
        } else {
            let caches = (try? fileManager.url(
                for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true
            )) ?? fileManager.temporaryDirectory
            self.directory = caches.appendingPathComponent("server-media", isDirectory: true)
        }
        try? fileManager.createDirectory(at: self.directory, withIntermediateDirectories: true)
    }

    /// 装配层在服务端配置变化时调用；传 nil 表示「服务端模式关掉了」。
    public func configure(client: ServerAPIClient?) {
        lock.lock()
        self.client = client
        lock.unlock()
    }

    /// 现在能不能取服务端媒体（服务端模式开着 + 配了地址）。
    public var isConfigured: Bool {
        lock.lock()
        defer { lock.unlock() }
        return client?.config.isUsable == true
    }

    // MARK: - 字节

    /// 取一张服务端图片的原始字节（先磁盘，后网络）。
    ///
    /// - Throws: `ServerAPIError`（未配置 / 网络 / 服务端 404）；调用方按「图片加载失败」展示。
    public func data(for uri: String) async throws -> Data {
        guard let mediaId = ServerMediaReference.mediaId(from: uri) else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "这不是服务端媒体"
            )
        }
        if let cached = cachedData(mediaId: mediaId) { return cached }
        if let task = existingTask(mediaId: mediaId) {
            return try await task.value
        }
        let task = Task<Data, Error> { [weak self] in
            guard let self else {
                throw ServerAPIError(code: ServerAPIError.Code.networkError, message: "媒体缓存已释放")
            }
            let current: ServerAPIClient?
            self.lock.lock()
            current = self.client
            self.lock.unlock()
            guard let current else {
                throw ServerAPIError(
                    code: ServerAPIError.Code.notConfigured,
                    message: "服务端模式没有开启，取不到这张图"
                )
            }
            let data = try await current.mediaData(mediaId: mediaId)
            self.store(data, mediaId: mediaId)
            return data
        }
        lock.lock()
        inflight[mediaId] = task
        lock.unlock()
        defer { removeTask(mediaId: mediaId) }
        return try await task.value
    }

    /// 按显示尺寸解码。
    ///
    /// 失败返回 nil（界面显示占位图），不抛：缩略图加载失败不该中断列表渲染。
    public func thumbnail(for uri: String, maxPixelSize: Int) async -> CGImage? {
        guard maxPixelSize > 0, let mediaId = ServerMediaReference.mediaId(from: uri) else {
            return nil
        }
        let key = "server:\(mediaId)#\(maxPixelSize)"
        if let cached = ImageThumbnailCache.shared.image(forKey: key) { return cached }
        guard let data = try? await data(for: uri), !data.isEmpty else { return nil }
        guard let image = ImageThumbnailDecoder.thumbnail(data: data, maxPixelSize: maxPixelSize) else {
            return nil
        }
        ImageThumbnailCache.shared.store(image, forKey: key)
        return image
    }

    /// 本地有没有这张图（不再请求网络；`AttachmentThumbnail` 的「已缓存」判断用）。
    public func hasCachedData(for uri: String) -> Bool {
        guard let mediaId = ServerMediaReference.mediaId(from: uri) else { return false }
        return cachedData(mediaId: mediaId) != nil
    }

    /// 清空磁盘与内存里的服务端图片（「删除全部本地数据」用；服务端那一份不动）。
    public func clear() {
        lock.lock()
        let tasks = inflight.values
        inflight.removeAll()
        lock.unlock()
        for task in tasks { task.cancel() }
        try? fileManager.removeItem(at: directory)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    // MARK: - 内部

    private func cachedData(mediaId: String) -> Data? {
        guard let url = fileURL(mediaId: mediaId) else { return nil }
        guard let data = try? Data(contentsOf: url), !data.isEmpty else { return nil }
        return data
    }

    private func store(_ data: Data, mediaId: String) {
        guard !data.isEmpty, let url = fileURL(mediaId: mediaId) else { return }
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }

    private func fileURL(mediaId: String) -> URL? {
        // mediaId 由服务端生成（UUID），仍然按不可信输入处理：不把 `/`、`..` 带进路径。
        let safe = mediaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !safe.isEmpty, !safe.contains("/"), !safe.contains(".."), !safe.contains("\\") else {
            return nil
        }
        return directory.appendingPathComponent(safe + ".img", isDirectory: false)
    }

    private func existingTask(mediaId: String) -> Task<Data, Error>? {
        lock.lock()
        defer { lock.unlock() }
        return inflight[mediaId]
    }

    private func removeTask(mediaId: String) {
        lock.lock()
        inflight[mediaId] = nil
        lock.unlock()
    }
}
