package com.lsfg.android.session

import com.lsfg.android.R
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.ui.ProjectionRequestActivity

/**
 * Process-wide coordinator for the "Automatic Overlay" feature.
 *
 * Watches foreground app changes (fed by [LsfgAccessibilityService]) and toggles
 * the orange edge-handle [LauncherDotOverlay] when one of the user-selected
 * target apps comes to the front. The handle is purely a UI affordance — it
 * never starts capture on its own; the user drags it open, sees the panel built
 * by [LauncherSheetView], and from there explicitly activates the full LSFG
 * capture session.
 *
 * Coordinates with [LsfgForegroundService]: when a session is active the handle
 * is hidden (the in-game settings drawer takes over); when the session stops,
 * the handle reappears if the target app is still in foreground.
 */
object AutoOverlayController {

    private const val TAG = "AutoOverlayCtrl"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var enabledApps: Set<String> = emptySet()

    @Volatile
    private var alwaysShow: Boolean = false

    @Volatile
    private var lastForegroundPkg: String? = null

    @Volatile
    private var sessionActive: Boolean = false

    /** True while the live session has parked its overlay (target app left
     *  the foreground). In that state the launcher dot may show again so the
     *  user can switch to another target without waiting for auto-stop. */
    @Volatile
    private var sessionOverlayParked: Boolean = false

    @Volatile
    private var dot: LauncherDotOverlay? = null
    private var overlayPermToastShown = false

    /**
     * Optional hook for the foreground service: invoked on every foreground
     * change while a session is active, so the service can auto-stop when the
     * target app exits. Registered/unregistered by LsfgForegroundService.
     */
    @Volatile
    private var sessionWatcher: ((String) -> Unit)? = null

    fun setSessionWatcher(watcher: ((String) -> Unit)?) {
        sessionWatcher = watcher
    }

    /** Hook for [AutoOverlayWatcherService]: runs a forced foreground probe
     *  async and delivers the fresh pkg (null = probe failed) to the callback. */
    @Volatile
    private var refreshHook: (((String?) -> Unit) -> Unit)? = null

    fun setForegroundRefreshHook(hook: (((String?) -> Unit) -> Unit)?) {
        refreshHook = hook
    }

    /** "Refresh current app" from the launcher sheet. */
    fun requestForegroundRefresh(result: (String?) -> Unit) {
        val hook = refreshHook
        if (hook == null) {
            mainHandler.post { result(null) }
            return
        }
        hook { pkg -> mainHandler.post { result(pkg) } }
    }

    @Synchronized
    fun init(ctx: Context) {
        val prefs = LsfgPreferences(ctx)
        enabledApps = prefs.getAutoEnabledApps()
        alwaysShow = prefs.getAutoOverlayAlwaysShow()
        Log.i(TAG, "init — ${enabledApps.size} app(s) enabled, alwaysShow=$alwaysShow")
    }

    /**
     * "Always show" mode: the launcher handle is visible on every app and the
     * home screen; activating it starts frame generation for whatever app is
     * currently in the foreground (already how onActivateRequested works).
     */
    @Synchronized
    fun onAlwaysShowChanged(ctx: Context, value: Boolean) {
        alwaysShow = value
        val appCtx = ctx.applicationContext
        if (value && !PermissionsHelper.isAccessibilityServiceEnabled(appCtx) &&
            LsfgPreferences(appCtx).load().captureSource != CaptureSource.MEDIA_PROJECTION
        ) {
            AutoOverlayWatcherService.ensureStarted(appCtx)
        }
        val pkg = lastForegroundPkg
        if (pkg != null) evaluate(appCtx, pkg)
    }

    @Synchronized
    fun onAutoEnabledAppsChanged(ctx: Context, newSet: Set<String>) {
        enabledApps = newSet
        // Keep the Shizuku/Root foreground watcher in sync with the user's
        // selection when accessibility (the original trigger source) is off.
        val appCtx = ctx.applicationContext
        if (newSet.isEmpty() && !alwaysShow) {
            AutoOverlayWatcherService.stop(appCtx)
        } else if (!PermissionsHelper.isAccessibilityServiceEnabled(appCtx) &&
            LsfgPreferences(appCtx).load().captureSource != CaptureSource.MEDIA_PROJECTION
        ) {
            AutoOverlayWatcherService.ensureStarted(appCtx)
        }
        val pkg = lastForegroundPkg
        if (pkg != null) {
            evaluate(ctx, pkg)
        } else if (newSet.isEmpty()) {
            hideDot()
        }
    }

