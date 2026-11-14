package com.myfriend.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户气泡「已读」的显示条件（规格 §2.3）。 */
class ReadReceiptTest {

    @Test
    fun `看到了且这一轮没有回复 才显示已读`() {
        assertTrue(ReadReceipt.shouldShow(readAt = 100L, hasAssistantAfter = false))
    }

    @Test
    fun `还没看到 不显示`() {
        assertFalse(ReadReceipt.shouldShow(readAt = null, hasAssistantAfter = false))
        assertFalse(ReadReceipt.shouldShow(readAt = 0L, hasAssistantAfter = false))
    }

    @Test
    fun `已经回了 就不再挂已读`() {
        assertFalse(ReadReceipt.shouldShow(readAt = 100L, hasAssistantAfter = true))
    }
}
