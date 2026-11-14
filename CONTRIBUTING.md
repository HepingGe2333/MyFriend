# 参与 MyFriend

谢谢你愿意看一眼这个项目。它很小，规则也简单。

## 先读什么

| 文件 | 为什么读它 |
| --- | --- |
| [`README.md`](README.md) | 项目是什么、怎么跑起来 |
| [`docs/VERSIONING.md`](docs/VERSIONING.md) | 版本号怎么涨、发一次版要改哪几处 |
| [`docs/ROADMAP-1.9.md`](docs/ROADMAP-1.9.md) | 当前这套行为是怎么定下来的（规格 + 验收表） |
| [`docs/IOS-PARITY.md`](docs/IOS-PARITY.md) | iOS 与 Android 哪些必须一致、哪些受平台限制 |

## 环境

- **Android**：JDK 17、Android SDK 35。用仓库自带的 Gradle Wrapper：
  ```bash
  ./gradlew :app:testDebugUnitTest :app:assembleDebug
  ```
  需要 `local.properties` 指向你的 SDK（`sdk.dir=...`），这个文件不会进仓库。
- **iOS**：Xcode 16+，部署目标 iOS 16.0，**没有任何第三方依赖**（SQLite 用系统 `SQLite3`，网络用 `URLSession`）。
  ```bash
  cd ios/MyFriendKit && swift test --disable-sandbox
  cd ios && xcodebuild -project MyFriend.xcodeproj -scheme MyFriend \
    -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
  ```
  `--disable-sandbox` 是给本地 SPM 包在受限环境里跑测试用的；能正常跑时可以不加。

## 提交前必须做的事

1. **两端单测都绿**：Android `:app:testDebugUnitTest`，iOS `swift test --disable-sandbox`。
2. **改到数据结构**：schema 版本 +1、写迁移、写迁移测试、两端一起改；
   备份结构变了就同时改 `fixtures/backup/` 与两端互通测试，否则另一端会立刻红。
3. **改到设置项**：先问自己「这是用户该决定的事，还是他（人格）自己该决定的事」。
   这个项目的立场是：**他是不是记得、什么时候回、多久主动找一次，都由他自己决定**，不要加回旋钮。
4. **不要提交**：`local.properties`、keystore、`.tooling/`、构建产物、`dist/`、`docs/screenshots/`、任何 API Key。

## 代码风格

- Android：Kotlin + Compose，`kotlin.code.style=official`；DOM/纯逻辑抽成可单测的函数（项目里大量算法都是纯函数，别把边界条件埋在 Composable 里）。
- iOS：Swift 5.9，kit 层（`ios/MyFriendKit`）**不许依赖任何 UI**；App 层不许 `import MyFriendKit`（同一个 module）。
- 注释用中文，写「为什么」而不是「做了什么」；数字（阈值、夹取范围）要能指出它在规格的哪一条。

## 关于「AI 伴侣」这个产品立场

提 issue / PR 之前值得知道：这个项目刻意**不做**下面这些，不是没做完：

- 不做关系时间线、关系报告、亲密度打分（把人变成 KPI）；
- 不做 TTS 语音、不做「人格商店」；
- 不对人格自己的决定加确认弹窗（他选择不回、选择睡觉、选择沉默，都不需要你批准）；
- 不加额度、单价、预算这类「花超了就停下」的闸门——只有 token 统计。

## License

提交贡献即表示同意以 [Apache-2.0](LICENSE) 授权。
