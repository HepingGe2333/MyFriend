package com.yap.agentd

import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Persona
import com.yap.agentd.store.StoreJson
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue

/**
 * 人格的 HTTP 形状（`GET /v1/state`、`/v1/personas` 的 CRUD 响应共用一份）。
 *
 * 为什么单独一个小对象：0.13 起人格响应 = 磁盘形状 + 解析后的 `activity`；0.14.1 又多了
 * `blockProposalJson`（`spec/experience/README.md` §9）。这两处以前各抄了一份 persona→JSON，
 * 加字段时很容易只改一边（客户端在 `/v1/state` 上看得到、在 CRUD 回包上看不到）。
 *
 * `blockProposalJson` 是**规范 §9 定死的 7 字段 JSON 字符串**（与客户端
 * `personas.blockProposalJson` 同一列同形）；没有未过期的提议时是 `null`。
 * 这里读的是存储里的提议（`block_proposals.json`），不写盘、不改状态。
 */
internal object PersonaJson {

    fun of(store: FileStore, persona: Persona, now: Long = store.serverTime()): JsonValue.Obj {
        val fields = LinkedHashMap(persona.toJson().fields)
        fields["activity"] = ActivityState.decode(persona.activityJson)?.toJson() ?: JsonValue.Null
        val proposal = store.blockProposal(persona.id)?.takeIf { !it.isExpired(now) }
        fields["blockProposalJson"] = if (proposal == null) {
            JsonValue.Null
        } else {
            JsonValue.Str(Json.stringify(proposal.toJson()))
        }
        return StoreJson.obj(fields)
    }
}
