package com.myfriend.app.ui.screens.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.myfriend.app.domain.MessagePaging
import androidx.lifecycle.viewModelScope
import com.myfriend.app.ui.common.ErrorText
import com.myfriend.app.R
import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.PersonaPendingPatchEntity
import com.myfriend.app.data.local.ProactiveRuleEntity
import com.myfriend.app.data.prefs.AppSettings
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.data.repo.ApiConfigRepository
import com.myfriend.app.data.repo.ChatRepository
import com.myfriend.app.data.repo.MemoryRepository
import com.myfriend.app.data.repo.MoodRepository
import com.myfriend.app.data.repo.PersonaCardRepository
import com.myfriend.app.data.repo.PresenceRepository
import com.myfriend.app.data.repo.ProactiveRepository
import com.myfriend.app.data.repo.ProactiveScheduler
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.domain.MessageAttachment
import com.myfriend.app.domain.PersonaSwitchNotice
import com.myfriend.app.domain.MoodState
import com.myfriend.app.domain.Presence
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.domain.GenerationEvent
import com.myfriend.app.domain.ToolTraceItem
import com.myfriend.app.domain.ProactiveProposal
import com.myfriend.app.util.HumanTiming
import com.myfriend.app.util.MediaStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val session: ChatSessionEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val persona: PersonaEntity? = null,
    /** 他此刻的心情，标题栏上那枚小胶囊。 */
    val mood: MoodState = MoodState(),
    /** 他此刻在做什么，标题栏副标题那行小字。他自己的状态，界面只读。 */
    val presence: Presence = Presence(),
    val config: ApiConfigEntity? = null,
    val personas: List<PersonaEntity> = emptyList(),
    val apiConfigs: List<ApiConfigEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val draft: String = "",
    val images: List<MessageAttachment> = emptyList(),
    val streamingText: String = "",
    val streamingTraces: List<ToolTraceItem> = emptyList(),
    val isGenerating: Boolean = false,
    /**
     * 模型真的开始说话了（收到 Started）。
     *
     * 睡眠 / 免打扰 / defer_reply 都会在 Started 之前就结束生成：界面连「正在输入」都不该出现，
     * 只让用户消息自己因为 readAt 显示「已读」。所以打字状态必须等这个标记为真。
     */
    val hasStarted: Boolean = false,
    val switchingPersona: Boolean = false,
    val snackbar: String? = null,
    val showConfigPicker: Boolean = false,
    val showPersonaPicker: Boolean = false,
    val loaded: Boolean = false,
    /** 还有更早的消息可以往回加载（首屏 60 条之外）。 */
    val hasMoreOlder: Boolean = false,
    /** 正在加载更早的一页：列表顶部显示转圈，并且不再重复触发。 */
    val loadingOlder: Boolean = false
) {
    val canSend: Boolean
        get() = session != null && config != null && persona != null && !isGenerating && !switchingPersona
    val canSendContent: Boolean
        get() = canSend && (draft.isNotBlank() || images.isNotEmpty())
}

data class ChatEphemeral(
    val draft: String = "",
    val images: List<MessageAttachment> = emptyList(),
    val streamingText: String = "",
    val streamingTraces: List<ToolTraceItem> = emptyList(),
    val isGenerating: Boolean = false,
    val hasStarted: Boolean = false,
    val switchingPersona: Boolean = false,
    val snackbar: String? = null,
    val showConfigPicker: Boolean = false,
    val showPersonaPicker: Boolean = false
)

/** 本地分页状态：只影响聊天页，不进 Session。 */

