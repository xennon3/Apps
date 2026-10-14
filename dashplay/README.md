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
| `Store.kt`, `assets/store.html` | The **Store**: browses the catalog at [xennon3/dashplay-store](https://github.com/xennon3/dashplay-store), downloads tools onto the phone so they work offline, and offers updates and removal. |
| `Local.kt` | Serves Dashplay's own pages from one local https address (`https://appassets.androidplatform.net`): bundled pages under `/assets/`, downloaded store items under `/store/`. |
| `store/` | A copy of the store repo's contents (tools, catalog and the catalog builder). |
| `assets/kit/` | Shared by every page: `games.js` and `sites.js` (the game and quick-access lists), `arcade.css` (the look), `arcade.js` (start/pause/game-over cards, best scores, input, sound) and `browser.js` (site icons). |

### Driving lock

On by default. It can be switched off in the phone app's **Settings** tab after a
confirmation. A second switch, **Turn back on every drive** (on by default), makes every new
connection start with the lock on again, so it is never left off by accident. Turn that
switch off to have the lock keep its setting between drives. Both settings are saved.

### Store and tools

The home screen has **Games** and **Tools** tabs. Tools come from the Store (open it from the
Tools tab, the car's Library list, or the phone's Apps tab). The first four:

| | Tool | |
| --- | --- | --- |
| 🧮 | **Calculator** | Big buttons, plus a tip & split mode |
| 🧭 | **Compass** | Heading from the car's own compass, falling back to the phone's sensors |
| 🅿️ | **Parking timer** | Meter countdown that survives leaving the tool, with beeps at 10 and 5 minutes |
| ⏱️ | **Stopwatch & timer** | Stopwatch with laps, countdown timer with an alarm |

To publish a new tool, add it to the store repo; see its README. Cars pick it up within minutes,
with no app update needed.

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

Android Auto only shows apps built with the Android for Cars App Library (Dashplay is one)
when they come from a trusted source such as Google Play. Its **Unknown sources** developer
setting does **not** cover these apps, so a plain sideloaded APK never appears in the car.
The way around it without a public Play listing is Play's **internal app sharing**, which
has no review:

1. Get a [Google Play Console](https://play.google.com/console) developer account
   (one-time US$25 fee) and create an app called Dashplay.
2. In the Play Console open **Internal app sharing**, upload the APK (download
   **Dashplay-APK** from the latest *Dashplay (Android APK)* workflow run, or build it with
   `./gradlew assembleDebug`), and copy the link it gives you. Debug builds are fine there.
3. On the phone, turn on internal app sharing in the Play Store: **Play Store → profile
   picture → Settings → About**, tap **Play Store version** seven times, then
   **Settings → General → Developer options → Internal app sharing**.
4. Uninstall any copy of Dashplay you installed from a file, since Play signs its copy differently.
5. Open the link on the phone and install from the Play Store.
6. Connect to the car. Dashplay appears in the Android Auto launcher. If it doesn't, check
   **Android Auto settings → Customize launcher**.

For each new version, upload the new APK to internal app sharing and install from the new link.

### Release builds (app bundle)

Play Console asks for an Android App Bundle signed with your upload key. Keep the key
(`upload.jks`) and its password somewhere safe and out of git, describe it in a properties file:

```properties
storeFile=/path/to/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

then build with `DASHPLAY_SIGNING=/path/to/that.properties ./gradlew bundleRelease`
(or name the file `keystore.properties` in this folder, which git ignores). The bundle is
`app/build/outputs/bundle/release/app-release.aab`. Raise `versionCode` in
`app/build.gradle.kts` for each upload after the first.

## Limits and honest notes

- **Not for the public Play Store.** Play wouldn't approve a "navigation" app that isn't one, so Dashplay is installed through internal app sharing (see Install).
- **Taps need car API level 5** (Android Auto from about 2022 onward).
- **Typing into web pages uses the car's own keyboard.** Tapping a text box (YouTube search, say) opens Android Auto's keyboard, and what you type goes into the page as you type. Android Auto hides that keyboard while driving; the phone's *Type* box always works.
- **The driving lock depends on the car reporting its speed.** The desktop head unit and
  some cars don't, and then the lock never engages. The driver should only play or browse while parked.
- **Android Auto hides the car keyboard while driving**, whatever the driving lock says. Use the phone's Web tab instead.
- Sound from pages should come out of the car speakers the same way phone media does.
