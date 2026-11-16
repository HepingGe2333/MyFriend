package com.yap.app.data.remote

import android.util.Log
import com.yap.app.data.prefs.AppSettings
import com.yap.app.domain.ActivityKind
import com.yap.app.domain.PersonaActivity
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 写路径只依赖「此刻能不能用 + 拿一个客户端」这两件事。
 *
 * 抽成接口是为了纯 JVM 单测：`ServerWriteGateway` 的成功/失败/404 语义可以直接用假实现钉住，
 * 不必构造 Android 的加密存储。
 */
interface ServerApiProvider {
    fun api(): ServerApi?
    fun isEnabled(): Boolean
}

/** `POST /v1/backup` 的两种模式（线上值就是小写字符串）。 */
enum class ServerBackupMode(val wire: String) {
    MERGE("merge"),
    REPLACE("replace")
}

/**
 * 服务端模式的**写路径**（M3 最小闭环 + M5 CRUD）。
 *
 * 与读路径的分寸不同：本地模式与本地缓存不能被服务端不可用拖住。所以活动上报/已读回执
 * 这类「尽力而为」的方法返回 [Result] 且失败只记日志；而 M5 的**权威写**（人格/记忆/预设/
 * 设置/备份）失败必须让调用方知道——用户改了东西，不能悄悄只写本机、看起来像保存成功。
 *
 * 日志只写异常类型，不写地址/令牌/聊天正文。
 */
