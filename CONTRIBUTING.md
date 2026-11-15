# 参与 MyFriend

本文档面向准备提交代码、文档或翻译的贡献者。MyFriend 是双端项目（Android + iOS），两端共享数据契约与行为规格，因此大部分规则都围绕「两端一起改」展开。

> English version: [CONTRIBUTING.en.md](CONTRIBUTING.en.md)

## 前置阅读

| 文件 | 为什么读 |
| --- | --- |
| [README.md](README.md) | 项目定位、构建命令、已知限制 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层、数据流、跨端契约 |
| [docs/README.md](docs/README.md) | 文档总索引（含跨端契约与子项目文档） |
| [docs/design/roadmap-1.9.md](docs/design/roadmap-1.9.md) | 0.9.0 的历史行为规格与验收表；「刻意删除清单」的出处 |
| [docs/IOS-PARITY.md](docs/IOS-PARITY.md) | 两端必须一致的部分、平台限制、iOS 已知缺口 |
| [docs/I18N.md](docs/I18N.md) | 文案与本地化规则 |

## 开发环境

- **Android**：JDK 17、Android SDK Platform 35、仓库自带的 Gradle Wrapper（Gradle 8.9 / AGP 8.7.3）。`local.properties` 需要写 `sdk.dir=...`，该文件不进版本库。
- **iOS**：Xcode 16+，部署目标 iOS 16.0。iOS 端**没有第三方依赖**：SQLite 使用系统 `SQLite3`，网络使用 `URLSession`，序列化使用 `Codable`。`ios/MyFriendKit` 是本地 SPM 包，用于在 macOS 上直接跑逻辑测试。
- **参考服务**：Node.js（`harness/server.mjs` 只用标准库，无 `package.json` 依赖）。
- **agentd**：JDK 17（Kotlin/JVM 模块，仅用 JDK 自带 `com.sun.net.httpserver`）。

## 构建与测试

提交前至少运行：

```bash
# Android 单元测试 + 构建
./gradlew :app:testDebugUnitTest :app:assembleDebug

# iOS：先构建共享决策层 framework（Kotlin Multiplatform，App 会链接它）
ios/scripts/build-agentcore-framework.sh

# iOS 逻辑测试（macOS 上直接跑，不需要模拟器，不链接该 framework）
cd ios/MyFriendKit && swift test --disable-sandbox

# iOS 应用构建
cd ios && xcodebuild -project MyFriend.xcodeproj -scheme MyFriend \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

`AgentCoreKit` 是 `agentcore/` 的 Kotlin Multiplatform 产物（iOS App 的决策逻辑就是它）；framework 是构建产物、
不进仓库，Xcode 工程里有一个 Run Script 会在缺失时自动构建。

改到 agent 核心服务时，加跑 `./gradlew :agentd:test`（纯 JVM，不需要模拟器）。

涉及数据库迁移时，还需要在模拟器/真机上跑：

```bash
./gradlew :app:connectedDebugAndroidTest
```

改动备份格式或跨端契约时，额外运行：

```bash
./gradlew :app:testDebugUnitTest --tests "*BackupFixtureTest*"
cd ios/MyFriendKit && swift test --disable-sandbox --filter BackupInteropTests
```

`--disable-sandbox` 供受限环境下本地 SPM 包跑测试使用；环境正常时可以省略。

## 代码风格

- **Android**：Kotlin + Compose，`kotlin.code.style=official`。算法与边界条件放在 `domain/` 的纯函数里并配单元测试，不要埋进 Composable；Composable 只负责状态与布局。
- **iOS**：Swift 5.9。`ios/MyFriendKit` 不允许依赖任何 UI 框架；SwiftUI 层不重复实现领域逻辑，直接复用 Kit 中的类型。
- **注释**：保持中文，写「为什么」而不是「做了什么」。阈值、夹取范围、时间窗口一类数字，应在注释里指向规格条目（例如 `docs/design/roadmap-1.9.md` 的对应小节）。
- **依赖**：Android 不新增未经讨论的第三方库；iOS 保持零第三方依赖；`harness/` 保持零依赖。
- **测试**：新增纯逻辑必须配单元测试；测试命名与既有文件保持一致（Android `XxxTest.kt`，iOS `XxxTests.swift`）。

## 提交信息

采用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <summary>

<body>
```

