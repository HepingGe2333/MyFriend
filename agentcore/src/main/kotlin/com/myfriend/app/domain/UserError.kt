package com.myfriend.app.domain

/**
 * 「用户会看见的错误」的结构化表示。
 *
 * ## 为什么要有这个文件
 *
 * 0.10.0 以前，数据层与网络层直接抛中文 `error("人格不存在")` / `ApiException(401, "API Key 无效…")`，
 * UI 再把 `throwable.message` 原样上屏。中文界面没错，但英文界面会在设置页、聊天错误气泡、
 * 备份导入结果里看到中文——i18n 收尾没做完。
 *
 * 0.10.1 起把「错误是什么」和「错误怎么说」分开：
 *
 * - **数据层 / 网络层**只说**哪一种**错误（本文件的 [UserError]），不再拼面向用户的句子；
 * - **UI 层**（`ui/`）把 [UserError] 映射成 `R.string.*`，语言随系统；
 * - **日志与模型提示词**仍然需要中文，走 [debugText]（不经过资源、只进日志/提示词）；
 * - 需要落库的错误（`messages.errorMessage`、`ToolTraceItem.detail`）用 [UserErrorTokens]
 *   存一个稳定的 ASCII 令牌，UI 读出来再翻译；历史数据里的中文原样返回（不改写老库）。
 *
 * 本文件是纯 Kotlin（不碰 Android/Room/Hilt/协程），`agentd` 会一起编译，
 * 因此错误码本身也能当成跨端契约的一部分。
 */
sealed interface UserError {

    /** 数据层：找不到对象、校验失败、状态不允许。 */
    data class Domain(
        val code: DomainErrorCode,
        /** 需要插进文案里的一个字符串参数（例如 Header 名、工具名）。 */
        val arg: String? = null,
        /** 需要插进文案里的一个数字参数（例如条数上限、间隔分钟数）。 */
        val number: Int? = null
    ) : UserError

    /** 网络层：地址、连接、HTTP 状态、流式响应。 */
    data class Network(val kind: NetworkErrorKind) : UserError

    /** 认不出来的异常：UI 显示一句通用文案，细节只进日志。 */
    data object Unknown : UserError
}

/** 数据层的错误码。命名即语义，UI 侧一一对应到 `R.string.error_*`。 */
enum class DomainErrorCode {
    // 找不到 / 已不存在
    PERSONA_NOT_FOUND,
    PERSONA_GONE,
    PERSONA_UNKNOWN,
    SESSION_NOT_FOUND,
    MESSAGE_NOT_FOUND,
    MEMORY_NOT_FOUND,
    API_CONFIG_NOT_FOUND,
    API_CONFIG_GONE,
    API_CONFIG_REQUIRED,
    API_CONFIG_NOT_BOUND,
    API_CONFIG_NOT_BOUND_FOR_PERSONA,
    RULE_NOT_FOUND,
    RULE_GONE,
    PRESET_NOT_FOUND,
    REVISION_NOT_FOUND,
    PENDING_PATCH_NOT_FOUND,

    // 聊天
    MESSAGE_EMPTY,
    MESSAGE_TOO_LONG,
    TITLE_EMPTY,
    NOTHING_TO_REGENERATE,
    ONLY_USER_MESSAGE_EDITABLE,
    NOTHING_TO_REPLY,
    NO_CONVERSATION_YET,

    // 记忆
    MEMORY_EMPTY,
    MEMORY_TOO_LONG,

    // 人格
    PERSONA_NAME_REQUIRED,
    PERSONA_NAME_TOO_LONG,
    PERSONA_DESCRIPTION_TOO_LONG,
    PERSONA_PROMPT_TOO_LONG,
    EVOLVED_PROMPT_EMPTY,

    // 预设
    PRESET_NAME_REQUIRED,
    PRESET_NAME_TOO_LONG,
    PRESET_PROMPT_REQUIRED,
    PRESET_PROMPT_TOO_LONG,
    PRESET_LIBRARY_EMPTY,

    // 导入 / 导出（人格、预设、备份）
    IMPORT_FILE_TOO_LARGE,
    /** 备份为空：没有任何可带走的内容（审计 P2-14）。 */
    BACKUP_EMPTY,
    IMPORT_INVALID_JSON,
    IMPORT_NOT_PRESET_FILE,
    IMPORT_NO_PERSONA_DATA,
    IMPORT_NO_VALID_PERSONA,
    IMPORT_TOO_MANY_PERSONAS,
    BACKUP_SECRET_FIELD,

