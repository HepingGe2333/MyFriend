# 报告索引

| 文件 | 说明 |
| --- | --- |
| `baseline.json` | **当前基线**（2026-11-20 16:43:20，总分 92.1，通过 25/31），与 `run-20261120-164320.json` 相同 |
| `run-20261120-164320.json/.md` | 当前基线同轮报告；失败：`low-001`、`blocked-002`、`memory-001`、`fact-001`、`fact-003`、`tool-003` |
| `run-20261120-163747.json/.md` | 上一轮（总分 92.8）；`fact-001` 返回 `<tool_code>`，`low-001` 有旁白 |
| `run-20261120-163723.json/.md` | 更早一轮（总分 94.8）；`fact-001` 返回 `<tool>web_search</tool>` |
| `run-20261120-163626.json/.md` | 更早一轮（总分 93.5）；`fact-001` 返回 `<tool_call>`，`blocked-002` 带括号旁白 |
| `run-20261120-162847.json/.md` | 旁白证据：`low-001` 回复「（沉默了一会儿）…」 |
| `run-20261120-162939.json/.md` | 幻觉证据：`fact-003` 回复「没说过吧。在天津读的。」 |
| `run-20261120-162246.json/.md` | 更早的幻觉证据：`fact-003` 回复「建筑系，沿海那个，你知道的。」 |
| `fake-smoke.json/.md` | `--provider fake` 离线冒烟，31/31 通过；只证明管线，不代表模型质量 |

报告里的 `meta.generated_at`、`summary.overall`、`summary.dimensions` 是判断基线是否过期的依据；
场景集变化后（`meta.scenario_set_digest` 不同）旧基线不能直接比。
