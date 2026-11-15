package com.myfriend.agentd

import com.myfriend.agentd.AgentdApi.Response
import com.myfriend.agentd.proactive.LevelPolicy
import com.myfriend.agentd.proactive.MoodState
import com.myfriend.agentd.proactive.PendingProactive
import com.myfriend.agentd.proactive.PersonaState
import com.myfriend.agentd.proactive.ProactiveDecision
import com.myfriend.agentd.proactive.ProactiveEngine
import com.myfriend.agentd.proactive.ProactiveScheduler
import com.myfriend.agentd.proactive.ProactiveSink
import com.myfriend.agentd.proactive.ProactiveSource
import com.myfriend.agentd.proactive.ProactiveTarget
import com.myfriend.agentd.search.Providers
import com.myfriend.agentd.search.SearchErrorKind
import com.myfriend.agentd.search.SearchException
import com.myfriend.agentd.search.SearchLimits
import com.myfriend.agentd.search.SearchRequest
import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.MessageRole
import com.myfriend.agentd.store.MessageSource
import com.myfriend.agentd.store.Persona
import com.myfriend.agentd.store.Session
import com.myfriend.agentd.store.StoreJson
import com.myfriend.app.core.json.Json
import com.myfriend.app.core.json.JsonParseException
import com.myfriend.app.core.json.JsonValue
import java.time.Clock
import kotlin.random.Random

/**
 * 服务端权威模式：联网搜索与主动心跳的路由（`docs/design/server-authoritative.md` §3.4/§3.5）。
 *
 * | 方法 | 路径 | 说明 |
 * | --- | --- | --- |
 * | `POST` | `/v1/tools/web-search` | 服务端执行搜索，返回归一化结果 |
 * | `POST` | `/v1/tools/web-fetch` | 服务端抓取公开页面并抽正文 |
 * | `POST` | `/v1/proactive/tick` | 立刻跑一次主动判定（生产由服务端定时器驱动） |
 *
 * 三条路由都只做「HTTP 形状 ↔ 已测试的领域模块」的翻译：
 * - 搜索/抓取直接调 [com.myfriend.agentd.search.WebSearchService]（provider 请求形状与结果归一化在那里）；
 * - 主动判定复用 `proactive/` 的 [ProactiveEngine] + [ProactiveScheduler]（冷却、每日上限、睡眠门槛都在那里）。
 *
 * 配置来源刻意**只走服务端**（环境变量 / 数据目录），客户端不传 key：
 * `MYFRIEND_AGENTD_SEARCH_PROVIDER`（默认 `MESSAGEAPI`）、`..._BASE`、`..._KEY`；
 * `SEARXNG` 允许请求里带 `baseUrl`（自建实例地址不是秘密），其余 provider 的地址必须由服务端配置。
 * 环境变量的读取器由 [AgentdServices.env] 注入（生产是 `System.getenv`，测试用固定表）。
 */
object ToolsRoutes {

    const val SEARCH_PATH = "/v1/tools/web-search"
    const val FETCH_PATH = "/v1/tools/web-fetch"
    const val TICK_PATH = "/v1/proactive/tick"

    /** 服务端配置项的名字（只读环境变量，不接受客户端传入）。 */
    const val ENV_SEARCH_PROVIDER = "MYFRIEND_AGENTD_SEARCH_PROVIDER"
    const val ENV_SEARCH_BASE = "MYFRIEND_AGENTD_SEARCH_BASE"
    const val ENV_SEARCH_KEY = "MYFRIEND_AGENTD_SEARCH_KEY"

    /** 搜索 provider 的默认值：客户端不再持有搜索配置，缺省沿用 0.12 之前的厂商接口。 */
    const val DEFAULT_SEARCH_PROVIDER = Providers.MESSAGEAPI

    /** 只接受 POST：三条路由都是有副作用的动作（搜索计费、抓取外网、写主动消息）。 */
    fun matches(method: String, target: String): Boolean {
        if (!method.equals("POST", ignoreCase = true)) return false
        val path = target.substringBefore('?')
        return path == SEARCH_PATH || path == FETCH_PATH || path == TICK_PATH
    }

