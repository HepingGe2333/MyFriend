# 性能基准：10 万条历史与 500 张图的备份

> 这份文档回答三个具体问题：**升级到 v11 区块存储时，10 万条历史要等多久**、
> **逐块检索翻到第 100 页还能不能用**、**备份 v3 带 500 张图在手机上会不会崩**。
> 所有数字都是在**真的跑起来的 App 上**测的（instrumented test），不是推算。
>
> 结论先说：**迁移与块存储没问题；检索在热门词上是秒级（4 秒左右）且瓶颈不在"扫块"而在"逐条打分"；
> 备份 v3 的整份 JSON 在 192MB 堆的设备上最多只能带走约 27MB 图片，500 × 800KB 根本走不完（预算截断 + OOM）。**

测量日期：2026-11-15（15:45–16:05 CST）。测量代码只新增在
`app/src/androidTest/java/com/yap/app/benchmark/`，**没有改任何生产代码**。

---

> **iOS 侧数字**：见 [`performance-ios.md`](performance-ios.md)（macOS Debug 主机进程、同口径 10 万条 / 检索分页 /
> 500 图备份）。两端结论一致：**迁移要加索引（iOS 已修）**、**备份要流式化**、**检索在超长历史下需要倒排索引或减少
> 评分行数**。
>
> 本文的 Android 数字采于 `0.12.0-debug`（`schema v12`）构建；之后只升了版本号与预算公式，测量对象代码未变。



## 1. 设备与构建

| 项 | 值 | 备注 |
| --- | --- | --- |
| 设备 | `sdk_gphone64_arm64`（Google / `ranchu`） | **headless 模拟器**（AVD `YapTest`，无窗口），不是真机 |
| 系统 | Android 15 / API 35 | `arm64-v8a` 系统镜像（宿主 macOS，10 核） |
| 内存 | 2.5 GB（`MemTotal` 2,531,992 kB） | 模拟器的 `dalvik.vm.heapgrowthlimit` = **192 MB**，`Runtime.maxMemory()` 也是 192 MB |
| 包 | `com.yap.app.debug`，`0.12.0-debug (18)` | **debug 包**，未开 R8/混淆 |
| Room schema | **v12**（`MIGRATION_1_2` … `MIGRATION_11_12` 全链注册；本次实测 `10 → 11 → 12`） | `schema.versionAfterOpen = 12` |
| 构建 | Gradle 8.9 / AGP + Kotlin（仓库自带 `.tooling`） | `:app:assembleDebug` + `:app:assembleDebugAndroidTest` |
| 宿主 | macOS，10 核 | 测量期间宿主是**共享**的，load 在 4–33 之间波动；这一点直接影响墙钟数字，见 §5 |

> 说明：同一构建内完成的测量，schema 版本以**本次构建**为准（v12）。
> 迁移链是从 v10 形状的库开始跑的，所以 `10 → 11`（块存储）这一档的耗时也包含在内。

---

## 2. 测量方法

### 2.1 10 万条历史的夹具（`LargeHistoryBenchmarkTest`）

- **表结构逐字来自 `app/schemas/.../10.json`**：`V10Schema.kt` 由 schema JSON 的 `createSql` 机械生成
  （17 张表 + 36 个索引），所以 `MIGRATION_10_11` 跑在「和用户手机上那一版一模一样」的 `messages` 表上；
  手抄 DDL 的抄漏风险被排除了。
- 灌 **100,000 条**消息，单会话、单人格，`1000 条 / 事务`（100 个事务）。
- 内容：中英混排的日常对话 + 技术词，平均 **56 字 / 117 UTF-8 字节**，`createdAt` 严格递增（1 秒一条，跨约 27.8 小时）。
- 关键词按**固定比例**埋进去，这样「命中数」这一列可以当作「同一份语料下不同查询的代价」来读：
  - `晚安` 每 5 条必现 → **20,000 条命中**（单词元，SQL 里只有 1 个 `LIKE`）；
  - `项目` 每 8 条、`压力` 每 7 条 → 合计 **25,000 条命中**（双词元，SQL 里 3 个 `LIKE`）；
  - `体检报告` 每 350 条 → **286 条命中**（冷门词）；
  - `量子力学导论` → **0 条命中**（用来量「扫完 500 块但一条都不打分」的底噪）。

