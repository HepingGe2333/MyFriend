import CoreGraphics
import Foundation

// ---------------------------------------------------------------------------
// 表情包的领域服务：导入 / 制作 / 发送 / 收藏。
//
// 它把四样东西接在一起：
//   压缩管线（`StickerGIF` / `StickerComposer`）→ 文件落盘（`StickerStorage`）
//   → 数据库行（`stickers`）→ 消息附件（`MessageAttachment`，`kind=STICKER`）。
//
// 三条入口对应模型工具的三个 action（`spec/stickers/README.md` §模型工具）：
// - `send`：把已有表情作为一条助手消息发出（attachment 指向同一个文件，不复制、不重编码）；
// - `make`：下载 URL 图片 / 以已有表情为基底 → 叠字或叠图 → 走同一条压缩管线 → 新表情；
// - `save`：把用户发过（或模型发过）的表情加入「我的表情」。
//
// 服务端模式（M5）：文件不落本机，`stickers.file` 存 `/v1/media/<id>`；上传/下载都由
// 装配层注入的闭包做（Kit 层不知道「现在是不是服务端模式」，也不该知道）。
// 本地模式：闭包为 nil，一切走 `StickerStorage`。
// ---------------------------------------------------------------------------

/// 表情操作失败的原因（都要能直接给用户 / 模型一句人话）。
public enum StickerError: LocalizedError, Equatable {
    case emptyInput
    case tooLargeInput
    case unreadableImage
    case compressionFailed
    case downloadRejected(String)
    case downloadFailed(String)
    case downloadTooLarge
    case stickerNotFound
    case missingStickerId
    case missingMessageId
    case messageNotFound
    case messageHasNoSticker
    case missingActionInput
    case nothingToCompose
    case writeFailed

    public var errorDescription: String? {
        switch self {
        case .emptyInput: return String(localized: "没有拿到图片内容")
        case .tooLargeInput: return String(localized: "图片太大了，换一张小一点的")
        case .unreadableImage: return String(localized: "读不出这张图片，换一张试试")
        case .compressionFailed: return String(localized: "这张图压不成表情（尺寸/帧数都超了）")
        case .downloadRejected(let reason): return reason
        case .downloadFailed(let message): return message
        case .downloadTooLarge: return String(localized: "网上的图片太大了（最多 8 MB）")
        case .stickerNotFound: return String(localized: "找不到这个表情（id 可能不对）")
        case .missingStickerId: return String(localized: "没有说要发哪个表情（需要 stickerId）")
        case .missingMessageId: return String(localized: "没有说是哪条消息里的表情（需要 fromMessageId）")
        case .messageNotFound: return String(localized: "找不到这条消息")
        case .messageHasNoSticker: return String(localized: "这条消息里没有表情")
        case .missingActionInput: return String(localized: "要做表情至少得给一个 url / baseStickerId / text / overlayStickerId")
        case .nothingToCompose: return String(localized: "只给底图不用做新表情：要叠字或叠图，想直接发就用 send")
        case .writeFailed: return String(localized: "表情没能存下来")
        }
    }
}

/// 一次「保存到我的表情」的结果。
public struct StickerSaveResult: Equatable, Sendable {
    public var sticker: StickerEntity
    /// true = 之前就收藏过（同一个文件），这次什么都没写。
    public var alreadySaved: Bool

    public init(sticker: StickerEntity, alreadySaved: Bool) {
        self.sticker = sticker
        self.alreadySaved = alreadySaved
    }
}

public final class StickerService {

    private let stores: DatabaseStores
    private let storage: StickerStorage
    private let session: URLSession
    /// 服务端模式：把 GIF 字节上传成 `/v1/media/<id>`；nil = 本地模式。
    private let serverUploader: ((Data, String?) async throws -> String)?
    /// 服务端模式：按 `/v1/media/<id>` 取回字节（制作/收藏服务端表情时用）；nil = 本地模式。
    private let serverDownloader: ((String) async throws -> Data)?
    /// 服务端模式的用户 id（本地模式为 nil）。
    private let ownerUserId: () -> String?
    private let now: () -> Int64
    private let log: (String) -> Void

