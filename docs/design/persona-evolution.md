# 人格进化与提示词框架补全（0.13）

## 1. 提示词必须回答「我是谁、我在哪、我在做什么、我能用什么」

现有五段（身份/世界/此刻/话风/关系/边界）已覆盖前四项；0.13 补最后一项：

```
# 你能做什么
- 你可以调用这些工具：remember / recall / set_mood / set_quiet_hours / web_search / web_fetch /
  persona_patch / defer_reply / set_activity / diary / sticker
- 每个工具的用途、什么时候该用、什么时候别用（一段一句，写清副作用）
- 工具失败时诚实说不知道，不要假装做过
```

- 工具说明与 `ToolProtocol` 的 schema **同源**（从工具定义生成，不手写两份）；
- 模型必须知道：`set_activity` 是「我在忙」不是睡觉；`set_quiet_hours` 是睡觉；`diary` 是自己的备忘录；
  `sticker` 是发表情；`persona_patch` 是改自己的人设（有限制）。

## 2. 人格进化机制

目标：人格会随对话缓慢变化，但**不失控、不 OOC、不偷偷改用户写的内容**。

- 每 N 轮（默认 20 轮用户消息）或每 24 小时，跑一次「进化评估」：
  - 输入：当前人格卡（traits/likes/behaviors/backstory/quirks）+ 最近 K 条对话摘要（不含原始长文本）；
  - 输出：JSON `{changes:[{field,add|remove,value,reason,confidence}]}`，最多 3 条、每条 ≤40 字；
  - 约束：只允许增删 traits/likes/behaviors/quirks；**不碰** name/description/prompt/world/relationship；
    单次最多加 2 条、删 1 条；置信度 <0.6 丢弃；同类重复去重；
  - 落库：写 `persona_pending_patch`（已有表）→ 用户可在人格页「他最近的变化」里接受/拒绝；
  - 拒绝/忽略 7 天后自动丢弃；接受后写回人格卡并 `version+1`。
- 模型自己也可以用 `persona_patch` 工具立刻提议一条（同样进 pending，不直接生效）。
- 反例：不因一次争吵就删「温柔」；不把用户随口一句「你好烦」变成 traits；不改用户手写的 prompt。

## 3. 验收

- 提示词包含工具用途段，且与实际工具列表一致（契约测试）；
- 进化评估：JSON 解析、上限、字段白名单、置信度、去重、pending → 接受/拒绝、7 天过期；
- 连续 20 轮对话后最多产生 3 条待审变化；不触碰禁用字段。
