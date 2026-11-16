package com.myfriend.app.data.remote

import java.util.UUID

/**
 * 服务端聊天/事件所需的远程调用集合。
 *
 * 为什么要这一层而不是让 [ServerChatGateway] 直接依赖 [ServerApi]：
 * 纯 JVM 单测需要假的「SSE 事件序列 + 分页 + 事件长轮询」；用 `ServerApi` 就必须起 socket。
 * 传输层的行为（分片、取消、超时）已经由 `ServerApiTest` 钉住，这里只测**编排规则**。
 */
interface ServerRemote {
    suspend fun sendMessage(
        sessionId: String,
        clientMessageId: String,
        content: String,
        attachments: List<String> = emptyList(),
        replyTo: String? = null,
        onEvent: (ServerChatEvent) -> Unit
    )

    suspend fun messages(
        sessionId: String,
        beforeBlock: Int? = null,
        afterBlock: Int? = null
    ): ServerMessagePage

    suspend fun events(since: Long, timeoutSeconds: Long? = null): ServerEvents

    /** 新建（或取回）会话：服务端模式下会话 id 由服务端分配，客户端不能自己造。 */
    suspend fun createSession(personaId: String, title: String? = null): ServerSessionCreated

    /** 标已读回执（服务端会话 id）。 */
    suspend fun markRead(sessionId: String, upTo: Long? = null): ServerReadReceipt
}

/**
 * 「此刻可用的远程客户端」。
 *
 * 用接口而不是 `() -> ServerRemote?`：Dagger 对函数类型（`Function0<out T>`）的键匹配不稳定，
 * 显式接口也把「每次调用现取」的语义写清楚——设置页换地址后，下一次调用就会拿到新客户端。
 */
interface ServerRemoteProvider {
    fun remote(): ServerRemote?
}

/** 生产实现：向 [ServerModeRepository] 要当前客户端，并包一层 [ServerApiRemote]。 */
class ModeServerRemoteProvider(
    private val mode: ServerModeRepository
) : ServerRemoteProvider {
    override fun remote(): ServerRemote? = mode.api()?.let(::ServerApiRemote)
}

/** [ServerApi] → [ServerRemote] 适配器（传输层不反向依赖编排层）。 */
class ServerApiRemote(private val api: ServerApi) : ServerRemote {
    override suspend fun sendMessage(
        sessionId: String,
        clientMessageId: String,
        content: String,
        attachments: List<String>,
        replyTo: String?,
        onEvent: (ServerChatEvent) -> Unit
    ) = api.sendMessage(sessionId, clientMessageId, content, attachments, replyTo, onEvent)

    override suspend fun messages(
        sessionId: String,
        beforeBlock: Int?,
        afterBlock: Int?
    ): ServerMessagePage = api.messages(sessionId, beforeBlock, afterBlock)

    override suspend fun events(since: Long, timeoutSeconds: Long?): ServerEvents =
        api.events(since, timeoutSeconds ?: DEFAULT_POLL_SECONDS)

    override suspend fun createSession(personaId: String, title: String?): ServerSessionCreated =
        api.createSession(personaId, title)

    override suspend fun markRead(sessionId: String, upTo: Long?): ServerReadReceipt =
        api.markRead(sessionId, upTo)

    private companion object {
        /**
         * 一次长轮询最多等多久。
         *
         * 用 20s 而不是服务端上限 25s：留 10s 给普通请求的 30s 读超时余量，
         * 避免「服务端还在等、客户端先判超时」。
         */
        const val DEFAULT_POLL_SECONDS = 20L
    }
}

/** 发送过程中给 UI 的进度（与 SSE 事件一一对应，只是多了「落库完成」的语义）。 */
sealed class ServerSendEvent {
    data class Accepted(val userMessageId: String) : ServerSendEvent()
    data class Delta(val text: String) : ServerSendEvent()
    data class Tool(val name: String, val status: String, val summary: String?) : ServerSendEvent()
    data class Done(val assistantMessageId: String, val usage: ServerUsage) : ServerSendEvent()
    data class Error(val code: String, val message: String) : ServerSendEvent()
}

/**
 * 一次服务端发送的结果。
 *
 * [accepted] 区分「用户消息已到服务端」与「连接都没通」：前者意味着即使回复流断了，
 * 服务端也会继续生成并落库，UI 不该把用户刚发的话退回来；后者才需要保留草稿并提示重试。
 */
data class ServerSendResult(
    val accepted: Boolean,
    val done: Boolean,
    val error: Throwable? = null,
    val clientMessageId: String = ""
) {
    val success: Boolean get() = done && error == null
}

/** SSE 之后要落的库：分页消息 + token 用量（抽接口便于纯 JVM 测试）。 */
interface ServerChatSink {
    suspend fun applyPage(page: ServerMessagePage): ServerSyncApplyResult
    suspend fun recordUsage(sessionId: String, usage: ServerUsage, at: Long = System.currentTimeMillis())
}

