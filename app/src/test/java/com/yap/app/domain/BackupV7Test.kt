package com.yap.app.domain

import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.MentalActivityEntity
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.SessionMemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份格式 v7（0.15）：人格语言/拉黑、消息 blocked、心理活动、会话备忘录、服务端 extensions。
 *
 * 纯 JVM（构造实体 → 编解码 → 断言），不碰 Room：
 * 「哪张表的哪一列进了备份、从备份回到哪一列」在这里钉死；
 * 「导入是不是真的写库」由 `BackupRepository` 的接线契约测试盯。
 *
 * v7 的跨端契约（与 iOS 对齐）：
 * - 顶层 `mentalActivities[]` / `sessionMemory[]`；服务端备份放在 `extensions` 里，
 *   读的时候**顶层优先、缺失/空数组回退 extensions**；
 * - `sessionMemory[].topics` 是数组（`{timeLabel,summary}`），旧写法 `topicsJson` 字符串可读；
 * - `personas[].languageTag` 与 `blockedUntil/blockReason/blockProposalJson`、`messages[].blocked`；
 * - `media[].sha256`（见 MediaIntegrityTest 的校验用例）。
 */
class BackupV7Test {

    // ------------------------------------------------------------------
    // 版本与顶层段
    // ------------------------------------------------------------------

    @Test
    fun `格式版本是 v7 且顶层有两张 0_15 新表`() {
        assertEquals(7, BACKUP_FORMAT_VERSION)
        assertEquals(7, BackupBundle().formatVersion)
        val bundle = BackupBundle()
        assertTrue(bundle.mentalActivities.isEmpty())
        assertTrue(bundle.sessionMemory.isEmpty())
        assertEquals(MediaHashes.HEX_LENGTH, 64)
    }

    @Test
    fun `v7 往返幂等 再编码与第一次完全一致`() {
        val bundle = sampleBundle()
        val first = BackupCodec.encode(bundle)
        val decoded = BackupCodec.decode(first) as BackupDecodeResult.Success
        val second = BackupCodec.encode(decoded.bundle)
        assertEquals("导出 → 读回 → 再导出必须逐字节一致（幂等）", first, second)
        assertFalse("任何密钥字段都不该出现", BackupCodec.containsSecretField(first))

        // 新段都在；顺序与数量不变（数组顺序即契约）。
        assertEquals(2, decoded.bundle.mentalActivities.size)
        assertEquals(1, decoded.bundle.sessionMemory.size)
        assertEquals(listOf("mental-a", "mental-b"), decoded.bundle.mentalActivities.map { it.id })
    }

    // ------------------------------------------------------------------
    // 人格：语言 / 拉黑 / 待确认提议
    // ------------------------------------------------------------------

    @Test
    fun `人格的语言与拉黑字段进出备份都保留`() {
        val entity = persona(
            languageTag = "en",
            blockedUntil = 1_794_600_000_000L,
            blockReason = "需要停一下",
            blockProposalJson = """{"proposalId":"p-1","hours":6}"""
        )
        val backup = entity.toBackupPersona()
        assertEquals("en", backup.languageTag)
        assertEquals(1_794_600_000_000L, backup.blockedUntil)
        assertEquals("需要停一下", backup.blockReason)
        assertTrue(backup.blockProposalJson!!.contains("proposal-id".let { "p-1" }))

        val restored = backup.toEntity()
        assertEquals("en", restored.languageTag)
        assertEquals(1_794_600_000_000L, restored.blockedUntil)
        assertEquals("需要停一下", restored.blockReason)
        assertEquals(backup.blockProposalJson, restored.blockProposalJson)
    }

