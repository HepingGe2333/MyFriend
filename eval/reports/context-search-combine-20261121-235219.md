# 上下文工程参数搜索（combine）

- campus-chat；35 个多轮场景；samples=1；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| web_hint | **93.7** | 91.1 | 99.0 | 2.9% | 23/0 | 4.40 | 4.86 | 4.29 | 4.49 | 4.91 | 4.40 |

## 最优 profile

```json
{
  "arch": "compact",
  "guidance": "g17",
  "memory": "retrieval",
  "anti": "full",
  "tools": "full",
  "history": "raw",
  "state": "full",
  "persona": "full",
  "hints": "web"
}
```
