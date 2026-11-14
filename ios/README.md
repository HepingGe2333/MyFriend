# MyFriend iOS（iOS 16+）

Android 版的同源移植：同一份人格、同一套记忆、同一条心跳曲线、**同一份备份 JSON**。
设计、对齐表、平台差异见 [../docs/IOS-PARITY.md](../docs/IOS-PARITY.md)。

## 目录

```
ios/
├── MyFriendKit/            与 UI 无关的一切（本地 SPM 包，可在 macOS 上 swift test）
│   └── Sources/MyFriendKit/{Domain,Data,Backup,Net,Chat,Services}
├── MyFriend/               SwiftUI 应用层（App 装配 + 各页面）
├── Config/Info.plist       部署配置（ATS、后台模式、本地网络说明）
├── MyFriend.xcodeproj      部署目标 iOS 16.0，无第三方依赖
└── scripts/                构建与真机安装脚本
```

## 在 Xcode 里第一次跑（推荐给真机测试）

1. 打开 `ios/MyFriend.xcodeproj`，左上角 scheme 选 **MyFriend**；
2. 选中工程 → **TARGETS: MyFriend** → *Signing & Capabilities*：
   - 勾上 **Automatically manage signing**；
   - **Team** 选你的 Apple ID（免费个人账号也行，7 天有效期）；
   - 如果 `com.myfriend.app.ios` 被占用，把 **Bundle Identifier** 改成 `com.<你的名字>.myfriend`；
3. 顶部设备选你的 iPhone（首次需要在 iPhone 上开「设置 → 隐私与安全性 → 开发者模式」并重启手机）；
4. 按 **⌘R**。首次安装后如果提示「不受信任的开发者」，去 iPhone 的
   「设置 → 通用 → VPN与设备管理」里信任你的证书，再运行一次；
5. App 里进「设置 → API 配置 → 新增」，填你自己的模型服务（或本机参考服务，见下一节）。

> 工程里 `CODE_SIGN_STYLE = Automatic`、没有写死 Team，所以你一选 Team 就能签名；
> 仓库里不带任何证书或描述文件。

## 在模拟器上跑

```bash
cd ios
TMPDIR=$PWD/.build/tmp \
CLANG_MODULE_CACHE_PATH=$PWD/.build/clang-cache \
SWIFT_MODULE_CACHE_PATH=$PWD/.build/swift-cache \
xcodebuild -project MyFriend.xcodeproj -scheme MyFriend \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath .build/DerivedData \
  OTHER_SWIFT_FLAGS='$(inherited) -disable-sandbox' build

xcrun simctl boot "iPhone 17 Pro" 2>/dev/null || true
xcrun simctl install booted .build/DerivedData/Build/Products/Debug-iphonesimulator/MyFriend.app
xcrun simctl launch booted com.myfriend.app.ios
xcrun simctl io booted screenshot /tmp/ios.png
```

两个 flag 都是**在这台受限环境的机器上**必须的，原因不是项目本身：

- `TMPDIR`：SwiftUI 的宏插件（`@State` 等）要在临时目录里落中间产物，默认目录不可写；
- `OTHER_SWIFT_FLAGS='-disable-sandbox'`：宏插件服务器（`swift-plugin-server`）被宿主沙箱拦住时，
  会报成 `SwiftUIMacros.StateMacro ... malformed response`，看起来像代码错，其实是工具链被拦。

在普通 Mac / Xcode 里正常点 Run 不需要这两个 flag。

## 调试启动助手（只在 Debug 里编译）

模拟器上没法「点一下再打字」，所以留了三个环境变量做验收（`DebugSeeding.swift`，Release 里不存在）：

```bash
SIMCTL_CHILD_MYFRIEND_DEBUG_API_BASE=http://127.0.0.1:8787/v1 \
SIMCTL_CHILD_MYFRIEND_DEBUG_SEND=你好 \
SIMCTL_CHILD_MYFRIEND_DEBUG_OPEN=chat \
xcrun simctl launch --terminate-running-process booted com.myfriend.app.ios
```

- `MYFRIEND_DEBUG_API_BASE`：自动建一条指向本地参考服务的 API 配置（Key 填 `sk-test`）；
- `MYFRIEND_DEBUG_SEND`：启动后在这段会话里真发一句，把流式回复写进 `Documents/debug.log`；
- `MYFRIEND_DEBUG_OPEN`：`chat` / `settings` / `memory` / `backup` / `personas` / `browser`。

读日志与库：

```bash
CONTAINER=$(xcrun simctl get_app_container booted com.myfriend.app.ios data)
tail -20 "$CONTAINER/Documents/debug.log"
sqlite3 "$CONTAINER/Library/Application Support/myfriend.db" 'select count(*) from messages;'
```

> **Keychain 提醒**：模拟器上的构建没有签名（`CODE_SIGNING_ALLOWED=NO`），
> `SecItemAdd` 会返回 `-34018`。`ResilientKeychain` 会在这种情况下把 Key 临时放进 UserDefaults，
> 并在日志里写着「这是调试兜底」。真机签名构建走正常 Keychain。

