package com.yap.agentd

import com.yap.agentd.AgentdApi.Response
import com.yap.agentd.evolution.EvolutionService
import com.yap.agentd.evolution.PersonaEvolutionRules
import com.yap.agentd.llm.LlmErrorKind
import com.yap.agentd.llm.LlmException
import com.yap.agentd.store.PendingPatch
import com.yap.agentd.store.StoreJson
import com.yap.agentd.store.StorePersonaNotFoundException
import com.yap.app.core.json.JsonValue

/**
 * 人格进化的服务端端点（0.13）。
 *
 * | 方法 | 路径 | 说明 |
 * | --- | --- | --- |
 * | `POST` | `/v1/personas/{id}/evolution/evaluate` | 用最近对话 + 人格 + 当前情绪问一次模型，写待审补丁 |
 * | `GET`  | `/v1/personas/{id}/evolution/pending` | 待审补丁列表（顺带清掉 7 天过期的） |
 * | `POST` | `/v1/personas/{id}/evolution/{patchId}/accept` | 接受：合并四字段 + `version + 1` |
 * | `POST` | `/v1/personas/{id}/evolution/{patchId}/reject` | 拒绝：只丢补丁，人格不动 |
 *
 * 错误语义（与 `AuthorityRoutes`/`ToolsRoutes` 同一套错误体）：
 * - 404 `not_found`：人格/补丁不存在；
 * - 410 `patch_expired`：补丁已过期（7 天），已顺手删除；
 * - 503 `llm_not_configured` / `llm_invalid_config`：服务端没有可用的模型配置
 *   （进化是显式动作，不能像后台任务那样静默跳过）；
 * - 502 `llm_upstream_error` / `llm_unauthorized` / …：上游失败，`code` 沿用 [LlmErrorKind]。
 *
 * 客户端可以改为调用这些端点（本轮只做服务端接口 + 测试）。
 */
object EvolutionRoutes {

    private const val PERSONAS_PREFIX = "/v1/personas/"
    private const val EVOLUTION_SEGMENT = "evolution"

    fun matches(path: String): Boolean {
        if (!path.startsWith(PERSONAS_PREFIX)) return false
        return path.substringAfter(PERSONAS_PREFIX).split('/').getOrNull(1) == EVOLUTION_SEGMENT
    }

    fun handle(services: AgentdServices, method: String, rawPath: String, body: String?): Response {
        val path = rawPath.substringBefore('?')
        val parts = path.removePrefix(PERSONAS_PREFIX).split('/')
        // parts: [personaId, "evolution", ...]
        val personaId = parts.getOrNull(0).orEmpty()
        if (!FileStoreId.isValid(personaId)) return notFound("没有这个人格")
        val suffix = parts.drop(2).filter { it.isNotEmpty() }
        return try {
            val store = services.store
                ?: throw AgentdApi.ApiFailure(503, "state_unavailable", "服务端权威模式未启用：没有状态存储")
            val service = services.evolutionService
                ?: throw AgentdApi.ApiFailure(503, "llm_unavailable", "服务端没有启用 LLM（无法做人格进化）")
            when {
                suffix.isEmpty() -> notFound("没有这个接口")

                suffix[0] == "evaluate" -> {
                    requirePost(method, "$PERSONAS_PREFIX{id}/evolution/evaluate")
                    evaluate(service, personaId)
                }

                suffix[0] == "pending" -> {
                    requireGet(method, "$PERSONAS_PREFIX{id}/evolution/pending")
                    pending(service, personaId, store.serverTime())
                }

                suffix.size == 2 && (suffix[1] == "accept" || suffix[1] == "reject") -> {
                    requirePost(method, "$PERSONAS_PREFIX{id}/evolution/{patchId}/${suffix[1]}")
                    val patchId = suffix[0]
                    if (!FileStoreId.isValid(patchId)) return notFound("没有这条待审补丁")
                    if (suffix[1] == "accept") accept(service, personaId, patchId) else reject(service, personaId, patchId)
                }

                else -> notFound("没有这个接口")
            }
        } catch (failure: AgentdApi.ApiFailure) {
            AgentdApi.errorResponse(failure.status, failure.code, failure.message ?: "请求不合法")
        } catch (missing: StorePersonaNotFoundException) {
            AgentdApi.errorResponse(404, "not_found", "没有这个人格")
        } catch (missing: EvolutionService.PatchNotFoundException) {
            AgentdApi.errorResponse(404, "patch_not_found", "没有这条待审补丁")
        } catch (expired: EvolutionService.PatchExpiredException) {
            AgentdApi.errorResponse(410, "patch_expired", "这条待审补丁已经过期（7 天），已自动丢弃")
        } catch (failure: LlmException) {
            llmFailure(failure)
        } catch (unexpected: Exception) {
            AgentdApi.unexpectedFailureResponse(unexpected)
        }
    }

