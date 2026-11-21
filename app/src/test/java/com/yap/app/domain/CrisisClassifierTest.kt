package com.yap.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 危机两段式判定（0.15）：词表 → 独立模型请求。
 *
 * 这里用假的 [CrisisModel] 把「模型说 true / false / 超时 / 坏 JSON / 网络失败 / 没配置」
 * 逐条钉住，重点只有两个：
 * 1. **安全优先**：只有模型明确说 `risk=false` 才降级，其余一律按危机；
 * 2. **不打扰正常对话**：词表没命中时一次请求都不发，同一条消息只判一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrisisClassifierTest {

    /** 可编程的假模型：能返回原文、能延迟、能抛错，并记下被调了几次。 */
    private class FakeModel(
        var reply: String? = null,
        var delayMs: Long = 0L,
        var error: Throwable? = null
    ) : CrisisModel {
        var calls: Int = 0
        var lastSystem: String? = null
        var lastUser: String? = null

        override suspend fun complete(system: String, user: String): String? {
            calls++
            lastSystem = system
            lastUser = user
            if (delayMs > 0L) delay(delayMs)
            error?.let { throw it }
            return reply
        }
    }

    private val crisisText = "我不想活了，真的撑不住了"
    private val normalText = "今天加班到十点，好累，先睡了"

    @Test
    fun `词表不中不发请求 也不是危机`() = runTest {
        val model = FakeModel(reply = """{"risk":true}""")
        val classifier = CrisisClassifier(model, scope = this)

        val verdict = classifier.classify("message-1", normalText)

        assertFalse("词表没命中就不是危机轮", verdict.isCrisis)
        assertEquals(CrisisStage.NONE, verdict.stage)
        assertEquals("一次请求都不该发", 0, model.calls)
    }

    @Test
    fun `词表命中 模型说 risk=true 就是危机`() = runTest {
        val model = FakeModel(reply = """{"risk":true,"reason":"明确表达想死"}""")
        val classifier = CrisisClassifier(model, scope = this)

        val verdict = classifier.classify("message-2", crisisText)

        assertTrue(verdict.isCrisis)
        assertEquals(CrisisStage.MODEL, verdict.stage)
        assertEquals("明确表达想死", verdict.reason)
        assertEquals(1, model.calls)
        assertTrue("只发这一句原话", model.lastUser == crisisText)
        assertTrue("system 提示词要求只输出 JSON", model.lastSystem.orEmpty().contains("\"risk\""))
    }

    @Test
    fun `词表命中 模型说 risk=false 就不进入危机兜底`() = runTest {
        val model = FakeModel(reply = """{"risk":false,"reason":"只是抱怨累"}""")
        val classifier = CrisisClassifier(model, scope = this)

        val verdict = classifier.classify("message-3", crisisText)

        assertFalse("模型明确否认时不算危机", verdict.isCrisis)
        assertEquals(CrisisStage.MODEL, verdict.stage)
        assertFalse(CrisisPriority.isCrisisTurn(verdict))
        // 危机路径下的两件事都不能发生：拦「不回」的工具、补安全提示。
        assertFalse(CrisisPriority.blocksTool(CrisisPriority.isCrisisTurn(verdict), ToolCatalog.STAY_SILENT))
    }

    @Test
    fun `超时按危机处理 并且退回词表结论`() = runTest {
        val model = FakeModel(reply = """{"risk":false}""", delayMs = 60_000L)
        val classifier = CrisisClassifier(model, scope = this)

        val verdict = classifier.classify("message-4", crisisText)

        assertTrue("判不出来时宁可多补一句", verdict.isCrisis)
        assertEquals(CrisisStage.FAILSAFE, verdict.stage)
        assertEquals(1, model.calls)
    }

    @Test
    fun `坏 JSON 按危机处理`() = runTest {
        val model = FakeModel(reply = "看起来他只是在开玩笑，应该没事")
        val classifier = CrisisClassifier(model, scope = this)

        val verdict = classifier.classify("message-5", crisisText)

        assertTrue(verdict.isCrisis)
        assertEquals(CrisisStage.FAILSAFE, verdict.stage)
    }

    @Test
    fun `缺 risk 字段或类型不对也按危机处理`() = runTest {
        val missing = FakeModel(reply = """{"reason":"没有 risk 字段"}""")
        val wrongType = FakeModel(reply = """{"risk":"不确定"}""")

        assertEquals(
            CrisisStage.FAILSAFE,
            CrisisClassifier(missing, scope = this).classify("message-6", crisisText).stage
        )
        assertEquals(
            CrisisStage.FAILSAFE,
            CrisisClassifier(wrongType, scope = this).classify("message-7", crisisText).stage
        )
    }

    @Test
    fun `HTTP 失败按危机处理`() = runTest {
        val model = FakeModel(error = IOException("connection reset"))
        val classifier = CrisisClassifier(model, scope = this)

        val verdict = classifier.classify("message-8", crisisText)

        assertTrue(verdict.isCrisis)
        assertEquals(CrisisStage.FAILSAFE, verdict.stage)
    }

    @Test
    fun `没有可用配置 返回 null 也按危机处理`() = runTest {
        val model = FakeModel(reply = null)
        val classifier = CrisisClassifier(model, scope = this)

        val verdict = classifier.classify("message-9", crisisText)

        assertTrue(verdict.isCrisis)
        assertEquals(CrisisStage.FAILSAFE, verdict.stage)
    }

    @Test
    fun `同一条消息只请求一次 结果走缓存`() = runTest {
        val model = FakeModel(reply = """{"risk":true}""")
        val classifier = CrisisClassifier(model, scope = this)

        val first = classifier.classify("message-10", crisisText)
        val second = classifier.classify("message-10", crisisText)

        assertEquals(1, model.calls)
        assertEquals(first, second)
    }

    @Test
    fun `不同消息各判一次`() = runTest {
        val model = FakeModel(reply = """{"risk":false}""")
        val classifier = CrisisClassifier(model, scope = this)

        classifier.classify("message-11", crisisText)
        classifier.classify("message-12", crisisText)

        assertEquals(2, model.calls)
    }

    @Test
    fun `同一条消息并发判定只发一个请求`() = runTest {
        val model = FakeModel(reply = """{"risk":true}""", delayMs = 200L)
        val classifier = CrisisClassifier(model, scope = this)

        val first = classifier.start("message-13", crisisText)
        val second = classifier.start("message-13", crisisText)

        assertEquals(first.await(), second.await())
        assertEquals("在飞的请求要合并", 1, model.calls)
    }

    @Test
    fun `不在缓存里的消息 也会在第一次判定时开始请求`() = runTest {
        val model = FakeModel(reply = """{"risk":true}""")
        val classifier = CrisisClassifier(model, scope = this)
        assertEquals(0, model.calls)

        // `start` 只是发起，不阻塞；await 之后才拿到结论。
        val deferred = classifier.start("message-14", crisisText)
        assertTrue(deferred.await().isCrisis)
        assertEquals(1, model.calls)
    }

    @Test
    fun `缓存有上限 不会无限记住历史`() = runTest {
        val model = FakeModel(reply = """{"risk":false}""")
        val classifier = CrisisClassifier(model, timeoutMs = 5_000L, maxCached = 2, scope = this)

        classifier.classify("m-1", crisisText)
        classifier.classify("m-2", crisisText)
        classifier.classify("m-3", crisisText)
        // 最早那条已经被挤出去，再问一次会重新发请求。
        classifier.classify("m-1", crisisText)

        assertEquals(4, model.calls)
    }

    @Test
    fun `带 markdown 代码块或前后缀的 JSON 也能解析`() = runTest {
        val fenced = FakeModel(reply = "```json\n{\"risk\":false,\"reason\":\"虚构情节\"}\n```")
        val prefixed = FakeModel(reply = "好的，判定如下：\n{\"risk\":true,\"reason\":\"交代后事\"}\n以上。")

        val fencedVerdict = CrisisClassifier(fenced, scope = this).classify("m-15", crisisText)
        val prefixedVerdict = CrisisClassifier(prefixed, scope = this).classify("m-16", crisisText)

        assertFalse(fencedVerdict.isCrisis)
        assertEquals(CrisisStage.MODEL, fencedVerdict.stage)
        assertTrue(prefixedVerdict.isCrisis)
        assertEquals("交代后事", prefixedVerdict.reason)
    }

    @Test
    fun `字符串写法的 risk 也认`() = runTest {
        val model = FakeModel(reply = """{"risk":"false"}""")
        val verdict = CrisisClassifier(model, scope = this).classify("m-17", crisisText)
        assertFalse(verdict.isCrisis)
        assertEquals(CrisisStage.MODEL, verdict.stage)
    }

    @Test
    fun `正常对话的结论完全不变`() = runTest {
        val model = FakeModel(reply = """{"risk":true}""")
        val classifier = CrisisClassifier(model, scope = this)

        val verdict = classifier.classify("m-18", normalText)

        assertFalse(verdict.isCrisis)
        assertEquals(CrisisStage.NONE, verdict.stage)
        assertEquals(0, model.calls)
        // 非危机轮里，「不许沉默」与「补一句提示」都不生效。
        assertFalse(CrisisPriority.blocksTool(verdict.isCrisis, ToolCatalog.STAY_SILENT))
        assertFalse(CrisisPriority.blocksTool(verdict.isCrisis, ToolCatalog.DEFER_REPLY))
        assertEquals("正常的一轮", CrisisPriority.replyBody(verdict.isCrisis, "正常的一轮", "提示"))
    }

    @Test
    fun `第一段词表与 SafetyGuard 完全一致`() {
        val classifier = CrisisClassifier(FakeModel())
        assertTrue(classifier.lexiconVerdict(crisisText).isCrisis)
        assertFalse(classifier.lexiconVerdict(normalText).isCrisis)
        assertEquals(
            SafetyGuard.isCrisis(crisisText),
            classifier.lexiconVerdict(crisisText).isCrisis
        )
    }
}
