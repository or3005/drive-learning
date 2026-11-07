#!/usr/bin/env python3
"""Records the same short passage with every free voice available, to choose the voice by ear.

Runs in GitHub Actions and uploads the MP3 files to the release voice-samples. Edge voices need
nothing; Fish Audio needs the OPENROUTER_API_KEY secret and Gemini the GEMINI_API_KEY secret
(both free). An engine without its secret is skipped. Changing this file records them again.
"""
import asyncio
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tts_render as t  # noqa: E402

# Chapter 17 of The Prince, translated into Hebrew from the public-domain English translation.
READING = (
    "מכאן עולה שאלה: האם עדיף שיאהבו אותך יותר משיפחדו ממך, או להפך. "
    "התשובה היא שהיה רצוי גם זה וגם זה. אבל מכיוון שקשה לחבר את שניהם יחד, "
    "בטוח הרבה יותר שיפחדו ממך מאשר שיאהבו אותך, אם צריך לוותר על אחד מהם. "
    "כי על בני האדם אפשר לומר באופן כללי שהם כפויי טובה, הפכפכים, צבועים, בורחים מסכנה ותאבי בצע. "
    "כל עוד אתה מיטיב איתם, הם כולם שלך: הם מציעים לך את דמם, את רכושם, את חייהם ואת ילדיהם, כל עוד הצורך רחוק. "
    "אבל כשהוא מתקרב, הם מסתובבים נגדך."
)
EXPLAIN = (
    "שים לב למה שמקיאוולי עושה כאן. הוא לא אומר שאהבה היא דבר רע. "
    "הוא אומר שאי אפשר לסמוך עליה ברגע האמת, כי אהבה תלויה ברצון של אחרים, ופחד תלוי בך."
)

SAMPLES = [
    ("1-edge-avri", {"engine": "edge", "explain": "he-IL-AvriNeural"}),
    ("2-edge-hila", {"engine": "edge", "explain": "he-IL-HilaNeural"}),
    ("3-fish-s2.1-free", {"engine": "fish"}),
    ("4-gemini-charon", {"engine": "gemini", "explain": "Charon",
                          "style": "Read in Hebrew, calmly and clearly, like an audiobook narrator"}),
    ("5-gemini-kore", {"engine": "gemini", "explain": "Kore",
                        "style": "Read in Hebrew, calmly and clearly, like an audiobook narrator"}),
]
NEEDS = {"fish": "OPENROUTER_API_KEY", "gemini": "GEMINI_API_KEY"}


async def main():
    out = t.ROOT / "build" / "voice-samples"
    out.mkdir(parents=True, exist_ok=True)
    made = []
    for name, voice in SAMPLES:
        need = NEEDS.get(voice["engine"])
        if need and not os.environ.get(need):
            print(f"{name}: skipped, no {need} secret")
            continue
        engine = t.Engine(voice)
        path = out / f"{name}.mp3"
        try:
            await engine.render(READING + " " + EXPLAIN, "explain", path)
            made.append(path)
            print(f"{name}: {path.stat().st_size // 1024} KB")
        except BaseException as e:  # noqa: BLE001
            print(f"{name}: failed: {e}")
    if made and "--upload" in sys.argv:
        t.upload("voice-samples", "Voice samples", made)


if __name__ == "__main__":
    asyncio.run(main())
