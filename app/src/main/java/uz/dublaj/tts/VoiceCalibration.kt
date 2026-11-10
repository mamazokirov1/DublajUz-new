package uz.dublaj.tts

import android.content.Context
import timber.log.Timber
import uz.dublaj.data.AppConfig
import uz.dublaj.text.TextNormalizer
import uz.dublaj.translate.GeminiTranslator
import uz.dublaj.util.WorkDir

/**
 * Tanlangan ovoz va sozlamalarda TTS soniyasiga nechta belgi aytishini o'lchaydi.
 * Tarjimaga "gapga necha belgi sig'adi" chegarasini haqiqiy tezlikdan beradi.
 * Natija sozlamalar o'zgarmaguncha eslab qolinadi.
 */
object VoiceCalibration {
    private const val PREFS = "dublaj_calib"

    private val SAMPLES = listOf(
        "Bugun biz yangi loyiha haqida gaplashamiz va uning asosiy afzalliklarini ko'rib chiqamiz.",
        "Bu juda muhim, chunki natija butun ishning sifatiga bog'liq bo'ladi.",
        "Agar savollaringiz bo'lsa, keyingi qismda ularga batafsil javob beramiz."
    )

    fun charsPerSec(context: Context, dir: WorkDir, config: AppConfig): Float {
        val fallback = GeminiTranslator.CHARS_PER_SEC.toFloat()
        if (!VitsSynthesizer.isReady(dir)) return fallback
        val modelLen = VitsSynthesizer.modelFiles(dir).first.length()
        val key = "${config.voiceSpeed}|${config.voiceNoise}|${config.voiceNoiseW}|${config.voicePitch}|$modelLen"
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getString("key", "") == key) {
            val v = sp.getFloat("cps", 0f)
            if (v > 0f) return v
        }
        return try {
            val synth = VitsSynthesizer.load(
                dir, TextNormalizer.fromAssets(context.applicationContext),
                noiseScale = config.voiceNoise,
                noiseScaleW = config.voiceNoiseW,
                minLengthScale = config.fitScale,
                pauseS = config.voicePause,
                threads = config.threads,
                baseLengthScale = 1f / config.voiceSpeed.coerceAtLeast(0.3f),
                pitchSemitones = config.voicePitch,
                userPron = config.pronDict
            )
            var chars = 0
            var sec = 0.0
            try {
                for (t in SAMPLES) {
                    val seg = synth.synthesize(t, null)
                    if (seg.pcm.isEmpty()) continue
                    chars += t.length
                    sec += seg.durationSec
                }
            } finally {
                synth.close()
            }
            if (chars == 0 || sec <= 0.5) return fallback
            val cps = (chars / sec).toFloat().coerceIn(8f, 30f)
            sp.edit().putString("key", key).putFloat("cps", cps).apply()
            Timber.i("Ovoz tezligi o'lchandi: %.1f belgi/s", cps)
            cps
        } catch (e: Exception) {
            Timber.w(e, "Ovoz tezligini o'lchab bo'lmadi")
            fallback
        }
    }
}
