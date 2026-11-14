package com.myfriend.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次 LLM 调用属于哪个车道：用户在等他回话，还是他背着你做别的事。 */
enum class LlmLane { CHAT, BACKGROUND }

/**
 * 全局 LLM 并发闸：全局最多 3 个，后台最多占 2 个，聊天永远能进剩下的那 1 个。
 *
 * 为什么是两层而不是一个 3 的信号量：
 * 后台任务（抽取 / 整理 / 打分 / 思考 / 回忆重构 / 历史压缩）会连着排很多条，
 * 如果它们能占满 3 个位，用户发一条消息就要排队等它们跑完——聊天变成了「抢资源」，
 * 那种卡顿用户会直接归因到「他不想理我」。留一个位给聊天，是这个产品最该保的底。
 *
 * 为什么不用三个信号量凑：需要「聊天优先、后台名额独立计数」的语义，
 * `Semaphore` 的 FIFO 会把聊天排在一堆等后台名额的任务后面，反而破坏保底。
 * 这里用一把互斥锁 + 两份显式计数 + 等待队列，行为完全确定，也方便在 JVM 上直接测。
 *
 * 这个类不碰网络、不碰 Android、不读时钟：超时由调用方（[com.myfriend.app.network.LlmGatekeeper]）控制。
 * 它只回答一个问题：现在能不能进；不能进就排队，直到有人 release。
 */
class LlmConcurrencyGate(
    private val globalLimit: Int = DEFAULT_GLOBAL_LIMIT,
    private val backgroundLimit: Int = DEFAULT_BACKGROUND_LIMIT
) {

    init {
        require(globalLimit >= 1) { "全局并发上限至少 1" }
        require(backgroundLimit >= 0) { "后台名额不能为负" }
    }

    private class Waiter(val lane: LlmLane) {
        val ready = CompletableDeferred<Unit>()
    }

    private val mutex = Mutex()
    private val waiters = ArrayDeque<Waiter>()
    private var activeTotal = 0
    private var activeBackground = 0

    /** 当前正在跑的总数与后台数（测试与日志用）。 */
    suspend fun activeCounts(): Pair<Int, Int> = mutex.withLock { activeTotal to activeBackground }

    suspend fun queuedCount(): Int = mutex.withLock { waiters.size }

    /**
     * 拿一个位；拿不到就排队。协程被取消时会把队列里的自己删掉，
     * 不会留下一个「永远等不到」的占位者。
     */
    suspend fun acquire(lane: LlmLane) {
        var waiter: Waiter? = null
        mutex.withLock {
            if (canAdmit(lane)) {
                admit(lane)
            } else {
                waiter = Waiter(lane).also { waiters.addLast(it) }
            }
        }
        val queued = waiter ?: return
        try {
            queued.ready.await()
        } catch (cancel: CancellationException) {
            mutex.withLock {
                if (queued.ready.isCompleted) {
                    // 取消和放行撞在一起：位置已经算给这个等待者了，要还回去。
                    releaseLocked(lane)
                    drainLocked()
                } else {
                    waiters.remove(queued)
                }
            }
            throw cancel
        }
    }

    /** 归还一个位，并尽量放行队列里的下一个。 */
    suspend fun release(lane: LlmLane) {
        mutex.withLock {
            releaseLocked(lane)
            drainLocked()
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun canAdmit(lane: LlmLane): Boolean {
        if (activeTotal >= globalLimit) return false
        return when (lane) {
            LlmLane.CHAT -> true
            LlmLane.BACKGROUND -> activeBackground < backgroundLimit
        }
    }

    private fun admit(lane: LlmLane) {
        activeTotal++
        if (lane == LlmLane.BACKGROUND) activeBackground++
    }

    private fun releaseLocked(lane: LlmLane) {
        // 防御性下限：release 的次数多于 acquire 是调用方的 bug，但不能因此把计数压成负数、
        // 让之后所有请求都以为还有空位。宁可记成 0，下一个请求自己去排队。
        activeTotal = (activeTotal - 1).coerceAtLeast(0)
        if (lane == LlmLane.BACKGROUND) activeBackground = (activeBackground - 1).coerceAtLeast(0)
    }

    /**
     * 放行尽可能多的等待者：先放聊天（保底语义），再放后台。
     *
     * 不用「一次只放一个」：release 之后可能正好能同时容纳多人，
     * 逐个唤醒会让后面的请求白等一个调度周期。
     */
    private fun drainLocked() {
        if (waiters.isEmpty()) return
        val iterator = waiters.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (waiter.lane != LlmLane.CHAT) continue
            if (!canAdmit(waiter.lane)) continue
            iterator.remove()
            admit(waiter.lane)
            waiter.ready.complete(Unit)
        }
        val backgroundIterator = waiters.iterator()
        while (backgroundIterator.hasNext()) {
            val waiter = backgroundIterator.next()
            if (!canAdmit(waiter.lane)) continue
            backgroundIterator.remove()
            admit(waiter.lane)
            waiter.ready.complete(Unit)
        }
    }

    companion object {
        /** 全局上限 3：规格里写死的数。 */
        const val DEFAULT_GLOBAL_LIMIT = 3

        /** 后台最多 2：剩下一个位永远留给聊天。 */
        const val DEFAULT_BACKGROUND_LIMIT = 2
    }
}
