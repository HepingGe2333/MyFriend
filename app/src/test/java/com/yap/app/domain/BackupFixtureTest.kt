package com.yap.app.domain

import com.yap.app.data.local.AgentIntentEntity
import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MemoryConsolidationEntity
import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPresetEntity
import com.yap.app.data.local.ProactiveRuleEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 跨端备份的锚点（0.13 / 格式 v6）。
 *
 * 这份测试做五件事：
 * 1. 生成 `fixtures/backup/android-export-v6.json` —— Android 端导出的黄金样本，iOS 必须能读；
 *    样本里带一张头像 jpeg、一张聊天 png、一张表情 GIF、一段语音与一段视频（base64），
 *    跨端要能逐字节解回来；同时带上 v6 的 `stickers[]` / `diaryNotes[]`；
 * 2. 如果仓库里有 `fixtures/backup/ios-export-v6.json`，读回来断言关键字段还在；v5/v4/v3 仍可读；
 * 3. **v1/v2 样本必须永远能读**：旧备份里的开关、节奏、额度字段被忽略，
 *    没有 `media` 时行为与本轮之前完全一致（图片只剩文字 + warning）；
 * 4. 任何一份样本都不允许带密钥字段 / 设备沙盒路径；
 * 5. v6 的 JSON 结构（provider 字段、引导字段、stickers/diaryNotes、base64 无换行）逐项钉死。
 *
 * 样本是写进仓库的，不是运行时临时文件：谁改了备份格式而没改另一端，测试立刻红。
 * 旧的 `android-export-v5.json` 保留不动（旧端读新端、新端读旧端都要有样本）。
 */
class BackupFixtureTest {

    @Test
    fun `生成 Android 端的 v6 黄金样本`() {
        val json = BackupCodec.encode(sampleBundle())
        assertTrue(BackupCodec.decode(json) is BackupDecodeResult.Success)
        assertTrue(!BackupCodec.containsSecretField(json))
        // 设备路径一个都不许出现：头像、附件、表情都只写裸文件名。
        listOf("file:///data", "files/avatars", "files/media", "files/stickers", "files/audio", "files/video", "/data/user/0")
            .forEach { marker ->
                assertTrue("v6 备份里不该出现设备路径 $marker", !json.contains(marker))
            }

        val dir = File("../fixtures/backup")
        dir.mkdirs()
        val target = File(dir, "android-export-v6.json")
        target.writeText(json)
        assertTrue(target.length() > 0)
        println("fixture written: " + target.absolutePath + " (" + target.length() + " bytes)")
    }

