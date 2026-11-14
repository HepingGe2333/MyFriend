# Roadmap 1.4：每个时刻都有心情

> 注：本文记录的是当时版本的设计与实现；「原生 Harness」模式已在 1.8 移除，只保留无状态兼容，相关描述不再适用于当前代码。

1.3 的他一直醒着，也知道自己想做什么；但温度是恒定的——刚被安慰过，和刚被怼完，语气差不多。1.4 给他加一层**会自己变的心情**：每一刻都有一个词、一种浓度、一句为什么，它常驻在提示词里影响语气，会随时间自己淡下去，也能被他自己改掉。

这一版只做「心情」这一件事：除了一个小工具 `set_mood`，不碰回复节奏，也不扩别的能力——心情影响的是**措辞**，不是回复长度与间隔。

## 一、目标

- **每个时刻都有心情**：一个常驻状态（词 + 浓度 + 原因 + 更新时间），不是一次性的表情，也不随消息消失。
- **心情由他自己说**：客户端只做规整（去空白、限长、夹强度），不替模型判断「该不该开心」。
- **心情会自己淡**：没人动它也会回落，避免「三天前的心情还挂在那儿」。
- **心情能被引导**：说过几句话就轻轻问一句「现在我的心情变得……？」，第一人称自言自语，而不是「请更新情绪状态」这种机械指令。
- **心情能被主动指定**：聊天里一个工具、后台思考里一个动作，两条路都能改，改完立刻反映在提示词与界面上。

## 二、用户看到什么

- **聊天标题栏常驻一枚胶囊**：模型名左侧，5 个点表示浓度 + 一个词；颜色随心情正负变化——负面偏暖红、低能量偏土黄、正面偏绿、中性灰。刚认识时是「平静」。

![聊天标题栏的心情胶囊](screenshots/mood-chip.png)

- **说一句带情绪的话，他可能顺手改心情**：气泡下方出现标题「心情变了」、副标题形如「有点低落（3/5） · 听你说累，我这边也跟着安静下来」的动作卡片，标题栏胶囊同时变成「有点低落」。

![模型调用 set_mood 之后的动作卡片](screenshots/mood-tool-trace.png)

- **「主动唤醒」页多一张「他此刻的心情」**：胶囊 + 一句原因。只展示，不提供手动调节——心情是他自己的状态，不是用户填的表。

![主动唤醒页的心情卡片](screenshots/mood-card.png)

- **说够几句话之后，提示词末尾会多一段「## 心里那句话」**：引导他回看「现在我的心情变得……？」，真的变了就自己调用 `set_mood`，没变就别动。

![提示词末尾的引导词](screenshots/mood-reflection.png)

- 界面上的胶囊不解释、不弹窗、不提问。心情是背景信息，不该抢走对话本身。

## 三、实现地图

### 1. 领域层（纯 Kotlin，不依赖 Android）

`Mood.kt` 里只有四样东西：

- `MoodState(label, intensity, reason, updatedAt)`：一个词的标签、1..5 的浓度、一句话原因、上次真正变化的时间；`display()` 形如「有点低落（3/5） · 听你说累，我这边也跟着安静下来」。
- `MoodLexicon`：基准词「平静」；词表带 `valence`（正面 / 负面）与 `energy`（有劲 / 没劲）两个维度；未知词按中性处理，**不猜**；`isNegative` / `isLowEnergy` 只用于措辞与自动回落，不参与模型决策。
- `MoodDrift`：情绪会自己淡——每小时强度 -1，6 小时后回到「平静」，负面词淡到最低时先被抹平；`shouldReflect` 判断「说了 3 句话」或「4 小时没动过心情、又聊过」该不该回看。
- `MoodPrompt`：`line()` 是常驻系统提示词的一段（词、强度、原因 + 一句语气指引，负面时是「说话慢一点、少一点，但不要卖惨」）；`reflectionNudge()` 是第一人称引导词，形如「说了 3 句话，停一下，问自己一句：现在我的心情变得……？真的变了就调用 set_mood 更新，没变就别动」。

### 2. 持久化

- Room v4 → v5（`MIGRATION_4_5`），`personas` 表加 `mood` / `moodIntensity` / `moodReason` / `moodUpdatedAt` / `moodNudgedAt` 五列。
- 心情跟着人格走：不另建表，也没有独立生命周期——人格在，心情就在。`moodNudgedAt` 记「上次被引导过」，避免同一句话被反复追问。

### 3. 仓库

`MoodRepository` 把心情的读、写、判、拼收在一处：

