# 1.9 规格：他是联系人，不是一个 API

> **历史设计记录**：本文是当时那一版的行为规格与验收表，保留下来供追溯设计取舍；
> 当前行为以 [README](../../README.md)、[ARCHITECTURE](../ARCHITECTURE.md) 与代码为准。


> **编号说明（开源发布）**：本文档写作时这一版的编号是 `1.9.0`；开源时把当时的最新版改成了内测编号
> `0.9.0`（内容完全一致），之后的小版本为 `0.9.1`、`0.9.2`……规则见 [CONTRIBUTING.md](../../CONTRIBUTING.md)。

这一版把「设置项」从产品里拿掉，把决定权交回给模型与人格。核心一句话：
**用户面对的是一个联系人，不是一个可以调参数的机器人。**

本文件是 Android 与 iOS 的**唯一事实来源**。两端字段名、枚举值、算法与文案以这里为准；
任何一端偏离，都算 bug。

---

## 一、要删掉的东西（用户不再能调）

| 现在 | 1.9 之后 |
| --- | --- |
| 长期记忆 / 自动抽取 / 每天整理 / 联网打分 四个开关 | **全部内置开启**，设置里删除 |
| 「读消息的节奏」（立刻 / 自然 / 慢一点） | **由模型自己决定**（见 §四），设置里删除 |
| 月度额度、超支只聊天、输入/输出单价 | **整段删除**（没有任何拦截）；只保留 token 统计（见 §五） |
| 「每天最多主动几次」 | **由人格决定**（`proactiveLevel`），设置里删除 |
| 同一个人的多段会话 | **一人一段**（见 §二） |
| 作息「几点睡几点醒」的固定语义 | 改成「他会不会主动睡觉 + 睡前是否开免打扰 + 唤醒阈值」（见 §六） |

---

## 二、数据模型：一人一段会话

### 2.1 结构

- `chat_sessions` 对 `personaId` 建**唯一索引**（`index_chat_sessions_personaId_unique`）。
- 会话不再有「新建」这个概念：创建人格时自动建他的那一段。
- 首页不再分「人格」「最近对话」两块，而是**一个联系人列表**（见 §七）。

### 2.2 迁移（Android v9 → v10，iOS schema v9 → v10）

对每个 persona：

1. 取该人格的全会话，按 `COALESCE(lastMessageAt, updatedAt)` 排序；
2. **保留最新的那一段**，其余会话连同其消息、摘要、主动规则里的 `sessionId` 引用一起删除；
3. 保留段的 `apiConfigId` 若为空，补成当前默认配置；
4. 删除后建唯一索引。

数据损失是用户明确要求的（「只保留最新那段，其余直接删除」）；迁移文档与空库测试都要覆盖。

### 2.3 消息已读

`messages` 新增 `readAt INTEGER`（可空）。

- 用户消息在**模型看到它的那一刻**写入 `readAt = now`；
- 界面：用户气泡右下角显示小字「已读」（仅在 `readAt != null` 且本轮没有助手回复时显示，避免每条消息都挂一个「已读」）；
- 已读**不保证会回**（§四）。

---

## 三、人格（=联系人）的细分字段

`personas` 新增列（全部可空 + 代码里有默认值，老数据不用回填）：

| 字段 | 类型 | 含义 | 默认 |
| --- | --- | --- | --- |
| `traits` | TEXT(JSON 数组) | 性格，如 `["慢热","嘴硬心软"]`，≤6 个 | `[]` |
| `likes` | TEXT(JSON 数组) | 喜欢的事，如 `["夜里散步"]`，≤8 个 | `[]` |
| `behaviors` | TEXT(JSON 数组) | 行为方式，如 `["先听完再给建议"]`，≤6 个 | `[]` |
| `backstory` | TEXT | 成长经历 / 历史，≤600 字 | null |
| `quirks` | TEXT | 个人设定：称呼、口头禅、忌讳，≤300 字 | null |
| `proactiveLevel` | INTEGER | 主动程度 1–5（1 很少主动，5 很黏） | 3 |
| `memoryRetention` | INTEGER | 记性 1–5（1 忘得快，5 什么都记得） | 3 |
| `extractionEvery` | INTEGER | 每多少条消息抽一次记忆（3–12） | 6 |
| `sleepEnabled` | INTEGER(bool) | 他会不会主动睡觉 | 1 |
| `wakeThreshold` | INTEGER | 睡眠中被唤醒需要的连续消息数（1–20） | 6 |
| `dndOnSleep` | INTEGER(bool) | 睡前是否开启免打扰（**模型可改**，见 §六） | 0 |

