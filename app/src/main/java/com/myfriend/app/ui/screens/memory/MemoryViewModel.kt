package com.myfriend.app.ui.screens.memory

import com.myfriend.app.ui.common.ErrorText
import com.myfriend.app.util.androidStringLookup
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.myfriend.app.R
import com.myfriend.app.data.local.MemoryCategory
import com.myfriend.app.data.local.MemoryConsolidationDao
import com.myfriend.app.data.local.MemoryConsolidationEntity
import com.myfriend.app.data.local.MemoryEntity
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.repo.MemoryConsolidationWorker
import com.myfriend.app.data.repo.MemoryRepository
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.domain.MemoryDrawer
import com.myfriend.app.domain.MemoryScreening
import com.myfriend.app.domain.MemoryTier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 列表排序方式：重要度沿用数据库默认顺序，时间只看最近更新。固定永远排最前。 */
enum class MemorySort(@StringRes val labelRes: Int) {
    IMPORTANCE(R.string.memory_sort_importance),
    TIME(R.string.memory_sort_time)
}

/** 一个抽屉在当前筛选/排序下要显示的一段。 */
data class MemoryDrawerSection(
    val drawer: MemoryDrawer,
    @StringRes val titleRes: Int,
    @StringRes val emptyHintRes: Int,
    val memories: List<MemoryEntity>
)

/**
 * 三档记忆的数量：还在用 / 沉下去了 / 已归档。
 *
 * 归档的那一栏在界面上要解释清楚「不参与上下文，只有他认真回忆时才可能翻到」，
 * 所以这里顺便给出总数，卡片上不必再各自加一遍。
 */
data class MemoryTierCounts(
    val active: Int = 0,
    val dormant: Int = 0,
    val archived: Int = 0
) {
    val total: Int get() = active + dormant + archived
}

data class MemoryUiState(
    val persona: PersonaEntity? = null,
    /** 全部记忆（已排序），不受抽屉筛选影响。 */
    val memories: List<MemoryEntity> = emptyList(),
    val extracting: Boolean = false,
    val message: String? = null,
    /** 当前抽屉筛选，null 表示「全部」。 */
    val filter: MemoryDrawer? = null,
    val sort: MemorySort = MemorySort.IMPORTANCE,
    /** 按抽屉分组后的可见段，已应用筛选与排序；空段也保留，用来显示引导文案。 */
    val sections: List<MemoryDrawerSection> = emptyList(),
    /** 记忆的健康度：还在用 / 沉下去了 / 已归档。 */
    val tierCounts: MemoryTierCounts = MemoryTierCounts(),
    /** 最近一次整理留痕；为 null 时界面写「还没整理过」。 */
    val lastConsolidation: MemoryConsolidationEntity? = null,
    /** 「现在整理一次」正在后台跑：按钮禁用并显示「整理中…」。 */
    val consolidating: Boolean = false,
    /** 手动整理已经排上、还没轮到跑（ENQUEUED）。 */
    val consolidationQueued: Boolean = false
) {
    val isEmpty: Boolean get() = memories.isEmpty()
    val visibleCount: Int get() = sections.sumOf { it.memories.size }

    /** 1.9：记忆内置开启，手动整理随时可用（正在跑时界面自己会禁用按钮）。 */
    val canConsolidate: Boolean get() = true
}

/** 抽屉固定顺序：关于你 → 他是什么样的人 → 共同经历与约定。 */
private val MEMORY_DRAWER_ORDER = listOf(MemoryDrawer.USER, MemoryDrawer.SELF, MemoryDrawer.EVENT)

@StringRes
private fun drawerTitle(drawer: MemoryDrawer): Int = when (drawer) {
    MemoryDrawer.USER -> R.string.memory_drawer_user
    MemoryDrawer.SELF -> R.string.memory_drawer_self
    MemoryDrawer.EVENT -> R.string.memory_drawer_event
}

@StringRes
private fun drawerEmptyHint(drawer: MemoryDrawer): Int = when (drawer) {
    MemoryDrawer.USER -> R.string.memory_empty_hint_user
    MemoryDrawer.SELF -> R.string.memory_empty_hint_self
    MemoryDrawer.EVENT -> R.string.memory_empty_hint_event
}

/** 优先用落库的 drawer，解析失败或为空时按 category 推断。 */
private fun MemoryEntity.drawerOrInferred(): MemoryDrawer =
    drawer?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { raw -> runCatching { MemoryDrawer.valueOf(raw.uppercase()) }.getOrNull() }
        ?: MemoryScreening.drawerOf(category.name)

