import Foundation

// 来源：app/src/main/java/com/myfriend/app/ui/screens/settings/SettingsViewModel.kt 的两个动作
//（`clearChats` → `ChatRepository.clearAllChatData`、`deleteAllData` → 各仓储 wipe + `settings.resetAll`）。
//
// Android 的设置页把「清空」分成两件事，文案写得很清楚：
//
// 1. **清空全部聊天与记忆**（保留人格与 API 配置）：messages / sessions / memories（连带索引）；
// 2. **删除全部本地数据**（人格、会话、记忆、预设、主动唤醒规则、API 配置与设置全部移除）。
//
// 移植时把这两个动作都放在 `DataReset` 里，名字直接对应，不做「一个函数猜用户想清多少」。
//
// 关于「保留 API Key」：Android 的**全部删除**其实会把 api_configs 一起删掉
// （`chatRepository.wipeAllData()` 里最后一句 `apiConfigDao.deleteAllApiConfigs()`），
// 密钥随配置一起消失；真正「保留 Key」的是**清空聊天与记忆**那个动作。
// iOS 这边因此按同一套语义实现：
// - `clearChats()` 不碰 api_configs / Keychain；
// - `clearAll()` 删 api_configs，并在删之前把对应 Keychain 条目清掉（不留孤儿密钥）。
//
// 图片：Android 会 `MediaStorage.clear(context)`。iOS 的图片存沙盒，服务层不碰文件系统
// （那属于 App 层的 Chat/Media 管理），所以这里留 `onClearMedia` 回调，App 层接上即可。

/// 清空动作的结果：给界面一句「删掉了多少」的中文，也方便单测断言。
public struct ClearSummary: Equatable, Sendable {
    public var messages: Int
    public var sessions: Int
    public var memories: Int
    public var personas: Int
    public var presets: Int
    public var rules: Int
    public var intents: Int
    public var consolidations: Int
    public var apiConfigs: Int
    public var usage: Int

    public init(
        messages: Int = 0,
        sessions: Int = 0,
        memories: Int = 0,
        personas: Int = 0,
        presets: Int = 0,
        rules: Int = 0,
        intents: Int = 0,
        consolidations: Int = 0,
        apiConfigs: Int = 0,
        usage: Int = 0
    ) {
        self.messages = messages
        self.sessions = sessions
        self.memories = memories
        self.personas = personas
        self.presets = presets
        self.rules = rules
        self.intents = intents
        self.consolidations = consolidations
        self.apiConfigs = apiConfigs
        self.usage = usage
    }

    public func describeChats() -> String {
        let counts = PluralText.list([
            PluralText.format("%d 段对话", sessions),
            PluralText.format("%d 句话", messages),
            PluralText.format("%d 条记忆", memories)
        ])
        return String(format: String(localized: "所有聊天与记忆已清空（%@）"), counts)
    }

    public func describeAll() -> String {
        var parts = [
            PluralText.format("%d 个人格", personas),
            PluralText.format("%d 条记忆", memories),
            PluralText.format("%d 段对话", sessions),
            PluralText.format("%d 句话", messages)
        ]
        if apiConfigs > 0 { parts.append(PluralText.format("%d 个 API 配置", apiConfigs)) }
        return String(
            format: String(localized: "所有本地数据已删除（%@）"),
            PluralText.list(parts)
        )
    }
}

/// 能一起清掉设置的东西。
///
/// `SettingsStore.reset()` 是「清键」而不是「写默认值」——两者在「以后加字段」时行为不同：
/// 清键能让新字段取到代码里的默认值；写死默认值会把它们钉在旧值上。
public protocol SettingsResetting {
    func resetAll()
}

extension SettingsStore: SettingsResetting {
    public func resetAll() {
        reset()
    }
}

public final class DataReset {

    private let stores: DatabaseStores
    private let settings: SettingsProviding
    private let vectorIndex: MemoryVectorIndex
    /// App 层传 `ApiKeyStore` 进来：全部删除时把配置对应的 Keychain 条目一起清掉。
    private let apiKeys: ApiKeyStore?
    /// App 层传图片目录清理：服务层不碰文件系统。
    private let onClearMedia: (() -> Void)?

