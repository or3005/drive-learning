# After-drive automation

Instructions for the scheduled Claude runs that keep the courses going between drives. A run
starts from nothing: read this file, then do the steps in order. Change content only, never the
Android code.

## Who this is for

- The learner is a junior developer (React and TypeScript at work, C# and ASP.NET on the backend).
  He learns by ear while driving about 1.5 hours each way, Sunday to Thursday, not every drive.
- **Language: Hebrew only, with technical terms in English (Latin script).** Never write any other
  language anywhere: not in content, not in feedback, not in commit messages, not in notes. A foreign
  word that a course needs (Italian in the Machiavelli course: וירטו, פורטונה) is written in Hebrew letters.
- He wants teaching before asking: explanations and worked examples first, questions after.
- Everything in `content/schema.md` applies, including format 2 for every new lesson.

## Files

- `content/courses/index.json`: the courses he can choose from in the app.
- `content/courses/<course>/course.json`: the course map; `units/<unit>.json`: written units.
- `content/courses/<course>/feedback.json`: spoken feedback on his explain-back answers. The app plays
  unheard items at the start of the next drive in that course.
- `progress/<course>.json`: uploaded by the app after drives (attempts, explain-back transcripts,
  completed units, diagnostics). Never edit these.
- `progress/processed.json`: what the automation already handled, per course.

## Each run

0. **First, check if there is anything to do:** `python3 tools/needs_run.py`. If it prints `NOTHING`,
   stop right there: don't read anything else, don't commit, and finish with one line saying there were
   no changes. It prints `RUN` with reasons when a course has new answers from a drive, explain-back
   answers without feedback, or fewer than 3 lessons ready ahead.

1. `git pull`. For each course in `content/courses/index.json`, read `progress/<course>.json` (skip
   courses without one) and its entry in `progress/processed.json`.

2. **Feedback on explain-back answers.** For every item in `explainAnswers` with `time` greater than
   `feedbackUntil`, skip it if it already has `feedback` (the app may grade live with Gemini). Otherwise
   find the question in its unit (`questionId` in `unitId`) and grade the transcript against the rubric.
   Speech recognition garbles English terms, so judge the idea, not the spelling. Add one item to
   `feedback.json`:
   `{ "id": "fb-<day>-<unitId>-<questionId>-<time>", "unitId": …, "questionId": …, "text": … }`.
   The text is 3 to 6 short spoken sentences: what was right (specific), what was missing or wrong and
   the correct idea, and one short line that is easy to remember. No ASCII double quotes. Then set
   `feedbackUntil` to the newest graded `time`.

3. **Mistakes that keep coming back.** If the same misconception appears in 2 or more wrong attempts,
   the remedial didn't land: rewrite that misconception's `remedial` with a concrete image or example
   (keep it about one minute). Small, focused edits only.

4. **Keep three lessons ready.** Count the lessons (not reviews) with status `draft` or `ready` that are
   not in `completedUnits`. If fewer than 3, write the next `planned` units in `course.json` order until
   there are 3, at most 2 new lessons per run. After every 3 lessons, add a review unit (`type: review`)
   that covers them, unless the course's `practice.reviewUnits` is false (the Machiavelli course is read
   for interest: no review units, and the app opens lessons with a reminder instead of warm-up questions). Every new lesson is **format 3** (`content/schema.md`): about 30 minutes in the car,
   so 10 to 16 minutes of explanation in 3 to 7 segments, a check after every segment, 3 to 5 end
   questions plus 3 short explain parts, a retell of at most 230 words, reminder and review questions,
   and every substantive term taught before it is used. **Questions must make him think** (he said
   they were too easy): new cases rather than lines from the lesson, wrong options that are real
   temptations, no wording or length clues, and explanations that teach something extra. Read the
   section on multiple-choice options in `content/schema.md` before writing any question. Use his weak
   spots (wrong attempts, missing rubric points) for examples and bridges. Set the unit's status to
   `draft` in `course.json`.

5. `python3 tools/validate.py` must end with 0 errors; fix warnings where reasonable. Then
   `python3 tools/render_unit_html.py`.

6. If anything changed: update `progress/processed.json` (`feedbackUntil`, and `lastAttemptTime` set to
   the newest attempt `time` in that course's progress), commit with a short English message
   (`content: feedback for 2 answers, unit u09`), and push to `main`. If the push is rejected because the
   app uploaded progress meanwhile, `git pull --rebase` and push again. If nothing changed, don't commit.

## Book courses

A course with a `book` section in `course.json` (now: `machiavelli-prince`) reads books in Hebrew. Each
unit in `course.json` says what it reads (`reads`). It reads two books in turn: all of The Prince
(`book.source`, units `u01` to `u27`), and after a Prince unit on a theme, a unit from the Discourses on
Livy (`book.secondSource`, units `d01` to `d14`) on the same theme: selected chapters and passages, one
theme per unit, with comparisons to The Prince. The order in `course.json` is the order of the drives. The
learner's main goal is to understand Machiavelli's view of the world, so he must always know which book he
is hearing. When writing these units:

- Translate from the public-domain English text named in `reads`, following `book.translationGuide`
  (`translation.md`): modern clear Hebrew split into short sentences for the ear, the glossary's words and
  the spelling of names. In The Prince the full text, no cuts and no additions. In the Discourses choose
  the passages that carry the chapter's argument (about 800 words in all), translate each one faithfully
  without changing it, never join two passages with a gap inside one `reading` (no "..."), and say in the
  explanation what was left out between them. Never use an existing Hebrew translation of either book.
- Always say which book: the unit title starts with the book's name and a comma (`הנסיך, פרק 6: ...` or
  `הדיונים, ספר א פרק 9: ...`), and every `reading` opens with its source: `מתוך הנסיך, פרק שישי.` or
  `מתוך הדיונים, ספר ראשון, פרק תשיעי.` (a passage that opens a chapter: `מתוך הנסיך. פרק שישי: ...`).
  `tools/validate.py` checks both. The first explanation of a unit names the book again, since the
  previous drive was from the other book.
- Discourses units bring out what he thinks and why, and where it agrees or clashes with The Prince. The
  last segment's explanation has a part that starts with `ומה זה אומר על תפיסת העולם שלו?`, and at least
  one end question compares the two books. Prince units point to the Discourses unit that follows when
  the two books meet. Don't say "in the next unit" for something two drives away; say "in the next unit
  from The Prince" (`ביחידה הבאה של הנסיך`).
- A lesson reads about 800 words of the book (the chapter split in `course.json` is planned for that).
- Each main segment has `reading` (a passage of about 150 to 300 words, ending with . ! or ?) and `text`
  (the explanation: who the people are, what happened, what the passage says, in plain words, ending with
  the idea in one sentence), and a `check`. Questions check that he understood the passage and the idea.
  A Discourses unit may open with one segment without `reading` that introduces the book or the theme.
- Long chapters may be split over two units; update `course.json` and `curriculum.md`
  (`python3 tools/render_curriculum.py content/courses/<course>`).
- The recordings are made by GitHub Actions after the push (`.github/workflows/audio.yml`): nothing to do.

## New courses

When the learner asks for a new topic, a regular session plans it: a course folder with `course.json`
(modules, concepts with prerequisites, units in order), the first 3 lessons in format 2, and an entry in
`content/courses/index.json`. After that, these runs keep it going like any other course.
