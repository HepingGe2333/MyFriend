package com.myfriend.app.domain

/**
 * 会话历史的「区块」规则（规范：`spec/messages-blocks/README.md` 第 1、2 节）。
 *
 * ## 为什么要有区块
 *
 * 0.10.1 起聊天页是「首屏 60 条 + `(createdAt,id)` 游标上滑 60 条」，0.11.x 又给检索加了
 * 「候选上限 500 条」。两条路都有同一个毛病：**一页的边界是浮动的**。
 *
 * - 游标分页要求「从最新往回数」，所以新消息进来时页边界会整体滑动，界面得自己追着锚点跑；
 * - 检索的 500 条上限意味着「更早的命中永远看不到」，而这在长历史里是真实会发生的事。
 *
 * 区块把「一页」变成一个**物理存在**的东西：消息按插入顺序每 [BLOCK_SIZE] 条一块，
 * `messages.blockIndex` + `message_blocks` 两张表把块的边界、条数、首尾消息落库。
 * 于是：
 *
 * - 一页 = 一块，`WHERE sessionId = ? AND blockIndex = ?` 直接定位，读取量与历史长度无关；
 * - 检索可以「一块一块走」，不需要一次性把整段历史读进内存，也不需要候选上限。
 *
 * ## 本文件的边界
 *
 * 这里**只有纯规则**（分配、插入、删除后的元数据、按顺序重建），不碰 Room、不碰协程。
 * 落库的那一半在 `data/repo/MessageBlockWriter`，两边共用这里的函数，所以单测能直接钉住
 * 「追加到哪一块」「删完以后首尾是谁」「删空的块怎么处理」这些最容易写错的细节。
 *
 * ## 不重平衡（规范明确要求）
 *
 * 块只会「变短」（删除）或「最后一块变长」（追加），**永远不重排编号、也不把短的块合并**。
 * 代价是块大小不均衡（极端删除后可能只剩 1 条），收益是「已加载的块编号永远有效」：
 * 界面不需要因为一次删除就重新算窗口，检索游标也不会因为重平衡而错位。
 */
object MessageBlocks {

    /**
     * 一块最多多少条消息（规范 §1：`BLOCK_SIZE = 200`）。
     *
     * 200 是「一屏装不下、一页读得完」的折中：一块的正文在几百 KB 量级（含 attachmentsJson
     * 时更高），既不至于让一次分页查询带回太多行，也不会让块数多到检索要走上千趟 SQL。
     */
    const val BLOCK_SIZE = 200

    /**
     * 一条消息在块维护里需要的全部信息。
     *
     * 刻意不是 `MessageEntity`：块规则只关心「稳定序 `(createdAt, id)` 里的位置」，
     * 用窄类型才能让这些规则在普通 JVM 单测里跑（不需要 Room / Android）。
     */
    data class Ref(
        val id: String,
        val sessionId: String,
        val createdAt: Long
    )

    /** `message_blocks` 的一行（规范 §1 的列一一对应）。 */
    data class Meta(
        val sessionId: String,
        val blockIndex: Int,
        val firstMessageId: String,
        val lastMessageId: String,
        val firstCreatedAt: Long,
        val lastCreatedAt: Long,
        val messageCount: Int
    ) {
        init {
            require(messageCount > 0) { "空块不该存在：空块要整行删掉（规范 §1）" }
        }
    }

    /** 一次「写入落点」的决定：目标块编号 + 写入之后那一块的元数据 + 是不是新建的块。 */
    data class Placement(
        val blockIndex: Int,
        val meta: Meta,
        val created: Boolean
    )

