package com.yap.app.data.remote

/**
 * 「我的头像」当前的两种引用（0.12.1）。
 *
 * - [serverRef]：服务端模式下的 `/v1/media/<id>`（也兼容旧数据里的本地文件路径）；
 * - [localRef]：上传成功后保留的本机缓存副本，离线/关掉服务端模式时兜底显示。
 */
data class UserAvatarSnapshot(
    val serverRef: String? = null,
    val localRef: String? = null
)

/**
 * 头像读写端口（生产实现是 [com.yap.app.data.prefs.SettingsRepository] + 服务端设置镜像）。
 *
 * 拆成端口只为一件事：纯 JVM 单测要能断言「压缩 → 上传 → 设置写入 → 推送」的顺序，
 * 以及失败时旧头像是否原样保留——这些规则不该只能在真机上看。
 */
interface UserAvatarSettingsPort {
    suspend fun read(): UserAvatarSnapshot

    /** 只写本地（不推送）；失败要抛异常，调用方据此回滚。 */
    suspend fun write(serverRef: String?, localRef: String?)

    /** 服务端模式下把白名单设置（含 `userAvatarUri`）镜像到服务端；失败抛异常。 */
    suspend fun push()

    /** 失败回滚到操作前的值（只写本地）。 */
    suspend fun restore(snapshot: UserAvatarSnapshot)
}

/** 上传端口：本地文件（`file://`）→ 压缩 → `POST /v1/media?kind=USER_AVATAR` → `/v1/media/<id>`。 */
fun interface UserAvatarUploadPort {
    suspend fun upload(localFileUri: String): String
}

/** 旧头像文件的回收端口（生产实现按引用计数删，可能因共享而不删）。 */
fun interface UserAvatarLocalDeleter {
    suspend fun delete(uri: String?)
}

/**
 * 「我的头像」上传链路（顺序是契约的一部分）：
 *
 * 1. **本地模式**：只写本地文件路径（与旧版本逐字一致，一次网络都不发）；
 * 2. **服务端模式**：
 *    a. 压缩并上传（≤512px / ≤400KB，`USER_AVATAR`）→ `/v1/media/<id>`；
 *    b. 先写本地设置（引用 + 本机缓存副本），再 `POST /v1/settings` 镜像；
 *    c. 推送失败 → **回滚本地设置**，旧头像继续可用（不出现「看起来换了、实际没同步」）；
 *    d. 全部成功之后才回收旧本地文件（仍被其他人格/预设引用时保留）。
 *
 * 失败时抛出的异常由界面提示；本类保证「失败不留半截状态」。
 */
class UserAvatarCoordinator(
    private val settings: UserAvatarSettingsPort,
    private val uploader: UserAvatarUploadPort,
    private val isServerMode: () -> Boolean,
    private val deleter: UserAvatarLocalDeleter = UserAvatarLocalDeleter { }
) {

    /**
     * 用户选好/压缩好一张新头像（[localFileUri] 已经是 App 私有目录里的文件）。
     *
     * @return 实际写入的设置值：服务端模式是 `/v1/media/<id>`，本地模式是本地文件路径
     */
    suspend fun applyPicked(localFileUri: String): String {
        val file = localFileUri.trim()
        require(file.isNotEmpty()) { "avatar file uri is empty" }
        val previous = settings.read()

        if (!isServerMode()) {
            settings.write(serverRef = file, localRef = null)
            deleteOld(previous.localRef ?: previous.serverRef, keep = file)
            return file
        }

        // 先上传：上传失败时设置一个字节都还没改，「保留旧头像」是自然结果。
        val serverRef = uploader.upload(file)
        settings.write(serverRef = serverRef, localRef = file)
        try {
            settings.push()
        } catch (error: Throwable) {
            // 推送失败：本地已写进去的引用必须退回去，否则这台机器显示新头像、服务端还是旧的。
            settings.restore(previous)
            throw error
        }
        deleteOld(previous.localRef ?: previous.serverRef, keep = file)
        return serverRef
    }

    /**
     * 恢复默认头像（没有图）。
     *
     * 服务端模式下先清本地再推送；推送失败回滚（旧头像继续显示），并保留旧文件。
     */
    suspend fun clear(): Unit {
        val previous = settings.read()
        settings.write(serverRef = null, localRef = null)
        if (!isServerMode()) {
            deleteOld(previous.localRef ?: previous.serverRef, keep = null)
            return
        }
        try {
            settings.push()
        } catch (error: Throwable) {
            settings.restore(previous)
            throw error
        }
        deleteOld(previous.localRef ?: previous.serverRef, keep = null)
    }

    private suspend fun deleteOld(previous: String?, keep: String?) {
        val old = previous?.trim().orEmpty()
        if (old.isEmpty() || old == keep) return
        // 只回收本地文件；服务端引用 / 外部 URL 交给服务端或别的清理路径。
        if (!old.startsWith("file://")) return
        runCatching { deleter.delete(old) }
    }
}
