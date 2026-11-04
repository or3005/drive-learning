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
# A correct option longer than this times the longest distractor is a giveaway.
OPTION_LENGTH_RATIO = 1.2
# Format 2 lessons: at least this many minutes of main explanation.
MIN_LESSON_MINUTES = 15
LEVELS = ["recognize", "why", "apply", "explain"]
STATUSES = {"planned", "draft", "ready"}
WORDS_PER_MINUTE = 140  # rough speaking rate for Hebrew TTS
MINUTES_PER_QUESTION = 1.0  # reading the question and options, answering, hearing feedback
UNSPEAKABLE = re.compile(r"=>|[{}<>\[\]`]|https?:")
WORD = r"[A-Za-z][A-Za-z0-9#+]*(?:[.\-'][A-Za-z0-9#+]+)*"
LATIN_TERM = re.compile(WORD + r"(?:\s+(?=[A-Z])" + WORD + r")*")
# Words the learner already knows well; everything else in Latin script should be in `terms`
KNOWN_WORDS = {"react", "javascript", "typescript", "c#", "asp.net", ".net", "node", "node.js",
               "google", "nestjs", "express", "fastify", "net", "chrome", "asp.net core", "task",
               "task.whenall"}


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
    ctx = {
        "concept_ids": concept_ids,
        "earlier_terms": set(),
        "loaded": {},  # unit id -> unit json, in course order
        "substantive": [t for t in course.get("substantiveTerms", [])],
        "taught": set(),  # substantive terms taught so far (lowercase)
    }
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
        total_minutes += check_unit(path, u, rep, ctx)
    if total_minutes:
        print(f"{course_dir.name}: ~{total_minutes:.0f} minutes of listening so far, without replays and remedials")


def check_mc(qw, q, rep, mcs, used_mcs):
    """A multiple-choice question: options, one correct answer, feedback, no giveaway length."""
    check_text(f"{qw}.prompt", q.get("prompt"), rep)
    check_text(f"{qw}.explanation", q.get("explanation"), rep)
    if q.get("hint"):
        check_text(f"{qw}.hint", q.get("hint"), rep)
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
    right = [len(o.get("text", "")) for o in opts if o.get("correct")]
    wrong = [len(o.get("text", "")) for o in opts if not o.get("correct")]
    if right and wrong and right[0] > OPTION_LENGTH_RATIO * max(wrong):
        rep.warn(qw, f"correct option is much longer than the others ({right[0]} vs {max(wrong)} chars)")


def term_regex(term):
    return re.compile(r"(?<![A-Za-z])" + re.escape(term) + r"(?![A-Za-z])", re.IGNORECASE)


def check_taught_before_used(where, unit, rep, ctx, main_ids_in_order):
    """Substantive concepts (Redis, Reverse Proxy...) must be taught before they are used to explain something."""
    teaches = {t.lower(): seg for t, seg in unit.get("teaches", {}).items()}
    for term, seg in unit.get("teaches", {}).items():
        if seg not in main_ids_in_order:
            rep.err(where, f"teaches {term} in segment {seg}, which is not a main segment")
    order = {sid: i for i, sid in enumerate(main_ids_in_order)}
    for term in ctx["substantive"]:
        low = term.lower()
        rx = term_regex(term)
        if low in ctx["taught"]:
            continue
        taught_at = order.get(teaches.get(low), None)
        for i, seg in enumerate(unit.get("explanations", {}).get("main", [])):
            if rx.search(seg.get("text", "")) and (taught_at is None or i < taught_at):
                rep.err(where, f"segment {seg.get('id')} uses {term} before it is taught")
                break
        if taught_at is None:
            texts = [q.get("prompt", "") + " " + " ".join(o.get("text", "") for o in q.get("options", []))
                     for q in unit.get("questions", []) + unit.get("reviewQuestions", [])]
            if any(rx.search(t) for t in texts):
                rep.err(where, f"a question uses {term}, which was not taught yet")
    ctx["taught"] |= set(teaches)


