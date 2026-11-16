# 服务端权威架构（server-authoritative）

> 状态：0.12 分支实验（未升版本）。目标是把 MyFriend 从「两端各存一份、agentd 只是可选的决策服务」
> 改成「**服务端持有状态与逻辑，客户端只做 UI 与交互**」，从而让 iOS / Android 可以在**没有任何后台进程**的
> 情况下持续运行（主动消息、睡眠、心情、联网搜索都由服务端推进）。

## 1. 目标与非目标

### 目标

1. **状态在服务端**：人格、会话、消息（按 200 条一块）、记忆、念头、心情、在场状态、用量、API 配置、
   图片/头像文件，全部由 `agentd` 持久化。
2. **逻辑在服务端**：聊天编排（组提示词、调 LLM、流式回包）、工具执行（联网搜索/抓取/记忆）、
   主动会话发起（心跳、排期、免打扰、睡眠窗口）、心情演化、记忆整理，都在 `agentd` 内运行。
3. **客户端无后台**：客户端进程被杀、退到后台、系统不给后台时间，服务端照常推进；客户端下次打开时
   **补齐状态**（消息、未读、主动消息、心情）。
4. **两端同一套**：Android 与 iOS 只保留 UI、输入、通知展示、本地缓存；不再各自实现业务规则。
5. **可回退**：本地模式（现有实现）保留，作为离线/单机形态；服务端模式通过显式开关启用。

### 非目标（本阶段不做）

- **真正的系统级推送**：iOS 要「App 不在前台也能弹通知」，必须有 APNs（以及服务端的推送证书）；
  Android 同理需要 FCM 或常驻连接。**没有推送时，客户端只能在前台/下次打开时同步到主动消息**。
  本阶段先做「服务端推进 + 前台同步/SSE」，推送作为后续独立项（APNs/FCM 的证书与账号不在代码里）。
- 多用户/账号体系、端到端加密、云端多租户：仍是「一个人一台服务端」。
- 把现有 Room/SQLite 数据自动搬到服务端：先提供导出/导入接口，迁移工具后续再做。

## 2. 职责切分（改造后）

| 层 | 服务端 `agentd` | 客户端（Android / iOS） |
| --- | --- | --- |
| 状态 | 唯一真相源：SQLite 文件（服务端侧），按人格/会话/块组织 | 只读缓存 + 未发送草稿 |
| 聊天 | 组提示词、调 LLM、工具循环、流式回包、落库、写用量 | 渲染流、输入、重试、滚动 |
| 主动消息 | 心跳循环、排期、DND/睡眠判定、生成消息并落库 | 前台时订阅/拉取新消息；本地通知展示 |
| 睡眠/心情 | 服务端时钟与规则推进，落库并随状态下发 | 只展示 |
| 联网搜索 | 服务端发起（含 SearXNG/Bing/站点过滤源） | 不联网 |
| 记忆 | 提取、衰减、整理、召回 | 只展示 |
| 备份 | 服务端导出/导入 | 触发 + 选择文件 |
| 图片 | 服务端存原始文件，按需下发/上传 | 选择、上传、展示 |

## 3. 通信契约（第一阶段）

所有接口在 `agentd`，前缀 `/v1`，`Content-Type: application/json`，可选 `Authorization: Bearer <token>`。

### 3.1 状态快照

`GET /v1/state?since=<epochMillis>&personaId=<id>`

返回：

```json
{
  "serverTime": 1794500000000,
  "personas": [ { "id": "...", "name": "...", "avatarUrl": "/v1/media/...", "updatedAt": 0 } ],
  "sessions": [ { "id": "...", "personaId": "...", "title": "...", "lastMessageAt": 0,
                  "unread": 0, "lastBlockIndex": 4 } ],
  "messages": [ { "id": "...", "sessionId": "...", "blockIndex": 4, "role": "USER|ASSISTANT",
                  "content": "...", "createdAt": 0, "readAt": null, "attachments": [] } ],
  "mood": { "personaId": "...", "label": "...", "updatedAt": 0 },
  "presence": { "personaId": "...", "kind": "IDLE", "detail": "...", "updatedAt": 0 },
  "serverCursor": 12345
}
```

`since` 用于增量：只返回 `updatedAt > since` 的人格/会话，与 `createdAt > since` 的消息；`serverCursor` 是
单调递增的游标（服务端用它做「下次从这里继续」）。首次同步 `since=0` 返回全量（按块分页，见 3.2）。

### 3.2 消息分页（区块）

`GET /v1/sessions/{sessionId}/messages?beforeBlock=<n>&limitBlocks=1`

- 与本地区块模式同构：一页一块（默认 200 条），`beforeBlock` 为空时取最新一块；
- 返回 `blockIndex`、`hasMoreOlder`、`hasMoreNewer`、`messages[]`；
- 客户端不拼 offset，服务端按块表找相邻块（允许编号空洞）。

