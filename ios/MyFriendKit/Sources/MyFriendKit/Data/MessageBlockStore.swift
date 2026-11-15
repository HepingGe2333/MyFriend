import Foundation

// ---------------------------------------------------------------------------
// `message_blocks` 的读写与维护（规范：`spec/messages-blocks/README.md`）。
//
// 这个文件是「块表」的唯一入口，**所有**改动消息的路径都必须经过它，否则块会漂：
// 发消息 / 主动开口 / 工具消息（`ChatService`、`AgentLoop`）、重新生成（删最后一条）、
// 删一条消息、清空上下文、备份导入（替换 / 合并）、删会话、删人格、删除全部数据……
// 因此 `MessageStore` 把 `upsert` / `delete` / `deleteAll` 全部转到这里（见 Stores.swift），
// 调用方一行都不用改，就不会有「某条路径忘了维护块」。
//
// 三条不变量（每个写方法结束时都成立）：
// 1. 一条消息的 `blockIndex` 一定指向同会话里一个**存在的块行**；
// 2. 块行的 `messageCount` 等于那块里的实际条数（> 0；空块行不存在）；
// 3. `first…` / `last…` 与块里那两条消息的 `createdAt` 一致。
// 注意：块**不保证** ≤ BLOCK_SIZE —— 锚点插入会往满块里再加一条，规范明确不重平衡，
// 所以「超了 200 条」不是不自洽。
//
// 刻意不做的事：不重平衡、不重排（空块删掉、编号留洞），与规范 §2 一致。
// ---------------------------------------------------------------------------

extension MessageBlockEntity: SQLiteRecord {
    public static let tableName = "message_blocks"
    public static let columns = [
        "sessionId", "blockIndex", "firstMessageId", "lastMessageId",
        "firstCreatedAt", "lastCreatedAt", "messageCount"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(sessionId), .int(blockIndex), .text(firstMessageId), .text(lastMessageId),
            .int(firstCreatedAt), .int(lastCreatedAt), .int(messageCount)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            sessionId: try row.text("sessionId"),
            blockIndex: try row.int("blockIndex"),
            firstMessageId: try row.text("firstMessageId"),
            lastMessageId: try row.text("lastMessageId"),
            firstCreatedAt: try row.int64("firstCreatedAt"),
            lastCreatedAt: try row.int64("lastCreatedAt"),
            messageCount: try row.int("messageCount")
        )
    }
}

public final class MessageBlockStore: SQLiteStore<MessageBlockEntity> {

    // MARK: - 读

    /// 一段会话的所有块（块号升序；编号可能有空洞）。
    public func blocks(sessionId: String) throws -> [MessageBlockEntity] {
        try fetch(where: "sessionId = ?", [.text(sessionId)], orderBy: "blockIndex ASC")
    }

    public func block(sessionId: String, blockIndex: Int) throws -> MessageBlockEntity? {
        try first(where: "sessionId = ? AND blockIndex = ?", [.text(sessionId), .int(blockIndex)])
    }

    public func lastBlock(sessionId: String) throws -> MessageBlockEntity? {
        try first(where: "sessionId = ?", [.text(sessionId)], orderBy: "blockIndex DESC")
    }

    /// 最新一块的块号（空会话返回 nil）。首屏就靠它定位，不扫消息表。
    public func lastBlockIndex(sessionId: String) throws -> Int? {
        try lastBlock(sessionId: sessionId)?.blockIndex
    }

    public func firstBlockIndex(sessionId: String) throws -> Int? {
        try first(where: "sessionId = ?", [.text(sessionId)], orderBy: "blockIndex ASC")?.blockIndex
    }

    /// 比 `blockIndex` 更旧的最近一块（没有就 nil）。向上翻页用它，不靠偏移量。
    public func olderBlockIndex(sessionId: String, before blockIndex: Int) throws -> Int? {
        try first(
            where: "sessionId = ? AND blockIndex < ?",
            [.text(sessionId), .int(blockIndex)],
            orderBy: "blockIndex DESC"
        )?.blockIndex
    }

    /// 比 `blockIndex` 更新的最近一块（没有就 nil）。向下翻页 / 「下方还有更新的消息」用它。
    public func newerBlockIndex(sessionId: String, after blockIndex: Int) throws -> Int? {
        try first(
            where: "sessionId = ? AND blockIndex > ?",
            [.text(sessionId), .int(blockIndex)],
            orderBy: "blockIndex ASC"
        )?.blockIndex
    }

