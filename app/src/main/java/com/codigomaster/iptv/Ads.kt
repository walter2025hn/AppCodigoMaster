package com.codigomaster.iptv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

internal object AdsConsentManager {
    private var requestStarted = false

    fun gatherConsent(activity: Activity) {
        if (requestStarted || !hasConfiguredAds()) return
        requestStarted = true
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (info.canRequestAds()) AdsManager.initialize(activity.applicationContext)
                }
            },
            {
                if (info.canRequestAds()) AdsManager.initialize(activity.applicationContext)
            }
        )
    }

    private fun hasConfiguredAds(): Boolean =
        BuildConfig.ADMOB_BANNER_ID.isNotBlank() ||
        BuildConfig.ADMOB_NATIVE_ID.isNotBlank() ||
        BuildConfig.ADMOB_INTERSTITIAL_ID.isNotBlank()
}

internal object AdsManager {
    @Volatile private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var lastInterstitialShownAt = 0L

    fun initialize(context: Context) {
        if (initialized || !hasConfiguredAds()) return
        MobileAds.initialize(context) {
            initialized = true
            preloadInterstitial(context.applicationContext)
        }
    }

    private fun hasConfiguredAds(): Boolean =
        BuildConfig.ADMOB_BANNER_ID.isNotBlank() ||
        BuildConfig.ADMOB_NATIVE_ID.isNotBlank() ||
        BuildConfig.ADMOB_INTERSTITIAL_ID.isNotBlank()

    private fun preloadInterstitial(context: Context) {
        val unitId = BuildConfig.ADMOB_INTERSTITIAL_ID
        if (!initialized || unitId.isBlank() || interstitial != null) return
        InterstitialAd.load(context, unitId, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null }
            })
    }

    fun showInterstitial(activity: Activity): Boolean {
        if (!initialized || BuildConfig.ADMOB_INTERSTITIAL_ID.isBlank()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastInterstitialShownAt < 180_000L) return false
        val ad = interstitial ?: run {
            preloadInterstitial(activity.applicationContext)
            return false
        }
        interstitial = null
        lastInterstitialShownAt = now
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { preloadInterstitial(activity.applicationContext) }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                preloadInterstitial(activity.applicationContext)
            }
        }
        ad.show(activity)
        return true
    }
}

@Composable
internal fun BannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val unitId = BuildConfig.ADMOB_BANNER_ID
    if (unitId.isBlank()) return
    val adView = remember(context, unitId) {
        AdView(context).apply {
            adUnitId = unitId
            val density = resources.displayMetrics.density
            val widthDp = (resources.displayMetrics.widthPixels / density).toInt().coerceAtLeast(320)
            setAdSize(AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp))
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(adView) { onDispose { adView.destroy() } }
    AndroidView(factory = { adView }, modifier = modifier.fillMaxWidth().wrapContentHeight())
}

@Composable
internal fun NativeAdCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val unitId = BuildConfig.ADMOB_NATIVE_ID
    if (unitId.isBlank()) return
    var loadedAd by remember { mutableStateOf<NativeAd?>(null) }
    DisposableEffect(loadedAd) { onDispose { loadedAd?.destroy() } }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        androidx.compose.material3.Text(
            "PUBLICIDAD",
            color = androidx.compose.ui.graphics.Color(0xFFB8B8B8),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
        )
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    val adView = NativeAdView(ctx)
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(12, 10, 12, 10)
                        setBackgroundColor(Color.rgb(27, 31, 43))
                    }
                    val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    val textColumn = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(12, 0, 8, 0)
                    }
                    val headline = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f; maxLines = 1 }
                    val body = TextView(ctx).apply { setTextColor(Color.LTGRAY); textSize = 12f; maxLines = 2 }
                    val cta = Button(ctx).apply { isAllCaps = false }
                    textColumn.addView(headline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    textColumn.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    row.addView(icon, LinearLayout.LayoutParams(52, 52))
                    row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(cta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    adView.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    adView.headlineView = headline
                    adView.bodyView = body
                    adView.iconView = icon
                    adView.callToActionView = cta
                    val loader = AdLoader.Builder(ctx, unitId).forNativeAd { ad ->
                        loadedAd?.destroy()
                        loadedAd = ad
                        headline.text = ad.headline
                        body.text = ad.body.orEmpty()
                        body.visibility = if (ad.body.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                        cta.text = ad.callToAction.orEmpty()
                        cta.visibility = if (ad.callToAction.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                        val drawable = ad.icon?.drawable
                        icon.setImageDrawable(drawable)
                        icon.visibility = if (drawable == null) android.view.View.GONE else android.view.View.VISIBLE
                        adView.setNativeAd(ad)
                        removeAllViews()
                        addView(adView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }.withAdListener(object : AdListener() {
                        override fun onAdFailedToLoad(error: LoadAdError) { /* no fill: keep card empty */ }
                    }).build()
                    loader.loadAd(AdRequest.Builder().build())
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 1.dp)
        )
    }
}
