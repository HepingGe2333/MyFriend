import Foundation

// ---------------------------------------------------------------------------
// 服务端模式下的**聊天链路**（M3，任务书第 3 条）：打开会话按块拉、发送走 SSE、
// 待发队列离线排队 + 恢复补发。
//
// 为什么单独一个类型而不是往 `ChatService` 里加分支：
// - `ChatService` 是**本地模式**的完整生成链路（提示词、工具、记忆召回、落库）。
//   服务端模式下这些全在服务端做，客户端只剩「请求 + 把流画出来 + 缓存落库」；
//   把两套逻辑塞进一个类型，会让每读一行都要先判断「现在哪种模式」。
// - 但它对外**说同一种话**：`sendChecked(...)` 返回 `ChatService.SendOutcome`、
//   流里吐 `ChatEvent`。所以界面层（`ChatViewModel.consume/apply`）一行都不用改 ——
//   这正是「本地模式默认不变 + 服务端模式最小接线」的实现方式。
//
// 幂等与队列：
// - 每条消息在**发出去之前**先写进 `server_outbox`（`clientMessageId` 是主键）；
// - 发送成功（收到 `done`）才出队；失败留在队列里，`flushOutbox()` 在启动/回前台补发；
// - 因为服务端按 `clientMessageId` 去重，补发同一条不会变成两句。
//
// 图片（M5）：服务端已有 `/v1/media` 上传接口。带图消息走 `sendCheckedWithMedia`：
// 先压缩（复用选图时已经压好的 JPEG）→ 上传拿 `/v1/media/<id>` → 再把 mediaId 放进
// `attachments` 发出去。上传失败**不入队**（`rejected`：草稿与图片还给界面）。
// 同步入口 `sendChecked` 仍然只接受纯文字：它没有 await，不能在入队前完成上传。
// ---------------------------------------------------------------------------

public final class ServerChatGateway {

    /// 本地「还没发出去」那一条消息的 id 前缀。
    ///
    /// 服务端回 `accepted` 之后会用真正的 `userMessageId` 替换它，所以这个 id 只在
    /// 「排队中」这一小段时间里存在；重启后由 `recoverPendingMessages()` 按队列重建。
    public static let pendingIdPrefix = "server-pending:"

    private let client: ServerAPIClient
    private let stores: DatabaseStores
    private let outbox: ServerOutboxStore
    /// 本地会话 id ⇄ 服务端会话 id（本地模式先建的会话 id 与服务端那条不同，见该类注释）。
    private let sessions: ServerSessionResolver
    /// 记「已经拉到服务端第几块 / 上面还有没有更早的块」。
    ///
    /// 为什么必须单独记：本地的 `messages.blockIndex` 是**本机**的块（由 `MessageBlockStore`
    /// 维护），和服务端的块号不是一回事；翻页请求只能带服务端的块号。
    private let blockState: ServerSyncStateStore
    private let now: () -> Int64
    private let log: (String) -> Void
    /// 上传图片（生产 = `ServerAPIClient`；测试注入假实现断言调用顺序）。
    private let mediaUploader: (any ServerMediaUploading)?
    /// 「本地附件 → 压缩后字节」（默认复用选图路径已经压好的 JPEG）。
    private let mediaPreparer: any ServerChatMediaPreparing
    private let lock = NSLock()
    /// 正在发送的 clientMessageId（同一条不并发发两次；补发与用户手点撞上也只发一次）。
    private var inFlight: Set<String> = []

    public init(
        client: ServerAPIClient,
        stores: DatabaseStores,
        outbox: ServerOutboxStore? = nil,
        mediaUploader: (any ServerMediaUploading)? = nil,
        mediaPreparer: (any ServerChatMediaPreparing)? = nil,
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.client = client
        self.stores = stores
        self.outbox = outbox ?? ServerOutboxStore(database: stores.database)
        self.sessions = ServerSessionResolver(stores: stores)
        self.blockState = ServerSyncStateStore(database: stores.database)
        // `ServerAPIClient` 本身就是上传实现；显式注入只是测试口子。
        self.mediaUploader = mediaUploader ?? client
        self.mediaPreparer = mediaPreparer ?? LocalChatMediaPreparer()
        self.now = now
        self.log = log
    }

    /// 服务端模式当前可用吗（开关 + 地址）。
    public var isActive: Bool { client.config.isUsable }

