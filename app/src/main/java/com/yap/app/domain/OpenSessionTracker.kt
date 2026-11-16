package com.yap.app.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记着「此刻屏幕上正开着哪一段对话」，未读数与通知都靠它判断。
 *
 * ## 为什么不是 `@Volatile var`
 *
 * 它是**单槽全局变量**：聊天页 A 的 ViewModel 在 `init` 里写入自己，A 的
 * `NavBackStackEntry` 被弹掉时旧 ViewModel 的 `onCleared` 又要把它清掉。
 * 转场期间「新 VM 写入」发生在「旧 VM 销毁」之前，如果清空是无条件的，
 * 最终值就变成 `null` —— 用户正看着的那个会话被当成没开着：未读 +1、
 * 前台弹出一条「你正在读的消息」的系统通知。
 *
 * 所以清空必须是 **compare-and-clear**：只有当前值仍是自己那段会话时才清。
 * 读-比较-写放在同一把锁里，保证 `open(B)` 与 `clearIfOpen(A)` 交错时
 * 不会出现「B 刚写进去就被 A 清掉」。
 *
 * 这个类刻意不依赖 Android：并发语义可以在 JVM 单测里直接验。
 */
@Singleton
class OpenSessionTracker @Inject constructor() {

    private val lock = Any()

    @Volatile
    private var current: String? = null

    /** 当前打开的会话 id（没有打开时为 null）。 */
    val openSessionId: String?
        get() = current

    /** 打开某段会话（原子写入）。传 null 等价于 [clearAll]。 */
    fun open(sessionId: String?) {
        synchronized(lock) { current = sessionId }
    }

    /**
     * 只在「当前打开的仍是 [sessionId]」时清空。
     *
     * @return 真的清掉了返回 true；当前已经换成别的会话（或已经清空）返回 false，
     *         这时**不能**动它——那正是「旧 ViewModel 销毁不该影响新会话」的保证。
     */
    fun clearIfOpen(sessionId: String): Boolean = synchronized(lock) {
        if (current == sessionId) {
            current = null
            true
        } else {
            false
        }
    }

    /** 无条件清空：只给「删除全部数据」「整库替换」这类确实没有任何会话可开的路径用。 */
    fun clearAll() {
        synchronized(lock) { current = null }
    }

    fun isOpen(sessionId: String): Boolean = current == sessionId
}
