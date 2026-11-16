package com.yap.app.domain

/**
 * 聊天页那行「他睡着了（08:00 醒）；连着发几条可以叫醒他」该不该出现（0.12 续 7）。
 *
 * 为什么单独一个纯函数：这条提示曾经**不存在**，于是夜里发消息只看到「已读」，没人知道
 * 他是睡了还是不想理人。它也不能滥用 —— 只有「这一轮确实因为睡着被静默」才有意义：
 *
 * - 他醒着、只是用 `defer_reply` 说了「等下再说」→ 不显示（那会变成一句假话）；
 * - 免打扰里他**不会**被叫醒 → 不显示（叫他「发几条就能醒」是错的）；
 * - 已经回了话、或者时间走到了他的起床点 → 不显示（[visible] 只看当下，界面自然就把它藏了）。
 *
 * 显示的文案由界面按语言资源拼（醒来的钟点来自 `SleepPolicy.wakeClockText`）。
 */
object ChatSleepHint {

    /** 这一轮的原因 + 他此刻的状态 → 要不要显示提示。 */
    fun visible(reason: SilentReason?, presence: Presence): Boolean =
        reason == SilentReason.ASLEEP && presence.kind == PresenceKind.ASLEEP
}
