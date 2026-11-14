package com.myfriend.app.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 到底在不在前台。
 *
 * 只在「聊天页没开着」时才通知是不够的：按 Home 退到桌面时聊天页还挂在栈上，
 * 那时候他回完消息你是看不到的，必须照样发通知。
 */
@Singleton
class AppForegroundTracker @Inject constructor() {

    @Volatile
    var isForeground: Boolean = false
        private set

    private var started = 0
    private var installed = false

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                isForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                isForeground = started > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
