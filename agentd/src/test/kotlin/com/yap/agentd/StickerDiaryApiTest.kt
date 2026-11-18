package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaKind
import com.yap.agentd.store.MediaMime
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.Base64

/**
 * 表情包与日记板的 HTTP 形状（`spec/stickers`、`spec/diary`）。
 *
 * 与 `client-sync-only` 约定的契约就钉在这里：根 key（`stickers` / `notes`）、
 * 行字段名、`POST /v1/stickers` 的「一条请求带 base64」、`DELETE` 的 404 语义、
 * 日记的 `remindAt`/`remindInMinutes` 二选一。
 */
class StickerDiaryApiTest {

    private lateinit var root: Path
    private val clock = TestSupport.FakeClock(1_700_000_000_000L)
    private lateinit var store: FileStore
    private lateinit var services: AgentdServices

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-sticker-diary")
        clock.now = 1_700_000_000_000L
        store = FileStore.open(root, clock.reader)
        services = AgentdServices(store = store)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun call(method: String, path: String, body: String? = null): AgentdApi.Response =
        AuthorityRoutes.handle(services, method, path, body)

    private fun json(response: AgentdApi.Response): JsonValue.Obj {
        assertEquals(response.body, 200, response.status)
        return TestSupport.parse(response.body)
    }

    private fun errorCode(response: AgentdApi.Response): String =
        (TestSupport.parse(response.body).obj("error").fields["code"] as JsonValue.Str).value

    private fun createSticker(personaId: String? = this.personaId, mime: String = MediaMime.GIF): String {
        val body = buildString {
            append("{\"mime\":\"").append(mime).append("\",\"width\":1,\"height\":1,\"frames\":1,")
            append("\"data\":\"").append(Base64.getEncoder().encodeToString(GIF)).append('"')
            if (personaId != null) append(",\"personaId\":\"").append(personaId).append('"')
            append('}')
        }
        val payload = json(call("POST", "/v1/stickers", body))
        assertTrue(payload.bool("created"))
        return payload.obj("sticker").str("id")
    }

    // ------------------------------------------------------------------ stickers

    @Test
    fun `create list and delete a sticker`() {
        val id = createSticker()
        val row = json(call("GET", "/v1/stickers")).objects("stickers").single()
        assertEquals(id, row.str("id"))
        assertEquals("裸文件名与 file 同义", row.str("file"), row.str("fileName"))
        assertEquals(id, row.str("mediaId"))
        assertEquals("/v1/media/$id", row.str("url"))
        assertEquals(MediaMime.GIF, row.str("mime"))
        assertEquals("IMPORTED", row.str("source"))
        assertEquals("USER", row.str("createdBy"))
        assertEquals(1, row.int("frames"))
        assertEquals("种子人格下可见", personaId, row.str("personaId"))

        // 字节真的落盘了（下载接口同一份文件）。
        assertEquals(GIF.toList(), store.stickerBytes(id)!!.toList())

        assertEquals(true, json(call("DELETE", "/v1/stickers/$id")).bool("deleted"))
        assertTrue(json(call("GET", "/v1/stickers")).objects("stickers").isEmpty())
        assertEquals(404, call("DELETE", "/v1/stickers/$id").status)
        assertEquals("not_found", errorCode(call("DELETE", "/v1/stickers/$id")))
    }

    @Test
    fun `list supports personaId filter and limit with newest first`() {
        val first = createSticker()
        clock.advance(10)
        val second = createSticker()
        val none = createSticker(personaId = FileStore.SEED_PERSONA_2_ID)

        val forPersona = json(call("GET", "/v1/stickers?personaId=$personaId")).objects("stickers")
        assertEquals(listOf(second, first), forPersona.map { it.str("id") })
        assertFalse(forPersona.any { it.str("id") == none })

        val limited = json(call("GET", "/v1/stickers?limit=1")).objects("stickers")
        assertEquals(listOf(none), limited.map { it.str("id") })
    }

    @Test
    fun `create accepts an already uploaded media id`() {
        val media = store.saveMedia(MediaKind.STICKER, personaId, MediaMime.GIF, GIF)
        val payload = json(call("POST", "/v1/stickers", """{"mediaId":"${media.item.id}"}"""))
        val row = payload.obj("sticker")
        assertEquals(media.item.id, row.str("id"))
        assertEquals(media.item.id, row.str("file").substringBefore('.'))
        assertEquals(1, json(call("GET", "/v1/stickers")).objects("stickers").size)
    }

    @Test
    fun `create validates mime size and base64`() {
        val badMime = call(
            "POST",
            "/v1/stickers",
            """{"mime":"image/jpeg","data":"${Base64.getEncoder().encodeToString(GIF)}"}"""
        )
        assertEquals(400, badMime.status)
        assertEquals("invalid_argument", errorCode(badMime))

        val badBase64 = call("POST", "/v1/stickers", """{"mime":"image/gif","data":"!!!not-base64!!!"}""")
        assertEquals(400, badBase64.status)

        // inline base64 受 JSON 单字段上限约束（约 192 KiB 文件）；更大的走 /v1/media。
        val tooLarge = call("POST", "/v1/stickers", """{"mime":"image/gif","data":"${"A".repeat(261_500)}"}""")
        assertEquals(413, tooLarge.status)
        assertTrue("要告诉客户端改走 /v1/media", tooLarge.body.contains("/v1/media"))

        val emptyData = call("POST", "/v1/stickers", """{"mime":"image/gif"}""")
        assertEquals(400, emptyData.status)

        val unknownPersona = call(
            "POST",
            "/v1/stickers",
            """{"personaId":"nope","mime":"image/gif","data":"${Base64.getEncoder().encodeToString(GIF)}"}"""
        )
        assertEquals(404, unknownPersona.status)
        assertTrue("失败的请求不能留下表情", store.stickers().isEmpty())
    }