### 3.3 发送消息（流式）

`POST /v1/sessions/{sessionId}/messages`，`Accept: text/event-stream`

请求：`{"clientMessageId": "uuid", "content": "...", "attachments": ["mediaId"], "replyTo": null}`

响应（SSE）：

```
event: accepted   data: {"userMessageId":"...","createdAt":0}
event: delta      data: {"text":"..."}
event: tool       data: {"name":"web_search","status":"running|done","summary":"..."}
event: done       data: {"assistantMessageId":"...","usage":{"promptTokens":0,"completionTokens":0}}
event: error      data: {"code":"...","message":"..."}
```

- `clientMessageId` 做幂等：同一 id 重复 POST 返回同一条用户消息，不重复调 LLM；
- 中断：客户端断开 SSE 时服务端**继续**生成并落库（这是「无后台持续运行」的关键），客户端下次同步即可拿到；
- 主动消息也走同一套消息表，`role = ASSISTANT`，`source = PROACTIVE`。

### 3.4 主动消息与心跳

- `GET /v1/events?since=<cursor>`：增量事件流（新消息、心情变化、在场变化），前台用长轮询或 SSE；
- `POST /v1/proactive/tick`：调试/测试用，立即跑一次心跳判定（生产由服务端定时器驱动）；
- 客户端不参与判定：不传「现在几点」「是否免打扰」，服务端自己算。

### 3.5 联网搜索（服务端）

`POST /v1/tools/web-search`：`{"query": "...", "count": 6, "provider": "MESSAGEAPI|TAVILY|SEARXNG|BING|..."}`

新增本地/自建源：

| provider | 说明 | 请求 |
| --- | --- | --- |
| `SEARXNG` | 自建聚合搜索（推荐，无 key） | `GET {base}/search?q=&format=json&categories=general` |
| `BING` | Azure Bing Web Search | `GET https://api.bing.microsoft.com/v7.0/search?q=&count=`，`Ocp-Apim-Subscription-Key` |
| `ZHIHU` / `TOUTIAO` / `QUORA` | **站点过滤**：通过 SearXNG/Bing 加 `site:`，不直接爬站点 | 见 §3.5.1 |

#### 3.5.1 站点过滤（不绕过站点条款）

- `ZHIHU` → `q + " site:zhihu.com"`（经 SearXNG/Bing）
- `TOUTIAO` → `q + " site:toutiao.com"`
- `QUORA` → `q + " site:quora.com"`
- 返回统一结构 `{title,url,snippet}`；**不抓取登录墙后的正文**，`web_fetch` 只抓公开页面。


### 3.6 服务端 CRUD 与推送（M3–M6 契约）

