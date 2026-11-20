package com.yap.app.domain

/**
 * 聊天页的「区块分页」状态机（规范：`spec/messages-blocks/README.md` §3）。
 *
 * ## 与旧的分页状态机（`MessagePaging`）的区别
 *
 * | | 旧（游标分页，0.10.1） | 新（区块分页） |
 * | --- | --- | --- |
 * | 一页是什么 | 「最近 60 条」/「游标之前 60 条」 | **恰好一块**（≤ [MessageBlocks.BLOCK_SIZE] 条） |
 * | 向上 | 加一页 | 加载 `blockIndex - 1` 那一块 |
 * | 向下 | **不能**（只能「回到最新一屏」） | 加载 `blockIndex + 1` 那一块 |
 * | 边界会滑动吗 | 会（新消息进来时页边界整体后移） | 不会（块编号是落库的） |
 *
 * 所以这里的状态是「已加载的块编号集合 + 两侧还有没有块 + 正在加载哪一侧」，
 * 而不是「已加载的条数」。所有函数都是纯函数，普通 JVM 单测直接钉住：
 * 首屏 1 块、向上/向下各一块、删除后的短块、边界块、并发触发只发一次查询。
 *
 * ## 缺口是合法的
 *
 * 块删空之后编号会留下空洞（规范 §1：编号永不重排）。所以「上一块」不是 `newest - 1`，
 * 而是「编号小于它的最大块」——由调用方（Repository）查出来再喂给 [onOlderLoaded]。
 */
object MessageBlockPaging {

    /**
     * 分页状态。
     *
     * [blocks] 是**已加载的块编号**（升序）。它不保证连续：中间某块被删空之后就是缺口，
     * 但这不影响展示——列表按 `(blockIndex, createdAt, id)` 拼起来仍然是有序的。
     */
    data class State(
        val blocks: List<Int> = emptyList(),
        /** 已加载的最早块之上还有块（界面顶部提示「加载更早」）。 */
        val hasMoreOlder: Boolean = false,
        /** 已加载的最新块之下还有块（界面底部提示「下面还有更新的消息」，可自动/点击加载）。 */
        val hasMoreNewer: Boolean = false,
        val loadingOlder: Boolean = false,
        val loadingNewer: Boolean = false,
        /** 首屏是否已经定位过（没有块的空会话也算定位过）。 */
        val initialized: Boolean = false
    ) {
        val oldestBlock: Int? get() = blocks.minOrNull()
        val newestBlock: Int? get() = blocks.maxOrNull()
        val canLoadOlder: Boolean get() = hasMoreOlder && !loadingOlder
        val canLoadNewer: Boolean get() = hasMoreNewer && !loadingNewer
    }

    /**
     * 首屏：**只加载最新一块**（规范 §3 Initial load）。
     *
     * @param lastBlockIndex 会话里编号最大的块；空会话为 null
     * @param firstBlockIndex 会话里编号最小的块；空会话为 null
     */
    fun onInitial(lastBlockIndex: Int?, firstBlockIndex: Int?): State {
        if (lastBlockIndex == null) return State(initialized = true)
        return State(
            blocks = listOf(lastBlockIndex),
            // 最早一块本身没有更早的；有缺口时也只看「还有没有编号更小的块」。
            hasMoreOlder = firstBlockIndex != null && firstBlockIndex < lastBlockIndex,
            hasMoreNewer = false,
            initialized = true
        )
    }

    /** 从检索结果定位：只加载目标那一块，上下是否还有块由查询结果决定。 */
    fun onJump(targetBlock: Int, hasMoreOlder: Boolean, hasMoreNewer: Boolean): State = State(
        blocks = listOf(targetBlock),
        hasMoreOlder = hasMoreOlder,
        hasMoreNewer = hasMoreNewer,
        initialized = true
    )

    /**
     * 数据变化后重算两侧边界。
     *
     * 不能只在首屏算一次（旧实现踩过这个坑）：别的会话写入、心跳/后台消息、自己发消息
     * 都可能让「下面还有更新的块」从 false 变 true，界面上的入口必须能重新出现。
     */
    fun onBoundaries(state: State, hasMoreOlder: Boolean, hasMoreNewer: Boolean): State =
        state.copy(hasMoreOlder = hasMoreOlder, hasMoreNewer = hasMoreNewer)

    /** 用户滚到顶部：返回新状态 + 是否真的该发查询（并发/到底/加载中都会被挡掉）。 */
    fun beginLoadOlder(state: State): Pair<State, Boolean> {
        if (!state.initialized || !state.canLoadOlder) return state to false
        return state.copy(loadingOlder = true) to true
    }

    /** 用户滚到底：向下加载一块（新增能力，旧实现只能「跳回最新」）。 */
    fun beginLoadNewer(state: State): Pair<State, Boolean> {
        if (!state.initialized || !state.canLoadNewer) return state to false
        return state.copy(loadingNewer = true) to true
    }

    /**
     * 更早的那一块到了。
     *
     * @param block 实际的块编号（可能是缺口之后的编号）；null 表示已经没有更早的块了
     */
    fun onOlderLoaded(state: State, block: Int?): State {
        if (block == null) return state.copy(loadingOlder = false, hasMoreOlder = false)
        return state.copy(
            blocks = (state.blocks + block).distinct().sorted(),
            loadingOlder = false,
            hasMoreOlder = true,
            initialized = true
        )
    }

    /**
     * 更新的那一块到了。
     *
     * @param block 实际的块编号；null 表示已经到最新块了
     */
    fun onNewerLoaded(state: State, block: Int?): State {
        if (block == null) return state.copy(loadingNewer = false, hasMoreNewer = false)
        return state.copy(
            blocks = (state.blocks + block).distinct().sorted(),
            loadingNewer = false,
            hasMoreNewer = true,
            initialized = true
        )
    }

    /** 查询失败：只收掉转圈，允许再试（不把失败当成「到底了」）。 */
    fun onLoadFailed(state: State, older: Boolean): State =
        if (older) state.copy(loadingOlder = false) else state.copy(loadingNewer = false)

    /** 「回到最新」：复位到首屏（只留最新一块）。 */
    fun onLatest(state: State, lastBlockIndex: Int?, firstBlockIndex: Int?): State =
        onInitial(lastBlockIndex, firstBlockIndex)

    /**
     * 当前窗口是不是已经在最新一块。
     *
     * 用户发消息/编辑重发之前用它判断要不要先「回到最新」：新消息永远落在最新块，
     * 停在旧块里的话，他刚说的话根本不在列表里（旧的定位窗口踩过同一个坑）。
     */
    fun isAtNewestBlock(state: State, lastBlockIndex: Int?): Boolean =
        lastBlockIndex == null || state.newestBlock == lastBlockIndex

    /** 这一块是不是已经加载过（用于「点一下已经在窗口里」的短路）。 */
    fun contains(state: State, block: Int): Boolean = block in state.blocks
}
