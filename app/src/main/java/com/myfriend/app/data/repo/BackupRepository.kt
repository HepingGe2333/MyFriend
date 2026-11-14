package com.myfriend.app.data.repo

import androidx.room.withTransaction
import com.myfriend.app.BuildConfig
import com.myfriend.app.data.local.AgentIntentDao
import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MemoryConsolidationDao
import com.myfriend.app.data.local.MemoryDao
import com.myfriend.app.data.local.MemoryTermDao
import com.myfriend.app.data.local.MemoryTermEntity
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaPresetDao
import com.myfriend.app.data.local.ProactiveRuleDao
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.domain.BACKUP_FORMAT_VERSION
import com.myfriend.app.domain.BackupBundle
import com.myfriend.app.domain.BackupCodec
import com.myfriend.app.domain.BackupDecodeResult
import com.myfriend.app.domain.BackupPreview
import com.myfriend.app.domain.BackupSettings
import com.myfriend.app.domain.BackupFailure
import com.myfriend.app.domain.BackupWarning
import com.myfriend.app.domain.DomainErrorCode
import com.myfriend.app.domain.ChineseTerms
import com.myfriend.app.domain.DataEraser
import com.myfriend.app.domain.backupFileName
import com.myfriend.app.domain.mergeInto
import com.myfriend.app.domain.toAppSettings
import com.myfriend.app.domain.toBackupApiConfig
import com.myfriend.app.domain.toBackupConsolidation
import com.myfriend.app.domain.toBackupIntent
import com.myfriend.app.domain.toBackupMemory
import com.myfriend.app.domain.toBackupMessage
import com.myfriend.app.domain.toBackupPersona
import com.myfriend.app.domain.toBackupPreset
import com.myfriend.app.domain.toBackupRule
import com.myfriend.app.domain.toBackupSession
import com.myfriend.app.domain.toBackupSettings
import com.myfriend.app.domain.toEntity
import com.myfriend.app.network.userError
import com.myfriend.app.util.MediaStorage
import com.myfriend.app.domain.toNewEntity
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** 接他回来时的两种方式。 */
enum class ImportMode {
    /** 按 id 去重合并：同一条用备份里的更新，备份里有而本机没有的追加进来。 */
    MERGE,

    /** 先清空这台手机上的他，再照备份原样接回来（API 配置与密钥不动，见 [BackupRepository]）。 */
    REPLACE
}

/**
 * 接回来之后给用户看的一句话：接回了多少、丢了多少、有什么要嘱咐的。
 */
data class ImportSummary(
    val personas: Int = 0,
    val memories: Int = 0,
    val sessions: Int = 0,
    val messages: Int = 0,
    val rules: Int = 0,
    val skipped: Int = 0,
    /** 结构化提醒：界面按当前语言拼句子（`BackupText`），数据层不拼中文。 */
    val warnings: List<BackupWarning> = emptyList(),
    val mode: ImportMode = ImportMode.MERGE,
    val presets: Int = 0
)

/**
 * 备份读不懂 / 写不进去时抛出。
 *
 * 只带结构化 [failure]（外加一句给日志看的中文）；界面用 `BackupText` 映射资源，
 * 不再显示 `throwable.message`。
 */
class BackupImportException(val failure: BackupFailure) : Exception(failure.toString())

/**
 * 「把他带走」与「从备份里接他回来」。
 *
 * ## 这不是数据库同步
 *
 * 导出的一份 JSON 里装的是他：人格（人设卡、作息、心情）、记忆（含分量与漂移后的正文）、
 * 你们的每一句对话、他给自己定的主动节奏、以及能带走的设置。
 * 换手机、重装、甚至只是想留个底，都用同一份文件。
 *
 * ## 三条不变量
 *
 * 1. **密钥永不进备份**。导出只走 [toBackupApiConfig] 的白名单；这里再用
 *    [BackupCodec.containsSecretField] 自检一遍，宁可导出失败也不写进一个明文密钥。
 * 2. **导入是一个事务**。全部写库工作在 `database.withTransaction { }` 里，任何一步失败整体回滚，
 *    绝不留下「一半的他」——半个备份比没有备份更糟，用户根本看不出少了什么。
 * 3. **API 配置从不删**。Key 只在他自己的手机里（Keystore 加密），备份里没有，
 *    所以「替换」也只覆盖配置里的地址与模型，把 Key 留着。真丢了就让用户重新填一次。
 *
 * 为什么没有 BackupWorker：导出与导入都是用户当面点一下、几秒内完成的事，
 * 而且要用户先用系统文件选择器选位置/选文件（SAF uri 只在这次交互里有效，后台任务接不住它）。
 * 挂到 WorkManager 上只会多一份状态要维护，还得处理「任务跑完了文件选择器早关了」。
 * 真正的重活（大 JSON 的读写）在 UI 层切到 Dispatchers.IO 就够了。
 */
