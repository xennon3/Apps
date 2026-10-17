# Logo Grabber

## The app with a UI: Logo Locker

```bash
python logo_app.py          # opens http://127.0.0.1:8765 in your browser
```

- Type a team name ("clemson", "the u", typos are fine) and results update as you type
- Click a conference or league chip to browse every logo in it
- Paste any link: a direct `.png`, or a web page to choose logos from
- Click a logo to preview it on a checkerboard, white, black or turf background, then **Download PNG**
- Tick several logos and press **Download ZIP**. You get one ZIP with a `sources.txt` inside.
- 🎲 picks a random team

It runs only on your computer (nothing is published). Press Ctrl+C in the terminal to stop it.

## Command line

Download a team logo as a PNG, either by **team name** or by **link**.
Needs only Python 3.9+, with nothing to install. If Pillow is installed, it also reports each image's size and whether it's transparent.

```bash
python logo_grabber.py clemson                      # by name (nicknames work: pitt, unc, vt, "the u")
python logo_grabber.py "wake forest" smu "nc state"  # several at once
python logo_grabber.py https://loodibee.com/wp-content/uploads/Duke_Blue_Devils_logo.png
python logo_grabber.py https://loodibee.com/ncaa/acc/        # a page: pick a logo from a menu
python logo_grabber.py -a https://loodibee.com/ncaa/acc/     # a page: save every logo on it
python logo_grabber.py                                       # interactive: keep typing names/links
```

| Option | What it does |
|---|---|
| `-o DIR` | Output folder (default `./logos`) |
| `-l LEAGUE` | Search only one conference or league, e.g. `-l acc`, `-l nfl` |
| `-y` | Take the best match instead of asking when a name is ambiguous ("miami") |
| `--list [LEAGUE]` | Show every team it knows (about 400 across NCAA, NFL, NBA, MLB, NHL, MLS, WNBA and CFL) |
| `--refresh` | Rebuild the team index (normally cached for 7 days in `~/.cache/logo_grabber`) |

Name lookups use logos from loodibee.com. Small typos ("clemsun") still match. A link must be either a direct `.png` or a web page with PNGs on it. Every saved file is checked to be a real PNG.