    // MARK: - 打开会话：按块拉取

    /// 拉某个会话的**最新一块**并写进缓存（进入聊天页时调一次）。
    ///
    /// 写库之后界面照旧从本地读（`ChatViewModel.reloadMessages`），所以这里不需要
    /// 把结果再递回给界面 —— 返回值只是给调试/测试看的。
    @discardableResult
    public func loadLatestBlock(sessionId: String) async throws -> ServerChatState {
        let page = try await client.messages(sessionId: remoteId(sessionId))
        try cache(page: page, localSessionId: sessionId)
        return ServerChatSync.chatState(from: page)
    }

    /// 往前翻一块（聊天页上滑到底）。
    @discardableResult
    public func loadOlderBlock(sessionId: String, beforeBlock: Int) async throws -> ServerChatState {
        let page = try await client.messages(sessionId: remoteId(sessionId), beforeBlock: beforeBlock)
        try cache(page: page, localSessionId: sessionId)
        return ServerChatSync.chatState(from: page)
    }

    /// 往后翻一块（从历史位置跳回最新）。
    @discardableResult
    public func loadNewerBlock(sessionId: String, afterBlock: Int) async throws -> ServerChatState {
        let page = try await client.messages(sessionId: remoteId(sessionId), afterBlock: afterBlock)
        try cache(page: page, localSessionId: sessionId)
        return ServerChatSync.chatState(from: page)
    }

    /// 把一页消息写进本地缓存（升序写，块边界才自然）。
    ///
    /// 块号不落服务端的值：本地的 `messages.blockIndex` 由 `MessageBlockStore` 维护
    ///（见 `ServerSyncMapping.message` 的注释）。这里只按时间顺序写。
    public func cache(page: ServerMessagePage, localSessionId: String) throws {
        let at = now()
        let personaId = ((try? stores.sessions.byId(localSessionId)) ?? nil)?.personaId ?? ""
        let sorted = page.messages.sorted { lhs, rhs in
            if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
            return lhs.id < rhs.id
        }
        try stores.database.inTransaction {
            for message in sorted {
                let existing = (try? stores.messages.byId(message.id)) ?? nil
                var record = ServerSyncMapping.message(
                    from: message, existing: existing, personaId: personaId, now: at
                )
                record.sessionId = localSessionId
                try stores.messages.upsert(record)
            }
            if let latest = sorted.last, var session = (try? stores.sessions.byId(localSessionId)) ?? nil {
                if let preview = ServerChatSync.previewText(latest) {
                    session.lastMessagePreview = preview
                }
                session.lastMessageAt = max(session.lastMessageAt ?? 0, latest.createdAt)
                try stores.sessions.upsert(session)
            }
            // 记下这一页对应的**服务端**块号与「上面还有没有」，下一次翻页才有依据。
            if let blockIndex = page.blockIndex {
                try? blockState.set(
                    String(blockIndex),
                    for: ServerSyncStateStore.Key.block(localSessionId),
                    at: at
                )
            }
            try? blockState.set(
                page.hasMoreOlder ? "1" : "0",
                for: ServerSyncStateStore.Key.hasOlder(localSessionId),
                at: at
            )
        }
    }

    /// 服务端说这个会话上面还有没有更早的块（界面「加载更早」用）。
    public func hasOlderOnServer(sessionId: String) -> Bool {
        blockState.string(ServerSyncStateStore.Key.hasOlder(sessionId)) == "1"
    }

    /// 按**服务端块号**再往前翻一块；没记过块号时先拉最新块（这一次不往前翻）。
    @discardableResult
    public func loadOlderBlockRememberingPosition(sessionId: String) async throws -> ServerChatState {
        guard let raw = blockState.string(ServerSyncStateStore.Key.block(sessionId)),
              let block = Int(raw) else {
            return try await loadLatestBlock(sessionId: sessionId)
        }
        return try await loadOlderBlock(sessionId: sessionId, beforeBlock: block)
    }

    // MARK: - 写路径：会话 / 已读

