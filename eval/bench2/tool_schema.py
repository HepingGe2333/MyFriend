"""Bench v2 的原生 function calling 工具 schema。

为什么要单独放一份：

- Bench 之前只把工具清单写进系统提示词，模型只能输出 ``yap-tool`` 代码块；
  产品真实链路早已是 OpenAI ``tools[]`` + ``tool_calls``，评测必须走同一条路。
- 这里只放 **Bench 关键工具**（记忆/沉默/活动/作息/备忘/连发/回忆/延迟回复），
  参数形状与 ``app/src/main/java/com/yap/app/domain/ToolProtocol.kt`` 的
  ``*_PARAMETERS`` 对齐；描述取自 ``eval/tools.py`` 的冻结快照。
- 纯函数与常量，不依赖网络，也不执行工具。工具执行仍在 ``simulator._mock_tool``。

用法：

    from eval.bench2 import tool_schema
    tools = tool_schema.openai_tools()          # 给 provider.complete(tools=...)
    remember = tool_schema.schema_for("remember")
"""

from __future__ import annotations

import copy
from dataclasses import dataclass
from typing import Any, Iterable

# 与 eval/tools.py 的 ``ToolSpec.description`` 保持一致；只取 Bench 关键工具。
# 参数来自产品 ToolProtocol.kt：枚举/范围不是装饰，原生 function calling 下
# 模型会真的按 schema 填参数，schema 松动会让“坏参数”测试失真。
@dataclass(frozen=True)
class ToolSchema:
    """一个工具的 OpenAI function 定义（不含外层 ``{"type": "function"}``）。"""

    name: str
    description: str
    parameters: dict[str, Any]

    def function_spec(self) -> dict[str, Any]:
        """返回 ``{"name", "description", "parameters"}`` 的深拷贝。"""
        return {
            "name": self.name,
            "description": self.description,
            "parameters": copy.deepcopy(self.parameters),
        }

    def as_openai(self) -> dict[str, Any]:
        """返回可直接放进请求体 ``tools`` 数组的对象。"""
        return {"type": "function", "function": self.function_spec()}

    def as_dict(self) -> dict[str, Any]:
        return {"name": self.name, "description": self.description, "parameters": copy.deepcopy(self.parameters)}


