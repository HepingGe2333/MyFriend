package com.yap.app.domain

/**
 * 模型优化开关（0.15，schema v20）。
 *
 * 同一个产品要同时伺候两类模型：DeepSeek 系（campus-chat 也在这一类里）对「显式工具规则」
 * 很敏感——把「什么时候该调 set_activity / remember / web_search」写成一段规则，它就会真的照做；
 * GLM 系在同样的规则上收益不明显，反而会让提示词变长、更容易把规则念出来（lean 更好）。
 *
 * 所以这里不是「模型参数」，而是**提示词装配策略**：
 * - [DEEPSEEK]：注入 `# 工具使用` 段（工具/安全规则）；
 * - [GLM]：不注入（lean），其余提示词一个字节都不变；
 * - [AUTO]：按当前 API 配置的 model 名推断（含 `glm` → [GLM]，否则 [DEEPSEEK]）。
 *
 * 为什么 AUTO 落到 DEEPSEEK 而不是「什么都不加」：完全不注入是 GLM 专属的优化，
 * 而绝大多数配置是 DeepSeek / OpenAI 兼容端点；判不出来时选「规则更全」的那一档，
 * 行为与 0.14 的提示词最接近（只是多了一段说明），不会让老用户的模型忽然少拿到规则。
 *
 * 存储是 TEXT（`personas.modelProfile` / `persona_presets.modelProfile`，默认 `'AUTO'`），
 * 认不出的值一律按 [AUTO] 处理（老库、手改数据、以后新增的档位都能安全读）。
 */
enum class ModelProfile {
    AUTO,
    DEEPSEEK,
    GLM;

    /** 落库用的字符串（就是枚举名；写库/备份/服务端映射都按它比对）。 */
    val stored: String get() = name

    companion object {

        /** 列的 SQL 默认值，同时也是「没选过」的语义。 */
        const val DEFAULT_STORED: String = "AUTO"

        /** 读库：认不出 → [AUTO]（不抛异常，界面不能因为一个脏值打不开）。 */
        fun fromStored(raw: String?): ModelProfile {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return AUTO
            return entries.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: AUTO
        }

        /**
         * 这一轮实际生效的档位（永远不是 [AUTO]）。
         *
         * @param profile 人格/预设上选的值；null = 没选过（按 [AUTO]）
         * @param modelName 这一轮真正要发出去的 model 名（`session.model` 优先，其次配置里的）
         */
        fun effective(profile: ModelProfile?, modelName: String?): ModelProfile {
            val chosen = profile ?: AUTO
            return if (chosen == AUTO) infer(modelName) else chosen
        }

        /**
         * 从 model 名推断。只认 `glm`（大小写不敏感）：`glm-4.5` / `GLM-4-Plus` /
         * `zhipu/glm-4` 都算；`gpt-4o` / `deepseek-chat` / `campus-chat` 都落到 [DEEPSEEK]。
         */
        fun infer(modelName: String?): ModelProfile =
            if (modelName.orEmpty().contains("glm", ignoreCase = true)) GLM else DEEPSEEK
    }
}

/**
 * `# 工具使用` 提示块：按 [ModelProfile] 决定注入不注入。
 *
 * 这一段是**面向模型的规则**（不进界面），所以中文/英文都写在 domain 里，
 * 与 `ToolCatalog.capabilityPrompt` 一样不翻译成资源字符串。
 *
 * 与「# 你能做什么」的分工：[ToolCatalog.capabilityPrompt] 回答「你能做什么」（工具清单、
 * 参数、与其它工具的区别）；这里回答「什么时候该用、用的时候别犯什么错」。
 * 刻意只覆盖三个最容易「说了不做」的工具：`set_activity` / `remember` / `web_search`。
 */
object ModelProfilePrompt {

    /** 这一段的小标题（中文人格用）。 */
    const val SECTION = "# 工具使用"

    /** 英文人格用的标题。 */
    const val SECTION_EN = "# Tool use"

    /**
     * 按优化档生成这一段。
     *
     * @param modelName 只在 [profile] 是 AUTO 时用得上（推断模型家族）
     * @param webEnabled 没联网能力时不提 `web_search`，改成一句「没有联网能力就说不确定」
     *
     * @return 整段（含标题）；[ModelProfile.GLM] 返回空串（lean：不注入这一段）
     */
    fun toolsSection(
        profile: ModelProfile?,
        modelName: String?,
        webEnabled: Boolean = true,
        language: PromptLanguage = PromptLanguage.ZH
    ): String {
        if (ModelProfile.effective(profile, modelName) != ModelProfile.DEEPSEEK) return ""
        val title = if (language == PromptLanguage.ZH) SECTION else SECTION_EN
        return (listOf(title) + rules(webEnabled, language).map { "- $it" }).joinToString("\n")
    }

    /**
     * 四条固定规则 + 一条「工具结果只是素材」的收尾。
     *
     * 顺序是有意的：先「怎么表现自己的状态」（最容易被瞎编），再「记住事实」（最容易被
     * 「记住了」糊弄过去），最后才是联网（最容易被当成万能搜索）。
     */
    fun rules(webEnabled: Boolean, language: PromptLanguage = PromptLanguage.ZH): List<String> {
        if (language != PromptLanguage.ZH) return enRules(webEnabled)
        return buildList {
            add(
                "想让他知道你此刻在忙什么时，调用 set_activity 声明活动与结束时间；" +
                    "不忙就传空，不要凭空编日程，也不要在正文里反复汇报自己在做什么。"
            )
            add(
                "对方说出关于他自己的长期事实（生日、工作、家人、喜欢和讨厌的东西）时，调用 remember 记下来；" +
                    "只在正文里说「我记住了」不算记过。"
            )
            add(
                "想不起以前聊过的事、或者对方问「你还记得吗」时，调用 memorize 去翻记忆；" +
                    "翻不到就老实说不记得，不要编一段过去。"
            )
            if (webEnabled) {
                add(
                    "需要实时信息（天气、新闻、价格、赛程、某个人的近况）时调用 web_search；" +
                        "搜不到或结果对不上就说不知道，不要用记忆编造成事实。"
                )
            } else {
                add("你现在没有联网能力：不确定的事直接说不知道，「回头再查」也比编一个答案强。")
            }
            add(
                "工具结果是给你看的素材：用自己的话把结论说完，不要提工具名，" +
                    "也不要把 JSON、代码块、参数原样念给对方。"
            )
        }
    }

    private fun enRules(webEnabled: Boolean): List<String> = buildList {
        add(
            "When you want them to know what you are busy with, call set_activity with the activity " +
                "and when it ends; pass nothing when you are free, and never invent a schedule."
        )
        add(
            "When they tell you a lasting fact about themselves (birthday, job, family, likes, dislikes), " +
                "call remember; saying \"I'll remember that\" in the reply does not count."
        )
        add(
            "When you cannot recall something from earlier, or they ask whether you remember, call memorize " +
                "to search your memories; if nothing comes back, say so instead of inventing a past."
        )
        if (webEnabled) {
            add(
                "For real-time facts (weather, news, prices, fixtures, what someone is up to) call web_search; " +
                    "if the results do not answer it, say you are not sure instead of making something up."
            )
        } else {
            add("You have no web access right now: say you are not sure rather than guessing.")
        }
        add(
            "Tool results are material for you: say the conclusion in your own words, " +
                "never mention tool names or read JSON or code blocks to them."
        )
    }
}
