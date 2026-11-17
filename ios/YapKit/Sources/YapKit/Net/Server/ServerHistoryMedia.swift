import Foundation

// ---------------------------------------------------------------------------
// 历史媒体的**逐项上传**（0.13「同意并同步」的核心）。
//
// 背景：bootstrap 以前只搬文字，本地 `file://` 图片在服务端不存在，服务端会整批
// `unknown_media` 拒掉（`ServerBootstrapService` 只好把附件引用去掉）。结果就是
// 「聊天搬过去了，图全没了」。
//
// 这一层的职责（顺序不能反）：
//
//   枚举本地历史消息里的附件
//     → 按类型准备字节（图片/表情压到 ≤800KB，音频 ≤8MB，视频 ≤64MB）
//     → POST /v1/media?kind=…（原始字节）
//     → 把这条消息的 `attachmentsJson` 改写成 `/v1/media/<id>`
//     → 交给 bootstrap 的 import（它只认服务端媒体引用）
//
// 四条硬约束：
// 1. **逐项**：一项一个请求，进度按项回调；某一项失败只跳过这一项，不整批放弃；
// 2. **可取消**：每个 await 前后都检查 `Task.isCancelled`；取消时把本次已经改写的
//    本地附件引用**还原**（否则用户取消后继续用本地模式，气泡里的图全变成坏引用）；
//    已经上传到服务端的媒体保留（规范：「取消后已上传的内容保留」）；
// 3. **重试不重复上传**：压缩后的字节算 sha256，`server.media.sha256.<sha>` 记住
//    `sha → /v1/media/<id>`；重试/断点续跑命中就直接复用（服务端自己也按 sha 去重）；
// 4. **失败计数**：报告里给出成功/复用/失败数与失败明细，bootstrap 把它带进同步报告，
//    设置页最终告诉用户「几张图没搬上去」。
//
// 为什么按 mime 判类型而不是新增字段：`attachmentsJson` 的契约（`uri/mime/width/height`）
// 与 Android 共享，不能为了这次改动动它；音频/视频在本机是「本地文件 + mime」，
// 图片是 JPEG/PNG/WebP/GIF，按 mime 分类已经够用（扩展名只在 mime 是默认值时兜底）。
// ---------------------------------------------------------------------------

/// 历史附件在本机属于哪一类（决定压缩策略与服务端 `kind`）。
public enum ServerHistoryMediaKind: Equatable, Sendable {
    /// 普通聊天图片：统一压成 JPEG（≤800KB），服务端 `CHAT_IMAGE`。
    case image
    /// 表情包：本身就是 GIF 且没超限时原样保留（动画不丢），否则重压成 JPEG。
    case sticker
    /// 语音：不转码，只做大小校验（≤8MB），服务端 `AUDIO`。
    case audio
    /// 视频：不转码，只做大小校验（≤64MB），服务端 `VIDEO`。
    case video

    public var serverKind: ServerMediaFileKind {
        switch self {
        case .image: return .chatImage
        case .sticker: return .sticker
        case .audio: return .audio
        case .video: return .video
        }
    }

    /// 这一类的体积上限（压缩/不压缩都要落在这个数以内）。
    public var byteLimit: Int {
        switch self {
        case .image, .sticker: return ServerHistoryMediaPolicy.maxImageBytes
        case .audio: return ServerHistoryMediaPolicy.maxAudioBytes
        case .video: return ServerHistoryMediaPolicy.maxVideoBytes
        }
    }
}

/// 类型判定与上限（纯函数，单测直接喂 mime/字节数）。
public enum ServerHistoryMediaPolicy {

    /// 图片/表情压缩后的上限（规范：图片 ≤800KB）。
    public static let maxImageBytes = 800 * 1024
    /// 音频上限（规范：≤8MB）。
    public static let maxAudioBytes = 8 * 1024 * 1024
    /// 视频上限（规范：≤64MB）。
    public static let maxVideoBytes = 64 * 1024 * 1024

