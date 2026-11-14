import Foundation

/// 备份解码用的最小 JSON 值树。
///
/// 为什么不直接用 `Codable` 解备份？
///
/// Android 端用的是 kotlinx.serialization：字段**缺失**时用默认值，字段**类型对不上**时
/// 抛异常，于是整条记录被跳过、只留一条警告（见 `BackupCodec.decodeList`）。
/// Swift 的 `Codable` 做不到这个区分：非可选属性缺 key 会整段失败，而 `try?` 包一层
/// 又会把「类型错了」悄悄变成默认值 —— 那就等于把一条记忆的 `createdAt` 静默写成 0，
/// 比丢一条更危险。
///
/// 所以这里自己拿值树：先解析成 `JSONValue`，再由 `[String: JSONValue]` 的取值助手区分
/// 「没写」（给默认值）与「写错了」（抛错 → 这一条跳过并记警告），行为与 Android 对齐。
public enum JSONValue: Decodable, Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
            return
        }
        // 顺序有讲究：Bool 必须排在 Int 之前（JSON 的 true/false 不能当数字读），
        // Int64 排在 Double 之前（整数值按整数保真，避免 1.79e12 这种时间戳被写成浮点）。
        if let value = try? container.decode(Bool.self) {
            self = .bool(value)
            return
        }
        if let value = try? container.decode(Int64.self) {
            self = .int(value)
            return
        }
        if let value = try? container.decode(Double.self) {
            self = .double(value)
            return
        }
        if let value = try? container.decode(String.self) {
            self = .string(value)
            return
        }
        if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
            return
        }
        if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
            return
        }
        throw DecodingError.dataCorruptedError(
            in: container,
            debugDescription: "不认识的 JSON 值"
        )
    }

    public var isNull: Bool {
        if case .null = self { return true }
        return false
    }
}

// MARK: - 解析入口

public enum JSONValueParser {
    /// 把备份文本解析成顶层对象。
    ///
    /// 顶层必须是对象：`[1,2,3]`、`"一段字符串"`、坏 JSON 一律返回 nil，由调用方给出中文原因。
    /// 这里解码 `[String: JSONValue]` 而不是 `JSONValue`，是因为顶层字典是「集合」而不是
    /// JSON 片段（fragment），在 Apple 与 Linux 的 Foundation 上都稳定可用。
    public static func object(from raw: String) -> [String: JSONValue]? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode([String: JSONValue].self, from: data)
    }
}

// MARK: - 宽容但会报错的取值

/// 「字段写错了」的错误：只在解码单条记录时内部使用，由调用方换算成「这一条跳过」。
public struct JSONFieldTypeError: Error, CustomStringConvertible, Equatable {
    public let key: String
    public let expected: String

    public init(key: String, expected: String) {
        self.key = key
        self.expected = expected
    }

    public var description: String { "字段「\(key)」不是\(expected)" }
}

/// 取值规则（与 kotlinx.serialization 的 `isLenient = true` 对齐）：
///
/// - key 不存在 / 值是 `null` → 用默认值；
/// - 数值字段写成带引号的数字（`"5"`）→ 接受（lenient 行为）；
/// - 字符串字段写成数字、数字字段写成非数字字符串 → **抛错**，这一条记录被跳过。
///
/// 最后一条是关键：宁可丢一条并告诉用户，也不要把 `createdAt` 写成 0 之后假装读成功了。
public extension Dictionary where Key == String, Value == JSONValue {
    // MARK: 字符串

    /// 取字符串；缺失/null 用 `fallback`。
    func text(_ key: String, _ fallback: String = "") throws -> String {
        try optionalText(key) ?? fallback
    }

    func optionalText(_ key: String) throws -> String? {
        guard let value = self[key], !value.isNull else { return nil }
        if case .string(let text) = value { return text }
        throw JSONFieldTypeError(key: key, expected: "字符串")
    }

    // MARK: 整数

    func int(_ key: String, _ fallback: Int = 0) throws -> Int {
        try optionalInt(key) ?? fallback
    }

    func optionalInt(_ key: String) throws -> Int? {
        guard let int64 = try optionalInt64(key) else { return nil }
        guard let value = Int(exactly: int64) else {
            throw JSONFieldTypeError(key: key, expected: "可放进 Int 的整数")
        }
        return value
    }

    func int64(_ key: String, _ fallback: Int64 = 0) throws -> Int64 {
        try optionalInt64(key) ?? fallback
    }

