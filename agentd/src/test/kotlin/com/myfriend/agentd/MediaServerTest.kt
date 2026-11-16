package com.myfriend.agentd

import com.myfriend.agentd.store.FileStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
import java.nio.file.Path

/**
 * 媒体路由的**真实 socket** 集成测试：二进制 body 不会被 UTF-8 解码破坏、下载头真的到了客户端、
 * `If-None-Match` 走 304、token 与 body 上限在媒体路径上和其他 `/v1` 一致。
 *
 * handler 级（[MediaApiTest]）已经覆盖大部分语义；这里只补「HTTP 接线」这一层。
 */
class MediaServerTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)
    private val client = HttpClient.newHttpClient()
    private val servers = mutableListOf<AgentdServer.StartedServer>()

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-media-server-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        TestSupport.deleteRecursively(root)
    }

    private fun start(token: String? = null): AgentdServer.StartedServer {
        val server = AgentdServer.start(
            "127.0.0.1",
            0,
            token,
            RateLimiter(perMinute = 0, burst = 1),
            AgentdServices(store)
        )
        servers += server
        return server
    }

    private fun postBytes(
        port: Int,
        path: String,
        bytes: ByteArray,
        contentType: String,
        token: String? = null
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun getBytes(
        port: Int,
        path: String,
        ifNoneMatch: String? = null,
        token: String? = null
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET()
        if (ifNoneMatch != null) builder.header("If-None-Match", ifNoneMatch)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    fun `real server uploads and downloads raw bytes with cache headers and 304`() {
        val server = start()
        val bytes = byteArrayOf(0, 1, 2, 3, 127, -1, -2, 10, 13)
        val upload = postBytes(
            server.port,
            "/v1/media?kind=CHAT_IMAGE&mime=image/jpeg",
            bytes,
            contentType = "image/jpeg"
        )
        assertEquals(String(upload.body(), StandardCharsets.UTF_8), 201, upload.statusCode())
        val body = TestSupport.parse(String(upload.body(), StandardCharsets.UTF_8))
        val id = body.str("id")

        val download = getBytes(server.port, "/v1/media/$id")
        assertEquals(200, download.statusCode())
        assertArrayEquals(bytes, download.body())
        assertEquals("image/jpeg", download.headers().firstValue("Content-Type").orElse(""))
        assertEquals(
            MediaRoutes.CACHE_CONTROL,
            download.headers().firstValue("Cache-Control").orElse("")
        )
        val etag = download.headers().firstValue("ETag").orElse("")
        assertEquals("\"${body.str("sha256")}\"", etag)

        val notModified = getBytes(server.port, "/v1/media/$id", ifNoneMatch = etag)
        assertEquals(304, notModified.statusCode())
        assertEquals(0, notModified.body().size)
        assertEquals(etag, notModified.headers().firstValue("ETag").orElse(""))
        assertEquals(
            MediaRoutes.CACHE_CONTROL,
            notModified.headers().firstValue("Cache-Control").orElse("")
        )
    }

    @Test
    fun `media routes require the token like every other v1 route`() {
        val token = "test-token-1234567890"
        val server = start(token)
        val bytes = byteArrayOf(4, 2)

        val unauthorizedUpload = postBytes(
            server.port,
            "/v1/media?kind=CHAT_IMAGE",
            bytes,
            contentType = "image/jpeg"
        )
        assertEquals(401, unauthorizedUpload.statusCode())
        assertTrue(String(unauthorizedUpload.body(), StandardCharsets.UTF_8).contains("unauthorized"))

        val upload = postBytes(
            server.port,
            "/v1/media?kind=CHAT_IMAGE",
            bytes,
            contentType = "image/jpeg",
            token = token
        )
        assertEquals(201, upload.statusCode())
        val id = TestSupport.parse(String(upload.body(), StandardCharsets.UTF_8)).str("id")

        assertEquals(401, getBytes(server.port, "/v1/media/$id").statusCode())
        val authorized = getBytes(server.port, "/v1/media/$id", token = token)
        assertEquals(200, authorized.statusCode())
        assertArrayEquals(bytes, authorized.body())
    }

    @Test
    fun `media uploads accept up to four mebibytes at the http layer and still cap files at two`() {
        val server = start()
        // 3 MiB：HTTP 层允许（媒体路由 4 MiB），路由层按单文件 2 MiB 拒绝。
        val threeMib = ByteArray(3 * 1024 * 1024) { 1 }
        val tooLargeFile = postBytes(
            server.port,
            "/v1/media?kind=CHAT_IMAGE",
            threeMib,
            contentType = "image/jpeg"
        )
        assertEquals(413, tooLargeFile.statusCode())
        assertTrue(String(tooLargeFile.body(), StandardCharsets.UTF_8).contains("content_too_large"))
    }

    @Test
    fun `declared body above four mebibytes on media is rejected from the headers`() {
        val server = start()
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5_000
            val request = buildString {
                append("POST /v1/media?kind=CHAT_IMAGE HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Content-Type: image/jpeg\r\n")
                append("Content-Length: ${MediaRoutes.MAX_REQUEST_BODY_BYTES + 1}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val response = readAll(socket.getInputStream())
            assertTrue("期望 413，实际：${response.take(160)}", response.startsWith("HTTP/1.1 413"))
            assertTrue(response.contains("content_too_large"))
            assertTrue("媒体路由的上限文案应是 4096 KiB", response.contains("4096 KiB"))
        }

        // 其它路由仍保持 1 MiB：声明 1.5 MiB 直接 413。
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5_000
            val request = buildString {
                append("POST /v1/personas HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${AgentdApi.MAX_BODY_BYTES + 1}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val response = readAll(socket.getInputStream())
            assertTrue("期望 413，实际：${response.take(160)}", response.startsWith("HTTP/1.1 413"))
            assertTrue("非媒体路由仍是 1024 KiB", response.contains("1024 KiB"))
        }
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: java.net.SocketTimeoutException) {
                break
            }
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }
}
