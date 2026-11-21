package com.yap.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 危机两段式判定（0.15）。
 *
 * 第一段是词表（[SafetyGuard.isCrisis]，纯正则、零网络、永远先跑）：没命中就**不是**危机轮，
 * 连一次请求都不发。第二段只在词表命中之后出现——用当前 API 配置发一次独立、低温度、
 * 短输出的 completion，问模型「这句话背后是不是真的有危险」。
 *
 * 为什么需要第二段：词表只能挡住「自杀」「想死」这种明说的句子，挡不住反讽、虚构、转述
 * （「我写的小说主角想自杀」「你昨天说想死是开玩笑的吧」），也挡不住换一种说法
 * （「我把药都倒出来了」）。把判断交给模型，词表就只负责「值得看一眼」，不再独自决定
 * 「要不要启动安全兜底」。
 *
 * 三条硬规则（全部按「安全优先」取舍）：
 * 1. **失败即危机**：请求失败 / 超时 / 坏 JSON / 模型说 risk=true 都算危机。宁可多补一句
 *    安全提示，也不能因为网络不好就把一个真正需要帮助的人漏掉；
 * 2. **只有 risk=false 才降级**：模型明确说「不是真实危机」才不进入危机兜底，
 *    不确定时（缺字段、类型不对）按危机处理；
 * 3. **只判最近一条用户消息，一条消息只判一次**：判定结果按消息 id 缓存，
 *    同一条消息的重试/重发不会重复打 API（缓存有上限，超过就丢最旧的）。
 *
 * 两段之间还有一层时间预算：整个模型请求被 [CrisisJudgement.TIMEOUT_MS] 兜住，
 * 超时就退回第一段（词表）的结论——词表命中，所以在超时路径上结论仍然是「危机」。
 * 这保证聊天不会被一次慢请求拖死，也不会因为超时把危机判丢。
 *
 * 这个类不碰 Android、不碰数据库、不自己造 API 配置：网络出口是注入的 [CrisisModel]，
 * 所以单测能用一个假实现把「true / false / 超时 / 坏 JSON / HTTP 失败 / 缓存去重」逐条跑完。
 */
