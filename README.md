# MyFriend

一个把「人格」当作**长期存在的人**、而不是一组聊天参数的 AI 陪伴应用。

Android（Kotlin · Jetpack Compose）+ iOS 16+（SwiftUI），同一套记忆、同一颗心跳、**同一份备份文件**。

> **当前版本：0.9.1（内测）** · 数据库 schema v10 · 备份格式 v2
> 版本号规则见 [docs/VERSIONING.md](docs/VERSIONING.md)：小改动末尾 +1，大改动中间 +1，大版本号除非手动指定永远是 0。

---

## 它是什么

大多数「AI 聊天」产品把你和模型之间塞满旋钮：记性开不开、回得多快、一天能主动几次、这个月还能花多少。
MyFriend 的立场相反——**这些是「他」的事，不是「你」的事**：

- 他记不记得、什么时候回、多久主动找你一次，由他的性格与心情决定，界面上没有开关；
- 他可以读了你的消息、显示「已读」，然后这一轮**选择不回**（过一会儿再自己决定要不要开口）；
- 他也会睡觉。睡着时消息留着；睡前要不要开免打扰是他自己的决定；没开免打扰时，你连着叫他够多条才会把他叫醒，醒来心情会缓一缓；
- 每个人格就是你手机里的一个联系人——头像、名字、你们那一段对话，仅此而已。

它**不做**这些（不是没做完，是刻意不做）：关系时间线 / 亲密度打分、TTS 语音、人格商店、对人格自己的决定加确认弹窗、额度 / 单价 / 「花超了就停下」的闸门（只有 token 统计）。

## 现在能用什么

| 能力 | 说明 |
| --- | --- |
| 联系人式首页 | 一行一个人：头像、名字、最后一句、时间、未读；点名字就是继续你们那一段 |
| 聊天 | SSE 流式、停止生成、Markdown、代码块、LaTeX、图片（每条最多 4 张，气泡缩略图 + 全屏查看，**两端都可用**）、长按菜单（复制 / 编辑重发 / 重新生成 / 删除） |
| 已读与沉默 | `readAt` 已读回执；`defer_reply` 让模型这一轮选择不回，界面只显示「已读」 |
| 睡眠 | 主动睡觉、睡前免打扰（他自己开）、唤醒阈值（30 分钟内消息数）、被叫醒会把心情拉回 |
| 人格 | 性格 / 喜欢的事 / 行为方式 / 成长经历 / 个人设定，加上主动程度、记性、抽记忆频率；提示词全文编辑、版本快照、AI 人格进化提案 |
| 头像 | 从相册给她/他选一张（采样压缩后存本地、统一渲染），你也可以有自己的一张，显示在你的消息旁 |
| 长期记忆 | 自动抽取、生命周期（还在用 / 沉下去了 / 已归档）、分量会淡、每天整理、`recall` 三档检索、回忆会重构记忆 |
| 检索 | 中文倒排 + 本地 384 维哈希嵌入（`local-hash-v1`，纯离线、零依赖）余弦重排 |
| 长会话 | 最近 24 条逐字保留，更早的收成一段「【更早之前你记得的】」摘要，原文一条不删 |
| 心跳 | U 形曲线（刚聊完不打扰、越久越想、三小时封顶），中了也只是叫他「想一想」，说什么由他决定 |
| 在线状态 | 「他此刻」：平静 / 在发呆 / 在写东西…，以及「睡着了 · 08:00 醒」 |
| 工具 | `remember` / `recall` / `set_mood` / `set_quiet_hours`（含免打扰）/ `web_search` / `web_fetch` / `persona_patch` / `defer_reply`，工具结果回灌给模型继续说完；**两端都有真执行器** |
| API | 任意 OpenAI 兼容服务：多套配置、Base URL / Key / 模型 / 自定义 Headers、连通性测试、推理参数、Gemini 与 Claude 原生适配已预留 |
| 用量 | 只统计 token：本月总数、按用途、按人排行。没有钱、没有额度、没有任何拦截 |
| 并发 | 全局最多 3 个 LLM 请求同时在跑，后台最多占 2 位，聊天永远保底 1 位 |
| 备份 | 导出 / 导入（合并 / 替换 / 预览），两端同一个 JSON 契约；API Key 永不进备份 |
| 外观 | 浅色 / 深色 / 跟随系统、主题色、字体大小、气泡样式、聊天背景（颜色或图片）、动画与触感开关 |

