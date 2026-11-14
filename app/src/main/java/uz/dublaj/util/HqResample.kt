package uz.dublaj.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Yuqori sifatli qayta namunalash (Hann oynali sinc). Chiziqli interpolyatsiyadan farqi:
 * pasaytirishda yuqori chastotalar "aliasing" bo'lib shovqin chiqarmaydi, oshirishda esa "imaging" yo'q.
 * Ovoz klipi va ton siljitish uchun ishlatiladi.
 */
object HqResample {
    /** [step] = kirish namunalari soni / chiqish namunasi (>1: qisqaradi va ton ko'tariladi). */
    fun byStep(input: ShortArray, step: Double, zeroCrossings: Int = 10): ShortArray {
        if (input.isEmpty() || abs(step - 1.0) < 1e-9) return input
        val outLen = max(1, floor(input.size / step).toInt())
        // Pasaytirishda kesish chastotasi pasayadi (anti-alias)
        val fc = (if (step > 1.0) 1.0 / step else 1.0) * 0.95
        val half = ceil(zeroCrossings / fc).toInt()
        val res = 256
        val table = FloatArray(2 * half * res + 2) { idx ->
            val x = idx.toDouble() / res - half
            val sinc = if (abs(x) < 1e-9) fc else sin(PI * fc * x) / (PI * x)
            val w = 0.5 + 0.5 * cos(PI * x / half)
            (sinc * w).toFloat()
        }
        val out = ShortArray(outLen)
        for (n in 0 until outLen) {
            val c = n * step
            val i0 = floor(c).toInt()
            var acc = 0.0
            var wsum = 0.0
            for (k in (i0 - half + 1)..(i0 + half)) {
                if (k < 0 || k >= input.size) continue
                val pos = (k - c + half) * res
                if (pos < 0.0 || pos >= table.size - 1) continue
                val ti = pos.toInt()
                val h = table[ti] + (table[ti + 1] - table[ti]) * (pos - ti).toFloat()
                acc += h * input[k]
                wsum += h
            }
            val v = if (wsum > 1e-6) acc / wsum else 0.0
            out[n] = v.coerceIn(-32768.0, 32767.0).roundToInt().toShort()
        }
        return out
    }

    fun resample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray =
        if (srcRate == dstRate) input else byStep(input, srcRate.toDouble() / dstRate)
}
