import Foundation
import XCTest
@testable import MyFriendKit

/// 备份编解码的边界：坏数据、缺字段、多余字段、版本、密钥自检、预览文案。
///
/// 这些用例大多能逐条对上 Android 的 `BackupCodecTest` —— 两边跑同一组语义，
/// 才是「同一个文件在两端行为一致」的真正保证。
final class BackupCodecTests: XCTestCase {

    private func success(_ raw: String, file: StaticString = #filePath, line: UInt = #line) throws -> BackupDecodeResult {
        let result = BackupCodec.decode(raw)
        XCTAssertTrue(result.isSuccess, "应该能读懂，实际：\(result.failureReason ?? "?")", file: file, line: line)
        return result
    }

    // MARK: - 坏数据只返回失败/警告，绝不抛

    func testBlankInputFailsInChinese() {
        for raw in ["", "   ", "\n\t "] {
            let result = BackupCodec.decode(raw)
            XCTAssertEqual(result.failureReason, "这个文件是空的，里面没有他。")
        }
        XCTAssertEqual(BackupCodec.decode(nil).failureReason, "这个文件是空的，里面没有他。")
    }

    func testMalformedJSONFailsInChinese() {
        let result = BackupCodec.decode("{这不是 JSON")
        XCTAssertEqual(result.failureReason, "这个文件读不出来：不是一份有效的备份 JSON。")
    }

    func testNonObjectJSONFails() {
        XCTAssertFalse(BackupCodec.decode("[1,2,3]").isSuccess)
        XCTAssertFalse(BackupCodec.decode("\"一段字符串\"").isSuccess)
        XCTAssertFalse(BackupCodec.decode("null").isSuccess)
    }

