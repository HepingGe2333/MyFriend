import Foundation
@testable import YapKit

/// iOS 侧的「黄金样本」。
///
/// - `iosBundle()`：**v7**（0.15，schema v19）结构，写进 `fixtures/backup/ios-export-v7.json`，
///   Android 的 `BackupFixtureTest` 用它验证「iOS 导出的备份 Android 接得住」；
///   `ios-export-v6.json` 与更早的样本从那以后是**只读输入**（证明旧版本仍然能读），
///   不再重写；
/// - 值全部是 iOS 自己造的（名字、id 都不同），并且**每个可选字段都填上**：
///   「所有可选字段都非 nil」的样本能顺带检查「编码时字段一个不多一个不少」
///   （见 BackupCodecTests 的键集合断言）——漏字段是备份互通最隐蔽的 bug。
/// - 图片用**真的** 2×2 小图（jpeg 632 字节 / png 75 字节）：base64 是标准字母表、
///   带 padding、不换行，两端都能真的解码出一张图，而不是一段假字符串。
///
/// v1/v2 的样本（`ios-export-v1.json` / `android-export-v1.json` / `*-export-v2.json`）
/// 留在仓库里当**输入**：v4 必须能读它们（「必须能读旧备份」），但不再写新的 v1/v2/v3。
enum BackupSamples {

    /// 样本里那张 2×2 的 jpeg（632 字节）。
    static let sampleJPEGBase64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikxMC4pLSwzOko+MzZGNywtQFdBRkxOUlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0//wAARCAACAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwCeiiivMPZP/9k="
    /// 样本里那张 2×2 的 png（75 字节）。
    static let samplePNGBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEklEQVR4nGM8URHAwMDAxAAGABNiAZSfXW4zAAAAAElFTkSuQmCC"

    /// 样本头像的**裸文件名**（v3 的关联键）：`personas.avatarUri` 与 `media[].fileName` 一致。
    static let personaAvatarFileName = "avatar-ios-sample.jpg"
    /// 样本聊天图片的**裸文件名**：`messages.attachmentsJson[].uri` 与 `media[].fileName` 一致。
    static let chatImageFileName = "chatImage-ios-sample.png"
    /// v6：样本表情 / 语音 / 视频的**裸文件名**（关联键同上）。
    static let stickerFileName = "sticker-ios-sample.gif"
    static let audioFileName = "audio-ios-sample.m4a"
    static let videoFileName = "video-ios-sample.mp4"

    /// 1×1 的 GIF（43 字节）：表情必须是真 GIF，Android 侧才能真的解出一张图。
    static let sampleGIFBase64 = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
    /// 语音/视频的样本字节：备份格式只要求「base64 → 原始字节数一致」，
    /// 不必是能播放的容器（真有内容的样本会让 fixture 大几十倍，而格式完全相同）。
    static let sampleAudioBase64 = "bW9jay1hdWRpby1ieXRlcw=="   // "mock-audio-bytes"（16 字节）
    static let sampleVideoBase64 = "bW9jay12aWRlby1ieXRlcw=="   // "mock-video-bytes"（16 字节）

    static let personaId = "ios-persona-1"
    static let sessionId = "ios-session-1"
    static let configId = "ios-config-1"
    static let now: Int64 = 1_795_000_000_000

    /// v7：对样本的 base64 字节算 sha256（不写死字符串：媒体内容一改，哈希自动跟着变）。
    static func sha256Hex(_ base64: String) -> String {
        ImageContentHash.sha256Hex(Data(base64Encoded: base64) ?? Data())
    }

