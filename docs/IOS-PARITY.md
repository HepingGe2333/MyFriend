# iOS 端（iOS 16+）与 Android 端的功能对齐与备份互通

> **编号说明（开源发布）**：本文档写作时这一版的编号是 `1.9.0`；开源时把当时的最新版改成了内测编号
> `0.9.0`（内容完全一致），之后的小版本为 `0.9.1`、`0.9.2`……规则见 [CONTRIBUTING.md](../CONTRIBUTING.md)。

iOS 版不是「一个能聊天的客户端」，而是同一套东西换一个平台：同一份人格、同一套记忆、
同一条心跳曲线、同一份备份文件。这份文档规定**哪些必须一模一样**、**哪些受平台限制只能近似**、
以及**怎么用机器验证**。

## 一、工程结构

```
ios/
├── MyFriendKit/
│   ├── Package.swift                 本地 SPM 包（iOS 16 / macOS 13），用来 swift test
│   ├── Sources/MyFriendKit/          与 UI 无关的一切（同时被 app target 直接编译进 App）
│   │   ├── Domain/                   纯算法：ChineseTerms / LocalEmbedding / VectorMath / Mood /
│   │   │                             Presence / Heartbeat / HistoryCompression / LinkDetector /
│   │   │                             MarkdownParser / ImportanceScoring / Recall / ToolProtocol /
│   │   │                             SleepSchedule（1.9 的睡眠-免打扰-唤醒阈值）
│   │   ├── Data/                     SQLite（schema 与 Android Room v10 对齐）+ Repositories + Settings
│   │   ├── Net/                      URLSession SSE 流式、三种 provider 的 usage 形状、
│   │   │                             LlmConcurrencyGate（全局 3 / 后台 2 / 聊天保底 1）
│   │   ├── Backup/                   BackupCodec（与 Android 逐字段一致）+ 导入导出
│   │   └── Support/                  JSON、日期、Keychain、日志
│   └── Tests/MyFriendKitTests/       swift test（macOS 上跑，不起模拟器）
├── MyFriend/                         SwiftUI 应用层（iOS 16 起可用的 API）
│   ├── App/                          MyFriendApp、依赖装配
│   ├── Views/                        首页 / 聊天 / 内置浏览器 / 人格 / 记忆 / 设置 / 备份
│   └── Resources/
└── MyFriend.xcodeproj                部署目标 16.0，无第三方依赖
```

**不引第三方依赖**（不装 GRDB/SQLDelight/Ktor）：SQLite 直接用系统 `SQLite3`，网络直接用
`URLSession`，序列化直接用 `Codable`。理由是这套代码要长期能编译，而不是三个月后卡在依赖上。

## 二、功能对齐表

