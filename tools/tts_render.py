#!/usr/bin/env python3
"""Records a course's lessons with a good voice, sentence by sentence, for the app to play.

Runs in GitHub Actions (the network there is open). For each course whose course.json has a
"voice" section, every sentence the app may speak from a unit (book passages, explanations,
questions, answers, feedback) becomes one MP3 file, named by a hash of the sentence. The files
of a unit are packed in <unit>.zip, and manifest.json lists the zips. Both go to the release
audio-<course>. The app downloads the zips for the next lessons and, when it reaches a sentence
it has a file for, plays the file instead of the phone's voice.

The sentence split and the hash must match the app (engine/Text.kt and audio/AudioLibrary.kt):
split after . ! or ? followed by whitespace, collapse whitespace, SHA-1 of the UTF-8 text, first 16 hex.

Engines (course.json voice.engine):
  edge    Microsoft Edge read-aloud neural voices (he-IL-AvriNeural, he-IL-HilaNeural). Free, no key.
  fish    Fish Audio S2.1 Pro Free through OpenRouter. Free, needs OPENROUTER_API_KEY. Rate limited.
  gemini  Gemini TTS with a free AI Studio key, GEMINI_API_KEY. Rate limited.

Usage:
  tools/tts_render.py                       # all courses with a voice, render what changed, no upload
  tools/tts_render.py --upload              # also upload the changed zips and the manifest
  tools/tts_render.py --list COURSE         # print the sentences of each unit (no network)
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COURSES = ROOT / "content" / "courses"
APP_SRC = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "or3005" / "drivelearning"
BUILD = ROOT / "build" / "audio"
REPO = "or3005/drive-learning"
FORMAT = 1  # bump when the zip layout changes

# Java's \s (without UNICODE_CHARACTER_CLASS) is ASCII whitespace only.
WS = "[ \\t\\n\\x0b\\f\\r]"
BOUNDARY = re.compile(r"(?<=[.!?])" + WS + "+")
HEBREW = re.compile(r"[֐-׿]")
NUMBER_WORDS = ["אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש"]


def split_sentences(text):
    return [s.strip() for s in BOUNDARY.split(text) if s.strip()]


def normalize(sentence):
    return re.sub(WS + "+", " ", sentence).strip()


def sentence_hash(sentence):
    return hashlib.sha1(normalize(sentence).encode("utf-8")).hexdigest()[:16]


def load(path, default=None):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


# ---- what the app speaks ------------------------------------------------------------------------

def question_texts(q):
    """Everything the app may say for a question: prompt, options, feedback, explanation, hint."""
    out = [q.get("prompt", ""), q.get("explanation", "")]
    if q.get("hint"):
        out.append("רמז: " + q["hint"])
    for o in q.get("options", []):
        text = o.get("text", "").strip()
        out.append(text if text[-1:] in ".!?" else text + ".")
        if o.get("feedback"):
            out.append(o["feedback"])
    return out


def unit_texts(unit):
    """(text, role) pairs: role 'reading' for book passages, 'explain' for everything else."""
    pairs = []
    ex = unit.get("explanations", {})
    for seg in ex.get("main", []):
        if seg.get("reading"):
            pairs.append((seg["reading"], "reading"))
        pairs.append((seg.get("text", ""), "explain"))
        if seg.get("check"):
            pairs += [(t, "explain") for t in question_texts(seg["check"])]
    for kind in ("simple", "deep"):
        for seg in ex.get(kind, []):
            if seg.get("reading"):
                pairs.append((seg["reading"], "reading"))
            pairs.append((seg.get("text", ""), "explain"))
    for q in unit.get("questions", []) + unit.get("reviewQuestions", []):
        pairs += [(t, "explain") for t in question_texts(q)]
    for mc in unit.get("misconceptions", []):
        pairs.append((mc.get("remedial", ""), "explain"))
    for field in ("recap", "reminder", "retell"):
        pairs.append((unit.get(field, ""), "explain"))
    for t in unit.get("terms", []):
        pairs.append((t.get("definition", ""), "explain"))
    return pairs


def common_texts():
    """Fixed sentences the app says itself (string literals in the engine and the controller)."""
    literal = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
    interpolation = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")
    texts = [f"תשובה {w}." for w in NUMBER_WORDS]
    for name in ("engine/LessonEngine.kt", "LearningController.kt"):
        path = APP_SRC / name
        if not path.exists():
            continue
        for m in literal.finditer(path.read_text(encoding="utf-8")):
            for piece in interpolation.split(m.group(1).replace("\\n", " ")):
                for s in split_sentences(piece):
                    if s[-1:] in ".!?" and s[0] not in ",.;:-" and len(s.split()) >= 2 and len(HEBREW.findall(s)) >= 4:
                        texts.append(s)
    return [(t, "explain") for t in texts]


def sentences_of(pairs):
    """{hash: (sentence, role)}; a sentence that is also part of a book passage is read as one."""
    out = {}
    for text, role in pairs:
        for s in split_sentences(text or ""):
            h = sentence_hash(s)
            if h not in out or role == "reading":
                out[h] = (normalize(s), role)
    return out


# ---- engines ------------------------------------------------------------------------------------

class Engine:
    def __init__(self, voice):
        self.voice = voice
        self.name = voice.get("engine", "edge")

    def voice_for(self, role):
        return self.voice.get(role) or self.voice.get("explain") or "he-IL-HilaNeural"

    def signature(self, role):
        return f"{self.name}:{self.voice_for(role)}:{self.voice.get('rate', '+0%')}:{self.voice.get('style', '')}"

    async def render(self, text, role, path):
        if self.name == "edge":
            await edge_render(text, self.voice_for(role), self.voice.get("rate", "+0%"), path)
        elif self.name == "fish":
            await asyncio.to_thread(fish_render, text, self.voice_for(role), path)
        elif self.name == "gemini":
            await asyncio.to_thread(gemini_render, text, self.voice_for(role), self.voice.get("style", ""), path)
        else:
            raise SystemExit(f"unknown voice engine {self.name}")

    @property
    def parallel(self):
        return 8 if self.name == "edge" else 1


async def edge_render(text, voice, rate, path):
    import edge_tts  # pip install edge-tts
    for attempt in range(4):
        try:
            await edge_tts.Communicate(text, voice, rate=rate).save(str(path))
            if path.stat().st_size > 0:
                return
        except Exception as e:  # noqa: BLE001
            if attempt == 3:
                raise
            print(f"  edge retry ({e})", file=sys.stderr)
        await asyncio.sleep(2 * (attempt + 1))


def http_post(url, body, headers, timeout=120):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json", **headers})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def with_retries(fn, tries=5):
    for attempt in range(tries):
        try:
            return fn()
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 502, 503) or attempt == tries - 1:
                raise
            wait = 20 * (attempt + 1)
            print(f"  HTTP {e.code}, waiting {wait}s", file=sys.stderr)
            time.sleep(wait)


def fish_render(text, voice, path):
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        raise SystemExit("fish needs the OPENROUTER_API_KEY secret")
    body = {"model": "fish-audio/s2.1-pro-free", "input": text}
    if voice and not voice.startswith("he-IL"):
        body["voice"] = voice
    audio = with_retries(lambda: http_post("https://openrouter.ai/api/v1/audio/speech", body, {"Authorization": f"Bearer {key}"}))
    path.write_bytes(audio)


def gemini_tts_model(key):
    data = json.loads(urllib.request.urlopen(urllib.request.Request(
        "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", headers={"x-goog-api-key": key}), timeout=30).read())
    names = [m["name"].removeprefix("models/") for m in data.get("models", []) if "tts" in m["name"]]

    def version(n):
        m = re.search(r"gemini-(\d+(?:\.\d+)?)", n)
        return float(m.group(1)) if m else 0.0
    names.sort(key=lambda n: ("pro" in n, -version(n), "preview" in n))  # flash first (free tier), newest
    if not names:
        raise SystemExit("no Gemini TTS model for this key")
    return names[0]


_gemini_model = None


def gemini_render(text, voice, style, path):
    global _gemini_model
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        raise SystemExit("gemini needs the GEMINI_API_KEY secret")
    _gemini_model = _gemini_model or gemini_tts_model(key)
    prompt = (style + ": " if style else "") + text
    body = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice if not voice.startswith("he-IL") else "Charon"}}},
        },
    }
    raw = with_retries(lambda: http_post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{_gemini_model}:generateContent", body, {"x-goog-api-key": key}))
    part = json.loads(raw)["candidates"][0]["content"]["parts"][0]["inlineData"]
    pcm = base64.b64decode(part["data"])
    rate = int(re.search(r"rate=(\d+)", part.get("mimeType", "")).group(1)) if "rate=" in part.get("mimeType", "") else 24000
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "s16le", "-ar", str(rate), "-ac", "1", "-i", "pipe:0",
                    "-b:a", "48k", str(path)], input=pcm, check=True)


# ---- the release --------------------------------------------------------------------------------

def gh(*args, check=True):
    return subprocess.run(["gh", *args], capture_output=True, text=True, check=check)


def fetch_manifest(tag, dest):
    """The release's current manifest, or {} when there is no release yet."""
    dest.mkdir(parents=True, exist_ok=True)
    r = gh("release", "download", tag, "-R", REPO, "-D", str(dest), "-p", "manifest.json", "--clobber", check=False)
    if r.returncode != 0:
        print(f"  no manifest in {tag} yet ({r.stderr.strip()[:120]})")
        return {}
    return load(dest / "manifest.json", {})


