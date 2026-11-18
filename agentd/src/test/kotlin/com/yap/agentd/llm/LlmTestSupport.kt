package com.yap.agentd.llm

import com.yap.agentd.store.ApiConfig
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Persona
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue

/**
 * LLM 相关测试的共用小工具：把「客户端推送的配置」「人格的客户端字段」写得短一点。
 *
 * 刻意都用**真实存储**（临时目录）而不是 mock：配置/人格字段的形状（JSON 文本 vs 数组）
 * 正是要测的东西之一。
 */
internal fun FileStore.pushLlmConfig(
    baseUrl: String,
    model: String = "test-model",
    key: String? = "sk-test",
    id: String = "cfg-llm"
) {
    putApiConfig(
        ApiConfig(
            id = id,
            name = id,
            provider = "OPENAI",
            baseUrl = baseUrl,
            model = model,
            createdAt = 0,
            updatedAt = 0
        )
    )
    if (key != null) setApiKey(id, key)
}

/** 本机模式的 LLM 接入（配置从刚才 push 的那条读）。 */
internal fun llmAccess(store: FileStore, loopback: Boolean = true): LlmAccess =
    LlmAccess(LlmClient(), LlmConfigResolver(store, loopback))

/** 给播种人格加客户端字段（worldJson / speechStyleJson / traits…），返回更新后的人格。 */
internal fun FileStore.setPersonaExtras(
    personaId: String = FileStore.SEED_PERSONA_1_ID,
    extras: Map<String, JsonValue>
): Persona = updatePersona(personaId) { existing, _ ->
    existing.copy(extraFields = existing.extraFields + extras)
}

/** 客户端 Room 列的形状：`traits` 是「JSON 数组文本」。 */
internal fun listText(vararg values: String): JsonValue =
    JsonValue.Str(Json.stringify(JsonValue.Arr(values.mapTo(mutableListOf()) { JsonValue.Str(it) })))