    // 模型
    MODEL_EMPTY_REPLY,
    MODEL_UNPARSABLE,

    // 主动节奏
    PROACTIVE_TIME_REQUIRED,
    PROACTIVE_INTERVAL_REQUIRED,
    PROACTIVE_INTERVAL_RANGE,

    // API 配置
    API_CONFIG_NAME_REQUIRED,
    API_CONFIG_NAME_TOO_LONG,
    MODEL_ID_REQUIRED,
    MODEL_ID_TOO_LONG,
    PROVIDER_RESERVED,
    /**
     * 有密文、但 Keystore 已经解不出来（安全审计 P2-6）。
     *
     * 与「Key 无效（401）」分开：这是本机密钥不可恢复，用户需要重新填一次 Key。
     */
    API_KEY_UNRECOVERABLE,
    HEADERS_INVALID_JSON,
    HEADER_NAME_REQUIRED,
    HEADER_VALUE_NOT_STRING,
    HEADER_RESERVED,
    TEMPERATURE_RANGE,
    TOP_P_RANGE,
    MAX_OUTPUT_TOKENS_RANGE,
    FREQUENCY_PENALTY_RANGE,
    PRESENCE_PENALTY_RANGE,

    // 他睡着了（内部流程用，正常不会上屏）
    ASLEEP,

    // 工具（会出现在聊天里的工具痕迹上）
    TOOL_UNKNOWN,
    TOOL_MOOD_MISSING,
    TOOL_PERSONA_NOT_FOUND,
    TOOL_QUERY_MISSING,
    TOOL_WEB_DISABLED,
    TOOL_URL_MISSING,
    TOOL_CONTENT_MISSING,
    TOOL_RECALL_MISSING,
    TOOL_PATCH_MISSING,
    TOOL_CARD_EMPTY,
    TOOL_SLEEP_TIME_INVALID,
    TOOL_WAKE_TIME_INVALID,
    TOOL_EDIT_FAILED,
    TOOL_FAILED
}

/** 网络层错误的种类；[NetworkErrorKind.Http] 带状态码，其余是本地判定的。 */
sealed interface NetworkErrorKind {
    data object EmptyUrl : NetworkErrorKind
    data object InvalidUrl : NetworkErrorKind
    data object HttpsRequired : NetworkErrorKind

    /**
     * 用户打开了「允许不安全 HTTP」，但这次构建（release）在系统层就不允许明文。
     *
     * 与 [HttpsRequired] 分开，是为了给出「发布版只允许 HTTPS」这句明确原因，
     * 而不是让请求悄悄失败成超时或一个莫名其妙的 IO 错误。
     */
    data object CleartextDisabled : NetworkErrorKind
    data object UnsupportedScheme : NetworkErrorKind
    data object InvalidBaseUrl : NetworkErrorKind
    data class Http(val status: Int) : NetworkErrorKind
    data object UnparsableModels : NetworkErrorKind
    data object EmptyReply : NetworkErrorKind
    data object UnparsableReply : NetworkErrorKind
    data object EmptyStream : NetworkErrorKind
    data class ResponseTooLong(val limitBytes: Long) : NetworkErrorKind
    data class StreamError(val status: Int?) : NetworkErrorKind
    data object NoContent : NetworkErrorKind
    data object Timeout : NetworkErrorKind
    data object UnknownHost : NetworkErrorKind
    data object NetworkInterrupted : NetworkErrorKind
    data object ChatQueueBusy : NetworkErrorKind
    data object BackgroundQueueBusy : NetworkErrorKind
    data class ProviderUnavailable(val provider: String?) : NetworkErrorKind

    // 联网工具（DeepSeek Search / Fetch）自己的失败原因。
    data object WebKeyMissing : NetworkErrorKind
    data object WebQueryMissing : NetworkErrorKind
    data object WebUrlMissing : NetworkErrorKind
    data object WebBaseMissing : NetworkErrorKind
    data object WebUnparsable : NetworkErrorKind
    data object WebPageUnreachable : NetworkErrorKind
    data class WebStatus(val status: Int) : NetworkErrorKind

