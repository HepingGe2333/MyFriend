package com.yap.agentd

import com.yap.agentd.AgentdApi.Response
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.proactive.LevelPolicy
import com.yap.agentd.proactive.MoodState
import com.yap.agentd.proactive.PendingProactive
import com.yap.agentd.proactive.PersonaState
import com.yap.agentd.proactive.ProactiveDecision
import com.yap.agentd.proactive.ProactiveEngine
import com.yap.agentd.proactive.ProactiveScheduler
import com.yap.agentd.proactive.ProactiveSink
import com.yap.agentd.proactive.ProactiveTextSource
import com.yap.agentd.proactive.ProactiveSource
import com.yap.agentd.proactive.ProactiveTarget
import com.yap.agentd.search.Providers
import com.yap.agentd.search.SearchErrorKind
import com.yap.agentd.search.SearchException
import com.yap.agentd.search.SearchLimits
import com.yap.agentd.search.SearchRequest
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.DiaryNote
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.MessageSource
import com.yap.agentd.store.Persona
import com.yap.agentd.store.Session
import com.yap.agentd.store.StoredIntent
import com.yap.agentd.store.StoreJson
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue
import com.yap.app.domain.Heartbeat
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
 * `POST /v1/personas/{id}/activity`（客户端上报「他此刻在做什么」）在 [AuthorityRoutes]，
 * 因为它是状态写入而不是工具调用；判定侧只从 `store/` 读那份活动，见 [ProactiveTicker]。
 *
 * 三条路由都只做「HTTP 形状 ↔ 已测试的领域模块」的翻译：
 * - 搜索/抓取直接调 [com.yap.agentd.search.WebSearchService]（provider 请求形状与结果归一化在那里）；
 * - 主动判定复用 `proactive/` 的 [ProactiveEngine] + [ProactiveScheduler]（冷却、每日上限、睡眠门槛都在那里）。
 *
 * 配置来源刻意**只走服务端**（环境变量 / 数据目录），客户端不传 key：
 * `YAP_AGENTD_SEARCH_PROVIDER`（默认 `MESSAGEAPI`）、`..._BASE`、`..._KEY`；
 * `SEARXNG` 允许请求里带 `baseUrl`（自建实例地址不是秘密），其余 provider 的地址必须由服务端配置。
 * 环境变量的读取器由 [AgentdServices.env] 注入（生产是 `System.getenv`，测试用固定表）。
 */
object ToolsRoutes {

    const val SEARCH_PATH = "/v1/tools/web-search"
    const val FETCH_PATH = "/v1/tools/web-fetch"
    const val TICK_PATH = "/v1/proactive/tick"

