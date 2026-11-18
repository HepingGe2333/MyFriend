package com.yap.agentd.tools

import com.yap.agentd.llm.LlmUsage
import com.yap.agentd.proactive.ActivityAvailability
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.search.Providers
import com.yap.agentd.search.SearchException
import com.yap.agentd.search.SearchRequest
import com.yap.agentd.search.SearchLimits
import com.yap.agentd.search.WebSearchService
import com.yap.agentd.store.DiaryNote
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaUrl
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Sticker
import com.yap.agentd.store.StoredIntent
import com.yap.agentd.store.StoreJson
import com.yap.agentd.store.StorePersonaNotFoundException
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import com.yap.app.domain.ToolArgClamp
import java.time.Clock
import java.util.UUID

/**
 * 服务端工具执行器：**模型说要做什么，这里真的去做**（`LlmReplyGenerator` 的工具回路调用它）。
 *
 * 三条硬规则：
 * 1. **永远返回一条工具结果，不抛给调用方**：模型给了坏参数也要回
 *    `{"ok":false,"error":"…"}`，让它在下一轮自己纠正；抛出异常会让整轮回复挂掉。
 * 2. **数值一律夹取**（`:agentcore` 的 [ToolArgClamp]）：模型给 `minutes:3000` 不能真的
 *    让「过三天再回」落库。
 * 3. **不泄漏密钥**：`web_search` / `web_fetch` 的 provider/key 只从**服务端环境**读
 *    （与 `ToolsRoutes` 同一套 `YAP_AGENTD_SEARCH_*`），模型参数里没有 key 这一格。
 *
 * 副作用（心情/活动/记忆/日记/表情）直接写进 [FileStore] —— 服务端是唯一真相源，
 * 工具执行不需要客户端参与（`docs/design/server-authoritative.md` §3.5）。
 *
 * @param env 服务端环境变量读取器（测试注入固定表）
 */
