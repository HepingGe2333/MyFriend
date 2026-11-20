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
| `android-export-v6.json` | v6 | 0.13 起 Android 导出的样本：`media[]` 扩到表情/语音/视频（`STICKER`/`AUDIO`/`VIDEO`），新增顶层 `stickers[]` 与 `diaryNotes[]`；表情字节只内联一次 |
| `ios-export-v6.json` | v6 | 0.13 起 iOS 导出的样本；缺失时 Android 测试跳过（并行实现） |
| `android-export-v7.json` | v7 | 0.15 起 Android 导出的样本：人格 `languageTag` / 拉黑字段、消息 `blocked`、顶层 `mentalActivities[]` / `sessionMemory[]`（`topics` 数组 + 游标）、`media[].sha256` |
| `ios-export-v7.json` | v7 | 0.15 起 iOS 导出的样本；与 Android v7 同一份字段契约（缺失时 Android 测试跳过） |
| `server-export-v7-extensions.json` | v7 | 服务端 `/v1/backup` 形态的只读样本：两张 0.15 新表写在 `extensions` 里（另有 `proactiveFailures`，客户端忽略），用来验证「顶层优先、缺失回退 extensions」 |


这些文件是 **Android 与 iOS 之间的接口契约**，不是示例数据：

| 文件 | 谁生成 | 谁消费 | 判据 |
| --- | --- | --- | --- |
| `android-export-v4.json` | Android：`app/src/test/java/com/yap/app/domain/BackupFixtureTest.kt` | iOS：`ios/YapKit/Tests/YapKitTests/Backup/BackupInteropTests` | iOS 必须解出全部段 + 设置 + `media[]` + `apiConfigs[].webToolsProvider`；图片可 base64 解码、字节数与 `bytes` 一致；无密钥字段 |
| `ios-export-v4.json` | iOS：同一组测试 | Android：同一个 `BackupFixtureTest` | 同上（方向相反）：`formatVersion=4`、`media` 字段齐全、`webToolsProvider` 合法、base64 无空白、不出现设备绝对路径 |
| `android-export-v7.json` | Android：`BackupFixtureTest` | iOS：`BackupInteropTests`（v7 用例） | `formatVersion=7`；人格 `languageTag`/拉黑字段、消息 `blocked`、`mentalActivities[]`/`sessionMemory[]`（`topics` 数组 + 游标）、每条 `media[].sha256` 与字节一致、附件 `kind` 显式且合法、无密钥字段 |
| `ios-export-v7.json` | iOS：同一组测试 | Android：同一个 `BackupFixtureTest` | 方向相反，判据同上；`media[].sha256` 非空时必须与 base64 解码后的字节一致 |
| `server-export-v7-extensions.json` | 服务端 `/v1/backup` 的形态（手工维护的只读样本） | Android：`BackupFixtureTest` + `ServerBackupV6InteropTest` | 两张 0.15 新表在 `extensions` 里，解码后要落到顶层段；`extensions.proactiveFailures` 被忽略 |
| `android-export-v2.json` / `ios-export-v2.json` | 0.9.0 起的历史 v2 样本，**不再重写** | 两端的 v2 兼容用例 | 仍必须能读：`media` 缺省为空、图片只留元数据（按旧行为告警） |
| `android-export-v1.json` / `ios-export-v1.json` | 1.8 时代留下的历史样本，**不再重写** | 两端的 v1 兼容用例 | 必须仍能读进来：旧开关与额度字段被忽略、人格缺的字段走默认、多段会话只留最新 |

