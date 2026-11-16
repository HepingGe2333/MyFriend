package com.yap.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yap.app.data.repo.BackupImportException
import com.yap.app.ui.common.BackupText
import com.yap.app.ui.common.ErrorText
import com.yap.app.R
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.prefs.BubbleStyle
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.prefs.ThemeMode
import com.yap.app.data.repo.ApiConfigRepository
import com.yap.app.data.repo.ApiUsageRepository
import com.yap.app.data.repo.BackupRepository
import com.yap.app.data.repo.ChatRepository
import com.yap.app.data.repo.MediaJanitor
import com.yap.app.data.repo.ImportMode
import com.yap.app.data.repo.PersonaRepository
import com.yap.app.data.repo.PresetRepository
import com.yap.app.data.repo.PersonaRepositoryInitializer
import com.yap.app.data.repo.ProactiveRepository
import com.yap.app.data.repo.UsageTotals
import com.yap.app.data.remote.ServerConfigRejection
import com.yap.app.data.remote.ServerCredentials
import com.yap.app.data.remote.ServerModeRepository
import com.yap.app.data.remote.ServerConnectionState
import com.yap.app.data.remote.ServerLocalStore
import com.yap.app.domain.BackupDecodeResult
import com.yap.app.domain.BackupPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 设置页「服务端模式」小节的状态。
 *
 * 令牌**不进这个状态**：界面只拿到脱敏串/“有没有”，输入框里新填的令牌由 Composable 自己
 * 持有，保存时直接交给 ViewModel。这样重组、状态保存、日志都不会把明文令牌带出去。
 */
data class ServerSettingsUiState(
    val url: String = "",
    val enabled: Boolean = false,
    val hasToken: Boolean = false,
    val maskedToken: String = "",
    val usable: Boolean = false,
    val rejection: ServerConfigRejection? = null,
    val testing: Boolean = false,
    /** 测试连接的结果（成功一行摘要 / 失败原因），直接显示在按钮下面。 */
    val testMessage: String? = null,
    /** 保存/清除失败时的提示。 */
    val error: String? = null
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val personaCount: Int = 0,
    val apiCount: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    /** 他这个月自己上网、整理记忆花掉的量（只在本机）。 */
    val usage: UsageTotals = UsageTotals.EMPTY,
    /** 服务端模式（M3）：默认关闭。 */
    val server: ServerSettingsUiState = ServerSettingsUiState()
)

sealed interface SettingsEvent {
    /**
     * 「导出全部对话」已经准备好：只带建议文件名，真正的内容在用户选完位置之后
     * 边读库边分批写盘（见 [SettingsViewModel.exportAllChatsTo]）。
     */
    data class ExportReady(val fileName: String) : SettingsEvent
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
    val decodeWarnings: List<com.yap.app.domain.BackupWarning> = emptyList(),
    /** v3 媒体段的提醒（坏条目 / 读不出来），与 [decodeWarnings] 一起显示。 */
    val decodeMediaWarnings: List<com.yap.app.domain.BackupMediaWarning> = emptyList()
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
    /** 「我的头像」换新图之后，旧文件按引用回收（0.12：共享文件不删）。 */
    private val mediaJanitor: MediaJanitor,
    private val personaInitializer: PersonaRepositoryInitializer,
    private val serverModeRepository: ServerModeRepository,
    private val serverLocalStore: ServerLocalStore,
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

    private val serverTesting = MutableStateFlow(false)
    private val serverTestMessage = MutableStateFlow<String?>(null)
    private val serverError = MutableStateFlow<String?>(null)

    private val serverState: Flow<ServerSettingsUiState> = combine(
        serverModeRepository.state,
        serverTesting,
        serverTestMessage,
        serverError
    ) { state, testing, testMessage, error ->
        state.toUiState(testing = testing, testMessage = testMessage, error = error)
    }

