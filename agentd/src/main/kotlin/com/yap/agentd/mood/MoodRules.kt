package com.yap.agentd.mood

import com.yap.agentd.store.Mood
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
 * - **单次变化幅度 ≤2**（valence/arousal/dominance 各自）；
 * - 失败/无配置时按时间衰减：每小时向 `0 / 2 / 3` 靠一步（不报错）。
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

    /** 单次变化幅度上限（valence / arousal / dominance 各自）。 */
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
     * 套范围 + 相对上一轮限制幅度（±[MAX_DELTA]）。
     *
     * 做法是「先夹范围，再夹相对变化」：模型给 `valence=2` 而上一轮是 -2 时，
     * 结果是 0 而不是 2 —— 一次对话不该让心情从谷底跳到顶。
     */
    fun limit(previous: Values, proposed: Values): Values = Values(
        label = proposed.label.ifBlank { previous.label },
        valence = clampDelta(previous.valence, proposed.valence.coerceIn(-2, 2), -2, 2),
        arousal = clampDelta(previous.arousal, proposed.arousal.coerceIn(0, 5), 0, 5),
        dominance = clampDelta(previous.dominance, proposed.dominance.coerceIn(0, 5), 0, 5),
        intensity = proposed.intensity.coerceIn(1, 5),
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
     * `spec/mood/README.md` §3 的固定提示词段：多维数值 + 1-2 句行为提示 + 反自述提醒。
     *
     * 段标题用规范里的 `# 你的心情`，单独成段插在「# 你此刻的状态」之后：
     * 这样既不改动共享脚手架（`PersonaPromptScaffold` 的段顺序），也不把数值塞进
     * `state_mood` 那一行（那一行只有「label（强度 N/5）」）。
     */
    fun promptSection(values: Values): String {
        val hints = behaviorHints(values)
        return buildString {
            append("# 你的心情\n")
            append("心情：").append(values.label).append("（强度 ").append(values.intensity).append("/5）。")
            append("愉快度 ").append(values.valence)
            append(" / 唤醒度 ").append(values.arousal).append("/5")
            append(" / 掌控感 ").append(values.dominance).append("/5。\n")
            for (hint in hints) append("- 这会影响你怎么说：").append(hint).append("\n")
            append("- 不要直接报数值，也不要说「我的情绪是…」；用说话方式体现。")
        }
    }

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

    private fun clampDelta(previous: Int, proposed: Int, min: Int, max: Int): Int {
        val delta = (proposed - previous).coerceIn(-MAX_DELTA, MAX_DELTA)
        return (previous + delta).coerceIn(min, max)
    }

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
