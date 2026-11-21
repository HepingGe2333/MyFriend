# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=3

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| base | **89.8** | 90.4 | 97.4 | 14.4% | 4.27 | 4.80 | 4.36 | 4.34 | 4.90 | 4.46 |

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
  "persona": "full",
  "hints": "off"
}
```
