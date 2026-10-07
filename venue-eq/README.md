# Venue EQ

An Android equalizer and room simulator. Pick a place (Club, Concert Hall, Cathedral,
Next Door, Underwater, and more) and your music sounds like it's playing there.

## Features

- **11 rooms**, each a full sound character: reverb, EQ curve, bass and stereo width.
- **Where you stand**: slide from the front row (direct, bright) to the back wall
  (more echo, duller highs).
- **5-band EQ** that adapts to however many bands your phone's equalizer has.
- **Bass boost** and **3D width** (virtualizer).
- **Works with other apps**: Spotify, YouTube Music, Poweramp, VLC and other players that
  announce their audio session get the effects automatically.
- **Try it in-app**: a built-in synthesized demo beat, or any song file on your phone.

## Install on your phone

1. Download `app-release.apk` (from the Actions tab → *Venue EQ (Android APK)* → artifact,
   or build it yourself below).
2. Open it on the phone and allow installs from that source when asked.
3. If Play Protect warns about an unknown app, choose **Install anyway**.

Every build is signed with the same key (`app/sideload.keystore`), so new versions install
over old ones without uninstalling first.

## Using it

1. Open Venue EQ and turn on **Effects**.
2. Start music in your player. Its name appears under the switch once attached.
3. If your player never shows up, turn on **Global mode** under Advanced. That applies EQ,
   bass and width to all phone audio on devices that still allow it. Room reverb only works
   on apps that attach normally, which is an Android limitation.

## How it works

There are two sound paths:

- **Inside the app** (demo beat, "My song"): Venue's own DSP engine in `RoomProcessor.kt`.
  It covers a 5-band biquad EQ, bass shelf, mid/side width, a Freeverb-style reverb with
  pre-delay and early reflections, loudness matching and a limiter. It works on every
  phone, through the speaker too.
- **Other apps** (Spotify, YouTube Music, ...): Android's built-in effects (`Equalizer`,
  `BassBoost`, `Virtualizer`, `EnvironmentalReverb`), attached to the player's audio
  session. Support varies by phone, so the status line shows each one as ✓ working,
  ⚠ taken over by another audio app (e.g. Dolby), or ✗ not supported.

Rooms are described with I3DL2 reverb parameters in `Rooms.kt`, so it's easy to add your own.
`./gradlew testReleaseUnitTest` renders the demo through every room into
`app/build/room-samples/*.wav` so you can listen without a phone.

## Build

```sh
cd venue-eq
./gradlew assembleRelease   # needs JDK 17+ and the Android SDK (platform 35)
# -> app/build/outputs/apk/release/app-release.apk
```
