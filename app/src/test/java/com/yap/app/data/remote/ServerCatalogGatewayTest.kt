package com.yap.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 服务端表情 / 日记**写路径**的网关语义（0.13）。
 *
 * 与人格/记忆同一条纪律：服务端成功才叫保存成功、删除 404 当「已经没了」；
 * 本地模式下不发任何请求（不能因为服务端开关关着就把表情存坏）。
 */
class ServerCatalogGatewayTest {

    private class FakeProvider(var api: ServerApi?, var enabled: Boolean) : ServerApiProvider {
        override fun api(): ServerApi? = api
        override fun isEnabled(): Boolean = enabled
    }

    private lateinit var server: FakeHttpTestServer
    private lateinit var provider: FakeProvider
    private lateinit var gateway: ServerWriteGateway

    @Before
    fun setUp() {
        server = FakeHttpTestServer()
        val api = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, token = "t", enabled = true),
            debuggable = true
        )
        provider = FakeProvider(api = api, enabled = true)
        gateway = ServerWriteGateway(provider, TestServerLocalStore())
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `新建表情成功返回服务端行`() = runBlocking {
        server.respondJson(
            """{"created":true,"sticker":{"id":"stk_1","file":"stk_1.gif","url":"/v1/media/stk_1",
               "mime":"image/gif","width":8,"height":8,"frames":1,"bytes":2,
               "source":"IMPORTED","createdBy":"USER","createdAt":3}}"""
        )

        val result = gateway.createSticker(buildJsonObject { put("mime", "image/gif"); put("data", "AA==") })

        assertTrue(result.isSuccess)
        assertEquals("stk_1", result.getOrThrow().localId())
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/stickers", request.path)
    }

    @Test
    fun `大表情先传媒体再建行`() = runBlocking {
        server.respondJson(
            """{"media":{"id":"media_big","url":"/v1/media/media_big","kind":"STICKER",
               "mime":"image/gif","bytes":4}}"""
        )

        val mediaId = gateway.uploadStickerMedia(byteArrayOf(1, 2, 3, 4), personaId = "p1").getOrThrow()

        assertEquals("media_big", mediaId)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/media", request.path)
        assertEquals("STICKER", request.query["kind"])
        assertEquals("image/gif", request.query["mime"])
        assertEquals("p1", request.query["personaId"])
    }

    @Test
    fun `新建表情服务端丢行时是失败而不是假装成功`() = runBlocking {
        server.respondJson("""{"created":false}""")
        assertTrue(gateway.createSticker(buildJsonObject { put("data", "AA==") }).isFailure)
    }

    @Test
    fun `删表情 404 视为已经删掉`() = runBlocking {
        server.respondJson("""{"error":{"code":"not_found","message":"没有这个表情"}}""", status = 404)
        assertTrue(gateway.deleteSticker("stk_1").isSuccess)
    }

    @Test
    fun `新建日记走人格下的 diary 端点`() = runBlocking {
        server.respondJson(
            """{"note":{"id":"n1","personaId":"p1","content":"记得","createdAt":1,
               "remindAt":null,"remindedAt":null,"updatedAt":1}}"""
        )

        val result = gateway.saveDiary("p1", buildJsonObject { put("content", "记得") })

        assertTrue(result.isSuccess)
        assertEquals("n1", result.getOrThrow().id)
        assertEquals("/v1/personas/p1/diary", server.requests.single().path)
    }

    @Test
    fun `删日记 404 视为已经删掉`() = runBlocking {
        server.respondJson("""{"error":{"code":"not_found"}}""", status = 404)
        assertTrue(gateway.deleteDiary("p1", "n1").isSuccess)
        assertEquals("/v1/personas/p1/diary/n1", server.requests.single().path)
    }

    @Test
    fun `本地模式表情与日记写路径一个请求都不发`() = runBlocking {
        provider.enabled = false
        provider.api = null

        assertFalse(gateway.isEnabled())
        assertTrue(gateway.createSticker(buildJsonObject { }).isFailure)
        assertTrue(gateway.deleteSticker("stk_1").isFailure)
        assertTrue(gateway.saveDiary("p1", buildJsonObject { put("content", "x") }).isFailure)
        assertTrue(gateway.deleteDiary("p1", "n1").isFailure)
        assertTrue(gateway.stickers().isFailure)
        assertTrue(gateway.diary("p1").isFailure)
        assertTrue(server.requests.isEmpty())
    }
}
