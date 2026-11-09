package uz.dublaj.text

/**
 * Tarjima sifatini tekshirish. null = muammo yo'q, aks holda sabab (tahrir ekranida ko'rinadi).
 *
 * Muhim: TTS normalizer raqamlarni so'zga aylantiradi ("5" → "besh"). Shuning uchun
 * raqamlarni faqat "raqam sifatida bir xil matn" deb solishtirmaymiz.
 */
object TranslationQa {

    private val DIGITS = Regex("\\d+")
    private val WS = Regex("\\s+")

    private val UZ_NUM_WORDS = setOf(
        "nol", "bir", "ikki", "uch", "to'rt", "tort", "besh", "olti", "yetti", "sakkiz", "to'qqiz", "toqqiz",
        "o'n", "on", "yigirma", "o'ttiz", "ottiz", "qirq", "ellik", "oltmish", "yetmish", "sakson", "to'qson", "toqson",
        "yuz", "ming", "million", "milliard", "yarim", "butun"
    )

    fun issue(src: String, uz: String): String? {
        if (uz.isBlank()) return "Bo'sh tarjima"

        val srcNums = DIGITS.findAll(src).map { it.value }.toList()
        if (srcNums.isNotEmpty()) {
            val uzDigits = DIGITS.findAll(uz).map { it.value }.toSet()
            val uzLower = uz.lowercase()
            val hasWordNums = UZ_NUM_WORDS.any { w ->
                Regex("(?<!\\p{L})${Regex.escape(w)}(?!\\p{L})").containsMatchIn(uzLower)
            }
            val missing = srcNums.filter { n ->
                if (n in uzDigits) return@filter false
                if (n.trimStart('0').ifEmpty { "0" } in uzDigits) return@filter false
                if (hasWordNums) return@filter false
                true
            }
            if (missing.isNotEmpty() && !hasWordNums && uzDigits.isEmpty()) {
                return "Raqamlar yo'qolgan bo'lishi mumkin: ${missing.distinct()}"
            }
        }

        val ls = src.count { it.isLetter() }
        val lu = uz.count { it.isLetter() }
        if (ls >= 20) {
            val r = lu.toDouble() / ls
            if (r < 0.35 || r > 2.8) return "Uzunlik nomuvofiq (%.2f)".format(r)
        }

        val words = uz.lowercase().split(WS).filter { it.length > 3 }
        if (words.size >= 6) {
            val top = words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (top >= 4) return "Takror so'zlar"
        }

        val cyr = uz.count { it in '\u0400'..'\u04FF' }
        if (uz.isNotEmpty() && cyr > uz.length / 2) return "Tarjima qilinmagan (kirill)"

        return null
    }
}
