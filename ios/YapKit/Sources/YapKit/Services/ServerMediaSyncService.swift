import Foundation

// ---------------------------------------------------------------------------
// 服务端媒体的「落地同步」（0.13 第二阶段）：把消息附件 / 头像 / 表情从
// `/v1/media/<id>` 变成**本机持久文件**，并把库里的引用改成本地路径。
//
// 触发时机（装配层负责接线，见交付报告）：进聊天页拉到新块之后、一次增量同步之后、
// 以及连上服务端之后的第一次整理。全都**不阻塞**任何界面动作：这是一个后台的「搬家」，
// 搬不动就下次再搬 —— 渲染仍然能走 `ServerMediaCache` 那条老路（带鉴权按需下载）。
//
// 四条不变量：
// 1. **幂等**：同一个 mediaId 已有本地文件就跳过（`ServerMediaLocalStore.existingFile`）；
// 2. **失败不阻断**：单个媒体失败只计数并保留原引用（界面显示占位图，不是整页报错）；
// 3. **先落盘、后改库**：文件写成功才重写 `attachmentsJson` —— 库里绝不能出现指向
//    一个不存在的文件的引用（那会让用户看到「图片已不在本机」）；
// 4. **保留 serverMediaId**：改成本地 `file://` 之后仍然记着服务端那一份的 id，
//    将来重新上传（转发/补发）或本机文件被清掉时都有依据。
// ---------------------------------------------------------------------------

/// 一次落地同步的结果（日志/诊断用）。
public struct ServerMediaSyncReport: Equatable, Sendable {
    /// 这次真的下载并落盘的份数。
    public var downloaded: Int
    /// 已经有本地文件、直接跳过（幂等）的份数。
    public var reused: Int
    /// 下载/落盘失败（保留原服务端引用，界面走占位）的份数。
    public var failed: Int
    /// 被改写的消息行 / 头像 / 表情行数。
    public var messagesUpdated: Int
    public var avatarsUpdated: Int
    public var stickersUpdated: Int

    public init(
        downloaded: Int = 0, reused: Int = 0, failed: Int = 0,
        messagesUpdated: Int = 0, avatarsUpdated: Int = 0, stickersUpdated: Int = 0
    ) {
        self.downloaded = downloaded
        self.reused = reused
        self.failed = failed
        self.messagesUpdated = messagesUpdated
        self.avatarsUpdated = avatarsUpdated
        self.stickersUpdated = stickersUpdated
    }

    public var isEmpty: Bool {
        downloaded == 0 && reused == 0 && failed == 0
            && messagesUpdated == 0 && avatarsUpdated == 0 && stickersUpdated == 0
    }

    public var describe: String {
        "下载 \(downloaded) · 复用 \(reused) · 失败 \(failed)"
            + " · 改写（消息 \(messagesUpdated) / 头像 \(avatarsUpdated) / 表情 \(stickersUpdated)）"
    }
}

/// 媒体下载口（生产用 `ServerAPIClient.mediaDownload(mediaId:)`；测试注入假实现）。
///
/// 0.15 起多了一个带元信息的入口：下载响应头里的 `ETag` 就是服务端算的内容 sha256，
/// 落地前必须拿它比对（见 `ServerMediaLocalStore.storeVerified`）。
/// `mediaData` 保留为唯一的**必须实现**方法：老测试的假下载器一行都不用改，
/// 它们拿不到哈希时自动跳过校验（= 老服务端/旧数据的行为）。
public protocol ServerMediaDownloading: AnyObject {
    func mediaData(mediaId: String) async throws -> Data
    func mediaDownload(mediaId: String) async throws -> ServerMediaDownload
}

extension ServerMediaDownloading {
    /// 默认实现：只有字节、没有声明的哈希（跳过校验）。
    public func mediaDownload(mediaId: String) async throws -> ServerMediaDownload {
        ServerMediaDownload(data: try await mediaData(mediaId: mediaId))
    }
}

extension ServerAPIClient: ServerMediaDownloading {}

public final class ServerMediaSyncService {

