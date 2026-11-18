import Foundation

// ---------------------------------------------------------------------------
// 表情包 / 日记板的服务端端点（0.13）。
//
// 与 `ServerAPIClient+CRUD.swift`（人格/记忆/预设/设置/备份）同一层：只做
// 「路径 + 请求体 + 响应解析」，不做任何本地缓存决策。
//
// 契约（与 `agentd` 逐字对齐，改字段要两端一起改）：
// - `GET /v1/stickers?personaId=&limit=`  → `{"stickers":[…]}`，`limit` 1..100，默认 30
// - `POST /v1/stickers`                   → 元数据 + base64（或 `mediaId`），回 `{"created","sticker"}`
// - `DELETE /v1/stickers/{id}`            → 404 = 已经没了（由服务层决定当不当成功）
// - `GET /v1/personas/{id}/diary?limit=&since=` → `{"notes":[…]}`
// - `POST /v1/personas/{id}/diary`        → 不带 id 新建、带 id 更新，回 `{"note"}`
// - `DELETE /v1/personas/{id}/diary/{noteId}`
// ---------------------------------------------------------------------------

public extension ServerAPIClient {

    /// 服务端 `limit` 上限（与 `agentd` 的 `MAX_STICKER_LIMIT` / `MAX_DIARY_LIMIT` 一致）。
    static let maxStickerListLimit = 100
    static let maxDiaryListLimit = 50

    /// `GET /v1/stickers`。
    func stickerRows(personaId: String? = nil, limit: Int = ServerAPIClient.maxStickerListLimit) async throws -> [ServerStickerRow] {
        var query = [
            URLQueryItem(name: "limit", value: String(min(max(limit, 1), Self.maxStickerListLimit)))
        ]
        if let personaId, !personaId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            query.append(URLQueryItem(name: "personaId", value: personaId))
        }
        let response = try await get(path: "/stickers", query: query, as: ServerStickerListResponse.self)
        return response.stickers
    }

    /// `POST /v1/stickers`：一条请求带元数据 + base64（或先传 `/v1/media` 再带 `mediaId`）。
    ///
    /// 返回服务端分配 id 的整行；响应里没有 `sticker` 时返回 nil（调用方按失败处理）。
    func createSticker(_ body: [String: JSONValue]) async throws -> ServerStickerRow? {
        let response = try await post(path: "/stickers", body: body, as: ServerStickerCreatedResponse.self)
        return response.sticker
    }

    /// `POST /v1/media?kind=STICKER`：大表情先传原始字节，返回媒体 id。
    ///
    /// 为什么需要：`POST /v1/stickers` 的 inline base64 有约 192 KiB 的原文件上限
    ///（服务端 JSON 单字符串 256 KiB），而表情本身可以到 512 KiB。
    func uploadStickerMedia(data: Data, personaId: String?) async throws -> String {
        let media = try await uploadSticker(bytes: data, personaId: personaId)
        if let id = ServerMediaReference.mediaId(from: media.url) { return id }
        let id = media.id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidResponse, message: "服务端没有返回媒体 id")
        }
        return id
    }

    /// `DELETE /v1/stickers/{id}`：404 由调用方当「已经没了」。
    func deleteSticker(id: String) async throws {
        let id = try Self.catalogPathId(id, field: "stickerId")
        _ = try await delete(path: "/stickers/\(Self.writePathSegment(id))")
    }

    /// `GET /v1/personas/{id}/diary`。
    func diaryRows(
        personaId: String,
        limit: Int = ServerAPIClient.maxDiaryListLimit,
        since: Int64 = 0
    ) async throws -> [ServerDiaryRow] {
        let id = try Self.catalogPathId(personaId, field: "personaId")
        var query = [
            URLQueryItem(name: "limit", value: String(min(max(limit, 1), Self.maxDiaryListLimit)))
        ]
        if since > 0 { query.append(URLQueryItem(name: "since", value: String(since))) }
        let response = try await get(
            path: "/personas/\(Self.writePathSegment(id))/diary",
            query: query,
            as: ServerDiaryListResponse.self
        )
        return response.notes
    }

    /// `POST /v1/personas/{id}/diary`：不带 `id` 新建、带 `id` 更新。
    func saveDiary(personaId: String, body: [String: JSONValue]) async throws -> ServerDiaryRow? {
        let id = try Self.catalogPathId(personaId, field: "personaId")
        let response = try await post(
            path: "/personas/\(Self.writePathSegment(id))/diary",
            body: body,
            as: ServerDiarySavedResponse.self
        )
        return response.note
    }

    /// `DELETE /v1/personas/{id}/diary/{noteId}`。
    func deleteDiary(personaId: String, noteId: String) async throws {
        let persona = try Self.catalogPathId(personaId, field: "personaId")
        let note = try Self.catalogPathId(noteId, field: "noteId")
        _ = try await delete(
            path: "/personas/\(Self.writePathSegment(persona))/diary/\(Self.writePathSegment(note))"
        )
    }

    /// 路径 id 守卫：空 / 带路径分隔符 / 超长一律拒绝（与 CRUD 那批同一套规则）。
    internal static func catalogPathId(_ raw: String, field: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              trimmed.count <= 64,
              !trimmed.contains("/"),
              !trimmed.contains("\\"),
              trimmed != ".",
              trimmed != ".." else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "缺少有效的 \(field)")
        }
        return trimmed
    }
}
