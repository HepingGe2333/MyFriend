# 上下文工程参数搜索（combine）

- glm-flash；35 个多轮场景；samples=1；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| base2 | **95.5** | 94.0 | 98.8 | 2.9% | 48/0 | 4.71 | 4.89 | 4.49 | 4.77 | 4.97 | 4.37 |

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
  "hints": "off"
}
```
