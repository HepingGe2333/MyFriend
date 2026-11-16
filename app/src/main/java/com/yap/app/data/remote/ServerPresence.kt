package com.yap.app.data.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yap.app.domain.Presence
import com.yap.app.domain.PresenceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端下发的「他此刻在做什么」。
 *
 * 本地模式里 presence 是**推导**出来的（`PresenceRepository` 看最近动静）；服务端模式里
 * 它是服务端职责，客户端只缓存并展示。两者不能同时生效：服务端模式下以这份缓存为准，
 * 切回本地模式立刻回到推导（不迁移数据、不清缓存）。
 */
@Serializable
data class ServerPresenceSnapshot(
    val personaId: String,
    val kind: String = "",
    val detail: String = "",
    val updatedAt: Long = 0L
) {
    /** 服务端 kind（`IDLE` / `ASLEEP` …）→ 界面用的枚举；认不出就退回「在发呆」。 */
    fun toPresence(): Presence {
        val kind = PresenceKind.entries.firstOrNull { it.name == this.kind.trim().uppercase() }
            ?: PresenceKind.IDLE
        return Presence(
            kind = kind,
            text = detail,
            detail = detail,
            sinceAt = updatedAt,
            updatedAt = updatedAt
        )
    }
}

/** presence 的落库/读取口（服务端模式）。 */
interface ServerPresenceStore {
    suspend fun save(snapshot: ServerPresenceSnapshot)
    fun observe(personaId: String): Flow<ServerPresenceSnapshot?>

    /** 一次性读取（后台上下文/AgentLoop 需要同步判定时用）。 */
    suspend fun current(personaId: String): ServerPresenceSnapshot?

    /** 兼容旧数据：一次快照里的 presence 只有一个人格。 */
    suspend fun save(server: ServerPresence) {
        if (server.personaId.isBlank()) return
        save(
            ServerPresenceSnapshot(
                personaId = server.personaId,
                kind = server.kind,
                detail = server.detail,
                updatedAt = server.updatedAt
            )
        )
    }
}

private val Context.serverPresenceDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "yap_server_presence")

/**
 * DataStore 实现：所有人格的 presence 存在一份 JSON 里。
 *
 * 为什么不是 Room 表：presence 是**易失的展示缓存**（服务端随时会覆盖），
 * 为它加一张表/一次迁移不划算；一份 JSON 足够小，也方便「清缓存时一起清掉」。
 */
@Singleton
class DataStoreServerPresenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) : ServerPresenceStore {

    override suspend fun save(snapshot: ServerPresenceSnapshot) {
        if (snapshot.personaId.isBlank()) return
        context.serverPresenceDataStore.edit { prefs ->
            val all = decode(prefs[KEY])
            val previous = all[snapshot.personaId]
            if (previous != null && previous.updatedAt > snapshot.updatedAt) return@edit
            prefs[KEY] = encode(all + (snapshot.personaId to snapshot))
        }
    }

    override fun observe(personaId: String): Flow<ServerPresenceSnapshot?> =
        context.serverPresenceDataStore.data.map { prefs -> decode(prefs[KEY])[personaId] }

    override suspend fun current(personaId: String): ServerPresenceSnapshot? =
        decode(context.serverPresenceDataStore.data.first()[KEY])[personaId]

    private fun decode(raw: String?): Map<String, ServerPresenceSnapshot> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, ServerPresenceSnapshot>>(raw)
        }.getOrDefault(emptyMap())
    }

    private fun encode(values: Map<String, ServerPresenceSnapshot>): String =
        runCatching { json.encodeToString(values) }.getOrDefault("{}")

    private companion object {
        val KEY = stringPreferencesKey("presence_by_persona")
    }
}
