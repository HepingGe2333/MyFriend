package com.myfriend.app.ui.screens.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val loaded: Boolean = false
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
    private val messageFlow = chatRepository.observeMessages(sessionId)

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

    private val uiFlow = combine(
        detailsFlow.combine(ambientFlow) { (bundle, settings), ambient -> Triple(bundle, settings, ambient) },
        ephemeral
    ) { (bundle, settings, ambient), local ->
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
            loaded = details.session != null
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

    fun removeImage(uri: String) {
        ephemeral.update { state -> state.copy(images = state.images.filterNot { it.uri == uri }) }
    }

    fun clearImages() {
        ephemeral.update { it.copy(images = emptyList()) }
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
                        it.copy(snackbar = result.exceptionOrNull()?.message ?: context.getString(R.string.chat_send_failed))
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
                                    snackbar = event.message
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
                        snackbar = error.message ?: context.getString(R.string.chat_generation_failed)
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
                    ephemeral.update { it.copy(snackbar = result.exceptionOrNull()?.message ?: context.getString(R.string.chat_regenerate_failed)) }
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
                    ephemeral.update { it.copy(snackbar = result.exceptionOrNull()?.message ?: context.getString(R.string.chat_edit_failed)) }
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
                .onFailure { error -> ephemeral.update { it.copy(snackbar = error.message ?: context.getString(R.string.chat_delete_failed)) } }
        }
    }

    fun clearContext() {
        streamJob?.cancel()
        viewModelScope.launch {
            ephemeral.update { it.copy(isGenerating = false, streamingText = "") }
            val result = chatRepository.clearContext(sessionId)
            ephemeral.update {
                it.copy(
                    snackbar = result.exceptionOrNull()?.message ?: context.getString(R.string.chat_context_cleared)
                )
            }
        }
    }

    fun selectConfig(configId: String) {
        viewModelScope.launch {
            val bound = chatRepository.bindSessionConfig(sessionId, configId)
            if (bound.isFailure) {
                ephemeral.update {
                    it.copy(showConfigPicker = false, snackbar = bound.exceptionOrNull()?.message ?: context.getString(R.string.chat_bind_failed))
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
                    it.copy(switchingPersona = false, snackbar = result.message ?: context.getString(R.string.chat_persona_switch_failed))
                }
            }
        }
    }

    fun exportChat() {
        viewModelScope.launch {
            val result = chatRepository.exportSession(sessionId)
            result.onSuccess { eventsChannel.send(ChatEvent.ExportReady(it)) }
                .onFailure { error -> ephemeral.update { it.copy(snackbar = error.message ?: context.getString(R.string.chat_export_failed)) } }
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
                ephemeral.update { it.copy(snackbar = extraction.message) }
            }
        }.onFailure { error ->
            if (!silent) {
                val reason = error.message ?: context.getString(R.string.chat_unknown_error)
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
