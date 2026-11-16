package com.yap.app.domain

/**
 * 「一条助手消息落库之后，要不要标未读、要不要弹系统通知」的唯一判定。
 *
 * 以前这个判断在三处各写了一遍（聊天回复、常驻 agent 主动消息、主动规则），
 * 其中一处漏了「会话正开着」的前置条件——用户看着屏幕还会收到一条自己正在读的
 * 消息的通知。抽到这里之后，三处共用同一套语义，也能在 JVM 单测里逐条钉住。
 *
 * ## 不变量
 *
 * 1. **写库永远要做**（消息、预览、时间戳都不受影响）；
 * 2. 「这段会话此刻在屏幕上开着」→ 标已读、**不弹通知**；
 * 3. 「没开着」或「App 不在前台」→ 未读 +1、弹通知（按 Home 退到桌面时聊天页还挂在
 *    栈上，光看 openSession 会漏掉通知，所以前台也要一起看）。
 *
 * 第 2 条是 0.10.2 修的 P1：`openSessionId` 曾经因为旧 ViewModel 的无条件清空变成
 * null，于是「正在读的会话」被判成没开着。判定本身没变，变的是 `openSessionId`
 * 的写入/清空语义（见 [OpenSessionTracker]）。
 */
object ChatNotificationPolicy {

    /** 这条消息落库时该不该把会话的未读数 +1。 */
    fun shouldMarkUnread(openSessionId: String?, sessionId: String): Boolean =
        openSessionId != sessionId

    /** 这条消息落库后该不该弹系统通知。 */
    fun shouldNotify(openSessionId: String?, sessionId: String, appInForeground: Boolean): Boolean =
        openSessionId != sessionId || !appInForeground

    /**
     * 主动规则（`ProactiveRepository`）用的组合：会话开着时消息已经在屏幕上出现，
     * 只写库、标已读、不弹通知；没开着或不在前台才打扰。
     */
    fun delivery(openSessionId: String?, sessionId: String, appInForeground: Boolean): Delivery =
        Delivery(
            markUnread = shouldMarkUnread(openSessionId, sessionId),
            notify = shouldNotify(openSessionId, sessionId, appInForeground)
        )

    data class Delivery(val markUnread: Boolean, val notify: Boolean)
}
