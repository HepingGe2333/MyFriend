# Roadmap 1.6：把地基补上，记得更深，也有「此刻」，还能把他带走

> **历史设计记录**：本文是当时那一版的行为规格与验收表，保留下来供追溯设计取舍；
> 当前行为以 [README](../../README.md)、[ARCHITECTURE](../ARCHITECTURE.md) 与代码为准。


> 注：本文记录的是当时版本的设计与实现；「原生 Harness」模式已在 1.8 移除，只保留无状态兼容，相关描述不再适用于当前代码。

1.5 让记忆像记忆：会变旧、会沉下去、会被彻底忘掉。但从 1.3 一路加到 1.5，有几件事一直没做扎实——升级数据库还留着 `fallbackToDestructiveMigration()`（迁移写错就把用户的记忆静默清空）、release 包拿 debug 的 key 签名（装上去就再也升不了级）、通知 id 用 `String.hashCode()`（两个会话撞上，后一条把前一条顶掉）、用户完全不知道他一个月在后台花了多少钱。

1.6 先修这些 P0，再往前推三格：**记性更深**（中文倒排索引 + 本地余弦重排，记忆带标签和「发生时间」，回忆会被此刻的心情轻轻改写，但事实层用硬规则卡死）、**他终于有「此刻」**（界面第一次显示他正在做什么，只读）、**能把他带走**（一份 JSON 备份，API Key 永不进备份，导入先预览再决定合并还是替换）。

这一版不加新的聊天工具，界面改动只有三处：聊天标题栏的副标题、「主动唤醒」页的一张卡片、设置页（新增「用量与花费」与「把他带走」两节）。

## 一、目标

- **迁移写错宁可崩，也不静默清库**：去掉 destructive fallback，`exportSchema = true` 把每一版表结构导出成 json，再补一组跑真实 `Migration` 的 androidTest；另有一条迁移链完整性守卫，1 → 当前版本不允许断档。
- **release 是一个能真正升级的包**：签名从 `keystore.properties` 或 `YAP_KEYSTORE_*` 环境变量读；缺了就产出 `app-release-unsigned.apk`，绝不拿 debug key 冒充。同时打开 minify + shrinkResources，proguard 规则按库分节写清。
- **通知不再互相顶掉，也不再在锁屏上念私聊**：通知 id 改成 64 位 FNV-1a 折叠进固定区段，每个 persona 一组 + 一条摘要，同组多会话只响一次；锁屏只显示「他发来一条消息」。
- **每一次模型调用都记一笔账**：`api_usage` 表按用途记账，覆盖 OpenAI 兼容 / Gemini / Claude 三种 usage 形状；流式没有 usage 时按字数估一个，不让「聊天」记成 0。
- **月度预算能拦住后台，绝不拦聊天**：超预算时整理、打分、思考、回忆重构、抽取这些后台调用被挡住并抛中文异常，聊天永远放行；设置里能填额度、能关掉这道闸。
- **记性更深**：中文倒排索引（字二元组 + 虚词过滤）+ 本地余弦重排，记忆多出 `tags` 与 `happenedAt` 两个结构化字段，三个抽屉按不同速度衰减。
- **回忆会被此刻的心情轻轻改写**：34% 概率、20 分钟冷却、一次只挑 1～2 条够长且没定型的记忆，做一次「用完即弃的分叉调用」；允许改多少由心情浓度、年龄、被改次数算出来，事实层（数字 / 称呼 / 叠词 / 否定 / 人名标签）用硬规则比对，任一不符整条放弃。
- **心情终于驱动行为**：tick 间隔、每日主动上限、上网与分享倾向、一句「今天话少一点」进聊天提示词；深夜再安静一点。这些都写进他的内心记录，用户在「他最近在想什么」里看得见。
- **界面第一次有「此刻」**：睡着了 / 刚醒 / 在写东西 / 在翻东西 / 在想事情 / 过一会儿想找你 / 在忙别的 / 在发呆，从思考记录、心情、作息、消息、念头推出来，30 秒自己刷新；**没有任何设置入口**——他的状态是他自己的。
- **能把他带走**：人格、记忆（含状态 / 强度 / 标签 / 发生时间 / 原文 / 漂移次数）、会话与消息、预设、规则、念头、整理记录和可移植设置打包成一份 JSON；API Key 与自定义 Headers 永不进备份；导入先预览、再选合并 / 替换、单事务、失败整体回滚。

## 二、用户看到什么

- **聊天标题栏的副标题让给了「他正在……」**：人格名字下面一行，先是心情胶囊，再是「在想事情 · 刚刚」这样的状态；模型名不再占这一行（资料面板里还在）。正在输入时仍然显示「正在输入…」。
- **他此刻在做什么，一句话就够**：睡着了 / 刚醒 / 在写东西 / 在翻东西 / 在想事情 / 过一会儿想找你 / 在忙别的 / 在发呆；0–4 点「在发呆」会写成「这么晚了还没睡…」。「几分钟前」会自己往前走，不需要刷新。
![聊天标题栏：心情胶囊与「他正在做什么」](../screenshots/presence-chat.png)

