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
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.MessageStatus
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
import com.myfriend.app.domain.ReadReceipt
import com.myfriend.app.domain.PersonaSwitchNotice
import com.myfriend.app.domain.MoodState
import com.myfriend.app.domain.Presence
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.domain.GenerationEvent
import com.myfriend.app.domain.ToolTraceItem
import com.myfriend.app.domain.ProactiveProposal
import com.myfriend.app.util.ChatNotifier
import com.myfriend.app.util.HumanTiming
import com.myfriend.app.util.MediaStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /** 正在读库、组装并编码导出文本（在 IO 线程上）：界面显示进行中并避免重复点。 */
    val isExporting: Boolean = false,
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
    val loadingOlder: Boolean = false,
    /**
     * 从检索结果跳到很久以前的消息后，窗口末尾**之后**还有更新的消息。
     *
     * 0.11.0 起定位只加载目标附近一个有界窗口（审计 P2-13），所以「下面还有更近的话」
     * 是一个真实状态：界面在底部给「更新的消息」入口，点了回到最新一屏。
     */
    val hasMoreNewer: Boolean = false,
    /**
     * 窗口里最后一条「可计入」的助手消息时间（`role=ASSISTANT && status != ERROR`）。
     *
     * 已读判定（`ReadReceipt`）每条气泡只跟它比一次：以前每个气泡都扫一遍整个已加载窗口，
     * 把历史翻完之后是 O(n²)（审计 P2-10）。
     */
    val lastAssistantAt: Long? = null
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
    val isExporting: Boolean = false,
    val snackbar: String? = null,
    val showConfigPicker: Boolean = false,
    val showPersonaPicker: Boolean = false
)

/** 本地分页状态：只影响聊天页，不进 Session。 */

sealed interface ChatEvent {
    data class OpenSession(val sessionId: String) : ChatEvent
    data class ExportReady(val file: ExportFile) : ChatEvent
    data object SessionDeleted : ChatEvent

    /**
     * 从检索结果跳进来时，要定位的那条消息已经进入窗口。
     *
     * 界面收到后滚到它、并给它约 2 秒的高亮。做成一次性事件而不是 UiState 字段：
     * 「定位」是一次动作，不该在每次重组时重新触发（返回列表再进来会重新滚一次）。
     */
    data class FocusMessage(val messageId: String) : ChatEvent
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
    private val chatNotifier: ChatNotifier,
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

    /**
     * 从检索结果定位时的**有界窗口**（审计 P2-13）。
     *
     * 不为 null 时聊天页只观察「窗口起点 + 最多 [MessagePaging.Window.rows] 条」，
     * 不再把「目标 → 最新」整段读进内存；窗口的「更新的一侧」由界面上的「更新的消息」
     * 入口回到最新一屏（[showLatest]）。
     */
    private val jumpWindow = MutableStateFlow<MessagePaging.Window?>(null)

    private val pagingState = MutableStateFlow(MessagePaging.State())

    private data class PagingTarget(
        val cursor: MessagePaging.Cursor?,
        val window: MessagePaging.Window?
    )

