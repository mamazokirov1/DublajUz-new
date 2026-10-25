package uz.dublaj.translate

/** Whisper / UI til kodlari. Tarjima Gemini orqali. */
object Languages {
    /** Sozlamalar menyusi uchun: (kod, nom). */
    val UI_OPTIONS: List<Pair<String, String>> = listOf(
        "auto" to "Avto (faqat en/ru farqlanadi)",
        "en" to "Inglizcha", "ru" to "Ruscha", "tr" to "Turkcha", "ar" to "Arabcha",
        "de" to "Nemischa", "fr" to "Fransuzcha", "es" to "Ispancha", "it" to "Italyancha",
        "pt" to "Portugalcha", "zh" to "Xitoycha", "ja" to "Yaponcha", "ko" to "Koreyscha",
        "hi" to "Hindcha", "fa" to "Forscha", "uk" to "Ukraincha", "kk" to "Qozoqcha",
        "tg" to "Tojikcha", "pl" to "Polyakcha", "nl" to "Niderlandcha", "id" to "Indonezcha",
        "vi" to "Vyetnamcha", "th" to "Tayland", "az" to "Ozarbayjoncha"
    )

    fun label(code: String): String = UI_OPTIONS.find { it.first == code }?.second ?: code
}
