package com.yap.agentd.llm

import com.yap.agentd.ChatService
import com.yap.agentd.RecordingSink
import com.yap.agentd.SendMessageRequest
import com.yap.agentd.TestSupport
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.app.domain.ExperienceContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工具轮数（0.15）：默认 5、调用方/模型可指定、硬上限 15（`ExperienceContract.clampToolRounds`）。
 *
 * 钉住三件事：
 * 1. 请求体里的 `toolRounds` 真的改变回路轮数；
 * 2. 越界值被夹取（0/负数 → 1 轮，100 → 15 轮），不会让一次回复变成几十次 completion；
 * 3. 最后一轮不再给 `tools`（逼模型用现有信息说话）。
 */
class ToolRoundsTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-tool-rounds")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun llmFor(server: FakeLlmServer): LlmAccess {
        store.pushLlmConfig(server.baseUrl)
        return LlmAccess(LlmClient(), LlmConfigResolver(store, loopback = true))
    }

    private fun serviceFor(server: FakeLlmServer): ChatService =
        ChatService(store, LlmReplyGenerator(store, llmFor(server)))

    private fun send(service: ChatService, toolRounds: Int?): RecordingSink {
        val sink = RecordingSink()
        service.send(
            sessionId,
            SendMessageRequest(
                clientMessageId = "c-${System.nanoTime()}",
                content = "在吗",
                toolRounds = toolRounds
            ),
            sink
        )
        return sink
    }

    @Test
    fun `request toolRounds controls how many rounds the loop runs`() {
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            val index = counter.getAndIncrement()
            exchange.sse(
                listOf(
                    FakeLlmServer.toolCall(0, id = "c$index", name = "diary", arguments = """{"action":"list"}"""),
                    FakeLlmServer.DONE
                )
            )
        }.use { server ->
            send(serviceFor(server), toolRounds = 2)
            assertEquals("请求指定 2 轮就只跑 2 轮", 2, server.requests.size)
            assertFalse("最后一轮不带 tools", server.requests[1].body.contains("\"tools\""))
        }
    }

    @Test
    fun `toolRounds is clamped to one through fifteen`() {
        listOf(0 to 1, -3 to 1, 99 to ExperienceContract.MAX_TOOL_ROUNDS).forEach { (requested, expected) ->
            val counter = AtomicInteger()
            FakeLlmServer { exchange ->
                val index = counter.getAndIncrement()
                exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "c$index",
                            name = "diary",
                            arguments = """{"action":"list"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
            }.use { server ->
                send(serviceFor(server), toolRounds = requested)
                assertEquals("toolRounds=$requested 应被夹到 $expected", expected, server.requests.size)
            }
        }
    }

    @Test
    fun `the default is five rounds`() {
        val counter = AtomicInteger()
        FakeLlmServer { exchange ->
            val index = counter.getAndIncrement()
            exchange.sse(
                listOf(
                    FakeLlmServer.toolCall(0, id = "c$index", name = "diary", arguments = """{"action":"list"}"""),
                    FakeLlmServer.DONE
                )
            )
        }.use { server ->
            send(serviceFor(server), toolRounds = null)
            assertEquals(ExperienceContract.DEFAULT_TOOL_ROUNDS, server.requests.size)
            assertEquals(5, server.requests.size)
        }
    }

    @Test
    fun `a request without tool rounds still persists the user message once`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("嗯。"), FakeLlmServer.DONE))
        }.use { server ->
            val sink = send(serviceFor(server), toolRounds = 4)
            assertEquals("accepted", sink.types().first())
            assertEquals(1, store.blockMessages(sessionId, 0).count { it.role == MessageRole.USER })
            assertTrue(sink.types().contains("done"))
        }
    }
}
