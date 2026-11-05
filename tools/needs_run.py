#!/usr/bin/env python3
"""Quick check for the after-drive runs: is there anything to do at all?

Prints NOTHING (and exits) when no course has new progress and every course still has enough
lessons ready. Otherwise prints RUN and the reasons. Reads only small JSON files, so a run with
nothing to do can stop right away.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COURSES = ROOT / "content" / "courses"
LESSONS_AHEAD = 3


def load(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def main():
    processed = load(ROOT / "progress" / "processed.json", {})
    index = load(COURSES / "index.json", {"courses": []})
    reasons = []
    for entry in index.get("courses", []):
        cid = entry["id"]
        course = load(COURSES / cid / "course.json", {"units": []})
        progress = load(ROOT / "progress" / f"{cid}.json", None)
        state = processed.get(cid, {})
        done = set(progress.get("completedUnits", [])) if progress else set()
        ready = [u for u in course.get("units", [])
                 if u.get("status") in ("draft", "ready") and not u["id"].startswith("r") and u["id"] not in done]
        planned = [u for u in course.get("units", []) if u.get("status") == "planned"]
        if len(ready) < LESSONS_AHEAD and planned:
            reasons.append(f"{cid}: only {len(ready)} lessons ready ahead")
        if not progress:
            continue
        new_explains = [a for a in progress.get("explainAnswers", [])
                        if a.get("time", 0) > state.get("feedbackUntil", 0) and not a.get("feedback")]
        if new_explains:
            reasons.append(f"{cid}: {len(new_explains)} explain-back answers without feedback")
        last = max((a.get("time", 0) for a in progress.get("attempts", [])), default=0)
        if last > state.get("lastAttemptTime", 0):
            reasons.append(f"{cid}: new answers since the last run")
    print("RUN: " + "; ".join(reasons) if reasons else "NOTHING")


if __name__ == "__main__":
    main()