def fetch_asset(tag, name, dest):
    gh("release", "download", tag, "-R", REPO, "-D", str(dest), "-p", name, "--clobber", check=False)
    return dest / name


def upload(tag, title, files):
    if gh("release", "view", tag, "-R", REPO, check=False).returncode != 0:
        gh("release", "create", tag, "-R", REPO, "--title", title, "--notes",
           "Recorded voice for the app. Made by tools/tts_render.py; the app downloads these files.", "--prerelease")
    for i in range(0, len(files), 20):
        gh("release", "upload", tag, "-R", REPO, "--clobber", *[str(f) for f in files[i:i + 20]])


# ---- rendering ----------------------------------------------------------------------------------

async def render_pack(name, sentences, engine, old_zip, old_entry, work):
    """Builds <name>.zip with one MP3 per sentence, reusing files from the old zip when the voice is the same."""
    reuse = {}
    if old_zip and old_zip.exists() and old_entry:
        old_roles = old_entry.get("hashes", {})
        old_sigs = old_entry.get("signatures", {})
        with zipfile.ZipFile(old_zip) as z:
            for h, (text, role) in sentences.items():
                if old_roles.get(h) == role and old_sigs.get(role) == engine.signature(role) and f"{h}.mp3" in z.namelist():
                    reuse[h] = z.read(f"{h}.mp3")
    todo = [h for h in sentences if h not in reuse]
    print(f"  {name}: {len(sentences)} sentences, {len(reuse)} reused, {len(todo)} to record")
    out_dir = work / name
    out_dir.mkdir(parents=True, exist_ok=True)
    sem = asyncio.Semaphore(engine.parallel)
    failed = []

    async def one(h):
        async with sem:
            text, role = sentences[h]
            try:
                await engine.render(text, role, out_dir / f"{h}.mp3")
            except SystemExit:
                raise
            except Exception as e:  # noqa: BLE001
                failed.append(h)
                print(f"  failed {h}: {e}", file=sys.stderr)
    await asyncio.gather(*(one(h) for h in todo))
    zip_path = work / f"{name}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED) as z:
        for h in sorted(sentences):
            if h in reuse:
                z.writestr(f"{h}.mp3", reuse[h])
            elif h not in failed and (out_dir / f"{h}.mp3").exists():
                z.write(out_dir / f"{h}.mp3", f"{h}.mp3")
    roles = {h: role for h, (_, role) in sentences.items() if h not in failed}
    sigs = {role: engine.signature(role) for role in set(roles.values())}
    digest = hashlib.sha1(json.dumps([sorted(roles.items()), sorted(sigs.items()), FORMAT]).encode()).hexdigest()[:16]
    return zip_path, {"file": f"{name}.zip", "sha": digest, "bytes": zip_path.stat().st_size,
                      "count": len(roles), "hashes": roles, "signatures": sigs}


