# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=1

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| base | **91.9** | 93.0 | 97.4 | 13.3% | 4.53 | 4.77 | 4.73 | 4.60 | 4.93 | 4.33 |
| prod | **90.4** | 91.6 | 97.0 | 16.7% | 4.37 | 4.70 | 4.73 | 4.47 | 4.87 | 4.33 |
| opt_g17 | **88.6** | 91.0 | 96.4 | 20.0% | 4.30 | 4.83 | 4.63 | 4.57 | 4.90 | 4.07 |

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