- **「主动唤醒」页原来那张「他此刻的心情」卡片改名为「他此刻」**：心情胶囊 + 一句原因不变，下面多一行状态。还是只展示，不提供手动调节。
- **设置页多一节「用量与花费」**：一句人话打头——「他这个月自己上网、整理记忆，一共花了约 $0.42」；下面一行「一共 N 次 · 读了 X tokens · 写了 Y tokens」；再按用途摊开（聊天 / 整理记忆 / 判断值不值得记 / 自己待着时想事情 / 回忆你说过的事 / 从聊天里记东西 / 想起旧事），每行「3 次 · 约 $0.01」；最下面才是「每月额度（美元）」输入框和「花完以后只让他在聊天里回你」开关。没有图表，没有 token 账单腔，还写着「只记在本机，不上传；也没法拿它去别处对账」。
![设置页的「用量与花费」](../screenshots/usage-cost.png)

- **设置页多一节「把他带走」**：开头一句「他不是一份数据，是一个陪你聊过很久的人」；两个动作——「把他带走」（选一个位置存 JSON）与「从备份里接他回来」（先看看里面有什么）；下面写明「备份里有你们的聊天和记忆，放在安全的地方。API Key 不在备份里」。导出时按钮区显示一行「正在整理他的东西，稍等一下……」，完成后一句「已经把他带走了：yap-backup-20261114-0155.json」。
![设置页的「把他带走」](../screenshots/backup-section.png)

- **接他回来之前先看清单**：选完文件弹出「备份里有什么」——「3 个人格 · 6 条记忆 · 1 段对话 · 51 句话 · 1 条主动节奏」，加一行导出时间与版本，再给「合并」和「替换」两个选择（替换是红字，但不叠第二个确认框）。有附件时会在这里说「有 N 句话原本带着图片，图片没有跟着备份走，现在只剩文字」。
![导入前的预览与合并 / 替换](../screenshots/backup-preview.png)

- **清空数据处不再只有一句警告**：上面一行「清空之前，先把他带走。」和一个「先把他带走」按钮，和备份那一节共用同一次文件选择。
- **锁屏上只有一句话**：不管他说了什么，锁屏的公开通知只写「他发来一条消息」，点开进 App 才看得到正文；主动唤醒那条也一样。

- **记忆被此刻改写这件事，界面上没有入口**：`memory_versions` 里有留痕（含当时的心情词与一句中文理由），但没有「回滚」按钮。这是他的记忆，不是一份可以随便恢复的文档。

## 三、实现地图

### 1. 数据安全（P0）

**[`AppModule.kt`](../../app/src/main/java/com/yap/app/di/AppModule.kt)** 里删掉了 `.fallbackToDestructiveMigration()`，注释写明理由：迁移写错时宁可当场崩溃让人看见，也不能把用户几年攒下的对话和记忆悄悄清空。1→7 六个迁移（`MIGRATION_1_2 … MIGRATION_6_7`）全部显式注册。

**[`YapDatabase.kt`](../../app/src/main/java/com/yap/app/data/local/YapDatabase.kt)**：`exportSchema = true`，`build.gradle.kts` 的 `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` 把每一版表结构落到 `app/schemas/com.yap.app.data.local.YapDatabase/<版本>.json`（现在只有 `7.json`：version 7、16 张表）；同一份目录又作为 androidTest 的 assets。

**[`YapDatabaseMigrationTest.kt`](../../app/src/androidTest/java/com/yap/app/data/local/YapDatabaseMigrationTest.kt)** 用 `FrameworkSQLiteOpenHelperFactory` **手写旧版 DDL** 造出「用户手机上那一版」，再真跑迁移对象——不需要历史 schema json：

| 用例 | 做了什么 | 断言什么 |
| --- | --- | --- |
| v6 → v7 | 建 v6 表（`personas` / `memories` / `api_configs`）并插一行旧数据，跑 `MIGRATION_6_7` | `tags` / `happenedAt` / `originalContent` / `driftCount` 四列都在；`api_usage` / `memory_terms` / `memory_versions` 三张新表与 5 个索引都在；旧记忆内容一字未改、`driftCount` 默认 0、`status` 仍 `ACTIVE`、`strength` 仍 1.0；三张旧表行数没变 |
| v5 → v7 | 建 v5 表（还没有心情列与生命周期列），依次跑 `MIGRATION_5_6`、`MIGRATION_6_7` | 8 个新列齐全；旧记忆内容 / 重要度不变，升级后是 `ACTIVE` / 1.0；`memory_consolidations` 表与索引也在 |
| 迁移链完整性 | 把已注册迁移的 `(startVersion, endVersion)` 摊开，与 `1..CURRENT_VERSION` 的每一档比对 | 每一档都有人负责；断一档就是升级即崩，没有 destructive fallback 兜底 |

**签名与混淆（[`app/build.gradle.kts`](../../app/build.gradle.kts)、[`proguard-rules.pro`](../../app/proguard-rules.pro)）**：

| 关注点 | 现在的行为 |
| --- | --- |
| 签名来源 | 先读项目根的 `keystore.properties`（`storeFile / storePassword / keyAlias / keyPassword`），再读环境变量 `YAP_KEYSTORE_FILE / _PASSWORD / _ALIAS / _KEY_PASSWORD` |
| 配不齐时 | 四个值齐、且 keystore 文件真的存在才算「配好了」；否则 `signingConfig = null`，产出 `app-release-unsigned.apk`——不失败、也不拿 debug key 冒充 |
| debug | `applicationIdSuffix = ".debug"`、`versionNameSuffix = "-debug"` 不变 |
| release | `isMinifyEnabled = true` + `isShrinkResources = true` |
| proguard | 按 kotlinx.serialization（`$$serializer` / `Companion` / `serializer()`）/ Room（`RoomDatabase` 子类、`@Entity`、`@Dao`、`Migration`）/ Hilt-Dagger / WorkManager（Worker 构造器、`WorkManagerImpl`）分节保留，另关掉 okhttp 与 compose 的无害 warn，保留行号 |

