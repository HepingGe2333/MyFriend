package com.myfriend.app.domain

/**
 * 用户气泡上那个小小的「已读」到底该不该出现。
 *
 * 规格 §2.3 的条件有两个：模型**真的看到了**（readAt 非空），并且**这一轮没有助手回复**。
 * 后半个条件是为了避免每条用户消息都挂一个「已读」——那读起来像回执系统，
 * 而不是一个正在和你说话的人。已读也不保证会回（defer_reply / 睡着 / 免打扰）。
 *
 * 做成纯函数是因为这两条判断很容易在 UI 里写成「只要有 readAt 就显示」，
 * 而那种错误肉眼几乎看不出来，只有真机上一屏消息全带「已读」时才会觉得不对劲。
 *
 * 0.11.0：第二个条件改为**一次算好的**时间戳（[latestAssistantAt]），界面每条气泡只做
 * 一次比较。以前每个气泡都 `state.messages.any { … }` 扫整个已加载窗口，把历史翻完之后
 * 每帧是 O(n²) 次比较（审计 P2-10）。
 */
object ReadReceipt {

    fun shouldShow(readAt: Long?, hasAssistantAfter: Boolean): Boolean =
        readAt != null && readAt > 0L && !hasAssistantAfter

    /**
     * 界面用：`lastAssistantAt` 是「窗口里最后一条可计入的助手消息」的 createdAt（没有为 null）。
     *
     * 语义与「遍历整个窗口找有没有更晚的助手消息」完全等价（见 `ReadReceiptTest` 的等价性用例），
     * 但每条气泡只做一次比较。
     */
    fun shouldShow(readAt: Long?, userCreatedAt: Long, lastAssistantAt: Long?): Boolean =
        shouldShow(readAt, lastAssistantAt != null && lastAssistantAt > userCreatedAt)

    /**
     * 从「窗口里的助手消息时间戳」算出界面上要用的那个数。
     *
     * 传进来的只该是 **role = ASSISTANT 且 status != ERROR** 的 createdAt（错误气泡不算回复）。
     * 一次 O(n) 计算，之后每条用户气泡 O(1)。
     */
    fun latestAssistantAt(assistantCreatedAts: List<Long>): Long? =
        assistantCreatedAts.maxOrNull()
}
