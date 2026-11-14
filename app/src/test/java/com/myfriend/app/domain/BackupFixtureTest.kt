package com.myfriend.app.domain

import com.myfriend.app.data.local.AgentIntentEntity
import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MemoryConsolidationEntity
import com.myfriend.app.data.local.MemoryEntity
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.PersonaPresetEntity
import com.myfriend.app.data.local.ProactiveRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 跨端备份的锚点（1.9 / 格式 v2）。
 *
 * 这份测试做四件事：
 * 1. 生成 `fixtures/backup/android-export-v2.json` —— Android 端导出的黄金样本，iOS 必须能读；
 * 2. 如果仓库里有 `fixtures/backup/ios-export-v2.json`，读回来断言关键字段还在；
 * 3. **v1 样本必须永远能读**：旧备份里的开关、节奏、额度字段被忽略，人格缺的字段用默认值；
 * 4. 任何一份样本都不允许带密钥字段。
 *
 * 样本是写进仓库的，不是运行时临时文件：谁改了备份格式而没改另一端，测试立刻红。
 */
class BackupFixtureTest {

    @Test
    fun `生成 Android 端的 v2 黄金样本`() {
        val json = BackupCodec.encode(sampleBundle())
        assertTrue(BackupCodec.decode(json) is BackupDecodeResult.Success)
        assertTrue(!BackupCodec.containsSecretField(json))

        val dir = File("../fixtures/backup")
        dir.mkdirs()
        val target = File(dir, "android-export-v2.json")
        target.writeText(json)
        assertTrue(target.length() > 0)
        println("fixture written: " + target.absolutePath + " (" + target.length() + " bytes)")
    }

    @Test
    fun `v2 样本往返后关键字段仍然一致`() {
        val original = sampleBundle()
        val decoded = BackupCodec.decode(BackupCodec.encode(original))
        val bundle = (decoded as BackupDecodeResult.Success).bundle

        assertEquals(BACKUP_FORMAT_NAME, bundle.format)
        assertEquals(2, bundle.formatVersion)

        val persona = bundle.personas.single()
        assertEquals(listOf("慢热", "嘴硬心软"), persona.traits)
        assertEquals(listOf("夜里散步"), persona.likes)
        assertEquals(listOf("先听完再给建议"), persona.behaviors)
        assertEquals("在南方长大，后来一个人搬到北方。", persona.backstory)
        assertEquals("叫你「阿深」；不说「加油」。", persona.quirks)
        assertEquals(4, persona.proactiveLevel)
        assertEquals(5, persona.memoryRetention)
        assertEquals(8, persona.extractionEvery)
        assertTrue(persona.sleepEnabled)
        assertEquals(8, persona.wakeThreshold)
        assertTrue(persona.dndOnSleep)

        // 一人一段：样本里只有一段，读回来也必须只有一段。
        assertEquals(1, bundle.sessions.size)
        assertEquals(persona.id, bundle.sessions.single().personaId)
        assertEquals(1, bundle.messages.count { it.readAt != null })

        // 设置里不该再出现 1.8 的那批字段。
        val encoded = BackupCodec.encode(bundle)
        listOf(
            "replyPace", "memoryEnabled", "autoMemoryEnabled", "memoryConsolidationEnabled",
            "webSearchScoringEnabled", "proactiveDailyLimit", "monthlyBudgetMicroUsd",
            "overBudgetChatOnly", "inputPriceUsdPerMillion", "outputPriceUsdPerMillion"
        ).forEach { key ->
            assertTrue("v2 备份里不该有设置项 $key", !encoded.contains("\"$key\""))
        }
    }

