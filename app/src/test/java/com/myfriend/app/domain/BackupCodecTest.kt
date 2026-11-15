package com.myfriend.app.domain

import com.myfriend.app.data.local.AgentIntentEntity
import com.myfriend.app.data.local.AgentIntentKind
import com.myfriend.app.data.local.AgentIntentStatus
import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MemoryCategory
import com.myfriend.app.data.local.MemoryConsolidationEntity
import com.myfriend.app.data.local.MemoryEntity
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.MessageStatus
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.PersonaPresetEntity
import com.myfriend.app.data.local.ProactiveKind
import com.myfriend.app.data.local.ProactiveRuleEntity
import com.myfriend.app.data.local.ProactiveSource
import com.myfriend.app.data.local.ProviderType
import com.myfriend.app.data.prefs.AppSettings
import com.myfriend.app.data.prefs.BubbleStyle
import com.myfriend.app.data.prefs.ThemeMode
import com.myfriend.app.data.repo.ImportMode
import com.myfriend.app.data.repo.ImportSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「把他带走」的那份文件：格式、容错、往返一致，以及最重要的那条——密钥永远不在里面。
 */
class BackupCodecTest {

    // ------------------------------------------------------------------
    // 构造样例：字段尽量填满，往返测试才有意义。
    // ------------------------------------------------------------------

    private fun persona() = PersonaEntity(
        id = "persona-1",
        name = "林岸",
        description = "住在海边的人，说话慢",
        avatarUri = "file:///data/avatar.png",
        avatarSeed = 42,
        prompt = "你叫林岸，住在海边。",
        category = "伴侣",
        version = 3,
        defaultApiConfigId = "config-1",
        modelOverride = "deepseek-chat",
        isDefault = true,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        personaCardJson = """{"identity":{"name":"林岸"},"traits":[{"name":"嘴硬","strength":0.8}]}""",
        quietSleepMinutes = 23 * 60 + 30,
        quietWakeMinutes = 7 * 60,
        mood = "疲惫",
        moodIntensity = 3,
        moodReason = "连着加了三天班",
        moodUpdatedAt = 1_700_000_200_000L,
        moodNudgedAt = 1_700_000_300_000L
    )

    private fun memory() = MemoryEntity(
        id = "memory-1",
        personaId = "persona-1",
        content = "你妹妹叫小满，今年高考完想去学建筑。",
        category = MemoryCategory.RELATIONSHIP,
        importance = 87,
        pinned = true,
        sourceSessionId = "session-1",
        createdAt = 1_700_000_400_000L,
        updatedAt = 1_700_000_500_000L,
        lastUsedAt = 1_700_000_600_000L,
        useCount = 7,
        drawer = "USER",
        sourceQuote = "我妹妹小满今年高考",
        status = "DORMANT",
        strength = 0.42,
        archivedAt = 1_700_000_700_000L,
        consolidatedAt = 1_700_000_800_000L,
        tags = """["妹妹","高考","建筑"]""",
        happenedAt = 1_690_000_000_000L,
        originalContent = "你妹妹叫小满，想去学建筑。",
        driftCount = 2
    )

    private fun session() = ChatSessionEntity(
        id = "session-1",
        personaId = "persona-1",
        apiConfigId = "config-1",
        model = "deepseek-chat",
        title = "加班到深夜",
        lastMessagePreview = "那我等你回来",
        lastMessageAt = 1_700_000_900_000L,
        unreadCount = 2,
        pinned = true,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_950_000L,
        lastPersonaVersion = 3,
        lastMemoryExtractedCount = 12
    )

    private fun message() = MessageEntity(
        id = "message-1",
        sessionId = "session-1",
        personaId = "persona-1",
        role = MessageRole.ASSISTANT,
        content = "那我等你回来。",
        createdAt = 1_700_000_900_000L,
        updatedAt = 1_700_000_900_000L,
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        model = "deepseek-chat",
        edited = true,
        attachmentsJson = """[{"uri":"file:///data/media/a.jpg","mime":"image/jpeg"}]""",
        toolTraceJson = """[{"name":"web_search","ok":true}]"""
    )

