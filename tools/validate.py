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
# Format 3 (Nov 2026): a lesson takes about 30 minutes in the car, everything included.
F3_MIN_EXPLANATION = 8       # reading and explanation, in minutes (a short chapter may make a shorter lesson)
F3_MAX_EXPLANATION = 16
F3_TARGET_TOTAL = 28         # estimate with questions, retell and warm-up; the drive adds thinking and replays
F3_MAX_TOTAL = 31
F3_MAX_RETELL_WORDS = 230
WARM_UP_MINUTES = 2.5        # the app opens each lesson with up to 3 short questions on earlier units
HEB_WORD = re.compile(r"[\u05D0-\u05EA]{3,}")
ABSOLUTE = re.compile(r"(?<![\u05D0-\u05EA])(תמיד|אף פעם|לעולם|בשום|לגמרי|אף אחד|כל דבר|בכלל)(?![\u05D0-\u05EA])")
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
        # Recorded courses speak at the measured pace of the recorded voices, pauses included; the phone's voice is faster.
        "wpm": course.get("wordsPerMinute") or (118 if course.get("voice") else WORDS_PER_MINUTE),
        # A course read for interest opens a lesson with a short reminder instead of warm-up questions.
        "warmup": WARM_UP_MINUTES if course.get("practice", {}).get("warmUpQuestions", 3) > 0 else 0.5,
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
            if rx.search(seg.get("reading", "") + " " + seg.get("text", "")) and (taught_at is None or i < taught_at):
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
    if not is_review and unit.get("format", 1) < 3 and not 4 <= len(main) <= 10:
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
            if seg.get("reading") is not None:
                # A passage from the book, read before the explanation (book courses).
                n += check_text(f"{where} {kind}/{seg.get('id')}.reading", seg.get("reading"), rep)
                if seg.get("reading", "").strip()[-1:] not in (".", "!", "?"):
                    rep.err(f"{where} {kind}/{seg.get('id')}.reading", "must end with . ! or ? so the voice switches cleanly")
            if kind == "main":
                words += n
    minutes = words / ctx["wpm"]
    f3 = unit.get("format", 1) >= 3
    if f3 and not is_review and not F3_MIN_EXPLANATION <= minutes <= F3_MAX_EXPLANATION:
        rep.err(where, f"format 3: reading and explanation take ~{minutes:.1f} min; keep them between {F3_MIN_EXPLANATION} and {F3_MAX_EXPLANATION}")
    elif strict and not f3 and minutes < MIN_LESSON_MINUTES:
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
        for m in LATIN_TERM.finditer(seg.get("reading", "") + " " + seg.get("text", "")):
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
        needed = ("why", "apply", "explain") if f3 else LEVELS
        for lvl in needed:
            if lvl not in levels_seen:
                rep.err(where, f"no {lvl} question")
        if explains < 3:
            (rep.err if strict else rep.warn)(where, f"{explains} explain question(s); split the explain-back into at least 3 short parts")
        if f3:
            mc = [q for q in unit.get("questions", []) if q.get("level") != "explain"]
            if not 3 <= len(mc) <= 5:
                rep.err(where, f"format 3: {len(mc)} multiple-choice questions at the end; use 3 to 5 (the checks already cover recognition)")
            if sum(1 for q in mc if q.get("level") == "apply") < 2:
                rep.err(where, "format 3: at least 2 apply questions (a new case, not a line from the lesson)")
            if explains != 3:
                rep.err(where, f"format 3: exactly 3 short explain parts, has {explains}")
            if not 3 <= len(main) <= 7:
                rep.err(where, f"format 3: 3 to 7 main segments, has {len(main)}")
            if len((unit.get("retell") or "").split()) > F3_MAX_RETELL_WORDS:
                rep.warn(where, f"format 3: retell is longer than {F3_MAX_RETELL_WORDS} words")
    for mc in mcs.keys() - used_mcs:
        rep.warn(where, f"misconception {mc} is not targeted by any option")

    check_taught_before_used(where, unit, rep, ctx, main_ids)
    ctx["loaded"][unit.get("id")] = unit

    n_questions = len(q_ids) + checks + (len(unit.get("covers", [])) * 6 if is_review else 0)
    total = minutes + n_questions * MINUTES_PER_QUESTION
    if is_review:
        fill_words = sum(len(next((s.get("text", "") for s in ctx["loaded"].get(r.get("unit"), {}).get("explanations", {}).get("main", []) if s.get("id") == r.get("segment")), "").split()) for r in unit.get("fillIns", []))
        total += fill_words / ctx["wpm"]
    else:
        total += len((unit.get("retell") or "").split()) / ctx["wpm"] + (ctx["warmup"] if strict else 0)
        if total < 12:
            rep.warn(where, f"unit is short (~{total:.0f} min including questions)")
        if f3 and total > F3_MAX_TOTAL:
            rep.err(where, f"format 3: ~{total:.0f} min with questions; a lesson must fit about 30 minutes in the car (at most {F3_MAX_TOTAL} estimated)")
        elif f3 and total > F3_TARGET_TOTAL:
            rep.warn(where, f"format 3: ~{total:.0f} min with questions; aim for {F3_TARGET_TOTAL} or less")
    if f3:
        check_giveaways(where, unit, rep)
    kind = "review" if is_review else f"{minutes:.1f} explanation"
    print(f"  {listed['id']}: ~{total:.0f} min ({kind} + {n_questions} questions), {len(mcs)} misconceptions")
    return total


