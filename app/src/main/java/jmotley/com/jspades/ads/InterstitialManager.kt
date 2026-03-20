package jmotley.com.jspades.ads

import android.app.Activity
import com.facebook.ads.Ad
import com.facebook.ads.AdError as FbAdError
import com.facebook.ads.InterstitialAdListener
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import jmotley.com.jspades.BuildConfig
import jmotley.com.jspades.R

/**
 * Singleton that manages one cached interstitial at a time (AdMob primary, FAN fallback).
 *
 * Typical usage:
 *   - Call [preload] after consent resolves and after each ad is dismissed.
 *   - Call [showIfReady] at trigger points; [onClosed] is always invoked.
 */
object InterstitialManager {

    private const val ADMOB_UNIT_ID = "ca-app-pub-9978563261260279/2189775212" //"ca-app-pub-3940256099942544/1033173712" // Google test ID

    private var admobAd: InterstitialAd? = null
    private var fbAd: com.facebook.ads.InterstitialAd? = null
    private var isLoading = false

    // Stored so the FAN listener (set at load time) can invoke the show-time callback.
    private var pendingOnClosed: (() -> Unit)? = null

    // ── Preload ───────────────────────────────────────────────────────────────

    fun preload(activity: Activity) {
        if (!ConsentManager.canServeAds(activity)) return
        if (isLoading || admobAd != null || fbAd != null) return
        isLoading = true
        if (BuildConfig.GOOGLE_ADS_ENABLED) loadAdmob(activity) else loadFacebook(activity)
    }

    private fun loadAdmob(activity: Activity) {
        InterstitialAd.load(
            activity,
            ADMOB_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    admobAd = ad
                    isLoading = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    loadFacebook(activity)
                }
            }
        )
    }

    private fun loadFacebook(activity: Activity) {
        if (!BuildConfig.FACEBOOK_ADS_ENABLED) { isLoading = false; return }
        val placementId = activity.getString(R.string.fb_interstitial_placement)
        val ad = com.facebook.ads.InterstitialAd(activity, placementId)
        ad.loadAd(
            ad.buildLoadAdConfig()
                .withAdListener(object : InterstitialAdListener {
                    override fun onInterstitialDisplayed(ad: Ad?) {}
                    override fun onInterstitialDismissed(ad: Ad?) {
                        fbAd = null
                        pendingOnClosed?.invoke()
                        pendingOnClosed = null
                    }
                    override fun onError(ad: Ad?, error: FbAdError?) {
                        fbAd = null
                        pendingOnClosed?.invoke()
                        pendingOnClosed = null
                    }
                    override fun onAdLoaded(ad: Ad?) { isLoading = false }
                    override fun onAdClicked(ad: Ad?) {}
                    override fun onLoggingImpression(ad: Ad?) {}
                })
                .build()
        )
        fbAd = ad
    }

    // ── Show ──────────────────────────────────────────────────────────────────

    /**
     * Shows a cached interstitial if one is ready, or calls [onClosed] immediately.
     * [onClosed] is always invoked exactly once, on the main thread.
     */
    fun showIfReady(activity: Activity, onClosed: () -> Unit) {
        if (!BuildConfig.GOOGLE_ADS_ENABLED) { showFacebook(activity, onClosed); return }
        val ad = admobAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    admobAd = null
                    onClosed()
                }
                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    admobAd = null
                    showFacebook(activity, onClosed)
                }
            }
            ad.show(activity)
        } else {
            showFacebook(activity, onClosed)
        }
    }

    private fun showFacebook(activity: Activity, onClosed: () -> Unit) {
        if (!BuildConfig.FACEBOOK_ADS_ENABLED) { onClosed(); return }
        val ad = fbAd
        if (ad != null && ad.isAdLoaded) {
            pendingOnClosed = onClosed
            ad.show()
        } else {
            onClosed()
        }
    }
}
