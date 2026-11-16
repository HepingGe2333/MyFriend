package com.yap.app.data.repo

import androidx.room.withTransaction
import com.yap.app.BuildConfig
import com.yap.app.data.local.AgentIntentDao
import com.yap.app.data.local.ApiConfigDao
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MemoryConsolidationDao
import com.yap.app.data.local.MemoryDao
import com.yap.app.data.local.MemoryTermDao
import com.yap.app.data.local.MemoryTermEntity
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaPresetDao
import com.yap.app.data.local.ProactiveRuleDao
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.BACKUP_FORMAT_VERSION
import com.yap.app.domain.BackupBundle
import com.yap.app.domain.BackupCodec
import com.yap.app.domain.BackupDecodeResult
import com.yap.app.domain.BackupPreview
import com.yap.app.domain.BackupSettings
import com.yap.app.domain.BackupFailure
import com.yap.app.domain.BackupAvatarPath
import com.yap.app.domain.BackupIntegrity
import com.yap.app.domain.BackupMediaCandidate
import com.yap.app.domain.BackupMediaFormats
import com.yap.app.domain.BackupMediaKind
import com.yap.app.domain.BackupMediaPacker
import com.yap.app.domain.BackupMediaRestorer
import com.yap.app.domain.BackupMediaWarning
import com.yap.app.domain.BackupAttachmentsPortable
import com.yap.app.domain.BackupWarning
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.DomainErrorCode
import com.yap.app.domain.ChineseTerms
import com.yap.app.domain.DataEraser
import com.yap.app.domain.SessionGenerationRegistry
import com.yap.app.domain.backupFileName
import com.yap.app.domain.mergeInto
import com.yap.app.domain.toAppSettings
import com.yap.app.domain.toBackupApiConfig
import com.yap.app.domain.toBackupConsolidation
import com.yap.app.domain.toBackupIntent
import com.yap.app.domain.toBackupMemory
import com.yap.app.domain.toBackupMessage
import com.yap.app.domain.toBackupPersona
import com.yap.app.domain.toBackupPreset
import com.yap.app.domain.toBackupRule
import com.yap.app.domain.toBackupSession
import com.yap.app.domain.toBackupSettings
import com.yap.app.domain.toEntity
import com.yap.app.network.userError
import com.yap.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import com.yap.app.domain.toNewEntity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    /** v3 媒体（头像/聊天图片）单独一组提醒：张数 + 原因，文案在 UI 层拼。 */
    val mediaWarnings: List<BackupMediaWarning> = emptyList(),
    val mode: ImportMode = ImportMode.MERGE,
    val presets: Int = 0
)

/**
 * 一次导出的结果：JSON 文本 + 没有带走的图片提醒。
 *
 * 为什么不是直接返回 String：v3 的媒体有上限，超限时备份仍然成功，但用户必须知道
 * 「有 N 张图片留在原机上」。把提醒和文本绑在同一个返回值里，调用方就没有「忘了看」的借口。
 */
