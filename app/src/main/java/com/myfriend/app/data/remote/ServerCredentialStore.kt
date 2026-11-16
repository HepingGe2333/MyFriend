package com.myfriend.app.data.remote

import android.content.Context
import com.myfriend.app.data.secure.StringCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端连接信息的最小键值存储。
 *
 * 抽成接口是为了纯 JVM 单测：加密存储的读写规则（哪些字段加密、解不开怎么办）
 * 属于数据层，不该只能在模拟器上验证。
 */
interface CredentialBackend {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/** 生产实现：App 私有 SharedPreferences（只有密文，没有明文令牌/地址）。 */
@Singleton
class SharedPreferencesCredentialBackend @Inject constructor(
    @ApplicationContext context: Context
) : CredentialBackend {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String?) {
        // 用 apply 而不是 commit：这是用户点一下开关/保存的路径，不值得为一次磁盘同步阻塞主线程；
        // 真正需要「立刻可见」的是内存里的 [ServerModeRepository]，它在写之前就已经更新过了。
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    private companion object {
        const val FILE_NAME = "myfriend_server_connection"
    }
}

/**
 * 服务端地址与令牌的加密存储（`docs/design/server-authoritative.md` §7）。
 *
 * 三条硬规则：
 *
 * 1. **地址与令牌都加密**：[StringCipher] 复用现有的 Android Keystore AES-GCM 方案
 *    （与 API Key 同一套），不把令牌放进 DataStore 明文；
 * 2. **解不开就当没配**：换机、Keystore 被系统重置、密文被改坏时，[load] 返回空配置并
 *    安静退化成「本地模式」，绝不抛异常让 App 起不来；
 * 3. **不写日志**：[ServerCredentials.toString] 已经脱敏，这里也不打印任何字段值。
 *
 * 开关本身（enabled）不是秘密，明文存；没有地址时开关没有意义（[ServerConfig.rejection]
 * 会回 `EMPTY`），所以不会出现「开关打开但地址丢了」还能连出去的路径。
 */
@Singleton
class ServerCredentialStore @Inject constructor(
    private val cipher: StringCipher,
    private val backend: CredentialBackend
) {

    fun load(): ServerCredentials {
        val enabled = backend.read(KEY_ENABLED) == "1"
        val url = decryptOrNull(backend.read(KEY_URL)).orEmpty()
        val token = decryptOrNull(backend.read(KEY_TOKEN)).orEmpty()
        return ServerCredentials(url = url, token = token, enabled = enabled)
    }

    /**
     * 保存一份配置。
     *
     * 加密失败（极少数设备 Keystore 不可用）会原样抛出 [Throwable]：这不是「静默失败」的场景，
     * 界面必须告诉用户「没存上」，否则用户会以为下次启动还能连上。
     */
    fun save(credentials: ServerCredentials) {
        backend.write(KEY_ENABLED, if (credentials.enabled) "1" else "0")
        backend.write(KEY_URL, encryptOrNull(credentials.cleanUrl))
        backend.write(KEY_TOKEN, encryptOrNull(credentials.cleanToken))
    }

    /** 清空（删除全部数据 / 换服务端）：连开关一起清掉。 */
    fun clear() {
        backend.write(KEY_ENABLED, null)
        backend.write(KEY_URL, null)
        backend.write(KEY_TOKEN, null)
    }

    private fun encryptOrNull(plain: String): String? {
        if (plain.isEmpty()) return null
        val encrypted = cipher.encrypt(plain)
        return encrypted.takeIf { it.isNotEmpty() }
    }

    /**
     * 解密失败 → null（= 没配）。
     *
     * `runCatching` 不是「防御性编程」的摆设：Keystore 解不开时 [StringCipher] 按约定返回 null，
     * 但第三方/未来的实现可能抛 `AEADBadTagException`；这里是唯一读取入口，必须兜住。
     */
    private fun decryptOrNull(encoded: String?): String? =
        if (encoded.isNullOrBlank()) null else runCatching { cipher.decrypt(encoded) }.getOrNull()

    private companion object {
        const val KEY_ENABLED = "server_enabled"
        const val KEY_URL = "server_url_cipher"
        const val KEY_TOKEN = "server_token_cipher"
    }
}
