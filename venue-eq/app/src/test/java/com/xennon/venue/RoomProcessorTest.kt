package com.xennon.venue

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

class RoomProcessorTest {
    private fun render(room: Room?, seconds: Int = 8): FloatArray {
        val src = DemoSource()
        val proc = RoomProcessor(src.sampleRate)
        proc.update(room?.let { Snapshot(it, it.mix, 0.4f, it.eqDb.copyOf(), it.bass, it.width) })
        val out = FloatArray(src.sampleRate * seconds * 2)
        val block = FloatArray(512 * 2)
        var pos = 0
        while (pos < out.size) {
            src.read(block, 512)
            proc.process(block, 512)
            val n = minOf(block.size, out.size - pos)
            System.arraycopy(block, 0, out, pos, n)
            pos += n
        }
        return out
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i].toDouble()
        return sqrt(s / (to - from))
    }

    @Test fun everyRoomAudiblyChangesTheSound() {
        val dry = render(null)
        for (room in ROOMS.filter { it.id != "dry" }) {
            val wet = render(room)
            assertTrue(room.name, wet.all { it.isFinite() && it in -1f..1f })
            val diff = FloatArray(dry.size) { wet[it] - dry[it] }
            val ratio = rms(diff, 0, diff.size) / rms(dry, 0, dry.size)
            println("%-15s difference vs dry: %.0f%%".format(room.name, ratio * 100))
            assertTrue("${room.name} barely changes the sound", ratio > 0.15)
            writeWav(File("build/room-samples/${room.id}.wav"), wet)
        }
        writeWav(File("build/room-samples/dry.wav"), dry)
    }

    @Test fun bigRoomsRingLongerAfterTheLastHit() {
        // The loop's final beat is a lone snare: measure what's left 0.3-0.45 s after it.
        val rate = 44100
        val snare = (15 * rate * 60 / 120) * 2
        val from = snare + (0.3 * rate).toInt() * 2
        val to = snare + (0.45 * rate).toInt() * 2
        val dryTail = rms(render(null), from, to)
        val cathedralTail = rms(render(roomById("cathedral")), from, to)
        println("tail rms dry=%.5f cathedral=%.5f".format(dryTail, cathedralTail))
        assertTrue(cathedralTail > dryTail * 5)
    }

    private fun writeWav(f: File, data: FloatArray) {
        f.parentFile.mkdirs()
        RandomAccessFile(f, "rw").use { w ->
            w.setLength(0)
            fun le32(v: Int) = w.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = w.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            w.writeBytes("RIFF"); le32(36 + data.size * 2); w.writeBytes("WAVEfmt ")
            le32(16); le16(1); le16(2); le32(44100); le32(44100 * 4); le16(4); le16(16)
            w.writeBytes("data"); le32(data.size * 2)
            val bytes = ByteArray(data.size * 2)
            data.forEachIndexed { i, v ->
                val s = (v * 32767).toInt()
                bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s shr 8).toByte()
            }
            w.write(bytes)
        }
    }
}
