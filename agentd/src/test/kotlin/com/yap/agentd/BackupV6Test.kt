package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.MessageRole
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * 备份 v6 与客户端**完全互通**（`app/src/main/java/com/yap/app/domain/BackupCodec.kt`）。
 *
 * 这条测试的判据不是「服务端自己能读回来」，而是三条更硬的东西：
 * 1. **键集**：导出的每一段、每一行，键名都能在客户端 v6 的 fixture
 *    （`fixtures/backup/android-export-v6.json` / `ios-export-v6.json`）里找到；
 * 2. **能导入**：两份客户端 v6 fixture 原样 POST 进来，段数与内容都对得上；
 * 3. **往返不丢**：导入客户端备份 → 再导出 → 再导入，两侧导出的每一段逐字节一致。
 *
 * 另外保留 v5 兼容用例（老备份别丢）与「裸文件 / {mode,data} 信封都收」的行为。
 */
class BackupV6Test {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val roots = mutableListOf<Path>()

    @Before
    fun setUp() {
        root = freshStoreRoot("agentd-backup-v6")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        roots.forEach { TestSupport.deleteRecursively(it) }
    }

    private fun freshStoreRoot(prefix: String): Path {
        val dir = TestSupport.tempDir(prefix)
        roots.add(dir)
        return dir
    }

    // ------------------------------------------------------------------ 工具

    private fun export(store: FileStore = this.store): JsonValue.Obj {
        val out = StringBuilder()
        store.writeBackup(AgentdApi.VERSION, 1_794_500_000_000L, out)
        return TestSupport.parse(out.toString())
    }

    /** 导入一份备份：`bare = true` 时直接用裸备份文件（客户端导出文件的形状）。 */
    private fun import(store: FileStore, text: String, mode: String = "replace", bare: Boolean = true): String {
        val payload = if (bare) text else """{"mode":"$mode","data":$text}"""
        val response = CrudRoutes.importBackupFromFile(store, writeTemp(payload), mode)
        assertEquals(response.body, 200, response.status)
        return response.body
    }

    private fun writeTemp(text: String): Path {
        val file = Files.createTempFile(freshStoreRoot("agentd-backup-tmp"), "backup-", ".json")
        Files.writeString(file, text)
        return file
    }

    private fun fixture(name: String): String {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("fixtures/backup/$name")
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        throw AssertionError("找不到 fixtures/backup/$name（测试应在仓库内运行）")
    }

    private fun keyUnion(section: String, vararg documents: JsonValue.Obj): Set<String> {
        val keys = LinkedHashSet<String>()
        for (document in documents) {
            val items = document.fields[section] as? JsonValue.Arr ?: continue
            for (item in items.items) {
                (item as? JsonValue.Obj)?.fields?.keys?.let { keys += it }
            }
            (document.fields[section] as? JsonValue.Obj)?.fields?.keys?.let { keys += it }
        }
        return keys
    }

    // ------------------------------------------------------------------ 1. 键集

    @Test
    fun `exported top level uses the client v6 key set`() {
        val backup = export()
        val expected = listOf(
            "format", "formatVersion", "appVersion", "exportedAt", "apiKeysOmitted",
            "personas", "memories", "sessions", "messages", "media", "stickers", "diaryNotes",
            "presets", "proactiveRules", "agentIntents", "memoryConsolidations", "apiConfigs", "settings"
        )
        assertEquals(expected, backup.fields.keys.toList().filter { it != "mediaSkipped" })
        assertEquals(FileStore.BACKUP_FORMAT_VERSION, backup.int("formatVersion"))
        assertEquals(6, backup.int("formatVersion"))
        assertEquals(FileStore.BACKUP_FORMAT_NAME, backup.str("format"))
    }