def entry_digest(sentences, engine):
    roles = {h: role for h, (_, role) in sentences.items()}
    sigs = {role: engine.signature(role) for role in set(roles.values())}
    return hashlib.sha1(json.dumps([sorted(roles.items()), sorted(sigs.items()), FORMAT]).encode()).hexdigest()[:16]


async def render_course(cid, do_upload, max_units):
    course = load(COURSES / cid / "course.json", {})
    voice = course.get("voice")
    if not voice:
        return
    engine = Engine(voice)
    tag = f"audio-{cid}"
    print(f"{cid}: engine {engine.name}")
    work = BUILD / cid
    old_dir = work / "release"
    online = do_upload or bool(os.environ.get("CI"))
    old = fetch_manifest(tag, old_dir) if online else load(old_dir / "manifest.json", {})
    packs = {}
    for u in course.get("units", []):
        if u.get("status") == "planned":
            continue
        unit = load(COURSES / cid / "units" / f"{u['id']}.json")
        if unit:
            packs[u["id"]] = sentences_of(unit_texts(unit))
    packs["common"] = sentences_of(common_texts())
    manifest = {"course": cid, "format": FORMAT, "engine": engine.name, "units": {}}
    changed = []
    done = 0
    for name, sentences in packs.items():
        old_entry = old.get("units", {}).get(name)
        if old_entry and old_entry.get("sha") == entry_digest(sentences, engine):
            manifest["units"][name] = old_entry  # recorded already, nothing changed
            continue
        if max_units is not None and done >= max_units:
            if old_entry:
                manifest["units"][name] = old_entry  # keep the older recording until there is time for this one
            continue
        old_zip = fetch_asset(tag, old_entry["file"], old_dir) if old_entry and online else old_dir / f"{name}.zip"
        zip_path, entry = await render_pack(name, sentences, engine, old_zip, old_entry, work)
        manifest["units"][name] = entry
        changed.append(zip_path)
        done += 1
    (work / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    total = sum(e["bytes"] for e in manifest["units"].values())
    print(f"{cid}: {len(manifest['units'])} packs, {len(changed)} changed, {total / 1e6:.1f} MB in all")
    if do_upload and changed:
        upload(tag, f"Recorded voice: {course.get('title', cid)}", changed + [work / "manifest.json"])
        print(f"{cid}: uploaded to release {tag}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--upload", action="store_true")
    ap.add_argument("--course")
    ap.add_argument("--max-units", type=int, help="record at most this many changed packs (rate-limited engines)")
    ap.add_argument("--list", metavar="COURSE")
    args = ap.parse_args()
    if args.list:
        course = load(COURSES / args.list / "course.json", {})
        for u in course.get("units", []):
            unit = load(COURSES / args.list / "units" / f"{u['id']}.json")
            if unit:
                s = sentences_of(unit_texts(unit))
                print(f"{u['id']}: {len(s)} sentences, {sum(len(t.split()) for t, _ in s.values())} words")
        print(f"common: {len(sentences_of(common_texts()))} sentences")
        return
    index = load(COURSES / "index.json", {"courses": []})
    ids = [args.course] if args.course else [c["id"] for c in index.get("courses", [])]
    for cid in ids:
        asyncio.run(render_course(cid, args.upload, args.max_units))


if __name__ == "__main__":
    main()