### 2.2 迁移怎么量（两种口径）

| 口径 | 做法 | 它代表什么 |
| --- | --- | --- |
| **Room 打开（主口径）** | `Room.databaseBuilder(...)` + `openHelper.writableDatabase.version` 包在计时区间内 | **用户升级时真实等待的那一次**：跑迁移 + Room 的 `onValidateSchema` |
| 纯 SQL（对照） | 把 v10 库文件复制一份，用裸 `FrameworkSQLiteOpenHelperFactory` 打开，直接调 `MIGRATION_10_11.migrate(db)` | 只有迁移 SQL 本身；但副本的页是**冷的**且不是 WAL，所以偏慢，作为上界 |

内存：迁移前后各做 4 轮 `System.gc()` + 60ms 间隔，读 `Runtime` 已用堆与 `Debug.MemoryInfo` 的 PSS。

> 诚实说明：迁移**过程中**没有做峰值采样（只记了前后 GC 后读数）。判断「流式回填成立」的依据是
> 「Java 堆前后增量 0.16MB、PSS 增量不到 1MB」——如果它把整段历史读成了一个 `List`，10 万条 × 百余字节
> 至少是十几 MB 的增量，不可能看不见。

### 2.3 检索怎么量

走**生产代码**：`BlockSearch.page`（逐块扫描 + 键集游标）+ `MessageSearchSql`（两条 SQL）+ 真实 Room DAO
（`messageBlockDao.searchBlocks` / `messageDao.searchBlockMessages`）。
唯一复制的是 `ChatRepository.blockScanSource` 那 15 行匿名实现——它是 `private`，而把 `ChatRepository`
的 25 个构造依赖在 instrumented test 里拼出来跟被测的东西无关（报告里标注了这一点）。

- 每页 30 条，**每页重复 5 次取中位数**，同时给 min/max（同一进程内）；另有 **3 次独立进程运行**给出跨运行范围；
- 第 N 页的游标不是「真翻 99 页」得到，而是先做一次**全量命中扫描**，按 `BlockSearch.compare` 排序后取第 `30(N-1)` 条——
  排序键 `(matchedTermCount, relevance, createdAt, id)` 唯一确定顺序，所以这等价于真的翻过去，但少扫 99 遍；
- 每一页都断言「内容 == 全量排序后的那一段」，防止量到一个语义坏掉的实现；
- 每条测量同时记录**墙钟**与**进程 CPU 时间**（`android.os.Process.getElapsedCpuTime()`）。
  宿主被抢占时墙钟会成倍膨胀（见 §5），CPU 口径用来还原「这段活本身有多重」。

### 2.4 备份怎么量（`BackupMediaBenchmarkTest`）

同样走**生产代码**：`MediaStorage.ownedFile`（目录归属守卫）→ `BackupMediaPacker.pack`（读文件 + base64）→
`MessageEntity.toBackupMessage`（附件引用换裸文件名）→ `BackupCodec.encode`（真 JSON 编码）→ 写文件；
导入侧 `BackupCodec.decode` + `BackupMediaRestorer.restore`（真落盘）。
**没有构造整个 `BackupRepository`**（它要 20 多个依赖），`export()` 里属于「读库」的几步用真实 DAO 按同样顺序做了一遍，
差异是常量级开销。

