# Roadmap 1.3：他不是「定时器」，是一个活着的人

> **历史设计记录**：本文是当时那一版的行为规格与验收表，保留下来供追溯设计取舍；
> 当前行为以 [README](../../README.md)、[ARCHITECTURE](../ARCHITECTURE.md) 与代码为准。


1.2 的他还是「规则 + 定时器」：到点触发，规则由用户确认后生效，回复只是对用户输入的被动响应。1.3 要把他换成一个**常驻的自主循环**——他自己想、自己决定、自己去做，发消息只是他手边的一个工具。

## 一、他要一直在

**后台前台服务 `AgentLoopService`**（`dataSync` 类型，带常驻通知）跑一个循环：

```
每 6–15 分钟（随机，避免机械感）醒来一次
  → 看看现在是什么时候、自己困不困、对方在不在、上次说话隔了多久
  → 想一想（一次很便宜的模型调用，允许输出「什么都不做」）
  → 决定动作并执行
  → 记录这次思考（可回看），再睡回去
```

动作集合（模型自己挑，可组合）：

| 动作 | 含义 |
| --- | --- |
| `nothing` | 什么都不做（**默认应该是这个**） |
| `message` | 想说话了就发一条（落库 + 系统通知） |
| `browse` | 自己上网看看，结果写成一条「近况」记忆 |
| `remember` | 把刚想到的事写进长期记忆 |
| `intent` | 给未来的自己留一个意向（「明早问问她睡得怎么样」），带一个时间**窗口** |
| `persona_patch` | 想改自己，仍要对方点头 |
| `quiet_hours` | 自己安排作息 |

循环状态全部落库（`agent_ticks` / `agent_intents`），进程被杀后重启能接着来，不是内存里的定时器。

## 二、抛弃「固定时刻」

- 唤醒不再等于「22:30 准时出现」，而是**意向 + 窗口**：`agent_intents` 里每条意向只有 `earliestAt` / `latestAt`。
- 规则（每天/每间隔）只负责**生成意向**：到点后在一个随机窗口里散开（默认 ±90 分钟），并且有概率直接放弃（今天就是不想说）。
- 他自己也能写意向：`intent` 动作可以让「明早」「等你下班」「过两小时再问」这类模糊时间落成一个窗口。
- 追问像人：他发完一条、对方 25–70 分钟没回，有概率补一句更轻的（同一条意向最多追问一次）。

## 三、克制（像人而不是像推送）

- 每人格每天主动消息上限（默认 3 条，可调）；
- 两条主动消息最小间隔 90 分钟；
- 他睡觉的时间里不主动开口；
- 对方正在打字/刚说完话时，不叠一条主动消息；
- 连续两次主动都没人回 → 自动降低频率（意向窗口越推越后）。

## 四、完全免确认，但仍然看得见

- AI 提议的唤醒/意向**直接生效**；他对**自己人设的修改也直接生效**，不再有任何「等你点头」的卡片。
- 安全网不是「审批」，而是**版本历史**：每次自我修改都会归档成一个版本，可以在人格版本里回滚。
- 全部在「主动唤醒」页面可见：来源（他自己 / 你设的）、下一次大概什么时候、为什么、可以一键关掉或删掉。
- 聊天里只留一句不起眼的提示（「他给自己安排了一件事，不喜欢可以关掉」），没有需要你点的按钮；旧数据里残留的「待确认」补丁在启动时自动落库一次。

## 五、让用户把他留在后台

新增「让他在后台活着」引导（设置页 + 聊天页一次性卡片）：

- 通知权限（Android 13+ 必须）
- 忽略电池优化（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）
- 前台常驻开关（关掉就退回 WorkManager 的粗粒度唤醒）
- 说明：手机厂商的自启动/后台白名单需要在系统设置里手动允许

## 六、回复也要「像个消息」

- 只要聊天页不在前台，他回复完成后就发一条系统通知（和主动消息同一个渠道族，按人格聚合），点开直接进对应会话。
- 前台正在这个会话里时不打扰。

## 七、验收

| 项 | 验收方式 | 状态 |
| --- | --- | --- |
| 免确认生效 | 对话里说「以后每天早上来问我一句」，规则立即 `enabled=1 / pending=1`，聊天页提示「他给自己安排了一件事」 | ✅ 模拟器实测 |
| 回复通知 | 发消息后按 Home，收到渠道 `chat_reply` 的通知（含回复正文），点开进会话 | ✅ 模拟器实测 |
| 随机窗口 | 规则 → 意向时写入随机窗口；每日时刻 ±25 分钟、间隔 ×0.75–1.35；18% 放弃 | ✅ 代码 + 库表实测（`agent_intents` 两条：REACH_OUT 与自动 FOLLOW_UP） |
| 自主循环 | 前台服务 `specialUse` 常驻、通知「他还醒着」；`agent_ticks` 记录每次思考 | ✅ 模拟器实测（含「什么都没做」「忍住了」「发了一条消息」「留了个念头」四种结果） |
| 动作可用 | `message` 落库 + 通知；`intent` 落库并在主动唤醒页可见 | ✅ 模拟器实测 |
| 克 制 | 每天上限、90 分钟间隔、他睡觉静默、对话正热不插话 | ✅ 实测「本想说话，忍住了（你们正聊着）」 |
| 后台引导 | 设置页「让他在后台活着」：开关 / 通知权限 / 电池白名单 / 每日上限 | ✅ 模拟器截图 |
| 质量 | 131 个单测全绿、`assembleDebug` 通过、模拟器冷启动无崩溃 | ✅ |

实现落点：

| 关注点 | 代码 |
| --- | --- |
| 自主循环（想一次） | [`AgentLoop.kt`](../../app/src/main/java/com/yap/app/data/repo/AgentLoop.kt) |
| 前台服务 / 总开关 / 开机恢复 | [`AgentLoopService.kt`](../../app/src/main/java/com/yap/app/service/AgentLoopService.kt)、[`AgentLoopController.kt`](../../app/src/main/java/com/yap/app/service/AgentLoopController.kt)、[`BootReceiver.kt`](../../app/src/main/java/com/yap/app/service/BootReceiver.kt) |
| 兜底 tick | [`AgentTickWorker.kt`](../../app/src/main/java/com/yap/app/data/repo/AgentTickWorker.kt) |
| 意向与窗口 / 克制 | [`AgentIntentRepository.kt`](../../app/src/main/java/com/yap/app/data/repo/AgentIntentRepository.kt) |
| 决策协议与提示词 | [`AgentActions.kt`](../../app/src/main/java/com/yap/app/domain/AgentActions.kt) |
| 回复通知 | [`ChatNotifier.kt`](../../app/src/main/java/com/yap/app/util/ChatNotifier.kt) |
| 常驻通知 | [`AgentNotifier.kt`](../../app/src/main/java/com/yap/app/util/AgentNotifier.kt) |
| 前后台判定 | [`AppForegroundTracker.kt`](../../app/src/main/java/com/yap/app/util/AppForegroundTracker.kt) |
| 权限引导 | [`BackgroundKeeper.kt`](../../app/src/main/java/com/yap/app/util/BackgroundKeeper.kt) |

已知取舍：
- 前台服务用 `specialUse` 而不是 `dataSync`：Android 15 对 dataSync 有 6 小时/天硬上限，会把他掐死。
- 部分品牌 ROM 的后台白名单（自启动）无法用代码申请，只能引导用户去系统设置。
- 意向/思考记录只保留最近 7 天；「上次浏览时间」这类频率状态在进程内，重启只影响频率不影响事实。
