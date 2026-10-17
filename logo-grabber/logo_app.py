#!/usr/bin/env python3
"""logo_app - a browser UI for logo_grabber.

    python logo_app.py            # opens http://127.0.0.1:8765 in your browser
    python logo_app.py --port 9000 --no-browser

Search teams by name, browse by league, paste any PNG or page link, preview
on a transparency checkerboard, and download one PNG or a ZIP of several.
Runs locally only (binds to 127.0.0.1); standard library only.
"""

import argparse
import io
import json
import os
import re
import sys
import threading
import urllib.parse
import webbrowser
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import logo_grabber as lg  # noqa: E402

_teams = None
_teams_lock = threading.Lock()
_img_cache = {}


def teams(refresh=False):
    global _teams
    with _teams_lock:
        if _teams is None or refresh:
            _teams = lg.load_index(refresh)
        return _teams


def get_png(url):
    if url not in _img_cache:
        data, ctype, _ = lg.fetch(url)
        if not data.startswith(lg.PNG_MAGIC):
            raise ValueError(f"not a PNG ({ctype or 'unknown type'})")
        if len(_img_cache) > 600:
            _img_cache.clear()
        _img_cache[url] = data
    return _img_cache[url]


def inspect_link(url):
    """A link becomes a list of {name, url}: one item for a PNG, many for a page."""
    data, ctype, final = lg.fetch(url)
    if data.startswith(lg.PNG_MAGIC):
        _img_cache[final] = data
        base = urllib.parse.unquote(os.path.basename(urllib.parse.urlparse(final).path))
        return [{"name": lg.clean_name(base.rsplit(".", 1)[0]) or "logo", "url": final, "league": "link"}]
    if "html" not in ctype.lower():
        raise ValueError(f"that link is neither a PNG nor a web page ({ctype or 'unknown'})")
    imgs = lg.page_images(data.decode("utf-8", "replace"), final)
    if not imgs:
        raise ValueError("no PNG images found on that page")
    return [{"name": n, "url": u, "league": "page"} for n, u, _ in imgs]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json", extra=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        try:
            if u.path == "/":
                return self.send(200, PAGE, "text/html; charset=utf-8")
            if u.path == "/api/teams":
                t = teams(q.get("refresh") == "1")
                leagues = sorted({x["league"] for x in t}, key=lambda l: (l not in lg.NCAA, l))
                return self.send(200, {"teams": t, "leagues": leagues, "ncaa": lg.NCAA})
            if u.path == "/api/search":
                hits = lg.search(q.get("q", ""), teams(), q.get("league") or None)
                return self.send(200, hits[:60])
            if u.path == "/api/inspect":
                return self.send(200, inspect_link(q.get("url", "").strip()))
            if u.path in ("/api/img", "/api/download"):
                data = get_png(q["url"])
                extra = {"Cache-Control": "max-age=86400"}
                if u.path == "/api/download":
                    extra["Content-Disposition"] = f'attachment; filename="{lg.safe_name(q.get("name", "logo"))}.png"'
                return self.send(200, data, "image/png", extra)
            self.send(404, {"error": "not found"})
        except Exception as e:
            self.send(400, {"error": str(e)})

    def do_POST(self):
        if self.path != "/api/zip":
            return self.send(404, {"error": "not found"})
        try:
            items = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0)) or 0))
            buf, used, failed, sources = io.BytesIO(), set(), [], []
            with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
                for it in items[:500]:
                    try:
                        data = get_png(it["url"])
                    except Exception as e:
                        failed.append(f"{it.get('name')}: {e}")
                        continue
                    fn, n = lg.safe_name(it.get("name", "logo")), 2
                    base = fn
                    while fn in used:
                        fn, n = f"{base}_{n}", n + 1
                    used.add(fn)
                    z.writestr(fn + ".png", data)
                    sources.append(f"{fn}.png: {it['url']}")
                note = "\n".join(sources) + ("\n\nNot retrieved:\n" + "\n".join(failed) if failed else "") + "\n"
                z.writestr("sources.txt", note)
            self.send(200, buf.getvalue(), "application/zip",
                      {"Content-Disposition": 'attachment; filename="Team_Logos.zip"'})
        except Exception as e:
            self.send(400, {"error": str(e)})


