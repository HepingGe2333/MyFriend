package com.myfriend.app.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.myfriend.app.data.prefs.AppSettings
import com.myfriend.app.ui.screens.apiconfig.ApiEditorScreen
import com.myfriend.app.ui.screens.browser.InAppBrowserScreen
import com.myfriend.app.ui.screens.apiconfig.ApiListScreen
import com.myfriend.app.ui.screens.chat.ChatScreen
import com.myfriend.app.ui.screens.evolution.EvolutionScreen
import com.myfriend.app.ui.screens.home.HomeScreen
import com.myfriend.app.ui.screens.memory.MemoryScreen
import com.myfriend.app.ui.screens.persona.PersonaEditorScreen
import com.myfriend.app.ui.screens.persona.PersonaListScreen
import com.myfriend.app.ui.screens.preset.PresetEditorScreen
import com.myfriend.app.ui.screens.preset.PresetListScreen
import com.myfriend.app.ui.screens.proactive.ProactiveScreen
import com.myfriend.app.ui.screens.search.SearchScreen
import com.myfriend.app.ui.screens.settings.AboutScreen
import com.myfriend.app.ui.screens.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val PERSONAS = "personas"
    const val PRESETS = "presets"
    const val API_CONFIGS = "api_configs"
    const val ABOUT = "about"
    const val CHAT_PATTERN = "chat/{sessionId}"
    const val PERSONA_EDIT_PATTERN = "persona_edit?personaId={personaId}"
    const val PRESET_EDIT_PATTERN = "preset_edit?presetId={presetId}"
    const val API_EDIT_PATTERN = "api_edit?configId={configId}"
    const val MEMORY_PATTERN = "memory/{personaId}"
    const val EVOLUTION_PATTERN = "evolution/{personaId}"
    const val PROACTIVE_PATTERN = "proactive?personaId={personaId}"
    const val BROWSER_PATTERN = "browser?url={url}"

    fun chat(sessionId: String) = "chat/${Uri.encode(sessionId)}"
    fun personaEdit(personaId: String? = null) =
        if (personaId == null) "persona_edit" else "persona_edit?personaId=${Uri.encode(personaId)}"
    fun presetEdit(presetId: String? = null) =
        if (presetId == null) "preset_edit" else "preset_edit?presetId=${Uri.encode(presetId)}"
    fun proactive(personaId: String? = null) =
        if (personaId == null) "proactive" else "proactive?personaId=${Uri.encode(personaId)}"
    fun apiEdit(configId: String? = null) =
        if (configId == null) "api_edit" else "api_edit?configId=${Uri.encode(configId)}"
    fun memory(personaId: String) = "memory/${Uri.encode(personaId)}"
    fun evolution(personaId: String) = "evolution/${Uri.encode(personaId)}"

    /** 应用内打开他发来的链接；地址要编码，避免里面的 ? & # 撕坏路由。 */
    fun browser(url: String) = "browser?url=${Uri.encode(url)}"
}

@Composable
fun MyFriendNavHost(
    settings: AppSettings,
    pendingSessionId: String? = null,
    onPendingSessionConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val animations = settings.animationsEnabled
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!animations) EnterTransition.None
        else fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 7 }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!animations) ExitTransition.None
        else fadeOut(tween(150)) + slideOutHorizontally(tween(240)) { -it / 14 }
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!animations) EnterTransition.None
        else fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 5 }
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!animations) ExitTransition.None
        else fadeOut(tween(150)) + slideOutHorizontally(tween(240)) { it / 10 }
    }

    // 从「主动唤醒」通知进来时直接落到对应会话。
    LaunchedEffect(pendingSessionId) {
        if (!pendingSessionId.isNullOrBlank()) {
            navController.navigate(Routes.chat(pendingSessionId)) { launchSingleTop = true }
            onPendingSessionConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenChat = { navController.navigate(Routes.chat(it)) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenPersonas = { navController.navigate(Routes.PERSONAS) },
                onOpenApiConfigs = { navController.navigate(Routes.API_CONFIGS) },
                onCreatePersona = { navController.navigate(Routes.personaEdit()) },
                onOpenMemory = { navController.navigate(Routes.memory(it)) }
            )
        }
        composable(
            route = Routes.CHAT_PATTERN,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) {
            ChatScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenPersona = { navController.navigate(Routes.personaEdit(it)) },
                onOpenMemory = { navController.navigate(Routes.memory(it)) },
                onOpenEvolution = { navController.navigate(Routes.evolution(it)) },
                onOpenApiConfigs = { navController.navigate(Routes.API_CONFIGS) },
                onOpenProactive = { personaId -> navController.navigate(Routes.proactive(personaId)) },
                // 消息里的链接在应用里打开，不跳外部浏览器
                onOpenLink = { url ->
                    navController.navigate(Routes.browser(url)) { launchSingleTop = true }
                },
                onSwitchSession = { sessionId ->
                    navController.navigate(Routes.chat(sessionId)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { navController.navigate(Routes.chat(it)) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenPersonas = { navController.navigate(Routes.PERSONAS) },
                onOpenApiConfigs = { navController.navigate(Routes.API_CONFIGS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenPresets = { navController.navigate(Routes.PRESETS) },
                onOpenProactive = { navController.navigate(Routes.proactive()) }
            )
        }
        composable(Routes.PERSONAS) {
            PersonaListScreen(
                onBack = { navController.popBackStack() },
                onOpenPersona = { navController.navigate(Routes.personaEdit(it)) },
                onCreatePersona = { navController.navigate(Routes.personaEdit()) },
                onOpenPresets = { navController.navigate(Routes.PRESETS) }
            )
        }
        composable(
            route = Routes.PERSONA_EDIT_PATTERN,
            arguments = listOf(navArgument("personaId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) {
            PersonaEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onOpenApiConfigs = { navController.navigate(Routes.API_CONFIGS) }
            )
        }
        composable(Routes.API_CONFIGS) {
            ApiListScreen(
                onBack = { navController.popBackStack() },
                onAddConfig = { navController.navigate(Routes.apiEdit()) },
                onEditConfig = { navController.navigate(Routes.apiEdit(it)) }
            )
        }
        composable(
            route = Routes.API_EDIT_PATTERN,
            arguments = listOf(navArgument("configId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) {
            ApiEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.MEMORY_PATTERN,
            arguments = listOf(navArgument("personaId") { type = NavType.StringType })
        ) {
            MemoryScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.EVOLUTION_PATTERN,
            arguments = listOf(navArgument("personaId") { type = NavType.StringType })
        ) {
            EvolutionScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PRESETS) {
            PresetListScreen(
                onBack = { navController.popBackStack() },
                onEditPreset = { presetId -> navController.navigate(Routes.presetEdit(presetId)) },
                onPersonaCreated = { personaId ->
                    // 人格已经写入，编辑页只用来微调名字与提示词；
                    // 保存后回到首页，而不是退回设置页。
                    navController.navigate(Routes.personaEdit(personaId)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }
        composable(
            route = Routes.PRESET_EDIT_PATTERN,
            arguments = listOf(navArgument("presetId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) {
            PresetEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.PROACTIVE_PATTERN,
            arguments = listOf(navArgument("personaId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) {
            ProactiveScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { sessionId -> navController.navigate(Routes.chat(sessionId)) }
            )
        }
        composable(
            route = Routes.BROWSER_PATTERN,
            arguments = listOf(navArgument("url") { type = NavType.StringType })
        ) { entry ->
            InAppBrowserScreen(
                url = entry.arguments?.getString("url").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
