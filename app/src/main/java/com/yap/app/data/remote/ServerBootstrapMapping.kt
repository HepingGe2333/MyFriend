package com.yap.app.data.remote

import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MemoryCategory
import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPresetEntity
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.MemoryTags
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * bootstrap 期间的「服务端 JSON ⇄ 本地实体」映射（纯函数，无 Room / 无 Android）。
 *
 * 与 M5 写路径的 [ServerPersonaWrite] / [ServerPresetWrite] / [ServerMemoryWrite] 分工：
 * - 那三个是**单条保存**的请求/响应映射，只关心服务端真正拥有的列；
 * - 这里是**全量搬运**：服务端 `GET /v1/personas|presets|{id}/memories` 返回的是完整对象
 *   （含 `extraFields` 里的客户端字段），拉回本机时要恢复成能直接渲染/进提示词的实体。
 *
 * 三条硬规则：
 * 1. 本地已有的行**保留自己的主键与本地专属列**（抽屉、分类、绑定关系、心情缓存）；
 * 2. 服务端拥有的列以服务端为准（名字、提示词、头像、正文、时间戳）；
 * 3. 缺失/坏字段一律回落到本地值或安全默认值，一条脏数据不能把整次 bootstrap 打挂。
 */
object ServerBootstrapMapping {

    /** 导入消息的幂等前缀：服务端即使自分配 id，也能按这个 clientMessageId 认出同一条。 */
    const val IMPORT_CLIENT_MESSAGE_ID_PREFIX = "import-"

    /** 服务端导入端接受的 id 字符集（`agentd` 的 `MESSAGE_ID_PATTERN`）：超出的本地 id 要换成稳定哈希。 */
    private val IMPORT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

