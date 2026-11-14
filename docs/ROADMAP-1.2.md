# MyFriend 1.2 设计草案：拟人节奏 · 插件式能力 · 可演化人设 · 现实连接

目标是把「一个会回话的模型」变成「一个在这台手机里生活的人」：有读消息的停顿、会自己上网看东西、能自己决定去睡觉、人设会在相处里慢慢改变，而这一切都留痕可查。

参考对象（DeepSeek Harness 生态里的人设/记忆插件与官方联网接口）：

- [dsh-guise](https://github.com/Quinn2006/dsh-guise)：人设库 + 全局/局部覆盖 + 总开关 + **人设编辑自动留档（最多 100 版，可回滚）**
- [dsh-soul / dsh-soul-md](https://www.npmjs.com/package/dsh-soul)：人设卡 + 文件式记忆，模型可用 `soul_read` / `soul_update` / `memory_*` **在对话中改写自己的人设与记忆**
- [dsh-persona-panel](https://www.npmjs.com/package/dsh-persona-panel)：记忆分三个抽屉（self / user / event），**同一件事出现两次以上才落一条**，每条带来历、可撤回，不向用户索取人设
- [dsh-persona-modes](https://github.com/railgun20001/dsh-persona-modes)：**人设放运行时上下文，系统提示词只留一条稳定策略**，切换人设不重写整份提示词；只改表达不改事实
- [DeepSeek Search & Fetch](https://api-docs.deepseek.com/guides/search/)：`POST https://api.deepseek.com/v1/tools/search` 与 `/v1/tools/fetch`，同一把 DeepSeek Key，按成功请求计费（$0.75/千次），Search QPS 100 / Fetch QPS 25

---

## 一、拟真节奏（第一条，因为它是「像人」的地基）

| 阶段 | 时长 | 界面 |
| --- | --- | --- |
| 已送达 · 未读 | `readDelay`（按字数估算，0.5–4.5s） | 只有自己的气泡 |
| 正在输入 | 到首个 token（最短 0.6s） | 标题栏「正在输入…」+ 气泡里的打字点 |
| 流式输出 | 模型吐字 | 文字逐字出现 |

- 读消息时间按「对方刚看到这段字」估算：`base + chars*0.05s + 图片*0.6s`，加 ±15% 抖动，三档（立刻 / 自然 / 慢）可调。
- **减少思考**：默认直接回答；只有模型主动调用 `think` 工具才展开长推理（阶段二）。
- 长消息、多条连发时合并成一次「正在输入」，避免跳来跳去。

## 二、插件式能力（AI 自己决定用不用）

不做「一堆硬编码功能」，而是一条工具管线：每个能力是一个带 JSON Schema 的工具，模型自己调用，调用记录以「动作」形式折叠在气泡下（可展开看它查了什么）。

内置工具（按优先级）：

1. `think` — 深思。默认不调用；用户问「你怎么看 / 帮我想想」或信息不足时才用。
2. `web_search（query, count?）` — DeepSeek Search，返回 title/url/summary/date/authority。
3. `web_fetch（url）` — DeepSeek Fetch，返回 Markdown，截断进上下文。
4. `remember（content, category, importance）` — 写长期记忆（带来源）。
5. `update_persona（patch, reason）` — 改自己的人设卡（生成新版本，可回滚）。
6. `set_quiet_hours（sleepAt, wakeAt, reason）` — 自己决定作息：到点不主动打扰，睡醒可以主动来说话。
7. `browse（topics?)` — 空闲时自己看资讯（阶段四）。

协议：优先用 OpenAI 原生 `tools` / `tool_calls`（DeepSeek 支持），模型不支持时回退到 `myfriend-tool` 代码块；单轮最多 3 次工具往返。

## 三、人设卡（结构化、可演化）

把现在的「一大段提示词」拆成结构化卡片 + 一句稳定策略：

```
身份 identity        名字、年龄感、身份、和对方的关系
声音 voice           语速、句子长度、口头禅、标点习惯、称呼
性格 traits          3–6 条，带强度（0–1）
在意 values          价值观与底线
边界 boundaries      不做什么、什么时候会拒绝
生活 life            作息、正在做的事、最近在关心什么
关系 dynamics        对「你」的认知与变化
```

注入方式（学 dsh-persona-modes）：系统提示词只放一条约 60 字的稳定策略（「你是 X，用 Y 的方式说话，先回答再展开」），人设卡正文放运行时上下文，按预算裁剪（低/中/高三档）。**只改表达，不改事实。**

- 编辑留档：每次改动生成新版本（已有 revision 机制复用），模型自己改的打 `source=AI`，人改的打 `source=USER`。
- 对话中演化：`update_persona` 默认需要你点一下确认（可切「自动接受」），拒绝也不影响这次对话。

## 四、记忆与现实连接

- 三个抽屉：`SELF`（我是什么样的人）、`USER`（关于你）、`EVENT`（约定、共同经历）。现在只有单一 memory 列表。
- 筛查规则：**同一件事第二次出现才落库**；一次性的情绪不外化；落库时保留原话片段（来源）与命中次数。
- 分层召回：常驻 `core`（重要度高、被 pin）+ 按主题召回（关键词 + 重要度 + 时间衰减，纯本地，不引入向量库）。
- 现实连接：`web_search`/`web_fetch` 的结果可被「记住」为 `EVENT`（他今天看到的事），下次聊天自然提起；主动唤醒的生成也会带最近的见闻。

## 五、分期与验收

| 阶段 | 内容 | 验收 | 状态 |
| --- | --- | --- | --- |
| 1 | 拟真节奏 + 正在输入 | 模拟器里发消息，能明显看到「正在输入」→ 逐字出现；三档可调 | ✅ 完成 |
| 2 | 工具管线 + `think` + `web_search`/`web_fetch` | 问实时问题会触发搜索，气泡下出现「查了 2 个网页」可展开；深思只在此刻发生 | ✅ 完成（联网需自备 DeepSeek tools Key） |
| 3 | 人设卡 + 自演化 + 留档 | 聊天中让 TA「记住自己改主意了」，人设页出现 AI 版本，可回滚 | ✅ 完成 |
| 4 | 空闲浏览资讯 + 作息 | 闲时自动看几条资讯，下次主动提起；到点「去睡了」不再打扰 | ✅ 完成 |
| 5 | 记忆抽屉 + 二次确认 | 同一件事说两次才进记忆，带来源、可撤回 | ✅ 完成 |

实现落点（便于以后回溯）：

| 关注点 | 代码 |
| --- | --- |
| 读消息时长 / 重生成 | [`HumanTiming.kt`](../app/src/main/java/com/myfriend/app/util/HumanTiming.kt) |
| 工具清单与两种协议 | [`ToolProtocol.kt`](../app/src/main/java/com/myfriend/app/domain/ToolProtocol.kt) |
| 工具执行（含失败兜底） | [`ToolExecutor.kt`](../app/src/main/java/com/myfriend/app/data/repo/ToolExecutor.kt) |
| 工具痕迹（可展开） | [`ToolTrace.kt`](../app/src/main/java/com/myfriend/app/domain/ToolTrace.kt) |
| DeepSeek 联网 | [`DeepSeekWebTools.kt`](../app/src/main/java/com/myfriend/app/network/DeepSeekWebTools.kt) |
| 人设卡结构 / 渲染 / 补丁 | [`PersonaCard.kt`](../app/src/main/java/com/myfriend/app/domain/PersonaCard.kt) |
| 人设卡持久化与待确认补丁 | [`PersonaCardRepository.kt`](../app/src/main/java/com/myfriend/app/data/repo/PersonaCardRepository.kt) |
| 记忆筛查 | [`MemoryScreening.kt`](../app/src/main/java/com/myfriend/app/domain/MemoryScreening.kt) |
| 记忆落库 / 回声 / 召回 | [`MemoryRepository.kt`](../app/src/main/java/com/myfriend/app/data/repo/MemoryRepository.kt) |
| 作息与主动消息 | [`ProactiveRepository.kt`](../app/src/main/java/com/myfriend/app/data/repo/ProactiveRepository.kt) |
| 空闲自浏览 | [`IdleBrowsing.kt`](../app/src/main/java/com/myfriend/app/data/repo/IdleBrowsing.kt) |

已知取舍：
- 联网工具用的是 DeepSeek 官方 `POST /v1/tools/search` 与 `POST /v1/tools/fetch`（按成功调用计费），不额外引入第三方搜索 Key。
- 人设改动、主动唤醒规则默认都要你点头，不做「AI 悄悄改自己」。
- 空闲自浏览的频率限制存在内存里，重启后清零，只影响频率不影响事实（摘要本身已经写进记忆）。

每个阶段都要求：`testDebugUnitTest` 通过、模拟器点按验证、README/设计文档同步更新。