    @Test
    fun `every section is a subset of the client key union with documented server extras`() {
        // 先造一份「什么都有」的数据，再逐段比对键名。
        seedEverything()
        val backup = export()
        val android = TestSupport.parse(fixture("android-export-v6.json"))
        val ios = TestSupport.parse(fixture("ios-export-v6.json"))

        val serverExtras = mapOf(
            "personas" to setOf("nameEn"),
            "media" to setOf("sha256"),
            "settings" to setOf("userAvatarUri", "autoTranslatePersona")
        )
        for (section in listOf(
            "personas", "memories", "sessions", "messages", "media", "stickers",
            "diaryNotes", "presets", "agentIntents", "apiConfigs"
        )) {
            val allowed = keyUnion(section, android, ios) + serverExtras.getOrDefault(section, emptySet())
            val actual = keyUnion(section, backup)
            val unexpected = actual - allowed
            assertTrue("$section 出现客户端不认识的键：$unexpected", unexpected.isEmpty())
            // 反向：客户端的**非空默认值**键必须写全（可空字段没值时会按 explicitNulls=false 省略，
            // 所以只查下面这张表——它对应客户端 `encodeDefaults = true` 的部分）。
            val required = REQUIRED_CLIENT_KEYS[section].orEmpty()
            val missing = required - actual
            assertTrue("$section 少了客户端的必写键：$missing", missing.isEmpty())
        }
        // 客户端用 unreadCount，服务端状态接口用 unread：备份里必须是客户端那个。
        val session = backup.objects("sessions").firstOrNull()
        assertNotNull("要有会话可断言", session)
        assertTrue(session!!.fields.containsKey("unreadCount"))
        assertFalse(session.fields.containsKey("unread"))
        val message = backup.objects("messages").firstOrNull()
        assertNotNull(message)
        assertTrue("消息要带客户端列", message!!.fields.containsKey("status"))
        assertTrue(message.fields.containsKey("updatedAt"))
    }

    @Test
    fun `stickers carry both fileName and the iOS alias file`() {
        store.createSticker(FileStore.SEED_PERSONA_1_ID, MediaMime.GIF, GIF, width = 1, height = 1)
        val sticker = export().objects("stickers").single()
        assertEquals(sticker.str("fileName"), sticker.str("file"))
        assertEquals("image/gif", sticker.str("mime"))
        assertEquals("IMPORTED", sticker.str("source"))
        assertEquals("USER", sticker.str("createdBy"))
        // 表情字节只内联一次：在 media[] 里（kind=STICKER，同名 fileName）。
        val media = export().objects("media").single()
        assertEquals("STICKER", media.str("kind"))
        assertEquals(sticker.str("fileName"), media.str("fileName"))
    }

    // ------------------------------------------------------------------ 2. 客户端 fixture 导入

    @Test
    fun `android v6 fixture imports with all sections intact`() {
        val response = TestSupport.parse(import(store, fixture("android-export-v6.json")))
        assertEquals(1, response.obj("imported").int("personas"))
        assertEquals(1, response.obj("imported").int("sessions"))
        assertEquals(3, response.obj("imported").int("messages"))
        assertEquals(1, response.obj("imported").int("memories"))
        assertEquals("fixture 里有 5 份媒体（头像/聊天图/表情/语音/视频）", 5, response.obj("imported").int("media"))
        assertEquals(1, response.obj("imported").int("stickers"))
        assertEquals(1, response.obj("imported").int("diaryNotes"))
        assertEquals(5, store.mediaItems().size)

        val persona = store.persona("11111111-1111-1111-1111-111111111111")!!
        assertEquals("林深", persona.name)
        // 头像按文件名接回了媒体：avatarUrl 必须是服务端引用，而不是裸文件名
        // （原始裸名保留在 media[].fileName 里，再导出时还会写回 avatarUri）。
        assertTrue(persona.avatarUrl!!.startsWith("/v1/media/"))
        assertNotNull(store.mediaBytes(persona.avatarUrl!!.removePrefix("/v1/media/")))
        assertEquals("{\"tone\":\"简短直接，偏冷淡务实\"", (persona.extraFields["speechStyleJson"] as JsonValue.Str).value.take(20))

        val session = store.session("22222222-2222-2222-2222-222222222222")!!
        assertEquals("下个月去哈尔滨", session.title)
        assertEquals("滚动摘要要带回来", "你提过下个月要去哈尔滨，怕冷。", session.extraFields["summary"]?.let { (it as JsonValue.Str).value })
        assertEquals(true, session.extraFields["memoryRetentionOverride"] != null)

        val messages = store.blockMessages(session.id, 0)
        assertEquals(3, messages.size)
        // 附件按裸文件名接回了媒体；音频/视频的 kind 也要在。
        val withImage = messages.first { it.content.contains("哈尔滨") }
        assertEquals(1, withImage.attachments.size)
        assertTrue(withImage.attachments.single().startsWith("/v1/media/"))
        assertEquals(1_794_492_900_000L, withImage.readAt)
        val withMedia = messages.first { it.attachments.size == 3 }
        assertEquals(3, withMedia.attachments.size)
        assertTrue("每个附件都要能真的取到字节", withMedia.attachments.all { store.mediaBytes(it.removePrefix("/v1/media/")) != null })
        val assistant = messages.first { it.role == MessageRole.ASSISTANT }
        assertEquals("[\"web_search\"]", assistant.toolTraceJson)

        val memory = store.memories(persona.id).single()
        assertEquals(listOf("哈尔滨", "怕冷"), memory.tags)
        assertEquals(5, memory.importance)

        val sticker = store.stickers().single()
        assertNotNull("表情文件要能取到", store.stickerBytes(sticker.id))
        assertEquals("sticker-fixture.gif", sticker.file)
        assertEquals(1, store.stickers(persona.id).size)

        val note = store.diaryNotes(persona.id).single()
        assertEquals("记得周四给妈妈打电话", note.content)
        assertEquals(1_794_506_400_000L, note.remindAt)
        assertEquals(1_794_506_500_000L, note.remindedAt)

        assertEquals("林深", store.persona("11111111-1111-1111-1111-111111111111")!!.name)
        // 客户端把「此刻的心情」放在 persona 行里；导入要拆回 moods.json。
        assertEquals("平静", store.mood(persona.id)?.label)
        assertEquals(2, store.mood(persona.id)?.intensity)
        // 设置只接白名单。
        assertEquals("SYSTEM", store.publicSettings().str("themeMode"))
    }

