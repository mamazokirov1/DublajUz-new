package uz.dublaj

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import uz.dublaj.ui.DublajNavHost
import uz.dublaj.ui.theme.DublajTheme

class MainActivity : ComponentActivity() {

    /** Galereyadan "Ochish" orqali kelgan video. */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        uz.dublaj.data.ConfigStore.load(this).let {
            uz.dublaj.ui.theme.ThemeSettings.mode = it.themeMode
            uz.dublaj.ui.theme.ThemeSettings.dynamic = it.dynamicColor
            uz.dublaj.ui.theme.ThemeSettings.accent = it.accent
            uz.dublaj.ui.theme.ThemeSettings.pro = it.isPro
        }
        handleIntent(intent)
        uz.dublaj.monetization.ConsentManager.gatherAndInit(this)
        addOnNewIntentListener { handleIntent(it) }
        setContent {
            DublajTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DublajNavHost(incomingUri = incoming, onIncomingConsumed = { incoming = null })
                }
            }
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { incoming = it }
    }
}
