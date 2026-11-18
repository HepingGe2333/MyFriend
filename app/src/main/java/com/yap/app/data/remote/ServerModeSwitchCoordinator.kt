package com.yap.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 服务端开关的**最小控制面**（生产实现是 [ServerModeRepository]；单测注入假实现）。
 *
 * 注意 [enabled] 是「用户拨的开关」，不是「配置可用」：地址非法时开关也可能是开的。
 * 真正的网络可用性看 [usable]。
 */
interface ServerModeControl {
    val enabled: Boolean
    val usable: Boolean

    /** 已保存的服务端地址（开关关着也有值；没填过时为空串）。 */
    val configuredUrl: String

    fun setEnabled(enabled: Boolean): Result<Unit>
}

/** 切换开关时要跑的同步动作（生产实现是 [ServerBootstrapManager]；单测注入假实现）。 */
interface ServerBootstrapRunner {
    suspend fun runBootstrap(baseUrl: String): ServerBootstrapOutcome

    suspend fun runFullDownload(baseUrl: String): ServerFullDownloadOutcome

    /** 刚跑完那轮的结果（并发时另一个调用者也能拿到真实计数）。 */
    fun latestOutcome(baseUrl: String): ServerBootstrapOutcome? = null
}

/** 设置页服务端小节的同步阶段（UI 负责枚举 → 资源文案）。 */
enum class ServerSyncPhase {
    IDLE,
    /** 需要弹窗问「把对话交给云端处理？」。 */
    CONSENT_REQUIRED,
    /** 已同意，正在推送（含历史媒体逐项上传）。 */
    SYNCING,
    /** 正在把服务端全量下载回本机。 */
    DOWNLOADING,
    /** 「同意并同步」/「只同步之后的新内容」已完成。 */
    COMPLETED,
    /** 全量下载完成，开关已关闭。 */
    DOWNLOADED,
    FAILED,
    CANCELLED
}

/** 失败原因（枚举而不是字符串：文案在资源里）。 */
enum class ServerSyncFailure {
    SAVE_FAILED,
    SYNC_FAILED,

    /** 下载到了服务端数据，但本地条数没凑齐（校验失败）。 */
    DOWNLOAD_INCOMPLETE,

    /** 服务端根本没连上（保持云端模式，等下次再试）。 */
    DOWNLOAD_UNAVAILABLE
}

/**
 * 一次「切换到服务端 / 切回本地」的结果（设置页据此提示，不出现技术细节）。
 */
data class ServerSyncStatus(
    val phase: ServerSyncPhase = ServerSyncPhase.IDLE,
    val baseUrl: String? = null,
    /** 这次用的同意方式：NEW_ONLY 时文案不能说「历史已上传」。 */
    val consent: ServerSyncConsent? = null,
    /** 推送的消息条数（NEW_ONLY 为 0）。 */
    val messages: Int = 0,
    /** 历史媒体总项数 / 已上传 / 失败。 */
    val mediaTotal: Int = 0,
    val mediaUploaded: Int = 0,
    val mediaFailed: Int = 0,
    /** 「切回本地」：这次取回的消息条数 + 本地/服务端条数（失败时用于提示）。 */
    val downloadedMessages: Int = 0,
    val localMessages: Int = 0,
    val serverMessages: Long = 0,
    val failure: ServerSyncFailure? = null,
    /** 数据本来就对齐（同一地址已经搬过），没有实际搬运。 */
    val skipped: Boolean = false
) {
    val running: Boolean get() = phase == ServerSyncPhase.SYNCING || phase == ServerSyncPhase.DOWNLOADING

    val consentRequired: Boolean get() = phase == ServerSyncPhase.CONSENT_REQUIRED
}

/** 开关被拨到某个方向时，界面该做什么。 */
sealed interface ServerToggleAction {
    /** 首次从本地切到远程：必须弹窗（三个选项）。 */
    data class NeedsConsent(val baseUrl: String) : ServerToggleAction

    /** 这台地址已经同意过：直接开启并同步。 */
    data class Enable(val consent: ServerSyncConsent) : ServerToggleAction

    /** 关闭：先全量下载 + 条数校验，成功后才真正关闭。 */
    data object Disable : ServerToggleAction

    /** 开关状态没变（重复点击/地址为空等），什么都不做。 */
    data object Ignore : ServerToggleAction
}

/**
 * 服务端开关的**状态机**（0.13，`spec/sync-consent/README.md`）。
 *
 * 职责（全部纯 JVM，可单测）：
 * - **同意门**：本地 → 远程必须先有 `server_sync_consent:<baseUrl>`；没有就返回
 *   [ServerToggleAction.NeedsConsent]，由界面弹窗。取消 = 什么都不记、开关不动；
 * - **同意并同步**：[ServerSyncConsent.FULL] → 开开关 → bootstrap（元数据/文本 + 历史媒体逐项上传）；
 * - **只同步之后的新内容**：[ServerSyncConsent.NEW_ONLY] → 开开关 → bootstrap（不推历史消息/记忆）；
 * - **取消语义**：同步被取消时把开关拨回去（**不切换模式**），已上传的媒体留在服务端；
 * - **切回本地**：先全量下载 + 条数校验，成功才关开关；失败/取消保持开启。
 *
 * 为什么开关先开后同步：`ServerApi` 只在「开关打开 + 地址合法」时才存在（[ServerModeControl]），
 * 而同步必须先有客户端。取消/失败时由本类负责回滚开关，界面不会停在半开状态。
 */