    /// 一条消息落在哪一块（搜索结果定位要用它）。
    public func blockIndex(ofMessage id: String) throws -> Int? {
        let statement = try database.prepare("SELECT blockIndex FROM messages WHERE id = ? LIMIT 1")
        try statement.bind([.text(id)])
        guard try statement.step() else { return nil }
        return Int(statement.int64(at: 0))
    }

    public func blockCount(sessionId: String) throws -> Int {
        try count(where: "sessionId = ?", [.text(sessionId)])
    }

    // MARK: - 写：消息 + 块元数据在同一个事务里

    /// 写一条消息并维护块（新增走追加规则、已存在保留原块）。
    ///
    /// 这是 `MessageStore.upsert` 的实现，也是所有「消息落库」路径的唯一入口。
    public func upsertMaintainingBlocks(_ message: MessageEntity) throws {
        try database.inTransaction {
            guard let existing = try existingMessage(id: message.id) else {
                try appendNew(message)
                return
            }
            if existing.sessionId != message.sessionId {
                // 会话被 remap（备份合并把备份那段并进本机那段）：按追加规则落到新会话，
                // 再把旧块按「剩下的行」重算 —— 旧块可能因此变空而被删除（编号留洞）。
                try appendNew(message)
                try refreshBlock(sessionId: existing.sessionId, blockIndex: existing.blockIndex)
                return
            }
            var row = message
            row.blockIndex = existing.blockIndex
            try writeMessage(row)
            if existing.createdAt != message.createdAt {
                // 同 id 的消息时间戳被改（合并导入）：边界要跟着重算，否则 first/last 会指向错的行。
                try refreshBlock(sessionId: row.sessionId, blockIndex: existing.blockIndex)
            }
        }
    }

    /// 按锚点插入（编辑用户消息 = 在锚点那条后面重建一条）。返回 false = 锚点已经不在了。
    ///
    /// 规范 §2「Insert at an anchor」：落进**锚点所在的块**，`messageCount + 1`，
    /// 新消息排在旧 `last…` 之后才更新 `last…`。
    @discardableResult
    public func insert(_ message: MessageEntity, atAnchor anchorId: String) throws -> Bool {
        try database.inTransaction {
            guard let anchor = try existingMessage(id: anchorId),
                  anchor.sessionId == message.sessionId,
                  let block = try block(sessionId: anchor.sessionId, blockIndex: anchor.blockIndex) else {
                return false
            }
            var row = message
            row.blockIndex = block.blockIndex
            try writeMessage(row)
            try upsert(MessageBlocks.anchored(block, with: row))
            return true
        }
    }

    /// 删一条消息并维护块（块空则删掉块行、留编号空洞）。
    public func deleteMessage(id: String) throws {
        try database.inTransaction {
            guard let existing = try existingMessage(id: id) else { return }
            try deleteMessageRow(where: "id = ?", [.text(id)])
            try refreshBlock(sessionId: existing.sessionId, blockIndex: existing.blockIndex)
        }
    }

