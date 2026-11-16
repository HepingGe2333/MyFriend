package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 定价、估算、预算判定、month start —— 全是纯函数，时间与时区都由测试固定传入。
 *
 * 这些数字决定「他这个月花了约多少钱」，所以取整、边界、坏输入都要钉住。
 */
class ApiCostTest {

    private val utc = ZoneId.of("UTC")

    // ------------------------------------------------------------ 各系模型定价

    @Test
    fun `deepseek-chat 输入按 0_27 美元每百万算`() {
        val cost = ApiCost.estimateCostMicroUsd("deepseek-chat", TokenUsage(promptTokens = 1_000_000, completionTokens = 0))
        assertEquals(270_000L, cost) // $0.27
    }

    @Test
    fun `deepseek-chat 输出按 1_10 美元每百万算`() {
        val cost = ApiCost.estimateCostMicroUsd("deepseek-chat", TokenUsage(promptTokens = 0, completionTokens = 1_000_000))
        assertEquals(1_100_000L, cost) // $1.10
    }

    @Test
    fun `deepseek-reasoner 的输出比 chat 贵`() {
        val chat = ApiCost.basePriceFor("deepseek-chat")
        val reasoner = ApiCost.basePriceFor("deepseek-reasoner")
        assertTrue(reasoner.outputMicroUsdPerMillion > chat.outputMicroUsdPerMillion)
        assertEquals(2_190_000L, reasoner.outputMicroUsdPerMillion)
    }

    @Test
    fun `claude 系按 sonnet 定价`() {
        val price = ApiCost.basePriceFor("claude-3-5-sonnet-20241022")
        assertEquals(3_000_000L, price.inputMicroUsdPerMillion)
        assertEquals(15_000_000L, price.outputMicroUsdPerMillion)
    }

    @Test
    fun `gpt-4o-mini 比 gpt-4o 便宜`() {
        val mini = ApiCost.basePriceFor("gpt-4o-mini")
        val full = ApiCost.basePriceFor("gpt-4o")
        assertTrue(mini.inputMicroUsdPerMillion < full.inputMicroUsdPerMillion)
        assertEquals(150_000L, mini.inputMicroUsdPerMillion)
    }

    @Test
    fun `gemini flash 走自己的价目`() {
        val price = ApiCost.basePriceFor("gemini-1.5-flash")
        assertEquals(75_000L, price.inputMicroUsdPerMillion)
        assertEquals(300_000L, price.outputMicroUsdPerMillion)
    }

    @Test
    fun `带服务商前缀的模型 id 也认得出来`() {
        val price = ApiCost.basePriceFor("openai/gpt-4o-mini")
        assertEquals(150_000L, price.inputMicroUsdPerMillion)
    }

    // ------------------------------------------------------------ 未知与坏输入

    @Test
    fun `未知模型用保守默认值，不会算成 0`() {
        val price = ApiCost.basePriceFor("some-brand-new-model")
        assertEquals(ApiCost.UNKNOWN_PRICE, price)
        val cost = ApiCost.estimateCostMicroUsd("some-brand-new-model", TokenUsage(1_000_000, 1_000_000))
        assertTrue("未知模型要算得比 deepseek 贵，别让人以为不要钱", cost > 1_100_000L)
    }

    @Test
    fun `模型名为空或空白时也不抛异常`() {
        assertEquals(ApiCost.UNKNOWN_PRICE, ApiCost.basePriceFor(null))
        assertEquals(ApiCost.UNKNOWN_PRICE, ApiCost.basePriceFor("   "))
        assertEquals(0L, ApiCost.estimateCostMicroUsd(null, TokenUsage.ZERO))
    }

    @Test
    fun `tokens 为 0 时成本为 0`() {
        assertEquals(0L, ApiCost.estimateCostMicroUsd("deepseek-chat", TokenUsage(0, 0)))
    }

    @Test
    fun `负数 tokens 夹到 0，不产生负成本`() {
        val cost = ApiCost.estimateCostMicroUsd("deepseek-chat", TokenUsage(-500, -20))
        assertEquals(0L, cost)
    }

    @Test
    fun `单价为负数时不算出负钱`() {
        val price = ModelPrice(inputMicroUsdPerMillion = -100L, outputMicroUsdPerMillion = -1L)
        assertEquals(0L, ApiCost.estimateMicroUsd(price, TokenUsage(10_000, 10_000)))
    }

    // ------------------------------------------------------------ 取整

    @Test
    fun `micro-USD 四舍五入，1 个 token 不到 1 micro，2 个 token 进位`() {
        // deepseek-chat 输入 270_000 micro / 百万 → 1 token = 0.27 micro（舍）
        assertEquals(0L, ApiCost.estimateCostMicroUsd("deepseek-chat", TokenUsage(1, 0)))
        // 2 token = 0.54 micro（进）
        assertEquals(1L, ApiCost.estimateCostMicroUsd("deepseek-chat", TokenUsage(2, 0)))
    }

