# iOS 性能基准：大库迁移 / 聊天记录检索 / 带图备份

> 本文件是 `docs/performance.md` 的 **iOS 章节（独立成篇）**，由 iOS 侧 benchmark 产出，
> 主线合并进 `docs/performance.md` 时直接取这里的表格与结论。
> Android 侧同口径数字见 `docs/performance.md`（另一条线在写，两边互不依赖）。
>
> 所有数字都能用文末命令重跑；原始结果同时写在
> `/tmp/ios-bench-<timestamp>-history.json`、`/tmp/ios-bench-<timestamp>-backup-media.json`。

测的是 **0.12.0 / `dev/0.12.0` 分支上的代码**：`Schema.version == 12`（v11 分块历史 +
v12 联网搜索服务列）、备份格式 **v4**（v3 图片 base64 + v4 `webToolsProvider`）。
注意 v12 / 备份 v4 / 媒体压缩这几件事正在同分支并行改动，本文数字对应 **2026-11-15 16:0x**
那一刻的工作区（导入侧的「重压缩 + 内容 hash 去重」已经生效，见 §4 的说明）。

---

## 1. 机器、工具链与测量边界

| 项 | 值 |
| --- | --- |
| 机型 | Mac17,3（Apple M5，10 核；`sysctl hw.model`） |
| 内存 | 16 GB |
| OS | macOS 27.0（build 26A428） |
| 工具链 | Xcode 27.1（build 27A9275）/ Swift 6.4，`swift-tools-version 5.9`（语言模式 5） |
| 构建配置 | **Debug `-Onone`**（`swift test` 默认；见 §7 误差说明） |
| 进程形态 | **macOS 主机进程**（`swift test`），非 iOS 模拟器、非 iOS 真机 |
| SQLite | 系统 SQLite 3.54.0（macOS 自带） |
| schema | v12（v10 → v12 的升级路径；主导耗时来自 v11 的分块回填） |
| 备份格式 | v4（500 张图走 v3 起的 `media[]` base64） |
| 备份预算（本机生效值） | 0.12 起按可用内存动态收紧：媒体 `min(128 MiB, 可用/12)`、读写 `min(256 MiB, 可用/8)`；16 GB Mac 上可用≈5.3 GB，仍取硬上限 **128 MiB / 256 MiB**，本文即「硬上限」场景 |
| 日期 | 2026-11-15 |

**边界**：这不是 iPhone 真机数字。内存上限（Jetsam）、CPU 频率、I/O 与真机不同；
Swift 代码在 Debug 下也明显更慢（SQL 部分不受影响）。真机结论见 §7。

---

## 2. 怎么跑

```bash
cd ios/YapKit

# 1) 10 万条消息：v10→v12 迁移 + 检索 1/10/100 页（约 4–6 分钟，独占机器时）
TMPDIR=$PWD/../.build/tmp CLANG_MODULE_CACHE_PATH=$PWD/../.build/clang-cache \
  SWIFT_MODULE_CACHE_PATH=$PWD/../.build/swift-cache \
  RUN_BENCHMARKS=1 swift test --disable-sandbox --filter LargeHistoryBenchmarkTests

# 2) 500 张图的备份导出/导入（约 40 秒）
TMPDIR=$PWD/../.build/tmp CLANG_MODULE_CACHE_PATH=$PWD/../.build/clang-cache \
  SWIFT_MODULE_CACHE_PATH=$PWD/../.build/swift-cache \
  RUN_BENCHMARKS=1 swift test --disable-sandbox --filter BackupMediaBenchmarkTests
```

- **默认跳过**：不设 `RUN_BENCHMARKS=1` 时两条用例 `XCTSkip`，常规套件只多 0.001 s
  （`swift test --disable-sandbox` 全绿，2 skipped / 0 failures）。
- 冒烟（几十秒）：`BENCH_MESSAGE_COUNT=40000 BENCH_REPEATS=2` /
  `BENCH_IMAGE_COUNT=20 BENCH_EXPORT_REPEATS=1 BENCH_IMPORT_REPEATS=1`。
