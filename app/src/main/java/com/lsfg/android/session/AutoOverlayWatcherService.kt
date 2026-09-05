package com.lsfg.android.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.lsfg.android.R
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.topjohnwu.superuser.Shell

/**
 * Accessibility-free trigger source for the Automatic Overlay feature.
 *
 * While accessibility is disabled, this lightweight foreground service polls
 * the current foreground app through the privileged backends (ROOT: libsu
 * dumpsys, SHIZUKU: shell-uid user service) and feeds the result into
 * [AutoOverlayController], so the launcher dot still appears over a
 * user-selected app opened from the home screen. Pure MediaProjection has no
 * privileged backend, so the watcher stops itself in that mode.
 */
class AutoOverlayWatcherService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    private var poller: Runnable? = null
    private var windowPoller: Runnable? = null
    private var shizukuEngine: ShizukuCaptureEngine? = null
    @Volatile private var lastFed: String? = null
    private var mpStrikes = 0
    private var smallWindowStrikes = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AutoOverlayController.init(applicationContext)
        startForegroundCompat()
        pollThread = HandlerThread("lsfg-auto-overlay").also { it.start() }
        pollHandler = Handler(pollThread!!.looper)
        AutoOverlayController.setForegroundRefreshHook { done ->
            pollHandler?.post {
                val pkg = pollOnce(force = true)
                main.post { done(pkg) }
            }
        }
        schedulePoll(FIRST_DELAY_MS)
        scheduleWindowCheck(WINDOW_CHECK_FIRST_DELAY_MS)
        LsfgLog.i(TAG, "auto overlay watcher started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        poller?.let { pollHandler?.removeCallbacks(it) }
        poller = null
        windowPoller?.let { pollHandler?.removeCallbacks(it) }
        windowPoller = null
        pollHandler = null
        pollThread?.quitSafely()
        pollThread = null
        AutoOverlayController.setForegroundRefreshHook(null)
        runCatching { shizukuEngine?.stop() }
        shizukuEngine = null
        LsfgLog.i(TAG, "auto overlay watcher stopped")
        super.onDestroy()
    }

    private fun schedulePoll(firstDelayMs: Long) {
        val r = object : Runnable {
            override fun run() {
                pollOnce()
                pollHandler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        poller = r
        pollHandler?.postDelayed(r, firstDelayMs)
    }

    private fun scheduleWindowCheck(firstDelayMs: Long) {
        val r = object : Runnable {
            override fun run() {
                checkWindowingOnce()
                pollHandler?.postDelayed(this, WINDOW_CHECK_INTERVAL_MS)
            }
        }
        windowPoller = r
        pollHandler?.postDelayed(r, firstDelayMs)
    }

    private fun pollOnce(force: Boolean = false): String? {
        val appCtx = applicationContext
        // A transient read failure (concurrent prefs rewrite, first-load race)
        // must NOT be misread as MediaProjection: skip this round and retry.
        val source = runCatching { LsfgPreferences(appCtx).load().captureSource }
            .onFailure { LsfgLog.w(TAG, "config read failed - skipping poll", it) }
            .getOrNull()
        if (source == null) return null
        if (source == CaptureSource.MEDIA_PROJECTION) {
            // Require consecutive confirmations so a one-off bad read can't
            // kill the watcher; genuine MP stops after ~3 polls (9s).
            mpStrikes++
            if (mpStrikes >= 3) {
                LsfgLog.i(TAG, "capture source is MediaProjection - no privileged probe, stopping watcher")
                main.post { stopSelf() }
            }
            return null
        }
        mpStrikes = 0
        val pkg: String? = when (source) {
            CaptureSource.ROOT -> runCatching {
                ForegroundProbe.parse(
                    Shell.cmd("dumpsys activity activities").exec().out.joinToString("\n"),
                ) ?: ForegroundProbe.parse(
                    Shell.cmd("dumpsys window windows").exec().out.joinToString("\n"),
                )
            }.getOrNull()
            CaptureSource.SHIZUKU -> {
                val engine = shizukuEngine ?: ShizukuCaptureEngine(appCtx).also {
                    shizukuEngine = it
                    it.bindForProbe()
                }
                engine.getForegroundPackage()
            }
            CaptureSource.MEDIA_PROJECTION -> null
        }
        if (pkg != null && (force || pkg != lastFed)) {
            if (pkg != lastFed) LsfgLog.i(TAG, "foreground -> $pkg")
            lastFed = pkg
            main.post { AutoOverlayController.onForegroundPackage(appCtx, pkg) }
        }
        return pkg
    }

    // Auto-end the session when the target is no longer fullscreen
    // (freeform small window / split screen / PIP): UID-filtered capture
    // only contains the target's own layers, so everything outside its
    // window renders black over the rest of the screen.
    // NOTE: the foreground probe cannot see the target once it is in a
    // ColorOS small window (double topResumedActivity, null parses), so
    // the windowing probe must target the session target directly and
    // must not depend on the foreground pkg. Runs on its own fast loop
    // (WINDOW_CHECK_INTERVAL_MS) so detection latency stays ~2s; when no
    // session is active this is a no-op. Two consecutive strikes before
    // acting, unknown states fail open.
    private fun checkWindowingOnce() {
        val appCtx = applicationContext
        val sessionTarget = LsfgForegroundService.activeSessionTarget ?: run {
            smallWindowStrikes = 0
            return
        }
        val source = runCatching { LsfgPreferences(appCtx).load().captureSource }
            .onFailure { LsfgLog.w(TAG, "config read failed - skipping window check", it) }
            .getOrNull()
        val mode = when (source) {
            CaptureSource.SHIZUKU -> shizukuEngine?.getTargetWindowingMode(sessionTarget)
            CaptureSource.ROOT -> runCatching {
                ForegroundProbe.parseWindowingState(
                    Shell.cmd("dumpsys window windows").exec().out.joinToString("\n"),
                    sessionTarget,
                )
            }.getOrNull()
            else -> null
        }
        if (mode != null && mode != "fullscreen") {
            smallWindowStrikes++
            if (smallWindowStrikes >= 2) {
                smallWindowStrikes = 0
                LsfgLog.i(TAG, "target $sessionTarget windowing=$mode - auto-ending session")
                main.post {
                    runCatching {
                        Toast.makeText(appCtx, R.string.auto_overlay_small_window_stop, Toast.LENGTH_LONG).show()
                    }
                    LsfgForegroundService.requestStopFromWatcher(appCtx)
                }
            }
        } else {
            smallWindowStrikes = 0
        }
    }

    private fun startForegroundCompat() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.auto_overlay_watcher_notif),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.auto_overlay_watcher_notif))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val TAG = "AutoOverlayWatcher"
        private const val CHANNEL_ID = "lsfg_auto_overlay"
        private const val NOTIF_ID = 2001
        private const val POLL_INTERVAL_MS = 3000L
        private const val FIRST_DELAY_MS = 1500L
        private const val WINDOW_CHECK_INTERVAL_MS = 1000L
        private const val WINDOW_CHECK_FIRST_DELAY_MS = 1500L

        fun ensureStarted(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, AutoOverlayWatcherService::class.java)) }
                .onFailure { LsfgLog.w(TAG, "ensureStarted failed", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, AutoOverlayWatcherService::class.java)) }
        }
    }
}
