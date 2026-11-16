package com.yap.app.ui.common

import android.content.Context
import com.yap.app.R
import com.yap.app.domain.PersonaRevisionReason

/**
 * 版本历史里那句「为什么有这个版本」。
 *
 * 新数据存的是 [PersonaRevisionReason] 令牌，这里翻译成当前语言；
 * 老库里已经是中文的 reason 原样显示（不改写历史数据）。
 */
object RevisionReasonText {

    fun of(context: Context, raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return context.getString(R.string.reason_revision_baseline)
        val label = PersonaRevisionReason.decode(text) ?: return text
        return when (label.kind) {
            PersonaRevisionReason.Kind.CARD_CREATED -> context.getString(R.string.reason_revision_card_created)
            PersonaRevisionReason.Kind.CARD_EDITED -> context.getString(R.string.reason_revision_card_edited)
            PersonaRevisionReason.Kind.CARD_BEFORE_ADJUST -> context.getString(R.string.reason_revision_card_before_adjust)
            PersonaRevisionReason.Kind.CREATED -> context.getString(R.string.reason_revision_created)
            PersonaRevisionReason.Kind.BEFORE_EDIT -> context.getString(R.string.reason_revision_before_edit)
            PersonaRevisionReason.Kind.EDITED -> context.getString(R.string.reason_revision_edited)
            PersonaRevisionReason.Kind.COPIED -> context.getString(R.string.reason_revision_copied)
            PersonaRevisionReason.Kind.BEFORE_RESTORE -> context.getString(R.string.reason_revision_before_restore)
            PersonaRevisionReason.Kind.RESTORED ->
                context.getString(R.string.reason_revision_restored, label.version ?: 1)
            PersonaRevisionReason.Kind.BEFORE_EVOLUTION -> context.getString(R.string.reason_revision_before_evolution)
            PersonaRevisionReason.Kind.EVOLVED ->
                context.getString(R.string.reason_revision_evolved, label.detail.orEmpty())
            PersonaRevisionReason.Kind.BASELINE -> context.getString(R.string.reason_revision_baseline)
            PersonaRevisionReason.Kind.IMPORTED -> context.getString(R.string.reason_revision_imported)
            PersonaRevisionReason.Kind.SELF_ADJUSTED ->
                context.getString(R.string.reason_revision_self_adjusted, label.detail.orEmpty())
        }
    }
}
