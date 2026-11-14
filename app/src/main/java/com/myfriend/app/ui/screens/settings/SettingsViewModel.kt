package com.myfriend.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.R
import com.myfriend.app.data.prefs.AppSettings
import com.myfriend.app.data.prefs.BubbleStyle
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.data.prefs.ThemeMode
import com.myfriend.app.data.repo.ApiConfigRepository
import com.myfriend.app.data.repo.ApiUsageRepository
import com.myfriend.app.data.repo.BackupRepository
import com.myfriend.app.data.repo.ChatRepository
import com.myfriend.app.data.repo.ImportMode
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.data.repo.PresetRepository
import com.myfriend.app.data.repo.ProactiveRepository
import com.myfriend.app.data.repo.UsageTotals
import com.myfriend.app.domain.BackupDecodeResult
import com.myfriend.app.domain.BackupPreview
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.util.MediaStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val personaCount: Int = 0,
    val apiCount: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    /** 他这个月自己上网、整理记忆花掉的量（只在本机）。 */
    val usage: UsageTotals = UsageTotals.EMPTY
)

sealed interface SettingsEvent {
    data class ExportReady(val file: ExportFile) : SettingsEvent
}

/**
 * 「把他带走 / 从备份里接他回来」这一节的状态。
 *
 * [preview] 不为空表示：文件已经读进来、等着用户决定合并还是替换。
 */
