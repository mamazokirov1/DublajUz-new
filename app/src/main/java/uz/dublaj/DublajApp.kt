package uz.dublaj

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import timber.log.Timber
import uz.dublaj.monetization.BillingManager

/**
 * Application ViewModelStoreOwner: asosiy ViewModel Activity yopilganda (Orqaga tugmasi, so'nggi ilovalardan
 * olib tashlash) yo'qolmaydi. Aks holda ishlab turgan dublyaj/tarjima ViewModel bilan birga bekor bo'lardi.
 * Jarayon ishlab turgan vaqtda (foreground servis bilan) ish davom etadi.
 */
class DublajApp : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        uz.dublaj.util.CrashLogger.install(this)
        // AdsManager.init — rozilikdan keyin MainActivity'da (ConsentManager)
        BillingManager.start(this)
    }
}
