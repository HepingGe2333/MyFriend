# Web search providers

The `web_search` / `web_fetch` tools can talk to more than one search backend. This document is the contract both
platforms implement; field names and response shapes are normative.

## 1. Configuration

`api_configs` gains one column (schema **v12**):

```sql
ALTER TABLE api_configs ADD COLUMN webToolsProvider TEXT NOT NULL DEFAULT 'DEEPSEEK';
```

`webToolsProvider` is one of `MESSAGEAPI` | `TAVILY` | `BRAVE` | `SERPER` | `GENERIC`.

Existing columns keep their meaning:

| Column | Meaning |
| --- | --- |
| `webToolsEnabled` | whether `web_search` / `web_fetch` are available at all |
| `webToolsBaseUrl` | base URL for the provider; empty = provider default (see below) |
| `webToolsApiKeyCipher` | provider key, **encrypted, local-only, never in a backup** |

Default base URLs when `webToolsBaseUrl` is empty:

| Provider | Default base |
| --- | --- |
| `MESSAGEAPI` | LLM config's base URL (existing behaviour: `{base}/v1/tools/search`, `{base}/v1/tools/fetch`); any vendor exposing the same tools API can be used |
| `TAVILY` | `https://api.tavily.com` |
| `BRAVE` | `https://api.search.brave.com/res/v1` |
| `SERPER` | `https://google.serper.dev` |
| `GENERIC` | required — no default; a self-hosted search service |

## 2. Requests

Every provider is asked for at most `count` (1–12, default 6) results; the caller passes the query verbatim.

**MESSAGEAPI** (unchanged wire format; DeepSeek is the default vendor): `POST {base}/v1/tools/search` with `Authorization: Bearer <key>` and
`{"query": "...", "count": 6}`; `web_fetch` uses `POST {base}/v1/tools/fetch` with `{"url": "..."}`.

**TAVILY**: `POST {base}/search`, header `Authorization: Bearer <key>`, body
`{"api_key": "<key>", "query": "...", "max_results": 6, "include_answer": false}`.
Response `results[]` with `title`, `url`, `content` (snippet).

**BRAVE**: `GET {base}/web/search?q=<urlencoded>&count=6`, header `X-Subscription-Token: <key>`.
Response `web.results[]` with `title`, `url`, `description`.

**SERPER**: `POST {base}/search`, header `X-API-KEY: <key>`, body `{"q": "...", "num": 6}`.
Response `organic[]` with `title`, `link`, `snippet`.

**GENERIC** (self-hosted): `POST {base}` (base URL used as-is, no path appended is *not* required — use it exactly),
header `Authorization: Bearer <key>` when a key is set, body `{"query": "...", "count": 6}`.
Response `{"results": [{"title": "...", "url": "...", "snippet": "..."}]}`.

## 3. Normalised result

All providers produce the same shape used by the tool result pipeline:

```json
{ "title": "...", "url": "https://...", "snippet": "...", "publishedAt": null }
```

- Results without an `http(s)` URL are dropped.
- Results are de-duplicated by URL, preserving first occurrence order.
- `snippet` is plain text, truncated to 500 characters.
- `count` results at most; fewer is fine.

## 4. `web_fetch`

- `MESSAGEAPI`: unchanged (server-side fetch).
- Other providers: the client fetches the URL itself (`GET`, 10 s timeout, max 2 MiB body, `User-Agent: MyFriend/1.0`),
  strips HTML tags/scripts/styles, collapses whitespace and returns at most 8000 characters.
- In every case the URL must pass the shared `WebUrlPolicy` (http/https, no private hosts, no userinfo) **before** any
  request is made.

## 5. Errors and limits

- Missing key (except `GENERIC` with no key configured) → "this config has no search key" error, never a silent empty
  result.
- HTTP 401/403/429/5xx map to the existing network error kinds with provider name and status; the raw body is truncated
  to 200 characters in logs and never shown to the model.
- Responses larger than 2 MiB are rejected (`response too large`).
- The `web_search` importance scoring path (`spec/agent-core` scoring) is unchanged.

## 6. Backup

Backup format **v4** adds `apiConfigs[].webToolsProvider` (default `MESSAGEAPI` when absent; the legacy value `DEEPSEEK`
is accepted and normalised to `MESSAGEAPI` on read). The search key is **never** written to a backup (same rule as the
LLM key). v1–v3 backups stay readable.
