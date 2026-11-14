# 版本号规则（内测 0.x）

> English version: [VERSIONING.en.md](VERSIONING.en.md)

MyFriend 目前处于**内测阶段**，版本号只跟改动大小相关，不跟「功能多不多」的直觉相关：

| 改动类型 | 做法 | 例子 |
| --- | --- | --- |
| 小改动（修 bug、调文案、打磨一个功能点） | **末尾 +1** | `0.10.0` → `0.10.1` |
| 大改动（新的能力面、数据结构变更、界面重构） | **中间 +1，末尾归 0** | `0.9.1` → `0.10.0` |
| 主版本号（`0.x` 的 `0`） | **除非维护者手动指定，否则永远是 0** | —— |

内测结束、正式发布时才会由维护者手动把第一位改成 `1.x`。当前版本为 **0.10.0**，数据库 schema **v10**，备份格式 **v2**。

## 每次发布要同步的四个地方

| 位置 | 字段 |
| --- | --- |
| `app/build.gradle.kts` | `versionCode`（每次发布 +1，只增不减）、`versionName` |
| `ios/MyFriend.xcodeproj/project.pbxproj` | `CURRENT_PROJECT_VERSION`（与 Android 的 `versionCode` 相等）、`MARKETING_VERSION` |
| `docs/CHANGELOG.md` | 新增该版本的记录 |
| `README.md` | 「项目状态」表中的版本一行 |

`README.md`、`docs/CHANGELOG.md` 均有英文镜像（`README.en.md`、`docs/CHANGELOG.en.md`），发布时一并更新，否则两份文档会在「当前版本」上不一致。

## 发布步骤

1. 按上表改完四个地方，并同步英文镜像；
2. 跑 Android `./gradlew :app:testDebugUnitTest`；
3. 跑 iOS `cd ios/MyFriendKit && swift test --disable-sandbox`；
4. schema 或备份格式有变化时，补跑迁移测试与 `BackupFixtureTest` / `BackupInteropTests`；
5. 确认两端可构建：`./gradlew :app:assembleDebug`、`xcodebuild ... build`；
6. 打 tag（沿用仓库既有命名）；
7. 用 CHANGELOG 中该版本的条目作为发布说明。

## 为什么 `versionCode` 与 `CURRENT_PROJECT_VERSION` 要相等

两端共用同一份备份文件与同一套数据库 schema（v10）。让安装包的构建号也相等，排查「某份备份是哪个版本导出的」时就不必再区分平台。构建号只增不减，升级路径才不会断。

schema 与备份各自有独立版本：

| 版本 | 何时 +1 |
| --- | --- |
| `MyFriendDatabase` 的 `version` / iOS 的 `user_version` | 表结构变化时必须 +1，并配一条迁移与一条迁移测试（两端都要） |
| `backup.formatVersion` | 备份结构变化时 +1，并同时更新 `fixtures/backup/` 与两端互通测试 |

schema 版本与 App 版本**没有**对应关系：`0.10.0` 的 schema 仍然是 v10。版本号在代码中的唯一真源是上面两处构建文件；关于页与备份里的 `appVersion` 都从构建号派生，不再手写版本字符串。
