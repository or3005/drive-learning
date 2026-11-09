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
import shutil
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
    """(text, role) for everything the app may say for a question. The question itself (prompt, options,
    hint) is asked in the quiz voice; the answer's explanation and feedback are taught in the explain voice."""
    out = [(q.get("prompt", ""), "quiz"), (q.get("explanation", ""), "explain")]
    if q.get("hint"):
        out.append(("רמז: " + q["hint"], "quiz"))
    for o in q.get("options", []):
        text = o.get("text", "").strip()
        out.append((text if text[-1:] in ".!?" else text + ".", "quiz"))
        if o.get("feedback"):
            out.append((o["feedback"], "explain"))
    return out


def segment_texts(seg):
    """A segment is spoken as one text, the book passage and then the explanation (Segment.spoken in
    the app), so it is split the same way here. Sentences from the passage are read in the reading voice."""
    text, reading = seg.get("text", ""), (seg.get("reading") or "").strip()
    if not reading:
        return [(text, "explain")]
    passage = {normalize(s) for s in split_sentences(reading)}
    return [(s, "reading" if normalize(s) in passage else "explain") for s in split_sentences(reading + "\n" + text)]


def unit_texts(unit):
    """(text, role) pairs: role 'reading' for book passages, 'explain' for everything else."""
    pairs = []
    ex = unit.get("explanations", {})
    for kind in ("main", "simple", "deep"):
        for seg in ex.get(kind, []):
            pairs += segment_texts(seg)
            if seg.get("check"):
                pairs += question_texts(seg["check"])
    for q in unit.get("questions", []) + unit.get("reviewQuestions", []):
        pairs += question_texts(q)
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
    return [(t, "quiz") for t in texts]


ROLE_RANK = {"quiz": 0, "explain": 1, "reading": 2}


def sentences_of(pairs):
    """{hash: (sentence, role)}. A sentence that appears in more than one role takes the stronger one:
    a book passage over an explanation, an explanation over a question."""
    out = {}
    for text, role in pairs:
        for s in split_sentences(text or ""):
            h = sentence_hash(s)
            if h not in out or ROLE_RANK[role] > ROLE_RANK[out[h][1]]:
                out[h] = (normalize(s), role)
    return out


# ---- engines ------------------------------------------------------------------------------------

RENDER_VERSION = 2  # bump when the way a sentence is turned into audio changes
LATIN_RUN = re.compile("[A-Za-z][A-Za-z0-9.#+'’\\-]*(?:" + WS + "+[A-Za-z0-9][A-Za-z0-9.#+'’\\-]*)*")
# English voices that go with each Hebrew voice, best first (the second is older and always there).
ENGLISH_FOR = {
    "he-IL-HilaNeural": ["en-US-AvaNeural", "en-US-JennyNeural"],
    "he-IL-AvriNeural": ["en-US-AndrewNeural", "en-US-GuyNeural"],
}
HE = "\u0590-\u05FF"


def runs(sentence):
    """Hebrew and Latin runs, like engine/Text.kt Runs.split: [(text, is_latin)]."""
    out, last = [], 0
    for m in LATIN_RUN.finditer(sentence):
        if m.start() > last:
            out.append((sentence[last:m.start()], False))
        out.append((m.group(0), True))
        last = m.end()
    if last < len(sentence):
        out.append((sentence[last:], False))
    return [(t, latin) for t, latin in out if t.strip()]


class Pronunciation:
    """content/pronunciation.json for the recorded voices, like engine/Pronunciation.kt: niqqud for
    tricky Hebrew words, and how the English voice should say some terms (C# as C sharp)."""

    def __init__(self, data):
        def alt(keys):
            return "|".join(re.escape(k) for k in sorted(keys, key=len, reverse=True))
        self.hebrew = data.get("hebrew", {})
        self.stems = data.get("stems", {})
        self.prefixes = data.get("prefixes", {})
        self.english = {k.lower(): v for k, v in data.get("english", {}).items()}
        self.heb_re = re.compile(f"(?<![{HE}])(?:{alt(self.hebrew)})(?![{HE}])") if self.hebrew else None
        self.stem_re = re.compile(f"(?<![{HE}])([והבכלמש]{{0,3}}?)({alt(self.stems)})(?![{HE}])") if self.stems else None
        self.eng_re = re.compile(f"(?<![A-Za-z0-9])(?:{alt(data.get('english', {}))})(?![A-Za-z0-9])", re.I) if self.english else None
        self.trailing = re.compile(f"(?<![{HE}])([והבכלמש]{{1,3}})-\\s*$")
        self.hyphen = re.compile(f"(?<![{HE}])([והבכלמש]{{1,3}})-(?=[{HE}])")

    def hebrew_text(self, text):
        if self.heb_re:
            text = self.heb_re.sub(lambda m: self.hebrew.get(m.group(0), m.group(0)), text)
        if self.stem_re:
            text = self.stem_re.sub(lambda m: (self.prefixes.get(m.group(1), m.group(1)) if m.group(1) else "")
                                    + self.stems.get(m.group(2), m.group(2)), text)
        text = self.trailing.sub(lambda m: self.prefixes.get(m.group(1), m.group(1)), text)
        return self.hyphen.sub(r"\1", text)

    def english_text(self, text):
        return self.eng_re.sub(lambda m: self.english.get(m.group(0).lower(), m.group(0)), text) if self.eng_re else text


class Plain(Pronunciation):
    def __init__(self):
        super().__init__({})


