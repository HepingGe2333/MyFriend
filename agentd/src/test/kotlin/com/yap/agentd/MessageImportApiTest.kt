package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaKind
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * `POST /v1/sessions/{id}/messages/import`（0.12.1：客户端切到远程时全量推送本地历史）。
 *
 * 覆盖任务要求的 8 个点：
 * 1. 正常导入（乱序也能落到正确块，块内按 `(createdAt, id)` 有序）；
 * 2. 幂等（同 id 跳过、不覆盖、不改 readAt）；
 * 3. session 不存在 → 404；
 * 4. 单批 > 200 → 400 `too_many_messages`；
 * 5. 坏 role → 400，且整批一条不落；
 * 6. 附件不存在 → 400 `unknown_media`；
 * 7. 重启后仍在（重新打开数据目录）；
 * 8. 与分页接口一致（翻完所有块拿到的顺序 = 导入顺序）。
 *
 * 另外钉住几条容易回归的语义：空数组、批量导入不写 `unread`（历史消息不该制造未读）、
 * 已有消息的 `blockIndex`/内容不被改动、请求体上限（8 MiB）。
 */
class MessageImportApiTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)
    private val extraRoots = mutableListOf<Path>()

    private val sessionId: String get() = FileStore.SEED_SESSION_ID
    private val importPath: String get() = "/v1/sessions/$sessionId/messages/import"

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-import-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
        extraRoots.forEach { TestSupport.deleteRecursively(it) }
    }

    // ------------------------------------------------------------------ 小工具

    private fun call(
        target: FileStore,
        method: String,
        path: String,
        body: String? = null
    ): AgentdApi.Response = AuthorityRoutes.handle(AgentdServices(target), method, path, body)

    private fun json(response: AgentdApi.Response): JsonValue.Obj = TestSupport.parse(response.body)

    private fun errorCode(response: AgentdApi.Response): String = json(response).obj("error").str("code")

    private fun importBody(vararg messages: String): String =
        """{"messages":[${messages.joinToString(",")}]}"""

    private fun message(
        id: String,
        role: String = "USER",
        content: String = "内容-$id",
        createdAt: Long,
        readAt: Long? = null,
        attachments: List<String> = emptyList(),
        source: String? = null,
        clientMessageId: String? = null,
        replyTo: String? = null
    ): String = buildString {
        append("""{"id":""").append(quote(id))
        append(""","role":""").append(quote(role))
        append(""","content":""").append(quote(content))
        append(""","createdAt":""").append(createdAt)
        if (readAt != null) append(""","readAt":""").append(readAt)
        if (attachments.isNotEmpty()) {
            append(""","attachments":[""")
            append(attachments.joinToString(",") { quote(it) })
            append("]")
        }
        if (source != null) append(""","source":""").append(quote(source))
        if (clientMessageId != null) append(""","clientMessageId":""").append(quote(clientMessageId))
        if (replyTo != null) append(""","replyTo":""").append(quote(replyTo))
        append("}")
    }

    private fun quote(text: String): String =
        com.yap.app.core.json.Json.stringify(JsonValue.Str(text))

    private fun uploadMedia(bytes: ByteArray = "image-bytes".toByteArray()): String {
        val saved = store.saveMedia(MediaKind.CHAT_IMAGE, null, MediaMime.JPEG, bytes)
        return saved.item.url
    }

    private fun page(blockIndex: Int? = null): JsonValue.Obj {
        val path = if (blockIndex == null) {
            "/v1/sessions/$sessionId/messages"
        } else {
            "/v1/sessions/$sessionId/messages?beforeBlock=$blockIndex"
        }
        val response = call(store, "GET", path)
        assertEquals(response.body, 200, response.status)
        return json(response)
    }

    /** 翻完所有块，按从旧到新返回消息（分页接口的真相）。 */
    private fun allMessagesViaPagination(): List<JsonValue.Obj> {
        val pages = ArrayList<List<JsonValue.Obj>>()
        var current = page()
        while (true) {
            pages.add(current.objects("messages"))
            val hasMoreOlder = current.bool("hasMoreOlder")
            if (!hasMoreOlder) break
            val blockIndex = current.fields["blockIndex"] as? JsonValue.Num
                ?: throw AssertionError("有更早的块却说 blockIndex=null")
            current = page(blockIndex.asInt)
        }
        return pages.reversed().flatten()
    }

    // ------------------------------------------------------------------ 1. 正常导入

    @Test
    fun `import appends out of order history into the right blocks`() {
        // 服务端已经有一条更新的消息：导入的历史应该插在它前面，而不是覆盖它。
        val existing = store.appendMessage(sessionId, MessageRole.USER, "服务端已有的消息")
        clock.advance(10_000)

        val response = call(
            store,
            "POST",
            importPath,
            importBody(
                message("m-late", content = "第三句", createdAt = 5_000),
                message("m-early", content = "第一句", createdAt = 1_000),
                message("m-mid", content = "第二句", createdAt = 3_000)
            )
        )
        assertEquals(response.body, 200, response.status)
        val payload = json(response)
        assertEquals(3, payload.int("imported"))
        assertEquals(0, payload.int("skipped"))
        assertEquals(0, payload.obj("blockRange").int("first"))
        assertEquals(0, payload.obj("blockRange").int("last"))
        assertEquals(4L, payload.long("messageCount"))

        val messages = allMessagesViaPagination()
        assertEquals(
            listOf("第一句", "服务端已有的消息", "第二句", "第三句"),
            messages.map { it.str("content") }
        )
        // 客户端自带 id 必须原样保留（不生成新 id）。
        assertEquals(listOf("m-early", existing.id, "m-mid", "m-late"), messages.map { it.str("id") })
        assertEquals(sessionId, messages.first().str("sessionId"))
    }

    @Test
    fun `import keeps readAt and does not turn history into unread messages`() {
        val before = store.session(sessionId)!!.unread
        val response = call(
            store,
            "POST",
            importPath,
            importBody(
                message("a1", role = "USER", createdAt = 1_000),
                message("a2", role = "ASSISTANT", createdAt = 2_000, readAt = 1_500),
                message("a3", role = "assistant", createdAt = 3_000) // 小写 role 也接受
            )
        )
        assertEquals(response.body, 200, response.status)
        val imported = allMessagesViaPagination().associateBy { it.str("id") }
        assertEquals(1_500L, imported.getValue("a2").long("readAt"))
        assertNull("readAt 缺省 = 未读（null）", imported.getValue("a3").optLong("readAt"))
        assertEquals("批量导入不写 unread", before, store.session(sessionId)!!.unread)
        assertEquals(
            "role 统一存大写",
            listOf("USER", "ASSISTANT", "ASSISTANT"),
            listOf("a1", "a2", "a3").map { imported.getValue(it).str("role") }
        )
        assertEquals("IMPORT", imported.getValue("a2").str("source"))
        assertEquals(3, store.session(sessionId)!!.messageCount)
        assertEquals(3_000L, store.session(sessionId)!!.lastMessageAt)
    }

    @Test
    fun `import accepts attachments client message ids and source`() {
        val url = uploadMedia()
        val response = call(
            store,
            "POST",
            importPath,
            importBody(
                message(
                    "att-1",
                    createdAt = 1_000,
                    attachments = listOf(url.removePrefix("/v1/media/")), // 裸 id 也接受
                    source = "CHAT",
                    clientMessageId = "local-1",
                    replyTo = "parent-1"
                )
            )
        )
        assertEquals(response.body, 200, response.status)
        val stored = allMessagesViaPagination().single()
        assertEquals(listOf(url), stored.strings("attachments"))
        assertEquals("CHAT", stored.str("source"))
        assertEquals("local-1", stored.str("clientMessageId"))
        assertEquals("parent-1", stored.str("replyTo"))
        // 带附件的历史消息 → 媒体引用统计仍然生效（删除接口要能看见它）。
        assertTrue(store.mediaReferenceCount(url.removePrefix("/v1/media/")) >= 1)
    }

    // ------------------------------------------------------------------ 2. 幂等

    @Test
    fun `import is idempotent by message id and never overwrites existing messages`() {
        val body = importBody(message("dup-1", content = "原始内容", createdAt = 1_000))
        assertEquals(200, call(store, "POST", importPath, body).status)

        // 同一个 id 再来一次、内容不同：跳过，不覆盖。
        val again = call(
            store,
            "POST",
            importPath,
            importBody(message("dup-1", content = "被改过的内容", createdAt = 2_000))
        )
        assertEquals(again.body, 200, again.status)
        assertEquals(0, json(again).int("imported"))
        assertEquals(1, json(again).int("skipped"))
        assertNull("没有新写入时 blockRange 是 null", json(again).optObj("blockRange"))
        assertEquals(1, store.session(sessionId)!!.messageCount)
        assertEquals("原始内容", allMessagesViaPagination().single().str("content"))
    }

    @Test
    fun `import rejects duplicate ids inside one batch`() {
        val response = call(
            store,
            "POST",
            importPath,
            importBody(
                message("same", createdAt = 1_000),
                message("same", createdAt = 2_000)
            )
        )
        assertEquals(400, response.status)
        assertEquals("invalid_argument", errorCode(response))
        assertEquals(0, store.session(sessionId)!!.messageCount)
    }

    // ------------------------------------------------------------------ 3–6. 校验

    @Test
    fun `import requires an existing session`() {
        val response = call(
            store,
            "POST",
            "/v1/sessions/no-such-session/messages/import",
            importBody(message("x", createdAt = 1_000))
        )
        assertEquals(404, response.status)
        assertEquals("not_found", errorCode(response))
    }

    @Test
    fun `import rejects more than two hundred messages per batch`() {
        val messages = (1..201).map { index -> message("cap-$index", createdAt = index.toLong()) }
        val response = call(store, "POST", importPath, importBody(*messages.toTypedArray()))
        assertEquals(400, response.status)
        assertEquals("too_many_messages", errorCode(response))
        assertEquals("整批拒绝，一条都不写", 0, store.session(sessionId)!!.messageCount)
    }

    @Test
    fun `exactly two hundred messages are accepted`() {
        val messages = (1..200).map { index -> message("ok-$index", createdAt = index.toLong()) }
        val response = call(store, "POST", importPath, importBody(*messages.toTypedArray()))
        assertEquals(response.body, 200, response.status)
        assertEquals(200, json(response).int("imported"))
        assertEquals(200, store.session(sessionId)!!.messageCount)
    }

    @Test
    fun `import rejects a bad role and writes nothing`() {
        val response = call(
            store,
            "POST",
            importPath,
            importBody(
                message("good", createdAt = 1_000),
                message("bad", role = "ROBOT", createdAt = 2_000)
            )
        )
        assertEquals(400, response.status)
        assertEquals("invalid_argument", errorCode(response))
        assertEquals("已校验的合法消息也不能落库", 0, store.session(sessionId)!!.messageCount)
    }

    @Test
    fun `import rejects illegal ids and missing required fields`() {
        assertEquals(400, call(store, "POST", importPath, """{"messages":[{}]}""").status)
        assertEquals(
            "missing_field",
            errorCode(call(store, "POST", importPath, """{"messages":[{"role":"USER"}]}"""))
        )
        assertEquals(
            "invalid_argument",
            errorCode(
                call(
                    store,
                    "POST",
                    importPath,
                    importBody(message("bad id with spaces", createdAt = 1_000))
                )
            )
        )
        assertEquals(
            "missing_field",
            errorCode(
                call(
                    store,
                    "POST",
                    importPath,
                    """{"messages":[{"id":"x","role":"USER","content":"c"}]}"""
                )
            )
        )
        assertEquals(400, call(store, "POST", importPath, """{"messages":{}}""").status)
        assertEquals(400, call(store, "POST", importPath, """{"nope":[]}""").status)
        assertEquals(0, store.session(sessionId)!!.messageCount)
    }

    @Test
    fun `import rejects attachments that do not exist`() {
        val response = call(
            store,
            "POST",
            importPath,
            importBody(
                message(
                    "with-missing-media",
                    createdAt = 1_000,
                    attachments = listOf("/v1/media/${UUID.randomUUID()}")
                )
            )
        )
        assertEquals(400, response.status)
        assertEquals("unknown_media", errorCode(response))
        assertEquals(0, store.session(sessionId)!!.messageCount)
    }

    @Test
    fun `empty messages array is a successful no-op`() {
        val response = call(store, "POST", importPath, """{"messages":[]}""")
        assertEquals(response.body, 200, response.status)
        assertEquals(0, json(response).int("imported"))
        assertEquals(0, json(response).int("skipped"))
        assertNull(json(response).optObj("blockRange"))
        assertEquals(0, store.session(sessionId)!!.messageCount)
    }

    @Test
    fun `import body above eight mebibytes is rejected with 413`() {
        // 8 MiB 上限：造一个略超的合法 JSON（内容是一条超长消息）。
        val padding = "x".repeat(8 * 1024 * 1024)
        val body = importBody(message("huge", content = padding, createdAt = 1_000))
        val response = call(store, "POST", importPath, body)
        assertEquals(413, response.status)
        assertEquals("content_too_large", errorCode(response))
        assertEquals(0, store.session(sessionId)!!.messageCount)
    }

    // ------------------------------------------------------------------ 7. 重启

    @Test
    fun `imported history survives a restart`() {
        val response = call(
            store,
            "POST",
            importPath,
            importBody(
                message("r1", content = "第一句", createdAt = 1_000),
                message("r2", role = "ASSISTANT", content = "第二句", createdAt = 2_000)
            )
        )
        assertEquals(response.body, 200, response.status)

        val reopened = FileStore.open(root, clock.reader)
        val messages = reopened.blockMessages(sessionId, 0)
        assertEquals(listOf("第一句", "第二句"), messages.map(Message::content))
        assertEquals(2, reopened.session(sessionId)!!.messageCount)
        assertEquals(2_000L, reopened.session(sessionId)!!.lastMessageAt)
        // 重启后分页也能读到（块表与块文件一致）。
        val page = AuthorityRoutes.handle(AgentdServices(reopened), "GET", "/v1/sessions/$sessionId/messages", null)
        val payload = TestSupport.parse(page.body)
        assertEquals(2, payload.objects("messages").size)
        assertEquals(0, payload.int("blockIndex"))
        assertFalse(payload.bool("hasMoreOlder"))
    }

    // ------------------------------------------------------------------ 8. 与分页一致

    @Test
    fun `import stays consistent with block pagination and block splitting`() {
        // 450 条 = 3 块（200/200/50），分 3 批推上去（客户端就是这样分批的）。
        val ids = (1..450).map { index -> "p-$index" }
        for (batch in ids.chunked(200)) {
            val messages = batch.mapIndexed { offset, id ->
                val createdAt = 1_000L + (ids.indexOf(id) * 1_000L) + offset * 0
                message(id, createdAt = createdAt)
            }
            val response = call(store, "POST", importPath, importBody(*messages.toTypedArray()))
            assertEquals(response.body, 200, response.status)
        }
        val blocks = store.blocks(sessionId)
        assertEquals(listOf(0, 1, 2), blocks.map { it.index })
        assertEquals(listOf(200, 200, 50), blocks.map { it.count })

        val paged = allMessagesViaPagination()
        assertEquals(450, paged.size)
        assertEquals(ids, paged.map { it.str("id") })
        assertEquals(450, store.session(sessionId)!!.messageCount)
        // 块内有序：每块内部 createdAt 单调。
        for (blockIndex in listOf(0, 1, 2)) {
            val page = page(blockIndex + 1)
            val times = page.objects("messages").map { it.long("createdAt") }
            assertEquals("块 $blockIndex 内要按时间升序", times.sorted(), times)
        }
    }

    @Test
    fun `import leaves existing messages untouched when inserting older history`() {
        // 造已有历史：3 条（createdAt = 1000/2000/3000）。
        repeat(3) { index ->
            clock.now = 1_000L + index * 1_000L
            store.appendMessage(sessionId, MessageRole.USER, "已有-$index")
        }
        val before = store.blockMessages(sessionId, 0)
        assertEquals(3, before.size)

        // 插入两条更早的（500/1500）：只应添加，不应改动已有消息。
        val response = call(
            store,
            "POST",
            importPath,
            importBody(
                message("older-1", content = "更早", createdAt = 500),
                message("older-2", content = "中间", createdAt = 1_500)
            )
        )
        assertEquals(response.body, 200, response.status)

        val after = store.blockMessages(sessionId, 0)
        assertEquals(5, after.size)
        assertEquals(
            listOf("更早", "已有-0", "中间", "已有-1", "已有-2"),
            after.map(Message::content)
        )
        // 已有消息的每个字段（含 blockIndex）都不变。
        val unchanged = after.filter { it.id in before.map(Message::id) }
        assertEquals(before.map { it.copy(blockIndex = 0) }, unchanged)
    }

    @Test
    fun `message import path only accepts post`() {
        val response = call(store, "GET", importPath)
        assertEquals(405, response.status)
        assertEquals("method_not_allowed", errorCode(response))
    }

    @Test
    fun `import does not write unread nor emit events`() {
        val cursorBefore = store.cursor()
        val unreadBefore = store.session(sessionId)!!.unread
        val response = call(
            store,
            "POST",
            importPath,
            importBody(
                message("e1", role = "ASSISTANT", createdAt = 1_000),
                message("e2", role = "USER", createdAt = 2_000)
            )
        )
        assertEquals(response.body, 200, response.status)
        assertEquals("历史消息不产生事件（客户端自己拉历史）", cursorBefore, store.cursor())
        assertEquals("历史消息不制造未读", unreadBefore, store.session(sessionId)!!.unread)
        assertNotNull(store.session(sessionId))
    }

    @Test
    fun `import rejects an illegal session id in the path`() {
        // 非法 id 是「参数不合法」（400），不是「会话不存在」（404）：它根本不可能是一个会话。
        val response = call(store, "POST", "/v1/sessions/bad..id/messages/import", importBody())
        assertEquals(400, response.status)
        assertEquals("invalid_argument", errorCode(response))
        assertFalse(Files.exists(root.resolve("sessions").resolve("bad..id")))
    }
}
