@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uz.dublaj.ui.screens

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import uz.dublaj.ui.components.CollectUiEvents

import android.app.Activity
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import uz.dublaj.ui.theme.AccentPalettes
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import uz.dublaj.translate.AiProvider
import uz.dublaj.translate.activeKey
import uz.dublaj.translate.activeModel
import uz.dublaj.translate.provider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.dublaj.data.VoicePreset
import uz.dublaj.data.applyPreset
import uz.dublaj.stt.WhisperRecognizer
import uz.dublaj.translate.GeminiTranslator
import uz.dublaj.translate.Languages
import uz.dublaj.ui.components.*
import uz.dublaj.ui.theme.ThemeSettings
import uz.dublaj.ui.viewmodel.PipelineViewModel
import uz.dublaj.util.BugReport
import uz.dublaj.util.UpdateChecker
import uz.dublaj.data.UsageStore
import uz.dublaj.monetization.BillingManager
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: PipelineViewModel,
    onBack: () -> Unit
) {
    val config by vm.config.collectAsState()
    val models by vm.models.collectAsState()
    val status by vm.modelStatus.collectAsState()
    val preview by vm.previewState.collectAsState()
    val updateMessage by vm.updateMessage.collectAsState()
    val availableUpdate by vm.update.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    val busy by vm.busy.collectAsState()
    val cancelling by vm.cancelling.collectAsState()
    val workVersion by vm.workVersion.collectAsState()
    val host = remember { SnackbarHostState() }
    CollectUiEvents(vm, host)
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val expert = config.expertMode
    var showProDialog by remember { mutableStateOf(false) }

    var usedMb by remember { mutableStateOf(0L) }
    LaunchedEffect(status, workVersion) {
        vm.refreshModels(context)
        usedMb = vm.usedBytes(context) / (1024 * 1024)
    }

    val whisperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importWhisper(context, it) }
    }
    val voiceRefPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importVoiceRef(context, it) }
    }
    val vitsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importVitsModel(context, it) }
    }
    val vitsCfgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importVitsConfig(context, it) }
    }
    val startAutoImport = rememberAutoImportAction(vm)

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(host) },
        topBar = {
            TopAppBar(
                title = { Text("Sozlamalar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------------- rejim
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rejim", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = !expert,
                            onClick = { vm.setConfig(config.copy(expertMode = false)) },
                            label = { Text("Oddiy") }
                        )
                        FilterChip(
                            selected = expert,
                            onClick = { vm.setConfig(config.copy(expertMode = true)) },
                            label = { Text("Ilg'or") }
                        )
                    }
                    Text(
                        if (expert) "Ilg'or: barcha sozlamalar ochiq — ovoz tembri, sinxronlash, aralashtirish, AI modeli va boshqalar."
                        else "Oddiy: faqat kerakli narsalar — AI kaliti, modellar va ovoz tezligi. Boshlovchilar uchun tavsiya etiladi.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
            }


            // ---------------- tezkor boshlash (oddiy rejimda har doim, ilg'orda tayyor bo'lmasa)
            val setupDone = models.whisper && config.onlineReady && models.vits
            if (!expert || !setupDone) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = if (setupDone) cs.secondaryContainer else cs.tertiaryContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (setupDone) "Hammasi tayyor ✓" else "Boshlash uchun 3 qadam",
                            style = MaterialTheme.typography.titleMedium
                        )
                        @Composable
                        fun stepLine(ok: Boolean, text: String) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(
                                    if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    null, modifier = Modifier.size(20.dp)
                                )
                                Text(text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        stepLine(config.onlineReady, "1. Tarjima: pastdagi \"Tarjima (AI)\" bo'limida provayderni tanlab, kalit kiriting")
                        stepLine(models.whisper, "2. Matn aniqlash modelini yuklab oling (\"Yuklab olinadigan komponentlar\")")
                        stepLine(models.vits, "3. O'zbekcha ovoz modelini yuklab oling (xuddi shu bo'limda)")
                        if (!setupDone) {
                            Text(
                                "Har bir bo'lim ichida qadamma-qadam yo'riqnoma bor. Yo'riqnomani ochish uchun bo'lim sarlavhasini bosing.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // ---------------- Pro / limit
            val isPro = config.isPro
            val remaining = remember(isPro, workVersion) {
                UsageStore.remainingDubs(context, isPro)
            }
            val dubsToday = remember(workVersion) { UsageStore.dubsToday(context) }
            val minsToday = remember(workVersion) { UsageStore.minutesToday(context) }
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = if (isPro) cs.primaryContainer else cs.surface
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                if (isPro) "Pro versiya" else "Bepul versiya",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (isPro) "Cheksiz dublyaj • reklamasiz"
                                else "Kuniga ${UsageStore.FREE_DAILY_DUBS} ta video / ~${UsageStore.FREE_DAILY_MINUTES} daqiqa",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        if (isPro) {
                            AssistChip(
                                onClick = {},
                                label = { Text("PRO") },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = cs.primary,
                                    labelColor = cs.onPrimary
                                )
                            )
                        }
                    }

                    if (!isPro) {
                        // Limit progress
                        val usedFrac = (dubsToday.toFloat() / UsageStore.FREE_DAILY_DUBS).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { usedFrac },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            trackColor = cs.surfaceVariant,
                        )
                        Text(
                            "Bugun: $dubsToday / ${UsageStore.FREE_DAILY_DUBS} video • $minsToday daqiqa ishlatildi • qoldi: $remaining",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                        Text(
                            "Pro afzalliklari: cheksiz dublyaj va reklamasiz foydalanish.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                        Button(
                            onClick = { showProDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Pro ga o'tish")
                        }
                    } else if (uz.dublaj.BuildConfig.ALLOW_TEST_UNLOCK) {
                        OutlinedButton(
                            onClick = {
                                // Test / debug: Pro ni o'chirish (faqat test APK)
                                BillingManager.setProLocal(context, false)
                                vm.setConfig(config.copy(isPro = false))
                                vm.toast("Pro o'chirildi (test)")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Pro ni o'chirish (test)")
                        }
                    }
                }
            }

            // ---------------- tarjima (AI provayderlar)
            val uriHandler = LocalUriHandler.current
            var showKey by remember { mutableStateOf(false) }
            val prov = config.provider()
            val foundModels by vm.aiModels.collectAsState()
            val curKey = config.activeKey()
            val curModel = config.activeModel()
            fun setKey(v: String) {
                vm.setConfig(
                    if (prov == AiProvider.GEMINI) config.copy(geminiKey = v.trim())
                    else config.copy(aiKeys = config.aiKeys + (prov.id to v.trim()))
                )
            }
            fun setModel(v: String) {
                vm.setConfig(
                    if (prov == AiProvider.GEMINI) config.copy(geminiModel = v.trim())
                    else config.copy(aiModels = config.aiModels + (prov.id to v.trim()))
                )
            }
            SectionCard(
                icon = Icons.Default.Translate,
                title = "Tarjima (AI)",
                subtitle = if (config.onlineReady) "Yoqilgan ✓ — ${prov.label}"
                else "Provayderni tanlab, kalit kiriting",
                initiallyExpanded = !config.onlineReady
            ) {
                Text(
                    "Tarjimani sun'iy intellekt qiladi. Provayderni o'zingiz tanlaysiz — har biri uchun alohida kalit saqlanadi. " +
                        "Faqat matn (videoning o'zi emas) tanlangan provayder serveriga yuboriladi.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                SwitchRow(
                    "AI bilan tarjima qilish",
                    "Kalit kiritilgach ishlaydi. Tarjima uchun majburiy.",
                    config.onlineTranslate
                ) { vm.setConfig(config.copy(onlineTranslate = it)) }

                Text("Provayder", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AiProvider.values().forEach { p ->
                        FilterChip(
                            selected = prov == p,
                            onClick = {
                                vm.clearAiModels()
                                vm.setConfig(config.copy(aiProvider = p.id))
                            },
                            label = { Text(p.label) }
                        )
                    }
                }
                Text(prov.pricing, style = MaterialTheme.typography.bodySmall, color = cs.tertiary)

                if (prov.customUrl) {
                    OutlinedTextField(
                        value = config.aiBaseUrl,
                        onValueChange = { vm.setConfig(config.copy(aiBaseUrl = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true,
                        label = { Text("Base URL") },
                        supportingText = { Text("Masalan: https://api.together.xyz/v1") }
                    )
                }
                OutlinedTextField(
                    value = curKey,
                    onValueChange = { setKey(it) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true,
                    label = { Text("${prov.label} API kaliti") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { showKey = !showKey }, shape = MaterialTheme.shapes.medium) {
                        Text(if (showKey) "Yashirish" else "Ko'rsatish")
                    }
                    Button(
                        onClick = { vm.testGemini() },
                        enabled = curKey.isNotBlank() && (!prov.customUrl || config.aiBaseUrl.isNotBlank()),
                        shape = MaterialTheme.shapes.medium
                    ) { Text("Tekshirish") }
                    OutlinedButton(
                        onClick = { uriHandler.openUri(prov.keyUrl) },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Link, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Kalit olish")
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }

                Text("Kalitni qanday olish mumkin:", style = MaterialTheme.typography.labelLarge)
                Text(
                    prov.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )

                // ---- model
                Text("Model", style = MaterialTheme.typography.labelLarge)
                if (prov.suggested.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        prov.suggested.forEach { m ->
                            FilterChip(
                                selected = curModel == m,
                                onClick = { setModel(m) },
                                label = { Text(m) }
                            )
                        }
                    }
                }
                if (expert || prov.customUrl) {
                    OutlinedTextField(
                        value = if (prov == AiProvider.GEMINI) config.geminiModel else config.aiModels[prov.id].orEmpty(),
                        onValueChange = { setModel(it) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true,
                        label = { Text("Model nomi (qo'lda)") },
                        supportingText = {
                            Text(
                                if (prov.defaultModel.isBlank()) "Majburiy: xizmat hujjatidagi model nomini yozing"
                                else "Bo'sh qoldirsangiz: ${prov.defaultModel}"
                            )
                        }
                    )
                    OutlinedButton(
                        onClick = { vm.fetchAiModels() },
                        enabled = curKey.isNotBlank() && (!prov.customUrl || config.aiBaseUrl.isNotBlank()),
                        shape = MaterialTheme.shapes.medium
                    ) { Text("Mavjud modellarni olish") }
                    if (foundModels.isNotEmpty()) {
                        Text("Topilgan modellar (bosib tanlang):", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            foundModels.take(40).forEach { m ->
                                AssistChip(onClick = { setModel(m) }, label = { Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                            }
                        }
                    }
                } else {
                    Text(
                        "Model eskirgan bo'lsa (xato: \"model topilmadi\"), Ilg'or rejimda yangi model nomini yozishingiz mumkin.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }

                Text("Tarjima uslubi", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = config.videoContext,
                    onValueChange = { vm.setConfig(config.copy(videoContext = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    minLines = 2,
                    maxLines = 5,
                    label = { Text("Video mavzusi va uslubi (ixtiyoriy)") },
                    supportingText = { Text("Masalan: yo'l harakati, norasmiy, ustoz shogirdga gapiradi") }
                )
                Text("Murojaat", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("auto" to "Avto", "sen" to "Sen", "siz" to "Siz").forEach { (id, label) ->
                        FilterChip(
                            selected = config.addressForm == id,
                            onClick = { vm.setConfig(config.copy(addressForm = id)) },
                            label = { Text(label) }
                        )
                    }
                }
                Text("Ohang", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("casual" to "Og'zaki", "neutral" to "Oddiy", "formal" to "Rasmiy").forEach { (id, label) ->
                        FilterChip(
                            selected = config.tone == id,
                            onClick = { vm.setConfig(config.copy(tone = id)) },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = config.glossary,
                    onValueChange = { vm.setConfig(config.copy(glossary = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    minLines = 3,
                    maxLines = 8,
                    label = { Text("Lug'at (ixtiyoriy)") },
                    supportingText = { Text("Har qatorda: so'z = tarjima. Masalan: фура = yuk mashinasi") }
                )
                if (expert) {
                    HorizontalDivider()
                    Text("Ilg'or: AI sozlamalari", style = MaterialTheme.typography.titleMedium)
                    if (prov != AiProvider.OPENAI) {
                        HelpSlider(
                            title = "Ijodkorlik (temperature)",
                            valueText = "%.2f".format(config.aiTemperature),
                            help = "Past (0.1–0.3) — aniq va barqaror tarjima. Yuqori — erkinroq, lekin xato ehtimoli ko'proq.",
                            value = config.aiTemperature, range = 0f..1f
                        ) { vm.setConfig(config.copy(aiTemperature = it)) }
                    }
                    HelpSlider(
                        title = "Bir so'rovda gaplar soni",
                        valueText = "${config.aiChunkSize}",
                        help = "Ko'p — tezroq va arzonroq, lekin xato bo'lsa ko'p gap yo'qoladi. Kam — sekinroq, lekin barqarorroq.",
                        value = config.aiChunkSize.toFloat(), range = 4f..30f
                    ) { vm.setConfig(config.copy(aiChunkSize = it.toInt())) }
                    OutlinedTextField(
                        value = config.aiExtraPrompt,
                        onValueChange = { vm.setConfig(config.copy(aiExtraPrompt = it.take(1200))) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        minLines = 2,
                        maxLines = 6,
                        label = { Text("Qo'shimcha ko'rsatma (inglizcha yoki o'zbekcha)") },
                        supportingText = { Text("AI ga beriladigan qo'shimcha qoida. Masalan: \"Avtomobil atamalarini rus tilidagidek qoldir\"") }
                    )
                }
                Text(
                    "Kalit faqat shu telefonda saqlanadi va hisobotlarga qo'shilmaydi. Bepul limitlar provayder tomonidan o'zgarishi mumkin.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            // ---------------- ovoz: Chatterbox (server) va klonlash — faqat ilg'or rejimda (tajriba)
            if (expert && uz.dublaj.BuildConfig.CLOUD_TTS) {
            var showCbToken by remember { mutableStateOf(false) }
            SectionCard(
                icon = Icons.Default.Mic,
                title = "Tajriba: Chatterbox ovozi (server)",
                subtitle = if (config.chatterboxReady) "Chatterbox yoqilgan ✓" else "Offline ovoz (VITS) ishlaydi",
                initiallyExpanded = false
            ) {
                Text(
                    "Chatterbox o'zbekcha ovozni ancha tabiiy chiqaradi va ixtiyoriy namuna ovozni klonlay oladi. " +
                        "Model telefonda ishlamaydi: uni Google Colab (GPU) yoki o'z serveringizda ishga tushirib, manzilini kiriting. " +
                        "Server ishlamasa, offline ovoz zaxira bo'ladi (agar uning fayllari qo'shilgan bo'lsa).",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                Text("Ovoz dvigateli", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("vits" to "Offline (VITS)", "chatterbox" to "Chatterbox").forEach { (id, label) ->
                        FilterChip(
                            selected = config.ttsEngine == id,
                            onClick = { vm.setConfig(config.copy(ttsEngine = id)) },
                            label = { Text(label) }
                        )
                    }
                }
                if (config.ttsEngine == "chatterbox") {
                    OutlinedTextField(
                        value = config.cbUrl,
                        onValueChange = { vm.setConfig(config.copy(cbUrl = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true,
                        label = { Text("Server manzili (https://…)") }
                    )
                    OutlinedTextField(
                        value = config.cbToken,
                        onValueChange = { vm.setConfig(config.copy(cbToken = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true,
                        label = { Text("Server tokeni") },
                        visualTransformation = if (showCbToken) VisualTransformation.None else PasswordVisualTransformation()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { showCbToken = !showCbToken }, shape = MaterialTheme.shapes.medium) {
                            Text(if (showCbToken) "Yashirish" else "Ko'rsatish")
                        }
                        Button(
                            onClick = { vm.testChatterbox() },
                            enabled = config.cbUrl.isNotBlank(),
                            shape = MaterialTheme.shapes.medium
                        ) { Text("Serverni tekshirish") }
                    }
                    Text("Qaysi ovozda gapirsin", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("default" to "Standart", "video" to "Videodagi ovoz", "file" to "Namuna fayl").forEach { (id, label) ->
                            FilterChip(
                                selected = config.cbVoiceMode == id,
                                onClick = { vm.setConfig(config.copy(cbVoiceMode = id)) },
                                label = { Text(label) }
                            )
                        }
                    }
                    if (config.cbVoiceMode == "video") {
                        Text(
                            "Videodan ≈10 soniyalik namuna avtomatik olinadi (matn aniqlash paytida). Bir nechta spiker bo'lsa yoki fon musiqasi baland bo'lsa, \"Namuna fayl\" yaxshiroq.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    if (config.cbVoiceMode == "file") {
                        OutlinedButton(
                            onClick = { voiceRefPicker.launch(arrayOf("audio/*")) },
                            shape = MaterialTheme.shapes.medium
                        ) { Text("Namuna ovozni tanlash (5–15 soniya, toza nutq)") }
                        if (config.cbRefName.isNotBlank()) {
                            Text("Tanlangan: ${config.cbRefName}", style = MaterialTheme.typography.bodySmall, color = cs.primary)
                        }
                    }
                    if (config.cbVoiceMode != "default") {
                        SwitchRow(
                            "Bu ovozdan foydalanishga huquqim bor",
                            "Faqat o'zingizning yoki egasi roziligini bergan ovozni klonlang. Boshqa shaxs ovozini roziligisiz ishlatish qonunga zid bo'lishi mumkin.",
                            config.cbCloneConsent
                        ) { vm.setConfig(config.copy(cbCloneConsent = it)) }
                    }
                    status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    }
                }
            }
            }

            // ---------------- modellar
            val allReady = models.whisper && models.vits && config.onlineReady
            SectionCard(
                icon = Icons.Default.Storage,
                title = "Yuklab olinadigan komponentlar",
                subtitle = if (allReady) "Hammasi tayyor ✓" else "Ba'zi komponentlar yetishmaydi",
                initiallyExpanded = !allReady
            ) {
                Text(
                    "Matn aniqlash va ovoz offline ishlaydi. Tarjima uchun AI kaliti kerak (Sozlamalar → Tarjima (AI)).",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill("Matn aniqlash", models.whisper)
                    StatusPill("Tarjima (AI)", config.onlineReady)
                    StatusPill("Ovoz", models.vits)
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    if (!it.startsWith("Tayyor") && !it.startsWith("Xato") && !it.startsWith("Bekor")) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { vm.cancel() }, enabled = !cancelling) {
                            Text(if (cancelling) "Bekor qilinmoqda…" else "Bekor qilish")
                        }
                    }
                }
                Button(
                    onClick = { startAutoImport() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Papka tanlab, fayllarni topish")
                }
                Text(
                    "Fayllar turgan papkani (masalan, Yuklanmalar) tanlang — kerakli fayllarni o'zi topib qo'shadi. " +
                        "Hech qanday maxsus ruxsat kerak emas.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )

                HorizontalDivider()
                Text("Nutqni matnga aylantirish", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Videodagi gapni eshitib, yozib oladi. \"Tez\" kichik va tez, \"Aniq\" katta va aniqroq (sekinroq).",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WhisperRecognizer.MODEL_OPTIONS.forEach { m ->
                        FilterChip(
                            selected = config.whisperModel == m.id,
                            onClick = { vm.setConfig(config.copy(whisperModel = m.id), context) },
                            label = { Text("${friendlyModelName(m.id)} • ${m.sizeMb} MB") }
                        )
                    }
                }
                Text(WhisperRecognizer.info(config.whisperModel).note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { vm.downloadWhisper(context) }, enabled = !busy) { Text("Yuklash") }
                    OutlinedButton(onClick = { whisperPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Fayldan qo'shish") }
                }

                HorizontalDivider()
                Text("O'zbekcha ovoz", style = MaterialTheme.typography.titleMedium)
                Text("Tarjimani o'zbekcha o'qiydigan ikki fayl: ovoz fayli (.onnx) va uning sozlama fayli (.json). Internetdan yuklash tavsiya etiladi (Wi-Fi bilan).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Button(onClick = { vm.downloadVits(context) }, enabled = !busy) { Text("Ovozni internetdan yuklash") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { vitsPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Ovoz fayli (.onnx)") }
                    OutlinedButton(onClick = { vitsCfgPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Sozlama fayli (.json)") }
                }
            }

            // ---------------- ovoz sifati
            var sample by remember {
                mutableStateOf("Assalomu alaykum! Bu ovoz sinovi. Tabiiy chiqayotganini tinglab ko'ring.")
            }
            SectionCard(
                icon = Icons.Default.GraphicEq,
                title = "Ovoz sifati",
                subtitle = "Tinglab ko'rib sozlang",
                initiallyExpanded = true
            ) {
                Text("Tayyor sozlama", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VoicePreset.values().forEach { p ->
                        FilterChip(
                            selected = config.applyPreset(p) == config,
                            onClick = { vm.applyVoicePreset(p) },
                            label = { Text(p.label) }
                        )
                    }
                }

                HelpSlider(
                    "Nutq tezligi", "${"%.2f".format(config.voiceSpeed)}×",
                    "1.0 oddiy tezlik. Past qiymat sekinroq va aniqroq, baland qiymat tezroq gapiradi.",
                    config.voiceSpeed, 0.7f..1.3f
                ) { vm.setConfig(config.copy(voiceSpeed = it)) }

                HelpSlider(
                    "Ovoz tembri", if (config.voicePitch == 0f) "Asl" else "%+.1f".format(config.voicePitch),
                    "Manfiy qiymat ovozni qalinroq (erkakcha), musbat qiymat ingichkaroq qiladi. 0 — o'zgarishsiz. Gapirish tezligiga ta'sir qilmaydi.",
                    config.voicePitch, -4f..4f, steps = 15
                ) { vm.setConfig(config.copy(voicePitch = it)) }

                if (expert) {
                    HelpSlider(
                        "Intonatsiya (jonlilik)", "%.2f".format(config.voiceNoise),
                        "Yuqori qiymat ovozni jonliroq qiladi, lekin beqaror bo'lishi mumkin. Past qiymat bir tekis, robotroq eshitiladi.",
                        config.voiceNoise, 0.3f..1.0f
                    ) { vm.setConfig(config.copy(voiceNoise = it)) }

                    HelpSlider(
                        "Ritm o'zgaruvchanligi", "%.2f".format(config.voiceNoiseW),
                        "Bo'g'inlar uzunligi qanchalik xilma-xil bo'lishi. Yuqori qiymat tabiiyroq ritm beradi.",
                        config.voiceNoiseW, 0.1f..1.0f
                    ) { vm.setConfig(config.copy(voiceNoiseW = it)) }

                    HelpSlider(
                        "Pauza (gap ichida)", "${"%.2f".format(config.voicePause)} s",
                        "Uzun gap bo'laklarga bo'linganda orasidagi to'xtash.",
                        config.voicePause, 0f..0.8f
                    ) { vm.setConfig(config.copy(voicePause = it)) }

                    HelpSlider(
                        "Sig'dirish uchun tezlashish", "${"%.2f".format(1f / config.fitScale)}×",
                        "Gap video vaqtiga sig'masa, ovoz shu darajagacha tezlashtiriladi (sifat saqlanadi). 1.0 o'chiq degani.",
                        config.fitScale, 0.7f..1.0f
                    ) { vm.setConfig(config.copy(fitScale = it)) }
                }

                OutlinedTextField(
                    value = config.pronDict,
                    onValueChange = { vm.setConfig(config.copy(pronDict = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    minLines = 2,
                    maxLines = 6,
                    label = { Text("Talaffuz lug'ati (ixtiyoriy)") },
                    supportingText = { Text("Har qatorda: so'z = qanday o'qilsin. Masalan: Gemini = jemini") }
                )
                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("Tinglash uchun matn") }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { vm.previewVoice(context, sample) },
                        enabled = models.vits && !busy,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Tinglab ko'rish")
                    }
                    OutlinedButton(onClick = { vm.stopPreview() }, shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(6.dp))
                        Text("To'xtatish")
                    }
                }
                if (!models.vits) {
                    Text("Tinglash uchun avval o'zbekcha ovoz fayllarini qo'shing.", style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
                }
                preview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
                Text(
                    "Haqiqiy dublyajda gaplar video vaqtiga sig'dirilgani uchun ovoz biroz tezroq bo'lishi mumkin.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            // ---------------- sinxronlash
            SectionCard(
                icon = Icons.Default.Sync,
                title = "Sinxronlash (vaqt)",
                subtitle = "Dublaj asl nutqqa mos tushishi",
                initiallyExpanded = false
            ) {
                SwitchRow(
                    "Vaqtni asl nutqqa moslash",
                    "Matn aniqlash paytida gap chegaralari asl audiodagi haqiqiy nutq boshlanishi va tugashiga suriladi. Videoni qayta aniqlaganda ishlaydi.",
                    config.snapSpeech
                ) { vm.setConfig(config.copy(snapSpeech = it)) }
                HelpSlider(
                    "Dublaj siljishi", "${"%+.2f".format(config.syncOffsetS)} s",
                    "Hamma gaplarni birdaniga surish: musbat — kechroq, manfiy — erta boshlanadi. Dublaj doim erta bo'lsa musbat, kech bo'lsa manfiy qiling.",
                    config.syncOffsetS, -0.6f..0.6f
                ) { vm.setConfig(config.copy(syncOffsetS = it)) }
            }

            // ---------------- aralashtirish
            SectionCard(
                icon = Icons.Default.Tune,
                title = "Aralashtirish",
                subtitle = "Asl ovoz va dublyaj balandligi"
            ) {
                HelpSlider(
                    "Asl ovoz (jimlikda)", "${(config.originalVolume * 100).roundToInt()}%",
                    "Videodagi asl ovoz va musiqa balandligi.",
                    config.originalVolume, 0f..0.6f
                ) { vm.setConfig(config.copy(originalVolume = it)) }

                HelpSlider(
                    "O'zbekcha ovoz", "${(config.uzbekVolume * 100).roundToInt()}%",
                    "Dublyaj ovozi balandligi.",
                    config.uzbekVolume, 0.5f..1.5f
                ) { vm.setConfig(config.copy(uzbekVolume = it)) }

                if (expert) {
                    HelpSlider(
                        "Gapirganda asl ovoz", "${(config.duckAmount * 100).roundToInt()}%",
                        "Dublyaj gapirayotganda asl ovoz shu darajagacha pasayadi. 100% degani pasaytirilmaydi.",
                        config.duckAmount, 0f..1f
                    ) { vm.setConfig(config.copy(duckAmount = it)) }

                    HelpSlider(
                        "Maks. tezlashtirish", "${"%.2f".format(config.maxAtempo)}×",
                        "Gap baribir sig'masa, tayyor ovoz shu tezlikkacha tezlashtiriladi. Past qiymat tabiiyroq, lekin gaplar ustma-ust tushishi mumkin.",
                        config.maxAtempo, 1.0f..1.6f
                    ) { vm.setConfig(config.copy(maxAtempo = it)) }

                    SwitchRow(
                        "Balandlikni tenglash", "Har bir gap bir xil balandlikda eshitiladi.",
                        config.levelClips
                    ) { vm.setConfig(config.copy(levelClips = it)) }

                    HelpSlider(
                        "Ishlash tezligi (protsessor yadrolari)", "${config.threads}",
                        "Ko'p oqim tezroq ishlaydi, lekin telefon qizishi va xotira sarfi oshishi mumkin.",
                        config.threads.toFloat(), 1f..8f, steps = 6
                    ) { vm.setConfig(config.copy(threads = it.roundToInt())) }
                }
            }

            // ---------------- til
            SectionCard(
                icon = Icons.Default.Language,
                title = "Video tili",
                subtitle = Languages.label(config.sourceLang)
            ) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, shape = MaterialTheme.shapes.medium) {
                        Text(Languages.label(config.sourceLang))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Languages.UI_OPTIONS.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    vm.setConfig(config.copy(sourceLang = code))
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "Ru/En dan boshqa tillar uchun tilni aniq tanlang.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            // ---------------- ko'rinish
            SectionCard(
                icon = Icons.Default.Palette,
                title = "Ko'rinish",
                subtitle = "Mavzu va ranglar"
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Tizim", "Yorug'", "Qorong'i").forEachIndexed { i, label ->
                        FilterChip(
                            selected = config.themeMode == i,
                            onClick = {
                                ThemeSettings.mode = i
                                vm.setConfig(config.copy(themeMode = i))
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rang mavzusi", style = MaterialTheme.typography.labelLarge)
                    if (!config.isPro) ProTag()
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AccentPalettes.forEachIndexed { i, pal ->
                        FilterChip(
                            selected = (if (config.isPro) config.accent else 0) == i,
                            onClick = {
                                if (i == 0 || config.isPro) {
                                    ThemeSettings.accent = i
                                    vm.setConfig(config.copy(accent = i))
                                } else showProDialog = true
                            },
                            leadingIcon = {
                                Box(
                                    Modifier.size(14.dp).clip(CircleShape)
                                        .background(pal.light)
                                )
                            },
                            label = { Text(if (i == 0 || config.isPro) pal.name else "🔒 ${pal.name}") }
                        )
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    SwitchRow(
                        "Telefon ranglari", "Android 12+ (Material You): ranglar fon rasmingizdan olinadi.",
                        config.dynamicColor
                    ) {
                        ThemeSettings.dynamic = it
                        vm.setConfig(config.copy(dynamicColor = it))
                    }
                }
            }

            // ---------------- yordam
            var reportNote by remember { mutableStateOf("") }
            SectionCard(
                icon = Icons.Default.BugReport,
                title = "Muammo yoki taklif yuborish",
                subtitle = if (BugReport.hasCrash(context)) "⚠ Oldingi safar ilova to'xtagan" else "Hisobot yaratib yuboring"
            ) {
                Text(
                    "Ilova to'xtab qolsa yoki taklifingiz bo'lsa, bizga yuboring. Hisobotga faqat telefon va sozlamalar haqida " +
                        "qisqa ma'lumot kiradi. Videolaringiz va matnlaringiz yuborilmaydi.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                OutlinedTextField(
                    value = reportNote,
                    onValueChange = { reportNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("Izoh yoki taklif (ixtiyoriy)") }
                )
                Button(
                    onClick = {
                        val f = BugReport.build(context, config, models, reportNote)
                        BugReport.share(context, f)
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Send, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Yuborish")
                }
                Text(
                    "Pochta ilovasi ochiladi, hammasi tayyor turadi — faqat \"Yuborish\" ni bosasiz.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            // ---------------- xotira
            val shownMb by animateIntAsState(usedMb.toInt(), animationSpec = tween(700), label = "usedMb")
            SectionCard(
                icon = Icons.Default.DeleteSweep,
                title = "Xotira",
                subtitle = "Vaqtinchalik fayllar: $shownMb MB"
            ) {
                Text(
                    "Video nusxalari va oraliq audio shu yerda turadi. Modellar va tayyor videolar o'chirilmaydi.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                SwitchRow(
                    "Galereyaga avtomatik saqlash",
                    "Dublyaj tayyor bo'lganda video Movies/DublajUz papkasiga ham saqlanadi. " +
                        "Ilovani o'chirsangiz ham yo'qolmaydi (Android 10+).",
                    config.autoSaveGallery
                ) { vm.setConfig(config.copy(autoSaveGallery = it)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ovozni avtomatik saqlash", style = MaterialTheme.typography.labelLarge)
                            if (!config.isPro) ProTag()
                        }
                        Text(
                            "Matn → ovoz tayyor bo'lganda audio Music/DublajUz papkasiga ham saqlanadi.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.autoSaveAudio && config.isPro,
                        onCheckedChange = { if (config.isPro) vm.setConfig(config.copy(autoSaveAudio = it)) else showProDialog = true }
                    )
                }
                OutlinedButton(
                    onClick = { vm.clearWork(context) },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.DeleteSweep, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Vaqtinchalik fayllarni tozalash")
                }
                if (busy) {
                    Text(
                        "Jarayon ketayotganda tozalab bo'lmaydi. Tugashini kuting yoki bekor qiling.",
                        style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                    )
                }
            }

            // ---------------- ilova haqida
            SectionCard(
                icon = Icons.Default.Info,
                title = "Ilova haqida",
                subtitle = "Versiya ${UpdateChecker.currentVersionName(context)}"
            ) {
                if (uz.dublaj.BuildConfig.SELF_UPDATE) {
                    Button(onClick = { vm.checkUpdate(context, manual = true) }, shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Default.SystemUpdate, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Yangilanishni tekshirish")
                    }
                    updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
                    availableUpdate?.let { u ->
                        Button(
                            onClick = { vm.downloadUpdate(context) },
                            enabled = updateProgress == null,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Yuklab olish va o'rnatish (${u.versionName})")
                        }
                    }
                    updateProgress?.let { p ->
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        "Yangi versiya chiqsa, ilova ochilganda kuniga bir marta o'zi xabar beradi.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
                val actCtx = context as? Activity
                if (actCtx != null && uz.dublaj.monetization.ConsentManager.privacyOptionsRequired(context)) {
                    OutlinedButton(
                        onClick = { uz.dublaj.monetization.ConsentManager.showPrivacyOptions(actCtx) },
                        shape = MaterialTheme.shapes.medium
                    ) { Text("Reklama maxfiyligi sozlamalari") }
                }
            }

            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }

    if (showProDialog) {
        ProBenefitsDialog(
            remainingDubs = UsageStore.remainingDubs(context, config.isPro),
            priceText = BillingManager.priceText(),
            onUpgrade = {
                showProDialog = false
                val act = context as? Activity
                val launched = act != null && BillingManager.launchPurchase(act)
                if (launched) {
                    vm.toast("Xarid oynasi ochildi…")
                } else if (uz.dublaj.BuildConfig.ALLOW_TEST_UNLOCK) {
                    // Faqat test APK (debug): Play Billing yo'q bo'lsa test Pro
                    BillingManager.setProLocal(context, true)
                    vm.setConfig(config.copy(isPro = true))
                    vm.toast("Pro test rejimida ochildi (Play Billing mahsuloti yo'q yoki ulanmagan)")
                } else {
                    vm.toast(BillingManager.lastError.value ?: "Xarid hozir mavjud emas. Keyinroq urinib ko'ring.")
                }
            },
            onDismiss = { showProDialog = false }
        )
    }
}

private fun friendlyModelName(id: String): String = when (id) {
    "tiny" -> "Tez"
    "base" -> "Oddiy"
    "small" -> "Aniq"
    else -> id
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }

}