    /**
     * 要抓的 URL 被本地校验拒绝（安全审计 P2-11）。
     *
     * Android 以前只判空：`file://`、`javascript:`、`data:` 和内网字面量都会被原样转给
     * 第三方服务。现在与 iOS 的 `validatePageUrl` 对齐，原因细分以便给不同文案。
     */
    data class WebUrlRejected(val reason: WebUrlPolicy.Rejection) : NetworkErrorKind

    /** HTTP 状态码（只有 [Http] 有）。 */
    val statusCode: Int?
        get() = (this as? Http)?.status
}

/**
 * 日志 / 模型提示词里的中文说法。
 *
 * **只允许**给日志、`toString()`、以及「回灌给模型」的提示词用；界面上一律走资源映射。
 * 集中在这里而不是散落在各层，是为了让「哪些中文还在」只有一处答案。
 */
fun UserError.debugText(): String = when (this) {
    is UserError.Unknown -> "未知错误"
    is UserError.Network -> kind.debugText()
    is UserError.Domain -> code.debugText(arg, number)
}

private fun NetworkErrorKind.debugText(): String = when (this) {
    NetworkErrorKind.EmptyUrl -> "地址不能为空"
    NetworkErrorKind.InvalidUrl -> "地址格式不正确，请填写完整的 http(s) 地址"
    NetworkErrorKind.HttpsRequired ->
        "出于安全要求，仅允许 HTTPS。若使用本地服务，请在 API 配置中显式开启“允许不安全 HTTP”。"
    NetworkErrorKind.CleartextDisabled ->
        "发布版只允许 HTTPS：明文 HTTP 只在这个调试构建里可用，请把服务地址改成 https://。"
    NetworkErrorKind.UnsupportedScheme -> "仅支持 HTTP(S) 地址"
    NetworkErrorKind.InvalidBaseUrl -> "API 地址无效，请检查 Base URL"
    is NetworkErrorKind.Http -> when (status) {
        400 -> "请求被服务拒绝（400），请检查模型名称与推理参数"
        401 -> "API Key 无效或未授权（401）"
        403 -> "没有访问该模型的权限（403）"
        404 -> "接口或模型不存在（404），请检查 Base URL 与模型 ID"
        408 -> "服务请求超时（408）"
        413 -> "请求内容过长（413），请缩短上下文或输出长度"
        429 -> "请求过于频繁或额度不足（429）"
        in 500..599 -> "服务端暂时不可用（$status）"
        else -> "请求失败（HTTP $status）"
    }
    NetworkErrorKind.UnparsableModels -> "服务返回了无法解析的模型列表"
    NetworkErrorKind.EmptyReply -> "服务返回了空回复"
    NetworkErrorKind.UnparsableReply -> "服务返回了无法识别的回复内容"
    is NetworkErrorKind.ResponseTooLong -> "这次的回复太长了（单行超过 ${limitBytes / (1024 * 1024)}MB），已中止"
    NetworkErrorKind.EmptyStream -> "模型没有返回任何文字，请检查模型名称或服务配置"
    is NetworkErrorKind.StreamError -> "流式响应返回错误"
    NetworkErrorKind.NoContent -> "服务未返回响应内容"
    NetworkErrorKind.Timeout -> "连接超时，请检查网络或服务地址"
    NetworkErrorKind.UnknownHost -> "无法解析服务器地址，请检查网络连接"
    NetworkErrorKind.NetworkInterrupted -> "网络连接中断"
    NetworkErrorKind.ChatQueueBusy -> "刚才那条还没说完，稍等一下再发一次"
    NetworkErrorKind.BackgroundQueueBusy -> "后台任务排队太久，这次先放弃"
    is NetworkErrorKind.ProviderUnavailable -> "该服务商适配仍在预留中，请选择 OpenAI 兼容"
    NetworkErrorKind.WebKeyMissing -> "DeepSeek 密钥为空，请先在 API 配置中填写"
    NetworkErrorKind.WebQueryMissing -> "搜索关键词不能为空"
    NetworkErrorKind.WebUrlMissing -> "要抓取的网页地址不能为空"
    NetworkErrorKind.WebBaseMissing -> "DeepSeek 地址不能为空"
    NetworkErrorKind.WebUnparsable -> "联网服务返回了无法解析的内容"
    NetworkErrorKind.WebPageUnreachable -> "这个页面抓不到内容"
    is NetworkErrorKind.WebUrlRejected -> when (reason) {
        WebUrlPolicy.Rejection.EMPTY -> "要抓取的网页地址不能为空"
        WebUrlPolicy.Rejection.UNSUPPORTED_SCHEME -> "只能抓取 http / https 网页"
        WebUrlPolicy.Rejection.MISSING_HOST -> "网页地址缺少主机名"
        WebUrlPolicy.Rejection.LOCAL_HOST -> "不支持抓取本机或内网地址"
    }
    is NetworkErrorKind.WebStatus -> when (status) {
        401 -> "DeepSeek 密钥无效或已过期"
        402 -> "DeepSeek 账户余额不足"
        403 -> "抓取被目标站点或安全策略拒绝"
        408 -> "读取超时，稍后再试"
        429 -> "请求太频繁，被限流了（每分钟有 QPS 上限）"
        in 500..599 -> "DeepSeek 服务暂时不可用"
        else -> "请求失败（HTTP $status）"
    }
}

