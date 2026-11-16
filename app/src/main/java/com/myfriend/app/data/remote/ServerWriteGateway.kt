package com.myfriend.app.data.remote

import android.util.Log
import com.myfriend.app.domain.ActivityKind
import com.myfriend.app.domain.PersonaActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端模式的**写路径**（M3 最小闭环）：把本地已经发生的动作同步给服务端。
 *
 * 与读路径的分寸不同：本地模式与本地缓存不能被服务端不可用拖住。所以这里的每个方法都
 * 返回 [Result]，调用方按「失败只记日志、本地照常」处理——例如 `set_activity` 先写本地
 * `personas.activityJson`，再尽力上报；上报失败不影响模型下一轮读到的活动。
 *
 * 日志只写异常类型，不写地址/令牌/聊天正文。
 */
@Singleton
class ServerWriteGateway @Inject constructor(
    private val mode: ServerModeRepository
) {

    /** `set_activity` → `POST /v1/personas/{id}/activity`。 */
    suspend fun reportActivity(personaId: String, activity: PersonaActivity): Result<Unit> =
        runCatching {
            val api = mode.api() ?: return@runCatching
            api.reportActivity(
                personaId = personaId,
                activity = ServerActivityWrite(
                    kind = activity.kind.name,
                    activity = activity.activity,
                    // until 已经在本地按 minutes 归一化过；服务端也接受 until，
                    // 用它比再传 minutes 更准（避免两次 now 之间的漂移）。
                    until = activity.until.takeIf { it > 0 },
                    availability = activity.availability.name
                )
            )
            Unit
        }.onFailure { error ->
            Log.w(TAG, "server activity report failed: ${error.javaClass.simpleName}")
        }

    /** `POST /v1/sessions` 的薄封装（供仓储层之外的小路径复用；失败返回 null）。 */
    suspend fun createSession(personaId: String, title: String? = null): ServerSession? =
        runCatching { mode.api()?.createSession(personaId, title)?.session }
            .onFailure { error ->
                Log.w(TAG, "server createSession failed: ${error.javaClass.simpleName}")
            }
            .getOrNull()

    /** `POST /v1/sessions/{id}/read`：标已读回执，失败只记日志。 */
    suspend fun markRead(sessionId: String): Boolean =
        runCatching { mode.api()?.markRead(sessionId) != null }
            .onFailure { error ->
                Log.w(TAG, "server markRead failed: ${error.javaClass.simpleName}")
            }
            .getOrDefault(false)

    /** 已知的活动 kind（提供一层字符串→枚举的桥，方便调用方构造请求）。 */
    fun kindOf(activity: PersonaActivity): String = ActivityKind.parse(activity.kind.name).name

    private companion object {
        const val TAG = "ServerWriteGateway"
    }
}
