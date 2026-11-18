package com.yap.agentd.tools

import com.yap.app.core.json.JsonValue

/**
 * 服务端工具表（`spec/mood` §4、`spec/diary`、`spec/stickers`、`docs/design/server-authoritative.md` §3.5）。
 *
 * **一处真源**：`tools` 数组、给模型看的 function schema、以及系统提示词里的「# 你能做什么」
 * 段全部由这里的 [ToolSpec] 生成（`capabilityPrompt()`）。加一个工具只改这一处，
 * 提示词不可能落后于实际能力——这是本轮「提示词必须与实际工具表一致」的落点。
 *
 * 与客户端 `app/src/main/java/com/yap/app/domain/ToolProtocol.kt` 的关系：
 * - **名字一致**（`stay_silent` / `defer_reply` / `set_mood` / `set_activity` / `diary` /
 *   `remember` / `recall` / `web_search` / `web_fetch` / `sticker`）：模型在两端看到同一套动词，
 *   提示词与备份里的 `toolTraceJson` 也能互通；
 * - **服务端只注册自己真的能执行的那些**：不注册 `think`（纯提示词技巧）、`update_persona`
 *   （服务端只走 pending patch 端点）、`set_quiet_hours`（服务端还没有作息写入）、
 *   `listen` / `watch`（服务端看不到语音/视频内容）、`sticker.make`（服务端不做图像合成）。
 *   宁可不提，也不要让模型调用一个「承诺了但会失败」的工具。
 */
object ServerToolCatalog {

    const val STAY_SILENT = "stay_silent"
    const val DEFER_REPLY = "defer_reply"
    const val SET_MOOD = "set_mood"
    const val SET_ACTIVITY = "set_activity"
    const val DIARY = "diary"
    const val REMEMBER = "remember"
    const val RECALL = "recall"
    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"
    const val STICKER = "sticker"

    /**
     * 一个工具：名字 + 给模型看的中文说明 + JSON Schema + 「和别的工具怎么区分」。
     *
     * [contrast] 只进提示词，不进 tools 数组（模型最容易搞混的几对写在这里：
     * `stay_silent` vs `defer_reply`、`diary` vs `remember`、`set_activity` 不是睡觉）。
     */
    data class ToolSpec(
        val name: String,
        val description: String,
        val parametersJson: String,
        val contrast: String = ""
    )

    // ------------------------------------------------------------------ 描述（给模型看，唯一真源）

    private const val STAY_SILENT_DESCRIPTION =
        "这一轮你只想「已读」、不想回复时调用（累了、在忙、对方只是随口一句、现在不想说话）。" +
            "调用后这一轮不产生任何消息，也不留念头——对方只会看到「已读」。" +
            "不要用它逃避重要或着急的话题，也别连续两轮都用；对方在等答案就回一句短的。"

    private const val STAY_SILENT_CONTRAST =
        "和 defer_reply 的区别：defer_reply 是「过一会儿再回」（会留下一个到点会想起的念头），" +
            "stay_silent 是「这一轮不回」（什么都不留）。"

    private const val DEFER_REPLY_DESCRIPTION =
        "这一轮先不回、过一会儿再找他时调用（想等自己缓一缓、不知道怎么接、手头正忙）。" +
            "调用后这一轮不会发出消息，到点之后你才会想起这件事。"

    private const val DEFER_REPLY_CONTRAST =
        "不想回就别硬回；但别用它敷衍正在着急的人——那种情况要么回一句短的，要么用 stay_silent 说清楚。"

    private const val SET_MOOD_DESCRIPTION =
        "你此刻的心情跟刚才不一样了才调用（开心、累、烦躁、想念……）：写一个词、三个维度与为什么。" +
            "一次只写此刻真实的那一个词，别为了有变化而编。"

    private const val SET_ACTIVITY_DESCRIPTION =
        "你接下来一段时间要在忙某件具体的事（工作、通勤、吃饭、社交、家务、学习、休息……）时调用：" +
            "写清在做什么、还要多久、这段时间忙不忙。只是随口说「我去忙了」、或对方正着急时，不要用它把话说死。"

    private const val SET_ACTIVITY_CONTRAST =
        "它管的是「我在做事」（到点自动失效），不等于睡觉或安静时段。"

    private const val DIARY_DESCRIPTION =
        "你自己的备忘本：想到要记住的小事（答应过的事、要问的问题、看到的地址、想提醒自己的时间点）时用 add；" +
            "想看看自己记过什么时用 list；记错了、过期了想撕掉时用 remove。" +
            "要「过一会儿提醒我」就写 remindInMinutes，或者写 remindAt（毫秒时间戳）；到点会变成你心里的一个念头。"

    private const val DIARY_CONTRAST =
        "它记的是**你自己**的事，不是关于对方的记忆（那是 remember）。"

    private const val REMEMBER_DESCRIPTION =
        "值得长期记住的事（对方的偏好、重要经历、你们的约定）才调用；一次性的情绪、寒暄、临时安排不要记。"

