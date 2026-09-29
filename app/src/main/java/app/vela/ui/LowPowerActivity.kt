package app.vela.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.unit.sp
import app.vela.core.data.tiles.MapStyle
import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.ui.formatArrivalClock
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import app.vela.ui.map.MapFonts
import app.vela.ui.map.VelaMapView
import app.vela.ui.nav.ManeuverBanner
import app.vela.ui.nav.maneuverIcon
import app.vela.ui.theme.VelaTheme

/**
 * Low-power navigation screen: Google's MinMode, built into Vela so it needs no
 * root, Shizuku, or notification listener. A LIVE black map (the route line with
 * traversed-gray progress + the location puck) under the real turn banner and
 * the ETA bar — the same drive as the main screen, on the AMOLED palette where
 * every pixel of land is off. The overlay hosts its own location feed and speaks
 * its own prompts, so guidance continues with the main screen asleep.
 *
 * Data arrives as intent extras (a snapshot taken when the screen turned off), so
 * the overlay needs no Hilt graph and no shared session handle. Any tap returns
 * to the map. The overlay finishes itself when navigation ends (see the receiver
 * below) or when the phone unlocks.
 */
class LowPowerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lock-screen surface, not a wake-up call: show over the keyguard when the
        // user wakes the phone themselves, but never force the display on. The old
        // turnScreenOn flag lit the screen at full power on every screen-off while
        // navigating — the opposite of a low-power mode on a dash mount. The dimmed
        // black OLED surface below is what sips battery, not a wake lock.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        // Deliberately NO keep-awake and NO turn-screen-on (see above).
        // Dim the panel like an AOD surface: the overlay is meant to be glanced at on a
        // dark dash mount, not to light the cabin. 0.25 keeps the white turn readable
        // while the OLED pixels sip power.
        window.attributes = window.attributes.apply { screenBrightness = 0.25f }
        val snap = LowPowerSnapshot(
            distanceText = intent.getStringExtra(EXTRA_DISTANCE).orEmpty(),
            instruction = intent.getStringExtra(EXTRA_INSTRUCTION).orEmpty(),
            road = intent.getStringExtra(EXTRA_ROAD),
            etaText = intent.getStringExtra(EXTRA_ETA).orEmpty(),
            locationText = intent.getStringExtra(EXTRA_LOCATION).orEmpty(),
            maneuverOrdinal = intent.getIntExtra(EXTRA_MANEUVER, -1),
            routeLat = intent.getDoubleArrayExtra(EXTRA_ROUTE_LAT) ?: doubleArrayOf(),
            routeLng = intent.getDoubleArrayExtra(EXTRA_ROUTE_LNG) ?: doubleArrayOf(),
            traveledM = intent.getDoubleExtra(EXTRA_TRAVELED_M, 0.0),
            myLat = intent.getDoubleExtra(EXTRA_MY_LAT, Double.NaN),
            myLng = intent.getDoubleExtra(EXTRA_MY_LNG, Double.NaN),
            bearing = if (intent.hasExtra(EXTRA_BEARING)) intent.getFloatExtra(EXTRA_BEARING, 0f) else null,
            stepIndex = intent.getIntExtra(EXTRA_STEP, 0),
            offRoute = intent.getBooleanExtra(EXTRA_OFF_ROUTE, false),
            maneuverRef = intent.getStringExtra(EXTRA_M_REF),
            maneuverRoad = intent.getStringExtra(EXTRA_M_ROAD),
            laneHint = intent.getStringExtra(EXTRA_LANE_HINT),
            nextInstruction = intent.getStringExtra(EXTRA_NEXT),
            nextManeuverOrdinal = intent.getIntExtra(EXTRA_NEXT_MANEUVER, -1),
            nextDistanceM = if (intent.hasExtra(EXTRA_NEXT_DIST)) intent.getDoubleExtra(EXTRA_NEXT_DIST, 0.0) else null,
            currentRef = intent.getStringExtra(EXTRA_CURRENT_REF),
            remainingDistanceM = intent.getDoubleExtra(EXTRA_REM_DIST, 0.0),
            remainingSeconds = intent.getDoubleExtra(EXTRA_REM_SEC, 0.0),
            trafficRatio = if (intent.hasExtra(EXTRA_TRAFFIC)) intent.getDoubleExtra(EXTRA_TRAFFIC, 0.0) else null,
            destName = intent.getStringExtra(EXTRA_DEST_NAME).orEmpty(),
            destAddress = intent.getStringExtra(EXTRA_DEST_ADDR).orEmpty(),
            distToNextM = intent.getDoubleExtra(EXTRA_DIST_M, 0.0),
            basemapArchive = intent.getStringExtra(EXTRA_BASEMAP),
        )
        setContent {
            VelaTheme(darkTheme = true, dynamicColor = false) {
                LowPowerScreen(snap, onExit = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Glanceable on the lock: the turn stays up while the keyguard is showing,
        // so a power-button press shows the next turn without a full unlock. Only
        // when the user actually UNLOCKS (keyguard gone AND device unlocked) does
        // the map become the surface again. isKeyguardLocked alone is not enough:
        // on several ROMs it reports false while the lock screen is still visible.
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked && !km.isDeviceLocked) finish()
    }

    companion object {
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_INSTRUCTION = "instruction"
        const val EXTRA_ROAD = "road"
        const val EXTRA_ETA = "eta"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_MANEUVER = "maneuver"
        // Live-map drive state (see LowPowerSnapshot): the route as parallel double
        // arrays (Parcelable lists of LatLng would also work but cost more), the
        // puck fix, progress, and the banner/ETA fields.
        const val EXTRA_ROUTE_LAT = "route_lat"
        const val EXTRA_ROUTE_LNG = "route_lng"
        const val EXTRA_TRAVELED_M = "traveled_m"
        const val EXTRA_MY_LAT = "my_lat"
        const val EXTRA_MY_LNG = "my_lng"
        const val EXTRA_BEARING = "bearing"
        const val EXTRA_STEP = "step"
        const val EXTRA_OFF_ROUTE = "off_route"
        const val EXTRA_M_REF = "m_ref"
        const val EXTRA_M_ROAD = "m_road"
        const val EXTRA_LANE_HINT = "lane_hint"
        const val EXTRA_NEXT = "next"
        const val EXTRA_NEXT_MANEUVER = "next_maneuver"
        const val EXTRA_NEXT_DIST = "next_dist"
        const val EXTRA_CURRENT_REF = "current_ref"
        const val EXTRA_REM_DIST = "rem_dist"
        const val EXTRA_REM_SEC = "rem_sec"
        const val EXTRA_TRAFFIC = "traffic"
        const val EXTRA_DEST_NAME = "dest_name"
        const val EXTRA_DEST_ADDR = "dest_addr"
        /** Approach distance to the shown maneuver, meters (banner headline). */
        const val EXTRA_DIST_M = "dist_m"
        /** Offline basemap archive URI covering the view (null = stream Liberty). */
        const val EXTRA_BASEMAP = "basemap"

        /**
         * Essentials-compatible MinMode entry point: the Essentials "Maps power saving
         * mode" tile/receiver fires
         * `com.google.android.apps.maps/com.google.android.apps.gmm.features.minmode.MinModeActivity`
         * on screen-off during navigation. Vela answers the SAME component (see the
         * manifest alias below), so Essentials drives Vela's overlay with zero changes
         * on its side. Extras are best-effort: Essentials sends none, so the overlay
         * falls back to the last pushed snapshot ([LowPowerWatcher.latestSnapshot]).
         */
        const val MINMODE_ACTION = "com.google.android.apps.maps.minmode.SHOW"

        /** Show the overlay for [snap]; no-op unless the toggle is on and locked. */
        fun show(context: Context, snap: LowPowerSnapshot) {
            showInternal(context, snap)
        }

        /** Essentials path: no snapshot in the intent, use the last pushed one. */
        fun showFromExternal(context: Context, intent: Intent?) {
            val snap = if (intent != null && intent.hasExtra(EXTRA_DISTANCE)) {
                snapshotFromIntent(intent)
            } else null
            val resolved = snap?.takeIf { it.instruction.isNotBlank() || it.distanceText.isNotBlank() }
                ?: LowPowerWatcher.latestSnapshot()
                ?: return
            showInternal(context, resolved)
        }

        /** Rebuild a snapshot from an intent (onCreate path and the update broadcast). */
        fun snapshotFromIntent(intent: Intent): LowPowerSnapshot = LowPowerSnapshot(
            distanceText = intent.getStringExtra(EXTRA_DISTANCE).orEmpty(),
            instruction = intent.getStringExtra(EXTRA_INSTRUCTION).orEmpty(),
            road = intent.getStringExtra(EXTRA_ROAD),
            etaText = intent.getStringExtra(EXTRA_ETA).orEmpty(),
            locationText = intent.getStringExtra(EXTRA_LOCATION).orEmpty(),
            maneuverOrdinal = intent.getIntExtra(EXTRA_MANEUVER, -1),
            routeLat = intent.getDoubleArrayExtra(EXTRA_ROUTE_LAT) ?: doubleArrayOf(),
            routeLng = intent.getDoubleArrayExtra(EXTRA_ROUTE_LNG) ?: doubleArrayOf(),
            traveledM = intent.getDoubleExtra(EXTRA_TRAVELED_M, 0.0),
            myLat = intent.getDoubleExtra(EXTRA_MY_LAT, Double.NaN),
            myLng = intent.getDoubleExtra(EXTRA_MY_LNG, Double.NaN),
            bearing = if (intent.hasExtra(EXTRA_BEARING)) intent.getFloatExtra(EXTRA_BEARING, 0f) else null,
            stepIndex = intent.getIntExtra(EXTRA_STEP, 0),
            offRoute = intent.getBooleanExtra(EXTRA_OFF_ROUTE, false),
            maneuverRef = intent.getStringExtra(EXTRA_M_REF),
            maneuverRoad = intent.getStringExtra(EXTRA_M_ROAD),
            laneHint = intent.getStringExtra(EXTRA_LANE_HINT),
            nextInstruction = intent.getStringExtra(EXTRA_NEXT),
            nextManeuverOrdinal = intent.getIntExtra(EXTRA_NEXT_MANEUVER, -1),
            nextDistanceM = if (intent.hasExtra(EXTRA_NEXT_DIST)) intent.getDoubleExtra(EXTRA_NEXT_DIST, 0.0) else null,
            currentRef = intent.getStringExtra(EXTRA_CURRENT_REF),
            remainingDistanceM = intent.getDoubleExtra(EXTRA_REM_DIST, 0.0),
            remainingSeconds = intent.getDoubleExtra(EXTRA_REM_SEC, 0.0),
            trafficRatio = if (intent.hasExtra(EXTRA_TRAFFIC)) intent.getDoubleExtra(EXTRA_TRAFFIC, 0.0) else null,
            destName = intent.getStringExtra(EXTRA_DEST_NAME).orEmpty(),
            destAddress = intent.getStringExtra(EXTRA_DEST_ADDR).orEmpty(),
            distToNextM = intent.getDoubleExtra(EXTRA_DIST_M, 0.0),
            basemapArchive = intent.getStringExtra(EXTRA_BASEMAP),
        )

        /** Pack a snapshot into an intent (launch and live-update broadcast share it). */
        fun putSnapshot(intent: Intent, snap: LowPowerSnapshot): Intent = intent.apply {
            putExtra(EXTRA_DISTANCE, snap.distanceText)
            putExtra(EXTRA_INSTRUCTION, snap.instruction)
            putExtra(EXTRA_ROAD, snap.road)
            putExtra(EXTRA_ETA, snap.etaText)
            putExtra(EXTRA_LOCATION, snap.locationText)
            putExtra(EXTRA_MANEUVER, snap.maneuverOrdinal)
            putExtra(EXTRA_ROUTE_LAT, snap.routeLat)
            putExtra(EXTRA_ROUTE_LNG, snap.routeLng)
            putExtra(EXTRA_TRAVELED_M, snap.traveledM)
            putExtra(EXTRA_MY_LAT, snap.myLat)
            putExtra(EXTRA_MY_LNG, snap.myLng)
            snap.bearing?.let { putExtra(EXTRA_BEARING, it) }
            putExtra(EXTRA_STEP, snap.stepIndex)
            putExtra(EXTRA_OFF_ROUTE, snap.offRoute)
            putExtra(EXTRA_M_REF, snap.maneuverRef)
            putExtra(EXTRA_M_ROAD, snap.maneuverRoad)
            putExtra(EXTRA_LANE_HINT, snap.laneHint)
            putExtra(EXTRA_NEXT, snap.nextInstruction)
            putExtra(EXTRA_NEXT_MANEUVER, snap.nextManeuverOrdinal)
            snap.nextDistanceM?.let { putExtra(EXTRA_NEXT_DIST, it) }
            putExtra(EXTRA_CURRENT_REF, snap.currentRef)
            putExtra(EXTRA_REM_DIST, snap.remainingDistanceM)
            putExtra(EXTRA_REM_SEC, snap.remainingSeconds)
            snap.trafficRatio?.let { putExtra(EXTRA_TRAFFIC, it) }
            putExtra(EXTRA_DEST_NAME, snap.destName)
            putExtra(EXTRA_DEST_ADDR, snap.destAddress)
            putExtra(EXTRA_DIST_M, snap.distToNextM)
            putExtra(EXTRA_BASEMAP, snap.basemapArchive)
        }

        /** A live-update broadcast the overlay listens for (action = class name). */
        fun broadcastUpdate(context: Context, snap: LowPowerSnapshot) {
            runCatching {
                context.sendBroadcast(putSnapshot(Intent(LowPowerActivity::class.java.name), snap))
            }
        }

        private fun showInternal(context: Context, snap: LowPowerSnapshot) {
            if (!LowPowerNav.on.value) return
            runCatching {
                context.startActivity(
                    putSnapshot(
                        Intent(context, LowPowerActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        },
                        snap,
                    ),
                )
            }
        }

        fun hide(context: Context) {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.appTasks.forEach { task ->
                    runCatching {
                        if (task.taskInfo?.baseActivity?.className?.endsWith("LowPowerActivity") == true) {
                            task.finishAndRemoveTask()
                        }
                    }
                }
            }
        }
    }
}

