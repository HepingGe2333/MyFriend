package com.myfriend.app.data.repo

import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MemoryCategory
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.secure.SecretCipher
import com.myfriend.app.domain.MessageWindows
import com.myfriend.app.domain.PersonaCard
import com.myfriend.app.network.DeepSeekWebTools
import com.myfriend.app.network.WebSearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.LinkedHashMap
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次「自己上网看看」的结果。
 *
 * @param digest 给模型看的搜索结果摘要（「标题 — 摘要（站点）」，每行一条）
 * @param queries 这次实际用到的搜索词
 * @param rememberedId 写进长期记忆的那条记忆 id；没写（记忆关了 / 写失败）时为 null
 * @param message 中文提示，给日志 / 调试面板用，例如「他自己上网看了 4 条资讯：科技新闻」
 */
data class IdleBrowseOutcome(
    val digest: String,
    val queries: List<String>,
    val rememberedId: String?,
    val message: String
)

/**
 * 空闲自浏览：没人理他的时候，他自己去网上看看最近有什么值得知道的。
 *
 * 分工：
 * - [IdleTopics] 是纯逻辑（话题挑选、结果摘要、记忆句），不碰网络也不碰数据库，单测覆盖；
 * - [IdleBrowsing] 负责取人设 / 最近对话 / 联网配置，真去搜，再把看到的东西写成一条 EVENT 记忆，
 *   等对方回来时由主动消息自然提起。
 *
 * 频率限制的记忆是**进程内**的：重启后 [lastBrowsedAt] 归零，只会让刚重启时可能多浏览一次，
 * 不影响功能；真正的近况已经落进长期记忆了。摘要缓存同理（重启丢失可接受）。
 *
 * 进程内的东西都刻意做得很小：这些都只是「顺手一眼」的加速，不值得长期占着内存——
 * 有一次数上限（[MAX_CACHED_PERSONAS] 个人格）、单条长度上限（[MAX_DIGEST_CHARS]）、
 * 每人格条数上限（[MAX_DIGESTS_PER_PERSONA]）和过期时间（[DIGEST_TTL_MS]，
 * 半天前的新闻不该还当成他的近况）。
 */
