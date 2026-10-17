#!/usr/bin/env python3
"""logo_grabber - download a team logo PNG from a link or by team name.

Examples:
    python logo_grabber.py clemson
    python logo_grabber.py "wake forest" "nc state" pitt
    python logo_grabber.py https://loodibee.com/wp-content/uploads/Duke_Blue_Devils_logo.png
    python logo_grabber.py https://loodibee.com/ncaa/acc/        # pick from a page
    python logo_grabber.py --list acc
    python logo_grabber.py                                        # interactive mode

Team names are looked up on loodibee.com (NCAA conferences plus NFL, NBA,
MLB, NHL, MLS, WNBA, CFL). The index is cached for a week; use --refresh
to rebuild it. Standard library only; Pillow is used if installed to report
whether the image has transparency.
"""

import argparse
import difflib
import html
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "Mozilla/5.0 (logo_grabber)"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
MAX_BYTES = 25 * 1024 * 1024
CACHE_TTL = 7 * 24 * 3600
CACHE_FILE = os.path.join(
    os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")),
    "logo_grabber", "index.json")

BASE = "https://loodibee.com"
NCAA = ["acc", "big-ten", "big-12", "sec", "pac-12", "american", "mountain-west",
        "sun-belt", "mac", "conference-usa", "fbs-independents", "big-east",
        "atlantic-10", "ivy-league", "big-sky", "southland", "swac", "the-summit", "wac"]
PRO = ["nfl", "nba", "mlb", "nhl", "mls", "wnba", "cfl"]
LEAGUES = {s: f"{BASE}/ncaa/{s}/" for s in NCAA} | {s: f"{BASE}/{s}/" for s in PRO}

# Nicknames people actually type -> words that appear in the full team name.
ALIASES = {
    "bc": "boston college", "cal": "california golden bears", "fsu": "florida state",
    "gt": "georgia tech", "georgia tech": "georgia tech yellow jackets",
    "the u": "miami hurricanes", "ncsu": "north carolina state wolfpack", "nc state": "north carolina state wolfpack",
    "unc": "north carolina tar heels", "tar heels": "north carolina tar heels",
    "pitt": "pitt panthers", "pittsburgh": "pitt panthers", "smu": "smu mustang",
    "nc state wolfpack": "north carolina state wolfpack",
    "cuse": "syracuse", "uva": "virginia cavaliers", "virginia": "virginia cavaliers",
    "vt": "virginia tech", "hokies": "virginia tech", "wake": "wake forest",
    "nd": "notre dame", "osu": "ohio state", "lsu": "lsu tigers", "usc": "usc trojans",
    "ucla": "ucla bruins", "tcu": "tcu horned frogs", "byu": "byu cougars",
    "ole miss": "ole miss rebels", "bama": "alabama crimson tide", "uga": "georgia bulldogs",
}


