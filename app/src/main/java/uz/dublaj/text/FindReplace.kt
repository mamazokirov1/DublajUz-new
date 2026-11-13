package uz.dublaj.text

import uz.dublaj.data.TranslationUnit

/** Tahrir ekranidagi «Qidirish va almashtirish» uchun yordamchilar. */
object FindReplace {
    // So'z ichidagi harf, raqam va o'zbek apostroflari (o', g', ʻ, ’) — butun so'z chegarasi uchun
    private const val WORD = "\\p{L}\\p{N}'ʻʼ’`"

    fun regex(find: String, matchCase: Boolean, wholeWord: Boolean): Regex {
        var pattern = Regex.escape(find)
        if (wholeWord) pattern = "(?<![$WORD])$pattern(?![$WORD])"
        return Regex(pattern, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }

    /** Nechta gapda uchraydi. */
    fun countUnits(units: List<TranslationUnit>, find: String, matchCase: Boolean, wholeWord: Boolean): Int {
        if (find.isEmpty()) return 0
        val re = regex(find, matchCase, wholeWord)
        return units.count { re.containsMatchIn(it.uzText) }
    }
}
