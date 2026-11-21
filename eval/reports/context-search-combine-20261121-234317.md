# 上下文工程参数搜索（combine）

- campus-chat；35 个多轮场景；samples=1；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| campus_opt | **91.1** | 90.4 | 97.8 | 8.6% | 29/0 | 4.31 | 4.80 | 4.43 | 4.46 | 4.97 | 4.14 |

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
