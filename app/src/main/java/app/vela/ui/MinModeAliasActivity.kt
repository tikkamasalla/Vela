package app.vela.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Essentials-compatible MinMode alias: Essentials' "Maps power saving mode"
 * fires `am start -n com.google.android.apps.maps/com.google.android.apps.gmm.features.minmode.MinModeActivity`
 * on screen-off during navigation. That activity only exists in NEW Google Maps;
 * the user's Maps 11.126 predates it, and patching a proprietary APK to add it is
 * off the table — so Vela answers the component itself (see the manifest
 * activity-alias) and renders its own black turn overlay from the last pushed
 * navigation snapshot. No extras are sent by Essentials; [LowPowerActivity]
 * falls back to [LowPowerWatcher.latestSnapshot]. Transparent: forwards to
 * [LowPowerActivity] and finishes.
 */
class MinModeAliasActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LowPowerActivity.showFromExternal(this, intent)
        finish()
    }

    companion object {
        /** Essentials' exact shell command, runnable from adb for testing. */
        fun startCommand(): String =
            "am start -n com.google.android.apps.maps/com.google.android.apps.gmm.features.minmode.MinModeActivity"

        fun fire(context: Context) {
            runCatching {
                context.startActivity(
                    Intent().apply {
                        setClassName(
                            "com.google.android.apps.maps",
                            "com.google.android.apps.gmm.features.minmode.MinModeActivity",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }
}
