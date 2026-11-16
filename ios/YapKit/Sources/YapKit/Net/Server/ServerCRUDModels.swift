import Foundation

// ---------------------------------------------------------------------------
// 服务端写路径（M5）的线上模型：人格 / 记忆 / 预设 / 设置 / 备份。
//
// 与 `CrudRoutes.kt` 一一对应，但**刻意不写成一堆强类型 Request**：
// - 人格与预设有几十个字段，其中一半是「服务端不认识但要原样保留」的客户端列
//   （`worldJson` / `speechStyleJson` / `traits` / `likes` / `behaviors` / `backstory` …）；
//   写强类型结构体就会在「加一个字段」时悄悄丢字段，还不知道丢在哪一层；
// - 服务端的响应同样会把未知字段原样带回（`extraFields`），所以响应也按
//   `[String: JSONValue]` 收，再由 `ServerWriteService` 映射回本地实体。
//
// 这里只定义**形状固定**的那几个包络：`{"persona":{…}}` / `{"memory":{…}}` /
// `{"preset":{…}}` / `{"settings":{…}}` / `{"deleted":true,"id":"…"}` /
// `{"mode":…,"imported":{…}}`。
// ---------------------------------------------------------------------------

/// `GET/POST /v1/settings` 的响应：`{"settings":{…}}`（白名单内的键）。
public struct ServerSettingsResponse: Decodable, Equatable, Sendable {
    public var settings: [String: JSONValue]

    public init(settings: [String: JSONValue] = [:]) {
        self.settings = settings
    }

    private enum CodingKeys: String, CodingKey { case settings }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        settings = (try? container.decodeIfPresent([String: JSONValue].self, forKey: .settings)) ?? [:]
    }
}

/// `POST /v1/backup` 的响应：`{"mode":"merge","imported":{personas,sessions,messages,memories,presets,settings}}`。
public struct ServerBackupImportResult: Decodable, Equatable, Sendable {
    public var mode: String
    public var personas: Int
    public var sessions: Int
    public var messages: Int
    public var memories: Int
    public var presets: Int
    public var settings: Bool

    public init(
        mode: String = "",
        personas: Int = 0,
        sessions: Int = 0,
        messages: Int = 0,
        memories: Int = 0,
        presets: Int = 0,
        settings: Bool = false
    ) {
        self.mode = mode
        self.personas = personas
        self.sessions = sessions
        self.messages = messages
        self.memories = memories
        self.presets = presets
        self.settings = settings
    }

    private enum CodingKeys: String, CodingKey { case mode, imported }
    private enum ImportedKeys: String, CodingKey {
        case personas, sessions, messages, memories, presets, settings
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        mode = (try? container.decodeIfPresent(String.self, forKey: .mode)) ?? ""
        let imported = try? container.nestedContainer(keyedBy: ImportedKeys.self, forKey: .imported)
        personas = (try? imported?.decodeIfPresent(Int.self, forKey: .personas)) ?? 0 ?? 0
        sessions = (try? imported?.decodeIfPresent(Int.self, forKey: .sessions)) ?? 0 ?? 0
        messages = (try? imported?.decodeIfPresent(Int.self, forKey: .messages)) ?? 0 ?? 0
        memories = (try? imported?.decodeIfPresent(Int.self, forKey: .memories)) ?? 0 ?? 0
        presets = (try? imported?.decodeIfPresent(Int.self, forKey: .presets)) ?? 0 ?? 0
        settings = (try? imported?.decodeIfPresent(Bool.self, forKey: .settings)) ?? false ?? false
    }
}

/// 写路径共用的小包络：服务端回 `{"persona":{…}}` / `{"memory":{…}}` / `{"preset":{…}}` /
/// `{"deleted":true,"id":"…"}`。缺哪个字段就 nil（不同接口回的键不同）。
public struct ServerWriteEnvelope: Decodable, Equatable, Sendable {
    public var persona: [String: JSONValue]?
    public var memory: [String: JSONValue]?
    public var preset: [String: JSONValue]?
    public var deleted: Bool?
    public var id: String?

    public init(
        persona: [String: JSONValue]? = nil,
        memory: [String: JSONValue]? = nil,
        preset: [String: JSONValue]? = nil,
        deleted: Bool? = nil,
        id: String? = nil
    ) {
        self.persona = persona
        self.memory = memory
        self.preset = preset
        self.deleted = deleted
        self.id = id
    }

    private enum CodingKeys: String, CodingKey {
        case persona, memory, preset, deleted, id
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        persona = try? container.decodeIfPresent([String: JSONValue].self, forKey: .persona)
        memory = try? container.decodeIfPresent([String: JSONValue].self, forKey: .memory)
        preset = try? container.decodeIfPresent([String: JSONValue].self, forKey: .preset)
        deleted = try? container.decodeIfPresent(Bool.self, forKey: .deleted)
        id = try? container.decodeIfPresent(String.self, forKey: .id)
    }
}