def fetch(url, limit=MAX_BYTES):
    if urllib.parse.urlparse(url).scheme not in ("http", "https"):
        raise ValueError(f"only http(s) links are supported: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": BASE + "/"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read(limit + 1)
        if len(data) > limit:
            raise ValueError("file is larger than 25 MB, refusing")
        return data, r.headers.get("Content-Type", ""), r.geturl()


def norm(s):
    return " ".join(re.sub(r"[^a-z0-9 ]+", " ", s.lower().replace("&", " and ")).split())


# ---------------------------------------------------------------- page parsing

def clean_name(s):
    """'Miami_Dolphins_logo-2018' / 'Miami Heat Transparent' -> tidy team name."""
    s = re.sub(r"[_-]+", " ", s)
    s = re.sub(r"^(nfl|nba|mlb|nhl|mls|wnba|cfl|ncaa)\s+", "", s, flags=re.I)
    s = re.sub(r"\s+team\b", " logo", s, flags=re.I)
    s = re.sub(r"\b(logo|logos|png|transparent|free|vector|svg|\d{4}|\d+x\d+)\b.*$", "", s, flags=re.I)
    s = " ".join(s.split())
    return s.title() if s.islower() else s


IMG_RE = re.compile(r"<img\b[^>]*>", re.I)
ATTR_RE = re.compile(r'([a-zA-Z-]+)\s*=\s*"([^"]*)"')


def page_images(page_html, page_url):
    """Return [(name, best_png_url, alt_text)] for every distinct PNG on a page."""
    found = {}
    for tag in IMG_RE.findall(page_html):
        a = {k.lower(): html.unescape(v) for k, v in ATTR_RE.findall(tag)}
        cands = []
        for part in (a.get("srcset") or a.get("data-srcset") or "").split(","):
            bits = part.split()
            if bits:
                w = int(bits[1][:-1]) if len(bits) > 1 and bits[1][:-1].isdigit() else 0
                cands.append((w, bits[0]))
        for key in ("data-src", "src"):
            if a.get(key) and not a[key].startswith("data:"):
                cands.append((int(a.get("width", "0") or 0) if a.get("width", "").isdigit() else 0, a[key]))
        cands = [(w, urllib.parse.urljoin(page_url, u)) for w, u in cands
                 if urllib.parse.urlparse(u).path.lower().endswith(".png")]
        if not cands:
            continue
        # The WordPress original has no "-123x123" size suffix; prefer it.
        best = max(cands, key=lambda c: (not re.search(r"-\d+x\d+\.png$", c[1]), c[0]))[1]
        key = re.sub(r"-\d+x\d+(?=\.png$)", "", best)
        name = clean_name(os.path.basename(key)[:-4])
        alt = clean_name(a.get("alt", ""))
        if not name or "loodibee" in os.path.basename(key).lower():
            continue
        found.setdefault(key, (name, best, alt))
    return list(found.values())


# ---------------------------------------------------------------- team index

def build_index():
    def one(item):
        league, url = item
        try:
            data, _, final = fetch(url)
            return [{"name": n, "alt": alt, "url": u, "league": league}
                    for n, u, alt in page_images(data.decode("utf-8", "replace"), final)]
        except Exception as e:  # one bad page shouldn't sink the index
            print(f"  (skipped {league}: {e})", file=sys.stderr)
            return []

    print("Building team index from loodibee.com (one-time, cached for a week)...", file=sys.stderr)
    with ThreadPoolExecutor(8) as pool:
        teams = [t for chunk in pool.map(one, LEAGUES.items()) for t in chunk]
    # Drop conference/league badges so "big ten" doesn't shadow real teams.
    teams = [t for t in teams if not re.search(r"conference|league logo|^ncaa", t["name"], re.I)]
    seen = set()  # a team can appear on two pages (e.g. Notre Dame); keep one
    teams = [t for t in teams if not (t["url"] in seen or seen.add(t["url"]))]
    os.makedirs(os.path.dirname(CACHE_FILE), exist_ok=True)
    with open(CACHE_FILE, "w") as f:
        json.dump({"built": time.time(), "teams": teams}, f)
    return teams


def load_index(refresh=False):
    if not refresh:
        try:
            with open(CACHE_FILE) as f:
                c = json.load(f)
            if time.time() - c["built"] < CACHE_TTL and c["teams"]:
                return c["teams"]
        except (OSError, ValueError, KeyError):
            pass
    return build_index()


def search(query, teams, league=None):
    """Return matching teams, best first. Several results means it's ambiguous."""
    pool = [t for t in teams if not league or t["league"] == league]
    q = norm(query)
    q = norm(ALIASES.get(q, q))
    keys = {id(t): {norm(t["name"]), norm(t.get("alt", ""))} - {""} for t in pool}

    exact = [t for t in pool if q in keys[id(t)]]
    if exact:
        return exact
    words = q.split()
    hits = [t for t in pool if any(all(w in k.split() for w in words) for k in keys[id(t)])]
    if not hits:
        hits = [t for t in pool if any(q in k for k in keys[id(t)])]
    if hits:
        return sorted(hits, key=lambda t: (t["league"] not in NCAA, len(t["name"])))

    # Typos: compare the query with the same number of leading words of each name.
    def score(t):
        return max(difflib.SequenceMatcher(None, q, " ".join(k.split()[:len(words)])).ratio()
                   for k in keys[id(t)])
    scored = sorted(((score(t), t) for t in pool), key=lambda st: -st[0])
    return [t for sc, t in scored[:5] if sc >= 0.75]


# ---------------------------------------------------------------- saving

def safe_name(s):
    return re.sub(r"_+", "_", re.sub(r"[^A-Za-z0-9]+", "_", s)).strip("_") or "logo"


def describe_png(path):
    try:
        from PIL import Image
    except ImportError:
        return ""
    with Image.open(path) as im:
        alpha = im.convert("RGBA").getchannel("A").getextrema()[0] < 255
        return f"{im.size[0]}x{im.size[1]}, {'transparent' if alpha else 'NO transparency'}"


def save_png(url, name, outdir):
    data, ctype, _ = fetch(url)
    if not data.startswith(PNG_MAGIC):
        raise ValueError(f"not a PNG (got {ctype or 'unknown type'})")
    os.makedirs(outdir, exist_ok=True)
    path = os.path.join(outdir, safe_name(name) + ".png")
    with open(path, "wb") as f:
        f.write(data)
    info = describe_png(path)
    print(f"Saved {path}  ({len(data) // 1024} KB{', ' + info if info else ''})")
    print(f"  source: {url}")
    return path


def choose(options, label, auto):
    if len(options) == 1 or auto:
        return options[0]
    if not sys.stdin.isatty():
        print(f"'{label}' is ambiguous; use -y to take the first match or be more specific:")
        for o in options[:10]:
            print(f"  - {o['name']} [{o.get('league', '')}]")
        return None
    print(f"Several matches for '{label}':")
    for i, o in enumerate(options[:15], 1):
        print(f"  {i:2}. {o['name']} [{o.get('league', 'page')}]")
    pick = input("Pick a number (Enter to skip): ").strip()
    return options[int(pick) - 1] if pick.isdigit() and 0 < int(pick) <= min(15, len(options)) else None


def grab(target, args, teams_cache):
    if re.match(r"[a-z][a-z0-9+.-]*://", target, re.I):
        data, ctype, final = fetch(target)
        if data.startswith(PNG_MAGIC):
            base = urllib.parse.unquote(os.path.basename(urllib.parse.urlparse(final).path))
            name = re.sub(r"_logo$", "", base.rsplit(".", 1)[0], flags=re.I)
            return save_png(final, name, args.out)
        if "html" not in ctype.lower():
            raise ValueError(f"link is neither a PNG nor a web page ({ctype})")
        imgs = [{"name": n, "url": u} for n, u, _ in page_images(data.decode("utf-8", "replace"), final)]
        if not imgs:
            raise ValueError("no PNG images found on that page")
        if args.all:
            return [save_png(i["url"], i["name"], args.out) for i in imgs]
        pick = choose(imgs, target, args.yes)
        return pick and save_png(pick["url"], pick["name"], args.out)

    if teams_cache[0] is None:
        teams_cache[0] = load_index(args.refresh)
    hits = search(target, teams_cache[0], args.league)
    if not hits:
        print(f"No team found for '{target}'. Try a fuller name, or paste a link.")
        return None
    pick = choose(hits, target, args.yes)
    return pick and save_png(pick["url"], pick["name"], args.out)


def main():
    p = argparse.ArgumentParser(description="Download a team logo PNG by link or team name.")
    p.add_argument("targets", nargs="*", help="team names and/or links (none = interactive)")
    p.add_argument("-o", "--out", default="logos", help="output folder (default: ./logos)")
    p.add_argument("-l", "--league", choices=sorted(LEAGUES), help="only search this league/conference")
    p.add_argument("-y", "--yes", action="store_true", help="take the best match without asking")
    p.add_argument("-a", "--all", action="store_true", help="for a page link, save every PNG on it")
    p.add_argument("--list", nargs="?", const="", metavar="LEAGUE", help="list known teams and exit")
    p.add_argument("--refresh", action="store_true", help="rebuild the cached team index")
    args = p.parse_args()

    teams_cache = [None]
    if args.list is not None:
        teams = load_index(args.refresh)
        for t in sorted(teams, key=lambda t: (t["league"], t["name"])):
            if not args.list or t["league"] == args.list:
                print(f"{t['league']:<18} {t['name']}")
        return 0

    targets = args.targets
    interactive = not targets
    if interactive:
        print("Logo grabber: type a team name or paste a link. Blank line quits.")
    failures = 0
    while True:
        if interactive:
            try:
                t = input("\nteam or link> ").strip()
            except EOFError:
                break
            if not t:
                break
            batch = [t]
        else:
            if not targets:
                break
            batch, targets = [targets[0]], targets[1:]
        for t in batch:
            try:
                if not grab(t, args, teams_cache):
                    failures += 1
            except Exception as e:
                failures += 1
                print(f"Could not get '{t}': {e}")
    return 1 if failures and not interactive else 0


if __name__ == "__main__":
    sys.exit(main())
