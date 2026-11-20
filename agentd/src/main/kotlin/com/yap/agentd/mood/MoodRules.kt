package com.yap.agentd.mood

import com.yap.agentd.store.Mood
import com.yap.app.domain.PromptContract
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue

/**
 * 多维情绪的**规则层**（`spec/mood/README.md`）。
 *
 * 模型输出一律不可信：JSON 可能坏、数值可能越界、一次跳五格；所以解析（[parse]）、
 * 幅度限制（[limit]）、"算不算明显变化"（[isNoticeable]）与失败回落（[decay]）都在这里，
 * 纯函数、可逐条钉住。HTTP/LLM 调用在 [MoodService]。
 *
 * 三条规范数字：
 * - 维度范围：valence -2..2、arousal 0..5、dominance 0..5、intensity 1..5；
 * - **不做「每维变化 ≤2」的硬限制**（0.15 心情直觉化）：心情是**每条用户消息之后**凭直觉
 *   重新判断的结论，一句话把心情从谷底拉到顶是正常的；投稿越界也只夹到物理范围
 *   （[PromptContract.Mood.VALENCE_MIN]..[PromptContract.Mood.DOMINANCE_MAX]）；
 * - 失败/无配置时按时间衰减：每小时向 `0 / 2 / 3` 靠一步（不报错）。
 *
 * 「取消每维 ≤2」只针对**心情判定**；`MentalActivityRules` 的「念头让心情动一格」是另一条
 * 规则（念头只是心里动了一下，不是一次完整判定），两者不冲突。
 */
object MoodRules {

    /** 一次判定/衰减后的多维心情（存储层 `Mood` 的业务视图）。 */
    data class Values(
        val label: String,
        val valence: Int,
        val arousal: Int,
        val dominance: Int,
        val intensity: Int,
        val reason: String = ""
    ) {
        companion object {
            val BASELINE = Values(
                label = com.yap.app.domain.MoodLexicon.BASELINE,
                valence = 0,
                arousal = 2,
                dominance = 3,
                intensity = 1
            )

            /** 存储层 → 规则层（旧数据缺失的维度已经由 `Mood.fromJson` 补过）。 */
            fun from(mood: Mood): Values = Values(
                label = mood.label.ifBlank { com.yap.app.domain.MoodLexicon.BASELINE },
                valence = mood.valence.coerceIn(-2, 2),
                arousal = mood.arousal.coerceIn(0, 5),
                dominance = mood.dominance.coerceIn(0, 5),
                intensity = mood.intensity.coerceIn(1, 5),
                reason = mood.reason
            )

            fun none(): Values = BASELINE.copy(intensity = 2)
        }
    }

    /**
     * 0.14 的「单次变化幅度 ≤2」上限，**0.15 起不再参与夹取**。
     *
     * 常量保留只是为了兼容旧调用方（备份/客户端可能把它写进诊断字段）；新代码不要再读它。
     */
    @Deprecated("0.15 心情直觉化：取消每维≤2，只夹物理范围")
    const val MAX_DELTA: Int = 2

    /** 衰减目标（每小时向它靠一步）。 */
    const val BASELINE_VALENCE: Int = 0
    const val BASELINE_AROUSAL: Int = 2
    const val BASELINE_DOMINANCE: Int = 3

    /** 情绪词长度上限（与 `MoodLexicon.normalize` 的 12 一致）。 */
    const val MAX_LABEL_CHARS: Int = 12

    // ---------------------------------------------------------------- 解析