private fun DomainErrorCode.debugText(arg: String?, number: Int?): String = when (this) {
    DomainErrorCode.PERSONA_NOT_FOUND -> "人格不存在"
    DomainErrorCode.PERSONA_GONE -> "人格已不存在"
    DomainErrorCode.PERSONA_UNKNOWN -> "未知人格"
    DomainErrorCode.SESSION_NOT_FOUND -> "会话不存在"
    DomainErrorCode.MESSAGE_NOT_FOUND -> "消息不存在"
    DomainErrorCode.MEMORY_NOT_FOUND -> "记忆已不存在"
    DomainErrorCode.API_CONFIG_NOT_FOUND -> "配置不存在"
    DomainErrorCode.API_CONFIG_GONE -> "API 配置已不存在"
    DomainErrorCode.API_CONFIG_REQUIRED -> "请先为该会话选择 API 配置"
    DomainErrorCode.API_CONFIG_NOT_BOUND -> "这个会话还没有 API 配置"
    DomainErrorCode.API_CONFIG_NOT_BOUND_FOR_PERSONA -> "请先在人格设置中绑定 API，才能生成进化提案"
    DomainErrorCode.RULE_NOT_FOUND -> "规则不存在"
    DomainErrorCode.RULE_GONE -> "规则已不存在"
    DomainErrorCode.PRESET_NOT_FOUND -> "预设不存在"
    DomainErrorCode.REVISION_NOT_FOUND -> "历史版本不存在"
    DomainErrorCode.PENDING_PATCH_NOT_FOUND -> "这条调整已经不存在了"
    DomainErrorCode.MESSAGE_EMPTY -> "消息不能为空"
    DomainErrorCode.MESSAGE_TOO_LONG -> "消息过长"
    DomainErrorCode.TITLE_EMPTY -> "标题不能为空"
    DomainErrorCode.NOTHING_TO_REGENERATE -> "没有可以重新生成的消息"
    DomainErrorCode.ONLY_USER_MESSAGE_EDITABLE -> "只能编辑用户消息"
    DomainErrorCode.NOTHING_TO_REPLY -> "还没有可以回复的消息"
    DomainErrorCode.NO_CONVERSATION_YET -> "该人格还没有对话"
    DomainErrorCode.MEMORY_EMPTY -> "记忆内容不能为空"
    DomainErrorCode.MEMORY_TOO_LONG -> "单条记忆请控制在 2000 字以内"
    DomainErrorCode.PERSONA_NAME_REQUIRED -> "请填写人格名称"
    DomainErrorCode.PERSONA_NAME_TOO_LONG -> "人格名称过长"
    DomainErrorCode.PERSONA_DESCRIPTION_TOO_LONG -> "简介过长"
    DomainErrorCode.PERSONA_PROMPT_TOO_LONG -> "人格提示词超出安全上限"
    DomainErrorCode.EVOLVED_PROMPT_EMPTY -> "进化后的人格提示词为空"
    DomainErrorCode.PRESET_NAME_REQUIRED -> "请填写预设名称"
    DomainErrorCode.PRESET_NAME_TOO_LONG -> "预设名称过长"
    DomainErrorCode.PRESET_PROMPT_REQUIRED -> "预设需要包含提示词"
    DomainErrorCode.PRESET_PROMPT_TOO_LONG -> "提示词超出安全上限"
    DomainErrorCode.PRESET_LIBRARY_EMPTY -> "预设库还是空的，先保存一个预设再导出"
    DomainErrorCode.IMPORT_FILE_TOO_LARGE -> "导入文件过大"
    DomainErrorCode.BACKUP_EMPTY -> "这里还没有可以带走的内容"
    DomainErrorCode.IMPORT_INVALID_JSON -> "文件不是有效的 JSON"
    DomainErrorCode.IMPORT_NOT_PRESET_FILE -> "这不是 MyFriend 的预设文件"
    DomainErrorCode.IMPORT_NO_PERSONA_DATA -> "未找到可导入的人格数据"
    DomainErrorCode.IMPORT_NO_VALID_PERSONA -> "没有可导入的有效人格"
    DomainErrorCode.IMPORT_TOO_MANY_PERSONAS -> "一次最多导入 200 个人格"
    DomainErrorCode.BACKUP_SECRET_FIELD -> "备份里出现了密钥字段，已中止导出"
    DomainErrorCode.MODEL_EMPTY_REPLY -> "模型没有返回内容"
    DomainErrorCode.MODEL_UNPARSABLE -> "模型返回的内容看不懂，请重试或检查模型是否支持 JSON 输出"
    DomainErrorCode.PROACTIVE_TIME_REQUIRED -> "请选择每天的时间"
    DomainErrorCode.PROACTIVE_INTERVAL_REQUIRED -> "请设置间隔小时数"
    DomainErrorCode.PROACTIVE_INTERVAL_RANGE -> "间隔需在 1 到 24 小时之间"
    DomainErrorCode.API_CONFIG_NAME_REQUIRED -> "请填写配置名称"
    DomainErrorCode.API_CONFIG_NAME_TOO_LONG -> "配置名称过长"
    DomainErrorCode.MODEL_ID_REQUIRED -> "请填写模型 ID"
    DomainErrorCode.MODEL_ID_TOO_LONG -> "模型 ID 过长"
    DomainErrorCode.PROVIDER_RESERVED -> "该服务商适配仍在预留中，请选择 OpenAI 兼容"
    DomainErrorCode.API_KEY_UNRECOVERABLE ->
        "这台设备上的密钥已经无法解密（系统恢复过备份或清过锁屏），请重新填写 API Key"
    DomainErrorCode.HEADERS_INVALID_JSON -> "自定义 Headers 必须是合法的 JSON 对象"
    DomainErrorCode.HEADER_NAME_REQUIRED -> "Header 名称不能为空"
    DomainErrorCode.HEADER_VALUE_NOT_STRING -> "Header ${arg.orEmpty()} 的值必须是字符串"
    DomainErrorCode.HEADER_RESERVED -> "不能自定义 Header「${arg.orEmpty()}」：它由 HTTP 框架管理"
    DomainErrorCode.TEMPERATURE_RANGE -> "Temperature 应在 0 到 2 之间"
    DomainErrorCode.TOP_P_RANGE -> "Top P 应在 0 到 1 之间"
    DomainErrorCode.MAX_OUTPUT_TOKENS_RANGE -> "Max Output Tokens 必须大于 0"
    DomainErrorCode.FREQUENCY_PENALTY_RANGE -> "Frequency Penalty 应在 -2 到 2 之间"
    DomainErrorCode.PRESENCE_PENALTY_RANGE -> "Presence Penalty 应在 -2 到 2 之间"
    DomainErrorCode.ASLEEP -> "这个点他在睡觉"
    DomainErrorCode.TOOL_UNKNOWN -> "不认识这个工具：${arg.orEmpty()}"
    DomainErrorCode.TOOL_MOOD_MISSING -> "没有写是什么心情"
    DomainErrorCode.TOOL_PERSONA_NOT_FOUND -> "找不到这个人格"
    DomainErrorCode.TOOL_QUERY_MISSING -> "没有给出要查的内容"
    DomainErrorCode.TOOL_WEB_DISABLED -> "这个人格还没有开启联网"
    DomainErrorCode.TOOL_URL_MISSING -> "没有给出要读的链接"
    DomainErrorCode.TOOL_CONTENT_MISSING -> "没有给出要记住的内容"
    DomainErrorCode.TOOL_RECALL_MISSING -> "没说要回忆什么"
    DomainErrorCode.TOOL_PATCH_MISSING -> "没有给出要改的内容"
    DomainErrorCode.TOOL_CARD_EMPTY -> "这个人格还没有人设，先别改"
    DomainErrorCode.TOOL_SLEEP_TIME_INVALID -> "睡觉时间要写成 HH:mm"
    DomainErrorCode.TOOL_WAKE_TIME_INVALID -> "起床时间要写成 HH:mm"
    DomainErrorCode.TOOL_EDIT_FAILED -> "这次没改成功"
    DomainErrorCode.TOOL_FAILED -> "工具调用失败"
}

