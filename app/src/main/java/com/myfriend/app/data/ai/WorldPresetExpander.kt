package com.myfriend.app.data.ai

import com.myfriend.app.domain.WorldPreset
import com.myfriend.app.domain.WorldPresetCodec
import javax.inject.Inject
import javax.inject.Singleton

/** 一次世界预设补全的结果；[source] 只给界面用，不写进 `worldJson`。 */
data class WorldPresetResult(
    val preset: WorldPreset,
    val source: String
) {
    val isFallback: Boolean get() = source == WorldPresetSource.FALLBACK
}

object WorldPresetSource {
    const val LLM = "llm"
    const val FALLBACK = "fallback"
}

/**
 * 角色简介 → 世界预设（`spec/persona-guidance/README.md` §2.2 / §6.2）。
 *
 * 提示词直接抄 §6.2 原文，只在末尾补一句「只输出 JSON」。
 * 没有配置 / 调用失败 / 坏 JSON 时走兜底：**至少返回 worldName = 角色名、空数组**，
 * 这样「AI 补全」永远不会把用户刚写的名字弄丢，也不会让界面卡在 loading。
 *
 * v14 起兜底还把**角色简介原文**放进 `notes`（规范 §2.1 的兜底自由文本）：
 * 模型没帮上忙的时候，用户写下的那段设定不该被扔掉——它至少还能进提示词的「其他背景」。
 *
 * 原文（角色简介）只用于这一次调用，不落库、不写日志。
 */
@Singleton
class WorldPresetExpander @Inject constructor(
    private val llm: JsonLlmClient
) {

    suspend fun expand(brief: String, name: String, configId: String? = null): WorldPresetResult {
        val input = brief.trim().ifBlank { name.trim() }
        if (input.isBlank()) return fallback(name, "")
        val raw = runCatching { llm.completeJson(PROMPT, input.take(MAX_BRIEF_CHARS), configId) }
            .getOrNull()
        val parsed = raw?.let { WorldPresetCodec.decode(GuidanceJson.firstObject(it)) }
        return if (parsed != null && !parsed.isEmpty() && parsed.worldName.isNotBlank()) {
            WorldPresetResult(parsed, WorldPresetSource.LLM)
        } else {
            fallback(name, input)
        }
    }

    /**
     * §2.2 的兜底：`worldName` = 角色名，`notes` = 角色简介原文（截到 1200 字），其余空数组。
     *
     * 把简介放进 `notes` 而不是丢掉：那一段是用户自己写的设定，是「其他背景」最原始的材料。
     */
    private fun fallback(name: String, brief: String): WorldPresetResult = WorldPresetResult(
        WorldPreset(
            worldName = name.trim().take(60),
            notes = brief.trim().take(WorldPreset.MAX_NOTES_CHARS)
        ),
        WorldPresetSource.FALLBACK
    )

    companion object {
        const val MAX_BRIEF_CHARS = 4000

        /** §6.2 的原文 + 一句「只输出 JSON」。 */
        val PROMPT: String = """
            你是角色设定助手。用户给出一个角色的简要设定，你要补全一个“世界预设”（角色所处的现实/虚构世界），
            只输出 JSON 对象，不要解释：
            {"worldName":"世界/城市一句话名","era":"时代与地点","description":"100-200字世界与角色处境概述",
             "schedule":[{"day":"工作日|周末|每天","start":"HH:mm","end":"HH:mm","activity":"在做什么"}],
             "relationships":[{"name":"称呼","relation":"关系","note":"一句细节"}],
             "obligations":["3-6 条角色必须承担的事/压力"]}
            规则：
            - 具体、可执行，不要空话；schedule 覆盖角色主要时间与占用；
            - 不要写剧情大纲，不要写用户；
            - 与角色设定不矛盾；不确定的地方留空数组。
            只输出 JSON。
        """.trimIndent()
    }
}