    func testForeignFormatIsRejected() {
        let result = BackupCodec.decode(#"{"format":"other.app.chats","personas":[]}"#)
        XCTAssertEqual(result.failureReason, "这不是 MyFriend 的备份文件（format：other.app.chats）。")
    }

    func testHigherFormatVersionIsRejectedWithUpgradeHint() {
        let result = BackupCodec.decode(#"{"format":"myfriend.backup","formatVersion":99,"personas":[]}"#)
        let reason = try? XCTUnwrap(result.failureReason)
        XCTAssertTrue(reason?.contains("v99") ?? false)
        XCTAssertTrue(reason?.contains("升级") ?? false)
        XCTAssertTrue(reason?.contains("v\(BackupCodec.formatVersion)") ?? false)
    }

    func testLowerFormatVersionDecodesWithWarning() throws {
        let result = try success(#"{"format":"myfriend.backup","formatVersion":0,"personas":[{"id":"p1"}]}"#)
        XCTAssertEqual(result.bundle?.personas.count, 1)
        XCTAssertTrue(result.warnings.contains("备份格式是 v0，已按 v\(BackupCodec.formatVersion) 尽力读出来"))
        // v1 及更早的备份还要多一句：旧开关不再生效、一人只留最新一段（§八）。
        XCTAssertTrue(result.warnings.contains { $0.contains("1.9 之前的备份") })
        // 读进来以后统一按本机版本记，再导出不会把 v0 传下去。
        XCTAssertEqual(result.bundle?.formatVersion, BackupCodec.formatVersion)
    }

    // MARK: - 宽容：缺字段 / 未知字段 / 部分损坏

    func testEmptyObjectDecodesWithWarningsAndNoSettings() throws {
        let result = try success("{}")
        let bundle = try XCTUnwrap(result.bundle)
        XCTAssertTrue(bundle.personas.isEmpty)
        XCTAssertEqual(bundle.messages.count, 0)
        XCTAssertNil(bundle.settings, "没写设置就不该覆盖本机设置")
        XCTAssertEqual(bundle.format, "myfriend.backup")
        XCTAssertEqual(bundle.formatVersion, BackupCodec.formatVersion)
        XCTAssertTrue(result.warnings.contains("这份备份里没有他：既没有人格也没有记忆和对话"))
    }

    func testUnknownFieldsAreIgnored() throws {
        let raw = """
        {
          "format": "myfriend.backup",
          "formatVersion": \(BackupCodec.formatVersion),
          "futureSection": [1, 2, 3],
          "personas": [
            {"id": "p1", "name": "他", "futureField": {"nested": [1, 2]}}
          ]
        }
        """
        let result = try success(raw)
        let persona = try XCTUnwrap(result.bundle?.personas.first)
        XCTAssertEqual(persona.id, "p1")
        XCTAssertEqual(persona.name, "他")
        XCTAssertTrue(result.warnings.isEmpty)
    }

    /// v1 的人格没有 1.9 的细分字段：要能读，并且走代码默认值（§三「老数据不用回填」）。
    func testMissingProfileFieldsFallBackToDefaults() throws {
        let raw = #"{"format":"myfriend.backup","formatVersion":1,"personas":[{"id":"p1","name":"他"}]}"#
        let persona = try XCTUnwrap(try success(raw).bundle?.personas.first)
        XCTAssertEqual(persona.traits, [])
        XCTAssertEqual(persona.likes, [])
        XCTAssertEqual(persona.behaviors, [])
        XCTAssertNil(persona.backstory)
        XCTAssertNil(persona.quirks)
        XCTAssertEqual(persona.proactiveLevel, 3)
        XCTAssertEqual(persona.memoryRetention, 3)
        XCTAssertEqual(persona.extractionEvery, 6)
        XCTAssertTrue(persona.sleepEnabled)
        XCTAssertEqual(persona.wakeThreshold, 6)
        XCTAssertFalse(persona.dndOnSleep)
    }

    /// 列表字段的三种写法都要认（数组 / 再转义一次的字符串 / 顿号分开的一句话）。
    func testProfileListFieldsAreTolerant() throws {
        let raw = """
        {"personas":[
          {"id":"p1","traits":["慢热","嘴硬心软"],"likes":"[\\\"夜里散步\\\"]","behaviors":"先听完再给建议、话少"},
          {"id":"p2","traits":"怪数据","likes":123,"behaviors":null}
        ]}
        """
        print("DEBUG raw:", raw)
        let bundle = try success(raw).bundle
        let first = try XCTUnwrap(bundle?.personas.first)
        XCTAssertEqual(first.traits, ["慢热", "嘴硬心软"])
        XCTAssertEqual(first.likes, ["夜里散步"])
        XCTAssertEqual(first.behaviors, ["先听完再给建议", "话少"])
        // 类型写坏了也不该把整个人格丢掉：这三个字段只是锦上添花。
        let second = try XCTUnwrap(bundle?.personas.last)
        XCTAssertEqual(second.traits, ["怪数据"])
        XCTAssertEqual(second.likes, [])
        XCTAssertEqual(second.behaviors, [])
    }

    func testMissingFieldsFallBackToKotlinDefaults() throws {
        let raw = #"{"format":"myfriend.backup","personas":[{"id":"p1","name":"他"}]}"#
        let persona = try XCTUnwrap(try success(raw).bundle?.personas.first)

        XCTAssertEqual(persona.id, "p1")
        XCTAssertEqual(persona.name, "他")
        XCTAssertEqual(persona.description, "")
        XCTAssertEqual(persona.avatarSeed, 0)
        XCTAssertEqual(persona.version, 1) // Kotlin 默认 1，不是 0
        XCTAssertNil(persona.mood)
        XCTAssertNil(persona.personaCardJson)
        XCTAssertFalse(persona.isDefault)

        let memory = try XCTUnwrap(try success(#"{"memories":[{"id":"m1"}]}"#).bundle?.memories.first)
        XCTAssertEqual(memory.category, "OTHER")
        XCTAssertEqual(memory.importance, 50)
        XCTAssertEqual(memory.status, "ACTIVE")
        XCTAssertEqual(memory.strength, 1.0)

        let session = try XCTUnwrap(try success(#"{"sessions":[{"id":"s1"}]}"#).bundle?.sessions.first)
        XCTAssertEqual(session.lastMemoryExtractedCount, 0)
        XCTAssertNil(session.summary)
        XCTAssertNil(session.summarizedUpTo)
        XCTAssertNil(session.summaryAt)

        let message = try XCTUnwrap(try success(#"{"messages":[{"id":"m1"}]}"#).bundle?.messages.first)
        XCTAssertEqual(message.role, "USER")
        XCTAssertEqual(message.status, "COMPLETE")
    }

    func testPartiallyBrokenListKeepsGoodItemsAndWarns() throws {
        // 与 Android `partiallyBrokenListKeepsGoodItems` 同一份数据：类型写错的那条被丢掉。
        let raw = """
        {
          "format": "myfriend.backup",
          "memories": [
            {"id":"m1","personaId":"p1","content":"他还记得的第一件事"},
            {"id":"m2","personaId":"p1","content":"第二件事","createdAt":"这不是数字"},
            {"id":"m3","personaId":"p1","content":"第三件事"}
          ]
        }
        """
        let result = try success(raw)
        XCTAssertEqual(result.bundle?.memories.count, 2)
        XCTAssertEqual(result.bundle?.memories.map(\.id), ["m1", "m3"])
        XCTAssertTrue(result.warnings.contains("记忆 里有 1 条读不出来，已跳过"))
    }

    func testItemsWithoutIdAreSkipped() throws {
        let result = try success(#"{"format":"myfriend.backup","memories":[{"content":"没有 id 的记忆"}]}"#)
        XCTAssertTrue(result.bundle?.memories.isEmpty ?? false)
        XCTAssertTrue(result.warnings.contains("记忆 里有 1 条没写 id，已跳过"))
    }

    func testSectionThatIsNotAListIsSkippedWithWarning() throws {
        let result = try success(#"{"personas":{"id":"p1"},"memories":[{"id":"m1"}]}"#)
        XCTAssertTrue(result.bundle?.personas.isEmpty ?? false)
        XCTAssertEqual(result.bundle?.memories.count, 1)
        XCTAssertTrue(result.warnings.contains("备份里的「personas」不是列表，这一段已跳过"))
    }

    func testLenientQuotedNumbersAreAccepted() throws {
        // kotlinx 的 isLenient = true 接受带引号的数字；这里保持同一口味。
        let raw = #"{"memories":[{"id":"m1","importance":"77","strength":"0.5","pinned":"true"}]}"#
        let memory = try XCTUnwrap(try success(raw).bundle?.memories.first)
        XCTAssertEqual(memory.importance, 77)
        XCTAssertEqual(memory.strength, 0.5)
        XCTAssertTrue(memory.pinned)
    }

    func testBrokenSettingsSectionIsDroppedWithWarning() throws {
        // settings 里 importance 之类写坏了 → 整段设置不接，但人格照样读出来。
        let raw = #"{"settings":{"fontScale":"不是数字"},"personas":[{"id":"p1"}]}"#
        let result = try success(raw)
        XCTAssertNil(result.bundle?.settings)
        XCTAssertEqual(result.bundle?.personas.count, 1)
        XCTAssertTrue(result.warnings.contains("设置这一段读不出来，接回来时设置页保持原样"))
    }

    func testNullSettingsIsTreatedAsMissing() throws {
        let result = try success(#"{"settings":null,"personas":[{"id":"p1"}]}"#)
        XCTAssertNil(result.bundle?.settings)
        XCTAssertFalse(result.warnings.contains { $0.contains("设置") })
    }

    // MARK: - v4：apiConfigs[].webToolsProvider

    /// v1–v3 的备份里没有这一列：必须读成 MESSAGEAPI，而不是报错或变成空串。
    func testApiConfigProviderDefaultsToMessageAPIWhenAbsent() throws {
        let raw = #"{"format":"myfriend.backup","formatVersion":3,"apiConfigs":[{"id":"c1","name":"旧配置"}]}"#
        let config = try XCTUnwrap(try success(raw).bundle?.apiConfigs.first)
        XCTAssertEqual(config.webToolsProvider, "MESSAGEAPI")
    }

    /// 0.12 改名兼容：旧备份里的 `DEEPSEEK`（含大小写/空白变体）读进来等价于 MESSAGEAPI，
    /// 而不是被当成「不认识的值」丢掉整条配置。
    func testLegacyDeepSeekBackupValueDecodesAsMessageAPI() throws {
        let raw = #"""
        {"format":"myfriend.backup","formatVersion":4,"apiConfigs":[
          {"id":"c1","name":"旧配置","webToolsProvider":"DEEPSEEK"},
          {"id":"c2","name":"小写旧值","webToolsProvider":" deepseek "},
          {"id":"c3","name":"新值","webToolsProvider":"MESSAGEAPI"}
        ]}
        """#
        let configs = try XCTUnwrap(try success(raw).bundle?.apiConfigs)
        XCTAssertEqual(configs.map(\.webToolsProvider), ["MESSAGEAPI", "MESSAGEAPI", "MESSAGEAPI"])
    }

    /// v4 写出去一定要带 provider（即使等于默认值），读回来保持一致。
    func testApiConfigProviderIsAlwaysWrittenAndRoundTrips() throws {
        var bundle = BackupSamples.iosBundle()
        bundle.apiConfigs = [
            BackupApiConfig(id: "c1", name: "默认", webToolsProvider: "DEEPSEEK"),
            BackupApiConfig(id: "c2", name: "自建", webToolsProvider: "GENERIC")
        ]
        let json = try BackupCodec.encode(bundle)
        XCTAssertTrue(json.contains("\"webToolsProvider\":\"MESSAGEAPI\""), "默认值也要写（规范 §6）")
        XCTAssertTrue(json.contains("\"webToolsProvider\":\"GENERIC\""))
        XCTAssertFalse(json.contains("\"webToolsProvider\":\"DEEPSEEK\""), "旧值不能继续写进备份")
        let decoded = try XCTUnwrap(BackupCodec.decode(json).bundle)
        XCTAssertEqual(decoded.apiConfigs.map(\.webToolsProvider), ["MESSAGEAPI", "GENERIC"])
    }

    // MARK: - 密钥：结构里没有，导出前再查一遍

    func testSecretMarkerTableMatchesAndroid() {
        XCTAssertEqual(
            BackupCodec.secretKeyMarkers,
            ["\"apiKeyCipher\"", "\"webToolsApiKeyCipher\"", "\"apiKey\"", "\"Authorization\"", "\"customHeadersJson\""]
        )
        XCTAssertTrue(BackupCodec.containsSecretField(#"{"apiKeyCipher":"abc"}"#))
        XCTAssertTrue(BackupCodec.containsSecretField(#"{"apiKey":"sk-1"}"#))
        XCTAssertTrue(BackupCodec.containsSecretField(#"{"webToolsApiKeyCipher":"abc"}"#))
        XCTAssertTrue(BackupCodec.containsSecretField(#"{"customHeadersJson":"{\"Authorization\":\"x\"}"}"#))
        XCTAssertFalse(BackupCodec.containsSecretField(#"{"apiKeysOmitted":true}"#))
    }

    func testEncodedBundleNeverContainsSecrets() throws {
        var bundle = BackupSamples.iosBundle()
        // 就算有人在内存里给配置塞了密文，BackupApiConfig 结构也没有地方放它。
        bundle.apiConfigs[0].model = "deepseek-chat"
        let json = try BackupCodec.encode(bundle)

        XCTAssertTrue(json.contains("\"apiKeysOmitted\":true"))
        XCTAssertFalse(BackupCodec.containsSecretField(json))
        XCTAssertFalse(json.contains("apiKeyCipher"))
        XCTAssertFalse(json.contains("webToolsApiKeyCipher"))
        XCTAssertFalse(json.contains("customHeadersJson"))
        XCTAssertFalse(json.contains("Authorization"))
        // nil 字段不写 → 文件里不该出现 null。
        XCTAssertFalse(json.contains("null"))
    }

    // MARK: - 字段表：与 Kotlin 逐字一致

    /// 编码出来的键集合必须与 Kotlin data class 的字段**完全相同**（可选字段全填的样本下）。
    ///
    /// 这是备份格式最实在的一条护栏：属性改名、忘了把新字段加进 CodingKeys、
    /// 或者有人顺手加了「只存本地」的字段，都会在这里红掉。
    func testEncodedKeysMatchKotlinFieldLists() throws {
        let json = try BackupCodec.encode(BackupSamples.iosBundle())
        let root = try XCTUnwrap(JSONValueParser.object(from: json))

        func keys(of section: String, at index: Int = 0) throws -> Set<String> {
            guard case .array(let items)? = root[section], items.count > index,
                  case .object(let object) = items[index] else {
                XCTFail("段「\(section)」读不出第 \(index) 条")
                return Set<String>()
            }
            return Set(object.keys)
        }

        XCTAssertEqual(
            try keys(of: "personas"),
            ["id", "name", "description", "avatarUri", "avatarSeed", "prompt", "category",
             "version", "defaultApiConfigId", "modelOverride", "isDefault", "createdAt",
             "updatedAt", "personaCardJson", "quietSleepMinutes", "quietWakeMinutes",
             "mood", "moodIntensity", "moodReason", "moodUpdatedAt", "moodNudgedAt",
             // 1.9 的细分字段（§三）
             "traits", "likes", "behaviors", "backstory", "quirks",
             "proactiveLevel", "memoryRetention", "extractionEvery",
             "sleepEnabled", "wakeThreshold", "dndOnSleep",
             // v5 的人设引导（§1/§2/§3/§4）
             "worldJson", "speechStyleJson", "activityJson"]
        )
        XCTAssertEqual(
            try keys(of: "memories"),
            ["id", "personaId", "content", "category", "importance", "pinned", "sourceSessionId",
             "createdAt", "updatedAt", "lastUsedAt", "useCount", "drawer", "sourceQuote",
             "status", "strength", "archivedAt", "consolidatedAt", "tags", "happenedAt",
             "originalContent", "driftCount"]
        )
        XCTAssertEqual(
            try keys(of: "sessions"),
            ["id", "personaId", "apiConfigId", "model", "title", "lastMessagePreview",
             "lastMessageAt", "unreadCount", "pinned", "createdAt", "updatedAt",
             // 6 个 sync 字段已从跨端契约里删掉（0.10.2 安全审计 P1-5）：
             // 本机列还在，但备份 JSON 里不再出现，与 Android `BackupSession` 逐字一致。
             "lastPersonaVersion", "lastMemoryExtractedCount",
             "summary", "summarizedUpTo", "summaryAt",
             // 1.9 的会话级覆盖（§三）
             "memoryRetentionOverride", "extractionEveryOverride"]
        )
        // 消息有三条：必填字段每条都有，可选字段分散在不同条上 —— 取并集核对全集。
        let expectedMessageKeys: Set<String> = [
            "id", "sessionId", "personaId", "role", "content", "createdAt", "updatedAt",
            "status", "errorMessage", "model", "edited", "attachmentsJson", "toolTraceJson",
            // 1.9：用户消息被模型看到的时刻
            "readAt"
        ]
        var messageKeyUnion = Set<String>()
        for index in 0..<BackupSamples.iosBundle().messages.count {
            let set = try keys(of: "messages", at: index)
            XCTAssertTrue(
                set.isSubset(of: expectedMessageKeys),
                "消息里出现了 Kotlin 结构没有的字段：\(set.subtracting(expectedMessageKeys))"
            )
            messageKeyUnion.formUnion(set)
        }
        XCTAssertEqual(messageKeyUnion, expectedMessageKeys)
        XCTAssertEqual(
            try keys(of: "presets"),
            ["id", "name", "description", "prompt", "category", "avatarUri", "avatarSeed",
             "createdAt", "updatedAt",
             // v5：预设也带世界与话风
             "worldJson", "speechStyleJson"]
        )
        // 两条规则：DAILY 有 timeOfDayMinutes、INTERVAL 有 intervalMinutes，同样取并集。
        let expectedRuleKeys: Set<String> = [
            "id", "personaId", "sessionId", "kind", "timeOfDayMinutes", "intervalMinutes",
            "hint", "note", "enabled", "pending", "source", "nextTriggerAt",
            "lastTriggeredAt", "lastError", "createdAt", "updatedAt"
        ]
        var ruleKeyUnion = Set<String>()
        for index in 0..<BackupSamples.iosBundle().proactiveRules.count {
            let set = try keys(of: "proactiveRules", at: index)
            XCTAssertTrue(set.isSubset(of: expectedRuleKeys))
            ruleKeyUnion.formUnion(set)
        }
        XCTAssertEqual(ruleKeyUnion, expectedRuleKeys)
        XCTAssertEqual(
            try keys(of: "agentIntents"),
            ["id", "personaId", "sessionId", "kind", "earliestAt", "latestAt", "note", "hint",
             "status", "ruleId", "createdAt", "sentAt", "lastError"]
        )
        XCTAssertEqual(
            try keys(of: "memoryConsolidations"),
            ["id", "personaId", "at", "kept", "merged", "archived", "forgotten", "summary", "modelName"]
        )
        XCTAssertEqual(
            try keys(of: "apiConfigs"),
            ["id", "name", "provider", "baseUrl", "model",
             "allowInsecureHttp", "temperature", "topP", "maxOutputTokens",
             "frequencyPenalty", "presencePenalty", "webToolsEnabled", "webToolsProvider",
             "webToolsBaseUrl",
             "createdAt", "updatedAt"]
        )

        guard case .object(let settings)? = root["settings"] else {
            return XCTFail("设置段丢了")
        }
        XCTAssertEqual(
            Set(settings.keys),
            // 1.9 瘦身：记忆开关 / 节奏 / 额度 / 主动次数全部不再写（§八）。
            ["themeMode", "accentColor", "fontScale", "bubbleStyle", "animationsEnabled",
             "hapticsEnabled", "chatBackgroundColor", "agentLoopEnabled",
             "activePersonaId"]
        )

        // 顶层字段。
        XCTAssertEqual(
            Set(root.keys),
            ["format", "formatVersion", "appVersion", "exportedAt", "apiKeysOmitted",
             "personas", "memories", "sessions", "messages", "media", "presets", "proactiveRules",
             "agentIntents", "memoryConsolidations", "apiConfigs", "settings"]
        )
        // 顶层不该出现 Kotlin 结构里没有的东西。
        XCTAssertFalse(root.keys.contains("apiUsage"))
        XCTAssertFalse(root.keys.contains("agentTicks"))
    }

    // MARK: - v3：media 段的 JSON 形状（两端逐字一致）

    /// `media` 永远存在（空数组也算），不能因为「没有图片」就省掉这个键。
    func testEmptyBundleStillWritesEmptyMediaArray() throws {
        let json = try BackupCodec.encode(BackupBundle())
        XCTAssertTrue(json.contains(#""media":[]"#), "没有图片时也要写空数组：\(json)")
    }

    /// 每个媒体条目的键集合：**恰好**这 7 个（`personaId` 为 nil 时省略）。
    func testMediaEntryKeySetIsExactlyTheContract() throws {
        let json = try BackupCodec.encode(BackupSamples.iosBundle())
        let root = try XCTUnwrap(JSONValueParser.object(from: json))
        guard case .array(let media)? = root["media"] else {
            return XCTFail("media 段丢了")
        }
        XCTAssertEqual(media.count, 2, "样本里是「人格头像 + 聊天图片」两张")
        for item in media {
            guard case .object(let object) = item else { return XCTFail("media 里有一条不是对象") }
            XCTAssertEqual(
                Set(object.keys),
                ["id", "kind", "personaId", "fileName", "mime", "bytes", "data"]
            )
        }
    }

    /// `bytes` 是 JSON **数字**，且等于 `data` 解出来的原始字节数。
    func testMediaBytesIsTheDecodedLength() throws {
        let json = try BackupCodec.encode(BackupSamples.iosBundle())
        let root = try XCTUnwrap(JSONValueParser.object(from: json))
        guard case .array(let media)? = root["media"] else { return XCTFail("media 段丢了") }

        var expected: [Int] = []
        for item in media {
            guard case .object(let object) = item,
                  case .int(let declared)? = object["bytes"],
                  case .string(let data)? = object["data"],
                  let decoded = Data(base64Encoded: data) else {
                return XCTFail("bytes / data 的形状不对：\(item)")
            }
            XCTAssertEqual(decoded.count, Int(declared), "bytes 必须等于 data 解出来的字节数")
            expected.append(decoded.count)
        }
        // 样本用的是真的 2×2 小图：632 字节的 jpeg、75 字节的 png。
        XCTAssertEqual(expected, [632, 75])
    }

    /// `data` 是标准 RFC 4648 base64：带 padding、不换行、不含空白。
    func testMediaBase64IsStandardAndUnwrapped() throws {
        let json = try BackupCodec.encode(BackupSamples.iosBundle())
        let root = try XCTUnwrap(JSONValueParser.object(from: json))
        guard case .array(let media)? = root["media"] else { return XCTFail("media 段丢了") }
        let allowed = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        )
        for item in media {
            guard case .object(let object) = item, case .string(let data)? = object["data"] else {
                return XCTFail("data 不是字符串")
            }
            XCTAssertFalse(data.isEmpty)
            XCTAssertFalse(data.contains("\n"), "base64 不许换行")
            XCTAssertFalse(data.contains(" "), "base64 不许带空白")
            XCTAssertTrue(
                data.unicodeScalars.allSatisfy { allowed.contains($0) },
                "不是标准 base64 字母表"
            )
            XCTAssertEqual(data.count % 4, 0, "标准 base64 的长度必须是 4 的倍数（带 padding）")
            XCTAssertTrue(Data(base64Encoded: data) != nil, "标准 base64 必须能被解回来")
        }
    }

    /// `personaId` 为 nil 时**这个键不写**（不是写 null）：USER_AVATAR 就是这样。
    func testMediaOmitsPersonaIdWhenNil() throws {
        var bundle = BackupSamples.iosBundle()
        bundle.media = [
            BackupMedia(
                id: "ios-media-user", kind: "USER_AVATAR", personaId: nil,
                fileName: "avatar-ios-me.jpg", mime: "image/jpeg", bytes: 632,
                data: BackupSamples.sampleJPEGBase64
            )
        ]
        let json = try BackupCodec.encode(bundle)
        let root = try XCTUnwrap(JSONValueParser.object(from: json))
        guard case .array(let media)? = root["media"], case .object(let entry)? = media.first else {
            return XCTFail("media 段丢了")
        }
        XCTAssertEqual(
            Set(entry.keys),
            ["id", "kind", "fileName", "mime", "bytes", "data"],
            "nil 的 personaId 不许写进媒体条目（别的段有 personaId 是正常的）"
        )
        // 读回来也必须是 nil（不是空串）。
        XCTAssertNil(BackupCodec.decode(json).bundle?.media.first?.personaId)
    }

    /// media 段能宽容解码：整段不是数组、某几条读不动，都只记警告、不影响别的段。
    func testBrokenMediaSectionIsSkippedWithWarning() throws {
        let result = try success(#"{"personas":[{"id":"p1"}],"media":{"id":"m1"}}"#)
        XCTAssertTrue(result.bundle?.media.isEmpty ?? false)
        XCTAssertEqual(result.bundle?.personas.count, 1)
        XCTAssertTrue(
            result.warnings.contains("备份里的「media」不是列表，这一段已跳过"),
            "\(result.warnings)"
        )

        // 某一条的 bytes 写成了一段文字 → 这一条跳过并计数，别的条目还在。
        let partiallyBroken = #"""
        {"media":[
          {"id":"m1","kind":"USER_AVATAR","fileName":"avatar-a.jpg","mime":"image/jpeg","bytes":"很多","data":""},
          {"id":"m2","kind":"USER_AVATAR","fileName":"avatar-b.jpg","mime":"image/jpeg","bytes":1,"data":"AA=="}
        ]}
        """#
        let broken = try success(partiallyBroken)
        XCTAssertEqual(broken.bundle?.media.map(\.id), ["m2"])
        XCTAssertTrue(broken.warnings.contains("媒体 里有 1 条读不出来，已跳过"), "\(broken.warnings)")
    }

    /// `media[].id` 只是排查用的：没有 id 的条目也要留下（关联靠 kind + fileName）。
    func testMediaWithoutIdIsKept() throws {
        let raw = #"{"media":[{"kind":"USER_AVATAR","fileName":"avatar-a.jpg","mime":"image/jpeg","bytes":0,"data":""}]}"#
        let result = try success(raw)
        XCTAssertEqual(result.bundle?.media.count, 1)
        XCTAssertEqual(result.bundle?.media.first?.fileName, "avatar-a.jpg")
        XCTAssertFalse(result.warnings.contains { $0.contains("没写 id") }, "\(result.warnings)")
    }

    // MARK: - 往返：中文、emoji、超长文本

    func testChineseAndEmojiSurviveRoundTrip() throws {
        var bundle = BackupSamples.iosBundle()
        let name = "沈迟🌊"
        let content = "你说「今天有点累😮‍💨」，他说：那就早点睡吧。"
        bundle.personas[0].name = name
        bundle.messages[0].content = content

        let json = try BackupCodec.encode(bundle)
        let decoded = try XCTUnwrap(BackupCodec.decode(json).bundle)
        XCTAssertEqual(decoded.personas[0].name, name)
        XCTAssertEqual(decoded.messages[0].content, content)
    }

    func testHugeTextSurvivesRoundTrip() throws {
        var bundle = BackupSamples.iosBundle()
        let long = "他很认真地听你说完：" + String(repeating: "嗯", count: 120_000)
        bundle.messages[0].content = long

        let json = try BackupCodec.encode(bundle)
        let decoded = try XCTUnwrap(BackupCodec.decode(json).bundle)
        XCTAssertEqual(decoded.messages[0].content.count, long.count)
        XCTAssertEqual(decoded.messages[0].content, long)
    }

    func testBundleEquatableDistinguishesFields() {
        // Equatable 是往返测试的判据，本身也要可信：改一个字段就必须不相等。
        let base = BackupSamples.iosBundle()
        var changed = base
        XCTAssertEqual(base, changed)
        changed.sessions[0].summary = "别的摘要"
        XCTAssertNotEqual(base, changed)
        changed = base
        changed.memories[0].strength = 0.1
        XCTAssertNotEqual(base, changed)
    }

    // MARK: - 预览与文件名

    func testPreviewCountsAndDescribes() {
        let preview = BackupPreview.from(BackupSamples.iosBundle())
        XCTAssertEqual(preview.personas, 1)
        XCTAssertEqual(preview.memories, 1)
        XCTAssertEqual(preview.sessions, 1)
        XCTAssertEqual(preview.messages, 3)
        XCTAssertEqual(preview.rules, 2)
        XCTAssertEqual(preview.presets, 1)
        XCTAssertEqual(preview.apiConfigs, 1)
        XCTAssertFalse(preview.isEmpty)
        XCTAssertEqual(preview.describe(), "1 个人格 · 1 条记忆 · 1 段对话 · 3 句话 · 2 条主动节奏")
        XCTAssertTrue(preview.exportedAtText().hasSuffix(" 带走"))
    }

    func testEmptyPreviewSaysThereIsNobody() {
        let preview = BackupPreview.from(BackupBundle())
        XCTAssertTrue(preview.isEmpty)
        XCTAssertEqual(preview.describe(), "这份备份里什么也没有")
        XCTAssertEqual(preview.exportedAtText(), "带走的时间不详")
    }

    func testPreviewDescribeMatchesTheDocumentedExample() {
        // 文档里的例子：「3 个人格 · 6 条记忆 · 1 段对话 · 51 句话 · 1 条主动节奏」。
        let preview = BackupPreview(
            personas: 3, memories: 6, sessions: 1, messages: 51, rules: 1,
            exportedAt: 1_795_000_000_000
        )
        XCTAssertEqual(preview.describe(), "3 个人格 · 6 条记忆 · 1 段对话 · 51 句话 · 1 条主动节奏")
    }

    func testBackupFileNameUsesTimestampShape() throws {
        let name = backupFileName(now: 1_763_053_200_000)
        XCTAssertNotNil(name.range(of: #"^myfriend-backup-\d{8}-\d{4}\.json$"#, options: .regularExpression))
    }

    func testMomentFormattingIsChineseAndStable() {
        XCTAssertEqual(formatBackupMoment(0), "时间不详")
        // 显式钉住语言与时区：CI 跑在 en_US/UTC 上，靠 Locale.current 会得到英文文案。
        let zh = formatBackupMoment(
            1_763_053_200_000,
            locale: Locale(identifier: "zh_CN"),
            timeZone: TimeZone(identifier: "Asia/Shanghai")!
        )
        XCTAssertTrue(
            zh.range(of: #"^\d{4}年\d{1,2}月\d{1,2}日 \d{2}:\d{2}$"#, options: .regularExpression) != nil,
            "实际：\(zh)"
        )
        let en = formatBackupMoment(
            1_763_053_200_000,
            locale: Locale(identifier: "en_US"),
            timeZone: TimeZone(identifier: "Asia/Shanghai")!
        )
        XCTAssertTrue(en.contains("2025"), "英文文案应含年份：\(en)")
    }
}