/** The turn snapshot the overlay renders: plain strings, no model dependency.
 *  [etaText] is the remaining trip ("12 min · 7:42 PM", like the nav bar);
 *  [locationText] is where you are ("on Silverado Saddle Heights SW", like the
 *  road pill) — blank when nothing stable is known.
 *
 *  The live-map fields ([routeLat]/[routeLng], [traveledM], [myLat]/[myLng],
 *  [bearing], [stepIndex], banner fields) let the overlay draw the SAME drive as
 *  the main screen: the route line with traversed-gray progress, the puck, and
 *  the real turn banner + ETA bar — Google's MinMode. The polyline is capped at
 *  [MAX_ROUTE_POINTS] points (evenly decimated) so the intent stays small; a
 *  longer route still draws correctly, just coarser off-screen. */
data class LowPowerSnapshot(
    val distanceText: String,
    val instruction: String,
    val road: String?,
    val etaText: String,
    val locationText: String = "",
    /** app.vela.core.model.ManeuverType ordinal, or -1 when unknown. */
    val maneuverOrdinal: Int,
    // Live-map drive state (all optional: absent = the static turn card).
    val routeLat: DoubleArray = doubleArrayOf(),
    val routeLng: DoubleArray = doubleArrayOf(),
    /** Meters already traveled along the route (traversed-gray split). */
    val traveledM: Double = 0.0,
    val myLat: Double = Double.NaN,
    val myLng: Double = Double.NaN,
    val bearing: Float? = null,
    val stepIndex: Int = 0,
    val offRoute: Boolean = false,
    // Banner fields for the CURRENT maneuver (the overlay's own ManeuverBanner).
    val maneuverRef: String? = null,
    val maneuverRoad: String? = null,
    val laneHint: String? = null,
    val nextInstruction: String? = null,
    val nextManeuverOrdinal: Int = -1,
    val nextDistanceM: Double? = null,
    val currentRef: String? = null,
    val remainingDistanceM: Double = 0.0,
    val remainingSeconds: Double = 0.0,
    val trafficRatio: Double? = null,
    val destName: String = "",
    val destAddress: String = "",
    /** Approach distance to the shown maneuver (banner headline), meters. */
    val distToNextM: Double = 0.0,
    /** Offline basemap archive URI (null = stream the Liberty style). */
    val basemapArchive: String? = null,
) {
    companion object {
        /** Polyline cap for the intent: ~64 KB as doubles, plenty for the visible drive. */
        const val MAX_ROUTE_POINTS = 4000
    }
}

