package jmotley.com.jspades.ads

import android.app.Activity
import android.util.Log
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
 * Each rewarded placement has its own LevelPlay ad unit ID so fill and pacing can
 * be tuned independently in the LevelPlay dashboard.
 */
internal class LevelPlayProvider : AdProvider {

    // ── Ad unit IDs (set in LevelPlay dashboard, surfaced via BuildConfig) ────

    private val INTERSTITIAL_AD_UNIT = BuildConfig.LEVEL_PLAY_INTERSTITIAL_AD_UNIT
    private val BANNER_AD_UNIT       = BuildConfig.LEVEL_PLAY_BANNER_AD_UNIT

    private val REWARDED_UNIT_IDS: Map<RewardedPlacement, String> = mapOf(
        RewardedPlacement.UNDO_LAST_TRICK  to BuildConfig.LEVEL_PLAY_REWARDED_UNDO_TRICK,
        RewardedPlacement.PEEK_ONE_CARD    to BuildConfig.LEVEL_PLAY_REWARDED_PEEK_CARD,
        RewardedPlacement.EXTRA_BOOK       to BuildConfig.LEVEL_PLAY_REWARDED_EXTRA_BOOK,
        RewardedPlacement.REPLAY_LAST_HAND to BuildConfig.LEVEL_PLAY_REWARDED_REPLAY_HAND,
        RewardedPlacement.BAG_FORGIVENESS  to BuildConfig.LEVEL_PLAY_REWARDED_BAG_FORGIVENESS,
        RewardedPlacement.BID_ADJUST       to BuildConfig.LEVEL_PLAY_REWARDED_BID_ADJUST,
    )

    private val REWARDED_PLACEMENT_NAMES: Map<RewardedPlacement, String> = mapOf(
        RewardedPlacement.UNDO_LAST_TRICK  to "reward-undotrick",
        RewardedPlacement.PEEK_ONE_CARD    to "reward-peekcard",
        RewardedPlacement.EXTRA_BOOK       to "reward-extrabook",
        RewardedPlacement.REPLAY_LAST_HAND to "reward-replayhand",
        RewardedPlacement.BAG_FORGIVENESS  to "reward-bagforgive",
        RewardedPlacement.BID_ADJUST       to "reward-bidadjust",
    )

    // ── Ad objects ────────────────────────────────────────────────────────────