    // ------------------------------------------------------------------ 各端点

    private fun evaluate(service: EvolutionService, personaId: String): Response {
        val result = service.evaluate(personaId)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "personaId" to StoreJson.str(personaId),
                    "proposed" to StoreJson.num(result.proposed),
                    "alreadyPending" to StoreJson.bool(result.alreadyPending),
                    "summary" to StoreJson.str(result.summary),
                    "patch" to (result.pending?.let { patchJson(it) } ?: JsonValue.Null)
                )
            )
        )
    }

    private fun pending(service: EvolutionService, personaId: String, now: Long): Response {
        val patches = service.pending(personaId, now)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "personaId" to StoreJson.str(personaId),
                    "serverTime" to StoreJson.num(now),
                    "patches" to StoreJson.arr(patches.map { patchJson(it) })
                )
            )
        )
    }

    private fun accept(service: EvolutionService, personaId: String, patchId: String): Response {
        val result = service.accept(personaId, patchId)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "personaId" to StoreJson.str(personaId),
                    "patchId" to StoreJson.str(patchId),
                    "changed" to StoreJson.bool(result.changed),
                    "version" to StoreJson.num(result.version),
                    "applied" to StoreJson.arr(result.applied.map { changeJson(it) }),
                    "persona" to result.persona.toJson()
                )
            )
        )
    }

    private fun reject(service: EvolutionService, personaId: String, patchId: String): Response {
        service.reject(personaId, patchId)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "personaId" to StoreJson.str(personaId),
                    "patchId" to StoreJson.str(patchId),
                    "rejected" to StoreJson.bool(true)
                )
            )
        )
    }

    // ------------------------------------------------------------------ JSON 形状

    private fun patchJson(patch: PendingPatch): JsonValue.Obj = StoreJson.obj(
        "id" to StoreJson.str(patch.id),
        "personaId" to StoreJson.str(patch.personaId),
        "sessionId" to StoreJson.nullableStr(patch.sessionId),
        // `patchJson` 保留原始文本（客户端可能整份存下来再回放），`changes` 是解析后的视图。
        "patchJson" to StoreJson.str(patch.patchJson),
        "summary" to StoreJson.str(patch.summary),
        "reason" to StoreJson.str(patch.reason),
        "createdAt" to StoreJson.num(patch.createdAt),
        "expiresAt" to StoreJson.num(patch.expiresAt),
        "changes" to StoreJson.arr(
            PersonaEvolutionRules.decodePatch(patch.patchJson).map { changeJson(it) }
        )
    )

    private fun changeJson(change: PersonaEvolutionRules.Change): JsonValue.Obj = StoreJson.obj(
        "field" to StoreJson.str(change.field.wireValue),
        "op" to StoreJson.str(change.op.wireValue),
        "value" to StoreJson.str(change.value),
        "reason" to StoreJson.str(change.reason)
    )

    // ------------------------------------------------------------------ 小工具

    private fun llmFailure(failure: LlmException): Response {
        val status = when (failure.kind) {
            LlmErrorKind.NOT_CONFIGURED, LlmErrorKind.INVALID_CONFIG -> 503
            else -> 502
        }
        return AgentdApi.errorResponse(status, failure.code, failure.userMessage())
    }

    private fun requireGet(method: String, path: String) {
        if (!method.equals("GET", ignoreCase = true)) {
            throw AgentdApi.ApiFailure(405, "method_not_allowed", "$path 只支持 GET")
        }
    }

    private fun requirePost(method: String, path: String) {
        if (!method.equals("POST", ignoreCase = true)) {
            throw AgentdApi.ApiFailure(405, "method_not_allowed", "$path 只支持 POST")
        }
    }

    private fun notFound(message: String): Response = AgentdApi.errorResponse(404, "not_found", message)

    /** 与存储层同一套 id 规则（防路径穿越）；路由层单独放一份，避免把 FileStore 拖进纯路由。 */
    private object FileStoreId {
        private val PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValid(id: String): Boolean = PATTERN.matches(id)
    }
}
