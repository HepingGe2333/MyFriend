# 更新记录

> English version: [CHANGELOG.en.md](CHANGELOG.en.md)

版本号规则见 [VERSIONING.md](VERSIONING.md)：内测阶段主版本固定为 `0`，小改动末尾 +1，大改动中间 +1。

> 编号说明：`0.9.0` 之前的版本用的是旧编号（`1.0.0`–`1.8.0`）。开源发布时把当时的最新版 `1.9.0`
> 改成了 `0.9.0`，更早的版本保留旧编号只在下面这张表里留档。

| 版本 | 主要变化 |
| --- | --- |
| `1.0.0` | 人格、会话、流式回复、Markdown / LaTeX、长期记忆雏形 |
| `1.1.0` | 人格版本快照、预设库、主动唤醒提案制、图片消息 |
| `1.2.0` | 记忆生命周期与每日整理（还在用 / 沉下去了 / 已归档） |
| `1.3.0` | `recall` 三档检索、回忆重构（回忆会改变记忆） |
| `1.4.0` | 人设卡与记忆筛查、自主决策协议与时间窗口、回复系统通知 |
| `1.5.0` | 情绪漂移、在场感（他此刻在做什么） |
| `1.6.0` | 备份与恢复（合并 / 替换 / 预览）、用量记账 |
| `1.7.0` | 消息里的链接与内置浏览器、心跳 U 形曲线、本地向量检索、长会话滚动摘要 |
| `1.8.0` | 移除原生 Harness 链路、iOS 端跑通、备份双向互通 |
| `0.9.0` | 把设置项从产品里拿掉：一人一段会话、已读回执、`defer_reply`、睡眠与唤醒阈值、人格细分字段、并发闸 ≤3、只统计 token |
| `0.9.1` | 两端可选头像（自己 + 他）、iOS 补齐落后 Android 的能力、内存占用优化 |
| `0.10.0` | 英文 UI 与双语资源守卫、跨端 agent 契约用例与 agentd 服务骨架、文档中英双语化 |

---

## 0.10.0

版本号 `0.10.0`（大改动：新增国际化能力与跨端 agent 契约），构建号 `versionCode` / `CURRENT_PROJECT_VERSION` 同步为 **12**，数据库 schema 仍是 **v10**，备份格式仍是 **v2**。

### 国际化：英文 UI

- Android：新增 `app/src/main/res/values-en/strings.xml`（与 `values/strings.xml` 键集合一致），
  通过 `android:localeConfig` 声明 `zh` / `en`，语言跟随系统，没有应用内切换；
  基资源 `values/` 为中文，未匹配语言时回落中文。
- iOS：新增 `ios/MyFriend/Resources/zh-Hans.lproj/Localizable.strings` 与
  `en.lproj/Localizable.strings`，`CFBundleDevelopmentRegion = zh-Hans`、
  `CFBundleLocalizations = [zh-Hans, en]`，两份 `InfoPlist.strings` 本地化本地网络权限说明，
  由 `Bundle.main` 自动选择；同样没有应用内切换。
- 模型语言：两端在 system 提示词末尾追加语言指令小节，要求模型使用与用户相同的语言回答。
  两端都按界面语言提供中 / 英两套模板（Android `values{,-en}` 的 `prompt_language_directive`，
  iOS `Localizable.strings` 的同名 key），模板文案逐字一致，只有占位符写法不同（`%1$s` / `%1$@`）。
  除该小节外，提示词脚手架、日志与代码注释不翻译。
- 资源一致性守卫：Android `StringResourceParityTest`（键集合与占位符）、`ChineseLiteralGuardTest`（UI 层不得硬编码中文）、
  `LanguageDirectiveTest`（提示词末尾追加语言指令）；iOS `LocalizationResourceTests`、`UIChineseLiteralGuardTests`。
- 文案条数：Android `values` / `values-en` 各 **876 条**（856 `<string>` + 20 `<plurals>`），另有 `seed.xml` 各 12 条、
  按语言取值的 `usage_tokens_compact_divisor` 与日期/时间格式资源；iOS 两份 `Localizable.strings` 各 **765 条**
  （App 层约 637 处调用点 + Kit 层 233 个 key），`InfoPlist.strings` 各 1 条。
