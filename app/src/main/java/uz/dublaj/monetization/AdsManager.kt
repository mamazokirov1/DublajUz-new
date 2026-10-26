package uz.dublaj.monetization

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import timber.log.Timber
import uz.dublaj.BuildConfig
import uz.dublaj.data.ConfigStore

/**
 * Google AdMob — banner va interstitial.
 *
 * Reklama ID lari build vaqtida beriladi (app/build.gradle.kts):
 * - debug: har doim Google TEST ID lari
 * - release: CI Secrets (ADMOB_APP_ID, ADMOB_BANNER_ID, ADMOB_INTERSTITIAL_ID); berilmasa TEST ID
 *
 * Reklama faqat foydalanuvchi roziligi (UMP) olingandan keyin ishga tushadi — [ConsentManager].
 */
object AdsManager {

    /** AdMob banner birligi (build'dan). */
    val BANNER_AD_UNIT: String = BuildConfig.ADMOB_BANNER_ID

    /** AdMob interstitial birligi (build'dan). */
    val INTERSTITIAL_AD_UNIT: String = BuildConfig.ADMOB_INTERSTITIAL_ID

    /** SDK ishga tushib, reklama so'rash mumkin bo'lganda true (BannerAd shuni kuzatadi). */
    var adsReady by mutableStateOf(false)
        private set

    private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var loadingInterstitial = false

    /** Faqat rozilik olingandan keyin chaqiriladi ([ConsentManager.gatherAndInit]). */
    fun init(context: Context) {
        if (initialized) return
        if (!shouldShowAds(context)) return
        initialized = true
        val app = context.applicationContext
        // Google tavsiyasi: MobileAds.initialize ni fon oqimida chaqirish (ANR xavfi)
        Thread {
            try {
                MobileAds.initialize(app) { status ->
                    Timber.i("AdMob initialized: ${status.adapterStatusMap.keys}")
                    Handler(Looper.getMainLooper()).post {
                        adsReady = true
                        preloadInterstitial(app)
                    }
                }
            } catch (e: Exception) {
                initialized = false
                Timber.e(e, "AdMob init xato")
            }
        }.start()
    }

    fun shouldShowAds(context: Context): Boolean = !ConfigStore.load(context).isPro

    fun bannerAdUnitId(): String = BANNER_AD_UNIT

    fun preloadInterstitial(context: Context) {
        if (!shouldShowAds(context)) {
            interstitial = null
            return
        }
        if (!adsReady) return
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
        val ad = if (adsReady) interstitial else null
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
