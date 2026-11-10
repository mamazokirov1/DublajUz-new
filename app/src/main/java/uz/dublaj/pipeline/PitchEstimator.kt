package uz.dublaj.pipeline

import uz.dublaj.data.TranscriptUnit
import uz.dublaj.util.WavFile
import java.io.File
import kotlin.math.sqrt

/**
 * Har bir gap uchun asosiy ton chastotasi (F0, Hz) ni avtokorrelyatsiya bilan baholaydi.
 * Natija gapning `f0` maydoniga yoziladi (0 = aniqlanmadi). Erkak/ayol ajratish uchun yetarli darajada aniq.
 */
object PitchEstimator {
    private const val MIN_HZ = 70
    private const val MAX_HZ = 350

    fun annotate(wav: File, units: List<TranscriptUnit>): List<TranscriptUnit> {
        if (!wav.isFile) return units
        WavFile(wav).use { w ->
            val sr = w.sampleRate
            return units.map { u -> u.copy(f0 = estimate(w, sr, u.start, u.end)) }
        }
    }

    private fun estimate(w: WavFile, sr: Int, start: Double, end: Double): Float {
        val dur = end - start
        if (dur < 0.6) return 0f
        val use = minOf(dur, 5.0)
        val from = start + (dur - use) / 2
        val s = (from * sr).toLong().coerceAtLeast(0L)
        val raw = w.read(s, (use * sr).toInt())
        if (raw.size < sr / 2) return 0f

        // ~8 kHz gacha siyraklashtirish (tezlik uchun)
        val step = (sr / 8000).coerceAtLeast(1)
        val m = raw.size / step
        val x = FloatArray(m) { i ->
            var a = 0f
            for (k in 0 until step) a += raw[i * step + k]
            a / step / 32768f
        }
        val rate = sr / step
        val frame = (rate * 0.04).toInt()
        val hop = (rate * 0.05).toInt().coerceAtLeast(1)
        val minLag = rate / MAX_HZ
        val maxLag = rate / MIN_HZ
        if (frame <= 0 || minLag < 2 || m < frame + maxLag + 1) return 0f

        val starts = ArrayList<Int>()
        val rms = ArrayList<Float>()
        var p = 0
        while (p + frame + maxLag <= m) {
            var e = 0f
            for (i in 0 until frame) e += x[p + i] * x[p + i]
            starts.add(p)
            rms.add(sqrt(e / frame))
            p += hop
        }
        if (starts.isEmpty()) return 0f
        val maxRms = rms.max()
        if (maxRms < 0.005f) return 0f

        val f0s = ArrayList<Float>()
        for (fi in starts.indices) {
            if (rms[fi] < 0.35f * maxRms) continue
            val q = starts[fi]
            var e0 = 0f
            for (i in 0 until frame) e0 += x[q + i] * x[q + i]
            if (e0 <= 1e-9f) continue
            var bestLag = 0
            var bestC = 0f
            for (lag in minLag..maxLag) {
                var r = 0f
                var el = 0f
                for (i in 0 until frame) {
                    val b = x[q + i + lag]
                    r += x[q + i] * b
                    el += b * b
                }
                if (el <= 1e-9f) continue
                val c = r / sqrt(e0 * el)
                if (c > bestC) {
                    bestC = c
                    bestLag = lag
                }
            }
            if (bestLag > 0 && bestC >= 0.5f) f0s.add(rate.toFloat() / bestLag)
        }
        if (f0s.size < 5) return 0f
        f0s.sort()
        return f0s[f0s.size / 2]
    }
}