    @Test
    fun `v6 样本往返后关键字段与媒体字节仍然一致`() {
        val original = sampleBundle()
        val decoded = BackupCodec.decode(BackupCodec.encode(original))
        val bundle = (decoded as BackupDecodeResult.Success).bundle

        assertEquals(BACKUP_FORMAT_NAME, bundle.format)
        assertEquals(6, bundle.formatVersion)

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
        // v5：人设引导字段原样往返。
        assertTrue(persona.worldJson!!.contains("临港新城·云筑建筑事务所"))
        assertTrue(persona.speechStyleJson!!.contains("简短直接"))
        assertTrue(persona.activityJson!!.contains("在事务所赶第三版方案"))
        val preset = bundle.presets.single()
        assertTrue(preset.worldJson!!.contains("临港新城·云筑建筑事务所"))
        assertTrue(preset.speechStyleJson!!.contains("简短直接"))

        // 一人一段：样本里只有一段，读回来也必须只有一段。
        assertEquals(1, bundle.sessions.size)
        assertEquals(persona.id, bundle.sessions.single().personaId)
        assertEquals(1, bundle.messages.count { it.readAt != null })

        // 媒体本体：五种 kind 都在，且 base64 解出来的字节与声明的 bytes 一致。
        assertEquals(5, bundle.media.size)
        val avatar = bundle.media.single { it.kind == BackupMediaKind.PERSONA_AVATAR }
        val chat = bundle.media.single { it.kind == BackupMediaKind.CHAT_IMAGE }
        val stickerMedia = bundle.media.single { it.kind == BackupMediaKind.STICKER }
        val audioMedia = bundle.media.single { it.kind == BackupMediaKind.AUDIO }
        val videoMedia = bundle.media.single { it.kind == BackupMediaKind.VIDEO }
        assertEquals(persona.id, avatar.personaId)
        assertEquals("avatar-fixture.jpg", avatar.fileName)
        assertEquals("image/jpeg", avatar.mime)
        assertEquals(JPEG_BYTES, avatar.bytes)
        assertArrayEquals(jpegBytes(), BackupBase64.decode(avatar.data))
        assertEquals("chat-fixture.png", chat.fileName)
        assertEquals("image/png", chat.mime)
        assertEquals(PNG_BYTES, chat.bytes)
        assertArrayEquals(pngBytes(), BackupBase64.decode(chat.data))
        assertEquals("sticker-fixture.gif", stickerMedia.fileName)
        assertEquals("image/gif", stickerMedia.mime)
        assertArrayEquals(gifBytes(), BackupBase64.decode(stickerMedia.data))
        assertEquals("voice-fixture.m4a", audioMedia.fileName)
        assertEquals("audio/m4a", audioMedia.mime)
        assertEquals("video-fixture.mp4", videoMedia.fileName)
        assertEquals("video/mp4", videoMedia.mime)

        // v6：表情行与 media 里的 GIF 一一对应（不重复内联 base64）。
        val sticker = bundle.stickers.single()
        assertEquals("sticker-fixture.gif", sticker.fileName)
        assertEquals("跨端别名 file 也要写出来", "sticker-fixture.gif", sticker.file)
        assertEquals("sticker-fixture.gif", sticker.portableFileName)
        assertEquals("image/gif", sticker.mime)
        assertEquals(1, sticker.frames)
        assertEquals(persona.id, sticker.personaId)
        assertEquals("user-fixture", sticker.ownerUserId)

        // v6：日记（含提醒时间）原样往返。
        val note = bundle.diaryNotes.single()
        assertEquals("diary-fixture-1", note.id)
        assertEquals(persona.id, note.personaId)
        assertEquals("记得周四给妈妈打电话", note.content)
        assertEquals(1_794_506_400_000L, note.remindAt)
        assertEquals(1_794_506_500_000L, note.remindedAt)

        // 消息附件只写裸文件名（导入时按 media 重建），不写设备路径。
        val attachmentJson = bundle.messages.single { it.id == "msg-1" }.attachmentsJson
            ?: error("msg-1 应当带着附件")
        assertTrue(attachmentJson.contains("\"uri\":\"chat-fixture.png\""))
        assertTrue(!attachmentJson.contains("file://"))
        // v6：语音/视频/表情的 kind 与时长也要跟着走。
        val mediaAttachmentJson = bundle.messages.single { it.id == "msg-3" }.attachmentsJson
            ?: error("msg-3 应当带着语音/视频/表情附件")
        listOf("voice-fixture.m4a", "video-fixture.mp4", "sticker-fixture.gif").forEach { name ->
            assertTrue("缺少附件 $name", mediaAttachmentJson.contains("\"uri\":\"$name\""))
        }
        assertTrue(mediaAttachmentJson.contains("\"kind\":\"AUDIO\""))
        assertTrue(mediaAttachmentJson.contains("\"durationMs\":4200"))
        assertTrue(!mediaAttachmentJson.contains("file://"))

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
     * v6 的 JSON 结构是跨端契约：字段名/大小写/顺序之外，`bytes` 必须是数字、
     * `data` 必须是标准 base64 且不带换行。
     */
    @Test
    fun `v6 media 与 stickers diaryNotes 的 JSON 结构逐项钉死`() {
        val json = BackupCodec.encode(sampleBundle())
        // v4：provider 总是写出来（即使默认值），且只写 provider、不写 Key。
        assertTrue("v4 必须写 apiConfigs[].webToolsProvider", json.contains("\"webToolsProvider\":\"TAVILY\""))
        assertTrue("provider 不是 Key，不能被当成密钥拦下", !BackupCodec.containsSecretField(json))
        assertFalse("v4 仍然不许带 webToolsApiKeyCipher", json.contains("webToolsApiKeyCipher"))
        // 顶层 media 是数组；每条含 7 个字段，名字与大小写与 iOS 完全一致。
        listOf(
            "\"media\":[", "\"id\":", "\"kind\":\"PERSONA_AVATAR\"", "\"kind\":\"CHAT_IMAGE\"",
            "\"kind\":\"STICKER\"", "\"kind\":\"AUDIO\"", "\"kind\":\"VIDEO\"",
            "\"personaId\":", "\"fileName\":", "\"mime\":", "\"bytes\":", "\"data\":"
        ).forEach { marker -> assertTrue("缺少 $marker", json.contains(marker)) }
        // v6：表情行与日记板是独立的顶层数组，表情行里只写裸文件名，不再内联一份 base64。
        assertTrue("缺少 stickers 段", json.contains("\"stickers\":["))
        assertTrue("缺少 diaryNotes 段", json.contains("\"diaryNotes\":["))
        listOf(
            "\"ownerUserId\":", "\"file\":", "\"frames\":", "\"source\":", "\"createdBy\":",
            "\"remindAt\":", "\"remindedAt\":", "\"updatedAt\":"
        ).forEach { marker -> assertTrue("缺少 $marker", json.contains(marker)) }
        // 大小写敏感：不接受 snake_case / 小写别名。
        listOf("\"file_name\"", "\"mime_type\"", "\"kind\":\"chat_image\"", "\"diary_notes\"").forEach { marker ->
            assertTrue("不该出现 $marker", !json.contains(marker))
        }
        // base64 里不许有换行/空白（JSON 字符串本身用 \n 转义也不行）。
        val dataSegments = Regex("\"data\":\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(5, dataSegments.size)
        dataSegments.forEach { data ->
            assertTrue("base64 不该含换行", !data.contains("\\n") && !data.contains("\n"))
            assertTrue("base64 不该含空格", !data.contains(" "))
            assertEquals("标准 base64 带 padding", 0, data.length % 4)
            assertTrue("只允许标准字母表", data.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
        }
        // bytes 是 JSON 数字，不是字符串。
        assertTrue(json.contains("\"bytes\":$JPEG_BYTES"))
        assertTrue(json.contains("\"bytes\":$PNG_BYTES"))
        assertTrue(json.contains("\"bytes\":$GIF_BYTES"))

        // v5：引导字段必须写出来（可空字段只有「有值」时才出现），且字段名与 iOS 一致。
        listOf("\"worldJson\":", "\"speechStyleJson\":", "\"activityJson\":").forEach { marker ->
            assertTrue("v5 备份缺少 $marker", json.contains(marker))
        }
    }

    /**
     * v4 样本仍然能读：它没有引导字段，读出来必须是 null（不是空字符串、更不是崩）。
     * 这是跨端升级的基本盘：用户手里的旧备份不能因为一次格式升级变成不可读。
     */
    @Test
    fun `v4 样本仍然可读且引导字段为空`() {
        listOf("android-export-v4.json", "ios-export-v4.json").forEach { name ->
            val file = File("../fixtures/backup/$name")
            if (!file.exists()) {
                println("skip: 没有找到 $name")
                return@forEach
            }
            val raw = file.readText()
            val result = BackupCodec.decode(raw)
            val success = result as? BackupDecodeResult.Success
            assertTrue("v4 样本 $name 读不出来：$result", success != null)
            val bundle = success!!.bundle
            assertEquals(6, bundle.formatVersion)
            assertTrue(
                "读 v4 要留一句格式提醒",
                success.warnings.any { it is BackupWarning.OlderFormat && it.version == 4 }
            )
            val persona = bundle.personas.first()
            assertNull("v4 没有世界预设", persona.worldJson)
            assertNull("v4 没有话风", persona.speechStyleJson)
            assertNull("v4 没有当前活动", persona.activityJson)
            assertTrue("v4 的其余数据仍然在", persona.name.isNotBlank())
            assertTrue(!BackupCodec.containsSecretField(raw))
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
            val raw = file.readText()
            val result = BackupCodec.decode(raw)
            val success = result as? BackupDecodeResult.Success
            assertTrue("v1 样本 $name 读不出来：$result", success != null)
            val bundle = success!!.bundle
            assertEquals(BACKUP_FORMAT_NAME, bundle.format)
            assertEquals("读 v1 备份时要按当前格式尽力读出来", 6, bundle.formatVersion)
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
            assertTrue(!BackupCodec.containsSecretField(raw))
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
            val raw = file.readText()
            val result = BackupCodec.decode(raw)
            val success = result as? BackupDecodeResult.Success
            assertTrue("v2 样本 $name 读不出来：$result", success != null)
            val bundle = success!!.bundle
            assertEquals(BACKUP_FORMAT_NAME, bundle.format)
            assertEquals(6, bundle.formatVersion)
            // v2 → v3 会留一句「旧格式尽力读」的提醒。
            assertTrue(
                "读 v2 要留一句格式提醒",
                success.warnings.any { it is BackupWarning.OlderFormat && it.version == 2 }
            )
            assertTrue(bundle.personas.isNotEmpty())
            assertTrue("v2 没有 media", bundle.media.isEmpty())
            assertTrue(!BackupCodec.containsSecretField(raw))
        }
    }

    @Test
    fun `iOS 导出的 v5 样本 Android 也接得住`() {
        val file = File("../fixtures/backup/ios-export-v5.json")
        if (!file.exists()) {
            println("skip: 还没有 iOS 导出的 v5 样本（iOS 端并行实现；不存在时跳过）")
            return
        }
        val result = BackupCodec.decode(file.readText())
        val success = result as? BackupDecodeResult.Success
        assertTrue("iOS 导出的 v3 备份读不出来：$result", success != null)
        val bundle = success!!.bundle
        assertEquals(BACKUP_FORMAT_NAME, bundle.format)
        assertEquals(6, bundle.formatVersion)
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

    /**
     * v5 样本仍可读：它没有 `stickers` / `diaryNotes` 段，也没有新 kind 的 media。
     * 读出来两段必须是空列表（不是 null、更不是失败）。
     */
    @Test
    fun `v5 样本仍然可读 只是没有 stickers 与 diaryNotes`() {
        listOf("android-export-v5.json", "ios-export-v5.json").forEach { name ->
            val file = File("../fixtures/backup/$name")
            if (!file.exists()) {
                println("skip: 没有找到 $name")
                return@forEach
            }
            val raw = file.readText()
            // 并行开发期间 iOS 侧可能把 v5 样本改写成 v6；那不是 v5 样本，跳过而不是误报。
            val declared = Regex("\"formatVersion\"\\s*:\\s*(\\d+)").find(raw)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (declared != 5) {
                println("skip: $name 的 formatVersion=$declared，不是 v5 样本")
                return@forEach
            }
            val result = BackupCodec.decode(raw)
            val success = result as? BackupDecodeResult.Success
            assertTrue("v5 样本 $name 读不出来：$result", success != null)
            val bundle = success!!.bundle
            assertEquals(6, bundle.formatVersion)
            assertTrue(
                "读 v5 要留一句格式提醒",
                success.warnings.any { it is BackupWarning.OlderFormat && it.version == 5 }
            )
            assertTrue("v5 没有 stickers 段", bundle.stickers.isEmpty())
            assertTrue("v5 没有 diaryNotes 段", bundle.diaryNotes.isEmpty())
            assertTrue("v5 的图片仍然在", bundle.media.isNotEmpty())
            val oldKinds = setOf(
                BackupMediaKind.PERSONA_AVATAR,
                BackupMediaKind.USER_AVATAR,
                BackupMediaKind.CHAT_IMAGE
            )
            assertTrue(
                "v5 的 media 只应该出现旧的三种 kind：${bundle.media.map { it.kind }.toSet()}",
                bundle.media.all { it.kind in oldKinds }
            )
            assertTrue(!BackupCodec.containsSecretField(raw))
        }
    }

    /**
     * iOS v6 样本可能缺 `stickers` / `diaryNotes`（并行实现、字段还没补齐）：
     * 必须宽容跳过，不能整份失败。样本不存在时跳过（这轮 Android 先落地）。
     */
    @Test
    fun `iOS 导出的 v6 样本 Android 也接得住`() {
        val file = File("../fixtures/backup/ios-export-v6.json")
        if (!file.exists()) {
            println("skip: 还没有 iOS 导出的 v6 样本（iOS 端并行实现；不存在时跳过）")
            return
        }
        val raw = file.readText()
        val result = BackupCodec.decode(raw)
        val success = result as? BackupDecodeResult.Success
        assertTrue("iOS 导出的 v6 备份读不出来：$result", success != null)
        val bundle = success!!.bundle
        assertEquals(BACKUP_FORMAT_NAME, bundle.format)
        assertEquals(6, bundle.formatVersion)
        assertTrue(bundle.personas.isNotEmpty())
        assertTrue(bundle.sessions.isNotEmpty())
        // 一人一段：同一人格不允许出现两段（备份是接口契约，iOS 也要满足）。
        val duplicates = bundle.sessions.groupBy { it.personaId }.filterValues { it.size > 1 }
        assertTrue("iOS 的 v6 备份里同一个人格有多段会话：${duplicates.keys}", duplicates.isEmpty())
        // 五种 kind 的 base64 必须都能被 Kotlin 解出来，字节数与声明一致。
        bundle.media.forEach { media ->
            assertTrue("iOS 的 media 缺少 kind：${media.fileName}", media.kind in BackupMediaKind.all)
            val bytes = BackupBase64.decode(media.data)
            assertTrue("iOS 的 media base64 解不出来：${media.fileName}", bytes != null)
            assertEquals("media.bytes 与 base64 解出的字节数不一致：${media.fileName}", media.bytes, bytes!!.size)
        }
        // 缺段不是错误：两段按空列表处理。
        bundle.stickers.forEach { sticker ->
            // 跨端：iOS 写 `file`、Android 写 `fileName`，两边都接受。
            assertTrue(
                "表情行必须是裸文件名：${sticker.portableFileName}",
                BackupAvatarPath.isSafeName(sticker.portableFileName)
            )
        }
        assertTrue(!BackupCodec.containsSecretField(raw))
    }

    /**
     * v6 的两段是可选内容：整段类型不对、部分行坏掉、字段缺失，都只能丢掉那一段/那几行，
     * 不能让整份备份失败（iOS 早期样本、手写文件都可能长这样）。
     */
    @Test
    fun `缺失或坏掉的 stickers 与 diaryNotes 段被宽容跳过`() {
        val raw = """
            {
              "format": "myfriend.backup",
              "formatVersion": 6,
              "personas": [{"id": "p1", "name": "他"}],
              "stickers": 5,
              "diaryNotes": [
                {"id": "d1", "personaId": "p1", "content": "只有最少字段也该能读"},
                {"id": "d2", "personaId": "p1", "createdAt": "这不是数字"},
                {}
              ]
            }
        """.trimIndent()
        val success = BackupCodec.decode(raw) as? BackupDecodeResult.Success
            ?: error("缺段/坏条目不应该让整份备份失败")
        assertTrue("不是数组的 stickers 段按空列表处理", success.bundle.stickers.isEmpty())
        assertEquals("好的日记行要保留、坏行与空 id 行丢掉", 1, success.bundle.diaryNotes.size)
        val note = success.bundle.diaryNotes.single()
        assertEquals("d1", note.id)
        assertEquals("只有最少字段也该能读", note.content)
        assertEquals(0L, note.createdAt)
        assertNull(note.remindAt)
    }

    @Test
    fun `v3 样本仍然可读 且 provider 默认 MESSAGEAPI`() {
        listOf("android-export-v3.json", "ios-export-v3.json").forEach { name ->
            val file = File("../fixtures/backup/$name")
            if (!file.exists()) {
                println("skip: 没有找到 $name")
                return@forEach
            }
            val raw = file.readText()
            val result = BackupCodec.decode(raw)
            val success = result as? BackupDecodeResult.Success
            assertTrue("v3 样本 $name 读不出来：$result", success != null)
            val bundle = success!!.bundle
            assertEquals("旧格式按当前格式读出来", 6, bundle.formatVersion)
            assertTrue(
                "读 v3 要留一句格式提醒",
                success.warnings.any { it is BackupWarning.OlderFormat && it.version == 3 }
            )
            val config = bundle.apiConfigs.firstOrNull() ?: error("$name 没有 apiConfigs")
            assertEquals("v3 备份缺 provider 时默认 MESSAGEAPI", "MESSAGEAPI", config.webToolsProvider)
            assertTrue("v3 的图片也要还在", bundle.media.isNotEmpty())
            assertTrue(!BackupCodec.containsSecretField(raw))
        }
    }

    @Test
    fun `v5 样本里的 provider 往返后仍然一致`() {
        val bundle = BackupBundle(
            personas = listOf(BackupPersona(id = "p-1", name = "阿深")),
            apiConfigs = listOf(
                BackupApiConfig(id = "a-1", name = "联网", webToolsProvider = "BRAVE"),
                BackupApiConfig(id = "a-2", name = "默认", webToolsProvider = WebToolsProviders.MESSAGEAPI)
            )
        )
        val encoded = BackupCodec.encode(bundle)
        assertTrue("默认值也要写出来", encoded.contains("\"webToolsProvider\":\"MESSAGEAPI\""))
        assertTrue(encoded.contains("\"webToolsProvider\":\"BRAVE\""))
        val decoded = (BackupCodec.decode(encoded) as BackupDecodeResult.Success).bundle
        assertEquals(listOf("BRAVE", "MESSAGEAPI"), decoded.apiConfigs.map { it.webToolsProvider })
    }

    /**
     * 0.12 改名兼容：**旧备份**里 `webToolsProvider` 是 `DEEPSEEK`（0.12 早期样本 / 手写文件），
     * 读进来必须是 `MESSAGEAPI`——不能原样透传给网络层，也不能被当成脏值丢掉这一条配置。
     */
    @Test
    fun `旧备份里的 DEEPSEEK 读进来归一化成 MESSAGEAPI`() {
        val legacyJson = """{"format":"myfriend.backup","formatVersion":4,"apiKeysOmitted":true,""" +
            """"appVersion":"0.12.0","exportedAt":1,"personas":[],"apiConfigs":[""" +
            """{"id":"a-1","name":"老的","provider":"OPENAI","baseUrl":"https://api.deepseek.com/v1",""" +
            """"model":"m","webToolsProvider":"DEEPSEEK"},""" +
            """{"id":"a-2","name":"空的","provider":"OPENAI","baseUrl":"https://api.deepseek.com/v1",""" +
            """"model":"m","webToolsProvider":"  "}]}"""
        val success = BackupCodec.decode(legacyJson) as BackupDecodeResult.Success
        assertEquals(
            listOf("MESSAGEAPI", "MESSAGEAPI"),
            success.bundle.apiConfigs.map { it.webToolsProvider }
        )
        // 再导出：写出去的一律是新值，旧值不会继续传下去。
        val reencoded = BackupCodec.encode(success.bundle)
        assertFalse("旧值不该被再写出去", reencoded.contains("\"webToolsProvider\":\"DEEPSEEK\""))
        assertTrue(reencoded.contains("\"webToolsProvider\":\"MESSAGEAPI\""))
    }

    private fun jpegBytes(): ByteArray = BackupBase64.decode(JPEG_BASE64)!!
    private fun pngBytes(): ByteArray = BackupBase64.decode(PNG_BASE64)!!
    private fun gifBytes(): ByteArray = BackupBase64.decode(GIF_BASE64)!!

    /** 语音/视频样本：不需要真的能播放，只要有稳定的字节与容器魔数（跨端逐字节比对用）。 */
    private fun audioBytes(): ByteArray = ByteArray(AUDIO_BYTES) { index -> (index * 7 + 1).toByte() }.also {
        "ftypM4A ".toByteArray(Charsets.US_ASCII).copyInto(it, 4)
    }

    private fun videoBytes(): ByteArray = ByteArray(VIDEO_BYTES) { index -> (index * 11 + 3).toByte() }.also {
        "ftypisom".toByteArray(Charsets.US_ASCII).copyInto(it, 4)
    }

    private fun sampleBundle(): BackupBundle {
        val personaId = "11111111-1111-1111-1111-111111111111"
        val sessionId = "22222222-2222-2222-2222-222222222222"
        val now = 1_794_500_000_000L
        return BackupBundle(
            appVersion = "0.13.0",
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
                    dndOnSleep = true,
                    worldJson = "" +
                        "{\"worldName\":\"临港新城·云筑建筑事务所\"," +
                        "\"era\":\"当代，中国沿海新一线城市，2026 年\"," +
                        "\"description\":\"他带着六人小组负责一个高层住宅项目。\"," +
                        "\"schedule\":[{\"day\":\"工作日\",\"start\":\"09:00\",\"end\":\"18:30\",\"activity\":\"画图、审图\"}]," +
                        "\"relationships\":[{\"name\":\"母亲\",\"relation\":\"母亲\",\"note\":\"每周三晚打电话\"}]," +
                        "\"obligations\":[\"按节点交图\"]}",
                    speechStyleJson = "" +
                        "{\"tone\":\"简短直接，偏冷淡务实\",\"phrases\":[\"在忙，晚点说\",\"嗯，知道了\"]," +
                        "\"habits\":[\"句子很短\"],\"avoid\":[\"长篇大论\"]}",
                    activityJson = "" +
                        "{\"kind\":\"WORK\",\"activity\":\"在事务所赶第三版方案\"," +
                        "\"until\":1794505400000,\"availability\":\"BUSY\"}"
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
                ),
                // v6：一条消息同时带语音/视频/表情，验证 kind 与 durationMs 的跨端契约。
                BackupMessage(
                    id = "msg-3",
                    sessionId = sessionId,
                    personaId = personaId,
                    role = "USER",
                    content = "给你听一段、看一段，还有这个表情。",
                    status = "COMPLETE",
                    createdAt = now - 7_000_000L,
                    updatedAt = now - 7_000_000L,
                    attachmentsJson = "[" +
                        "{\"uri\":\"voice-fixture.m4a\",\"mime\":\"audio/m4a\",\"kind\":\"AUDIO\",\"durationMs\":4200}," +
                        "{\"uri\":\"video-fixture.mp4\",\"mime\":\"video/mp4\",\"kind\":\"VIDEO\",\"durationMs\":9000}," +
                        "{\"uri\":\"sticker-fixture.gif\",\"mime\":\"image/gif\",\"kind\":\"STICKER\"}" +
                        "]"
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
                ),
                BackupMedia(
                    id = "media-sticker-1",
                    kind = BackupMediaKind.STICKER,
                    personaId = personaId,
                    fileName = "sticker-fixture.gif",
                    mime = "image/gif",
                    bytes = GIF_BYTES,
                    data = GIF_BASE64
                ),
                BackupMedia(
                    id = "media-audio-1",
                    kind = BackupMediaKind.AUDIO,
                    personaId = personaId,
                    fileName = "voice-fixture.m4a",
                    mime = "audio/m4a",
                    bytes = AUDIO_BYTES,
                    data = BackupBase64.encode(audioBytes())
                ),
                BackupMedia(
                    id = "media-video-1",
                    kind = BackupMediaKind.VIDEO,
                    personaId = personaId,
                    fileName = "video-fixture.mp4",
                    mime = "video/mp4",
                    bytes = VIDEO_BYTES,
                    data = BackupBase64.encode(videoBytes())
                )
            ),
            // v6：表情行只写裸文件名，字节在 media 里（上面的 STICKER 条目）。
            stickers = listOf(
                BackupSticker(
                    id = "sticker-fixture-1",
                    ownerUserId = "user-fixture",
                    personaId = personaId,
                    fileName = "sticker-fixture.gif",
                    // iOS 端读 `file`，Android 端读 `fileName`：黄金样本两个都写，跨端才都能取到名字。
                    file = "sticker-fixture.gif",
                    mime = "image/gif",
                    width = 1,
                    height = 1,
                    frames = 1,
                    bytes = GIF_BYTES,
                    source = "IMPORTED",
                    createdBy = "USER",
                    createdAt = now - 6_900_000L
                )
            ),
            // v6：日记（含提醒时间）。
            diaryNotes = listOf(
                BackupDiaryNote(
                    id = "diary-fixture-1",
                    personaId = personaId,
                    content = "记得周四给妈妈打电话",
                    createdAt = now - 6_800_000L,
                    remindAt = 1_794_506_400_000L,
                    remindedAt = 1_794_506_500_000L,
                    updatedAt = now - 6_800_000L
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
                    updatedAt = now - 172_800_000L,
                    worldJson = "{\"worldName\":\"临港新城·云筑建筑事务所\"}",
                    speechStyleJson = "{\"tone\":\"简短直接\"}"
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
                    model = "yap-reference",
                    allowInsecureHttp = true,
                    // v4：provider 一定要写进导出（非默认值便于跨端验证）。
                    webToolsProvider = "TAVILY",
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
        /** 1×1 的透明 GIF（42 字节）：表情包的主形态是 GIF，跨端要能逐字节解回来。 */
        const val GIF_BASE64 = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        const val GIF_BYTES = 42
        /** 语音/视频样本只求稳定字节（容器魔数写在 helper 里），不追求真的可播放。 */
        const val AUDIO_BYTES = 32
        const val VIDEO_BYTES = 48
    }
}
