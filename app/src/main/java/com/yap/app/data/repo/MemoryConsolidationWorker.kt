package com.yap.app.data.repo

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 每天整理一次记忆的入口。
 *
 * 一天一次，挑凌晨 4 点左右（本地时间，人基本都睡了）跑；装上 App 不会立刻跑，
 * 第一次也等到下一个 4 点，免得刚装完就烧掉一次模型额度。
 * 手机没开机 / WorkManager 错过了点，下次开机后系统会自己补跑（这也是"每天一次"能成立的原因）。
 *
 * 1.9 起这是**内置行为**，没有开关：每天到点就整理一次，失败下次再来。
 * 关掉时不再排周期任务，已排的也会空跑一次、什么都不做。
 *
 * 周期任务的工作名是 [WORK_NAME]；设置页「现在整理一下」走另一个工作名 [WORK_NAME_MANUAL]
 * （[enqueueOneTime]），两者互不覆盖。
 */
@HiltWorker
class MemoryConsolidationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val consolidator: MemoryConsolidator
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 1.9：整理记忆是内置行为，没有开关可读；到点就整理，失败明天再来。
        // 一次整理要问模型，必须给个上限：卡住时宁可说「这次没整理完」，也别让界面一直转。
        val results = runCatching {
            withTimeoutOrNull(WORK_TIMEOUT_MILLIS) { consolidator.consolidateAll() }
        }.getOrNull() ?: return Result.success(
            // 整理失败/超时不该让 WorkManager 一直重试烧额度：标记 success，等明天的周期任务。
            workDataOf(KEY_STATUS to STATUS_FAILED)
        )
        val done = results.count { it.didAnything }
        val total = results.fold(
            Counts(0, 0, 0, 0)
        ) { acc, result ->
            Counts(
                acc.kept + result.kept,
                acc.merged + result.merged,
                acc.archived + result.archived,
                acc.forgotten + result.forgotten
            )
        }
        // 排完这一次再把周期任务对齐一下：设置里刚打开开关时也能立刻生效。
        runCatching { schedule(applicationContext) }
        return Result.success(
            workDataOf(
                KEY_STATUS to if (done > 0) STATUS_DONE else STATUS_EMPTY,
                KEY_PERSONAS to done,
                KEY_KEPT to total.kept,
                KEY_MERGED to total.merged,
                KEY_ARCHIVED to total.archived,
                KEY_FORGOTTEN to total.forgotten
            )
        )
    }

    private data class Counts(val kept: Int, val merged: Int, val archived: Int, val forgotten: Int)

    companion object {
        /** 周期任务的唯一工作名：设置页开关 / 取消都靠它。 */
        const val WORK_NAME = "yap-memory-consolidation"

        /**
         * 手动「现在整理一次」的唯一工作名。
         *
         * 必须和周期任务分开：WorkManager 的唯一工作名是共享的，
         * 如果手动任务复用 [WORK_NAME] + REPLACE，就会把每天那次周期整理顶掉。
         */
        const val WORK_NAME_MANUAL = "yap-memory-consolidation-now"

        const val TAG = "memory-consolidation"

        const val KEY_STATUS = "status"
        const val KEY_PERSONAS = "personas"
        const val KEY_KEPT = "kept"
        const val KEY_MERGED = "merged"
        const val KEY_ARCHIVED = "archived"
        const val KEY_FORGOTTEN = "forgotten"
        const val KEY_MESSAGE = "message"

        /** 一次整理最多跑这么久：手动按钮不该变成「永远转」，周期任务也不该死扛。 */
        private const val WORK_TIMEOUT_MILLIS = 120_000L

        const val STATUS_DONE = "done"
        const val STATUS_EMPTY = "empty"
        const val STATUS_DISABLED = "disabled"
        const val STATUS_FAILED = "failed"

        /** 每天几点整理（本地时间）：凌晨 4 点，人基本都睡了，也避开用电高峰。 */
        const val CONSOLIDATION_HOUR = 4

        /** 周期就是一天。 */
        private const val PERIOD_HOURS = 24L

        /** 第一次至少延后这么久，别在安装/升级后立刻跑。 */
        private const val MIN_FIRST_DELAY_MILLIS = 2 * 3_600_000L

        /**
         * 排上「每天整理记忆」的周期任务（幂等，重复调用只会更新同一个工作）。
         *
         * 调用点由主线接：App 启动时（[com.yap.app.YapApplication.onCreate]），
         * 以及设置里打开「每天整理记忆」时。
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MemoryConsolidationWorker>(
                PERIOD_HOURS,
                TimeUnit.HOURS,
                // 15 分钟弹性窗口：系统可以合并唤醒，省电，也更容易真的跑起来。
                15,
                TimeUnit.MINUTES
            )
                .setInitialDelay(millisUntilNextRun(System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(TAG)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }

        /**
         * 立刻整理一次（设置页「现在整理一下」按钮用）。
         *
         * 用一次性任务而不是直接调 [MemoryConsolidator]：界面不该自己跑网络请求，
         * 交给 WorkManager 还能在 App 退到后台时继续跑完。
         */
        fun enqueueOneTime(context: Context) {
            // 手动这次是用户当场点的：不挂联网约束，否则没网时会一直躺在 ENQUEUED，
            // 界面上就成了永远转不完的「整理中…」。没网时它自己会失败，并说一句人话。
            val request = OneTimeWorkRequestBuilder<MemoryConsolidationWorker>()
                .addTag(TAG)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME_MANUAL,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }

        /** 关掉「每天整理记忆」时调：周期任务和待跑的手动任务一起撤掉。 */
        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_MANUAL) }
        }

        /**
         * 距离「下一个本地 [CONSOLIDATION_HOUR] 点」还有多少毫秒，最少 [MIN_FIRST_DELAY_MILLIS]。
         * 纯计算，方便单测/排查；时区取系统时区。
         */
        fun millisUntilNextRun(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            val target = runCatching {
                val current = Instant.ofEpochMilli(now).atZone(zone)
                var next = current
                    .withHour(CONSOLIDATION_HOUR)
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0)
                if (!next.isAfter(current)) next = next.plusDays(1)
                next.toInstant().toEpochMilli() - now
            }.getOrDefault(MIN_FIRST_DELAY_MILLIS)
            return target.coerceAtLeast(MIN_FIRST_DELAY_MILLIS)
        }
    }
}
