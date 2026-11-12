package uz.dublaj.data

/** Dublyajdan keyingi hisobot belgisi: bunday gaplar tasdiqlashni to'smaydi. */
const val DUB_ISSUE_PREFIX = "Dublyajda "

data class TranscriptUnit(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val language: String = "en",
    /** Asl nutqning asosiy ton chastotasi, Hz (0 = aniqlanmagan). */
    val f0: Float = 0f
)

data class TranslationUnit(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,          // original
    val uzText: String,        // foydalanuvchi tahrir qilgan o'zbekcha matn
    val language: String = "en",
    /** true = tarjima ishlamadi / tekshirish kerak. Foydalanuvchi tahrir qilgach false bo'ladi. */
    val check: Boolean = false,
    /** Tarjima nomzodlari (alts[0] = tanlangan). Dublaj vaqtida sig'masa qisqaroq variant sinab ko'riladi. */
    val alts: List<String> = emptyList(),
    /** QA sababi (tahrir ekranida ko'rsatiladi). */
    val issue: String? = null,
    /** Oddiy izoh: "" | "erkak" | "ayol" | "bola" — keyinchalik ovoz tanlash uchun. */
    val voiceRole: String = "",
    /** Foydalanuvchi shu gap uchun o'z ovozini yozganmi (segment wav mavjud). */
    val hasUserVoice: Boolean = false
)

