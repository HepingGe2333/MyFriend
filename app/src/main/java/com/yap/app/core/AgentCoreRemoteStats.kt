package com.yap.app.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 远程决策客户端的可观测指标（审计 P2-7 / 0.11.1）。
 *
 * 0.11.0 的回退只有一行 logcat：`RemoteAgentCoreClient` 把**所有**失败都吞掉换成进程内结果，
 * 于是「远端从来没接上」和「远端偶尔抖一下」在日志之外完全分不开，也没有任何数字能看。
 *
 * 这里只记计数（不记 URL、不记请求体、不记 Key——安全审计要求日志里不能出现这些）：
 * - [fallbackCount]：回退到进程内的总次数；
 * - [successCount]：远程真的成功的次数；
 * - [fallbackCount] 按 action 细分，能看出是哪个决策接口在失败。
 *
 * 进程内单例、线程安全；测试可以直接 [reset] 后断言。
 */
object AgentCoreRemoteStats {

    private val fallbacks = AtomicLong()
    private val successes = AtomicLong()
    private val perAction = ConcurrentHashMap<String, AtomicLong>()

    /** 远程调用失败、已回退进程内。 */
    fun recordFallback(action: String) {
        fallbacks.incrementAndGet()
        perAction.getOrPut(action) { AtomicLong() }.incrementAndGet()
    }

    /** 远程调用成功（没回退）。 */
    fun recordSuccess(action: String) {
        successes.incrementAndGet()
    }

    fun fallbackCount(): Long = fallbacks.get()

    fun successCount(): Long = successes.get()

    fun fallbackCount(action: String): Long = perAction[action]?.get() ?: 0L

    /** 清空计数（只给测试用；运行时没有调用点）。 */
    fun reset() {
        fallbacks.set(0L)
        successes.set(0L)
        perAction.clear()
    }
}
