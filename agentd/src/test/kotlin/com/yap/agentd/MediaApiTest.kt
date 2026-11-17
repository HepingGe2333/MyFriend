package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaKind
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Preset
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 媒体上传/下载/删除 + 消息附件 + 备份媒体（M5 剩余项）的 handler 级测试。
 *
 * 覆盖点（对应任务要求）：
 * - 上传 201 / 同 sha256 200 复用 / png+webp / 类型、kind、persona、大小、空 body 拒绝；
 * - 下载原始字节 + Content-Type/Cache-Control/ETag；If-None-Match → 304（含弱校验与 `*`）；未知/畸形 id 404；
 * - 删除：无人引用真删；消息/人格/预设引用 409 + 引用数；`?force=1` 强制删；
 * - 消息附件：裸 id 或 `/v1/media/<id>` 都接受，落库/下发统一成 URL；未知媒体 400 `unknown_media`；
 * - 备份：导出含 `media[]` base64 + `mediaSkipped`；导入还原文件与引用；超大媒体跳过计数；
 *   replace 清旧媒体；sha 不符的备份被拒且零写入；
 * - 重启后元数据还在；并发上传同内容只落一份。
 */
class MediaApiTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)
    private val extraRoots = mutableListOf<Path>()

    private val sessionId: String get() = FileStore.SEED_SESSION_ID
    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-media-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
        extraRoots.forEach { TestSupport.deleteRecursively(it) }
    }

    private fun services(): AgentdServices = AgentdServices(store)

    // ------------------------------------------------------------------ 小工具

    private fun resultJson(result: MediaRoutes.MediaResult): AgentdApi.Response =
        (result as MediaRoutes.MediaResult.Json).response

    private fun upload(
        bytes: ByteArray,
        path: String = "/v1/media?kind=CHAT_IMAGE",
        contentType: String? = "image/jpeg"
    ): AgentdApi.Response {
        val headers = if (contentType == null) emptyMap() else mapOf("content-type" to contentType)
        return resultJson(MediaRoutes.handle(services(), "POST", path, headers, bytes))
    }

    private fun uploadOk(
        bytes: ByteArray,
        path: String = "/v1/media?kind=CHAT_IMAGE",
        contentType: String = "image/jpeg"
    ): JsonValue.Obj {
        val response = upload(bytes, path, contentType)
        assertEquals(response.body, 201, response.status)
        return TestSupport.parse(response.body)
    }

    private fun downloadResult(id: String, ifNoneMatch: String? = null): MediaRoutes.MediaResult {
        val headers = if (ifNoneMatch == null) emptyMap() else mapOf("if-none-match" to ifNoneMatch)
        return MediaRoutes.handle(services(), "GET", "/v1/media/$id", headers, ByteArray(0))
    }

    private fun download(id: String, ifNoneMatch: String? = null): MediaRoutes.MediaResult.Binary =
        downloadResult(id, ifNoneMatch) as MediaRoutes.MediaResult.Binary

    private fun delete(id: String, query: String = ""): AgentdApi.Response =
        resultJson(MediaRoutes.handle(services(), "DELETE", "/v1/media/$id$query", emptyMap(), ByteArray(0)))

    private fun jsonOf(response: AgentdApi.Response): JsonValue.Obj = TestSupport.parse(response.body)

    private fun errorCode(response: AgentdApi.Response): String = jsonOf(response).obj("error").str("code")

    private fun mediaFiles(): List<Path> = Files.list(root.resolve(FileStore.MEDIA_DIR)).use { stream ->
        stream.filter { Files.isRegularFile(it) }.toList()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private class RecordingSink : SseSink {
        val events = mutableListOf<SseEvent>()

        override fun emit(event: SseEvent) {
            events += event
        }
    }

    private fun sendWithAttachments(body: String, sink: RecordingSink = RecordingSink()): RecordingSink {
        AuthorityRoutes.stream(services(), "POST", "/v1/sessions/$sessionId/messages", body, sink)
        return sink
    }

    private fun messagesPage(): JsonValue.Obj {
        val response = AuthorityRoutes.handle(services(), "GET", "/v1/sessions/$sessionId/messages", null)
        assertEquals(response.body, 200, response.status)
        return TestSupport.parse(response.body)
    }

    private fun exportBackup(): JsonValue.Obj {
        val response = AuthorityRoutes.handle(services(), "GET", "/v1/backup", null)
        assertEquals(response.body, 200, response.status)
        return TestSupport.parse(response.body)
    }

    private fun importBackup(target: FileStore, data: JsonValue.Obj): AgentdApi.Response {
        val payload = JsonValue.Obj(
            linkedMapOf(
                "mode" to JsonValue.Str("replace"),
                "data" to data
            )
        )
        return AuthorityRoutes.handle(AgentdServices(target), "POST", "/v1/backup", Json.stringify(payload))
    }

    private fun backupMediaEntry(
        id: String,
        bytes: ByteArray,
        sha: String = sha256(bytes),
        bytesField: Long = bytes.size.toLong()
    ): JsonValue.Obj = TestSupport.parse(
        """
        {
          "id":"$id","kind":"CHAT_IMAGE","personaId":null,"mime":"image/jpeg",
          "bytes":$bytesField,"sha256":"$sha","createdAt":20,
          "data":"${Base64.getEncoder().encodeToString(bytes)}"
        }
        """.trimIndent()
    )

    // ------------------------------------------------------------------ 上传

    @Test
    fun `upload stores raw bytes metadata and returns the url`() {
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        val body = uploadOk(bytes)

        assertEquals("CHAT_IMAGE", body.str("kind"))
        assertEquals(MediaMime.JPEG, body.str("mime"))
        assertEquals(bytes.size.toLong(), body.long("bytes"))
        assertEquals(sha256(bytes), body.str("sha256"))
        val id = body.str("id")
        assertEquals("/v1/media/$id", body.str("url"))

        val file = root.resolve(FileStore.MEDIA_DIR).resolve("$id.jpg")
        assertTrue("文件必须落在 media/<id>.jpg：$file", Files.exists(file))
        assertArrayEquals(bytes, Files.readAllBytes(file))

        val metadata = TestSupport.parse(Files.readString(root.resolve(FileStore.MEDIA_METADATA_FILE)))
        val item = metadata.objects("media").single()
        assertEquals(id, item.str("id"))
        assertEquals("CHAT_IMAGE", item.str("kind"))
        assertEquals(MediaMime.JPEG, item.str("mime"))
        assertEquals(bytes.size.toLong(), item.long("bytes"))
        assertEquals(sha256(bytes), item.str("sha256"))
        assertNull("没给 personaId 时元数据里是 null", item.fields["personaId"] as? JsonValue.Str)
    }

    @Test
    fun `same content is deduplicated and returns 200`() {
        val bytes = byteArrayOf(1, 1, 2, 3, 5, 8)
        val first = uploadOk(bytes)
        val second = upload(bytes)
        assertEquals(second.body, 200, second.status)
        assertEquals(first.str("id"), TestSupport.parse(second.body).str("id"))

        assertEquals(1, mediaFiles().size)
        assertEquals(1, store.mediaItems().size)

        // 元数据在、文件被手工删掉：再上传同内容应补回字节（仍是 200，不新增第二份）。
        val file = root.resolve(FileStore.MEDIA_DIR).resolve("${first.str("id")}.jpg")
        Files.delete(file)
        assertEquals(200, upload(bytes).status)
        assertTrue("重复上传应把丢失的文件补回来", Files.exists(file))
        assertArrayEquals(bytes, Files.readAllBytes(file))
    }

    @Test
    fun `png and webp are accepted with matching extension and persona`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val webp = "RIFFxxxxWEBP".toByteArray()
        val pngBody = uploadOk(
            png,
            "/v1/media?kind=PERSONA_AVATAR&mime=image/png&personaId=$personaId",
            contentType = "image/png; charset=binary"
        )
        val webpBody = uploadOk(
            webp,
            "/v1/media?kind=USER_AVATAR&mime=image/webp",
            contentType = "image/webp"
        )
        assertEquals(MediaMime.PNG, pngBody.str("mime"))
        assertEquals(personaId, store.media(pngBody.str("id"))?.personaId)
        assertTrue(Files.exists(root.resolve(FileStore.MEDIA_DIR).resolve("${pngBody.str("id")}.png")))
        assertTrue(Files.exists(root.resolve(FileStore.MEDIA_DIR).resolve("${webpBody.str("id")}.webp")))
    }

    @Test
    fun `unsupported or missing content type is 415`() {
        val text = upload(byteArrayOf(1, 2, 3), contentType = "text/plain")
        assertEquals(415, text.status)
        assertEquals("unsupported_media_type", errorCode(text))

        val missing = upload(byteArrayOf(1, 2, 3), contentType = null)
        assertEquals(415, missing.status)
        assertEquals("unsupported_media_type", errorCode(missing))
        assertTrue(store.mediaItems().isEmpty())
    }

    @Test
    fun `mime query must match content type`() {
        val mismatch = upload(
            byteArrayOf(1, 2, 3),
            "/v1/media?kind=CHAT_IMAGE&mime=image/png",
            contentType = "image/jpeg"
        )
        assertEquals(400, mismatch.status)
        assertEquals("mime_mismatch", errorCode(mismatch))

        val badMime = upload(
            byteArrayOf(1, 2, 3),
            "/v1/media?kind=CHAT_IMAGE&mime=text/plain",
            contentType = "image/jpeg"
        )
        assertEquals(415, badMime.status)

        val ok = upload(
            byteArrayOf(1, 2, 3),
            "/v1/media?kind=CHAT_IMAGE&mime=image/jpeg",
            contentType = "image/jpeg"
        )
        assertEquals(201, ok.status)
    }

    @Test
    fun `kind is required and restricted to known values`() {
        val missing = upload(byteArrayOf(1), "/v1/media")
        assertEquals(400, missing.status)
        assertEquals("missing_field", errorCode(missing))

        val invalid = upload(byteArrayOf(1), "/v1/media?kind=UNKNOWN")
        assertEquals(400, invalid.status)
        assertEquals("invalid_argument", errorCode(invalid))

        // 0.13：GIF/音频/视频也是合法 kind。
        assertEquals(201, upload(byteArrayOf(1, 2, 3), "/v1/media?kind=VIDEO&mime=video/mp4", "video/mp4").status)
        assertEquals(201, upload(byteArrayOf(4, 5, 6), "/v1/media?kind=AUDIO&mime=audio/m4a", "audio/m4a").status)
        assertEquals(201, upload(byteArrayOf(7, 8, 9), "/v1/media?kind=STICKER&mime=image/gif", "image/gif").status)
    }

    @Test
    fun `persona id must be well formed and exist`() {
        val unknown = upload(byteArrayOf(1), "/v1/media?kind=PERSONA_AVATAR&personaId=no-such-persona")
        assertEquals(404, unknown.status)
        assertEquals("not_found", errorCode(unknown))

        val malformed = upload(byteArrayOf(1), "/v1/media?kind=PERSONA_AVATAR&personaId=..%2Fescape")
        assertEquals(400, malformed.status)
        assertEquals("invalid_argument", errorCode(malformed))
    }

    @Test
    fun `file size is capped at two mebibytes`() {
        val empty = upload(ByteArray(0))
        assertEquals(400, empty.status)
        assertEquals("empty_body", errorCode(empty))

        val tooLarge = upload(ByteArray(MediaRoutes.MAX_UPLOAD_BYTES + 1))
        assertEquals(413, tooLarge.status)
        assertEquals("content_too_large", errorCode(tooLarge))

        val exactlyLimit = upload(ByteArray(MediaRoutes.MAX_UPLOAD_BYTES) { 7 })
        assertEquals(exactlyLimit.body, 201, exactlyLimit.status)
        assertTrue(store.mediaItems().single().bytes == MediaRoutes.MAX_UPLOAD_BYTES.toLong())
    }

    // ------------------------------------------------------------------ 下载

    @Test
    fun `download returns raw bytes with cache headers and etag`() {
        val bytes = "hello-image".toByteArray()
        val body = uploadOk(bytes)
        val id = body.str("id")

        val response = download(id)
        assertEquals(200, response.status)
        assertEquals(MediaMime.JPEG, response.contentType)
        assertEquals(MediaRoutes.CACHE_CONTROL, response.headers["Cache-Control"])
        assertEquals("\"${sha256(bytes)}\"", response.headers["ETag"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertArrayEquals(bytes, response.bytes)
        assertEquals("哈希与响应体一致", body.str("sha256"), sha256(response.bytes))
    }

    @Test
    fun `if none match returns 304 for exact weak and star etags`() {
        val bytes = byteArrayOf(3, 1, 4, 1, 5)
        val sha = sha256(bytes)
        val id = uploadOk(bytes).str("id")

        val exact = download(id, "\"$sha\"")
        assertEquals(304, exact.status)
        assertNull("304 没有 Content-Type", exact.contentType)
        assertEquals(0, exact.bytes.size)
        assertEquals("\"$sha\"", exact.headers["ETag"])

        assertEquals(304, download(id, "W/\"$sha\"").status)
        assertEquals(304, download(id, "\"other\", \"$sha\"").status)
        assertEquals(304, download(id, "*").status)

        val stale = download(id, "\"${sha256(byteArrayOf(0))}\"")
        assertEquals(200, stale.status)
        assertArrayEquals(bytes, stale.bytes)
    }

    @Test
    fun `unknown and malformed media ids are 404`() {
        assertEquals(404, resultJson(downloadResult("no-such-media")).status)

        val traversal = MediaRoutes.handle(
            services(),
            "GET",
            "/v1/media/..%2Fmeta.json",
            emptyMap(),
            ByteArray(0)
        )
        val traversalResponse = resultJson(traversal)
        assertEquals(404, traversalResponse.status)
        assertFalse("不能回显文件路径", traversalResponse.body.contains("meta.json"))

        val nested = resultJson(MediaRoutes.handle(services(), "GET", "/v1/media/a/b", emptyMap(), ByteArray(0)))
        assertEquals(404, nested.status)

        assertEquals(404, delete("no-such-media").status)
    }

    // ------------------------------------------------------------------ 删除与引用

    @Test
    fun `deleting an unreferenced media removes the file and metadata`() {
        val body = uploadOk(byteArrayOf(1, 2, 3, 4))
        val id = body.str("id")

        val response = delete(id)
        assertEquals(response.body, 200, response.status)
        assertEquals(id, jsonOf(response).str("id"))
        assertTrue(jsonOf(response).bool("deleted"))
        assertFalse(Files.exists(root.resolve(FileStore.MEDIA_DIR).resolve("$id.jpg")))
        assertTrue(mediaFiles().isEmpty())
        assertTrue(store.mediaItems().isEmpty())

        assertEquals(404, delete(id).status)
    }

    @Test
    fun `media referenced by a message is 409 until forced`() {
        val body = uploadOk(byteArrayOf(5, 5, 5))
        val id = body.str("id")
        store.appendMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = "发张图",
            attachments = listOf(body.str("url"))
        )

        val blocked = delete(id)
        assertEquals(409, blocked.status)
        assertEquals("media_in_use", errorCode(blocked))
        assertEquals(1, jsonOf(blocked).int("references"))
        assertNotNull("409 时文件必须还在", store.media(id))

        val forced = delete(id, "?force=1")
        assertEquals(forced.body, 200, forced.status)
        assertTrue(jsonOf(forced).bool("deleted"))
        assertNull(store.media(id))
        assertFalse(Files.exists(root.resolve(FileStore.MEDIA_DIR).resolve("$id.jpg")))
        assertEquals(
            "强制删除不重写历史消息",
            listOf(body.str("url")),
            store.blockMessages(sessionId, 0).single().attachments
        )
    }

    @Test
    fun `persona avatar and preset avatar also count as references`() {
        val personaMedia = uploadOk(
            byteArrayOf(7, 7, 7),
            "/v1/media?kind=PERSONA_AVATAR&personaId=$personaId"
        )
        val personaUrl = personaMedia.str("url")
        store.updatePersona(personaId) { persona, _ -> persona.copy(avatarUrl = personaUrl) }
        val personaBlocked = delete(personaMedia.str("id"))
        assertEquals(409, personaBlocked.status)
        assertEquals(1, jsonOf(personaBlocked).int("references"))

        val presetMedia = uploadOk(byteArrayOf(8, 8, 8), "/v1/media?kind=CHAT_IMAGE")
        val presetUrl = presetMedia.str("url")
        store.savePreset(null) { _, now ->
            Preset(id = "", name = "带头像的预设", avatarUri = presetUrl, createdAt = now, updatedAt = now)
        }
        val presetBlocked = delete(presetMedia.str("id"))
        assertEquals(409, presetBlocked.status)
        assertEquals(1, jsonOf(presetBlocked).int("references"))
    }

    @Test
    fun `media routes need a data directory and enforce methods`() {
        val noStore = MediaRoutes.handle(
            AgentdServices(),
            "POST",
            "/v1/media?kind=CHAT_IMAGE",
            mapOf("content-type" to "image/jpeg"),
            byteArrayOf(1, 2, 3)
        )
        assertEquals(503, resultJson(noStore).status)

        val put = resultJson(
            MediaRoutes.handle(services(), "PUT", "/v1/media", mapOf("content-type" to "image/jpeg"), ByteArray(0))
        )
        assertEquals(405, put.status)
        assertEquals("method_not_allowed", errorCode(put))

        val deleteRoot = resultJson(MediaRoutes.handle(services(), "DELETE", "/v1/media", emptyMap(), ByteArray(0)))
        assertEquals(405, deleteRoot.status)
    }

    @Test
    fun `invalid force value is rejected instead of guessed`() {
        val body = uploadOk(byteArrayOf(1))
        val response = delete(body.str("id"), "?force=maybe")
        assertEquals(400, response.status)
        assertEquals("invalid_argument", errorCode(response))
    }

    // ------------------------------------------------------------------ 消息附件

    @Test
    fun `message attachments accept ids or urls and are stored and returned as urls`() {
        val first = uploadOk(byteArrayOf(1, 2))
        val second = uploadOk(byteArrayOf(3, 4))
        val sink = sendWithAttachments(
            """{"clientMessageId":"media-attach-1","content":"看图","attachments":["${first.str("id")}","${second.str("url")}"]}"""
        )
        assertEquals("accepted", sink.events.first().type)
        assertEquals(
            listOf("/v1/media/${first.str("id")}", "/v1/media/${second.str("id")}"),
            store.blockMessages(sessionId, 0).first { it.role == MessageRole.USER }.attachments
        )

        val page = messagesPage()
        val userMessage = page.objects("messages").first { it.str("role") == MessageRole.USER }
        assertEquals(
            listOf(first.str("url"), second.str("url")),
            userMessage.strings("attachments")
        )
    }

    @Test
    fun `unknown attachment is rejected before the user message is persisted`() {
        val failure = try {
            sendWithAttachments(
                """{"clientMessageId":"media-attach-missing","content":"看图","attachments":["missing-media"]}"""
            )
            null
        } catch (failure: AgentdApi.ApiFailure) {
            failure
        }
        assertNotNull(failure)
        assertEquals(400, failure!!.status)
        assertEquals("unknown_media", failure.code)
        assertTrue("不能落半条消息", store.blockMessages(sessionId, 0).isEmpty())

        val malformed = try {
            sendWithAttachments(
                """{"clientMessageId":"media-attach-bad","content":"看图","attachments":["../escape"]}"""
            )
            null
        } catch (failure: AgentdApi.ApiFailure) {
            failure
        }
        assertNotNull(malformed)
        assertEquals(400, malformed!!.status)
        assertEquals("invalid_argument", malformed.code)
    }

    // ------------------------------------------------------------------ 备份

    @Test
    fun `backup export contains media base64 and round trips into a fresh store`() {
        val bytes = "backup-image-bytes".toByteArray()
        val media = uploadOk(bytes)
        val id = media.str("id")
        val url = media.str("url")
        store.appendMessage(sessionId, MessageRole.USER, "带图消息", attachments = listOf(url))
        store.updatePersona(personaId) { persona, _ -> persona.copy(avatarUrl = url) }

        val backup = exportBackup()
        assertEquals(5, backup.int("formatVersion"))
        assertEquals(0, backup.int("mediaSkipped"))
        val exported = backup.objects("media").single()
        assertEquals(id, exported.str("id"))
        assertEquals(sha256(bytes), exported.str("sha256"))
        assertArrayEquals(bytes, Base64.getDecoder().decode(exported.str("data")))

        val targetRoot = TestSupport.tempDir("agentd-media-import")
        extraRoots.add(targetRoot)
        val target = FileStore.open(targetRoot, clock.reader)
        val response = importBackup(target, backup)
        assertEquals(response.body, 200, response.status)
        assertEquals(1, jsonOf(response).obj("imported").int("media"))

        val restored = target.media(id)
        assertNotNull("导入后媒体元数据要回来", restored)
        assertArrayEquals(bytes, target.mediaBytes(id))
        assertTrue(Files.exists(targetRoot.resolve(FileStore.MEDIA_DIR).resolve("$id.jpg")))
        assertEquals(
            listOf(url),
            target.blockMessages(sessionId, 0).single().attachments
        )
        assertEquals(url, target.persona(personaId)?.avatarUrl)
        assertEquals("导入后引用计数仍能挡住删除（消息 + 人格头像）", 2, target.mediaReferenceCount(id))
    }

    @Test
    fun `backup skips oversized and tampered media and counts them`() {
        store.saveMedia(
            MediaKind.CHAT_IMAGE,
            null,
            MediaMime.JPEG,
            ByteArray((FileStore.MAX_BACKUP_MEDIA_FILE_BYTES + 1).toInt()) { 1 }
        )
        val tampered = store.saveMedia(MediaKind.CHAT_IMAGE, null, MediaMime.JPEG, byteArrayOf(1, 2, 3)).item
        Files.write(root.resolve(FileStore.MEDIA_DIR).resolve("${tampered.id}.jpg"), byteArrayOf(9, 9, 9))
        val backup = exportBackup()
        assertEquals(0, backup.objects("media").size)
        assertEquals(2, backup.int("mediaSkipped"))
    }

    @Test
    fun `backup import rejects a media entry whose sha does not match and writes nothing`() {
        val bytes = byteArrayOf(1, 2, 3)
        val entry = backupMediaEntry("media-bad-sha", bytes, sha = sha256(byteArrayOf(9)))
        val data = TestSupport.parse(
            """{"formatVersion":5,"personas":[],"sessions":[],"messages":[],"media":[],"settings":{}}"""
        )
        data.fields["media"] = JsonValue.Arr(mutableListOf(entry))
        val response = importBackup(store, data)
        assertEquals(400, response.status)
        assertEquals("invalid_backup", errorCode(response))
        assertTrue("校验失败不能落任何媒体", store.mediaItems().isEmpty())
        assertTrue(mediaFiles().isEmpty())
    }

    @Test
    fun `replace import clears old media and restores only the backup media`() {
        val old = uploadOk(byteArrayOf(1, 1, 1))
        val newBytes = byteArrayOf(2, 2, 2)
        val entry = backupMediaEntry("media-restored", newBytes)
        val data = TestSupport.parse(
            """{"formatVersion":5,"personas":[],"sessions":[],"messages":[],"media":[],"settings":{}}"""
        )
        data.fields["media"] = JsonValue.Arr(mutableListOf(entry))

        val response = importBackup(store, data)
        assertEquals(response.body, 200, response.status)
        assertNull("replace 要清掉旧媒体", store.media(old.str("id")))
        assertFalse(Files.exists(root.resolve(FileStore.MEDIA_DIR).resolve("${old.str("id")}.jpg")))
        assertNotNull(store.media("media-restored"))
        assertArrayEquals(newBytes, store.mediaBytes("media-restored"))
        assertEquals(1, mediaFiles().size)
    }

    // ------------------------------------------------------------------ 重启与并发

    @Test
    fun `media metadata and bytes survive a restart`() {
        val bytes = "restart-image".toByteArray()
        val media = uploadOk(bytes)
        val reopened = FileStore.open(root, clock.reader)
        assertNotNull(reopened.media(media.str("id")))
        assertArrayEquals(bytes, reopened.mediaBytes(media.str("id")))
        assertEquals(1, reopened.mediaItems().size)
    }

    @Test
    fun `concurrent uploads of the same content store exactly one file`() {
        val bytes = "concurrent-image".toByteArray()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (1..threads).map {
                pool.submit<Boolean> {
                    start.await(5, TimeUnit.SECONDS)
                    store.saveMedia(MediaKind.CHAT_IMAGE, null, MediaMime.JPEG, bytes).created
                }
            }
            start.countDown()
            val created = futures.count { it.get(10, TimeUnit.SECONDS) }
            assertEquals("同内容只允许一次 created", 1, created)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, store.mediaItems().size)
        assertEquals(1, mediaFiles().size)
    }
}
