package com.yap.app.data.repo

import androidx.room.withTransaction
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPendingPatchDao
import com.yap.app.data.local.PersonaPendingPatchEntity
import com.yap.app.data.local.PersonaRevisionDao
import com.yap.app.data.local.PersonaRevisionEntity
import com.yap.app.data.local.YapDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「接受 / 拒绝一条待审进化变化」需要的本地读写端口。
 *
 * 抽成接口而不是直接拿 Room 的 DAO：本项目的纯 JVM 单测没有 Room/Robolectric，
 * 而「接受后本地写成什么、服务端失败时待审项留不留」正是 0.13 最该钉住的行为。
 * 生产实现是 [RoomPersonaEvolutionStore]，测试用内存假实现（见 `ServerPersonaEvolutionTest`）。
 *
 * 端口只覆盖「一条补丁 + 一个人格」的读写，不做通用仓储：面越窄，假实现越不容易和真实行为分叉。
 */
interface PersonaEvolutionStore {

    suspend fun patch(id: String): PersonaPendingPatchEntity?

    suspend fun persona(id: String): PersonaEntity?

    suspend fun deletePatch(id: String)

    suspend fun updatePersona(persona: PersonaEntity)

    suspend fun insertRevision(revision: PersonaRevisionEntity)

    /** 把「写人格 + 记版本历史 + 删待审」放在一次事务里（与本地模式逐字一致）。 */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

@Singleton
class RoomPersonaEvolutionStore @Inject constructor(
    private val personaDao: PersonaDao,
    private val pendingPatchDao: PersonaPendingPatchDao,
    private val revisionDao: PersonaRevisionDao,
    private val database: YapDatabase
) : PersonaEvolutionStore {

    override suspend fun patch(id: String): PersonaPendingPatchEntity? = pendingPatchDao.getById(id)

    override suspend fun persona(id: String): PersonaEntity? = personaDao.getById(id)

    override suspend fun deletePatch(id: String) {
        pendingPatchDao.deleteById(id)
    }

    override suspend fun updatePersona(persona: PersonaEntity) {
        personaDao.update(persona)
    }

    override suspend fun insertRevision(revision: PersonaRevisionEntity) {
        revisionDao.insert(revision)
    }

    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }
}
