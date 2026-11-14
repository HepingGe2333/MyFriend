package com.myfriend.app.data.repo

import androidx.room.withTransaction
import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MemoryDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.PersonaRevisionDao
import com.myfriend.app.data.local.PersonaRevisionEntity
import com.myfriend.app.data.local.ProactiveRuleDao
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.domain.ImportResult
import com.myfriend.app.data.local.AgentIntentDao
import com.myfriend.app.data.local.AgentTickDao
import com.myfriend.app.data.local.MemoryConsolidationDao
import com.myfriend.app.data.local.MemoryEchoDao
import com.myfriend.app.data.local.MemoryVectorDao
import com.myfriend.app.domain.PersonaProfile
import com.myfriend.app.domain.ProactiveLevel
import com.myfriend.app.domain.SleepPolicy
import com.myfriend.app.domain.PersonaDraft
import com.myfriend.app.network.NetworkGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PersonaExportItem(
    val name: String,
    val description: String = "",
    val prompt: String,
    val category: String = "日常聊天",
    val avatarUri: String? = null,
    val avatarSeed: Int = 0,
    val promptVersion: Int = 1
)

@Serializable
data class PersonaExportBundle(
    val format: String = "myfriend.personas",
    val schemaVersion: Int = 1,
    val exportedAt: Long,
    val personas: List<PersonaExportItem>
)

