package com.yap.app.network

import com.yap.app.data.repo.ApiUsageRepository
import com.yap.app.domain.TokenUsage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络层眼里的「记账」。
 *
 * 1.9 之后这里只剩一件事：收到回复后报一句这次用了多少 token。
 * 额度、单价、超支拦截全部删除——没有任何请求会因为「花得多了」被拒绝。
 */
interface ApiUsageReporter {

    /**
     * 报一笔。
     *
     * [sessionId] / [personaId] 用来支撑设置页的「按人排行」；后台任务没有会话时可以为空。
     */
    suspend fun record(
        kind: String,
        modelName: String?,
        usage: TokenUsage,
        sessionId: String? = null,
        personaId: String? = null
    )
}

/**
 * 记账器：把流水写进本机的 `api_usage`。
 *
 * 由 Hilt 构造成单例，[OpenAiAdapter] 通过构造参数显式拿到它——所以只要走 AppModule 拿到 adapter，
 * 之后每一次 `complete()` / `streamChat()` 都会自动记账，各个调用点一行都不用改。
 * 记账失败只吞「写不进去」，不影响聊天；它自己不做任何清理，那是 App 启动编排的事。
 */
@Singleton
class ApiUsageRecorder @Inject constructor(
    private val usageRepository: ApiUsageRepository
) : ApiUsageReporter {

    override suspend fun record(
        kind: String,
        modelName: String?,
        usage: TokenUsage,
        sessionId: String?,
        personaId: String?
    ) {
        usageRepository.record(
            kind = kind,
            modelName = modelName,
            usage = usage,
            personaId = personaId,
            sessionId = sessionId
        )
    }
}
