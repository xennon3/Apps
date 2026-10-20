# Murmur PC kit

Plug your phone into your computer and let Claude (or you) see what Murmur is really doing:
live logs, why Android stopped it, battery and sensor state, screenshots. Everything here runs on
your own computer and talks to your phone over the USB cable. Nothing is uploaded anywhere unless
you choose to share a file.

## One-time setup (about 10 minutes)

1. **Get Android's USB tools ("platform-tools").** Download from
   https://developer.android.com/tools/releases/platform-tools and unzip it somewhere easy,
   e.g. `C:\platform-tools` (Windows) or `~/platform-tools` (Mac/Linux).
   Put this whole `pc-kit` folder **inside** that platform-tools folder (or add platform-tools to
   your PATH), so the scripts can find `adb`.
2. **Turn on USB debugging on the phone.** Settings → About phone → tap **Build number** 7 times
   (it says "You are now a developer"). Then Settings → System → **Developer options** → turn on
   **USB debugging**.
3. **Plug in the phone** with a data cable. On the phone, tap **Allow** on "Allow USB debugging?"
   (tick "Always allow from this computer").
4. Run **check-phone** (below). It should print your phone's model.

## The scripts

Double-click the `.bat` files on Windows; on Mac/Linux run `./name.sh` in a terminal
(once: `chmod +x *.sh`).

| Script | What it does |
|---|---|
| `check-phone` | Confirms the phone is connected and shows Murmur's version on it. |
| `install-murmur` | Installs the newest Murmur build from GitHub (or an APK you drop in this folder) over your current one. Your messages and settings stay. |
| `live-log` | Shows Murmur's log live as you use the phone, and saves it to `logs/`. Reproduce the problem, then press Ctrl+C. |
| `snapshot` | Saves one file in `logs/` with everything useful at this moment: Murmur's recent log, why Android last stopped it, whether Ambient mode's service is running, battery and battery-optimisation state, permissions, memory, location and sensor status. |
| `screenshot` | Saves a screenshot of the phone into `logs/`. |

Send the file from `logs/` to Claude when something misbehaves.

## Let Claude drive (the best option)

Install Claude Code on this computer (https://claude.ai/download for the desktop app, or the
`claude` command-line tool), then open the Murmur project folder:

```
git clone https://github.com/xennon3/Apps
cd Apps
git checkout ccr-29cac12d-qr0wyi
claude
```

That session runs on your computer, so it can use `adb` itself: read live logs, pull diagnostics,
take screenshots, install fixed builds and check them, all with the phone on the cable. Tell it:
"Read murmur/pc-kit/FOR-CLAUDE.md, then help me debug Murmur on my plugged-in phone."
