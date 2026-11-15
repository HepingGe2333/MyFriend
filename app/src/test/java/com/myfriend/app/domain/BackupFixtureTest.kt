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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 跨端备份的锚点（0.12 / 格式 v3）。
 *
 * 这份测试做五件事：
 * 1. 生成 `fixtures/backup/android-export-v3.json` —— Android 端导出的黄金样本，iOS 必须能读；
 *    样本里带一张头像 jpeg 与一张聊天 png（base64），跨端要能逐字节解回来；
 * 2. 如果仓库里有 `fixtures/backup/ios-export-v3.json`，读回来断言关键字段还在；
 * 3. **v1/v2 样本必须永远能读**：旧备份里的开关、节奏、额度字段被忽略，
 *    没有 `media` 时行为与本轮之前完全一致（图片只剩文字 + warning）；
 * 4. 任何一份样本都不允许带密钥字段 / 设备沙盒路径；
 * 5. v3 的 JSON 结构（字段名、大小写、base64 无换行）逐项钉死。
 *
 * 样本是写进仓库的，不是运行时临时文件：谁改了备份格式而没改另一端，测试立刻红。
 */
class BackupFixtureTest {

    @Test
    fun `生成 Android 端的 v3 黄金样本`() {
        val json = BackupCodec.encode(sampleBundle())
        assertTrue(BackupCodec.decode(json) is BackupDecodeResult.Success)
        assertTrue(!BackupCodec.containsSecretField(json))
        // 设备路径一个都不许出现：头像与附件都只写裸文件名。
        listOf("file:///data", "files/avatars", "files/media", "/data/user/0").forEach { marker ->
            assertTrue("v3 备份里不该出现设备路径 $marker", !json.contains(marker))
        }

        val dir = File("../fixtures/backup")
        dir.mkdirs()
        val target = File(dir, "android-export-v3.json")
        target.writeText(json)
        assertTrue(target.length() > 0)
        println("fixture written: " + target.absolutePath + " (" + target.length() + " bytes)")
    }

    @Test
    fun `v3 样本往返后关键字段与图片字节仍然一致`() {
        val original = sampleBundle()
        val decoded = BackupCodec.decode(BackupCodec.encode(original))
        val bundle = (decoded as BackupDecodeResult.Success).bundle

        assertEquals(BACKUP_FORMAT_NAME, bundle.format)
        assertEquals(3, bundle.formatVersion)

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
        assertEquals("头像只写裸文件名", "avatar-fixture.jpg", persona.avatarUri)

        // 一人一段：样本里只有一段，读回来也必须只有一段。
        assertEquals(1, bundle.sessions.size)
        assertEquals(persona.id, bundle.sessions.single().personaId)
        assertEquals(1, bundle.messages.count { it.readAt != null })

        // 图片本体：两张都在，且 base64 解出来的字节与声明的 bytes 一致。
        assertEquals(2, bundle.media.size)
        val avatar = bundle.media.single { it.kind == BackupMediaKind.PERSONA_AVATAR }
        val chat = bundle.media.single { it.kind == BackupMediaKind.CHAT_IMAGE }
        assertEquals(persona.id, avatar.personaId)
        assertEquals("avatar-fixture.jpg", avatar.fileName)
        assertEquals("image/jpeg", avatar.mime)
        assertEquals(JPEG_BYTES, avatar.bytes)
        assertArrayEquals(jpegBytes(), BackupBase64.decode(avatar.data))
        assertEquals("chat-fixture.png", chat.fileName)
        assertEquals("image/png", chat.mime)
        assertEquals(PNG_BYTES, chat.bytes)
        assertArrayEquals(pngBytes(), BackupBase64.decode(chat.data))

        // 消息附件只写裸文件名（导入时按 media 重建），不写设备路径。
        val attachmentJson = bundle.messages.single { it.id == "msg-1" }.attachmentsJson
            ?: error("msg-1 应当带着附件")
        assertTrue(attachmentJson.contains("\"uri\":\"chat-fixture.png\""))
        assertTrue(!attachmentJson.contains("file://"))

        // 设置里不该再出现 1.8 的那批字段。
        val encoded = BackupCodec.encode(bundle)
        listOf(
            "replyPace", "memoryEnabled", "autoMemoryEnabled", "memoryConsolidationEnabled",
            "webSearchScoringEnabled", "proactiveDailyLimit", "monthlyBudgetMicroUsd",
            "overBudgetChatOnly", "inputPriceUsdPerMillion", "outputPriceUsdPerMillion"
        ).forEach { key ->
            assertTrue("v3 备份里不该有设置项 $key", !encoded.contains("\"$key\""))
        }
    }

