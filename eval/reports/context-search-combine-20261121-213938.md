# 上下文工程参数搜索（combine）

- campus-chat；30 个多轮场景；samples=1；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| campus_opt | **94.7** | 91.3 | 99.7 | 0.0% | 30/0 | 4.33 | 4.97 | 4.40 | 4.47 | 4.93 | 4.30 |

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