@Singleton
class IdleBrowsing @Inject constructor(
    private val webTools: DeepSeekWebTools,
    private val apiConfigRepository: ApiConfigRepository,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val memoryRepository: MemoryRepository,
    private val personaCardRepository: PersonaCardRepository,
    // 联网专用 Key 是加密存的，解它需要和 ToolExecutor 一样的做法；这只是复用现成依赖，不改它。
    private val secretCipher: SecretCipher
) {

    /** 同一时间只允许一次自浏览，避免并发两次把同一批新闻写两遍。 */
    private val browseMutex = Mutex()

    /** 进程内的「上次自浏览时间」：重启后丢失，只影响频率限制，不影响功能。 */
    private val lastBrowsedAt = LinkedHashMap<String, Long>()

    /**
     * 进程内的「最近几次摘要」：每个人格最多留 [MAX_DIGESTS_PER_PERSONA] 条，
     * 最多留 [MAX_CACHED_PERSONAS] 个人格；按写入顺序淘汰最旧。
     */
    private val digestCache = LinkedHashMap<String, MutableList<CachedDigest>>()

    private val cacheLock = Any()

    /** 一条进程内缓存的摘要：正文（已经截短）+ 记下来的时间（判过期用）。 */
    private data class CachedDigest(val digest: String, val at: Long)

    // ---------------------------------------------------------------- 公开 API

    /** 这个会话/人格最新活动时间；用来判断是不是一个人待着。没有任何记录时返回 0。 */
    suspend fun lastActivityAt(personaId: String): Long = runCatching<Long> {
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull()
            ?: return@runCatching 0L
        var latest = persona.updatedAt
        val sessions = runCatching { sessionDao.getByPersona(personaId) }.getOrDefault(emptyList())
        sessions.forEach { session ->
            listOfNotNull(session.lastMessageAt, session.createdAt, session.updatedAt)
                .forEach { latest = maxOf(latest, it) }
            runCatching { messageDao.latestInSession(session.id) }.getOrNull()?.let { message ->
                latest = maxOf(latest, message.createdAt)
            }
        }
        latest
    }.getOrDefault(0L)

    /** 真正去浏览一次：挑话题 → 搜索 → 写进记忆（drawer = "EVENT", importance = 2）。 */
    suspend fun browseOnce(
        personaId: String,
        now: Long = System.currentTimeMillis()
    ): Result<IdleBrowseOutcome> = browseMutex.withLock {
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull()
            ?: return@withLock Result.failure(IllegalStateException("找不到这个人格"))
        val access = webAccess(persona)
            ?: return@withLock Result.failure(IllegalStateException(NOT_CONFIGURED_MESSAGE))
        browse(persona, access, now)
    }

    /**
     * 关心的人回来了：把最近一次自浏览的摘要拿出来，供主动消息引用。
     *
     * 过了 [DIGEST_TTL_MS] 的就算过期：半天前的新闻再拿出来当近况说，反而像没在听人说话。
     */
    suspend fun latestDigest(personaId: String, now: Long = System.currentTimeMillis()): String? =
        synchronized(cacheLock) {
            val history = digestCache[personaId] ?: return@synchronized null
            dropExpiredDigests(history, now)
            val latest = history.lastOrNull()
            if (latest == null) digestCache.remove(personaId)
            latest?.digest
        }

    suspend fun markBrowsed(personaId: String, now: Long) {
        if (personaId.isBlank()) return
        synchronized(cacheLock) {
            lastBrowsedAt[personaId] = now
            trimOldest(lastBrowsedAt, MAX_CACHED_PERSONAS)
        }
    }

    /** 这个人格上一次自浏览的时间；没有记录（含刚重启）时返回 0。 */
    suspend fun lastBrowseAt(personaId: String): Long = synchronized(cacheLock) {
        lastBrowsedAt[personaId] ?: 0L
    }

    /**
     * 现在适合不适合让他自己出去看看：真的一个人待够了、离上次自浏览够久、并且联网工具可用。
     * 接线方（Worker）用这一个判断就够了；细节规则都在 [IdleTopics] 里。
     */
    suspend fun shouldBrowseNow(
        personaId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = runCatching {
        if (!IdleTopics.shouldBrowse(lastBrowseAt(personaId), now)) return@runCatching false
        if (!IdleTopics.isIdle(lastActivityAt(personaId), now)) return@runCatching false
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull()
            ?: return@runCatching false
        webAccess(persona) != null
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 浏览主流程

    private suspend fun browse(
        persona: PersonaEntity,
        access: WebAccess,
        now: Long
    ): Result<IdleBrowseOutcome> {
        val card = runCatching { personaCardRepository.cardOf(persona.id) }
            .getOrDefault(PersonaCard())
        val queries = IdleTopics.queriesFor(
            card = card,
            recentUserTexts = recentUserTexts(persona.id),
            daySeed = IdleTopics.daySeedOf(now)
        )

        val hits = mutableListOf<WebSearchHit>()
        var lastError: Throwable? = null
        for (query in queries) {
            guarded {
                webTools.search(
                    baseUrl = access.baseUrl,
                    apiKey = access.apiKey,
                    query = query,
                    count = SEARCH_COUNT,
                    oriQuestion = null,
                    needContent = false,
                    allowInsecureHttp = access.allowInsecureHttp
                )
            }.onSuccess { found -> hits += found }.onFailure { error -> lastError = error }
            if (hits.size >= MAX_HITS) break
        }

        val digest = IdleTopics.digestFor(hits)
        if (digest.isBlank()) {
            // 一条都没搜到（或者全都挂了）：把最后一次错误交回去，别让异常冒泡到 Worker。
            return Result.failure(lastError ?: IllegalStateException("这次没搜到什么值得说的"))
        }

        val points = digest.lines().count { it.isNotBlank() }
        val rememberedId = remember(persona.id, IdleTopics.memoryLineFor(digest))
        markBrowsed(persona.id, now)
        rememberDigest(persona.id, digest, now)
        return Result.success(
            IdleBrowseOutcome(
                digest = digest,
                queries = queries,
                rememberedId = rememberedId,
                message = describe(points, queries)
            )
        )
    }

    /** 写成一条「近况」记忆。1.9 起记忆内置开启：看到的东西照常记，没有开关可判断。 */
    private suspend fun remember(personaId: String, content: String): String? {
        return runCatching {
            memoryRepository.addDetailed(
                personaId = personaId,
                content = content,
                category = MemoryCategory.EVENT,
                importance = MEMORY_IMPORTANCE,
                pinned = false,
                drawer = MEMORY_DRAWER,
                sourceQuote = MEMORY_SOURCE_QUOTE,
                sourceSessionId = null
            )
        }.getOrNull()?.getOrNull()
    }

    private fun rememberDigest(personaId: String, digest: String, now: Long) {
        val text = digest.trim()
        if (personaId.isBlank() || text.isBlank()) return
        synchronized(cacheLock) {
            // 重新 put 会保留 LinkedHashMap 的插入顺序语义，这里干脆先删再插，把它排到最新。
            val history = digestCache.remove(personaId)?.toMutableList() ?: mutableListOf()
            dropExpiredDigests(history, now)
            // 单条也截一下：摘要本来就短，留 2k 只是防某天搜索结果异常长。
            history.add(CachedDigest(text.take(MAX_DIGEST_CHARS), now))
            while (history.size > MAX_DIGESTS_PER_PERSONA) history.removeAt(0)
            digestCache[personaId] = history
            trimOldest(digestCache, MAX_CACHED_PERSONAS)
        }
    }

    /** 把过期的摘要从这条人格的历史里剔掉；将来的时间（时钟被拨回）也算过期，别占着位置。 */
    private fun dropExpiredDigests(history: MutableList<CachedDigest>, now: Long) {
        history.removeAll { cached ->
            val age = now - cached.at
            age > DIGEST_TTL_MS || age < -DIGEST_TTL_MS
        }
    }

    private fun describe(points: Int, queries: List<String>): String {
        val topic = queries.firstOrNull()?.trim().orEmpty()
        return if (topic.isBlank()) {
            "他自己上网看了 $points 条资讯"
        } else {
            "他自己上网看了 $points 条资讯：$topic"
        }
    }

    // ---------------------------------------------------------------- 联网配置

    /** 和 ToolExecutor 的判断一致：开了联网、并且拿得到 Key，才算能用。 */
    private suspend fun webAccess(persona: PersonaEntity): WebAccess? = runCatching<WebAccess?> {
        val candidates = candidateConfigs(persona)
        candidates.firstNotNullOfOrNull { config -> accessOf(config) }
    }.getOrNull()

    /**
     * 候选配置：这个人格当前会话 / 默认绑定的配置优先，再退到任意一个开了联网的配置
     * （他一个人待着的时候，只需要一把能用的联网 Key）。
     */
    private suspend fun candidateConfigs(persona: PersonaEntity): List<ApiConfigEntity> {
        val boundIds = buildList {
            runCatching { sessionDao.latestForPersona(persona.id)?.apiConfigId }
                .getOrNull()?.let { add(it) }
            persona.defaultApiConfigId?.let { add(it) }
        }.distinct()
        val bound = boundIds.mapNotNull { id ->
            runCatching { apiConfigRepository.getById(id) }.getOrNull()
        }
        val enabled = runCatching {
            apiConfigRepository.getAll().filter { it.webToolsEnabled }
        }.getOrDefault(emptyList())
        return (bound + enabled).distinctBy { it.id }
    }

    private suspend fun accessOf(config: ApiConfigEntity): WebAccess? = runCatching<WebAccess?> {
        if (!config.webToolsEnabled) return@runCatching null
        val baseUrl = config.webToolsBaseUrl.ifBlank { DEFAULT_DEEPSEEK_BASE }
        // 联网专用 Key 优先；没单独配就复用主 Key（与 ToolExecutor.webAccess 一致）。
        val key = config.webToolsApiKeyCipher?.let { ciphertext ->
            runCatching { secretCipher.decrypt(ciphertext) }.getOrNull()
        } ?: runCatching { apiConfigRepository.resolve(config.id)?.apiKey }.getOrNull()
        if (key.isNullOrBlank()) return@runCatching null
        WebAccess(baseUrl = baseUrl, apiKey = key.trim(), allowInsecureHttp = config.allowInsecureHttp)
    }.getOrNull()

    // ---------------------------------------------------------------- 最近对话（只取短片段）

    /**
     * 最近几条用户消息原文，交给 [IdleTopics] 抽关键词。
     * 只取最后几条，且 [IdleTopics] 侧只会截出短词，不会把整段对话塞进搜索词。
     */
    private suspend fun recentUserTexts(personaId: String): List<String> = runCatching<List<String>> {
        val session = runCatching { sessionDao.latestForPersona(personaId) }.getOrNull()
            ?: return@runCatching emptyList()
        // 只查最近 120 条再挑用户消息：这里只需要最后 6 条用户原话，
        // 没有理由把整段历史读进内存（后台 worker 一次醒来的临时峰值也在这里省掉）。
        val recentRows = runCatching {
            messageDao.recentBySession(session.id, MessageWindows.IDLE_LOOKBACK_ROWS)
        }.getOrDefault(emptyList())
        MessageWindows.lastN(recentRows, RECENT_TEXT_LIMIT) {
            it.role == MessageRole.USER && it.content.isNotBlank()
        }.map { it.content }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- 辅助

    /**
     * 兜住所有异常，但协程取消要原样抛出去：Worker 被叫停时不能变成「一次失败」。
     */
    private suspend fun <T> guarded(block: suspend () -> T): Result<T> {
        val outcome = runCatching { block() }
        outcome.exceptionOrNull()?.let { error -> if (error is CancellationException) throw error }
        return outcome
    }

    private fun trimOldest(cache: LinkedHashMap<String, *>, max: Int) {
        while (cache.size > max) {
            val oldest = cache.keys.firstOrNull() ?: return
            cache.remove(oldest)
        }
    }

    private data class WebAccess(
        val baseUrl: String,
        val apiKey: String,
        val allowInsecureHttp: Boolean
    )

    private companion object {
        const val DEFAULT_DEEPSEEK_BASE = "https://api.deepseek.com"
        const val NOT_CONFIGURED_MESSAGE = "还没给他配联网工具的 Key"
        const val SEARCH_COUNT = 6
        const val MAX_HITS = 12
        const val RECENT_TEXT_LIMIT = 6
        const val MEMORY_IMPORTANCE = 2
        const val MEMORY_DRAWER = "EVENT"
        const val MEMORY_SOURCE_QUOTE = "他自己上网看到的"

        /** 进程内最多记多少个人格的自浏览时间 / 摘要：只是加速用，多了就是白占内存。 */
        const val MAX_CACHED_PERSONAS = 8

        /** 每个人格最多留几条摘要：只给主动消息引用最近这一两次，留多了没人看。 */
        const val MAX_DIGESTS_PER_PERSONA = 3

        /** 单条摘要最多留这么多字：防某天搜索结果异常长，把内存撑住。 */
        const val MAX_DIGEST_CHARS = 2_000

        /** 摘要的过期时间：12 小时前的「近况」已经不算近况了。 */
        const val DIGEST_TTL_MS = 12 * 60 * 60 * 1000L
    }
}

/**
 * 「空闲自浏览」里所有可以单测的纯逻辑：挑话题、摘结果、写记忆句、判断该不该出去看看。
 *
 * 全是纯函数（同样的输入永远同样的输出），不碰网络、不碰 Room、不用全局随机数。
 */
object IdleTopics {

    private const val MAX_QUERY_CHARS = 30
    private const val MIN_QUERIES = 2
    private const val MAX_QUERIES = 3
    private const val MIN_QUERY_CHARS = 2
    private const val FRAGMENT_MAX_CHARS = 12
    private const val MAX_DIGEST_LINE_CHARS = 120
    private const val MAX_DIGEST_ITEMS = 5
    private const val MAX_MEMORY_LINE_CHARS = 400
    private const val MAX_MEMORY_POINTS = 3
    private const val MEMORY_POINT_CHARS = 90
    private const val SITE_MAX_CHARS = 16
    private const val SEPARATOR = " — "
    private const val TITLE_FALLBACK = "（没有标题）"
    private const val MIN_HEAD_CHARS = 8
    private const val MIN_BODY_CHARS = 8

    /** 一个人待着多久才算「没人理」：6 小时。 */
    const val DEFAULT_QUIET_SINCE = 6 * 3600_000L

    /** 两次自浏览至少隔 3 小时，免得他一直在刷新闻。 */
    const val DEFAULT_MIN_GAP = 3 * 3600_000L

    /** 完全没有人设线索时的中性兜底话题。 */
    private val NEUTRAL_QUERIES = listOf("今天的重要新闻", "科技新闻")

    /** 人设线索不够时用来补位的中性话题（保持 2~3 条，且不和已有话题重复）。 */
    private val PADDING_QUERIES = listOf("今天的重要新闻", "科技新闻", "今天的热点话题")

    /** 对话里抽关键词时要丢掉的虚词 / 口头语，避免搜出没意义的东西。 */
    private val NOISE_WORDS = setOf(
        "我们", "你们", "他们", "这个", "那个", "什么", "怎么", "然后", "就是",
        "可以", "还是", "已经", "因为", "所以", "但是", "如果", "今天", "现在",
        "一下", "有点", "其实", "感觉", "知道", "觉得", "东西", "事情", "时候",
        "自己", "大家", "真的", "不是", "没有", "起来", "出来", "回来", "一直",
        "本来", "好像", "应该", "可能", "哈哈", "嗯嗯", "哦哦", "好的", "谢谢"
    )

    /** 句子切分：中文标点、空白、常见分隔符。 */
    private val SPLIT_REGEX = Regex("[，,。.；;、！!？?／/|｜:：\\s~～…—\\-]+")

    /** 片段开头要去掉的「在做什么」的引导词（长的排前面，否则先命中短的）。 */
    private val LEADING_WORDS = listOf(
        "最近一直在", "每天都在", "最近在", "现在在", "正在", "最近", "现在", "每天", "在"
    )

    // ------------------------------------------------------------ 挑话题

    /**
     * 从人设卡 + 最近对话拼出 2~3 个中文搜索词。
     *
     * - 人设线索顺序固定：身份/职业 → 最近关心 → 正在做 → 在意的东西 → 对话关键词；
     * - 结果去重、每条 ≤ 30 字、非空；
     * - [daySeed]（当天是这一年第几天）决定从话题池的哪个位置开始取，同一天稳定、换天换主题；
     * - 只取短关键词，绝不把整段对话塞进搜索词，也不碰任何隐私内容（长句、数字串、@ 都会被丢掉）。
     */
    fun queriesFor(card: PersonaCard, recentUserTexts: List<String>, daySeed: Int): List<String> {
        val pool = topicPool(card, recentUserTexts)
        if (pool.isEmpty()) return NEUTRAL_QUERIES.toList()

        val want = pool.size.coerceIn(MIN_QUERIES, MAX_QUERIES)
        val picked = rotated(pool, daySeed).take(want).toMutableList()
        // 话题不够 2 条时用中性话题补位，保证每次都有话可搜。
        if (picked.size < MIN_QUERIES) {
            PADDING_QUERIES.forEach { padding ->
                if (picked.size < MIN_QUERIES && padding !in picked) picked += padding
            }
        }
        return picked.map { it.take(MAX_QUERY_CHARS).trim() }
            .filter { it.length >= MIN_QUERY_CHARS }
            .distinct()
    }

    /**
     * 当天是这一年第几天，用来做确定性轮换（同一天同一个种子）。
     * 用调用方的时区算，跨天就换一批话题。
     */
    fun daySeedOf(now: Long, zone: ZoneId = ZoneId.systemDefault()): Int = runCatching {
        Instant.ofEpochMilli(now).atZone(zone).dayOfYear
    }.getOrDefault(1)

    /** 人设 + 对话能给出的话题池（已去重、已裁剪），顺序固定。 */
    private fun topicPool(card: PersonaCard, recentUserTexts: List<String>): List<String> {
        val topics = mutableListOf<String>()
        val role = oneLine(card.identity.role)
        if (role.isNotBlank()) topics += "${role.take(FRAGMENT_MAX_CHARS)} 行业新闻"

        fragments(card.life.caresAbout).forEach { topics += "$it 最新进展" }
        fragments(card.life.doing).forEach { topics += "$it 最新消息" }
        card.values.filter { it.isNotBlank() }.take(2).forEach { value ->
            val clean = oneLine(value)
            if (clean.isNotBlank()) topics += "关于${clean.take(FRAGMENT_MAX_CHARS)}的讨论"
        }
        userKeywords(recentUserTexts).forEach { topics += "$it 最新消息" }

        return topics.map { oneLine(it).take(MAX_QUERY_CHARS).trim() }
            .filter { it.length >= MIN_QUERY_CHARS }
            .distinct()
    }

    /** 把一个字段切成短片段（关心什么 / 正在做什么这种一句话里常常有好几个话题）。 */
    private fun fragments(raw: String): List<String> = raw.split(SPLIT_REGEX)
        .mapNotNull { normalizeFragment(it, allowTrim = true) }
        .distinct()
        .take(2)

    /** 从最近对话里只抽 2 个短关键词：不取长句、不取数字串、不取带 @ 的东西。 */
    private fun userKeywords(texts: List<String>): List<String> = texts
        .flatMap { text -> text.split(SPLIT_REGEX) }
        .mapNotNull { normalizeFragment(it, allowTrim = false) }
        .filter { keyword -> keyword !in NOISE_WORDS }
        .distinct()
        .take(2)

    /**
     * 归一化一个片段：折成一行、去掉引导词，只留 2~12 字的干净词。
     *
     * @param allowTrim 人设字段（他自己的设定，不是隐私）太长时截成短词；
     *   用户原话一律不截——长句直接丢掉，宁可少一个话题也不把对话内容往搜索框里搬。
     */
    private fun normalizeFragment(raw: String, allowTrim: Boolean): String? {
        var text = oneLine(raw)
        LEADING_WORDS.forEach { lead ->
            if (text.length > lead.length + MIN_QUERY_CHARS && text.startsWith(lead)) {
                text = text.removePrefix(lead)
            }
        }
        text = text.trim().trimEnd('的', '了', '呢', '吧', '啊', '呀')
        if (allowTrim && text.length > FRAGMENT_MAX_CHARS) text = text.take(FRAGMENT_MAX_CHARS)
        if (text.length !in MIN_QUERY_CHARS..FRAGMENT_MAX_CHARS) return null
        if (text in NOISE_WORDS) return null
        if (text.any { it == '@' || it == '#' || it == '\\' }) return null
        // 数字串（电话号、验证码、金额）一律不搜：跟隐私沾边的都不往搜索框里放。
        if (text.count { it.isDigit() } >= 3) return null
        return text
    }

    /**
     * 按 [daySeed] 做一个确定性的轮换：同一天稳定，换一天换主题。
     *
     * 主话题固定取第 [Math.floorMod] 个：种子/天数不同，主话题一定跟着换；
     * 剩下的话题顺序用固定种子的 [Random] 打散，保证同一天多次调用完全一致。
     */
    private fun <T> rotated(items: List<T>, daySeed: Int): List<T> {
        if (items.isEmpty()) return items
        val offset = Math.floorMod(daySeed, items.size)
        val rest = items.filterIndexed { index, _ -> index != offset }.toMutableList()
        val random = Random(daySeed.toLong())
        for (index in rest.indices.reversed()) {
            if (index == 0) continue
            val swap = random.nextInt(index + 1)
            val temp = rest[index]
            rest[index] = rest[swap]
            rest[swap] = temp
        }
        return listOf(items[offset]) + rest
    }

    // ------------------------------------------------------------ 判断「该不该出去看看」

    /** 超过 [quietSince] 没有任何活动，才算「一个人待着」。正好到点还不算（超过才算）。 */
    fun isIdle(lastActivityAt: Long, now: Long, quietSince: Long = DEFAULT_QUIET_SINCE): Boolean {
        if (lastActivityAt <= 0L) return true
        return now - lastActivityAt > quietSince
    }

    /** 两次自浏览至少间隔 [minGap]：刚好满 3 小时就可以再去。 */
    fun shouldBrowse(lastBrowseAt: Long, now: Long, minGap: Long = DEFAULT_MIN_GAP): Boolean {
        if (lastBrowseAt <= 0L) return true
        return now - lastBrowseAt >= minGap
    }

    // ------------------------------------------------------------ 整理搜索结果

    /**
     * 把搜索结果整理成给模型看的中文摘要：「标题 — 摘要（站点）」，每条 ≤ 120 字，
     * 最多 [limit] 条，同一个 url 只留一条。
     */
    fun digestFor(hits: List<WebSearchHit>, limit: Int = MAX_DIGEST_ITEMS): String {
        if (hits.isEmpty() || limit <= 0) return ""
        val seen = HashSet<String>()
        val lines = mutableListOf<String>()
        hits.forEach { hit ->
            if (lines.size >= limit) return@forEach
            val title = oneLine(hit.title)
            val body = oneLine(hit.summary.ifBlank { hit.snippet })
            if (title.isBlank() && body.isBlank()) return@forEach
            val key = hit.url.trim().ifBlank { title }
            if (key.isBlank() || !seen.add(key)) return@forEach
            lines += digestLine(hit)
        }
        return lines.joinToString("\n")
    }

    /** 拼一行摘要，保证总长 ≤ 120 字，且站点后缀不会被截掉。 */
    private fun digestLine(hit: WebSearchHit): String {
        val site = oneLine(hit.siteName.orEmpty()).take(SITE_MAX_CHARS)
        val suffix = if (site.isBlank()) "" else "（$site）"
        val title = oneLine(hit.title).ifBlank { TITLE_FALLBACK }
        val body = oneLine(hit.summary.ifBlank { hit.snippet })

        val headBudget = (MAX_DIGEST_LINE_CHARS - suffix.length).coerceAtLeast(MIN_HEAD_CHARS)
        val head = title.take(headBudget)
        val bodyBudget = MAX_DIGEST_LINE_CHARS - suffix.length - head.length - SEPARATOR.length
        val tail = if (body.isNotBlank() && bodyBudget > MIN_BODY_CHARS) {
            SEPARATOR + body.take(bodyBudget)
        } else {
            ""
        }
        return head + tail + suffix
    }

    /**
     * 把摘要整理成一条「共同经历 / 近况」式的记忆句，例如「他今天自己上网看了几条资讯：……」。
     * 最多留 [MAX_MEMORY_POINTS] 条要点，整句 ≤ 400 字。
     */
    fun memoryLineFor(digest: String): String {
        val points = digest.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_MEMORY_POINTS)
            .map { it.take(MEMORY_POINT_CHARS) }
            .toList()
        if (points.isEmpty()) return FALLBACK_MEMORY_LINE
        val body = points.mapIndexed { index, point -> "${index + 1}. $point" }.joinToString("\n")
        return "$MEMORY_PREFIX\n$body".take(MAX_MEMORY_LINE_CHARS)
    }

    private const val MEMORY_PREFIX = "他今天自己上网看了几条资讯："
    private const val FALLBACK_MEMORY_LINE = "他今天自己上网看了一会儿新闻，没看到什么特别值得说的。"

    /** 折成一行：换行、tab、连续空白都收成一个空格。 */
    private fun oneLine(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
            .replace(WHITESPACE_RUN, " ")
            .trim()

    private val WHITESPACE_RUN = Regex("\\s+")
}
