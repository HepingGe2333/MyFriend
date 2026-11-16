package com.yap.app.domain

/**
 * 通知深链的落点校验（安全 P2-7 / 0.11.1 审计 P2-6）。
 *
 * ## 为什么不能直接信 extra
 *
 * `MainActivity` 是 `exported="true"` 的 LAUNCHER Activity，没有权限保护：
 * 任意已安装应用都能 `startActivity(MainActivity + extra_session_id=<任意字符串>)`。
 * 0.11.0 之前 `onCreate`/`onNewIntent` 把它直接交给导航，于是：
 *
 * - 伪造的 id → 空白会话页（发送会被 `SESSION_NOT_FOUND` 拒绝，但用户看到的是「他不见了」）；
 * - 真实但不属于当前人格的 id → 把 App 强推到别人的会话、顺带 `markRead` + 撤通知。
 *
 * 本 App 是单用户本地库，不存在「越权读别人数据」，所以这是 UI 可控性/完整性层面的问题；
 * 修法也按这个口径：**字符串不可信**，必须同时满足
 * 1. 非空；
 * 2. 这台手机上真的有这个会话（`sessionPersonaId != null`）；
 * 3. 该会话属于当前的 `activePersonaId`（没有 activePersona 时不认）。
 *
 * 纯函数，不碰数据库：DAO 查询在 `ChatRepository.resolveNotificationSession` 里做，
 * 单测直接钉住各种输入 → 输出。
 */
object NotificationDeepLink {

    /**
     * @param raw intent 里带进来的会话 id（不可信）
     * @param sessionPersonaId 该会话真实所属的人格；会话不存在时传 null
     * @param activePersonaId 本机当前「正在聊的人」；没有时传 null
     * @return 允许导航的会话 id；任何一条不满足就返回 null（界面停在首页）
     */
    fun sessionIdToOpen(
        raw: String?,
        sessionPersonaId: String?,
        activePersonaId: String?
    ): String? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        val owner = sessionPersonaId?.trim().orEmpty()
        if (owner.isEmpty()) return null
        val active = activePersonaId?.trim().orEmpty()
        if (active.isEmpty() || active != owner) return null
        return candidate
    }
}