    @Test
    fun `一万 token 的整数取整结果可预测`() {
        // 10_000 * 270_000 / 1_000_000 = 2700 micro-USD
        assertEquals(2_700L, ApiCost.estimateCostMicroUsd("deepseek-chat", TokenUsage(10_000, 0)))
    }

    @Test
    fun `输入输出分别取整再相加，不是先加 tokens`() {
        val usage = TokenUsage(promptTokens = 1, completionTokens = 1)
        // 0.27 舍成 0；1.10 进成 1
        assertEquals(1L, ApiCost.estimateCostMicroUsd("deepseek-chat", usage))
    }

    // ------------------------------------------------------------ 前缀匹配优先级

    @Test
    fun `长前缀优先：gpt-4o-mini 不会被 gpt-4o 抢走`() {
        assertEquals(150_000L, ApiCost.basePriceFor("gpt-4o-mini").inputMicroUsdPerMillion)
        assertEquals(2_500_000L, ApiCost.basePriceFor("gpt-4o").inputMicroUsdPerMillion)
    }

    @Test
    fun `大小写与空白不影响匹配`() {
        assertEquals(
            ApiCost.basePriceFor("deepseek-chat"),
            ApiCost.basePriceFor("  DeepSeek-Chat  ")
        )
    }

    // ------------------------------------------------------------ usage 解析（三种协议）

    @Test
    fun `OpenAI 的 usage 能认出来`() {
        val body = """
            {"choices":[{"message":{"content":"在"}}],
             "usage":{"prompt_tokens":123,"completion_tokens":45,"total_tokens":168}}
        """.trimIndent()
        assertEquals(TokenUsage(123, 45), ApiCost.parseUsage(body))
    }

    @Test
    fun `Gemini 的 usageMetadata 能认出来`() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"在"}]}}],
             "usageMetadata":{"promptTokenCount":200,"candidatesTokenCount":30,"totalTokenCount":230}}
        """.trimIndent()
        assertEquals(TokenUsage(200, 30), ApiCost.parseUsage(body))
    }

    @Test
    fun `Claude 的 input_tokens 与 output_tokens 能认出来`() {
        val body = """{"content":[{"type":"text","text":"在"}],"usage":{"input_tokens":88,"output_tokens":12}}"""
        assertEquals(TokenUsage(88, 12), ApiCost.parseUsage(body))
    }

    @Test
    fun `没有 usage 或坏 JSON 一律当 0，绝不抛异常`() {
        assertEquals(TokenUsage.ZERO, ApiCost.parseUsage("""{"choices":[{"message":{"content":"在"}}]}"""))
        assertEquals(TokenUsage.ZERO, ApiCost.parseUsage("not json at all"))
        assertEquals(TokenUsage.ZERO, ApiCost.parseUsage(""))
        assertEquals(TokenUsage.ZERO, ApiCost.parseUsage(null))
    }

    @Test
    fun `usage 里出现负数或字符串数字也不崩`() {
        assertEquals(TokenUsage(10, 0), ApiCost.parseUsage("""{"usage":{"prompt_tokens":10,"completion_tokens":-3}}"""))
        assertEquals(TokenUsage(7, 9), ApiCost.parseUsage("""{"usage":{"prompt_tokens":"7","completion_tokens":"9"}}"""))
    }

    // ------------------------------------------------------------ 月初边界

    @Test
    fun `月初是本地时区的 1 号 0 点`() {
        val now = java.time.ZonedDateTime.of(2026, 3, 17, 23, 30, 0, 0, utc).toInstant().toEpochMilli()
        val start = ApiCost.monthStart(now, utc)
        val expected = java.time.ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(expected, start)
    }

    @Test
    fun `1 号 0 点整点算本月第一天`() {
        val firstMoment = java.time.ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(firstMoment, ApiCost.monthStart(firstMoment, utc))
        // 差 1 毫秒就还是上个月
        val justBefore = firstMoment - 1
        assertEquals(
            java.time.ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, utc).toInstant().toEpochMilli(),
            ApiCost.monthStart(justBefore, utc)
        )
    }

    @Test
    fun `12 月的下个月是次年 1 月`() {
        val now = java.time.ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, utc).toInstant().toEpochMilli()
        val next = ApiCost.nextMonthStart(now, utc)
        assertEquals(
            java.time.ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, utc).toInstant().toEpochMilli(),
            next
        )
    }

    // ------------------------------------------------------------ 分类归一

    @Test
    fun `分类常量宽松归一，不认识的就是 UNKNOWN`() {
        assertEquals(UsageKind.CHAT, UsageKind.normalize(" chat "))
        assertEquals(UsageKind.CONSOLIDATION, UsageKind.normalize("consolidation"))
        assertEquals(UsageKind.UNKNOWN, UsageKind.normalize("billing"))
        assertEquals(UsageKind.UNKNOWN, UsageKind.normalize(null))
        assertEquals("整理记忆", UsageKind.label(UsageKind.CONSOLIDATION))
        assertEquals("聊天", UsageKind.label(UsageKind.CHAT))
    }
}
