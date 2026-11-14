package com.myfriend.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "myfriend_settings")

/**
 * 本机偏好的唯一入口。
 *
 * 1.9 删掉了记忆开关、读消息节奏、额度/单价、每日主动次数这几组键。
 * 这里**刻意不保留**它们的旧 key：即使老用户的 DataStore 里还躺着这些值，
 * 也不会再被读出来、更不会被界面写回去。数据迁移不需要清它们——它们已经没人认识。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val accentColor = longPreferencesKey("accent_color")
        val fontScale = floatPreferencesKey("font_scale")
        val bubbleStyle = stringPreferencesKey("bubble_style")
        val animations = booleanPreferencesKey("animations")
        val chatBackgroundColor = longPreferencesKey("chat_background_color")
        val chatBackgroundImageUri = stringPreferencesKey("chat_background_image")
        val userAvatarUri = stringPreferencesKey("user_avatar_uri")
        val activePersonaId = stringPreferencesKey("active_persona_id")
        val haptics = booleanPreferencesKey("haptics")
        val seeded = booleanPreferencesKey("initial_personas_seeded")
        val agentLoop = booleanPreferencesKey("agent_loop")
        val backgroundTipDismissed = booleanPreferencesKey("background_tip_dismissed")
        // 心跳的内部状态：只用来让冲动曲线跨重启接着走，不对外暴露（设置页里没有它）。
        val heartbeatLastResetAt = longPreferencesKey("heartbeat_last_reset_at")
        val heartbeatLastFiredAt = longPreferencesKey("heartbeat_last_fired_at")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            accentColor = prefs[Keys.accentColor] ?: 0xFFC76F4E,
            fontScale = (prefs[Keys.fontScale] ?: 1f).coerceIn(0.85f, 1.3f),
            bubbleStyle = prefs[Keys.bubbleStyle]?.let { runCatching { BubbleStyle.valueOf(it) }.getOrNull() } ?: BubbleStyle.SOFT,
            animationsEnabled = prefs[Keys.animations] ?: true,
            chatBackgroundColor = prefs[Keys.chatBackgroundColor],
            chatBackgroundImageUri = prefs[Keys.chatBackgroundImageUri],
            userAvatarUri = prefs[Keys.userAvatarUri],
            activePersonaId = prefs[Keys.activePersonaId],
            hapticsEnabled = prefs[Keys.haptics] ?: true,
            agentLoopEnabled = prefs[Keys.agentLoop] ?: true,
            backgroundTipDismissed = prefs[Keys.backgroundTipDismissed] ?: false,
            heartbeatLastResetAt = prefs[Keys.heartbeatLastResetAt]?.takeIf { it > 0L } ?: 0L,
            heartbeatLastFiredAt = prefs[Keys.heartbeatLastFiredAt]?.takeIf { it > 0L }
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.themeMode] = mode.name }
    suspend fun setAccentColor(argb: Long) = edit { it[Keys.accentColor] = argb }
    suspend fun setFontScale(scale: Float) = edit { it[Keys.fontScale] = scale.coerceIn(0.85f, 1.3f) }
    suspend fun setBubbleStyle(style: BubbleStyle) = edit { it[Keys.bubbleStyle] = style.name }
    suspend fun setAnimationsEnabled(enabled: Boolean) = edit { it[Keys.animations] = enabled }
    suspend fun setChatBackgroundColor(argb: Long?) = edit {
        if (argb == null) it.remove(Keys.chatBackgroundColor) else it[Keys.chatBackgroundColor] = argb
    }
    suspend fun setChatBackgroundImageUri(uri: String?) = edit {
        if (uri == null) it.remove(Keys.chatBackgroundImageUri) else it[Keys.chatBackgroundImageUri] = uri
    }

    /**
     * 「我的头像」只存一个 App 私有目录里的文件路径。
     *
     * 不加数据库迁移：头像不是人格/聊天/记忆的一部分，也没有历史版本要保留；
     * 清空数据（[resetAll]）时它跟着清掉，符合「这台手机上的你重新开始」的语义。
     */
    suspend fun setUserAvatarUri(uri: String?) = edit {
        if (uri == null) it.remove(Keys.userAvatarUri) else it[Keys.userAvatarUri] = uri
    }
    suspend fun setActivePersonaId(id: String?) = edit {
        if (id == null) it.remove(Keys.activePersonaId) else it[Keys.activePersonaId] = id
    }
    suspend fun setHapticsEnabled(enabled: Boolean) = edit { it[Keys.haptics] = enabled }

    suspend fun resetAll() {
        // Preserve the seed marker so deleting all data does not resurrect sample personas.
        val seeded = context.settingsDataStore.data.first()[Keys.seeded] ?: false
        context.settingsDataStore.edit {
            it.clear()
            if (seeded) it[Keys.seeded] = true
        }
    }

    suspend fun hasSeededInitialPersonas(): Boolean =
        context.settingsDataStore.data.first()[Keys.seeded] ?: false

    suspend fun markInitialPersonasSeeded() {
        context.settingsDataStore.edit { it[Keys.seeded] = true }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    suspend fun setAgentLoopEnabled(enabled: Boolean) = edit { it[Keys.agentLoop] = enabled }

    suspend fun setBackgroundTipDismissed(dismissed: Boolean) =
        edit { it[Keys.backgroundTipDismissed] = dismissed }

    /**
     * 记下他的冲动曲线走到哪儿了（内部状态，没有对应的界面开关）。
     *
     * 只由心跳那一层调用：一次互动或一次真的冲动之后重置概率时钟。
     * 传 0 / null 表示「当没有过」，比如清空数据之后。
     */
    suspend fun setHeartbeatState(lastResetAt: Long, lastFiredAt: Long?) = edit {
        if (lastResetAt > 0L) it[Keys.heartbeatLastResetAt] = lastResetAt else it.remove(Keys.heartbeatLastResetAt)
        if (lastFiredAt != null && lastFiredAt > 0L) {
            it[Keys.heartbeatLastFiredAt] = lastFiredAt
        } else {
            it.remove(Keys.heartbeatLastFiredAt)
        }
    }
}