    /**
     * 解析模型输出的 JSON：`{label,valence,arousal,dominance,intensity,reason}`。
     *
     * 容忍 markdown 代码块/前后解释文字（取第一对 `{...}`）、字符串数字、多余字段。
     * 一个可用的数值都没有（或不是对象）时返回 `null`，调用方按"判定失败"处理。
     */
    fun parse(raw: String): Values? {
        val root = parseObject(raw) ?: return null
        val label = string(root.fields["label"])?.trim()?.take(MAX_LABEL_CHARS).orEmpty()
        val valence = number(root.fields["valence"])
        val arousal = number(root.fields["arousal"])
        val dominance = number(root.fields["dominance"])
        val intensity = number(root.fields["intensity"])
        if (valence == null && arousal == null && dominance == null && intensity == null) return null
        val reason = string(root.fields["reason"])?.trim()?.take(Mood.MAX_REASON_CHARS).orEmpty()
        return Values(
            label = label,
            valence = valence ?: BASELINE_VALENCE,
            arousal = arousal ?: BASELINE_AROUSAL,
            dominance = dominance ?: BASELINE_DOMINANCE,
            intensity = intensity ?: 2,
            reason = reason
        ).normalized()
    }

    /**
     * 套物理范围（**不再限制相对变化**，0.15 心情直觉化）。
     *
     * 为什么取消「每维 ≤2」：那条规则把心情变成了缓慢漂移的数值——用户说了一件大事，
     * 模型却只能把「愉快度」挪两格，回复的语气与刚听到的话对不上。现在每条用户消息之后都
     * 重新判一次直觉结论，模型给多少就取多少（越界才夹）。
     *
     * [previous] 只在模型没给标签时兜底（空标签沿用上一轮的词），不参与数值夹取。
     */
    fun limit(previous: Values, proposed: Values): Values = Values(
        label = proposed.label.ifBlank { previous.label },
        valence = proposed.valence.coerceIn(
            PromptContract.Mood.VALENCE_MIN,
            PromptContract.Mood.VALENCE_MAX
        ),
        arousal = proposed.arousal.coerceIn(
            PromptContract.Mood.AROUSAL_MIN,
            PromptContract.Mood.AROUSAL_MAX
        ),
        dominance = proposed.dominance.coerceIn(
            PromptContract.Mood.DOMINANCE_MIN,
            PromptContract.Mood.DOMINANCE_MAX
        ),
        intensity = proposed.intensity.coerceIn(
            PromptContract.Mood.INTENSITY_MIN,
            PromptContract.Mood.INTENSITY_MAX
        ),
        reason = proposed.reason
    )

    /** 「没有明显变化就不写」：任何一个维度/标签/强度变了才算。 */
    fun isNoticeable(previous: Values, next: Values): Boolean =
        previous.label != next.label ||
            previous.valence != next.valence ||
            previous.arousal != next.arousal ||
            previous.dominance != next.dominance ||
            previous.intensity != next.intensity

    /**
     * 失败/无配置时的回落：每小时向 `0 / 2 / 3` 靠一步（最多走满）。
     *
     * 不碰 label 与 intensity：规范只说三个维度回落；标签由下一次成功判定更新。
     */
    fun decay(previous: Values, updatedAt: Long, now: Long): Values {
        val hours = ((now - updatedAt).coerceAtLeast(0L) / 3_600_000L).toInt()
        if (hours <= 0) return previous
        return previous.copy(
            valence = toward(previous.valence, BASELINE_VALENCE, hours, -2, 2),
            arousal = toward(previous.arousal, BASELINE_AROUSAL, hours, 0, 5),
            dominance = toward(previous.dominance, BASELINE_DOMINANCE, hours, 0, 5)
        )
    }

    // ---------------------------------------------------------------- 提示词

