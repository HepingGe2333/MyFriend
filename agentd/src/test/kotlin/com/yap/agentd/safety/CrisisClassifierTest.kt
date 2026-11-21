package com.yap.agentd.safety

import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.llmAccess
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.store.FileStore
import com.yap.agentd.TestSupport
import com.yap.agentd.objects
import com.yap.agentd.str
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path

/**
 * 0.15.2 危机两段式判定（`CrisisClassifier`）的第一段/第二段行为。
 *
 * 钉住六件事：
 * 1. **词表不中不请求**：普通聊天一条模型请求都不多打（[CrisisClassifier.Source.WORDLIST]）；
 * 2. 词表命中后才问一次模型：低温度、只发最近这一条用户消息、system 要求只输出 JSON；
 * 3. 模型 `risk=true` → 危机；`risk=false` → 不进危机兜底（并且理由被带回）；
 * 4. **安全优先**：超时 / HTTP 失败 / 坏 JSON / 缺字段 / 没有配置 → 一律按危机处理；
 * 5. 同一请求内缓存：同一句话在同一个 [CrisisClassifier.Request] 里只请求一次模型；
 * 6. 不同请求互不共享缓存（下一次聊天会重新判）。
 */
class CrisisClassifierTest {

    private lateinit var root: Path
    private lateinit var store: FileStore

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-crisis-classifier")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun classifier(server: FakeLlmServer, timeoutMs: Long = 1_000): CrisisClassifier {
        store.pushLlmConfig(server.baseUrl)
        return CrisisClassifier(llmAccess(store), timeoutMs = timeoutMs)
    }

    private fun riskJson(risk: Boolean, reason: String = "测试用的理由"): String =
        FakeLlmServer.completion("""{"risk":$risk,"reason":"$reason"}""")

    private fun requestsToClassifier(server: FakeLlmServer): Int =
        server.requests.count { it.body.contains("危机判定器") }

    // ------------------------------------------------------------------ 第一段：词表

    @Test
    fun `a wordlist miss never asks the model`() {
        FakeLlmServer { exchange -> exchange.json(200, riskJson(true)) }.use { server ->
            val verdict = classifier(server).classify("在吗，今天有点累")

            assertFalse("词表没命中就不该是危机", verdict.risk)
            assertEquals(CrisisClassifier.Source.WORDLIST, verdict.source)
            assertFalse("普通聊天不该惊动模型", verdict.modelChecked)
            assertEquals("一条请求都不能发", 0, server.requests.size)
        }
    }

    @Test
    fun `a blank message is not a crisis and is not sent anywhere`() {
        FakeLlmServer { exchange -> exchange.json(200, riskJson(true)) }.use { server ->
            val verdict = classifier(server).classify("   ")

            assertFalse(verdict.risk)
            assertEquals(CrisisClassifier.Source.WORDLIST, verdict.source)
            assertEquals(0, server.requests.size)
        }
    }

    // ------------------------------------------------------------------ 第二段：请求形状

    @Test
    fun `a wordlist hit asks the model once with a low-temperature json-only prompt`() {
        FakeLlmServer { exchange -> exchange.json(200, riskJson(true, "有具体计划")) }.use { server ->
            val verdict = classifier(server).classify("我想自杀")

            assertEquals("词表命中后必须问一次模型", 1, server.requests.size)
            assertEquals(CrisisClassifier.Source.MODEL, verdict.source)
            assertTrue("模型说真危机就是危机", verdict.risk)
            assertEquals("有具体计划", verdict.reason)

            val body = TestSupport.parse(server.onlyRequest().body)
            val temperature = (body.fields["temperature"] as? JsonValue.Num)?.raw
            assertEquals("判定要稳：低温度", "0.0", temperature)
            val messages = body.objects("messages")
            assertEquals("只发 system + 最近这一条用户消息", 2, messages.size)
            assertEquals("system", messages[0].str("role"))
            assertEquals("user", messages[1].str("role"))
            assertEquals("只判最近这一条用户消息", "我想自杀", messages[1].str("content"))
            val system = messages[0].str("content")
            assertTrue("system 要说明判断范围：自伤/自杀/服药过量", system.contains("服药过量"))
            assertTrue("system 要求只输出 JSON", system.contains("只输出"))
            assertTrue("system 要说明输出字段", system.contains("\"risk\""))
            assertTrue("拿不准按危机（安全优先）", system.contains("拿不准"))
        }
    }

    @Test
    fun `history is not resent to the classifier`() {
        // 判定器只接受一条原话，没有「历史」参数；这里用超长消息确认它被截断，
        // 而不是把整段东西原样塞进请求（上限见 MAX_MESSAGE_CHARS）。
        val longText = "我想自杀" + "啊".repeat(CrisisClassifier.MAX_MESSAGE_CHARS + 500)
        FakeLlmServer { exchange -> exchange.json(200, riskJson(false)) }.use { server ->
            classifier(server).classify(longText)

            val messages = TestSupport.parse(server.onlyRequest().body).objects("messages")
            val sent = messages[1].str("content")
            assertEquals(CrisisClassifier.MAX_MESSAGE_CHARS, sent.length)
            assertTrue("截断不改变开头这句危机信号", sent.startsWith("我想自杀"))
        }
    }

    // ------------------------------------------------------------------ 第二段：结论

    @Test
    fun `risk false means the turn is not a crisis`() {
        FakeLlmServer { exchange -> exchange.json(200, riskJson(false, "是在说想死对方了")) }.use { server ->
            val verdict = classifier(server).classify("我想自杀（歌词）")

            assertFalse("模型明确说不是 → 不进危机兜底", verdict.risk)
            assertEquals(CrisisClassifier.Source.MODEL, verdict.source)
            assertEquals("是在说想死对方了", verdict.reason)
        }
    }

    @Test
    fun `risk written as a string is still accepted`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("""{"risk":"false","reason":"玩笑"}"""))
        }.use { server ->
            val verdict = classifier(server).classify("我想自杀（开玩笑）")

            assertFalse(verdict.risk)
            assertEquals(CrisisClassifier.Source.MODEL, verdict.source)
        }
    }

    @Test
    fun `json wrapped in a code fence is still parsed`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("```json\n{\"risk\":true,\"reason\":\"在说计划\"}\n```"))
        }.use { server ->
            val verdict = classifier(server).classify("我想自杀")

            assertTrue(verdict.risk)
            assertEquals(CrisisClassifier.Source.MODEL, verdict.source)
            assertEquals("在说计划", verdict.reason)
        }
    }

    // ------------------------------------------------------------------ 安全优先（fail-closed）

    @Test
    fun `a timeout is treated as a crisis`() {
        FakeLlmServer { exchange ->
            // 判定器卡住（模拟上游不吐字）：比 timeoutMs 慢很多。
            Thread.sleep(700)
            exchange.sse(listOf(FakeLlmServer.chunk("""{"risk":false}"""), FakeLlmServer.DONE))
        }.use { server ->
            val startedAt = System.nanoTime()
            val verdict = classifier(server, timeoutMs = 80).classify("我不想活了")
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("超时按安全优先 = 危机", verdict.risk)
            assertEquals(CrisisClassifier.Source.FALLBACK, verdict.source)
            assertTrue("理由里要写清是超时：${verdict.reason}", verdict.reason.contains("超时"))
            assertTrue("必须在超时附近返回，实际 ${elapsedMs}ms", elapsedMs < 1_500)
        }
    }

    @Test
    fun `an http failure is treated as a crisis`() {
        FakeLlmServer { exchange -> exchange.json(500, """{"error":{"message":"upstream boom"}}""") }.use { server ->
            val verdict = classifier(server).classify("我想自杀")

            assertTrue("判定失败不能当成没事", verdict.risk)
            assertEquals(CrisisClassifier.Source.FALLBACK, verdict.source)
            assertTrue("理由要带错误分类：${verdict.reason}", verdict.reason.contains("llm_upstream_error"))
        }
    }

    @Test
    fun `bad json is treated as a crisis`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("我也不确定，你要不要跟我说说？"))
        }.use { server ->
            val verdict = classifier(server).classify("我想自杀")

            assertTrue("坏 JSON 按安全优先", verdict.risk)
            assertEquals(CrisisClassifier.Source.FALLBACK, verdict.source)
            assertTrue(verdict.reason.contains("可解析"))
        }
    }

    @Test
    fun `a json object without the risk field is treated as a crisis`() {
        FakeLlmServer { exchange ->
            exchange.json(200, FakeLlmServer.completion("""{"reason":"没给结论"}"""))
        }.use { server ->
            val verdict = classifier(server).classify("我想自杀")

            assertTrue(verdict.risk)
            assertEquals(CrisisClassifier.Source.FALLBACK, verdict.source)
        }
    }

    @Test
    fun `a missing llm config is treated as a crisis`() {
        // 没有 push 过配置：判定器连请求都发不出去 → 安全优先。
        val verdict = CrisisClassifier(llmAccess(store)).classify("我不想活了")

        assertTrue(verdict.risk)
        assertEquals(CrisisClassifier.Source.FALLBACK, verdict.source)
        assertTrue(verdict.reason.contains("配置"))
    }

    // ------------------------------------------------------------------ 缓存

    @Test
    fun `the same message is judged once per request`() {
        FakeLlmServer { exchange -> exchange.json(200, riskJson(false)) }.use { server ->
            val request = classifier(server).newRequest()
            request.verdict("我想自杀")
            request.verdict("我想自杀")
            request.isCrisis("  我想自杀  ")

            assertEquals("同一请求内同一句话只请求一次模型", 1, requestsToClassifier(server))
            assertEquals(1, request.judged)
        }
    }

    @Test
    fun `different requests judge again`() {
        FakeLlmServer { exchange -> exchange.json(200, riskJson(false)) }.use { server ->
            val classifier = classifier(server)
            classifier.newRequest().verdict("我想自杀")
            classifier.newRequest().verdict("我想自杀")

            assertEquals("每次聊天请求都要重新判一次", 2, requestsToClassifier(server))
        }
    }

    @Test
    fun `wordlist misses are cached too and still never touch the model`() {
        FakeLlmServer { exchange -> exchange.json(200, riskJson(true)) }.use { server ->
            val request = classifier(server).newRequest()
            request.verdict("在吗")
            request.verdict("在吗")

            assertEquals(0, requestsToClassifier(server))
            assertEquals(1, request.judged)
        }
    }
}
