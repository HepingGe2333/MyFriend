import Foundation

// ---------------------------------------------------------------------------
// 批量历史消息导入（`POST /v1/sessions/{id}/messages/import`，0.12.1 契约）。
//
// 为什么需要它：切到一台「没有你们历史」的服务端时，本机那几千条聊天必须整段搬过去。
// 逐条 `POST /messages` 会真的触发模型生成（还会一条条 SSE），那是聊天不是搬家；
// 这个接口只做「把已有消息按原 id / 原时间写进服务端」，不生成、不发事件、不造未读。
//
// 契约要点（与 agentd 0.12.1 逐字对齐）：
// - body 是**对象** `{"messages":[…]}`，不是裸数组；每批 ≤200 条（201 → 400 整批不写）；
// - `id` **原样保留**（服务端不生成新 id），字符集 `[A-Za-z0-9_-]{1,128}`；
//   本地 id 不是这个形状时由 `normalizedImportId(_:)` 确定地转写，重推仍然是同一个 id；
// - 幂等：按 id 去重，已存在的直接 `skipped++` 并返回 200，**不覆盖、不改 readAt**；
// - 一批是原子单位：任何一条校验失败（坏 role / 附件不存在 / 缺 createdAt）整批 400；
// - `attachments` 里的媒体必须已经在服务端存在；本地 `file://` 图无法在搬家时上传，
//   调用方应只带服务端媒体引用（见 `ServerBootstrapService` 的取舍）。
//
// 这一层只拼请求/解响应，不做分批策略之外的任何决策（谁该被推由 bootstrap 决定）。
// ---------------------------------------------------------------------------

/// 一条要导入服务端的历史消息。
///
/// 字段名与契约逐字一致；可空字段**不写键**（服务端缺省 = 未读 / 无来源 / 无引用）。
public struct ServerImportMessage: Encodable, Equatable, Sendable {
    public var id: String
    public var role: String
    public var content: String
    public var createdAt: Int64
    public var readAt: Int64?
    /// 服务端媒体（`/v1/media/<id>` 或裸 mediaId 都收）。
    public var attachments: [String]
    public var source: String
    public var clientMessageId: String?
    public var replyTo: String?

    public init(
        id: String,
        role: String,
        content: String = "",
        createdAt: Int64 = 0,
        readAt: Int64? = nil,
        attachments: [String] = [],
        source: String = "IMPORT",
        clientMessageId: String? = nil,
        replyTo: String? = nil
    ) {
        self.id = id
        self.role = role
        self.content = content
        self.createdAt = createdAt
        self.readAt = readAt
        self.attachments = attachments
        self.source = source
        self.clientMessageId = clientMessageId
        self.replyTo = replyTo
    }

    private enum CodingKeys: String, CodingKey {
        case id, role, content, createdAt, readAt, attachments, source, clientMessageId, replyTo
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(role, forKey: .role)
        try container.encode(content, forKey: .content)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encodeIfPresent(readAt, forKey: .readAt)
        // 空数组也照发：服务端缺省同样是空，显式给出来读报文更清楚。
        try container.encode(attachments, forKey: .attachments)
        try container.encode(source, forKey: .source)
        try container.encodeIfPresent(clientMessageId, forKey: .clientMessageId)
        try container.encodeIfPresent(replyTo, forKey: .replyTo)
    }

    /// 估算这一条在 body 里占多少字节（分批用；不精确但足够保守）。
    var estimatedBytes: Int {
        content.utf8.count + id.utf8.count + role.utf8.count + source.utf8.count
            + attachments.reduce(0) { $0 + $1.utf8.count + 8 }
            + (clientMessageId?.utf8.count ?? 0) + (replyTo?.utf8.count ?? 0)
            + 96
    }
}

/// `POST /v1/sessions/{id}/messages/import` 的请求体（对象，不是裸数组）。
struct ServerImportRequest: Encodable {
    var messages: [ServerImportMessage]
}

/// 导入响应：`{"sessionId":"s1","imported":2,"skipped":1,"blockRange":{…},"messageCount":2}`。
///
/// 宽容解码：`imported` 既可能是条数，也可能是 id 数组（服务端实现细节不同）；
/// 缺字段一律当 0 —— 这个响应只是**对账用的**，不该因为它少一个键就把一次成功的导入
/// 判成失败（那会让 bootstrap 反复重推同一批）。
public struct ServerImportResult: Decodable, Equatable, Sendable {
    public var sessionId: String
    public var imported: Int
    public var skipped: Int
    public var firstBlock: Int?
    public var lastBlock: Int?
    public var messageCount: Int64?

    public init(
        sessionId: String = "",
        imported: Int = 0,
        skipped: Int = 0,
        firstBlock: Int? = nil,
        lastBlock: Int? = nil,
        messageCount: Int64? = nil
    ) {
        self.sessionId = sessionId
        self.imported = imported
        self.skipped = skipped
        self.firstBlock = firstBlock
        self.lastBlock = lastBlock
        self.messageCount = messageCount
    }

    private enum CodingKeys: String, CodingKey {
        case sessionId, imported, skipped, blockRange, messageCount
    }

