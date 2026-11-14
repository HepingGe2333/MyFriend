package com.myfriend.app.ui.screens.proactive

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.R
import com.myfriend.app.data.local.AgentIntentEntity
import com.myfriend.app.data.local.AgentTickEntity
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.ProactiveKind
import com.myfriend.app.data.local.ProactiveRuleEntity
import com.myfriend.app.data.repo.AgentIntentRepository
import com.myfriend.app.data.repo.MoodRepository
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.data.repo.PresenceRepository
import com.myfriend.app.data.repo.ProactiveRepository
import com.myfriend.app.domain.MoodState
import com.myfriend.app.domain.Presence
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProactiveUiState(
    val rules: List<ProactiveRuleEntity> = emptyList(),
    val personas: List<PersonaEntity> = emptyList(),
    val message: String? = null,
    /** 他此刻的心情。 */
    val mood: MoodState = MoodState(),
    /** 他此刻在做什么。也是他自己的状态，这一页同样只显示、不提供设置。 */
    val presence: Presence = Presence(),
    /** 他最近几次「想」的留痕，最近的在前。 */
    val thoughts: List<AgentTickEntity> = emptyList(),
    /** 他给自己留的念头（还没到窗口 / 正在窗口里）。 */
    val intents: List<AgentIntentEntity> = emptyList()
) {
    /** 没有名字时用资源里的兜底称呼；需要 Context 才能取串，所以由调用方传进来。 */
    fun personaName(id: String, context: Context): String =
        personas.firstOrNull { it.id == id }?.name
            ?: context.getString(R.string.proactive_persona_unnamed)
    fun persona(id: String): PersonaEntity? = personas.firstOrNull { it.id == id }
}

sealed interface ProactiveEvent {
    data class OpenChat(val sessionId: String) : ProactiveEvent
}