会话级覆盖（`chat_sessions` 新增，可空 = 继承人格）：

- `memoryRetentionOverride`、`extractionEveryOverride`。

**提示词**：这些字段拼成一段结构化人设（标题「## 你是谁」下）：性格 / 喜欢 / 习惯 / 过去 / 设定；
没有填的字段不占位。`proactiveLevel` 与 `memoryRetention` **不进提示词正文**，它们是行为参数。

---

## 四、回复节奏由模型决定：`defer_reply`

新增工具动作（Android `myfriend-tool` / iOS 同名）：

```json
{"name":"defer_reply","arguments":{"minutes":90,"reason":"现在不想说话"}}
```

语义：

1. 模型这一轮**选择不回**：不产生助手消息（不写空消息、不留半句），但用户消息已被标记已读；
2. 写一条 `agent_intents`（kind `REACH_OUT`，`earliestAt = now + minutes`，note = reason）；
3. 由心跳 / 前台循环在到点时让他自己决定开不开口（他仍然可以选择继续沉默，此时再写一条 intent 或放弃）；
4. `minutes` 缺省 45，夹在 5–720；`reason` ≤80 字，只进日志与「他最近在想什么」，不进聊天。

界面：用户消息显示「已读」；**不显示**「对方正在输入」、不显示任何「他可能不想回」的解释。

---

## 五、并发与用量

### 5.1 并发上限：全局 3

- 所有 LLM 调用（聊天 / 抽取 / 整理 / 打分 / 思考 / 回忆重构 / 历史压缩）共享一个**全局闸门，上限 3**；
- **聊天有保底**：后台任务最多同时占 2 个位，聊天总能进剩下那 1 个；实现为「后台名额 2 + 全局名额 3」两层；
- 排队等待不设无限等待：后台任务等 20 秒拿不到就放弃（记一条日志，不抛给用户）；聊天等 60 秒；
- 闸门必须是可测的纯逻辑（信号量 + 计数），不能散在各个调用点。

### 5.2 用量：只统计 token

- 删除 `ApiBudget` / `budgetVerdict` / 超支拦截 / 定价覆盖设置；
- `api_usage` 保留并**必须写 sessionId**（现有行可为空）；
- 设置页「用量与花费」改成「用量」：本月 token 总数 + 按用途分类 + **按会话（= 按人）排行**（前 10）+「只记在本机」；
- 不显示钱、不显示额度、没有输入框。

---

## 六、睡眠、免打扰与唤醒阈值

### 6.1 状态

- `sleepEnabled = false`：**他不睡觉**，任何时间都会回。
- `sleepEnabled = true`：在他自己的作息窗口里睡觉（沿用 `quietSleepMinutes` / `quietWakeMinutes`；未设时用 23:30–08:00）。
- `dndOnSleep`：睡前由**模型**决定（动作 `dnd` 或 `quiet_hours` 的 `dnd: true` 参数），持久化到人格；用户不能直接改。

### 6.2 睡觉期间收到消息

```
if !sleepEnabled or 不在睡眠窗口: 正常回复

if dndOnSleep:                        # 免打扰
    不回复、不唤醒、不显示已读后的任何反应
    消息照常落库；醒来后按「一段时间的消息」一起处理

else:                                 # 没开免打扰
    recent = 最近 30 分钟内该人格会话里用户发的消息数（含这一条）
    if recent >= wakeThreshold:
        唤醒：mood 恢复（强度 -1，若为负面词则改「平静」）、写一条 tick 记录（outcome 含「被你叫醒了」）
        立刻回复（这一轮正常走）
    else:
        不回复（但已读）；等他自然醒
```

