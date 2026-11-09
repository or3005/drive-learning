# Content schema

All learning content is plain JSON so the app can read it offline and so Claude (nightly) and
Gemini (post-drive) can generate more of it. `tools/validate.py` enforces everything below.

## Writing rules (apply to every text field)

- **Language:** Hebrew. Technical terms stay in English, in Latin script (Event Loop, Middleware).
  The app splits text into Hebrew and Latin runs and reads Latin runs with an English voice.
- **Written for the ear:** short sentences, no code, no bullet lists, no URLs, no symbols that a
  voice can't read (`=>`, `{}`, `/`). Say "סלאש" or rephrase instead.
- **Teach before asking.** Every question must be answerable from the unit's explanation.
- **Architecture over syntax.** Explain what, why and when; not how to type it.
- **The learner is a junior developer.** Define every technical term the first time it appears in
  the unit, in one plain sentence, and add it to the unit's `terms`. Prefer a concrete example or
  an everyday analogy over an abstract statement. One main idea per segment.
- **Bridge from .NET where it helps.** The learner works daily with ASP.NET and React/TypeScript.
- No ASCII double quotes inside strings (use ״ or rephrase) to keep the JSON hand-editable.
- **Pronunciation.** Write English terms in English (Body, Token), never in Hebrew letters, so the
  English voice reads them. Hebrew words that a voice may read with the wrong vowels (לפרק, מורכב,
  משנה) go into `content/pronunciation.json` under `hebrew` with niqqud, instead of adding niqqud
  in the unit text. A new English abbreviation that should be read letter by letter, or a symbol
  like C#, goes under `english`.
- **Depth.** A lesson is about 30 minutes in all (format 3: 10–16 minutes of explanation in 3–7 segments). Segments walk
  through a concrete example out loud: a real scenario, what the code does step by step in words,
  what goes wrong in the naive version and how a better version fixes it. Good practice (naming,
  layering, error handling, testing) is taught inside these examples, not as a list of rules.

## Writing multiple-choice options

The learner said the questions were too easy: he found the right answer without thinking. A
question must make him use the idea, and teach something on the way. Before writing any option, ask:
could someone who only half-listened pick the right one by its wording, its length, or because the
others are silly? If yes, rewrite.

- **Ask about a new case.** Most questions put the idea in a situation the lesson didn't describe: a
  modern scenario, a different historical case, a what-would-he-say, a choice between two plausible
  reasons. A question that can be answered by recognizing a sentence from the lesson is a weak question.