    @Test
    fun `ios v6 fixture imports too`() {
        val response = TestSupport.parse(import(store, fixture("ios-export-v6.json")))
        val imported = response.obj("imported")
        assertTrue(imported.int("personas") >= 1)
        assertTrue(imported.int("messages") >= 1)
        // 缺段/缺字段都要宽容：不因为 iOS 少写了什么整份失败。
        assertTrue(store.personas().isNotEmpty())
        assertTrue(store.mediaItems().isNotEmpty())
    }

    @Test
    fun `v5 fixture still imports with empty v6 sections`() {
        val response = TestSupport.parse(import(store, fixture("android-export-v5.json")))
        assertTrue(response.obj("imported").int("personas") >= 1)
        assertTrue("v5 没有 stickers 段", store.stickers().isEmpty())
        assertTrue("v5 没有 diaryNotes 段", store.diaryNotes(store.personas().first().id).isEmpty())
    }

    @Test
    fun `wrapper envelope with mode still works`() {
        val v6 = fixture("android-export-v6.json")
        import(store, v6, mode = "replace", bare = false)
        assertEquals(1, store.personas().count { it.id == "11111111-1111-1111-1111-111111111111" })
    }

    @Test
    fun `bare backup file defaults to merge`() {
        import(store, fixture("android-export-v6.json"))
        val before = store.personas().size
        import(store, fixture("android-export-v6.json"))
        assertEquals("merge 语义：同一 id 覆盖，不重复", before, store.personas().size)
    }

    // ------------------------------------------------------------------ 3. 往返

    @Test
    fun `client backup survives export import export without losing a field`() {
        import(store, fixture("android-export-v6.json"))
        val first = export()

        val target = FileStore.open(freshStoreRoot("agentd-backup-v6-target"))
        import(target, Json.stringify(first))
        val second = export(target)

        val sections = listOf(
            "personas", "memories", "sessions", "messages", "media", "stickers",
            "diaryNotes", "presets", "agentIntents", "apiConfigs", "settings"
        )
        for (section in sections) {
            assertEquals(
                "$section 往返之后必须完全一致",
                Json.stringify(first.fields.getValue(section)),
                Json.stringify(second.fields.getValue(section))
            )
        }
        assertEquals(first.int("formatVersion"), second.int("formatVersion"))
    }

