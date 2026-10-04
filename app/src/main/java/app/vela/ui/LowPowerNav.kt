package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Low-power navigation screen (the Pixel "Maps power saving mode" idea, built in):
 * while navigating, turning the screen off and back on shows a minimal pitch-black
 * lock-screen overlay with only the next turn, instead of the full map. The map
 * renderer sleeps behind it, so an ambient dash mount sips battery.
 *
 * Off by default. Needs no root, Shizuku, or notification listener: Vela knows
 * its own nav state, and the overlay is just an activity over the lock screen.
 */
object LowPowerNav {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "low_power_nav"
}
