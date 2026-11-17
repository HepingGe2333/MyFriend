package com.yap.app.data.repo

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.ProactiveDiagnostics
import com.yap.app.service.AgentLoopController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
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
            // 读失败时**不要猜开关状态**：以前是 `getOrDefault(false)`，一次 DataStore 抖动
            // 就会被当成「循环关了」，于是绕过模型直接按规则发消息（1.2 的老行为）。
            // 现在三态处理：读不出来就这一轮什么都不做，等下一次唤醒再判断。
            val agentEnabled = try {
                settingsRepository.settings.first().agentLoopEnabled
            } catch (cancel: CancellationException) {
                // 取消不是「读不到设置」：必须继续向上抛，否则 Worker 被取消后还在跑。
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "读不到常驻循环开关，这一轮不猜、什么都不做", error)
                null
            }
            val fired = when (ProactiveDiagnostics.workerBranch(agentEnabled)) {
                ProactiveDiagnostics.WorkerBranch.RUN_AGENT_LOOP -> {
                    // 让循环决定要不要说话（会把到点的规则变成带窗口的意向）。
                    runCatching { agentLoop.tick(now) }
                    // 服务可能已经被系统掐了，回来的时候顺手拉一下。
                    runCatching { controller.startIfEnabled() }
                    0
                }

                ProactiveDiagnostics.WorkerBranch.RUN_LEGACY_RULES -> repository.runDue(now)

                ProactiveDiagnostics.WorkerBranch.SKIP -> 0
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

        /** 诊断日志 tag：`adb logcat -s ProactiveWorker`。 */
        private const val TAG = "ProactiveWorker"
    }
}
