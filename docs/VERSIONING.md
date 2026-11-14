# 版本号规则（内测 0.9.x）

MyFriend 目前处于**内测阶段**，版本号按下面的规则走，不跟「功能多不多」的直觉，只跟改动大小：

| 改动类型 | 怎么做 | 例子 |
| --- | --- | --- |
| 小改动（修 bug、调文案、打磨一个功能点） | **末尾 +1** | `0.9.0` → `0.9.1` |
| 大改动（一个新的能力面、数据结构变更、界面重构） | **中间 +1，末尾归 0** | `0.9.4` → `0.10.0` |
| 大版本号（`0.x` 的那个 `0`） | **除非维护者手动指定，否则永远是 0** | —— |

内测结束、正式发布时才会由维护者手动把第一位改成 `1.x`。

## 每次发布要同步的四个地方

| 位置 | 字段 |
| --- | --- |
| `app/build.gradle.kts` | `versionCode`（**每次发布 +1，只增不减**）、`versionName` |
| `ios/MyFriend.xcodeproj/project.pbxproj` | `CURRENT_PROJECT_VERSION`（与 Android 的 `versionCode` 对齐）、`MARKETING_VERSION` |
| `docs/CHANGELOG.md` | 新增一条该版本的记录 |
| `README.md` | 顶部「当前版本」一行 |

## 为什么 `versionCode` 与 `CURRENT_PROJECT_VERSION` 要相等

两端应用同一份备份文件、同一套数据库 schema（v10）。让安装包的构建号也相等，
排查「用户到底是哪个版本导出的备份」时就不用再分辨平台。schema 版本另有一套：

| 数据库 schema | 何时 +1 |
| --- | --- |
| `MyFriendDatabase` 的 `version` / iOS 的 `user_version` | 只要表结构变了就必须 +1，并配一条迁移 + 一条迁移测试（两端都要） |
| `backup.formatVersion` | 备份结构变了就 +1，并同时更新 `fixtures/backup/` 与两端互通测试 |

schema 版本与 App 版本**没有**对应关系：`0.9.1` 的 schema 仍然是 v10。