/**
 * 服务端聊天编排（M3）：
 *
 * ```
 * 入队(clientMessageId) → POST SSE → accepted（落用户消息）→ delta/tool（只渲染）
 *   → done（落 assistant 消息与用量）→ 出队
 * ```
 *
 * 几条规则直接决定用户看到什么：
 *
 * 1. **先入队再发**：进程被杀/断网时，这条消息还在待发队列里，下次前台用同一个
 *    `clientMessageId` 补发；服务端幂等，不会出现两条一样的话；
 * 2. **accepted 才出队**：只有服务端确认落库，用户的话才算「发出去了」；连接没通时保留队列；
 * 3. **accepted 之后失败也不退草稿**：回复流断了但用户消息已在服务端，退草稿会让人以为没发出去；
 * 4. **消息内容以服务端为准**：accepted/done 之后各拉一次当前块写进 Room，而不是把 SSE
 *    增量拼成一条本地消息——服务端会分配 id/块编号/用量，客户端自己拼迟早对不上。
 */
class ServerChatGateway(
    private val remoteProvider: ServerRemoteProvider,
    private val localStore: ServerLocalStore,
    private val sink: ServerChatSink,
    private val newClientMessageId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /** 当前是否具备发送条件（设置了服务端且地址合法）。 */
    fun isAvailable(): Boolean = remoteProvider.remote() != null

    /**
     * 发一条用户消息；返回时用户消息与（正常情况下）assistant 消息都已尝试落库。
     *
     * @param onEvent 进度回调（可能在 OkHttp 回调线程；调用方自己切到主线程）
     */
    suspend fun send(
        sessionId: String,
        content: String,
        attachments: List<String> = emptyList(),
        replyTo: String? = null,
        onEvent: (ServerSendEvent) -> Unit = {}
    ): ServerSendResult {
        val remote = remoteProvider.remote()
            ?: return ServerSendResult(
                accepted = false,
                done = false,
                error = ServerApiException("server mode is not available", code = "server_unavailable")
            )
        val clientMessageId = newClientMessageId()
        localStore.enqueuePending(
            PendingServerMessage(
                clientMessageId = clientMessageId,
                sessionId = sessionId,
                content = content,
                attachments = attachments,
                replyTo = replyTo,
                createdAt = now()
            )
        )
        return sendWithId(
            remote = remote,
            localSessionId = sessionId,
            remoteSessionId = remoteSessionId(sessionId),
            clientMessageId = clientMessageId,
            content = content,
            attachments = attachments,
            replyTo = replyTo,
            onEvent = onEvent
        )
    }

    /**
     * 补发离线队列（恢复网络/回到前台时调用）。
     *
     * 按入队顺序一条条发；第一条失败就停——后面的消息依赖前面的上下文，插队补发会让服务端
     * 看到「后发的先到」。同一条失败的消息保留在队列里，下次再试。
     *
     * @return 这次成功（accepted）发出的条数
     */
    suspend fun resendPending(onEvent: (ServerSendEvent) -> Unit = {}): Int {
        val remote = remoteProvider.remote() ?: return 0
        var sent = 0
        localStore.pending().sortedBy { it.createdAt }.forEach { pending ->
            val result = sendWithId(
                remote = remote,
                localSessionId = pending.sessionId,
                remoteSessionId = remoteSessionId(pending.sessionId),
                clientMessageId = pending.clientMessageId,
                content = pending.content,
                attachments = pending.attachments,
                replyTo = pending.replyTo,
                onEvent = onEvent
            )
            if (!result.accepted) return sent
            sent++
        }
        return sent
    }

    /**
     * 新建/取回会话（`POST /v1/sessions`）。
     *
     * 返回值直接是服务端 DTO：落缓存由调用方（[ChatRepository]）负责——它已经有人格/会话 DAO，
     * 不需要再为「一次建会话」加一个新的 sink 接口。
     */
    suspend fun createSession(personaId: String, title: String? = null): Result<ServerSessionCreated> =
        runCatching {
            val remote = remoteProvider.remote()
                ?: throw ServerApiException("server mode is not available", code = "server_unavailable")
            remote.createSession(personaId, title)
        }

    /** 打开会话：先拿最新一块写进 Room（首屏不需要等下一次同步）。 */
    suspend fun openSession(sessionId: String): Result<ServerSyncApplyResult> = runCatching {
        val remote = remoteProvider.remote()
            ?: throw ServerApiException("server mode is not available", code = "server_unavailable")
        sink.applyPage(remote.messages(remoteSessionId(sessionId)))
    }

    /**
     * 前翻/后翻一块；调用方随后按 Room 里的块编号驱动现有窗口即可。
     */
    suspend fun loadPage(
        sessionId: String,
        beforeBlock: Int? = null,
        afterBlock: Int? = null
    ): Result<ServerSyncApplyResult> = runCatching {
        if (beforeBlock != null && afterBlock != null) {
            throw ServerApiException("beforeBlock and afterBlock are mutually exclusive", code = "invalid_argument")
        }
        val remote = remoteProvider.remote()
            ?: throw ServerApiException("server mode is not available", code = "server_unavailable")
        sink.applyPage(remote.messages(remoteSessionId(sessionId), beforeBlock, afterBlock))
    }

    /**
     * 把本地缓存会话 id 换成服务端会话 id（没有映射时按相同 id 处理：服务端模式新建的缓存
     * 就是服务端 id）。
     */
    private suspend fun remoteSessionId(localSessionId: String): String =
        localStore.serverSessionId(localSessionId) ?: localSessionId

    /** 记住「本地缓存会话 ↔ 服务端会话」；[ChatRepository.createServerSession] 落缓存后调用。 */
    suspend fun registerSessionMapping(localSessionId: String, serverSessionId: String) {
        localStore.setSessionMapping(serverSessionId, localSessionId)
    }

    /** 标已读（服务端模式）：本地 id → 服务端 id，失败返回 failure 由调用方只记日志。 */
    suspend fun markRead(localSessionId: String): Result<Unit> = runCatching {
        val remote = remoteProvider.remote()
            ?: throw ServerApiException("server mode is not available", code = "server_unavailable")
        remote.markRead(remoteSessionId(localSessionId))
        Unit
    }

    /**
     * 用固定幂等键发一次。
     *
     * 失败路径的分寸：
     * - accepted 之前失败 → 记一次 attempt，**保留队列**，返回 accepted=false（UI 退草稿）；
     * - accepted 之后失败 → 用户消息已落服务端，出队并返回 accepted=true（UI 不退草稿，
     *   只提示「回复没接上，稍后同步」）。
     */
    private suspend fun sendWithId(
        remote: ServerRemote,
        localSessionId: String,
        remoteSessionId: String,
        clientMessageId: String,
        content: String,
        attachments: List<String>,
        replyTo: String?,
        onEvent: (ServerSendEvent) -> Unit
    ): ServerSendResult {
        var accepted = false
        var done = false
        var streamError: Throwable? = null
        // SSE 回调在 OkHttp 线程、且不是 suspend；用量先攒着，流结束后在协程里落库。
        var usage: ServerUsage? = null
        try {
            remote.sendMessage(remoteSessionId, clientMessageId, content, attachments, replyTo) { event ->
                when (event) {
                    is ServerChatEvent.Accepted -> {
                        accepted = true
                        onEvent(ServerSendEvent.Accepted(event.userMessageId))
                    }

                    is ServerChatEvent.Delta -> onEvent(ServerSendEvent.Delta(event.text))

                    is ServerChatEvent.Tool -> onEvent(
                        ServerSendEvent.Tool(event.name, event.status, event.summary)
                    )

                    is ServerChatEvent.Done -> {
                        done = true
                        onEvent(ServerSendEvent.Done(event.assistantMessageId, event.usage))
                        // done 的用量属于服务端那次生成；流结束后落本机记账（只记 token，不记钱）。
                        usage = event.usage
                    }

                    is ServerChatEvent.Error -> {
                        streamError = ServerApiException(event.message, code = event.code)
                        onEvent(ServerSendEvent.Error(event.code, event.message))
                    }
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // 用户主动停止：服务端会继续生成并落库（契约 §3.3）。若 accepted 过就出队，
            // 否则保留待发，让「停止」不至于把用户的话弄丢。
            if (accepted) localStore.removePending(clientMessageId) else localStore.markPendingAttempt(clientMessageId)
            throw cancel
        } catch (error: Throwable) {
            streamError = error
        }

        if (accepted) {
            localStore.removePending(clientMessageId)
        } else {
            localStore.markPendingAttempt(clientMessageId)
            return ServerSendResult(
                accepted = false,
                done = false,
                error = streamError ?: ServerApiException("message was not accepted", code = "io_error"),
                clientMessageId = clientMessageId
            )
        }

        // accepted/done 之后各拉一次当前块：用户消息与 assistant 消息由服务端分配 id/块，
        // 客户端不自己拼实体。拉取失败不影响「消息已发出去」这一事实（下次同步补）。
        runCatching { sink.applyPage(remote.messages(remoteSessionId)) }
        usage?.let { tokens -> runCatching { sink.recordUsage(localSessionId, tokens, now()) } }

        return ServerSendResult(
            accepted = true,
            done = done,
            error = streamError,
            clientMessageId = clientMessageId
        )
    }
}