    private enum BlockRangeKeys: String, CodingKey { case first, last }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sessionId = (try? container.decodeIfPresent(String.self, forKey: .sessionId)) ?? ""
        // `imported` 的两种形状：数字或 id 数组。
        if let count = (try? container.decodeIfPresent(Int.self, forKey: .imported)) ?? nil {
            imported = count
        } else if let ids = (try? container.decodeIfPresent([String].self, forKey: .imported)) ?? nil {
            imported = ids.count
        } else {
            imported = 0
        }
        skipped = ((try? container.decodeIfPresent(Int.self, forKey: .skipped)) ?? nil) ?? 0
        messageCount = ((try? container.decodeIfPresent(Int64.self, forKey: .messageCount)) ?? nil)
        let range = (try? container.nestedContainer(keyedBy: BlockRangeKeys.self, forKey: .blockRange))
            ?? nil
        firstBlock = ((try? range?.decodeIfPresent(Int.self, forKey: .first)) ?? nil) ?? nil
        lastBlock = ((try? range?.decodeIfPresent(Int.self, forKey: .last)) ?? nil) ?? nil
    }
}

// MARK: - 客户端

public extension ServerAPIClient {

    /// 一批硬上限（服务端 201 条直接 400；不要靠服务端兜底）。
    static let maxImportBatchSize = 200
    /// 单批 body 的保守上限。服务端的硬上限是 8 MiB，这里留 1 MiB 给 envelope 与编码开销。
    static let maxImportBatchBytes = 7 * 1024 * 1024

    /// 推一批历史消息。
    ///
    /// - Parameters:
    ///   - sessionId: **服务端**会话 id（不是本地 id）。
    ///   - messages: 已经分好批（≤200 条、≤7 MiB）的消息；超限会在本地直接拒绝，
    ///     不发一个注定 400 的请求。
    /// - Returns: 服务端对账结果（`imported` / `skipped` / 块区间 / 总条数）。
    func importMessages(
        sessionId: String,
        messages: [ServerImportMessage]
    ) async throws -> ServerImportResult {
        let sessionId = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sessionId.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "sessionId 不能为空")
        }
        guard messages.count <= Self.maxImportBatchSize else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "一次最多导入 \(Self.maxImportBatchSize) 条消息"
            )
        }
        let body = ServerImportRequest(messages: messages)
        let encoded: Data
        do {
            encoded = try JSONEncoder().encode(body)
        } catch {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "消息无法编码")
        }
        guard encoded.count <= Self.maxImportBatchBytes else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "这一批消息太大了，请拆小一点"
            )
        }
        // 走同一个 `post`：错误体映射、鉴权头、超时口径与其它写路径完全一致。
        // 用资源超时（30s）：历史消息批量写可能比普通写慢。
        return try await post(
            path: "/sessions/\(Self.writePathSegment(sessionId))/messages/import",
            body: body,
            as: ServerImportResult.self,
            timeout: Self.resourceTimeout
        )
    }

    /// 把一串消息切成「条数 ≤200、估算字节 ≤7 MiB」的批次（保持原顺序）。
    ///
    /// 单条自己就超过字节上限时仍然单独成批（服务端会以 413 明确告诉我们太大，
    /// 客户端在这里静默丢消息更糟）。
    static func importBatches(
        for messages: [ServerImportMessage],
        maxCount: Int = ServerAPIClient.maxImportBatchSize,
        maxBytes: Int = ServerAPIClient.maxImportBatchBytes
    ) -> [[ServerImportMessage]] {
        let countLimit = max(1, min(maxCount, ServerAPIClient.maxImportBatchSize))
        let byteLimit = max(1024, maxBytes)
        var batches: [[ServerImportMessage]] = []
        var current: [ServerImportMessage] = []
        var currentBytes = 0
        for message in messages {
            let size = message.estimatedBytes
            let wouldOverflow = !current.isEmpty
                && (current.count + 1 > countLimit || currentBytes + size > byteLimit)
            if wouldOverflow {
                batches.append(current)
                current = []
                currentBytes = 0
            }
            current.append(message)
            currentBytes += size
        }
        if !current.isEmpty { batches.append(current) }
        return batches
    }

    /// 把本地消息 id 转成服务端接受的形状（`[A-Za-z0-9_-]{1,128}`）。
    ///
    /// 正常路径上本地 id 就是 UUID 字符串，原样返回（服务端会保留它，重推幂等）。
    /// 老数据里如果有 `:` / `.` / 空格这类字符，这里按位置替换成 `-` 并截断 ——
    /// 结果是**确定的**：同一条本地消息每次都会得到同一个 id，重试不会变成两条。
    static func normalizedImportId(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return UUID().uuidString }
        let allowed = CharacterSet(charactersIn:
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")
        var scalars: [Unicode.Scalar] = []
        for scalar in trimmed.unicodeScalars {
            scalars.append(allowed.contains(scalar) ? scalar : "-")
            if scalars.count >= 128 { break }
        }
        let text = String(String.UnicodeScalarView(scalars))
        return text.isEmpty ? UUID().uuidString : text
    }
}
