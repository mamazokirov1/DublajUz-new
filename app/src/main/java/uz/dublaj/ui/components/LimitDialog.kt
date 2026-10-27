package uz.dublaj.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import uz.dublaj.data.ProPrompt
import uz.dublaj.data.UsageStore
import uz.dublaj.monetization.AdsManager
import uz.dublaj.monetization.BillingManager
import uz.dublaj.ui.theme.findActivity

/**
 * Bepul foydalanuvchiga ko'rsatiladigan dialog:
 *  - video 15 daqiqadan uzun bo'lsa — Pro'ga undaydi;
 *  - kunlik imkoniyat tugagan bo'lsa — reklama ko'rib imkoniyat olish yoki Pro.
 */
@Composable
fun LimitDialog(
    prompt: ProPrompt,
    onUpgrade: () -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tick by remember { mutableIntStateOf(0) }
    val progress = remember(tick) { UsageStore.adsProgress(context) }
    val bonus = remember(tick) { UsageStore.bonusCredits(context) }
    val price = BillingManager.priceText()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Star, null, tint = cs.primary, modifier = Modifier.size(32.dp)) },
        title = {
            Text(
                when (prompt) {
                    is ProPrompt.TooLong -> "Video juda uzun"
                    ProPrompt.LimitReached -> "Bepul imkoniyatlar tugadi"
                },
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (prompt) {
                    is ProPrompt.TooLong -> {
                        Text(
                            "Tanlangan video taxminan ${prompt.minutes} daqiqa. Bepul versiyada bitta video " +
                                "${UsageStore.FREE_MAX_VIDEO_MINUTES} daqiqagacha bo'lishi mumkin.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Pro bilan video uzunligi cheklanmaydi: soatlab davom etadigan videolarni ham dublyaj qilasiz. " +
                                "Yoki videoni ${UsageStore.FREE_MAX_VIDEO_MINUTES} daqiqagacha qisqartiring.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    ProPrompt.LimitReached -> {
                        Text(
                            "Bugungi ${UsageStore.FREE_DAILY_DUBS} ta bepul imkoniyat ishlatildi. " +
                                "Davom etish uchun reklama ko'ring: har ${UsageStore.ADS_PER_BONUS} ta reklama = 1 ta yangi imkoniyat.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(
                            progress = { progress.toFloat() / UsageStore.ADS_PER_BONUS },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp),
                            trackColor = cs.surfaceVariant
                        )
                        Text(
                            "Ko'rilgan reklama: $progress / ${UsageStore.ADS_PER_BONUS}" +
                                if (bonus > 0) " • yig'ilgan imkoniyat: $bonus" else "",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                val act = context.findActivity()
                                if (act == null) {
                                    onToast("Reklama ochilmadi")
                                } else {
                                    AdsManager.showRewarded(
                                        act,
                                        onEarned = {
                                            val granted = UsageStore.addRewardedAd(context)
                                            tick++
                                            if (granted) {
                                                onToast("Yangi imkoniyat olindi ✓ Endi davom eting")
                                                onDismiss()
                                            }
                                        },
                                        onUnavailable = { onToast("Reklama hozir mavjud emas. Birozdan keyin urinib ko'ring.") }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.PlayCircle, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (AdsManager.rewardedReady) "Reklama ko'rish" else "Reklama yuklanmoqda…")
                        }
                        Text(
                            "Yoki Pro bilan cheksiz davom eting — reklamasiz.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onUpgrade) {
                Icon(Icons.Default.Star, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (price != null) "Pro — $price" else "Pro olish")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Yopish") } }
    )
}