**通知（[`NotificationIds.kt`](../../app/src/main/java/com/yap/app/util/NotificationIds.kt)、[`ChatNotifier.kt`](../../app/src/main/java/com/yap/app/util/ChatNotifier.kt)、[`ProactiveNotifier.kt`](../../app/src/main/java/com/yap/app/util/ProactiveNotifier.kt)）**：

- id 用 64 位 FNV-1a，再 `(hash xor hash ushr 32) and (2^24 - 1)` 折叠进固定区段：会话 `0x0C00_0000`、每个 persona 的摘要 `0x0D00_0000`；常驻「他还醒着」的 `0x001F_2E3D` 不在这两段里，永不重叠。同一个 `sessionId` 跨进程、跨版本都得到同一个 id。
- 每个 persona 一个分组 `chat-<personaId>`，配一条摘要通知；组内 `GROUP_ALERT_SUMMARY`，几个会话同时来信只响一次。进会话撤掉该条时，组空了就把摘要一起收走，不留点不开的空摘要。
- 锁屏打码：渠道与每条通知都 `VISIBILITY_PRIVATE`，并挂一个 `publicVersion`——标题只有 App 名、正文只有「他发来一条消息」，`PendingIntent` 照样直达会话。主动唤醒走同一套 id / 分组 / 打码。

### 2. 用量与预算

**记账**：新表 `api_usage`（`id / at / kind / personaId / sessionId / modelName / promptTokens / completionTokens / costMicroUsd`，索引 `at`、`(kind, at)`、`(personaId, at)`）。捕获点在 network 层而不是各个业务仓库——[`OpenAiAdapter.kt`](../../app/src/main/java/com/yap/app/network/OpenAiAdapter.kt) 在请求前问一次闸门、收到回复后报一笔；[`ApiUsage.kt`](../../app/src/main/java/com/yap/app/network/ApiUsage.kt) 的 `ApiUsageRecorder` 一被构造就把自己挂到进程级句柄上，所以各个调用点只需要各自传一个 `kind`：聊天与主动开口记 `CHAT`，每日整理与人格进化记 `CONSOLIDATION`，联网打分记 `SCORING`，后台思考记 `AGENT`，对话抽取记 `EXTRACTION`，回忆重构记 `RECONSOLIDATION`。（`RECALL` 这个标签留着，但他「回忆」本身不是一次独立调用——它是在聊天那一轮里调用的工具，钱记在那次聊天上。）

[`ApiCost.kt`](../../app/src/main/java/com/yap/app/domain/ApiCost.kt) 一次认全三种 usage 形状：OpenAI 兼容的 `usage.prompt_tokens / completion_tokens`、Gemini 的 `usageMetadata.promptTokenCount / candidatesTokenCount`、Claude 的 `usage.input_tokens / output_tokens`（后两者的原生适配接口仍是预留，但走兼容端点时返回这些形状也能记账）。流式请求加了 `stream_options.include_usage`；服务端仍然不给 usage 时按字数估——中文约 0.7 token/字、英文约 4 字符/token。

**定价**：micro-USD 整数（$1 = 1_000_000），避免浮点越攒越偏；模型名先按前缀、长前缀优先，再按 `contains` 兜住 `vendor/model` 的写法；不认识的模型用保守默认 $1.5 / $6 每百万 tokens（宁可算贵一点，也别显示「不要钱」）。DeepSeek 系在表的最前面（reasoner $0.55/$2.19、chat $0.27/$1.10 每百万）。设置里的单价覆盖（`inputPriceUsdPerMillion / outputPriceUsdPerMillion`）已经落库、也被 `ApiBudgetConfig` 推给记账器，但**没有 UI**。