- 结果文件：`/tmp/ios-bench-<epoch-ms>-history.json`、
  `/tmp/ios-bench-<epoch-ms>-backup-media.json`（本次报告用的是
  `ios-bench-1794730171282-history.json` 与 `ios-bench-1794730309662-backup-media.json`）。
- 新增文件：`Tests/YapKitTests/Benchmarks/{BenchmarkSupport,LargeHistoryBenchmarkTests,BackupMediaBenchmarkTests}.swift`。
  生产代码零改动。

---

## 3. 数据集与测量方法

### 3.1 10 万条消息（迁移 + 检索）

| 项 | 值 |
| --- | --- |
| 消息数 | 100,000（单会话、单人格；`BENCH_MESSAGE_COUNT` 可覆盖） |
| `createdAt` | 固定基准 `1_794_500_000_000` + `i × 1000`（严格递增） |
| 正文 | 中英混排；每 10 条有 1 条含「约定」和「harbin」（→ 10,000 条命中 / 334 页），其余为普通中文短句 |
| v10 形状 | 先按当前 schema 建库，再 `DROP TABLE message_blocks`、`DROP INDEX index_messages_sessionId_blockIndex_createdAt`、`ALTER TABLE messages DROP COLUMN blockIndex`、`PRAGMA user_version=10`（与 `SchemaMigrationTests` 同一手法，走的是**真实**升级路径） |
| 插入 | 10 万行在**单个事务**里用同一条 prepared statement 写完 |

**迁移计时**：`DatabaseStores.open(path:)` 的整段墙钟时间（含 `PRAGMA journal_mode=WAL`、
`Schema.create` 的建表事务、`migrateLegacyData` 的补列/回填/建块行）。这就是 App 启动时真实发生的事。

**检索计时**：真实 `MessageStore.search(query:limit:cursor:)`：
- 每页 30 条（`MessageStore.defaultSearchPageSize`），第 1 / 10 / 100 页各重复 **5 次**，
  取 median / min / max；每次都是完整一次检索（逐区块枚举 + 每块内存精排 + 键集游标 + 无候选上限）；
- 页游标：第 N 页 = 「排序后第 `30×(N-1)` 条命中之后」的位置。逐页翻 99 次与一次
  `limit: 30×99` 得到**同一个游标**，但前者在 Debug 下要多扫 99 遍 10 万行，所以游标用
  一次性检索构造、**不计入页耗时**；页耗时本身仍是真实的 `limit: 30, cursor:` 检索；
- 每次测量前用同一游标预热一次；`MessageStore.queryObserver` 统计「扫了多少块 / 读了多少行」；
- 命中总数按 `SearchRanking.isHit` 全量判定（与 `search` 同一套规则），并用 SQL `LIKE` 交叉验证。

**内存**：`task_info(MACH_TASK_BASIC_INFO).resident_size` + `task_info(TASK_VM_INFO).phys_footprint`。
前后基线 = `malloc_zone_pressure_relief(nil, 0)` 后取 3 次最小值；操作期间由 5 ms 采样线程记录峰值。
`phys_footprint` 是 Jetsam 口径，判断「会不会被杀」看它。

### 3.2 500 张图（备份导出 / 导入）

| 项 | 值 |
| --- | --- |
| 图片 | 500 张真实 JPEG：250 张噪点 800×600（quality 0.5，约 301 KB）+ 250 张纯色 1024×768（quality 0.8，约 13 KB） |
| 原始字节 | 78,451,229 B（约 74.8 MiB；单张 13 KB–301 KB，均值 157 KB） |
| 消息 | 500 条，每条 1 张附件（`attachmentsJson`，绝对 `file://` 路径） |
| 导出 | 生产入口 `BackupService.exportWithWarnings()`，重复 3 次 |
| 导入 | 生产入口 `BackupService.importBackup(text:mode:.replace)`，每次一个全新库 + 空附件目录，重复 2 次 |
| 内存 | 与 §3.1 同一套 `task_info` + 5 ms 采样 |

---

## 4. 数字

### 4.1 10 万条消息：v10 → v12 迁移

三次「安静机器」运行 + 一次与其它 `swift test` 并发的运行：