- `type`：`feat` / `fix` / `refactor` / `test` / `docs` / `build` / `chore` / `perf`。
- `scope` 建议使用模块或平台：`android` / `ios` / `agentd` / `backup` / `memory` / `i18n` / `docs`。
- `summary` 用一句话说明结果；正文可用中文，写清动机、影响面与验证方式。
- 一个提交只做一件事；格式化与行为改动分开提交。

示例：

```
fix(memory): 修正整理后归档记忆仍进入提示词

MemoryRepository.getRelevant 之前读 getForPersona，
归档条目会被重新注入 system。改为 getForPrompt，
并补一条 recall 深度用例。
```

## PR 流程

1. 从默认分支（`main`）开分支，分支名用 `type/short-description`（如 `docs/bilingual-readme`）。
2. 本地跑完上文的构建与测试；贴出关键命令与结果。
3. PR 描述包含：动机、行为变化、影响面（Android / iOS / schema / 备份 / 文案）、验证方式、关联 issue。
4. 涉及 UI 的改动请说明两端是否一致；不能一致时，在 [docs/IOS-PARITY.md](docs/IOS-PARITY.md) 的「平台差异」或「还没接上」表里补一行。
5. 涉及规格变化的改动，同时更新对应 `docs/ROADMAP-*.md` 或 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。
6. 评审通过前不要合并；维护者合并时统一使用 squash。

## 不要提交、不要修改

以下内容**不进入版本库**（`.gitignore` 已排除，仍未跟踪的也请不要 `git add -f`）：

- 任何 API Key、`keystore.properties`、`*.jks` / `*.keystore`、`local.properties`；
- `dist/`、APK / AAB / IPA、`app/build/`、`.gradle/`、`ios/.build/`；
- `docs/screenshots/` 与其它截图（仓库不发布截图，文档里也不要引用图片文件）；
- 本地工具链与缓存目录（例如把 SDK、Gradle 缓存放在仓库内时产生的目录）。
- 未经讨论不要改 `LICENSE`；修改许可证需要维护者决定。

## 数据结构改动

数据库 schema 目前是 **v10**，备份格式是 **v2**。改动流程：

1. **两端同时改**：Android（Room 实体、DAO、`MyFriendDatabase` 的 `version`）与 iOS（`Models.swift`、`SQLiteStore`、`Schema.swift` 的建表语句与 `Schema.version`）必须在同一个 PR 里完成。
2. **schema +1**：Room `@Database(version = N+1)`，iOS `Schema.version = N+1`（打开库时写入 `user_version`，两端数字仍需相等）；`app/schemas/<库名>/<N+1>.json` 由 KSP 自动导出，需要随 PR 提交。
3. **写迁移**：Android 在 `MyFriendDatabase` 增加 `MIGRATION_N_(N+1)`；iOS 在 `Schema.swift` 的建表语句与 `migrateLegacyData`（补表 / 补列，必须幂等）里加对应逻辑；禁止使用 destructive fallback 或「删库重建」。
4. **写迁移测试**：Android 在 `app/src/androidTest/.../MyFriendDatabaseMigrationTest.kt` 覆盖旧库打开后的表结构与数据；iOS 在 `ios/MyFriendKit/Tests/MyFriendKitTests/Data/SchemaMigrationTests.swift` 补迁移用例。迁移必须可重复执行（幂等）。
5. **备份**：字段增删要判断是否影响 `BackupCodec`。影响则 `formatVersion` +1，两端 `BackupCodec.*` 一起改，并**重新生成** `fixtures/backup/android-export-v3.json` 与 `ios-export-v3.json`（当前版本；v1 / v2 样本只作为输入，永不重写）。
6. **验证**：`BackupFixtureTest`（Android）与 `BackupInteropTests`（iOS）必须同时绿；再确认旧备份仍能读入。

字段命名、枚举取值与默认值以 [docs/design/roadmap-1.9.md](docs/design/roadmap-1.9.md) 与两端 `BackupCodec` 为准；两端字段名必须逐字相同。

