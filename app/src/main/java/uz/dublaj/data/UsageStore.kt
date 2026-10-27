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
    /** Free da BITTA video uchun eng uzun davomiylik (daqiqa). Undan uzun video uchun Pro kerak. */
    const val FREE_MAX_VIDEO_MINUTES = 15
    /** Nechta reklama ko'rilsa 1 ta qo'shimcha imkoniyat beriladi. */
    const val ADS_PER_BONUS = 5
    /** Free da matn → ovoz uchun bir martalik matn uzunligi (belgi). */
    const val FREE_TEXT_CHARS = 1500
    /** Pro da matn → ovoz uchun bir martalik matn uzunligi (belgi). */
    const val PRO_TEXT_CHARS = 200_000
    /** Free da navbatda bir vaqtda turishi mumkin bo'lgan videolar soni. */
    const val FREE_QUEUE_ITEMS = 2

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

    // ---------------------------------------------------------------- reklama evaziga imkoniyat

    /** Reklama ko'rib yig'ilgan, hali ishlatilmagan qo'shimcha imkoniyatlar. */
    fun bonusCredits(context: Context): Int = prefs(context).getInt("bonus", 0)

    /** Joriy bonus uchun ko'rilgan reklamalar (0 until ADS_PER_BONUS). */
    fun adsProgress(context: Context): Int = prefs(context).getInt("adsProgress", 0)

    /** Reklama oxirigacha ko'rildi. @return true — yangi imkoniyat berildi. */
    fun addRewardedAd(context: Context): Boolean {
        val sp = prefs(context)
        val progress = sp.getInt("adsProgress", 0) + 1
        return if (progress >= ADS_PER_BONUS) {
            sp.edit().putInt("adsProgress", 0).putInt("bonus", sp.getInt("bonus", 0) + 1).apply()
            true
        } else {
            sp.edit().putInt("adsProgress", progress).apply()
            false
        }
    }

    private fun consumeBonus(context: Context) {
        val sp = prefs(context)
        val b = sp.getInt("bonus", 0)
        if (b > 0) sp.edit().putInt("bonus", b - 1).apply()
    }

    /** Dublyaj muvaffaqiyatli tugagach chaqiriladi (faqat bepul foydalanuvchi uchun). */
    fun recordDub(context: Context, durationSec: Double) {
        val sp = prefs(context)
        val day = dayKey()
        val editor = sp.edit()
        if (sp.getString("day", "") != day) {
            editor.putString("day", day).putInt("dubs", 0).putInt("minutes", 0)
        }
        val before = dubsToday(context)
        val mins = (durationSec / 60.0).toInt().coerceAtLeast(1)
        editor.putInt("dubs", before + 1)
            .putInt("minutes", (if (sp.getString("day", "") != day) 0 else sp.getInt("minutes", 0)) + mins)
            .apply()
        // kunlik bepul limit tugagan bo'lsa — reklama imkoniyati sarflanadi
        if (before >= FREE_DAILY_DUBS) consumeBonus(context)
    }

    /** Boshqa ish (masalan, matn → ovoz) tugagach: kunlik limit tugagan bo'lsa 1 imkoniyat sarflanadi. */
    fun recordWork(context: Context) {
        if (dubsToday(context) >= FREE_DAILY_DUBS) consumeBonus(context)
    }

    /**
     * Free foydalanuvchi yangi ish boshlay oladimi?
     * @return null = mumkin, String = rad etish sababi (UI da ko'rsatish uchun)
     */
    fun canDub(context: Context, isPro: Boolean, estimatedMinutes: Int = 1): String? {
        if (isPro) return null
        if (dubsToday(context) < FREE_DAILY_DUBS) return null
        if (bonusCredits(context) > 0) return null
        return "Bugungi $FREE_DAILY_DUBS ta bepul imkoniyat tugadi. Reklama ko'rib qo'shimcha imkoniyat oling yoki Pro versiyaga o'ting."
    }

    /** Qolgan imkoniyatlar: kunlik bepul + reklama bilan yig'ilgan. */
    fun remainingDubs(context: Context, isPro: Boolean): Int {
        if (isPro) return Int.MAX_VALUE
        return (FREE_DAILY_DUBS - dubsToday(context)).coerceAtLeast(0) + bonusCredits(context)
    }
}
