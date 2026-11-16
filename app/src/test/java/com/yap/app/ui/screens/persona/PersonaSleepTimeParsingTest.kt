package com.yap.app.ui.screens.persona

import com.yap.app.domain.SleepPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作息时间解析（0.12 续 8）。`24:00` 曾经被当成非法值 → 存 null → SleepPolicy 回退 23:30，
 * 于是「设了 24:00–08:00 的人」在 23:40 就已经在睡、只读不回。这里钉住修复与反向用例。
 */
class PersonaSleepTimeParsingTest {

    @Test
    fun `24点整等于午夜`() {
        assertEquals(0, PersonaEditorViewModel.parseClock("24:00"))
        assertEquals(0, PersonaEditorViewModel.parseClock("24：00"))
        assertEquals(0, PersonaEditorViewModel.parseClock(" 24:00 "))
    }

    @Test
    fun `其它非法时间仍然返回 null`() {
        assertNull(PersonaEditorViewModel.parseClock("24:30"))
        assertNull(PersonaEditorViewModel.parseClock("25:00"))
        assertNull(PersonaEditorViewModel.parseClock("12:60"))
        assertNull(PersonaEditorViewModel.parseClock("abc"))
        assertNull(PersonaEditorViewModel.parseClock(""))
    }

    @Test
    fun `24点到8点的窗口在23点40是清醒的`() {
        val minute = 23 * 60 + 40
        assertFalse(SleepPolicy.inWindow(minute, 0, 8 * 60))
        // 反例：解析失败回退默认 23:30–08:00 时，23:40 已经在睡。
        assertTrue(SleepPolicy.inWindow(minute, SleepPolicy.DEFAULT_SLEEP_MINUTES, SleepPolicy.DEFAULT_WAKE_MINUTES))
    }

    @Test
    fun `24点到8点的窗口在凌晨1点是睡着的`() {
        assertTrue(SleepPolicy.inWindow(60, 0, 8 * 60))
    }
}
