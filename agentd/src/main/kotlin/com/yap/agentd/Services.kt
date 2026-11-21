package com.yap.agentd

import com.yap.agentd.automation.AutoTriggers
import com.yap.agentd.evolution.EvolutionService
import com.yap.agentd.experience.ExperienceService
import com.yap.agentd.experience.MentalActivityService
import com.yap.agentd.experience.SessionMemoryService
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmProactiveTextSource
import com.yap.agentd.llm.LlmReplyGenerator
import com.yap.agentd.mood.MoodService
import com.yap.agentd.proactive.ProactiveTextSource
import com.yap.agentd.search.WebSearchService
import com.yap.agentd.store.FileStore
import kotlin.random.Random

/**
 * `AgentdServer` 的依赖装配。
 *
 * 为什么做成一个小对象而不是 `start()` 的一堆参数：start() 已经有 host/port/token/rateLimiter 四个参数，
 * 再加「数据目录 + 回复生成器 + 搜索服务 + LLM 接入 + 环境变量 + 随机源」会让测试里的每种组合都要写一遍。
 * 测试可以只给一个临时目录和一个假生成器：
 *
 * ```kotlin
 * AgentdServer.start("127.0.0.1", 0, services = AgentdServices(FileStore.open(tempDir)))
 * ```
 *
 * 生产路径由 `Main` 组装（0.13 起）：
 * `--data` → [FileStore]；LLM 配置 → [com.yap.agentd.llm.LlmConfigResolver]；
 * 回复生成器 → [com.yap.agentd.llm.LlmReplyGenerator]；主动消息正文 → [LlmProactiveTextSource]。
 * 默认值仍然可用（回显生成器 + 固定主动文案），但**只给测试/冒烟**：没有 [llm] 时不会假装调用模型。
 */
