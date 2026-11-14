package com.myfriend.app.domain

/**
 * 聊天页的「窗口化 + 上滑加载」。
 *
 * ## 为什么要有这个文件
 *
 * 0.10.0 以前聊天页 `observeMessages()` 一次读整段会话：聊了几个月之后，进聊天页就要把
 * 几千条消息（每条还带着 attachmentsJson / toolTraceJson）读进内存。这是长会话内存的
 * 最大一块，也是这次 0.10.1 明确要补的缺口。
 *
 * 现在：
 * - 首屏只取最近 [PAGE_SIZE] 条；
 * - 滚到顶部再往前取 [PAGE_SIZE] 条（以最早一条的 `(createdAt, id)` 为游标），直到最早一条；
 * - 「加载更早」的**状态机**（能不能再加载、正在加载、是不是到底了）放在这里，
 *   是纯函数，普通 JVM 单测就能钉住边界：首页 60 / 不足 60 / 恰好 60 / 加载到底 / 并发只触发一次。
 *
 * ## 不变量
 *
 * - 已读回执只覆盖「当前窗口里的用户消息」——窗口外的消息看不到，也就没有回执可显示；
 * - **未读计数与免打扰判定走全表 SQL**（`chat_sessions.unreadCount` 列、`countUserMessagesSince`），
 *   与窗口无关，所以分页不会让徽标数字或「他在睡觉」判断变形；
 * - 新消息仍然追加进窗口（窗口是「最近 N 条」的观察结果，不是一次快照）。
 */
object MessagePaging {

    /** 一页多少条：首屏与每次「加载更早」都一样。 */
    const val PAGE_SIZE = 60

    /**
     * 走查过：一页 60 条在 1080p 手机上大约 4-6 屏，够首屏「看起来是完整对话」，
     * 又不会让一次查询带回太多 JSON 列。
     */
    val pageSize: Int get() = PAGE_SIZE

    /** 分页状态：界面只读，改动都通过下面几个纯函数。 */
    data class State(
        /** 正在加载更早的一页：此时不允许再次触发（防止快速滚顶重复查询）。 */
        val loadingOlder: Boolean = false,
        /** 还有更早的消息。首屏未知时按「有」处理，等第一批数据回来看条数。 */
        val hasMoreOlder: Boolean = true,
        /** 首屏是否已经回过一次（[onFirstWindow] 之后为真）。 */
        val initialized: Boolean = false,
        /**
         * 定位到很久以前的消息之后，窗口末尾**之后**还有更新的消息。
         *
         * 0.11.0 起定位只加载目标附近一个有界窗口（见 [MessageJump]），所以「下面还有更近的话」
         * 是一个真实存在的状态：界面在列表底部给一个「更新的消息」入口，点了就回到最新一屏。
         */
        val hasMoreNewer: Boolean = false
    ) {
        val canLoadOlder: Boolean get() = hasMoreOlder && !loadingOlder
    }

    /** 一次定位要观察的窗口：[start] 是窗口起点，[rows] 是窗口条数上界。 */
    data class Window(
        val start: Cursor,
        val rows: Int,
        val hasMoreOlder: Boolean,
        val hasMoreNewer: Boolean
    )

    /**
     * 首屏窗口到了。
     *
     * 只有**首屏**（还没设过游标）能靠「不足一页」判断到底了；之后的观察结果是整个
     * 已加载区间，条数变多不代表「还有更早的」——这一点由 ViewModel 负责（只在游标为空时调这里）。
     *
     * 每次都要重算：窗口是滑动的（新消息进来时最上面那条会悄悄滑出 60 条），
     * 如果只在第一次算，用户会看不到「上面还有更早的消息」这条提示（审计 P2-14）。
     */
    fun onFirstWindow(state: State, rows: Int): State =
        state.copy(initialized = true, hasMoreOlder = rows >= PAGE_SIZE)

    /** 从检索结果定位成功：窗口起点与「还有更早/更新」都按 [window] 定。 */
    fun onJumped(state: State, window: Window): State = state.copy(
        loadingOlder = false,
        initialized = true,
        hasMoreOlder = window.hasMoreOlder,
        hasMoreNewer = window.hasMoreNewer
    )

    /** 回到最新一屏（定位窗口之外的「更新消息」入口）：状态整体复位。 */
    fun onLatest(state: State): State = State()

    /** 用户滚到顶部，想加载更早：返回新的状态 + 是否真的该发查询。 */
    fun beginLoadOlder(state: State): Pair<State, Boolean> {
        if (!state.canLoadOlder) return state to false
        return state.copy(loadingOlder = true) to true
    }

    /**
     * 更早那一页回来了。
     *
     * - 取满一页：可能还有，保持 hasMoreOlder；
     * - 不足一页（含 0 条）：到底了。
     */
    fun onOlderPage(state: State, rows: Int): State =
        state.copy(loadingOlder = false, hasMoreOlder = rows >= PAGE_SIZE)

    /** 查询失败：收掉转圈，允许再试（不把「失败」当成到底）。 */
    fun onLoadFailed(state: State): State = state.copy(loadingOlder = false)

    /** 游标：已加载区间里最早的那一条。 */
    data class Cursor(val createdAt: Long, val id: String)

    /**
     * 取游标：按 `(createdAt, id)` 升序的列表里的第一条。
     * 传进来的列表为空（还没有消息）时返回 null。
     */
    fun <T> oldestCursor(
        messages: List<T>,
        createdAt: (T) -> Long,
        id: (T) -> String
    ): Cursor? {
        val first = messages.firstOrNull() ?: return null
        return Cursor(createdAt(first), id(first))
    }
}
