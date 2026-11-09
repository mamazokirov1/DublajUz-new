package uz.dublaj.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.dublaj.ui.components.fmtTime
import uz.dublaj.util.ExportUtils
import uz.dublaj.util.SegmentRecorder
import uz.dublaj.util.SrtExporter
import uz.dublaj.ui.viewmodel.PipelineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTranslationScreen(
    vm: PipelineViewModel,
    onBack: () -> Unit
) {
    val units by vm.translationUnits.collectAsState()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val pending = units.count { it.check }
    val withVoice = units.count { it.hasUserVoice }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                                if (pending > 0) append(" • ⚠ $pending tekshirilsin")
                                if (withVoice > 0) append(" • 🎤 $withVoice ovoz")
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
                    FilledTonalButton(onClick = { vm.saveTranslations(context) }) {
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
                                    context.startActivity(
                                        ExportUtils.shareIntent(context, f, "application/x-subrip", "Subtitr")
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("SRT (asl)") },
                                onClick = {
                                    menu = false
                                    val f = SrtExporter.tempFile(context, units, false)
                                    context.startActivity(
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
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Erkak/ayol/bola — oddiy izoh (ixtiyoriy). Mikrofon — o‘z ovozingiz; yozilgan gaplar dublajda shu ovoz bilan chiqadi, qolganlari ONNX ovozida.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
            itemsIndexed(units, key = { _, u -> u.id }) { index, unit ->
                var text by remember(unit.id, unit.uzText) { mutableStateOf(unit.uzText) }
                val targetSec = (unit.end - unit.start).coerceAtLeast(0.5)
                var recording by remember(unit.id) { mutableStateOf(false) }
                var recProgress by remember(unit.id) { mutableStateOf(0.0) }
                var recorder by remember(unit.id) { mutableStateOf<SegmentRecorder?>(null) }

                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        // restart record after grant
                    } else {
                        vm.toast("Mikrofon ruxsati kerak")
                    }
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
                                } else {
                                    vm.toast("Yozib bo‘lmadi")
                                }
                            }
                        )
                    }
                }

                fun stopRec() {
                    recorder?.stop()
                }

                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            unit.check -> cs.errorContainer.copy(alpha = 0.55f)
                            unit.hasUserVoice -> cs.tertiaryContainer.copy(alpha = 0.45f)
                            else -> cs.surface
                        }
                    )
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = MaterialTheme.shapes.small, color = cs.primaryContainer) {
                                Text(
                                    "${fmtTime(unit.start)} – ${fmtTime(unit.end)}  (${"%.1f".format(targetSec)} s)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "#${unit.id}",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (unit.check) {
                                Icon(Icons.Default.Warning, null, tint = cs.error, modifier = Modifier.size(18.dp))
                            }
                            FilledTonalIconButton(onClick = { vm.previewVoice(context, text) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Tinglash")
                            }
                        }

                        if (unit.check) {
                            Text(
                                unit.issue ?: "Tekshirish kerak — matnni tuzating",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.error
                            )
                        }

                        // Ovoz roli (izoh)
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
                            modifier = Modifier.fillMaxWidth()
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
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            label = { Text("O'zbekcha") }
                        )

                        // Mikrofon
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (recording) {
                                FilledTonalButton(onClick = { stopRec() }) {
                                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("To‘xtatish ${"%.1f".format(recProgress)} / ${"%.1f".format(targetSec)} s")
                                }
                            } else {
                                OutlinedButton(onClick = { startRec() }) {
                                    Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (unit.hasUserVoice) "Qayta yozish" else "O‘zim o‘qiyman")
                                }
                            }
                            if (unit.hasUserVoice && !recording) {
                                IconButton(onClick = {
                                    vm.clearUserVoice(context, index, unit.id)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Ovozni o‘chirish")
                                }
                                Text("🎤 yozilgan", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                            }
                        }
                        if (recording) {
                            LinearProgressIndicator(
                                progress = { (recProgress / targetSec).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Maksimal ~${"%.1f".format(targetSec + 0.4)} s (video segmenti). Gapni shu vaqtda o‘qing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
