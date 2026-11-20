import Foundation

// ---------------------------------------------------------------------------
// 备份文件的「封条」：整份 JSON（去掉封条自己）规范化之后的 SHA-256（0.15）。
//
// **这是跨端协议，不是本机实现细节。** Android 端写的是
// `app/src/main/java/com/yap/app/domain/BackupMetadata.kt` 里的 `BackupChecksum`，
// 两边必须逐字节一致，否则「我导出的备份你读不了」——那正是备份功能最不能出的错。
//
// 协议：`yap-canonical-json-sha256-v1`
// 1. 把文件解析成 JSON 树（**数字保留文件里的原始字面量**：`1.0` 就是 `1.0`，
//    绝不先转成 Double 再重新格式化 —— 两端的浮点打印规则不一样，一转就对不上）；
// 2. 顶层的 `checksum` 与 `checksumAlgorithm` 两个键去掉（封条不封自己）；
// 3. 对象键按 UTF-16 码元升序排序，数组保持原顺序，输出**无任何空白**的紧凑 JSON；
// 4. 字符串按 kotlinx.serialization 的转义表输出：
//    `"` → `\"`、`\` → `\\`、`\b \t \n \f \r` 用短转义、其余 < 0x20 用 `\u00xx`（小写十六进制）；
//    非 ASCII 字符**原样**输出（UTF-8 字节），不转义、不做 Unicode 归一化；
// 5. 对上一步的 UTF-8 字节做 SHA-256，写 64 位小写 hex。
//
// 为什么不用各自语言的 JSONEncoder 重新编码整个 bundle：Swift 的 JSONEncoder 与 Kotlin 的
// kotlinx 对 Double（`1` vs `1.0`）、Unicode 转义、斜杠转义的写法都不一样，两边算出来的哈希
// 必然不同。所以这里只对**文件里已有的字面量**做排序去空白，把「编码器差异」排除在外。
//
// 导入侧的规则（与 Android 相同，见 `BackupCodec.decode`）：
// - 只有带着本算法标记的 checksum 才复核；
// - 没有 checksum、没有标记、标记是别的客户端 → **不拦**（旧备份 / 别的客户端导出的备份），
//   只在预览里提示「校验和来自其他客户端，未在本机复核」；
// - 只有「标记匹配但哈希对不上」才判损坏。
// ---------------------------------------------------------------------------

public enum BackupIntegrity {

    /// 本机（= 跨端）算法标记。只有带着这个标记的 checksum 才由本机复核。
    public static let algorithm = "yap-canonical-json-sha256-v1"

    /// JSON 里校验和的键名。
    public static let checksumKey = "checksum"

    /// JSON 里算法标记的键名。
    public static let algorithmKey = "checksumAlgorithm"

    /// 规范化 JSON 的 SHA-256（64 位小写 hex）。
    ///
    /// - Parameter raw: 整份备份文件的文本（不是某个 `data` 字段：v7 是扁平结构，没有那个字段）。
    /// - Returns: 解析不出来时返回 nil（调用方按「文件读不懂」处理，而不是假装校验通过）。
    public static func checksum(ofJSONText raw: String) -> String? {
        guard let canonical = canonicalJSONWithoutIntegrityKeys(raw) else { return nil }
        return ImageContentHash.sha256Hex(Data(canonical.utf8))
    }

    /// 「除 checksum / checksumAlgorithm 外的整份 JSON」的规范化文本。
    ///
    /// 顶层必须是对象；解析失败返回 nil。数字保留原始字面量（见文件头注释）。
    public static func canonicalJSONWithoutIntegrityKeys(_ raw: String) -> String? {
        guard var value = CanonicalJSON.parse(raw), case .object(let members) = value else {
            return nil
        }
        var stripped = members
        stripped.removeValue(forKey: checksumKey)
        stripped.removeValue(forKey: algorithmKey)
        value = .object(stripped)
        var out = ""
        value.writeCanonical(into: &out)
        return out
    }

    /// 归一一个「声明里的哈希」：去空白、去引号 / `W/` 前缀、小写；不是 64 位 hex → nil。
    ///
    /// 与 Android 的 `MediaHashes.normalize` 同一套口径（坏字符串按「没有哈希」处理）。
    public static func normalizedChecksum(_ raw: String?) -> String? {
        ServerMediaHash.normalized(raw)
    }
}

// MARK: - 保留数字字面量的 JSON 树

/// 只给校验和用的 JSON 树：**数字存原始文本**，对象存字典（键排序在输出时做）。
///
/// 为什么不复用 `JSONValue`：那个树把数字解析成 `Int64` / `Double`，`1.0` 会变成 `1`，
/// 再打印出来与 Kotlin 的 `1.0` 对不上（跨端哈希就对不上了）。
enum CanonicalJSON {