    func optionalInt64(_ key: String) throws -> Int64? {
        guard let value = self[key], !value.isNull else { return nil }
        switch value {
        case .int(let number):
            return number
        case .string(let raw):
            if let number = Int64(raw.trimmingCharacters(in: .whitespacesAndNewlines)) {
                return number
            }
            throw JSONFieldTypeError(key: key, expected: "整数")
        default:
            throw JSONFieldTypeError(key: key, expected: "整数")
        }
    }

    // MARK: 浮点

    func double(_ key: String, _ fallback: Double = 0) throws -> Double {
        try optionalDouble(key) ?? fallback
    }

    func optionalDouble(_ key: String) throws -> Double? {
        guard let value = self[key], !value.isNull else { return nil }
        switch value {
        case .double(let number):
            return number
        case .int(let number):
            return Double(number)
        case .string(let raw):
            if let number = Double(raw.trimmingCharacters(in: .whitespacesAndNewlines)) {
                return number
            }
            throw JSONFieldTypeError(key: key, expected: "数字")
        default:
            throw JSONFieldTypeError(key: key, expected: "数字")
        }
    }

    func float(_ key: String, _ fallback: Float = 0) throws -> Float {
        try optionalFloat(key) ?? fallback
    }

    func optionalFloat(_ key: String) throws -> Float? {
        guard let number = try optionalDouble(key) else { return nil }
        guard number.isFinite else {
            throw JSONFieldTypeError(key: key, expected: "有限的数字")
        }
        return Float(number)
    }

    // MARK: 布尔

    func bool(_ key: String, _ fallback: Bool = false) throws -> Bool {
        guard let value = self[key], !value.isNull else { return fallback }
        switch value {
        case .bool(let flag):
            return flag
        case .string(let raw):
            // lenient：带引号的 "true" / "false" 也认。
            switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true": return true
            case "false": return false
            default: throw JSONFieldTypeError(key: key, expected: "布尔值")
            }
        default:
            throw JSONFieldTypeError(key: key, expected: "布尔值")
        }
    }

    // MARK: 字符串列表（1.9 的 traits / likes / behaviors）

    /// 取一个字符串列表，**永远不抛错**。
    ///
    /// 为什么宽容到这个程度：这三个字段是「性格 / 喜欢 / 习惯」这种锦上添花的东西，
    /// 为了一个标签的写法把整个人格丢掉（连同他的记忆与对话）是明显错误的取舍。
    /// 认得的写法：
    /// - JSON 数组 `["慢热","嘴硬心软"]`（我们与 Android 都这么写）；
    /// - 被再转义一次的 JSON 字符串 `"[\"慢热\"]"`（早期 tags 就是这种存法）；
    /// - 一串用顿号/逗号/换行分开的话 `"慢热、嘴硬心软"`；
    /// - 单个字符串 `"慢热"`。
    func stringList(_ key: String, limit: Int) -> [String] {
        guard let value = self[key], !value.isNull else { return [] }
        switch value {
        case .array(let items):
            return PersonaTags.normalize(items.map { item -> String in
                if case .string(let text) = item { return text }
                // 数字标签（"1"）这种也收下：宁可多一个怪标签，也别丢字段。
                if case .int(let number) = item { return String(number) }
                return ""
            }, limit: limit)
        case .string(let raw):
            return PersonaTags.decode(raw, limit: limit)
        default:
            return []
        }
    }

    // MARK: 顶层元数据（不抛错，与 Android 的 textOrNull / intOrNull 一致）

    /// 只认 JSON 字符串，别的类型一律当「没写」。
    func rawText(_ key: String) -> String? {
        guard let value = self[key], case .string(let text) = value else { return nil }
        return text
    }

    /// 只认 JSON 整数，别的类型一律当「没写」。
    func rawInt64(_ key: String) -> Int64? {
        guard let value = self[key], case .int(let number) = value else { return nil }
        return number
    }

    func rawInt(_ key: String) -> Int? {
        guard let number = rawInt64(key) else { return nil }
        return Int(exactly: number)
    }
}

// MARK: - 表格行（数据库 ↔ 实体）用的宽容枚举换算

/// 把数据库/备份里的字符串枚举换成 Swift 枚举：不认识就退回默认档，不因为一个坏字段整条失败。
/// 与 Android 的 `enumOr` 同语义（去空白、忽略大小写）。
public func backupEnumOr<T>(_ raw: String?, _ fallback: T) -> T where T: RawRepresentable, T.RawValue == String {
    guard let cleaned = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !cleaned.isEmpty else {
        return fallback
    }
    return T(rawValue: cleaned.uppercased()) ?? fallback
}
