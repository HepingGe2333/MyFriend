import Foundation

// ---------------------------------------------------------------------------
// 服务端表情包 / 日记板的**拉取缓存与写路径编排**（0.13）。
//
// 分工与 Android `ServerCatalogSync` 一致：
// - 写（新增/删除）先过服务端，成功了才写本机缓存；
// - 读（列表）把服务端列表同步进本机数据库，界面只读本机；
// - 表情字节经 `/v1/media/<id>` 落进 `Application Support/stickers`（离线可用、发送不必先下载），
//   日记只同步行（文本，没有媒体）。
//
// 拉取失败**不能影响**聊天同步：都吞成 0 并留一行日志，等下一轮前台重试。
// 不删除服务端已经删掉的本机行：本机可能还留着本地模式时期的表情/日记，一次「列表对齐」
// 把它们清掉是数据丢失；删除只由用户操作触发。
// ---------------------------------------------------------------------------

/// 表情写入服务端的最小口（`StickerService` 只需要这两件事）。
///
/// 单独抽协议而不是把整个 `ServerCatalogService` 塞进去：表情领域不该知道日记/媒体同步，
/// 测试也能用两行假实现钉住「服务端模式下新建/删除真的走了服务端」。
public protocol StickerServerCatalog: AnyObject {
    func createSticker(
        data: Data, width: Int, height: Int, frames: Int,
        source: String, createdBy: String, personaId: String?
    ) async throws -> ServerStickerRow
    func deleteSticker(id: String) async throws
}

public final class ServerCatalogService: StickerServerCatalog {

    /// 取一个可用的 API（服务端模式关掉时返回 nil）；用闭包是因为模式可运行中切换。
    public typealias APIProvider = () -> ServerCatalogAPI?

    private let stores: DatabaseStores
    private let provider: APIProvider
    private let mediaStore: ServerMediaLocalStore
    private let now: () -> Int64
    private let log: (String) -> Void

    public init(
        stores: DatabaseStores,
        provider: @escaping APIProvider,
        mediaStore: ServerMediaLocalStore = .shared,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.provider = provider
        self.mediaStore = mediaStore
        self.now = now
        self.log = log
    }

    // MARK: - 拉取（读）

    /// 拉取服务端表情列表并写进本机缓存。
    ///
    /// - Returns: 写进缓存的行数；不是服务端模式 / 拉取失败时为 0。
    @discardableResult
    public func syncStickers(limit: Int = ServerAPIClient.maxStickerListLimit) async -> Int {
        guard let api = provider() else { return 0 }
        let rows: [ServerStickerRow]
        do {
            rows = try await api.stickerRows(personaId: nil, limit: limit)
        } catch is CancellationError {
            return 0
        } catch {
            log("[catalog] 表情拉取失败：\(error.localizedDescription)")
            return 0
        }
        var written = 0
        for row in rows {
            guard let entity = await localSticker(for: row, api: api) else { continue }
            do {
                try stores.stickers.upsert(entity)
                written += 1
            } catch {
                log("[catalog] 表情缓存写入失败：\(error.localizedDescription)")
            }
        }
        return written
    }

    /// 拉取这些本机人格在服务端上的日记行，写进本机缓存。
    ///
    /// - Parameter personaIds: 本机人格 id（服务端 id 由调用方先映射，见 `serverPersonaId` 闭包）。
    /// - Returns: 写进缓存的行数；不是服务端模式 / 拉取失败时为 0。
    @discardableResult
    public func syncDiary(personaIds: [(localId: String, serverId: String)]) async -> Int {
        guard let api = provider() else { return 0 }
        if personaIds.isEmpty { return 0 }
        var written = 0
        for persona in personaIds {
            let rows: [ServerDiaryRow]
            do {
                rows = try await api.diaryRows(
                    personaId: persona.serverId,
                    limit: ServerAPIClient.maxDiaryListLimit,
                    since: 0
                )
            } catch is CancellationError {
                return written
            } catch {
                log("[catalog] 日记拉取失败：\(error.localizedDescription)")
                continue
            }
            for row in rows {
                guard var note = Self.diaryEntity(
                    row: row,
                    existing: try? stores.diaryNotes.byId(row.id),
                    fallbackPersonaId: persona.localId
                ) else { continue }
                // 本机缓存一律用**本地**人格 id：服务端人格 id 与本地不同（bootstrap 之后），
                // 存服务端 id 会让「按当前人格查日记」查不到。
                note.personaId = persona.localId
                do {
                    try stores.diaryNotes.upsert(note)
                    written += 1
                } catch {
                    log("[catalog] 日记缓存写入失败：\(error.localizedDescription)")
                }
            }
        }
        return written
    }

