package com.yap.app.domain

/**
 * 「他为什么没主动找我」的可观测词汇表。
 *
 * 背景：真机反馈「他不主动找我」，但心跳每 30 秒都在判定，绝大多数拍本来就"什么都不做"。
 * 没有一行能直接回答「这一拍卡在哪道闸门」的日志时，只能靠猜。这里把每道闸门收成一个
 * **稳定的 reason code**（logcat 能 grep、以后做统计也不会漂），外加一句给本地 debug 看的中文。
 *
 * 为什么放在 `domain/` 而不是 `data/repo/HeartbeatController` 里：
 * - 这里只有纯数据与纯函数：不碰 Android、不碰 Room、不认识 data 层的 `Beat` / `Outcome`；
 * - 所以每条映射都能在 JVM 单测里逐条钉住，日志文案调整不会悄悄改掉排查语义。
 *
 * **它不参与任何产品判定。** 谁该不该主动、概率多少、冷却多久，仍然只由
 * `Heartbeat` / `HeartbeatController` 那一套决定；这里只负责"解释已经发生的事"。
 */
object ProactiveDiagnostics {

    /**
     * 一次心跳没能开口（或开口了）的稳定原因。
     *
     * `code` 是给日志/检索用的标识符，**不要改**；`explanation` 是给人看的一句话，
     * 只出现在本地 debug 日志的兜底 detail 里，改它不会影响任何行为。
     */
    enum class Reason(val code: String, val explanation: String) {
        /** 设置（DataStore）这一拍读不出来，什么都不敢判断。 */
        SETTINGS_UNREADABLE("SETTINGS_UNREADABLE", "读不到本机设置，这一拍不判断"),

        /** 用户把「常驻后台」关了（默认是开的）。 */
        LOOP_DISABLED("LOOP_DISABLED", "常驻循环开关是关的，他只在应用打开时存在"),

        /** 还没有可对话的人格：种子没播完，或者人格被删光了。 */
        NO_PERSONA("NO_PERSONA", "还没有可以对话的人格"),

        /** 按他自己排的作息，此刻在睡觉。 */
        SLEEPING("SLEEPING", "他在睡觉（按人格的作息窗口）"),

        /** 活动状态 BUSY：他在做事，这段不主动开口。 */
        BUSY("BUSY", "他正忙着（活动状态 BUSY），这段不主动开口"),

        /** 第一次心跳：只把概率起点定下来，这一拍不掷骰子。 */
        FIRST_BEAT("FIRST_BEAT", "第一次心跳只定概率起点，这一拍不掷骰子"),

        /** 刚互动完的收尾期：十分钟内根本不掷骰子。 */
        COOL_DOWN("COOL_DOWN", "刚互动过，收尾期内不掷骰子"),

        /** 掷骰没中。这是概率曲线的正常结果，不是故障。 */
        ROLL_MISSED("ROLL_MISSED", "这一拍掷骰没中（正常概率，不是故障）"),

        /** 距离上一次真正思考不到五分钟的下限。 */
        TICK_GAP("TICK_GAP", "距离上一次真正思考还不到五分钟"),

        /** 今天的主动额度用完了（由人格的主动程度决定，不是用户设置）。 */
        DAILY_CAP("DAILY_CAP", "今天的主动额度用完了（由人格的主动程度决定）"),

        /** 另一个 tick 正在跑，这一拍跳过（不排队）。 */
        TICK_BUSY("TICK_BUSY", "上一轮思考还没结束，这一拍跳过"),

        /** 没有可用的 API 配置：即使他想，也发不出去。 */
        NO_CONFIG("NO_CONFIG", "还没有可用的 API 配置"),

        /** 模型这次没给出可解析的决定。 */
        PARSE_FAILED("PARSE_FAILED", "模型这次没给出可解析的决定"),

        /** 他想开口，但被自己的分寸拦住了（正聊着 / 刚说完 / 到上限）。 */
        HELD_BACK("HELD_BACK", "他想开口，但被自己的分寸拦住了（对方正在聊 / 刚说过 / 到上限）"),

        /** 这一拍真的做了点什么（主动消息 / 上网 / 记事……）。 */
        ACTED("ACTED", "这一拍真的做了点什么"),

        /** 他想了，但决定什么都不做（这是设计里的默认答案）。 */
        NO_ACTION("NO_ACTION", "他想了，但决定什么都不做"),

        /** 这一轮出错了，detail 里是异常/失败摘要。 */
        ERROR("ERROR", "这一轮出错了（看 detail 或异常堆栈）"),

        /** 没匹配到已知原因（比如上游改了一句 why）。 */
        UNKNOWN("UNKNOWN", "没有匹配到已知原因（看 detail）")
    }

    /**
     * 拼一条 logcat 用的一行日志。
     *
     * 格式固定为 `beat: fired=… acted=… reason=… detail=…`：前缀和字段名稳定，
     * 既方便人肉看，也方便脚本 grep / 统计。detail 会被压成一行并截断，
     * 免得某次异常里带换行把整条日志拆散。
     */
    fun logLine(
        fired: Boolean,
        acted: Boolean,
        reason: Reason,
        detail: String = ""
    ): String {
        val text = detail
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .ifBlank { reason.explanation }
            .take(MAX_DETAIL_CHARS)
        return "beat: fired=$fired acted=$acted reason=${reason.code} detail=$text"
    }