    /// 按 mime 判类型；不认识的返回 nil（调用方把它算作失败项，保留本地引用）。
    public static func kind(forMime mime: String) -> ServerHistoryMediaKind? {
        let normalized = mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if normalized == "image/gif" { return .sticker }
        if normalized.hasPrefix("image/") { return .image }
        if normalized.hasPrefix("audio/") { return .audio }
        if normalized.hasPrefix("video/") { return .video }
        return nil
    }

    /// 这个字节数能不能按这一类上传（超限的项不传，算失败并保留本地）。
    public static func fits(byteCount: Int, kind: ServerHistoryMediaKind) -> Bool {
        byteCount > 0 && byteCount <= kind.byteLimit
    }

    /// 扩展名兜底（`mime` 是默认的 `image/jpeg` 时用；老数据没有写对 mime）。
    public static func mime(forFileExtension raw: String) -> String? {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "webp": return "image/webp"
        case "gif": return "image/gif"
        case "m4a": return "audio/m4a"
        case "mp4": return "video/mp4"
        case "mov": return "video/quicktime"
        case "mp3": return "audio/mpeg"
        default: return nil
        }
    }
}

/// 一项附件准备之后要上传的字节。
public struct ServerHistoryMediaPreparation: Equatable, Sendable {
    /// 原始字节（已压缩；音频/视频不转码）。
    public var bytes: Data
    /// 上传时的 `Content-Type` / `mime` 查询参数。
    public var mime: String
    /// 服务端用途（`ServerMediaFileKind.rawValue`）。
    public var kind: String

    public init(bytes: Data, mime: String, kind: String) {
        self.bytes = bytes
        self.mime = mime
        self.kind = kind
    }
}

/// 「把本地附件变成可上传字节」的抽象（测试注入假实现，避免真的去解码图片）。
public protocol ServerHistoryMediaPreparing {
    /// 返回 nil = 这一项准备不了（文件不在 / 不是支持的格式 / 超上限）。
    func prepare(attachment: MessageAttachment) -> ServerHistoryMediaPreparation?
}

/// 生产实现：读本机附件文件，图片走 `ImageCompressor`，音频/视频只做上限校验。
public struct LocalServerHistoryMediaPreparer: ServerHistoryMediaPreparing {

    private let storage: MediaStorage

    public init(storage: MediaStorage = .shared) {
        self.storage = storage
    }

    public func prepare(attachment: MessageAttachment) -> ServerHistoryMediaPreparation? {
        // 已经是服务端媒体：上传器在调用准备器之前就会跳过（这里返回 nil 也无害）。
        guard ServerMediaReference.mediaId(from: attachment.uri) == nil else { return nil }
        guard let url = storage.fileURL(for: attachment.uri),
              let raw = try? Data(contentsOf: url), !raw.isEmpty else {
            return nil
        }
        let mime = Self.effectiveMime(declared: attachment.mime, pathExtension: url.pathExtension)
        // 显式 `kind=STICKER` 优先（0.13 表情附件会带这个字段；只能是图片）。
        let explicitKind: ServerHistoryMediaKind? = attachment.kind?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased() == MessageAttachment.stickerKind ? .sticker : nil
        guard let kind = explicitKind ?? ServerHistoryMediaPolicy.kind(forMime: mime) else {
            return nil
        }

        switch kind {
        case .image, .sticker:
            // GIF 表情：本来就是表情包且没超限 → 原样保留（重压会把动画变成一张静图）。
            if kind == .sticker, mime == "image/gif",
               ServerHistoryMediaPolicy.fits(byteCount: raw.count, kind: kind) {
                return ServerHistoryMediaPreparation(
                    bytes: raw, mime: mime, kind: ServerMediaFileKind.sticker.rawValue
                )
            }
            // 其余（PNG/WebP/超限 GIF/普通图）统一压成 JPEG：透明垫白底、长边 ≤1600、≤800KB。
            guard let compressed = ImageCompressor.compressedJPEG(data: raw, kind: .chatImage),
                  ServerHistoryMediaPolicy.fits(byteCount: compressed.count, kind: kind) else {
                return nil
            }
            let serverKind: ServerMediaFileKind = kind == .sticker ? .sticker : .chatImage
            return ServerHistoryMediaPreparation(
                bytes: compressed, mime: "image/jpeg", kind: serverKind.rawValue
            )
        case .audio, .video:
            // 音频/视频不转码（iOS 端本轮没有可用的重编码链路）：超过上限的项算失败。
            guard ServerHistoryMediaPolicy.fits(byteCount: raw.count, kind: kind) else { return nil }
            return ServerHistoryMediaPreparation(
                bytes: raw, mime: mime, kind: kind.serverKind.rawValue
            )
        }
    }