    /**
     * `spec/mood/README.md` §3 的固定提示词段，**先直觉结论、再三维数值**。
     *
     * 0.15 的顺序是有意的：模型先读到「心情：有点累（强度 3/5）」这个直觉结论，再看
     * 「愉快度 -1 / 唤醒度 2/5 / 掌控感 3/5」这行**校准值**。反过来（先数值后标签）时，
     * 模型容易把力气花在解释数值上，说话反而变机械；先给结论，数值只是补充。
     *
     * 标题与两行模板全部来自共享契约 [PromptContract.Mood]：两端与服务端逐字一致。
     */
    fun promptSection(values: Values): String {
        val clamped = hintSafe(values)
        val hints = behaviorHints(clamped)
        return buildString {
            appendLine(PromptContract.Mood.TITLE)
            appendLine(PromptContract.Mood.line(clamped.label, clamped.intensity))
            appendLine(
                PromptContract.Mood.dimensionsLine(
                    valence = clamped.valence,
                    arousal = clamped.arousal,
                    dominance = clamped.dominance
                )
            )
            appendLine("- 先按这个直觉说话，数值只是校准；不要向对方解释你的心情。")
            for (hint in hints) appendLine("- 这会影响你怎么说：$hint")
            append("- 不要直接报数值，也不要说「我的情绪是…」；用说话方式体现。")
        }
    }

    /** 提示词里只写物理范围内的值（脏数据不能把提示词写成 `愉快度 9`）。 */
    private fun hintSafe(values: Values): Values = values.copy(
        valence = values.valence.coerceIn(PromptContract.Mood.VALENCE_MIN, PromptContract.Mood.VALENCE_MAX),
        arousal = values.arousal.coerceIn(PromptContract.Mood.AROUSAL_MIN, PromptContract.Mood.AROUSAL_MAX),
        dominance = values.dominance.coerceIn(PromptContract.Mood.DOMINANCE_MIN, PromptContract.Mood.DOMINANCE_MAX),
        intensity = values.intensity.coerceIn(PromptContract.Mood.INTENSITY_MIN, PromptContract.Mood.INTENSITY_MAX)
    )

    /** 按三个维度生成 1–2 句行为提示（不是给用户看的文案，是给模型的指令）。 */
    fun behaviorHints(values: Values): List<String> {
        val hints = mutableListOf<String>()
        hints += when {
            values.valence <= -1 -> "话更少，情绪压着说；不要主动安慰对方，也不要撒娇。"
            values.valence >= 1 -> "语气松一点，可以多露半句自己的事。"
            else -> "语气按平常来，不用刻意热络。"
        }
        hints += when {
            values.arousal <= 1 -> "句子更短、更慢，少用感叹号。"
            values.arousal >= 4 -> "反应更直接、快一点，但别写成一连串感叹句。"
            else -> "节奏正常，不要把每句都写满。"
        }
        if (values.dominance <= 1) {
            hints += "别替对方拿主意，多问一句。"
        } else if (values.dominance >= 4) {
            hints += "可以更笃定，但不要命令对方。"
        }
        return hints.take(2)
    }

    // ---------------------------------------------------------------- 小工具

    private fun Values.normalized(): Values = copy(
        label = label.trim().take(MAX_LABEL_CHARS),
        valence = valence.coerceIn(-2, 2),
        arousal = arousal.coerceIn(0, 5),
        dominance = dominance.coerceIn(0, 5),
        intensity = intensity.coerceIn(1, 5),
        reason = reason.take(Mood.MAX_REASON_CHARS)
    )

    private fun toward(value: Int, target: Int, steps: Int, min: Int, max: Int): Int = when {
        value > target -> (value - steps).coerceAtLeast(target).coerceIn(min, max)
        value < target -> (value + steps).coerceAtMost(target).coerceIn(min, max)
        else -> value
    }

    private fun parseObject(raw: String?): JsonValue.Obj? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val candidates = listOfNotNull(
            text,
            if (start >= 0 && end > start) text.substring(start, end + 1) else null
        ).distinct()
        for (candidate in candidates) {
            val parsed = try {
                Json.parse(candidate)
            } catch (_: JsonParseException) {
                null
            }
            if (parsed is JsonValue.Obj) return parsed
        }
        return null
    }

    private fun string(value: JsonValue?): String? = (value as? JsonValue.Str)?.value

    private fun number(value: JsonValue?): Int? = when (value) {
        is JsonValue.Num -> value.asInt
        is JsonValue.Str -> value.value.trim().toIntOrNull()
        else -> null
    }
}