- 唤醒后 `wakeThreshold` 计数天然清空（靠 30 分钟窗口滚动，不额外建表）；
- 界面在聊天页顶部状态行显示「睡着了 · 23:30 醒」，**不**额外提示「他被你吵醒了」；
- 醒来时心情改善要写进 `persona.mood` 与 `moodUpdatedAt`，并在「他最近在想什么」里留一句。

---

## 七、界面（两端一致）

### 首页 = 联系人列表

- 一行一个联系人：头像、名字、最后一条消息预览（或「还没有说过话」）、时间、未读数；
- 顶部标题 `MyFriend` + 一句副标题；右上角「设置」；人格管理入口放在设置里或右上「管理」；
- 没有「新建对话」按钮；「+」= **新建人格**（即新建联系人）；
- 左右滑动或长按联系人 → 删除（连同消息/记忆/规则，需二次确认）。

### 人格编辑（创建/编辑）

分节填写：**名字 / 一句话介绍 / 性格（多选+自定义）/ 喜欢的事 / 行为方式 / 成长经历 / 个人设定 /
主动程度（1–5 滑杆，带人话解释）/ 记性（1–5）/ 抽记忆频率（3–12）/ 会不会主动睡觉（开关）/
睡觉时间段（睡/醒）/ 睡眠唤醒阈值（1–20）/ 提示词全文（高级，可折叠）**。

### 设置（瘦身）

只剩：外观（主题/主色/字号/气泡样式/动画/触感）、API 配置、**用量（只统计）**、
备份与恢复、数据清除、关于。
记忆开关、节奏、额度、主动次数**全部删除**。

### 聊天页

- 顶栏：头像、名字、心情胶囊、状态行（`Presence.display()`；睡着时显示「睡着了 · 23:30 醒」）；
- 用户消息在无回复时显示「已读」；
- 其余保持 1.8 行为（Markdown、链接、长按菜单、工具痕迹）。

---

## 八、备份格式 v2

- `formatVersion` 升到 **2**；`format` 仍是 `myfriend.backup`；
- `personas` 增加 §三 的 9 个字段；`sessions` 每人一条（若备份里同一人格多条，导入时保留最新一条）；
- `settings` 删除：`replyPace`、`memoryEnabled`、`autoMemoryEnabled`、`memoryConsolidationEnabled`、
  `webSearchScoringEnabled`、`proactiveDailyLimit`、`monthlyBudgetMicroUsd`、`overBudgetChatOnly`、
  `inputPriceUsdPerMillion`、`outputPriceUsdPerMillion`；
- `messages` 增加 `readAt`（可空）；
- **必须能读 v1 备份**：v1 的开关字段忽略、`replyPace` 忽略、人格缺的字段用默认值、
  v1 多会话按「保留最新一条」导入；读 v2 的旧客户端会被 `formatVersion > 1` 明确拒绝（现有逻辑已如此）。

---

## 九、验收（两端各自都要有）

| 项 | 判据 |
| --- | --- |
| 一人一段 | 迁移后人手一条会话；旧的多余会话与消息确实删除；唯一索引存在 |
| 并发闸 | 纯逻辑测试：3 个并发 + 聊天保底；后台连开 5 个任务最多同时 2 个在跑 |
| defer_reply | 模型选择不回时：无助手消息、用户消息 `readAt` 非空、生成 `agent_intents` 一条 |
| 睡眠阈值 | `dndOnSleep=false` 且消息数 < 阈值 → 不回；≥ 阈值 → 回且 mood 被拉回；`dndOnSleep=true` → 永不唤醒 |
| 消失的设置 | 设置页不再出现记忆开关 / 节奏 / 额度 / 主动次数；代码里也搜不到这些设置键 |
| 用量统计 | 按用途与按会话（人）两种口径都能出数；没有任何拦截逻辑 |
| 人格细分 | 新建人格填满 5 个结构化字段后，system 提示词里出现对应小节；老数据不填也能用 |
| 备份 v2 | 导出再导入往返一致；能读 v1 样本；fixtures 重新生成 |
| 测试 | Android 全量单测绿；iOS `swift test --disable-sandbox` 全绿；两端 App 都能编译 |

---

## 十、里程碑

