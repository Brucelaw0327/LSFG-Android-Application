package com.lsfg.android.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.HandlerThread
import android.os.Handler
import android.widget.Toast
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.View
import android.graphics.PixelFormat
import android.view.MotionEvent
import android.view.Gravity
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.lsfg.android.R
import com.lsfg.android.benchmark.BenchmarkController
import com.lsfg.android.benchmark.BenchmarkLogWriter
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.PacingDefaults
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs for the lifetime of an LSFG session. Owns the MediaProjection token, the
 * CaptureEngine, and the OverlayManager.
 *
 * Startup sequence (important for stable overlay behaviour):
 *   1. Post foreground notification (required by FGS + mediaProjection rules).
 *   2. Acquire MediaProjection from the consent intent.
 *   3. Show overlay (adds window via WindowManager — synchronous).
 *   4. **Wait for surfaceCreated before starting capture.** On-create is async;
 *      launching the target app before the Surface is ready was causing capture
 *      to race with the target's first paint, leaving the overlay empty.
 *   5. Launch the target app + re-assert overlay z-order.
 */
class LsfgForegroundService : Service() {

    private var projection: MediaProjection? = null
    private var capture: CaptureEngine? = null
    private var shizukuCapture: ShizukuCaptureEngine? = null
    // Bumped on every handleStart; delayed capture-start runnables from a
    // superseded session must not fire (zombie UID captures -> black screen).
    private var sessionGeneration = 0
    private var rootCapture: RootCaptureEngine? = null
    private var overlay: OverlayManager? = null
    private var drawer: SettingsDrawerOverlay? = null
    private var targetPkgPending: String? = null
    // Last non-null target of this service instance; survives targetPkgPending
    // consumption so rotation restarts can re-launch the same target.
    private var activeTargetPackage: String? = null
    private var overlayHiddenForBackground = false
    private var autoStopPending: Runnable? = null
    // Grace period before auto-stopping after the target app leaves foreground
    private val AUTO_STOP_DELAY_MS = 10_000L
    private val TARGET_ALIVE_POLL_MS = 1_000L
    private val TOUCH_PAUSE_POLL_MS = 80L
    /** Touches on our own overlay UI (drawer, launcher dot) inside this
     *  window never park framegen — parking removed the drawer window
     *  mid-tap and swallowed its clicks (End session bug). */
    private val OVERLAY_UI_TOUCH_GRACE_MS = 600L
    private val TOUCH_RESUME_DELAY_MS = 250L
    private val TOUCH_WATCH_RESUME_MS = 800L
    private var targetAlivePoller: Runnable? = null
    // Touch-to-pause: hide the framegen overlay while a finger is on the
    // screen so touch response runs on the native display with zero extra
    // latency; restore shortly after release.
    private var touchPauseThread: HandlerThread? = null
    private var touchPauseHandler: Handler? = null
    private var touchPausePoller: Runnable? = null
    private var touchPauseArmed = false
    private var touchResumePending = false
    private var touchWatchView: View? = null
    private var touchWatchResume: Runnable? = null

    private var initialCaptureStarted: Boolean = false
    private var lsfgContextActive: Boolean = false
    private var lastSurface: Surface? = null
    private var lastSurfaceW: Int = 0
    private var lastSurfaceH: Int = 0
    @Volatile
    private var activeRenderW: Int = 0
    @Volatile
    private var activeRenderH: Int = 0
    @Volatile
    private var reinitInFlight: Boolean = false
    // Signalled when the in-flight reinit thread finishes. Lets onDestroy() wait
    // for completion without busy-polling the Main thread (the previous code
    // burned ~75 cycles of Thread.sleep(20) before timeout, blocking the looper
    // and starving system input — causing visible freeze on swipe-out).
    @Volatile
    private var reinitDoneLatch: CountDownLatch? = null
    // When the user changes a parameter while a previous reinit is still in
    // flight, we can't start a second one concurrently (it would race on the
    // native context). Instead we mark a pending request and the in-flight
    // reinit re-runs itself once it finishes, picking up the freshest prefs.
    // Without this, mid-reinit changes were silently dropped — that's why
    // toggling "Bypass" appeared to "make settings apply": users were
    // accidentally triggering a second reinit by changing something else.
    @Volatile
    private var reinitRequested: Boolean = false
    @Volatile
    private var pendingReinitW: Int = 0
    @Volatile
    private var pendingReinitH: Int = 0
    // Set in onDestroy. While true, new reinit requests are dropped on the
    // floor — we're tearing down the service and any allocation we'd do here
    // would just have to be undone (and would race the shutdown).
    @Volatile
    private var shuttingDown: Boolean = false
    @Volatile
    private var pendingFpsCounter: Boolean = false
    @Volatile
    private var benchmarkRequested: Boolean = false
    @Volatile
    private var benchmarkStarted: Boolean = false
    /** Polls [BenchmarkController.currentState] to feed the in-overlay
     *  progress panel. Started in [maybeStartBenchmark]; cancelled when
     *  the controller reaches Completed/Failed or the service tears down. */
    private var benchmarkProgressPoller: Runnable? = null
    private var pendingPrivilegedVideoStart: ShizukuVideoStart? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // Display rotation listener — Service.onConfigurationChanged only fires
    // for configChanges declared in the manifest, but services can't declare
    // them. DisplayManager.DisplayListener fires on every rotation regardless.
    private var displayListener: DisplayManager.DisplayListener? = null

    // Rotation auto-restart: the privileged capture args (DisplayCaptureArgs
    // setSize) and the engine input-slot images are sized once at session
    // start. After a display rotation the captured buffers no longer match
    // the composed display and ScreenCapture hands us black frames (the
    // overlay shows black). The engine cannot resize live, so restart the
    // whole session for the new orientation — only for SHIZUKU/ROOT, which
    // can be started programmatically without user interaction.
    private var lastKnownRotation: Int = -1
    private var rotationRestartArmed: Boolean = false

    // Wait this long after a display rotation before rebuilding capture,
    // so the foreground app can finish its own rotation animation. The
    // overlay is parked during the wait so the game shows native frames
    // instead of framegen output from the frozen pre-rotation capture.
    private val rotationRestartDelayMs = 500L
    private var rotationRestartDeferred: Boolean = false
    private var lastStartId: Int = -1

