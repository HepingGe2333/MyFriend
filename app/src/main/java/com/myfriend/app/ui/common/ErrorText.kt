package com.myfriend.app.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.myfriend.app.R
import com.myfriend.app.domain.DomainErrorCode
import com.myfriend.app.domain.NetworkErrorKind
import com.myfriend.app.domain.UserError
import com.myfriend.app.domain.UserErrorTokens
import com.myfriend.app.domain.WebUrlPolicy
import com.myfriend.app.data.remote.ServerApiException
import com.myfriend.app.network.toUserError

/**
 * 错误 → 界面文案。
 *
 * **这是数据层/网络层的错误唯一允许上屏的地方**：所有 `ui/` 代码都不再显示
 * `throwable.message`（那是给日志看的中文），而是把结构化错误交给这里换一条 `R.string.*`。
 * 语言随系统，英文界面不会再冒出中文。
 *
 * 落库的错误（`messages.errorMessage`、`ToolTraceItem.detail`）存的是
 * [UserErrorTokens] 令牌，[stored] 负责解开；老库里的中文原样显示（不改写历史数据）。
 */
object ErrorText {

    /** 这些状态码有专属文案（不带占位符）；其余走 `%1$d` 的通用文案。 */
    private val SPECIFIC_HTTP_CODES = setOf(400, 401, 403, 404, 408, 413, 429)
    private val SPECIFIC_WEB_CODES = setOf(401, 402, 403, 408, 429)

    /**
     * 把任意异常换成一句人话。
     *
     * 认不出来的异常回落到 [fallback]（各页面自己的「导出失败」之类），
     * 没给就回落到「未知错误」——**绝不**回落到 `throwable.message`。
     */
    fun of(context: Context, error: Throwable?, @StringRes fallback: Int? = null): String {
        if (error == null) return context.getString(fallback ?: R.string.error_unknown)
        // 服务端权威写失败有专门的几句人话：编辑页/记忆页/预设页保存失败时，
        // 「保存失败」太笼统，用户需要知道是没连上、还是服务端拒了、还是图片太大。
        if (error is ServerApiException) return serverText(context, error)
        val mapped = error.toUserError()
        if (mapped == UserError.Unknown) return context.getString(fallback ?: R.string.error_unknown)
        return of(context, mapped)
    }

    /**
     * 服务端错误的几句话。
     *
     * 只按结构化 code/status 分类，**不显示服务端原文**：服务端文案是给开发/部署看的
     * （可能是英文技术句），用户界面统一用本机语言的同一套说法。
     */
    fun serverText(context: Context, error: ServerApiException): String {
        val text = error.message.orEmpty()
        return when {
            error.code == "media_too_large" || error.status == 413 ->
                context.getString(R.string.error_server_media_too_large)
            error.code == "media_compress_failed" ||
                error.code == "media_read_failed" ||
                error.code == "media_empty" ||
                error.code == "media_failed" ||
                text.contains("media", ignoreCase = true) ->
                context.getString(R.string.error_server_media_failed)
            error.code == "timeout" ||
                error.code == "io_error" ||
                error.code == "server_unavailable" ->
                context.getString(R.string.error_server_unreachable)
            else -> context.getString(R.string.error_server_rejected)
        }
    }

    fun of(context: Context, error: UserError?): String = when (error) {
        null, UserError.Unknown -> context.getString(R.string.error_unknown)
        is UserError.Domain -> domainText(context, error)
        is UserError.Network -> networkText(context, error.kind)
    }

    /**
     * 落库的错误文本：是令牌就翻译，是旧数据（中文）就原样返回。
     * 空串返回 null，方便调用方回落到别的默认文案。
     */
    fun stored(context: Context, raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val decoded = UserErrorTokens.decode(text) ?: return text
        return of(context, decoded)
    }

    /** 连接测试成功时的说法（失败走 [of]）。 */
    fun connectionSuccess(context: Context, modelCount: Int): String =
        if (modelCount <= 0) {
            context.getString(R.string.api_connect_success)
        } else {
            context.resources.getQuantityString(R.plurals.api_connect_success_models, modelCount, modelCount)
        }

