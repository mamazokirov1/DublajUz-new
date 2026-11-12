package uz.dublaj.text

import uz.dublaj.data.AppConfig
import uz.dublaj.data.TranscriptUnit

/**
 * Asl nutqning ton balandligidan (F0) gapirayotgan jinsini taxmin qilib, har gapga "ayol"/"erkak" izohini beradi.
 * Dublyaj ovozi (asosiy TTS) jinsi bilan bir xil bo'lgan gaplarga izoh QO'YILMAYDI — ular o'zgarishsiz qoladi.
 */
object VoiceRoles {
    const val MALE_MAX_HZ = 160f
    const val FEMALE_MIN_HZ = 172f

    /** -1 = erkak, 1 = ayol, 0 = noaniq. */
    fun gender(f0: Float): Int = when {
        f0 <= 0f -> 0
        f0 <= MALE_MAX_HZ -> -1
        f0 >= FEMALE_MIN_HZ -> 1
        else -> 0
    }

    fun assign(units: List<TranscriptUnit>, baseVoiceFemale: Boolean): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (u in units) {
            val g = gender(u.f0)
            if (g == 0) continue
            val female = g > 0
            if (female == baseVoiceFemale) continue
            out[u.id] = if (female) "ayol" else "erkak"
        }
        return out
    }

    /**
     * Gap izohi ("erkak"/"ayol"/"bola"/"") uchun jami tembr siljishi (yarim ton, voicePitch ham qo'shiladi).
     *
     * Siljishlar ASOSIY OVOZ (model) jinsiga nisbatan hisoblanadi:
     *  - model erkak ovozi: «erkak» o'zgarmaydi, «ayol» va «bola» ko'tariladi;
     *  - model ayol ovozi: «ayol» o'zgarmaydi, «erkak» pasayadi, «bola» biroz ko'tariladi.
     * Sozlamalardagi qiymatlar ERKAK modelga nisbatan: rolePitchFemale = erkak→ayol farqi,
     * rolePitchChild = erkak→bola farqi, rolePitchMale = «erkak» uchun qo'shimcha nozik sozlash.
     */
    fun pitchFor(role: String, c: AppConfig): Float {
        val fromMale = when (role) {
            "erkak" -> c.rolePitchMale
            "ayol" -> c.rolePitchFemale
            "bola" -> c.rolePitchChild
            else -> return c.voicePitch
        }
        // Ayol model erkak o'lchamidan rolePitchFemale yarim ton yuqorida turadi
        val modelOffset = if (c.baseVoiceFemale) c.rolePitchFemale else 0f
        return (c.voicePitch + fromMale - modelOffset).coerceIn(-12f, 16f)
    }
}