/** 分组、筛选、排序都从这里派生，写操作仍只走 repository。 */
private data class MemoryContent(
    val persona: PersonaEntity?,
    val memories: List<MemoryEntity>,
    val extracting: Boolean,
    val message: String?,
    val lastConsolidation: MemoryConsolidationEntity?,
    val consolidating: Boolean,
    val consolidationQueued: Boolean = false
) {
    fun toUiState(filter: MemoryDrawer?, sort: MemorySort): MemoryUiState {
        val ordered = memories.sortedWith(
            when (sort) {
                MemorySort.IMPORTANCE -> compareByDescending<MemoryEntity> { it.pinned }
                    .thenByDescending { it.importance }
                    .thenByDescending { it.updatedAt }

                MemorySort.TIME -> compareByDescending<MemoryEntity> { it.pinned }
                    .thenByDescending { it.updatedAt }
            }
        )
        val sections = MEMORY_DRAWER_ORDER
            .filter { filter == null || it == filter }
            .map { drawer ->
                MemoryDrawerSection(
                    drawer = drawer,
                    titleRes = drawerTitle(drawer),
                    emptyHintRes = drawerEmptyHint(drawer),
                    memories = ordered.filter { it.drawerOrInferred() == drawer }
                )
            }
        return MemoryUiState(
            persona = persona,
            memories = ordered,
            extracting = extracting,
            message = message,
            filter = filter,
            sort = sort,
            sections = sections,
            tierCounts = ordered.tierCounts(),
            lastConsolidation = lastConsolidation,
            consolidating = consolidating,
            consolidationQueued = consolidationQueued
        )
    }
}

/** 三档各多少条：从列表本身数，数据库里刚被整理器改过状态时卡片会立刻跟着变。 */
private fun List<MemoryEntity>.tierCounts(): MemoryTierCounts = fold(MemoryTierCounts()) { acc, memory ->
    when (MemoryTier.parse(memory.status)) {
        MemoryTier.ACTIVE -> acc.copy(active = acc.active + 1)
        MemoryTier.DORMANT -> acc.copy(dormant = acc.dormant + 1)
        MemoryTier.ARCHIVED -> acc.copy(archived = acc.archived + 1)
    }
}

/** 主数据：人格与记忆。1.9 起没有「记忆开关」这类设置要一起读了。 */
private data class MemoryCore(
    val persona: PersonaEntity?,
    val memories: List<MemoryEntity>
)

