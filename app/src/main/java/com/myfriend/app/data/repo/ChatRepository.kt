package com.myfriend.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.myfriend.app.R
import com.myfriend.app.data.local.AgentTickDao
import com.myfriend.app.data.local.AgentTickEntity
import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MemoryDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.MessageSearchRow
import com.myfriend.app.data.local.MessageStatus
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.PersonaRevisionDao
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.domain.AttachmentsCodec
import com.myfriend.app.domain.ChatExport
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.domain.GenerationEvent
import com.myfriend.app.domain.HistoryCompression
import com.myfriend.app.domain.LanguageDirective
import com.myfriend.app.domain.LlmLane
import com.myfriend.app.domain.MoodRecovery
import com.myfriend.app.domain.MessageAttachment
import com.myfriend.app.domain.MoodBehavior
import com.myfriend.app.domain.MoodPrompt
import com.myfriend.app.domain.OpenSessionTracker
import com.myfriend.app.domain.PersonaSwitchResult
import com.myfriend.app.domain.SleepPolicy
import com.myfriend.app.domain.ToolCatalog
import com.myfriend.app.domain.ToolProtocol
import com.myfriend.app.domain.ToolResult
import com.myfriend.app.domain.ToolTraceCodec
import com.myfriend.app.domain.ToolTraceItem
import com.myfriend.app.network.ApiException
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.StreamEvent
import com.myfriend.app.network.WireMessage
import com.myfriend.app.network.inferenceParams
import com.myfriend.app.util.ChatNotifier
import com.myfriend.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SessionSearchHit(
    val sessionId: String,
    val title: String,
    val personaName: String,
    val personaAvatarUri: String? = null,
    val personaAvatarSeed: Int = 0,
    val preview: String? = null,
    val lastMessageAt: Long? = null
)

@Serializable
data class SearchResults(
    val sessions: List<SessionSearchHit> = emptyList(),
    val messages: List<MessageSearchHit> = emptyList()
)

