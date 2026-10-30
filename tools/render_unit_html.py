#!/usr/bin/env python3
"""Render written units into one self-contained RTL HTML page for reviewing on a phone.

Usage: python3 tools/render_unit_html.py [course_dir]
Writes content/courses/<course>/review/index.html with every unit that has a file.
"""
import html
import json
import sys
from pathlib import Path

LEVEL_LABEL = {"recognize": "זיהוי", "why": "למה", "apply": "יישום", "explain": "הסבר במילים שלך"}

CSS = """
:root{--bg:#f7f6f3;--card:#fff;--ink:#1f2328;--muted:#5f6670;--line:#e6e3dc;--accent:#2f6f5e;
--ok:#e7f4ee;--okline:#2f8a63;--bad:#fbecec;--badline:#b74b4b}
@media (prefers-color-scheme:dark){:root{--bg:#16181b;--card:#1f2226;--ink:#e8e6e1;--muted:#9aa1a9;
--line:#2e3237;--accent:#7cc4ad;--ok:#1d3029;--okline:#5fbf94;--bad:#3a2224;--badline:#e08585}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font-family:'Heebo',system-ui,sans-serif;
font-size:17px;line-height:1.75;-webkit-text-size-adjust:100%}
main{max-width:720px;margin:0 auto;padding:20px 16px 64px}
header h1{font-size:1.6rem;line-height:1.3;margin:8px 0 4px}
.meta{color:var(--muted);font-size:.9rem;margin-bottom:12px}
nav{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0 20px}
nav a{color:var(--accent);text-decoration:none;border:1px solid var(--line);border-radius:999px;
padding:4px 12px;font-size:.9rem;background:var(--card)}
h2{font-size:1.25rem;margin:32px 0 12px;padding-bottom:6px;border-bottom:2px solid var(--line)}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px;margin:12px 0}
.card h3{font-size:1.05rem;margin:0 0 6px;color:var(--accent)}
.card p{margin:0}
.q .lvl{display:inline-block;font-size:.8rem;color:var(--muted);border:1px solid var(--line);
border-radius:6px;padding:0 8px;margin-bottom:6px}
.q .prompt{font-weight:600;margin-bottom:10px}
.opt{display:block;width:100%;text-align:right;font:inherit;color:inherit;background:transparent;
border:1px solid var(--line);border-radius:10px;padding:10px 12px;margin:8px 0;cursor:pointer}
.opt .n{color:var(--muted);margin-left:8px}
.opt.ok{background:var(--ok);border-color:var(--okline)}
.opt.bad{background:var(--bad);border-color:var(--badline)}
.fb{display:none;font-size:.95rem;margin:-2px 4px 10px;color:var(--muted)}
.fb.show{display:block}
details{margin-top:8px}
summary{cursor:pointer;color:var(--accent);font-size:.95rem}
.rubric li{margin:2px 0}
.belief{font-weight:600}
.terms{margin:0}.terms dt{font-weight:700;margin-top:10px;color:var(--accent);text-align:right}
.terms dd{margin:2px 0 0}
.picker{display:grid;grid-template-columns:1fr;gap:6px;margin:12px 0 8px}
.picker a{display:block;color:var(--ink);text-decoration:none;border:1px solid var(--line);border-radius:10px;
padding:8px 12px;background:var(--card);font-size:.95rem}
.picker a .num{color:var(--muted);margin-left:6px}
.picker a.on{border-color:var(--accent);box-shadow:inset 0 0 0 1px var(--accent)}
"""

REPLAY = [
    ("תחזור", "חוזר על המשפטים האחרונים"),
    ("מההתחלה", "מתחיל את החלק הנוכחי מחדש"),
    ("אחורה / קדימה", "עובר לחלק הקודם או הבא"),
    ("תסביר אחרת", "מסביר שוב בגרסה פשוטה, עם משל"),
    ("תעמיק", "מוסיף הסבר מעמיק יותר"),
    ("מה זה …", "מקריא את ההגדרה מהמילון"),
    ("רמז", "נותן רמז לשאלה"),
    ("תחזור על השאלה", "קורא שוב את השאלה והתשובות"),
    ("סיכום", "מקריא את הסיכום של היחידה"),
    ("חזרה על יחידה …", "משמיע יחידה שכבר סיימת מההתחלה"),
    ("עצור / המשך", "עוצר וממשיך"),
]