    @Test
    fun `list rejects an invalid personaId and unknown methods`() {
        assertEquals(400, call("GET", "/v1/stickers?personaId=../etc").status)
        assertEquals(405, call("PUT", "/v1/stickers").status)
    }

    // ------------------------------------------------------------------ diary

    @Test
    fun `diary create list update and delete`() {
        val created = json(
            call("POST", "/v1/personas/$personaId/diary", """{"content":"记得周四给妈妈打电话","remindInMinutes":30}""")
        ).obj("note")
        val noteId = created.str("id")
        assertEquals("记得周四给妈妈打电话", created.str("content"))
        assertEquals(clock.now + 30 * 60_000L, created.long("remindAt"))
        assertNull("新建时还没提醒过", created.optLong("remindedAt"))

        val listed = json(call("GET", "/v1/personas/$personaId/diary")).objects("notes").single()
        assertEquals(noteId, listed.str("id"))
        assertEquals(7, listed.fields.size)

        // 更新正文不会清掉已提醒标记（提醒时间没变）。
        store.markDiaryReminded(noteId, clock.now)
        val updated = json(
            call("POST", "/v1/personas/$personaId/diary", """{"id":"$noteId","content":"记得周五给妈妈打电话"}""")
        ).obj("note")
        assertEquals("记得周五给妈妈打电话", updated.str("content"))
        assertNotNull("提醒时间没变，不该清 remindedAt", updated.optLong("remindedAt"))

        // remindAt: null 清掉提醒。
        val cleared = json(
            call("POST", "/v1/personas/$personaId/diary", """{"id":"$noteId","remindAt":null}""")
        ).obj("note")
        assertNull(cleared.optLong("remindAt"))

        assertEquals(true, json(call("DELETE", "/v1/personas/$personaId/diary/$noteId")).bool("deleted"))
        assertEquals(404, call("DELETE", "/v1/personas/$personaId/diary/$noteId").status)
        assertTrue(store.diaryNotes(personaId).isEmpty())
    }

    @Test
    fun `diary rejects bad payloads and unknown ids`() {
        assertEquals(400, call("POST", "/v1/personas/$personaId/diary", """{}""").status)
        assertEquals(
            "内容上限 2000 字",
            400,
            call("POST", "/v1/personas/$personaId/diary", """{"content":"${"字".repeat(2001)}"}""").status
        )
        assertEquals(
            "remindInMinutes 上限 30 天",
            400,
            call("POST", "/v1/personas/$personaId/diary", """{"content":"x","remindInMinutes":999999}""").status
        )
        assertEquals(
            "带 id 但不存在 → 404",
            404,
            call("POST", "/v1/personas/$personaId/diary", """{"id":"missing","content":"x"}""").status
        )
        assertEquals(404, call("GET", "/v1/personas/nope/diary").status)
        assertEquals(405, call("PUT", "/v1/personas/$personaId/diary").status)
    }

    @Test
    fun `diary list supports limit and since and isolates personas`() {
        call("POST", "/v1/personas/$personaId/diary", """{"content":"第一条"}""")
        clock.advance(10)
        call("POST", "/v1/personas/$personaId/diary", """{"content":"第二条"}""")
        call(
            "POST",
            "/v1/personas/${FileStore.SEED_PERSONA_2_ID}/diary",
            """{"content":"别人格的"}"""
        )

        val all = json(call("GET", "/v1/personas/$personaId/diary")).objects("notes")
        assertEquals(listOf("第二条", "第一条"), all.map { it.str("content") })
        val limited = json(call("GET", "/v1/personas/$personaId/diary?limit=1")).objects("notes")
        assertEquals(listOf("第二条"), limited.map { it.str("content") })
        val since = json(call("GET", "/v1/personas/$personaId/diary?since=${clock.now - 5}")).objects("notes")
        assertEquals(listOf("第二条"), since.map { it.str("content") })
    }

    @Test
    fun `diary notes survive a restart`() {
        call("POST", "/v1/personas/$personaId/diary", """{"content":"重启之后还在"}""")
        val reopened = FileStore.open(root, clock.reader)
        assertEquals("重启之后还在", reopened.diaryNotes(personaId).single().content)
    }

    @Test
    fun `stickers survive a restart`() {
        val id = createSticker()
        val reopened = FileStore.open(root, clock.reader)
        assertEquals(id, reopened.stickers().single().id)
        assertEquals(GIF.toList(), reopened.stickerBytes(id)!!.toList())
    }

    private companion object {
        val GIF: ByteArray = Base64.getDecoder()
            .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    }
}
