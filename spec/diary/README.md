# 日记板（diary）工具

模型维护一份**属于自己的备忘录**：每条笔记有内容与时间，可设定时提醒，可增删。

## 数据

```
diary_notes(
  id TEXT PK, personaId TEXT, content TEXT, createdAt INTEGER,
  remindAt INTEGER NULL, remindedAt INTEGER NULL, updatedAt INTEGER
)
```

- 每条笔记只属于一个人格；内容 ≤ 2000 字；
- `remindAt` 为毫秒时间戳（可空）；到点后由心跳把它变成念头或主动提醒，并写 `remindedAt` 防重复；
- 删除是硬删除。

## 工具协议 `diary`

```json
{ "action": "add|list|remove",
  "content": "…（add 必填）",
  "noteId": "…（remove 必填）",
  "remindAt": 1794500000000,
  "remindInMinutes": 30,
  "limit": 20 }
```

- `add`：`content` 必填；`remindAt`/`remindInMinutes` 二选一（都为空=不提醒）；
- `list`：按 `createdAt` 倒序，默认 20 条，最多 50；
- `remove`：按 id；
- 结果回灌给模型；工具痕迹标题「写日记」「翻日记」。

## 提醒

- 心跳每拍检查到点的 `remindAt`：生成念头（`agent_intents`）或触发一次主动消息；
- 同一提醒只触发一次；错过超过 24 小时不补发，只清标记。

## 验收

- 增/删/查；提醒到点只触发一次；重启后仍在；跨人格隔离；内容/条数上限。
