package com.yap.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yap.app.R
import com.yap.app.domain.ModelProfile

/**
 * 「模型优化」开关（0.15，schema v20）：自动 / DeepSeek / GLM。
 *
 * 人格编辑页与预设编辑页共用这一条控件：选项顺序、文案、选中态都只有一处，
 * 不会出现「人格页叫 DeepSeek、预设页叫 deepseek」这种漂移。
 *
 * 传进来的字符串就是落库的值（[ModelProfile.stored]）；认不出的值显示成「自动」，
 * 与读路径的回退一致。
 */
@Composable
fun ModelProfilePicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val current = ModelProfile.fromStored(selected)
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModelProfile.entries.forEach { profile ->
            TextPill(
                text = modelProfileLabel(profile),
                selected = current == profile,
                onClick = { onSelect(profile.stored) }
            )
        }
    }
}

/** 档位名（资源文案）。 */
@Composable
fun modelProfileLabel(profile: ModelProfile): String = when (profile) {
    ModelProfile.AUTO -> stringResource(R.string.model_profile_auto)
    ModelProfile.DEEPSEEK -> stringResource(R.string.model_profile_deepseek)
    ModelProfile.GLM -> stringResource(R.string.model_profile_glm)
}

/** 当前档位的一句解释：选了什么就说明它到底改变了什么。 */
@Composable
fun modelProfileHint(profile: ModelProfile): String = when (profile) {
    ModelProfile.AUTO -> stringResource(R.string.model_profile_hint_auto)
    ModelProfile.DEEPSEEK -> stringResource(R.string.model_profile_hint_deepseek)
    ModelProfile.GLM -> stringResource(R.string.model_profile_hint_glm)
}