    private const val REMEMBER_CONTRAST =
        "和 diary 的区别：remember 记的是「关于对方/你们之间」的事，会进长期记忆并被 recall 检索；" +
            "diary 是你自己的备忘本。"

    private const val RECALL_DESCRIPTION =
        "想不起对方提过的事、要找很久以前聊过的内容、或想知道自己记过什么的时候调用；日常闲聊不要调用。"

    private const val WEB_SEARCH_DESCRIPTION =
        "需要最新事实、新闻、价格、版本号、你不确定的信息时调用；纯聊天、或你本来就知道的常识不要调用。"

    private const val WEB_FETCH_DESCRIPTION =
        "已经拿到具体链接、需要读全文时调用；还没拿到链接就先 web_search，不要凭空编 URL。"

    private const val STICKER_DESCRIPTION =
        "表情包：想发一张表情时用 send（stickerId 取「我的表情」列表里的 id）；" +
            "想看看自己有哪些表情时用 list。" +
            "id 只能用真实存在的，别自己编；做不出来的图就老实说做不了，不要假装发过。"

    private const val STICKER_CONTRAST =
        "它管的是「图」，不是文字：想说话直接说，不要为了热闹而发。"

    // ------------------------------------------------------------------ 参数（JSON Schema，直接进 tools[]）

