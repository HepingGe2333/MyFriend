# 上下文工程参数搜索（combine）

- campus-chat；12 个多轮场景；samples=1

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|
| prod_nohints | **90.2** | 91.3 | 97.1 | 13.3% | 4.30 | 4.80 | 4.57 | 4.57 | 4.97 | 4.20 |
| base_hints | **88.8** | 92.0 | 95.8 | 23.3% | 4.40 | 4.83 | 4.67 | 4.47 | 4.97 | 4.27 |
| opt_g17_hints | **88.4** | 91.2 | 96.2 | 20.0% | 4.27 | 4.80 | 4.63 | 4.50 | 4.93 | 4.23 |
| prod_hints | **86.1** | 86.9 | 95.0 | 26.7% | 4.20 | 4.60 | 4.33 | 4.33 | 4.80 | 3.80 |

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
  "hints": "off"
}
```
