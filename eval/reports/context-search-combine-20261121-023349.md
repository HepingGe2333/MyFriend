# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=3

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| current | **87.3** | 90.6 | 95.9 | 22.2% | 4.42 | 4.86 | 4.39 | 4.31 | 4.89 | 4.33 |
| cons_state_first | **87.2** | 86.9 | 96.9 | 16.7% | 4.08 | 4.78 | 4.44 | 4.14 | 4.81 | 3.83 |
| opt_g12 | **86.3** | 88.5 | 95.6 | 22.2% | 4.14 | 4.89 | 4.28 | 4.25 | 4.89 | 4.11 |
| cons_compact | **83.8** | 85.5 | 95.4 | 25.0% | 3.94 | 4.67 | 4.44 | 4.08 | 4.78 | 3.72 |

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
  "persona": "full"
}
```
