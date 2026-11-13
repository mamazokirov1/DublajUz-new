package uz.dublaj.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.dublaj.data.TranslationUnit
import uz.dublaj.text.FindReplace
import uz.dublaj.ui.components.VideoPlayer
import uz.dublaj.ui.components.fmtTime
import uz.dublaj.util.ExportUtils
import uz.dublaj.util.SegmentRecorder
import uz.dublaj.util.SrtExporter
import uz.dublaj.ui.viewmodel.PipelineViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditTranslationScreen(
    vm: PipelineViewModel,
    onBack: () -> Unit
) {
    val units by vm.translationUnits.collectAsState()
    val context = LocalContext.current
    val projectSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(uz.dublaj.util.ProjectFile.MIME)
    ) { uri: Uri? -> uri?.let { vm.exportProject(context, it) } }
    val previewUnit by vm.previewUnit.collectAsState()
    val previewPhase by vm.previewState.collectAsState()
    val videoUri by vm.videoUri.collectAsState()
    val busy by vm.busy.collectAsState()
    val cs = MaterialTheme.colorScheme
    val pending = units.count { it.check }
    val withVoice = units.count { it.hasUserVoice }
    var menu by remember { mutableStateOf(false) }
    var onlyProblems by remember { mutableStateOf(units.any { it.check }) }
    var onlyNoVoice by remember { mutableStateOf(false) }
    var seekMs by remember { mutableStateOf<Long?>(null) }
    var seekTick by remember { mutableIntStateOf(0) }
    // Klaviatura ochilganda video/filtrlar yig'ishtiriladi — matn maydoni uchun joy qoladi
    val imeVisible = WindowInsets.isImeVisible
    var seqMode by remember { mutableStateOf(false) }
    var seqIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    // Past (yoki gorizontal) ekranda video avtomatik yashirin: tahrirlash uchun joy qolsin
    var showVideo by remember { mutableStateOf(screenHeightDp >= 600) }
    var showInfo by remember { mutableStateOf(false) }
    val snackbarState = remember { SnackbarHostState() }
    var showFind by remember { mutableStateOf(false) }
    var showSpeakers by remember { mutableStateOf(false) }
    var pendingScrollId by remember { mutableStateOf<Int?>(null) }
    val speakersOn = units.any { it.speaker > 0 }

    // Ommaviy o'zgarishdan keyin «Qaytarish» tugmali xabar
    LaunchedEffect(Unit) {
        vm.undoOffers.collectLatest { label ->
            val r = snackbarState.showSnackbar(
                message = label,
                actionLabel = "Qaytarish",
                duration = SnackbarDuration.Long
            )
            if (r == SnackbarResult.ActionPerformed) vm.undo()
        }
    }

    if (showFind) {
        FindReplaceDialog(
            units = units,
            onDismiss = { showFind = false },
            onApply = { f, r, mc, ww ->
                val n = vm.replaceInTexts(f, r, mc, ww)
                if (n == 0) vm.toast("Hech narsa topilmadi")
            }
        )
    }
    if (showSpeakers) SpeakersDialog(units = units, vm = vm, onDismiss = { showSpeakers = false })

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Qanday ishlaydi") },
            text = {
                Text(
                    "• Vaqtni bosing — video shu joyga o'tadi.\n" +
                        "• ▶ — shu gapni tanlangan rol bilan darhol tinglash (yana bossangiz to'xtaydi).\n" +
                        "• ⋮ → Qidirish va almashtirish: ism yoki atamani hamma gapda birdan almashtiradi.\n" +
                        "• ⋮ → Spikerlar: gaplarni spikerlarga ajratib, ovozni spiker bo'yicha birdan berasiz.\n" +
                        "• Ommaviy o'zgarishdan keyin pastda «Qaytarish» chiqadi.\n" +
                        "• ⋮ → Loyihani faylga saqlash: tarjima va sozlamalarni boshqa telefonda ochish uchun (video va API kalit kirmaydi).\n" +
                        "• «Keyingi muammo» tugmasi ⚠ belgili gapga o'tkazadi.\n" +
                        "• Mikrofon — o'z ovozingiz bilan yozish.\n" +
                        "• 🔄 — faqat shu gapni qayta tarjima qilish.\n" +
                        "• Ovoz belgisi «—» — ONNX ovozining o'z tembri (o'zgartirilmaydi).\n" +
                        "• ⋮ menyusidan hamma gaplar ovozini (erkak / ayol / bola / avtomatik / asl) birdan o'zgartirish mumkin."
                )
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Tushunarli") } }
        )
    }

    val filtered = remember(units, onlyProblems, onlyNoVoice) {
        units.mapIndexed { i, u -> i to u }.filter { (_, u) ->
            when {
                onlyProblems && onlyNoVoice -> u.check || !u.hasUserVoice
                onlyProblems -> u.check
                onlyNoVoice -> !u.hasUserVoice
                else -> true
            }
        }
    }

    LaunchedEffect(pendingScrollId, filtered) {
        val id = pendingScrollId ?: return@LaunchedEffect
        val pos = filtered.indexOfFirst { it.second.id == id }
        if (pos >= 0) {
            listState.animateScrollToItem(pos)
            pendingScrollId = null
        }
    }

    /** Ko'rinib turgan gapdan keyingi ⚠ belgili gapga o'tadi (oxirida boshidan). */
    fun goToNextProblem() {
        if (units.none { it.check }) { vm.toast("Muammoli gap yo'q ✓"); return }
        val curId = filtered.getOrNull(listState.firstVisibleItemIndex)?.second?.id
        val curIdx = units.indexOfFirst { it.id == curId }
        val target = units.withIndex().firstOrNull { (i, u) -> u.check && i > curIdx }?.value
            ?: units.first { it.check }
        if (onlyNoVoice && !onlyProblems && target.hasUserVoice) onlyNoVoice = false
        pendingScrollId = target.id
    }

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(snackbarState) },
        floatingActionButton = {
            if (pending > 0 && !imeVisible) {
                ExtendedFloatingActionButton(
                    onClick = { goToNextProblem() },
                    icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                    text = { Text("Keyingi muammo ($pending)") }
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tarjimani tahrirlash")
                        Text(
                            buildString {
                                append("${units.size} gap")
                                if (pending > 0) append(" • ⚠ $pending")
                                if (withVoice > 0) append(" • 🎤 $withVoice")
                                if (onlyProblems || onlyNoVoice) append(" • filtr: ${filtered.size}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.saveTranslations(context) }, enabled = !busy) {
                        Icon(Icons.Default.Save, contentDescription = "Saqlash")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menyu")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Qidirish va almashtirish…") },
                                enabled = !busy,
                                onClick = { menu = false; showFind = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Spikerlar…") },
                                enabled = !busy,
                                onClick = { menu = false; showSpeakers = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Loyihani faylga saqlash (.dublaj)") },
                                enabled = !busy,
                                onClick = {
                                    menu = false
                                    projectSaver.launch("loyiha.dublaj")
                                }
                            )
                            HorizontalDivider()
                            Text(
                                "Hamma gaplar uchun ovoz",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("Hammasi erkak") },
                                enabled = !busy,
                                onClick = { menu = false; vm.setAllRoles("erkak") }
                            )
                            DropdownMenuItem(
                                text = { Text("Hammasi ayol") },
                                enabled = !busy,
                                onClick = { menu = false; vm.setAllRoles("ayol") }
                            )
                            DropdownMenuItem(
                                text = { Text("Hammasi bola") },
                                enabled = !busy,
                                onClick = { menu = false; vm.setAllRoles("bola") }
                            )
                            DropdownMenuItem(
                                text = { Text("Avtomatik aniqlangan") },
                                enabled = !busy,
                                onClick = { menu = false; vm.restoreAutoRoles(context) }
                            )
                            DropdownMenuItem(
                                text = { Text("Hammasi asl ovoz (—)") },
                                enabled = !busy,
                                onClick = { menu = false; vm.setAllRoles("") }
                            )
                            DropdownMenuItem(
                                text = { Text("Spiker± (navbatma-navbat)") },
                                enabled = !busy,
                                onClick = { menu = false; vm.autoAssignRoles() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("SRT (o'zbekcha)") },
                                onClick = {
                                    menu = false
                                    val f = SrtExporter.tempFile(context, units, true)
                                    uz.dublaj.util.safeStart(
                                        context,
                                        ExportUtils.shareIntent(context, f, "application/x-subrip", "Subtitr")
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("SRT (asl)") },
                                onClick = {
                                    menu = false
                                    val f = SrtExporter.tempFile(context, units, false)
                                    uz.dublaj.util.safeStart(
                                        context,
                                        ExportUtils.shareIntent(context, f, "application/x-subrip", "Subtitr")
                                    )
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!imeVisible) {
                // Bitta qatorli filtrlar: gorizontal suriladi + video va izoh tugmalari
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = onlyProblems,
                            onClick = { onlyProblems = !onlyProblems },
                            label = { Text("⚠ Muammoli") }
                        )
                        FilterChip(
                            selected = onlyNoVoice,
                            onClick = { onlyNoVoice = !onlyNoVoice },
                            label = { Text("Ovoz yo'q") }
                        )
                        FilterChip(
                            selected = seqMode,
                            onClick = {
                                if (!seqMode) {
                                    seqMode = true
                                    seqIndex = units.indexOfFirst { !it.hasUserVoice }.takeIf { it >= 0 } ?: 0
                                } else {
                                    seqMode = false; seqIndex = -1
                                }
                            },
                            label = { Text(if (seqMode) "Ketma-ket: to'xtat" else "Ketma-ket") }
                        )
                    }
                    if (videoUri != null) {
                        IconButton(onClick = { showVideo = !showVideo }) {
                            Icon(
                                if (showVideo) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = if (showVideo) "Videoni yashirish" else "Videoni ko'rsatish"
                            )
                        }
                    }
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Izoh")
                    }
                }

                // Kichik video: tahrirlashga halal bermaydi, kerak bo'lmasa yashiriladi
                if (showVideo) {
                    videoUri?.let { uri ->
                        VideoPlayer(
                            uri = uri,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            seekMs = seekMs,
                            seekToken = seekTick,
                            maxVideoHeight = (screenHeightDp * 0.2f).coerceIn(100f, 170f).dp
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(filtered, key = { _, pair -> pair.second.id }) { _, pair ->
                    val index = pair.first
                    val unit = pair.second
                    // Dublyajdagidek: gapga keyingi gap boshigacha bo'lgan vaqt ajratiladi
                    val slotSec = ((units.getOrNull(index + 1)?.start ?: (unit.end + 1.0)) - unit.start).coerceAtLeast(0.3)
                    UnitCard(
                        unit = unit,
                        index = index,
                        vm = vm,
                        busy = busy,
                        slotSec = slotSec,
                        showSpeakers = speakersOn,
                        previewing = previewUnit == unit.id,
                        preparing = previewUnit == unit.id && previewPhase?.startsWith("Ovoz tayyor") == true,
                        highlightSeq = seqMode && index == seqIndex,
                        onSeek = { seekMs = (unit.start * 1000).toLong(); seekTick++ },
                        onRecorded = {
                            if (seqMode) {
                                val next = (index + 1 until units.size).firstOrNull { !units[it].hasUserVoice }
                                seqIndex = next ?: -1
                                if (next == null) {
                                    seqMode = false
                                    vm.toast("Ketma-ket o'qish tugadi")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnitCard(
    unit: TranslationUnit,
    index: Int,
    vm: PipelineViewModel,
    busy: Boolean,
    slotSec: Double,
    showSpeakers: Boolean,
    previewing: Boolean,
    preparing: Boolean,
    highlightSeq: Boolean,
    onSeek: () -> Unit,
    onRecorded: () -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var text by remember(unit.id, unit.uzText) { mutableStateOf(unit.uzText) }
    val targetSec = (unit.end - unit.start).coerceAtLeast(0.5)
    var recording by remember(unit.id) { mutableStateOf(false) }
    var recProgress by remember(unit.id) { mutableStateOf(0.0) }
    var recorder by remember(unit.id) { mutableStateOf<SegmentRecorder?>(null) }
    val scope = rememberCoroutineScope()
    val bringIntoView = remember { BringIntoViewRequester() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) vm.toast("Mikrofon ruxsati kerak")
    }

    fun startRec() {
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!has) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        scope.launch {
            val file = withContext(Dispatchers.IO) { vm.segmentVoiceFile(context, unit.id) }
            val maxSec = targetSec + 0.4
            val r = SegmentRecorder(file, 22050, maxSec)
            recorder = r
            recording = true
            recProgress = 0.0
            r.start(
                onTick = { e, _ -> recProgress = e },
                onDone = { ok ->
                    recording = false
                    recorder = null
                    if (ok) {
                        vm.setUserVoice(index, true)
                        vm.toast("Ovoz saqlandi (#${unit.id})")
                        onRecorded()
                    } else vm.toast("Yozib bo'lmadi")
                }
            )
        }
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = when {
                highlightSeq -> cs.primaryContainer.copy(alpha = 0.5f)
                unit.check -> cs.errorContainer.copy(alpha = 0.55f)
                unit.hasUserVoice -> cs.tertiaryContainer.copy(alpha = 0.45f)
                else -> cs.surface
            }
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = cs.primaryContainer,
                    modifier = Modifier.clickable { onSeek() }
                ) {
                    Text(
                        "${fmtTime(unit.start)} – ${fmtTime(unit.end)}  (${"%.1f".format(targetSec)} s)",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("#${unit.id}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (unit.check) Icon(Icons.Default.Warning, null, tint = cs.error, modifier = Modifier.size(18.dp))
                // Qayta tarjima
                IconButton(onClick = { vm.retranslateUnit(context, index) }, enabled = !busy && !recording) {
                    Icon(Icons.Default.Refresh, contentDescription = "Qayta tarjima")
                }
                // TTS preview: shu matn va shu rol bilan, dublyajdagidek
                FilledTonalIconButton(
                    onClick = { vm.togglePreview(context, unit.id, text, unit.voiceRole, slotSec) },
                    enabled = !busy && !recording
                ) {
                    when {
                        preparing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        previewing -> Icon(Icons.Default.Stop, contentDescription = "To'xtatish")
                        else -> Icon(Icons.Default.PlayArrow, contentDescription = "Tinglash")
                    }
                }
            }

            if (unit.check) {
                Text(
                    unit.issue ?: "Tekshirish kerak — matnni tuzating",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "—", "erkak" to "Erkak", "ayol" to "Ayol", "bola" to "Bola").forEach { (id, label) ->
                    FilterChip(
                        selected = unit.voiceRole == id,
                        onClick = { vm.setVoiceRole(index, id) },
                        label = { Text(label) }
                    )
                }
            }

            if (showSpeakers) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Spiker:", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    listOf(0 to "—", 1 to "1", 2 to "2", 3 to "3", 4 to "4").forEach { (n, label) ->
                        FilterChip(
                            selected = unit.speaker == n,
                            onClick = { vm.setSpeaker(index, n) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = cs.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth().clickable { onSeek() }
            ) {
                Text(
                    unit.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    vm.updateUzText(index, it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoView)
                    .onFocusEvent { f ->
                        if (f.isFocused) scope.launch {
                            delay(350)   // klaviatura ochilib bo'lishini kutamiz
                            bringIntoView.bringIntoView()
                        }
                    },
                shape = MaterialTheme.shapes.medium,
                label = { Text("O'zbekcha") }
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (recording) {
                    FilledTonalButton(onClick = { recorder?.stop() }) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("To'xtat ${"%.1f".format(recProgress)}/${"%.1f".format(targetSec)}s")
                    }
                } else {
                    OutlinedButton(onClick = { startRec() }) {
                        Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (unit.hasUserVoice) "Qayta yozish" else "O'zim o'qiyman")
                    }
                }
                if (unit.hasUserVoice && !recording) {
                    IconButton(onClick = { vm.playUserVoice(context, unit.id) }) {
                        Icon(Icons.Default.Hearing, contentDescription = "Yozilgan ovozni tinglash")
                    }
                    IconButton(onClick = { vm.clearUserVoice(context, index, unit.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Ovozni o'chirish")
                    }
                    Text("🎤", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (recording) {
                LinearProgressIndicator(
                    progress = { (recProgress / targetSec).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Maks. ~${"%.1f".format(targetSec + 0.4)} s — gapni shu vaqtda o'qing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}


@Composable
private fun FindReplaceDialog(
    units: List<TranslationUnit>,
    onDismiss: () -> Unit,
    onApply: (find: String, repl: String, matchCase: Boolean, wholeWord: Boolean) -> Unit
) {
    var find by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var whole by remember { mutableStateOf(false) }
    val count = remember(find, matchCase, whole, units) { FindReplace.countUnits(units, find, matchCase, whole) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Qidirish va almashtirish") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = find, onValueChange = { find = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Qidirilayotgan so'z") }
                )
                OutlinedTextField(
                    value = repl, onValueChange = { repl = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Yangi so'z (bo'sh = o'chirish)") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = matchCase, onCheckedChange = { matchCase = it })
                    Text("Katta-kichik harfni farqlash")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = whole, onCheckedChange = { whole = it })
                    Text("Faqat butun so'z")
                }
                Text(
                    if (find.isEmpty()) "O'zbekcha matnlarda qidiriladi." else "$count ta gapda topildi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = find.isNotEmpty() && count > 0,
                onClick = { onApply(find, repl, matchCase, whole); onDismiss() }
            ) { Text("Hammasini almashtirish") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bekor") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakersDialog(
    units: List<TranslationUnit>,
    vm: PipelineViewModel,
    onDismiss: () -> Unit
) {
    val groups = units.filter { it.speaker > 0 }.groupBy { it.speaker }.toSortedMap()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spikerlar") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (groups.isEmpty()) {
                    Text(
                        "Hali spiker belgilanmagan. «Pauza bo'yicha ajratish» gaplarni spikerlarga taxminan bo'ladi " +
                            "(pauza uzun bo'lsa, spiker almashadi). Keyin har bir gapdagi 1/2/3/4 tugmalari bilan tuzatasiz " +
                            "va ovozni spiker bo'yicha birdan berasiz.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                for ((n, list) in groups) {
                    val current = list.map { it.voiceRole }.distinct().singleOrNull()
                    Text("Spiker $n · ${list.size} gap", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "—", "erkak" to "Erkak", "ayol" to "Ayol", "bola" to "Bola").forEach { (id, label) ->
                            FilterChip(
                                selected = current == id,
                                onClick = { vm.setRoleForSpeaker(n, id) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.detectSpeakers(2) }) { Text("Pauza bo'yicha: 2 spiker") }
                    OutlinedButton(onClick = { vm.detectSpeakers(3) }) { Text("3 spiker") }
                    if (groups.isNotEmpty()) TextButton(onClick = { vm.clearSpeakers() }) { Text("Belgilarni o'chirish") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Yopish") } }
    )
}
