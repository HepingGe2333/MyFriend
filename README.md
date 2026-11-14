# MyFriend

[English](README.en.md) · **简体中文**

MyFriend 是一个 Android + iOS 的 AI 陪伴应用：把「人格」建模成长期存在的联系人，而不是一组可调的聊天参数。两端共享同一套数据模型、同一套行为约定和同一份备份文件。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20%7C%20iOS%2016%2B-lightgrey.svg)](#架构与技术栈)
[![Tests](https://img.shields.io/badge/tests-970%20Android%20%7C%201187%20iOS%20%7C%2047%20agentcore%20%7C%2034%20agentd-brightgreen.svg)](#构建与测试)

## 项目状态

| 项 | 值 |
| --- | --- |
| 版本 | `0.11.1`（内测，`0.x` 体系，规则见 [docs/VERSIONING.md](docs/VERSIONING.md)） |
| 数据库 schema | v10（Android Room `@Database(version = 10)` 与 iOS SQLite `user_version = 10` 对齐） |
| 备份格式 | v2（`format = "myfriend.backup"`、`formatVersion = 2`；v1 样本只读） |
| 构建号 | Android `versionCode` 与 iOS `CURRENT_PROJECT_VERSION` 保持相等 |
| 单元测试 | Android 970 条（`:app:testDebugUnitTest`）、`:agentcore` 47 条、`:agentd` 34 条、iOS 1187 条（`swift test`） |
| 持续集成 | 暂无；两端测试与构建均由本地命令执行 |

## 特性概览

| 领域 | 已实现的能力 |
| --- | --- |
| 首页 | 联系人列表：头像、名字、最后一条消息、时间、未读数；一人一段会话 |
| 检索 | 聊天记录全文检索：摘要高亮、按人格与时间过滤、分页、点结果跳回该消息并高亮（两端语义一致，契约在 `spec/search/`） |
| 聊天 | SSE 流式、停止生成、Markdown / 代码块 / LaTeX、图片消息（每条最多 4 张）、长按菜单（复制 / 编辑重发 / 重新生成 / 删除）、链接识别与内置浏览器 |
| 已读与沉默 | `messages.readAt` 已读回执；`defer_reply` 允许模型本轮不回，界面只显示「已读」 |
| 睡眠 | 人格自主睡觉、睡前免打扰（由模型决定）、30 分钟窗口内的唤醒阈值 |
| 人格 | 结构化字段（性格 / 喜欢的事 / 行为方式 / 成长经历 / 个人设定）+ 行为参数（主动程度、记性、抽取频率、睡眠）、提示词全文编辑、版本快照、人格演化提案、人设卡 |
| 头像 | 人格头像与用户头像；采样压缩后存本地，统一渲染，不进备份 |
| 长期记忆 | 自动抽取、生命周期（ACTIVE / DORMANT / ARCHIVED）、分量衰减、每日整理、`recall` 三档检索、回忆重构 |
| 检索 | 中文二元组倒排索引 + 本地 384 维哈希嵌入余弦重排（`local-hash-v1`，离线、零依赖） |
| 长会话 | 最近 24 条逐字保留，更早的内容压成滚动摘要；原文不删 |
| 主动行为 | 30 秒心跳（先降后升的冲动曲线，三小时封顶）、主动唤醒规则；是否开口由模型决定 |
| 在线状态 | Presence：平静 / 发呆 / 在写东西等，以及「睡着了 · 08:00 醒」 |
| 工具 | `think` / `remember` / `recall` / `set_mood` / `set_quiet_hours` / `web_search` / `web_fetch` / `update_persona` / `defer_reply`；两端均有真实执行器 |
| 模型接入 | 任意 OpenAI 兼容服务：多套配置、Base URL / Key / 模型 / 自定义 Headers、连通性测试、推理参数；Gemini 与 Claude 原生适配已预留 |
| 用量 | 只统计 token（本月总量、按用途、按人排行）；没有金额、额度或拦截 |
| 并发 | 全局 LLM 并发闸 3，后台最多占 2 位，聊天保底 1 位 |
| 备份 | 导出 / 导入（合并 / 替换 / 预览），两端同一 JSON 契约；API Key 永不进备份 |
| 外观 | 浅色 / 深色 / 跟随系统、主题色、字号、气泡样式、聊天背景、动画与触感开关 |
| 语言 | 中文（默认）与英文，跟随系统语言；没有应用内语言开关（见 [docs/I18N.md](docs/I18N.md)） |

两端并非逐项完全对等；平台限制与 iOS 仍缺的能力以 [docs/IOS-PARITY.md](docs/IOS-PARITY.md) 为准。

## 设计立场

项目的大部分取舍来自同一条约束：**界面面对的是一个联系人，不是一台可调参的机器人。** 记忆开关、回复节奏、每日主动次数、月度额度这类旋钮被刻意移除，相关决定交给人格字段与模型（`defer_reply`、睡眠与唤醒阈值、`proactiveLevel` 等）。这条立场写进了 [docs/ROADMAP-1.9.md](docs/ROADMAP-1.9.md) 的规格与删除清单，并在 [CONTRIBUTING.md](CONTRIBUTING.md) 里作为评审约束；架构层面的取舍见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 架构与技术栈

| 层 | Android | iOS |
| --- | --- | --- |
| 语言与 UI | Kotlin 2.0.21、Jetpack Compose（BOM 2024.12.01）、Material 3 | Swift 5.9、SwiftUI（iOS 16.0+） |
| 依赖注入 / 装配 | Hilt 2.52 | 手写依赖装配（`ios/MyFriend/App`） |
| 持久化 | Room 2.6.1（schema v10，导出 `app/schemas/`）、DataStore | 系统 `SQLite3`（表结构对齐 v10）、`UserDefaults` |
| 后台任务 | WorkManager 2.10、前台服务（`specialUse`）、开机恢复 | `BGAppRefreshTask` / `BGProcessingTask`（系统择机调度） |
| 网络 | OkHttp 4.12（SSE）、kotlinx.serialization 1.7.3 | `URLSession`（SSE）、`Codable` |
| 密钥 | Keystore 加密后写入 DataStore | Keychain（模拟器无 entitlement 时回退到调试兜底） |
| 最低版本 | Android 8.0（`minSdk 26`）、`targetSdk 35`、JDK 17 | iOS 16.0、Xcode 16+ |
| 测试 | JUnit4 + kotlinx-coroutines-test；迁移测试在 `androidTest` | XCTest（`ios/MyFriendKit` 本地 SPM 包，macOS 上直接跑） |
| Agent 决策 | 共享 `:agentcore`（Kotlin/JVM，唯一实现） | 契约对齐的 Swift 实现；可切换远程 `agentd` |

跨端契约（三处必须一致）：

1. **数据库**：iOS 的 `SQLiteStore` 表名与列名逐字对齐 Room 导出的 `10.json`，两端 schema 都是 v10；
2. **备份**：两端 `BackupCodec` 与 `fixtures/backup/` 的 v1 / v2 样本互读，任何一端改格式而另一端没跟就会红；
3. **Agent 核心**：`spec/agent-core/cases/*.json` 是与语言无关的用例，Android 与 iOS 各有一条测试读同一批文件，喂给各自的领域实现（睡眠判定、`defer_reply`、主动上限、心跳、记忆衰减、工具参数夹取）。

决策层只有一份实现：`agentcore/`（`:agentcore`，零第三方依赖）被 `:app` 与 `agentd/` 同时依赖。`agentd/` 在此之上加一层 HTTP（仅 JDK `com.sun.net.httpserver`），暴露 `GET /health` 与四个核心接口（`sleep-decision` / `defer-plan` / `proactive-cap` / `tool-args`）。两端客户端都通过 `AgentCoreClient` 抽象接入：**默认走进程内/本机实现**（零网络），可按构建开关或环境变量切到远程服务，失败自动回退——没有用户可见设置项。

## 目录结构

```
.
├── app/                    Android 应用（Kotlin · Compose · Hilt · Room · WorkManager）
│   ├── schemas/            Room 导出的历史 schema（迁移测试的依据）
│   └── src/main/java/com/myfriend/app/
│       ├── data/           Room 实体 / DAO / 迁移 / 仓库 / DataStore / Keystore
│       ├── domain/         纯算法：记忆生命周期、心情、心跳、链接识别、向量、备份编解码…
│       ├── network/        OpenAI 兼容 SSE（多模态）+ 并发闸 + 用量
│       ├── service/        前台服务、开机恢复、自主循环控制
│       ├── ui/             components / screens / theme
│       └── util/           时间、LaTeX、媒体压缩与持久化、通知
├── ios/                    iOS 16+ 应用
│   ├── MyFriendKit/        与 UI 无关的 SPM 包（Domain / Data / Net / Backup / Chat / Services），可 swift test
│   ├── MyFriend/           SwiftUI 层（App / Views / Resources 本地化资源）
│   └── MyFriend.xcodeproj  部署目标 16.0，无第三方依赖
├── agentcore/              共享 agent 决策层（纯 Kotlin/JVM，零依赖；:app 与 :agentd 都依赖它）
├── agentd/                 独立 agent 核心服务（Kotlin/JVM，HTTP，基于 :agentcore）
├── spec/agent-core/cases/  跨端 agent 契约用例（JSON，两端测试共读）
├── harness/                零依赖 Node.js 参考服务（OpenAI 兼容 mock，含工具与心跳分支）
├── fixtures/backup/        跨端备份黄金样本（v1 只读样例 + v2 契约）
├── docs/                   架构、对齐、更新记录、版本规则、i18n 说明
└── gradle/                 Gradle Wrapper
```

## 构建与测试

**Android**（JDK 17、Android SDK Platform 35）：

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # 指向本机 SDK，此文件不进仓库
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

迁移测试（`app/src/androidTest`）需要模拟器或真机：

```bash
./gradlew :app:connectedDebugAndroidTest
```

**iOS**（Xcode 16+，部署目标 iOS 16.0，零第三方依赖）：

```bash
cd ios/MyFriendKit && swift test --disable-sandbox      # 纯逻辑，macOS 上秒级
cd ios && xcodebuild -project MyFriend.xcodeproj -scheme MyFriend \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

**本地联调参考服务**（无需真实 API Key 即可走通全链路）：

```bash
node harness/server.mjs        # http://127.0.0.1:8787/v1 ，模型 ID 填 myfriend-reference
```

参考服务认得 `remember` / `recall` / `set_mood` / `web_search` / `myfriend-wake` / `defer_reply` / 记忆抽取 / 记忆整理 / 历史压缩等分支；`[chat]` 日志会打印 `systemChars` 与本轮使用的工具，便于核对人格与记忆是否真的随 `system` 注入。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [EN](docs/ARCHITECTURE.en.md) | 分层、数据流、跨端契约与关键取舍 |
| [docs/I18N.md](docs/I18N.md) · [EN](docs/I18N.en.md) | 双语方案、资源位置、新增文案规则、不翻译的内容 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) · [EN](docs/CHANGELOG.en.md) | 每个版本的实际变更 |
| [docs/VERSIONING.md](docs/VERSIONING.md) · [EN](docs/VERSIONING.en.md) | 版本号规则、构建号与 schema 的关系、发版清单 |
| [docs/IOS-PARITY.md](docs/IOS-PARITY.md) | 两端哪些必须一致、哪些受平台限制、iOS 仍缺什么 |
| [docs/audits/](docs/audits/README.md) | 独立审计报告（每版的 P0/P1/P2 发现、已修复与已接受的取舍） |
| [docs/ROADMAP-1.9.md](docs/ROADMAP-1.9.md) | 0.9.0（旧编号 1.9.0）的规格、删除清单与验收表 |
| [docs/ROADMAP-1.7.md](docs/ROADMAP-1.7.md) | 链接与内置浏览器、心跳曲线、向量检索、长会话压缩 |
| [CONTRIBUTING.md](CONTRIBUTING.md) · [EN](CONTRIBUTING.en.md) | 环境、代码风格、提交规范、数据与文案改动流程 |

## 已知限制

- **没有 CI**：两端测试、构建与迁移测试都靠本地命令；PR 评审以本地运行结果为准。
- **检索的候选上限**：排序要在内存里算词元命中，只在最新 500 条候选里排序；命中更多时界面会提示「只显示最近 N 条」。
- **聊天页分页有代价**：两端首屏只读最近 60 条、上滑再取（未读/已读/DND 仍走全表），
  把整个历史翻完之后可观察区间等价于全量；检索跳转到很旧的消息会把「目标→最新」整段装进窗口（上限 60 页）。
- **iOS 后台不保证实时**：`BGAppRefreshTask` / `BGProcessingTask` 由系统择机调度，后台心跳与每日整理可能延后；回到前台时按错过的时间补算。
- **图片与头像不进备份**：备份只保留路径与元数据，换机后需要重新选择图片。
- **只有 token 统计**：没有金额、额度与拦截，`costMicroUsd` 仅保留列，不展示。
- **远程模式默认关闭**：两端默认走本机决策（零网络）；Android 用 `-PagentCoreRemote=true`、iOS 用 `MYFRIEND_AGENT_CORE=remote` 才会连 `agentd`，失败自动回退本机并记日志。
- **iOS 仍是第二份实现**：`:agentcore` 是 Kotlin/JVM，iOS 不能用它；两端一致靠 `spec/agent-core` 与 `spec/search` 的共享用例 + 字段名 golden 测试保证（KMP 是后续可选项）。
- **仓库不含产物**：`.gitignore` 排除了 `dist/`、`docs/screenshots/`、APK / IPA 与构建缓存，因此文档中不放截图，也不提供预编译包。
- **英文仍有几处中文**：工具痕迹标题、数据层错误与 Kit 层可见文案已本地化（含写入时生成的会话标题与预览），
  但**升级前已落库的中文值**、**模型写出的文本**（心情理由、记忆正文、人格名/简介、自造心情词）与
  代码注释/日志/提示词脚手架仍是中文（见 [docs/I18N.md](docs/I18N.md)）。
- **发布包不发明文 HTTP**：Android Release 关闭 `usesCleartextTraffic`，iOS Release 只放行局域网明文；
  在发布包里把「允许不安全 HTTP」打开去连公网会得到明确错误（调试包不受影响）。
- **模拟器 Keychain 限制**：未签名模拟器构建没有 Keychain entitlement；明文兜底**只在 `DEBUG && 模拟器`** 生效并写 `debug.log`，真机（含 Release 模拟器）写失败会直接报错，不会降级。

## 贡献与许可

提交改动前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)（[English](CONTRIBUTING.en.md)）。最低要求：两端单测通过；涉及数据结构时 schema、迁移、迁移测试与 fixtures 同步改；不提交密钥、`local.properties`、`dist/` 或截图。

License：[Apache-2.0](LICENSE) © 2026 Heping Ge。提交贡献即表示同意以同一协议授权。
