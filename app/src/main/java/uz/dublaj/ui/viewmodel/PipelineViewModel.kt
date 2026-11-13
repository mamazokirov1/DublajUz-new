package uz.dublaj.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import uz.dublaj.data.AppConfig
import uz.dublaj.translate.activeKey
import uz.dublaj.translate.newTranslator
import uz.dublaj.translate.withAddedKey
import uz.dublaj.translate.provider
import uz.dublaj.data.ConfigStore
import uz.dublaj.data.VoicePreset
import uz.dublaj.data.applyPreset
import uz.dublaj.tts.VitsSynthesizer
import uz.dublaj.util.AudioLevel
import uz.dublaj.util.PcmPlayer
import uz.dublaj.data.ModelsInfo
import uz.dublaj.data.PipelineState
import uz.dublaj.data.TranslationUnit
import uz.dublaj.data.UserCancelled
import uz.dublaj.data.TranslateDecision
import uz.dublaj.data.TranslatePause
import uz.dublaj.data.TranslationPaused
import uz.dublaj.pipeline.DubbingPipeline
import uz.dublaj.service.PipelineService
import uz.dublaj.util.FileUtils
import uz.dublaj.util.OutputUtils
import uz.dublaj.util.UpdateChecker
import uz.dublaj.data.QueueItem
import uz.dublaj.data.QueueStatus
import uz.dublaj.data.UsageStore
import uz.dublaj.monetization.BillingManager
import uz.dublaj.monetization.AdsManager
import kotlinx.coroutines.flow.update
import java.io.File

/** Yagona (Activity darajasidagi) ViewModel: barcha ekranlar shu nusxani ulashadi. */
class PipelineViewModel : ViewModel() {

    private val pipeline = DubbingPipeline()
    private var job: Job? = null
    private var restored = false

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    private val _hasTranscript = MutableStateFlow(false)
    val hasTranscript: StateFlow<Boolean> = _hasTranscript.asStateFlow()

    private val _translationUnits = MutableStateFlow<List<TranslationUnit>>(emptyList())
    val translationUnits: StateFlow<List<TranslationUnit>> = _translationUnits.asStateFlow()

    private val _approved = MutableStateFlow(false)
    val approved: StateFlow<Boolean> = _approved.asStateFlow()

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val _models = MutableStateFlow(ModelsInfo())
    val models: StateFlow<ModelsInfo> = _models.asStateFlow()

    /** Model yuklash/import holati (matn); null = hech narsa ketayotgani yo'q. */
    private val _modelStatus = MutableStateFlow<String?>(null)
    val modelStatus: StateFlow<String?> = _modelStatus.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    // ---- ekranlar uchun kuzatiladigan holatlar
    private val _busy = MutableStateFlow(false)
    /** Ish ketyaptimi (Compose'da kuzatiladi; isBusy esa oddiy tekshiruv). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _cancelling = MutableStateFlow(false)
    /** "Bekor qilish" bosildi, ish hozir to'xtayapti. */
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Foydalanuvchiga qisqa xabarlar (Snackbar). */
    val uiEvents: SharedFlow<String> = _events.asSharedFlow()
    fun toast(msg: String) { _events.tryEmit(msg) }

    private val _workVersion = MutableStateFlow(0)
    /** Vaqtinchalik fayllar o'zgarganda oshadi — Xotira kartasi qayta o'qiydi. */
    val workVersion: StateFlow<Int> = _workVersion.asStateFlow()

    private val _playerUri = MutableStateFlow<Uri?>(null)
    val playerUri: StateFlow<Uri?> = _playerUri.asStateFlow()
    fun openPlayer(uri: Uri) { _playerUri.value = uri }

    private val _audioResult = MutableStateFlow<File?>(null)
    /** Oxirgi "matn → ovoz" natijasi (WAV). */
    val audioResult: StateFlow<File?> = _audioResult.asStateFlow()
    fun clearAudioResult() { _audioResult.value = null }

    private val _plainOriginal = MutableStateFlow("")
    val plainOriginal: StateFlow<String> = _plainOriginal.asStateFlow()

