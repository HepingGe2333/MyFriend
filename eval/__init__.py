"""Yap 生成质量评测平台（离线优先，纯标准库）。

入口：

    python3 -m eval.run --provider fake
    python3 -m eval.run --provider deepseek --limit 10

模块划分：
- ``eval.prompt``   —— 按 App 的 ``PromptBuilder`` 形状拼系统提示词（快照，见 README）
- ``eval.provider`` —— OpenAI 兼容客户端 + 假 provider
- ``eval.metrics``  —— 长度 / AI 味 / 事实 / 工具 / 沉默 / 语言等指标
- ``eval.run``      —— CLI、报告、baseline 对比
"""

__all__ = ["prompt", "provider", "metrics", "run"]
