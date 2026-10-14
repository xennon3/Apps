# Dashplay Store

Tools and games for [Dashplay](https://github.com/xennon3/Apps/tree/main/dashplay), the
web-and-games app for Android Auto. The Store screen in Dashplay reads `catalog.json` from
this repo and downloads items from `items/<id>/` onto the phone, where they keep working
without a signal.

| Item | What it is |
| --- | --- |
| 🧮 Calculator | Big buttons for the car screen, with a tip & split mode |
| 🧭 Compass | Heading from the car's own compass (falls back to the phone's) |
| 🅿️ Parking timer | Countdown for your meter, with beeps before it runs out |
| ⏱️ Stopwatch & timer | Stopwatch with laps, and a countdown timer with an alarm |

## Adding an item

1. Make a folder `items/<id>/` (lowercase letters, digits and dashes).
2. Add the item's files, with an `index.html` to open first. Pages can use Dashplay's shared
   look and helpers, which every Dashplay install serves locally:
   ```html
   <link rel="stylesheet" href="/assets/kit/arcade.css">
   <script src="/assets/kit/tool.js"></script>   <!-- Tool.header(), Tool.beep(), Tool.store -->
   ```
   Games can use the game shell instead (`/assets/kit/games.js` and `/assets/kit/arcade.js`).
3. Add `items/<id>/item.json`:
   ```json
   { "id": "my-tool", "kind": "tool", "title": "My tool", "emoji": "🔧",
     "color": "#4C8DF0", "version": "1.0.0", "blurb": "One line about it.", "entry": "index.html" }
   ```
   `kind` is `tool` or `game`. Bump `version` when you change an item, and Dashplay offers an update.
4. Run `python3 scripts/build_catalog.py` and commit `catalog.json` with your item.

Each item can be up to 8 MB. Pushed changes reach cars within a few minutes.