    /** 「用量」要实时刷新：订阅本月流水与按人的排行，聊天或后台任务一跑，这里立刻变。 */
    val uiState: StateFlow<SettingsUiState> = combine(
        baseState,
        apiUsageRepository.observeMonth(),
        apiUsageRepository.observeMonthBySession(),
        serverState
    ) { current, usage, bySession, server ->
        current.copy(usage = usage.copy(bySession = bySession), server = server)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val backupBusy = MutableStateFlow(false)
    private val backupPreview = MutableStateFlow<BackupPreview?>(null)
    private val backupDecodeWarnings = MutableStateFlow<List<com.yap.app.domain.BackupWarning>>(emptyList())
    private val backupDecodeMediaWarnings =
        MutableStateFlow<List<com.yap.app.domain.BackupMediaWarning>>(emptyList())

    /** 「把他带走」这一节：正在打包/接回来时按钮停一下；读完文件先给一句摘要。 */
    val backupState: StateFlow<BackupUiState> = combine(
        backupBusy,
        backupPreview,
        backupDecodeWarnings,
        backupDecodeMediaWarnings
    ) { busy, preview, warnings, mediaWarnings ->
        BackupUiState(busy, preview, warnings, mediaWarnings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

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

    fun setAutoTranslatePersona(enabled: Boolean) =
        launchSetting { settingsRepository.setAutoTranslatePersona(enabled) }

    fun setChatBackgroundColor(color: Long?) = launchSetting { settingsRepository.setChatBackgroundColor(color) }
    fun setChatBackgroundImage(uri: String?) = launchSetting { settingsRepository.setChatBackgroundImageUri(uri) }

    /**
     * 「我的头像」只写 DataStore 里的路径；文件已经由 MediaStorage 采样压缩好了。
     *
     * 0.12：换新图之后旧文件按引用回收——旧实现从不删旧图，每换一次就多一个孤儿文件；
     * 而「删」必须查引用（同一个 `avatar-*.jpg` 可能还被某个人格/预设引用着）。
     */
    fun setUserAvatar(uri: String?) = launchSetting {
        val previous = runCatching { settingsRepository.settings.first().userAvatarUri }.getOrNull()
        settingsRepository.setUserAvatarUri(uri)
        if (previous != null && previous != uri) {
            runCatching { mediaJanitor.deleteIfUnreferenced(previous) }
        }
    }

    /** 常驻后台：关掉后他就只在应用打开时存在。 */
    fun setAgentLoopEnabled(enabled: Boolean) = launchSetting { settingsRepository.setAgentLoopEnabled(enabled) }

    /** 导出全部对话的默认文件名（界面先拿它去开系统文件选择器）。 */
    fun suggestedAllChatsFileName(): String = chatRepository.suggestedAllChatsFileName()

    fun exportAllChats() {
        viewModelScope.launch {
            // 这里只发起「选位置」；真正的读库 + 分批写文件在 exportAllChatsTo 里做。
            runCatching { events.send(SettingsEvent.ExportReady(chatRepository.suggestedAllChatsFileName())) }
        }
    }

    /**
     * 把全部对话**边读边写**进用户选的文件。
     *
     * 0.10.2 之前：仓库先在主线程上下文里组装整份字符串，界面再整体写盘 ——
     * 整库导出时这是几十 MB 的堆峰值 + 一段主线程卡顿。现在读取/序列化在仓库的
     * IO 上下文里，按 [com.yap.app.domain.ExportJsonStream] 的批大小一段段回调，
     * 这里每段直接写进输出流并 flush；失败（磁盘满、权限没了）会让整次导出失败并报出来。
     */
    fun exportAllChatsTo(uri: Uri) {
        viewModelScope.launch {
            busy.value = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        chatRepository.exportAllChatsTo { chunk ->
                            output.write(chunk.toByteArray(Charsets.UTF_8))
                            // 分批落盘：不要等整份文件拼完再一次性写。
                            output.flush()
                        }.getOrThrow()
                    } ?: error(context.getString(R.string.settings_error_unwritable_file))
                }
            }
            message.value = result.fold(
                { context.getString(R.string.settings_export_success) },
                { context.getString(R.string.settings_export_failed, ErrorText.of(context, it)) }
            )
            busy.value = false
        }
    }