    /// 按条件批量删消息：先把受影响的块记下来，删完逐块重算（清空上下文 / 删人格走这里）。
    public func deleteMessages(where clause: String, _ arguments: [SQLiteValue] = []) throws {
        let trimmed = clause.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            try deleteAllMessages()
            return
        }
        try database.inTransaction {
            let affected = try affectedBlocks(where: trimmed, arguments)
            try deleteMessageRow(where: trimmed, arguments)
            for coordinate in affected {
                try refreshBlock(sessionId: coordinate.sessionId, blockIndex: coordinate.blockIndex)
            }
        }
    }

    public func deleteMessages(sessionId: String) throws {
        try deleteMessages(where: "sessionId = ?", [.text(sessionId)])
    }

    /// 删掉全部消息与全部块（替换导入 / 清空全部数据走这里）。
    public func deleteAllMessages() throws {
        try database.inTransaction {
            try database.execute("DELETE FROM messages")
            try deleteAll()
        }
    }

    /// 删掉某段会话的全部块（会话被删时调用；先删块再删会话，避免中间态留下孤儿块）。
    public func deleteBlocks(sessionId: String) throws {
        try delete(where: "sessionId = ?", [.text(sessionId)])
    }

    /// 清掉「会话已经不存在」的块行与「块里已经没有消息」的块行。返回清掉几行。
    ///
    /// 迁移（规范 §5 第 4 条「删除孤儿块」）与删人格 / 替换导入的收尾都用它：
    /// 正常路径不会留下这种行，它是兜底而不是常规手段。
    @discardableResult
    public func removeOrphanBlocks() throws -> Int {
        let orphanBySession = try database.executeUpdate("""
            DELETE FROM message_blocks
            WHERE sessionId NOT IN (SELECT id FROM chat_sessions)
            """)
        let orphanByCount = try database.executeUpdate("""
            DELETE FROM message_blocks
            WHERE messageCount <= 0
               OR NOT EXISTS (
                    SELECT 1 FROM messages m
                    WHERE m.sessionId = message_blocks.sessionId
                      AND m.blockIndex = message_blocks.blockIndex
               )
            """)
        return orphanBySession + orphanByCount
    }

    /// 从剩下的消息重算一块的元数据；空块直接删掉块行（规范 §2 Delete）。
    public func refreshBlock(sessionId: String, blockIndex: Int) throws {
        let rows = try blockMessages(sessionId: sessionId, blockIndex: blockIndex)
        if let metadata = MessageBlocks.metadata(
            sessionId: sessionId, blockIndex: blockIndex, messages: rows
        ) {
            try upsert(metadata)
        } else {
            try delete(where: "sessionId = ? AND blockIndex = ?", [.text(sessionId), .int(blockIndex)])
        }
    }

    /// 某一块里的消息（`(createdAt, id)` 升序）。分页与「刷新块」共用。
    public func blockMessages(sessionId: String, blockIndex: Int) throws -> [MessageEntity] {
        try messageRows(
            where: "sessionId = ? AND blockIndex = ?",
            [.text(sessionId), .int(blockIndex)],
            orderBy: "createdAt ASC, id ASC"
        )
    }

    // MARK: - 自洽审计（测试与诊断用）

    /// 块表与消息表是否自洽。返回的问题描述为**空**才叫自洽。
    ///
    /// 对齐的三条不变量见文件头。抽成公开方法是为了让「备份导入 / 合并 / 替换之后块表自洽」
    /// 这件事可以在测试里对任意写路径复用同一份判定，而不是每个用例自己拼 SQL。
    public func inconsistencies(limit: Int = 20) throws -> [String] {
        var issues: [String] = []

        // 1) 块行：条数、边界 id、边界时间、上限。
        let blockStatement = try database.prepare("""
            SELECT b.sessionId, b.blockIndex, b.messageCount, b.firstMessageId, b.lastMessageId,
                   b.firstCreatedAt, b.lastCreatedAt,
                   (SELECT COUNT(*) FROM messages m
                     WHERE m.sessionId = b.sessionId AND m.blockIndex = b.blockIndex) AS actual,
                   (SELECT m2.createdAt FROM messages m2 WHERE m2.id = b.firstMessageId) AS firstAt,
                   (SELECT m3.createdAt FROM messages m3 WHERE m3.id = b.lastMessageId) AS lastAt
            FROM message_blocks b
            ORDER BY b.sessionId ASC, b.blockIndex ASC
            """)
        while try blockStatement.step() {
            let sessionId = blockStatement.text(at: 0) ?? ""
            let blockIndex = blockStatement.int64(at: 1)
            let declared = blockStatement.int64(at: 2)
            let actual = blockStatement.int64(at: 7)
            let label = "块 \(sessionId)#\(blockIndex)"
            if blockIndex < 0 { issues.append("\(label)：块号是负数") }
            if declared != actual {
                issues.append("\(label)：messageCount=\(declared) 但块里有 \(actual) 条")
            }
            if actual == 0 { issues.append("\(label)：空块行还在（应当删除并留下编号空洞）") }
            // 边界 id 必须真的是这一块里的消息，且时间对得上。
            let firstStatement = try database.prepare(
                "SELECT blockIndex, createdAt FROM messages WHERE id = ? LIMIT 1"
            )
            try firstStatement.bind([.text(blockStatement.text(at: 3) ?? "")])
            if try firstStatement.step() {
                if firstStatement.int64(at: 0) != blockIndex {
                    issues.append("\(label)：firstMessageId 指向别的块")
                }
                if firstStatement.int64(at: 1) != blockStatement.int64(at: 5) {
                    issues.append("\(label)：firstCreatedAt 与 firstMessageId 的时间不一致")
                }
            } else {
                issues.append("\(label)：firstMessageId 在消息表里不存在")
            }
            let lastStatement = try database.prepare(
                "SELECT blockIndex, createdAt FROM messages WHERE id = ? LIMIT 1"
            )
            try lastStatement.bind([.text(blockStatement.text(at: 4) ?? "")])
            if try lastStatement.step() {
                if lastStatement.int64(at: 0) != blockIndex {
                    issues.append("\(label)：lastMessageId 指向别的块")
                }
                if lastStatement.int64(at: 1) != blockStatement.int64(at: 6) {
                    issues.append("\(label)：lastCreatedAt 与 lastMessageId 的时间不一致")
                }
            } else {
                issues.append("\(label)：lastMessageId 在消息表里不存在")
            }
            if issues.count >= limit { break }
        }

        // 2) 消息：blockIndex 必须指向一个存在的块行（不能悬空）。
        let messageStatement = try database.prepare("""
            SELECT m.sessionId, m.blockIndex, m.id FROM messages m
            WHERE NOT EXISTS (
                SELECT 1 FROM message_blocks b
                WHERE b.sessionId = m.sessionId AND b.blockIndex = m.blockIndex
            )
            LIMIT \(max(1, limit))
            """)
        while try messageStatement.step() {
            issues.append(
                "消息 \(messageStatement.text(at: 2) ?? "")：blockIndex=\(messageStatement.int64(at: 1)) 没有对应块行"
            )
        }

        // 3) 块行：会话必须还在。
        let orphanStatement = try database.prepare("""
            SELECT sessionId, blockIndex FROM message_blocks
            WHERE sessionId NOT IN (SELECT id FROM chat_sessions)
            LIMIT \(max(1, limit))
            """)
        while try orphanStatement.step() {
            issues.append("孤儿块 \(orphanStatement.text(at: 0) ?? "")#\(orphanStatement.int64(at: 1))：会话已删除")
        }

        return issues
    }

    // MARK: - 内部

    private struct ExistingMessage {
        let sessionId: String
        let blockIndex: Int
        let createdAt: Int64
    }

    private struct BlockCoordinate {
        let sessionId: String
        let blockIndex: Int
    }

    private func existingMessage(id: String) throws -> ExistingMessage? {
        let statement = try database.prepare(
            "SELECT sessionId, blockIndex, createdAt FROM messages WHERE id = ? LIMIT 1"
        )
        try statement.bind([.text(id)])
        guard try statement.step() else { return nil }
        return ExistingMessage(
            sessionId: statement.text(at: 0) ?? "",
            blockIndex: Int(statement.int64(at: 1)),
            createdAt: statement.int64(at: 2)
        )
    }

    /// 追加规则 + 落库 + 块元数据（调用方保证在事务里）。
    private func appendNew(_ message: MessageEntity) throws {
        let last = try lastBlock(sessionId: message.sessionId)
        let assignment = MessageBlocks.appendAssignment(
            lastBlockIndex: last?.blockIndex,
            lastBlockMessageCount: last?.messageCount ?? 0
        )
        var row = message
        row.blockIndex = assignment.blockIndex
        try writeMessage(row)
        if assignment.createsBlock {
            if let metadata = MessageBlocks.metadata(
                sessionId: row.sessionId, blockIndex: assignment.blockIndex, messages: [row]
            ) {
                try upsert(metadata)
            }
        } else if let last {
            try upsert(MessageBlocks.appended(last, with: row))
        }
    }

    private func affectedBlocks(where clause: String, _ arguments: [SQLiteValue]) throws -> [BlockCoordinate] {
        let statement = try database.prepare(
            "SELECT DISTINCT sessionId, blockIndex FROM messages WHERE \(clause)"
        )
        try statement.bind(arguments)
        var coordinates: [BlockCoordinate] = []
        while try statement.step() {
            coordinates.append(BlockCoordinate(
                sessionId: statement.text(at: 0) ?? "",
                blockIndex: Int(statement.int64(at: 1))
            ))
        }
        return coordinates
    }

    private func writeMessage(_ message: MessageEntity) throws {
        let columns = MessageEntity.columns.joined(separator: ", ")
        let placeholders = Array(repeating: "?", count: MessageEntity.columns.count).joined(separator: ", ")
        let statement = try database.prepare(
            "INSERT OR REPLACE INTO messages (\(columns)) VALUES (\(placeholders))"
        )
        try statement.bind(message.bindings)
        _ = try statement.step()
    }

    private func deleteMessageRow(where clause: String, _ arguments: [SQLiteValue]) throws {
        let statement = try database.prepare("DELETE FROM messages WHERE \(clause)")
        try statement.bind(arguments)
        _ = try statement.step()
    }

    private func messageRows(
        where clause: String,
        _ arguments: [SQLiteValue],
        orderBy: String
    ) throws -> [MessageEntity] {
        let columns = MessageEntity.columns.joined(separator: ", ")
        let statement = try database.prepare(
            "SELECT \(columns) FROM messages WHERE \(clause) ORDER BY \(orderBy)"
        )
        try statement.bind(arguments)
        var rows: [MessageEntity] = []
        while try statement.step() {
            rows.append(try MessageEntity(row: SQLiteRow(statement: statement)))
        }
        return rows
    }
}
