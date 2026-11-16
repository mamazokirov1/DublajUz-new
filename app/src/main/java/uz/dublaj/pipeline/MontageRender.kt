package uz.dublaj.pipeline

import uz.dublaj.data.AppConfig
import uz.dublaj.util.WavFile
import uz.dublaj.util.WavWriter
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/** Montaj uchun aralashtirish: tinglash fayli (preview.wav), uning bir qismini yangilash va yakuniy video. */
object MontageRender {
    private const val BLOCK = 32 * 1024

    private fun newMixer(p: MontageProject, cfg: AppConfig, clips: List<SegmentPlacer.Clip>, ranges: List<Pair<Long, Long>>): DubMixer =
        DubMixer(
            p.sr, WavFile(p.origWav), clips,
            cfg.originalVolume, cfg.uzbekVolume, cfg.duckAmount, ranges,
            releaseMs = if (cfg.softTransitions) 450.0 else 250.0
        )

    /** Butun videoning aralashmasini preview.wav ga yozadi (ExoPlayer shuni chaladi). */
    fun buildPreview(p: MontageProject, cfg: AppConfig, onProgress: (Float) -> Unit = {}) {
        val total = (p.duration * p.sr).toLong()
        val tmp = File(p.dir, "preview.wav.tmp")
        newMixer(p, cfg, p.toRenderClips(), p.origRanges()).use { mixer ->
            WavWriter(tmp, p.sr).use { w ->
                var pos = 0L
                while (pos < total) {
                    val n = min(BLOCK.toLong(), total - pos).toInt()
                    w.write(mixer.mix(pos, n))
                    pos += n
                    onProgress(pos.toFloat() / total)
                }
            }
        }
        if (p.previewWav.exists()) p.previewWav.delete()
        if (!tmp.renameTo(p.previewWav)) { tmp.copyTo(p.previewWav, overwrite = true); tmp.delete() }
    }

    /**
     * Faqat [fromSec, toSec] oralig'ini qayta aralashtirib, preview.wav ichiga joyida yozadi
     * (klip surilganda butun videoni qayta yig'maslik uchun). Oldidagi 2 soniya — pasaytirish silliq boshlanishi uchun.
     */
    fun patchPreview(p: MontageProject, cfg: AppConfig, fromSec: Double, toSec: Double) {
        if (!p.previewWav.isFile) return
        val total = (p.duration * p.sr).toLong()
        val winStart = max(0L, (fromSec * p.sr).toLong())
        val winEnd = min(total, (toSec * p.sr).toLong())
        if (winEnd <= winStart) return
        val mixStart = max(0L, winStart - 2L * p.sr)
        val near = p.toRenderClips().filter { it.placeAtSample < winEnd && it.placeAtSample + it.samples > mixStart }
        val ranges = p.origRanges().filter { it.second > mixStart && it.first < winEnd }
        newMixer(p, cfg, near, ranges).use { mixer ->
            RandomAccessFile(p.previewWav, "rw").use { raf ->
                var pos = mixStart
                while (pos < winEnd) {
                    val n = min(BLOCK.toLong(), winEnd - pos).toInt()
                    val out = mixer.mix(pos, n)
                    if (pos + n > winStart) {
                        val skip = max(0L, winStart - pos).toInt()
                        val bytes = ByteArray((n - skip) * 2)
                        for (i in skip until n) {
                            val s = out[i].toInt()
                            bytes[(i - skip) * 2] = (s and 0xff).toByte()
                            bytes[(i - skip) * 2 + 1] = ((s shr 8) and 0xff).toByte()
                        }
                        raf.seek(44L + (pos + skip) * 2)
                        raf.write(bytes)
                    }
                    pos += n
                }
            }
        }
    }

    /** Saqlangan joylar bilan yakuniy videoni yig'adi (gaplar qayta ovozlanmaydi). */
    fun export(
        p: MontageProject, cfg: AppConfig, video: File, outFile: File,
        onProgress: (Float, String) -> Unit
    ): File {
        val total = (p.duration * p.sr).toLong()
        newMixer(p, cfg, p.toRenderClips(), p.origRanges()).use { mixer ->
            VideoMuxer.mux(video, outFile, p.sr, total, mixer, cfg.outAudioBitrate) { pr ->
                onProgress(pr, "Video yig'ilmoqda…")
            }
        }
        return outFile
    }
}