    /// 附件声明的 mime 可信就用它；写着默认 `image/jpeg` 但扩展名是音频/视频时按扩展名纠正。
    static func effectiveMime(declared: String, pathExtension: String) -> String {
        let normalized = declared.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let inferred = ServerHistoryMediaPolicy.mime(forFileExtension: pathExtension)
        guard let inferred, inferred != "image/jpeg" else { return normalized }
        if normalized == MessageAttachment.defaultMime { return inferred }
        return normalized
    }
}

// MARK: - 报告与进度

/// 一项上传失败（保留本地引用；bootstrap 的 import 会把这几个附件算进「丢弃」）。
public struct ServerHistoryMediaFailure: Equatable, Sendable {
    public var messageId: String
    public var uri: String
    public var reason: String

    public init(messageId: String, uri: String, reason: String) {
        self.messageId = messageId
        self.uri = uri
        self.reason = reason
    }
}

/// 一次历史媒体上传的结果。
public struct ServerHistoryMediaReport: Equatable, Sendable {
    /// 枚举到的附件项数（含已经是服务端引用的）。
    public var total: Int
    /// 真正传上去的项数。
    public var uploaded: Int
    /// 复用（已经是 `/v1/media/<id>` 或 sha256 命中）的项数。
    public var reused: Int
    /// 失败项数（文件不在 / 格式不支持 / 超上限 / 服务端拒绝）。
    public var failed: Int
    /// 被改写成本地服务端引用的消息条数。
    public var messagesRewritten: Int
    /// 真正上传的字节数（日志/诊断用）。
    public var bytesUploaded: Int
    public var failures: [ServerHistoryMediaFailure]

    public init(
        total: Int = 0,
        uploaded: Int = 0,
        reused: Int = 0,
        failed: Int = 0,
        messagesRewritten: Int = 0,
        bytesUploaded: Int = 0,
        failures: [ServerHistoryMediaFailure] = []
    ) {
        self.total = total
        self.uploaded = uploaded
        self.reused = reused
        self.failed = failed
        self.messagesRewritten = messagesRewritten
        self.bytesUploaded = bytesUploaded
        self.failures = failures
    }

    /// 给日志/设置页的一句话（中文；App 层直接用 Kit 给的说法，不新增本地化 key）。
    public var describe: String {
        var parts: [String] = []
        if uploaded > 0 { parts.append("上传 \(uploaded) 项") }
        if reused > 0 { parts.append("已有 \(reused) 项") }
        if failed > 0 { parts.append("失败 \(failed) 项") }
        if parts.isEmpty { return "没有需要搬的图片/语音" }
        return "媒体：" + parts.joined(separator: "，")
    }
}

/// 逐项进度（bootstrap 把它转成设置页那一行）。
public struct ServerHistoryMediaProgress: Equatable, Sendable {
    public var completed: Int
    public var total: Int
    public var detail: String

    public init(completed: Int, total: Int, detail: String = "") {
        self.completed = completed
        self.total = total
        self.detail = detail
    }
}

/// 能跑一次「历史媒体全量上传」的东西（bootstrap 依赖它；测试注入假的来测取消/顺序）。
public protocol ServerHistoryMediaUploading: AnyObject {
    func uploadAll() async throws -> ServerHistoryMediaReport
}

// MARK: - 上传器

