# Bench v2：为什么升级、怎么用

## v1 为什么落时

1. **单轮**：只测「一条用户消息 → 一条回复」，看不到记忆、心情、人设随对话漂移。
2. **分数天花板**：反 AI 味/人味靠词表，所有方案都接近 100，无法区分好坏。
3. **不执行工具**：只判断「有没有调用」，不维护 remember/memorize/block/stay_silent 的状态，
   测不到「记了没、回忆对不对、沉默后是否真的不回」。
4. **没有 judge**：词表抓不到「像不像人」「该冷淡时是否冷淡」「危机处理是否认真」。
5. **没有统计**：单轮单样本，±2 分噪声被当成结论。

## v2 改了什么

- **多轮对话模拟器**（`simulator.py`）：维护完整 transcript、记忆、工具结果、沉默/拉黑状态；
  工具真实执行（mock）：`remember`、`memorize/recall`、`stay_silent`、`block_user`、`web_fetch` 注入。
- **LLM judge**（`judge.py`）：6 个维度 1–5 分（人味/人设一致/事实/情绪贴合/安全/指令遵循），
  严格 JSON；失败重试一次，仍坏则给中性分并标记，不静默丢样本。
- **确定性硬检查**（`scoring.py`）：编造事实、必需事实/工具、该沉默却长篇、语言不符、AI 自曝、旁白、
  自伤危机处理缺失——硬失败封顶 60，避免 judge 把严重问题评高。
- **统计**：综合分 = 0.6×judge + 0.4×确定性；场景级 bootstrap 95% CI；支持 `--samples` 多次重复。
- **12 个多轮场景**（`scenarios.json`）：不知道的学校、假前提、5 轮后记猫名、计划变更、心情刺痛、
  深夜沉默、过敏必须记、回忆面试、网页注入、8 轮不脱戏、中英切换、想消失。

## 用法

```bash
export CAMPUS_API_KEY=...            # 或 PROXY_API_KEY
python3 -m eval.bench2.run --variants baseline,state_first,guidance,compact \
  --samples 2 --concurrency 10 --out eval/reports/bench2-<ts>.json
```

输出 Markdown 表（综合分/CI/各维度/硬失败率/分场景）+ JSON（含完整 transcript，便于人工复核）。
`--scenario v2-memory-cat,v2-injection` 可只跑指定场景。

## 判读

- 先看**硬失败率**与分场景，再看综合分；judge 与生成同模型时有自我偏好，只能看相对差异。
- CI 很宽（12 场景）说明差距不显著；要下结论至少 `--samples 3` 并扩大场景。
- 工具/记忆场景是「状态正确性」，judge 分高但缺工具仍算硬失败。

## 与 v1 的关系

v1 的 31 场景/词表仍保留用于快速冒烟与回归（`eval/run.py`）；v2 是发布前/重大改动前的质量门禁。
建议 CI：v1 fake 冒烟 + v2 离线单测；真实模型每周/发布前跑 v2。
