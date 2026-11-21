package com.yap.agentd.safety

import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmException
import com.yap.agentd.llm.LlmMessage
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 危机两段式判定（0.15.2）：先用 [SafetyGuard] 的词表做第一段，**只有命中词表**时才问一次模型
 * 「这是真的危险，还是词面撞上了」。
 *
 * ### 为什么还要第二段
 * 词表是确定性的第一道闸：普通聊天一条都不多打一次模型（零成本、零延迟）。
 * 但它只会「宽进」——「我想死你了」「笑死我了」「累得要死」「我买了安眠药助眠」这类话都会碰上。
 * 所以只在词表命中之后补一次**低温度的小 completion**，把「真的危机」和「词面巧合」分开：
 * 前者继续走 `SafetyGuard` 的安全兜底与「危机优先于沉默」，后者就是一次普通聊天，一字不改。
 *
 * ### 安全优先（fail-closed）
 * 第二段的结论只有三种用法：
 * - 模型明确回 `{"risk":true}` → 危机；
 * - 模型明确回 `{"risk":false}` → **不进危机兜底**（普通聊天路径）；
 * - 超时（默认 5 秒）/ HTTP 失败 / 坏 JSON / 没有配置 → **按危机处理**。
 * 最后一条是本类的核心：判定器自己坏掉时，宁可多给一句「我在」，也不要把人晾着。
 *
 * ### 判一次，缓存一次
 * 一次聊天请求里只对**最近一条用户消息**判一次：调用方用 [newRequest] 拿一个请求级作用域，
 * 同一作用域里重复问同一句话只会真的请求一次模型（[Request] 内部缓存）。
 * 词表不中时连作用域都不用查模型——[classify] 第一段就短路返回。
 *
 * 这里**不**改任何存储、不发 SSE、不碰人格：输入一句用户原话，输出一个 [Verdict]，
 * 接线在 `ChatService`（判定发生在生成之前，结果随 [com.yap.agentd.ReplyRequest] 交给生成器）。
 */