    /** 服务端配置项的名字（只读环境变量，不接受客户端传入）。 */
    const val ENV_SEARCH_PROVIDER = "YAP_AGENTD_SEARCH_PROVIDER"
    const val ENV_SEARCH_BASE = "YAP_AGENTD_SEARCH_BASE"
    const val ENV_SEARCH_KEY = "YAP_AGENTD_SEARCH_KEY"

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
     * 其它 provider 的地址一律以服务端 `YAP_AGENTD_SEARCH_BASE` 为准 —— 否则客户端就能把
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
        val outcome = ticker.tickWithDecisions(now)
        val fired = outcome.fired
        val decisions = fired.map { decision ->
            StoreJson.obj(
                "personaId" to StoreJson.str(decision.personaId),
                "sessionId" to StoreJson.str(decision.sessionId),
                "messageId" to StoreJson.str(decision.messageId),
                "reason" to StoreJson.str(decision.reason)
            )
        }
        // 「为什么没开口」：睡着、刚聊完、今天说够了、**正在忙（BUSY）**、**在忙且概率降了
        // 没掷中（LIMITED）**……每一样都在这里给一条，客户端/日志不必再猜。
        val skipped = outcome.decisions
            .filter { !it.shouldFire }
            .map { decision ->
                StoreJson.obj(
                    "personaId" to StoreJson.str(decision.targetPersonaId),
                    "reason" to StoreJson.str(decision.reason.code),
                    "scheduledAt" to StoreJson.num(decision.scheduledAt),
                    "probability" to StoreJson.num(decision.probability),
                    "roll" to StoreJson.num(decision.roll),
                    "dailyCap" to StoreJson.num(decision.dailyCap),
                    "sentToday" to StoreJson.num(decision.sentToday)
                )
            }
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "now" to StoreJson.num(now),
                    "fired" to StoreJson.num(fired.size),
                    "decisions" to StoreJson.arr(decisions),
                    "skipped" to StoreJson.arr(skipped)
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

/**
 * `ProactiveTicker` 跑一拍的完整结果。
 *
 * @param fired 真正落库的主动消息（含 messageId；被上限/冷却挡在锁外的不会出现在这里）
 * @param decisions 每个人格一条判定，`shouldFire == false` 的那些就是路由响应里的 `skipped`
 */
internal data class ProactiveTickOutcome(
    val now: Long,
    val fired: List<FiredProactive>,
    val decisions: List<ProactiveDecision>
)

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
    private val roll: () -> Double = { Random.nextDouble() },
    private val pushSender: PushSender = NoopPushSender,
    /**
     * 正文来源：生产是 `llm/LlmProactiveTextSource`（真调模型）；默认 [ProactiveTextSource.DEFAULT]
     * 只给测试/冒烟用。返回空串表示这一拍不开口（不落固定文案）。
     */
    private val texts: ProactiveTextSource = ProactiveTextSource.DEFAULT,
    /**
     * 0.14 体验功能：心理活动 / 会话记忆 / 拉黑到期都挂在这一拍上（**不依赖客户端连接**）。
     * `null` = 没有接 LLM/存储（老测试），心跳照常做主动/日记/念头。
     */
    private val experience: com.yap.agentd.experience.ExperienceService? = null
) {

    private val lock = Any()

    /**
     * 手动 tick 收集「这一拍落了哪些消息」。
     *
     * 定时器（[start]）与 HTTP 调试 tick 可能同时跑：定时器那一拍不该把结果塞进某个请求的响应里，
     * 所以收集器放在 ThreadLocal —— 同一个线程里的 [tickWithDecisions] 才收，定时器线程不收。
     */
    private val collected = ThreadLocal<MutableList<FiredProactive>?>()

    private val engine = ProactiveEngine(clock = clock, roll = roll, texts = texts)
    private val scheduler = ProactiveScheduler(
        engine = engine,
        source = ProactiveSource { now -> targets(now) },
        sink = ProactiveSink { decision -> append(decision) },
        clock = clock
    )

    /** 跑一拍，返回这一拍真正落库的主动消息。同一个 `now` 重复调用返回空列表。 */
    fun tick(now: Long): List<FiredProactive> = tickWithDecisions(now).fired

    /**
     * 跑一拍，额外把**全部判定**（含没开口的原因）交回去。
     *
     * 与 [tick] 是同一次判定：`lastTickAt` 去重与「刚发过」overlay 都在 [ProactiveScheduler] 里，
     * 所以「先 tick 一次拿 fired、再 tick 一次拿原因」这种写法在这里不存在（第二次会是空的）。
     */
    fun tickWithDecisions(now: Long): ProactiveTickOutcome = synchronized(lock) {
        val fired = ArrayList<FiredProactive>()
        collected.set(fired)
        try {
            val tick = scheduler.tick(now)
            // 0.13：日记提醒与 defer_reply 的念头也在这里落库。它们**不参与**主动判定的
            // 冷却/每日上限（用户自己定的提醒不该被「今天说够了」挡掉），但同样不依赖客户端。
            deliverDiaryReminders(now, fired)
            deliverDeferredIntents(now, fired)
            // 0.14：心理活动、会话记忆、拉黑到期——同一拍里跑，客户端不在线也照常推进。
            runCatching { experience?.onTick(now) }
                .onFailure { failure ->
                    System.err.println("[agentd] 体验功能心跳失败：${failure::class.simpleName}: ${failure.message}")
                }
            ProactiveTickOutcome(now = now, fired = fired.toList(), decisions = tick.decisions)
        } finally {
            collected.remove()
        }
    }

    // ------------------------------------------------------------------ 日记提醒 / 延后回复

    /**
     * 到点的日记提醒 → 一条主动消息（**只发一次**）。
     *
     * 三个细节：
     * - 先 `expireOverdueDiaryReminders`：错过超过 24h 的只清标记、不补发
     *   （「周四提醒你打电话」周六才发出来只会让人莫名其妙）；
     * - 落下 remarks 之后写 `remindedAt`，所以下一拍不会重复；
     * - 消息正文带**笔记原文**（这是他自己的备忘），不走 LLM：提醒的价值在内容，不在措辞。
     */
    private fun deliverDiaryReminders(now: Long, fired: MutableList<FiredProactive>) {
        store.expireOverdueDiaryReminders(now)
        for (note in store.dueDiaryReminders(now)) {
            val session = latestSessionFor(note.personaId) ?: continue
            val message = try {
                store.appendMessage(
                    sessionId = session.id,
                    role = MessageRole.ASSISTANT,
                    content = diaryReminderText(note),
                    source = MessageSource.PROACTIVE
                )
            } catch (failure: Exception) {
                System.err.println(
                    "[agentd] 日记提醒落库失败（${note.id}）：${failure::class.simpleName}: ${failure.message}"
                )
                continue
            }
            store.markDiaryReminded(note.id, now)
            fired += FiredProactive(
                personaId = note.personaId,
                sessionId = session.id,
                messageId = message.id,
                reason = REASON_DIARY_REMINDER
            )
            notifyPush(note.personaId, session.id, message.id, message.content, message.createdAt)
        }
    }

    /**
     * 到点的 `defer_reply` 念头 → 一条主动消息（**只发一次**）。
     *
     * 正文由 [texts] 生成（生产是 LLM，测试是固定文案）：念头本身只有「为什么当时没回」，
     * 不适合直接发给对方。生成失败（空串）就落一句最朴素的兜底，不能让「过会儿再说」变成永不回话。
     */
    private fun deliverDeferredIntents(now: Long, fired: MutableList<FiredProactive>) {
        store.expireIntents(now)
        for (intent in store.dueIntents(now)) {
            val persona = store.persona(intent.personaId) ?: continue
            val session = intent.sessionId?.let { store.session(it) } ?: latestSessionFor(intent.personaId) ?: continue
            val text = try {
                texts.compose(personaState(persona, session, now), now).trim().ifBlank { DEFERRED_FALLBACK_TEXT }
            } catch (failure: Exception) {
                System.err.println("[agentd] 延后回复正文生成失败，用兜底文案：${failure::class.simpleName}")
                DEFERRED_FALLBACK_TEXT
            }
            val message = try {
                store.appendMessage(
                    sessionId = session.id,
                    role = MessageRole.ASSISTANT,
                    content = text,
                    source = MessageSource.PROACTIVE
                )
            } catch (failure: Exception) {
                System.err.println(
                    "[agentd] 延后回复落库失败（${intent.id}）：${failure::class.simpleName}: ${failure.message}"
                )
                continue
            }
            store.markIntentSent(intent.id, now)
            fired += FiredProactive(
                personaId = intent.personaId,
                sessionId = session.id,
                messageId = message.id,
                reason = REASON_DEFERRED
            )
            notifyPush(intent.personaId, session.id, message.id, message.content, message.createdAt)
        }
    }

    /** 日记提醒的正文（模板，不走 LLM：提醒的价值在内容）。 */
    private fun diaryReminderText(note: DiaryNote): String {
        val content = note.content.replace('\n', ' ').trim().take(200)
        return "刚翻到自己在备忘里记的一句：$content"
    }

    /** 推送：有 token 才调用适配器；失败只记日志（与主动消息同一套 M6 规则）。 */
    private fun notifyPush(personaId: String, sessionId: String, messageId: String, body: String, createdAt: Long) {
        val tokens = store.pushTokens()
        if (tokens.isEmpty()) return
        val name = store.persona(personaId)?.name.orEmpty()
        runCatching {
            pushSender.send(
                tokens,
                PushMessage(
                    personaId = personaId,
                    sessionId = sessionId,
                    messageId = messageId,
                    title = name.ifBlank { "Yap" },
                    body = body,
                    createdAt = createdAt
                )
            )
        }.onFailure { failure ->
            System.err.println("[agentd] 推送适配器调用失败：${failure::class.simpleName}")
        }
    }

    /**
     * 启动服务端主动心跳（`Main` 在启动时调用，60 秒一拍）。
     *
     * 幂等：重复调用不会起两个线程池。定时器与客户端连接无关——这是「客户端没有后台进程也能主动」
     * 的前提（`docs/design/server-authoritative.md` §3.4）。
     */
    fun start() = scheduler.start()

    /** 停掉心跳（`Main` 的 shutdown hook；已在跑的这一拍会自然结束）。 */
    fun stop() = scheduler.stop()

    /** 心跳是不是在跑（`Main` 启动日志/测试用）。 */
    val isRunning: Boolean get() = scheduler.isRunning

    /** 只有「有人格的会话」才参与判定：没有会话就没有落库的地方，不掷骰子。 */
    private fun targets(now: Long): List<ProactiveTarget> = store.personas().mapNotNull { persona ->
        val session = latestSessionFor(persona.id) ?: return@mapNotNull null
        ProactiveTarget(persona = personaState(persona, session, now), level = LevelPolicy())
    }

    /**
     * 从存储层读到的人格状态。
     *
     * 刻意用保守默认值：`sleepEnabled = false`（服务端拿不到「他现在睡没睡」的客户端状态，不猜）、
     * 唤醒阈值/作息窗口用 [PersonaState] 的默认值、主动程度用 [LevelPolicy] 的默认值。
     * 从存储层读的是**最近互动时间**（会话的 `lastMessageAt`）、**心情**，以及
     * **当日已主动次数 / 上次主动时刻**（`proactive_state.json`，进程重启也不丢）。
     */
    private fun personaState(persona: Persona, session: Session, now: Long): PersonaState {
        val mood = store.mood(persona.id)?.let { stored ->
            MoodState(label = stored.label, intensity = stored.intensity, updatedAt = stored.updatedAt)
        } ?: MoodState()
        // 每日上限按服务端本地自然日算；跨日时 store 会把计数读成 0。
        val proactive = store.proactiveState(persona.id, localDate(now))
        // 占用时间（`set_activity`）：坏 JSON / 没设过都是 null；过期由引擎读取时判断。
        val activity = ActivityState.decode(store.activity(persona.id))
        return PersonaState(
            personaId = persona.id,
            displayName = persona.name,
            sleepEnabled = false,
            lastInteractionAt = session.lastMessageAt ?: 0L,
            lastProactiveAt = proactive.lastProactiveAt,
            proactiveCountToday = proactive.proactiveCountToday,
            mood = mood,
            activity = activity
        )
    }

    /**
     * 掷中之后落一条 `role = ASSISTANT, source = PROACTIVE` 的消息（契约 §3.3/§3.4 的同一套消息表）。
     *
     * 落库与「今天第几次 / 上次什么时候」由 [FileStore.appendProactiveMessageIfAllowed] 在**同一把
     * 存储锁内**完成：并发的两个 tick 只有一个能真正写进去（后到的在锁内复查上限/冷却），
     * 进程重启后也能从 `proactive_state.json` 读回真实计数。
     */
    private fun append(decision: ProactiveDecision) {
        val pending: PendingProactive = decision.pending ?: return
        val session = latestSessionFor(decision.targetPersonaId) ?: return
        val at = pending.createdAt
        val message = store.appendProactiveMessageIfAllowed(
            sessionId = session.id,
            personaId = decision.targetPersonaId,
            content = pending.text,
            at = at,
            today = localDate(at),
            dailyCap = decision.dailyCap,
            minGapMs = (Heartbeat.COOL_DOWN_MINUTES * 60_000.0).toLong()
        ) ?: return
        collected.get()?.add(
            FiredProactive(
                personaId = decision.targetPersonaId,
                sessionId = session.id,
                messageId = message.id,
                reason = decision.reason.code
            )
        )
        // M6：消息已经落库后才调用适配器；失败只记日志，绝不回滚消息或影响 tick 响应。
        val tokens = store.pushTokens()
        if (tokens.isNotEmpty()) {
            val personaName = store.persona(decision.targetPersonaId)?.name.orEmpty()
            runCatching {
                pushSender.send(
                    tokens,
                    PushMessage(
                        personaId = decision.targetPersonaId,
                        sessionId = session.id,
                        messageId = message.id,
                        title = personaName.ifBlank { "Yap" },
                        body = message.content,
                        createdAt = message.createdAt
                    )
                )
            }.onFailure { failure ->
                System.err.println("[agentd] 推送适配器调用失败：${failure::class.simpleName}")
            }
        }
    }

    /** 服务端本地自然日（`yyyy-MM-dd`）；主动计数与每日上限都按它翻页。 */
    private fun localDate(now: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(now), clock.zone).toString()

    /** 这个人格最近活跃的会话（主动消息写到这里）。 */
    private fun latestSessionFor(personaId: String): Session? =
        store.sessions()
            .filter { it.personaId == personaId }
            .maxByOrNull { it.lastMessageAt ?: it.createdAt }

    companion object {
        /** `fired[].reason`：到点的日记提醒。 */
        const val REASON_DIARY_REMINDER = "diary_reminder"

        /** `fired[].reason`：`defer_reply` 的念头到点了。 */
        const val REASON_DEFERRED = "deferred_reply"

        /** 念头到点时正文生成失败用的兜底（不含内部理由，读起来像一句人话）。 */
        const val DEFERRED_FALLBACK_TEXT = "刚才没顾上说话，现在回来找你了。"
    }
}
