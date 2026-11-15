import Foundation

// ---------------------------------------------------------------------------
// 分块历史存储的**纯规则**（规范：`spec/messages-blocks/README.md` §1–§3）。
//
// 为什么规则单独一层：
// - 追加落哪一块、锚点插入怎么改元数据、删到空块要不要重排 —— 这些是两端必须逐字一致的
//   语义，不该藏在 SQL 里；抽出来才能在 `swift test` 里用「没有数据库」的用例钉住；
// - `MessageBlockStore`（Data 层）只负责把这里的结论写成 SQL，迁移回填也复用同一组常量，
//   于是「迁移后块边界」和「运行期追加出来的块边界」不会漂成两套。
//
// 三条刻意保留的「不」：
// 1. **不重平衡**：块只会变小（删除）或最后一块变大（追加），编号只增不减；
// 2. **不重排**：块变空就删掉块行、留下编号空洞，绝不把后面的块往前挪；
// 3. **不猜**：`first…` / `last…` 是「最近一次插入/删除之后的边界」，不是时间范围查询结果。
// ---------------------------------------------------------------------------

/// 一块的元数据（= `message_blocks` 的一行）。
///
/// 放在 Domain 里而不是 Data 里：`MessageBlocks` 的纯函数直接返回它，
/// 数据层只补一个 `SQLiteRecord` 映射（见 `MessageBlockStore`）。
public struct MessageBlockEntity: Equatable, Sendable {
    public var sessionId: String
    public var blockIndex: Int
    public var firstMessageId: String
    public var lastMessageId: String
    public var firstCreatedAt: Int64
    public var lastCreatedAt: Int64
    public var messageCount: Int

    public init(
        sessionId: String,
        blockIndex: Int,
        firstMessageId: String,
        lastMessageId: String,
        firstCreatedAt: Int64,
        lastCreatedAt: Int64,
        messageCount: Int
    ) {
        self.sessionId = sessionId
        self.blockIndex = blockIndex
        self.firstMessageId = firstMessageId
        self.lastMessageId = lastMessageId
        self.firstCreatedAt = firstCreatedAt
        self.lastCreatedAt = lastCreatedAt
        self.messageCount = messageCount
    }
}

public enum MessageBlocks {

    /// 每块最多多少条（规范写死 200，两端同一个数字）。
    public static let blockSize = 200

    /// 定位到某条消息时，目标块之外再往前带几块上下文（默认 1 块 = 最多 400 条）。
    ///
    /// 有上限的原因与旧的分页窗口一样：目标如果已经被删掉，没有上限就是「一路读到最早」，
    /// 长会话下点一下搜索结果会卡住好几秒。宁可少读一点，给一句「没找到」。
    public static let focusContextBlocks = 1

    // MARK: - 追加（规范 §2 Append）

    /// 一次追加该落进哪一块。
    public struct Assignment: Equatable, Sendable {
        /// 落进这一块。
        public var blockIndex: Int
        /// 这一块原来不存在，要新建块行（`first…` 也要写）。
        public var createsBlock: Bool

        public init(blockIndex: Int, createsBlock: Bool) {
            self.blockIndex = blockIndex
            self.createsBlock = createsBlock
        }
    }

    /// 追加规则：取最高块；满了就开下一块。
    ///
    /// - Parameters:
    ///   - lastBlockIndex: 这段会话当前最高的块号（还没有块时为 nil）。
    ///   - lastBlockMessageCount: 那一块里现在几条（无块时传 0）。
    public static func appendAssignment(
        lastBlockIndex: Int?,
        lastBlockMessageCount: Int
    ) -> Assignment {
        guard let last = lastBlockIndex else {
            return Assignment(blockIndex: 0, createsBlock: true)
        }
        if lastBlockMessageCount < blockSize {
            return Assignment(blockIndex: last, createsBlock: false)
        }
        return Assignment(blockIndex: last + 1, createsBlock: true)
    }

    /// 追加进一个**已存在**的块之后，这一块的新元数据。
    ///
    /// 规范 §2 第 3 条：更新 `lastMessageId` / `lastCreatedAt` / `messageCount`。
    /// `first…` 不动（追加的消息永远排在后面）。
    public static func appended(_ block: MessageBlockEntity, with message: MessageEntity) -> MessageBlockEntity {
        var updated = block
        updated.lastMessageId = message.id
        updated.lastCreatedAt = message.createdAt
        updated.messageCount = block.messageCount + 1
        return updated
    }

    /// 按锚点插入（编辑用户消息 = 重建一条）之后，这一块的新元数据。
    ///
    /// 规范 §2「Insert at an anchor」：落在锚点那条消息所在的块；`messageCount + 1`，
    /// **仅当**新消息排在旧的最后一条之后才改 `last…`（编辑出来的消息时间更晚，通常都会改）。
    /// `first…` 不动。
    public static func anchored(_ block: MessageBlockEntity, with message: MessageEntity) -> MessageBlockEntity {
        var updated = block
        updated.messageCount = block.messageCount + 1
        if sortsAfter(
            createdAt: message.createdAt, id: message.id,
            than: block.lastCreatedAt, id: block.lastMessageId
        ) {
            updated.lastMessageId = message.id
            updated.lastCreatedAt = message.createdAt
        }
        return updated
    }