- **Every wrong option is a real temptation.** It is one of: the unit's misconception in the words
  people use; a true statement that doesn't answer this question; cause and effect reversed; the right
  idea with one important detail wrong; a correct idea from another segment or chapter that doesn't fit
  here. Never a filler that no one would choose (״because the Pope forbade it״, ״it doesn't matter״).
- **No wording clues.** Reword all options in fresh words: don't copy the lesson's phrase into the
  correct option only (the validator warns). Same grammatical shape for all options. Words like always,
  never, at all (תמיד, אף פעם, בכלל) appear in correct options as often as in wrong ones, or not at all.
- **The correct option is not usually the longest** (the validator warns when it is in more than half
  the questions). Vary which option is longest.
- **The explanation teaches.** After the answer, the explanation says why the tempting wrong option is
  tempting and wrong, or adds an example or consequence the lesson didn't spell out. It never just
  repeats the correct option.
- **Checks after a segment** may ask what the passage said, but their wrong options are near misses
  (the right idea with a wrong detail, or what a careless listener would remember), not obvious mistakes.

The learner answers by ear while driving, so the options must also follow these older rules:

- **Same shape, same length.** Every option is one sentence of similar length (the validator warns
  when the correct one is more than 20% longer than the longest wrong one). If the correct option
  has a reason, every wrong option has a reason too.
- **Plausible wrong options.** Each wrong option is something a junior developer could really
  believe: a half-truth with the wrong conclusion, a real technique applied in the wrong place, or
  the misconception phrased the way people actually say it. No joke options, no ״it doesn't
  matter״, no options that are obviously from a different topic.
- **Gradual difficulty.** `recognize` questions may have one clearly-wrong option. `why` and
  `apply` questions have wrong options that are partly true, so choosing needs the actual reason.
- The feedback of a partly-true option says first what is true in it, then why it's still not the
  answer.

## Courses: `content/courses/index.json`

```json
{ "courses": [ { "id": "nodejs-backend", "title": "Node.js ו-Backend", "status": "active" } ] }
```

Every folder in `content/courses/` is listed here, and only those. The app shows the courses on its
main screen and in the car, and the learner picks one for the coming drive. Each course keeps its own
progress, uploaded to `progress/<course>.json`. `status: hidden` keeps a course out of the picker.

## Feedback: `content/courses/<course>/feedback.json`

```json
{ "items": [ { "id": "fb-…", "unitId": "u02-…", "questionId": "q10", "text": "…" } ] }
```

Written after drives (see `AUTOMATION.md`): spoken feedback on explain-back answers. The app plays
items it hasn't played yet at the start of the next drive in that course.

## `course.json`

```jsonc
{
  "id": "nodejs-backend",
  "title": "…",
  "language": "he",
  "modules": [ { "id": "m1", "title": "…" } ],
  "concepts": [
    {
      "id": "event-loop",              // kebab-case, unique in the course
      "module": "m2",
      "title": "…",
      "summary": "…",                  // one line: what the learner should understand
      "prerequisites": ["node-runtime"],
      "dotnetBridge": "…"              // optional: the closest .NET idea
    }
  ],
  "units": [
    { "id": "u08-event-loop", "concepts": ["event-loop"], "status": "planned" }
  ]
}
```

- `units` is the default teaching order. Every unit's concepts must have all prerequisites taught
  in an earlier unit. Review units (`r…`, `"concepts": []`) sit in the order after every two or
  three lessons.
- `substantiveTerms`: concepts with real substance (Redis, Reverse Proxy, Cache...). A unit may use
  one, in its explanation or in a question, only after a unit has taught it: the teaching unit lists
  it in `teaches` with the segment that teaches it. The validator enforces this. Never explain
  something through a concept the learner hasn't learned; teach it first, with a check question.
- `status`: `planned` (no file yet) → `draft` (file exists, not reviewed) → `ready` (reviewed).
- `voice` (optional): the course is recorded with a good voice by `tools/tts_render.py` in GitHub
  Actions, sentence by sentence, and the app plays the recordings instead of the phone's voice.
  `{ "engine": "edge", "reading": "he-IL-AvriNeural", "explain": "he-IL-HilaNeural", "rate": "-5%" }`.
  `engine` is `edge` (free, no key), `fish` (Fish Audio S2.1 Free through OpenRouter, needs the
  `OPENROUTER_API_KEY` secret) or `gemini` (Gemini TTS, needs `GEMINI_API_KEY`). `reading` is the voice
  for book passages, `explain` for everything else.
- `book` (optional): a course that reads a book. `source` is the public-domain text it is translated
  from, `translationGuide` the rules and glossary for the translation (see `AUTOMATION.md`).
- `minutesPerUnit` (optional): for `curriculum.md`; default 15.

## Unit file `units/<unit-id>.json`

```jsonc
{
  "id": "u01-backend-role",
  "concepts": ["backend-role"],
  "title": "…",
  "estimatedMinutes": 15,
  "explanations": {
    "main":   [ { "id": "s1", "title": "…", "text": "…" } ],  // the lesson; format 3: 3–7 segments, 10–16 minutes, each with a "check"
                                                                // book courses: also "reading", the passage read (in the reading voice) before "text"
    "simple": [ { "id": "simple-1", "text": "…" } ],           // same ideas through an analogy
    "deep":   [ { "id": "deep-1", "text": "…" } ]              // for when the basics are solid
  },
  "terms": [                           // glossary, answered when the learner asks ״מה זה …״
    { "term": "Middleware", "definition": "…" }
  ],
  "misconceptions": [
    {
      "id": "mc-client-validation-enough",
      "concept": "backend-role",
      "belief": "…",                   // the wrong idea, stated as the learner might think it
      "remedial": "…"                  // ~1 minute spoken explanation that fixes exactly this
    }
  ],
  "questions": [
    {
      "id": "q1",
      "concept": "backend-role",
      "level": "recognize",            // recognize | why | apply | explain
      "segments": ["s1", "s2"],        // main segments that teach this; replayed after a wrong answer
      "prompt": "…",
      "hint": "…",                     // offered if the learner says ״רמז״
      "options": [                      // 3–4 options for recognize/why/apply; numbered 1..n by the app
        { "text": "…", "correct": true },
        { "text": "…", "misconception": "mc-…" },
        { "text": "…", "feedback": "…" } // wrong option without a known misconception
      ],
      "explanation": "…",              // played after a correct answer (why it is right)
      "rubric": ["…"],                 // explain-level only: key points a good answer mentions
      "minPoints": 3                   // explain-level only
    }
  ],
  "recap": "…"                         // 30-second spoken summary
}
```

Rules:
- Exactly one `correct: true` option per multiple-choice question. The app shuffles the options every
  time it reads a question, so the position in the file doesn't matter.
- Every wrong option has either `misconception` (must exist in the unit) or `feedback`.
- `explain` questions have no options; they have `rubric` and `minPoints` and are graded later
  (Gemini after the drive, Claude at night).
- Questions are listed in ladder order: recognize → why → apply → explain.

### Format 3 lessons (every new unit, from November 2026)

A lesson must take about 30 minutes in the car, everything included: the warm-up, the lesson, the
checks, the retell and the questions. In practice the drive adds about 15% to the estimate (thinking,
replays, wrong answers). New units set `"format": 3`, and the validator enforces:

- **Reading and explanation: 10 to 16 minutes**, at the speaking rate of the course's voice (125 words
  a minute for recorded courses, 140 for the phone's voice): about 1300 to 2000 words, in 3 to 7 main
  segments, each with a `check`. In a book course, about 800 words of the book and 700 to 900 words of
  explanation per lesson; split a long chapter over two or three lessons rather than rushing it.
- **End questions: 3 to 5 multiple-choice** (at least one `why` and two `apply`; `recognize` is
  optional, the checks already do that), **and exactly 3 short explain parts**. Each explain part asks
  for one idea that can be said in two or three sentences.
- **`retell`** of at most 230 words, **`reminder`**, at least 3 **`reviewQuestions`**, `simple` and `deep`.
- **Estimated total at most 27 minutes** (the validator warns above 27 and fails above 31).
- Worked examples still matter, but one good example told well beats two rushed ones.

Everything else from format 2 below still applies.

### Format 2 lessons (units written before November 2026)

Units with `"format": 2` were written to these rules, and the validator enforces them:

- **At least 15 minutes of main explanation** (about 2100 words at 140 words a minute), in 6–10
  segments, with at least two worked examples told out loud.
- **A `check` question on every segment**: one short multiple-choice question (`"level": "check"`)
  asked right after the segment. A wrong answer plays the remedial, offers the segment again, and
  asks the same check once more. Checks don't move Leitner boxes.
- **`retell`**: the whole unit told again in a different way (a new analogy), about 2 minutes,
  played after the lesson and before the final questions.
- **`reminder`**: two or three sentences that bring the unit back, read before warm-up and review
  questions on it.
- **`reviewQuestions`**: at least 3 short multiple-choice questions (recognize, why or apply) kept for
  later: warm-ups before new lessons and review units pick from them, weakest concepts first.
- **The explain-back is split into at least 3 short parts** (`q9`, `q10`, `q11`), each with its own
  narrow prompt (״חלק ראשון מתוך שלושה...״), a rubric of about 3 points and `minPoints` 2. Short
  answers are easier to say while driving and are recorded reliably.
- `teaches`: `{ "Term": "segment-id" }` for every substantive term the unit introduces.

### Review units

```jsonc
{
  "id": "r1-review-foundations", "type": "review", "concepts": [],
  "title": "…", "estimatedMinutes": 25,
  "covers": ["u01-…", "u02-…", "u03-…"],          // earlier lessons to go back over
  "fillIns": [{ "unit": "u02-…", "segment": "s4-proxy" }], // segments added after the learner heard the unit
  "explanations": { "main": [ { "id": "s1", "title": "…", "text": "…" } ] }, // short intro
  "recap": "…"
}
```

The app plays the intro, the fill-in segments with their checks, then for each covered lesson its
`reminder`, three review questions, two of its questions (the ones answered wrong first), and one
explain part. It ends with a summary of what is solid and what to strengthen; wrong answers send
those concepts back to box 0, so they return on the next drives with a reminder first.

### Warm-up before a lesson

Before a new lesson the app asks three short questions on earlier concepts, weakest first (lowest
box, recent mistakes). A weak concept gets its `reminder` read before the question.

## Replay and navigation (implemented by the app)

The learner can always go back. Voice commands, available at any point in a drive:

| Say | What happens |
|---|---|
| ״תחזור״ | Replays the last few sentences |
| ״מההתחלה״ | Restarts the current segment |
| ״אחורה״ / ״קדימה״ | Previous / next segment |
| ״תסביר אחרת״ | Plays the `simple` version of the current idea |
| ״תעמיק״ | Plays the `deep` version |
| ״מה זה …״ | Reads the term's definition from `terms` (this unit, then earlier units) |
| ״רמז״ | Reads the question's `hint` |
| ״תחזור על השאלה״ | Repeats the question and its options (also: ⏮ on the steering wheel during a question, or the ↺ button on the car screen) |
| ״עצור״ / ״המשך״ | Pause / resume |
| ״חזרה על יחידה …״ | Replays a finished unit from the start (review mode) |
| ״סיכום״ | Plays the `recap` of the current or last unit |

After a wrong answer the app plays the misconception's `remedial`, then offers
״רוצה לשמוע שוב את החלק הזה?״ and, on yes, replays the question's `segments` before asking a
new question on the same concept. Finished units stay available for replay from the car screen.

## Mastery rules (implemented by the app, listed here so generators know the target)

- The confidence question (״בטוח או לא בטוח?״) is off by default; every answer counts as sure.
- A concept's Leitner box goes up at most once a day, however many answers are right that day.
  A wrong answer sends it back to box 0.
- Wrong answer → play the misconception's `remedial`, then ask a different question on the same
  concept from another angle; repeat that concept again ~20 minutes later in the same drive.
- A concept is **mastered** after correct + sure answers on 3 occasions, on at least 2 different
  days, including at least one `apply` and one passed `explain` question.
- Mastered concepts still come back on a spaced-repetition schedule (by date, not by drive).

## Progress log `progress/<yyyy-mm-dd>-<am|pm>.json` (written by the app)

```jsonc
{
  "drive": { "start": "2026-11-01T07:05:00+02:00", "end": "…", "minutesListened": 82 },
  "events": [
    { "t": "…", "type": "answer", "question": "u01-backend-role/q2", "choice": 1,
      "correct": false, "sure": true, "misconception": "mc-client-validation-enough" },
    { "t": "…", "type": "explain", "question": "u01-backend-role/q8", "transcript": "…" },
    { "t": "…", "type": "live-question", "text": "…", "answered": false }
  ]
}
```
