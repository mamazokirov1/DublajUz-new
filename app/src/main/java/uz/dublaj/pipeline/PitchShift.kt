package uz.dublaj.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Ovoz tembrini (pitch) davomiylikni saqlagan holda o'zgartiradi:
 * avval qayta tanlab (resample) balandlik o'zgaradi, keyin WSOLA bilan uzunlik asl holiga qaytariladi.
 * semitones > 0 → ingichkaroq, < 0 → chuqurroq.
 */
object PitchShift {
    fun shift(pcm: ShortArray, sr: Int, semitones: Float): ShortArray {
        if (pcm.isEmpty() || abs(semitones) < 0.05f) return pcm
        val f = 2.0.pow(semitones / 12.0)
        // Chiziqli interpolyatsiya aliasing/shovqin berardi: sinc oynali qayta namunalash ishlatiladi
        val res = uz.dublaj.util.HqResample.byStep(pcm, f)
        return TimeStretch.stretch(res, sr, (1.0 / f).toFloat())
    }
}
