package com.yap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天页那行「他睡着了（08:00 醒）；连着发几条可以叫醒他」的出现条件（0.12 续 7）。
 *
 * 这行提示存在的唯一理由是「用户不知道他为什么只标已读不回」。所以它必须**精确**：
 * 醒着的人用 `defer_reply` 说了「等下再说」时显示「他睡着了」就是在撒谎；
 * 免打扰里叫不醒他时显示「发几条就能叫醒」也是撒谎。
 */
class ChatSleepHintTest {

    private val asleep = Presence(kind = PresenceKind.ASLEEP, text = "睡着了")
    private val thinking = Presence(kind = PresenceKind.THINKING, text = "在想事情")

    @Test
    fun `睡着被静默之后显示`() {
        assertTrue(ChatSleepHint.visible(SilentReason.ASLEEP, asleep))
    }

    @Test
    fun `收到回复之后不再显示`() {
        // 回了话 = 这一轮的 silentReason 已经被清掉（ChatViewModel 在 Started/Completed 清）。
        assertFalse(ChatSleepHint.visible(null, asleep))
    }

    @Test
    fun `他睡醒之后不再显示`() {
        // 时间走过起床点，Presence 会变回别的状态：提示自己消失。
        assertFalse(ChatSleepHint.visible(SilentReason.ASLEEP, thinking))
        assertFalse(ChatSleepHint.visible(SilentReason.ASLEEP, Presence()))
    }

    @Test
    fun `免打扰与晚点回都不显示`() {
        assertFalse("免打扰叫不醒他，不能提示「发几条就能叫醒」", ChatSleepHint.visible(SilentReason.DND, asleep))
        assertFalse("他醒着、只是决定晚点回", ChatSleepHint.visible(SilentReason.DEFERRED, asleep))
    }
}