class ServerModeSwitchCoordinator(
    private val mode: ServerModeControl,
    private val store: ServerLocalStore,
    private val runner: ServerBootstrapRunner,
    /**
     * 0.13：开启成功后把本机模型配置推给本机服务端（远程地址会被策略直接挡掉）。
     *
     * 默认 null：纯 JVM 单测只关心开关状态机，不需要为此构造一个推送器；
     * 生产装配（`AppModule`）一定会传。
     */
    private val llmConfig: ServerLlmConfigPusher? = null
) {

    private val _status = MutableStateFlow(ServerSyncStatus())
    val status: StateFlow<ServerSyncStatus> = _status.asStateFlow()

    /** 记住「正在等用户回答」的地址：换地址/重新拨开关时以最新一次为准。 */
    private var pendingConsentUrl: String? = null

    /**
     * 开关被拨到 [next] 时调用：只做判定，不做重活（重活在 [enableWith]/[disable]）。
     */
    suspend fun requestToggle(next: Boolean): ServerToggleAction {
        val currentlyEnabled = mode.enabled
        if (next == currentlyEnabled) return ServerToggleAction.Ignore
        val baseUrl = ServerSyncConsentPolicy.normalize(mode.configuredUrl)
        if (!next) return ServerToggleAction.Disable
        if (baseUrl.isEmpty()) {
            // 没有地址时开关本身没有意义（ServerModeRepository 也会把它存成关）。
            pendingConsentUrl = null
            _status.value = ServerSyncStatus(phase = ServerSyncPhase.IDLE)
            return ServerToggleAction.Ignore
        }
        val recorded = store.syncConsent(baseUrl)
        if (ServerSyncConsentPolicy.needsPrompt(currentlyEnabled, next, recorded)) {
            pendingConsentUrl = baseUrl
            _status.value = ServerSyncStatus(
                phase = ServerSyncPhase.CONSENT_REQUIRED,
                baseUrl = baseUrl
            )
            return ServerToggleAction.NeedsConsent(baseUrl)
        }
        pendingConsentUrl = null
        return ServerToggleAction.Enable(recorded ?: ServerSyncConsent.NEW_ONLY)
    }

    /** 用户点了「取消」（或关掉弹窗）：不记同意、开关保持关闭。 */
    fun dismissConsent() {
        if (_status.value.phase == ServerSyncPhase.CONSENT_REQUIRED) {
            pendingConsentUrl = null
            _status.value = ServerSyncStatus(phase = ServerSyncPhase.IDLE)
        }
    }

    /** 当前弹窗对应的地址（界面用来避免重复弹窗）；没有待回答时为 null。 */
    fun pendingConsentBaseUrl(): String? = pendingConsentUrl

    /**
     * 用户在弹窗里选了 [choice]：记录同意 → 开开关 → 按同意口径同步。
     *
     * 取消（协程被 cancel）时：开关拨回关闭、状态置 [ServerSyncPhase.CANCELLED]，
     * 已上传的媒体不做任何删除（下次重试由服务端按 sha256 复用）。
     */
    suspend fun enableWith(choice: ServerSyncConsent): ServerSyncStatus {
        val baseUrl = ServerSyncConsentPolicy.normalize(mode.configuredUrl)
        pendingConsentUrl = null
        if (baseUrl.isEmpty() || mode.setEnabled(true).isFailure) {
            _status.value = ServerSyncStatus(
                phase = ServerSyncPhase.FAILED,
                baseUrl = baseUrl.takeIf { it.isNotEmpty() },
                consent = choice,
                failure = ServerSyncFailure.SAVE_FAILED
            )
            return _status.value
        }
        // 先记同意再开开关：运行时（ServerModeRuntime）随后可能自动触发 bootstrap，
        // 它读到的必须是这次选择，而不是「没有记录」。
        store.setSyncConsent(baseUrl, choice)
        _status.value = ServerSyncStatus(
            phase = ServerSyncPhase.SYNCING,
            baseUrl = baseUrl,
            consent = choice
        )
        try {
            val outcome = runner.runBootstrap(baseUrl)
            // 0.13：开启成功（bootstrap 跑完、开关已经打开）后推一次模型配置。
            // 不是回环地址、或本机没有可用配置时什么都不做；失败只记日志，不影响同步结果。
            runCatching { llmConfig?.pushIfLocal() }
            val effective = if (outcome.skipped) runner.latestOutcome(baseUrl) ?: outcome else outcome
            _status.value = if (effective.success) {
                ServerSyncStatus(
                    phase = ServerSyncPhase.COMPLETED,
                    baseUrl = baseUrl,
                    consent = choice,
                    messages = effective.messages,
                    mediaTotal = effective.mediaTotal,
                    mediaUploaded = effective.mediaUploaded,
                    mediaFailed = effective.mediaFailed,
                    // 这次调用自己没搬东西（另一轮刚跑完/之前已对齐）——文案上仍要说明「没有实际搬运」。
                    skipped = outcome.skipped
                )
            } else {
                ServerSyncStatus(
                    phase = ServerSyncPhase.FAILED,
                    baseUrl = baseUrl,
                    consent = choice,
                    failure = ServerSyncFailure.SYNC_FAILED
                )
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                // 取消后不切换模式：把刚打开的开关拨回关闭。
                runCatching { mode.setEnabled(false) }
                _status.value = ServerSyncStatus(
                    phase = ServerSyncPhase.CANCELLED,
                    baseUrl = baseUrl,
                    consent = choice
                )
            }
            throw cancel
        }
        return _status.value
    }

    /**
     * 关闭开关：先全量下载 + 校验条数，成功才关闭（失败/取消保持开启）。
     */
    suspend fun disable(): ServerSyncStatus {
        if (!mode.enabled) {
            _status.value = ServerSyncStatus(phase = ServerSyncPhase.IDLE)
            return _status.value
        }
        val baseUrl = ServerSyncConsentPolicy.normalize(mode.configuredUrl)
        // 地址非法/从未可用：没有正在用的远端数据可下载，直接关。
        if (!mode.usable || baseUrl.isEmpty()) {
            return closeWithoutDownload(baseUrl)
        }
        _status.value = ServerSyncStatus(phase = ServerSyncPhase.DOWNLOADING, baseUrl = baseUrl)
        val outcome = try {
            runner.runFullDownload(baseUrl)
        } catch (cancel: CancellationException) {
            _status.value = ServerSyncStatus(
                phase = ServerSyncPhase.CANCELLED,
                baseUrl = baseUrl,
                failure = ServerSyncFailure.DOWNLOAD_INCOMPLETE
            )
            throw cancel
        }
        if (!outcome.success) {
            _status.value = ServerSyncStatus(
                phase = ServerSyncPhase.FAILED,
                baseUrl = baseUrl,
                downloadedMessages = outcome.downloadedMessages,
                localMessages = outcome.localMessages,
                serverMessages = outcome.serverMessages,
                failure = if (outcome.unavailable) {
                    ServerSyncFailure.DOWNLOAD_UNAVAILABLE
                } else {
                    ServerSyncFailure.DOWNLOAD_INCOMPLETE
                }
            )
            // 校验失败：保持云端模式（不调用 setEnabled(false)）。
            return _status.value
        }
        if (mode.setEnabled(false).isFailure) {
            _status.value = ServerSyncStatus(
                phase = ServerSyncPhase.FAILED,
                baseUrl = baseUrl,
                failure = ServerSyncFailure.SAVE_FAILED
            )
            return _status.value
        }
        _status.value = ServerSyncStatus(
            phase = ServerSyncPhase.DOWNLOADED,
            baseUrl = baseUrl,
            downloadedMessages = outcome.downloadedMessages,
            localMessages = outcome.localMessages,
            serverMessages = outcome.serverMessages
        )
        return _status.value
    }

    /** 收起上一次的结果提示（界面消费后调用）。 */
    fun clearStatus() {
        if (_status.value.running || _status.value.consentRequired) return
        _status.value = ServerSyncStatus()
    }

    private fun closeWithoutDownload(baseUrl: String): ServerSyncStatus {
        if (mode.setEnabled(false).isFailure) {
            _status.value = ServerSyncStatus(phase = ServerSyncPhase.FAILED, failure = ServerSyncFailure.SAVE_FAILED)
            return _status.value
        }
        _status.value = ServerSyncStatus(
            phase = ServerSyncPhase.DOWNLOADED,
            baseUrl = baseUrl.takeIf { it.isNotEmpty() }
        )
        return _status.value
    }
}

/**
 * [ServerModeControl] 的生产实现：读/写 [ServerModeRepository] 的凭据与开关。
 *
 * 抽成独立适配器（而不是让仓库直接实现接口）：仓库是「加密存储 + 运行时配置」的唯一入口，
 * 不应该为了设置页的状态机多承担一层语义；单测也更容易注入假实现。
 */
class RepositoryServerModeControl(
    private val mode: ServerModeRepository
) : ServerModeControl {
    override val enabled: Boolean get() = mode.currentCredentials().enabled
    override val usable: Boolean get() = mode.isEnabled()
    override val configuredUrl: String get() = mode.currentCredentials().cleanUrl
    override fun setEnabled(enabled: Boolean): Result<Unit> =
        mode.setEnabled(enabled).map { }
}
