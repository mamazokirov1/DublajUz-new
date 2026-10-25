package uz.dublaj.monetization

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import timber.log.Timber
import uz.dublaj.data.ConfigStore

/**
 * Google AdMob — banner va interstitial.
 *
 * Hozir **Google test ID** lari ishlatiladi (xavfsiz, haqiqiy trafik hisoblanmaydi).
 * Play ga chiqarishdan oldin AdMob konsoldan o'z App ID va Ad Unit ID laringizni qo'ying:
 * - Manifest: com.google.android.gms.ads.APPLICATION_ID
 * - BANNER_AD_UNIT / INTERSTITIAL_AD_UNIT
 */
object AdsManager {

    /** Google test banner. Haqiqiy: ca-app-pub-XXXX/YYYY */
    const val BANNER_AD_UNIT = "ca-app-pub-3940256099942544/6300978111"

    /** Google test interstitial. */
    const val INTERSTITIAL_AD_UNIT = "ca-app-pub-3940256099942544/1033173712"

    private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var loadingInterstitial = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            MobileAds.initialize(context) { status ->
                Timber.i("AdMob initialized: ${status.adapterStatusMap.keys}")
            }
            // Oldindan interstitial yuklab qo'yamiz
            preloadInterstitial(context.applicationContext)
        } catch (e: Exception) {
            Timber.e(e, "AdMob init xato")
        }
    }

    fun shouldShowAds(context: Context): Boolean = !ConfigStore.load(context).isPro

    fun bannerAdUnitId(): String = BANNER_AD_UNIT

    fun preloadInterstitial(context: Context) {
        if (!shouldShowAds(context)) {
            interstitial = null
            return
        }
        if (loadingInterstitial || interstitial != null) return
        loadingInterstitial = true
        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            context,
            INTERSTITIAL_AD_UNIT,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadingInterstitial = false
                    interstitial = ad
                    Timber.i("Interstitial yuklandi")
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            interstitial = null
                            preloadInterstitial(context.applicationContext)
                        }
                        override fun onAdFailedToShowFullScreenContent(error: AdError) {
                            Timber.w("Interstitial ko'rsatilmadi: ${error.message}")
                            interstitial = null
                            preloadInterstitial(context.applicationContext)
                        }
                        override fun onAdShowedFullScreenContent() {
                            Timber.i("Interstitial ko'rsatildi")
                        }
                    }
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingInterstitial = false
                    interstitial = null
                    Timber.w("Interstitial yuklanmadi: ${error.message} (${error.code})")
                }
            }
        )
    }

    /**
     * Dublyaj muvaffaqiyatli tugagach chaqiring.
     * Pro da yoki reklama yuklanmagan bo'lsa — darhol onDismissed.
     */
    fun showInterstitialAfterDub(activity: Activity, onDismissed: () -> Unit = {}) {
        if (!shouldShowAds(activity)) {
            onDismissed()
            return
        }
        val ad = interstitial
        if (ad == null) {
            Timber.d("Interstitial tayyor emas — o'tkazib yuborildi")
            preloadInterstitial(activity.applicationContext)
            onDismissed()
            return
        }
        val prev = ad.fullScreenContentCallback
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                prev?.onAdDismissedFullScreenContent()
                onDismissed()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                prev?.onAdFailedToShowFullScreenContent(error)
                onDismissed()
            }
            override fun onAdShowedFullScreenContent() {
                prev?.onAdShowedFullScreenContent()
            }
        }
        try {
            ad.show(activity)
            interstitial = null
        } catch (e: Exception) {
            Timber.e(e, "Interstitial show xato")
            interstitial = null
            onDismissed()
        }
    }
}