/// 枚举本地历史消息里的附件，逐项上传并把引用改写为 `/v1/media/<id>`。
///
/// `actor`：bootstrap 同一时刻只会跑一次；把「枚举 → 上传 → 改写」放在一个隔离域里，
/// 避免两次同步同时改同一条消息的 `attachmentsJson`。
public actor ServerHistoryMediaUploader: ServerHistoryMediaUploading {

    /// 一次读多少条本地消息（与 bootstrap 分页读同一口径，不把整段历史读进内存）。
    static let pageSize = 400

    private let uploader: any ServerMediaFileUploading
    private let stores: DatabaseStores
    private let state: ServerSyncStateStore
    /// 这些媒体属于哪台服务端（媒体 id 是那边生成的；去重键必须带上它）。
    private let baseURLKey: String
    private let preparer: any ServerHistoryMediaPreparing
    private let now: () -> Int64
    private let log: (String) -> Void
    private let progress: (@Sendable (ServerHistoryMediaProgress) -> Void)?

    /// 枚举出来的一项附件。
    struct Item {
        var messageId: String
        var attachment: MessageAttachment
        var index: Int
    }

    public init(
        uploader: any ServerMediaFileUploading,
        stores: DatabaseStores,
        state: ServerSyncStateStore? = nil,
        baseURL: URL? = nil,
        preparer: any ServerHistoryMediaPreparing = LocalServerHistoryMediaPreparer(),
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in },
        progress: (@Sendable (ServerHistoryMediaProgress) -> Void)? = nil
    ) {
        self.uploader = uploader
        self.stores = stores
        self.state = state ?? ServerSyncStateStore(database: stores.database)
        self.baseURLKey = baseURL?.absoluteString ?? "unknown"
        self.preparer = preparer
        self.now = now
        self.log = log
        self.progress = progress
    }

    // MARK: 主流程

    /// 跑一次全量上传。
    ///
    /// - Throws: `CancellationError`（调用方取消；本地引用已还原）或读库错误。
    public func uploadAll() async throws -> ServerHistoryMediaReport {
        let items = try pendingItems()
        var report = ServerHistoryMediaReport(total: items.count)
        publish(ServerHistoryMediaProgress(completed: 0, total: items.count, detail: "媒体"))
        // 本次改写过哪些消息（messageId → 原始 attachmentsJson）：取消时用它回滚。
        var originals: [String: String] = [:]

        do {
            for (offset, item) in items.enumerated() {
                try Task.checkCancellation()

                // 1) 已经是服务端引用：不用传（从服务端拉回来的历史再搬一次的情形）。
                if ServerMediaReference.mediaId(from: item.attachment.uri) != nil {
                    report.reused += 1
                    publish(progress(offset: offset, total: items.count))
                    continue
                }

                // 2) 准备字节（压缩图片 / 读音频视频）。
                guard let prepared = preparer.prepare(attachment: item.attachment) else {
                    report.failed += 1
                    report.failures.append(ServerHistoryMediaFailure(
                        messageId: item.messageId,
                        uri: item.attachment.uri,
                        reason: "本地文件不在、格式不支持或超过上限"
                    ))
                    publish(progress(offset: offset, total: items.count))
                    continue
                }
                try Task.checkCancellation()

                // 3) 按内容哈希去重（重试/断点续跑不重复上传）。
                let sha256 = ImageContentHash.sha256Hex(prepared.bytes)
                let uri: String
                if let known = state.string(mediaStateKey(sha256)),
                   ServerMediaReference.isServerMediaURI(known) {
                    uri = known
                    report.reused += 1
                } else {
                    do {
                        let media = try await uploader.uploadMediaFile(
                            bytes: prepared.bytes,
                            mime: prepared.mime,
                            kind: prepared.kind,
                            personaId: nil
                        )
                        uri = ServerMediaReference.uri(id: media.id)
                        // 先记映射再改写：万一进程在两者之间被杀掉，下一次也不会重传。
                        try? state.set(uri, for: mediaStateKey(sha256), at: now())
                        report.uploaded += 1
                        report.bytesUploaded += prepared.bytes.count
                    } catch is CancellationError {
                        throw CancellationError()
                    } catch {
                        // URLSession 的取消常被映射成 network_error：任务已取消时按取消处理，
                        // 不要把它算进「失败项」（否则取消会被报告成「几张图没传上去」）。
                        if Task.isCancelled { throw CancellationError() }
                        report.failed += 1
                        report.failures.append(ServerHistoryMediaFailure(
                            messageId: item.messageId,
                            uri: item.attachment.uri,
                            reason: ServerSyncService.describe(error)
                        ))
                        publish(progress(offset: offset, total: items.count))
                        continue
                    }
                }
                try Task.checkCancellation()

                // 4) 改写这条消息的附件引用（bootstrap 的 import 从这里读）。
                if try rewrite(
                    messageId: item.messageId,
                    attachmentIndex: item.index,
                    to: uri,
                    originals: &originals
                ) {
                    report.messagesRewritten += 1
                }
                publish(progress(offset: offset, total: items.count))
            }
        } catch {
            // 取消/意外：把本次改写的引用还原，避免「没切模式但本机图变成了坏引用」。
            restore(originals)
            throw error
        }

        log("历史媒体：\(report.describe)")
        return report
    }

    // MARK: 枚举

    /// 本地所有「会被 import 的消息」里的附件（按会话/时间顺序）。
    ///
    /// 过滤规则与 `ServerBootstrapService.readLocalMessages` 一致：
    /// 排队中的占位消息、发送失败的消息不参与（它们本来就不推）。
    func pendingItems() throws -> [Item] {
        var result: [Item] = []
        let sessions = try stores.sessions.all(orderBy: "createdAt ASC")
        for session in sessions {
            var cursorAt = Int64.min
            var cursorId = ""
            while true {
                let page = try stores.messages.pageAfter(
                    sessionId: session.id,
                    createdAt: cursorAt,
                    id: cursorId,
                    limit: Self.pageSize
                )
                if page.isEmpty { break }
                for message in page {
                    guard !message.id.hasPrefix(ServerChatGateway.pendingIdPrefix) else { continue }
                    guard message.status != .error else { continue }
                    let attachments = AttachmentsCodec.decode(message.attachmentsJson)
                    guard !attachments.isEmpty else { continue }
                    for (index, attachment) in attachments.enumerated() {
                        result.append(Item(messageId: message.id, attachment: attachment, index: index))
                    }
                }
                guard let last = page.last else { break }
                cursorAt = last.createdAt
                cursorId = last.id
                if page.count < Self.pageSize { break }
            }
        }
        return result
    }

    // MARK: 改写 / 回滚

    /// 把一条消息的第 `attachmentIndex` 个附件的 uri 换成服务端引用。
    ///
    /// - Returns: 真的写进库了才 true。
    @discardableResult
    private func rewrite(
        messageId: String,
        attachmentIndex: Int,
        to uri: String,
        originals: inout [String: String]
    ) throws -> Bool {
        guard var message = (try? stores.messages.byId(messageId)) ?? nil,
              let json = message.attachmentsJson else { return false }
        var attachments = AttachmentsCodec.decode(json)
        guard attachmentIndex >= 0, attachmentIndex < attachments.count else { return false }
        if originals[messageId] == nil { originals[messageId] = json }
        attachments[attachmentIndex].uri = uri
        message.attachmentsJson = AttachmentsCodec.encode(attachments)
        try stores.messages.upsert(message)
        return true
    }

    /// 取消/失败时复原（只动本次改过的消息；不删已经上传到服务端的媒体）。
    private func restore(_ originals: [String: String]) {
        guard !originals.isEmpty else { return }
        for (messageId, json) in originals {
            guard var message = (try? stores.messages.byId(messageId)) ?? nil else { continue }
            message.attachmentsJson = json
            try? stores.messages.upsert(message)
        }
        log("已还原 \(originals.count) 条消息的本地附件引用")
    }

    // MARK: 内部

    /// sha256 → `/v1/media/<id>` 的簿记键（带 baseURL，换服务端不会复用旧 id）。
    private func mediaStateKey(_ sha256: String) -> String {
        ServerSyncStateStore.Key.mediaSha256(baseURLKey, sha256)
    }

    private func progress(offset: Int, total: Int) -> ServerHistoryMediaProgress {
        ServerHistoryMediaProgress(completed: offset + 1, total: total, detail: "媒体")
    }

    private func publish(_ value: ServerHistoryMediaProgress) {
        progress?(value)
    }
}
