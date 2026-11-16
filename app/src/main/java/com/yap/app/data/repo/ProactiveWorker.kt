package com.yap.app.data.repo

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.service.AgentLoopController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * 系统的「叫醒」入口。
 *
 * 常驻循环开着时，这里只是替他把前台服务再拉一次、并顺手想一次（服务被系统掐掉也能续上）；
 * 关着时退回 1.2 的老行为：到点直接按规则发消息。
 */
@HiltWorker
class ProactiveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ProactiveRepository,
    private val scheduler: ProactiveScheduler,
    private val agentLoop: AgentLoop,
    private val settingsRepository: SettingsRepository,
    private val controller: AgentLoopController
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val now = System.currentTimeMillis()
            runCatching { repository.browseIdlePersonas(now) }
            val agentEnabled = runCatching {
                settingsRepository.settings.first().agentLoopEnabled
            }.getOrDefault(false)
            val fired = if (agentEnabled) {
                // 让循环决定要不要说话（会把到点的规则变成带窗口的意向）。
                runCatching { agentLoop.tick(now) }.isSuccess
                // 服务可能已经被系统掐了，回来的时候顺手拉一下。
                runCatching { controller.startIfEnabled() }
                0
            } else {
                repository.runDue(now)
            }
            scheduler.ensureScheduled()
            Result.success(workDataOf(KEY_FIRED to fired))
        }.getOrElse {
            scheduler.ensureScheduled()
            Result.success()
        }
    }

    companion object {
        const val KEY_TRIGGER_AT = "trigger_at"
        const val KEY_FIRED = "fired"
    }
}
