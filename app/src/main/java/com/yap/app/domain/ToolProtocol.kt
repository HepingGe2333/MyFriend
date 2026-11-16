package com.yap.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 「他」的自主能力（插件式工具）协议。
 *
 * 与 yap-wake 保持同一风格：
 * 1. 客户端把工具清单（[ToolCatalog.all]）交给模型，并附上 [ToolCatalog.capabilityPrompt]；
 * 2. 模型可以用原生 function calling 调用，也可以输出文本代码块：
 *
 * ```yap-tool
 * {"name":"web_search","arguments":{"query":"今天的天气"}}
 * ```
 *
 * 3. 客户端执行后把结果回灌给模型（原生 tool 消息或 [ToolProtocol.renderResultsForModel] 文本），
 *    同时在聊天界面渲染成可展开的「动作」卡片——那张卡片的标题/副标题是**给用户看的**，
 *    走 `data/repo/ToolTraceText` 的 `R.string.tool_trace_*`；本文件只负责模型面向的文本。
 *
 * 本文件只做协议解析与序列化，不碰网络、数据库和 UI，任何坏数据都不抛异常。
 */

/** 解析与序列化工具协议时统一使用的宽松 Json：模型输出经常带多余字段或轻微格式问题。 */
private val toolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 一个可被模型调用的能力 */
data class ToolSpec(
    val name: String,
    val description: String,          // 给模型看，中文，一句话 + 什么时候该用/不该用
    val parametersJson: String        // JSON Schema 字符串，直接塞进 OpenAI tools[].function.parameters
)

object ToolCatalog {
    const val THINK = "think"
    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"
    const val REMEMBER = "remember"
    const val UPDATE_PERSONA = "update_persona"
    const val SET_QUIET_HOURS = "set_quiet_hours"
    const val SET_ACTIVITY = "set_activity"
    const val SET_MOOD = "set_mood"
    const val RECALL = "recall"
    const val DEFER_REPLY = "defer_reply"

    /** 给模型看的一句话能力说明（中文，含该用/不该用的边界）。 */
    private const val THINK_DESCRIPTION =
        "需要认真想清楚、比较几种可能、或者信息不足时调用；日常闲聊不要调用，也不要为了显得深刻而调用。"

    private const val WEB_SEARCH_DESCRIPTION =
        "需要最新事实、新闻、价格、版本号、你不确定的信息时调用；纯聊天、或你本来就知道的常识不要调用。"

    private const val WEB_FETCH_DESCRIPTION =
        "已经拿到具体链接、需要读全文时调用；还没拿到链接就先 web_search，不要凭空编 URL。"

    private const val REMEMBER_DESCRIPTION =
        "值得长期记住的事（对方的偏好、重要经历、你们的约定）才调用；一次性的情绪、寒暄、临时安排不要记。"

    private const val UPDATE_PERSONA_DESCRIPTION =
        "你对自己的设定有了新的认识、想调整表达方式或偏好时才调用；patch 只写要改的字段，不要整段重写。"

    private const val SET_QUIET_HOURS_DESCRIPTION =
        "你想按自己的作息休息、或想避开某些时段时调用；对方正需要陪伴时不要随便设。" +
            "它管的是「不被打扰」（睡觉/安静时段），不是「我在做事」——在忙某件具体的事用 set_activity。"

    private const val SET_ACTIVITY_DESCRIPTION =
        "你接下来一段时间要在忙某件具体的事（工作、通勤、吃饭、社交、家务、学习、休息……）时调用；" +
            "它管的是「我在做事」以及这段忙到什么时候，不等同于安静时段（set_quiet_hours），两者可以同时存在。" +
            "只是随口说「我去忙了」、或对方正着急需要你时，不要用它把话说死。"

    private const val SET_MOOD_DESCRIPTION =
        "你此刻的心情跟刚才不一样了才调用（开心、累、烦躁、想念……）；一次只写此刻真实的那一个词，别为了有变化而编。"

    private const val RECALL_DESCRIPTION =
        "想不起对方提过的事、要找很久以前聊过的内容、或想知道自己看过什么的时候调用；depth 越深翻得越久也越慢（3 会翻到很早以前，甚至翻不出来）；日常闲聊不要调用。"

    private const val DEFER_REPLY_DESCRIPTION =
        "这一轮你想先不回、过一会儿再说时调用（现在不想说话、想等自己缓一缓、不知道怎么接）；" +
            "调用后这一轮不会发出任何消息，对方只会看到「已读」。不想回就别硬回，别用它来敷衍正在着急的人。"

