# 本地聊天检索：用了什么、能做什么、做不到什么

这份文档回答三个问题：**本地搜索用什么实现**、**它能搜到什么**、**它明确不支持什么**。

## 1. 结论先说

- **没有用任何搜索引擎/检索库**：没有 SQLite FTS3/4/5、没有倒排索引表、没有 Elasticsearch / Meilisearch /
  Typesense，也没有第三方中文分词库。
- 用的是**纯 Kotlin（`:agentcore`，两端共用）+ SQLite/Room 的逐块扫描**：`LIKE` 粗筛 → 共享词元 →
  内存精排 → 键集游标分页。
- 数据规模定位是「一个人几年的聊天记录」（几千到十几万条），不是「全站搜索」。

## 2. 组成与源码位置

| 层 | 实现 | 位置 |
| --- | --- | --- |
| 候选枚举（哪些块可能有命中） | 一条 SQL 只取 `(sessionId, blockIndex)`，人格/时间过滤在这里完成 | Android `data/local/MessageSearchSql.kt`（`blocksQuery`）、iOS `Data/Stores.swift`（`blocksAround`/`search`） |
| 块内读取 | 每块一条 SQL（`sessionId + blockIndex + LIKE 词元`），一块最多 200 行 | 同上（`blockScanQuery`） |
| 中文词元 | 字二元组 + 虚词删除 + 全角半角/标点归一化；「项目压力」→ 项目/目压/压力 | `agentcore/.../ChineseTerms.kt`（KMP，两端同一份） |
| 排序 | 命中词元数 ↓ → relevance（加权覆盖率 + 余弦）↓ → `createdAt` ↓ → `id` ↑ | `agentcore/.../SearchRanking.kt` |
| 分页 | 键集游标，无候选上限、无 offset | `agentcore/.../BlockSearch.kt` |
| 摘要与高亮 | 60 字窗口、大小写不敏感的偏移 | 契约 `spec/search/cases/snippet.json`，两端各自实现 |
| 跨端一致性 | 16 条 snippet/ranking 用例 + 字段名 golden 测试 | `spec/search/` |

## 3. 能做什么（实测/单测覆盖的行为）

| 能力 | 说明 | 例子 |
| --- | --- | --- |
| 中文整句/子串 | 直接 `LIKE` 原文 | 搜「项目压力」命中原句 |
| 中文换说法 | 二元组召回，不要求连续 | 「项目压力」命中「项目压得我睡不着」 |
| 虚词不影响 | 查询与正文都先删「的了吗呢…」 | 「我养了猫」与「养猫」等价 |
| 全角/半角、标点 | 归一化后再匹配 | `ＡＢＣ`、`，` 与 `ABC`、`,` 等价 |
| 英文大小写 | 归一化小写 + `COLLATE NOCASE` | `Kotlin` / `kotlin` 等价 |
| 中英混排 | 同一套词元同时处理 | 「用 Kotlin 写的小工具」 |
| 人格过滤 | `personaId` 在枚举阶段过滤 | 只搜某个人 |
| 时间过滤 | `since`/`until`，块级直接跳过 | 只搜最近 7 天 |
| 高亮 | 返回命中偏移，界面加底色 | 结果列表与消息内高亮 |
| 完整结果 | 无 500 候选上限，可一直翻到底 | 2 万命中也能翻到最旧一条 |
| 稳定翻页 | 游标排序，翻页期间新增消息不重复/不乱序 | 后台来消息时继续翻页 |

## 4. 明确做不到（不是 bug，是设计边界）

1. **只搜消息正文**：不搜人格名、会话标题、记忆、念头、用量记录。跨字段检索要另做（记忆检索是另一套
   `recall` 工具，走 `memories` 表）。
2. **没有纠错/同义词/拼音**：搜「睡不著」不会命中「睡不着」；搜「xm」不会命中「项目」。要做需要词典或
   FTS 的 tokenizer。
3. **没有通配符/正则/布尔语法**：`%`、`_` 被转义成普通字符，「A AND B」不会当语法解析。
4. **没有词频/IDF 排序**：relevance 是「加权覆盖率 + 余弦」，不是 BM25；命中词元数优先于分数。
5. **每翻一页都重扫历史**：无候选上限 + 无 offset 的直接代价；10 万条、2 万命中时约 4.3 s/页
   （拆解与优化建议见 [performance.md](performance.md)）。
6. **没有增量索引**：写入不维护倒排，搜索时现算；写入路径因此很轻，代价压在搜索侧。
7. **不跨设备/不联网**：纯本地 SQLite，没有远程索引或云端同步检索。

## 5. 想升级时的路线（按代价从低到高）

1. **SQL 侧先算词元命中数**，只对能进当前页 frontier 的行算 relevance（估算 4.3 s → 0.5–0.8 s，不改 schema）。
2. **允许「近似总数」**：界面不要求精确 `totalMatches` 时，可以提前停止统计。
3. **FTS5 + 自定义 tokenizer**（schema v13）：把二元组写进 `fts5` 虚表，写入路径维护索引，搜索走索引；
   代价是迁移 + 两条写入路径 + `spec/search` 跨端一致性重做。
4. 词典分词/拼音/纠错：只有产品明确要做「模糊回忆」时才值得引入。

## 6. 怎么自己验一遍

```bash
# Android：词元、排序、块扫描、摘要的纯逻辑测试
./gradlew :app:testDebugUnitTest --tests "*ChineseTermsTest*" --tests "*SearchRankingTest*" \
  --tests "*BlockSearchTest*" --tests "*SearchSnippet*"

# iOS：同一批 spec 用例（snippet/ranking）
cd ios/YapKit && swift test --disable-sandbox --filter "SearchSpec|Snippet"
```

> 端到端（真库、10 万条）的数字见 [performance.md](performance.md)；接口层的实测见
> [web-search-live-check.md](web-search-live-check.md)（那是**联网**搜索，与本文的本地检索是两套东西）。
