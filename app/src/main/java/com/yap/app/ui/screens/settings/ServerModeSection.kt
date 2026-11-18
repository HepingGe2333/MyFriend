package com.yap.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yap.app.R
import com.yap.app.data.remote.ServerConfigRejection
import com.yap.app.data.remote.ServerSyncConsent
import com.yap.app.ui.components.SettingCard
import com.yap.app.ui.components.SettingRow
import com.yap.app.ui.components.StyledOutlinedField
import com.yap.app.ui.components.SectionLabel

/**
 * 设置页「服务端模式」（M3）。
 *
 * 交互上刻意把「保存」与「测试连接」分开：
 * - **测试**只验证当前输入（地址 + 令牌），成功/失败都显示在按钮下面，不写盘；
 * - **保存**才落加密存储并切换运行时配置；
 * - 开关单独保存（用户可以先关掉服务端、回到本地模式，不用删地址）。
 *
 * 令牌输入框从来不回填明文：只显示「已保存：••••1234」。留空保存 = 沿用旧令牌；
 * 要换/清掉令牌才需要重新输入或点「清除令牌」。
 */
@Composable
fun ServerModeSection(
    state: ServerSettingsUiState,
    onSave: (url: String, token: String, enabled: Boolean) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onTest: (url: String, token: String) -> Unit,
    onClearToken: () -> Unit,
    onConsent: (ServerSyncConsent) -> Unit,
    onDismissConsent: () -> Unit,
    onCancelSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 首次/保存后把已存的地址带进输入框；用户正在打字时 state.url 不会变，所以不会被覆盖。
    var url by rememberSaveable(state.url) { mutableStateOf(state.url) }
    var token by rememberSaveable { mutableStateOf("") }
    // 开关完全由状态驱动：本地 → 远程要先过同意弹窗，远程 → 本地要先完成全量下载，
    // 所以不能在点击时先乐观翻转（那会出现「看起来切了、其实没有」）。
    val enabled = state.enabled

    SectionLabel(stringResource(R.string.settings_section_server), modifier = modifier)
    SettingCard {
        SettingRow(
            title = stringResource(R.string.settings_server_title),
            description = stringResource(R.string.settings_server_desc),
            icon = Icons.Outlined.Cloud,
            trailing = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked -> onSetEnabled(checked) }
                )
            },
            showDivider = true
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            StyledOutlinedField(
                value = url,
                onValueChange = { url = it },
                label = stringResource(R.string.settings_server_url),
                placeholder = stringResource(R.string.settings_server_url_hint),
                enabled = !state.testing
            )
            Spacer(Modifier.height(10.dp))
            StyledOutlinedField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.settings_server_token),
                placeholder = when {
                    state.hasToken -> stringResource(R.string.settings_server_token_saved, state.maskedToken)
                    else -> stringResource(R.string.settings_server_token_hint)
                },
                singleLine = true,
                enabled = !state.testing,
                visualTransformation = PasswordVisualTransformation()
            )
            if (state.hasToken) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_server_token_masked, state.maskedToken),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClearToken) {
                        Text(stringResource(R.string.settings_server_clear_token))
                    }
                }
            }
            ServerRejectionHint(state)
            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.testMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 0.12.1：首次连接后的一次性数据对齐进度（四象限全量搬运 / 合并）。
            state.bootstrapStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 0.13：历史媒体逐项上传 / 全量下载的进度 + 取消（取消后不切换模式）。
            state.syncStatus?.let { status ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.syncRunning) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCancelSync) {
                            Text(stringResource(R.string.settings_server_cancel_sync))
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onTest(url, token) },
                    enabled = !state.testing && url.isNotBlank()
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_server_testing))
                    } else {
                        Text(stringResource(R.string.settings_server_test))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onSave(url, token, enabled) },
                    enabled = !state.testing && url.isNotBlank()
                ) {
                    Text(stringResource(R.string.settings_server_save))
                }
            }
            if (!enabled) {
                Text(
                    text = stringResource(R.string.settings_server_off_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (state.consentPrompt) {
        ServerSyncConsentDialog(onChoose = onConsent, onDismiss = onDismissConsent)
    }
}

/**
 * 0.13「把对话交给云端处理？」弹窗（`spec/sync-consent/README.md` §1）。
 *
 * 三个选项是规格的一部分：**同意并同步** / **只同步之后的新内容** / **取消**，
 * 默认焦点放在「取消」——这是一次会把私人聊天传到别人机器上的操作，默认不该是同意。
 */
@Composable
private fun ServerSyncConsentDialog(
    onChoose: (ServerSyncConsent) -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_server_consent_title)) },
        text = { Text(stringResource(R.string.settings_server_consent_body)) },
        confirmButton = {
            Column {
                TextButton(onClick = { onChoose(ServerSyncConsent.FULL) }) {
                    Text(stringResource(R.string.settings_server_consent_full))
                }
                TextButton(onClick = { onChoose(ServerSyncConsent.NEW_ONLY) }) {
                    Text(stringResource(R.string.settings_server_consent_new_only))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus)
            ) {
                Text(stringResource(R.string.settings_server_consent_cancel))
            }
        }
    )
    LaunchedEffect(cancelFocus) {
        runCatching { cancelFocus.requestFocus() }
    }
}

/**
 * 地址不合法的原因。
 *
 * 规则只有一份（[ServerConfigRejection]），这里只做「枚举 → 资源」的翻译；
 * 不要在 UI 里再判一次 https/内网，否则两边口径迟早分叉。
 */
@Composable
private fun ServerRejectionHint(state: ServerSettingsUiState) {
    val rejection = state.rejection ?: return
    if (rejection == ServerConfigRejection.DISABLED) return
    val text = when (rejection) {
        ServerConfigRejection.DISABLED -> return
        ServerConfigRejection.EMPTY -> stringResource(R.string.settings_server_reject_empty)
        ServerConfigRejection.UNSUPPORTED_SCHEME,
        ServerConfigRejection.INVALID_URL -> stringResource(R.string.settings_server_reject_invalid)
        ServerConfigRejection.CLEARTEXT_HTTP_IN_RELEASE ->
            stringResource(R.string.settings_server_reject_https_only)
        ServerConfigRejection.CLEARTEXT_HTTP_NOT_LOCAL ->
            stringResource(R.string.settings_server_reject_local_only)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error
    )
}