    indirect enum Value: Equatable {
        case object([String: Value])
        case array([Value])
        case string(String)
        /// 数字的原始字面量（`1.0` / `1e-5` / `-0` 都原样保留）。
        case number(String)
        case bool(Bool)
        case null

        /// 规范化输出（见 `BackupIntegrity` 文件头）：键排序、无空白、kotlinx 转义规则。
        func writeCanonical(into out: inout String) {
            switch self {
            case .object(let members):
                out.append("{")
                let keys = members.keys.sorted(by: Self.utf16Less)
                for (index, key) in keys.enumerated() {
                    if index > 0 { out.append(",") }
                    Self.writeString(key, into: &out)
                    out.append(":")
                    members[key]!.writeCanonical(into: &out)
                }
                out.append("}")
            case .array(let items):
                out.append("[")
                for (index, item) in items.enumerated() {
                    if index > 0 { out.append(",") }
                    item.writeCanonical(into: &out)
                }
                out.append("]")
            case .string(let text):
                Self.writeString(text, into: &out)
            case .number(let literal):
                out.append(literal)
            case .bool(let flag):
                out.append(flag ? "true" : "false")
            case .null:
                out.append("null")
            }
        }

        /// Kotlin 的 `String.compareTo` 比的是 UTF-16 码元；备份里的键都是 ASCII，
        /// 但这里不假设它 —— 别的客户端可能塞进非 ASCII 键。
        static func utf16Less(_ lhs: String, _ rhs: String) -> Bool {
            var left = lhs.utf16.makeIterator()
            var right = rhs.utf16.makeIterator()
            while true {
                switch (left.next(), right.next()) {
                case (nil, nil):
                    return false
                case (nil, _):
                    return true
                case (_, nil):
                    return false
                case (let l?, let r?):
                    if l != r { return l < r }
                }
            }
        }

        /// kotlinx.serialization 的 `printQuoted`：只转义 `"`、`\`、控制字符，非 ASCII 原样。
        static func writeString(_ text: String, into out: inout String) {
            out.append("\"")
            for scalar in text.unicodeScalars {
                switch scalar.value {
                case 0x22:
                    out.append("\\\"")
                case 0x5C:
                    out.append("\\\\")
                case 0x08:
                    out.append("\\b")
                case 0x09:
                    out.append("\\t")
                case 0x0A:
                    out.append("\\n")
                case 0x0C:
                    out.append("\\f")
                case 0x0D:
                    out.append("\\r")
                case 0x00...0x1F:
                    // 与 kotlinx 一致：`\u00xx`，小写十六进制。
                    out.append(String(format: "\\u%04x", scalar.value))
                default:
                    out.unicodeScalars.append(scalar)
                }
            }
            out.append("\"")
        }
    }

    // MARK: 解析

    static func parse(_ text: String) -> Value? {
        var parser = Parser(bytes: Array(text.utf8))
        guard let value = parser.parseValue() else { return nil }
        parser.skipWhitespace()
        guard parser.isAtEnd else { return nil }
        return value
    }

    /// 一个够用的 JSON 解析器：只需要「标准 JSON + 保留数字字面量」。
    private struct Parser {
        let bytes: [UInt8]
        var index = 0

        init(bytes: [UInt8]) {
            self.bytes = bytes
        }

        var isAtEnd: Bool { index >= bytes.count }

        func peek(_ offset: Int = 0) -> UInt8? {
            let position = index + offset
            guard position >= 0, position < bytes.count else { return nil }
            return bytes[position]
        }

        mutating func advance() { index += 1 }

        mutating func skipWhitespace() {
            while let byte = peek(), byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D {
                index += 1
            }
        }

        mutating func consume(_ byte: UInt8) -> Bool {
            guard peek() == byte else { return false }
            index += 1
            return true
        }

        mutating func parseValue() -> Value? {
            skipWhitespace()
            guard let byte = peek() else { return nil }
            switch byte {
            case 0x7B: return parseObject()
            case 0x5B: return parseArray()
            case 0x22: return parseString().map(Value.string)
            case 0x74: return parseLiteral("true").map { _ in .bool(true) }
            case 0x66: return parseLiteral("false").map { _ in .bool(false) }
            case 0x6E: return parseLiteral("null").map { _ in .null }
            default: return parseNumber().map(Value.number)
            }
        }

