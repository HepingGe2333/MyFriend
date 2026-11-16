package com.yap.app.ui.screens.settings

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
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yap.app.R
import com.yap.app.data.repo.KindUsage
import com.yap.app.data.repo.SessionUsage
import com.yap.app.domain.UsageKind
import com.yap.app.data.repo.UsageTotals
import com.yap.app.ui.components.SectionLabel
import com.yap.app.ui.components.SettingCard
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
            usageKindLabel(bucket.kind),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            pluralStringResource(
                R.plurals.usage_kind_calls_tokens,
                bucket.calls,
                bucket.calls,
                formatTokens(bucket.totalTokens)
            ),
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
            sessionLabel(item),
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
    return pluralStringResource(
        R.plurals.usage_detail_counts,
        totals.calls,
        totals.calls,
        formatTokens(totals.promptTokens),
        formatTokens(totals.completionTokens)
    )
}

/**
 * 大数字说人话，单位跟着语言走：中文 12345 → 1.2 万，英文 → 12.3k。
 *
 * 除数也放在资源里（`usage_tokens_compact_divisor`），不在这里写死 10000：
 * 中文习惯按万，英语习惯按千，换个说法不用改代码。
 */
/**
 * 「按用途」那一列：用途是 [UsageKind] 里的稳定值，说法在资源里。
 *
 * 不用 [KindUsage.label]：那是给日志和旧调用方的一句中文，界面要能跟着 App 语言走。
 */
@Composable
private fun usageKindLabel(kind: String): String = stringResource(
    when (kind) {
        UsageKind.CHAT -> R.string.usage_kind_chat
        UsageKind.CONSOLIDATION -> R.string.usage_kind_consolidation
        UsageKind.SCORING -> R.string.usage_kind_scoring
        UsageKind.AGENT -> R.string.usage_kind_agent
        UsageKind.RECALL -> R.string.usage_kind_recall
        UsageKind.EXTRACTION -> R.string.usage_kind_extraction
        UsageKind.RECONSOLIDATION -> R.string.usage_kind_reconsolidation
        UsageKind.HISTORY -> R.string.usage_kind_history
        else -> R.string.usage_kind_unknown
    }
)

/** 「按人」那一列：有名字就写名字，没名字（人格删了 / 老数据）说一句人话。 */
@Composable
private fun sessionLabel(item: SessionUsage): String = when {
    item.sessionId.isNullOrBlank() -> stringResource(R.string.usage_session_other)
    item.nameless -> stringResource(R.string.usage_session_unknown)
    else -> item.label
}

@Composable
private fun formatTokens(value: Long): String = when {
    value <= 0L -> "0"
    value < 10_000L -> value.toString()
    else -> {
        val divisor = integerResource(R.integer.usage_tokens_compact_divisor).coerceAtLeast(1)
        val scaled = value.toDouble() / divisor
        stringResource(
            R.string.usage_tokens_ten_thousand,
            String.format(Locale.getDefault(), "%.1f", scaled).trimEnd('0').trimEnd('.')
        )
    }
}
