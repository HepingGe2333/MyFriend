import Foundation

// ---------------------------------------------------------------------------
// 服务端备份（`GET /v1/backup`，v6 同形 JSON）→ 客户端本地 v6 形状（`spec/experience` §11）。
//
// 为什么需要这一层：服务端导出的确实是 v6 同形，但**少数列的名字/单位**跟客户端本地
// 备份不一样。直接塞给 `BackupService.importBackup` 会出现「接回来了，但头像没了 /
// 重要度变成 80 星 / 图片全丢」：
// - 服务端人格用 `avatarUrl`，客户端备份列是 `avatarUri`；
// - 服务端记忆 `importance` 是 0–100，客户端是 1–5；`tags` 服务端是数组，客户端是 JSON 文本；
// - 服务端消息把附件存成字符串数组 `attachments`，客户端是 `attachmentsJson` 文本；
// - 服务端会话叫 `unread`，客户端备份叫 `unreadCount`；
// - 表情文件名服务端可能只写 `file`，客户端还认 `fileName`（反之亦然）。
//
// 0.14.1 / 0.15 的两条硬规矩（`spec/experience/README.md` §11 + 0.15 体验备份）：
// 1. `extensions` 段本身不是客户端备份格式的一部分，但它里面有三样**客户端也要**的数据：
//    `mentalActivities[]` / `sessionMemory[]`（0.15 起落进本机对应表）与
//    `proactiveFailures[]`（只落本机诊断表）。这里把它们**提到顶层**再交给
//    `BackupCodec`；别的服务端专有键继续忽略（客户端不认、也不写回）。
// 2. 客户端独有段（`proactiveRules[]` / `agentIntents[]` / `memoryConsolidations[]` 以及
//    将来新增的段）如果被服务端收在 `client_extras` 里，这里**提到顶层**再交给
//    `BackupCodec`，否则本地导入会把它们当未知键丢掉。
//
// 这个适配**只用于刷新本地缓存**：交给服务端（`POST /v1/backup`）的仍然是原始备份文本，
// 服务端自己负责权威导入（与 Android `ServerBackupAdapter` 同一条分工）。
// ---------------------------------------------------------------------------

public enum ServerBackupAdapter {

    /// 会被逐字段改写的段；其余顶层键原样保留（除了 `extensions` / `client_extras`）。
    private static let rewrittenSections: Set<String> = [
        "personas", "memories", "sessions", "messages", "presets"
    ]

    /// 服务端备份原文 → 本地可解码的 v6 原文。不是 JSON 对象时返回 nil。
    public static func toLocalBackupJSON(_ raw: String) -> String? {
        guard let root = JSONValueParser.object(from: raw) else { return nil }
        let local = toLocalBackup(root)
        guard let data = try? JSONEncoder().encode(local),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    /// 同上，直接处理值树（单测用，避免反复序列化）。
    public static func toLocalBackup(_ root: [String: JSONValue]) -> [String: JSONValue] {
        var fields: [String: JSONValue] = [:]
        let clientExtras = root["client_extras"]?.objectValue

        for (key, value) in root {
            // 服务端专有段与承载客户端段的容器都不进本地备份。
            if key == "extensions" || key == "client_extras" { continue }
            if rewrittenSections.contains(key) { continue }
            fields[key] = value
        }
        // 客户端独有的段可能被服务端原样收在 `client_extras` 里：提到顶层。
        // 顶层已有值时以顶层为准（新服务端会把它们写回顶层）。
        for (key, value) in clientExtras ?? [:] where fields[key] == nil && key != "extensions" {
            fields[key] = value
        }
        // 0.15：服务端专有 `extensions` 里也有客户端要的两段（+ 一段诊断数据）。
        // 只在「顶层缺失或为空」时才用它们，保证客户端自己导出的 v7 永远优先。
        if let extensions = root["extensions"]?.objectValue {
            for key in ["mentalActivities", "sessionMemory", "proactiveFailures"] {
                guard let value = extensions[key] else { continue }
                let existing = fields[key]
                let isEmpty = existing == nil || existing?.arrayValue?.isEmpty == true
                guard isEmpty, value.arrayValue?.isEmpty == false else { continue }
                fields[key] = value
            }
        }

        if let personas = root["personas"]?.arrayValue {
            fields["personas"] = .array(personas.map { item in
                guard var persona = item.objectValue else { return item }
                if persona["avatarUri"] == nil,
                   let url = persona["avatarUrl"]?.stringValue,
                   !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    persona["avatarUri"] = .string(url)
                }
                return .object(persona)
            })
        }
        if let memories = root["memories"]?.arrayValue {
            fields["memories"] = .array(memories.map { item in
                guard var memory = item.objectValue else { return item }
                if let server = memory["importance"]?.intValue, server > 5 {
                    // 与 Android `ServerWriteMapping.importanceFromServer` 同一公式。
                    let local = min(max(Int((server + 10) / 20), 1), 5)
                    memory["importance"] = .int(Int64(local))
                }
                if let tags = memory["tags"], tags.objectValue == nil, tags.arrayValue != nil,
                   let text = encodeJSONText(tags) {
                    memory["tags"] = .string(text)
                }
                return .object(memory)
            })
        }
        if let sessions = root["sessions"]?.arrayValue {
            fields["sessions"] = .array(sessions.map { item in
                guard var session = item.objectValue else { return item }
                if session["unreadCount"] == nil, let unread = session["unread"]?.intValue {
                    session["unreadCount"] = .int(unread)
                }
                return .object(session)
            })
        }
        if let presets = root["presets"] {
            fields["presets"] = presets
        }
        if let stickers = root["stickers"]?.arrayValue {
            fields["stickers"] = .array(stickers.map { item in
                guard var sticker = item.objectValue else { return item }
                let fileName = sticker["fileName"]?.stringValue ?? ""
                let file = sticker["file"]?.stringValue ?? ""
                if fileName.isEmpty, !file.isEmpty { sticker["fileName"] = .string(file) }
                if file.isEmpty, !fileName.isEmpty { sticker["file"] = .string(fileName) }
                return .object(sticker)
            })
        }
        if let notes = root["diaryNotes"] {
            fields["diaryNotes"] = notes
        }
        if let messages = root["messages"]?.arrayValue {
            fields["messages"] = .array(messages.map { item in
                guard var message = item.objectValue else { return item }
                if message["attachmentsJson"] == nil,
                   let attachments = message["attachments"],
                   let json = attachmentsAsLocalJson(attachments) {
                    message["attachmentsJson"] = .string(json)
                }
                return .object(message)
            })
        }
        return fields
    }

    /// 服务端 `attachments: ["/v1/media/id"]` → 客户端 `attachmentsJson` 文本。
    ///
    /// 只保留非空字符串项，坏数据丢掉（与 Android 同口径）；mime 用 `image/jpeg` 占位，
    /// 本地渲染不依赖它，上传新消息时会重新压缩。
    private static func attachmentsAsLocalJson(_ value: JSONValue) -> String? {
        guard let items = value.arrayValue else { return nil }
        let uris = items.compactMap { item -> String? in
            guard let text = item.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !text.isEmpty else { return nil }
            return text
        }
        guard !uris.isEmpty else { return nil }
        let objects: [JSONValue] = uris.map { uri in
            .object(["uri": .string(uri), "mime": .string("image/jpeg")])
        }
        return encodeJSONText(.array(objects))
    }

    private static func encodeJSONText(_ value: JSONValue) -> String? {
        guard let data = try? JSONEncoder().encode(value) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