@Singleton
class BackupRepository @Inject constructor(
    private val database: MyFriendDatabase,
    private val personaDao: PersonaDao,
    private val presetDao: PersonaPresetDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val memoryTermDao: MemoryTermDao,
    private val memoryConsolidationDao: MemoryConsolidationDao,
    private val proactiveRuleDao: ProactiveRuleDao,
    private val agentIntentDao: AgentIntentDao,
    private val apiConfigDao: ApiConfigDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val dataEraser: DataEraser,
    private val settingsRepository: SettingsRepository
) {

    /** 备份文件建议名字：`myfriend-backup-20261114-0140.json`。 */
    fun suggestedFileName(): String = backupFileName()

    /**
     * 把「他」收集成一份可以带走的 JSON 文本。
     *
     * 大文本由调用方切到 IO 线程去写文件，这里只负责组装。
     */
    suspend fun exportJson(): String {
        val now = System.currentTimeMillis()
        val personas = personaDao.getAll()
        // 一人一段：即使库外数据（旧备份、手工 SQL）塞进来多段，导出也只带最新那段。
        val sessions = sessionDao.getAll()
            .groupBy { it.personaId }
            .mapNotNull { (_, list) ->
                list.maxByOrNull { it.lastMessageAt ?: it.updatedAt }
            }

        // 每条消息都得按会话取：MessageDao 没有「取全部」的查询。
        // （如果以后要省这一轮 N 次查询，可以在 Daos.kt 里加
        //  `@Query("SELECT * FROM messages ORDER BY createdAt ASC") suspend fun getAll(): List<MessageEntity>`。）
        val messages = sessions.flatMap { session -> messageDao.getBySession(session.id) }
        val memories = personas.flatMap { memoryDao.getAllForPersona(it.id) }

        // 整理记录与念头只有「最近 N 条」的查询，够用来保住「他最近整理过记忆」这条历史。
        val consolidations = personas.flatMap { persona ->
            runCatching { memoryConsolidationDao.observeRecent(persona.id, EXPORT_CONSOLIDATIONS_PER_PERSONA).first() }
                .getOrDefault(emptyList())
        }
        val intents = personas.flatMap { persona ->
            runCatching { agentIntentDao.observeRecent(persona.id, EXPORT_INTENTS_PER_PERSONA).first() }
                .getOrDefault(emptyList())
        }

        val bundle = BackupBundle(
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = now,
            personas = personas.map { it.toBackupPersona() },
            memories = memories.map { it.toBackupMemory() },
            sessions = sessions.map { it.toBackupSession() },
            messages = messages.map { it.toBackupMessage() },
            presets = presetDao.getAll().map { it.toBackupPreset() },
            proactiveRules = proactiveRuleDao.observeAll().first().map { it.toBackupRule() },
            agentIntents = intents.map { it.toBackupIntent() },
            memoryConsolidations = consolidations.map { it.toBackupConsolidation() },
            apiConfigs = apiConfigDao.getAll().map { it.toBackupApiConfig() },
            settings = settingsRepository.settings.first().toBackupSettings()
        )

        val encoded = BackupCodec.encode(bundle)
        // 最后一道闸：真出现了密钥字段就中止导出，不写文件。
        if (BackupCodec.containsSecretField(encoded)) userError(DomainErrorCode.BACKUP_SECRET_FIELD)
        return encoded
    }

    /**
     * 读进来的备份先解码看一眼，不写库。
     *
     * 界面上先让用户确认「几个人格、多少条记忆、多少句话」，再决定合并还是替换。
     */
    fun preview(raw: String): BackupDecodeResult = BackupCodec.decode(raw)

    /** 预览用的一行摘要（读不懂时返回 null，原因走 [preview]）。 */
    fun previewOf(raw: String): BackupPreview? =
        (preview(raw) as? BackupDecodeResult.Success)?.let { BackupPreview.from(it.bundle) }

    /** 从 JSON 文本把他接回来。读不懂就抛 [BackupImportException]，库里什么都不改。 */
    suspend fun importJson(raw: String, mode: ImportMode): ImportSummary {
        return when (val decoded = BackupCodec.decode(raw)) {
            is BackupDecodeResult.Failure -> throw BackupImportException(decoded.reason)
            is BackupDecodeResult.Success -> {
                val summary = importBundle(decoded.bundle, mode)
                summary.copy(warnings = decoded.warnings + summary.warnings)
            }
        }
    }

    /**
     * 把已经解码好的备份写进库。
     *
     * 单个 Room 事务：清空（仅替换模式）、人格、记忆、对话、消息、规则、念头、整理记录一起提交，
     * 失败整体回滚。设置走 DataStore，只能在事务成功之后单独写——所以它若失败，
     * 只提示「设置没接回来」，绝不把已经写好的他回滚掉。
     */
    suspend fun importBundle(bundle: BackupBundle, mode: ImportMode): ImportSummary {
        val warnings = mutableListOf<BackupWarning>()
        var skipped = 0

        // 备份里的头像只是一条路径：换手机之后文件已经不在了。清空这类失效路径，
        // 别让界面拿到一个「点了没反应」的地址；同时记一条导入提醒。
        val personas = bundle.personas.map { backup ->
            val missing = MediaStorage.isMissingLocalFile(backup.avatarUri)
            if (missing) warnings += BackupWarning.AvatarMissing(backup.name)
            if (missing) backup.copy(avatarUri = null).toEntity() else backup.toEntity()
        }
        val memories = bundle.memories.map { it.toEntity() }
        val sessions = bundle.sessions.map { it.toEntity() }
        val presets = bundle.presets.map { it.toEntity() }
        val rules = bundle.proactiveRules.map { it.toEntity() }
        val intents = bundle.agentIntents.map { it.toEntity() }
        val consolidations = bundle.memoryConsolidations.map { it.toEntity() }

        // 一人一段：备份里同一人格有多条时只保留最新一条（规格 §八）。
        // v1 备份可能着带好几段，v2 之后的备份不该有；这里统一按「保留最新」处理。
        val latestSessions = bundle.sessions
            .groupBy { it.personaId }
            .mapNotNull { (_, list) -> list.maxByOrNull { (it.lastMessageAt ?: it.updatedAt) } }
        if (latestSessions.size != bundle.sessions.size) {
            warnings += BackupWarning.ExtraSessions(bundle.sessions.size - latestSessions.size)
        }

        // 合并模式下，如果本机已经有这个人的会话，就把备份里的那段落回本机那一段（保留本机 sessionId），
        // 否则唯一索引会让 REPLACE 把本机会话整行删掉，本机消息就成了孤儿。
        val localSessions = if (mode == ImportMode.MERGE) sessionDao.getAll() else emptyList()
        val localByPersona = localSessions.associateBy { it.personaId }
        val sessionIdMap = mutableMapOf<String, String>()
        val sessionsToInsert = mutableListOf<ChatSessionEntity>()
        latestSessions.forEach { backupSession ->
            val entity = backupSession.toEntity()
            val local = localByPersona[backupSession.personaId]
            if (local != null) {
                sessionIdMap[backupSession.id] = local.id
                sessionsToInsert += entity.copy(id = local.id, personaId = local.personaId)
            } else {
                sessionIdMap[backupSession.id] = entity.id
                sessionsToInsert += entity
            }
        }

        // 消息得有个能落脚的会话：按上面的映射改 sessionId，落不进去的跳过并计数。
        val messages = bundle.messages.map { message ->
            message.toEntity().copy(
                sessionId = sessionIdMap[message.sessionId] ?: message.sessionId
            )
        }.filter { message ->
            val ok = message.sessionId in sessionIdMap.values
            if (!ok) skipped++
            ok
        }

        // 备份替换时按引用保留头像：备份里带到的 avatarUri（可能就指向本机仍有效的文件）
        // + 用户自己的头像。其余旧头像文件当孤儿回收（否则「替换」之后旧头像永远留在手机上）。
        val keepAvatarUris = buildSet {
            bundle.personas.forEach { persona ->
                persona.avatarUri?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            runCatching { settingsRepository.settings.first().userAvatarUri }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }

        val messagesWithAttachments = bundle.messages.count { !it.attachmentsJson.isNullOrBlank() }
        if (messagesWithAttachments > 0) {
            warnings += BackupWarning.AttachmentsDropped(messagesWithAttachments)
        }
        database.withTransaction {
            if (mode == ImportMode.REPLACE) {
                // 0.10.2：清空清单统一收在 DataEraser（与「清空聊天与记忆 / 删除全部数据 / 删人格」
                // 共用 ErasurePlan），这里不再手写一份——以前漏的 agent_ticks / memory_vectors /
                // persona_pending_patches / 聊天图片文件都在那份清单里。
                // 注意：API 配置从不删（Key 只在本机），所以 ErasurePlan.BACKUP_REPLACE 不含它。
                // 用 InsideTransaction 变体：本函数已经在 database.withTransaction 里，
                // 不再嵌套开事务（清空 + 写回必须同属一个原子操作）。
                dataEraser.replaceForBackupInsideTransaction(keepAvatarUris)
            }

            personaDao.insertAll(personas)
            presetDao.insertAll(presets)
            memoryDao.insertAll(memories)
            // 记忆的倒排索引要跟着一起建：否则接回来以后按关键词想不起事，只剩 LIKE 兜底。
            memories.forEach { memory ->
                runCatching {
                    memoryTermDao.deleteByMemory(memory.id)
                    val terms = ChineseTerms.terms(memory.content)
                        .map { MemoryTermEntity(memoryId = memory.id, term = it.term, weight = it.weight) }
                    if (terms.isNotEmpty()) memoryTermDao.insertAll(terms)
                }
            }
            sessionsToInsert.forEach { sessionDao.insert(it) }
            messages.forEach { messageDao.insert(it) }
            rules.forEach { proactiveRuleDao.insert(it) }
            intents.forEach { agentIntentDao.insert(it) }
            consolidations.forEach { memoryConsolidationDao.insert(it) }

            // API 配置：只覆盖非敏感字段（名称 / 模型 / 推理参数 / 联网开关）；
            // **地址与明文开关永远保留本机值**，而且每条都要过一遍与 save() 相同的校验。
            // 不合法（例如备份里是 http:// 明文地址、temperature 越界）就跳过这条并记 warning，
            // 绝不让一份外部文件把本机配置改成攻击者的主机或打开明文。
            var addressKept = 0
            var insecureIgnored = 0
            bundle.apiConfigs.forEach { imported ->
                val existing = apiConfigDao.getById(imported.id)
                val merged = if (existing != null) imported.mergeInto(existing) else imported.toNewEntity()
                if (merged.addressKept) addressKept++
                if (merged.insecureIgnored) insecureIgnored++
                val validation = runCatching { apiConfigRepository.validateEntity(merged.entity) }
                if (validation.isFailure) {
                    // 已存在的配置保持本机原值不动；新配置直接丢弃。
                    warnings += BackupWarning.ApiConfigRejected(
                        merged.entity.name.trim().ifBlank { merged.entity.id }
                    )
                    return@forEach
                }
                apiConfigDao.insert(merged.entity)
            }
            if (addressKept > 0) warnings += BackupWarning.ApiConfigAddressKept(addressKept)
            if (insecureIgnored > 0) warnings += BackupWarning.ApiConfigInsecureIgnored(insecureIgnored)
        }

        val configsMissingKey = bundle.apiConfigs.count { config ->
            apiConfigDao.getById(config.id)?.let { it.apiKeyCipher.isNullOrBlank() } ?: true
        }
        if (configsMissingKey > 0) {
            warnings += BackupWarning.ConfigsMissingKey(configsMissingKey)
        }

        val settings = bundle.settings
        if (settings == null) {
            warnings += BackupWarning.NoSettings
        } else {
            runCatching { restoreSettings(settings, warnings) }
                .onFailure { warnings += BackupWarning.SettingsPartiallyRestored }
        }

        return ImportSummary(
            personas = personas.size,
            memories = memories.size,
            sessions = sessionsToInsert.size,
            messages = messages.size,
            rules = rules.size,
            skipped = skipped,
            warnings = warnings.toList(),
            mode = mode,
            presets = presets.size
        )
    }

    /**
     * 把设置逐项写回本机。
     *
     * 只写备份里真的带来的那几项；聊天背景图片（设备路径）与本地引导状态一律不碰。
     */
    private suspend fun restoreSettings(backup: BackupSettings, warnings: MutableList<BackupWarning>) {
        val restored = backup.toAppSettings()
        // 「正在聊的人」必须真的存在：备份里选中的那个人格可能没在这一次接回来的内容里。
        val active = restored.activePersonaId?.takeIf { personaDao.getById(it) != null }
        if (restored.activePersonaId != null && active == null) {
            warnings += BackupWarning.ActivePersonaMissing
        }

        settingsRepository.setThemeMode(restored.themeMode)
        settingsRepository.setAccentColor(restored.accentColor)
        settingsRepository.setFontScale(restored.fontScale)
        settingsRepository.setBubbleStyle(restored.bubbleStyle)
        settingsRepository.setAnimationsEnabled(restored.animationsEnabled)
        settingsRepository.setHapticsEnabled(restored.hapticsEnabled)
        settingsRepository.setChatBackgroundColor(restored.chatBackgroundColor)
        settingsRepository.setAgentLoopEnabled(restored.agentLoopEnabled)
        settingsRepository.setActivePersonaId(active)
    }

    private companion object {
        /** 每个他，最多带多少条「整理过记忆」的留痕——历史够看就行，不必全量。 */
        const val EXPORT_CONSOLIDATIONS_PER_PERSONA = 20

        /** 每个他，最多带多少条还没过期的念头。 */
        const val EXPORT_INTENTS_PER_PERSONA = 30
    }
}
