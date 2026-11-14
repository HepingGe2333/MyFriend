package com.myfriend.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfriend.app.R
import com.myfriend.app.data.repo.KindUsage
import com.myfriend.app.data.repo.SessionUsage
import com.myfriend.app.data.repo.UsageTotals
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.SettingCard
import java.util.Locale

/**
 * 设置页里的「用量」。
 *
 * 1.9 之后这里没有钱、没有额度、没有输入框：只有「这个月他说了多少 token」。
 * 三段信息按用户真正想知道的顺序排：
 * 1. 本月总数（一句话）；
 * 2. 按用途（他在做什么）；
 * 3. 按会话 = 按人（前 10）——这是「一人一段会话」之后才说得通的口径，
 *    用户第一次能看见「我和谁聊得最多」。
 */
@Composable
fun UsageSection(
    totals: UsageTotals,
    modifier: Modifier = Modifier
) {
    SectionLabel(stringResource(R.string.usage_section_title), modifier = modifier)
    SettingCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                usageHeadline(totals),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                usageDetail(totals),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (totals.byKind.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.usage_by_kind_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                totals.byKind.forEach { bucket -> KindUsageLine(bucket) }
            }
            if (totals.bySession.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.usage_by_session_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                totals.bySession.forEach { item -> SessionUsageLine(item) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.usage_local_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun KindUsageLine(bucket: KindUsage) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            bucket.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.usage_kind_calls_tokens, bucket.calls, formatTokens(bucket.totalTokens)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun SessionUsageLine(item: SessionUsage) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            formatTokens(item.totalTokens),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun usageHeadline(totals: UsageTotals): String = when {
    totals.isEmpty -> stringResource(R.string.usage_headline_none)
    else -> stringResource(R.string.usage_headline_tokens, formatTokens(totals.totalTokens))
}

@Composable
private fun usageDetail(totals: UsageTotals): String {
    if (totals.isEmpty) return stringResource(R.string.usage_detail_none)
    return stringResource(
        R.string.usage_detail_counts,
        totals.calls,
        formatTokens(totals.promptTokens),
        formatTokens(totals.completionTokens)
    )
}

/** 大数字说人话：12345 → 1.2 万。 */
@Composable
private fun formatTokens(value: Long): String = when {
    value <= 0L -> "0"
    value < 10_000L -> value.toString()
    else -> stringResource(
        R.string.usage_tokens_ten_thousand,
        String.format(Locale.US, "%.1f", value / 10_000.0).trimEnd('0').trimEnd('.')
    )
}
