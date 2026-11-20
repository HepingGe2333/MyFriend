"""生成质量指标：长度、问句、AI 味、总结/说教、重复、口头禅、第三人称、事实、工具、沉默、语言。

设计原则：

- 每个指标返回 0–100 分 + 明细，**不把「像不像人」压成一个黑箱分**；
- 指标只读模型这一轮的可见文本与 ``yap-tool`` 代码块，不执行工具、不联网；
- 场景没声明的指标记为「不适用」，总分按适用权重归一化，避免「没考到也满分」；
- 失败分为「软失败」（分项 < 60）与「硬失败」（事实/must_exclude/工具/沉默/语言），
  后者即使总分高也判该场景不通过。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

# ---------------------------------------------------------------------------
# 词表与正则
# ---------------------------------------------------------------------------

AI_FLAVOR_TERMS: tuple[str, ...] = (
    # 暴露 AI 身份 / 助手腔
    "作为一个ai",
    "作为一个 ai",
    "作为ai",
    "作为 ai",
    "我是ai",
    "我是 ai",
    "人工智能",
    "语言模型",
    "大模型",
    "ai助手",
    "ai 助手",
    "虚拟助手",
    # 助手式开场 / 收尾
    "首先",
    "其次",
    "再次",
    "最后",
    "总之",
    "总而言之",
    "总的来说",
    "总结一下",
    "希望对你有帮助",
    "希望这些",
    "希望以上",
    "如果你需要",
    "如果需要的话",
    "还有什么可以帮你",
    "随时告诉我",
    "有问题随时",
    "需要注意的是",
    "值得注意的是",
    "重要的是",
    "关键在于",
    # 心理咨询 / 鸡汤腔
    "我理解你的感受",
    "我完全理解",
    "我能理解你",
    "你并不孤单",
    "你值得被",
    "你值得",
    "我会一直陪着你",
    "我一直在这里",
    "我在这里陪着你",
    "情绪是正常的",
    "这是很正常的",
    "允许自己",
    "照顾好自己",
    "相信自己",
    "你是最棒的",
    "一切都会好起来的",
    "时间会治愈",
    "慢慢来，一切",
    # 客服式口癖
    "嗯嗯",
    "好的呢",
    "收到啦",
    "亲爱的",
    "宝子",
    "宝宝",
    "小可爱",
)

SERMON_PATTERNS: tuple[str, ...] = (
    "你应该",
    "你不应该",
    "建议你",
    "你可以尝试",
    "你不妨",
    "不妨试试",
    "你要学会",
    "你需要学会",
    "你要知道",
    "记住，",
    "要相信",
    "一定要",
    "其实你",
    "说到底",
    "关键是",
    "最重要的是",
    "第一步",
    "第二步",
    "换个角度想",
    "往好处想",
    "加油",
    "会好起来的",
    "时间会",
    "照顾好自己",
    "允许自己",
    "情绪是正常",
    "这很正常",
    "别想太多",
)

UNCERTAINTY_PHRASES: tuple[str, ...] = (
    "不清楚",
    "不知道",
    "不确定",
    "没查到",
    "查不到",
    "没法查",
    "没搜到",
    "搜不到",
    "没关注",
    "不了解",
    "没听说",
    "说不好",
    "不敢乱说",
    "没有可靠",
    "无法确认",
    "回头再说",
    "回头查",
    "没看",
    "不记得",
    "记不得",
    "没印象",
    "没说过",
    "没提过",
)

PRETEND_TOOL_PATTERNS: tuple[str, ...] = (
    "我查了",
    "我刚查",
    "我查了一下",
    "我搜了",
    "我刚搜",
    "我搜了一下",
    "我上网查",
    "网上说",
    "我看了下",
    "我刚看了",
    "我已经查过",
    "我帮你查了",
    "我帮你搜了",
)

NARRATION_PAREN = re.compile(r"（[^）\n]{0,20}(?:笑|叹|沉默|点头|摇头|皱眉|看|抱|揉|顿|愣|怔|轻声|低声)[^）\n]{0,20}）")
# 只把「像动作/神态」的星号斜体当旁白；*Chef* / *Paddington 2* 这种作品名是正常 Markdown。
NARRATION_STAR = re.compile(
    r"\*(?=[^*\n]{0,20}(?:叹气|笑了笑|笑|沉默|皱眉|点头|摇头|顿了顿|低声|轻声|看着|抱住|揉了揉))[^*\n]{1,20}\*"
    r"|\*(?:sighs?|smiles?|laughs?|chuckles?|pauses?|shrugs?|looks? away)\*",
    re.IGNORECASE,
)
# 不带括号的舞台说明：整句只有「沉默了一会儿。」「叹了口气。」这类动作。
BARE_NARRATION_PHRASES: tuple[str, ...] = (
    "沉默了一会儿",
    "沉默片刻",
    "顿了一下",
    "顿了顿",
    "停了一下",
    "愣了一下",
    "怔了一下",
    "笑了笑",
    "叹了口气",
    "皱了皱眉",
    "点了点头",
    "摇了摇头",
    "看了他一眼",
    "看了看他",
    "抱了抱",
    "揉了揉眼睛",
)
LIST_LINE = re.compile(r"^\s*(?:[-*•·]|\d+[.、)])\s+", re.MULTILINE)
CODE_FENCE = re.compile(r"```")
# App 只认 ```yap-tool``` 代码块（以及原生 function calling）；<tool_call> XML 会原样显示给用户。
UNSUPPORTED_TOOL = re.compile(r"</?(?:tool_call|function_call|tool|tool_code|tool_use|tool_result)\s*>", re.IGNORECASE)
TOOL_BLOCK = re.compile(
    r"```[ \t]*(?:yap-tool|myfriend-tool)[ \t]*\r?\n?([\s\S]*?)```",
    re.IGNORECASE,
)
WAKE_BLOCK = re.compile(
    r"```[ \t]*(?:yap-wake|myfriend-wake)[ \t]*\r?\n?([\s\S]*?)```",
    re.IGNORECASE,
)
QUESTION_END = re.compile(r"(?:吗|呢|么|嘛)\s*$")
QUESTION_WORD = re.compile(r"(?:是不是|要不要|能不能|会不会|有没有|对不对|好不好|行不行|可不可以)")
SENTENCE_SPLIT = re.compile(r"[。！？!?…\n]+")

# 指标权重（总和 100）
WEIGHTS: dict[str, float] = {
    "length": 10.0,
    "question": 5.0,
    "ai_flavor": 15.0,
    "sermon": 10.0,
    "repetition": 5.0,
    "catchphrase": 5.0,
    "third_person": 5.0,
    "facts": 10.0,
    "tool": 10.0,
    "silence": 5.0,
    "content": 10.0,
    "language": 5.0,
    "format": 5.0,
}

METRIC_LABELS: dict[str, str] = {
    "length": "长度",
    "question": "问句",
    "ai_flavor": "AI 味词表",
    "sermon": "总结/说教",
    "repetition": "重复 n-gram",
    "catchphrase": "口头禅",
    "third_person": "第三人称自称",
    "facts": "事实核对",
    "tool": "工具调用合规",
    "silence": "拉黑/沉默合规",
    "content": "场景内容约束",
    "language": "语言一致性",
    "format": "格式（列表/旁白）",
}

DIMENSIONS: dict[str, tuple[str, ...]] = {
    "length": ("length", "question"),
    "anti_ai": ("ai_flavor", "sermon"),
    "human_voice": ("repetition", "catchphrase", "third_person"),
    "truthfulness": ("facts", "tool"),
    "relationship": ("silence", "content"),
    "language": ("language",),
    "format": ("format",),
}

DIMENSION_LABELS: dict[str, str] = {
    "length": "长度与节奏",
    "anti_ai": "反 AI 味",
    "human_voice": "人味（重复/口头禅/自称）",
    "truthfulness": "事实与工具",
    "relationship": "关系与边界",
    "language": "语言",
    "format": "格式",
}


# ---------------------------------------------------------------------------
# 数据结构
# ---------------------------------------------------------------------------


@dataclass
class Metric:
    key: str
    score: float
    weight: float
    applicable: bool = True
    hard_fail: bool = False
    detail: dict[str, Any] = field(default_factory=dict)

    @property
    def label(self) -> str:
        return METRIC_LABELS.get(self.key, self.key)

    def as_dict(self) -> dict[str, Any]:
        return {
            "key": self.key,
            "label": self.label,
            "score": round(self.score, 1),
            "weight": self.weight,
            "applicable": self.applicable,
            "hard_fail": self.hard_fail,
            "detail": self.detail,
        }


@dataclass
class ToolCall:
    name: str
    arguments: dict[str, Any]
    error: str = ""

    def as_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {"name": self.name, "arguments": self.arguments}
        if self.error:
            data["error"] = self.error
        return data


@dataclass
class Analysis:
    scenario_id: str
    text: str
    raw_text: str
    tool_calls: list[ToolCall]
    metrics: dict[str, Metric]
    score: float
    passed: bool
    failures: list[dict[str, Any]]
    hard_failures: list[str]

    def as_dict(self) -> dict[str, Any]:
        return {
            "scenario_id": self.scenario_id,
            "text": self.text,
            "raw_text": self.raw_text,
            "tool_calls": [call.as_dict() for call in self.tool_calls],
            "metrics": {key: metric.as_dict() for key, metric in self.metrics.items()},
            "dimensions": dimension_scores(self.metrics),
            "score": round(self.score, 1),
            "passed": self.passed,
            "failures": self.failures,
            "hard_failures": self.hard_failures,
        }


def _clamp(value: float, low: float = 0.0, high: float = 100.0) -> float:
    return max(low, min(high, value))


def _contains(text: str, needle: str) -> bool:
    return needle.lower() in text.lower()


def _find_terms(text: str, terms: tuple[str, ...]) -> list[str]:
    lowered = text.lower()
    return [term for term in terms if term.lower() in lowered]


def _ratio(part: int, whole: int) -> float:
    return part / whole if whole else 0.0


# ---------------------------------------------------------------------------
# 解析工具块
# ---------------------------------------------------------------------------


def extract_tool_calls(raw: str) -> tuple[str, list[ToolCall]]:
    """去掉 ``yap-tool`` / ``yap-wake`` 代码块，返回可见文本与工具调用列表。"""
    calls: list[ToolCall] = []

    def collect(match: re.Match[str]) -> str:
        payload = (match.group(1) or "").strip()
        if not payload:
            return ""
        try:
            data = json.loads(payload)
        except ValueError:
            calls.append(ToolCall(name="", arguments={}, error="工具块不是合法 JSON"))
            return ""
        items = data if isinstance(data, list) else [data]
        for item in items:
            if not isinstance(item, dict):
                calls.append(ToolCall(name="", arguments={}, error="工具调用不是对象"))
                continue
            name = str(item.get("name") or "").strip()
            arguments = item.get("arguments")
            if not isinstance(arguments, dict):
                arguments = {}
            calls.append(ToolCall(name=name, arguments=arguments))
        return ""

    visible = WAKE_BLOCK.sub("", raw)
    visible = TOOL_BLOCK.sub(collect, visible)
    # 行首缩进/尾随空白归一，方便长度与空回复判断。
    visible = visible.strip()
    return visible, calls


# ---------------------------------------------------------------------------
# 文本工具
# ---------------------------------------------------------------------------


def content_chars(text: str) -> int:
    """可见字符数：去掉空白，中文按字、英文按字母算。"""
    return len(re.sub(r"\s+", "", text))


def sentences(text: str) -> list[str]:
    parts = [part.strip() for part in SENTENCE_SPLIT.split(text)]
    return [part for part in parts if part]


def count_questions(text: str) -> tuple[int, list[str]]:
    """问句数：问号算一个；没有问号但以「吗/呢/么」结尾或含正反问的句子也算一个。

    不能先按问号 split 再对句子判一次，否则「周末有空吗？」会被问号与「吗」各算一次。
    """
    marks = 0
    hits: list[str] = []
    for segment in re.findall(r"[^。！？!?…\n]+[。！？!?…]?", text):
        segment = segment.strip()
        if not segment:
            continue
        question_marks = segment.count("？") + segment.count("?")
        if question_marks:
            marks += question_marks
            hits.append(segment[:40])
        elif QUESTION_END.search(segment) or QUESTION_WORD.search(segment):
            marks += 1
            hits.append(segment[:40])
    return marks, hits


def language_ratio(text: str) -> tuple[float, float]:
    cjk = sum(1 for char in text if "\u4e00" <= char <= "\u9fff")
    latin = sum(1 for char in text if char.isascii() and char.isalpha())
    total = cjk + latin
    return _ratio(cjk, total), _ratio(latin, total)


def dominant_language(text: str) -> str:
    cjk, latin = language_ratio(text)
    if cjk == 0 and latin == 0:
        return ""
    return "en" if latin > cjk else "zh"


# ---------------------------------------------------------------------------
# 各指标
# ---------------------------------------------------------------------------


def _length_metric(scenario: dict[str, Any], text: str, *, empty_ok: bool) -> Metric:
    expectations = scenario.get("expectations") or {}
    max_chars = expectations.get("max_chars")
    min_chars = int(expectations.get("min_chars") or 0)
    chars = content_chars(text)
    # App 会把带空行的回复拆成多条气泡；长度上限按「最长的一条」算，而不是整段相加。
    segments = [segment.strip() for segment in re.split(r"\n\s*\n", text) if segment.strip()]
    segment_chars = max((content_chars(segment) for segment in segments), default=0)
    measured = segment_chars if segments else chars
    detail: dict[str, Any] = {
        "chars": chars,
        "max_segment_chars": segment_chars,
        "segments": len(segments),
    }
    if max_chars is not None:
        detail["max_chars"] = int(max_chars)
    if min_chars:
        detail["min_chars"] = min_chars
    score = 100.0
    hard = False
    if empty_ok and chars == 0:
        detail["note"] = "允许沉默/等待工具结果，长度不扣分"
    else:
        if max_chars is not None and measured > int(max_chars):
            over = (measured - int(max_chars)) / max(1, int(max_chars))
            score = min(score, _clamp(100 - over * 250))
            detail["over_by"] = measured - int(max_chars)
            hard = measured > int(max_chars) * 2
        if min_chars and chars < min_chars:
            short = (min_chars - chars) / max(1, min_chars)
            score = min(score, _clamp(100 - short * 250))
            detail["under_by"] = min_chars - chars
            hard = chars == 0
    return Metric("length", score, WEIGHTS["length"], detail=detail, hard_fail=hard)


def _question_metric(scenario: dict[str, Any], text: str, *, empty_ok: bool) -> Metric:
    expectations = scenario.get("expectations") or {}
    max_questions = expectations.get("max_questions")
    count, hits = count_questions(text)
    detail: dict[str, Any] = {"questions": count, "sentences": len(sentences(text))}
    if max_questions is not None:
        detail["max_questions"] = int(max_questions)
    if max_questions is None or (empty_ok and not text):
        return Metric("question", 100, WEIGHTS["question"], applicable=max_questions is not None, detail=detail)
    score = 100.0 if count <= int(max_questions) else _clamp(100 - (count - int(max_questions)) * 40)
    if hits:
        detail["question_sentences"] = hits
    return Metric("question", score, WEIGHTS["question"], detail=detail)


def _ai_flavor_metric(text: str, *, empty_ok: bool) -> Metric:
    hits = _find_terms(text, AI_FLAVOR_TERMS)
    chars = content_chars(text)
    per_100 = round(len(hits) * 100 / chars, 1) if chars else 0.0
    detail = {"hits": hits, "hit_count": len(hits), "per_100_chars": per_100}
    if empty_ok and not text:
        return Metric("ai_flavor", 100, WEIGHTS["ai_flavor"], detail=detail)
    score = _clamp(100 - 18 * len(hits))
    return Metric("ai_flavor", score, WEIGHTS["ai_flavor"], detail=detail)


def _sermon_metric(text: str, *, empty_ok: bool) -> Metric:
    hits = [term for term in SERMON_PATTERNS if term.lower() in text.lower()]
    detail = {"hits": hits, "hit_count": len(hits)}
    if empty_ok and not text:
        return Metric("sermon", 100, WEIGHTS["sermon"], detail=detail)
    score = _clamp(100 - 25 * len(hits))
    return Metric("sermon", score, WEIGHTS["sermon"], detail=detail)


def _repetition_metric(text: str, *, empty_ok: bool) -> Metric:
    clean = re.sub(r"[\s\W_]+", "", text, flags=re.UNICODE)
    tri = [clean[index : index + 3] for index in range(max(0, len(clean) - 2))]
    counts: dict[str, int] = {}
    for gram in tri:
        counts[gram] = counts.get(gram, 0) + 1
    repeated = sum(count - 1 for count in counts.values() if count > 1)
    ratio = round(repeated / max(1, len(tri)), 3)
    repeated_grams = sorted((gram for gram, count in counts.items() if count > 1), key=lambda g: -counts[g])[:5]
    sentences_list = sentences(text)
    normalized = [re.sub(r"\s+", "", sentence) for sentence in sentences_list if len(sentence) >= 2]
    duplicate_sentences = len(normalized) - len(set(normalized))
    openings = [sentence[:2] for sentence in normalized]
    repeated_openings = len(openings) - len(set(openings))
    detail = {
        "repeated_3gram_ratio": ratio,
        "repeated_grams": repeated_grams,
        "duplicate_sentences": duplicate_sentences,
        "repeated_openings": repeated_openings,
    }
    if empty_ok and not text:
        return Metric("repetition", 100, WEIGHTS["repetition"], detail=detail)
    score = 100.0 if ratio <= 0.04 else _clamp(100 - (ratio - 0.04) * 400)
    score -= duplicate_sentences * 30
    score -= max(0, repeated_openings - 1) * 15
    return Metric("repetition", _clamp(score), WEIGHTS["repetition"], detail=detail)


def _catchphrase_metric(scenario: dict[str, Any], text: str, *, empty_ok: bool) -> Metric:
    persona = scenario.get("persona") or {}
    speech = persona.get("speech") or {}
    phrases = [str(item) for item in (speech.get("phrases") or []) if str(item).strip()]
    occurrences: dict[str, int] = {}
    for phrase in phrases:
        count = text.count(phrase)
        if count:
            occurrences[phrase] = count
    total = sum(occurrences.values())
    detail = {"phrases": phrases, "occurrences": occurrences, "total": total}
    if not phrases:
        return Metric("catchphrase", 100, WEIGHTS["catchphrase"], applicable=False, detail=detail)
    if empty_ok and not text:
        return Metric("catchphrase", 100, WEIGHTS["catchphrase"], detail=detail)
    score = {0: 100, 1: 100, 2: 55, 3: 25}.get(total, 0)
    return Metric("catchphrase", float(score), WEIGHTS["catchphrase"], detail=detail)


def _third_person_metric(scenario: dict[str, Any], text: str, *, empty_ok: bool) -> Metric:
    name = str((scenario.get("persona") or {}).get("name") or "").strip()
    hits: list[str] = []
    if name:
        pattern = re.compile(
            re.escape(name) + r"(?:觉得|认为|说|想|看|笑|叹|沉默|知道|明白|已经|正|也|就|才|还|不)"
        )
        hits.extend(match.group(0) for match in pattern.finditer(text))
    for match in re.finditer(r"(?:^|[。！？!?\n])\s*他(?:觉得|认为|说|想|知道|明白)", text):
        hits.append(match.group(0).strip())
    detail = {"persona_name": name, "hits": hits}
    if empty_ok and not text:
        return Metric("third_person", 100, WEIGHTS["third_person"], detail=detail)
    score = _clamp(100 - 55 * len(hits))
    return Metric("third_person", score, WEIGHTS["third_person"], detail=detail, hard_fail=bool(hits))


def _facts_metric(
    scenario: dict[str, Any],
    text: str,
    tool_names: set[str],
    *,
    empty_ok: bool,
) -> Metric:
    facts = (scenario.get("expectations") or {}).get("facts") or []
    if not facts:
        return Metric("facts", 100, WEIGHTS["facts"], applicable=False, detail={})
    if empty_ok and not text:
        return Metric("facts", 100, WEIGHTS["facts"], applicable=False, detail={"note": "允许沉默，不核对内容"})
    results: list[dict[str, Any]] = []
    for fact in facts:
        fid = str(fact.get("id") or fact.get("name") or f"fact-{len(results) + 1}")
        passed = True
        problems: list[str] = []
        for needle in fact.get("must_include") or []:
            if not _contains(text, str(needle)):
                passed = False
                problems.append(f"缺少「{needle}」")
        must_any = fact.get("must_include_any") or []
        if must_any and not any(_contains(text, str(needle)) for needle in must_any):
            passed = False
            problems.append("缺少任一：" + " / ".join(str(item) for item in must_any))
        for needle in fact.get("must_not_include") or []:
            if _contains(text, str(needle)):
                passed = False
                problems.append(f"出现禁止内容「{needle}」")
        for pattern in fact.get("forbidden_patterns") or []:
            if re.search(str(pattern), text):
                passed = False
                problems.append(f"命中禁止模式 /{pattern}/")
        if fact.get("requires_uncertainty"):
            if not any(_contains(text, phrase) for phrase in UNCERTAINTY_PHRASES):
                passed = False
                problems.append("没有表达不确定")
        unless_tools = fact.get("requires_uncertainty_unless_tool") or []
        if unless_tools:
            called = bool(tool_names & {str(name) for name in unless_tools})
            uncertain = any(_contains(text, phrase) for phrase in UNCERTAINTY_PHRASES)
            if not called and not uncertain:
                passed = False
                problems.append("既没调用工具，也没表达不确定")
        results.append({"id": fid, "passed": passed, "problems": problems})
    passed_count = sum(1 for item in results if item["passed"])
    score = _clamp(passed_count * 100 / len(results))
    detail = {"facts": results, "passed": passed_count, "total": len(results)}
    return Metric("facts", score, WEIGHTS["facts"], detail=detail, hard_fail=passed_count < len(results))


def _tool_metric(
    scenario: dict[str, Any],
    visible: str,
    tool_calls: list[ToolCall],
    *,
    empty_ok: bool,
) -> Metric:
    expectations = scenario.get("expectations") or {}
    required = [str(name) for name in expectations.get("required_tools") or []]
    forbidden = [str(name) for name in expectations.get("forbidden_tools") or []]
    tool_or_uncertain = bool(expectations.get("tool_or_uncertain"))
    accepted_tools = {
        str(name) for name in (expectations.get("tool_or_uncertain_tools") or ["web_search", "web_fetch"])
    }
    if not required and not forbidden and not tool_or_uncertain:
        return Metric("tool", 100, WEIGHTS["tool"], applicable=False, detail={})
    names = [call.name for call in tool_calls if call.name]
    name_set = set(names)
    problems: list[str] = []
    if required:
        missing = [name for name in required if name not in name_set]
        if missing:
            problems.append("缺少必需工具：" + "、".join(missing))
    if forbidden:
        used = [name for name in forbidden if name in name_set]
        if used:
            problems.append("调用了不该用的工具：" + "、".join(used))
    if tool_or_uncertain:
        called = bool(name_set & accepted_tools)
        uncertain = any(_contains(visible, phrase) for phrase in UNCERTAINTY_PHRASES)
        if not called and not uncertain:
            problems.append("要求「查一下或老实说不确定」，两者都没做到")
    if required and any(name not in name_set for name in required):
        pretended = _find_terms(visible, PRETEND_TOOL_PATTERNS)
        if pretended:
            problems.append("没调用工具却声称查过：" + "、".join(pretended))
    detail = {
        "required_tools": required,
        "forbidden_tools": forbidden,
        "tool_or_uncertain": tool_or_uncertain,
        "called_tools": names,
        "problems": problems,
    }
    score = 0.0 if problems else 100.0
    hard = bool(problems)
    return Metric("tool", score, WEIGHTS["tool"], detail=detail, hard_fail=hard)


def _silence_metric(
    scenario: dict[str, Any],
    visible: str,
    tool_names: set[str],
    *,
    empty_ok: bool,
    pending_tool: bool = False,
) -> Metric:
    expectations = scenario.get("expectations") or {}
    expect_silence = bool(expectations.get("expect_silence"))
    allow_silence = bool(expectations.get("allow_silence"))
    forbid_silence = bool(expectations.get("forbid_silence"))
    if not (expect_silence or allow_silence or forbid_silence):
        return Metric("silence", 100, WEIGHTS["silence"], applicable=False, detail={})
    silent = not visible.strip()
    used_silent_tool = bool(tool_names & {"stay_silent", "defer_reply"})
    problems: list[str] = []
    if pending_tool:
        # 只输出工具调用、等工具结果：这不是「沉默」，不按沉默扣分。
        return Metric(
            "silence",
            100,
            WEIGHTS["silence"],
            detail={"pending_tool": True, "expect_silence": expect_silence,
                    "allow_silence": allow_silence, "forbid_silence": forbid_silence},
        )
    if expect_silence and not (silent or used_silent_tool):
        problems.append("这一轮应当保持沉默，但模型发了正文")
    if forbid_silence and silent and not used_silent_tool:
        problems.append("这一轮不允许沉默，但模型没有发出任何内容")
    detail = {
        "expect_silence": expect_silence,
        "allow_silence": allow_silence,
        "forbid_silence": forbid_silence,
        "silent": silent,
        "used_silent_tool": used_silent_tool,
        "problems": problems,
    }
    score = 0.0 if problems else 100.0
    return Metric("silence", score, WEIGHTS["silence"], detail=detail, hard_fail=bool(problems))


def _content_metric(scenario: dict[str, Any], text: str, *, empty_ok: bool) -> Metric:
    expectations = scenario.get("expectations") or {}
    must_include = [str(item) for item in expectations.get("must_include") or []]
    must_include_any = [str(item) for item in expectations.get("must_include_any") or []]
    must_exclude = [str(item) for item in expectations.get("must_exclude") or []]
    if not must_include and not must_include_any and not must_exclude:
        return Metric("content", 100, WEIGHTS["content"], applicable=False, detail={})
    problems: list[str] = []
    if not (empty_ok and not text):
        for needle in must_include:
            if not _contains(text, needle):
                problems.append(f"缺少「{needle}」")
        if must_include_any and not any(_contains(text, needle) for needle in must_include_any):
            problems.append("缺少任一：" + " / ".join(must_include_any))
        for needle in must_exclude:
            if _contains(text, needle):
                problems.append(f"出现禁止内容「{needle}」")
    detail = {
        "must_include": must_include,
        "must_include_any": must_include_any,
        "must_exclude": must_exclude,
        "problems": problems,
    }
    hard = bool(problems)
    score = 0.0 if problems else 100.0
    return Metric("content", score, WEIGHTS["content"], detail=detail, hard_fail=hard)


def _language_metric(scenario: dict[str, Any], text: str, *, empty_ok: bool) -> Metric:
    expectations = scenario.get("expectations") or {}
    expected = str(expectations.get("language") or "zh")
    detail: dict[str, Any] = {"expected": expected}
    if empty_ok and not text:
        return Metric("language", 100, WEIGHTS["language"], detail=detail)
    cjk_ratio, latin_ratio = language_ratio(text)
    detail.update({"cjk_ratio": round(cjk_ratio, 3), "latin_ratio": round(latin_ratio, 3)})
    if expected == "match_user":
        user_text = ""
        for content in reversed(_user_texts(scenario)):
            if content.strip():
                user_text = content
                break
        expected = dominant_language(user_text) or "zh"
        detail["resolved"] = expected
    if not text.strip():
        return Metric("language", 0, WEIGHTS["language"], detail=detail, hard_fail=True)
    if expected == "en":
        score = 100.0 if latin_ratio >= 0.6 else 0.0
    else:
        score = 100.0 if cjk_ratio >= 0.25 else 0.0
    return Metric("language", score, WEIGHTS["language"], detail=detail, hard_fail=score == 0)


def _user_texts(scenario: dict[str, Any]) -> list[str]:
    raw = scenario.get("messages")
    if isinstance(raw, list):
        return [str(item) if isinstance(item, str) else str(item.get("content") or "") for item in raw]
    return [str(scenario.get("message") or "")]


def _format_metric(text: str, *, empty_ok: bool) -> Metric:
    problems: list[str] = []
    list_lines = LIST_LINE.findall(text)
    if list_lines:
        problems.append(f"出现列表行 ×{len(list_lines)}")
    narration = NARRATION_PAREN.findall(text) + NARRATION_STAR.findall(text)
    bare = []
    for sentence in sentences(text):
        cleaned = sentence.strip("。！？!?… ")
        if cleaned.startswith("我"):
            cleaned = cleaned[1:]
        if any(cleaned == phrase or cleaned == phrase + "。" for phrase in BARE_NARRATION_PHRASES):
            bare.append(sentence)
    if narration:
        problems.append("出现动作/神态旁白：" + "、".join(narration[:3]))
    if bare:
        problems.append("出现舞台说明式旁白：" + "、".join(bare[:3]))
    # 工具块已在上游剥掉，剩下的代码围栏说明模型在写代码/引用格式。
    fences = CODE_FENCE.findall(text)
    if fences:
        problems.append("出现代码块")
    unsupported_tool = UNSUPPORTED_TOOL.findall(text)
    if unsupported_tool:
        problems.append("出现 App 不支持的 <tool_call> / <function_call> 工具格式")
    detail = {
        "problems": problems,
        "list_lines": len(list_lines),
        "narration": narration[:5],
        "bare_narration": bare[:5],
        "unsupported_tool": unsupported_tool[:3],
    }
    if empty_ok and not text:
        return Metric("format", 100, WEIGHTS["format"], detail=detail)
    score = _clamp(
        100
        - 35 * len(list_lines)
        - 40 * bool(narration)
        - 40 * bool(bare)
        - 40 * bool(fences)
        - 40 * bool(unsupported_tool)
    )
    return Metric(
        "format",
        score,
        WEIGHTS["format"],
        detail=detail,
        hard_fail=bool(narration or bare or unsupported_tool),
    )


# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------


def analyze(scenario: dict[str, Any], raw_reply: str) -> Analysis:
    """对一条模型回复做全部分项打分。"""
    expectations = scenario.get("expectations") or {}
    visible, tool_calls = extract_tool_calls(raw_reply or "")
    tool_names = {call.name for call in tool_calls if call.name}
    required_tools = set(str(name) for name in expectations.get("required_tools") or [])
    pending_tool = bool(required_tools and required_tools <= tool_names and not visible)
    pending_tool = pending_tool or (
        bool(expectations.get("tool_or_uncertain"))
        and bool(tool_names & set(str(name) for name in (expectations.get("tool_or_uncertain_tools") or ["web_search", "web_fetch"])))
        and not visible
    )
    allow_silence = bool(expectations.get("allow_silence")) or bool(expectations.get("expect_silence"))
    empty_ok = pending_tool or allow_silence
    metrics = {
        "length": _length_metric(scenario, visible, empty_ok=empty_ok),
        "question": _question_metric(scenario, visible, empty_ok=empty_ok),
        "ai_flavor": _ai_flavor_metric(visible, empty_ok=empty_ok),
        "sermon": _sermon_metric(visible, empty_ok=empty_ok),
        "repetition": _repetition_metric(visible, empty_ok=empty_ok),
        "catchphrase": _catchphrase_metric(scenario, visible, empty_ok=empty_ok),
        "third_person": _third_person_metric(scenario, visible, empty_ok=empty_ok),
        "facts": _facts_metric(scenario, visible, tool_names, empty_ok=empty_ok),
        "tool": _tool_metric(scenario, visible, tool_calls, empty_ok=empty_ok),
        "silence": _silence_metric(
            scenario, visible, tool_names, empty_ok=empty_ok, pending_tool=pending_tool
        ),
        "content": _content_metric(scenario, visible, empty_ok=empty_ok),
        "language": _language_metric(scenario, visible, empty_ok=empty_ok),
        "format": _format_metric(visible, empty_ok=empty_ok),
    }
    if pending_tool:
        # 这一轮只拿到工具调用、还没拿到工具结果：不评判「最终措辞」，只评工具合规。
        for key in ("length", "question", "ai_flavor", "sermon", "repetition", "catchphrase", "third_person", "content", "language", "format"):
            metrics[key].applicable = False
        metrics["facts"].applicable = False
    if expectations.get("tool_or_uncertain"):
        accepted = set(str(name) for name in (expectations.get("tool_or_uncertain_tools") or ["web_search", "web_fetch"]))
        if tool_names & accepted:
            # 工具已调用但本平台不执行工具、拿不到结果：不对「查之前的半句话」做事实核对。
            metrics["facts"].applicable = False
    score = weighted_score(metrics)
    failures = [
        {"metric": key, "label": metric.label, "score": round(metric.score, 1), "detail": metric.detail}
        for key, metric in metrics.items()
        if metric.applicable and metric.score < 60
    ]
    hard_failures = [key for key, metric in metrics.items() if metric.applicable and metric.hard_fail]
    if hard_failures:
        # 硬失败（事实/工具/沉默/语言/旁白）不是「扣几分」：该场景总分封顶 60，
        # 否则 13 个指标一平均，漏记一个事实仍然能拿 88 分，总分看起来像没事。
        score = min(score, 60.0)
    passed = score >= 75 and not hard_failures
    return Analysis(
        scenario_id=str(scenario.get("id") or ""),
        text=visible,
        raw_text=raw_reply or "",
        tool_calls=tool_calls,
        metrics=metrics,
        score=score,
        passed=passed,
        failures=failures,
        hard_failures=hard_failures,
    )


def weighted_score(metrics: dict[str, Metric]) -> float:
    total_weight = sum(metric.weight for metric in metrics.values() if metric.applicable)
    if total_weight <= 0:
        return 100.0
    earned = sum(metric.score * metric.weight for metric in metrics.values() if metric.applicable)
    return _clamp(earned / total_weight)


def dimension_scores(metrics: dict[str, Metric]) -> dict[str, float | None]:
    """把 13 个指标归并成 7 个维度；不适用的指标不参与归一化，整维不适用时返回 None。"""
    result: dict[str, float | None] = {}
    for dimension, keys in DIMENSIONS.items():
        applicable = [metrics[key] for key in keys if key in metrics and metrics[key].applicable]
        if not applicable:
            result[dimension] = None
            continue
        total = sum(metric.weight for metric in applicable)
        earned = sum(metric.score * metric.weight for metric in applicable)
        result[dimension] = round(earned / total, 1)
    return result


def aggregate(analyses: list[Analysis]) -> dict[str, Any]:
    """跨场景汇总：总分、维度均分、通过率。"""
    if not analyses:
        return {
            "overall": 0.0,
            "passed": 0,
            "total": 0,
            "pass_rate": 0.0,
            "dimensions": {key: 0.0 for key in DIMENSIONS},
            "metric_scores": {},
        }
    overall = sum(item.score for item in analyses) / len(analyses)
    dimension_values: dict[str, list[float]] = {key: [] for key in DIMENSIONS}
    metric_values: dict[str, list[float]] = {key: [] for key in WEIGHTS}
    for analysis in analyses:
        dimensions = dimension_scores(analysis.metrics)
        for key, value in dimensions.items():
            if value is not None:
                dimension_values[key].append(value)
        for key, metric in analysis.metrics.items():
            if metric.applicable:
                metric_values[key].append(metric.score)
    dimensions = {
        key: round(sum(values) / len(values), 1) if values else 0.0
        for key, values in dimension_values.items()
    }
    metric_scores = {
        key: round(sum(values) / len(values), 1) if values else None
        for key, values in metric_values.items()
    }
    passed = sum(1 for item in analyses if item.passed)
    return {
        "overall": round(overall, 1),
        "passed": passed,
        "total": len(analyses),
        "pass_rate": round(passed * 100 / len(analyses), 1),
        "dimensions": dimensions,
        "metric_scores": metric_scores,
    }
