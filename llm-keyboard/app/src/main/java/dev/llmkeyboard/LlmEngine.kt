package dev.llmkeyboard

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/** Swap this out (e.g. for a llama.cpp JNI wrapper) without touching the keyboard. */
interface LlmEngine {
    val ready: Boolean
    /** Streams text to [onPartial]. [stale] is checked before starting; [maxWords] is a hint for callers. */
    fun generate(prompt: String, maxWords: Int = 0, stale: () -> Boolean = { false }, onPartial: (String) -> Unit = {}): String
}

/** Runs any MediaPipe-compatible open-weights model (.task / .litertlm), e.g. Gemma 3 1B or Qwen2.5 0.5B. */
class MediaPipeEngine(context: Context, modelFile: File) : LlmEngine {
    var error: String? = null
        private set

    private val llm: LlmInference? = try {
        LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(384)
                .build()
        )
    } catch (t: Throwable) {
        error = "${t.javaClass.simpleName}: ${t.message}"; null
    }

    override val ready get() = llm != null

    /**
     * Uses the plain LlmInference call. A per-call session with cancel() crashed natively on device
     * (exit reason 5), so generation is never cancelled; stale requests are skipped before they start.
     */
    @Synchronized
    override fun generate(prompt: String, maxWords: Int, stale: () -> Boolean, onPartial: (String) -> Unit): String {
        val engine = llm ?: return ""
        if (stale()) return ""
        val acc = StringBuffer()
        try {
            engine.generateResponseAsync(prompt) { part, _ -> acc.append(part); onPartial(acc.toString()) }.get()
        } catch (_: Exception) {
        }
        return acc.toString().trim()
    }
}

object ModelStore {
    fun file(context: Context) = File(context.filesDir, "model.task")
    fun exists(context: Context) = file(context).length() > 0
    fun log(context: Context) = File(context.filesDir, "diagnostics.txt")
}
