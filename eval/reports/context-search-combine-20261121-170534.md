# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=3

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| campus_opt | **90.2** | 86.8 | 98.8 | 6.7% | 3.99 | 4.57 | 4.41 | 4.18 | 4.96 | 3.94 |

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