| 能力 | Android | iOS | 说明 |
| --- | --- | --- | --- |
| 人格 / 人设卡 / 版本历史 | ✅ | 目标一致 | 人设卡 JSON 原样互通 |
| 聊天（流式、Markdown、代码块、LaTeX 转写） | ✅ | 目标一致 | SSE 解析逻辑与排版规则同源 |
| 消息里的链接 + 内置浏览器 | ✅ | 目标一致 | 蓝色下划线；`WKWebView` 替代系统 WebView |
| 长期记忆（抽屉 / 分量 / 衰减 / 归档） | ✅ | 目标一致 | 同一套 `MemoryLifecycle` 常量 |
| 检索（中文倒排 + 本地向量余弦） | ✅ | 目标一致 | 同一套 `ChineseTerms` / `LocalEmbedding` 算法与 `provider` 标记 |
| 回忆工具 recall 三档 + 回忆重构 | ✅ | 目标一致 | 同样的硬拒清单与相似度门槛 |
| 每日记忆整理 | WorkManager 定时 | `BGProcessingTask` + 打开 App 时补做 | 见「三、平台差异」 |
| 心情（会自己淡、模型可改、驱动行为） | ✅ | 目标一致 | 同一套词表与衰减常量 |
| 「他此刻」（Presence） | ✅ | 目标一致 | 只读推导，30 秒脉冲 |
| 心跳（U 形曲线、自己决定做不做） | 前台服务每 30 秒 | 前台 30 秒 + 后台 `BGAppRefreshTask`（机会式） | iOS 不允许常驻后台 |
| 主动唤醒规则 / 念头 / 后台思考 | ✅ | 目标一致，触发时机受平台限制 | |
| 历史压缩（滚动摘要） | ✅ | 目标一致 | 摘要随备份一起迁移 |
| 用量统计（只 token，分类目 + 按人） | ✅ | 目标一致 | 没有额度、没有单价输入、没有任何拦截；`costMicroUsd` 只留列不显示 |
| 已读回执（`messages.readAt`） | ✅ | 目标一致 | 只在本轮没有助手回复时给最后一条挂「已读」；免打扰时连「已读」都不留 |
| `defer_reply`（这一轮选择不回） | ✅ | 目标一致 | 无助手消息 + 写一条 `REACH_OUT` 意向（5–720 分钟，窗口 +30 分钟） |
| 睡眠 / 免打扰 / 唤醒阈值 | ✅ | 目标一致 | `dndOnSleep` 永不清醒；否则 30 分钟内消息数 ≥ `wakeThreshold` 才唤醒并把 mood 拉回 |
| 人格细分字段（性格/喜欢/习惯/过去/设定 + 主动程度/记性/抽记忆频率） | ✅ | 目标一致 | 同一套 `## 你是谁` 拼装规则，空字段不占位 |
| 一人一段会话（唯一索引） | ✅ | 目标一致 | 迁移时每人只留最新那段，其余会话与消息删除 |
| 全局 LLM 并发闸（≤3） | ✅ | 目标一致 | 后台最多占 2 位、聊天保底 1 位；后台等不到就放弃并写日志 |
| 备份导出 / 导入（合并 / 替换 / 预览） | ✅ | 目标一致 | **同一份 JSON 格式** |
| 预设库 / 人格进化 | ✅ | 目标一致 | |
| 图片消息 | ✅ | ✅（0.9.1 起） | `attachmentsJson` 字段逐字一致（`uri/mime/width/height`），二进制不进备份；iOS 压缩长边 1600 / JPEG 0.85 / ≤800KB |
| 聊天侧工具执行 | ✅ | ✅（0.9.1 起） | `think` / `web_search` / `web_fetch` / `remember` / `update_persona` / `set_quiet_hours`（含 `dnd`）/ `set_mood` / `recall` / `defer_reply`，参数夹取与 `【工具结果】` 回灌形状对齐 |
| 主动唤醒规则界面 | ✅ | ✅（0.9.1 起） | 列表 / 开关 / 下次触发 / 删除 / 手动新建 |
| 头像（人格 + 自己） | ✅ | ✅（0.9.1 起） | 人格头像存沙盒并落库；「我的头像」存 UserDefaults + 沙盒，显示在用户气泡旁；图片不进备份 |

## 三、平台差异（必须写清，不假装一样）

| 项 | Android | iOS 16+ | 处理方式 |
| --- | --- | --- | --- |
| 后台常驻思考 | 前台服务，每 30 秒一拍，系统允许常驻 | 不允许常驻；`BGAppRefreshTask` 由系统择机调度（通常几十分钟到数小时一次） | 前台时每 30 秒一拍；进后台后登记一次刷新任务，能跑就跑；App 重新打开时按「错过的时间」补算一次冲动。**不承诺后台实时性**，这一点在设置页用一句人话说明 |
| 每日整理 | 4:00 定时 | `BGProcessingTask`（可要求充电/联网） | 同上，另外「现在整理一次」必须随时能用 |
| 通知 | 渠道 + 分组 + 摘要 | `UNNotificationCenter` 单渠道 + 线程分组 | 锁屏不露正文，交互式通知可回复（Android 端没有，属于 iOS 增益） |
| 文件导出 | SAF | `UIDocumentPickerViewController` / `fileExporter` | 都用系统文件选择器，默认名 `myfriend-backup-yyyyMMdd-HHmm.json` |

## 四、备份互通（硬要求）