## 跑单元测试

```bash
cd ios/MyFriendKit && swift test --disable-sandbox
```

> macOS 上的 SwiftPM 会用 `sandbox-exec` 编译 manifest，在受限环境里会被拒；
> `--disable-sandbox` 是官方参数，只是关掉那一层包裹，不影响测试本身。

## 真机测试（iPhone）

真机需要你自己的 Apple 开发者账号签名，仓库里不带证书。

### 方式 A：Xcode（最省事）

1. 打开 `ios/MyFriend.xcodeproj`；
2. 选中 **MyFriend** target → *Signing & Capabilities* → 勾选 *Automatically manage signing*，
   Team 选你的账号（个人免费账号也可以，7 天有效期）；
3. 如果 Bundle Identifier 已被占用，改成 `com.<你的名字>.myfriend`（`PRODUCT_BUNDLE_IDENTIFIER`）；
4. 设备连上电脑 → 选你的 iPhone → Run；
5. 首次运行后在 iPhone 的「设置 → 通用 → VPN与设备管理」里信任你的开发者证书。

### 方式 B：命令行

```bash
cd ios
export DEVELOPMENT_TEAM=你的TeamID          # Xcode 里 Account 详情可查
export BUNDLE_ID=com.yourname.myfriend      # 可省略，默认 com.myfriend.app.ios
./scripts/build-device.sh                  # 产出 .build/DerivedData/Build/Products/Debug-iphoneos/MyFriend.app
./scripts/install-device.sh                # 用 devicectl 装到已连接的 iPhone 并启动
```

脚本会把 `DEVELOPMENT_TEAM` / `PRODUCT_BUNDLE_IDENTIFIER` 作为构建参数传进去，
并使用 `-allowProvisioningUpdates` 让 Xcode 自动申请描述文件。

## 连本地参考服务（Android 的 `harness/server.mjs`）

1. 电脑上起服务：`node harness/server.mjs`（在仓库根目录，端口 8787）；
2. iPhone 与电脑连同一个 Wi-Fi，记下电脑局域网 IP（`ipconfig getifaddr en0`）；
3. App 里「设置 → API 配置 → 新增」：
   - Base URL：`http://<电脑IP>:8787/v1`
   - 模型：`myfriend-reference`
   - API Key：随便填（`sk-test`）
   - 打开「允许不安全 HTTP」
4. 回到会话发一句，应该能看到流式回复。

`Config/Info.plist` 里已经为本地联调放开了 ATS（`NSAllowsArbitraryLoads`）并声明了
`NSLocalNetworkUsageDescription`——首次访问局域网时系统会弹一次权限，选「允许」。
**正式发布前**应把这两项改回 HTTPS-only。

## 0.9.1：头像、图片、工具与主动唤醒

这一版把 iOS 落后 Android 的部分补齐了，并且两端都能选头像。要点：

| 能力 | 位置 / 说明 |
| --- | --- |
| **图片消息** | `Support/Attachments.swift`（与 Android `Attachments.kt` 逐字段一致）+ `Support/MediaStorage.swift`（长边 1600 / JPEG 0.85 / ≤800KB，按显示尺寸解码）+ `Views/Chat/ChatImageBubble.swift`（气泡与全屏查看）；文件缺失显示「图片已不在本机」 |
| **头像** | `Views/Shared/MFAvatar.swift` 统一渲染；人格头像存沙盒并落库，`SettingsStore.myAvatarUri` 存「我的头像」（只存本机，不进备份） |
| **聊天侧工具执行器** | `Services/ToolExecutor.swift`：`think` / `web_search` / `web_fetch` / `remember` / `update_persona` / `set_quiet_hours`（含 `dnd`）/ `set_mood` / `recall` / `defer_reply`，参数夹取与 `【工具结果】` 回灌对齐 Android；`Net/WebToolsClient.swift` 走 DeepSeek 联网工具 |
| **主动唤醒** | `Services/ProactiveRuleService.swift` 落库 + `Views/Proactive/ProactiveListScreen.swift`（列表 / 开关 / 下次触发 / 删除 / 手动新建） |
| **历史压缩** | `Services/HistoryCompressionService.swift`：最近 24 条逐字保留、够 12 条或 24000 字压一段、原文不删，带拒收守卫与启动自检 |
| **人设卡** | `Domain/PersonaCard.swift` + `Services/PersonaCardRepository.swift`，编辑页可结构化编辑并进提示词 |
| **通知与后台** | `App/AppNotifications.swift`（锁屏不露正文、点开进聊天）+ `App/BackgroundScheduler.swift`（`BGAppRefreshTask` / `BGProcessingTask` 注册，回到前台补算一次） |
| **内存** | 图片按显示尺寸解码 + `NSCache`（`min(物理内存×10%, 48MB)`，内存警告清空）；只给最近 2 条带图消息解 data URL |

## 1.9：他是联系人，不是一个 API