        mutating func parseObject() -> Value? {
            guard consume(0x7B) else { return nil }
            var members: [String: Value] = [:]
            skipWhitespace()
            if consume(0x7D) { return .object(members) }
            while true {
                skipWhitespace()
                guard let key = parseString() else { return nil }
                skipWhitespace()
                guard consume(0x3A) else { return nil }
                guard let value = parseValue() else { return nil }
                // 重复键按「后写覆盖」处理，与 kotlinx 的 JsonObject 一致。
                members[key] = value
                skipWhitespace()
                if consume(0x2C) { continue }
                if consume(0x7D) { return .object(members) }
                return nil
            }
        }

        mutating func parseArray() -> Value? {
            guard consume(0x5B) else { return nil }
            var items: [Value] = []
            skipWhitespace()
            if consume(0x5D) { return .array(items) }
            while true {
                guard let value = parseValue() else { return nil }
                items.append(value)
                skipWhitespace()
                if consume(0x2C) { continue }
                if consume(0x5D) { return .array(items) }
                return nil
            }
        }

        mutating func parseString() -> String? {
            guard consume(0x22) else { return nil }
            var buffer: [UInt8] = []
            while let byte = peek() {
                if byte == 0x22 {
                    index += 1
                    let text = String(decoding: buffer, as: UTF8.self)
                    // 原样保留非法 UTF-8 会让两端得到不同的字符串；直接判读不懂。
                    guard Array(text.utf8) == buffer else { return nil }
                    return text
                }
                if byte == 0x5C {
                    index += 1
                    guard let escape = peek() else { return nil }
                    index += 1
                    switch escape {
                    case 0x22: buffer.append(0x22)
                    case 0x5C: buffer.append(0x5C)
                    case 0x2F: buffer.append(0x2F)
                    case 0x62: buffer.append(0x08)
                    case 0x66: buffer.append(0x0C)
                    case 0x6E: buffer.append(0x0A)
                    case 0x72: buffer.append(0x0D)
                    case 0x74: buffer.append(0x09)
                    case 0x75:
                        guard let unit = readHex4() else { return nil }
                        if unit >= 0xD800 && unit <= 0xDBFF {
                            guard peek() == 0x5C, peek(1) == 0x75 else { return nil }
                            index += 2
                            guard let low = readHex4(), low >= 0xDC00, low <= 0xDFFF else { return nil }
                            let scalarValue = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00)
                            append(scalar: scalarValue, to: &buffer)
                        } else if unit >= 0xDC00 && unit <= 0xDFFF {
                            return nil   // 孤立的低代理项：不是合法 JSON
                        } else {
                            append(scalar: unit, to: &buffer)
                        }
                    default:
                        return nil
                    }
                } else {
                    buffer.append(byte)
                    index += 1
                }
            }
            return nil
        }

        mutating func parseNumber() -> String? {
            let start = index
            if peek() == 0x2D { index += 1 }
            guard let first = peek(), isDigit(first) else { return nil }
            while let byte = peek(), isDigit(byte) { index += 1 }
            if peek() == 0x2E {
                index += 1
                guard let digit = peek(), isDigit(digit) else { return nil }
                while let byte = peek(), isDigit(byte) { index += 1 }
            }
            if let byte = peek(), byte == 0x65 || byte == 0x45 {
                index += 1
                if let sign = peek(), sign == 0x2B || sign == 0x2D { index += 1 }
                guard let digit = peek(), isDigit(digit) else { return nil }
                while let byte = peek(), isDigit(byte) { index += 1 }
            }
            return String(decoding: bytes[start..<index], as: UTF8.self)
        }

        mutating func parseLiteral(_ literal: String) -> String? {
            let expected = Array(literal.utf8)
            guard index + expected.count <= bytes.count else { return nil }
            guard Array(bytes[index..<(index + expected.count)]) == expected else { return nil }
            index += expected.count
            return literal
        }

        mutating func readHex4() -> UInt32? {
            guard index + 4 <= bytes.count else { return nil }
            var value: UInt32 = 0
            for _ in 0..<4 {
                guard let byte = peek() else { return nil }
                let digit: UInt32
                switch byte {
                case 0x30...0x39: digit = UInt32(byte - 0x30)
                case 0x41...0x46: digit = UInt32(byte - 0x41 + 10)
                case 0x61...0x66: digit = UInt32(byte - 0x61 + 10)
                default: return nil
                }
                value = value << 4 | digit
                index += 1
            }
            return value
        }

        private func isDigit(_ byte: UInt8) -> Bool {
            byte >= 0x30 && byte <= 0x39
        }

        private func append(scalar value: UInt32, to buffer: inout [UInt8]) {
            if let scalar = Unicode.Scalar(value) {
                buffer.append(contentsOf: Array(String(scalar).utf8))
            }
        }
    }
}
