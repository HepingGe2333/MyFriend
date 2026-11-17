package com.yap.agentd

import com.yap.agentd.AgentdApi.Response
import com.yap.agentd.llm.LlmErrorKind
import com.yap.agentd.llm.LlmException
import com.yap.agentd.mood.MoodService
import com.yap.agentd.store.StoreJson
import com.yap.agentd.store.StorePersonaNotFoundException
import com.yap.app.core.json.JsonValue

/**
 * 多维情绪的判定端点（`spec/mood/README.md` §2）。
 *
 * | 方法 | 路径 | 说明 |
 * | --- | --- | --- |
 * | `POST` | `/v1/personas/{id}/mood/evaluate` | 用上一轮心情 + 最近 8 条对话判定一次，写 `moods.json` |
 *
 * 响应：
 *
 * ```json
 * {"personaId":"…","changed":true,"source":"llm",
 *  "mood":{"label":"有点累","intensity":3,"valence":-1,"arousal":2,"dominance":3,"reason":"…","updatedAt":0}}
 * ```
 *
 * `source` 说明这次是模型判定（`llm`）、失败后按时间衰减（`decay`），还是没明显变化（`unchanged`）。
 * **失败/无配置不报错**（规范 §2 最后一条）：端点仍回 200，由 `source` 说明回落方式。
 */
object MoodRoutes {

    private const val PERSONAS_PREFIX = "/v1/personas/"
    private const val MOOD_SEGMENT = "mood"

    fun matches(path: String): Boolean {
        if (!path.startsWith(PERSONAS_PREFIX)) return false
        val parts = path.removePrefix(PERSONAS_PREFIX).split('/')
        return parts.getOrNull(1) == MOOD_SEGMENT
    }

    fun handle(services: AgentdServices, method: String, rawPath: String, body: String?): Response {
        val path = rawPath.substringBefore('?')
        val parts = path.removePrefix(PERSONAS_PREFIX).split('/')
        val personaId = parts.getOrNull(0).orEmpty()
        val action = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
        return try {
            if (!ID_PATTERN.matches(personaId)) {
                return AgentdApi.errorResponse(404, "not_found", "没有这个人格")
            }
            val service = services.moodService
                ?: throw AgentdApi.ApiFailure(503, "llm_unavailable", "服务端没有启用 LLM（无法判定情绪）")
            if (action != "evaluate") {
                throw AgentdApi.ApiFailure(404, "not_found", "没有这个接口")
            }
            if (!method.equals("POST", ignoreCase = true)) {
                throw AgentdApi.ApiFailure(405, "method_not_allowed", "$PERSONAS_PREFIX{id}/mood/evaluate 只支持 POST")
            }
            evaluate(service, personaId)
        } catch (failure: AgentdApi.ApiFailure) {
            AgentdApi.errorResponse(failure.status, failure.code, failure.message ?: "请求不合法")
        } catch (missing: StorePersonaNotFoundException) {
            AgentdApi.errorResponse(404, "not_found", "没有这个人格")
        } catch (failure: LlmException) {
            val status = when (failure.kind) {
                LlmErrorKind.NOT_CONFIGURED, LlmErrorKind.INVALID_CONFIG -> 503
                else -> 502
            }
            AgentdApi.errorResponse(status, failure.code, failure.userMessage())
        } catch (unexpected: Exception) {
            AgentdApi.unexpectedFailureResponse(unexpected)
        }
    }

    private fun evaluate(service: MoodService, personaId: String): Response {
        val outcome = service.evaluate(personaId)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "personaId" to StoreJson.str(personaId),
                    "changed" to StoreJson.bool(outcome.changed),
                    "source" to StoreJson.str(outcome.source.wireValue),
                    "mood" to (outcome.mood?.let { moodJson(it) } ?: JsonValue.Null)
                )
            )
        )
    }

    private fun moodJson(mood: com.yap.agentd.store.Mood): JsonValue.Obj = mood.toJson()

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
}
