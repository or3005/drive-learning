#!/usr/bin/env python3
"""Validate course maps and unit files against content/schema.md.

Usage: python3 tools/validate.py [course_dir ...]
Exit code 1 if any error is found. Warnings do not fail the run.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COURSES = ROOT / "content" / "courses"
LEVELS = ["recognize", "why", "apply", "explain"]
STATUSES = {"planned", "draft", "ready"}
WORDS_PER_MINUTE = 140  # rough speaking rate for Hebrew TTS
UNSPEAKABLE = re.compile(r"=>|[{}<>\[\]`]|https?:")
WORD = r"[A-Za-z][A-Za-z0-9#+]*(?:\.[A-Za-z0-9#+]+)*"
LATIN_TERM = re.compile(WORD + r"(?:\s+(?=[A-Z])" + WORD + r")*")
# Words the learner already knows well; everything else in Latin script should be in `terms`
KNOWN_WORDS = {"react", "javascript", "typescript", "c#", "asp.net", ".net", "node", "node.js",
               "google", "nestjs", "express", "fastify", "net"}


class Report:
    def __init__(self):
        self.errors, self.warnings = [], []

    def err(self, where, msg):
        self.errors.append(f"ERROR  {where}: {msg}")

    def warn(self, where, msg):
        self.warnings.append(f"WARN   {where}: {msg}")


def load(path, rep):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        rep.err(str(path.relative_to(ROOT)), f"cannot parse JSON: {e}")
        return None


def check_text(where, text, rep):
    if not isinstance(text, str) or not text.strip():
        rep.err(where, "missing or empty text")
        return 0
    if '"' in text:
        rep.err(where, "contains an ASCII double quote")
    if UNSPEAKABLE.search(text):
        rep.warn(where, f"contains symbols a voice can't read: {UNSPEAKABLE.search(text).group(0)!r}")
    return len(text.split())


def find_cycle(concepts):
    graph = {c["id"]: c.get("prerequisites", []) for c in concepts}
    state = {}

    def visit(node, path):
        if state.get(node) == "done":
            return None
        if state.get(node) == "visiting":
            return path + [node]
        state[node] = "visiting"
        for nxt in graph.get(node, []):
            cyc = visit(nxt, path + [node])
            if cyc:
                return cyc
        state[node] = "done"
        return None

    for n in graph:
        cyc = visit(n, [])
        if cyc:
            return cyc
    return None


def check_course(course_dir, rep):
    course = load(course_dir / "course.json", rep)
    if course is None:
        return
    where = f"{course_dir.name}/course.json"
    modules = {m["id"] for m in course.get("modules", [])}
    concepts = course.get("concepts", [])
    ids = [c["id"] for c in concepts]
    concept_ids = set(ids)
    for dup in {i for i in ids if ids.count(i) > 1}:
        rep.err(where, f"duplicate concept id {dup}")
    for c in concepts:
        cw = f"{where} concept {c['id']}"
        if c.get("module") not in modules:
            rep.err(cw, f"unknown module {c.get('module')}")
        for field in ("title", "summary"):
            check_text(f"{cw}.{field}", c.get(field), rep)
        for p in c.get("prerequisites", []):
            if p not in concept_ids:
                rep.err(cw, f"unknown prerequisite {p}")
    cyc = find_cycle(concepts)
    if cyc:
        rep.err(where, "prerequisite cycle: " + " -> ".join(cyc))

    prereqs = {c["id"]: set(c.get("prerequisites", [])) for c in concepts}
    taught, unit_ids = set(), set()
    for u in course.get("units", []):
        uw = f"{where} unit {u['id']}"
        if u["id"] in unit_ids:
            rep.err(uw, "duplicate unit id")
        unit_ids.add(u["id"])
        if u.get("status") not in STATUSES:
            rep.err(uw, f"bad status {u.get('status')}")
        for c in u.get("concepts", []):
            if c not in concept_ids:
                rep.err(uw, f"unknown concept {c}")
                continue
            missing = prereqs[c] - taught
            if missing:
                rep.err(uw, f"concept {c} is taught before its prerequisites {sorted(missing)}")
        taught.update(u.get("concepts", []))
    for c in concept_ids - taught:
        rep.warn(where, f"concept {c} is not covered by any unit")

    units_dir = course_dir / "units"
    files = {p.stem: p for p in units_dir.glob("*.json")} if units_dir.exists() else {}
    for stem in files.keys() - unit_ids:
        rep.err(f"{course_dir.name}/units/{stem}.json", "file is not listed in course.json units")
    total_minutes = 0.0
    for u in course.get("units", []):
        path = files.get(u["id"])
        if u.get("status") == "planned":
            if path:
                rep.warn(f"{where} unit {u['id']}", "file exists but status is planned")
            continue
        if not path:
            rep.err(f"{where} unit {u['id']}", f"status {u['status']} but no file units/{u['id']}.json")
            continue
        total_minutes += check_unit(path, u, concept_ids, rep)
    if total_minutes:
        print(f"{course_dir.name}: ~{total_minutes:.0f} minutes of written explanation so far")


def check_unit(path, listed, concept_ids, rep):
    unit = load(path, rep)
    if unit is None:
        return 0
    where = f"{path.parent.parent.name}/units/{path.name}"
    if unit.get("id") != listed["id"]:
        rep.err(where, f"id {unit.get('id')} does not match file name")
    if sorted(unit.get("concepts", [])) != sorted(listed.get("concepts", [])):
        rep.err(where, "concepts differ from course.json")
    unit_concepts = set(unit.get("concepts", []))
    check_text(f"{where}.title", unit.get("title"), rep)
    check_text(f"{where}.recap", unit.get("recap"), rep)

    ex = unit.get("explanations", {})
    main = ex.get("main", [])
    if not 4 <= len(main) <= 6:
        rep.err(where, f"explanations.main should have 4-6 segments, has {len(main)}")
    words = 0
    seg_ids = set()
    for kind in ("main", "simple", "deep"):
        if not ex.get(kind):
            rep.err(where, f"explanations.{kind} is empty")
        for seg in ex.get(kind, []):
            if seg.get("id") in seg_ids:
                rep.err(where, f"duplicate segment id {seg.get('id')}")
            seg_ids.add(seg.get("id"))
            n = check_text(f"{where} {kind}/{seg.get('id')}", seg.get("text"), rep)
            if kind == "main":
                words += n
    minutes = words / WORDS_PER_MINUTE
    if minutes < 6:
        rep.warn(where, f"main explanation is short (~{minutes:.1f} min)")
    main_ids = {seg.get("id") for seg in main}

    terms = unit.get("terms", [])
    if not terms:
        rep.err(where, "terms (glossary) is empty")
    term_names = set()
    for t in terms:
        name = t.get("term", "")
        if name.lower() in term_names:
            rep.err(where, f"duplicate term {name}")
        term_names.add(name.lower())
        check_text(f"{where} term {name}", t.get("definition"), rep)
    known = term_names | KNOWN_WORDS
    covered_words = {w for name in term_names for w in name.split()}
    undefined = set()
    for seg in main:
        for m in LATIN_TERM.finditer(seg.get("text", "")):
            phrase = m.group(0).strip(".").lower()
            if phrase in known or all(w in known or w in covered_words for w in phrase.split()):
                continue
            undefined.add(m.group(0).strip("."))
    if undefined:
        rep.warn(where, "terms in the explanation without a glossary entry: " + ", ".join(sorted(undefined)))

    mcs = {}
    for mc in unit.get("misconceptions", []):
        mw = f"{where} {mc.get('id')}"
        if mc.get("id") in mcs:
            rep.err(mw, "duplicate misconception id")
        mcs[mc.get("id")] = mc
        if mc.get("concept") not in unit_concepts:
            rep.err(mw, f"concept {mc.get('concept')} is not one of the unit's concepts")
        check_text(f"{mw}.belief", mc.get("belief"), rep)
        check_text(f"{mw}.remedial", mc.get("remedial"), rep)

    used_mcs, last_level, q_ids = set(), 0, set()
    levels_seen = set()
    for q in unit.get("questions", []):
        qw = f"{where} {q.get('id')}"
        if q.get("id") in q_ids:
            rep.err(qw, "duplicate question id")
        q_ids.add(q.get("id"))
        level = q.get("level")
        if level not in LEVELS:
            rep.err(qw, f"bad level {level}")
            continue
        levels_seen.add(level)
        if LEVELS.index(level) < last_level:
            rep.err(qw, "questions are not in ladder order (recognize, why, apply, explain)")
        last_level = LEVELS.index(level)
        if q.get("concept") not in unit_concepts:
            rep.err(qw, f"concept {q.get('concept')} is not one of the unit's concepts")
        segs = q.get("segments", [])
        if not segs:
            rep.err(qw, "segments is empty (which part of the lesson teaches this?)")
        for s in segs:
            if s not in main_ids:
                rep.err(qw, f"segment {s} is not a main explanation segment")
        check_text(f"{qw}.prompt", q.get("prompt"), rep)
        check_text(f"{qw}.hint", q.get("hint"), rep)
        if level == "explain":
            if q.get("options"):
                rep.err(qw, "explain questions must not have options")
            rubric = q.get("rubric", [])
            if len(rubric) < 2:
                rep.err(qw, "explain questions need a rubric of at least 2 points")
            for i, point in enumerate(rubric):
                check_text(f"{qw}.rubric[{i}]", point, rep)
            if not 1 <= q.get("minPoints", 0) <= len(rubric):
                rep.err(qw, "minPoints must be between 1 and the rubric length")
            continue
        check_text(f"{qw}.explanation", q.get("explanation"), rep)
        opts = q.get("options", [])
        if not 3 <= len(opts) <= 4:
            rep.err(qw, f"needs 3-4 options, has {len(opts)}")
        if sum(1 for o in opts if o.get("correct")) != 1:
            rep.err(qw, "needs exactly one correct option")
        for i, o in enumerate(opts, 1):
            check_text(f"{qw} option {i}", o.get("text"), rep)
            if o.get("correct"):
                continue
            mc = o.get("misconception")
            if mc:
                if mc not in mcs:
                    rep.err(qw, f"option {i} points to unknown misconception {mc}")
                used_mcs.add(mc)
            elif not o.get("feedback"):
                rep.err(qw, f"wrong option {i} needs a misconception or feedback")
            else:
                check_text(f"{qw} option {i} feedback", o.get("feedback"), rep)
    for lvl in LEVELS:
        if lvl not in levels_seen:
            rep.err(where, f"no {lvl} question")
    for mc in mcs.keys() - used_mcs:
        rep.warn(where, f"misconception {mc} is not targeted by any option")
    print(f"  {listed['id']}: ~{minutes:.1f} min explanation, {len(q_ids)} questions, {len(mcs)} misconceptions")
    return minutes


def main():
    rep = Report()
    dirs = [Path(a) for a in sys.argv[1:]] or sorted(p for p in COURSES.iterdir() if p.is_dir())
    for d in dirs:
        check_course(d.resolve(), rep)
    for line in rep.warnings + rep.errors:
        print(line)
    print(f"{len(rep.errors)} errors, {len(rep.warnings)} warnings")
    sys.exit(1 if rep.errors else 0)


if __name__ == "__main__":
    main()
