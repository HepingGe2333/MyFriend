package com.yap.app.ui.screens.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.yap.app.domain.MessageBlockPaging
import androidx.lifecycle.viewModelScope
import com.yap.app.ui.common.ErrorText
import com.yap.app.R
import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPendingPatchEntity
import com.yap.app.data.local.ProactiveRuleEntity
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.repo.ApiConfigRepository
import com.yap.app.data.repo.ChatRepository
import com.yap.app.data.repo.MediaJanitor
import com.yap.app.data.repo.MemoryRepository
import com.yap.app.data.repo.MoodRepository
import com.yap.app.data.repo.PersonaCardRepository
import com.yap.app.data.repo.PresenceRepository
import com.yap.app.data.repo.ProactiveRepository
import com.yap.app.data.repo.ProactiveScheduler
import com.yap.app.data.repo.PersonaRepository
import com.yap.app.data.repo.PersonaTranslationCoordinator
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.ReadReceipt
import com.yap.app.domain.PersonaSwitchNotice
import com.yap.app.domain.MoodState
import com.yap.app.domain.Presence
import com.yap.app.domain.ExportFile
import com.yap.app.domain.GenerationEvent
import com.yap.app.domain.ToolTraceItem
import com.yap.app.domain.ProactiveProposal
import com.yap.app.util.ChatNotifier
import com.yap.app.util.HumanTiming
import com.yap.app.util.MediaStorage
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
    /** 已加载的块之外还有更早的块（界面上「加载更早」那一行）。 */
    val hasMoreOlder: Boolean = false,
    /** 正在加载更早的那一块：列表顶部显示转圈，并且不再重复触发。 */
    val loadingOlder: Boolean = false,
    /**
     * 已加载的最新块之外还有更新的块（规范 §3 Newer）。
     *
     * 0.12 起这是**可以加载**的：底部显示「下面还有更新的消息」，滚到底会自动加载，
     * 点一下也加载（不再是「只能跳回最新」）。
     */
    val hasMoreNewer: Boolean = false,
    /** 正在加载更新的那一块。 */
    val loadingNewer: Boolean = false,
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
    /** 删草稿图片之前的引用守卫（0.12）：用户可能选了一张已经发过的图（内容寻址同名文件）。 */
    private val mediaJanitor: MediaJanitor,
    /** 打开会话前按需翻译人设（`spec/persona-translation/README.md` §2 第 1 条）。 */
    private val translationCoordinator: PersonaTranslationCoordinator,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle.get<String>("sessionId"))

    private val ephemeral = MutableStateFlow(ChatEphemeral())
    private val eventsChannel = Channel<ChatEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private val sessionFlow = chatRepository.observeSession(sessionId)

    /**
     * 区块分页状态（规范 §3）。
     *
     * 只记「已加载哪几块 + 两侧还有没有块 + 哪一侧在加载」，不记条数、不记 offset：
     * 一页就是一块，块编号是落库的，所以新消息进来不会让页边界滑动。
     */
    private val window = MutableStateFlow(MessageBlockPaging.State())

    /**
     * 已加载的那几块的消息（可观察）。
     *
     * 块内的编辑/删除、以及块里追加的新消息都会通过 Room 的失效通知回到这里；
     * 每次数据回来都重算一次两侧边界（`hasMoreOlder/Newer`）——不能只在首屏算一次，
     * 否则「下面还有更新的消息」在后台写入之后就不会再出现（旧实现踩过）。
     */
    private val messageFlow = window
        .map { it.blocks }
        .distinctUntilChanged()
        .flatMapLatest { blocks -> chatRepository.observeBlocks(sessionId, blocks) }
        .onEach { rows -> refreshBlockBoundaries(rows) }

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

    private val localFlow = combine(ephemeral, window) { local, blocks -> local to blocks }

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
            loadingNewer = paging.loadingNewer,
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
     * 定位是**区块级**的：目标消息的 `blockIndex` 是落库的，所以只要把窗口换成「目标所在的那一块」，
     * 再等它真的出现在列表里即可（块内最多 [com.yap.app.domain.MessageBlocks.BLOCK_SIZE] 条，
     * 一次读取量与会话长度无关）。
     *
     * 为什么不「按 id 只查一条插进列表」：聊天页的列表是一段连续的块，孤立插一条会让上下文断开，
     * 往上滑还会跳；所以只能真的把窗口翻到那一块。
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
        // 服务端模式：先把最新一块拉进 Room。聊天页的窗口/块边界逻辑完全复用本地实现，
        // 这里只负责「首屏数据从服务端来」；失败安静（事件/下一次同步会补）。
        if (chatRepository.isServerMode()) {
            viewModelScope.launch {
                runCatching { chatRepository.openServerSession(sessionId) }
            }
        }
        // 开聊之前先把人设翻译好：异步、不阻塞首屏；失败静默（用原文）。
        // 同一进程内按 `personaId + lang + sourceHash` 去重，缓存命中时零请求。
        viewModelScope.launch {
            val personaId = runCatching { chatRepository.getSession(sessionId)?.personaId }.getOrNull()
                ?: return@launch
            translationCoordinator.prefetch(personaId)
        }
        // 首屏只加载**最新一块**（规范 §3）：最新块编号从块表观察，
        // 这样「会话从空变成有一块」「最新块被删空」都能重新定位。
        viewModelScope.launch {
            chatRepository.observeLastBlockIndex(sessionId).collect { last ->
                applyLatestBlock(last)
            }
        }
        startJumpToTarget()
    }

    /**
     * 滚到检索命中的那条消息：把窗口换成「目标那一块」并等它出现。
     *
     * 三种结局都安静收场：找到了 → 发事件；目标在首屏（最新块里）→ 一样发事件；
     * 目标不在了（删过 / 换过设备）→ 停在最新一屏，不报错 —— 用户自己会明白那条话没了。
     */
    private fun startJumpToTarget() {
        val target = jumpTargetId ?: return
        if (target.isBlank()) return
        viewModelScope.launch {
            val located = runCatching {
                chatRepository.blockContainingMessage(sessionId, target)
            }.getOrNull()
            if (located != null && window.value.newestBlock != located.blockIndex) {
                window.value = MessageBlockPaging.onJump(
                    targetBlock = located.blockIndex,
                    hasMoreOlder = located.hasMoreOlder,
                    hasMoreNewer = located.hasMoreNewer
                )
            }

            // 5 秒上限是为了它已经被删掉时不要把协程永远挂着（此时停在新的一屏即可）。
            val settled = withTimeoutOrNull(JUMP_TIMEOUT_MILLIS) {
                uiState.first { state -> state.messages.any { it.id == target } }
            }
            if (settled != null) eventsChannel.send(ChatEvent.FocusMessage(target))
        }
    }

    /**
     * 「回到最新」：只保留最新一块（首屏语义）。
     *
     * 0.12 起它不再是「往下看更新消息」的唯一入口——底部那一行会一块一块往下加载，
     * 这里留给「从很久以前跳回来」和「发新消息之前」用。
     */
    fun showLatest() {
        viewModelScope.launch {
            if (chatRepository.isServerMode()) {
                runCatching { chatRepository.loadServerPage(sessionId) }
            }
            val last = runCatching { chatRepository.lastBlockIndex(sessionId) }.getOrNull()
            val first = runCatching { chatRepository.firstBlockIndex(sessionId) }.getOrNull()
            window.value = MessageBlockPaging.onLatest(window.value, last, first)
        }
    }

    /**
     * 用户主动写消息（发送 / 重新生成 / 编辑）之前，若停在更早的块里，先回到最新块。
     *
     * 留在旧块里的话：新写的消息落在最新块，列表里根本看不到——用户感知是
     * 「发出去的话消失了、他也不理我」；随后助手回复同样落在窗口之外。
     */
    private suspend fun leaveJumpWindowForNewMessage() {
        val last = runCatching { chatRepository.lastBlockIndex(sessionId) }.getOrNull()
        if (!MessageBlockPaging.isAtNewestBlock(window.value, last)) {
            val first = runCatching { chatRepository.firstBlockIndex(sessionId) }.getOrNull()
            window.value = MessageBlockPaging.onLatest(window.value, last, first)
        }
    }

    /**
     * 块表数据变化后重算两侧边界（规范 §3 的「还有没有上一块 / 下一块」）。
     *
     * @param rows 本次观察到的消息（没用上，但保留参数让调用点读起来就是「数据回来了」）
     */
    private suspend fun refreshBlockBoundaries(@Suppress("UNUSED_PARAMETER") rows: List<MessageEntity>) {
        val state = window.value
        if (!state.initialized) return
        val oldest = state.oldestBlock ?: return
        val newest = state.newestBlock ?: return
        val hasOlder = runCatching { chatRepository.blocksAround(sessionId, oldest).older != null }
            .getOrDefault(false)
        val hasNewer = runCatching { chatRepository.blocksAround(sessionId, newest).newer != null }
            .getOrDefault(false)
        window.update { MessageBlockPaging.onBoundaries(it, hasOlder, hasNewer) }
    }

    /**
     * 最新块编号变了（首屏、会话清空、最新块被删空）：重新定位窗口。
     *
     * - 还没定位过 → 首屏；
     * - 窗口里一块都没有（会话本来是空的，现在有消息了；或者被清空了）→ 重新首屏；
     * - 否则只重算边界，「下面还有更新的消息」会自己亮起来。
     */
    private suspend fun applyLatestBlock(last: Int?) {
        val current = window.value
        when {
            last == null -> window.value = MessageBlockPaging.onInitial(null, null)
            !current.initialized || current.blocks.isEmpty() -> {
                val first = runCatching { chatRepository.firstBlockIndex(sessionId) }.getOrNull()
                window.value = MessageBlockPaging.onInitial(last, first)
            }
            else -> refreshBlockBoundaries(emptyList())
        }
    }

    private suspend fun initialPrepare() {
        if (chatRepository.isServerMode()) return
        val session = chatRepository.getSession(sessionId) ?: return
        if (session.apiConfigId == null && apiConfigRepository.getAll().isNotEmpty()) {
            ephemeral.update { it.copy(showConfigPicker = true) }
        }
    }

    /**
     * 滚到顶部：加载 `blockIndex - 1` 那一块（规范 §3 Older）。
     *
     * 「同一时刻只加载一块」由 [MessageBlockPaging.beginLoadOlder] 保证：快速滚顶 / 反复触发时
     * 第二次直接返回，不会并发打两条查询、也不会把同一块插两遍。
     */
    fun loadOlder() {
        val (next, started) = MessageBlockPaging.beginLoadOlder(window.value)
        if (!started) return
        window.value = next
        viewModelScope.launch {
            try {
                val oldest = window.value.oldestBlock
                // 服务端模式：先让服务端把「上一块」写进 Room，再走同一套块边界查询。
                if (chatRepository.isServerMode() && oldest != null) {
                    chatRepository.loadServerPage(sessionId, beforeBlock = oldest)
                }
                // 「上一块」不是 oldest - 1：块删空之后编号会留缺口（规范 §1），要查。
                val previous = oldest?.let { chatRepository.blocksAround(sessionId, it).older }
                window.update { MessageBlockPaging.onOlderLoaded(it, previous) }
            } catch (cancel: CancellationException) {
                window.update { MessageBlockPaging.onLoadFailed(it, older = true) }
                throw cancel
            } catch (error: Exception) {
                window.update { MessageBlockPaging.onLoadFailed(it, older = true) }
            }
        }
    }

    /**
     * 滚到底（且不在最新块）：加载 `blockIndex + 1` 那一块（规范 §3 Newer）。
     *
     * 这是 0.12 新增的方向：以前只能「跳回最新」，中间那段要么看不到、要么得整段读进内存。
     * 现在一块一块往下走，读取量与「目标 → 最新」之间隔着多少历史无关。
     */
    fun loadNewer() {
        val (next, started) = MessageBlockPaging.beginLoadNewer(window.value)
        if (!started) return
        window.value = next
        viewModelScope.launch {
            try {
                val newest = window.value.newestBlock
                if (chatRepository.isServerMode() && newest != null) {
                    chatRepository.loadServerPage(sessionId, afterBlock = newest)
                }
                val following = newest?.let { chatRepository.blocksAround(sessionId, it).newer }
                window.update { MessageBlockPaging.onNewerLoaded(it, following) }
            } catch (cancel: CancellationException) {
                window.update { MessageBlockPaging.onLoadFailed(it, older = false) }
                throw cancel
            } catch (error: Exception) {
                window.update { MessageBlockPaging.onLoadFailed(it, older = false) }
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
                    // ViewModel 已经销毁（onCleared 跑过）：文件不该留在磁盘上——
                    // 但仍要先查引用：这张图可能已经发给过某条消息（内容寻址同名文件）。
                    mediaJanitor.deleteAttachmentsIfUnreferenced(persisted)
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
        // 0.12：草稿图片也要过引用守卫——同一张图可能已经发出去过（内容寻址 → 同一个文件）。
        viewModelScope.launch { mediaJanitor.deleteAttachmentsIfUnreferenced(removed) }
    }

    fun clearImages() {
        val removed = ephemeral.value.images
        ephemeral.update { it.copy(images = emptyList()) }
        viewModelScope.launch { mediaJanitor.deleteAttachmentsIfUnreferenced(removed) }
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
        // 服务端模式不需要本地 API 配置：模型与密钥都在服务端。
        if (!chatRepository.isServerMode() && current.config == null) {
            ephemeral.update { it.copy(showConfigPicker = true, snackbar = context.getString(R.string.chat_select_config_first)) }
            return
        }
        if (chatRepository.isServerMode()) {
            sendViaServer(text, images)
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
     * 服务端模式发送：SSE 事件 → 界面增量；消息落库由 ChatRepository/ServerChatGateway 负责。
     *
     * 失败时**草稿留在输入框**（[ephemeral] 的 draft/images 直到 accepted 才清空）：
     * 发送失败最常见的原因是没网，用户刚打的一段话不该被清掉；只有服务端确认 accepted
     * 之后才清空，因为那时服务端已经落库、重发会产生重复（幂等键只对同一 id 有效）。
     */
    private fun sendViaServer(text: String, images: List<MessageAttachment>) {
        viewModelScope.launch {
            if (operationInFlight) return@launch
            operationInFlight = true
            try {
                leaveJumpWindowForNewMessage()
                ephemeral.update {
                    it.copy(
                        isGenerating = true,
                        hasStarted = false,
                        streamingText = "",
                        streamingTraces = emptyList(),
                        snackbar = null
                    )
                }
                val result = chatRepository.sendServerMessage(sessionId, text, images) { event ->
                    when (event) {
                        is com.yap.app.data.remote.ServerSendEvent.Accepted ->
                            ephemeral.update { it.copy(hasStarted = true) }

                        is com.yap.app.data.remote.ServerSendEvent.Delta ->
                            ephemeral.update { it.copy(streamingText = it.streamingText + event.text) }

                        is com.yap.app.data.remote.ServerSendEvent.Tool ->
                            ephemeral.update {
                                it.copy(
                                    streamingTraces = it.streamingTraces + ToolTraceItem(
                                        name = event.name,
                                        title = event.name,
                                        subtitle = event.summary,
                                        detail = event.summary.orEmpty(),
                                        ok = event.status != "error"
                                    )
                                )
                            }

                        is com.yap.app.data.remote.ServerSendEvent.Done -> Unit
                        is com.yap.app.data.remote.ServerSendEvent.Error -> Unit
                    }
                }
                if (result.accepted) {
                    ephemeral.update { it.copy(draft = "", images = emptyList()) }
                    // M5：图片已经上传到服务端，本机这份压缩后的临时图不再被任何消息引用。
                    // 仍然走引用守卫（内容寻址下同一张图可能被别的本地消息用着）。
                    if (images.isNotEmpty()) {
                        runCatching { mediaJanitor.deleteAttachmentsIfUnreferenced(images) }
                    }
                }
                val error = result.error
                ephemeral.update {
                    it.copy(
                        isGenerating = false,
                        hasStarted = false,
                        streamingText = "",
                        streamingTraces = emptyList(),
                        // accepted 之前的失败才是「没发出去」；accepted 之后流断了只是回复没接上，
                        // 不能显示「发送失败」去误导用户重发（重发会产生第二条，虽然服务端幂等）。
                        snackbar = when {
                            error != null && !result.accepted ->
                                ErrorText.of(context, error, R.string.chat_send_failed)

                            !result.done -> context.getString(R.string.chat_server_reply_pending)
                            else -> null
                        }
                    )
                }
            } catch (cancel: CancellationException) {
                // 用户点了停止：SSE 连接关闭，服务端继续生成并落库；本机下一次同步/事件会拿到。
                ephemeral.update { it.copy(isGenerating = false, hasStarted = false, streamingText = "") }
                throw cancel
            } catch (error: Exception) {
                ephemeral.update {
                    it.copy(
                        isGenerating = false,
                        hasStarted = false,
                        streamingText = "",
                        streamingTraces = emptyList(),
                        snackbar = ErrorText.of(context, error, R.string.chat_send_failed)
                    )
                }
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
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_server_action_unsupported)) }
            return
        }
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
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_server_action_unsupported)) }
            return
        }
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
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_server_action_unsupported)) }
            return
        }
        viewModelScope.launch {
            chatRepository.deleteMessage(messageId)
                .onFailure { error -> ephemeral.update { it.copy(snackbar = ErrorText.of(context, error, R.string.chat_delete_failed)) } }
        }
    }

    fun clearContext() {
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_server_action_unsupported)) }
            return
        }
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
                mediaJanitor.deleteAttachmentsIfUnreferenced(abandoned)
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
        // 服务端契约 §3.6 没有「删除单条会话」的接口（删人格会连带删会话）。
        // 本地只删缓存会让服务端留下一段再也同步不回来的会话，所以这里明确拒绝，
        // 而不是让用户以为删掉了、下次同步又冒出来。
        if (chatRepository.isServerMode()) {
            ephemeral.update { it.copy(snackbar = context.getString(R.string.chat_server_action_unsupported)) }
            return
        }
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
    private fun memoryResultText(result: com.yap.app.data.repo.MemoryExtractionResult): String =
        when (result.outcome) {
            com.yap.app.data.repo.MemoryExtractionOutcome.ADDED ->
                context.getString(R.string.memory_extract_result_added, result.added)
            com.yap.app.data.repo.MemoryExtractionOutcome.REINFORCED ->
                context.getString(R.string.memory_extract_result_reinforced)
            com.yap.app.data.repo.MemoryExtractionOutcome.ECHOED ->
                context.getString(R.string.memory_extract_result_echoed)
            com.yap.app.data.repo.MemoryExtractionOutcome.NONE ->
                context.getString(R.string.memory_extract_result_none)
            com.yap.app.data.repo.MemoryExtractionOutcome.NOT_ENOUGH_CHAT ->
                context.getString(R.string.memory_extract_result_not_enough)
            com.yap.app.data.repo.MemoryExtractionOutcome.INTERVAL_NOT_REACHED ->
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
        // 0.12：走进程级作用域（viewModelScope 已被取消），引用守卫照样生效。
        mediaJanitor.deleteAttachmentsLater(ephemeral.value.images)
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
