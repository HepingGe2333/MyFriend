import Foundation
import XCTest
@testable import MyFriendKit

/// 跨端备份的「硬要求」：Android 导出的备份 iOS 能读、iOS 导出的备份 Android 能读。
///
/// 两端在 `fixtures/backup/` 里各放一份黄金样本：
/// - `android-export-v1.json` / `ios-export-v1.json`：1.8 时的形状；
/// - `android-export-v2.json` / `ios-export-v2.json`：1.9 时的形状。
///   v1/v2 都是**只读输入**：永远不重写，用来钉住「必须能读旧备份」；
/// - `android-export-v3.json`：Android 的 `BackupFixtureTest` 生成（v3，带两张图片的
///   base64），这里只读；它可能比 iOS 这一版晚到，所以缺失时跳过而不是红掉；
/// - `ios-export-v4.json`：v4 的历史样本，只读（证明 v4 仍然能读）。
/// - `ios-export-v5.json`：由**这个测试**生成（v5，带世界/话风/活动），Android 侧会读它。
/// - `android-export-v4.json` / `android-export-v5.json`：Android 的样本，只读；缺失时跳过。
///
/// 谁改了备份格式而没改另一端，任何一边的测试都会当场变红。
final class BackupInteropTests: XCTestCase {

    private func fixtureDirectory() throws -> URL {
        try XCTUnwrap(
            RepoLayout.backupFixtureDirectory(),
            "找不到仓库里的 fixtures/backup 目录；swift test 的工作目录与测试源码都在仓库内时应当能找到"
        )
    }

