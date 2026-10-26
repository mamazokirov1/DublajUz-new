package uz.dublaj.monetization

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import timber.log.Timber

/**
 * Google UMP — reklama roziligi (GDPR/EEA/UK va boshqa hududlar).
 * Rozilik olinmaguncha AdMob ishga tushmaydi. Pro foydalanuvchida rozilik so'ralmaydi.
 */
object ConsentManager {

    fun gatherAndInit(activity: Activity) {
        val app = activity.applicationContext
        if (!AdsManager.shouldShowAds(app)) return
        val info = UserMessagingPlatform.getConsentInformation(activity)

        // Oldingi sessiyada rozilik olingan bo'lsa — reklamani darhol boshlaymiz
        if (info.canRequestAds()) AdsManager.init(app)

        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { err ->
                    if (err != null) Timber.w("UMP forma: ${err.message}")
                    if (info.canRequestAds()) AdsManager.init(app)
                }
            },
            { err ->
                Timber.w("UMP yangilash xato: ${err.message}")
                if (info.canRequestAds()) AdsManager.init(app)
            }
        )
    }

    /** Sozlamalarda "Reklama maxfiyligi" tugmasi ko'rsatilsinmi (hududga qarab). */
    fun privacyOptionsRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { err ->
            if (err != null) Timber.w("UMP privacy options: ${err.message}")
        }
    }
}
