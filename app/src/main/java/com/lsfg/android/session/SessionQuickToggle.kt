package com.lsfg.android.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lsfg.android.R
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.ui.MainActivity

/**
 * Standby "quick toggle" card in the notification shade.
 *
 * It is rendered by [AutoOverlayWatcherService] as that service's **foreground
 * notification** ([NOTIFICATION_ID]), i.e. there is exactly one card for the
 * whole app while no session runs:
 *
 *  - no session: "待机 — 目标：X" with [开始会话] / [刷新当前应用];
 *  - session running: plain status line, no actions (the live session's own
 *    foreground notification carries 结束会话);
 *  - MediaProjection mode: plain status line — quick start cannot fulfil its
 *    promise because the consent dialog needs the app.
 *
 * Why one card and not a notification group: grouping two cards (this one plus
 * a separate watcher notification) made ColorOS collapse the whole group into a
 * single row, which hid every action button behind an extra expand tap. Hosting
 * the actions on the (always-present) foreground notification removes the
 * second card entirely, so nothing can be folded away.
 */
object SessionQuickToggle {

    private const val TAG = "QuickToggle"
    private const val CHANNEL_ID = "lsfg_quick_toggle_v3"

    /** Superseded channels; deleted on first run (Android never lets an app
     *  raise an existing channel's importance, and MIN/LOW cards get folded). */
    private val LEGACY_CHANNEL_IDS = listOf("lsfg_quick_toggle", "lsfg_quick_toggle_v2")

    /** Foreground notification id — shared with [AutoOverlayWatcherService]. */
    const val NOTIFICATION_ID = 3001

    private const val RC_OPEN_APP = 1
    private const val RC_QUICK_START = 2
    private const val RC_REFRESH = 4

    /** Action fired by the "Refresh current app" button; handled by
     *  [SessionQuickToggleReceiver], which reuses the launcher dot's
     *  privileged foreground probe. */
    const val ACTION_REFRESH_TARGET = "com.lsfg.android.action.REFRESH_TARGET"

    /**
     * Set by [AutoOverlayWatcherService] while it is alive: the service owns the
     * notification (it must, being a foreground service), so show()/hide() only
     * ask it to re-render instead of posting a second card.
     */
    fun interface Host {
        /** true = standby card with actions, false = plain status line. */
        fun render(withActions: Boolean)
    }

    @Volatile
    private var host: Host? = null

    fun setHost(value: Host?) {
        host = value
    }

    /** Human-readable app label, falling back to the raw package name. */
    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** True when [pkg] is the device launcher (ACTION_MAIN + CATEGORY_HOME).
     *  A launcher is never a usable capture target. */
    fun isHomePackage(ctx: Context, pkg: String): Boolean = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == pkg
    }.getOrDefault(false)

    private fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.quick_toggle_channel),
                    // DEFAULT + no sound: MIN/LOW land in ColorOS's "unimportant"
                    // bucket, which is rendered collapsed without action buttons.
                    // DEFAULT still never makes a sound here because the channel
                    // sound is explicitly nulled (and it is not HIGH, so no
                    // heads-up either).
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
            LEGACY_CHANNEL_IDS.forEach { id ->
                runCatching { mgr.deleteNotificationChannel(id) }
            }
        }
    }

    /** True when the standby actions can actually be fulfilled right now. */
    fun applicable(ctx: Context): Boolean {
        // A live session already owns the shade via its own FGS notification.
        if (LsfgForegroundService.activeSessionTarget != null) return false
        // MediaProjection sessions need the consent dialog — quick start
        // cannot fulfil its promise, so don't advertise it.
        val source = runCatching { LsfgPreferences(ctx).load().captureSource }
            .getOrDefault(CaptureSource.MEDIA_PROJECTION)
        return source != CaptureSource.MEDIA_PROJECTION
    }

    /**
     * Builds the single standby/status notification.
     *
     * @param withActions true for the "待机 — 目标：X" card with 开始会话 /
     *        refresh; false for the plain status line shown while a session runs
     *        or in MediaProjection mode.
     */
    fun build(ctx: Context, withActions: Boolean): Notification {
        val appCtx = ctx.applicationContext
        ensureChannel(appCtx)

        val openApp = PendingIntent.getActivity(
            appCtx, RC_OPEN_APP,
            Intent(appCtx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)

        if (!withActions) {
            builder.setContentTitle(appCtx.getString(R.string.auto_overlay_watcher_notif))
            return builder.build()
        }

        val prefs = runCatching { LsfgPreferences(appCtx).load() }.getOrNull()
        val target = prefs?.targetPackage
        val targetText = if (target != null && !isHomePackage(appCtx, target)) {
            appCtx.getString(R.string.quick_toggle_target_text, appLabel(appCtx, target))
        } else {
            appCtx.getString(R.string.quick_toggle_no_target)
        }

        val startPi = PendingIntent.getService(
            appCtx, RC_QUICK_START,
            Intent(appCtx, LsfgForegroundService::class.java)
                .setAction(LsfgForegroundService.ACTION_QUICK_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Re-probe the foreground app and retarget the standby session — same
        // mechanism as the launcher dot's "Refresh current app".
        val refreshPi = PendingIntent.getBroadcast(
            appCtx, RC_REFRESH,
            Intent(appCtx, SessionQuickToggleReceiver::class.java)
                .setAction(ACTION_REFRESH_TARGET),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // No "end session" action here on purpose: this card only carries
        // actions while no session exists, so the button could never do
        // anything but start the service just to stop it again. The live
        // session's own foreground notification carries that action.
        return builder
            .setContentTitle(appCtx.getString(R.string.quick_toggle_title))
            .setContentText(targetText)
            .addAction(0, appCtx.getString(R.string.session_start), startPi)
            .addAction(0, appCtx.getString(R.string.quick_toggle_refresh), refreshPi)
            .build()
    }

    /** Ask for the standby card (or post it standalone if no service hosts it). */
    fun show(ctx: Context) {
        val appCtx = ctx.applicationContext
        val h = host
        if (h != null) {
            h.render(applicable(appCtx))
            return
        }
        if (!applicable(appCtx)) return
        runCatching {
            NotificationManagerCompat.from(appCtx).notify(NOTIFICATION_ID, build(appCtx, true))
        }.onFailure { LsfgLog.w(TAG, "show quick-toggle notification failed", it) }
    }

    /**
     * Session started (or host going away): stop advertising the standby card.
     * While a session runs the host drops its foreground notification entirely
     * instead of downgrading it to a "自动悬浮层待机中" status line — the session's
     * own notification is the only card the user needs.
     */
    fun hide(ctx: Context) {
        val appCtx = ctx.applicationContext
        val h = host
        if (h != null) {
            h.render(false)
            return
        }
        runCatching { NotificationManagerCompat.from(appCtx).cancel(NOTIFICATION_ID) }
    }
}
