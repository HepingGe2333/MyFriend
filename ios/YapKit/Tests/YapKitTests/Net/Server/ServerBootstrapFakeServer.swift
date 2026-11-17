import Foundation
@testable import YapKit

/// bootstrap 测试用的**内存假服务端**：按 agentd 0.12.1 的契约回响应。
///
/// 为什么不逐个预置响应（`ServerURLProtocolStub` 的顺序队列）：bootstrap 的请求数量与顺序
/// 随数据变化（要不要建人格/会话、每个会话几块消息……），预置响应既写不准、也看不出
/// 「跑完以后两边到底对不对」。这里让假服务端像真的那样**按请求算状态**，测试只断言
/// 最终状态与计数（契约测试的正是这些）。
///
/// 刻意只实现 bootstrap/增量同步会碰到的那几个端点；未登记路由由 `ServerRouteStub` 501。
final class BootstrapFakeServer {

    // MARK: - 数据

    struct Persona {
        var id: String
        var name: String
        var description: String = ""
        var isDefault: Bool = false
        var createdAt: Int64 = 1
        var updatedAt: Int64 = 1
        var avatarUrl: String?

        var json: [String: Any] {
            var object: [String: Any] = [
                "id": id,
                "name": name,
                "nameEn": "",
                "description": description,
                "isDefault": isDefault,
                "createdAt": createdAt,
                "updatedAt": updatedAt
            ]
            if let avatarUrl { object["avatarUrl"] = avatarUrl }
            return object
        }
    }

    struct Session {
        var id: String
        var personaId: String
        var title: String = ""
        var createdAt: Int64 = 1
        var updatedAt: Int64 = 1
        var unread: Int = 0
        /// 测试用：故意让服务端声称更多/更少的消息（校验失败路径）。
        var messageCountOverride: Int64?
    }

    struct Message {
        var id: String
        var sessionId: String
        var role: String
        var content: String
        var createdAt: Int64
        var readAt: Int64?
        var attachments: [String] = []
        var source: String = "IMPORT"

        var json: [String: Any] {
            var object: [String: Any] = [
                "id": id,
                "sessionId": sessionId,
                "blockIndex": 0,
                "role": role,
                "content": content,
                "createdAt": createdAt,
                "attachments": attachments,
                "source": source,
                "clientMessageId": NSNull(),
                "replyTo": NSNull(),
                "promptTokens": NSNull(),
                "completionTokens": NSNull()
            ]
            object["readAt"] = readAt.map { $0 as Any } ?? NSNull()
            return object
        }
    }

    /// 一块 200 条（与契约一致：`limitBlocks=1` 一页一块）。
    static let blockSize = 200

    var personas: [Persona] = []
    var sessions: [Session] = []
    /// 会话 id → 消息（按写入顺序升序）。
    var messages: [String: [Message]] = [:]
    var presets: [[String: Any]] = []
    /// 人格 id → 记忆对象。
    var memories: [String: [[String: Any]]] = [:]
    var settings: [String: Any] = [:]
    var uploadedMedia: [(id: String, kind: String, bytes: Int)] = []
    /// 已经上传过的媒体 id（`validateAttachments` 打开时 import 会检查引用存在）。
    var knownMediaIds: Set<String> = []
    /// import 时是否校验附件引用（模拟 agentd 的 `unknown_media`）。
    var validateAttachments = false
    /// 接下来多少次 `GET /v1/state` 返回 500（测全量下载失败路径）。
    var failStateTimes = 0

    /// 服务端游标（任何写都让它前进）。
    var cursor: Int64 = 100

    // ---- 故障注入

    /// 「本地附件在服务端不存在」：带附件的 import 整批 400（契约里就是要整批拒）。
    var rejectUnknownMedia = false
    /// 接下来多少次 import 返回 500（测重试与断点续跑）。
    var failImportTimes = 0
    /// `POST /v1/media` 是否失败。
    var failMediaUpload = false

    // ---- 记录的请求（断言契约）

    /// 每一次 import 的 messages 数组（顺序即调用顺序）。
    var importRequests: [[[String: Any]]] = []
    var importedTotal = 0
    var skippedTotal = 0

