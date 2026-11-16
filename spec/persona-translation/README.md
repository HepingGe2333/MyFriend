# 人格提示词的语言本地化（启动前翻译）

## 1. 为什么需要

人设、世界设定、话风经常是用户用中文写的，而 App 可能跑在英文界面（或反过来）。如果直接把中文设定塞进
英文系统提示词，模型会在「中文设定 + 英文对话」之间摇摆，输出混杂；用户看到的人设卡片也会是另一种语言。

所以：**在「开始人格」之前**（打开聊天、发起主动消息、生成前），把人设相关提示词翻译成**软件当前语言**，
缓存起来复用；翻译失败就用原文，不阻塞聊天。

## 2. 触发时机（规范性）

在以下动作**之前**检查并按需翻译（同一进程内只做一次，缓存命中则零网络）：

1. 打开某个会话 / 进入聊天页；
2. 服务端或客户端要生成一条主动消息；
3. 导出提示词预览（调试面板）。

**不在** App 启动时批量翻译（避免一次打开就发 N 个请求）。

## 3. 翻译哪些字段

| 来源 | 字段 |
| --- | --- |
| `personas` | `name`、`description`、`traits`、`likes`、`behaviors`、`backstory`、`quirks`、`systemPrompt`（若存在） |
| `worldJson` | `worldName`、`era`、`description`、`schedule[].activity/day`、`relationships[].relation/note`、`obligations[]`、`notes` |
| `speechStyleJson` | `tone`、`habits[]`、`avoid[]`（**翻译**；`phrases[]` 是「这个人怎么说话」，**永不翻译**——翻译会毁掉语感，且不进 `sourceHash`） |
| 会话 | `title`（可选，仅展示用） |

**不翻译**：消息正文、记忆正文（它们是「他说过的话」，翻译会破坏真实性）、`phrases`。

## 4. 输出与缓存

```json
{
  "lang": "en",
  "translatedAt": 1794500000000,
  "sourceHash": "sha256:…",
  "persona": { "name": "...", "description": "...", "traits": [...], "likes": [...], "behaviors": [...], "backstory": "...", "quirks": "...", "systemPrompt": "..." },
  "world": { "worldName": "...", "era": "...", "description": "...", "schedule": [...], "relationships": [...], "obligations": [...], "notes": "..." },
  "speech": { "tone": "...", "habits": [...], "avoid": [...] }
}
```

- 存 `personas.translationJson`（TEXT，nullable）+ `translationLang`（TEXT，nullable）；
- `sourceHash` = 规范化 JSON 的 SHA-256：**两端必须逐字节同算法**，形状由 §4.1 定死；
  `phrases` 不参与；**任一源字段变化 → 缓存失效**，下次启动重译；
- `lang` 与当前 App 语言不一致 → 重译；
- 手动「重新翻译」按钮（人格编辑页）清空缓存并立即翻译。

### 4.1 `sourceHash` 的规范化输入（规范性）

两端在算哈希前，都必须把「被翻译字段」整理成**同一个固定形状**，再规范化序列化 + SHA-256。
跨端共享用例见 `spec/persona-translation/cases/hash-cases.json`；其中的 `expectHash` 由
`scripts/hash-cases.py` **独立**算出（不调用 Android / iOS 实现），复核时直接跑那个脚本。

形状（`…` = 字符串，`|` = 二选一）：

```json
{"persona":{"name":…,"description":…,"traits":[…],"likes":[…],"behaviors":[…],"backstory":…,"quirks":…,"systemPrompt":…},"speech":{…}|null,"world":{…}|null}
```

规则：

1. **根**固定 3 键：`persona` 永远存在；`world` / `speech` 在「没有」或「全空」时写 JSON `null`
   （**不省键、也不写一个空对象**）。
2. **`persona` 固定 8 键**：`name/description/traits/likes/behaviors/backstory/quirks/systemPrompt`。
   缺值写 `""` / `[]`；数组保持源顺序（不排序、不去重）；字符串**原样**——不 `trim()`、不截断、
   不丢空串项。
3. **`world` 固定 7 键**：`worldName/era/description/schedule/relationships/obligations/notes`。
   - `schedule` 项固定 4 键：`day/start/end/activity`；
   - `relationships` 项固定 `name/relation/note`，`intimacy` **仅在非 null 时写**（为 null 时该键
     整个不出现，不能写 `"intimacy":null`）；
   - `world` 的「全空」按 §2.1 判定：七个字段都是空串/空数组 → 整个 `world` 写 `null`；
   - 七个键**全部写出来**（空串/空数组照写）；这里不复用 `WorldPresetCodec.encode`——它那套
     「省略空键 / 过滤空项」是人格列的存储规则，不是哈希协议。
