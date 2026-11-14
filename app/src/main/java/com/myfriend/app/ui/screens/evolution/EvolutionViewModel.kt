package com.myfriend.app.ui.screens.evolution

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.PersonaRevisionEntity
import com.myfriend.app.data.repo.EvolutionRepository
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.domain.EvolutionProposal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EvolutionUiState(
    val persona: PersonaEntity? = null,
    val revisions: List<PersonaRevisionEntity> = emptyList(),
    val generating: Boolean = false,
    val proposal: EvolutionProposal? = null,
    val accepting: Boolean = false,
    val restoringId: String? = null,
    val message: String? = null
)

@HiltViewModel
class EvolutionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personaRepository: PersonaRepository,
    private val evolutionRepository: EvolutionRepository
) : ViewModel() {
    val personaId: String = checkNotNull(savedStateHandle.get<String>("personaId"))
    private val generating = MutableStateFlow(false)
    private val proposal = MutableStateFlow<EvolutionProposal?>(null)
    private val accepting = MutableStateFlow(false)
    private val restoringId = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private var proposalJob: Job? = null

    private val activity = combine(generating, proposal, accepting, restoringId, message) { gen, prop, isAccepting, restoring, currentMessage ->
        ActivityState(gen, prop, isAccepting, restoring, currentMessage)
    }

    val uiState: StateFlow<EvolutionUiState> = combine(
        personaRepository.observeById(personaId),
        personaRepository.observeRevisions(personaId),
        activity
    ) { persona, revisions, current ->
        EvolutionUiState(
            persona = persona,
            revisions = revisions,
            generating = current.generating,
            proposal = current.proposal,
            accepting = current.accepting,
            restoringId = current.restoringId,
            message = current.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EvolutionUiState())

    fun dismissMessage() {
        message.value = null
    }

    fun generateProposal() {
        if (generating.value) return
        proposalJob = viewModelScope.launch {
            generating.value = true
            proposal.value = null
            evolutionRepository.generateProposal(personaId)
                .onSuccess { proposal.value = it }
                .onFailure { message.value = it.message ?: "生成进化提案失败" }
            generating.value = false
        }
    }

    fun cancelProposal() {
        proposalJob?.cancel()
        generating.value = false
        proposal.value = null
    }

    fun discardProposal() {
        proposal.value = null
    }

    fun acceptProposal(proposal: EvolutionProposal) {
        if (accepting.value) return
        viewModelScope.launch {
            accepting.value = true
            personaRepository.acceptEvolution(personaId, proposal.prompt, proposal.summary)
                .onSuccess {
                    this@EvolutionViewModel.proposal.value = null
                    message.value = "人格已进化。旧版本已保留，下一轮对话就会用上新版本。"
                }
                .onFailure { message.value = it.message ?: "应用进化失败" }
            accepting.value = false
        }
    }

    fun restoreRevision(revision: PersonaRevisionEntity) {
        if (restoringId.value != null) return
        viewModelScope.launch {
            restoringId.value = revision.id
            personaRepository.restoreRevision(personaId, revision.id)
                .onSuccess { message.value = "已恢复 v${revision.version} 的人格内容，并生成新版本" }
                .onFailure { message.value = it.message ?: "恢复失败" }
            restoringId.value = null
        }
    }
}

private data class ActivityState(
    val generating: Boolean,
    val proposal: EvolutionProposal?,
    val accepting: Boolean,
    val restoringId: String?,
    val message: String?
)