TOOL_SCHEMAS = (
    ToolSchema(
        "remember",
        "值得长期记住的事（对方的偏好、重要经历、你们的约定）才调用；一次性的情绪、寒暄、临时安排不要记。",
        {
            "type": "object",
            "properties": {
                "content": {
                    "type": "string",
                    "description": "要记住的一句话，写成将来你还能看懂的样子",
                },
                "drawer": {
                    "type": "string",
                    "enum": ["SELF", "USER", "EVENT"],
                    "description": "SELF 记你自己和关系，USER 记对方，EVENT 记发生过的事",
                },
                "importance": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 5,
                    "description": "重要程度，1 很轻 5 很重要",
                },
            },
            "required": ["content", "drawer", "importance"],
        },
    ),
    ToolSchema(
        "memorize",
        "对方提起以前说过的事、你隐约记得但想不起细节时调用（「让我想想」）；"
        "只在你们这段会话的历史里翻，不会改任何东西。和 recall 的区别：recall 翻记忆库/更早的会话，"
        "memorize 翻这一段对话本身。只在正文说「我记得」而不调用不算。",
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "想回想起什么，关键词或一句话，例如「他说过要去的城市」「我们约的几点」",
                }
            },
            "required": ["query"],
        },
    ),
    ToolSchema(
        "stay_silent",
        "这一轮你只想「已读」、不想回时调用（累了、在忙、对方只是随口一句、不想说话）。"
        "调用后这一轮不产生任何消息，也不留念头——对方只看到「已读」。"
        "不要用它逃避重要或着急的话题，也别连续两轮都用；对方在等答案就回一句短的。",
        {
            "type": "object",
            "properties": {
                "reason": {
                    "type": "string",
                    "description": "为什么这一轮不想说话，一句话，只给自己看；可以不写",
                }
            },
        },
    ),
    ToolSchema(
        "set_activity",
        "你接下来一段时间要在忙某件具体的事（工作、通勤、吃饭、社交、家务、学习、休息……）时调用；"
        "它管的是「我在做事」以及这段忙到什么时候，不等同于安静时段（set_quiet_hours），两者可以同时存在。"
        "只是随口说「我去忙了」、或对方正着急需要你时，不要用它把话说死。",
        {
            "type": "object",
            "properties": {
                "kind": {
                    "type": "string",
                    "enum": ["WORK", "COMMUTE", "MEAL", "SOCIAL", "CHORE", "STUDY", "REST", "OTHER"],
                    "description": "这件事属于哪一类，默认 OTHER",
                },
                "activity": {
                    "type": "string",
                    "description": "用你自己的口吻一句话说清在做什么，例如「在事务所赶第三版方案」",
                },
                "minutes": {
                    "type": "integer",
                    "minimum": 15,
                    "maximum": 480,
                    "description": "预计还要多久（分钟），15-480，默认 60",
                },
                "availability": {
                    "type": "string",
                    "enum": ["BUSY", "LIMITED", "FREE"],
                    "description": "这段时间忙不忙：BUSY 回复更短更晚、不主动开口；LIMITED 回得慢；FREE 只是手头有这件事",
                },
            },
            "required": ["activity"],
        },
    ),
    ToolSchema(
        "set_quiet_hours",
        "你想按自己的作息休息、或想避开某些时段时调用；对方正需要陪伴时不要随便设。"
        "它管的是「不被打扰」（睡觉/安静时段），不是「我在做事」——在忙某件具体的事用 set_activity。",
        {
            "type": "object",
            "properties": {
                "sleep_at": {"type": "string", "description": "几点睡，24 小时制 HH:mm"},
                "wake_at": {"type": "string", "description": "几点醒，24 小时制 HH:mm"},
                "reason": {"type": "string", "description": "为什么这样安排，一句话"},
                "dnd": {
                    "type": "boolean",
                    "description": "睡前要不要开免打扰；开了睡着时就不会被消息叫醒",
                },
            },
            "required": ["sleep_at", "wake_at", "reason"],
        },
    ),
    ToolSchema(
        "diary",
        "你自己的备忘本：想到要记住的小事（答应过的事、要问的问题、看到的地址、想提醒自己的时间点）时用 add；"
        "想看看自己记过什么时用 list；记错了、过期了想撕掉时用 remove。"
        "它记的是**你自己**的事，不是关于对方的记忆（那是 remember）；"
        "要「过一会儿提醒我」就写 remindInMinutes，或者写 remindAt（毫秒时间戳）；到点会变成你心里的一个念头。",
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["add", "list", "remove"],
                    "description": "add 写一条、list 翻最近的、remove 撕掉一条",
                },
                "content": {
                    "type": "string",
                    "description": "add 必填：要记下来的事，最多 2000 字，写成将来你自己还看得懂的样子",
                },
                "noteId": {"type": "string", "description": "remove 必填：list 结果里那条笔记的 id"},
                "remindAt": {
                    "type": "integer",
                    "description": "可选：什么时候提醒你，毫秒时间戳（与 remindInMinutes 二选一）",
                },
                "remindInMinutes": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 43200,
                    "description": "可选：过多少分钟提醒你（1 到 30 天）",
                },
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 50,
                    "description": "list 要几条，默认 20，最多 50",
                },
            },
            "required": ["action"],
        },
    ),
    ToolSchema(
        "send_message",
        "想分几条短消息发出去时用（一句一条、想到什么先发什么）；每条最多 120 字，一轮最多 4 条。"
        "发完还可以继续用正文补最后一句。日常只回一句时不要用它，直接说。",
        {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "这一条要发出去的话，最多 120 字"},
            },
            "required": ["text"],
        },
    ),
    ToolSchema(
        "recall",
        "想不起对方提过的事、要找很久以前聊过的内容、或想知道自己看过什么的时候调用；"
        "depth 越深翻得越久也越慢（3 会翻到很早以前，甚至翻不出来）；日常闲聊不要调用。",
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "想找什么，关键词或一句话，例如「怕冷」「上次说的那家店」",
                },
                "depth": {
                    "type": "string",
                    "description": "回忆深度：1/2/3 或 quick/normal/deep；1 随口想想只翻还在用的，"
                    "2 认真回忆（默认）连沉下去的和最近聊天一起翻，3 翻箱倒柜连很久以前的都翻",
                },
            },
            "required": ["query"],
        },
    ),
    ToolSchema(
        "defer_reply",
        "这一轮你想先不回、过一会儿再说时调用（现在不想说话、想等自己缓一缓、不知道怎么接）；"
        "调用后这一轮不会发出任何消息，对方只会看到「已读」。不想回就别硬回，别用它来敷衍正在着急的人。",
        {
            "type": "object",
            "properties": {
                "minutes": {
                    "type": "integer",
                    "minimum": 5,
                    "maximum": 720,
                    "description": "过多少分钟再考虑开口，默认 45",
                },
                "reason": {"type": "string", "description": "为什么现在不回，一句话，只给自己看"},
            },
        },
    ),
    ToolSchema(
        "web_search",
        "价格、多少钱、新闻、今天发生的事、版本号、你不确定的最新事实：必须先调用；纯聊天或你本来就知道的常识不要调用。第一个工具调用必须是 web_search，不要用 memorize/recall 代替。",
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "搜索关键词，具体一点"},
                "count": {"type": "integer", "minimum": 1, "maximum": 10, "description": "结果条数，默认 5"},
            },
            "required": ["query"],
        },
    ),
    ToolSchema(
        "web_fetch",
        "已经拿到具体链接、需要读全文时调用；还没拿到链接先 web_search，不要凭空编 URL。",
        {
            "type": "object",
            "properties": {"url": {"type": "string", "description": "完整的 http(s) 链接"}},
            "required": ["url"],
        },
    ),
)

