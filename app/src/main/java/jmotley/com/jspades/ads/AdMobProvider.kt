package jmotley.com.jspades.ads

import android.app.Activity
import android.util.Log
import android.content.Context
import android.view.ViewGroup
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import jmotley.com.jspades.BuildConfig

/**
 * AdMob-backed provider. Used as the session-level fallback when LevelPlay
 * is unavailable or returns no fill.
 */
internal class AdMobProvider : AdProvider {

    // ── Unit IDs ──────────────────────────────────────────────────────────────

    private val ADMOB_INTERSTITIAL_ID = BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID
    private val ADMOB_BANNER_ID       = BuildConfig.ADMOB_BANNER_AD_UNIT_ID

    private val ADMOB_REWARDED_UNIT_IDS: Map<RewardedPlacement, String> = mapOf(
        RewardedPlacement.UNDO_LAST_TRICK  to BuildConfig.ADMOB_REWARDED_UNDO_TRICK,
        RewardedPlacement.PEEK_ONE_CARD    to BuildConfig.ADMOB_REWARDED_PEEK_CARD,
        RewardedPlacement.EXTRA_BOOK       to BuildConfig.ADMOB_REWARDED_EXTRA_BOOK,
        RewardedPlacement.REPLAY_LAST_HAND to BuildConfig.ADMOB_REWARDED_REPLAY_HAND,
        RewardedPlacement.BAG_FORGIVENESS  to BuildConfig.ADMOB_REWARDED_BAG_FORGIVENESS,
        RewardedPlacement.BID_ADJUST       to BuildConfig.ADMOB_REWARDED_BID_ADJUST,
    )

    private fun toAdMobRewardedUnitId(placement: RewardedPlacement): String? =
        ADMOB_REWARDED_UNIT_IDS[placement]?.ifBlank { null }

    // ── Cached ad state ───────────────────────────────────────────────────────

    private var appContext: Context? = null

    private var admobInterstitial: InterstitialAd? = null
    private var isLoadingInterstitial = false

    private val admobRewarded = mutableMapOf<RewardedPlacement, RewardedAd>()

    // ── AdProvider ────────────────────────────────────────────────────────────

    override fun initialize(context: Context) {
        if (!BuildConfig.GOOGLE_ADS_ENABLED) return
        appContext = context.applicationContext
        MobileAds.initialize(context)
        Log.i("REWARDDEBUG", "AdMobProvider.initialize called")
    }

    // ── Interstitial ──────────────────────────────────────────────────────────

    override fun preloadInterstitial() {
        if (!BuildConfig.GOOGLE_ADS_ENABLED) return
        if (isLoadingInterstitial || admobInterstitial != null) return
        if (ADMOB_INTERSTITIAL_ID.isBlank()) {
            Log.d("REWARDDEBUG", "AdMob preloadInterstitial skipped — no unit ID configured")
            return
        }
        isLoadingInterstitial = true
        loadAdMobInterstitial()
    }

