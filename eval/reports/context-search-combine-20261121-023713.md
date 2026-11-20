# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=5

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| cons_state_first | **85.3** | 86.1 | 95.8 | 23.3% | 4.03 | 4.67 | 4.47 | 4.12 | 4.82 | 3.72 |
| current | **84.3** | 88.4 | 94.7 | 26.7% | 4.20 | 4.72 | 4.37 | 4.22 | 4.90 | 4.13 |
| g12_only | **83.2** | 87.3 | 94.6 | 30.0% | 4.15 | 4.75 | 4.42 | 4.10 | 4.82 | 3.95 |

## 最优 profile

```json
{
  "arch": "state_first",
  "guidance": "g12",
  "memory": "full",
  "anti": "core",
  "tools": "full",
  "history": "timestamped",
  "state": "full",
  "persona": "full"
}
```