/** 数据层抛的「用户可见」异常：只装结构化错误，中文只在 [UserError.debugText] 里取。 */
class UserErrorException(val error: UserError.Domain) : Exception(error.debugText()) {
    constructor(code: DomainErrorCode, arg: String? = null, number: Int? = null) :
        this(UserError.Domain(code, arg, number))
}

/**
 * 需要落库 / 跨协程传递的错误令牌（ASCII，稳定）。
 *
 * 为什么令牌而不是直接存中文：`messages.errorMessage` 与 `ToolTraceItem.detail` 都落库，
 * 存中文等于把语言写死；存令牌则可以在渲染时按当前 UI 语言翻译，而且旧数据（非令牌）
 * 可以原样显示——不需要数据库迁移，也不改写老库。
 */
object UserErrorTokens {
    const val PREFIX = "@mf-err:"
    private const val SEP = "|"

    fun encode(error: UserError): String = PREFIX + when (error) {
        is UserError.Unknown -> "unknown"
        is UserError.Network -> "net" + SEP + networkKey(error.kind)
        is UserError.Domain -> buildList {
            add("domain")
            add(error.code.name)
            if (error.arg != null) add(sanitize(error.arg)) else add("")
            if (error.number != null) add(error.number.toString()) else add("")
        }.joinToString(SEP)
    }

