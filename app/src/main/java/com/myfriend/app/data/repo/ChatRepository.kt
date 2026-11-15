package com.myfriend.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.myfriend.app.R
import com.myfriend.app.data.local.AgentTickDao
import com.myfriend.app.data.local.AgentTickEntity
import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MessageBlockDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.MessageSearchRow
import com.myfriend.app.data.local.MessageSearchSql
import com.myfriend.app.data.local.MessageStatus
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.SearchBlockRow
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.core.AgentCoreClient
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.domain.NotificationDeepLink
import com.myfriend.app.domain.AttachmentsCodec
import com.myfriend.app.domain.ChatNotificationPolicy
import com.myfriend.app.domain.DataEraser
import com.myfriend.app.domain.ExportJsonStream
import com.myfriend.app.domain.SessionGenerationRegistry
import com.myfriend.app.domain.ChatExport
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.domain.GenerationEvent
import com.myfriend.app.domain.HistoryCompression
import com.myfriend.app.domain.LanguageDirective
import com.myfriend.app.domain.LlmLane
import com.myfriend.app.domain.ImageContextBudget
import com.myfriend.app.domain.InterruptedReply
import com.myfriend.app.domain.MessageOrder
import com.myfriend.app.domain.MoodRecovery
import com.myfriend.app.domain.MessageAttachment
import com.myfriend.app.domain.MoodBehavior
import com.myfriend.app.domain.MoodPrompt
import com.myfriend.app.domain.OpenSessionTracker
import com.myfriend.app.domain.PersonaActivityCodec
import com.myfriend.app.domain.PersonaProfile
import com.myfriend.app.domain.PromptBuilder
import com.myfriend.app.domain.PersonaSwitchNotice
import com.myfriend.app.domain.PersonaSwitchResult
import com.myfriend.app.domain.DomainErrorCode
import com.myfriend.app.domain.NetworkErrorKind
import com.myfriend.app.domain.BlockSearch
import com.myfriend.app.domain.RankInput
import com.myfriend.app.domain.SearchFilter
import com.myfriend.app.domain.SearchRanking
import com.myfriend.app.domain.SearchSnippet
import com.myfriend.app.domain.UserError
import com.myfriend.app.domain.UserErrorTokens
import com.myfriend.app.domain.SleepDecision
import com.myfriend.app.domain.SleepInput
import com.myfriend.app.domain.SleepPolicy
import com.myfriend.app.domain.SpeechStyleCodec
import com.myfriend.app.domain.ToolCatalog
import com.myfriend.app.domain.ToolProtocol
import com.myfriend.app.domain.ToolResult
import com.myfriend.app.domain.ToolTraceCodec
import com.myfriend.app.domain.ToolTraceItem
import com.myfriend.app.domain.WorldPresetCodec
import com.myfriend.app.network.ApiException
import com.myfriend.app.network.toUserError
import com.myfriend.app.network.userError
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.StreamEvent
import com.myfriend.app.network.WireMessage
import com.myfriend.app.network.inferenceParams
import com.myfriend.app.util.ChatNotifier
import com.myfriend.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「会话/人格」这一类命中：标题、人格名或最后一条预览里有关键词。
 *
 * 检索页把它和「消息」分两个页签展示：用户找「那段对话」和找「那句话」是两种意图，
 * 混在一列里就会互相挤占视线。
 */
@Serializable
data class SessionSearchHit(
    val sessionId: String,
    val personaId: String = "",
    val title: String,
    val personaName: String,
    val personaAvatarUri: String? = null,
    val personaAvatarSeed: Int = 0,
    val preview: String? = null,
    val lastMessageAt: Long? = null
)

/** 相邻块：编号可能不连续（块删空后留下空洞，规范 §1）。 */
data class BlocksAround(val older: Int?, val newer: Int?)

/** 从检索结果定位时要加载的块（`hasMoreOlder/Newer` 决定两端要不要给加载入口）。 */
data class BlockWindow(
    val blockIndex: Int,
    val hasMoreOlder: Boolean,
    val hasMoreNewer: Boolean
)

/**
 * 一次检索请求：关键词 + 过滤 + **键集游标**。
 *
 * 过滤在 SQL 与内存里各做一次（[com.myfriend.app.domain.SearchFilter]），
 * 这样即使将来 SQL 变了，也不会因为「过滤漏在某一层」而把别人的消息露出来。
 *
 * 0.12 起没有 `offset`：翻页用上一页最后一条的
 * [com.myfriend.app.domain.BlockSearch.Cursor]，所以翻页期间新增消息不会让结果重复或乱序，
 * 也不需要「候选上限」。
 */
data class SearchQuery(
    val text: String,
    val personaId: String? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int = SEARCH_PAGE_SIZE,
    val cursor: BlockSearch.Cursor? = null
)

/** 检索页的一页消息结果（分页单位：[SEARCH_PAGE_SIZE] 条，键集游标翻页）。 */
data class SearchPage(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    /** 全部扫完之后的命中总数——**不受页大小、也没有 500 条上限**。 */
    val total: Int = 0,
    val hasMore: Boolean = false,
    /** 下一页的游标；没有下一页时为 null。 */
    val nextCursor: BlockSearch.Cursor? = null
)

/**
 * 一条消息命中。
 *
 * [snippet] / [highlights] 由 [SearchSnippet] 按 `spec/search/README.md` 生成，
 * 界面只要把高亮区间换成 SpanStyle，不需要自己再截一遍（以前界面自己截，
 * 高亮偏移和截断逻辑各写一份，中文多字节时经常错位）。
 */
data class SearchHit(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val personaId: String,
    val personaName: String,
    val personaAvatarUri: String? = null,
    val personaAvatarSeed: Int = 0,
    val snippet: String,
    val highlights: List<IntRange>,
    val createdAt: Long,
    val role: String
)

/** 检索页每页条数：30 条大约 2-3 屏，滚到底再要下一页。 */
const val SEARCH_PAGE_SIZE = 30

@Serializable
private data class ExportMessage(
    val role: String,
    val content: String,
    val createdAt: Long,
    val status: String
)

@Serializable
private data class ExportSession(
    val sessionId: String,
    val title: String,
    val personaName: String,
    val personaCategory: String,
    val personaVersion: Int,
    val apiConfigName: String? = null,
    val model: String,
    val createdAt: Long,
    val messages: List<ExportMessage>
)

@Serializable
private data class ExportBundle(
    val format: String = "myfriend.chats",
    val schemaVersion: Int = 1,
    val exportedAt: Long,
    val sessions: List<ExportSession>
)

