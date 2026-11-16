package com.yap.app.data.repo

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 兜底的「想一下」：前台服务被系统掐掉时，至少每 15 分钟还能想一次。
 * 前台服务正常时它也会跑，[AgentLoop] 自己会判断要不要真的做什么，不会重复打扰。
 */
@HiltWorker
class AgentTickWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val agentLoop: AgentLoop
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { agentLoop.tick() }
        return Result.success()
    }
}