    fun clearChats() {
        viewModelScope.launch {
            busy.value = true
            runCatching { chatRepository.clearAllChatData() }
                .onSuccess { message.value = context.getString(R.string.settings_cleared_chats) }
                .onFailure { message.value = ErrorText.of(context, it, R.string.settings_error_clear) }
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
                // 媒体文件（files/media + files/avatars）由 ChatRepository.wipeAllData 内的
                // DataEraser 一并清掉：删表与删文件走同一份清单，避免两处各自漏。
                settingsRepository.resetAll(preserveSeedMarker = false)
                // 服务端连接也一起清：否则「删除全部数据」之后 App 还会连回旧服务器。
                clearServerConnectionQuietly()
                // 与 iOS 对齐：清空之后按当前语言重新播种，别让用户面对一个空白 App。
                personaInitializer.seedIfNeeded()
            }.onSuccess { message.value = context.getString(R.string.settings_wiped) }
                .onFailure { message.value = ErrorText.of(context, it, R.string.settings_error_delete) }
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
    suspend fun buildBackupJson(): Result<com.yap.app.data.repo.BackupExport> = runCatching {
        backupBusy.value = true
        try {
            backupRepository.export()
        } finally {
            backupBusy.value = false
        }
    }

    /** 读进来的备份先解码看一眼：几个人格、多少条记忆、多少句话。 */
    fun openBackup(text: String) {
        viewModelScope.launch {
            backupBusy.value = true
            when (val decoded = backupRepository.preview(text)) {
                is BackupDecodeResult.Failure -> message.value = BackupText.failure(context, decoded.reason)
                is BackupDecodeResult.Success -> {
                    pendingBackup = text
                    backupDecodeWarnings.value = decoded.warnings
                    backupDecodeMediaWarnings.value = decoded.mediaWarnings
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
        backupDecodeMediaWarnings.value = emptyList()
    }

    /** 照备份把他接回来。失败时库里什么都没变，只给一句中文。 */
    fun importBackup(mode: ImportMode) {
        val raw = pendingBackup ?: return
        viewModelScope.launch {
            backupBusy.value = true
            runCatching { backupRepository.importJson(raw, mode) }
                .onSuccess { summary ->
                    message.value = BackupText.importSummary(context, summary)
                    dismissBackupPreview()
                }
                .onFailure { error ->
                    message.value = if (error is BackupImportException) {
                        BackupText.failure(context, error.failure)
                    } else {
                        ErrorText.of(context, error, R.string.settings_error_import)
                    }
                }
            backupBusy.value = false
        }
    }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                message.value = ErrorText.of(context, it, R.string.settings_error_save)
            }
        }
    }

    // ---------------------------------------------------------------- 服务端模式（M3）

    /**
     * 保存地址/令牌/开关。
     *
     * 令牌输入框留空 = 沿用已保存的令牌（脱敏展示时用户不该为了改地址而重新粘贴一次）；
     * 要清除令牌用 [clearServerToken]。
     */
    fun saveServerConnection(url: String, tokenInput: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = serverModeRepository.currentCredentials()
            val token = tokenInput.trim().ifEmpty { current.token }
            val credentials = ServerCredentials(url = url, token = token, enabled = enabled)
            serverModeRepository.save(credentials)
                .onSuccess {
                    serverTestMessage.value = null
                    serverError.value = null
                    message.value = context.getString(R.string.settings_server_saved)
                }
                .onFailure {
                    serverError.value = context.getString(R.string.settings_server_save_failed)
                }
        }
    }

    /** 打开/关闭服务端模式：先落盘，失败时界面靠 [serverState] 回滚。 */
    fun setServerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            serverModeRepository.setEnabled(enabled)
                .onFailure { serverError.value = context.getString(R.string.settings_server_save_failed) }
        }
    }

    /** 清除已保存令牌（地址/开关保留）。 */
    fun clearServerToken() {
        viewModelScope.launch {
            val current = serverModeRepository.currentCredentials()
            serverModeRepository.save(current.copy(token = ""))
                .onFailure { serverError.value = context.getString(R.string.settings_server_save_failed) }
        }
    }

    /**
     * 测试连接：用界面上**当前输入**试一次（令牌留空则用已保存的）。
     *
     * 测试成功不代表已保存：用户可能还在改地址；保存是另一个动作，避免「点测试就悄悄改了配置」。
     */
    fun testServerConnection(url: String, tokenInput: String) {
        viewModelScope.launch {
            if (serverTesting.value) return@launch
            serverTesting.value = true
            serverTestMessage.value = null
            val current = serverModeRepository.currentCredentials()
            val credentials = ServerCredentials(
                url = url,
                token = tokenInput.trim().ifEmpty { current.token },
                enabled = true
            )
            serverModeRepository.testConnection(credentials)
                .onSuccess { result ->
                    serverTestMessage.value =
                        context.getString(R.string.settings_server_test_success, result.describe())
                }
                .onFailure { error ->
                    serverTestMessage.value = ErrorText.of(
                        context,
                        error,
                        R.string.settings_server_test_failed
                    )
                }
            serverTesting.value = false
        }
    }

    /** 删除全部数据后连服务端配置一起清掉（否则新用户会连上旧服务器/旧游标）。 */
    private suspend fun clearServerConnectionQuietly() {
        serverModeRepository.clear()
        serverModeRepository.reload()
        // 游标、会话映射、待发队列都属于那台服务端；数据清空后它们没有意义。
        runCatching { serverLocalStore.resetForNewServer() }
    }

    private fun ServerConnectionState.toUiState(
        testing: Boolean,
        testMessage: String?,
        error: String?
    ): ServerSettingsUiState = ServerSettingsUiState(
        url = credentials.cleanUrl,
        enabled = credentials.enabled,
        hasToken = credentials.cleanToken.isNotEmpty(),
        maskedToken = credentials.maskedToken(),
        usable = usable,
        rejection = config.rejection(),
        testing = testing,
        testMessage = testMessage,
        error = error
    )
}