    @StringRes
    fun domainRes(code: DomainErrorCode): Int = when (code) {
        DomainErrorCode.PERSONA_NOT_FOUND -> R.string.error_persona_not_found
        DomainErrorCode.PERSONA_GONE -> R.string.error_persona_gone
        DomainErrorCode.PERSONA_UNKNOWN -> R.string.error_persona_unknown
        DomainErrorCode.SESSION_NOT_FOUND -> R.string.error_session_not_found
        DomainErrorCode.MESSAGE_NOT_FOUND -> R.string.error_message_not_found
        DomainErrorCode.MEMORY_NOT_FOUND -> R.string.error_memory_not_found
        DomainErrorCode.API_CONFIG_NOT_FOUND -> R.string.error_api_config_not_found
        DomainErrorCode.API_CONFIG_GONE -> R.string.error_api_config_gone
        DomainErrorCode.API_CONFIG_REQUIRED -> R.string.error_api_config_required
        DomainErrorCode.API_CONFIG_NOT_BOUND -> R.string.error_api_config_not_bound
        DomainErrorCode.API_CONFIG_NOT_BOUND_FOR_PERSONA -> R.string.error_api_config_not_bound_for_persona
        DomainErrorCode.RULE_NOT_FOUND -> R.string.error_rule_not_found
        DomainErrorCode.RULE_GONE -> R.string.error_rule_gone
        DomainErrorCode.PRESET_NOT_FOUND -> R.string.error_preset_not_found
        DomainErrorCode.REVISION_NOT_FOUND -> R.string.error_revision_not_found
        DomainErrorCode.PENDING_PATCH_NOT_FOUND -> R.string.error_pending_patch_not_found
        DomainErrorCode.MESSAGE_EMPTY -> R.string.error_message_empty
        DomainErrorCode.MESSAGE_TOO_LONG -> R.string.error_message_too_long
        DomainErrorCode.TITLE_EMPTY -> R.string.error_title_empty
        DomainErrorCode.NOTHING_TO_REGENERATE -> R.string.error_nothing_to_regenerate
        DomainErrorCode.ONLY_USER_MESSAGE_EDITABLE -> R.string.error_only_user_message_editable
        DomainErrorCode.NOTHING_TO_REPLY -> R.string.error_nothing_to_reply
        DomainErrorCode.NO_CONVERSATION_YET -> R.string.error_no_conversation_yet
        DomainErrorCode.MEMORY_EMPTY -> R.string.error_memory_empty
        DomainErrorCode.MEMORY_TOO_LONG -> R.string.error_memory_too_long
        DomainErrorCode.PERSONA_NAME_REQUIRED -> R.string.error_persona_name_required
        DomainErrorCode.PERSONA_NAME_TOO_LONG -> R.string.error_persona_name_too_long
        DomainErrorCode.PERSONA_DESCRIPTION_TOO_LONG -> R.string.error_persona_description_too_long
        DomainErrorCode.PERSONA_PROMPT_TOO_LONG -> R.string.error_persona_prompt_too_long
        DomainErrorCode.EVOLVED_PROMPT_EMPTY -> R.string.error_evolved_prompt_empty
        DomainErrorCode.PRESET_NAME_REQUIRED -> R.string.error_preset_name_required
        DomainErrorCode.PRESET_NAME_TOO_LONG -> R.string.error_preset_name_too_long
        DomainErrorCode.PRESET_PROMPT_REQUIRED -> R.string.error_preset_prompt_required
        DomainErrorCode.PRESET_PROMPT_TOO_LONG -> R.string.error_preset_prompt_too_long
        DomainErrorCode.PRESET_LIBRARY_EMPTY -> R.string.error_preset_library_empty
        DomainErrorCode.IMPORT_FILE_TOO_LARGE -> R.string.error_import_file_too_large
        DomainErrorCode.BACKUP_EMPTY -> R.string.error_backup_empty
        DomainErrorCode.IMPORT_INVALID_JSON -> R.string.error_import_invalid_json
        DomainErrorCode.IMPORT_NOT_PRESET_FILE -> R.string.error_import_not_preset_file
        DomainErrorCode.IMPORT_NO_PERSONA_DATA -> R.string.error_import_no_persona_data
        DomainErrorCode.IMPORT_NO_VALID_PERSONA -> R.string.error_import_no_valid_persona
        DomainErrorCode.IMPORT_TOO_MANY_PERSONAS -> R.string.error_import_too_many_personas
        DomainErrorCode.BACKUP_SECRET_FIELD -> R.string.error_backup_secret_field
        DomainErrorCode.MODEL_EMPTY_REPLY -> R.string.error_model_empty_reply
        DomainErrorCode.MODEL_UNPARSABLE -> R.string.error_model_unparsable
        DomainErrorCode.PROACTIVE_TIME_REQUIRED -> R.string.error_proactive_time_required
        DomainErrorCode.PROACTIVE_INTERVAL_REQUIRED -> R.string.error_proactive_interval_required
        DomainErrorCode.PROACTIVE_INTERVAL_RANGE -> R.string.error_proactive_interval_range
        DomainErrorCode.API_CONFIG_NAME_REQUIRED -> R.string.error_api_config_name_required
        DomainErrorCode.API_CONFIG_NAME_TOO_LONG -> R.string.error_api_config_name_too_long
        DomainErrorCode.MODEL_ID_REQUIRED -> R.string.error_model_id_required
        DomainErrorCode.MODEL_ID_TOO_LONG -> R.string.error_model_id_too_long
        DomainErrorCode.PROVIDER_RESERVED -> R.string.error_provider_reserved
        DomainErrorCode.API_KEY_UNRECOVERABLE -> R.string.error_api_key_unrecoverable
        DomainErrorCode.HEADERS_INVALID_JSON -> R.string.error_headers_invalid_json
        DomainErrorCode.HEADER_NAME_REQUIRED -> R.string.error_header_name_required
        DomainErrorCode.HEADER_VALUE_NOT_STRING -> R.string.error_header_value_not_string
        DomainErrorCode.HEADER_RESERVED -> R.string.error_header_reserved
        DomainErrorCode.TEMPERATURE_RANGE -> R.string.error_temperature_range
        DomainErrorCode.TOP_P_RANGE -> R.string.error_top_p_range
        DomainErrorCode.MAX_OUTPUT_TOKENS_RANGE -> R.string.error_max_output_tokens_range
        DomainErrorCode.FREQUENCY_PENALTY_RANGE -> R.string.error_frequency_penalty_range
        DomainErrorCode.PRESENCE_PENALTY_RANGE -> R.string.error_presence_penalty_range
        DomainErrorCode.ASLEEP -> R.string.error_asleep
        DomainErrorCode.TOOL_UNKNOWN -> R.string.error_tool_unknown
        DomainErrorCode.TOOL_MOOD_MISSING -> R.string.error_tool_mood_missing
        DomainErrorCode.TOOL_PERSONA_NOT_FOUND -> R.string.error_tool_persona_not_found
        DomainErrorCode.TOOL_QUERY_MISSING -> R.string.error_tool_query_missing
        DomainErrorCode.TOOL_WEB_DISABLED -> R.string.error_tool_web_disabled
        DomainErrorCode.TOOL_URL_MISSING -> R.string.error_tool_url_missing
        DomainErrorCode.TOOL_CONTENT_MISSING -> R.string.error_tool_content_missing
        DomainErrorCode.TOOL_RECALL_MISSING -> R.string.error_tool_recall_missing
        DomainErrorCode.TOOL_PATCH_MISSING -> R.string.error_tool_patch_missing
        DomainErrorCode.TOOL_CARD_EMPTY -> R.string.error_tool_card_empty
        DomainErrorCode.TOOL_SLEEP_TIME_INVALID -> R.string.error_tool_sleep_time_invalid
        DomainErrorCode.TOOL_WAKE_TIME_INVALID -> R.string.error_tool_wake_time_invalid
        DomainErrorCode.TOOL_EDIT_FAILED -> R.string.error_tool_edit_failed
        DomainErrorCode.TOOL_FAILED -> R.string.error_tool_failed
    }

