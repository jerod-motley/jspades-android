package jmotley.com.jspades.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import com.unity3d.mediation.LevelPlay
import com.unity3d.mediation.LevelPlayAdError
import com.unity3d.mediation.LevelPlayAdInfo
import com.unity3d.mediation.LevelPlayAdSize
import com.unity3d.mediation.LevelPlayInitError
import com.unity3d.mediation.LevelPlayInitListener
import com.unity3d.mediation.LevelPlayInitRequest
import com.unity3d.mediation.banner.LevelPlayBannerAdView
import com.unity3d.mediation.banner.LevelPlayBannerAdViewListener
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAd
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAdListener
import com.unity3d.mediation.rewarded.LevelPlayReward
import com.unity3d.mediation.rewarded.LevelPlayRewardedAd
import com.unity3d.mediation.rewarded.LevelPlayRewardedAdListener
import jmotley.com.jspades.BuildConfig

/**
 * LevelPlay (Unity mediation) provider. Primary ad provider for the session.
 *
 * LevelPlay runs a waterfall internally across Unity Ads, AdMob, and Meta — the app
 * never talks to those networks directly during the mediation path. If the entire
 * waterfall returns no fill, [AdManager.failOverToAdMob] handles the fallback.
 *
 * All six rewarded cheat placements share a single LevelPlay rewarded ad unit ID.
 * The distinction between placements is a game-level concern, invisible to LevelPlay.
 */
internal class LevelPlayProvider : AdProvider {

    // ── Ad unit IDs (set in LevelPlay dashboard, surfaced via BuildConfig) ────

    private val INTERSTITIAL_AD_UNIT = BuildConfig.LEVEL_PLAY_INTERSTITIAL_AD_UNIT
    private val REWARDED_AD_UNIT     = BuildConfig.LEVEL_PLAY_REWARDED_AD_UNIT
    private val BANNER_AD_UNIT       = BuildConfig.LEVEL_PLAY_BANNER_AD_UNIT

    // ── Ad objects ────────────────────────────────────────────────────────────

    private var interstitialAd: LevelPlayInterstitialAd? = null
    private var rewardedAd: LevelPlayRewardedAd? = null
    private var bannerAdView: LevelPlayBannerAdView? = null

    // Pending show callbacks — set just before showAd() so the listener can invoke them.
    private var pendingInterstitialOnClosed: (() -> Unit)? = null
    private var pendingRewardedOnReward: (() -> Unit)? = null
    private var pendingRewardedOnClosed: (() -> Unit)? = null

    private var initialized = false

    // ── AdProvider ────────────────────────────────────────────────────────────

    override fun initialize(context: Context) {
        if (!BuildConfig.UNITY_ADS_ENABLED) return
        val appKey = BuildConfig.LEVEL_PLAY_APP_KEY
        if (appKey.isBlank()) return   // dashboard not configured yet — safe no-op

        val request = LevelPlayInitRequest.Builder(appKey).build()
        LevelPlay.init(context, request, object : LevelPlayInitListener {
            override fun onInitSuccess(configuration: com.unity3d.mediation.LevelPlayConfiguration) {
                initialized = true
                setupInterstitialAd()
                setupRewardedAd()
            }
            override fun onInitFailed(error: LevelPlayInitError) {
                AdManager.failOverToAdMob("LevelPlay init failed: ${error.errorMessage}")
            }
        })
    }

    // ── Interstitial ──────────────────────────────────────────────────────────

    private fun setupInterstitialAd() {
        if (INTERSTITIAL_AD_UNIT.isBlank()) return
        val ad = LevelPlayInterstitialAd(INTERSTITIAL_AD_UNIT)
        ad.setListener(object : LevelPlayInterstitialAdListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) {}
            override fun onAdLoadFailed(error: LevelPlayAdError) {}
            override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
            override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) {
                pendingInterstitialOnClosed?.invoke()
                pendingInterstitialOnClosed = null
            }
            override fun onAdClosed(adInfo: LevelPlayAdInfo) {
                pendingInterstitialOnClosed?.invoke()
                pendingInterstitialOnClosed = null
            }
        })
        interstitialAd = ad
    }

    override fun preloadInterstitial() {
        if (!initialized) return
        interstitialAd?.loadAd()
    }

    override fun canShowInterstitial(): Boolean =
        initialized && interstitialAd?.isAdReady == true

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit) {
        val ad = interstitialAd ?: run { onClosed(); return }
        pendingInterstitialOnClosed = onClosed
        ad.showAd(activity)
    }

    // ── Rewarded ──────────────────────────────────────────────────────────────

    private fun setupRewardedAd() {
        if (REWARDED_AD_UNIT.isBlank()) return
        val ad = LevelPlayRewardedAd(REWARDED_AD_UNIT)
        ad.setListener(object : LevelPlayRewardedAdListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) {}
            override fun onAdLoadFailed(error: LevelPlayAdError) {}
            override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
            override fun onAdRewarded(reward: LevelPlayReward, adInfo: LevelPlayAdInfo) {
                pendingRewardedOnReward?.invoke()
                pendingRewardedOnReward = null
            }
            override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) {
                pendingRewardedOnReward = null
                pendingRewardedOnClosed?.invoke()
                pendingRewardedOnClosed = null
            }
            override fun onAdClosed(adInfo: LevelPlayAdInfo) {
                pendingRewardedOnReward = null
                pendingRewardedOnClosed?.invoke()
                pendingRewardedOnClosed = null
            }
        })
        rewardedAd = ad
    }

    override fun preloadRewarded(placement: RewardedPlacement) {
        // All placements share one ad unit — load once; subsequent calls are no-ops if already loaded.
        if (!initialized || rewardedAd?.isAdReady == true) return
        rewardedAd?.loadAd()
    }

    override fun canShowRewarded(placement: RewardedPlacement): Boolean =
        initialized && rewardedAd?.isAdReady == true

    override fun showRewarded(
        activity: Activity,
        placement: RewardedPlacement,
        onReward: () -> Unit,
        onClosed: () -> Unit,
    ) {
        val ad = rewardedAd ?: run { onClosed(); return }
        pendingRewardedOnReward = onReward
        pendingRewardedOnClosed = onClosed
        ad.showAd(activity)
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    override fun showBanner(container: ViewGroup) {
        if (BANNER_AD_UNIT.isBlank()) return
        bannerAdView?.destroy()
        val config = LevelPlayBannerAdView.Config.Builder()
            .setAdSize(LevelPlayAdSize.BANNER)
            .build()
        val banner = LevelPlayBannerAdView(container.context, BANNER_AD_UNIT, config)
        banner.setBannerListener(object : LevelPlayBannerAdViewListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) {}
            override fun onAdLoadFailed(error: LevelPlayAdError) {}
        })
        container.removeAllViews()
        container.addView(banner)
        banner.loadAd()
        bannerAdView = banner
    }

    override fun hideBanner() {
        bannerAdView?.destroy()
        bannerAdView = null
    }

}
