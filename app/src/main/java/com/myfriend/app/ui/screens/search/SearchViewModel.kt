package com.myfriend.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.repo.ChatRepository
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.data.repo.SEARCH_PAGE_SIZE
import com.myfriend.app.data.repo.SearchHit
import com.myfriend.app.data.repo.SearchQuery
import com.myfriend.app.data.repo.SessionSearchHit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 结果分段：找「哪句话说过了」和找「哪段对话」是两种意图，分开放。 */
enum class SearchTab { MESSAGES, SESSIONS }

/**
 * 时间过滤：全部 / 最近 7 天 / 最近 30 天。
 *
 * 刻意只给这三档：更细的时间段在这个检索框里没有入口（用户想找某个具体日子时，
 * 直接翻聊天记录比先选时间段再搜更快）。文案在资源里（`search_time_*`），这里只管算边界。
 */
enum class SearchTimeFilter {
    ALL,
    LAST_7_DAYS,
    LAST_30_DAYS;

    /** 起始时间戳（闭区间下界）；[ALL] 返回 null 表示不限。 */
    fun since(now: Long): Long? = when (this) {
        ALL -> null
        LAST_7_DAYS -> now - 7L * DAY_MILLIS
        LAST_30_DAYS -> now - 30L * DAY_MILLIS
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}

/**
 * 检索页状态。
 *
 * 一个很长的 data class 是故意的：检索页的每一块（关键词、过滤、分段、分页、加载、
 * 错误、空态）都会同时出现在界面上，拆成多个 StateFlow 只会让 UI 收到不一致的中间态。
 */
data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.MESSAGES,
    /** null = 全部人格。 */
    val personaId: String? = null,
    val personas: List<PersonaEntity> = emptyList(),
    val timeFilter: SearchTimeFilter = SearchTimeFilter.ALL,
    val hits: List<SearchHit> = emptyList(),
    val sessions: List<SessionSearchHit> = emptyList(),
    /** 命中总数（SQL 统计；可能大于已加载条数）。 */
    val total: Int = 0,
    val hasMore: Boolean = false,
    /** 命中数超过候选池上限：更早的命中不会出现在结果里（界面会说明）。 */
    val truncated: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val failed: Boolean = false,
    /** 至少完整跑过一次检索（用来区分「还没搜」和「搜了但没结果」）。 */
    val searched: Boolean = false
) {
    val activePersona: PersonaEntity?
        get() = personas.firstOrNull { it.id == personaId }

    val hasAnyResult: Boolean
        get() = when (tab) {
            SearchTab.MESSAGES -> hits.isNotEmpty()
            SearchTab.SESSIONS -> sessions.isNotEmpty()
        }
}

