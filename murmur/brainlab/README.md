# Brain lab

Runs Murmur Brain's real prompts, parsers and tools against the real model on a computer, with no phone involved. The plain-Kotlin brain files (`BrainPrompts`, `Calc`, `Skills`, `PlanTime`, `tools/`) are compiled straight from `../app`, so what passes here is what ships.

```sh
# once: the same model file the app downloads (2.6 GB, Apache 2.0, no sign-in needed)
curl -L -o gemma4.litertlm https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
gradle run --args="$PWD/gemma4.litertlm"
```

Every prompt change has been checked this way first. Several shipped bugs were caught here and never reached a phone:
- emoji crash the engine;
- the Qwen chat template ignored its input;
- raw JSON tool results made the model double-convert currency;
- a named skill was mistaken for a tool.