/**
 * Watches the screen while navigating: screen-off with the toggle on and the
 * phone locked raises the black overlay (built from the latest nav snapshot the
 * UI layer pushes via [push]); screen-on/unlock or nav end drops it again.
 * Register from MainActivity while navigation is active.
 */
object LowPowerWatcher {
    private var receiver: BroadcastReceiver? = null
    @Volatile private var latest: LowPowerSnapshot? = null

    fun push(snap: LowPowerSnapshot) {
        latest = snap
    }

    /** Last pushed snapshot, for the Essentials/MinMode entry path (no extras). */
    fun latestSnapshot(): LowPowerSnapshot? = latest

    fun clear() {
        latest = null
    }

    fun attach(activity: Activity) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    // Screen off while navigating: raise the overlay immediately, before
                    // the display sleeps. It renders over the lock screen (showWhenLocked
                    // + turnScreenOn), so a later power-button press wakes straight into
                    // the turn — no full unlock needed. Matches the
                    // Essentials/SecurityReceiver path, which fires the MinMode alias
                    // on the same broadcast.
                    Intent.ACTION_SCREEN_OFF -> latest?.let { LowPowerActivity.show(context, it) }
                    // A plain wake (power button, no unlock): the overlay is already up
                    // from screen-off; re-raise it in case the keyguard came up over it.
                    Intent.ACTION_SCREEN_ON -> latest?.let { LowPowerActivity.show(context, it) }
                    Intent.ACTION_USER_PRESENT -> LowPowerActivity.hide(context)
                }
            }
        }
        // NOT_EXPORTED: all three actions are system-only broadcasts (screen/lock state).
        // The unflagged overload throws SecurityException on targetSdk 34+ (the crash).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(
                r,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            activity.registerReceiver(r, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        }
        receiver = r
    }

    fun detach(activity: Activity) {
        receiver?.let { runCatching { activity.unregisterReceiver(it) } }
        receiver = null
        latest = null
        LowPowerActivity.hide(activity)
    }
}

