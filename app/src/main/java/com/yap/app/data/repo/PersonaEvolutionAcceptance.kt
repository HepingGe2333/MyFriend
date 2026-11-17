package com.yap.app.data.repo

import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaRevisionEntity
import com.yap.app.data.remote.ServerPersonaWrite
import com.yap.app.data.remote.ServerWriteGateway
import com.yap.app.data.remote.long
import com.yap.app.domain.DomainErrorCode
import com.yap.app.domain.PersonaEvolution
import com.yap.app.domain.PersonaRevisionReason
import com.yap.app.network.userError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 拒绝一条变化：只丢掉提议，人设一动不动。 */
    suspend fun reject(patchId: String) {
        runCatching { store.deletePatch(patchId) }
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
     * 服务端模式：先让服务端接受，再写本地缓存。
     *
     * 请求体是**整条人格**（[ServerPersonaWrite.payload]），不是只发四个字段：服务端的
     * `POST /v1/personas` 按字段合并，发全量既保证「保留其它字段」，也让服务端拿到新的 `version`。
     * 头像不变就不发 `avatarUrl`（否则一次进化会把服务端头像清空）。
     *
     * 服务端失败时 [ServerWriteGateway.savePersona] 返回 failure，这里 `getOrThrow()` 直接抛出：
     * 本地一行都不写、待审项留着，界面提示「没写进去」。
     */
    private suspend fun acceptOnServer(
        patchId: String,
        persona: PersonaEntity,
        updated: PersonaEntity,
        changes: List<PersonaEvolution.Change>,
        now: Long
    ): PersonaEntity {
        val serverPersonaId = serverWrites.serverPersonaId(persona.id)
        val payload = ServerPersonaWrite.payload(
            persona = updated.copy(id = serverPersonaId),
            avatarUrl = null,
            creating = false,
            includeAvatar = false
        )
        val server = serverWrites.savePersona(payload).getOrThrow()
        // 服务端时钟比本机可信（其它设备/服务端都会按它排序）：能读到时用它覆盖 updatedAt。
        val applied = server.long("updatedAt")
            ?.takeIf { it > 0L }
            ?.let { updated.copy(updatedAt = it) }
            ?: updated
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
