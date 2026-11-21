# 上下文工程参数搜索（combine）

- campus-chat；35 个多轮场景；samples=1；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| base | **92.6** | 91.2 | 98.3 | 5.7% | 30/0 | 4.43 | 4.83 | 4.40 | 4.51 | 4.91 | 4.29 |

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