@Composable
private fun LowPowerScreen(snap: LowPowerSnapshot, onExit: () -> Unit) {
    val context = LocalContext.current
    // A turn spoken while the overlay is up replaces it in place: the watcher
    // keeps pushing snapshots, and the latest wins. The map/banner/ETA below all
    // read this one state, so every tick moves everything together.
    var current by remember { mutableStateOf(snap) }
    DisposableEffect(context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == LowPowerActivity::class.java.name) {
                    current = LowPowerActivity.snapshotFromIntent(intent)
                }
            }
        }
        // NOT_EXPORTED: app-private update broadcast; the unflagged overload throws
        // SecurityException on targetSdk 34+ (same crash class as the watcher above).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(r, IntentFilter(LowPowerActivity::class.java.name), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(r, IntentFilter(LowPowerActivity::class.java.name))
        }
        onDispose { runCatching { context.unregisterReceiver(r) } }
    }
    // No route yet (Essentials path with nothing pushed, or a stale snapshot): the
    // static turn card. With a route, the live map below takes over.
    if (current.routeLat.size < 2 || current.routeLat.size != current.routeLng.size) {
        LowPowerStaticCard(current, onExit)
        return
    }
    LowPowerLiveMap(current, onExit)
}

@Composable
private fun LowPowerStaticCard(snap: LowPowerSnapshot, onExit: () -> Unit) {    val types = remember { app.vela.core.model.ManeuverType.entries }
    val glyph = types.getOrNull(snap.maneuverOrdinal)?.let { maneuverIcon(it) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onExit),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (glyph != null) {
                Surface(shape = CircleShape, color = Color(0xFF1E8E3E)) {
                    Icon(
                        glyph,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(20.dp).size(56.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
            } else {
                Icon(
                    Icons.Default.Navigation,
                    contentDescription = null,
                    tint = Color(0xFF8AB4F8),
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
            if (snap.distanceText.isNotBlank()) {
                Text(
                    snap.distanceText,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
            }
            val headline = snap.road?.takeIf { it.isNotBlank() }
                ?: snap.instruction.takeIf { it.isNotBlank() }
                ?: "Navigating"
            // The headline is the turn instruction; the remaining time/distance sits
            // with it (the bar's figures), then where you are (the road pill).
            Text(
                headline,
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFE8EAED),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (snap.etaText.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(snap.etaText, style = MaterialTheme.typography.titleMedium, color = Color(0xFF9AA0A6))
            }
            if (snap.locationText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(snap.locationText, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF9AA0A6))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Tap anywhere to return to the map",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5F6368),
            )
        }
    }
}

/**
 * Google's MinMode: the LIVE drive on a black map. The route line (traversed
 * gray behind the puck, blue ahead), the location puck with heading-up follow,
 * the real turn banner and a bottom ETA pill — the same ManeuverBanner the main
 * screen uses, so the overlay can never disagree with it.
 *
 * Minimal chrome on purpose: no POIs, no traffic raster, no satellite, no
 * steps list, no End button (ending a drive from the lock screen by accident
 * is the failure mode). Any tap on the map or the ETA pill returns to the map;
 * the banner keeps its swipe grammar but previews nothing (no-ops).
 */
@Composable
private fun LowPowerLiveMap(snap: LowPowerSnapshot, onExit: () -> Unit) {
    val route = remember(snap.routeLat.size, snap.traveledM) {
        val n = minOf(snap.routeLat.size, snap.routeLng.size)
        List(n) { i -> LatLng(snap.routeLat[i], snap.routeLng[i]) }
    }
    val myLoc = remember(snap.myLat, snap.myLng) {
        if (snap.myLat.isNaN() || snap.myLng.isNaN()) null
        else LatLng(snap.myLat, snap.myLng)
    }
    // MinMode draws the route WHITE, always — Google's black/white look. The
    // traversed part falls back to the AMOLED dark-gray automatically.
    val routeColor = "#FFFFFF"
    val types = remember { ManeuverType.entries }
    val etaColor = remember(snap.trafficRatio) {
        when (val r = snap.trafficRatio) {
            null -> Color.White
            else -> when {
                r > 1.4 -> Color(0xFFD93838)
                r > 1.15 -> Color(0xFFE8923D)
                else -> Color(0xFF34A853)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VelaMapView(
            styleUri = MapFonts.effective(MapStyle.DEFAULT.uri),
            myLocation = myLoc,
            myBearing = snap.bearing,
            cameraTarget = null,
            routePolyline = route,
            routeColor = routeColor,
            markers = emptyList(),
            frameMarkers = false,
            navMode = true,
            navDriveMode = true,
            navFollowing = true,
            whitePuck = true,
            darkTheme = true,
            amoled = true,
            applyKeylessTheme = true,
            trafficOn = false,
            previewTarget = null,
            onPoiTap = { _, _, _ -> },
            onMarkerTap = {},
            onCameraIdle = {},
            onMapLongPress = {},
            onMapTap = onExit,
            poisEnabled = false,
            basemapArchive = snap.basemapArchive,
            modifier = Modifier.fillMaxSize(),
        )
        ManeuverBanner(
            text = snap.instruction,
            distanceMeters = snap.distToNextM,
            type = types.getOrNull(snap.maneuverOrdinal) ?: ManeuverType.STRAIGHT,
            ref = snap.maneuverRef,
            laneHint = snap.laneHint,
            nextText = snap.nextInstruction,
            nextType = types.getOrNull(snap.nextManeuverOrdinal),
            currentRef = snap.currentRef,
            nextDistanceMeters = snap.nextDistanceM,
            destName = snap.destName.ifBlank { null },
            destAddress = snap.destAddress.ifBlank { null },
            offRoute = snap.offRoute,
            minMode = true,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
        )
        // Google's MinMode bottom is bare text on the map — no pill, no buttons.
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
                .clickable(onClick = onExit),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                formatDuration(snap.remainingSeconds),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = etaColor,
                maxLines = 1,
            )
            Text(
                formatDistance(snap.remainingDistanceM) + " · " + formatArrivalClock(snap.remainingSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF9AA0A6),
                maxLines = 1,
            )
        }
    }
}
