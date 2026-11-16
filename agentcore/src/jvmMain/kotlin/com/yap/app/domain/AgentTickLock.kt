package com.yap.app.domain

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「同一时刻只能有一拍思考」的非重入闸。
 *
 * `AgentLoop.tick` 有三条入口：前台服务心跳（约 30 秒一拍）、WorkManager 兜底
 * （15 分钟一次，但**不经过**心跳的下限与快照缓存）、主动规则到点的一次性 Worker。
 * 三条入口都会走到 `tick` 里同一段「今天说够没 / 隔够没 → 发一条」的 check-then-act；
 * 没有互斥时两次并发 tick 都能通过检查，然后各发一条几乎一样的消息。
 *
 * 用 `AtomicBoolean` 而不是协程 Mutex：三条入口可能在不同线程 / 不同调度器上，
 * 这里只需要「拿不到就跳过这一拍」，不需要排队（排队只会把两拍叠成一条，没意义）。
 *
 * 纯 JVM（不碰 Android / 协程），可用两个线程直接验。
 */
class AgentTickLock {

    private val held = AtomicBoolean(false)

    /** 是否有人正持有。测试用。 */
    val isHeld: Boolean get() = held.get()

    /**
     * 抢占一拍。
     *
     * @return 抢到返回 true，必须由**同一个调用方**在 finally 里 [exit]；
     *         已经有人在想就直接返回 false（这一拍按「没发生」收场，不排队）。
     *
     * 分开 tryEnter/exit 而不是 `withLock { }`，是因为 `AgentLoop.tick` 的函数体是
     * suspend 的（要读设置、调模型），不能塞进普通 lambda；语义与 withLock 相同。
     */
    fun tryEnter(): Boolean = held.compareAndSet(false, true)

    /** 释放。重复释放是安全的（幂等）。 */
    fun exit() {
        held.set(false)
    }

    /**
     * 拿到锁就跑 [block]，拿不到立刻返回 null（这一拍照旧按「没发生」收场）。
     *
     * [block] 抛异常时锁一定会释放——否则一次失败会让 thinking 永远停摆。
     */
    fun <T> withLock(block: () -> T): T? {
        if (!tryEnter()) return null
        return try {
            block()
        } finally {
            exit()
        }
    }
}