共同要求：
- `format = "myfriend.backup"`（兼容保留的格式名，产品已更名为 YetAnotherPal / Yap，不要随品牌改名）、`formatVersion = 7`（0.15 起；v6/v5/v4/v3/v2/v1 只作为输入）、`apiKeysOmitted = true`；
- `media[]` 的键固定为 `id` / `kind`（`PERSONA_AVATAR` / `USER_AVATAR` / `CHAT_IMAGE`，v6 起还有 `STICKER` / `AUDIO` / `VIDEO`）/ `personaId`（可空）/ `fileName` / `mime` / `bytes` / `data`（标准 base64，无换行）/ `sha256`（v7 起可选，原始字节的 SHA-256）；总量 128 MiB、单个文件 8 MiB（视频也按 8 MiB，超限跳过并计数）；
- v6 的 `stickers[]` 字段：`id` / `ownerUserId` / `personaId` / `fileName` / `file` / `mime` / `width` / `height` / `frames` / `bytes` / `source` / `createdBy` / `createdAt`；`fileName` 与 `media[]` 里 `kind=STICKER` 的 `fileName` 对应，**不重复内联 base64**；`file` 是 iOS 端的同义字段（两端导出都写、读取取非空那个），保证任一端的 v6 备份另一端都能接住；
- v6 的 `diaryNotes[]` 字段：`id` / `personaId` / `content` / `createdAt` / `remindAt` / `remindedAt` / `updatedAt`；
- v6 缺段兼容：`stickers` / `diaryNotes` 缺失或坏条目按空/跳过处理，绝不让整份失败。
- **任何密钥字段都不允许出现**（`apiKey` / `*Cipher` / `Authorization` / `customHeadersJson`），两端导出前后都会自检；
- **0.15 的字段增删（v7）**：
  - `personas[]` 新增 `languageTag`（String?，BCP-47 主标签 `zh`/`en`/`ja`…；空 = 没选过）与
    `blockedUntil`（Int64?，毫秒）/ `blockReason`（String?）/ `blockProposalJson`（String?，未生效的两轮确认提议）；
  - `messages[]` 新增 `blocked`（Bool，默认 false）；
  - 顶层新增 `mentalActivities[]`：`id, personaId, sessionId?, at, thought, moodLabel?, moodIntensity?, moodValence?, moodArousal?, moodDominance?`；
  - 顶层新增 `sessionMemory[]`：`sessionId, personaId, shortSummary?, shortUpdatedAt?, shortUserMessages, longSummary?, longUpdatedAt?, longUserMessages, longCheckedUserMessages, topics[{timeLabel,summary}]?, topicUpdatedAt?, topicUserMessages?, topicCheckedUserMessages?`。
    主题总结用**数组**（最多 12 段，与服务端 `extensions` 同形）；读取端宽容接受旧写法 `topicsJson` 字符串与本地别名 `shortUserCount/longUserCount/topicUserCount`，导出只写规范键；
  - `media[]` 新增可选 `sha256`（原始字节的 SHA-256，64 位小写 hex）：导出写，导入非空必校验（不一致拒绝该条），老样本没有则跳过校验；服务端 `/v1/backup` 的 `media[].sha256` 是同一字段；
  - `attachmentsJson[].kind` 必须显式且只能是 `IMAGE/STICKER/AUDIO/VIDEO`（`AVATAR` 只出现在 `media[].kind` 的 `PERSONA_AVATAR/USER_AVATAR`），`mime` 必须与 kind 同族；未知 kind / mime 不匹配 → 拒绝该附件（不猜成图片）。旧数据 `kind:null` 仍等价 `IMAGE`、`image/gif` 仍等价 `STICKER`；
  - `attachmentsJson[].sha256`（可选）：本地校验过的内容哈希，随附件透传（用于本地副本复检）；读取端缺失就跳过校验，两端都不强制；
  - 服务端形态的 v7 把 `mentalActivities` / `sessionMemory` 写在可选的 `extensions` 里；读取时**顶层优先，缺失/空数组回退 `extensions`**；`extensions.proactiveFailures[]` 是服务端诊断段（客户端备份格式里没有它、导出不写：iOS 导入时落本机诊断台账供排查，Android 忽略）；
  - **v1–v6 仍然必须能读**：没有上面这些字段/段时按默认值处理（空列表、跳过 sha256 校验），旧备份恢复后本机已有的 0.15 数据（心理活动、会话备忘录）不被清掉；
- 缺字段用默认值、未知字段忽略：这样老备份能被新版本读，新备份也不会把旧版本读崩；
- 改动备份格式时**两端一起改**，并重新生成两个 v7 文件（`android-export-v7.json` / `ios-export-v7.json`）；只改一边，测试立刻红。旧的 `*-v6.json` / `*-v5.json` 保留不动（只读样本）。
- **0.13 的字段增删（v6）**：顶层新增 `stickers[]` / `diaryNotes[]`；`media[]` 的 `kind` 增加 `STICKER` / `AUDIO` / `VIDEO`；消息 `attachmentsJson` 的 `kind`/`durationMs` 跟着备份走，`uri` 仍只写裸文件名。
- **0.12 的字段增删（v5）**：`personas[]` 新增 `worldJson`/`speechStyleJson`/`activityJson`，`presets[]` 新增 `worldJson`/`speechStyleJson`（均可空；世界预设与话风学习见 `spec/persona-guidance`）。
- **0.12 的字段增删（v4）**：`apiConfigs[]` 新增 `webToolsProvider`（默认 `MESSAGEAPI`；搜索 Key 仍不进备份）。
- **0.12.0 的字段增删**：新增顶层 `media[]`；`personas[].avatarUri` / `presets[].avatarUri` / `messages[].attachmentsJson` 里的路径改成裸文件名（真正字节在 `media[]` 里）。备份**不含**消息区块（`blockIndex` / `message_blocks` 是派生数据，导入时按消息顺序重建）。
- **0.9.0 的字段增删**：`personas` 多了 `traits` / `likes` / `behaviors` / `backstory` / `quirks`（前三个是 JSON 字符串数组）/ `proactiveLevel` / `memoryRetention` / `extractionEvery` / `sleepEnabled` / `wakeThreshold` / `dndOnSleep`；`sessions` 多了 `memoryRetentionOverride` / `extractionEveryOverride` 且每人只有一条；`messages` 多了 `readAt`；`apiConfigs` 去掉了三个 Harness 字段；`settings` 去掉了 `replyPace` / 记忆四开关 / `proactiveDailyLimit` / 月度额度与单价。

**枚举取值必须是合法的**：`proactiveRules[].source` 只有 `AI` / `MANUAL`，`apiConfigs[].provider` 只有
`OPENAI` / `GEMINI` / `CLAUDE`，`agentIntents[].kind` 只有 `REACH_OUT` / `FOLLOW_UP`。写了别的字符串两端都会
静默退成默认值——那等于「同一份备份换端后语义变了」，比读不出来更难查。

改动流程：改 `BackupCodec.kt` / `BackupCodec.swift` → 跑 Android `:app:testDebugUnitTest --tests "*BackupFixtureTest*"` 与 iOS `swift test --disable-sandbox` → 两个 v2 json 都会重写 → 一起提交。