@Serializable
data class MessageSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val personaName: String,
    val personaAvatarUri: String? = null,
    val personaAvatarSeed: Int = 0,
    val content: String,
    val createdAt: Long
)

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
    private val personaDao: PersonaDao,
    private val apiConfigDao: ApiConfigDao,
    private val memoryDao: MemoryDao,
    private val revisionDao: PersonaRevisionDao,
    private val database: MyFriendDatabase,
    private val apiConfigRepository: ApiConfigRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryTermDao: com.myfriend.app.data.local.MemoryTermDao,
    private val registry: AiProviderRegistry,
    private val settingsRepository: SettingsRepository,
    private val openSessionTracker: OpenSessionTracker,
    private val toolExecutor: ToolExecutor,
    private val personaCardRepository: PersonaCardRepository,
    private val moodRepository: MoodRepository,
    private val presenceRepository: PresenceRepository,
    private val historyCompressor: HistoryCompressor,
    private val agentTickDao: AgentTickDao,
    @ApplicationContext private val appContext: Context,
    private val chatNotifier: ChatNotifier,
    private val foregroundTracker: com.myfriend.app.util.AppForegroundTracker,
    private val json: Json
) {
    fun observeSessions(): Flow<List<ChatSessionEntity>> = sessionDao.observeAll()
    fun observeSession(id: String): Flow<ChatSessionEntity?> = sessionDao.observeById(id)
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = messageDao.observeBySession(sessionId)

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
        val persona = personaDao.getById(personaId) ?: error("人格不存在")
        val configId = apiConfigId ?: persona.defaultApiConfigId
        val config = configId?.let { apiConfigDao.getById(it) }
        val model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: config?.model.orEmpty()
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            personaId = persona.id,
            apiConfigId = config?.id,
            model = model,
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: "与 ${persona.name} 的对话",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            pinned = false,
            createdAt = now,
            updatedAt = now,
            lastPersonaVersion = persona.version,
            lastMemoryExtractedCount = 0
        )
        sessionDao.insert(session)
        session
    }

    suspend fun getOrCreateForPersona(personaId: String): ChatSessionEntity =
        sessionDao.latestForPersona(personaId) ?: createSession(personaId).getOrThrow()

    suspend fun switchToPersona(personaId: String): PersonaSwitchResult {
        val persona = personaDao.getById(personaId)
            ?: return PersonaSwitchResult(false, null, "人格不存在")
        val session = runCatching { getOrCreateForPersona(personaId) }.getOrElse {
            return PersonaSwitchResult(false, null, it.message ?: "无法创建会话")
        }
        settingsRepository.setActivePersonaId(personaId)
        val config = getConfig(session.apiConfigId)
        return if (config == null) {
            PersonaSwitchResult(true, session, "尚未绑定 API，请在会话中完成配置")
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
        require(content.isNotBlank() || images.isNotEmpty()) { "消息不能为空" }
        require(content.length <= 32_000) { "消息过长" }
        val session = sessionDao.getById(sessionId) ?: error("会话不存在")
        require(session.apiConfigId != null) { "请先为该会话选择 API 配置" }
        apiConfigDao.getById(session.apiConfigId) ?: error("API 配置已不存在")
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
            messageDao.insert(message)
            val preview = content.replace('\n', ' ').trim().take(90)
                .ifBlank { if (images.isNotEmpty()) "图片" else "" }
            val title = if (isFirstUserMessage) autoTitle(content.ifBlank { "图片" }) else session.title
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
        val session = sessionDao.getById(sessionId)
            ?: run {
                emit(GenerationEvent.Failed("会话不存在", null))
                return@flow
            }
        val persona = personaDao.getById(session.personaId)
            ?: run {
                emit(GenerationEvent.Failed("人格不存在", null))
                return@flow
            }
        val configId = session.apiConfigId
            ?: run {
                emit(GenerationEvent.Failed("请先为该会话选择 API 配置", null))
                return@flow
            }
        val resolved = apiConfigRepository.resolve(configId)
            ?: run {
                emit(GenerationEvent.Failed("API 配置已不存在", null))
                return@flow
            }
        val config = resolved.config
        // 只读最近 N 条：长会话不再每次把整段历史读进内存（更早的部分已经收成摘要了）。
        val recentHistory = messageDao.recentBySession(sessionId, CONTEXT_RECENT_LIMIT)
        if (recentHistory.none { it.role == MessageRole.USER }) {
            emit(GenerationEvent.Failed("还没有可以回复的消息", null))
            return@flow
        }
        val now0 = System.currentTimeMillis()
        // 睡眠 / 免打扰 / 唤醒阈值（规格 §六）。判定是纯函数，这里只负责拿数据、落痕迹。
        val sleepDecision = evaluateSleep(persona, sessionId, now0)
        if (sleepDecision.asleep && !sleepDecision.woken) {
            // 他确实看到了（已读），但这会儿不会回。界面只显示「已读」，不显示任何解释。
            // 免打扰是例外：连「已读」都不留 —— 那是「他不想被打扰」的一部分（规格 §6.2）。
            if (sleepDecision.marksRead) {
                runCatching { messageDao.markUserMessagesRead(sessionId, now0) }
            }
            emit(GenerationEvent.Silent)
            return@flow
        }
        if (sleepDecision.woken) {
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
                throw ApiException(
                    null,
                    if (traces.isEmpty()) "模型没有返回任何文字" else "他查完了东西，怕是走神了，再发一条试试"
                )
            }
            val saved = withContext(NonCancellable) {
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
                    messageDao.insert(assistant)
                    val preview = finalText.replace('\n', ' ').trim().take(100).ifBlank { "主动提议" }
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
                    if (openSessionTracker.openSessionId == sessionId) {
                        sessionDao.markRead(sessionId)
                    } else {
                        sessionDao.incrementUnread(sessionId)
                    }
                }
                assistant
            }
            completed = true
            // 普通回复落库后：只要「这个会话没开着」或者「App 根本不在前台」，都补一条系统通知。
            // 后者是必须的——按 Home 退到桌面时聊天页还挂在栈上，不这样写就永远收不到通知。
            if (openSessionTracker.openSessionId != sessionId || !foregroundTracker.isForeground) {
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
            if (!completed && (shownText.isNotBlank() || traces.isNotEmpty())) {
                withContext(NonCancellable) {
                    saveInterrupted(
                        sessionId = sessionId,
                        personaId = persona.id,
                        partial = shownText,
                        model = session.model.ifBlank { config.model },
                        toolTraceJson = ToolTraceCodec.encode(traces)
                    )
                }
            }
            throw cancel
        } catch (error: Exception) {
            if (!completed) {
                val message = when (error) {
                    is ApiException -> error.message
                    else -> error.message ?: "生成失败"
                }.take(800)
                val saved = withContext(NonCancellable) {
                    saveFailed(
                        sessionId = sessionId,
                        personaId = persona.id,
                        partial = shownText,
                        errorText = message,
                        model = session.model.ifBlank { config.model },
                        toolTraceJson = ToolTraceCodec.encode(traces)
                    )
                }
                emit(GenerationEvent.Failed(message, saved))
            }
        }
    }

    private suspend fun saveInterrupted(
        sessionId: String,
        personaId: String,
        partial: String,
        model: String,
        toolTraceJson: String? = null
    ) {
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
            messageDao.insert(message)
            val fresh = sessionDao.getById(sessionId) ?: return@withTransaction
            sessionDao.update(
                fresh.copy(
                    lastMessagePreview = partial.replace('\n', ' ').trim().take(100),
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
        toolTraceJson: String? = null
    ): MessageEntity? {
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
            messageDao.insert(message)
            val fresh = sessionDao.getById(sessionId)
            if (fresh != null) {
                sessionDao.update(
                    fresh.copy(
                        lastMessagePreview = partial.replace('\n', ' ').trim().take(100).ifBlank { "回复失败" },
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
        val session = sessionDao.getById(sessionId) ?: error("会话不存在")
        val latest = messageDao.latestInSession(sessionId)
        if (latest?.role == MessageRole.ASSISTANT) {
            messageDao.deleteById(latest.id)
            refreshPreview(session)
        }
        val hasUser = messageDao.latestUser(sessionId) != null
        require(hasUser) { "没有可以重新生成的消息" }
    }

    suspend fun editUserMessage(sessionId: String, messageId: String, newContent: String): Result<Unit> = runCatching {
        val content = newContent.trim()
        require(content.isNotBlank()) { "消息不能为空" }
        require(content.length <= 32_000) { "消息过长" }
        val message = messageDao.getById(messageId) ?: error("消息不存在")
        require(message.role == MessageRole.USER && message.sessionId == sessionId) { "只能编辑用户消息" }
        val now = System.currentTimeMillis()
        val session = sessionDao.getById(sessionId) ?: error("会话不存在")
        database.withTransaction {
            messageDao.updateContent(messageId, content, now)
            messageDao.deleteAfter(sessionId, message.createdAt)
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
        messageDao.deleteById(messageId)
        val session = sessionDao.getById(message.sessionId) ?: return@runCatching
        refreshPreview(session)
    }

    suspend fun clearContext(sessionId: String): Result<Unit> = runCatching {
        val session = sessionDao.getById(sessionId) ?: error("会话不存在")
        val now = System.currentTimeMillis()
        database.withTransaction {
            messageDao.deleteBySession(sessionId)
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
    }

    suspend fun renameSession(sessionId: String, title: String): Result<Unit> = runCatching {
        val value = title.trim()
        require(value.isNotBlank()) { "标题不能为空" }
        sessionDao.rename(sessionId, value.take(120), System.currentTimeMillis())
    }

    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        sessionDao.setPinned(sessionId, pinned, System.currentTimeMillis())
    }

    suspend fun markRead(sessionId: String) {
        sessionDao.markRead(sessionId)
    }

    fun setOpenSession(id: String?) {
        openSessionTracker.openSessionId = id
    }

    fun isSessionOpen(sessionId: String): Boolean = openSessionTracker.openSessionId == sessionId

    suspend fun deleteSession(sessionId: String) {
        database.withTransaction {
            messageDao.deleteBySession(sessionId)
            sessionDao.deleteById(sessionId)
        }
    }

    suspend fun bindSessionConfig(sessionId: String, configId: String): Result<ChatSessionEntity> = runCatching {
        val session = sessionDao.getById(sessionId) ?: error("会话不存在")
        val config = apiConfigDao.getById(configId) ?: error("API 配置不存在")
        val persona = personaDao.getById(session.personaId) ?: error("人格不存在")
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
        sessionDao.getById(sessionId) ?: error("会话不存在")
    }

    suspend fun search(query: String): SearchResults {
        val term = query.trim()
        if (term.isBlank()) return SearchResults()
        val lower = term.lowercase()
        val personas = personaDao.getAll().associateBy { it.id }
        val sessions = sessionDao.getAll()
            .filter { session ->
                val persona = personas[session.personaId]
                session.title.lowercase().contains(lower) ||
                    (persona?.name?.lowercase()?.contains(lower) == true) ||
                    (session.lastMessagePreview?.lowercase()?.contains(lower) == true)
            }
            .map { session ->
                val persona = personas[session.personaId]
                SessionSearchHit(
                    sessionId = session.id,
                    title = session.title,
                    personaName = persona?.name ?: "未知人格",
                    personaAvatarUri = persona?.avatarUri,
                    personaAvatarSeed = persona?.avatarSeed ?: 0,
                    preview = session.lastMessagePreview,
                    lastMessageAt = session.lastMessageAt
                )
            }
        val messageRows: List<MessageSearchRow> = messageDao.search(term)
        return SearchResults(
            sessions = sessions,
            messages = messageRows.map {
                MessageSearchHit(
                    sessionId = it.sessionId,
                    sessionTitle = it.sessionTitle,
                    personaName = it.personaName,
                    personaAvatarUri = it.personaAvatarUri,
                    personaAvatarSeed = it.personaAvatarSeed,
                    content = it.content,
                    createdAt = it.createdAt
                )
            }
        )
    }

    suspend fun exportSession(sessionId: String): Result<ExportFile> = runCatching {
        val session = sessionDao.getById(sessionId) ?: error("会话不存在")
        val persona = personaDao.getById(session.personaId) ?: error("人格不存在")
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

    suspend fun exportAllChats(): Result<ExportFile> = runCatching {
        val personas = personaDao.getAll().associateBy { it.id }
        val configs = apiConfigDao.getAll().associateBy { it.id }
        val sessions = sessionDao.getAll()
        val exported = sessions.map { session ->
            val persona = personas[session.personaId]
            ExportSession(
                sessionId = session.id,
                title = session.title,
                personaName = persona?.name ?: "未知人格",
                personaCategory = persona?.category ?: "",
                personaVersion = persona?.version ?: 0,
                apiConfigName = session.apiConfigId?.let { configs[it]?.name },
                model = session.model,
                createdAt = session.createdAt,
                messages = messageDao.getBySession(session.id).map {
                    ExportMessage(it.role.name.lowercase(), it.content, it.createdAt, it.status.name)
                }
            )
        }
        ExportFile(
            fileName = "myfriend-all-chats-${System.currentTimeMillis()}.json",
            content = json.encodeToString(
                ExportBundle.serializer(),
                ExportBundle(exportedAt = System.currentTimeMillis(), sessions = exported)
            )
        )
    }

    suspend fun clearAllChatData() {
        database.withTransaction {
            messageDao.deleteAll()
            sessionDao.deleteAll()
            memoryDao.deleteAll()
            runCatching { memoryTermDao.deleteOrphans() }
        }
    }

    suspend fun wipeAllData() {
        database.withTransaction {
            messageDao.deleteAll()
            sessionDao.deleteAll()
            memoryDao.deleteAll()
            runCatching { memoryTermDao.deleteOrphans() }
            revisionDao.deleteByPersonaAll()
            personaDao.deleteAll()
            apiConfigDao.deleteAllApiConfigs()
        }
        openSessionTracker.openSessionId = null
    }

    /**
     * 睡眠判定：把人格的作息、免打扰、唤醒阈值和「最近 30 分钟几条消息」交给纯逻辑。
     *
     * 最近消息数包含刚刚发来的这一条——所以「第 N 条叫醒」的语义是直观的，
     * 不需要额外建表记「叫了几次」，30 分钟窗口自己会滚动清零。
     */
    private suspend fun evaluateSleep(
        persona: PersonaEntity,
        sessionId: String,
        now: Long
    ): SleepPolicy.Decision {
        if (!persona.sleepEnabled) return SleepPolicy.Decision()
        val minute = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .let { it.hour * 60 + it.minute }
        val recent = runCatching {
            messageDao.countUserMessagesSince(sessionId, now - SleepPolicy.WAKE_WINDOW_MS)
        }.getOrDefault(0)
        return SleepPolicy.evaluate(
            sleepEnabled = true,
            sleepMinutes = persona.quietSleepMinutes,
            wakeMinutes = persona.quietWakeMinutes,
            minuteOfDay = minute,
            dndOnSleep = persona.dndOnSleep,
            recentUserMessages = recent,
            wakeThreshold = persona.wakeThreshold
        )
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
        // 只带着最近几条消息里的图片，避免上下文被 base64 撑爆（先过滤再解码，别为裁掉的消息解一遍）。
        val recentImageOwners = visible.filter { it.role == MessageRole.USER && !it.attachmentsJson.isNullOrBlank() }
            .takeLast(MAX_IMAGE_MESSAGES_IN_CONTEXT)
            .map { it.id }
            .toSet()
        val history = visible.filter {
            (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) &&
                (it.content.isNotBlank() || !it.attachmentsJson.isNullOrBlank()) &&
                it.status != MessageStatus.ERROR
        }.map { message ->
            val outgoing = message.role == MessageRole.USER
            val images = if (outgoing && message.id in recentImageOwners) {
                AttachmentsCodec.decode(message.attachmentsJson)
                    .take(MAX_IMAGES_PER_MESSAGE)
                    .mapNotNull { MediaStorage.toDataUrl(it) }
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
        val system = buildString {
            append(personaCardRepository.systemText(persona))
            if (memories.isNotEmpty()) append(memoryRepository.formatForPrompt(memories))
            append(memoryOfEarlier)
            append(moodLine)
            append(moodPace)
            append(presenceLine)
            append("\n\n---\n")
            append(ProactiveProtocol.CAPABILITY_PROMPT)
            append("\n\n")
            append(ToolCatalog.capabilityPrompt(webEnabled = config.webToolsEnabled))
            append(moodNudge)
            // 最后一节：用对方的语言回答。脚手架保持中文，这一节跟着 App 语言走。
            append(
                LanguageDirective.section(
                    appContext.getString(R.string.prompt_language_directive),
                    LanguageDirective.languageName()
                )
            )
        }
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
        val detail = presenceRepository.current(personaId).detail.trim()
        if (detail.isBlank()) "" else "\n\n## 你此刻的状态\n$detail"
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
            latest?.content?.replace('\n', ' ')?.trim()?.take(100)?.ifBlank { if (latest?.status == MessageStatus.ERROR) "回复失败" else null },
            latest?.createdAt,
            now
        )
    }

    private suspend fun nextTimestamp(sessionId: String): Long {
        val latest = messageDao.latestInSession(sessionId)
        val now = System.currentTimeMillis()
        return if (latest != null && now <= latest.createdAt) latest.createdAt + 1 else now
    }

    private fun autoTitle(content: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 26) flat else flat.take(26) + "…"
    }

    private fun sanitizeFile(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").take(30).ifBlank { "chat" }

    private companion object {
        /** 一次回复里最多连着用几轮工具，用完就必须说话。 */
        const val MAX_TOOL_ROUNDS = 3
        const val CONTEXT_CHAR_BUDGET = 28_000

        /**
         * 装配上下文时最多从库里读多少条消息。
         *
         * 不是「只记得这么多」——更早的部分由 [HistoryCompressor] 收成一段摘要放进 system；
         * 这里只是别让一次聊天把整段历史读进内存（几百条够覆盖两万字预算了）。
         */
        const val CONTEXT_RECENT_LIMIT = 200
        const val MAX_IMAGES_PER_MESSAGE = 4
        const val MAX_IMAGE_MESSAGES_IN_CONTEXT = 3
    }
}
