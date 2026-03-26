package jmotley.com.jspades

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

import jmotley.com.jspades.ads.AdManager
import jmotley.com.jspades.data.AchievementsRepo
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.screens.ChallengesScreen
import jmotley.com.jspades.screens.MainMenuScreen
import jmotley.com.jspades.screens.MessagesScreen
import jmotley.com.jspades.screens.PlayScreen
import jmotley.com.jspades.screens.ProfileScreen
import jmotley.com.jspades.screens.RenegeJokesScreen
import jmotley.com.jspades.screens.SettingsScreen
import jmotley.com.jspades.screens.StandingsScreen
import jmotley.com.jspades.screens.SuggestionsScreen
import jmotley.com.jspades.ui.theme.JSpadesTheme
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var adsReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AdManager.start(this)
        adsReady = true
        enableEdgeToEdge()
        AchievementsRepo.init(this)
        setContent {
            JSpadesTheme {
                val navController = rememberNavController()
                Scaffold(
                    containerColor = Color.Black,
                    contentWindowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    ),
                    bottomBar = { if (adsReady) AdBannerView() }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "menu",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("menu") {
                            MainMenuScreen(
                                onNavigateToPlay = { gameType ->
                                    val encoded = URLEncoder.encode(gameType, "utf-8")
                                    navController.navigate("play/$encoded")
                                },
                                onNavigateToProfile = { navController.navigate("profile") },
                                onNavigateToChallenges = { gameType ->
                                    val encoded = URLEncoder.encode(gameType, "utf-8")
                                    navController.navigate("challenges/$encoded")
                                },
                                onNavigateToMessages     = { navController.navigate("messages") },
                                onNavigateToSuggestions  = { navController.navigate("suggestions") },
                                onNavigateToRenegeJokes  = { navController.navigate("renegejokes") },
                                onNavigateToStandings    = { navController.navigate("standings") },
                                onNavigateToSettings     = { navController.navigate("settings") }
                            )
                        }
                        composable("play/{gameType}") { backStackEntry ->
                            val raw = backStackEntry.arguments
                                ?.getString("gameType") ?: "Classic"
                            val decoded = try { URLDecoder.decode(raw, "utf-8") } catch (_: Exception) { raw }
                            PlayScreen(
                                localPlayerId = "south",
                                gameType = decoded,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("challenges/{gameType}") { backStackEntry ->
                            val raw = backStackEntry.arguments
                                ?.getString("gameType") ?: "Classic"
                            val decoded = try { URLDecoder.decode(raw, "utf-8") } catch (_: Exception) { raw }
                            ChallengesScreen(
                                gameTypeLabel = decoded,
                                onStartChallenge = { _, gameType ->
                                    val encoded = URLEncoder.encode(gameType, "utf-8")
                                    navController.navigate("play/$encoded")
                                },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("profile") {
                            ProfileScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("messages") {
                            MessagesScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("suggestions") {
                            SuggestionsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("renegejokes") {
                            RenegeJokesScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("standings") {
                            StandingsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

// ── Ad composables ────────────────────────────────────────────────────────────

@Composable
private fun AdBannerView() {
    Column(modifier = Modifier.navigationBarsPadding()) {
        if (BuildConfig.GOOGLE_ADS_ENABLED) {
            var showFacebook by remember { mutableStateOf(false) }
            if (!showFacebook) {
                AndroidView(factory = { ctx ->
                    com.google.android.gms.ads.AdView(ctx).apply {
                        val dm = ctx.resources.displayMetrics
                        val widthDp = (dm.widthPixels / dm.density).toInt()
                        setAdSize(com.google.android.gms.ads.AdSize
                            .getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                        adUnitId = "ca-app-pub-9978563261260279/1783777324" //"ca-app-pub-3940256099942544/6300978111" // Google test ID
                        adListener = object : com.google.android.gms.ads.AdListener() {
                            override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                                showFacebook = true
                            }
                        }
                        loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
                    }
                })
            } else if (BuildConfig.FACEBOOK_ADS_ENABLED) {
                FbBannerView()
            }
        } else if (BuildConfig.FACEBOOK_ADS_ENABLED) {
            FbBannerView()
        }
    }
}

@Composable
private fun FbBannerView() {
    AndroidView(factory = { ctx ->
        val placementId = ctx.getString(R.string.fb_banner_placement)
        com.facebook.ads.AdView(ctx, placementId, com.facebook.ads.AdSize.BANNER_HEIGHT_50).apply {
            loadAd()
        }
    })
}

// ── POC animation demo (unused in production flow) ────────────────────────────

@Composable
fun GameScreen() {
    // card visual size
    val cardWidthDp: Dp = 80.dp
    val cardHeightDp: Dp = 120.dp

    // create four random cards (north, east, south, west)
    val cards = remember {
        listOf(
            Card(Suit.SPADES, Rank.TWO),
            Card(Suit.HEARTS, Rank.THREE),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.DIAMONDS, Rank.FIVE)
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        // compute target positions for the player diamond
        val cardW = with(density) { cardWidthDp.toPx() }
        val cardH = with(density) { cardHeightDp.toPx() }
        val safeTop = 24.dp
        val safeTopPx = with(density) { safeTop.toPx() }

        // north: centered horizontally, top respecting safe area
        val northX = (containerWidthPx - cardW) / 2f
        val northY = safeTopPx

        // south: two card heights below north
        val southX = northX
        val southY = northY + cardH * 2f

        // east/west vertically between north and south
        val middleY = (northY + southY) / 2f
        // east is one card width left of north center point
        val eastX = northX - cardW
        val westX = northX + cardW

        // starting positions (off-screen)
        val offTopY = -cardH - 100f
        val offBottomY = containerHeightPx + 100f
        val offLeftX = -cardW - 100f
        val offRightX = containerWidthPx + 100f

        // create animatables for each card (separate X/Y floats to avoid Offset converter issues)
        val animNX = remember { Animatable(northX) }
        val animNY = remember { Animatable(offTopY) }

        val animEX = remember { Animatable(offLeftX) }
        val animEY = remember { Animatable(middleY) }

        val animSX = remember { Animatable(southX) }
        val animSY = remember { Animatable(offBottomY) }

        val animWX = remember { Animatable(offRightX) }
        val animWY = remember { Animatable(middleY) }

        // Event-driven sequential animation: fire-and-forget cleanup between 3 rounds.
        // Each card slides in one at a time, clockwise from a random starting seat.
        // Seats: 0=north, 1=east, 2=south, 3=west
        LaunchedEffect(Unit) {
            val slideDuration = 600
            val pauseMs = 250L
            val cleanupDuration = 400

            // helper: animate one card into its diamond position and await completion
            suspend fun playCard(seat: Int) {
                when (seat) {
                    0 -> { // north: slide down from above
                        launch { animNX.animateTo(northX, animationSpec = tween(slideDuration)) }
                        animNY.animateTo(northY, animationSpec = tween(slideDuration))
                    }
                    1 -> { // east: slide right from left
                        launch { animEY.animateTo(middleY, animationSpec = tween(slideDuration)) }
                        animEX.animateTo(eastX, animationSpec = tween(slideDuration))
                    }
                    2 -> { // south: slide up from below
                        launch { animSX.animateTo(southX, animationSpec = tween(slideDuration)) }
                        animSY.animateTo(southY, animationSpec = tween(slideDuration))
                    }
                    3 -> { // west: slide left from right
                        launch { animWY.animateTo(middleY, animationSpec = tween(slideDuration)) }
                        animWX.animateTo(westX, animationSpec = tween(slideDuration))
                    }
                }
            }

            // helper: fire-and-forget cleanup — fly all cards off-screen then reset positions
            fun cleanup() {
                launch {
                    launch { animNY.animateTo(offTopY, animationSpec = tween(cleanupDuration)) }
                    launch { animEX.animateTo(offLeftX, animationSpec = tween(cleanupDuration)) }
                    launch { animSY.animateTo(offBottomY, animationSpec = tween(cleanupDuration)) }
                    animWX.animateTo(offRightX, animationSpec = tween(cleanupDuration))
                    // brief pause to let them exit before snapping back off-screen for next round
                    delay(100L)
                    animNX.snapTo(northX);  animNY.snapTo(offTopY)
                    animEX.snapTo(offLeftX); animEY.snapTo(middleY)
                    animSX.snapTo(southX);  animSY.snapTo(offBottomY)
                    animWX.snapTo(offRightX); animWY.snapTo(middleY)
                }
            }

            repeat(3) {
                // pick a random starting seat, then fill clockwise
                val startSeat = Random.nextInt(4)
                val sequence = List(4) { i -> (startSeat + i) % 4 }

                for (seat in sequence) {
                    playCard(seat)                 // animate card, suspends until done
                    delay(pauseMs)                 // background sleep before next card
                }

                cleanup()                          // fire-and-forget: cards exit screen concurrently
                delay(cleanupDuration + 200L)      // wait for cleanup to finish before next round
            }
        }

        // render cards at animated positions (compose Offset from X/Y animatables)
        CardView(cards[0], cardWidthDp, cardHeightDp, Offset(animNX.value, animNY.value))
        CardView(cards[1], cardWidthDp, cardHeightDp, Offset(animEX.value, animEY.value))
        CardView(cards[2], cardWidthDp, cardHeightDp, Offset(animSX.value, animSY.value))
        CardView(cards[3], cardWidthDp, cardHeightDp, Offset(animWX.value, animWY.value))
    }
}

@Composable
fun CardView(card: Card, w: Dp, h: Dp, pos: Offset) {
    val density = LocalDensity.current
    val xDp = with(density) { pos.x.toDp() }
    val yDp = with(density) { pos.y.toDp() }
    val context = LocalContext.current

    // resolve drawable resource by filename (without extension)
    val resName = card.assetFileName().substringBeforeLast('.')
    val resId = remember(resName) { context.resources.getIdentifier(resName, "drawable", context.packageName) }

    Box(modifier = Modifier
        .zIndex(1f)
        .offset(x = xDp, y = yDp)
    ) {
        if (resId != 0) {
            Image(
                painter = painterResource(id = resId),
                contentDescription = null,
                modifier = Modifier.size(w, h)
            )
        } else {
            // fallback: white box with filename if resource not found
            Box(modifier = Modifier.size(w, h).background(Color.White)) {
                Text(
                    text = card.assetFileName(),
                    modifier = Modifier.align(Alignment.Center),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
