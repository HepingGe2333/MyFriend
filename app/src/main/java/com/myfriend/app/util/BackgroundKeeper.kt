package com.myfriend.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 「让他留在后台」需要用到的系统设置入口。
 *
 * 这里只做纯 Android 的事情，不碰任何 repository：查询通知权限、查询电池优化白名单，
 * 以及把用户送到对应的系统页面。所有跳转都包在 runCatching 里 —— 部分 ROM 会把
 * 这些系统 Activity 裁掉，或者没有声明对应权限，失败时宁可什么都不发生，也不能崩。
 */
object BackgroundKeeper {

    private const val PACKAGE_SCHEME = "package"

    /** 13 以上要看 POST_NOTIFICATIONS；以下默认有通知权限。 */
    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 是否已经在电池优化的白名单里（被排除在省电策略之外）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@runCatching false
        manager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** 弹系统询问框，把本应用从电池优化里排除。 */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("$PACKAGE_SCHEME:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            // 少数 ROM 没有这个页面（或没声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS），退回应用详情页。
            openAppDetails(context)
        }
    }

    /** 通知设置页：让用户自己打开通知总开关或单个渠道。 */
    fun openAppNotificationSettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { openAppDetails(context) }
    }

    /** 应用详情页：自启动、后台活动、权限等系统白名单的总入口。 */
    fun openAppDetails(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts(PACKAGE_SCHEME, context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
