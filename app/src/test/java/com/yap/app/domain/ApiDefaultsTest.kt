package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用默认参数与上下文预算（`spec/api-defaults/README.md`）。
 *
 * 两件事在这里钉住：
 * 1. 默认值的**具体数字**（温度 0.75、上限 1,000,000、单次最大输出 16,384）；
 * 2. 上下文预算计算**真的用了 1M**：窗口减掉单次最大输出再折成字符，
 *    产品侧的收紧值只能让它更小，不能越过这条线。
 */
class ApiDefaultsTest {

    @Test
    fun `默认参数就是规范里的那几个数字`() {
        assertEquals(0.75, ApiDefaults.TEMPERATURE, 0.0)
        assertEquals(1_000_000, ApiDefaults.CONTEXT_WINDOW_TOKENS)
        assertEquals(16_384, ApiDefaults.MAX_OUTPUT_TOKENS)
        assertEquals(1.0, ApiDefaults.TOP_P, 0.0)
        assertEquals(0.0, ApiDefaults.FREQUENCY_PENALTY, 0.0)
        assertEquals(0.0, ApiDefaults.PRESENCE_PENALTY, 0.0)
    }

    @Test
    fun `输入字符容量由 1M 窗口减去最大输出再折算`() {
        // (1_000_000 - 16_384) × 2 = 1_967_232
        assertEquals(1_967_232, ApiDefaults.inputCharBudget())
    }

    @Test
    fun `预算公式的边界 窗口小于输出时输入容量为 0 而不是负数`() {
        assertEquals(0, ApiDefaults.inputCharBudget(contextWindowTokens = 100, maxOutputTokens = 16_384))
        assertEquals(0, ApiDefaults.inputCharBudget(contextWindowTokens = 0, maxOutputTokens = 16_384))
        assertEquals(0, ApiDefaults.inputCharBudget(contextWindowTokens = -5, maxOutputTokens = 16_384))
    }

    @Test
    fun `更大的窗口给出更大的容量`() {
        val small = ApiDefaults.inputCharBudget(contextWindowTokens = 8_192, maxOutputTokens = 4_096)
        val large = ApiDefaults.inputCharBudget(contextWindowTokens = 1_000_000, maxOutputTokens = 4_096)
        assertTrue("$small 应该小于 $large", small < large)
        assertEquals((8_192 - 4_096) * ApiDefaults.CHARS_PER_TOKEN, small)
    }

    @Test
    fun `产品收紧值不会越过 1M 折算出的上限`() {
        // 聊天目前的实际口径：2.8 万字，远小于 1M 折算值 → 取 2.8 万。
        assertEquals(28_000, ApiDefaults.requestCharBudget(28_000))
        // 有人把收紧值调得离谱大时，仍然被 1M 窗口截住（这条就是「用 1M 计算」的证据）。
        assertEquals(ApiDefaults.inputCharBudget(), ApiDefaults.requestCharBudget(Int.MAX_VALUE))
        assertEquals(ApiDefaults.inputCharBudget(), ApiDefaults.requestCharBudget(5_000_000))
    }

    @Test
    fun `收紧值非法时不返回负数`() {
        assertEquals(0, ApiDefaults.requestCharBudget(-1))
        assertEquals(0, ApiDefaults.requestCharBudget(0))
    }
}