sealed interface ChatEvent {
    data class OpenSession(val sessionId: String) : ChatEvent
    data class ExportReady(val file: ExportFile) : ChatEvent
    data object SessionDeleted : ChatEvent
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val personaRepository: PersonaRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val memoryRepository: MemoryRepository,
    private val personaCardRepository: PersonaCardRepository,
    private val moodRepository: MoodRepository,
    private val presenceRepository: PresenceRepository,
    private val proactiveRepository: ProactiveRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle.get<String>("sessionId"))

    private val ephemeral = MutableStateFlow(ChatEphemeral())
    private val eventsChannel = Channel<ChatEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private val sessionFlow = chatRepository.observeSession(sessionId)

    /**
     * 分页游标：null 表示只观察最近 `PAGE_SIZE` 条；一旦加载过更早的一页，
     * 就改成观察「从那一页最早一条到最新」的区间（依然由 Room 观察，编辑/删除都会跟着更新）。
     */
    private val sinceCursor = MutableStateFlow<MessagePaging.Cursor?>(null)
    private val pagingState = MutableStateFlow(MessagePaging.State())

    private val messageFlow = sinceCursor
        .flatMapLatest { cursor ->
            if (cursor == null) {
                chatRepository.observeLatestMessages(sessionId)
            } else {
                chatRepository.observeMessagesFrom(sessionId, cursor)
            }
        }
        .onEach { rows ->
            // 只有首屏能靠「不足一页」判断到底了；之后的区间条数变多不代表还有更早的。
            if (sinceCursor.value == null) {
                pagingState.update { MessagePaging.onFirstWindow(it, rows.size) }
            }
        }

    private val personaFlow = sessionFlow
        .map { it?.personaId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else personaRepository.observeById(id) }

    private val configFlow = sessionFlow
        .map { it?.apiConfigId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else apiConfigRepository.observeById(id) }

    private val moodFlow = personaFlow
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else moodRepository.observe(id) }

    /**
     * 「他正在……」：同样是照 [moodFlow] 的写法接线，换了个人就跟着换。
     * 这是从他自己的痕迹推出来的，界面只显示。
     */
    private val presenceFlow = personaFlow
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else presenceRepository.observe(id) }

    private val detailsFlow = combine(sessionFlow, messageFlow, personaFlow, configFlow) { session, messages, persona, config ->
        Details(session, messages, persona, config)
    }.combine(personaRepository.observeAll()) { details, personas ->
        details to personas
    }.combine(apiConfigRepository.observeAll()) { pair, configs ->
        DetailsBundle(pair.first, pair.second, configs)
    }.combine(settingsRepository.settings) { bundle, settings ->
        bundle to settings
    }

    /** 心情和「他正在……」一起进界面：都是他自己的状态，界面看得到、改不了。 */
    private val ambientFlow = combine(moodFlow, presenceFlow) { mood, presence -> mood to presence }

    private val localFlow = combine(ephemeral, pagingState) { local, paging -> local to paging }

    private val uiFlow = combine(
        detailsFlow.combine(ambientFlow) { (bundle, settings), ambient -> Triple(bundle, settings, ambient) },
        localFlow
    ) { (bundle, settings, ambient), (local, paging) ->
        val details = bundle.details
        ChatUiState(
            session = details.session,
            messages = details.messages,
            persona = details.persona,
            mood = ambient.first ?: MoodState(),
            presence = ambient.second ?: Presence(),
            config = details.config,
            personas = bundle.personas,
            apiConfigs = bundle.apiConfigs,
            settings = settings,
            draft = local.draft,
            images = local.images,
            streamingText = local.streamingText,
            streamingTraces = local.streamingTraces,
            isGenerating = local.isGenerating,
            hasStarted = local.hasStarted,
            switchingPersona = local.switchingPersona,
            snackbar = local.snackbar,
            showConfigPicker = local.showConfigPicker,
            showPersonaPicker = local.showPersonaPicker,
            loaded = details.session != null,
            hasMoreOlder = paging.hasMoreOlder,
            loadingOlder = paging.loadingOlder
        )
    }

    val uiState: StateFlow<ChatUiState> = uiFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChatUiState()
    )

    private var streamJob: Job? = null
    private var memoryJob: Job? = null

    /**
     * 一次「发送 / 重新生成 / 编辑」从落库到开始出字之间有几十毫秒空档。
     * 没有它，连着点两下会写进两条一样的消息——这是原来 isPreparing 顺带挡住的事。
     */
    private var operationInFlight = false

    init {
        chatRepository.setOpenSession(sessionId)
        viewModelScope.launch {
            chatRepository.markRead(sessionId)
            initialPrepare()
        }
    }

    private suspend fun initialPrepare() {
        val session = chatRepository.getSession(sessionId) ?: return
        if (session.apiConfigId == null && apiConfigRepository.getAll().isNotEmpty()) {
            ephemeral.update { it.copy(showConfigPicker = true) }
        }
    }

    /**
     * 滚到顶部时往回要一页。
     *
     * 「同一时刻只加载一页」由 [MessagePaging.beginLoadOlder] 保证：
     * 快速滚顶 / 反复触发时第二次直接返回，不会并发打两条查询、也不会把同一页插两遍。
     */
    fun loadOlder() {
        val (next, shouldLoad) = MessagePaging.beginLoadOlder(pagingState.value)
        if (!shouldLoad) return
        pagingState.value = next
        viewModelScope.launch {
            try {
                val window = uiState.value.messages
                val cursor = MessagePaging.oldestCursor(window, { it.createdAt }, { it.id })
                if (cursor == null) {
                    // 还没读到首屏（或会话为空）：收掉转圈，下次滚动再试。
                    pagingState.value = MessagePaging.onLoadFailed(pagingState.value)
                    return@launch
                }
                val page = chatRepository.messagesBefore(sessionId, cursor)
                pagingState.value = MessagePaging.onOlderPage(pagingState.value, page.size)
                val oldest = page.firstOrNull() ?: return@launch
                sinceCursor.value = MessagePaging.Cursor(oldest.createdAt, oldest.id)
            } catch (cancel: CancellationException) {
                pagingState.value = MessagePaging.onLoadFailed(pagingState.value)
                throw cancel
            } catch (error: Exception) {
                pagingState.value = MessagePaging.onLoadFailed(pagingState.value)
            }
        }
    }

    fun onResume() {
        viewModelScope.launch { chatRepository.markRead(sessionId) }
    }

    fun setDraft(value: String) {
        ephemeral.update { it.copy(draft = value.take(32_000)) }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = uris.take(MAX_IMAGES).mapNotNull { uri ->
                MediaStorage.persistChatImage(context, uri).getOrNull()
            }
            if (added.isEmpty()) {
                ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_image_load_failed)) }
                return@launch
            }
            ephemeral.update { state ->
                val merged = (state.images + added).take(MAX_IMAGES)
                state.copy(images = merged)
            }
        }
    }

    /**
     * 从草稿里去掉一张图：文件已经落盘了，取消发送就得把它删掉，
     * 否则「选了又删」每来一次就多一个孤儿文件。
     */
    fun removeImage(uri: String) {
        val removed = ephemeral.value.images.filter { it.uri == uri }
        ephemeral.update { state -> state.copy(images = state.images.filterNot { it.uri == uri }) }
        MediaStorage.deleteFiles(removed)
    }

    fun clearImages() {
        val removed = ephemeral.value.images
        ephemeral.update { it.copy(images = emptyList()) }
        MediaStorage.deleteFiles(removed)
    }

    fun dismissSnackbar() {
        ephemeral.update { it.copy(snackbar = null) }
    }

    fun openConfigPicker() {
        ephemeral.update { it.copy(showConfigPicker = true) }
    }

    fun closeConfigPicker() {
        ephemeral.update { it.copy(showConfigPicker = false) }
    }

    fun openPersonaPicker() {
        ephemeral.update { it.copy(showPersonaPicker = true) }
    }

    fun closePersonaPicker() {
        ephemeral.update { it.copy(showPersonaPicker = false) }
    }

    fun send() {
        val text = ephemeral.value.draft
        val images = ephemeral.value.images
        if ((text.isBlank() && images.isEmpty()) || ephemeral.value.isGenerating || operationInFlight) return
        val current = uiState.value
        if (current.config == null) {
            ephemeral.update { it.copy(showConfigPicker = true, snackbar = context.getString(R.string.chat_select_config_first)) }
            return
        }
        viewModelScope.launch {
            if (operationInFlight) return@launch
            operationInFlight = true
            try {
                memoryJob?.join()
                val result = chatRepository.sendUserMessage(sessionId, text, images)
                if (result.isFailure) {
                    ephemeral.update {
                        it.copy(snackbar = ErrorText.of(context, result.exceptionOrNull(), R.string.chat_send_failed))
                    }
                    return@launch
                }
                // 1.9：「读消息的节奏」不再是设置项，固定用自然速度。
                // 具体什么时候回、回不回，交给模型自己的 defer_reply 与睡眠判定。
                val readDelay = HumanTiming.readDelayMillis(
                    textLength = text.length,
                    images = images.size,
                    paceFactor = 1f
                )
                ephemeral.update { it.copy(draft = "", images = emptyList(), snackbar = null) }
                startGeneration(readDelay)
            } finally {
                operationInFlight = false
            }
        }
    }

    /**
     * @param readDelay 首个字出现前的「读消息」时间，让回复看起来不是瞬发的。
     */
    private fun startGeneration(readDelay: Long = 0L) {
        streamJob?.cancel()
        ephemeral.update {
            it.copy(
                isGenerating = true,
                hasStarted = false,
                streamingText = "",
                streamingTraces = emptyList(),
                snackbar = null
            )
        }
        streamJob = viewModelScope.launch {
            try {
                if (readDelay > 0) kotlinx.coroutines.delay(readDelay)
                chatRepository.generateAssistant(sessionId).collect { event ->
                    when (event) {
                        GenerationEvent.Started -> ephemeral.update { it.copy(hasStarted = true) }
                        is GenerationEvent.Delta -> ephemeral.update { it.copy(streamingText = event.fullText) }
                        is GenerationEvent.ToolCall -> ephemeral.update { it.copy(streamingTraces = event.traces) }
                        is GenerationEvent.Completed -> {
                            ephemeral.update {
                                it.copy(
                                    isGenerating = false,
                                    hasStarted = false,
                                    streamingText = "",
                                    streamingTraces = emptyList()
                                )
                            }
                            event.proposal?.let { proposal -> saveProposal(proposal) }
                            if (event.autoMemoryPending) {
                                memoryJob = viewModelScope.launch { extractMemory(silent = false) }
                            }
                        }
                        is GenerationEvent.Failed -> {
                            ephemeral.update {
                                it.copy(
                                    isGenerating = false,
                                    hasStarted = false,
                                    streamingText = "",
                                    snackbar = ErrorText.of(context, event.error)
                                )
                            }
                        }
                        GenerationEvent.Stopped -> ephemeral.update { it.copy(isGenerating = false, hasStarted = false, streamingText = "") }
                        // 睡着 / 免打扰 / 他选择先不回：只把「正在输入」收掉。
                        // 用户消息会因 readAt 非空显示「已读」——已读不代表会回。
                        GenerationEvent.Silent -> ephemeral.update {
                            it.copy(isGenerating = false, hasStarted = false, streamingText = "", streamingTraces = emptyList())
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                ephemeral.update { it.copy(isGenerating = false, streamingText = "") }
            } catch (error: Exception) {
                ephemeral.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        snackbar = ErrorText.of(context, error, R.string.chat_generation_failed)
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        streamJob?.cancel()
        ephemeral.update { it.copy(isGenerating = false, streamingText = "") }
    }

    fun regenerate() {
        if (ephemeral.value.isGenerating || operationInFlight) return
        viewModelScope.launch {
            if (operationInFlight) return@launch
            operationInFlight = true
            try {
                memoryJob?.join()
                val result = chatRepository.prepareRegeneration(sessionId)
                if (result.isFailure) {
                    ephemeral.update { it.copy(snackbar = ErrorText.of(context, result.exceptionOrNull(), R.string.chat_regenerate_failed)) }
                    return@launch
                }
                startGeneration(HumanTiming.REGENERATE_MS)
            } finally {
                operationInFlight = false
            }
        }
    }

    fun editUserMessage(messageId: String, content: String) {
        if (ephemeral.value.isGenerating || operationInFlight) return
        viewModelScope.launch {
            if (operationInFlight) return@launch
            operationInFlight = true
            try {
                memoryJob?.join()
                val result = chatRepository.editUserMessage(sessionId, messageId, content)
                if (result.isFailure) {
                    ephemeral.update { it.copy(snackbar = ErrorText.of(context, result.exceptionOrNull(), R.string.chat_edit_failed)) }
                    return@launch
                }
                startGeneration(HumanTiming.REGENERATE_MS)
            } finally {
                operationInFlight = false
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessage(messageId)
                .onFailure { error -> ephemeral.update { it.copy(snackbar = ErrorText.of(context, error, R.string.chat_delete_failed)) } }
        }
    }

    fun clearContext() {
        streamJob?.cancel()
        viewModelScope.launch {
            ephemeral.update { it.copy(isGenerating = false, streamingText = "") }
            val result = chatRepository.clearContext(sessionId)
            ephemeral.update {
                it.copy(
                    snackbar = result.exceptionOrNull()?.let { ErrorText.of(context, it) }
                        ?: context.getString(R.string.chat_context_cleared)
                )
            }
        }
    }

    fun selectConfig(configId: String) {
        viewModelScope.launch {
            val bound = chatRepository.bindSessionConfig(sessionId, configId)
            if (bound.isFailure) {
                ephemeral.update {
                    it.copy(
                        showConfigPicker = false,
                        snackbar = ErrorText.of(context, bound.exceptionOrNull(), R.string.chat_bind_failed)
                    )
                }
                return@launch
            }
            ephemeral.update {
                it.copy(
                    showConfigPicker = false,
                    snackbar = context.getString(R.string.chat_api_ready)
                )
            }
        }
    }

    fun switchPersona(personaId: String) {
        if (ephemeral.value.switchingPersona) return
        if (personaId == uiState.value.session?.personaId) {
            ephemeral.update { it.copy(showPersonaPicker = false) }
            return
        }
        viewModelScope.launch {
            streamJob?.cancel()
            ephemeral.update { it.copy(switchingPersona = true, isGenerating = false, streamingText = "") }
            val result = chatRepository.switchToPersona(personaId)
            if (result.success && result.session != null) {
                ephemeral.update { it.copy(switchingPersona = false, showPersonaPicker = false) }
                eventsChannel.send(ChatEvent.OpenSession(result.session.id))
            } else {
                ephemeral.update {
                    it.copy(switchingPersona = false, snackbar = switchNoticeText(result.notice))
                }
            }
        }
    }

    fun exportChat() {
        viewModelScope.launch {
            val result = chatRepository.exportSession(sessionId)
            result.onSuccess { eventsChannel.send(ChatEvent.ExportReady(it)) }
                .onFailure { error -> ephemeral.update { it.copy(snackbar = ErrorText.of(context, error, R.string.chat_export_failed)) } }
        }
    }

    private suspend fun saveProposal(proposal: ProactiveProposal) {
        val personaId = uiState.value.session?.personaId ?: return
        val result = proactiveRepository.createFromProposal(personaId, sessionId, proposal)
        result.onSuccess {
            // 不再需要用户批准：他直接安排好了，这里只是告诉他一声。
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_proactive_scheduled)) }
        }
    }

    /** 一次性引导卡片点「知道了」：以后不再显示。 */
    fun dismissBackgroundTip() {
        viewModelScope.launch {
            runCatching { settingsRepository.setBackgroundTipDismissed(true) }
        }
    }

    private suspend fun extractMemory(silent: Boolean) {
        val result = memoryRepository.maybeExtractMemories(sessionId)
        result.onSuccess { extraction ->
            if (extraction.added > 0) {
                ephemeral.update { it.copy(snackbar = memoryResultText(extraction)) }
            }
        }.onFailure { error ->
            if (!silent) {
                val reason = ErrorText.of(context, error, R.string.chat_unknown_error)
                ephemeral.update { state ->
                    state.copy(snackbar = context.getString(R.string.chat_memory_failed, reason))
                }
            }
        }
    }

    fun deleteSession() {
        viewModelScope.launch {
            chatRepository.deleteSession(sessionId)
            eventsChannel.send(ChatEvent.SessionDeleted)
        }
    }

    fun markRead() {
        viewModelScope.launch { chatRepository.markRead(sessionId) }
    }

    /** 切人格时顺口提示的那一句：数据层给枚举，这里翻成当前语言。 */
    private fun switchNoticeText(notice: PersonaSwitchNotice?): String = when (notice) {
        PersonaSwitchNotice.API_NOT_BOUND -> context.getString(R.string.chat_persona_switch_api_not_bound)
        PersonaSwitchNotice.SESSION_CREATE_FAILED -> context.getString(R.string.chat_persona_switch_no_session)
        PersonaSwitchNotice.PERSONA_NOT_FOUND -> context.getString(R.string.error_persona_not_found)
        null -> context.getString(R.string.chat_persona_switch_failed)
    }

    /** 「记忆已更新（+2）」这类结果句：数量在数据层，说法在资源里。 */
    private fun memoryResultText(result: com.myfriend.app.data.repo.MemoryExtractionResult): String =
        when (result.outcome) {
            com.myfriend.app.data.repo.MemoryExtractionOutcome.ADDED ->
                context.getString(R.string.memory_extract_result_added, result.added)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.REINFORCED ->
                context.getString(R.string.memory_extract_result_reinforced)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.ECHOED ->
                context.getString(R.string.memory_extract_result_echoed)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.NONE ->
                context.getString(R.string.memory_extract_result_none)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.NOT_ENOUGH_CHAT ->
                context.getString(R.string.memory_extract_result_not_enough)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.INTERVAL_NOT_REACHED ->
                context.getString(R.string.memory_extract_result_interval, result.every ?: 0)
        }

    override fun onCleared() {
        streamJob?.cancel()
        memoryJob?.cancel()
        chatRepository.setOpenSession(null)
        super.onCleared()
    }

    private companion object {
        const val MAX_IMAGES = 4
    }

    private data class Details(
        val session: ChatSessionEntity?,
        val messages: List<MessageEntity>,
        val persona: PersonaEntity?,
        val config: ApiConfigEntity?
    )

    private data class DetailsBundle(
        val details: Details,
        val personas: List<PersonaEntity>,
        val apiConfigs: List<ApiConfigEntity>
    )
}
