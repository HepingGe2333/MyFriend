package com.yap.agentd

import com.yap.agentd.AgentdApi.ApiFailure
import com.yap.agentd.AgentdApi.Response
import com.yap.agentd.store.ApiConfig
import com.yap.agentd.store.StoreJson
import com.yap.agentd.store.StoreJson.optString
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue

/**
 * 客户端把「本机已经配好的模型配置」推给服务端（仅本机部署）。
 *
 * | 方法 | 路径 | 说明 |
 * | --- | --- | --- |
 * | `POST` | `/v1/llm-config` | body `{baseUrl, model, apiKey, provider?}`；key 写入 `secrets.json`（0600），不回显 |
 *
 * 安全：只有服务端监听回环地址时才启用（`AgentdServices.allowClientLlmConfig`）。
 * 远程部署一律 403：远程服务器必须由部署者用 `--llm-*` / `YAP_LLM_*` 自己配置，
 * 不能让任何客户端把 key 推到公网服务上。
 */
object LlmConfigRoutes {

    const val PATH = "/v1/llm-config"

    fun matches(path: String): Boolean = path == PATH

    fun handle(services: AgentdServices, method: String, body: String?): Response {
        if (!services.allowClientLlmConfig) {
            throw ApiFailure(
                403,
                "remote_config_disallowed",
                "远程部署不接受客户端推送的模型配置；请用 --llm-base-url / --llm-key / --llm-model 配置服务端"
            )
        }
        val store = services.store ?: throw ApiFailure(503, "state_unavailable", "服务端尚未启用数据目录")
        if (!method.equals("POST", ignoreCase = true)) {
            throw ApiFailure(405, "method_not_allowed", "$PATH 只支持 POST")
        }
        val root = try {
            Json.parse(body.orEmpty()) as? JsonValue.Obj
        } catch (_: Exception) {
            null
        } ?: throw ApiFailure(400, "invalid_json", "请求体必须是 JSON 对象")

        val baseUrl = root.optString("baseUrl", "body").orEmpty().trim().trimEnd('/')
        val model = root.optString("model", "body").orEmpty().trim()
        val apiKey = root.optString("apiKey", "body").orEmpty().trim()
        val provider = root.optString("provider", "body").orEmpty().trim().ifEmpty { "OPENAI" }.uppercase()
        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            throw ApiFailure(400, "invalid_argument", "baseUrl / model / apiKey 都不能为空")
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw ApiFailure(400, "invalid_argument", "baseUrl 必须是 http(s) 地址")
        }
        if (provider !in setOf("OPENAI", "GEMINI", "CLAUDE", "DEEPSEEK")) {
            throw ApiFailure(400, "invalid_argument", "provider 不认识：$provider")
        }
        val config = store.putApiConfig(
            ApiConfig(
                id = CONFIG_ID,
                name = "客户端推送的配置",
                provider = provider,
                baseUrl = baseUrl,
                model = model,
                createdAt = 0,
                updatedAt = 0
            )
        )
        store.setApiKey(CONFIG_ID, apiKey)
        return Response(
            200,
            StoreJson.obj(
                "ok" to StoreJson.bool(true),
                "id" to StoreJson.str(config.id),
                "provider" to StoreJson.str(config.provider),
                "baseUrl" to StoreJson.str(config.baseUrl),
                "model" to StoreJson.str(config.model)
            ).let { Json.stringify(it) }
        )
    }

    /** 固定 id：一个本机服务端只保存一份「客户端配置」，重复推送是覆盖而不是堆积。 */
    const val CONFIG_ID = "client-pushed"
}