data class AppConfig(
    val originalVolume: Float = 0.25f,
    val uzbekVolume: Float = 1.0f,
    val maxAtempo: Float = 1.3f,
    val whisperModel: String = "small",
    val threads: Int = 4,
    val marginS: Float = 0.1f,
    val gapS: Float = 0.08f,
    /** "auto" yoki Whisper til kodi (en, ru, tr, ...) */
    val sourceLang: String = "auto",
    // ---- ovoz sifati (VITS)
    /** Intonatsiya o'zgaruvchanligi (noise_scale). Yuqori = jonliroq, lekin beqarorroq. */
    val voiceNoise: Float = 0.667f,
    /** Ritm/davomiylik o'zgaruvchanligi (noise_scale_w). */
    val voiceNoiseW: Float = 0.4f,
    /** Nutq tezligi: 1.0 = oddiy, >1 tezroq (length_scale = 1 / voiceSpeed). */
    val voiceSpeed: Float = 1.0f,
    /** Gap ichidagi bo'laklar orasidagi pauza (soniya). */
    val voicePause: Float = 0.25f,
    /** Ovoz tembri, yarim ton (-4 chuqurroq … +4 ingichkaroq). 0 = o'zgarishsiz. */
    val voicePitch: Float = 0f,
    /** Gap vaqtga sig'masa sintez ichida eng kam length_scale (0.8 = 1.25× tezlashish). */
    val fitScale: Float = 0.8f,
    // ---- aralashtirish
    /** Dublaj gapirganda asl ovoz darajasi (1.0 = pasaytirilmaydi). */
    val duckAmount: Float = 0.2f,
    /** Har bir klip balandligini tenglash. */
    val levelClips: Boolean = true,
    /** Chiqish audio sifati: 0 = standart (22.05 kHz, 96 kbps), 1 = yuqori (44.1 kHz, 160 kbps). */
    val audioQuality: Int = 0,
    /** Gap izohiga qarab ovoz balandligi siljishi (yarim ton). */
    /** «Erkak» uchun qo'shimcha nozik sozlash (asosiy ovoz erkak bo'lsa 0 = o'zgarishsiz). */
    val rolePitchMale: Float = 0f,
    /** Erkak ovozidan ayol ovoziga farq (yarim ton). */
    val rolePitchFemale: Float = 5f,
    /** Erkak ovozidan bola ovoziga farq (yarim ton). */
    val rolePitchChild: Float = 8f,
    /** Gapirayotgan jinsini asl nutqdan avtomatik aniqlab, ovozga tembr siljishini qo'llash. */
    val autoVoiceRole: Boolean = true,
    /** Asosiy dublyaj ovozi (VITS modeli) ayol ovozimi. false = erkak. */
    val baseVoiceFemale: Boolean = false,
    // ---- ko'rinish
    /** false = Oddiy (kam sozlama), true = Mutaxassis (hamma sozlama). */
    val expertMode: Boolean = false,
    /** 0 = tizim, 1 = yorug', 2 = qorong'i. */
    val themeMode: Int = 0,
    val dynamicColor: Boolean = false,
    /** Tayyor videoni avtomatik Galereyaga (Movies/DublajUz) ham saqlash. */
    val autoSaveGallery: Boolean = false,
    // ---- onlayn tarjima (Gemini)
    val onlineTranslate: Boolean = true,
    /** Faol AI provayder identifikatori (AiProvider.id). */
    val aiProvider: String = "gemini",
    /** Boshqa provayderlar kalitlari: provayder id -> kalit. (Gemini kaliti geminiKey da.) */
    val aiKeys: Map<String, String> = emptyMap(),
    /** Provayder id -> model nomi (bo'sh = standart). */
    val aiModels: Map<String, String> = emptyMap(),
    /** "Boshqa (OpenAI-mos)" provayder uchun Base URL. */
    val aiBaseUrl: String = "",
    val geminiKey: String = "",
    val geminiModel: String = "gemini-3.5-flash",
    /** Video mavzusi va uslubi haqida erkin izoh (Gemini'ga beriladi). */
    val videoContext: String = "",
    /** Murojaat shakli: "auto", "sen", "siz". */
    val addressForm: String = "auto",
    /** Ohang: "casual" (og'zaki), "neutral", "formal". */
    val tone: String = "casual",
    /** Lug'at: har qatorda "so'z = tarjima". */
    val glossary: String = "",
    /** TTS talaffuz lug'ati: har qatorda "so'z = qanday o'qilsin". */
    val pronDict: String = "",
    /** Whisper vaqtlarini asl audio nutqiga moslash (SpeechSnap). */
    val snapSpeech: Boolean = true,
    /** Barcha dublaj gaplarini surish (soniya): musbat — kechroq. */
    val syncOffsetS: Float = 0f,
    // ---- Chatterbox (server) ovozi va klonlash
    /** "vits" (offline, standart) yoki "chatterbox" (server). */
    val ttsEngine: String = "vits",
    val cbUrl: String = "",
    val cbToken: String = "",
    /** "default" (o'qitilgan ovoz), "video" (videodagi spiker), "file" (tanlangan namuna). */
    val cbVoiceMode: String = "default",
    val cbCloneConsent: Boolean = false,
    val cbRefName: String = "",
    // ---- ko'rinish (Pro)
    /** Rang mavzusi: 0 = standart (bepul); 1.. = Pro mavzulari. */
    val accent: Int = 0,
    /** Ovoz tayyor bo'lganda avtomatik Musiqa papkasiga saqlash. */
    val autoSaveAudio: Boolean = false,
    /** Matn ajratish: nutqsiz (sukunat) oraliqlarni qirqib, tezroq ishlash. */
    val sttSkipSilence: Boolean = true,
    /** Matn ajratish protsessor oqimlari: 0 = avtomatik. */
    val sttThreads: Int = 0,
    /** Tarjima haroratini (ijodkorlik) qo'lda berish: 0.0..1.0 (OpenAI'dan boshqalarida). */
    val aiTemperature: Float = 0.3f,
    /** Bir so'rovda nechta gap tarjima qilinsin. */
    val aiChunkSize: Int = 18,
    /** Qo'shimcha tizim ko'rsatmasi (mutaxassislar uchun). */
    val aiExtraPrompt: String = "",
    // ---- monetizatsiya
    /** Pro versiya ochilganmi (Play Billing orqali). */
    val isPro: Boolean = false,
    /** Birinchi ochilishdagi onboarding tugaganmi. */
    val onboardingDone: Boolean = false
) {
    /** Chatterbox tanlangan va server manzili kiritilgan. */
    val chatterboxReady: Boolean get() = uz.dublaj.BuildConfig.CLOUD_TTS && ttsEngine == "chatterbox" && cbUrl.isNotBlank()

    val outSampleRate: Int get() = if (audioQuality >= 1) 44100 else 22050
    val outAudioBitrate: Int get() = if (audioQuality >= 1) 160_000 else 96_000

    /** Onlayn tarjima yoqilgan va kalit kiritilgan. */
    val onlineReady: Boolean
        get() {
            if (!onlineTranslate) return false
            val p = uz.dublaj.translate.AiProvider.byId(aiProvider)
            val key = if (p == uz.dublaj.translate.AiProvider.GEMINI) geminiKey else aiKeys[aiProvider].orEmpty()
            if (p.customUrl && aiBaseUrl.isBlank()) return false
            // lokal serverlar (Ollama) uchun kalit shart emas, lekin maydon bo'sh bo'lmasin
            return key.isNotBlank()
        }
}

