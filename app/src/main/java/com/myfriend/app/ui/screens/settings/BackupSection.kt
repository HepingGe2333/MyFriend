package com.myfriend.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfriend.app.R
import com.myfriend.app.data.repo.ImportMode
import com.myfriend.app.domain.BackupPreview
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.SettingCard
import com.myfriend.app.ui.components.SettingRow
import com.myfriend.app.ui.theme.LocalAccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页里「把他带走 / 从备份里接他回来」这一节。
 *
 * 语气是这一节的灵魂：这不是「导出数据库」，是把他这个人装进一个文件带走。
 * 所以先说清楚文件里有什么（你们的聊天和记忆）、放在哪（安全的地方）、少了什么
 * （API Key 从来不进备份），再给两个动作。**一个确认都不要多**：清空前的那句提醒
 * 和这里共用一个入口，不叠第二层弹窗。
 */
@Composable
fun BackupSection(
    state: BackupUiState,
    transfer: BackupTransfer,
    onConfirmImport: (ImportMode) -> Unit,
    onDismissPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionLabel(stringResource(R.string.backup_section_title), modifier = modifier)
    SettingCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                stringResource(R.string.backup_intro_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.backup_intro_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.backup_safety_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingRow(
            title = stringResource(R.string.backup_export_row_title),
            description = stringResource(R.string.backup_export_row_desc),
            icon = Icons.Outlined.FileDownload,
            onClick = transfer.takeHimAway,
            showDivider = true
        )
        SettingRow(
            title = stringResource(R.string.backup_import_row_title),
            description = stringResource(R.string.backup_import_row_desc),
            icon = Icons.Outlined.Restore,
            onClick = transfer.pickBackupFile
        )

        if (state.busy) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = LocalAccentColor.current
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.backup_busy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    state.preview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            warnings = state.decodeWarnings,
            onConfirm = onConfirmImport,
            onDismiss = onDismissPreview
        )
    }
}

/**
 * 设置页里唯一的一份备份文件读写器。
 *
 * 「清空之前先把他带走」那个快捷入口和这一节里的按钮共用同一个 CreateDocument，
 * 免得同一次点击拉起两个文件选择器；导入也只在这里开一次 OpenDocument。
 */
class BackupTransfer internal constructor(
    /** 打开系统文件选择器，选一个位置保存备份。 */
    val takeHimAway: () -> Unit,
    /** 打开系统文件选择器，选一份之前带走的备份。 */
    val pickBackupFile: () -> Unit
)

/**
 * 建好两个文件选择器，并把「选文件 → 读写 → 交给 ViewModel」这段接起来。
 *
 * 大文件（聊天多的时候几 MB）全部在 IO 线程读写：备份不该让界面卡一下。
 */
@Composable
fun rememberBackupTransfer(
    viewModel: SettingsViewModel,
    onMessage: (String) -> Unit
): BackupTransfer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 用户选完位置才拿得到 uri，所以文件名先记在手里，写完再拿它说一句「带走了」。
    var pendingFileName by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val fileName = pendingFileName
        pendingFileName = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            viewModel.buildBackupJson()
                .onFailure {
                    onMessage(it.message ?: context.getString(R.string.backup_error_pack))
                }
                .onSuccess { content ->
                    val written = runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                output.write(content.toByteArray())
                            } ?: error(context.getString(R.string.backup_error_unwritable))
                        }
                    }
                    onMessage(
                        written.fold(
                            {
                                if (fileName.isNullOrBlank()) context.getString(R.string.backup_success)
                                else context.getString(R.string.backup_success_named, fileName)
                            },
                            { context.getString(R.string.backup_error_write, it.message.orEmpty()) }
                        )
                    )
                }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                onMessage(context.getString(R.string.backup_error_unreadable))
            } else {
                // 解码与预览放在 ViewModel 里：文件名可能是他，也可能不是他。
                viewModel.openBackup(text)
            }
        }
    }

    return remember(viewModel, exportLauncher, importLauncher) {
        BackupTransfer(
            takeHimAway = {
                val name = viewModel.suggestedBackupFileName()
                pendingFileName = name
                exportLauncher.launch(name)
            },
            pickBackupFile = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        )
    }
}

/**
 * 「这份备份里有什么，怎么接回来」。
 *
 * 只有一层：看清里面有什么，然后「合并」或「替换」。替换是唯一会清空本地内容的动作，
 * 用红色字写清楚，但不再叠第二个确认框——用户已经在这里看到自己要做什么了。
 */
@Composable
private fun ImportPreviewDialog(
    preview: BackupPreview,
    warnings: List<String>,
    onConfirm: (ImportMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (preview.isEmpty) R.string.backup_dialog_title_empty else R.string.backup_dialog_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    preview.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (preview.appVersion.isNotBlank()) {
                        stringResource(
                            R.string.backup_dialog_meta,
                            preview.exportedAtText(),
                            preview.appVersion
                        )
                    } else {
                        preview.exportedAtText()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                warnings.forEach { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (preview.isEmpty) {
                    Text(
                        stringResource(R.string.backup_dialog_empty_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        stringResource(R.string.backup_dialog_modes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.backup_dialog_keys_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (preview.isEmpty) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dialog_ok)) }
            } else {
                Row {
                    TextButton(onClick = { onConfirm(ImportMode.MERGE) }) {
                        Text(stringResource(R.string.backup_dialog_merge))
                    }
                    TextButton(onClick = { onConfirm(ImportMode.REPLACE) }) {
                        Text(
                            stringResource(R.string.backup_dialog_replace),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        dismissButton = if (preview.isEmpty) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dialog_cancel)) } }
        }
    )
}