    private fun domainText(context: Context, error: UserError.Domain): String {
        val id = domainRes(error.code)
        val arg: Any? = when (error.code) {
            DomainErrorCode.HEADER_VALUE_NOT_STRING,
            DomainErrorCode.HEADER_RESERVED,
            DomainErrorCode.TOOL_UNKNOWN -> error.arg ?: ""
            else -> error.number ?: error.arg
        }
        return if (arg == null) context.getString(id) else context.getString(id, arg)
    }

    /**
     * 网络错误对应的资源 id（`is Http` / `is WebStatus` 会按状态码继续细分）。
     *
     * 单独暴露出来是为了让守卫测试能直接遍历所有种类、检查中英两份资源都在，
     * 不用在单测里构造 `Context`。
     */
    @StringRes
    fun networkRes(kind: NetworkErrorKind): Int = when (kind) {
        NetworkErrorKind.EmptyUrl -> R.string.error_network_empty_url
        NetworkErrorKind.InvalidUrl -> R.string.error_network_invalid_url
        NetworkErrorKind.HttpsRequired -> R.string.error_network_https_required
        NetworkErrorKind.CleartextDisabled -> R.string.error_network_cleartext_disabled
        NetworkErrorKind.UnsupportedScheme -> R.string.error_network_unsupported_scheme
        NetworkErrorKind.InvalidBaseUrl -> R.string.error_network_invalid_base_url
        is NetworkErrorKind.Http -> httpRes(kind.status)
        NetworkErrorKind.UnparsableModels -> R.string.error_network_unparsable_models
        NetworkErrorKind.EmptyReply -> R.string.error_network_empty_reply
        NetworkErrorKind.UnparsableReply -> R.string.error_network_unparsable_reply
        NetworkErrorKind.EmptyStream -> R.string.error_network_empty_stream
        is NetworkErrorKind.StreamError -> R.string.error_network_stream_error
        is NetworkErrorKind.ResponseTooLong -> R.string.error_network_response_too_long
        NetworkErrorKind.NoContent -> R.string.error_network_no_content
        NetworkErrorKind.Timeout -> R.string.error_network_timeout
        NetworkErrorKind.UnknownHost -> R.string.error_network_unknown_host
        NetworkErrorKind.NetworkInterrupted -> R.string.error_network_interrupted
        NetworkErrorKind.ChatQueueBusy -> R.string.error_network_chat_queue_busy
        NetworkErrorKind.BackgroundQueueBusy -> R.string.error_network_background_queue_busy
        is NetworkErrorKind.ProviderUnavailable -> R.string.error_network_provider_unavailable
        NetworkErrorKind.WebKeyMissing -> R.string.error_web_key_missing
        NetworkErrorKind.WebQueryMissing -> R.string.error_web_query_missing
        NetworkErrorKind.WebUrlMissing -> R.string.error_web_url_missing
        NetworkErrorKind.WebBaseMissing -> R.string.error_web_base_missing
        NetworkErrorKind.WebUnparsable -> R.string.error_web_unparsable
        NetworkErrorKind.WebPageUnreachable -> R.string.error_web_page_unreachable
        // 安全 P2-11：被本地 URL 校验拒绝时，按原因给不同说法（与 iOS 的 validatePageUrl 语义一致）。
        is NetworkErrorKind.WebUrlRejected -> when (kind.reason) {
            WebUrlPolicy.Rejection.EMPTY -> R.string.error_web_url_missing
            WebUrlPolicy.Rejection.UNSUPPORTED_SCHEME -> R.string.error_network_web_url_scheme
            WebUrlPolicy.Rejection.MISSING_HOST -> R.string.error_network_web_url_host
            WebUrlPolicy.Rejection.LOCAL_HOST -> R.string.error_network_web_url_local
        }
        is NetworkErrorKind.WebStatus -> webStatusRes(kind.status)
    }

