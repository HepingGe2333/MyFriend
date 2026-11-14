package com.myfriend.app.core

import android.util.Log
import com.myfriend.app.domain.AgentCore
import com.myfriend.app.domain.AgentCoreCodec
import com.myfriend.app.domain.AgentCoreProtocol
import com.myfriend.app.domain.AgentCoreProtocolException
import com.myfriend.app.domain.DeferPlan
import com.myfriend.app.domain.InProcessAgentCore
import com.myfriend.app.domain.ProactiveCapInput
import com.myfriend.app.domain.SleepDecision
import com.myfriend.app.domain.SleepInput
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Android 侧的 agent 决策客户端（0.11.0 客户端/服务端分层）。
 *
 * 它就是 [AgentCore]：调用点（`ChatRepository` 的睡眠判定、`ToolExecutor` 的 defer_reply 与参数夹取、
 * `AgentLoop` / 心跳 / 主动判定的每日上限）只认这个接口，不需要知道背后是「进程内计算」还是
 * 「请求 agentd」。用继承而不是组合，是为了让调用点少一层 `client.core.sleepDecision(...)` 的转发。
 *
 * 两种实现：
 * - [InProcessAgentCoreClient]（默认）：直接委托 [InProcessAgentCore]，行为与 0.10.x 完全一致；
 * - [RemoteAgentCoreClient]：POST `agentd` 的 `/v1/core/…`，**任何失败都回退到进程内并记日志**。
 *
 * 选哪一种由构建开关决定：`app/build.gradle.kts` 的 `BuildConfig.AGENT_CORE_REMOTE`
 * （默认 false，可用 `-PagentCoreRemote=true` 覆盖）。这是开发/联调开关，不是用户可见设置项。
 */
interface AgentCoreClient : AgentCore

/**
 * 默认实现：进程内。
 *
 * 为什么单独一个 object、而不是让调用点直接用 `InProcessAgentCore`：
 * - 调用点依赖的是「客户端接口」，这样打开远程开关时不用改任何业务代码；
 * - 0.11.0 的验收要求「默认走进程内」这件事在类型层面就能看出来。
 */
object InProcessAgentCoreClient : AgentCoreClient {
    override fun sleepDecision(input: SleepInput): SleepDecision = InProcessAgentCore.sleepDecision(input)

    override fun deferPlan(minutes: Int?, reason: String?, now: Long): DeferPlan =
        InProcessAgentCore.deferPlan(minutes, reason, now)

    override fun proactiveCap(input: ProactiveCapInput): Int = InProcessAgentCore.proactiveCap(input)

    override fun clampToolArgs(tool: String, args: Map<String, Any?>): Map<String, Any?> =
        InProcessAgentCore.clampToolArgs(tool, args)
}

/** 决策层日志：远程失败要留痕。抽成函数式接口，单测不碰 `android.util.Log`。 */
fun interface AgentCoreLogger {
    fun warn(message: String, error: Throwable?)
}

/** 默认日志实现：写 Logcat 的 `MyFriendAgentCore` tag。 */
object LogcatAgentCoreLogger : AgentCoreLogger {
    private const val TAG = "MyFriendAgentCore"

    override fun warn(message: String, error: Throwable?) {
        if (error == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, error)
        }
    }
}

/**
 * 远程实现：把同步决策包成一次带超时的 HTTP 请求。
 *
 * **调用线程**：`execute()` 是同步阻塞的，禁止在主线程调用。App 内所有调用点都是 suspend
 * 函数并包了 `withContext(Dispatchers.IO)`；`ChatRepository.generateAssistant` 这条 flow 是从
 * `viewModelScope`（Main）collect 的，不切 IO 会触发 `NetworkOnMainThreadException`，
 * 然后被这里当成「远程失败」静默回退——远程模式就永远不生效了。
 *
 * 三条硬约束（都是 0.11.0 的验收项）：
 * 1. **超时很短**：决策层被聊天链路同步调用，一次远程卡 30 秒等于聊天卡 30 秒。
 *    所以这里用独立的、毫秒级的超时（默认 1.5 秒），而不是 App 里那个 10 分钟的 `OkHttpClient`；
 * 2. **失败回退**：网络断了 / agentd 没起 / 返回 5xx / JSON 对不上 / 字段缺失，一律回退
 *    [fallback]（默认进程内）并记一行日志——**决策层不能因为服务不可用就让聊天挂掉**；
 * 3. **不改契约**：路径与字段名来自 [AgentCoreProtocol] / [AgentCoreCodec]，服务端读的是同一份常量。
 *
 * @param baseUrl agentd 的根地址，如 `http://10.0.2.2:8788`（模拟器访问宿主机）
 * @param fallback 回退实现（默认 [InProcessAgentCore]；测试可以塞一个记录调用的假实现）
 * @param logger 失败日志
 * @param httpClient 复用 App 的连接池 / 线程池，但超时会被改短
 * @param timeoutMillis 决策请求的总超时（连接 / 读 / 写 / 整次调用都用它）
 */
