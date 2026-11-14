package com.myfriend.app.network

import android.util.Log
import com.myfriend.app.domain.LlmConcurrencyGate
import com.myfriend.app.domain.NetworkErrorKind
import com.myfriend.app.domain.LlmLane
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 [LlmConcurrencyGate] 接到真实调用上：拿位、等超时、归还、记日志。
 *
 * 超时是产品决定，不是技术参数：
 * - 聊天等 60 秒。用户发完消息，60 秒还没排上，那说明后台确实把三个位都占了（理论上不会），
 *   或者网络堆了很多请求；再等下去不如让他重发一次。
 * - 后台等 20 秒。抽取、整理、打分这些事晚一点做没有损失，等不到就放弃这一次，
 *   记一条日志，绝不弹错误给用户——用户不该知道自己「被打分」这件事失败了。
 */
@Singleton
class LlmGatekeeper @Inject constructor() {

    private val gate = LlmConcurrencyGate()

    /** 测试与诊断用：现在几个在跑、几个在排队。 */
    suspend fun activeCounts(): Pair<Int, Int> = gate.activeCounts()

    suspend fun <T> withPermit(lane: LlmLane, block: suspend () -> T): T {
        val timeoutMs = if (lane == LlmLane.CHAT) CHAT_WAIT_MS else BACKGROUND_WAIT_MS
        val admitted = withTimeoutOrNull(timeoutMs) {
            gate.acquire(lane)
            true
        } ?: false
        if (!admitted) {
            // 只进日志，不进界面：写在 Log.w(...) 同一行，中文日志守卫认得出来。
            if (lane == LlmLane.CHAT) {
                Log.w(TAG, "聊天等并发位超过 ${CHAT_WAIT_MS / 1000} 秒，放弃这一次请求")
            } else {
                Log.w(TAG, "后台任务等并发位超过 ${BACKGROUND_WAIT_MS / 1000} 秒，放弃这一次请求")
            }
            throw ApiException(
                if (lane == LlmLane.CHAT) NetworkErrorKind.ChatQueueBusy else NetworkErrorKind.BackgroundQueueBusy
            )
        }
        return try {
            block()
        } finally {
            gate.release(lane)
        }
    }

    private companion object {
        const val TAG = "LlmGatekeeper"
        const val CHAT_WAIT_MS = 60_000L
        const val BACKGROUND_WAIT_MS = 20_000L
    }
}
