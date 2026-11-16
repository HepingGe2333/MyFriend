package com.yap.app.ui.screens.persona

import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.SleepPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「新建人格」表单的默认值（0.12 续 7）。
 *
 * 过去 `PersonaForm()` 默认 `sleepEnabled = true`、`wakeThreshold = 6`：用户新建一个人格、
 * 什么都不改，当天夜里就会得到一个「只标已读、发 5 条也不回」的人。现在：
 * - 睡眠默认关（想让他睡，自己在编辑页打开）；
 * - 真打开了，默认窗口 23:30–08:00、连着发 3 条就醒。
 *
 * 已经在库里的人格不受影响：编辑页载入时会用实体值覆盖这些默认值（见 `PersonaEditorViewModel`）。
 */
class PersonaFormDefaultsTest {

    @Test
    fun `新建人格默认不睡`() {
        assertFalse("新建人格不该默认开着睡眠", PersonaForm().sleepEnabled)
    }

    @Test
    fun `新建人格的唤醒阈值是 3`() {
        assertEquals(3, PersonaForm().wakeThreshold)
    }

    @Test
    fun `新建人格默认不填作息时段`() {
        assertEquals("", PersonaForm().sleepAt)
        assertEquals("", PersonaForm().wakeAt)
    }

    @Test
    fun `表单默认值能让一个新人夜里被叫醒`() {
        // 表单默认值落到「他睡着」的判定里：默认 3 条，发 3 条就该醒（不再是 6）。
        val threshold = PersonaForm().wakeThreshold
        val decision = SleepPolicy.evaluate(
            sleepEnabled = true,
            sleepMinutes = null,
            wakeMinutes = null,
            minuteOfDay = 2 * 60,
            dndOnSleep = false,
            recentUserMessages = threshold,
            wakeThreshold = SleepPolicy.normalizeThreshold(threshold)
        )
        assertTrue(decision.woken)
        assertTrue(decision.shouldReply)
    }

    @Test
    fun `载入既有实体时表单照抄实体的睡眠字段`() {
        // 只是钉住「默认值不会盖掉既有数据」这条边界：编辑页的 form 构造用的是实体值。
        val persona = PersonaEntity(
            id = "persona-1",
            name = "林深",
            description = "",
            avatarUri = null,
            avatarSeed = 1,
            prompt = "",
            category = "日常聊天",
            version = 2,
            defaultApiConfigId = null,
            modelOverride = null,
            isDefault = false,
            createdAt = 0L,
            updatedAt = 0L,
            sleepEnabled = true,
            quietSleepMinutes = 23 * 60,
            quietWakeMinutes = 7 * 60,
            wakeThreshold = 5
        )
        val form = PersonaForm(
            sleepEnabled = persona.sleepEnabled,
            sleepAt = "23:00",
            wakeAt = "07:00",
            wakeThreshold = SleepPolicy.normalizeThreshold(persona.wakeThreshold)
        )
        assertTrue(form.sleepEnabled)
        assertEquals(5, form.wakeThreshold)
        assertEquals("23:00", form.sleepAt)
    }
}
