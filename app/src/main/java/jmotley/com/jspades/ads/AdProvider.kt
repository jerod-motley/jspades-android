package jmotley.com.jspades.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup

/**
 * Internal contract implemented by each concrete ad provider.
 * Nothing outside the ads package references this interface directly.
 */
internal interface AdProvider {
    fun initialize(context: Context)
    fun preloadRewarded(placement: RewardedPlacement)
    fun preloadInterstitial()
    fun canShowRewarded(placement: RewardedPlacement): Boolean
    fun canShowInterstitial(): Boolean
    fun showRewarded(
        activity: Activity,
        placement: RewardedPlacement,
        onReward: () -> Unit,
        onClosed: () -> Unit,
    )
    fun showInterstitial(activity: Activity, onClosed: () -> Unit)
    fun showBanner(container: ViewGroup, onFailed: () -> Unit = {})
    fun hideBanner()
    fun onResume(activity: Activity) {}
    fun onPause(activity: Activity) {}
}