/**
 * 检索页的 ViewModel。
 *
 * ## 为什么不用 `mapLatest` 那条 flow 链
 *
 * 结果要**追加**（滚动加载下一页），而不是每次全部重排；同时过滤条件变化必须让旧请求作废。
 * 用一个显式的 Job（新请求先 cancel 旧的）+ 一个 StateFlow，比把「追加」硬塞进
 * `scan`/`combine` 更容易读懂，也不会出现「旧查询的结果盖掉新查询」的竞态。
 *
 * ## 只读本地
 *
 * 这里只调 [ChatRepository.searchMessages] / [ChatRepository.searchSessions]，
 * 它们只读本地 Room，不发网络请求。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val personaRepository: PersonaRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var moreJob: Job? = null

    init {
        viewModelScope.launch {
            personaRepository.observeAll().collect { personas ->
                _state.update { state ->
                    // 选中的人格被删掉了：退回「全部人格」，否则过滤条件会永远匹配不到人。
                    val stillThere = state.personaId == null || personas.any { it.id == state.personaId }
                    state.copy(
                        personas = personas,
                        personaId = if (stillThere) state.personaId else null
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 输入与过滤

    fun updateQuery(value: String) {
        if (_state.value.query == value) return
        _state.update { it.copy(query = value) }
        restart(debounce = true)
    }

    fun setTab(tab: SearchTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab) }
        restart()
    }

    fun setPersona(personaId: String?) {
        if (_state.value.personaId == personaId) return
        _state.update { it.copy(personaId = personaId) }
        restart()
    }

    fun setTimeFilter(filter: SearchTimeFilter) {
        if (_state.value.timeFilter == filter) return
        _state.update { it.copy(timeFilter = filter) }
        restart()
    }

    fun retry() = restart()

    // ---------------------------------------------------------------- 检索

    private fun restart(debounce: Boolean = false) {
        searchJob?.cancel()
        moreJob?.cancel()
        _state.update { it.copy(loadingMore = false) }
        val snapshot = _state.value
        val text = snapshot.query.trim()
        if (text.isEmpty()) {
            _state.update {
                it.copy(
                    hits = emptyList(), sessions = emptyList(), total = 0, hasMore = false,
                    truncated = false, loading = false, loadingMore = false, failed = false,
                    searched = false
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MILLIS)
            val now = System.currentTimeMillis()
            val since = snapshot.timeFilter.since(now)
            _state.update { it.copy(loading = true, failed = false) }
            when (snapshot.tab) {
                SearchTab.MESSAGES -> loadFirstPageMessages(text, snapshot.personaId, since)
                SearchTab.SESSIONS -> loadSessions(text, snapshot.personaId, since)
            }
        }
    }

    private suspend fun loadFirstPageMessages(text: String, personaId: String?, since: Long?) {
        val result = chatRepository.searchMessages(
            SearchQuery(
                text = text,
                personaId = personaId,
                since = since,
                limit = SEARCH_PAGE_SIZE,
                offset = 0
            )
        )
        result.fold(
            onSuccess = { page ->
                _state.update {
                    it.copy(
                        hits = page.hits,
                        sessions = emptyList(),
                        total = page.total,
                        hasMore = page.hasMore,
                        truncated = page.truncated,
                        loading = false,
                        loadingMore = false,
                        failed = false,
                        searched = true
                    )
                }
            },
            onFailure = {
                _state.update {
                    it.copy(
                        hits = emptyList(), sessions = emptyList(), total = 0, hasMore = false,
                        truncated = false, loading = false, loadingMore = false, failed = true, searched = true
                    )
                }
            }
        )
    }

    private suspend fun loadSessions(text: String, personaId: String?, since: Long?) {
        runCatching { chatRepository.searchSessions(text, personaId, since) }
            .fold(
                onSuccess = { sessions ->
                    _state.update {
                        it.copy(
                            sessions = sessions,
                            hits = emptyList(),
                            total = sessions.size,
                            hasMore = false,
                            truncated = false,
                            loading = false,
                            loadingMore = false,
                            failed = false,
                            searched = true
                        )
                    }
                },
                onFailure = {
                    _state.update {
                        it.copy(
                            sessions = emptyList(), hits = emptyList(), total = 0, hasMore = false,
                            truncated = false, loading = false, loadingMore = false, failed = true, searched = true
                        )
                    }
                }
            )
    }

    /**
     * 滚到底：追加下一页（每页 [SEARCH_PAGE_SIZE] 条）。
     *
     * 是否真的发查询由状态位决定（没有更多 / 正在加载 / 正在首次加载 / 不在消息分段都直接返回），
     * 所以界面可以放心地在每次滚动回调里调用它。
     */
    fun loadMore() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.loadingMore) return
        if (!snapshot.hasMore || snapshot.tab != SearchTab.MESSAGES) return
        val text = snapshot.query.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
            val since = snapshot.timeFilter.since(System.currentTimeMillis())
            val result = chatRepository.searchMessages(
                SearchQuery(
                    text = text,
                    personaId = snapshot.personaId,
                    since = since,
                    limit = SEARCH_PAGE_SIZE,
                    offset = snapshot.hits.size
                )
            )
            result.fold(
                onSuccess = { page ->
                    _state.update { state ->
                        // 过滤条件在请求飞行途中变了：这一页作废。
                        if (state.query.trim() != text || state.personaId != snapshot.personaId) {
                            state.copy(loadingMore = false)
                        } else {
                            state.copy(
                                hits = state.hits + page.hits.filterNot { hit -> state.hits.any { it.messageId == hit.messageId } },
                                total = page.total,
                                hasMore = page.hasMore,
                                truncated = page.truncated,
                                loadingMore = false
                            )
                        }
                    }
                },
                onFailure = {
                    _state.update { it.copy(loadingMore = false) }
                }
            )
        }
    }

    private companion object {
        /** 输入防抖：比聊天分页的 250ms 一致，快速连打不会每敲一个字就查一次库。 */
        const val DEBOUNCE_MILLIS = 250L
    }
}