    private fun apiConfig() = ApiConfigEntity(
        id = "config-1",
        name = "DeepSeek 官方",
        provider = ProviderType.OPENAI,
        baseUrl = "https://api.deepseek.com",
        apiKeyCipher = "CIPHER-SHOULD-NOT-LEAK",
        model = "deepseek-chat",
        customHeadersJson = """{"Authorization":"Bearer sk-should-not-leak"}""",
        allowInsecureHttp = false,
        temperature = 0.8,
        topP = 0.95,
        maxOutputTokens = 2048,
        frequencyPenalty = 0.1,
        presencePenalty = 0.2,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        webToolsEnabled = true,
        webToolsProvider = "SERPER",
        webToolsBaseUrl = "https://api.deepseek.com",
        webToolsApiKeyCipher = "WEB-CIPHER-SHOULD-NOT-LEAK"
    )

    private fun sampleBundle() = BackupBundle(
        appVersion = "1.6.0",
        exportedAt = 1_700_001_000_000L,
        personas = listOf(persona().toBackupPersona()),
        memories = listOf(memory().toBackupMemory()),
        sessions = listOf(session().toBackupSession()),
        messages = listOf(message().toBackupMessage()),
        presets = listOf(
            PersonaPresetEntity(
                id = "preset-1",
                name = "温柔模板",
                description = "慢一点的说话方式",
                prompt = "你说话很慢",
                category = "温柔",
                avatarUri = null,
                avatarSeed = 7,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_100_000L
            ).toBackupPreset()
        ),
        proactiveRules = listOf(
            ProactiveRuleEntity(
                id = "rule-1",
                personaId = "persona-1",
                sessionId = "session-1",
                kind = ProactiveKind.DAILY,
                timeOfDayMinutes = 22 * 60,
                intervalMinutes = null,
                hint = "晚上问问今天怎么样",
                note = "他答应过",
                enabled = true,
                pending = false,
                source = ProactiveSource.AI,
                nextTriggerAt = 1_700_002_000_000L,
                lastTriggeredAt = 1_700_000_500_000L,
                lastError = null,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_600_000L
            ).toBackupRule()
        ),
        agentIntents = listOf(
            AgentIntentEntity(
                id = "intent-1",
                personaId = "persona-1",
                sessionId = "session-1",
                kind = AgentIntentKind.REACH_OUT,
                earliestAt = 1_700_003_000_000L,
                latestAt = 1_700_003_600_000L,
                note = "想问问他面试怎么样",
                hint = "别太准点",
                status = AgentIntentStatus.PENDING,
                ruleId = "rule-1",
                createdAt = 1_700_000_100_000L,
                sentAt = null,
                lastError = null
            ).toBackupIntent()
        ),
        memoryConsolidations = listOf(
            MemoryConsolidationEntity(
                id = "run-1",
                personaId = "persona-1",
                at = 1_700_000_700_000L,
                kept = 12,
                merged = 3,
                archived = 2,
                forgotten = 1,
                summary = "把两件关于加班的事并成了一件",
                modelName = "deepseek-chat"
            ).toBackupConsolidation()
        ),
        apiConfigs = listOf(apiConfig().toBackupApiConfig()),
        settings = AppSettings().toBackupSettings()
    )

    private fun decodeSuccess(raw: String): BackupDecodeResult.Success {
        val decoded = BackupCodec.decode(raw)
        if (decoded !is BackupDecodeResult.Success) {
            fail("应该能读懂，实际：${(decoded as BackupDecodeResult.Failure).reason}")
        }
        return decoded as BackupDecodeResult.Success
    }

    // ------------------------------------------------------------------
    // 往返一致
    // ------------------------------------------------------------------

