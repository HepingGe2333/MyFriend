package com.myfriend.app.ui.screens.preset

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.ui.common.ErrorText
import com.myfriend.app.R
import com.myfriend.app.data.repo.PresetDraft
import com.myfriend.app.data.repo.PresetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PresetForm(
    val name: String = "",
    val description: String = "",
    val prompt: String = "",
    val category: String = "通用",
    val avatarSeed: Int = 0
)

data class PresetEditorUiState(
    val form: PresetForm = PresetForm(),
    val saving: Boolean = false,
    val loaded: Boolean = false,
    val isNew: Boolean = true
)

sealed interface PresetEditorEvent {
    data class Saved(val presetId: String) : PresetEditorEvent
    data class Message(val text: String) : PresetEditorEvent
}

@HiltViewModel
class PresetEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val presetRepository: PresetRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val presetId: String? = savedStateHandle.get<String>("presetId")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(
        PresetEditorUiState(loaded = presetId == null, isNew = presetId == null)
    )
    val state: StateFlow<PresetEditorUiState> = _state

    private val events = Channel<PresetEditorEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        if (presetId != null) {
            viewModelScope.launch {
                val preset = presetRepository.get(presetId)
                if (preset != null) {
                    _state.update {
                        it.copy(
                            form = PresetForm(
                                name = preset.name,
                                description = preset.description,
                                prompt = preset.prompt,
                                category = preset.category,
                                avatarSeed = preset.avatarSeed
                            ),
                            loaded = true
                        )
                    }
                } else {
                    _state.update { it.copy(loaded = true) }
                    events.send(PresetEditorEvent.Message(context.getString(R.string.preset_editor_not_found)))
                }
            }
        }
    }

    fun update(transform: (PresetForm) -> PresetForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val form = current.form
        if (form.name.isBlank()) {
            viewModelScope.launch { events.send(PresetEditorEvent.Message(context.getString(R.string.preset_editor_name_required))) }
            return
        }
        if (form.prompt.isBlank()) {
            viewModelScope.launch { events.send(PresetEditorEvent.Message(context.getString(R.string.preset_editor_prompt_required))) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val result = presetRepository.save(
                PresetDraft(
                    id = presetId,
                    name = form.name,
                    description = form.description,
                    prompt = form.prompt,
                    category = form.category,
                    avatarUri = null,
                    avatarSeed = form.avatarSeed
                )
            )
            _state.update { it.copy(saving = false) }
            result.onSuccess { events.send(PresetEditorEvent.Saved(it)) }
                .onFailure { events.send(PresetEditorEvent.Message(ErrorText.of(context, it, R.string.preset_editor_save_failed))) }
        }
    }
}