@Singleton
class ServerWriteGateway @Inject constructor(
    private val mode: ServerApiProvider
) {

    /** 现在是不是服务端模式（仓储层据此决定走权威写还是本地写）。 */
    fun isEnabled(): Boolean = mode.isEnabled()

    // ------------------------------------------------------------------ 尽力而为（本地优先）

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
            warn("server activity report failed: ${error.javaClass.simpleName}")
        }

    /** `POST /v1/sessions` 的薄封装（供仓储层之外的小路径复用；失败返回 null）。 */
    suspend fun createSession(personaId: String, title: String? = null): ServerSession? =
        runCatching { mode.api()?.createSession(personaId, title)?.session }
            .onFailure { error ->
                warn("server createSession failed: ${error.javaClass.simpleName}")
            }
            .getOrNull()

    /** `POST /v1/sessions/{id}/read`：标已读回执，失败只记日志。 */
    suspend fun markRead(sessionId: String): Boolean =
        runCatching { mode.api()?.markRead(sessionId) != null }
            .onFailure { error ->
                warn("server markRead failed: ${error.javaClass.simpleName}")
            }
            .getOrDefault(false)

    /** 已知的活动 kind（提供一层字符串→枚举的桥，方便调用方构造请求）。 */
    fun kindOf(activity: PersonaActivity): String = ActivityKind.parse(activity.kind.name).name

    // ------------------------------------------------------------------ M5 权威写

    /**
     * `POST /v1/personas`：服务端成功之后，调用方才可以把结果写进 Room。
     *
     * 返回服务端响应里的 `persona` 对象（动态字段由 [ServerPersonaWrite.localEntity] 映射）。
     * 失败原样作为 [Result.failure] 返回：调用方**不得**回退成静默本地写。
     */
    suspend fun savePersona(payload: JsonObject): Result<JsonObject> = runCatching {
        val response = requireApi().savePersona(payload)
        response.child("persona") ?: throw ServerApiException(
            "server persona response is missing the persona object",
            code = "invalid_response"
        )
    }.onFailure { error ->
        warn("server savePersona failed: ${error.javaClass.simpleName}")
    }

    /**
     * `DELETE /v1/personas/{id}`。
     *
     * 404 当成成功：目标已经不存在时，本机缓存也应该跟着消失（以服务端状态为准），
     * 否则用户会看到一个服务端根本不存在的人格。
     */
    suspend fun deletePersona(personaId: String): Result<Unit> = runCatching {
        guardDelete { requireApi().deletePersona(personaId) }
    }.onFailure { error ->
        warn("server deletePersona failed: ${error.javaClass.simpleName}")
    }

    /** `POST /v1/personas/{id}/memories`：返回 `memory` 对象。 */
    suspend fun saveMemory(personaId: String, payload: JsonObject): Result<JsonObject> = runCatching {
        val response = requireApi().saveMemory(personaId, payload)
        response.child("memory") ?: throw ServerApiException(
            "server memory response is missing the memory object",
            code = "invalid_response"
        )
    }.onFailure { error ->
        warn("server saveMemory failed: ${error.javaClass.simpleName}")
    }

    /** `DELETE /v1/memories/{id}`；404 视为已经删掉。 */
    suspend fun deleteMemory(memoryId: String): Result<Unit> = runCatching {
        guardDelete { requireApi().deleteMemory(memoryId) }
    }.onFailure { error ->
        warn("server deleteMemory failed: ${error.javaClass.simpleName}")
    }

    /** `POST /v1/presets`：返回 `preset` 对象。 */
    suspend fun savePreset(payload: JsonObject): Result<JsonObject> = runCatching {
        val response = requireApi().savePreset(payload)
        response.child("preset") ?: throw ServerApiException(
            "server preset response is missing the preset object",
            code = "invalid_response"
        )
    }.onFailure { error ->
        warn("server savePreset failed: ${error.javaClass.simpleName}")
    }

    /** `DELETE /v1/presets/{id}`；404 视为已经删掉。 */
    suspend fun deletePreset(presetId: String): Result<Unit> = runCatching {
        guardDelete { requireApi().deletePreset(presetId) }
    }.onFailure { error ->
        warn("server deletePreset failed: ${error.javaClass.simpleName}")
    }

    /**
     * `POST /v1/settings`：只推白名单字段（见 [ServerSettingsWrite]）。
     *
     * 设置是「本机偏好 + 服务端镜像」：本地已经落盘，服务端失败不回滚本地，
     * 但仓储层会把失败交给界面提示（用户应该知道这台机器以外没有同步上）。
     */
    suspend fun pushSettings(settings: AppSettings): Result<Unit> = runCatching {
        requireApi().postSettings(ServerSettingsWrite.payload(settings))
        Unit
    }.onFailure { error ->
        warn("server settings push failed: ${error.javaClass.simpleName}")
    }

    /** `GET /v1/backup`：服务端当前的完整备份 JSON（原样，供 UI 写文件）。 */
    suspend fun exportBackup(): Result<String> = runCatching {
        requireApi().exportBackup()
    }.onFailure { error ->
        warn("server backup export failed: ${error.javaClass.simpleName}")
    }

    /**
     * `POST /v1/backup`：把本机备份 JSON 交给服务端导入。
     *
     * @param rawBackup 用户选中的备份文件原文（v5，本地/服务端同构）
     */
    suspend fun importBackup(rawBackup: String, mode: ServerBackupMode): Result<ServerBackupImport> =
        runCatching {
            val data = runCatching { DEFAULT_JSON.parseToJsonElement(rawBackup) as? JsonObject }
                .getOrNull()
                ?: throw ServerApiException("backup file is not a JSON object", code = "invalid_backup")
            requireApi().importBackup(mode.wire, data)
        }.onFailure { error ->
            warn("server backup import failed: ${error.javaClass.simpleName}")
        }

    /** `POST /v1/media`：媒体上传（头像/聊天图共用）。 */
    suspend fun uploadMedia(
        bytes: ByteArray,
        mime: String,
        kind: String,
        personaId: String?
    ): Result<ServerMedia> = runCatching {
        requireApi().uploadMedia(bytes, mime, kind, personaId)
    }.onFailure { error ->
        warn("server media upload failed: ${error.javaClass.simpleName}")
    }

    /**
     * 日志失败也不能影响业务。
     *
     * 纯 JVM 单测里 `android.util.Log` 是「not mocked」会直接抛 RuntimeException；
     * 一条失败日志把一次「本可以正常返回 Result.failure」的写路径变成崩溃，是不可接受的。
     */
    private fun warn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun requireApi(): ServerApi = mode.api() ?: throw ServerApiException(
        "server mode is not available",
        code = "server_unavailable"
    )

    /** 删除类的 404：服务端已经没有它了，本机也不该继续保留。 */
    private inline fun guardDelete(block: () -> Unit) {
        try {
            block()
        } catch (error: ServerApiException) {
            if (error.status == 404 || error.code == "not_found") return
            throw error
        }
    }

    private companion object {
        const val TAG = "ServerWriteGateway"

        /** 只用于把用户文件解析成对象；宽松解析，不引入第二套 Json 配置。 */
        val DEFAULT_JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