    private var nextId = 1
    /// 变更日志（游标 → 消息），给 `GET /v1/state?since=` 用。
    private var messageLog: [(seq: Int64, message: Message)] = []

    // MARK: - 造数据

    @discardableResult
    func seedPersona(
        id: String,
        name: String,
        description: String = "",
        isDefault: Bool = false,
        updatedAt: Int64 = 1
    ) -> String {
        personas.append(Persona(
            id: id, name: name, description: description, isDefault: isDefault,
            createdAt: 1, updatedAt: updatedAt
        ))
        return id
    }

    @discardableResult
    func seedSession(
        id: String,
        personaId: String,
        title: String = "",
        unread: Int = 0
    ) -> String {
        sessions.append(Session(id: id, personaId: personaId, title: title, unread: unread))
        return id
    }

    /// 追加消息（`startAt` 起每条 +1 毫秒）。
    ///
    /// - Parameter readCount: 前多少条带 `readAt`（服务端权威已读），其余 null。
    @discardableResult
    func seedMessages(
        sessionId: String,
        count: Int,
        startAt: Int64 = 1_000,
        readCount: Int = 0
    ) -> [String] {
        var ids: [String] = []
        for index in 0..<count {
            let id = "srv-\(sessionId)-\(index)"
            let message = Message(
                id: id,
                sessionId: sessionId,
                role: index % 2 == 0 ? "USER" : "ASSISTANT",
                content: "第 \(index + 1) 句",
                createdAt: startAt + Int64(index),
                readAt: index < readCount ? startAt + Int64(index) + 5 : nil
            )
            record(message, sessionId: sessionId)
            ids.append(id)
        }
        return ids
    }

    /// 追加一条消息（模拟「另一边刚说了话」），顺带推进游标与变更日志。
    @discardableResult
    func appendMessage(
        sessionId: String,
        content: String,
        createdAt: Int64,
        role: String = "ASSISTANT"
    ) -> String {
        let message = Message(
            id: "srv-new-\(nextId)",
            sessionId: sessionId,
            role: role,
            content: content,
            createdAt: createdAt
        )
        nextId += 1
        record(message, sessionId: sessionId)
        return message.id
    }

    /// 追加一条带附件的消息（历史媒体/媒体引用校验用）。
    @discardableResult
    func seedMessage(
        sessionId: String,
        id: String,
        content: String,
        createdAt: Int64,
        role: String = "USER",
        attachments: [String] = []
    ) -> String {
        let message = Message(
            id: id,
            sessionId: sessionId,
            role: role,
            content: content,
            createdAt: createdAt,
            attachments: attachments
        )
        record(message, sessionId: sessionId)
        return message.id
    }

    private func record(_ message: Message, sessionId: String) {
        messages[sessionId, default: []].append(message)
        cursor += 1
        // 每一条消息都有一条递增的变更序号：`/v1/state?since=` 用它做「游标之后」。
        messageLog.append((seq: cursor, message: message))
    }

    func setSettings(_ values: [String: Any]) {
        for (key, value) in values { settings[key] = value }
    }

    // MARK: - 块

    /// 某个会话的块数（没有消息 = 0）。
    func blockCount(sessionId: String) -> Int {
        let count = messages[sessionId]?.count ?? 0
        return (count + Self.blockSize - 1) / Self.blockSize
    }

    func lastBlockIndex(sessionId: String) -> Int? {
        let count = blockCount(sessionId: sessionId)
        return count == 0 ? nil : count - 1
    }

    func block(sessionId: String, index: Int) -> [Message] {
        let all = messages[sessionId] ?? []
        let start = index * Self.blockSize
        guard start < all.count, index >= 0 else { return [] }
        let end = min(start + Self.blockSize, all.count)
        return Array(all[start..<end])
    }

    // MARK: - 路由

