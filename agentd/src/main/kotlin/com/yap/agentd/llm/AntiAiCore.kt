package com.yap.agentd.llm

import com.yap.app.domain.PromptContract

/**
 * 「# 别像 AI」的 **core（5 条）** 版本（0.15 上下文 profile，`spec/context-profile/README.md`）。
 *
 * 评测做法（`eval/context_search.py` 的 `ANTI_CORE`）：从全量规则里只留最能治「AI 味」的五条，
 * 其余删掉——规则越多，模型越容易把提示词当清单逐条「表演」，反而写得像客服。
 * 保留的五条（顺序固定，逐字不改）：
 *
 * 1. 不反问（每句都反问 = 把话题抛回去）；
 * 2. 不总结对方的话（「所以你的意思是…」）；
 * 3. 不列点、不写说明书；
 * 4. 不总认同/安慰；
 * 5. 不写旁白、动作、神态描写。
 *
 * 与共享契约的关系：前四条**逐字复用** [PromptContract.AntiAi.RULES] 的前四条（`:agentcore`
 * 是唯一文案源，两端与这里不会漂）；第五条是服务端原先挂在段尾的「反旁白」三条里留下的
 * 一条——另外两条（「不要括号补充」「不要第三人称转述」）与 `# 边界` 的四条重复，删掉。
 * 被删掉的共享三条（节奏、不给建议、`stay_silent`）同理：节奏由话风段管，建议由引导段管，
 * `stay_silent` 的用法写在 `# 你能做什么` 的工具说明里，不再在提示词里说两遍。
 */
object AntiAiCore {

    /** 段标题（共享契约）。 */
    const val TITLE: String = PromptContract.AntiAi.TITLE

    /** 第五条：旁白（服务端评测里最常出现的问题：一条消息里两三次「顿了顿」）。 */
    const val NARRATION_RULE: String =
        "不要写动作、神态、心理描写式的旁白（「沉默了一会儿」「顿了顿」「叹了口气」）——只写说出口的话。"

    /** 五条：前四条来自共享契约，第五条是服务端旁白规则。 */
    val RULES: List<String> = PromptContract.AntiAi.RULES.take(4) + NARRATION_RULE

    /** 完整段：标题 + 每条一行 `- …`。 */
    fun section(): String = (listOf(TITLE) + RULES.map { "- $it" }).joinToString("\n")
}