    /**
     * 处理一条路由。校验失败一律抛 [RouteFailure] 并在 [handle] 里翻成统一错误体
     * （错误体沿用 `AgentdApi.errorResponse`：`{"error":{"message":"…","code":"…"}}`）。
     */
    fun handle(services: AgentdServices, target: String, body: String): Response {
        val path = target.substringBefore('?')
        return try {
            when (path) {
                SEARCH_PATH -> searchRoute(services, body)
                FETCH_PATH -> fetchRoute(services, body)
                TICK_PATH -> tickRoute(services, body)
                else -> AgentdApi.errorResponse(404, "not_found", "未知的路由：$path")
            }
        } catch (failure: RouteFailure) {
            AgentdApi.errorResponse(failure.status, failure.code, failure.message ?: "请求不合法")
        } catch (failure: SearchException) {
            searchFailure(failure)
        } catch (unexpected: Exception) {
            // 与 AuthorityRoutes 一致：handler 自己的 bug 变成固定文案的 500，细节只进 stderr。
            AgentdApi.unexpectedFailureResponse(unexpected)
        }
    }

    // ---------------------------------------------------------------- 搜索

    private fun searchRoute(services: AgentdServices, body: String): Response {
        val payload = requireObject(body)
        val query = payload.stringField("query")?.trim().orEmpty()
        if (query.isEmpty()) throw RouteFailure(400, "invalid_argument", "搜索词不能为空")

        val provider = resolveProvider(services, payload)
        val baseUrl = resolveBase(services, provider, payload)
        // key 只从服务端环境读：即使客户端在 body 里塞了 apiKey，也不会被使用（见 README）。
        val apiKey = envValue(services, ENV_SEARCH_KEY).orEmpty()
        val count = payload.intField("count") ?: SearchLimits.DEFAULT_COUNT

        val hits = services.searchService.search(
            SearchRequest(
                query = query,
                count = count,
                provider = provider,
                baseUrl = baseUrl,
                apiKey = apiKey
            )
        )
        val results = hits.map { hit ->
            StoreJson.obj(
                "title" to StoreJson.str(hit.title),
                "url" to StoreJson.str(hit.url),
                "snippet" to StoreJson.str(hit.snippet)
            )
        }
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "provider" to StoreJson.str(provider),
                    "results" to StoreJson.arr(results)
                )
            )
        )
    }

    /**
     * provider：请求体优先，其次服务端环境变量，最后 [DEFAULT_SEARCH_PROVIDER]。
     * 脏值统一走 [Providers.normalize]（与客户端/存储层读旧配置的规则一致）。
     */
    private fun resolveProvider(services: AgentdServices, payload: JsonValue.Obj): String {
        val requested = payload.stringField("provider")?.trim()?.takeIf { it.isNotEmpty() }
            ?: envValue(services, ENV_SEARCH_PROVIDER)
            ?: DEFAULT_SEARCH_PROVIDER
        return Providers.normalize(requested)
    }

    /**
     * base：**只有 `SEARXNG`** 允许请求里覆盖（自建实例地址不是秘密，客户端可能连的是自己的实例）；
     * 其它 provider 的地址一律以服务端 `MYFRIEND_AGENTD_SEARCH_BASE` 为准 —— 否则客户端就能把
     * 服务端的 key 引到任意主机去（SSRF + 密钥外带）。
     */
    private fun resolveBase(services: AgentdServices, provider: String, payload: JsonValue.Obj): String {
        val requested = payload.stringField("baseUrl")?.trim().orEmpty()
        if (provider == Providers.SEARXNG && requested.isNotEmpty()) return requested
        return envValue(services, ENV_SEARCH_BASE).orEmpty()
    }

    // ---------------------------------------------------------------- 抓取

    private fun fetchRoute(services: AgentdServices, body: String): Response {
        val payload = requireObject(body)
        val url = payload.stringField("url")?.trim().orEmpty()
        if (url.isEmpty()) throw RouteFailure(400, "invalid_argument", "URL 不能为空")
        // 服务端抓取一律直连（config = null）：MESSAGEAPI 的 fetch 接口是 LLM 厂商的能力，不是搜索源的能力。
        val page = services.searchService.webFetch(url, config = null)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "url" to StoreJson.str(page.url),
                    "title" to StoreJson.str(page.title),
                    "text" to StoreJson.str(page.text)
                )
            )
        )
    }

    /** 搜索/抓取的错误映射：配置与请求类错误是 400，key 无效 401，限流 429，其余上游故障 502。 */
    private fun searchFailure(error: SearchException): Response {
        val status = when (error.error.kind) {
            SearchErrorKind.EMPTY_QUERY, SearchErrorKind.EMPTY_URL,
            SearchErrorKind.MISSING_KEY, SearchErrorKind.MISSING_BASE,
            SearchErrorKind.INVALID_BASE, SearchErrorKind.URL_REJECTED -> 400
            SearchErrorKind.UNAUTHORIZED -> 401
            SearchErrorKind.RATE_LIMITED -> 429
            else -> 502
        }
        val code = when (status) {
            400 -> "search_bad_request"
            401 -> "search_unauthorized"
            429 -> "search_rate_limited"
            else -> "search_upstream_error"
        }
        // SearchError.message 里只有 provider + 状态码，绝不包含 key（见 SearchHttpClient 的日志脱敏）。
        return AgentdApi.errorResponse(status, code, error.error.message)
    }

    // ---------------------------------------------------------------- 主动心跳

    private fun tickRoute(services: AgentdServices, body: String): Response {
        val store = services.store
        val ticker = services.proactiveTicker
        if (store == null || ticker == null) {
            throw RouteFailure(503, "store_disabled", "服务端尚未启用状态存储（启动时没有配置 --data）")
        }
        // body 可以完全没有（用服务端时钟），也可以是 {} 或 {"now":<毫秒>}。
        val payload = optionalObject(body)
        val explicitNow = payload?.longField("now")
        if (explicitNow != null && explicitNow <= 0L) {
            throw RouteFailure(400, "invalid_argument", "now 必须是正的毫秒时间戳")
        }
        // 固定时钟：同一个 now 重复 tick 只生效一次（去重在 ProactiveTicker/ProactiveScheduler 里）。
        val now = explicitNow ?: store.serverTime()
        val fired = ticker.tick(now)
        val decisions = fired.map { decision ->
            StoreJson.obj(
                "personaId" to StoreJson.str(decision.personaId),
                "sessionId" to StoreJson.str(decision.sessionId),
                "messageId" to StoreJson.str(decision.messageId),
                "reason" to StoreJson.str(decision.reason)
            )
        }
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "now" to StoreJson.num(now),
                    "fired" to StoreJson.num(fired.size),
                    "decisions" to StoreJson.arr(decisions)
                )
            )
        )
    }

    // ---------------------------------------------------------------- 请求体

    private fun requireObject(body: String): JsonValue.Obj =
        optionalObject(body) ?: throw RouteFailure(400, "empty_body", "请求体不能为空")

    /** 解析 JSON 对象；空 body 返回 null（只有 `proactive/tick` 允许没有 body）。 */
    private fun optionalObject(body: String): JsonValue.Obj? {
        val text = body.trim()
        if (text.isEmpty()) return null
        val root = try {
            Json.parse(text)
        } catch (invalid: JsonParseException) {
            throw RouteFailure(400, "invalid_json", "请求体不是合法 JSON：${invalid.message.orEmpty()}")
        }
        return root as? JsonValue.Obj
            ?: throw RouteFailure(400, "invalid_body", "请求体必须是一个 JSON 对象")
    }

    private fun JsonValue.Obj.stringField(key: String): String? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> throw RouteFailure(400, "invalid_argument", "字段 $key 必须是字符串")
    }

    private fun JsonValue.Obj.intField(key: String): Int? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> value.asInt
            ?: throw RouteFailure(400, "invalid_argument", "字段 $key 必须是整数")
        else -> throw RouteFailure(400, "invalid_argument", "字段 $key 必须是整数")
    }

    private fun JsonValue.Obj.longField(key: String): Long? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> value.asLong
            ?: throw RouteFailure(400, "invalid_argument", "字段 $key 必须是整数")
        else -> throw RouteFailure(400, "invalid_argument", "字段 $key 必须是整数")
    }

    private fun envValue(services: AgentdServices, name: String): String? =
        services.env(name)?.trim()?.takeIf { it.isNotEmpty() }

    /** 路由层的可预期错误：由 [handle] 统一翻成 `AgentdApi.errorResponse`。 */
    private class RouteFailure(val status: Int, val code: String, message: String) : Exception(message)
}

