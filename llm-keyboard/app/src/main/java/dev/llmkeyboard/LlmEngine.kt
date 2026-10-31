package dev.llmkeyboard

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File

/** Swap this out (e.g. for a llama.cpp JNI wrapper) without touching the keyboard. */
interface LlmEngine {
    val ready: Boolean
    /** Streams text to [onPartial]; stops early at [maxWords] words or as soon as [stale] returns true. */
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

    @Synchronized
    override fun generate(prompt: String, maxWords: Int, stale: () -> Boolean, onPartial: (String) -> Unit): String {
        val engine = llm ?: return ""
        val session = LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).build()
        )
        val acc = StringBuffer()
        try {
            session.addQueryChunk(prompt)
            val future = session.generateResponseAsync { part, _ -> acc.append(part); onPartial(acc.toString()) }
            var stopped = false
            while (!future.isDone) {
                val words = acc.trim().split(Regex("\\s+")).size
                if (!stopped && (stale() || (maxWords > 0 && words > maxWords))) {
                    session.cancelGenerateResponseAsync(); stopped = true
                }
                Thread.sleep(15)
            }
            runCatching { future.get() }
        } catch (_: Exception) {
        } finally {
            runCatching { session.close() }
        }
        return acc.toString().trim()
    }
}

object ModelStore {
    fun file(context: Context) = File(context.filesDir, "model.task")
    fun exists(context: Context) = file(context).length() > 0
    fun log(context: Context) = File(context.filesDir, "diagnostics.txt")
}