    public init(
        stores: DatabaseStores,
        storage: StickerStorage = .shared,
        session: URLSession = .shared,
        serverUploader: ((Data, String?) async throws -> String)? = nil,
        serverDownloader: ((String) async throws -> Data)? = nil,
        ownerUserId: @escaping () -> String? = { nil },
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.storage = storage
        self.session = session
        self.serverUploader = serverUploader
        self.serverDownloader = serverDownloader
        self.ownerUserId = ownerUserId
        self.now = now
        self.log = log
    }

    public var storageDirectory: URL { storage.directory }
    public var isServerMode: Bool { serverUploader != nil }

    // MARK: - 读

    /// 当前用户自己的表情（历史面板的「全部」）。
    ///
    /// 按 `ownerUserId` 过滤而不是 `allStickers()`：服务端模式是多用户的，
    /// 同一个库/服务端上别人做的表情不该出现在「我的表情」里（本地模式 owner 为 nil）。
    public func all() throws -> [StickerEntity] {
        try stores.stickers.byOwner(ownerUserId())
    }

    /// 历史面板的「最近 30 个」。
    public func recent(limit: Int = StickerLimits.recentLimit) throws -> [StickerEntity] {
        Array(try stores.stickers.byOwner(ownerUserId()).prefix(max(0, limit)))
    }

    public func sticker(id: String) throws -> StickerEntity? {
        try stores.stickers.byId(id)
    }

    /// 给界面/消息用的附件（`kind=STICKER`，mime `image/gif`）。
    public func attachment(for sticker: StickerEntity) -> MessageAttachment {
        let uri: String
        if sticker.isServerSticker {
            uri = sticker.file
        } else if let url = storage.fileURL(for: sticker.file) {
            uri = url.absoluteString
        } else {
            // 文件不在也照样给 uri：界面会显示「表情已不在本机」，而不是整条消息消失。
            uri = sticker.file
        }
        return MessageAttachment(
            uri: uri,
            mime: "image/gif",
            width: sticker.width,
            height: sticker.height,
            kind: MessageAttachment.stickerKind
        )
    }

    /// 导入（相册 / 文件 / 粘贴）一张图片或 GIF。
    ///
    /// - Parameters:
    ///   - data: 原始字节。
    ///   - source: 来源（用户导入 = `.imported`）。
    ///   - personaId: 挂在谁名下（用户导入一般 nil）。
    ///   - createdBy: 谁做的（用户导入 = `.user`）。
    @discardableResult
    public func importSticker(
        data: Data,
        source: StickerSource = .imported,
        personaId: String? = nil,
        createdBy: StickerCreator = .user
    ) async throws -> StickerEntity {
        guard !data.isEmpty else { throw StickerError.emptyInput }
        guard data.count <= StickerLimits.maxImportBytes else { throw StickerError.tooLargeInput }
        guard let result = StickerGIF.compress(data: data) else {
            throw StickerError.compressionFailed
        }
        return try await persist(
            result: result,
            source: source,
            personaId: personaId,
            createdBy: createdBy
        )
    }

    /// 从本地文件导入（`fileImporter` 拿到的是临时 URL）。
    @discardableResult
    public func importSticker(
        at url: URL,
        source: StickerSource = .imported,
        personaId: String? = nil,
        createdBy: StickerCreator = .user
    ) async throws -> StickerEntity {
        guard let data = try? Data(contentsOf: url), !data.isEmpty else {
            throw StickerError.unreadableImage
        }
        return try await importSticker(
            data: data, source: source, personaId: personaId, createdBy: createdBy
        )
    }

    // MARK: - 制作

