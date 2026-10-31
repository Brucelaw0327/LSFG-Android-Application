package com.lsfg.android.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lsfg.android.R
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.ui.MainActivity

/**
 * Persistent "quick toggle" notification for the notification shade.
 *
 * Two purposes, mirroring the in-game settings drawer:
 *  1. While NO session is running: a silent standby notification with a
 *     [LsfgForegroundService.ACTION_QUICK_START] action that launches a
 *     Shizuku/Root session for the last-selected target app without opening
 *     the main UI (MediaProjection mode hides this notification entirely —
 *     consent requires the projection dialog, which only the app can drive).
 *  2. While a session IS running the notification is removed: the foreground
 *     session notification carries the explicit "end session" action instead.
 *
 * Lifecycle anchors:
 *  - shown from [com.lsfg.android.LsfgApplication] process start and from
 *    [AutoOverlayWatcherService] (the always-on foreground anchor);
 *  - hidden by [LsfgForegroundService] when a session takes over;
 *  - re-shown from [LsfgForegroundService.onDestroy] when the session ends.
 */
object SessionQuickToggle {

    private const val TAG = "QuickToggle"
    private const val CHANNEL_ID = "lsfg_quick_toggle_v2"
    /** Pre-LOW-importance channel; deleted on first run so the new importance
     *  takes effect (Android never lets an app raise an existing channel's
     *  importance). */
    private const val LEGACY_CHANNEL_ID = "lsfg_quick_toggle"
    private const val NOTIF_ID = 3001
    private const val RC_OPEN_APP = 1
    private const val RC_QUICK_START = 2
    private const val RC_REFRESH = 4

    /**
     * Shared notification group. Without an explicit group the system folds
     * this notification together with the watcher's foreground notification
     * into a synthetic "Aggregate_AlertingSection" summary — the result mixed
     * this card's title with the watcher's body text and hid all action
     * buttons behind an extra expand tap. Both notifications join [GROUP_KEY]
     * instead, with this one as the summary, so the buttons stay reachable.
     */
    const val GROUP_KEY = "com.lsfg.android.LSFG_STATUS"

    /** Action fired by the "Refresh current app" notification button. Handled
     *  by [SessionQuickToggleReceiver], which reuses the launcher dot's
     *  privileged foreground probe. */
    const val ACTION_REFRESH_TARGET = "com.lsfg.android.action.REFRESH_TARGET"

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
                    // LOW instead of MIN: MIN is filed under the "silent
                    // notifications" bucket on ColorOS, which hides the card
                    // behind another expand step. LOW is still silent (no
                    // sound, no heads-up) but stays in the main list.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            runCatching { mgr.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        }
    }

    /** True when this notification can be useful right now. */
    private fun applicable(ctx: Context): Boolean {
        // A live session already owns the shade via its FGS notification.
        if (LsfgForegroundService.activeSessionTarget != null) return false
        // MediaProjection sessions need the consent dialog — quick start
        // cannot fulfil its promise, so don't advertise it.
        val source = runCatching { LsfgPreferences(ctx).load().captureSource }
            .getOrDefault(CaptureSource.MEDIA_PROJECTION)
        return source != CaptureSource.MEDIA_PROJECTION
    }

    /** Post (or refresh) the standby quick-toggle notification. No-op when a
     *  session is running or the capture source is MediaProjection. */
    fun show(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (!applicable(appCtx)) return
        runCatching {
            ensureChannel(appCtx)
            val prefs = LsfgPreferences(appCtx).load()
            val target = prefs.targetPackage
            val targetText = if (target != null && !isHomePackage(appCtx, target)) {
                appCtx.getString(R.string.quick_toggle_target_text, appLabel(appCtx, target))
            } else {
                appCtx.getString(R.string.quick_toggle_no_target)
            }

            val openApp = PendingIntent.getActivity(
                appCtx, RC_OPEN_APP,
                Intent(appCtx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val startPi = PendingIntent.getService(
                appCtx, RC_QUICK_START,
                Intent(appCtx, LsfgForegroundService::class.java)
                    .setAction(LsfgForegroundService.ACTION_QUICK_START),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Re-probe the foreground app and retarget the standby session —
            // same mechanism as the launcher dot's "Refresh current app".
            val refreshPi = PendingIntent.getBroadcast(
                appCtx, RC_REFRESH,
                Intent(appCtx, SessionQuickToggleReceiver::class.java)
                    .setAction(ACTION_REFRESH_TARGET),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            // No "end session" action here on purpose: show() only runs while
            // no session exists (applicable()), so the button could never do
            // anything but start the service just to stop it again. The live
            // session's own foreground notification carries that action.
            val notif = NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setContentTitle(appCtx.getString(R.string.quick_toggle_title))
                .setContentText(targetText)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setContentIntent(openApp)
                .addAction(0, appCtx.getString(R.string.session_start), startPi)
                .addAction(0, appCtx.getString(R.string.quick_toggle_refresh), refreshPi)
                .build()
            NotificationManagerCompat.from(appCtx).notify(NOTIF_ID, notif)
        }.onFailure { LsfgLog.w(TAG, "show quick-toggle notification failed", it) }
    }

    /** Remove the standby notification (session starting, or feature host gone). */
    fun hide(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx.applicationContext).cancel(NOTIF_ID) }
    }
}