- 种子人设随语言：中文系统「林深 / 阿岚 / 墨」，英文系统 Android「Elias / Finn / Ansel」、iOS「Rowan / Juno / Ellis」；
  仅在首次安装时生效，不迁移、不改名已有人格。

### Agent 核心契约

- 新增 `spec/agent-core/cases/*.json`：**94 条**与语言无关的用例（睡眠判定 16、`defer_reply` 9、主动上限 14、
  心跳 17、记忆衰减 22、工具参数夹取 16），每条含 `name` / `input` / `expect` / 可选 `why`；浮点断言带 `tolerance`。
- Android 测试 `app/src/test/java/com/myfriend/app/spec/AgentCoreSpecTest.kt` 与
  iOS 测试 `ios/MyFriendKit/Tests/MyFriendKitTests/Spec/AgentCoreSpecTests.swift` 读取**同一批** JSON，
  分别喂给两端已有的领域实现；任何一端单独改行为，另一端立刻红。

### 内嵌服务骨架：agentd

- 新增独立 Gradle JVM 模块 `agentd/`（Kotlin/JVM，仅用 JDK 自带 `com.sun.net.httpserver`，零第三方依赖），
  直接编译 Android 的纯领域源码（同一批源文件，不复制）。
- 对外提供 `GET /health` 与四个核心接口（`sleep-decision` / `defer-plan` / `proactive-cap` / `tool-args`），
  可独立运行，也可作为本机端口的嵌入式服务；不接触数据库与 LLM 调用，无鉴权、无状态持久化。
- 实测：`:agentd:test` **19 条**全绿（18 条 handler + 1 条真端口集成）；`agentd/build/install/agentd/bin/agentd --port 8788`
  与 `java -jar agentd-all.jar` 都能起，`/health` 与四个接口按约定返回（错误统一 `{"error":{"message","code"}}` + 4xx/5xx）。
- **本轮未接线**：Android 客户端没有启动 agentd，iOS 侧也没有调用它的代码；两端仍走原有进程内实现。

### 契约测试暴露并修掉的两端不一致

契约用例写完之后，两端有 10 处实现细节并不一致（数值、边界、脏数据降级各有各的做法）。处理原则：以
`docs/ROADMAP-1.9.md` 的规格为准，两边都合理时以 Android 为准并对齐 Swift。主要几条：

| 项目 | 修复前 | 修复后 |
| --- | --- | --- |
| 免打扰时是否写已读 | Android 写、iOS 不写 | 统一**不写**（规格 §六「不显示已读后的任何反应」），`SleepPolicy.Decision.marksRead` 决定 |
| 被叫醒后的心情强度 | iOS 强制 1、Android 减 1 | 统一「强度 −1」 |
| 睡醒同一分钟的脏数据 | Android 回退默认 23:30–08:00、iOS 视为永不睡 | 统一回退默认 |
| `memoryRetention` 是否影响衰减 | Android 完全没接、iOS 有系数 | 统一接上（×1.6 / 1.3 / 1.0 / 0.75 / 0.5） |
| `proactiveLevel` 是否影响心跳 | Android 乘 0.7–1.3、iOS 不乘 | 统一乘上；iOS 新增 `ProactiveLevel` |
| `defer_reply` 夹取 | iOS 在三处各写一份 | 两端都收进纯函数 `DeferReply` / `ToolArgClamp`，契约测试直接覆盖 |
| 脏心情数据的归一化 | Android 会写出越界 label / 强度 | 两端都归一化并夹 1–5 |

### 测试

| 目标 | 命令 | 结果 |
| --- | --- | --- |
| Android | `./gradlew :app:testDebugUnitTest` | **639 条 / 47 个测试类 / 0 失败** |
| iOS | `cd ios/MyFriendKit && swift test --disable-sandbox` | **827 条 / 0 失败** |
| agentd | `./gradlew :agentd:test` | **19 条 / 0 失败** |
| 构建 | `:app:assembleDebug`、`xcodebuild … build`、`:agentd:build` | 均成功 |
### 文档

