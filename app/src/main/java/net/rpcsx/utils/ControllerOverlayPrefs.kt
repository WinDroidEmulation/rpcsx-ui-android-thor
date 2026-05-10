package net.rpcsx.utils

import net.rpcsx.performance.ThorPerformanceProfile
import net.rpcsx.utils.GeneralSettings.boolean

object ControllerOverlayPrefs {
    private const val PREF_SHOW_SCREEN_CONTROLS = "show_screen_controls"

    fun defaultShowScreenControls(): Boolean = !ThorPerformanceProfile.isThorTarget()

    fun showScreenControls(): Boolean {
        return GeneralSettings[PREF_SHOW_SCREEN_CONTROLS].boolean(defaultShowScreenControls())
    }

    fun setShowScreenControls(value: Boolean) {
        GeneralSettings.setValue(PREF_SHOW_SCREEN_CONTROLS, value)
    }
}
