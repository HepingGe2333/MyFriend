package com.yap.app.service

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yap.app.BuildConfig
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.repo.AgentTickWorker
import com.yap.app.domain.ProactiveDiagnostics
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
 * 失败了还有 WorkManager 兜底，不会崩也不会悄无声息 —— 失败会打一条 `Log.w`，
 * 因为「后台限制」正是真机上「他不主动找我」最常见的外部原因。
 */
@Singleton
class AgentLoopController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scheduler: com.yap.app.data.repo.ProactiveScheduler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 设置变化时调用；幂等。 */
    fun sync() {
        scope.launch { applyFromSettings() }
    }

    /** 观察设置，自动跟随开关（App 启动时装一次即可）。 */
    fun observe() {
        scope.launch {
            runCatching {
                settingsRepository.settings
                    .map { it.agentLoopEnabled }
                    .distinctUntilChanged()
                    .collect { apply(it) }
            }.onFailure { error ->
                // 观察流本身断了（例如 DataStore 读失败）时至少留下一条日志：
                // 没有它，「开关拨了没反应」在真机上完全不可见。
                Log.w(LOG_TAG, "常驻循环开关的观察流断了，保留当前状态", error)
            }
        }
    }

    fun startIfEnabled() {
        scope.launch { applyFromSettings() }
    }

    /**
     * 读一次设置再决定。
     *
     * **读失败不等于用户关掉了它。** 以前这里是 `getOrDefault(false)`：一次 DataStore 抖动
     * 就会被当成「关闭」，直接把前台服务和 WorkManager 都停掉；现在交给
     * [ProactiveDiagnostics.loopSwitch] 三态处理，读不出来就保持现状（KEEP）。
     */
    private suspend fun applyFromSettings() {
        val enabled = runCatching { settingsRepository.settings.first().agentLoopEnabled }.getOrNull()
        apply(enabled)
    }

    private suspend fun apply(enabled: Boolean?) {
        // 规则排期与他是死是活无关，始终维持。
        runCatching { scheduler.ensureScheduled() }
        when (ProactiveDiagnostics.loopSwitch(enabled)) {
            ProactiveDiagnostics.LoopSwitch.RUN -> {
                runCatching { AgentLoopService.start(context) }
                enqueuePeriodic()
            }

            ProactiveDiagnostics.LoopSwitch.STOP -> {
                runCatching { AgentLoopService.stop(context) }
                runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
            }

            ProactiveDiagnostics.LoopSwitch.KEEP -> {
                // 读不到设置：不动服务、不动兜底。下一次设置变化或下一轮唤起会再判断。
                Log.w(LOG_TAG, "读不到常驻循环开关，保持当前状态（不当成关闭）")
            }
        }
    }

    private fun enqueuePeriodic() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<AgentTickWorker>(15, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "WorkManager 兜底已排期：$WORK_NAME，每 15 分钟（ExistingPeriodicWorkPolicy.KEEP）")
            }
        }.onFailure { error ->
            // 排不上兜底是「后台完全不会再想」的硬故障，必须能看见。
            Log.w(LOG_TAG, "WorkManager 兜底排期失败：$WORK_NAME", error)
        }
    }

    companion object {
        const val WORK_NAME = "yap-agent-tick"
        const val TAG = "agent"

        /** 诊断日志的 tag：`adb logcat -s AgentLoopController`。 */
        private const val LOG_TAG = "AgentLoopController"
    }
}