- `README` 与 `CONTRIBUTING` 改为中英双语（`README.md` / `README.en.md`、`CONTRIBUTING.md` / `CONTRIBUTING.en.md`），
  内容改为面向贡献者的技术说明：项目状态、架构与技术栈、目录结构、构建与测试、已知限制。
- `docs/VERSIONING`、`docs/CHANGELOG` 增加英文版；新增 `docs/I18N.md` / `docs/I18N.en.md`
  与 `docs/ARCHITECTURE.md` / `docs/ARCHITECTURE.en.md`。
- 文档不再嵌入截图或链接截图文件；仓库不发布 APK 与截图。

### 版本与工程

| 项 | 值 |
| --- | --- |
| 版本 | `0.10.0` |
| 构建号 | Android `versionCode` 与 iOS `CURRENT_PROJECT_VERSION` 相等（待补：具体数值） |
| 数据库 schema | v10（本轮未变） |
| 备份格式 | v2（本轮未变） |
| Android 单测 | 619+（本轮新增数待补） |
| iOS 单测 | 810+（本轮新增数待补） |

---

## 0.9.1

内测阶段的第一次小版本更新。三件事：**你和他都能有自己的脸**、**iOS 追上 Android**、**少占一点内存**。

### 头像：他可以有，你也可以有

- **他（人格）**：编辑人格时可以从相册选一张，采样压缩后存在应用私有目录（长边 512px、JPEG 85、≤400KB），
  全部界面（首页联系人、聊天页、人格列表、设置）统一渲染；随时可以「恢复默认」退回色块 + 首字。
  文件丢了（换机、清理）不会崩，只是退回默认。
- **你（自己）**：设置里新增安静的「我的头像」一行，选一张之后会显示在**你的消息气泡旁**。
  不存在数据库里（Android 走 DataStore、iOS 走 UserDefaults），所以**没有 schema 迁移**。
- 两端一致：头像图片本身**不进备份**（备份里只留路径），换手机后重新选一张即可；导出/导入页有这句中文提醒。

### iOS 补齐落后 Android 的能力

| 能力 | 0.9.1 之前 | 现在 |
| --- | --- | --- |
| 图片消息 | 只写「（发来一张图片）」 | 相册选图 → 长边 1600px / JPEG 0.85 / ≤800KB → 气泡缩略图 → 全屏查看（缩放、翻页）→ 重启后仍渲染；文件缺失显示「图片已不在本机」 |
| 聊天侧工具执行 | 一律回「这个工具用不了」 | `think` / `web_search` / `web_fetch` / `remember` / `update_persona` / `set_quiet_hours`（含 `dnd`）/ `set_mood` / `recall` / `defer_reply`，参数夹取与回灌文案对齐 Android；失败有中文原因，不假装成功 |
| 联网工具 | 无 | `Net/WebToolsClient`（DeepSeek `/v1/tools/search`、`/v1/tools/fetch`），搜索真的联网 |
| 主动唤醒规则 | 提案只能显示，不能落库 | 落库（daily / interval、`nextTriggerAt` 按本地时区、重复只更新不新增），并且新增**「主动唤醒」页面**：列表、开关、下次大概什么时候、长按删除、手动新建 |
| 历史压缩 | 只有算法，没有编排 | 最近 24 条逐字保留、够 12 条或 24000 字压一段、一次最多 40 条、原文一条不删；带「工具协议形状 / 推脱 / 占位」拒收守卫与启动自检修复 |
| 人设卡 | 只读 | 结构化编辑（identity / traits / bond…），落库并进提示词，空卡回落到手写 `prompt` |
| 通知与后台 | 无 | 本地通知（锁屏不露正文、点开进对应聊天）+ `BGAppRefreshTask` / `BGProcessingTask` 注册 + 回到前台按错过的时间补算一次 |
| 两张遗留表 | `memory_echoes` / `agent_ticks` 未进 schema 清单、无测试 | 进入 `Schema.allTables`（15 张），补「老库缺表打开即建」的迁移测试；`remember` 的回声跨进程重启仍在 |