以下接口补齐「客户端只做 UI」所需的写路径。统一前缀 `/v1`，JSON，沿用 token/限流/错误体。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/v1/sessions` | `{personaId, title?}` → 新建会话（一人格一会话，已存在则返回既有） |
| `POST` | `/v1/sessions/{id}/read` | `{upTo?}` → 标已读（unread=0），返回 `{unread:0, readAt}` |
| `GET` | `/v1/personas` | 人格列表（含 world/style/activity/translation 摘要） |
| `POST` | `/v1/personas` | 新建/更新人格（`id` 可空=新建）；字段与本地 schema 同义 |
| `DELETE` | `/v1/personas/{id}` | 删人格（连带会话/消息/记忆；媒体交给客户端 GC） |
| `POST` | `/v1/personas/{id}/activity` | 上报/清除当前活动（`{}` 清除） |
| `GET` | `/v1/personas/{id}/memories?limit=&since=` | 记忆列表 |
| `POST` | `/v1/personas/{id}/memories` | 新建/更新记忆 |
| `DELETE` | `/v1/memories/{id}` | 删记忆 |
| `GET` | `/v1/presets` · `POST /v1/presets` · `DELETE /v1/presets/{id}` | 人格预设（含 world/style） |
| `GET` | `/v1/settings` · `POST /v1/settings` | 全局设置（不含 API Key） |
| `GET` | `/v1/backup` | 导出备份 JSON（v5，与客户端一致） |
| `POST` | `/v1/backup` | 导入备份（merge/replace） |
| `POST` | `/v1/push/register` | `{platform: ios|android, token, bundleId/packageName}` → 注册推送 token |
| `DELETE` | `/v1/push/register` | 注销 token |

- 人格/记忆/预设的**字段名**与客户端 schema 同义（`worldJson`/`speechStyleJson`/`activityJson`/`translationJson`…），
  服务端可以有自己的存储形态，但 JSON 契约一致。
- 推送：服务端只负责「有 token 就调用推送适配器」（APNs/FCM 的具体实现作为可插拔接口，凭据在部署配置里）；
  没有凭据时降级为「客户端前台同步」。

## 4. 服务端存储

- 单文件 SQLite（`agentd --data <path>`，默认 `~/.myfriend/agentd.db`），用 JDBC（`org.xerial:sqlite-jdbc`）
  或纯文件 JSONL；**先用 SQLite**：块分页、增量查询、并发读写都更省事。
- 表：`personas`、`sessions`、`messages`（含 `blockIndex`、`source`）、`message_blocks`、`memories`、
  `moods`、`presence`、`usage`、`api_configs`、`settings`、`events`（增量游标）、`media`（文件路径+元数据）。
- 与客户端 schema **不要求逐字一致**（服务端可以有自己的列），但**语义必须一致**：`BLOCK_SIZE=200`、
  一条会话一个人格、消息删除是硬删除、备份 v4 的字段名不变。

## 5. 客户端改造路径（分阶段）

| 阶段 | 客户端 | 服务端 |
| --- | --- | --- |
| P1 | 新增 `ServerBackend`（HTTP/SSE），聊天页先走服务端；本地模式保留 | 状态快照 + 分页 + 发送流 |
| P2 | 首页/主动消息/心情/在场改为服务端下发 | 心跳循环 + 事件流 |
| P3 | 设置/人格/记忆/备份改为服务端 CRUD | 对应 CRUD + 备份导入导出 |
| P4 | 删除客户端业务逻辑与 Room（只留缓存） | 全部逻辑 |

本阶段目标：**P1 + P2 的服务端侧完成并测试**；客户端先接一条最小链路（聊天 + 状态同步）。

## 6. 失败与离线

- 服务端不可达：客户端显示「离线」，允许读缓存；发送进入待发送队列（客户端本地队列，带 `clientMessageId`），
  恢复后按顺序补发（服务端幂等）。
- 服务端重启：状态在 SQLite，重启不丢；`serverCursor` 单调，客户端按游标补齐。
- 冲突：同一 `clientMessageId` 已存在 → 返回已存在；同一会话不会出现两个客户端同时写（单人单设备为主）。

## 7. 安全

- 默认只监听 `127.0.0.1`；对外必须反代 + TLS（见 `agentd/README.md`），Token 必开。
- 服务端持有 API Key（LLM/搜索），客户端不再保存密钥（这是「客户端只做 UI」的直接结果）。
- 媒体：客户端上传/下载走 `/v1/media/{id}`，路径由服务端生成，客户端不传文件系统路径。

## 8. 进度（0.12 分支，未升版本）

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| **M1 服务端权威 P1** | 服务端持久化（文件存储、块维护、事件游标）、`/v1/state`、`/v1/sessions`、区块分页、SSE 发送（幂等 + 断开仍生成）、长轮询事件、服务端搜索（MessageAPI/Tavily/SearXNG/Bing/站点过滤）、主动引擎与 `/v1/proactive/tick` | ✅ 完成（`agentd` 244 条测试） |
| **M2 客户端传输层 P1a** | Android `data/remote/`（ServerConfig/ServerApi/SSE/映射）、iOS `Net/Server/`（同构）；配置来源、URL/明文策略、SSE 增量解析、取消关闭连接、状态映射 | ✅ 完成（Android 54 条、iOS 71 条） |
| M3 客户端接线 P1b | 首页/聊天改走服务端、离线待发队列、DI 与设置项（服务端地址/令牌）、前台事件订阅 | ✅ 本轮（两端服务端模式） |
| M4 状态迁移 P2 | 状态快照增量同步、主动消息经事件流下发、心情/在场展示；本地模式保留 | ✅ 本轮（服务端模式） |
| M5 存储迁移 P3/P4 | 人格/记忆/预设/设置/备份在服务端模式下全部走 `/v1`（本地仅缓存）；媒体压缩上传/下载/渲染；本地模式保留为离线形态 | ✅ 完成（删除客户端本地模式不在本阶段） |
| M6 推送 | 服务端 token 注册 + 可插拔 `PushSender`（默认 Noop）；**APNs/FCM 协议、证书与发送实现由服务端部署者自行配置**（客户端提供注册入口） | 🟡 按约定保留给部署者 |

已知边界（M1/M2 之后仍然成立）：

- 没有推送时，客户端进程被杀后收不到实时通知，只能下次打开时同步到主动消息；
- 服务端是单用户、单进程文件存储（无 SQL、无跨进程锁）；
- 服务端主动计数/最后互动时间尚未落盘（重启后当日计数从 0 起算，靠进程内 overlay 去重）；
- 客户端 release 包目前不能启用服务端模式（传输层配置在 release 被强制清空，等 P1b 的设置项与安全存储）。
