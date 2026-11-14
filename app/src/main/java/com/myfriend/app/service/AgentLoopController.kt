package com.myfriend.app.service

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.data.repo.AgentTickWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「他活着」这件事的总开关。
 *
 * - 开着：起前台服务（半分钟一拍的心跳，绝大多数拍什么都不做），同时挂一个 15 分钟的
 *   WorkManager 兜底 —— 兜底和心跳走的是同一套判断，所以不会因为两条路都在跑就多说话；
 * - 关着：两个都停，他就只在你打开 App 时存在。
 *
 * 注意：心跳不是定时任务，它只是「他此刻想不想你」的判定节拍；
 * 这里不、以后也不要给它加频率/概率这类用户可调的旋钮。
 *
 * 部分系统会拒绝在后台起前台服务（Android 12+），这里一律 runCatching，
 * 失败了还有 WorkManager 兜底，不会崩也不会悄无声息。
 */
@Singleton
class AgentLoopController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scheduler: com.myfriend.app.data.repo.ProactiveScheduler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 设置变化时调用；幂等。 */
    fun sync() {
        scope.launch { apply(runCatching { settingsRepository.settings.first().agentLoopEnabled }.getOrDefault(false)) }
    }

    /** 观察设置，自动跟随开关（App 启动时装一次即可）。 */
    fun observe() {
        scope.launch {
            runCatching {
                settingsRepository.settings
                    .map { it.agentLoopEnabled }
                    .distinctUntilChanged()
                    .collect { apply(it) }
            }
        }
    }

    fun startIfEnabled() {
        scope.launch { apply(runCatching { settingsRepository.settings.first().agentLoopEnabled }.getOrDefault(false)) }
    }

    private suspend fun apply(enabled: Boolean) {
        // 规则排期与他是死是活无关，始终维持。
        runCatching { scheduler.ensureScheduled() }
        if (enabled) {
            runCatching { AgentLoopService.start(context) }
            enqueuePeriodic()
        } else {
            runCatching { AgentLoopService.stop(context) }
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
        }
    }

    private fun enqueuePeriodic() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<AgentTickWorker>(15, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    companion object {
        const val WORK_NAME = "myfriend-agent-tick"
        const val TAG = "agent"
    }
}