JS = """
function show(){
  var units=document.querySelectorAll('section.unit');
  var id=(location.hash||'').slice(1);
  var found=false;
  units.forEach(function(u){ if(u.id===id){found=true;} });
  if(!found && units.length){ id=units[0].id; }
  units.forEach(function(u){ u.hidden = u.id!==id; });
  document.querySelectorAll('.picker a').forEach(function(a){ a.classList.toggle('on', a.getAttribute('href')==='#'+id); });
}
window.addEventListener('hashchange', function(){ show(); window.scrollTo(0,0); });
show();
document.querySelectorAll('.jump').forEach(function(a){
  a.addEventListener('click', function(e){
    e.preventDefault();
    var t=document.getElementById(a.dataset.target); if(t){ t.scrollIntoView({behavior:'smooth'}); }
  });
});
document.querySelectorAll('.q').forEach(function(q){
  q.querySelectorAll('.opt').forEach(function(btn){
    btn.addEventListener('click',function(){
      var ok=btn.dataset.correct==='1';
      btn.classList.add(ok?'ok':'bad');
      var fb=btn.nextElementSibling; if(fb){fb.classList.add('show');}
      if(ok){var ex=q.querySelector('.explain'); if(ex){ex.classList.add('show');}}
    });
  });
});
"""


def esc(s):
    return html.escape(s, quote=True)


def unit_parts(unit, add):
    """Append the HTML for one unit. All element ids are prefixed with the unit number."""
    uid = unit["id"].split("-")[0]
    mcs = {m["id"]: m for m in unit["misconceptions"]}
    seg_title = {s["id"]: s["title"] for s in unit["explanations"]["main"]}

    def jump(label, name):
        return f"<a href='#{uid}' class='jump' data-target='{uid}-{name}'>{label}</a>"

    add(f"<section class='unit' id='{uid}'>")
    add(f"<header><h1>{esc(unit['title'])}</h1>")
    add(f"<div class='meta'>כ-{unit['estimatedMinutes']} דקות · {len(unit['questions'])} שאלות</div></header>")
    add("<nav>" + jump("ההסבר", "lesson") + jump("שאלות", "questions") + jump("מילון מונחים", "terms")
        + jump("טעויות נפוצות", "mistakes") + jump("גרסאות נוספות", "variants") + "</nav>")

    add(f"<h2 id='{uid}-lesson'>ההסבר</h2>")
    for seg in unit["explanations"]["main"]:
        add(f"<div class='card'><h3>{esc(seg['title'])}</h3><p>{esc(seg['text'])}</p></div>")

    add(f"<h2 id='{uid}-questions'>שאלות</h2>")
    add("<p class='meta'>לחיצה על תשובה מראה מה האפליקציה תגיד אם תבחר בה.</p>")
    for n, q in enumerate(unit["questions"], 1):
        add("<div class='card q'>")
        add(f"<span class='lvl'>{n} · {LEVEL_LABEL[q['level']]}</span>")
        add(f"<div class='prompt'>{esc(q['prompt'])}</div>")
        for i, o in enumerate(q.get("options", []), 1):
            correct = "1" if o.get("correct") else "0"
            add(f"<button class='opt' data-correct='{correct}'><span class='n'>{i}.</span>{esc(o['text'])}</button>")
            if o.get("correct"):
                add(f"<div class='fb'>{esc(q['explanation'])}</div>")
            elif o.get("misconception"):
                add(f"<div class='fb'>{esc(mcs[o['misconception']]['remedial'])}</div>")
            else:
                add(f"<div class='fb'>{esc(o['feedback'])}</div>")
        if q.get("rubric"):
            add(f"<details><summary>מה תשובה טובה צריכה להזכיר (לפחות {q['minPoints']})</summary><ul class='rubric'>")
            add("".join(f"<li>{esc(p)}</li>" for p in q["rubric"]))
            add("</ul></details>")
        add(f"<details><summary>רמז</summary><p>{esc(q['hint'])}</p></details>")
        taught = " · ".join(esc(seg_title[s]) for s in q.get("segments", []))
        add(f"<p class='meta'>אם טועים, אפשר לשמוע שוב: {taught}</p>")
        add("</div>")

    add(f"<h2 id='{uid}-terms'>מילון מונחים</h2>")
    add("<p class='meta'>ברכב אפשר לשאול ״מה זה …?״ ולשמוע את ההגדרה. מונחים מיחידות קודמות זמינים גם הם.</p>")
    add("<div class='card'><dl class='terms'>")
    for t in unit.get("terms", []):
        add(f"<dt dir='ltr'>{esc(t['term'])}</dt><dd>{esc(t['definition'])}</dd>")
    add("</dl></div>")
    add(f"<h2 id='{uid}-mistakes'>טעויות נפוצות</h2>")
    for m in unit["misconceptions"]:
        add(f"<div class='card'><p class='belief'>{esc(m['belief'])}</p>"
            f"<details><summary>ההסבר שמושמע</summary><p>{esc(m['remedial'])}</p></details></div>")

    add(f"<h2 id='{uid}-variants'>גרסאות נוספות</h2>")
    simple = " ".join(esc(s["text"]) for s in unit["explanations"]["simple"])
    deep = " ".join(esc(s["text"]) for s in unit["explanations"]["deep"])
    add(f"<div class='card'><h3>גרסה פשוטה</h3><p class='meta'>מושמעת כשקשה</p><p>{simple}</p></div>")
    add(f"<div class='card'><h3>העמקה</h3><p class='meta'>מושמעת כשהבסיס יושב</p><p>{deep}</p></div>")
    add(f"<h2>סיכום</h2><div class='card'><p>{esc(unit['recap'])}</p></div>")
    add("</section>")


