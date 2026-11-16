package com.myfriend.agentd

import com.myfriend.agentd.search.WebSearchService
import com.myfriend.agentd.store.FileStore
import kotlin.random.Random

/**
 * `AgentdServer` 的依赖装配。
 *
 * 为什么做成一个小对象而不是 `start()` 的一堆参数：start() 已经有 host/port/token/rateLimiter 四个参数，
 * 再加「数据目录 + 回复生成器 + 搜索服务 + 环境变量 + 随机源」会让测试里的每种组合都要写一遍。
 * 测试可以只给一个临时目录和一个假生成器：
 *
 * ```kotlin
 * AgentdServer.start("127.0.0.1", 0, services = AgentdServices(FileStore.open(tempDir)))
 * ```
 *
 * 生产路径由 `Main` 组装：`--data` → [FileStore]，生成器先固定用 [EchoReplyGenerator]（还没接 LLM）；
 * 搜索 provider/base/key 只从服务端环境变量读（[env] 的生产实现就是 `System.getenv`）。
 */
class AgentdServices(
    /** 服务端权威状态存储；`null` 表示没启用（老测试只测 `/v1/core/…` 与 `/health`）。 */
    val store: FileStore? = null,
    /** 可注入的回复生成器；测试用假实现，生产下一步换成 LLM 实现。 */
    val replyGenerator: ReplyGenerator = EchoReplyGenerator(),
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
    val pushSender: PushSender = NoopPushSender
) {
    /** 聊天服务与 [store] 一起创建：幂等用的在途锁表必须跨请求共享，不能每个请求 new 一个。 */
    val chatService: ChatService? = store?.let { ChatService(it, replyGenerator) }

    /**
     * `POST /v1/proactive/tick` 的判定状态；`null` 表示没有状态存储（路由回 503）。
     *
     * `internal` 是因为它是接线细节，不是给外部（或未来客户端）用的 API。
     */
    internal val proactiveTicker: ProactiveTicker? =
        store?.let { ProactiveTicker(it, roll = proactiveRoll, pushSender = pushSender) }
}
