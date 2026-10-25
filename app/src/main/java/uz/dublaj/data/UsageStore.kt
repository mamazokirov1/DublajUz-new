package uz.dublaj.data

import android.content.Context
import java.util.Calendar

/**
 * Free versiya uchun kunlik limitlarni kuzatadi.
 * Pro da cheklov yo'q.
 */
object UsageStore {
    private const val PREFS = "dublaj_usage"
    /** Free da kuniga ruxsat etilgan dublyaj soni. */
    const val FREE_DAILY_DUBS = 3
    /** Free da kuniga ruxsat etilgan umumiy daqiqalar (taxminan). */
    const val FREE_DAILY_MINUTES = 15

    private fun dayKey(): String {
        val c = Calendar.getInstance()
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dubsToday(context: Context): Int {
        val sp = prefs(context)
        val day = dayKey()
        if (sp.getString("day", "") != day) return 0
        return sp.getInt("dubs", 0)
    }

    fun minutesToday(context: Context): Int {
        val sp = prefs(context)
        val day = dayKey()
        if (sp.getString("day", "") != day) return 0
        return sp.getInt("minutes", 0)
    }

    /** Dublyaj muvaffaqiyatli tugagach chaqiriladi. durationSec — video/audio davomiyligi. */
    fun recordDub(context: Context, durationSec: Double) {
        val sp = prefs(context)
        val day = dayKey()
        val editor = sp.edit()
        if (sp.getString("day", "") != day) {
            editor.putString("day", day).putInt("dubs", 0).putInt("minutes", 0)
        }
        val mins = (durationSec / 60.0).toInt().coerceAtLeast(1)
        editor.putInt("dubs", sp.getInt("dubs", 0) + 1)
            .putInt("minutes", sp.getInt("minutes", 0) + mins)
            .apply()
    }

    /**
     * Free foydalanuvchi hali limit ichidami?
     * @return null = mumkin, String = rad etish sababi (UI da ko'rsatish uchun)
     */
    fun canDub(context: Context, isPro: Boolean, estimatedMinutes: Int = 1): String? {
        if (isPro) return null
        val dubs = dubsToday(context)
        val mins = minutesToday(context)
        if (dubs >= FREE_DAILY_DUBS) {
            return "Bugungi bepul limit tugadi ($FREE_DAILY_DUBS ta video). Pro versiyaga o'ting yoki ertaga qayta urinib ko'ring."
        }
        if (mins + estimatedMinutes > FREE_DAILY_MINUTES) {
            return "Bugungi bepul daqiqalar tugadi (~$FREE_DAILY_MINUTES daqiqa). Pro versiyaga o'ting."
        }
        return null
    }

    fun remainingDubs(context: Context, isPro: Boolean): Int {
        if (isPro) return Int.MAX_VALUE
        return (FREE_DAILY_DUBS - dubsToday(context)).coerceAtLeast(0)
    }
}
