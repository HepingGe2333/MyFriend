package com.myfriend.app.ui.screens.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.repo.ApiConfigRepository
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.data.repo.PresetRepository
import com.myfriend.app.domain.ExportFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PersonaListUiState(
    val personas: List<PersonaEntity> = emptyList(),
    val configs: List<ApiConfigEntity> = emptyList(),
    val category: String? = null,
    val busy: Boolean = false,
    val message: String? = null
) {
    val categories: List<String>
        get() = personas.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    val filtered: List<PersonaEntity>
        get() = category?.let { selected -> personas.filter { it.category == selected } } ?: personas
}

sealed interface PersonaListEvent {
    data class ExportReady(val file: ExportFile) : PersonaListEvent
    data class OpenEditor(val personaId: String) : PersonaListEvent
    data object CreateNew : PersonaListEvent
}

@HiltViewModel
class PersonaListViewModel @Inject constructor(
    private val personaRepository: PersonaRepository,
    private val presetRepository: PresetRepository,
    apiConfigRepository: ApiConfigRepository
) : ViewModel() {
    private val category = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val events = Channel<PersonaListEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val uiState: StateFlow<PersonaListUiState> = combine(
        personaRepository.observeAll(),
        apiConfigRepository.observeAll(),
        category,
        busy,
        message
    ) { personas, configs, selectedCategory, isBusy, currentMessage ->
        PersonaListUiState(personas, configs, selectedCategory, isBusy, currentMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonaListUiState())

    fun setCategory(value: String?) {
        category.value = value
    }

    fun dismissMessage() {
        message.value = null
    }

    fun saveAsPreset(personaId: String) {
        viewModelScope.launch {
            presetRepository.savePersonaAsPreset(personaId)
                .onSuccess { message.value = "已保存到预设库" }
                .onFailure { message.value = it.message ?: "保存到预设库失败" }
        }
    }

    fun setDefault(personaId: String) {
        viewModelScope.launch {
            busy.value = true
            personaRepository.setDefault(personaId)
                .onSuccess { message.value = "已设为默认人格" }
                .onFailure { message.value = it.message ?: "设置失败" }
            busy.value = false
        }
    }

    fun duplicate(personaId: String) {
        viewModelScope.launch {
            busy.value = true
            personaRepository.duplicate(personaId)
                .onSuccess { message.value = "人格已复制" }
                .onFailure { message.value = it.message ?: "复制失败" }
            busy.value = false
        }
    }

    fun delete(personaId: String) {
        viewModelScope.launch {
            busy.value = true
            runCatching { personaRepository.delete(personaId) }
                .onSuccess { message.value = "人格已删除" }
                .onFailure { message.value = it.message ?: "删除失败" }
            busy.value = false
        }
    }

    fun exportAll() {
        viewModelScope.launch {
            busy.value = true
            personaRepository.exportPersonas()
                .onSuccess { events.send(PersonaListEvent.ExportReady(it)) }
                .onFailure { message.value = it.message ?: "导出失败" }
            busy.value = false
        }
    }

    fun exportPersona(personaId: String) {
        viewModelScope.launch {
            busy.value = true
            personaRepository.exportPersonas(listOf(personaId))
                .onSuccess { events.send(PersonaListEvent.ExportReady(it)) }
                .onFailure { message.value = it.message ?: "导出失败" }
            busy.value = false
        }
    }

    fun import(raw: String) {
        viewModelScope.launch {
            busy.value = true
            personaRepository.importPersonas(raw)
                .onSuccess { message.value = it.message }
                .onFailure { message.value = "导入失败：${it.message ?: "文件格式不正确"}" }
            busy.value = false
        }
    }
}
