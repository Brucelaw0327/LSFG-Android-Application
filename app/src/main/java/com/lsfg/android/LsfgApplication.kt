package com.lsfg.android

import android.app.Application
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.session.AutoOverlayController
import com.lsfg.android.session.AutoOverlayWatcherService
import com.lsfg.android.session.CrashReporter
import com.lsfg.android.session.LsfgLog
import com.lsfg.android.session.PermissionsHelper

class LsfgApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install the crash reporter as early as possible so even a crash during
        // the first JNI load attempt gets captured (NativeBridge static init
        // already loads the .so; the call below only configures the handler).
        CrashReporter.install(this)
        LsfgLog.init(this)
        // Always-show launcher dot: start the foreground watcher as soon as the
        // app opens so the handle appears without visiting the overlay screen.
        // Skipped for MediaProjection (no privileged foreground probe) and when
        // the accessibility service is already feeding foreground events.
        runCatching {
            val prefs = LsfgPreferences(this)
            if (prefs.getAutoOverlayAlwaysShow() &&
                !PermissionsHelper.isAccessibilityServiceEnabled(this) &&
                prefs.load().captureSource != CaptureSource.MEDIA_PROJECTION
            ) {
                AutoOverlayController.init(this)
                AutoOverlayWatcherService.ensureStarted(this)
            }
        }
    }
}
