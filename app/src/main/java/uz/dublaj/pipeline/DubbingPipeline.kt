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
import uz.dublaj.translate.newTranslator
import uz.dublaj.translate.provider
import uz.dublaj.tts.ChatterboxClient
import uz.dublaj.tts.SegmentCache
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
        /** Whisper o'z oqimida ishlaydi; bekor qilinganda natija tashlab yuboriladi, tugagach xotira bo'shatiladi. */
        private val sttScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var sttBusy = false
    }

    @Volatile private var cancelled = false

    /** Oxirgi dublyaj hisoboti: gap id -> muammo matni (tezlashtirilgan / kechikkan gaplar). */
    @Volatile var lastIssues: Map<Int, String> = emptyMap()
        private set
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

    fun importFromTree(context: Context, tree: Uri, onProgress: (String) -> Unit): uz.dublaj.util.StorageScanner.ImportReport =
        uz.dublaj.util.StorageScanner.importFromTree(context, tree, ensure(context), onProgress)

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
        dir.translatePartial.delete()
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
        // Nutqsiz oraliqlarni qirqish — Whisper ishlash vaqtini qisqartiradi
        var sttInput: File = dir.extractedWav
        var trimMap: SilenceTrimmer.Mapping? = null
        val trimmedWav = File(dir.temp, "trimmed.wav")
        if (config.sttSkipSilence) {
            progress(0.42f, "Sukunat joylari qidirilmoqda…")
            try {
                val tr = SilenceTrimmer.trim(dir.extractedWav, trimmedWav)
                if (tr != null) {
                    sttInput = tr.file
                    trimMap = tr.mapping
                    progress(0.44f, "Sukunat qirqildi: ~${tr.savedPercent}% tezroq")
                }
            } catch (e: Exception) {
                Timber.w(e, "SilenceTrimmer ishlamadi")
                trimmedWav.delete()
            }
        }
        val recognizer = WhisperRecognizer(context.applicationContext)
        var detached = false
        val result = try {
            progress(0.45f, "Nutq tanish komponenti yuklanmoqda…")
            recognizer.ensureModel(modelFile)
            sttBusy = true
            val job = sttScope.async {
                try {
                    recognizer.transcribe(sttInput, config.sourceLang, config.sttThreads) { p, m ->
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
        val mapped: List<TranscriptUnit> = trimMap?.let { m ->
            result.units.map { u ->
                val s = m.toOrigStart(u.start)
                u.copy(start = s, end = maxOf(m.toOrigEnd(u.end), s + 0.1))
            }
        } ?: result.units
        trimmedWav.delete()
        val fixes = SttFixes.load(context, File(dir.root, "stt_fixes.json"))
        val fixedUnits: List<TranscriptUnit> = SttFixes.apply(mapped, fixes)
        val snapped: List<TranscriptUnit> = if (!config.snapSpeech) fixedUnits else try {
            SpeechSnap.refine(dir.extractedWav, fixedUnits)
        } catch (e: Exception) {
            Timber.w(e, "SpeechSnap ishlamadi")
            fixedUnits
        }
        // Gapirayotgan jinsini aniqlash uchun ton chastotasi (extracted.wav o'chirilishidan oldin)
        val units: List<TranscriptUnit> = try {
            PitchEstimator.annotate(dir.extractedWav, snapped)
        } catch (e: Exception) {
            Timber.w(e, "PitchEstimator ishlamadi")
            snapped
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
                put("f0", u.f0.toDouble())
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
                u.getString("text"), u.optString("language", "en"),
                u.optDouble("f0", 0.0).toFloat()
            )
        }
    }

    // ------------------------------------------------------------------ 2) tarjima (faqat Gemini — NLLB (CC-BY-NC) tufayli olib tashlandi)

    /**
     * [resume] = true: oldingi to'xtab qolgan tarjimaning tayyor qismi o'tkazib yuboriladi.
     * [onProblem]: kalit/limit/internet xatosida chaqiriladi (bloklaydi) va foydalanuvchi qarorini qaytaradi.
     */
    fun translate(
        context: Context,
        config: AppConfig,
        resume: Boolean = false,
        onProblem: ((String, Int, Int) -> uz.dublaj.data.TranslateDecision)? = null,
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
                "Tarjima uchun AI kaliti kerak. Sozlamalar → Tarjima (AI) bo'limida provayderni tanlab, kalitni kiriting."
            )
        }

        val norm = TextNormalizer.fromAssets(context.applicationContext)
        progress(0.01f, "Ovoz tezligi o'lchanmoqda…")
        val cps = uz.dublaj.tts.VoiceCalibration.charsPerSec(context, dir, config)
        val autoRoles: Map<Int, String> =
            if (config.autoVoiceRole) uz.dublaj.text.VoiceRoles.assign(src, config.baseVoiceFemale) else emptyMap()
        val result = ArrayList<TranslationUnit>()
        var failed = 0
        var flagged = 0
        var firstError: String? = null

        progress(0.02f, "${config.provider().label} bilan tarjima…")
        var cfgNow = config
        var gem = cfgNow.newTranslator()
        val online = HashMap<Int, GeminiTranslator.Result>()
        val sig = translateSig(src)
        if (resume) {
            online.putAll(loadPartial(dir, sig))
            if (online.isNotEmpty()) Timber.i("Tarjima davom ettirilmoqda: ${online.size}/${src.size} gap tayyor")
        }
        var skipRest = false
        var onlineNote: String? = null
        var idx = 0
        // Kalit limiti tugab, keyingisiga o'tilganda foydalanuvchiga ko'rsatamiz (dialog chiqmaydi)
        fun watchKeys(t: GeminiTranslator) {
            t.onKeySwitch = { n, total ->
                progress(0.05f + 0.85f * idx / src.size, "Limit tugadi, $n/$total-kalitga o'tildi")
            }
        }
        watchKeys(gem)
        try {
            while (idx < src.size) {
                checkCancelled()
                // bo'lak: ko'pi bilan 18 gap yoki ~3500 belgi
                val chunk = ArrayList<TranscriptUnit>()
                var chars = 0
                while (idx + chunk.size < src.size && chunk.size < config.aiChunkSize.coerceIn(2, 30) && (chunk.isEmpty() || chars < 3500)) {
                    val u = src[idx + chunk.size]
                    chunk.add(u)
                    chars += u.text.length
                }
                val items = chunk.mapIndexed { k, u ->
                    val nextStart = src.getOrNull(idx + k + 1)?.start
                    val slot = maxOf(u.end - u.start, (nextStart ?: (u.end + 1.5)) - u.start)
                    GeminiTranslator.Item(u.id, u.text, (slot * cps).toInt().coerceAtLeast(20))
                }.filter { it.id !in online }
                // Tayyor bo'lgan (davom ettirilayotgan) yoki o'tkazib yuboriladigan bo'laklar
                if (items.isEmpty() || skipRest) {
                    idx += chunk.size
                    continue
                }
                progress(0.05f + 0.85f * idx / src.size, "${cfgNow.provider().label} ${idx + 1}–${idx + chunk.size}/${src.size}")
                // oldingi 3 gapning tarjimasi — kontekst sifatida; keyingi gap — maslahat
                val ctxPairs = ArrayList<Pair<String, String>>()
                for (k in maxOf(0, idx - 3) until idx) {
                    val prev = online[src[k].id]
                    if (prev != null) ctxPairs.add(src[k].text to prev.uz)
                }
                val srcLang = chunk.firstOrNull()?.language ?: "en"
                val nextHint = src.getOrNull(idx + chunk.size)?.text
                var got: Map<Int, GeminiTranslator.Result>? = null
                while (got == null) {
                    checkCancelled()
                    try {
                        got = gem.translateChunk(
                            srcLang,
                            ctxPairs,
                            items,
                            nextHint,
                            GeminiTranslator.Style(cfgNow.videoContext, cfgNow.addressForm, cfgNow.tone, cfgNow.glossary, cfgNow.aiExtraPrompt, cfgNow.aiTemperature)
                        )
                    } catch (e: GeminiTranslator.GeminiException) {
                        Timber.w(e, "Gemini chunk xato")
                        if (firstError == null) firstError = e.message
                        // Kalit/limit/hisob/internet muammosi: jarayonni yo'qotmasdan foydalanuvchidan so'raymiz
                        val pausable = e.code != 0 || (e.message ?: "").startsWith("Internet")
                        if (onProblem != null && pausable) {
                            savePartial(dir, sig, online)
                            when (val d = onProblem(e.message ?: "Noma'lum xato", online.size, src.size)) {
                                is uz.dublaj.data.TranslateDecision.Retry -> {
                                    cfgNow = d.config
                                    GeminiTranslator.resetCooldowns()
                                    gem = cfgNow.newTranslator()
                                    watchKeys(gem)
                                    firstError = null
                                }
                                is uz.dublaj.data.TranslateDecision.Skip -> {
                                    skipRest = true
                                    got = emptyMap()
                                }
                                is uz.dublaj.data.TranslateDecision.Stop -> {
                                    checkCancelled()
                                    throw uz.dublaj.data.TranslationPaused(online.size, src.size, e.message ?: "")
                                }
                            }
                        } else {
                            got = emptyMap()
                        }
                    }
                }
                online.putAll(got ?: emptyMap())
                savePartial(dir, sig, online)
                idx += chunk.size
            }
        } catch (e: uz.dublaj.data.TranslationPaused) {
            throw e
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
                // Avval xom tarjimani, keyin normalizatsiyadan keyin tekshiramiz (raqam→so'z)
                val rawUz = g.uz.trim()
                val uz = norm.normalize(rawUz)
                val qa = TranslationQa.issue(u.text, rawUz) ?: TranslationQa.issue(u.text, uz)
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
                // QA: asl AI matn + normalizatsiyadan keyin (raqam so'zga aylanganini hisobga oladi)
                result.add(TranslationUnit(u.id, u.start, u.end, u.text, uz, u.language, qa != null, alts, qa, autoRoles[u.id] ?: ""))
            } else {
                failed++
                if (firstError == null) firstError = "AI javob bermadi"
                result.add(TranslationUnit(u.id, u.start, u.end, u.text, u.text, u.language, true, emptyList(), "AI javob bermadi", autoRoles[u.id] ?: ""))
            }
        }

        if (failed == src.size) {
            throw IllegalStateException("Hech bir segment tarjima qilinmadi: $firstError")
        }

        dir.approvedFlag.delete()
        saveTranslation(context, result)
        dir.translatePartial.delete()
        val done = "Tarjima tayyor (${config.provider().label}: ${online.size}/${src.size})"
        progress(1f, if (failed + flagged > 0) "Tayyor (${failed + flagged} ta gap tekshirilsin)" else done)
        return result
    }


    // ------------------------------------------------------------------ to'xtab qolgan tarjimani davom ettirish

    private fun translateSig(src: List<TranscriptUnit>): String =
        "${src.size}:${src.joinToString("|") { it.text }.hashCode()}"

    private fun savePartial(dir: WorkDir, sig: String, online: Map<Int, GeminiTranslator.Result>) {
        try {
            val arr = JSONArray()
            for ((id, r) in online) {
                arr.put(JSONObject().apply {
                    put("id", id); put("uz", r.uz)
                    if (r.short != null) put("short", r.short)
                })
            }
            dir.translatePartial.writeText(JSONObject().put("sig", sig).put("items", arr).toString())
        } catch (e: Exception) {
            Timber.w(e, "Tarjima qismi saqlanmadi")
        }
    }

    private fun loadPartial(dir: WorkDir, sig: String): Map<Int, GeminiTranslator.Result> {
        val f = dir.translatePartial
        if (!f.isFile) return emptyMap()
        return try {
            val j = JSONObject(f.readText())
            if (j.optString("sig") != sig) return emptyMap()
            val arr = j.getJSONArray("items")
            val out = HashMap<Int, GeminiTranslator.Result>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uz = o.optString("uz", "")
                if (uz.isBlank()) continue
                out[o.getInt("id")] = GeminiTranslator.Result(uz, o.optString("short", "").ifEmpty { null })
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** To'xtab qolgan tarjima bo'lsa (tayyor, jami) gap soni; yo'q bo'lsa null. */
    fun translatePartialInfo(context: Context): Pair<Int, Int>? {
        return try {
            val dir = ensure(context)
            val src = SentenceMerger.merge(loadTranscript(context))
            val done = loadPartial(dir, translateSig(src)).size
            if (done > 0 && done < src.size) done to src.size else null
        } catch (e: Exception) {
            null
        }
    }

    /** Asl nutq ton balandligidan avtomatik aniqlangan erkak/ayol izohlari (id → rol). */
    fun autoRolesFor(context: Context, config: AppConfig): Map<Int, String> {
        val src = SentenceMerger.merge(loadTranscript(context))
        return uz.dublaj.text.VoiceRoles.assign(src, config.baseVoiceFemale)
    }

    fun saveTranslation(context: Context, units: List<TranslationUnit>) {
        val dir = ensure(context)
        val arr = JSONArray()
        for (u in units) {
            arr.put(JSONObject().apply {
                put("id", u.id); put("start", u.start); put("end", u.end)
                put("text", u.text); put("uzText", u.uzText)
                put("language", u.language); put("check", u.check)
                if (u.issue != null) put("issue", u.issue)
                put("voiceRole", u.voiceRole)
                put("hasUserVoice", u.hasUserVoice)
                if (u.alts.isNotEmpty()) put("alts", JSONArray(u.alts))
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
                    u.optJSONArray("alts")?.let { arr -> (0 until arr.length()).map { k -> arr.getString(k) } } ?: emptyList(),
                    if (u.has("issue") && !u.isNull("issue")) u.getString("issue") else null,
                    u.optString("voiceRole", ""),
                    u.optBoolean("hasUserVoice", false)
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

    /**
     * Bitta gapni qayta tarjima qiladi (tahrir ekrani).
     * Mavjud unitlar ro'yxatini yangilab qaytaradi.
     */
    fun translateSingle(
        context: Context,
        config: AppConfig,
        units: List<TranslationUnit>,
        index: Int
    ): List<TranslationUnit> {
        if (index !in units.indices) return units
        if (!config.onlineReady) throw IllegalStateException(
            "Tarjima uchun AI kaliti kerak. Sozlamalar → Tarjima (AI) bo'limida kalitni kiriting."
        )
        val u = units[index]
        val norm = TextNormalizer.fromAssets(context.applicationContext)
        val cps = uz.dublaj.tts.VoiceCalibration.charsPerSec(context, ensure(context), config)
        val gem = config.newTranslator()
        val slot = (u.end - u.start).coerceAtLeast(0.5)
        val item = GeminiTranslator.Item(
            u.id, u.text, (slot * cps).toInt().coerceAtLeast(20)
        )
        val ctxPairs = ArrayList<Pair<String, String>>()
        for (k in maxOf(0, index - 3) until index) {
            val p = units[k]
            if (p.uzText.isNotBlank()) ctxPairs.add(p.text to p.uzText)
        }
        val nextHint = units.getOrNull(index + 1)?.text
        val got = gem.translateChunk(
            u.language.ifBlank { "en" },
            ctxPairs,
            listOf(item),
            nextHint,
            GeminiTranslator.Style(
                config.videoContext, config.addressForm, config.tone,
                config.glossary, config.aiExtraPrompt, config.aiTemperature
            )
        )
        val g = got[u.id] ?: throw IllegalStateException("AI bu gapga javob bermadi. Qayta urinib ko'ring.")
        val rawUz = g.uz.trim()
        val uz = norm.normalize(rawUz)
        val qa = TranslationQa.issue(u.text, rawUz) ?: TranslationQa.issue(u.text, uz)
        val alts = ArrayList<String>()
        alts.add(uz)
        g.short?.let { s ->
            val n = norm.normalize(s)
            if (n.isNotBlank() && n !in alts) alts.add(n)
        }
        val updated = units.toMutableList()
        updated[index] = u.copy(
            uzText = uz, check = qa != null, issue = qa, alts = alts
        )
        saveTranslation(context, updated)
        return updated
    }


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
        val outSr = config.outSampleRate
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

        FileUtils.ensureSpace(dir.temp, (duration * outSr * 2 * 2).toLong() + 100L * 1024 * 1024)
        FileUtils.ensureSpace(dir.output, video.length() + 50L * 1024 * 1024)

        val norm = TextNormalizer.fromAssets(context.applicationContext)
        val placer = SegmentPlacer(
            totalSec = duration, sr = outSr,
            maxSpeed = config.maxAtempo,
            marginSec = config.marginS.toDouble(),
            gapSec = config.gapS.toDouble(),
            clipDir = dir.temp
        )
        val clips = ArrayList<SegmentPlacer.Clip>()
        val usedText = HashMap<Int, String>()
        val lags = ArrayList<Double>()
        val dubIssues = LinkedHashMap<Int, String>()
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
        // ---- kesh: matni, roli, sozlamalari va vaqti o'zgarmagan gaplar qayta ovozlanmaydi
        val (vitsModel, vitsCfg) = VitsSynthesizer.modelFiles(dir)
        val baseScale = 1f / config.voiceSpeed.coerceAtLeast(0.3f)
        val canCompress = config.fitScale < baseScale
        val vitsKey = listOf(
            "v1", uz.dublaj.BuildConfig.VERSION_CODE, "vits",
            SegmentCache.fileSig(vitsModel), SegmentCache.fileSig(vitsCfg),
            config.voiceNoise, config.voiceNoiseW, config.fitScale, config.voicePause, config.voiceSpeed,
            config.pronDict.hashCode()
        ).joinToString("|")
        val cbKey = listOf(
            "v1", uz.dublaj.BuildConfig.VERSION_CODE, "cb", config.cbUrl, config.cbVoiceMode, SegmentCache.fileSig(cbRef)
        ).joinToString("|")
        val vitsCache = SegmentCache(dir.ttsCache, vitsKey)
        val cbCache = SegmentCache(dir.ttsCache, cbKey)
        var cacheHits = 0
        var curPitch = 0f
        fun synthSeg(text: String, slot: Double): VitsSynthesizer.SegmentAudio {
            if (cb != null && !cbOff) {
                cbCache.get(text, 0f, slot, slotDependent = false, canCompress = false)?.let { cacheHits++; return it }
                try {
                    val r = cb.synthesize(text, cbRef)
                    cbFails = 0
                    cbCache.put(text, 0f, slot, r, compressed = false)
                    return r
                } catch (e: ChatterboxClient.ChatterboxException) {
                    cbFails++
                    Timber.w("Chatterbox xato ($cbFails): ${e.message}")
                    if (!vitsOk) throw IllegalStateException("Chatterbox serveri: ${e.message}")
                    if (cbFails >= 3) cbOff = true
                    fallbacks++
                }
            }
            vitsCache.get(text, curPitch, slot, slotDependent = true, canCompress = canCompress)?.let { cacheHits++; return it }
            val synth = vits()
            synth.pitchSemitones = curPitch
            val r = synth.synthesize(text, slot)
            vitsCache.put(text, curPitch, slot, r, compressed = r.lengthScale < baseScale - 1e-4f)
            return r
        }
        try {
            active.forEachIndexed { i, u ->
                progress(0.05f + 0.55f * i / active.size, "Ovozlashtirish ${i + 1}/${active.size}" + if (cacheHits > 0) " (tayyor: $cacheHits)" else "")
                val sStart = u.start + off
                val sNext = active.getOrNull(i + 1)?.start?.plus(off)
                // Haqiqiy bo'sh vaqt: oldingi klip tugashidan (kechikish bo'lsa) keyingi gap boshigacha
                val fromEff = maxOf(sStart, placer.cursorSec)
                val slot = ((sNext ?: duration) - fromEff).coerceAtLeast(0.3)
                val availSec = (slot - config.marginS).coerceAtLeast(0.3)
                // Foydalanuvchi o'z ovozini yozgan bo'lsa — shu ishlatiladi; aks holda ONNX/VITS
                var seg: VitsSynthesizer.SegmentAudio
                val userWav = dir.segmentVoice(u.id)
                if (u.hasUserVoice && userWav.isFile && userWav.length() > 44) {
                    val (pcmU, srU) = uz.dublaj.util.SegmentRecorder.readWavPcm(userWav)
                    seg = VitsSynthesizer.SegmentAudio(pcmU, srU, 1f)
                    Timber.i("Gap #${u.id}: foydalanuvchi ovozi")
                } else {
                    // Erkak/ayol/bola izohi → pitch (taxminiy, alohida model emas)
                    curPitch = uz.dublaj.text.VoiceRoles.pitchFor(u.voiceRole, config)
                    seg = synthSeg(u.uzText, slot)
                }
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
                val resampled = StreamResampler.resample(seg.pcm, seg.sampleRate, outSr)
                val pcm = if (config.levelClips) uz.dublaj.util.AudioLevel.level(resampled) else resampled
                placer.place(u.id, sStart, sNext, pcm)?.let { c ->
                    clips.add(c)
                    val placeAt = c.placeAtSample.toDouble() / outSr
                    val lag = placeAt - sStart
                    lags.add(lag)
                    val problems = ArrayList<String>()
                    if (c.speed > 1.2f) problems.add("tezlashtirilgan ${SyncReport.f(c.speed.toDouble())}×")
                    if (lag > 0.8) problems.add("${SyncReport.f(lag)} s kechikkan")
                    if (problems.isNotEmpty()) {
                        dubIssues[u.id] = uz.dublaj.data.DUB_ISSUE_PREFIX + problems.joinToString(", ") + " — matnni qisqartirsangiz tabiiyroq chiqadi"
                    }
                    SyncReport.add(
                        context,
                        "#${u.id} s=${SyncReport.f(sStart)} slot=${SyncReport.f(slot)} tts=${SyncReport.f(seg.durationSec)} " +
                            "x${SyncReport.f(c.speed.toDouble())} dub=${SyncReport.f(placeAt)}-${SyncReport.f(placeAt + c.samples.toDouble() / outSr)} " +
                            "kechikish=${SyncReport.sf(lag)}"
                    )
                }
            }
        } finally {
            vitsSynth?.close()
            try { vitsCache.prune() } catch (_: Exception) {}
        }
        if (clips.isEmpty()) throw IllegalStateException("Ovoz hosil bo'lmadi (matn bo'sh yoki o'qib bo'lmadi)")

        progress(0.62f, "Asl audio tayyorlanmoqda…")
        dir.mixWav.delete()   // boshqa chastotadagi eski nusxa qolmasin
        run {
            AudioExtractor.extractToWav(video, dir.mixWav, outSr) { p ->
                progress(0.62f + 0.1f * p, "Asl audio tayyorlanmoqda…")
            }
        }

        val outFile = File(dir.output, "dublaj_${System.currentTimeMillis()}.mp4")
        val totalSamples = (duration * outSr).toLong()
        try {
            val origRanges = active.map { a ->
                Pair((maxOf(0.0, a.start - 0.05) * outSr).toLong(), (a.end * outSr).toLong())
            }
            DubMixer(outSr, WavFile(dir.mixWav), clips, config.originalVolume, config.uzbekVolume, config.duckAmount, origRanges).use { mixer ->
                VideoMuxer.mux(video, outFile, outSr, totalSamples, mixer, config.outAudioBitrate) { p ->
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
        if (cacheHits > 0) notes.add("$cacheHits ta gap qayta ovozlanmadi")
        SyncReport.add(context, "KESH: $cacheHits/${active.size} gap tayyor ovozdan olindi")
        val maxLag = lags.maxOrNull() ?: 0.0
        SyncReport.add(
            context,
            "XULOSA: ${lags.size} gap, o'rtacha kechikish ${SyncReport.sf(if (lags.isEmpty()) 0.0 else lags.average())} s, eng katta ${SyncReport.sf(maxLag)} s, tezlashtirilgan(>1.2x) $fast"
        )
        if (maxLag > 1.0) notes.add("eng katta kechikish ${SyncReport.f(maxLag)} s")
        lastIssues = dubIssues
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
