package jmotley.com.jspades.ads

import android.app.Activity
import android.util.Log
import android.content.Context
import android.view.ViewGroup
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import jmotley.com.jspades.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AdMob-backed provider (GMA Next-Gen SDK). Used as the session-level fallback when
 * LevelPlay is unavailable or returns no fill.
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

    private var admobInterstitial: InterstitialAd? = null
    private var isLoadingInterstitial = false

    private val admobRewarded = mutableMapOf<RewardedPlacement, RewardedAd>()

    // ── AdProvider ────────────────────────────────────────────────────────────

    override fun initialize(context: Context) {
        if (!BuildConfig.GOOGLE_ADS_ENABLED) return
        Log.i("REWARDDEBUG", "AdMobProvider.initialize called")
        Log.i("ADLOADING", "AdMobProvider.initialize called")
        // GMA Next-Gen SDK requires initialize() to run off the main thread and to complete
        // before any ad is loaded — unlike the classic SDK, which queued loads during init.
        CoroutineScope(Dispatchers.IO).launch {
            val initConfig = InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build()
            MobileAds.initialize(context, initConfig) {
                Log.i("REWARDDEBUG", "AdMob Next-Gen SDK initialized")
                Log.i("ADLOADING", "AdMob Next-Gen SDK initialized")
                // Mirrors LevelPlayProvider.initialize(): preload fallback + rewarded-interstitial
                // inventory once the shared SDK's init completes, rather than the caller racing it.
                AdManager.onAdMobSdkReady()
            }
        }
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
        Log.d("REWARDDEBUG", "AdMob interstitial loading unit=$ADMOB_INTERSTITIAL_ID")
        Log.d("ADLOADING", "AdMob interstitial loading unit=$ADMOB_INTERSTITIAL_ID")
        InterstitialAd.load(
            AdRequest.Builder(ADMOB_INTERSTITIAL_ID).build(),
            object : AdLoadCallback<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d("REWARDDEBUG", "AdMob interstitial loaded unit=$ADMOB_INTERSTITIAL_ID")
                    Log.i("ADLOADING", "AdMob interstitial loaded unit=$ADMOB_INTERSTITIAL_ID")
                    admobInterstitial = ad
                    isLoadingInterstitial = false
                }
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.w("REWARDDEBUG", "AdMob interstitial failed to load unit=$ADMOB_INTERSTITIAL_ID err=${adError.message}")
                    Log.w("ADLOADING", "AdMob interstitial failed to load unit=$ADMOB_INTERSTITIAL_ID err=${adError.message}")
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
            Log.i("ADLOADING", "AdMob interstitial showing")
            ad.adEventCallback = object : InterstitialAdEventCallback {
                override fun onAdDismissedFullScreenContent() {
                    Log.d("REWARDDEBUG", "AdMob interstitial dismissed")
                    Log.d("ADLOADING", "AdMob interstitial dismissed")
                    admobInterstitial = null
                    onClosed()
                }
                override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                    Log.w("REWARDDEBUG", "AdMob interstitial failed to show err=${fullScreenContentError.message}")
                    Log.w("ADLOADING", "AdMob interstitial failed to show err=${fullScreenContentError.message}")
                    admobInterstitial = null
                    onClosed()
                }
            }
            ad.show(activity)
            return
        }
        // Not preloaded — attempt load+show inline
        Log.w("REWARDDEBUG", "AdMob interstitial not preloaded — loading inline")
        Log.w("ADLOADING", "AdMob interstitial not preloaded — loading inline")
        if (BuildConfig.GOOGLE_ADS_ENABLED && ADMOB_INTERSTITIAL_ID.isNotBlank()) {
            InterstitialAd.load(
                AdRequest.Builder(ADMOB_INTERSTITIAL_ID).build(),
                object : AdLoadCallback<InterstitialAd> {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        Log.d("REWARDDEBUG", "AdMob interstitial inline load succeeded")
                        Log.i("ADLOADING", "AdMob interstitial inline load succeeded")
                        admobInterstitial = ad
                        showInterstitial(activity, onClosed)
                    }
                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        Log.w("REWARDDEBUG", "AdMob interstitial inline load failed err=${adError.message}")
                        Log.w("ADLOADING", "AdMob interstitial inline load failed err=${adError.message}")
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
        val unitId = toAdMobRewardedUnitId(placement) ?: run {
            Log.d("REWARDDEBUG", "AdMob preloadRewarded skipped — no unit ID configured for placement=$placement")
            Log.d("ADLOADING", "AdMob preloadRewarded skipped — no unit ID configured for placement=$placement")
            return
        }
        if (admobRewarded.containsKey(placement)) return
        Log.d("ADLOADING", "AdMob preloadRewarded loading placement=$placement unit=$unitId")
        RewardedAd.load(
            AdRequest.Builder(unitId).build(),
            object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) {
                    Log.d("REWARDDEBUG", "AdMob rewarded loaded for placement=$placement unit=$unitId")
                    Log.i("ADLOADING", "AdMob rewarded loaded for placement=$placement unit=$unitId")
                    admobRewarded[placement] = ad
                }
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.w("REWARDDEBUG", "AdMob rewarded failed to load for placement=$placement unit=$unitId err=${adError.message}")
                    Log.w("ADLOADING", "AdMob rewarded failed to load for placement=$placement unit=$unitId err=${adError.message}")
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
        Log.i("ADLOADING", "AdMob.showRewarded showing placement=$placement")
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                Log.d("REWARDDEBUG", "AdMob rewarded dismissed placement=$placement")
                Log.d("ADLOADING", "AdMob rewarded dismissed placement=$placement")
                onClosed()
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                Log.w("REWARDDEBUG", "AdMob rewarded failed to show placement=$placement err=${fullScreenContentError.message}")
                Log.w("ADLOADING", "AdMob rewarded failed to show placement=$placement err=${fullScreenContentError.message}")
                onClosed()
            }
        }
        ad.show(activity) {
            Log.i("REWARDDEBUG", "AdMob rewarded granted placement=$placement")
            Log.i("ADLOADING", "AdMob rewarded granted placement=$placement")
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
        container.removeAllViews()
        container.addView(av)
        Log.d("REWARDDEBUG", "AdMob banner loading unit=$ADMOB_BANNER_ID")
        Log.d("ADLOADING", "AdMob banner loading unit=$ADMOB_BANNER_ID")
        val adRequest = BannerAdRequest.Builder(ADMOB_BANNER_ID, AdSize.BANNER).build()
        av.loadAd(
            adRequest,
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    Log.d("REWARDDEBUG", "AdMob banner loaded unit=$ADMOB_BANNER_ID")
                    Log.i("ADLOADING", "AdMob banner loaded unit=$ADMOB_BANNER_ID")
                }
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.w("REWARDDEBUG", "AdMob banner failed to load unit=$ADMOB_BANNER_ID err=${adError.message}")
                    Log.w("ADLOADING", "AdMob banner failed to load unit=$ADMOB_BANNER_ID err=${adError.message}")
                }
            }
        )
        adView = av
    }

    override fun hideBanner() {
        adView?.destroy()
        adView = null
    }
}
