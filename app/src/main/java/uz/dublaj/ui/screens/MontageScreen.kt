package uz.dublaj.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uz.dublaj.data.PipelineState
import uz.dublaj.pipeline.ClipRevoicer
import uz.dublaj.pipeline.MontageClip
import uz.dublaj.pipeline.MontageProject
import uz.dublaj.pipeline.MontageRender
import uz.dublaj.translate.splitApiKeys
import uz.dublaj.tts.VitsSynthesizer
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.SegmentRecorder
import uz.dublaj.util.WorkDir
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Montajdagi klip: joyi (at) Compose holatida, surishda real vaqtda yangilanadi. */
private class UiClip(val clip: MontageClip, sr: Int) {
    var at by mutableStateOf(clip.atSec)
    /** Klip davomiyligi (soniya): qayta ovozlanganda o'zgaradi, blok kengligi shundan. */
    var dur by mutableStateOf(clip.durSec(sr))
    var text by mutableStateOf(clip.text)
}

/** Qaytarish (undo) yozuvlari: surish yoki qayta ovozlash. */
private sealed class MontageUndo {
    class Move(val id: Int, val oldAt: Double) : MontageUndo()
    class Revoice(
        val id: Int, val backup: File, val oldSamples: Int, val oldSpeed: Float,
        val oldText: String, val oldRole: String
    ) : MontageUndo()
}

