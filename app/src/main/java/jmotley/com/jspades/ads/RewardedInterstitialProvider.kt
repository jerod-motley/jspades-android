package jmotley.com.jspades.ads

import android.app.Activity
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdPreloader
import jmotley.com.jspades.BuildConfig

/**
 * Dedicated AdMob Rewarded Interstitial ad unit for the "watch one ad, skip N interstitials"
 * offer. Kept separate from [LevelPlayProvider]/[AdMobProvider] and the [AdProvider] contract —
 * it's a sibling owned directly by [AdManager], not part of the regular-interstitial
 * LevelPlay-then-AdMob failover chain.
 *
 * Uses the GMA Next-Gen preloader API, which manages its own cache/retry/expiration — call
 * [startPreloading] once after AdMob's Next-Gen SDK init completes, no manual re-preload needed.
 */
internal class RewardedInterstitialProvider {

    private val unitId: String
        get() = BuildConfig.ADMOB_REWARDED_INTERSTITIAL_UNIT_ID
            .ifBlank { "ca-app-pub-3940256099942544/5354046379" } // Google test rewarded-interstitial unit

    /** Fired whenever [isReady] may have changed (preloaded, exhausted, or consumed). */
    var onReadinessChanged: () -> Unit = {}

    val isReady: Boolean
        get() = RewardedInterstitialAdPreloader.isAdAvailable(unitId)

    fun startPreloading() {
        if (!BuildConfig.GOOGLE_ADS_ENABLED) return
        val adRequest = AdRequest.Builder(unitId).build()
        val preloadConfig = PreloadConfiguration(adRequest, bufferSize = 2)
        RewardedInterstitialAdPreloader.start(
            unitId,
            preloadConfig,
            object : PreloadCallback {
                override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
                    Log.d("REWARDDEBUG", "rewarded-interstitial preloaded unit=$unitId")
                    onReadinessChanged()
                }
                override fun onAdsExhausted(preloadId: String) {
                    Log.d("REWARDDEBUG", "rewarded-interstitial buffer exhausted unit=$unitId")
                    onReadinessChanged()
                }
                override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
                    Log.w("REWARDDEBUG", "rewarded-interstitial preload failed unit=$unitId err=${adError.message}")
                    onReadinessChanged()
                }
            }
        )
    }

    /**
     * Shows a buffered ad. [onReward] fires only from the SDK's verified reward callback —
     * never call it from anywhere else. [onComplete] always fires exactly once, with `earned`
     * reflecting whether [onReward] ran before dismissal.
     */
    fun present(activity: Activity, onReward: () -> Unit, onComplete: (earned: Boolean) -> Unit) {
        val ad = RewardedInterstitialAdPreloader.pollAd(unitId)
        if (ad == null) {
            Log.w("REWARDDEBUG", "rewarded-interstitial present() called with no ad buffered")
            onComplete(false)
            return
        }
        onReadinessChanged() // buffer count just dropped
        var earned = false
        // Attach the event callback before show(). The reward lambda fires first (sets `earned`),
        // then onAdDismissedFullScreenContent runs onComplete — exactly one of dismissed /
        // failed-to-show fires per show() call.
        ad.adEventCallback = object : RewardedInterstitialAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                onReadinessChanged()
                onComplete(earned)
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                Log.w("REWARDDEBUG", "rewarded-interstitial failed to show err=${fullScreenContentError.message}")
                onReadinessChanged()
                onComplete(false)
            }
        }
        ad.show(activity) { _ ->
            earned = true
            onReward()
        }
    }
}
