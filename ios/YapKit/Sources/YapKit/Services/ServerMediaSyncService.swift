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

/// 媒体下载口（生产用 `ServerAPIClient.mediaData(mediaId:)`；测试注入假实现）。
public protocol ServerMediaDownloading: AnyObject {
    func mediaData(mediaId: String) async throws -> Data
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
    private let log: (String) -> Void

    public init(
        downloader: any ServerMediaDownloading,
        stores: DatabaseStores,
        localStore: ServerMediaLocalStore = .shared,
        mediaMeta: ServerMediaMetaStore? = nil,
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.downloader = downloader
        self.stores = stores
        self.localStore = localStore
        self.mediaMeta = mediaMeta ?? ServerMediaMetaStore(database: stores.database)
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

    /// 一条服务端附件 → 本地附件（下载 + 落盘）。失败返回 nil（调用方保留原引用）。
    private func resolve(
        attachment: MessageAttachment,
        mediaId: String,
        report: inout ServerMediaSyncReport
    ) async -> MessageAttachment? {
        let kind = ServerMediaLocalKind.kind(for: attachment)
        if let existing = localStore.existingFile(mediaId: mediaId, kind: kind) {
            report.reused += 1
            return localStore.localAttachment(
                for: attachment, mediaId: mediaId, kind: kind, fileURL: existing
            )
        }
        do {
            let data = try await downloader.mediaData(mediaId: mediaId)
            // 先按文件头认一次：别的设备上传的语音/视频在这台手机上没有任何元信息，
            // 只按「没有 kind 就当图片」会让它落错目录（详见 `ServerMediaSniffer`）。
            let sniffed = ServerMediaSniffer.sniff(data)
            let resolvedKind = Self.resolveKind(
                attachment: attachment, sniffed: sniffed, fallback: kind
            )
            let resolvedMime = Self.resolveMime(attachment: attachment, sniffed: sniffed)
            let url = try localStore.store(
                data: data, mediaId: mediaId, kind: resolvedKind, mime: resolvedMime
            )
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
                for: enriched, mediaId: mediaId, kind: resolvedKind, fileURL: url
            )
        } catch {
            // 失败只计数（含 404：服务端那边删了，本机就保持「占位」）。
            report.failed += 1
            log("[media] 下载失败（\(mediaId)）：\(ServerSyncService.describe(error))")
            return nil
        }
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
        sniffed: ServerMediaSniffed?
    ) -> String {
        guard let sniffed else { return attachment.mime }
        let declared = attachment.mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if declared.isEmpty || declared == MessageAttachment.defaultMime { return sniffed.mime }
        let declaredKind = ServerMediaLocalKind.kind(for: MessageAttachment(uri: "", mime: declared))
        if declaredKind != sniffed.kind { return sniffed.mime }
        return declared
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
            if let existing = localStore.existingFile(mediaId: mediaId, kind: kind) {
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
                let data = try await downloader.mediaData(mediaId: mediaId)
                // 头像一定是图片：按文件头认格式，落盘扩展名才不会与字节不一致
                //（一张 PNG 存成 .jpg 在部分系统版本上会让解码器走错分支）。
                let mime = ServerMediaSniffer.sniff(data)?.mime ?? "image/jpeg"
                let url = try localStore.store(
                    data: data, mediaId: mediaId, kind: kind, mime: mime
                )
                report.downloaded += 1
                if var updated = (try? stores.personas.byId(persona.id)) ?? nil {
                    updated.avatarUri = url.absoluteString
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
            if let existing = localStore.existingFile(mediaId: mediaId, kind: kind) {
                report.reused += 1
                if updateStickerFile(sticker, file: existing.path) { report.stickersUpdated += 1 }
                continue
            }
            do {
                let data = try await downloader.mediaData(mediaId: mediaId)
                let declared = sticker.mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                let mime = (declared.isEmpty ? nil : declared)
                    ?? ServerMediaSniffer.sniff(data)?.mime
                    ?? "image/gif"
                let url = try localStore.store(
                    data: data, mediaId: mediaId, kind: kind, mime: mime
                )
                report.downloaded += 1
                if updateStickerFile(sticker, file: url.path) { report.stickersUpdated += 1 }
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
