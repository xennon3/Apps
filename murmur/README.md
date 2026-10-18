# Murmur

A texting app for Android that replaces your phone's default SMS app. It does what Messages does, plus a few things that save you from yourself.

| Conversation | Heat meter | First run |
|---|---|---|
| ![](app/src/test/snapshots/images/app.murmur.ui_ScreenshotTest_conversation.png) | ![](app/src/test/snapshots/images/app.murmur.ui_ScreenshotTest_heated.png) | ![](app/src/test/snapshots/images/app.murmur.ui_ScreenshotTest_onboarding.png) |

## What's different

- **Undo send.** A sent text waits 5 seconds (or 3, or 10, or none) with a countdown ring before it really leaves. Tap **Undo** and it's back in the box.
- **Send later.** Hold the send button: in an hour, this evening, tomorrow morning, or any date and time. Scheduled texts sit in the conversation with an outline until they go. They are stored on disk and armed with an alarm, so they still go out if Murmur is closed or the phone restarts.
- **Sleep on it.** Between midnight and 5am, if what you wrote reads heated, Murmur offers to send it at 8am instead. You can still edit or cancel it before then.
- **Heat meter.** The send button shifts from violet to ember to red as a draft gets angrier (shouting, `??!!`, "you always…", a one-word "k."). It is a simple word-and-punctuation check that runs on the phone.
- **Codes, copied.** Login and verification codes get a **Copy 482913** button in the notification and on the message, and their own "Codes" filter.
- **Remind me to reply.** From a notification ("Remind me in 1h") or a conversation's menu: in 1 or 3 hours, this evening, or tomorrow morning.
- **Pictures and group chats (MMS).** Tap the picture button to send a photo. It's turned upright and shrunk to fit your carrier's size limit. Picture and group messages download automatically, and tapping a photo opens it full screen with pinch to zoom. If a download fails (MMS needs mobile data, even on Wi-Fi), the notification has a **Try again** button.
- **Group chats** go to everyone as one conversation, so replies reach the whole group. Settings can switch this to separate texts.
- **Dual SIM.** A chip above the message box picks the SIM for each conversation. Until you choose, Murmur answers on the SIM the other person last wrote to. Each message shows which SIM it used.
- **Filters:** All, Unread, People (in your contacts), Unknown (not in your contacts), Codes.
- **Pins, drafts, search.** Pin conversations to the top, keep a draft per conversation, and search names and the text of every message.
- The basics: reply from the notification, mark as read, delivery reports, a counter that shows when a text will split into several, contact photos, "respond via message" when you decline a call, and dark and light themes.

Everything stays on the phone. Murmur has no internet permission, so it cannot upload anything.

## Not done yet

- **RCS** (Google's chat features: typing dots, read receipts, high-res media over data). Google doesn't let other apps use it.
- **Video, audio and contact cards.** Murmur sends photos only. Received ones that aren't photos still save to the phone, but Murmur shows only their text.
- **Your own number in group chats.** Murmur hides you from a group's member list using the number Android reports for your SIM. Some carriers don't report it, and then you may appear as a member of your own groups.

## Install on your phone

1. On your phone, open this repo's **Releases** page and pick **Murmur for Android (latest)**. That's `github.com/<owner>/<repo>/releases/tag/murmur-latest`.
2. Tap **Murmur.apk**, then open the download. Android asks you to allow installs from your browser once.
3. Open Murmur and tap **Make Murmur my messaging app**.
4. **Android 13 and newer** blocks texting apps that didn't come from the Play Store. If step 3 is refused, open Settings → Apps → Murmur, tap **⋮** (top right) → **Allow restricted settings**, then tap the button in Murmur again. The ⋮ option only appears after Android has blocked something once.

To go back to your old app, open Murmur → Settings → Default messaging app → Change. Your messages stay where they are, because every SMS app reads the same shared store on the phone.

## Build

You need JDK 17+ and the Android SDK (platform 35).

```sh
cd murmur
./gradlew assembleRelease          # → app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # MMS codec, codes, heat and segment tests
./gradlew recordPaparazziDebug     # re-render the screenshots above, no phone needed
```

Every push that touches `murmur/` builds the APK on GitHub Actions and updates the `murmur-latest` release.

APKs are signed with `app/sideload.keystore` (password `murmur`), a throwaway key committed to the repo so that each new build installs over the last one. It is not a secret. If you ever publish to the Play Store, make a real key and keep it out of git.

## Layout

- `data/`
  - `SmsRepository`: reads and writes the phone's shared SMS/MMS store and contacts.
  - `PendingStore`: texts waiting to go out, in the undo window or scheduled.
  - `Vibes`: the heat meter, code detection, segment counting and avatar colours.
  - `Sims`: dual-SIM list and your own numbers.
  - `Prefs`: settings.
- `mms/`
  - `Pdu`: reads and writes the MMS wire format.
  - `Mms`: shrinks pictures, and sends, downloads and stores MMS. Android's own MMS service handles the carrier connection.
- `sms/`
  - `Outbox`: every message leaves through here. It waits, then fires on an alarm or a timer, whichever comes first. Pictures and group chats go as MMS, everything else as SMS.
  - `SmsSender`: hands a text to the radio.
  - `Receivers`: incoming SMS, MMS notices, sent and delivered reports, scheduled sends, boot, and respond-via-message.
- `notify/Notifier`: notification channels, inline reply, the Copy-code button, and reply reminders.
- `ui/`: Jetpack Compose screens.
