package com.myfriend.app.ui.common

import android.content.Context
import com.myfriend.app.R
import com.myfriend.app.domain.WorldEditorField
import com.myfriend.app.domain.WorldEditorIssue
import com.myfriend.app.domain.WorldPreset

/**
 * 世界编辑器的校验结果 → 一句人话（`spec/persona-guidance/README.md` §2.3）。
 *
 * 状态层只产出「哪一段的第几行错了」（[WorldEditorIssue]），把句子留给这里：
 * 这样英文界面看到的是英文，中文界面看到的是「日程第 2 行的开始时间要写成 HH:mm」，
 * 而规则本身只有一份。
 */
object WorldEditorErrors {

    /** 第一条问题的可读描述；没有问题返回空串。 */
    fun message(context: Context, issues: List<WorldEditorIssue>): String {
        val first = issues.firstOrNull() ?: return ""
        // 行号对用户是 1 起；notes 没有行号，用不到这个参数。
        val row = first.rowIndex + 1
        return when (first.field) {
            WorldEditorField.SCHEDULE_DAY -> context.getString(R.string.guidance_world_error_schedule_day, row)
            WorldEditorField.SCHEDULE_START -> context.getString(R.string.guidance_world_error_schedule_start, row)
            WorldEditorField.SCHEDULE_END -> context.getString(R.string.guidance_world_error_schedule_end, row)
            WorldEditorField.SCHEDULE_ACTIVITY ->
                context.getString(R.string.guidance_world_error_schedule_activity, row)
            WorldEditorField.RELATION_NAME -> context.getString(R.string.guidance_world_error_relation_name, row)
            WorldEditorField.RELATION_INTIMACY ->
                context.getString(R.string.guidance_world_error_relation_intimacy, row)
            WorldEditorField.NOTES ->
                context.getString(R.string.guidance_world_error_notes, WorldPreset.MAX_NOTES_CHARS)
        }
    }
}