    // MARK: - 刷新（删除 / 合并导入后的整块重算）

    /// 由块里剩下的消息重新算出元数据；**空块返回 nil**（调用方据此删掉块行、留下编号空洞）。
    ///
    /// 边界按 `(createdAt, id)` 升序取第一条与最后一条 —— 这也是删除规则（规范 §2 Delete）
    /// 里「从这一块剩下的行重算 `first…`/`last…`」的同一套口径。
    public static func metadata(
        sessionId: String,
        blockIndex: Int,
        messages: [MessageEntity]
    ) -> MessageBlockEntity? {
        guard !messages.isEmpty, let first = messages.min(by: precedes), let last = messages.max(by: precedes) else {
            return nil
        }
        return MessageBlockEntity(
            sessionId: sessionId,
            blockIndex: blockIndex,
            firstMessageId: first.id,
            lastMessageId: last.id,
            firstCreatedAt: first.createdAt,
            lastCreatedAt: last.createdAt,
            messageCount: messages.count
        )
    }

    // MARK: - 排序与回填

    /// `(createdAt, id)` 升序，`id` 用 UTF-16 码元序（与 Kotlin `compareTo` 一致）。
    public static func precedes(_ lhs: MessageEntity, _ rhs: MessageEntity) -> Bool {
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
        return Lexicographic.utf16Less(lhs.id, rhs.id)
    }

    /// `(createdAt, id)` 是否排在另一个位置之后（锚点插入要用它决定改不改 `last…`）。
    public static func sortsAfter(
        createdAt: Int64,
        id: String,
        than otherCreatedAt: Int64,
        id otherId: String
    ) -> Bool {
        if createdAt != otherCreatedAt { return createdAt > otherCreatedAt }
        return Lexicographic.utf16Less(otherId, id)
    }

    /// 迁移回填：`(createdAt, id)` 序里第 `rowNumber` 条（1 基）属于第几块。
    public static func backfillBlockIndex(rowNumber: Int) -> Int {
        guard rowNumber > 1 else { return 0 }
        return (rowNumber - 1) / blockSize
    }

    // MARK: - 从检索结果定位到某一块（区块分页的「跳过去看」）

    /// 定位的窗口：目标块 + 往前带的上下文块，按块号升序拼起来。
    public struct FocusWindow: Equatable, Sendable {
        /// 窗口里的消息（块号升序、块内 `(createdAt, id)` 升序）。
        public var messages: [MessageEntity]
        /// 窗口里最旧/最新的块号（目标块不存在时都等于 `targetBlock`）。
        public var lowerBlockIndex: Int
        public var upperBlockIndex: Int
        /// 一共读进来几块（含目标块）。
        public var blocksLoaded: Int
        /// 目标消息有没有进窗口（被删掉 / 没权限时是 false）。
        public var found: Bool

        public init(
            messages: [MessageEntity],
            lowerBlockIndex: Int,
            upperBlockIndex: Int,
            blocksLoaded: Int,
            found: Bool
        ) {
            self.messages = messages
            self.lowerBlockIndex = lowerBlockIndex
            self.upperBlockIndex = upperBlockIndex
            self.blocksLoaded = blocksLoaded
            self.found = found
        }
    }

    /// 往上最多扫多少个空块号（块被删空会留下空洞；正常连续编号时一次都不会扫）。
    ///
    /// 有上限是为了保证「块表被写坏（全是空洞）」时这里也不会变成死循环。
    public static let maxGapScan = 64

    /// 定位到 `targetBlock`：读目标块，再往前带 `contextBlocks` 块上下文。
    ///
    /// - Parameters:
    ///   - targetMessageId: 要找的那条消息 id（用来判断 `found`）。
    ///   - targetBlock: 目标消息所在的块号（调用方从库里查出来）。
    ///   - contextBlocks: 往前带几块（默认 `focusContextBlocks`）。
    ///   - loadBlock: 读某一块的正文（`(createdAt, id)` 升序）；**块不存在返回 nil**（空洞），
    ///     块存在但为空返回空数组（这种状态不该出现，遇到就停）。
    public static func focus(
        targetMessageId: String,
        targetBlock: Int,
        contextBlocks: Int = focusContextBlocks,
        loadBlock: (Int) -> [MessageEntity]?
    ) -> FocusWindow {
        var loaded: [(index: Int, messages: [MessageEntity])] = []
        if let target = loadBlock(targetBlock), !target.isEmpty {
            loaded.append((targetBlock, target))
        }

        var context = 0
        var gaps = 0
        var index = targetBlock - 1
        while index >= 0, context < max(0, contextBlocks), gaps < maxGapScan {
            guard let older = loadBlock(index) else {
                // 空洞：块被删空了，编号不会补回来，继续往前找。
                index -= 1
                gaps += 1
                continue
            }
            if older.isEmpty { break }
            loaded.insert((index, older), at: 0)
            context += 1
            index -= 1
        }

        let messages = loaded.flatMap(\.messages)
        return FocusWindow(
            messages: messages,
            lowerBlockIndex: loaded.first?.index ?? targetBlock,
            upperBlockIndex: loaded.last?.index ?? targetBlock,
            blocksLoaded: loaded.count,
            found: messages.contains { $0.id == targetMessageId }
        )
    }
}
