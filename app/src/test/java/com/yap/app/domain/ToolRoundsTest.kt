package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 0.15 工具轮数：默认 5、模型可指定、硬上限 15。
 */
class ToolRoundsTest {

    @Test
    fun `默认五轮`() {
        assertEquals(5, ToolRounds.DEFAULT)
        assertEquals(5, ToolRounds.resolve(null))
        assertNull(ToolRounds.parse(null))
        assertNull(ToolRounds.parse(""))
        assertNull(ToolRounds.parse("  "))
    }

    @Test
    fun `模型指定值被夹到一到十五`() {
        assertEquals(1, ToolRounds.resolve(0))
        assertEquals(1, ToolRounds.resolve(-3))
        assertEquals(8, ToolRounds.resolve(8))
        assertEquals(15, ToolRounds.resolve(15))
        assertEquals(15, ToolRounds.resolve(99))
    }

    @Test
    fun `解析宽松 认数字 字符串 小数与说明文字`() {
        assertEquals(7, ToolRounds.parse("7"))
        assertEquals(7, ToolRounds.parse(" 7 "))
        assertEquals(7, ToolRounds.parse("7.0"))
        assertEquals(7, ToolRounds.parse("rounds=7"))
        assertEquals(12, ToolRounds.parse("12 轮"))
        assertNull(ToolRounds.parse("很多"))
    }

    @Test
    fun `从工具调用里取 rounds 数字与字符串都认`() {
        assertEquals(
            8,
            ToolRounds.requested(ToolInvocation(name = "think", argumentsJson = """{"rounds":8}"""))
        )
        assertEquals(
            8,
            ToolRounds.requested(ToolInvocation(name = "think", argumentsJson = """{"rounds":"8"}"""))
        )
        assertNull(ToolRounds.requested(ToolInvocation(name = "think", argumentsJson = "{}")))
    }

    @Test
    fun `一轮里取见过的最大值 但都先夹取`() {
        assertEquals(8, ToolRounds.merge(current = 5, requested = 8))
        assertEquals(8, ToolRounds.merge(current = 8, requested = null))
        assertEquals("后来的小值不缩预算", 8, ToolRounds.merge(current = 8, requested = 2))
        assertEquals(15, ToolRounds.merge(current = 5, requested = 99))
        assertEquals(5, ToolRounds.merge(current = 5, requested = null))
    }

    @Test
    fun `与共享契约同一口径`() {
        assertEquals(ExperienceContract.DEFAULT_TOOL_ROUNDS, ToolRounds.DEFAULT)
        assertEquals(ExperienceContract.MAX_TOOL_ROUNDS, ToolRounds.MAX)
        assertEquals(ExperienceContract.MIN_TOOL_ROUNDS, ToolRounds.MIN)
        (Int.MIN_VALUE..20).forEach { value ->
            assertEquals(ExperienceContract.clampToolRounds(value), ToolRounds.resolve(value))
        }
    }
}