格式见 [`app/src/main/java/com/myfriend/app/domain/BackupCodec.kt`](../app/src/main/java/com/myfriend/app/domain/BackupCodec.kt)：
顶层 `format = "myfriend.backup"`、`formatVersion = 2`（1.9 起；v1 仍读得进）、`apiKeysOmitted = true`，下面
`personas / memories / sessions / messages / presets / proactiveRules / agentIntents /
memoryConsolidations / apiConfigs / settings`。iOS 端的 `BackupCodec.swift` 必须：

1. **字段名逐字相同**（Codable 的 `CodingKeys` 手写，不依赖属性名巧合）；
2. **缺字段用默认值**（老备份没有 `sessions[].summary` 三个字段时按「没压过」处理）；
3. **未知字段忽略**（新版本备份给旧 App 时要能读）；
4. **`formatVersion` 比自己的高就明确拒绝**，中文原因；
5. **任何 `*Cipher` / `apiKey` / `Authorization` / `customHeadersJson` 都不出现在结构里**，
   导出前再自检一次（与 Android 的 `containsSecretField` 同样的标记表）；
6. 导出时**省略 null 字段、但默认值照写**——这是 Android 的实际行为：`BackupCodec.kt` 的 `writeJson` 是
   `encodeDefaults = true` + `explicitNulls = false`。iOS 必须跟着走，否则「缺字段」在老版本那里会被当成默认值，
   写出来的文件语义就和 Android 不同了。

### 双向验证

| 方向 | 怎么做 | 判据 |
| --- | --- | --- |
| Android → iOS | Android 生成 `fixtures/backup/android-export-v2.json`（[`BackupFixtureTest`](../app/src/test/java/com/myfriend/app/domain/BackupFixtureTest.kt)），iOS 的 `BackupInteropTests` 读它 | 人格 / 记忆 / 会话 / 消息 / 规则 / 念头 / 整理记录 / 配置 / 设置逐项数量与关键字段一致；1.9 的细分字段、`readAt`、两个 override 都在；无密钥字段 |
| iOS → Android | iOS 生成 `fixtures/backup/ios-export-v2.json`（`BackupInteropTests` 里写盘），Android 的 `BackupFixtureTest` 读它 | 解码成功、`format`/`formatVersion=2` 正确、各段非空、无密钥字段 |
| 往返 | iOS 解码 Android 样本、重新编码、再解码 | 两次结果相等（说明 encode/decode 是同一套语义） |
| v1 兼容 | 两端的 v1 样本（`*-export-v1.json`）现在只作**输入** | 能读、旧开关与额度字段被忽略、人格缺的字段走默认、多段会话只留最新 |
| 合并时的同人冲突 | 备份里那段与本机那段属于同一个人 | 两端一致：**并进本机那段**（会话用本机 id、消息改挂过去），不静默丢弃任何一句 |

两端都跑同一批 fixture，谁改了格式而没改另一端，测试立刻红。

### 模拟器上唯一的环境坑

未签名模拟器构建没有 Keychain entitlement（`SecItemAdd` 返回 `-34018`），所以 App 里包了一层
`ResilientKeychain`：真机签名构建走 Keychain；模拟器上失败时把 Key 临时放进 UserDefaults，
并在 `Documents/debug.log` 里写明「这是调试兜底」。真机不受影响。

## 五、验证命令

```bash
# 纯逻辑（macOS 上，秒级）
cd ios/MyFriendKit && swift test --disable-sandbox

# 应用（模拟器；DerivedData 与各种缓存都放在仓库内，避开沙箱）
cd ios && CLANG_MODULE_CACHE_PATH=$PWD/.build/clang-cache \
  SWIFT_MODULE_CACHE_PATH=$PWD/.build/swift-cache \
  xcodebuild -project MyFriend.xcodeproj -scheme MyFriend \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath .build/DerivedData build

# 装到模拟器并截图
xcrun simctl boot "iPhone 17 Pro" || true
xcrun simctl install booted .build/DerivedData/Build/Products/Debug-iphonesimulator/MyFriend.app
xcrun simctl launch booted com.myfriend.app.ios
xcrun simctl io booted screenshot /tmp/ios.png
```