| 指标 | 安静（3 次） | 并发时（1 次，仅作上界） |
| --- | --- | ---: |
| 迁移耗时（`DatabaseStores.open`） | **110.4 / 116.3 / 145.5 s**（median **116.3 s**） | 373.4 s |
| 10 万行插入（单事务） | 0.38 / 0.48 / 0.39 s | 0.80 s |
| 迁移后消息数 | 100,000（3 次一致） | 100,000 |
| 迁移后块数 | 500（每块 200，3 次一致） | 500 |
| 抽样块 | block 0 / 250 / 499：各 200 条，首尾 id 与 `createdAt` 都正确 | 同左 |
| 块表自洽审计 | 0 问题 | 0 问题 |
| 库体积（v10 → 迁移后） | 27.4 MB → 30.95 MB（+3.46 MB，+12.6%） | 同左 |
| footprint（迁移前 → 后） | 7.4→18.1 / 7.5→18.1 / 7.5→18.0 MB | 7.6 → 18.2 MB |
| 迁移期间峰值增量（footprint） | 31.5 / 31.5 / 31.4 MB | 31.4 MB |
| 迁移期间峰值增量（resident） | 30.6 / 29.0 / 27.3 MB | 16.8 MB |
| `user_version` | 12 | 12 |

**结论：迁移本身不占内存（峰增 30 MB 量级），但耗时不可接受：10 万条约 2 分钟。**
耗时几乎全部来自 v11 的块号回填，而不是 v12 的补列（v12 只 `ADD COLUMN webToolsProvider`）。

**根因（用 sqlite3 CLI 在同一份 10 万行库上逐条复现）**：

| 回填步骤 | 耗时 |
| --- | ---: |
| `CREATE TEMP TABLE message_block_assignment AS SELECT … ROW_NUMBER() OVER (…)` | 0.057 s |
| `UPDATE messages SET blockIndex = (SELECT a.blockIndex FROM message_block_assignment a WHERE a.messageId = messages.id)` | **123.5 s** |
| `DELETE FROM message_blocks` | 0.001 s |
| `INSERT INTO message_blocks … GROUP BY sessionId, blockIndex`（含首尾 id 子查询） | 2.57 s |
| 合计 | 约 126 s（与基准里的 116–145 s 同一量级） |

那条 `UPDATE` 在**没有索引**的 TEMP 表上做相关子查询：10 万行 × 10 万行 ≈ 10¹⁰ 次比较，是 O(n²)。
验证修复：在 TEMP 表上补 `CREATE INDEX temp.index_message_block_assignment_messageId ON
message_block_assignment (messageId)` 后，同一条 `UPDATE` **0.0085 s**，整段回填 0.13 s，
结果完全一致（500 块 / 100,000 条 / 无孤儿）。**建议**：`Schema.backfillMessageBlocks` 在
`CREATE TEMP TABLE` 之后加这行索引（或改成 `UPDATE … FROM`）；预期把 10 万条的升级从 ~2 分钟
压到秒级（剩余部分是 `createStatements` 与 WAL 开销）。

### 4.2 10 万条消息：检索第 1 / 10 / 100 页

主查询「约定」，命中总数 **10,000**（SQL `LIKE` 交叉验证同为 10,000；次查询「约定 harbin」
同为 10,000）。每页 30 条、`hasMore == true`、都带下一页游标。

| 页 | median | min | max | 命中 | 扫描代价（每次检索） |
| ---: | ---: | ---: | ---: | ---: | --- |
| 第 1 页 | **4,270 ms** | 4,253 ms | 4,311 ms | 30 | 500 块 / 100,000 行 |
| 第 10 页 | **4,339 ms** | 4,289 ms | 4,417 ms | 30 | 500 块 / 100,000 行 |
| 第 100 页 | **4,355 ms** | 4,328 ms | 4,428 ms | 30 | 500 块 / 100,000 行 |
| 次查询第 1 页（中英多词元） | 5,934 ms | 5,868 ms | 5,971 ms | 30 | 500 块 / 100,000 行 |