STOP_WORDS = set("היא הוא הם הן אותה אותו אותם אותן שלו שלה שלהם שלך בלי יותר פחות כדי אבל כמו רק גם עוד כל "
                 "זה זו זאת אין היה הייתה היו אחד אחת מאוד בין עצמו עצמה עצמם כבר אחרי לפני כאן שם אנשים "
                 "דבר דברים מישהו משהו כולם תמיד".split())


def _words(text):
    """Content words (Hebrew, 3+ letters, a one-letter prefix dropped), for comparing wordings."""
    out = set()
    for w in HEB_WORD.findall(text or ""):
        if w in STOP_WORDS:
            continue
        w = w[1:] if len(w) > 3 and w[0] in "והבכלמש" else w
        if w not in STOP_WORDS:
            out.add(w)
    return out


def check_giveaways(where, unit, rep):
    """Format 3: multiple-choice questions shouldn't be answerable without thinking.

    - The correct option shouldn't be the one that repeats the lesson's wording.
    - The correct option shouldn't usually be the longest.
    - Wrong options shouldn't be the only ones with absolute words (always, never), which give them away.
    """
    main = {s.get("id"): s for s in unit.get("explanations", {}).get("main", [])}
    mcs = []
    for s in main.values():
        if s.get("check"):
            mcs.append((f"{s.get('id')} check", s["check"], s.get("reading", "") + " " + s.get("text", "")))
    for q in unit.get("questions", []) + unit.get("reviewQuestions", []):
        if q.get("options"):
            lesson = " ".join(main[i].get("reading", "") + " " + main[i].get("text", "") for i in q.get("segments", []) if i in main)
            mcs.append((q.get("id"), q, lesson))
    longest, wrong_abs, wrong_total, right_abs = 0, 0, 0, 0
    for qid, q, lesson in mcs:
        opts = q.get("options", [])
        right = [o for o in opts if o.get("correct")]
        wrong = [o for o in opts if not o.get("correct")]
        if not right or not wrong:
            continue
        lw = _words(lesson)

        def overlap(o):
            w = _words(o.get("text"))
            return len(w & lw) / len(w) if w else 0.0
        if lw and overlap(right[0]) - max(overlap(o) for o in wrong) >= 0.3:
            rep.warn(f"{where} {qid}", "the correct option repeats the lesson's words and the others don't: reword it, or let a wrong option use them too")
        if len(right[0].get("text", "")) > max(len(o.get("text", "")) for o in wrong):
            longest += 1
        wrong_total += len(wrong)
        wrong_abs += sum(1 for o in wrong if ABSOLUTE.search(o.get("text", "")))
        right_abs += 1 if ABSOLUTE.search(right[0].get("text", "")) else 0
    if mcs and longest > len(mcs) / 2:
        rep.warn(where, f"the correct option is the longest in {longest} of {len(mcs)} questions: a listener learns to pick the longest")
    if wrong_total and wrong_abs / wrong_total > 0.2 and right_abs == 0:
        rep.warn(where, f"{wrong_abs} wrong options use words like always or never, and no correct one does: that gives them away")


def check_index(rep):
    """content/courses/index.json lists every course folder, and nothing else."""
    where = "courses/index.json"
    index = load(COURSES / "index.json", rep)
    if index is None:
        return
    ids = [c.get("id") for c in index.get("courses", [])]
    folders = {p.name for p in COURSES.iterdir() if p.is_dir()}
    for i in ids:
        if i not in folders:
            rep.err(where, f"course {i} has no folder")
        check_text(f"{where} {i}.title", next(c for c in index["courses"] if c.get("id") == i).get("title"), rep)
    for f in folders - set(ids):
        rep.err(where, f"course folder {f} is not listed")
    if len(ids) != len(set(ids)):
        rep.err(where, "duplicate course id")


def check_feedback(course_dir, rep):
    """feedback.json: short spoken feedback on explain-back answers, written after drives."""
    path = course_dir / "feedback.json"
    if not path.exists():
        return
    data = load(path, rep)
    if data is None:
        return
    seen = set()
    for item in data.get("items", []):
        w = f"{course_dir.name}/feedback.json {item.get('id')}"
        if not item.get("id") or item["id"] in seen:
            rep.err(w, "every item needs a unique id")
        seen.add(item.get("id"))
        check_text(w, item.get("text"), rep)


def main():
    rep = Report()
    check_index(rep)
    dirs = [Path(a) for a in sys.argv[1:]] or sorted(p for p in COURSES.iterdir() if p.is_dir())
    for d in dirs:
        check_course(d.resolve(), rep)
        check_feedback(d.resolve(), rep)
    for line in rep.warnings + rep.errors:
        print(line)
    print(f"{len(rep.errors)} errors, {len(rep.warnings)} warnings")
    sys.exit(1 if rep.errors else 0)


if __name__ == "__main__":
    main()
