package jmotley.com.jspades.ads

import android.app.Activity
import android.util.Log
import java.util.UUID
import android.content.Context
import jmotley.com.jspades.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public singleton ad facade. The only ads type referenced outside the ads package.
 *
 * Owns session-level provider selection and the every-N-hands interstitial counter
 * (persisted in app_prefs so the cadence survives app restarts).
 * All Unity SDK types, placement IDs, and mediation details stay inside the providers.
 */
object AdManager {

    private const val INTERSTITIAL_EVERY_N = 3
    private const val PREF_HAND_COUNTER = "interstitial_hand_counter"
    private const val SKIPS_PER_REWARD = 2
    private const val PREF_INTERSTITIAL_SKIPS = "interstitial_skips_remaining"

    enum class AdMode { LevelPlay, AdMobFallback }

    // ── Session state (observable by UI) ─────────────────────────────────────

    private val _sessionAdMode = MutableStateFlow(AdMode.LevelPlay)
    val sessionAdMode: StateFlow<AdMode> = _sessionAdMode

    private val _bannerVisible = MutableStateFlow(false)
    val bannerVisible: StateFlow<Boolean> = _bannerVisible

    // ── Internal state ────────────────────────────────────────────────────────

    // active is null until start() is called — do not initialize here so provider
    // constructors don't run before consent is obtained.
    private var active: AdProvider? = null
    private var appContext: Context? = null
    private val levelPlay by lazy { LevelPlayProvider() }
    private val admob by lazy { AdMobProvider() }
    private val sessionId: String = UUID.randomUUID().toString()

    // ── Startup ───────────────────────────────────────────────────────────────

    /**
     * Call once in MainActivity.onCreate(). Handles consent, then initializes providers
     * and preloads session inventory. Safe to call multiple times — no-ops after first.
     * [onReady] fires on the main thread once consent is resolved and providers are initialized.
     */
    fun start(context: Context, onReady: () -> Unit = {}) {
        if (active != null) { onReady(); return }
        appContext = context.applicationContext
        Log.i("REWARDDEBUG", "AdManager.start requesting consent session=$sessionId")
        ConsentManager.requestConsent(context as? Activity ?: return) {
            Log.i("REWARDDEBUG", "consent obtained — initializing providers session=$sessionId")
            levelPlay.initialize(context)
            admob.initialize(context)
            active = levelPlay
            _sessionAdMode.value = AdMode.LevelPlay
            Log.i("REWARDDEBUG", "active provider set to LevelPlay session=$sessionId")
            // Preload AdMob fallback inventory once here; LevelPlay preloads after its own init.
            preloadAdMobFallback()
            onReady()
        }
    }

    fun preloadForSession() {
        val provider = active ?: return
        provider.preloadInterstitial()
        RewardedPlacement.entries.forEach { provider.preloadRewarded(it) }
    }

    internal fun preloadAdMobFallback() {
        if (active !== admob) {
            admob.preloadInterstitial()
            RewardedPlacement.entries.forEach { admob.preloadRewarded(it) }
        }
    }

    fun preloadRewarded(placement: RewardedPlacement) {
        active?.preloadRewarded(placement)
        if (active !== admob) admob.preloadRewarded(placement)
    }

    fun preloadInterstitial() {
        active?.preloadInterstitial()
        if (active !== admob) admob.preloadInterstitial()
    }

    // ── Interstitial ──────────────────────────────────────────────────────────

    /**
     * Call at every hand/game end. Shows an interstitial every [INTERSTITIAL_EVERY_N] calls;
     * the counter persists across sessions in app_prefs. [onClosed] is always invoked exactly once.
     */
    fun maybeShowInterstitial(activity: Activity, onClosed: () -> Unit = {}) {
        val prefs = appContext?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val counter = prefs?.getInt(PREF_HAND_COUNTER, 0) ?: 0
        val nextCount = counter + 1
        val shouldShow = nextCount >= INTERSTITIAL_EVERY_N
        prefs?.edit()?.putInt(PREF_HAND_COUNTER, if (shouldShow) 0 else nextCount)?.apply()
        val provider = active
        if (!shouldShow) { onClosed(); return }
        val skips = prefs?.getInt(PREF_INTERSTITIAL_SKIPS, 0) ?: 0
        if (skips > 0) {
            prefs?.edit()?.putInt(PREF_INTERSTITIAL_SKIPS, skips - 1)?.apply()
            onClosed(); return
        }
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

    private fun grantInterstitialSkips() {
        val prefs = appContext?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) ?: return
        val current = prefs.getInt(PREF_INTERSTITIAL_SKIPS, 0)
        prefs.edit().putInt(PREF_INTERSTITIAL_SKIPS, current + SKIPS_PER_REWARD).apply()
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
        if (BuildConfig.REWARDED_TEST_MODE) {
            Log.i("REWARDDEBUG", "REWARDED_TEST_MODE: skipping ad, granting reward placement=$placement")
            onReward()
            onClosed()
            return
        }

        val provider = active ?: run {
            Log.w("REWARDDEBUG", "showRewarded no active provider — granting reward placement=$placement session=$sessionId")
            onReward(); onClosed(); return
        }

        Log.i("REWARDDEBUG", "showRewarded request placement=$placement provider=${provider.javaClass.simpleName} session=$sessionId")

        val canShow = provider.canShowRewarded(placement)
        Log.d("REWARDDEBUG", "canShowRewarded=$canShow placement=$placement provider=${provider.javaClass.simpleName} session=$sessionId")
        if (!canShow) {
            Log.w("REWARDDEBUG", "provider ${provider.javaClass.simpleName} cannot show placement=$placement; attempting AdMob fallback session=$sessionId")
            // Try AdMob directly if LevelPlay can't serve this placement
            if (provider !== admob && admob.canShowRewarded(placement)) {
                Log.i("REWARDDEBUG", "falling back to AdMob for placement=$placement session=$sessionId")
                admob.showRewarded(activity, placement, {
                    Log.i("REWARDDEBUG", "onReward (AdMob) placement=$placement session=$sessionId")
                    grantInterstitialSkips()
                    onReward()
                }, {
                    Log.i("REWARDDEBUG", "onClosed (AdMob) placement=$placement session=$sessionId")
                    admob.preloadRewarded(placement)
                    onClosed()
                })
            } else {
                Log.w("REWARDDEBUG", "no provider available — granting reward placement=$placement session=$sessionId")
                onReward(); onClosed()
            }
            return
        }

        provider.showRewarded(activity, placement, {
            Log.i("REWARDDEBUG", "onReward placement=$placement provider=${provider.javaClass.simpleName} session=$sessionId")
            grantInterstitialSkips()
            onReward()
        }, {
            Log.i("REWARDDEBUG", "onClosed placement=$placement provider=${provider.javaClass.simpleName} session=$sessionId")
            provider.preloadRewarded(placement)
            onClosed()
        })
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    fun showBanner() {
        _bannerVisible.value = true
    }

    fun hideBanner() {
        _bannerVisible.value = false
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onActivityResume(activity: Activity) { active?.onResume(activity) }
    fun onActivityPause(activity: Activity)  { active?.onPause(activity) }

    // ── Fallback ──────────────────────────────────────────────────────────────

    /**
     * Called by [LevelPlayProvider] when initialization fails hard.
     * Switches the session to direct AdMob for continuity.
     */
    internal fun failOverToAdMob(reason: String) {
        Log.w("REWARDDEBUG", "failOverToAdMob: $reason session=$sessionId")
        active = admob
        _sessionAdMode.value = AdMode.AdMobFallback
        preloadForSession()
    }

}
