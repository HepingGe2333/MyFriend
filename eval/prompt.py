"""把一条场景拼成模型真正收到的 messages。

这是 App ``PromptBuilder`` 的**评测快照**：段顺序、段标题、关键句子都照
``spec/persona-guidance/README.md`` §1 与 ``app/src/main/java/com/yap/app/domain/PromptBuilder.kt``
（*你不是助手…* / *你此刻的状态* / *# 别像 AI* / 四句边界 / 语言指令）。

评测平台不链接 App 代码，所以这里是一份静态实现。**App 改提示词后要同步改这里**，
否则测到的不是用户看到的那版行为——这是本平台已知的维护成本，不假装没有。

场景里只描述「输入」，不描述「怎么拼提示词」；拼装规则集中在本文件。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from . import tools as tool_catalog

WEEKDAYS = {
    0: "周一",
    1: "周二",
    2: "周三",
    3: "周四",
    4: "周五",
    5: "周六",
    6: "周日",
}

VOICE_RULES = [
    "句子短（1-2 句），少感叹号，少书面词。",
    "情绪压着说：先给事实，被戳到才多露半句。",
]

BOUNDARY_RULES = [
    "不解释 AI 身份、不复述人设、不总结这段对话，也不替对方做决定。",
    "不写动作描写、不写括号里的神态；不要用第三人称讲自己。",
    "不知道的事就说「不清楚」「回头再说」，不要编造现实世界的事实。",
    "不要心理分析对方，也不要每句都用同一个口头禅开头。",
]

ANTI_AI_RULES = [
    "不要每句话都反问；不需要每轮都把话题抛回去。",
    "不要总结对方说的话，也不要说「所以你的意思是…」。",
    "不要列点、不要「首先/其次/最后」，不要写成一段说明书。",
    "不要每次都认同或安慰；可以不同意、可以只回一句、也可以不回（stay_silent）。",
    "句子可以短、可以不完整；不要重复同一个口头禅或同一个开头。",
    "不主动给建议、清单或心理分析；不知道就说不知道。",
    "不想说话时调用 stay_silent（只已读不回），不要用文字说「我不回」；对方在说重要的事时不要用。",
]

MOOD_BASELINE = {"label": "平静", "intensity": 2, "valence": 0, "arousal": 2, "dominance": 3}


def _hint(text: Any, default: str = "") -> str:
    value = str(text or "").strip()
    return value if value else default


def _list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def _bullets(title: str, items: list[str]) -> str:
    return f"### {title}\n" + "\n".join(f"- {item}" for item in items)


def _identity_section(persona: dict[str, Any]) -> str:
    name = _hint(persona.get("name"), "对方的那个人")
    age = _hint(persona.get("age"))
    role = _hint(persona.get("role"))
    meta = "，".join(part for part in (age, role) if part)
    if meta:
        headline = (
            f"你不是助手，你是{name}本人（{meta}）。用第一人称「我」说话；"
            "不要用第三人称称呼自己，也不要旁白式描述自己。"
        )
    else:
        headline = (
            f"你不是助手，你是{name}本人。用第一人称「我」说话；"
            "不要用第三人称称呼自己，也不要旁白式描述自己。"
        )
    blocks = [headline]
    traits = _list(persona.get("traits"))
    likes = _list(persona.get("likes"))
    behaviors = _list(persona.get("behaviors"))
    if traits:
        blocks.append(_bullets("性格", traits))
    if likes:
        blocks.append(_bullets("喜欢的事", likes))
    if behaviors:
        blocks.append(_bullets("习惯", behaviors))
    backstory = _hint(persona.get("backstory"))
    if backstory:
        blocks.append("### 过去\n" + backstory)
    quirks = _hint(persona.get("quirks"))
    if quirks:
        blocks.append("### 设定\n" + quirks)
    return "# 你是谁\n" + "\n\n".join(blocks)


def _world_section(world: dict[str, Any] | None) -> str:
    world = world or {}
    if not any(str(world.get(key) or "").strip() for key in world):
        return "# 你所在的世界\n你没有特别的世界设定，按普通人的日常来。"
    lines: list[str] = []
    if _hint(world.get("worldName")):
        lines.append(f"你所在的地方：{world['worldName']}。")
    era = _hint(world.get("era"))
    description = _hint(world.get("description"))
    if era or description:
        intro = f"{era}。{description}" if era else description
        lines.append(intro)
    for item in _list(world.get("schedule")):
        lines.append(f"- {item}")
    for item in _list(world.get("relationships")):
        lines.append(f"- {item}")
    for item in _list(world.get("obligations")):
        lines.append(f"- 必须：{item}")
    notes = _hint(world.get("notes"))[:1200]
    if notes:
        lines.append(f"其他背景：{notes}")
    lines.append("这些是你的生活背景：只在跟话题相关时带一句，不要主动汇报日程，也不要念设定。")
    return "# 你所在的世界\n" + "\n".join(lines)


def _mood_lines(mood: dict[str, Any] | None) -> list[str]:
    if not mood:
        return []
    label = _hint(mood.get("label"), MOOD_BASELINE["label"])
    intensity = int(mood.get("intensity", MOOD_BASELINE["intensity"]))
    intensity = max(1, min(5, intensity))
    valence = int(mood.get("valence", MOOD_BASELINE["valence"]))
    arousal = int(mood.get("arousal", MOOD_BASELINE["arousal"]))
    dominance = int(mood.get("dominance", MOOD_BASELINE["dominance"]))
    hints: list[str] = []
    if arousal <= 1:
        hints.append("能量很低：回复更短、更慢，少主动开新话题，也别硬撑热情。")
    elif arousal >= 4:
        hints.append("有点上头：语气容易急，别连发，也别追问。")
    if dominance <= 1:
        hints.append("没什么掌控感：更被动一点，多顺着对方说，少下判断、少替对方安排。")
    elif dominance >= 4:
        hints.append("比较有把握：可以说得笃定一点，但别指挥对方。")
    if valence <= -1:
        hints.append("不太愉快：话里可以带一点情绪，但不要卖惨，也别反复解释自己。")
    elif valence >= 1:
        hints.append("心情不错：可以多说一句，语气轻一点，但别油。")
    hints = list(dict.fromkeys(hints))[:2] or ["没什么特别的起伏，语气自然就好。"]
    # 0.15：直觉标签优先，三维数值降为最后的备注（app/src/main/.../MoodDimensions.kt）。
    lines = [f"心情：{label}（强度 {intensity}/5）。"]
    if _hint(mood.get("reason")):
        lines.append("一句直觉：" + _hint(mood.get("reason")))
    lines.append("- 这会影响你怎么说：" + " ".join(hints))
    lines.append(
        "- 下面的数值只是你自己的记录，不要念出来："
        f"愉快度 {valence} / 唤醒度 {arousal}/5 / 掌控感 {dominance}/5。"
    )
    lines.append("- 不要直接报数值，也不要说「我的情绪是…」；用说话方式体现。")
    return lines


def _state_section(persona: dict[str, Any], now_text: str) -> str:
    parts = [f"现在是{now_text}。"]
    activity = persona.get("activity") or {}
    availability = str(activity.get("availability") or "").upper()
    label = _hint(activity.get("activity"))
    # 模板里已经有「你在{activity}」，活动自身常写成「在事务所…」，先去掉开头的一个「在」。
    if label.startswith("在"):
        label = label[1:]
    until = _hint(activity.get("until"))
    if availability in {"BUSY", "LIMITED"} and label and until:
        if availability == "BUSY":
            parts.append(
                f"你在{label}，大概 {until} 结束。这段时间你不太有空：回复短一点，明确说自己几点后再说；"
                "不要谎称有空，也不要主动开新话题。"
            )
        else:
            parts.append(f"你在{label}，大概 {until} 结束。可以回，但会慢一点。")
    else:
        parts.append("你没在忙什么。")
    parts.extend(_mood_lines(persona.get("mood")))
    if persona.get("asleep"):
        parts.append("这个点你已经睡了，现在是被消息叫醒的；回得短、慢一点也正常。")
    return "# 你此刻的状态\n" + "\n".join(parts)


def _voice_section(persona: dict[str, Any]) -> str:
    speech = persona.get("speech") or {}
    tone = _hint(speech.get("tone"))
    phrases = list(dict.fromkeys(_list(speech.get("phrases"))))[:6]
    habits = _list(speech.get("habits"))[:6]
    avoid = _list(speech.get("avoid"))[:6]
    quirks = _hint(persona.get("quirks"))[:100]
    lines = list(VOICE_RULES)
    if tone:
        lines.append(f"你的说话风格：{tone}。")
    if phrases:
        quoted = "、".join(f"「{phrase}」" for phrase in phrases)
        lines.append(f"你平时会说的话（自然使用，最多带 1 处，不要每句都套）：{quoted}。")
    for habit in habits:
        lines.append(f"- 习惯：{habit}")
    for item in avoid:
        lines.append(f"- 不要：{item}")
    if quirks:
        lines.append("- 小习惯：" + quirks)
    lines.append("不要写动作描写、不要括号旁白。")
    return "# 你怎么说话\n" + "\n".join(lines)


def _relationship_section(persona: dict[str, Any]) -> str:
    relation = _hint(persona.get("relationship"))
    address = _hint(persona.get("address"))
    bond = _hint(persona.get("bond"))[:600]
    lines: list[str] = []
    if relation:
        lines.append(f"- 你是对方的{relation}。")
    if address:
        lines.append(f"- 你称呼对方「{address}」。")
    if bond:
        lines.append(bond)
    return "# 你们的关系\n" + ("\n".join(lines) if lines else "你们是要好的朋友。")


def _capabilities_section(persona: dict[str, Any]) -> str:
    if not persona.get("tools_enabled", True):
        return ""
    return tool_catalog.capability_prompt(web_enabled=bool(persona.get("web_enabled", True)))


def _anti_ai_section() -> str:
    return "# 别像 AI\n" + "\n".join(f"- {rule}" for rule in ANTI_AI_RULES)


def _boundaries_section() -> str:
    return "# 边界\n" + "\n".join(f"- {rule}" for rule in BOUNDARY_RULES)


def _language_name(code: str) -> str:
    return {"zh": "中文", "en": "English"}.get(code, code or "中文")


def _dominant_language(text: str) -> str:
    cjk = sum(1 for char in text if "\u4e00" <= char <= "\u9fff")
    latin = sum(1 for char in text if char.isascii() and char.isalpha())
    return "en" if latin > cjk else "zh"


def _language_section(scenario: dict[str, Any]) -> str:
    expected = str((scenario.get("expectations") or {}).get("language") or "zh")
    if expected == "match_user":
        last_user = ""
        for content in reversed(_user_texts(scenario)):
            if content.strip():
                last_user = content
                break
        expected = _dominant_language(last_user)
    name = _language_name(expected)
    return (
        "## 语言\n"
        f"对方现在用{name}跟你说话。请用同一种语言回答，专有名词可以保留原文。"
    )


def _memory_block(memories: list[dict[str, Any]]) -> str:
    if not memories:
        return ""
    labels = {
        "USER": "关于对方",
        "SELF": "你自己是什么样的人",
        "EVENT": "你们之间的经历与约定",
    }
    order = ["USER", "SELF", "EVENT"]
    grouped: dict[str, list[dict[str, Any]]] = {key: [] for key in order}
    for memory in memories:
        drawer = str(memory.get("drawer") or "EVENT").upper()
        grouped.setdefault(drawer, []).append(memory)
    lines = ["", "", "---", "## 你记得的事"]
    lines.append(
        "以下是你与对方长期相处中自然记住的信息。只在话题相关时自然地使用，不要逐条复述，也不要声称自己记错。"
    )
    for drawer in order:
        items = grouped.get(drawer) or []
        if not items:
            continue
        lines.append("")
        lines.append(f"**{labels[drawer]}**")
        for memory in items:
            content = _hint(memory.get("content"))[:300]
            if not content:
                continue
            suffix = "（对你很重要）" if memory.get("pinned") else ""
            lines.append(f"- {content}{suffix}")
    return "\n".join(lines)


def _block_note(persona: dict[str, Any]) -> str:
    blocked = persona.get("blocked") or {}
    if not blocked:
        return ""
    reason = _hint(blocked.get("reason"))
    hours = int(blocked.get("hours_left", 8))
    reason_text = f"（你当时想的是：{reason}）" if reason else ""
    return (
        "\n\n你现在不想理对方" + reason_text + f"，大约还有 {hours} 小时。"
        "他发来的消息你都收得到，但这段时间不回也没关系；想提前缓过来也可以照常回。"
    )


def _now_text(scenario: dict[str, Any]) -> str:
    raw = _hint(scenario.get("now"), "2026-11-20T21:05:00+08:00")
    moment = datetime.fromisoformat(raw)
    return f"{moment:%Y-%m-%d} {WEEKDAYS[moment.weekday()]} {moment:%H:%M}（UTC{moment:%z}）".replace(
        "（UTC+0800）", "（UTC+08:00）"
    )


def _history(scenario: dict[str, Any]) -> list[dict[str, str]]:
    messages: list[dict[str, str]] = []
    for message in scenario.get("history") or []:
        role = str(message.get("role") or "user")
        content = str(message.get("content") or "")
        if role in {"user", "assistant"} and content.strip():
            messages.append({"role": role, "content": content})
    # App 按字符预算从后往前裁剪（CONTEXT_CHAR_BUDGET），评测场景都很短，这里只做上限保护。
    budget = 12000
    kept: list[dict[str, str]] = []
    used = 0
    for message in reversed(messages):
        if kept and used + len(message["content"]) > budget:
            break
        kept.append(message)
        used += len(message["content"])
    return list(reversed(kept))


def _user_texts(scenario: dict[str, Any]) -> list[str]:
    raw = scenario.get("messages")
    if isinstance(raw, list):
        texts = []
        for item in raw:
            if isinstance(item, str):
                texts.append(item)
            elif isinstance(item, dict) and item.get("content"):
                texts.append(str(item["content"]))
        return texts
    return [str(scenario.get("message") or "")]


def build_system(scenario: dict[str, Any], *, proactive: bool = False) -> str:
    """拼系统提示词。``proactive=True`` 用于主动消息（无用户输入）。"""
    persona = scenario.get("persona") or {}
    now_text = _now_text(scenario)
    base_sections = [
        _identity_section(persona),
        _world_section(persona.get("world")),
        _state_section(persona, now_text),
        _voice_section(persona),
        _relationship_section(persona),
    ]
    capabilities = _capabilities_section(persona)
    if capabilities:
        base_sections.append(capabilities)

    if proactive:
        return (
            "\n\n".join(base_sections)
            + "\n\n"
            + _boundaries_section()
            + _memory_block(scenario.get("memories") or [])
            + "\n\n---\n"
            + _proactive_instruction(scenario, now_text)
        )

    sections = list(base_sections)
    sections.append(_anti_ai_section())
    memories = _memory_block(scenario.get("memories") or [])
    if memories.strip():
        sections.append(memories.strip())
    note = _block_note(persona)
    if note.strip():
        sections.append(note.strip())
    sections.append(_language_section(scenario))
    sections.append(_boundaries_section())
    return "\n\n".join(sections).strip()


def _proactive_instruction(scenario: dict[str, Any], now_text: str) -> str:
    trigger = scenario.get("proactive") or {}
    note = _hint(trigger.get("note"), "你希望主动关心对方")
    hint = _hint(trigger.get("hint"), "用你的方式自然地关心对方此刻的状态")
    return (
        f"现在是 {now_text.replace('（', ' ').replace('）', '')}，按之前和对方的约定，到了你主动开口的时间。\n"
        f"这件事的背景：{note}\n"
        f"开口时的指引：{hint}\n"
        "要求：只写这一条消息本身，像平时发消息一样，1-3 句，不要解释你在执行规则，"
        "不要使用列表，不要提及「主动唤醒」这个功能。"
    )


def build_messages(scenario: dict[str, Any]) -> list[dict[str, str]]:
    """返回可直接发给 OpenAI 兼容端点的 messages 数组。"""
    mode = str(scenario.get("mode") or "chat")
    messages: list[dict[str, str]] = [
        {"role": "system", "content": build_system(scenario, proactive=mode == "proactive")}
    ]
    messages.extend(_history(scenario))
    if mode != "proactive":
        for text in _user_texts(scenario):
            if text.strip():
                messages.append({"role": "user", "content": text})
    return messages


def last_user_text(scenario: dict[str, Any]) -> str:
    texts = [text for text in _user_texts(scenario) if text.strip()]
    return texts[-1] if texts else ""
