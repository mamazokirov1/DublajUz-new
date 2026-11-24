package uz.dublaj.monetization

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import timber.log.Timber
import uz.dublaj.BuildConfig
import uz.dublaj.data.ConfigStore
import java.lang.ref.WeakReference

/**
 * Google AdMob — banner, interstitial, rewarded va ilova ochilganda App Open.
 *
 * Reklama ID lari build vaqtida beriladi (app/build.gradle.kts):
 * - debug: har doim Google TEST ID lari
 * - release: CI Secrets (ADMOB_APP_ID, ADMOB_BANNER_ID, ADMOB_INTERSTITIAL_ID, ADMOB_REWARDED_ID, ADMOB_APP_OPEN_ID); berilmasa TEST ID
 *
 * Reklama faqat foydalanuvchi roziligi (UMP) olingandan keyin ishga tushadi — [ConsentManager].
 */
object AdsManager {

    /** AdMob banner birligi (build'dan). */
    val BANNER_AD_UNIT: String = BuildConfig.ADMOB_BANNER_ID

    /** AdMob interstitial birligi (build'dan). */
    val INTERSTITIAL_AD_UNIT: String = BuildConfig.ADMOB_INTERSTITIAL_ID

    /** AdMob rewarded (mukofotli) birligi (build'dan). */
    val REWARDED_AD_UNIT: String = BuildConfig.ADMOB_REWARDED_ID

    /** AdMob App Open birligi (build'dan). */
    val APP_OPEN_AD_UNIT: String = BuildConfig.ADMOB_APP_OPEN_ID

    private const val OPEN_TTL = 4 * 60 * 60 * 1000L
    private const val OPEN_BG_MIN = 60_000L
    private const val OPEN_GAP = 2 * 60 * 1000L
    private const val FS_GAP = 15_000L
    private const val COLD_WAIT = 8_000L

    private var appOpen: AppOpenAd? = null
    private var appOpenAt = 0L
    private var loadingOpen = false
    private var showingFs = false
    private var lastFsEnd = 0L
    private var lastOpenShown = 0L
    private var bgAt = 0L
    private var firstStart = true
    private var coldDeadline = 0L
    private var coldAct: WeakReference<ComponentActivity>? = null