## 目录结构

```
.
├── app/                    Android 应用（Kotlin · Compose · Hilt · Room · WorkManager）
│   └── src/main/java/com/myfriend/app/
│       ├── data/           Room 实体 / DAO / 迁移 / 仓库 / DataStore / Keystore
│       ├── domain/         纯算法：记忆生命周期、心情、心跳、链接识别、向量、备份编解码…
│       ├── network/        OpenAI 兼容 SSE（多模态）+ 并发闸 + 用量
│       ├── ui/             components / screens / theme
│       └── util/           时间、LaTeX、媒体压缩与持久化
├── ios/                    iOS 16+ 应用
│   ├── MyFriendKit/        与 UI 无关的 SPM 包（Domain / Data / Net / Backup / Chat / Services），可 swift test
│   └── MyFriend/           SwiftUI 层（App / Views）
├── harness/                零依赖 Node.js 参考服务（OpenAI 兼容 mock，含工具与心跳分支）
├── fixtures/backup/        跨端备份黄金样本（v1 只读样例 + v2 契约）
├── docs/                   设计文档、对齐文档、更新记录、版本规则
└── gradle/                 Gradle Wrapper
```

## 怎么跑起来

**Android**（JDK 17、Android SDK Platform 35）：

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # 指向你的 SDK，此文件不进仓库
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**iOS**（Xcode 16+，部署目标 iOS 16.0，**零第三方依赖**）：

```bash
cd ios/MyFriendKit && swift test --disable-sandbox      # 纯逻辑，macOS 上秒级
cd ios && xcodebuild -project MyFriend.xcodeproj -scheme MyFriend \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

**本地联调参考服务**（不需要真 API Key 就能把全链路走完）：

```bash
node harness/server.mjs        # http://127.0.0.1:8787/v1 ，模型 ID 填 myfriend-reference
```

它认得 `remember` / `recall` / `set_mood` / `web_search` / `myfriend-wake` / `defer_reply` /
记忆抽取 / 记忆整理 / 历史压缩这些分支，`[chat]` 日志会打印 `systemChars` 与本轮用的工具，方便核对
「人格与记忆是不是真的随 `system` 注入了」。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 每个版本干了什么 |
| [docs/VERSIONING.md](docs/VERSIONING.md) | 版本号规则与发版清单 |
| [docs/ROADMAP-1.9.md](docs/ROADMAP-1.9.md) | 0.9.0（旧编号 1.9.0）的完整规格与验收表 |
| [docs/IOS-PARITY.md](docs/IOS-PARITY.md) | 两端哪些必须一致、哪些受平台限制、iOS 还缺什么 |
| [docs/ROADMAP-1.7.md](docs/ROADMAP-1.7.md) | 链接与内置浏览器、心跳曲线、向量检索、长会话压缩 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 提 PR 前要读的东西 |

## 测试

两端的测试都是「真的跑过才算」：

| 平台 | 命令 | 规模 |
| --- | --- | --- |
| Android | `./gradlew :app:testDebugUnitTest` | 619 条单元测试（43 个测试类）+ 模拟器上的数据库迁移测试（v6→…→v10，含「Room 真正打开旧库」的表校验） |
| iOS | `cd ios/MyFriendKit && swift test --disable-sandbox` | 810 条单元测试（域算法 / 数据 / 备份 / 网络 / 聊天 / 服务 / 工具 / 附件） |
| 备份互通 | 两端 `BackupFixtureTest` ↔ `BackupInteropTests` | Android 导出的 `android-export-v2.json` 必须被 iOS 读通，反之亦然；v1 样本只读不写 |

## 隐私

聊天、记忆、人格、头像与密钥只保存在设备本地。人格提示词只会发送给**该人格绑定的那个 API**。
没有内置遥测、崩溃上报或日志上传。API Key 在 Android 用 Keystore 加密、在 iOS 用 Keychain 保存，
不写日志、不进备份、不进人格导出文件。

## License

[Apache-2.0](LICENSE) © 2026 Heping Ge

提交贡献即表示同意以同一协议授权（见 [CONTRIBUTING.md](CONTRIBUTING.md)）。
