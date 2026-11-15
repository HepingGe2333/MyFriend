package com.myfriend.app.data.repo

import com.myfriend.app.data.local.PersonaPresetDao
import com.myfriend.app.data.local.PersonaPresetEntity
import com.myfriend.app.domain.DomainErrorCode
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.domain.PersonaDraft
import com.myfriend.app.network.userError
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PresetDraft(
    val id: String? = null,
    val name: String,
    val description: String,
    val prompt: String,
    val category: String,
    val avatarUri: String?,
    val avatarSeed: Int
)

@Serializable
private data class PresetExportItem(
    val name: String,
    val description: String = "",
    val prompt: String,
    val category: String = "未分类",
    val avatarSeed: Int = 0
)

@Serializable
private data class PresetExportBundle(
    val format: String = "myfriend.presets",
    val schemaVersion: Int = 1,
    val exportedAt: Long,
    val presets: List<PresetExportItem>
)

/**
 * 预设导入的结果。
 *
 * 不再带现成的中文句子：界面用 [imported] / [skipped] 自己拼资源文案
 * （`preset_import_result_*`），这样英文界面不会看到中文。
 */
data class PresetImportResult(
    val imported: Int,
    val skipped: Int
)

/** 自定义预设库：可复用的提示词模板，能直接生成人格，也能把现有人格存成预设。 */
@Singleton
class PresetRepository @Inject constructor(
    private val presetDao: PersonaPresetDao,
    private val personaRepository: PersonaRepository,
    /** 删预设/换预设头像时删旧文件的引用守卫（0.12）：预设与人格经常共用同一张头像。 */
    private val mediaJanitor: MediaJanitor,
    private val json: Json
) {
    fun observeAll(): Flow<List<PersonaPresetEntity>> = presetDao.observeAll()
    fun observeById(id: String): Flow<PersonaPresetEntity?> = presetDao.observeById(id)

    suspend fun get(id: String): PersonaPresetEntity? = presetDao.getById(id)

    suspend fun count(): Int = presetDao.count()

    suspend fun save(draft: PresetDraft): Result<String> = runCatching {
        val name = draft.name.trim()
        if (name.isBlank()) userError(DomainErrorCode.PRESET_NAME_REQUIRED)
        if (name.length > 60) userError(DomainErrorCode.PRESET_NAME_TOO_LONG)
        if (draft.prompt.isBlank()) userError(DomainErrorCode.PRESET_PROMPT_REQUIRED)
        if (draft.prompt.length > MAX_PROMPT_CHARS) userError(DomainErrorCode.PRESET_PROMPT_TOO_LONG)
        val now = System.currentTimeMillis()
        val existing = draft.id?.let { presetDao.getById(it) }
        val entity = PersonaPresetEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            description = draft.description.trim().take(300),
            prompt = draft.prompt,
            category = draft.category.trim().ifBlank { "未分类" }.take(30),
            avatarUri = draft.avatarUri,
            avatarSeed = draft.avatarSeed,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        if (existing == null) presetDao.insert(entity) else presetDao.update(entity)
        // 换了头像：旧的按引用回收（预设之间/与人格之间可能共用同一个文件）。
        if (existing != null && existing.avatarUri != entity.avatarUri) {
            runCatching { mediaJanitor.deleteIfUnreferenced(existing.avatarUri) }
        }
        entity.id
    }

    suspend fun duplicate(id: String, copySuffix: String): Result<String> = runCatching {
        val source = presetDao.getById(id) ?: userError(DomainErrorCode.PRESET_NOT_FOUND)
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = (source.name + copySuffix).take(60),
            createdAt = now,
            updatedAt = now
        )
        presetDao.insert(copy)
        copy.id
    }

    /**
     * 删一个预设。
     *
     * 0.12：预设的头像文件在行删掉**之后**按引用回收——「把人格保存为预设」共用同一个
     * `avatar-*.jpg`，那个文件大概率还被原人格引用，闭眼删就会让原人格的脸消失。
     */
    suspend fun delete(id: String) {
        val existing = presetDao.getById(id)
        presetDao.deleteById(id)
        if (existing?.avatarUri != null) {
            runCatching { mediaJanitor.deleteIfUnreferenced(existing.avatarUri) }
        }
    }

    /** 把现有人格保存为预设（提示词、简介、分类、头像一起带走）。 */
    suspend fun savePersonaAsPreset(personaId: String): Result<String> = runCatching {
        val persona = personaRepository.getById(personaId) ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
        save(
            PresetDraft(
                name = persona.name,
                description = persona.description,
                prompt = persona.prompt,
                category = persona.category,
                avatarUri = persona.avatarUri,
                avatarSeed = persona.avatarSeed
            )
        ).getOrThrow()
    }

    /** 用预设创建一个独立人格（版本从 v1 开始，并保留初始快照）。 */
    suspend fun createPersonaFromPreset(
        presetId: String,
        name: String? = null,
        defaultApiConfigId: String? = null
    ): Result<String> = runCatching {
        val preset = presetDao.getById(presetId) ?: userError(DomainErrorCode.PRESET_NOT_FOUND)
        val existingNames = personaRepository.getAll().map { it.name }.toSet()
        val resolvedName = (name?.trim()?.takeIf { it.isNotBlank() } ?: preset.name).take(60)
            .let { if (it in existingNames) "$it · ${System.currentTimeMillis() % 1000}" else it }
        personaRepository.save(
            PersonaDraft(
                id = null,
                name = resolvedName,
                description = preset.description,
                avatarUri = preset.avatarUri,
                avatarSeed = preset.avatarSeed,
                prompt = preset.prompt,
                category = preset.category,
                defaultApiConfigId = defaultApiConfigId,
                modelOverride = null
            )
        ).getOrThrow()
    }

    private val encoder = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    suspend fun exportAll(): Result<ExportFile> = runCatching {
        val items = presetDao.getAll().map {
            PresetExportItem(
                name = it.name,
                description = it.description,
                prompt = it.prompt,
                category = it.category,
                avatarSeed = it.avatarSeed
            )
        }
        if (items.isEmpty()) userError(DomainErrorCode.PRESET_LIBRARY_EMPTY)
        ExportFile(
            fileName = "myfriend-presets-${System.currentTimeMillis()}.json",
            content = encoder.encodeToString(
                PresetExportBundle.serializer(),
                PresetExportBundle(exportedAt = System.currentTimeMillis(), presets = items)
            )
        )
    }

    suspend fun exportOne(id: String): Result<ExportFile> = runCatching {
        val preset = presetDao.getById(id) ?: userError(DomainErrorCode.PRESET_NOT_FOUND)
        val item = PresetExportItem(
            name = preset.name,
            description = preset.description,
            prompt = preset.prompt,
            category = preset.category,
            avatarSeed = preset.avatarSeed
        )
        ExportFile(
            fileName = "myfriend-preset-${sanitize(preset.name)}-${System.currentTimeMillis()}.json",
            content = encoder.encodeToString(
                PresetExportBundle.serializer(),
                PresetExportBundle(exportedAt = System.currentTimeMillis(), presets = listOf(item))
            )
        )
    }

    suspend fun import(raw: String): Result<PresetImportResult> = runCatching {
        if (raw.length > 6_000_000) userError(DomainErrorCode.IMPORT_FILE_TOO_LARGE)
        val root = runCatching { json.parseToJsonElement(raw) }.getOrElse { userError(DomainErrorCode.IMPORT_INVALID_JSON) }
        val bundle = runCatching {
            json.decodeFromJsonElement(PresetExportBundle.serializer(), root)
        }.getOrElse { userError(DomainErrorCode.IMPORT_NOT_PRESET_FILE) }
        var imported = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        bundle.presets.forEach { item ->
            val prompt = item.prompt.trim()
            if (item.name.isBlank() || prompt.isBlank()) {
                skipped++
            } else {
                presetDao.insert(
                    PersonaPresetEntity(
                        id = UUID.randomUUID().toString(),
                        name = item.name.trim().take(60),
                        description = item.description.trim().take(300),
                        prompt = prompt,
                        category = item.category.trim().ifBlank { "未分类" }.take(30),
                        avatarUri = null,
                        avatarSeed = item.avatarSeed,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                imported++
            }
        }
        PresetImportResult(imported = imported, skipped = skipped)
    }

    suspend fun wipeAll() {
        presetDao.deleteAll()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").take(30).ifBlank { "preset" }

    private companion object {
        const val MAX_PROMPT_CHARS = 200_000
    }
}