class AgentdServices(
    /** 服务端权威状态存储；`null` 表示没启用（老测试只测 `/v1/core/…` 与 `/health`）。 */
    val store: FileStore? = null,
    /**
     * 可注入的回复生成器。`null` = 按有无 [llm] 自动选择：
     * 有 LLM → `LlmReplyGenerator`（真调模型）；没有 → `EchoReplyGenerator`（只给测试/冒烟）。
     * 显式传入时以传入的为准（`ChatSseTest` 用假生成器验证事件顺序）。
     */
    val replyGenerator: ReplyGenerator? = null,
    /**
     * 服务端执行的联网搜索/抓取（`/v1/tools/…`）。
     *
     * 无状态；留成构造参数是为了让路由测试能注入「假 HTTP 客户端 + 放行本地 URL 的策略」。
     */
    val searchService: WebSearchService = WebSearchService(),
    /**
     * 环境变量读取器。搜索 provider/base/key 是**服务端配置**（客户端不传 key），
     * 但 `System.getenv` 在 JVM 测试里改不了 —— 所以留一个可注入的读表函数。
     */
    val env: (String) -> String? = { System.getenv(it) },
    /**
     * 主动判定的随机源；生产是 [Random.nextDouble]，测试注入固定值（例如 `{ 0.0 }`）。
     * 只影响「掷没掷中」，不改变睡眠/冷却/每日上限这些门槛。
     */
    val proactiveRoll: () -> Double = { Random.nextDouble() },
    /**
     * 推送适配器（M6）。默认 [NoopPushSender]：没有 APNs/FCM 凭据时只落库，客户端前台再同步。
     * 有 token 时 [ProactiveTicker] 会在主动消息落库后调用它。
     */
    val pushSender: PushSender = NoopPushSender,
    /**
     * LLM 接入（配置解析 + HTTP 客户端）。`null` = 没有接模型：
     * 聊天用注入的 `replyGenerator`（测试/回显）、主动消息用固定文案、进化/情绪端点回 503。
     */
    val llm: LlmAccess? = null,
    /**
     * 是否允许客户端通过 `POST /v1/llm-config` 推送模型配置（仅本机部署为 true）。
     * 远程部署必须由部署者用 `--llm-*`/`YAP_LLM_*` 配置，绝不接受客户端推来的 key。
     */
    val allowClientLlmConfig: Boolean = false,
    /**
     * 0.14.1：客户端「在不在前台」的近似（最近一次 `/v1/ 路径` 请求）。路由与 [delivery] 共用一份。
     */
    val presence: com.yap.agentd.delivery.ClientPresence = com.yap.agentd.delivery.ClientPresence(),
    /**
     * 0.14.1：消息投递队列与 typing 指示器（`spec/experience` §8）。
     *
     * `null` = **不启用**：消息落库即发 `message.created`（0.14 的旧行为）。单测默认走这条，
     * 生产由 `Main` 装配真实时钟/线程；时序测试显式构造一个带假时钟与假 sleeper 的实例。
     */
    val delivery: com.yap.agentd.delivery.MessageDelivery? = null
) {
    /** 实际生效的生成器：显式注入 > 有 LLM 时用真实现 > 回显（测试/冒烟）。 */
    val effectiveReplyGenerator: ReplyGenerator = replyGenerator
        ?: (if (store != null && llm != null) LlmReplyGenerator(store, llm) else EchoReplyGenerator())



    /** 人格进化（`/v1/personas/{id}/evolution/…`）；没有 LLM 或没有存储时为 null（路由回 503）。 */
    val evolutionService: EvolutionService? =
        if (store != null && llm != null) EvolutionService(store, llm) else null

    /** 多维情绪判定（`/v1/personas/{id}/mood/evaluate`）；同上。 */
    val moodService: MoodService? = if (store != null && llm != null) MoodService(store, llm) else null

    /**
     * 每条聊天结束后的自动判定（每 4 条判心情、每 20 条评估进化）。
     *
     * 0.13 的「不依赖客户端连接」就落在这里：阈值与游标都在服务端（`auto_state.json`），
     * 客户端连不连、在不在前台都不影响。没有 LLM 时为 null（不会假装判定过）。
     */
    val autoTriggers: AutoTriggers? =
        if (store != null && llm != null) AutoTriggers(store, moodService, evolutionService) else null

    /** 0.14：心理活动（内心独白 + 心情微调）；没有 LLM 时为 null（不假装想过）。 */
    val mentalActivityService: MentalActivityService? =
        if (store != null && llm != null) MentalActivityService(store, llm) else null

    /** 0.14：会话级短期/长期摘要（每 5 / 30 条用户消息更新）。 */
    val sessionMemoryService: SessionMemoryService? =
        if (store != null && llm != null) SessionMemoryService(store, llm) else null

    /** 0.14：体验功能总入口（聊天后 + 每拍心跳都跑）；没有存储时为 null。 */
    val experienceService: ExperienceService? = store?.let {
        ExperienceService(it, mentalActivityService, sessionMemoryService)
    }

    /**
     * 0.15（心情直觉化）：**每条用户消息之后**判一次心情。
     *
     * 与 [autoTriggers] 的关系：它每判一次都会推进 `auto_state.json` 的心情游标，
     * 所以 0.13 的「每 4 条再判一次」不会再重复打模型（进化评估仍按 20 条走）。
     */
    val perMessageMood: MoodService? = moodService

    /**
     * 0.15.2（危机两段式）：词表命中后再问一次模型「这是不是真的危险」。
     *
     * 没有接 LLM 时为 `null`：聊天退回纯词表判定（0.15.1 的行为）。
     * 有 LLM 但客户端还没推送配置时判定器仍然在——那一刻它会把「无法判定」按安全优先处理。
     *
     * **必须声明在 [chatService] 之前**：Kotlin 的属性按声明顺序初始化，放到后面
     * `chatService` 读到的会是 null（危机判定被静默跳过，最难查的那种 bug）。
     */
    val crisisClassifier: com.yap.agentd.safety.CrisisClassifier? =
        if (llm != null) com.yap.agentd.safety.CrisisClassifier(llm) else null

    /** 聊天服务与 [store] 一起创建：幂等用的在途锁表必须跨请求共享，不能每个请求 new 一个。 */
    val chatService: ChatService? =
        store?.let {
            ChatService(
                it,
                effectiveReplyGenerator,
                autoTriggers = autoTriggers,
                experience = experienceService,
                delivery = delivery,
                moodJudge = perMessageMood,
                crisisClassifier = crisisClassifier
            )
        }

    /**
     * `POST /v1/proactive/tick` 的判定状态；`null` 表示没有状态存储（路由回 503）。
     *
     * 0.13 起正文由 LLM 生成（[LlmProactiveTextSource]）；没有接 LLM 时才退回固定的
     * [ProactiveTextSource.DEFAULT]（只给测试/冒烟，不是产品行为）。
     */
    internal val proactiveTicker: ProactiveTicker? = store?.let { store ->
        ProactiveTicker(
            store = store,
            roll = proactiveRoll,
            pushSender = pushSender,
            texts = llm?.let { access -> LlmProactiveTextSource(store, access) }
                ?: ProactiveTextSource.DEFAULT,
            experience = experienceService,
            delivery = delivery
        )
    }
}
