package uz.dublaj.pipeline

import android.content.Context
import timber.log.Timber
import uz.dublaj.data.AppConfig
import uz.dublaj.text.TextNormalizer
import uz.dublaj.text.VoiceRoles
import uz.dublaj.translate.splitApiKeys
import uz.dublaj.tts.GeminiTtsClient
import uz.dublaj.tts.VitsSynthesizer
import uz.dublaj.tts.gemVoiceFor
import uz.dublaj.util.AudioLevel
import uz.dublaj.util.HqResample
import uz.dublaj.util.RawPcm
import uz.dublaj.util.SegmentRecorder
import uz.dublaj.util.WorkDir
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Montajda BITTA tanlangan gapni qayta ovozlaydi (butun video qayta ovozlanmaydi).
 * Ovoz manbai: ONNX (oflayn, standart), Gemini (onlayn) yoki foydalanuvchining o'z yozgan ovozi.
 * Jarayon ikki bosqichli: [synth] yangi klipni vaqtinchalik faylga tayyorlaydi (uzoq, bekor qilinishi mumkin),
 * [apply] esa uni qisqa vaqtda klip o'rniga qo'yadi va loyihani saqlaydi.
 */
object ClipRevoicer {
    const val ENGINE_ONNX = "onnx"
    const val ENGINE_GEMINI = "gemini"

    /** Tayyor, lekin hali qo'llanmagan klip. */
    class Prepared(
        val tmp: File,
        val samples: Int,
        val speed: Float,
        /** Klip ajratilgan vaqtdan necha soniya oshib ketdi (0 = sig'di). */
        val overflowSec: Double
    )

    /**
     * @param ownWav foydalanuvchi yozgan WAV; null bo'lmasa [engine]/[role] e'tiborsiz (o'z ovozi ishlatiladi)
     * @param slotSec shu klipga ajratilgan vaqt (keyingi blokkacha), soniya
     */
    fun synth(
        context: Context, p: MontageProject, clip: MontageClip,
        text: String, role: String, engine: String, cfg: AppConfig,
        ownWav: File?, slotSec: Double
    ): Prepared {
        val app = context.applicationContext
        val dir = WorkDir(app)
        val outSr = p.sr
        val seg: VitsSynthesizer.SegmentAudio = when {
            ownWav != null -> {
                if (!ownWav.isFile || ownWav.length() <= 44) throw IllegalStateException("Yozilgan ovoz topilmadi. Qaytadan yozing.")
                val (pcm, sr) = SegmentRecorder.readWavPcm(ownWav)
                if (pcm.isEmpty()) throw IllegalStateException("Yozilgan ovoz bo'sh. Qaytadan yozing.")
                VitsSynthesizer.SegmentAudio(pcm, sr, 1f)
            }
            engine == ENGINE_GEMINI -> synthGemini(app, text, role, cfg, slotSec)
            else -> synthOnnx(app, dir, text, role, cfg, slotSec)
        }
        if (seg.pcm.isEmpty()) throw IllegalStateException("Ovoz hosil bo'lmadi (matn bo'sh yoki o'qib bo'lmadi)")

        val resampled = HqResample.resample(seg.pcm, seg.sampleRate, outSr)
        var audio = if (cfg.levelClips) AudioLevel.level(resampled) else resampled
        var dur = audio.size.toDouble() / outSr

        // Ajratilgan vaqtga sig'masa — pitch'ni saqlab tezlashtiramiz (eng ko'pi bilan maxAtempo)
        var speed = 1.0f
        if (slotSec > 0.0 && dur > slotSec * 1.02) {
            speed = min(cfg.maxAtempo, (dur / slotSec).toFloat())
            if (speed > 1.01f) {
                audio = TimeStretch.stretch(audio, outSr, speed)
                dur = audio.size.toDouble() / outSr
            } else speed = 1.0f
        }
        fade(audio, outSr, if (cfg.softTransitions) 30 else 12)

        val tmp = File(p.dir, "clip_${clip.id}.new")
        RawPcm.write(tmp, audio)
        val overflow = (dur - slotSec).coerceAtLeast(0.0)
        Timber.i("Qayta ovozlash #${clip.id}: dur=${"%.2f".format(dur)} slot=${"%.2f".format(slotSec)} speed=$speed")
        return Prepared(tmp, audio.size, speed, overflow)
    }

