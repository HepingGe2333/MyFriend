# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=2

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| opt_g12 | **85.8** | 91.4 | 94.8 | 29.2% | 4.29 | 4.96 | 4.75 | 4.29 | 4.92 | 4.21 |
| current | **84.7** | 85.8 | 96.0 | 20.8% | 4.00 | 4.50 | 4.29 | 4.08 | 4.79 | 4.08 |
| opt_state_first | **84.2** | 87.5 | 94.8 | 29.2% | 4.17 | 4.71 | 4.29 | 4.17 | 4.88 | 4.04 |
| opt_mem_long | **84.2** | 88.5 | 94.5 | 29.2% | 4.12 | 4.92 | 4.38 | 4.17 | 4.96 | 4.00 |
| opt_compact | **84.1** | 89.4 | 94.1 | 29.2% | 4.17 | 4.88 | 4.50 | 4.29 | 5.00 | 4.00 |
| opt_raw_history | **83.6** | 90.8 | 93.8 | 33.3% | 4.29 | 4.92 | 4.54 | 4.38 | 4.96 | 4.17 |

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