    @Test
    fun `人格语言标签在导入时归一 认不出就落 null 而不是写坏值`() {
        assertEquals("zh", BackupPersona(languageTag = "zh-CN").toEntity().languageTag)
        assertEquals("en", BackupPersona(languageTag = "EN").toEntity().languageTag)
        assertEquals("ja", BackupPersona(languageTag = "ja-JP").toEntity().languageTag)
        assertNull("认不出的标签不能原样写进库", BackupPersona(languageTag = "xx-YY").toEntity().languageTag)
        assertNull(BackupPersona(languageTag = "  ").toEntity().languageTag)
        assertNull("v6 老备份没有这个字段", BackupPersona().toEntity().languageTag)

        // 有效期 <= 0 的拉黑值没有意义：读进来就是「没拉黑」，不留一个 0/负数在库里。
        assertNull(BackupPersona(blockedUntil = 0L).toEntity().blockedUntil)
        assertNull(BackupPersona(blockedUntil = -1L).toEntity().blockedUntil)
    }

    // ------------------------------------------------------------------
    // 消息 blocked
    // ------------------------------------------------------------------

    @Test
    fun `消息的 blocked 标记跟着备份走`() {
        val entity = MessageEntity(
            id = "msg-1",
            sessionId = "s1",
            personaId = "p1",
            role = MessageRole.USER,
            content = "在吗",
            createdAt = 1_000L,
            updatedAt = 1_000L,
            status = MessageStatus.COMPLETE,
            errorMessage = null,
            model = null,
            edited = false,
            blocked = true
        )
        val backup = entity.toBackupMessage()
        assertTrue(backup.blocked)
        assertTrue(backup.toEntity().blocked)

        assertFalse(BackupMessage().blocked)
        assertFalse(BackupMessage().toEntity().blocked)
    }

    // ------------------------------------------------------------------
    // 服务端 extensions：顶层优先 / 回退 / 忽略 proactiveFailures
    // ------------------------------------------------------------------

    @Test
    fun `服务端 extensions 里的两张表在顶层缺失时被读出来`() {
        val raw = """{
          "format":"myfriend.backup","formatVersion":7,"apiKeysOmitted":true,
          "personas":[{"id":"p1","name":"他"}],
          "extensions":{
            "mediaSkipped":3,
            "mentalActivities":[
              {"id":"m1","personaId":"p1","sessionId":"s1","at":10,"thought":"他在等对方先开口",
               "moodLabel":"平静","moodValence":0,"moodArousal":2,"moodDominance":3,"moodIntensity":2}
            ],
            "sessionMemory":[
              {"sessionId":"s1","personaId":"p1","shortSummary":"短期","shortUserMessages":5,
               "topics":[{"timeLabel":"2026-11-19 下午","summary":"聊搬家"}],
               "topicUserMessages":5}
            ],
            "proactiveFailures":[{"id":"f1","personaId":"p1","stage":"generation","reason":"x"}]
          }
        }"""
        val success = BackupCodec.decode(raw) as BackupDecodeResult.Success
        assertEquals(1, success.bundle.mentalActivities.size)
        assertEquals("他在等对方先开口", success.bundle.mentalActivities.single().thought)
        assertEquals(1, success.bundle.sessionMemory.size)
        assertEquals("短期", success.bundle.sessionMemory.single().shortSummary)
        assertEquals(5L, success.bundle.sessionMemory.single().shortUserMessages)
        assertEquals("季度话题数组", 1, success.bundle.sessionMemory.single().topics.size)
        // proactiveFailures 客户端没有本地表：不读、不报错、不写回。
        val reencoded = BackupCodec.encode(success.bundle)
        assertFalse(reencoded.contains("proactiveFailures"))
    }

    @Test
    fun `顶层段优先于 extensions 同名字段`() {
        val raw = """{
          "format":"myfriend.backup","formatVersion":7,
          "personas":[{"id":"p1","name":"他"}],
          "mentalActivities":[
            {"id":"top-1","personaId":"p1","at":1,"thought":"顶层"}
          ],
          "sessionMemory":[{"sessionId":"s-top","personaId":"p1","shortSummary":"顶层"}],
          "extensions":{
            "mentalActivities":[{"id":"ext-1","personaId":"p1","at":2,"thought":"夹层"}],
            "sessionMemory":[{"sessionId":"s-ext","personaId":"p1","shortSummary":"夹层"}]
          }
        }"""
        val bundle = (BackupCodec.decode(raw) as BackupDecodeResult.Success).bundle
        assertEquals(listOf("top-1"), bundle.mentalActivities.map { it.id })
        assertEquals(listOf("s-top"), bundle.sessionMemory.map { it.sessionId })
    }

