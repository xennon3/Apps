package app.murmur.voice

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.murmur.data.PendingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Voice notes travel as AMR audio inside an MMS: the format every carrier and phone
 * (iPhones included) plays, at about 1.5 KB a second.
 */
object Voice {
    const val MIME = "audio/amr"
    const val BYTES_PER_SECOND = 1_600

    fun isVoice(path: String?) = path?.endsWith(".amr") == true

    /** Longest note that fits the carrier's MMS limit, at most five minutes. */
    fun maxMillis(maxBytes: Int) = ((maxBytes.toLong() * 1000) / BYTES_PER_SECOND).coerceIn(30_000, 300_000)

    /** Length of an AMR file from its size: every 20 ms frame of 12.2 kbps AMR is 32 bytes. */
    fun durationOf(file: File) = ((file.length() - 6).coerceAtLeast(0) / 32) * 20

    // =========================================================== recording

    class Recorder(private val context: Context) {
        private var recorder: MediaRecorder? = null
        var file: File? = null; private set
        private var started = 0L

        fun start(): Boolean = runCatching {
            val f = File(PendingStore.outgoingDir(context), "voice-${UUID.randomUUID()}.amr")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            r.setAudioSamplingRate(8000)
            r.setAudioEncodingBitRate(12_200)
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            recorder = r; file = f; started = System.currentTimeMillis()
            true
        }.getOrElse { release(); false }

        val elapsed get() = if (recorder == null) 0 else System.currentTimeMillis() - started

        /** 0..1, for the live waveform. */
        fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f)

        /** Stops and returns the file, or null if it was too short to keep. */
        fun stop(): File? {
            val r = recorder ?: return null
            val ok = runCatching { r.stop() }.isSuccess
            release()
            val f = file
            return if (ok && f != null && f.length() > 600) f else { f?.delete(); null }
        }

        fun cancel() { runCatching { recorder?.stop() }; release(); file?.delete(); file = null }

