package com.myfriend.app.data.repo

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.myfriend.app.data.local.ProactiveRuleDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用一次性 WorkRequest 在「最近一条规则的触发时间」醒来，跑完由 Worker 再排下一次。
 * 这样既不用精确闹钟权限，也能在系统重启后自动恢复。
 */
@Singleton
class ProactiveScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleDao: ProactiveRuleDao
) {
    suspend fun ensureScheduled() {
        val next = runCatching { ruleDao.nextTriggerAt() }.getOrNull()
        val manager = WorkManager.getInstance(context)
        if (next == null) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = (next - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ProactiveWorker.KEY_TRIGGER_AT to next))
            .addTag(TAG)
            .build()
        manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "myfriend-proactive-next"
        const val TAG = "proactive"
    }
}
