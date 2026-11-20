# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=3

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| opt_g12 | **86.2** | 89.6 | 95.2 | 25.0% | 4.17 | 4.86 | 4.56 | 4.31 | 4.94 | 4.06 |
| opt_g17 | **85.8** | 89.2 | 94.9 | 25.0% | 4.14 | 4.78 | 4.36 | 4.33 | 4.97 | 4.17 |
| current | **85.6** | 88.2 | 95.7 | 22.2% | 4.22 | 4.78 | 4.36 | 4.19 | 4.89 | 4.03 |
| opt_g12_keep | **83.9** | 89.9 | 94.5 | 30.6% | 4.28 | 4.86 | 4.50 | 4.28 | 4.86 | 4.19 |

## 最优 profile

```json
{
  "arch": "state_first",
  "guidance": "g12",
  "memory": "full",
  "anti": "core",
  "tools": "full",
  "history": "timestamped",
  "state": "minimal",
  "persona": "compact"
}
```
