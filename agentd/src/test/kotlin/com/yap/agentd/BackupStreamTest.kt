package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaKind
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.MessageRole
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 0.12.1 的备份「流式」语义（`GET/POST /v1/backup`）。
 *
 * 与 [BackupApiTest] 的分工：那边测的是**语义**（merge/replace、校验、事务性），这里测的是
 * **传输形态与边界**：
 * - `GET /v1/backup` 真的用 `Transfer-Encoding: chunked` 流式写（原始 socket 逐段解码）；
 * - JDK `HttpClient` 拿到的 body 与 handler 直调路径逐字节一致（客户端可解析）；
 * - `POST /v1/backup` 的请求体被流式落到 `.staging/` 临时文件，导入后临时文件消失；
 * - 坏 JSON / 信封顺序不对 → 400 且零写入、零残渣；
 * - 8 MiB 单张媒体走「流式 base64」往返（不经过整份 `JsonValue`）；
 * - 超大媒体导出时跳过并计入 `mediaSkipped`；
 * - 未知顶层字段（未来版本新增段）被跳过而不是报错。
 */
class BackupStreamTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)
    private val extraRoots = mutableListOf<Path>()
    private val client = HttpClient.newHttpClient()

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-backup-stream")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
        extraRoots.forEach { TestSupport.deleteRecursively(it) }
    }

    // ------------------------------------------------------------------ 小工具

    private fun handlerExport(target: FileStore = store): AgentdApi.Response =
        AuthorityRoutes.handle(AgentdServices(target), "GET", "/v1/backup", null)

    private fun handlerImport(target: FileStore, body: String): AgentdApi.Response =
        AuthorityRoutes.handle(AgentdServices(target), "POST", "/v1/backup", body)

    private fun envelope(mode: String, data: JsonValue): String =
        Json.stringify(JsonValue.Obj(linkedMapOf("mode" to JsonValue.Str(mode), "data" to data)))

    private fun freshStore(prefix: String): FileStore {
        val target = TestSupport.tempDir(prefix)
        extraRoots.add(target)
        return FileStore.open(target, clock.reader)
    }

    private fun seedSmallContent() {
        store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.USER, "用户消息")
        store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.ASSISTANT, "助手消息")
    }

    private fun uploadMedia(bytes: ByteArray, mime: String = MediaMime.JPEG): String =
        store.saveMedia(MediaKind.CHAT_IMAGE, null, mime, bytes).item.id

    /** 用流式导出写进文件（生产路径：socket chunked；测试里用文件 Writer，内存占用同一量级）。 */
    private fun exportToFile(target: FileStore, file: Path) {
        Files.newBufferedWriter(file, StandardCharsets.UTF_8).use { writer ->
            target.writeBackup(AgentdApi.VERSION, target.serverTime(), writer)
        }
    }

    private fun startServer(): AgentdServer.StartedServer = AgentdServer.start(
        "127.0.0.1",
        0,
        services = AgentdServices(store),
        rateLimiter = RateLimiter(perMinute = 0, burst = 1)
    )

    // ------------------------------------------------------------------ chunked 流式导出

    /**
     * 原始 socket：断言 `Transfer-Encoding: chunked`、没有 `Content-Length`，并把 chunk 拼回 body。
     * 这是「导出的确是流式」最直接的证据（一次性响应一定会带 Content-Length）。
     */
    @Test
    fun `backup export uses chunked transfer encoding`() {
        seedSmallContent()
        val server = startServer()
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 20_000
                socket.getOutputStream().apply {
                    write(
                        (
                            "GET /v1/backup HTTP/1.1\r\n" +
                                "Host: 127.0.0.1\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII)
                    )
                    flush()
                }
                val raw = readAll(socket.getInputStream())
                val headerEnd = raw.indexOf("\r\n\r\n")
                assertTrue("没有找到响应头结束标记", headerEnd > 0)
                val head = raw.substring(0, headerEnd)
                assertTrue("状态行不对：${head.lineSequence().first()}", head.startsWith("HTTP/1.1 200"))
                assertTrue("必须是 chunked：$head", head.contains("Transfer-Encoding: chunked", ignoreCase = true))
                assertFalse("流式响应不能带 Content-Length：$head", head.contains("Content-Length:", ignoreCase = true))

                val body = decodeChunked(raw.substring(headerEnd + 4))
                val parsed = TestSupport.parse(body)
                assertEquals(FileStore.BACKUP_FORMAT_NAME, parsed.str("format"))
                assertEquals(FileStore.BACKUP_FORMAT_VERSION, parsed.int("formatVersion"))
                assertEquals(2, parsed.objects("messages").size)
            }
        } finally {
            server.stop()
        }
    }

    /** JDK HttpClient 拿到的 body 与 handler 直调路径逐字节一致（客户端/测试可解析）。 */
    @Test
    fun `chunked export body equals the handler level export`() {
        seedSmallContent()
        val server = startServer()
        try {
            val response = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/v1/backup")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, response.statusCode())
            val streamed = TestSupport.parse(response.body())
            val direct = TestSupport.parse(handlerExport().body)
            for (section in listOf("personas", "sessions", "messages", "memories", "presets", "media", "settings")) {
                assertEquals(
                    "$section 在流式与直调路径下应一致",
                    Json.stringify(direct.fields.getValue(section)),
                    Json.stringify(streamed.fields.getValue(section))
                )
            }
            assertEquals(direct.str("appVersion"), streamed.str("appVersion"))
            assertEquals(direct.obj("extensions").int("mediaSkipped"), streamed.obj("extensions").int("mediaSkipped"))
        } finally {
            server.stop()
        }
    }

    /** 没有任何数据目录（`services.store == null`）时导出回 503，而不是流式写半截。 */
    @Test
    fun `backup export without a data directory is 503`() {
        val server = AgentdServer.start(
            "127.0.0.1",
            0,
            services = AgentdServices(store = null),
            rateLimiter = RateLimiter(perMinute = 0, burst = 1)
        )
        try {
            val response = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/v1/backup")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(503, response.statusCode())
            assertTrue(response.body().contains("state_unavailable"))
        } finally {
            server.stop()
        }
    }

    // ------------------------------------------------------------------ 请求体流式落盘

    /** 真 HTTP：`POST /v1/backup` 的 body 先落临时文件再增量解析；导入成功后临时文件不残留。 */
    @Test
    fun `backup import over http streams the body to a staging file`() {
        seedSmallContent()
        val exported = TestSupport.parse(handlerExport().body)
        val server = startServer()
        try {
            val response = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/v1/backup"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope("replace", exported)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(response.body(), 200, response.statusCode())
            val imported = TestSupport.parse(response.body()).obj("imported")
            assertEquals(2, imported.int("messages"))
            assertTrue(Files.exists(root.resolve(FileStore.SESSIONS_FILE)))
            // 清理是请求结束后的收尾动作：极慢的 CI 上可能晚一两个调度周期才从磁盘消失。
            val staging = root.resolve(FileStore.STAGING_DIR)
            val deadline = System.currentTimeMillis() + 2_000
            while (Files.exists(staging) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertFalse(
                "导入结束后必须清掉 `.staging/`（成功路径也不留垃圾）",
                Files.exists(staging)
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `bad json import over http is 400 and writes nothing`() {
        val before = Files.list(root).use { stream -> stream.count() }
        val server = startServer()
        try {
            val response = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/v1/backup"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"merge\",\"data\":{\"personas\":[{"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, response.statusCode())
            assertTrue("坏 JSON 要回 invalid_json：${response.body()}", response.body().contains("invalid_json"))
        } finally {
            server.stop()
        }
        assertEquals("失败时数据目录里不能多出文件", before, Files.list(root).use { stream -> stream.count() })
    }

    @Test
    fun `backup import rejects data before mode`() {
        val body = """{"data":{"formatVersion":5,"personas":[]},"mode":"merge"}"""
        val response = handlerImport(store, body)
        assertEquals(400, response.status)
        assertEquals("invalid_backup", TestSupport.parse(response.body).obj("error").str("code"))
    }

    @Test
    fun `backup import rejects a data section that is not an object`() {
        val response = handlerImport(store, """{"mode":"merge","data":[]}""")
        assertEquals(400, response.status)
        assertTrue(
            "data 不是对象时要么 invalid_json 要么 invalid_backup：${response.body}",
            response.body.contains("invalid_json") || response.body.contains("invalid_backup")
        )
    }

    @Test
    fun `trailing garbage after the envelope is rejected`() {
        val body = """{"mode":"merge","data":{"formatVersion":5,"personas":[]}} trailing"""
        val response = handlerImport(store, body)
        assertEquals(400, response.status)
        assertTrue(response.body.contains("invalid_json"))
    }

    // ------------------------------------------------------------------ 流式 base64

    /** 8 MiB 单张媒体：导出→导入往返字节一致（全程不构造整份 base64 字符串）。 */
    @Test
    fun `eight mebibyte media round trips through the streaming backup`() {
        val bytes = ByteArray(8 * 1024 * 1024) { index -> (index % 251).toByte() }
        val mediaId = uploadMedia(bytes)
        store.appendMessage(
            FileStore.SEED_SESSION_ID,
            MessageRole.USER,
            "带大图的消息",
            attachments = listOf("/v1/media/$mediaId")
        )

        val dir = TestSupport.tempDir("agentd-backup-bigmedia")
        extraRoots.add(dir)
        val backup = dir.resolve("backup.json")
        exportToFile(store, backup)
        assertTrue("8 MiB 媒体 base64 后备份至少 10 MiB", Files.size(backup) > 10L * 1024 * 1024)

        // POST /v1/backup 的 body 是信封；用流式拼接，避免把 11 MiB base64 放进内存字符串。
        val upload = dir.resolve("upload.json")
        wrapAsEnvelope(backup, upload, "replace")
        val target = freshStore("agentd-backup-bigmedia-target")
        val response = CrudRoutes.importBackupFromFile(target, upload)
        assertEquals(response.body, 200, response.status)
        val restored = target.media(mediaId)
        assertNotNull("8 MiB 媒体必须导入成功（<=8 MiB 上限内）", restored)
        assertEquals(bytes.size.toLong(), restored!!.bytes)
        val targetFile = target.mediaDirectory.resolve("$mediaId.jpg")
        assertTrue(Files.exists(targetFile))
        assertArrayEquals(bytes, Files.readAllBytes(targetFile))
        assertEquals(1, TestSupport.parse(response.body).obj("imported").int("media"))
    }

    /** 超过 8 MiB 单文件上限的媒体：导出时跳过并计入 `mediaSkipped`（导入端据此知道少了几张）。 */
    @Test
    fun `oversized media is skipped and counted in mediaSkipped`() {
        val tooBig = ByteArray((FileStore.MAX_BACKUP_MEDIA_FILE_BYTES + 1024).toInt()) { 7 }
        uploadMedia(tooBig)
        val smallId = uploadMedia("small-image".toByteArray())

        val backup = TestSupport.tempDir("agentd-backup-skip").resolve("backup.json")
        extraRoots.add(backup.parent)
        exportToFile(store, backup)
        val text = Files.readString(backup, StandardCharsets.UTF_8)
        assertTrue("mediaSkipped 必须是 1：${text.takeLast(200)}", text.contains("\"mediaSkipped\":1"))
        assertTrue("导入的备份里应包含小图：$text", text.contains(smallId))
        assertFalse("超限媒体不该出现在备份里", text.contains("\"bytes\":${tooBig.size}"))
    }

    /** 坏 JSON（媒体 base64 被截断）导入失败，且不写任何媒体文件。 */
    @Test
    fun `corrupt media base64 is rejected without writing media`() {
        val body = """
            {"mode":"replace","data":{"formatVersion":5,
              "personas":[{"id":"p1","name":"P","createdAt":1,"updatedAt":1}],
              "sessions":[{"id":"s1","personaId":"p1","title":"t","createdAt":1,"updatedAt":1}],
              "messages":[],"memories":[],"presets":[],
              "media":[{"id":"m1","kind":"CHAT_IMAGE","mime":"image/jpeg","bytes":3,
                        "data":"QUJD!","createdAt":1}]}}
        """.trimIndent()
        val target = freshStore("agentd-backup-corrupt-media")
        val response = CrudRoutes.importBackupFromFile(target, writeTemp(body))
        assertEquals(400, response.status)
        assertTrue(response.body.contains("invalid_json") || response.body.contains("invalid_backup"))
        assertTrue(
            "校验失败不能落媒体文件",
            Files.list(target.mediaDirectory).use { stream -> stream.noneMatch { Files.isRegularFile(it) } }
        )
    }

    // ------------------------------------------------------------------ 兼容性

    /** 段顺序不同、`formatVersion` 放在最后、有未知顶层段：仍然能导入（旧/未来备份）。 */
    @Test
    fun `sections in any order and unknown sections are accepted`() {
        val body = """
            {"mode":"replace","data":{
              "settings":{"themeMode":"DARK","userAvatarUri":"/v1/media/avatar-1"},
              "futureSection":{"anything":[1,2,3]},
              "messages":[
                {"id":"m1","sessionId":"s1","role":"USER","content":"嗨","createdAt":11},
                {"id":"m2","sessionId":"s1","role":"ASSISTANT","content":"在","createdAt":12}
              ],
              "sessions":[{"id":"s1","personaId":"p1","title":"对话","createdAt":10,"updatedAt":10}],
              "personas":[{"id":"p1","name":"唯一","createdAt":10,"updatedAt":10}],
              "formatVersion":5
            }}
        """.trimIndent()
        val target = freshStore("agentd-backup-order")
        val response = CrudRoutes.importBackupFromFile(target, writeTemp(body))
        assertEquals(response.body, 200, response.status)
        assertEquals("唯一", target.persona("p1")?.name)
        assertEquals(2, target.blockMessages("s1", 0).size)
        assertEquals("DARK", target.publicSettings().str("themeMode"))
        assertEquals("/v1/media/avatar-1", target.publicSettings().str("userAvatarUri"))
    }

    /** 旧别名 `myAvatarUri` 读时归一成 `userAvatarUri`（客户端两条线各用过一个名字）。 */
    @Test
    fun `legacy avatar setting alias is normalised`() {
        assertEquals(
            200,
            handlerImport(store, """{"mode":"merge","data":{"formatVersion":5,"settings":{"myAvatarUri":"/v1/media/a"}}}""").status
        )
        val settings = TestSupport.parse(handlerExport().body).obj("settings")
        assertEquals("/v1/media/a", settings.str("userAvatarUri"))
        assertFalse("别名不能同时出现在导出里", settings.fields.containsKey("myAvatarUri"))
    }

    /** 设置里的用户头像走白名单（之前会被静默丢弃）。 */
    @Test
    fun `user avatar setting is whitelisted and survives export`() {
        val response = AuthorityRoutes.handle(
            AgentdServices(store),
            "POST",
            "/v1/settings",
            """{"userAvatarUri":"/v1/media/abc"}"""
        )
        assertEquals(response.body, 200, response.status)
        assertEquals("/v1/media/abc", TestSupport.parse(response.body).obj("settings").str("userAvatarUri"))
        assertEquals(
            "/v1/media/abc",
            TestSupport.parse(handlerExport().body).obj("settings").str("userAvatarUri")
        )
    }

    // ------------------------------------------------------------------ 工具

    /** `{"mode":"…","data":` + 备份原文 + `}`：流式拼信封，不把备份读成字符串。 */
    private fun wrapAsEnvelope(backup: Path, target: Path, mode: String) {
        Files.newBufferedWriter(target, StandardCharsets.UTF_8).use { writer ->
            writer.write("{\"mode\":")
            writer.write(Json.stringify(JsonValue.Str(mode)))
            writer.write(",\"data\":")
            Files.newBufferedReader(backup, StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    writer.write(buffer, 0, read)
                }
            }
            writer.write("}")
        }
    }

    private fun writeTemp(text: String): Path {
        val dir = TestSupport.tempDir("agentd-backup-json")
        extraRoots.add(dir)
        val file = dir.resolve("backup.json")
        Files.writeString(file, text, StandardCharsets.UTF_8)
        return file
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    /** 解 `Transfer-Encoding: chunked`（`STATUS\r\n...\r\n\r\n` 之后的 body 部分）。 */
    private fun decodeChunked(body: String): String {
        val out = ByteArrayOutputStream()
        var index = 0
        while (true) {
            val next = body.indexOf("\r\n", index)
            if (next < 0) break
            val size = body.substring(index, next).trim().toInt(16)
            index = next + 2
            if (size == 0) break
            out.write(body.substring(index, index + size).toByteArray(StandardCharsets.ISO_8859_1))
            index += size + 2
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }
}
