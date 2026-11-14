package com.myfriend.app.ui.screens.apiconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.repo.ApiConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApiListUiState(
    val configs: List<ApiConfigEntity> = emptyList(),
    val testingId: String? = null,
    val busy: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class ApiListViewModel @Inject constructor(
    private val repository: ApiConfigRepository
) : ViewModel() {
    private val testingId = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ApiListUiState> = combine(
        repository.observeAll(),
        testingId,
        busy,
        message
    ) { configs, currentTesting, isBusy, currentMessage ->
        ApiListUiState(configs, currentTesting, isBusy, currentMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApiListUiState())

    fun dismissMessage() {
        message.value = null
    }

    fun test(configId: String) {
        if (testingId.value != null) return
        viewModelScope.launch {
            testingId.value = configId
            repository.testConnection(configId)
                .onSuccess { message.value = it.message }
                .onFailure { message.value = "连接失败：${it.message ?: "未知错误"}" }
            testingId.value = null
        }
    }

    fun duplicate(configId: String) {
        viewModelScope.launch {
            busy.value = true
            repository.duplicate(configId)
                .onSuccess { message.value = "配置已复制" }
                .onFailure { message.value = it.message ?: "复制失败" }
            busy.value = false
        }
    }

    fun delete(configId: String) {
        viewModelScope.launch {
            busy.value = true
            runCatching { repository.delete(configId) }
                .onSuccess { message.value = "配置已删除，相关会话需重新选择 API" }
                .onFailure { message.value = it.message ?: "删除失败" }
            busy.value = false
        }
    }
}