- 500 条消息 + **500 张 JPEG**（噪点图，可压缩性最差，用来当「压不小的图」的上界；每档 3 种质量轮换）；
- 梯度：0 张（控制组）→ 目标 32KB → 64KB → 128KB → 250KB → 800KB（题目要求的 500 × ≤800KB 在最后一档）；
- 峰值内存：后台线程每 5ms 采样一次 `Runtime.totalMemory - freeMemory`，取最高点（**采样值**，不是精确峰值）；
  另记 GC 后的 `Runtime` 已用堆、`Debug.MemoryInfo` 的 totalPss / dalvikPss / nativePss；
- JSON 体积给三种口径：字符数、UTF-8 字节数（写盘后 `File.length()` 核对）、**字符串在 Java 堆上的字节数**
  （Java 9+ 紧凑字符串：出现任何非 Latin-1 字符就退回 UTF-16，即 2 字节/字符）；
- 每档结束清空媒体文件、删库、GC；**每档跑完就落一次盘**，某一档 OOM 不会毁掉前面的结果。

### 2.5 可重复命令

```bash
cd /Users/hpg2333/Desktop/Projects/YetAnotherPal
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home \
  ANDROID_HOME=$PWD/.tooling/android-sdk ANDROID_USER_HOME=$PWD/.tooling/android-user \
  GRADLE_USER_HOME=$PWD/.tooling/build-home TMPDIR=$PWD/.tooling/tmp

# 1) 构建（benchmark 测试在 androidTest 源集里，随 debug 包一起编译）
.tooling/gradle-8.9/bin/gradle -p . :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

**标准跑法**（需要 `bench=true`，否则测试自己 `Assume` 跳过——它是分钟级测量，不该拖慢别人的 androidTest）：

```bash
.tooling/gradle-8.9/bin/gradle -p . :app:connectedDebugAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yap.app.benchmark.LargeHistoryBenchmarkTest \
  -Pandroid.testInstrumentationRunnerArguments.bench=true

.tooling/gradle-8.9/bin/gradle -p . :app:connectedDebugAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yap.app.benchmark.BackupMediaBenchmarkTest \
  -Pandroid.testInstrumentationRunnerArguments.bench=true
```

`--tests "*LargeHistoryBenchmarkTest*"` 只对单元测试有效；instrumentation 要用上面的
`-Pandroid.testInstrumentationRunnerArguments.class=...`（`bench=true` 是同名参数的第二个键）。

**本次实际用的绕法**：测量窗口内有并行的 UI 测试改动引入了 `HiltTestRunner` 回归——安装后的
`MY_PACKAGE_REPLACED` 广播会让 `@AndroidEntryPoint` 的 `BootReceiver` 在 Hilt 组件就绪前被拉起，
把目标进程带崩（`connectedDebugAndroidTest` 直接 "Starting 0 tests / Process crashed"）。
绕过方式（不改任何别人的文件）：

```bash
A=.tooling/android-sdk/platform-tools/adb
$A install -r app/build/outputs/apk/debug/app-debug.apk
$A install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
sleep 2
$A shell pm disable com.yap.app.debug/com.yap.app.service.BootReceiver
$A shell am instrument -w -r \
  -e class com.yap.app.benchmark.LargeHistoryBenchmarkTest -e bench true \
  com.yap.app.debug.test/com.yap.app.HiltTestRunner
```

结果文件在 App 的外部私有目录里（pull 不需要 `run-as`）：

```bash
$A pull /sdcard/Android/data/com.yap.app.debug/files/bench-large-history.json /tmp/
$A pull /sdcard/Android/data/com.yap.app.debug/files/bench-backup-media.json /tmp/
# 同时在 logcat 里逐行可见：adb logcat -s YapBench
```

**v10 表结构生成**（`V10Schema.kt` 的内容由它生成，改 schema 历史时才需要重跑）：

```bash
python3 - <<'PY'
import json
d = json.load(open('app/schemas/com.yap.app.data.local.YapDatabase/10.json'))['database']
for e in d['entities']:
    print(e['createSql'].replace('${TABLE_NAME}', e['tableName']))
    for i in e.get('indices', []):
        print(i['createSql'].replace('${TABLE_NAME}', e['tableName']))
