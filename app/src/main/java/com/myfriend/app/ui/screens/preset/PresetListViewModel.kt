package com.myfriend.app.ui.screens.preset

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.R
import com.myfriend.app.data.local.PersonaPresetEntity
import com.myfriend.app.data.repo.PresetRepository
import com.myfriend.app.domain.ExportFile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PresetListUiState(
    val presets: List<PersonaPresetEntity> = emptyList(),
    val category: String? = null,
    val busy: Boolean = false,
    val message: String? = null
) {
    val categories: List<String>
        get() = presets.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

    val filtered: List<PersonaPresetEntity>
        get() = category?.let { selected -> presets.filter { it.category == selected } } ?: presets
}

sealed interface PresetListEvent {
    data class ExportReady(val file: ExportFile) : PresetListEvent
    data class OpenEditor(val presetId: String?) : PresetListEvent
    data class PersonaCreated(val personaId: String) : PresetListEvent
}

@HiltViewModel
class PresetListViewModel @Inject constructor(
    private val presetRepository: PresetRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val category = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val events = Channel<PresetListEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val uiState: StateFlow<PresetListUiState> = combine(
        presetRepository.observeAll(),
        category,
        busy,
        message
    ) { presets, selectedCategory, isBusy, currentMessage ->
        PresetListUiState(presets, selectedCategory, isBusy, currentMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PresetListUiState())

    fun setCategory(value: String?) {
        category.value = value
    }

    fun dismissMessage() {
        message.value = null
    }

    fun newPreset() {
        viewModelScope.launch { events.send(PresetListEvent.OpenEditor(null)) }
    }

    fun editPreset(presetId: String) {
        viewModelScope.launch { events.send(PresetListEvent.OpenEditor(presetId)) }
    }

    fun duplicate(presetId: String) {
        viewModelScope.launch {
            busy.value = true
            presetRepository.duplicate(presetId)
                .onSuccess { message.value = context.getString(R.string.preset_duplicated) }
                .onFailure { message.value = it.message ?: context.getString(R.string.preset_duplicate_failed) }
            busy.value = false
        }
    }

    fun delete(presetId: String) {
        viewModelScope.launch {
            presetRepository.delete(presetId)
            message.value = context.getString(R.string.preset_deleted)
        }
    }

    fun createPersona(presetId: String) {
        viewModelScope.launch {
            busy.value = true
            presetRepository.createPersonaFromPreset(presetId)
                .onSuccess { personaId ->
                    busy.value = false
                    events.send(PresetListEvent.PersonaCreated(personaId))
                }
                .onFailure {
                    busy.value = false
                    message.value = it.message ?: context.getString(R.string.preset_create_persona_failed)
                }
        }
    }

    fun exportPreset(presetId: String) {
        viewModelScope.launch {
            presetRepository.exportOne(presetId)
                .onSuccess { events.send(PresetListEvent.ExportReady(it)) }
                .onFailure { message.value = it.message ?: context.getString(R.string.preset_export_error) }
        }
    }

    fun exportAll() {
        viewModelScope.launch {
            presetRepository.exportAll()
                .onSuccess { events.send(PresetListEvent.ExportReady(it)) }
                .onFailure { message.value = it.message ?: context.getString(R.string.preset_export_error) }
        }
    }

    fun import(raw: String) {
        viewModelScope.launch {
            busy.value = true
            presetRepository.import(raw)
                .onSuccess { message.value = it.message }
                .onFailure { message.value = it.message ?: context.getString(R.string.preset_import_failed) }
            busy.value = false
        }
    }
}