    /// 一次最多处理多少条消息（防止首次同步时把几千条一次拉爆）。
    public static let defaultMessageLimit = 80
    /// 一轮最多处理多少个头像 / 表情。
    public static let defaultItemLimit = 60

    private let downloader: any ServerMediaDownloading
    private let stores: DatabaseStores
    private let localStore: ServerMediaLocalStore
    private let mediaMeta: ServerMediaMetaStore?
    private let hashStore: ServerMediaLocalHashStore?
    private let log: (String) -> Void

    public init(
        downloader: any ServerMediaDownloading,
        stores: DatabaseStores,
        localStore: ServerMediaLocalStore = .shared,
        mediaMeta: ServerMediaMetaStore? = nil,
        hashStore: ServerMediaLocalHashStore? = nil,
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.downloader = downloader
        self.stores = stores
        self.localStore = localStore
        self.mediaMeta = mediaMeta ?? ServerMediaMetaStore(database: stores.database)
        self.hashStore = hashStore ?? ServerMediaLocalHashStore(database: stores.database)
        self.log = log
    }

    // MARK: - 消息附件

    /// 把最近 `limit` 条消息里的服务端附件搬成本地文件。
    ///
    /// - Parameter sessionId: 只处理某个会话（进聊天页时用）；nil = 全库最近的消息。
    @discardableResult
    public func syncMessageAttachments(
        sessionId: String? = nil,
        limit: Int = ServerMediaSyncService.defaultMessageLimit
    ) async -> ServerMediaSyncReport {
        var report = ServerMediaSyncReport()
        let rows = recentMessages(sessionId: sessionId, limit: limit)
        for row in rows {
            let attachments = AttachmentsCodec.decode(row.attachmentsJson)
            guard attachments.contains(where: Self.isServerAttachment) else { continue }
            var rewritten: [MessageAttachment] = []
            var changed = false
            for attachment in attachments {
                guard Self.isServerAttachment(attachment),
                      let mediaId = ServerMediaReference.mediaId(from: attachment.uri) else {
                    rewritten.append(attachment)
                    continue
                }
                let enrichment = await resolve(attachment: attachment, mediaId: mediaId, report: &report)
                if let enrichment {
                    rewritten.append(enrichment)
                    changed = true
                } else {
                    // 失败/超限：保留原引用（渲染层还能按需下载显示占位图）。
                    rewritten.append(attachment)
                }
            }
            guard changed, let json = AttachmentsCodec.encode(rewritten) else { continue }
            do {
                guard var message = (try? stores.messages.byId(row.id)) ?? nil else { continue }
                message.attachmentsJson = json
                try stores.messages.upsert(message)
                report.messagesUpdated += 1
            } catch {
                log("[media] 改写消息附件失败（\(row.id)）：\(error.localizedDescription)")
            }
        }
        if !report.isEmpty { log("[media] 消息附件落地：\(report.describe)") }
        return report
    }

