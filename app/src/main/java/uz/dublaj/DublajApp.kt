package uz.dublaj

import android.app.Application
import timber.log.Timber
import uz.dublaj.monetization.AdsManager
import uz.dublaj.monetization.BillingManager

class DublajApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        uz.dublaj.util.CrashLogger.install(this)
        AdsManager.init(this)
        BillingManager.start(this)
    }
}