    private const val THINK_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "question": {"type": "string", "description": "要思考的问题，尽量具体"}
          },
          "required": ["question"]
        }
    """

    private const val WEB_SEARCH_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "搜索关键词或一句话问题"},
            "count": {"type": "integer", "minimum": 1, "maximum": 10, "description": "要几条结果，默认 5，最多 10"}
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

    private const val REMEMBER_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "content": {"type": "string", "description": "要记住的一句话，写成将来你还能看懂的样子"},
            "drawer": {"type": "string", "enum": ["SELF", "USER", "EVENT"], "description": "SELF 记你自己和关系，USER 记对方，EVENT 记发生过的事"},
            "importance": {"type": "integer", "minimum": 1, "maximum": 5, "description": "重要程度，1 很轻 5 很重要"}
          },
          "required": ["content", "drawer", "importance"]
        }
    """

    private const val UPDATE_PERSONA_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "patch": {"type": "object", "description": "只写要改的字段，例如 {\"speechStyle\":\"话更少一些\"}"},
            "reason": {"type": "string", "description": "为什么要改，一句话"}
          },
          "required": ["patch", "reason"]
        }
    """

    private const val SET_QUIET_HOURS_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "sleep_at": {"type": "string", "description": "几点睡，24 小时制 HH:mm"},
            "wake_at": {"type": "string", "description": "几点醒，24 小时制 HH:mm"},
            "reason": {"type": "string", "description": "为什么这样安排，一句话"},
            "dnd": {"type": "boolean", "description": "睡前要不要开免打扰；开了睡着时就不会被消息叫醒"}
          },
          "required": ["sleep_at", "wake_at", "reason"]
        }
    """

    private const val SET_ACTIVITY_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "kind": {"type": "string", "enum": ["WORK", "COMMUTE", "MEAL", "SOCIAL", "CHORE", "STUDY", "REST", "OTHER"], "description": "这件事属于哪一类，默认 OTHER"},
            "activity": {"type": "string", "description": "用你自己的口吻一句话说清在做什么，例如「在事务所赶第三版方案」"},
            "minutes": {"type": "integer", "minimum": 15, "maximum": 480, "description": "预计还要多久（分钟），15-480，默认 60"},
            "availability": {"type": "string", "enum": ["BUSY", "LIMITED", "FREE"], "description": "这段时间忙不忙：BUSY 回复更短更晚、不主动开口；LIMITED 回得慢；FREE 只是手头有这件事"}
          },
          "required": ["activity"]
        }
    """

    private const val SET_MOOD_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "mood": {"type": "string", "description": "一个词的此刻心情，例如 雀跃 / 有点累 / 低落 / 平静"},
            "intensity": {"type": "integer", "minimum": 1, "maximum": 5, "description": "有多浓，1 很淡 5 满脑子都是"},
            "reason": {"type": "string", "description": "为什么会这样，一句话，写给将来的自己看"}
          },
          "required": ["mood"]
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

    private const val RECALL_PARAMETERS = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "想找什么，关键词或一句话，例如「怕冷」「上次说的那家店」"},
            "depth": {"type": "string", "description": "回忆深度：1/2/3 或 quick/normal/deep；1 随口想想只翻还在用的，2 认真回忆（默认）连沉下去的和最近聊天一起翻，3 翻箱倒柜连很久以前的都翻"}
          },
          "required": ["query"]
        }
    """

    val all: List<ToolSpec> = listOf(
        ToolSpec(THINK, THINK_DESCRIPTION, THINK_PARAMETERS.trimIndent()),
        ToolSpec(WEB_SEARCH, WEB_SEARCH_DESCRIPTION, WEB_SEARCH_PARAMETERS.trimIndent()),
        ToolSpec(WEB_FETCH, WEB_FETCH_DESCRIPTION, WEB_FETCH_PARAMETERS.trimIndent()),
        ToolSpec(REMEMBER, REMEMBER_DESCRIPTION, REMEMBER_PARAMETERS.trimIndent()),
        ToolSpec(UPDATE_PERSONA, UPDATE_PERSONA_DESCRIPTION, UPDATE_PERSONA_PARAMETERS.trimIndent()),
        ToolSpec(SET_QUIET_HOURS, SET_QUIET_HOURS_DESCRIPTION, SET_QUIET_HOURS_PARAMETERS.trimIndent()),
        ToolSpec(SET_ACTIVITY, SET_ACTIVITY_DESCRIPTION, SET_ACTIVITY_PARAMETERS.trimIndent()),
        ToolSpec(SET_MOOD, SET_MOOD_DESCRIPTION, SET_MOOD_PARAMETERS.trimIndent()),
        ToolSpec(RECALL, RECALL_DESCRIPTION, RECALL_PARAMETERS.trimIndent()),
        ToolSpec(DEFER_REPLY, DEFER_REPLY_DESCRIPTION, DEFER_REPLY_PARAMETERS.trimIndent())
    )

    fun byName(name: String): ToolSpec? = all.firstOrNull { it.name == name.trim() }

    /** 转成 OpenAI 兼容的 tools 数组 JSON 字符串（[{"type":"function","function":{...}}]） */
    fun toOpenAiToolsJson(specs: List<ToolSpec> = all): String = buildJsonArray {
        specs.forEach { spec ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", parametersElement(spec))
                        }
                    )
                }
            )
        }
    }.toString()

    /**
     * 系统提示词里的一段「你可以调用这些工具」，中文，简短（≤ 600 字）。
     * @param webEnabled 没配联网时不必勾引他去查，直接说明「你别装懂」。
     */
    fun capabilityPrompt(webEnabled: Boolean = true): String = """
        ## 你可以调用这些工具
        需要时直接调用：
        - think(question)：理清思路、比较几种可能时用；闲聊别用。
        ${if (webEnabled) "- web_search(query, count?)：要最新事实、价格或你不确定的信息时用。\n        - web_fetch(url)：拿到链接读全文时用。" else "- 你现在没有联网能力。涉及实时信息时，老实说自己不确定，别编。"}
        - remember(content, drawer, importance)：值得长期记的事才记，drawer 取 SELF/USER/EVENT，importance 1-5。
        - update_persona(patch, reason)：有新认识才改，patch 只写要改的字段。
        - set_quiet_hours(sleep_at, wake_at)：想按作息休息时用。
        - set_activity(kind, activity, minutes?)：在忙时用。
        - set_mood(mood, intensity?, reason?)：心情真变了才记一个词。
        - recall(query, depth)：想不起对方提过的事、或要找旧聊天时用；depth 1/2/3，越深翻得越久；闲聊别用。
        - defer_reply(minutes?, reason?)：这一轮先不回时用；对方只看到「已读」，过一会儿再由你决定要不要开口。

        不支持原生调用时，用代码块（可连写）：
        ```${ToolProtocol.BLOCK_LANGUAGE}
        {"name":"web_search","arguments":{"query":"今天的天气"}}
        ```

        结果会交回给你，用自己的口吻说完，别把代码块念给对方。
    """.trimIndent()

    /** parameters 必须是 JSON 对象，坏数据时退化成一个空 object schema，避免整份 tools 失效。 */
    private fun parametersElement(spec: ToolSpec): JsonElement {
        val parsed = runCatching {
            toolJson.parseToJsonElement(spec.parametersJson.ifBlank { EMPTY_PARAMETERS })
        }.getOrNull()
        return parsed as? JsonObject ?: toolJson.parseToJsonElement(EMPTY_PARAMETERS)
    }

    private const val EMPTY_PARAMETERS = """{"type":"object","properties":{}}"""
}