另外两次安静运行（同代码、同机器）第 1 / 10 / 100 页 median 分别为
**4,623 / 4,576 / 5,346 ms** 与 **6,896 / 6,165 / 5,988 ms**；并发运行时第 1 页 median
曾到 29,056 ms（见 §7）。三次安静运行的量级一致：**每页 ≈ 4.3–6.9 s**。

**结论**：
- **页号几乎不影响成本**（第 1 页和第 100 页一样贵）：`search` 无候选上限，每页都要把
  全部块（500 个）读一遍、把 10 万条正文精排一遍。深翻页在 UI 上不会更慢，但每一页都慢。
- 命中总数统计（`SearchRanking.isHit` 扫 10 万条）单次 7.7 s（另两次 8.0 / 12.6 s），**别把它放进 UI 路径**。
- 这些是 **Debug `-Onone`** 数字；Release 下 Swift 精排部分会快数倍（见 §7），需要用
  `swift test -c release` 再测一轮才能用于「交互是否够快」的最终判断。

### 4.3 500 张图：备份导出 / 导入

| 指标 | 值 |
| --- | --- |
| 原始图片 | 78,451,229 B（74.8 MiB） |
| 备份 JSON | **104,847,991 B（100.0 MiB）＝原始的 1.34×**（base64 理论值 4/3） |
| `media[]` 条目 | 500（全部 `CHAT_IMAGE`），`bytes` 合计 = 78,451,229（与原始一致，导出未重压缩） |
| 导出耗时 | median **8,013 ms**（min 7,958 / max 8,228，3 次） |
| 导出峰值内存（footprint） | 峰值绝对值 **517.2 MB**；相对首次基线增量 **404.8 MB** |
| 导入耗时 | median **7,543 ms**（min 7,442 / max 7,645，2 次） |
| 导入落盘 | 459 个文件 / 77,903,396 B（原始的 **0.99×**；41 张内容相同的纯色图被去重合并） |
| 导入峰值内存（footprint） | 峰值绝对值 **739 MB（第一次）/ 845 MB（第二次）**；相对基线增量 232 MB |
| 导入正确性 | 500 条消息全部回来；500 条消息的附件全部指向**真实存在**的 JPEG 文件；`CHAT_IMAGE` 全部落盘；唯一 warning 是「1 个 API 配置还缺 Key」（预期） |

**结论**：
- 体积：base64 把 78.5 MB 变 104.9 MB（1.34×）；128 MiB 媒体上限对应的 JSON 上限约 171 MiB，
  再叠加文字/记忆，导出 → 导入两端都在 **256 MiB 总闸**附近，没有余量。
- 内存：导出峰值 ≈ **JSON 的 5 倍**（517 MB），导入峰值 ≈ **JSON 的 8–10 倍**（0.85–1.09 GB，跨运行）。
  导出路径上同时存在「原始字节 + base64 字符串 + JSON 的 Data + JSON 的 String」；
  导入路径上还多一份 `JSONValue` 语法树 + `BackupBundle` 的字符串拷贝。
- **导入的约 1 GB 峰值对 3 GB 内存的 iPhone 是真实风险**（见 §7）；这是「备份时被系统杀掉」的候选主因。
- 导入侧 0.12 新行为：`BackupMediaRestorer` 会重压缩（JPEG 0.85、聊天图长边 ≤1600）并按
  「落盘内容的 SHA-256 前 64 bit」去重，**且「压缩后更大就保留原图」**（这条正是本基准上一轮
  发现 301 KB→547 KB 负优化后补上的）。因此本次落盘 77.9 MB（0.99× 原始），不再是 1.78×；
  41 张颜色只差一两个色阶的纯色图被内容去重合并成一个文件，500 条消息的附件引用全部有效。
  > 注意：写作时该「保留原图」策略**仍在与「透明 PNG 必须垫白 + 统一 JPEG」的既有契约协调**
  > （已在 16:33 收敛为「只对真 JPEG、且尺寸/字节已在上限内的保留原图」，
  > 全量套件已恢复 1305 / 0 failures；PNG/WebP 仍重压垫白，属既有契约）。

---

## 5. 结论与建议（按优先级）