    /**
     * Serializes every native initContext/destroyContext call. Two handleStart
     * passes (rotation auto-restart re-entry) or a reinit overlapping a session
     * start must never enter the native layer concurrently.
     */
    private val nativeInitLock = Any()
    private var activeCaptureSource: CaptureSource? = null
    private var activeFpsCounter: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        registerDisplayListener()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Belt-and-braces: in addition to DisplayListener we also handle the
        // platform configuration callback so very quick rotations don't slip
        // through (some OEMs deliver one but not the other).
        propagateDisplayChange()
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                propagateDisplayChange()
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        runCatching { dm.registerDisplayListener(listener, mainHandler) }
            .onSuccess { displayListener = listener }
            .onFailure { LsfgLog.w(TAG, "registerDisplayListener failed", it) }
    }

    private fun unregisterDisplayListener() {
        val l = displayListener ?: return
        displayListener = null
        val dm = getSystemService(DisplayManager::class.java) ?: return
        runCatching { dm.unregisterDisplayListener(l) }
    }

    private fun propagateDisplayChange() {
        // Always run on the main thread — WindowManager rejects updateViewLayout
        // calls from binder threads on some OEMs, and OverlayManager /
        // SettingsDrawerOverlay both touch the WM internally.
        mainHandler.post {
            // Arm rotation restart FIRST: the overlay reconfig below fires
            // the surface geometry-changed callback, which must see
            // rotationRestartArmed to skip its now-pointless LSFG reinit.
            maybeRestartForRotation()
            runCatching { overlay?.onDisplayConfigurationChanged() }
                .onFailure { LsfgLog.w(TAG, "overlay.onDisplayConfigurationChanged failed", it) }
            runCatching { drawer?.onDisplayConfigurationChanged() }
                .onFailure { LsfgLog.w(TAG, "drawer.onDisplayConfigurationChanged failed", it) }
        }
    }

    private fun currentRotation(): Int? = runCatching {
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
    }.getOrNull()

    /**
     * Detect a display rotation while a privileged session is running and
     * auto-restart the session (stopSelf + programmatic re-start) so the
     * capture args and engine images are rebuilt for the new orientation.
     * Without this the frozen capture size yields black frames after the
     * device rotates.
     */
    private fun maybeRestartForRotation() {
        val rot = currentRotation() ?: return
        if (lastKnownRotation == -1) { lastKnownRotation = rot; return }
        if (rot == lastKnownRotation) return
        lastKnownRotation = rot
        if (overlay == null) return // no active session
        if (rotationRestartArmed) return
        val src = activeCaptureSource
        if (src != CaptureSource.SHIZUKU && src != CaptureSource.ROOT) return
        if (benchmarkRequested) return
        if (overlayHiddenForBackground) {
            // Target is not in the foreground: the overlay is parked
            // off-screen, so rebuilding capture now is pointless and would
            // recreate the overlay visible on the desktop (black screen).
            // Defer the restart until the target returns to the foreground.
            rotationRestartDeferred = true
            LsfgLog.i(TAG, "display rotated while target backgrounded - deferring restart until it returns")
            return
        }
        rotationRestartArmed = true
        // Frozen capture + framegen overlay on top of the app's own
        // rotation animation reads as a visible stutter. Park the overlay
        // right away so the game's native frames show through during the
        // settle wait, then rebuild capture and resume frame generation.
        runCatching { drawer?.hide() }
        runCatching { overlay?.setWindowHidden(true) }
        LsfgLog.i(TAG, "display rotated during session - parking overlay, auto-restarting for new orientation")
        mainHandler.postDelayed({
            rotationRestartArmed = false
            if (shuttingDown || overlay == null) return@postDelayed
            performRotationRestart(src)
        }, rotationRestartDelayMs)
    }

    private fun performRotationRestart(src: CaptureSource) {
        val target = targetPkgPending ?: activeTargetPackage
        val fps = activeFpsCounter
        val appCtx = applicationContext
        val intent = if (src == CaptureSource.SHIZUKU) {
            buildShizukuStartIntent(appCtx, target, fps)
        } else {
            buildRootStartIntent(appCtx, target, fps)
        }
        // 先启动新会话——此时本服务仍是前台服务，FGS 启动被允许；先 stopSelf
        // 会让 app 落入后台，startForegroundService 被系统拒绝（日志
        // "rotation auto-restart failed"）。
        intent.putExtra(EXTRA_AUTO_RESTART, true)
        runCatching { appCtx.startForegroundService(intent) }
            .onFailure { LsfgLog.w(TAG, "rotation auto-restart failed", it) }
        val startIdAtRestart = lastStartId
        Handler(Looper.getMainLooper()).postDelayed({
            // stopSelfResult(startId)：若新 start 已被投递（startId 更大），
            // 这里是 no-op，绝不会误杀新会话；本实例已销毁则 shuttingDown
            // 为 true，直接跳过。
            if (!shuttingDown) stopSelfResult(startIdAtRestart)
        }, 300)
    }

    /**
     * Auto-stop without accessibility: poll whether the target app still has a
     * live process. ROOT mode probes via libsu pidof, SHIZUKU mode via the
     * shell-uid user service; unavailable in plain MediaProjection mode.
     */
    private fun startTargetAlivePoller(target: String) {
        stopTargetAlivePoller()
        val check: ((String) -> Boolean?)? = when {
            rootCapture != null -> { pkg -> rootCapture?.isTargetProcessAlive(pkg) }
            shizukuCapture != null -> { pkg -> shizukuCapture?.isTargetProcessAlive(pkg) }
            else -> null
        }
        val foreground: (() -> String?)? = when {
            rootCapture != null -> { -> rootCapture?.getForegroundPackage() }
            shizukuCapture != null -> { -> shizukuCapture?.getForegroundPackage() }
            else -> null
        }
        if (check == null) {
            LsfgLog.i(TAG, "auto-stop: no privileged backend to probe target process - disabled")
            return
        }
        if (foreground == null) {
            LsfgLog.i(TAG, "target-only overlay: no privileged backend to probe foreground app - disabled")
        }
        val r = object : Runnable {
            override fun run() {
                if (shuttingDown) return
                val alive = runCatching { check(target) }.getOrNull()
                if (alive == false) {
                    LsfgLog.i(TAG, "target process $target exited - auto-stopping session")
                    stopSelf()
                    return
                }
                // Overlay is only visible while the target app is in the
                // foreground; hidden (window parked off-screen) otherwise.
                if (foreground != null) {
                    when (runCatching { foreground() }.getOrNull()) {
                        target -> setOverlayForTarget(true)
                        null -> Unit // probe failed - keep the current state
                        else -> setOverlayForTarget(false)
                    }
                }
                mainHandler.postDelayed(this, TARGET_ALIVE_POLL_MS)
            }
        }
        mainHandler.postDelayed(r, TARGET_ALIVE_POLL_MS)
        targetAlivePoller = r
    }

    /**
     * Show/hide the frame-gen overlay + drawer depending on whether the
     * target app is in the foreground. Idempotent.
     */
    private fun setOverlayForTarget(visible: Boolean) {
        if (overlayHiddenForBackground == !visible) return
        overlayHiddenForBackground = !visible
        // While parked the launcher dot may show so the user can switch
        // targets instead of waiting out the auto-stop grace.
        runCatching {
            AutoOverlayController.onSessionOverlayParked(applicationContext, !visible)
        }
        mainHandler.post {
            if (shuttingDown) return@post
            if (visible) {
                if (rotationRestartDeferred) {
                    rotationRestartDeferred = false
                    LsfgLog.i(TAG, "target back in foreground - performing deferred rotation restart")
                    val src = activeCaptureSource ?: return@post
                    rotationRestartArmed = true
                    mainHandler.postDelayed({
                        rotationRestartArmed = false
                        if (shuttingDown || overlay == null) return@postDelayed
                        performRotationRestart(src)
                    }, rotationRestartDelayMs)
                    return@post
                }
                LsfgLog.i(TAG, "target in foreground - showing overlay")
                runCatching { drawer?.show() }
                runCatching { overlay?.setWindowHidden(false) }
                runCatching { overlay?.bringToFront() }
                runCatching { drawer?.bringToFront() }
            } else {
                LsfgLog.i(TAG, "target left foreground - hiding overlay")
                runCatching { overlay?.setWindowHidden(true) }
                runCatching { drawer?.hide() }
            }
        }
    }

    private fun stopTargetAlivePoller() {
        targetAlivePoller?.let { mainHandler.removeCallbacks(it) }
        targetAlivePoller = null
        stopTouchPausePoller()
    }

    private fun startTouchPausePoller() {
        stopTouchPausePoller()
        // MediaProjection mode: no privileged backend to stream getevent
        // from (the app process lacks the input group). Fall back to a 1px
        // watch overlay that receives ACTION_OUTSIDE on every touch down.
        if (activeCaptureSource == CaptureSource.MEDIA_PROJECTION) {
            startTouchWatchWindow()
            return
        }
        val thread = HandlerThread("lsfg-touch-pause").also { it.start() }
        touchPauseThread = thread
        touchPauseHandler = Handler(thread.looper)
        val r = object : Runnable {
            override fun run() {
                if (shuttingDown) return
                pollTouchPause()
                touchPauseHandler?.postDelayed(this, TOUCH_PAUSE_POLL_MS)
            }
        }
        touchPausePoller = r
        touchPauseHandler?.postDelayed(r, TOUCH_PAUSE_POLL_MS)
    }

    private fun stopTouchPausePoller() {
        touchPausePoller?.let { touchPauseHandler?.removeCallbacks(it) }
        touchPausePoller = null
        touchPauseHandler = null
        touchPauseThread?.quitSafely()
        touchPauseThread = null
    }

    private fun startTouchWatchWindow() {
        if (touchWatchView != null) return
        runCatching {
            val wm = getSystemService(WindowManager::class.java) ?: return
            val view = View(this)
            view.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onWatchOutsideDown()
                false
            }
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            wm.addView(view, lp)
            touchWatchView = view
            LsfgLog.i(TAG, "touch watch window attached (MediaProjection mode)")
        }.onFailure { LsfgLog.w(TAG, "touch watch window attach failed", it) }
    }

    private fun stopTouchWatchWindow() {
        touchWatchResume?.let { mainHandler.removeCallbacks(it) }
        touchWatchResume = null
        touchWatchView?.let { v ->
            runCatching { getSystemService(WindowManager::class.java)?.removeView(v) }
        }
        touchWatchView = null
    }

    /** Touch DOWN anywhere on screen (ACTION_OUTSIDE). Parks immediately;
     * lift is not observable, so resume after a heuristic grace timer that
     * is extended by every new down (rapid taps keep it parked). */
    private fun onWatchOutsideDown() {
        mainHandler.post {
            if (shuttingDown) return@post
            val enabled = runCatching { LsfgPreferences(applicationContext).load().pauseOnTouch }
                .getOrDefault(false)
            if (!enabled) return@post
            // Same overlay-UI exclusion as pollTouchPause: touches on the
            // drawer / launcher dot must not hide the panel mid-tap.
            if (drawer?.isExpanded() == true ||
                SettingsDrawerOverlay.uiTouchFresh(OVERLAY_UI_TOUCH_GRACE_MS)
            ) {
                if (touchPauseArmed) restoreAfterTouchPause()
                return@post
            }
            if (!touchPauseArmed) {
                touchPauseArmed = true
                LsfgLog.i(TAG, "touch down - parking framegen overlay for direct touch response")
                runCatching { drawer?.hide() }
                runCatching { overlay?.setWindowHidden(true) }
            }
        }
        touchWatchResume?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (shuttingDown) return@Runnable
            touchWatchResume = null
            restoreAfterTouchPause()
        }
        touchWatchResume = r
        mainHandler.postDelayed(r, TOUCH_WATCH_RESUME_MS)
    }

    private fun isTouchDownNow(): Boolean? = when {
        rootCapture != null -> rootCapture?.isTouchDown()
        shizukuCapture != null -> shizukuCapture?.isTouchDown()
        else -> null
    }

    private fun pollTouchPause() {
        val src = activeCaptureSource
        if (src != CaptureSource.SHIZUKU && src != CaptureSource.ROOT) return
        val enabled = runCatching { LsfgPreferences(applicationContext).load().pauseOnTouch }
            .getOrDefault(false)
        if (!enabled) {
            if (touchPauseArmed) restoreAfterTouchPause()
            return
        }
        // Touches on our own overlay chrome (expanded drawer panel, drawer
        // strip, launcher dot) belong to the UI, not the game: never park,
        // and un-park if a park is still pending so drawer clicks always land.
        if (drawer?.isExpanded() == true ||
            SettingsDrawerOverlay.uiTouchFresh(OVERLAY_UI_TOUCH_GRACE_MS)
        ) {
            if (touchPauseArmed) restoreAfterTouchPause()
            return
        }
        val down = isTouchDownNow() ?: false
        if (down && !touchPauseArmed) {
            touchPauseArmed = true
            touchResumePending = false
            mainHandler.post {
                if (shuttingDown) return@post
                LsfgLog.i(TAG, "touch down - parking framegen overlay for direct touch response")
                runCatching { drawer?.hide() }
                runCatching { overlay?.setWindowHidden(true) }
            }
        } else if (!down && touchPauseArmed && !touchResumePending) {
            touchResumePending = true
            mainHandler.postDelayed({
                touchResumePending = false
                if (shuttingDown) return@postDelayed
                // Finger may have touched down again during the grace window.
                if (isTouchDownNow() == true) return@postDelayed
                restoreAfterTouchPause()
            }, TOUCH_RESUME_DELAY_MS)
        }
    }

    private fun restoreAfterTouchPause() {
        if (!touchPauseArmed) return
        touchPauseArmed = false
        mainHandler.post {
            if (shuttingDown) return@post
            LsfgLog.i(TAG, "touch released - restoring framegen overlay")
            if (!overlayHiddenForBackground) {
                runCatching { drawer?.show() }
                runCatching { overlay?.setWindowHidden(false) }
                runCatching { overlay?.bringToFront() }
                // Overlay just re-fronted itself; keep the drawer above it or
                // the ball/panel stay invisible until some other event
                // re-adds the drawer window.
                runCatching { drawer?.bringToFront() }
            }
        }
    }

    /**
     * Auto-stop: when the target app leaves the foreground (user exited the
     * game), schedule a delayed session stop. Coming back to the target within
     * the delay cancels it, so brief app-switches don't kill the session.
     */
    private fun handleSessionForegroundChange(pkg: String) {
        mainHandler.post {
            if (shuttingDown) return@post
            val target = targetPkgPending ?: activeTargetPackage
                ?: LsfgPreferences(this).load().targetPackage
                ?: return@post
            if (pkg == target) {
                if (autoStopPending != null) {
                    LsfgLog.i(TAG, "target back in foreground - auto-stop cancelled")
                }
                autoStopPending?.let { mainHandler.removeCallbacks(it) }
                autoStopPending = null
            } else {
                // Switched straight to another auto-start target app: end the
                // session at once so the launcher dot can take over. Holding
                // the grace timer here only delays the dot (it is suppressed
                // while a session is active).
                val autoApps = runCatching {
                    LsfgPreferences(applicationContext).load().autoEnabledApps
                }.getOrDefault(emptySet())
                if (pkg in autoApps) {
                    LsfgLog.i(TAG, "switched to auto-start app $pkg - stopping session immediately")
                    stopSelf()
                    return@post
                }
                // The UID-filtered capture turns black the moment the target
                // leaves the foreground - hide the overlay now instead of
                // waiting for the next alive-poll tick (black screen).
                setOverlayForTarget(false)
                if (autoStopPending == null) {
                LsfgLog.i(TAG, "target $target left foreground (now $pkg) - auto-stop in ${AUTO_STOP_DELAY_MS / 1000}s")
                val r = Runnable {
                    autoStopPending = null
                    if (shuttingDown) return@Runnable
                    LsfgLog.i(TAG, "target still away - auto-stopping session")
                    stopSelf()
                }
                autoStopPending = r
                mainHandler.postDelayed(r, AUTO_STOP_DELAY_MS)
                }
            }
        }
    }

    // ---- source fps guard（90/120 档自动关闭）----
    @Volatile private var fpsGuardCancelled = false
    private var fpsGuardRunnable: Runnable? = null

    private fun startSourceFpsGuard() {
        if (activeCaptureSource == CaptureSource.MEDIA_PROJECTION) {
            LsfgLog.i(TAG, "fps guard skipped (MediaProjection capture includes overlay frames)")
            return
        }
        val guardPrefs = runCatching { LsfgPreferences(applicationContext).load() }.getOrNull()
        val enabled = guardPrefs?.fpsGuardEnabled ?: true
        if (!enabled) {
            LsfgLog.i(TAG, "fps guard disabled by user pref")
            return
        }
        val threshold = (guardPrefs?.fpsGuardThreshold ?: 62).coerceIn(30, 240)
        fpsGuardCancelled = false
        val h = mainHandler
        LsfgLog.i(TAG, "fps guard armed (threshold=%d fps, warm-up 2s)".format(threshold))
        val guard = object : Runnable {
            var lastCount = -1L
            var windowStartMs = 0L
            var hits = 0
            var windows = 0
            override fun run() {
                if (fpsGuardCancelled) return
                val now = android.os.SystemClock.elapsedRealtime()
                val c = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(-1L)
                if (lastCount >= 0 && c >= 0 && windowStartMs > 0) {
                    val dt = now - windowStartMs
                    if (dt >= 1000L) {
                        val fps = (c - lastCount).coerceAtLeast(0L) * 1000f / dt
                        lastCount = c
                        windowStartMs = now
                        windows++
                        val above = fps > threshold
                        LsfgLog.i(TAG, "fps guard sample %.1f fps (above=%s, window %d)".format(fps, above, windows))
                        hits = if (above) hits + 1 else 0
                        if (hits >= 2) {
                            LsfgLog.i(TAG, "fps guard triggered at ~%.1f fps — stopping session".format(fps))
                            fpsGuardRunnable = null
                            Toast.makeText(
                                applicationContext,
                                getString(R.string.fps_guard_toast, fps.toInt()),
                                Toast.LENGTH_LONG,
                            ).show()
                            stopSelf()
                            return
                        }
                        if (windows >= 10) {
                            LsfgLog.i(TAG, "fps guard: no sustained 90/120 band after %d windows — disarmed".format(windows))
                            fpsGuardRunnable = null
                            return
                        }
                    }
                } else {
                    lastCount = c
                    windowStartMs = now
                }
                h.postDelayed(this, 250L)
            }
        }
        fpsGuardRunnable = guard
        h.postDelayed(guard, 2000L)
    }

    private fun cancelSourceFpsGuard() {
        fpsGuardCancelled = true
        fpsGuardRunnable?.let { mainHandler.removeCallbacks(it) }
        fpsGuardRunnable = null
    }

    // ---- output watchdog（HDR 色彩切换把 overlay 踢出合成时优雅收场）----
    @Volatile private var outputWatchdogCancelled = false
    private var outputWatchdogRunnable: Runnable? = null

    private fun startOutputWatchdog() {
        outputWatchdogCancelled = false
        val h = mainHandler
        val watchdog = object : Runnable {
            var reported = false
            override fun run() {
                if (outputWatchdogCancelled) return
                val stalled = runCatching { NativeBridge.isOutputStalled() }.getOrDefault(false)
                if (stalled) {
                    if (!reported) {
                        reported = true
                        LsfgLog.w(TAG, "output watchdog: overlay stalled after repeated swapchain recreates - ending session")
                        Toast.makeText(
                            applicationContext,
                            getString(R.string.output_stalled_toast),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    outputWatchdogRunnable = null
                    stopSelf()
                    return
                }
                h.postDelayed(this, 500L)
            }
        }
        outputWatchdogRunnable = watchdog
        h.postDelayed(watchdog, 500L)
    }

    private fun cancelOutputWatchdog() {
        outputWatchdogCancelled = true
        outputWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        outputWatchdogRunnable = null
    }

    // ---- HDR guard（检测到 HDR 内容 → 自动结束会话）----
    // HDR 视频会让 SurfaceFlinger 把 SDR overlay 踢出合成（输出停滞死锁的根源），
    // 悬浮窗方案无法在 HDR 内容上存活，检测到即收场。
    @Volatile private var hdrGuardCancelled = false
    private var hdrGuardThread: Thread? = null

    private fun startHdrGuard() {
        if (activeCaptureSource == CaptureSource.MEDIA_PROJECTION) {
            LsfgLog.i(TAG, "hdr guard skipped (no privileged shell in MediaProjection mode)")
            return
        }
        hdrGuardCancelled = false
        LsfgLog.i(TAG, "hdr guard armed (stop on HDR)")
        val t = Thread {
            var hits = 0
            while (!hdrGuardCancelled) {
                val hdr = when {
                    rootCapture != null -> runCatching { rootCapture?.isHdrContentActive() }.getOrDefault(false)
                    shizukuCapture != null -> runCatching { shizukuCapture?.isHdrContentActive() }.getOrDefault(false)
                    else -> false
                } == true
                if (hdr) {
                    hits++
                    if (hits >= 2) {
                        LsfgLog.i(TAG, "hdr guard: HDR content detected — stopping session")
                        mainHandler.post {
                            if (hdrGuardCancelled) return@post
                            Toast.makeText(
                                applicationContext,
                                getString(R.string.hdr_guard_toast),
                                Toast.LENGTH_LONG,
                            ).show()
                            stopSelf()
                        }
                        return@Thread
                    }
                } else {
                    hits = 0
                }
                try {
                    Thread.sleep(1500L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            name = "lsfg-hdr-guard"
            isDaemon = true
            start()
        }
        hdrGuardThread = t
    }

    private fun cancelHdrGuard() {
        hdrGuardCancelled = true
        hdrGuardThread?.interrupt()
        hdrGuardThread = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LsfgLog.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                LsfgLog.i(TAG, "ACTION_STOP received — stopSelf()")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LsfgLog.i(TAG, "onDestroy — tearing down service (caller triggered stopSelf or system killed us)")
        super.onDestroy()
        AutoOverlayController.setSessionWatcher(null)
        autoStopPending?.let { mainHandler.removeCallbacks(it) }
        autoStopPending = null
        overlayHiddenForBackground = false
        rotationRestartDeferred = false
        activeSessionTarget = null
        stopTargetAlivePoller()
        unregisterDisplayListener()
        // Block any further reinit requests and wait for one already in flight
        // to finish. Without this, a parameter change happening concurrently
        // with stopSelf() races destroyContext() on the C++ side: the reinit
        // worker is in the middle of initRenderLoop(), allocating AHB images
        // and starting the worker thread, while onDestroy calls
        // shutdownRenderLoop() which joins that same worker and frees the
        // images — SIGSEGV on the next access. This was the "stop overlay
        // crashes when settings stop applying" symptom.
        shuttingDown = true
        cancelSourceFpsGuard()
        cancelOutputWatchdog()
        cancelHdrGuard()
        stopBenchmarkProgressPoller()
        // Wait on the latch instead of polling — frees the Main thread looper
        // to deliver pending input events instead of sleeping in 20 ms ticks.
        val latch = reinitDoneLatch
        if (latch != null && reinitInFlight) {
            val finished = latch.await(1500, TimeUnit.MILLISECONDS)
            if (!finished) {
                LsfgLog.w(TAG, "onDestroy: reinit still in flight after 1.5s — proceeding anyway")
            }
        }
        // Every step below must run even if a previous one throws — an aborted
        // onDestroy leaves the overlay/drawer windows attached as touch-eating
        // zombies after the session is gone (2026-09-07 19:18 lockup).
        runCatching { capture?.stop() }
            .onFailure { LsfgLog.w(TAG, "capture.stop() threw during teardown", it) }
        capture = null
        runCatching { shizukuCapture?.stop() }
            .onFailure { LsfgLog.w(TAG, "shizukuCapture.stop() threw during teardown", it) }
        shizukuCapture = null
        runCatching { rootCapture?.stop() }
            .onFailure { LsfgLog.w(TAG, "rootCapture.stop() threw during teardown", it) }
        rootCapture = null
        runCatching { NativeBridge.setShizukuTimingEnabled(false) }
            .onFailure { LsfgLog.w(TAG, "setShizukuTimingEnabled(false) failed during teardown", it) }
        if (lsfgContextActive) {
            runCatching { NativeBridge.setOutputSurface(null, 0, 0) }
            synchronized(nativeInitLock) {
                runCatching { NativeBridge.destroyContext() }
            }
            lsfgContextActive = false
        }
        runCatching { drawer?.hide() }
            .onFailure { LsfgLog.w(TAG, "drawer.hide() threw during teardown", it) }
        drawer = null
        runCatching { overlay?.hide() }
            .onFailure { LsfgLog.w(TAG, "overlay.hide() threw during teardown", it) }
        overlay = null
        projection?.stop()
        projection = null
        // The session is gone — the launcher dot may want to re-appear if the
        // target app is still in the foreground.
        AutoOverlayController.onSessionStopped(applicationContext)
    }

    private fun handleStart(intent: Intent) {
        // Rotation auto-restart delivers its queued START while this same
        // instance may still be running the previous session. A second live
        // handleStart pass would leak the first overlay/drawer pair as a
        // "ghost" overlay whose stop button belongs to a dead session.
        // Tear the previous session's UI/capture state down first.
        // NOTE: the native render loop is intentionally left alive here — the
        // new session's init will hit kRenderLoopAlreadyInit (-40) and the
        // existing self-heal (destroy + retry) takes over, which is safe
        // because -40 can only be returned once the previous init finished.
        if (overlay != null || drawer != null || capture != null ||
            shizukuCapture != null || rootCapture != null) {
            LsfgLog.i(TAG, "handleStart: previous session still active — tearing it down first")
            stopTargetAlivePoller()
            stopBenchmarkProgressPoller()
            capture?.stop()
            capture = null
            shizukuCapture?.stop()
            shizukuCapture = null
            rootCapture?.stop()
            rootCapture = null
            drawer?.hide()
            drawer = null
            overlay?.hide()
            overlay = null
            projection?.stop()
            projection = null
        }
        sessionGeneration++
        val captureSource = CaptureSource.fromPref(intent.getStringExtra(EXTRA_CAPTURE_SOURCE))
        val isPrivilegedCapture = captureSource == CaptureSource.SHIZUKU || captureSource == CaptureSource.ROOT
        runCatching { NativeBridge.setShizukuTimingEnabled(isPrivilegedCapture) }
            .onFailure { LsfgLog.w(TAG, "setShizukuTimingEnabled($isPrivilegedCapture) failed", it) }
        val usesMediaProjectionVideo = captureSource == CaptureSource.MEDIA_PROJECTION
        // Pick the FGS type that matches what we'll actually do this session.
        // - MediaProjection capture → FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        //   requires a live projection token (handled below) and the matching
        //   uses-permission in the manifest.
        // - Shizuku capture → FOREGROUND_SERVICE_TYPE_SPECIAL_USE. Without
        //   this, Android 14+/15+ rejects startForeground with
        //   "Starting FGS with type mediaProjection ... requires CAPTURE_VIDEO_OUTPUT
        //   or android:project_media" because the manifest declares
        //   foregroundServiceType="mediaProjection|specialUse" and the system
        //   defaults to the first declared type when none is passed.
        val type = if (usesMediaProjectionVideo) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        startForeground(NOTIF_ID, buildNotification(), type)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val autoRestart = intent.getBooleanExtra(EXTRA_AUTO_RESTART, false)
        val initialFpsCounter = intent.getBooleanExtra(EXTRA_FPS_COUNTER, false)
        activeCaptureSource = captureSource
        activeFpsCounter = initialFpsCounter
        lastKnownRotation = currentRotation() ?: -1
        rotationRestartDeferred = false
        benchmarkRequested = intent.getBooleanExtra(EXTRA_BENCHMARK_REQUESTED, false)
        benchmarkStarted = false
        if (usesMediaProjectionVideo && (data == null || resultCode == 0)) {
            LsfgLog.e(TAG, "Missing MediaProjection result intent; stopping")
            stopSelf()
            return
        }

        val proj = if (usesMediaProjectionVideo) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mpm.getMediaProjection(resultCode, data!!).also {
                projection = it
                // Android 14 (API 34) requires a non-null Handler for registerCallback on
                // some OEMs (MediaTek/PowerVR devices observed revoking the projection
                // token instantly when callback handler is null). Register BEFORE any
                // VirtualDisplay is created so we also hear about system-initiated stops.
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        LsfgLog.i(TAG, "MediaProjection.onStop")
                        stopSelf()
                    }
                }, mainHandler)
            }
        } else {
            null
        }

        // A previous ACTION_START (dot retarget / auto restart) may have left a
        // fully-built overlay attached. Overwriting the field without hiding would
        // orphan that window: nobody syncs its geometry or touchable region anymore,
        // and teardown only removes the NEW instance. Always tear the old one down.
        runCatching { overlay?.hide() }
        runCatching { drawer?.hide() }
        drawer = null
        val ov = OverlayManager(this)
        overlay = ov
        targetPkgPending = targetPkg
        if (targetPkg != null) {
            activeTargetPackage = targetPkg
            activeSessionTarget = targetPkg
        }
        initialCaptureStarted = false

        // Tell the Automatic Overlay controller that the full session has started:
        // it hides the floating dot until we tear down again.
        AutoOverlayController.onSessionStarted(targetPkg)
        AutoOverlayController.setSessionWatcher { pkg -> handleSessionForegroundChange(pkg) }

        val cap = if (proj != null) CaptureEngine(this, proj) else null
        capture = cap
        val shizukuCap = if (captureSource == CaptureSource.SHIZUKU) {
            ShizukuCaptureEngine(this).also { engine ->
                engine.setErrorListener { msg ->
                    LsfgLog.w(TAG, msg)
                    ov.updateStatus(msg)
                }
            }
        } else null
        shizukuCapture = shizukuCap
        // Bind the probe service early (video capture starts ~500ms after the
        // first Surface, but launchTarget() runs before that and needs
        // isTargetProcessAlive() to skip relaunching an already-running game).
        shizukuCap?.bindForProbe()
        val rootCap = if (captureSource == CaptureSource.ROOT) {
            RootCaptureEngine(this).also { engine ->
                engine.setErrorListener { msg ->
                    LsfgLog.w(TAG, msg)
                    ov.updateStatus(msg)
                }
            }
        } else null
        rootCapture = rootCap

        // Auto-stop when the target app's process exits (no accessibility needed).
        val pollTarget = targetPkg ?: LsfgPreferences(this).load().targetPackage
        if (pollTarget != null) startTargetAlivePoller(pollTarget)
        startTouchPausePoller()
        // Sync the fresh overlay with the actual foreground state: the
        // hidden-flag may be stale from the torn-down session (e.g. rotation
        // restart while the target was backgrounded), which would otherwise
        // leave the new overlay stuck visible on the desktop.
        overlayHiddenForBackground = false
        if (pollTarget != null) {
            val fgNow = when {
                rootCap != null -> runCatching { rootCap.getForegroundPackage() }.getOrNull()
                shizukuCap != null -> runCatching { shizukuCap.getForegroundPackage() }.getOrNull()
                else -> null
            }
            if (fgNow != null && fgNow != pollTarget) setOverlayForTarget(false)
        }
        cap?.setFpsListener { captured, posted -> ov.updateFps(captured, posted) }
        cap?.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        shizukuCap?.setFpsListener { captured, posted -> ov.updateFps(captured, posted) }
        shizukuCap?.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        rootCap?.setFpsListener { captured, posted -> ov.updateFps(captured, posted) }
        rootCap?.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        // Apply persisted LSFG on/off preference before the first frame is captured. The drawer
        // toggle persists `lsfgEnabled`; we mirror that into the bypass path (lsfgEnabled=false ⇒
        // bypass=true ⇒ raw passthrough).
        val persistedLsfgEnabled = com.lsfg.android.prefs.LsfgPreferences(this).load().lsfgEnabled
        if (!persistedLsfgEnabled) {
            cap?.frameGenBypass = true
            runCatching { NativeBridge.setBypass(true) }
                .onFailure { LsfgLog.w(TAG, "initial setBypass failed", it) }
            ov.updateStatus("LSFG: bypass (raw capture)")
        }
        // NOTE: deferring the initial FPS-counter wiring until AFTER ov.show() —
        // setFpsVisible() is a no-op when ov.fpsView hasn't been created yet,
        // and the view is only created inside show().

        ov.onSurfaceReady { surface, w, h ->
            if (ov !== overlay) {
                LsfgLog.i(TAG, "ignoring surface-ready from a stale overlay instance")
                return@onSurfaceReady
            }
            // Android delivers surfaceCreated immediately followed by surfaceChanged
            // on the same Surface instance. Retargeting the VirtualDisplay onto the
            // identical Surface a second time stops frame delivery on PowerVR drivers
            // (the overlay freezes on the first frame). Coalesce duplicate ready
            // events when nothing actually changed.
            val sameAsLast = surface === lastSurface && w == lastSurfaceW && h == lastSurfaceH
            LsfgLog.i(TAG, "onSurfaceReady ${w}x${h} initial=$initialCaptureStarted sameAsLast=$sameAsLast")
            if (sameAsLast && initialCaptureStarted) {
                LsfgLog.i(TAG, "onSurfaceReady coalesced — identical Surface, skipping retarget")
                return@onSurfaceReady
            }
            lastSurface = surface
            lastSurfaceW = w
            lastSurfaceH = h
            // Always tell native about the latest output surface — even if the LSFG
            // context isn't (yet) active, this lets a future re-init pick it up.
            runCatching { NativeBridge.setOutputSurface(surface, w, h) }
                .onFailure { LsfgLog.w(TAG, "setOutputSurface failed", it) }

            if (!initialCaptureStarted) {
                initialCaptureStarted = true
                // CRITICAL ORDER (Android 14+ MediaTek/PowerVR):
                // getMediaProjection() opens a short window (~200 ms on some OEMs)
                // during which we MUST call createVirtualDisplay, or the system
                // revokes the projection with MediaProjection.onStop. We used to
                // run Vulkan init (100-200 ms) before the first createVirtualDisplay
                // and occasionally blew past that deadline. In MediaProjection
                // mode start capture first on the LSFG ImageReader so the token
                // is consumed immediately without ever mirroring the visible
                // overlay surface back into MediaProjection. On Orange Pi /
                // RK3588 Android builds, that mirror bootstrap can make the
                // framegen path capture its own overlay frames.
                if (cap != null) {
                    LsfgLog.i(TAG, "Starting ImageReader capture first to consume MediaProjection token")
                    cap.setLsfgNativeInputEnabled(false)
                    cap.setLsfgMode(w, h)
                }
                ov.updateStatus("LSFG: starting ${w}×${h}…")

                val cfg = LsfgPreferences(this).load()
                val cacheDir = File(filesDir, "spirv").absolutePath
                val pacing = PacingDefaults.forPreset(
                    cfg.pacingPreset,
                    PacingDefaults.Params(cfg.emaAlpha, cfg.outlierRatio, cfg.vsyncSlackMs, cfg.queueDepth),
                )
                fun callInitContext(): Int = runCatching {
                    NativeBridge.initContext(
                        cacheDir = cacheDir,
                        width = w,
                        height = h,
                        multiplier = cfg.multiplier,
                        flowScale = cfg.flowScale,
                        performance = cfg.performanceMode,
                        hdr = false,
                        antiArtifacts = cfg.antiArtifacts,
                        framegenFp16 = cfg.framegenFp16,
                        npuPostProcessing = cfg.npuPostProcessingEnabled,
                        npuPreset = cfg.npuPostProcessingPreset.nativeValue,
                        npuUpscaleFactor = cfg.npuUpscaleFactor,
                        npuAmount = cfg.npuAmount,
                        npuRadius = cfg.npuRadius,
                        npuThreshold = cfg.npuThreshold,
                        npuFp16 = cfg.npuFp16,
                        cpuPostProcessing = cfg.cpuPostProcessingEnabled,
                        cpuPreset = cfg.cpuPostProcessingPreset.nativeValue,
                        cpuStrength = cfg.cpuStrength,
                        cpuSaturation = cfg.cpuSaturation,
                        cpuVibrance = cfg.cpuVibrance,
                        cpuVignette = cfg.cpuVignette,
                        gpuPostProcessing = cfg.gpuPostProcessingEnabled,
                        gpuStage = cfg.gpuPostProcessingStage.nativeValue,
                        gpuMethod = cfg.gpuPostProcessingMethod.nativeValue,
                        gpuUpscaleFactor = cfg.gpuUpscaleFactor,
                        gpuSharpness = cfg.gpuSharpness,
                        gpuStrength = cfg.gpuStrength,
                        targetFpsCap = cfg.targetFpsCap,
                        emaAlpha = pacing.emaAlpha,
                        outlierRatio = pacing.outlierRatio,
                        vsyncSlackMs = pacing.vsyncSlackMs,
                        queueDepth = pacing.queueDepth,
                    )
                }.getOrElse { e ->
                    LsfgLog.w(TAG, "initContext threw", e)
                    -1
                }
                var rc = synchronized(nativeInitLock) { callInitContext() }
                if (rc == -40) {
                    // -40 = kRenderLoopAlreadyInit：上一会话的原生渲染循环因 stop/start
                    // 竞态未被销毁。恢复：暂停输入 → destroyContext → 重试一次。
                    LsfgLog.i(TAG, "initContext rc=-40 (stale loop) — destroying and retrying")
                    runCatching { cap?.pauseLsfgInput() }
                    runCatching { shizukuCapture?.pauseCapture() }
                    runCatching { rootCapture?.pauseCapture() }
                    synchronized(nativeInitLock) {
                        runCatching { NativeBridge.destroyContext() }
                        lsfgContextActive = false
                        rc = callInitContext()
                    }
                }
                when {
                    rc == 0 -> {
                        // Framegen active: the ImageReader path is already running;
                        // now allow frames into the native render loop.
                        lsfgContextActive = true
                        activeRenderW = w
                        activeRenderH = h
                        cap?.setLsfgNativeInputEnabled(true)
                        if (isPrivilegedCapture) {
                            pendingPrivilegedVideoStart = ShizukuVideoStart(w, h, cfg)
                        }
                        ov.updateStatus("LSFG: frame-gen active ${w}×${h} ×${cfg.multiplier}")
                        startSourceFpsGuard()
                        startOutputWatchdog()
                        startHdrGuard()
                        // If the user launched this session via the Benchmark
                        // screen, hand control to BenchmarkController now that
                        // framegen has reached steady state. The controller runs
                        // on its own thread; we resume normal service handling.
                        maybeStartBenchmark()
                    }
                    rc > 0 -> {
                        LsfgLog.w(TAG, "initContext rc=$rc — framegen disabled, staying in mirror mode")
                        lsfgContextActive = true
                        if (isPrivilegedCapture) {
                            activeRenderW = 0
                            activeRenderH = 0
                            pendingPrivilegedVideoStart = ShizukuVideoStart(w, h, cfg)
                            val label = if (captureSource == CaptureSource.ROOT) "Root" else "Shizuku"
                            ov.updateStatus("LSFG: $label mirror ${w}×${h} (GPU lacks required Vulkan ext)")
                        } else if (cap != null) {
                            cap.setSurface(surface, w, h)
                            activeRenderW = 0
                            activeRenderH = 0
                            // Retarget the bootstrap ImageReader capture to mirror
                            // mode because framegen is unavailable.
                            ov.updateStatus("LSFG: mirror ${w}×${h} (GPU lacks required Vulkan ext)")
                        } else {
                            activeRenderW = 0
                            activeRenderH = 0
                            ov.updateStatus("LSFG: frame-gen unavailable (init rc=$rc)")
                        }
                    }
                    else -> {
                        LsfgLog.w(TAG, "initContext failed rc=$rc — staying in mirror mode")
                        activeRenderW = 0
                        activeRenderH = 0
                        if (isPrivilegedCapture && rc > 0) {
                            pendingPrivilegedVideoStart = ShizukuVideoStart(w, h, cfg)
                            val label = if (captureSource == CaptureSource.ROOT) "Root" else "Shizuku"
                            ov.updateStatus("LSFG: $label mirror active ${w}×${h} (init rc=$rc)")
                        } else if (cap != null) {
                            ov.updateStatus("LSFG: mirror active ${w}×${h} (init rc=$rc)")
                        } else {
                            ov.updateStatus("LSFG: init failed (rc=$rc)")
                        }
                    }
                }
                // Launch the target only after the first valid Surface exists so the
                // VirtualDisplay has somewhere to draw from frame #1.
                val pkg = targetPkgPending
                if (autoRestart) {
                    LsfgLog.i(TAG, "auto-restart: skipping target launch (pkg=$pkg)")
                    // Still start the privileged capture — only the app launch
                    // is skipped, otherwise the restarted session has no input
                    // frames and frame generation silently dies.
                    pendingPrivilegedVideoStart?.let { start ->
                        pendingPrivilegedVideoStart = null
                        if (pkg != null) {
                            val gen = sessionGeneration
                            mainHandler.postDelayed({
                                if (gen != sessionGeneration) return@postDelayed
                                startShizukuVideo(shizukuCap, pkg, start.width, start.height, start.cfg)
                                startRootVideo(rootCap, pkg, start.width, start.height, start.cfg)
                            }, 500L)
                        }
                    }
                } else if (pkg != null) {
                    LsfgLog.i(TAG, "About to launch target: pkg=$pkg")
                    targetPkgPending = null
                    launchTarget(pkg)
                    pendingPrivilegedVideoStart?.let { start ->
                        pendingPrivilegedVideoStart = null
                        val gen = sessionGeneration
                        mainHandler.postDelayed({
                            if (gen != sessionGeneration) return@postDelayed
                            startShizukuVideo(shizukuCap, pkg, start.width, start.height, start.cfg)
                            startRootVideo(rootCap, pkg, start.width, start.height, start.cfg)
                        }, 500L)
                    }
                    // Re-assert the overlay on top a few times to win a potential race
                    // with the target activity's first-paint z-order assignment.
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 150)
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 600)
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 1500)
                } else {
                    LsfgLog.w(TAG, "No target package set — overlay will show but no app is launched")
                }
                // FPS counter no longer creates a VirtualDisplay; it piggybacks on
                // the main LSFG-mode ImageReader. Safe to start immediately.
                if (pendingFpsCounter) {
                    pendingFpsCounter = false
                    runCatching {
                        when {
                            captureSource == CaptureSource.SHIZUKU -> shizukuCap?.startFpsCounter()
                            captureSource == CaptureSource.ROOT -> rootCap?.startFpsCounter()
                            else -> cap?.startFpsCounter()
                        }
                    }.onFailure { LsfgLog.w(TAG, "startFpsCounter failed", it) }
                }
            } else if (!lsfgContextActive || activeRenderW == 0) {
                // Mirror mode (framegen disabled or context not yet active): retarget
                // the existing VirtualDisplay onto the new Surface. activeRenderW==0
                // is our "running in mirror" sentinel — don't try to reinit the
                // render loop, just keep the capture alive.
                cap?.setSurface(surface, w, h)
            } else if (w != activeRenderW || h != activeRenderH) {
                if (rotationRestartArmed || rotationRestartDeferred) {
                    // Auto-restart will rebuild capture + engine for the new
                    // orientation; a full reinit here (~2.4s) would only produce
                    // another stale loop for the -40 self-heal to clean up.
                    LsfgLog.i(TAG, "Surface geometry changed ${activeRenderW}x${activeRenderH} -> ${w}x${h}; rotation restart pending — skipping LSFG reinit")
                } else {
                    LsfgLog.i(TAG, "Surface geometry changed ${activeRenderW}x${activeRenderH} -> ${w}x${h}; reinitializing LSFG context")
                    reinitLsfgContext(w, h)
                }
            }
        }
        ov.onSurfaceLost {
            if (ov !== overlay) {
                LsfgLog.i(TAG, "ignoring surface-lost from a stale overlay instance")
                return@onSurfaceLost
            }
            LsfgLog.i(TAG, "onSurfaceLost — detaching output until a new Surface arrives")
            lastSurface = null
            runCatching { NativeBridge.setOutputSurface(null, 0, 0) }
            if (!lsfgContextActive) {
                capture?.clearSurface()
            }
        }
        ov.show()

        // Now that ov.show() has actually created the FPS TextView, we can safely
        // make the UI visible. The actual counter (second VirtualDisplay) is
        // started AFTER the main capture is running — on Android 14 MediaTek/
        // PowerVR the system revokes MediaProjection if a second VirtualDisplay
        // is created while the first token is still unconsumed.
        //
        // When a benchmark session is starting we force the FPS counter ON
        // regardless of the user's last toggle state — the run is meaningless
        // without the live real/total readout, and the in-overlay benchmark
        // progress panel (shown below) is also revealed.
        val effectiveFpsCounter = initialFpsCounter || benchmarkRequested
        if (effectiveFpsCounter) {
            ov.setFpsVisible(true)
        }
        pendingFpsCounter = effectiveFpsCounter
        if (benchmarkRequested) {
            ov.setBenchmarkProgressVisible(true)
            ov.updateBenchmarkProgress("Benchmark — preparing", 0L, 1L)
        }

        // Frame pacing graph is purely a diagnostic overlay — no intent extra needed,
        // read the persisted pref directly so it restores across service restarts.
        val initialFrameGraph = LsfgPreferences(this).load().frameGraphEnabled
        if (initialFrameGraph) {
            ov.setFrameGraphVisible(true)
            when {
                captureSource == CaptureSource.SHIZUKU -> shizukuCapture?.startFrameGraph()
                captureSource == CaptureSource.ROOT -> rootCapture?.startFrameGraph()
                else -> capture?.startFrameGraph()
            }
        }

        // The main overlay and drawer both stay in TYPE_APPLICATION_OVERLAY so
        // the drawer/icon remains visible above the full-screen output surface.
        val overlayMode = LsfgPreferences(this).load().overlayMode
        val dr = SettingsDrawerOverlay(this, overlayMode)
        dr.setBypassListener { bypass ->
            if (bypass) {
                // Turning frame-gen OFF from the drawer ends the session
                // entirely: mirror passthrough pays the full capture +
                // overlay cost while adding nothing — ending is strictly
                // better for game FPS and battery.
                LsfgLog.i(TAG, "frameGen disabled from drawer - ending session (mirror passthrough has no benefit)")
                // The drawer switch is a per-session control; restore the
                // app-level default so the NEXT session starts with frame-gen
                // active instead of silently coming up in bypass mode.
                runCatching { LsfgPreferences(this).setLsfgEnabled(true) }
                runCatching { Toast.makeText(this, getString(R.string.toast_fg_off), Toast.LENGTH_SHORT).show() }
                stopSelf()
                return@setBypassListener
            }
            LsfgLog.i(TAG, "frameGenBypass=$bypass")
            capture?.frameGenBypass = bypass
            runCatching { NativeBridge.setBypass(bypass) }
                .onFailure { LsfgLog.w(TAG, "setBypass failed", it) }
            ov.updateStatus(if (bypass) "LSFG: bypass (raw capture)" else "LSFG: frame-gen active")
        }
        dr.setStopOverlayListener {
            LsfgLog.i(TAG, "Stop overlay requested from drawer")
            stopSelf()
        }
        dr.setFpsCounterListener { enabled ->
            LsfgLog.i(TAG, "fpsCounter=$enabled")
            if (enabled) {
                when {
                    captureSource == CaptureSource.SHIZUKU -> shizukuCapture?.startFpsCounter()
                    captureSource == CaptureSource.ROOT -> rootCapture?.startFpsCounter()
                    else -> capture?.startFpsCounter()
                }
                overlay?.setFpsVisible(true)
            } else {
                capture?.stopFpsCounter()
                shizukuCapture?.stopFpsCounter()
                rootCapture?.stopFpsCounter()
                overlay?.setFpsVisible(false)
            }
        }
        dr.setFrameGraphListener { enabled ->
            LsfgLog.i(TAG, "frameGraph=$enabled")
            if (enabled) {
                when {
                    captureSource == CaptureSource.SHIZUKU -> shizukuCapture?.startFrameGraph()
                    captureSource == CaptureSource.ROOT -> rootCapture?.startFrameGraph()
                    else -> capture?.startFrameGraph()
                }
                overlay?.setFrameGraphVisible(true)
            } else {
                capture?.stopFrameGraph()
                shizukuCapture?.stopFrameGraph()
                rootCapture?.stopFrameGraph()
                overlay?.setFrameGraphVisible(false)
            }
        }
        dr.setInitialFpsCounterState(effectiveFpsCounter)
        dr.setInitialFrameGraphState(initialFrameGraph)
        dr.setLiveParamsListener {
            reinitLsfgContext()
        }
        dr.show()
        drawer = dr
    }

    /**
     * Tear down and re-create the native LSFG context so a parameter change from
     * the live drawer (multiplier, flow scale, performance/HDR switch) actually
     * takes effect. The shaders and pipeline state are baked at initContext time;
     * there's no in-place update path.
     *
     * Runs on a worker thread because destroyContext blocks on vkDeviceWaitIdle
     * and initContext can take 100-300ms while it recompiles the shader chain.
     */
    private fun reinitLsfgContext(width: Int = lastSurfaceW, height: Int = lastSurfaceH) {
        if (shuttingDown) {
            LsfgLog.i(TAG, "reinitLsfgContext skipped — shutting down")
            return
        }
        val cap = capture
        val ov = overlay ?: return
        if (width == 0 || height == 0) {
            LsfgLog.w(TAG, "reinitLsfgContext skipped — no surface yet")
            return
        }
        // Coalesce concurrent requests: if a reinit is already running, just
        // mark that another one is wanted. The running worker will pick up the
        // newest prefs in a follow-up pass before clearing the in-flight flag.
        if (reinitInFlight) {
            pendingReinitW = width
            pendingReinitH = height
            LsfgLog.i(TAG, "reinitLsfgContext queued ${width}x${height} while another pass is running")
            reinitRequested = true
            return
        }
        reinitInFlight = true
        reinitRequested = false
        pendingReinitW = width
        pendingReinitH = height
        val doneLatch = CountDownLatch(1)
        reinitDoneLatch = doneLatch
        Thread {
            try {
            val cacheDir = File(filesDir, "spirv").absolutePath
            // Drain any pending requests that arrived while we were running.
            // Each pass re-reads prefs so the final native state matches the
            // most recent UI value, even if the user spammed slider releases.
            var pass = 0
            do {
                reinitRequested = false
                pass++
                val targetW = pendingReinitW.takeIf { it > 0 } ?: width
                val targetH = pendingReinitH.takeIf { it > 0 } ?: height
                pendingReinitW = 0
                pendingReinitH = 0
                val cfg = LsfgPreferences(this).load()
                LsfgLog.i(TAG, "Re-init LSFG context pass=$pass ${targetW}x${targetH} multiplier=${cfg.multiplier} flowScale=${cfg.flowScale} perf=${cfg.performanceMode}")

                if (lsfgContextActive) {
                    // Stop pushing new captures BEFORE we tear down the native
                    // context. shutdownRenderLoop() joins the C++ worker which
                    // can sit inside vkDeviceWaitIdle for tens of ms; if a new
                    // pushFrame arrives concurrently it can leave the framegen
                    // device with in-flight commands and the next waitIdle
                    // hangs forever (multi-second). Symptom from logs: re-init
                    // started but "Render loop shut down" never came.
                    runCatching { cap?.pauseLsfgInput() }
                        .onFailure { LsfgLog.w(TAG, "pauseLsfgInput failed", it) }
                    runCatching { shizukuCapture?.pauseCapture() }
                        .onFailure { LsfgLog.w(TAG, "pause Shizuku capture failed", it) }
                    runCatching { rootCapture?.pauseCapture() }
                        .onFailure { LsfgLog.w(TAG, "pause Root capture failed", it) }
                    synchronized(nativeInitLock) {
                        runCatching { NativeBridge.destroyContext() }
                        lsfgContextActive = false
                    }
                }
                val pacing = PacingDefaults.forPreset(
                    cfg.pacingPreset,
                    PacingDefaults.Params(cfg.emaAlpha, cfg.outlierRatio, cfg.vsyncSlackMs, cfg.queueDepth),
                )
                fun callInitContext(): Int = runCatching {
                    NativeBridge.initContext(
                        cacheDir = cacheDir,
                        width = targetW,
                        height = targetH,
                        multiplier = cfg.multiplier,
                        flowScale = cfg.flowScale,
                        performance = cfg.performanceMode,
                        hdr = false,
                        antiArtifacts = cfg.antiArtifacts,
                        framegenFp16 = cfg.framegenFp16,
                        npuPostProcessing = cfg.npuPostProcessingEnabled,
                        npuPreset = cfg.npuPostProcessingPreset.nativeValue,
                        npuUpscaleFactor = cfg.npuUpscaleFactor,
                        npuAmount = cfg.npuAmount,
                        npuRadius = cfg.npuRadius,
                        npuThreshold = cfg.npuThreshold,
                        npuFp16 = cfg.npuFp16,
                        cpuPostProcessing = cfg.cpuPostProcessingEnabled,
                        cpuPreset = cfg.cpuPostProcessingPreset.nativeValue,
                        cpuStrength = cfg.cpuStrength,
                        cpuSaturation = cfg.cpuSaturation,
                        cpuVibrance = cfg.cpuVibrance,
                        cpuVignette = cfg.cpuVignette,
                        gpuPostProcessing = cfg.gpuPostProcessingEnabled,
                        gpuStage = cfg.gpuPostProcessingStage.nativeValue,
                        gpuMethod = cfg.gpuPostProcessingMethod.nativeValue,
                        gpuUpscaleFactor = cfg.gpuUpscaleFactor,
                        gpuSharpness = cfg.gpuSharpness,
                        gpuStrength = cfg.gpuStrength,
                        targetFpsCap = cfg.targetFpsCap,
                        emaAlpha = pacing.emaAlpha,
                        outlierRatio = pacing.outlierRatio,
                        vsyncSlackMs = pacing.vsyncSlackMs,
                        queueDepth = pacing.queueDepth,
                    )
                }.getOrElse { -1 }
                var rc = synchronized(nativeInitLock) { callInitContext() }
                if (rc == -40) {
                    // 泄漏的原生循环仍在（例如本会话曾以镜像模式运行，flag 为 false
                    // 时上方跳过了 destroyContext）。销毁后重试一次。
                    LsfgLog.i(TAG, "reinit rc=-40 (stale loop) — destroying and retrying")
                    synchronized(nativeInitLock) {
                        runCatching { NativeBridge.destroyContext() }
                        rc = callInitContext()
                    }
                }
                if (rc == 0 || rc > 0) {
                    lsfgContextActive = true
                    // CRITICAL: destroyContext() above released the native ANativeWindow
                    // handle, so initContext() came up with no output surface attached.
                    // Without this re-attach, blitOutputToWindow() short-circuits and
                    // the overlay freezes on whatever was last posted.
                    val surface = lastSurface
                    if (surface != null) {
                        runCatching { NativeBridge.setOutputSurface(surface, targetW, targetH) }
                            .onFailure { LsfgLog.w(TAG, "setOutputSurface (re-init) failed", it) }
                    } else {
                        LsfgLog.w(TAG, "reinit: no cached surface to re-attach")
                    }
                    if (rc == 0) {
                        activeRenderW = targetW
                        activeRenderH = targetH
                        cap?.setLsfgMode(targetW, targetH)
                        cap?.setLsfgNativeInputEnabled(true)
                        val reinitTarget = targetPkgPending ?: activeTargetPackage
                            ?: LsfgPreferences(this).load().targetPackage
                        startShizukuVideo(shizukuCapture, reinitTarget, targetW, targetH, cfg)
                        startRootVideo(rootCapture, reinitTarget, targetW, targetH, cfg)
                        mainHandler.post {
                            ov.updateStatus("LSFG: ${lastSurfaceW}×${lastSurfaceH} ×${cfg.multiplier} flow=${"%.2f".format(cfg.flowScale)}")
                        }
                    } else {
                        LsfgLog.w(TAG, "reinit rc=$rc — framegen disabled, staying in mirror mode")
                        activeRenderW = 0
                        activeRenderH = 0
                        if (surface != null) cap?.setSurface(surface, targetW, targetH)
                        val reinitTarget = targetPkgPending ?: activeTargetPackage
                            ?: LsfgPreferences(this).load().targetPackage
                        startShizukuVideo(shizukuCapture, reinitTarget, targetW, targetH, cfg)
                        startRootVideo(rootCapture, reinitTarget, targetW, targetH, cfg)
                        mainHandler.post {
                            if ((shizukuCapture != null || rootCapture != null) && cap != null) {
                                ov.updateStatus("LSFG: privileged capture unavailable for mirror fallback (frame-gen unavailable)")
                            } else if (cap != null) {
                                ov.updateStatus("LSFG: mirror ${width}×${height} (GPU lacks required Vulkan ext)")
                            } else {
                                ov.updateStatus("LSFG: frame-gen unavailable (init rc=$rc)")
                            }
                        }
                    }
                } else {
                    LsfgLog.w(TAG, "reinit failed rc=$rc")
                    activeRenderW = 0
                    activeRenderH = 0
                }
            } while (reinitRequested)
            } finally {
                reinitInFlight = false
                doneLatch.countDown()
            }
        }.start()
    }

    /**
     * Kicks the [BenchmarkController] off if this session was started with
     * EXTRA_BENCHMARK_REQUESTED. Idempotent — flips [benchmarkStarted] so
     * subsequent reinit completions don't re-trigger the controller.
     */
    private fun maybeStartBenchmark() {
        if (!benchmarkRequested || benchmarkStarted) return
        if (shuttingDown) return
        benchmarkStarted = true
        LsfgLog.i(TAG, "starting BenchmarkController")
        startBenchmarkProgressPoller()
        BenchmarkController.start(applicationContext, object : BenchmarkController.Hooks {
            override fun requestReinit() {
                // Mirror the live-drawer code path so the controller doesn't
                // need to know about reinit internals.
                mainHandler.post { reinitLsfgContext() }
            }
            override fun activeRenderWidth(): Int = activeRenderW
            override fun activeRenderHeight(): Int = activeRenderH
            override fun targetPackage(): String? =
                LsfgPreferences(this@LsfgForegroundService).load().targetPackage
            override fun vsyncPeriodNs(): Long {
                val hz = runCatching {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.refreshRate
                }.getOrNull() ?: return 0L
                if (hz <= 0f) return 0L
                return (1_000_000_000.0 / hz).toLong()
            }
            override fun onCompleted(state: BenchmarkController.State.Completed) {
                LsfgLog.i(TAG, "benchmark completed — report=${state.reportFile.absolutePath}")
                // Stash the report file for the UI to pick up.
                lastBenchmarkReport = state.reportFile
                mainHandler.post {
                    stopBenchmarkProgressPoller()
                    overlay?.setBenchmarkProgressVisible(false)
                    runCatching {
                        val intent = BenchmarkLogWriter.buildShareIntent(
                            this@LsfgForegroundService, state.reportFile,
                        )
                        val chooser = Intent.createChooser(
                            intent, "Share LSFG benchmark report",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(chooser)
                    }.onFailure {
                        LsfgLog.w(TAG, "failed to launch share chooser", it)
                    }
                    // The session is now done — tear it down.
                    stopSelf()
                }
            }
            override fun onFailed(state: BenchmarkController.State.Failed) {
                LsfgLog.w(TAG, "benchmark failed: ${state.message}")
                mainHandler.post {
                    stopBenchmarkProgressPoller()
                    overlay?.setBenchmarkProgressVisible(false)
                    stopSelf()
                }
            }
        })
    }

    /**
     * Drives the in-overlay benchmark progress panel by polling the
     * [BenchmarkController] state at 200 ms (the same cadence the Compose
     * BenchmarkScreen uses, so the two stay visually in sync). The poller
     * computes the elapsed/total fraction inside the current Running phase
     * (warmup or sampling) and pushes it to [OverlayManager.updateBenchmarkProgress].
     *
     * Each phase resets the bar to 0 and refills as the phase advances; the
     * label shows "Run x/y · <precision> ×<mult> — warming up | sampling" so
     * the user can see how much of the run is left without leaving the game.
     */
    private fun startBenchmarkProgressPoller() {
        if (benchmarkProgressPoller != null) return
        val poll = object : Runnable {
            override fun run() {
                val ov = overlay
                val st = BenchmarkController.currentState()
                if (ov == null) {
                    // Overlay torn down — exit the loop; teardown path will
                    // null the field below the next time we look.
                    benchmarkProgressPoller = null
                    return
                }
                when (st) {
                    is BenchmarkController.State.Running -> {
                        val elapsed = (System.currentTimeMillis() - st.phaseStartedAtMs)
                            .coerceAtLeast(0L)
                        val phase = when (st.phase) {
                            BenchmarkController.Phase.WARMUP -> "warming up"
                            BenchmarkController.Phase.SAMPLING -> "sampling"
                        }
                        val label = "Run ${st.runIndex + 1}/${st.totalRuns} · " +
                            "${st.precision.label} ×${st.multiplier} — $phase"
                        ov.updateBenchmarkProgress(label, elapsed, st.phaseDurationMs)
                        mainHandler.postDelayed(this, 200L)
                    }
                    is BenchmarkController.State.Completed,
                    is BenchmarkController.State.Failed -> {
                        // The controller's onCompleted/onFailed callbacks
                        // already handle hiding the panel and stopping the
                        // service — just exit the loop.
                        benchmarkProgressPoller = null
                    }
                    BenchmarkController.State.Idle -> {
                        // Controller hasn't published Running yet — keep
                        // the "preparing" label visible and check again.
                        mainHandler.postDelayed(this, 200L)
                    }
                }
            }
        }
        benchmarkProgressPoller = poll
        mainHandler.post(poll)
    }

    private fun stopBenchmarkProgressPoller() {
        val p = benchmarkProgressPoller
        benchmarkProgressPoller = null
        if (p != null) mainHandler.removeCallbacks(p)
    }

    private fun launchTarget(pkg: String) {
        LsfgLog.i(TAG, "launchTarget($pkg)")
        val gen = sessionGeneration
        decideTargetLaunch(pkg, probeTargetAlive(pkg), 0L, gen)
    }

    /**
     * Relaunching an already-running game goes through its launcher entry
     * again. fiveplay GTASA's RMS.Recovery wrapper recreates the Activity in
     * that path, mismatches the EGL surface and black-screens. Skip the launch
     * when the process is known alive; capture picks up frames as soon as the
     * existing game is foregrounded (recents switch is safe). A null probe
     * (e.g. Shizuku user service still binding on a fresh session) retries
     * briefly, then fails open so a probe bug can never block launching.
     */
    private fun decideTargetLaunch(pkg: String, alive: Boolean?, waitedMs: Long, gen: Int) {
        if (shuttingDown || gen != sessionGeneration) return
        when {
            alive == true -> {
                LsfgLog.i(TAG, "target $pkg already running - skipping launch (avoids launcher-entry relaunch)")
            }
            alive == null && activeCaptureSource == CaptureSource.SHIZUKU && waitedMs < 2000L -> {
                mainHandler.postDelayed(
                    { decideTargetLaunch(pkg, probeTargetAlive(pkg), waitedMs + 250, gen) },
                    250L,
                )
            }
            else -> {
                if (alive == null) {
                    LsfgLog.i(TAG, "target probe unavailable after " + waitedMs + "ms - launching $pkg (fail open)")
                }
                doLaunchTarget(pkg)
            }
        }
    }

    private fun probeTargetAlive(pkg: String): Boolean? = when {
        rootCapture != null -> runCatching { rootCapture?.isTargetProcessAlive(pkg) }.getOrNull()
        shizukuCapture != null -> runCatching { shizukuCapture?.isTargetProcessAlive(pkg) }.getOrNull()
        else -> null
    }

    private fun doLaunchTarget(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            LsfgLog.e(TAG, "No launch intent for $pkg")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
            .onSuccess { LsfgLog.i(TAG, "startActivity($pkg) returned") }
            .onFailure { LsfgLog.e(TAG, "Launching $pkg failed", it) }
    }

    private fun startShizukuMetrics(
        engine: ShizukuCaptureEngine?,
        targetPackage: String?,
        width: Int,
        height: Int,
        cfg: com.lsfg.android.prefs.LsfgConfig,
    ) {
        if (engine == null || targetPackage == null) return
        engine.startMetricsOnly(targetPackage, width, height, maxCaptureFps(cfg))
    }

    private fun startShizukuVideo(
        engine: ShizukuCaptureEngine?,
        targetPackage: String?,
        width: Int,
        height: Int,
        cfg: com.lsfg.android.prefs.LsfgConfig,
    ) {
        if (engine == null) return
        if (targetPackage == null) {
            LsfgLog.w(TAG, "startShizukuVideo skipped — no target package (capture would stay dead)")
            return
        }
        engine.startCapture(targetPackage, width, height, maxCaptureFps(cfg))
    }

    private fun startRootVideo(
        engine: RootCaptureEngine?,
        targetPackage: String?,
        width: Int,
        height: Int,
        cfg: com.lsfg.android.prefs.LsfgConfig,
    ) {
        if (engine == null) return
        if (targetPackage == null) {
            LsfgLog.w(TAG, "startRootVideo skipped — no target package (capture would stay dead)")
            return
        }
        engine.startCapture(targetPackage, width, height, maxCaptureFps(cfg))
    }

    private fun maxCaptureFps(cfg: com.lsfg.android.prefs.LsfgConfig): Int {
        val cap = cfg.targetFpsCap.takeIf { it > 0 }
        val refresh = cfg.vsyncRefreshOverride.hz.takeIf { it > 0 }
            ?: requestedOverlayRefreshHz()
        return (cap ?: refresh ?: 60).coerceIn(15, 120)
    }

    private fun requestedOverlayRefreshHz(): Int? {
        val surfaceW = lastSurfaceW
        if (surfaceW == 0) return null
        return runCatching {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.refreshRate.toInt()
        }.getOrNull()
    }

    private data class ShizukuVideoStart(
        val width: Int,
        val height: Int,
        val cfg: com.lsfg.android.prefs.LsfgConfig,
    )

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_session),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LsfgForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_session_title))
            .setContentText(getString(R.string.notif_session_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(stopPending)
            .build()
    }

    companion object {
        private const val TAG = "LsfgFGS"
        private const val CHANNEL_ID = "lsfg_session"
        private const val NOTIF_ID = 1001

        /** Last benchmark report file produced by a benchmark session. The
         *  Benchmark screen polls this so the user can re-share the file
         *  after dismissing the system chooser. Set by the BenchmarkController
         *  hook callback; cleared lazily by the UI when acknowledged. */
        @Volatile
        var lastBenchmarkReport: File? = null

        // Foreground target of the running session, mirrored to the static
        // scope so the always-on overlay watcher can match its probe result
        // against the session (null = no session running).
        @Volatile
        var activeSessionTarget: String? = null

        /** Stops the running session (called by the overlay watcher when the
         *  target leaves fullscreen, e.g. entered a freeform small window). */
        fun requestStopFromWatcher(ctx: Context) {
            if (activeSessionTarget == null) return
            runCatching {
                ctx.startService(Intent(ctx, LsfgForegroundService::class.java).setAction(ACTION_STOP))
            }.onFailure { LsfgLog.w(TAG, "watcher stop request failed", it) }
        }

        const val ACTION_START = "com.lsfg.android.action.START_SESSION"
        const val ACTION_STOP = "com.lsfg.android.action.STOP_SESSION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_FPS_COUNTER = "fps_counter"
        const val EXTRA_CAPTURE_SOURCE = "capture_source"
        // When true, after framegen reaches steady state the service hands
        // control to BenchmarkController which runs three back-to-back passes
        // (multiplier x2/x3/x4) and produces a shareable report. The session
        // is stopped automatically when the report is ready.
        const val EXTRA_BENCHMARK_REQUESTED = "benchmark_requested"
        // Set on rotation auto-restart intents: session must NOT re-launch
        // the target app (the user may have just left it for the desktop).
        const val EXTRA_AUTO_RESTART = "auto_restart"

        fun buildStartIntent(
            ctx: Context,
            resultCode: Int,
            resultData: Intent,
            targetPackage: String?,
            fpsCounter: Boolean,
            captureSource: CaptureSource = CaptureSource.MEDIA_PROJECTION,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, resultData)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, fpsCounter)
            .putExtra(EXTRA_CAPTURE_SOURCE, captureSource.prefValue)

        fun buildShizukuStartIntent(
            ctx: Context,
            targetPackage: String?,
            fpsCounter: Boolean,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, fpsCounter)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.SHIZUKU.prefValue)

        fun buildRootStartIntent(
            ctx: Context,
            targetPackage: String?,
            fpsCounter: Boolean,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, fpsCounter)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.ROOT.prefValue)

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, LsfgForegroundService::class.java).setAction(ACTION_STOP)
            )
        }

        /** MediaProjection variant that flips on EXTRA_BENCHMARK_REQUESTED. The
         *  caller still supplies a fresh consent token (resultCode/resultData)
         *  exactly like the normal path. */
        fun buildBenchmarkStartIntent(
            ctx: Context,
            resultCode: Int,
            resultData: Intent,
            targetPackage: String?,
            captureSource: CaptureSource = CaptureSource.MEDIA_PROJECTION,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, resultData)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, false)
            .putExtra(EXTRA_CAPTURE_SOURCE, captureSource.prefValue)
            .putExtra(EXTRA_BENCHMARK_REQUESTED, true)

        /** Shizuku-capture variant of the benchmark intent. No MediaProjection
         *  consent token is required — the privileged side channel handles
         *  capture without the system recording dialog. */
        fun buildShizukuBenchmarkStartIntent(
            ctx: Context,
            targetPackage: String?,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, false)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.SHIZUKU.prefValue)
            .putExtra(EXTRA_BENCHMARK_REQUESTED, true)

        /** Root-capture variant. Same shape as the Shizuku variant — no
         *  consent intent, capture happens through the rooted privileged
         *  pipeline. */
        fun buildRootBenchmarkStartIntent(
            ctx: Context,
            targetPackage: String?,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, false)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.ROOT.prefValue)
            .putExtra(EXTRA_BENCHMARK_REQUESTED, true)
    }
}