/** Qayta ovozlash tugagach UI uchun natija. */
private class RevoiceDone(val prep: ClipRevoicer.Prepared, val backup: File)

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

    val clips = remember(p) { p.clips.map { UiClip(it, p.sr) }.toMutableStateList() }

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
    val undo = remember { mutableStateListOf<MontageUndo>() }
    var showRevoice by remember { mutableStateOf(false) }
    var revoicing by remember { mutableStateOf(false) }
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
            undo.forEach { if (it is MontageUndo.Revoice) it.backup.delete() }
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
        if (record) undo.add(MontageUndo.Move(uc.clip.id, oldAt))
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

    /** ExoPlayer yangilangan preview.wav ni qayta o'qisin. */
    fun reloadAudio() {
        val pos = vPlayer.currentPosition
        aPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(p.previewWav)), pos)
        aPlayer.prepare()
    }

    /** Tanlangan bitta gapni qayta ovozlaydi: faqat shu klip almashadi, tinglash fayli shu joyda yangilanadi. */
    fun revoice(uc: UiClip, text: String, role: String, engine: String, ownWav: File?) {
        if (revoicing) return
        playing = false
        revoicing = true
        val clip = uc.clip
        val nextStart = clips.filter { it !== uc && it.at > uc.at + 0.01 }.minOfOrNull { it.at } ?: p.duration
        val slot = max(0.3, nextStart - uc.at - cfg.marginS.toDouble())
        val oldSamples = clip.samples
        val oldSpeed = clip.speed
        val oldText = clip.text
        val oldRole = clip.role
        val oldDur = clip.durSec(p.sr)
        scope.launch {
            try {
                val done = withContext(Dispatchers.IO) {
                    val prep = ClipRevoicer.synth(ctx, p, clip, text, role, engine, cfg, ownWav, slot)
                    withContext(NonCancellable) {
                        patchLock.withLock {
                            val backup = File(p.dir, "undo_${clip.id}_${System.nanoTime()}.pcm")
                            ClipRevoicer.apply(p, clip, prep, text, role, backup)
                            MontageRender.patchPreview(
                                p, cfg, clip.atSec - 0.1,
                                clip.atSec + max(oldDur, clip.durSec(p.sr)) + 1.0
                            )
                            RevoiceDone(prep, backup)
                        }
                    }
                }
                uc.dur = clip.durSec(p.sr)
                uc.text = text
                undo.add(MontageUndo.Revoice(clip.id, done.backup, oldSamples, oldSpeed, oldText, oldRole))
                reloadAudio()
                val msg = buildString {
                    append("Qayta ovozlandi ✓")
                    if (done.prep.speed > 1.01f) append(" (${"%.2f".format(done.prep.speed)}× tezlashtirildi)")
                    if (done.prep.overflowSec > 0.05) {
                        append(" — keyingi blokka ${"%.1f".format(done.prep.overflowSec)} s o'tib ketdi, uni suring")
                    }
                }
                vm.toast(msg)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                timber.log.Timber.w(e, "Qayta ovozlash")
                vm.toast(e.message ?: "Qayta ovozlab bo'lmadi")
            } finally {
                revoicing = false
                ownWav?.delete()
            }
        }
    }

    /** Oxirgi amalni qaytaradi (surish yoki qayta ovozlash). */
    fun undoLast() {
        if (undo.isEmpty()) return
        when (val u = undo.removeAt(undo.lastIndex)) {
            is MontageUndo.Move ->
                clips.firstOrNull { it.clip.id == u.id }?.let { commit(it, it.at, u.oldAt, record = false) }
            is MontageUndo.Revoice -> {
                val uc = clips.firstOrNull { it.clip.id == u.id } ?: return
                scope.launch(Dispatchers.IO) {
                    patchLock.withLock {
                        try {
                            val oldDur = uc.clip.durSec(p.sr)
                            ClipRevoicer.restore(p, uc.clip, u.backup, u.oldSamples, u.oldSpeed, u.oldText, u.oldRole)
                            u.backup.delete()
                            MontageRender.patchPreview(
                                p, cfg, uc.clip.atSec - 0.1,
                                uc.clip.atSec + max(oldDur, uc.clip.durSec(p.sr)) + 1.0
                            )
                        } catch (e: Exception) {
                            timber.log.Timber.w(e, "Montaj: qayta ovozlashni qaytarib bo'lmadi")
                        }
                    }
                    withContext(Dispatchers.Main) {
                        uc.dur = uc.clip.durSec(p.sr)
                        uc.text = uc.clip.text
                        reloadAudio()
                    }
                }
            }
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
                        sel.text,
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
                            onClick = { undoLast() },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("↶ Qaytarish") }
                    }
                    Button(
                        enabled = !busy && !revoicing && !exporting,
                        onClick = { playing = false; showRevoice = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🔊 Shu ovozni qayta dublyaj qilish") }
                    Text(
                        "Usulni tanlaysiz: ONNX, Gemini yoki o'z ovozingiz (aytib yozasiz).",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "Dublyaj blokini bosing: suring yoki «qayta dublyaj» (ONNX / Gemini / o'z ovozim). Surish uchun 1 soniya bosib turing.",
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
                        val maxAtOf = { uc: UiClip -> max(0.0, p.duration - uc.dur) }
                        for (uc in clips) key(uc.clip.id) {
                            val isSel = uc.clip.id == selected
                            val w = dpOf(max(0.08, uc.dur))
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
                                    uc.text,
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

    // Qayta ovozlash oynasi
    if (showRevoice && sel != null) {
        val nextStart = clips.filter { it !== sel && it.at > sel.at + 0.01 }.minOfOrNull { it.at } ?: p.duration
        val slot = max(0.3, nextStart - sel.at - cfg.marginS.toDouble())
        val ownFile = remember(p) { File(p.dir, "rec_own.wav") }
        RevoiceDialog(
            initialText = sel.clip.text,
            initialRole = sel.clip.role.ifBlank { if (cfg.baseVoiceFemale) "ayol" else "erkak" },
            initialEngine = if (cfg.voiceEngine == ClipRevoicer.ENGINE_GEMINI) ClipRevoicer.ENGINE_GEMINI else ClipRevoicer.ENGINE_ONNX,
            slotSec = slot,
            geminiReady = splitApiKeys(cfg.geminiKey).isNotEmpty(),
            onnxReady = remember { VitsSynthesizer.isReady(WorkDir(ctx.applicationContext)) },
            ownFile = ownFile,
            onToast = { vm.toast(it) },
            onDismiss = { showRevoice = false },
            onApply = { text, role, engine, own ->
                showRevoice = false
                revoice(sel, text.trim(), role, engine, if (own) ownFile else null)
            }
        )
    }
    if (revoicing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Qayta ovozlanmoqda") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Faqat tanlangan blok ovozlanadi. Bir necha soniya kuting…", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}
        )
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


/**
 * Tanlangan blokni qayta ovozlash oynasi: matn, ovoz (erkak/ayol/bola yoki o'z ovozim) va dvigatel (ONNX / Gemini).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RevoiceDialog(
    initialText: String,
    initialRole: String,
    initialEngine: String,
    slotSec: Double,
    geminiReady: Boolean,
    onnxReady: Boolean,
    ownFile: File,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: (text: String, role: String, engine: String, own: Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initialText) }
    var role by remember { mutableStateOf(if (initialRole in listOf("erkak", "ayol", "bola")) initialRole else "erkak") }
    var own by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf(initialEngine) }
    var recording by remember { mutableStateOf(false) }
    var recSec by remember { mutableStateOf(0.0) }
    var recorded by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<SegmentRecorder?>(null) }

    // Eski yozuv qolib ketmasin; oyna yopilsa yozish to'xtaydi
    DisposableEffect(Unit) {
        try { ownFile.delete() } catch (_: Exception) { }
        onDispose {
            val r = recorder
            if (r != null) Thread { r.stop() }.start()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onToast("Mikrofon ruxsati kerak")
    }

    fun startRec() {
        val has = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!has) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        recorded = false
        recSec = 0.0
        val r = SegmentRecorder(ownFile, 22050, 60.0)
        recorder = r
        recording = true
        r.start(
            onTick = { e, _ -> recSec = e },
            onDone = { ok ->
                recording = false
                recorder = null
                recorded = ok
                if (!ok) onToast("Yozib bo'lmadi")
            }
        )
    }

    val engineReady = if (engine == ClipRevoicer.ENGINE_GEMINI) geminiReady else onnxReady
    val canApply = !recording && if (own) recorded else (text.isNotBlank() && engineReady)

    AlertDialog(
        onDismissRequest = { if (!recording) onDismiss() },
        title = { Text("Qayta ovozlash") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Faqat shu blok qayta ovozlanadi, joyi o'zgarmaydi. Ajratilgan vaqt: ${"%.1f".format(slotSec)} s " +
                        "(sig'masa avtomatik tezlashtiriladi).",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Matn") },
                    minLines = 2, maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Ovoz", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("erkak" to "Erkak", "ayol" to "Ayol", "bola" to "Bola").forEach { (id, label) ->
                        FilterChip(
                            selected = !own && role == id,
                            enabled = !recording,
                            onClick = { own = false; role = id },
                            label = { Text(label) }
                        )
                    }
                    FilterChip(
                        selected = own,
                        enabled = !recording,
                        onClick = { own = true },
                        label = { Text("O'zim") }
                    )
                }
                if (!own) {
                    Text("Ovoz manbai", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = engine == ClipRevoicer.ENGINE_ONNX,
                            onClick = { engine = ClipRevoicer.ENGINE_ONNX },
                            label = { Text("ONNX (oflayn)") }
                        )
                        FilterChip(
                            selected = engine == ClipRevoicer.ENGINE_GEMINI,
                            onClick = { engine = ClipRevoicer.ENGINE_GEMINI },
                            label = { Text("Gemini (onlayn)") }
                        )
                    }
                    if (!engineReady) {
                        Text(
                            if (engine == ClipRevoicer.ENGINE_GEMINI) "Gemini kaliti kiritilmagan (Sozlamalar → Tarjima)."
                            else "ONNX ovoz fayllari yo'q (Sozlamalar → Ovoz).",
                            style = MaterialTheme.typography.bodySmall, color = cs.error
                        )
                    }
                } else {
                    Text(
                        "Yuqoridagi matnni o'zingiz o'qing. Quloqchin ishlatsangiz yaxshi. Eng ko'pi bilan 60 soniya.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!recording) {
                            FilledTonalButton(onClick = { startRec() }) { Text(if (recorded) "● Qayta yozish" else "● Yozishni boshlash") }
                        } else {
                            Button(onClick = { recorder?.let { r -> Thread { r.stop() }.start() } }) { Text("■ To'xtatish") }
                            Text("${"%.1f".format(recSec)} s", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (recorded && !recording) Text("✓ Yozildi", color = cs.primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canApply,
                onClick = { onApply(text, role, engine, own) }
            ) { Text("Ovozlash") }
        },
        dismissButton = { TextButton(enabled = !recording, onClick = onDismiss) { Text("Bekor qilish") } }
    )
}
