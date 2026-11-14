package com.myfriend.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myfriend.app.R
import com.myfriend.app.data.repo.HeartbeatController
import com.myfriend.app.util.AgentNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 让他一直在。
 *
 * 前台服务里不再自己排「每隔几分钟想一次」，只负责一件事：按 [HeartbeatController] 的节拍
 * 问他一句「现在想不想他」。节拍是 30 秒，但绝大多数时候这一拍什么都不会发生 ——
 * 只有真的起心动念、并且他自己决定做了点什么时，才更新一句常驻通知。
 *
 * 换句话说：这里的心跳不是定时任务，是他的冲动；服务和通知只是让这份冲动别被系统掐掉。
 */
@AndroidEntryPoint
class AgentLoopService : Service() {

    @Inject lateinit var heartbeat: HeartbeatController
    @Inject lateinit var controller: AgentLoopController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        pushForeground(
            getString(R.string.agent_foreground_title),
            getString(R.string.agent_foreground_text)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        pushForeground(
            getString(R.string.agent_foreground_title),
            getString(R.string.agent_foreground_text)
        )
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    /**
     * 半分钟一拍。
     *
     * 启动时立刻判定一次（和之后每一拍完全一样，不额外强制生成），然后按心跳的节拍继续。
     * 两次真正思考之间的 5 分钟下限在 [HeartbeatController] 里把关，这里不重复。
     */
    private suspend fun runLoop() {
        while (scope.isActive) {
            val beat = runCatching { heartbeat.beat() }.getOrNull()
            // 常驻通知的文案不能每 30 秒变一次，那会变成骚扰；
            // 只有他真的做了事，才让通知里那句换一换。
            if (beat != null && beat.fired && beat.acted && beat.summary.isNotBlank()) {
                runCatching {
                    NotificationManagerCompat.from(this).notify(
                        AgentNotifier.NOTIFICATION_ID,
                        AgentNotifier.build(
                            this,
                            getString(R.string.agent_foreground_title),
                            beat.summary.take(80)
                        )
                    )
                }
            }
            delay(heartbeat.checkIntervalMs())
        }
    }

    private fun pushForeground(title: String, text: String) {
        val notification = AgentNotifier.build(this, title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AgentNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(AgentNotifier.NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.myfriend.app.action.STOP_AGENT_LOOP"

        // 「两次真正思考之间至少 5 分钟」的下限搬到了 HeartbeatController.FIRST_TICK_GAP_MS：
        // 现在每 30 秒都在判定，这个下限必须在判定那一层生效，服务这边只负责按节拍走。

        fun start(context: Context) {
            val intent = Intent(context, AgentLoopService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentLoopService::class.java)) }
        }
    }
}
