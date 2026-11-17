package com.yap.app.data.repo

import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaRevisionEntity
import com.yap.app.data.remote.ServerEvolutionAcceptance
import com.yap.app.data.remote.ServerWriteGateway
import com.yap.app.data.remote.int
import com.yap.app.data.remote.long
import com.yap.app.data.remote.string
import com.yap.app.domain.DomainErrorCode
import com.yap.app.domain.PersonaEvolution
import com.yap.app.domain.PersonaProfile
import com.yap.app.domain.PersonaRevisionReason
import com.yap.app.network.userError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「他最近的变化」的接受 / 拒绝（0.13）。
 *
 * 一条待审变化被接受时，人格是**服务端权威**还是**本机权威**决定写路径：
 *
 * - **本地模式**：写人格四个字段 + `version + 1`，记两条版本历史，删掉待审——0.13 之前的行为逐字保留；
 * - **服务端模式**：先把四个字段合并成完整人格 `POST /v1/personas`（带全部字段，服务端保留其余字段），
 *   服务端接受后才写回本地缓存（同样 `version + 1`）并删待审。**失败不写本地、不删待审**，
 *   由界面提示——用户点头的变化不能只在本机看起来成功。
 *
 * 拒绝在两种模式下都一样：只删本地待审项，人格一动不动（服务端没有待审表）。
 *
 * 所有写操作串行（[lock]）：连点两次「接受」时第二次会看到补丁已被删，返回「补丁不存在」，
 * 而不是把同一个 `version + 1` 叠两次。
 */
@Singleton
class PersonaEvolutionAcceptance @Inject constructor(
    private val store: PersonaEvolutionStore,
    private val serverWrites: ServerWriteGateway
) {

    private val lock = Mutex()

    /**
     * 接受一条变化。
     *
     * @return 变化后的人格；没有实际变化（去重后为空）时返回原人格且不升版本，但仍然清掉这条待审；
     *         任何失败原样返回 [Result.failure]，待审项与本地人格都不动。
     */
    suspend fun accept(patchId: String, now: Long = System.currentTimeMillis()): Result<PersonaEntity> =
        lock.withLock {
            runCatching {
                val patch = store.patch(patchId)
                    ?: userError(DomainErrorCode.PENDING_PATCH_NOT_FOUND)
                val persona = store.persona(patch.personaId)
                    ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
                val changes = PersonaEvolution.decodePatch(patch.patchJson)
                if (!PersonaEvolution.isEvolutionPatch(patch.patchJson) || changes.isEmpty()) {
                    userError(DomainErrorCode.MODEL_UNPARSABLE)
                }
                val updated = PersonaEvolutionRepository.applyTo(persona, changes, now)
                if (updated == null) {
                    // 变化已经被别处写进去了（或与现有值完全重复）：只清掉这条待审，不升版本。
                    store.deletePatch(patchId)
                    return@runCatching persona
                }
                if (serverWrites.isEnabled()) {
                    acceptOnServer(patchId, persona, updated, changes, now)
                } else {
                    acceptLocally(patchId, persona, updated, changes, now)
                    updated
                }
            }
        }

    /**
     * 拒绝一条变化：只丢掉提议，人设一动不动。
     *
     * 服务端模式先 `POST /v1/personas/{id}/evolution/{patchId}/reject`，成功才删本地待审；
     * 失败保留待审并返回 failure（界面提示「没拒绝成」）——用户点了拒绝却还挂在那里，
     * 比直接消失更让人困惑。本地模式与 0.13 之前完全一致：只删本地。
     */
    suspend fun reject(patchId: String): Result<Unit> = runCatching {
        if (serverWrites.isEnabled()) {
            val patch = store.patch(patchId) ?: userError(DomainErrorCode.PENDING_PATCH_NOT_FOUND)
            serverWrites.rejectEvolution(patch.personaId, patchId).getOrThrow()
        }
        store.deletePatch(patchId)
    }

    /**
     * 本地模式：一次事务里记「变化前快照 → 写人格 → 变化后版本 → 删待审」。
     *
     * 顺序与 0.13 之前完全一致：先把变化前的人格记成 [PersonaRevisionReason.BEFORE_EVOLUTION]，
     * 再写新版本，再记一条带摘要的 [PersonaRevisionReason.evolved]。
     */
    private suspend fun acceptLocally(
        patchId: String,
        persona: PersonaEntity,
        updated: PersonaEntity,
        changes: List<PersonaEvolution.Change>,
        now: Long
    ) {
        store.inTransaction {
            store.insertRevision(revisionOf(persona, now, PersonaRevisionReason.BEFORE_EVOLUTION))
            store.updatePersona(updated)
            store.insertRevision(revisionOf(updated, now, PersonaRevisionReason.evolved(PersonaEvolution.summary(changes))))
            store.deletePatch(patchId)
        }
    }

    /**
     * 服务端模式：先让服务端接受（0.13 起改用服务端进化端点），再写本地缓存。
     *
     * 为什么不再走 `POST /v1/personas`：服务端已经有 `.../evolution/{patchId}/accept`，
     * 它才是「这条待审补丁」的唯一真相——自己拼一份全量人格发过去，会把服务端别的设备
     * 刚接受的变化覆盖掉，也可能把 pending 表留在服务端（用户下次又看到同一条）。
     *
     * 服务端失败（404/410/502/网络）时 `getOrThrow()` 直接抛出：本地一行都不写、待审项留着，
     * 界面提示「没写进去」——用户点头的变化不能只在本机看起来成功。
     */
    private suspend fun acceptOnServer(
        patchId: String,
        persona: PersonaEntity,
        updated: PersonaEntity,
        changes: List<PersonaEvolution.Change>,
        now: Long
    ): PersonaEntity {
        val acceptance = serverWrites.acceptEvolution(persona.id, patchId).getOrThrow()
        val applied = ServerEvolutionPersonaCache.apply(persona, updated, acceptance)
        store.inTransaction {
            store.insertRevision(revisionOf(persona, now, PersonaRevisionReason.BEFORE_EVOLUTION))
            store.updatePersona(applied)
            store.insertRevision(revisionOf(applied, now, PersonaRevisionReason.evolved(PersonaEvolution.summary(changes))))
            store.deletePatch(patchId)
        }
        return applied
    }

    /** 版本历史的一行（字段与 `PersonaRepository.insertRevision` 同构）。 */
    private fun revisionOf(persona: PersonaEntity, now: Long, reason: String): PersonaRevisionEntity =
        PersonaRevisionEntity(
            id = UUID.randomUUID().toString(),
            personaId = persona.id,
            version = persona.version,
            name = persona.name,
            description = persona.description,
            avatarUri = persona.avatarUri,
            avatarSeed = persona.avatarSeed,
            prompt = persona.prompt,
            category = persona.category,
            reason = reason,
            createdAt = now
        )
}