    /** Eski klipni [backup] ga ko'chirib, yangisini o'rniga qo'yadi va loyihani saqlaydi. */
    @Synchronized
    fun apply(p: MontageProject, clip: MontageClip, prep: Prepared, text: String, role: String, backup: File) {
        clip.file.copyTo(backup, overwrite = true)
        if (!prep.tmp.renameTo(clip.file)) {
            prep.tmp.copyTo(clip.file, overwrite = true)
            prep.tmp.delete()
        }
        clip.samples = prep.samples
        clip.speed = prep.speed
        clip.text = text
        clip.role = role
        p.save()
    }

    /** [apply] ni qaytaradi (Qaytarish tugmasi). */
    @Synchronized
    fun restore(p: MontageProject, clip: MontageClip, backup: File, oldSamples: Int, oldSpeed: Float, oldText: String, oldRole: String) {
        backup.copyTo(clip.file, overwrite = true)
        clip.samples = oldSamples
        clip.speed = oldSpeed
        clip.text = oldText
        clip.role = oldRole
        p.save()
    }

    // ------------------------------------------------------------------ manbalar

    private fun synthOnnx(app: Context, dir: WorkDir, text: String, role: String, cfg: AppConfig, slotSec: Double): VitsSynthesizer.SegmentAudio {
        if (!VitsSynthesizer.isReady(dir)) {
            throw IllegalStateException("ONNX ovoz komponenti yo'q. Sozlamalarda ovoz fayllarini (.onnx va .json) qo'shing yoki Gemini ovozini tanlang.")
        }
        val synth = VitsSynthesizer.load(
            dir, TextNormalizer.fromAssets(app),
            noiseScale = cfg.voiceNoise,
            noiseScaleW = cfg.voiceNoiseW,
            minLengthScale = cfg.fitScale,
            pauseS = cfg.voicePause,
            threads = cfg.threads,
            baseLengthScale = 1f / cfg.voiceSpeed.coerceAtLeast(0.3f),
            pitchSemitones = cfg.voicePitch,
            userPron = cfg.pronDict
        )
        try {
            // Ko'p spikerli model: rol alohida spiker; bir spikerli modelda rol ton siljitish bilan beriladi
            val spk = VitsSynthesizer.readSpeakers(dir)
            if (spk.isNotEmpty()) {
                synth.speakerId = spk[role] ?: spk[""] ?: spk.values.first()
                synth.pitchSemitones = cfg.voicePitch
            } else {
                synth.speakerId = null
                synth.pitchSemitones = VoiceRoles.pitchFor(role, cfg)
            }
            return synth.synthesize(text, slotSec.takeIf { it > 0.0 })
        } finally {
            synth.close()
        }
    }

    private fun synthGemini(app: Context, text: String, role: String, cfg: AppConfig, slotSec: Double): VitsSynthesizer.SegmentAudio {
        if (splitApiKeys(cfg.geminiKey).isEmpty()) {
            throw IllegalStateException("Gemini ovozi uchun Gemini API kaliti kerak: Sozlamalar → Tarjima (AI) da kalitni kiriting.")
        }
        val client = GeminiTtsClient(cfg.geminiKey, cfg.gemTtsModel)
        val voice = cfg.gemVoiceFor(role)
        val prepared = GeminiTtsClient.prepare(TextNormalizer.fromAssets(app), text)
        try {
            val base = client.synthesize(prepared, voice)
            // Sig'masa: tezroq o'qish uslubi bilan bir marta qayta so'raymiz (WSOLA tezlashtirishdan tabiiyroq)
            if (slotSec > 0.0 && base.durationSec > slotSec * 1.15) {
                try {
                    val fast = client.synthesize(prepared, voice, "speaking rapidly")
                    if (fast.durationSec < base.durationSec * 0.95) return fast
                } catch (e: GeminiTtsClient.TtsException) {
                    Timber.w("Gemini tez uslub: ${e.message}")
                }
            }
            return base
        } catch (e: GeminiTtsClient.TtsException) {
            throw IllegalStateException("Gemini ovozi: ${e.message}")
        }
    }

    private fun fade(a: ShortArray, sr: Int, fadeMs: Int) {
        val fade = min((sr * fadeMs / 1000.0).roundToInt().coerceAtLeast(1), a.size / 4)
        if (fade <= 0) return
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            a[i] = (a[i] * g).toInt().toShort()
            val j = a.size - 1 - i
            a[j] = (a[j] * g).toInt().toShort()
        }
    }
}
