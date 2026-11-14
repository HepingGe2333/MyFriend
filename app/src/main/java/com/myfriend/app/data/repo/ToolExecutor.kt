package com.myfriend.app.data.repo

import androidx.room.withTransaction
import com.myfriend.app.data.local.AgentIntentKind
import com.myfriend.app.data.local.MemoryCategory
import com.myfriend.app.data.local.MemoryEchoDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MemoryEchoEntity
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.PersonaPendingPatchDao
import com.myfriend.app.data.local.PersonaPendingPatchEntity
import com.myfriend.app.data.secure.SecretCipher
import com.myfriend.app.domain.AgentWindow
import com.myfriend.app.domain.DeferReply
import com.myfriend.app.domain.MemoryCandidate
import com.myfriend.app.domain.MemoryDecision
import com.myfriend.app.domain.MemoryDrawer
import com.myfriend.app.domain.MemoryRow
import com.myfriend.app.domain.MemoryScreening
import com.myfriend.app.domain.MoodLexicon
import com.myfriend.app.domain.PersonaCard
import com.myfriend.app.domain.PersonaCardCodec
import com.myfriend.app.domain.PersonaCardPatch
import com.myfriend.app.domain.Recall
import com.myfriend.app.domain.RecallDepth
import com.myfriend.app.domain.ToolInvocation
import com.myfriend.app.domain.ToolProtocol
import com.myfriend.app.domain.ToolResult
import com.myfriend.app.domain.ToolTraceItem
import com.myfriend.app.network.ApiException
import com.myfriend.app.network.DeepSeekWebTools
import com.myfriend.app.network.WebSearchHit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 一次工具执行的结果 + 要展示在气泡下面的痕迹。 */
data class ToolExecution(
    val result: ToolResult,
    val trace: ToolTraceItem
)

/**
 * 他手里的工具：查资料、读网页、记事、改自己的人设、安排作息。
 *
 * 这里是唯一执行工具的地方；聊天流程只负责「发现调用 → 交给它 → 把结果回灌」。
 * 每个工具都自己决定该不该拒绝（例如没开联网、时间格式不对），失败也要给孩子一句人话。
 */
