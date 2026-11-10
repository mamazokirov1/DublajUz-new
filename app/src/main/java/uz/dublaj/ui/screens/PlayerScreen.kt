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
        Box(
            Modifier.padding(padding).fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            uri?.let {
                VideoPlayer(
                    uri = it,
                    modifier = Modifier.fillMaxWidth(),
                    autoPlay = true,
                    cues = cues
                )
            } ?: Text("Video tanlanmagan", color = Color.White)
        }
    }
}