## 六、SQLite schema（与 Android v10 对齐）

iOS 用自己的 `SQLiteStore` 建表，**表名与列名与 Room 导出的 `10.json` 一致**，这样备份里的
id / 外键关系可以原样写回。第一版只需要这些表：

`personas`、`chat_sessions`、`messages`、`memories`、`memory_terms`、`memory_vectors`、
`memory_versions`、`memory_consolidations`、`persona_presets`、`proactive_rules`、
`agent_intents`、`api_configs`、`api_usage`。

设置不放数据库，走 `UserDefaults`（Android 端是 DataStore），键名与 `AppSettings` 字段同名。
API Key 放 Keychain（Android 端是 Keystore 加密后的 DataStore 字符串）。

## 六点五、共享决策层（0.12.0 起）

`:agentcore` 已是 Kotlin Multiplatform 模块（`commonMain` + `jvmMain` + `iosMain`），产出 iOS 静态 framework
`AgentCoreKit`。iOS App 的四个 agent 决策（睡眠、`defer_reply`、主动上限、工具参数夹取）**执行的是同一份 Kotlin 代码**
（`KotlinAgentCoreClient` → `InProcessAgentCore.shared`，只有类型转换，没有判定逻辑）。

**iOS App 的运行时只有 Kotlin，没有第二份可执行的 Swift 决策实现**（0.12.0）：

- `NativeAgentCoreClient`（Swift 实现，委托 `SleepGate` / `DeferReply` / `ProactiveLevel` /
  `ToolArgClamp`）整个类型包在 `#if !os(iOS)` 里，**只在 macOS 的 `swift test` 构建**中编译；
- 四个调用点（`ChatService` / `HeartbeatController` / `AgentLoop` / `ToolExecutor`）统一走
  `AgentCoreNativeFallback`：iOS 上它返回 `nil`，`decide(endpoint:nativeFallback:)` 失败只记
  `AgentCoreDiagnostics` 诊断并**上抛**，绝不回落到 Swift；macOS 上它返回 Swift 闭包，
  `swift test` 因此仍然覆盖两条实现；
- 契约对照变成「两边各自对同一批 `spec/agent-core/cases/*.json`」：macOS 上 Swift 实现跑这批用例，
  iOS 测试 target 里 Kotlin 实现对同一批用例（Swift 不再参与 iOS 比对）；
- `AppWiringGuardTests.testSwiftDecisionImplementationIsExcludedFromIOSBuilds` 是源码扫描守卫：
  Swift 实现必须在 `#if !os(iOS)` 块内、iOS 条件块内不得出现 `NativeAgentCoreClient()`、
  四个服务调用点不得引用它。

构建步骤见 `CONTRIBUTING` 与 `ios/README.md`。

## 七、进度

| 阶段 | 状态 | 证据 |
| --- | --- | --- |
| 工程骨架（Xcode + 本地 SPM，iOS 16 部署目标） | ✅ | 模拟器可装可跑（[ios-skeleton.png](screenshots/ios-skeleton.png)） |
| 域算法层（含 `SleepSchedule`：睡眠窗口、免打扰、唤醒阈值、醒来后的心情） | ✅ | `swift test` |
| 数据层（SQLite schema 与 Room v10 对齐：15 张表；iOS 自身 `user_version=10`；一人一段唯一索引） | ✅ | 迁移用例含「v8 库 → 一人一段 + 删多余消息 + 摘掉规则里的 sessionId + Harness 列消失」 |
| 备份编解码 v2 + **双向互通** + v1 兼容 | ✅ | `android-export-v2.json` ↔ `ios-export-v2.json` 互读通过；v1 样本作输入仍可读 |
| 网络（SSE 流式、三种 usage、`LlmConcurrencyGate`）+ 工具协议 + 提示词 + 聊天主链路 | ✅ | 含「后台连开 5 个最多 2 个在跑 / 聊天保底第 3 位」的门控用例 |
| 服务层（记忆抽取/整理/召回/向量、心跳编排、自主循环、Presence、聊天节奏 `ChatRhythmService`、备份导入、清空数据） | ✅ | 含 `defer_reply` 与睡眠-唤醒的端到端用例 |
| SwiftUI 界面与应用装配（0.9.0） | ✅ | 联系人首页 / 聊天「已读」/ 人格编辑分节 / 瘦身设置 / 用量只统计 token；模拟器上跑通真实流式对话与「已读不回」（[ios-home-19.png](screenshots/ios-home-19.png)、[ios-read-receipt-19.png](screenshots/ios-read-receipt-19.png)、[ios-defer-reply-19.png](screenshots/ios-defer-reply-19.png)、[ios-persona-editor-19.png](screenshots/ios-persona-editor-19.png)、[ios-settings-19.png](screenshots/ios-settings-19.png)） |