    @Test
    fun `extensions 里的两张表也可以整段坏掉或缺失 不让整份失败`() {
        val raw = """{
          "format":"myfriend.backup","formatVersion":7,
          "personas":[{"id":"p1","name":"他"}],
          "extensions":{"mentalActivities":5,"sessionMemory":[{"personaId":"p1"},{"sessionId":"s1","personaId":"p1","shortSummary":"好的"}]}
        }"""
        val success = BackupCodec.decode(raw) as BackupDecodeResult.Success
        assertTrue("不是数组的 mentalActivities 按空处理", success.bundle.mentalActivities.isEmpty())
        assertEquals("坏条目（缺 sessionId）丢掉，好的留下", 1, success.bundle.sessionMemory.size)
        assertEquals("s1", success.bundle.sessionMemory.single().sessionId)
    }

    // ------------------------------------------------------------------
    // 旧备份（v6 及以前）：新段缺失 = 空，不丢已有数据
    // ------------------------------------------------------------------

    @Test
    fun `v6 备份读出来两张新表为空 且只留一句旧格式提醒`() {
        val raw = """{
          "format":"myfriend.backup","formatVersion":6,"appVersion":"0.13.0","apiKeysOmitted":true,
          "personas":[{"id":"p1","name":"他","worldJson":"{\"worldName\":\"城\"}"}],
          "sessions":[{"id":"s1","personaId":"p1"}],
          "messages":[{"id":"m1","sessionId":"s1","personaId":"p1","content":"在吗"}],
          "media":[]
        }"""
        val success = BackupCodec.decode(raw) as BackupDecodeResult.Success
        assertTrue(success.bundle.mentalActivities.isEmpty())
        assertTrue(success.bundle.sessionMemory.isEmpty())
        assertNull("v6 没有语言标签", success.bundle.personas.single().languageTag)
        assertFalse(success.bundle.messages.single().blocked)
        assertTrue(
            "v6 备份要留一句「旧格式尽力读」的提醒",
            success.warnings.any { it is BackupWarning.OlderFormat && it.version == 6 }
        )
        // 旧格式的关键内容一条都不能丢。
        assertEquals("他", success.bundle.personas.single().name)
        assertEquals(1, success.bundle.sessions.size)
        assertEquals(1, success.bundle.messages.size)
    }

    @Test
    fun `比本机更高的格式明确拒绝 而不是半懂不懂地读`() {
        val raw = """{"format":"myfriend.backup","formatVersion":8,"personas":[]}"""
        val failure = BackupCodec.decode(raw) as BackupDecodeResult.Failure
        assertEquals(BackupFailure.NewerFormat(8, 7), failure.reason)
    }

    // ------------------------------------------------------------------
    // 实体 ⇄ 备份的「字段覆盖率」
    // ------------------------------------------------------------------

    @Test
    fun `心理活动导入时按规范截断念头并夹取心情维度`() {
        val restored = BackupMentalActivity(
            id = "m1",
            personaId = "p1",
            at = 1L,
            thought = "很长的念头".repeat(40),
            moodIntensity = 99,
            moodValence = -99,
            moodArousal = 99,
            moodDominance = -3
        ).toEntity()
        assertTrue("念头按规范截到 60 字", restored.thought.length <= MentalActivity.MAX_THOUGHT_CHARS)
        assertEquals(5, restored.moodIntensity)
        assertEquals(MoodDimensions.VALENCE_MIN, restored.moodValence)
        assertEquals(MoodDimensions.AROUSAL_MAX, restored.moodArousal)
        assertEquals(MoodDimensions.DOMINANCE_MIN, restored.moodDominance)
    }