    /**
     * 本地消息 id → 服务端导入 id。
     *
     * 正常路径是客户端 UUID（只含 16 进制与 `-`），原样保留 → 之后增量同步按同一 id 覆盖本地行，
     * 不会出现「同一条消息两份」。只有异常历史数据（含空格/中文/冒号等）才换成确定性哈希：
     * 服务端会整批 400，宁可牺牲这一条的 id 对齐也不能让整次 bootstrap 永远重试失败。
     */
    fun importMessageId(localId: String): String {
        if (IMPORT_ID_PATTERN.matches(localId)) return localId
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(localId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { byte -> "%02x".format(byte) }
        return "import-" + hex.take(32)
    }

    /**
     * 本地附件 → 可导入的服务端附件。
     *
     * 服务端导入端**只接受已经存在的 `/v1/media/<id>`**；本地 `file://` 聊天图在 bootstrap 阶段
     * 没有上传（媒体历史迁移不在本版范围），必须在这里丢掉，否则整批 400、聊天文本也搬不过去。
     * 这是已知边界：文字历史完整，图片历史在另一台机器上暂时看不到。
     */
    fun importAttachments(attachmentsJson: String?): List<String> =
        AttachmentsCodec.decode(attachmentsJson).mapNotNull { ServerMediaRef.canonical(it.uri) }

    private fun importClientMessageId(message: MessageEntity, serverId: String): String? =
        if (message.role == MessageRole.USER) IMPORT_CLIENT_MESSAGE_ID_PREFIX + serverId else null

    // ------------------------------------------------------------------ 人格

    /**
     * 服务端人格 JSON → 本地实体。
     *
     * @param local 本地同一实体的当前行（按 id 映射查到）；null = 服务端独有，按默认值新建
     */
    fun personaEntity(
        raw: JsonObject,
        local: PersonaEntity?,
        now: Long = System.currentTimeMillis()
    ): PersonaEntity {
        val serverId = raw.string("id")?.takeIf { it.isNotBlank() } ?: local?.id.orEmpty()
        val seed = raw.int("avatarSeed") ?: local?.avatarSeed ?: serverId.hashCode()
        val createdAt = raw.long("createdAt")?.takeIf { it > 0L }
            ?: local?.createdAt
            ?: now
        val updatedAt = raw.long("updatedAt")?.takeIf { it > 0L }
            ?: local?.updatedAt
            ?: createdAt
        return PersonaEntity(
            id = local?.id ?: serverId,
            name = raw.string("name")?.takeIf { it.isNotBlank() } ?: local?.name.orEmpty(),
            description = raw.string("description") ?: local?.description.orEmpty(),
            avatarUri = avatarRef(raw.string("avatarUrl"))
                ?: local?.avatarUri,
            avatarSeed = seed,
            prompt = raw.string("prompt") ?: local?.prompt.orEmpty(),
            category = raw.string("category") ?: local?.category
                ?: ServerMergePlan.DEFAULT_SERVER_PERSONA_CATEGORY,
            version = raw.int("version") ?: local?.version ?: 1,
            defaultApiConfigId = raw.string("defaultApiConfigId")
                ?: local?.defaultApiConfigId,
            modelOverride = raw.string("modelOverride") ?: local?.modelOverride,
            isDefault = raw.bool("isDefault") ?: local?.isDefault ?: false,
            createdAt = createdAt,
            updatedAt = updatedAt,
            personaCardJson = raw.string("personaCardJson") ?: local?.personaCardJson,
            quietSleepMinutes = raw.int("quietSleepMinutes") ?: local?.quietSleepMinutes,
            quietWakeMinutes = raw.int("quietWakeMinutes") ?: local?.quietWakeMinutes,
            mood = raw.string("mood") ?: local?.mood,
            moodIntensity = raw.int("moodIntensity") ?: local?.moodIntensity,
            moodReason = raw.string("moodReason") ?: local?.moodReason,
            moodUpdatedAt = raw.long("moodUpdatedAt") ?: local?.moodUpdatedAt,
            moodNudgedAt = raw.long("moodNudgedAt") ?: local?.moodNudgedAt,
            traits = raw.string("traits") ?: local?.traits ?: "[]",
            likes = raw.string("likes") ?: local?.likes ?: "[]",
            behaviors = raw.string("behaviors") ?: local?.behaviors ?: "[]",
            backstory = raw.string("backstory") ?: local?.backstory,
            quirks = raw.string("quirks") ?: local?.quirks,
            proactiveLevel = raw.int("proactiveLevel") ?: local?.proactiveLevel ?: 3,
            memoryRetention = raw.int("memoryRetention") ?: local?.memoryRetention ?: 3,
            extractionEvery = raw.int("extractionEvery") ?: local?.extractionEvery ?: 6,
            sleepEnabled = raw.bool("sleepEnabled") ?: local?.sleepEnabled ?: false,
            wakeThreshold = raw.int("wakeThreshold") ?: local?.wakeThreshold ?: 3,
            dndOnSleep = raw.bool("dndOnSleep") ?: local?.dndOnSleep ?: false,
            worldJson = raw.string("worldJson") ?: local?.worldJson,
            speechStyleJson = raw.string("speechStyleJson") ?: local?.speechStyleJson,
            activityJson = raw.string("activityJson") ?: local?.activityJson,
            translationJson = raw.string("translationJson") ?: local?.translationJson,
            translationLang = raw.string("translationLang") ?: local?.translationLang
        )
    }

    /** bootstrap 需要的人格外层摘要（[StateSnapshot.personas] 只带这四个字段）。 */
    fun personaSummary(raw: JsonObject, fallbackId: String = ""): ServerPersona = ServerPersona(
        id = raw.string("id") ?: fallbackId,
        name = raw.string("name").orEmpty(),
        avatarUrl = avatarRef(raw.string("avatarUrl")),
        updatedAt = raw.long("updatedAt") ?: 0L
    )

    // ------------------------------------------------------------------ 预设

    fun presetEntity(
        raw: JsonObject,
        local: PersonaPresetEntity?,
        now: Long = System.currentTimeMillis()
    ): PersonaPresetEntity {
        val serverId = raw.string("id")?.takeIf { it.isNotBlank() } ?: local?.id.orEmpty()
        val createdAt = raw.long("createdAt")?.takeIf { it > 0L } ?: local?.createdAt ?: now
        return PersonaPresetEntity(
            id = local?.id ?: serverId,
            name = raw.string("name")?.takeIf { it.isNotBlank() } ?: local?.name.orEmpty(),
            description = raw.string("description") ?: local?.description.orEmpty(),
            prompt = raw.string("prompt") ?: local?.prompt.orEmpty(),
            category = raw.string("category") ?: local?.category.orEmpty(),
            avatarUri = avatarRef(raw.string("avatarUri")) ?: local?.avatarUri,
            avatarSeed = raw.int("avatarSeed") ?: local?.avatarSeed ?: serverId.hashCode(),
            createdAt = createdAt,
            updatedAt = raw.long("updatedAt")?.takeIf { it > 0L } ?: local?.updatedAt ?: createdAt,
            worldJson = raw.string("worldJson") ?: local?.worldJson,
            speechStyleJson = raw.string("speechStyleJson") ?: local?.speechStyleJson
        )
    }

    // ------------------------------------------------------------------ 记忆

    /**
     * 服务端记忆 JSON → 本地实体。
     *
     * 服务端只有 `content/importance(0–100)/tags` 是权威列；本地多出来的抽屉/分类/固定/
     * 生命周期/回忆漂移全部保留本地值——别的设备看不到抽屉分类是当前契约的已知边界。
     */
    fun memoryEntity(
        raw: JsonObject,
        personaId: String,
        local: MemoryEntity?,
        now: Long = System.currentTimeMillis()
    ): MemoryEntity {
        val serverId = raw.string("id")?.takeIf { it.isNotBlank() } ?: local?.id.orEmpty()
        val createdAt = raw.long("createdAt")?.takeIf { it > 0L } ?: local?.createdAt ?: now
        val updatedAt = raw.long("updatedAt")?.takeIf { it > 0L } ?: local?.updatedAt ?: createdAt
        val tags = raw.stringList("tags")
        return MemoryEntity(
            id = local?.id ?: serverId,
            personaId = personaId.ifBlank { local?.personaId.orEmpty() },
            content = raw.string("content")?.takeIf { it.isNotBlank() }
                ?: local?.content.orEmpty(),
            category = local?.category ?: MemoryCategory.FACT,
            importance = raw.int("importance")?.let(ServerMemoryWrite::importanceFromServer)
                ?: local?.importance ?: 3,
            pinned = local?.pinned ?: false,
            sourceSessionId = local?.sourceSessionId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastUsedAt = local?.lastUsedAt ?: 0L,
            useCount = local?.useCount ?: 0,
            drawer = local?.drawer,
            sourceQuote = local?.sourceQuote,
            status = local?.status ?: "ACTIVE",
            strength = local?.strength ?: 1.0,
            archivedAt = local?.archivedAt,
            consolidatedAt = local?.consolidatedAt,
            tags = if (tags.isNotEmpty()) MemoryTags.encode(tags) else local?.tags,
            happenedAt = local?.happenedAt,
            originalContent = local?.originalContent,
            driftCount = local?.driftCount ?: 0
        )
    }

    // ------------------------------------------------------------------ 消息导入

    /** 本地消息 → 服务端批量导入条目；顺序由调用方保证（按 createdAt/id 升序分批）。 */
    fun importPayload(messages: List<MessageEntity>): List<ServerMessageImport> =
        messages.map { message ->
            val wireRole = when (message.role) {
                MessageRole.USER -> "USER"
                MessageRole.ASSISTANT -> "ASSISTANT"
                MessageRole.SYSTEM -> "SYSTEM"
            }
            val serverId = importMessageId(message.id)
            ServerMessageImport(
                id = serverId,
                role = wireRole,
                content = message.content,
                createdAt = message.createdAt,
                // 已读时间原样保留：服务端未读数是按 assistant+readAt 判的，丢掉会让另一端全是未读。
                readAt = message.readAt,
                attachments = importAttachments(message.attachmentsJson),
                source = "IMPORT",
                // 仅用户消息带幂等键（与服务端「clientMessageId 只出现在用户消息」的约定一致）。
                clientMessageId = importClientMessageId(message, serverId)
            )
        }

    /** 本机消息排序口径：与块内稳定序一致（`createdAt` 升序、同毫秒按 id）。 */
    fun sortMessages(messages: List<MessageEntity>): List<MessageEntity> =
        messages.sortedWith(compareBy({ it.createdAt }, { it.id }))

    /** 本地会话可导出的标题（服务端不接受空标题；空就用 null 让它自己生成）。 */
    fun sessionTitle(session: ChatSessionEntity): String? =
        session.title.trim().takeIf { it.isNotEmpty() }

    /**
     * 媒体引用归一：服务端可能回裸 id / `/v1/media/<id>` / 绝对 URL。
     *
     * 前两种统一成 `/v1/media/<id>`（渲染层再拼当前服务端地址并加鉴权头）；
     * 绝对 URL 原样保留（外部图床）。空值返回 null。
     */
    fun avatarRef(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return ServerMediaRef.canonical(value) ?: value
    }

    /** 一个 JSON 数组里的字符串；非数组/坏元素直接忽略。 */
    private fun JsonObject.stringList(key: String): List<String> {
        val array = runCatching { get(key) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { (element as? JsonPrimitive)?.jsonPrimitive?.content }.getOrNull()
        }.filter { it.isNotBlank() }
    }
}
