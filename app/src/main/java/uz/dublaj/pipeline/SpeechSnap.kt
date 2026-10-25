package uz.dublaj.pipeline

import timber.log.Timber
import uz.dublaj.data.TranscriptUnit
import uz.dublaj.util.WavFile
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Whisper segment vaqtlari odatda noaniq: boshi ko'pincha 0.3–1 s ERTA, oxiri kech bo'ladi.
 * Bu yerda asl audioning energiyasi bo'yicha nutq oralig'lari (VAD) topilib, segment chegaralari
 * eng yaqin HAQIQIY nutq boshlanishi/tugashiga suriladi.
 *
 * v2: lokal shovqin darajasi (fon musiqasi/shovqinga chidamli), gisterezis, qisqa teshiklarni
 * yopish (so'z orasidagi pauza nutqni bo'lmasin) va kengroq qidiruv oynasi (boshi −0.5…+1.5 s).
 * Nutq ajralmasa (dinamika yo'q) — hech narsa o'zgartirilmaydi.
 */
object SpeechSnap {
    private const val FRAME_MS = 20
    private const val BACK = 0.5      // boshlanishni Whisper vaqtidan ko'pi bilan shuncha oldin qidirish
    private const val FWD = 1.5       // ... va shuncha keyin
    private const val E_BACK = 1.0    // tugashni qidirish oynasi
    private const val E_FWD = 1.2

    data class Region(val start: Double, val end: Double)

    private fun regions(wav: File): List<Region>? {
        WavFile(wav).use { w ->
            val frame = w.sampleRate * FRAME_MS / 1000
            if (frame <= 0) return null
            val n = (w.numSamples / frame).toInt()
            if (n < 100) return null

            // 1) kadrlar energiyasi (dB)
            val db = FloatArray(n)
            var f = 0
            while (f < n) {
                val cnt = min(500, n - f)
                val buf = w.read(f.toLong() * frame, cnt * frame)
                for (k in 0 until cnt) {
                    var s = 0.0
                    val o = k * frame
                    for (j in 0 until frame) {
                        val v = buf[o + j] / 32768.0
                        s += v * v
                    }
                    db[f + k] = (20.0 * Math.log10(Math.sqrt(s / frame) + 1e-6)).toFloat()
                }
                f += cnt
            }

            // 2) umumiy dinamika yetarlimi (aks holda nutqni ajratib bo'lmaydi)
            val sorted = db.copyOf()
            sorted.sort()
            val p10 = sorted[(n * 0.10).toInt()]
            val p95 = sorted[min(n - 1, (n * 0.95).toInt())]
            if (p95 - p10 < 10f) return null

            // 3) lokal shovqin darajasi: har 0.5 s da ±1 s oynadagi 15-persentil
            val step = 25
            val half = 50
            val floors = FloatArray(n)
            val tmp = FloatArray(2 * half)
            var b = 0
            while (b < n) {
                val lo = max(0, b - half)
                val hi = min(n, b + half)
                val len = hi - lo
                System.arraycopy(db, lo, tmp, 0, len)
                java.util.Arrays.sort(tmp, 0, len)
                val fl = tmp[min(len - 1, (len * 0.15).toInt())]
                val end = min(n, b + step)
                for (i in b until end) floors[i] = fl
                b += step
            }

            // 4) gisterezis: +10 dB dan oshsa nutq boshlanadi, +6 dB dan tushsa tugaydi
            val on = BooleanArray(n)
            var state = false
            for (i in 0 until n) {
                if (!state && db[i] > floors[i] + 10f) state = true
                else if (state && db[i] < floors[i] + 6f) state = false
                on[i] = state
            }

            // 5) oraliqlar; <150 ms teshiklar yopiladi, <100 ms nutq olib tashlanadi
            val regs = ArrayList<IntArray>()
            var i = 0
            while (i < n) {
                if (on[i]) {
                    var j = i
                    while (j < n && on[j]) j++
                    if (regs.isNotEmpty() && i - regs[regs.size - 1][1] < 8) {
                        regs[regs.size - 1][1] = j
                    } else {
                        regs.add(intArrayOf(i, j))
                    }
                    i = j
                } else {
                    i++
                }
            }
            return regs.filter { it[1] - it[0] >= 5 }
                .map { Region(it[0] * FRAME_MS / 1000.0, it[1] * FRAME_MS / 1000.0) }
        }
    }

    fun refine(wav: File, units: List<TranscriptUnit>): List<TranscriptUnit> {
        if (units.isEmpty()) return units
        val regs = regions(wav)
        if (regs == null || regs.isEmpty()) {
            Timber.i("SpeechSnap: nutq ajratilmadi (dinamika yetarli emas) — vaqtlar o'zgartirilmadi")
            return units
        }

        val out = ArrayList<TranscriptUnit>(units.size)
        var prevEnd = 0.0
        var moved = 0
        for (u in units.sortedBy { it.start }) {
            // --- boshlanish: eng yaqin nutq boshlanishi (kechroq bo'lganini afzal ko'ramiz: Whisper boshi odatda erta)
            var ns = u.start
            var best = Double.MAX_VALUE
            for (r in regs) {
                if (r.start < u.start - BACK) continue
                if (r.start > u.start + FWD) break
                if (r.start < prevEnd - 0.05) continue
                val d = r.start - u.start
                val score = if (d >= 0) d else -d * 1.5
                if (score < best) {
                    best = score
                    ns = r.start
                }
            }

            // --- tugash: eng yaqin nutq tugashi
            var ne = u.end
            best = Double.MAX_VALUE
            for (r in regs) {
                if (r.end < u.end - E_BACK) continue
                if (r.end > u.end + E_FWD) break
                if (r.end < ns + 0.3) continue
                val score = abs(r.end - u.end)
                if (score < best) {
                    best = score
                    ne = r.end
                }
            }

            if (ne < ns + 0.3) ne = u.end
            if (ne < ns + 0.3) ns = u.start
            if (abs(ns - u.start) > 0.01 || abs(ne - u.end) > 0.01) moved++
            prevEnd = ne
            out.add(u.copy(start = ns, end = ne))
        }
        Timber.i("SpeechSnap v2: ${regs.size} nutq oralig'i, ${out.size} segmentdan $moved tasi surildi")
        return out
    }
}