    /// 制作新表情（`sticker.make`）。
    ///
    /// 四种输入可以组合：
    /// - `url`：下载一张公开 http(s) 图片（走 `WebUrlPolicy`、≤8 MiB）；
    /// - `baseStickerId`：用已有表情当基底（可以是动图，逐帧叠）；
    /// - `text`：叠字（meme 风格；没有 base 时用内置空白底）；
    /// - `overlayStickerId`：叠另一张表情（只取它的一帧）。
    ///
    /// 全部合成完之后走 `StickerGIF.compress`，所以输出同样 ≤320 px / ≤60 帧 / ≤6 s / ≤512 KB。
    @discardableResult
    public func makeSticker(
        url: String? = nil,
        baseStickerId: String? = nil,
        text: String? = nil,
        overlayStickerId: String? = nil,
        position: String? = nil,
        personaId: String? = nil
    ) async throws -> StickerEntity {
        let cleanURL = url?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let cleanBase = baseStickerId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let cleanOverlay = overlayStickerId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let cleanText = String(
            (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(StickerLimits.maxOverlayTextLength)
        )
        guard !cleanURL.isEmpty || !cleanBase.isEmpty || !cleanOverlay.isEmpty || !cleanText.isEmpty else {
            throw StickerError.missingActionInput
        }

        // 1) 基底的帧与延迟。
        var baseFrames: [CGImage] = []
        var baseDelays: [Int] = []
        var downloadedFromWeb = false
        var downloadedData: Data?
        if !cleanBase.isEmpty {
            guard let base = try sticker(id: cleanBase) else { throw StickerError.stickerNotFound }
            guard let data = try await data(for: base) else { throw StickerError.unreadableImage }
            guard let decoded = StickerGIF.decodeFrames(data: data) else {
                throw StickerError.unreadableImage
            }
            baseFrames = decoded.frames
            baseDelays = decoded.delaysMillis
        } else if !cleanURL.isEmpty {
            let data = try await download(url: cleanURL)
            downloadedFromWeb = true
            downloadedData = data
            guard let decoded = StickerGIF.decodeFrames(data: data) else {
                throw StickerError.unreadableImage
            }
            baseFrames = decoded.frames
            baseDelays = decoded.delaysMillis
        }

        // 2) 叠图（只取一帧）。
        var overlayFrame: CGImage?
        if !cleanOverlay.isEmpty {
            guard let overlay = try sticker(id: cleanOverlay) else { throw StickerError.stickerNotFound }
            guard let data = try await data(for: overlay) else { throw StickerError.unreadableImage }
            guard let decoded = StickerGIF.decodeFrames(data: data), let first = decoded.frames.first else {
                throw StickerError.unreadableImage
            }
            overlayFrame = first
        }

        // 3) 只有 URL、没有叠东西：直接当「网上下载的表情」压缩入库（不重复合成一遍）。
        let hasDecoration = !cleanText.isEmpty || overlayFrame != nil
        if !hasDecoration, !downloadedFromWeb {
            // 只给了 baseStickerId、什么也没叠：做出来会和底图一模一样，没有意义。
            throw StickerError.nothingToCompose
        }
        if !hasDecoration, downloadedFromWeb {
            // 注意：**不要**在这里再下一次 —— 同一张图下载两次既慢又可能被限流；
            // 上面已经拿到 `downloadedData`，直接用它压缩。
            guard let downloadedData, let result = StickerGIF.compress(data: downloadedData) else {
                throw StickerError.compressionFailed
            }
            return try await persist(
                result: result, source: .web, personaId: personaId, createdBy: .model
            )
        }

        // 4) 合成 → 同一条压缩管线。
        let positionValue = StickerComposer.Position.parse(position)
        guard let composition = StickerComposer.compose(
            baseFrames: baseFrames,
            baseDelays: baseDelays,
            overlay: overlayFrame,
            text: cleanText.isEmpty ? nil : cleanText,
            position: positionValue
        ) else {
            throw StickerError.compressionFailed
        }
        guard let result = StickerGIF.compress(
            frames: composition.frames, delaysMillis: composition.delaysMillis
        ) else {
            throw StickerError.compressionFailed
        }
        log("[sticker] make 完成：\(result.displaySummary)（\(downloadedFromWeb ? "web" : "base")）")
        return try await persist(
            result: result, source: .modelMade, personaId: personaId, createdBy: .model
        )
    }

    // MARK: - 发送

    /// 把已有表情作为一条**助手消息**发出（`sticker.send`）。
    ///
    /// 附件直接指向表情文件（服务端模式是 `/v1/media/<id>`），不复制、不重新编码。
    @discardableResult
    public func send(stickerId: String, personaId: String, sessionId: String) throws -> MessageEntity {
        let id = stickerId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { throw StickerError.missingStickerId }
        guard let sticker = try stores.stickers.byId(id) else { throw StickerError.stickerNotFound }
        guard let session = try stores.sessions.byId(sessionId) else { throw StickerError.messageNotFound }
        let attachment = attachment(for: sticker)
        let timestamp = try nextTimestamp(sessionId: sessionId)
        let message = MessageEntity(
            id: UUID().uuidString,
            sessionId: sessionId,
            personaId: personaId.isEmpty ? session.personaId : personaId,
            role: .assistant,
            content: "",
            createdAt: timestamp,
            updatedAt: timestamp,
            status: .complete,
            model: nil,
            edited: false,
            attachmentsJson: AttachmentsCodec.encode([attachment])
        )
        try stores.database.inTransaction {
            try stores.messages.upsert(message)
            var updated = (try stores.sessions.byId(sessionId)) ?? session
            updated.lastMessagePreview = String(localized: "表情")
            updated.lastMessageAt = max(updated.lastMessageAt ?? 0, timestamp)
            updated.updatedAt = max(updated.updatedAt, timestamp)
            try stores.sessions.upsert(updated)
        }
        return message
    }

    /// 给**用户**发送一条表情准备附件：服务端模式先上传成 `/v1/media/<id>`，本地模式用文件路径。
    ///
    /// 为什么用户发送也走这里：服务端模式的 `ServerChatGateway` 只接受「已经上传好」的媒体 id；
    /// 它自己的 `LocalChatMediaPreparer` 只认识聊天图目录，表情文件在那个目录之外
    ///（而且它会把 GIF 重压成 JPEG，表情就不动了）。
    public func prepareForSending(
        _ sticker: StickerEntity,
        personaId: String? = nil
    ) async throws -> MessageAttachment {
        if sticker.isServerSticker { return attachment(for: sticker) }
        guard let uploader = serverUploader else { return attachment(for: sticker) }
        guard let data = try await data(for: sticker) else { throw StickerError.unreadableImage }
        let uri = try await uploader(data, personaId)
        return MessageAttachment(
            uri: uri,
            mime: "image/gif",
            width: sticker.width,
            height: sticker.height,
            kind: MessageAttachment.stickerKind
        )
    }

    // MARK: - 收藏

    /// 把一条消息里的表情加入「我的表情」。
    ///
    /// 规则（`spec/stickers/README.md` §发送与历史）：
    /// - 同一个文件已经收藏过 → 直接返回已有那条（`alreadySaved = true`），不重复占空间；
    /// - 同一个文件在库里已有记录（模型做的 / 用户导入的）→ **createdBy / source 不变**，
    ///   只增加 owner 关联（这条记录本来就属于用户，所以直接返回它）；
    /// - 文件是从别处（服务端媒体 / 聊天附件目录）来的 → 复制/下载进表情目录，再落一行。
    public func save(fromMessageId: String) async throws -> StickerSaveResult {
        let id = fromMessageId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { throw StickerError.missingMessageId }
        guard let message = try stores.messages.byId(id) else { throw StickerError.messageNotFound }
        let attachments = AttachmentsCodec.decode(message.attachmentsJson)
        guard let attachment = attachments.first(where: { $0.isSticker }) else {
            throw StickerError.messageHasNoSticker
        }
        let owner = ownerUserId()

        // 1) 已经有同文件 + 同 owner 的收藏：直接返回。
        if let existing = try stores.stickers.findByFile(attachment.uri, ownerUserId: owner) {
            return StickerSaveResult(sticker: existing, alreadySaved: true)
        }
        if let localURL = storage.fileURL(for: attachment.uri),
           let existing = try stores.stickers.findByFile(localURL.path, ownerUserId: owner) {
            return StickerSaveResult(sticker: existing, alreadySaved: true)
        }

        // 2) 文件已经在表情目录里、库里也有记录（模型做的 / 之前导入的）：
        //    它本来就在「我的表情」里，只补 owner 关联（本地模式没有 owner，就什么都不改）。
        if let localURL = storage.fileURL(for: attachment.uri),
           let existing = try stores.stickers.firstByFile(localURL.path) {
            var owned = existing
            if let owner, owned.ownerUserId != owner {
                owned.ownerUserId = owner
                try stores.stickers.upsert(owned)
            }
            return StickerSaveResult(sticker: owned, alreadySaved: true)
        }

        // 3) 服务端媒体：下载字节读元数据，`file` 继续指向服务端（不复制到本机）。
        if attachment.uri.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("/v1/media/") {
            guard let downloader = serverDownloader else {
                throw StickerError.unreadableImage
            }
            let data = try await downloader(attachment.uri)
            guard let inspected = StickerGIF.inspect(data: data) else {
                throw StickerError.unreadableImage
            }
            let sticker = StickerEntity(
                id: UUID().uuidString,
                ownerUserId: owner,
                personaId: message.personaId,
                file: attachment.uri,
                mime: "image/gif",
                width: inspected.width,
                height: inspected.height,
                frames: inspected.frames,
                bytes: data.count,
                source: StickerSource.fromUserMessage.rawValue,
                createdBy: message.role == .assistant
                    ? StickerCreator.model.rawValue
                    : StickerCreator.user.rawValue,
                createdAt: now()
            )
            try stores.stickers.upsert(sticker)
            return StickerSaveResult(sticker: sticker, alreadySaved: false)
        }

        // 4) 本机文件（通常在聊天附件目录，不在表情目录）：复制进表情目录，再落一行。
        //    这里不能只用 `StickerStorage.fileURL(for:)`：那个只认表情目录里的文件。
        guard let sourceURL = Self.localFileURL(attachment.uri),
              let data = try? Data(contentsOf: sourceURL), !data.isEmpty else {
            throw StickerError.unreadableImage
        }
        guard data.count <= StickerLimits.maxImportBytes else { throw StickerError.tooLargeInput }
        guard let inspected = StickerGIF.inspect(data: data) else {
            throw StickerError.unreadableImage
        }
        // 用内容哈希当 id：同一张图从不同消息收藏两次会落到同一个文件/同一行，
        // 「已经收藏过」这件事就不依赖调用方记没记住（也不需要新增一列源 URL）。
        let stickerId = "saved-" + ImageContentHash.shortPrefix(data)
        if let existing = try stores.stickers.byId(stickerId) {
            var owned = existing
            if let owner, owned.ownerUserId != owner {
                owned.ownerUserId = owner
                try stores.stickers.upsert(owned)
            }
            return StickerSaveResult(sticker: owned, alreadySaved: true)
        }
        let target = try storage.copy(from: sourceURL, id: stickerId)
        let sticker = StickerEntity(
            id: stickerId,
            ownerUserId: owner,
            personaId: message.personaId,
            file: target.path,
            mime: "image/gif",
            width: inspected.width,
            height: inspected.height,
            frames: inspected.frames,
            bytes: data.count,
            source: StickerSource.fromUserMessage.rawValue,
            createdBy: message.role == .assistant
                ? StickerCreator.model.rawValue
                : StickerCreator.user.rawValue,
            createdAt: now()
        )
        try stores.stickers.upsert(sticker)
        return StickerSaveResult(sticker: sticker, alreadySaved: false)
    }

    // MARK: - 删除（设置页/面板的「删掉这个表情」）

    /// 删一行与它的文件。
    ///
    /// 文件还被聊天消息引用时**只删行、保留文件**：那条消息仍能正常显示表情，
    /// 用户删的是「收藏」，不是「历史」。引用判断按文件名在 `attachmentsJson` 里查
    /// （手动删除才走这里，一次 LIKE 扫描的代价可以接受）。
    @discardableResult
    public func delete(id: String) throws -> Bool {
        guard let sticker = try stores.stickers.byId(id) else { return false }
        let fileName = (sticker.file as NSString).lastPathComponent
        let referenced = !fileName.isEmpty && (try? stores.messages.count(
            where: "attachmentsJson LIKE ?", [.text("%\(fileName)%")]
        )) .map { $0 > 0 } ?? false
        let removed = try stores.stickers.remove(id: id)
        if removed, !sticker.isServerSticker, !referenced {
            _ = storage.delete(file: sticker.file)
        }
        return removed
    }

    // MARK: - 内部：落盘 + 落库

    /// 压缩产物 → 文件（或服务端媒体）→ `stickers` 一行。
    func persist(
        result: StickerGIFResult,
        source: StickerSource,
        personaId: String?,
        createdBy: StickerCreator
    ) async throws -> StickerEntity {
        let id = UUID().uuidString
        let file: String
        if let uploader = serverUploader {
            let uri: String
            do {
                uri = try await uploader(result.data, personaId)
            } catch {
                log("[sticker] 上传失败：\(error.localizedDescription)")
                throw StickerError.writeFailed
            }
            file = uri
        } else {
            do {
                let url = try storage.write(result.data, id: id)
                file = url.path
            } catch {
                throw StickerError.writeFailed
            }
        }
        let sticker = StickerEntity(
            id: id,
            ownerUserId: ownerUserId(),
            personaId: personaId,
            file: file,
            mime: "image/gif",
            width: result.width,
            height: result.height,
            frames: result.frames,
            bytes: result.bytes,
            source: source.rawValue,
            createdBy: createdBy.rawValue,
            createdAt: now()
        )
        do {
            try stores.stickers.upsert(sticker)
        } catch {
            if !sticker.isServerSticker {
                _ = storage.delete(file: sticker.file)
            }
            log("[sticker] 落库失败：\(error.localizedDescription)")
            throw StickerError.writeFailed
        }
        return sticker
    }

    /// 读一个表情的字节（本机直读；服务端媒体走注入的下载器）。
    func data(for sticker: StickerEntity) async throws -> Data? {
        if sticker.isServerSticker {
            guard let downloader = serverDownloader else { return nil }
            return try await downloader(sticker.file)
        }
        return storage.data(for: sticker)
    }

    // MARK: - 内部：下载

    /// 下载一张公开图片：`WebUrlPolicy` 先判，`≤8 MiB` 后判。
    func download(url: String) async throws -> Data {
        if let rejection = WebUrlPolicy.check(url) {
            throw StickerError.downloadRejected(Self.rejectionMessage(rejection))
        }
        guard let parsed = URL(string: url.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw StickerError.downloadRejected(String(localized: "这个链接不像一个网址"))
        }
        var request = URLRequest(url: parsed)
        request.httpMethod = "GET"
        request.timeoutInterval = StickerLimits.downloadTimeout
        request.setValue("image/*", forHTTPHeaderField: "Accept")
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            if error is CancellationError { throw error }
            let message = (error as NSError).localizedDescription
            throw StickerError.downloadFailed(
                message.isEmpty
                    ? String(localized: "图片没下载下来，网络可能断了")
                    : String(format: String(localized: "图片没下载下来：%@"), message)
            )
        }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw StickerError.downloadFailed(
                String(format: String(localized: "对方服务器返回了 %d"), http.statusCode)
            )
        }
        if let expected = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Length"),
           let bytes = Int64(expected), bytes > Int64(StickerLimits.maxDownloadBytes) {
            throw StickerError.downloadTooLarge
        }
        guard !data.isEmpty else { throw StickerError.emptyInput }
        guard data.count <= StickerLimits.maxDownloadBytes else { throw StickerError.downloadTooLarge }
        return data
    }

    /// 把 `file://` / 绝对路径解成本机文件 URL；拒绝路径穿越与服务端媒体。
    ///
    /// 只给「收藏消息里的表情」用：那个路径来自我们自己的 `attachmentsJson`，
    /// 但仍按「数据即输入」处理（0.11.1 审计 P2-3 的同一条规则）。
    static func localFileURL(_ raw: String) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              ServerMediaReference.mediaId(from: trimmed) == nil,
              !AttachmentLocation.hasPathTraversal(trimmed) else { return nil }
        let url: URL
        if trimmed.lowercased().hasPrefix("file://") {
            guard let parsed = URL(string: trimmed), parsed.isFileURL else { return nil }
            url = parsed
        } else if trimmed.hasPrefix("/") {
            url = URL(fileURLWithPath: trimmed)
        } else {
            return nil
        }
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    static func rejectionMessage(_ rejection: WebUrlPolicy.Rejection) -> String {
        switch rejection {
        case .empty: return String(localized: "没有给出图片链接")
        case .unsupportedScheme: return String(localized: "只支持 http / https 的图片链接")
        case .missingHost: return String(localized: "这个链接里没有域名")
        case .localHost: return String(localized: "不能下载本机或内网地址")
        case .credentialsInURL: return String(localized: "链接里不能带用户名或密码")
        case .ambiguousAuthority: return String(localized: "这个链接的写法不合法")
        case .invalidHost: return String(localized: "链接里不能有空格或控制字符")
        }
    }

    // MARK: - 内部：小工具

    private func nextTimestamp(sessionId: String) throws -> Int64 {
        let latest = try stores.messages.latest(sessionId: sessionId)
        let timestamp = now()
        if let latest, timestamp <= latest.createdAt { return latest.createdAt + 1 }
        return timestamp
    }

}
