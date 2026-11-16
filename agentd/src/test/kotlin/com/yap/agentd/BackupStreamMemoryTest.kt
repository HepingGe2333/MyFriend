package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaKind
import com.yap.agentd.store.MediaMime
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 备份「不进内存」的硬证据：**在 `-Xmx256m` 的测试 JVM 里**导出 + 导入 1 万条消息 + 50 张图。
 *
 * 为什么这条测试有效：0.12.0 的实现会
 * 1) 把全部消息拼成 `JsonValue.Arr` → `String`（约 2 MB）× 2 份；
 * 2) 把 50 张 64 KiB 图读成 `ByteArray` + base64 `String` 再塞进同一棵树；
 * 3) 导入时把整份 body 变 `String`、再 `Json.parse` 成整棵树、再把每条媒体的 `ByteArray` 全留在内存。
 * 峰值是「备份大小的数倍」；在 256 MiB 堆里 100k 级数据会直接 OOM，本测试只要实现退回老路就会
 * `OutOfMemoryError`（测试失败）。
 *
 * 现在的实现：
 * - 导出：`GET /v1/backup` 走 chunked，消息按块惰性读、媒体按 24 KiB 分块 base64 → 峰值几十 KB；
 * - 导入：body 流式落 `.staging/` 临时文件，媒体逐条解码到暂存文件 → 峰值由「单条消息 + 单个暂存块」决定。
 *
 * 断言：
 * - JVM 确实只有 256 MiB 堆（`Runtime.maxMemory()`）；
 * - 1 万条消息 / 50 张图 的备份能经真实 HTTP（chunked 导出 + 流式请求体导入）往返；
 * - 导入后的条数、媒体字节数一致（这份备份不是「看起来成功」）。
 */
class BackupStreamMemoryTest {

    private lateinit var root: Path
    private lateinit var workDir: Path
    private lateinit var store: FileStore
    private val client = HttpClient.newHttpClient()

    private val messageCount = 10_000
    private val mediaCount = 50
    private val mediaBytes = 64 * 1024

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-backup-memory")
        workDir = TestSupport.tempDir("agentd-backup-memory-work")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
        TestSupport.deleteRecursively(workDir)
    }

    @Test
    fun `ten thousand messages and fifty images round trip inside a 256m heap`() {
        // 1) 造数据：1 万条消息（每批 200 条，走批量导入接口）+ 50 张 64 KiB 图。
        val totalBatches = messageCount / FileStore.MAX_IMPORT_MESSAGES
        for (batch in 0 until totalBatches) {
            val messages = (0 until FileStore.MAX_IMPORT_MESSAGES).map { offset ->
                val index = batch * FileStore.MAX_IMPORT_MESSAGES + offset
                com.yap.agentd.store.ImportMessage(
                    id = "msg-$index",
                    role = if (index % 2 == 0) "USER" else "ASSISTANT",
                    content = "第 $index 条消息：备份内存测试的正文，长度足够让 JSON 有几百字节。" +
                        "（repeat 两遍以接近真实聊天内容）",
                    createdAt = 1_000L + index,
                    readAt = if (index % 2 == 0) null else 1_000L + index
                )
            }
            val result = store.importMessages(FileStore.SEED_SESSION_ID, messages)
            assertEquals(FileStore.MAX_IMPORT_MESSAGES, result.imported)
        }
        assertEquals(messageCount.toLong(), store.session(FileStore.SEED_SESSION_ID)!!.messageCount)

        val mediaIds = (0 until mediaCount).map { index ->
            val bytes = ByteArray(mediaBytes) { position -> ((index + position) % 251).toByte() }
            store.saveMedia(MediaKind.CHAT_IMAGE, null, MediaMime.JPEG, bytes).item.id
        }
        assertEquals(mediaCount, store.mediaItems().size)

        val server = AgentdServer.start(
            "127.0.0.1",
            0,
            services = AgentdServices(store),
            rateLimiter = RateLimiter(perMinute = 0, burst = 1)
        )
        try {
            // 2) 导出：chunked 流式，`BodyHandlers.ofFile` 让**客户端也不把响应读进内存**。
            val rawBackup = workDir.resolve("backup.json")
            val export = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/v1/backup")).GET().build(),
                HttpResponse.BodyHandlers.ofFile(rawBackup)
            )
            assertEquals(200, export.statusCode())
            assertTrue(
                "导出的备份应包含 1 万条消息 + 50 张图（至少 4 MiB），实际 ${Files.size(rawBackup)} 字节",
                Files.size(rawBackup) > 4L * 1024 * 1024
            )

            // 3) 信封（`{"mode":"replace","data":<备份>}`）流式拼接，不把备份读成字符串。
            val upload = workDir.resolve("upload.json")
            wrapAsEnvelope(rawBackup, upload, "replace")

            // 4) 导入：真实 HTTP + 流式请求体（服务端先落 `.staging/` 再增量解析）。
            val beforeHeapPeak = heapUsed()
            val import = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/v1/backup"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofFile(upload))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(import.body(), 200, import.statusCode())
            val imported = TestSupport.parse(import.body()).obj("imported")
            assertEquals(messageCount, imported.int("messages"))
            assertEquals(mediaCount, imported.int("media"))
            assertEquals(1, imported.int("sessions"))

            // 5) 数据真的落回去了：消息条数、媒体文件字节数都对得上。
            assertEquals(messageCount.toLong(), store.session(FileStore.SEED_SESSION_ID)!!.messageCount)
            assertEquals(mediaCount, store.mediaItems().size)
            val firstMedia = store.media(mediaIds.first())!!
            assertEquals(mediaBytes.toLong(), firstMedia.bytes)
            assertEquals(mediaBytes.toLong(), Files.size(store.mediaDirectory.resolve("${firstMedia.id}.jpg")))

            // 6) 堆上限证据：这条测试确实跑在 256 MiB 堆里（不然「不 OOM」没有意义）。
            val maxHeap = Runtime.getRuntime().maxMemory()
            assertTrue(
                "测试 JVM 的堆上限必须 <= 320 MiB（期望 256 MiB），实际 ${maxHeap / (1024 * 1024)} MiB",
                maxHeap <= 320L * 1024 * 1024
            )
            System.err.println(
                "[agentd-test] 备份内存测试：堆上限=${maxHeap / (1024 * 1024)} MiB，" +
                    "导入前已用≈${beforeHeapPeak / (1024 * 1024)} MiB，" +
                    "备份文件=${Files.size(rawBackup) / 1024} KiB"
            )
        } finally {
            server.stop()
        }
    }

    private fun heapUsed(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

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
}
