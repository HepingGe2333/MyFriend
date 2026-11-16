import Foundation

// ---------------------------------------------------------------------------
// 服务端**写路径**（M5）：人格 / 记忆 / 预设 / 设置 / 备份。
//
// 与 `ServerAPIClient+Write.swift`（M3 的会话/已读/活动/推送）分开一文件：
// 那一批是「聊天链路的必要写」，这一批是「把本地 CRUD 上移到服务端」。
// 两者共用 `post` / `get` 的错误映射与超时口径。
//
// 请求体的形状是**动态键**（人格/预设的字段太多，且服务端要把未知字段原样保留），
// 所以统一用 `[String: JSONValue]`，不写一堆强类型 Request（见 `ServerCRUDModels`）。
// 服务端回的是 `{"persona":{…}}` 这样的包络，也不做强类型解码。
// ---------------------------------------------------------------------------

public extension ServerAPIClient {

    // MARK: - 人格

    /// `POST /v1/personas`：新建（body 不带 `id`）或更新（带 `id`）。
    ///
    /// 服务端按「缺省字段保留旧值」处理，所以调用方可以只发改动的键；
    /// 这里仍然发完整字段（人格编辑器保存的是整份）。
    func savePersona(_ body: [String: JSONValue]) async throws -> ServerWriteEnvelope {
        guard let name = body["name"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
              !name.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "名字不能为空")
        }
        return try await post(path: "/personas", body: body, as: ServerWriteEnvelope.self)
    }

    /// `DELETE /v1/personas/{id}`：删人格（服务端连带会话/消息/记忆）。
    func deletePersona(id: String) async throws -> ServerWriteEnvelope {
        let id = try Self.crudPathId(id, field: "personaId")
        return try await delete(path: "/personas/\(Self.writePathSegment(id))")
    }

    // MARK: - 记忆

    /// `GET /v1/personas/{id}/memories`（缓存回填/调试用；写路径本身不依赖它）。
    func memories(personaId: String, limit: Int = 200, since: Int64 = 0) async throws -> [String: JSONValue] {
        let id = try Self.crudPathId(personaId, field: "personaId")
        var query = [URLQueryItem(name: "limit", value: String(max(limit, 1)))]
        if since > 0 { query.append(URLQueryItem(name: "since", value: String(since))) }
        return try await get(
            path: "/personas/\(Self.writePathSegment(id))/memories",
            query: query,
            as: [String: JSONValue].self
        )
    }

    /// `POST /v1/personas/{id}/memories`：新建（body 不带 `id`）或更新（带 `id`）。
    func saveMemory(personaId: String, body: [String: JSONValue]) async throws -> ServerWriteEnvelope {
        let id = try Self.crudPathId(personaId, field: "personaId")
        guard let content = body["content"]?.stringValue?
            .trimmingCharacters(in: .whitespacesAndNewlines), !content.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "记忆内容不能为空")
        }
        return try await post(
            path: "/personas/\(Self.writePathSegment(id))/memories",
            body: body,
            as: ServerWriteEnvelope.self
        )
    }

    /// `DELETE /v1/memories/{id}`。
    func deleteMemory(id: String) async throws -> ServerWriteEnvelope {
        let id = try Self.crudPathId(id, field: "memoryId")
        return try await delete(path: "/memories/\(Self.writePathSegment(id))")
    }

    // MARK: - 预设

    /// `POST /v1/presets`：新建（不带 `id`）或更新（带 `id`）。
    func savePreset(_ body: [String: JSONValue]) async throws -> ServerWriteEnvelope {
        guard let name = body["name"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
              !name.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "预设名字不能为空")
        }
        return try await post(path: "/presets", body: body, as: ServerWriteEnvelope.self)
    }

    /// `DELETE /v1/presets/{id}`。
    func deletePreset(id: String) async throws -> ServerWriteEnvelope {
        let id = try Self.crudPathId(id, field: "presetId")
        return try await delete(path: "/presets/\(Self.writePathSegment(id))")
    }

    /// `GET /v1/presets`：服务端预设列表（服务端模式下拉取缓存用）。
    func presets() async throws -> [[String: JSONValue]] {
        let response = try await get(path: "/presets", query: [], as: [String: JSONValue].self)
        return response["presets"]?.arrayValue?.compactMap(\.objectValue) ?? []
    }

    // MARK: - 设置

    /// `GET /v1/settings`（只回白名单内的键，不含任何 API Key）。
    func settings() async throws -> [String: JSONValue] {
        let response = try await get(
            path: "/settings", query: [], as: ServerSettingsResponse.self
        )
        return response.settings
    }

    /// `POST /v1/settings`：白名单合并；未知键/密钥形状的键由服务端忽略。
    func updateSettings(_ values: [String: JSONValue]) async throws -> [String: JSONValue] {
        let response = try await post(
            path: "/settings", body: values, as: ServerSettingsResponse.self
        )
        return response.settings
    }

    // MARK: - 备份

    /// `GET /v1/backup`：导出服务端的备份对象（v5 JSON 文本）。
    ///
    /// 先解成 `JSONValue` 再序列化回字符串：服务端回来的是「备份对象本身」而不是字符串，
    /// 直接拿 `Data` 会多一条绕过错误映射的原始请求路径。字段/数值都保真（`.int` 不转浮点）。
    func backupJSON() async throws -> String {
        let object = try await get(path: "/backup", query: [], as: [String: JSONValue].self)
        // `JSONValue` 是值树，不是 `JSONSerialization` 认的 Foundation 对象：
        // 先转成 `Any` 再序列化（`.int` 保持 Int64，时间戳不会被写成浮点）。
        let any = object.mapValues(Self.anyValue)
        guard JSONSerialization.isValidJSONObject(any) else {
            throw ServerAPIError(
                code: ServerAPIError.Code.decodeFailed,
                message: "服务端返回的备份内容读不出来"
            )
        }
        // 不用 `.sortedKeys`：备份是给人换机用的，字段顺序不影响导入；
        // 但保留原样更容易和 Android 端导出的文件 diff。
        guard let data = try? JSONSerialization.data(withJSONObject: any, options: []),
              let text = String(data: data, encoding: .utf8) else {
            throw ServerAPIError(
                code: ServerAPIError.Code.decodeFailed,
                message: "服务端返回的备份内容读不出来"
            )
        }
        return text
    }

    /// `POST /v1/backup`：把一份客户端备份 JSON 交给服务端（merge / replace）。
    func importBackup(text: String, mode: ImportMode) async throws -> ServerBackupImportResult {
        guard let object = JSONValueParser.object(from: text) else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "备份文件读不出来")
        }
        let body: [String: JSONValue] = [
            "mode": .string(mode.rawValue),
            "data": .object(object)
        ]
        return try await post(path: "/backup", body: body, as: ServerBackupImportResult.self)
    }

    // MARK: - 内部

    /// `DELETE` 一个 JSON 接口（服务端错误体与 GET/POST 一致）。
    func delete(path: String) async throws -> ServerWriteEnvelope {
        var request = try makeRequest(
            path: path, query: [], method: "DELETE", accept: "application/json"
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw ServerAPIError(
                    code: ServerAPIError.Code.invalidResponse,
                    message: "服务端没有返回 HTTP 响应"
                )
            }
            guard (200..<300).contains(http.statusCode) else {
                throw Self.error(from: data, status: http.statusCode)
            }
            return (try? JSONDecoder().decode(ServerWriteEnvelope.self, from: data))
                ?? ServerWriteEnvelope.empty
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

    /// `JSONValue` → `JSONSerialization` 认的 Foundation 对象树。
    static func anyValue(_ value: JSONValue) -> Any {
        switch value {
        case .null: return NSNull()
        case .bool(let flag): return flag
        case .int(let number): return NSNumber(value: number)
        case .double(let number): return NSNumber(value: number)
        case .string(let text): return text
        case .array(let items): return items.map(anyValue)
        case .object(let fields): return fields.mapValues(anyValue)
        }
    }

    private static func crudPathId(_ raw: String, field: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "\(field) 不能为空")
        }
        return trimmed
    }
}

/// `ServerWriteEnvelope` 的空实例（DELETE 的服务端响应体可能是任意 JSON）。
public extension ServerWriteEnvelope {
    static let empty = ServerWriteEnvelope()
}

/// `JSONValue` → 基础类型的便捷读取（写路径拼 body 与映射响应用）。
public extension JSONValue {
    var stringValue: String? {
        if case .string(let text) = self { return text }
        return nil
    }

    var objectValue: [String: JSONValue]? {
        if case .object(let fields) = self { return fields }
        return nil
    }

    var arrayValue: [JSONValue]? {
        if case .array(let items) = self { return items }
        return nil
    }

    var boolValue: Bool? {
        if case .bool(let flag) = self { return flag }
        return nil
    }

    var intValue: Int64? {
        if case .int(let number) = self { return number }
        return nil
    }

    /// `.double` 与 `.int` 都当成浮点读：服务端可能把同一个设置写成 `1` 或 `1.0`。
    var doubleValue: Double? {
        switch self {
        case .double(let number): return number
        case .int(let number): return Double(number)
        default: return nil
        }
    }
}