enum class VoicePreset(val label: String) { STANDARD("Standart"), NATURAL("Tabiiy"), CLEAR("Aniq"), FAST("Tez") }

/** Boshlang'ich nuqtalar — quloq bilan sozlang. */
fun AppConfig.applyPreset(p: VoicePreset): AppConfig = when (p) {
    VoicePreset.STANDARD -> copy(voiceNoise = 0.667f, voiceNoiseW = 0.4f, voiceSpeed = 1.0f, voicePause = 0.25f, fitScale = 0.8f, maxAtempo = 1.3f)
    VoicePreset.NATURAL -> copy(voiceNoise = 0.75f, voiceNoiseW = 0.6f, voiceSpeed = 0.95f, voicePause = 0.35f, fitScale = 0.85f, maxAtempo = 1.2f)
    VoicePreset.CLEAR -> copy(voiceNoise = 0.5f, voiceNoiseW = 0.3f, voiceSpeed = 0.9f, voicePause = 0.3f, fitScale = 0.9f, maxAtempo = 1.15f)
    VoicePreset.FAST -> copy(voiceNoise = 0.667f, voiceNoiseW = 0.4f, voiceSpeed = 1.1f, voicePause = 0.15f, fitScale = 0.75f, maxAtempo = 1.4f)
}

data class ModelsInfo(
    val whisper: Boolean = false,
    val vits: Boolean = false
)

sealed class PipelineState {
    data object Idle : PipelineState()
    data class Running(val stage: String, val progress: Float = 0f, val message: String = "") : PipelineState()
    data class Success(val outputPath: String) : PipelineState()
    data class Error(val message: String) : PipelineState()
}

/** Tarjima paytida kalit/limit/internet muammosi chiqqanda foydalanuvchi qarori. */
sealed class TranslateDecision {
    /** Shu yerdan davom etish (kalit almashtirilgan bo'lishi mumkin). */
    data class Retry(val config: AppConfig) : TranslateDecision()
    /** Qolgan gaplarni tarjimasiz qoldirish (keyin qo'lda tuzatiladi). */
    data object Skip : TranslateDecision()
    /** To'xtatib turish: tayyor qismi saqlanadi, keyin davom etish mumkin. */
    data object Stop : TranslateDecision()
}

/** Tarjima to'xtatib qo'yildi; [done]/[total] gap tayyor va saqlangan. */
class TranslationPaused(val done: Int, val total: Int, message: String) : RuntimeException(message)

/** Dialogda ko'rsatiladigan to'xtash ma'lumoti. */
data class TranslatePause(val message: String, val done: Int, val total: Int)

/** Foydalanuvchi "Bekor qilish" bosganda tashlanadi. */
class UserCancelled : RuntimeException("Bekor qilindi")

enum class QueueStatus { WAITING, RUNNING, DONE, FAILED, CANCELLED }

/** Navbatdagi video. */
data class QueueItem(
    val id: Long,
    val uri: android.net.Uri,
    val name: String,
    val status: QueueStatus = QueueStatus.WAITING,
    val progress: Float = 0f,
    val message: String = "",
    val outputPath: String? = null
)

/** Pro'ga chorlovchi holatlar (UI dialog ko'rsatadi). */
sealed class ProPrompt {
    /** Bepul versiyada video juda uzun. */
    data class TooLong(val minutes: Int) : ProPrompt()
    /** Kunlik bepul imkoniyatlar tugadi. */
    data object LimitReached : ProPrompt()
}
