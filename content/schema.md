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
  in an earlier unit.
- `status`: `planned` (no file yet) → `draft` (file exists, not reviewed) → `ready` (reviewed).

## Unit file `units/<unit-id>.json`

```jsonc
{
  "id": "u01-backend-role",
  "concepts": ["backend-role"],
  "title": "…",
  "estimatedMinutes": 15,
  "explanations": {
    "main":   [ { "id": "s1", "title": "…", "text": "…" } ],  // the lesson, 4–6 segments
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
- Exactly one `correct: true` option per multiple-choice question.
- Every wrong option has either `misconception` (must exist in the unit) or `feedback`.
- `explain` questions have no options; they have `rubric` and `minPoints` and are graded later
  (Gemini after the drive, Claude at night).
- Questions are listed in ladder order: recognize → why → apply → explain.

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
| ״תחזור על השאלה״ | Repeats the question and its options |
| ״עצור״ / ״המשך״ | Pause / resume |
| ״חזרה על יחידה …״ | Replays a finished unit from the start (review mode) |
| ״סיכום״ | Plays the `recap` of the current or last unit |

After a wrong answer the app plays the misconception's `remedial`, then offers
״רוצה לשמוע שוב את החלק הזה?״ and, on yes, replays the question's `segments` before asking a
new question on the same concept. Finished units stay available for replay from the car screen.

## Mastery rules (implemented by the app, listed here so generators know the target)

- After every answer the app asks ״בטוח או לא בטוח?״. Correct + unsure counts as weak.
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