    @Test
    fun `server created content round trips through a client shaped export`() {
        // 服务端自己产生的数据：头像、聊天图、表情、日记、消息工具痕迹、会话摘要。
        val media = store.saveMedia(
            kind = "CHAT_IMAGE",
            personaId = FileStore.SEED_PERSONA_1_ID,
            mime = MediaMime.PNG,
            data = PNG
        )
        store.updatePersona(FileStore.SEED_PERSONA_1_ID) { persona, _ ->
            persona.copy(avatarUrl = media.item.url)
        }
        store.appendMessage(
            sessionId = FileStore.SEED_SESSION_ID,
            role = MessageRole.USER,
            content = "看这张",
            attachments = listOf(media.item.url)
        )
        store.appendMessage(
            sessionId = FileStore.SEED_SESSION_ID,
            role = MessageRole.ASSISTANT,
            content = "看到了",
            toolTraceJson = """[{"name":"web_search","title":"联网搜索","detail":"…","ok":true}]"""
        )
        store.createSticker(FileStore.SEED_PERSONA_1_ID, MediaMime.GIF, GIF, width = 1, height = 1)
        store.upsertDiaryNote(FileStore.SEED_PERSONA_1_ID, null, "记得买牛奶", remindAt = 1_800_000_000_000L)
        store.saveIntent(
            com.yap.agentd.store.StoredIntent(
                id = "intent-1",
                personaId = FileStore.SEED_PERSONA_1_ID,
                sessionId = FileStore.SEED_SESSION_ID,
                kind = "REACH_OUT",
                earliestAt = 1_794_600_000_000L,
                latestAt = 1_794_601_800_000L,
                note = "手头忙",
                hint = "",
                status = "PENDING",
                createdAt = 1_794_500_000_000L,
                sentAt = null
            )
        )

        val backup = export()
        val target = FileStore.open(freshStoreRoot("agentd-backup-v6-roundtrip"))
        import(target, Json.stringify(backup))

        val persona = target.persona(FileStore.SEED_PERSONA_1_ID)!!
        assertTrue(persona.avatarUrl!!.startsWith("/v1/media/"))
        assertNotNull(target.mediaBytes(persona.avatarUrl!!.removePrefix("/v1/media/")))
        val messages = target.blockMessages(FileStore.SEED_SESSION_ID, 0)
        val withImage = messages.first { it.role == MessageRole.USER && it.attachments.isNotEmpty() }
        assertEquals(1, withImage.attachments.size)
        assertNotNull(target.mediaBytes(withImage.attachments.single().removePrefix("/v1/media/")))
        val assistant = messages.first { it.role == MessageRole.ASSISTANT }
        assertTrue(assistant.toolTraceJson!!.contains("web_search"))
        assertEquals("记得买牛奶", target.diaryNotes(FileStore.SEED_PERSONA_1_ID).single().content)
        assertEquals("服务端发出的表情也能带回来", 1, target.stickers().size)
        assertNotNull(target.stickerBytes(target.stickers().single().id))
        assertEquals("intent-1", target.pendingIntents(FileStore.SEED_PERSONA_1_ID).single().id)
    }

    @Test
    fun `unknown v7 backup is still rejected`() {
        val response = CrudRoutes.importBackupFromFile(
            store,
            writeTemp("""{"formatVersion":7,"personas":[]}"""),
            "replace"
        )
        assertEquals(409, response.status)
        assertTrue(response.body.contains("unsupported_backup_version"))
    }

    @Test
    fun `import is transactional for broken media`() {
        val broken = """{
          "format":"myfriend.backup","formatVersion":6,
          "personas":[{"id":"p1","name":"甲","createdAt":1,"updatedAt":1}],
          "media":[{"id":"m1","kind":"CHAT_IMAGE","fileName":"a.png","mime":"image/png","bytes":3,"data":"!!!"}]
        }"""
        val before = store.personas().size
        val response = CrudRoutes.importBackupFromFile(store, writeTemp(broken), "replace")
        assertEquals(400, response.status)
        assertEquals("失败不能写一半", before, store.personas().size)
    }

    // ------------------------------------------------------------------ 数据