    @Test
    fun `v1 样本两端都还读得通`() {
        listOf("android-export-v1.json", "ios-export-v1.json").forEach { name ->
            val file = File("../fixtures/backup/$name")
            if (!file.exists()) {
                println("skip: 没有找到 $name")
                return@forEach
            }
            val result = BackupCodec.decode(file.readText())
            val success = result as? BackupDecodeResult.Success
            assertTrue("v1 样本 $name 读不出来：$result", success != null)
            val bundle = success!!.bundle
            assertEquals(BACKUP_FORMAT_NAME, bundle.format)
            assertEquals("读 v1 备份时要按 v2 尽力读出来", 2, bundle.formatVersion)
            assertTrue(bundle.personas.isNotEmpty())
            assertTrue(bundle.memories.isNotEmpty())
            assertTrue(bundle.sessions.isNotEmpty())
            assertTrue(bundle.messages.isNotEmpty())
            // 人格缺的 1.9 字段用默认值，而不是让整份备份读失败。
            val persona = bundle.personas.first()
            assertTrue(persona.traits.isEmpty())
            assertEquals(3, persona.proactiveLevel)
            assertEquals(3, persona.memoryRetention)
            assertEquals(6, persona.extractionEvery)
            assertTrue(persona.sleepEnabled)
            // 设置里旧的 1.8 字段被忽略，读出来的仍是合法的可移植设置。
            assertTrue(bundle.settings != null)
            assertTrue(bundle.settings!!.themeMode.isNotBlank())
            assertTrue(!BackupCodec.containsSecretField(file.readText()))
        }
    }

    @Test
    fun `iOS 导出的 v2 样本 Android 也接得住`() {
        val file = File("../fixtures/backup/ios-export-v2.json")
        if (!file.exists()) {
            println("skip: 还没有 iOS 导出的 v2 样本")
            return
        }
        val result = BackupCodec.decode(file.readText())
        val success = result as? BackupDecodeResult.Success
        assertTrue("iOS 导出的 v2 备份读不出来：$result", success != null)
        val bundle = success!!.bundle
        assertEquals(BACKUP_FORMAT_NAME, bundle.format)
        assertEquals(2, bundle.formatVersion)
        assertTrue(bundle.personas.isNotEmpty())
        assertTrue(bundle.sessions.isNotEmpty())
        // 一人一段：同一人格不允许出现两段（备份是接口契约，iOS 也要满足）。
        val duplicates = bundle.sessions.groupBy { it.personaId }.filterValues { it.size > 1 }
        assertTrue("iOS 的 v2 备份里同一个人格有多段会话：${duplicates.keys}", duplicates.isEmpty())
        assertTrue(!BackupCodec.containsSecretField(file.readText()))
    }