    public init(
        stores: DatabaseStores,
        settings: SettingsProviding,
        vectorIndex: MemoryVectorIndex? = nil,
        apiKeys: ApiKeyStore? = nil,
        onClearMedia: (() -> Void)? = nil
    ) {
        self.stores = stores
        self.settings = settings
        self.vectorIndex = vectorIndex ?? MemoryVectorIndex(stores: stores)
        self.apiKeys = apiKeys
        self.onClearMedia = onClearMedia
    }

    // MARK: - 清空聊天与记忆（保留人格与 API 配置）

    /// 与 Android 的「清空全部聊天与记忆」一致：消息、会话、记忆（连带倒排/向量/回声）。
    /// 人格、API 配置、预设、主动规则、用量都留着。
    public func clearChats() throws -> ClearSummary {
        var summary = ClearSummary()
        summary.messages = (try? stores.messages.count()) ?? 0
        summary.sessions = (try? stores.sessions.count()) ?? 0
        summary.memories = (try? stores.memories.count()) ?? 0
        try stores.database.inTransaction {
            try stores.messages.deleteAll()
            try stores.sessions.deleteAll()
            try stores.memories.deleteAll()
            try stores.memoryTerms.deleteAll()
            try stores.memoryVersions.deleteAll()
            MemoryEchoStore(database: stores.database).deleteAll()
            try stores.memoryConsolidations.deleteAll()
            _ = try? stores.memoryVectors.deleteAll()
        }
        onClearMedia?()
        return summary
    }

    // MARK: - 删除全部本地数据

    /// 与 Android 的「删除全部本地数据」一致：
    /// 规则 → 聊天/记忆/人格/API 配置 → 用量 → 预设 → 图片 → 设置。
    ///
    /// 顺序有意义：先清规则与排期，再清聊天/人格，最后清模板与设置。
    /// 任何一步失败都继续往下清（与 Android 的 runCatching per step 同义）：
    /// 用户点的是「都删掉」，不该因为一张表的锁失败就半途停下。
    @discardableResult
    public func clearAll() -> ClearSummary {
        var summary = ClearSummary()
        summary.messages = (try? stores.messages.count()) ?? 0
        summary.sessions = (try? stores.sessions.count()) ?? 0
        summary.memories = (try? stores.memories.count()) ?? 0
        summary.personas = (try? stores.personas.count()) ?? 0
        summary.presets = (try? stores.presets.count()) ?? 0
        summary.rules = (try? stores.proactiveRules.count()) ?? 0
        summary.intents = (try? stores.agentIntents.count()) ?? 0
        summary.consolidations = (try? stores.memoryConsolidations.count()) ?? 0
        summary.apiConfigs = (try? stores.apiConfigs.count()) ?? 0
        summary.usage = (try? stores.apiUsage.count()) ?? 0

        // Keychain 里的密钥随配置一起走：先读 id 再删库，最后清 Keychain。
        let configIds = ((try? stores.apiConfigs.all()) ?? []).map(\.id)

        _ = try? stores.database.inTransaction {
            // 1) 规则与排期
            try? stores.proactiveRules.deleteAll()
            try? stores.agentIntents.deleteAll()
            // 2) 聊天与记忆（子表先走）
            try? stores.messages.deleteAll()
            try? stores.sessions.deleteAll()
            try? stores.memoryTerms.deleteAll()
            try? stores.memoryVersions.deleteAll()
            MemoryEchoStore(database: stores.database).deleteAll()
            try? stores.memories.deleteAll()
            try? stores.memoryConsolidations.deleteAll()
            try? stores.memoryVectors.deleteAll()
            // 3) 人格与配置
            try? stores.personas.deleteAll()
            try? stores.apiConfigs.deleteAll()
            // 4) 用量 / 预设
            try? stores.apiUsage.deleteAll()
            try? stores.presets.deleteAll()
        }
        // 思考留痕（表补上之前是进程内实现）
        AgentTickStore(database: stores.database).deleteAll()

        if let apiKeys {
            for id in configIds {
                try? apiKeys.deleteKeys(for: id)
            }
        }
        onClearMedia?()
        // 设置最后清：清完这里，「正在聊的人 / 心跳进度 / 主题」一起回到出厂。
        if let resetting = settings as? SettingsResetting {
            resetting.resetAll()
        } else {
            settings.save(AppSettings())
        }
        vectorIndex.resetEnsured()
        return summary
    }
}