    /** 解不出来就返回 null（调用方应当回落到「原样显示」）。 */
    fun decode(raw: String?): UserError? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith(PREFIX)) return null
        val parts = text.removePrefix(PREFIX).split(SEP)
        return when (parts.firstOrNull()) {
            "unknown" -> UserError.Unknown
            // 「net」后面可能还有一段参数（状态码 / 上限），整体交给 decodeNetwork。
            "net" -> parts.drop(1).joinToString(SEP)
                .takeIf { it.isNotEmpty() }
                ?.let(::decodeNetwork)
                ?.let { UserError.Network(it) }
            "domain" -> {
                val code = parts.getOrNull(1)?.let { name -> DomainErrorCode.entries.firstOrNull { it.name == name } }
                code?.let {
                    UserError.Domain(
                        code = it,
                        arg = parts.getOrNull(2)?.takeIf { value -> value.isNotEmpty() },
                        number = parts.getOrNull(3)?.toIntOrNull()
                    )
                }
            }
            else -> null
        }
    }

    /** 是不是一个令牌（用于区分旧库里的中文原文）。 */
    fun isToken(raw: String?): Boolean = raw?.trim()?.startsWith(PREFIX) == true

    private fun sanitize(value: String): String =
        value.replace(SEP, " ").replace("\n", " ").take(200)

    private fun networkKey(kind: NetworkErrorKind): String = when (kind) {
        NetworkErrorKind.EmptyUrl -> "empty_url"
        NetworkErrorKind.InvalidUrl -> "invalid_url"
        NetworkErrorKind.HttpsRequired -> "https_required"
        NetworkErrorKind.CleartextDisabled -> "cleartext_disabled"
        NetworkErrorKind.UnsupportedScheme -> "unsupported_scheme"
        NetworkErrorKind.InvalidBaseUrl -> "invalid_base_url"
        is NetworkErrorKind.Http -> "http" + SEP + kind.status
        NetworkErrorKind.UnparsableModels -> "unparsable_models"
        NetworkErrorKind.EmptyReply -> "empty_reply"
        NetworkErrorKind.UnparsableReply -> "unparsable_reply"
        NetworkErrorKind.EmptyStream -> "empty_stream"
        is NetworkErrorKind.ResponseTooLong -> "response_too_long" + SEP + kind.limitBytes
        is NetworkErrorKind.StreamError -> "stream_error" + SEP + (kind.status ?: "")
        NetworkErrorKind.NoContent -> "no_content"
        NetworkErrorKind.Timeout -> "timeout"
        NetworkErrorKind.UnknownHost -> "unknown_host"
        NetworkErrorKind.NetworkInterrupted -> "network_interrupted"
        NetworkErrorKind.ChatQueueBusy -> "chat_queue_busy"
        NetworkErrorKind.BackgroundQueueBusy -> "background_queue_busy"
        is NetworkErrorKind.ProviderUnavailable -> "provider_unavailable" + SEP + sanitize(kind.provider.orEmpty())
        NetworkErrorKind.WebKeyMissing -> "web_key_missing"
        NetworkErrorKind.WebQueryMissing -> "web_query_missing"
        NetworkErrorKind.WebUrlMissing -> "web_url_missing"
        NetworkErrorKind.WebBaseMissing -> "web_base_missing"
        NetworkErrorKind.WebUnparsable -> "web_unparsable"
        NetworkErrorKind.WebPageUnreachable -> "web_page_unreachable"
        is NetworkErrorKind.WebStatus -> "web_status" + SEP + kind.status
        is NetworkErrorKind.WebUrlRejected -> "web_url_rejected" + SEP + kind.reason.name
    }

    private fun decodeNetwork(key: String): NetworkErrorKind? = when (key) {
        "empty_url" -> NetworkErrorKind.EmptyUrl
        "invalid_url" -> NetworkErrorKind.InvalidUrl
        "https_required" -> NetworkErrorKind.HttpsRequired
        "cleartext_disabled" -> NetworkErrorKind.CleartextDisabled
        "unsupported_scheme" -> NetworkErrorKind.UnsupportedScheme
        "invalid_base_url" -> NetworkErrorKind.InvalidBaseUrl
        "unparsable_models" -> NetworkErrorKind.UnparsableModels
        "empty_reply" -> NetworkErrorKind.EmptyReply
        "unparsable_reply" -> NetworkErrorKind.UnparsableReply
        "empty_stream" -> NetworkErrorKind.EmptyStream
        "no_content" -> NetworkErrorKind.NoContent
        "timeout" -> NetworkErrorKind.Timeout
        "unknown_host" -> NetworkErrorKind.UnknownHost
        "network_interrupted" -> NetworkErrorKind.NetworkInterrupted
        "chat_queue_busy" -> NetworkErrorKind.ChatQueueBusy
        "background_queue_busy" -> NetworkErrorKind.BackgroundQueueBusy
        "web_key_missing" -> NetworkErrorKind.WebKeyMissing
        "web_query_missing" -> NetworkErrorKind.WebQueryMissing
        "web_url_missing" -> NetworkErrorKind.WebUrlMissing
        "web_base_missing" -> NetworkErrorKind.WebBaseMissing
        "web_unparsable" -> NetworkErrorKind.WebUnparsable
        "web_page_unreachable" -> NetworkErrorKind.WebPageUnreachable
        else -> key.split(SEP).let { parts ->
            when (parts.firstOrNull()) {
                "http" -> parts.getOrNull(1)?.toIntOrNull()?.let(NetworkErrorKind::Http)
                "stream_error" -> NetworkErrorKind.StreamError(parts.getOrNull(1)?.toIntOrNull())
                "provider_unavailable" ->
                    NetworkErrorKind.ProviderUnavailable(parts.getOrNull(1)?.takeIf { it.isNotEmpty() })
                "web_status" -> NetworkErrorKind.WebStatus(parts.getOrNull(1)?.toIntOrNull() ?: 0)
                "web_url_rejected" -> parts.getOrNull(1)
                    ?.let { name -> WebUrlPolicy.Rejection.entries.firstOrNull { it.name == name } }
                    ?.let { NetworkErrorKind.WebUrlRejected(it) }
                "response_too_long" -> NetworkErrorKind.ResponseTooLong(parts.getOrNull(1)?.toLongOrNull() ?: 0L)
                else -> null
            }
        }
    }
}
