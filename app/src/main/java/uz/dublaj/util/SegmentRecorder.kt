package uz.dublaj.util

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Gap bo'yicha mikrofon yozish (PCM 16-bit mono → WAV).
 * [maxSec] tugagach avtomatik to'xtaydi.
 */
class SegmentRecorder(
    private val outFile: File,
    private val sampleRate: Int = 22050,
    private val maxSec: Double
) {
    private val running = AtomicBoolean(false)
    private var recordThread: Thread? = null
    @Volatile var elapsedSec: Double = 0.0
        private set

    val isRecording: Boolean get() = running.get()

    fun start(onTick: (elapsed: Double, max: Double) -> Unit = { _, _ -> }, onDone: (ok: Boolean) -> Unit) {
        if (running.getAndSet(true)) return
        outFile.parentFile?.mkdirs()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            running.set(false); onDone(false); return
        }
        val bufSize = minBuf * 2
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: Exception) {
            Timber.e(e, "AudioRecord")
            running.set(false); onDone(false); return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); running.set(false); onDone(false); return
        }
        recordThread = thread(name = "seg-rec") {
            val pcm = ArrayList<Short>(sampleRate * maxSec.toInt().coerceAtLeast(1))
            val buf = ShortArray(bufSize / 2)
            val maxSamples = (maxSec * sampleRate).toInt().coerceAtLeast(sampleRate / 2)
            try {
                recorder.startRecording()
                val t0 = System.nanoTime()
                while (running.get()) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) for (i in 0 until n) pcm.add(buf[i])
                    elapsedSec = (System.nanoTime() - t0) / 1e9
                    onTick(elapsedSec, maxSec)
                    if (pcm.size >= maxSamples) break
                }
                running.set(false)
                writeWav(outFile, sampleRate, pcm)
                onDone(outFile.isFile && outFile.length() > 44)
            } catch (e: Exception) {
                Timber.e(e, "Yozish xato")
                outFile.delete()
                onDone(false)
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        try { recordThread?.join(3000) } catch (_: Exception) {}
    }

    companion object {
        fun writeWav(file: File, sampleRate: Int, pcm: List<Short>) {
            val dataBytes = pcm.size * 2
            FileOutputStream(file).use { out ->
                val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                hdr.put("RIFF".toByteArray())
                hdr.putInt(36 + dataBytes)
                hdr.put("WAVE".toByteArray())
                hdr.put("fmt ".toByteArray())
                hdr.putInt(16)
                hdr.putShort(1)
                hdr.putShort(1)
                hdr.putInt(sampleRate)
                hdr.putInt(sampleRate * 2)
                hdr.putShort(2)
                hdr.putShort(16)
                hdr.put("data".toByteArray())
                hdr.putInt(dataBytes)
                out.write(hdr.array())
                val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (s in pcm) body.putShort(s)
                out.write(body.array())
            }
        }

        /** Oddiy PCM 16 mono WAV o'qish. */
        fun readWavPcm(file: File): Pair<ShortArray, Int> {
            val bytes = file.readBytes()
            if (bytes.size < 44) return ShortArray(0) to 22050
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            // scan for data
            var pos = 12
            var sr = 22050
            var dataPos = 44
            var dataSize = bytes.size - 44
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "fmt ") {
                    sr = ByteBuffer.wrap(bytes, pos + 8 + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                }
                if (id == "data") {
                    dataPos = pos + 8
                    dataSize = size
                    break
                }
                pos += 8 + size
            }
            val n = (dataSize / 2).coerceAtMost((bytes.size - dataPos) / 2)
            val pcm = ShortArray(n)
            val data = ByteBuffer.wrap(bytes, dataPos, n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) pcm[i] = data.short
            return pcm to sr
        }
    }
}
