package com.myfriend.app.ui.screens.persona

import androidx.annotation.StringRes
import com.myfriend.app.R
import com.myfriend.app.domain.ProactiveLevel

/**
 * 「主动程度」1–5 的人话解释，取的是资源 id。
 *
 * 放在 ui 层而不是 [ProactiveLevel] 里：`domain/` 要保持纯 Kotlin（`agentd/` 直接复用同一批源文件），
 * 不该出现 `R` 和 `androidx.annotation`。中文资源与 [ProactiveLevel.description] 逐字一致，
 * 英文在 `values-en/`；跨端 spec 仍然比对 [ProactiveLevel.description]。
 */
@StringRes
fun proactiveLevelDescriptionRes(level: Int): Int = when (ProactiveLevel.normalize(level)) {
    1 -> R.string.proactive_level_1
    2 -> R.string.proactive_level_2
    3 -> R.string.proactive_level_3
    4 -> R.string.proactive_level_4
    else -> R.string.proactive_level_5
}