    private fun sampleBundle(): BackupBundle {
        val personaId = "11111111-1111-1111-1111-111111111111"
        val sessionId = "22222222-2222-2222-2222-222222222222"
        val now = 1_794_500_000_000L
        return BackupBundle(
            appVersion = "0.9.1",
            exportedAt = now,
            personas = listOf(
                BackupPersona(
                    id = personaId,
                    name = "林深",
                    description = "一个温柔而安静的朋友",
                    avatarSeed = 7,
                    prompt = "你是林深，说话从容、克制，先回应再展开。",
                    category = "朋友",
                    version = 3,
                    isDefault = true,
                    createdAt = now - 86_400_000L,
                    updatedAt = now,
                    quietSleepMinutes = 90,
                    quietWakeMinutes = 540,
                    mood = "平静",
                    moodIntensity = 2,
                    moodReason = "刚刚聊完一段安静的话",
                    traits = listOf("慢热", "嘴硬心软"),
                    likes = listOf("夜里散步"),
                    behaviors = listOf("先听完再给建议"),
                    backstory = "在南方长大，后来一个人搬到北方。",
                    quirks = "叫你「阿深」；不说「加油」。",
                    proactiveLevel = 4,
                    memoryRetention = 5,
                    extractionEvery = 8,
                    sleepEnabled = true,
                    wakeThreshold = 8,
                    dndOnSleep = true
                )
            ),
            memories = listOf(
                BackupMemory(
                    id = "m-1",
                    personaId = personaId,
                    content = "你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套。",
                    originalContent = "对方提过下个月去哈尔滨，怕冷，要提醒带外套。",
                    category = "PREFERENCE",
                    drawer = "USER",
                    importance = 5,
                    pinned = true,
                    sourceSessionId = sessionId,
                    sourceQuote = "我下个月要去哈尔滨",
                    createdAt = now - 7_200_000L,
                    updatedAt = now - 3_600_000L,
                    lastUsedAt = now - 600_000L,
                    useCount = 3,
                    status = "ACTIVE",
                    strength = 0.86,
                    tags = "[\"哈尔滨\",\"怕冷\"]",
                    happenedAt = now + 1_209_600_000L,
                    driftCount = 1
                )
            ),
            sessions = listOf(
                BackupSession(
                    id = sessionId,
                    personaId = personaId,
                    title = "下个月去哈尔滨",
                    createdAt = now - 7_200_000L,
                    updatedAt = now,
                    lastMessageAt = now,
                    lastMessagePreview = "记得带厚外套",
                    pinned = false,
                    unreadCount = 0,
                    summary = "你提过下个月要去哈尔滨，怕冷。",
                    summarizedUpTo = now - 600_000L,
                    summaryAt = now - 300_000L,
                    memoryRetentionOverride = 4,
                    extractionEveryOverride = 10
                )
            ),
            messages = listOf(
                BackupMessage(
                    id = "msg-1",
                    sessionId = sessionId,
                    personaId = personaId,
                    role = "USER",
                    content = "我下个月要去哈尔滨",
                    status = "COMPLETE",
                    createdAt = now - 7_200_000L,
                    updatedAt = now - 7_200_000L,
                    readAt = now - 7_100_000L
                ),
                BackupMessage(
                    id = "msg-2",
                    sessionId = sessionId,
                    personaId = personaId,
                    role = "ASSISTANT",
                    content = "记得带厚外套，那边比这边冷得多。",
                    status = "COMPLETE",
                    createdAt = now - 7_100_000L,
                    updatedAt = now - 7_100_000L,
                    toolTraceJson = "[\"web_search\"]"
                )
            ),
            presets = listOf(
                BackupPreset(
                    id = "p-1",
                    name = "安静的朋友",
                    description = "话不多，但一直在",
                    category = "朋友",
                    prompt = "你是他安静的朋友。",
                    avatarSeed = 3,
                    createdAt = now - 172_800_000L,
                    updatedAt = now - 172_800_000L
                )
            ),
            proactiveRules = listOf(
                BackupProactiveRule(
                    id = "r-1",
                    personaId = personaId,
                    kind = "DAILY",
                    timeOfDayMinutes = 21 * 60 + 30,
                    hint = "问问他今天过得怎么样",
                    note = "别像查岗",
                    enabled = true,
                    source = "AI",
                    nextTriggerAt = now + 3_600_000L,
                    createdAt = now - 86_400_000L,
                    updatedAt = now - 86_400_000L
                )
            ),
            agentIntents = listOf(
                BackupAgentIntent(
                    id = "i-1",
                    personaId = personaId,
                    sessionId = sessionId,
                    kind = "FOLLOW_UP",
                    earliestAt = now + 3_600_000L,
                    latestAt = now + 5_400_000L,
                    note = "问问他厚外套买了没",
                    hint = "别像查岗",
                    status = "PENDING",
                    createdAt = now - 1_800_000L
                )
            ),
            memoryConsolidations = listOf(
                BackupMemoryConsolidation(
                    id = "c-1",
                    personaId = personaId,
                    at = now - 3_600_000L,
                    kept = 4,
                    merged = 2,
                    archived = 1,
                    forgotten = 0,
                    summary = "把重复的两条并成一条"
                )
            ),
            apiConfigs = listOf(
                BackupApiConfig(
                    id = "a-1",
                    name = "本地参考服务",
                    provider = "OPENAI",
                    baseUrl = "http://10.0.2.2:8787/v1",
                    model = "myfriend-reference",
                    allowInsecureHttp = true,
                    createdAt = now - 86_400_000L,
                    updatedAt = now - 86_400_000L
                )
            ),
            settings = BackupSettings(
                themeMode = "SYSTEM",
                bubbleStyle = "SOFT",
                activePersonaId = personaId
            )
        )
    }
}