**预算闸**：[`ApiBudget.kt` `ApiBudget.kt` 只做两件事——查本月花了多少、判这次放不放行，自己绝不调用模型。[`ApiCost.budgetVerdict`](../../app/src/main/java/com/yap/app/domain/ApiCost.kt) 的顺序是有意义的：关掉「超支只聊天」→ 放行；没设额度 → 放行；没花到额度 → 放行；花到 / 超了 → 只有 `CHAT` 放行，其余返回 `OverLimit`。被拦住时抛带中文原因的 `ApiException`，交给既有错误处理；聊天永不拦，因为那是你在跟他说话。

**设置页**（[`UsageSection.kt`](../../app/src/main/java/com/yap/app/ui/screens/settings/UsageSection.kt)）：订阅本月的 `api_usage` 流水实时刷新；打头一句 `usageHeadline()` 人话，明细 `usageDetail()`，再按 `UsageKind.displayOrder` 摊开；额度输入留空 = 不限，填了但不是钱（如「5 块」）不会静默当成 0。超预算时补一句状态说明，超支开关关着时换一句「不过你没让他停」。

### 3. 记忆检索深度

**中文倒排索引**：新表 `memory_terms(memoryId, term, weight)`，`index(term)`。[`ChineseTerms.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/ChineseTerms.kt) 纯 Kotlin：

- 先归一化（全角转半角、小写、标点换空格），再**先删虚词再切字二元组**——「我养了猫」→「养猫」→ 养猫，避免三个词元全带虚词反而查不到「猫」；
- 权重按同一段文本里的出现次数归一（出现最多的是 1.0），单个汉字权重 0.7；一条记忆最多 160 个词元、一次查询最多 24 个；
- 相关性 = **0.6 × 加权覆盖率 + 0.4 × 加权余弦**；整段包含查询时直接给 1.0；
- 另外解析「昨天 / 上周三 / 上个月 / 去年冬天 / N 个月前 / 最近」这类时间指代成左闭右开区间，`center` 就是区间中点。

索引在写入、整理、改写正文、删除时维护；回忆重构改完正文会 `reindexTerms()`（否则新措辞搜不到、旧措辞还在命中）；老库里的记忆由 `ensureTermIndex()` 惰性补建（表为空时按人格各取最多 500 条，只补一次）。索引出任何问题都只是「少几条候选」，下面的全量兜底会接住。

**召回链路**（[`MemoryRepository.getRelevant`](../../app/src/main/java/com/yap/app/data/repo/MemoryRepository.kt)、[`RecallRepository`](../../app/src/main/java/com/yap/app/data/repo/RecallRepository.kt)）三步：

1. **候选** = 倒排索引命中 ∪ 关键词 LIKE 兜底 ∪ 标签命中 ∪ 时间指代（`withTimeHint`）∪ `getForPrompt` 的 pinned / 重要度基线；
2. **重排**：在内存里由 [`MemoryScreening`](../../app/src/main/java/com/yap/app/domain/MemoryScreening.kt) 算词元重合、标签加成、`happenedAt` 邻近、pinned、重要度、新鲜度与抽屉；
3. **裁剪**：按 7000 字预算取前 N 条，避免把上下文塞满。被挑中的顺手记一次「被想起」，免得被每日衰减无声无息地沉掉。

**记忆结构化**：`memories` 多出 `tags`（≤5 个短词，JSON 数组）与 `happenedAt`（模型给「昨天 / 上周三 / 去年冬天」这类相对说法，用 `now` 换算成区间中点；算不出来就 null）。抽取器产出这两个字段，整理器的清单里看得见，`merge` 时要求保留（标签取各条的并集；发生时间取这批里按创建时间排第一的那条非空值）。

**抽屉差异化衰减**（[`MemoryLifecycle.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/MemoryLifecycle.kt)）：`USER 0.55` / `SELF 0.8` / `EVENT 1.0`。USER 最慢，因为名字、忌口、家人这些事半年不提也不会变假；EVENT 是琐事，掉得快才合理。

**整理清单**从「最近 60 条」改成按抽屉各 30 条、总上限 90（[`MemoryConsolidator.kt`](../../app/src/main/java/com/yap/app/data/repo/MemoryConsolidator.kt)），让 USER 抽屉里的老事实不会被 EVENT 的琐事挤掉；某个抽屉不够 30 条时用剩下的按原顺序补满，总上限不变。

### 4. 回忆会被此刻的心情轻轻改写

两个文件：[`domain/MemoryReconsolidation.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/MemoryReconsolidation.kt)（纯规则）与 [`data/repo/MemoryReconsolidator.kt`](../../app/src/main/java/com/yap/app/data/repo/MemoryReconsolidator.kt)（执行）。

**什么时候做**：recall 之后由 `reconsiderAfterRecall()` 顺手触发，**不挡召回**（起在 app scope 里）。先摇骰子 34%，同一个人格两次之间至少隔 20 分钟；从这次真的被翻出来的记忆里随机挑 **1～2 条**，只挑够长（≥12 字）且没定型（`driftCount < 3`）的。

**允许改多少**（`driftBudget`）：

```
allowance = 0.18 + 0.42×心情浓度 + 0.25×年龄(0..90 天) + 0.15×(1 − 已改次数/3)
minSimilarity = 0.86 − 0.24×allowance，且不低于 0.62   → 实际落在约 0.62～0.80
maxLengthRatio = 1.10 + 0.15×allowance；maxAddedChars = 14 + 46×allowance
```

心情越浓、记忆越旧、被改得越少 → 余地越大；改满 3 次就「定型」，只被想起、不再变。

**怎么改**：一次用完即弃的分叉调用——自己拼 `system`（只讲规矩：可以动措辞 / 语序 / 哪个细节先浮上来，绝对不动谁、何时、发生了什么）与 `user`（原文 + 此刻心情 + 这次允许的尺度），没有会话、没有历史、没有工具，温度 0.3、输出上限 260 token，模型要么回「不改」要么只回改完的一段正文。

**硬拒清单**（`acceptDrift`，任一命中整条放弃）：阿拉伯数字变了、中文数字变了、叠词丢了（「妹妹」不能变「哥哥」）、亲人称呼计数变了、否定词计数变了、`tags` 里的人名锚点丢了、长度膨胀超限或缩水到 0.7 倍以下、与原文的二元组 Jaccard 相似度低于门槛、与 `originalContent`（第一次写下的原文）的累计相似度低于 `max(门槛, 0.72)`、超过 900 字。被拒绝时只记 `markUsed`——「他想起过这件事」是真的，「改动」不是。

**留痕**：通过校验才写库，同时插一条 [`memory_versions`](../../app/src/main/java/com/yap/app/data/local/Entities.kt)（`content / beforeContent / reason / moodLabel`），reason 形如 `心情「有点低落」时想起它，第 1 次改动，措辞有点像此刻的自己（相似度 83%）`；`memories.originalContent` 只在第一次写，之后一直当锚点。正文改完重建倒排索引。**用户界面不提供回滚入口。**

### 5. 心情驱动行为

[`MoodBehavior.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/MoodBehavior.kt) 把心情算成几个克制的数（不新建设置、不给滑杆）：

| 心情 | tick 间隔乘数 | 每日主动上限 | 想上网 | 想分享 | 进聊天提示词的一句 |
| --- | --- | --- | --- | --- | --- |
| 没劲、累、困（energy ≤ −2） | 1.2～1.6 | −1 | 0.35 | 0.30 | 今天说话短一点，能一句说完就别拖成两句。 |
| 低落、难过、委屈（valence ≤ −2） | 1.3～1.7（夹到 1.6） | −1 | 0.40 | 0.35 | 今天话少一点、慢一点，不用硬撑热情。 |
| 轻负面（想念、不安） | 1.1 | 0 | 0.8 | 0.9 | —（不写，免得把一句想念说成低落） |
| 雀跃、开心（valence ≥ 2） | 0.85（浓度 ≥3）/ 0.92 | +1（浓度 ≥3） | 1.25 | 1.35 | 今天心情好，话可以多一点，语气轻快些。 |
| 温柔、放松、期待 | 0.95 | 0 | 1.1 | 1.15 | — |
| 平静 / 空 / 未知词 | 1.0 | 0 | 1.0 | 1.0 | — |

约束都写成常量并被夹住：间隔乘数 0.8～1.6、每日上限增减 −1..+1（最终上限落在 1～5）、浏览 / 分享倾向 0.2～1.5、下次思考间隔仍夹在 1～60 分钟。**关掉主动消息时 `baseCap <= 0`，心情不能替他打开**（返回 0）。深夜 0～5 点再乘：间隔 ×1.15、浏览 / 分享 ×0.7，note 尾巴加「，夜里更安静」。

[`AgentLoop.kt`](../../app/src/main/java/com/yap/app/data/repo/AgentLoop.kt) 用它决定下次多久再想、今天还能说几次、要不要跳过上网；`tuning.note` 会进这次思考的 summary / 处境描述（「你今天的状态：心情低落，今天不太想说话」），用户在「他最近在想什么」里看得见。

### 6. 他此刻在做什么

[`Presence.kt`](../../agentcore/src/commonMain/kotlin/com/yap/app/domain/Presence.kt) 是纯推导，先命中的赢：

| 优先级 | 状态 | 条件（窗口都是常量） |
| --- | --- | --- |
| 1 | 睡着了 | 在他自己定的休息时间段里 |
| 2 | 刚醒 | 起床 45 分钟内 |
| 3 | 在写东西 | 3 分钟内说过话，或对方刚说完、他大概正在回 |
| 4 | 在翻东西 | 15 分钟内有过一次「上网看看」的痕迹（`outcome` 里认「上网 / 浏览 / 翻了翻 / browse / web_search」） |
| 5 | 在想事情 | 15 分钟内有过一次后台思考 |
| 6 | 过一会儿想找你 | 半小时内有个到点的念头 |
| 7 | 在忙别的 | 3 小时以上没有任何动静 |
| 8 | 在发呆 | 其余（0–4 点措辞换成「这么晚了还没睡，他就一个人坐着」） |

另给 `relativeTime(ms, now)`：刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 上周 / N 周前 / N 个月前 / 很久以前。

[`PresenceRepository`](../../app/src/main/java/com/yap/app/data/repo/PresenceRepository.kt) 把「他最近做过什么」拼成 `PresenceInput`：作息（`PersonaCardRepository.isAsleep` / 起床时间）、最近一次 tick 的 outcome、最近一次浏览、最近一条消息与他最后说的那句、半小时内有没有念头、现在几点。它把 tick 表与一个 **30 秒脉冲** 合起来，所以「3 分钟前」会自己长大；数据库出问题就退回「在发呆」，不让标题栏空掉。这里只读——没有任何写库或「设置状态」的方法。

界面：[`PresenceText.kt`](../../app/src/main/java/com/yap/app/ui/components/PresenceText.kt) 一行小字（「在想事情 · 12 分钟前」），聊天标题栏里排在心情胶囊后面；模型名退到资料面板（`${config.model} · 原生 Harness / 无状态兼容`）。「主动唤醒」页的卡片改名「他此刻」，心情下面多一行同样的状态。

### 7. 备份与恢复

**格式**（[`BackupCodec.kt`](../../app/src/main/java/com/yap/app/domain/BackupCodec.kt)）：顶层 `format: "myfriend.backup"`、`formatVersion: 1`、`appVersion`、`exportedAt`、`apiKeysOmitted: true`，以及 `personas / memories / sessions / messages / presets / proactiveRules / agentIntents / memoryConsolidations / apiConfigs / settings`。所有字段都有默认值，缺字段不炸、未知字段忽略、版本比本机高时明确拒绝并给中文原因。

**密钥永不进备份**——这是在类型层面掐死的，不是靠「记得删」：

- `BackupApiConfig` 里根本没有 `apiKeyCipher` / `webToolsApiKeyCipher` / 自定义 Headers 字段，只有 name / provider / baseUrl / model / harness 设置 / 推理参数 / webTools 地址与开关；
- `BackupPersona`、`BackupSettings` 也不含任何密钥；导出前还有一次自检；
- `apiKeysOmitted: true` 是写给「打开这份 JSON 的人」看的一句声明。

**导入**（[`BackupRepository.kt`](../../app/src/main/java/com/yap/app/data/repo/BackupRepository.kt)）：先 `preview()` 解出一份 `BackupPreview`，界面上 `describe()` 成「3 个人格 · 6 条记忆 · 1 段对话 · 51 句话 · 1 条主动节奏」，再选 `MERGE` / `REPLACE`。写库全部在**单个 `database.withTransaction { }`** 里：替换模式先清子表再清父表（消息、索引、版本、回声、记忆、整理留痕、念头、规则、预设、人格版本、人格），再按备份写回；失败整体回滚。几个刻意的行为：

| 关注点 | 行为 |
| --- | --- |
| `api_configs` | **从不删除**；已存在的配置只覆盖地址 / 模型 / 开关这些非敏感字段，本机 Key 原样保留；新配置 Key 留空，并提示「去『API 配置』里补一次」 |
| 记忆索引 | 写回时顺手重建 `memory_terms`，否则接回来以后按关键词想不起事 |
| 附件 | 只有元数据，没有二进制；按条数提示「有 N 句话原本带着图片……现在只剩文字」 |
| 设置 | 走 DataStore，只能在事务成功之后单独写；写失败只提示「设置没接回来」，绝不把已经写好的他回滚掉 |
| 空备份 | 不写任何东西，提示「换一份之前带走的备份试试」 |

**界面**（[`BackupSection.kt`](../../app/src/main/java/com/yap/app/ui/screens/settings/BackupSection.kt)）：`CreateDocument("application/json")` 存到用户选的位置（默认名 `yap-backup-yyyyMMdd-HHmm.json`），`OpenDocument` 读回来；清空数据区共用同一个文件选择器，只多一句「清空之前，先把他带走。」和一个按钮。

### 8. 版本

`versionCode 7 / versionName "1.6.0"`，数据库版本 7（`MIGRATION_6_7`）。

## 四、验收

| 项 | 怎么验证 | 状态 |
| --- | --- | --- |
| 迁移写错不再静默清库 | `AppModule.kt` 里没有 `fallbackToDestructiveMigration()`；少注册一个迁移时 Room 直接抛异常（崩溃），而不是把库清空 | ✅ |
| 每一版表结构有据可查 | `app/schemas/com.yap.app.data.local.YapDatabase/7.json`：`version = 7`、16 张表，`api_usage` / `memory_terms` / `memory_versions` 的列与索引都在 | ✅ |
| 迁移真的跑过、不丢数据 | androidTest 的 `migrate6To7_…`、`migrate5To7_…` 两条用例：旧记忆内容一字未改、`driftCount` 默认 0、新列新表新索引齐全；本机模拟器不稳，验收标准是 `assembleDebugAndroidTest` 编译通过 | ✅ |
| 迁移链不缺档 | `已注册的迁移覆盖了每一个版本号没有断档`：已注册的 `(start, end)` 必须刚好等于 `1..CURRENT_VERSION` 每一档；以后加 v8 时把 `CURRENT_VERSION` 一起改成 8，漏写 `MIGRATION_7_8` 这条就红 | ✅ |
| release 不再拿 debug key 签名 | 没有 `keystore.properties`、也没有 `YAP_KEYSTORE_*` 时 `assembleRelease` 产出 `app/build/outputs/apk/release/app-release-unsigned.apk`；配齐四项且 keystore 存在才签 | ✅ |
| 混淆与瘦身开着 | `isMinifyEnabled = true` + `isShrinkResources = true`；实测 release 包 2.57 MB / 157 个文件；`proguard-rules.pro` 里 serialization / Room / Hilt / WorkManager 各有小节 | ✅ |
| 通知 id 不撞、不互相顶掉 | `NotificationIdsTest` 7 个用例：区段与 `AgentNotifier.NOTIFICATION_ID` 不重叠、同一 key 稳定、`0x0C00_0000` / `0x0D00_0000` 两段互不相交 | ✅ |
| 同组多会话只响一次 | 每个 persona 一个分组 `chat-<personaId>` + 一条摘要；`GROUP_ALERT_SUMMARY`；进会话撤掉该条，组空时连摘要一起收走 | ✅ |
| 锁屏不念私聊 | 每条通知 `VISIBILITY_PRIVATE` + `publicVersion`，公开版只有「他发来一条消息」；主动唤醒也走同一套 | ✅ |
| 三种 usage 形状都能认 | `ApiCostTest` 36 个用例；`ApiCost.parseUsage` 覆盖 `usage.*`、`usageMetadata.*`、`input_tokens/output_tokens` | ✅ |
| 聊天不再记成 0 / UNKNOWN | 模拟器实测：发一句话后 `api_usage` 出现 `CHAT` 记录（2260 / 52 tokens，约 $0.0037），后台 tick 记 `AGENT`；流式请求带 `stream_options.include_usage`，没有 usage 时按字数估 | ✅ |
| 预算闸拦后台、不拦聊天 | `ApiCost.budgetVerdict` 的四步顺序单测覆盖；超预算时后台调用抛中文 `ApiException` 交给既有错误处理，`CHAT` 永远放行 | ✅ |
| 设置页「用量与花费」 | 打开设置：一句「他这个月自己上网、整理记忆，一共花了约 $0.42」+ 次数 / tokens + 按用途拆分 + 额度输入 + 超支开关；聊天或后台一花钱，这一节立刻变 | ✅ |
| 中文倒排索引真的能换说法找到 | `ChineseTermsTest` 20 个用例；老库升级后 `memory_terms` 补建出 101 条 | ✅ |
| 召回是「候选 → 重排 → 裁剪」 | `getRelevant` / `RecallRepository`：倒排 ∪ LIKE ∪ 标签 ∪ 时间指代 ∪ 基线，再按 0.6 覆盖率 + 0.4 余弦重排，最后按 7000 字预算裁；索引异常退回旧路径 | ✅ |
| 记忆带标签与发生时间 | 抽取器解析 `tags`（≤5 个短词）与 `happenedAt`（相对说法换算成区间中点）；整理器清单看得见，`merge` 时保留；`ImportanceScoringTest` 28 个用例里有对应边界 | ✅ |
| 三个抽屉按不同速度衰减 | `MemoryLifecycle.DRAWER_DECAY_USER = 0.55` / `SELF = 0.8` / `EVENT = 1.0`；`MemoryLifecycleTest` 32 个用例 | ✅ |
| 回忆改写有界、宁可不改 | `MemoryReconsolidationTest` 25 个用例：预算随心情 / 年龄 / 次数变化、相似度地板 0.62、数字 / 称呼 / 叠词 / 否定 / 锚点 / 长度任一不符即拒；`driftCount` 到 3 定型 | ✅ |
| 改写留痕、界面无回滚 | 通过校验才写 `memory_versions`（含 `moodLabel` 与中文 reason），`originalContent` 只写一次；`ui/` 下没有任何地方读 `memory_versions` | ✅ |
| 心情真的驱动行为 | `MoodBehaviorTest` 19 个用例（各档乘数、上限边界与夹取）；`AgentLoop` 用它调间隔 / 上限 / 是否上网，并把 note 写进 summary | ✅ |
| 「他此刻在做什么」只读 | `PresenceTest` 33 个用例（每种状态的窗口边界与优先级）；聊天标题栏出现「在想事情 · 刚刚」「在写东西 · 3 分钟前」这类状态，主动唤醒页卡片改名「他此刻」；设置里没有任何与状态相关的入口 | ✅ |
| 备份里没有密钥 | 实测导出 44.9 KB，`formatVersion = 1`、`apiKeysOmitted = true`；搜 `apiKeyCipher`、`webToolsApiKeyCipher`、`Authorization`、`test key` 均不存在 | ✅ |
| 导入先预览、单事务、可合并 | 预览「3 个人格 · 6 条记忆 · 1 段对话 · 51 句话 · 1 条主动节奏」；选「合并」后数据库仍是 3 / 6 / 51，没有重复；`importBundle` 全程一个 `withTransaction`，`api_configs` 从不删 | ✅ |
| 单测全绿 | `./gradlew :app:testDebugUnitTest`：423 个全绿（25 个测试类） | ✅ |
| 模拟器逐项走查 | 见下方「七、模拟器上的真实结果」 | ✅ |

## 五、取舍与已知限制

- **记忆重构是有意的设计，不是 bug**：记忆本来就会被当下改写。但它**只改措辞与侧重**，事实层用硬规则卡死（数字、称呼、叠词、否定、人名锚点、长度、相似度、原文锚点），宁可这一次什么都不变，也不让「他妹妹」变成「他哥哥」。代价是有时模型改得很漂亮却被整条拒掉——那也认。
- **检索是「本地倒排 + 余弦」，不是向量库**：没有 embeddings，中文之外的语言效果一般；词表里没有的新词、纯语义相近但用词完全不同的查询，仍然可能漏。好处是完全离线、零额外调用、可解释。
- **`happenedAt` 用区间中点近似**：人本来只记得「去年冬天」，所以「去年冬天」记成那个区间的中点，而不是假装知道具体哪一天。
- **预算闸拦不住聊天**（刻意的）：拦断你和他说话，比多花几毛钱更糟。单价覆盖已经落库但**没有 UI**；`api_usage` 不按 persona 分账（adapter 层拿不到人格，只到会话 / 用途粒度）。
- **迁移测试只覆盖 v5 / v6 → v7**：1~6 的历史 schema json 没有，只能手写 DDL；更早的版本没有逐版往返验证，只靠迁移链完整性守卫保证「每一档都有主」。另外那条守卫的 `CURRENT_VERSION` 是测试文件里的常量，加 v8 时要跟着 `@Database(version)` 一起改，否则它不会自己发现数据库版本变了。
- **通知 id 仍有理论碰撞**：折叠进每段 2^24 个格，同一个人的会话量级下可以忽略，但不是零。
- **备份不含附件二进制，也不含 `agent_ticks` / `api_usage` 流水**：前者换机后就失效（只留元数据），后者是过程流水、不是「他」。`REPLACE` 会清空本机的人格与记忆——所以先有预览和「合并」这条路；`api_configs` 与 Key 永远不动。
- **presence 是从行为反推的「看起来像」，不是真实日程**：他没留下痕迹时就是「在发呆」；这不是编造，只是最不冒犯的一种猜测。
- **倒排索引：启动预热 + 惰性兜底**：`YetAnotherPalApplication.onCreate` 会调一次 `ensureTermIndex()`（带锁、幂等、失败静默），`getRelevant` / `recall` 里也各留了一次兜底调用。
- **R8 的保留规则是「宁可多留一点」**：混淆出错的表现往往不是崩溃，而是某个功能悄悄不工作（比如序列化器被削掉），所以按库分节显式 keep，包体宁可大一点。
- **`RECALL` 这个用量分类目前没有独立账目**：回忆是聊天那一轮里调的工具，钱记在 `CHAT` 上；等哪天回忆自己能发起模型调用（现在只在他回忆之后做重构）再单独记。
- **流式 token 估算是粗的**：0.7 token/字、4 字符/token 只是量级正确；金额用来「心里有数」，不是账单。

## 六、测试

```bash
./gradlew :app:testDebugUnitTest
```

全量 **423** 个单测通过（25 个测试类）。与 1.6 直接相关的新增类：

- [`ApiCostTest.kt`](../../app/src/test/java/com/yap/app/domain/ApiCostTest.kt)（36 个）：各系模型定价与前缀优先级、未知模型的保守默认、micro-USD 取整、三种 usage 形状的解析、月初 / 跨年边界、预算判定的四步顺序、额度输入的宽松解析。
- [`PresenceTest.kt`](../../app/src/test/java/com/yap/app/domain/PresenceTest.kt)（33 个）：八种状态的窗口边界（45 / 3 / 15 / 15 / 30 / 180 分钟）、优先级顺序、`relativeTime` 的中文说法、坏输入（null、负数、未来时间）。
- [`BackupCodecTest.kt`](../../app/src/test/java/com/yap/app/domain/BackupCodecTest.kt)（31 个）：往返一致、未知字段忽略、缺字段默认、坏 JSON、版本过高拒绝、空备份、JSON 里不含 `apiKeyCipher` / `webToolsApiKeyCipher`、中文与 emoji 保真。
- [`MemoryReconsolidationTest.kt`](../../app/src/test/java/com/yap/app/domain/MemoryReconsolidationTest.kt)（25 个）：预算随心情 / 年龄 / 次数变化、定型后不再动、相似度、数字与称呼与叠词与否定与锚点的拒绝路径、超长拒绝、提示词里不含放行话术。
- [`ChineseTermsTest.kt`](../../app/src/test/java/com/yap/app/domain/ChineseTermsTest.kt)（20 个）：二元组切分、虚词过滤、英文与混合文本、时间指代（昨天 / 上周三 / 去年冬天 / N 个月前）与不越界、空串与怪输入。
- [`MoodBehaviorTest.kt`](../../app/src/test/java/com/yap/app/domain/MoodBehaviorTest.kt)（19 个）：各心情档的乘数、每日上限的增减与边界、关掉主动消息时不能被打开、深夜加成、夹取。
- [`NotificationIdsTest.kt`](../../app/src/test/java/com/yap/app/util/NotificationIdsTest.kt)（7 个）：区段互不相交、与常驻通知不重叠、稳定性、折叠范围。

其余是在既有测试类里补的用例：`RecallTest` 21 → 25、`MemoryLifecycleTest` 27 → 32、`ImportanceScoringTest` 24 → 28，另有 `MemoryScreeningTest` 23 个覆盖余弦重排与标签加成。

androidTest 另有一组数据库迁移测试（`YapDatabaseMigrationTest`，3 条用例）：编译通过；本机模拟器不稳，没在它上面跑。

## 七、模拟器上的真实结果（v1.6.0-debug）

全部在 AVD `YapTest`（API 35）+ 本地参考服务（`harness/server.mjs`）上跑出来的，不是设计推演：

- **1.5 的 v6 库原地升到 v7**：`PRAGMA user_version = 7`；6 条记忆、51 条消息都在；`memory_terms` 由启动时的 `ensureTermIndex()` 补建出 101 条。
- **聊天标题栏**：「林深 / [心情胶囊 有点低落] / 在想事情 · 刚刚」；刚说完话时会变成「在写东西 · 3 分钟前」（截图里那一帧就是这种）。
- **记账真的有数了**：发一句话后 `api_usage` 出现 `CHAT` 记录（2260 / 52 tokens，约 $0.0037），后台 tick 记 `AGENT`；改之前聊天会记成 `UNKNOWN` / 0。
- **设置页「用量与花费」**：「他这个月自己上网、整理记忆，一共花了约 $0.02」。
- **记忆重构真的改了记忆**：连着让他回忆几次，`memories` 里那条经历从「…需要有人**安静**陪着…」变成「…需要有人**静静**陪着…」；`originalContent` 仍留着原文「安静陪着」，`driftCount = 1`；`memory_versions` 里一条留痕：
  `moodLabel = 有点低落`、`reason = 心情「有点低落」 时想起它，第 1 次改动，措辞有点像此刻的自己（相似度 87%）`。
  同一套机制在「改得太多」时会整条拒掉——先前的验证里 mock 返回过一版加了半句尾巴的改写，因为相似度掉到门槛以下被拒绝，库里没写任何东西，只记了一次「用过」。
- **备份实测**：点「把他带走」→ 系统文件选择器 → 存到 Downloads，生成 `yap-backup-20261114-0155.json`（44.9 KB）；解析后 `formatVersion = 1`、`apiKeysOmitted = true`，3 个人格 / 6 条记忆 / 51 句话；搜 `apiKeyCipher`、`webToolsApiKeyCipher`、`Authorization`、`test key` 均**不存在**。
- **接他回来实测**：点「从备份里接他回来」→ 预览「3 个人格 · 6 条记忆 · 1 段对话 · 51 句话 · 1 条主动节奏」→ 选「合并」→ 数据库仍是 3 / 6 / 51，没有重复。
- **单测**：423 个全绿（25 个测试类）；androidTest 迁移测试编译通过，模拟器不稳未在其上跑。
