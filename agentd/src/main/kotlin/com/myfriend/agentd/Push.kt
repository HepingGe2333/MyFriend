package com.myfriend.agentd

import com.myfriend.agentd.store.PushRegistration

/**
 * 服务端推送适配器（M6 的服务端部分）。
 *
 * 这一阶段**不实现 APNs / FCM 协议本身**：证书、Key、HTTP/2 连接、重试都由部署侧提供。
 * `agentd` 只负责两件事：
 * 1. `POST/DELETE /v1/push/register` 保存/删除设备 token（`push_tokens.json`）；
 * 2. 主动消息真正落库后，如果存在 token，就调用装配进来的 [PushSender]。
 *
 * 默认实现 [NoopPushSender] 什么都不做——没有凭据时契约允许降级为「客户端前台同步/SSE」。
 * 测试可以注入一个记录调用的假实现；生产可以在 `AgentdServices` 里替换成调用 APNs/FCM 的实现，
 * 而不需要改路由或存储。
 */
fun interface PushSender {
    /**
     * @param tokens 当前注册的全部设备 token（可能为空；调用方通常已过滤）
     * @param message 刚落库的主动消息（含 persona/session/message id，便于客户端深链）
     */
    fun send(tokens: List<PushRegistration>, message: PushMessage)
}

/** 一条待推送的主动消息。 */
data class PushMessage(
    val personaId: String,
    val sessionId: String,
    val messageId: String,
    val title: String,
    val body: String,
    val createdAt: Long
)

/** 默认适配器：没有凭据/实现时保持安静，不影响落库与事件流。 */
object NoopPushSender : PushSender {
    override fun send(tokens: List<PushRegistration>, message: PushMessage) {
        // 有意为空：真正的 APNs/FCM 凭据与实现由部署侧提供（见 agentd/README.md）。
    }
}