    private fun networkText(context: Context, kind: NetworkErrorKind): String {
        val id = networkRes(kind)
        val arg: Int? = when (kind) {
            is NetworkErrorKind.Http -> kind.status.takeIf { it !in SPECIFIC_HTTP_CODES }
            is NetworkErrorKind.ResponseTooLong -> (kind.limitBytes / (1024 * 1024)).toInt()
            is NetworkErrorKind.WebStatus -> kind.status.takeIf { it !in SPECIFIC_WEB_CODES }
            else -> null
        }
        return if (arg == null) context.getString(id) else context.getString(id, arg)
    }

    @StringRes
    private fun httpRes(status: Int): Int = when (status) {
        400 -> R.string.error_http_400
        401 -> R.string.error_http_401
        403 -> R.string.error_http_403
        404 -> R.string.error_http_404
        408 -> R.string.error_http_408
        413 -> R.string.error_http_413
        429 -> R.string.error_http_429
        in 500..599 -> R.string.error_http_5xx
        else -> R.string.error_http_other
    }

    @StringRes
    private fun webStatusRes(status: Int): Int = when (status) {
        401 -> R.string.error_web_status_401
        402 -> R.string.error_web_status_402
        403 -> R.string.error_web_status_403
        408 -> R.string.error_web_status_408
        429 -> R.string.error_web_status_429
        in 500..599 -> R.string.error_web_status_5xx
        else -> R.string.error_web_status_other
    }


}