    /**
     * Called by the options screen after the user flips the overlay entry mode
     * between drawer / icon button. If the launcher dot is currently visible,
     * tear it down and re-show so it picks up the new entry-affordance.
     * Re-reading the pref on every show() also means a hidden dot will pick up
     * the change next time it appears.
     */
    @Synchronized
    fun onOverlayModeChanged(ctx: Context) {
        val pkg = lastForegroundPkg ?: return
        if (dot == null) return
        Log.i(TAG, "overlay mode changed — recreating launcher dot")
        val appCtx = ctx.applicationContext
        mainHandler.post {
            dot?.hide()
            dot = null
            evaluate(appCtx, pkg)
        }
    }

    fun onForegroundPackage(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName) return
        lastForegroundPkg = pkg
        evaluate(ctx, pkg)
        if (sessionActive) sessionWatcher?.invoke(pkg)
    }

    fun onSessionStarted(pkg: String?) {
        sessionActive = true
        sessionOverlayParked = false
        Log.i(TAG, "session started for $pkg — hiding handle")
        hideDot()
    }

    fun onSessionStopped(ctx: Context) {
        sessionActive = false
        sessionOverlayParked = false
        Log.i(TAG, "session stopped — re-evaluating handle for $lastForegroundPkg")
        val pkg = lastForegroundPkg
        if (pkg != null) {
            evaluate(ctx, pkg)
        }
    }

    private fun onActivateRequested(ctx: Context) {
        val target = lastForegroundPkg ?: return
        hideDot()
        val intent = ProjectionRequestActivity.buildIntent(ctx, target)
        ctx.startActivity(intent)
    }

    private fun onDisableForApp(ctx: Context) {
        val pkg = lastForegroundPkg ?: return
        val updated = enabledApps - pkg
        enabledApps = updated
        LsfgPreferences(ctx).setAutoEnabledApps(updated)
        hideDot()
    }

    /** Called by the foreground service when its overlay parks/unparks. */
    @Synchronized
    fun onSessionOverlayParked(ctx: Context, parked: Boolean) {
        if (sessionOverlayParked == parked) return
        sessionOverlayParked = parked
        val pkg = lastForegroundPkg ?: return
        evaluate(ctx, pkg)
    }

    private fun evaluate(ctx: Context, pkg: String) {
        if (sessionActive && !sessionOverlayParked) {
            hideDot()
            return
        }
        if (alwaysShow || pkg in enabledApps) {
            showDot(ctx)
        } else {
            hideDot()
        }
    }

    private fun showDot(ctx: Context) {
        val appCtx = ctx.applicationContext
        mainHandler.post {
            if (dot != null) return@post
            // Mirror the in-game settings drawer's entry mode so the user sees
            // the same affordance everywhere. Reading the pref each time the
            // dot appears means switching the option in the app picks up
            // automatically the next time the launcher is shown.
            val mode = LsfgPreferences(appCtx).load().overlayMode
            val d = LauncherDotOverlay(
                ctx = appCtx,
                targetPackageProvider = { lastForegroundPkg },
                onActivate = { onActivateRequested(appCtx) },
                onDisableForApp = if (alwaysShow) null else ({ onDisableForApp(appCtx) }),
                entryMode = mode,
                onRefresh = { result -> requestForegroundRefresh(result) },
            )
            val attached = d.show()
            dot = if (attached) d else null
            if (!attached && !overlayPermToastShown) {
                overlayPermToastShown = true
                android.widget.Toast.makeText(
                    appCtx,
                    appCtx.getString(R.string.toast_overlay_perm),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun hideDot() {
        mainHandler.post {
            dot?.hide()
            dot = null
        }
    }
}
