# Roadmap 1.5：记忆会变旧，也会被想起

> **历史设计记录**：本文是当时那一版的行为规格与验收表，保留下来供追溯设计取舍；
> 当前行为以 [README](../../README.md)、[ARCHITECTURE](../ARCHITECTURE.md) 与代码为准。


> 注：本文记录的是当时版本的设计与实现；「原生 Harness」模式已在 1.8 移除，只保留无状态兼容，相关描述不再适用于当前代码。

1.4 给了他一层会自己变的心情；但长期记忆还是「只增不减」的：记得越多，抽屉越满，重要的和随口一提的挤在一起，而且永远占着上下文。

1.5 做的是**让记忆像记忆**：每条记忆有状态与分量，会随时间沉下去、被移出上下文，只有他认真回忆时才翻得到；真正不重要的会被彻底忘掉——那是真删，不是软删除。同时，判断「什么值得记」这件事只有一套判据：随手整理对话、每天整理记忆、上网看完东西打分，用的是同一段 `ImportanceScoring.SHARED_CRITERIA`。

这一版只做记忆：不碰回复节奏、不加新工具以外的能力，界面改动只有记忆页与设置页两处。

## 一、目标

- **每天整理一次，不需要你确认**：凌晨 4 点左右（本地时间，人基本都睡了）在后台跑一次，留下重要的、把零碎并起来、把不重要的移出上下文。装上 App 不会立刻跑，第一次也等到下一个 4 点。
- **整理是一次「用完即弃的分叉会话」**：不是聊天。它自己拼一份请求（只有 system + user 两条消息），不建会话、不写 message 表、不给工具、不带历史，调用一结束就把请求上下文丢掉——所以你在界面上永远看不到一条「整理对话」。
- **记忆有状态，也有分量**：`ACTIVE`（还在用）/ `DORMANT`（沉下去了）/ `ARCHIVED`（已归档），外加一个 0..1 的 `strength`。时间和整理会让分量变小，先掉到线下的先沉、先后退。
- **三档回忆**：`recall(query, depth)` 的 depth 1 / 2 / 3。浅了只翻还在用的，深了连归档和旧聊天一起翻。
- **彻底遗忘是真删**：分量极低、并且半年没人提起过的记忆，直接从库里删掉，不留墓碑。
- **一套判据，三处共用**：对话抽取记忆、每日整理、联网看完打分都读 `SHARED_CRITERIA`，不会出现「聊天里值得记、看完网页不值得记」这种自相矛盾。
- **看得见，但不打扰**：记忆页一张「记忆的健康」卡片、列表行尾一点点标记、设置页两个开关；没有确认弹窗，没有红色警告。

## 二、用户看到什么

- **记忆页顶部多一张「记忆的健康」卡片**：三个数字——还在用、沉下去了、已归档；一行小字说明归档的分记忆「不参与上下文，只有他认真回忆（recall 3）时才可能翻到」；一行「上次整理：昨天 04:10 · 留下 12 条、并掉 2 条、移出 3 条、忘掉 1 条」（没有记录时写「还没整理过」）。
![记忆的健康与上次整理](../screenshots/memory-health.png)

- **卡片右下角有一个「现在整理一次」**：点了之后按钮先变「排队中…」，真的开跑才变「整理中…」并禁用，跑完弹一句 snackbar：「整理完了：留下 12 条，并掉 2 条，移出 3 条，忘掉 1 条」。没有确认框，没有失败红屏——失败也只是一句「这次没能整理，明天它自己会再试一次」；排队超过一分钟还没轮到，会自己收场并说「这次没跑起来，再点一次试试」，不会永远转圈。
- **列表里的记忆分三种样子**：还在用的正常显示；沉下去的整张卡降对比度、行尾标「沉睡」；归档的更淡一些、行尾标「归档」，并在卡片底部多一句小字「只有他认真回忆时才可能翻到」。被固定的一直显示「一直记着」。
![三种状态与「他自己上网看到的」](../screenshots/memory-tiers.png)

- **设置页「AI 与记忆」多两个开关**：
  - 「每天整理记忆」——副标题：留下重要的、把不重要的移出上下文。他会在后台自己整理，不需要你确认；
  - 「联网时判断重要性」——副标题：他看完网页会先在一边评估值不值得记，值得的才写进记忆。
  两个开关都直接落库（DataStore），写失败就退回原来的样子，并提示一句。
![三档回忆的命中与来源](../screenshots/recall-trace.png)

- 界面上不出现「ACTIVE / DORMANT / ARCHIVED」「strength」这些词，也没有手动改状态的入口——状态是他自己整理出来的，不是一张表单。