1.9 的目标是「把设置项从产品里拿掉，把决定权交回给模型与人格」。与 Android 完全同构，
字段名、枚举值、算法与文案都以 `docs/design/roadmap-1.9.md` 为准。iOS 这边落成了这些：

| 改动 | 位置 / 说明 |
| --- | --- |
| **一人一段会话** | `chat_sessions.personaId` 上唯一索引（`index_chat_sessions_personaId_unique`）。schema v8/v9 → v10 时按 `COALESCE(lastMessageAt, updatedAt)` 每人保留最新那段，其余会话连同消息删除，规则里的 `sessionId` 引用被摘掉，保留段的 `apiConfigId` 补成当前默认配置（见 `Schema.migrateLegacyData`） |
| **消息已读** | `messages.readAt`：模型看到消息的那一刻写；聊天页只在「用户消息是最后一条、且这一轮没有回复」时显示「已读」 |
| **人格细分字段** | `personas` 新增 11 列（性格 / 喜欢 / 习惯 / 过去 / 设定 / 主动程度 / 记性 / 抽记忆间隔 / 会主动睡觉 / 唤醒阈值 / 睡前免打扰），编辑页分节填写，前五个拼进 `## 你是谁` 下的小节；`proactiveLevel` 与 `memoryRetention` 只当行为参数，不进提示词 |
| **删设置** | 记忆开关、读消息的节奏、月度额度/单价、「每天最多主动几次」全部删除；记忆内置开启 |
| **并发闸** | `LlmConcurrencyGate`：全局 3、后台 2（聊天保底 1 个位），后台排队 20 秒 / 聊天 60 秒就放弃（`Net/LlmConcurrencyGate.swift`） |
| **删额度** | `ApiBudget` / 超支拦截 / 定价覆盖全删；`api_usage` 仍然记账并**写 sessionId**；设置页「用量」只显示 token（总数 / 按用途 / 按人前 10） |
| **`defer_reply`** | 模型可以这一轮不回：不写助手消息、用户消息已读、落一条到点的 `agent_intents`（5–720 分钟，默认 45），界面上只看到「已读」 |
| **睡眠 / 免打扰 / 唤醒** | `sleepEnabled`、`dndOnSleep`（模型可改）、`wakeThreshold`；睡着时按「最近 30 分钟消息数」判定叫醒，免打扰永不唤醒；被叫醒会把 mood 拉回并留一条 tick |
| **首页 = 联系人列表** | 一行一个联系人（头像 / 名字 / 最后一句 / 时间 / 未读），「+」= 新建人格，长按删除（二次确认）；人格管理搬进设置 |
| **备份 v2** | `formatVersion = 2`，能读 v1（旧开关忽略、缺的字段走默认、多会话只留最新一段）；`fixtures/backup/ios-export-v2.json` 由 `BackupInteropTests` 生成 |

### 迁移与数据损失（说清楚，不藏）

1.9 升级时会**真的删数据**：同一个人多余的会话与它们的消息会被删掉，只留最新那段。
这是产品明确要求的（「只保留最新那段，其余直接删除」），但升级前请先「把他带走」——
v1 备份仍然能被 1.9 读回来，读回来时同样按「一人一段」只保留最新那段。

### 版本

- `MARKETING_VERSION = 0.9.1`、`CURRENT_PROJECT_VERSION = 11`（两端构建号相等；数据库 schema 仍是 v10）。

## 后台行为（和 Android 不一样的地方）

iOS 不允许常驻后台进程。这里能做到的是：

| 能力 | iOS 上的表现 |
| --- | --- |
| 心跳（他什么时候想你） | App 在前台时每 30 秒一拍；退到后台后系统按 `BGAppRefreshTask` 机会式唤醒（几十分钟到几小时不等）；再次打开 App 时按「错过的时间」补算一次 |
| 每日记忆整理 | `BGProcessingTask`（可要求充电/联网），外加设置页里的「现在整理一次」手动入口 |
| 主动消息通知 | 本地通知；锁屏不显示正文（与 Android 一致） |

这些限制在设置页有一句人话说明，不假装和 Android 一样。

## 已验证

- `swift test --disable-sandbox`：**810 条单测**全绿（域算法 / 数据 / 备份 / 网络 / 聊天 / 服务 / 工具 / 附件），
  其中 1.9 新增：并发闸、`defer_reply`、睡眠阈值、主动上限、备份 v2 往返 + v1 兼容、
  schema v8→v10 迁移（一人一段）、「消失的设置」源码守卫；
- 备份双向互通：`android-export-v2.json` ↔ `ios-export-v2.json`
  （Android 端 `BackupFixtureTest` 与 iOS 端 `BackupInteropTests` 互为验证；两份 v1 样本只读不写，用来钉住兼容性）；
  合并导入时「备份那段与本机那段同属一人」两端一致：并进本机那段，一句都不丢；
- 模拟器可编译、可安装、可启动，首页是联系人列表、能发消息；睡着时发消息只出现「已读」不回，
  `defer_reply` 端到端跑通（DB 里多一条 `REACH_OUT` 意向）。
