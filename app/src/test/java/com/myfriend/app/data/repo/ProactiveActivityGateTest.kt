package com.myfriend.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 定时主动规则的活动状态门禁（0.12 遗留缺陷 3）。
 *
 * 缺陷：`HeartbeatController` 看了 `activityJson.availability`，但 Worker 的定时规则
 * （`ProactiveRepository.runDue` → `fire`）只看了作息。BUSY 照样开口，LIMITED 也不降概率。
 *
 * 修法拆成纯函数 [ProactiveActivityGate]（随机源可注入），这里逐条钉：
 * - BUSY 跳过并顺延到活动结束；
 * - LIMITED 按 `LIMITED_PROACTIVE_FACTOR` 降概率，没掷中排到活动结束/30 分钟后；
 * - 过期活动视为 FREE；
 * - 坏 JSON/空活动不挡；睡眠检查仍在门禁之前（作息优先）。
 *
 * 真正的「不产生消息」由源码契约钉住：`fire` 在调用模型/写库之前就抛
 * `ActivityDeferredException`，`runDue` 把它当「延后」而不是失败。
 */
class ProactiveActivityGateTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L

    private fun activityJson(
        availability: String,
        until: Long,
        text: String = "在事务所赶方案",
        kind: String = "WORK"
    ): String = """{"kind":"$kind","activity":"$text","until":$until,"availability":"$availability"}"""

    // ------------------------------------------------------------------ 纯判定

    @Test
    fun `busy skips the tick and reschedules to the end of the activity`() {
        val until = now + 2 * hour
        val result = ProactiveActivityGate.evaluate(activityJson("BUSY", until), now, roll = 0.0)

        assertFalse("BUSY 时不能触发主动消息", result.shouldProceed)
        assertEquals(ProactiveActivityBlock.BUSY, result.block)
        assertEquals("顺延到活动结束附近", until, result.resumeAt)
    }

    @Test
    fun `limited lowers the probability with an injectable roll`() {
        val until = now + 4 * hour
        val json = activityJson("LIMITED", until)

        // 低于降权系数：正常触发。
        val low = ProactiveActivityGate.evaluate(json, now, roll = 0.0)
        assertTrue(low.shouldProceed)
        assertEquals(ProactiveActivityBlock.NONE, low.block)

        // 刚好到系数：不触发（LIMITED 的概率是 0.35，不是 1）。
        val boundary = ProactiveActivityGate.evaluate(
            json, now, roll = com.myfriend.app.domain.PersonaActivity.LIMITED_PROACTIVE_FACTOR
        )
        assertFalse(boundary.shouldProceed)
        assertEquals(ProactiveActivityBlock.LIMITED, boundary.block)
        assertEquals("最多等 30 分钟再看一次", now + ProactiveActivityGate.LIMITED_RETRY_DELAY_MS, boundary.resumeAt)

        val high = ProactiveActivityGate.evaluate(json, now, roll = 0.99)
        assertFalse(high.shouldProceed)

        // 1000 个均匀随机数里只有约 35% 能通过：不是「完全挡」，也不是「照常发」。
        var passed = 0
        for (step in 0 until 1000) {
            if (ProactiveActivityGate.evaluate(json, now, roll = step / 1000.0).shouldProceed) passed++
        }
        assertEquals("LIMITED 通过率应接近 35%", 350, passed)
    }

    @Test
    fun `limited never reschedules past the activity end`() {
        val until = now + 5 * 60_000L // 活动只剩 5 分钟，比 30 分钟重试间隔更早
        val result = ProactiveActivityGate.evaluate(activityJson("LIMITED", until), now, roll = 0.9)

        assertEquals(ProactiveActivityBlock.LIMITED, result.block)
        assertEquals("活动结束前就该再看一眼", until, result.resumeAt)
    }

    @Test
    fun `an expired activity is treated as free`() {
        val expired = activityJson("BUSY", until = now - 1)
        val result = ProactiveActivityGate.evaluate(expired, now, roll = 0.99)

        assertTrue("过期的 BUSY 不能继续压住主动消息", result.shouldProceed)
        assertEquals(ProactiveActivityBlock.NONE, result.block)
    }

    @Test
    fun `free and missing or broken activity never block`() {
        assertTrue(ProactiveActivityGate.evaluate(activityJson("FREE", now + hour), now, 0.99).shouldProceed)
        assertTrue(ProactiveActivityGate.evaluate(null, now, 0.99).shouldProceed)
        assertTrue(ProactiveActivityGate.evaluate("", now, 0.99).shouldProceed)
        assertTrue(ProactiveActivityGate.evaluate("not json", now, 0.99).shouldProceed)
        // 没有 activity 文本的 JSON 不是有效活动（PersonaActivityCodec 返回 null）。
        assertTrue(
            ProactiveActivityGate.evaluate("""{"availability":"BUSY","until":${now + hour}}""", now, 0.99)
                .shouldProceed
        )
    }

    @Test
    fun `bad rolls are conservative and do not fire a limited persona`() {
        val json = activityJson("LIMITED", now + hour)
        assertFalse(ProactiveActivityGate.evaluate(json, now, Double.NaN).shouldProceed)
        assertFalse(ProactiveActivityGate.evaluate(json, now, 1.5).shouldProceed)
        assertFalse(ProactiveActivityGate.evaluate(json, now, -0.1).shouldProceed)
    }

    // ------------------------------------------------------------------ 接线契约

    @Test
    fun `repository checks activity after sleep and before the model call`() {
        val path = "src/main/java/com/myfriend/app/data/repo/ProactiveRepository.kt"
        val source = File(path).readText()

        val sleepCheck = source.indexOf("isAsleep(persona, minuteOfDay(now))")
        val gate = source.indexOf("ProactiveActivityGate.evaluate(")
        val build = source.indexOf("buildProactiveMessages(")
        val model = source.indexOf("registry.adapterFor(")
        assertTrue("睡眠检查必须还在", sleepCheck >= 0)
        assertTrue("定时规则必须检查 activity", gate > sleepCheck)
        assertTrue("活动门禁必须在组装提示词之前", gate in 0 until build)
        assertTrue("活动门禁必须在调用模型之前", gate in 0 until model)

        // BUSY/LIMITED 抛的是「延后」而不是普通失败：runDue 用它作为 nextTriggerAt，且不落错误令牌。
        val runDue = source.substringAfter("suspend fun runDue(").substringBefore("suspend fun browseIdlePersonas(")
        assertTrue(runDue.contains("val deferred = error as? ActivityDeferredException"))
        assertTrue(runDue.contains("deferred != null -> deferred.resumeAt"))
        assertTrue(runDue.contains("asleep || deferred != null"))
        assertTrue(
            "被活动门禁挡住时，模型调用根本走不到",
            source.substringAfter("private suspend fun fire(").substringBefore("private suspend fun buildProactiveMessages(")
                .contains("throw ActivityDeferredException(")
        )

        // set_quiet_hours / 睡眠逻辑没有被本修复改动：ToolExecutor 仍然负责写入安静时段。
        val toolExecutor = File("src/main/java/com/myfriend/app/data/repo/ToolExecutor.kt").readText()
        assertTrue(toolExecutor.contains("\"set_quiet_hours\""))
    }
}
