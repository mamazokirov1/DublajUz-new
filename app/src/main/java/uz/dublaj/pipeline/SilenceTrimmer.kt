package uz.dublaj.pipeline

import timber.log.Timber
import uz.dublaj.util.WavFile
import uz.dublaj.util.WavWriter
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Whisper'ga berilishidan oldin uzun sukunat/shovqin oraliqlarini qirqadi.
 * Whisper ishlash vaqti audio uzunligiga proporsional, shuning uchun nutqsiz joylarni olib tashlash
 * to'g'ridan-to'g'ri tezlashtiradi (va "sukunatda gap to'qish" xatosini kamaytiradi).
 * Natija vaqtlari [Mapping] orqali asl audio vaqtiga qaytariladi.
 */
object SilenceTrimmer {
    private const val FRAME_MS = 20
    private const val MIN_GAP_S = 1.2      // shundan qisqa pauzalar saqlanadi
    private const val PAD_S = 0.4          // nutq atrofidagi xavfsizlik zaxirasi
    private const val JOIN_GAP_S = 0.2     // qirqilgan joylar orasiga qo'yiladigan sukunat
    private const val MIN_SAVING = 0.12    // kamida 12% qisqarmasa, qirqmaymiz
    private const val MIN_KEPT = 0.15      // 15% dan kam qolsa — noto'g'ri aniqlangan, qirqmaymiz

    /** Qirqilgan audio vaqtini asl vaqtga o'giradi. */
    class Mapping(
        private val condStart: DoubleArray,
        private val origStart: DoubleArray,
        private val length: DoubleArray
    ) {
        fun toOrigStart(t: Double): Double {
            for (i in condStart.indices) {
                val s = condStart[i]
                if (t < s + length[i]) return origStart[i] + max(0.0, t - s)
            }
            return origStart.last() + length.last()
        }

        fun toOrigEnd(t: Double): Double {
            for (i in condStart.indices.reversed()) {
                val s = condStart[i]
                if (t > s) return origStart[i] + min(t - s, length[i])
            }
            return origStart.first()
        }
    }

    class Result(val file: File, val mapping: Mapping, val keptSec: Double, val totalSec: Double) {
        val savedPercent: Int get() = (100.0 * (1.0 - keptSec / totalSec)).toInt()
    }

    /** @return null — qirqishning foydasi yo'q yoki nutq ajratib bo'lmadi (asl faylni ishlating). */
    fun trim(wav: File, out: File): Result? {
        WavFile(wav).use { w ->
            val sr = w.sampleRate
            val frame = sr * FRAME_MS / 1000
            if (frame <= 0) return null
            val n = (w.numSamples / frame).toInt()
            if (n < 500) return null // 10 soniyadan qisqa — foydasi yo'q

            // 1) kadr energiyasi (dB)
            val db = FloatArray(n)
            var f = 0
            while (f < n) {
                val cnt = min(500, n - f)
                val buf = w.read(f.toLong() * frame, cnt * frame)
                for (k in 0 until cnt) {
                    var s = 0.0
                    val o = k * frame
                    val end = min(frame, buf.size - o)
                    for (j in 0 until end) {
                        val v = buf[o + j] / 32768.0
                        s += v * v
                    }
                    db[f + k] = (20.0 * Math.log10(sqrt(s / frame) + 1e-6)).toFloat()
                }
                f += cnt
            }

            // 2) dinamika yetarlimi
            val sorted = db.copyOf()
            sorted.sort()
            val p10 = sorted[(n * 0.10).toInt()]
            val p95 = sorted[min(n - 1, (n * 0.95).toInt())]
            if (p95 - p10 < 12f) return null
            val thr = p10 + max(6f, 0.22f * (p95 - p10))

            // 3) nutq kadrlari; qisqa teshiklarni yopamiz
            val speech = BooleanArray(n) { db[it] > thr }
            val minGapF = MIN_GAP_S * 1000 / FRAME_MS
            var i = 0
            while (i < n) {
                if (!speech[i]) {
                    var j = i
                    while (j < n && !speech[j]) j++
                    val gapLen = j - i
                    if (i > 0 && j < n && gapLen < minGapF) for (k in i until j) speech[k] = true
                    i = j
                } else i++
            }

            // 4) oraliqlar (+zaxira), ustma-ust tushganlarini birlashtirish
            val padF = (PAD_S * 1000 / FRAME_MS).toInt()
            val regs = ArrayList<IntArray>()
            i = 0
            while (i < n) {
                if (speech[i]) {
                    var j = i
                    while (j < n && speech[j]) j++
                    val s = max(0, i - padF)
                    val e = min(n, j + padF)
                    if (regs.isNotEmpty() && s <= regs.last()[1]) regs.last()[1] = max(regs.last()[1], e)
                    else regs.add(intArrayOf(s, e))
                    i = j
                } else i++
            }
            if (regs.isEmpty()) return null

            val total = n.toDouble() * FRAME_MS / 1000.0
            val kept = regs.sumOf { (it[1] - it[0]).toDouble() } * FRAME_MS / 1000.0
            if (kept < total * MIN_KEPT || 1.0 - kept / total < MIN_SAVING) return null

            // 5) yozish va moslash jadvali
            val condStart = DoubleArray(regs.size)
            val origStart = DoubleArray(regs.size)
            val length = DoubleArray(regs.size)
            val gapSamples = ShortArray((JOIN_GAP_S * sr).toInt())
            var cum = 0.0
            out.parentFile?.mkdirs()
            WavWriter(out, sr).use { wr ->
                for ((idx, r) in regs.withIndex()) {
                    val startSample = r[0].toLong() * frame
                    val endSample = min(r[1].toLong() * frame, w.numSamples)
                    var pos = startSample
                    while (pos < endSample) {
                        val c = min(64 * 1024L, endSample - pos).toInt()
                        wr.write(w.read(pos, c))
                        pos += c
                    }
                    origStart[idx] = startSample.toDouble() / sr
                    length[idx] = (endSample - startSample).toDouble() / sr
                    condStart[idx] = cum
                    cum += length[idx] + JOIN_GAP_S
                    if (idx < regs.size - 1) wr.write(gapSamples)
                }
            }
            Timber.i("SilenceTrimmer: ${"%.0f".format(total)}s -> ${"%.0f".format(kept)}s, ${regs.size} oraliq")
            return Result(out, Mapping(condStart, origStart, length), kept, total)
        }
    }
}