@Singleton
class ChatRepository @Inject constructor(
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val blockDao: MessageBlockDao,
    /** 块元数据的唯一写入入口（规范 §2）。 */
    private val blockWriter: MessageBlockWriter,
    private val personaDao: PersonaDao,
    private val apiConfigDao: ApiConfigDao,
    private val database: MyFriendDatabase,
    private val apiConfigRepository: ApiConfigRepository,
    private val memoryRepository: MemoryRepository,
    private val registry: AiProviderRegistry,
    private val settingsRepository: SettingsRepository,
    private val openSessionTracker: OpenSessionTracker,
    private val toolExecutor: ToolExecutor,
    private val personaCardRepository: PersonaCardRepository,
    private val moodRepository: MoodRepository,
    private val presenceRepository: PresenceRepository,
    private val historyCompressor: HistoryCompressor,
    private val sessionGeneration: SessionGenerationRegistry,
    private val dataEraser: DataEraser,
    /** 删图片文件之前的引用守卫（0.12）：共享文件还有别的消息/头像引用时不删。 */
    private val mediaJanitor: MediaJanitor,
    private val agentTickDao: AgentTickDao,
    /** agent 决策（睡眠判定）：默认进程内，可切 agentd；失败会自动回退，见 [AgentCoreClient]。 */
    private val agentCore: AgentCoreClient,
    @ApplicationContext private val appContext: Context,
    private val chatNotifier: ChatNotifier,
    private val foregroundTracker: com.myfriend.app.util.AppForegroundTracker,
    private val json: Json
) {
    /**
     * 正在跑的生成任务（按 sessionId）。
     *
     * 删除 / 清空上下文时按会话取消它：`NonCancellable` 的收尾写入虽然不响应取消，
     * 但配合世代号与存在性校验（[writeStillValid]）就不会写进已删会话。
     * 这里只是「尽快停下来」，正确性由世代号保证。
     */
    private val activeGenerations = ConcurrentHashMap<String, Job>()

    fun observeSessions(): Flow<List<ChatSessionEntity>> = sessionDao.observeAll()
    fun observeSession(id: String): Flow<ChatSessionEntity?> = sessionDao.observeById(id)

    // ---------------------------------------------------------------- 区块分页（v11）
    //
    // 规范 `spec/messages-blocks/README.md` §3：一页 = 一块；首屏只读最新块，滚到顶读
    // `blockIndex - 1`，滚到底（且不在最新块）读 `blockIndex + 1`。永远按
    // `(sessionId, blockIndex)` 定位，不用 offset，所以「一页读多少行」与会话长度无关。

    /** 「读某块」：返回这一块的消息，按 `(createdAt, id)` 升序。 */
    suspend fun blockOf(sessionId: String, blockIndex: Int): List<MessageEntity> =
        messageDao.blockOf(sessionId, blockIndex)

    /**
     * 聊天页观察「已加载的那几块」。
     *
     * 用可观察查询而不是快照：块内编辑/删除、以及块里追加的新消息都要反映到列表里。
     * 空列表直接给空流（Room 对 `IN ()` 的行为不保证）。
     */
    fun observeBlocks(sessionId: String, blockIndices: List<Int>): Flow<List<MessageEntity>> {
        val distinct = blockIndices.distinct().sorted()
        if (distinct.isEmpty()) return flowOf(emptyList())
        return messageDao.observeBlocks(sessionId, distinct)
    }

    /** 会话里编号最大的块（空会话为 null）。 */
    suspend fun lastBlockIndex(sessionId: String): Int? = blockDao.lastBlockIndex(sessionId)

    /** 会话里编号最小的块（空会话为 null）。块编号有空洞时它不等于 0。 */
    suspend fun firstBlockIndex(sessionId: String): Int? = blockDao.firstBlockIndex(sessionId)

    /**
     * 可观察的「最新块」：聊天页靠它在「会话从空变成有一块」「最新块被删空」时重新定位。
     */
    fun observeLastBlockIndex(sessionId: String): Flow<Int?> =
        blockDao.observeLastBlock(sessionId).map { it.firstOrNull() }

    /** 相邻块（规范 §3 的「上一块 / 下一块」；编号有空洞时不是 ±1）。 */
    suspend fun blocksAround(sessionId: String, blockIndex: Int): BlocksAround = BlocksAround(
        older = blockDao.blockBefore(sessionId, blockIndex),
        newer = blockDao.blockAfter(sessionId, blockIndex)
    )

    /**
     * 从检索结果跳到某条消息：定位到它所在的那一块（不再按「条数窗口」算）。
     *
     * 目标消息的 `blockIndex` 是落库的，所以一次查询（`getById`）+ 两次相邻块查询就够了；
     * 块内最多 BLOCK_SIZE 条，定位的读取量与会话长度无关。
     */
    suspend fun blockContainingMessage(sessionId: String, messageId: String): BlockWindow? {
        val target = messageDao.getById(messageId) ?: return null
        if (target.sessionId != sessionId) return null
        val around = blocksAround(sessionId, target.blockIndex)
        return BlockWindow(
            blockIndex = target.blockIndex,
            hasMoreOlder = around.older != null,
            hasMoreNewer = around.newer != null
        )
    }

    suspend fun getSession(id: String): ChatSessionEntity? = sessionDao.getById(id)
    suspend fun getPersona(id: String): PersonaEntity? = personaDao.getById(id)
    suspend fun getConfig(id: String?): com.myfriend.app.data.local.ApiConfigEntity? =
        id?.let { apiConfigDao.getById(it) }

    /**
     * 取这个人格的那一段会话。
     *
     * 1.9 一人一段：已经有就返回已有，没有才建。调用方不再需要区分「新建」与「继续」，
     * 界面上的「新建对话」也就没有存在意义了。
     */
    suspend fun createSession(
        personaId: String,
        apiConfigId: String? = null,
        title: String? = null
    ): Result<ChatSessionEntity> = runCatching {
        sessionDao.latestForPersona(personaId)?.let { return@runCatching it }
        val persona = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        val configId = apiConfigId ?: persona.defaultApiConfigId
        val config = configId?.let { apiConfigDao.getById(it) }
        val model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: config?.model.orEmpty()
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            personaId = persona.id,
            apiConfigId = config?.id,
            model = model,
            // 会话标题会落库、也会进搜索与模型上下文：写入时按当前界面语言取，老库里的中文原样显示。
            title = title?.trim()?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.chat_session_default_title, persona.name),
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            pinned = false,
            createdAt = now,
            updatedAt = now,
            lastPersonaVersion = persona.version,
            lastMemoryExtractedCount = 0
        )
        // 幂等 upsert：`index_chat_sessions_personaId_unique` 下，两个调用方（首页点联系人 /
        // 启动补会话 / 主动消息）同时发现「还没有会话」时，只有一方插得进去。
        // 以前走 ABORT 会抛 SQLiteConstraintException，首页直接报「打开失败」（审计 P2-6）；
        // 走 REPLACE 更糟：会把对方刚建的那一行整行删掉再插（消息成孤儿）。
        // IGNORE + 回读：撞了就说明别人已经建好了，直接返回那一行。
        val inserted = sessionDao.insertIgnore(session)
        if (inserted != -1L) return@runCatching session
        sessionDao.latestForPersona(personaId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
    }

    suspend fun getOrCreateForPersona(personaId: String): ChatSessionEntity =
        sessionDao.latestForPersona(personaId) ?: createSession(personaId).getOrThrow()

    suspend fun switchToPersona(personaId: String): PersonaSwitchResult {
        val persona = personaDao.getById(personaId)
            ?: return PersonaSwitchResult(false, null, PersonaSwitchNotice.PERSONA_NOT_FOUND)
        val session = runCatching { getOrCreateForPersona(personaId) }.getOrElse {
            return PersonaSwitchResult(false, null, PersonaSwitchNotice.SESSION_CREATE_FAILED)
        }
        settingsRepository.setActivePersonaId(personaId)
        val config = getConfig(session.apiConfigId)
        return if (config == null) {
            PersonaSwitchResult(true, session, PersonaSwitchNotice.API_NOT_BOUND)
        } else {
            PersonaSwitchResult(true, session)
        }
    }

    suspend fun sendUserMessage(
        sessionId: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    ): Result<Unit> = runCatching {
        val content = text.trim()
        val images = attachments.filter { it.isImage }.take(MAX_IMAGES_PER_MESSAGE)
        if (content.isBlank() && images.isEmpty()) userError(DomainErrorCode.MESSAGE_EMPTY)
        if (content.length > 32_000) userError(DomainErrorCode.MESSAGE_TOO_LONG)
        val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
        if (session.apiConfigId == null) userError(DomainErrorCode.API_CONFIG_REQUIRED)
        apiConfigDao.getById(session.apiConfigId) ?: userError(DomainErrorCode.API_CONFIG_GONE)
        val now = nextTimestamp(sessionId)
        val isFirstUserMessage = messageDao.countUserMessages(sessionId) == 0
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            personaId = session.personaId,
            role = MessageRole.USER,
            content = content,
            createdAt = now,
            updatedAt = now,
            status = MessageStatus.COMPLETE,
            errorMessage = null,
            model = null,
            edited = false,
            attachmentsJson = AttachmentsCodec.encode(images)
        )
        database.withTransaction {
            // 写入统一走块维护（规范 §2 Append）：落进最后一块，满了才开新块。
            blockWriter.append(message)
            // 消息预览同样是落库数据：图片这类「没有文字的消息」用资源生成，不写死中文。
            val preview = content.replace('\n', ' ').trim().take(90)
                .ifBlank { if (images.isNotEmpty()) appContext.getString(R.string.chat_preview_image) else "" }
            val title = if (isFirstUserMessage) {
                autoTitle(content.ifBlank { appContext.getString(R.string.chat_preview_image) })
            } else {
                session.title
            }
            sessionDao.update(
                session.copy(
                    title = title,
                    lastMessagePreview = preview,
                    lastMessageAt = now,
                    unreadCount = 0,
                    updatedAt = now
                )
            )
        }
    }

    fun generateAssistant(sessionId: String): Flow<GenerationEvent> = flow {
        // 这一轮属于哪个世代：期间若有清空 / 删除 / 整库替换，收尾写入就必须丢弃。
        val generation = sessionGeneration.current(sessionId)
        val session = sessionDao.getById(sessionId)
            ?: run {
                emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.SESSION_NOT_FOUND), null))
                return@flow
            }
        val persona = personaDao.getById(session.personaId)
            ?: run {
                emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.PERSONA_NOT_FOUND), null))
                return@flow
            }
        val configId = session.apiConfigId
            ?: run {
                emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.API_CONFIG_REQUIRED), null))
                return@flow
            }
        // 安全 P2-6：配置在、但 Key 解不出来（Keystore 被系统清掉 / 换机迁移异常）时说清楚，
        // 不再让请求带着空 Authorization 发出去、最后变成一句「API Key 无效（401）」。
        val resolved = try {
            apiConfigRepository.resolve(configId)
        } catch (error: Exception) {
            emit(GenerationEvent.Failed(error.toUserError(), null))
            return@flow
        } ?: run {
            emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.API_CONFIG_GONE), null))
            return@flow
        }
        val config = resolved.config
        // 只读最近 N 条：长会话不再每次把整段历史读进内存（更早的部分已经收成摘要了）。
        val recentHistory = messageDao.recentBySession(sessionId, CONTEXT_RECENT_LIMIT)
        if (recentHistory.none { it.role == MessageRole.USER }) {
            emit(GenerationEvent.Failed(UserError.Domain(DomainErrorCode.NOTHING_TO_REPLY), null))
            return@flow
        }
        val now0 = System.currentTimeMillis()
        // 睡眠 / 免打扰 / 唤醒阈值（规格 §六）。判定是纯函数，这里只负责拿数据、落痕迹。
        val sleepDecision = evaluateSleep(persona, sessionId, now0)
        // asleep && !woken 的等价写法（决策数据类只暴露 wake / moodRelaxed）：
        if (!sleepDecision.wake && !sleepDecision.moodRelaxed) {
            // 他确实看到了（已读），但这会儿不会回。界面只显示「已读」，不显示任何解释。
            // 免打扰是例外：连「已读」都不留 —— 那是「他不想被打扰」的一部分（规格 §6.2）。
            if (sleepDecision.marksRead) {
                runCatching { messageDao.markUserMessagesRead(sessionId, now0) }
            }
            emit(GenerationEvent.Silent)
            return@flow
        }
        if (sleepDecision.moodRelaxed) {
            // 被连着叫醒：心情先缓一缓，并在他自己的思考留痕里写一句，用户看不见细节。
            runCatching {
                val mood = moodRepository.current(persona.id, now0)
                val recovered = MoodRecovery.afterWake(mood, now0)
                personaDao.setMood(
                    id = persona.id,
                    mood = recovered.label,
                    intensity = recovered.intensity,
                    reason = recovered.reason,
                    now = now0
                )
                agentTickDao.insert(
                    AgentTickEntity(
                        id = UUID.randomUUID().toString(),
                        personaId = persona.id,
                        at = now0,
                        thought = "被对方连着几条消息叫醒了",
                        actionJson = """{"actions":[]}""",
                        outcome = "被你叫醒了，缓了缓"
                    )
                )
            }
        }
        // 模型看到这些消息的那一刻就是「已读」；在读历史之前写，保证这一轮一定带上这次看到的消息。
        runCatching { messageDao.markUserMessagesRead(sessionId, now0) }
        val wireMessages = buildContext(session, persona, config, recentHistory)
        val adapter = registry.adapterFor(config.provider)
        emit(GenerationEvent.Started)
        val traces = mutableListOf<ToolTraceItem>()
        var completed = false
        var shownText = ""
        try {
            val conversation = wireMessages.toMutableList()
            val executed = mutableSetOf<String>()
            var finalBody = ""
            var round = 0
            var deferred = false
            while (true) {
                val buffer = StringBuilder()
                adapter.streamChat(
                    config = config,
                    apiKey = resolved.apiKey,
                    model = session.model.ifBlank { config.model },
                    messages = conversation,
                    params = config.inferenceParams(),
                    kind = com.myfriend.app.domain.UsageKind.CHAT,
                    lane = LlmLane.CHAT,
                    sessionId = sessionId,
                    personaId = persona.id
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Text -> {
                            buffer.append(event.value)
                            // 只把「还没写到的工具块」之前的部分推到界面，协议块永不示人。
                            val visible = ToolProtocol.visiblePrefix(buffer.toString())
                            if (visible != shownText) {
                                shownText = visible
                                emit(GenerationEvent.Delta(visible))
                            }
                        }
                        StreamEvent.Done -> Unit
                    }
                }
                val (body, calls) = ToolProtocol.extract(buffer.toString())
                if (calls.isEmpty() || round >= MAX_TOOL_ROUNDS) {
                    finalBody = body.ifBlank { if (calls.isEmpty()) buffer.toString().trim() else "" }
                    break
                }
                round++
                val results = calls.map { call ->
                    val fingerprint = call.name.trim() + "|" + call.argumentsJson.trim()
                    if (!executed.add(fingerprint)) {
                        // 同一个调用这一轮已经做过：别再执行一次，只告诉它「刚做过」，
                        // 否则模型会陷进「查了又查」的循环。
                        ToolResult(
                            invocation = call,
                            ok = true,
                            summary = "刚刚已经做过",
                            contentForModel = "这个调用你刚刚已经做过了，结果就在上面。直接用那些结果把话说完。"
                        )
                    } else {
                        val execution = toolExecutor.execute(
                            invocation = call,
                            persona = persona,
                            configId = config.id,
                            sessionId = sessionId
                        )
                        traces += execution.trace
                        if (call.name.trim().equals(ToolCatalog.DEFER_REPLY, ignoreCase = true)) deferred = true
                        execution.result
                    }
                }
                if (deferred) {
                    // 模型已经明确选择不回：没必要再问一轮，也不该让它再说一句话。
                    finalBody = ""
                    break
                }
                // 过程话不算数：先把气泡里的临时文字清干净，等最后那一轮的真话。
                shownText = ""
                emit(GenerationEvent.Delta(""))
                emit(GenerationEvent.ToolCall(traces.toList()))
                conversation += WireMessage("assistant", buffer.toString().trim())
                conversation += WireMessage(
                    "user",
                    ToolProtocol.renderResultsForModel(results) +
                        "\n\n（以上是你自己刚做完的事，对方看不见细节。现在接着把话说完，别重复，也别提工具。）"
                )
            }
            if (deferred) {
                // defer_reply：这一轮他明确选择不回。工具已经写了念头、标了已读，
                // 这里不能再落一条空助手消息——「没有消息」本身就是他的回答。
                runCatching { messageDao.markUserMessagesRead(sessionId, now0) }
                completed = true
                emit(GenerationEvent.Silent)
                return@flow
            }
            val rawFinalText = finalBody.trim()
            val (finalText, proposal) = ProactiveProtocol.extract(rawFinalText)
            if (finalText.isBlank() && proposal == null) {
                throw ApiException(NetworkErrorKind.EmptyStream)
            }
            val saved = withContext(NonCancellable) {
                // 用户可能在流式过程中清空上下文 / 删掉会话：这条回复不能再写回去
                // （0.10.2 之前 NonCancellable 会把半截回复写进已删会话，留下永远看不见的孤儿行）。
                if (!writeStillValid(sessionId, generation)) return@withContext null
                val now = nextTimestamp(sessionId)
                val assistant = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    personaId = persona.id,
                    role = MessageRole.ASSISTANT,
                    content = finalText,
                    createdAt = now,
                    updatedAt = now,
                    status = MessageStatus.COMPLETE,
                    errorMessage = null,
                    model = session.model.ifBlank { config.model },
                    edited = false,
                    toolTraceJson = ToolTraceCodec.encode(traces)
                )
                database.withTransaction {
                    // 同上：助手回复也走统一追加规则，块边界不会因为「另一条路径」而错位。
                    blockWriter.append(assistant)
                    val preview = finalText.replace('\n', ' ').trim().take(100)
                        .ifBlank { appContext.getString(R.string.chat_preview_proactive) }
                    val fresh = sessionDao.getById(sessionId)
                    if (fresh != null) {
                        sessionDao.update(
                            fresh.copy(
                                lastMessagePreview = preview,
                                lastMessageAt = now,
                                updatedAt = now
                            )
                        )
                    }
                    if (ChatNotificationPolicy.shouldMarkUnread(openSessionTracker.openSessionId, sessionId)) {
                        sessionDao.incrementUnread(sessionId)
                    } else {
                        sessionDao.markRead(sessionId)
                    }
                }
                assistant
            }
            if (saved == null) {
                // 会话没了 / 世代过期：这次回复作废，界面收成「已停止」而不是假装成功。
                completed = true
                emit(GenerationEvent.Stopped)
                return@flow
            }
            completed = true
            // 普通回复落库后：只要「这个会话没开着」或者「App 根本不在前台」，都补一条系统通知。
            // 后者是必须的——按 Home 退到桌面时聊天页还挂在栈上，不这样写就永远收不到通知。
            if (ChatNotificationPolicy.shouldNotify(
                    openSessionTracker.openSessionId,
                    sessionId,
                    foregroundTracker.isForeground
                )
            ) {
                runCatching {
                    chatNotifier.notifyMessage(appContext, persona, sessionId, saved.content)
                }
            }
            // 这一轮聊完了：让他在后台把「已经聊过去的那一段」收成一段记忆。
            // 异步、不 await：压缩再慢也晚不了一毫秒回话，失败也当没发生。
            historyCompressor.ensureAsync(sessionId)
            // 记忆内置开启；抽不抽由人格的「抽记忆频率」决定（会话可覆盖）。
            val count = messageDao.countInSession(sessionId)
            val freshSession = sessionDao.getById(sessionId)
            val every = (freshSession?.extractionEveryOverride ?: persona.extractionEvery).coerceIn(3, 12)
            val pendingMemory = count - (freshSession?.lastMemoryExtractedCount ?: 0) >= every
            emit(GenerationEvent.Completed(saved, pendingMemory, proposal))
        } catch (cancel: CancellationException) {
            // 0.11.0（审计 P2-8）：这一轮到底有没有「值得落库的东西」由 InterruptedReply 决定。
            // 以前只要 traces 非空就落一条 content="" 的消息，界面上既没有正文（空串不渲染）
            // 也没有工具痕迹（只有 COMPLETE 才渲染），于是出现一个点不开的空气泡。
            if (!completed) {
                when (InterruptedReply.decide(shownText, traces.isNotEmpty())) {
                    InterruptedReply.Outcome.DISCARD -> Unit
                    InterruptedReply.Outcome.STATUS_ONLY,
                    InterruptedReply.Outcome.SAVE_TEXT -> withContext(NonCancellable) {
                        saveInterrupted(
                            sessionId = sessionId,
                            personaId = persona.id,
                            partial = shownText,
                            model = session.model.ifBlank { config.model },
                            toolTraceJson = ToolTraceCodec.encode(traces),
                            generation = generation
                        )
                    }
                }
            }
            throw cancel
        } catch (error: Exception) {
            if (!completed) {
                val userFacing = error.toUserError()
                val saved = withContext(NonCancellable) {
                    saveFailed(
                        sessionId = sessionId,
                        personaId = persona.id,
                        partial = shownText,
                        errorText = UserErrorTokens.encode(userFacing),
                        model = session.model.ifBlank { config.model },
                        toolTraceJson = ToolTraceCodec.encode(traces),
                        generation = generation
                    )
                }
                emit(GenerationEvent.Failed(userFacing, saved))
            }
        }
    }
        // 注册 / 注销「这段会话正在跑生成」，删除与清空按 sessionId 取消它。
        .onStart {
            currentCoroutineContext()[Job]?.let { job -> activeGenerations[sessionId] = job }
        }
        .onCompletion {
            activeGenerations.remove(sessionId, currentCoroutineContext()[Job])
        }

    /**
     * 收尾写入（成功 / 中断 / 失败）之前的最后一道检查：会话还在、世代没变。
     *
     * 两件事缺一不可：`NonCancellable` 让写入不受取消影响（这是为了「停止生成」也能留下
     * 已收到的半截话），但取消之后用户可能已经删了会话——存在性 + 世代号负责别写孤儿。
     */
    private suspend fun writeStillValid(sessionId: String, generation: Long): Boolean {
        if (!sessionGeneration.isCurrent(sessionId, generation)) return false
        return sessionDao.getById(sessionId) != null
    }

    /**
     * 按会话取消正在跑的生成。
     *
     * 删除 / 清空上下文先调它，让 UI 立刻停下；真正的正确性由 [writeStillValid] 保证
     * （取消是异步的，`NonCancellable` 的收尾可能仍然排在删除事务之后）。
     */
    fun cancelGeneration(sessionId: String) {
        activeGenerations.remove(sessionId)?.cancel()
    }

    private suspend fun saveInterrupted(
        sessionId: String,
        personaId: String,
        partial: String,
        model: String,
        toolTraceJson: String? = null,
        generation: Long
    ) {
        // 删除会话 / 清空上下文之后不再把半截回复写回去。
        if (!writeStillValid(sessionId, generation)) return
        val now = nextTimestamp(sessionId)
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            personaId = personaId,
            role = MessageRole.ASSISTANT,
            content = partial,
            createdAt = now,
            updatedAt = now,
            status = MessageStatus.STOPPED,
            errorMessage = null,
            model = model,
            edited = false,
            toolTraceJson = toolTraceJson
        )
        database.withTransaction {
            blockWriter.append(message)
            val fresh = sessionDao.getById(sessionId) ?: return@withTransaction
            sessionDao.update(
                fresh.copy(
                    // 只有工具调用、没有正文的中断：预览保持上一句，不写一个空预览（P2-8）。
                    lastMessagePreview = if (partial.isBlank()) {
                        fresh.lastMessagePreview
                    } else {
                        partial.replace('\n', ' ').trim().take(100)
                    },
                    lastMessageAt = now,
                    updatedAt = now
                )
            )
        }
    }

    private suspend fun saveFailed(
        sessionId: String,
        personaId: String,
        partial: String,
        errorText: String,
        model: String,
        toolTraceJson: String? = null,
        generation: Long
    ): MessageEntity? {
        // 同上：会话没了（或世代过期）就只把错误交给界面，不落一条孤儿错误消息。
        if (!writeStillValid(sessionId, generation)) return null
        val now = nextTimestamp(sessionId)
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            personaId = personaId,
            role = MessageRole.ASSISTANT,
            content = partial,
            createdAt = now,
            updatedAt = now,
            status = MessageStatus.ERROR,
            errorMessage = errorText,
            model = model,
            edited = false,
            toolTraceJson = toolTraceJson
        )
        database.withTransaction {
            blockWriter.append(message)
            val fresh = sessionDao.getById(sessionId)
            if (fresh != null) {
                sessionDao.update(
                    fresh.copy(
                        lastMessagePreview = partial.replace('\n', ' ').trim().take(100)
                            .ifBlank { appContext.getString(R.string.chat_preview_failed) },
                        lastMessageAt = now,
                        updatedAt = now
                    )
                )
                if (openSessionTracker.openSessionId != sessionId) sessionDao.incrementUnread(sessionId)
            }
        }
        return message
    }

    suspend fun prepareRegeneration(sessionId: String): Result<Unit> = runCatching {
        val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
        val latest = messageDao.latestInSession(sessionId)
        if (latest?.role == MessageRole.ASSISTANT) {
            blockWriter.deleteMessage(latest)
            refreshPreview(session)
        }
        if (messageDao.latestUser(sessionId) == null) userError(DomainErrorCode.NOTHING_TO_REGENERATE)
    }

    suspend fun editUserMessage(sessionId: String, messageId: String, newContent: String): Result<Unit> = runCatching {
        val content = newContent.trim()
        if (content.isBlank()) userError(DomainErrorCode.MESSAGE_EMPTY)
        if (content.length > 32_000) userError(DomainErrorCode.MESSAGE_TOO_LONG)
        val message = messageDao.getById(messageId) ?: userError(DomainErrorCode.MESSAGE_NOT_FOUND)
        if (message.role != MessageRole.USER || message.sessionId != sessionId) {
            userError(DomainErrorCode.ONLY_USER_MESSAGE_EDITABLE)
        }
        val now = System.currentTimeMillis()
        val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
        database.withTransaction {
            // 编辑用户消息按规范 §2「按锚点插入」处理：先移除旧行，再以同一个
            // `(createdAt, id)` 落回锚点消息所在的那一块（位置不变，块元数据由统一规则重算）。
            // 不直接 UPDATE content：否则「编辑」与「删除 + 锚点插入」会各算一套块元数据。
            blockWriter.replaceAtAnchor(
                message.copy(content = content, edited = true, updatedAt = now)
            )
            // 「删除这条消息之后的消息」按稳定序 (createdAt, id) 判断（审计 P2-7）：
            // 以前只删 createdAt 严格更晚的，后台消息刚好落在同一毫秒时旧回复会留下来，
            // 编辑重发后对话读起来是错位的。
            blockWriter.deleteAfterCursor(sessionId, message.createdAt, message.id)
            val latest = messageDao.latestInSession(sessionId)
            sessionDao.update(
                session.copy(
                    lastMessagePreview = latest?.content?.replace('\n', ' ')?.trim()?.take(100),
                    lastMessageAt = latest?.createdAt,
                    updatedAt = now
                )
            )
        }
        refreshPreview(session)
    }

    suspend fun deleteMessage(messageId: String): Result<Unit> = runCatching {
        val message = messageDao.getById(messageId) ?: return@runCatching
        blockWriter.deleteMessage(message)
        // 消息没了，它带着的图片文件也不该继续占地方。
        deleteAttachmentFiles(listOf(message))
        val session = sessionDao.getById(message.sessionId) ?: return@runCatching
        refreshPreview(session)
    }

    suspend fun clearContext(sessionId: String): Result<Unit> = runCatching {
        // 先让「这段会话的后台写入」全部作废：生成收尾（NonCancellable）与历史压缩
        // 都可能排在删除事务之后，把半截回复或旧摘要写回来。世代号 + 取消双保险。
        sessionGeneration.bump(sessionId)
        cancelGeneration(sessionId)
        historyCompressor.cancel(sessionId)
        val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
        val now = System.currentTimeMillis()
        // 先取一遍要删的消息，事务提交后把它们的图片文件一并清掉（事务里不做文件 IO）。
        val doomed = messageDao.getBySession(sessionId)
        database.withTransaction {
            blockWriter.deleteSession(sessionId)
            sessionDao.update(
                session.copy(
                    lastMessagePreview = null,
                    lastMessageAt = null,
                    unreadCount = 0,
                    lastMemoryExtractedCount = 0,
                    // 消息都删了：那段「更早之前你记得的」也跟着作废，别让新对话背着旧摘要。
                    historySummary = null,
                    historySummarizedUpTo = null,
                    historySummaryAt = null,
                    updatedAt = now
                )
            )
        }
        deleteAttachmentFiles(doomed)
    }

    suspend fun renameSession(sessionId: String, title: String): Result<Unit> = runCatching {
        val value = title.trim()
        if (value.isBlank()) userError(DomainErrorCode.TITLE_EMPTY)
        sessionDao.rename(sessionId, value.take(120), System.currentTimeMillis())
    }

    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        sessionDao.setPinned(sessionId, pinned, System.currentTimeMillis())
    }

    suspend fun markRead(sessionId: String) {
        sessionDao.markRead(sessionId)
    }

    /**
     * 通知深链的落点校验（安全 P2-7 / 审计 P2-6）：这个 `extra_session_id` 能不能直接打开。
     *
     * 任意应用都能给 exported 的 `MainActivity` 塞这个 extra，所以字符串一律不可信：
     * 会话必须真的存在，而且属于当前 `activePersonaId`。任何一条不满足都返回 null，
     * 界面停在首页（不导航、不 `markRead`、也不撤别人的通知）。
     */
    suspend fun resolveNotificationSession(raw: String?): String? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        val session = sessionDao.getById(candidate) ?: return null
        val active = runCatching { settingsRepository.settings.first().activePersonaId }.getOrNull()
        return NotificationDeepLink.sessionIdToOpen(candidate, session.personaId, active)
    }

    /** 打开某段会话（原子写入，见 [OpenSessionTracker]）。 */
    fun setOpenSession(id: String?) {
        openSessionTracker.open(id)
    }

    /**
     * 「我的这段会话还开着吗」——VM 销毁时的 compare-and-clear。
     *
     * 只有当前打开的仍是 [sessionId] 才清空：转场期间新 VM 可能已经写入了下一段会话，
     * 旧 VM 的 `onCleared` 不能把它一起清掉（否则用户正看着的会话被判成没开着，
     * 未读 +1 并且弹一条「你正在读的消息」的通知）。
     */
    fun clearOpenSession(sessionId: String) {
        openSessionTracker.clearIfOpen(sessionId)
    }

    fun isSessionOpen(sessionId: String): Boolean = openSessionTracker.isOpen(sessionId)

    /** 当前屏幕上看的是哪段会话（没有为 null）。通知判定统一走 [ChatNotificationPolicy]。 */
    fun openSessionId(): String? = openSessionTracker.openSessionId

    suspend fun deleteSession(sessionId: String) {
        // 与 clearContext 同一套顺序：先 bump（让在途写入一定作废），再取消。
        sessionGeneration.bump(sessionId)
        cancelGeneration(sessionId)
        historyCompressor.cancel(sessionId)
        val doomed = messageDao.getBySession(sessionId)
        database.withTransaction {
            blockWriter.deleteSession(sessionId)
            sessionDao.deleteById(sessionId)
        }
        deleteAttachmentFiles(doomed)
        // 会话彻底没了：世代记录也没必要留着（不然后台写入的 key 会无限增长）。
        sessionGeneration.forget(sessionId)
    }

    /**
     * 删掉这些消息带在身上的图片文件（只删 App 自己媒体目录里的）。
     *
     * **必须在数据库行删掉之后调用**（0.12 媒体去重）：聊天图按内容寻址命名，
     * 同一个 `chat-<sha>.<ext>` 可能被别的消息引用；先删行再查引用，
     * 本次要删的那条消息自己才不会被算成「还有人在用」（否则文件永远删不掉），
     * 而别人还在用的那一份也不会被误删。
     */
    private suspend fun deleteAttachmentFiles(messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        runCatching {
            val attachments = messages.flatMap { AttachmentsCodec.decode(it.attachmentsJson) }
            if (attachments.isNotEmpty()) mediaJanitor.deleteAttachmentsIfUnreferenced(attachments)
        }
    }

    suspend fun bindSessionConfig(sessionId: String, configId: String): Result<ChatSessionEntity> = runCatching {
        val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
        val config = apiConfigDao.getById(configId) ?: userError(DomainErrorCode.API_CONFIG_NOT_FOUND)
        val persona = personaDao.getById(session.personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        val model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: config.model
        val now = System.currentTimeMillis()
        database.withTransaction {
            sessionDao.rebindConfig(sessionId, config.id, model, now)
            if (persona.defaultApiConfigId == null) {
                personaDao.update(
                    persona.copy(
                        defaultApiConfigId = config.id,
                        updatedAt = now
                    )
                )
            }
        }
        sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
    }

    /**
     * 检索页的「消息」页签：**逐块扫描 + 键集游标，无候选上限**（规范 §4）。
     *
     * ## 一次检索的完整路径
     *
     * 1. [MessageSearchSql.blocksQuery] 枚举（可过滤人格/时间的）块——一条 SQL，只回
     *    `(sessionId, blockIndex)`，不读正文；
     * 2. 对每一块调 [MessageSearchSql.blockScanQuery]：**一条 SQL 只读一块**（`blockIndex = ?`），
     *    块内命中由 [BlockSearch] 用 [SearchRanking] 的同一套词元规则判定并打分；
     * 3. 遍历时只维护「这一页 + 一条 frontier」（固定大小的堆），一块读完立即丢掉，
     *    所以峰值内存 ≈ 一块 + 一页，与会话总长度无关；
     * 4. 翻页用键集游标 `(matchedTermCount, relevance, createdAt, id)`：下一页重走一遍块，
     *    只保留**严格排在游标之后**的行。这就是「结果完整且不重复」的保证，
     *    也是 0.11 的「候选上限 500」被删掉的原因。
     *
     * 返回的 `total` 是这一遍扫描的**全部**命中数（不是候选池大小）：界面显示「共 N 条」时
     * 不再需要附加一句「只显示最近的部分」。
     *
     * 只读本地库：不联网、不写库、不改任何状态。
     */
    suspend fun searchMessages(request: SearchQuery): Result<SearchPage> = runCatching {
        val text = request.text.trim()
        if (text.isEmpty()) return@runCatching SearchPage()
        val limit = request.limit.coerceIn(1, 100)
        val filter = SearchFilter(
            personaId = request.personaId,
            since = request.since,
            until = request.until
        )
        val page = BlockSearch.page(
            source = blockScanSource(text = text, filter = filter),
            query = text,
            filter = filter,
            limit = limit,
            cursor = request.cursor
        )
        SearchPage(
            query = text,
            hits = page.hits.map { hit -> toSearchHit(hit.payload, text) },
            total = page.totalMatches,
            hasMore = page.hasMore,
            nextCursor = page.nextCursor
        )
    }

    /**
     * 检索的数据源：枚举块 → 一块一块读。
     *
     * 做成匿名实现而不是往 DAO 里塞循环，是为了让「每条 SQL 只读一块」在源码上就是显然的
     * （`BlockSearchTest` 用假数据源断言同一个形状：每次 `messages()` 只拿到一个块键）。
     */
    private fun blockScanSource(
        text: String,
        filter: SearchFilter
    ): BlockSearch.BlockScanSource<MessageSearchRow> = object : BlockSearch.BlockScanSource<MessageSearchRow> {
        override suspend fun blocks(filter: SearchFilter): List<BlockSearch.BlockKey> {
            val query = MessageSearchSql.blocksQuery(filter.personaId, filter.since, filter.until)
            if (query.sql.isEmpty()) return emptyList()
            return blockDao.searchBlocks(query.toSupportQuery()).map { row: SearchBlockRow ->
                BlockSearch.BlockKey(row.sessionId, row.blockIndex)
            }
        }

        override suspend fun messages(block: BlockSearch.BlockKey): List<Pair<RankInput, MessageSearchRow>> {
            val query = MessageSearchSql.blockScanQuery(
                text = text,
                sessionId = block.sessionId,
                blockIndex = block.blockIndex,
                since = filter.since,
                until = filter.until
            )
            if (query.sql.isEmpty()) return emptyList()
            return messageDao.searchBlockMessages(query.toSupportQuery()).map { row ->
                RankInput(
                    id = row.messageId,
                    content = row.content,
                    createdAt = row.createdAt,
                    personaId = row.personaId
                ) to row
            }
        }
    }

    /**
     * 检索页的「会话 / 人格」页签。
     *
     * 会话表很小（一人一段），所以照旧在内存里过滤：标题、人格名、最后一条预览。
     * 消息正文的命中在「消息」页签里，这里只回答「哪段对话值得点进去」。
     */
    suspend fun searchSessions(
        text: String,
        personaId: String? = null,
        since: Long? = null
    ): List<SessionSearchHit> {
        val term = text.trim()
        if (term.isEmpty()) return emptyList()
        val personas = personaDao.getAll().associateBy { it.id }
        return sessionDao.getAll()
            .asSequence()
            .filter { personaId == null || it.personaId == personaId }
            .filter { since == null || (it.lastMessageAt ?: it.updatedAt) >= since }
            .mapNotNull { session ->
                val persona = personas[session.personaId]
                val titleHit = session.title.indexOf(term, ignoreCase = true) >= 0
                val personaHit = persona?.name?.indexOf(term, ignoreCase = true)?.let { it >= 0 } == true
                val previewHit = session.lastMessagePreview?.indexOf(term, ignoreCase = true)?.let { it >= 0 } == true
                if (!titleHit && !personaHit && !previewHit) return@mapNotNull null
                SessionSearchHit(
                    sessionId = session.id,
                    personaId = session.personaId,
                    title = session.title,
                    personaName = persona?.name.orEmpty(),
                    personaAvatarUri = persona?.avatarUri,
                    personaAvatarSeed = persona?.avatarSeed ?: 0,
                    preview = session.lastMessagePreview,
                    lastMessageAt = session.lastMessageAt
                )
            }
            .sortedWith(
                compareByDescending<SessionSearchHit> { it.lastMessageAt ?: 0L }
                    .thenBy { it.sessionId }
            )
            .toList()
    }

    private fun toSearchHit(row: MessageSearchRow, query: String): SearchHit {
        val snippet = SearchSnippet.build(row.content, query)
        return SearchHit(
            messageId = row.messageId,
            sessionId = row.sessionId,
            sessionTitle = row.sessionTitle,
            personaId = row.personaId,
            personaName = row.personaName,
            personaAvatarUri = row.personaAvatarUri,
            personaAvatarSeed = row.personaAvatarSeed,
            snippet = snippet.text,
            highlights = snippet.highlights,
            createdAt = row.createdAt,
            role = row.role
        )
    }

    private fun com.myfriend.app.data.local.SqlQuery.toSupportQuery(): SimpleSQLiteQuery =
        SimpleSQLiteQuery(sql, args.toTypedArray())

    suspend fun exportSession(sessionId: String): Result<ExportFile> = runCatching {
        // 组装 + JSON 编码都可能在主线程上几十毫秒到几百毫秒（几百上千条消息），
        // 一律下沉到 IO；界面只负责把结果写进用户选的文件（同样在 IO 上）。
        withContext(Dispatchers.IO) {
            val session = sessionDao.getById(sessionId) ?: userError(DomainErrorCode.SESSION_NOT_FOUND)
            val persona = personaDao.getById(session.personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
            val config = session.apiConfigId?.let { apiConfigDao.getById(it) }
            val messages = messageDao.getBySession(sessionId)
            val dto = ExportSession(
                sessionId = session.id,
                title = session.title,
                personaName = persona.name,
                personaCategory = persona.category,
                personaVersion = persona.version,
                apiConfigName = config?.name,
                model = session.model,
                createdAt = session.createdAt,
                messages = messages.map {
                    ExportMessage(
                        role = it.role.name.lowercase(),
                        content = it.content,
                        createdAt = it.createdAt,
                        status = it.status.name
                    )
                }
            )
            val bundle = ExportBundle(
                exportedAt = System.currentTimeMillis(),
                sessions = listOf(dto)
            )
            ExportFile(
                fileName = "myfriend-chat-${sanitizeFile(persona.name)}-${System.currentTimeMillis()}.json",
                content = json.encodeToString(ExportBundle.serializer(), bundle)
            )
        }
    }

    /** 「导出全部对话」的默认文件名（界面先拿它去开系统文件选择器）。 */
    fun suggestedAllChatsFileName(): String = "myfriend-all-chats-${System.currentTimeMillis()}.json"

    /**
     * 「导出全部对话」：边读库、边序列化、边**分批**交给 [writeChunk] 写文件。
     *
     * 不再先拼一个几 MB～几十 MB 的字符串：峰值只跟单条会话和
     * [ExportJsonStream.DEFAULT_BATCH_CHARS] 有关。调用方负责把 [writeChunk]
     * 落到 `contentResolver.openOutputStream(uri)`（本函数已经在 IO 上执行）。
     *
     * @return 建议的文件名
     */
    suspend fun exportAllChatsTo(writeChunk: suspend (String) -> Unit): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val exportedAt = System.currentTimeMillis()
            val fileName = "myfriend-all-chats-$exportedAt.json"
            val personas = personaDao.getAll().associateBy { it.id }
            val configs = apiConfigDao.getAll().associateBy { it.id }
            val sessions = sessionDao.getAll()
            val stream = ExportJsonStream { chunk -> writeChunk(chunk) }
            stream.start(schemaVersion = EXPORT_SCHEMA_VERSION, exportedAt = exportedAt)
            sessions.forEach { session ->
                val persona = personas[session.personaId]
                val dto = ExportSession(
                    sessionId = session.id,
                    title = session.title,
                    personaName = persona?.name ?: "",
                    personaCategory = persona?.category ?: "",
                    personaVersion = persona?.version ?: 0,
                    apiConfigName = session.apiConfigId?.let { configs[it]?.name },
                    model = session.model,
                    createdAt = session.createdAt,
                    messages = messageDao.getBySession(session.id).map {
                        ExportMessage(it.role.name.lowercase(), it.content, it.createdAt, it.status.name)
                    }
                )
                stream.writeSession(json.encodeToString(ExportSession.serializer(), dto))
            }
            stream.finish()
            fileName
        }
    }

    /**
     * 「清空全部聊天与记忆」。
     *
     * 清单在 [com.myfriend.app.domain.ErasurePlan]：除了消息/会话/记忆，还包括
     * `memory_echoes`（原话）、`memory_consolidations`（整理摘要）、`memory_versions`
     * （记忆旧正文）、`memory_vectors`，以及聊天图片文件（不碰聊天背景与头像）。
     */
    suspend fun clearAllChatData() {
        // 文件清理（删聊天图）在 IO 上做：`ErasureFiles` 是非挂起接口，那里的引用查询会阻塞
        // 调用线程（见 `AndroidErasureFiles`），不能让它阻塞主线程。
        withContext(Dispatchers.IO) { dataEraser.clearChatsAndMemories() }
        // 审计 P2-2（0.11.1）：与 wipeAllData 对齐——表都删了，还挂着的通知与被认为
        // 「仍开着」的会话都要收掉，否则点旧通知会跳进一个不存在的会话、未读判定也拿旧值比较。
        openSessionTracker.clearAll()
        runCatching { chatNotifier.cancelAll(appContext) }
    }

    /**
     * 「删除全部本地数据」。
     *
     * 除聊天与记忆外，还清 `agent_ticks` / `agent_intents` / `persona_pending_patches` /
     * 人格版本历史 / 主动节奏 / 人格预设 / 人格 / API 配置，以及整个媒体目录。
     */
    suspend fun wipeAllData() {
        // 整棵媒体目录的删除是阻塞文件 IO；与其他删除路径一样挪到 IO 上。
        withContext(Dispatchers.IO) { dataEraser.wipeAllData() }
        openSessionTracker.clearAll()
        // 「删除全部数据」之后不该还挂着任何一条他的通知（审计 P2-14：以前 cancel 只支持单会话）。
        runCatching { chatNotifier.cancelAll(appContext) }
    }

    /**
     * 睡眠判定：把人格的作息、免打扰、唤醒阈值和「最近 30 分钟几条消息」交给 agent 决策层。
     *
     * 最近消息数包含刚刚发来的这一条——所以「第 N 条叫醒」的语义是直观的，
     * 不需要额外建表记「叫了几次」，30 分钟窗口自己会滚动清零。
     *
     * 0.11.0：判定本身改为经由 [AgentCoreClient]（默认进程内委托 `InProcessAgentCore`，
     * 打开构建开关时请求 `agentd` 的同名接口，失败自动回退）。这里只负责取数据与落痕迹，
     * 规则仍只有一份（`:agentcore`），行为与 0.10.x 逐字一致。
     */
    private suspend fun evaluateSleep(
        persona: PersonaEntity,
        sessionId: String,
        now: Long
    ): SleepDecision {
        val minute = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .let { it.hour * 60 + it.minute }
        // 不睡觉的人不需要查「最近几条」：判定输入里 sleepEnabled=false 已经足够。
        val recent = if (persona.sleepEnabled) {
            runCatching {
                messageDao.countUserMessagesSince(sessionId, now - SleepPolicy.WAKE_WINDOW_MS)
            }.getOrDefault(0)
        } else {
            0
        }
        // 放到 IO：远程模式下这是一次同步 HTTP（最长 1.5 秒），而这条 flow 是从
        // viewModelScope（Main）collect 的；留在主线程会触发 NetworkOnMainThreadException
        // 并被客户端当成「远程失败」，于是永远静默回退。进程内实现只是多一次调度，可忽略。
        return withContext(Dispatchers.IO) {
            agentCore.sleepDecision(
                SleepInput(
                    sleepEnabled = persona.sleepEnabled,
                    dndOnSleep = persona.dndOnSleep,
                    sleepMinutes = persona.quietSleepMinutes,
                    wakeMinutes = persona.quietWakeMinutes,
                    nowMinutes = minute,
                    userMessagesInWindow = recent,
                    wakeThreshold = persona.wakeThreshold
                )
            )
        }
    }

    private suspend fun buildContext(
        session: ChatSessionEntity,
        persona: PersonaEntity,
        config: com.myfriend.app.data.local.ApiConfigEntity,
        recentMessages: List<MessageEntity>
    ): List<WireMessage> {
        // 近处这一段按时间正序排好；已经被摘要盖住的那部分不再逐字塞进来
        // ——它已经在 system 里那一段「更早之前你记得的」里了，同一个意思说两遍只会让他混乱。
        val summarizedUpTo = session.historySummarizedUpTo?.takeIf { it > 0L }
        val visible = recentMessages
            .asReversed()
            .filter { summarizedUpTo == null || it.createdAt > summarizedUpTo }
        // 用最近一句对方说的话当检索线索，挑「此刻最相关」的记忆。
        val recallQuery = visible.lastOrNull { it.role == MessageRole.USER }?.content?.take(300).orEmpty()
        // 1.9：记忆内置开启；「记性」决定此刻能想起多少条（1 忘得快，5 什么都记得）。
        val memoryLimit = 4 + persona.memoryRetention.coerceIn(1, 5) * 2
        val memories = memoryRepository.getRelevant(persona.id, query = recallQuery, limit = memoryLimit)
        // 只带着最近两条消息里的图片（审计 P2-9）：先按「数量预算」挑消息，再逐张解码，
        // 别为裁掉的消息解一遍。真正的双重限制（条数 / 单图尺寸 / 总字符预算）在
        // ImageContextBudget 里，纯逻辑单测钉住了上界。
        val imageOwners = ImageContextBudget.ownersWithImages(
            visible.map {
                ImageContextBudget.Candidate(
                    messageId = it.id,
                    isUser = it.role == MessageRole.USER,
                    hasImages = !it.attachmentsJson.isNullOrBlank()
                )
            }
        )
        // 整个上下文里所有 data URL 的字符预算：塞不下就不再带（宁可少一张，也别把堆撑爆）。
        var usedImageChars = 0
        val history = visible.filter {
            (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) &&
                (it.content.isNotBlank() || !it.attachmentsJson.isNullOrBlank()) &&
                it.status != MessageStatus.ERROR
        }.map { message ->
            val outgoing = message.role == MessageRole.USER
            val images = if (outgoing && message.id in imageOwners) {
                buildList {
                    val attachments = AttachmentsCodec.decode(message.attachmentsJson)
                        .take(ImageContextBudget.imagesForMessage(Int.MAX_VALUE))
                    for (attachment in attachments) {
                        // 发送前按目标尺寸重编码（长边 ≤1024、单张 ≤320KB），并且守住整体预算。
                        val dataUrl = MediaStorage.toContextDataUrl(attachment) ?: continue
                        if (!ImageContextBudget.acceptsEncoded(usedImageChars, dataUrl)) continue
                        add(dataUrl)
                        usedImageChars += dataUrl.length
                    }
                }
            } else {
                emptyList()
            }
            WireMessage(
                role = if (outgoing) "user" else "assistant",
                content = message.content.ifBlank { if (images.isNotEmpty()) "（发来一张图片）" else "" },
                images = images
            )
        }
        val trimmed = trimHistory(history)
        // 心情只读一次库：提示词那一行和「今天话多话少」都由这一个状态换算得出，不再各自去查。
        val mood = moodRepository.current(persona.id)
        val moodLine = MoodPrompt.line(mood)
        val moodNudge = moodRepository.reflectionNudge(persona.id).orEmpty()
        // 心情也决定他今天话多话少：只补很短的一行，不动人格设定，也不改回复长度的硬逻辑。
        val moodPace = MoodBehavior.promptLine(mood)
        // 他此刻在做什么：一行，很短，像他自己状态，不像状态栏。
        val presenceLine = presenceLine(persona.id)
        // 更早之前他记得的：只有真的压过（有摘要、也有压到哪一条的时间点）才注入。
        val memoryOfEarlier = HistoryCompression.memoryBlock(session.historySummary, session.historySummarizedUpTo)
        // 0.12 人设引导：系统提示词按规范 §1 的五段顺序拼（身份 → 世界 → 此刻状态 → 话风 → 关系），
        // 记忆 / 念头 / 工具说明放在边界之前；边界与语言指令由 PromptBuilder 统一收尾。
        val now = System.currentTimeMillis()
        val card = personaCardRepository.readCard(persona)
        val minuteOfDay = runCatching {
            Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
        }.getOrDefault(12 * 60)
        val asleep = runCatching { personaCardRepository.isAsleep(persona, minuteOfDay) }.getOrDefault(false)
        val system = PromptBuilder.system(
            PromptBuilder.Inputs(
                name = persona.name,
                age = card.identity.age,
                role = card.identity.role,
                relation = card.identity.relation,
                address = card.voice.address,
                card = card,
                legacyPrompt = persona.prompt,
                profileBlock = PersonaProfile.promptBlock(persona),
                world = WorldPresetCodec.decode(persona.worldJson),
                speech = SpeechStyleCodec.decode(persona.speechStyleJson),
                activity = PersonaActivityCodec.decode(persona.activityJson),
                now = now,
                zone = ZoneId.systemDefault(),
                moodBlock = moodLine,
                asleep = asleep,
                extraStatus = listOf(moodPace, presenceLine(persona.id)).filter { it.isNotBlank() }
                    .joinToString("\n"),
                variableBlocks = listOfNotNull(
                    memoryRepository.formatForPrompt(memories).takeIf { memories.isNotEmpty() },
                    memoryOfEarlier.takeIf { it.isNotBlank() },
                    "---\n" + ProactiveProtocol.CAPABILITY_PROMPT,
                    ToolCatalog.capabilityPrompt(webEnabled = config.webToolsEnabled),
                    moodNudge.takeIf { it.isNotBlank() }
                ),
                // 语言指令跟着 App 语言走，脚手架保持中文。
                languageSection = LanguageDirective.section(
                    appContext.getString(R.string.prompt_language_directive),
                    LanguageDirective.languageName()
                )
            )
        )
        return listOf(WireMessage("system", system)) + trimmed
    }

    /**
     * 「他此刻在做什么」的那一行。
     *
     * 只是让语气对得上此刻：他在忙、刚睡醒、正想着事，回话的样子都不一样。
     * 用 [PresenceRepository.current] 要一个一次性快照（不订阅、不留协程），
     * 拿到的是他自己视角的说法，直接拼进 system 不会像在讲别人。
     * 拿不到或者没内容就当没有这一行——状态不该成为聊天里的一个障碍。
     */
    private suspend fun presenceLine(personaId: String): String = runCatching {
        // 这一段现在并进 PromptBuilder 的「你此刻的状态」，只返回正文，不再自己带标题。
        presenceRepository.current(personaId).detail.trim()
    }.getOrDefault("")

    private fun trimHistory(history: List<WireMessage>): List<WireMessage> {
        if (history.isEmpty()) return history
        val result = ArrayDeque<WireMessage>()
        var chars = 0
        for (message in history.asReversed()) {
            if (result.isNotEmpty() && chars + message.content.length > CONTEXT_CHAR_BUDGET) break
            result.addFirst(message)
            chars += message.content.length
        }
        return result.toList()
    }

    private suspend fun refreshPreview(session: ChatSessionEntity) {
        val latest = messageDao.latestInSession(session.id)
        val now = System.currentTimeMillis()
        sessionDao.updatePreview(
            session.id,
            latest?.content?.replace('\n', ' ')?.trim()?.take(100)?.ifBlank {
                if (latest?.status == MessageStatus.ERROR) {
                    appContext.getString(R.string.chat_preview_failed)
                } else {
                    null
                }
            },
            latest?.createdAt,
            now
        )
    }

    private suspend fun nextTimestamp(sessionId: String): Long =
        MessageOrder.nextTimestamp(
            latestCreatedAt = messageDao.latestInSession(sessionId)?.createdAt,
            now = System.currentTimeMillis()
        )

    private fun autoTitle(content: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 26) flat else flat.take(26) + "…"
    }

    private fun sanitizeFile(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").take(30).ifBlank { "chat" }

    private companion object {
        /** 一次回复里最多连着用几轮工具，用完就必须说话。 */
        const val MAX_TOOL_ROUNDS = 3

        /** 聊天导出的 schema 版本（与 `ExportBundle` 的默认值一致）。 */
        const val EXPORT_SCHEMA_VERSION = 1

        /** 一条消息最多带几张图（与 `ImageContextBudget` 的预算一致）。 */
        const val MAX_IMAGES_PER_MESSAGE = ImageContextBudget.MAX_IMAGES_PER_MESSAGE

        const val CONTEXT_CHAR_BUDGET = 28_000

        /**
         * 装配上下文时最多从库里读多少条消息。
         *
         * 不是「只记得这么多」——更早的部分由 [HistoryCompressor] 收成一段摘要放进 system；
         * 这里只是别让一次聊天把整段历史读进内存（几百条够覆盖两万字预算了）。
         */
        const val CONTEXT_RECENT_LIMIT = 200
    }
}
