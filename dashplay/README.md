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
| `car/PlayerScreen.kt` | The page itself, with buttons for Library, Address, Back and Home. Pan mode scrolls the page. |
| `car/LibraryScreen.kt`, `car/AddressScreen.kt` | Pick a game or bookmark, or type an address on the car's keyboard. |
| `MainActivity.kt` | The phone becomes a remote: send a URL, type into a text box on the car screen, or hand it to a passenger as a **gamepad**. |
| `assets/` | The home page and three games built for car input: **Bubble Pop**, **Turn Snake** and **Memory Lane**. |

### Making a game that works on the car

The car only reports single taps (no dragging), and the phone gamepad sends
ordinary keyboard events. So a Dashplay game should:

- respond to `pointerdown` taps anywhere, with big targets;
- respond to `ArrowUp/Down/Left/Right`, `" "` (A) and `Enter` (B) on `keydown`;
- optionally listen for `window.addEventListener('dashplay', e => e.detail)`, which
  receives `{type: "scroll", dx, dy}` and `{type: "zoom", factor}` from the car.

Drop the page into `app/src/main/assets/games/` and add it to `Games.kt`.

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
  some cars don't, and then the lock never engages. Play only while parked.
- Sound from pages should come out of the car speakers the same way phone media does.