    /// 把假服务端接到 `ServerRouteStub` 上（每个测试 `ServerRouteStub.reset()` 之后调一次）。
    func registerRoutes() {
        ServerRouteStub.route(method: "GET", prefix: "/v1/state") { [unowned self] request in
            if self.failStateTimes > 0 {
                self.failStateTimes -= 1
                return .error("服务端临时故障", code: "internal", status: 500)
            }
            return .json(self.stateJSON(since: Int64(request.query["since"] ?? "0") ?? 0))
        }

        ServerRouteStub.route(method: "GET", prefix: "/v1/settings") { [unowned self] _ in
            .json(["settings": self.settings])
        }
        ServerRouteStub.route(method: "POST", prefix: "/v1/settings") { [unowned self] request in
            self.applySettings(request.jsonObject ?? [:])
        }

        ServerRouteStub.route(method: "GET", prefix: "/v1/presets") { [unowned self] _ in
            .json(["presets": self.presets])
        }
        ServerRouteStub.route(method: "POST", prefix: "/v1/presets") { [unowned self] request in
            self.savePreset(request.jsonObject ?? [:])
        }

        ServerRouteStub.route(method: "GET", prefix: "/v1/personas/", suffix: "/memories") { [unowned self] request in
            let personaId = Self.personaId(fromMemoriesPath: request.path)
            return .json(["memories": self.memories[personaId] ?? []])
        }
        ServerRouteStub.route(method: "POST", prefix: "/v1/personas/", suffix: "/memories") { [unowned self] request in
            let personaId = Self.personaId(fromMemoriesPath: request.path)
            return self.saveMemory(personaId: personaId, body: request.jsonObject ?? [:])
        }
        ServerRouteStub.route(method: "POST", prefix: "/v1/personas") { [unowned self] request in
            self.savePersona(request.jsonObject ?? [:])
        }

        ServerRouteStub.route(method: "POST", prefix: "/v1/sessions/", suffix: "/messages/import") { [unowned self] request in
            let sessionId = Self.sessionId(fromMessagesPath: request.path, suffix: "/messages/import")
            return self.importMessages(sessionId: sessionId, body: request.jsonObject ?? [:])
        }
        ServerRouteStub.route(method: "GET", prefix: "/v1/sessions/", suffix: "/messages") { [unowned self] request in
            let sessionId = Self.sessionId(fromMessagesPath: request.path, suffix: "/messages")
            let beforeBlock = Int(request.query["beforeBlock"] ?? "").map { $0 - 1 }
            return .json(self.messagePage(sessionId: sessionId, blockIndex: beforeBlock))
        }
        ServerRouteStub.route(method: "POST", prefix: "/v1/sessions") { [unowned self] request in
            self.createSession(request.jsonObject ?? [:])
        }

        ServerRouteStub.route(method: "POST", prefix: "/v1/media") { [unowned self] request in
            self.uploadMedia(request)
        }
    }

    // MARK: - 端点实现

    private func stateJSON(since: Int64) -> [String: Any] {
        var messageList: [[String: Any]] = []
        var hasMoreMessages = false
        if since == 0 {
            // 首次快照：每个会话只给**最新一块**（契约 §3.4）。
            for session in sessions {
                guard let last = lastBlockIndex(sessionId: session.id) else { continue }
                if last > 0 { hasMoreMessages = true }
                for message in block(sessionId: session.id, index: last) {
                    var object = message.json
                    object["blockIndex"] = last
                    messageList.append(object)
                }
            }
        } else {
            // 增量：游标之后的变更（模拟 agentd 的 events/state since 语义）。
            for entry in messageLog where entry.seq > since {
                var object = entry.message.json
                object["blockIndex"] = blockIndex(of: entry.message)
                messageList.append(object)
            }
        }
        let sessionList = sessions.map { session -> [String: Any] in
            let lastBlock = lastBlockIndex(sessionId: session.id)
            let lastMessageAt = messages[session.id]?.last?.createdAt
            return [
                "id": session.id,
                "personaId": session.personaId,
                "title": session.title,
                "createdAt": session.createdAt,
                "updatedAt": session.updatedAt,
                "lastMessageAt": lastMessageAt.map { $0 as Any } ?? NSNull(),
                "lastBlockIndex": lastBlock ?? -1,
                "unread": session.unread,
                "messageCount": session.messageCountOverride ?? Int64(messages[session.id]?.count ?? 0)
            ]
        }
        return [
            "serverTime": 1_800_000_000_000 as Any,
            "personas": personas.map(\.json),
            "sessions": sessionList,
            "messages": messageList,
            "mood": NSNull(),
            "presence": NSNull(),
            "serverCursor": cursor,
            "hasMoreMessages": hasMoreMessages
        ]
    }