    /**
     * 心跳早退时 `Beat.why` 的旧中文 → reason code。
     *
     * 新代码每个 return 都显式带 code，所以这个映射主要是兜底与兼容：
     * 记录日志的调用方以后若只拿到 `why`，也能得到一个可检索的 code。
     * 注意空串无法区分 FIRST_BEAT / ROLL_MISSED（老接口两处都返回空串），按 UNKNOWN 处理。
     */
    fun fromBeatWhy(why: String): Reason {
        val text = why.trim()
        if (text.isEmpty()) return Reason.UNKNOWN
        return when {
            text == "读不到设置" -> Reason.SETTINGS_UNREADABLE
            text.contains("常驻循环已关闭") -> Reason.LOOP_DISABLED
            text.contains("还没有可以对话的人格") -> Reason.NO_PERSONA
            text.contains("他在睡觉") -> Reason.SLEEPING
            text.startsWith("他正忙着") || text.contains("他正忙着") -> Reason.BUSY
            text.contains("刚聊完，先安静一会儿") -> Reason.COOL_DOWN
            text.contains("刚想过，再等等") -> Reason.TICK_GAP
            text.contains("他今天不想主动开口") -> Reason.DAILY_CAP
            text.startsWith("今天说够了") || text.contains("今天说够了") -> Reason.DAILY_CAP
            text.contains("这一轮没想成") -> Reason.ERROR
            text.contains("出错") -> Reason.ERROR
            else -> Reason.UNKNOWN
        }
    }

    /**
     * `AgentLoop.Outcome.summary`（一句中文记录）→ reason code。
     *
     * 心跳里「掷中了」之后，模型可能选择什么都不做、被分寸拦住、或者干脆没配置/没解析出来；
     * 这些只体现在 tick 的 summary 里，不体现在 `Beat.why`。这一步把这些"想了但没发生"的情况
     * 翻译成同一个词汇表，logcat 才不会只看到一句 `acted=false`。
     */
    fun fromTickSummary(summary: String): Reason {
        val text = summary.trim()
        if (text.isEmpty()) return Reason.UNKNOWN
        return when {
            text.contains("常驻循环已关闭") -> Reason.LOOP_DISABLED
            text.contains("还没有可以对话的人格") -> Reason.NO_PERSONA
            text.contains("他在睡觉") -> Reason.SLEEPING
            text.contains("还没有可用的 API 配置") -> Reason.NO_CONFIG
            text.contains("这次没想清楚") -> Reason.PARSE_FAILED
            text.contains("上一次思考还没结束") -> Reason.TICK_BUSY
            text.contains("想的时候出错了") -> Reason.ERROR
            text.contains("消息没发出去") -> Reason.ERROR
            text.contains("忍住了") -> Reason.HELD_BACK
            text.contains("什么都没做") -> Reason.NO_ACTION
            else -> Reason.UNKNOWN
        }
    }

    /**
     * 一次「掷中了、真的去想了」的 tick 结果 → reason code。
     *
     * 优先级刻意是「summary 先说事实，why 再说失败」：
     * - tick 抛异常（outcome == null）时 why 是「这一轮没想成」，映射成 [Reason.ERROR]；
     * - tick 正常返回但 summary 说「没配置 / 没解析 / 被忍住 / 什么都没做」时用 summary 的结论；
     * - 都没命中时，按 acted 落回 ACTED / NO_ACTION。
     */
    fun classify(
        fired: Boolean,
        acted: Boolean,
        why: String,
        tickSummary: String = ""
    ): Reason {
        if (!fired) return fromBeatWhy(why)
        val fromSummary = fromTickSummary(tickSummary)
        if (fromSummary != Reason.UNKNOWN) return fromSummary
        if (why.isNotBlank()) {
            val fromWhy = fromBeatWhy(why)
            if (fromWhy != Reason.UNKNOWN) return fromWhy
        }
        return if (acted) Reason.ACTED else Reason.NO_ACTION
    }

    // ---------------------------------------------------------------- 开关读取失败

    /**
     * 「常驻循环」开关读出来之后该做什么。
     *
     * 三态而不是布尔：**读设置失败不等于用户把开关关了**。以前 `getOrDefault(false)` 会把
     * 一次 DataStore 抖动当成「关闭」，直接把前台服务和 WorkManager 都停掉；现在失败时
     * 保持现状（[KEEP]），等服务活着的下一轮或下一次设置变化再纠正。
     */
    enum class LoopSwitch {
        /** 开启：起前台服务 + 排 15 分钟兜底。 */
        RUN,

        /** 明确关闭：停服务、撤掉兜底。 */
        STOP,

        /** 读不出来：什么都不动，保留当前状态。 */
        KEEP
    }

    fun loopSwitch(enabled: Boolean?): LoopSwitch = when (enabled) {
        true -> LoopSwitch.RUN
        false -> LoopSwitch.STOP
        null -> LoopSwitch.KEEP
    }

    /**
     * 主动规则 Worker 该走哪条路。
     *
     * 三态的原因与 [loopSwitch] 相同：读不到设置时**不要猜**。
     * 猜「关」会退回 1.2 的老行为（绕过模型直接按规则发消息），
     * 猜「开」又会在用户真的关掉循环时多调一次模型——两者都是错误的默认值。
     */
    enum class WorkerBranch {
        /** 常驻循环开着：让循环自己决定要不要说话，并顺手把服务拉起来。 */
        RUN_AGENT_LOOP,

        /** 用户明确关了循环：退回 1.2 的定时规则。 */
        RUN_LEGACY_RULES,

        /** 读不到设置：这一轮什么都不做。 */
        SKIP
    }

    fun workerBranch(agentLoopEnabled: Boolean?): WorkerBranch = when (agentLoopEnabled) {
        true -> WorkerBranch.RUN_AGENT_LOOP
        false -> WorkerBranch.RUN_LEGACY_RULES
        null -> WorkerBranch.SKIP
    }

    /** detail 最多留这么长：logcat 一行别被一段模型输出撑爆。 */
    private const val MAX_DETAIL_CHARS = 180
}