def render(course_dir: Path) -> Path:
    course = json.loads((course_dir / "course.json").read_text(encoding="utf-8"))
    units = []
    for u in course["units"]:
        path = course_dir / "units" / f"{u['id']}.json"
        if path.exists():
            units.append(json.loads(path.read_text(encoding="utf-8")))
    total = sum(u["estimatedMinutes"] for u in units)
    parts = []
    add = parts.append
    add(f"<div class='meta'>{esc(course['title'])} · יחידות לבדיקה</div>")
    add(f"<div class='meta'>{len(units)} יחידות מוכנות · כ-{total} דקות האזנה</div>")
    add("<div class='picker'>")
    for n, u in enumerate(units, 1):
        uid = u["id"].split("-")[0]
        add(f"<a href='#{uid}'><span class='num'>{n}.</span>{esc(u['title'])}</a>")
    add("</div>")
    for u in units:
        unit_parts(u, add)
    add("<h2 id='replay'>חזרה ברכב</h2><div class='card'><p class='meta'>אפשר להגיד בכל רגע בנסיעה:</p><dl class='terms'>")
    for say, does in REPLAY:
        add(f"<dt>״{esc(say)}״</dt><dd>{esc(does)}</dd>")
    add("</dl></div>")

    page = (
        "<!DOCTYPE html><html lang='he' dir='rtl'><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        f"<title>{esc(course['title'])} · יחידות לבדיקה</title>"
        "<link rel='preconnect' href='https://fonts.googleapis.com'>"
        "<link href='https://fonts.googleapis.com/css2?family=Heebo:wght@400;600;700&display=swap' rel='stylesheet'>"
        f"<style>{CSS}</style></head><body><main>{''.join(parts)}</main><script>{JS}</script></body></html>"
    )
    review_dir = course_dir / "review"
    review_dir.mkdir(exist_ok=True)
    target = review_dir / "index.html"
    target.write_text(page, encoding="utf-8")
    return target


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent / "content" / "courses"
    dirs = [Path(a) for a in sys.argv[1:]] or sorted(d for d in root.iterdir() if d.is_dir())
    for d in dirs:
        print("wrote", render(d.resolve()))
