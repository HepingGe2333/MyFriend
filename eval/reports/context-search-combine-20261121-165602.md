# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=3

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| campus_opt | **91.9** | 88.5 | 99.0 | 4.4% | 4.11 | 4.70 | 4.47 | 4.27 | 4.97 | 4.03 |

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