PY
```

### 2.6 结果文件里都有什么

`bench-*.json` 是扁平的 `key = value` 对，字段名自解释（`search.hot-single.page100.medianMs`、
`profile.500x800KB.export.peakDeltaMb` …）。两处容易误读：

- `*.cpu.medianMs`：**进程** CPU 时间（所有线程，含 Room 的查询线程），不是调用线程的时间；
- `*.jsonStringHeapBytes`：这份 JSON 字符串在堆上占多少（UTF-16 时是字符数 × 2），**不是**文件大小。

---

## 3. 结果

### 3.1 100,000 条：插入与迁移

| 项 | 值（三次独立进程运行） | 说明 |
| --- | --- | --- |
| 夹具插入 100,000 条 | **2.26 s / 2.40 s / 2.48 s**（40.4k–44.2k 行/秒） | 100 个事务 × 1000 条，`INSERT` 直写 `messages` |
| v10 库体积 | 32.2 MB | 100k 行文本（平均 117 B/条） |
| 迁移后库体积 | **39.0 MB**（+21%） | `blockIndex` 列 + `message_blocks`（500 行）+ 两个索引 |
| **Room 打开（迁移 + 表校验，用户真实等待）** | **2.53 s / 2.82 s** | 从 v10 升到 v12（`10→11` 回填 + `11→12` 加一列）。第一次运行的计时位置写错（只量到 Room 对象构造，7 ms），已作废、不计入 |
| 纯迁移 SQL（冷副本、非 WAL，上界） | 4.51 s / 5.27 s / 5.51 s | 多出来的是冷页缓存与 journal 模式差异，不代表用户体验 |
| 迁移期间 Java 堆增量（GC 后） | **+0.16 MB** | 流式回填成立：没有把 10 万条读成 `List` |
| 迁移期间 PSS 增量 | +0.93 ~ +0.99 MB | 同上；SQLite 页缓存本身就占大头（见 §3.5 的 totalPss 基线） |

### 3.2 迁移后的块分布（正确性）

| 项 | 值 |
| --- | --- |
| `messages` 行数 | 100,000（一条不少） |
| `message_blocks` 行数 | **500** |
| `MAX(blockIndex)` | 499 |
| 每块条数 | min = max = avg = **200**（`200条×500块`） |
| 抽样的块边界 | `#0: m000000..m000199`、`#1: m000200..m000399`、`#2: m000400..m000599`、`#498: m099600..m099799`、`#499: m099800..m099999` |
| 断言 | 首条 `blockIndex=0`，末条 `blockIndex=499`；检索的每一页内容与全量排序切片逐条相等 |

### 3.3 检索：第 1 / 10 / 100 页（热门词，20,000 条命中，`晚安`）

每页 30 条；同一进程内 5 次取中位；三次独立进程运行。

| 页码 | 命中数（`totalMatches`） | run1 min / **median** / max | run2 min / **median** / max | run3 min / **median** / max | **三次 median 的中位数** | 进程 CPU median（run1/2/3） | 扫描块 | `hasMore` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 第 1 页 | 20,000 | 3,339 / **3,506** / 3,585 ms | 4,619 / **5,273** / 5,642 ms | 4,167 / **4,289** / 4,634 ms | **4,289 ms** | 3,983 / 5,855 / 4,855 ms | 500 | true |
| 第 10 页 | 20,000 | 3,271 / **3,499** / 4,482 ms | 4,493 / **5,648** / 6,479 ms | 3,783 / **4,435** / 4,866 ms | **4,435 ms** | 3,997 / 6,368 / 5,006 ms | 500 | true |
| 第 100 页 | 20,000 | 3,607 / **4,193** / 6,168 ms | 5,742 / **5,912** / 6,842 ms | 3,455 / **3,811** / 4,385 ms | **4,193 ms** | 4,742 / 6,702 / 4,337 ms | 500 | true |