    /// 完整样本（v7）：全部段非空 + 设置段在内 + 拉黑/心理活动/会话备忘录/媒体哈希。
    static func iosBundle() -> BackupBundle {
        BackupBundle(
            appVersion: "0.15.0-ios",
            exportedAt: now,
            personas: [
                BackupPersona(
                    id: personaId,
                    name: "沈迟",
                    description: "在旧书店打工的人，说话慢，记性好",
                    // v3：只写裸文件名（关联键），二进制在 media 里。
                    avatarUri: personaAvatarFileName,
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
                    dndOnSleep: true,
                    // v5：人设引导三列，全部非空 —— 「所有可选字段都填上」这条规矩同样适用。
                    worldJson: #"{"description":"临港新城的老城区，事务所常年加班。","era":"当代，沿海城市，2026 年","obligations":["按节点交图"],"relationships":[{"name":"老周","note":"催图直接","relation":"合伙人"}],"schedule":[{"activity":"在旧书店理书","day":"工作日","end":"18:00","start":"09:00"}],"worldName":"临港新城·旧书店"}"#,
                    speechStyleJson: #"{"avoid":["长篇大论"],"habits":["句子很短"],"phrases":["在忙，晚点说","嗯，知道了"],"source":"model","tone":"简短直接，偏冷淡务实"}"#,
                    activityJson: #"{"activity":"在整理新到的一批旧书","availability":"LIMITED","kind":"WORK","minutes":60,"startedAt":1795000000000,"until":1795003600000}"#,
                    // v7：人格自己的提示词语言（0.15）。
                    languageTag: "zh",
                    // v7：拉黑状态（到期时间 / 原因 / 未确认提议）。样本里故意都填上：
                    // 「所有可选字段都有值」才能查出编码时漏字段。
                    blockedUntil: now + 6 * 3_600_000,
                    blockReason: "想安静一会儿，别追问",
                    blockProposalJson: #"{"proposalId":"ios-proposal-1","personaId":"ios-persona-1","hours":6,"reason":"你说了那句话","userMessageId":"ios-msg-1","proposedAt":1794999000000,"expiresAt":1794999600000}"#
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
                    // v3：附件 uri 是裸文件名，本体在 media 的 CHAT_IMAGE 里。
                    // v6：同一条消息还可以带一段语音；`kind` 与 `durationMs` 一起带走
                    //（服务端不下发这两样，丢了气泡上就永远是 --:--）。
                    attachmentsJson: "[{\"uri\":\"chatImage-ios-sample.png\",\"mime\":\"image/png\",\"width\":2,\"height\":2,\"kind\":\"IMAGE\"},{\"uri\":\"audio-ios-sample.m4a\",\"mime\":\"audio/m4a\",\"kind\":\"AUDIO\",\"durationMs\":7000}]",
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
                ),
                // v7：拉黑期间用户发来的消息（`blocked=true`，模型永久看不到）。
                BackupMessage(
                    id: "ios-msg-4",
                    sessionId: sessionId,
                    personaId: personaId,
                    role: "USER",
                    content: "你在吗？",
                    createdAt: now - 6_900_000,
                    updatedAt: now - 6_900_000,
                    status: "COMPLETE",
                    edited: false,
                    blocked: true
                )
            ],
            // v3：两张图（人格头像 jpeg + 聊天图片 png）。fileName 是关联键，
            // data 是标准 base64（带 padding、不换行），bytes 是解出来的原始字节数。
            media: [
                BackupMedia(
                    id: "ios-media-1",
                    kind: "PERSONA_AVATAR",
                    personaId: personaId,
                    fileName: personaAvatarFileName,
                    mime: "image/jpeg",
                    bytes: 632,
                    data: sampleJPEGBase64,
                    sha256: sha256Hex(sampleJPEGBase64)
                ),
                BackupMedia(
                    id: "ios-media-2",
                    kind: "CHAT_IMAGE",
                    personaId: personaId,
                    fileName: chatImageFileName,
                    mime: "image/png",
                    bytes: 75,
                    data: samplePNGBase64,
                    sha256: sha256Hex(samplePNGBase64)
                ),
                // v6：表情 / 语音 / 视频。kind 是新的三个取值，旧版本读到会跳过并计数。
                BackupMedia(
                    id: "ios-media-3",
                    kind: "STICKER",
                    personaId: personaId,
                    fileName: stickerFileName,
                    mime: "image/gif",
                    bytes: 42,
                    data: sampleGIFBase64,
                    sha256: sha256Hex(sampleGIFBase64)
                ),
                BackupMedia(
                    id: "ios-media-4",
                    kind: "AUDIO",
                    personaId: personaId,
                    fileName: audioFileName,
                    mime: "audio/m4a",
                    bytes: 16,
                    data: sampleAudioBase64,
                    sha256: sha256Hex(sampleAudioBase64)
                ),
                BackupMedia(
                    id: "ios-media-5",
                    kind: "VIDEO",
                    personaId: personaId,
                    fileName: videoFileName,
                    mime: "video/mp4",
                    bytes: 16,
                    data: sampleVideoBase64,
                    sha256: sha256Hex(sampleVideoBase64)
                )
            ],
            presets: [
                BackupPreset(
                    id: "ios-preset-1",
                    name: "慢热的朋友",
                    description: "话少，但记得住细节",
                    prompt: "你说话慢，先回应再展开。",
                    category: "朋友",
                    // 预设头像与人格头像指向同一张图（现实里「从人格另存为预设」就是这样）。
                    avatarUri: personaAvatarFileName,
                    avatarSeed: 4,
                    createdAt: now - 259_200_000,
                    updatedAt: now - 86_400_000,
                    // v5：预设也带世界与话风（应用到人格时复制过去）。
                    worldJson: #"{"description":"旧书店所在的街区。","era":"当代","obligations":[],"relationships":[],"schedule":[],"worldName":"临港新城·旧书店"}"#,
                    speechStyleJson: #"{"avoid":[],"habits":["少用感叹号"],"phrases":["大概吧"],"source":"manual","tone":"慢，具体"}"#
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
            // v6：一张表情（文件在 media 的 STICKER 里）。
            stickers: [
                BackupSticker(
                    id: "ios-sticker-1",
                    ownerUserId: "ios-owner-1",
                    personaId: personaId,
                    file: stickerFileName,
                    mime: "image/gif",
                    width: 1,
                    height: 1,
                    frames: 1,
                    bytes: 42,
                    source: "AI",
                    createdBy: "PERSONA",
                    createdAt: now - 5_400_000
                )
            ],
            // v6：两条日记便条 —— 一条带提醒（已经提醒过）、一条不提醒。
            diaryNotes: [
                BackupDiaryNote(
                    id: "ios-diary-1",
                    personaId: personaId,
                    content: "明天记得问他那本书看完了没",
                    createdAt: now - 3_600_000,
                    remindAt: now + 72_000_000,
                    remindedAt: nil,
                    updatedAt: now - 3_600_000
                ),
                BackupDiaryNote(
                    id: "ios-diary-2",
                    personaId: personaId,
                    content: "他说过奶奶的生日是腊月十七，别忘了",
                    createdAt: now - 86_400_000,
                    remindAt: now - 82_800_000,
                    remindedAt: now - 82_700_000,
                    updatedAt: now - 82_700_000
                )
            ],
            // v7：心理活动（私密念头 + 当时心情快照）与会话备忘录（含 0.15 主题总结）。
            mentalActivities: [
                BackupMentalActivity(
                    id: "ios-mental-1",
                    personaId: personaId,
                    sessionId: sessionId,
                    at: now - 60_000,
                    thought: "他刚才那句话让我有点想笑",
                    moodLabel: "轻快",
                    moodIntensity: 3,
                    moodValence: 1,
                    moodArousal: 3,
                    moodDominance: 4
                ),
                BackupMentalActivity(
                    id: "ios-mental-2",
                    personaId: personaId,
                    sessionId: nil,
                    at: now - 300_000,
                    thought: "待会儿想问问他那本书看完了没",
                    moodLabel: "平静",
                    moodIntensity: 2,
                    moodValence: 0,
                    moodArousal: 1,
                    moodDominance: 2
                )
            ],
            sessionMemory: [
                BackupSessionMemory(
                    sessionId: sessionId,
                    personaId: personaId,
                    shortSummary: "你聊到奶奶留下的旧书，他答应替你记着。",
                    shortUpdatedAt: now - 120_000,
                    shortUserMessages: 5,
                    longSummary: "你们认识三年了，他一直在旧书店。",
                    longUpdatedAt: now - 3_600_000,
                    longUserMessages: 40,
                    longCheckedUserMessages: 0,
                    topics: [
                        BackupTopicSegment(timeLabel: "2026-11-18 晚", summary: "奶奶的《诗经》与那行铅笔字"),
                        BackupTopicSegment(timeLabel: "2026-11-19 早上", summary: "他说旧书店下周要盘点")
                    ],
                    topicUpdatedAt: now - 90_000,
                    topicUserMessages: 30,
                    topicCheckedUserMessages: 0
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
                    // v4：样本刻意用一个**非默认**的 provider，证明「导出总是写」，
                    // 而不是靠缺字段默认值蒙混过关。
                    webToolsProvider: "TAVILY",
                    webToolsBaseUrl: "https://api.tavily.com",
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