    /// 建（或取回）服务端上的会话，并把它的 id 记进本地会话的 `serverSessionId`。
    ///
    /// 「一人格一会话」由服务端保证幂等，所以这里可以直接调，不用先查。
    @discardableResult
    public func ensureSession(personaId: String, title: String? = nil) async throws -> ServerSession {
        let creation = try await client.createSession(personaId: personaId, title: title)
        // 本地可能已经有一条「本机建的」同人格会话（id 不同）：由 resolver 把服务端 id
        // 记到它头上，**不新建第二行**（`chat_sessions.personaId` 上唯一索引，硬插会把本机那条冲掉）。
        try sessions.localSessionId(forServer: creation.session, at: now())
        return creation.session
    }

    /// 标已读（首页/聊天页打开会话时）。
    ///
    /// 失败只记日志：标已读是「顺手做的事」，不该因为它失败就弹错误给用户；
    /// 下一次打开会话或下一次事件轮询会把未读数修正回来。
    public func markRead(sessionId: String, upTo: Int64? = nil) async {
        do {
            let result = try await client.markRead(sessionId: remoteId(sessionId), upTo: upTo)
            if var session = (try? stores.sessions.byId(sessionId)) ?? nil {
                session.unreadCount = max(result.unread, 0)
                try stores.sessions.upsert(session)
            }
        } catch {
            log("标已读失败（忽略）：\(ServerSyncService.describe(error))")
        }
    }

    // MARK: - 发送（SSE）

