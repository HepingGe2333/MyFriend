package com.yap.app.data.remote

/**
 * 服务端状态 → UI 可直接渲染的最小模型（P1a 的映射器）。
 *
 * 为什么要有这一层：传输层（[ServerApi]）的 DTO 是**线上契约的形状**（`role` 是字符串、
 * 会话与消息是两张平铺的表、`blockIndex` 可能为 -1），而 P1b 的 `ChatViewModel` 需要的是
 * 「会话列表」与「当前聊天页的一屏」。映射放在这里，三个好处：
 *
 * 1. 纯 Kotlin、零 Android 依赖，JVM 单测能逐字段钉住（包括 `hasMoreMessages` 这种
 *    「服务端可加字段」的语义）；
 * 2. P1b 换 UI 时不需要重新理解契约：改的是映射，不是 ViewModel；
 * 3. 本地 Room 路径完全不动：这是新增的旁路，而不是把现有模型掰弯。
 *
 * 映射是**无状态**的：同一份快照永远映射出同样的结果，不做去重、不做缓存、不碰数据库。
 */
object ServerChatSync {

    /** 会话列表里消息预览的最大长度：列表只要一行，不要整段正文。 */
    const val PREVIEW_MAX_CHARS = 80

    /**
     * [StateSnapshot] → 首页（会话列表 + 心情/在场 + 游标）。
     *
     * 排序：`lastMessageAt` 倒序、再按 `updatedAt` 倒序。服务端 `GET /v1/sessions` 已排过，
     * 但 `GET /v1/state` 的会话顺序是存储遍历顺序，不能直接交给 UI。
     */
    fun home(snapshot: StateSnapshot): ServerHomeState {
        val messagesBySession = snapshot.messages.groupBy { it.sessionId }
        val sessions = snapshot.sessions
            .map { session -> session.toSummary(messagesBySession[session.id].orEmpty()) }
            .sortedWith(
                compareByDescending<ServerSessionSummary> { it.lastMessageAt ?: 0L }
                    .thenByDescending { it.updatedAt }
            )
        return ServerHomeState(
            sessions = sessions,
            mood = snapshot.mood,
            presence = snapshot.presence,
            serverTime = snapshot.serverTime,
            serverCursor = snapshot.serverCursor,
            hasMoreMessages = snapshot.hasMoreMessages
        )
    }

    /**
     * [StateSnapshot] → 某个会话的聊天页（当前块）。
     *
     * 首次同步（`since=0`）服务端只下发每个会话的最新一块，所以这里：
     * - `hasMoreNewer = false`（我们拿到的就是最新块）；
     * - `hasMoreOlder`：块编号 > 0 说明还有更早的块；另外快照级 [StateSnapshot.hasMoreMessages]
     *   为 true（服务端因为条数上限截断过）时也保守地标成 true，宁可多给一个「加载更早」的入口。
     *
     * @return 快照里没有这个会话时返回 null（不要用一个空壳页把「会话被删了」伪装成「没有消息」）
     */
    fun chat(snapshot: StateSnapshot, sessionId: String): ServerChatState? {
        val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return null
        val messages = snapshot.messages
            .filter { it.sessionId == sessionId }
            .sortedWith(compareBy({ it.blockIndex }, { it.createdAt }, { it.id }))
            .map { it.toChatMessage() }
        val blockIndex = messages.maxOfOrNull { it.blockIndex }
            ?: session.lastBlockIndex.takeIf { it >= 0 }
        return ServerChatState(
            sessionId = session.id,
            personaId = session.personaId,
            title = session.title,
            blockIndex = blockIndex,
            messages = messages,
            hasMoreOlder = (blockIndex ?: 0) > 0 || snapshot.hasMoreMessages,
            // 快照总是给最新一块，所以「更新的内容」不存在；新消息靠事件/再次同步拿。
            hasMoreNewer = false,
            hasMoreMessages = snapshot.hasMoreMessages,
            unread = session.unread,
            lastMessageAt = session.lastMessageAt
        )
    }

