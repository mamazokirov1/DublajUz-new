package uz.dublaj.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import uz.dublaj.ui.theme.findActivity
import androidx.compose.ui.Modifier
import uz.dublaj.monetization.AdsManager
import uz.dublaj.monetization.BannerAd
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import uz.dublaj.ui.screens.EditTranslationScreen
import uz.dublaj.ui.screens.HistoryScreen
import uz.dublaj.ui.screens.HomeScreen
import uz.dublaj.ui.screens.PlayerScreen
import uz.dublaj.ui.screens.QueueScreen
import uz.dublaj.ui.screens.TextToolsScreen
import uz.dublaj.ui.screens.SettingsScreen
import uz.dublaj.ui.viewmodel.PipelineViewModel

object Routes {
    const val HOME = "home"
    const val EDIT = "edit"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val QUEUE = "queue"
    const val PLAYER = "player"
    const val TEXT = "text"
}

@Composable
fun DublajNavHost(incomingUri: Uri? = null, onIncomingConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    // NavHost'dan TASHQARIDA yaratiladi -> Activity'ga bog'langan, barcha ekranlar bitta nusxani ulashadi
    val vm: PipelineViewModel = viewModel()

    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            vm.restore(ctx) // Pro holati yuklangan bo'lsin (uzun videoni noto'g'ri rad etmaslik uchun)
            vm.setVideo(incomingUri)
            onIncomingConsumed()
        }
    }

    // Rang mavzusi va Pro holati
    val config by vm.config.collectAsState()
    var prompt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<uz.dublaj.data.ProPrompt?>(null) }
    LaunchedEffect(Unit) { vm.proPrompts.collect { prompt = it } }
    prompt?.let { p ->
        uz.dublaj.ui.components.LimitDialog(
            prompt = p,
            onUpgrade = {
                prompt = null
                val act = ctx.findActivity()
                val launched = act != null && uz.dublaj.monetization.BillingManager.launchPurchase(act)
                if (!launched) {
                    vm.toast(uz.dublaj.monetization.BillingManager.lastError.value ?: "Xarid hozir mavjud emas. Keyinroq urinib ko'ring.")
                }
            },
            onToast = { vm.toast(it) },
            onDismiss = { prompt = null }
        )
    }
    LaunchedEffect(config.accent, config.isPro) {
        uz.dublaj.ui.theme.ThemeSettings.accent = config.accent
        uz.dublaj.ui.theme.ThemeSettings.pro = config.isPro
    }
    // Banner BARCHA ekranlar tagida (Pro da ko'rinmaydi)
    val showBanner = !config.isPro && AdsManager.adsReady
    Column(Modifier.fillMaxSize()) {
      Box(
        Modifier
            .weight(1f)
            .then(if (showBanner) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
      ) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally(tween(280)) { it / 6 } + fadeIn(tween(280)) },
        exitTransition = { slideOutHorizontally(tween(280)) { -it / 10 } + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(tween(280)) { -it / 10 } + fadeIn(tween(280)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it / 6 } + fadeOut(tween(200)) }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onEditTranslation = { navController.navigate(Routes.EDIT) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onQueue = { navController.navigate(Routes.QUEUE) },
                onTextTools = { navController.navigate(Routes.TEXT) },
                onPlay = { uri ->
                    vm.openPlayer(uri)
                    navController.navigate(Routes.PLAYER)
                }
            )
        }
        composable(Routes.EDIT) {
            EditTranslationScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.QUEUE) {
            QueueScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onPlay = { f ->
                    vm.openPlayer(Uri.fromFile(f))
                    navController.navigate(Routes.PLAYER)
                }
            )
        }
        composable(Routes.PLAYER) {
            PlayerScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.TEXT) {
            TextToolsScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() })
        }
    }
    }
      if (showBanner) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
        ) { BannerAd() }
      }
    }
}
