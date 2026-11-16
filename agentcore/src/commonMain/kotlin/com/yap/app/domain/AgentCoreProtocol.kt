package com.yap.app.domain

/**
 * `agentd` 的 `/v1/core/…` HTTP 契约里所有**字面量**的唯一出处。
 *
 * 为什么把路径与字段名收进 `:agentcore`：服务端（`:agentd` 的 `AgentdApi`）和客户端
 * （Android 的 `RemoteAgentCoreClient`）如果各写一遍字符串，改名时最容易漏掉一边，
 * 而那种漏法在单元测试里往往看不出来——只有真的连上服务才会 404 / 字段读空。
 * 现在两端都引这些常量，`:agentd` 还有一条测试直接断言「路由表 == 这里的常量」。
 *
 * 注意：这里只放**协议**，不放业务规则；规则在 [InProcessAgentCore] 与它委托的
 * `SleepPolicy` / `DeferReply` / `ProactiveLevel` / `ToolArgClamp`。
 */
object AgentCoreProtocol {

    // ------------------------------------------------------------------ 路径

    /** 服务身份与版本，联调时先 curl 这一条。 */
    const val HEALTH_PATH = "/health"

    const val SLEEP_DECISION_PATH = "/v1/core/sleep-decision"
    const val DEFER_PLAN_PATH = "/v1/core/defer-plan"
    const val PROACTIVE_CAP_PATH = "/v1/core/proactive-cap"
    const val TOOL_ARGS_PATH = "/v1/core/tool-args"

    /** 四类决策的完整路径表（顺序固定：错误信息与日志里按这个顺序列）。 */
    val CORE_PATHS: List<String> = listOf(
        SLEEP_DECISION_PATH,
        DEFER_PLAN_PATH,
        PROACTIVE_CAP_PATH,
        TOOL_ARGS_PATH
    )

    // ------------------------------------------------------------------ 通用

    /** 统一错误体：`{"error":{"message":"…","code":"…"}}`。 */
    const val FIELD_ERROR = "error"
    const val FIELD_ERROR_MESSAGE = "message"
    const val FIELD_ERROR_CODE = "code"

    // ------------------------------------------------------------------ 睡眠

    const val FIELD_SLEEP_ENABLED = "sleepEnabled"
    const val FIELD_DND_ON_SLEEP = "dndOnSleep"
    const val FIELD_SLEEP_MINUTES = "sleepMinutes"
    const val FIELD_WAKE_MINUTES = "wakeMinutes"
    const val FIELD_NOW_MINUTES = "nowMinutes"
    const val FIELD_USER_MESSAGES_IN_WINDOW = "userMessagesInWindow"
    const val FIELD_WAKE_THRESHOLD = "wakeThreshold"

    const val FIELD_WAKE = "wake"
    const val FIELD_MOOD_RELAXED = "moodRelaxed"
    const val FIELD_REASON = "reason"
    const val FIELD_MARKS_READ = "marksRead"

    // ------------------------------------------------------------------ defer_reply

    const val FIELD_NOW = "now"
    const val FIELD_MINUTES = "minutes"
    const val FIELD_EARLIEST_AT = "earliestAt"
    const val FIELD_LATEST_AT = "latestAt"

    // ------------------------------------------------------------------ 主动上限

    const val FIELD_LEVEL = "level"
    const val FIELD_MOOD_LABEL = "moodLabel"
    const val FIELD_MOOD_INTENSITY = "moodIntensity"
    const val FIELD_DAILY_CAP = "dailyCap"

    // ------------------------------------------------------------------ 工具参数

    const val FIELD_TOOL = "tool"
    const val FIELD_ARGS = "args"

    /** 客户端做的事：把业务异常翻译成一句人话（`agentd` 的错误码也会带在这里）。 */
    fun describeError(statusCode: Int, body: String): String {
        val message = AgentCoreCodec.decodeErrorMessage(body)
        return if (message.isNullOrBlank()) {
            "agentd 返回 HTTP $statusCode"
        } else {
            "agentd 返回 HTTP $statusCode：$message"
        }
    }
}

/**
 * 远程调用失败时抛的异常。
 *
 * 单独一个类型而不是 `IOException`：上层（`RemoteAgentCoreClient`）要把
 * 「协议对不上 / 字段缺失 / JSON 坏了」和「网络断了」统一当成「这一次远程没成功」，
 * 回退进程内实现并把原因写进日志；用自定义类型能让日志一眼看出是协议问题还是网络问题。
 */
class AgentCoreProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
