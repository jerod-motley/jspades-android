package jmotley.com.jspades.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import com.facebook.ads.Ad
import com.facebook.ads.AdError as FbAdError
import com.facebook.ads.InterstitialAdListener
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
import jmotley.com.jspades.R

/**
 * AdMob-backed provider. Used as the session-level fallback when Unity is
 * unavailable or has tripped the circuit breaker.
 *
 * Rewarded is supported for UNDO_LAST_TRICK and REPLAY_LAST_HAND only — the two
 * placements most likely to have AdMob rewarded unit IDs configured. All other
 * rewarded placements return false from canShowRewarded().
 *
 * FAN interstitial fallback is preserved from the original InterstitialManager.
 */
internal class AdMobProvider : AdProvider {

    // ── Unit IDs ──────────────────────────────────────────────────────────────

    private val ADMOB_INTERSTITIAL_ID = "ca-app-pub-9978563261260279/2189775212"
    // TODO: replace with real rewarded unit IDs once created in AdMob console
    private val ADMOB_REWARDED_UNDO_TRICK    = ""
    private val ADMOB_REWARDED_REPLAY_HAND   = ""

    private fun toAdMobRewardedUnitId(placement: RewardedPlacement): String? = when (placement) {
        RewardedPlacement.UNDO_LAST_TRICK  -> ADMOB_REWARDED_UNDO_TRICK.ifBlank { null }
        RewardedPlacement.REPLAY_LAST_HAND -> ADMOB_REWARDED_REPLAY_HAND.ifBlank { null }
        else                               -> null
    }

    // ── Cached ad state ───────────────────────────────────────────────────────

    private var appContext: Context? = null

    private var admobInterstitial: InterstitialAd? = null
    private var fbInterstitial: com.facebook.ads.InterstitialAd? = null
    private var isLoadingInterstitial = false
    private var pendingFbOnClosed: (() -> Unit)? = null

    private val admobRewarded = mutableMapOf<RewardedPlacement, RewardedAd>()

    // ── AdProvider ────────────────────────────────────────────────────────────

    override fun initialize(context: Context) {
        if (!BuildConfig.GOOGLE_ADS_ENABLED) return
        appContext = context.applicationContext
        MobileAds.initialize(context)
    }

    // ── Interstitial ──────────────────────────────────────────────────────────

    override fun preloadInterstitial() {
        if (!BuildConfig.GOOGLE_ADS_ENABLED) return
        if (isLoadingInterstitial || admobInterstitial != null || fbInterstitial != null) return
        isLoadingInterstitial = true
        loadAdMobInterstitial()
    }

    private fun loadAdMobInterstitial() {
        // Context needed for load — deferred until show; use application context via stored ref.
        // AdMob load requires a context. We'll load lazily on the first showInterstitial call
        // if not already loaded. This flag just signals intent.
        isLoadingInterstitial = false
    }

    override fun canShowInterstitial(): Boolean =
        admobInterstitial != null || (fbInterstitial?.isAdLoaded == true)

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit) {
        val admob = admobInterstitial
        if (admob != null) {
            admob.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    admobInterstitial = null
                    onClosed()
                }
                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    admobInterstitial = null
                    showFbInterstitial(activity, onClosed)
                }
            }
            admob.show(activity)
            return
        }
        // AdMob not loaded — try load+show via AdMob, fall through to FAN
        if (BuildConfig.GOOGLE_ADS_ENABLED) {
            InterstitialAd.load(
                activity,
                ADMOB_INTERSTITIAL_ID,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        admobInterstitial = ad
                        showInterstitial(activity, onClosed)
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        showFbInterstitial(activity, onClosed)
                    }
                }
            )
        } else {
            showFbInterstitial(activity, onClosed)
        }
    }

    private fun showFbInterstitial(activity: Activity, onClosed: () -> Unit) {
        if (!BuildConfig.FACEBOOK_ADS_ENABLED) { onClosed(); return }
        val fb = fbInterstitial
        if (fb != null && fb.isAdLoaded) {
            pendingFbOnClosed = onClosed
            fb.show()
        } else {
            // Try loading then showing
            val placementId = try { activity.getString(R.string.fb_interstitial_placement) } catch (e: Exception) { ""; }
            if (placementId.isBlank()) { onClosed(); return }
            val ad = com.facebook.ads.InterstitialAd(activity, placementId)
            ad.loadAd(
                ad.buildLoadAdConfig()
                    .withAdListener(object : InterstitialAdListener {
                        override fun onInterstitialDisplayed(ad: Ad?) {}
                        override fun onInterstitialDismissed(ad: Ad?) {
                            fbInterstitial = null
                            pendingFbOnClosed?.invoke()
                            pendingFbOnClosed = null
                        }
                        override fun onError(ad: Ad?, error: FbAdError?) {
                            fbInterstitial = null
                            onClosed()
                        }
                        override fun onAdLoaded(ad: Ad?) {
                            fbInterstitial?.show()
                        }
                        override fun onAdClicked(ad: Ad?) {}
                        override fun onLoggingImpression(ad: Ad?) {}
                    })
                    .build()
            )
            pendingFbOnClosed = onClosed
            fbInterstitial = ad
        }
    }

    // ── Rewarded ──────────────────────────────────────────────────────────────

    override fun preloadRewarded(placement: RewardedPlacement) {
        val ctx = appContext ?: return
        val unitId = toAdMobRewardedUnitId(placement) ?: return
        if (admobRewarded.containsKey(placement)) return
        RewardedAd.load(
            ctx,
            unitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { admobRewarded[placement] = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { admobRewarded.remove(placement) }
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
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { onClosed() }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { onClosed() }
        }
        ad.show(activity) { onReward() }
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    private var adView: AdView? = null

    override fun showBanner(container: ViewGroup) {
        if (!BuildConfig.GOOGLE_ADS_ENABLED) return
        adView?.destroy()
        val av = AdView(container.context)
        av.setAdSize(AdSize.BANNER)
        av.adUnitId = "ca-app-pub-9978563261260279/BANNER_UNIT_ID" // TODO: replace with real unit ID
        container.removeAllViews()
        container.addView(av)
        av.loadAd(AdRequest.Builder().build())
        adView = av
    }

    override fun hideBanner() {
        adView?.destroy()
        adView = null
    }
}
