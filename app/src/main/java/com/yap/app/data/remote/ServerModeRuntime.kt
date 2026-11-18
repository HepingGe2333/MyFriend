package com.yap.app.data.remote

import com.yap.app.data.repo.ServerCatalogSync
import com.yap.app.util.AppForegroundTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ServerStateSource] 的生产实现：每次调用现取 [ServerModeRepository.api]。
 *
 * 不在单例里缓存 [ServerApi]：设置页一改地址，[ServerModeRepository] 会换一个新客户端；
 * 这里跟着它走，不需要自己再维护一代缓存。
 */
@Singleton
class ServerModeStateSource @Inject constructor(
    private val mode: ServerModeRepository
) : ServerStateSource {

    override suspend fun state(since: Long): StateSnapshot {
        val api = mode.api() ?: throw unavailable()
        return api.state(since)
    }

    override suspend fun latestPage(sessionId: String): ServerMessagePage {
        val api = mode.api() ?: throw unavailable()
        return api.messages(sessionId)
    }

    private fun unavailable() = ServerApiException(
        "server mode is not configured",
        code = "server_unavailable"
    )
}

/**
 * 服务端模式的**生命周期驱动**（M3/M4 的「什么时候同步/什么时候订阅」）。
 *
 * 触发规则（规格：启动或回前台同步一次；前台时长轮询事件；退后台停止）：
 *
 * 1. 服务端可用 + App 在**前台**：同步一次 → 补发离线队列 → 事件长轮询循环；
 * 2. 服务端可用 + App 在**后台**：只做一次同步（启动时/刚切后台时），不拉事件、不发通知；
 * 3. 服务端不可用：什么都不做（本地模式路径完全不受影响）；
 * 4. 事件轮询失败退避 5 秒；成功时 500ms 后再拉（服务端长轮询本身会等 20s，不需要更密）。
 *
 * 「前台」用现有 [AppForegroundTracker]（Activity 生命周期回调），不额外引入
 * ProcessLifecycleOwner：聊天通知那一层已经在用它，两处口径必须一致。
 */
@Singleton
class ServerModeRuntime @Inject constructor(
    private val mode: ServerModeRepository,
    private val syncManager: ServerSyncManager,
    private val poller: ServerEventPoller,
    private val gateway: ServerChatGateway,
    private val foregroundTracker: AppForegroundTracker,
    private val localStore: ServerLocalStore,
    private val bootstrap: ServerBootstrapManager,
    /** 0.13：连接成功后把本机模型配置推给**本机部署**的服务端（远程地址不推，见策略类）。 */
    private val llmConfig: ServerLlmConfigPusher,
    /** 0.13：服务端表情/日记的拉取缓存（只在服务端模式下工作，失败不影响聊天同步）。 */
    private val catalogSync: ServerCatalogSync
) {
    /** 上一次看到的服务端地址：换地址＝换服务端，旧游标/队列必须清掉。 */
    private var lastBaseUrl: String? = null

    /** Application 的进程级 scope 里调用一次即可。 */
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(mode.state, foregroundTracker.foreground) { state, foreground ->
                // 把 config 也放进 key：换地址/换令牌但「仍可用」时，也要重启同步与事件循环，
                // 否则用户改了令牌会一直拿旧客户端请求。
                Triple(state.usable, foreground, state.config)
            }
                .distinctUntilChanged()
                .collectLatest { (usable, foreground, config) ->
                    if (!usable) return@collectLatest
                    val baseUrl = config.baseUrl
                    if (lastBaseUrl != null && lastBaseUrl != baseUrl) {
                        // 换服务端：旧游标/映射/待发队列全部失效，必须先清掉再同步。
                        localStore.resetForNewServer()
                    }
                    lastBaseUrl = baseUrl
                    // 0.13：连接成功 = 「可以把本机模型配置交给它」的时机。独立协程、不等待：
                    // 推送失败只记日志（远程部署会 403，这本来就不是错误路径）。
                    scope.launch { runCatching { llmConfig.pushIfLocal() } }
                    // 0.12.1：这台服务端的第一次连接先把两端数据量对齐（四象限全量搬运），
                    // 已做过就直接返回；失败不阻塞后面的增量同步，下一次前台/启动重试。
                    bootstrap.runIfNeeded(baseUrl)
                    // 启动/回前台/刚切换到服务端模式：先补一次状态与离线队列。
                    syncManager.syncNow()
                    // 0.13：表情/日记是「他随身带的东西」，跟着状态一起刷新（有节流，失败静默）。
                    runCatching { catalogSync.syncAll(force = true) }
                    if (gateway.isAvailable()) {
                        runCatching { gateway.resendPending() }
                    }
                    if (!foreground) return@collectLatest
                    coroutineScope {
                        // 轻量增量：前台每 5 分钟拉一次 state（不是全量），事件长轮询照常负责实时消息。
                        launch {
                            while (currentCoroutineContext().isActive) {
                                delay(INCREMENTAL_INTERVAL_MILLIS)
                                // 上次 bootstrap 失败（服务端当时没起来）也在这里重试：
                                // 标记没写就会真的再试一轮，写过就立刻返回。
                                bootstrap.runIfNeeded(baseUrl)
                                syncManager.syncNow()
                                runCatching { catalogSync.syncAll() }
                            }
                        }
                        while (currentCoroutineContext().isActive) {
                            val outcome = poller.pollOnce()
                            delay(
                                if (outcome.success) {
                                    POLL_INTERVAL_MILLIS
                                } else {
                                    POLL_RETRY_MILLIS
                                }
                            )
                        }
                    }
                }
        }
    }

    private companion object {
        /** 成功一次之后稍等一下再拉：服务端长轮询通常已经阻塞了 20 秒。 */
        const val POLL_INTERVAL_MILLIS = 500L

        /** 失败退避：网络断了不要每 500ms 打一次。 */
        const val POLL_RETRY_MILLIS = 5_000L

        /** 前台轻量增量间隔（`/v1/state?since=` 只回变化；不是全量）。 */
        const val INCREMENTAL_INTERVAL_MILLIS = 5 * 60 * 1_000L
    }
}