    private val messageFlow = combine(sinceCursor, jumpWindow) { cursor, window -> PagingTarget(cursor, window) }
        .distinctUntilChanged()
        .flatMapLatest { target ->
            val window = target.window
            when {
                window != null -> chatRepository.observeMessagesWindow(sessionId, window.start, window.rows)
                target.cursor == null -> chatRepository.observeLatestMessages(sessionId)
                else -> chatRepository.observeMessagesFrom(sessionId, target.cursor)
            }
        }
        .onEach { rows ->
            if (jumpWindow.value == null) {
                // 只有首屏能靠「不足一页」判断到底了；之后的区间条数变多不代表还有更早的。
                if (sinceCursor.value == null) {
                    pagingState.update { MessagePaging.onFirstWindow(it, rows.size) }
                }
            } else {
                // 审计 P1-1：有界窗口是 `LIMIT rows` 的固定区间，窗口末尾之后的新消息
                // （自己发的、心跳/后台写的）不会自己进来。Room 在 messages 变化时会重跑
                // 这个窗口查询，这里趁机按「窗口末尾 vs 会话最新一条」重算 hasMoreNewer，
                // 让「更新的消息」入口能重新出现，而不是只在定位那一刻算一次。
                val windowEnd = rows.lastOrNull()?.let { MessagePaging.Cursor(it.createdAt, it.id) }
                val latest = runCatching { chatRepository.latestMessageCursor(sessionId) }.getOrNull()
                pagingState.update { MessagePaging.onWindowRows(it, latest, windowEnd) }
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
        // 已读判定用到的「最后一条可计入的助手消息」一次算好（审计 P2-10）：
        // 以前每个气泡都扫一遍整个窗口，翻完历史后每帧上万次比较。
        val lastAssistantAt = ReadReceipt.latestAssistantAt(
            details.messages
                .filter { it.role == MessageRole.ASSISTANT && it.status != MessageStatus.ERROR }
                .map { it.createdAt }
        )
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
            isExporting = local.isExporting,
            snackbar = local.snackbar,
            showConfigPicker = local.showConfigPicker,
            showPersonaPicker = local.showPersonaPicker,
            loaded = details.session != null,
            hasMoreOlder = paging.hasMoreOlder,
            loadingOlder = paging.loadingOlder,
            hasMoreNewer = paging.hasMoreNewer,
            lastAssistantAt = lastAssistantAt
        )
    }