    private func blockIndex(of message: Message) -> Int {
        let all = messages[message.sessionId] ?? []
        guard let index = all.firstIndex(where: { $0.id == message.id }) else { return 0 }
        return index / Self.blockSize
    }

    private func messagePage(sessionId: String, blockIndex: Int?) -> [String: Any] {
        let count = blockCount(sessionId: sessionId)
        guard count > 0 else {
            return [
                "sessionId": sessionId,
                "blockIndex": NSNull(),
                "hasMoreOlder": false,
                "hasMoreNewer": false,
                "messages": []
            ]
        }
        let index = blockIndex ?? (count - 1)
        let resolved = max(0, min(index, count - 1))
        let list = block(sessionId: sessionId, index: resolved).map { message -> [String: Any] in
            var object = message.json
            object["blockIndex"] = resolved
            return object
        }
        return [
            "sessionId": sessionId,
            "blockIndex": resolved,
            "hasMoreOlder": resolved > 0,
            "hasMoreNewer": resolved < count - 1,
            "messages": list
        ]
    }

    private func applySettings(_ body: [String: Any]) -> ServerRouteStub.Reply {
        // 白名单合并（与 agentd 一致）：未知键忽略；`null` = 删掉这个键。
        let whitelist: Set<String> = [
            "themeMode", "accentColor", "fontScale", "bubbleStyle",
            "animationsEnabled", "hapticsEnabled", "agentLoopEnabled",
            "autoTranslatePersona", "chatBackgroundColor", "activePersonaId",
            ServerSettingsMapping.avatarKey, ServerSettingsMapping.legacyAvatarKey
        ]
        // agentd 会校验「正在聊的人」存在：推一个它不认识的人格 id 是整条 400。
        // 假服务端也这么判，才测得出「bootstrap 推设置前要换算人格 id」。
        if let active = body["activePersonaId"] as? String, !active.isEmpty,
           !personas.contains(where: { $0.id == active }) {
            return .error("activePersonaId 指向不存在的人格", code: "invalid_argument", status: 400)
        }
        for (key, value) in body where whitelist.contains(key) {
            if value is NSNull {
                settings.removeValue(forKey: key)
            } else {
                settings[key] = value
            }
            cursor += 1
        }
        return .json(["settings": settings])
    }

    private func savePersona(_ body: [String: Any]) -> ServerRouteStub.Reply {
        guard let name = body["name"] as? String, !name.isEmpty else {
            return .error("名字不能为空", code: "invalid_argument", status: 400)
        }
        if let id = body["id"] as? String, !id.isEmpty {
            guard let index = personas.firstIndex(where: { $0.id == id }) else {
                // 与 agentd 一致：带 id = 更新，更新一个不存在的 id 是 404。
                return .error("人格不存在", code: "not_found", status: 404)
            }
            personas[index].name = name
            if let description = body["description"] as? String { personas[index].description = description }
            personas[index].updatedAt += 1
            cursor += 1
            return .json(["persona": personas[index].json])
        }
        let id = "p-\(nextId)"
        nextId += 1
        let persona = Persona(
            id: id,
            name: name,
            description: body["description"] as? String ?? "",
            isDefault: body["isDefault"] as? Bool ?? false,
            createdAt: 1,
            updatedAt: 1
        )
        personas.append(persona)
        cursor += 1
        return .json(["persona": persona.json])
    }