class Engine:
    def __init__(self, voice):
        self.voice = voice
        self.name = voice.get("engine", "edge")
        self.rate = voice.get("rate", "+0%")
        self.ffmpeg = shutil.which("ffmpeg")
        self.pron = Plain()
        self.pron_sig = ""
        if voice.get("pronunciation"):
            path = ROOT / "content" / "pronunciation.json"
            data = load(path, {})
            self.pron = Pronunciation(data)
            self.pron_sig = hashlib.sha1(path.read_bytes()).hexdigest()[:8] if path.exists() else ""

    def voice_for(self, role):
        if role == "quiz":
            return self.voice.get("quiz") or self.voice.get("explain") or "he-IL-HilaNeural"
        return self.voice.get(role) or self.voice.get("explain") or "he-IL-HilaNeural"

    def english_for(self, role):
        hebrew = self.voice_for(role)
        mapping = self.voice.get("english", {})
        return ([mapping[hebrew]] if hebrew in mapping else []) + ENGLISH_FOR.get(hebrew, ["en-US-JennyNeural"])

    def signature(self, role):
        return (f"{self.name}:{self.voice_for(role)}:{self.english_for(role)[0]}:{self.rate}:"
                f"{self.voice.get('style', '')}:p{self.pron_sig}:v{RENDER_VERSION}")

    async def render(self, text, role, path):
        """One sentence to one MP3. With edge, English runs are read by an English voice and joined to the
        Hebrew ones; silences at the edges are trimmed so sentences follow each other at a natural pace."""
        work = Path(tempfile.mkdtemp(dir=path.parent))
        try:
            pieces = []  # (mp3 path, gap before it in seconds)
            if self.name == "edge":
                parts = runs(text) if HEBREW.search(text) else [(text, True)]
                gap = 0.0
                for i, (part, latin) in enumerate(parts):
                    if not re.search(r"[A-Za-z0-9\u05D0-\u05EA]", part):
                        gap = 0.18 if re.search(r"[,;:]", part) else gap  # just punctuation between runs
                        continue
                    piece = work / f"{i}.mp3"
                    if latin:
                        await edge_render(self.pron.english_text(part), self.english_for(role), self.rate, piece)
                    else:
                        await edge_render(self.pron.hebrew_text(part), [self.voice_for(role)], self.rate, piece)
                    pieces.append((piece, gap if pieces else 0.0))
                    gap = 0.18 if part.rstrip()[-1:] in ",;:" else 0.05
            else:
                piece = work / "0.mp3"
                if self.name == "fish":
                    await asyncio.to_thread(fish_render, text, self.voice_for(role), piece)
                elif self.name == "gemini":
                    await asyncio.to_thread(gemini_render, text, self.voice_for(role), self.voice.get("style", ""), piece)
                else:
                    raise SystemExit(f"unknown voice engine {self.name}")
                pieces.append((piece, 0.0))
            if not pieces:
                raise ValueError("nothing to say")
            await asyncio.to_thread(join, pieces, path, self.ffmpeg)
        finally:
            shutil.rmtree(work, ignore_errors=True)

    @property
    def parallel(self):
        return 8 if self.name == "edge" else 1


def join(pieces, out, ffmpeg):
    """Joins the pieces with short gaps, trims silence at their edges, and leaves a short pause at the end."""
    if not ffmpeg:
        out.write_bytes(b"".join(p.read_bytes() for p, _ in pieces))
        return
    trim = "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse"
    args, chain, labels = [ffmpeg, "-loglevel", "error", "-y"], [], ["[lead]"]
    chain.append("aevalsrc=0:s=24000:d=0.05[lead]")
    for i, (piece, gap) in enumerate(pieces):
        args += ["-i", str(piece)]
        chain.append(f"[{i}:a]aformat=sample_rates=24000:channel_layouts=mono,{trim}[p{i}]")
        if i and gap > 0:
            chain.append(f"aevalsrc=0:s=24000:d={gap}[g{i}]")
            labels.append(f"[g{i}]")
        labels.append(f"[p{i}]")
    chain.append("aevalsrc=0:s=24000:d=0.32[tail]")
    labels.append("[tail]")
    chain.append("".join(labels) + f"concat=n={len(labels)}:v=0:a=1[out]")
    args += ["-filter_complex", ";".join(chain), "-map", "[out]", "-ac", "1", "-ar", "24000", "-b:a", "48k", str(out)]
    subprocess.run(args, check=True)


BAD_VOICES = set()  # voices that never answered in this run


async def edge_render(text, voices, rate, path):
    import edge_tts  # pip install edge-tts
    last = None
    usable = [v for v in voices if v not in BAD_VOICES] or voices[-1:]
    for n, voice in enumerate(usable):
        for attempt in range(4):
            try:
                await edge_tts.Communicate(text, voice, rate=rate).save(str(path))
                if path.exists() and path.stat().st_size > 0:
                    return
            except Exception as e:  # noqa: BLE001
                last = e
                if "voice" in str(e).lower():
                    break  # this voice doesn't exist: try the next one
                print(f"  edge retry ({e})", file=sys.stderr)
            await asyncio.sleep(2 * (attempt + 1))
        if n < len(usable) - 1:
            BAD_VOICES.add(voice)  # fall back to the next voice from now on
            print(f"  voice {voice} gave no audio; using {usable[n + 1]}", file=sys.stderr)
    raise RuntimeError(f"edge failed: {last}")


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