@HiltViewModel
class ProactiveViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val proactiveRepository: ProactiveRepository,
    private val intentRepository: AgentIntentRepository,
    private val personaRepository: PersonaRepository,
    private val moodRepository: MoodRepository,
    private val presenceRepository: PresenceRepository
) : ViewModel() {

    private val personaId: String? = savedStateHandle.get<String>("personaId")?.takeIf { it.isNotBlank() }
    private val message = MutableStateFlow<String?>(null)
    private val events = Channel<ProactiveEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()
    /** 已经自动生效过的旧「待确认」规则，防止每次列表刷新都重复写库。 */
    private val autoActivated = mutableSetOf<String>()

    private val personasFlow = personaRepository.observeAll()

    private val rulesFlow = if (personaId == null) {
        proactiveRepository.observeAll()
    } else {
        proactiveRepository.observeByPersona(personaId)
    }

    /** 没有指定人格时看「当前默认人格」的思考记录，指定了就看这个人的。 */
    private val thoughtsFlow = personasFlow.flatMapLatest { personas ->
        val target = personaId ?: personas.firstOrNull { it.isDefault }?.id ?: personas.firstOrNull()?.id
        if (target == null) kotlinx.coroutines.flow.flowOf(emptyList()) else intentRepository.observeTicks(target, 8)
    }

    private val moodFlow = personasFlow.flatMapLatest { personas ->
        val target = personaId ?: personas.firstOrNull { it.isDefault }?.id ?: personas.firstOrNull()?.id
        if (target == null) kotlinx.coroutines.flow.flowOf(MoodState()) else moodRepository.observe(target)
    }

    /** 「他正在……」：和心情同一个目标人格，界面只显示。 */
    private val presenceFlow = personasFlow.flatMapLatest { personas ->
        val target = personaId ?: personas.firstOrNull { it.isDefault }?.id ?: personas.firstOrNull()?.id
        if (target == null) kotlinx.coroutines.flow.flowOf(Presence()) else presenceRepository.observe(target)
    }

    private val intentsFlow = personasFlow.flatMapLatest { personas ->
        val target = personaId ?: personas.firstOrNull { it.isDefault }?.id ?: personas.firstOrNull()?.id
        if (target == null) kotlinx.coroutines.flow.flowOf(emptyList()) else intentRepository.observeIntents(target, 6)
    }

    val uiState: StateFlow<ProactiveUiState> = combine(
        combine(rulesFlow, personasFlow, message) { rules, personas, currentMessage ->
            Triple(rules, personas, currentMessage)
        },
        thoughtsFlow,
        intentsFlow,
        moodFlow,
        presenceFlow
    ) { (rules, personas, currentMessage), thoughts, intents, mood, presence ->
        ProactiveUiState(
            rules = rules,
            personas = personas,
            message = currentMessage,
            mood = mood,
            presence = presence,
            thoughts = thoughts,
            intents = intents.filter { it.status.name == "PENDING" }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProactiveUiState())

    fun dismissMessage() {
        message.value = null
    }

    fun createManual(
        personaId: String,
        kind: ProactiveKind,
        timeOfDayMinutes: Int?,
        intervalMinutes: Int?,
        hint: String,
        note: String,
        notificationPermissionGranted: Boolean
    ) {
        viewModelScope.launch {
            val result = proactiveRepository.createManual(
                personaId = personaId,
                sessionId = null,
                kind = kind,
                timeOfDayMinutes = timeOfDayMinutes,
                intervalMinutes = intervalMinutes,
                hint = hint,
                note = note
            )
            result.onSuccess {
                message.value = if (notificationPermissionGranted) {
                    context.getString(R.string.proactive_created_on)
                } else {
                    context.getString(R.string.proactive_created_permission_needed)
                }
            }.onFailure { error ->
                message.value = error.message ?: context.getString(R.string.proactive_create_failed)
            }
        }
    }

    fun confirm(ruleId: String) {
        viewModelScope.launch {
            proactiveRepository.confirm(ruleId)
                .onSuccess { message.value = context.getString(R.string.proactive_created_on) }
                .onFailure { message.value = it.message ?: context.getString(R.string.proactive_enable_failed) }
        }
    }

    /**
     * 旧的「待确认」规则在新语义下不需要谁批准：看到就直接让它生效，
     * 页面上一律按「已经安排好了」展示。用集合去重，避免每次列表刷新都重写一遍。
     */
    fun activateLegacyPending(rules: List<ProactiveRuleEntity>) {
        val targets = rules.filter { it.pending && autoActivated.add(it.id) }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.forEach { proactiveRepository.setEnabled(it.id, true) }
        }
    }

    fun setEnabled(rule: ProactiveRuleEntity, enabled: Boolean) {
        viewModelScope.launch {
            proactiveRepository.setEnabled(rule.id, enabled)
                .onSuccess {
                    message.value = context.getString(
                        if (enabled) R.string.proactive_enabled else R.string.proactive_paused
                    )
                }
                .onFailure { message.value = it.message ?: context.getString(R.string.proactive_toggle_failed) }
        }
    }

    fun triggerNow(ruleId: String) {
        viewModelScope.launch {
            message.value = context.getString(R.string.proactive_trigger_running)
            proactiveRepository.triggerNow(ruleId)
                .onSuccess { message.value = context.getString(R.string.proactive_trigger_sent) }
                .onFailure { message.value = it.message ?: context.getString(R.string.proactive_trigger_failed) }
        }
    }

    fun delete(ruleId: String) {
        viewModelScope.launch {
            proactiveRepository.delete(ruleId)
            message.value = context.getString(R.string.proactive_deleted)
        }
    }

    fun openChat(rule: ProactiveRuleEntity) {
        val sessionId = rule.sessionId
        if (sessionId == null) {
            message.value = context.getString(R.string.proactive_no_session_yet)
            return
        }
        viewModelScope.launch { events.send(ProactiveEvent.OpenChat(sessionId)) }
    }

    companion object {
        val KINDS = ProactiveKind.entries
    }
}
