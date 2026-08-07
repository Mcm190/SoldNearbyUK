package com.soldnearby.app.data

import android.content.Context

data class AppSettings(
    val recentOnly: Boolean = false,
    val heatmapEnabled: Boolean = false,
    // Defaults to light rather than following the system setting — the app picks its own
    // default independent of whatever the device's global appearance is set to.
    val darkModeEnabled: Boolean = false
)

/** Thin SharedPreferences wrapper — just a couple of scalar settings, not worth a bigger framework. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        recentOnly = prefs.getBoolean(KEY_RECENT_ONLY, false),
        heatmapEnabled = prefs.getBoolean(KEY_HEATMAP_ENABLED, false),
        darkModeEnabled = prefs.getBoolean(KEY_DARK_MODE_ENABLED, false)
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_RECENT_ONLY, settings.recentOnly)
            .putBoolean(KEY_HEATMAP_ENABLED, settings.heatmapEnabled)
            .putBoolean(KEY_DARK_MODE_ENABLED, settings.darkModeEnabled)
            .apply()
    }

    companion object {
        private const val KEY_RECENT_ONLY = "recent_only"
        private const val KEY_HEATMAP_ENABLED = "heatmap_enabled"
        private const val KEY_DARK_MODE_ENABLED = "dark_mode_enabled"
    }
}