    private func savePreset(_ body: [String: Any]) -> ServerRouteStub.Reply {
        guard let name = body["name"] as? String, !name.isEmpty else {
            return .error("预设名字不能为空", code: "invalid_argument", status: 400)
        }
        if let id = body["id"] as? String, !id.isEmpty {
            guard let index = presets.firstIndex(where: { ($0["id"] as? String) == id }) else {
                return .error("预设不存在", code: "not_found", status: 404)
            }
            var preset = presets[index]
            preset["name"] = name
            presets[index] = preset
            cursor += 1
            return .json(["preset": preset])
        }
        let id = "pre-\(nextId)"
        nextId += 1
        var preset: [String: Any] = ["id": id, "name": name, "createdAt": 1, "updatedAt": 1]
        if let description = body["description"] as? String { preset["description"] = description }
        presets.append(preset)
        cursor += 1
        return .json(["preset": preset])
    }

    private func saveMemory(personaId: String, body: [String: Any]) -> ServerRouteStub.Reply {
        guard let content = body["content"] as? String, !content.isEmpty else {
            return .error("记忆内容不能为空", code: "invalid_argument", status: 400)
        }
        var list = memories[personaId] ?? []
        if let id = body["id"] as? String, !id.isEmpty {
            guard let index = list.firstIndex(where: { ($0["id"] as? String) == id }) else {
                return .error("记忆不存在", code: "not_found", status: 404)
            }
            list[index]["content"] = content
            memories[personaId] = list
            cursor += 1
            return .json(["memory": list[index]])
        }
        let id = "mem-\(nextId)"
        nextId += 1
        let memory: [String: Any] = [
            "id": id,
            "personaId": personaId,
            "content": content,
            "importance": Self.int64(body["importance"]).map(Int.init) ?? 60,
            "tags": body["tags"] as? [String] ?? [],
            "createdAt": 1,
            "updatedAt": 1
        ]
        list.append(memory)
        memories[personaId] = list
        cursor += 1
        return .json(["memory": memory])
    }

    private func createSession(_ body: [String: Any]) -> ServerRouteStub.Reply {
        guard let personaId = body["personaId"] as? String, !personaId.isEmpty else {
            return .error("personaId 不能为空", code: "invalid_argument", status: 400)
        }
        if let existing = sessions.first(where: { $0.personaId == personaId }) {
            return .json(["created": false, "session": Self.sessionJSON(existing, messages: messages[existing.id] ?? [])])
        }
        let id = "s-\(nextId)"
        nextId += 1
        let session = Session(
            id: id,
            personaId: personaId,
            title: body["title"] as? String ?? "",
            createdAt: cursor,
            updatedAt: cursor
        )
        sessions.append(session)
        cursor += 1
        return .json(["created": true, "session": Self.sessionJSON(session, messages: [])])
    }

    private static func sessionJSON(_ session: Session, messages: [Message]) -> [String: Any] {
        let count = Int(session.messageCountOverride ?? Int64(messages.count))
        let lastBlock = count == 0 ? -1 : (count - 1) / blockSize
        return [
            "id": session.id,
            "personaId": session.personaId,
            "title": session.title,
            "createdAt": session.createdAt,
            "updatedAt": session.updatedAt,
            "lastMessageAt": messages.last.map { $0.createdAt as Any } ?? NSNull(),
            "lastBlockIndex": lastBlock,
            "unread": session.unread,
            "messageCount": count
        ]
    }