    private fun loadAdMobInterstitial() {
        val ctx = appContext ?: run { isLoadingInterstitial = false; return }
        Log.d("REWARDDEBUG", "AdMob interstitial loading unit=$ADMOB_INTERSTITIAL_ID")
        InterstitialAd.load(
            ctx,
            ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d("REWARDDEBUG", "AdMob interstitial loaded unit=$ADMOB_INTERSTITIAL_ID")
                    admobInterstitial = ad
                    isLoadingInterstitial = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w("REWARDDEBUG", "AdMob interstitial failed to load unit=$ADMOB_INTERSTITIAL_ID err=${error.message}")
                    isLoadingInterstitial = false
                }
            }
        )
    }

    override fun canShowInterstitial(): Boolean = admobInterstitial != null

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit) {
        val ad = admobInterstitial
        if (ad != null) {
            Log.i("REWARDDEBUG", "AdMob interstitial showing")
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    Log.d("REWARDDEBUG", "AdMob interstitial dismissed")
                    admobInterstitial = null
                    onClosed()
                }
                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    Log.w("REWARDDEBUG", "AdMob interstitial failed to show err=${error.message}")
                    admobInterstitial = null
                    onClosed()
                }
            }
            ad.show(activity)
            return
        }
        // Not preloaded — attempt load+show inline
        Log.w("REWARDDEBUG", "AdMob interstitial not preloaded — loading inline")
        if (BuildConfig.GOOGLE_ADS_ENABLED && ADMOB_INTERSTITIAL_ID.isNotBlank()) {
            InterstitialAd.load(
                activity,
                ADMOB_INTERSTITIAL_ID,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        Log.d("REWARDDEBUG", "AdMob interstitial inline load succeeded")
                        admobInterstitial = ad
                        showInterstitial(activity, onClosed)
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w("REWARDDEBUG", "AdMob interstitial inline load failed err=${error.message}")
                        onClosed()
                    }
                }
            )
        } else {
            onClosed()
        }
    }

    // ── Rewarded ──────────────────────────────────────────────────────────────

    override fun preloadRewarded(placement: RewardedPlacement) {
        val ctx = appContext ?: return
        val unitId = toAdMobRewardedUnitId(placement) ?: run {
            Log.d("REWARDDEBUG", "AdMob preloadRewarded skipped — no unit ID configured for placement=$placement")
            return
        }
        if (admobRewarded.containsKey(placement)) return
        RewardedAd.load(
            ctx,
            unitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    Log.d("REWARDDEBUG", "AdMob rewarded loaded for placement=$placement unit=$unitId")
                    admobRewarded[placement] = ad
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w("REWARDDEBUG", "AdMob rewarded failed to load for placement=$placement unit=$unitId err=${error.message}")
                    admobRewarded.remove(placement)
                }
            }
        )
    }

    override fun canShowRewarded(placement: RewardedPlacement): Boolean =
        admobRewarded.containsKey(placement)

    override fun showRewarded(
        activity: Activity,
        placement: RewardedPlacement,
        onReward: () -> Unit,
        onClosed: () -> Unit,
    ) {
        val ad = admobRewarded.remove(placement)
        if (ad == null) { onClosed(); return }
        Log.i("REWARDDEBUG", "AdMob.showRewarded showing placement=$placement")
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d("REWARDDEBUG", "AdMob rewarded dismissed placement=$placement")
                onClosed()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w("REWARDDEBUG", "AdMob rewarded failed to show placement=$placement err=${error.message}")
                onClosed()
            }
        }
        ad.show(activity) {
            Log.i("REWARDDEBUG", "AdMob rewarded granted placement=$placement")
            onReward()
        }
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    private var adView: AdView? = null

    override fun showBanner(container: ViewGroup, onFailed: () -> Unit) {
        if (!BuildConfig.GOOGLE_ADS_ENABLED) return
        if (ADMOB_BANNER_ID.isBlank()) {
            Log.d("REWARDDEBUG", "AdMob showBanner skipped — no unit ID configured")
            return
        }
        adView?.destroy()
        val av = AdView(container.context)
        av.setAdSize(AdSize.BANNER)
        av.adUnitId = ADMOB_BANNER_ID
        av.adListener = object : com.google.android.gms.ads.AdListener() {
            override fun onAdLoaded() {
                Log.d("REWARDDEBUG", "AdMob banner loaded unit=$ADMOB_BANNER_ID")
            }
            override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                Log.w("REWARDDEBUG", "AdMob banner failed to load unit=$ADMOB_BANNER_ID err=${error.message}")
            }
        }
        container.removeAllViews()
        container.addView(av)
        Log.d("REWARDDEBUG", "AdMob banner loading unit=$ADMOB_BANNER_ID")
        av.loadAd(AdRequest.Builder().build())
        adView = av
    }

    override fun hideBanner() {
        adView?.destroy()
        adView = null
    }
}