data class BackupExport(
    val json: String,
    val mediaWarnings: List<BackupMediaWarning> = emptyList(),
    /** 真的装进备份的图片张数（日志/界面摘要用）。 */
    val mediaCount: Int = 0
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
    private val database: YapDatabase,
    private val personaDao: PersonaDao,
    private val presetDao: PersonaPresetDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    /** 块元数据的唯一写入入口：导入之后按消息顺序重建（备份格式仍是 v2，不含块）。 */
    private val blockWriter: MessageBlockWriter,
    private val memoryDao: MemoryDao,
    private val memoryTermDao: MemoryTermDao,
    private val memoryConsolidationDao: MemoryConsolidationDao,
    private val proactiveRuleDao: ProactiveRuleDao,
    private val agentIntentDao: AgentIntentDao,
    private val apiConfigDao: ApiConfigDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val dataEraser: DataEraser,
    /** 替换导入之后回收旧图：先查引用再删（0.12；新图/共享图不会被误删）。 */
    private val mediaJanitor: MediaJanitor,
    private val settingsRepository: SettingsRepository,
    /** 备份「替换」前要 bump 所有会复用 sessionId 的世代（审计 P2-3）。 */
    private val sessionGeneration: SessionGenerationRegistry,
    /** 取消在跑的生成：替换时本机会话会被清掉，收尾写入不能落进刚恢复的会话。 */
    private val chatRepository: ChatRepository,
    /** 头像文件在应用私有目录里，导入时要按名字去查（安全 P2-5）。 */
    @ApplicationContext private val appContext: Context,
    /** M5：服务端模式的导出/导入走 `/v1/backup`；本地模式仍走下面这套 v5。 */
    private val serverWrites: com.yap.app.data.remote.ServerWriteGateway
) {

    /** 备份文件建议名字：`yap-backup-20261114-0140.json`。 */
    fun suggestedFileName(): String = backupFileName()

    /**
     * 把「他」收集成一份可以带走的 JSON 文本。
     *
     * 审计 P1-3 / 0.11.1：读库（每人一次消息查询）、实体→备份结构映射、JSON 编码
     * 全在 `Dispatchers.IO` 上；256MB 的读入上限让「主线程做这些」变成可达的 ANR。
     * 写文件仍由调用方负责（SAF 输出流）。
     *
     * v3 起顺带收集**本机当前引用到的**图片：人格/预设/用户自己的头像、聊天消息里的附件。
     * 只读、只收集真实存在且落在 App 媒体目录里的文件（见 [MediaStorage.ownedFile]）。
     */
    suspend fun export(): BackupExport = withContext(Dispatchers.IO) {
        // M5：服务端模式下「他」住在服务端，导出必须取服务端那份（本机 Room 只是缓存，
        // 可能缺别的设备/服务端主动写入的内容）。客户端只负责把 JSON 原样写进用户选的文件，
        // 备份里带不带媒体、带多少，都由服务端的约定决定。
        if (serverWrites.isEnabled()) {
            val json = serverWrites.exportBackup().getOrThrow()
            // 与本地导出最后一道闸一致：服务端实现出错时，也绝不把含密钥字段的文件写到用户选的位置。
            if (BackupCodec.containsSecretField(json)) userError(DomainErrorCode.BACKUP_SECRET_FIELD)
            return@withContext BackupExport(json = json, mediaWarnings = emptyList(), mediaCount = 0)
        }
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
        val presets = presetDao.getAll()
        val settings = settingsRepository.settings.first()

        // ---- v3：把图片二进制一起装进 media ----------------------------------
        val avatarDir = MediaStorage.avatarDirectory(appContext)
        val mediaDir = MediaStorage.mediaDirectory(appContext)
        val candidates = mutableListOf<BackupMediaCandidate>()
        fun addCandidate(kind: String, personaId: String?, uri: String?, directory: java.io.File) {
            // 只读「属于我们自己的、真实存在的」文件：头像/附件路径来自数据库，属于
            // 「数据即代码」的输入面，不能让它把沙盒里任意文件读进备份。
            val file = MediaStorage.ownedFile(uri, directory) ?: return
            if (!BackupAvatarPath.isSafeName(file.name)) return
            val mime = BackupMediaFormats.mimeOfFile(file.name) ?: return
            candidates += BackupMediaCandidate(kind, personaId, file.name, file, mime)
        }
        personas.forEach { addCandidate(BackupMediaKind.PERSONA_AVATAR, it.id, it.avatarUri, avatarDir) }
        addCandidate(BackupMediaKind.USER_AVATAR, null, settings.userAvatarUri, avatarDir)
        presets.forEach { addCandidate(BackupMediaKind.PERSONA_AVATAR, null, it.avatarUri, avatarDir) }
        messages.forEach { message ->
            AttachmentsCodec.decode(message.attachmentsJson).forEach { attachment ->
                addCandidate(BackupMediaKind.CHAT_IMAGE, message.personaId, attachment.uri, mediaDir)
            }
        }
        val packed = BackupMediaPacker.pack(candidates)
        val chatImageNames = packed.media
            .filter { it.kind == BackupMediaKind.CHAT_IMAGE }
            .map { it.fileName }
            .toSet()

        val bundle = BackupBundle(
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = now,
            personas = personas.map { it.toBackupPersona() },
            memories = memories.map { it.toBackupMemory() },
            sessions = sessions.map { it.toBackupSession() },
            messages = messages.map { it.toBackupMessage(chatImageNames) },
            media = packed.media,
            presets = presets.map { it.toBackupPreset() },
            proactiveRules = proactiveRuleDao.observeAll().first().map { it.toBackupRule() },
            agentIntents = intents.map { it.toBackupIntent() },
            memoryConsolidations = consolidations.map { it.toBackupConsolidation() },
            apiConfigs = apiConfigDao.getAll().map { it.toBackupApiConfig() },
            settings = settings.toBackupSettings()
        )

        // 空包闸（审计 P2-14）：没有任何人事/记忆/对话时不该导出一个「什么都接不回来」的文件，
        // 用户会以为备份成功了，换手机才发现里面是空的。
        if (bundle.personas.isEmpty() && bundle.memories.isEmpty() && bundle.sessions.isEmpty() &&
            bundle.messages.isEmpty() && bundle.presets.isEmpty() && bundle.proactiveRules.isEmpty()
        ) {
            userError(DomainErrorCode.BACKUP_EMPTY)
        }

        val encoded = BackupCodec.encode(bundle)
        // 最后一道闸：真出现了密钥字段就中止导出，不写文件。
        if (BackupCodec.containsSecretField(encoded)) userError(DomainErrorCode.BACKUP_SECRET_FIELD)
        BackupExport(
            json = encoded,
            mediaWarnings = if (packed.skippedByLimit > 0) {
                listOf(BackupMediaWarning.ExportSkipped(packed.skippedByLimit))
            } else {
                emptyList()
            },
            mediaCount = packed.media.size
        )
    }

    /** 只要 JSON 文本的调用方（测试、旧代码）：媒体提醒走 [export]。 */
    suspend fun exportJson(): String = export().json

    /**
     * 读进来的备份先解码看一眼，不写库。
     *
     * 界面上先让用户确认「几个人格、多少条记忆、多少句话」，再决定合并还是替换。
     */
    suspend fun preview(raw: String): BackupDecodeResult = withContext(Dispatchers.IO) {
        // 审计 P1-3：JSON 解析 + DOM 构造 + 逐段反序列化可能几十到几百毫秒（v3 上限 256MB），
        // 不能占着主线程。这里顺手把「结构不完整」也提前算出来，预览对话框先提醒一句。
        //
        // M5：服务端备份用 `avatarUrl/importance(0–100)/unread/attachments 数组`，直接按本地
        // v5 预览会把记忆/消息当成坏条目报 warning；预览与导入统一先过适配器。
        val readable = if (serverWrites.isEnabled()) {
            com.yap.app.data.remote.ServerBackupAdapter.toLocalBackupJson(raw) ?: raw
        } else {
            raw
        }
        // 本地模式保持原调用形态（契约测试与审计都按「同一个 BackupCodec」盯这条路径）；
        // 服务端模式才走适配后的文本。
        val decodedResult = if (readable == raw) {
            BackupCodec.decode(raw)
        } else {
            BackupCodec.decode(readable)
        }
        when (val decoded = decodedResult) {
            is BackupDecodeResult.Failure -> decoded
            is BackupDecodeResult.Success -> {
                val holes = BackupIntegrity.check(decoded.bundle)
                if (holes.isClean) {
                    decoded
                } else {
                    decoded.copy(
                        warnings = decoded.warnings + BackupWarning.IncompleteReferences(holes.total)
                    )
                }
            }
        }
    }

    /** 预览用的一行摘要（读不懂时返回 null，原因走 [preview]）。 */
    suspend fun previewOf(raw: String): BackupPreview? =
        (preview(raw) as? BackupDecodeResult.Success)?.let { BackupPreview.from(it.bundle) }

    /**
     * 从 JSON 文本把他接回来。读不懂就抛 [BackupImportException]，库里什么都不改。
     *
     * 整段（解析 + 映射 + 写库）在 `Dispatchers.IO` 上：Room 事务自己会切线程，
     * 但「解码 + 结构映射」是纯 CPU 活，留在 `viewModelScope`（Main）上就是一段 ANR 风险。
     */
    suspend fun importJson(raw: String, mode: ImportMode): ImportSummary =
        withContext(Dispatchers.IO) {
            if (serverWrites.isEnabled()) {
                return@withContext importServerBackup(raw, mode)
            }
            when (val decoded = BackupCodec.decode(raw)) {
                is BackupDecodeResult.Failure -> throw BackupImportException(decoded.reason)
                is BackupDecodeResult.Success -> {
                    val summary = importBundle(decoded.bundle, mode)
                    summary.copy(
                        warnings = decoded.warnings + summary.warnings,
                        mediaWarnings = decoded.mediaWarnings + summary.mediaWarnings
                    )
                }
            }
        }

    /**
     * 服务端模式的「接他回来」：先交给服务端导入，成功后再把同一份数据写进本机缓存。
     *
     * 为什么本机也要写一遍：服务端成功之后本机若还停在旧内容，用户会看到「接回来了但列表没变」，
     * 直到下一次同步才恢复。缓存写失败**不推翻**服务端已经完成的事实——只记日志，
     * 返回服务端的分段计数（界面提示以服务端为准），下一次同步会补齐。
     */
    private suspend fun importServerBackup(raw: String, mode: ImportMode): ImportSummary {
        val serverMode = when (mode) {
            ImportMode.MERGE -> com.yap.app.data.remote.ServerBackupMode.MERGE
            ImportMode.REPLACE -> com.yap.app.data.remote.ServerBackupMode.REPLACE
        }
        val imported = serverWrites.importBackup(raw, serverMode).getOrThrow()
        runCatching {
            // 服务端 JSON 与客户端 v5 的字段名有差异（avatarUrl/importance/unread/attachments），
            // 先适配再写本地缓存；不适配有「头像全没、重要度变成 80 星」这类静默错位。
            val localRaw = com.yap.app.data.remote.ServerBackupAdapter.toLocalBackupJson(raw)
                ?: return@runCatching
            when (val decoded = BackupCodec.decode(localRaw)) {
                is BackupDecodeResult.Failure -> Unit
                is BackupDecodeResult.Success -> importBundle(decoded.bundle, mode)
            }
        }.onFailure { error ->
            // 日志本身不能把「服务端已导入成功」翻成失败：纯 JVM 单测里 Log 也是 not mocked。
            runCatching {
                android.util.Log.w(
                    "BackupRepository",
                    "local cache refresh after server import failed: ${error.javaClass.simpleName}"
                )
            }
        }
        val counts = imported.imported
        return ImportSummary(
            personas = counts.personas,
            memories = counts.memories,
            sessions = counts.sessions,
            messages = counts.messages,
            rules = 0,
            skipped = 0,
            warnings = emptyList(),
            mediaWarnings = emptyList(),
            mode = mode,
            presets = counts.presets
        )
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

        // 一人一段：备份里同一人格有多条时只保留最新一条（规格 §八）。
        // v1 备份可能着带好几段，v2 之后的备份不该有；这里统一按「保留最新」处理。
        val latestSessions = BackupIntegrity.sessionsToRestore(bundle)

        // 引用完整性闸（审计 P1-2 / 0.11.1）：**在 database.withTransaction 之前**算清
        // 「这份备份能落成什么」。消息找不到会话、会话/记忆/规则/念头找不到人格时整份拒绝，
        // 绝不先清空本机再把这些行丢掉（那是「本机没了、备份也没接回来」）。
        BackupIntegrity.rejectionOf(bundle)?.let { failure ->
            throw BackupImportException(failure)
        }
        // 「替换」不能拿一份四个主段全空的备份把本机清成空（界面本来也不给这个按钮，
        // 这里挡住绕过界面的调用方）。
        if (mode == ImportMode.REPLACE && BackupIntegrity.isEffectivelyEmpty(bundle)) {
            userError(DomainErrorCode.BACKUP_EMPTY)
        }
        if (latestSessions.size != bundle.sessions.size) {
            warnings += BackupWarning.ExtraSessions(bundle.sessions.size - latestSessions.size)
        }

        // ---- v3：先把 media 落盘，再落库引用 ---------------------------------
        // 顺序是刻意的：库里出现的每个 file:// 地址都必须已经指向一个真实文件。
        // 写失败/坏数据的条目跳过并计数；导入结束后若落库失败，会把这次写的文件回滚删掉。
        val avatarDir = MediaStorage.avatarDirectory(appContext)
        val mediaDir = MediaStorage.mediaDirectory(appContext)
        val referencedAvatarNames = buildSet {
            bundle.personas.forEach { BackupAvatarPath.fileName(it.avatarUri)?.let { name -> add(name) } }
            bundle.presets.forEach { BackupAvatarPath.fileName(it.avatarUri)?.let { name -> add(name) } }
        }
        val personaIds = bundle.personas.map { it.id }.toSet()
        val referencedChatNames = buildSet {
            bundle.messages.forEach { message ->
                AttachmentsCodec.decode(message.attachmentsJson).forEach { attachment ->
                    val name = attachment.uri.trim()
                    if (name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains("..")) {
                        add(name)
                    }
                }
            }
        }
        // 只恢复「这次备份里真的会被引用到」的媒体：没被任何头像/消息引用的条目写下去
        // 也只是孤儿文件（还会给恶意备份一个白占 128 MiB 的机会）。
        val restorableMedia = bundle.media.filter { media ->
            when (media.kind) {
                BackupMediaKind.USER_AVATAR -> true
                BackupMediaKind.PERSONA_AVATAR -> {
                    val mediaPersonaId = media.personaId?.trim().orEmpty()
                    media.fileName in referencedAvatarNames ||
                        (mediaPersonaId.isNotEmpty() && mediaPersonaId in personaIds)
                }
                BackupMediaKind.CHAT_IMAGE -> media.fileName in referencedChatNames
                else -> false
            }
        }
        val restoredMedia = BackupMediaRestorer.restore(
            entries = restorableMedia,
            avatarDir = avatarDir,
            chatDir = mediaDir,
            // 导入压缩：头像长边 512 / 聊天图 1600、JPEG 85、透明垫白底，输出字节有上限（0.12）。
            compressor = { bytes, mime, kind -> MediaStorage.compressBackupImage(bytes, mime, kind) }
        )
        val mediaWarnings = restoredMedia.warnings.toMutableList()
        val restoredChatFileNames = restoredMedia.chatImagesByFileName.values.map { it.file.name }.toSet()

        // 备份里的头像只有文件名（安全 P2-5）：v3 用 media 里的同名条目恢复成新的本机文件；
        // v1/v2 没有 media，仍按「本机 files/avatars 里有没有同名文件」处理。
        // 有 → 恢复成本机路径；没有（换设备的常态）→ 清空并记一条导入提醒，
        // 别让界面拿到一个「点了没反应」的地址。
        val personas = bundle.personas.map { backup ->
            val rawAvatar = backup.avatarUri?.trim().orEmpty()
            val restored = rawAvatar.takeIf { it.isNotEmpty() }?.let { restoredMedia.avatarsByFileName[it] }
                ?: restoredMedia.avatarsByPersonaId[backup.id]
            val localAvatar = restored?.uri ?: MediaStorage.resolveImportedAvatar(appContext, rawAvatar)
            if (rawAvatar.isNotEmpty() && restored == null && localAvatar == null) {
                warnings += BackupWarning.AvatarMissing(backup.name)
            }
            backup.copy(avatarUri = localAvatar).toEntity()
        }
        val memories = bundle.memories.map { it.toEntity() }
        val sessions = bundle.sessions.map { it.toEntity() }
        // 预设头像与人格同一策略（安全 P2-6 / 审计 P2-8）：v3 用 media 恢复成本机新文件，
        // 老备份则只认本机同名文件——不把外来绝对路径原样写进 presets。
        val presets = bundle.presets.map { preset ->
            val restored = preset.avatarUri?.trim()?.takeIf { it.isNotEmpty() }?.let {
                restoredMedia.avatarsByFileName[it]
            }
            val localAvatar = restored?.uri ?: MediaStorage.resolveImportedAvatar(appContext, preset.avatarUri)
            preset.copy(avatarUri = localAvatar).toEntity()
        }
        val rules = bundle.proactiveRules.map { it.toEntity() }
        val intents = bundle.agentIntents.map { it.toEntity() }
        val consolidations = bundle.memoryConsolidations.map { it.toEntity() }

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
        // 附件：v3 用 media 里的 CHAT_IMAGE 重建成本机新路径；v1/v2 没有 media，仍然只剩文字。
        var messagesWithDroppedAttachments = 0
        val messages = bundle.messages.map { message ->
            val restoredAttachments = BackupAttachmentsPortable.restore(
                message.attachmentsJson,
                restoredMedia.chatImagesByFileName
            )
            if (!message.attachmentsJson.isNullOrBlank() && restoredAttachments.dropped > 0) {
                messagesWithDroppedAttachments++
            }
            message.toEntity().copy(
                sessionId = sessionIdMap[message.sessionId] ?: message.sessionId,
                attachmentsJson = restoredAttachments.json
            )
        }.filter { message ->
            val ok = message.sessionId in sessionIdMap.values
            if (!ok) skipped++
            ok
        }

        if (messagesWithDroppedAttachments > 0) {
            warnings += BackupWarning.AttachmentsDropped(messagesWithDroppedAttachments)
        }

        // 备份替换时按引用保留头像：备份里带到的 avatarUri（可能就指向本机仍有效的文件）、
        // 这次刚从 media 落盘的新头像、以及用户自己的头像。
        // 其余旧头像文件当孤儿回收（否则「替换」之后旧头像永远留在手机上）。
        val keepAvatarUris = buildSet {
            bundle.personas.forEach { persona ->
                // 备份里是文件名，本机要留的是解析后的绝对路径（解析不出来就是没有这个文件）。
                MediaStorage.resolveImportedAvatar(appContext, persona.avatarUri)?.let { add(it) }
            }
            // v3 新落盘的头像（含预设/用户自己的）：此刻库里还没引用它们，必须显式保住。
            restoredMedia.avatarsByFileName.values.forEach { add(it.uri) }
            val restoredUserAvatar = restoredMedia.userAvatar
            if (restoredUserAvatar != null) {
                add(restoredUserAvatar.uri)
            } else {
                runCatching { settingsRepository.settings.first().userAvatarUri }
                    .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        // 审计 P2-3（0.11.1）：替换会清空本机、再从备份插回**同一个 sessionId**。
        // 在途生成的收尾（saveInterrupted / saveFailed / save）持有的是 bump 前的世代，
        // 如果不作废，就会把「用旧上下文生成的半截回复」写进刚恢复的会话。
        // 放在所有闸门之后、进事务之前：被拒绝的备份不该顺手打断一段正常的在途回复。
        // （世代号同时也让 HistoryCompressor 的 CompressionWriteGuard 丢弃旧摘要。）
        if (mode == ImportMode.REPLACE) {
            val doomed = buildSet {
                sessionDao.getAll().forEach { add(it.id) }
                latestSessions.forEach { add(it.id) }
            }
            doomed.forEach { sessionId ->
                sessionGeneration.bump(sessionId)
                chatRepository.cancelGeneration(sessionId)
            }
        }

        try {
            database.withTransaction {
                if (mode == ImportMode.REPLACE) {
                    // 0.10.2：清空清单统一收在 DataEraser（与「清空聊天与记忆 / 删除全部数据 / 删人格」
                    // 共用 ErasurePlan），这里不再手写一份——以前漏的 agent_ticks / memory_vectors /
                    // persona_pending_patches / 聊天图片文件都在那份清单里。
                    // 注意：API 配置从不删（Key 只在本机），所以 ErasurePlan.BACKUP_REPLACE 不含它。
                    // 用 InsideTransaction 变体：本函数已经在 database.withTransaction 里，
                    // 不再嵌套开事务（清空 + 写回必须同属一个原子操作）。
                    // 它只删表、只收集待删文件；文件清理在事务提交之后才做（审计 P1-4）。
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
                // 规范 §5：块的元数据是派生数据，不进备份；导入（替换/合并）之后按消息顺序
                // 用追加规则重建 blockIndex 与块行。合并在中间插入更早的消息也没问题——
                // 这里整段重建，不依赖「新消息一定排在最后」。
                blockWriter.insertImported(messages)
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
        } catch (error: Throwable) {
            // 落库失败：把刚写进媒体目录的文件删掉，别在用户手机上留一批没人引用的图片。
            restoredMedia.writtenFiles.forEach { file -> runCatching { file.delete() } }
            throw error
        }
        // 事务已经提交：现在才清理旧媒体文件（审计 P1-4）。失败最多留下孤儿文件，
        // 不能让「他已经被替换接回来」的导入再报一次失败。
        //
        // 这里没有直接 dataEraser.commitFiles(erasure)：BackupReplace 的清理是「把 files/media/chat-*
        // 全删掉」，而 v3 的新聊天图片是在事务**之前**写进去的（库里已经引用它们）——照搬会把
        // 刚恢复的图片删光。所以按同一份语义手工做：旧头像按引用回收、旧聊天图片只删不是这次写的。
        if (mode == ImportMode.REPLACE) {
            runCatching {
                // 0.12：两条清理都带引用守卫——`keep` 集合是按备份里的引用算的，
                // 一个刚写进来、备份里没写成引用格式的新文件可能漏在 keep 外面；
                // 只按 keep 删会把它删掉（人格「接回来了却没有脸」）。旧图没有引用才回收。
                mediaJanitor.clearUnreferencedAvatars(keepAvatarUris)
                mediaJanitor.clearChatImagesExcept(restoredChatFileNames)
            }
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

        // 「我的头像」不在可移植设置里，但 v3 会把它的图片一起带来：
        // 设置写完之后再指到刚恢复的新文件（没有 USER_AVATAR 时保持本机原样）。
        restoredMedia.userAvatar?.let { avatar ->
            runCatching { settingsRepository.setUserAvatarUri(avatar.uri) }
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
            mediaWarnings = mediaWarnings.toList(),
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
