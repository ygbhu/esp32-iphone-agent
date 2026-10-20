package com.esp32phone.agent.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-secret app state: calibration flags, board identity and UI preferences.
 *
 * Never stores the user Key, the device binding secret, screenshots or chat content.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent.preferences", Context.MODE_PRIVATE)

    /** True only after a completed calibration run on this device/display. */
    var calibrationValid: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATION, false)
        set(value) = prefs.edit().putBoolean(KEY_CALIBRATION, value).apply()

    /** Bound board id, empty until a device binding is performed. */
    var boundBoardId: String
        get() = prefs.getString(KEY_BOARD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BOARD, value.trim()).apply()

    companion object {
        private const val KEY_CALIBRATION = "calibration_valid"
        private const val KEY_BOARD = "bound_board"
    }
}