    // MARK: - 写（新增/删除）

    /// 新建一个服务端表情（base64 一条请求），返回服务端那一行。
    ///
    /// 失败原样抛出：调用方（表情导入/制作/收藏）必须让用户知道「没存下来」，
    /// 不能悄悄只存本机 —— 否则下一次同步它就不见了。
    public func createSticker(
        data: Data,
        width: Int,
        height: Int,
        frames: Int,
        source: String,
        createdBy: String,
        personaId: String?
    ) async throws -> ServerStickerRow {
        guard let api = provider() else { throw ServerAPIError(code: "server_unavailable", message: "服务端没有连上") }
        var body: [String: JSONValue] = [
            "mime": .string("image/gif"),
            "width": .int(Int64(max(width, 0))),
            "height": .int(Int64(max(height, 0))),
            "frames": .int(Int64(max(frames, 1))),
            "source": .string(source),
            "createdBy": .string(createdBy)
        ]
        // inline base64 有约 192 KiB 的原文件上限（服务端 JSON 单字符串 256 KiB）；
        // 更大的表情先 `POST /v1/media?kind=STICKER`，再用 mediaId 建行。
        if data.count > Self.inlineBase64LimitBytes {
            let mediaId = try await api.uploadStickerMedia(data: data, personaId: personaId)
            body["mediaId"] = .string(mediaId)
        } else {
            body["data"] = .string(data.base64EncodedString())
            body["bytes"] = .int(Int64(data.count))
        }
        if let personaId, !personaId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            body["personaId"] = .string(personaId)
        }
        guard let row = try await api.createSticker(body), !row.localId.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidResponse, message: "服务端没有返回表情")
        }
        return row
    }

    /// 删除一个服务端表情；404 视为已经没了（本机缓存也跟着消失）。
    /// inline base64 的原文件上限（保守取 180 KiB；服务端最多约 192 KiB）。
    public static let inlineBase64LimitBytes = 180 * 1024

    public func deleteSticker(id: String) async throws {
        guard let api = provider() else { throw ServerAPIError(code: "server_unavailable", message: "服务端没有连上") }
        do {
            try await api.deleteSticker(id: id)
        } catch let error as ServerAPIError where error.status == 404 || error.code == "not_found" {
            return
        }
    }

    /// 新建一条服务端日记；返回服务端那一行（含服务端分配 id 与时间）。
    public func saveDiary(
        personaId: String,
        serverPersonaId: String,
        content: String,
        remindAt: Int64?
    ) async throws -> ServerDiaryRow {
        guard let api = provider() else { throw ServerAPIError(code: "server_unavailable", message: "服务端没有连上") }
        var body: [String: JSONValue] = ["content": .string(content)]
        body["remindAt"] = remindAt.map { .int($0) } ?? .null
        guard let row = try await api.saveDiary(personaId: serverPersonaId, body: body), !row.id.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidResponse, message: "服务端没有返回日记")
        }
        return row
    }

    /// 删除一条服务端日记；404 视为已经没了。
    public func deleteDiary(serverPersonaId: String, noteId: String) async throws {
        guard let api = provider() else { throw ServerAPIError(code: "server_unavailable", message: "服务端没有连上") }
        do {
            try await api.deleteDiary(personaId: serverPersonaId, noteId: noteId)
        } catch let error as ServerAPIError where error.status == 404 || error.code == "not_found" {
            return
        }
    }

    // MARK: - 内部：行 → 本机实体

    /// 表情行 → 本机行；字节能落盘就指向本地文件，失败保留 `/v1/media/<id>`（渲染按需下载）。
    private func localSticker(for row: ServerStickerRow, api: ServerCatalogAPI) async -> StickerEntity? {
        let id = row.localId
        guard !id.isEmpty else { return nil }
        var file = row.mediaRef ?? row.file
        if let ref = row.mediaRef, let mediaId = ServerMediaReference.mediaId(from: ref) {
            if let existing = mediaStore.existingFile(mediaId: mediaId, kind: .sticker) {
                file = existing.path
            } else if let data = try? await api.mediaData(mediaId: mediaId) {
                if let url = try? mediaStore.store(
                    data: data, mediaId: mediaId, kind: .sticker, mime: row.mime
                ) {
                    file = url.path
                }
            }
        }
        guard !file.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return StickerEntity(
            id: id,
            ownerUserId: row.ownerUserId,
            personaId: row.personaId,
            file: file,
            mime: row.mime.isEmpty ? "image/gif" : row.mime,
            width: max(row.width, 0),
            height: max(row.height, 0),
            frames: max(row.frames, 1),
            bytes: max(row.bytes, 0),
            source: row.source.isEmpty ? "IMPORTED" : row.source,
            createdBy: row.createdBy.isEmpty ? "USER" : row.createdBy,
            createdAt: row.createdAt > 0 ? row.createdAt : now()
        )
    }

    /// 日记行 → 本机行（纯函数，单测直接调）。
    ///
    /// 规则：没有 id / 没有内容（且没有既有缓存）就丢掉；`personaId` 缺失时用本机人格兜底；
    /// 时间缺失时沿用既有缓存（不能写成 0，否则列表排序会乱）。
    public static func diaryEntity(
        row: ServerDiaryRow,
        existing: DiaryNoteEntity?,
        fallbackPersonaId: String
    ) -> DiaryNoteEntity? {
        let id = row.id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return nil }
        let content = row.content.isEmpty ? (existing?.content ?? "") : row.content
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let personaId = row.personaId.trimmingCharacters(in: .whitespacesAndNewlines)
        return DiaryNoteEntity(
            id: id,
            personaId: personaId.isEmpty ? (existing?.personaId ?? fallbackPersonaId) : personaId,
            content: content,
            createdAt: row.createdAt > 0 ? row.createdAt : (existing?.createdAt ?? 0),
            remindAt: row.remindAt,
            remindedAt: row.remindedAt,
            updatedAt: row.updatedAt > 0 ? row.updatedAt : (existing?.updatedAt ?? 0)
        )
    }

    /// 表情行 → 本机行（纯函数，单测直接调；不带字节落盘）。
    ///
    /// `localFile` 为 nil 时保留服务端引用（渲染层按需下载）。
    public static func stickerEntity(
        row: ServerStickerRow,
        localFile: String?,
        now: Int64
    ) -> StickerEntity? {
        let id = row.localId
        guard !id.isEmpty else { return nil }
        guard let file = localFile ?? row.mediaRef ?? (row.file.isEmpty ? nil : row.file) else { return nil }
        if file.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
        return StickerEntity(
            id: id,
            ownerUserId: row.ownerUserId,
            personaId: row.personaId,
            file: file,
            mime: row.mime.isEmpty ? "image/gif" : row.mime,
            width: max(row.width, 0),
            height: max(row.height, 0),
            frames: max(row.frames, 1),
            bytes: max(row.bytes, 0),
            source: row.source.isEmpty ? "IMPORTED" : row.source,
            createdBy: row.createdBy.isEmpty ? "USER" : row.createdBy,
            createdAt: row.createdAt > 0 ? row.createdAt : now
        )
    }
}