    val uiState: StateFlow<ChatUiState> = uiFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChatUiState()
    )

    private var streamJob: Job? = null
    private var memoryJob: Job? = null

    /** ViewModel 是否已经销毁（[onCleared] 里立起）：选图协程据此决定「登记还是直接删文件」。 */
    @Volatile
    private var draftWindowClosed = false

    /**
     * 一次「发送 / 重新生成 / 编辑」从落库到开始出字之间有几十毫秒空档。
     * 没有它，连着点两下会写进两条一样的消息——这是原来 isPreparing 顺带挡住的事。
     */
    private var operationInFlight = false

    /**
     * 从检索结果跳过来时要定位的消息 id（导航参数，可能没有）。
     *
     * 定位复用聊天页自己的分页：先让 [ChatRepository.cursorContainingMessage] 算出「窗口起点」，
     * 把已加载区间挪到包含目标消息的那一段（前面还会多带一两页上下文），再等窗口真的到达，
     * 最后发一次 [ChatEvent.FocusMessage] 让界面滚过去并高亮。
     *
     * 为什么不「按 id 只查一条插进列表」：聊天页的列表是连续窗口，孤立插一条会让上下文断开，
     * 往上滑还会跳；所以只能真的把区间翻到那里。
     */
    private val jumpTargetId: String? = savedStateHandle.get<String>("messageId")

    init {
        chatRepository.setOpenSession(sessionId)
        // 从首页点进这个会话时，也把它的那条系统通知撤掉（审计 P2-14：以前只有「从通知进来」才撤）。
        runCatching { chatNotifier.cancel(context, sessionId) }
        viewModelScope.launch {
            chatRepository.markRead(sessionId)
            initialPrepare()
        }
        startJumpToTarget()
    }

    /**
     * 滚到检索命中的那条消息。
     *
     * 三种结局都安静收场：找到了 → 发事件；目标已经在首屏窗口（不用挪）→ 一样发事件；
     * 目标不在了（删过 / 换过设备）→ 停在最新一屏，不报错 —— 用户自己会明白那条话没了。
     */
    private fun startJumpToTarget() {
        val target = jumpTargetId ?: return
        if (target.isBlank()) return
        viewModelScope.launch {
            // 一次算出「目标附近的有界窗口」（审计 P2-13）：两次 COUNT + 一页上下文，
            // 不再从最新一页往回爬（那是 O(页数²) 行读取）。
            val window = runCatching {
                chatRepository.cursorContainingMessage(sessionId, target)
            }.getOrNull()
            if (window != null) {
                // 先写窗口、再写游标：messageFlow 一看到窗口就按有界区间观察，
                // 不会先按「游标 → 最新」订阅一次（那才是要避免的全量读）。
                jumpWindow.value = window
                sinceCursor.value = window.start
                pagingState.update { MessagePaging.onJumped(it, window) }
            }

            // 目标可能就在首屏（window 为 null），也可能要等窗口到；统一等「窗口里出现它」。
            // 5 秒上限是为了它已经被删掉时不要把协程永远挂着（此时停在新的一屏即可）。
            val settled = withTimeoutOrNull(JUMP_TIMEOUT_MILLIS) {
                uiState.first { state -> state.messages.any { it.id == target } }
            }
            if (settled != null) eventsChannel.send(ChatEvent.FocusMessage(target))
        }
    }

    /** 定位窗口之外的「更新的消息」：回到最新一屏（清掉游标与有界窗口，恢复实时观察）。 */
    fun showLatest() {
        jumpWindow.value = null
        sinceCursor.value = null
        pagingState.update { MessagePaging.onLatest(it) }
    }

    /**
     * 用户主动写消息（发送 / 重新生成 / 编辑）之前，若正停在检索定位的有界窗口里，
     * 先回到最新一屏（审计 P1-1）。
     *
     * 留在窗口里的话：新写的消息 `createdAt` 落在窗口区间之外，列表里根本看不到——
     * 用户感知是「发出去的话消失了、他也不理我」；随后助手回复同样落在窗口外。
     */
    private fun leaveJumpWindowForNewMessage() {
        if (jumpWindow.value != null) showLatest()
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
                // 定位窗口里往前翻：窗口起点前移一页，同时把条数上界加上一页，
                // 这样窗口「更新的一侧」不动（目标还在，往下看还是原来那段）。
                val bounded = jumpWindow.value
                if (bounded != null) {
                    val page = chatRepository.messagesBefore(sessionId, bounded.start)
                    pagingState.value = MessagePaging.onOlderPage(pagingState.value, page.size)
                    val oldest = page.firstOrNull() ?: return@launch
                    jumpWindow.value = bounded.copy(
                        start = MessagePaging.Cursor(oldest.createdAt, oldest.id),
                        rows = bounded.rows + page.size
                    )
                    return@launch
                }
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
            // 审计 P2-12（0.11.1）：落盘与登记必须在同一个不可取消区域里。
            // 以前 `persistChatImage` 已经写完文件、协程在 `ephemeral.update` 之前被取消
            //（选完图立刻按返回 / 切人格），返回的路径从未进入 `images`，`onCleared` 按
            // 已登记集合删除，自然删不到——每张 ≤4MB 纯增量泄漏。
            val added = withContext(NonCancellable) {
                val persisted = uris.take(MAX_IMAGES).mapNotNull { uri ->
                    MediaStorage.persistChatImage(context, uri).getOrNull()
                }
                if (persisted.isEmpty()) {
                    emptyList()
                } else if (draftWindowClosed) {
                    // ViewModel 已经销毁（onCleared 跑过）：文件不该留在磁盘上。
                    MediaStorage.deleteAttachments(context, persisted)
                    emptyList()
                } else {
                    ephemeral.update { state ->
                        val merged = (state.images + persisted).take(MAX_IMAGES)
                        state.copy(images = merged)
                    }
                    persisted
                }
            }
            if (added.isEmpty()) {
                ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_image_load_failed)) }
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
        MediaStorage.deleteAttachments(context, removed)
    }

    fun clearImages() {
        val removed = ephemeral.value.images
        ephemeral.update { it.copy(images = emptyList()) }
        MediaStorage.deleteAttachments(context, removed)
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
                // 审计 P1-1：定位窗口里发送 → 先回到最新一屏，否则自己刚发的那条落在窗口外。
                leaveJumpWindowForNewMessage()
                // 0.10.2：发送不再等「抽记忆」跑完。抽取是后台任务（读的是历史窗口），
                // 与「写一条用户消息」没有写冲突；以前在这里 join 会让发送键看起来坏掉
                // （最长等一次完整模型往返，期间没有任何 loading）。
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
                // 审计 P1-1：定位窗口里重新生成 → 新回复会落在窗口外，先回到最新一屏。
                leaveJumpWindowForNewMessage()
                // 同上：重新生成也不等后台抽记忆。
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
                // 审计 P1-1：定位窗口里编辑重发 → 先回到最新一屏，不然改完的话看不见。
                leaveJumpWindowForNewMessage()
                // 同上：编辑重发也不等后台抽记忆。
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
        viewModelScope.launch {
            // 先停下正在跑的生成并**等它收尾**：仓库层还会用世代号 + 存在性校验兜底，
            // 但界面上不能出现「清空成功之后又冒出一条半截回复」。
            streamJob?.cancelAndJoin()
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
                // 切走时草稿里那几张图属于上一段对话：文件留在磁盘上没人会再引用（审计 P2-1）。
                val abandoned = ephemeral.value.images
                ephemeral.update {
                    it.copy(
                        switchingPersona = false,
                        showPersonaPicker = false,
                        images = emptyList(),
                        draft = ""
                    )
                }
                MediaStorage.deleteAttachments(context, abandoned)
                eventsChannel.send(ChatEvent.OpenSession(result.session.id))
            } else {
                ephemeral.update {
                    it.copy(switchingPersona = false, snackbar = switchNoticeText(result.notice))
                }
            }
        }
    }

    fun exportChat() {
        if (ephemeral.value.isExporting) return
        viewModelScope.launch {
            ephemeral.update { it.copy(isExporting = true, snackbar = context.getString(R.string.chat_export_in_progress)) }
            try {
                // 仓库内部切 Dispatchers.IO：读整段会话 + 组 DTO + JSON 编码都不在主线程。
                val result = chatRepository.exportSession(sessionId)
                result.onSuccess { eventsChannel.send(ChatEvent.ExportReady(it)) }
                    .onFailure { error ->
                        ephemeral.update {
                            it.copy(snackbar = ErrorText.of(context, error, R.string.chat_export_failed))
                        }
                    }
            } finally {
                ephemeral.update { it.copy(isExporting = false) }
            }
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
            // 删除前先取消并等生成收尾：NonCancellable 的落库可能排在删除事务之后，
            // 仓库层的存在性/世代校验会丢弃它，这里等一等让顺序确定下来。
            streamJob?.cancelAndJoin()
            ephemeral.update { it.copy(isGenerating = false, streamingText = "") }
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
                run {
                    val every = result.every ?: 0
                    context.resources.getQuantityString(
                        R.plurals.memory_extract_result_interval,
                        every,
                        every
                    )
                }
        }

    override fun onCleared() {
        // 审计 P2-12（0.11.1）：先立起「草稿窗口已关」的旗子，再删已登记的图片。
        // `addImages` 的落盘+登记在 NonCancellable 里，看到旗子后会自己删掉刚写的文件，
        // 不会把路径登记进一个已经销毁的 ViewModel。
        draftWindowClosed = true
        streamJob?.cancel()
        memoryJob?.cancel()
        // 审计 P2-1：选了图但没发出去（返回键 / 切人格 / 从通知跳到别的会话）时，
        // 文件已经落在 files/media/chat-*.jpg 了。只有「点 ×」「清空」「发送成功」三条路
        // 会处理它们，退出编辑必须在这里兜一次，否则是纯增量、无上限的磁盘泄漏。
        // 发送成功时 images 已经被清空，所以这里不会删到已发出的图。
        MediaStorage.deleteAttachments(context, ephemeral.value.images)
        // compare-and-clear：转场期间新 VM 可能已经打开了下一段会话，旧 VM 不能把它清掉。
        // 无条件 setOpenSession(null) 会让「正在读的会话」被判成没开着：未读 +1 并弹通知。
        chatRepository.clearOpenSession(sessionId)
        super.onCleared()
    }

    private companion object {
        const val MAX_IMAGES = 4

        /** 定位消息最多等多久：窗口挪过去通常几十毫秒，留 5 秒足够；超时就当作「那条消息不在了」。 */
        const val JUMP_TIMEOUT_MILLIS = 5_000L
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
