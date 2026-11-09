package uz.dublaj.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uz.dublaj.ui.viewmodel.PipelineViewModel

/**
 * Birinchi ochilishda ko'rsatiladigan 4 bosqichli yo'riqnoma.
 * 0 — Xush kelibsiz
 * 1 — Modellarni yuklash
 * 2 — API kalit (ixtiyoriy)
 * 3 — Qanday ishlatish
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    vm: PipelineViewModel,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val config by vm.config.collectAsState()
    val models by vm.models.collectAsState()
    val modelStatus by vm.modelStatus.collectAsState()
    val busy by vm.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        vm.restore(context)
        vm.refreshModels(context)
    }

    fun finish() {
        vm.setConfig(config.copy(onboardingDone = true), context)
        onFinished()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // Page indicators
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(4) { i ->
                        val selected = pagerState.currentPage == i
                        val color by animateColorAsState(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            label = "dot"
                        )
                        Box(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (selected) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (pagerState.currentPage > 0) {
                        OutlinedButton(
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Orqaga") }
                    }
                    Button(
                        onClick = {
                            if (pagerState.currentPage < 3) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            } else {
                                finish()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !(pagerState.currentPage == 1 && busy)
                    ) {
                        Text(
                            when (pagerState.currentPage) {
                                3 -> "Boshlash"
                                else -> "Keyingi"
                            }
                        )
                    }
                }

                if (pagerState.currentPage < 3) {
                    TextButton(
                        onClick = { finish() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("O‘tkazib yuborish") }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            userScrollEnabled = !busy
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> ModelsPage(
                    whisperOk = models.whisper,
                    vitsOk = models.vits,
                    status = modelStatus,
                    busy = busy,
                    onDownload = { vm.downloadMissing(context) }
                )
                2 -> ApiKeyPage(
                    currentKey = config.geminiKey,
                    onlineReady = config.onlineReady,
                    onSaveKey = { key ->
                        vm.setConfig(
                            config.copy(
                                geminiKey = key.trim(),
                                onlineTranslate = key.isNotBlank(),
                                aiProvider = "gemini"
                            ),
                            context
                        )
                    },
                    onOpenGuide = {
                        uriHandler.openUri("https://aistudio.google.com/apikey")
                    }
                )
                3 -> HowToPage()
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    OnboardScaffold(
        icon = Icons.Filled.Movie,
        title = "DublajUz ga xush kelibsiz",
        subtitle = "Videodagi nutqni o‘zbekchaga tarjima qilib, o‘zbekcha ovoz bilan dublyaj qiladi."
    ) {
        InfoRow("1", "Video tanlang")
        InfoRow("2", "Matn avtomatik chiqadi va tarjima qilinadi")
        InfoRow("3", "Kerak bo‘lsa tahrirlang")
        InfoRow("4", "Dublyaj qiling — tayyor video chiqadi")
        Spacer(Modifier.height(8.dp))
        Text(
            "Birinchi marta biroz sozlash kerak. 1 daqiqada tugatamiz.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ModelsPage(
    whisperOk: Boolean,
    vitsOk: Boolean,
    status: String?,
    busy: Boolean,
    onDownload: () -> Unit
) {
    val allOk = whisperOk && vitsOk
    OnboardScaffold(
        icon = Icons.Filled.CloudDownload,
        title = "Kerakli komponentlar",
        subtitle = "Matn aniqlash (Whisper) va o‘zbekcha ovoz (VITS) bir marta yuklanadi. Keyin offline ishlaydi."
    ) {
        StatusLine("Matn aniqlash (Whisper)", whisperOk)
        StatusLine("O‘zbekcha ovoz (VITS)", vitsOk)
        Spacer(Modifier.height(12.dp))
        if (!allOk) {
            Button(
                onClick = onDownload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Yuklanmoqda…")
                } else {
                    Text("Hamma narsani yuklash")
                }
            }
            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Internet kerak. Hajmi ~100–200 MB atrofida.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            AssistChip(
                onClick = {},
                label = { Text("Hammasi tayyor ✓") },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, null) }
            )
        }
    }
}

@Composable
private fun ApiKeyPage(
    currentKey: String,
    onlineReady: Boolean,
    onSaveKey: (String) -> Unit,
    onOpenGuide: () -> Unit
) {
    var key by remember(currentKey) { mutableStateOf(currentKey) }
    var show by remember { mutableStateOf(false) }

    OnboardScaffold(
        icon = Icons.Filled.Key,
        title = "Tarjima uchun kalit",
        subtitle = "Tarjima internet orqali (Gemini) ishlaydi. Kalitni hozir yoki keyinroq Sozlamalardan kiritishingiz mumkin."
    ) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini API kaliti") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(
                        if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null
                    )
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSaveKey(key) },
                enabled = key.isNotBlank()
            ) { Text("Saqlash") }
            TextButton(onClick = onOpenGuide) { Text("Kalitni qayerdan olaman?") }
        }
        if (onlineReady || key.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Kalit saqlangan. Keyinroq Sozlamalardan o‘zgartirish mumkin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Hozir o‘tkazib yuborsangiz ham, keyin Sozlamalar → Tarjima bo‘limidan kiritasiz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Qisqa yo‘riqnoma", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. aistudio.google.com/apikey saytiga kiring\n" +
                        "2. Google hisobingiz bilan kiring\n" +
                        "3. «Create API key» bosing\n" +
                        "4. Kalitni nusxalab shu yerga yozing",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun HowToPage() {
    OnboardScaffold(
        icon = Icons.Filled.PlayArrow,
        title = "Qanday ishlatiladi?",
        subtitle = "Asosiy ekranda 4 ta bosqich ko‘rinadi."
    ) {
        InfoRow("①", "Video tanlang (galereyadan)")
        InfoRow("②", "«Transkript + tarjima» — matn chiqadi")
        InfoRow("③", "Matnni tekshiring / tahrirlang, «Tasdiqlash»")
        InfoRow("④", "«Dublyaj qilish» — tayyor video chiqadi")
        Spacer(Modifier.height(12.dp))
        Text(
            "Natijani ko‘rish, ulashish yoki Galereyaga saqlash mumkin.\n" +
                "Sozlamalardan ovoz, til va sifatni o‘zgartirasiz.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OnboardScaffold(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(num: String, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(num, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.CloudDownload,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            if (ok) "Tayyor" else "Kerak",
            style = MaterialTheme.typography.labelMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    }
}