（每个 run 内部是同一进程的 5 次重复；三次 run 是三个独立进程，分别在宿主 load≈7 / ≈10 / ≈5 时跑。）

**读法**：`scannedBlocks` 恒为 500、`totalMatches` 恒为 20,000 —— 每一页都要重新走完 500 块、并把 20,000 条命中
全部打分一遍，所以**页码深浅几乎不影响耗时**（差异在噪声范围内）。这既是「结果完整、不重复、无候选上限」的代价，
也是它和 offset 分页最大的不同。

### 3.4 检索成本拆解（为什么慢）

| 场景 | 命中数 | 耗时（三次运行的中位） | 其中「扫 500 块」 | 其中「逐条打分」 |
| --- | --- | --- | --- | --- |
| 只枚举块（1 条 SQL） | – | 0.83 ms | 0.83 ms | – |
| 只读 1 块（1 条 SQL） | 200 行 | 0.51–0.94 ms | ≈ 500 块共 **0.26–0.47 s** | – |
| 零命中词（`量子力学导论`，全量扫描） | 0 | **0.38 s**（0.56 / 0.34 / 0.38） | 0.38 s | 0 |
| 冷门词（`体检报告`） | 286 | **0.37 s**（0.50 / 0.33 / 0.37） | ≈ 0.38 s | ≈ 0（286 条几乎免费） |
| 热门单词元（`晚安`） | 20,000 | **4.29 s**（3.51 / 5.27 / 4.29） | ≈ 0.4 s | **≈ 3.9 s（约 195 µs/条）** |
| 热门双词元（`项目 压力`，3 个 `LIKE`） | 25,000 | **9.61 s**（9.61 / 9.73 / 8.08） | ≈ 1.0–1.5 s（3 个 `LIKE`） | ≈ 8 s（2.5 万条 × 更贵的打分） |

结论很清楚：**慢的不是"逐块扫描"（0.4 秒），而是"对全部命中逐条算 relevance"（4 秒）+ "必须给出精确总数"这条产品契约。**
`ChineseTerms.relevance` 对每条正文要做归一化、二元组切分、两个 `Map` 与余弦——单条约 0.2 毫秒，乘以两万就是四秒。

### 3.5 备份 500 张图（192 MB 堆的模拟器上）

媒体上限是 `MAX_MEDIA_BYTES = 128 MiB`、单张 8 MiB（代码里的常量）。下表是实际测量：

| 档 | 图片原始字节 | 打包进备份 / 因预算跳过 | JSON 体积（UTF-8） | JSON 字符串占堆 | 导出耗时 | 导出峰值（Java 堆，含 2.4 MB 基线） | 结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 控制组：500 条消息、0 张图 | 0 | 0 / 0 | 138.6 KB | 0.24 MB | 51 ms | 4.0 MB（+1.5） | ✅ |
| 500 × 29 KB | 14.5 MB | 500 / 0 | **19.6 MB**（×1.35） | 39.1 MB | 374 ms | **130.9 MB（+128.1）** | ✅ 导出 / ✅ 导入 |
| 500 × 54 KB | 27.0 MB | 500 / 0 | **36.2 MB**（×1.34） | 72.4 MB | 2,675 ms | **176.2 MB（+173.5）** | ✅ 导出 / ❌ 导入 OOM |
| 500 × 110 KB | 55.2 MB | 500 / 0 | 预计 ≈74.5 MB | – | – | **182.8 MB（+180.1）** | ❌ 导出 OOM（encode） |
| 500 × 210 KB | 105.2 MB | 500 / 0 | – | – | – | **189.6 MB（+186.9）** | ❌ 导出 OOM（encode） |
| **500 × 670 KB（题目要求：500 × ≤800 KB）** | **335.0 MB** | **200 / 300** | – | – | – | **190.4 MB（+187.7）** | ❌ **导出 OOM（encode）**：先被 128 MiB 预算截到 200 张，再在编码阶段 OOM |

