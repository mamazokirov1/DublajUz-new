package uz.dublaj.ui.screens

import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uz.dublaj.data.PipelineState
import uz.dublaj.pipeline.MontageClip
import uz.dublaj.pipeline.MontageProject
import uz.dublaj.pipeline.MontageRender
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.WorkDir
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Montajdagi klip: joyi (at) Compose holatida, surishda real vaqtda yangilanadi. */
private class UiClip(val clip: MontageClip) {
    var at by mutableStateOf(clip.atSec)
}

private fun fmt(ms: Long): String {
    val s = ms.coerceAtLeast(0L) / 1000.0
    val m = (s / 60).toInt()
    return "%02d:%04.1f".format(m, s - m * 60)
}

/**
 * Montaj: video va dublyaj ovozi bir vaqtda chaladi; vaqt chizig'ida dublyaj bloklarini barmoq bilan surasiz.
 * Blokni 1 soniya bosib turing, keyin suring. Aniq joylash: vaqt chizig'ini suring va «Shu yerga» ni bosing.
 */
@Composable
fun MontageScreen(vm: PipelineViewModel, onBack: () -> Unit, onExported: (File) -> Unit) {
    val ctx = LocalContext.current
    val cfg by vm.config.collectAsState()
    var project by remember { mutableStateOf<MontageProject?>(null) }
    var status by remember { mutableStateOf("Montaj yuklanmoqda…") }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val p = MontageProject.load(WorkDir(ctx.applicationContext))
                    ?: throw IllegalStateException("Montaj uchun dublyaj topilmadi. Avval videoni dublyaj qiling.")
                if (!File(p.videoPath).isFile) {
                    throw IllegalStateException("Asl video topilmadi. Videoni qayta tanlab dublyaj qiling.")
                }
                if (!p.previewWav.isFile) {
                    status = "Ovoz aralashtirilmoqda…"
                    MontageRender.buildPreview(p, cfg) { progress = it }
                }
                project = p
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
    }

    val p = project
    if (p != null) {
        MontageEditor(p, vm, onBack, onExported)
        return
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (error != null) {
            Text(error ?: "", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Orqaga") }
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(status)
            if (progress > 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MontageEditor(p: MontageProject, vm: PipelineViewModel, onBack: () -> Unit, onExported: (File) -> Unit) {
    val ctx = LocalContext.current
    val cfg by vm.config.collectAsState()
    val vmState by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val durMs = (p.duration * 1000).toLong()

    val clips = remember(p) { p.clips.map { UiClip(it) }.toMutableStateList() }

    // Video (ovozsiz) va tayyor aralashma (ovoz) — ikki pleyer, video asosiy soat
    val vPlayer = remember(p) {
        ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)).build().apply {
            volume = 0f
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(p.videoPath))))
            playWhenReady = false
            prepare()
        }
    }
    val aPlayer = remember(p) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(p.previewWav)))
            playWhenReady = false
            prepare()
        }
    }

    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0L) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    var exporting by remember { mutableStateOf(false) }
    val undo = remember { mutableStateListOf<Pair<Int, Double>>() }
    val patchLock = remember { Mutex() }
    val scroll = rememberScrollState()
    // Compose o'lchami ~262 000 px dan oshsa ilova qulaydi: uzun videoda masshtab cheklanadi
    val basePx = with(density) { 70.dp.toPx() }
    val zoomMax = min(6f, 240_000f / (max(1.0, p.duration).toFloat() * basePx))
    val zoomMin = min(0.25f, zoomMax)
    val pps = basePx * zoom.coerceIn(zoomMin, zoomMax)          // 1 soniya = pps piksel

    DisposableEffect(p) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(0.5f, 2.4f)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playing = false
            }
        }
        vPlayer.addListener(l)
        onDispose {
            vPlayer.removeListener(l)
            try { vPlayer.release() } catch (_: Exception) { }
            try { aPlayer.release() } catch (_: Exception) { }
        }
    }

    // Ijro: ovoz videoga ergashadi; og'ish 70 ms dan oshsa tuzatiladi
    LaunchedEffect(playing) {
        if (playing) {
            if (vPlayer.currentPosition >= durMs - 100) { vPlayer.seekTo(0); aPlayer.seekTo(0); posMs = 0 }
            aPlayer.seekTo(vPlayer.currentPosition)
            vPlayer.playWhenReady = true
            aPlayer.playWhenReady = true
            var tick = 0
            while (playing) {
                delay(50)
                val vp = vPlayer.currentPosition
                posMs = vp
                tick++
                if (tick % 5 == 0 && abs(aPlayer.currentPosition - vp) > 70) aPlayer.seekTo(vp + 25)
            }
        } else {
            vPlayer.playWhenReady = false
            aPlayer.playWhenReady = false
        }
    }

    // Ijro paytida vaqt chizig'i playhead (markaz) ostidan siljiydi (programmatik: isScrollInProgress bo'lmaydi)
    LaunchedEffect(posMs, pps) {
        if (!scroll.isScrollInProgress) {
            val target = (posMs / 1000.0 * pps).toInt().coerceIn(0, scroll.maxValue)
            val delta = target - scroll.value
            if (delta != 0) scroll.dispatchRawDelta(delta.toFloat())
        }
    }

    // Foydalanuvchi vaqt chizig'ini surganda video/ovoz shu joyga o'tadi
    LaunchedEffect(pps) {
        var lastSeek = 0L
        snapshotFlow { scroll.value }.collect { v ->
            if (scroll.isScrollInProgress) {
                if (playing) playing = false
                val ms = (v / pps * 1000.0).toLong().coerceIn(0L, durMs)
                posMs = ms
                val now = SystemClock.elapsedRealtime()
                if (now - lastSeek > 70) {
                    lastSeek = now
                    vPlayer.seekTo(ms)
                    aPlayer.seekTo(ms)
                }
            }
        }
    }
    LaunchedEffect(pps) {
        snapshotFlow { scroll.isScrollInProgress }.collect { inProgress ->
            if (!inProgress && !playing) {
                val ms = (scroll.value / pps * 1000.0).toLong().coerceIn(0L, durMs)
                if (abs(ms - posMs) > 30) {
                    posMs = ms
                    vPlayer.seekTo(ms)
                    aPlayer.seekTo(ms)
                }
            }
        }
    }

    // Eksport tugashini kutish
    LaunchedEffect(vmState) {
        if (exporting) {
            when (val s = vmState) {
                is PipelineState.Success -> { exporting = false; onExported(File(s.outputPath)) }
                is PipelineState.Error -> exporting = false
                else -> {}
            }
        }
    }

    /** Klipni yangi joyga qo'yadi, faylga saqlaydi va tinglash faylining o'sha qismini yangilaydi. */
    fun commit(uc: UiClip, oldAt: Double, rawNew: Double, record: Boolean = true) {
        val d = uc.clip.durSec(p.sr)
        val newAt = rawNew.coerceIn(0.0, max(0.0, p.duration - d))
        uc.at = newAt
        if (abs(newAt - oldAt) < 1e-4) return
        uc.clip.atSec = newAt
        if (record) undo.add(uc.clip.id to oldAt)
        scope.launch(Dispatchers.IO) {
            patchLock.withLock {
                try {
                    p.save()
                    MontageRender.patchPreview(p, cfg, min(oldAt, newAt) - 0.1, max(oldAt, newAt) + d + 1.0)
                } catch (e: Exception) {
                    timber.log.Timber.w(e, "Montaj: tinglash yangilanmadi")
                }
            }
            withContext(Dispatchers.Main) { aPlayer.seekTo(vPlayer.currentPosition) }   // bufer yangilansin
        }
    }

    val sel = clips.firstOrNull { it.clip.id == selected }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("Montaj") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga") }
                },
                actions = {
                    Button(
                        enabled = !busy && !exporting,
                        onClick = {
                            playing = false
                            exporting = true
                            vm.exportMontage(ctx)
                        }
                    ) { Text("Saqlash") }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ---- video
            Box(
                Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { c -> TextureView(c).also { vPlayer.setVideoTextureView(it) } },
                    modifier = Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)
                )
            }

            // ---- boshqaruv
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledIconButton(onClick = { playing = !playing }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Ijro")
                }
                Text(
                    "${fmt(posMs)} / ${fmt(durMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { zoom = (zoom / 1.5f).coerceIn(zoomMin, zoomMax) },
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) { Text("−") }
                OutlinedButton(
                    onClick = { zoom = (zoom * 1.5f).coerceIn(zoomMin, zoomMax) },
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) { Text("+") }
            }

            // ---- tanlangan blok bilan ishlash
            if (sel != null) {
                val off = sel.at - sel.clip.origStart
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        sel.clip.text,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Asl nutqqa nisbatan: ${if (off >= 0) "+" else ""}${"%.2f".format(off)} s",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (abs(off) < 0.05) cs.primary else cs.tertiary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { commit(sel, sel.at, sel.at - 0.05) },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("◀ 0.05") }
                        OutlinedButton(
                            onClick = { commit(sel, sel.at, sel.at + 0.05) },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("0.05 ▶") }
                        FilledTonalButton(
                            onClick = { commit(sel, sel.at, posMs / 1000.0) },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("Shu yerga") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { commit(sel, sel.at, sel.clip.origStart) },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("Asl boshiga") }
                        OutlinedButton(
                            onClick = { commit(sel, sel.at, sel.clip.at0Sec) },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("Dastlabki") }
                        OutlinedButton(
                            enabled = undo.isNotEmpty(),
                            onClick = {
                                val (id, old) = undo.removeAt(undo.lastIndex)
                                clips.firstOrNull { it.clip.id == id }?.let { commit(it, it.at, old, record = false) }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("↶ Qaytarish") }
                    }
                }
            } else {
                Text(
                    "Dublyaj blokini bosing. Surish uchun 1 soniya bosib turing.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }

            // ---- vaqt chizig'i (playhead markazda, chiziq suriladi)
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(116.dp).background(cs.surfaceVariant.copy(alpha = 0.35f))
            ) {
                val halfW = maxWidth / 2
                fun dpOf(sec: Double): Dp = with(density) { (sec * pps).toFloat().toDp() }
                val totalDp = dpOf(p.duration)

                Row(Modifier.fillMaxSize().horizontalScroll(scroll)) {
                    Spacer(Modifier.width(halfW))
                    Box(Modifier.width(totalDp).fillMaxHeight()) {
                        // chizg'ich
                        val step = when { pps >= 150f -> 1; pps >= 70f -> 5; pps >= 35f -> 10; else -> 30 }
                        var t = 0
                        while (t <= p.duration) {
                            Box(Modifier.offset(x = dpOf(t.toDouble())).width(1.dp).height(6.dp).background(cs.outline))
                            Text(
                                "${t / 60}:${"%02d".format(t % 60)}",
                                fontSize = 9.sp, color = cs.onSurfaceVariant,
                                modifier = Modifier.offset(x = dpOf(t.toDouble()) + 2.dp, y = 4.dp)
                            )
                            t += step
                        }
                        // asl nutq (kulrang)
                        for (uc in clips) {
                            val w = dpOf(max(0.05, uc.clip.origEnd - uc.clip.origStart))
                            Box(
                                Modifier.offset(x = dpOf(uc.clip.origStart), y = 24.dp).width(w).height(14.dp)
                                    .background(cs.outline.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
                            )
                        }
                        // dublyaj bloklari
                        val maxAtOf = { uc: UiClip -> max(0.0, p.duration - uc.clip.durSec(p.sr)) }
                        for (uc in clips) key(uc.clip.id) {
                            val isSel = uc.clip.id == selected
                            val w = dpOf(max(0.08, uc.clip.durSec(p.sr)))
                            var dragStart by remember(uc) { mutableStateOf(0.0) }
                            var acc by remember(uc) { mutableStateOf(0f) }
                            Box(
                                Modifier
                                    .offset(x = dpOf(uc.at), y = 44.dp)
                                    .width(w).height(52.dp)
                                    .background(if (isSel) cs.tertiary else cs.primary, RoundedCornerShape(6.dp))
                                    .border(
                                        if (isSel) BorderStroke(2.dp, Color.White) else BorderStroke(0.dp, Color.Transparent),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .pointerInput(uc.clip.id) {
                                        detectTapGestures(onTap = { selected = uc.clip.id })
                                    }
                                    .pointerInput(uc.clip.id, pps) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                selected = uc.clip.id
                                                playing = false
                                                dragStart = uc.clip.atSec
                                                acc = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                acc += amount.x
                                                var cand = dragStart + acc / pps
                                                // asl boshiga yopishish (±0.1 s)
                                                if (abs(cand - uc.clip.origStart) < 0.1) cand = uc.clip.origStart
                                                uc.at = cand.coerceIn(0.0, maxAtOf(uc))
                                            },
                                            onDragEnd = { commit(uc, dragStart, uc.at) },
                                            onDragCancel = { uc.at = dragStart }
                                        )
                                    }
                                    .padding(4.dp)
                            ) {
                                Text(
                                    uc.clip.text,
                                    color = if (isSel) cs.onTertiary else cs.onPrimary,
                                    fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Clip
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(halfW))
                }
                // playhead
                Box(
                    Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(Color.White)
                )
            }
        }
    }

    // Eksport paytida to'siq
    if (exporting || (busy && vmState is PipelineState.Running)) {
        val r = vmState as? PipelineState.Running
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Video yig'ilmoqda") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r?.message?.ifBlank { null } ?: "Tayyorlanmoqda…")
                    if (r != null && r.progress > 0f) {
                        LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Ilovani fonga olishingiz mumkin: jarayon bildirishnomada davom etadi.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.cancel() }) { Text("Bekor qilish") } }
        )
    }
}