@Serializable
data class ToolInvocation(
    val id: String = "",              // 原生调用带 id；代码块解析出来的自己生成
    val name: String = "",
    val argumentsJson: String = "{}"
) {
    fun argument(name: String): String? {
        val element = argumentsObject()?.get(name) as? JsonPrimitive ?: return null
        if (!element.isString) return null
        return element.content
    }

    fun intArgument(name: String, default: Int): Int {
        val element = argumentsObject()?.get(name) as? JsonPrimitive ?: return default
        return element.intOrNull ?: element.contentOrNull?.trim()?.toIntOrNull() ?: default
    }

    /** 布尔参数：认 true/false、1/0、开/关；没写或认不出返回 null（调用方决定默认值）。 */
    fun booleanArgument(name: String): Boolean? {
        val element = argumentsObject()?.get(name) as? JsonPrimitive ?: return null
        element.booleanOrNull?.let { return it }
        return when (element.contentOrNull?.trim()?.lowercase()) {
            "1", "true", "on", "yes", "开", "是" -> true
            "0", "false", "off", "no", "关", "否" -> false
            else -> null
        }
    }

    /** 参数 JSON 解析失败时退化成 null，调用方按「参数缺失」处理。 */
    private fun argumentsObject(): JsonObject? = runCatching {
        toolJson.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject
    }.getOrNull()
}