    /** Videodan ajratilgan matnni (vaqtlarsiz) diskdan o'qiydi. */
    fun refreshPlainTexts(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            _plainOriginal.value = withContext(Dispatchers.IO) { pipeline.plainTranscript(app) }
        }
    }

    /** Tarjima qilingan (tahrir qilingan) o'zbekcha matn, bitta uzun matn sifatida. */
    fun plainUzbek(): String =
        _translationUnits.value.map { it.uzText.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** Istalgan o'zbekcha matnni ovozga aylantirib, WAV fayl qiladi. */
    /** Home'dagi "Matn → ovoz" tugmasi shu yorliqni qo'yadi; TextToolsScreen boshlang'ich yorliq sifatida oladi. */
    private var pendingTextTab = 0
    fun requestTextTab(tab: Int) { pendingTextTab = tab }
    fun consumeTextTab(): Int = pendingTextTab.also { pendingTextTab = 0 }

    fun textLimit(): Int =
        if (_config.value.isPro) uz.dublaj.data.UsageStore.PRO_TEXT_CHARS else uz.dublaj.data.UsageStore.FREE_TEXT_CHARS

    /** .txt faylni o'qiydi (UTF-8; BOM olib tashlanadi). Natija [onLoaded] ga beriladi. */
    fun loadTextFile(context: Context, uri: Uri, onLoaded: (String) -> Unit) {
        val app = context.applicationContext
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { ins ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 4 * 1024 * 1024) throw IllegalStateException("Fayl juda katta (4 MB gacha bo'lsin)")
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } ?: throw IllegalStateException("Fayl ochilmadi")
                    var s = String(bytes, Charsets.UTF_8)
                    if (s.startsWith("\uFEFF")) s = s.substring(1)
                    Result.success(s.replace("\r\n", "\n").trim())
                } catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess {
                if (it.isBlank()) toast("Fayl bo'sh") else {
                    onLoaded(it)
                    toast("Fayl yuklandi: ${it.length} belgi")
                }
            }.onFailure { toast("Fayl o'qilmadi: ${it.message}") }
        }
    }

    fun speak(context: Context, rawText: String) {
        val app = context.applicationContext
        val limit = textLimit()
        val text = if (rawText.length > limit) {
            toast(
                if (_config.value.isPro) "Matn ${limit} belgigacha qisqartirildi"
                else "Bepul versiyada $limit belgigacha o'qiladi. Pro bilan ${uz.dublaj.data.UsageStore.PRO_TEXT_CHARS} belgigacha."
            )
            rawText.take(limit)
        } else rawText
        if (text.isBlank()) { toast("Avval matn kiriting"); return }
        if (UsageStore.canDub(app, _config.value.isPro) != null) { showLimitPrompt(); return }
        if (isBusy) { toast("Hozir boshqa jarayon ketyapti. Tugashini kuting yoki bekor qiling."); return }
        if (!_models.value.vits) { toast("Avval Sozlamalarda o'zbekcha ovoz fayllarini qo'shing"); return }
        releasePreview()
        _audioResult.value = null
        launchTask(context, "Matnni ovozlashtirish") {
            val cfg = _config.value
            val f = withContext(Dispatchers.IO) {
                pipeline.synthesizeText(app, text, cfg) { p, m ->
                    _state.value = PipelineState.Running("Matnni ovozlashtirish", p, m)
                }
            }
            _audioResult.value = f
            _state.value = PipelineState.Idle
            if (!cfg.isPro) UsageStore.recordWork(app)
            if (cfg.autoSaveAudio && cfg.isPro) {
                try { withContext(Dispatchers.IO) { uz.dublaj.util.OutputUtils.saveAudioToMusic(app, f) } } catch (_: Exception) { }
            }
            toast("Audio tayyor ✓")
            PipelineService.notifyDone(app, "Audio tayyor", "Matn o'zbekcha ovozga aylantirildi")
        }
    }

    // ------------------------------------------------------------------ holat

    private val proListener: (Boolean) -> Unit = { pro ->
        _config.value = _config.value.copy(isPro = pro)
        if (pro) toast("Pro faollashtirildi ✓")
    }

    private var adShownFor: String? = null
    /** Bitta natija uchun reklama faqat bir marta ko'rsatiladi (ekranga qaytganda takrorlanmasin). */
    fun markAdShown(path: String): Boolean {
        if (adShownFor == path) return false
        adShownFor = path
        return true
    }

    fun restore(context: Context) {
        if (restored) return
        restored = true
        val app = context.applicationContext
        appCtx = app
        _config.value = ConfigStore.load(app)
        // Play xarididan keyin UI ni yangilash
        BillingManager.addProListener(proListener)
        BillingManager.refreshPurchases(app)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { pipeline.restore(app) }
            if (_videoUri.value == null && r.videoFile != null) _videoUri.value = Uri.fromFile(r.videoFile)
            _hasTranscript.value = r.hasTranscript
            _translationUnits.value = r.units
            _approved.value = r.approved
            val partial = withContext(Dispatchers.IO) { pipeline.translatePartialInfo(app) }
            _translateResume.value = if (r.units.isEmpty()) partial?.let { "${it.first}/${it.second}" } else null
        }
        // Bildirishnomadagi «Bekor qilish» tugmasi
        PipelineService.onCancelRequested = { cancel() }
        viewModelScope.launch {
            _models.value = withContext(Dispatchers.IO) { pipeline.modelsInfo(app, _config.value) }
        }
    }

    fun refreshModels(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            _models.value = withContext(Dispatchers.IO) { pipeline.modelsInfo(app, _config.value) }
        }
    }

    fun setConfig(c: AppConfig, context: Context? = null) {
        val modelChanged = c.whisperModel != _config.value.whisperModel
        _config.value = c
        (context?.applicationContext ?: appCtx)?.let { ConfigStore.save(it, c) }
        if (modelChanged && context != null) refreshModels(context)
    }

    fun applyVoicePreset(p: VoicePreset) = setConfig(_config.value.applyPreset(p))

    // ------------------------------------------------------------------ ovozni tinglab ko'rish

    private var appCtx: Context? = null
    private var previewSynth: VitsSynthesizer? = null
    private var previewJob: Job? = null
    private val _preview = MutableStateFlow<String?>(null)
    val previewState: StateFlow<String?> = _preview.asStateFlow()
    private val _previewUnit = MutableStateFlow(-1)
    /** Hozir tayyorlanayotgan yoki chalinayotgan gap id si (-1 = hech biri). */
    val previewUnit: StateFlow<Int> = _previewUnit.asStateFlow()

    /**
     * Tahrir ekranidagi ▶: gapni aynan dublyajdagidek (rol + vaqt oralig'iga moslab) chaladi.
     * Shu gap chalinayotgan bo'lsa — to'xtatadi.
     */
    fun togglePreview(context: Context, unitId: Int, text: String, role: String, slotSec: Double) {
        if (_previewUnit.value == unitId && previewJob?.isActive == true) {
            if (_preview.value?.startsWith("Ijro") == true) {
                PcmPlayer.stop()
                previewJob?.cancel()
                _previewUnit.value = -1
                _preview.value = null
            }
            return
        }
        previewVoice(context, text, role, slotSec, unitId)
    }

    /** Joriy sozlamalar bilan matnni sintez qilib chaladi. Dublyaj paytida o'chiq (xotira). */
    fun previewVoice(context: Context, text: String, role: String = "", slotSec: Double? = null, unitId: Int = -1) {
        val app = context.applicationContext
        if (isBusy) {
            _preview.value = "Dublyaj/tarjima ishlayotganda tinglab bo'lmaydi"
            toast("Dublyaj/tarjima ishlayotganda tinglab bo'lmaydi")
            return
        }
        if (text.isBlank()) return
        if (previewJob?.isActive == true && _preview.value?.startsWith("Ovoz tayyorlanmoqda") == true) return
        PcmPlayer.stop()
        previewJob?.cancel()
        _previewUnit.value = unitId
        previewJob = viewModelScope.launch {
            try {
                _preview.value = "Ovoz tayyorlanmoqda…"
                val cfg = _config.value
                val (pcm, sr) = withContext(Dispatchers.Default) {
                    val synth = previewSynth ?: pipeline.loadPreviewSynth(app, cfg).also { previewSynth = it }
                    synth.configure(
                        cfg.voiceNoise, cfg.voiceNoiseW,
                        1f / cfg.voiceSpeed.coerceAtLeast(0.3f), cfg.fitScale, cfg.voicePause,
                        uz.dublaj.text.VoiceRoles.pitchFor(role, cfg)
                    )
                    synth.setUserPronunciations(cfg.pronDict)
                    val seg = synth.synthesize(text, slotSec)
                    (if (cfg.levelClips) AudioLevel.level(seg.pcm) else seg.pcm) to seg.sampleRate
                }
                if (pcm.isEmpty()) {
                    _preview.value = "Matn bo'sh yoki o'qib bo'lmadi"
                    return@launch
                }
                _preview.value = "Ijro etilmoqda…"
                PcmPlayer.play(pcm, sr)
                _preview.value = null
            } catch (e: CancellationException) {
                _preview.value = null
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Preview xato")
                _preview.value = "Xato: ${e.message ?: e.javaClass.simpleName}"
                toast("Tinglab bo'lmadi: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (_previewUnit.value == unitId) _previewUnit.value = -1
            }
        }
    }

    fun stopPreview() {
        PcmPlayer.stop()
        _previewUnit.value = -1
        if (_preview.value?.startsWith("Ijro") == true) _preview.value = null
    }

    private fun releasePreview() {
        PcmPlayer.stop()
        previewJob?.cancel()
        previewSynth?.close()
        previewSynth = null
        _preview.value = null
    }

    override fun onCleared() {
        pauseWait?.complete(TranslateDecision.Stop)
        PipelineService.onCancelRequested = null
        BillingManager.removeProListener(proListener)
        releasePreview()
        super.onCleared()
    }

    private val _proPrompts = MutableSharedFlow<uz.dublaj.data.ProPrompt>(extraBufferCapacity = 4)
    /** Pro'ga chorlovchi dialoglar (video uzun / limit tugagan). Barcha ekranlarda ko'rsatiladi. */
    val proPrompts: SharedFlow<uz.dublaj.data.ProPrompt> = _proPrompts.asSharedFlow()
    fun showLimitPrompt() { _proPrompts.tryEmit(uz.dublaj.data.ProPrompt.LimitReached) }

    /** Video davomiyligi (ms); o'qib bo'lmasa null. */
    private fun videoDurationMs(app: Context, uri: Uri): Long? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(app, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    fun setVideo(uri: Uri) {
        if (isBusy) return
        _videoUri.value = uri
        _hasTranscript.value = false
        undoStack.clear()
        _translationUnits.value = emptyList()
        _approved.value = false
        _state.value = PipelineState.Idle
        // Bepul versiyada bitta video 15 daqiqadan oshmasligi kerak
        val app = appCtx
        if (app != null && !_config.value.isPro) {
            viewModelScope.launch {
                val ms = withContext(Dispatchers.IO) { videoDurationMs(app, uri) }
                if (ms != null && ms > UsageStore.FREE_MAX_VIDEO_MINUTES * 60_000L && _videoUri.value == uri) {
                    _videoUri.value = null
                    _proPrompts.tryEmit(uz.dublaj.data.ProPrompt.TooLong(kotlin.math.ceil(ms / 60000.0).toInt()))
                }
            }
        }
    }

    fun resetState() {
        if (!isBusy) _state.value = PipelineState.Idle
    }

    fun cancel() {
        if (!isBusy) return
        if (_cancelling.value) {
            toast("Bekor qilinmoqda… biroz kuting")
            return
        }
        pipeline.cancel()
        pauseWait?.complete(TranslateDecision.Stop)
        _cancelling.value = true
        _state.update { if (it is PipelineState.Running) it.copy(message = "Bekor qilinmoqda…") else it }
        if (_modelStatus.value != null) _modelStatus.value = "Bekor qilinmoqda…"
        toast("Bekor qilinmoqda… hozirgi qadam tugashi bilan to'xtaydi")
    }

    // ------------------------------------------------------------------ bosqichlar

    fun transcribe(context: Context) {
        val uri = _videoUri.value ?: return
        launchTask(context, "Matn ajratish") {
            val cfg = _config.value
            withContext(Dispatchers.IO) {
                pipeline.transcribe(context.applicationContext, uri, cfg) { p, m ->
                    _state.value = PipelineState.Running("Matn ajratish", p, m)
                }
            }
            _hasTranscript.value = true
            _translationUnits.value = emptyList()
            _approved.value = false
            _state.value = PipelineState.Idle
        }
    }

    fun translate(context: Context, resume: Boolean = false) {
        launchTask(context, "Tarjima") {
            val cfg = _config.value
            val app = context.applicationContext
            val units = withContext(Dispatchers.IO) {
                pipeline.translate(app, cfg, resume, { m, d, t -> askTranslateDecision(app, m, d, t) }) { p, m ->
                    _state.value = PipelineState.Running("Tarjima", p, m)
                }
            }
            undoStack.clear()
            _translationUnits.value = units
            _approved.value = false
            _translateResume.value = null
            _state.value = PipelineState.Idle
        }
    }

    // ------------------------------------------------------------------ tarjima to'xtab qolsa: kalit almashtirish / davom etish

    private val _translatePause = MutableStateFlow<TranslatePause?>(null)
    /** Tarjima kalit/limit/internet muammosi tufayli kutayotganda to'ladi (dialog ko'rsatiladi). */
    val translatePause: StateFlow<TranslatePause?> = _translatePause.asStateFlow()
    private val _translateResume = MutableStateFlow<String?>(null)
    /** To'xtatilgan tarjimaning tayyor qismi, masalan "40/120"; yo'q bo'lsa null. */
    val translateResume: StateFlow<String?> = _translateResume.asStateFlow()
    private var pauseWait: kotlinx.coroutines.CompletableDeferred<TranslateDecision>? = null

    /** IO oqimida chaqiriladi: foydalanuvchi qaror qilguncha kutadi (jarayon yo'qolmaydi). */
    private fun askTranslateDecision(app: Context, message: String, done: Int, total: Int): TranslateDecision {
        val wait = kotlinx.coroutines.CompletableDeferred<TranslateDecision>()
        pauseWait = wait
        _translatePause.value = TranslatePause(message, done, total)
        PipelineService.notifyDone(app, "Tarjima to'xtab qoldi", "$done/$total gap tayyor. Kalitni almashtiring yoki qayta urining.")
        val decision = try {
            kotlinx.coroutines.runBlocking { wait.await() }
        } catch (e: Exception) {
            TranslateDecision.Stop
        }
        _translatePause.value = null
        pauseWait = null
        return decision
    }

    /** Yangi kalit (bo'sh bo'lsa — eskisi) bilan aynan to'xtagan joydan davom etish. */
    fun continueTranslation(newKey: String?) {
        val wait = pauseWait ?: return
        var cfg = _config.value
        val key = newKey?.trim().orEmpty()
        if (key.isNotEmpty()) {
            cfg = cfg.withAddedKey(key)
            setConfig(cfg)
        }
        wait.complete(TranslateDecision.Retry(cfg))
    }

    /** Qolgan gaplarni tarjimasiz qoldirib, jarayonni yakunlash. */
    fun skipRestOfTranslation() { pauseWait?.complete(TranslateDecision.Skip) }

    /** Hozircha to'xtatish: tayyor qismi saqlanadi, keyin «Davom etish» bilan davom ettiriladi. */
    fun pauseTranslation() { pauseWait?.complete(TranslateDecision.Stop) }

    fun updateUzText(index: Int, newText: String) {
        val list = _translationUnits.value.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(uzText = newText, check = false, issue = null)
        _translationUnits.value = list
        _approved.value = false
    }

    // ------------------------------------------------------------------ bekor qilish (Undo)

    private class UndoEntry(
        val label: String,
        val roles: Map<Int, String>?,
        val speakers: Map<Int, Int>?,
        val texts: Map<Int, TranslationUnit>?
    )
    private val undoStack = ArrayList<UndoEntry>()
    private val _undoOffers = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Ommaviy o'zgarishdan keyin chiqadi: ekran «Qaytarish» tugmali xabar ko'rsatadi. */
    val undoOffers: SharedFlow<String> = _undoOffers.asSharedFlow()

    /** O'zgarishdan OLDIN chaqiriladi: faqat o'zgaradigan maydonlar eslab qolinadi (keyingi tahrirlar buzilmaydi). */
    private fun pushUndo(label: String, roles: Boolean = false, speakers: Boolean = false, oldTexts: List<TranslationUnit>? = null) {
        val cur = _translationUnits.value
        undoStack.add(
            UndoEntry(
                label,
                if (roles) cur.associate { it.id to it.voiceRole } else null,
                if (speakers) cur.associate { it.id to it.speaker } else null,
                oldTexts?.associateBy { it.id }
            )
        )
        while (undoStack.size > 10) undoStack.removeAt(0)
        _undoOffers.tryEmit(label)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val e = undoStack.removeAt(undoStack.lastIndex)
        _translationUnits.value = _translationUnits.value.map { u ->
            var x = u
            e.roles?.get(u.id)?.let { x = x.copy(voiceRole = it) }
            e.speakers?.get(u.id)?.let { x = x.copy(speaker = it) }
            e.texts?.get(u.id)?.let { x = x.copy(uzText = it.uzText, check = it.check, issue = it.issue) }
            x
        }
        _approved.value = false
        toast("Qaytarildi: ${e.label}")
    }

    // ------------------------------------------------------------------ qidirish va almashtirish

    /** Hamma gaplarda [find] ni [repl] ga almashtiradi. Nechta gap o'zgargani qaytariladi. */
    fun replaceInTexts(find: String, repl: String, matchCase: Boolean, wholeWord: Boolean): Int {
        if (find.isEmpty()) return 0
        val re = uz.dublaj.text.FindReplace.regex(find, matchCase, wholeWord)
        val replacement = Regex.escapeReplacement(repl)
        val old = ArrayList<TranslationUnit>()
        val newList = _translationUnits.value.map { u ->
            if (re.containsMatchIn(u.uzText)) {
                val t = re.replace(u.uzText, replacement)
                if (t != u.uzText) {
                    old.add(u)
                    return@map u.copy(uzText = t, check = false, issue = null)
                }
            }
            u
        }
        if (old.isEmpty()) return 0
        pushUndo("${old.size} ta gapda «$find» → «$repl»", oldTexts = old)
        _translationUnits.value = newList
        _approved.value = false
        return old.size
    }

    // ------------------------------------------------------------------ spikerlar bo'yicha guruhlash

    fun setSpeaker(index: Int, speaker: Int) {
        val list = _translationUnits.value.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(speaker = speaker)
        _translationUnits.value = list
        _approved.value = false
    }

    /** Gaplar orasidagi pauza ≥ [gapSec] bo'lsa spiker almashadi: Spiker 1 → 2 → 1 … (taxminiy, qo'lda tuzatiladi). */
    fun detectSpeakers(count: Int = 2, gapSec: Double = 1.2) {
        val sorted = _translationUnits.value.sortedBy { it.start }
        if (sorted.isEmpty()) return
        pushUndo("Spikerlar pauzalar bo'yicha ajratildi", speakers = true)
        val bySpeaker = HashMap<Int, Int>()
        var cur = 1
        var prevEnd = sorted.first().start
        sorted.forEachIndexed { i, u ->
            if (i > 0 && u.start - prevEnd >= gapSec) cur = if (cur >= count) 1 else cur + 1
            bySpeaker[u.id] = cur
            prevEnd = u.end
        }
        _translationUnits.value = _translationUnits.value.map { it.copy(speaker = bySpeaker[it.id] ?: 0) }
        _approved.value = false
    }

    fun clearSpeakers() {
        if (_translationUnits.value.none { it.speaker > 0 }) return
        pushUndo("Spiker belgilari o'chirildi", speakers = true)
        _translationUnits.value = _translationUnits.value.map { it.copy(speaker = 0) }
    }

    /** Bir spikerning hamma gapiga ovozni (rolni) birdan berish. */
    fun setRoleForSpeaker(speaker: Int, role: String) {
        if (_translationUnits.value.none { it.speaker == speaker }) return
        val name = when (role) { "erkak" -> "erkak"; "ayol" -> "ayol"; "bola" -> "bola"; else -> "asl ovoz" }
        pushUndo("Spiker $speaker: hamma gap $name ovoziga o'tkazildi", roles = true)
        _translationUnits.value = _translationUnits.value.map { if (it.speaker == speaker) it.copy(voiceRole = role) else it }
        _approved.value = false
    }

    fun setVoiceRole(index: Int, role: String) {
        val list = _translationUnits.value.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(voiceRole = role)
        _translationUnits.value = list
        _approved.value = false
    }

    /**
     * Spikerlarni taxminiy ajratish:
     * gaplar orasidagi pauza ≥ [gapSec] bo'lsa yangi spiker deb hisoblab
     * erkak/ayol navbat bilan belgilanadi. Bu ML diarization emas — qo'lda tuzatish mumkin.
     */
    fun autoAssignRoles(gapSec: Double = 1.2) {
        val list = _translationUnits.value.sortedBy { it.start }.toMutableList()
        if (list.isEmpty()) return
        pushUndo("Spikerlar taxminan belgilandi (erkak/ayol)", roles = true)
        var role = "erkak"
        var prevEnd = list.first().start
        for (i in list.indices) {
            val u = list[i]
            if (i > 0 && u.start - prevEnd >= gapSec) {
                role = if (role == "erkak") "ayol" else "erkak"
            }
            list[i] = u.copy(voiceRole = role)
            prevEnd = u.end
        }
        // id bo'yicha asl tartibga qaytarish
        val byId = list.associateBy { it.id }
        _translationUnits.value = _translationUnits.value.map { byId[it.id] ?: it }
        _approved.value = false
    }

    fun clearAllRoles() {
        pushUndo("Hamma ovoz belgilari tozalandi", roles = true)
        _translationUnits.value = _translationUnits.value.map { it.copy(voiceRole = "") }
        _approved.value = false
    }

    /** Hamma gaplarni bitta ovozga o'tkazish: "erkak" | "ayol" | "bola" | "" (ONNX ning asl ovozi). */
    fun setAllRoles(role: String) {
        if (_translationUnits.value.isEmpty()) return
        pushUndo(
            when (role) {
                "erkak" -> "Hamma gap erkak ovoziga o'tkazildi"
                "ayol" -> "Hamma gap ayol ovoziga o'tkazildi"
                "bola" -> "Hamma gap bola ovoziga o'tkazildi"
                else -> "Hamma gap ONNX ning asl ovoziga qaytarildi"
            },
            roles = true
        )
        _translationUnits.value = _translationUnits.value.map { it.copy(voiceRole = role) }
        _approved.value = false
    }

    /** Asl nutq ton balandligidan aniqlangan erkak/ayol belgilarini qayta qo'yadi. */
    fun restoreAutoRoles(context: Context) {
        val app = context.applicationContext
        if (_translationUnits.value.isEmpty()) return
        viewModelScope.launch {
            try {
                val map = withContext(Dispatchers.IO) { pipeline.autoRolesFor(app, _config.value) }
                pushUndo("Avtomatik aniqlangan ovozlar qaytarildi", roles = true)
                _translationUnits.value = _translationUnits.value.map { it.copy(voiceRole = map[it.id] ?: "") }
                _approved.value = false
            } catch (e: Exception) {
                toast("Avtomatik aniqlab bo'lmadi: ${e.message ?: "matn ma'lumoti yo'q"}")
            }
        }
    }

    fun setUserVoice(index: Int, has: Boolean) {
        val list = _translationUnits.value.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(hasUserVoice = has)
        _translationUnits.value = list
        _approved.value = false
        appCtx?.let { ctx ->
            viewModelScope.launch(Dispatchers.IO) {
                try { pipeline.saveTranslation(ctx, _translationUnits.value) } catch (_: Exception) {}
            }
        }
    }

    fun clearUserVoice(context: Context, index: Int, unitId: Int) {
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try { pipeline.work(app).clearSegmentVoice(unitId) } catch (_: Exception) {}
        }
        setUserVoice(index, false)
    }

    fun segmentVoiceFile(context: Context, unitId: Int): java.io.File =
        pipeline.work(context.applicationContext).segmentVoice(unitId)

    /** Bitta gapni qayta tarjima. */
    fun retranslateUnit(context: Context, index: Int) {
        val app = context.applicationContext
        if (isBusy) { toast("Boshqa jarayon ketyapti — biroz kuting"); return }
        if (!_config.value.onlineReady) {
            toast("AI kaliti kerak. Sozlamalar → Tarjima (AI)")
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _modelStatus.value = "Gap qayta tarjima qilinmoqda…"
            try {
                val list = withContext(Dispatchers.IO) {
                    pipeline.translateSingle(app, _config.value, _translationUnits.value, index)
                }
                _translationUnits.value = list
                _approved.value = false
                toast("Gap yangilandi ✓")
            } catch (e: Exception) {
                toast(friendlyError(e.message))
            } finally {
                _busy.value = false
                _modelStatus.value = null
            }
        }
    }

    /** Yozilgan foydalanuvchi ovozini tinglash. */
    fun playUserVoice(context: Context, unitId: Int) {
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    pipeline.work(context.applicationContext).segmentVoice(unitId)
                }
                if (!f.isFile || f.length() < 44) {
                    toast("Bu gap uchun ovoz yozilmagan")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val (pcm, sr) = uz.dublaj.util.SegmentRecorder.readWavPcm(f)
                    if (pcm.isEmpty()) return@withContext
                    uz.dublaj.util.PcmPlayer.play(pcm, sr)
                }
            } catch (e: Exception) {
                toast("Ijro xato: ${e.message}")
            }
        }
    }

    /** Foydalanuvchiga tushunarli xato matni. */
    fun friendlyError(raw: String?): String {
        val m = (raw ?: "").lowercase()
        return when {
            m.isBlank() -> "Noma'lum xato. Qayta urinib ko'ring."
            "kalit" in m || "api" in m || "401" in m || "403" in m ->
                "API kalit noto'g'ri yoki yo'q. Sozlamalar → Tarjima (AI) dan tekshiring."
            "internet" in m || "unable to resolve" in m || "failed to connect" in m || "timeout" in m ->
                "Internet yo'q yoki sekin. Wi‑Fi/mobil internetni tekshirib qayta urining."
            "joy" in m || "space" in m || "xotira" in m || "enospc" in m ->
                "Telefonda bo'sh joy yetarli emas. Sozlamalar → Xotira dan tozalang."
            "vits" in m || "onnx" in m || "ovoz komponenti" in m ->
                "Ovoz modeli yo'q. Sozlamalarda «Ovozni internetdan yuklash» ni bosing."
            "whisper" in m || "matn aniqlash" in m ->
                "Matn aniqlash modeli yo'q. Sozlamalarda Whisper yuklang."
            "bekor" in m || "cancel" in m -> "Bekor qilindi."
            else -> raw!!.take(180)
        }
    }

    fun saveTranslations(context: Context) {
        val app = context.applicationContext
        val units = _translationUnits.value
        if (units.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pipeline.saveTranslation(app, units)
                if (!_approved.value) pipeline.clearApproval(app)
            } catch (e: Exception) {
                Timber.w(e, "saveTranslations")
            }
        }
    }

    /** Tasdiqlash: tekshirilmagan (xato) segmentlar qolgan bo'lsa rad etiladi. */
    fun approveTranslation(context: Context) {
        val units = _translationUnits.value
        if (units.isEmpty()) return
        val pending = units.count { it.check && it.issue?.startsWith(uz.dublaj.data.DUB_ISSUE_PREFIX) != true }
        if (pending > 0) {
            _state.value = PipelineState.Error(
                "$pending ta segment tarjima qilinmagan (⚠). Tarjimani tahrirlab chiqing, keyin tasdiqlang."
            )
            return
        }
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            pipeline.saveTranslation(app, units)
            pipeline.markApproved(app)
            _approved.value = true
        }
    }

    fun dub(context: Context) {
        if (!_approved.value) {
            _state.value = PipelineState.Error("Avval tarjimani tasdiqlang")
            return
        }
        val cfg0 = _config.value
        val estMin = (((_translationUnits.value.lastOrNull()?.end ?: 0.0) / 60.0).toInt()).coerceAtLeast(1)
        val limitMsg = UsageStore.canDub(context, cfg0.isPro, estMin)
        if (limitMsg != null) {
            _state.value = PipelineState.Error(limitMsg)
            showLimitPrompt()
            return
        }
        releasePreview()
        launchTask(context, "Dublyaj") {
            val cfg = _config.value
            val units = _translationUnits.value
            val app = context.applicationContext
            val title = _videoUri.value?.let { FileUtils.displayName(app, it) } ?: ""
            val out: File = withContext(Dispatchers.IO) {
                pipeline.dub(app, units, cfg, title) { p, m ->
                    _state.value = PipelineState.Running("Dublyaj", p, m)
                }.also { autoSave(app, it, cfg) }
            }
            // Free limit hisobi
            val dur = units.lastOrNull()?.end ?: 60.0
            if (!cfg.isPro) UsageStore.recordDub(app, dur)
            // Hisobot: tezlashtirilgan/kechikkan gaplar tahrir ekranida "tekshirilsin" deb belgilanadi
            val issues = pipeline.lastIssues
            val flagged = units.map { u ->
                val clean = if (u.issue?.startsWith(uz.dublaj.data.DUB_ISSUE_PREFIX) == true) u.copy(check = false, issue = null) else u
                issues[u.id]?.let { clean.copy(check = true, issue = it) } ?: clean
            }
            if (flagged != units) {
                _translationUnits.value = flagged
                withContext(Dispatchers.IO) { pipeline.saveTranslation(app, flagged) }
            }
            _state.value = PipelineState.Success(out.absolutePath)
            toast("Dublyaj tayyor ✓")
            PipelineService.notifyDone(app, "Dublyaj tayyor", title.ifBlank { "Video dublyaj qilindi" })
            // Interstitial (Pro da o'tkazib yuboriladi)
            // AdsManager.showInterstitialAfterDub(...) — Activity kerak, UI dan chaqirish mumkin
        }
    }

    /** Matn ajratish + tarjima; keyin foydalanuvchi tahrir qiladi. */
    fun transcribeAndTranslate(context: Context) {
        val uri = _videoUri.value ?: return
        // Limit tugagan bo'lsa AI so'rovlarini behuda sarflamaymiz
        if (UsageStore.canDub(context, _config.value.isPro) != null) {
            showLimitPrompt()
            return
        }
        launchTask(context, "Matn va tarjima") {
            val cfg = _config.value
            val app = context.applicationContext
            withContext(Dispatchers.IO) {
                pipeline.transcribe(app, uri, cfg) { p, m ->
                    _state.value = PipelineState.Running("Matn ajratish", p * 0.5f, m)
                }
            }
            _hasTranscript.value = true
            val units = withContext(Dispatchers.IO) {
                pipeline.translate(app, cfg, false, { m, d, t -> askTranslateDecision(app, m, d, t) }) { p, m ->
                    _state.value = PipelineState.Running("Tarjima", 0.5f + p * 0.5f, m)
                }
            }
            undoStack.clear()
            _translationUnits.value = units
            _approved.value = false
            _translateResume.value = null
            _state.value = PipelineState.Idle
            _plainOriginal.value = withContext(Dispatchers.IO) { pipeline.plainTranscript(app) }
            toast("Matn va tarjima tayyor ✓")
            PipelineService.notifyDone(app, "Tarjima tayyor", "${units.size} ta gap tekshirishni kutmoqda")
        }
    }

    // ------------------------------------------------------------------ modellar

    fun downloadWhisper(context: Context) {
        val app = context.applicationContext
        val id = _config.value.whisperModel
        launchModelTask(app, "Matn aniqlash komponenti yuklanmoqda…") {
            pipeline.downloadWhisper(app, id) { d, t ->
                _modelStatus.value = "Matn aniqlash: ${mb(d)} / ${if (t > 0) mb(t) else "?"}"
            }
        }
    }

    /** O'zbekcha ovoz modelini internetdan (GitHub Release) yuklaydi. */
    fun downloadVits(context: Context) {
        val app = context.applicationContext
        launchModelTask(app, "O'zbekcha ovoz yuklanmoqda…") {
            pipeline.downloadVits(app) { d, t ->
                _modelStatus.value = "Ovoz: ${mb(d)} / ${if (t > 0) mb(t) else "?"}"
            }
        }
    }

    /** Yetishmayotgan hamma komponentni (matn aniqlash + ovoz) bir bosishda yuklaydi. */
    fun downloadMissing(context: Context) {
        val app = context.applicationContext
        val id = _config.value.whisperModel
        launchModelTask(app, "Komponentlar yuklanmoqda…") {
            val m = pipeline.modelsInfo(app, _config.value)
            if (!m.whisper) {
                pipeline.downloadWhisper(app, id) { d, t ->
                    _modelStatus.value = "Matn aniqlash: ${mb(d)} / ${if (t > 0) mb(t) else "?"}"
                }
            }
            if (!m.vits) {
                pipeline.downloadVits(app) { d, t ->
                    _modelStatus.value = "Ovoz: ${mb(d)} / ${if (t > 0) mb(t) else "?"}"
                }
            }
        }
    }

    private val _aiModels = MutableStateFlow<List<String>>(emptyList())
    /** "Modellarni olish" natijasi (faol provayder uchun). */
    val aiModels: StateFlow<List<String>> = _aiModels.asStateFlow()

    /** Sozlamalardagi "Kalitni tekshirish" tugmasi (faol provayder uchun). */
    fun testGemini() {
        val cfg = _config.value
        val p = cfg.provider()
        if (!cfg.onlineReady && cfg.activeKey().isBlank()) {
            _modelStatus.value = "Avval ${p.label} kalitini kiriting"
            return
        }
        viewModelScope.launch {
            _modelStatus.value = "${p.label} tekshirilmoqda…"
            val msg = withContext(Dispatchers.IO) { cfg.newTranslator().test() }
            _modelStatus.value = msg
        }
    }

    fun clearAiModels() { _aiModels.value = emptyList() }

    /** Provayderdan mavjud modellar ro'yxatini oladi. */
    fun fetchAiModels() {
        val cfg = _config.value
        if (cfg.activeKey().isBlank()) {
            toast("Avval kalitni kiriting")
            return
        }
        viewModelScope.launch {
            _modelStatus.value = "Modellar olinmoqda…"
            val res = withContext(Dispatchers.IO) {
                try { Result.success(cfg.newTranslator().listModels()) } catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess {
                _aiModels.value = it
                _modelStatus.value = if (it.isEmpty()) "Modellar topilmadi" else "${it.size} ta model topildi ✓"
            }.onFailure { _modelStatus.value = "Xato: ${it.message}" }
        }
    }

    /** Sozlamalardagi "Serverni tekshirish" tugmasi. */
    fun testChatterbox() {
        val cfg = _config.value
        if (cfg.cbUrl.isBlank()) {
            _modelStatus.value = "Avval server manzilini kiriting"
            return
        }
        viewModelScope.launch {
            _modelStatus.value = "Server tekshirilmoqda…"
            val msg = withContext(Dispatchers.IO) {
                uz.dublaj.tts.ChatterboxClient(cfg.cbUrl, cfg.cbToken).health()
            }
            _modelStatus.value = msg
        }
    }

    /** Klonlash uchun namuna ovoz faylini tanlash. */
    fun importVoiceRef(context: Context, uri: Uri) {
        val app = context.applicationContext
        launchModelTask(app, "Namuna ovoz qo'shilmoqda…") {
            val name = pipeline.importVoiceRef(app, uri)
            setConfig(_config.value.copy(cbRefName = name, cbVoiceMode = "file"))
        }
    }


    fun importWhisper(context: Context, uri: Uri) {
        val app = context.applicationContext
        val id = _config.value.whisperModel
        launchModelTask(app, "Matn aniqlash fayli qo'shilmoqda…") { pipeline.importWhisper(app, uri, id) }
    }

    fun importVitsModel(context: Context, uri: Uri) {
        val app = context.applicationContext
        launchModelTask(app, "O'zbekcha ovoz fayli qo'shilmoqda…") { pipeline.importVitsModel(app, uri) }
    }

    fun importVitsConfig(context: Context, uri: Uri) {
        val app = context.applicationContext
        launchModelTask(app, "Ovoz sozlama fayli qo'shilmoqda…") { pipeline.importVitsConfig(app, uri) }
    }


    /** Foydalanuvchi tanlagan papkadan modellarni topib qo'shadi (SAF). */
    fun importFromTree(context: Context, tree: Uri) {
        val app = context.applicationContext
        if (isBusy) return
        launchModelTask(app, "Papka qidirilmoqda…") {
            val rep = pipeline.importFromTree(app, tree) { _modelStatus.value = it }
            if (rep.found == 0) {
                throw IllegalStateException(
                    "Bu papkada kerakli fayl topilmadi: " + rep.notFound.joinToString("; ")
                )
            }
        }
    }


    fun clearWork(context: Context) {
        if (isBusy) {
            toast("Hozir jarayon ketyapti. Avval uni tugating yoki bekor qiling.")
            return
        }
        val app = context.applicationContext
        viewModelScope.launch {
            val before = withContext(Dispatchers.IO) {
                val b = pipeline.usedBytes(app)
                pipeline.clearWork(app)
                b
            }
            _videoUri.value = null
            _hasTranscript.value = false
            _translationUnits.value = emptyList()
            _approved.value = false
            _plainOriginal.value = ""
            _state.value = PipelineState.Idle
            _workVersion.update { it + 1 }
            toast(if (before > 0) "Tozalandi ✓ ${mb(before)} bo'shatildi" else "Tozalanadigan fayl yo'q ✓")
        }
    }

    suspend fun usedBytes(context: Context): Long =
        withContext(Dispatchers.IO) { pipeline.usedBytes(context.applicationContext) }

    // ------------------------------------------------------------------ ichki

    private fun mb(b: Long) = "${b / (1024 * 1024)} MB"

    private fun launchTask(context: Context, label: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        pipeline.resetCancel()
        _cancelling.value = false
        _busy.value = true
        _state.value = PipelineState.Running(label, 0f, "Boshlanmoqda…")
        job = viewModelScope.launch {
            PipelineService.start(app, "$label ishlamoqda…")
            val notifJob = launch { trackStateNotification(app) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserCancelled) {
                _state.value = PipelineState.Idle
                toast("Bekor qilindi ✓")
            } catch (e: TranslationPaused) {
                _state.value = PipelineState.Idle
                _translateResume.value = "${e.done}/${e.total}"
                toast("Tarjima to'xtatildi (${e.done}/${e.total}). Keyinroq «Davom etish» ni bosing.")
            } catch (e: OutOfMemoryError) {
                Timber.e(e, "OOM")
                _state.value = PipelineState.Error("Xotira yetmadi. Kichikroq video tanlang yoki Sozlamalarda matn aniqlashni \"Tez\" rejimga o'tkazing.")
            } catch (e: Exception) {
                Timber.e(e, "Pipeline xatosi")
                _state.value = PipelineState.Error(e.message ?: e.toString())
            } finally {
                notifJob.cancel()
                PipelineService.stop(app)
                _cancelling.value = false
                _busy.value = false
            }
        }
    }

    /** Bildirishnomada joriy bosqich va foizni ko'rsatib turadi (sekundiga ko'pi bilan bir marta). */
    private suspend fun trackStateNotification(app: Context) {
        var lastPct = -2
        var lastAt = 0L
        _state.collect { st ->
            if (st is PipelineState.Running) {
                val pct = (st.progress * 100).toInt().coerceIn(0, 100)
                val now = android.os.SystemClock.elapsedRealtime()
                if (pct != lastPct && now - lastAt >= 1000L) {
                    lastPct = pct
                    lastAt = now
                    val text = if (st.message.isBlank()) st.stage else "${st.stage} — ${st.message}"
                    PipelineService.update(app, text, if (st.progress > 0f) pct else -1)
                }
            }
        }
    }

    /** Navbat uchun: ishlayotgan videoning foizi bildirishnomada. */
    private suspend fun trackQueueNotification(app: Context) {
        var lastPct = -2
        var lastAt = 0L
        _queue.collect { list ->
            val running = list.firstOrNull { it.status == QueueStatus.RUNNING } ?: return@collect
            val pct = (running.progress * 100).toInt().coerceIn(0, 100)
            val now = android.os.SystemClock.elapsedRealtime()
            if (pct != lastPct && now - lastAt >= 1000L) {
                lastPct = pct
                lastAt = now
                val pos = list.indexOf(running) + 1
                PipelineService.update(app, "Navbat $pos/${list.size}: ${running.name} — ${running.message}", pct)
            }
        }
    }

    // ------------------------------------------------------------------ yangilanish

    private val _update = MutableStateFlow<UpdateChecker.Result.Available?>(null)
    val update: StateFlow<UpdateChecker.Result.Available?> = _update.asStateFlow()
    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()

    /** manual = true: foydalanuvchi o'zi bosdi (natija har doim ko'rsatiladi). */
    fun checkUpdate(context: Context, manual: Boolean) {
        if (!uz.dublaj.BuildConfig.SELF_UPDATE) return   // Play (release): yangilanishni faqat Google Play beradi
        val app = context.applicationContext
        if (!manual && !UpdateChecker.shouldAutoCheck(app)) return
        viewModelScope.launch {
            if (manual) _updateMessage.value = "Tekshirilmoqda…"
            val r = withContext(Dispatchers.IO) { UpdateChecker.check(app) }
            if (r !is UpdateChecker.Result.Failed) UpdateChecker.markChecked(app)
            when (r) {
                is UpdateChecker.Result.Available -> {
                    if (manual || !UpdateChecker.isSkipped(app, r.build)) _update.value = r
                    _updateMessage.value = "Yangi versiya mavjud: ${r.versionName}"
                }
                UpdateChecker.Result.UpToDate -> if (manual) _updateMessage.value = "Sizda eng so'nggi versiya ✓"
                is UpdateChecker.Result.Failed -> if (manual) _updateMessage.value = r.message
            }
        }
    }

    private val _updateProgress = MutableStateFlow<Float?>(null)
    /** null = yuklanmayapti; 0..1 = yuklash jarayoni. */
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()

    /** Yangilanish APK'ni ilova ichida yuklaydi va o'rnatuvchini ochadi. */
    fun downloadUpdate(context: Context) {
        val app = context.applicationContext
        val u = _update.value ?: return
        if (_updateProgress.value != null) return
        viewModelScope.launch {
            _updateProgress.value = 0f
            _updateMessage.value = "Yangilanish yuklanmoqda…"
            try {
                val apk = withContext(Dispatchers.IO) {
                    UpdateChecker.download(app, u) { p -> _updateProgress.value = p }
                }
                _updateProgress.value = null
                val started = UpdateChecker.install(app, apk)
                _updateMessage.value = if (started) {
                    "O'rnatish oynasi ochildi: \"Yangilash\" ni bosing"
                } else {
                    "\"Noma'lum manbalar\" ruxsatini yoqing, so'ng qayta \"Yuklab olish\" ni bosing"
                }
            } catch (e: CancellationException) {
                _updateProgress.value = null
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Yangilanishni yuklash xatosi")
                _updateProgress.value = null
                _updateMessage.value = "Yuklab bo'lmadi: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun dismissUpdate(context: Context, skipThis: Boolean) {
        _update.value?.let { if (skipThis) UpdateChecker.skip(context.applicationContext, it.build) }
        _update.value = null
    }

    // ------------------------------------------------------------------ navbat

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()
    private var queueSeq = 0L

    fun addToQueue(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        var picked = uris
        if (!_config.value.isPro) {
            val room = (uz.dublaj.data.UsageStore.FREE_QUEUE_ITEMS - _queue.value.count { it.status == QueueStatus.WAITING || it.status == QueueStatus.RUNNING }).coerceAtLeast(0)
            if (picked.size > room) {
                toast("Bepul versiyada navbatda ${uz.dublaj.data.UsageStore.FREE_QUEUE_ITEMS} tagacha video. Pro bilan cheksiz.")
                picked = picked.take(room)
            }
        }
        val pro = _config.value.isPro
        viewModelScope.launch {
            val ok = ArrayList<Uri>()
            var longest = 0
            for (u in picked) {
                val ms = if (pro) null else withContext(Dispatchers.IO) { videoDurationMs(app, u) }
                if (ms != null && ms > UsageStore.FREE_MAX_VIDEO_MINUTES * 60_000L) {
                    longest = maxOf(longest, kotlin.math.ceil(ms / 60000.0).toInt())
                } else ok.add(u)
            }
            if (longest > 0) _proPrompts.tryEmit(uz.dublaj.data.ProPrompt.TooLong(longest))
            val items = ok.map { QueueItem(id = ++queueSeq, uri = it, name = FileUtils.displayName(app, it)) }
            _queue.update { it + items }
        }
    }

    fun removeFromQueue(id: Long) {
        _queue.update { list -> list.filterNot { it.id == id && it.status != QueueStatus.RUNNING } }
    }

    fun clearFinishedQueue() {
        _queue.update { list -> list.filter { it.status == QueueStatus.WAITING || it.status == QueueStatus.RUNNING } }
    }

    private fun updateItem(id: Long, f: (QueueItem) -> QueueItem) {
        _queue.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    /** Navbatdagi videolarni ketma-ket avtomatik dublyaj qiladi (tarjimani tekshirmasdan). */
    fun startQueue(context: Context) {
        if (isBusy) return
        val app = context.applicationContext
        if (_queue.value.none { it.status == QueueStatus.WAITING }) return
        val cfg = _config.value
        if (!_models.value.let { it.whisper && cfg.onlineReady && (it.vits || cfg.chatterboxReady) }) {
            _modelStatus.value = "Xato: navbat uchun uchala komponent (matn aniqlash, tarjima, o'zbekcha ovoz) kerak"
            return
        }
        releasePreview()
        pipeline.resetCancel()
        _cancelling.value = false
        _busy.value = true
        // Navbat umumiy ish papkasidan foydalanadi: joriy bitta-video loyihasi tozalanadi
        _videoUri.value = null
        _hasTranscript.value = false
        undoStack.clear()
        _translationUnits.value = emptyList()
        _approved.value = false
        _state.value = PipelineState.Idle

        job = viewModelScope.launch {
            PipelineService.start(app, "Navbat ishlamoqda…")
            val notifJob = launch { trackQueueNotification(app) }
            try {
                while (true) {
                    val item = _queue.value.firstOrNull { it.status == QueueStatus.WAITING } ?: break
                    val limitMsg = UsageStore.canDub(app, cfg.isPro)
                    if (limitMsg != null) {
                        updateItem(item.id) { it.copy(status = QueueStatus.FAILED, message = limitMsg) }
                        showLimitPrompt()
                        break
                    }
                    updateItem(item.id) { it.copy(status = QueueStatus.RUNNING, progress = 0f, message = "Boshlanmoqda…") }
                    try {
                        var skipped = 0
                        val out = withContext(Dispatchers.IO) {
                            pipeline.transcribe(app, item.uri, cfg) { p, m ->
                                updateItem(item.id) { it.copy(progress = p * 0.25f, message = "Matn: $m") }
                            }
                            val translated = pipeline.translate(app, cfg) { p, m ->
                                updateItem(item.id) { it.copy(progress = 0.25f + p * 0.25f, message = "Tarjima: $m") }
                            }
                            // Tarjima qilinmagan gaplar o'tkazib yuboriladi (avtomatik rejimda tahrir yo'q)
                            skipped = translated.count { it.check && it.uzText == it.text }
                            val units = translated.map { if (it.check && it.uzText == it.text) it.copy(uzText = "", check = false) else it.copy(check = false) }
                            pipeline.saveTranslation(app, units)
                            pipeline.markApproved(app)
                            pipeline.dub(app, units, cfg, item.name) { p, m ->
                                updateItem(item.id) { it.copy(progress = 0.5f + p * 0.5f, message = m) }
                            }.also { autoSave(app, it, cfg) }
                        }
                        if (!cfg.isPro) {
                            val durSec = withContext(Dispatchers.IO) {
                                try {
                                    val r = android.media.MediaMetadataRetriever()
                                    try {
                                        r.setDataSource(out.absolutePath)
                                        (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 60_000L) / 1000.0
                                    } finally {
                                        try { r.release() } catch (_: Exception) {}
                                    }
                                } catch (e: Exception) { 60.0 }
                            }
                            UsageStore.recordDub(app, durSec)
                        }
                        updateItem(item.id) {
                            it.copy(
                                status = QueueStatus.DONE, progress = 1f, outputPath = out.absolutePath,
                                message = if (skipped > 0) "Tayyor ✓ • ⚠ $skipped ta gap tarjima qilinmadi, o'tkazildi" else "Tayyor ✓"
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UserCancelled) {
                        updateItem(item.id) { it.copy(status = QueueStatus.CANCELLED, message = "Bekor qilindi") }
                        break
                    } catch (e: OutOfMemoryError) {
                        updateItem(item.id) { it.copy(status = QueueStatus.FAILED, message = "Xotira yetmadi") }
                    } catch (e: Exception) {
                        Timber.e(e, "Navbat xatosi")
                        updateItem(item.id) { it.copy(status = QueueStatus.FAILED, message = e.message ?: e.toString()) }
                    }
                    withContext(Dispatchers.IO) { pipeline.clearWork(app) }
                }
            } finally {
                notifJob.cancel()
                PipelineService.stop(app)
                _cancelling.value = false
                _busy.value = false
                val done = _queue.value.count { it.status == QueueStatus.DONE }
                toast("Navbat tugadi: $done ta video tayyor")
                if (done > 0) PipelineService.notifyDone(app, "Navbat tugadi", "$done ta video dublyaj qilindi")
            }
        }
    }

    private fun autoSave(app: Context, out: File, cfg: AppConfig) {
        if (!cfg.autoSaveGallery) return
        try {
            OutputUtils.saveToGallery(app, out)
        } catch (e: Exception) {
            Timber.w(e, "Galereyaga avtomatik saqlanmadi")
        }
    }

    private fun launchModelTask(app: Context, label: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        pipeline.resetCancel()
        _cancelling.value = false
        _busy.value = true
        _modelStatus.value = label
        job = viewModelScope.launch {
            PipelineService.start(app, label, dataSync = true)
            try {
                withContext(Dispatchers.IO) { block() }
                _modelStatus.value = "Tayyor ✓"
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserCancelled) {
                _modelStatus.value = "Bekor qilindi"
                toast("Bekor qilindi ✓")
            } catch (e: Exception) {
                Timber.e(e, "Model xatosi")
                _modelStatus.value = "Xato: ${e.message}"
            } finally {
                PipelineService.stop(app)
                _cancelling.value = false
                _busy.value = false
                _models.value = withContext(Dispatchers.IO) { pipeline.modelsInfo(app, _config.value) }
            }
        }
    }
}
