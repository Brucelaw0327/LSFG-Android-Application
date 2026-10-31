package com.lsfg.android.ui

import com.lsfg.android.prefs.LsfgConfig
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.DrawerEdge
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.OverlayMode
import com.lsfg.android.prefs.PacingDefaults
import com.lsfg.android.prefs.PacingPreset
import com.lsfg.android.prefs.VsyncRefreshOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Produces a StateFlow bound to [LsfgPreferences] content. Each screen that mutates prefs
 * should call [refresh] after commit so the home screen reflects updates.
 *
 * This is intentionally simple (manual refresh) to avoid pulling in DataStore for Phase 1.
 */
private val shared: MutableStateFlow<LsfgConfig> = MutableStateFlow(
    LsfgConfig(
        dllUri = null,
        dllDisplayName = null,
        shadersReady = false,
        lsfgEnabled = true,
        multiplier = 2,
        flowScale = 0.6f,
        performanceMode = true,
        antiArtifacts = false,
        framegenFp16 = false,
        targetPackage = null,
        captureSource = CaptureSource.MEDIA_PROJECTION,
        legalAccepted = false,
        fpsCounterEnabled = false,
        frameGraphEnabled = false,
        drawerEdge = DrawerEdge.RIGHT,
        overlayMode = OverlayMode.ICON_BUTTON,
        // Defaults must match LsfgPreferences.load(): smooth pacing, performance
        // mode off. Effective pacing values come from PacingDefaults.forPreset().
        pacingPreset = PacingPreset.SMOOTH,
        vsyncAlignmentEnabled = PacingDefaults.VSYNC_ALIGNMENT,
        vsyncRefreshOverride = VsyncRefreshOverride.AUTO,
        targetFpsCap = PacingDefaults.TARGET_FPS_CAP,
        emaAlpha = 0.08f,
        outlierRatio = 6.0f,
        vsyncSlackMs = 3.0f,
        queueDepth = 6,
        autoEnabledApps = emptySet(),
        trustedOverlay = false,
        pauseOnTouch = false,
        fpsGuardEnabled = true,
        fpsGuardThreshold = 62,
    )
)

private var seeded = false

fun produceConfigState(prefs: LsfgPreferences): StateFlow<LsfgConfig> {
    // Seed once. Writing on every recomposition meant a 29-field SharedPreferences
    // read per recomposition (HomeScreen recomposes on scroll); the preference
    // listener installed by bindConfigState keeps `shared` current afterwards.
    if (!seeded) {
        shared.value = prefs.load()
        seeded = true
    }
    return shared
}

/** Kept for callers that write and immediately read back; the preference
 *  listener in [ConfigRepository] would deliver the same value a moment later. */
fun refreshConfigState(prefs: LsfgPreferences) {
    shared.value = prefs.load()
}

/**
 * Wires the process-wide preference listener into [shared] so writes made
 * outside the UI (in-game drawer, foreground service) are reflected on screen.
 * Call once from the single Activity.
 */
fun bindConfigState(ctx: android.content.Context) {
    com.lsfg.android.prefs.ConfigRepository.install(ctx) { config ->
        shared.value = config
    }
}