/**
 * 服务端接受响应 → 本地缓存人格（0.13，纯函数、可单测）。
 *
 * 服务端的 `accept` 响应里 `persona` 是**合并后的完整人格**：四字段的值可能是
 * 「JSON 数组文本」（客户端 Room 列的形状，`PersonaFields.encodeListLike` 保持原形状）
 * 或 JSON 数组（服务端自己新建的人格）；`version` 是 `version + 1`。
 *
 * 只写这五样（四字段 + version，外加服务端时钟的 updatedAt），其余本地字段一律保留：
 * 服务端不理解心情、翻译缓存、人设卡这些客户端字段，不能拿一份响应把它们清掉。
 */
object ServerEvolutionPersonaCache {

    fun apply(
        local: PersonaEntity,
        locallyComputed: PersonaEntity,
        acceptance: ServerEvolutionAcceptance
    ): PersonaEntity {
        // 服务端说「没有实际变化」（去重后为空）时，本地也保持原样：不要让一次 no-op 接受
        // 悄悄把本地缓存里别的设备刚写进去的值覆盖回去。
        if (!acceptance.changed) return local
        val persona = acceptance.persona ?: return locallyComputed
        val version = acceptance.version.takeIf { it > 0 }
            ?: persona.int("version")?.takeIf { it > 0 }
            ?: locallyComputed.version
        // quirks 要区分「服务端明确写了 null（清空设定）」与「响应里根本没这个字段（回退本地）」：
        // 两者都读成 null，只有 containsKey 能分辨。
        val quirks = if (persona.containsKey("quirks")) {
            quirksText(persona["quirks"])
        } else {
            locallyComputed.quirks
        }
        return local.copy(
            traits = listText(persona["traits"], PersonaProfile.MAX_TRAITS) ?: locallyComputed.traits,
            likes = listText(persona["likes"], PersonaProfile.MAX_LIKES) ?: locallyComputed.likes,
            behaviors = listText(persona["behaviors"], PersonaProfile.MAX_BEHAVIORS) ?: locallyComputed.behaviors,
            quirks = quirks,
            version = version,
            updatedAt = persona.long("updatedAt")?.takeIf { it > 0L } ?: locallyComputed.updatedAt
        )
    }

    /** 列表字段：兼容「JSON 数组文本」与「真的数组」两种形状，统一落成 Room 的数组文本。 */
    private fun listText(element: JsonElement?, max: Int): String? {
        val values = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
            is JsonPrimitive -> {
                if (!element.isString) return null
                PersonaProfile.decodeList(element.content)
            }
            else -> return null
        }
        // 与服务端 `PersonaFields.withCurrent` 的上限一致：超出部分丢掉，已有的不截断。
        return PersonaProfile.encodeList(PersonaProfile.sanitizeList(values, max))
    }

    /** quirks：一段自由文本；空串/null 表示清空（`null` 与本地「没有设定」同语义）。 */
    private fun quirksText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonNull -> null
        is JsonPrimitive -> if (!element.isString) {
            null
        } else {
            element.content.trim().take(PersonaProfile.MAX_QUIRKS).takeIf { it.isNotEmpty() }
        }
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString("\n")
            .take(PersonaProfile.MAX_QUIRKS)
            .takeIf { it.isNotEmpty() }
        else -> null
    }
}