    @Test
    fun personaRoundTripKeepsEveryField() {
        val original = persona()
        // 安全 P2-5 是唯一的例外：头像在备份里只留文件名（不写本机绝对路径），
        // 所以「往返一致」在这一列上按「同一张图的文件名」断言。
        val backup = original.toBackupPersona()
        assertEquals("avatar.png", backup.avatarUri)
        assertEquals(original, backup.toEntity().copy(avatarUri = original.avatarUri))
    }

    @Test
    fun memoryRoundTripKeepsEveryField() {
        val original = memory()
        val restored = original.toBackupMemory().toEntity()
        assertEquals(original, restored)
        // 这几个是「记忆还是不是他记得的那件事」的关键字段，单独再钉一次。
        assertEquals("USER", restored.drawer)
        assertEquals(0.42, restored.strength, 0.0001)
        assertEquals("你妹妹叫小满，想去学建筑。", restored.originalContent)
        assertEquals(2, restored.driftCount)
    }

    @Test
    fun sessionRoundTripKeepsEveryField() {
        val original = session()
        assertEquals(original, original.toBackupSession().toEntity())
    }

    @Test
    fun messageRoundTripKeepsToolTrace() {
        val original = message()
        val restored = original.toBackupMessage().toEntity()
        assertEquals(MessageRole.ASSISTANT, restored.role)
        assertEquals(original.toolTraceJson, restored.toolTraceJson)
        assertEquals(original.id, restored.id)
        assertEquals(original.edited, restored.edited)
    }

    @Test
    fun importedMessageDropsAttachmentsButKeepsText() {
        val restored = message().toBackupMessage().toEntity()
        // 图片二进制不在备份里，uri 换机就失效：接回来只留文字。
        assertNull(restored.attachmentsJson)
        assertEquals("那我等你回来。", restored.content)
    }

