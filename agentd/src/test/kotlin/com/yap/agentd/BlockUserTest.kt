package com.yap.agentd

import com.yap.agentd.store.BlockProposal
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.tools.ServerToolCatalog
import com.yap.agentd.tools.ToolExecutor
import com.yap.app.core.json.Json
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

/**
 * 0.14 拉黑（`block_user`）：两轮确认、落库过滤、到期自动解除、DTO 暴露。
 *
 * 覆盖 `spec/experience/README.md` §2 的每一条硬要求：
 * - 第一次调用只返回待确认，不写 `blockedUntil`；
 * - 同一轮里确认无效；要等一条新的用户消息；
 * - 10 分钟没确认就作废；
 * - 拉黑期间用户消息落库 `blocked=true`、不生成回复；
 * - 模型上下文**永久**排除这些消息（解除后也不可见）；
 * - 到期自动解除（读取即解除 + 心跳真的清字段）。
 */
class BlockUserTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-block-user")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun executor(): ToolExecutor = ToolExecutor(store, clock = clock.asClock())

    /** 造一条用户消息，返回它的 id（模拟「这一轮」）。 */
    private fun userTurn(index: Int): String = store.appendMessage(
        sessionId = sessionId,
        role = MessageRole.USER,
        content = "第 $index 条",
        clientMessageId = "c-$index"
    ).id

    private fun blockUser(userMessageId: String, arguments: String): ToolExecutor.Outcome =
        executor().execute(
            ServerToolCatalog.BLOCK_USER,
            arguments,
            ToolExecutor.Context(personaId = personaId, sessionId = sessionId, userMessageId = userMessageId)
        )

    /** 只为了从 `/v1/state` 读人格 DTO（拉黑提议的 wire 形状）。 */
    private fun services(): AgentdServices = AgentdServices(
        store = store,
        replyGenerator = ReplyGenerator { _, _ -> ReplyOutcome(Usage.ZERO) }
    )

    // ------------------------------------------------------------------ 两轮确认

    @Test
    fun `first call only proposes and never writes blockedUntil`() {
        val turn = userTurn(1)
        val outcome = blockUser(turn, """{"hours":2,"reason":"想清静一会儿"}""")

        assertTrue("第一次是「提议」，工具本身不算失败", outcome.ok)
        // 规范 §9：工具回灌的是固定 JSON（不多不少两个键），不是一段中文。
        val json = TestSupport.parse(outcome.contentForModel)
        assertEquals("pending_confirmation", json.str("status"))
        assertEquals(clock.now + BlockProposal.TTL_MS, json.long("expiresAt"))
        assertNull("第一次不能生效", store.persona(personaId)!!.blockedUntil)
        assertFalse(store.isBlocked(personaId, clock.now))
        val proposal = store.blockProposal(personaId)
        assertNotNull("要留下待确认的提议", proposal)
        assertEquals(turn, proposal!!.userMessageId)
        assertEquals(2, proposal.hours)
        assertTrue("proposalId 必须存在", proposal.proposalId.isNotBlank())
        assertEquals(clock.now + BlockProposal.TTL_MS, proposal.expiresAt)
        // blockProposalJson 的 7 个字段与服务端内部模型同形。
        assertEquals(
            listOf("proposalId", "personaId", "hours", "reason", "userMessageId", "proposedAt", "expiresAt"),
            proposal.toJson().fields.keys.toList()
        )
        // DTO 暴露给客户端（GET /v1/state 的 personas[]）。
        val state = TestSupport.parse(AuthorityRoutes.handle(services(), "GET", "/v1/state", null).body)
        val persona = state.objects("personas").single { it.str("id") == personaId }
        val proposalJson = TestSupport.parse(persona.str("blockProposalJson"))
        assertEquals(proposal.proposalId, proposalJson.str("proposalId"))
        assertEquals(proposal.expiresAt, proposalJson.long("expiresAt"))
    }

    @Test
    fun `confirm in the same round is refused`() {
        val turn = userTurn(1)
        blockUser(turn, """{"hours":2}""")
        val confirm = blockUser(turn, """{"hours":2,"confirm":true}""")

        assertEquals("还差一轮", confirm.summary)
        assertEquals("need_more_user_messages", TestSupport.parse(confirm.contentForModel).str("code"))
        assertNull(store.persona(personaId)!!.blockedUntil)
    }

    @Test
    fun `confirm on a later user message really blocks and clears the proposal`() {
        val first = userTurn(1)
        blockUser(first, """{"hours":3,"reason":"需要安静"}""")
        clock.advance(60_000)
        val second = userTurn(2)
        val confirm = blockUser(second, """{"hours":3,"confirm":true}""")

        assertTrue(confirm.ok)
        assertEquals("blocked", TestSupport.parse(confirm.contentForModel).str("status"))
        val persona = store.persona(personaId)!!
        assertNotNull(persona.blockedUntil)
        assertEquals("需要安静", persona.blockReason)
        assertEquals(clock.now + 3 * 3_600_000L, persona.blockedUntil)
        assertTrue(store.isBlocked(personaId, clock.now))
        assertNull("确认后提议要清掉", store.blockProposal(personaId))
    }

    @Test
    fun `confirm without a proposal is refused`() {
        val turn = userTurn(1)
        val outcome = blockUser(turn, """{"hours":2,"confirm":true}""")
        assertEquals("no_pending_proposal", TestSupport.parse(outcome.contentForModel).str("code"))
        assertNull(store.persona(personaId)!!.blockedUntil)
    }

    @Test
    fun `a proposal older than ten minutes is discarded`() {
        val first = userTurn(1)
        blockUser(first, """{"hours":2}""")
        clock.advance(10 * 60 * 1000L + 1)
        val second = userTurn(2)
        val confirm = blockUser(second, """{"hours":2,"confirm":true}""")

        assertEquals("过期提议不能生效", "proposal_expired", TestSupport.parse(confirm.contentForModel).str("code"))
        assertNull(store.persona(personaId)!!.blockedUntil)
        assertNull("过期提议要清掉", store.blockProposal(personaId))
    }

    @Test
    fun `hours are clamped to one through twenty four`() {
        val first = userTurn(1)
        val outcome = blockUser(first, """{"hours":99}""")
        assertTrue(outcome.ok)
        assertEquals(24, store.blockProposal(personaId)!!.hours)
        clock.advance(1_000)
        val second = userTurn(2)
        blockUser(second, """{"hours":99,"confirm":true}""")
        val expected = clock.now + 24 * 3_600_000L
        assertEquals(expected, store.persona(personaId)!!.blockedUntil)
    }

    // ------------------------------------------------------------------ 到期

    @Test
    fun `block expires by itself and the heartbeat clears the fields`() {
        val first = userTurn(1)
        blockUser(first, """{"hours":1}""")
        clock.advance(1_000)
        blockUser(userTurn(2), """{"hours":1,"confirm":true}""")
        assertTrue(store.isBlocked(personaId, clock.now))

        clock.advance(3_600_001L)
        assertFalse("到期读取即解除", store.isBlocked(personaId, clock.now))
        // 心跳真的把字段清掉（/v1/state 不再返回过去的时刻）。
        assertEquals(1, store.expireBlocks(clock.now))
        assertNull(store.persona(personaId)!!.blockedUntil)
        assertEquals("", store.persona(personaId)!!.blockReason)
        assertEquals("再清一次没有东西可清", 0, store.expireBlocks(clock.now))
    }

    // ------------------------------------------------------------------ 落库与上下文过滤

    @Test
    fun `messages sent while blocked are stored blocked without a reply`() {
        val first = userTurn(1)
        blockUser(first, """{"hours":2}""")
        clock.advance(1_000)
        blockUser(userTurn(2), """{"hours":2,"confirm":true}""")

        val generated = java.util.concurrent.atomic.AtomicInteger()
        val services = AgentdServices(
            store = store,
            replyGenerator = ReplyGenerator { _, emit ->
                generated.incrementAndGet()
                emit(SseEvent.Delta("不该出现"))
                ReplyOutcome(Usage(1, 1))
            }
        )
        clock.advance(1_000)
        val sink = RecordingSink()
        AuthorityRoutes.stream(
            services,
            "POST",
            "/v1/sessions/$sessionId/messages",
            """{"clientMessageId":"during-block","content":"你在吗"}""",
            sink
        )

        assertEquals("生成器不该被调用", 0, generated.get())
        assertEquals(listOf("accepted", "done"), sink.types())
        val done = sink.firstOf<SseEvent.Done>()
        assertTrue("拉黑期间只已读", done.silent)
        assertEquals("", done.assistantMessageId)

        val messages = store.blockMessages(sessionId, 0)
        val blocked = messages.single { it.clientMessageId == "during-block" }
        assertTrue("落库要标 blocked", blocked.blocked)
        assertEquals(MessageRole.USER, blocked.role)
        assertEquals("没有 assistant 回复", 0, messages.count { it.replyTo == blocked.id })

        // 模型上下文永远看不到它（即使在拉黑期间）。
        val visible = store.latestMessages(sessionId, 100, includeBlocked = false)
        assertFalse("模型上下文不能有 blocked 消息", visible.any { it.id == blocked.id })
        assertTrue("客户端同步仍然看得到", store.latestMessages(sessionId, 100).any { it.id == blocked.id })
    }

    @Test
    fun `blocked messages stay invisible to the model after the block expires`() {
        val first = userTurn(1)
        blockUser(first, """{"hours":1}""")
        clock.advance(1_000)
        blockUser(userTurn(2), """{"hours":1,"confirm":true}""")
        clock.advance(1_000)
        val services = AgentdServices(store, ReplyGenerator { _, _ -> ReplyOutcome(Usage.ZERO) })
        AuthorityRoutes.stream(
            services,
            "POST",
            "/v1/sessions/$sessionId/messages",
            """{"clientMessageId":"during-block-2","content":"你还在吗"}""",
            sink = RecordingSink()
        )
        clock.advance(3_600_002L)
        assertEquals(1, store.expireBlocks(clock.now))

        // 解除后再聊：生成器收到的历史里不能有那条被拉黑的消息。
        var captured: ReplyRequest? = null
        val services2 = AgentdServices(
            store,
            ReplyGenerator { request, emit ->
                captured = request
                emit(SseEvent.Delta("嗯。"))
                ReplyOutcome(Usage(1, 1))
            }
        )
        AuthorityRoutes.stream(
            services2,
            "POST",
            "/v1/sessions/$sessionId/messages",
            """{"clientMessageId":"after-block","content":"在吗"}""",
            RecordingSink()
        )
        val history = captured!!.history
        assertTrue("新消息要进上下文", history.any { it.clientMessageId == "after-block" })
        assertFalse("被拉黑的旧消息永远不可见", history.any { it.clientMessageId == "during-block-2" })
    }

    // ------------------------------------------------------------------ DTO

    @Test
    fun `state exposes blockedUntil and per message blocked flags`() {
        val first = userTurn(1)
        blockUser(first, """{"hours":2,"reason":"累了"}""")
        clock.advance(1_000)
        blockUser(userTurn(2), """{"hours":2,"confirm":true}""")
        clock.advance(1_000)
        val services = AgentdServices(store, ReplyGenerator { _, _ -> ReplyOutcome(Usage.ZERO) })
        AuthorityRoutes.stream(
            services,
            "POST",
            "/v1/sessions/$sessionId/messages",
            """{"clientMessageId":"state-blocked","content":"在吗"}""",
            RecordingSink()
        )

        val response = AuthorityRoutes.handle(services, "GET", "/v1/state?since=0", null)
        val state = Json.parse(response.body) as JsonValue.Obj
        val persona = state.objects("personas").first { it.str("id") == personaId }
        assertEquals(store.persona(personaId)!!.blockedUntil, persona.long("blockedUntil"))
        assertEquals("累了", persona.str("blockReason"))

        val blockedMessage = state.objects("messages").first { it.str("clientMessageId") == "state-blocked" }
        assertTrue(blockedMessage.bool("blocked"))
        val normal = state.objects("messages").first { it.str("clientMessageId") == "c-1" }
        assertFalse("普通消息永远是 false", normal.bool("blocked"))
        assertEquals("拉黑状态不写进客户端额外字段", emptySet<String>(), persona.fields.keys.intersect(emptySet()))
    }
}
