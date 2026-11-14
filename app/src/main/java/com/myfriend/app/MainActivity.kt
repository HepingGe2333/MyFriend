package com.myfriend.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.data.prefs.ThemeMode
import com.myfriend.app.navigation.MyFriendNavHost
import com.myfriend.app.util.ChatNotifier
import com.myfriend.app.util.ProactiveNotifier
import com.myfriend.app.ui.RootViewModel
import com.myfriend.app.ui.theme.MyFriendTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var chatNotifier: ChatNotifier

    private var pendingSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        pendingSessionId = intent?.getStringExtra(ProactiveNotifier.EXTRA_SESSION_ID)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb())
        )
        setContent {
            val rootViewModel: RootViewModel = hiltViewModel()
            val settings by rootViewModel.settings.collectAsStateWithLifecycle()
            // Android 13+ 通知权限：首次进入请求一次，拒绝过两次就不再打扰。
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) chatNotifier.ensureChannel(this@MainActivity)
            }
            LaunchedEffect(Unit) {
                if (shouldAskForNotificationPermission()) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            MyFriendTheme(settings) {
                val dark = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                ApplySystemBars(dark)
                MyFriendNavHost(
                    settings = settings,
                    pendingSessionId = pendingSessionId,
                    onPendingSessionConsumed = {
                        // 从通知进来时把这条通知撤掉，免得会话里还挂着提醒。
                        pendingSessionId?.let { chatNotifier.cancel(this@MainActivity, it) }
                        pendingSessionId = null
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSessionId = intent.getStringExtra(ProactiveNotifier.EXTRA_SESSION_ID)
    }

    /** 首次进入请求一次通知权限；被拒后只有系统还允许解释时才补问一次，两次之后不再打扰。 */
    private fun shouldAskForNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return false
        val prefs = getSharedPreferences(NOTIFICATION_PREFS, MODE_PRIVATE)
        val asked = prefs.getInt(KEY_NOTIFICATION_ASKED, 0)
        if (asked >= MAX_NOTIFICATION_ASKS) return false
        // 问过一次后，只有在还能弹解释框（而非「不再询问」）时才补问。
        if (asked > 0 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            return false
        }
        prefs.edit().putInt(KEY_NOTIFICATION_ASKED, asked + 1).apply()
        return true
    }

    private companion object {
        const val NOTIFICATION_PREFS = "notification_permission"
        const val KEY_NOTIFICATION_ASKED = "asked_count"

        /** 最多问两次：拒绝两次后系统默认不再弹，我们也不再打扰。 */
        const val MAX_NOTIFICATION_ASKS = 2
    }
}

@Composable
private fun ApplySystemBars(dark: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? ComponentActivity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
}