TOOL_NAMES: tuple[str, ...] = tuple(schema.name for schema in TOOL_SCHEMAS)
_SCHEMA_BY_NAME: dict[str, ToolSchema] = {schema.name: schema for schema in TOOL_SCHEMAS}


def tool_names() -> list[str]:
    """返回原生 tools 里的工具名（顺序稳定，便于测试与报告）。"""
    return list(TOOL_NAMES)


def schema_for(name: str) -> ToolSchema | None:
    return _SCHEMA_BY_NAME.get(str(name).strip())


def openai_tools(names: Iterable[str] | None = None) -> list[dict[str, Any]]:
    """生成 OpenAI ``tools`` 数组。

    ``names`` 为空/None 时返回全部 9 个关键工具；顺序按 ``TOOL_NAMES``，
    传进来的重复名字会被去重。未知名字忽略（不抛异常，benchmark 不该因拼错名字中断）。
    """
    if names is None:
        selected = list(TOOL_SCHEMAS)
    else:
        wanted = {str(name).strip() for name in names if str(name).strip()}
        selected = [schema for schema in TOOL_SCHEMAS if schema.name in wanted]
    return [schema.as_openai() for schema in selected]


def tools_json(names: Iterable[str] | None = None) -> str:
    """调试/报告用：序列化后的 OpenAI tools JSON 字符串。"""
    import json

    return json.dumps(openai_tools(names), ensure_ascii=False, indent=2)


__all__ = [
    "TOOL_NAMES",
    "TOOL_SCHEMAS",
    "ToolSchema",
    "openai_tools",
    "schema_for",
    "tool_names",
    "tools_json",
]