| 方法 | 用途 |
| --- | --- |
| `observe` | 界面用，读的时候顺手让时间衰减 |
| `current` | 衰减后如有变化就落库，免得界面与提示词对不上 |
| `setMood` | 规整词与强度后落库，返回落库后的状态 |
| `reflectionNudge` | 到点返回引导词并记 `moodNudgedAt`；不到点返回 null |
| `promptLine` | 拼系统提示词那一段 |

### 4. 模型主动指定心情

- **聊天内**：工具 `set_mood(mood, intensity?, reason?)` 加进 `ToolCatalog`，双协议都支持（原生 `tool_calls` 与 `myfriend-tool` 代码块），`ToolExecutor` 里落库。动作卡片标题「心情变了」、副标题是 `display()`；回灌给模型的话是「对方不知道你记了心情，也不用主动汇报，接着把话说完」。
- **后台一个人待着时**：agent 协议新增 `mood` 动作（`AgentAction.Mood`，中文别名「心情 / 情绪」都能解析，强度自动夹到 1..5），`AgentLoop` 执行时直接落库，并在这次思考的 notes 里写「心情变成「有点低落」」。

### 5. 心情进三处提示词

| 位置 | 代码 | 注入什么 |
| --- | --- | --- |
| 聊天（无状态与 Harness 两种模式） | [`ChatRepository.kt`](../app/src/main/java/com/myfriend/app/data/repo/ChatRepository.kt) | `promptLine()`；到点时把 `reflectionNudge()` 追加在系统提示词末尾「## 心里那句话」 |
| 主动开口 | [`ProactiveRepository.kt`](../app/src/main/java/com/myfriend/app/data/repo/ProactiveRepository.kt) | `promptLine()` |
| 后台自主思考 | [`AgentLoop.kt`](../app/src/main/java/com/myfriend/app/data/repo/AgentLoop.kt) | `promptLine()`，另有 `mood` 动作可改 |

### 6. 界面

- `MoodChip`（5 个点 + 一个词）与 `MoodLine`（再加一句原因）。
- 聊天标题栏在模型名左侧常驻胶囊，`ChatViewModel` 新增 `mood` 状态。
- 「主动唤醒」页新增「他此刻的心情」卡片，只读。

### 7. 版本

`versionCode 5 / versionName "1.4.0"`，数据库版本 5。

实现落点（便于以后回溯）：

| 关注点 | 代码 |
| --- | --- |
| 心情的领域模型 / 词表 / 衰减 / 引导时机 / 提示词措辞 | [`Mood.kt`](../app/src/main/java/com/myfriend/app/domain/Mood.kt) |
| 心情的存取与引导 | [`MoodRepository.kt`](../app/src/main/java/com/myfriend/app/data/repo/MoodRepository.kt) |
| 表结构与迁移 | [`Entities.kt`](../app/src/main/java/com/myfriend/app/data/local/Entities.kt)、[`Daos.kt`](../app/src/main/java/com/myfriend/app/data/local/Daos.kt)、[`MyFriendDatabase.kt`](../app/src/main/java/com/myfriend/app/data/local/MyFriendDatabase.kt) |
| 聊天工具 `set_mood` | [`ToolProtocol.kt`](../app/src/main/java/com/myfriend/app/domain/ToolProtocol.kt)、[`ToolExecutor.kt`](../app/src/main/java/com/myfriend/app/data/repo/ToolExecutor.kt) |
| 后台 `mood` 动作 | [`AgentActions.kt`](../app/src/main/java/com/myfriend/app/domain/AgentActions.kt)、[`AgentLoop.kt`](../app/src/main/java/com/myfriend/app/data/repo/AgentLoop.kt) |
| 胶囊 / 一行心情 | [`MoodChip.kt`](../app/src/main/java/com/myfriend/app/ui/components/MoodChip.kt) |
| 聊天标题栏 | [`ChatViewModel.kt`](../app/src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt)、[`ChatScreen.kt`](../app/src/main/java/com/myfriend/app/ui/screens/chat/ChatScreen.kt) |
| 主动唤醒页的心情卡片 | [`ProactiveScreen.kt`](../app/src/main/java/com/myfriend/app/ui/screens/proactive/ProactiveScreen.kt) |
| 单测 | [`MoodTest.kt`](../app/src/test/java/com/myfriend/app/domain/MoodTest.kt) |

## 四、验收

