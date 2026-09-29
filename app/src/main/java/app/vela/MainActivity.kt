package app.vela

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.vela.core.data.MapLinkParser
import app.vela.ui.AppLocale
import app.vela.ui.VelaRoot
import app.vela.ui.map.MapViewModel
import app.vela.ui.theme.VelaTheme
import app.vela.ui.theme.isAppInDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Same instance the Compose tree gets (both resolve to this activity's store),
    // so a deep link handled here shows up in the UI.
    private val vm: MapViewModel by viewModels()

    /** Apply the in-app language override to this Activity's resources (no-op when following the
     *  system locale) so `stringResource` resolves in the chosen language. */
    override fun attachBaseContext(newBase: Context) {
        // Adaptive small-screen density first (a no-op on normal screens), then the language override.
        super.attachBaseContext(AppLocale.wrap(app.vela.ui.AdaptiveDensity.wrap(newBase)))
    }

    /** The LIVE configuration, kept for the Compose tree. The manifest declares `configChanges`
     *  for orientation/screenSize, so Android hands the change to this Activity and never notifies
     *  the application-level callbacks Compose's own `LocalConfiguration` listens to - so rotating
     *  the phone left every `LocalConfiguration.current` read stuck on the PREVIOUS orientation
     *  (the landscape side panels stayed portrait-shaped until the app was restarted, user
     *  2026-09-17). Providing this state below keeps every screen-size read honest. */
    private val liveConfig = androidx.compose.runtime.mutableStateOf<android.content.res.Configuration?>(null)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        liveConfig.value = android.content.res.Configuration(newConfig)
    }

    override fun onResume() {
        super.onResume()
        // The 12/24-hour clock setting can change while Vela sits in the background (issue #357).
        app.vela.ui.Clock24.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A language change re-creates this Activity so the whole UI re-reads localized resources.
        AppLocale.onLocaleChanged = { recreate() }
        // Picture-in-picture mini map while navigating (user 2026-07-24, the Google Maps
        // behavior): on Android 12+ the system auto-enters PiP on Home/gesture-up whenever the
        // params say so, so keep autoEnter in lockstep with the nav state; pre-12 the
        // onUserLeaveHint below calls enterPictureInPictureMode by hand. Everything is
        // best-effort (runCatching): a launcher or ROM that forbids PiP just falls back to the
        // notification-only background nav that already works.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                var last: Boolean? = null
                vm.state.collect { s ->
                    if (s.navigating != last) {
                        last = s.navigating
                        runCatching { setPictureInPictureParams(pipParams(s.navigating)) }
                            .onFailure { android.util.Log.w("VelaPip", "setPictureInPictureParams failed", it) }
                    }
                }
            }
        }
        handleIntent(intent)
        setContent {
            // Read the theme at the call site (a recomposing scope) and pass it in
            // — reading it inside VelaTheme's default arg didn't reliably invalidate
            // VelaTheme, so MaterialTheme never flipped when the user changed it.
            val dark = isAppInDarkTheme()
            // The system status/nav bar ICONS (clock, wifi, battery) must contrast with the
            // MAP under them, which follows Vela's own theme — not the system's. In light mode
            // the map is white, so the icons must go DARK; edge-to-edge alone left them light
            // (white-on-white, unreadable). Flip the appearance whenever the app theme changes.
            androidx.compose.runtime.LaunchedEffect(dark) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalConfiguration provides
                    (liveConfig.value ?: androidx.compose.ui.platform.LocalConfiguration.current),
            ) {
                VelaTheme(darkTheme = dark) {
                    VelaRoot(vm = vm)
                }
            }
        }
    }

    /** A portrait-ish mini map, Google's PiP proportions. */
    private fun pipParams(autoEnter: Boolean): android.app.PictureInPictureParams {
        val b = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(3, 4))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(autoEnter)
            b.setSeamlessResizeEnabled(false) // map surfaces cross-fade better than they stretch
        }
        // Android 13+: the PiP menu's expand toggle (and a double-tap on some launchers) grows
        // the window to a taller shape, which for a map means more road ahead; without it the
        // window has one size and the only other option is the full app (user 2026-09-13).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            b.setExpandedAspectRatio(android.util.Rational(9, 16))
        }
        return b.build()
    }

    @Deprecated("Deprecated in Java")
    override fun onUserLeaveHint() {
        @Suppress("DEPRECATION")
        super.onUserLeaveHint()
        // Pre-Android-12 manual PiP entry (12+ auto-enters via the params above).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S &&
            vm.state.value.navigating
        ) {
            runCatching { enterPictureInPictureMode(pipParams(true)) }
                .onFailure { android.util.Log.w("VelaPip", "enterPictureInPictureMode failed", it) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        app.vela.ui.PipMode.active.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.vela.ui.AppVisibility.foreground.value = true
        // Low-power lock overlay (Settings > Navigation): push the live turn snapshot
        // while navigating so screen-off over the lock screen shows the black overlay.
        lifecycleScope.launch {
            vm.state.collect { s ->
                if (s.navigating && s.activeRoute != null) {
                    app.vela.ui.LowPowerWatcher.attach(this@MainActivity)
                    val route = s.activeRoute!!
                    val m = route.maneuvers.getOrNull(s.nav.stepIndex)
                    val road = m?.ref?.takeIf { it.isNotBlank() } ?: m?.road?.takeIf { it.isNotBlank() }
                    // The road you are ON: the leg entered by the last-passed maneuver (never the
                    // upcoming one — same source as barRoadName / the floating pill). Renames
                    // already passed on that leg apply, so the overlay agrees with the bar.
                    val onRoad = route.maneuvers.getOrNull(s.nav.stepIndex - 1)?.let { prev ->
                        val (name, ref) = prev.roadAt(prev.distanceMeters - s.nav.distanceToNextManeuver)
                        ref?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
                    }
                    // The overlay's live map needs the drive itself: the route polyline
                    // (decimated to the intent cap), progress, the puck fix, and the full
                    // banner/ETA fields — Google's MinMode. The next maneuver + the driven
                    // road's ref ride along so the banner's "then" row and shield match.
                    val next = route.maneuvers.getOrNull(s.nav.stepIndex + 1)
                    val drivenRef = route.maneuvers.getOrNull(s.nav.stepIndex - 1)?.let { prev ->
                        val (_, ref) = prev.roadAt(prev.distanceMeters - s.nav.distanceToNextManeuver)
                        ref?.takeIf { it.isNotBlank() }
                    }
                    app.vela.ui.LowPowerWatcher.push(
                        app.vela.ui.LowPowerSnapshot(
                            distanceText = app.vela.ui.formatDistance(s.nav.distanceToNextManeuver),
                            instruction = s.maneuverText,
                            road = road,
                            etaText = remainingText(s.nav.remainingDuration) + " · " +
                                app.vela.ui.formatDistance(s.nav.remainingDistance),
                            locationText = onRoad?.let { "on $it" }.orEmpty(),
                            maneuverOrdinal = m?.type?.ordinal ?: -1,
                            routeLat = decimatedLat(route),
                            routeLng = decimatedLng(route),
                            traveledM = s.nav.traveledM,
                            myLat = s.myLocation?.lat ?: Double.NaN,
                            myLng = s.myLocation?.lng ?: Double.NaN,
                            bearing = s.myBearing,
                            stepIndex = s.nav.stepIndex,
                            offRoute = s.nav.offRoute,
                            maneuverRef = m?.ref,
                            maneuverRoad = m?.road,
                            laneHint = m?.laneHint,
                            nextInstruction = next?.instruction,
                            nextManeuverOrdinal = next?.type?.ordinal ?: -1,
                            nextDistanceM = m?.distanceMeters,
                            currentRef = drivenRef,
                            remainingDistanceM = s.nav.remainingDistance,
                            remainingSeconds = s.nav.remainingDuration,
                            trafficRatio = route.trafficRatio,
                            destName = s.arrivedLabel,
                            destAddress = s.navDestAddress,
                            distToNextM = s.nav.distanceToNextManeuver,
                            basemapArchive = s.basemapArchive,
                        ),
                    )
                    // The overlay is already up (screen off): stream each state tick to it
                    // so the map, banner and ETA stay live without waking the main screen.
                    app.vela.ui.LowPowerWatcher.latestSnapshot()?.let {
                        app.vela.ui.LowPowerActivity.broadcastUpdate(this@MainActivity, it)
                    }
                } else {
                    app.vela.ui.LowPowerWatcher.clear()
                    app.vela.ui.LowPowerWatcher.detach(this@MainActivity)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        app.vela.ui.AppVisibility.foreground.value = false
    }

    /** Remaining-time + arrival clock for the lock overlay, Google-style ("12 min · 7:42 PM"). */
    private fun remainingText(remainingSeconds: Double): String {
        val mins = (remainingSeconds / 60.0).toLong().coerceAtLeast(0)
        val clock = app.vela.ui.formatArrivalClock(remainingSeconds)
        return "$mins min · $clock"
    }

    /** Evenly decimate a route polyline to the overlay intent cap (see
     *  LowPowerSnapshot.MAX_ROUTE_POINTS): every Nth point plus the endpoint, so
     *  a long route still spans its full length, just coarser off-screen. */
    private fun decimatedLat(route: app.vela.core.model.Route): DoubleArray {
        val pts = route.polyline
        val cap = app.vela.ui.LowPowerSnapshot.MAX_ROUTE_POINTS
        if (pts.size <= cap) return pts.map { it.lat }.toDoubleArray()
        val step = (pts.size - 1).toDouble() / (cap - 1)
        return DoubleArray(cap) { i ->
            if (i == cap - 1) pts.last().lat else pts[(i * step).toInt()].lat
        }
    }

    private fun decimatedLng(route: app.vela.core.model.Route): DoubleArray {
        val pts = route.polyline
        val cap = app.vela.ui.LowPowerSnapshot.MAX_ROUTE_POINTS
        if (pts.size <= cap) return pts.map { it.lng }.toDoubleArray()
        val step = (pts.size - 1).toDouble() / (cap - 1)
        return DoubleArray(cap) { i ->
            if (i == cap - 1) pts.last().lng else pts[(i * step).toInt()].lng
        }
    }

    /** Vela registers for `geo:` URIs and Google-Maps web links so it can be the
     *  system maps handler; turn whichever we got into a search or a dropped pin. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data?.toString() ?: return
                MapLinkParser.parse(data)?.let { vm.openDeepLink(it) }
            }
            // Share TO Vela: a Google Maps share link imports without the copy-paste dance, a
            // geo:/maps URL opens like a deep link, plain text (an address someone texted you)
            // just searches.
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                vm.openSharedText(text)
            }
        }
    }
}