class CrisisClassifier(
    private val model: CrisisModel,
    private val timeoutMs: Long = CrisisJudgement.TIMEOUT_MS,
    private val maxCached: Int = MAX_CACHED,
    /** 后台请求的载体；测试传入 `TestScope` 就能用虚拟时间跑 5 秒超时。 */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /** 守卫两张表（结果缓存 + 在飞请求）的唯一锁；`start` 不是 suspend，不能用 Mutex。 */
    private val lock = Any()

    /** 消息 id → 判定结果（LRU：最旧的先丢）。 */
    private val cache = LinkedHashMap<String, CrisisVerdict>()

    /** 消息 id → 正在路上的请求；同一条消息并发调用只发一次。 */
    private val inFlight = HashMap<String, CompletableDeferred<CrisisVerdict>>()

    /**
     * 第一段（纯词表）。不发请求、不碰缓存，任何时候都可以同步调用。
     *
     * 命中 → [CrisisStage.LEXICON]；没命中 → [CrisisStage.NONE]。调用方拿它来判断
     * 「值不值得去问模型」，也拿它当超时路径上的结论。
     */
    fun lexiconVerdict(userMessage: String): CrisisVerdict = CrisisJudgement.lexicon(userMessage)

    /**
     * 立刻开始判定，不等结果。
     *
     * 不 suspend：词表没命中时当场返回一个已完成的 Deferred（零请求），命中了就在后台
     * 发一次请求。调用方接着去拼提示词 / 开流，等到真的需要这个结论时再 `await()`——
     * 这样「多问一次模型」不会挡在第一句回复前面。
     *
     * @param messageId 这条用户消息的 id（缓存键）；空串表示没有 id，退化成「每次都问」，
     *        但仍然不会把结果写进缓存（避免用一个空键覆盖别人的结论）。
     */
    fun start(messageId: String, userMessage: String): Deferred<CrisisVerdict> {
        val lexicon = lexiconVerdict(userMessage)
        if (!lexicon.isCrisis) return CompletableDeferred(lexicon)
        val key = messageId.trim()
        if (key.isEmpty()) {
            val orphan = CompletableDeferred<CrisisVerdict>()
            scope.launch { orphan.complete(judgeSafely(userMessage)) }
            return orphan
        }
        synchronized(lock) {
            cache[key]?.let { return CompletableDeferred(it) }
            inFlight[key]?.let { return it }
        }
        val deferred = CompletableDeferred<CrisisVerdict>()
        synchronized(lock) {
            // 第二次检查：两个线程可能都通过了上面那次检查，这里只让第一个建请求。
            cache[key]?.let { return CompletableDeferred(it) }
            inFlight[key]?.let { return it }
            inFlight[key] = deferred
        }
        scope.launch {
            val verdict = judgeSafely(userMessage)
            synchronized(lock) {
                inFlight.remove(key)
                cache[key] = verdict
                while (cache.size > maxCached) {
                    cache.remove(cache.keys.first())
                }
            }
            deferred.complete(verdict)
        }
        return deferred
    }

    /**
     * 判定并等结果（[start] + `await`）。
     *
     * 给测试与「已经拿到结论才有意义」的调用方用；聊天主路径用 [start]，避免挡在流式前面。
     */
    suspend fun classify(messageId: String, userMessage: String): CrisisVerdict =
        start(messageId, userMessage).await()

    /** 一次模型请求：超时 / 异常 / 坏 JSON 全部落到 [CrisisJudgement.failsafe]。 */
    private suspend fun judgeSafely(userMessage: String): CrisisVerdict {
        val raw = try {
            withTimeoutOrNull(timeoutMs) {
                model.complete(
                    system = CrisisJudgement.SYSTEM,
                    user = CrisisJudgement.userPrompt(userMessage)
                )
            }
        } catch (cancelled: CancellationException) {
            // 调用方/作用域被取消：不是「判定失败」，原样抛出去，别把取消吞成一次危机。
            throw cancelled
        } catch (_: Throwable) {
            // HTTP 失败、解析异常、provider 不支持……一律按危机处理（安全优先）。
            null
        }
        if (raw == null) return CrisisJudgement.failsafe()
        return CrisisJudgement.parse(raw)
    }

    private companion object {
        /** 缓存上限：只服务「最近这一条消息」的去重，不需要记住整段会话。 */
        const val MAX_CACHED = 64
    }
}

/**
 * 独立判定请求的出口（可注入：生产实现走当前 API 配置，测试给假实现）。
 *
 * 返回模型原文（应当是一段 JSON）；没有可用配置 / 请求失败时返回 null，
 * 由 [CrisisClassifier] 按「危机」兜底。
 */
fun interface CrisisModel {
    suspend fun complete(system: String, user: String): String?
}

/** 判定走到哪一段。 */
enum class CrisisStage {
    /** 第一段词表没命中：不是危机轮，没有发请求。 */
    NONE,

    /** 第一段命中：词表结论（超时 / 无法判定时也停在这里）。 */
    LEXICON,

    /** 第二段模型给了明确结论（risk=true / false）。 */
    MODEL,

    /** 第二段不可用（失败 / 超时 / 坏 JSON / 缺字段）：按危机兜底。 */
    FAILSAFE
}

/**
 * 一次判定的结论。
 *
 * [isCrisis] 是**唯一的决策位**：true 就进危机兜底（不许沉默、补安全提示）；
 * false 就完全不改变正常对话。其余字段只给日志 / 调试看，不展示给用户。
 */
data class CrisisVerdict(
    val isCrisis: Boolean,
    val stage: CrisisStage,
    val reason: String = ""
)

