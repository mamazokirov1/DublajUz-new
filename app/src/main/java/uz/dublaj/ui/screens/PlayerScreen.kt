package uz.dublaj.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import uz.dublaj.ui.theme.findActivity
import androidx.compose.ui.unit.dp
import uz.dublaj.ui.components.SubCue
import uz.dublaj.ui.components.VideoPlayer
import uz.dublaj.ui.viewmodel.PipelineViewModel

/** To'liq ekran video + vaqt bilan sinxron subtitr. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: PipelineViewModel, onBack: () -> Unit) {
    val uri by vm.playerUri.collectAsState()
    val units by vm.translationUnits.collectAsState()
    var showSub by remember { mutableStateOf(true) }

    // Qora fonda status/navigatsiya belgilari oq bo'lsin (yorug' mavzuda ular ko'rinmay qolardi)
    val hostView = LocalView.current
    val darkBg = MaterialTheme.colorScheme.background.luminance() < 0.5f
    DisposableEffect(darkBg) {
        val window = hostView.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, hostView) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !darkBg
            controller?.isAppearanceLightNavigationBars = !darkBg
        }
    }

    val cues = remember(units, showSub) {
        if (!showSub) emptyList()
        else units
            .filter { it.uzText.isNotBlank() }
            .map { SubCue(it.start, it.end, it.uzText) }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Videoni ko'rish") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                actions = {
                    IconButton(onClick = { showSub = !showSub }) {
                        Icon(
                            if (showSub) Icons.Default.Subtitles else Icons.Default.SubtitlesOff,
                            contentDescription = "Subtitr",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        BoxWithConstraints(
            Modifier.padding(padding).fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Pleyer boshqaruv paneli uchun ~64dp qoldiriladi: tik video ham ekranga to'liq sig'adi
            val videoCap = (maxHeight - 64.dp).coerceAtLeast(160.dp)
            uri?.let {
                VideoPlayer(
                    uri = it,
                    modifier = Modifier.fillMaxWidth(),
                    autoPlay = true,
                    cues = cues,
                    maxVideoHeight = videoCap
                )
            } ?: Text("Video tanlanmagan", color = Color.White)
        }
    }
}
