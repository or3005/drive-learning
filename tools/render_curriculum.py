#!/usr/bin/env python3
"""Render course.json into a readable curriculum.md (Hebrew).

Usage: python3 tools/render_curriculum.py [course_dir ...]
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATUS_LABEL = {"planned": "מתוכנן", "draft": "טיוטה", "ready": "מוכן"}
MINUTES_PER_UNIT = 15


def render(course_dir: Path) -> None:
    course = json.loads((course_dir / "course.json").read_text(encoding="utf-8"))
    concepts = {c["id"]: c for c in course["concepts"]}
    unit_of = {}
    for n, u in enumerate(course["units"], 1):
        for c in u["concepts"]:
            unit_of[c] = (n, u)

    total_hours = len(course["units"]) * MINUTES_PER_UNIT / 60
    lines = [
        f"# {course['title']}",
        "",
        f"{len(course['modules'])} פרקים, {len(course['units'])} יחידות של כ-{MINUTES_PER_UNIT} דקות.",
        f"בערך {total_hours:.0f} שעות האזנה, בלי לספור חזרות.",
        "",
        "> הקובץ הזה נוצר אוטומטית מ-`course.json`. לשינויים, ערוך את ה-JSON והרץ `python3 tools/render_curriculum.py`.",
        "",
    ]
    for m_index, module in enumerate(course["modules"], 1):
        lines += [f"## פרק {m_index}: {module['title']}", ""]
        for c in (c for c in course["concepts"] if c["module"] == module["id"]):
            n, unit = unit_of.get(c["id"], ("?", {"status": "planned"}))
            lines.append(f"**{n}. {c['title']}** · {STATUS_LABEL[unit['status']]}")
            lines.append(f"- {c['summary']}")
            if c.get("dotnetBridge"):
                lines.append(f"- מ-.NET: {c['dotnetBridge']}")
            if c.get("prerequisites"):
                names = ", ".join(concepts[p]["title"] for p in c["prerequisites"])
                lines.append(f"- דורש: {names}")
            lines.append("")
    (course_dir / "curriculum.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {(course_dir / 'curriculum.md').relative_to(ROOT)}")


def main():
    dirs = [Path(a) for a in sys.argv[1:]] or sorted(p for p in (ROOT / "content" / "courses").iterdir() if p.is_dir())
    for d in dirs:
        render(d.resolve())


if __name__ == "__main__":
    main()
