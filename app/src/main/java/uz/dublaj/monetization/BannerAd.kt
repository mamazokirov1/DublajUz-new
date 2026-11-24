package uz.dublaj.monetization

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * Banner. Pro da hech narsa chizilmaydi. Ekrandan ketganda AdView yo'q qilinadi (xotira oqishi bo'lmasin).
 * [rect] = true: 300×250 o'rta to'rtburchak (jarayon kartasi uchun, daromadi yuqoriroq). Yuklanmasa joy egallamaydi.
 */
@Composable
fun BannerAd(modifier: Modifier = Modifier, rect: Boolean = false) {
    val context = LocalContext.current
    if (!AdsManager.shouldShowAds(context) || !AdsManager.adsReady) return

    var loaded by remember { mutableStateOf(!rect) }
    val adView = remember {
        AdView(context).apply {
            setAdSize(if (rect) AdSize.MEDIUM_RECTANGLE else AdSize.BANNER)
            adUnitId = AdsManager.bannerAdUnitId()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (rect) adListener = object : AdListener() {
                override fun onAdLoaded() { loaded = true }
                override fun onAdFailedToLoad(error: LoadAdError) { loaded = false }
            }
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .then(if (loaded) Modifier.heightIn(min = if (rect) 250.dp else 50.dp) else Modifier.height(0.dp)),
        factory = { adView }
    )
}
