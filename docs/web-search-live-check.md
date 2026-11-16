# 搜索接口实测记录（0.12）

本文件记录 `web_search` 各 provider 的**真实接口调用**结果（不是 mock）。目的是把「规范里写的字段」和
「厂商实际返回的字段」对齐，避免只有单测绿、真网一调就崩。

- 时间：2026-11-15
- 代码：`dev/0.12.0`（provider 已改名 `MESSAGEAPI`，旧值 `DEEPSEEK` 仍可读）
- 测试机：macOS（直连，无代理重写）
- **不在本文件写任何密钥**；复现时用环境变量传 key

## 1. 结论

| provider | 是否实测 | 结果 | 备注 |
| --- | --- | --- | --- |
| `MESSAGEAPI`（DeepSeek 官方 tools 接口） | ✅ | 200，`results[]` 字段齐全，`fetch` 可用 | 默认厂商；任何兼容同一套 `/v1/tools/search`、`/v1/tools/fetch` 的厂商都可替换 |
| `TAVILY` | ✅ | 200，`results[]` 为 `title/url/content`（另有 `id/score/raw_content`） | 与客户端解析一致 |
| `BRAVE` | ⏸ | 未测（本机无法连通） | 解析代码按官方文档形状写，未真网验证 |
| `SERPER` | ⏸ | 未测 | 同上 |
| `GENERIC`（自建） | ⏸ | 未测（没有自建服务） | 契约：`POST {base}` → `{"results":[{title,url,snippet}]}` |

## 2. `MESSAGEAPI`（DeepSeek 官方）

请求（与 `WebSearchClient.deepSeekSearchRequest` 完全一致）：

```http
POST https://api.deepseek.com/v1/tools/search
Authorization: Bearer $DEEPSEEK_API_KEY
Content-Type: application/json

{"query":"今天北京天气","count":3,"need_content":false}
```

实际响应（截断）：

```json
{"results":[{
  "title":"北京天气预报,北京7天天气预报,…",
  "site_name":"天气网",
  "site_icon":"https://cdn.deepseek.com/site-icons/weather.com.cn",
  "authority":4,
  "rerank_score":1.95,
  "date":"2026-11-14T16:00:00+00:00",
  "url":"https://www.weather.com.cn/weather/101010100.shtml",
  "summary":"全国> 北京 > 城区 …",
  "snippet":"北京 > 城区 …",
  "content":""
}]}
```

- 命中我们的解析：`title` / `url` / `snippet` / `summary` / `content` 都在，`count` 生效（要 3 给 3）。
- 额外字段（`site_name`、`authority`、`rerank_score`、`date`）当前没有用上；`date` 可以作为后续
  「结果新鲜度」的排序输入。
- `web_fetch`：`POST /v1/tools/fetch`，body `{"url": "…"}`，返回 `{"content":"…"}`（纯文本，无 HTML 标签），
  与客户端 `parseDeepSeekFetch` 一致。
- 错误：错误 key → **HTTP 401**（客户端映射为「鉴权失败」，不把响应体回灌给模型）。

## 3. `TAVILY`

```http
POST https://api.tavily.com/search
Authorization: Bearer $TAVILY_API_KEY
Content-Type: application/json

{"api_key":"$TAVILY_API_KEY","query":"今天北京天气","max_results":3,"include_answer":false}
```

实际响应（顶层键）：`query`、`follow_up_questions`、`answer`、`images`、`results`、`response_time`、`request_id`；
`results[]` 的键：`title`、`url`、`content`、`id`、`score`、`raw_content`。

- 命中我们的解析：`results[].title/url/content`，`max_results` 生效。
- 错误 key → **HTTP 401**。
- 注意：`content` 可能很长（实测单条上万字符），客户端统一截到 500 字符后再进入工具结果。

## 4. 复现方式（不暴露 key）

```bash
export DEEPSEEK_API_KEY=...   # 本机已存的 DeepSeek 官方 key
export TAVILY_API_KEY=...
curl -sS -X POST 'https://api.deepseek.com/v1/tools/search' \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" -H 'Content-Type: application/json' \
  -d '{"query":"今天北京天气","count":3,"need_content":false}'

curl -sS -X POST 'https://api.tavily.com/search' \
  -H "Authorization: Bearer $TAVILY_API_KEY" -H 'Content-Type: application/json' \
  -d "{\"api_key\":\"$TAVILY_API_KEY\",\"query\":\"今天北京天气\",\"max_results\":3,\"include_answer\":false}"
```

## 5. 还没覆盖的

1. **Brave / Serper 真实字段**：只按文档写了解析，`web_fetch` 也没跑过真网。
2. **限流与配额**：两家都可能返回 429，客户端目前只映射成错误、不重试、不退避。
3. **结果新鲜度**：`MESSAGEAPI` 返回了 `date`，`TAVILY` 有 `score`，目前都没参与排序。
4. **非 DeepSeek 的 `web_fetch` 抓取质量**：只测过 MESSAGEAPI 的服务端 fetch；客户端抓取（去标签）只在单测里验证过。