| 0.9.1：图片消息、头像、内存上限、工具执行器、主动唤醒页面、人设卡、压缩编排、通知与后台调度 | ✅ | iOS **810 条**单测全绿、`xcodebuild` BUILD SUCCEEDED；模拟器实测发图 / 工具调用 / 规则落库与开关 / 重启后回声仍在 |

iOS 侧合计 **1305 条单测**（`swift test --disable-sandbox`），Android 侧 **1072+ 条**（`:app:testDebugUnitTest`），
两端都绿；两端 App 都能编译（Android `assembleDebug`、iOS `xcodebuild … build`）。

### 1.9 里两端必须一致的三个取舍（规格没写死，已对齐）

| 取舍 | 两端现在的做法 |
| --- | --- |
| `proactiveLevel` → 每天最多主动几次 | `cap = level`（1→1 … 5→5，默认 3，与 1.8 的默认一致），心情再在 `MoodBehavior` 里挪一格 |
| `defer_reply` 的决策窗口 `latestAt` | `earliestAt + 30 分钟`（沿用「人不会准点」的窗口语义） |
| 合并导入时「备份那段」与「本机那段」同属一人 | 并进本机那段：会话用本机 id、消息改挂过去，并给一句中文提醒；不丢弃任何一句 |

### 还没接上的（显式缺口，不是静默空实现）

> 0.9.1 补掉了：图片附件、聊天侧工具执行器、主动唤醒规则落库 + 界面、历史压缩编排、人设卡编辑器、通知与 BGTask 注册。
> 0.10.1 补掉了：聊天分页（两端）、iOS 的 `web_search` 后打分、记忆回声清理、「现在试一次」+ 失败原因、编译警告清零。
> 0.10.2 补掉了：聊天历史检索（iOS 从零新建）与「点结果跳回该消息并高亮」；并按三份审计修掉 1 个 P0 / 17 个 P1。
XX（Android 用共享 `:agentcore`，iOS 用契约对齐的 Swift 实现 + 可切远程 `agentd`），以及三份审计剩下的 P2 项。
> 0.12.0 补掉了：iOS App 的四个决策改走同一份 Kotlin（`AgentCoreKit`），Swift 实现收窄为**只在 macOS 测试构建存在**；`agentd` 加了按来源限流（429 + `Retry-After`）与 TLS 部署说明。
> 下面这张表是**现在**还差的。

| 缺口 | 现状 |
| --- | --- |
| ~~工具痕迹标题的本地化~~ | 0.10.2 已完成（模型面向文本仍中文，显示标题走资源） |
| ~~历史消息定位~~ | 0.10.2 已完成（检索结果可跳回并高亮）；通知深链仍只打开会话、停在最新窗口 |
| `persona_revisions` / `persona_pending_patches` | iOS 不做人格版本历史、不做 pending patch（既定取舍，与备份互通无关） |
| 回忆后的心情漂移 | 留了 `afterRecallDrift` 回调，不接就是「只回忆不改写」 |
| 真机后台调度 | `BGAppRefreshTask` / `BGProcessingTask` 只有真机能验证；模拟器上走「回到前台补算一次」 |
| 相册选择器的人工点验 | 模拟器无法注入触控，`PhotosPicker` 只验证了「拿到 Data 之后」的整条链路 |
