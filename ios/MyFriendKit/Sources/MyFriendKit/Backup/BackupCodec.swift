import Foundation

/// 备份编解码器：与 Android 的 `BackupCodec`（kotlinx.serialization）逐字段对齐。
///
/// 兼容性取舍写在这里，免得以后有人「顺手优化」掉：
///
/// 1. **写出去的东西不做花活**：紧凑 JSON（不缩进）、值为 nil 的字段不写、
///    非空字段一律写（哪怕等于默认值）—— 这样文件自己说清楚「这一段是什么」，
///    也方便两端以后加字段。Android 侧 `explicitNulls = false`，效果相同。
/// 2. **读进来能松就松**：缺字段补默认值、未知字段忽略、坏条目跳过并记中文警告，
///    只有「格式标记不对 / 版本过高 / 根本不是 JSON」才整体拒绝。
///    这条宽容就是「能读 v1 备份」的全部实现：v1 里那些已经被删掉的开关
///    （记忆开关 / 回复节奏 / 额度 / 每日主动次数）在我们这里根本没有对应字段，
///    解码时自然被当成未知字段忽略，不需要写一行 `if version == 1` 的分支。
///    v1 的人格缺的 9 个细分字段走代码默认值；v1 的多段会话在导入时只留最新一条
///    （见 `BackupService.importBundle`）。
/// 3. **密钥永远不写**：结构里没有密钥字段（见 `BackupApiConfig`），
///    `containsSecretField` 只是导出前的报警器，不是唯一防线。
///
/// 注意 Android 源码里 `writeJson` 实际是 `encodeDefaults = true`（虽然文档写的是 false）：
/// 以实际行为为准 —— 非空字段全部落盘。iOS 这边合成 Encodable 对非可选属性本来就会全写，
/// 对可选属性用 `encodeIfPresent`（nil 不写），正好一致。
public enum BackupCodec {

    public static let formatName: String = backupFormatName
    public static let formatVersion: Int = backupFormatVersion

    /// 导出前的最后一道自检：管线上真的会写这些键名吗？
    ///
    /// 与 Android 的 `SECRET_KEY_MARKERS` 是**同一张表、同一个顺序**：
    /// 结构上已经不可能带密钥了，这一层是「万一以后有人改坏了」的报警器——
    /// 命中就宁可不导出，也不把用户的 Key 写进一个明文文件。
    public static let secretKeyMarkers: [String] = [
        "\"apiKeyCipher\"",
        "\"webToolsApiKeyCipher\"",
        "\"apiKey\"",
        "\"Authorization\"",
        "\"customHeadersJson\""
    ]

    public static func containsSecretField(_ encoded: String) -> Bool {
        secretKeyMarkers.contains { encoded.contains($0) }
    }

    // MARK: - 导出

