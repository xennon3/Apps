package com.xennon.venue

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/**
 * Routes other apps' audio through Venue's own [RoomProcessor]:
 * capture (Android's playback capture) -> process -> play back.
 *
 * The originals are silenced separately with a [SessionMute] on each app's session;
 * capture taps the audio before session effects, so it still hears them.
 */
@TargetApi(29)
@SuppressLint("MissingPermission") // RECORD_AUDIO is checked before capture is started.
class CaptureEngine(projection: MediaProjection) {
    private val processor = RoomProcessor(RATE)
    private val record: AudioRecord
    private val track: AudioTrack
    @Volatile private var running = true
    private val thread: Thread

    /** elapsedRealtime of the last block with audible content. */
    @Volatile var lastSoundAt = SystemClock.elapsedRealtime()
        private set

    init {
        val format = AudioFormat.Builder()
            .setSampleRate(RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // Never capture ourselves, or our own output would loop back forever.
            .excludeUid(Process.myUid())
            .build()
        val inBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(maxOf(inBuf, BLOCK * 8 * 4) * 2)
            .build()
        val outBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(outBuf * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        thread = Thread(::run, "venue-capture")
    }

    fun start(initial: Snapshot?) {
        processor.update(initial)
        thread.start()
    }

    fun update(s: Snapshot?) = processor.update(s)

    fun release() {
        running = false
        thread.join(1000)
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            record.startRecording()
            track.play()
            Log.i(TAG, "capture running: record=${record.recordingState} track=${track.playState}")
            val buf = FloatArray(BLOCK * 2)
            while (running) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "capture read error $n")
                    continue
                }
                var peak = 0f
                for (i in 0 until n) peak = maxOf(peak, abs(buf[i]))
                if (peak > 1e-4f) lastSoundAt = SystemClock.elapsedRealtime()
                processor.process(buf, n / 2)
                track.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }

    companion object {
        private const val TAG = "VenueCapture"
        private const val RATE = 48000
        private const val BLOCK = 256
    }
}

/** Silences one app's own output (its audio still reaches the capture). */
@TargetApi(29)
class SessionMute(val sessionId: Int) {
    private val dp: DynamicsProcessing

    init {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            false, 0, false, 0, false, 0, false,
        ).build()
        dp = DynamicsProcessing(Int.MAX_VALUE, sessionId, config)
        try {
            dp.setInputGainAllChannelsTo(-120f)
            dp.enabled = true
            check(dp.hasControl()) { "another app controls this session" }
        } catch (t: Throwable) {
            dp.release()
            throw t
        }
    }

    fun release() {
        runCatching { dp.enabled = false }
        runCatching { dp.release() }
    }
}
