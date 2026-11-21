# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=2

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| opt | **92.2** | 91.2 | 98.7 | 6.7% | 4.33 | 4.87 | 4.47 | 4.37 | 4.93 | 4.38 |
| base | **92.0** | 89.9 | 98.5 | 6.7% | 4.30 | 4.78 | 4.35 | 4.32 | 4.95 | 4.27 |

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
