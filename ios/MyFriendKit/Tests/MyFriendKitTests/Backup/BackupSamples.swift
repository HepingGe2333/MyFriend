import Foundation
@testable import MyFriendKit

/// iOS 侧的「黄金样本」。
///
/// - `iosBundle()`：**v2**（1.9）结构，写进 `fixtures/backup/ios-export-v2.json`，
///   Android 的 `BackupFixtureTest` 用它验证「iOS 导出的备份 Android 接得住」；
/// - 值全部是 iOS 自己造的（名字、id 都不同），并且**每个可选字段都填上**：
///   「所有可选字段都非 nil」的样本能顺带检查「编码时字段一个不多一个不少」
///   （见 BackupCodecTests 的键集合断言）——漏字段是备份互通最隐蔽的 bug。
///
/// v1 的样本（`ios-export-v1.json` / `android-export-v1.json`）留在仓库里当**输入**：
/// 1.9 必须能读它们（§八「必须能读 v1 备份」），但不再写新的 v1。
enum BackupSamples {

    static let personaId = "ios-persona-1"
    static let sessionId = "ios-session-1"
    static let configId = "ios-config-1"
    static let now: Int64 = 1_795_000_000_000

    /// 完整样本（v2）：9 段全非空 + 设置段在内 + 人格的 9 个细分字段。
    static func iosBundle() -> BackupBundle {
        BackupBundle(
            appVersion: "0.9.1-ios",
            exportedAt: now,
            personas: [
                BackupPersona(
                    id: personaId,
                    name: "沈迟",
                    description: "在旧书店打工的人，说话慢，记性好",
                    avatarUri: "file:///var/mobile/Containers/avatar-shenchi.png",
                    avatarSeed: 12,
                    prompt: "你是沈迟，在旧书店打工，说话慢而具体。",
                    category: "朋友",
                    version: 2,
                    defaultApiConfigId: configId,
                    modelOverride: "deepseek-chat",
                    isDefault: true,
                    createdAt: now - 172_800_000,
                    updatedAt: now,
                    personaCardJson: "{\"identity\":{\"name\":\"沈迟\"},\"traits\":[{\"name\":\"慢热\",\"strength\":0.7}]}",
                    quietSleepMinutes: 80,
                    quietWakeMinutes: 560,
                    mood: "困倦",
                    moodIntensity: 2,
                    moodReason: "昨天理书到很晚",
                    moodUpdatedAt: now - 3_600_000,
                    moodNudgedAt: now - 1_800_000,
                    traits: ["慢热", "嘴硬心软"],
                    likes: ["夜里散步", "旧书"],
                    behaviors: ["先听完再给建议"],
                    backstory: "在旧书店长大，父母都是老师。十七岁那年离家，很久没回去。",
                    quirks: "叫他「阿迟」；说话结尾爱加「大概吧」；不要提他父亲。",
                    proactiveLevel: 4,
                    memoryRetention: 5,
                    extractionEvery: 9,
                    sleepEnabled: true,
                    wakeThreshold: 4,
                    dndOnSleep: true
                )
            ],
            memories: [
                BackupMemory(
                    id: "ios-memory-1",
                    personaId: personaId,
                    content: "你提过奶奶留下的那本《诗经》扉页上有一行铅笔字，你一直没舍得擦。",
                    category: "EVENT",
                    importance: 88,
                    pinned: true,
                    sourceSessionId: sessionId,
                    createdAt: now - 7_200_000,
                    updatedAt: now - 3_600_000,
                    lastUsedAt: now - 1_200_000,
                    useCount: 5,
                    drawer: "USER",
                    sourceQuote: "那行字是我奶奶写的",
                    status: "DORMANT",
                    strength: 0.73,
                    archivedAt: now - 1_000_000,
                    consolidatedAt: now - 900_000,
                    tags: "[\"诗经\",\"奶奶\",\"旧书\"]",
                    happenedAt: now - 20_000_000,
                    originalContent: "对方说奶奶在《诗经》扉页写过字，不让擦。",
                    driftCount: 2
                )
            ],
            sessions: [
                BackupSession(
                    id: sessionId,
                    personaId: personaId,
                    apiConfigId: configId,
                    model: "deepseek-chat",
                    title: "旧书店的下午",
                    lastMessagePreview: "那我给你留着",
                    lastMessageAt: now,
                    unreadCount: 1,
                    pinned: true,
                    createdAt: now - 7_200_000,
                    updatedAt: now,
                    lastPersonaVersion: 2,
                    // 不再有 syncState / synced* / serverSessionId / lastSync* 这 6 个字段：
                    // 它们只在 iOS 库里，Android 的 BackupSession 没有（0.10.2 安全审计 P1-5）。
                    lastMemoryExtractedCount: 9,
                    summary: "你聊到奶奶留下的旧书，他答应替你记着。",
                    summarizedUpTo: now - 1_200_000,
                    summaryAt: now - 1_000_000,
                    memoryRetentionOverride: 2,
                    extractionEveryOverride: 12
                )
            ],
            messages: [
                BackupMessage(
                    id: "ios-msg-1",
                    sessionId: sessionId,
                    personaId: personaId,
                    role: "USER",
                    content: "我奶奶在那本《诗经》上写过一行字。",
                    createdAt: now - 7_200_000,
                    updatedAt: now - 7_200_000,
                    status: "COMPLETE",
                    edited: false,
                    readAt: now - 7_100_000
                ),
                BackupMessage(
                    id: "ios-msg-2",
                    sessionId: sessionId,
                    personaId: personaId,
                    role: "ASSISTANT",
                    content: "那我给你留着，别擦。",
                    createdAt: now - 7_100_000,
                    updatedAt: now - 7_100_000,
                    status: "COMPLETE",
                    model: "deepseek-chat",
                    edited: true,
                    attachmentsJson: "[{\"uri\":\"file:///var/mobile/Containers/book.jpg\",\"mime\":\"image/jpeg\"}]",
                    toolTraceJson: "[{\"name\":\"web_search\",\"ok\":true}]"
                ),
                BackupMessage(
                    id: "ios-msg-3",
                    sessionId: sessionId,
                    personaId: personaId,
                    role: "ASSISTANT",
                    content: "",
                    createdAt: now - 7_000_000,
                    updatedAt: now - 7_000_000,
                    status: "ERROR",
                    errorMessage: "网络断开",
                    model: "deepseek-chat",
                    edited: false
                )
            ],
            presets: [
                BackupPreset(
                    id: "ios-preset-1",
                    name: "慢热的朋友",
                    description: "话少，但记得住细节",
                    prompt: "你说话慢，先回应再展开。",
                    category: "朋友",
                    avatarUri: "file:///var/mobile/Containers/preset.png",
                    avatarSeed: 4,
                    createdAt: now - 259_200_000,
                    updatedAt: now - 86_400_000
                )
            ],
            proactiveRules: [
                BackupProactiveRule(
                    id: "ios-rule-daily",
                    personaId: personaId,
                    sessionId: sessionId,
                    kind: "DAILY",
                    timeOfDayMinutes: 1290,
                    intervalMinutes: nil,
                    hint: "问问他那本书看完了没",
                    note: "别像查岗",
                    enabled: true,
                    pending: false,
                    source: "MANUAL",
                    nextTriggerAt: now + 3_600_000,
                    lastTriggeredAt: now - 86_400_000,
                    lastError: "上次推送被系统折叠",
                    createdAt: now - 172_800_000,
                    updatedAt: now - 86_400_000
                ),
                BackupProactiveRule(
                    id: "ios-rule-interval",
                    personaId: personaId,
                    sessionId: nil,
                    kind: "INTERVAL",
                    timeOfDayMinutes: nil,
                    intervalMinutes: 240,
                    hint: "隔一阵问问他在干嘛",
                    note: "",
                    enabled: false,
                    pending: true,
                    source: "AI",
                    nextTriggerAt: now + 7_200_000,
                    lastTriggeredAt: nil,
                    lastError: nil,
                    createdAt: now - 172_800_000,
                    updatedAt: now - 172_800_000
                )
            ],
            agentIntents: [
                BackupAgentIntent(
                    id: "ios-intent-1",
                    personaId: personaId,
                    sessionId: sessionId,
                    kind: "FOLLOW_UP",
                    earliestAt: now + 3_600_000,
                    latestAt: now + 5_400_000,
                    note: "想问问他那行字还在不在",
                    hint: "别太准点",
                    status: "PENDING",
                    ruleId: "ios-rule-daily",
                    createdAt: now - 1_800_000,
                    sentAt: now - 1_700_000,
                    lastError: "上一次发送超时"
                )
            ],
            memoryConsolidations: [
                BackupMemoryConsolidation(
                    id: "ios-consolidation-1",
                    personaId: personaId,
                    at: now - 3_600_000,
                    kept: 9,
                    merged: 2,
                    archived: 1,
                    forgotten: 0,
                    summary: "把两条关于旧书的记忆并成了一条",
                    modelName: "deepseek-chat"
                )
            ],
            apiConfigs: [
                BackupApiConfig(
                    id: configId,
                    name: "DeepSeek 官方",
                    provider: "OPENAI",
                    baseUrl: "https://api.deepseek.com",
                    model: "deepseek-chat",
                    allowInsecureHttp: true,
                    temperature: 0.8,
                    topP: 0.95,
                    maxOutputTokens: 2048,
                    frequencyPenalty: 0.1,
                    presencePenalty: 0.2,
                    webToolsEnabled: true,
                    webToolsBaseUrl: "https://api.deepseek.com",
                    createdAt: now - 172_800_000,
                    updatedAt: now - 86_400_000
                )
            ],
            // 1.9 的设置只剩外观与「常驻思考」：记忆/节奏/额度/主动次数都不再写。
            settings: BackupSettings(
                themeMode: "DARK",
                accentColor: 0xFF4F7F92,
                fontScale: 1.15,
                bubbleStyle: "COMPACT",
                animationsEnabled: false,
                hapticsEnabled: false,
                chatBackgroundColor: 0xFF191A18,
                agentLoopEnabled: false,
                activePersonaId: personaId
            )
        )
    }
}