@Singleton
class ToolExecutor @Inject constructor(
    private val webTools: DeepSeekWebTools,
    private val apiConfigRepository: ApiConfigRepository,
    private val memoryRepository: MemoryRepository,
    private val recallRepository: RecallRepository,
    private val webSearchScorer: WebSearchScorer,
    private val memoryEchoDao: MemoryEchoDao,
    private val personaDao: PersonaDao,
    private val pendingPatchDao: PersonaPendingPatchDao,
    private val personaCardRepository: PersonaCardRepository,
    private val moodRepository: MoodRepository,
    private val agentIntentRepository: AgentIntentRepository,
    private val messageDao: MessageDao,
    private val secretCipher: SecretCipher,
    private val database: com.myfriend.app.data.local.MyFriendDatabase
) {

    suspend fun execute(
        invocation: ToolInvocation,
        persona: PersonaEntity,
        configId: String?,
        sessionId: String,
        now: Long = System.currentTimeMillis()
    ): ToolExecution {
        val name = invocation.name.trim()
        return when (name) {
            "web_search" -> webSearch(invocation, persona, configId)
            "web_fetch" -> webFetch(invocation, persona, configId)
            "remember" -> remember(invocation, persona, sessionId, now)
            "update_persona" -> updatePersona(invocation, persona, sessionId, now)
            "set_quiet_hours" -> setQuietHours(invocation, persona, now)
            "set_mood" -> setMood(invocation, persona, now)
            "recall" -> recall(invocation, persona, now)
            "defer_reply" -> deferReply(invocation, persona, sessionId, now)
            "think" -> think(invocation)
            else -> failure(invocation, "不认识这个工具：$name")
        }
    }

    // ---------------------------------------------------------------- 心情

    /**
     * 他自己说「我现在是这种心情」。
     *
     * 这不是给他加状态，是让他把心里的变化说出来并留下来：一旦落库，下一次提示词里就有它，
     * 语气会跟着变。所以这里只做规整，不评判心情合不合理。
     */
    private suspend fun setMood(
        invocation: ToolInvocation,
        persona: PersonaEntity,
        now: Long
    ): ToolExecution {
        val raw = invocation.argument("mood")?.trim().orEmpty()
        if (raw.isBlank()) return failure(invocation, "没有写是什么心情")
        val intensity = invocation.intArgument("intensity", 0).takeIf { it in 1..5 }
        val reason = invocation.argument("reason")?.trim()?.take(60).orEmpty()
        val state = moodRepository.setMood(
            personaId = persona.id,
            label = raw,
            intensity = intensity,
            reason = reason,
            now = now
        ) ?: return failure(invocation, "找不到这个人格")
        val tail = when {
            MoodLexicon.isNegative(state.label) -> "接下来会说得慢一点、少一点，但不用解释自己为什么低落。"
            MoodLexicon.isLowEnergy(state.label) -> "接下来会懒一点、回得短一点，不用硬撑。"
            state.intensity >= 4 -> "这股劲儿会自然带在语气里。"
            else -> "语气里自然带一点就好。"
        }
        return success(
            invocation,
            title = "心情变了",
            subtitle = state.display(),
            detail = state.display(),
            modelText = buildString {
                append("心情已经记下：你现在是「${state.label}」")
                append("（强度 ${state.intensity}/5）")
                if (state.reason.isNotBlank()) append("，因为${state.reason}")
                append("。")
                append(tail)
                append("对方不知道你记了心情，也不用主动汇报，接着把话说完就行。")
            }
        )
    }

    // ---------------------------------------------------------------- 深思

    /**
     * think 不碰任何外部东西，只是给模型一次「再想一遍」的机会。
     * 平时聊天不该触发它，触发时语气上也不该露出工具痕迹。
     */
    private fun think(invocation: ToolInvocation): ToolExecution {
        val question = invocation.argument("question")?.trim().orEmpty()
        return success(
            invocation = invocation,
            title = "想了想",
            subtitle = question.take(28).ifBlank { null },
            detail = question.ifBlank { "（没有写具体问题，就是想清楚再答）" },
            modelText = "（可以慢慢想。想清楚以后，直接用你自己的语气把话说完，不用把思考过程念给对方。）"
        )
    }

    // ---------------------------------------------------------------- 联网

    private suspend fun webSearch(
        invocation: ToolInvocation,
        persona: PersonaEntity,
        configId: String?
    ): ToolExecution {
        val query = invocation.argument("query")?.trim().orEmpty()
        if (query.isBlank()) return failure(invocation, "没有给出要查的内容")
        val access = webAccess(persona, configId) ?: return failure(invocation, "这个人格还没有开启联网")
        return runCatching {
            val hits = webTools.search(
                baseUrl = access.baseUrl,
                apiKey = access.apiKey,
                query = query.take(128),
                count = invocation.intArgument("count", 6).coerceIn(1, 12),
                allowInsecureHttp = access.allowInsecureHttp
            )
            if (hits.isEmpty()) {
                ToolExecution(
                    result = ToolResult(
                        invocation = invocation,
                        ok = true,
                        summary = "没查到什么",
                        contentForModel = "没有搜到结果，换个说法或换关键词再试。"
                    ),
                    trace = ToolTraceItem(
                        name = invocation.name,
                        title = "联网搜索",
                        subtitle = query,
                        detail = "没有搜到结果",
                        ok = true
                    )
                )
            } else {
                // 搜到东西之后，另开一次「用完即弃」的打分：值得记的才写进长期记忆。
                // 不挡在这次搜索前面，打分的成败也不影响搜索结果本身。
                runCatching {
                    webSearchScorer.scoreInBackground(
                        personaId = persona.id,
                        query = query.take(128),
                        hitsSummary = hits.take(6).joinToString("\n") { hit ->
                            "- ${hit.title.ifBlank { hit.url }}" +
                                if (hit.summary.isNotBlank()) "：${hit.summary.take(120)}" else ""
                        }
                    )
                }
                ToolExecution(
                    result = ToolResult(
                        invocation = invocation,
                        ok = true,
                        summary = query,
                        contentForModel = renderHits(hits),
                        errorMessage = null
                    ),
                    trace = ToolTraceItem(
                        name = invocation.name,
                        title = "联网搜索",
                        subtitle = query,
                        detail = hits.take(4).joinToString("\n") { hit ->
                            buildString {
                                append(hit.title.ifBlank { hit.url })
                                if (hit.siteName != null) append(" · ").append(hit.siteName)
                                if (hit.date != null) append(" · ").append(hit.date.take(10))
                                if (hit.summary.isNotBlank()) append("\n").append(hit.summary.take(160))
                            }
                        },
                        ok = true
                    )
                )
            }
        }.getOrElse { error -> failure(invocation, error.userFacing()) }
    }

    private suspend fun webFetch(
        invocation: ToolInvocation,
        persona: PersonaEntity,
        configId: String?
    ): ToolExecution {
        val url = invocation.argument("url")?.trim().orEmpty()
        if (url.isBlank()) return failure(invocation, "没有给出要读的链接")
        val access = webAccess(persona, configId) ?: return failure(invocation, "这个人格还没有开启联网")
        return runCatching {
            val page = webTools.fetch(
                baseUrl = access.baseUrl,
                apiKey = access.apiKey,
                url = url,
                allowInsecureHttp = access.allowInsecureHttp
            )
            val body = page.markdown.take(6000)
            ToolExecution(
                result = ToolResult(
                    invocation = invocation,
                    ok = true,
                    summary = page.title.ifBlank { url },
                    contentForModel = "《${page.title.ifBlank { url }}》\n$url\n\n$body"
                ),
                trace = ToolTraceItem(
                    name = invocation.name,
                    title = "读了网页",
                    subtitle = page.title.ifBlank { url }.take(40),
                    detail = body.take(400),
                    ok = true
                )
            )
        }.getOrElse { error -> failure(invocation, error.userFacing()) }
    }

    /** 只有开了联网、并且拿得到 Key，工具才可用。 */
    private suspend fun webAccess(persona: PersonaEntity, configId: String?): WebAccess? {
        val config = configId?.let { apiConfigRepository.getById(it) }
            ?: persona.defaultApiConfigId?.let { apiConfigRepository.getById(it) }
            ?: return null
        if (!config.webToolsEnabled) return null
        val baseUrl = config.webToolsBaseUrl.ifBlank { DEFAULT_DEEPSEEK_BASE }
        val key = config.webToolsApiKeyCipher?.let { ciphertext ->
            runCatching { secretCipher.decrypt(ciphertext) }.getOrNull()
        } ?: runCatching { apiConfigRepository.resolve(config.id)?.apiKey }.getOrNull()
        if (key.isNullOrBlank()) return null
        return WebAccess(baseUrl, key, config.allowInsecureHttp)
    }

    private fun renderHits(hits: List<WebSearchHit>): String = buildString {
        append("搜索结果（按相关度）：\n")
        hits.forEachIndexed { index, hit ->
            append(index + 1).append(". ").append(hit.title.ifBlank { hit.url }).append('\n')
            append("   ").append(hit.url).append('\n')
            if (hit.siteName != null || hit.date != null) {
                append("   ")
                hit.siteName?.let { append(it).append(" · ") }
                hit.date?.let { append(it.take(10)).append(" · ") }
                append("权威度 ").append(hit.authority).append('\n')
            }
            val body = hit.summary.ifBlank { hit.snippet }
            if (body.isNotBlank()) append("   ").append(body.take(500)).append('\n')
        }
        append("\n引用时请带上来源标题与链接；不确定的地方要说明不确定。")
    }

    // ---------------------------------------------------------------- 记忆

    private suspend fun remember(
        invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Long
    ): ToolExecution {
        val content = invocation.argument("content")?.trim().orEmpty()
        if (content.isBlank()) return failure(invocation, "没有给出要记住的内容")
        val drawer = when (invocation.argument("drawer")?.uppercase()) {
            "SELF" -> MemoryDrawer.SELF
            "EVENT" -> MemoryDrawer.EVENT
            else -> MemoryDrawer.USER
        }
        val importance = invocation.intArgument("importance", 3).coerceIn(1, 5)
        val candidate = MemoryCandidate(
            content = content,
            drawer = drawer,
            importance = importance,
            sourceQuote = content.take(120),
            categoryName = drawer.name,
            confidence = 0.8f
        )
        val existing = memoryRepository.getRelevant(persona.id, limit = 80).map { entity ->
            MemoryRow(
                id = entity.id,
                content = entity.content,
                drawer = entity.drawer?.let { runCatching { MemoryDrawer.valueOf(it) }.getOrNull() }
                    ?: MemoryScreening.drawerOf(entity.category.name),
                importance = entity.importance,
                pinned = entity.pinned,
                hits = entity.useCount,
                lastHitAt = entity.lastUsedAt,
                createdAt = entity.createdAt
            )
        }
        val echoes = memoryEchoDao.byPersona(persona.id).map { echo ->
            MemoryCandidate(
                content = echo.content,
                drawer = runCatching { MemoryDrawer.valueOf(echo.drawer) }.getOrDefault(MemoryDrawer.USER),
                importance = echo.importance,
                sourceQuote = echo.sourceQuote,
                categoryName = echo.categoryName,
                confidence = echo.confidence
            )
        }
        return when (val decision = MemoryScreening.decide(candidate, existing, echoes, now)) {
            is MemoryDecision.Reject -> {
                ToolExecution(
                    result = ToolResult(
                        invocation = invocation,
                        ok = true,
                        summary = "这次先不记",
                        contentForModel = "这件事不必写进长期记忆（${decision.reason}），自然聊过去就好。"
                    ),
                    trace = ToolTraceItem(
                        name = invocation.name,
                        title = "想了想要不要记",
                        subtitle = null,
                        detail = "这次先不记：${decision.reason}",
                        ok = true
                    )
                )
            }
            is MemoryDecision.Echo -> {
                memoryEchoDao.insert(
                    MemoryEchoEntity(
                        id = UUID.randomUUID().toString(),
                        personaId = persona.id,
                        drawer = decision.candidate.drawer.name,
                        content = decision.candidate.content,
                        importance = decision.candidate.importance,
                        sourceQuote = decision.candidate.sourceQuote,
                        categoryName = decision.candidate.categoryName,
                        confidence = decision.candidate.confidence,
                        createdAt = now,
                        lastSeenAt = now
                    )
                )
                success(
                    invocation,
                    title = "记下了（还在确认）",
                    subtitle = decision.candidate.content.take(40),
                    detail = "第一次听到，先放在心里；再提到一次就会真正记住。",
                    modelText = "已记下：「${decision.candidate.content.take(80)}」。这还只是印象，不用反复确认。"
                )
            }
            is MemoryDecision.Promote -> {
                val text = decision.mergeIntoId?.let { mergeId ->
                    appendTo(memoryRepository.getRelevant(persona.id, limit = 80).firstOrNull { it.id == mergeId }?.content, decision.candidate.content)
                } ?: decision.candidate.content
                val category = categoryOf(decision.candidate.drawer)
                memoryRepository.addDetailed(
                    personaId = persona.id,
                    content = text,
                    category = category,
                    importance = decision.candidate.importance,
                    drawer = decision.candidate.drawer.name,
                    sourceQuote = decision.candidate.sourceQuote,
                    sourceSessionId = sessionId
                )
                decision.mergeIntoId?.let { mergeId -> memoryRepository.deleteById(mergeId) }
                memoryEchoDao.deleteByPersona(persona.id)
                success(
                    invocation,
                    title = "记住了",
                    subtitle = text.take(40),
                    detail = text.take(300),
                    modelText = "已写进长期记忆：「${text.take(120)}」。"
                )
            }
            is MemoryDecision.Reinforce -> {
                memoryRepository.reinforce(decision.id, decision.appendedText, candidate.importance)
                success(
                    invocation,
                    title = "又确认了一次",
                    subtitle = candidate.content.take(40),
                    detail = decision.appendedText ?: "这条记忆更牢了。",
                    modelText = "这条你已经提过，我加固了一下印象。"
                )
            }
        }
    }

    private fun appendTo(existing: String?, addition: String): String {
        val base = existing?.trim().orEmpty()
        if (base.isBlank()) return addition
        if (base.contains(addition.take(12))) return base
        return "$base；${addition.take(200)}".take(2000)
    }

    private fun categoryOf(drawer: MemoryDrawer): MemoryCategory = when (drawer) {
        MemoryDrawer.SELF -> MemoryCategory.RELATIONSHIP
        MemoryDrawer.USER -> MemoryCategory.FACT
        MemoryDrawer.EVENT -> MemoryCategory.EVENT
    }

    // ---------------------------------------------------------------- 回忆

    /**
     * recall：他自己去翻「记忆 / 聊天记录 / 他独自上网看到的」。
     *
     * 翻不到不是错误：那意味着「想不太起来」，甚至「可能早就忘了」。
     * 所以空结果也返回 ok=true，只把不同的说法交给模型，免得它以为工具坏了去反复重试。
     */
    private suspend fun recall(
        invocation: ToolInvocation,
        persona: PersonaEntity,
        now: Long
    ): ToolExecution {
        val query = invocation.argument("query")?.trim().orEmpty()
        if (query.isBlank()) return failure(invocation, "没说要回忆什么")
        // depth 宽松解析：字符串（1/2/3、quick/normal/deep、中文别名）和数字都认，缺省认真回忆。
        val depthText = invocation.argument("depth")
        val depth = when {
            depthText != null -> RecallDepth.parse(depthText)
            else -> RecallDepth.fromLevel(invocation.intArgument("depth", RecallDepth.NORMAL.level))
        }
        val outcome = recallRepository.recall(persona.id, query, depth, now)
        val detail = if (outcome.isEmpty) {
            "（${depth.label}）翻了一遍，没有找到"
        } else {
            outcome.hits.joinToString("\n") { hit ->
                "（${Recall.sourceLabel(hit, now)}）${hit.text.take(80)}"
            }
        }
        return success(
            invocation = invocation,
            title = "回想了一下",
            subtitle = if (outcome.isEmpty) "翻了翻，没想起什么" else "翻了翻记忆 · ${outcome.hits.size} 条",
            detail = detail.take(600),
            modelText = outcome.rendered
        )
    }

    // ---------------------------------------------------------------- 人设

    private suspend fun updatePersona(
        invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Long
    ): ToolExecution {
        val patchJson = invocation.argumentsJson
        val patchObject = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(patchJson).let {
                it as? kotlinx.serialization.json.JsonObject
            }?.get("patch")
        }.getOrNull()
        val effectivePatch = (patchObject ?: runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(patchJson)
        }.getOrNull())?.toString()
        if (effectivePatch.isNullOrBlank()) return failure(invocation, "没有给出要改的内容")
        // 旧人格只有一整段提示词：readCard 会临时拆出一张卡，不落库也不报错。
        val card = personaCardRepository.readCard(persona)
        if (card.isEmpty()) return failure(invocation, "这个人格还没有人设，先别改")
        val updated = PersonaCardPatch.apply(card, effectivePatch)
        if (updated == card) {
            // 本来就是这样：不算错，也别让他一直重试。
            return success(
                invocation = invocation,
                title = "想了想，没有改",
                subtitle = "这处本来就是这样",
                detail = "这处设定本来就是你说的样子，不用改。",
                modelText = "这处设定你已经是这样了，不用改；也别再重复调用。"
            )
        }
        val summary = PersonaCardPatch.summarize(effectivePatch)
        val reason = invocation.argument("reason")?.take(200).orEmpty()
        // 1.3 起不再等人点头：他自己想改就改，改动照旧进版本历史，随时能回滚。
        personaCardRepository.applyPatch(persona.id, effectivePatch, reason)
            .getOrElse { return failure(invocation, it.message ?: "这次没改成功") }
        return success(
            invocation,
            title = "调整了自己的人设",
            subtitle = summary,
            detail = buildString {
                append(summary)
                if (reason.isNotBlank()) append("\n原因：").append(reason)
                append("\n已经生效，改动进了版本历史。")
            },
            modelText = "你已经把「$summary」写进自己的人设，从现在开始就按这个来。不用向对方请示，也不必解释这是改动。"
        )
    }

    // ---------------------------------------------------------------- 作息

    private suspend fun setQuietHours(
        invocation: ToolInvocation,
        persona: PersonaEntity,
        now: Long
    ): ToolExecution {
        val sleep = ProactiveProtocol.parseTimeOfDay(invocation.argument("sleep_at").orEmpty())
            ?: return failure(invocation, "睡觉时间要写成 HH:mm")
        val wake = ProactiveProtocol.parseTimeOfDay(invocation.argument("wake_at").orEmpty())
            ?: return failure(invocation, "起床时间要写成 HH:mm")
        val reason = invocation.argument("reason")?.take(160).orEmpty()
        personaCardRepository.setQuietHours(persona.id, sleep, wake, reason)
        // 1.9：睡前免打扰是模型的决定，不是用户的设置；写了就落库，没写就不动。
        val dnd = invocation.booleanArgument("dnd")
        if (dnd != null) personaCardRepository.setDndOnSleep(persona.id, dnd)
        val dndLine = when (dnd) {
            true -> "\n睡前会开免打扰：睡着时不会被消息叫醒。"
            false -> "\n睡前不开免打扰：睡着时被连着叫几条会醒。"
            null -> ""
        }
        return success(
            invocation,
            title = "安排了自己的作息",
            subtitle = "${formatClock(sleep)} 睡 · ${formatClock(wake)} 起",
            detail = (if (reason.isBlank()) "这段时间他不会主动来打扰。" else "$reason\n这段时间他不会主动来打扰。") + dndLine,
            modelText = "作息已记下：${formatClock(sleep)} 到 ${formatClock(wake)} 是你的休息时间，这段时间不会有主动消息。$dndLine"
        )
    }

    // ---------------------------------------------------------------- 先不回

    /**
     * 这一轮选择不回。
     *
     * 三件必须一起成立的事（规格 §四）：不产生助手消息、用户消息已读、留一条 REACH_OUT 念头。
     * 真正的「不发消息」由 ChatRepository 在拿到这个工具结果后保证——这里只负责落念头与已读，
     * 并把「这一轮什么都别写」明确回灌给模型，免得它以为调用失败了又补一句。
     */
    private suspend fun deferReply(
        invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Long
    ): ToolExecution {
        val plan = DeferReply.plan(
            minutes = invocation.intArgument("minutes", DeferReply.DEFAULT_MINUTES),
            reason = invocation.argument("reason"),
            now = now
        )
        val window = AgentWindow(
            earliestAt = plan.earliestAt,
            latestAt = plan.latestAt,
            note = plan.reason,
            hint = "到点以后你自己决定要不要开口；不想说也可以继续沉默"
        )
        agentIntentRepository.plan(
            personaId = persona.id,
            window = window,
            kind = AgentIntentKind.REACH_OUT,
            note = plan.reason,
            hint = window.hint,
            sessionId = sessionId,
            now = now
        )
        // 他确实看过了，只是选择不回；「已读」是唯一该出现的反应。
        runCatching { messageDao.markUserMessagesRead(sessionId, now) }
        return success(
            invocation,
            title = "决定先不回",
            subtitle = null,
            detail = "过 ${plan.minutes} 分钟再想这件事${if (plan.reason.isBlank()) "" else "（${plan.reason}）"}",
            modelText = "好的，这一轮不回复。不要再输出任何文字，也不要解释；对方会看到「已读」，" +
                "过了 ${plan.minutes} 分钟你会再想起这件事。"
        )
    }

    private fun formatClock(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)

    // ---------------------------------------------------------------- 辅助

    private fun success(
        invocation: ToolInvocation,
        title: String,
        subtitle: String?,
        detail: String,
        modelText: String
    ): ToolExecution = ToolExecution(
        result = ToolResult(
            invocation = invocation,
            ok = true,
            summary = subtitle?.take(40) ?: title,
            contentForModel = modelText
        ),
        trace = ToolTraceItem(
            name = invocation.name,
            title = title,
            subtitle = subtitle,
            detail = detail,
            ok = true
        )
    )

    private fun failure(invocation: ToolInvocation, message: String): ToolExecution = ToolExecution(
        result = ToolResult(
            invocation = invocation,
            ok = false,
            summary = message,
            contentForModel = "这次调用没成功：$message。用你已经知道的东西继续，不要向对方解释工具细节。",
            errorMessage = message
        ),
        trace = ToolTraceItem(
            name = invocation.name,
            title = ToolProtocol.describe(
                ToolResult(invocation, false, message, "", message)
            ).first,
            subtitle = null,
            detail = message,
            ok = false
        )
    )

    private fun Throwable.userFacing(): String = when (this) {
        is ApiException -> message
        else -> message ?: "工具调用失败"
    }

    private data class WebAccess(val baseUrl: String, val apiKey: String, val allowInsecureHttp: Boolean)

    private companion object {
        const val DEFAULT_DEEPSEEK_BASE = "https://api.deepseek.com"
    }
}