        private fun release() { runCatching { recorder?.release() }; recorder = null }
    }

    // =========================================================== playback

    data class Playback(val source: Uri? = null, val playing: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0, val speed: Float = 1f, val earpiece: Boolean = false)

    /**
     * One note plays at a time. Holding the phone to your ear (proximity sensor) moves the
     * sound to the earpiece and turns the screen off, like a call.
     */
    object Player : SensorEventListener {
        private val _state = MutableStateFlow(Playback())
        val state: StateFlow<Playback> = _state

        private var mp: MediaPlayer? = null
        private var app: Context? = null
        private var wake: PowerManager.WakeLock? = null
        private var ticker: Thread? = null

        fun toggle(context: Context, uri: Uri) {
            val s = _state.value
            if (s.source == uri && mp != null) {
                if (s.playing) pause() else resume()
            } else {
                play(context.applicationContext, uri, 0, s.speed, earpiece = false)
            }
        }

        fun cycleSpeed() {
            val next = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
            _state.value = _state.value.copy(speed = next)
            mp?.let { p -> runCatching { if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(next) } }
        }

        fun seek(fraction: Float) {
            val p = mp ?: return
            runCatching { p.seekTo((p.duration * fraction).toInt()) }
            tick()
        }

        private fun play(context: Context, uri: Uri, from: Int, speed: Float, earpiece: Boolean) {
            stopInternal(keepState = false)
            app = context
            val am = context.getSystemService(AudioManager::class.java)
            am.mode = if (earpiece) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            // Only force the route for the earpiece; otherwise leave it alone so headphones keep working.
            @Suppress("DEPRECATION")
            if (earpiece) am.isSpeakerphoneOn = false
            val p = MediaPlayer()
            runCatching {
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                p.setDataSource(context, uri)
                p.prepare()
                if (from > 0) p.seekTo(from)
                p.playbackParams = p.playbackParams.setSpeed(speed)
                p.setOnCompletionListener { finish() }
                p.start()
            }.onFailure { p.release(); _state.value = Playback(); return }
            mp = p
            _state.value = Playback(uri, true, from, p.duration, speed, earpiece)
            listen(context, true)
            startTicker()
        }

        private fun pause() { runCatching { mp?.pause() }; _state.value = _state.value.copy(playing = false); listen(app, false) }

        private fun resume() {
            val p = mp ?: return
            runCatching { p.playbackParams = p.playbackParams.setSpeed(_state.value.speed); p.start() }
            _state.value = _state.value.copy(playing = true)
            listen(app, true); startTicker()
        }

        private fun finish() {
            stopInternal(keepState = false)
            _state.value = _state.value.copy(playing = false, positionMs = 0)
        }

        fun stop() { stopInternal(keepState = false); _state.value = Playback(speed = _state.value.speed) }

        private fun stopInternal(keepState: Boolean) {
            ticker?.interrupt(); ticker = null
            runCatching { mp?.release() }; mp = null
            listen(app, false)
            app?.getSystemService(AudioManager::class.java)?.mode = AudioManager.MODE_NORMAL
            if (!keepState) wake?.let { if (it.isHeld) it.release() }
        }

        private fun startTicker() {
            ticker?.interrupt()
            ticker = Thread {
                try { while (!Thread.currentThread().isInterrupted) { tick(); Thread.sleep(120) } } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
        }

        private fun tick() {
            val p = mp ?: return
            runCatching { _state.value = _state.value.copy(positionMs = p.currentPosition, durationMs = p.duration) }
        }

        private fun listen(context: Context?, on: Boolean) {
            val sm = context?.getSystemService(SensorManager::class.java) ?: return
            val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
            if (on) sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL) else sm.unregisterListener(this)
        }

        override fun onSensorChanged(event: SensorEvent) {
            val near = event.values[0] < minOf(5f, event.sensor.maximumRange)
            val s = _state.value
            val ctx = app ?: return
            val uri = s.source ?: return
            if (near == s.earpiece || !s.playing) return
            // Switch route: restart from the same spot through the earpiece (or back to the speaker).
            val pos = runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0)
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (near && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                wake = wake ?: pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "murmur:earpiece")
                if (!wake!!.isHeld) wake!!.acquire(10 * 60_000L)
            } else {
                wake?.let { if (it.isHeld) it.release() }
            }
            play(ctx, uri, (pos - 600).coerceAtLeast(0), s.speed, earpiece = near)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // =========================================================== transcription

    /** Murmur Brain can listen to voice notes itself; otherwise Android 13+ can transcribe on the phone. */
    fun canTranscribe(context: Context): Boolean =
        app.murmur.brain.Brain.ready(context) || (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context))

    /**
     * Turns a voice note into text with Android's own speech recognizer, preferring the
     * on-device one. Returns null when this phone can't (older Android, no recognizer).
     */
    suspend fun transcribe(context: Context, source: Uri): String? {
        if (!canTranscribe(context)) return null
        val pcm = withContext(Dispatchers.IO) { decodeToPcm(context, source) } ?: return null
        if (pcm.data.isEmpty()) return null
        if (app.murmur.brain.Brain.ready(context)) {
            app.murmur.brain.Brain.listen(context, wav16k(pcm))?.let { return it }
        }
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(context)) return null
        return withTimeoutOrNull(60_000) { withContext(Dispatchers.Main) { recognize(context, pcm) } }
    }

    private class Pcm(val data: ByteArray, val rate: Int, val channels: Int)

    /** Decoded audio as a 16 kHz mono 16-bit WAV file, the shape Murmur Brain's ears were tested with. */
    fun wavFor(context: Context, source: Uri): ByteArray? = decodeToPcm(context, source)?.takeIf { it.data.isNotEmpty() }?.let(::wav16k)

    private fun wav16k(pcm: Pcm): ByteArray {
        val inSamples = pcm.data.size / 2 / pcm.channels
        val src = java.nio.ByteBuffer.wrap(pcm.data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        fun mono(i: Int): Float { var sum = 0f; for (ch in 0 until pcm.channels) sum += src.get(i * pcm.channels + ch); return sum / pcm.channels }
        val outSamples = (inSamples.toLong() * 16_000 / pcm.rate).toInt().coerceAtMost(16_000 * 120) // two minutes is plenty
        val body = java.nio.ByteBuffer.allocate(outSamples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (j in 0 until outSamples) {
            val x = j.toDouble() * pcm.rate / 16_000
            val i = x.toInt().coerceAtMost(inSamples - 1)
            val f = (x - i).toFloat()
            val v = mono(i) * (1 - f) + mono((i + 1).coerceAtMost(inSamples - 1)) * f
            body.putShort(v.toInt().coerceIn(-32768, 32767).toShort())
        }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + outSamples * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(outSamples * 2)
        return h.array() + body.array()
    }

    private fun decodeToPcm(context: Context, source: Uri): Pcm? = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(context, source, null)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    val chunk = ByteArray(info.size); buf.get(chunk); out.write(chunk)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        Pcm(out.toByteArray(), rate, channels)
    }.getOrNull()

    private suspend fun recognize(context: Context, pcm: Pcm): String? = suspendCancellableCoroutine { cont ->
        val onDevice = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val sr = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        val text = StringBuilder()
        var done = false
        fun finish(result: String?) {
            if (done) return
            done = true
            runCatching { sr.destroy() }
            runCatching { pipe[0].close() }
            if (cont.isActive) cont.resume(result?.trim()?.takeIf { it.isNotEmpty() })
        }
        fun collect(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                if (text.isNotEmpty()) text.append(' ')
                text.append(it)
            }
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onSegmentResults(segmentResults: Bundle) = collect(segmentResults)
            override fun onEndOfSegmentedSession() = finish(text.toString())
            override fun onResults(results: Bundle?) { collect(results); finish(text.toString()) }
            override fun onError(error: Int) = finish(text.toString().ifBlank { null })
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcm.channels)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.rate)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        // Feed the audio in from a background thread; closing the pipe marks the end.
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm.data) } }
        }.start()
        cont.invokeOnCancellation { finish(null) }
        runCatching { sr.startListening(intent) }.onFailure { finish(null) }
    }
}