/** 一次工具执行的结果 */
data class ToolResult(
    val invocation: ToolInvocation,
    val ok: Boolean,
    val summary: String,              // 回灌给模型的摘要（中文），如「翻了翻记忆 · 2 条」
    val contentForModel: String,      // 回灌给模型的文本（已裁剪，≤ 4000 字）
    val errorMessage: String? = null
)

object ToolProtocol {
    const val BLOCK_LANGUAGE = "yap-tool"

    /**
     * 兼容常量：0.12 更名前的客户端 / 参考服务使用的工具块标记。
     *
     * 只用于解析旧输出，不写进工具提示词；新输出一律用 [BLOCK_LANGUAGE]。
     */
    const val LEGACY_BLOCK_LANGUAGE = "myfriend-tool"

    /** 回灌给模型的内容上限，避免一次搜索把上下文塞爆。 */
    private const val MAX_CONTENT_CHARS = 4000

    /** 摘要/错误信息写进提示词时的长度上限，避免一次失败把上下文占满。 */
    private const val MAX_SUMMARY_CHARS = 200

    private val blockRegex = Regex(
        "```\\s*(?:$BLOCK_LANGUAGE|$LEGACY_BLOCK_LANGUAGE)\\s*([\\s\\S]*?)```",
        setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * 解析文本里的 ```yap-tool ... ``` 代码块，同时返回清理后的正文。
     *
     * - 一段文本里可以有多个块；
     * - 坏块丢弃，但仍然从正文里移除（它本来就是给客户端看的协议块，不能显示给用户）；
     * - 没有块时正文只做 trim。
     */
    fun extract(rawText: String): Pair<String, List<ToolInvocation>> {
        if (rawText.isBlank()) return "" to emptyList()
        val matches = blockRegex.findAll(rawText).toList()
        if (matches.isEmpty()) return rawText.trim() to emptyList()

        val invocations = mutableListOf<ToolInvocation>()
        val cleaned = StringBuilder()
        var cursor = 0
        matches.forEach { match ->
            cleaned.append(rawText, cursor, match.range.first)
            cursor = match.range.last + 1
            val payload = match.groupValues.getOrNull(1)?.trim().orEmpty()
            parseBlock(payload, invocations.size)?.let(invocations::add)
        }
        cleaned.append(rawText, cursor, rawText.length)
        return cleaned.toString().trim() to invocations
    }

    /**
     * 流式渲染时用：把还没闭合的工具块尾巴切掉，别让协议块露给用户看。
     * 只有出现 ```` ```yap-tool ```` 时才截断，普通正文里的代码块不受影响。
     */
    fun visiblePrefix(rawText: String): String {
        if (rawText.isBlank()) return ""
        // 旧标记也要切掉，否则升级后流式回复会把旧协议块露给用户。
        val markers = listOf("```$BLOCK_LANGUAGE", "```$LEGACY_BLOCK_LANGUAGE")
        val index = markers.map { rawText.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: return rawText
        return rawText.substring(0, index).trimEnd()
    }

    /** 解析原生 tool_calls（OpenAI 风格 JSON 数组字符串），坏数据返回空列表。 */
    fun parseNativeCalls(rawCallsJson: String?): List<ToolInvocation> {
        if (rawCallsJson.isNullOrBlank()) return emptyList()
        val root = runCatching {
            toolJson.parseToJsonElement(rawCallsJson.trim())
        }.getOrNull() ?: return emptyList()
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["tool_calls"] as? JsonArray
                ?: root["calls"] as? JsonArray
                ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapIndexedNotNull { index, element ->
            val call = element as? JsonObject ?: return@mapIndexedNotNull null
            // 标准形状把 name/arguments 放在 function 里；也容忍直接平铺的写法。
            val function = call["function"] as? JsonObject ?: call
            val name = function.string("name")?.trim().orEmpty()
            if (name.isBlank()) return@mapIndexedNotNull null
            val arguments = function["arguments"] ?: call["arguments"] ?: call["args"]
            ToolInvocation(
                id = call.string("id")?.trim().orEmpty().ifBlank { "call_native_${index + 1}" },
                name = name,
                argumentsJson = arguments.toArgumentsJson()
            )
        }
    }

    /** 生成回灌给模型的一条 tool 结果消息体（OpenAI chat.completions 的 role=tool 形状） */
    fun toolMessageJson(result: ToolResult): String {
        val invocation = result.invocation
        val callId = invocation.id.ifBlank { "call_${invocation.name.ifBlank { "tool" }}" }
        return buildJsonObject {
            put("role", "tool")
            put("tool_call_id", callId)
            put("content", toolMessageContent(result))
        }.toString()
    }

    /**
     * 一轮工具执行完成后要回灌给模型的整段提示（当模型不支持原生 tool role 时使用文本形式）
     * 形如：
     * 【工具结果】web_search
     * 查询：xxx
     * 摘要：查了 3 个网页
     * 结果：……
     */
    fun renderResultsForModel(results: List<ToolResult>): String =
        results.filter { it.invocation.name.isNotBlank() || it.summary.isNotBlank() }
            .joinToString("\n\n") { renderOne(it) }

    /** 解析单个代码块的内容；形状为 {name,arguments} 或 {tool,args}。 */
    private fun parseBlock(payload: String, index: Int): ToolInvocation? {
        if (payload.isBlank()) return null
        val root = runCatching {
            toolJson.parseToJsonElement(payload) as? JsonObject
        }.getOrNull() ?: return null
        val name = root.string("name") ?: root.string("tool") ?: return null
        if (name.isBlank()) return null
        val arguments = root["arguments"] ?: root["args"] ?: root["parameters"]
        return ToolInvocation(
            id = "call_block_${index + 1}",
            name = name.trim(),
            argumentsJson = arguments.toArgumentsJson()
        )
    }

    /** arguments 为对象时序列化回字符串，为字符串时原样用；其它情况按空参数处理。 */
    private fun JsonElement?.toArgumentsJson(): String {
        val element = this ?: return "{}"
        return when (element) {
            is JsonNull -> "{}"
            is JsonPrimitive -> if (element.isString) element.content.ifBlank { "{}" } else "{}"
            else -> element.toString()
        }
    }

    private fun renderOne(result: ToolResult): String {
        val lines = mutableListOf<String>()
        lines += "【工具结果】${result.invocation.name.ifBlank { "unknown" }}"
        argumentHint(result.invocation)?.let { lines += it }
        if (result.summary.isNotBlank()) {
            lines += "摘要：${result.summary.take(MAX_SUMMARY_CHARS)}"
        }
        val content = result.contentForModel.trim().take(MAX_CONTENT_CHARS)
        if (result.ok) {
            if (content.isNotBlank()) lines += "结果：$content"
        } else {
            val reason = result.errorMessage?.trim()?.ifBlank { null }
                ?: result.summary.ifBlank { "执行失败" }
            lines += "失败：${reason.take(MAX_SUMMARY_CHARS)}"
            if (content.isNotBlank()) lines += "结果：$content"
        }
        return lines.joinToString("\n")
    }

    /** 把模型给的参数回显成一行中文，让模型知道这条结果是它哪次调用的。 */
    private fun argumentHint(invocation: ToolInvocation): String? = when (invocation.name) {
        ToolCatalog.THINK -> invocation.argument("question")?.take(120)?.let { "问题：$it" }
        ToolCatalog.WEB_SEARCH -> invocation.argument("query")?.take(120)?.let { "查询：$it" }
        ToolCatalog.WEB_FETCH -> invocation.argument("url")?.take(200)?.let { "链接：$it" }
        ToolCatalog.REMEMBER -> invocation.argument("content")?.take(120)?.let { "内容：$it" }
        ToolCatalog.RECALL -> invocation.argument("query")?.take(120)?.let { "想找：$it" }
        ToolCatalog.DEFER_REPLY -> invocation.argument("reason")?.take(80)?.let { "原因：$it" }
        ToolCatalog.UPDATE_PERSONA -> invocation.argument("reason")?.take(120)?.let { "原因：$it" }
        ToolCatalog.SET_QUIET_HOURS -> {
            val sleepAt = invocation.argument("sleep_at")
            val wakeAt = invocation.argument("wake_at")
            if (sleepAt.isNullOrBlank() && wakeAt.isNullOrBlank()) {
                null
            } else {
                "作息：${sleepAt ?: "?"} 睡，${wakeAt ?: "?"} 醒"
            }
        }
        ToolCatalog.SET_ACTIVITY -> invocation.argument("activity")?.take(80)?.let { "在忙：$it" }
        else -> null
    }

    /** 回灌内容为空时用错误原因或摘要兜底，保证模型一定能拿到一句可读的话。 */
    private fun toolMessageContent(result: ToolResult): String {
        val content = result.contentForModel.trim().take(MAX_CONTENT_CHARS)
        if (content.isNotBlank()) return content
        val fallback = result.errorMessage?.trim()?.ifBlank { null } ?: result.summary
        return fallback.take(MAX_CONTENT_CHARS)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
