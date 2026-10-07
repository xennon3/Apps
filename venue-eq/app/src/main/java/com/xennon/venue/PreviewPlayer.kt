package com.xennon.venue

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder

/** Endless interleaved-stereo float audio. */
interface AudioSource {
    val sampleRate: Int

    /** Fills [frames] stereo frames into [out]. */
    fun read(out: FloatArray, frames: Int): Int
    fun release()
}

/**
 * Plays an [AudioSource] through Venue's own [RoomProcessor], so what you hear inside
 * the app doesn't depend on the phone's built-in effects at all.
 */
class PreviewPlayer(
    private val open: () -> AudioSource,
    private val onError: (Exception) -> Unit,
) {
    @Volatile private var running = true
    @Volatile private var pending: Snapshot? = null
    @Volatile var processor: RoomProcessor? = null
        private set
    private val thread = Thread(::run, "venue-preview")

    fun start(initial: Snapshot?) {
        pending = initial
        thread.start()
    }

    /** New settings, or null to bypass (Effects switched off). */
    fun update(s: Snapshot?) {
        pending = s
        processor?.update(s)
    }

    fun release() {
        running = false
        thread.join(1000)
    }

    private fun run() {
        var source: AudioSource? = null
        var track: AudioTrack? = null
        try {
            val src = open().also { source = it }
            val proc = RoomProcessor(src.sampleRate)
            proc.update(pending)
            processor = proc
            val minBuf = AudioTrack.getMinBufferSize(
                src.sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(src.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            t.play()
            val buf = FloatArray(BLOCK * 2)
            while (running) {
                src.read(buf, BLOCK)
                proc.process(buf, BLOCK)
                t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            Log.e("VenuePreview", "playback failed", e)
            if (running) onError(e)
        } finally {
            processor = null
            track?.run {
                try {
                    pause()
                    flush()
                } catch (_: Exception) {
                }
                release()
            }
            source?.release()
        }
    }

    companion object {
        private const val BLOCK = 512
    }
}

/** Decodes any audio file Android understands (mp3, m4a, flac, ogg, wav...) and loops it. */
class FileSource(context: Context, uri: Uri) : AudioSource {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var channels: Int
    private var floatPcm = false
    private var inputDone = false
    private var decoded = 0

    // Decoded but not yet consumed interleaved-stereo frames.
    private var pend = FloatArray(16384)
    private var pendStart = 0
    private var pendEnd = 0

    override val sampleRate: Int

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio track in this file")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        // Decode until the real output format is known (it can differ, e.g. HE-AAC).
        var tries = 0
        while (pendEnd == 0 && tries++ < 500) {
            val fmt = step()
            if (fmt != null) rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        }
        sampleRate = rate
    }

    override fun read(out: FloatArray, frames: Int): Int {
        var written = 0
        var stalls = 0
        while (written < frames) {
            if (pendStart == pendEnd) {
                pendStart = 0
                pendEnd = 0
                step()
                if (pendStart == pendEnd && ++stalls > 200) {
                    // Decoder stuck: pad with silence rather than block forever.
                    out.fill(0f, written * 2, frames * 2)
                    return frames
                }
                continue
            }
            val n = minOf(frames - written, (pendEnd - pendStart) / 2)
            System.arraycopy(pend, pendStart, out, written * 2, n * 2)
            pendStart += n * 2
            written += n
        }
        return frames
    }

    /** Feeds one input buffer and drains one output buffer. Returns a new output format if seen. */
    private fun step(): MediaFormat? {
        if (!inputDone) {
            val i = codec.dequeueInputBuffer(5000)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i)!!
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val o = codec.dequeueOutputBuffer(info, 5000)
        when {
            o >= 0 -> {
                if (info.size > 0) append(o)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) rewind()
            }
            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val f = codec.outputFormat
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                return f
            }
        }
        return null
    }

    private fun append(index: Int) {
        val bytes = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
        bytes.position(info.offset)
        bytes.limit(info.offset + info.size)
        val ch = channels.coerceAtLeast(1)
        val frames = info.size / (ch * if (floatPcm) 4 else 2)
        if (pend.size < pendEnd + frames * 2) pend = pend.copyOf(pendEnd + frames * 2)
        if (floatPcm) {
            val fb = bytes.asFloatBuffer()
            for (f in 0 until frames) {
                val l = fb.get(f * ch)
                pend[pendEnd++] = l
                pend[pendEnd++] = if (ch > 1) fb.get(f * ch + 1) else l
            }
        } else {
            val sb = bytes.asShortBuffer()
            for (f in 0 until frames) {
                val l = sb.get(f * ch) / 32768f
                pend[pendEnd++] = l
                pend[pendEnd++] = if (ch > 1) sb.get(f * ch + 1) / 32768f else l
            }
        }
        decoded += frames
    }

    private fun rewind() {
        if (decoded == 0) throw IllegalStateException("Couldn't decode any audio from this file")
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()
        inputDone = false
    }

    override fun release() {
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
        extractor.release()
    }
}
