#!/usr/bin/env python3
"""Render a unit JSON into a readable Markdown file for review (Hebrew).

Usage: python3 tools/render_unit.py content/courses/<course>/units/<unit>.json [...]
Writes content/courses/<course>/review/<unit>.md
"""
import json
import sys
from pathlib import Path

LEVEL_LABEL = {"recognize": "זיהוי", "why": "למה", "apply": "יישום", "explain": "הסבר במילים שלך"}


def render(path: Path) -> Path:
    unit = json.loads(path.read_text(encoding="utf-8"))
    mcs = {m["id"]: m for m in unit["misconceptions"]}
    out = [f"# {unit['title']}", "", f"כ-{unit['estimatedMinutes']} דקות", ""]

    out += ["## ההסבר", ""]
    for seg in unit["explanations"]["main"]:
        out += [f"### {seg['title']}", "", seg["text"], ""]

    out += ["## השאלות", ""]
    for n, q in enumerate(unit["questions"], 1):
        out += [f"**{n}. ({LEVEL_LABEL[q['level']]})** {q['prompt']}", ""]
        for i, o in enumerate(q.get("options", []), 1):
            mark = " ✅" if o.get("correct") else ""
            out.append(f"{i}. {o['text']}{mark}")
            if o.get("misconception"):
                out.append(f"   - אם בוחרים בזה: מושמע הסבר על הטעות ״{mcs[o['misconception']]['belief']}״")
            elif o.get("feedback"):
                out.append(f"   - אם בוחרים בזה: {o['feedback']}")
        if q.get("rubric"):
            out.append("נקודות שתשובה טובה מזכירה (צריך לפחות " + str(q["minPoints"]) + "):")
            out += [f"- {p}" for p in q["rubric"]]
        out += ["", f"> רמז: {q['hint']}", ""]

    out += ["## מילון מונחים", ""]
    out += [f"- **{t['term']}**: {t['definition']}" for t in unit.get("terms", [])]
    out += [""]
    out += ["## הסברים לטעויות נפוצות", ""]
    for m in unit["misconceptions"]:
        out += [f"**הטעות:** {m['belief']}", "", m["remedial"], ""]

    out += ["## גרסה פשוטה (מושמעת אם קשה)", ""]
    out += [s["text"] + "\n" for s in unit["explanations"]["simple"]]
    out += ["## העמקה (מושמעת כשהבסיס יושב)", ""]
    out += [s["text"] + "\n" for s in unit["explanations"]["deep"]]
    out += ["## סיכום", "", unit["recap"], ""]

    review_dir = path.parent.parent / "review"
    review_dir.mkdir(exist_ok=True)
    target = review_dir / f"{path.stem}.md"
    target.write_text("\n".join(out), encoding="utf-8")
    return target


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        print("wrote", render(Path(arg)))
