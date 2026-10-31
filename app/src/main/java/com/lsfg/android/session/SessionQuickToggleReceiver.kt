package com.lsfg.android.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lsfg.android.R
import com.lsfg.android.prefs.LsfgPreferences

/**
 * Handles the quick-toggle notification's "Refresh current app" button.
 *
 * Reuses the same privileged foreground probe as the launcher dot's
 * "Refresh current app" affordance ([AutoOverlayController.requestForegroundRefresh]):
 * the result becomes the persisted target package, and the notification text
 * is rebuilt so the next "Start session" tap picks up the new app.
 *
 * The probe hook only exists while [AutoOverlayWatcherService] runs; when it
 * is absent (service was killed or never started) we start it and retry once,
 * matching the dot's behaviour of failing open rather than silently doing
 * nothing.
 */
class SessionQuickToggleReceiver : BroadcastReceiver() {

    private val main = Handler(Looper.getMainLooper())

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != SessionQuickToggle.ACTION_REFRESH_TARGET) return
        val appCtx = ctx.applicationContext
        val pending = goAsync()
        LsfgLog.i(TAG, "refresh current app requested from notification")

        fun done() = runCatching { pending.finish() }

        fun applyTarget(pkg: String) {
            if (pkg == appCtx.packageName) {
                // Pulling the shade down doesn't change the foreground app, but
                // tapping the notification body may have brought us forward.
                Toast.makeText(appCtx, R.string.quick_toggle_refresh_self, Toast.LENGTH_SHORT).show()
                done()
                return
            }
            if (SessionQuickToggle.isHomePackage(appCtx, pkg)) {
                // The launcher is never a useful capture target — keep the
                // current one instead of silently retargeting to the desktop.
                LsfgLog.i(TAG, "foreground is the launcher — keeping current target")
                Toast.makeText(appCtx, R.string.quick_toggle_refresh_home, Toast.LENGTH_LONG).show()
                done()
                return
            }
            LsfgPreferences(appCtx).setTargetPackage(pkg)
            LsfgLog.i(TAG, "retargeted to $pkg")
            SessionQuickToggle.show(appCtx)
            Toast.makeText(
                appCtx,
                appCtx.getString(R.string.quick_toggle_refresh_ok, SessionQuickToggle.appLabel(appCtx, pkg)),
                Toast.LENGTH_SHORT,
            ).show()
            done()
        }

        fun attempt(retry: Boolean) {
            AutoOverlayController.init(appCtx)
            AutoOverlayController.requestForegroundRefresh { pkg ->
                when {
                    pkg != null -> applyTarget(pkg)
                    !retry -> {
                        // No probe backend yet (watcher not running) — start it
                        // and give the Shizuku/Root probe a moment to bind.
                        LsfgLog.i(TAG, "probe unavailable — starting watcher and retrying once")
                        AutoOverlayWatcherService.ensureStarted(appCtx)
                        main.postDelayed({ attempt(true) }, RETRY_DELAY_MS)
                    }
                    else -> {
                        LsfgLog.w(TAG, "foreground probe failed twice — leaving target unchanged")
                        Toast.makeText(appCtx, R.string.quick_toggle_refresh_failed, Toast.LENGTH_LONG).show()
                        done()
                    }
                }
            }
        }

        attempt(false)
    }

    companion object {
        private const val TAG = "QuickToggleRx"
        /** Watcher startup + Shizuku probe bind is ~1-2 s on a cold service. */
        private const val RETRY_DELAY_MS = 1_800L
    }
}
