package jmotley.com.jspades.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public singleton ad facade. The only ads type referenced outside the ads package.
 *
 * Owns session-level provider selection and the every-other-hand interstitial toggle.
 * All Unity SDK types, placement IDs, and mediation details stay inside the providers.
 */
object AdManager {

    enum class AdMode { LevelPlay, AdMobFallback }

    // ── Session state (observable by UI) ─────────────────────────────────────

    private val _sessionAdMode = MutableStateFlow(AdMode.LevelPlay)
    val sessionAdMode: StateFlow<AdMode> = _sessionAdMode

    private val _bannerVisible = MutableStateFlow(false)
    val bannerVisible: StateFlow<Boolean> = _bannerVisible

    // ── Internal state ────────────────────────────────────────────────────────

    // Read-and-flip boolean: false → skip, flip to true → true → show, flip to false → …
    // Starts at false so the first hand end shows no ad, the second does.
    private var showInterstitialNext: Boolean = false

    // active is null until start() is called — do not initialize here so provider
    // constructors don't run before consent is obtained.
    private var active: AdProvider? = null
    private val levelPlay by lazy { LevelPlayProvider() }
    private val admob by lazy { AdMobProvider() }

    // ── Startup ───────────────────────────────────────────────────────────────

    /**
     * Call once in MainActivity.onCreate(). Handles consent, then initializes providers
     * and preloads session inventory. Safe to call multiple times — no-ops after first.
     */
    fun start(context: Context) {
        if (active != null) return
        ConsentManager.requestConsent(context as? Activity ?: return) {
            levelPlay.initialize(context)
            admob.initialize(context)
            active = levelPlay
            _sessionAdMode.value = AdMode.LevelPlay
            preloadForSession()
        }
    }

    fun preloadForSession() {
        val provider = active ?: return
        provider.preloadInterstitial()
        RewardedPlacement.entries.forEach { provider.preloadRewarded(it) }
    }

    fun preloadRewarded(placement: RewardedPlacement) {
        active?.preloadRewarded(placement)
    }

    fun preloadInterstitial() {
        active?.preloadInterstitial()
    }

    // ── Interstitial ──────────────────────────────────────────────────────────

    /**
     * Call at every hand end. The every-other-hand cadence is managed internally —
     * callers never pass a flag. [onClosed] is always invoked exactly once.
     */
    fun maybeShowInterstitial(activity: Activity, onClosed: () -> Unit = {}) {
        val shouldShow = showInterstitialNext
        showInterstitialNext = !showInterstitialNext
        val provider = active
        if (!shouldShow) { onClosed(); return }
        if (provider?.canShowInterstitial() == true) {
            provider.showInterstitial(activity) {
                preloadInterstitial()
                onClosed()
            }
        } else if (provider !== admob && admob.canShowInterstitial()) {
            // LevelPlay waterfall returned no fill — try direct AdMob before giving up
            admob.showInterstitial(activity) {
                admob.preloadInterstitial()
                onClosed()
            }
        } else {
            onClosed()
        }
    }

    // ── Rewarded ──────────────────────────────────────────────────────────────

    /**
     * Show a rewarded ad. [onReward] is called only if the user completes the ad.
     * [onClosed] is always called when the ad is dismissed or fails.
     * On Unity show failure, retries once against AdMob before giving up.
     */
    fun showRewarded(
        activity: Activity,
        placement: RewardedPlacement,
        onReward: () -> Unit,
        onClosed: () -> Unit = {},
    ) {
        val provider = active ?: run { onClosed(); return }
        if (!provider.canShowRewarded(placement)) {
            // Try AdMob directly if Unity can't serve this placement
            if (provider !== admob && admob.canShowRewarded(placement)) {
                admob.showRewarded(activity, placement, onReward, onClosed)
            } else {
                onClosed()
            }
            return
        }
        provider.showRewarded(activity, placement, onReward, onClosed)
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    fun showBanner(container: ViewGroup) {
        active?.showBanner(container)
        _bannerVisible.value = true
    }

    fun hideBanner() {
        active?.hideBanner()
        _bannerVisible.value = false
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    /**
     * Called by [LevelPlayProvider] when initialization fails hard.
     * Switches the session to direct AdMob for continuity.
     */
    internal fun failOverToAdMob(reason: String) {
        android.util.Log.w("AdManager", "Falling over to AdMob: $reason")
        active = admob
        _sessionAdMode.value = AdMode.AdMobFallback
        preloadForSession()
    }

}
