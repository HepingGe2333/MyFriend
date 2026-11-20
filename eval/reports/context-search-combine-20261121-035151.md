# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=1

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| opt_g17 | **88.7** | 88.2 | 97.6 | 13.3% | 4.07 | 4.83 | 4.23 | 4.40 | 4.87 | 4.07 |
| prod | **86.6** | 88.7 | 96.2 | 20.0% | 4.13 | 4.80 | 4.40 | 4.30 | 4.87 | 4.10 |
| base | **85.3** | 87.8 | 95.8 | 23.3% | 4.30 | 4.70 | 4.20 | 4.17 | 4.80 | 4.17 |

## 最优 profile

```json
{
  "arch": "state_first",
  "guidance": "g17",
  "memory": "full",
  "anti": "core",
  "tools": "full",
  "history": "timestamped",
  "state": "full",
  "persona": "full"
}
```
