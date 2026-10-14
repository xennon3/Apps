# Dashplay

Web pages and games on your car's Android Auto screen.

Android Auto normally allows only list-style apps. Dashplay registers as a
navigation app, the one kind of app that gets a raw drawing surface, and then
renders a real **WebView** onto that surface. Anything that runs in a browser can
run on the dashboard.

```
 PHONE                                                        CAR SCREEN
 MainActivity ─▶ CarBridge ─▶ DashplaySession ─▶ WebSurface ══ video ══▶ head unit
 (URL, gamepad, typing)               ▲                                    │
                                      └──────── taps, pans, zoom ──────────┘
```

## What's inside

| Piece | What it does |
| --- | --- |
| `car/WebSurface.kt` | Wraps the car's surface in a private `VirtualDisplay`, shows a `Presentation` with a WebView on it, and replays car taps as touch events. |
| `car/DashplaySession.kt` | Wires up the surface callbacks and the **driving lock**: when the car reports a speed over ~7 km/h, the page is covered and its timers pause. |
| `car/PlayerScreen.kt` | The page itself, with buttons for Library, Address and Home. Pan mode scrolls the page. |
| `assets/chrome.html` | The **browser toolbar** above web pages: back, forward, reload/stop, address pill with lock icon, loading bar, text size and start page. It is a second small WebView, driven by `WebSurface`. |
| `assets/browser.html`, `assets/offline.html` | The browser start page (search, quick-access sites, recent pages) and a friendly "can't reach this page" screen. Web pages are shown in dark mode where possible. |
| `car/LibraryScreen.kt`, `car/AddressScreen.kt` | Pick a game or bookmark, or type an address on the car's keyboard. |
| `MainActivity.kt` + `assets/remote.html` | The phone remote: start games on the car, a full-screen **gamepad** for a passenger, control the car's browser (back/forward/reload, quick sites, recent pages, typing), and **Settings**. With no car connected, games open on the phone. |
| `assets/index.html` | The car home screen: clock, "continue" card, and a paged grid of games (no scrolling needed). |
| `assets/kit/` | Shared by every page: `games.js` and `sites.js` (the game and quick-access lists), `arcade.css` (the look), `arcade.js` (start/pause/game-over cards, best scores, input, sound) and `browser.js` (site icons). |

### Driving lock

On by default. A passenger can switch it off in the phone app's **Settings** tab after a
confirmation. Switching it off lasts only until the car disconnects: every new connection
starts with the lock on again, so it is never left off by accident.

### Games

| | Game | Controls |
| --- | --- | --- |
| 🏎️ | **Lane Racer** — dodge traffic, grab fuel cans | tap left/right half · ◀ ▶ |
| 🫧 | **Bubble Pop** — 60-second popping with combos | tap · A |
| 🏗️ | **Stack Tower** — drop floors; three perfects in a row grow the floor back | tap · A |
| 🐦 | **Sky Hop** — flap between highway signs | tap · A |
| 🐍 | **Turn Snake** — steer by tapping either half of the screen | tap · arrows |
| 🛣️ | **Mile 2048** — the sliding number puzzle, with big on-screen arrows | buttons · arrows |
| 🚧 | **Pothole Patrol** — patch potholes before cars hit them, spare the ducks | tap · arrows + A |
| 🚦 | **Signal Says** — repeat the traffic-light sequence | tap · ◀ ▲ ▼ ▶ |
| 🟢 | **Green Light** — reaction time over five lights | tap · A |
| 🛑 | **Memory Lane** — match the road-sign pairs | tap · arrows + A |
| ⭕ | **Noughts & Crosses** — build a win streak against the car | tap · arrows + A |

### Adding a game

The car reports only single taps (no dragging), and the phone gamepad sends ordinary
keyboard events, so games are built around `pointerdown` taps and arrow/Space/Enter keys.
The shell handles the rest:

```html
<link rel="stylesheet" href="../kit/arcade.css">
<script src="../kit/games.js"></script>
<script src="../kit/arcade.js"></script>
<script>
  const game = Arcade.create({
    id: 'my-game',                       // add a matching entry to kit/games.js
    keys: [['Tap', 'jump']],             // shown on the start card
    start() { /* reset */ },
    tap(x, y) { game.add(1); game.sfx('point'); },
    key(k) { /* 'up' 'down' 'left' 'right' 'a' 'b' */ },
    update(dt) { if (lost) game.end('Game over'); },
    draw(g, W, H) { /* canvas drawing */ },
  });
</script>
```

Save it as `assets/games/my-game.html` and add `{ "id": "my-game", ... }` to
`assets/kit/games.js`. It then shows up on the car home screen, the car's Library list and
the phone remote. Pages can also listen for `window.addEventListener('dashplay', e => e.detail)`,
which receives `{type: "scroll", dx, dy}` and `{type: "zoom", factor}` from the car.

## Install

1. Get the APK: download **Dashplay-APK** from the latest run of the
   *Dashplay (Android APK)* workflow on GitHub Actions, or build it yourself with
   `./gradlew assembleDebug`.
2. Install it on your phone (allow installs from your browser or file manager).
3. Unlock Android Auto developer mode: phone **Settings → Apps → Android Auto →
   Additional settings in the app**, scroll to the bottom and tap **Version** ten times.
4. In the ⋮ menu choose **Developer settings** and turn on **Unknown sources**.
5. Connect to the car. Dashplay appears in the Android Auto app launcher.

No car handy? Use Google's **Desktop Head Unit** (Android SDK Manager → SDK Tools →
*Android Auto Desktop Head Unit Emulator*). In Android Auto's ⋮ menu choose
**Start head unit server**, run `adb forward tcp:5277 tcp:5277`, then start `desktop-head-unit`.

## Limits and honest notes

- **Sideload only.** The Play Store won't accept a "navigation" app that isn't one.
- **Taps need car API level 5** (Android Auto from about 2022 onward).
- **Web text boxes have no on-screen keyboard** on the car; use the phone's *Type* box.
- **The driving lock depends on the car reporting its speed.** The desktop head unit and
  some cars don't, and then the lock never engages. The driver should only play or browse while parked.
- **Android Auto hides the car keyboard while driving**, whatever the driving lock says. Use the phone's Web tab instead.
- Sound from pages should come out of the car speakers the same way phone media does.