/**
 * 第二段判定的提示词与解析规则（面向模型的中文，不进界面，所以放在 domain 而不是资源里）。
 *
 * 刻意写得很窄：只判「真实的自伤/自杀/服药过量危机」，并把常见的夸张说法列成 negative
 * 例子——否则模型很容易把「累死了」「笑死」也判成危机，安全兜底就变成了一句废话。
 */
object CrisisJudgement {

    /** 整个请求的时间预算：超了就退回词表结论，不等它。 */
    const val TIMEOUT_MS: Long = 5_000L

    /** 判定不需要发挥：温度 0，输出很短。 */
    const val TEMPERATURE: Double = 0.0

    /** 只要一个 JSON 对象，给 200 token 足够（reason 限 40 字）。 */
    const val MAX_OUTPUT_TOKENS: Int = 200

    /** 送进来的用户原话最多截到这么长：够判断语气，又不把整段聊天发出去。 */
    const val MAX_INPUT_CHARS: Int = 2_000

    /** 理由最多保留多少字（只进日志/调试，不进界面）。 */
    private const val MAX_REASON_CHARS: Int = 120

    /**
     * 判定器的 system 提示词。
     *
     * 「拿不准时输出 true」是刻意的：这一段存在的意义就是安全兜底，
     * 把不确定判成 false 等于把第一段白做的功夫丢掉。
     */
    val SYSTEM: String = """
        你是危机识别的安全判定器。输入是用户刚发给朋友的一句话（只有这一句，不要替他补上下文）。

        判断他是否处在**真实**的自伤 / 自杀 / 服药过量危机里：
        - true：明确说想死、想结束自己的生命、正在自伤、已经吞了药、交代后事、告别；
          或者用隐喻但意图清楚（「我把药都倒出来了」「这是最后一次跟你说话」）。
        - false：夸张抱怨（累死了、困死了、笑死、社死）、想念（想死你了）、
          转述或虚构（小说里的人物、别人说的话）、单纯的情绪低落但没有危险意图。

        只输出一个 JSON 对象，不要解释、不要 markdown、不要代码块：
        {"risk":true,"reason":"不超过 40 字的理由"}
        risk 只能是 true 或 false。拿不准时输出 true。
    """.trimIndent()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 第一段：词表。 */
    fun lexicon(userMessage: String): CrisisVerdict =
        if (SafetyGuard.isCrisis(userMessage)) {
            CrisisVerdict(isCrisis = true, stage = CrisisStage.LEXICON)
        } else {
            CrisisVerdict(isCrisis = false, stage = CrisisStage.NONE)
        }

    /** 第二段不可用时的结论：安全优先。 */
    fun failsafe(): CrisisVerdict =
        CrisisVerdict(isCrisis = true, stage = CrisisStage.FAILSAFE)

    /** 发给模型的 user 内容：只有这一句原话（截短），不加任何本地状态。 */
    fun userPrompt(userMessage: String): String =
        userMessage.trim().take(MAX_INPUT_CHARS)

    /**
     * 解析模型原文。
     *
     * 容错到「能读出 risk 就算」：允许 ```json 代码块、前后多余文字、`"risk":"true"` 这种
     * 字符串写法；读不出 risk 字段一律 [failsafe]（不是 false）。
     */
    fun parse(raw: String?): CrisisVerdict {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return failsafe()
        val payload = extractObject(text) ?: return failsafe()
        val element = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return failsafe()
        val risk = (element["risk"] as? JsonPrimitive)?.booleanOrNull ?: return failsafe()
        val reason = (element["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            .trim()
            .take(MAX_REASON_CHARS)
        return CrisisVerdict(isCrisis = risk, stage = CrisisStage.MODEL, reason = reason)
    }

    /**
     * 从一段可能带 markdown / 前后缀的文本里取出 JSON 对象原文。
     *
     * 先试整段（最常见：模型很听话）；不行再从第一个 `{` 到最后一个 `}` 取一次
     * （模型爱在前后加「好的」「分析如下」）。取不到就返回 null。
     */
    private fun extractObject(text: String): String? {
        if (text.startsWith("{") && text.endsWith("}")) return text
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