    @Test
    fun `会话备忘录往返保留游标与主题总结`() {
        val entity = SessionMemoryEntity(
            sessionId = "s1",
            personaId = "p1",
            shortSummary = "短期",
            longSummary = "长期",
            shortUserCount = 5,
            longUserCount = 30,
            shortUpdatedAt = 100L,
            longUpdatedAt = 200L,
            updatedAt = 200L,
            topicsJson = TopicSummary.encode(
                listOf(MemoryContract.TopicSegment("2026-11-19 下午", "聊搬家"))
            ),
            topicUserCount = 25,
            topicUpdatedAt = 150L
        )
        val decoded = (BackupCodec.decode(BackupCodec.encode(BackupBundle(
            personas = listOf(BackupPersona(id = "p1", name = "他")),
            sessionMemory = listOf(entity.toBackupSessionMemory())
        ))) as BackupDecodeResult.Success).bundle
        val restored = decoded.sessionMemory.single().toEntity("s1")
        assertEquals(entity.shortUserCount, restored.shortUserCount)
        assertEquals(entity.longUserCount, restored.longUserCount)
        assertEquals(entity.topicUserCount, restored.topicUserCount)
        assertEquals(entity.shortSummary, restored.shortSummary)
        assertEquals(entity.longSummary, restored.longSummary)
        assertEquals(
            "主题总结要经 topics 数组跨端往返",
            "聊搬家",
            TopicSummary.parse(restored.topicsJson!!).single().summary
        )
    }

    // ------------------------------------------------------------------
    // 样例
    // ------------------------------------------------------------------

    private fun sampleBundle(): BackupBundle = BackupBundle(
        appVersion = "0.15.0",
        exportedAt = 1_794_600_000_000L,
        personas = listOf(
            persona(
                languageTag = "zh",
                blockedUntil = 1_794_600_100_000L,
                blockReason = "累了",
                blockProposalJson = """{"proposalId":"p-1","hours":6}"""
            ).toBackupPersona()
        ),
        sessions = listOf(BackupSession(id = "s1", personaId = "p1")),
        messages = listOf(
            BackupMessage(
                id = "msg-1",
                sessionId = "s1",
                personaId = "p1",
                role = "USER",
                content = "在吗",
                blocked = true,
                readAt = 1_794_600_000_000L
            )
        ),
        mentalActivities = listOf(
            BackupMentalActivity(
                id = "mental-a",
                personaId = "p1",
                sessionId = "s1",
                at = 10L,
                thought = "他在等我先开口",
                moodLabel = "平静",
                moodIntensity = 2,
                moodValence = 0,
                moodArousal = 2,
                moodDominance = 3
            ),
            BackupMentalActivity(
                id = "mental-b",
                personaId = "p1",
                at = 20L,
                thought = "要不要主动一点",
                moodLabel = "犹豫",
                moodIntensity = 2
            )
        ),
        sessionMemory = listOf(
            BackupSessionMemory(
                sessionId = "s1",
                personaId = "p1",
                shortSummary = "短期：刚聊完搬家",
                shortUpdatedAt = 1_794_599_000_000L,
                shortUserMessages = 5L,
                longSummary = "长期：他在准备换工作",
                longUpdatedAt = 1_794_598_000_000L,
                longUserMessages = 18L,
                longCheckedUserMessages = 20L,
                topics = listOf(BackupTopicSegment("2026-11-19 下午", "聊搬家")),
                topicUpdatedAt = 1_794_597_000_000L,
                topicUserMessages = 12L,
                topicCheckedUserMessages = 15L
            )
        )
    )

    private fun persona(
        languageTag: String? = null,
        blockedUntil: Long? = null,
        blockReason: String? = null,
        blockProposalJson: String? = null
    ): PersonaEntity = PersonaEntity(
        id = "p1",
        name = "他",
        description = "朋友",
        avatarUri = "avatar.jpg",
        avatarSeed = 1,
        prompt = "你是他。",
        category = "朋友",
        version = 1,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = true,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        languageTag = languageTag,
        blockedUntil = blockedUntil,
        blockReason = blockReason,
        blockProposalJson = blockProposalJson
    )
}