1. **P0 — v10→v11 块回填是 O(n²)，10 万条升级 ~2 分钟。**
   `Schema.backfillMessageBlocks` 的 `UPDATE … WHERE a.messageId = messages.id` 在无索引 TEMP 表上
   全表扫描（已用 sqlite3 CLI 复现：同一条 UPDATE 123.5 s）。加一行
   `CREATE INDEX temp.… ON message_block_assignment (messageId)` 后 0.0085 s、结果不变。
   这是「老用户升级后卡在启动页」的直接原因，改动小、风险低，建议在 0.12 发布前修掉。
2. **P1 — 检索每页都全量扫 10 万条（Debug ≈ 4.3–6.9 s/页，与页号无关）。**
   如果目标是「搜一下立刻出结果」，当前实现（无候选上限 + 每块全量精排）在 10 万条规模下
   不满足交互要求。先补一轮 Release 数字；若仍 >1 s，再考虑：按块缓存命中计数/排序前缀、
   把「无上限」改成「先给够一页就停」的可证明上界，或引入倒排索引（维护成本见 `MessageStore.search` 注释）。
   命中总数统计绝不能放 UI 路径（单次 8–12 s）。
3. **P1 — 带图备份的峰值内存远大于文件体积**（导出 518 MB / 0.10 GiB JSON；导入 1.0 GB）。
   代价来自「整份 JSON 一次性在内存里」（base64 字符串 + Data + String + JSONValue 树）。
   建议：导出/导入改成**流式**（逐 `media[]` 条目 base64 编码/解码并立刻落盘），
   至少对大备份走分块；否则 3 GB 机型上 500 张图就可能被 Jetsam 杀掉。
4. **P2 — 内存预算已按可用内存收紧，但仍只限「体积」不限「峰值」**：0.12 起媒体预算
   `min(128 MiB, 可用/12)`、读写预算 `min(256 MiB, 可用/8)`；3 GB 机型（可用≈1 GB）媒体上限≈85 MB、
   读写上限≈128 MB。500 张图（78.5 MB / JSON 104.9 MB）仍能通过这两道闸，但实测导入峰值 0.85–1.09 GB
   ≈ 可用内存上限——低内存机型上仍可能被 Jetsam。建议再压导入峰值（流式解析/落盘）或把低内存机型的
   导入闸再收紧一档。
5. **P2 — 口径**：本文是 Debug/macOS 主机进程；决策前补 Release（`swift test -c release`）
   与至少一台真机（3 GB 内存的 iPhone）的数字。Android 侧对比时同样要注明构建配置。

---

## 6. 复现与原始数据

- 两条用例在 `Tests/YapKitTests/Benchmarks/`：
  - `LargeHistoryBenchmarkTests.testV10ToV11MigrationAndSearchPagingOn100kMessages`
  - `BackupMediaBenchmarkTests.testBackupWith500ImagesExportImportMemoryAndSize`
- 公共底座 `BenchmarkSupport.swift`：开关（`RUN_BENCHMARKS=1`）、单调时钟、`PeakMemorySampler`
  （5 ms）、`task_info` 两种口径、环境快照、JSON 落盘。
- 所有数字同时打印到测试输出（`IOS-BENCH   …` 行）并写 JSON；JSON 里包含环境、方法学、
  每次运行的样本与内存快照。
- 本文引用的原始结果：
  - `/tmp/ios-bench-1794730915009-history.json`（安静 #1：迁移 110.4 s、检索 4.27/4.34/4.36 s）
  - `/tmp/ios-bench-1794730171282-history.json`（安静 #2：迁移 116.3 s、检索 4.62/4.58/5.35 s）
  - `/tmp/ios-bench-1794729725716-history.json`（安静 #3：迁移 145.5 s、检索 6.9/6.2/6.0 s）
  - `/tmp/ios-bench-1794728696837-history.json`（并发时：迁移 373.4 s、第 1 页 29.1 s）
  - `/tmp/ios-bench-1794731856669-backup-media.json`（500 图最终结果；导入已按「不放大」保留原图）

---

## 7. 误差说明与风险（诚实清单）

**误差来源**
- **并发干扰最大**：同一台机器上还有别的 agent 在跑 `swift test` / 构建。迁移 110 s ↔ 373 s、
  第 1 页 4.3 s ↔ 29.1 s 的差异主要来自这里。表里优先用「安静机器」的两次运行，
  并把并发那次作为上界列出。**要拿稳定数字请独占机器**。