## 文案与 i18n

界面语言为中文（默认/base）与英文，跟随系统语言，没有应用内切换。新增或修改用户可见文案时：

1. **Android**：同时在 `app/src/main/res/values/strings.xml`（中文）与 `app/src/main/res/values-en/strings.xml`（英文）添加同一个 key；key 使用 snake_case 加页面/组件前缀（`home_` / `chat_` / `persona_` / `settings_` / …），种子人格文案放 `seed.xml`。
2. **iOS**：同时在 `ios/MyFriend/Resources/zh-Hans.lproj/Localizable.strings` 与 `en.lproj/Localizable.strings` 添加同一条目。iOS 以中文原文为 key。
3. 两份资源的 key 集合与占位符集合必须一致（占位符允许换顺序，不允许丢参数），守卫测试：Android `app/src/test/java/com/myfriend/app/i18n/StringResourceParityTest.kt`，iOS `ios/MyFriendKit/Tests/MyFriendKitTests/I18N/LocalizationResourceTests.swift`。
4. UI 层不允许硬编码中文；守卫测试：Android `ChineseLiteralGuardTest.kt`、iOS `UIChineseLiteralGuardTests.swift`。需要豁免的固定文案（日志、模型输入）以白名单形式显式声明。
5. **不翻译**：代码注释、日志、`ios/MyFriendKit` 与 Android `domain/` 的模型提示词脚手架、工具名与 JSON 字段名。模型语言由 system 末尾的语言指令小节控制，该小节**随界面语言切换**：Android 模板在 `values` / `values-en` 的 `prompt_language_directive`，iOS 在 `Localizable.strings` 的同名语义 key；两端文案逐字一致，只有占位符写法不同（Android `%1$s`、iOS `%1$@`）。
6. 详细方案与已知限制见 [docs/I18N.md](docs/I18N.md)。

## 不要重新引入已被删除的设计

以下能力是**刻意删除**的，不是待办事项。重新引入会破坏产品契约，需要先开 issue 并获得维护者明确同意：

- 额度、预算、单价、超支拦截（用量只统计 token）；
- 记忆开关（自动抽取 / 每日整理 / 联网打分）、读消息节奏；
- 每天最多主动几次的显式上限（改由 `proactiveLevel` 决定）；
- 关系时间线、关系报告、亲密度打分；
- TTS 语音、人格商店；
- 对人格自身决定（不回、睡觉、沉默）的确认弹窗。

完整删除清单与原因见 [docs/design/roadmap-1.9.md](docs/design/roadmap-1.9.md) 第一节。

## 版本号与发版

版本号在内测阶段刻意保持简单、可预期：

- 内测阶段主版本号固定为 `0`；小改动末尾 +1（`0.10.0` → `0.10.1`），大改动中间 +1、末尾归零（`0.9.1` → `0.10.0`）。
- `versionCode` 与 `CURRENT_PROJECT_VERSION` 每次发布 +1 且保持相等；schema 版本与 App 版本没有对应关系。

发版清单：

1. 改版本号与状态（**4 处**）：`app/build.gradle.kts` 的 `versionCode` / `versionName`；`ios/MyFriend.xcodeproj/project.pbxproj` 的 `CURRENT_PROJECT_VERSION` / `MARKETING_VERSION`；`docs/CHANGELOG.md` 新条目；`README.md` 顶部的项目状态。
2. 同步双语镜像：`docs/CHANGELOG.en.md`、`README.en.md`（及对应英文文档若版本相关）。
3. 跑两端测试：`./gradlew :app:testDebugUnitTest`、`cd ios/MyFriendKit && swift test --disable-sandbox`；schema 或备份有改动时补跑迁移测试与 fixture 测试。
4. 确认两端都能构建：`./gradlew :app:assembleDebug` 与 `xcodebuild ... build`。
5. 打一个与该版本对应的 tag（沿用仓库既有命名）。
6. 推送 tag 并发布说明；说明内容取自 CHANGELOG 该版本条目。

## License

提交贡献即表示同意以 [Apache-2.0](LICENSE) 授权。
