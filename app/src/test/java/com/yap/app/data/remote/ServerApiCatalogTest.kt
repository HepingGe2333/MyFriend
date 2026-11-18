package com.yap.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 表情包 / 日记板的传输层契约单测（0.13）。
 *
 * 钉住的是「客户端与服务端逐字对齐」的部分：
 * 1. 路径与方法（`/v1/stickers`、`/v1/personas/{id}/diary/{noteId}`）；
 * 2. 请求体字段（表情的 base64 / 尺寸 / 来源；日记的 content / remindAt）；
 * 3. 响应解析（根 key `stickers` / `notes`，行字段缺省不炸）；
 * 4. 404 与坏响应按既有 [ServerApiException] 语义上报。
 */
class ServerApiCatalogTest {

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

    // ------------------------------------------------------------------ 表情

    @Test
    fun `GET stickers 走正确路径并解析行`() = runBlocking {
        server.respondJson(
            """
            {"serverTime":1794500000000,"stickers":[
              {"id":"stk_1","personaId":"p1","file":"stk_1.gif","mediaId":"stk_1",
               "url":"/v1/media/stk_1","mime":"image/gif","width":320,"height":320,
               "frames":12,"bytes":40123,"source":"IMPORTED","createdBy":"USER",
               "createdAt":1794500000000}
            ]}
            """.trimIndent()
        )

        val rows = api.stickers(limit = 100)

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("stk_1", row.id)
        assertEquals("/v1/media/stk_1", row.mediaRef())
        assertEquals("stk_1", row.localId())
        assertEquals(320, row.width)
        assertEquals(12, row.frames)
        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/stickers", request.path)
        assertEquals("100", request.query["limit"])
        assertEquals("Bearer test-token", request.headers["authorization"])
    }

    @Test
    fun `GET stickers 没有根对象时当空列表`() = runBlocking {
        server.respondJson("""{"serverTime":1}""")
        assertTrue(api.stickers().isEmpty())
    }

    @Test
    fun `GET stickers 支持 personaId 过滤`() = runBlocking {
        server.respondJson("""{"stickers":[]}""")
        api.stickers(personaId = "p1")
        val request = server.requests.single()
        assertEquals("p1", request.query["personaId"])
    }

    @Test
    fun `POST stickers 一条请求带元数据与 base64`() = runBlocking {
        server.respondJson(
            """{"created":true,"sticker":{"id":"stk_9","file":"stk_9.gif","mediaId":"stk_9",
               "url":"/v1/media/stk_9","mime":"image/gif","width":10,"height":10,"frames":1,
               "bytes":3,"source":"IMPORTED","createdBy":"USER","createdAt":7}}"""
        )
        val payload = buildJsonObject {
            put("mime", "image/gif")
            put("data", "R0lGODlh")
            put("width", 10)
            put("height", 10)
            put("frames", 1)
            put("source", "IMPORTED")
            put("createdBy", "USER")
        }

        val created = api.createSticker(payload)

        assertNotNull(created)
        assertEquals("stk_9", created!!.localId())
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/stickers", request.path)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body) as JsonObject
        assertEquals("R0lGODlh", body["data"]?.jsonPrimitive?.content)
        assertEquals("image/gif", body["mime"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST stickers 响应缺少 sticker 时返回 null`() = runBlocking {
        server.respondJson("""{"created":false}""")
        assertNull(api.createSticker(JsonObject(emptyMap())))
    }

    @Test
    fun `DELETE stickers 走正确路径`() = runBlocking {
        server.respondJson("""{"deleted":true,"id":"stk_1"}""")

        api.deleteSticker("stk_1")

        val request = server.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/stickers/stk_1", request.path)
    }

    @Test
    fun `DELETE stickers 404 抛出可识别的错误`() = runBlocking {
        server.respondJson("""{"error":{"message":"没有这个表情","code":"not_found"}}""", status = 404)
        val error = runCatching { api.deleteSticker("gone") }.exceptionOrNull()
        assertTrue(error is ServerApiException)
        assertEquals(404, (error as ServerApiException).status)
    }

    // ------------------------------------------------------------------ 日记

    @Test
    fun `GET diary 解析 notes 根字段`() = runBlocking {
        server.respondJson(
            """
            {"personaId":"p1","notes":[
              {"id":"note-1","personaId":"p1","content":"周四给妈妈打电话",
               "createdAt":1794493200000,"remindAt":1794506400000,"remindedAt":null,
               "updatedAt":1794493200000}
            ]}
            """.trimIndent()
        )

        val notes = api.diary("p1", limit = 20)

        assertEquals(1, notes.size)
        assertEquals("note-1", notes.single().id)
        assertEquals(1794506400000L, notes.single().remindAt)
        assertNull(notes.single().remindedAt)
        val request = server.requests.single()
        assertEquals("/v1/personas/p1/diary", request.path)
        assertEquals("20", request.query["limit"])
    }

    @Test
    fun `GET diary 坏行被跳过而不是整段失败`() = runBlocking {
        server.respondJson("""{"notes":[{"id":"n1","content":"ok"},{"content":"没有 id 也照样能解"}]}""")
        val notes = api.diary("p1")
        assertEquals(2, notes.size)
        assertEquals("n1", notes.first().id)
    }

    @Test
    fun `POST diary 新建与更新同一个端点`() = runBlocking {
        server.respondJson(
            """{"note":{"id":"note-1","personaId":"p1","content":"记得","createdAt":1,
               "remindAt":null,"remindedAt":null,"updatedAt":2}}"""
        )
        val saved = api.saveDiary(
            "p1",
            buildJsonObject {
                put("content", "记得")
                put("remindAt", 123L)
            }
        )
        assertNotNull(saved)
        assertEquals("note-1", saved!!.id)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/personas/p1/diary", request.path)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body) as JsonObject
        assertEquals("记得", body["content"]?.jsonPrimitive?.content)
        assertEquals("123", body["remindAt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE diary 走人格下的 noteId`() = runBlocking {
        server.respondJson("""{"deleted":true,"id":"note-1"}""")
        api.deleteDiary("p1", "note-1")
        val request = server.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/personas/p1/diary/note-1", request.path)
    }

    @Test
    fun `limit 会夹取到服务端上限`() = runBlocking {
        server.respondJson("""{"stickers":[],"notes":[]}""")
        api.stickers(limit = 9999)
        api.diary("p1", limit = 9999)
        val stickerRequest = server.requests[0]
        val diaryRequest = server.requests[1]
        assertEquals(ServerApi.MAX_STICKER_LIMIT.toString(), stickerRequest.query["limit"])
        assertEquals(ServerApi.MAX_DIARY_LIMIT.toString(), diaryRequest.query["limit"])
    }
}
