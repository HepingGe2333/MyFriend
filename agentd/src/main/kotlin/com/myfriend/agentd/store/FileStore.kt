package com.myfriend.agentd.store

import com.myfriend.app.core.json.JsonParseException
import com.myfriend.app.core.json.JsonValue
import com.myfriend.agentd.store.StoreJson.optArr
import com.myfriend.agentd.store.StoreJson.optInt
import com.myfriend.agentd.store.StoreJson.optLong
import com.myfriend.agentd.store.StoreJson.optObj
import com.myfriend.agentd.store.StoreJson.optString
import com.myfriend.agentd.store.StoreJson.reqLong
import com.myfriend.agentd.store.StoreJson.reqString
import com.myfriend.agentd.store.StoreJson.stringList
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `agentd` 的服务端状态存储：**纯文件 + JSON，零第三方依赖**（不引 SQLite/JDBC）。
 *
 * 设计取舍（`docs/design/server-authoritative.md` §4 要求「先用 SQLite」，本阶段改成文件存储）：
 * - 这个服务是**一个人一台服务端**，写并发只有「一个聊天请求 + 一个心跳」量级，不需要数据库的
 *   事务/索引能力；而零第三方依赖是本模块的硬约束（发行包只有 `agentd.jar + agentcore.jar`），
 *   引 `sqlite-jdbc` 就会破坏它。代价是：没有事务、没有并发多写、查询靠扫块（见 [Risk] 说明）。
 * - 唯一真相源是磁盘：进程重启后全部从文件读回（`meta.json` 的 `version` 对不上就拒绝启动，
 *   不静默重建）。
 *
 * 目录结构（`--data <path>`，默认 `./agentd-data`）：
 * ```
 * <root>/
 *   meta.json                        {"version":1,"cursor":<long>,"seeded":<bool>}
 *   personas.json                    {"personas":[...]}
 *   sessions.json                    {"sessions":[...]}
 *   settings.json                    {"settings":{...}}
 *   api_configs.json                 {"configs":[...]}          ← 只放元数据，**没有 key**
 *   secrets.json                     {"keys":{...}}             ← 0600，仅服务端内部读，永不下发
 *   moods.json / presence.json       {"moods":[...]} / {"presence":[...]}
 *   usage.json                       {"usage":{personaId:{...}}}
 *   events.jsonl                     每行一个事件（增量游标；append-only）
 *   sessions/<sessionId>/block_index.json   {"sessionId":…,"blocks":[{index,firstMessageId,…}]}
 *   sessions/<sessionId>/blocks/<blockIndex>.jsonl   每行一条消息（append-only）
 *   media/                           先只建目录；文件上传接口在下一阶段
 * ```
 *
 * 写入的原子性：
 * - 所有「整体替换」的 JSON 文件（meta/personas/sessions/block_index/moods/…）都是
 *   **先写 `*.tmp` + `force`，再 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`**；
 * - 消息是 append-only：追加一行到 `.jsonl`，不重写历史文件；删除/标已读才整块重写（同样原子替换）；
 * - 所有写操作共用一把 [ReentrantLock]（单写锁），读也在同一把锁下，避免读到半个块表；
 * - 崩溃窗口：消息行已追加、但 `block_index.json`/`sessions.json` 还没落盘时进程被杀，块计数会少 1。
 *   恢复办法是删掉该会话的 `block_index.json` 让服务端按块文件重建（见 [rebuildBlockIndex]），
 *   这一步**不会**自动做（不静默改数据）。`events.jsonl` 的末尾半行在读取时会被跳过。
 *
 * 块维护（与 `spec/messages-blocks/README.md` 同构，`BLOCK_SIZE = 200`）：
 * - 追加：块满 200 → 开 `blockIndex + 1`；新消息进最新块；
 * - 删除：重算该块的 first/last/count；**块空了就删文件**，块表里删掉这一行 → 编号留空洞；
 * - **不重平衡**：块可以变短，只有最新块在追加时增长；「上一块/下一块」按块表里的相邻编号找，
 *   所以空洞不影响分页。
 */
class FileStore(
    val root: Path,
    private val clock: () -> Long = { System.currentTimeMillis() },
    seedIfEmpty: Boolean = true
) {

    companion object {
        /** 数据目录版本；结构一改就要 +1，并且 [FileStore] 要能读旧版本或明确拒绝。 */
        const val VERSION = 1

        /** 一块最多多少条消息（与客户端 `BLOCK_SIZE` 一致）。 */
        const val BLOCK_SIZE = 200

        /** 事件日志常驻内存的条数上限；更早的仍然按需从 `events.jsonl` 读回。 */
        const val MAX_EVENTS_IN_MEMORY = 20_000

        /** `GET /v1/state` 一次最多返回多少条消息（超出时 `hasMoreMessages = true`）。 */
        const val MAX_STATE_MESSAGES = 500

        /**
         * `personas.activityJson` 的长度上限（字符）。
         *
         * 活动是一句话（客户端 `PersonaActivity` 里 `activity` 最多 160 字），四格 JSON 撑不到
         * 1 KiB。上限存在的意义是「不许借这个字段往 personas.json 里塞大块数据」。
         */
        const val MAX_ACTIVITY_JSON_CHARS = 1024

        /** 会话/人格 id 只允许 `[A-Za-z0-9_-]`：它们会拼进文件路径，不能接受 `..` / `/`。 */
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /** 消息 id 不拼路径，但要防超长/奇怪值；客户端 UUID 与服务端 UUID 都满足。 */
        private val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

        const val META_FILE = "meta.json"
        const val PERSONAS_FILE = "personas.json"
        const val SESSIONS_FILE = "sessions.json"
        const val SETTINGS_FILE = "settings.json"
        const val API_CONFIGS_FILE = "api_configs.json"
        const val SECRETS_FILE = "secrets.json"
        const val MOODS_FILE = "moods.json"
        const val PRESENCE_FILE = "presence.json"
        const val USAGE_FILE = "usage.json"
        const val PROACTIVE_STATE_FILE = "proactive_state.json"
        const val MEMORIES_FILE = "memories.json"
        const val PRESETS_FILE = "presets.json"
        const val PUSH_TOKENS_FILE = "push_tokens.json"
        const val EVENTS_FILE = "events.jsonl"

        /** 当前导出的备份格式版本（与客户端 `BACKUP_FORMAT_VERSION` 对齐）。 */
        const val BACKUP_FORMAT_VERSION = 5
        const val BACKUP_FORMAT_NAME = "myfriend.backup"

        /** `GET /v1/settings` / 备份可携带的设置键；任何 API Key / secrets 都不在白名单里。 */
        val SETTINGS_WHITELIST: Set<String> = setOf(
            "themeMode",
            "accentColor",
            "fontScale",
            "bubbleStyle",
            "animationsEnabled",
            "hapticsEnabled",
            "chatBackgroundColor",
            "agentLoopEnabled",
            "activePersonaId",
            "autoTranslatePersona"
        )
        const val SESSIONS_DIR = "sessions"
        const val BLOCKS_DIR = "blocks"
        const val BLOCK_INDEX_FILE = "block_index.json"
        const val MEDIA_DIR = "media"

        /**
         * 播种人格的固定 id 与中英文名对。
         *
         * 为什么不用 Android 的 `SeedPersonas`：它从 `R.string` 读名字，`:agentd` 没有 Android 资源。
         * 服务端自己定义一套**稳定 id**（客户端可以把它们当成普通人格；名字与 Android 种子一致，
         * 便于两端对照），写进 README。
         */
        const val SEED_PERSONA_1_ID = "seed-linshen"
        const val SEED_PERSONA_2_ID = "seed-alan"
        const val SEED_PERSONA_3_ID = "seed-mo"
        val SEED_PERSONA_IDS = listOf(SEED_PERSONA_1_ID, SEED_PERSONA_2_ID, SEED_PERSONA_3_ID)

        /** 空目录播种时附带的示例会话（让 `curl` 冒烟不用先调「建会话」接口）。 */
        const val SEED_SESSION_ID = "seed-session-linshen"

        /** 安全 id 校验：路由层和存储层各挡一次（防路径穿越）。 */
        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        fun open(
            root: Path,
            clock: () -> Long = { System.currentTimeMillis() },
            seedIfEmpty: Boolean = true
        ): FileStore = FileStore(root, clock, seedIfEmpty)
    }

    // ------------------------------------------------------------------ 内存状态
    //
    // 消息正文不进内存（按块从磁盘读）；这里只缓存元数据，保证「一页一块」的内存占用与历史长度无关。

    private val lock = ReentrantLock()
    private val eventMonitor = Object()
    private val sessionsDir: Path = root.resolve(SESSIONS_DIR)
    private val mediaDir: Path = root.resolve(MEDIA_DIR)
    private val eventsFile: Path = root.resolve(EVENTS_FILE)

    private var meta: StoreMeta = StoreMeta()
    private val personas = mutableListOf<Persona>()
    private val sessions = LinkedHashMap<String, Session>()
    private val blockCache = HashMap<String, MutableList<BlockMeta>>()
    private val messageIndexCache = HashMap<String, SessionMessageIndex>()
    private val moods = LinkedHashMap<String, Mood>()
    private val presence = LinkedHashMap<String, Presence>()
    private val apiConfigs = LinkedHashMap<String, ApiConfig>()
    private var settings: JsonValue.Obj = JsonValue.Obj()
    private var usage = LinkedHashMap<String, UsageTotal>()
    private val proactiveStates = LinkedHashMap<String, ProactiveState>()
    private val memories = LinkedHashMap<String, Memory>()
    private val presets = LinkedHashMap<String, Preset>()
    /** key = `platform|token`，保证「同一 token 重复注册」幂等。 */
    private val pushTokens = LinkedHashMap<String, PushRegistration>()

    /** 内存里的事件窗口（最早的会被裁剪；更早的从 [eventsFile] 读回）。 */
    private val events = ArrayDeque<StoredEvent>()

    private var cursor: Long = 0
    private var lastTimestamp: Long = 0

    /** 本次打开是否新建了数据目录并播种（`main` 用它打印一行提示）。 */
    val seeded: Boolean

    /** 媒体目录（本阶段只建目录，不上传文件）。 */
    val mediaDirectory: Path get() = mediaDir

    init {
        Files.createDirectories(root)
        Files.createDirectories(sessionsDir)
        Files.createDirectories(mediaDir)
        val metaJson = readJsonFile(root.resolve(META_FILE))
        if (metaJson == null) {
            if (hasExistingData()) {
                throw StoreCorruptionException(
                    "数据目录 $root 里有数据但缺少 $META_FILE；不要手工删除它（要么恢复它，要么换一个空目录）"
                )
            }
            if (seedIfEmpty) {
                seedFreshData()
                seeded = true
            } else {
                writeEmptyBaseline()
                seeded = false
            }
        } else {
            loadExistingData(metaJson)
            seeded = false
        }
    }

    // ================================================================== 读：元数据

    fun meta(): StoreMeta = lock.withLock { meta }

    /** 最新的全局事件游标（`GET /v1/state` 的 `serverCursor`）。 */
    fun cursor(): Long = lock.withLock { cursor }

    /** 服务端当前时间（毫秒）。测试注入假时钟，所以路由不要直接调 `System.currentTimeMillis()`。 */
    fun serverTime(): Long = clock()

    fun personas(): List<Persona> = lock.withLock { personas.toList() }

    fun persona(personaId: String): Persona? = lock.withLock { personas.firstOrNull { it.id == personaId } }

    fun defaultPersonaId(): String = lock.withLock { defaultPersonaIdLocked() }

    fun sessions(): List<Session> = lock.withLock { sessions.values.toList() }

    fun session(sessionId: String): Session? = lock.withLock {
        if (!isValidId(sessionId)) return@withLock null
        sessions[sessionId]
    }

    fun mood(personaId: String): Mood? = lock.withLock { moods[personaId] }

    fun moods(): List<Mood> = lock.withLock { moods.values.toList() }

    fun presence(personaId: String): Presence? = lock.withLock { presence[personaId] }

    /**
     * 读他此刻的活动（`personas.json` 里的 `activityJson`，原样返回）。
     *
     * 不做解析也不做过期判断：`ActivityState`（`proactive/`）负责解析，读取方按 `now` 判断
     * 「有没有过期」。没设过就是 `null`。
     */
    fun activity(personaId: String): String? = lock.withLock {
        personas.firstOrNull { it.id == personaId }?.activityJson
    }

    fun usage(personaId: String): UsageTotal = lock.withLock { usage[personaId] ?: UsageTotal() }

    fun usageAll(): Map<String, UsageTotal> = lock.withLock { LinkedHashMap(usage) }

    /**
     * 读某个人的主动记录；[today] 是**服务端本地自然日**（`yyyy-MM-dd`）。
     *
     * 跨日时只把计数当成 0（不立刻写盘）：没有真正开口的一天不该因为一次读就产生写操作。
     * `lastProactiveAt` 原样返回，冷却期不会在零点被清掉。
     */
    fun proactiveState(personaId: String, today: String): ProactiveState = lock.withLock {
        effectiveProactiveStateLocked(personaId, today)
    }

    /**
     * 落一次「真的主动开口了」：计数 +1、日期翻新、`lastProactiveAt=at`，**同一把存储锁内**
     * 更新内存并原子写 `proactive_state.json`。返回更新后的记录。
     */
    fun recordProactiveFired(personaId: String, at: Long, today: String): ProactiveState = lock.withLock {
        requirePersonaLocked(personaId)
        recordProactiveFiredLocked(personaId, at, today)
    }

    /**
     * 写入 / 清掉「他此刻在做什么」（`POST /v1/personas/{id}/activity` 的落库入口）。
     *
     * - [activityJson] 为 `null` 表示清掉（读到就是「没在忙」）；
     * - **同一把写锁内**改内存 + 原子重写 `personas.json`（与 `mood/presence/proactive_state`
     *   完全一致的做法：`*.tmp` → `ATOMIC_MOVE`，重启后读回同一份）；
     * - 顺手把 `updatedAt` 抬到新的时间戳：`GET /v1/state?since=` 靠 `updatedAt` 过滤人格，
     *   不抬的话客户端增量同步看不到活动变化。
     *
     * 参数校验只做「人格存在 + 长度上限」：结构归一化（枚举、分钟夹取）在路由层，
     * 存储层不猜调用方想表达什么。
     */
    fun updateActivity(personaId: String, activityJson: String?): Persona = lock.withLock {
        requirePersonaLocked(personaId)
        if (activityJson != null && activityJson.length > MAX_ACTIVITY_JSON_CHARS) {
            throw IllegalArgumentException("activityJson 最长 $MAX_ACTIVITY_JSON_CHARS 个字符")
        }
        val index = personas.indexOfFirst { it.id == personaId }
        val updated = personas[index].copy(
            activityJson = activityJson?.takeIf { it.isNotBlank() },
            updatedAt = nextTimestampLocked()
        )
        personas[index] = updated
        persistPersonasLocked()
        updated
    }

    /**
     * 新建人格。id 由调用方给（可空→生成），时间戳由存储层给；创建与落盘同锁。
     *
     * 路由层通过 [transform] 拿到 now，把请求字段拼成 [Persona]；这里再统一覆盖
     * `id/createdAt/updatedAt`，避免客户端伪造时间线。
     */
    fun createPersona(
        requestedId: String?,
        transform: (now: Long) -> Persona
    ): Persona = lock.withLock {
        val id = requestedId ?: newId()
        if (!isValidId(id)) throw IllegalArgumentException("非法的人格 id")
        if (personas.any { it.id == id }) throw IllegalArgumentException("人格 id 已存在：$id")
        val now = nextTimestampLocked()
        var persona = transform(now).copy(id = id, createdAt = now, updatedAt = now)
        if (persona.name.isBlank()) throw IllegalArgumentException("name 不能为空")
        if (persona.isDefault) {
            for (index in personas.indices) personas[index] = personas[index].copy(isDefault = false)
        }
        personas += persona
        persistPersonasLocked()
        persona
    }

    /**
     * 更新人格：读-改-写同一把锁。`transform` 收到当前值和 now，返回新值；核心 `id/createdAt`
     * 由存储层钉住，`updatedAt` 由存储层抬到 now（`/v1/state?since=` 靠它做增量）。
     */
    fun updatePersona(
        personaId: String,
        transform: (existing: Persona, now: Long) -> Persona
    ): Persona = lock.withLock {
        if (!isValidId(personaId)) throw IllegalArgumentException("非法的人格 id")
        val index = personas.indexOfFirst { it.id == personaId }
        if (index < 0) throw StorePersonaNotFoundException(personaId)
        val now = nextTimestampLocked()
        val updated = transform(personas[index], now).copy(
            id = personaId,
            createdAt = personas[index].createdAt,
            updatedAt = now
        )
        if (updated.name.isBlank()) throw IllegalArgumentException("name 不能为空")
        if (updated.isDefault) {
            for (i in personas.indices) {
                if (i != index) personas[i] = personas[i].copy(isDefault = false)
            }
        }
        personas[index] = updated
        persistPersonasLocked()
        updated
    }

    /**
     * 删除人格并级联清理：
     * - 该人格的会话目录（消息块 + 块表）与 `sessions.json` 行；
     * - 记忆、活动（活动就在 personas.json 里，随人格一起消失）、主动计数；
     * - 心情/在场（否则会留下无主记录）。
     *
     * **媒体文件不删**（`media/` 由客户端 GC，契约 §3.6）；usage / API 配置是服务端资源，
     * 也不跟着人格走。返回是否真的存在过。
     */
    fun deletePersona(personaId: String): Boolean = lock.withLock {
        if (!isValidId(personaId)) return@withLock false
        val index = personas.indexOfFirst { it.id == personaId }
        if (index < 0) return@withLock false
        personas.removeAt(index)

        val doomedSessions = sessions.values.filter { it.personaId == personaId }.map { it.id }
        for (sessionId in doomedSessions) {
            sessions.remove(sessionId)
            blockCache.remove(sessionId)
            messageIndexCache.remove(sessionId)
            deleteRecursively(sessionDir(sessionId))
        }
        moods.remove(personaId)
        presence.remove(personaId)
        proactiveStates.remove(personaId)
        memories.entries.removeAll { it.value.personaId == personaId }

        persistPersonasLocked()
        persistSessionsLocked()
        persistMoodsLocked()
        persistPresenceLocked()
        persistProactiveStateLocked()
        persistMemoriesLocked()
        true
    }

    fun apiConfigs(): List<ApiConfig> = lock.withLock { apiConfigs.values.toList() }

    fun apiKey(configId: String): String? = lock.withLock { readSecretsLocked()[configId] }

    fun settings(): JsonValue.Obj = lock.withLock { settings }

    /**
     * 对外可见的设置：只保留白名单键。
     *
     * 备份/HTTP 都走这里。即便有人在磁盘上手工塞了 `apiKey`，它也不会出现在响应里；
     * 写路径也只接受白名单（见 [mergeSettings]）。
     */
    fun publicSettings(): JsonValue.Obj = lock.withLock { publicSettingsLocked() }

    // ================================================================== 读：记忆 / 预设 / push

    /**
     * 某个人格的记忆，按 `updatedAt` 倒序。
     *
     * @param since 只返回 `updatedAt > since` 的记忆（与 `/v1/state` 的增量语义一致）
     */
    fun memories(personaId: String, limit: Int = 200, since: Long = 0L): List<Memory> = lock.withLock {
        memories.values
            .filter { it.personaId == personaId && (since <= 0 || it.updatedAt > since) }
            .sortedWith(compareByDescending<Memory> { it.updatedAt }.thenByDescending { it.createdAt })
            .take(limit.coerceAtLeast(0))
    }

    fun memory(memoryId: String): Memory? = lock.withLock { memories[memoryId] }

    fun presets(): List<Preset> = lock.withLock {
        presets.values.sortedWith(compareByDescending<Preset> { it.updatedAt }.thenBy { it.name })
    }

    fun preset(presetId: String): Preset? = lock.withLock { presets[presetId] }

    fun pushTokens(): List<PushRegistration> = lock.withLock { pushTokens.values.toList() }

    // ================================================================== 写：记忆 / 预设 / push

    /**
     * 新建或更新一条记忆。`id == null` 新建；给了 id 就必须已经存在（路由层把不存在翻成 404）。
     *
     * 整个读-改-写都在存储锁内，路由层不要先 `memory()` 再拼一个完整对象写回（会丢并发更新）。
     */
    fun saveMemory(
        personaId: String,
        memoryId: String?,
        transform: (existing: Memory?, now: Long) -> Memory
    ): Memory = lock.withLock {
        requirePersonaLocked(personaId)
        val now = nextTimestampLocked()
        if (memoryId == null) {
            val memory = transform(null, now).copy(
                id = newId(),
                personaId = personaId,
                createdAt = now,
                updatedAt = now
            )
            memories[memory.id] = memory
            persistMemoriesLocked()
            return@withLock memory
        }
        if (!isValidId(memoryId)) throw IllegalArgumentException("非法的记忆 id")
        val existing = memories[memoryId] ?: throw StoreMemoryNotFoundException(memoryId)
        if (existing.personaId != personaId) throw StoreMemoryNotFoundException(memoryId)
        val updated = transform(existing, now).copy(
            id = existing.id,
            personaId = existing.personaId,
            createdAt = existing.createdAt,
            updatedAt = now
        )
        memories[updated.id] = updated
        persistMemoriesLocked()
        updated
    }

    fun deleteMemory(memoryId: String): Boolean = lock.withLock {
        if (!isValidId(memoryId)) return@withLock false
        if (memories.remove(memoryId) == null) return@withLock false
        persistMemoriesLocked()
        true
    }

    fun savePreset(
        presetId: String?,
        transform: (existing: Preset?, now: Long) -> Preset
    ): Preset = lock.withLock {
        val now = nextTimestampLocked()
        if (presetId == null) {
            val preset = transform(null, now).copy(
                id = newId(),
                createdAt = now,
                updatedAt = now
            )
            presets[preset.id] = preset
            persistPresetsLocked()
            return@withLock preset
        }
        if (!isValidId(presetId)) throw IllegalArgumentException("非法的预设 id")
        val existing = presets[presetId] ?: throw StorePresetNotFoundException(presetId)
        val updated = transform(existing, now).copy(
            id = existing.id,
            createdAt = existing.createdAt,
            updatedAt = now
        )
        presets[updated.id] = updated
        persistPresetsLocked()
        updated
    }

    fun deletePreset(presetId: String): Boolean = lock.withLock {
        if (!isValidId(presetId)) return@withLock false
        if (presets.remove(presetId) == null) return@withLock false
        persistPresetsLocked()
        true
    }

    /**
     * 注册/更新推送 token（同一 `platform + token` 只留一条）。幂等：重复注册只刷新 `updatedAt`。
     * 返回 `true` 表示新建，`false` 表示更新了既有注册。
     */
    fun registerPushToken(platform: String, token: String, bundleId: String?): Boolean = lock.withLock {
        val key = pushTokenKey(platform, token)
        val now = nextTimestampLocked()
        val existing = pushTokens[key]
        pushTokens[key] = PushRegistration(
            platform = platform,
            token = token,
            bundleId = bundleId ?: existing?.bundleId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        persistPushTokensLocked()
        existing == null
    }

    /** 注销 token；幂等（本来没有也返回 false，不报错）。platform 为 null 时删掉这个 token 的所有平台。 */
    fun unregisterPushToken(token: String, platform: String? = null): Boolean = lock.withLock {
        val keys = pushTokens.entries
            .filter { it.value.token == token && (platform == null || it.value.platform == platform) }
            .map { it.key }
        if (keys.isEmpty()) return@withLock false
        keys.forEach { pushTokens.remove(it) }
        persistPushTokensLocked()
        true
    }

    /** 合并设置：只写白名单键；未知键（包括 `apiKey` / `secrets`）静默忽略，绝不落盘。 */
    fun mergeSettings(values: Map<String, JsonValue>): JsonValue.Obj = lock.withLock {
        // 从白名单开始重建：即使旧盘上手工塞过 apiKey，下一次设置写入也会把它清掉。
        val updated = LinkedHashMap<String, JsonValue>()
        for ((key, value) in settings.fields) {
            if (key in SETTINGS_WHITELIST) updated[key] = value
        }
        for ((key, value) in values) {
            if (key in SETTINGS_WHITELIST) updated[key] = value
        }
        settings = JsonValue.Obj(updated)
        writeJsonLocked(root.resolve(SETTINGS_FILE), StoreJson.obj("settings" to settings))
        publicSettingsLocked()
    }

    // ================================================================== 读：块与消息

    /** 一个会话的块表（按 `index` 升序；允许编号空洞）。 */
    fun blocks(sessionId: String): List<BlockMeta> = lock.withLock {
        requireSessionLocked(sessionId)
        blocksForLocked(sessionId).toList()
    }

    /** 最新块编号；没有消息时 -1。 */
    fun lastBlockIndex(sessionId: String): Int = lock.withLock {
        requireSessionLocked(sessionId)
        blocksForLocked(sessionId).lastOrNull()?.index ?: -1
    }

    /** 严格小于 [beforeBlock] 的最近一块；没有则 -1。 */
    fun previousBlockIndex(sessionId: String, beforeBlock: Int): Int = lock.withLock {
        requireSessionLocked(sessionId)
        blocksForLocked(sessionId).lastOrNull { it.index < beforeBlock }?.index ?: -1
    }

    /** 严格大于 [afterBlock] 的最近一块；没有则 -1。 */
    fun nextBlockIndex(sessionId: String, afterBlock: Int): Int = lock.withLock {
        requireSessionLocked(sessionId)
        blocksForLocked(sessionId).firstOrNull { it.index > afterBlock }?.index ?: -1
    }

    /** 读一整块（按写入顺序，即 `(createdAt, id)` 顺序）。 */
    fun blockMessages(sessionId: String, blockIndex: Int): List<Message> = lock.withLock {
        requireSessionLocked(sessionId)
        readBlockLocked(sessionId, blockIndex)
    }

    /**
     * 最新 [limit] 条消息（跨块向前取，按时间升序返回），生成器用它当上下文。
     * 内存占用与 [limit] 相关，与历史长度无关。
     */
    fun latestMessages(sessionId: String, limit: Int = BLOCK_SIZE): List<Message> = lock.withLock {
        requireSessionLocked(sessionId)
        if (limit <= 0) return@withLock emptyList()
        val blocks = blocksForLocked(sessionId)
        val collected = ArrayList<Message>()
        for (block in blocks.asReversed()) {
            val messages = readBlockLocked(sessionId, block.index)
            collected.addAll(0, messages)
            if (collected.size >= limit) break
        }
        if (collected.size > limit) collected.subList(collected.size - limit, collected.size).toList() else collected
    }

    fun findMessage(sessionId: String, messageId: String): Message? = lock.withLock {
        requireSessionLocked(sessionId)
        for (block in blocksForLocked(sessionId).asReversed()) {
            readBlockLocked(sessionId, block.index).firstOrNull { it.id == messageId }?.let { return@withLock it }
        }
        null
    }

    /** 幂等查找：同一个 `clientMessageId` 已经落过的用户消息。 */
    fun findUserMessageByClientMessageId(sessionId: String, clientMessageId: String): Message? = lock.withLock {
        requireSessionLocked(sessionId)
        val messageId = messageIndexLocked(sessionId).clientMessageIds[clientMessageId] ?: return@withLock null
        findMessageLocked(sessionId, messageId)
    }

    /** 某条用户消息已经生成的 assistant 回复（重复 POST 时重放 `done`，不再调生成器）。 */
    fun findAssistantReply(sessionId: String, userMessageId: String): Message? = lock.withLock {
        requireSessionLocked(sessionId)
        val messageId = messageIndexLocked(sessionId).replies[userMessageId] ?: return@withLock null
        findMessageLocked(sessionId, messageId)
    }

    /**
     * `GET /v1/state` 的快照。
     *
     * `since <= 0`：首次同步，每会话只返回**最新一块**（与 §3.2 的「初始加载 = 最新一块」一致），
     * 更早的历史用 `GET /v1/sessions/{id}/messages` 分页拿。
     * `since > 0`：返回 `createdAt > since` 的消息，从最新块向前扫，最多 [MAX_STATE_MESSAGES] 条
     * （超出时 `messagesTruncated = true`）。
     */
    fun snapshot(
        since: Long,
        personaId: String? = null,
        maxMessages: Int = MAX_STATE_MESSAGES
    ): StateSnapshot = lock.withLock {
        // 契约 §3.1：`since` 过滤 `updatedAt > since` 的人格/会话，与 `createdAt > since` 的消息。
        // 心情/在场不做 since 过滤：客户端每次都需要「当前值」，而不是「这次变了的值」。
        val filteredPersonas = personas.filter {
            (personaId == null || it.id == personaId) && (since <= 0 || it.updatedAt > since)
        }
        val filteredSessions = sessions.values.filter {
            (personaId == null || it.personaId == personaId) && (since <= 0 || it.updatedAt > since)
        }
        val messages = ArrayList<Message>()
        var truncated = false
        for (session in filteredSessions) {
            if (messages.size >= maxMessages) {
                truncated = true
                break
            }
            val blocks = blocksForLocked(session.id)
            if (blocks.isEmpty()) continue
            if (since <= 0) {
                messages.addAll(readBlockLocked(session.id, blocks.last().index))
                if (blocks.size > 1) truncated = true
                if (messages.size > maxMessages) {
                    while (messages.size > maxMessages) messages.removeAt(messages.size - 1)
                    truncated = true
                }
            } else {
                val perSession = ArrayList<Message>()
                for (block in blocks.asReversed()) {
                    if (block.lastAt <= since) break
                    val older = readBlockLocked(session.id, block.index)
                        .filter { it.createdAt > since }
                    perSession.addAll(0, older)
                    if (messages.size + perSession.size >= maxMessages) {
                        truncated = true
                        break
                    }
                }
                messages.addAll(perSession)
            }
        }
        val targetPersona = personaId ?: defaultPersonaIdLocked()
        StateSnapshot(
            serverTime = clock(),
            personas = filteredPersonas,
            sessions = filteredSessions,
            messages = messages.sortedWith(compareBy({ it.createdAt }, { it.id })),
            mood = moods[targetPersona],
            presence = presence[targetPersona],
            serverCursor = cursor,
            messagesTruncated = truncated
        )
    }

    // ================================================================== 写：会话 / 消息

    /**
     * 「一人格一会话」的建会话入口（`POST /v1/sessions`）。
     *
     * 已存在该人格的会话就返回既有会话（不覆盖 title，避免客户端一次重试把用户改过的标题写回默认）。
     * 查找与创建在同一把锁内，两个并发 POST 不会建出两条。
     *
     * @return `created to session`
     */
    fun ensureSessionForPersona(personaId: String, title: String): Pair<Boolean, Session> = lock.withLock {
        requirePersonaLocked(personaId)
        sessions.values.firstOrNull { it.personaId == personaId }?.let { return@withLock false to it }
        val now = nextTimestampLocked()
        val session = Session(
            id = newId(),
            personaId = personaId,
            title = title,
            createdAt = now,
            updatedAt = now,
            lastMessageAt = null,
            lastBlockIndex = -1,
            unread = 0,
            messageCount = 0
        )
        sessions[session.id] = session
        persistSessionsLocked()
        true to session
    }

    /** 建会话（已存在则原样返回，方便测试与后续「建会话」接口复用）。 */
    fun ensureSession(personaId: String, title: String, sessionId: String = newId()): Session = lock.withLock {
        if (!isValidId(sessionId)) throw IllegalArgumentException("非法的会话 id")
        sessions[sessionId]?.let { return@withLock it }
        val persona = personas.firstOrNull { it.id == personaId }
            ?: throw IllegalArgumentException("人格不存在：$personaId")
        val now = nextTimestampLocked()
        val session = Session(
            id = sessionId,
            personaId = persona.id,
            title = title,
            createdAt = now,
            updatedAt = now,
            lastMessageAt = null,
            lastBlockIndex = -1,
            unread = 0,
            messageCount = 0
        )
        sessions[sessionId] = session
        persistSessionsLocked()
        session
    }

    /**
     * 追加一条消息（块规则见类注释）。
     *
     * @param clientMessageId 用户消息的幂等键（assistant 消息为 null）。
     * @param replyTo assistant 消息指回的用户消息 id。
     * @param promptTokens / completionTokens 那一次生成的用量（重放 `done` 时用）。
     */
    fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        attachments: List<String> = emptyList(),
        source: String = MessageSource.CHAT,
        clientMessageId: String? = null,
        replyTo: String? = null,
        readAt: Long? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null
    ): Message = lock.withLock {
        if (!MessageRole.isValid(role)) throw IllegalArgumentException("非法的角色：$role")
        val session = requireSessionLocked(sessionId)
        val blocks = blocksForLocked(sessionId)
        var block = blocks.lastOrNull()
        if (block == null || block.count >= BLOCK_SIZE) {
            val nextIndex = (block?.index ?: -1) + 1
            block = BlockMeta(
                index = nextIndex,
                firstMessageId = "",
                lastMessageId = "",
                firstAt = 0,
                lastAt = 0,
                count = 0
            )
            blocks.add(block)
        }
        val createdAt = nextTimestampLocked()
        val message = Message(
            id = newId(),
            sessionId = sessionId,
            blockIndex = block.index,
            role = role,
            content = content,
            createdAt = createdAt,
            readAt = readAt,
            attachments = attachments,
            source = source,
            clientMessageId = clientMessageId,
            replyTo = replyTo,
            promptTokens = promptTokens,
            completionTokens = completionTokens
        )
        appendLineLocked(blockFile(sessionId, block.index), StoreJson.stringify(message.toJson()))

        val position = blocks.indexOfFirst { it.index == block.index }
        blocks[position] = block.copy(
            firstMessageId = if (block.count == 0) message.id else block.firstMessageId,
            lastMessageId = message.id,
            firstAt = if (block.count == 0) createdAt else block.firstAt,
            lastAt = createdAt,
            count = block.count + 1
        )
        persistBlockIndexLocked(sessionId, blocks)

        val unreadDelta = if (role == MessageRole.ASSISTANT && readAt == null) 1 else 0
        val updated = session.copy(
            lastMessageAt = createdAt,
            lastBlockIndex = block.index,
            updatedAt = createdAt,
            unread = session.unread + unreadDelta,
            messageCount = session.messageCount + 1
        )
        sessions[sessionId] = updated
        persistSessionsLocked()
        indexForAppendLocked(sessionId, message)

        appendEventLocked(
            type = EventType.MESSAGE_CREATED,
            at = createdAt,
            sessionId = sessionId,
            personaId = session.personaId,
            payload = linkedMapOf("message" to message.toJson())
        )
        message
    }

    /**
     * 主动消息的**原子落库入口**：每日上限 / 冷却复查 + 追加消息 + 更新主动记录，
     * 全部在同一把存储锁内完成。
     *
     * 为什么不能「路由先读一次、再 append、再 record」：
     * - 两个并发 tick（两个 `ProactiveTicker` 实例、或调试接口与定时器撞在一起）会同时读到
     *   `count=0`，各发一条；这里在锁内复查，后到的那个返回 `null`；
     * - 消息与计数必须一起可见：只写消息不写计数，重启后等于没发过（本次修复的缺陷）。
     *
     * @param dailyCap 今天允许的主动次数（引擎已经算过，包含心情修正）
     * @param minGapMs 距上次主动开口至少多久（冷却期）；`<= 0` 表示不检查
     * @return 真正落库的消息；被上限/冷却挡住时返回 `null`
     */
    fun appendProactiveMessageIfAllowed(
        sessionId: String,
        personaId: String,
        content: String,
        at: Long,
        today: String,
        dailyCap: Int,
        minGapMs: Long
    ): Message? = lock.withLock {
        requirePersonaLocked(personaId)
        val state = effectiveProactiveStateLocked(personaId, today)
        if (dailyCap <= 0 || state.proactiveCountToday >= dailyCap) return@withLock null
        if (minGapMs > 0 && state.lastProactiveAt > 0 && at - state.lastProactiveAt < minGapMs) {
            return@withLock null
        }
        // ReentrantLock 可重入：appendMessage 内部再取一次锁不会死锁，但整段仍是同一个临界区
        // （消息、会话、事件、主动计数在一次锁持有期间一起落盘）。
        val message = appendMessage(
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = content,
            source = MessageSource.PROACTIVE
        )
        recordProactiveFiredLocked(personaId, at, today)
        message
    }

    /**
     * 硬删除一条消息：重算块边界；块空了就删块文件并在块表里留空洞（不重平衡）。
     * 返回是否真的删掉了。
     */
    fun deleteMessage(sessionId: String, messageId: String): Boolean = lock.withLock {
        val session = requireSessionLocked(sessionId)
        val blocks = blocksForLocked(sessionId)
        for (block in blocks.asReversed()) {
            val messages = readBlockLocked(sessionId, block.index)
            val deleted = messages.firstOrNull { it.id == messageId } ?: continue
            val remaining = messages.filterNot { it.id == messageId }
            if (remaining.isEmpty()) {
                Files.deleteIfExists(blockFile(sessionId, block.index))
                blocks.removeAll { it.index == block.index }
            } else {
                rewriteBlockLocked(sessionId, block.index, remaining)
                val position = blocks.indexOfFirst { it.index == block.index }
                blocks[position] = block.copy(
                    firstMessageId = remaining.first().id,
                    lastMessageId = remaining.last().id,
                    firstAt = remaining.first().createdAt,
                    lastAt = remaining.last().createdAt,
                    count = remaining.size
                )
            }
            persistBlockIndexLocked(sessionId, blocks)

            val wasUnreadAssistant = deleted.role == MessageRole.ASSISTANT && deleted.readAt == null
            val lastBlock = blocks.lastOrNull()
            val updated = session.copy(
                updatedAt = nextTimestampLocked(),
                lastMessageAt = lastBlock?.lastAt,
                lastBlockIndex = lastBlock?.index ?: -1,
                unread = if (wasUnreadAssistant) maxOf(0, session.unread - 1) else session.unread,
                messageCount = maxOf(0, session.messageCount - 1)
            )
            sessions[sessionId] = updated
            persistSessionsLocked()
            indexForDeleteLocked(sessionId, deleted)
            return@withLock true
        }
        false
    }

    /**
     * 把会话里的 assistant 消息标成已读并清零未读计数；返回标记了几条。
     *
     * 兼容旧调用（全部标记）。HTTP 路由用 [markSessionReadUpTo] 拿 `readAt`。
     */
    fun markSessionRead(sessionId: String): Int = markSessionReadUpTo(sessionId, null).marked

    /**
     * `POST /v1/sessions/{id}/read` 的存储入口。
     *
     * @param upTo 只把 `createdAt <= upTo` 的未读 assistant 消息标已读（null = 全部）。
     *   无论标了几条，会话的 `unread` 都归零：客户端说「我看到了这里」，契约就是
     *   `unread = 0`；`upTo` 只决定哪些消息的 `readAt` 被补上。
     * @return 标记条数、用于响应的 `readAt`（服务端时钟）、更新后的会话
     */
    fun markSessionReadUpTo(sessionId: String, upTo: Long?): SessionReadResult = lock.withLock {
        val session = requireSessionLocked(sessionId)
        var marked = 0
        val readAt = clock()
        val blocks = blocksForLocked(sessionId)
        for (block in blocks.toList()) {
            val messages = readBlockLocked(sessionId, block.index)
            var changed = false
            val updatedMessages = messages.map { message ->
                if (message.role == MessageRole.ASSISTANT &&
                    message.readAt == null &&
                    (upTo == null || message.createdAt <= upTo)
                ) {
                    marked += 1
                    changed = true
                    message.copy(readAt = readAt)
                } else {
                    message
                }
            }
            if (changed) rewriteBlockLocked(sessionId, block.index, updatedMessages)
        }
        val updated = session.copy(unread = 0, updatedAt = nextTimestampLocked())
        sessions[sessionId] = updated
        persistSessionsLocked()
        SessionReadResult(marked = marked, readAt = readAt, session = updated)
    }

    // ================================================================== 写：心情 / 在场 / 用量 / 配置

    /** 改心情；值真的变了才落盘并发 `mood.changed` 事件。 */
    fun setMood(personaId: String, label: String, intensity: Int): Mood = lock.withLock {
        requirePersonaLocked(personaId)
        val clamped = intensity.coerceIn(0, 5)
        val previous = moods[personaId]
        if (previous != null && previous.label == label && previous.intensity == clamped) return@withLock previous
        val now = nextTimestampLocked()
        val mood = Mood(personaId, label, clamped, now)
        moods[personaId] = mood
        persistMoodsLocked()
        appendEventLocked(
            type = EventType.MOOD_CHANGED,
            at = now,
            sessionId = null,
            personaId = personaId,
            payload = linkedMapOf("mood" to mood.toJson())
        )
        mood
    }

    /** 改在场状态；值真的变了才落盘并发 `presence.changed` 事件。 */
    fun setPresence(personaId: String, kind: String, detail: String = ""): Presence = lock.withLock {
        requirePersonaLocked(personaId)
        val previous = presence[personaId]
        if (previous != null && previous.kind == kind && previous.detail == detail) return@withLock previous
        val now = nextTimestampLocked()
        val next = Presence(personaId, kind, detail, now)
        presence[personaId] = next
        persistPresenceLocked()
        appendEventLocked(
            type = EventType.PRESENCE_CHANGED,
            at = now,
            sessionId = null,
            personaId = personaId,
            payload = linkedMapOf("presence" to next.toJson())
        )
        next
    }

    fun addUsage(personaId: String, promptTokens: Int, completionTokens: Int) = lock.withLock {
        val current = usage[personaId] ?: UsageTotal()
        usage[personaId] = current.plus(
            UsageTotal(
                promptTokens = promptTokens.toLong().coerceAtLeast(0),
                completionTokens = completionTokens.toLong().coerceAtLeast(0),
                replies = 1
            )
        )
        persistUsageLocked()
    }

    /** 落一条 API 配置（只有元数据；key 用 [setApiKey] 单独存）。 */
    fun putApiConfig(config: ApiConfig): ApiConfig = lock.withLock {
        val now = nextTimestampLocked()
        val stored = if (apiConfigs.containsKey(config.id)) {
            config.copy(createdAt = apiConfigs.getValue(config.id).createdAt, updatedAt = now)
        } else {
            config.copy(createdAt = now, updatedAt = now)
        }
        apiConfigs[stored.id] = stored
        persistApiConfigsLocked()
        stored
    }

    /**
     * 把明文 key 写进 `secrets.json`（0600）。**永远不要**把它放进 `api_configs.json` 或任何响应。
     */
    fun setApiKey(configId: String, key: String) = lock.withLock {
        val secrets = readSecretsLocked().toMutableMap()
        if (key.isEmpty()) secrets.remove(configId) else secrets[configId] = key
        persistSecretsLocked(secrets)
    }

    fun setSetting(key: String, value: JsonValue) = lock.withLock {
        val updated = LinkedHashMap(settings.fields)
        updated[key] = value
        settings = JsonValue.Obj(updated)
        writeJsonLocked(root.resolve(SETTINGS_FILE), StoreJson.obj("settings" to settings))
    }

    fun deleteSession(sessionId: String): Boolean = lock.withLock {
        if (sessions.remove(sessionId) == null) return@withLock false
        blockCache.remove(sessionId)
        messageIndexCache.remove(sessionId)
        deleteRecursively(sessionDir(sessionId))
        persistSessionsLocked()
        true
    }

    // ================================================================== 备份

    /**
     * 导出备份 v5（`GET /v1/backup`）。
     *
     * 只导服务端权威模式持有的内容：人格、会话、消息、记忆、预设、设置。**不含**：
     * `secrets.json` / API Key / 搜索密钥（本来就不在人格/设置里）；媒体二进制；usage；
     * api_configs 的密钥。`apiKeysOmitted: true` 是给读文件的人看的声明。
     */
    fun exportBackup(appVersion: String, exportedAt: Long): JsonValue.Obj = lock.withLock {
        val allMessages = allMessagesLocked()
        StoreJson.obj(
            "format" to StoreJson.str(BACKUP_FORMAT_NAME),
            "formatVersion" to StoreJson.num(BACKUP_FORMAT_VERSION),
            "appVersion" to StoreJson.str(appVersion),
            "exportedAt" to StoreJson.num(exportedAt),
            "apiKeysOmitted" to StoreJson.bool(true),
            "personas" to StoreJson.arr(personas.map { it.toJson() }),
            "sessions" to StoreJson.arr(sessions.values.map { it.toJson() }),
            "messages" to StoreJson.arr(allMessages.map { it.toJson() }),
            "memories" to StoreJson.arr(
                memories.values.sortedBy { it.createdAt }.map { it.toJson() }
            ),
            "presets" to StoreJson.arr(presets.values.map { it.toJson() }),
            "settings" to publicSettingsLocked()
        )
    }

    /**
     * 导入备份（`POST /v1/backup`）。
     *
     * 事务性：先把所有段解析成候选内存模型、校验 id/引用/唯一性；**全部通过之后**才动
     * 内存和磁盘。不是数据库事务（写多个文件时进程被杀仍可能留下部分文件），但请求级
     * 校验失败绝不会写一半。
     *
     * merge：按 id 更新/新增，未在备份里的内容保留；
     * replace：清空人格/会话/消息/记忆/预设/设置（以及随之孤儿化的心情、在场、主动计数）
     * 后导入，usage/api_configs/secrets/push_tokens 不在备份里，因此保留。
     */
    fun importBackup(mode: String, data: JsonValue.Obj, now: Long): BackupImportResult = lock.withLock {
        if (mode != "merge" && mode != "replace") {
            throw BackupValidationException("mode 只能是 merge 或 replace")
        }
        val parsed = try {
            parseBackupLocked(data, now)
        } catch (corruption: StoreCorruptionException) {
            throw BackupValidationException(corruption.message ?: "备份结构不合法")
        } catch (invalid: JsonParseException) {
            throw BackupValidationException("备份里有坏 JSON：${invalid.message.orEmpty()}")
        }

        val finalPersonas = LinkedHashMap<String, Persona>()
        if (mode == "merge") personas.forEach { finalPersonas[it.id] = it }
        for (persona in parsed.personas) finalPersonas[persona.id] = persona

        val finalSessions = LinkedHashMap<String, Session>()
        if (mode == "merge") sessions.forEach { (id, session) -> finalSessions[id] = session }
        for (session in parsed.sessions) finalSessions[session.id] = session
        // 契约：一人格一会话。导入数据里出现两条就拒绝，而不是悄悄造出客户端无法处理的形状。
        val duplicatePersona = finalSessions.values
            .groupBy { it.personaId }
            .entries
            .firstOrNull { it.value.size > 1 }
        if (duplicatePersona != null) {
            throw BackupValidationException("人格 ${duplicatePersona.key} 只能有一个会话，备份里有 ${duplicatePersona.value.size} 个")
        }

        val existingMessages = allMessagesLocked()
        val finalMessages = LinkedHashMap<String, Message>()
        if (mode == "merge") existingMessages.forEach { finalMessages[it.id] = it }
        for (message in parsed.messages) finalMessages[message.id] = message

        val finalMemories = LinkedHashMap<String, Memory>()
        if (mode == "merge") memories.forEach { (id, memory) -> finalMemories[id] = memory }
        for (memory in parsed.memories) finalMemories[memory.id] = memory

        val finalPresets = LinkedHashMap<String, Preset>()
        if (mode == "merge") presets.forEach { (id, preset) -> finalPresets[id] = preset }
        for (preset in parsed.presets) finalPresets[preset.id] = preset

        // 引用校验：会话的 persona、消息的 session 都必须在最终集合里。
        for (session in finalSessions.values) {
            if (finalPersonas[session.personaId] == null) {
                throw BackupValidationException("会话 ${session.id} 引用了不存在的人格 ${session.personaId}")
            }
        }
        for (message in finalMessages.values) {
            if (finalSessions[message.sessionId] == null) {
                throw BackupValidationException("消息 ${message.id} 引用了不存在的会话 ${message.sessionId}")
            }
        }
        for (memory in parsed.memories) {
            if (finalPersonas[memory.personaId] == null) {
                throw BackupValidationException("记忆 ${memory.id} 引用了不存在的人格 ${memory.personaId}")
            }
        }
        val activePersonaId = parsed.settings?.fields?.get("activePersonaId")
        if (activePersonaId is JsonValue.Str && finalPersonas[activePersonaId.value] == null) {
            throw BackupValidationException("settings.activePersonaId 引用了不存在的人格 ${activePersonaId.value}")
        }

        // 受影响会话：导入的会话 + 导入消息落在的会话 + 被替换消息原来的会话。
        val affectedSessions = LinkedHashSet<String>()
        if (mode == "replace") {
            affectedSessions += finalSessions.keys
        } else {
            parsed.sessions.forEach { affectedSessions += it.id }
            parsed.messages.forEach { affectedSessions += it.sessionId }
            parsed.messages.forEach { message ->
                existingMessages.firstOrNull { it.id == message.id }?.let { affectedSessions += it.sessionId }
            }
        }

        // 块编号统一按最终消息顺序重排：备份导入不信任外部 blockIndex，避免出现 > 200 条的块。
        for (sessionId in affectedSessions) {
            val ordered = finalMessages.values
                .filter { it.sessionId == sessionId }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
            ordered.forEachIndexed { index, message ->
                finalMessages[message.id] = message.copy(blockIndex = index / BLOCK_SIZE)
            }
            val existing = finalSessions.getValue(sessionId)
            val last = ordered.lastOrNull()
            val unread = ordered.count { it.role == MessageRole.ASSISTANT && it.readAt == null }
            finalSessions[sessionId] = existing.copy(
                lastMessageAt = last?.createdAt,
                lastBlockIndex = if (ordered.isEmpty()) -1 else (ordered.size - 1) / BLOCK_SIZE,
                unread = unread,
                messageCount = ordered.size.toLong(),
                updatedAt = maxOf(existing.updatedAt, last?.createdAt ?: 0L)
            )
        }

        // ---- 校验全部通过，开始改内存/落盘 ----
        if (mode == "replace") {
            deleteRecursively(sessionsDir)
            Files.createDirectories(sessionsDir)
            blockCache.clear()
            messageIndexCache.clear()
            moods.clear()
            presence.clear()
            proactiveStates.clear()
        } else {
            for (sessionId in affectedSessions) {
                deleteRecursively(sessionDir(sessionId))
                blockCache.remove(sessionId)
                messageIndexCache.remove(sessionId)
            }
        }

        personas.clear()
        personas.addAll(finalPersonas.values)
        sessions.clear()
        sessions.putAll(finalSessions)
        memories.clear()
        memories.putAll(finalMemories)
        presets.clear()
        presets.putAll(finalPresets)

        // 合并设置的语义：备份里给了 settings 才改；只接白名单键。
        if (parsed.settings != null) {
            val merged = LinkedHashMap<String, JsonValue>()
            if (mode == "merge") {
                for ((key, value) in settings.fields) {
                    if (key in SETTINGS_WHITELIST) merged[key] = value
                }
            }
            for ((key, value) in parsed.settings.fields) {
                if (key in SETTINGS_WHITELIST) merged[key] = value
            }
            settings = JsonValue.Obj(merged)
        } else if (mode == "replace") {
            settings = JsonValue.Obj()
        }

        // 只重写受影响会话的块文件；其它会话的磁盘内容原样不动。
        for (sessionId in affectedSessions) {
            val messages = finalMessages.values
                .filter { it.sessionId == sessionId }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
            val blocks = messages.groupBy { it.blockIndex }.toSortedMap()
            val metas = ArrayList<BlockMeta>()
            for ((index, blockMessages) in blocks) {
                rewriteBlockLocked(sessionId, index, blockMessages)
                metas += BlockMeta(
                    index = index,
                    firstMessageId = blockMessages.first().id,
                    lastMessageId = blockMessages.last().id,
                    firstAt = blockMessages.first().createdAt,
                    lastAt = blockMessages.last().createdAt,
                    count = blockMessages.size
                )
            }
            blockCache[sessionId] = metas.toMutableList()
            if (metas.isEmpty()) {
                persistBlockIndexLocked(sessionId, emptyList())
            } else {
                persistBlockIndexLocked(sessionId, metas)
            }
        }

        persistPersonasLocked()
        persistSessionsLocked()
        persistMoodsLocked()
        persistPresenceLocked()
        persistProactiveStateLocked()
        persistMemoriesLocked()
        persistPresetsLocked()
        writeJsonLocked(root.resolve(SETTINGS_FILE), StoreJson.obj("settings" to settings))
        lastTimestamp = maxOf(lastTimestamp, computeLastTimestampLocked())
        for (memory in memories.values) lastTimestamp = maxOf(lastTimestamp, memory.updatedAt)
        for (preset in presets.values) lastTimestamp = maxOf(lastTimestamp, preset.updatedAt)

        BackupImportResult(
            mode = mode,
            personas = parsed.personas.size,
            sessions = parsed.sessions.size,
            messages = parsed.messages.size,
            memories = parsed.memories.size,
            presets = parsed.presets.size,
            settings = parsed.settings != null
        )
    }

    // ================================================================== 事件（增量游标）

    /** 游标大于 [since] 的事件（最多 [limit] 条），同步读。 */
    fun eventsSince(since: Long, limit: Int = 200): List<StoredEvent> = lock.withLock {
        eventsSinceLocked(since, limit)
    }

    /**
     * 长轮询：有事件立即返回；否则最多等 [timeoutMs]（毫秒）。
     *
     * 实现是「事件条件变量 + 250ms 兜底轮询」：写事件时 `notifyAll` 唤醒等待者，但等待者**不持锁**
     * 阻塞，所以不会和写锁形成交叉死锁；250ms 的兜底上限保证「通知早于 wait」这种丢唤醒最多晚
     * 250ms，而不是等满整个超时。
     *
     * 注意：等待期间会占住一个 HTTP worker（`AgentdServer.WORKER_THREADS` 个固定线程），
     * 生产上长轮询/SSE 应该移出工作池 —— 这是本阶段的已知限制，写在 README 里。
     */
    fun awaitEvents(since: Long, timeoutMs: Long, limit: Int = 200): EventWaitResult {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000
        while (true) {
            val page = lock.withLock { eventsSinceLocked(since, limit) }
            if (page.isNotEmpty()) return EventWaitResult(page, page.last().cursor)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return EventWaitResult(emptyList(), since)
            val waitMs = minOf(remaining / 1_000_000, 250L).coerceAtLeast(1L)
            try {
                synchronized(eventMonitor) { eventMonitor.wait(waitMs) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return EventWaitResult(emptyList(), since)
            }
        }
    }

    // ================================================================== 内部：备份解析

    private class ParsedBackup(
        val personas: List<Persona>,
        val sessions: List<Session>,
        val messages: List<Message>,
        val memories: List<Memory>,
        val presets: List<Preset>,
        val settings: JsonValue.Obj?
    )

    private fun parseBackupLocked(data: JsonValue.Obj, now: Long): ParsedBackup {
        val where = "$root/backup"
        val format = data.optString("format", where)
        if (format != null && format != BACKUP_FORMAT_NAME) {
            throw BackupValidationException("不是 MyFriend 备份（format=$format）")
        }
        val versionValue = data.fields["formatVersion"]
            ?: throw BackupValidationException("备份缺少 formatVersion")
        val version = (versionValue as? JsonValue.Num)?.asInt
            ?: throw BackupValidationException("备份的 formatVersion 必须是整数")
        if (version != BACKUP_FORMAT_VERSION) throw UnsupportedBackupVersionException(version)

        fun section(key: String): List<JsonValue.Obj> = data.optArr(key, where).mapIndexed { index, item ->
            item as? JsonValue.Obj ?: throw BackupValidationException("备份 $key 第 ${index + 1} 项不是对象")
        }

        val personas = section("personas").mapIndexed { index, obj ->
            parseBackupPersona(obj, "$where.personas[$index]", now)
        }
        val sessions = section("sessions").mapIndexed { index, obj ->
            parseBackupSession(obj, "$where.sessions[$index]", now)
        }
        val messages = section("messages").mapIndexed { index, obj ->
            parseBackupMessage(obj, "$where.messages[$index]", now)
        }
        val memories = section("memories").mapIndexed { index, obj ->
            parseBackupMemory(obj, "$where.memories[$index]", now)
        }
        val presets = section("presets").mapIndexed { index, obj ->
            parseBackupPreset(obj, "$where.presets[$index]", now)
        }
        val settings = data.optObj("settings", where)?.let { raw ->
            val fields = LinkedHashMap<String, JsonValue>()
            for ((key, value) in raw.fields) {
                if (key in SETTINGS_WHITELIST) fields[key] = value
            }
            JsonValue.Obj(fields)
        }

        requireUniqueIds("personas", personas.map { it.id })
        requireUniqueIds("sessions", sessions.map { it.id })
        requireUniqueIds("messages", messages.map { it.id })
        requireUniqueIds("memories", memories.map { it.id })
        requireUniqueIds("presets", presets.map { it.id })
        return ParsedBackup(personas, sessions, messages, memories, presets, settings)
    }

    private fun requireUniqueIds(section: String, ids: List<String>) {
        val duplicated = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicated != null) {
            throw BackupValidationException("备份 $section 里 id=${duplicated.key} 重复")
        }
    }

    private fun parseBackupPersona(obj: JsonValue.Obj, where: String, now: Long): Persona {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val name = obj.optString("name", where)?.trim().orEmpty()
        if (name.isEmpty()) throw BackupValidationException("$where 缺少 name")
        val createdAt = obj.optLong("createdAt", where) ?: now
        val updatedAt = obj.optLong("updatedAt", where) ?: createdAt
        val isDefault = obj.optBool("isDefault", where) ?: false
        return Persona(
            id = id,
            name = name,
            nameEn = obj.optString("nameEn", where) ?: "",
            description = obj.optString("description", where) ?: "",
            avatarUrl = obj.optString("avatarUrl", where),
            isDefault = isDefault,
            createdAt = createdAt,
            updatedAt = updatedAt,
            activityJson = obj.optString("activityJson", where)?.takeIf { it.isNotBlank() },
            extraFields = obj.fields.filterKeys { it !in Persona.RESERVED_KEYS }
        )
    }

    private fun parseBackupSession(obj: JsonValue.Obj, where: String, now: Long): Session {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val personaId = obj.reqString("personaId", where)
        if (!isValidId(personaId)) throw BackupValidationException("$where 的 personaId 不合法")
        val createdAt = obj.optLong("createdAt", where) ?: now
        val updatedAt = obj.optLong("updatedAt", where) ?: createdAt
        return Session(
            id = id,
            personaId = personaId,
            title = obj.optString("title", where) ?: "",
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastMessageAt = obj.optLong("lastMessageAt", where),
            lastBlockIndex = obj.optInt("lastBlockIndex", where) ?: -1,
            // 兼容客户端备份的 `unreadCount`。
            unread = obj.optInt("unread", where) ?: obj.optInt("unreadCount", where) ?: 0,
            messageCount = obj.optLong("messageCount", where) ?: 0
        )
    }

    private fun parseBackupMessage(obj: JsonValue.Obj, where: String, now: Long): Message {
        val id = obj.reqString("id", where)
        if (!MESSAGE_ID_PATTERN.matches(id)) throw BackupValidationException("$where 的 id 不合法")
        val sessionId = obj.reqString("sessionId", where)
        if (!isValidId(sessionId)) throw BackupValidationException("$where 的 sessionId 不合法")
        val role = obj.reqString("role", where)
        if (!MessageRole.isValid(role)) throw BackupValidationException("$where 的 role 不合法")
        val content = obj.reqString("content", where)
        val createdAt = obj.optLong("createdAt", where) ?: now
        val blockIndex = obj.optInt("blockIndex", where) ?: 0
        if (blockIndex < 0) throw BackupValidationException("$where 的 blockIndex 不能是负数")
        val attachments = if (obj.fields.containsKey("attachments")) {
            obj.stringList("attachments", where)
        } else {
            emptyList()
        }
        return Message(
            id = id,
            sessionId = sessionId,
            blockIndex = blockIndex,
            role = role,
            content = content,
            createdAt = createdAt,
            readAt = obj.optLong("readAt", where),
            attachments = attachments,
            source = obj.optString("source", where) ?: MessageSource.IMPORT,
            clientMessageId = obj.optString("clientMessageId", where),
            replyTo = obj.optString("replyTo", where),
            promptTokens = obj.optInt("promptTokens", where),
            completionTokens = obj.optInt("completionTokens", where)
        )
    }

    private fun parseBackupMemory(obj: JsonValue.Obj, where: String, now: Long): Memory {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val personaId = obj.reqString("personaId", where)
        if (!isValidId(personaId)) throw BackupValidationException("$where 的 personaId 不合法")
        val content = obj.reqString("content", where)
        if (content.isBlank()) throw BackupValidationException("$where 的 content 不能为空")
        val importance = obj.optInt("importance", where) ?: 50
        if (importance !in 0..100) throw BackupValidationException("$where 的 importance 必须在 0–100")
        val createdAt = obj.optLong("createdAt", where) ?: now
        val updatedAt = obj.optLong("updatedAt", where) ?: createdAt
        return Memory(
            id = id,
            personaId = personaId,
            content = content,
            importance = importance,
            tags = parseTags(obj.fields["tags"], where),
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun parseBackupPreset(obj: JsonValue.Obj, where: String, now: Long): Preset {
        val id = obj.reqString("id", where)
        if (!isValidId(id)) throw BackupValidationException("$where 的 id 不合法")
        val name = obj.optString("name", where)?.trim().orEmpty()
        if (name.isEmpty()) throw BackupValidationException("$where 缺少 name")
        val createdAt = obj.optLong("createdAt", where) ?: now
        val updatedAt = obj.optLong("updatedAt", where) ?: createdAt
        return Preset(
            id = id,
            name = name,
            description = obj.optString("description", where) ?: "",
            prompt = obj.optString("prompt", where) ?: "",
            category = obj.optString("category", where) ?: "",
            avatarUri = obj.optString("avatarUri", where),
            avatarSeed = obj.optInt("avatarSeed", where) ?: 0,
            createdAt = createdAt,
            updatedAt = updatedAt,
            worldJson = obj.optString("worldJson", where)?.takeIf { it.isNotBlank() },
            speechStyleJson = obj.optString("speechStyleJson", where)?.takeIf { it.isNotBlank() },
            extraFields = obj.fields.filterKeys { it !in Preset.RESERVED_KEYS }
        )
    }

    private fun parseTags(value: JsonValue?, where: String): List<String> = when (value) {
        null, JsonValue.Null -> emptyList()
        is JsonValue.Arr -> value.items.map { item ->
            (item as? JsonValue.Str)?.value ?: throw BackupValidationException("$where 的 tags 里有非字符串元素")
        }
        is JsonValue.Str -> {
            val text = value.value.trim()
            if (text.isEmpty()) {
                emptyList()
            } else {
                val parsed = try {
                    com.myfriend.app.core.json.Json.parse(text)
                } catch (_: JsonParseException) {
                    // 也接受逗号分隔的旧写法；不把一条脏标签当成整份备份失败。
                    return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                (parsed as? JsonValue.Arr)?.items?.map { item ->
                    (item as? JsonValue.Str)?.value
                        ?: throw BackupValidationException("$where 的 tags 里有非字符串元素")
                } ?: listOf(text)
            }
        }
        else -> throw BackupValidationException("$where 的 tags 必须是数组或字符串")
    }

    private fun JsonValue.Obj.optBool(key: String, where: String): Boolean? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Bool)?.value
            ?: throw BackupValidationException("$where 的字段 $key 不是布尔")
    }

    private fun publicSettingsLocked(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap<String, JsonValue>().apply {
            for ((key, value) in settings.fields) if (key in SETTINGS_WHITELIST) put(key, value)
        }
    )

    private fun allMessagesLocked(): List<Message> {
        val result = ArrayList<Message>()
        for (session in sessions.values) {
            for (block in blocksForLocked(session.id)) {
                result.addAll(readBlockLocked(session.id, block.index))
            }
        }
        return result.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    // ================================================================== 内部：加载与播种

    private fun hasExistingData(): Boolean =
        Files.exists(root.resolve(PERSONAS_FILE)) ||
            Files.exists(root.resolve(SESSIONS_FILE)) ||
            Files.exists(root.resolve(MEMORIES_FILE)) ||
            Files.exists(root.resolve(PRESETS_FILE)) ||
            Files.exists(root.resolve(PUSH_TOKENS_FILE)) ||
            Files.exists(eventsFile) ||
            Files.newDirectoryStream(sessionsDir).use { it.iterator().hasNext() }

    private fun loadExistingData(metaJson: JsonValue) {
        val where = "${root.resolve(META_FILE)}"
        val parsed = StoreMeta.fromJson(metaJson as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象"), where)
        if (parsed.version != VERSION) {
            throw StoreCorruptionException("$where 的 version=${parsed.version} 不受支持（本版本只读 $VERSION）")
        }
        meta = parsed
        cursor = parsed.cursor

        val personasJson = readJsonFile(root.resolve(PERSONAS_FILE))
            ?: throw StoreCorruptionException("数据目录缺少 $PERSONAS_FILE（不能静默重建：那会丢掉人格）")
        val personasWhere = "${root.resolve(PERSONAS_FILE)}"
        val personaObj = personasJson as? JsonValue.Obj ?: throw StoreCorruptionException("$personasWhere 不是 JSON 对象")
        for ((index, item) in personaObj.optArr("personas", personasWhere).withIndex()) {
            val obj = item as? JsonValue.Obj
                ?: throw StoreCorruptionException("$personasWhere 第 ${index + 1} 个人格不是对象")
            personas += Persona.fromJson(obj, "$personasWhere 第 ${index + 1} 个人格")
        }

        val sessionsJson = readJsonFile(root.resolve(SESSIONS_FILE))
            ?: throw StoreCorruptionException("数据目录缺少 $SESSIONS_FILE（不能静默重建：那会丢掉会话）")
        val sessionsWhere = "${root.resolve(SESSIONS_FILE)}"
        val sessionObj = sessionsJson as? JsonValue.Obj ?: throw StoreCorruptionException("$sessionsWhere 不是 JSON 对象")
        for ((index, item) in sessionObj.optArr("sessions", sessionsWhere).withIndex()) {
            val obj = item as? JsonValue.Obj
                ?: throw StoreCorruptionException("$sessionsWhere 第 ${index + 1} 个会话不是对象")
            val session = Session.fromJson(obj, "$sessionsWhere 第 ${index + 1} 个会话")
            sessions[session.id] = session
        }

        loadOptionalObjectList(root.resolve(MOODS_FILE), "moods") { obj, where ->
            val mood = Mood.fromJson(obj, where)
            moods[mood.personaId] = mood
        }
        loadOptionalObjectList(root.resolve(PRESENCE_FILE), "presence") { obj, where ->
            val value = Presence.fromJson(obj, where)
            presence[value.personaId] = value
        }
        loadOptionalObjectList(root.resolve(API_CONFIGS_FILE), "configs") { obj, where ->
            val config = ApiConfig.fromJson(obj, where)
            apiConfigs[config.id] = config
        }
        readJsonFile(root.resolve(USAGE_FILE))?.let { json ->
            val where = "${root.resolve(USAGE_FILE)}"
            val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
            for ((personaId, total) in obj.optObj("usage", where)?.fields.orEmpty()) {
                usage[personaId] = UsageTotal.fromJson(total as? JsonValue.Obj ?: throw StoreCorruptionException("$where 的 usage.$personaId 不是对象"), "$where 的 usage.$personaId")
            }
        }
        readJsonFile(root.resolve(SETTINGS_FILE))?.let { json ->
            val where = "${root.resolve(SETTINGS_FILE)}"
            val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
            settings = obj.optObj("settings", where) ?: JsonValue.Obj()
        }
        loadOptionalObjectList(root.resolve(PROACTIVE_STATE_FILE), "states") { obj, where ->
            val state = ProactiveState.fromJson(obj, where)
            proactiveStates[state.personaId] = state
        }
        loadOptionalObjectList(root.resolve(MEMORIES_FILE), "memories") { obj, where ->
            val memory = Memory.fromJson(obj, where)
            memories[memory.id] = memory
        }
        loadOptionalObjectList(root.resolve(PRESETS_FILE), "presets") { obj, where ->
            val preset = Preset.fromJson(obj, where)
            presets[preset.id] = preset
        }
        loadOptionalObjectList(root.resolve(PUSH_TOKENS_FILE), "tokens") { obj, where ->
            val token = PushRegistration.fromJson(obj, where)
            pushTokens[pushTokenKey(token.platform, token.token)] = token
        }
        if (Files.exists(root.resolve(SECRETS_FILE))) {
            // 0600 是承诺，不是建议：打开时再收紧一次（有人复制/解压可能带上更宽的权限）。
            setPosixPermissions(root.resolve(SECRETS_FILE), "rw-------")
        }

        loadEventsLocked()
        lastTimestamp = computeLastTimestampLocked()
        if (meta.cursor < cursor) {
            persistMetaLocked()
        }
    }

    private fun loadOptionalObjectList(
        file: Path,
        arrayKey: String,
        consume: (JsonValue.Obj, String) -> Unit
    ) {
        val json = readJsonFile(file) ?: return
        val where = "$file"
        val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
        for ((index, item) in obj.optArr(arrayKey, where).withIndex()) {
            val element = item as? JsonValue.Obj
                ?: throw StoreCorruptionException("$where 的 $arrayKey 第 ${index + 1} 项不是对象")
            consume(element, "$where 的 $arrayKey 第 ${index + 1} 项")
        }
    }

    private fun loadEventsLocked() {
        if (!Files.exists(eventsFile)) return
        var maxCursor = 0L
        var lineNumber = 0
        Files.newBufferedReader(eventsFile, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1
                if (line.isBlank()) continue
                val event = try {
                    val parsed = StoreJson.parse(line, "$eventsFile 第 $lineNumber 行")
                    StoredEvent.fromJson(
                        parsed as? JsonValue.Obj
                            ?: throw StoreCorruptionException("$eventsFile 第 $lineNumber 行不是 JSON 对象"),
                        "$eventsFile 第 $lineNumber 行"
                    )
                } catch (failure: StoreCorruptionException) {
                    // 只容忍末尾半行（进程被杀时 append 到一半）；中间坏行说明文件被改过，照样跳过并告警。
                    System.err.println("[agentd] 跳过事件日志坏行：$eventsFile 第 $lineNumber 行（${failure.message}）")
                    continue
                }
                maxCursor = maxOf(maxCursor, event.cursor)
                if (events.size >= MAX_EVENTS_IN_MEMORY) events.removeFirst()
                events.addLast(event)
            }
        }
        cursor = maxOf(cursor, maxCursor)
    }

    private fun computeLastTimestampLocked(): Long {
        var latest = 0L
        for (persona in personas) latest = maxOf(latest, persona.updatedAt)
        for (session in sessions.values) {
            latest = maxOf(latest, session.updatedAt, session.lastMessageAt ?: 0L)
        }
        for (mood in moods.values) latest = maxOf(latest, mood.updatedAt)
        for (value in presence.values) latest = maxOf(latest, value.updatedAt)
        for (event in events) latest = maxOf(latest, event.at)
        return latest
    }

    private fun seedFreshData() {
        val now = nextTimestampLocked()
        personas += Persona(
            id = SEED_PERSONA_1_ID,
            name = "林深",
            nameEn = "Elias",
            description = "一个安静、温柔的倾听者。适合在夜里说说话。",
            avatarUrl = null,
            isDefault = true,
            createdAt = now,
            updatedAt = now
        )
        personas += Persona(
            id = SEED_PERSONA_2_ID,
            name = "阿岚",
            nameEn = "Finn",
            description = "直率、鲜活的行动派。会把沉闷的日子搅出一点风。",
            avatarUrl = null,
            isDefault = false,
            createdAt = now,
            updatedAt = now
        )
        personas += Persona(
            id = SEED_PERSONA_3_ID,
            name = "墨",
            nameEn = "Mo",
            description = "冷静、清晰的思考伙伴。擅长把复杂的问题拆开。",
            avatarUrl = null,
            isDefault = false,
            createdAt = now,
            updatedAt = now
        )
        sessions[SEED_SESSION_ID] = Session(
            id = SEED_SESSION_ID,
            personaId = SEED_PERSONA_1_ID,
            title = "和林深的对话",
            createdAt = now,
            updatedAt = now,
            lastMessageAt = null,
            lastBlockIndex = -1,
            unread = 0,
            messageCount = 0
        )
        moods[SEED_PERSONA_1_ID] = Mood(SEED_PERSONA_1_ID, "平静", 2, now)
        presence[SEED_PERSONA_1_ID] = Presence(SEED_PERSONA_1_ID, "IDLE", "在家，安静地待着", now)
        meta = StoreMeta(version = VERSION, cursor = cursor, seeded = true)
        persistMetaLocked()
        persistPersonasLocked()
        persistSessionsLocked()
        persistMoodsLocked()
        persistPresenceLocked()
    }

    private fun writeEmptyBaseline() {
        meta = StoreMeta(version = VERSION, cursor = 0, seeded = false)
        persistMetaLocked()
        persistPersonasLocked()
        persistSessionsLocked()
    }

    // ================================================================== 内部：块

    private fun blocksForLocked(sessionId: String): MutableList<BlockMeta> = blockCache.getOrPut(sessionId) {
        loadBlockIndexLocked(sessionId).toMutableList()
    }

    private fun loadBlockIndexLocked(sessionId: String): List<BlockMeta> {
        val file = blockIndexFile(sessionId)
        val json = readJsonFile(file)
        if (json == null) {
            // 块表丢了（或从没写过）：按块文件重建一次，不静默丢消息。
            // 空会话不写文件：读路径不产生写操作（下次仍然是「没有块表 + 没有块文件」）。
            val rebuilt = rebuildBlockIndexLocked(sessionId)
            if (rebuilt.isNotEmpty()) persistBlockIndexLocked(sessionId, rebuilt)
            return rebuilt
        }
        val where = "$file"
        val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
        return obj.optArr("blocks", where).mapIndexed { index, item ->
            BlockMeta.fromJson(
                item as? JsonValue.Obj ?: throw StoreCorruptionException("$where 的 blocks 第 ${index + 1} 项不是对象"),
                "$where 的 blocks 第 ${index + 1} 项"
            )
        }.sortedBy { it.index }
    }

    /** 扫描 `blocks/` 目录里的 `*.jsonl` 重建块表（只有块表丢失/被要求时才做，O(历史)）。 */
    fun rebuildBlockIndex(sessionId: String): List<BlockMeta> = lock.withLock {
        requireSessionLocked(sessionId)
        val rebuilt = rebuildBlockIndexLocked(sessionId)
        blockCache[sessionId] = rebuilt.toMutableList()
        messageIndexCache.remove(sessionId)
        persistBlockIndexLocked(sessionId, rebuilt)
        rebuilt
    }

    private fun rebuildBlockIndexLocked(sessionId: String): List<BlockMeta> {
        val dir = blockDir(sessionId)
        if (!Files.exists(dir)) return emptyList()
        val indexes = Files.newDirectoryStream(dir).use { stream ->
            stream.mapNotNull { path ->
                val name = path.fileName.toString()
                if (!name.endsWith(".jsonl")) return@mapNotNull null
                name.removeSuffix(".jsonl").toIntOrNull()
            }.sorted()
        }
        return indexes.mapNotNull { index ->
            val messages = readBlockLocked(sessionId, index)
            if (messages.isEmpty()) return@mapNotNull null
            BlockMeta(
                index = index,
                firstMessageId = messages.first().id,
                lastMessageId = messages.last().id,
                firstAt = messages.first().createdAt,
                lastAt = messages.last().createdAt,
                count = messages.size
            )
        }
    }

    private fun readBlockLocked(sessionId: String, blockIndex: Int): List<Message> {
        val file = blockFile(sessionId, blockIndex)
        if (!Files.exists(file)) return emptyList()
        val messages = ArrayList<Message>(BLOCK_SIZE)
        var lineNumber = 0
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1
                if (line.isBlank()) continue
                val where = "$file 第 $lineNumber 行"
                try {
                    val parsed = StoreJson.parse(line, where) as? JsonValue.Obj
                        ?: throw StoreCorruptionException("$where 不是 JSON 对象")
                    messages += Message.fromJson(parsed, where)
                } catch (failure: StoreCorruptionException) {
                    // 典型的「末尾半行」：append 到一半进程被杀。跳过并告警，不让整块读不出来。
                    System.err.println("[agentd] 跳过消息坏行：$where（${failure.message}）")
                }
            }
        }
        return messages
    }

    private fun rewriteBlockLocked(sessionId: String, blockIndex: Int, messages: List<Message>) {
        val text = buildString {
            for (message in messages) append(StoreJson.stringify(message.toJson())).append('\n')
        }
        writeTextLocked(blockFile(sessionId, blockIndex), text)
    }

    private fun persistBlockIndexLocked(sessionId: String, blocks: List<BlockMeta>) {
        writeJsonLocked(
            blockIndexFile(sessionId),
            StoreJson.obj(
                "sessionId" to StoreJson.str(sessionId),
                "blocks" to StoreJson.arr(blocks.map { it.toJson() })
            )
        )
    }

    // ================================================================== 内部：消息索引（幂等查找）

    /**
     * 每个会话的 `clientMessageId → messageId` 与 `userMessageId → assistantMessageId` 索引。
     * 第一次用到时扫一遍块建立（O(历史)），之后随追加/删除增量维护。
     */
    private class SessionMessageIndex(
        val clientMessageIds: MutableMap<String, String> = HashMap(),
        val replies: MutableMap<String, String> = HashMap()
    )

    private fun messageIndexLocked(sessionId: String): SessionMessageIndex = messageIndexCache.getOrPut(sessionId) {
        val index = SessionMessageIndex()
        for (block in blocksForLocked(sessionId)) {
            for (message in readBlockLocked(sessionId, block.index)) {
                message.clientMessageId?.let { index.clientMessageIds[it] = message.id }
                message.replyTo?.let { index.replies[it] = message.id }
            }
        }
        index
    }

    private fun indexForAppendLocked(sessionId: String, message: Message) {
        val index = messageIndexCache[sessionId] ?: return
        message.clientMessageId?.let { index.clientMessageIds[it] = message.id }
        message.replyTo?.let { index.replies[it] = message.id }
    }

    private fun indexForDeleteLocked(sessionId: String, message: Message) {
        val index = messageIndexCache[sessionId] ?: return
        message.clientMessageId?.let { index.clientMessageIds.remove(it) }
        message.replyTo?.let { index.replies.remove(it) }
    }

    private fun findMessageLocked(sessionId: String, messageId: String): Message? {
        for (block in blocksForLocked(sessionId).asReversed()) {
            readBlockLocked(sessionId, block.index).firstOrNull { it.id == messageId }?.let { return it }
        }
        return null
    }

    // ================================================================== 内部：事件写入

    private fun eventsSinceLocked(since: Long, limit: Int): List<StoredEvent> {
        if (limit <= 0) return emptyList()
        val oldestInMemory = events.firstOrNull()?.cursor
        // 游标比内存窗口还老：说明有事件被裁剪了，回文件里找（内存窗口只是加速）。
        if (oldestInMemory != null && since + 1 < oldestInMemory) {
            return readEventsFromFileLocked(since, limit)
        }
        if (oldestInMemory == null && since < cursor) {
            return readEventsFromFileLocked(since, limit)
        }
        return events.filter { it.cursor > since }.take(limit)
    }

    private fun readEventsFromFileLocked(since: Long, limit: Int): List<StoredEvent> {
        if (!Files.exists(eventsFile)) return emptyList()
        val result = ArrayList<StoredEvent>(minOf(limit, 64))
        Files.newBufferedReader(eventsFile, StandardCharsets.UTF_8).use { reader ->
            while (result.size < limit) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val parsed = try {
                    StoreJson.parse(line, "$eventsFile（增量回读）") as? JsonValue.Obj
                        ?: continue
                } catch (_: StoreCorruptionException) {
                    continue
                }
                val event = try {
                    StoredEvent.fromJson(parsed, "$eventsFile（增量回读）")
                } catch (_: StoreCorruptionException) {
                    continue
                }
                if (event.cursor > since) result += event
            }
        }
        return result
    }

    private fun appendEventLocked(
        type: String,
        at: Long,
        sessionId: String?,
        personaId: String?,
        payload: LinkedHashMap<String, JsonValue>
    ): StoredEvent {
        val nextCursor = cursor + 1
        val event = StoredEvent(nextCursor, type, at, sessionId, personaId, JsonValue.Obj(payload))
        appendLineLocked(eventsFile, StoreJson.stringify(event.toJson()))
        cursor = nextCursor
        if (events.size >= MAX_EVENTS_IN_MEMORY) events.removeFirst()
        events.addLast(event)
        meta = meta.copy(cursor = cursor)
        persistMetaLocked()
        synchronized(eventMonitor) { eventMonitor.notifyAll() }
        return event
    }

    // ================================================================== 内部：持久化

    private fun persistMetaLocked() = writeJsonLocked(root.resolve(META_FILE), meta.toJson())

    private fun persistPersonasLocked() = writeJsonLocked(
        root.resolve(PERSONAS_FILE),
        StoreJson.obj("personas" to StoreJson.arr(personas.map { it.toJson() }))
    )

    private fun persistSessionsLocked() = writeJsonLocked(
        root.resolve(SESSIONS_FILE),
        StoreJson.obj("sessions" to StoreJson.arr(sessions.values.map { it.toJson() }))
    )

    private fun persistMoodsLocked() = writeJsonLocked(
        root.resolve(MOODS_FILE),
        StoreJson.obj("moods" to StoreJson.arr(moods.values.map { it.toJson() }))
    )

    private fun persistPresenceLocked() = writeJsonLocked(
        root.resolve(PRESENCE_FILE),
        StoreJson.obj("presence" to StoreJson.arr(presence.values.map { it.toJson() }))
    )

    private fun persistUsageLocked() = writeJsonLocked(
        root.resolve(USAGE_FILE),
        StoreJson.obj(
            "usage" to StoreJson.obj(
                LinkedHashMap<String, JsonValue>().apply {
                    for ((personaId, total) in usage) put(personaId, total.toJson())
                }
            )
        )
    )

    private fun persistApiConfigsLocked() = writeJsonLocked(
        root.resolve(API_CONFIGS_FILE),
        StoreJson.obj("configs" to StoreJson.arr(apiConfigs.values.map { it.toJson() }))
    )

    private fun persistProactiveStateLocked() = writeJsonLocked(
        root.resolve(PROACTIVE_STATE_FILE),
        StoreJson.obj("states" to StoreJson.arr(proactiveStates.values.map { it.toJson() }))
    )

    private fun persistMemoriesLocked() = writeJsonLocked(
        root.resolve(MEMORIES_FILE),
        StoreJson.obj("memories" to StoreJson.arr(memories.values.map { it.toJson() }))
    )

    private fun persistPresetsLocked() = writeJsonLocked(
        root.resolve(PRESETS_FILE),
        StoreJson.obj("presets" to StoreJson.arr(presets.values.map { it.toJson() }))
    )

    private fun persistPushTokensLocked() = writeJsonLocked(
        root.resolve(PUSH_TOKENS_FILE),
        StoreJson.obj("tokens" to StoreJson.arr(pushTokens.values.map { it.toJson() }))
    )

    private fun readSecretsLocked(): Map<String, String> {
        val file = root.resolve(SECRETS_FILE)
        val json = readJsonFile(file) ?: return emptyMap()
        val where = "$file"
        val obj = json as? JsonValue.Obj ?: throw StoreCorruptionException("$where 不是 JSON 对象")
        val keys = obj.optObj("keys", where) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((configId, value) in keys.fields) {
            val secret = (value as? JsonValue.Str)?.value
                ?: throw StoreCorruptionException("$where 的 keys.$configId 不是字符串")
            result[configId] = secret
        }
        return result
    }

    private fun persistSecretsLocked(secrets: Map<String, String>) {
        val file = root.resolve(SECRETS_FILE)
        val keys = JsonValue.Obj()
        for ((configId, secret) in secrets) keys.fields[configId] = JsonValue.Str(secret)
        writeJsonLocked(file, StoreJson.obj("keys" to keys), posixMode = "rw-------")
    }

    /** 原子写一份 JSON：`*.tmp` → `force` → `ATOMIC_MOVE`。 */
    private fun writeJsonLocked(file: Path, value: JsonValue, posixMode: String? = null) {
        writeTextLocked(file, StoreJson.stringify(value), posixMode)
    }

    private fun writeTextLocked(file: Path, text: String, posixMode: String? = null) {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        // 不调 `force(true)`：原子性由 rename 保证；force 只影响「断电后能否保住最后几次写」，
        // 而每次写都 fsync 会让「一条消息写 4 个文件」变成 4 次磁盘同步（并发追加测试会慢一个数量级）。
        // 取舍写在 README 的「风险」里：进程崩溃安全，机器掉电可能丢最后几条。
        FileChannel.open(
            temp,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        if (posixMode != null) setPosixPermissions(temp, posixMode)
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            // 某些文件系统（网络盘）不支持原子 rename：退化成普通替换，至少不会留下半截文件。
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        if (posixMode != null) setPosixPermissions(file, posixMode)
    }

    private fun appendLineLocked(file: Path, line: String) {
        Files.createDirectories(file.parent)
        Files.write(
            file,
            (line + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        )
    }

    private fun readJsonFile(file: Path): JsonValue? {
        if (!Files.exists(file)) return null
        val text = try {
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (failure: IOException) {
            throw StoreCorruptionException("读不了 $file：${failure.message}")
        }
        if (text.isBlank()) return null
        return StoreJson.parse(text, "$file")
    }

    private fun setPosixPermissions(path: Path, mode: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        } catch (_: UnsupportedOperationException) {
            // 非 POSIX 文件系统（Windows）：没有 0600 这个概念，忽略。
        } catch (_: IOException) {
            System.err.println("[agentd] 设置文件权限失败：$path（$mode）")
        }
    }

    // ================================================================== 内部：路径与 id

    private fun pushTokenKey(platform: String, token: String): String = "$platform|$token"

    private fun sessionDir(sessionId: String): Path {
        if (!isValidId(sessionId)) throw IllegalArgumentException("非法的会话 id")
        return sessionsDir.resolve(sessionId)
    }

    private fun blockDir(sessionId: String): Path = sessionDir(sessionId).resolve(BLOCKS_DIR)

    private fun blockFile(sessionId: String, blockIndex: Int): Path =
        blockDir(sessionId).resolve("$blockIndex.jsonl")

    private fun blockIndexFile(sessionId: String): Path =
        sessionDir(sessionId).resolve(BLOCK_INDEX_FILE)

    private fun requireSessionLocked(sessionId: String): Session {
        if (!isValidId(sessionId)) throw IllegalArgumentException("非法的会话 id")
        return sessions[sessionId] ?: throw StoreSessionNotFoundException(sessionId)
    }

    private fun requirePersonaLocked(personaId: String) {
        if (personas.none { it.id == personaId }) throw IllegalArgumentException("人格不存在：$personaId")
    }

    /**
     * 读路径的跨日归零：日期不是 [today] 时计数当 0，其余字段（尤其 `lastProactiveAt`）保留。
     * 不写盘——只读不该产生副作用。
     */
    private fun effectiveProactiveStateLocked(personaId: String, today: String): ProactiveState {
        val stored = proactiveStates[personaId]
        if (stored == null) return ProactiveState(personaId = personaId, proactiveCountDate = today)
        return if (stored.proactiveCountDate == today) {
            stored
        } else {
            stored.copy(proactiveCountToday = 0, proactiveCountDate = today)
        }
    }

    /** 写路径：跨日归零后 +1，并原子落盘。调用方必须已经持有 [lock]。 */
    private fun recordProactiveFiredLocked(personaId: String, at: Long, today: String): ProactiveState {
        val previous = effectiveProactiveStateLocked(personaId, today)
        val updated = previous.copy(
            lastProactiveAt = at,
            proactiveCountToday = previous.proactiveCountToday + 1,
            proactiveCountDate = today
        )
        proactiveStates[personaId] = updated
        persistProactiveStateLocked()
        return updated
    }

    private fun defaultPersonaIdLocked(): String =
        personas.firstOrNull { it.isDefault }?.id ?: personas.firstOrNull()?.id ?: ""

    private fun nextTimestampLocked(): Long {
        lastTimestamp = maxOf(clock(), lastTimestamp + 1)
        return lastTimestamp
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

/** 会话不存在（路由层翻成 404，不是 500）。 */
class StoreSessionNotFoundException(val sessionId: String) : NoSuchElementException("没有这个会话：$sessionId")
