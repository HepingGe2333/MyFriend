import Foundation
import XCTest
@testable import MyFriendKit

/// 跨端备份的「硬要求」：Android 导出的备份 iOS 能读、iOS 导出的备份 Android 能读。
///
/// 两端在 `fixtures/backup/` 里各放一份黄金样本：
/// - `android-export-v1.json` / `ios-export-v1.json`：1.8 时的形状。1.9 **只读不写**，
///   用来钉住「必须能读 v1 备份」（§八）；
/// - `android-export-v2.json`：由 Android 的 `BackupFixtureTest` 生成，这里只读；
///   它可能比 iOS 这一版晚到（另一端也在写），所以缺失时跳过而不是红掉；
/// - `ios-export-v2.json`：由**这个测试**生成，Android 侧会读它。
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
        // 读进来统一按本机版本记：再导出就是 v2。
        XCTAssertEqual(bundle.formatVersion, 2)
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

    /// v1 的样本读进来、再导出，必须是 v2 的形状（不再带着那串旧开关）。
    func testV1ImportReExportsAsV2() throws {
        let raw = try fixtureText("android-export-v1.json")
        let bundle = try decodeSuccess(raw, allowWarnings: true)
        let reEncoded = try BackupCodec.encode(bundle)

        XCTAssertTrue(reEncoded.contains("\"formatVersion\":2"))
        for removed in ["replyPace", "memoryEnabled", "autoMemoryEnabled",
                        "memoryConsolidationEnabled", "webSearchScoringEnabled",
                        "proactiveDailyLimit", "monthlyBudgetMicroUsd",
                        "overBudgetChatOnly", "inputPriceUsdPerMillion",
                        "outputPriceUsdPerMillion", "harnessMode", "harnessUrl",
                        "harnessSyncPath"] {
            XCTAssertFalse(reEncoded.contains("\"\(removed)\""), "v2 里不该再有 \(removed)")
        }
        // 再读一遍仍然稳定：解→编→解 幂等。
        let second = try decodeSuccess(reEncoded)
        XCTAssertEqual(bundle, second)
    }

    func testIOSV1FixtureStillDecodes() throws {
        let raw = try fixtureText("ios-export-v1.json")
        let bundle = try decodeSuccess(raw, allowWarnings: true)
        XCTAssertEqual(bundle.formatVersion, 2)
        XCTAssertFalse(bundle.personas.isEmpty)
        XCTAssertFalse(bundle.messages.isEmpty)
    }

    // MARK: - Android v2 → iOS（对方还没生成时跳过）

    func testAndroidV2FixtureDecodesWhenPresent() throws {
        let url = try fixtureDirectory().appendingPathComponent("android-export-v2.json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            // 两端并行实现：Android 的 v2 样本可能晚到。缺它就跳过，报告里会写清楚。
            throw XCTSkip("android-export-v2.json 还不存在（Android 端尚未生成 v2 黄金样本）")
        }
        let raw = try String(contentsOf: url, encoding: .utf8)
        let bundle = try decodeSuccess(raw)

        XCTAssertEqual(bundle.format, "myfriend.backup")
        XCTAssertEqual(bundle.formatVersion, 2)
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

    func testIOSExportIsWrittenForAndroidFixtureTest() throws {
        let bundle = BackupSamples.iosBundle()
        let json = try BackupCodec.encode(bundle)

        // 先自查，再写盘：写出去的东西必须能被自己读回来，且没有密钥。
        XCTAssertFalse(BackupCodec.containsSecretField(json))
        let decoded = try decodeSuccess(json)
        XCTAssertEqual(decoded, bundle)
        XCTAssertTrue(json.contains("\"format\":\"myfriend.backup\""))
        XCTAssertTrue(json.contains("\"formatVersion\":2"))
        XCTAssertTrue(json.contains("\"apiKeysOmitted\":true"))
        XCTAssertFalse(json.contains("\"apiKey\""))

        let directory = try fixtureDirectory()
        let target = directory.appendingPathComponent("ios-export-v2.json")
        try json.write(to: target, atomically: true, encoding: .utf8)

        let attributes = try FileManager.default.attributesOfItem(atPath: target.path)
        let size = try XCTUnwrap(attributes[.size] as? NSNumber).intValue
        XCTAssertGreaterThan(size, 0)

        let onDisk = try String(contentsOf: target, encoding: .utf8)
        XCTAssertEqual(onDisk, json)
        let reloaded = try decodeSuccess(onDisk)
        XCTAssertEqual(reloaded, bundle)
        XCTAssertEqual(reloaded.formatVersion, 2)
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