data class BackupUiState(
    val busy: Boolean = false,
    val preview: BackupPreview? = null,
    /** 这份备份里有东西没读出来 / 带不回来时的中文说明，直接给用户看。 */
    val decodeWarnings: List<String> = emptyList()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    personaRepository: PersonaRepository,
    private val presetRepository: PresetRepository,
    private val proactiveRepository: ProactiveRepository,
    apiConfigRepository: ApiConfigRepository,
    private val apiUsageRepository: ApiUsageRepository,
    private val backupRepository: BackupRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val events = Channel<SettingsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val baseState: Flow<SettingsUiState> = combine(
        settingsRepository.settings,
        personaRepository.observeAll(),
        apiConfigRepository.observeAll(),
        busy,
        message
    ) { settings, personas, configs, isBusy, currentMessage ->
        SettingsUiState(settings, personas.size, configs.size, isBusy, currentMessage)
    }

    /** 「用量」要实时刷新：订阅本月流水与按人的排行，聊天或后台任务一跑，这里立刻变。 */
    val uiState: StateFlow<SettingsUiState> = combine(
        baseState,
        apiUsageRepository.observeMonth(),
        apiUsageRepository.observeMonthBySession()
    ) { current, usage, bySession ->
        current.copy(usage = usage.copy(bySession = bySession))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val backupBusy = MutableStateFlow(false)
    private val backupPreview = MutableStateFlow<BackupPreview?>(null)
    private val backupDecodeWarnings = MutableStateFlow<List<String>>(emptyList())

    /** 「把他带走」这一节：正在打包/接回来时按钮停一下；读完文件先给一句摘要。 */
    val backupState: StateFlow<BackupUiState> = combine(
        backupBusy,
        backupPreview,
        backupDecodeWarnings
    ) { busy, preview, warnings -> BackupUiState(busy, preview, warnings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    /** 用户手里那份备份文件：预览之后先拿着，等用户决定怎么接回来时再用。 */
    private var pendingBackup: String? = null

    fun dismissMessage() {
        message.value = null
    }

    fun setThemeMode(mode: ThemeMode) = launchSetting { settingsRepository.setThemeMode(mode) }
    fun setAccentColor(color: Long) = launchSetting { settingsRepository.setAccentColor(color) }
    fun setFontScale(scale: Float) = launchSetting { settingsRepository.setFontScale(scale) }
    fun setBubbleStyle(style: BubbleStyle) = launchSetting { settingsRepository.setBubbleStyle(style) }
    fun setAnimations(enabled: Boolean) = launchSetting { settingsRepository.setAnimationsEnabled(enabled) }
    fun setHaptics(enabled: Boolean) = launchSetting { settingsRepository.setHapticsEnabled(enabled) }

    fun setChatBackgroundColor(color: Long?) = launchSetting { settingsRepository.setChatBackgroundColor(color) }
    fun setChatBackgroundImage(uri: String?) = launchSetting { settingsRepository.setChatBackgroundImageUri(uri) }

    /** 「我的头像」只写 DataStore 里的路径；文件已经由 MediaStorage 采样压缩好了。 */
    fun setUserAvatar(uri: String?) = launchSetting { settingsRepository.setUserAvatarUri(uri) }

    /** 常驻后台：关掉后他就只在应用打开时存在。 */
    fun setAgentLoopEnabled(enabled: Boolean) = launchSetting { settingsRepository.setAgentLoopEnabled(enabled) }

    fun exportAllChats() {
        viewModelScope.launch {
            busy.value = true
            chatRepository.exportAllChats()
                .onSuccess { events.send(SettingsEvent.ExportReady(it)) }
                .onFailure { message.value = it.message ?: context.getString(R.string.settings_error_export) }
            busy.value = false
        }
    }

    fun clearChats() {
        viewModelScope.launch {
            busy.value = true
            runCatching { chatRepository.clearAllChatData() }
                .onSuccess { message.value = context.getString(R.string.settings_cleared_chats) }
                .onFailure { message.value = it.message ?: context.getString(R.string.settings_error_clear) }
            busy.value = false
        }
    }

    fun deleteAllData() {
        viewModelScope.launch {
            busy.value = true
            runCatching {
                // 顺序有意义：先清规则与排期，再清聊天/人格，最后清模板。
                proactiveRepository.wipeAll()
                chatRepository.wipeAllData()
                apiUsageRepository.wipe()
                presetRepository.wipeAll()
                MediaStorage.clear(context)
                settingsRepository.resetAll()
            }.onSuccess { message.value = context.getString(R.string.settings_wiped) }
                .onFailure { message.value = it.message ?: context.getString(R.string.settings_error_delete) }
            busy.value = false
        }
    }

    // ------------------------------------------------------------------
    // 把他带走 / 从备份里接他回来
    // ------------------------------------------------------------------

    /** 备份文件建议名字（界面上先给用户看，选位置时填进文件名）。 */
    fun suggestedBackupFileName(): String = backupRepository.suggestedFileName()

    /**
     * 生成备份文本。
     *
     * 交给界面的 rememberCoroutineScope 在 IO 线程写文件——文件可能几 MB，
     * 但生成这份文本要读库，所以这里只负责「生成」。
     */
    suspend fun buildBackupJson(): Result<String> = runCatching {
        backupBusy.value = true
        try {
            backupRepository.exportJson()
        } finally {
            backupBusy.value = false
        }
    }

    /** 读进来的备份先解码看一眼：几个人格、多少条记忆、多少句话。 */
    fun openBackup(text: String) {
        viewModelScope.launch {
            backupBusy.value = true
            when (val decoded = backupRepository.preview(text)) {
                is BackupDecodeResult.Failure -> message.value = decoded.reason
                is BackupDecodeResult.Success -> {
                    pendingBackup = text
                    backupDecodeWarnings.value = decoded.warnings
                    backupPreview.value = BackupPreview.from(decoded.bundle)
                }
            }
            backupBusy.value = false
        }
    }

    /** 先不接了：把文件放回去，什么都不写。 */
    fun dismissBackupPreview() {
        pendingBackup = null
        backupPreview.value = null
        backupDecodeWarnings.value = emptyList()
    }

    /** 照备份把他接回来。失败时库里什么都没变，只给一句中文。 */
    fun importBackup(mode: ImportMode) {
        val raw = pendingBackup ?: return
        viewModelScope.launch {
            backupBusy.value = true
            runCatching { backupRepository.importJson(raw, mode) }
                .onSuccess { summary ->
                    message.value = summary.describe()
                    dismissBackupPreview()
                }
                .onFailure {
                    message.value = it.message ?: context.getString(R.string.settings_error_import)
                }
            backupBusy.value = false
        }
    }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                message.value = it.message ?: context.getString(R.string.settings_error_save)
            }
        }
    }
}
