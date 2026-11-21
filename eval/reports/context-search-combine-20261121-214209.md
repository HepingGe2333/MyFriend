# 上下文工程参数搜索（combine）

- glm-flash；30 个多轮场景；samples=1；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| glm_base | **94.6** | 94.7 | 98.8 | 6.7% | 34/0 | 4.57 | 4.80 | 4.80 | 4.80 | 4.93 | 4.50 |

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