    /**
     * 稳定序 `(createdAt, id)` 下 [a] 是否严格晚于 [b]。
     *
     * 与 `MessageOrder` 的语义一致：同一毫秒时按 id 决先后，所以「追加」永远是「排到最后」。
     */
    fun isAfter(a: Ref, b: Ref): Boolean =
        a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.id > b.id)

    /**
     * 追加规则（规范 §2 Append）。
     *
     * @param last 这个会话当前编号最大的块（没有块时为 null）
     * @param ref 新消息
     */
    fun planAppend(last: Meta?, ref: Ref, blockSize: Int = BLOCK_SIZE): Placement {
        require(blockSize > 0) { "blockSize 必须为正" }
        if (last == null) {
            return Placement(
                blockIndex = 0,
                meta = Meta(
                    sessionId = ref.sessionId,
                    blockIndex = 0,
                    firstMessageId = ref.id,
                    lastMessageId = ref.id,
                    firstCreatedAt = ref.createdAt,
                    lastCreatedAt = ref.createdAt,
                    messageCount = 1
                ),
                created = true
            )
        }
        return if (last.messageCount < blockSize) {
            Placement(
                blockIndex = last.blockIndex,
                meta = last.copy(
                    lastMessageId = ref.id,
                    lastCreatedAt = ref.createdAt,
                    messageCount = last.messageCount + 1
                ),
                created = false
            )
        } else {
            val next = last.blockIndex + 1
            Placement(
                blockIndex = next,
                meta = Meta(
                    sessionId = ref.sessionId,
                    blockIndex = next,
                    firstMessageId = ref.id,
                    lastMessageId = ref.id,
                    firstCreatedAt = ref.createdAt,
                    lastCreatedAt = ref.createdAt,
                    messageCount = 1
                ),
                created = true
            )
        }
    }

    /**
     * 按锚点插入规则（规范 §2 Insert at an anchor）。
     *
     * 编辑用户消息是「在锚点后面重新落一条」：块归属跟着锚点走，块可能因此超过
     * [BLOCK_SIZE] 一条（规范允许，不做重平衡）。只要新消息排在原 last 之后，`last…` 就前移；
     * 否则只把 `messageCount` 加一（例如导入的备份里时间戳更早的一条）。
     */
    fun planInsertAtAnchor(anchor: Meta, ref: Ref, blockSize: Int = BLOCK_SIZE): Placement {
        require(blockSize > 0) { "blockSize 必须为正" }
        val sortsLast = ref.createdAt > anchor.lastCreatedAt ||
            (ref.createdAt == anchor.lastCreatedAt && ref.id > anchor.lastMessageId)
        val meta = anchor.copy(
            lastMessageId = if (sortsLast) ref.id else anchor.lastMessageId,
            lastCreatedAt = if (sortsLast) ref.createdAt else anchor.lastCreatedAt,
            messageCount = anchor.messageCount + 1
        )
        return Placement(blockIndex = anchor.blockIndex, meta = meta, created = false)
    }

    /**
     * 删除一条消息之后的块元数据（规范 §2 Delete）。
     *
     * @param remainingFirst 剩下的第一条（块空了就传 null）
     * @param remainingLast 剩下的最后一条（块空了就传 null）
     * @param remainingCount 剩下几条
     * @return 新的块元数据；返回 null 表示块已空，**整行删掉并留下编号空洞**（不重排）
     */
    fun afterDelete(
        meta: Meta,
        remainingFirst: Ref?,
        remainingLast: Ref?,
        remainingCount: Int
    ): Meta? {
        if (remainingCount <= 0 || remainingFirst == null || remainingLast == null) return null
        return meta.copy(
            firstMessageId = remainingFirst.id,
            firstCreatedAt = remainingFirst.createdAt,
            lastMessageId = remainingLast.id,
            lastCreatedAt = remainingLast.createdAt,
            messageCount = remainingCount
        )
    }

    /** 第 [position] 条（0 起）消息应该落在哪一块：迁移回填与导入重建都走这一个公式。 */
    fun blockIndexOf(position: Int, blockSize: Int = BLOCK_SIZE): Int {
        require(position >= 0) { "position 不能为负" }
        require(blockSize > 0) { "blockSize 必须为正" }
        return position / blockSize
    }

    /**
     * 按「消息顺序 + 追加规则」重建整个会话的区块（规范 §5 Backup 备注）。
     *
     * 备份里**不写块元数据**（格式仍是 v2）：导入之后用这里从消息本身重新推一遍，
     * 所以「替换 / 合并导入之后块表自洽」不依赖备份文件里有没有块。
     *
     * @param refs 该会话的全部消息，已按 `(createdAt, id)` 升序
     * @return 每块一行，按 blockIndex 升序
     */
    fun rebuild(sessionId: String, refs: List<Ref>, blockSize: Int = BLOCK_SIZE): List<Meta> {
        require(blockSize > 0) { "blockSize 必须为正" }
        if (refs.isEmpty()) return emptyList()
        val metas = ArrayList<Meta>(refs.size / blockSize + 1)
        refs.forEachIndexed { position, ref ->
            val index = blockIndexOf(position, blockSize)
            val previous = metas.lastOrNull()
            if (previous == null || previous.blockIndex != index) {
                metas += Meta(
                    sessionId = sessionId,
                    blockIndex = index,
                    firstMessageId = ref.id,
                    lastMessageId = ref.id,
                    firstCreatedAt = ref.createdAt,
                    lastCreatedAt = ref.createdAt,
                    messageCount = 1
                )
            } else {
                metas[metas.lastIndex] = previous.copy(
                    lastMessageId = ref.id,
                    lastCreatedAt = ref.createdAt,
                    messageCount = previous.messageCount + 1
                )
            }
        }
        return metas
    }

    /**
     * 每一条消息应该落在哪一块（与 [rebuild] 同一套顺序，返回 `id -> blockIndex`）。
     *
     * 导入重建时用它把 `messages.blockIndex` 一次写对；顺序与 [blockIndexOf] 一致，
     * 所以块行与消息列永远不会各自算一套。
     */
    fun assignments(refs: List<Ref>, blockSize: Int = BLOCK_SIZE): Map<String, Int> {
        require(blockSize > 0) { "blockSize 必须为正" }
        val result = LinkedHashMap<String, Int>(refs.size)
        refs.forEachIndexed { position, ref -> result[ref.id] = blockIndexOf(position, blockSize) }
        return result
    }
}