    /// 一条服务端附件 → 本地附件（下载 + 校验 + 落盘）。失败返回 nil（调用方保留原引用）。
    ///
    /// 0.15 的两道校验：
    /// 1. 下载响应声明的 sha256（`ETag`，或上传时记下的元信息）必须与字节一致；
    /// 2. 复用本机已有文件之前，先按记下的哈希复核文件（旧数据没有记录 → 直接复用）。
    private func resolve(
        attachment: MessageAttachment,
        mediaId: String,
        report: inout ServerMediaSyncReport
    ) async -> MessageAttachment? {
        let kind = ServerMediaLocalKind.kind(for: attachment)
        // 幂等：已有且（有哈希记录时）校验通过的文件直接复用。
        if let existing = reusableFile(mediaId: mediaId, kind: kind) {
            report.reused += 1
            return localStore.localAttachment(
                for: attachment, mediaId: mediaId, kind: kind, fileURL: existing
            )
        }
        do {
            let download = try await downloader.mediaDownload(mediaId: mediaId)
            let data = download.data
            // 先按文件头认一次：别的设备上传的语音/视频在这台手机上没有任何元信息，
            // 只按「没有 kind 就当图片」会让它落错目录（详见 `ServerMediaSniffer`）。
            let sniffed = ServerMediaSniffer.sniff(data)
            let resolvedKind = Self.resolveKind(
                attachment: attachment, sniffed: sniffed, fallback: kind
            )
            let resolvedMime = Self.resolveMime(
                attachment: attachment, sniffed: sniffed, downloadMime: download.mime
            )
            // 校验值：响应头最可信；其次是本机上传那一刻记下的元信息。
            let expected = ServerMediaHash.normalized(download.sha256)
                ?? mediaMeta?.meta(mediaId: mediaId)?.sha256
            let stored = try localStore.storeVerified(
                data: data, mediaId: mediaId, kind: resolvedKind,
                mime: resolvedMime, expectedSha256: expected
            )
            hashStore?.record(mediaId: mediaId, sha256: stored.sha256)
            report.downloaded += 1
            // 上传时记下的元信息（时长/宽高）如果本机还留着，补进本地附件：
            // 服务端消息只带 mediaId，没有这些字段。
            var enriched = attachment
            if let meta = mediaMeta?.meta(mediaId: mediaId) {
                if let duration = meta.durationMs, enriched.durationMs == nil, duration > 0 {
                    enriched.durationMs = duration
                }
                if enriched.kind == nil, !meta.kind.isEmpty { enriched.kind = meta.kind }
                if enriched.width == 0, meta.width > 0 { enriched.width = meta.width }
                if enriched.height == 0, meta.height > 0 { enriched.height = meta.height }
            }
            // 嗅探出来的种类比「缺省当图片」更可信：补上 kind，界面才画对气泡。
            if enriched.kind == nil, let sniffed { enriched.kind = sniffed.attachmentKind }
            return localStore.localAttachment(
                for: enriched, mediaId: mediaId, kind: resolvedKind, fileURL: stored.url
            )
        } catch {
            // 失败只计数（含 404：服务端那边删了，本机就保持「占位」）。
            report.failed += 1
            if case ServerMediaLocalError.hashMismatch = error {
                // 哈希不一致是「这份字节不是服务端那份」：临时文件已经删掉、库也没改。
                log("[media] 校验失败（\(mediaId)）：下载字节与 sha256 不一致，已丢弃")
            } else {
                log("[media] 下载失败（\(mediaId)）：\(ServerSyncService.describe(error))")
            }
            return nil
        }
    }

    /// 可以复用的本机文件（幂等判据）。
    ///
    /// - 没有文件 → nil（照常下载）；
    /// - 有文件、但没有哈希记录（0.14 及更早的旧数据）→ 复用，不校验；
    /// - 有文件、有记录但**文件内容对不上记录** → 删掉并返回 nil（重新下载）。
    ///
    /// 为什么删：留着它等于把一份被改坏/截断的字节当成「已有缓存」，界面会一直显示
    /// 一张坏图，而同步永远认为「已经落地了」。
    private func reusableFile(mediaId: String, kind: ServerMediaLocalKind) -> URL? {
        guard let existing = localStore.existingFile(mediaId: mediaId, kind: kind) else {
            return nil
        }
        guard let recorded = hashStore?.recorded(mediaId: mediaId) else { return existing }
        guard let actual = localStore.sha256Hex(ofFileAt: existing) else {
            localStore.deleteStoredFile(at: existing)
            hashStore?.clear(mediaId: mediaId)
            return nil
        }
        if actual != recorded {
            localStore.deleteStoredFile(at: existing)
            hashStore?.clear(mediaId: mediaId)
            log("[media] 本机文件与记录不一致（\(mediaId)）：重新下载")
            return nil
        }
        return existing
    }

    /// 落哪个目录：附件自己写了 kind 就听它的，否则看文件头，再否则按图片。
    static func resolveKind(
        attachment: MessageAttachment,
        sniffed: ServerMediaSniffed?,
        fallback: ServerMediaLocalKind
    ) -> ServerMediaLocalKind {
        // 显式的 `kind` 是上传时写下的（最可信）：GIF 表情、语音、视频都能立刻分对。
        if attachment.normalizedKind != nil {
            let declared = ServerMediaLocalKind.kind(for: attachment)
            if declared != .chatImage || sniffed == nil { return declared }
        }
        return sniffed?.kind ?? fallback
    }