4. **`speech` 固定 3 键**：`tone/habits/avoid`。空字符串 / 空数组**原样保留**
   （`{"tone":"","habits":[],"avoid":[]}` 是合法的非 null 值）；但 `tone`、`habits`、`avoid`
   **三者都为空** → `speech` 写 `null`。`phrases` 不参与这个判断：只有 `phrases` 的话风等同于
   「没有话风」→ `null`。否则「改常用语」会经由 null ↔ 空对象的翻转改变哈希，违反 §3。
5. **规范化序列化**：对象键**递归**按字典序排序、无任何多余空白（`json_encode` / `JSONSerialization`
   等价的紧凑形式）；字符串只按 JSON 规范转义 `"` / `\` / 控制字符，**非 ASCII 与 `/` 都不转义**
   （iOS 的 `JSONSerialization` 默认会把 `/` 写成 `\/`，必须加 `.withoutEscapingSlashes`；
   否则人设里出现 `方案/施工图` 或 URL，两端就会算出两个哈希）；JSON `null` 小写；
   UTF-8 字节做 SHA-256；十六进制**小写**、`sha256:` 前缀。
6. **值来自源字段在内存里的值**：世界/话风先经两端各自的宽容解析成模型（那一步可能 `trim` 首尾空白、
   丢掉格式不对的条目或超出上限的项——这是解析的事），规范化函数**本身不再二次加工**：
   不再 `trim`、不再过滤空项、不再排序数组、非 ASCII 不转义（`ensure_ascii=false`）。
   人设字段（`name/description/backstory/quirks/systemPrompt`）本来就是库里的原值，
   首尾空白会原样进哈希——这是「两端不 trim」的准确含义。

只有人设的规范化输入示例（键已字典序、无空白）：

```json
{"persona":{"backstory":"","behaviors":[],"description":"建筑师","likes":[],"name":"林深","quirks":"","systemPrompt":"你是林深。","traits":[]},"speech":null,"world":null}
```

## 5. 提示词（实测可用）

```
你是翻译器。把给定的人设/世界设定翻译成{目标语言}，保留语气、称呼与专有名词的意译一致性。
只输出 JSON，键名原样保留，不要增删键，不要翻译 phrases 数组，不要解释：
{ "persona": {…}, "world": {…}, "speech": {…} }
规则：
- 专有名词首次出现可音译，之后保持一致；
- 不要润色成书面语，保持口语性格；
- 不确定的短语可保留原文。
```

- 调用参数：温度 0.2，`response_format=json_object`（客户端支持时）；
- 失败/超时（8s）/无 Key → 用原文，记录一次「翻译失败」状态，下次启动再试；
- 语言检测：源字段中 CJK 字符占比 > 0.2 视为中文；目标语言取 App 语言（`zh` / `en`），源=目标则跳过。

## 6. 隐私与成本

- 只发送**人设/世界/话风**（用户自己写的设定），不发送聊天记录、记忆、API Key；
- 每次翻译是一次普通 completion，计入 `api_usage`（`kind=TRANSLATION`，新增枚举值）；
- 缓存命中后零请求；用户可在设置里关掉「自动翻译人设」（默认开）。

## 7. 回退与错误

| 情况 | 行为 |
| --- | --- |
| 无 API 配置 | 直接用原文，不提示（离线可用） |
| 调用失败/超时 | 用原文 + 人格页显示「翻译失败，可重试」 |
| 返回坏 JSON/缺字段 | 逐字段回退原文（不是整份丢弃） |
| 目标语言不是 zh/en | 按 BCP-47 传给模型；缓存按完整 tag 区分 |

## 8. 与两端/服务端的一致性

- 翻译发生在**客户端**（prompt 构造在客户端或服务端都可用；服务端权威模式下由服务端在生成前翻译并缓存，
  规则相同，字段名一致）；
- 缓存列名固定为 `translationJson` / `translationLang`，两端 schema 对齐（v14）；
- 备份 v6 携带翻译缓存（可选，见备份文档）；恢复时若 `lang` 与当前不符则忽略缓存。
