package com.myfriend.agentd

import com.myfriend.agentd.store.ApiConfig
import com.myfriend.agentd.store.EventType
import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.MessageRole
import com.myfriend.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * 存储层测试：播种/读回、块切分与空洞、原子写、并发追加、幂等索引、事件游标、secrets 权限。
 *
 * 全部只用临时目录 + 注入时钟，不碰网络、不碰真实用户目录。
 */
class FileStoreTest {

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-store-test")
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun open(
        clock: () -> Long = { 2_000L },
        seedIfEmpty: Boolean = true
    ): FileStore = FileStore.open(root, clock, seedIfEmpty)

    private val sessionId: String get() = FileStore.SEED_SESSION_ID
    private val defaultPersona: String get() = FileStore.SEED_PERSONA_1_ID

    // ------------------------------------------------------------------ 播种与读回

    @Test
    fun `empty directory is seeded with three stable personas`() {
        val store = open()

        assertTrue("空目录应该播种", store.seeded)
        assertEquals(FileStore.SEED_PERSONA_IDS, store.personas().map { it.id })
        assertEquals(listOf("林深", "阿岚", "墨"), store.personas().map { it.name })
        assertEquals(listOf("Elias", "Finn", "Mo"), store.personas().map { it.nameEn })
        assertEquals(FileStore.SEED_PERSONA_1_ID, store.personas().first { it.isDefault }.id)

        val session = store.sessions().single()
        assertEquals(FileStore.SEED_SESSION_ID, session.id)
        assertEquals(FileStore.SEED_PERSONA_1_ID, session.personaId)
        assertEquals(-1, session.lastBlockIndex)
        assertEquals(0L, session.messageCount)
        assertEquals(0L, store.cursor())

        assertEquals("平静", store.mood(FileStore.SEED_PERSONA_1_ID)?.label)
        assertEquals("IDLE", store.presence(FileStore.SEED_PERSONA_1_ID)?.kind)

        // 文件真的落盘了（不是只活在内存里）
        for (file in listOf("meta.json", "personas.json", "sessions.json", "moods.json", "presence.json")) {
            assertTrue("$file 应该存在", Files.isRegularFile(root.resolve(file)))
        }
        assertTrue(Files.isDirectory(root.resolve(FileStore.MEDIA_DIR)))

        val meta = TestSupport.parse(Files.readString(root.resolve(FileStore.META_FILE)))
        assertEquals(FileStore.VERSION.toLong(), meta.long("version"))
        assertEquals(0L, meta.long("cursor"))
        assertTrue(meta.bool("seeded"))
    }

    @Test
    fun `reopening an existing directory reads the data back without reseeding`() {
        val first = open()
        val stored = first.appendMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = "你好",
            clientMessageId = "client-1"
        )