| 项 | 怎么验证 | 状态 |
| --- | --- | --- |
| 每个时刻都有心情 | 打开任一聊天，标题栏胶囊常驻：一开始是「平静」，被他改过之后随实际变化 | ✅ |
| 心情会自己淡下去 | 设定上 6 小时回落到「平静」，强度逐小时递减；`MoodDrift.decay` 的单测覆盖 | ✅ |
| 每说几句就有引导词 | 模拟器实测第 12 句时，系统提示词末尾出现「## 心里那句话 / 现在我的心情变得……？」（harness 日志可查） | ✅ |
| 模型能主动指定心情 | 说一句带情绪的话，模型调用 `set_mood`：气泡下方出现「心情变了」动作卡片，标题栏胶囊立刻变成「有点低落」 | ✅ |
| 后台也能改心情 | `AgentLoop` 的 `mood` 动作；单测覆盖解析、中文别名、强度夹取、序列化往返 | ✅ |
| 心情进提示词 | 聊天 / 主动开口 / 后台思考三处都注入，代码位置见实现地图第 5 节 | ✅ |
| 数据库升级不丢数据 | v4 → v5 迁移：模拟器上从 1.3 的库直接升级，未崩溃、旧数据都在 | ✅ |
| 单测 | 152 个全绿，其中新增 `MoodTest.kt` 21 个（词表规整、衰减、引导时机、提示词措辞、agent 协议心情动作解析） | ✅ |

## 五、取舍与已知限制

- **心情词由模型自己说**，客户端只做规整（去空白、限 12 字、强度夹 1..5），不替模型判断「该不该开心」。代价是词表里没有的新词会按中性处理，措辞指引也就更保守。
- **衰减是简化模型**：固定每小时 -1、6 小时归零，不是真实情绪曲线。宁可简单可预期，也不做黑箱——用户看一眼胶囊上的点就知道为什么是这个浓度。
- **`moodNudgedAt` 不会连续追问**，但也意味着「他心情没变」时不会刷新 `moodUpdatedAt`，所以胶囊上的浓度只会随时间下降，不会凭空虚涨；想变浓，只能他自己真的调用 `set_mood`。
- **心情目前只影响措辞与语气**（提示词），不影响回复长度、间隔等节奏参数——节奏仍由 `HumanTiming` 与回复节奏设置决定。
- **「主动唤醒」页的心情卡片只读不写**：想改就让他自己改（聊天里说一句，或后台思考时自己调 `set_mood`），不给用户一张调节表。

## 六、测试

```bash
./gradlew :app:testDebugUnitTest
```

全量 152 个单测通过，其中本版新增 `MoodTest.kt` 21 个：

- **词表规整**：空标签回落到「平静」、去空白与标点并限长、未知词按中性处理、负面与低能量判断、展示文案带强度与原因。
- **衰减**：几小时后回到基准、每小时掉一点但不会掉到零、淡到最低时负面情绪先被抹平、刚发生的心情不会被打折、空心情衰减后至少有基准词。
- **引导时机**：说够三句就该回看、很久没动过心情时聊一句也值得看看、刚被问过就不会再问同样一句。
- **提示词**：有心情时带上词与原因、没有心情时也给一句基准描述。
- **agent 协议**：`mood` 动作的解析、中文别名（心情 / 情绪）、强度夹取与序列化往返。

除单测外，模拟器上逐项走过：胶囊常驻与变色、「心情变了」动作卡片、主动唤醒页的心情卡片、第 12 句出现的「## 心里那句话」，以及从 1.3 的数据库直接升级到 v5 不丢数据。

## 七、顺手修掉的两处（1.3 遗留）

心情这条链路要求「他每次思考都要看见自己现在的心情」，而这一点正好把两个旧问题暴露了出来，于是在本版一起收掉：

- **同一个念头不再堆一排**：他有时会在几次思考里反复想到同一句话，旧逻辑每次都插一条，主动唤醒页会出现四五条一模一样的「问问他白天的项目进展」。现在 `AgentIntentRepository.plan` 遇到「同人格 + 同动作 + 同一句备注」时只把窗口往后挪，另外每次 tick 开头会跑一遍 `collapseDuplicates()`，把历史遗留的重复念头并成一条（其余标成已放弃）。
- **进程被反复拉起不会连着自言自语**：前台服务与 WorkManager 都可能把进程拉起来，旧逻辑每次 `onStartCommand` 都会立刻 `tick()` 一次，于是开机后一分钟内他可能连想三四次。现在服务起来先看一眼 `agent_ticks` 里最近一次思考的时间，不到 5 分钟就先等一会儿（`AgentLoopService.FIRST_TICK_GAP_MS`）。
