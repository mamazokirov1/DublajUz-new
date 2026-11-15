package uz.dublaj.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * Dublaj klip'larini oldindan (hamma gap davomiyligi ma'lum bo'lgach) rejalashtiradi.
 *
 * Eski usulda har bir gap faqat KEYINGI gap boshigacha ko'rilar edi, sig'masa u asl gap boshidan
 * ERTA (0.12 s gacha) qo'yilar yoki tezlashtirilardi — keyingi gaplarda bo'sh vaqt bo'lsa ham.
 * Endi:
 *  - klip HECH QACHON asl gap boshidan oldin boshlanmaydi;
 *  - ortiqcha qism keyingi gaplardagi zaxiraga (qisqa tarjima, pauza) siljiydi (ko'pi bilan ~1 s);
 *  - tezlashtirish faqat zarur bo'lganda va eng kichik darajada.
 *
 * Har bir gap uchun tezlik tanlanadi: xarajat = w_speed*(v-1)^2 + keyingi gaplardagi kechikish kvadrati.
 */
object PlacementPlanner {
    class Plan(val placeAt: DoubleArray, val speed: FloatArray)

    private val SPEEDS = floatArrayOf(1.0f, 1.04f, 1.08f, 1.12f, 1.16f, 1.2f, 1.25f, 1.3f, 1.35f, 1.4f, 1.45f, 1.5f)
    private const val W_SPEED = 6.0
    private const val W_LAG = 1.0
    private const val W_OVER = 0.5
    private const val LOOKAHEAD = 6

    /**
     * @param starts har bir gapning (siljish qo'shilgan) asl boshlanishi, o'sish tartibida
     * @param nexts  keyingi gapning boshi (oxirgisi uchun — video davomiyligi)
     * @param durs   klipning tabiiy davomiyligi (tezlashtirilmagan), soniya
     */
    fun plan(
        starts: DoubleArray,
        nexts: DoubleArray,
        durs: DoubleArray,
        margin: Double,
        gap: Double,
        maxSpeed: Float,
        lagHard: Double = 1.0
    ): Plan {
        val n = starts.size
        val placeAt = DoubleArray(n)
        val speed = FloatArray(n) { 1f }
        var cursor = 0.0

        // Klip [end] da tugasa, keyingi gaplar qanchalik kechikadi: (kvadratlar yig'indisi, eng kattasi)
        fun cascade(i: Int, end: Double): DoubleArray {
            var cur = end + gap
            var cost = 0.0
            var worst = 0.0
            var j = i + 1
            while (j < n && j <= i + LOOKAHEAD) {
                val pj = max(starts[j], cur)
                val lag = pj - starts[j]
                if (lag < 0.02) break
                cost += lag * lag
                worst = max(worst, lag)
                cur = pj + durs[j] + gap
                j++
            }
            return doubleArrayOf(cost, worst)
        }

        for (i in 0 until n) {
            val from = max(starts[i], cursor)
            val lag0 = from - starts[i]
            // Kechikish to'planib borsa, tezlashtirish chegarasi biroz oshiriladi (ko'pi bilan 1.5×)
            val cap = if (lag0 <= 0.6) maxSpeed else min(1.5f, maxSpeed + ((lag0 - 0.6) * 0.5).toFloat())
            val limit = nexts[i] - margin

            val cands = ArrayList<Float>()
            for (sp in SPEEDS) if (sp <= cap + 1e-6f) cands.add(sp)
            if (cands.isEmpty() || cap > cands.last() + 1e-3f) cands.add(max(1f, cap))

            var bestCost = Double.MAX_VALUE
            var bestV = 1f
            for (v in cands) {
                val end = from + durs[i] / v
                val fits = end <= limit
                val cost = if (fits) {
                    W_SPEED * (v - 1.0) * (v - 1.0)
                } else {
                    val cc = cascade(i, end)
                    val over = end - limit
                    var c = W_SPEED * (v - 1.0) * (v - 1.0) + W_LAG * cc[0] + W_OVER * over * over
                    if (cc[1] > lagHard) c += 10.0 * (cc[1] - lagHard)
                    c
                }
                if (cost < bestCost - 1e-12) {
                    bestCost = cost
                    bestV = v
                }
                if (fits) break   // sig'gan eng kichik tezlik yetarli
            }
            placeAt[i] = from
            speed[i] = bestV
            cursor = from + durs[i] / bestV + gap
        }
        return Plan(placeAt, speed)
    }
}
