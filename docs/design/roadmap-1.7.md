# Roadmap 1.7：先把他当人，再把地基擦干净

> **历史设计记录**：本文是当时那一版的行为规格与验收表，保留下来供追溯设计取舍；
> 当前行为以 [README](../../README.md)、[ARCHITECTURE](../ARCHITECTURE.md) 与代码为准。


1.5、1.6 是两层叠加：记忆会变旧、会被想起、会被此刻的心情轻轻改写，界面第一次有「他此刻」，还能把他带走。代价是并行开发留下的毛边——同一个界面里两套通知 id、预算闸有的地方走 DI 有的地方直接 new、字符串一半在 `strings.xml` 一半硬编码。

1.7 先还这些债，然后只做一件跟「人感」有关的事：**让他发来的链接点得开，而且是在应用里开**，像微信内置的那个小浏览器。其余改动都往「他自己会想」这个方向挪一寸：冲动改成一条先降后升的心跳曲线（每半分钟一拍，中不中他一律自己判断做不做），提供程序侧多一层本地向量检索，长会话不再靠截断而靠「收成一段记忆」。

明确不做的三件：**TTS**（链路太长，1.7 不碰）、**关系时间线与关系报告**（数值化的关系只会把人做成仪表盘）、**安全与隐私加固**（用户明确不要；本版一个字都不写）。

## 一、目标

- **并行开发的债一次清完**：`NotificationIds` 单点生成、预算闸统一走 `ApiBudget` 注入、可用字符串进 `strings.xml`、迁移链守卫与 schema 版本守卫都进测试，全量单测从 551 涨到 **570（32 个测试类）** 并全绿。
- **点得开**：所有会话消息里的链接（带协议的地址、裸域名、Markdown 的 Markdown 链接语法）一律蓝色下划线，点一下**在应用里**打开，不是跳系统浏览器；长按仍然叫出消息菜单，滚动、选字、返回都不受影响。
- **环境自知**：他自己那一轮的提示词里写清「现在几点、星期几、你的作息、你此刻在做什么、你今天的状态」，让他像一个知道自己在什么光景里的人那样开口。
- **心跳取代定时器**：每 30 秒一拍，概率是一条 U 形曲线（刚互动完最安静 → 一个人待久了慢慢变想 → 三小时后封顶 0.85），会话与触发后重置，心情越浓概率越大；**中了也只是叫他去「想一想」，说什么、做不做仍由他自己决定**。
- **检索多一层语义**：本地 384 维哈希嵌入 + 余弦重排，纯离线、零依赖、确定性；换实现靠 `provider` 字段自然失效。
- **上下文不再靠截断**：长会话把最旧的一段收成一句「他还记得的话」，最近 24 条逐字保留，原文一条不删；向量检索分块扫，扫完即丢。

## 二、用户看到什么

- **他发来的链接是蓝的**：地址、裸域名、`[一份更短的说明](https://…)` 这种 Markdown 链接，显示的都只是能读的文字，颜色统一 `#2C7DBE` + 下划线。
![聊天里的蓝色链接](../screenshots/link-in-chat.png)

- **点一下就在应用里看**：顶部是网页自己的标题（取不到就退回域名），下面一行小字「他发给你的链接」，右上角两个动作——「在系统浏览器打开」和「复制链接」。加载有细进度条，返回键优先在网页里往回退，退到头才离开这个页面。
![应用内浏览器](../screenshots/in-app-browser.png)

- **长按还是那个菜单**：复制 / 重新生成 / 删除这条消息；长按正文和长按气泡空白处一样都叫得出来。菜单干净，不会同时弹出系统选字工具条。
![长按消息的菜单](../screenshots/message-menu.png)

- **心跳在界面上完全看不见**：没有频率、概率、开关，也没有「他刚刚想了想」的流水。用户能感觉到的只有「他有时候会突然想起我」，以及他自己留下的一句「刚才翻了翻……」

## 三、实现地图

### 1. 并行开发的债