class RemoteAgentCoreClient(
    baseUrl: String,
    private val fallback: AgentCore = InProcessAgentCore,
    private val logger: AgentCoreLogger = LogcatAgentCoreLogger,
    httpClient: OkHttpClient = OkHttpClient(),
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) : AgentCoreClient {

    private val baseUrl: String = baseUrl.trim().trimEnd('/')
    private val client: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override fun sleepDecision(input: SleepInput): SleepDecision = remoteOrFallback(
        action = "sleep-decision",
        remote = {
            AgentCoreCodec.decodeSleepDecision(
                post(AgentCoreProtocol.SLEEP_DECISION_PATH, AgentCoreCodec.encodeSleepInput(input))
            )
        },
        local = { fallback.sleepDecision(input) }
    )

    override fun deferPlan(minutes: Int?, reason: String?, now: Long): DeferPlan = remoteOrFallback(
        action = "defer-plan",
        remote = {
            AgentCoreCodec.decodeDeferPlan(
                post(
                    AgentCoreProtocol.DEFER_PLAN_PATH,
                    AgentCoreCodec.encodeDeferPlanRequest(minutes, reason, now)
                )
            )
        },
        local = { fallback.deferPlan(minutes, reason, now) }
    )

    override fun proactiveCap(input: ProactiveCapInput): Int = remoteOrFallback(
        action = "proactive-cap",
        remote = {
            AgentCoreCodec.decodeProactiveCap(
                post(
                    AgentCoreProtocol.PROACTIVE_CAP_PATH,
                    AgentCoreCodec.encodeProactiveCapRequest(input)
                )
            )
        },
        local = { fallback.proactiveCap(input) }
    )

    override fun clampToolArgs(tool: String, args: Map<String, Any?>): Map<String, Any?> = remoteOrFallback(
        action = "tool-args",
        remote = {
            AgentCoreCodec.decodeToolArgs(
                post(AgentCoreProtocol.TOOL_ARGS_PATH, AgentCoreCodec.encodeToolArgsRequest(tool, args))
            )
        },
        local = { fallback.clampToolArgs(tool, args) }
    )

    // ------------------------------------------------------------------ 内部

    private inline fun <T> remoteOrFallback(action: String, remote: () -> T, local: () -> T): T = try {
        remote()
    } catch (failure: Exception) {
        // 刻意不 catch Throwable：OOM / StackOverflow 这类问题不该被伪装成「服务没起」。
        logger.warn(
            "agentd 决策调用失败（$action），已回退进程内实现：${failure.message ?: failure::class.simpleName}",
            failure
        )
        local()
    }

    private fun post(path: String, body: String): String {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 统一错误体里有一句中文；没有就退化成「HTTP 5xx」。
                throw AgentCoreProtocolException(
                    AgentCoreProtocol.describeError(response.code, text)
                )
            }
            return text
        }
    }

    companion object {
        /**
         * 默认 1.5 秒：纯函数计算的远程往返，模拟器访问宿主机通常 < 10ms；
         * 1.5 秒还没回来，说明服务多半不在，回退比继续等更有价值。
         */
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 客户端装配：**开关在这里落地**。
 *
 * 为什么单独抽一个工厂：`AppModule` 里写 `if (BuildConfig...)` 的话，单测没法验证
 * 「开关关掉时一个请求都不发」。工厂是纯函数，测试可以给它一个假服务，
 * 断言关掉开关时服务器的命中计数是 0。
 */
object AgentCoreClients {

    fun create(
        remoteEnabled: Boolean,
        baseUrl: String,
        httpClient: OkHttpClient,
        fallback: AgentCore = InProcessAgentCore,
        logger: AgentCoreLogger = LogcatAgentCoreLogger
    ): AgentCoreClient = if (remoteEnabled) {
        RemoteAgentCoreClient(
            baseUrl = baseUrl,
            fallback = fallback,
            logger = logger,
            httpClient = httpClient
        )
    } else {
        // 开关关掉时**根本不构造**远程客户端：不发请求、不建连接、不留后台线程。
        InProcessAgentCoreClient
    }
}