    @Test
    fun bundleRoundTripsThroughJson() {
        val bundle = sampleBundle()
        val decoded = decodeSuccess(BackupCodec.encode(bundle))
        assertEquals(bundle, decoded.bundle)
        assertTrue(decoded.warnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 密钥永不进备份
    // ------------------------------------------------------------------

    @Test
    fun exportedJsonNeverContainsSecrets() {
        val bundle = sampleBundle()
        val encoded = BackupCodec.encode(bundle)

        assertFalse("备份里不能出现 apiKeyCipher", encoded.contains("apiKeyCipher"))
        assertFalse("备份里不能出现 webToolsApiKeyCipher", encoded.contains("webToolsApiKeyCipher"))
        assertFalse("备份里不能出现自定义 Headers", encoded.contains("customHeadersJson"))
        assertFalse("密文不能出现", encoded.contains("CIPHER-SHOULD-NOT-LEAK"))
        assertFalse("联网密文不能出现", encoded.contains("WEB-CIPHER-SHOULD-NOT-LEAK"))
        assertFalse("Authorization 不能出现", encoded.contains("Authorization"))
        assertFalse("Key 明文不能出现", encoded.contains("sk-should-not-leak"))
        assertTrue("要写明没带 Key", encoded.contains("\"apiKeysOmitted\":true"))
        assertTrue("v4 要写 provider", encoded.contains("\"webToolsProvider\":\"SERPER\""))
        assertFalse(BackupCodec.containsSecretField(encoded))
    }

    @Test
    fun containsSecretFieldCatchesLeaks() {
        // 这是「万一以后有人把敏感字段加回结构」的报警器，本身也要可靠。
        assertTrue(BackupCodec.containsSecretField("""{"apiKeyCipher":"abc"}"""))
        assertTrue(BackupCodec.containsSecretField("""{"apiKey":"sk-1"}"""))
        assertTrue(BackupCodec.containsSecretField("""{"webToolsApiKeyCipher":"abc"}"""))
        assertTrue(BackupCodec.containsSecretField("""{"customHeadersJson":"{\"Authorization\":\"x\"}"}"""))
        assertFalse(BackupCodec.containsSecretField("""{"apiKeysOmitted":true}"""))
    }

    @Test
    fun apiConfigRoundTripKeepsNonSensitiveFieldsOnly() {
        val backup = apiConfig().toBackupApiConfig()
        assertEquals("https://api.deepseek.com", backup.baseUrl)
        assertEquals("deepseek-chat", backup.model)
        assertEquals("OPENAI", backup.provider)
        assertTrue(backup.webToolsEnabled)
        assertEquals("v4 要带着 provider", "SERPER", backup.webToolsProvider)

        val restored = backup.toNewEntity().entity
        assertEquals("接回来的配置保留 provider", "SERPER", restored.webToolsProvider)
        assertNull("新配置没有 Key，等用户在这台手机上重新填", restored.apiKeyCipher)
        assertNull(restored.webToolsApiKeyCipher)
        assertEquals("{}", restored.customHeadersJson)
        assertEquals("https://api.deepseek.com", restored.baseUrl)
        assertFalse("明文开关不能由备份打开（只能用户在本机手勾）", restored.allowInsecureHttp)
    }

    @Test
    fun mergingConfigIntoExistingKeepsLocalKeys() {
        val local = apiConfig()
        val incoming = local.toBackupApiConfig().copy(
            baseUrl = "https://new.example.com",
            model = "new-model",
            webToolsEnabled = false,
            webToolsProvider = "GENERIC"
        )

        val merged = incoming.mergeInto(local)
        // 0.10.2 安全修复：地址**不再**从备份迁移（否则一份可控备份就能把 Key 引到攻击者主机）；
        // 模型/开关这类非敏感字段仍然可以跟着备份走。
        assertEquals("地址必须保留本机值", local.baseUrl, merged.entity.baseUrl)
        assertTrue("地址被拒绝时要在导入摘要里说明", merged.addressKept)
        assertEquals("new-model", merged.entity.model)
        assertFalse(merged.entity.webToolsEnabled)
        assertEquals("provider 不是敏感字段，可以跟着备份走", "GENERIC", merged.entity.webToolsProvider)
        assertEquals(local.apiKeyCipher, merged.entity.apiKeyCipher)
        assertEquals(local.webToolsApiKeyCipher, merged.entity.webToolsApiKeyCipher)
        assertEquals(local.customHeadersJson, merged.entity.customHeadersJson)
        assertEquals(local.createdAt, merged.entity.createdAt)
    }

    // ------------------------------------------------------------------
    // 设置的移植边界
    // ------------------------------------------------------------------

    @Test
    fun settingsRoundTripKeepsOnlyPortableOnes() {
        val original = AppSettings(
            themeMode = ThemeMode.DARK,
            accentColor = 0xFF4F7F92,
            fontScale = 1.15f,
            bubbleStyle = BubbleStyle.COMPACT,
            animationsEnabled = false,
            hapticsEnabled = false,
            chatBackgroundColor = 0xFF191A18,
            chatBackgroundImageUri = "file:///data/media/bg.png",
            activePersonaId = "persona-1",
            agentLoopEnabled = false,
            backgroundTipDismissed = true
        )

        val restored = original.toBackupSettings().toAppSettings()

        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertEquals(BubbleStyle.COMPACT, restored.bubbleStyle)
        assertEquals("persona-1", restored.activePersonaId)
        assertFalse(restored.agentLoopEnabled)
        // 1.9：记忆开关、节奏、额度、单价这些设置项已经不存在，
        // 备份里也不该再有它们——这一条同时是「设置项彻底消失」的守卫。
        val encoded = BackupCodec.encode(
            BackupBundle(settings = original.toBackupSettings())
        )
        listOf(
            "replyPace", "memoryEnabled", "autoMemoryEnabled", "memoryConsolidationEnabled",
            "webSearchScoringEnabled", "proactiveDailyLimit", "monthlyBudgetMicroUsd",
            "overBudgetChatOnly", "inputPriceUsdPerMillion", "outputPriceUsdPerMillion"
        ).forEach { key ->
            assertFalse("1.9 的备份里不该再有设置项 $key", encoded.contains("\"$key\""))
        }
        // 设备上的路径与纯本地的引导状态不带走，接回来就是默认值。
        assertNull(restored.chatBackgroundImageUri)
        assertFalse(restored.backgroundTipDismissed)
    }

    // ------------------------------------------------------------------
    // 容错：坏数据只返回失败/警告，绝不抛
    // ------------------------------------------------------------------

    @Test
    fun malformedJsonReturnsFailure() {
        val decoded = BackupCodec.decode("{这不是 JSON")
        assertTrue(decoded is BackupDecodeResult.Failure)
        assertEquals(BackupFailure.InvalidJson, (decoded as BackupDecodeResult.Failure).reason)
    }

    @Test
    fun nonObjectJsonReturnsFailure() {
        val decoded = BackupCodec.decode("[1,2,3]")
        assertTrue(decoded is BackupDecodeResult.Failure)
    }

    @Test
    fun blankTextReturnsFailure() {
        assertTrue(BackupCodec.decode("") is BackupDecodeResult.Failure)
        assertTrue(BackupCodec.decode(null) is BackupDecodeResult.Failure)
        assertTrue(BackupCodec.decode("   ") is BackupDecodeResult.Failure)
    }

    @Test
    fun foreignFormatIsRejected() {
        val decoded = BackupCodec.decode("""{"format":"other.app.chats","personas":[]}""")
        assertTrue(decoded is BackupDecodeResult.Failure)
        // 结构化原因：界面自己翻成当前语言，数据层不拼句子。
        assertEquals(
            BackupFailure.WrongFormat("other.app.chats"),
            (decoded as BackupDecodeResult.Failure).reason
        )
    }

    @Test
    fun higherFormatVersionIsRejected() {
        val decoded = BackupCodec.decode("""{"format":"myfriend.backup","formatVersion":99,"personas":[]}""")
        assertTrue(decoded is BackupDecodeResult.Failure)
        assertEquals(
            BackupFailure.NewerFormat(99, BACKUP_FORMAT_VERSION),
            (decoded as BackupDecodeResult.Failure).reason
        )
    }

    @Test
    fun emptyBackupDecodesWithWarning() {
        val decoded = decodeSuccess("{}")
        assertTrue(decoded.bundle.personas.isEmpty())
        assertEquals(0, decoded.bundle.messages.size)
        assertTrue(decoded.warnings.contains(BackupWarning.NoContent))
        assertNull("没写设置就不该覆盖本机设置", decoded.bundle.settings)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val raw = """
            {
              "format": "myfriend.backup",
              "formatVersion": 1,
              "futureSection": [1, 2, 3],
              "personas": [
                {"id": "p1", "name": "他", "futureField": {"nested": [1, 2]}}
              ]
            }
        """.trimIndent()

        val decoded = decodeSuccess(raw)
        assertEquals(1, decoded.bundle.personas.size)
        assertEquals("他", decoded.bundle.personas.single().name)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val raw = """{"format":"myfriend.backup","personas":[{"id":"p1","name":"他"}]}"""
        val decoded = decodeSuccess(raw)
        val restored = decoded.bundle.personas.single().toEntity()

        assertEquals("p1", restored.id)
        assertEquals("他", restored.name)
        assertEquals("", restored.description)
        assertEquals(0, restored.avatarSeed)
        assertNull(restored.mood)
        assertNull(restored.personaCardJson)
        assertFalse(restored.isDefault)
        // 没写的消息/记忆段是空列表，不是 null。
        assertTrue(decoded.bundle.memories.isEmpty())
        assertTrue(decoded.bundle.sessions.isEmpty())
    }

    @Test
    fun unknownEnumValuesFallBackToDefaults() {
        val raw = """
            {
              "format": "myfriend.backup",
              "messages": [{"id":"m1","sessionId":"s1","personaId":"p1","role":"未来才有的角色"}]
            }
        """.trimIndent()

        val decoded = decodeSuccess(raw)
        assertEquals(MessageRole.USER, decoded.bundle.messages.single().toEntity().role)
    }

    @Test
    fun partiallyBrokenListKeepsGoodItems() {
        val raw = """
            {
              "format": "myfriend.backup",
              "memories": [
                {"id":"m1","personaId":"p1","content":"他还记得的第一件事"},
                {"id":"m2","personaId":"p1","content":"第二件事","createdAt":"这不是数字"},
                {"id":"m3","personaId":"p1","content":"第三件事"}
              ]
            }
        """.trimIndent()

        val decoded = decodeSuccess(raw)
        assertEquals(2, decoded.bundle.memories.size)
        assertTrue(decoded.warnings.contains(BackupWarning.DroppedRows(BackupSection.MEMORY, 1)))
    }

    @Test
    fun itemsWithoutIdAreSkipped() {
        val raw = """{"format":"myfriend.backup","memories":[{"content":"没有 id 的记忆"}]}"""
        val decoded = decodeSuccess(raw)
        assertTrue(decoded.bundle.memories.isEmpty())
        assertTrue(decoded.warnings.contains(BackupWarning.BlankIds(BackupSection.MEMORY, 1)))
    }

    // ------------------------------------------------------------------
    // 真实文本：篇幅、中文、emoji
    // ------------------------------------------------------------------

    @Test
    fun personaOnlyBackupStillMakesSense() {
        val bundle = BackupBundle(personas = listOf(persona().toBackupPersona()))
        val decoded = decodeSuccess(BackupCodec.encode(bundle))

        assertEquals(1, decoded.bundle.personas.size)
        assertTrue(decoded.bundle.messages.isEmpty())
        val preview = BackupPreview.from(decoded.bundle)
        assertEquals(1, preview.personas)
        assertEquals(0, preview.messages)
    }

    @Test
    fun hugeTextSurvivesRoundTrip() {
        val long = "他很认真地听你说完：" + "嗯".repeat(120_000)
        val bundle = BackupBundle(
            messages = listOf(message().toBackupMessage().copy(id = "m-long", content = long))
        )

        val decoded = decodeSuccess(BackupCodec.encode(bundle))
        assertEquals(long.length, decoded.bundle.messages.single().content.length)
        assertEquals(long, decoded.bundle.messages.single().content)
    }

    @Test
    fun chineseAndEmojiSurviveRoundTrip() {
        val name = "林岸🌊"
        val content = "你说「今天有点累😮‍💨」，他说：那就早点睡吧。"
        val bundle = BackupBundle(
            personas = listOf(persona().toBackupPersona().copy(id = "p-emoji", name = name)),
            messages = listOf(message().toBackupMessage().copy(id = "m-emoji", content = content))
        )

        val decoded = decodeSuccess(BackupCodec.encode(bundle))
        assertEquals(name, decoded.bundle.personas.single().name)
        assertEquals(content, decoded.bundle.messages.single().content)
    }

    // ------------------------------------------------------------------
    // 摘要与文件名
    // ------------------------------------------------------------------

    @Test
    fun previewCountsAreStructured() {
        // 0.10.1 起 preview 只给计数（句子在 UI 层按语言拼），所以这里只钉计数与「有没有内容」。
        val preview = BackupPreview.from(sampleBundle())
        assertEquals(1, preview.personas)
        assertEquals(1, preview.memories)
        assertEquals(1, preview.messages)
        assertEquals(1, preview.rules)
        assertFalse(preview.isEmpty)
        assertTrue(preview.hasAnyContent)
        assertTrue(preview.exportedAt > 0L)
    }

    @Test
    fun emptyPreviewIsEmpty() {
        val preview = BackupPreview.from(BackupBundle())
        assertTrue(preview.isEmpty)
        assertFalse(preview.hasAnyContent)
    }

    @Test
    fun backupFileNameUsesTimestampShape() {
        // 2026-11-14 01:40 附近（具体时区不影响形状，只校验格式）。
        val name = backupFileName(1_763_053_200_000L)
        assertTrue("实际：$name", Regex("""myfriend-backup-\d{8}-\d{4}\.json""").matches(name))
    }

    @Test
    fun importSummaryKeepsCountsAndStructuredWarnings() {
        // 句子由 UI（BackupText）按当前语言拼，这里只验证数据层给的东西够拼出那句话。
        val summary = ImportSummary(
            personas = 2,
            memories = 10,
            sessions = 3,
            messages = 99,
            rules = 1,
            skipped = 2,
            warnings = listOf(BackupWarning.ConfigsMissingKey(1)),
            mode = ImportMode.REPLACE
        )
        assertEquals(2, summary.personas)
        assertEquals(99, summary.messages)
        assertEquals(2, summary.skipped)
        assertEquals(ImportMode.REPLACE, summary.mode)
        assertEquals(listOf(BackupWarning.ConfigsMissingKey(1)), summary.warnings)
    }

    @Test
    fun mergeSummaryKeepsMode() {
        val summary = ImportSummary(personas = 1, memories = 1, sessions = 1, messages = 1)
        assertEquals(ImportMode.MERGE, summary.mode)
        assertEquals(0, summary.skipped)
        assertTrue(summary.warnings.isEmpty())
    }

    /**
     * 安全 P2-6（审计 P2-8）：预设头像与人格同一策略——备份里只写裸文件名。
     *
     * 0.11.0 修了 persona 的 `avatarUri = BackupAvatarPath.portable(...)`，preset 仍是
     * `avatarUri = avatarUri`：任一含头像预设的导出都会把
     * `file:///data/user/0/<pkg>/files/avatars/avatar-<uuid>.jpg` 写进 JSON（设备沙盒布局 + 文件名
     * 一起外泄，对换机恢复零收益）。
     */
    @Test
    fun presetsExportAvatarAsBareFileName() {
        val preset = PersonaPresetEntity(
            id = "preset-avatar",
            name = "带头像的模板",
            description = "",
            prompt = "你说话很慢",
            category = "温柔",
            avatarUri = "file:///data/user/0/com.myfriend.app/files/avatars/avatar-1234.jpg",
            avatarSeed = 3,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        ).toBackupPreset()
        assertEquals("预设头像只该写裸文件名", "avatar-1234.jpg", preset.avatarUri)

        val bundle = sampleBundle().copy(presets = listOf(preset))
        val encoded = BackupCodec.encode(bundle)
        // 注意：sampleBundle 的消息 attachmentsJson 仍带 `file:///data/media/...`（安全 P2-12
        // 是另一条），这里只断言**头像字段**不再出现设备路径。
        assertFalse(
            "预设头像不该把 files/avatars 的绝对路径写进备份",
            encoded.contains("files/avatars")
        )
        val decoded = decodeSuccess(encoded)
        assertEquals("avatar-1234.jpg", decoded.bundle.presets.single().avatarUri)
    }

    @Test
    fun presetsWithoutAvatarStayNull() {
        val entity = PersonaPresetEntity(
            id = "preset-null",
            name = "无头像",
            description = "",
            prompt = "",
            category = "",
            avatarUri = null,
            avatarSeed = 0,
            createdAt = 0L,
            updatedAt = 0L
        )
        assertNull(entity.toBackupPreset().avatarUri)
    }

    @Test
    fun decodedBundleCarriesTechniqueMetadata() {
        val decoded = decodeSuccess(BackupCodec.encode(sampleBundle()))
        assertEquals(BACKUP_FORMAT_VERSION, decoded.bundle.formatVersion)
        assertEquals("1.6.0", decoded.bundle.appVersion)
        assertTrue(decoded.bundle.apiKeysOmitted)
        assertNotNull(decoded.bundle.settings)
    }
}
