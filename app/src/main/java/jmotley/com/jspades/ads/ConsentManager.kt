package jmotley.com.jspades.ads

import android.app.Activity
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Thin wrapper around Google's User Messaging Platform (UMP) SDK.
 *
 * Call [requestConsent] once at app start (before loading any ads).
 * Use [canServeAds] before every ad load or display.
 */
object ConsentManager {

    /**
     * Requests or refreshes consent info and shows the form if required.
     * [onReadyForAds] fires on the main thread once consent status is known.
     */
    fun requestConsent(activity: Activity, onReadyForAds: () -> Unit) {
        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()

        consentInfo.requestConsentInfoUpdate(
            activity,
            params,
            {
                // Success — loadAndShowConsentFormIfRequired shows form when needed
                // or calls back immediately for users who don't require one.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    onReadyForAds()
                }
            },
            { _ ->
                // Network error — rely on cached consent if available
                if (consentInfo.canRequestAds()) onReadyForAds()
            }
        )
    }

    fun canServeAds(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity).canRequestAds()
}