- **Debug `-Onone`**：检索/JSON/图片编解码是 Swift 热路径，Release 会快数倍；SQL（迁移的主要部分）
  在 SQLite C 层，构建配置基本不影响。所以「迁移 2 分钟」在 Release 下仍然成立，
  而「检索 5 秒」在 Release 下会显著变小——**不要拿 Debug 的检索数字直接下产品结论**。
- **内存基线漂移**：分配器高水位不会立刻还给系统，重复运行时「峰值增量」会越来越小
  （本次导出三次的增量：405 → 102 → 1.6 MB），所以表里同时给**峰值绝对值**。
  `malloc_zone_pressure_relief` 只能缓解，不能消除碎片。
- **采样粒度**：峰值由 5 ms 采样线程记录，毫秒级尖峰可能漏掉（本报告的量级都在秒级，影响小）。
- **autorelease/ARC**：导出/导入中间对象（JSON Data/String、CFData 等）在操作结束前不会 drain，
  峰值偏保守（偏高）——对「会不会被 Jetsam 杀」的判断来说，这个方向是安全的。
- **迁移耗时构成**：包含 `PRAGMA journal_mode=WAL`、全部 `createStatements`（幂等）与 v12 的
  `ADD COLUMN`（毫秒级）。块回填是绝对大头：SQL 拆分里 backfill 约 126 s，其中那条 `UPDATE`
  123.5 s（见 §4.1），其余步骤是秒级及以下。
- **图片内容的随机性**：噪点图用 `arc4random_buf` 生成，单张体积 ±1%，对总量与内存量级无影响。

**macOS vs iOS 真机的差异（本文数字不能直接当 iPhone 数字）**
- **内存上限**：iOS 单进程可用内存由 Jetsam 决定，3 GB 机型通常 ~1.2–1.5 GB、后台更少；
  本次导入峰值 0.89–1.04 GB（macOS 上不会杀，iPhone 上有概率）。导出 0.52 GB 相对安全但也不宽裕。
- **CPU**：M5 单核远强于 iPhone；迁移是单线程 SQL，真机可能比这里慢（也可能因 I/O 更快而接近）。
- **I/O**：macOS APFS 与 iPhone NVMe 不同；WAL/临时表（TEMP 落盘）行为受 `temp_store` 影响。
- **模拟器**：本次**没有**用模拟器。模拟器内存/CPU 与真机差异更大，且不触发 Jetsam，不能用来判断内存。
- **SQLite 版本**：macOS 3.54 vs iOS 系统自带版本，窗口函数执行计划可能略有差异（结论 O(n²) 不变）。
- **预算动态化**：媒体/读写预算按 `os_proc_available_memory()` 收紧（macOS 退回物理内存 1/3）。
  16 GB Mac 上取的是硬上限（128/256 MiB），所以本文的 500 图数字是「最宽松」档；3 GB iPhone 上
  媒体上限≈85 MB、读写上限≈128 MB，会先被预算挡下一部分图片（导出时按张数报 warning），
  但**预算不改变内存峰值**——通过闸的 105 MB JSON 仍会走到约 1 GB 峰值。

**其它风险**
- v12 / 备份 v4 / 媒体压缩仍在并行改动：本基准测的是改动后的工作区（导入重压缩 + 去重已生效），
  该线继续改数字会变；迁移结论只针对当前 `Schema.backfillMessageBlocks` 实现。
- 导入「458 个文件 ≠ 500 张图」是 0.12 的**内容去重**行为，不是丢图；500 条消息的附件都校验为
  真实存在的 JPEG。真实照片内容不同，去重通常不会触发。
- 备份 JSON 超过 256 MiB 会被导出闸拒绝；500 张图（105 MB JSON）已占 41% 的闸，图更多时先撞闸。
- 常规套件不受影响：不设 `RUN_BENCHMARKS=1` 时两条用例 0.001 s 内 skip（已验证：
  `Executed 2 tests, with 2 tests skipped and 0 failures`）。