    /// 落盘扩展名要用的 mime。
    ///
    /// 三种情况改用嗅探结果：附件里没写、写的是默认值（`image/jpeg` 是「不知道」的同义词）、
    /// 或者**写错了大类**（例如老数据把 GIF 表情记成 `image/png`）。同一大类里就不动它 ——
    /// `image/jpeg` 与 `image/webp` 的区别由文件本身决定，不必替服务端改。
    static func resolveMime(
        attachment: MessageAttachment,
        sniffed: ServerMediaSniffed?,
        downloadMime: String? = nil
    ) -> String {
        // 附件没写 mime、或者只写了默认值（`image/jpeg` 是「不知道」的同义词）时，
        // 下载响应头里的 Content-Type 比默认值可信（0.15）。
        let responseMime = downloadMime?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let declared = attachment.mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let base: String = {
            guard declared.isEmpty || declared == MessageAttachment.defaultMime else { return declared }
            guard let responseMime, !responseMime.isEmpty else { return declared }
            return responseMime
        }()
        guard let sniffed else { return base }
        if base.isEmpty || base == MessageAttachment.defaultMime { return sniffed.mime }
        let declaredKind = ServerMediaLocalKind.kind(for: MessageAttachment(uri: "", mime: base))
        if declaredKind != sniffed.kind { return sniffed.mime }
        return base
    }

    static func isServerAttachment(_ attachment: MessageAttachment) -> Bool {
        ServerMediaReference.mediaId(from: attachment.uri) != nil
    }

    private struct MessageRow {
        var id: String
        var attachmentsJson: String?
    }

    private func recentMessages(sessionId: String?, limit: Int) -> [MessageRow] {
        let capped = max(1, min(limit, 500))
        let sql: String
        let bindings: [SQLiteValue]
        if let sessionId, !sessionId.isEmpty {
            sql = "SELECT id, attachmentsJson FROM messages WHERE sessionId = ? "
                + "AND attachmentsJson IS NOT NULL ORDER BY createdAt DESC LIMIT \(capped)"
            bindings = [.text(sessionId)]
        } else {
            sql = "SELECT id, attachmentsJson FROM messages WHERE attachmentsJson IS NOT NULL "
                + "ORDER BY createdAt DESC LIMIT \(capped)"
            bindings = []
        }
        guard let statement = try? stores.database.prepare(sql) else { return [] }
        if !bindings.isEmpty {
            guard (try? statement.bind(bindings)) != nil else { return [] }
        }
        var rows: [MessageRow] = []
        while (try? statement.step()) == true {
            guard let id = statement.text(at: 0) else { continue }
            rows.append(MessageRow(id: id, attachmentsJson: statement.text(at: 1)))
        }
        return rows
    }

    // MARK: - 头像

    /// 把人格头像（`/v1/media/<id>`）搬成本地文件，并写回 `personas.avatarUri`。
    @discardableResult
    public func syncPersonaAvatars(limit: Int = ServerMediaSyncService.defaultItemLimit) async -> ServerMediaSyncReport {
        var report = ServerMediaSyncReport()
        let personas = ((try? stores.personas.all(orderBy: "updatedAt DESC")) ?? [])
            .prefix(max(1, min(limit, 200)))
        for persona in personas {
            guard let uri = persona.avatarUri,
                  let mediaId = ServerMediaReference.mediaId(from: uri) else { continue }
            let kind = ServerMediaLocalKind.avatar
            if let existing = reusableFile(mediaId: mediaId, kind: kind) {
                report.reused += 1
                if var updated = (try? stores.personas.byId(persona.id)) ?? nil {
                    updated.avatarUri = existing.absoluteString
                    if (try? stores.personas.upsert(updated)) != nil {
                        report.avatarsUpdated += 1
                    }
                }
                continue
            }
            do {
                let download = try await downloader.mediaDownload(mediaId: mediaId)
                let data = download.data
                // 头像一定是图片：按文件头认格式，落盘扩展名才不会与字节不一致
                //（一张 PNG 存成 .jpg 在部分系统版本上会让解码器走错分支）。
                let sniffedMime = ServerMediaSniffer.sniff(data)?.mime
                let responseMime = download.mime?.lowercased()
                let mime = sniffedMime
                    ?? (responseMime?.hasPrefix("image/") == true ? responseMime : nil)
                    ?? "image/jpeg"
                let expected = ServerMediaHash.normalized(download.sha256)
                    ?? mediaMeta?.meta(mediaId: mediaId)?.sha256
                let stored = try localStore.storeVerified(
                    data: data, mediaId: mediaId, kind: kind,
                    mime: mime, expectedSha256: expected
                )
                hashStore?.record(mediaId: mediaId, sha256: stored.sha256)
                report.downloaded += 1
                if var updated = (try? stores.personas.byId(persona.id)) ?? nil {
                    updated.avatarUri = stored.url.absoluteString
                    if (try? stores.personas.upsert(updated)) != nil {
                        report.avatarsUpdated += 1
                    }
                }
            } catch {
                report.failed += 1
                log("[media] 头像下载失败（\(mediaId)）：\(ServerSyncService.describe(error))")
            }
        }
        if !report.isEmpty { log("[media] 头像落地：\(report.describe)") }
        return report
    }

