package uz.dublaj.pipeline

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.data.AppConfig
import uz.dublaj.data.ModelsInfo
import uz.dublaj.data.TranscriptUnit
import uz.dublaj.data.TranslationUnit
import uz.dublaj.data.UserCancelled
import uz.dublaj.stt.ModelDownloader
import uz.dublaj.stt.SttFixes
import uz.dublaj.stt.WhisperRecognizer
import uz.dublaj.text.SentenceMerger
import uz.dublaj.text.TextNormalizer
import uz.dublaj.text.TranslationQa
import uz.dublaj.translate.GeminiTranslator
import uz.dublaj.tts.ChatterboxClient
import uz.dublaj.tts.VitsSynthesizer
import uz.dublaj.util.FileUtils
import uz.dublaj.util.SyncReport
import uz.dublaj.util.ModelImporter
import uz.dublaj.util.StreamResampler
import uz.dublaj.util.WavFile
import uz.dublaj.util.WorkDir
import uz.dublaj.util.ExportUtils
import uz.dublaj.util.HistoryStore
import uz.dublaj.util.SrtExporter
import java.io.File

/**
 * Butun zanjir: video → audio → Whisper → Gemini → (tahrir) → VITS → joylash → mux.
 * Har bir bosqich oxirida og'ir resurslar (model) bo'shatiladi.
 */
class DubbingPipeline {

    companion object {
        private const val OUT_SR = 22050
        /** Whisper o'z oqimida ishlaydi; bekor qilinganda natija tashlab yuboriladi, tugagach xotira bo'shatiladi. */
        private val sttScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var sttBusy = false
    }

    @Volatile private var cancelled = false
    private var localVideo: File? = null
    private var workDir: WorkDir? = null

    fun cancel() { cancelled = true }
    fun resetCancel() { cancelled = false }

    private fun checkCancelled() {
        if (cancelled) throw UserCancelled()
    }

    /** Har progress chaqiruvida bekor qilishni tekshiradi (blokli sikllar shu orqali to'xtaydi). */
    private fun wrap(cb: (Float, String) -> Unit): (Float, String) -> Unit = { p, m ->
        checkCancelled()
        cb(p, m)
    }

    private fun ensure(context: Context): WorkDir =
        workDir ?: WorkDir(context.applicationContext).also { workDir = it }

    fun work(context: Context): WorkDir = ensure(context)

    // ------------------------------------------------------------------ modellar

    fun modelsInfo(context: Context, config: AppConfig): ModelsInfo {
        val dir = ensure(context)
        val wi = WhisperRecognizer.info(config.whisperModel)
        return ModelsInfo(
            whisper = ModelDownloader.isDownloaded(WhisperRecognizer.defaultModelFile(dir, wi.id), wi.minBytes),
            vits = VitsSynthesizer.isReady(dir)
        )
    }

    fun downloadWhisper(context: Context, modelId: String, onProgress: (Long, Long) -> Unit) {
        val dir = ensure(context)
        val wi = WhisperRecognizer.info(modelId)
        ModelDownloader.download(
            WhisperRecognizer.defaultModelFile(dir, wi.id), wi.fileName, wi.minBytes
        ) { d, t -> checkCancelled(); onProgress(d, t) }
    }

    /** O'zbekcha ovoz modelini (GitHub Release) yuklaydi. */
    fun downloadVits(context: Context, onProgress: (Long, Long) -> Unit) {
        val dir = ensure(context)
        uz.dublaj.tts.VitsDownloader.download(dir) { d, t -> checkCancelled(); onProgress(d, t) }
    }

    fun importWhisper(context: Context, uri: Uri, modelId: String) {
        val dir = ensure(context)
        val wi = WhisperRecognizer.info(modelId)
        ModelImporter.importFile(context, uri, WhisperRecognizer.defaultModelFile(dir, wi.id), wi.minBytes)
    }