1. **数据层**（两端）：实体/DAO/schema v10/迁移/备份 v2/设置清理 + fixtures；
2. **行为层**（两端）：并发闸、删预算、`defer_reply` + 已读、睡眠阈值与唤醒、主动上限由人格决定；
3. **界面**（两端）：联系人首页、人格编辑分节、设置瘦身、聊天「已读」；
4. **验收**：两端构建 + 单测 + 真机/模拟器走查，截图与文档更新。

---

## 十一、验收结果（1.9.0 已交付）

两端同一版 1.9.0（Android `versionCode 10` / iOS `CURRENT_PROJECT_VERSION 10`）。

| 项 | Android | iOS |
| --- | --- | --- |
| 一人一段 | ✅ 迁移 v9→v10 后 2 个人格 2 段会话；`index_chat_sessions_personaId_unique` 唯一索引；多余会话的消息随会话删除 | ✅ 迁移用例覆盖「v8 库 → 一人一段 + 删多余消息 + 摘掉 `proactive_rules.sessionId` + 补 `apiConfigId` + 唯一索引 + Harness 列消失」，并验证幂等与空库 |
| 并发闸 | ✅ `LlmConcurrencyGateTest`（全局 3 / 后台 2 / 聊天保底） | ✅ `LlmConcurrencyGateTests` 7 条，含「后台连开 5 个最多 2 个在跑」「聊天保底第 3 位」「超时也归还名额」 |
| `defer_reply` | ✅ 模拟器实测：用户消息「22:55 · 已读」、无助手气泡；DB `messages.readAt` 非空、无助手行、`agent_intents` 一条 `REACH_OUT/PENDING`、窗口 5 400 000 ms、note 为模型所写 | ✅ 模拟器实测同形；另有 `ChatRhythmTests` 覆盖「免打扰不留已读」「阈值下只已读」「唤醒后回复 + mood + tick」 |
| 睡眠阈值 | ✅ `SleepPolicyTest`（免打扰永不清醒 / 阈值以下只已读 / 到阈值唤醒并把 mood 拉回） | ✅ `SleepScheduleTests` 10 条 + 模拟器实测「睡着 · 01:40 醒 → 攒够条数被叫醒并回话」 |
| 消失的设置 | ✅ `SettingsRemovalGuardTest` + 全仓 grep 干净；截图 `android-settings-usage-19.png` 只剩外观/API/用量/备份/数据/关于 | ✅ `SettingsRetirementTests`（扫生产源码里的退役标识符 + 设置页无退役控件） |
| 用量统计 | ✅ `UsageTotalsTest` + `ApiCostTest`：只出 token（总数 / 按用途 / 按人前 10），无任何拦截 | ✅ `UsageRecorderTests`：token 口径 + `byKind` + `bySession` 前 10 稳定排序 |
| 人格细分 | ✅ `PersonaProfileTest`：5 个结构化字段进「## 你是谁」，空字段不占位；两个行为参数不进正文 | ✅ `PromptBuilderTests` 4 条：五小节、空字段不占位、行为参数不漏、老数据 |
| 备份 v2 | ✅ `BackupCodecTest` / `BackupFixtureTest`：v2 往返、v1 可读、两份 v2 样本互读 | ✅ `BackupServiceTests` / `BackupInteropTests`：同左，另加「合并时同人冲突并进本机那段」 |
| 测试 | ✅ **601 条 / 40 类 / 0 失败**（`--rerun-tasks --no-build-cache` 真跑） | ✅ **653 条 / 0 失败**（`swift test --disable-sandbox`）；`xcodebuild … build` **BUILD SUCCEEDED** 无警告 |
| 走查截图 | `android-home-19.png` / `android-persona-editor-19.png` / `android-persona-editor-19-more.png` / `android-settings-usage-19.png` / `android-read-receipt-19.png` | `ios-home-19.png` / `ios-persona-editor-19.png` / `ios-settings-19.png` / `ios-read-receipt-19.png` / `ios-defer-reply-19.png` |

已知未做（1.9 之外，逐条写在 `docs/IOS-PARITY.md`）：iOS 聊天侧工具执行器、图片附件、主动唤醒规则落库、历史压缩编排、人设卡编辑器。
