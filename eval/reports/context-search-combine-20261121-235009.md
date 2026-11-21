# 上下文工程参数搜索（combine）

- glm-flash；35 个多轮场景；samples=1；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| web_hint | **93.7** | 94.5 | 97.8 | 8.6% | 37/0 | 4.63 | 4.89 | 4.63 | 4.69 | 4.94 | 4.57 |

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