## 三、实现地图

### 1. 领域层（纯 Kotlin，不依赖 Android）

| 文件 | 内容 |
| --- | --- |
| [`MemoryLifecycle.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/MemoryLifecycle.kt) | 三档状态与阈值（沉底 0.45、归档 0.20、彻底遗忘 0.08 且 180 天没被提起）、每天的衰减与回血、钉住的下限 0.55、单次最多补算 14 天 |
| [`ImportanceScoring.kt`](../../app/src/main/java/com/yap/app/domain/ImportanceScoring.kt) | `SHARED_CRITERIA`（什么值得记 / 不值得记 / 重要度 1–5）；整理器的 system 提示词与 JSON 容错解析；联网打分的提示词与解析 |
| [`Recall.kt`](../../app/src/main/java/com/yap/app/domain/Recall.kt) | 三档 recall（`QUICK` / `NORMAL` / `DEEP`）、排序与中文段落渲染、depth 的宽松解析（数字 / 英文 / 中文别名） |

关键数字都写成常量并有单测：衰减是**线性简化**（每天掉 4.5% 左右、重要度越高掉得越慢），被用过的会回血，不是玄学曲线。

### 2. 持久化

Room v5 → **v6**（`MIGRATION_5_6`）：

- `memories` 加四列：`status`（默认 `ACTIVE`）、`strength`（默认 1.0）、`archivedAt`、`consolidatedAt`；
- 新表 `memory_consolidations`：每次整理一行留痕（人格、时间、留下 / 并掉 / 移出 / 忘掉各几条、一句话总结、模型名），对应 `MemoryConsolidationDao.observeRecent(personaId, limit)`。

`MemoryDao` 跟着分成两种读法：`getForPrompt` 只取 `status != 'ARCHIVED'` 的（归档永不进上下文），`getAllForPersona` 取全部（整理与回忆要用）。

### 3. 每日整理：一次用完即弃的分叉会话

[`MemoryConsolidator.kt`](../../app/src/main/java/com/yap/app/data/repo/MemoryConsolidator.kt) 是整理器本体：

1. 读设置：记忆关着 / 整理关着 → 直接返回 `DISABLED`，不动数据、不写留痕；
2. 取这个人格最多 300 条记忆，拼一份**一次性请求**：`system` = 整理器提示词，`user` = 编号清单（抽屉 / 重要度 / 被提起次数 / 多久没动 / 状态 / 正文）+ 最近 8 句聊天摘要 + 他最近独自上网看到的 digest。**只有这两条消息**，没有会话、没有历史、没有工具；温度 0.25，输出上限 900 token；
3. 模型只负责下判断，回一个 JSON：`keep / merge / archive / forget / raise` + 一句话总结；
4. 本类负责按判断改库，顺序固定 `raise → keep → merge → archive → forget`（先加固合并，再归档删除，避免「先删了后面还想合并它」）；模型重复列同一编号也只处理一次；
5. 模型没提到的记忆，交给时间：`MemoryLifecycle.dailyUpdate` 变淡 → 沉底 → 归档 → 很久以后忘掉；
6. 无论模型有没有给操作，都往 `memory_consolidations` 写一行留痕，并标记原生 Harness 会话需要重新同步（记忆变了，同步内容也变了）。

`Forgot` / `forget` 走的是 `memoryDao.deleteById(...)`——**真删**，不是把状态改成 `FORGOTTEN`。

### 4. 调度：每天 4 点，一个周期任务

[`MemoryConsolidationWorker.kt`](../../app/src/main/java/com/yap/app/data/repo/MemoryConsolidationWorker.kt)（`@HiltWorker`）：

| 关注点 | 取值 |
| --- | --- |
| 周期 | 24 小时 + 15 分钟弹性窗口，只在有网时跑 |
| 首次延迟 | 最近的下一个本地凌晨 4 点，至少 2 小时（刚装完不烧额度） |
| 周期任务名 | `WORK_NAME` = `yap-memory-consolidation` |
| 手动任务名 | `WORK_NAME_MANUAL` = `yap-memory-consolidation-now`（分开，手动的 `REPLACE` 不会把每天那次顶掉） |
| 开关关着 | 空跑一次，返回 `STATUS_DISABLED`，什么都不写 |
| 出错 | 返回 `STATUS_FAILED` 而不是重试——整理失败不该反复烧额度 |
| 回传 | `KEY_STATUS / KEY_PERSONAS / KEY_KEPT / KEY_MERGED / KEY_ARCHIVED / KEY_FORGOTTEN` |

入口三个：`schedule(context)`（幂等，排周期）、`enqueueOneTime(context)`（手动一次）、`cancel(context)`（开关关掉时连待跑的手动任务一起撤）。App 启动时在 `YetAnotherPalApplication` 里排一次；设置页开关变化时，打开就 `schedule`，关掉就 `cancel`。

### 5. 回忆：三档深度

工具 `recall(query, depth)`，depth 认数字 1/2/3，也认「随口想想 / 认真回忆 / 翻箱倒柜」。真正的取数在 [`RecallRepository.kt`](../../app/src/main/java/com/yap/app/data/repo/RecallRepository.kt)：

| 档位 | 名字 | 能看到 | 还会翻什么 | 字数 / 条数上限 |
| --- | --- | --- | --- | --- |
| 1 | 随口想想 | 只 `ACTIVE` | 不搜聊天、不带浏览 | 1200 字 / 6 条 |
| 2 | 认真回忆 | `ACTIVE` + `DORMANT` | 关键词搜聊天 + 他独自浏览的摘要 | 3000 字 / 12 条 |
| 3 | 翻箱倒柜 | 含 `ARCHIVED` | 再扫一遍最近消息 + 浏览摘要 | 6000 字 / 24 条 |

翻出来的按「关键词命中 + 新近程度 + 重要度」排序，写成一段中文给人格看，并顺手记下「这些记忆又被用到了」（回血，免得被聊到的事还被时间沉掉）。

### 6. 联网也共用一套判据

[`WebSearchScorer.kt`](../../app/src/main/java/com/yap/app/data/repo/WebSearchScorer.kt)：工具看完网页后，把摘要丢进一个**后台**的一次性调用，用 `ImportanceScoring.SHARED_CRITERIA` 判断「值不值得记」，值得（`importance >= 3`）才写成一条记忆，并按 SELF / USER / EVENT 归抽屉。

打分是后台跑的，不挡搜索结果返回；开关是 `AppSettings.webSearchScoringEnabled`，关掉就完全不问模型。

### 7. 界面

| 位置 | 代码 | 做了什么 |
| --- | --- | --- |
| 记忆页健康卡片 | [`MemoryScreen.kt`](../../app/src/main/java/com/yap/app/ui/screens/memory/MemoryScreen.kt)、[`MemoryViewModel.kt`](../../app/src/main/java/com/yap/app/ui/screens/memory/MemoryViewModel.kt) | `tierCounts` 三档数量、`lastConsolidation` 最近留痕、`consolidating` 是否在跑；卡片含「现在整理一次」 |
| 手动整理的触发与结果 | 同上 | `runConsolidationNow()` → `MemoryConsolidationWorker.enqueueOneTime(context)`；盯 `WorkManager.getWorkInfosForUniqueWorkFlow(WORK_NAME_MANUAL)`，完成时读 `outputData` 拼中文 snackbar |
| 文案 | [`MemoryHealthText.kt`](../../app/src/main/java/com/yap/app/ui/screens/memory/MemoryHealthText.kt) | 「上次整理：昨天 04:10 · …」「整理完了：…」的拼接（纯 Kotlin，单测 9 个） |
| 列表状态标记 | `MemoryScreen.kt` 的 `MemoryCard` | 沉睡 / 归档降对比度 + 行尾小字；归档卡片底部一句说明；pinned 显示「一直记着」 |
| 设置开关 | [`SettingsScreen.kt`](../../app/src/main/java/com/yap/app/ui/screens/settings/SettingsScreen.kt)、[`SettingsViewModel.kt`](../../app/src/main/java/com/yap/app/ui/screens/settings/SettingsViewModel.kt) | 「每天整理记忆」落库成功后 `schedule` / `cancel`；「联网时判断重要性」只落库；两者写失败都写回旧值 |

### 8. 版本

`versionCode 6 / versionName "1.5.0"`，数据库版本 6。

## 四、验收

| 项 | 怎么验证 | 状态 |
| --- | --- | --- |
| 每天整理一次，后台自己跑 | 装上后 `adb shell dumpsys jobscheduler` 能看到周期任务；或在设置里点「现在整理一次」，整理完「上次整理」那行立刻变成「今天 HH:mm」并有四类计数 | ✅ |
| 不联网不烧额度 / 关掉就空跑 | 关掉「每天整理记忆」后手动触发一次：snackbar 说「还没整理：记忆整理现在是关着的」，`memory_consolidations` 不新增行 | ✅ |
| 整理是「用完即弃的分叉会话」 | 代码：`MemoryConsolidator.forkedMessages` 只拼 `system` + `user` 两条，全程不建 session、不写 message 表、不给工具；整理完 `chat_sessions` / `messages` 行数不变 | ✅ |
| 记忆有状态与分量 | `memories.status` 三档 + `strength`；整理一次后，记忆页卡片上「还在用 / 沉下去了 / 已归档」三个数字跟着变 | ✅ |
| 三档回忆 recall(1/2/3) | 让他在聊天里「仔细想想去年的事」，触发 `recall` 且 depth=3：能翻到已归档的条目；`RecallTest` 21 个单测覆盖档位差异、排序与渲染 | ✅ |
| 彻底遗忘是真删 | `MemoryLifecycle.dailyUpdate` 在「分量 ≤ 0.08 且 180 天没被提起」时返回 `Forget`，整理器执行 `deleteById`；`MemoryLifecycleTest` 27 个单测覆盖阈值与边界 | ✅ |
| 联网打分与对话抽取共用判据 | `ImportanceScoring.SHARED_CRITERIA` 同时被整理器提示词与联网打分提示词引用；`WebSearchScorer` 里 `importance >= 3` 才落库 | ✅ |
| 记忆页健康卡片 | 打开任一「长期记忆」页：顶部卡片显示三档数字、归档说明、上次整理一行；没有整理记录时写「还没整理过」 | ✅ |
| 「现在整理一次」 | 点一下：按钮变「整理中…」并禁用，结束时 snackbar 出现中文结果；手动任务与每天那次互不覆盖（两个不同工作名） | ✅ |
| 列表能分辨沉睡 / 归档 / 固定 | 手动把一条记忆的 `status` 改成 `ARCHIVED`（或等整理/衰减）：卡片变淡、行尾「归档」、底部出现「只有他认真回忆时才可能翻到」；pinned 的一直显示「一直记着」 | ✅ |
| 设置两个开关 | 打开「每天整理记忆」→ 排上周期任务；关掉 → 周期任务与待跑的手动任务一起撤掉；「联网时判断重要性」关掉后搜索完不再问模型 | ✅ |
| 开关失败会回滚 | 单测层面看代码路径：写 DataStore 失败时把旧值写回去并提示；因为开关显示直接读 DataStore，落库失败时界面本来就不会停在新值上 | ✅ |
| 数据库升级不丢数据 | v5 → v6 迁移：模拟器上从 1.4 的库直接升级，旧记忆全部变成 `ACTIVE` / `strength = 1.0`，未崩溃 | ✅ |
| 单测全绿 | `./gradlew :app:testDebugUnitTest`：233 个全绿（其中 1.5 的领域测试 72 个、本次界面文案 9 个） | ✅ |
| 模拟器逐项走查 | 见下方「七、模拟器上的真实结果」 | ✅ |

## 五、取舍与已知限制

- **整理结果取决于模型下判断**：解析是容错的（容错 JSON、重复编号、`第3条` 这类写法），但模型如果什么都没给，这次整理就只是「让它自己变淡」，界面上也会照实说「这次没怎么动」。宁可不动，也不让客户端替它猜。
- **一次整理最多看 60 条清单、300 条记忆**：剩下的走时间衰减（而不是被模型重新判断）。抽屉不是数据库备份，它是「他此刻还记得的东西」。
- **衰减是线性简化 + 最多补算 14 天**：不是真实遗忘曲线。好处是用户看一眼状态就能明白为什么这条沉下去了；代价是手机几个月不开机后，不会一次性抹平半年记忆。
- **归档不是删除，但界面上没有「取消归档」按钮**：想让它回来，要么等他 `recall 3` 翻到，要么等整理时模型说 keep / raise（这两条路都会把它写回 `ACTIVE`）。不给你一张手动调状态的表单，是为了让「记忆是他自己的」这件事成立。
- **「现在整理一次」走 WorkManager，但不挂联网约束**：手动那次是用户当场点的，挂上约束后没网时会一直躺在 `ENQUEUED`，界面上就成了永远转不完的「整理中…」——实测踩过这个坑。现在它没有约束、最多跑 120 秒，排队超过 60 秒界面会自己收场。每天那次仍然只在联网时跑。工作名与周期任务分开，手动整理不会顶掉当天那次。
- **手动整理与每天整理共用一把互斥锁**：两个 Worker 撞上时后来的等前面结束，不会对同一批记忆重复扣分量。
- **关闭「每天整理记忆」只撤任务，不擦历史**：已经发生过的整理留痕仍然在（那是真实发生过的事），只是以后不再排新的。
- **联网打分要花一次模型调用**：它是后台跑的，不影响搜索结果返回；不想花就关掉那个开关。

## 六、测试

```bash
./gradlew :app:testDebugUnitTest
```

全量 **233** 个单测通过。与记忆 1.5 直接相关的：

- [`MemoryLifecycleTest.kt`](../../app/src/test/java/com/yap/app/domain/MemoryLifecycleTest.kt)（27 个）：三档阈值、钉住的下限、衰减与回血、补算天数上限、180 天 + 极低分量才彻底遗忘、坏数据（负数时间 / NaN 分量 / 越界重要度）不抛异常。
- [`ImportanceScoringTest.kt`](../../app/src/test/java/com/yap/app/domain/ImportanceScoringTest.kt)（24 个）：共用判据的措辞、整理 JSON 的容错解析（缺字段、重复编号、`第3条`、纯文本包着 JSON）、联网打分的解析与「重要度到 3 分才记」。
- [`RecallTest.kt`](../../app/src/test/java/com/yap/app/domain/RecallTest.kt)（21 个）：三档能看到的记忆状态、排序、空结果的人话、depth 的宽松解析（数字 / 英文 / 中文别名 / 越界）。
- [`MemoryHealthTextTest.kt`](../../app/src/test/java/com/yap/app/ui/screens/memory/MemoryHealthTextTest.kt)（9 个，本次新增）：「上次整理：昨天 04:10 · …」的日期边界与计数拼接、「整理完了：…」的四类顺序、全为 0 时的说法、空跑 / 关闭 / 失败 / 任务挂掉各自的那句话。

模拟器上的逐项走查见下一节。

## 七、模拟器上的真实结果（v1.5.0-debug）

全部在 AVD `YapTest`（API 35）+ 本地参考服务（`harness/server.mjs`）上跑出来的，不是设计推演：

- **数据库升级**：从 1.4 的 v5 库直接升到 v6，启动无崩溃；4 条旧记忆全部 `ACTIVE` / `strength = 1.0`。
- **整理真的改了记忆**：点「现在整理一次」→ `memory_consolidations` 新增一行
  `kept=2, merged=1, archived=0, forgotten=0, summary="把重复的两条并成一条，重要的事标得更清楚"`；
  `memories` 里两条重复的经历被并成一条（`importance 3 → 4`），被并掉的那条变成 `ARCHIVED`、`strength = 0.0`，另有一条 `importance 3 → 5`。
  记忆页卡片同步变成「3 还在用 / 0 沉下去了 / 1 已归档」+「上次整理：今天 00:30 · 留下 2 条、并掉 1 条」。
- **三档回忆拿到了真东西**：聊天里触发 `recall`（depth=3，query「项目」），气泡下出现「回想了一下」，
  展开后是 5 条命中，每条带来源与相对时间，其中**归档那条被标成「这是很久以前的事，他不太确定了」**：
  ```
  （记忆 · 今天 · 你们之间的经历）他最近常熬夜赶项目，夜里容易焦虑，需要有人安静陪着，而不是立刻给建议。
  （记忆 · 7 分钟前 · 关于对方）对方说想在天冷的时候收到一句提醒，不喜欢被反复叮嘱
  （聊天 · 刚刚 · 《I had a long day and I can…》）对方说：recall
  （记忆 · 今天 · 关于他自己 · 这是很久以前的事，他不太确定了）他习惯在回答前先停一下…
  ```
- **联网打分真的写进了记忆**：把联网工具指向参考服务后发一句「search the project」，
  app 日志 `WebSearchScorer: score scored=true important=true memoryId=… msg=记住了一条上网看到的`，
  记忆页多出一条「对方在做的那个项目下个月上线，压力主要来自排期」，来源写的是「他自己上网看到的」，重要度 4。
  同一句连发四次、判为不重要时不写库（`important=false memoryId=null`），也验证过了。
- **「整理中」不再卡死**：旧版本手动任务带联网约束、离线时永远 `ENQUEUED`，界面永远转圈；
  现在改法后点一次约 1 秒内进 `RUNNING`，跑完 0.6 秒结束（本地服务），按钮立即恢复。

### 顺手补的两处（也是这轮才发现的）

- **手动整理去掉联网约束 + 120 秒超时 + 60 秒排队看门狗**（见「五、取舍」那条）。
- **App 启动时按开关决定排不排**：原来 `YetAnotherPalApplication` 无条件 `schedule(...)`，关掉开关后仍会空跑一次；
  现在启动时读 `memoryConsolidationEnabled`，关着就直接 `cancel(...)`，让「关掉」是真的不跑。
