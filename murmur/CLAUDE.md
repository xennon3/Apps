# Murmur: notes for the next session

Murmur is a native Android SMS/MMS app (Kotlin, Jetpack Compose, minSdk 26, targetSdk 35) that replaces the default messaging app. It includes **Murmur Brain**, an optional on-device AI (Gemma 4 E2B through LiteRT-LM). `README.md` is the feature list. This file is how to work on it.

## The user
- They test on a **Moto G Stylus 5G (2024)** (Snapdragon 6 Gen 1: 4x2.2 GHz + 4x1.8 GHz cores, Adreno 710, 8 GB RAM). The Brain's speed test there showed about **6.6 tokens/s writing**.
- They install APKs by sideloading. Android 13+ needs "Allow restricted settings" before the SMS role can be granted, and that's already done on their phone.
- Their preferences: creative ideas, direct answers, and quick research before deciding.
- **Standing direction for the Brain (from the user):** keep widening the subjects it answers and use everything the model can do. Murmur adds no topic limits of its own; prompts should make it a frank, capable helper on any subject, and each update should find and remove remaining hedges or refusals (check them in `brainlab`'s `OpinionLabKt`). Gemma's own built-in limits on truly dangerous requests stay; don't try to break them.
- Each release: bump `versionCode`/`versionName` in `app/build.gradle.kts` and the "Murmur x.y" line in `ui/OtherScreens.kt`. Build the release APK and send it to them. Commit and push to the session branch.
- Current version: **1.11.1** (versionCode 18). Ambient pace: Lively 5 min, Balanced 10 (default), Relaxed 30; the user found 30 too slow.

## Build and check (all must pass before shipping)
```sh
export ANDROID_HOME=/opt/android-sdk   # install cmdline-tools + platforms;android-35 + build-tools;35.0.0 if missing
./gradlew assembleRelease lintDebug testDebugUnitTest recordPaparazziDebug
```
- Unit tests cover the MMS codec, scam shield, repeats, calculator, date phrases, Brain prompt parsers, tools and MCP parsing.
- Paparazzi renders screens to `app/src/test/snapshots/images/`. Look at them; they caught real colour bugs.
- No emulator works here (no KVM). The phone is the only real device test.
- `brainlab/` runs the real prompts against the real model on the desktop (see its README). **Any prompt change gets checked there first.**
- CI: `.github/workflows/murmur-android.yml` builds the APK and updates the `murmur-latest` GitHub release.
- Signing: `app/sideload.keystore` (password `murmur`) is a throwaway key so updates install over each other.

## Map
- `data/`: `SmsRepository` (Telephony provider, contacts, search), `PendingStore` (undo/scheduled/repeating sends, SQLite v3), `Prefs`, `Vibes` (codes, segments, the late-night anger check for "sleep on it"), `ScamShield`, `Repeat`, `Sims`.
- `sms/`: `Outbox` is the only way a message leaves. It waits for the undo window or schedule, then fires via alarm or coroutine; SMS or MMS. Plus `SmsSender` and the receivers.
- `mms/`: `Pdu` is the hand-written OMA MMS codec. `Mms` does sending, downloading and storing through Android's MMS service, with a FileProvider.
- `voice/`: AMR voice notes: recording, playback with raise-to-ear, and transcription via SpeechRecognizer.
- `brain/`:
  - `Brain` (engine, download, `ask()`)
  - `BrainChat` (the Brain tab pipeline)
  - `BrainPrompts` (every prompt and parser; plain Kotlin)
  - `PlanTime`, `Calc`, `Skills`, `Extensions` (web/skills/MCP settings and the tool list)
  - `PhoneData` (calendar/calls/contacts/location, `BrainAccess`, `BrainMemory`)
  - `tools/` (tools, MCP client and LiteRT bridge; plain Kotlin). `tools/Phone.kt` holds the phone tools' definitions and `PhoneMath` (battery, speed, timers); tested in the lab with fake readings.
  - Ambient mode: `AmbientMind` (prompt, situation, parser, code veto; plain Kotlin, lab: `AmbientLabKt`), `Ambient` (state, timeline, goals, plans, acting), `AmbientService` (foreground service reflexes), `Places` (stay detection, home/work), `ui/MindScreen`. Phase 2 (hearing: loudness trigger + audio clips) and Phase 3 (sight: camera when still and face-up) are next.
  - `PhoneSensors` (Android readings + `PhoneControls`), `BatteryLog` (half-hourly readings, steps baseline, battery coach), `Meaning` (EmbeddingGemma index in `meaning.db`), `Lessons` + `ReplyStyle` (what the user taught it), `Eyes` (shrinks pictures to 768 px JPEG)
- `CrashReport`: records Kotlin crashes, plus `ApplicationExitInfo` for native crashes and memory kills. Shown with **Copy details**; ask the user for these.

## Hard-won rules (each one came from a real bug)
- **Never send characters outside Unicode's basic plane (emoji) to LiteRT-LM.** It aborts the whole process. Everything goes through `BrainPrompts.engineSafe`.
- **Brain requests must not be cancellable mid-flight.** `ask()` runs under `NonCancellable`. A cancelled load leaked a 2.6 GB engine and the app was killed for memory.
- **Plain code decides, the model words it.** Send requests, remember/forget, which phone data to read, date maths (`PlanTime`) and arithmetic (`Calc`) are code. The model extracts, words and chooses tools.
- **Tools return finished sentences, never raw JSON.** With raw JSON the model applied an exchange rate twice.
- **Skills are worded as guidance, not by name.** A named skill was taken for a tool.
- **Small models follow examples, not rules.** Use example chat turns (`initialMessages`); one-shot instructions failed.
- **Don't store a model "no" when the model simply failed** (`Brain.planCheck` returns null to mean "retry later").
- **Kotlin 2.3.21 is required** because the LiteRT-LM 0.18 jar has Kotlin 2.4 metadata. Native libraries are arm64 only.
- **Murmur has the INTERNET permission** only because DownloadManager requires it for the model. Messages never go online. Web lookups (off by default) send only the model's search words.
- **Brave Search dropped its free tier** (2026). Default search is FREE (DuckDuckGo's HTML page, parsed in `WebSearchTool.duckResults`); Tavily is the steady free-key option.
- **LiteRT-LM 0.18 already has** speculative decoding (`ExperimentalFlags.enableSpeculativeDecoding`, 1.74x in the lab), image/audio input (`visionBackend`/`audioBackend` + `Content.ImageBytes`/`AudioBytes`), and `EmbeddingEngine`. Audio is fed as 16 kHz mono WAV (`Voice.wav16k`).
- **Phone tools: one tool per kind, the model picks a topic.** Two tools (`phone_status`, `phone_action`) scored 17/17; many small tools would crowd the model.
- **Thinking mode** (`ConversationConfig.thinkingConfig`): thoughts arrive in `Message.channels["thought"]`, not in the text. It fixed 3 of 3 reasoning mistakes in the lab but makes easy questions ~40x slower, so `BrainPrompts.isHard` gates it. Memory boost (storage as RAM) doesn't help the model; E4B on 8 GB is slow partly from paging.
- **Gemma hedges unless told not to.** The user wants a frank helper on every subject, no topic limits from Murmur. `BrainPrompts.OPINIONS` + `OPINION_EXAMPLES` + the `isRefusal` retry took dodges from 7/7 ("it depends") to 0/12. Don't reintroduce "keep it short"-style limits in chat prompts.
- **Workspace** (`Brain.workspace()`): 8,192 tokens on the Standard model with 8 GB+ RAM (+530 MB vs 4,096; 16,384 is +1.6 GB, too much). Big Brain stays 4,096 under 11 GB.
- **Don't wait on a process with `pgrep -f <name>` inside the same shell command:** it matches its own command line and waits forever.
- **Model bake-off (Oct 2026, `BakeOffKt`):** Gemma 4 E2B 6/6 reasoning, 8/8 scams, 8/8 tools, sees+hears; Qwen3.5 4B/2B 2-3/6 reasoning and 1/8 tools; LFM2.5 2.6B blank answers; Ministral 3 3B crashed. Keep Gemma.
- **Qwen models were rejected.** Qwen3 0.6B and Qwen2.5 1.5B were too weak, and Qwen's bundled chat template breaks in LiteRT-LM. Gemma 4 E2B scored 8/8 on scam checks and 13/13 on tool choice.

## Untested on a real phone (ask the user)
- **1.11 features:** Ambient mode on a real phone (service survival, battery cost, place learning, thought quality, notification noise).
- **1.10 features:** pictures and spoken questions in the Brain tab, the 8K workspace (memory on the real phone).
- **1.8 features:** everything (fast writing on the phone, Big Brain download/switch, pictures, Brain voice transcription, phone tools on a real phone, battery coach over days, meaning-search indexing time, reply learning, fact chips).
- **1.7 features:** reply chips (speed and quality), free DuckDuckGo search (can't be tested from the cloud: DuckDuckGo blocks data-centre IPs), Tavily.
- **1.6 features:** web lookups, skills, MCP via DeepWiki, the status line, speed of tool answers.
- **Earlier features:** picture/group MMS with their carrier, voice-note transcription, graphics-chip (GPU) Brain backend.

## Ideas not built yet
- A "Needs reply" home-screen widget.
- Polls in group chats.
- "One year ago today".
- Arrival texts (geofence).
- The graphics-chip-specific Gemma file (`gemma-4-E2B-it-gpu.litertlm`, 2.0 GB) if the CPU feels slow.