导入（同一份 JSON 再读回来 + 媒体落盘）：

| 档 | 结果 | 导入耗时 | 导入峰值 | 落盘图片 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 控制组 | ✅ | 91 ms | +2.0 MB | 0 | – |
| 500 × 29 KB（19.6 MB JSON） | ✅ | **3.91 s** | **134.5 MB** | 500 | 峰值仍然贴着 192 MB 的天花板 |
| 500 × 54 KB（36.2 MB JSON） | ❌ | – | 150.1 MB | – | OOM 在 `read-file`：`File.readText()` 的 `StringBuilder` 需要一块 **134 MB** 的 char[] |

**这条链上最容易被忽略的放大因素**：JSON 里有中文正文 → Java 的紧凑字符串退回 **UTF-16** →
base64（本来 1 字节/字符）也跟着变成 2 字节/字符。于是「19.6 MB 的文件」在堆上要先占 39 MB 的字符串，
再叠加 `BackupMedia` 里已经存着的 base64 字符串（19 MB）与打包时的原始字节（14.5 MB），
峰值到 131 MB —— **大约是媒体原始字节的 9 倍**。

对用户意味着什么：在 192 MB 堆的设备上，**媒体超过约 27 MB（≈500 张 54 KB）就只能导出、导不回来**；
**超过约 55 MB 直接导不出去**。题目里的「500 × ≤800KB」不但走不完，而且**连上限都没走到**就被编码阶段打死了。

---

## 4. 结论与建议

### 4.1 加倒排索引：**现在不值得单独做；先降低"打分"成本，或放宽"精确总数"**

数字摆在这里：

- 「扫块」只占总耗时的 **10–15%**（0.4 s / 4.3 s）。倒排索引能省掉的正好是这一块；
- 真正的成本是**对 20,000 条命中逐条算 `ChineseTerms.relevance`**（≈3.9 s）——倒排索引**不会**减少命中数，
  所以它**不会**让第 1 页变快多少；
- 还要注意「`totalMatches` 必须精确」这条产品契约（界面显示「共 N 条」，没有候选上限）：
  它天然要求 O(命中数) 的工作量，任何索引都绕不开。

建议的优先级（从便宜到贵）：

1. **先优化打分（不改 schema）**：把"命中判定"与"相关度"拆开——SQL 侧用 `LIKE`/`CASE` 先算出每条命中的词元命中数
   （第一排序键），只对**能进入这一页 frontier 的少数行**调用 `ChineseTerms.relevance` 算精确值。
   按本次数据**估算**，热门词第 1 页应从 4.3 s 降到 **0.5–0.8 s**（≈扫描成本），这是投入最小、收益最大的一步。
   代价：需要证明与 `spec/search` 的排序等价（或在 spec 里显式把 relevance 定义为「只对同词元数分组内生效」）。
2. **产品侧放宽总数**：如果允许「≥3000 条」或"滑到第 N 页时按已扫部分估算"，热门词首屏可以做到百毫秒级，
   不需要任何 schema 变更。
3. **再谈索引**（schema v13）：只有在前两步之后仍不够（例如要求首屏 <300 ms 且要精确总数），
   才值得上 FTS5 / `message_terms` 持久化倒排 + 预计算文档词向量，把词元数与 coverage/cosine 下沉到 SQL。
   那时要连带处理：迁移期建索引（按本次 10 万条 2.2 s CPU 的量级估，可接受）、写入路径每块维护索引、
   以及 `spec/search` 跨端一致性。**单为"检索慢"上索引，收益对不起复杂度。**

### 4.2 流式备份：**必须做，不是说"优化"**

- 500 × 800 KB 在 192 MB 堆设备上**不可能成功**：先被 128 MiB 预算截到 200 张（用户会看到「300 张没带走」的提醒），
  再在 `BackupCodec.encode` 里 OOM。这不是极端配置——手机上 800 KB 的照片很常见。
