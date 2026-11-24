@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uz.dublaj.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.dublaj.data.PipelineState
import uz.dublaj.data.UsageStore
import uz.dublaj.monetization.BillingManager
import uz.dublaj.ui.components.ProBenefitsDialog
import uz.dublaj.ui.theme.findActivity
import uz.dublaj.ui.components.CollectUiEvents
import uz.dublaj.ui.components.HelpSlider
import uz.dublaj.ui.components.RunningBanner
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.ExportUtils
import uz.dublaj.util.OutputUtils
import uz.dublaj.util.WorkDir
import java.io.File
import java.util.Locale

/** "Matn va ovoz": videodan oddiy matn olish va istalgan matnni o'zbekcha ovozga aylantirish. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextToolsScreen(vm: PipelineViewModel, onBack: () -> Unit, onEdit: () -> Unit = {}) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val cancelling by vm.cancelling.collectAsState()
    val models by vm.models.collectAsState()
    val config by vm.config.collectAsState()
    val videoUri by vm.videoUri.collectAsState()
    val units by vm.translationUnits.collectAsState()
    val hasTranscript by vm.hasTranscript.collectAsState()
    val original by vm.plainOriginal.collectAsState()
    val timed by vm.timedOriginal.collectAsState()
    val audio by vm.audioResult.collectAsState()
    val host = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(vm.consumeTextTab()) }
    var typed by rememberSaveable { mutableStateOf("") }

    CollectUiEvents(vm, host)
    LaunchedEffect(audio) {
        if (audio != null) context.findActivity()?.let { uz.dublaj.monetization.AdsManager.showInterstitialSoft(it) }
    }
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (wasBusy && !busy && timed.isNotEmpty()) {
            context.findActivity()?.let { uz.dublaj.monetization.AdsManager.showInterstitialSoft(it) }
        }
        wasBusy = busy
    }
    LaunchedEffect(hasTranscript) { if (hasTranscript) vm.refreshPlainTexts(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u?.let { vm.setVideo(it) }
    }
    val uzText = remember(units) { vm.plainUzbek() }
    val txtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { vm.loadTextFile(context, it) { text -> typed = text } }
    }
    val limit = vm.textLimit()
    var showPro by remember { mutableStateOf(false) }
    if (showPro) {
        ProBenefitsDialog(
            remainingDubs = UsageStore.remainingDubs(context, config.isPro),
            priceText = BillingManager.priceText(),
            onUpgrade = {
                showPro = false
                context.findActivity()?.let { BillingManager.launchPurchase(it) }
            },
            onDismiss = { showPro = false }
        )
    }

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(host) },
        topBar = {
            TopAppBar(
                title = { Text("Matn va ovoz") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Videodan matn") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Matn → ovoz") })
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (val s = state) {
                    is PipelineState.Running -> RunningBanner(s, cancelling, onCancel = { vm.cancel() })
                    is PipelineState.Error -> Card(
                        colors = CardDefaults.cardColors(containerColor = cs.errorContainer),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.message, color = cs.onErrorContainer)
                            TextButton(onClick = { vm.resetState() }) { Text("Yopish") }
                        }
                    }
                    else -> {}
                }

                Crossfade(targetState = tab, label = "tab") { t ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (t == 0) {
                            Text(
                                "Videodagi nutqni matnga aylantiradi: vaqt belgilari bilan va yaxlit matn ko'rinishida. " +
                                    "«Faqat matn» tarjimasiz va AI kalitisiz ishlaydi.",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
                            )
                            if (videoUri == null) {
                                Button(
                                    onClick = { picker.launch("video/*") }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                ) {
                                    Icon(Icons.Default.VideoLibrary, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Video tanlash")
                                }
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { vm.transcribeOnly(context) },
                                        enabled = !busy && models.whisper,
                                        modifier = Modifier.heightIn(min = 52.dp)
                                    ) {
                                        Icon(Icons.Default.TextSnippet, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Faqat matn")
                                    }
                                    FilledTonalButton(
                                        onClick = { vm.transcribeAndTranslate(context) },
                                        enabled = !busy && models.whisper && config.onlineReady,
                                        modifier = Modifier.heightIn(min = 52.dp)
                                    ) {
                                        Icon(Icons.Default.Translate, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Matn + tarjima")
                                    }
                                    OutlinedButton(
                                        onClick = { picker.launch("video/*") }, enabled = !busy,
                                        modifier = Modifier.heightIn(min = 52.dp)
                                    ) { Text("Boshqa video") }
                                }
                                if (!models.whisper) {
                                    Text(
                                        "Avval Sozlamalardan matn aniqlash (Whisper) komponentini yuklab oling.",
                                        style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                                    )
                                }
                            }
                            if (timed.isNotEmpty()) {
                                TranscriptView(timed, original, vm, onEdit)
                            }
                            if (uzText.isNotBlank()) {
                                TextBlock(
                                    "O'zbekcha matn", uzText, "ozbekcha_matn",
                                    onSpeak = if (busy) null else ({ vm.speak(context, uzText) })
                                )
                            }
                            if (original.isBlank() && uzText.isBlank() && videoUri != null && !busy) {
                                Text(
                                    "Matn hali ajratilmagan. Yuqoridagi tugmani bosing.",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                                )
                            }
                        } else {
                            var showRead by remember { mutableStateOf(false) }
                            // Asosiy tugma — eng tepada, KO'RINARLI
                            Button(
                                onClick = { vm.speak(context, typed) },
                                enabled = !busy && typed.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Icon(Icons.Default.RecordVoiceOver, null)
                                Spacer(Modifier.width(10.dp))
                                Text("Ovozga aylantirish", style = MaterialTheme.typography.titleMedium)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledTonalButton(
                                    onClick = { txtPicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                                    enabled = !busy
                                ) {
                                    Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("TXT fayl yuklash")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                        if (t.isBlank()) vm.toast("Buferda matn yo'q") else typed = t
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Qo'yish")
                                }
                                if (typed.isNotEmpty()) {
                                    TextButton(onClick = { typed = "" }) { Text("Tozalash") }
                                }
                            }
                            OutlinedTextField(
                                value = typed,
                                onValueChange = { typed = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 6,
                                label = { Text("O'zbekcha matnni kiriting yoki qo'ying") },
                                isError = typed.length > limit,
                                supportingText = {
                                    Text(
                                        if (typed.length > limit) "${typed.length} / $limit belgi — ortig'i kesiladi"
                                        else "${typed.length} / $limit belgi"
                                    )
                                }
                            )
                            if (!config.isPro && typed.length > UsageStore.FREE_TEXT_CHARS / 2) {
                                TextButton(onClick = { showPro = true }) {
                                    Icon(Icons.Default.Star, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Pro bilan ${UsageStore.PRO_TEXT_CHARS} belgigacha")
                                }
                            }
                            TextButton(
                                onClick = { showRead = !showRead },
                                enabled = typed.isNotBlank()
                            ) { Text(if (showRead) "O'qilishini yashirish" else "Qanday o'qilishini ko'rish") }
                            if (showRead && typed.isNotBlank()) {
                                // Ovozga AYNAN shu matn ketadi (raqam, qisqartma, lug'at almashtirilgandan keyin)
                                val readText = remember(typed) {
                                    uz.dublaj.text.TextNormalizer.fromAssets(context).normalize(typed)
                                }
                                Card(
                                    shape = MaterialTheme.shapes.large,
                                    colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant)
                                ) {
                                    SelectionContainer {
                                        Text(
                                            readText,
                                            modifier = Modifier.padding(12.dp),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                            if (!models.vits) {
                                Text(
                                    "O'zbekcha ovoz fayllari qo'shilmagan: Sozlamalar bo'limidan qo'shing.",
                                    style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                                )
                            }
                            // tezkor ovoz sozlamalari
                            Card(
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(containerColor = cs.surface)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Ovoz sozlamalari", style = MaterialTheme.typography.titleMedium)
                                    HelpSlider(
                                        title = "Gapirish tezligi",
                                        valueText = String.format(Locale.US, "%.2f×", config.voiceSpeed),
                                        help = "1.0 — oddiy. Kichik son sekinroq, katta son tezroq gapiradi.",
                                        value = config.voiceSpeed, range = 0.6f..1.5f
                                    ) { vm.setConfig(config.copy(voiceSpeed = it), context) }
                                    HelpSlider(
                                        title = "Ovoz tembri",
                                        valueText = if (config.voicePitch == 0f) "Asl"
                                        else String.format(Locale.US, "%+.1f", config.voicePitch),
                                        help = "Manfiy qiymat — qalinroq (erkakcha), musbat — ingichkaroq ovoz. 0 — o'zgarishsiz.",
                                        value = config.voicePitch, range = -4f..4f
                                    ) { vm.setConfig(config.copy(voicePitch = Math.round(it * 2f) / 2f), context) }
                                    HelpSlider(
                                        title = "Abzaslar orasidagi pauza",
                                        valueText = String.format(Locale.US, "%.2f s", config.voicePause),
                                        help = "Gaplar va abzaslar orasida qancha to'xtash bo'lishi.",
                                        value = config.voicePause, range = 0.1f..1.0f
                                    ) { vm.setConfig(config.copy(voicePause = it), context) }
                                }
                            }
                        }

                        audio?.let { f -> AudioCard(f, vm) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextBlock(title: String, text: String, fileBase: String, onSpeak: (() -> Unit)?) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${text.length} belgi",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant
                )
            }
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (text.length > 400) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Yig'ish" else "To'liq ko'rish") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("matn", text))
                        Toast.makeText(context, "Nusxalandi ✓", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nusxa")
                }
                FilledTonalButton(
                    onClick = {
                        try {
                            val dir = WorkDir(context.applicationContext).output.apply { mkdirs() }
                            val f = File(dir, "${fileBase}_${System.currentTimeMillis()}.txt")
                            f.writeText(text)
                            uz.dublaj.util.safeStart(context, ExportUtils.shareIntent(context, f, "text/plain", "Matnni saqlash/ulashish"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Fayl yozilmadi: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Description, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("TXT")
                }
                FilledTonalButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        uz.dublaj.util.safeStart(context, Intent.createChooser(send, "Ulashish"))
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ulashish")
                }
            }
            if (onSpeak != null) {
                Button(onClick = onSpeak, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.RecordVoiceOver, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Audioga aylantirish")
                }
            }
        }
    }
}

@Composable
private fun AudioCard(file: File, vm: PipelineViewModel) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    DisposableEffect(file) {
        onDispose {
            try { player?.release() } catch (_: Exception) { }
            player = null
        }
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AudioFile, null, tint = cs.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Audio tayyor", style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
                    Text(
                        "${file.length() / 1024} KB • WAV",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
                IconButton(onClick = { vm.clearAudioResult() }) {
                    Icon(Icons.Default.Close, contentDescription = "Yopish", tint = cs.onSecondaryContainer)
                }
            }
            Button(
                onClick = {
                    try {
                        if (playing) {
                            player?.pause()
                            playing = false
                        } else {
                            val p = player ?: MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                setOnCompletionListener { playing = false }
                                prepare()
                            }.also { player = it }
                            p.start()
                            playing = true
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Ijro etilmadi: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (playing) "To'xtatish" else "Eshitish")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { uz.dublaj.util.safeStart(context, ExportUtils.shareIntent(context, file, "audio/wav", "Audio ulashish")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ulashish")
                }
                FilledTonalButton(
                    onClick = {
                        val msg = try {
                            if (OutputUtils.saveAudioToMusic(context, file) != null) "Musiqa papkasiga saqlandi (Music/DublajUz) ✓"
                            else "Android 10+ kerak. 'Ulashish' orqali saqlang."
                        } catch (e: Exception) { "Saqlash xatosi: ${e.message}" }
                        vm.toast(msg)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Saqlash")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptView(timed: List<uz.dublaj.data.TranscriptUnit>, plain: String, vm: PipelineViewModel, onEdit: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var mode by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.animateContentSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == 0, onClick = { mode = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) { Text("Vaqtli (${timed.size})") }
            SegmentedButton(
                selected = mode == 1, onClick = { mode = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) { Text("Yaxlit matn") }
        }
        Crossfade(targetState = mode, label = "trmode") { m ->
            if (m == 1) {
                TextBlock("Asl matn (videodagi til)", plain, "asl_matn", null)
            } else {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = cs.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        timed.forEachIndexed { i, u ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(shape = MaterialTheme.shapes.small, color = cs.primaryContainer) {
                                    Text(
                                        uz.dublaj.ui.components.fmtTime(u.start),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer
                                    )
                                }
                                SelectionContainer(Modifier.weight(1f)) {
                                    Text(u.text.trim(), style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("matn", u.text.trim()))
                                        Toast.makeText(context, "Nusxalandi ✓", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) { Icon(Icons.Default.ContentCopy, "Nusxa", Modifier.size(16.dp), tint = cs.onSurfaceVariant) }
                            }
                            if (i < timed.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
        ExternalAiCard(vm, onEdit)
        if (mode == 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = {
                    val txt = timed.joinToString("\n") { "[" + uz.dublaj.ui.components.fmtTime(it.start) + "] " + it.text.trim() }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("matn", txt))
                    Toast.makeText(context, "Vaqtli matn nusxalandi ✓", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hammasini nusxalash")
                }
                FilledTonalButton(onClick = {
                    try {
                        val dir = WorkDir(context.applicationContext).output.apply { mkdirs() }
                        val f = File(dir, "asl_matn_${System.currentTimeMillis()}.srt")
                        f.writeText(vm.originalSrt())
                        uz.dublaj.util.safeStart(context, ExportUtils.shareIntent(context, f, "application/x-subrip", "SRT ni saqlash/ulashish"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Fayl yozilmadi: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }) {
                    Icon(Icons.Default.Subtitles, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("SRT")
                }
            }
        }
    }
}

private const val AI_PROMPT = "Quyidagi JSON dagi «segments» ro'yxatining har bir elementidagi «text» ni o'zbek tiliga (lotin yozuvi) " +
    "tabiiy, og'zaki nutq uslubida tarjima qiling va natijani shu elementning «uz» maydoniga yozing. " +
    "«id», «start», «end» va «text» ni O'ZGARTIRMANG, elementlarni qo'shmang va tushirib qoldirmang. " +
    "Raqam va qisqartmalarni so'z bilan yozing. Javobda faqat toza JSON qaytaring (izoh va ``` belgilarisiz).\n\n"

@Composable
private fun ExternalAiCard(vm: PipelineViewModel, onEdit: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val busy by vm.busy.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            val txt = try {
                context.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (e: Exception) { "" }
            if (txt.isBlank()) vm.toast("Fayl o'qilmadi yoki bo'sh") else vm.importTranslation(context, txt, onEdit)
        }
    }
    fun clip(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Boshqa AI yoki ilovada tarjima", style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
            Text(
                "1) Matnni JSON qilib oling va istalgan AI ga bering.  2) Tarjima qilingan JSON ni shu yerga qaytaring.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = {
                    scope.launch {
                        val j = withContext(kotlinx.coroutines.Dispatchers.IO) { vm.transcriptJson(context) }
                        clip("json", AI_PROMPT + j)
                        Toast.makeText(context, "Ko'rsatma + JSON nusxalandi. AI ga joylang ✓", Toast.LENGTH_LONG).show()
                    }
                }) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("AI uchun nusxalash")
                }
                FilledTonalButton(onClick = {
                    scope.launch {
                        try {
                            val j = withContext(kotlinx.coroutines.Dispatchers.IO) { vm.transcriptJson(context) }
                            val dir = WorkDir(context.applicationContext).output.apply { mkdirs() }
                            val f = File(dir, "matn_${System.currentTimeMillis()}.json")
                            f.writeText(j)
                            uz.dublaj.util.safeStart(context, ExportUtils.shareIntent(context, f, "application/json", "JSON ni saqlash/ulashish"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Fayl yozilmadi: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Icon(Icons.Default.DataObject, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("JSON fayl")
                }
            }
            HorizontalDivider(color = cs.onSecondaryContainer.copy(alpha = 0.15f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (t.isBlank()) vm.toast("Buferda matn yo'q") else vm.importTranslation(context, t, onEdit)
                    }
                ) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Buferdan import")
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                ) {
                    Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Fayldan import")
                }
            }
        }
    }
}