def check_unit(path, listed, rep, ctx):
    unit = load(path, rep)
    if unit is None:
        return 0
    where = f"{path.parent.parent.name}/units/{path.name}"
    if unit.get("id") != listed["id"]:
        rep.err(where, f"id {unit.get('id')} does not match file name")
    if sorted(unit.get("concepts", [])) != sorted(listed.get("concepts", [])):
        rep.err(where, "concepts differ from course.json")
    is_review = unit.get("type") == "review"
    strict = unit.get("format", 1) >= 2
    unit_concepts = set(unit.get("concepts", []))
    check_text(f"{where}.title", unit.get("title"), rep)
    check_text(f"{where}.recap", unit.get("recap"), rep)

    ex = unit.get("explanations", {})
    main = ex.get("main", [])
    if not is_review and not 4 <= len(main) <= 10:
        rep.err(where, f"explanations.main should have 4-10 segments, has {len(main)}")
    words = 0
    seg_ids = set()
    for kind in ("main", "simple", "deep"):
        if not is_review and not ex.get(kind):
            rep.err(where, f"explanations.{kind} is empty")
        for seg in ex.get(kind, []):
            if seg.get("id") in seg_ids:
                rep.err(where, f"duplicate segment id {seg.get('id')}")
            seg_ids.add(seg.get("id"))
            n = check_text(f"{where} {kind}/{seg.get('id')}", seg.get("text"), rep)
            if kind == "main":
                words += n
    minutes = words / WORDS_PER_MINUTE
    if strict and minutes < MIN_LESSON_MINUTES:
        rep.err(where, f"main explanation is ~{minutes:.1f} min; format 2 lessons need at least {MIN_LESSON_MINUTES}")
    elif not is_review and minutes < 5:
        rep.warn(where, f"main explanation is short (~{minutes:.1f} min)")
    main_ids = [seg.get("id") for seg in main]

    terms = unit.get("terms", [])
    if not terms and not is_review:
        rep.err(where, "terms (glossary) is empty")
    term_names = set()
    for t in terms:
        name = t.get("term", "")
        if name.lower() in term_names:
            rep.err(where, f"duplicate term {name}")
        term_names.add(name.lower())
        check_text(f"{where} term {name}", t.get("definition"), rep)
    earlier_terms = ctx["earlier_terms"]
    known = term_names | KNOWN_WORDS | earlier_terms
    earlier_terms |= term_names
    covered_words = {w for name in term_names | earlier_terms for w in name.split()}
    undefined = set()
    for seg in main:
        for m in LATIN_TERM.finditer(seg.get("text", "")):
            raw = m.group(0).strip(".")
            if raw[0].islower():
                continue  # terms are capitalized; lowercase words are names (localhost) or code (getOrders)
            phrase = raw.lower()

            def is_known(w):
                return any(v in known or v in covered_words for v in (w, w + "s", w.rstrip("s")))
            if is_known(phrase) or all(is_known(w) for w in phrase.split()):
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
    used_mcs = set()

    # Checks: a short question right after a segment.
    checks = 0
    for seg in main:
        q = seg.get("check")
        if not q:
            continue
        checks += 1
        qw = f"{where} {seg.get('id')} check"
        if q.get("level") != "check":
            rep.err(qw, "a segment check needs level check")
        if unit_concepts and q.get("concept") not in unit_concepts:
            rep.err(qw, f"concept {q.get('concept')} is not one of the unit's concepts")
        check_mc(qw, q, rep, mcs, used_mcs)
    if strict and checks < len(main):
        rep.err(where, f"format 2: every segment needs a check question ({checks} of {len(main)})")

    # Review questions: short questions for warm-ups and review units.
    rq = unit.get("reviewQuestions", [])
    for q in rq:
        qw = f"{where} review {q.get('id')}"
        if q.get("level") not in ("recognize", "why", "apply"):
            rep.err(qw, "review questions are recognize, why or apply")
        if q.get("concept") not in unit_concepts:
            rep.err(qw, f"concept {q.get('concept')} is not one of the unit's concepts")
        for s_id in q.get("segments", []):
            if s_id not in main_ids:
                rep.err(qw, f"segment {s_id} is not a main explanation segment")
        check_mc(qw, q, rep, mcs, used_mcs)
    if not is_review:
        if not unit.get("reminder"):
            rep.warn(where, "no reminder (read before warm-up and review questions on this unit)")
        else:
            check_text(f"{where}.reminder", unit.get("reminder"), rep)
        if len(rq) < 3:
            rep.warn(where, f"only {len(rq)} review questions (want at least 3)")
        if unit.get("retell"):
            check_text(f"{where}.retell", unit.get("retell"), rep)
        elif strict:
            rep.err(where, "format 2: retell is missing (the unit told again in another way)")

    q_ids = set()
    if is_review:
        for c in unit.get("covers", []):
            if c not in ctx["loaded"]:
                rep.err(where, f"covers {c}, which is not an earlier unit")
        for ref in unit.get("fillIns", []):
            src = ctx["loaded"].get(ref.get("unit"))
            if not src or ref.get("segment") not in [s.get("id") for s in src.get("explanations", {}).get("main", [])]:
                rep.err(where, f"fill-in {ref} does not point to a main segment of an earlier unit")
            elif ref.get("segment") not in [s.get("id") for s in src.get("explanations", {}).get("main", []) if s.get("check")]:
                rep.warn(where, f"fill-in {ref} has no check question")
    else:
        last_level = 0
        levels_seen = set()
        explains = 0
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
            for s_id in segs:
                if s_id not in main_ids:
                    rep.err(qw, f"segment {s_id} is not a main explanation segment")
            if level == "explain":
                explains += 1
                check_text(f"{qw}.prompt", q.get("prompt"), rep)
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
            check_mc(qw, q, rep, mcs, used_mcs)
        for lvl in LEVELS:
            if lvl not in levels_seen:
                rep.err(where, f"no {lvl} question")
        if explains < 3:
            (rep.err if strict else rep.warn)(where, f"{explains} explain question(s); split the explain-back into at least 3 short parts")
    for mc in mcs.keys() - used_mcs:
        rep.warn(where, f"misconception {mc} is not targeted by any option")

    check_taught_before_used(where, unit, rep, ctx, main_ids)
    ctx["loaded"][unit.get("id")] = unit

    n_questions = len(q_ids) + checks + (len(unit.get("covers", [])) * 6 if is_review else 0)
    total = minutes + n_questions * MINUTES_PER_QUESTION
    if is_review:
        fill_words = sum(len(next((s.get("text", "") for s in ctx["loaded"].get(r.get("unit"), {}).get("explanations", {}).get("main", []) if s.get("id") == r.get("segment")), "").split()) for r in unit.get("fillIns", []))
        total += fill_words / WORDS_PER_MINUTE
    elif total < 12:
        rep.warn(where, f"unit is short (~{total:.0f} min including questions)")
    kind = "review" if is_review else f"{minutes:.1f} explanation"
    print(f"  {listed['id']}: ~{total:.0f} min ({kind} + {n_questions} questions), {len(mcs)} misconceptions")
    return total


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