        val second = open()
        assertFalse("已存在的数据目录不应该再播种", second.seeded)
        assertEquals(3, second.personas().size)
        assertEquals(1, second.sessions().size)
        assertEquals(stored.id, second.blockMessages(sessionId, 0).single().id)
        assertEquals("你好", second.blockMessages(sessionId, 0).single().content)
        assertEquals(stored.id, second.findUserMessageByClientMessageId(sessionId, "client-1")?.id)
    }

    @Test
    fun `a directory with data but no meta file is rejected instead of silently reseeded`() {
        val first = open()
        first.appendMessage(sessionId, MessageRole.USER, "别丢")
        Files.delete(root.resolve(FileStore.META_FILE))

        val failure = runCatching { open() }.exceptionOrNull()
        assertTrue("应该拒绝启动，实际：$failure", failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("meta.json"))
    }

    // ------------------------------------------------------------------ 块切分

    @Test
    fun `append assigns block 0 and updates session counters`() {
        val store = open()
        val user = store.appendMessage(sessionId, MessageRole.USER, "早", clientMessageId = "c-1")
        val assistant = store.appendMessage(sessionId, MessageRole.ASSISTANT, "早呀")

        assertEquals(0, user.blockIndex)
        assertEquals(0, assistant.blockIndex)
        assertEquals("-1 表示没有块", 0, store.lastBlockIndex(sessionId))

        val session = store.session(sessionId)!!
        assertEquals(2L, session.messageCount)
        assertEquals("assistant 未读 +1", 1, session.unread)
        assertEquals(assistant.createdAt, session.lastMessageAt)

        val block = store.blocks(sessionId).single()
        assertEquals(user.id, block.firstMessageId)
        assertEquals(assistant.id, block.lastMessageId)
        assertEquals(2, block.count)
        assertEquals(user.createdAt, block.firstAt)
        assertEquals(assistant.createdAt, block.lastAt)
    }

    @Test
    fun `block splits at 200 messages and never rebalances`() {
        val store = open()
        repeat(FileStore.BLOCK_SIZE) { index ->
            store.appendMessage(sessionId, MessageRole.USER, "m$index")
        }
        assertEquals(listOf(0), store.blocks(sessionId).map { it.index })
        assertEquals(FileStore.BLOCK_SIZE, store.blocks(sessionId).single().count)

        val overflow = store.appendMessage(sessionId, MessageRole.USER, "m200")
        assertEquals(1, overflow.blockIndex)
        assertEquals(listOf(0, 1), store.blocks(sessionId).map { it.index })
        assertEquals(FileStore.BLOCK_SIZE, store.blocks(sessionId)[0].count)
        assertEquals(1, store.blocks(sessionId)[1].count)

        val firstBlock = store.blockMessages(sessionId, 0)
        assertEquals("m0", firstBlock.first().content)
        assertEquals("m199", firstBlock.last().content)
        assertEquals("m200", store.blockMessages(sessionId, 1).single().content)

        // 一块一个 jsonl 文件，行数就是块里的消息数
        val blockFile = root.resolve("sessions/$sessionId/blocks/0.jsonl")
        assertEquals(FileStore.BLOCK_SIZE, Files.readAllLines(blockFile).size)
    }

    @Test
    fun `deleting the boundary messages recomputes the block`() {
        val store = open()
        val a = store.appendMessage(sessionId, MessageRole.USER, "a")
        val b = store.appendMessage(sessionId, MessageRole.USER, "b")
        val c = store.appendMessage(sessionId, MessageRole.USER, "c")

        assertTrue(store.deleteMessage(sessionId, a.id))
        var block = store.blocks(sessionId).single()
        assertEquals("删掉 first 后要重算", b.id, block.firstMessageId)
        assertEquals(b.createdAt, block.firstAt)
        assertEquals(2, block.count)

        assertTrue(store.deleteMessage(sessionId, c.id))
        block = store.blocks(sessionId).single()
        assertEquals(b.id, block.lastMessageId)
        assertEquals(b.createdAt, block.lastAt)
        assertEquals(1, block.count)
        assertEquals(1L, store.session(sessionId)!!.messageCount)

        assertFalse("删不存在的消息返回 false", store.deleteMessage(sessionId, "nope"))
    }

    @Test
    fun `emptying a block deletes its file and leaves a numbering hole`() {
        val store = open()
        (0 until FileStore.BLOCK_SIZE).forEach { store.appendMessage(sessionId, MessageRole.USER, "a$it") }
        val middle = (0 until FileStore.BLOCK_SIZE).map {
            store.appendMessage(sessionId, MessageRole.USER, "b$it")
        }
        (0 until 10).forEach { store.appendMessage(sessionId, MessageRole.USER, "c$it") }
        assertEquals(listOf(0, 1, 2), store.blocks(sessionId).map { it.index })

        // 把中间那一块整块删空
        for (message in middle) assertTrue(store.deleteMessage(sessionId, message.id))

        assertEquals("块表留下空洞：0 和 2 还在，1 没了", listOf(0, 2), store.blocks(sessionId).map { it.index })
        assertFalse("空块文件要删掉", Files.exists(root.resolve("sessions/$sessionId/blocks/1.jsonl")))
        assertEquals("不重平衡：block 0 仍然是满的", FileStore.BLOCK_SIZE, store.blocks(sessionId).first().count)

        // 相邻块查找必须跳过空洞（分页靠这个，不靠 blockIndex - 1）
        assertEquals(0, store.previousBlockIndex(sessionId, 2))
        assertEquals(2, store.nextBlockIndex(sessionId, 0))
        assertEquals(-1, store.previousBlockIndex(sessionId, 0))
        assertEquals(-1, store.nextBlockIndex(sessionId, 2))
        assertEquals(2, store.lastBlockIndex(sessionId))
        assertEquals(2, store.session(sessionId)!!.lastBlockIndex)
    }

    @Test
    fun `a missing block index is rebuilt from the block files`() {
        val store = open()
        repeat(3) { store.appendMessage(sessionId, MessageRole.USER, "m$it") }
        Files.delete(root.resolve("sessions/$sessionId/block_index.json"))

        val reopened = open()
        val blocks = reopened.blocks(sessionId)
        assertEquals(1, blocks.size)
        assertEquals(3, blocks.single().count)
        assertEquals("m2", reopened.blockMessages(sessionId, 0).last().content)
        assertTrue("重建后要写回块表", Files.isRegularFile(root.resolve("sessions/$sessionId/block_index.json")))
    }

    // ------------------------------------------------------------------ 原子写 / 并发

    @Test
    fun `json files are replaced atomically and no tmp files are left behind`() {
        val store = open()
        repeat(30) { store.appendMessage(sessionId, MessageRole.USER, "m$it") }

        val leftovers = Files.walk(root).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.count()
        }
        assertEquals("原子写不能留下 *.tmp", 0L, leftovers)
        for (relative in listOf(
            "meta.json",
            "personas.json",
            "sessions.json",
            "sessions/$sessionId/block_index.json"
        )) {
            val parsed = TestSupport.parse(Files.readString(root.resolve(relative)))
            assertTrue("$relative 应该是可解析的 JSON 对象", parsed.fields.isNotEmpty())
        }
    }

    @Test
    fun `concurrent appends of 1000 messages lose nothing`() {
        val store = open()
        val threads = 8
        val perThread = 125
        val start = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (0 until threads).map { threadIndex ->
            Thread {
                start.await()
                repeat(perThread) { messageIndex ->
                    runCatching {
                        store.appendMessage(
                            sessionId,
                            MessageRole.USER,
                            "t$threadIndex-$messageIndex",
                            clientMessageId = "c-$threadIndex-$messageIndex"
                        )
                    }.onFailure { failures += it }
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(120_000) }

        assertTrue("并发追加不应失败：$failures", failures.isEmpty())
        assertEquals(1000L, store.session(sessionId)!!.messageCount)
        val blocks = store.blocks(sessionId)
        assertEquals("1000 条 = 5 个满块", 5, blocks.size)
        val all = blocks.flatMap { store.blockMessages(sessionId, it.index) }
        assertEquals(1000, all.size)
        assertEquals("消息 id 不能重复", 1000, all.map { it.id }.toSet().size)
        assertEquals("时间戳严格递增（不会并列）", 1000, all.map { it.createdAt }.toSet().size)
        assertEquals(1000, all.map { it.content }.toSet().size)
        blocks.forEach { block -> assertEquals(FileStore.BLOCK_SIZE, block.count) }
    }

    @Test
    fun `client message id lookup is an exact idempotency key`() {
        val store = open()
        val first = store.appendMessage(
            sessionId,
            MessageRole.USER,
            "只此一条",
            clientMessageId = "dup-1"
        )
        assertEquals(first.id, store.findUserMessageByClientMessageId(sessionId, "dup-1")?.id)
        assertNull(store.findUserMessageByClientMessageId(sessionId, "dup-2"))
        assertEquals(1, store.blockMessages(sessionId, 0).size)
    }

    // ------------------------------------------------------------------ secrets / 用量

    @Test
    fun `api keys live in a 0600 secrets file and never in config metadata`() {
        val store = open()
        store.putApiConfig(
            ApiConfig(
                id = "cfg-1",
                name = "默认配置",
                provider = "MESSAGEAPI",
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                createdAt = 0,
                updatedAt = 0
            )
        )
        store.setApiKey("cfg-1", "sk-test-super-secret")

        assertEquals("sk-test-super-secret", store.apiKey("cfg-1"))
        assertEquals(1, store.apiConfigs().size)
        assertFalse(
            "api_configs.json 里不能出现明文 key",
            Files.readString(root.resolve(FileStore.API_CONFIGS_FILE)).contains("sk-test-super-secret")
        )
        val secrets = root.resolve(FileStore.SECRETS_FILE)
        assertTrue(Files.readString(secrets).contains("sk-test-super-secret"))
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            val permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(secrets))
            assertEquals("secrets.json 必须是 0600", "rw-------", permissions)
        }

        store.setApiKey("cfg-1", "")
        assertNull("传空串表示删除", store.apiKey("cfg-1"))
    }

    @Test
    fun `usage accumulates per persona and survives a reopen`() {
        val store = open()
        store.addUsage(defaultPersona, 10, 20)
        store.addUsage(defaultPersona, 1, 2)

        val usage = store.usage(defaultPersona)
        assertEquals(11L, usage.promptTokens)
        assertEquals(22L, usage.completionTokens)
        assertEquals(2L, usage.replies)
        assertEquals(11L, open().usage(defaultPersona).promptTokens)
    }

    // ------------------------------------------------------------------ 事件

    @Test
    fun `event cursors are monotonic and awaitEvents times out when idle`() {
        val clock = TestSupport.FakeClock(1_000L)
        val store = open(clock.reader)
        val first = store.appendMessage(sessionId, MessageRole.USER, "一")
        clock.advance(5)
        val second = store.appendMessage(sessionId, MessageRole.ASSISTANT, "二")

        assertEquals(2L, store.cursor())
        val events = store.eventsSince(0)
        assertEquals(2, events.size)
        assertEquals(EventType.MESSAGE_CREATED, events[0].type)
        assertTrue(events[0].cursor < events[1].cursor)
        val payload = events[0].payload.obj("message")
        assertEquals(first.id, payload.str("id"))
        assertEquals(second.id, store.eventsSince(events[0].cursor).single().payload.obj("message").str("id"))

        // 已经读到的游标不再重复返回
        assertTrue(store.eventsSince(events.last().cursor).isEmpty())

        // 没有新事件时，长轮询到超时就返回空，且游标不前移
        val startedAt = System.nanoTime()
        val result = store.awaitEvents(store.cursor(), 150)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(result.events.isEmpty())
        assertEquals(store.cursor(), result.cursor)
        assertTrue("应该真的等了一会儿：${elapsedMs}ms", elapsedMs >= 100)
    }

    @Test
    fun `mood and presence changes emit events only when the value changes`() {
        val store = open()
        val before = store.cursor()

        store.setMood(defaultPersona, "平静", 2)
        assertEquals("与播种值相同：不落盘不发事件", before, store.cursor())

        store.setMood(defaultPersona, "雀跃", 4)
        val moodEvents = store.eventsSince(before)
        assertEquals(1, moodEvents.size)
        assertEquals(EventType.MOOD_CHANGED, moodEvents.single().type)
        assertEquals("雀跃", moodEvents.single().payload.obj("mood").str("label"))

        store.setPresence(defaultPersona, "TYPING", "正在打字")
        val all = store.eventsSince(before)
        assertEquals(2, all.size)
        assertEquals(EventType.PRESENCE_CHANGED, all.last().type)
    }

    @Test
    fun `a torn trailing line is skipped instead of losing the whole block`() {
        val store = open()
        store.appendMessage(sessionId, MessageRole.USER, "完整的一行")
        val blockFile = root.resolve("sessions/$sessionId/blocks/0.jsonl")
        Files.write(
            blockFile,
            "{\"id\":\"torn".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
        )

        val messages = store.blockMessages(sessionId, 0)
        assertEquals(1, messages.size)
        assertEquals("完整的一行", messages.single().content)
    }

    @Test
    fun `empty baseline without seeding can be used and filled later`() {
        val store = open(seedIfEmpty = false)
        assertFalse(store.seeded)
        assertTrue(store.personas().isEmpty())
        assertTrue(store.sessions().isEmpty())
        assertTrue(FileStore.open(root, { 2_000L }, seedIfEmpty = false).personas().isEmpty())

        // 空基线之后仍然能正常建人格/会话（这里只验证会话创建的守卫：人格不存在会被拒绝）
        val failure = runCatching { store.ensureSession("missing-persona", "标题") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `settings round trip`() {
        val store = open()
        store.setSetting("locale", JsonValue.Str("zh-CN"))
        assertEquals("zh-CN", open().settings().fields["locale"]?.let { (it as JsonValue.Str).value })
    }

    @Test
    fun `unsafe session ids are rejected before touching the filesystem`() {
        val store = open()
        assertFalse(FileStore.isValidId("../../etc/passwd"))
        assertFalse(FileStore.isValidId("a/b"))
        assertNull(store.session("../../etc/passwd"))
        val failure = runCatching { store.blocks("../escape") }.exceptionOrNull()
        assertTrue("非法 id 应该抛 IllegalArgumentException：$failure", failure is IllegalArgumentException)
    }
}
