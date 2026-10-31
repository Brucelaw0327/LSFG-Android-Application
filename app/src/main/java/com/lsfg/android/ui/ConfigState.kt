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
        pacingPreset = PacingPreset.BALANCED,
        vsyncAlignmentEnabled = PacingDefaults.VSYNC_ALIGNMENT,
        vsyncRefreshOverride = VsyncRefreshOverride.AUTO,
        targetFpsCap = PacingDefaults.TARGET_FPS_CAP,
        emaAlpha = PacingDefaults.EMA_ALPHA,
        outlierRatio = PacingDefaults.OUTLIER_RATIO,
        vsyncSlackMs = PacingDefaults.VSYNC_SLACK_MS,
        queueDepth = PacingDefaults.QUEUE_DEPTH,
        autoEnabledApps = emptySet(),
        trustedOverlay = false,
        pauseOnTouch = false,
        fpsGuardEnabled = true,
        fpsGuardThreshold = 62,
    )
)

fun produceConfigState(prefs: LsfgPreferences): StateFlow<LsfgConfig> {
    shared.value = prefs.load()
    return shared
}

fun refreshConfigState(prefs: LsfgPreferences) {
    shared.value = prefs.load()
}
