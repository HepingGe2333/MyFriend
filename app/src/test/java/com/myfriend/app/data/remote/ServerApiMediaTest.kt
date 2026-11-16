package com.myfriend.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M5 媒体传输层：上传请求形状、2 MiB 硬上限、错误映射、下载路径。
 *
 * 这里只钉「HTTP 上真的发了什么」；「先压缩再上传」的编排顺序在
 * `ServerChatMediaUploaderTest`，引用解析/鉴权在 `ServerMediaRefTest`。
 */
class ServerApiMediaTest {

    private lateinit var server: FakeHttpTestServer
    private lateinit var api: ServerApi

    @Before
    fun setUp() {
        server = FakeHttpTestServer()
        api = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, token = "test-token", enabled = true),
            debuggable = true
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `上传是原始字节 POST v1 media 且 query 带 mime kind personaId`() = runBlocking {
        server.respondJson(
            """{"id":"media-1","kind":"CHAT_IMAGE","mime":"image/jpeg","bytes":9,
               "sha256":"abc","url":"/v1/media/media-1"}"""
        )

        val media = api.uploadMedia(
            bytes = "JPEG-DATA".toByteArray(),
            mime = "image/jpeg",
            kind = ServerMediaKinds.CHAT_IMAGE,
            personaId = "p1"
        )

        assertEquals("media-1", media.id)
        assertEquals("/v1/media/media-1", media.url)
        assertEquals("image/jpeg", media.mime)
        assertEquals(9L, media.bytes)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/media", request.path)
        assertEquals("image/jpeg", request.query["mime"])
        assertEquals("CHAT_IMAGE", request.query["kind"])
        assertEquals("p1", request.query["personaId"])
        assertEquals("Bearer test-token", request.headers["authorization"])
        // body 必须是原始字节，不是 JSON/base64/multipart。
        assertEquals("JPEG-DATA", request.body)
        assertEquals("image/jpeg", request.headers["content-type"])
    }

    @Test
    fun `服务端无 envelope 的六键响应也能解析 且 url 缺失时用 id 拼`() = runBlocking {
        server.respondJson(
            """{"id":"media-2","kind":"PERSONA_AVATAR","mime":"image/png","bytes":3,"sha256":"def"}"""
        )

        val media = api.uploadMedia(byteArrayOf(1, 2, 3), "image/png", ServerMediaKinds.PERSONA_AVATAR)

        assertEquals("media-2", media.id)
        assertEquals("/v1/media/media-2", media.url)
        val request = server.requests.single()
        assertEquals("image/png", request.headers["content-type"])
        // personaId 未传时不应出现空 query。
        assertTrue("personaId 可选，不该拼空值", !request.query.containsKey("personaId"))
    }

    @Test
    fun `超过 2 MiB 在本地就拒绝 不发请求`() {
        val tooLarge = ByteArray(ServerMediaLimits.MAX_UPLOAD_BYTES + 1)

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking {
                api.uploadMedia(tooLarge, "image/jpeg", ServerMediaKinds.CHAT_IMAGE)
            }
        }

        assertEquals("media_too_large", error.code)
        assertTrue("超限不该把 2MB body 发出去", server.requests.isEmpty())
    }

    @Test
    fun `空字节被拒绝 不发请求`() {
        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.uploadMedia(ByteArray(0), "image/jpeg", ServerMediaKinds.CHAT_IMAGE) }
        }

        assertEquals("media_empty", error.code)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `413 content_too_large 映射成服务端 code 与 status`() = runBlocking {
        server.respondJson(
            """{"error":{"message":"单文件最大 2 MiB","code":"content_too_large"}}""",
            status = 413
        )

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.uploadMedia(byteArrayOf(1), "image/jpeg", ServerMediaKinds.CHAT_IMAGE) }
        }

        assertEquals(413, error.status)
        assertEquals("content_too_large", error.code)
    }

    @Test
    fun `缺 id 的媒体响应按 invalid_response 失败`() = runBlocking {
        server.respondJson("""{"url":"/v1/media/x"}""")

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.uploadMedia(byteArrayOf(1), "image/jpeg", ServerMediaKinds.CHAT_IMAGE) }
        }

        assertEquals("invalid_response", error.code)
    }

    @Test
    fun `下载走 GET v1 media id 并带 Bearer`() = runBlocking {
        server.respondJson("""{"ok":true}""")

        // 下载内容本身是图片字节；这里只验路径/鉴权（假服务端把响应当文本回）。
        val bytes = runCatching { api.downloadMedia("/v1/media/media-9") }

        assertTrue(bytes.isSuccess)
        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/media/media-9", request.path)
        assertEquals("Bearer test-token", request.headers["authorization"])
    }
}
