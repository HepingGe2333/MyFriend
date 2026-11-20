package com.yap.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yap.app.R
import com.yap.app.data.repo.GenerationTestRepository
import com.yap.app.domain.GenerationProbe
import com.yap.app.ui.components.SectionLabel
import com.yap.app.ui.components.SettingCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页「测试生成」小节（0.15）。
 *
 * 用当前人格 + 当前 API 配置发一条测试消息，显示耗时、字数、有没有调用工具。
 * 这一条消息**不写进任何会话**（见 [GenerationTestRepository]），所以用户可以放心多点几次。
 *
 * 单独一个 ViewModel：设置页的主 ViewModel 已经背了服务端模式/备份/同步一大堆状态，
 * 这里只需要一个「跑/没跑」的按钮状态，不该再往里塞。
 */
@HiltViewModel
class GenerationTestViewModel @Inject constructor(
    private val repository: GenerationTestRepository
) : ViewModel() {

    data class UiState(
        val running: Boolean = false,
        val result: GenerationProbe.Result? = null
    )

    var state by mutableStateOf(UiState())
        private set

    fun run() {
        if (state.running) return
        state = state.copy(running = true)
        viewModelScope.launch {
            val result = runCatching { repository.run() }.getOrNull()
                ?: GenerationProbe.Result(
                    elapsedMs = 0L,
                    charCount = 0,
                    toolCalls = 0,
                    text = "",
                    errorToken = GenerationProbe.TOKEN_FAILED
                )
            state = UiState(running = false, result = result)
        }
    }
}

@Composable
fun GenerationTestSection(viewModel: GenerationTestViewModel = hiltViewModel()) {
    val state = viewModel.state
    SectionLabel(stringResource(R.string.settings_generation_section))
    SettingCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_generation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = { viewModel.run() },
                enabled = !state.running,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Text(
                    text = stringResource(
                        if (state.running) R.string.settings_generation_running
                        else R.string.settings_generation_button
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            state.result?.let { result ->
                Spacer(Modifier.height(12.dp))
                if (result.errorToken != null) {
                    Text(
                        text = stringResource(R.string.settings_generation_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.settings_generation_summary,
                            result.elapsedMs / 1000.0,
                            result.charCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (result.toolCalls > 0) {
                            stringResource(R.string.settings_generation_tools_used, result.toolCalls)
                        } else {
                            stringResource(R.string.settings_generation_tools_none)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (result.preview.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = result.preview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