| 债 | 1.6 的样子 | 1.7 的做法 |
| --- | --- | --- |
| 通知 id | `ProactiveNotifier` / `AgentNotifier` / `ChatNotifier` 各算一份 | [`NotificationIds.kt`](../../app/src/main/java/com/yap/app/util/NotificationIds.kt) 一处生成：64 位 FNV-1a 折叠进固定区段（会话 `0x0C00_0000`、摘要 `0x0D00_0000`），常驻通知 `0x001F_2E3D` 单独一段 |
| 预算闸 | 有的调用点直接 `new`，有的走 DI，超支判定两套 | 全部走 [`ApiBudget` `ApiBudget.kt`（Hilt 单例），`ApiCost.budgetVerdict` 是唯一的判定入口 |
| 字符串 | 界面文案一半硬编码 | 通知、用量、备份、清空数据这些「一句话就是产品」的文案进 [`strings.xml`](../../app/src/main/res/values/strings.xml)（62 条）；长句仍留在 Kotlin 里，因为它们是函数而不是模板 |
| 迁移防线 | 只有 androidTest 里的迁移测试 | 另加一条 JVM 守卫（[`SchemaVersionTest`](../../app/src/test/java/com/yap/app/data/local/SchemaVersionTest.kt)）：DB 版本号、`schemas/` 里的 json、迁移注册三处必须对齐 |
| 向量补建有硬顶 | 补建只读前 500 条（按重要度排序），第 501 条之后永远没有向量；全库「够数」判断会让进程每次启动都重读 500 行正文 | 补建改成「先问还有谁缺向量，再补一批」，循环到问不出东西（`MemoryDao.idsMissingVectors` + `byIds`，一批 64 条、最多 40 轮）；删掉不可靠的算术快路径 |
| 链接误触 | `getOffsetForPosition` 会把任意一点吸附到最近的字符，点在行尾留白或两行之间也可能「命中链接」 | 命中后再核对触点是否落在该字的包围盒里（±8dp 容差） |
| 备份格式漏了滚动摘要 | 1.7 新增的 `historySummary` 没进备份契约，换端接回来会突然忘掉聊过的事 | `BackupSession` 增加 `summary` / `summarizedUpTo` / `summaryAt` 三个可空字段（向后兼容）；导入只接受「有正文且压到某一条」的完整组合；Android 与 iOS 共用 `fixtures/backup/*.json` 做双向验证 |
| 浏览器只看网络错误 | 404 / 500 / CDN 拦截不会走 `onReceivedError`，白屏、无提示、无重试 | 补 `onReceivedHttpError`（只认主文档、只认 4xx/5xx） |
| 遗留硬编码 | 通知与常驻服务的文案仍散在代码里 | `ProactiveNotifier` 的锁屏文案、`AgentLoopService` 的常驻通知标题与正文都改用 `R.string`（新增 `agent_foreground_title` / `agent_foreground_text`） |

清债的过程里顺手补了两个真问题：`MemoryVectorDao.deleteOtherProviders` 之前返回 `Unit`，调用方却 `getOrDefault(0)`（编译期就该拦下来，是接口签名写松了）；`ApiBudget` 的 KDoc 还停在 1.6 的三档判定，改完之后与实际代码一致。

### 2. 点得开：链接与内置浏览器

**识别**（[`LinkDetector.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/LinkDetector.kt)）是纯函数，只放行 `http` / `https`：

| 形态 | 例子 | 点击区域 |
| --- | --- | --- |
| 带协议 | `https://example.com/reading` | 整个地址 |
| 裸域名 | `example.com/a`、`www.example.com` | 整个域名（连同路径） |
| Markdown | `[一份更短的说明](https://example.com/brief)` | **只有「一份更短的说明」** |

几条克制的规则：中文标点（`，。！？`）、成对的右括号、句末的点会被剔出地址；`javascript:` / `file:` / `content:` / `intent:` / `data:` 一律拒绝；`.md`、`.png` 这类看起来像文件名的后缀不点亮；单条地址上限 2048 字符，流式输出截断到一半的地址也能安全处理（多识别一段正文比点错更糟）。

**渲染**（[`MarkdownText.kt`](../../app/src/main/java/com/yap/app/ui/components/MarkdownText.kt)）：`buildInline` 在着色之外给链接登记 `LinkAnnotation.Clickable`（tag 就是地址）。点击判定**不用** Compose 自带的那条路，理由写在代码里：

- 带链接的 `AnnotatedString` 会让 `Text` 在内部接一层手势，它在 Initial 阶段就把按下事件吃掉；
- 于是外面气泡的 `combinedClickable` 再也收不到「按在文字上」的长按，而且它会把「按住—松手」一律当成点击——长按叫菜单时浏览器跟着弹出来。

所以 `InlineText` 自己判定：手指压下、中途没被拖走（超出 touch slop 就放弃）、按住时间不超过长按阈值、抬手位置用 `TextLayoutResult.getOffsetForPosition` 换算成字符下标、落在哪个链接区间就开哪个地址。命中才 `consume()`，落在别处一律放行。链接的 `linkInteractionListener` 置空实现——样式与语义留着，动作归我们。

**长按**：因为上面那层事件被 Text 吃掉了，正文自己补一条长按（`awaitLongPressOrCancellation`），叫出和气泡空白处一样的消息菜单。聊天气泡同时传 `selectable = false`——`SelectionContainer` 在这里只会带来第二套菜单（系统选字工具条），而「复制整条」在消息菜单里、复制代码片段在代码块右上角。

**打开**（[`InAppBrowserScreen.kt`](../../app/src/main/java/com/yap/app/ui/screens/browser/InAppBrowserScreen.kt)、路由 `browser?url={url}`）：

| 关注点 | 行为 |
| --- | --- |
| 载体 | 系统 WebView（`AndroidView`），JS 与 DOM storage 打开（多数网页要），`file://` 与 `content://` 访问关闭 |
| 标题 | 网页自己的 `title`；拿不到就用域名 |
| 进度 | 顶部一条细长的 `LinearProgressIndicator`，加载完隐藏 |
| 返回 | 先 `webView.canGoBack()` 在页面里退；退到头再离开 |
| 错误 | 主文档加载失败（`onReceivedError` 且 `isForMainFrame`）才提示，子资源失败不打扰 |
| 出口 | 「在系统浏览器打开」（`ACTION_VIEW`）与「复制链接」 |
| 生命周期 | 跟随 `Lifecycle` 做 `onResume` / `onPause`，离屏 `destroy()`，避免 WebView 泄漏 |

### 3. 环境自知

他自己那一轮的 `system` 提示词由 [`AgentPromptBuilder`](../../app/src/main/java/com/yap/app/domain/AgentActions.kt) 拼，1.7 在「处境」里补了四行（[`AgentLoop.buildContext`](../../app/src/main/java/com/yap/app/data/repo/AgentLoop.kt)）：

```
现在：2026-11-14 星期四 03:05（深夜）
你的作息：通常 01:30 睡，09:00 起床
你现在：在想事情 · 刚刚
今天你已经主动开口 1 次（上限 3）
你今天的状态：心情低落，今天不太想说话
```

关键在措辞：是**第二人称的自我感知**（「你现在：……」），不是系统状态栏。他不是在读一份设备信息，而是知道自己此刻在什么光景里。`心情` 那行来自 `MoodBehavior.tuning.note`，与「每天想几次」用的是同一份修正——所以「今天话少」在提示词里也看得见，不是一个隐藏参数。

### 4. 心跳：冲动什么时候来

[`Heartbeat.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/Heartbeat.kt) 是纯函数，[`HeartbeatController.kt`](../../app/src/main/java/com/yap/app/data/repo/HeartbeatController.kt) 负责读库与执行。

```
p(t) = FLOOR + (INITIAL − FLOOR)·e^(−t/3.5) + (CEILING − FLOOR)·ramp(t)
```

| 常量 | 值 | 为什么是这个数 |
| --- | --- | --- |
| `INITIAL` | 0.35 | 刚聊完不是最想说话的时候，人会收一收 |
| `FLOOR` | 0.10 | 十分钟前后基本不想开口（谷约 0.115） |
| `CEILING` | 0.85 | 一个人待三小时后很想，但不是「一定」 |
| `COOL_DOWN_MINUTES` | 10 | 那股热乎劲大约十分钟散掉 |
| `RAMP_MINUTES` | 180 | 三小时是「一个人待着开始有点空」的自然尺度 |
| `NIGHT_FACTOR` | 0.5（0–5 点） | 他也要睡 |
| 心情放大 | 1.15 / 1.35 / 1.55（强度 3/4/5） | 高兴和难过都会让人想找人说说话，平静不放大 |
| 夹取 | 0.02 ～ 0.90 | 再低留一点念想，再高也不到「一定」 |

**p(t) 是「这一分钟里他突然想起你」的概率**，而心跳是离散采样：每 30 秒（低电量时 2 分钟）掷一次，所以真正用的是 `1 − (1 − p)^Δ`（[`Heartbeat.perBeatProbability`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/Heartbeat.kt)）。直接用 p 每拍掷，等于一分钟内六成会触发，「先降后升」在采样层面根本不存在；而且 `t < COOL_DOWN_MINUTES`（10 分钟）时干脆不掷——收尾期是真的留白，不是「概率低一点」。

每拍的顺序（先门槛、后掷骰子，省电也省额度）：

1. 常驻循环关着 / 没有可用人格 / 他在睡觉 → 不掷；
2. 距离上次互动不到 10 分钟 → 不掷（收尾期留白）；
3. 算出 `p`、换算成这一拍的概率，掷一次；**没中什么都不做**（一次内存比较）；
4. 中了之后才去确认那几笔账：上一次思考不到 5 分钟 → 放弃；今天主动开口已达上限（含心情修正）→ 放弃；本月额度被闸掉 → 放弃；
5. 都放行 → 调 `AgentLoop.tick`，由他看处境自己决定说什么、翻点什么、还是什么都不做；
6. 不管他做了什么（甚至出错），概率时钟都从此刻重置。

一拍要看的那几件事（上次互动、心情、重置点）在内存里缓 **60 秒**——一天 2880 拍，如果每拍都去查「他最近说过什么、心情怎样」，光查询就够把手机从睡梦里叫醒几十次，而这些东西一分钟内的变化对他「想不想你」没有意义。

重置的真源是 `Heartbeat.latestMark(上次重置, 上次触发, 最近一次互动)`——用户刚说话、他刚说话、他上次主动开口，取最新的那个；他说的每一句都会让曲线重新走一遍。正常情况下每 30 秒一拍，**电量低于 20% 且未充电时放宽到 2 分钟**——这是设备约束，不改用户能看到的东西。

界面上没有任何入口：没有频率、概率、开关，也没有一串「他刚刚想了想」的流水。

### 5. 向量检索

[`LocalEmbedding.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/LocalEmbedding.kt)：把一段话压成 **384 维**向量。

它**不是**神经网络 embedding——没有模型文件、不下载任何东西。做法是「字 n-gram 的带符号哈希」：

1. 复用 [`ChineseTerms`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/ChineseTerms.kt) 的切词结果（中文按字二元组、英文按整词、虚词已删），**查询和记忆必须走同一套词元**，否则会出现「上下文里挑中的记忆，回忆工具却翻不到」；
2. 相邻二元组拼出字三元组（「项目」+「目压」→「项目压」），不重新扫正文；
3. 没有语料库统计 DF，就用长度做静态先验：单字 0.8、二元组 1.0、三元组更高；
4. 亚线性 TF（`1 + ln(1 + w)`），避免长记忆被一个高频词主导；
5. L2 归一化，空文本返回全零（调用方跳过）。

所以它捕捉的是**字面近似的语义**：换一种说法只要还共享一部分字（「项目压力大」/「工作上的压力」共享「压力」），向量就会靠近；「我哥」/「家兄」这种一个字都不沾的抓不到。真模型换来的收益在这个场景（几百条几十字的中文短句 + 查询就是用户当下那句话）不值得 50–300MB 的包体和一次下载等待。

[`MemoryVectorIndex.kt`](../../app/src/main/java/com/yap/app/data/repo/MemoryVectorIndex.kt) 负责写与查：

| 关注点 | 行为 |
| --- | --- |
| 存储 | `memory_vectors(memoryId, provider, dim, vector BLOB, updatedAt)`，`provider = "local-hash-v1"` |
| 换实现 | 只要改 `LocalEmbedding.PROVIDER`，旧向量因为 provider 对不上自然失效，`deleteOtherProviders` 顺手清掉——不需要迁移脚本，也不会「旧向量按新含义被解读」 |
| 查询 | 只查向量表、不碰正文；候选按 **128 条一块**扫，算完即丢（同一时刻最多 128 个 `FloatArray`，约 200KB），每块先取 topK 再合并 |
| 坏数据 | 半个 float、维度不对、别的 provider、NaN、零向量——跳过，不抛异常 |
| 兜底 | 索引出任何问题都只是「少几条候选」，倒排索引与关键词 LIKE 会接住 |

召回链路因此变成四步：候选（倒排 ∪ LIKE ∪ 标签 ∪ 时间指代 ∪ pinned/重要度基线）→ 关键词重排 → **向量余弦再排一层** → 按 7000 字预算裁剪。

### 6. 长会话的「滚动记忆」

[`HistoryCompression.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/HistoryCompression.kt) 是纯逻辑，[`HistoryCompressor.kt`](../../app/src/main/java/com/yap/app/data/repo/HistoryCompressor.kt) 负责执行。

| 参数 | 值 | 意思 |
| --- | --- | --- |
| `DEFAULT_MAX_VERBATIM` | 24 | 最近 24 条永远逐字留着（近处的话不能变成概括） |
| `DEFAULT_MIN_TO_COMPRESS` | 12 | 更早的至少要攒够 12 条才值得花一次调用 |
| `DEFAULT_MAX_CHARS` | 24 000 | 字数太多时即使条数不够也压（有人一发就是几千字） |
| `DEFAULT_MAX_BATCH` | 40 | 一次最多压 40 条，绝不把一年历史一口气塞进去 |
| `MAX_SUMMARY_CHARS` | 300 | 摘要上限（模型被要求 200 字，留一点余地）；少于 6 字当失败 |

它不是「省 token」，是在替他留住「我们聊过什么」：截断等于那段在**他**看来没发生过，所以摘要存回会话（`historySummary` / `historySummarizedUpTo` / `historySummaryAt`），之后拼进 `system` 时是「【更早之前你记得的】……」这样一段第一人称的记忆。**原文一条都不删**，分页、搜索、导出看到的仍是完整记录。调用记 `UsageKind.HISTORY`，照样受月度预算闸。

真机上跑出一条必须记下来的教训：第一次实跑时，模型把一次 `remember` 工具调用（```` ```yap-tool ```` 代码块）当成「回忆」写了回来，而当时只校验了长度与标点，于是那段工具调用被当成记忆存了进 `historySummary`——之后每一轮都会把它塞进提示词。修法三层：`cleanSummary` 先把所有 ``` 围起来的块整段删掉（留下前后正文）；`isSummaryAcceptable` 明确拒收任何含代码围栏或 `"name"/"arguments"` 形状的正文；**已经写进库里的脏摘要**由启动时的 `HistoryCompressor.repairStaleSummaries()` 自检清空（连带清掉「压到哪一条」，下次该压的时候重新压一遍）。`HistoryCompressionTest` 补了一条用例钉住它。

顺带的内存工作：向量候选分块扫（见上）、`memory_terms` 与 `memory_vectors` 的孤儿行清理、API 用量只留 90 天。

### 7. 明确不做的

| 项 | 为什么不做 |
| --- | --- |
| TTS / 语音 | 链路太长（授权、播放、打断、离线），1.7 不碰 |
| 关系时间线与关系报告 | 把关系做成数值与图表，就把人做成了仪表盘 |
| 安全与隐私加固（应用锁、消息加密、导出脱敏开关） | 用户明确不要；本版不写。既有的「API Key 不进备份」「锁屏只显示『他发来一条消息』」保持不变 |

## 四、验收

| 项 | 怎么验证 | 状态 |
| --- | --- | --- |
| 链接点得开 | 模拟器里点「https://example.com/reading」与「[一份更短的说明](https://example.com/brief)」，都在应用内打开并显示网页标题 | ✅（[link-in-chat.png](../screenshots/link-in-chat.png)、[in-app-browser.png](../screenshots/in-app-browser.png)） |
| 长按不误触 | 长按带链接的正文 → 只出消息菜单，不开浏览器、不出系统选字工具条 | ✅（[message-menu.png](../screenshots/message-menu.png)） |
| 点空白不误触 | 点正文里的非链接文字 → 什么都不发生 | ✅ |
| 心跳不会变成推送 | 30 秒一拍、命中后重置、5 分钟下限、睡着/额度/上限都不掷 | ✅（`HeartbeatTest` 34 条） |
| 向量索引真的在库里 | 升级后 `memory_vectors` 行数与记忆数一致，provider 全是 `local-hash-v1` | ✅（模拟器 `sqlite3` 核对：6 条记忆 / 6 条向量） |
| 长会话会收成记忆 | 超过 24 条 + 更早部分够 12 条时压缩一次，`historySummary` 落库、原文不变 | ✅（`HistoryCompressionTest` 28 条 + 模拟器实跑） |
| 数据库升级不清库 | v7 → v8 迁移后 `user_version = 8`，记忆 / 消息 / 会话条数不变 | ✅ |
| 全量单测 | `:app:testDebugUnitTest --rerun-tasks` | ✅ 32 个测试类 / **570 条**，0 失败 |
| 脏摘要自愈 | 往库里塞一段工具调用形状的 `historySummary`，重启 App 后自动清空 | ✅ |

## 五、版本

`versionCode 8 / versionName "1.7.0"`，数据库版本 8（`MIGRATION_7_8`：会话的滚动摘要三列 + `memory_vectors` 表与索引）。
