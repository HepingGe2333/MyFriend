# 跨端备份样本（fixtures）

## 版本对照

| 文件 | 格式版本 | 用途 |
| --- | --- | --- |
| `android-export-v1.json` | v1 | **历史样本**，必须永远能被两端读通（验证「旧备份别丢」） |
| `ios-export-v1.json` | v1 | 同上（含已移除的 Harness / 额度字段，正好验证未知字段被忽略） |
| `android-export-v2.json` | v2 | 1.9 起 Android 导出的样本：一人一段会话、人格细分字段、已读时间、无额度设置 |
| `ios-export-v2.json` | v2 | 1.9 起 iOS 导出的样本，双方必须互读通过 |


这些文件是 **Android 与 iOS 之间的接口契约**，不是示例数据：

| 文件 | 谁生成 | 谁消费 | 判据 |
| --- | --- | --- | --- |
| `android-export-v2.json` | Android：`app/src/test/java/com/myfriend/app/domain/BackupFixtureTest.kt` | iOS：`ios/MyFriendKit/Tests/MyFriendKitTests/Backup/BackupInteropTests` | iOS 必须能解出全部 9 段 + 设置，人格的细分字段、`messages[].readAt`、会话的两个 override 都在，字段值逐项一致，会话的滚动摘要（`summary` / `summarizedUpTo` / `summaryAt`）也要读得出来 |
| `ios-export-v2.json` | iOS：同一组测试 | Android：同一个 `BackupFixtureTest` | Android 必须能 `BackupCodec.decode` 成功，`format="myfriend.backup"`、`formatVersion=2`、各段非空、无密钥字段 |
| `android-export-v1.json` / `ios-export-v1.json` | 1.8 时代留下的历史样本，**不再重写** | 两端的 v1 兼容用例 | 必须仍能读进来：旧开关与额度字段被忽略、人格缺的字段走默认、多段会话只留最新 |

共同要求：
- `format = "myfriend.backup"`、`formatVersion = 2`（1.9 起；v1 只作为输入）、`apiKeysOmitted = true`；
- **任何密钥字段都不允许出现**（`apiKey` / `*Cipher` / `Authorization` / `customHeadersJson`），两端导出前后都会自检；
- 缺字段用默认值、未知字段忽略：这样老备份能被新版本读，新备份也不会把旧版本读崩；
- 改动备份格式时**两端一起改**，并重新生成两个 v2 文件；只改一边，测试立刻红。
- **1.9 的字段增删**：`personas` 多了 `traits` / `likes` / `behaviors` / `backstory` / `quirks`（前三个是 JSON 字符串数组）/ `proactiveLevel` / `memoryRetention` / `extractionEvery` / `sleepEnabled` / `wakeThreshold` / `dndOnSleep`；`sessions` 多了 `memoryRetentionOverride` / `extractionEveryOverride` 且每人只有一条；`messages` 多了 `readAt`；`apiConfigs` 去掉了三个 Harness 字段；`settings` 去掉了 `replyPace` / 记忆四开关 / `proactiveDailyLimit` / 月度额度与单价。

**枚举取值必须是合法的**：`proactiveRules[].source` 只有 `AI` / `MANUAL`，`apiConfigs[].provider` 只有
`OPENAI` / `GEMINI` / `CLAUDE`，`agentIntents[].kind` 只有 `REACH_OUT` / `FOLLOW_UP`。写了别的字符串两端都会
静默退成默认值——那等于「同一份备份换端后语义变了」，比读不出来更难查。

改动流程：改 `BackupCodec.kt` / `BackupCodec.swift` → 跑 Android `:app:testDebugUnitTest --tests "*BackupFixtureTest*"` 与 iOS `swift test --disable-sandbox` → 两个 v2 json 都会重写 → 一起提交。
