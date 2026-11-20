package com.yap.app.ui.common

import android.content.Context
import androidx.annotation.PluralsRes
import com.yap.app.R
import com.yap.app.data.repo.ImportMode
import com.yap.app.data.repo.ImportSummary
import com.yap.app.domain.BackupCorruption
import com.yap.app.domain.BackupFailure
import com.yap.app.domain.BackupMediaWarning
import com.yap.app.domain.BackupPreview
import com.yap.app.domain.BackupSection
import com.yap.app.domain.BackupWarning
import com.yap.app.domain.formatBackupMoment

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

    /**
     * 「备份格式 v7 · schema v19 · 校验和已通过」。
     *
     * 这一行回答「这份文件是谁、什么时候、哪一版写出来的」，也是 0.15 起
     * 「版本过高被拒 / checksum 对不上」这两类拒绝能被用户理解的前提。
     */
    fun format(context: Context, preview: BackupPreview): String {
        val checksum = context.getString(
            when {
                preview.checksumVerified -> R.string.backup_dialog_checksum_ok
                // 别的客户端写的 checksum 本机不复核（两端 JSON 编码字节不同），但仍然允许导入。
                preview.checksumForeign -> R.string.backup_dialog_checksum_foreign
                else -> R.string.backup_dialog_checksum_missing
            }
        )
        return if (preview.schemaVersion > 0) {
            context.getString(
                R.string.backup_dialog_format,
                preview.formatVersion,
                preview.schemaVersion,
                checksum
            )
        } else {
            context.getString(
                R.string.backup_dialog_format_no_schema,
                preview.formatVersion,
                checksum
            )
        }
    }

    /** 文件损坏（checksum 对不上）：无论具体是哪一种，用户要做的事都一样——换一份。 */
    fun corruption(context: Context, corruption: BackupCorruption): String =
        when (corruption) {
            is BackupCorruption.ChecksumMismatch,
            is BackupCorruption.ChecksumMalformed -> context.getString(R.string.backup_fail_checksum)
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
            // 分隔符也走资源：中文用全角分号，英文用 "; "。
            val separator = context.getString(R.string.backup_warn_separator)
             // 结构化提醒 + v3 媒体提醒一起说：用户看到的是同一句「哪些东西没回来」。
            val notes = summary.warnings.map { warning(context, it) } +
                summary.mediaWarnings.map { mediaWarning(context, it) }
            if (notes.isNotEmpty()) {
                append(separator)
                append(notes.joinToString(separator))
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
            context.quantity(R.plurals.backup_warn_api_config_insecure_ignored, warning.count, warning.count)
        is BackupWarning.ApiConfigRejected ->
            context.getString(R.string.backup_warn_api_config_rejected, warning.configName)
        // 审计 P1-2：预览阶段就告诉用户「这份结构不完整，导入会被拒绝」。
        is BackupWarning.IncompleteReferences ->
            context.getString(R.string.backup_warn_incomplete_references, warning.holes)
    }

    /** 导出成功那句话后面要补的「有 N 张图片没随备份走」。 */
    fun exportWarnings(context: Context, warnings: List<BackupMediaWarning>): String {
        if (warnings.isEmpty()) return ""
        val separator = context.getString(R.string.backup_warn_separator)
        return separator + warnings.joinToString(separator) { mediaWarning(context, it) }
    }

    /** v3 媒体的三条提醒（导出超限 / 导入跳过 / 导入超限）。 */
    fun mediaWarning(context: Context, warning: BackupMediaWarning): String = when (warning) {
        is BackupMediaWarning.ExportSkipped ->
            context.quantity(R.plurals.backup_media_export_skipped, warning.count)
        is BackupMediaWarning.ImportSkipped ->
            context.quantity(R.plurals.backup_media_import_skipped, warning.count)
        is BackupMediaWarning.ImportOverLimit ->
            context.quantity(R.plurals.backup_media_import_over_limit, warning.count)
    }

    fun failure(context: Context, failure: BackupFailure): String = when (failure) {
        BackupFailure.EmptyFile -> context.getString(R.string.backup_fail_empty)
        BackupFailure.InvalidJson -> context.getString(R.string.backup_fail_invalid_json)
        is BackupFailure.WrongFormat -> context.getString(R.string.backup_fail_wrong_format, failure.format)
        is BackupFailure.NewerFormat ->
            context.getString(R.string.backup_fail_newer_format, failure.version, failure.supported)
        // 超大文件（安全 P2-3）：给出上限，用户能判断「是不是拿错了文件」。
        is BackupFailure.TooLarge ->
            context.getString(R.string.backup_fail_too_large, com.yap.app.domain.BackupLimits.megabytes(failure.limitBytes))
        // 审计 P1-2：结构不完整（跨表引用断了）时整份拒绝，并说清「没有被清空」。
        is BackupFailure.IncompleteReferences ->
            context.getString(R.string.backup_fail_incomplete_references, failure.holes)
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
