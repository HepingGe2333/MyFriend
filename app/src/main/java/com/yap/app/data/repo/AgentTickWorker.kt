package com.yap.app.data.repo

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yap.app.BuildConfig
import com.yap.app.data.remote.ServerModeRepository
import com.yap.app.domain.LocalAutonomy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 兜底的「想一下」：前台服务被系统掐掉时，至少每 15 分钟还能想一次。
 * 前台服务正常时它也会跑，[AgentLoop] 自己会判断要不要真的做什么，不会重复打扰。
 *
 * 这里不再静默吞掉 tick 的结果：debug 构建下留一行「兜底到底跑没跑、做了什么」，
 * 因为「后台限制」时它是唯一还在动的入口，logcat 里必须看得见它。
 */
@HiltWorker
class AgentTickWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val agentLoop: AgentLoop,
    /** 0.13：服务端模式下不再由本机兜底「想一下」。 */
    private val serverMode: ServerModeRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!LocalAutonomy.shouldRunLocalTick(serverMode.isEnabled())) {
            if (BuildConfig.DEBUG) Log.d(TAG, "服务端模式：跳过本机兜底 tick")
            return Result.success()
        }
        val outcome = runCatching { agentLoop.tick() }
        if (BuildConfig.DEBUG) {
            val failure = outcome.exceptionOrNull()
            val detail = failure?.javaClass?.simpleName
                ?: outcome.getOrNull()?.summary.orEmpty().ifBlank { "no summary" }
            Log.d(TAG, "worker tick: ok=${failure == null} detail=$detail")
        }
        return Result.success()
    }

    private companion object {
        /** 诊断日志 tag：`adb logcat -s AgentTickWorker`。 */
        const val TAG = "AgentTickWorker"
    }
}