class ToolExecutor(
    private val store: FileStore,
    private val search: WebSearchService = WebSearchService(),
    private val env: (String) -> String? = { System.getenv(it) },
    private val clock: Clock = Clock.systemDefaultZone()
) {

    /** 一次工具调用的上下文（哪个人格、哪段会话）。 */
    data class Context(val personaId: String, val sessionId: String, val userMessageId: String? = null)

    /**
     * 一次工具调用的结果。
     *
     * @param contentForModel 回灌给模型的正文（中文、短、可执行）
     * @param summary 写进 SSE `tool` 事件与 `toolTraceJson` 的一句话（给人看）
     * @param staySilent 这一轮到此为止、不落 assistant 消息（只有 `stay_silent` 会置位）
     * @param attachments 要挂到 assistant 消息上的媒体引用（`sticker.send`）
     */
    data class Outcome(
        val name: String,
        val ok: Boolean,
        val summary: String,
        val contentForModel: String,
        val staySilent: Boolean = false,
        val attachments: List<String> = emptyList(),
        /** `toolTraceJson` 里的一行（客户端 `ToolTraceItem` 同形）。 */
        val trace: ToolTrace
    )

    data class ToolTrace(
        val name: String,
        val title: String,
        val subtitle: String?,
        val detail: String,
        val ok: Boolean
    )

    /** 工具痕迹的 JSON（客户端 `ToolTraceCodec.decode` 能直接读）。 */
    fun encodeTrace(traces: List<ToolTrace>): String? {
        if (traces.isEmpty()) return null
        val items = traces.mapTo(mutableListOf<JsonValue>()) { trace ->
            val fields = linkedMapOf<String, JsonValue>(
                "name" to StoreJson.str(trace.name),
                "title" to StoreJson.str(trace.title),
                "detail" to StoreJson.str(trace.detail),
                "ok" to StoreJson.bool(trace.ok)
            )
            trace.subtitle?.takeIf { it.isNotBlank() }?.let { fields["subtitle"] = StoreJson.str(it) }
            JsonValue.Obj(fields)
        }
        return Json.stringify(JsonValue.Arr(items))
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 执行一次工具调用。**不抛异常**：不认识的工具、坏参数、上游失败都变成 `ok=false` 的结果。
     *
     * @param argumentsJson 模型给的原始参数文本（可能是坏 JSON）
     */
    fun execute(name: String, argumentsJson: String, context: Context): Outcome {
        val args = parseArgs(argumentsJson)
        return try {
            when (name.trim()) {
                ServerToolCatalog.STAY_SILENT -> staySilent(args, context)
                ServerToolCatalog.DEFER_REPLY -> deferReply(args, context)
                ServerToolCatalog.SET_MOOD -> setMood(args, context)
                ServerToolCatalog.SET_ACTIVITY -> setActivity(args, context)
                ServerToolCatalog.DIARY -> diary(args, context)
                ServerToolCatalog.REMEMBER -> remember(args, context)
                ServerToolCatalog.RECALL -> recall(args, context)
                ServerToolCatalog.WEB_SEARCH -> webSearch(args)
                ServerToolCatalog.WEB_FETCH -> webFetch(args)
                ServerToolCatalog.STICKER -> sticker(args, context)
                else -> failure(name, "不认识的工具：$name", "没有这个工具")
            }
        } catch (missing: StorePersonaNotFoundException) {
            failure(name, "人格不存在", "找不到这个人格")
        } catch (searchFailure: SearchException) {
            failure(name, describeSearchFailure(searchFailure), "没能联网")
        } catch (failure: Exception) {
            // 工具自己的 bug 也不该打挂这一轮回复：回一条「没做成」，让模型决定怎么说。
            failure(name, "${failure::class.simpleName}: ${failure.message}", "工具执行失败")
        }
    }

    // ------------------------------------------------------------------ stay_silent / defer_reply

    private fun staySilent(args: JsonValue.Obj, context: Context): Outcome {
        // 规范 §4：连续使用超过 2 轮会被强制要求回一句（否则「可以不回」会变成「一直不回」）。
        val state = store.autoState(context.personaId)
        if (state.silentLimitReached) {
            return failure(
                ServerToolCatalog.STAY_SILENT,
                "你已经连续 ${state.consecutiveSilent} 轮没说话了，这一轮必须回一句短的",
                "这轮得说点什么"
            )
        }
        val reason = args.stringOrNull("reason").orEmpty()
        store.recordSilentTurn(context.personaId)
        return Outcome(
            name = ServerToolCatalog.STAY_SILENT,
            ok = true,
            summary = "这一轮不回了",
            contentForModel = "好，这一轮就不回了（不产生任何消息）。",
            staySilent = true,
            trace = ToolTrace(
                name = ServerToolCatalog.STAY_SILENT,
                title = "没说话",
                subtitle = reason.take(40).ifBlank { null },
                detail = reason,
                ok = true
            )
        )
    }

    /**
     * `defer_reply`：写一条 `PENDING` 念头，到点由心跳变成主动消息。
     *
     * 分钟数夹到 5–720（`ToolArgClamp`），`latestAt` 是「再晚就作废」的窗口（+30 分钟，与客户端一致）。
     */
    private fun deferReply(args: JsonValue.Obj, context: Context): Outcome {
        val minutes = ToolArgClamp.clampDeferMinutes(args.intOrNull("minutes") ?: ToolArgClamp.MINUTES_DEFAULT)
        val reason = args.stringOrNull("reason").orEmpty().trim().take(80)
        val now = clock.millis()
        val earliestAt = now + minutes * 60_000L
        val intent = StoredIntent(
            id = UUID.randomUUID().toString(),
            personaId = context.personaId,
            sessionId = context.sessionId,
            kind = StoredIntent.KIND_REACH_OUT,
            earliestAt = earliestAt,
            latestAt = earliestAt + DEFER_WINDOW_MS,
            note = reason,
            hint = "",
            status = StoredIntent.STATUS_PENDING,
            createdAt = now,
            sentAt = null
        )
        store.saveIntent(intent)
        val summary = "过 $minutes 分钟再看"
        return Outcome(
            name = ServerToolCatalog.DEFER_REPLY,
            ok = true,
            summary = summary,
            contentForModel = "好，这一轮先不回；大约 $minutes 分钟后你会再想起这件事" +
                (if (reason.isNotBlank()) "（原因：${reason}）" else "") + "。",
            trace = ToolTrace(
                name = ServerToolCatalog.DEFER_REPLY,
                title = "等会儿再说",
                subtitle = summary,
                detail = reason,
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ set_mood / set_activity

    private fun setMood(args: JsonValue.Obj, context: Context): Outcome {
        val label = (args.stringOrNull("label") ?: args.stringOrNull("mood")).orEmpty().trim().take(20)
        if (label.isEmpty()) return failure(ServerToolCatalog.SET_MOOD, "缺少 label", "没写心情")
        val intensity = (args.intOrNull("intensity") ?: 3).coerceIn(1, 5)
        val valence = args.intOrNull("valence")?.coerceIn(-2, 2) ?: com.yap.agentd.store.Mood.defaultValence(label)
        val arousal = args.intOrNull("arousal")?.coerceIn(0, 5) ?: com.yap.agentd.store.Mood.defaultArousal(intensity)
        val dominance = args.intOrNull("dominance")?.coerceIn(0, 5) ?: com.yap.agentd.store.Mood.defaultDominance()
        val reason = args.stringOrNull("reason").orEmpty().trim()
        val mood = store.setMood(
            personaId = context.personaId,
            label = label,
            intensity = intensity,
            valence = valence,
            arousal = arousal,
            dominance = dominance,
            reason = reason
        )
        val summary = "心情：${mood.label}（强度 ${mood.intensity}/5）"
        return Outcome(
            name = ServerToolCatalog.SET_MOOD,
            ok = true,
            summary = summary,
            contentForModel = "记住了：你现在的心情是「${mood.label}」（强度 ${mood.intensity}/5，" +
                "愉快度 ${mood.valence} / 唤醒度 ${mood.arousal} / 掌控感 ${mood.dominance}）。",
            trace = ToolTrace(
                name = ServerToolCatalog.SET_MOOD,
                title = "心情变了",
                subtitle = mood.label,
                detail = reason,
                ok = true
            )
        )
    }

    private fun setActivity(args: JsonValue.Obj, context: Context): Outcome {
        val text = args.stringOrNull("activity").orEmpty().trim()
        if (text.isEmpty()) return failure(ServerToolCatalog.SET_ACTIVITY, "缺少 activity", "没写在忙什么")
        val minutes = ToolArgClamp.clampActivityMinutes(args.intOrNull("minutes") ?: ToolArgClamp.ACTIVITY_MINUTES_DEFAULT)
        val kind = ToolArgClamp.normalizeActivityKind(args.stringOrNull("kind"))
        val availability = ActivityAvailability.parse(args.stringOrNull("availability"))
        val activity = ActivityState.fromMinutes(kind, text, minutes, availability, clock.millis())
        store.updateActivity(context.personaId, ActivityState.encode(activity))
        val summary = "在忙：${activity.activity.take(30)}"
        return Outcome(
            name = ServerToolCatalog.SET_ACTIVITY,
            ok = true,
            summary = summary,
            contentForModel = "记下了：你接下来大约 $minutes 分钟在「${activity.activity}」" +
                "（这段时间${if (availability == ActivityAvailability.BUSY) "很忙，回复会更短更晚" else "可以回，但慢一点"}）。",
            trace = ToolTrace(
                name = ServerToolCatalog.SET_ACTIVITY,
                title = "在忙",
                subtitle = activity.activity.take(40),
                detail = "$minutes 分钟",
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ diary

    private fun diary(args: JsonValue.Obj, context: Context): Outcome {
        val action = args.stringOrNull("action").orEmpty().trim().lowercase()
        return when (action) {
            "add" -> {
                val content = args.stringOrNull("content").orEmpty().trim()
                if (content.isEmpty()) {
                    return failure(ServerToolCatalog.DIARY, "add 需要 content", "没写内容")
                }
                val now = clock.millis()
                val remindAt = args.longOrNull("remindAt")?.takeIf { it > now }
                    ?: args.intOrNull("remindInMinutes")?.let { minutes ->
                        now + minutes.coerceIn(DiaryNote.MIN_REMIND_MINUTES, DiaryNote.MAX_REMIND_MINUTES) * 60_000L
                    }
                val note = store.upsertDiaryNote(
                    personaId = context.personaId,
                    noteId = null,
                    content = content,
                    remindAt = remindAt
                )
                val summary = if (note.remindAt != null) "记下了，到点提醒你" else "记下了"
                Outcome(
                    name = ServerToolCatalog.DIARY,
                    ok = true,
                    summary = summary,
                    contentForModel = "记在备忘里了（id=${note.id}）" +
                        (note.remindAt?.let { "，到 $it 会提醒你" } ?: "") + "。",
                    trace = ToolTrace(
                        name = ServerToolCatalog.DIARY,
                        title = "写日记",
                        subtitle = note.content.take(40),
                        detail = note.content,
                        ok = true
                    )
                )
            }

            "list" -> {
                val limit = (args.intOrNull("limit") ?: DIARY_DEFAULT_LIMIT).coerceIn(1, 50)
                val notes = store.diaryNotes(context.personaId, limit = limit)
                val lines = if (notes.isEmpty()) {
                    "（备忘本是空的）"
                } else {
                    notes.joinToString("\n") { note ->
                        "- ${note.id}｜${note.content.take(60)}" +
                            (note.remindAt?.let { "（提醒：$it）" } ?: "")
                    }
                }
                Outcome(
                    name = ServerToolCatalog.DIARY,
                    ok = true,
                    summary = "翻了翻备忘（${notes.size} 条）",
                    contentForModel = "你记过这些：\n$lines",
                    trace = ToolTrace(
                        name = ServerToolCatalog.DIARY,
                        title = "翻日记",
                        subtitle = "${notes.size} 条",
                        detail = lines,
                        ok = true
                    )
                )
            }

            "remove" -> {
                val noteId = args.stringOrNull("noteId").orEmpty().trim()
                if (noteId.isEmpty()) {
                    return failure(ServerToolCatalog.DIARY, "remove 需要 noteId", "没给要撕的 id")
                }
                val deleted = store.deleteDiaryNote(context.personaId, noteId)
                Outcome(
                    name = ServerToolCatalog.DIARY,
                    ok = deleted,
                    summary = if (deleted) "撕掉了" else "没找到那条",
                    contentForModel = if (deleted) "已经撕掉 $noteId。" else "没找到 $noteId（可能已经删了）。",
                    trace = ToolTrace(
                        name = ServerToolCatalog.DIARY,
                        title = "撕日记",
                        subtitle = noteId,
                        detail = "",
                        ok = deleted
                    )
                )
            }

            else -> failure(ServerToolCatalog.DIARY, "action 只能是 add/list/remove", "日记参数不对")
        }
    }

    // ------------------------------------------------------------------ remember / recall

    private fun remember(args: JsonValue.Obj, context: Context): Outcome {
        val content = args.stringOrNull("content").orEmpty().trim()
        if (content.isEmpty()) return failure(ServerToolCatalog.REMEMBER, "缺少 content", "没写要记什么")
        val importance = ToolArgClamp.clampImportance(args.intOrNull("importance") ?: ToolArgClamp.IMPORTANCE_DEFAULT)
        val tags = args.stringListOrEmpty("tags")
        // 记忆的 importance 是 0–100（客户端列），工具参数是 1–5（给模型看的），这里换算。
        val stored = store.saveMemory(context.personaId, memoryId = null) { _, _ ->
            com.yap.agentd.store.Memory(
                id = "",
                personaId = context.personaId,
                content = content,
                importance = (importance * 20).coerceIn(0, 100),
                tags = tags,
                createdAt = 0,
                updatedAt = 0
            )
        }
        val summary = "记住了：${content.take(30)}"
        return Outcome(
            name = ServerToolCatalog.REMEMBER,
            ok = true,
            summary = summary,
            contentForModel = "记住了（id=${stored.id}）：$content",
            trace = ToolTrace(
                name = ServerToolCatalog.REMEMBER,
                title = "记住了",
                subtitle = content.take(40),
                detail = content,
                ok = true
            )
        )
    }

    private fun recall(args: JsonValue.Obj, context: Context): Outcome {
        val query = args.stringOrNull("query").orEmpty().trim()
        if (query.isEmpty()) return failure(ServerToolCatalog.RECALL, "缺少 query", "没写要找什么")
        val limit = (args.intOrNull("limit") ?: RECALL_DEFAULT_LIMIT).coerceIn(1, 12)
        // 服务端这一层没有向量检索：按关键词/子串在一份记忆里翻（够用，且完全可测）。
        val hits = store.memories(context.personaId, limit = 500)
            .filter { memory ->
                memory.content.contains(query, ignoreCase = true) ||
                    memory.tags.any { it.contains(query, ignoreCase = true) }
            }
            .sortedByDescending { it.importance }
            .take(limit)
        val text = if (hits.isEmpty()) {
            "（没翻到什么）"
        } else {
            hits.joinToString("\n") { "- ${it.content.take(80)}" }
        }
        return Outcome(
            name = ServerToolCatalog.RECALL,
            ok = true,
            summary = "想起了 ${hits.size} 条",
            contentForModel = "关于「$query」，你记得：\n$text",
            trace = ToolTrace(
                name = ServerToolCatalog.RECALL,
                title = "想了想",
                subtitle = query.take(30),
                detail = text,
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ web_search / web_fetch

    private fun webSearch(args: JsonValue.Obj): Outcome {
        val query = args.stringOrNull("query").orEmpty().trim()
        if (query.isEmpty()) return failure(ServerToolCatalog.WEB_SEARCH, "缺少 query", "没写搜索词")
        val count = ToolArgClamp.clampCount(args.intOrNull("count") ?: SearchLimits.DEFAULT_COUNT)
        val provider = Providers.normalize(env(ENV_SEARCH_PROVIDER) ?: ServerToolCatalogProvider)
        val baseUrl = env(ENV_SEARCH_BASE).orEmpty()
        val apiKey = env(ENV_SEARCH_KEY).orEmpty()
        val hits = search.search(
            SearchRequest(query = query, count = count, provider = provider, baseUrl = baseUrl, apiKey = apiKey)
        )
        val lines = if (hits.isEmpty()) {
            "（没有结果）"
        } else {
            hits.joinToString("\n") { "${it.title}｜${it.url}｜${it.snippet.take(120)}" }
        }
        return Outcome(
            name = ServerToolCatalog.WEB_SEARCH,
            ok = true,
            summary = "搜到 ${hits.size} 条",
            contentForModel = "搜索「$query」的结果：\n$lines",
            trace = ToolTrace(
                name = ServerToolCatalog.WEB_SEARCH,
                title = "联网搜索",
                subtitle = query.take(30),
                detail = lines,
                ok = true
            )
        )
    }

    private fun webFetch(args: JsonValue.Obj): Outcome {
        val url = args.stringOrNull("url").orEmpty().trim()
        if (url.isEmpty()) return failure(ServerToolCatalog.WEB_FETCH, "缺少 url", "没给链接")
        val page = search.webFetch(url, config = null)
        return Outcome(
            name = ServerToolCatalog.WEB_FETCH,
            ok = true,
            summary = "读了 ${page.text.length} 字",
            contentForModel = "《${page.title.ifBlank { page.url }}》正文：\n${page.text.take(6000)}",
            trace = ToolTrace(
                name = ServerToolCatalog.WEB_FETCH,
                title = "打开链接",
                subtitle = page.title.take(40).ifBlank { page.url.take(40) },
                detail = page.text.take(400),
                ok = true
            )
        )
    }

    // ------------------------------------------------------------------ sticker

    /**
     * `sticker`：`send` 发一张**已存在**的表情；`list` 列出最近的。
     *
     * 服务端不做 `make`（图像合成需要解码/编码 GIF，且违背「零第三方依赖」）——
     * 工具描述里也没有它，模型不该调用；万一调了，这里回一条诚实的结果而不是假装发过。
     */
    private fun sticker(args: JsonValue.Obj, context: Context): Outcome {
        val action = args.stringOrNull("action").orEmpty().trim().lowercase()
        return when (action) {
            "send" -> {
                val stickerId = args.stringOrNull("stickerId").orEmpty().trim()
                if (stickerId.isEmpty()) {
                    return failure(ServerToolCatalog.STICKER, "send 需要 stickerId", "没给表情 id")
                }
                val sticker = store.sticker(stickerId)
                    ?: return failure(ServerToolCatalog.STICKER, "没有这个表情：$stickerId", "没这个表情")
                val mediaId = store.stickerMediaId(stickerId)
                    ?: return failure(ServerToolCatalog.STICKER, "这个表情没有文件：$stickerId", "表情文件丢了")
                Outcome(
                    name = ServerToolCatalog.STICKER,
                    ok = true,
                    summary = "发了个表情",
                    contentForModel = "已经发出表情（id=${sticker.id}），这一条消息里就带着它，别再说一遍文字。",
                    attachments = listOf(MediaUrl.url(mediaId)),
                    trace = ToolTrace(
                        name = ServerToolCatalog.STICKER,
                        title = "发表情",
                        subtitle = sticker.file,
                        detail = "",
                        ok = true
                    )
                )
            }

            "list" -> {
                val limit = (args.intOrNull("limit") ?: STICKER_DEFAULT_LIMIT).coerceIn(1, 30)
                val stickers = store.stickers(context.personaId, limit = Int.MAX_VALUE)
                    .sortedWith(compareByDescending<Sticker> { it.createdAt })
                    .take(limit)
                val lines = if (stickers.isEmpty()) {
                    "（还没有表情）"
                } else {
                    stickers.joinToString("\n") { "${it.id}｜${it.mime}｜${it.width}x${it.height}" }
                }
                Outcome(
                    name = ServerToolCatalog.STICKER,
                    ok = true,
                    summary = "有 ${stickers.size} 个表情",
                    contentForModel = "你可以发的表情：\n$lines",
                    trace = ToolTrace(
                        name = ServerToolCatalog.STICKER,
                        title = "翻表情",
                        subtitle = "${stickers.size} 个",
                        detail = lines,
                        ok = true
                    )
                )
            }

            "make" -> failure(
                ServerToolCatalog.STICKER,
                "服务端还不能现做表情（只能发已有的）",
                "做不了新表情"
            )

            else -> failure(ServerToolCatalog.STICKER, "action 只能是 send/list", "表情参数不对")
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun failure(name: String, message: String, summary: String): Outcome = Outcome(
        name = name,
        ok = false,
        summary = summary,
        contentForModel = "工具调用没有成功：$message",
        trace = ToolTrace(
            name = name,
            title = "没做成",
            subtitle = summary,
            detail = message,
            ok = false
        )
    )

    /** 模型参数：坏 JSON 当成空对象（执行器会按「缺字段」回一条失败结果）。 */
    private fun parseArgs(raw: String): JsonValue.Obj {
        val text = raw.trim().ifEmpty { "{}" }
        return runCatching { Json.parse(text) as? JsonValue.Obj }.getOrNull() ?: JsonValue.Obj()
    }

    private fun JsonValue.Obj.stringOrNull(key: String): String? =
        (fields[key] as? JsonValue.Str)?.value

    private fun JsonValue.Obj.intOrNull(key: String): Int? = when (val value = fields[key]) {
        is JsonValue.Num -> value.asInt
        is JsonValue.Str -> value.value.trim().toIntOrNull()
        else -> null
    }

    private fun JsonValue.Obj.longOrNull(key: String): Long? = when (val value = fields[key]) {
        is JsonValue.Num -> value.asLong
        is JsonValue.Str -> value.value.trim().toLongOrNull()
        else -> null
    }

    private fun JsonValue.Obj.stringListOrEmpty(key: String): List<String> {
        val value = fields[key] ?: return emptyList()
        return when (value) {
            is JsonValue.Arr -> value.items.mapNotNull { (it as? JsonValue.Str)?.value?.trim()?.takeIf(String::isNotEmpty) }
            is JsonValue.Str -> value.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    companion object {
        /** `defer_reply` 的「再晚就作废」窗口（与客户端 `DeferReply` 一致：+30 分钟）。 */
        const val DEFER_WINDOW_MS: Long = 30 * 60 * 1000L

        const val DIARY_DEFAULT_LIMIT = 20
        const val RECALL_DEFAULT_LIMIT = 5
        const val STICKER_DEFAULT_LIMIT = 12

        /** 与 `ToolsRoutes` 共用同一套环境变量（key 永不出现在模型参数里）。 */
        const val ENV_SEARCH_PROVIDER = "YAP_AGENTD_SEARCH_PROVIDER"
        const val ENV_SEARCH_BASE = "YAP_AGENTD_SEARCH_BASE"
        const val ENV_SEARCH_KEY = "YAP_AGENTD_SEARCH_KEY"

        /** 没配 provider 时的默认（与 `ToolsRoutes.DEFAULT_SEARCH_PROVIDER` 一致）。 */
        val ServerToolCatalogProvider: String = Providers.MESSAGEAPI

        /** 当前会话里最后一条用户消息 id（工具上下文用得到，也方便测试断言）。 */
        fun lastUserMessageId(store: FileStore, sessionId: String): String? =
            store.latestMessages(sessionId, 20).lastOrNull { it.role == MessageRole.USER }?.id

        /** 一次工具调用大概值多少 token（用量统计的兜底；网关没报 usage 时用）。 */
        fun estimateToolTokens(content: String): Int = LlmUsage.estimate(content)

        /** 附件引用归一（工具返回的媒体引用统一成 `/v1/media/<id>`）。 */
        fun canonicalMedia(reference: String?): String? = MediaUrl.canonical(reference)

        /** 搜索错误转成一句人话（`SearchException` 的 message 里没有 key）。 */
        fun describeSearchFailure(failure: SearchException): String =
            failure.error.message.ifBlank { "搜索失败（${failure.error.kind}）" }
    }
}