    // MARK: - 表情

    /// 把表情收藏（`/v1/media/<id>`）搬成本地文件，并写回 `stickers.file`。
    @discardableResult
    public func syncStickers(limit: Int = ServerMediaSyncService.defaultItemLimit) async -> ServerMediaSyncReport {
        var report = ServerMediaSyncReport()
        let stickers = (try? stores.stickers.allStickers()) ?? []
        for sticker in stickers.prefix(max(1, min(limit, 200))) {
            guard let mediaId = ServerMediaReference.mediaId(from: sticker.file) else { continue }
            let kind = ServerMediaLocalKind.sticker
            if let existing = reusableFile(mediaId: mediaId, kind: kind) {
                report.reused += 1
                if updateStickerFile(sticker, file: existing.path) { report.stickersUpdated += 1 }
                continue
            }
            do {
                let download = try await downloader.mediaDownload(mediaId: mediaId)
                let data = download.data
                let declared = sticker.mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                let mime = (declared.isEmpty ? nil : declared)
                    ?? ServerMediaSniffer.sniff(data)?.mime
                    ?? download.mime?.lowercased()
                    ?? "image/gif"
                let expected = ServerMediaHash.normalized(download.sha256)
                    ?? mediaMeta?.meta(mediaId: mediaId)?.sha256
                let stored = try localStore.storeVerified(
                    data: data, mediaId: mediaId, kind: kind,
                    mime: mime, expectedSha256: expected
                )
                hashStore?.record(mediaId: mediaId, sha256: stored.sha256)
                report.downloaded += 1
                if updateStickerFile(sticker, file: stored.url.path) { report.stickersUpdated += 1 }
            } catch {
                report.failed += 1
                log("[media] 表情下载失败（\(mediaId)）：\(ServerSyncService.describe(error))")
            }
        }
        if !report.isEmpty { log("[media] 表情落地：\(report.describe)") }
        return report
    }

    private func updateStickerFile(_ sticker: StickerEntity, file: String) -> Bool {
        guard var current = (try? stores.stickers.byId(sticker.id)) ?? nil else { return false }
        current.file = file
        return (try? stores.stickers.upsert(current)) != nil
    }

    // MARK: - 一轮全做

    /// 一次把最近的消息附件、头像、表情都搬一遍（连上服务端之后跑一次）。
    @discardableResult
    public func syncAll(limit: Int = ServerMediaSyncService.defaultMessageLimit) async -> ServerMediaSyncReport {
        var total = ServerMediaSyncReport()
        let messages = await syncMessageAttachments(limit: limit)
        let avatars = await syncPersonaAvatars()
        let stickers = await syncStickers()
        for report in [messages, avatars, stickers] {
            total.downloaded += report.downloaded
            total.reused += report.reused
            total.failed += report.failed
            total.messagesUpdated += report.messagesUpdated
            total.avatarsUpdated += report.avatarsUpdated
            total.stickersUpdated += report.stickersUpdated
        }
        return total
    }
}
