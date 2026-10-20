# For Claude running on the user's computer

The user's phone (Moto G Stylus 5G 2024, Android, package `app.murmur`) is on USB with adb.
Read `murmur/CLAUDE.md` first: it is the full project handoff (build commands, code map, the
hard-won rules, and what's untested on the phone).

Useful commands (adb is in the platform-tools folder or on PATH):

- Devices: `adb devices -l`
- Murmur's live log: `adb logcat -v time --pid=$(adb shell pidof app.murmur)` (re-run after the app restarts)
  Tags worth grepping: `Murmur`, `MurmurBrain`, `MurmurMeaning`, `litert`, `AndroidRuntime`.
- Why Android stopped the app: `adb shell dumpsys activity exit-info app.murmur`
- Is Ambient mode's service alive: `adb shell dumpsys activity services app.murmur`
- Battery optimisation: `adb shell dumpsys deviceidle whitelist | grep murmur` (absent = optimised)
- Permissions and app ops: `adb shell dumpsys package app.murmur | grep -A60 "runtime permissions"`, `adb shell appops get app.murmur`
- Memory while the Brain thinks: `adb shell dumpsys meminfo app.murmur`
- Screenshot: `adb exec-out screencap -p > shot.png`
- Install a build: `adb install -r murmur/app/build/outputs/apk/release/app-release.apk`
  (build with `cd murmur && ./gradlew assembleRelease`; the sideload key in `app/sideload.keystore`
  matches the user's installed copy, so `-r` keeps their data)

The release build is not debuggable, so `adb shell run-as app.murmur` won't work. For the app's own
files (Ambient timeline, 6-hour sensor diary, battery log), ask the user to tap **Send diagnostics**
in Murmur's Settings or the Mind window's bug icon (version 1.12.1+), which writes them to one file.

Fix things the way the project does: plain code decides, the model words it; check prompt changes
in `murmur/brainlab` against the real model; keep `CLAUDE.md` updated; commit and push to the
session branch `ccr-29cac12d-qr0wyi`.
