package com.myfriend.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.myfriend.app.R
import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.PersonaRevisionDao
import com.myfriend.app.data.local.PersonaRevisionEntity
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.domain.ImportResult
import com.myfriend.app.domain.DataEraser
import com.myfriend.app.domain.DomainErrorCode
import com.myfriend.app.domain.PersonaProfile
import com.myfriend.app.domain.PersonaRevisionReason
import com.myfriend.app.domain.ProactiveLevel
import com.myfriend.app.domain.SleepPolicy
import com.myfriend.app.domain.PersonaDraft
import com.myfriend.app.network.NetworkGuard
import com.myfriend.app.network.userError
import com.myfriend.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    private val dataEraser: DataEraser,
    /** 换头像/回滚版本时删旧文件的引用守卫（0.12）：同一张图可能还被预设或别人引用。 */
    private val mediaJanitor: MediaJanitor,
    private val proactiveScheduler: ProactiveScheduler,
    private val database: MyFriendDatabase,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val appContext: Context,
    private val chatNotifier: com.myfriend.app.util.ChatNotifier,
    private val json: Json
) {
    fun observeAll(): Flow<List<PersonaEntity>> = personaDao.observeAll()
    fun observeById(id: String): Flow<PersonaEntity?> = personaDao.observeById(id)
    fun observeRevisions(personaId: String): Flow<List<PersonaRevisionEntity>> = revisionDao.observeByPersona(personaId)
    suspend fun getById(id: String): PersonaEntity? = personaDao.getById(id)
    suspend fun getAll(): List<PersonaEntity> = personaDao.getAll()

    suspend fun save(draft: PersonaDraft): Result<String> = runCatching {
        val name = draft.name.trim()
        if (name.isBlank()) userError(DomainErrorCode.PERSONA_NAME_REQUIRED)
        if (name.length > 80) userError(DomainErrorCode.PERSONA_NAME_TOO_LONG)
        if (draft.description.length > 600) userError(DomainErrorCode.PERSONA_DESCRIPTION_TOO_LONG)
        if (draft.prompt.length > MAX_PROMPT_CHARS) userError(DomainErrorCode.PERSONA_PROMPT_TOO_LONG)
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
            worldJson = draft.worldJson?.trim()?.takeIf { it.isNotBlank() },
            speechStyleJson = draft.speechStyleJson?.trim()?.takeIf { it.isNotBlank() },
            updatedAt = now
        )
        database.withTransaction {
            if (existing == null) {
                personaDao.insert(entity)
                insertRevision(entity, reason = PersonaRevisionReason.CREATED, now = now)
                // 一人一段：新人格当场就有他的那一段会话，首页点进去就是聊天，不再有「新建对话」。
                ensureSessionFor(entity, now)
            } else {
                if (promptChanged) {
                    insertRevision(existing, reason = PersonaRevisionReason.BEFORE_EDIT, now = now)
                    insertRevision(entity, reason = PersonaRevisionReason.EDITED, now = now)
                }
                personaDao.update(entity)
            }
            if (entity.isDefault) {
                personaDao.clearDefaults()
                personaDao.markDefault(id, now)
            }
        }
        // 头像换了一张（或恢复成默认头像）：旧文件不再被任何一行引用，得删掉，否则越攒越多。
        // 0.12：**先查引用**再删——「保存人格为预设」会共用同一个头像文件，闭眼删会把预设的脸删掉。
        if (existing?.avatarUri != entity.avatarUri) {
            runCatching { mediaJanitor.deleteIfUnreferenced(existing?.avatarUri) }
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
            if (personaDao.getById(personaId) == null) userError(DomainErrorCode.PERSONA_NOT_FOUND)
            personaDao.clearDefaults()
            personaDao.markDefault(personaId, now)
        }
    }

    suspend fun duplicate(personaId: String, copySuffix: String): Result<String> = runCatching {
        val source = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        val now = System.currentTimeMillis()
        // 审计 P2-11（0.11.1）：头像必须复制成独立文件。原样共享 `avatarUri` 的话，
        // 删副本或给副本换头像都会把原人格的文件删掉（`deleteAvatar` 只按路径删，
        // 不查还有没有别的行引用）。
        val copyAvatarUri = MediaStorage.copyAvatar(appContext, source.avatarUri)
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = (source.name + copySuffix).take(80),
            avatarUri = copyAvatarUri,
            isDefault = false,
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        database.withTransaction {
            personaDao.insert(copy)
            insertRevision(copy, PersonaRevisionReason.COPIED, now)
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
        // 事务内只删表、只收集待删文件；文件必须等提交成功之后再删（审计 P1-4：
        // 事务回滚时消息/人格会回来，删掉的文件却回不来）。
        val erasure = database.withTransaction {
            val wasDefault = personaDao.getById(personaId)?.isDefault == true
            // 清单统一在 ErasurePlan（DataEraser）：消息 / 会话 / 记忆 / 回声 / 整理摘要 /
            // 记忆旧正文（memory_versions）/ 向量 / 念头 / 思考留痕 / 待确认补丁 / 版本历史 /
            // 主动节奏 / 人格本体，外加他的聊天图片与头像文件（后者在提交后删）。
            val report = dataEraser.deletePersonaInsideTransaction(personaId)
            if (wasDefault) {
                personaDao.getAll().firstOrNull()?.let {
                    personaDao.markDefault(it.id, System.currentTimeMillis())
                }
            }
            report
        }
        // 事务已经提交：现在删文件才是安全的。删不掉只会留下孤儿文件（不影响这次删除），
        // 不让「人格已经删了」的操作再报一次失败。
        // 切到 IO：`ErasureFiles` 是非挂起接口，里面的引用查询会阻塞调用线程
        // （见 `AndroidErasureFiles`），不能让它阻塞主线程。
        runCatching { withContext(Dispatchers.IO) { dataEraser.commitFiles(erasure) } }
        // 人已经删了：他发过的通知（会话通知 + 聚合摘要）也得撤掉，否则点开是一个空会话
        //（审计 P2-14：以前 cancel 只支持单会话，删人格之后残留通知没人收）。
        runCatching { chatNotifier.cancelByPersona(appContext, personaId) }
        proactiveScheduler.ensureScheduled()
        if (wasActive) {
            settingsRepository.setActivePersonaId(personaDao.getAll().firstOrNull()?.id)
        }
    }

    /** 一人一段：给还没有会话的人格补一段。旧库、导入的人格都可能缺。 */
    private suspend fun ensureSessionFor(persona: PersonaEntity, now: Long) {
        if (sessionDao.latestForPersona(persona.id) != null) return
        val config = persona.defaultApiConfigId?.let { apiConfigDao.getById(it) }
        // 启动补会话与「首页点联系人」是并发入口：唯一索引下只可能有一方插得进去（审计 P2-6）。
        // 用 IGNORE 而不是 ABORT：撞了就说明别人刚补上了，这里不该让启动编排失败。
        sessionDao.insertIgnore(
            com.myfriend.app.data.local.ChatSessionEntity(
                id = UUID.randomUUID().toString(),
                personaId = persona.id,
                apiConfigId = config?.id,
                model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: config?.model.orEmpty(),
                title = appContext.getString(R.string.chat_session_default_title, persona.name),
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

    /**
     * 写入 / 清空翻译缓存（`spec/persona-translation/README.md` §4，schema v14）。
     *
     * 与 `save` 分开：`save` 是用户改人格，这条是后台按需翻译。它不该产生新版本、
     * 不该动 `updatedAt`，也不该触发任何通知。
     */
    suspend fun updateTranslationCache(personaId: String, translationJson: String?, lang: String?) {
        val json = translationJson?.takeIf { it.isNotBlank() }
        personaDao.setTranslation(personaId, json, json?.let { lang?.takeIf(String::isNotBlank) })
    }

    /** 「重新翻译」：先清掉旧缓存，再让协调器立刻译一次。 */
    suspend fun clearTranslationCache(personaId: String) = personaDao.setTranslation(personaId, null, null)

    suspend fun updateAvatar(personaId: String, uri: String?): Result<Unit> = runCatching {
        val persona = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
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
                quietWakeMinutes = persona.quietWakeMinutes,
                worldJson = persona.worldJson,
                speechStyleJson = persona.speechStyleJson
            )
        ).getOrThrow()
    }

    suspend fun restoreRevision(personaId: String, revisionId: String): Result<Unit> = runCatching {
        val persona = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        val revision = revisionDao.getById(revisionId) ?: userError(DomainErrorCode.REVISION_NOT_FOUND)
        val now = System.currentTimeMillis()
        // 旧版本里的头像路径可能已经被删掉了（换头像时删旧文件是 0.10.1 的行为）：
        // 恢复一个不存在的文件等于给界面一个点了没反应的地址，这里直接当成「没有头像」。
        val restoredAvatar = revision.avatarUri?.takeUnless { MediaStorage.isMissingLocalFile(it) }
        database.withTransaction {
            insertRevision(persona, PersonaRevisionReason.BEFORE_RESTORE, now)
            val restored = persona.copy(
                name = revision.name,
                description = revision.description,
                avatarUri = restoredAvatar,
                avatarSeed = revision.avatarSeed,
                prompt = revision.prompt,
                category = revision.category,
                version = persona.version + 1,
                updatedAt = now
            )
            personaDao.update(restored)
            insertRevision(restored, PersonaRevisionReason.restored(revision.version), now)
        }
        // 换了一张（或恢复成默认）：当前头像文件不再被引用，删掉。
        // 0.12：回滚版本换掉的头像同样先查引用（可能被预设/别的人格共享）。
        if (persona.avatarUri != restoredAvatar) {
            runCatching { mediaJanitor.deleteIfUnreferenced(persona.avatarUri) }
        }
    }

    suspend fun acceptEvolution(personaId: String, newPrompt: String, summary: String): Result<Unit> = runCatching {
        val persona = personaDao.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        if (newPrompt.isBlank()) userError(DomainErrorCode.EVOLVED_PROMPT_EMPTY)
        if (newPrompt.length > MAX_PROMPT_CHARS) userError(DomainErrorCode.PERSONA_PROMPT_TOO_LONG)
        val now = System.currentTimeMillis()
        database.withTransaction {
            insertRevision(persona, PersonaRevisionReason.BEFORE_EVOLUTION, now)
            val evolved = persona.copy(
                prompt = newPrompt,
                version = persona.version + 1,
                updatedAt = now
            )
            personaDao.update(evolved)
            insertRevision(evolved, PersonaRevisionReason.evolved(summary), now)
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
                    insertRevision(persona, PersonaRevisionReason.BASELINE, now)
                    created++
                }
            }
        }
        created
    }.getOrDefault(0)

    suspend fun importPersonas(raw: String, importedSuffix: String = ""): Result<ImportResult> = runCatching {
        if (raw.length > 8_000_000) userError(DomainErrorCode.IMPORT_FILE_TOO_LARGE)
        val root = runCatching { json.parseToJsonElement(raw) }.getOrElse { userError(DomainErrorCode.IMPORT_INVALID_JSON) }
        val items: List<PersonaExportItem> = when (root) {
            is JsonArray -> root.mapNotNull { decodeItem(it) }
            is JsonObject -> {
                when {
                    root["personas"] is JsonArray -> (root["personas"] as JsonArray).mapNotNull { decodeItem(it) }
                    root.containsKey("prompt") && root.containsKey("name") -> listOfNotNull(decodeItem(root))
                    else -> userError(DomainErrorCode.IMPORT_NO_PERSONA_DATA)
                }
            }
            else -> userError(DomainErrorCode.IMPORT_NO_PERSONA_DATA)
        }
        if (items.isEmpty()) userError(DomainErrorCode.IMPORT_NO_VALID_PERSONA)
        if (items.size > 200) userError(DomainErrorCode.IMPORT_TOO_MANY_PERSONAS)
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
                val uniqueName = if (existingNames.contains(name)) (name + importedSuffix).take(80) else name
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
                insertRevision(persona, PersonaRevisionReason.IMPORTED, now + imported)
                ensureSessionFor(persona, now + imported)
                if (first) first = false
                imported++
            }
        }
        if (imported > 0) {
            val active = settingsRepository.settings.first().activePersonaId
            if (active.isNullOrBlank()) settingsRepository.setActivePersonaId(personaDao.getAll().firstOrNull()?.id)
        }
        ImportResult(imported = imported, skipped = skipped)
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