    /// 发一条消息。语义与 `ChatService.sendChecked` 一致：
    /// - `.rejected`：**什么都没改**（参数不对 / 会话不存在 / 带图片），调用方把草稿还回去；
    /// - `.started(stream)`：已经进队列、也已经在界面上出现；流里会依次吐
    ///   `delta` / `tool*` / `done`，或者以错误结束。
    ///
    /// 为什么「网络失败」也是 `.started`：消息已经落进待发队列（磁盘上），
    /// 用户的字**没有丢**，界面上那条气泡会留着并在联网后自动补发。若这时返回 `.rejected`
    /// 让界面恢复草稿，用户会以为「没发出去」，而实际上队列里已经有一份 —— 重发就是两条。
    public func sendChecked(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment] = []
    ) -> ChatService.SendOutcome {
        let content = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // 同步入口没有 await：带图必须先上传，走 `sendCheckedWithMedia`。
        guard attachments.isEmpty else {
            return .rejected(ApiException(
                statusCode: nil,
                message: "图片还没准备好，请重试"
            ))
        }
        guard !content.isEmpty else {
            return .rejected(ApiException(statusCode: nil, message: String(localized: "消息不能为空")))
        }
        guard content.count <= ChatService.maxMessageLength else {
            return .rejected(ApiException(statusCode: nil, message: String(localized: "消息太长了")))
        }
        guard let session = (try? stores.sessions.byId(sessionId)) ?? nil else {
            return .rejected(ApiException(statusCode: nil, message: String(localized: "会话不存在")))
        }
        return start(session: session, content: content, mediaIds: [])
    }

    /// 发一条**带图片**的消息：压缩 → 上传 → 入队 → 开始流。
    ///
    /// 与 `sendChecked` 的区别只有「先上传」这一步；失败语义一致：
    /// 上传/准备失败 = `rejected`（什么都没落库，界面把草稿和图片留下来）。
    ///
    /// - Parameters:
    ///   - attachments: 已经压好、存在沙盒里的本地附件（`ImageImport` 的产物）。
    ///   - kind: 压缩策略（聊天图 `.chatImage`；头像另走 `uploadMedia`）。
    public func sendCheckedWithMedia(
        sessionId: String,
        text: String,
        attachments: [MessageAttachment],
        kind: MediaKind = .chatImage
    ) async -> ChatService.SendOutcome {
        let content = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !content.isEmpty || !attachments.isEmpty else {
            return .rejected(ApiException(statusCode: nil, message: String(localized: "消息不能为空")))
        }
        guard content.count <= ChatService.maxMessageLength else {
            return .rejected(ApiException(statusCode: nil, message: String(localized: "消息太长了")))
        }
        guard let session = (try? stores.sessions.byId(sessionId)) ?? nil else {
            return .rejected(ApiException(statusCode: nil, message: String(localized: "会话不存在")))
        }
        guard attachments.count <= AttachmentsCodec.maxImagesPerMessage else {
            return .rejected(ApiException(statusCode: nil, message: "一条消息最多带 \(AttachmentsCodec.maxImagesPerMessage) 张图"))
        }
        if attachments.isEmpty {
            return start(session: session, content: content, mediaIds: [])
        }
        guard let uploader = mediaUploader else {
            return .rejected(ApiException(
                statusCode: nil,
                message: String(localized: "服务端模式还不能发图片")
            ))
        }

        // 逐张：准备（压缩）→ 上传。顺序固定，测试用假实现断言「上传的字节已经是压缩产物」。
        var mediaIds: [String] = []
        for attachment in attachments {
            if let existingId = ServerMediaReference.mediaId(from: attachment.uri) {
                mediaIds.append(existingId)
                continue
            }
            guard let prepared = mediaPreparer.prepare(attachment: attachment, kind: kind),
                  !prepared.bytes.isEmpty else {
                return .rejected(ApiException(
                    statusCode: nil,
                    message: "这张图片读不出来，换一张试试"
                ))
            }
            do {
                let media = try await uploader.uploadMedia(
                    bytes: prepared.bytes,
                    mime: prepared.mime,
                    kind: ServerMediaKind(mediaKind: kind).rawValue,
                    personaId: session.personaId
                )
                mediaIds.append(media.id)
            } catch {
                // 上传失败：不入队、不发文字 —— 用户看到的应该是一句「为什么没发出去」。
                log("图片上传失败：\(ServerSyncService.describe(error))")
                return .rejected(error)
            }
        }
        return start(session: session, content: content, mediaIds: mediaIds)
    }

    /// 两条发送入口共用：写待发队列 + 落一条「排队中」的本地消息 + 开流。
    private func start(
        session: ChatSessionEntity,
        content: String,
        mediaIds: [String]
    ) -> ChatService.SendOutcome {
        let at = now()
        let clientMessageId = UUID().uuidString
        let entry = ServerOutboxEntry(
            clientMessageId: clientMessageId,
            sessionId: session.id,
            personaId: session.personaId,
            content: content,
            attachments: mediaIds,
            replyTo: nil,
            createdAt: at
        )
        do {
            try outbox.enqueue(entry)
            try insertPendingMessage(entry, at: at)
        } catch {
            return .rejected(error)
        }
        return .started(stream(for: entry))
    }

    /// 补发队列里的全部待发消息（启动 / 回前台 / 网络恢复时调）。
    ///
    /// 顺序：先入先发（同一会话里顺序就是对话顺序）。一条网络失败就停（服务端多半连不上，
    /// 继续发只会把队列里的每一条都记一次失败）；参数类失败（4xx）跳过这条继续下一条。
    ///
    /// - Returns: 这次成功补发了几条。
    @discardableResult
    public func flushOutbox() async -> Int {
        var sent = 0
        for entry in outbox.all() {
            if Task.isCancelled { return sent }
            do {
                try await drain(entry)
                sent += 1
            } catch let error as ServerAPIError {
                recordFailure(entry: entry, error: error)
                if error.statusCode.map({ $0 >= 400 && $0 < 500 }) == true {
                    // 4xx：这条本身有问题（会话被删、内容被拒），继续下一条。
                    continue
                }
                return sent
            } catch {
                recordFailure(entry: entry, error: error)
                return sent
            }
        }
        return sent
    }

    /// 某个会话还有几条没发出去（界面可以显示「待发送」）。
    public func pendingEntries(sessionId: String) -> [ServerOutboxEntry] {
        outbox.entries(sessionId: sessionId)
    }

    /// 重启后把「队列里有、界面上没有」的消息补回界面。
    ///
    /// 为什么需要：进程被杀时界面上的那条本地气泡已经没了（它只是 UI 状态），
    /// 但队列还在；重建一次让用户看到「这句话还等着发」，而不是以为它凭空消失了。
    @discardableResult
    public func recoverPendingMessages() -> Int {
        var restored = 0
        let at = now()
        for entry in outbox.all() {
            guard ((try? stores.messages.byId(Self.pendingId(entry.clientMessageId))) ?? nil) == nil else {
                continue
            }
            guard ((try? stores.messages.byId(entry.clientMessageId)) ?? nil) == nil else { continue }
            guard (try? insertPendingMessage(entry, at: at)) != nil else { continue }
            restored += 1
        }
        return restored
    }

    // MARK: - 内部：发送一条

    /// 把一条队列里的消息发出去并消费到底（补发路径用；不往外吐事件）。
    private func drain(_ entry: ServerOutboxEntry) async throws {
        markInFlight(entry.clientMessageId)
        defer { clearInFlight(entry.clientMessageId) }
        var accumulated = ""
        for try await event in client.sendMessage(
            sessionId: remoteId(entry.sessionId),
            clientMessageId: entry.clientMessageId,
            content: entry.content,
            attachments: entry.attachments
        ) {
            _ = try map(event: event, entry: entry, accumulated: &accumulated)
        }
    }

    private func stream(for entry: ServerOutboxEntry) -> AsyncThrowingStream<ChatEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task { [weak self] in
                guard let self else {
                    continuation.finish()
                    return
                }
                self.markInFlight(entry.clientMessageId)
                defer { self.clearInFlight(entry.clientMessageId) }
                var accumulated = ""
                do {
                    for try await event in self.client.sendMessage(
                        sessionId: self.remoteId(entry.sessionId),
                        clientMessageId: entry.clientMessageId,
                        content: entry.content,
                        attachments: entry.attachments
                    ) {
                        try Task.checkCancellation()
                        if let chatEvent = try self.map(event: event, entry: entry, accumulated: &accumulated) {
                            continuation.yield(chatEvent)
                        }
                    }
                    continuation.finish()
                } catch is CancellationError {
                    // 用户点了停止：消息已经发出去了（服务端会继续生成并落库），
                    // 队列条目照样出队；这里只结束流。
                    self.finishQueueEntry(entry, keepOnFailure: false, error: nil)
                    continuation.finish(throwing: CancellationError())
                } catch {
                    self.finishQueueEntry(entry, keepOnFailure: true, error: error)
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// 一个 SSE 事件 → 界面事件（同时负责落库）。
    ///
    /// - `accepted`：把本地「排队中」的气泡换成服务端 id 的那一条（折叠成一条消息）；
    /// - `delta`：累加正文，吐**全文**（与 `ChatEvent.delta` 的语义一致，界面直接覆盖）；
    /// - `tool`：吐工具痕迹（`running` → 开始，其它 → 完成）；
    /// - `done`：助手消息落库 + 出队 + `ChatEvent.done`；
    /// - `error`：抛出去（由流的 catch 记录失败并保留队列条目）。
    private func map(
        event: ServerChatEvent,
        entry: ServerOutboxEntry,
        accumulated: inout String
    ) throws -> ChatEvent? {
        switch event {
        case .accepted(let userMessageId, let createdAt):
            try replacePending(with: entry, userMessageId: userMessageId, createdAt: createdAt)
            return nil
        case .delta(let text):
            accumulated += text
            return .delta(accumulated)
        case .tool(let name, let status, let summary):
            let finished = status.lowercased() != "running"
            let item = ToolTraceItem(
                name: name,
                title: ToolProtocol.title(forTool: name),
                subtitle: summary,
                detail: summary ?? "",
                ok: !status.lowercased().contains("error") && !status.lowercased().contains("fail")
            )
            return finished ? .toolFinished(item) : .toolStarted(item)
        case .done(let assistantMessageId, let usage):
            guard let message = try finishAssistantMessage(
                entry: entry,
                assistantMessageId: assistantMessageId,
                content: accumulated
            ) else {
                return nil
            }
            log("服务端回复完成（tokens：\(usage.promptTokens)+\(usage.completionTokens)）")
            return .done(ChatCompletion(message: message))
        case .error(let error):
            throw error
        }
    }

    // MARK: - 内部：落库

    private static func pendingId(_ clientMessageId: String) -> String {
        pendingIdPrefix + clientMessageId
    }

    /// 排队中的那条本地消息（`status = .error` + 一句解释：界面上看得出「还没发出去」，
    /// 而不是假装已经送达）。
    private func insertPendingMessage(_ entry: ServerOutboxEntry, at: Int64) throws {
        let session = (try? stores.sessions.byId(entry.sessionId)) ?? nil
        let message = MessageEntity(
            id: Self.pendingId(entry.clientMessageId),
            sessionId: entry.sessionId,
            personaId: entry.personaId ?? session?.personaId ?? "",
            role: .user,
            content: entry.content,
            createdAt: entry.createdAt,
            updatedAt: entry.createdAt,
            status: .error,
            errorMessage: String(localized: "还没发出去，联网后会自动补发"),
            attachmentsJson: ServerSyncMapping.attachmentsJson(from: entry.attachments)
        )
        try stores.messages.upsert(message)
        if var session {
            session.lastMessagePreview = ServerChatSync.previewText(entry.content)
            session.lastMessageAt = max(session.lastMessageAt ?? 0, entry.createdAt)
            session.updatedAt = max(session.updatedAt, at)
            try stores.sessions.upsert(session)
        }
    }

    /// `accepted`：删掉排队中的占位消息，写入服务端真实的那条用户消息。
    private func replacePending(
        with entry: ServerOutboxEntry,
        userMessageId: String,
        createdAt: Int64
    ) throws {
        try stores.database.inTransaction {
            try? stores.messages.delete(id: Self.pendingId(entry.clientMessageId))
            let session = (try? stores.sessions.byId(entry.sessionId)) ?? nil
            let message = MessageEntity(
                id: userMessageId,
                sessionId: entry.sessionId,
                personaId: entry.personaId ?? session?.personaId ?? "",
                role: .user,
                content: entry.content,
                createdAt: createdAt > 0 ? createdAt : entry.createdAt,
                updatedAt: createdAt > 0 ? createdAt : entry.createdAt,
                status: .complete,
                attachmentsJson: ServerSyncMapping.attachmentsJson(from: entry.attachments)
            )
            try stores.messages.upsert(message)
        }
    }

    /// `done`：助手消息落库并出队。
    private func finishAssistantMessage(
        entry: ServerOutboxEntry,
        assistantMessageId: String,
        content: String
    ) throws -> MessageEntity? {
        guard !assistantMessageId.isEmpty else { return nil }
        let at = now()
        var saved: MessageEntity?
        try stores.database.inTransaction {
            let session = (try? stores.sessions.byId(entry.sessionId)) ?? nil
            let message = MessageEntity(
                id: assistantMessageId,
                sessionId: entry.sessionId,
                personaId: entry.personaId ?? session?.personaId ?? "",
                role: .assistant,
                content: content,
                createdAt: at,
                updatedAt: at,
                status: .complete
            )
            try stores.messages.upsert(message)
            saved = message
            if var session {
                if let preview = ServerChatSync.previewText(content) {
                    session.lastMessagePreview = preview
                }
                session.lastMessageAt = at
                session.updatedAt = at
                try stores.sessions.upsert(session)
            }
        }
        try outbox.remove(clientMessageId: entry.clientMessageId)
        return saved
    }

    // MARK: - 内部：id 换算

    /// 本地会话 id → 请求服务端时该用的 id（没有 `serverSessionId` 时就是它自己）。
    private func remoteId(_ localSessionId: String) -> String {
        sessions.remoteSessionId(forLocal: localSessionId)
    }

    // MARK: - 内部：失败处理

    private func recordFailure(entry: ServerOutboxEntry, error: Error) {
        let message = ServerSyncService.describe(error)
        _ = try? outbox.recordFailure(clientMessageId: entry.clientMessageId, error: message)
        log("待发消息补发失败（\(entry.clientMessageId)）：\(message)")
        // 占位气泡上的解释换成真实原因（用户能看到「为什么没发出去」）。
        if var pending = (try? stores.messages.byId(Self.pendingId(entry.clientMessageId))) ?? nil {
            pending.errorMessage = message
            try? stores.messages.upsert(pending)
        }
    }

    private func finishQueueEntry(_ entry: ServerOutboxEntry, keepOnFailure: Bool, error: Error?) {
        if keepOnFailure {
            recordFailure(entry: entry, error: error ?? ServerAPIError(
                code: ServerAPIError.Code.networkError, message: "发送失败"
            ))
        } else {
            try? outbox.remove(clientMessageId: entry.clientMessageId)
        }
    }

    private func markInFlight(_ clientMessageId: String) {
        lock.lock()
        inFlight.insert(clientMessageId)
        lock.unlock()
    }

    private func clearInFlight(_ clientMessageId: String) {
        lock.lock()
        inFlight.remove(clientMessageId)
        lock.unlock()
    }
}

// MARK: - 小工具

extension ServerAPIError {
    /// HTTP 状态码（SSE 的 `error` 事件与纯网络错误都没有）。
    ///
    /// 补发队列用它区分「这条本身有问题（4xx，跳过）」与「服务端连不上（停手）」：
    /// 4xx 重试一万次也不会成功，而网络错误重试到「下一条也发不出去」就是白费。
    public var statusCode: Int? { status }
}
