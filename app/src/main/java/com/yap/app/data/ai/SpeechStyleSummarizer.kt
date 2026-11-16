package com.yap.app.data.ai

import com.yap.app.domain.SpeechStyle
import com.yap.app.domain.SpeechStyleCodec
import com.yap.app.domain.SpeechStyleHeuristic
import javax.inject.Inject
import javax.inject.Singleton

/** 话风总结的来源标记：用来在界面上区分「模型总结」与「本地粗略总结」。 */
object SpeechStyleSource {
    const val LLM = "llm"
    const val HEURISTIC = "heuristic"
}

/** 一次话风总结的结果；[source] 进界面状态，不写进 `speechStyleJson`。 */
data class SpeechStyleResult(
    val style: SpeechStyle,
    val source: String
) {
    val isHeuristic: Boolean get() = source == SpeechStyleSource.HEURISTIC
}

/**
 * 微信/QQ 聊天记录 → 话风（`spec/persona-guidance/README.md` §3）。
 *
 * 流程：原文 → 模型（§6.1 的提示词原文）→ 严格 JSON 解析 → [SpeechStyle]；
 * 没配置 / 调用失败 / 输出认不出来 → §3.2 的**本地兜底**（[SpeechStyleHeuristic]），
 * 结果标记 `source = heuristic`，界面提示「本地粗略总结，可手动修改」。
 *
 * 隐私（§3.3）：原文只作为一次请求的 user 消息，**不落库、不写日志、不进备份**；
 * 这个类也不引用任何数据访问对象、数据行或备份结构。
 */
@Singleton
class SpeechStyleSummarizer @Inject constructor(
    private val llm: JsonLlmClient
) {

    suspend fun summarize(rawSample: String, configId: String? = null): SpeechStyleResult {
        val sample = rawSample.trim()
        if (sample.isBlank()) return heuristic(sample)
        val raw = runCatching { llm.completeJson(PROMPT, sample.take(MAX_SAMPLE_CHARS), configId) }
            .getOrNull()
        val parsed = raw?.let(::parseStyle)
        return if (parsed != null && !parsed.isEmpty()) {
            SpeechStyleResult(parsed, SpeechStyleSource.LLM)
        } else {
            heuristic(sample)
        }
    }

    /** 容错解析：剥 markdown 代码块、找第一个完整 JSON 对象；认不出返回 null。 */
    fun parseStyle(raw: String): SpeechStyle? {
        val payload = GuidanceJson.firstObject(raw) ?: return null
        return SpeechStyleCodec.decode(payload)
    }

    private fun heuristic(sample: String): SpeechStyleResult =
        SpeechStyleResult(SpeechStyleHeuristic.summarize(sample), SpeechStyleSource.HEURISTIC)

    companion object {
        /** 一次最多分析多少字：够看几十条消息，又不至于把一次调用撑爆。 */
        const val MAX_SAMPLE_CHARS = 8000

        /**
         * §6.1 的原文，只在末尾补一句「只输出 JSON」。
         *
         * 这段会被 `ChineseLiteralGuardTest` 当成「提示词脚手架」整份放行（见白名单），
         * 不要把它翻译成英文：翻译会改变模型行为。
         */
        val PROMPT: String = """
            你是对话风格分析器。输入是一段微信/QQ 聊天记录（可能含“昵称：内容”）。
            只输出 JSON 对象，不要解释、不要 markdown：
            {"tone":"一句话概括说话风格（中文，<=40字）",
             "phrases":["6-12 条这个人常用的短句/口头禅，尽量原样取，去重"],
             "habits":["3-6 条语言习惯（句长、标点、语气词、是否用表情/省略号等）"],
             "avoid":["3-5 条不该出现的表达（例如书面语、感叹号滥用、长篇大论）"]}
            规则：
            - phrases 必须是聊天记录里真实出现过的、或只做最小归一化（去掉语气词重复），不要编造新句子；
            - 若记录里只有一个参与者，按“这个人”总结；若多人，只总结出现最多的那个人；
            - 不要输出人名，不要评价关系。
            只输出 JSON。
        """.trimIndent()
    }
}