    private var interstitialAd: LevelPlayInterstitialAd? = null
    private val rewardedAds = mutableMapOf<RewardedPlacement, LevelPlayRewardedAd>()
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
                Log.i("REWARDDEBUG", "LevelPlay.init success appKey=$appKey")
                setupInterstitialAd()
                setupRewardedAds()
                AdManager.preloadForSession()
            }
            override fun onInitFailed(error: LevelPlayInitError) {
                Log.w("REWARDDEBUG", "LevelPlay.init failed: ${error.errorMessage}")
                AdManager.failOverToAdMob("LevelPlay init failed: ${error.errorMessage}")
            }
        })
    }

    // ── Interstitial ──────────────────────────────────────────────────────────

    private fun setupInterstitialAd() {
        if (INTERSTITIAL_AD_UNIT.isBlank()) return
        val ad = LevelPlayInterstitialAd(INTERSTITIAL_AD_UNIT)
        ad.setListener(object : LevelPlayInterstitialAdListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) {
                Log.d("REWARDDEBUG", "LevelPlay interstitial loaded: ${adInfo.adUnitId}")
            }
            override fun onAdLoadFailed(error: LevelPlayAdError) {
                Log.w("REWARDDEBUG", "LevelPlay interstitial load failed: ${error.errorMessage}")
            }
            override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {
                Log.d("REWARDDEBUG", "LevelPlay interstitial displayed: ${adInfo.adUnitId}")
            }
            override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) {
                Log.w("REWARDDEBUG", "LevelPlay interstitial display failed: ${error.errorMessage}")
                pendingInterstitialOnClosed?.invoke()
                pendingInterstitialOnClosed = null
            }
            override fun onAdClosed(adInfo: LevelPlayAdInfo) {
                Log.d("REWARDDEBUG", "LevelPlay interstitial closed: ${adInfo.adUnitId}")
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

    private fun setupRewardedAds() {
        REWARDED_UNIT_IDS.forEach { (placement, unitId) ->
            if (unitId.isBlank()) return@forEach
            val ad = LevelPlayRewardedAd(unitId)
            ad.setListener(object : LevelPlayRewardedAdListener {
                override fun onAdLoaded(adInfo: LevelPlayAdInfo) {
                    Log.d("REWARDDEBUG", "LevelPlay rewarded loaded: ${adInfo.adUnitId} placement=$placement")
                }
                override fun onAdLoadFailed(error: LevelPlayAdError) {
                    Log.w("REWARDDEBUG", "LevelPlay rewarded load failed: placement=$placement err=${error.errorMessage}")
                }
                override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {
                    Log.d("REWARDDEBUG", "LevelPlay rewarded displayed: ${adInfo.adUnitId} placement=$placement")
                }
                override fun onAdRewarded(reward: LevelPlayReward, adInfo: LevelPlayAdInfo) {
                    Log.i("REWARDDEBUG", "LevelPlay rewarded granted: ${adInfo.adUnitId} placement=$placement reward=${reward.amount}")
                    pendingRewardedOnReward?.invoke()
                    pendingRewardedOnReward = null
                }
                override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) {
                    Log.w("REWARDDEBUG", "LevelPlay rewarded display failed: placement=$placement err=${error.errorMessage}")
                    pendingRewardedOnReward = null
                    pendingRewardedOnClosed?.invoke()
                    pendingRewardedOnClosed = null
                }
                override fun onAdClosed(adInfo: LevelPlayAdInfo) {
                    Log.d("REWARDDEBUG", "LevelPlay rewarded closed: ${adInfo.adUnitId} placement=$placement")
                    pendingRewardedOnReward = null
                    pendingRewardedOnClosed?.invoke()
                    pendingRewardedOnClosed = null
                }
            })
            rewardedAds[placement] = ad
        }
    }

    override fun preloadRewarded(placement: RewardedPlacement) {
        if (!initialized) {
            Log.d("REWARDDEBUG", "LevelPlay preloadRewarded skipped — not initialized placement=$placement")
            return
        }
        val ad = rewardedAds[placement] ?: run {
            Log.d("REWARDDEBUG", "LevelPlay preloadRewarded skipped — no ad unit configured placement=$placement")
            return
        }
        if (ad.isAdReady) {
            Log.d("REWARDDEBUG", "LevelPlay preloadRewarded skipped — ad already ready placement=$placement")
            return
        }
        Log.d("REWARDDEBUG", "LevelPlay preloadRewarded calling loadAd placement=$placement")
        ad.loadAd()
    }

    override fun canShowRewarded(placement: RewardedPlacement): Boolean =
        initialized && rewardedAds[placement]?.isAdReady == true

    override fun showRewarded(
        activity: Activity,
        placement: RewardedPlacement,
        onReward: () -> Unit,
        onClosed: () -> Unit,
    ) {
        val ad = rewardedAds[placement] ?: run {
            Log.w("REWARDDEBUG", "LevelPlay.showRewarded — no ad object for placement=$placement; invoking onClosed")
            onClosed(); return
        }
        val placementName = REWARDED_PLACEMENT_NAMES[placement]
        Log.i("REWARDDEBUG", "LevelPlay.showRewarded requested placement=$placement placementName=$placementName")
        pendingRewardedOnReward = onReward
        pendingRewardedOnClosed = onClosed
        if (placementName != null) ad.showAd(activity, placementName) else ad.showAd(activity)
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    override fun showBanner(container: ViewGroup, onFailed: () -> Unit) {
        if (BANNER_AD_UNIT.isBlank()) { onFailed(); return }
        bannerAdView?.destroy()
        val config = LevelPlayBannerAdView.Config.Builder()
            .setAdSize(LevelPlayAdSize.BANNER)
            .build()
        val banner = LevelPlayBannerAdView(container.context, BANNER_AD_UNIT, config)
        banner.setBannerListener(object : LevelPlayBannerAdViewListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) {
                Log.d("REWARDDEBUG", "LevelPlay banner loaded: ${adInfo.adUnitId}")
            }
            override fun onAdLoadFailed(error: LevelPlayAdError) {
                Log.w("REWARDDEBUG", "LevelPlay banner load failed: ${error.errorMessage} — trying AdMob fallback")
                onFailed()
            }
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

    // LevelPlay SDK 9.x handles lifecycle internally — no manual forwarding needed.
    override fun onResume(activity: Activity) {}
    override fun onPause(activity: Activity)  {}

}