PAGE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Logo Locker</title>
<style>
:root{--bg:#0d1117;--panel:#161b22;--line:#30363d;--text:#e6edf3;--muted:#8b949e;--accent:#f0b429;--accent2:#ff6b35;--ok:#3fb950;--err:#f85149}
@media (prefers-color-scheme: light){:root{--bg:#f6f8fa;--panel:#fff;--line:#d0d7de;--text:#1f2328;--muted:#656d76;--accent:#b7791f;--accent2:#d9480f}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
header{position:sticky;top:0;z-index:5;background:color-mix(in srgb,var(--bg) 88%,transparent);backdrop-filter:blur(10px);border-bottom:1px solid var(--line);padding:14px 16px}
.wrap{max-width:1200px;margin:0 auto}
h1{margin:0 0 10px;font-size:22px;letter-spacing:.5px}
h1 span{background:linear-gradient(90deg,var(--accent),var(--accent2));-webkit-background-clip:text;background-clip:text;color:transparent}
.bar{display:flex;gap:8px;flex-wrap:wrap}
input[type=text]{flex:1;min-width:200px;padding:11px 14px;border-radius:10px;border:1px solid var(--line);background:var(--panel);color:var(--text);font-size:16px;outline:none}
input[type=text]:focus{border-color:var(--accent)}
button{padding:10px 14px;border-radius:10px;border:1px solid var(--line);background:var(--panel);color:var(--text);font-weight:600;cursor:pointer;font-size:14px}
button:hover{border-color:var(--accent)}
button.primary{background:var(--accent);color:#111;border-color:var(--accent)}
button:disabled{opacity:.45;cursor:default}
.chips{display:flex;gap:6px;overflow-x:auto;padding:10px 0 2px;scrollbar-width:thin}
.chip{white-space:nowrap;padding:5px 11px;border-radius:999px;border:1px solid var(--line);font-size:13px;cursor:pointer;color:var(--muted);background:transparent}
.chip.on{background:var(--accent);color:#111;border-color:var(--accent)}
main{padding:16px}
.status{color:var(--muted);margin:0 0 12px;min-height:20px}
.status.err{color:var(--err)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px}
.card{position:relative;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:12px 10px 10px;cursor:pointer;transition:transform .12s,border-color .12s;text-align:center}
.card:hover{transform:translateY(-3px);border-color:var(--accent)}
.card.sel{border-color:var(--accent);box-shadow:0 0 0 2px var(--accent) inset}
.card img{width:100%;aspect-ratio:1;object-fit:contain;display:block;background:radial-gradient(circle,#f4f6f8 0 62%,#dfe3e8 100%);border-radius:10px;padding:10px}
.card .n{font-size:13px;margin-top:8px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.card .l{font-size:11px;color:var(--muted);text-transform:uppercase;letter-spacing:.6px}
.tick{position:absolute;top:8px;left:8px;width:22px;height:22px;border-radius:6px;border:1px solid var(--line);background:var(--bg);display:grid;place-items:center;font-size:13px;color:transparent;z-index:1}
.card.sel .tick{background:var(--accent);color:#111;border-color:var(--accent)}
.tray{position:fixed;left:50%;bottom:16px;transform:translateX(-50%) translateY(140%);transition:transform .2s;background:var(--panel);border:1px solid var(--accent);border-radius:14px;padding:10px 12px;display:flex;gap:10px;align-items:center;box-shadow:0 10px 30px #0006;z-index:6;max-width:calc(100% - 32px)}
.tray.show{transform:translateX(-50%)}
dialog{border:1px solid var(--line);border-radius:18px;background:var(--panel);color:var(--text);padding:0;width:min(560px,calc(100% - 32px))}
dialog::backdrop{background:#000a}
.view{padding:18px}
.stage{border-radius:12px;padding:20px;display:grid;place-items:center;transition:background .2s}
.stage.check{background:repeating-conic-gradient(#888 0 25%,#ccc 0 50%) 0 0/22px 22px}
.stage img{max-width:100%;max-height:360px}
.bgs{display:flex;gap:6px;margin:10px 0}
.bgs button{width:30px;height:30px;padding:0;border-radius:50%}
.meta{color:var(--muted);font-size:13px;word-break:break-all;margin:8px 0 14px}
.row{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap}
.empty{text-align:center;color:var(--muted);padding:60px 10px}
</style></head>
<body>
<header><div class="wrap">
  <h1>🏈 Logo <span>Locker</span></h1>
  <div class="bar">
    <input id="q" type="text" placeholder="Team name (clemson, the u, pitt...) or paste a link" autocomplete="off" autofocus>
    <button id="go" class="primary">Find</button>
    <button id="lucky" title="Show me a random team">🎲</button>
  </div>
  <div class="chips" id="chips"></div>
</div></header>
<main class="wrap">
  <p class="status" id="status">Loading team index... (the first run takes a few seconds)</p>
  <div class="grid" id="grid"></div>
</main>
<div class="tray" id="tray">
  <strong id="count">0 selected</strong>
  <button id="selAll">Select all shown</button>
  <button id="clear">Clear</button>
  <button id="zip" class="primary">⬇ Download ZIP</button>
</div>
<dialog id="dlg"><div class="view">
  <div class="stage check" id="stage"><img id="big" alt=""></div>
  <div class="bgs" title="Preview background">
    <button data-bg="check" style="background:repeating-conic-gradient(#888 0 25%,#ccc 0 50%) 0 0/10px 10px"></button>
    <button data-bg="#ffffff" style="background:#fff"></button>
    <button data-bg="#000000" style="background:#000"></button>
    <button data-bg="#1b4d2e" style="background:#1b4d2e" title="Turf"></button>
  </div>
  <h2 id="dn" style="margin:0"></h2>
  <div class="meta" id="dm"></div>
  <div class="row">
    <button id="dsel">Add to ZIP</button>
    <button id="dcopy">Copy link</button>
    <button id="dclose">Close</button>
    <a id="ddl"><button class="primary">⬇ Download PNG</button></a>
  </div>
</div></dialog>
<script>
const $ = s => document.querySelector(s);
let ALL = [], LEAGUES = [], league = "", shown = [], picked = new Map(), current = null, timer;
const img = u => "/api/img?url=" + encodeURIComponent(u);
const label = l => l.replace(/-/g, " ");

function status(t, err){ const s=$("#status"); s.textContent=t; s.className="status"+(err?" err":""); }

function render(list, msg){
  shown = list; const g = $("#grid"); g.innerHTML = "";
  if(!list.length){ g.innerHTML = '<div class="empty" style="grid-column:1/-1">No logos here. Try another name or paste a link.</div>'; }
  for(const t of list){
    const c = document.createElement("div"); c.className = "card" + (picked.has(t.url) ? " sel" : "");
    c.innerHTML = '<div class="tick">✓</div><img loading="lazy" alt=""><div class="n"></div><div class="l"></div>';
    c.querySelector("img").src = img(t.url);
    c.querySelector(".n").textContent = t.name; c.querySelector(".n").title = t.name;
    c.querySelector(".l").textContent = label(t.league || "");
    c.querySelector(".tick").onclick = e => { e.stopPropagation(); toggle(t); c.classList.toggle("sel", picked.has(t.url)); };
    c.onclick = () => open_(t);
    g.appendChild(c);
  }
  status(msg ?? (list.length + " logo" + (list.length===1?"":"s")));
}

function toggle(t){ picked.has(t.url) ? picked.delete(t.url) : picked.set(t.url, t); tray(); }
function tray(){ $("#count").textContent = picked.size + " selected"; $("#tray").classList.toggle("show", picked.size > 0); }

function chips(){
  const box = $("#chips"); box.innerHTML = "";
  for(const l of ["", ...LEAGUES]){
    const b = document.createElement("button"); b.className = "chip" + (l===league?" on":"");
    b.textContent = l ? label(l).toUpperCase() : "ALL"; b.onclick = () => { league = l; chips(); run(); };
    box.appendChild(b);
  }
}

async function api(path){ const r = await fetch(path); const j = await r.json(); if(!r.ok) throw new Error(j.error); return j; }

async function run(){
  const q = $("#q").value.trim();
  if(/^[a-z][a-z0-9+.-]*:\/\//i.test(q)){
    status("Looking at that link...");
    try { const items = await api("/api/inspect?url=" + encodeURIComponent(q));
          render(items, items.length === 1 ? "Found 1 PNG at that link" : `Found ${items.length} PNGs on that page`);
          if(items.length === 1) open_(items[0]); }
    catch(e){ render([], ""); status(e.message, true); }
    return;
  }
  if(!q){ render(ALL.filter(t => !league || t.league === league)); return; }
  try { const hits = await api(`/api/search?q=${encodeURIComponent(q)}&league=${league}`);
        render(hits, hits.length ? null : `No team matches "${q}"`); }
  catch(e){ status(e.message, true); }
}

function open_(t){
  current = t; $("#big").src = img(t.url); $("#dn").textContent = t.name;
  $("#dm").textContent = (t.league ? label(t.league).toUpperCase() + " · " : "") + t.url;
  $("#ddl").href = `/api/download?url=${encodeURIComponent(t.url)}&name=${encodeURIComponent(t.name)}`;
  $("#dsel").textContent = picked.has(t.url) ? "Remove from ZIP" : "Add to ZIP";
  $("#big").onload = () => $("#dm").textContent += ` · ${$("#big").naturalWidth}×${$("#big").naturalHeight}px`;
  $("#dlg").showModal();
}

$("#dclose").onclick = () => $("#dlg").close();
$("#dlg").onclick = e => { if(e.target === $("#dlg")) $("#dlg").close(); };
$("#dsel").onclick = () => { toggle(current); render(shown, $("#status").textContent); $("#dlg").close(); };
$("#dcopy").onclick = () => { navigator.clipboard?.writeText(current.url); $("#dcopy").textContent = "Copied!"; setTimeout(()=>$("#dcopy").textContent="Copy link",1200); };
document.querySelectorAll(".bgs button").forEach(b => b.onclick = () => {
  const s = $("#stage"), bg = b.dataset.bg; s.classList.toggle("check", bg==="check"); s.style.background = bg==="check" ? "" : bg; });
$("#go").onclick = run;
$("#q").addEventListener("keydown", e => { if(e.key === "Enter"){ clearTimeout(timer); run(); } });
$("#q").addEventListener("input", () => { clearTimeout(timer); if(!/:\/\//.test($("#q").value)) timer = setTimeout(run, 220); });
$("#lucky").onclick = () => { const pool = ALL.filter(t => !league || t.league === league); if(pool.length) open_(pool[Math.floor(Math.random()*pool.length)]); };
$("#selAll").onclick = () => { shown.forEach(t => picked.set(t.url, t)); tray(); render(shown, $("#status").textContent); };
$("#clear").onclick = () => { picked.clear(); tray(); render(shown, $("#status").textContent); };
$("#zip").onclick = async () => {
  const b = $("#zip"); b.disabled = true; b.textContent = "Zipping...";
  try { const r = await fetch("/api/zip", {method:"POST", body: JSON.stringify([...picked.values()])});
        if(!r.ok) throw new Error((await r.json()).error);
        const a = document.createElement("a"); a.href = URL.createObjectURL(await r.blob()); a.download = "Team_Logos.zip"; a.click(); }
  catch(e){ status("ZIP failed: " + e.message, true); }
  b.disabled = false; b.textContent = "⬇ Download ZIP";
};

api("/api/teams").then(d => { ALL = d.teams; LEAGUES = d.leagues; league = "acc"; chips(); run(); })
  .catch(e => status("Could not load teams: " + e.message + ". You can still paste links.", true));
</script>
</body></html>
"""


def main():
    p = argparse.ArgumentParser(description="Browser UI for downloading team logos.")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--no-browser", action="store_true")
    a = p.parse_args()
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    url = f"http://127.0.0.1:{a.port}"
    print(f"Logo Locker running at {url}  (Ctrl+C to stop)")
    threading.Thread(target=teams, daemon=True).start()  # warm the index
    if not a.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