    fun importVitsModel(context: Context, uri: Uri) {
        val (m, _) = VitsSynthesizer.modelFiles(ensure(context))
        ModelImporter.importFile(context, uri, m, 100_000)
    }

    /** Klonlash uchun namuna ovoz faylini saqlaydi. Qaytaradi: fayl nomi. */
    fun importVoiceRef(context: Context, uri: Uri): String {
        val dir = ensure(context)
        val name = FileUtils.displayName(context, uri)
        val ext = name.substringAfterLast('.', "wav").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "wav" }
        dir.root.listFiles()?.filter { it.name.startsWith("voice_ref.") }?.forEach { it.delete() }
        val dest = File(dir.root, "voice_ref.$ext")
        FileUtils.copyUriToFile(context, uri, dest)
        if (dest.length() > 25L * 1024 * 1024) {
            dest.delete()
            throw IllegalStateException("Namuna ovoz juda katta (25 MB gacha bo'lsin)")
        }
        return name
    }

    fun importVitsConfig(context: Context, uri: Uri) {
        val (_, c) = VitsSynthesizer.modelFiles(ensure(context))
        ModelImporter.importFile(context, uri, c, 10)
    }

    /** Ovozni tinglab ko'rish uchun TTS modelini yuklaydi (ViewModel keshlaydi). */
    fun loadPreviewSynth(context: Context, config: AppConfig): VitsSynthesizer {
        val dir = ensure(context)
        if (!VitsSynthesizer.isReady(dir)) {
            throw IllegalStateException("O'zbekcha ovoz komponenti yo'q. Sozlamalarda ovoz fayllarini (.onnx va .json) qo'shing.")
        }
        return VitsSynthesizer.load(
            dir, TextNormalizer.fromAssets(context.applicationContext),
            threads = config.threads, pitchSemitones = config.voicePitch,
            userPron = config.pronDict
        )
    }

    fun autoImport(context: Context, onProgress: (String) -> Unit): uz.dublaj.util.StorageScanner.ImportReport =
        uz.dublaj.util.StorageScanner.importAll(context, ensure(context), onProgress)

    fun clearWork(context: Context) {
        ensure(context).clearWork()
        localVideo = null
    }

    fun usedBytes(context: Context): Long = ensure(context).usedBytes()

    // ------------------------------------------------------------------ tiklash

    data class Restored(
        val videoFile: File?,
        val hasTranscript: Boolean,
        val units: List<TranslationUnit>,
        val approved: Boolean
    )

    /** Ilova qayta ochilganda (process o'ldirilgan bo'lsa) holatni diskdan tiklaydi. */
    fun restore(context: Context): Restored {
        val dir = ensure(context)
        var video: File? = null
        val hasTranscript = dir.transcriptJson.isFile
        if (hasTranscript) {
            try {
                val meta = JSONObject(dir.transcriptJson.readText()).optJSONObject("meta")
                val path = meta?.optString("video").orEmpty()
                if (path.isNotEmpty() && File(path).isFile) video = File(path)
            } catch (e: Exception) {
                Timber.w(e, "restore meta")
            }
        }
        localVideo = video
        val units = loadTranslation(context)
        return Restored(video, hasTranscript && video != null, units, dir.approvedFlag.isFile && units.isNotEmpty())
    }

    private fun prepareVideo(context: Context, uri: Uri, dir: WorkDir): File {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.isFile && f.parentFile?.canonicalPath == dir.input.canonicalPath) return f
        }
        val dest = File(dir.input, FileUtils.localVideoName(context, uri))
        return FileUtils.copyUriToFile(context, uri, dest)
    }

    // ------------------------------------------------------------------ 1) STT

    suspend fun transcribe(
        context: Context,
        videoUri: Uri,
        config: AppConfig,
        onProgress: (Float, String) -> Unit
    ): List<TranscriptUnit> {
        val progress = wrap(onProgress)
        val dir = ensure(context)

        val wi = WhisperRecognizer.info(config.whisperModel)
        val modelFile = WhisperRecognizer.defaultModelFile(dir, wi.id)
        if (!ModelDownloader.isDownloaded(modelFile, wi.minBytes)) {
            throw IllegalStateException("Matn aniqlash komponenti yo'q. Sozlamalar bo'limida uni yuklab oling.")
        }

        progress(0.02f, "Video nusxalanmoqda…")
        val video = prepareVideo(context, videoUri, dir)
        localVideo = video

        // Eski natijalar yaroqsiz
        dir.translationJson.delete()
        dir.approvedFlag.delete()
        dir.mixWav.delete()

        progress(0.1f, "Audio ajratilmoqda…")
        AudioExtractor.extractToWav(video, dir.extractedWav, 16000) { p ->
            progress(0.1f + 0.3f * p, "Audio ajratilmoqda…")
        }

        // Oldingi (bekor qilingan) matn aniqlash hali tugamagan bo'lsa, xotira to'qnashmasligi uchun kutamiz
        while (sttBusy) {
            checkCancelled()
            progress(0.42f, "Oldingi amal tugashi kutilmoqda…")
            delay(500)
        }
        val recognizer = WhisperRecognizer(context.applicationContext)
        var detached = false
        val result = try {
            progress(0.45f, "Nutq tanish komponenti yuklanmoqda…")
            recognizer.ensureModel(modelFile)
            sttBusy = true
            val job = sttScope.async {
                try {
                    recognizer.transcribe(dir.extractedWav, config.sourceLang) { p, m ->
                        progress(0.5f + 0.45f * p, m)
                    }
                } finally {
                    sttBusy = false
                }
            }
            while (!job.isCompleted) {
                if (cancelled) {
                    // Native kod to'xtatilmaydi: UI darhol bo'shatiladi, model esa tugagach yopiladi
                    detached = true
                    job.invokeOnCompletion { recognizer.release() }
                    throw UserCancelled()
                }
                delay(150)
            }
            job.await()
        } finally {
            if (!detached) recognizer.release()          // tarjima uchun xotirani bo'shatamiz
        }
        checkCancelled()
        val fixes = SttFixes.load(context, File(dir.root, "stt_fixes.json"))
        val fixedUnits: List<TranscriptUnit> = SttFixes.apply(result.units, fixes)
        val units: List<TranscriptUnit> = if (!config.snapSpeech) fixedUnits else try {
            SpeechSnap.refine(dir.extractedWav, fixedUnits)
        } catch (e: Exception) {
            Timber.w(e, "SpeechSnap ishlamadi")
            fixedUnits
        }
        SyncReport.reset(context, "STT vaqtlari (xom Whisper -> moslangan), moslash=${config.snapSpeech}, ${units.size} segment")
        val rawById = fixedUnits.associateBy { it.id }
        for (u in units) {
            val r = rawById[u.id] ?: continue
            SyncReport.add(
                context,
                "#${u.id} ${SyncReport.f(r.start)}-${SyncReport.f(r.end)} -> ${SyncReport.f(u.start)}-${SyncReport.f(u.end)} " +
                    "(dS ${SyncReport.sf(u.start - r.start)}, dE ${SyncReport.sf(u.end - r.end)})"
            )
        }
        // Ovoz klonlash uchun videodan namuna ovoz (extracted.wav o'chirilishidan oldin)
        try {
            ReferenceVoice.extract(dir.extractedWav, units, dir.refAutoWav)
        } catch (e: Exception) {
            Timber.w(e, "Namuna ovoz ajratilmadi")
        }
        val lang: String = result.language
        dir.extractedWav.delete()

        if (units.isEmpty()) throw IllegalStateException("Videoda nutq topilmadi")

        val meta = JSONObject().apply {
            put("language", lang)
            put("video", video.absolutePath)
            put("count", units.size)
        }
        val arr = JSONArray()
        for (u in units) {
            arr.put(JSONObject().apply {
                put("id", u.id); put("start", u.start); put("end", u.end)
                put("text", u.text); put("language", u.language)
            })
        }
        dir.transcriptJson.writeText(JSONObject().put("meta", meta).put("units", arr).toString(2))
        progress(1f, "Transkript tayyor: ${units.size} segment")
        return units
    }

    private fun loadTranscript(context: Context): List<TranscriptUnit> {
        val dir = ensure(context)
        if (!dir.transcriptJson.isFile) throw IllegalStateException("Matn hali ajratilmagan — avval \"Matn ajratish\" ni bajaring")
        val arr = JSONObject(dir.transcriptJson.readText()).getJSONArray("units")
        return (0 until arr.length()).map { i ->
            val u = arr.getJSONObject(i)
            TranscriptUnit(
                u.getInt("id"), u.getDouble("start"), u.getDouble("end"),
                u.getString("text"), u.optString("language", "en")
            )
        }
    }

    // ------------------------------------------------------------------ 2) tarjima (faqat Gemini — NLLB (CC-BY-NC) tufayli olib tashlandi)

    fun translate(
        context: Context,
        config: AppConfig,
        onProgress: (Float, String) -> Unit
    ): List<TranslationUnit> {
        val progress = wrap(onProgress)
        val dir = ensure(context)
        val raw = loadTranscript(context)
        // Whisper bo'laklarini TO'LIQ GAPLARGA birlashtiramiz: tarjima gap bo'yicha bo'ladi
        val src = SentenceMerger.merge(raw)
        Timber.i("Gaplar: ${raw.size} segment → ${src.size} gap")

        if (!config.onlineReady) {
            throw IllegalStateException(
                "Tarjima uchun Gemini API kaliti kerak. Sozlamalar → Onlayn tarjima bo'limida kalitni kiriting."
            )
        }

        val norm = TextNormalizer.fromAssets(context.applicationContext)
        val result = ArrayList<TranslationUnit>()
        var failed = 0
        var flagged = 0
        var firstError: String? = null

        progress(0.02f, "Gemini bilan tarjima…")
        val gem = GeminiTranslator(config.geminiKey, config.geminiModel.ifBlank { GeminiTranslator.DEFAULT_MODEL })
        val online = HashMap<Int, GeminiTranslator.Result>()
        var onlineNote: String? = null
        var idx = 0
        try {
            while (idx < src.size) {
                checkCancelled()
                // bo'lak: ko'pi bilan 18 gap yoki ~3500 belgi
                val chunk = ArrayList<TranscriptUnit>()
                var chars = 0
                while (idx + chunk.size < src.size && chunk.size < 18 && (chunk.isEmpty() || chars < 3500)) {
                    val u = src[idx + chunk.size]
                    chunk.add(u)
                    chars += u.text.length
                }
                progress(0.05f + 0.85f * idx / src.size, "Gemini ${idx + 1}–${idx + chunk.size}/${src.size}")
                val items = chunk.mapIndexed { k, u ->
                    val nextStart = src.getOrNull(idx + k + 1)?.start
                    val slot = maxOf(u.end - u.start, (nextStart ?: (u.end + 1.5)) - u.start)
                    GeminiTranslator.Item(u.id, u.text, (slot * GeminiTranslator.CHARS_PER_SEC).toInt().coerceAtLeast(20))
                }
                // oldingi 3 gapning tarjimasi — kontekst sifatida; keyingi gap — maslahat
                val ctxPairs = ArrayList<Pair<String, String>>()
                for (k in maxOf(0, idx - 3) until idx) {
                    val prev = online[src[k].id]
                    if (prev != null) ctxPairs.add(src[k].text to prev.uz)
                }
                val srcLang = chunk.firstOrNull()?.language ?: "en"
                val nextHint = src.getOrNull(idx + chunk.size)?.text
                val got: Map<Int, GeminiTranslator.Result> = try {
                    gem.translateChunk(
                        srcLang,
                        ctxPairs,
                        items,
                        nextHint,
                        GeminiTranslator.Style(config.videoContext, config.addressForm, config.tone, config.glossary)
                    )
                } catch (e: GeminiTranslator.GeminiException) {
                    Timber.w(e, "Gemini chunk xato")
                    if (firstError == null) firstError = e.message
                    emptyMap()
                }
                online.putAll(got)
                idx += chunk.size
            }
        } catch (e: UserCancelled) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Gemini tarjima xato")
            if (firstError == null) firstError = e.message
        }

        // Natijalarni yig'ish
        for (u in src) {
            checkCancelled()
            val g = online[u.id]
            if (g != null && g.uz.isNotBlank()) {
                val uz = norm.normalize(g.uz)
                val qa = TranslationQa.issue(u.text, uz)
                if (qa != null) {
                    flagged++
                    Timber.w("Tarjima shubhali #${u.id}: $qa")
                }
                val alts = ArrayList<String>()
                alts.add(uz)
                for (a in listOfNotNull(g.short)) {
                    val n = norm.normalize(a)
                    if (n.isNotBlank() && n !in alts) alts.add(n)
                }
                result.add(TranslationUnit(u.id, u.start, u.end, u.text, uz, u.language, qa != null, alts))
            } else {
                failed++
                if (firstError == null) firstError = "Gemini javob bermadi"
                result.add(TranslationUnit(u.id, u.start, u.end, u.text, u.text, u.language, true))
            }
        }

        if (failed == src.size) {
            throw IllegalStateException("Hech bir segment tarjima qilinmadi: $firstError")
        }

        dir.approvedFlag.delete()
        saveTranslation(context, result)
        val done = "Tarjima tayyor (Gemini: ${online.size}/${src.size})"
        progress(1f, if (failed + flagged > 0) "Tayyor (${failed + flagged} ta gap tekshirilsin)" else done)
        return result
    }


    fun saveTranslation(context: Context, units: List<TranslationUnit>) {
        val dir = ensure(context)
        val arr = JSONArray()
        for (u in units) {
            arr.put(JSONObject().apply {
                put("id", u.id); put("start", u.start); put("end", u.end)
                put("text", u.text); put("uzText", u.uzText)
                put("language", u.language); put("check", u.check)
            })
        }
        dir.translationJson.writeText(arr.toString(2))
    }

    fun loadTranslation(context: Context): List<TranslationUnit> {
        val dir = ensure(context)
        if (!dir.translationJson.isFile) return emptyList()
        return try {
            val arr = JSONArray(dir.translationJson.readText())
            (0 until arr.length()).map { i ->
                val u = arr.getJSONObject(i)
                TranslationUnit(
                    u.getInt("id"), u.getDouble("start"), u.getDouble("end"),
                    u.getString("text"), u.getString("uzText"),
                    u.optString("language", "en"), u.optBoolean("check", false),
                    u.optJSONArray("alts")?.let { arr -> (0 until arr.length()).map { k -> arr.getString(k) } } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "translation.json buzuq")
            emptyList()
        }
    }

    fun markApproved(context: Context) {
        ensure(context).approvedFlag.writeText("approved")
    }

    fun clearApproval(context: Context) {
        ensure(context).approvedFlag.delete()
    }

    fun isApproved(context: Context): Boolean = ensure(context).approvedFlag.isFile

    // ------------------------------------------------------------------ 3) dublyaj

    fun dub(
        context: Context,
        units: List<TranslationUnit>,
        config: AppConfig,
        title: String = "",
        onProgress: (Float, String) -> Unit
    ): File {
        val progress = wrap(onProgress)
        val dir = ensure(context)
        if (!dir.approvedFlag.isFile) throw IllegalStateException("Tarjima tasdiqlanmagan")
        val useCb = config.chatterboxReady
        val vitsOk = VitsSynthesizer.isReady(dir)
        if (!useCb && !vitsOk) {
            throw IllegalStateException(
                "O'zbekcha ovoz komponenti yo'q. Sozlamalarda ovoz fayllarini (.onnx va .json) qo'shing."
            )
        }
        val cbRef: File? = if (useCb) {
            when (config.cbVoiceMode) {
                "video" -> dir.refAutoWav.takeIf { it.isFile && it.length() > 0 }
                "file" -> dir.refUserFile
                else -> null
            }
        } else null
        if (useCb && config.cbVoiceMode != "default") {
            if (!config.cbCloneConsent) {
                throw IllegalStateException("Ovoz klonlash uchun Sozlamalarda rozilik belgisini qo'ying")
            }
            if (cbRef == null) {
                throw IllegalStateException(
                    if (config.cbVoiceMode == "video") "Videodan namuna ovoz ajratilmagan. Videoni qayta aniqlang yoki \"Namuna fayl\" rejimini tanlang."
                    else "Namuna ovoz fayli tanlanmagan"
                )
            }
        }
        val video = localVideo?.takeIf { it.isFile }
            ?: throw IllegalStateException("Video topilmadi — videoni qayta tanlang")

        val duration = AudioExtractor.probeDuration(video)
        if (duration <= 0.0) throw IllegalStateException("Video davomiyligi aniqlanmadi")

        val active = units.filter { it.uzText.isNotBlank() && it.start < duration }.sortedBy { it.start }
        if (active.isEmpty()) throw IllegalStateException("Dublyaj uchun matn yo'q")

        FileUtils.ensureSpace(dir.temp, (duration * OUT_SR * 2 * 2).toLong() + 100L * 1024 * 1024)
        FileUtils.ensureSpace(dir.output, video.length() + 50L * 1024 * 1024)

        val norm = TextNormalizer.fromAssets(context.applicationContext)
        val placer = SegmentPlacer(
            totalSec = duration, sr = OUT_SR,
            maxSpeed = config.maxAtempo,
            marginSec = config.marginS.toDouble(),
            gapSec = config.gapS.toDouble(),
            clipDir = dir.temp
        )
        val clips = ArrayList<SegmentPlacer.Clip>()
        val usedText = HashMap<Int, String>()
        val lags = ArrayList<Double>()
        val off = config.syncOffsetS.toDouble()
        SyncReport.add(
            context,
            "--- DUBLAJ: siljish=${SyncReport.sf(off)} s, maxAtempo=${config.maxAtempo}, fitScale=${config.fitScale}, gap=${config.gapS}, margin=${config.marginS} ---"
        )

        progress(0.02f, if (useCb) "Chatterbox serveriga ulanilmoqda…" else "Ovoz komponenti yuklanmoqda…")
        var vitsSynth: VitsSynthesizer? = null
        fun vits(): VitsSynthesizer {
            vitsSynth?.let { return it }
            val loaded = VitsSynthesizer.load(
                dir, norm,
                noiseScale = config.voiceNoise,
                noiseScaleW = config.voiceNoiseW,
                minLengthScale = config.fitScale,
                pauseS = config.voicePause,
                threads = config.threads,
                baseLengthScale = 1f / config.voiceSpeed.coerceAtLeast(0.3f),
                pitchSemitones = config.voicePitch,
                userPron = config.pronDict
            )
            vitsSynth = loaded
            return loaded
        }
        val cb = if (useCb) ChatterboxClient(config.cbUrl, config.cbToken) else null
        var cbFails = 0
        var cbOff = false
        var fallbacks = 0
        fun synthSeg(text: String, slot: Double): VitsSynthesizer.SegmentAudio {
            if (cb != null && !cbOff) {
                try {
                    val r = cb.synthesize(text, cbRef)
                    cbFails = 0
                    return r
                } catch (e: ChatterboxClient.ChatterboxException) {
                    cbFails++
                    Timber.w("Chatterbox xato ($cbFails): ${e.message}")
                    if (!vitsOk) throw IllegalStateException("Chatterbox serveri: ${e.message}")
                    if (cbFails >= 3) cbOff = true
                    fallbacks++
                }
            }
            return vits().synthesize(text, slot)
        }
        try {
            active.forEachIndexed { i, u ->
                progress(0.05f + 0.55f * i / active.size, "Ovozlashtirish ${i + 1}/${active.size}")
                val sStart = u.start + off
                val sNext = active.getOrNull(i + 1)?.start?.plus(off)
                // Haqiqiy bo'sh vaqt: oldingi klip tugashidan (kechikish bo'lsa) keyingi gap boshigacha
                val fromEff = maxOf(sStart, placer.cursorSec)
                val slot = ((sNext ?: duration) - fromEff).coerceAtLeast(0.3)
                val availSec = (slot - config.marginS).coerceAtLeast(0.3)
                var seg = synthSeg(u.uzText, slot)
                // Sig'masa: qisqaroq tarjima nomzodini sinaymiz (faqat foydalanuvchi matnni o'zgartirmagan bo'lsa)
                if (seg.durationSec > availSec * 1.12 && u.alts.size > 1 && u.alts[0] == u.uzText) {
                    for (alt in u.alts.drop(1).take(3)) {
                        val s2 = synthSeg(alt, slot)
                        if (s2.durationSec <= availSec * 1.12) {
                            seg = s2
                            usedText[u.id] = alt
                            Timber.i("Gap #${u.id}: qisqaroq variant tanlandi")
                            break
                        }
                    }
                }
                val resampled = StreamResampler.resample(seg.pcm, seg.sampleRate, OUT_SR)
                val pcm = if (config.levelClips) uz.dublaj.util.AudioLevel.level(resampled) else resampled
                placer.place(u.id, sStart, sNext, pcm)?.let { c ->
                    clips.add(c)
                    val placeAt = c.placeAtSample.toDouble() / OUT_SR
                    val lag = placeAt - sStart
                    lags.add(lag)
                    SyncReport.add(
                        context,
                        "#${u.id} s=${SyncReport.f(sStart)} slot=${SyncReport.f(slot)} tts=${SyncReport.f(seg.durationSec)} " +
                            "x${SyncReport.f(c.speed.toDouble())} dub=${SyncReport.f(placeAt)}-${SyncReport.f(placeAt + c.samples.toDouble() / OUT_SR)} " +
                            "kechikish=${SyncReport.sf(lag)}"
                    )
                }
            }
        } finally {
            vitsSynth?.close()
        }
        if (clips.isEmpty()) throw IllegalStateException("Ovoz hosil bo'lmadi (matn bo'sh yoki o'qib bo'lmadi)")

        progress(0.62f, "Asl audio tayyorlanmoqda…")
        if (!dir.mixWav.isFile) {
            AudioExtractor.extractToWav(video, dir.mixWav, OUT_SR) { p ->
                progress(0.62f + 0.1f * p, "Asl audio tayyorlanmoqda…")
            }
        }

        val outFile = File(dir.output, "dublaj_${System.currentTimeMillis()}.mp4")
        val totalSamples = (duration * OUT_SR).toLong()
        try {
            val origRanges = active.map { a ->
                Pair((maxOf(0.0, a.start - 0.05) * OUT_SR).toLong(), (a.end * OUT_SR).toLong())
            }
            DubMixer(OUT_SR, WavFile(dir.mixWav), clips, config.originalVolume, config.uzbekVolume, config.duckAmount, origRanges).use { mixer ->
                VideoMuxer.mux(video, outFile, OUT_SR, totalSamples, mixer) { p ->
                    progress(0.72f + 0.27f * p, "Video yig'ilmoqda…")
                }
            }
        } finally {
            clips.forEach { it.file.delete() }
            dir.mixWav.delete()
        }
        // Tarix uchun: metama'lumot va subtitrlar videoning yonida saqlanadi
        try {
            HistoryStore.writeMeta(outFile, title, duration, active.size)
            val finalUnits = if (usedText.isEmpty()) units else units.map { x ->
                val t = usedText[x.id]
                if (t != null) x.copy(uzText = t) else x
            }
            SrtExporter.write(ExportUtils.srtFile(outFile, true), finalUnits, true)
            SrtExporter.write(ExportUtils.srtFile(outFile, false), finalUnits, false)
        } catch (e: Exception) {
            Timber.w(e, "Tarix/SRT yozilmadi")
        }
        val fast = clips.count { it.speed > 1.2f }
        val notes = ArrayList<String>()
        if (fast > 0) notes.add("$fast ta gap tezlashtirilgan")
        if (fallbacks > 0) notes.add("$fallbacks ta gap offline ovozda")
        val maxLag = lags.maxOrNull() ?: 0.0
        SyncReport.add(
            context,
            "XULOSA: ${lags.size} gap, o'rtacha kechikish ${SyncReport.sf(if (lags.isEmpty()) 0.0 else lags.average())} s, eng katta ${SyncReport.sf(maxLag)} s, tezlashtirilgan(>1.2x) $fast"
        )
        if (maxLag > 1.0) notes.add("eng katta kechikish ${SyncReport.f(maxLag)} s")
        progress(1f, if (notes.isEmpty()) "Tayyor" else "Tayyor (${notes.joinToString(", ")})")
        return outFile
    }

    // ------------------------------------------------------------------ oddiy matn

    /** Videodan ajratilgan asl matn: vaqtlarsiz, bitta uzun matn (gaplar birlashtirilgan). */
    fun plainTranscript(context: Context): String {
        val dir = ensure(context)
        if (!dir.transcriptJson.isFile) return ""
        return try {
            SentenceMerger.merge(loadTranscript(context)).joinToString(" ") { it.text.trim() }.trim()
        } catch (e: Exception) {
            Timber.w(e, "plainTranscript")
            ""
        }
    }

    /** Ixtiyoriy o'zbekcha matnni ovozga aylantirib, WAV fayl qiladi (video vaqtlari yo'q). */
    fun synthesizeText(
        context: Context,
        text: String,
        config: AppConfig,
        onProgress: (Float, String) -> Unit
    ): File {
        val progress = wrap(onProgress)
        val dir = ensure(context)
        if (!VitsSynthesizer.isReady(dir)) {
            throw IllegalStateException("O'zbekcha ovoz komponenti yo'q. Sozlamalarda ovoz fayllarini (.onnx va .json) qo'shing.")
        }
        val pieces = text.split(Regex("\\n+"))
            .flatMap { par -> if (par.isBlank()) emptyList() else VitsSynthesizer.splitText(par, 220) }
            .filter { it.isNotBlank() }
        if (pieces.isEmpty()) throw IllegalStateException("Matn bo'sh")
        FileUtils.ensureSpace(dir.output, 50L * 1024 * 1024)

        progress(0.02f, "Ovoz komponenti yuklanmoqda…")
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
        val out = File(dir.output, "ovoz_${System.currentTimeMillis()}.wav")
        try {
            var writer: uz.dublaj.util.WavWriter? = null
            try {
                pieces.forEachIndexed { i, piece ->
                    progress(0.05f + 0.93f * i / pieces.size, "Ovozlashtirish ${i + 1}/${pieces.size}")
                    val seg = synth.synthesize(piece, null)
                    if (seg.pcm.isEmpty()) return@forEachIndexed
                    val pcm = if (config.levelClips) uz.dublaj.util.AudioLevel.level(seg.pcm) else seg.pcm
                    val w = writer ?: uz.dublaj.util.WavWriter(out, seg.sampleRate).also { writer = it }
                    w.write(pcm)
                    w.write(ShortArray((seg.sampleRate * config.voicePause.coerceAtLeast(0.15f)).toInt()))
                }
            } finally {
                writer?.close()
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            synth.close()
        }
        if (!out.isFile || out.length() < 1000) {
            out.delete()
            throw IllegalStateException("Ovoz hosil bo'lmadi (matn o'qib bo'lmadi)")
        }
        progress(1f, "Tayyor")
        return out
    }
}
