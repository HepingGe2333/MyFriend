package com.yap.app.ui.screens.apiconfig

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yap.app.ui.common.ErrorText
import com.yap.app.R
import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.data.repo.ApiConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val repository: ApiConfigRepository,
    @ApplicationContext private val context: Context
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
                .onSuccess { message.value = ErrorText.connectionSuccess(context, it.modelCount) }
                .onFailure {
                    val reason = ErrorText.of(context, it, R.string.api_error_unknown)
                    message.value = context.getString(R.string.api_error_connect_failed, reason)
                }
            testingId.value = null
        }
    }

    fun duplicate(configId: String) {
        viewModelScope.launch {
            busy.value = true
            repository.duplicate(configId, context.getString(R.string.common_copy_suffix))
                .onSuccess { message.value = context.getString(R.string.api_list_duplicated) }
                .onFailure { message.value = ErrorText.of(context, it, R.string.api_list_duplicate_failed) }
            busy.value = false
        }
    }

    fun delete(configId: String) {
        viewModelScope.launch {
            busy.value = true
            runCatching { repository.delete(configId) }
                .onSuccess { message.value = context.getString(R.string.api_list_deleted) }
                .onFailure { message.value = ErrorText.of(context, it, R.string.api_list_delete_failed) }
            busy.value = false
        }
    }
}