    private func fixtureText(_ name: String) throws -> String {
        let url = try fixtureDirectory().appendingPathComponent(name)
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func decodeSuccess(_ raw: String, allowWarnings: Bool = false,
                               file: StaticString = #filePath, line: UInt = #line) throws -> BackupBundle {
        let result = BackupCodec.decode(raw)
        let bundle = try XCTUnwrap(
            result.bundle,
            "应该能读懂，实际：\(result.failureReason ?? "?")",
            file: file, line: line
        )
        if !allowWarnings {
            XCTAssertTrue(result.warnings.isEmpty, "不该有警告，实际：\(result.warnings)",
                          file: file, line: line)
        }
        return bundle
    }

    // MARK: - v1 → v2：老备份必须还能读

    func testAndroidV1FixtureStillDecodes() throws {
        let raw = try fixtureText("android-export-v1.json")
        // v1 → v2 会**故意**留一句「旧开关不再生效、一人只留最新一段」的警告。
        let bundle = try decodeSuccess(raw, allowWarnings: true)

        XCTAssertEqual(bundle.format, "myfriend.backup")
        // 读进来统一按本机版本记：再导出就是 v4。
        XCTAssertEqual(bundle.formatVersion, BackupCodec.formatVersion)
        XCTAssertEqual(bundle.appVersion, "1.7.0")
        XCTAssertEqual(bundle.exportedAt, 1_794_500_000_000)
        XCTAssertTrue(bundle.apiKeysOmitted)

        // 各段数量：与 Android `BackupFixtureTest.sampleBundle()` 一一对应。
        XCTAssertEqual(bundle.personas.count, 1)
        XCTAssertEqual(bundle.memories.count, 1)
        XCTAssertEqual(bundle.sessions.count, 1)
        XCTAssertEqual(bundle.messages.count, 2)
        XCTAssertEqual(bundle.presets.count, 1)
        XCTAssertEqual(bundle.proactiveRules.count, 1)
        XCTAssertEqual(bundle.agentIntents.count, 1)
        XCTAssertEqual(bundle.memoryConsolidations.count, 1)
        XCTAssertEqual(bundle.apiConfigs.count, 1)

        let persona = try XCTUnwrap(bundle.personas.first)
        XCTAssertEqual(persona.name, "林深")
        XCTAssertEqual(persona.quietSleepMinutes, 90)
        XCTAssertEqual(persona.quietWakeMinutes, 540)
        XCTAssertEqual(persona.mood, "平静")
        // v1 没有 1.9 的细分字段：走默认值，不报错（§三、§八）。
        XCTAssertEqual(persona.traits, [])
        XCTAssertEqual(persona.proactiveLevel, 3)
        XCTAssertTrue(persona.sleepEnabled)

        let memory = try XCTUnwrap(bundle.memories.first)
        XCTAssertEqual(memory.tags, "[\"哈尔滨\",\"怕冷\"]")

        let session = try XCTUnwrap(bundle.sessions.first)
        XCTAssertEqual(session.summary, "你提过下个月要去哈尔滨，怕冷。")
        XCTAssertNil(session.extractionEveryOverride)

        // 消息没有 readAt：读成 nil（「还没被看到」）。
        XCTAssertNil(bundle.messages.first?.readAt)

        // v1 的设置里有一串已经被删掉的开关：它们连解码都不会发生（§八「v1 的开关字段忽略」）。
        let settings = try XCTUnwrap(bundle.settings)
        XCTAssertEqual(settings.themeMode, "SYSTEM")
        XCTAssertEqual(settings.activePersonaId, "11111111-1111-1111-1111-111111111111")

        XCTAssertFalse(BackupCodec.containsSecretField(raw))
    }

    /// v1 的样本读进来、再导出，必须是 v4 的形状（不再带着那串旧开关）。
    func testV1ImportReExportsAsV3() throws {
        let raw = try fixtureText("android-export-v1.json")
        let bundle = try decodeSuccess(raw, allowWarnings: true)
        let reEncoded = try BackupCodec.encode(bundle)

        XCTAssertTrue(reEncoded.contains("\"formatVersion\":\(BackupCodec.formatVersion)"))
        // v1 没有 media：再导出时是一个空数组（这个键永远存在）。
        XCTAssertTrue(reEncoded.contains("\"media\":[]"))
        for removed in ["replyPace", "memoryEnabled", "autoMemoryEnabled",
                        "memoryConsolidationEnabled", "webSearchScoringEnabled",
                        "proactiveDailyLimit", "monthlyBudgetMicroUsd",
                        "overBudgetChatOnly", "inputPriceUsdPerMillion",
                        "outputPriceUsdPerMillion", "harnessMode", "harnessUrl",
                        "harnessSyncPath"] {
            XCTAssertFalse(reEncoded.contains("\"\(removed)\""), "v4 里不该再有 \(removed)")
        }
        // 再读一遍仍然稳定：解→编→解 幂等。
        let second = try decodeSuccess(reEncoded)
        XCTAssertEqual(bundle, second)
    }

    func testIOSV1FixtureStillDecodes() throws {
        let raw = try fixtureText("ios-export-v1.json")
        let bundle = try decodeSuccess(raw, allowWarnings: true)
        XCTAssertEqual(bundle.formatVersion, BackupCodec.formatVersion)
        XCTAssertFalse(bundle.personas.isEmpty)
        XCTAssertFalse(bundle.messages.isEmpty)
    }

    // MARK: - Android v2 → iOS（旧样本，只读；缺失时跳过）

    func testAndroidV2FixtureDecodesWhenPresent() throws {
        let url = try fixtureDirectory().appendingPathComponent("android-export-v2.json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("android-export-v2.json 还不存在（历史样本可以缺席）")
        }
        let raw = try String(contentsOf: url, encoding: .utf8)
        // v2 备份在 v4 里会多一句「格式是 v2，已按 v4 尽力读出来」的警告：允许。
        let bundle = try decodeSuccess(raw, allowWarnings: true)

        XCTAssertEqual(bundle.format, "myfriend.backup")
        // 读进来统一按本机版本记（v1/v2 的文件不会把 v2 一路传下去）。
        XCTAssertEqual(bundle.formatVersion, BackupCodec.formatVersion)
        XCTAssertTrue(bundle.media.isEmpty, "v2 备份里没有 media，读出来是空数组")
        XCTAssertFalse(bundle.personas.isEmpty, "v2 样本至少要有一个人格")
        XCTAssertFalse(BackupCodec.containsSecretField(raw))

        // 一人一段（§八）：Android 的导出不应该再出现同一人格的两段会话。
        let personaIds = bundle.sessions.map(\.personaId)
        XCTAssertEqual(Set(personaIds).count, personaIds.count,
                       "sessions 每人一条：同一个 personaId 出现了两次")

        // 1.9 的细分字段真的能跨端读出来：这是「两端字段名与编码必须一致」的现场证据。
        //
        // 这里刻意**不**钉死具体文案（人家的样本随时会改一句）：只钉结构——
        // 列表字段解出来是「字符串数组」、档位落在合法区间、开关读成 Bool。
        // 数值越界、类型读歪（比如列表被写成一段字符串）都会在这里红。
        let persona = try XCTUnwrap(bundle.personas.first)
        XCTAssertFalse(persona.traits.isEmpty, "traits 应当被解成数组")
        XCTAssertLessThanOrEqual(persona.traits.count, PersonaFieldLimits.maxTraits)
        XCTAssertLessThanOrEqual(persona.likes.count, PersonaFieldLimits.maxLikes)
        XCTAssertLessThanOrEqual(persona.behaviors.count, PersonaFieldLimits.maxBehaviors)
        XCTAssertTrue(PersonaFieldLimits.levelRange.contains(persona.proactiveLevel))
        XCTAssertTrue(PersonaFieldLimits.levelRange.contains(persona.memoryRetention))
        XCTAssertTrue(PersonaFieldLimits.extractionEveryRange.contains(persona.extractionEvery))
        XCTAssertTrue(PersonaFieldLimits.wakeThresholdRange.contains(persona.wakeThreshold))
        XCTAssertFalse(persona.backstory?.isEmpty ?? true, "v2 样本带了成长经历")
        XCTAssertFalse(persona.quirks?.isEmpty ?? true, "v2 样本带了个人设定")

        // 会话覆盖与消息已读也在。
        let session = try XCTUnwrap(bundle.sessions.first)
        XCTAssertNotNil(session.memoryRetentionOverride)
        XCTAssertNotNil(session.extractionEveryOverride)
        XCTAssertNotNil(bundle.messages.first?.readAt, "v2 的第一条消息带了已读时间")

        // 设置段读得出来，而且只剩 1.9 的那几项（解码器不会凭空造字段）。
        let settings = try XCTUnwrap(bundle.settings)
        XCTAssertFalse(settings.themeMode.isEmpty)
    }

    // MARK: - Android v3 → iOS：图片二进制真的能跨端解码

    /// Android 的 v3 样本：`media` 里的两张图必须真的能解码、字节数与声明一致，
    /// 而且头像 / 附件的裸文件名都能在 `media` 里找到对应条目（关联键对得上）。
    func testAndroidV3FixtureDecodesWhenPresent() throws {
        let url = try fixtureDirectory().appendingPathComponent("android-export-v3.json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            // 两端并行实现：Android 的 v3 样本可能晚到。缺它就跳过，报告里会写清楚。
            throw XCTSkip("android-export-v3.json 还不存在（Android 端尚未生成 v3 黄金样本）")
        }
        let raw = try String(contentsOf: url, encoding: .utf8)
        // v3 文件按 v4 读会有一句「格式是 v3，已按 v4 尽力读出来」的警告：允许。
        let bundle = try decodeSuccess(raw, allowWarnings: true)

        XCTAssertEqual(bundle.format, "myfriend.backup")
        XCTAssertEqual(bundle.formatVersion, BackupCodec.formatVersion)
        XCTAssertFalse(bundle.media.isEmpty, "v3 样本必须带 media")
        // v1–v3 里没有 webToolsProvider：读成默认 MESSAGEAPI（这就是「旧备份仍能读」）。
        XCTAssertEqual(bundle.apiConfigs.first?.webToolsProvider, "MESSAGEAPI")
        XCTAssertFalse(BackupCodec.containsSecretField(raw))

        // 每一条都真的能解出一张图：bytes 是声明值，也是 `data` 解出来的字节数。
        var fileNames = Set<String>()
        for media in bundle.media {
            XCTAssertTrue(
                ["PERSONA_AVATAR", "USER_AVATAR", "CHAT_IMAGE"].contains(media.kind),
                "不认识的 kind：\(media.kind)"
            )
            XCTAssertTrue(
                ["image/jpeg", "image/png", "image/webp"].contains(media.mime),
                "不支持的 mime：\(media.mime)"
            )
            XCTAssertTrue(BackupMediaTransfer.isSafeFileName(media.fileName), media.fileName)
            XCTAssertGreaterThan(media.bytes, 0)
            XCTAssertLessThanOrEqual(media.bytes, 8 * 1024 * 1024)
            let decoded = try XCTUnwrap(
                BackupMediaTransfer.decode(media.data),
                "base64 解不出来：\(media.fileName)"
            )
            XCTAssertEqual(decoded.count, media.bytes, "\(media.fileName) 的 bytes 与解出来的字节数不一致")
            XCTAssertEqual(BackupMediaTransfer.mime(forFileName: media.fileName), media.mime)
            fileNames.insert(media.fileName)
        }

        // 关联键：头像与附件里写的裸文件名，必须都能在 media 里找到。
        for persona in bundle.personas {
            guard let avatarUri = persona.avatarUri else { continue }
            XCTAssertFalse(avatarUri.contains("/"), "v3 的头像只写裸文件名：\(avatarUri)")
            XCTAssertTrue(fileNames.contains(avatarUri), "头像 \(avatarUri) 在 media 里找不到")
        }
        for preset in bundle.presets {
            guard let avatarUri = preset.avatarUri else { continue }
            XCTAssertFalse(avatarUri.contains("/"))
            XCTAssertTrue(fileNames.contains(avatarUri), "预设头像 \(avatarUri) 在 media 里找不到")
        }
        for message in bundle.messages {
            for attachment in AttachmentsCodec.decode(message.attachmentsJson) {
                XCTAssertFalse(attachment.uri.contains("/"), "v3 的附件 uri 只写裸文件名：\(attachment.uri)")
                XCTAssertTrue(fileNames.contains(attachment.uri), "附件 \(attachment.uri) 在 media 里找不到")
            }
        }

        // 再读一遍仍然稳定：解→编→解 幂等（base64 不会被重新排版）。
        let reEncoded = try BackupCodec.encode(bundle)
        XCTAssertEqual(try decodeSuccess(reEncoded), bundle)
    }

    // MARK: - Android v4 → iOS：搜索服务选择能跨端读

    /// Android 的 v4 样本：`apiConfigs[].webToolsProvider` 必须读得出来。
    ///
    /// 两端并行实现，Android 的 v4 样本可能晚到：缺文件就跳过并说明（不是静默通过）。
    func testAndroidV4FixtureDecodesWhenPresent() throws {
        let url = try fixtureDirectory().appendingPathComponent("android-export-v4.json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("android-export-v4.json 还不存在（Android 端尚未生成 v4 黄金样本）")
        }
        let raw = try String(contentsOf: url, encoding: .utf8)
        // v4 按 v5 读会有一句「格式是 v4，已按 v5 尽力读出来」的警告：允许。
        let bundle = try decodeSuccess(raw, allowWarnings: true)

        XCTAssertEqual(bundle.format, "myfriend.backup")
        XCTAssertEqual(bundle.formatVersion, BackupCodec.formatVersion)
        XCTAssertFalse(BackupCodec.containsSecretField(raw), "v4 备份同样不能带密钥")
        XCTAssertFalse(bundle.apiConfigs.isEmpty, "样本至少要有一条 API 配置")
        for config in bundle.apiConfigs {
            XCTAssertTrue(
                WebToolsProvider.allCases.map(\.rawValue).contains(config.webToolsProvider),
                "不认识的 webToolsProvider：\(config.webToolsProvider)"
            )
        }
        // 至少有一条不是默认值，才能证明「跨端真的传了 provider」而不是靠默认值蒙对。
        XCTAssertTrue(
            bundle.apiConfigs.contains { $0.webToolsProvider != WebToolsProvider.messageAPI.rawValue },
            "Android 的 v4 样本里应当有一条非 MESSAGEAPI 的配置"
        )
    }

    // MARK: - Android v5 → iOS：人设引导三列能跨端读

    /// Android 的 v5 样本：人格的世界 / 话风 / 活动、预设的世界 / 话风都要读得出来。
    ///
    /// 两端并行实现，Android 的 v5 样本可能晚到：缺文件就跳过并说明（不是静默通过）。
    func testAndroidV5FixtureDecodesWhenPresent() throws {
        let url = try fixtureDirectory().appendingPathComponent("android-export-v5.json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("android-export-v5.json 还不存在（Android 端尚未生成 v5 黄金样本）")
        }
        let raw = try String(contentsOf: url, encoding: .utf8)
        let bundle = try decodeSuccess(raw)

        XCTAssertEqual(bundle.format, "myfriend.backup")
        XCTAssertEqual(bundle.formatVersion, BackupCodec.formatVersion)
        XCTAssertFalse(BackupCodec.containsSecretField(raw), "v5 备份同样不能带密钥")

        // 人格三列：至少要有一条带世界、话风或活动，且解析得开。
        let withGuidance = bundle.personas.filter {
            $0.worldJson != nil || $0.speechStyleJson != nil || $0.activityJson != nil
        }
        XCTAssertFalse(withGuidance.isEmpty, "Android 的 v5 样本里应当有人格带了人设引导字段")
        for persona in withGuidance {
            if let worldJson = persona.worldJson {
                XCTAssertNotNil(WorldPreset.decode(worldJson), "世界 JSON 解析不开：\(worldJson)")
            }
            if let styleJson = persona.speechStyleJson {
                XCTAssertNotNil(SpeechStyle.decode(styleJson), "话风 JSON 解析不开：\(styleJson)")
            }
            if let activityJson = persona.activityJson {
                XCTAssertNotNil(
                    PersonaActivity.decodeIgnoringExpiry(activityJson),
                    "活动 JSON 解析不开：\(activityJson)"
                )
            }
        }
        // 预设的世界 / 话风同样要能读。
        for preset in bundle.presets {
            if let worldJson = preset.worldJson {
                XCTAssertNotNil(WorldPreset.decode(worldJson))
            }
            if let styleJson = preset.speechStyleJson {
                XCTAssertNotNil(SpeechStyle.decode(styleJson))
            }
        }
        // 原始聊天记录绝不进备份（§3.3）：整份文件里不该出现「昵称：」这种记录形状的键。
        XCTAssertFalse(raw.contains("\"transcript\""), "备份里不该有原始聊天记录")
    }

    // MARK: - v4 历史样本：仍然能读（只读输入）

    func testIOSV4FixtureStillDecodesAsV5() throws {
        let url = try fixtureDirectory().appendingPathComponent("ios-export-v4.json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("ios-export-v4.json 不在仓库里")
        }
        let raw = try String(contentsOf: url, encoding: .utf8)
        let bundle = try decodeSuccess(raw, allowWarnings: true)
        XCTAssertEqual(bundle.formatVersion, BackupCodec.formatVersion, "读进来统一按本机版本记")
        XCTAssertFalse(bundle.personas.isEmpty)
        // v4 没有这三列：读成 nil（= 没有世界/话风/活动），不报错、不编内容。
        XCTAssertTrue(bundle.personas.allSatisfy { $0.worldJson == nil })
        XCTAssertTrue(bundle.personas.allSatisfy { $0.speechStyleJson == nil })
        XCTAssertTrue(bundle.personas.allSatisfy { $0.activityJson == nil })
        XCTAssertTrue(bundle.presets.allSatisfy { $0.worldJson == nil && $0.speechStyleJson == nil })
    }

    // MARK: - 往返：decode → encode → decode 必须相等

    func testAndroidV1FixtureSurvivesRoundTrip() throws {
        let raw = try fixtureText("android-export-v1.json")
        let first = try decodeSuccess(raw, allowWarnings: true)

        let reEncoded = try BackupCodec.encode(first)
        XCTAssertFalse(BackupCodec.containsSecretField(reEncoded))
        XCTAssertFalse(reEncoded.contains("null"), "值为 nil 的字段不该写成 null：\(reEncoded)")

        let second = try decodeSuccess(reEncoded)
        XCTAssertEqual(first, second, "解→编→解 两次结果必须相等")
    }

    // MARK: - iOS → Android

    func testIOSExportV5IsWrittenForAndroidFixtureTest() throws {
        let bundle = BackupSamples.iosBundle()
        let json = try BackupCodec.encode(bundle)

        // 先自查，再写盘：写出去的东西必须能被自己读回来，且没有密钥。
        XCTAssertFalse(BackupCodec.containsSecretField(json))
        let decoded = try decodeSuccess(json)
        XCTAssertEqual(decoded, bundle)
        XCTAssertTrue(json.contains("\"format\":\"myfriend.backup\""))
        XCTAssertTrue(json.contains("\"formatVersion\":\(BackupCodec.formatVersion)"))
        XCTAssertTrue(json.contains("\"webToolsProvider\":\"TAVILY\""),
                      "v4 导出必须带 provider（样本用的是非默认值，不能靠默认值蒙混）")
        XCTAssertTrue(json.contains("\"apiKeysOmitted\":true"))
        XCTAssertFalse(json.contains("\"apiKey\""))

        let directory = try fixtureDirectory()
        // v1–v4 的样本是**只读输入**，绝不重写；v5 只写这一个文件。
        let target = directory.appendingPathComponent("ios-export-v5.json")
        try json.write(to: target, atomically: true, encoding: .utf8)

        let attributes = try FileManager.default.attributesOfItem(atPath: target.path)
        let size = try XCTUnwrap(attributes[.size] as? NSNumber).intValue
        XCTAssertGreaterThan(size, 0)

        let onDisk = try String(contentsOf: target, encoding: .utf8)
        XCTAssertEqual(onDisk, json)
        let reloaded = try decodeSuccess(onDisk)
        XCTAssertEqual(reloaded, bundle)
        XCTAssertEqual(reloaded.formatVersion, BackupCodec.formatVersion)
        XCTAssertEqual(reloaded.media.count, 2, "样本带两张真图")
        XCTAssertFalse(reloaded.personas.isEmpty)
        XCTAssertFalse(reloaded.sessions.isEmpty)

        print("ios fixture written: \(target.path) (\(size) bytes)")
    }

    /// 写盘这一步是「给 Android 端用的」，但如果仓库被只读挂载（CI 上偶尔会），
    /// 测试不该因此整片红：明确跳过并说明原因。平时它必须真的写成功。
    func testFixtureDirectoryIsWritable() throws {
        let directory = try fixtureDirectory()
        XCTAssertTrue(FileManager.default.isWritableFile(atPath: directory.path))
    }
}