    /**
     * [ServerMessagePage] → 某个会话的聊天页（P1b 的前翻/后翻）。
     *
     * 分页接口自带 `hasMoreOlder` / `hasMoreNewer`，直接采用；[session] 只用来补标题/未读等
     * 列表字段，可以缺省（比如只翻页、没有会话列表的时候）。
     */
    fun chat(page: ServerMessagePage, session: ServerSession? = null): ServerChatState {
        val messages = page.messages
            .sortedWith(compareBy({ it.blockIndex }, { it.createdAt }, { it.id }))
            .map { it.toChatMessage() }
        return ServerChatState(
            sessionId = page.sessionId.ifEmpty { session?.id.orEmpty() },
            personaId = session?.personaId.orEmpty(),
            title = session?.title.orEmpty(),
            blockIndex = page.blockIndex,
            messages = messages,
            hasMoreOlder = page.hasMoreOlder,
            hasMoreNewer = page.hasMoreNewer,
            hasMoreMessages = page.hasMoreOlder,
            unread = session?.unread ?: 0,
            lastMessageAt = session?.lastMessageAt
        )
    }

    // ------------------------------------------------------------------ 内部

    private fun ServerSession.toSummary(messages: List<ServerMessage>): ServerSessionSummary {
        val last = messages.maxWithOrNull(compareBy({ it.blockIndex }, { it.createdAt }, { it.id }))
        return ServerSessionSummary(
            id = id,
            personaId = personaId,
            title = title,
            lastMessageAt = lastMessageAt,
            unread = unread,
            lastBlockIndex = lastBlockIndex,
            messageCount = messageCount,
            updatedAt = updatedAt,
            preview = last?.let { previewOf(it.content) }
        )
    }

    private fun previewOf(content: String): String {
        val singleLine = content.replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= PREVIEW_MAX_CHARS) {
            singleLine
        } else {
            singleLine.take(PREVIEW_MAX_CHARS) + "…"
        }
    }
}

/** 消息角色；未知值单独一档，UI 才能决定「不认识的角色怎么显示」。 */
enum class ServerMessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    UNKNOWN;

    companion object {
        fun from(raw: String): ServerMessageRole = when (raw.trim().uppercase()) {
            "USER" -> USER
            "ASSISTANT" -> ASSISTANT
            "SYSTEM" -> SYSTEM
            else -> UNKNOWN
        }
    }
}

/** UI 侧的一条消息（已把 DTO 的字符串角色归一成枚举）。 */
data class ServerChatMessage(
    val id: String,
    val role: ServerMessageRole,
    val content: String,
    val createdAt: Long,
    val blockIndex: Int,
    val attachments: List<String> = emptyList(),
    val readAt: Long? = null,
    val source: String = "CHAT"
)

/** 首页列表里的一行。 */
data class ServerSessionSummary(
    val id: String,
    val personaId: String,
    val title: String,
    val lastMessageAt: Long?,
    val unread: Int,
    val lastBlockIndex: Int,
    val messageCount: Long,
    val updatedAt: Long,
    /** 快照里该会话最新一条消息的正文预览（单行、最多 [ServerChatSync.PREVIEW_MAX_CHARS] 字）。 */
    val preview: String?
)

/** 首页状态：会话列表 + 当前心情/在场 + 下次同步用的游标。 */
data class ServerHomeState(
    val sessions: List<ServerSessionSummary>,
    val mood: ServerMood?,
    val presence: ServerPresence?,
    val serverTime: Long,
    val serverCursor: Long,
    /** 首次同步是否被截断（还有更早的块要翻）。 */
    val hasMoreMessages: Boolean
)

/** 聊天页状态：当前块的消息 + 两个方向的「还能翻吗」。 */
data class ServerChatState(
    val sessionId: String,
    val personaId: String,
    val title: String,
    /** 当前块的编号；会话还没有消息时为 null。 */
    val blockIndex: Int?,
    val messages: List<ServerChatMessage>,
    val hasMoreOlder: Boolean,
    val hasMoreNewer: Boolean,
    val hasMoreMessages: Boolean,
    val unread: Int,
    val lastMessageAt: Long?
)

/** DTO → UI 消息的转换（保持一个地方，避免两个映射函数各写一遍角色归一）。 */
private fun ServerMessage.toChatMessage(): ServerChatMessage = ServerChatMessage(
    id = id,
    role = ServerMessageRole.from(role),
    content = content,
    createdAt = createdAt,
    blockIndex = blockIndex,
    attachments = attachments,
    readAt = readAt,
    source = source
)
