package uz.dublaj.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.dublaj.data.TranslationUnit
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
    val videoUri by vm.videoUri.collectAsState()
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current
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

    Scaffold(
        containerColor = cs.background,
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
                    FilledTonalButton(onClick = { vm.saveTranslations(context) }, enabled = !busy) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Saqlash")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menyu")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("SRT (o'zbekcha)") },
                                onClick = {
                                    menu = false
                                    val f = SrtExporter.tempFile(context, units, true)
                                    uz.dublaj.util.safeStart(context, 
                                        ExportUtils.shareIntent(context, f, "application/x-subrip", "Subtitr")
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("SRT (asl)") },
                                onClick = {
                                    menu = false
                                    val f = SrtExporter.tempFile(context, units, false)
                                    uz.dublaj.util.safeStart(context, 
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
                // Video preview + seek
                videoUri?.let { uri ->
                    VideoPlayer(
                        uri = uri,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        seekMs = seekMs,
                        seekToken = seekTick,
                        maxVideoHeight = 220.dp
                    )
                }

                // Filters + sequential
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
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
                    TextButton(onClick = { vm.autoAssignRoles() }) { Text("Spiker±") }
                    if (!seqMode) {
                        TextButton(onClick = {
                            seqMode = true
                            seqIndex = units.indexOfFirst { !it.hasUserVoice }.takeIf { it >= 0 } ?: 0
                        }) { Text("Ketma-ket") }
                    } else {
                        TextButton(onClick = { seqMode = false; seqIndex = -1 }) { Text("To'xtat") }
                    }
                }

                // Hamma gaplar uchun ovozni birdan o'zgartirish
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Text(
                        "Hamma gaplar uchun ovoz",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        AssistChip(onClick = { vm.setAllRoles("erkak") }, enabled = !busy, label = { Text("Hammasi erkak") })
                        AssistChip(onClick = { vm.setAllRoles("ayol") }, enabled = !busy, label = { Text("Hammasi ayol") })
                        AssistChip(onClick = { vm.setAllRoles("bola") }, enabled = !busy, label = { Text("Hammasi bola") })
                        AssistChip(onClick = { vm.restoreAutoRoles(context) }, enabled = !busy, label = { Text("Avtomatik aniqlangan") })
                        AssistChip(onClick = { vm.setAllRoles("") }, enabled = !busy, label = { Text("Hammasi asl (—)") })
                    }
                    Text(
                        "«—» = ONNX ovozining o'z tembri (o'zgartirilmaydi). Har bir gapni alohida ham sozlash mumkin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }

                Text(
                    "Vaqtni bosing → video shu joyga o'tadi. Mikrofon → o'z ovozi. 🔄 → faqat shu gapni qayta tarjima.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(filtered, key = { _, pair -> pair.second.id }) { _, pair ->
                    val index = pair.first
                    val unit = pair.second
                    UnitCard(
                        unit = unit,
                        index = index,
                        vm = vm,
                        busy = busy,
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
                // TTS preview
                FilledTonalIconButton(onClick = { vm.previewVoice(context, text) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "TTS tinglash")
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
