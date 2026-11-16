package com.yap.app.data.remote

/**
 * 纯 JVM 测试用的 [ServerLocalStore] 内存实现（游标、待发队列、id 映射、bootstrap 标记）。
 *
 * 抽成独立文件而不是在各测试里各写一份：bootstrap 的幂等性测试与写路径测试都依赖
 * 「映射是否被写下来」，两处用同一套语义才不会出现「测试通过但生产行为不同」。
 */
class TestServerLocalStore : ServerLocalStore {

    var currentCursor: Long = 0L
        private set
    var currentStateCursor: Long = 0L
        private set

    val pending = mutableListOf<PendingServerMessage>()
    val doneBootstraps = mutableSetOf<String>()

    private val serverToLocalSession = mutableMapOf<String, String>()
    private val localToServerSession = mutableMapOf<String, String>()
    private val serverToLocalPersona = mutableMapOf<String, String>()
    private val localToServerPersona = mutableMapOf<String, String>()
    private val serverToLocalMemory = mutableMapOf<String, String>()
    private val localToServerMemory = mutableMapOf<String, String>()

    override suspend fun cursor(): Long = currentCursor

    override suspend fun setCursor(cursor: Long) {
        if (cursor > currentCursor) currentCursor = cursor
    }

    override suspend fun stateCursor(): Long = currentStateCursor

    override suspend fun setStateCursor(cursor: Long) {
        if (cursor > currentStateCursor) currentStateCursor = cursor
    }

    override suspend fun pending(): List<PendingServerMessage> = pending.toList()

    override suspend fun enqueuePending(message: PendingServerMessage): Boolean {
        if (pending.any { it.clientMessageId == message.clientMessageId }) return false
        pending += message
        return true
    }

    override suspend fun removePending(clientMessageId: String) {
        pending.removeAll { it.clientMessageId == clientMessageId }
    }

    override suspend fun markPendingAttempt(clientMessageId: String) {
        pending.replaceAll {
            if (it.clientMessageId == clientMessageId) it.copy(attempts = it.attempts + 1) else it
        }
    }

    override suspend fun localSessionId(serverSessionId: String): String? =
        serverToLocalSession[serverSessionId]

    override suspend fun serverSessionId(localSessionId: String): String? =
        localToServerSession[localSessionId]

    override suspend fun setSessionMapping(serverSessionId: String, localSessionId: String) {
        if (serverSessionId.isBlank() || localSessionId.isBlank()) return
        serverToLocalSession[serverSessionId] = localSessionId
        localToServerSession[localSessionId] = serverSessionId
    }

    override suspend fun localPersonaId(serverPersonaId: String): String? =
        serverToLocalPersona[serverPersonaId]

    override suspend fun serverPersonaId(localPersonaId: String): String? =
        localToServerPersona[localPersonaId]

    override suspend fun setPersonaMapping(serverPersonaId: String, localPersonaId: String) {
        if (serverPersonaId.isBlank() || localPersonaId.isBlank()) return
        serverToLocalPersona[serverPersonaId] = localPersonaId
        localToServerPersona[localPersonaId] = serverPersonaId
    }

    override suspend fun localMemoryId(serverMemoryId: String): String? =
        serverToLocalMemory[serverMemoryId]

    override suspend fun serverMemoryId(localMemoryId: String): String? =
        localToServerMemory[localMemoryId]

    override suspend fun setMemoryMapping(serverMemoryId: String, localMemoryId: String) {
        if (serverMemoryId.isBlank() || localMemoryId.isBlank()) return
        serverToLocalMemory[serverMemoryId] = localMemoryId
        localToServerMemory[localMemoryId] = serverMemoryId
    }

    override suspend fun bootstrapDone(baseUrl: String): Boolean =
        baseUrl.trim().trimEnd('/') in doneBootstraps

    override suspend fun markBootstrapDone(baseUrl: String) {
        val key = baseUrl.trim().trimEnd('/')
        if (key.isNotEmpty()) doneBootstraps += key
    }

    override suspend fun resetForNewServer() {
        currentCursor = 0
        currentStateCursor = 0
        pending.clear()
        // 与生产实现一致：只清会话映射，人格/记忆映射跨服务端保留。
        serverToLocalSession.clear()
        localToServerSession.clear()
    }
}