- 就算降到「500 张 54 KB」这种小图，导出峰值也到 **176 MB / 192 MB**、导入直接 OOM。
- 根因是「整份 JSON 先进内存」+「base64 + UTF-16 双倍放大」：
  导出峰值 ≈ 媒体原始字节 × 6–9；导入还要再叠一份「读回来的字符串」。
- 具体做法（按收益排序）：
  1. **导出改成流式写**：`Json.encodeToStream(...)` 或手写 `media` 段，逐张 base64 → 写 `OutputStreamWriter`。
     峰值从「媒体 × 8」降到「单张图（≤8 MiB）+ 常量」。
  2. **导入改成流式读**：用 `BoundedTextRead` + `decodeFromStream`，媒体逐条解码落盘（现在 `readText()` 的
     `StringBuilder` 单块就要 134 MB）。
  3. **预算与堆对齐**：`MAX_MEDIA_BYTES = 128 MiB` 已经大于这台设备能承受的约 27 MB。
     建议把总量预算改成 `min(128 MiB, 可用堆 / 8)`，或者在流式改造之后（那时预算才真的有意义）保持 128 MiB。
  4. 顺带：`MAX_BACKUP_BYTES = 256 MiB` 的读入闸在 192 MB 堆上根本到不了——真正的闸是堆，不是这个常量。

### 4.3 维持现状：块存储 + 键集游标 + 流式回填迁移

这三样都不需要动，数据支持：

- **迁移**：100k 条、从 v10 到 v12，用户等待 **2.5–2.8 s**；Java 堆增量 **0.16 MB**（确认是流式回填，不随历史长度爆内存）；
- **块分布**：500 块 × 200 条，`blockIndex` 0..499，首尾块边界精确；
- **块存储本身**：一页 = 一块，读 1 块 0.5–0.9 ms，首屏定位与会话长度无关——这部分设计是赚的；
- **键集游标**：第 1 / 10 / 100 页耗时持平（差异在噪声内），说明翻页没有退化；结果完整、不重复。
- 唯一值得记一笔的：**第 100 页的耗时和总耗时一样**，因为每一页都重扫一遍。若将来要"边搜边翻"更顺，
  可以缓存一次扫描的「前 K 名」结果，但那是锦上添花，不是必要。

---

## 5. 误差、适用边界与风险（诚实清单）

1. **模拟器 ≠ 真机**。这是 headless emulator（`ranchu`，arm64 在 Apple Silicon 上原生跑）。
   真机（尤其低端机）单核性能可能更差、调度更保守；而**堆上限**真机常见 256–512 MB，
   所以备份的 OOM 阈值会相应上移（大致与可用堆线性）。
2. **宿主是共享的**。测量窗内宿主 load 在 4–33 之间波动，同一段检索在 load≈7 时中位 3.5 s、
   load≈28 时 5.9 s（**±40%**）。所以本文的墙钟一律给「三次运行的 min–max + 三次中位数」，
   并同时给**进程 CPU 口径**（它更接近"活本身的重量"，但也会受 GC 线程争抢影响）。
3. **JIT / 每次新进程**。每条测量都在一个全新进程里，第一遍含 JIT 预热；每页先跑 1 次预热再取 5 次样本。
   debug 包未开 R8，release 通常快 10–30%（未测，不作为结论依据）。
4. **GC 噪声**。热门词场景要把 20,000 条命中留在堆里（约 12 MB），GC 抖动直接落在样本上——
   第 100 页的 max（6.2 s）就是这种抖动。
5. **合成语料**。中文二元组 + 固定关键词比例；真实语料的长尾更重、热门词占比不同。
   热门词占比是决定耗时的最大变量（命中数 ≈ 打分成本），所以**别把 4.3 s 当成"任何搜索都 4 秒"**：
   冷门词是 0.37 s。
