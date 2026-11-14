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
 */
object ReadReceipt {

    fun shouldShow(readAt: Long?, hasAssistantAfter: Boolean): Boolean =
        readAt != null && readAt > 0L && !hasAssistantAfter
}