/** 一次 tick 里真正落库的主动消息（路由响应里需要 messageId）。 */
internal data class FiredProactive(
    val personaId: String,
    val sessionId: String,
    val messageId: String,
    val reason: String
)

/**
 * `POST /v1/proactive/tick` 的判定状态：把 `proactive/` 的引擎接到 `store/` 上。
 *
 * 为什么不是每个请求 new 一个引擎：`ProactiveScheduler` 的 `lastTickAt` 去重与「刚发过」overlay
 * 必须跨请求共享 —— 同一个 `now` 重复 tick 不能把同一条主动消息发两遍，而两次 tick 之间
 * 存储层可能还没来得及把「上次主动」写回去。所以这个对象跟随 [AgentdServices] 的生命周期。
 *
 * 生产由服务端定时器每秒/每分钟调 [tick]（本阶段的调试入口是 HTTP 路由）；
 * 判定本身仍是纯函数：时间来自 `now`，随机数来自注入的 [roll]。
 */
internal class ProactiveTicker(
    private val store: FileStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val roll: () -> Double = { Random.nextDouble() }
) {

    private val lock = Any()
    private val fired = ArrayList<FiredProactive>()
    private val engine = ProactiveEngine(clock = clock, roll = roll)
    private val scheduler = ProactiveScheduler(
        engine = engine,
        source = ProactiveSource { targets() },
        sink = ProactiveSink { decision -> append(decision) },
        clock = clock
    )

    /** 跑一拍，返回这一拍真正落库的主动消息。同一个 `now` 重复调用返回空列表。 */
    fun tick(now: Long): List<FiredProactive> = synchronized(lock) {
        fired.clear()
        scheduler.tickOnce(now)
        fired.toList()
    }

    /** 只有「有人格的会话」才参与判定：没有会话就没有落库的地方，不掷骰子。 */
    private fun targets(): List<ProactiveTarget> = store.personas().mapNotNull { persona ->
        val session = latestSessionFor(persona.id) ?: return@mapNotNull null
        ProactiveTarget(persona = personaState(persona, session), level = LevelPolicy())
    }

    /**
     * 从存储层读到的人格状态。
     *
     * 刻意用保守默认值：`sleepEnabled = false`（服务端拿不到「他现在睡没睡」的客户端状态，不猜）、
     * 唤醒阈值/作息窗口用 [PersonaState] 的默认值、主动程度用 [LevelPolicy] 的默认值。
     * 唯一从存储层读的两件事是**最近互动时间**（会话的 `lastMessageAt`）与**心情**。
     */
    private fun personaState(persona: Persona, session: Session): PersonaState {
        val mood = store.mood(persona.id)?.let { stored ->
            MoodState(label = stored.label, intensity = stored.intensity, updatedAt = stored.updatedAt)
        } ?: MoodState()
        return PersonaState(
            personaId = persona.id,
            displayName = persona.name,
            sleepEnabled = false,
            lastInteractionAt = session.lastMessageAt ?: 0L,
            mood = mood
        )
    }

    /** 掷中之后落一条 `role = ASSISTANT, source = PROACTIVE` 的消息（契约 §3.3/§3.4 的同一套消息表）。 */
    private fun append(decision: ProactiveDecision) {
        val pending: PendingProactive = decision.pending ?: return
        val session = latestSessionFor(decision.targetPersonaId) ?: return
        val message = store.appendMessage(
            sessionId = session.id,
            role = MessageRole.ASSISTANT,
            content = pending.text,
            source = MessageSource.PROACTIVE
        )
        fired += FiredProactive(
            personaId = decision.targetPersonaId,
            sessionId = session.id,
            messageId = message.id,
            reason = decision.reason.code
        )
    }

    /** 这个人格最近活跃的会话（主动消息写到这里）。 */
    private fun latestSessionFor(personaId: String): Session? =
        store.sessions()
            .filter { it.personaId == personaId }
            .maxByOrNull { it.lastMessageAt ?: it.createdAt }
}
