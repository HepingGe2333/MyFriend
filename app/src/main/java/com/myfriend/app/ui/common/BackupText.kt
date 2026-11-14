package com.myfriend.app.ui.common

import android.content.Context
import androidx.annotation.PluralsRes
import com.myfriend.app.R
import com.myfriend.app.data.repo.ImportMode
import com.myfriend.app.data.repo.ImportSummary
import com.myfriend.app.domain.BackupFailure
import com.myfriend.app.domain.BackupPreview
import com.myfriend.app.domain.BackupSection
import com.myfriend.app.domain.BackupWarning
import com.myfriend.app.domain.formatBackupMoment

/**
 * 备份相关的句子：预览摘要、带走时间、导入总结、warnings、失败原因。
 *
 * 数据层只给计数与枚举（`BackupPreview` / `ImportSummary` / [BackupWarning] / [BackupFailure]），
 * 句子在这里按当前 UI 语言拼——英文界面不会看到中文。
 */
object BackupText {

    /** 「3 个人格 · 128 条记忆 · 42 段对话 · 1580 句话」。 */
    fun preview(context: Context, preview: BackupPreview): String {
        if (preview.isEmpty) return context.getString(R.string.backup_preview_empty)
        val parts = buildList {
            add(context.quantity(R.plurals.backup_preview_personas, preview.personas))
            add(context.quantity(R.plurals.backup_preview_memories, preview.memories))
            add(context.quantity(R.plurals.backup_preview_sessions, preview.sessions))
            add(context.quantity(R.plurals.backup_preview_messages, preview.messages))
            if (preview.rules > 0) add(context.quantity(R.plurals.backup_preview_rules, preview.rules))
            if (preview.presets > 0) add(context.quantity(R.plurals.backup_preview_presets, preview.presets))
        }
        return parts.joinToString(" · ")
    }

    /** 「2026年11月14日 01:40 带走」（模式随语言）。 */
    fun exportedAt(context: Context, preview: BackupPreview): String {
        if (preview.exportedAt <= 0L) return context.getString(R.string.backup_exported_unknown)
        val pattern = context.getString(R.string.backup_moment_pattern)
        return context.getString(R.string.backup_exported_at, formatBackupMoment(preview.exportedAt, pattern))
    }

    /** 「把他接回来了：3 个人格 · …」+ 跳过的条数 + 每条提醒。 */
    fun importSummary(context: Context, summary: ImportSummary): String {
        val counts = buildList {
            add(context.quantity(R.plurals.backup_preview_personas, summary.personas))
            add(context.quantity(R.plurals.backup_preview_memories, summary.memories))
            add(context.quantity(R.plurals.backup_preview_sessions, summary.sessions))
            add(context.quantity(R.plurals.backup_preview_messages, summary.messages))
            if (summary.rules > 0) add(context.quantity(R.plurals.backup_preview_rules, summary.rules))
            if (summary.presets > 0) add(context.quantity(R.plurals.backup_preview_presets, summary.presets))
        }.joinToString(" · ")

        val head = context.getString(
            if (summary.mode == ImportMode.REPLACE) R.string.backup_import_restored else R.string.backup_import_done,
            counts
        )
        return buildString {
            append(head)
            if (summary.skipped > 0) append(context.quantity(R.plurals.backup_import_skipped, summary.skipped))
            if (summary.warnings.isNotEmpty()) {
                // 分隔符也走资源：中文用全角分号，英文用 "; "。
                val separator = context.getString(R.string.backup_warn_separator)
                append(separator)
                append(summary.warnings.joinToString(separator) { warning(context, it) })
            }
        }
    }

    fun warning(context: Context, warning: BackupWarning): String = when (warning) {
        is BackupWarning.OlderFormat ->
            context.getString(R.string.backup_warn_older_format, warning.version, warning.supported)
        is BackupWarning.SectionNotList ->
            context.getString(R.string.backup_warn_section_not_list, section(context, warning.section))
        is BackupWarning.DroppedRows ->
            context.quantity(R.plurals.backup_warn_dropped_rows, warning.count, section(context, warning.section), warning.count)
        is BackupWarning.BlankIds ->
            context.quantity(R.plurals.backup_warn_blank_ids, warning.count, section(context, warning.section), warning.count)
        BackupWarning.SettingsUnreadable -> context.getString(R.string.backup_warn_settings_unreadable)
        BackupWarning.NoContent -> context.getString(R.string.backup_warn_no_content)
        is BackupWarning.ExtraSessions -> context.quantity(R.plurals.backup_warn_extra_sessions, warning.count)
        is BackupWarning.AttachmentsDropped -> context.quantity(R.plurals.backup_warn_attachments, warning.count)
        is BackupWarning.ConfigsMissingKey -> context.quantity(R.plurals.backup_warn_configs_missing_key, warning.count)
        BackupWarning.NoSettings -> context.getString(R.string.backup_warn_no_settings)
        BackupWarning.SettingsPartiallyRestored -> context.getString(R.string.backup_warn_settings_partial)
        BackupWarning.ActivePersonaMissing -> context.getString(R.string.backup_warn_active_persona_missing)
        is BackupWarning.AvatarMissing -> context.getString(R.string.backup_warn_avatar_missing, warning.personaName)
        is BackupWarning.ApiConfigAddressKept ->
            context.quantity(R.plurals.backup_warn_api_config_address_kept, warning.count)
        is BackupWarning.ApiConfigInsecureIgnored ->
            context.getString(R.string.backup_warn_api_config_insecure_ignored, warning.count)
        is BackupWarning.ApiConfigRejected ->
            context.getString(R.string.backup_warn_api_config_rejected, warning.configName)
    }

    fun failure(context: Context, failure: BackupFailure): String = when (failure) {
        BackupFailure.EmptyFile -> context.getString(R.string.backup_fail_empty)
        BackupFailure.InvalidJson -> context.getString(R.string.backup_fail_invalid_json)
        is BackupFailure.WrongFormat -> context.getString(R.string.backup_fail_wrong_format, failure.format)
        is BackupFailure.NewerFormat ->
            context.getString(R.string.backup_fail_newer_format, failure.version, failure.supported)
    }

    private fun section(context: Context, section: BackupSection): String = context.getString(
        when (section) {
            BackupSection.PERSONA -> R.string.backup_section_persona
            BackupSection.MEMORY -> R.string.backup_section_memory
            BackupSection.SESSION -> R.string.backup_section_session
            BackupSection.MESSAGE -> R.string.backup_section_message
            BackupSection.PRESET -> R.string.backup_section_preset
            BackupSection.PROACTIVE_RULE -> R.string.backup_section_rule
            BackupSection.AGENT_INTENT -> R.string.backup_section_intent
            BackupSection.CONSOLIDATION -> R.string.backup_section_consolidation
            BackupSection.API_CONFIG -> R.string.backup_section_api_config
        }
    )

    /**
     * 计数文案：数量既决定单复数（英文 one/other），也按位置填进 `%1$d`。
     *
     * 备份预览/导入总结里「1 个人格」不能再说成「1 personas」，所以这些 key
     * 从 `<string>` 换成了 `<plurals>`。
     */
    private fun Context.quantity(@PluralsRes id: Int, count: Int): String =
        resources.getQuantityString(id, count, count)

    /** 模板里 `%1$d` 不是第一个参数的（例如 `%1$s 里有 %2$d 条`），参数按顺序给全。 */
    private fun Context.quantity(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        resources.getQuantityString(id, count, *args)
}