@Singleton
class PersonaRepository @Inject constructor(
    private val personaDao: PersonaDao,
    private val revisionDao: PersonaRevisionDao,
    private val apiConfigDao: ApiConfigDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val memoryTermDao: com.myfriend.app.data.local.MemoryTermDao,
    private val proactiveRuleDao: ProactiveRuleDao,
    private val agentIntentDao: AgentIntentDao,
    private val agentTickDao: AgentTickDao,
    private val memoryEchoDao: MemoryEchoDao,
    private val memoryConsolidationDao: MemoryConsolidationDao,
    private val memoryVectorDao: MemoryVectorDao,
    private val proactiveScheduler: ProactiveScheduler,
    private val database: MyFriendDatabase,
    private val settingsRepository: SettingsRepository,
    private val json: Json
) {
    fun observeAll(): Flow<List<PersonaEntity>> = personaDao.observeAll()
    fun observeById(id: String): Flow<PersonaEntity?> = personaDao.observeById(id)
    fun observeRevisions(personaId: String): Flow<List<PersonaRevisionEntity>> = revisionDao.observeByPersona(personaId)
    suspend fun getById(id: String): PersonaEntity? = personaDao.getById(id)
    suspend fun getAll(): List<PersonaEntity> = personaDao.getAll()

    suspend fun save(draft: PersonaDraft): Result<String> = runCatching {
        val name = draft.name.trim()
        require(name.isNotBlank()) { "请填写人格名称" }
        require(name.length <= 80) { "人格名称过长" }
        require(draft.description.length <= 600) { "简介过长" }
        require(draft.prompt.length <= MAX_PROMPT_CHARS) { "人格提示词超出安全上限" }
        val category = draft.category.trim().ifBlank { "未分类" }.take(30)
        val now = System.currentTimeMillis()
        val existing = draft.id?.let { personaDao.getById(it) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        // 只有提示词变化才代表人格进入新版本；改名字、换头像、换默认 API 不应产生新版本，
        // 否则版本历史会被空版本污染，原生会话也会被无谓地标记为需要重新同步。
        val promptChanged = existing != null && existing.prompt != draft.prompt
        val version = when {
            existing == null -> 1
            promptChanged -> existing.version + 1
            else -> existing.version
        }
        // 以现有实体为底只覆盖「编辑页能改的字段」：1.9 起心情、人设卡、dndOnSleep、
        // 会话覆盖这些字段不在这张表单里，整条重建会把它们悄悄抹掉。
        val base = existing ?: PersonaEntity(
            id = id,
            name = name,
            description = draft.description.trim(),
            avatarUri = draft.avatarUri,
            avatarSeed = draft.avatarSeed,
            prompt = draft.prompt,
            category = category,
            version = version,
            defaultApiConfigId = draft.defaultApiConfigId,
            modelOverride = draft.modelOverride?.trim()?.ifBlank { null },
            isDefault = personaDao.count() == 0,
            createdAt = now,
            updatedAt = now
        )
        val entity = base.copy(
            name = name,
            description = draft.description.trim(),
            avatarUri = draft.avatarUri,
            avatarSeed = draft.avatarSeed,
            prompt = draft.prompt,
            category = category,
            version = version,
            defaultApiConfigId = draft.defaultApiConfigId,
            modelOverride = draft.modelOverride?.trim()?.ifBlank { null },
            traits = PersonaProfile.encodeList(PersonaProfile.sanitizeList(draft.traits, PersonaProfile.MAX_TRAITS)),
            likes = PersonaProfile.encodeList(PersonaProfile.sanitizeList(draft.likes, PersonaProfile.MAX_LIKES)),
            behaviors = PersonaProfile.encodeList(PersonaProfile.sanitizeList(draft.behaviors, PersonaProfile.MAX_BEHAVIORS)),
            backstory = draft.backstory?.trim()?.take(PersonaProfile.MAX_BACKSTORY)?.ifBlank { null },
            quirks = draft.quirks?.trim()?.take(PersonaProfile.MAX_QUIRKS)?.ifBlank { null },
            proactiveLevel = ProactiveLevel.normalize(draft.proactiveLevel),
            memoryRetention = draft.memoryRetention.coerceIn(1, 5),
            extractionEvery = draft.extractionEvery.coerceIn(3, 12),
            sleepEnabled = draft.sleepEnabled,
            wakeThreshold = SleepPolicy.normalizeThreshold(draft.wakeThreshold),
            quietSleepMinutes = draft.quietSleepMinutes?.let(SleepPolicy::normalizeMinutes),
            quietWakeMinutes = draft.quietWakeMinutes?.let(SleepPolicy::normalizeMinutes),
            updatedAt = now
        )
        database.withTransaction {
            if (existing == null) {
                personaDao.insert(entity)
                insertRevision(entity, reason = "创建人格", now = now)
                // 一人一段：新人格当场就有他的那一段会话，首页点进去就是聊天，不再有「新建对话」。
                ensureSessionFor(entity, now)
            } else {
                if (promptChanged) {
                    insertRevision(existing, reason = "编辑前版本", now = now)
                    insertRevision(entity, reason = "编辑人格", now = now)
                }
                personaDao.update(entity)
            }
            if (entity.isDefault) {
                personaDao.clearDefaults()
                personaDao.markDefault(id, now)
            }
        }
        if (existing == null) {
            val active = settingsRepository.settings.first().activePersonaId
            if (active.isNullOrBlank()) settingsRepository.setActivePersonaId(id)
        }
        id
    }

    suspend fun setDefault(personaId: String): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        database.withTransaction {
            if (personaDao.getById(personaId) == null) error("人格不存在")
            personaDao.clearDefaults()
            personaDao.markDefault(personaId, now)
        }
    }

    suspend fun duplicate(personaId: String): Result<String> = runCatching {
        val source = personaDao.getById(personaId) ?: error("人格不存在")
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} 副本".take(80),
            isDefault = false,
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        database.withTransaction {
            personaDao.insert(copy)
            insertRevision(copy, "复制人格", now)
            // 一人一段：复制出来的人立刻也有自己的那一段。
            ensureSessionFor(copy, now)
        }
        copy.id
    }

    /**
     * 老库、种子人格、导入的人格都可能还没有会话；启动时补一次。
     *
     * 「一人一段」是 1.9 的数据不变量，首页点进去才建会话会让「启动后每人恰好一段」不成立，
     * 也让通知/主动消息在某些路径上找不到落点。这里在启动编排里统一补齐。
     */
    suspend fun ensureSessionsForAll(): Int = runCatching {
        val now = System.currentTimeMillis()
        var created = 0
        database.withTransaction {
            personaDao.getAll().forEach { persona ->
                if (sessionDao.latestForPersona(persona.id) == null) {
                    ensureSessionFor(persona, now + created)
                    created++
                }
            }
        }
        created
    }.getOrDefault(0)

    /**
     * 删掉一个联系人：他的消息、记忆、念头、思考留痕、整理记录、主动节奏一起走。
     *
     * 1.9 起首页长按/滑动删除走的就是这里。所有子表都显式清一遍——
     * 只删 sessions/personas 会留下一堆孤儿行（尤其是念头和思考日志，它们不挂在会话上）。
     */
    suspend fun delete(personaId: String) {
        val wasActive = settingsRepository.settings.first().activePersonaId == personaId
        database.withTransaction {
            val wasDefault = personaDao.getById(personaId)?.isDefault == true
            messageDao.deleteByPersona(personaId)
            sessionDao.deleteByPersona(personaId)
            memoryDao.deleteByPersona(personaId)
            // 记忆没了，倒排索引与向量里指向它们的行也要一起收掉，否则越删越多。
            runCatching { memoryTermDao.deleteOrphans() }
            runCatching { memoryVectorDao.deleteOrphans() }
            runCatching { memoryEchoDao.deleteByPersona(personaId) }
            runCatching { memoryConsolidationDao.deleteByPersona(personaId) }
            revisionDao.deleteByPersona(personaId)
            // 人格没了，它的主动节奏、念头、思考留痕都不该继续留着排期或出现在「他最近在想什么」。
            proactiveRuleDao.deleteByPersona(personaId)
            runCatching { agentIntentDao.deleteByPersona(personaId) }
            runCatching { agentTickDao.deleteByPersona(personaId) }
            personaDao.deleteById(personaId)
            if (wasDefault) {
                personaDao.getAll().firstOrNull()?.let {
                    personaDao.markDefault(it.id, System.currentTimeMillis())
                }
            }
        }
        proactiveScheduler.ensureScheduled()
        if (wasActive) {
            settingsRepository.setActivePersonaId(personaDao.getAll().firstOrNull()?.id)
        }
    }

    /** 一人一段：给还没有会话的人格补一段。旧库、导入的人格都可能缺。 */
    private suspend fun ensureSessionFor(persona: PersonaEntity, now: Long) {
        if (sessionDao.latestForPersona(persona.id) != null) return
        val config = persona.defaultApiConfigId?.let { apiConfigDao.getById(it) }
        sessionDao.insert(
            com.myfriend.app.data.local.ChatSessionEntity(
                id = UUID.randomUUID().toString(),
                personaId = persona.id,
                apiConfigId = config?.id,
                model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: config?.model.orEmpty(),
                title = "与 ${persona.name} 的对话",
                lastMessagePreview = null,
                lastMessageAt = null,
                unreadCount = 0,
                pinned = false,
                createdAt = now,
                updatedAt = now,
                lastPersonaVersion = persona.version,
                lastMemoryExtractedCount = 0
            )
        )
    }

    suspend fun updateAvatar(personaId: String, uri: String?): Result<Unit> = runCatching {
        val persona = personaDao.getById(personaId) ?: error("人格不存在")
        val now = System.currentTimeMillis()
        save(
            PersonaDraft(
                id = persona.id,
                name = persona.name,
                description = persona.description,
                avatarUri = uri,
                avatarSeed = persona.avatarSeed,
                prompt = persona.prompt,
                category = persona.category,
                defaultApiConfigId = persona.defaultApiConfigId,
                modelOverride = persona.modelOverride,
                traits = PersonaProfile.decodeList(persona.traits),
                likes = PersonaProfile.decodeList(persona.likes),
                behaviors = PersonaProfile.decodeList(persona.behaviors),
                backstory = persona.backstory,
                quirks = persona.quirks,
                proactiveLevel = persona.proactiveLevel,
                memoryRetention = persona.memoryRetention,
                extractionEvery = persona.extractionEvery,
                sleepEnabled = persona.sleepEnabled,
                wakeThreshold = persona.wakeThreshold,
                quietSleepMinutes = persona.quietSleepMinutes,
                quietWakeMinutes = persona.quietWakeMinutes
            )
        ).getOrThrow()
    }

    suspend fun restoreRevision(personaId: String, revisionId: String): Result<Unit> = runCatching {
        val persona = personaDao.getById(personaId) ?: error("人格不存在")
        val revision = revisionDao.getById(revisionId) ?: error("历史版本不存在")
        val now = System.currentTimeMillis()
        database.withTransaction {
            insertRevision(persona, "恢复前版本", now)
            val restored = persona.copy(
                name = revision.name,
                description = revision.description,
                avatarUri = revision.avatarUri,
                avatarSeed = revision.avatarSeed,
                prompt = revision.prompt,
                category = revision.category,
                version = persona.version + 1,
                updatedAt = now
            )
            personaDao.update(restored)
            insertRevision(restored, "恢复至 v${revision.version}", now)
        }
    }

    suspend fun acceptEvolution(personaId: String, newPrompt: String, summary: String): Result<Unit> = runCatching {
        val persona = personaDao.getById(personaId) ?: error("人格不存在")
        require(newPrompt.isNotBlank()) { "进化后的人格提示词为空" }
        require(newPrompt.length <= MAX_PROMPT_CHARS) { "人格提示词超出安全上限" }
        val now = System.currentTimeMillis()
        database.withTransaction {
            insertRevision(persona, "进化前版本", now)
            val evolved = persona.copy(
                prompt = newPrompt,
                version = persona.version + 1,
                updatedAt = now
            )
            personaDao.update(evolved)
            insertRevision(evolved, "人格进化：${summary.take(80)}", now)
        }
    }

    suspend fun exportPersonas(ids: List<String>? = null): Result<ExportFile> = runCatching {
        val personas = personaDao.getAll().filter { ids == null || it.id in ids }
        val bundle = PersonaExportBundle(
            exportedAt = System.currentTimeMillis(),
            personas = personas.map {
                PersonaExportItem(
                    name = it.name,
                    description = it.description,
                    prompt = it.prompt,
                    category = it.category,
                    avatarUri = it.avatarUri?.takeIf { uri -> uri.startsWith("content://") },
                    avatarSeed = it.avatarSeed,
                    promptVersion = it.version
                )
            }
        )
        ExportFile(
            fileName = "myfriend-personas-${System.currentTimeMillis()}.json",
            content = json.encodeToString(PersonaExportBundle.serializer(), bundle)
        )
    }

    /**
     * Guarantees that every persona keeps at least one restorable snapshot.
     * Seeded or imported personas never pass through [save], so without this
     * the 人格进化 history could start empty and the original voice would be lost.
     */
    suspend fun ensureBaselineRevisions(): Int = runCatching {
        val now = System.currentTimeMillis()
        var created = 0
        database.withTransaction {
            personaDao.getAll().forEach { persona ->
                if (revisionDao.countByPersona(persona.id) == 0) {
                    insertRevision(persona, "初始版本", now)
                    created++
                }
            }
        }
        created
    }.getOrDefault(0)

    suspend fun importPersonas(raw: String): Result<ImportResult> = runCatching {
        require(raw.length <= 8_000_000) { "导入文件过大" }
        val root = runCatching { json.parseToJsonElement(raw) }.getOrElse { error("文件不是有效的 JSON") }
        val items: List<PersonaExportItem> = when (root) {
            is JsonArray -> root.mapNotNull { decodeItem(it) }
            is JsonObject -> {
                when {
                    root["personas"] is JsonArray -> (root["personas"] as JsonArray).mapNotNull { decodeItem(it) }
                    root.containsKey("prompt") && root.containsKey("name") -> listOfNotNull(decodeItem(root))
                    else -> error("未找到可导入的人格数据")
                }
            }
            else -> error("未找到可导入的人格数据")
        }
        require(items.isNotEmpty()) { "没有可导入的有效人格" }
        require(items.size <= 200) { "一次最多导入 200 个人格" }
        var imported = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        val existingNames = personaDao.getAll().map { it.name }.toMutableSet()
        database.withTransaction {
            var first = personaDao.count() == 0
            for (item in items) {
                val name = item.name.trim()
                val prompt = item.prompt
                if (name.isBlank() || name.length > 80 || prompt.isBlank() || prompt.length > MAX_PROMPT_CHARS) {
                    skipped++
                    continue
                }
                val uniqueName = if (existingNames.contains(name)) "$name（导入）" else name
                existingNames.add(uniqueName)
                val persona = PersonaEntity(
                    id = UUID.randomUUID().toString(),
                    name = uniqueName,
                    description = item.description.take(600),
                    avatarUri = item.avatarUri?.takeIf { it.startsWith("content://") },
                    avatarSeed = item.avatarSeed.takeIf { it in 0..1000 } ?: (0..12).random(),
                    prompt = prompt,
                    category = item.category.trim().ifBlank { "日常聊天" }.take(30),
                    version = 1,
                    defaultApiConfigId = null,
                    modelOverride = null,
                    isDefault = first,
                    createdAt = now + imported,
                    updatedAt = now + imported
                )
                personaDao.insert(persona)
                insertRevision(persona, "导入人格", now + imported)
                ensureSessionFor(persona, now + imported)
                if (first) first = false
                imported++
            }
        }
        if (imported > 0) {
            val active = settingsRepository.settings.first().activePersonaId
            if (active.isNullOrBlank()) settingsRepository.setActivePersonaId(personaDao.getAll().firstOrNull()?.id)
        }
        ImportResult(
            imported = imported,
            skipped = skipped,
            message = if (skipped > 0) "成功导入 $imported 个人格，跳过 $skipped 个格式不完整项" else "成功导入 $imported 个人格"
        )
    }

    private fun decodeItem(element: kotlinx.serialization.json.JsonElement): PersonaExportItem? =
        runCatching { json.decodeFromJsonElement(PersonaExportItem.serializer(), element) }.getOrNull()

    private suspend fun insertRevision(persona: PersonaEntity, reason: String, now: Long) {
        revisionDao.insert(
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
        )
    }

    private companion object {
        const val MAX_PROMPT_CHARS = 200_000
    }
}