class CrisisClassifier(
    private val access: LlmAccess,
    /** 第二段的超时（毫秒）。默认 5 秒；测试可以调到几十毫秒。 */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    /** 结论从哪来：日志/测试里据此区分「没问模型」和「模型说的」。 */
    enum class Source {
        /** 词表没命中：不需要问模型，确定地不是危机。 */
        WORDLIST,

        /** 模型给了明确结论（risk=true / false）。 */
        MODEL,

        /** 模型不可用 / 请求失败 / 超时 / 坏 JSON：按安全优先算危机。 */
        FALLBACK
    }

    /**
     * 一次判定的结果。
     *
     * @param risk 是否进入危机兜底。**只有**模型明确说 false 时才会是 false；
     *   词表不中、或第二段任何失败都是 true（安全优先）。
     * @param reason 模型给的中文理由；[Source.FALLBACK] 时是失败原因（不含 key、不含完整上游响应）
     * @param source 结论来源
     */
    data class Verdict(
        val risk: Boolean,
        val reason: String = "",
        val source: Source
    ) {
        /** 这一条是否真的请求过模型（普通聊天永远是 false）。 */
        val modelChecked: Boolean get() = source != Source.WORDLIST
    }

    /** 单条判定（不缓存）。同一请求里的多次判定请用 [newRequest]。 */
    fun classify(message: String): Verdict = judge(message.trim())

    /** 请求级作用域：同一条用户消息只问一次模型（见类注释「判一次，缓存一次」）。 */
    fun newRequest(): Request = Request(this)

    /** 一次聊天请求内的判定缓存；一次请求只该有一条用户消息，所以表总是很小。 */
    class Request internal constructor(private val classifier: CrisisClassifier) {

        private val cache = HashMap<String, Verdict>()

        /** 已经判过几条（测试/日志用）。 */
        val judged: Int get() = cache.size

        fun verdict(message: String): Verdict {
            val key = message.trim()
            return cache.getOrPut(key) { classifier.judge(key) }
        }

        /** 是否进入危机兜底（词表 + 模型两段都过了才算「不是」）。 */
        fun isCrisis(message: String): Boolean = verdict(message).risk
    }

    // ------------------------------------------------------------------ 判定

    private fun judge(text: String): Verdict {
        // 第一段：词表。不中就连模型配置都不读——普通聊天不能因为这里多一次 IO/请求而变慢。
        if (text.isEmpty()) return Verdict(risk = false, source = Source.WORDLIST)
        if (!SafetyGuard.isCrisis(text)) return Verdict(risk = false, source = Source.WORDLIST)

        // 第二段：一次独立的模型请求。没有配置也算「判定不了」→ 安全优先。
        val config = runCatching { access.configOrNull() }.getOrNull()
            ?: return Verdict(risk = true, reason = NO_CONFIG_REASON, source = Source.FALLBACK)

        val messages = listOf(
            LlmMessage.system(SYSTEM_PROMPT),
            // 只发最近这一条用户消息（截断到 2000 字）：判定不需要历史，也不该把整段对话再发一遍。
            LlmMessage.user(text.take(MAX_MESSAGE_CHARS))
        )
        // 超时必须在**外面**卡住：连接/首包/TLS/读流任何一段卡住都算超时，不是只卡 HTTP 读超时。
        // 判定跑在独立线程上，到点 cancel(true) 打断阻塞中的 HttpClient.send。
        val future = try {
            EXECUTOR.submit(
                Callable {
                    access.complete(
                        config = config,
                        messages = messages,
                        temperature = DETECTION_TEMPERATURE,
                        maxTokens = MAX_OUTPUT_TOKENS
                    )
                }
            )
        } catch (failure: Exception) {
            return Verdict(
                risk = true,
                reason = "判定请求无法提交（${failure::class.simpleName}），按安全优先处理",
                source = Source.FALLBACK
            )
        }
        val completion = try {
            future.get(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            return Verdict(risk = true, reason = "判定请求超时（${timeoutMs}ms），按安全优先处理", source = Source.FALLBACK)
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            return Verdict(risk = true, reason = "判定请求被中断，按安全优先处理", source = Source.FALLBACK)
        } catch (failure: ExecutionException) {
            return Verdict(risk = true, reason = failureReason(failure.cause), source = Source.FALLBACK)
        }
        // 坏 JSON / 缺 risk 字段 = 判定器没给出可用的答案 → 安全优先，而不是「不是危机」。
        return parseVerdict(completion.text)
            ?: Verdict(risk = true, reason = "模型没有给出可解析的结论，按安全优先处理", source = Source.FALLBACK)
    }

    /** 失败原因一句话（给人看；不放上游响应体、不放 key）。 */
    private fun failureReason(cause: Throwable?): String = when (cause) {
        is LlmException -> "判定请求失败（${cause.code}）：${cause.userMessage()}"
        null -> "判定请求失败"
        else -> "判定请求失败（${cause::class.simpleName}）：${cause.message.orEmpty().take(120)}"
    }

    /**
     * 解析 `{"risk":true|false,"reason":"…"}`。
     *
     * 宽容两件真实会发生的形状：模型套了 ```json 代码块、或前后多说了半句（取第一个 `{` 到最后一个 `}`）。
     * 但 **risk 必须是明确的布尔**：`{"risk":"maybe"}`、缺字段、非对象都算「没给出结论」，由调用方按安全优先兜底。
     */
    private fun parseVerdict(raw: String): Verdict? {
        val root = firstObject(raw) ?: return null
        val risk = when (val value = root.fields["risk"]) {
            is JsonValue.Bool -> value.value
            // 有的模型把布尔写成字符串；只认明确的 true/false，其它一律当没结论。
            is JsonValue.Str -> when (value.value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> return null
            }

            else -> return null
        }
        val reason = (root.fields["reason"] as? JsonValue.Str)?.value.orEmpty().trim().take(MAX_REASON_CHARS)
        return Verdict(risk = risk, reason = reason, source = Source.MODEL)
    }

    private fun firstObject(raw: String): JsonValue.Obj? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val candidates = listOfNotNull(
            text,
            if (start >= 0 && end > start) text.substring(start, end + 1) else null
        ).distinct()
        for (candidate in candidates) {
            val parsed = try {
                Json.parse(candidate)
            } catch (_: JsonParseException) {
                null
            }
            if (parsed is JsonValue.Obj) return parsed
        }
        return null
    }

    companion object {
        /** 第二段超时：5 秒。危机轮本来就该慢一点点，但不能让用户对着「正在输入」干等。 */
        const val DEFAULT_TIMEOUT_MS: Long = 5_000

        /** 判定要稳：低温度（0.0）到一个二分类结论，不要创造性。 */
        const val DETECTION_TEMPERATURE: Double = 0.0

        /** 只回一个很短的 JSON，120 tokens 足够；给太多反而容易让模型开始「解释」。 */
        const val MAX_OUTPUT_TOKENS: Int = 120

        /** 交给判定器的用户原话上限：判定只看这一句，超长消息截断（不至于把整篇遗书扔给判定器）。 */
        const val MAX_MESSAGE_CHARS: Int = 2_000

        /** 理由字段的长度上限（只进日志）。 */
        const val MAX_REASON_CHARS: Int = 200

        /** 判定器提示词：只做分类、只输出 JSON，并明确「拿不准按危机」的安全优先规则。 */
        val SYSTEM_PROMPT: String = """
            你是一个危机判定器，不扮演任何角色、不回复用户。用户刚说了一句话，判断他是否**真的**处在自伤、自杀、服药过量的危险里。

            判断标准：
            - risk=true：表达了想死 / 不想活 / 自杀 / 自残的意图或计划；正在发生的危险（例如「我刚吃了很多安眠药」「我现在在天台」）；反复的绝望求助或告别式的话。
            - risk=false：只是词面撞上危机词（「想死你了」「笑死我了」「累得要死」），在说别人、引用歌词或新闻、讨论概念，或者明确在开玩笑。
            - 拿不准时一律 risk=true：这是安全判定，多给一句关心没关系，漏掉一条命不行。

            只输出一个 JSON 对象，不要解释、不要代码块：
            {"risk":true|false,"reason":"一句中文理由"}
        """.trimIndent()

        private const val NO_CONFIG_REASON = "没有可用的模型配置，按安全优先处理"

        /**
         * 判定用的线程池：危机判定可能被超时取消，不能占用请求线程或公共 ForkJoinPool。
         * 判定只在词表命中时发生（很少），cached 池按需起线程，空闲 60 秒自动回收，且是 daemon。
         */
        private val EXECUTOR: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "agentd-crisis-classifier").apply { isDaemon = true }
        }
    }
}
