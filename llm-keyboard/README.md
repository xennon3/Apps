# LLM Keyboard

An Android keyboard (IME) that predicts words and whole sentences, and can translate, simplify
or fix your text, using an open-weights LLM that runs **on the phone**. The app has no INTERNET
permission, so nothing you type can leave the device.

## How it predicts
| Layer | Latency | Does |
|---|---|---|
| `NgramModel` | instant | word completion + next word, learns your vocabulary locally |
| `LlmEngine` | ~0.5-3 s, debounced 450 ms after you pause | ✨ full-sentence continuation chip |
| Action row | on tap | Translate (EN/ES/FR/DE/PT/IT/JA cycle chip), Simplify, Fix. Works on the selection, else the text before the cursor |

Password and no-suggestion fields disable learning and the LLM.

## Setup
1. Open in Android Studio (or `gradle :app:assembleDebug`), install on a phone (8 GB RAM is comfortable for 1B models).
2. Download a MediaPipe-format open model, e.g. **Gemma 3 1B IT** (`.task`, int4) from the LiteRT community on Hugging Face (accept the Gemma license), or a converted Qwen2.5 0.5B/1.5B.
3. Launch the app: Enable keyboard, Load model file, Switch keyboard.

Prompts in `Prompts.kt` use the Gemma chat template; change them if you use another model family.
To use llama.cpp/GGUF instead, implement `LlmEngine` with a JNI wrapper and swap it in `LlmKeyboardService.loadEngine()`.

## Status
Scaffold written without an Android SDK in this environment: **not yet compiled or run on a device.**
Missing: key long-press, backspace repeat, emoji, other languages' layouts, streaming output.