/** 会变的一点点状态：正在抽取、待提示的话、是否正在整理。 */
private data class MemoryTransient(
    val extracting: Boolean,
    val message: String?,
    val consolidating: Boolean,
    val consolidationQueued: Boolean
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    personaRepository: PersonaRepository,
    private val memoryRepository: MemoryRepository,
    consolidationDao: MemoryConsolidationDao,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val personaId: String = checkNotNull(savedStateHandle.get<String>("personaId"))
    private val extracting = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow<MemoryDrawer?>(null)
    private val sort = MutableStateFlow(MemorySort.IMPORTANCE)

    /** 「现在整理一次」是否在跑。由 WorkManager 的任务状态推出来，不靠本地乐观标记。 */
    private val consolidating = MutableStateFlow(false)
    private val consolidationQueued = MutableStateFlow(false)

    /** 最近一次整理留痕：订下去，后台整理完卡片上的数字和时间会自己刷新。 */
    private val lastConsolidation = consolidationDao.observeRecent(personaId, limit = 1)
        .map { runs -> runs.firstOrNull() }

    private val core = combine(
        personaRepository.observeById(personaId),
        memoryRepository.observe(personaId)
    ) { persona, memories -> MemoryCore(persona, memories) }

    private val transient = combine(extracting, message, consolidating, consolidationQueued) { isExtracting, currentMessage, isConsolidating, queued ->
        MemoryTransient(isExtracting, currentMessage, isConsolidating, queued)
    }

    private val content = combine(core, transient, lastConsolidation) { base, status, recent ->
        MemoryContent(
            persona = base.persona,
            memories = base.memories,
            extracting = status.extracting,
            message = status.message,
            lastConsolidation = recent,
            consolidating = status.consolidating,
            consolidationQueued = status.consolidationQueued
        )
    }

    val uiState: StateFlow<MemoryUiState> = combine(content, filter, sort) { data, currentFilter, currentSort ->
        data.toUiState(currentFilter, currentSort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

    /** WorkManager 没初始化好时（极少数）为 null：此时「现在整理一次」只提示、不假装在忙。 */
    private val workManager: WorkManager? by lazy {
        runCatching { WorkManager.getInstance(context) }.getOrNull()
    }

    /** 这一轮跟踪到的手动任务 id：只报一次结果，也用来分辨「新排的任务」和上一轮留下的旧任务。 */
    private var trackedManualWorkId: UUID? = null
    private var awaitingManualResult = false

    /** 排队看门狗：排上了却迟迟不跑时收场。 */
    private var queueWatchdog: Job? = null

    init {
        observeManualConsolidation()
    }

    fun dismissMessage() {
        message.value = null
    }

    fun setFilter(drawer: MemoryDrawer?) {
        filter.value = drawer
    }

    fun toggleSort() {
        sort.value = if (sort.value == MemorySort.IMPORTANCE) MemorySort.TIME else MemorySort.IMPORTANCE
    }

    fun addMemory(content: String, category: MemoryCategory, importance: Int) {
        viewModelScope.launch {
            memoryRepository.add(personaId, content, category, importance)
                .onSuccess { message.value = context.getString(R.string.memory_added) }
                .onFailure { message.value = ErrorText.of(context, it, R.string.memory_add_failed) }
        }
    }

    fun updateMemory(memory: MemoryEntity, content: String, category: MemoryCategory, importance: Int) {
        viewModelScope.launch {
            memoryRepository.update(memory, content, category, importance)
                .onSuccess { message.value = context.getString(R.string.memory_updated) }
                .onFailure { message.value = ErrorText.of(context, it, R.string.memory_update_failed) }
        }
    }

    fun deleteMemory(memory: MemoryEntity) {
        viewModelScope.launch {
            runCatching { memoryRepository.delete(memory) }
                .onSuccess { message.value = context.getString(R.string.memory_deleted) }
                .onFailure {
                    message.value = ErrorText.of(context, it, R.string.memory_delete_failed)
                }
        }
    }

    fun togglePinned(memory: MemoryEntity) {
        viewModelScope.launch {
            memoryRepository.setPinned(memory, !memory.pinned)
        }
    }

    fun extractFromChats() {
        if (extracting.value) return
        viewModelScope.launch {
            extracting.value = true
            memoryRepository.extractFromLatestSession(personaId)
                .onSuccess { message.value = extractionMessage(it) }
                .onFailure {
                    val detail = ErrorText.of(context, it, R.string.memory_extract_failed_detail)
                    message.value = context.getString(R.string.memory_extract_failed, detail)
                }
            extracting.value = false
        }
    }

    /**
     * 立刻整理一次：交给 WorkManager 的一次性任务（工作名 [MemoryConsolidationWorker.WORK_NAME_MANUAL]，
     * 和每天那次周期整理互不覆盖），退出界面也会在后台跑完。
     *
     * 结果不在这里等：任务跑完由 [observeManualConsolidation] 读到，再用 snackbar 说一句人话。
     */
    fun runConsolidationNow() {
        if (consolidating.value) return
        val manager = workManager
        if (manager == null) {
            message.value = context.getString(R.string.memory_enqueue_failed)
            return
        }
        MemoryConsolidationWorker.enqueueOneTime(context)
        // enqueue 是异步落库的：给它一拍再确认任务真的排上了，排不上就别让按钮装作在忙。
        viewModelScope.launch {
            delay(ENQUEUE_CONFIRM_MILLIS)
            if (trackedManualWorkId == null && !consolidating.value && !awaitingManualResult) {
                val infos = runCatching {
                    manager.getWorkInfosForUniqueWorkFlow(MemoryConsolidationWorker.WORK_NAME_MANUAL).first()
                }.getOrDefault(emptyList())
                if (infos.isEmpty()) message.value = context.getString(R.string.memory_enqueue_failed)
            }
        }
    }

    /**
     * 盯着手动整理那一条任务。
     *
     * 用 Flow 而不是轮询：任务一进 RUNNING 按钮就变成「整理中…」，一结束就弹结果。
     * 离开界面再回来也能接上还没跑完的那次（id 记在 ViewModel 里，只报一次结果）。
     */
    private fun observeManualConsolidation() {
        val manager = workManager ?: return
        viewModelScope.launch {
            manager.getWorkInfosForUniqueWorkFlow(MemoryConsolidationWorker.WORK_NAME_MANUAL)
                // WorkManager 的库还没开好时当作「没有手动整理」，不要因为读不到状态而崩溃。
                .catch { }
                .collect { infos -> onManualWorkInfos(infos) }
        }
    }

    private fun onManualWorkInfos(infos: List<WorkInfo>) {
        val tracked = trackedManualWorkId?.let { id -> infos.firstOrNull { it.id == id } }
        val target = tracked ?: infos.firstOrNull { !it.state.isFinished }
        if (target == null) {
            // 没有任何手动任务在跑：按钮放开，别把「等下一次」误当成「还在整理」。
            resetManualState()
            return
        }
        trackedManualWorkId = target.id
        if (target.state.isFinished) {
            queueWatchdog?.cancel()
            consolidating.value = false
            consolidationQueued.value = false
            if (awaitingManualResult) {
                awaitingManualResult = false
                // 报完就忘掉这条，同一个结果不会重复弹。
                trackedManualWorkId = null
                message.value = manualResultMessage(target)
            }
            return
        }
        awaitingManualResult = true
        // 只有真的在跑才叫「整理中」；排上了还没轮到（ENQUEUED / BLOCKED）只是「排队中」，
        // 否则任务一旦被系统压住，按钮就会永远停在转圈上。
        val running = target.state == WorkInfo.State.RUNNING
        consolidating.value = running
        consolidationQueued.value = !running
        if (!running) startQueueWatchdog()
    }

    /**
     * 排队超过一分钟还没轮到，就别再假装在忙：撤掉这条任务、放开按钮，让用户再点一次。
     * （手动整理不挂联网约束，正常情况下几秒内就该进 RUNNING。）
     */
    private fun startQueueWatchdog() {
        if (queueWatchdog?.isActive == true) return
        queueWatchdog = viewModelScope.launch {
            delay(QUEUE_WATCHDOG_MILLIS)
            if (!consolidationQueued.value) return@launch
            runCatching { workManager?.cancelUniqueWork(MemoryConsolidationWorker.WORK_NAME_MANUAL) }
            resetManualState()
            message.value = context.getString(R.string.memory_queue_failed)
        }
    }

    private fun resetManualState() {
        queueWatchdog?.cancel()
        consolidating.value = false
        consolidationQueued.value = false
        trackedManualWorkId = null
        awaitingManualResult = false
    }

    /** 把 WorkManager 回传的 outputData 翻成一句中文。任务自己挂了（没有 status）也说得出口。 */
    private fun manualResultMessage(info: WorkInfo): String {
        // 任务被系统打断时 outputData 是空的，getString 会返回 null，直接当「没结果」处理。
        val data = info.outputData
        val status = runCatching { data.getString(MemoryConsolidationWorker.KEY_STATUS) }.getOrNull()
        return MemoryHealthText.manualResultMessage(
            status = status,
            kept = data.getInt(MemoryConsolidationWorker.KEY_KEPT, 0),
            merged = data.getInt(MemoryConsolidationWorker.KEY_MERGED, 0),
            archived = data.getInt(MemoryConsolidationWorker.KEY_ARCHIVED, 0),
            forgotten = data.getInt(MemoryConsolidationWorker.KEY_FORGOTTEN, 0),
            strings = androidStringLookup(context)
        )
    }

    private companion object {
        /** enqueue 之后等多久再确认任务是否真的排上（WorkManager 落库是异步的，很短）。 */
        const val ENQUEUE_CONFIRM_MILLIS = 800L

        /** 排上多久还没开跑就认为「没跑起来」：给系统排队留足余量，又不至于让人一直等。 */
        const val QUEUE_WATCHDOG_MILLIS = 60_000L
    }

    /** 「记忆已更新（+2）」这类结果句：数量在数据层，说法在资源里。 */
    private fun extractionMessage(result: com.myfriend.app.data.repo.MemoryExtractionResult): String =
        when (result.outcome) {
            com.myfriend.app.data.repo.MemoryExtractionOutcome.ADDED ->
                context.getString(R.string.memory_extract_result_added, result.added)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.REINFORCED ->
                context.getString(R.string.memory_extract_result_reinforced)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.ECHOED ->
                context.getString(R.string.memory_extract_result_echoed)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.NONE ->
                context.getString(R.string.memory_extract_result_none)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.NOT_ENOUGH_CHAT ->
                context.getString(R.string.memory_extract_result_not_enough)
            com.myfriend.app.data.repo.MemoryExtractionOutcome.INTERVAL_NOT_REACHED ->
                run {
                    val every = result.every ?: 0
                    context.resources.getQuantityString(
                        R.plurals.memory_extract_result_interval,
                        every,
                        every
                    )
                }
        }
}