    /// 紧凑 JSON。键顺序不重要（两端都按 key 读），这里排序只是为了让写进仓库的
    /// fixture 在两次运行之间不抖动，diff 里只出现真正的变化。
    public static func encode(_ bundle: BackupBundle) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes, .sortedKeys]
        let data = try encoder.encode(bundle)
        guard let text = String(data: data, encoding: .utf8) else {
            throw BackupCodecError.notUTF8
        }
        return text
    }

    // MARK: - 导入

    /// 坏 JSON、坏条目、版本过高都在这里被接住，返回中文原因而不是抛异常。
    ///
    /// 文案照抄 Android：用户看见的是同一句话，两端客服/帮助文档不用分裂。
    public static func decode(_ raw: String?) -> BackupDecodeResult {
        guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .failure(reason: "这个文件是空的，里面没有他。")
        }
        guard let root = JSONValueParser.object(from: raw) else {
            return .failure(reason: "这个文件读不出来：不是一份有效的备份 JSON。")
        }

        var warnings: [String] = []

        if let format = root.rawText("format"), format != formatName {
            return .failure(reason: "这不是 MyFriend 的备份文件（format：\(format)）。")
        }

        let version = root.rawInt("formatVersion") ?? formatVersion
        if version > formatVersion {
            return .failure(
                reason: "这份备份来自更新版本的 MyFriend（格式 v\(version)），当前只认到 v\(formatVersion)。"
                    + "先升级 App，再把他接回来；旧版本硬读会把记忆读歪。"
            )
        }
        if version < formatVersion {
            warnings.append("备份格式是 v\(version)，已按 v\(formatVersion) 尽力读出来")
        }
        // v1 → v2 的两处变化值得跟用户说一句：多会话会合并、旧开关不再生效。
        if version < 2 {
            warnings.append("这是 1.9 之前的备份：记忆与节奏的旧开关不再生效，一人只保留最新的一段对话")
        }

        let personas = decodeList(root, key: "personas", label: "人格", warnings: &warnings,
                                  id: { $0.id }, build: BackupPersona.init(json:))
        let memories = decodeList(root, key: "memories", label: "记忆", warnings: &warnings,
                                  id: { $0.id }, build: BackupMemory.init(json:))
        let sessions = decodeList(root, key: "sessions", label: "对话", warnings: &warnings,
                                  id: { $0.id }, build: BackupSession.init(json:))
        let messages = decodeList(root, key: "messages", label: "消息", warnings: &warnings,
                                  id: { $0.id }, build: BackupMessage.init(json:))
        let presets = decodeList(root, key: "presets", label: "预设", warnings: &warnings,
                                 id: { $0.id }, build: BackupPreset.init(json:))
        let rules = decodeList(root, key: "proactiveRules", label: "主动节奏", warnings: &warnings,
                               id: { $0.id }, build: BackupProactiveRule.init(json:))
        let intents = decodeList(root, key: "agentIntents", label: "念头", warnings: &warnings,
                                 id: { $0.id }, build: BackupAgentIntent.init(json:))
        let consolidations = decodeList(root, key: "memoryConsolidations", label: "整理记录",
                                        warnings: &warnings, id: { $0.id },
                                        build: BackupMemoryConsolidation.init(json:))
        let configs = decodeList(root, key: "apiConfigs", label: "API 配置", warnings: &warnings,
                                 id: { $0.id }, build: BackupApiConfig.init(json:))

        let settings = decodeSettings(root, warnings: &warnings)

        let bundle = BackupBundle(
            // 「读懂了」之后，格式标记统一按本机版本记：这样再编码出去的文件总能被任何
            // 同版本 App 读，不会把一份 v0 的老文件一路传下去。
            format: formatName,
            formatVersion: formatVersion,
            appVersion: root.rawText("appVersion") ?? "",
            exportedAt: root.rawInt64("exportedAt") ?? 0,
            apiKeysOmitted: true,
            personas: personas,
            memories: memories,
            sessions: sessions,
            messages: messages,
            presets: presets,
            proactiveRules: rules,
            agentIntents: intents,
            memoryConsolidations: consolidations,
            apiConfigs: configs,
            settings: settings
        )

        if BackupPreview.from(bundle).isEmpty {
            warnings.append("这份备份里没有他：既没有人格也没有记忆和对话")
        }
        return .success(bundle, warnings: warnings)
    }

    // MARK: - 内部

    /// 一段列表的宽容解码：整段读不动就逐条读，能救几条是几条，丢掉的记进 warnings。
    ///
    /// 这里逐条解码而不是先整段解，是因为 Swift 没有「整段失败再回退」的必要：
    /// 每条记录独立解码得到的结果与 Android 的两段式完全一样（缺字段默认、类型错丢弃）。
    private static func decodeList<T>(
        _ root: [String: JSONValue],
        key: String,
        label: String,
        warnings: inout [String],
        id: (T) -> String,
        build: ([String: JSONValue]) throws -> T
    ) -> [T] {
        guard let element = root[key] else { return [] }
        if element.isNull { return [] }
        guard case .array(let items) = element else {
            warnings.append("备份里的「\(key)」不是列表，这一段已跳过")
            return []
        }

        var decoded: [T] = []
        var dropped = 0
        for item in items {
            guard case .object(let object) = item else {
                dropped += 1
                continue
            }
            do {
                decoded.append(try build(object))
            } catch {
                dropped += 1
            }
        }
        if dropped > 0 {
            warnings.append("\(label) 里有 \(dropped) 条读不出来，已跳过")
        }

        let valid = decoded.filter {
            !id($0).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        let blank = decoded.count - valid.count
        if blank > 0 {
            warnings.append("\(label) 里有 \(blank) 条没写 id，已跳过")
        }
        return valid
    }

    /// 设置段：没写就是 nil（绝不清空本机偏好）；写了但读不懂也保持 nil，并记一条警告。
    private static func decodeSettings(
        _ root: [String: JSONValue],
        warnings: inout [String]
    ) -> BackupSettings? {
        guard let element = root["settings"], !element.isNull else { return nil }
        guard case .object(let object) = element else {
            warnings.append("设置这一段读不出来，接回来时设置页保持原样")
            return nil
        }
        do {
            return try BackupSettings(json: object)
        } catch {
            warnings.append("设置这一段读不出来，接回来时设置页保持原样")
            return nil
        }
    }
}

public enum BackupCodecError: Error, LocalizedError, CustomStringConvertible {
    case notUTF8

    public var errorDescription: String? { description }

    public var description: String {
        switch self {
        case .notUTF8:
            return "备份编码失败：内容不是合法的 UTF-8"
        }
    }
}