    private func importMessages(sessionId: String, body: [String: Any]) -> ServerRouteStub.Reply {
        guard let incoming = body["messages"] as? [[String: Any]] else {
            return .error("messages 必须是数组", code: "invalid_argument", status: 400)
        }
        importRequests.append(incoming)
        if failImportTimes > 0 {
            failImportTimes -= 1
            return .error("服务端临时故障", code: "internal", status: 500)
        }
        guard incoming.count <= Self.blockSize else {
            return .error("一次最多 200 条", code: "invalid_argument", status: 400)
        }
        // 一批是原子单位（契约）：先把整批校验完，任何一条不合格就整批 400、一条都不写。
        var validated: [(id: String, role: String, content: String, createdAt: Int64, readAt: Int64?, attachments: [String], source: String)] = []
        for object in incoming {
            guard let id = object["id"] as? String, !id.isEmpty,
                  let role = object["role"] as? String,
                  ["USER", "ASSISTANT", "SYSTEM"].contains(role),
                  object["content"] is String,
                  let createdAt = Self.int64(object["createdAt"])
            else {
                return .error("消息字段不合法", code: "invalid_argument", status: 400)
            }
            let attachments = object["attachments"] as? [String] ?? []
            if rejectUnknownMedia, !attachments.isEmpty {
                return .error("附件不存在", code: "unknown_media", status: 400)
            }
            if validateAttachments {
                for raw in attachments {
                    guard let id = ServerMediaReference.mediaId(from: raw),
                          knownMediaIds.contains(id) else {
                        return .error("附件不存在：\(raw)", code: "unknown_media", status: 400)
                    }
                }
            }
            validated.append((
                id: id,
                role: role,
                content: object["content"] as? String ?? "",
                createdAt: createdAt,
                readAt: Self.int64(object["readAt"]),
                attachments: attachments,
                source: object["source"] as? String ?? "IMPORT"
            ))
        }
        var existingIds = Set((messages[sessionId] ?? []).map(\.id))
        var imported = 0
        var skipped = 0
        for object in validated {
            if existingIds.contains(object.id) {
                skipped += 1
                continue
            }
            record(Message(
                id: object.id,
                sessionId: sessionId,
                role: object.role,
                content: object.content,
                createdAt: object.createdAt,
                readAt: object.readAt,
                attachments: object.attachments,
                source: object.source
            ), sessionId: sessionId)
            existingIds.insert(object.id)
            imported += 1
        }
        var blockRange: [String: Any]?
        if imported > 0 {
            let total = messages[sessionId]?.count ?? 0
            blockRange = [
                "first": (total - imported) / Self.blockSize,
                "last": (total - 1) / Self.blockSize
            ]
        }
        if let index = sessions.firstIndex(where: { $0.id == sessionId }) {
            sessions[index].updatedAt = cursor
        }
        importedTotal += imported
        skippedTotal += skipped
        return .json([
            "sessionId": sessionId,
            "imported": imported,
            "skipped": skipped,
            "blockRange": blockRange ?? NSNull(),
            "messageCount": messages[sessionId]?.count ?? 0
        ])
    }

    private func uploadMedia(_ request: ServerRouteStub.Request) -> ServerRouteStub.Reply {
        if failMediaUpload {
            return .error("上传失败", code: "storage_failed", status: 500)
        }
        let kind = request.query["kind"] ?? "CHAT_IMAGE"
        let mime = request.headers["content-type"] ?? request.query["mime"] ?? "image/jpeg"
        let id = "media-\(nextId)"
        nextId += 1
        uploadedMedia.append((id: id, kind: kind, bytes: request.body.count))
        knownMediaIds.insert(id)
        return .json([
            "id": id,
            "kind": kind,
            "mime": mime,
            "bytes": request.body.count,
            "sha256": String(repeating: "a", count: 64),
            "url": "/v1/media/\(id)"
        ])
    }

    // MARK: - 路径解析

    /// `JSONSerialization` 给的是 `NSNumber`：统一按 Int64 取（布尔的 0/1 也认，别处不用它）。
    static func int64(_ value: Any?) -> Int64? {
        switch value {
        case let number as NSNumber: return number.int64Value
        case let number as Int64: return number
        case let number as Int: return Int64(number)
        default: return nil
        }
    }

    static func personaId(fromMemoriesPath path: String) -> String {
        // /v1/personas/<id>/memories
        let prefix = "/v1/personas/"
        guard path.hasPrefix(prefix) else { return "" }
        let tail = String(path.dropFirst(prefix.count))
        return String(tail.split(separator: "/").first ?? "")
    }

    static func sessionId(fromMessagesPath path: String, suffix: String) -> String {
        // /v1/sessions/<id>/messages[/import]
        let prefix = "/v1/sessions/"
        guard path.hasPrefix(prefix), path.hasSuffix(suffix) else { return "" }
        let tail = String(path.dropFirst(prefix.count).dropLast(suffix.count))
        return String(tail.split(separator: "/").first ?? "")
    }
}
