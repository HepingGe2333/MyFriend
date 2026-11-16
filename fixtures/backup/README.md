# 跨端备份样本（fixtures）

## 版本对照

| 文件 | 格式版本 | 用途 |
| --- | --- | --- |
| `android-export-v1.json` | v1 | **历史样本**，必须永远能被两端读通（验证「旧备份别丢」） |
| `ios-export-v1.json` | v1 | 同上（含已移除的 Harness / 额度字段，正好验证未知字段被忽略） |
| `android-export-v2.json` | v2 | 0.9.0 起 Android 导出的样本：一人一段会话、人格细分字段、已读时间、无额度设置 |
| `ios-export-v2.json` | v2 | 0.9.0 起 iOS 导出的样本，双方必须互读通过 |
| `android-export-v3.json` | v3 | 0.12.0 起 Android 导出的样本：**图片与头像随备份走**（`media[]`，base64），附件只写裸名 |
| `ios-export-v3.json` | v3 | 0.12.0 起 iOS 导出的样本，双方必须互读通过 |
| `android-export-v4.json` | v4 | 0.12 起 Android 导出的样本：`apiConfigs[].webToolsProvider` 随配置走（MessageAPI/Tavily/Brave/Serper/自建），Key 仍不进备份 |
| `ios-export-v4.json` | v4 | 0.12 起 iOS 导出的样本，双方必须互读通过 |
| `android-export-v5.json` | v5 | 0.12 起 Android 导出的样本：`personas[]` 带 `worldJson`/`speechStyleJson`/`activityJson`，`presets[]` 带前两者（世界预设与话风学习） |
| `ios-export-v5.json` | v5 | 0.12 起 iOS 导出的样本，双方必须互读通过 |


这些文件是 **Android 与 iOS 之间的接口契约**，不是示例数据：

| 文件 | 谁生成 | 谁消费 | 判据 |
| --- | --- | --- | --- |
| `android-export-v4.json` | Android：`app/src/test/java/com/yap/app/domain/BackupFixtureTest.kt` | iOS：`ios/YapKit/Tests/YapKitTests/Backup/BackupInteropTests` | iOS 必须解出全部段 + 设置 + `media[]` + `apiConfigs[].webToolsProvider`；图片可 base64 解码、字节数与 `bytes` 一致；无密钥字段 |
| `ios-export-v4.json` | iOS：同一组测试 | Android：同一个 `BackupFixtureTest` | 同上（方向相反）：`formatVersion=4`、`media` 字段齐全、`webToolsProvider` 合法、base64 无空白、不出现设备绝对路径 |
| `android-export-v2.json` / `ios-export-v2.json` | 0.9.0 起的历史 v2 样本，**不再重写** | 两端的 v2 兼容用例 | 仍必须能读：`media` 缺省为空、图片只留元数据（按旧行为告警） |
| `android-export-v1.json` / `ios-export-v1.json` | 1.8 时代留下的历史样本，**不再重写** | 两端的 v1 兼容用例 | 必须仍能读进来：旧开关与额度字段被忽略、人格缺的字段走默认、多段会话只留最新 |

共同要求：
- `format = "myfriend.backup"`（兼容保留的格式名，产品已更名为 YetAnotherPal / Yap，不要随品牌改名）、`formatVersion = 5`（0.12 起；v4/v3/v2/v1 只作为输入）、`apiKeysOmitted = true`；
- `media[]` 的键固定为 `id` / `kind`（`PERSONA_AVATAR` / `USER_AVATAR` / `CHAT_IMAGE`）/ `personaId`（可空）/ `fileName` / `mime` / `bytes` / `data`（标准 base64，无换行）；总量 128 MiB、单张 8 MiB；
- **任何密钥字段都不允许出现**（`apiKey` / `*Cipher` / `Authorization` / `customHeadersJson`），两端导出前后都会自检；
- 缺字段用默认值、未知字段忽略：这样老备份能被新版本读，新备份也不会把旧版本读崩；
- 改动备份格式时**两端一起改**，并重新生成两个 v5 文件；只改一边，测试立刻红。
- **0.12 的字段增删（v5）**：`personas[]` 新增 `worldJson`/`speechStyleJson`/`activityJson`，`presets[]` 新增 `worldJson`/`speechStyleJson`（均可空；世界预设与话风学习见 `spec/persona-guidance`）。
- **0.12 的字段增删（v4）**：`apiConfigs[]` 新增 `webToolsProvider`（默认 `MESSAGEAPI`；搜索 Key 仍不进备份）。
- **0.12.0 的字段增删**：新增顶层 `media[]`；`personas[].avatarUri` / `presets[].avatarUri` / `messages[].attachmentsJson` 里的路径改成裸文件名（真正字节在 `media[]` 里）。备份**不含**消息区块（`blockIndex` / `message_blocks` 是派生数据，导入时按消息顺序重建）。
- **0.9.0 的字段增删**：`personas` 多了 `traits` / `likes` / `behaviors` / `backstory` / `quirks`（前三个是 JSON 字符串数组）/ `proactiveLevel` / `memoryRetention` / `extractionEvery` / `sleepEnabled` / `wakeThreshold` / `dndOnSleep`；`sessions` 多了 `memoryRetentionOverride` / `extractionEveryOverride` 且每人只有一条；`messages` 多了 `readAt`；`apiConfigs` 去掉了三个 Harness 字段；`settings` 去掉了 `replyPace` / 记忆四开关 / `proactiveDailyLimit` / 月度额度与单价。

**枚举取值必须是合法的**：`proactiveRules[].source` 只有 `AI` / `MANUAL`，`apiConfigs[].provider` 只有
`OPENAI` / `GEMINI` / `CLAUDE`，`agentIntents[].kind` 只有 `REACH_OUT` / `FOLLOW_UP`。写了别的字符串两端都会
静默退成默认值——那等于「同一份备份换端后语义变了」，比读不出来更难查。

改动流程：改 `BackupCodec.kt` / `BackupCodec.swift` → 跑 Android `:app:testDebugUnitTest --tests "*BackupFixtureTest*"` 与 iOS `swift test --disable-sandbox` → 两个 v2 json 都会重写 → 一起提交。
