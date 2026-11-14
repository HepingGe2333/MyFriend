package com.myfriend.app.data.repo

import com.myfriend.app.data.local.PersonaPresetDao
import com.myfriend.app.data.local.PersonaPresetEntity
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.domain.PersonaDraft
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

@Serializable
data class PresetImportResult(
    val imported: Int,
    val skipped: Int,
    val message: String
)

/** 自定义预设库：可复用的提示词模板，能直接生成人格，也能把现有人格存成预设。 */
@Singleton
class PresetRepository @Inject constructor(
    private val presetDao: PersonaPresetDao,
    private val personaRepository: PersonaRepository,
    private val json: Json
) {
    fun observeAll(): Flow<List<PersonaPresetEntity>> = presetDao.observeAll()
    fun observeById(id: String): Flow<PersonaPresetEntity?> = presetDao.observeById(id)

    suspend fun get(id: String): PersonaPresetEntity? = presetDao.getById(id)

    suspend fun count(): Int = presetDao.count()

    suspend fun save(draft: PresetDraft): Result<String> = runCatching {
        val name = draft.name.trim()
        require(name.isNotBlank()) { "请填写预设名称" }
        require(name.length <= 60) { "预设名称过长" }
        require(draft.prompt.isNotBlank()) { "预设需要包含提示词" }
        require(draft.prompt.length <= MAX_PROMPT_CHARS) { "提示词超出安全上限" }
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
        entity.id
    }

    suspend fun duplicate(id: String): Result<String> = runCatching {
        val source = presetDao.getById(id) ?: error("预设不存在")
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} 副本".take(60),
            createdAt = now,
            updatedAt = now
        )
        presetDao.insert(copy)
        copy.id
    }

    suspend fun delete(id: String) {
        presetDao.deleteById(id)
    }

    /** 把现有人格保存为预设（提示词、简介、分类、头像一起带走）。 */
    suspend fun savePersonaAsPreset(personaId: String): Result<String> = runCatching {
        val persona = personaRepository.getById(personaId) ?: error("人格不存在")
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
        val preset = presetDao.getById(presetId) ?: error("预设不存在")
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
        require(items.isNotEmpty()) { "预设库还是空的，先保存一个预设再导出" }
        ExportFile(
            fileName = "myfriend-presets-${System.currentTimeMillis()}.json",
            content = encoder.encodeToString(
                PresetExportBundle.serializer(),
                PresetExportBundle(exportedAt = System.currentTimeMillis(), presets = items)
            )
        )
    }

    suspend fun exportOne(id: String): Result<ExportFile> = runCatching {
        val preset = presetDao.getById(id) ?: error("预设不存在")
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
        require(raw.length <= 6_000_000) { "导入文件过大" }
        val root = runCatching { json.parseToJsonElement(raw) }.getOrElse { error("文件不是有效的 JSON") }
        val bundle = runCatching {
            json.decodeFromJsonElement(PresetExportBundle.serializer(), root)
        }.getOrElse { error("这不是 MyFriend 的预设文件") }
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
        PresetImportResult(
            imported = imported,
            skipped = skipped,
            message = when {
                imported == 0 -> "没有可导入的预设"
                skipped == 0 -> "已导入 $imported 个预设"
                else -> "已导入 $imported 个预设，跳过 $skipped 个无效条目"
            }
        )
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
