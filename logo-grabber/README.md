# Logo Grabber

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