6. **备份的 OOM 是"预期结果"而不是测试缺陷**。测试捕获 `OutOfMemoryError` 后记录阶段、清理、进入下一档，
   进程未崩；每档的 `peakDeltaMb` 是 **5ms 采样峰值**，真实瞬时峰值可能更高。
7. **base64 膨胀两处**：文件层面 ×1.33–1.35（实测 14.5 MB → 19.6 MB、27.0 MB → 36.2 MB）；
   内存层面再 ×2（中文导致 UTF-16）。只算前者会严重低估内存。
8. **测量范围的取舍**：只测了单会话 10 万条的形状。多会话时 `blocksQuery` 返回的行数不变（块总数一样），
   但每次检索要跨会话 join；真实用户更常见的是"若干会话、总共几万条"，那会比本文的形状略好。
9. **本次绕过了 `connectedDebugAndroidTest`**（并行改动引入的 `HiltTestRunner` 回归，见 §2.5）。
   数字本身与跑法无关，但复验时建议按标准命令再跑一遍。

---

## 6. 附：文件与引用

| 位置 | 内容 |
| --- | --- |
| `app/src/androidTest/java/com/yap/app/benchmark/LargeHistoryBenchmarkTest.kt` | 100k 夹具、迁移、检索基准 |
| `app/src/androidTest/java/com/yap/app/benchmark/BackupMediaBenchmarkTest.kt` | 500 张图的导出/导入基准 |
| `app/src/androidTest/java/com/yap/app/benchmark/V10Schema.kt` | 由 `schemas/10.json` 机械生成的 v10 表结构（53 条 DDL） |
| `app/src/androidTest/java/com/yap/app/benchmark/BenchmarkSupport.kt` | 计时、内存读数、峰值采样、结果落盘 |
| `spec/messages-blocks/README.md` | 区块存储与「逐块扫描 + 无候选上限」的契约（本文的被测对象） |
| `spec/search/README.md` | 检索排序/过滤规则（relevance 与 totalMatches 的来源） |
| `app/src/main/java/com/yap/app/domain/BlockSearch.kt` | 逐块扫描 + 键集游标的实现 |
| `app/src/main/java/com/yap/app/domain/BackupMedia.kt` | v3 媒体的 base64 打包/落盘与上限 |

---

## 7. 基于这些数字已经做的改动（0.13.0）

| 发现 | 改动 |
| --- | --- |
| 备份整份 JSON 进内存，峰值约为媒体原始字节的 5–10 倍（500×800KB 在 192MB 堆上 OOM） | 两端媒体与导入上限改成**按可用内存收紧**（`min(上限, 堆/12)` / `min(上限, 堆/8)`），UI 文案注明「小内存机型会更少」 |
| iOS v10→v11 块回填的 `UPDATE … correlated subquery` 在无索引 TEMP 表上 O(n²)：10 万条 **123.5s** | 回填前给 TEMP 表建索引（`messageId` / `(sessionId, blockIndex)`），同样数据 **0.0085s**；迁移测试全绿 |
| 导入时对本来就小的图重编码会变大（301KB→547KB） | 只在「压缩后确实更小」时才替换，原图已在上限内则原样保留（格式也保留） |
| 检索第 1/10/100 页都约 4.3s（2 万命中），成本主要在逐条 relevance | **本轮不做倒排索引**：先按结论里的低成本方案（SQL 侧算词元命中数、只对能进 frontier 的行算 relevance，或产品接受近似总数）评估；FTS5/词元倒排留到 schema v13 |
| 流式备份（导出/导入都不把整份 JSON 放内存） | **未做**，列为下一版的首要性能项（见下） |

**下一版优先级**：① 备份导出/导入流式化（两端，格式仍是 v4 的 JSON，逐块写/读）；② 检索评分剪枝或 FTS；
③ 真机（3GB 内存）复测上述数字。