    /** Mukofotli reklama ko'rsatishga tayyor bo'lganda true. */
    var rewardedReady by mutableStateOf(false)
        private set
    private var rewarded: RewardedAd? = null
    private var loadingRewarded = false

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
                        preloadRewarded(app)
                        preloadAppOpen(app)
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
                            fsEnd()
                            interstitial = null
                            preloadInterstitial(context.applicationContext)
                        }
                        override fun onAdFailedToShowFullScreenContent(error: AdError) {
                            Timber.w("Interstitial ko'rsatilmadi: ${error.message}")
                            fsEnd()
                            interstitial = null
                            preloadInterstitial(context.applicationContext)
                        }
                        override fun onAdShowedFullScreenContent() {
                            showingFs = true
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

    private fun fsEnd() {
        showingFs = false
        lastFsEnd = SystemClock.elapsedRealtime()
    }

    private fun openFresh(): Boolean = appOpen != null && SystemClock.elapsedRealtime() - appOpenAt < OPEN_TTL

    fun preloadAppOpen(context: Context) {
        if (!shouldShowAds(context) || !adsReady) return
        if (loadingOpen || openFresh()) return
        loadingOpen = true
        val app = context.applicationContext
        AppOpenAd.load(app, APP_OPEN_AD_UNIT, AdRequest.Builder().build(), object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) {
                loadingOpen = false
                appOpen = ad
                appOpenAt = SystemClock.elapsedRealtime()
                Timber.i("App Open yuklandi")
                tryColdShow()
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                loadingOpen = false
                appOpen = null
                Timber.w("App Open yuklanmadi: ${error.message} (${error.code})")
            }
        })
    }

    /**
     * Qoidalar (Google siyosati va foydalanuvchi qulayligi): Pro da yo'q; birinchi ishga tushishda (kirish ekranlari tugamaguncha) yo'q;
     * boshqa to'liq ekran reklamadan keyin 15 s ichida yo'q; ikki App Open orasida kamida 2 daqiqa.
     */
    private fun canShowOpen(act: ComponentActivity): Boolean {
        val now = SystemClock.elapsedRealtime()
        return adsReady && !showingFs && openFresh() && shouldShowAds(act) &&
            ConfigStore.load(act).onboardingDone &&
            now - lastFsEnd > FS_GAP &&
            (lastOpenShown == 0L || now - lastOpenShown > OPEN_GAP) &&
            act.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }

    private fun showOpen(act: ComponentActivity) {
        val ad = appOpen ?: return
        appOpen = null
        val app = act.applicationContext
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                showingFs = true
                lastOpenShown = SystemClock.elapsedRealtime()
                Timber.i("App Open ko'rsatildi")
            }
            override fun onAdDismissedFullScreenContent() {
                fsEnd()
                preloadAppOpen(app)
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Timber.w("App Open ko'rsatilmadi: ${error.message}")
                fsEnd()
                preloadAppOpen(app)
            }
        }
        try {
            showingFs = true
            ad.show(act)
        } catch (e: Exception) {
            Timber.e(e, "App Open show xato")
            fsEnd()
        }
    }

    /** Ilova sovuq ochilganda: reklama ~8 soniyagacha yuklanishini kutadi, ulgurmasa o'tkazib yuboradi (foydalanuvchini kuttirmaydi). */
    fun requestColdAppOpen(act: ComponentActivity) {
        coldAct = WeakReference(act)
        coldDeadline = SystemClock.elapsedRealtime() + COLD_WAIT
        tryColdShow()
    }

    private fun tryColdShow() {
        val act = coldAct?.get() ?: return
        if (SystemClock.elapsedRealtime() > coldDeadline) { coldAct = null; return }
        if (!openFresh()) return
        coldAct = null
        if (canShowOpen(act)) showOpen(act)
    }

    /** Activity.onStart dan: ilova fondan qaytganda (kamida 60 s tashqarida bo'lgan bo'lsa) App Open ko'rsatadi. */
    fun onForeground(act: ComponentActivity) {
        if (firstStart) { firstStart = false; return }
        if (bgAt == 0L || SystemClock.elapsedRealtime() - bgAt < OPEN_BG_MIN) return
        if (canShowOpen(act)) showOpen(act) else preloadAppOpen(act)
    }

    /** Activity.onStop dan. */
    fun onBackground() {
        bgAt = SystemClock.elapsedRealtime()
    }

    fun preloadRewarded(context: Context) {
        if (!shouldShowAds(context) || !adsReady) return
        if (loadingRewarded || rewarded != null) return
        loadingRewarded = true
        val app = context.applicationContext
        RewardedAd.load(app, REWARDED_AD_UNIT, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                loadingRewarded = false
                rewarded = ad
                rewardedReady = true
                Timber.i("Rewarded yuklandi")
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                loadingRewarded = false
                rewarded = null
                rewardedReady = false
                Timber.w("Rewarded yuklanmadi: ${error.message} (${error.code})")
            }
        })
    }

    /**
     * Foydalanuvchi o'zi tanlagan mukofotli reklamani ko'rsatadi.
     * [onEarned] — reklama oxirigacha ko'rilganda; [onUnavailable] — reklama tayyor emas.
     */
    fun showRewarded(activity: Activity, onEarned: () -> Unit, onUnavailable: () -> Unit) {
        val ad = if (adsReady) rewarded else null
        val app = activity.applicationContext
        if (ad == null) {
            preloadRewarded(app)
            onUnavailable()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { showingFs = true }
            override fun onAdDismissedFullScreenContent() {
                fsEnd()
                rewarded = null
                rewardedReady = false
                preloadRewarded(app)
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Timber.w("Rewarded ko'rsatilmadi: ${error.message}")
                fsEnd()
                rewarded = null
                rewardedReady = false
                preloadRewarded(app)
                onUnavailable()
            }
        }
        try {
            ad.show(activity, object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(reward: com.google.android.gms.ads.rewarded.RewardItem) { onEarned() }
            })
        } catch (e: Exception) {
            Timber.e(e, "Rewarded show xato")
            rewarded = null
            rewardedReady = false
            onUnavailable()
        }
    }

    private var lastSoftAt = 0L

    /** Ish tugagach (matn, ovoz) yumshoq interstitial: ikki reklama orasida kamida 90 s. */
    fun showInterstitialSoft(activity: Activity) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSoftAt < 90_000L || now - lastFsEnd < FS_GAP) return
        if (!shouldShowAds(activity) || !adsReady || interstitial == null) return
        lastSoftAt = now
        showInterstitialAfterDub(activity)
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