### 内存占用

- **Android**：Coil 改成懒初始化单例（内存缓存 `min(可用内存×12%, 48MB)`、磁盘 128MB、`respectCacheHeaders(false)`），
  头像与聊天图一律按显示尺寸解码（不再整图常驻）；六处后台路径（自主循环 / 空闲浏览 / 主动唤醒 / 记忆整理 / 记忆读写 / 人格进化）
  从「读整段历史」改成「最近 40 / 120 条 + 窗口工具」。
- **iOS**：图片按显示尺寸解码（ImageIO 缩略图）+ `NSCache`（`min(物理内存×10%, 48MB)`）+ 内存警告清空；
  只给**最近 2 条**带图消息解 data URL；附件目录标记为不进 iCloud 整机备份。
- **实测数字（不粉饰）**：
  - iOS：单张聊天图在气泡里只多约 **5.1MB**，全屏查看是唯一大头 **+21.3MB**（退出即释放，缓存受 48MB 上限约束）。
  - Android：两个规定场景（首页 30s / 聊天页 30s）PSS 变化 **<1%，属噪声量级**——不是没改，是这两个场景里 58MB 的匿名 dex 与图形缓冲占主导，
    应用自身 Java 堆只有约 11MB。真正的收益在「长历史 / 大图」路径上（后台从读几千条变 40 条、图片按显示尺寸解码），
    这两个场景没有触发它们。**已知遗留**：聊天页仍然一次性加载整段消息列表，这是长会话下最大的一块，留给下一版做分页。

### 版本与工程

- 版本号 `0.9.1`，Android `versionCode 11`、iOS `CURRENT_PROJECT_VERSION 11`（两端构建号相等，schema 仍是 v10）。
- 关于页、备份 `appVersion` 都从构建号派生，不再手写版本字符串。
- 开源准备：Apache-2.0、Gradle Wrapper、`.gitignore`（排除工具链与构建产物）、`CONTRIBUTING.md`、`docs/VERSIONING.md`。

---

## 0.9.0（旧编号 1.9.0）

**这一版只做一件事：把「设置项」从产品里拿掉，把他还成一个人。**

以前你可以调他的记性、调他回消息的快慢、给他设一个月度额度、规定他一天最多主动几次。这些旋钮让界面看起来像一台可以调的机器人，而不是一个你认识的人。这一版把这些全部删掉。

- **首页变成联系人列表**：一行一个人——头像、名字、最后一句、时间、未读。没有「最近对话」，也没有「新建对话」。
- **一人一段会话**：升级时旧的多段会话按「保留最新那一段」合并，其余连同消息一起删除。
- **他有了更具体的样子**：性格、喜欢的事、行为方式、成长经历、个人设定，加上主动程度、记性、抽记忆频率、会不会主动睡觉、睡觉时间段与被叫醒的阈值。
- **回不回由他决定**：他可以读到你的消息、显示「已读」，然后这一轮选择不回（`defer_reply`）。界面不会显示「对方正在输入」来骗你。
- **他也要睡觉**：睡着时消息留着；睡前要不要开免打扰由他自己决定；没开免打扰时，你连着叫他够多条会把他叫醒，醒来心情会缓一缓。
- **设置页瘦身**：只剩外观、聊天背景、后台常驻与通知、API 配置、用量、备份与恢复、数据清除、关于。
- **同时最多三件事，聊天永远有位**：全局并发闸 3（后台最多占 2 位）。
- **备份升到 v2**：1.8 及更早的备份仍然读得进来。

---

## 更早的版本

旧编号时代的详细设计文档仍在 `docs/` 下：[ROADMAP-1.2](ROADMAP-1.2.md)、[ROADMAP-1.3](ROADMAP-1.3.md)、[ROADMAP-1.4](ROADMAP-1.4.md)、[ROADMAP-1.5](ROADMAP-1.5.md)、[ROADMAP-1.6](ROADMAP-1.6.md)、[ROADMAP-1.7](ROADMAP-1.7.md)、[ROADMAP-1.9](ROADMAP-1.9.md)（= 0.9.0）。