    /**
     * v3 的 JSON 结构是跨端契约：字段名/大小写/顺序之外，`bytes` 必须是数字、
     * `data` 必须是标准 base64 且不带换行。
     */
    @Test
    fun `v3 media 的 JSON 结构逐项钉死`() {
        val json = BackupCodec.encode(sampleBundle())
        // 顶层 media 是数组；每条含 7 个字段，名字与大小写与 iOS 完全一致。
        listOf(
            "\"media\":[", "\"id\":", "\"kind\":\"PERSONA_AVATAR\"", "\"kind\":\"CHAT_IMAGE\"",
            "\"personaId\":", "\"fileName\":", "\"mime\":", "\"bytes\":", "\"data\":"
        ).forEach { marker -> assertTrue("缺少 $marker", json.contains(marker)) }
        // 大小写敏感：不接受 snake_case / 小写别名。
        listOf("\"file_name\"", "\"mime_type\"", "\"kind\":\"chat_image\"").forEach { marker ->
            assertTrue("不该出现 $marker", !json.contains(marker))
        }
        // base64 里不许有换行/空白（JSON 字符串本身用 \n 转义也不行）。
        val dataSegments = Regex("\"data\":\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(2, dataSegments.size)
        dataSegments.forEach { data ->
            assertTrue("base64 不该含换行", !data.contains("\\n") && !data.contains("\n"))
            assertTrue("base64 不该含空格", !data.contains(" "))
            assertEquals("标准 base64 带 padding", 0, data.length % 4)
            assertTrue("只允许标准字母表", data.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
        }
        // bytes 是 JSON 数字，不是字符串。
        assertTrue(json.contains("\"bytes\":$JPEG_BYTES"))
        assertTrue(json.contains("\"bytes\":$PNG_BYTES"))
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
            assertEquals("读 v1 备份时要按 v3 尽力读出来", 3, bundle.formatVersion)
            assertTrue(bundle.personas.isNotEmpty())
            assertTrue(bundle.memories.isNotEmpty())
            assertTrue(bundle.sessions.isNotEmpty())
            assertTrue(bundle.messages.isNotEmpty())
            // 老备份没有 media：空的，不是崩。
            assertTrue("v1 备份没有 media", bundle.media.isEmpty())
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
    fun `v2 样本仍然读得通 行为与 v2 之前一致`() {
        listOf("android-export-v2.json", "ios-export-v2.json").forEach { name ->
            val file = File("../fixtures/backup/$name")
            if (!file.exists()) {
                println("skip: 没有找到 $name")
                return@forEach
            }
            val result = BackupCodec.decode(file.readText())
            val success = result as? BackupDecodeResult.Success
            assertTrue("v2 样本 $name 读不出来：$result", success != null)
            val bundle = success!!.bundle
            assertEquals(BACKUP_FORMAT_NAME, bundle.format)
            assertEquals(3, bundle.formatVersion)
            // v2 → v3 会留一句「旧格式尽力读」的提醒。
            assertTrue(
                "读 v2 要留一句格式提醒",
                success.warnings.any { it is BackupWarning.OlderFormat && it.version == 2 }
            )
            assertTrue(bundle.personas.isNotEmpty())
            assertTrue("v2 没有 media", bundle.media.isEmpty())
            assertTrue(!BackupCodec.containsSecretField(file.readText()))
        }
    }

    @Test
    fun `iOS 导出的 v3 样本 Android 也接得住`() {
        val file = File("../fixtures/backup/ios-export-v3.json")
        if (!file.exists()) {
            println("skip: 还没有 iOS 导出的 v3 样本")
            return
        }
        val result = BackupCodec.decode(file.readText())
        val success = result as? BackupDecodeResult.Success
        assertTrue("iOS 导出的 v3 备份读不出来：$result", success != null)
        val bundle = success!!.bundle
        assertEquals(BACKUP_FORMAT_NAME, bundle.format)
        assertEquals(3, bundle.formatVersion)
        assertTrue(bundle.personas.isNotEmpty())
        assertTrue(bundle.sessions.isNotEmpty())
        // 一人一段：同一人格不允许出现两段（备份是接口契约，iOS 也要满足）。
        val duplicates = bundle.sessions.groupBy { it.personaId }.filterValues { it.size > 1 }
        assertTrue("iOS 的 v3 备份里同一个人格有多段会话：${duplicates.keys}", duplicates.isEmpty())
        // 图片本体跨端可解：iOS 的 base64 必须能被 Kotlin 解出来，字节数对得上。
        assertTrue("iOS 的 v3 样本应当带图片", bundle.media.isNotEmpty())
        bundle.media.forEach { media ->
            assertTrue("iOS 的 media 缺少 kind", media.kind in BackupMediaKind.all)
            val bytes = BackupBase64.decode(media.data)
            assertTrue("iOS 的 media base64 解不出来：${media.fileName}", bytes != null)
            assertEquals("media.bytes 与 base64 解出的字节数不一致：${media.fileName}", media.bytes, bytes!!.size)
        }
        assertTrue(!BackupCodec.containsSecretField(file.readText()))
    }

    private fun jpegBytes(): ByteArray = BackupBase64.decode(JPEG_BASE64)!!
    private fun pngBytes(): ByteArray = BackupBase64.decode(PNG_BASE64)!!

    private fun sampleBundle(): BackupBundle {
        val personaId = "11111111-1111-1111-1111-111111111111"
        val sessionId = "22222222-2222-2222-2222-222222222222"
        val now = 1_794_500_000_000L
        return BackupBundle(
            appVersion = "0.12.0",
            exportedAt = now,
            personas = listOf(
                BackupPersona(
                    id = personaId,
                    name = "林深",
                    description = "一个温柔而安静的朋友",
                    avatarUri = "avatar-fixture.jpg",
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
                    attachmentsJson = "[{\"uri\":\"chat-fixture.png\",\"mime\":\"image/png\",\"width\":2,\"height\":2}]",
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
            media = listOf(
                BackupMedia(
                    id = "media-avatar-1",
                    kind = BackupMediaKind.PERSONA_AVATAR,
                    personaId = personaId,
                    fileName = "avatar-fixture.jpg",
                    mime = "image/jpeg",
                    bytes = JPEG_BYTES,
                    data = JPEG_BASE64
                ),
                BackupMedia(
                    id = "media-chat-1",
                    kind = BackupMediaKind.CHAT_IMAGE,
                    personaId = personaId,
                    fileName = "chat-fixture.png",
                    mime = "image/png",
                    bytes = PNG_BYTES,
                    data = PNG_BASE64
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

    private companion object {
        /** 2×2 的 jpeg（632 字节）：样本要“真的能解码的图”，但不能把 fixture 撑大。 */
        const val JPEG_BASE64 =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikxMC4pLSwzOko+MzZGNywtQFdBRkxOUlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0//wAARCAACAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwCeiiivMPZP/9k="
        const val JPEG_BYTES = 632
        /** 2×2 的 png（75 字节）。 */
        const val PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEklEQVR4nGM8URHAwMDAxAAGABNiAZSfXW4zAAAAAElFTkSuQmCC"
        const val PNG_BYTES = 75
    }
}
