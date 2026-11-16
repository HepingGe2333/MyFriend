package com.yap.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 种子人格的默认值（0.12 续 7）。
 *
 * 这是「新装第一晚发消息没人理」的源头之一：种子人格过去没有显式设 `sleepEnabled`，
 * 于是取了 Entity 默认的 `true` —— 一到 23:30 就只标已读、连着发到第 6 条才醒。
 * 现在种子显式给 `false`（睡眠是可选功能，用户在人格编辑里打开才算数）。
 *
 * 用 `SleepPersonas.build(now) { ... }` 这个资源解耦的重载跑**真的构造逻辑**：
 * 纯 JVM 单测没有 `Context`，但默认值该测的是构造结果，不是「源码里写了 false」。
 */
class SeedPersonasTest {

    private fun seeds(): List<PersonaEntity> =
        SeedPersonas.build(now = 1_700_000_000_000L) { "seed-$it" }

    @Test
    fun `种子人格默认不睡`() {
        val seeds = seeds()
        assertEquals("还是一共三个人格", 3, seeds.size)
        seeds.forEach { persona ->
            assertFalse("种子人格「${persona.name}」不该默认开着睡眠", persona.sleepEnabled)
            assertEquals("种子人格没设作息", null, persona.quietSleepMinutes)
            assertEquals("种子人格没设作息", null, persona.quietWakeMinutes)
        }
    }

    @Test
    fun `种子人格的唤醒阈值用新的默认值 3`() {
        seeds().forEach { persona ->
            assertEquals("睡眠可以后来打开；打开后发 3 条就该叫醒他", 3, persona.wakeThreshold)
        }
    }

    @Test
    fun `种子人格的其它字段保持原样`() {
        val seeds = seeds()
        assertEquals("只有第一个人格是默认人格", 1, seeds.count { it.isDefault })
        assertTrue("第一个人格是默认人格", seeds.first().isDefault)
        seeds.forEach { persona ->
            assertEquals(1, persona.version)
            assertFalse("种子人格不该带 API 配置", persona.defaultApiConfigId != null)
        }
    }
}
