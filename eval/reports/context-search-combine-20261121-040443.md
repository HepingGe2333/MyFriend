# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=1

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| prod_hints | **89.9** | 88.0 | 98.2 | 10.0% | 4.20 | 4.77 | 4.37 | 4.30 | 4.87 | 3.90 |
| base_hints | **89.9** | 88.2 | 98.2 | 10.0% | 4.30 | 4.73 | 4.30 | 4.23 | 4.83 | 4.07 |
| prod_nohints | **89.2** | 88.8 | 97.6 | 13.3% | 4.07 | 4.77 | 4.50 | 4.27 | 4.93 | 4.10 |
| opt_g17_hints | **88.1** | 86.8 | 97.6 | 13.3% | 4.10 | 4.70 | 4.17 | 4.17 | 4.93 | 3.97 |

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
  "persona": "full",
  "hints": "on"
}
```
