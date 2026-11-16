#!/usr/bin/env python3
"""sourceHash 跨端共享用例的**独立权威实现**（`spec/persona-translation/README.md` §4.1）。

这个脚本故意不读 Android / iOS 的任何代码：它只按规范 §4.1 从用例的 `input` 直接
构造规范化 JSON、算 SHA-256，用来生成/复核 `spec/persona-translation/cases/hash-cases.json`
里的 `canonical` 与 `expectHash`。两端实现与它不一致，就说明跨端契约破了。

用法（在仓库任意位置都行，脚本按自身位置找仓库根）：

    python3 scripts/hash-cases.py            # 校验：用例里的 expectHash 必须等于这里算的
    python3 scripts/hash-cases.py --write    # 重新生成 canonical / expectHash（改用例后跑一次）
    python3 scripts/hash-cases.py --print    # 只打印每条用例的规范化 JSON 与哈希

规范化规则（§4.1）：
- 根固定 `persona` / `speech` / `world` 三键；`speech`、`world` 全空时写 `null`；
- `persona` 固定 8 键，缺值 `""` / `[]`，数组保序，字符串原样（不 trim）；
- `world` 固定 7 键；`schedule` 项固定 4 键；`relationships` 项固定 3 键，
  `intimacy` 只在非 null 时出现；world 全空（§2.1）→ null；
- `speech` 固定 `tone/habits/avoid`；三项全空 → null；`phrases` 不参与；
- 键递归字典序、`separators=(",", ":")`、`ensure_ascii=False`，UTF-8 → SHA-256 →
  小写 hex + `sha256:` 前缀。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

# 与规范 §4.1 一一对应的固定键集合（顺序只影响可读性：真正进哈希前会 sort_keys）。
PERSONA_STRING_KEYS = ("name", "description", "backstory", "quirks", "systemPrompt")
PERSONA_LIST_KEYS = ("traits", "likes", "behaviors")
WORLD_KEYS = (
    "worldName",
    "era",
    "description",
    "schedule",
    "relationships",
    "obligations",
    "notes",
)
SCHEDULE_KEYS = ("day", "start", "end", "activity")
RELATIONSHIP_KEYS = ("name", "relation", "note")


def _expect(value: object, kind: type, where: str):
    if not isinstance(value, kind):
        raise SystemExit(f"{where}: 期望 {kind.__name__}，实际是 {type(value).__name__}（{value!r}）")
    return value


def canonical_persona(raw: object) -> dict:
    persona = _expect(raw, dict, "input.persona")
    out: dict[str, object] = {}
    for key in PERSONA_STRING_KEYS:
        out[key] = _expect(persona.get(key, ""), str, f"persona.{key}")
    for key in PERSONA_LIST_KEYS:
        items = _expect(persona.get(key, []), list, f"persona.{key}")
        out[key] = [_expect(item, str, f"persona.{key}[]") for item in items]
    return out


def world_is_empty(world: dict) -> bool:
    """§2.1：七个字段都是空串 / 空数组 → 没有世界。"""
    for key in ("worldName", "era", "description", "notes"):
        if _expect(world.get(key, ""), str, f"world.{key}") != "":
            return False
    for key in ("schedule", "relationships", "obligations"):
        if len(_expect(world.get(key, []), list, f"world.{key}")) > 0:
            return False
    return True


def canonical_world(raw: object) -> dict:
    world = _expect(raw, dict, "input.world")
    schedule = []
    for index, item in enumerate(_expect(world.get("schedule", []), list, "world.schedule")):
        entry = _expect(item, dict, f"world.schedule[{index}]")
        schedule.append({key: _expect(entry.get(key, ""), str, f"world.schedule[{index}].{key}")
                         for key in SCHEDULE_KEYS})
    relationships = []
    for index, item in enumerate(_expect(world.get("relationships", []), list, "world.relationships")):
        entry = _expect(item, dict, f"world.relationships[{index}]")
        person = {key: _expect(entry.get(key, ""), str, f"world.relationships[{index}].{key}")
                  for key in RELATIONSHIP_KEYS}
        intimacy = entry.get("intimacy")
        if intimacy is not None:
            # 只有非 null 才写这个键（§4.1 第 3 条）。
            person["intimacy"] = _expect(intimacy, int, f"world.relationships[{index}].intimacy")
        relationships.append(person)
    obligations = _expect(world.get("obligations", []), list, "world.obligations")
    return {
        "worldName": _expect(world.get("worldName", ""), str, "world.worldName"),
        "era": _expect(world.get("era", ""), str, "world.era"),
        "description": _expect(world.get("description", ""), str, "world.description"),
        "schedule": schedule,
        "relationships": relationships,
        "obligations": [_expect(item, str, "world.obligations[]") for item in obligations],
        "notes": _expect(world.get("notes", ""), str, "world.notes"),
    }


def speech_is_empty(raw: object) -> bool:
    """只按 tone/habits/avoid 判空；`phrases` 不参与（§4.1 第 4 条）。"""
    speech = _expect(raw, dict, "input.speech")
    tone = _expect(speech.get("tone", ""), str, "speech.tone")
    habits = _expect(speech.get("habits", []), list, "speech.habits")
    avoid = _expect(speech.get("avoid", []), list, "speech.avoid")
    return tone == "" and len(habits) == 0 and len(avoid) == 0


def canonical_speech(raw: object) -> dict:
    speech = _expect(raw, dict, "input.speech")
    return {
        "tone": _expect(speech.get("tone", ""), str, "speech.tone"),
        "habits": [_expect(item, str, "speech.habits[]") for item in
                   _expect(speech.get("habits", []), list, "speech.habits")],
        "avoid": [_expect(item, str, "speech.avoid[]") for item in
                  _expect(speech.get("avoid", []), list, "speech.avoid")],
    }


def canonical_object(case_input: object) -> dict:
    source = _expect(case_input, dict, "input")
    raw_world = source.get("world")
    if raw_world is None or (isinstance(raw_world, dict) and world_is_empty(raw_world)):
        world = None
    else:
        world = canonical_world(raw_world)
    raw_speech = source.get("speech")
    if raw_speech is None or (isinstance(raw_speech, dict) and speech_is_empty(raw_speech)):
        speech = None
    else:
        speech = canonical_speech(raw_speech)
    return {
        "persona": canonical_persona(source.get("persona")),
        "speech": speech,
        "world": world,
    }


def canonical_text(case_input: object) -> str:
    return json.dumps(
        canonical_object(case_input),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )


def hash_of_case(case_input: object) -> tuple[str, str]:
    text = canonical_text(case_input)
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    return text, "sha256:" + digest


def repo_root() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "spec/persona-translation/cases/hash-cases.json").is_file() \
                and (parent / "settings.gradle.kts").is_file():
            return parent
    raise SystemExit(f"找不到仓库根目录（从 {here} 向上没有 spec/persona-translation/cases/hash-cases.json）")


def main() -> int:
    parser = argparse.ArgumentParser(description="sourceHash 共享用例的独立权威实现")
    parser.add_argument("--write", action="store_true", help="重新生成 canonical / expectHash")
    parser.add_argument("--print", dest="print_only", action="store_true", help="只打印规范化 JSON 与哈希")
    args = parser.parse_args()

    cases_path = repo_root() / "spec/persona-translation/cases/hash-cases.json"
    raw = cases_path.read_text(encoding="utf-8")
    document = json.loads(raw)
    cases = document.get("cases")
    if not isinstance(cases, list) or not cases:
        raise SystemExit(f"{cases_path}: 顶层 cases 必须是非空数组")

    failures = 0
    for case in cases:
        if not isinstance(case, dict):
            raise SystemExit(f"{cases_path}: cases[] 必须是对象")
        name = case.get("name", "<unnamed>")
        text, expected = hash_of_case(case.get("input"))
        if args.print_only:
            print(f"[{name}]\n  canonical = {text}\n  expectHash = {expected}")
            continue
        if args.write:
            case["canonical"] = text
            case["expectHash"] = expected
            print(f"[write] {name}: {expected}")
            continue
        checks = (("canonical", text), ("expectHash", expected))
        for key, want in checks:
            got = case.get(key)
            if got != want:
                failures += 1
                print(f"[FAIL] {name}.{key}\n  expected: {want}\n  actual:   {got}")
        if all(case.get(key) == want for key, want in checks):
            print(f"[PASS] {name}: {expected}")

    if args.write:
        document["cases"] = cases
        cases_path.write_text(
            json.dumps(document, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"已写入 {cases_path}（{len(cases)} 条用例）")
        return 0
    if args.print_only:
        return 0
    if failures:
        print(f"{failures} 处不一致：先确认规范变更是否有意，再用 --write 重新生成。")
        return 1
    print(f"全部 {len(cases)} 条用例与独立实现一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