    private fun seedEverything() {
        val personaId = FileStore.SEED_PERSONA_1_ID
        store.updatePersona(personaId) { persona, _ ->
            persona.copy(
                avatarUrl = null,
                extraFields = persona.extraFields + mapOf(
                    "traits" to JsonValue.Arr(mutableListOf(JsonValue.Str("慢热"))),
                    "prompt" to JsonValue.Str("你是林深。"),
                    "worldJson" to JsonValue.Str("""{"worldName":"临港新城"}"""),
                    "speechStyleJson" to JsonValue.Str("""{"tone":"简短"}"""),
                    "personaCardJson" to JsonValue.Str("""{"identity":{}}"""),
                    "quietSleepMinutes" to JsonValue.Num("90")
                )
            )
        }
        store.updateActivity(personaId, """{"kind":"WORK","activity":"在画图","until":1794505400000,"availability":"BUSY"}""")
        store.setMood(personaId, "平静", 2, 0, 2, 3, "没什么事")
        store.appendMessage(
            FileStore.SEED_SESSION_ID,
            MessageRole.USER,
            "嗨",
            readAt = 1_794_492_900_000L
        )
        store.appendMessage(
            FileStore.SEED_SESSION_ID,
            MessageRole.ASSISTANT,
            "在",
            toolTraceJson = """[{"name":"web_search","title":"联网搜索","detail":"x","ok":true}]"""
        )
        store.saveMemory(personaId, null) { _, _ ->
            com.yap.agentd.store.Memory(
                id = "",
                personaId = personaId,
                content = "记得",
                importance = 60,
                tags = listOf("a", "b"),
                createdAt = 0,
                updatedAt = 0
            )
        }
        store.savePreset(null) { _, _ ->
            com.yap.agentd.store.Preset(
                id = "",
                name = "预设",
                description = "",
                prompt = "",
                category = "",
                avatarUri = null,
                avatarSeed = 1,
                createdAt = 0,
                updatedAt = 0,
                worldJson = """{"worldName":"x"}""",
                speechStyleJson = """{"tone":"y"}"""
            )
        }
        store.putApiConfig(
            com.yap.agentd.store.ApiConfig(
                id = "cfg",
                name = "配置",
                provider = "OPENAI",
                baseUrl = "https://api.example.com/v1",
                model = "m",
                createdAt = 0,
                updatedAt = 0
            )
        )
        store.setSetting("themeMode", JsonValue.Str("DARK"))
        store.setSetting("userAvatarUri", JsonValue.Str("/v1/media/abc"))
        store.createSticker(personaId, MediaMime.GIF, GIF, width = 1, height = 1)
        store.upsertDiaryNote(personaId, null, "备忘", remindAt = null)
        store.saveIntent(
            com.yap.agentd.store.StoredIntent(
                id = "i-1",
                personaId = personaId,
                sessionId = FileStore.SEED_SESSION_ID,
                kind = "REACH_OUT",
                earliestAt = 1_794_600_000_000L,
                latestAt = 1_794_601_800_000L,
                note = "",
                hint = "",
                status = "PENDING",
                createdAt = 1_794_500_000_000L,
                sentAt = null
            )
        )
    }

    private companion object {
        /** 客户端 v6 各段里「总是会写」的键（可空字段不在此列）。 */
        val REQUIRED_CLIENT_KEYS = mapOf(
            "personas" to setOf(
                "id", "name", "description", "avatarSeed", "prompt", "category", "version", "isDefault",
                "createdAt", "updatedAt", "traits", "likes", "behaviors", "proactiveLevel",
                "memoryRetention", "extractionEvery", "sleepEnabled", "wakeThreshold", "dndOnSleep"
            ),
            "memories" to setOf(
                "id", "personaId", "content", "category", "importance", "pinned", "createdAt",
                "updatedAt", "lastUsedAt", "useCount", "status", "strength", "driftCount"
            ),
            "sessions" to setOf(
                "id", "personaId", "model", "title", "unreadCount", "pinned", "createdAt", "updatedAt",
                "lastPersonaVersion", "lastMemoryExtractedCount"
            ),
            "messages" to setOf(
                "id", "sessionId", "personaId", "role", "content", "createdAt", "updatedAt", "status", "edited"
            ),
            "media" to setOf("id", "kind", "fileName", "mime", "bytes", "data"),
            "stickers" to setOf(
                "id", "fileName", "file", "mime", "width", "height", "frames", "bytes",
                "source", "createdBy", "createdAt"
            ),
            "diaryNotes" to setOf("id", "personaId", "content", "createdAt", "updatedAt"),
            "presets" to setOf("id", "name", "description", "prompt", "category", "avatarSeed", "createdAt", "updatedAt"),
            "apiConfigs" to setOf(
                "id", "name", "provider", "baseUrl", "model", "allowInsecureHttp", "webToolsEnabled",
                "webToolsBaseUrl", "webToolsProvider", "createdAt", "updatedAt"
            )
        )

        val GIF: ByteArray = Base64.getDecoder()
            .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")

        /** 2×2 的 PNG。 */
        val PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEklEQVR4nGM8URHAwMDAxAAGABNiAZSfXW4zAAAAAElFTkSuQmCC"
        )
    }
}