    private const val STAY_SILENT_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "reason": {"type": "string", "description": "为什么这一轮不想说话，一句话，只给自己看；可以不写"}
          }
        }
    """

    private const val DEFER_REPLY_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "minutes": {"type": "integer", "minimum": 5, "maximum": 720, "description": "过多少分钟再考虑开口，默认 45"},
            "reason": {"type": "string", "description": "为什么现在不回，一句话，只给自己看"}
          }
        }
    """

    private const val SET_MOOD_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "label": {"type": "string", "description": "一个词的此刻心情，例如 雀跃 / 有点累 / 低落 / 平静"},
            "valence": {"type": "integer", "minimum": -2, "maximum": 2, "description": "愉快度：负=不悦，正=愉快"},
            "arousal": {"type": "integer", "minimum": 0, "maximum": 5, "description": "唤醒度：0 很困，5 很激动"},
            "dominance": {"type": "integer", "minimum": 0, "maximum": 5, "description": "掌控感：0 被压住，5 掌控"},
            "intensity": {"type": "integer", "minimum": 1, "maximum": 5, "description": "有多浓，1 很淡 5 满脑子都是"},
            "reason": {"type": "string", "description": "为什么会这样，一句话，写给将来的自己看"}
          },
          "required": ["label"]
        }
    """

    private const val SET_ACTIVITY_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "kind": {"type": "string", "enum": ["WORK","COMMUTE","MEAL","SOCIAL","CHORE","STUDY","REST","OTHER"], "description": "这件事属于哪一类，默认 OTHER"},
            "activity": {"type": "string", "description": "用你自己的口吻一句话说清在做什么，例如「在事务所赶第三版方案」"},
            "minutes": {"type": "integer", "minimum": 15, "maximum": 480, "description": "预计还要多久（分钟），15-480，默认 60"},
            "availability": {"type": "string", "enum": ["BUSY","LIMITED","FREE"], "description": "这段时间忙不忙：BUSY 回复更短更晚、不主动开口；LIMITED 回得慢；FREE 只是手头有这件事"}
          },
          "required": ["activity"]
        }
    """

    private const val DIARY_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "action": {"type": "string", "enum": ["add","list","remove"], "description": "add 写一条、list 翻最近的、remove 撕掉一条"},
            "content": {"type": "string", "description": "add 必填：要记下来的事，最多 2000 字，写成将来你自己还看得懂的样子"},
            "noteId": {"type": "string", "description": "remove 必填：list 结果里那条笔记的 id"},
            "remindAt": {"type": "integer", "description": "可选：什么时候提醒你，毫秒时间戳（与 remindInMinutes 二选一）"},
            "remindInMinutes": {"type": "integer", "minimum": 1, "maximum": 43200, "description": "可选：过多少分钟提醒你（1 到 30 天）"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 50, "description": "list 要几条，默认 20，最多 50"}
          },
          "required": ["action"]
        }
    """

    private const val REMEMBER_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "content": {"type": "string", "description": "要记住的一句话，写成将来你还能看懂的样子"},
            "importance": {"type": "integer", "minimum": 1, "maximum": 5, "description": "重要程度，1 很轻 5 很重要"},
            "tags": {"type": "array", "items": {"type": "string"}, "description": "可选：几个关键词，方便以后找"}
          },
          "required": ["content"]
        }
    """

    private const val RECALL_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "想找什么，关键词或一句话，例如「怕冷」「上次说的那家店」"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 12, "description": "最多想起几条，默认 5"}
          },
          "required": ["query"]
        }
    """

    private const val WEB_SEARCH_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "搜索关键词或一句话问题"},
            "count": {"type": "integer", "minimum": 1, "maximum": 12, "description": "要几条结果，默认 6，最多 12"}
          },
          "required": ["query"]
        }
    """

    private const val WEB_FETCH_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "url": {"type": "string", "description": "要读取的完整 http/https 链接"}
          },
          "required": ["url"]
        }
    """

    private const val STICKER_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "action": {"type": "string", "enum": ["send","list"], "description": "send 发一张已有的表情；list 看看自己有哪些"},
            "stickerId": {"type": "string", "description": "send 必填：要发的表情 id（来自「我的表情」）"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 30, "description": "list 要几条，默认 12"}
          },
          "required": ["action"]
        }
    """

    /** 注册表：顺序就是提示词里的顺序（也决定 tools[] 的顺序）。 */
    val all: List<ToolSpec> = listOf(
        ToolSpec(STAY_SILENT, STAY_SILENT_DESCRIPTION, STAY_SILENT_PARAMETERS.trimIndent(), STAY_SILENT_CONTRAST),
        ToolSpec(DEFER_REPLY, DEFER_REPLY_DESCRIPTION, DEFER_REPLY_PARAMETERS.trimIndent(), DEFER_REPLY_CONTRAST),
        ToolSpec(SET_MOOD, SET_MOOD_DESCRIPTION, SET_MOOD_PARAMETERS.trimIndent()),
        ToolSpec(SET_ACTIVITY, SET_ACTIVITY_DESCRIPTION, SET_ACTIVITY_PARAMETERS.trimIndent(), SET_ACTIVITY_CONTRAST),
        ToolSpec(DIARY, DIARY_DESCRIPTION, DIARY_PARAMETERS.trimIndent(), DIARY_CONTRAST),
        ToolSpec(REMEMBER, REMEMBER_DESCRIPTION, REMEMBER_PARAMETERS.trimIndent(), REMEMBER_CONTRAST),
        ToolSpec(RECALL, RECALL_DESCRIPTION, RECALL_PARAMETERS.trimIndent()),
        ToolSpec(WEB_SEARCH, WEB_SEARCH_DESCRIPTION, WEB_SEARCH_PARAMETERS.trimIndent()),
        ToolSpec(WEB_FETCH, WEB_FETCH_DESCRIPTION, WEB_FETCH_PARAMETERS.trimIndent()),
        ToolSpec(STICKER, STICKER_DESCRIPTION, STICKER_PARAMETERS.trimIndent(), STICKER_CONTRAST)
    )

    val names: List<String> = all.map { it.name }

    fun byName(name: String): ToolSpec? = all.firstOrNull { it.name == name.trim() }

    /** OpenAI 兼容的 `tools[]`（`[{"type":"function","function":{...}}]`）。 */
    fun toOpenAiToolsJson(specs: List<ToolSpec> = all): String {
        val array = specs.mapTo(mutableListOf<JsonValue>()) { spec ->
            val parameters = parseParameters(spec)
            JsonValue.Obj(
                linkedMapOf(
                    "type" to JsonValue.Str("function"),
                    "function" to JsonValue.Obj(
                        linkedMapOf(
                            "name" to JsonValue.Str(spec.name),
                            "description" to JsonValue.Str(spec.description),
                            "parameters" to parameters
                        )
                    )
                )
            )
        }
        return com.yap.app.core.json.Json.stringify(JsonValue.Arr(array))
    }

    /**
     * 系统提示词里的「# 你能做什么」段——**完全由 [all] 生成**。
     *
     * @param webEnabled 没有配联网 provider 时把 `web_search` / `web_fetch` 摘掉，
     *   并补一句「你现在不能联网」。与其勾引它去查，不如让它老实承认不确定。
     */
    fun capabilityPrompt(webEnabled: Boolean = true): String {
        val specs = if (webEnabled) all else all.filter { it.name != WEB_SEARCH && it.name != WEB_FETCH }
        val lines = mutableListOf("# 你能做什么")
        for (spec in specs) {
            lines += "- `${spec.name}`：${spec.description}"
            if (spec.contrast.isNotBlank()) lines += "  - ${spec.contrast}"
        }
        if (!webEnabled) {
            lines += "- 你现在没有联网能力：查不到的实时信息（新闻、价格、天气）就说「不清楚」，不要编。"
        }
        lines += "- 对方可能发图片或文件，但你暂时看不到内容，不要假装看见了。"
        lines += "- 只有真的调用过工具，才能说「我查了」「我记住了」；没调用就说不知道。"
        return lines.joinToString("\n")
    }

    /** 参数 schema 解析成 `JsonValue`；写坏了直接抛（启动期就暴露，而不是等模型调用时）。 */
    private fun parseParameters(spec: ToolSpec): JsonValue =
        com.yap.app.core.json.Json.parse(spec.parametersJson)
}
